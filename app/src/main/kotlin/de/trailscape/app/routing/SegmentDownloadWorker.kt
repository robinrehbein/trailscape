package de.trailscape.app.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.core.parseSegmentTile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Der Hintergrundlauf, der Routing-Kacheln holt.
 *
 * ## Warum WorkManager und kein App-interner Vorgang
 * Eine Kachel ist bis zu 240 MB gross. Auf dem Handynetz sind das Minuten bis
 * Stunden — Zeit, in der die Nutzerin die App wegwischt, das Display sperrt
 * oder telefoniert. Eine Coroutine im `viewModelScope` waere in dem Moment
 * tot, eine im Anwendungs-Scope stirbt spaetestens mit dem Prozess, den
 * Android jederzeit einsammeln darf. WorkManager haelt den Auftrag in seiner
 * eigenen Datenbank, setzt ihn nach einem Neustart des Geraets von selbst
 * wieder auf (Vorbild und Nachweis: `reminder/ReminderScheduler.kt`) und
 * ueberlebt damit alles, was der Kartenkachel-Download in
 * `ui/map/OfflineRegions.kt` nicht ueberlebt.
 *
 * ## Warum ein Vordergrund-Lauf
 * WorkManager raeumt gewoehnlichen Arbeiten rund zehn Minuten ein und
 * stoppt sie danach. Das reicht fuer 240 MB im Mobilfunk nicht. Mit
 * [getForegroundInfo] laeuft die Arbeit als Vordergrunddienst mit sichtbarer
 * Benachrichtigung — die ehrliche Bauart fuer etwas, das lange laeuft und
 * Daten verbraucht: Sie ist sichtbar und jederzeit abbrechbar (Knopf in der
 * Benachrichtigung). Scheitert der Wechsel in den Vordergrund (Android 12+
 * erlaubt ihn nicht in jeder Lage), laeuft die Arbeit trotzdem weiter — nur
 * eben mit dem Zeitlimit. Weil der Download fortsetzbar ist, ist ein
 * Abschneiden kein Verlust: Der naechste Versuch setzt an der Stelle wieder
 * auf.
 *
 * ## Warum hier nichts entschieden wird
 * Ob Delta oder Vollabzug, ob ueberhaupt etwas zu tun ist — das entscheidet
 * `:core` ([de.trailscape.core.planSegmentUpdate]) und fuehrt
 * [SegmentDownloader] aus. Dieser Worker ist die Verkabelung: Eingabe lesen,
 * Fortschritt melden, Benachrichtigung pflegen, Ergebnis zurueckgeben.
 *
 * ## Wiederholung
 * Netzfehler sind voruebergehend, deshalb [Result.retry] — WorkManager
 * wiederholt mit wachsendem Abstand, und dank der Teildatei faengt der
 * Versuch nicht von vorn an. Nach [MAX_ATTEMPTS] Anlaeufen ist Schluss, damit
 * ein dauerhaft kaputter Zustand (Server weg, Speicher voll) nicht ewig im
 * Hintergrund weiterrumort.
 */
internal class SegmentDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = buildNotification(notificationText, progressPercent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Ab Android 10 muss der Typ genannt werden, ab Android 14 ist er
            // Pflicht und braucht zusaetzlich die Berechtigung
            // FOREGROUND_SERVICE_DATA_SYNC (siehe AndroidManifest.xml).
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppServices.init(applicationContext)

        val names = inputData.getStringArray(KEY_SEGMENTS)
            ?.filter { parseSegmentTile(it) != null }
            ?.distinct()
            .orEmpty()
        if (names.isEmpty()) {
            return@withContext Result.failure(errorData("Keine gültige Kachel angegeben."))
        }

        // Der Vordergrund-Wechsel darf scheitern (siehe Klassendoc) — dann
        // laeuft die Arbeit eben mit Zeitlimit weiter, statt gar nicht.
        runCatching { setForeground(getForegroundInfo()) }

        val downloader = AppServices.segmentDownloader
        var transferred = 0L
        var updated = 0

        for ((index, fileName) in names.withIndex()) {
            if (isStopped) return@withContext Result.success(summary(updated, transferred, true))
            notificationText = downloadLabel(fileName, index, names.size)

            val result = try {
                downloader.sync(
                    fileName = fileName,
                    isCancelled = { isStopped },
                ) { progress ->
                    publish(progress, index, names.size)
                }
            } catch (e: IOException) {
                return@withContext retryOrFail(e)
            } catch (e: Exception) {
                // Alles Uebrige (kaputtes Delta, voller Speicher) ist kein
                // Netzproblem und wird nicht wiederholt.
                return@withContext Result.failure(
                    errorData(e.message ?: "Die Karte konnte nicht geladen werden."),
                )
            }

            when (result) {
                is SegmentSyncResult.AlreadyCurrent -> Unit
                is SegmentSyncResult.Updated -> {
                    updated++
                    transferred += result.bytesTransferred
                }

                is SegmentSyncResult.Cancelled -> {
                    transferred += result.bytesTransferred
                    return@withContext Result.success(summary(updated, transferred, true))
                }
            }
        }

        Result.success(summary(updated, transferred, false))
    }

    // -----------------------------------------------------------------------
    // Fortschritt
    // -----------------------------------------------------------------------

    /** Text der Benachrichtigung; wird zwischen den Kacheln fortgeschrieben. */
    private var notificationText: String = ""

    private var progressPercent: Int = 0

    /**
     * Meldet den Fortschritt nach aussen — einmal als WorkManager-`Data`
     * (daraus speist sich [SegmentDownloads.statusFlow] fuer die Oberflaeche)
     * und einmal in die Benachrichtigung.
     *
     * Bewusst `setProgressAsync` und nicht das suspendierende `setProgress`:
     * Der Downloader meldet aus einer gewoehnlichen Rueckruffunktion mitten im
     * Kopierschleifen-Rumpf, und die soll nicht suspendieren duerfen — sonst
     * haetten wir eine Coroutine, die zwischen zwei Puffern anhaelt, waehrend
     * der Datenstrom weiterlaeuft.
     */
    private fun publish(progress: SegmentProgress, index: Int, count: Int) {
        progressPercent = progress.percent
        setProgressAsync(
            workDataOf(
                KEY_PROGRESS_SEGMENT to progress.fileName,
                KEY_PROGRESS_PHASE to progress.phase.name,
                KEY_PROGRESS_DONE to progress.done,
                KEY_PROGRESS_TOTAL to progress.total,
                KEY_PROGRESS_INDEX to index,
                KEY_PROGRESS_COUNT to count,
            ),
        )
        runCatching {
            notificationManager()?.notify(
                NOTIFICATION_ID,
                buildNotification(notificationText, progressPercent),
            )
        }
    }

    private fun downloadLabel(fileName: String, index: Int, count: Int): String {
        val tile = parseSegmentTile(fileName)
        val name = tile?.title ?: fileName
        return if (count > 1) "$name (${index + 1} von $count)" else name
    }

    private fun notificationManager(): NotificationManager? =
        applicationContext.getSystemService(NotificationManager::class.java)

    // -----------------------------------------------------------------------
    // Ergebnis
    // -----------------------------------------------------------------------

    private fun retryOrFail(e: IOException): Result =
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            Result.failure(errorData(e.message ?: "Die Karte konnte nicht geladen werden."))
        }

    private fun errorData(message: String): Data = workDataOf(KEY_OUTPUT_ERROR to message)

    private fun summary(updated: Int, transferred: Long, cancelled: Boolean): Data = workDataOf(
        KEY_OUTPUT_UPDATED to updated,
        KEY_OUTPUT_BYTES to transferred,
        KEY_OUTPUT_CANCELLED to cancelled,
    )

    // -----------------------------------------------------------------------
    // Benachrichtigung
    // -----------------------------------------------------------------------

    /**
     * Die laufende Benachrichtigung: Titel, welche Kachel gerade laeuft, ein
     * Balken und der Abbrechen-Knopf.
     *
     * Bewusst schmucklos und mit einem System-Symbol — die Gestaltung der
     * Kachelverwaltung ist Sache der Oberflaechen-Stufe; hier zaehlt nur, dass
     * ein langer Vorgang sichtbar und **abbrechbar** ist. Der Knopf haengt an
     * [WorkManager.createCancelPendingIntent] mit der [id] genau dieses Laufs;
     * der Abbruch setzt `isStopped`, das der Downloader zwischen zwei
     * Puffern abfragt.
     */
    private fun buildNotification(text: String, percent: Int): Notification {
        val context = applicationContext
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.segment_notification_title))
            .setContentText(text.ifBlank { context.getString(R.string.segment_notification_start) })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Bis der erste Fortschritt da ist, ein unbestimmter Balken statt
            // einer 0, die wie „haengt" aussieht.
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.segment_notification_cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()
    }

    /** Legt den Kanal an; `createNotificationChannel` ist idempotent. */
    private fun ensureChannel(context: Context) {
        val manager = notificationManager() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.segment_notification_channel_name),
            // Leise: Der Fortschritt eines Downloads ist nichts, wofuer ein
            // Telefon klingeln muss.
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = context.getString(R.string.segment_notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    internal companion object {

        /** Eingabe: die Kacheldateinamen, z. B. `["E10_N50.rd5"]`. */
        const val KEY_SEGMENTS = "segments"

        const val KEY_PROGRESS_SEGMENT = "progress.segment"
        const val KEY_PROGRESS_PHASE = "progress.phase"
        const val KEY_PROGRESS_DONE = "progress.done"
        const val KEY_PROGRESS_TOTAL = "progress.total"
        const val KEY_PROGRESS_INDEX = "progress.index"
        const val KEY_PROGRESS_COUNT = "progress.count"

        const val KEY_OUTPUT_ERROR = "error"
        const val KEY_OUTPUT_UPDATED = "updated"
        const val KEY_OUTPUT_BYTES = "bytes"
        const val KEY_OUTPUT_CANCELLED = "cancelled"

        /** Kanal im `trailscape.*`-Namensraum wie die uebrigen Kanaele der App. */
        const val CHANNEL_ID = "trailscape.segments"

        private const val NOTIFICATION_ID = 4712

        /** So oft wird ein Netzfehler wiederholt, bevor der Lauf aufgibt. */
        private const val MAX_ATTEMPTS = 5
    }
}
