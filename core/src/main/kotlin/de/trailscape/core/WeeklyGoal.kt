package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * # Wochenziel ohne Plan
 *
 * Wer keinen Trainingsplan hat, hat bisher auch kein Wochenziel. Dieses Ziel
 * waechst sanft mit dem Fahrer mit und macht „diese Woche" zu etwas, das man
 * zu Ende bringen will — ohne zu ueberfordern.
 *
 * Reine Rechnung ueber [RideFact]s wie `Motivation.kt`: kein Android, keine
 * Uhr, keine Zeitzone; „heute" kommt als Parameter. Die Art des Ziels ist ein
 * [WeeklyGoalKind] — Saetze baut die Oberflaeche.
 *
 * ## Vorrang
 * Treffen mehrere Regeln zu, gilt die erste dieser Liste:
 * [WeeklyGoalKind.EINSTIEG], [WeeklyGoalKind.WIEDEREINSTIEG],
 * [WeeklyGoalKind.ENTLASTUNG], [WeeklyGoalKind.HALTEN], [WeeklyGoalKind.AUFBAU].
 * Wer nach einer Pause neu beginnt, bekommt also nie ein Steigerungsziel, und
 * eine Entlastungswoche geht einer schwachen Vorwoche vor (sie senkt das Ziel
 * staerker).
 */
enum class WeeklyGoalKind {
    /** Noch keine abgeschlossene Woche: fester Startwert. */
    EINSTIEG,

    /** Gleichmaessig gefahren: leichte Steigerung ueber dem Wochenschnitt. */
    AUFBAU,

    /** Schwache Vorwoche: nicht steigern, nur den Schnitt wieder erreichen. */
    HALTEN,

    /** Drei steigende Wochen in Folge: eine leichtere Woche zur Erholung. */
    ENTLASTUNG,

    /** Zwei Wochen Pause: sanfter Wiedereinstieg unter dem frueheren Schnitt. */
    WIEDEREINSTIEG,
}

/**
 * Das Kilometerziel einer Woche.
 *
 * @property monday die Woche, fuer die das Ziel gilt.
 * @property targetKm auf 5 km gerundet, mindestens [minWeeklyGoalKm].
 * @property basisKm die „typische Woche", aus der es abgeleitet wurde
 *   (0.0 bei [WeeklyGoalKind.EINSTIEG]).
 */
data class WeeklyGoal(
    val monday: LocalDate,
    val targetKm: Int,
    val basisKm: Double,
    val kind: WeeklyGoalKind,
)

/** Startwert ohne Vorgeschichte: eine Strecke, die fast jeder an einem Wochenende schafft. */
const val einstiegWeeklyKm: Int = 30

/** Wie viele abgeschlossene Wochen die „typische Woche" bestimmen — lang genug fuer einen Ausreisser, kurz genug fuer die aktuelle Form. */
const val weeklyGoalWindowWeeks: Int = 6

/** Aufschlag beim Aufbau — gerade genug, dass es ein Fortschritt ist, nicht genug, um zu ueberlasten. */
const val aufbauFactor: Double = 1.08

/**
 * Obergrenze des Sprungs in km. Bei grossen Wochenumfaengen waeren 8 % schnell
 * ein ganzer Tag mehr; die absolute Grenze haelt den Schritt klein.
 */
const val aufbauMaxJumpKm: Double = 25.0

/** Eine Entlastungswoche liegt bei 80 % des Schnitts — spuerbar leichter, aber kein Ausfall. */
const val entlastungFactor: Double = 0.8

/**
 * Unter diesem Anteil des Schnitts gilt die Vorwoche als schwach. Dann ist
 * Steigern das falsche Signal: erst den Schnitt wieder erreichen.
 */
const val weakWeekShare: Double = 0.6

/** Nach zwei Wochen Pause starten wir bei 60 % des frueheren Schnitts — Gewoehnung statt Frust. */
const val wiedereinstiegFactor: Double = 0.6

/** Ohne Fahrten im Fenster zaehlen die letzten so vielen Wochen mit Fahrten aus der ganzen Historie — der Schnitt von frueher, nicht der von ganz frueher. */
const val wiedereinstiegHistoryWeeks: Int = 4

/** Darunter ist ein Wochenziel kein Ziel mehr, sondern ein Spaziergang. */
const val minWeeklyGoalKm: Int = 10

/** Darueber ist es kein Wochenziel mehr, sondern ein Rechenfehler (oder eine Weltreise). */
const val maxWeeklyGoalKm: Int = 600

private const val roundEps = 1e-9

private fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

// Das Epsilon faengt Gleitkommafehler wie 50 x 1,1 = 55,000000000000007 ab,
// die sonst eine Stufe weiterspraengen.
private fun ceil5(km: Double): Int = (ceil(km / 5.0 - roundEps) * 5).toInt()

private fun floor5(km: Double): Int = (floor(km / 5.0 + roundEps) * 5).toInt()

private fun round5(km: Double): Int = (floor(km / 5.0 + 0.5 + roundEps) * 5).toInt()

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * Das Ziel fuer die Woche, in die [today] faellt.
 *
 * Es haengt **nur von Touren vor dem Montag dieser Woche** ab. Ein Ziel, das
 * sich mit jeder Fahrt verschiebt, liesse sich nie erreichen — und eine Tour
 * am Dienstag darf es nicht hochziehen. Touren nach [today] zaehlen nicht.
 *
 * `null` nur, wenn es keine gefahrene Tour gibt.
 */
fun suggestWeeklyGoal(facts: List<RideFact>, today: LocalDate): WeeklyGoal? {
    val monday = mondayOf(today)
    val history = facts.filter { it.date.isBefore(monday) }
    if (history.isEmpty()) {
        val rodeThisWeek = facts.any { !it.date.isAfter(today) }
        return if (rodeThisWeek) WeeklyGoal(monday, einstiegWeeklyKm, 0.0, WeeklyGoalKind.EINSTIEG) else null
    }

    val kmByWeek = history.groupBy { mondayOf(it.date) }.mapValues { (_, rides) -> rides.sumOf { it.distanceKm } }
    // Index 0 = Vorwoche, 1 = die davor, ...; null = keine Fahrt in der Woche.
    val recent = (1..weeklyGoalWindowWeeks).map { kmByWeek[monday.minusWeeks(it.toLong())] }
    val windowKm = recent.filterNotNull()

    fun goal(target: Int, basis: Double, kind: WeeklyGoalKind) =
        WeeklyGoal(monday, target.coerceIn(minWeeklyGoalKm, maxWeeklyGoalKm), basis, kind)

    if (recent[0] == null && recent[1] == null) {
        val basis = if (windowKm.isNotEmpty()) {
            median(windowKm)
        } else {
            median(kmByWeek.entries.sortedByDescending { it.key }.take(wiedereinstiegHistoryWeeks).map { it.value })
        }
        return goal(round5(basis * wiedereinstiegFactor), basis, WeeklyGoalKind.WIEDEREINSTIEG)
    }

    // Eine der beiden letzten Wochen hatte eine Fahrt, das Fenster ist also nicht leer.
    val basis = median(windowKm)

    // Aelteste zuerst: drei Wochen vor der Vorwoche bis zur Vorwoche.
    val lastThree = listOf(recent[2], recent[1], recent[0])
    if (lastThree.all { it != null } && lastThree[0]!! < lastThree[1]!! && lastThree[1]!! < lastThree[2]!!) {
        return goal(round5(basis * entlastungFactor), basis, WeeklyGoalKind.ENTLASTUNG)
    }

    if ((recent[0] ?: 0.0) < basis * weakWeekShare) {
        return goal(round5(basis), basis, WeeklyGoalKind.HALTEN)
    }

    // Die Kappung rundet nach unten, sonst laege das Ziel ueber der Sprunggrenze.
    val target = min(ceil5(basis * aufbauFactor), floor5(basis + aufbauMaxJumpKm))
    return goal(target, basis, WeeklyGoalKind.AUFBAU)
}

/**
 * Stand einer Woche zum Wochenziel.
 *
 * @property riddenKm Kilometer von [WeeklyGoal.monday] bis heute (hoechstens Sonntag).
 * @property daysLeft Tage bis einschliesslich Sonntag, heute zaehlt mit (1…7).
 *   Fuer eine vergangene Woche bleibt es bei 1.
 */
data class WeeklyGoalProgress(
    val goal: WeeklyGoal,
    val riddenKm: Double,
    val daysLeft: Int,
) {
    val remainingKm: Double get() = (goal.targetKm - riddenKm).coerceAtLeast(0.0)
    val fraction: Double get() = (riddenKm / goal.targetKm).coerceIn(0.0, 1.0)
    val reached: Boolean get() = riddenKm >= goal.targetKm
}

fun weeklyGoalProgress(goal: WeeklyGoal, facts: List<RideFact>, today: LocalDate): WeeklyGoalProgress {
    val sunday = goal.monday.plusDays(6)
    val to = if (today.isAfter(sunday)) sunday else today
    val ridden = periodTotals(facts, goal.monday, to).km
    val daysLeft = (ChronoUnit.DAYS.between(today, sunday).toInt() + 1).coerceIn(1, 7)
    return WeeklyGoalProgress(goal, ridden, daysLeft)
}
