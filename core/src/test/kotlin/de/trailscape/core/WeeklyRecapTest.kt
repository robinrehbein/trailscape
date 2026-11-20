package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests des Wochenrueckblicks (`WeeklyRecap.kt`). */
class WeeklyRecapTest {

    /** Montag, 16. November 2026; Sonntag ist der 22. */
    private val monday = LocalDate.of(2026, 11, 16)
    private val sunday = monday.plusDays(6)

    private fun fact(
        id: String,
        date: LocalDate,
        km: Double = 30.0,
        ascent: Double = 200.0,
        timeS: Int? = 5400,
        speed: Double? = 20.0,
    ) = RideFact(id, date, km, ascent, timeS, speed)

    @Test
    fun totalsAndPreviousWeek() {
        val facts = listOf(
            fact("old", monday.minusWeeks(2), km = 99.0),
            fact("p", monday.minusDays(3), km = 40.0),
            fact("a", monday, km = 20.0),
            fact("b", monday.plusDays(2), km = 30.0),
        )
        val recap = weeklyRecap(facts, monday.plusDays(4))
        assertEquals(monday, recap.monday)
        assertEquals(50.0, recap.totals.km, 1e-9)
        assertEquals(2, recap.totals.rides)
        assertEquals(40.0, recap.previous.km, 1e-9)
        assertEquals(1, recap.previous.rides)
        assertEquals(30.0, recap.longestRideKm, 1e-9)
    }

    @Test
    fun activeDaysCountDaysNotRides() {
        val facts = listOf(
            fact("a", monday),
            fact("b", monday),
            fact("c", monday.plusDays(3)),
        )
        val recap = weeklyRecap(facts, sunday)
        assertEquals(3, recap.totals.rides)
        assertEquals(2, recap.activeDays)
    }

    @Test
    fun recordsComeOnlyFromThisWeeksRidesAgainstAllEarlierOnes() {
        val facts = listOf(
            fact("early", monday.minusWeeks(3), km = 50.0, ascent = 900.0, timeS = 9000, speed = 18.0),
            // Die Vorwoche setzt einen Bestwert (Strecke), der nicht zaehlt.
            fact("prev", monday.minusDays(2), km = 60.0, ascent = 100.0, timeS = 9000, speed = 18.0),
            // Diese Woche: weiter als alles Fruehere, sonst nichts uebertroffen.
            fact("this", monday.plusDays(1), km = 70.0, ascent = 300.0, timeS = 9000, speed = 18.0),
        )
        assertEquals(listOf(RecordKind.DISTANCE), weeklyRecap(facts, sunday).records)
        // Die Vorwoche hat ihren eigenen Rekord (Strecke, 60 gegen 50).
        assertEquals(listOf(RecordKind.DISTANCE), weeklyRecap(facts, monday.minusDays(1)).records)
    }

    @Test
    fun recordsAreDistinctAndInEnumOrder() {
        val facts = listOf(
            fact("a", monday.minusWeeks(1), km = 30.0, ascent = 200.0, timeS = 5000, speed = 15.0),
            fact("b", monday, km = 40.0, ascent = 300.0, timeS = 6000, speed = 16.0),
            fact("c", monday.plusDays(1), km = 50.0, ascent = 400.0, timeS = 7000, speed = 17.0),
        )
        assertEquals(
            listOf(RecordKind.DISTANCE, RecordKind.ASCENT, RecordKind.TIME, RecordKind.SPEED),
            weeklyRecap(facts, sunday).records,
        )
    }

    @Test
    fun streakIsEvaluatedAtThatSunday() {
        val facts = listOf(
            fact("a", monday.minusWeeks(2)),
            fact("b", monday.minusWeeks(1)),
            fact("c", monday),
            // Spaeter gefahren: gehoert nicht zum Stand von damals.
            fact("d", monday.plusWeeks(1)),
        )
        // Mitten in der Woche gefragt, gerechnet wird bis zum Sonntag.
        assertEquals(3, weeklyRecap(facts, monday.plusDays(2)).streakWeeks)
        assertEquals(4, weeklyRecap(facts, monday.plusWeeks(1)).streakWeeks)
    }

    @Test
    fun kmChangePct() {
        val up = weeklyRecap(listOf(fact("p", monday.minusDays(2), km = 50.0), fact("a", monday, km = 60.0)), sunday)
        assertEquals(20, up.kmChangePct)
        val down = weeklyRecap(listOf(fact("p", monday.minusDays(2), km = 80.0), fact("a", monday, km = 60.0)), sunday)
        assertEquals(-25, down.kmChangePct)
        val none = weeklyRecap(listOf(fact("a", monday, km = 60.0)), sunday)
        assertNull(none.kmChangePct)
    }

    @Test
    fun ridesAfterTheGivenDayAreIgnored() {
        val facts = listOf(
            fact("a", monday, km = 20.0),
            fact("b", monday.plusDays(4), km = 70.0),
        )
        val recap = weeklyRecap(facts, monday.plusDays(1))
        assertEquals(1, recap.totals.rides)
        assertEquals(20.0, recap.totals.km, 1e-9)
        assertEquals(1, recap.activeDays)
        assertEquals(20.0, recap.longestRideKm, 1e-9)
    }

    @Test
    fun emptyWeekHasZeroValues() {
        val recap = weeklyRecap(emptyList(), sunday)
        assertEquals(0.0, recap.longestRideKm)
        assertEquals(0, recap.activeDays)
        assertEquals(0, recap.streakWeeks)
    }

    @Test
    fun lastWeekRecapLooksAtTheCompletedWeek() {
        val facts = listOf(fact("a", monday, km = 25.0), fact("b", monday.plusWeeks(1), km = 99.0))
        val recap = assertNotNull(lastWeekRecap(facts, monday.plusWeeks(1).plusDays(2)))
        assertEquals(monday, recap.monday)
        assertEquals(25.0, recap.totals.km, 1e-9)
    }

    @Test
    fun lastWeekRecapIsNullForEmptyWeek() {
        val facts = listOf(fact("a", monday.minusWeeks(1)), fact("b", monday.plusWeeks(1)))
        assertNull(lastWeekRecap(facts, monday.plusWeeks(1).plusDays(2)))
        assertNull(lastWeekRecap(emptyList(), monday))
    }
}
