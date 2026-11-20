package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests des Wochenziels ohne Plan (`WeeklyGoal.kt`). */
class WeeklyGoalTest {

    /** Mittwoch, 18. November 2026; Montag der Woche ist der 16. */
    private val today = LocalDate.of(2026, 11, 18)
    private val monday = LocalDate.of(2026, 11, 16)

    private fun fact(date: LocalDate, km: Double) = RideFact("r$date-$km", date, km, 0.0, null, null)

    /** Eine Tour in der Woche, die [weeksBack] Wochen vor dieser liegt (Mittwoch). */
    private fun week(weeksBack: Int, km: Double) = fact(today.minusWeeks(weeksBack.toLong()), km)

    private fun goal(facts: List<RideFact>) = assertNotNull(suggestWeeklyGoal(facts, today))

    // --------------------------------------------------------------- Arten

    @Test
    fun emptyInputGivesNull() {
        assertNull(suggestWeeklyGoal(emptyList(), today))
    }

    @Test
    fun onlyFutureRidesGiveNull() {
        assertNull(suggestWeeklyGoal(listOf(fact(today.plusDays(1), 40.0)), today))
    }

    @Test
    fun einstiegWhenOnlyThisWeekHasRides() {
        val g = goal(listOf(fact(monday, 20.0), fact(today, 15.0)))
        assertEquals(WeeklyGoalKind.EINSTIEG, g.kind)
        assertEquals(30, g.targetKm)
        assertEquals(0.0, g.basisKm)
        assertEquals(monday, g.monday)
    }

    @Test
    fun aufbauRoundsUpToNextFiveKm() {
        // 40 * 1,08 = 43,2 -> 45
        val g = goal((1..4).map { week(it, 40.0) })
        assertEquals(WeeklyGoalKind.AUFBAU, g.kind)
        assertEquals(40.0, g.basisKm)
        assertEquals(45, g.targetKm)
    }

    @Test
    fun aufbauFiftyKmBasisGivesFiftyFive() {
        val g = goal((1..3).map { week(it, 50.0) })
        assertEquals(55, g.targetKm)
    }

    @Test
    fun aufbauFloatingPointNoiseDoesNotJumpAStep() {
        // 5 * 1,08 = 5,4 -> 10 (Minimum), 62,5 * 1,08 = 67,5 -> 70; entscheidend ist ein Wert,
        // dessen Produkt knapp ueber einem 5er liegt: 125/1,08 * 1,08 ist nicht exakt 125.
        val basis = 125.0 / aufbauFactor
        val g = goal((1..3).map { week(it, basis) })
        assertEquals(125, g.targetKm)
    }

    @Test
    fun aufbauCapsAbsoluteJump() {
        // 400 * 1,08 = 432, Grenze 425.
        val g = goal((1..4).map { week(it, 400.0) })
        assertEquals(WeeklyGoalKind.AUFBAU, g.kind)
        assertEquals(425, g.targetKm)
    }

    @Test
    fun capNeverExceedsBasisPlus25EvenWhenNotMultipleOfFive() {
        // 303 * 1,08 = 327,24 -> 330, Grenze 328 -> auf 325 abgerundet.
        val g = goal((1..3).map { week(it, 303.0) })
        assertEquals(325, g.targetKm)
        assertTrue(g.targetKm <= g.basisKm + aufbauMaxJumpKm)
    }

    @Test
    fun targetIsClampedToTenAndSixHundred() {
        assertEquals(10, goal(listOf(week(1, 1.0), week(2, 1.0))).targetKm)
        assertEquals(600, goal((1..4).map { week(it, 900.0) }).targetKm)
    }

    @Test
    fun basisIsMedianOfWeeksWithRidesEvenWithGaps() {
        // Wochen 1, 2, 4, 6 mit 20, 100, 40, 60; Wochen 3 und 5 leer -> Median (40 + 60) / 2 = 50.
        val g = goal(listOf(week(1, 20.0), week(2, 100.0), week(4, 40.0), week(6, 60.0)))
        assertEquals(50.0, g.basisKm)
        // Vorwoche 20 < 60 % von 50 -> HALTEN
        assertEquals(WeeklyGoalKind.HALTEN, g.kind)
        assertEquals(50, g.targetKm)
    }

    @Test
    fun singleWeekIsUsedAsBasis() {
        val g = goal(listOf(week(1, 50.0)))
        assertEquals(50.0, g.basisKm)
        assertEquals(WeeklyGoalKind.AUFBAU, g.kind)
        assertEquals(55, g.targetKm)
    }

    @Test
    fun weeksOlderThanSixAreIgnoredForBasis() {
        val g = goal(listOf(week(1, 40.0), week(7, 500.0)))
        assertEquals(40.0, g.basisKm)
    }

    @Test
    fun ridesOfSeveralDaysAreSummedPerWeek() {
        val g = goal(listOf(fact(monday.minusDays(7), 20.0), fact(monday.minusDays(5), 20.0)))
        assertEquals(40.0, g.basisKm)
    }

    @Test
    fun haltenAfterWeakWeekRoundsToNearestFive() {
        // Median(10, 62, 58) = 58; Vorwoche 10 < 34,8 -> HALTEN, 58 -> 60.
        val g = goal(listOf(week(1, 10.0), week(2, 62.0), week(3, 58.0)))
        assertEquals(WeeklyGoalKind.HALTEN, g.kind)
        assertEquals(58.0, g.basisKm)
        assertEquals(60, g.targetKm)
    }

    @Test
    fun haltenWhenLastWeekEmptyButWeekBeforeRode() {
        val g = goal(listOf(week(2, 60.0), week(3, 60.0)))
        assertEquals(WeeklyGoalKind.HALTEN, g.kind)
        assertEquals(60, g.targetKm)
    }

    @Test
    fun lastWeekExactlyAtThresholdIsNotWeak() {
        // Basis 50, Vorwoche 30 = genau 60 % -> nicht darunter.
        val g = goal(listOf(week(1, 30.0), week(2, 50.0), week(3, 70.0)))
        assertEquals(50.0, g.basisKm)
        assertEquals(WeeklyGoalKind.AUFBAU, g.kind)
    }

    @Test
    fun entlastungAfterThreeStrictlyIncreasingWeeks() {
        // 30, 40, 50 (alt -> neu); Basis 40 -> 0,8 * 40 = 32 -> 30.
        val g = goal(listOf(week(3, 30.0), week(2, 40.0), week(1, 50.0)))
        assertEquals(WeeklyGoalKind.ENTLASTUNG, g.kind)
        assertEquals(40.0, g.basisKm)
        assertEquals(30, g.targetKm)
    }

    @Test
    fun noEntlastungWhenNotStrictlyIncreasing() {
        val g = goal(listOf(week(3, 30.0), week(2, 40.0), week(1, 40.0)))
        assertEquals(WeeklyGoalKind.AUFBAU, g.kind)
    }

    @Test
    fun noEntlastungWhenOneOfTheThreeWeeksIsEmpty() {
        val g = goal(listOf(week(4, 10.0), week(2, 40.0), week(1, 50.0)))
        assertTrue(g.kind != WeeklyGoalKind.ENTLASTUNG)
    }

    @Test
    fun entlastungBeatsHalten() {
        val facts = listOf(week(6, 200.0), week(5, 200.0), week(4, 200.0), week(3, 10.0), week(2, 20.0), week(1, 30.0))
        // Median 115, Vorwoche 30 < 69 waere HALTEN; ENTLASTUNG hat Vorrang: 0,8 * 115 = 92 -> 90.
        val g = goal(facts)
        assertEquals(WeeklyGoalKind.ENTLASTUNG, g.kind)
        assertEquals(115.0, g.basisKm)
        assertEquals(90, g.targetKm)
    }

    @Test
    fun wiedereinstiegAfterTwoEmptyWeeksUsesWindow() {
        // Wochen 3 und 4 mit 50 -> Basis 50, 60 % = 30.
        val g = goal(listOf(week(3, 50.0), week(4, 50.0)))
        assertEquals(WeeklyGoalKind.WIEDEREINSTIEG, g.kind)
        assertEquals(50.0, g.basisKm)
        assertEquals(30, g.targetKm)
    }

    @Test
    fun wiedereinstiegFromOldHistoryUsesLastFourWeeksWithRides() {
        // Nichts in den letzten sechs Wochen; Historie: 100, 100, 80, 80 und ein uralter Ausreisser.
        val g = goal(listOf(week(10, 100.0), week(11, 100.0), week(12, 80.0), week(13, 80.0), week(30, 1000.0)))
        assertEquals(WeeklyGoalKind.WIEDEREINSTIEG, g.kind)
        assertEquals(90.0, g.basisKm)
        assertEquals(55, g.targetKm) // 54 -> 55
    }

    @Test
    fun wiedereinstiegBeatsEntlastung() {
        // Drei steigende Wochen, aber danach zwei leere.
        val g = goal(listOf(week(5, 30.0), week(4, 40.0), week(3, 50.0)))
        assertEquals(WeeklyGoalKind.WIEDEREINSTIEG, g.kind)
    }

    @Test
    fun wiedereinstiegIgnoresRidesOfThisWeek() {
        val g = goal(listOf(week(4, 50.0), fact(monday, 5.0)))
        assertEquals(WeeklyGoalKind.WIEDEREINSTIEG, g.kind)
    }

    // ------------------------------------------------------------ Stabilitaet

    @Test
    fun goalIsStableWithinTheWeek() {
        val before = listOf(week(1, 40.0), week(2, 50.0), week(3, 45.0))
        val onMonday = suggestWeeklyGoal(before, monday)
        val onTuesday = suggestWeeklyGoal(before + fact(monday.plusDays(1), 200.0), monday.plusDays(1))
        val onSunday = suggestWeeklyGoal(
            before + fact(monday.plusDays(1), 200.0) + fact(monday.plusDays(5), 100.0),
            monday.plusDays(6),
        )
        assertNotNull(onMonday)
        assertEquals(onMonday, onTuesday)
        assertEquals(onMonday, onSunday)
    }

    @Test
    fun futureRidesDoNotInfluenceGoal() {
        val before = listOf(week(1, 40.0), week(2, 40.0))
        val withFuture = before + fact(today.plusDays(2), 500.0) + fact(monday.plusWeeks(1), 500.0)
        assertEquals(suggestWeeklyGoal(before, today), suggestWeeklyGoal(withFuture, today))
    }

    // ------------------------------------------------------------ Fortschritt

    private val sampleGoal = WeeklyGoal(monday, 50, 46.0, WeeklyGoalKind.AUFBAU)

    @Test
    fun progressCountsThisWeekOnly() {
        val facts = listOf(
            fact(monday.minusDays(1), 99.0), // Sonntag davor
            fact(monday, 10.0),
            fact(today, 15.0),
            fact(today.plusDays(1), 99.0), // Zukunft
        )
        val p = weeklyGoalProgress(sampleGoal, facts, today)
        assertEquals(25.0, p.riddenKm)
        assertEquals(25.0, p.remainingKm)
        assertEquals(0.5, p.fraction)
        assertFalse(p.reached)
        assertEquals(5, p.daysLeft) // Mi bis So
    }

    @Test
    fun progressClampsAndReaches() {
        val p = weeklyGoalProgress(sampleGoal, listOf(fact(monday, 70.0)), today)
        assertEquals(0.0, p.remainingKm)
        assertEquals(1.0, p.fraction)
        assertTrue(p.reached)
    }

    @Test
    fun progressReachedExactlyAtTarget() {
        val p = weeklyGoalProgress(sampleGoal, listOf(fact(monday, 50.0)), today)
        assertTrue(p.reached)
        assertEquals(0.0, p.remainingKm)
    }

    @Test
    fun daysLeftOnMondayIsSevenAndOnSundayOne() {
        assertEquals(7, weeklyGoalProgress(sampleGoal, emptyList(), monday).daysLeft)
        val sunday = monday.plusDays(6)
        val p = weeklyGoalProgress(sampleGoal, listOf(fact(sunday, 12.0)), sunday)
        assertEquals(1, p.daysLeft)
        assertEquals(12.0, p.riddenKm)
    }

    @Test
    fun progressOfPastWeekStopsAtSunday() {
        val later = monday.plusDays(8)
        val p = weeklyGoalProgress(sampleGoal, listOf(fact(monday, 10.0), fact(later, 40.0)), later)
        assertEquals(10.0, p.riddenKm)
        assertEquals(1, p.daysLeft)
    }
}
