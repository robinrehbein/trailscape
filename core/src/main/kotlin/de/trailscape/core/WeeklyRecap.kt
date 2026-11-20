package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * # Wochenrueckblick — eine Woche in einem Wert
 *
 * Die Sonntagserinnerung ohne Trainingsplan und spaeter eine Rueckschau in der
 * App brauchen dieselbe Auskunft: Was war diese Woche, verglichen mit der
 * davor? Statt sie an zwei Stellen aus [periodTotals], [recordsSetBy] und
 * [weekStreak] zusammenzusetzen, steht sie hier einmal — als reine Rechnung
 * ueber [RideFact]s, ohne Uhr und Zeitzone ("heute" kommt als Parameter).
 */

/**
 * Eine Kalenderwoche (Mo–So) im Rueckblick.
 *
 * @property monday der Montag der betrachteten Woche.
 * @property totals Summen dieser Woche.
 * @property previous Summen der Woche davor.
 * @property activeDays Kalendertage mit mindestens einer Tour — zwei Touren
 *   an einem Tag sind ein aktiver Tag, weil es um Regelmaessigkeit geht.
 * @property longestRideKm weiteste Tour der Woche, 0.0 ohne Tour.
 * @property records Bestwerte, die Touren dieser Woche gesetzt haben (je Art
 *   einmal, in Reihenfolge von [RecordKind]).
 * @property streakWeeks Wochenserie zum *Ende* dieser Woche, nicht zu heute:
 *   Der Rueckblick auf eine alte Woche soll den Stand von damals nennen.
 */
data class WeeklyRecap(
    val monday: LocalDate,
    val totals: PeriodTotals,
    val previous: PeriodTotals,
    val activeDays: Int,
    val longestRideKm: Double,
    val records: List<RecordKind>,
    val streakWeeks: Int,
) {
    /**
     * Aenderung der Kilometer gegenueber der Vorwoche in Prozent; `null`, wenn
     * die Vorwoche leer war (dann gibt es nichts, wovon ein Prozentwert
     * sprechen koennte).
     */
    val kmChangePct: Int?
        get() = if (previous.km > 0) ((totals.km - previous.km) / previous.km * 100).roundToInt() else null
}

/**
 * Rueckblick auf die Woche, in der [day] liegt. Touren nach [day] werden
 * ignoriert — fuer die laufende Woche heisst das „bis jetzt", und auch die
 * Serie und die Bestwerte sehen nur, was bis dahin bekannt war.
 */
fun weeklyRecap(facts: List<RideFact>, day: LocalDate): WeeklyRecap {
    val monday = day.with(DayOfWeek.MONDAY)
    val sunday = monday.plusDays(6)
    val end = if (sunday.isAfter(day)) day else sunday
    // Reihenfolge bleibt erhalten: [recordsSetBy] liest „frueher" als „davor".
    val known = facts.filter { !it.date.isAfter(end) }
    val inWeek = known.filter { !it.date.isBefore(monday) }

    val records = inWeek
        .flatMap { ride -> recordsSetBy(ride.id, known).map { it.kind } }
        .distinct()
        .sortedBy { it.ordinal }

    return WeeklyRecap(
        monday = monday,
        totals = periodTotals(known, monday, end),
        previous = periodTotals(known, monday.minusWeeks(1), monday.minusDays(1)),
        activeDays = inWeek.map { it.date }.distinct().size,
        longestRideKm = inWeek.maxOfOrNull { it.distanceKm } ?: 0.0,
        records = records,
        streakWeeks = weekStreak(known, sunday).weeks,
    )
}

/**
 * Rueckblick auf die letzte **abgeschlossene** Woche vor der Woche von
 * [today]; `null`, wenn in ihr nicht gefahren wurde — eine leere Woche ist
 * kein Rueckblick, sondern ein Fall fuer den Anstupser.
 */
fun lastWeekRecap(facts: List<RideFact>, today: LocalDate): WeeklyRecap? =
    weeklyRecap(facts, today.with(DayOfWeek.MONDAY).minusDays(1)).takeIf { it.totals.rides > 0 }
