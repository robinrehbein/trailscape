package de.trailscape.app.widget

import android.content.Context
import de.trailscape.app.data.AppServices
import de.trailscape.core.RideInfo
import de.trailscape.core.TrainingPlan
import de.trailscape.core.ZielSnapshot
import de.trailscape.core.loadPlan
import de.trailscape.core.ohneLaufendeTour
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
    val stand = ladeGeraetestand(context)
    buildWidgetState(
        rides = stand.rides,
        plan = stand.plan,
        now = LocalDateTime.now(stand.zone),
        zoneDateOf = { ms -> Instant.ofEpochMilli(ms).atZone(stand.zone).toLocalDate() },
        zone = stand.zone,
        shownTarget = stand.shownTarget,
    )
}.getOrDefault(WidgetState.Empty)

/**
 * Der Ziel-Snapshot zum Start einer Aufzeichnung (`zielSnapshot` in `:core`):
 * Rest zum Wochenziel und weiteste Tour. Das Wochenziel ist **dasselbe wie
 * in Widget und Uhr-Kachel** (gleiche Rechnung, mit aktivem Trainingsplan
 * dessen Wochenziel), damit „Noch 12 km bis zum Wochenziel" zu „48 von 60 km"
 * passt. Rechnung in [zielSnapshotZumStart].
 *
 * Blockierend (Dateizugriff): nur von einem IO-Kontext aufrufen. Wirft nie;
 * `null` bei einem Lesefehler oder wenn es nichts zu zeigen gibt.
 */
fun loadZielSnapshot(context: Context, laufendeTourId: String?, startedAtMs: Long): ZielSnapshot? = runCatching {
    val stand = ladeGeraetestand(context)
    zielSnapshotZumStart(
        rides = stand.rides,
        plan = stand.plan,
        startedAtMs = startedAtMs,
        laufendeTourId = laufendeTourId,
        zone = stand.zone,
        shownTarget = stand.shownTarget,
    )
}.getOrNull()

/**
 * Der Ziel-Snapshot einer Tour, die um [startedAtMs] begonnen hat — rein, fuer
 * Tests ohne Geraet.
 *
 *  * **Ohne die laufende Tour** ([laufendeTourId], [ohneLaufendeTour]): Ist sie
 *    schon gespeichert (Wiederherstellung, deren Journal liegen blieb, und
 *    danach fortgesetzt), zaehlte sie sonst doppelt — im Wochenrest und als
 *    ihre eigene Bestweite.
 *  * **Zum Start, nicht jetzt**: Nach einem Dienst-Neustart wird neu geladen.
 *    Mit „jetzt" gaelte nach Mitternacht von Sonntag auf Montag schon die neue
 *    Woche, waehrend die Tour (nach ihrem Start datiert) zur alten zaehlt.
 */
internal fun zielSnapshotZumStart(
    rides: List<RideInfo>,
    plan: TrainingPlan?,
    startedAtMs: Long,
    laufendeTourId: String?,
    zone: ZoneId,
    shownTarget: ShownWeekTarget?,
): ZielSnapshot? {
    val gespeichert = ohneLaufendeTour(rides, laufendeTourId)
    val zoneDateOf = { ms: Long -> Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() }
    val state = buildWidgetState(
        rides = gespeichert,
        plan = plan,
        now = LocalDateTime.ofInstant(Instant.ofEpochMilli(startedAtMs), zone),
        zoneDateOf = zoneDateOf,
        zone = zone,
        shownTarget = shownTarget,
    )
    return zielSnapshot(rideFacts(gespeichert, zoneDateOf), state.targetKm, state.riddenKm)
}

private class Geraetestand(
    val rides: List<RideInfo>,
    val plan: TrainingPlan?,
    val zone: ZoneId,
    val shownTarget: ShownWeekTarget?,
)

private fun ladeGeraetestand(context: Context): Geraetestand {
    // Das Widget kann einen Prozess wecken, dessen Application gerade
    // erst hochfaehrt; `init` ist idempotent (siehe AppServices).
    AppServices.init(context)
    return Geraetestand(
        // Zusammenfassungen genuegen: Die Rechnung braucht nur
        // Kennzahlen und Zeitstempel, nie GPS-Punkte.
        rides = AppServices.rideStorage.listSummaries().summaries,
        plan = loadPlan(AppServices.trainingPlanStore),
        zone = ZoneId.systemDefault(),
        shownTarget = ShownWeekTarget.decode(
            runCatching { AppServices.keyValueStore.getString(ShownWeekTarget.STORAGE_KEY) }.getOrNull(),
        ),
    )
}
