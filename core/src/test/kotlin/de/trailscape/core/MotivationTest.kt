package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests der Motivationsrechnung (`Motivation.kt`). */
class MotivationTest {

    /** Mittwoch, 18. November 2026. */
    private val today = LocalDate.of(2026, 11, 18)

    private fun fact(
        id: String,
        date: LocalDate,
        km: Double = 30.0,
        ascent: Double = 200.0,
        timeS: Int? = 5400,
        speed: Double? = 20.0,
    ) = RideFact(id, date, km, ascent, timeS, speed)

    // ------------------------------------------------------------ rideFacts

    @Test
    fun rideFactsDropsPlannedAndSortsByTime() {
        fun ride(id: String, at: Long, planned: Boolean = false) = RideSummary(
            id = id, name = id, createdAt = at, updatedAt = at,
            stats = RideStats(distanceKm = 10.0, ascentM = 0.0, descentM = 0.0, durationS = 100, movingTimeS = null),
            planned = planned,
        )
        val facts = rideFacts(listOf(ride("b", 2000), ride("p", 1500, planned = true), ride("a", 1000))) {
            LocalDate.ofEpochDay(it / 1000)
        }
        assertEquals(listOf("a", "b"), facts.map { it.id })
        // Ohne Bewegungszeit zaehlt die Gesamtdauer.
        assertEquals(100, facts.first().timeS)
    }

    // --------------------------------------------------------------- Serie

    @Test
    fun streakCountsConsecutiveWeeksIncludingThisOne() {
        val facts = listOf(
            fact("a", today.minusWeeks(2)),
            fact("b", today.minusWeeks(1)),
            fact("c", today.minusDays(1)),
        )
        val streak = weekStreak(facts, today)
        assertEquals(3, streak.weeks)
        assertTrue(streak.thisWeekDone)
        assertFalse(streak.atRisk)
        assertEquals(3, streak.best)
    }

    @Test
    fun streakStaysAliveWhileThisWeekIsStillOpen() {
        val facts = listOf(fact("a", today.minusWeeks(2)), fact("b", today.minusWeeks(1)))
        val streak = weekStreak(facts, today)
        assertEquals(2, streak.weeks)
        assertFalse(streak.thisWeekDone)
        assertTrue(streak.atRisk)
        // Mittwoch: Mi, Do, Fr, Sa, So.
        assertEquals(5, streak.daysLeft)
    }

    @Test
    fun streakBreaksAfterAnEmptyWeekButRemembersTheBest() {
        val facts = listOf(
            fact("a", today.minusWeeks(6)),
            fact("b", today.minusWeeks(5)),
            fact("c", today.minusWeeks(4)),
            // Woche -3 und -2 leer
            fact("d", today.minusWeeks(1)),
        )
        val streak = weekStreak(facts, today)
        assertEquals(1, streak.weeks)
        assertEquals(3, streak.best)
    }

    @Test
    fun streakIsZeroWhenLastWeekWasEmpty() {
        val streak = weekStreak(listOf(fact("a", today.minusWeeks(2))), today)
        assertEquals(0, streak.weeks)
        assertFalse(streak.atRisk)
        assertEquals(1, streak.best)
    }

    @Test
    fun streakWithoutRides() {
        assertEquals(WeekStreak(weeks = 0, thisWeekDone = false, best = 0, daysLeft = 5), weekStreak(emptyList(), today))
    }

    @Test
    fun severalRidesInOneWeekCountOnce() {
        val monday = today.minusDays(2)
        val streak = weekStreak(listOf(fact("a", monday), fact("b", monday.plusDays(1))), today)
        assertEquals(1, streak.weeks)
    }

    @Test
    fun sundayLeavesOneDay() {
        assertEquals(1, weekStreak(emptyList(), LocalDate.of(2026, 11, 22)).daysLeft)
    }

    // ----------------------------------------------------------- Bestwerte

    @Test
    fun personalRecordsPickTheBestRidePerKind() {
        val facts = listOf(
            fact("lang", today.minusDays(10), km = 82.0, ascent = 600.0, timeS = 14400, speed = 20.5),
            fact("steil", today.minusDays(5), km = 45.0, ascent = 1100.0, timeS = 9000, speed = 18.0),
            fact("schnell", today.minusDays(2), km = 30.0, ascent = 150.0, timeS = 3600, speed = 30.0),
        )
        val records = personalRecords(facts).associateBy { it.kind }
        assertEquals("lang", records.getValue(RecordKind.DISTANCE).rideId)
        assertEquals("steil", records.getValue(RecordKind.ASCENT).rideId)
        assertEquals("lang", records.getValue(RecordKind.TIME).rideId)
        assertEquals("schnell", records.getValue(RecordKind.SPEED).rideId)
    }

    @Test
    fun shortRidesNeverHoldTheSpeedRecord() {
        val facts = listOf(
            fact("baecker", today.minusDays(3), km = 3.0, speed = 38.0),
            fact("runde", today.minusDays(1), km = 40.0, speed = 24.0),
        )
        assertEquals("runde", personalRecords(facts).first { it.kind == RecordKind.SPEED }.rideId)
    }

    @Test
    fun implausibleSpeedIsIgnored() {
        val facts = listOf(fact("gps", today, km = 50.0, speed = 140.0))
        assertNull(personalRecords(facts).firstOrNull { it.kind == RecordKind.SPEED })
    }

    @Test
    fun recordsSetByComparesOnlyWithEarlierRides() {
        val facts = listOf(
            fact("a", today.minusDays(20), km = 40.0, ascent = 300.0),
            fact("b", today.minusDays(10), km = 55.0, ascent = 250.0),
            fact("c", today.minusDays(1), km = 50.0, ascent = 500.0),
        )
        val b = recordsSetBy("b", facts)
        assertEquals(listOf(RecordKind.DISTANCE), b.map { it.kind })
        assertEquals(40.0, b.single().previous)
        // c ist nicht die weiteste, aber die mit den meisten Hoehenmetern.
        assertEquals(listOf(RecordKind.ASCENT), recordsSetBy("c", facts).map { it.kind })
    }

    @Test
    fun theVeryFirstRideSetsNoRecord() {
        assertTrue(recordsSetBy("a", listOf(fact("a", today, km = 60.0))).isEmpty())
    }

    @Test
    fun tinyRidesAreNotCelebrated() {
        val facts = listOf(fact("a", today.minusDays(1), km = 2.0, ascent = 5.0, timeS = 300, speed = null),
            fact("b", today, km = 3.0, ascent = 10.0, timeS = 400, speed = null))
        assertTrue(recordsSetBy("b", facts).isEmpty())
    }

    @Test
    fun unknownRideSetsNothing() {
        assertTrue(recordsSetBy("x", listOf(fact("a", today))).isEmpty())
        assertTrue(milestonesReachedBy("x", listOf(fact("a", today))).isEmpty())
    }

    // --------------------------------------------------------- Meilensteine

    @Test
    fun milestoneProgressBetweenTwoSteps() {
        val facts = listOf(fact("a", today.minusDays(3), km = 100.0), fact("b", today, km = 75.0))
        val progress = milestoneProgress(facts, MilestoneKind.TOTAL_KM)
        assertEquals(175.0, progress.current)
        assertEquals(100.0, progress.last)
        assertEquals(250.0, progress.next)
        assertEquals(0.5, progress.fraction)
        assertEquals(75.0, progress.remaining)
        assertEquals(2, progress.reached)
    }

    @Test
    fun milestoneProgressBeforeTheFirstStep() {
        val progress = milestoneProgress(listOf(fact("a", today, km = 10.0)), MilestoneKind.TOTAL_KM)
        assertNull(progress.last)
        assertEquals(50.0, progress.next)
        assertEquals(0.2, progress.fraction)
    }

    @Test
    fun laddersAreStrictlyAscending() {
        for (kind in MilestoneKind.entries) {
            val ladder = milestoneLadder(kind)
            assertEquals(ladder.sorted().distinct(), ladder, "Leiter $kind")
        }
    }

    @Test
    fun milestonesReachedByReportsOnlyTheHighestStepPerLadder() {
        val facts = listOf(
            fact("a", today.minusDays(2), km = 40.0, ascent = 100.0),
            // Springt ueber 50 und 100 km gesamt und ueber 25/40/60/80/100 km Einzelweite.
            fact("b", today, km = 105.0, ascent = 100.0),
        )
        val reached = milestonesReachedBy("b", facts)
        assertEquals(
            setOf(Milestone(MilestoneKind.TOTAL_KM, 100.0), Milestone(MilestoneKind.LONGEST_RIDE, 100.0)),
            reached.toSet(),
        )
    }

    @Test
    fun firstRideReachesTheFirstRideMilestone() {
        val reached = milestonesReachedBy("a", listOf(fact("a", today, km = 12.0, ascent = 50.0)))
        assertEquals(listOf(Milestone(MilestoneKind.RIDE_COUNT, 1.0)), reached)
    }

    @Test
    fun nearestMilestonePrefersTheClosestOpenStep() {
        // 240 von 250 km gesamt (93 %), 60-km-Weite erreicht → naechste 80 (0 %).
        val facts = listOf(
            fact("a", today.minusDays(9), km = 60.0, ascent = 0.0),
            fact("b", today.minusDays(6), km = 60.0, ascent = 0.0),
            fact("c", today.minusDays(3), km = 60.0, ascent = 0.0),
            fact("d", today, km = 60.0, ascent = 0.0),
        )
        val nearest = assertNotNull(nearestMilestone(facts))
        assertEquals(MilestoneKind.TOTAL_KM, nearest.kind)
        assertEquals(250.0, nearest.next)
    }

    // ----------------------------------------------------------- Jahresziel

    @Test
    fun yearGoalFromAnEarlierYearRunsFromJanuary() {
        // 1. Juli 2026: 181 von 365 Tagen des Jahres sind abgeschlossen.
        val day = LocalDate.of(2026, 7, 1)
        val facts = listOf(
            fact("alt", LocalDate.of(2025, 12, 30), km = 500.0),
            fact("a", LocalDate.of(2026, 3, 1), km = 1000.0),
            fact("b", LocalDate.of(2026, 6, 30), km = 1000.0),
        )
        val progress = yearGoalProgress(facts, YearGoal(3650, LocalDate.of(2025, 5, 1)), day)
        assertEquals(2000.0, progress.riddenKm)
        assertEquals(3650.0 * 181 / 365, progress.expectedKm, 0.01)
        assertEquals(2000.0 - 3650.0 * 181 / 365, progress.aheadKm, 0.01)
        assertEquals(1650.0, progress.remainingKm)
        // 183 Tage = 26,14 Wochen übrig.
        assertEquals(1650.0 / (183 / 7.0), progress.neededPerWeekKm, 0.01)
        // Letzte acht Wochen: 1000 km → 125 km/Woche für den Rest.
        assertEquals(2000.0 + 125.0 * 183 / 7.0, progress.projectedKm, 0.01)
        assertFalse(progress.reached)
    }

    @Test
    fun yearGoalSetInAutumnDoesNotStartBehind() {
        // Am 2. Dezember gesetzt, bis dahin 700 km; 30 Tage einschliesslich Silvester.
        val since = LocalDate.of(2026, 12, 2)
        val facts = listOf(fact("a", LocalDate.of(2026, 6, 1), km = 700.0))
        val goal = YearGoal(1000, since)

        val firstDay = yearGoalProgress(facts, goal, since)
        // Am Tag des Setzens ist das Soll der Stand von heute.
        assertEquals(700.0, firstDay.expectedKm, 0.01)
        assertEquals(0.0, firstDay.aheadKm, 0.01)

        // Zehn Tage spaeter mit 150 km mehr: 700 + 300 × 10/30 = 800 Soll, 850 Ist.
        val later = yearGoalProgress(facts + fact("b", since.plusDays(5), km = 150.0), goal, since.plusDays(10))
        assertEquals(800.0, later.expectedKm, 0.01)
        assertEquals(50.0, later.aheadKm, 0.01)
    }

    @Test
    fun yearGoalReached() {
        val progress = yearGoalProgress(listOf(fact("a", today, km = 1200.0)), YearGoal(1000, today), today)
        assertTrue(progress.reached)
        assertEquals(1.0, progress.fraction)
        assertEquals(0.0, progress.neededPerWeekKm)
    }

    @Test
    fun yearGoalOnNewYearsEveNeedsTheRest() {
        val facts = listOf(fact("a", LocalDate.of(2026, 6, 1), km = 900.0))
        val progress = yearGoalProgress(facts, YearGoal(1000, LocalDate.of(2026, 1, 1)), LocalDate.of(2026, 12, 31))
        assertEquals(100.0, progress.neededPerWeekKm)
        // Faellig ist das Ziel mit dem Ende des Tages: 364 von 365 Tagen sind um.
        assertEquals(1000.0 * 364 / 365, progress.expectedKm, 0.01)
    }

    @Test
    fun yearGoalSetOnNewYearsEveIsNotBehind() {
        val day = LocalDate.of(2026, 12, 31)
        val progress = yearGoalProgress(listOf(fact("a", LocalDate.of(2026, 6, 1), km = 900.0)), YearGoal(1000, day), day)
        assertEquals(900.0, progress.expectedKm, 0.01)
        assertEquals(0.0, progress.aheadKm, 0.01)
    }

    @Test
    fun suggestedYearGoalTerminatesAndLiesAboveWhatIsRiddenAtEveryScale() {
        // Frueher eine Schleife mit festem 50-km-Schritt, die ab 3000 km am
        // Runden auf 250 haengen blieb. Jahresende und lange Pause sind die
        // Faelle, in denen die Hochrechnung nicht ueber das Gefahrene kommt.
        val newYearsEve = LocalDate.of(2026, 12, 31)
        for (ridden in listOf(40.0, 990.0, 1000.0, 2960.0, 3000.0, 3050.0, 4010.0, 5100.0, 29900.0, 30000.0, 45000.0)) {
            val facts = listOf(fact("a", LocalDate.of(2026, 3, 1), km = ridden))
            for (day in listOf(today, newYearsEve)) {
                val suggestion = suggestYearGoalKm(facts, day)
                assertTrue(suggestion <= maxYearGoalKm, "ridden $ridden")
                if (ridden < maxYearGoalKm) assertTrue(suggestion > ridden, "ridden $ridden → $suggestion")
            }
        }
        assertEquals(4250, suggestYearGoalKm(listOf(fact("a", LocalDate.of(2026, 3, 1), km = 4010.0)), newYearsEve))
        assertEquals(3000, suggestYearGoalKm(listOf(fact("a", LocalDate.of(2026, 3, 1), km = 2960.0)), newYearsEve))
        assertEquals(3250, suggestYearGoalKm(listOf(fact("a", LocalDate.of(2026, 3, 1), km = 3000.0)), newYearsEve))
        assertEquals(1100, suggestYearGoalKm(listOf(fact("a", LocalDate.of(2026, 3, 1), km = 1000.0)), newYearsEve))
    }

    @Test
    fun suggestedYearGoalCoversTheRestOfTheYear() {
        // 8 Wochen à 60 km = 480 km gefahren; bis 31.12. bleiben 43 Tage:
        // 480 + 60 × 43/7 × 1,15 = 903,9 → 900.
        val facts = (0 until 8).map { fact("w$it", today.minusWeeks(it.toLong()), km = 60.0) }
        assertEquals(900, suggestYearGoalKm(facts, today))
        assertEquals(minYearGoalKm, suggestYearGoalKm(emptyList(), today))
    }

    @Test
    fun suggestedYearGoalIsAlwaysAboveWhatIsRidden() {
        // Alles im Fruehjahr gefahren, seit Wochen nichts: Vorschlag liegt trotzdem darueber.
        val facts = listOf(fact("a", LocalDate.of(2026, 4, 1), km = 1500.0))
        assertTrue(suggestYearGoalKm(facts, today) > 1500)
    }

    @Test
    fun yearGoalRounding() {
        assertEquals(450, roundYearGoalKm(431.0))
        assertEquals(1700, roundYearGoalKm(1740.0))
        assertEquals(3500, roundYearGoalKm(3588.0))
        assertEquals(minYearGoalKm, roundYearGoalKm(3.0))
        assertEquals(maxYearGoalKm, roundYearGoalKm(99999.0))
    }

    @Test
    fun yearGoalSurvivesEncoding() {
        val goal = YearGoal(2500, LocalDate.of(2026, 9, 14))
        assertEquals("2500|2026-09-14", goal.encode())
        assertEquals(goal, YearGoal.decode(goal.encode()))
        for (broken in listOf(null, "", "2500", "x|2026-09-14", "2500|gestern", "0|2026-09-14", "1|2|3")) {
            assertNull(YearGoal.decode(broken), "Eingabe $broken")
        }
    }

    // ----------------------------------------------------------- Bestweite

    @Test
    fun stretchChallengeAddsTenPercentRoundedUpToFive() {
        val facts = listOf(fact("a", today.minusDays(60), km = 120.0), fact("b", today.minusDays(7), km = 62.0))
        val challenge = assertNotNull(stretchChallenge(facts, today))
        assertEquals(70, challenge.targetKm)
        assertEquals(62.0, challenge.baseKm)
        // 120 km von vor zwei Monaten bleiben die weiteste Tour.
        assertFalse(challenge.beatsAllTime)
    }

    @Test
    fun stretchChallengeBeatsAllTimeWhenRecentIsTheBest() {
        val challenge = assertNotNull(stretchChallenge(listOf(fact("a", today.minusDays(3), km = 80.0)), today))
        assertEquals(90, challenge.targetKm)
        assertTrue(challenge.beatsAllTime)
    }

    @Test
    fun stretchChallengeAlwaysMovesForward() {
        // 10 km × 1,1 = 11 → mindestens +3 → 13 → aufgerundet 15.
        assertEquals(15, stretchChallenge(listOf(fact("a", today, km = 10.0)), today)?.targetKm)
        // Genau auf einer Fuenferstufe: 50 → 55.
        assertEquals(55, stretchChallenge(listOf(fact("a", today, km = 50.0)), today)?.targetKm)
    }

    @Test
    fun noStretchChallengeWithoutRecentRides() {
        assertNull(stretchChallenge(listOf(fact("a", today.minusDays(50), km = 80.0)), today))
        assertNull(stretchChallenge(listOf(fact("a", today, km = 6.0)), today))
        assertNull(stretchChallenge(emptyList(), today))
    }

    @Test
    fun stretchChallengeIsCappedAtTheRoutingLimit() {
        assertNull(stretchChallenge(listOf(fact("a", today, km = 230.0)), today))
        assertEquals(200, stretchChallenge(listOf(fact("a", today, km = 190.0)), today)?.targetKm)
    }

    // ------------------------------------------------------------- Verlauf

    @Test
    fun weeklyBucketsCoverEmptyWeeksAndEndWithTheCurrentOne() {
        val facts = listOf(
            fact("a", today.minusWeeks(2), km = 40.0),
            fact("b", today.minusDays(1), km = 25.0),
            fact("c", today, km = 10.0),
            // Zukunft (falsche Uhr) zaehlt nicht in die laufende Woche.
            fact("z", today.plusDays(2), km = 99.0),
        )
        val buckets = weeklyBuckets(facts, today, weeks = 4)
        assertEquals(4, buckets.size)
        assertEquals(listOf(0.0, 40.0, 0.0, 35.0), buckets.map { it.totals.km })
        assertEquals(LocalDate.of(2026, 11, 16), buckets.last().monday)
        assertEquals(2, buckets.last().totals.rides)
    }

    @Test
    fun recentTrendComparesFourWeeksWithTheFourBefore() {
        val facts = listOf(
            fact("alt1", today.minusDays(40), km = 50.0),
            fact("alt2", today.minusDays(28), km = 50.0),
            fact("neu1", today.minusDays(27), km = 70.0),
            fact("neu2", today, km = 80.0),
        )
        val trend = recentTrend(facts, today)
        assertEquals(150.0, trend.recent.km)
        assertEquals(100.0, trend.before.km)
        assertEquals(50, trend.kmChangePct)
    }

    @Test
    fun trendWithoutComparisonHasNoPercentage() {
        assertNull(recentTrend(listOf(fact("a", today)), today).kmChangePct)
    }

    @Test
    fun lifetimeTotalsSumEverything() {
        val totals = lifetimeTotals(listOf(fact("a", today, km = 10.0, ascent = 100.0, timeS = 1000), fact("b", today, km = 5.0, ascent = 50.0, timeS = null)))
        assertEquals(PeriodTotals(km = 15.0, ascentM = 150.0, timeS = 1000, rides = 2), totals)
        assertEquals(PeriodTotals.empty, lifetimeTotals(emptyList()))
    }

    @Test
    fun daysSinceLastRide() {
        assertEquals(3, daysSinceLastRide(listOf(fact("a", today.minusDays(9)), fact("b", today.minusDays(3))), today))
        assertEquals(0, daysSinceLastRide(listOf(fact("a", today)), today))
        assertNull(daysSinceLastRide(emptyList(), today))
    }
}
