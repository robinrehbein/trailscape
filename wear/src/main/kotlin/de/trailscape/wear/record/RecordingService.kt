package de.trailscape.wear.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAccuracy
import androidx.health.services.client.data.LocationData
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import de.trailscape.core.Befehl
import de.trailscape.core.PFAD_BEFEHL_AN_TELEFON
import de.trailscape.core.SensorSample
import de.trailscape.core.formatDuration
import de.trailscape.core.formatKm
import de.trailscape.core.kodiereBefehl
import de.trailscape.wear.MainActivity
import de.trailscape.wear.R
import de.trailscape.wear.comm.PhoneLink
import de.trailscape.wear.comm.SensorSender
import de.trailscape.wear.exercise.ExerciseRecorder
import de.trailscape.wear.exercise.ermittleFaehigkeiten
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Der Vordergrunddienst, der eine Ausfahrt aufzeichnet.
 *
 * Haelt den [androidx.health.services.client.ExerciseClient], puffert
 * Sensordaten fuer das Telefon ([SensorSender], siehe dortiger Klassen-KDoc)
 * und schreibt ein schlankes Ereignisprotokoll ([RecordingJournal]).
 *
 * Warum ueberhaupt ein Vordergrunddienst und KEIN WakeLock: Health Services
 * haelt die Uebung genau so lange am Leben, wie ein Dienst mit
 * `foregroundServiceType="health|location"` laeuft — mehr braucht es nicht.
 * Ein zusaetzlicher WakeLock wuerde die CPU wachhalten und unnoetig Akku
 * kosten, ohne dass Health Services das verlangt. Der Dienst darf und soll
 * schlafen; die Daten kommen trotzdem, notfalls gebatcht.
 *
 * Der Dienst ist bewusst NICHT `START_STICKY` und stellt sich nach einem
 * Prozesstod auch nicht wieder her — die laufende Ausfahrt bleibt dann auf
 * dem Telefon (dessen eigene Aufzeichnung unabhaengig weiterlaeuft) und im
 * schlanken [RecordingJournal] auf der Uhr nachvollziehbar; ein automatischer
 * Neustart der Uebung ohne Nutzerinteraktion waere riskanter als gar keiner
 * (doppelt gezaehlte Distanz, verwaiste Health-Services-Session).
 *
 * ## Wer meldet was ans Telefon
 * Dieser Dienst — und ausschliesslich er — meldet jeden TATSAECHLICH
 * vollzogenen Zustandswechsel (Start/Pause/Weiter/Ende) per [Befehl] an
 * [PFAD_BEFEHL_AN_TELEFON], unabhaengig davon, ob ein Tipp auf der Uhr oder
 * ein vom Telefon weitergereichter Fernbefehl (siehe
 * [de.trailscape.wear.comm.CommandListenerService]) ihn ausgeloest hat —
 * siehe [RecordingStatus]s Klassen-KDoc fuer die Begruendung dieser
 * Symmetrie.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var journal: RecordingJournal
    private lateinit var recorder: ExerciseRecorder
    private lateinit var sensorSender: SensorSender

    /**
     * Wall-Clock-Zeitpunkt des letzten Bootvorgangs.
     *
     * Health Services stempelt jeden Datenpunkt mit `timeDurationFromBoot` —
     * einem Offset seit dem Systemstart, NICHT mit einer Uhrzeit. Erst dieser
     * Bezugspunkt macht daraus einen Zeitstempel, den das Telefon mit seiner
     * eigenen Zeitachse abgleichen kann (siehe `LocationFusion` in `:core`,
     * die genau solche, leicht unsortiert eintreffenden Zeitstempel erwartet).
     */
    private lateinit var bootZeitpunkt: Instant

    private var startMs = 0L
    private var startElapsedMs = 0L
    private var pausiertSeitMs: Long? = null
    private var pausierteMs = 0L
    private var laeuft = false
    private var distanzM = 0.0
    private var letztesTempoMps: Double? = null
    private var benachrichtigungsZaehler = 0

    @Volatile
    private var letzteHf: Int? = null

    private var chronometerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        legeBenachrichtigungskanalAn()
        recorder = ExerciseRecorder(HealthServices.getClient(this).exerciseClient)
        sensorSender = SensorSender(applicationContext, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                gehInDenVordergrund()
                // Der Guard muss SYNCHRON gesetzt werden, bevor die Coroutine
                // loslaeuft: Zwei schnelle START-Aufrufe (z. B. ein Tipp
                // gefolgt von einem gerade eintreffenden Fernbefehl) kamen
                // sonst beide durch `!laeuft` (der Dispatch auf den IO-Thread
                // dauert spuerbar laenger) und bereiteten die Uebung doppelt
                // vor. Schlaegt die Vorbereitung fehl, setzt `scheitere()` den
                // Guard zurueck — ein spaeterer START kann es erneut versuchen.
                if (!laeuft) {
                    laeuft = true
                    scope.launch { beginneVersuch() }
                }
            }

            ACTION_PAUSE -> scope.launch { pausiere() }
            ACTION_RESUME -> scope.launch { setzeFort() }
            ACTION_STOP -> scope.launch { beendeVersuch("benutzer") }
            else -> stopSelf()
        }
        // Kein START_STICKY: siehe Klassendoc.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        chronometerJob?.cancel()
        sensorSender.stoppe()
        scope.cancel()
        super.onDestroy()
    }

    // --- Ablauf --------------------------------------------------------------

    private suspend fun beginneVersuch() {
        // `laeuft` steht bereits: onStartCommand setzt den Guard synchron
        // vor dem Start dieser Coroutine (Doppel-START-Schutz).
        RecordingStatus.zuruecksetzen()
        distanzM = 0.0
        pausierteMs = 0L
        pausiertSeitMs = null
        letztesTempoMps = null
        letzteHf = null

        startMs = System.currentTimeMillis()
        startElapsedMs = SystemClock.elapsedRealtime()
        bootZeitpunkt = Instant.ofEpochMilli(startMs - startElapsedMs)

        val verzeichnis = getExternalFilesDir(null) ?: filesDir
        journal = RecordingJournal(RecordingJournal.datei(verzeichnis, startMs))

        try {
            val client = HealthServices.getClient(this).exerciseClient
            val bericht = ermittleFaehigkeiten(client)
            RecordingStatus.setzeBericht(bericht)

            journal.beginn(
                startMs = startMs,
                hinweis = "${bericht.unterstuetzteNamen.size} Datentypen, absolute Hoehe: " +
                    if (bericht.hatAbsoluteHoehe) "ja" else "nein",
            )

            if (!bericht.radfahrenUnterstuetzt || bericht.angeforderte.isEmpty()) {
                scheitere("Uhr meldet keine Radfahr-Fähigkeiten")
                return
            }

            sensorSender.starte()

            // Erst zuhoeren, dann vorwaermen, dann starten. Andersherum gingen
            // Callback-Ereignisse der Aufwaermphase verloren.
            scope.launch { sammleEreignisse() }

            RecordingStatus.setzePhase(RecordingStatus.Phase.VORBEREITEN)
            recorder.vorbereiten(bericht.angeforderte)
            journal.notiz(System.currentTimeMillis(), "prepareExercise abgeschlossen")

            recorder.starten(bericht.angeforderte)
            journal.notiz(System.currentTimeMillis(), "startExercise abgeschlossen")

            // Der Chronometer laeuft ab dem tatsaechlichen Start der Uebung,
            // nicht ab dem Vorwaermen.
            startElapsedMs = SystemClock.elapsedRealtime()
            RecordingStatus.setzePhase(RecordingStatus.Phase.LAEUFT)
            benachrichtigeTelefon(Befehl.START)

            starteChronometer()
        } catch (e: Exception) {
            scheitere(e.message ?: e::class.java.simpleName)
        }
    }

    private suspend fun sammleEreignisse() {
        recorder.ereignisse().collect { ereignis ->
            when (ereignis) {
                is ExerciseRecorder.Ereignis.Registriert ->
                    journal.notiz(System.currentTimeMillis(), "Callback registriert")

                is ExerciseRecorder.Ereignis.RegistrierungFehlgeschlagen -> {
                    journal.notiz(
                        System.currentTimeMillis(),
                        "Callback-Registrierung fehlgeschlagen: ${ereignis.ursache}",
                    )
                    scheitere("Callback nicht registriert: ${ereignis.ursache.message}")
                }

                // Sensorverfuegbarkeit und Rundenzusammenfassungen waren fuer
                // die Geraeteanalyse des Spikes interessant (siehe
                // RecordingJournal-KDoc), nicht fuer den Betrieb dieser App —
                // bewusst nicht mehr protokolliert.
                is ExerciseRecorder.Ereignis.Verfuegbarkeit,
                is ExerciseRecorder.Ereignis.Runde,
                -> Unit

                is ExerciseRecorder.Ereignis.Aktualisierung -> verarbeite(ereignis.update)
            }
        }
    }

    private suspend fun verarbeite(update: ExerciseUpdate) {
        if (update.exerciseStateInfo.state.isEnded) {
            // Health Services kann eine Uebung auch von sich aus beenden
            // (System-Eingriff, ein anderer Trainingsdienst uebernimmt). Ohne
            // diesen Zweig liefe der Vordergrunddienst weiter, obwohl die
            // Uebung, auf die er sich stuetzt, gar nicht mehr existiert.
            beendeVersuch("health_services")
            return
        }
        verarbeiteMesswerte(update)
        verarbeitePositionen(update)
    }

    private fun verarbeiteMesswerte(update: ExerciseUpdate) {
        for (punkt in update.latestMetrics.sampleDataPoints) {
            if (punkt.dataType == DataType.LOCATION) continue
            val wert = (punkt.value as? Number)?.toDouble() ?: continue
            val zeitMs = bootZeitpunkt.toEpochMilli() + punkt.timeDurationFromBoot.toMillis()

            when (punkt.dataType) {
                DataType.HEART_RATE_BPM -> {
                    letzteHf = wert.toInt()
                    RecordingStatus.setzeHf(letzteHf)
                    // HF-Probe unabhaengig vom naechsten GPS-Fix einreihen:
                    // Sonst haengt die Frische der Telefon-Anzeige an der
                    // GPS-Rate, die bei dunklem Display auf ~150 s absackt
                    // (siehe ExerciseRecorder-KDoc) — die Herzfrequenz soll
                    // trotzdem zeitnah ankommen.
                    sensorSender.probe(SensorSample(zeitMs = zeitMs, hf = letzteHf))
                }

                // Health Services liefert m/s; SensorSample.tempoMps auch,
                // die Uhr-Anzeige will km/h.
                DataType.SPEED -> {
                    letztesTempoMps = wert
                    RecordingStatus.setzeTempo(wert * 3.6)
                }
            }
        }

        for (punkt in update.latestMetrics.intervalDataPoints) {
            if (punkt.dataType != DataType.DISTANCE) continue
            // DISTANCE ist ein Zuwachs pro Intervall, kein Gesamtwert.
            distanzM += (punkt.value as? Number)?.toDouble() ?: continue
            RecordingStatus.setzeDistanz(distanzM / 1000.0)
        }

        for (punkt in update.latestMetrics.cumulativeDataPoints) {
            if (punkt.dataType != DataType.DISTANCE_TOTAL) continue
            // Der Gesamtwert der Uhr sticht die selbst summierten Zuwaechse
            // oben — er ueberlebt auch eine verpasste Zustellung.
            distanzM = punkt.total.toDouble()
            RecordingStatus.setzeDistanz(distanzM / 1000.0)
        }
    }

    private fun verarbeitePositionen(update: ExerciseUpdate) {
        val gemeldete = update.latestMetrics.getData(DataType.LOCATION)
        if (gemeldete.isEmpty()) return

        for (punkt in gemeldete) {
            val ort: LocationData = punkt.value
            // altitude ist Double.NaN, wenn die Uhr keine absolute Hoehe
            // liefert — ungeprueft weitergereicht wuerde daraus im
            // Wire-Format ein "NaN", das kein JSON-Parser annimmt.
            val hoehe = ort.altitude.takeIf { !it.isNaN() }
            val genauigkeit = punkt.accuracy as? LocationAccuracy
            val zeitMs = bootZeitpunkt.toEpochMilli() + punkt.timeDurationFromBoot.toMillis()

            sensorSender.probe(
                SensorSample(
                    zeitMs = zeitMs,
                    lat = ort.latitude,
                    lon = ort.longitude,
                    hoeheM = hoehe,
                    genauigkeitM = genauigkeit?.horizontalPositionErrorMeters,
                    tempoMps = letztesTempoMps,
                    hf = letzteHf,
                ),
            )
        }
    }

    private suspend fun pausiere() {
        if (!laeuft || pausiertSeitMs != null) return
        runCatching { recorder.pausieren() }
            .onFailure { journal.notiz(System.currentTimeMillis(), "pauseExercise fehlgeschlagen: $it") }
        pausiertSeitMs = SystemClock.elapsedRealtime()
        journal.notiz(System.currentTimeMillis(), "pausiert")
        RecordingStatus.setzePhase(RecordingStatus.Phase.PAUSIERT)
        benachrichtigeTelefon(Befehl.PAUSE)
    }

    private suspend fun setzeFort() {
        val seit = pausiertSeitMs ?: return
        runCatching { recorder.fortsetzen() }
            .onFailure { journal.notiz(System.currentTimeMillis(), "resumeExercise fehlgeschlagen: $it") }
        pausierteMs += SystemClock.elapsedRealtime() - seit
        pausiertSeitMs = null
        journal.notiz(System.currentTimeMillis(), "fortgesetzt")
        RecordingStatus.setzePhase(RecordingStatus.Phase.LAEUFT)
        benachrichtigeTelefon(Befehl.WEITER)
    }

    private suspend fun beendeVersuch(grund: String) {
        if (!laeuft) {
            stopSelf()
            return
        }
        laeuft = false
        chronometerJob?.cancel()
        sensorSender.stoppe()

        runCatching { recorder.beenden() }
            .onFailure { journal.notiz(System.currentTimeMillis(), "endExercise fehlgeschlagen: $it") }

        journal.ende(System.currentTimeMillis(), grund)
        RecordingStatus.setzePhase(RecordingStatus.Phase.BEENDET)
        benachrichtigeTelefon(Befehl.STOPP)

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheitere(text: String) {
        RecordingStatus.setzeFehler(text)
        RecordingStatus.setzePhase(RecordingStatus.Phase.FEHLER)
        journal.notiz(System.currentTimeMillis(), "Abbruch: $text")
        journal.ende(System.currentTimeMillis(), "fehler")
        laeuft = false
        sensorSender.stoppe()
        benachrichtigeTelefon(Befehl.STOPP)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Meldet einen tatsaechlich vollzogenen Zustandswechsel ans Telefon — siehe Klassen-KDoc. */
    private fun benachrichtigeTelefon(cmd: String) {
        PhoneLink.sendeBestEffort(scope, applicationContext, PFAD_BEFEHL_AN_TELEFON, kodiereBefehl(Befehl(cmd)))
    }

    // --- Chronometer -----------------------------------------------------------

    /**
     * Eigener Chronometer, sekuendlich. Siehe [RecordingStatus.laufzeitMs] fuer
     * die Begruendung, warum die Dauer nicht aus dem `ExerciseUpdate` kommt.
     *
     * Treibt nebenbei die Benachrichtigung: eigener Takt statt an GPS-Fixe
     * gekoppelt, sonst bliebe der Text bei schwachem GPS oder waehrend einer
     * Pause stehen, obwohl die Ausfahrt weiterlaeuft.
     */
    private fun starteChronometer() {
        chronometerJob?.cancel()
        benachrichtigungsZaehler = 0
        chronometerJob = scope.launch {
            while (isActive) {
                val offen = pausiertSeitMs?.let { SystemClock.elapsedRealtime() - it } ?: 0L
                RecordingStatus.setzeLaufzeit(
                    SystemClock.elapsedRealtime() - startElapsedMs - pausierteMs - offen,
                )

                benachrichtigungsZaehler++
                if (benachrichtigungsZaehler % BENACHRICHTIGUNG_ALLE_S == 0) {
                    aktualisiereBenachrichtigung()
                }
                delay(1_000)
            }
        }
    }

    // --- Vordergrund + Ongoing Activity --------------------------------------

    private fun gehInDenVordergrund() {
        val typ = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, baueBenachrichtigung(), typ)
    }

    private fun aktualisiereBenachrichtigung() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, baueBenachrichtigung())
    }

    /**
     * Vordergrund-Benachrichtigung mit aufgesetzter Ongoing Activity.
     *
     * Die Ongoing Activity ist reine Jetpack-Verzierung der Notification —
     * es gibt keine eigene Berechtigung dafuer. Was sie braucht, ist
     * POST_NOTIFICATIONS, und zwar fuer die Notification selbst.
     */
    private fun baueBenachrichtigung(): android.app.Notification {
        val tippIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val laufzeitS = (RecordingStatus.laufzeitMs.value / 1000).toInt()
        val text = "${formatDuration(laufzeitS)} · ${formatKm(RecordingStatus.distanzKm.value)} km"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(tippIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher)
            .setTouchIntent(tippIntent)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }

    private fun legeBenachrichtigungskanalAn() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
            },
        )
    }

    companion object {
        const val ACTION_START = "de.trailscape.wear.START"
        const val ACTION_PAUSE = "de.trailscape.wear.PAUSE"
        const val ACTION_RESUME = "de.trailscape.wear.RESUME"
        const val ACTION_STOP = "de.trailscape.wear.STOP"

        private const val CHANNEL_ID = "aufzeichnung"
        private const val NOTIFICATION_ID = 1

        /** Alle wie vielen Sekunden-Ticks des Chronometers die Benachrichtigung neu gebaut wird. */
        private const val BENACHRICHTIGUNG_ALLE_S = 15
    }
}
