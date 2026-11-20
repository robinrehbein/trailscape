package de.trailscape.app.widget

import de.trailscape.core.RideInfo
import de.trailscape.core.TrainingPlan
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.periodTotals
import de.trailscape.core.rideFacts
import de.trailscape.core.suggestWeeklyGoal
import de.trailscape.core.weekKm
import de.trailscape.core.weekStreak
import de.trailscape.core.weeklyGoalProgress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Alles, was das Startbildschirm-Widget anzeigt — als schlichte Werte, ohne
 * Android-Typen, damit [buildWidgetState] mit festen Daten testbar bleibt und
 * die Glance-Oberflaeche (`TrailscapeWidget.kt`) nur noch zeichnet.
 *
 * @property hasRides ob es ueberhaupt eine gefahrene Tour gibt (geplante
 *   Routen zaehlen nicht). Ohne sie zeigt das Widget den Leerzustand statt
 *   „0 Wochen".
 * @property streakWeeks Laenge der Wochenserie, siehe [weekStreak]: Die
 *   laufende Woche zaehlt mit, sobald in ihr gefahren wurde; sonst steht hier
 *   die Serie bis einschliesslich Vorwoche, die noch *lebt*.
 * @property thisWeekSecured ob diese Woche schon eine Tour hat.
 * @property daysLeft Tage bis einschliesslich Sonntag, heute zaehlt mit (1…7).
 * @property riddenKm Kilometer dieser Woche (Planwoche bzw. Mo–heute).
 * @property targetKm Wochenziel in km oder `null`, wenn es keines gibt.
 * @property fraction `riddenKm / targetKm` in 0…1; 0 ohne Ziel.
 */
data class WidgetState(
    val hasRides: Boolean,
    val streakWeeks: Int,
    val thisWeekSecured: Boolean,
    val daysLeft: Int,
    val riddenKm: Double,
    val targetKm: Int?,
    val fraction: Float,
) {
    /** Eine Serie laeuft, ist diese Woche aber noch nicht verlaengert. */
    val streakAtRisk: Boolean get() = streakWeeks > 0 && !thisWeekSecured

    /** Das Wochenziel ist erreicht oder uebertroffen. */
    val goalReached: Boolean get() = targetKm != null && riddenKm >= targetKm

    companion object {
        /** Vor der ersten Tour und bei einem Lesefehler: der Leerzustand. */
        val Empty = WidgetState(
            hasRides = false,
            streakWeeks = 0,
            thisWeekSecured = false,
            daysLeft = 7,
            riddenKm = 0.0,
            targetKm = null,
            fraction = 0f,
        )
    }
}

/**
 * Rechnet den Widget-Stand aus Tourzusammenfassungen und Plan.
 *
 * **Warum Wochen und nicht Tage:** Die Serie ist dieselbe Wochenserie wie auf
 * „Heute" ([weekStreak]). Eine Tagesserie auf dem Startbildschirm wuerde
 * jeden Ruhetag als Verlust zeigen und genau zu dem Dauerdruck erziehen, vor
 * dem die Bereitschaftsrechnung warnt; das Widget soll zurueckholen, nicht
 * draengeln.
 *
 * **Warum das Plan-Ziel vor dem adaptiven gewinnt:** Wer einen Trainingsplan
 * aktiv hat, hat sich bewusst auf eine Wochenmenge festgelegt (und die
 * Planwoche kennt Aufbau, Entlastung und Ruhewochen). Das automatische Ziel
 * ([suggestWeeklyGoal]) ist nur der Ersatz fuer alle ohne Plan — zwei
 * verschiedene Zahlen auf Widget und „Heute" waeren schlimmer als keine.
 * Eine Planwoche mit Ziel 0 (reine Pause) zaehlt als „kein Plan-Ziel" und
 * faellt auf das adaptive zurueck.
 *
 * Planwoche und adaptive Woche werden wie in der App unterschiedlich gezaehlt:
 * Planwochen ueber [weekKm] (Zeitstempel gegen die Wochengrenzen des Plans),
 * das adaptive Ziel ueber Kalendertage Montag bis heute.
 *
 * @param zoneDateOf Kalendertag eines Tourzeitpunkts (Zeitzone des Nutzers).
 * @param zone Zone, in der [now] zu Epoch-Millisekunden wird (fuer die
 *   Planwochen, die als Zeitstempel gespeichert sind). Nur Tests ueberschreiben.
 */
fun buildWidgetState(
    rides: List<RideInfo>,
    plan: TrainingPlan?,
    now: LocalDateTime,
    zoneDateOf: (Long) -> LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    shownTarget: ShownWeekTarget? = null,
): WidgetState {
    val today = now.toLocalDate()
    val facts = rideFacts(rides, zoneDateOf)
    val streak = weekStreak(facts, today)

    val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    val planWeek = plan
        ?.let { p -> p.weeks.getOrNull(currentWeekIndex(p, nowMs)) }
        ?.takeIf { nowMs >= it.start && nowMs < it.end && it.targetKm > 0 }

    // Hat die Startseite fuer diese Woche schon ein Ziel gezeigt, gilt genau
    // das: Sie rechnet mit dem an die gefahrene Realitaet angepassten Plan
    // (`adaptPlan`), den das Widget ohne die Trainingsauswertung nicht
    // nachbauen kann. Zwei verschiedene Wochenziele waeren schlimmer als keins.
    val shownKm = shownTarget?.takeIf { it.monday == today.with(DayOfWeek.MONDAY) && it.targetKm > 0 }?.targetKm

    val riddenKm: Double
    val targetKm: Int?
    if (shownKm != null) {
        riddenKm = if (planWeek != null) weekKm(planWeek, rides) else periodTotals(facts, today.with(DayOfWeek.MONDAY), today).km
        targetKm = shownKm
    } else if (planWeek != null) {
        riddenKm = weekKm(planWeek, rides)
        targetKm = planWeek.targetKm
    } else {
        val goal = suggestWeeklyGoal(facts, today)
        if (goal != null) {
            riddenKm = weeklyGoalProgress(goal, facts, today).riddenKm
            targetKm = goal.targetKm
        } else {
            riddenKm = periodTotals(facts, today.with(DayOfWeek.MONDAY), today).km
            targetKm = null
        }
    }

    val fraction = if (targetKm != null && targetKm > 0) {
        (riddenKm / targetKm).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }

    return WidgetState(
        hasRides = facts.isNotEmpty(),
        streakWeeks = streak.weeks,
        thisWeekSecured = streak.thisWeekDone,
        daysLeft = streak.daysLeft,
        riddenKm = riddenKm,
        targetKm = targetKm,
        fraction = fraction,
    )
}

/**
 * Das Wochenziel, das die Startseite zuletzt gezeigt hat — der Abgleich
 * zwischen App und Widget (siehe [buildWidgetState]). Gilt nur fuer die Woche,
 * fuer die es notiert wurde.
 */
data class ShownWeekTarget(val monday: LocalDate, val targetKm: Int) {
    /** Eine Zeile fuer den Schluessel-Wert-Speicher: `JJJJ-MM-TT|km`. */
    fun encode(): String = "$monday|$targetKm"

    companion object {
        /** Schluessel im Schluessel-Wert-Speicher. */
        const val STORAGE_KEY: String = "trailscape.widget.weektarget.v1"

        /** `null` bei allem, was keine gueltige Zeile ist. */
        fun decode(text: String?): ShownWeekTarget? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 2) return null
            val monday = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            val km = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return ShownWeekTarget(monday, km)
        }
    }
}
