package de.trailscape.app.routing

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.trailscape.core.KeyValueStore
import de.trailscape.core.parseSegmentTile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Die **eine Tuer** zur Kachelverwaltung fuer alles ausserhalb dieses Pakets —
 * insbesondere fuer die Oberflaeche der Folgestufe.
 *
 * Sie braucht genau vier Dinge und findet sie hier bzw. nebenan:
 *
 *  * **Was liegt da?** [de.trailscape.app.data.AppServices.segmentInventory]
 *    → [SegmentInventory.list], [SegmentInventory.totalBytes].
 *  * **Was fehlt fuer diese Gegend?**
 *    [de.trailscape.core.segmentTilesForBounds] bzw.
 *    [de.trailscape.core.requiredSegmentFiles] in `:core`, abgeglichen mit
 *    [SegmentInventory.contains].
 *  * **Holen/Aktualisieren/Abbrechen:** [enqueue], [cancel].
 *  * **Fortschritt anzeigen:** [statusFlow].
 *
 * Loeschen laeuft direkt ueber [SegmentInventory.delete], Aktualitaet ueber
 * [SegmentDownloader.hasUpdate].
 */
object SegmentDownloads {

    /**
     * Eindeutiger Name der Arbeit — im `trailscape.*`-Namensraum wie die
     * Erinnerungen. Ein einziger Name fuer alle Kacheln, damit nie zwei
     * Downloads gleichzeitig am Netz ziehen und sich gegenseitig ausbremsen.
     */
    const val WORK_NAME: String = "trailscape.segments"

    /**
     * Reiht Kacheln zum Laden bzw. Aktualisieren ein.
     *
     * Mehrfachaufrufe sind unschaedlich: Mit
     * [ExistingWorkPolicy.APPEND_OR_REPLACE] haengt sich ein zweiter Auftrag
     * **hinten an**, statt den laufenden abzubrechen — wer waehrend eines
     * 119-MB-Downloads eine zweite Kachel antippt, soll den ersten nicht
     * verlieren. (Das `OR_REPLACE` greift nur, wenn die vorige Kette
     * abgebrochen oder gescheitert ist; sonst haenge nichts mehr daran.)
     *
     * @param unmeteredOnly `true` (Vorgabe) laesst den Lauf nur im WLAN
     *   starten — siehe [SegmentSettings.unmeteredOnly].
     * @return `false`, wenn kein gueltiger Kachelname dabei war.
     */
    fun enqueue(
        context: Context,
        fileNames: Collection<String>,
        unmeteredOnly: Boolean = true,
    ): Boolean {
        val valid = fileNames.filter { parseSegmentTile(it) != null }.distinct()
        if (valid.isEmpty()) return false

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            // Kein `setRequiresStorageNotLow`: Der Lauf wuerde dann bei knappem
            // Speicher gar nicht erst starten, statt mit einer verstaendlichen
            // Meldung zu scheitern — und knapp ist relativ, 119 MB koennen auch
            // bei „wenig frei" noch passen.
            .build()

        val request = OneTimeWorkRequestBuilder<SegmentDownloadWorker>()
            .setInputData(workDataOf(SegmentDownloadWorker.KEY_SEGMENTS to valid.toTypedArray()))
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()
        return true
    }

    /**
     * Bricht den laufenden und alle wartenden Downloads ab.
     *
     * Die bereits geladenen Bytes bleiben als Teildatei liegen — ein spaeterer
     * Aufruf von [enqueue] setzt genau dort wieder auf (siehe
     * [SegmentDownloader]). Wer sie wirklich loswerden will, nimmt
     * [SegmentInventory.deleteTemporaries].
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Der Fortschritt fuer die Oberflaeche — ein [Flow], der bei jeder
     * Aenderung nachlegt.
     *
     * `null` bedeutet: gerade laeuft nichts (und es liegt auch kein frisches
     * Ergebnis vor).
     */
    fun statusFlow(context: Context): Flow<SegmentDownloadStatus?> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.toStatus() }

    /**
     * Der aktuelle Stand ohne Flow — fuer einmalige Abfragen.
     *
     * Blockiert kurz auf der WorkManager-Datenbank und gehoert deshalb nicht
     * auf den Hauptthread.
     */
    fun status(context: Context): SegmentDownloadStatus? =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosForUniqueWork(WORK_NAME)
            .get()
            .toStatus()

    private fun List<WorkInfo>.toStatus(): SegmentDownloadStatus? {
        // Der jeweils interessante Eintrag ist der laufende; gibt es keinen,
        // zaehlt der zuletzt beendete (fuer Fehlermeldung bzw. Abschluss).
        val running = firstOrNull { it.state == WorkInfo.State.RUNNING }
        val info = running ?: lastOrNull() ?: return null
        val data = if (running != null) info.progress else info.outputData
        return SegmentDownloadStatus(
            running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
            fileName = data.getString(SegmentDownloadWorker.KEY_PROGRESS_SEGMENT),
            phase = data.phase(),
            bytesDone = data.getLong(SegmentDownloadWorker.KEY_PROGRESS_DONE, 0L),
            bytesTotal = data.getLong(SegmentDownloadWorker.KEY_PROGRESS_TOTAL, 0L),
            index = data.getInt(SegmentDownloadWorker.KEY_PROGRESS_INDEX, 0),
            count = data.getInt(SegmentDownloadWorker.KEY_PROGRESS_COUNT, 0),
            error = info.outputData.getString(SegmentDownloadWorker.KEY_OUTPUT_ERROR),
            finished = info.state.isFinished,
        )
    }

    private fun Data.phase(): SegmentPhase? =
        getString(SegmentDownloadWorker.KEY_PROGRESS_PHASE)
            ?.let { name -> SegmentPhase.entries.firstOrNull { it.name == name } }
}

/**
 * Fortschritt eines Kachel-Downloads, fertig fuer die Anzeige.
 *
 * [bytesDone]/[bytesTotal] sind Bytes — ausser waehrend
 * [SegmentPhase.DELTA_APPLY], wo die Engine nur Prozent liefert (siehe
 * [SegmentProgress]).
 */
data class SegmentDownloadStatus(
    val running: Boolean,
    val fileName: String?,
    val phase: SegmentPhase?,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** Der wievielte Auftrag der Kette gerade laeuft (ab 0). */
    val index: Int,
    /** Wie viele Kacheln der laufende Auftrag umfasst. */
    val count: Int,
    /** Deutsche Fehlermeldung des letzten Laufs, sonst `null`. */
    val error: String?,
    val finished: Boolean,
) {
    val percent: Int
        get() = if (bytesTotal <= 0L) 0 else ((bytesDone * 100L) / bytesTotal).coerceIn(0L, 100L).toInt()
}

/**
 * Die eine Einstellung der Kachelverwaltung: **nur im WLAN laden?**
 *
 * Als Einstellung und nicht fest verdrahtet, weil beide Antworten vertretbar
 * sind: Wer eine Datenflatrate hat und morgen frueh losfahren will, soll die
 * fehlende Kachel unterwegs holen duerfen; wer ein kleines Volumen hat, darf
 * von einer App nicht 240 MB verlieren, weil sie es besser zu wissen glaubt.
 * Die **Vorgabe** ist deshalb die schonende (`true`), die Entscheidung bleibt
 * aber beim Nutzer. Umgesetzt wird sie als
 * [androidx.work.Constraints] am Auftrag — WorkManager wartet dann von selbst,
 * bis WLAN da ist, statt den Download abzubrechen.
 *
 * Liegt auf demselben [KeyValueStore] wie Profil, Kartenstil und Erinnerungen.
 */
class SegmentSettings(private val store: KeyValueStore) {

    var unmeteredOnly: Boolean
        get() = store.getString(KEY_UNMETERED_ONLY) != "false"
        set(value) = store.setString(KEY_UNMETERED_ONLY, value.toString())

    private companion object {
        const val KEY_UNMETERED_ONLY = "trailscape.segments.unmeteredOnly"
    }
}
