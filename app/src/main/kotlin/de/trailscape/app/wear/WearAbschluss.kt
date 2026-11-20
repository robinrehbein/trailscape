package de.trailscape.app.wear

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.map.rideHighlightTexts
import de.trailscape.app.ui.weekTargetKmOutsideUi
import de.trailscape.app.widget.ShownWeekTarget
import de.trailscape.core.AbschlussInfo
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.loadPlan
import de.trailscape.core.rideFacts
import de.trailscape.core.rideHighlights
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Nach einer gespeicherten Tour: die ein, zwei wichtigsten Dinge, die sie
 * gebracht hat („Neue Bestweite: 82,4 km (vorher 61,0)", „Wochenziel
 * geschafft: …"), einmal an die Uhr ([WearBridge.sendAbschluss],
 * Pfad `PFAD_ABSCHLUSS`). Die Uhr zeigt sie unter ihrer Zusammenfassung.
 *
 * ## Wo es haengt
 * Ausgeloest von `TrailscapeApplication` ueber
 * `RecordingRepository.finishedRides` — demselben Fluss, an dem Widget und
 * Strava-Upload haengen. Er meldet erst, wenn die Tour gespeichert ist
 * (`RecordingService.finishAndStop` → `trySave` → `publishStopped`), also
 * liegt nichts hiervon in der Aufzeichnung oder vor dem Speichern. Der
 * Sammler startet je Tour eine eigene Coroutine und kehrt sofort zurueck;
 * alles hier laeuft auf IO in `runCatching`.
 *
 * ## Wann gesendet wird
 * Nur, wenn die Uhr an der Tour teilgenommen hat — sie hat seit dem Start
 * der Tour etwas geschickt ([WearBridge.letzteUhrNachrichtMs]) — und gerade
 * erreichbar ist; nur, wenn es Highlights gibt; ein Mal, ohne Wiederholung.
 *
 * ## Was gerechnet wird
 * [rideHighlights] mit `max = 2` aus den Tourzusammenfassungen, ohne
 * Rundenvergleich (der laese die Signaturen aller Touren von der Platte).
 * Wochenziel wie auf „Heute" ueber [weekTargetKmOutsideUi].
 *
 * ## Bekannte Grenze: Sprache
 * Die Saetze entstehen in der Sprache der **Telefon-App**
 * ([rideHighlightTexts]); die Uhr folgt sonst ihrer eigenen Systemsprache
 * (`wear/…/WearLocale.kt`). Steht das Telefon auf Englisch und die Uhr auf
 * Deutsch, kommen diese Zeilen englisch an. Fuer diesen ersten Schritt
 * hingenommen; sauber waere, Art und Zahlen zu uebertragen und auf der Uhr zu
 * formulieren.
 */
object WearAbschluss {

    /** Rechnet, formuliert und sendet fuer [rideId]. Wirft nie. Blockierend: nur von IO aufrufen. */
    fun nachDerFahrt(context: Context, rideId: String) {
        runCatching {
            val zeilen = zeilenFuer(context, rideId) ?: return
            if (zeilen.isEmpty()) return
            WearBridge.sendAbschluss(context, AbschlussInfo(rideId, zeilen))
        }.onFailure { DiagLog.shared.log(DiagEvent.WEAR_SEND_FAILED, error = it) }
    }

    /** `null`, wenn die Uhr nicht teilgenommen hat oder die Tour fehlt. */
    private fun zeilenFuer(context: Context, rideId: String): List<String>? {
        val uhrZuletztMs = WearBridge.letzteUhrNachrichtMs
        if (uhrZuletztMs == 0L) return null
        val summaries = AppServices.rideStorage.listSummaries().summaries
        val ride = summaries.firstOrNull { it.id == rideId && !it.planned } ?: return null
        if (uhrZuletztMs < ride.createdAt) return null

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate()
        // Dieselbe Tageszuordnung wie die Oberflaeche (`rememberRideFacts`).
        val facts = rideFacts(summaries) { localOfEpochMs(it).toLocalDate() }
        val weekTargetKm = weekTargetKmOutsideUi(
            plan = runCatching { loadPlan(AppServices.trainingPlanStore) }.getOrNull(),
            facts = facts,
            today = today,
            nowMs = now.atZone(zone).toInstant().toEpochMilli(),
            shown = ShownWeekTarget.decode(
                runCatching { AppServices.keyValueStore.getString(ShownWeekTarget.STORAGE_KEY) }.getOrNull(),
            ),
        )
        val highlights = rideHighlights(
            rideId = rideId,
            facts = facts,
            today = today,
            weekTargetKm = weekTargetKm,
            routeComparison = null,
            max = AbschlussInfo.MAX_ZEILEN,
        )
        return rideHighlightTexts(context, highlights)
    }
}
