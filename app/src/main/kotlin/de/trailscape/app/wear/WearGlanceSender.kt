package de.trailscape.app.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.trailscape.app.data.AppServices
import de.trailscape.app.widget.WidgetState
import de.trailscape.app.widget.loadWidgetState
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.PFAD_WOCHE
import de.trailscape.core.WochenGlance
import de.trailscape.core.dekodiereWochenGlance
import de.trailscape.core.kodiereWochenGlance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Schreibt Serie und Wochenziel als DataItem ([PFAD_WOCHE]) in den Data Layer,
 * damit Kachel und Komplikation der Uhr sie zeigen (siehe [WochenGlance]).
 *
 * ## Wann
 * Aufgerufen von [de.trailscape.app.widget.WidgetUpdater.request] — also an
 * genau den Stellen, die auch das Handy-Widget aktualisieren (Tour gespeichert,
 * App in den Hintergrund, Datums-, Zeitzonen- und Sprachwechsel). Dieselben
 * Ausloeser, weil dieselben Zahlen: Aendert sich etwas fuers Widget, aendert
 * es sich auch fuer die Uhr. Der Datumswechsel am Montag kommt ohne
 * App-Start ueber den Widget-Empfaenger.
 *
 * ## Nur bei Aenderung
 * Der zuletzt **erfolgreich** geschriebene Stand liegt im Schluessel-Wert-Speicher;
 * ein gleicher Inhalt ([WochenGlance.gleicherInhalt], ohne Zeitstempel) wird nicht
 * noch einmal gesendet. Das spart Funk und Akku — der Ausloeser „App geht in den
 * Hintergrund" feuert oft. Schlug das Schreiben fehl, wird nichts vermerkt und der
 * naechste Ausloeser versucht es erneut.
 *
 * ## Fehler
 * Alles laeuft auf IO in `runCatching`; ohne Play Services, ohne Uhr oder bei
 * gestoertem Funk passiert nichts weiter als ein Diagnose-Eintrag. Das Speichern
 * einer Tour darf davon nie abhaengen. Ein Mutex ordnet gleichzeitige Aufrufe.
 */
object WearGlanceSender {

    /** Schluessel des zuletzt gesendeten Stands (JSON wie auf dem Draht). */
    private const val STORAGE_KEY = "trailscape.wear.glance.v1"

    private val lock = Mutex()

    /**
     * Pause nach einem gescheiterten Senden. Ohne Play Services oder ohne
     * Uhr scheitert jeder Versuch gleich; ohne Pause schriebe jedes
     * Verlassen der App einen weiteren Fehler ins Diagnose-Log.
     */
    private const val FEHLER_PAUSE_MS = 60_000L

    @Volatile
    private var letzterFehlerMs = 0L

    /** Stoesst eine Pruefung und ggf. das Senden an. Kehrt sofort zurueck, wirft nie. */
    fun request(context: Context) {
        val app = context.applicationContext
        AppServices.appScope.launch(Dispatchers.IO) {
            runCatching { lock.withLock { sendeWennGeaendert(app) } }
                .onFailure { DiagLog.shared.log(DiagEvent.WEAR_SEND_FAILED, error = it) }
        }
    }

    private fun sendeWennGeaendert(context: Context) {
        val jetzt = System.currentTimeMillis()
        if (jetzt - letzterFehlerMs in 0 until FEHLER_PAUSE_MS) return
        val heute = LocalDate.now(ZoneId.systemDefault())
        val neu = glanceVon(loadWidgetState(context), heute, jetzt)

        val zuletzt = runCatching { AppServices.keyValueStore.getString(STORAGE_KEY) }.getOrNull()
            ?.let { dekodiereWochenGlance(it.toByteArray(Charsets.UTF_8)) }
        if (neu.gleicherInhalt(zuletzt)) return

        val bytes = kodiereWochenGlance(neu)
        val request = PutDataRequest.create(PFAD_WOCHE).setData(bytes).setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener {
                runCatching { AppServices.keyValueStore.setString(STORAGE_KEY, bytes.toString(Charsets.UTF_8)) }
            }
            .addOnFailureListener {
                letzterFehlerMs = System.currentTimeMillis()
                DiagLog.shared.log(DiagEvent.WEAR_SEND_FAILED, error = it)
            }
    }

    /** Uebersetzt den Widget-Stand in den Draht-Stand; ohne Touren: Serie 0, kein Ziel. */
    internal fun glanceVon(state: WidgetState, heute: LocalDate, jetztMs: Long): WochenGlance =
        WochenGlance.von(
            serieWochen = state.streakWeeks,
            wocheErledigt = state.thisWeekSecured,
            wochenKm = state.riddenKm,
            zielKm = state.targetKm,
            heute = heute,
            standMs = jetztMs,
        )
}
