package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests fuer „Was die Tour gebracht hat" (`RideHighlights.kt`). */
class RideHighlightsTest {

    /** Mittwoch, 18. November 2026 — die Woche beginnt am 16. */
    private val today = LocalDate.of(2026, 11, 18)

    private fun day(dayOfMonth: Int, month: Int = 11) = LocalDate.of(2026, month, dayOfMonth)

    private fun fact(
        id: String,
        date: LocalDate,
        km: Double = 10.0,
        ascent: Double = 0.0,
        timeS: Int? = 1800,
        speed: Double? = 20.0,
    ) = RideFact(id, date, km, ascent, timeS, speed)

    private fun effort(id: String, timeS: Int, at: Long) = RouteEffort(id, at, timeS, 25.0)

    private fun comparison(efforts: List<RouteEffort>, currentId: String): RouteComparison {
        val byTime = efforts.sortedWith(compareBy({ it.timeS }, { it.createdAt }))
        val index = efforts.indexOfFirst { it.rideId == currentId }
        return RouteComparison(
            efforts = efforts,
            rank = byTime.indexOfFirst { it.rideId == currentId } + 1,
            previous = efforts.getOrNull(index - 1),
            best = byTime.first(),
            current = efforts[index],
        )
    }

    @Test
    fun unknownRideIsEmpty() {
        val facts = listOf(fact("a", day(16)))
        assertEquals(emptyList(), rideHighlights("x", facts, today, weekTargetKm = 50, routeComparison = null))
    }

    @Test
    fun nothingAppliesGivesEmptyList() {
        // Drei kurze Touren: keine Stufe (50 km, 5 Touren) ueberschritten,
        // kein Bestwert (gleich weit), die dritte nicht die erste ihrer Woche,
        // keine Challenge erreicht, kein Wochenziel.
        val facts = listOf(fact("a", day(2)), fact("b", day(16)), fact("c", day(17)))
        assertEquals(emptyList(), rideHighlights("c", facts, today, weekTargetKm = null, routeComparison = null))
    }

    @Test
    fun weekGoalIsClaimedOnlyByTheRideThatReachedIt() {
        val facts = listOf(
            fact("a", day(16), km = 40.0),
            fact("b", day(17), km = 25.0),
            fact("c", day(18), km = 10.0),
        )
        val b = rideHighlights("b", facts, today, weekTargetKm = 60, routeComparison = null, max = 10)
        assertEquals(
            listOf(RideHighlight.WeekGoalReached(60, 65.0)),
            b.filterIsInstance<RideHighlight.WeekGoalReached>(),
        )
        // Die Fahrt danach: Ziel schon erreicht — weder „geschafft" noch
        // „noch x km". Sonst trifft nichts zu, also bleibt die Liste leer.
        assertEquals(emptyList(), rideHighlights("c", facts, today, weekTargetKm = 60, routeComparison = null))
    }

    @Test
    fun weekGoalNeedsARideOfTheCurrentWeek() {
        val facts = listOf(fact("a", day(9), km = 70.0))
        val highlights = rideHighlights("a", facts, today, weekTargetKm = 60, routeComparison = null, max = 10)
        assertTrue(highlights.none { it is RideHighlight.WeekGoalReached || it is RideHighlight.WeekGoalProgress })
    }

    @Test
    fun weekGoalProgressIsOnlyTheFallback() {
        val facts = listOf(fact("a", day(16), km = 40.0), fact("b", day(17), km = 5.0))
        assertEquals(
            listOf(RideHighlight.WeekGoalProgress(targetKm = 100, riddenKm = 45.0, remainingKm = 55.0, daysLeft = 5)),
            rideHighlights("b", facts, today, weekTargetKm = 100, routeComparison = null),
        )
        // Ohne Ziel: nichts.
        assertEquals(emptyList(), rideHighlights("b", facts, today, weekTargetKm = null, routeComparison = null))
        // Mit einem echten Erfolg (Bestweite) faellt der Rueckfall weg.
        val longer = listOf(fact("a", day(16), km = 20.0), fact("b", day(17), km = 24.0))
        val highlights = rideHighlights("b", longer, today, weekTargetKm = 100, routeComparison = null, max = 10)
        assertTrue(highlights.none { it is RideHighlight.WeekGoalProgress })
        assertTrue(highlights.first() is RideHighlight.Record)
    }

    @Test
    fun distanceRecordComesFirstAndSwallowsTimeRecordAndLongestRideMilestone() {
        val facts = listOf(
            fact("a", day(2), km = 30.0, timeS = 5400),
            fact("b", day(16), km = 61.0, timeS = 10800),
        )
        val highlights = rideHighlights("b", facts, today, weekTargetKm = null, routeComparison = null, max = 10)
        assertEquals(RideHighlight.Record(RecordGain(RecordKind.DISTANCE, 61.0, 30.0)), highlights.first())
        assertTrue(highlights.none { it is RideHighlight.Record && it.gain.kind == RecordKind.TIME })
        assertTrue(
            highlights.none {
                it is RideHighlight.MilestoneReached && it.milestone.kind == MilestoneKind.LONGEST_RIDE
            },
        )
    }

    @Test
    fun timeRecordAloneIsReported() {
        val facts = listOf(
            fact("a", day(2), km = 30.0, timeS = 3600),
            fact("b", day(16), km = 15.0, timeS = 5400),
        )
        val highlights = rideHighlights("b", facts, today, weekTargetKm = null, routeComparison = null)
        assertEquals(listOf(RideHighlight.Record(RecordGain(RecordKind.TIME, 5400.0, 3600.0))), highlights)
    }

    @Test
    fun routeBestNeedsAPredecessorAndReportsTheGap() {
        val facts = listOf(fact("a", day(2)), fact("b", day(17)))
        val route = comparison(listOf(effort("a", 3600, 1), effort("b", 3475, 2)), currentId = "b")
        assertEquals(
            listOf(RideHighlight.RouteBest(count = 2, deltaToBestOtherS = -125)),
            rideHighlights("b", facts, today, weekTargetKm = null, routeComparison = route),
        )
        // Ein Vergleich fuer eine andere Tour zaehlt nicht.
        val other = rideHighlights("a", facts, today, weekTargetKm = null, routeComparison = route, max = 10)
        assertTrue(other.none { it is RideHighlight.RouteBest || it is RideHighlight.RouteFaster })
    }

    @Test
    fun routeFasterThanLastTimeButNotTheBest() {
        val facts = listOf(fact("a", day(2)), fact("b", day(3)), fact("c", day(17)))
        val route = comparison(
            listOf(effort("a", 3000, 1), effort("b", 3600, 2), effort("c", 3400, 3)),
            currentId = "c",
        )
        assertEquals(
            listOf(RideHighlight.RouteFaster(deltaToPreviousS = -200, rank = 2, count = 3)),
            rideHighlights("c", facts, today, weekTargetKm = null, routeComparison = route),
        )
        // Langsamer als beim letzten Mal: kein Lob.
        val slower = comparison(
            listOf(effort("a", 3000, 1), effort("b", 3300, 2), effort("c", 3400, 3)),
            currentId = "c",
        )
        assertEquals(emptyList(), rideHighlights("c", facts, today, weekTargetKm = null, routeComparison = slower))
    }

    @Test
    fun streakIsExtendedByTheFirstRideOfTheWeekOnly() {
        val facts = listOf(fact("a", day(2)), fact("b", day(9)), fact("c", day(16)), fact("d", day(16)))
        assertEquals(
            listOf(RideHighlight.StreakExtended(3)),
            rideHighlights("c", facts, today, weekTargetKm = null, routeComparison = null),
        )
        assertEquals(emptyList(), rideHighlights("d", facts, today, weekTargetKm = null, routeComparison = null))
        // Eine Woche allein ist noch keine Serie.
        val single = listOf(fact("a", day(2)), fact("c", day(16)))
        assertEquals(emptyList(), rideHighlights("c", single, today, weekTargetKm = null, routeComparison = null))
    }

    @Test
    fun challengeIsClaimedOnlyByTheRideThatCompletedIt() {
        // Ohne Vormonate gilt der Startsatz: u. a. „eine Tour ueber 30 km".
        val facts = listOf(fact("a", day(3), km = 35.0), fact("b", day(17), km = 40.0))
        val a = rideHighlights("a", facts, today, weekTargetKm = null, routeComparison = null, max = 10)
        assertEquals(
            listOf(ChallengeKind.LONG_RIDE),
            a.filterIsInstance<RideHighlight.ChallengeReached>().map { it.challenge.kind },
        )
        val b = rideHighlights("b", facts, today, weekTargetKm = null, routeComparison = null, max = 10)
        assertTrue(b.none { it is RideHighlight.ChallengeReached })
    }

    @Test
    fun milestoneIsReported() {
        val facts = listOf(fact("a", day(2), km = 30.0), fact("b", day(17), km = 25.0))
        // Bestwert? Nein (25 < 30). Serie? Nein (Woche dazwischen leer).
        assertEquals(
            listOf(RideHighlight.MilestoneReached(Milestone(MilestoneKind.TOTAL_KM, 50.0))),
            rideHighlights("b", facts, today, weekTargetKm = null, routeComparison = null),
        )
    }

    @Test
    fun orderAndCut() {
        val facts = listOf(
            fact("a", day(2), km = 20.0, ascent = 100.0, timeS = 3600),
            fact("b", day(9), km = 20.0, ascent = 100.0, timeS = 3600),
            fact("c", day(16), km = 45.0, ascent = 400.0, timeS = 7000),
        )
        val route = comparison(listOf(effort("a", 3600, 1), effort("c", 3500, 3)), currentId = "c")
        val all = rideHighlights("c", facts, today, weekTargetKm = 40, routeComparison = route, max = 20)
        assertEquals(
            listOf(
                RideHighlight.Record(RecordGain(RecordKind.DISTANCE, 45.0, 20.0)),
                RideHighlight.RouteBest(count = 2, deltaToBestOtherS = -100),
                RideHighlight.Record(RecordGain(RecordKind.ASCENT, 400.0, 100.0)),
                RideHighlight.WeekGoalReached(40, 45.0),
            ),
            all.take(4),
        )
        assertEquals(ChallengeKind.LONG_RIDE, (all[4] as RideHighlight.ChallengeReached).challenge.kind)
        assertEquals(
            listOf(
                RideHighlight.MilestoneReached(Milestone(MilestoneKind.TOTAL_KM, 50.0)),
                RideHighlight.MilestoneReached(Milestone(MilestoneKind.TOTAL_ASCENT, 500.0)),
                RideHighlight.StreakExtended(3),
            ),
            all.drop(5),
        )
        // Standard: die ersten drei.
        assertEquals(all.take(3), rideHighlights("c", facts, today, weekTargetKm = 40, routeComparison = route))
        assertEquals(emptyList(), rideHighlights("c", facts, today, weekTargetKm = 40, routeComparison = route, max = 0))
    }
}
