package de.trailscape.core

import kotlin.math.roundToInt

/**
 * Bewertung einer [Goal.targetTimeMin]: Was verlangt die Zeit, und traegt die
 * eigene Historie sie?
 *
 * ## Warum eine Rechnung und kein Feld
 * Noetiger Schnitt und Prognose haengen an der Historie — sie aendern sich mit
 * jeder Tour, das Ziel nicht. Beides als Feld zu speichern wuerde entweder
 * einfrieren oder bei jeder Fahrt nachgezogen werden muessen; als Rechnung
 * (wie [assessPlanFeasibility]) ist es immer aktuell.
 *
 * ## Das Schaetzmodell — und was es bewusst nicht ist
 * Die Prognose ist `Zieldistanz / Historien-Median` ([typicalAvgSpeedKmh]),
 * also die ehrliche Fortschreibung dessen, was die letzten Touren hergaben.
 * Sie rechnet **keine** Ermuedung ueber die Zieldistanz ein und keine
 * Zieltagsform: Wer 200 km plant, dessen Median aus 60-km-Runden ist eine
 * untere, keine obere Schaetzung. Das Urteil ([GoalTimeVerdict]) spiegelt
 * genau diesen Spielraum zurueck, statt eine Scheinpraezision zu behaupten,
 * die kein Modell ohne Leistungsmessung hergibt.
 */

/** Urteil ueber eine Zielzeit gegen die eigene Historie. */
enum class GoalTimeVerdict(
    val label: String,
) {
    /** Prognose deutlich unter der Zielzeit — Puffer fuer Renntag, Wind und Hunger. */
    KOMFORTABEL("komfortabel"),

    /** Prognose passt knapp — der Tag muss schon mitgehen. */
    KNAPP("knapp"),

    /** Prognose ueber der Zielzeit — machbar mit Aufbau und gutem Tag. */
    AMBITIOS("ambitioniert"),

    /** Prognose weit ueber der Zielzeit — dieses Zeitziel traegt der Plan nicht. */
    UNREALISTISCH("unrealistisch"),
}

/**
 * Das Ergebnis von [assessGoalTime].
 *
 * @property targetTimeMin die bewertete Zielzeit in Minuten (Abbild aus dem
 *   Ziel, fuer UI-Anbindung ohne zweites Goal-Argument).
 * @property requiredAvgSpeedKmh Schnitt, den die Zielzeit ueber die
 *   Zieldistanz verlangt: `distanceKm / (targetTimeMin / 60)`.
 * @property estimatedTimeMin Prognose der Fahrzeit in Minuten aus dem
 *   Historien-Median — bei leerer Historie der [fallbackGravelSpeedKmh]
 *   (dann ist [basedOnHistory] `false`).
 * @property basedOnHistory ob die Prognose aus gefahrenen Touren stammt
 *   (`false` = Fallback-Schnitt 18 km/h, noch keine Aussage ueber dich).
 */
data class GoalTimeAssessment(
    val targetTimeMin: Int,
    val requiredAvgSpeedKmh: Double,
    val estimatedTimeMin: Int,
    val basedOnHistory: Boolean,
    val verdict: GoalTimeVerdict,
)

/** Bis hierher gilt eine Zielzeit als komfortabel (Prognose hoechstens 90 % der Zeit). */
internal const val goalTimeComfortRatio: Double = 0.90

/** Ab hier ist eine Zielzeit unrealistisch (Prognose ueber 115 % der Zeit). */
internal const val goalTimeUnrealisticRatio: Double = 1.15

/** Schnitt, den eine Zielzeit mindestens verlangen darf ([minPlausibleAvgSpeedKmh]). */
const val goalTimeMinRequiredSpeedKmh: Double = minPlausibleAvgSpeedKmh

/** Schnitt, den eine Zielzeit hoechstens verlangen darf — 40 km/h auf Schotter ist kein Ziel, sondern ein Wunsch. */
const val goalTimeMaxRequiredSpeedKmh: Double = 40.0

/**
 * Der Schnitt, den [timeMin] ueber [distanceKm] verlangt; `null`, wenn die
 * Eingaben keine sinnvolle Rechnung ergeben (Distanz oder Zeit nicht positiv).
 */
fun requiredPaceKmh(distanceKm: Double, timeMin: Int): Double? {
    if (!distanceKm.isFinite() || distanceKm <= 0 || timeMin <= 0) {
        return null
    }
    val pace = distanceKm / (timeMin / 60.0)
    return if (pace.isFinite() && pace > 0) pace else null
}

/**
 * Bewertet die Zielzeit von [goal] gegen die eigene Historie
 * ([recentRides], Median wie bei der Routenplanung, siehe
 * [typicalAvgSpeedKmh]).
 *
 * `null`, wenn das Ziel keine Zielzeit traegt oder Distanz/Zeit keine
 * sinnvolle Rechnung ergeben — beides ist kein Fehler, sondern ein
 * Distanzziel ohne Zeitanspruch.
 */
fun assessGoalTime(goal: Goal, recentRides: List<RideInfo>): GoalTimeAssessment? {
    val targetTimeMin = goal.targetTimeMin ?: return null
    val required = requiredPaceKmh(goal.distanceKm, targetTimeMin) ?: return null

    val historySpeed = typicalAvgSpeedKmh(recentRides)
    val basedOnHistory = historySpeed != null
    // Ohne Historie ist der Fallback eine Annahme ueber „Gravel allgemein",
    // nicht ueber dich — die Prognose bleibt trotzdem stehen, aber ihr
    // Urteil sagt nichts Persoenliches aus (siehe [GoalTimeAssessment.basedOnHistory]).
    val speed = historySpeed ?: fallbackGravelSpeedKmh
    val estimated = goal.distanceKm / speed * 60.0

    val ratio = estimated / targetTimeMin
    val verdict = when {
        ratio <= goalTimeComfortRatio -> GoalTimeVerdict.KOMFORTABEL
        ratio <= 1.0 -> GoalTimeVerdict.KNAPP
        ratio <= goalTimeUnrealisticRatio -> GoalTimeVerdict.AMBITIOS
        else -> GoalTimeVerdict.UNREALISTISCH
    }

    return GoalTimeAssessment(
        targetTimeMin = targetTimeMin,
        requiredAvgSpeedKmh = required,
        estimatedTimeMin = estimated.roundToInt().coerceAtLeast(1),
        basedOnHistory = basedOnHistory,
        verdict = verdict,
    )
}
