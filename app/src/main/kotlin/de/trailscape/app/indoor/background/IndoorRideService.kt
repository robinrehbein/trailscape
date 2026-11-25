package de.trailscape.app.indoor.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.i18n.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * # Vordergrunddienst der Indoor-Fahrt (docs/indoor/hintergrund.md)
 *
 * Haelt den Prozess am Leben, solange eine Fahrt laeuft und „Im Hintergrund
 * weiterfahren" an ist — die Fahrt selbst (Trainer, Steuerbefehle, Sitzung,
 * Sicherung) lebt in der [de.trailscape.app.indoor.IndoorRideRuntime], die der
 * [IndoorRideHost] haelt. Der Dienst ist nur die Huelle darum:
 *
 *  * **Diensttyp `connectedDevice`**: Er haelt die Verbindung zum
 *    Rollentrainer (Bluetooth LE, FTMS) und schickt ihm laufend Steigung bzw.
 *    Zielleistung. Ab Android 14 braucht das die Berechtigung
 *    `FOREGROUND_SERVICE_CONNECTED_DEVICE` (Manifest) und eine erteilte
 *    `BLUETOOTH_CONNECT` ([IndoorBackgroundLogic.canStartService]).
 *  * **Benachrichtigung** mit Leistung, Zeit und Distanz, Aktionen
 *    Pause/Weiter und Beenden; ein Tipp fuehrt zurueck in die Fahrt.
 *  * **Teil-Wecksperre** (`PARTIAL_WAKE_LOCK`), solange der Dienst laeuft:
 *    Die Fahrt ist eine Simulation mit 10 Takten je Sekunde, die dem Trainer
 *    laufend die Steigung schickt — schlaeft die CPU bei gesperrtem Display
 *    ein, rollte man auf der Stelle. Mit Zeitlimit ([WAKELOCK_TIMEOUT_MS]),
 *    bei jeder Aktualisierung erneuert; endet mit der Fahrt.
 *  * **`START_NOT_STICKY`**: Stirbt der Prozess, gibt es keine Fahrt mehr,
 *    die ein Neustart fortsetzen koennte; die Sicherung
 *    ([IndoorRideJournal]) wird beim naechsten Start der App zur Tour.
 *  * **`stopWithTask="false"`** (Manifest): Wischt man die App aus den
 *    Letzten Apps, faehrt man weiter; zurueck geht es ueber die
 *    Benachrichtigung oder das App-Symbol.
 *
 * Der Dienst beendet sich selbst, sobald der Halter keine Fahrt mehr hat.
 */
class IndoorRideService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var inForeground = false
    private val host: IndoorRideHost get() = IndoorRideHost.shared

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> host.ride.value?.runtime?.pause()
            ACTION_RESUME -> host.ride.value?.runtime?.resume()
            ACTION_FINISH -> {
                // Nicht im Bereich des Dienstes: Der beendet sich, sobald die
                // Fahrt endet, und braeche sonst das Speichern ab.
                AppServices.appScope.launch(Dispatchers.Main.immediate) {
                    val outcome = runCatching { host.finishFromNotification() }.getOrNull()
                    outcome?.let { postOutcome(this@IndoorRideService, it) }
                }
            }
            else -> Unit
        }
        if (!inForeground) {
            // Erst in den Vordergrund, dann pruefen: Wer per
            // startForegroundService kam, muss startForeground rufen, auch
            // wenn es nichts mehr zu tun gibt.
            if (!enterForeground() || host.ride.value == null) {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            inForeground = true
            acquireWakeLock()
            watch()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (e: Exception) {
        // Ab Android 14 ohne BLUETOOTH_CONNECT (SecurityException) oder ein
        // Start aus dem Hintergrund (ForegroundServiceStartNotAllowedException):
        // Die Fahrt laeuft weiter, pausiert aber wie frueher beim Verlassen der App.
        host.ride.value?.serviceFailed = true
        false
    }

    /** Folgt der Fahrt: Benachrichtigung einmal je Sekunde, Ende → Dienst beenden. */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            host.ride.collectLatest { ride ->
                if (ride == null) {
                    stopSelfSafely()
                    return@collectLatest
                }
                while (isActive) {
                    notifyProgress()
                    acquireWakeLock()
                    delay(UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    private fun notifyProgress() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Ohne POST_NOTIFICATIONS laeuft die Fahrt trotzdem.
        }
    }

    private fun buildNotification(): Notification {
        val texts = localized()
        val ride = host.ride.value
        val model = IndoorBackgroundLogic.modelOf(ride?.runtime?.state?.value)
        val routeName = ride?.routeName.orEmpty()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(IndoorBackgroundLogic.title(texts.resources, routeName, model))
            .setContentText(IndoorBackgroundLogic.text(texts.resources, languageOf(texts.resources.configuration), model))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openRideIntent(this))
        if (model.kind != IndoorBackgroundLogic.NotificationModel.Kind.FINISHING) {
            builder.addAction(
                0,
                texts.getString(if (model.showResume) R.string.idee05_notification_action_resume else R.string.idee05_notification_action_pause),
                commandIntent(if (model.showResume) ACTION_RESUME else ACTION_PAUSE, if (model.showResume) REQUEST_RESUME else REQUEST_PAUSE),
            )
            builder.addAction(0, texts.getString(R.string.idee05_notification_action_finish), commandIntent(ACTION_FINISH, REQUEST_FINISH))
        }
        return builder.build()
    }

    private fun commandIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, IndoorRideService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val lock = wakeLock ?: runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()?.also { wakeLock = it } ?: return
        // Nicht referenzgezaehlt: Jeder Aufruf verlaengert nur die Frist.
        runCatching { lock.acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { l -> runCatching { if (l.isHeld) l.release() } }
        wakeLock = null
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // War nicht im Vordergrund.
        }
        inForeground = false
        stopSelf()
    }

    companion object {
        const val ACTION_START = "de.trailscape.app.indoor.action.START"
        const val ACTION_PAUSE = "de.trailscape.app.indoor.action.PAUSE"
        const val ACTION_RESUME = "de.trailscape.app.indoor.action.RESUME"
        const val ACTION_FINISH = "de.trailscape.app.indoor.action.FINISH"

        /** Extra am Launcher-Intent: zurueck in den Indoor-Ablauf (die laufende Fahrt bzw. ihre Auswertung). */
        const val EXTRA_OPEN_INDOOR = "de.trailscape.app.extra.OPEN_INDOOR"

        private const val CHANNEL_ID = "indoor_ride"
        private const val RESULT_CHANNEL_ID = "indoor_ride_result"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val REQUEST_PAUSE = 51
        private const val REQUEST_RESUME = 52
        private const val REQUEST_FINISH = 53
        private const val REQUEST_OPEN = 54
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60_000L
        private const val WAKELOCK_TAG = "trailscape:indoor-ride"

        /**
         * Startet den Dienst fuer die gerade angemeldete Fahrt — aus dem
         * Vordergrund (beim Start der Fahrt), sonst verweigert Android 12+
         * den Start. `false`, wenn die Voraussetzungen fehlen.
         */
        fun start(context: Context): Boolean {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (!IndoorBackgroundLogic.canStartService(Build.VERSION.SDK_INT, granted)) return false
            return try {
                ContextCompat.startForegroundService(context, Intent(context, IndoorRideService::class.java).setAction(ACTION_START))
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Oeffnet die App im Indoor-Ablauf (Launcher-Intent, damit der Dienst den Aufbau der Oberflaeche nicht kennt). */
        fun openRideIntent(context: Context): PendingIntent? {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            intent.putExtra(EXTRA_OPEN_INDOOR, true)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                REQUEST_OPEN,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val texts = context.localized()
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, texts.getString(R.string.idee05_notification_channel), NotificationManager.IMPORTANCE_LOW),
                )
                manager.createNotificationChannel(
                    NotificationChannel(
                        RESULT_CHANNEL_ID,
                        texts.getString(R.string.idee05_notification_result_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }

        /** Meldet nach dem Ende ohne sichtbare App, was aus der Fahrt wurde. */
        fun postOutcome(context: Context, outcome: IndoorRideHost.Outcome) {
            val texts = context.localized()
            val body = texts.getString(
                when (outcome) {
                    is IndoorRideHost.Outcome.Saved -> R.string.idee05_result_saved
                    IndoorRideHost.Outcome.TooShort -> R.string.idee05_result_too_short
                    IndoorRideHost.Outcome.Failed -> R.string.idee05_result_failed
                    IndoorRideHost.Outcome.ShownInApp -> R.string.idee05_result_in_app
                },
            )
            postResult(context, texts.getString(R.string.idee05_result_title), body)
        }

        /** Eine einmalige Meldung (Ergebnis, Wiederherstellung); ein Tipp oeffnet die App im Indoor-Ablauf. */
        fun postResult(context: Context, title: String, body: String) {
            ensureChannels(context)
            try {
                val n = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setAutoCancel(true)
                    .apply { openRideIntent(context)?.let { setContentIntent(it) } }
                    .build()
                context.getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, n)
            } catch (e: Exception) {
                // Ohne POST_NOTIFICATIONS bleibt es bei der Tour in der Liste.
            }
        }
    }
}
