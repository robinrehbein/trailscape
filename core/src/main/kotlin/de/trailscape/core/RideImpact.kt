package de.trailscape.core

import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * „Was die Tour gebracht hat" — die Wirkung einer einzelnen Fahrt, fertig
 * gerechnet fuer den Block oben in der Tourdetail-Ansicht.
 *
 * ## Warum es das gibt
 * Die App plant (Trainingsplan), schlaegt eine Runde vor („Heute"), zeichnet
 * die Fahrt auf — und hoerte dort auf. Dieser Block schliesst den Kreis
 * Plan → Runde → Fahrt → **Wirkung**: Was hat die Tour an Fitness und
 * Muedigkeit bewegt, wie steht das Wochenziel, welche Kacheln sind neu,
 * welche Bestzeiten gefallen?
 *
 * ## Warum jede Zeile einzeln entfaellt
 * Jede Teilaussage steht nur, wenn ihre Datengrundlage traegt — keine Last →
 * keine Formzeile, kein Plan → keine Wochenzeile, unvollstaendiger
 * Kachel-Cache → keine Kachelzeile. Liefert keine einzige Zeile etwas, kommt
 * `null` zurueck und der Block entfaellt ganz. Lieber nichts sagen als etwas
 * Geschaetztes wie eine Messung aussehen lassen.
 *
 * Bewusst ohne Android und ohne `ZoneId`: Den lokalen Zeitpunkt der Fahrt
 * reicht der Aufrufer herein.
 */

/**
 * Ab diesem Fitness-Zuwachs (CTL-Punkte, entspricht rund 21 Lastpunkten) gilt
 * die Tour als spuerbarer Reiz. Darunter wuerde die gerundete Anzeige „+0"
 * zeigen — die UI sagt dann ehrlich „Kleiner Reiz" statt zu loben.
 */
const val rideImpactNoticeableFitnessGain: Double = 0.5

/**
 * Was die Tour an der Form bewegt hat.
 *
 * [fitnessGain] und [fatigueGain] sind der exakte Beitrag **dieser** Tour
 * (siehe [computeRideImpact]); [freshnessAfter] ist dagegen die Frische am
 * Ende des Fahrtags — eine zweite Tour am selben Tag steckt darin mit drin.
 */
data class RideFormImpact(
    val load: Double,
    /** Zuwachs der Fitness (CTL) durch diese Tour. */
    val fitnessGain: Double,
    /** Zuwachs der Muedigkeit (ATL) durch diese Tour. */
    val fatigueGain: Double,
    /** `CTL − ATL` am Ende des Fahrtags. */
    val freshnessAfter: Double,
    /** `fitnessGain >= `[rideImpactNoticeableFitnessGain]. */
    val noticeable: Boolean,
)

/** Stand des Wochenziels nach dieser Tour. */
data class RideWeekGoalImpact(
    /** Gefahrene km der Planwoche bis einschliesslich dieser Tour. */
    val kmAfter: Double,
    val targetKm: Int,
    /** km dieser Tour. */
    val rideKm: Double,
    /** Ob erst diese Tour das (gerundete) Ziel erreicht hat. */
    val reachedByThisRide: Boolean,
)

/** Eine neue Bestzeit dieser Tour auf einem Segment. */
data class RideSegmentBest(
    val segmentId: String,
    val name: String,
    val timeS: Int,
    /** Um so viele Sekunden war die Tour schneller als die fruehere Bestzeit. */
    val improvementS: Int,
)

/** Die Wirkung einer Tour; jede Teilaussage `null`/leer ohne Datengrundlage. */
data class RideImpact(
    val form: RideFormImpact?,
    val weekGoal: RideWeekGoalImpact?,
    /** Zum ersten Mal befahrene Kacheln, nur wenn > 0. */
    val newTiles: Int?,
    /** Neue Bestzeiten, nach Verbesserung absteigend sortiert. */
    val newBests: List<RideSegmentBest>,
)

/**
 * Rechnet die Wirkung der Tour [ride].
 *
 * ## Form
 * Das EWMA-Update `ctl += λ·(load − ctl)` ist linear in der Tageslast. Diese
 * Tour hebt die Fitness deshalb **genau** um `λ_ctl · rideLoad` und die
 * Muedigkeit um `λ_atl · rideLoad` — unabhaengig davon, ob am selben Tag
 * noch eine andere Tour lag. Voraussetzung: [rideLoad] ist bekannt und
 * positiv, die Kurve ist anzeigereif ([FitnessSeries.displayReady], dieselbe
 * 28-Tage-Regel wie im Trainings-Tab) und hat einen Punkt fuer den Fahrtag.
 *
 * ## Wochenziel
 * Die Planwoche, in die die Tour faellt ([plan] ist der angepasste
 * Anzeigeplan, derselbe wie auf „Heute"), mit `targetKm > 0`. Gezaehlt werden
 * gefahrene Touren dieser Woche bis einschliesslich dieser — spaetere nicht,
 * damit eine alte Tour den Stand *nach ihr* zeigt. Die Tour selbst zaehlt
 * immer mit, auch wenn [rides] sie direkt nach der Aufzeichnung noch nicht
 * enthaelt. „Erreicht" vergleicht die gerundeten km wie `weekSummary` auf
 * „Heute" — sonst stuende dort „180 von 180 km" ohne „geschafft".
 *
 * ## Kacheln und Bestzeiten
 * [newTiles] kommt fertig aus [explorerTilesNewInRide]. Bestzeiten sind die
 * [SegmentEffortView]s mit [SegmentEffortView.isNewBest], je Segment die
 * schnellste Runde.
 *
 * @return `null` fuer eine geplante Route oder wenn keine Zeile etwas zu
 *   sagen hat.
 */
fun computeRideImpact(
    ride: RideInfo,
    rideAt: LocalDateTime,
    rideLoad: Double?,
    fitness: FitnessSeries,
    plan: TrainingPlan?,
    rides: List<RideInfo>,
    newTiles: Int?,
    segmentViews: List<SegmentEffortView>,
): RideImpact? {
    if (ride.planned) return null

    val form = rideFormImpact(rideAt, rideLoad, fitness)
    val weekGoal = rideWeekGoalImpact(ride, plan, rides)
    val tiles = newTiles?.takeIf { it > 0 }
    val bests = rideSegmentBests(segmentViews)

    if (form == null && weekGoal == null && tiles == null && bests.isEmpty()) return null
    return RideImpact(form = form, weekGoal = weekGoal, newTiles = tiles, newBests = bests)
}

private fun rideFormImpact(rideAt: LocalDateTime, rideLoad: Double?, fitness: FitnessSeries): RideFormImpact? {
    if (rideLoad == null || !rideLoad.isFinite() || rideLoad <= 0) return null
    if (!fitness.displayReady) return null
    val point = fitness.at(rideAt) ?: return null
    val fitnessGain = lambdaCtl * rideLoad
    return RideFormImpact(
        load = rideLoad,
        fitnessGain = fitnessGain,
        fatigueGain = lambdaAtl * rideLoad,
        freshnessAfter = point.ctl - point.atl,
        noticeable = fitnessGain >= rideImpactNoticeableFitnessGain,
    )
}

private fun rideWeekGoalImpact(ride: RideInfo, plan: TrainingPlan?, rides: List<RideInfo>): RideWeekGoalImpact? {
    val week = plan?.weeks?.firstOrNull { ride.createdAt >= it.start && ride.createdAt < it.end } ?: return null
    if (week.targetKm <= 0) return null
    val rideKm = ride.stats.distanceKm.takeIf { it.isFinite() && it >= 0 } ?: 0.0
    val earlierKm = riddenRides(rides)
        .filter {
            it.id != ride.id &&
                it.createdAt >= week.start && it.createdAt < week.end &&
                it.createdAt <= ride.createdAt
        }
        .sumOf { it.stats.distanceKm.takeIf { km -> km.isFinite() && km >= 0 } ?: 0.0 }
    val kmAfter = earlierKm + rideKm
    return RideWeekGoalImpact(
        kmAfter = kmAfter,
        targetKm = week.targetKm,
        rideKm = rideKm,
        reachedByThisRide = earlierKm.roundToInt() < week.targetKm && kmAfter.roundToInt() >= week.targetKm,
    )
}

private fun rideSegmentBests(views: List<SegmentEffortView>): List<RideSegmentBest> =
    views
        .filter { it.isNewBest && it.previousBestTimeS != null }
        .groupBy { it.segmentId }
        .values
        .mapNotNull { laps ->
            val fastest = laps.minBy { it.timeS }
            val improvement = fastest.previousBestTimeS!! - fastest.timeS
            if (improvement <= 0) {
                null
            } else {
                RideSegmentBest(
                    segmentId = fastest.segmentId,
                    name = fastest.name,
                    timeS = fastest.timeS,
                    improvementS = improvement,
                )
            }
        }
        .sortedByDescending { it.improvementS }
