package de.trailscape.app.widget

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.core.RideFact
import de.trailscape.core.ZielSnapshot
import de.trailscape.core.loadPlan
import de.trailscape.core.rideFacts
import de.trailscape.core.zielSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Liest Touren und Plan vom Geraet und rechnet daraus den [WidgetState] —
 * die eine Stelle, an der Handy-Widget und Uhr-Kachel
 * ([de.trailscape.app.wear.WearGlanceSender]) ihre Zahlen herholen. Zwei
 * getrennte Rechnungen koennten auseinanderlaufen, und dann zeigte die Uhr
 * eine andere Serie als das Widget.
 *
 * Blockierend (Dateizugriff): nur von einem IO-Kontext aufrufen. Wirft nie;
 * bei einem Lesefehler kommt [WidgetState.Empty].
 */
fun loadWidgetState(context: Context): WidgetState = runCatching {
    ladeGeraetestand(context).state
}.getOrDefault(WidgetState.Empty)

/**
 * Der Ziel-Snapshot zum Start einer Aufzeichnung (`zielSnapshot` in `:core`):
 * Rest zum Wochenziel und weiteste Tour. Das Wochenziel ist **dasselbe wie
 * in Widget und Uhr-Kachel** (gleiche Rechnung, mit aktivem Trainingsplan
 * dessen Wochenziel), damit „Noch 12 km bis zum Wochenziel" zu „48 von 60 km"
 * passt.
 *
 * Blockierend (Dateizugriff): nur von einem IO-Kontext aufrufen. Wirft nie;
 * `null` bei einem Lesefehler oder wenn es nichts zu zeigen gibt.
 */
fun loadZielSnapshot(context: Context): ZielSnapshot? = runCatching {
    val stand = ladeGeraetestand(context)
    zielSnapshot(stand.facts(), stand.state.targetKm, stand.state.riddenKm)
}.getOrNull()

private class Geraetestand(val state: WidgetState, val facts: () -> List<RideFact>)

private fun ladeGeraetestand(context: Context): Geraetestand {
    // Das Widget kann einen Prozess wecken, dessen Application gerade
    // erst hochfaehrt; `init` ist idempotent (siehe AppServices).
    AppServices.init(context)
    val zone = ZoneId.systemDefault()
    val zoneDateOf = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
    // Zusammenfassungen genuegen: Die Rechnung braucht nur
    // Kennzahlen und Zeitstempel, nie GPS-Punkte.
    val rides = AppServices.rideStorage.listSummaries().summaries
    val state = buildWidgetState(
        rides = rides,
        plan = loadPlan(AppServices.trainingPlanStore),
        now = LocalDateTime.now(zone),
        zoneDateOf = zoneDateOf,
        zone = zone,
        shownTarget = ShownWeekTarget.decode(
            runCatching { AppServices.keyValueStore.getString(ShownWeekTarget.STORAGE_KEY) }.getOrNull(),
        ),
    )
    return Geraetestand(state) { rideFacts(rides, zoneDateOf) }
}
