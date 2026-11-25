package de.trailscape.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests der Erfolge (`Achievements.kt`): Punkte, Stufen, Serie mit Pausen, Abzeichen. */
class AchievementsTest {

    /** Montag, 2. November 2026. */
    private val mon = LocalDate.of(2026, 11, 2)

    private fun fact(
        id: String,
        date: LocalDate,
        km: Double = 30.0,
        ascent: Double = 100.0,
        timeS: Int? = 3600,
        speed: Double? = 25.0,
    ) = RideFact(id, date, km, ascent, timeS, speed)

    private fun input(
        facts: List<RideFact>,
        today: LocalDate,
        indoor: Set<String> = emptySet(),
        plan: PlanAchievementFacts? = null,
        yearGoal: YearGoal? = null,
        progress: AchievementProgressData = AchievementProgressData(),
        sources: AchievementSources = AchievementSources(),
    ) = AchievementInput(facts, today, indoor, plan, yearGoal, progress, sources)

    private fun states(input: AchievementInput) = achievementOverview(input).states.associateBy { it.id }

    // -----------------------------------------------------------------------
    // Stufen
    // -----------------------------------------------------------------------

    @Test
    fun levelCurveFollowsTheConceptRoundedToTen() {
        assertEquals(0, levelThreshold(1))
        assertEquals(500, levelThreshold(2))
        assertEquals(1520, levelThreshold(3))
        assertEquals(4590, levelThreshold(5))
        assertEquals(16820, levelThreshold(10))
        assertEquals(23180, levelThreshold(12))
        assertEquals(55590, levelThreshold(20))
        assertEquals(109350, levelThreshold(30))
        assertEquals(253100, levelThreshold(50))
        // Geklemmt statt Ausnahme.
        assertEquals(0, levelThreshold(0))
        assertEquals(253100, levelThreshold(99))
        // Streng steigend.
        assertTrue((2..maxAchievementLevel).all { levelThreshold(it) > levelThreshold(it - 1) })
    }

    @Test
    fun levelForPointsUsesTheThresholds() {
        assertEquals(1, levelForPoints(0))
        assertEquals(1, levelForPoints(499))
        assertEquals(2, levelForPoints(500))
        assertEquals(4, levelForPoints(4589))
        assertEquals(5, levelForPoints(4590))
        assertEquals(maxAchievementLevel, levelForPoints(Int.MAX_VALUE))
        assertEquals(1, levelForPoints(-10))
    }

    @Test
    fun keptLevelIsNeverLowered() {
        val p = levelProgress(points = 100, keptLevel = 3)
        assertEquals(3, p.level)
        assertEquals(1520, p.levelPoints)
        assertEquals(levelThreshold(4), p.nextLevelPoints)
        assertEquals(0.0, p.fraction)
        assertEquals(levelThreshold(4) - 100, p.remaining)
        // Mehr Punkte als die gespeicherte Stufe: die Punkte gewinnen.
        assertEquals(5, levelProgress(5000, keptLevel = 2).level)
    }

    @Test
    fun topLevelHasNoNextStep() {
        val p = levelProgress(1_000_000)
        assertEquals(maxAchievementLevel, p.level)
        assertNull(p.nextLevelPoints)
        assertEquals(1.0, p.fraction)
        assertEquals(0, p.remaining)
    }

    @Test
    fun levelFractionIsTheWayBetweenThresholds() {
        val p = levelProgress(1010) // zwischen 500 (Stufe 2) und 1520 (Stufe 3)
        assertEquals(2, p.level)
        assertEquals(0.5, p.fraction, 1e-9)
        assertEquals(510, p.remaining)
    }

    // -----------------------------------------------------------------------
    // Punkte
    // -----------------------------------------------------------------------

    @Test
    fun ridePointsAreMinutesWithADailyCap() {
        val sameDay = listOf(fact("a", mon, timeS = 2 * 3600), fact("b", mon, timeS = 2 * 3600))
        assertEquals(180, achievementPoints(input(sameDay, mon.plusDays(6))).ride)
        val twoDays = listOf(fact("a", mon, timeS = 2 * 3600), fact("b", mon.plusDays(1), timeS = 2 * 3600))
        assertEquals(240, achievementPoints(input(twoDays, mon.plusDays(6))).ride)
        // Angefangene Minuten zaehlen nicht, fehlende Zeit gibt nichts.
        val odd = listOf(fact("a", mon, timeS = 89), fact("b", mon.plusDays(1), timeS = null))
        assertEquals(1, achievementPoints(input(odd, mon.plusDays(6))).ride)
    }

    @Test
    fun pointsIgnoreDistanceAscentAndSpeed() {
        val slow = listOf(fact("a", mon, km = 10.0, ascent = 0.0, speed = 10.0))
        val fast = listOf(fact("a", mon, km = 40.0, ascent = 900.0, speed = 40.0))
        assertEquals(
            achievementPoints(input(slow, mon)).ride,
            achievementPoints(input(fast, mon)).ride,
        )
    }

    @Test
    fun ridesBeforePointsSinceGiveNoPointsButCountForBadges() {
        val facts = listOf(fact("alt", mon.minusDays(30), km = 120.0), fact("neu", mon))
        val since = AchievementProgressData(pointsSince = mon)
        val points = achievementPoints(input(facts, mon, progress = since))
        assertEquals(60, points.ride)
        val s = states(input(facts, mon, progress = since))
        assertTrue(s.getValue(AchievementId.RIDE_100_KM).reached)
        assertEquals("alt", s.getValue(AchievementId.RIDE_100_KM).rideId)
    }

    @Test
    fun ridesAfterTodayAreIgnored() {
        val facts = listOf(fact("a", mon), fact("zukunft", mon.plusDays(3), km = 200.0))
        assertEquals(60, achievementPoints(input(facts, mon)).ride)
        assertFalse(states(input(facts, mon)).getValue(AchievementId.RIDE_100_KM).reached)
    }

    private fun session(day: Long, status: PlanSessionStatus, rideId: String? = null) =
        PlanSessionFact(mon.plusDays(day), status, rideId)

    @Test
    fun planSessionsGiveFortyAndTwentyPoints() {
        val plan = PlanAchievementFacts(
            listOf(
                PlanWeekFact(
                    mon,
                    WeekKind.AUFBAU,
                    targetKm = 500,
                    sessions = listOf(
                        session(1, PlanSessionStatus.ERLEDIGT, "a"),
                        session(3, PlanSessionStatus.TEILWEISE, "b"),
                        session(5, PlanSessionStatus.VERPASST),
                        session(6, PlanSessionStatus.OFFEN),
                    ),
                ),
            ),
        )
        val facts = listOf(fact("a", mon.plusDays(1)), fact("b", mon.plusDays(3)))
        assertEquals(60, achievementPoints(input(facts, mon.plusDays(6), plan = plan)).plan)
    }

    @Test
    fun weekGoalGivesSixtyPointsWithPlanTarget() {
        val plan = PlanAchievementFacts(listOf(PlanWeekFact(mon, WeekKind.AUFBAU, 50, emptyList())))
        val reached = listOf(fact("a", mon, km = 30.0), fact("b", mon.plusDays(2), km = 25.0))
        assertEquals(60, achievementPoints(input(reached, mon.plusDays(6), plan = plan)).week)
        val missed = listOf(fact("a", mon, km = 30.0))
        assertEquals(0, achievementPoints(input(missed, mon.plusDays(6), plan = plan)).week)
    }

    @Test
    fun weekGoalWithoutPlanUsesTheGrowingGoal() {
        // Erste Woche: Einstiegsziel 30 km.
        val facts = listOf(fact("a", mon, km = 31.0))
        assertEquals(60, achievementPoints(input(facts, mon.plusDays(6))).week)
    }

    @Test
    fun challengePointsPerReachedChallenge() {
        // Ohne Vorgeschichte gilt der Startsatz: 100 km, 4 Tage, 30 km am Stueck.
        val facts = (0L..3L).map { fact("r$it", mon.plusDays(it), km = 30.0) }
        val points = achievementPoints(input(facts, mon.plusDays(6)))
        assertEquals(3 * challengeReachedPoints, points.challenge)
    }

    @Test
    fun challengeBonusGivesFiftyMore() {
        // 4 × 35 km: 140 km ≥ Bonus 125, Tour 35 ≥ Bonus 35.
        val facts = (0L..3L).map { fact("r$it", mon.plusDays(it), km = 35.0) }
        val points = achievementPoints(input(facts, mon.plusDays(6)))
        assertEquals(3 * challengeReachedPoints + 2 * challengeBonusPoints, points.challenge)
    }

    @Test
    fun noPointsWithoutRides() {
        assertEquals(AchievementPoints(), achievementPoints(input(emptyList(), mon)))
        assertEquals(1, achievementOverview(input(emptyList(), mon)).level.level)
    }

    // -----------------------------------------------------------------------
    // Pausenwochen und Serie
    // -----------------------------------------------------------------------

    private fun weekly(vararg weeks: Long) = weeks.map { fact("w$it", mon.plusWeeks(it)) }

    @Test
    fun pauseWeekHoldsTheStreakWithoutExtendingIt() {
        val facts = weekly(0, 1, 3)
        val today = mon.plusWeeks(3).plusDays(2)
        assertEquals(1, weekStreakWithPauses(facts, emptySet(), today).weeks)
        val withPause = weekStreakWithPauses(facts, setOf(mon.plusWeeks(2).plusDays(4)), today)
        assertEquals(3, withPause.weeks)
        assertEquals(3, withPause.best)
        // Ohne Pausen dasselbe wie die bekannte Wochenserie.
        assertEquals(weekStreak(facts, today), weekStreakWithPauses(facts, emptySet(), today))
    }

    @Test
    fun atMostTwoPausesInTwelveWeeks() {
        val pauses = setOf(mon.plusWeeks(1), mon.plusWeeks(3), mon.plusWeeks(5), mon.plusWeeks(14))
        val accepted = acceptedPauseWeeks(weekly(0, 2, 4), pauses)
        assertEquals(setOf(mon.plusWeeks(1), mon.plusWeeks(3), mon.plusWeeks(14)), accepted)
    }

    @Test
    fun pauseInARideWeekIsNotConsumed() {
        val pauses = setOf(mon, mon.plusWeeks(1), mon.plusWeeks(2))
        // Woche 0 hat eine Tour: zaehlt ohnehin, verbraucht keine Pause.
        assertEquals(setOf(mon.plusWeeks(1), mon.plusWeeks(2)), acceptedPauseWeeks(weekly(0), pauses))
    }

    @Test
    fun canMarkPauseWeekChecksRideAndQuota() {
        val facts = weekly(0)
        assertFalse(canMarkPauseWeek(facts, emptySet(), mon.plusDays(3)))
        assertTrue(canMarkPauseWeek(facts, emptySet(), mon.plusWeeks(1)))
        val two = setOf(mon.plusWeeks(1), mon.plusWeeks(2))
        assertFalse(canMarkPauseWeek(facts, two, mon.plusWeeks(3)))
        assertFalse(canMarkPauseWeek(facts, two, mon.plusWeeks(1)))
        // Rueckwirkend davor wuerde eine spaetere, geltende Pause verdraengen.
        assertFalse(canMarkPauseWeek(weekly(0, 5), setOf(mon.plusWeeks(6), mon.plusWeeks(7)), mon.plusWeeks(1)))
        assertTrue(canMarkPauseWeek(facts, two, mon.plusWeeks(13)))
    }

    @Test
    fun pausedCurrentWeekIsNotAtRisk() {
        val facts = weekly(0, 1)
        val today = mon.plusWeeks(2).plusDays(1)
        assertTrue(weekStreak(facts, today).atRisk)
        val paused = weekStreakWithPauses(facts, setOf(mon.plusWeeks(2)), today)
        assertFalse(paused.atRisk)
        assertEquals(2, paused.weeks)
    }

    @Test
    fun streakBadgesUnlockAtTheFirstRideOfTheFourthWeek() {
        val facts = weekly(0, 1, 2, 3, 4)
        val s = states(input(facts, mon.plusWeeks(4)))
        val streak4 = s.getValue(AchievementId.STREAK_4)
        assertTrue(streak4.reached)
        assertEquals("w3", streak4.rideId)
        assertEquals(mon.plusWeeks(3), streak4.date)
        val streak12 = s.getValue(AchievementId.STREAK_12)
        assertFalse(streak12.reached)
        assertEquals(5.0, streak12.current)
    }

    @Test
    fun streakBadgeCountsPauses() {
        val facts = weekly(0, 1, 3, 4)
        val today = mon.plusWeeks(4)
        assertFalse(states(input(facts, today)).getValue(AchievementId.STREAK_4).reached)
        val paused = input(facts, today, progress = AchievementProgressData(pauseWeeks = setOf(mon.plusWeeks(2))))
        assertEquals("w4", states(paused).getValue(AchievementId.STREAK_4).rideId)
        assertEquals(4, achievementOverview(paused).streak.weeks)
    }

    // -----------------------------------------------------------------------
    // Abzeichen aus Touren
    // -----------------------------------------------------------------------

    @Test
    fun singleRideBadges() {
        val facts = listOf(
            fact("a", mon, km = 20.0),
            fact("b", mon.plusDays(1), km = 55.0, ascent = 1200.0),
            fact("c", mon.plusDays(2), km = 60.0, timeS = 4 * 3600),
        )
        val s = states(input(facts, mon.plusDays(2)))
        assertEquals("a", s.getValue(AchievementId.FIRST_RIDE).rideId)
        assertEquals(mon, s.getValue(AchievementId.FIRST_RIDE).date)
        assertEquals("b", s.getValue(AchievementId.RIDE_50_KM).rideId)
        assertEquals("b", s.getValue(AchievementId.RIDE_1000_M).rideId)
        assertEquals("c", s.getValue(AchievementId.RIDE_4_H).rideId)
        val century = s.getValue(AchievementId.RIDE_100_KM)
        assertEquals(AchievementStatus.OPEN, century.status)
        assertEquals(60.0, century.current)
        assertEquals(0.6, century.fraction!!, 1e-9)
    }

    @Test
    fun countAndTotalBadgesNameTheCrossingRide() {
        val facts = (0L until 12L).map { fact("r$it", mon.plusDays(it), km = 90.0, ascent = 800.0) }
        val s = states(input(facts, mon.plusDays(20)))
        assertEquals("r9", s.getValue(AchievementId.RIDES_10).rideId)
        assertEquals(12.0, s.getValue(AchievementId.RIDES_100).current)
        // 12 × 90 = 1080 km: die zwoelfte Tour ueberschreitet 1000 km.
        assertEquals("r11", s.getValue(AchievementId.TOTAL_1000_KM).rideId)
        // 12 × 800 = 9600 Hm: die zwoelfte Tour ueberschreitet den Everest.
        assertEquals("r11", s.getValue(AchievementId.TOTAL_EVEREST).rideId)
        assertFalse(s.getValue(AchievementId.TOTAL_EARTH).reached)
    }

    @Test
    fun indoorBadgesUseTheIndoorRides() {
        val facts = (0L until 6L).map { fact("i$it", mon.plusDays(it), timeS = 2 * 3600) } +
            fact("draussen", mon.plusDays(7), timeS = 5 * 3600)
        val indoor = facts.filter { it.id.startsWith("i") }.mapTo(HashSet()) { it.id }
        val s = states(input(facts, mon.plusDays(8), indoor = indoor))
        assertEquals("i0", s.getValue(AchievementId.INDOOR_FIRST).rideId)
        assertEquals("i4", s.getValue(AchievementId.INDOOR_10_H).rideId)
        val none = states(input(facts, mon.plusDays(8)))
        assertFalse(none.getValue(AchievementId.INDOOR_FIRST).reached)
        assertEquals(0.0, none.getValue(AchievementId.INDOOR_10_H).current)
    }

    @Test
    fun monthCompleteAndBonusNameTheDecidingRide() {
        val facts = (0L..3L).map { fact("r$it", mon.plusDays(it), km = 35.0) }
        val s = states(input(facts, mon.plusDays(6)))
        assertEquals("r3", s.getValue(AchievementId.MONTH_COMPLETE).rideId)
        // Bonus der weitesten Tour (35 km) gleich mit der ersten.
        assertEquals("r0", s.getValue(AchievementId.CHALLENGE_BONUS).rideId)
        assertEquals(3.0, s.getValue(AchievementId.MONTH_COMPLETE).target)
    }

    @Test
    fun yearGoalBadge() {
        val goal = YearGoal(100, LocalDate.of(2026, 1, 1))
        val facts = listOf(fact("a", mon, km = 60.0), fact("b", mon.plusDays(1), km = 50.0))
        val s = states(input(facts, mon.plusDays(2), yearGoal = goal)).getValue(AchievementId.YEAR_GOAL)
        assertTrue(s.reached)
        assertEquals("b", s.rideId)
        assertEquals(100.0, s.target)
        val open = states(input(facts.take(1), mon.plusDays(2), yearGoal = goal)).getValue(AchievementId.YEAR_GOAL)
        assertEquals(0.6, open.fraction!!, 1e-9)
        val noGoal = states(input(facts, mon.plusDays(2))).getValue(AchievementId.YEAR_GOAL)
        assertEquals(AchievementStatus.OPEN, noGoal.status)
        assertNull(noGoal.fraction)
    }

    @Test
    fun yearGoalOfAnEarlierYearCounts() {
        val goal = YearGoal(100, LocalDate.of(2025, 3, 1))
        val facts = listOf(fact("alt", LocalDate.of(2025, 6, 1), km = 120.0), fact("neu", mon, km = 10.0))
        val s = states(input(facts, mon, yearGoal = goal)).getValue(AchievementId.YEAR_GOAL)
        assertEquals("alt", s.rideId)
        assertEquals(10.0, s.current)
    }

    // -----------------------------------------------------------------------
    // Plan
    // -----------------------------------------------------------------------

    @Test
    fun planBadges() {
        val week0 = PlanWeekFact(
            mon,
            WeekKind.AUFBAU,
            90,
            listOf(session(1, PlanSessionStatus.ERLEDIGT, "a"), session(3, PlanSessionStatus.TEILWEISE, "b")),
        )
        val week1 = PlanWeekFact(
            mon.plusWeeks(1),
            WeekKind.ERHOLUNG,
            25,
            listOf(
                PlanSessionFact(mon.plusWeeks(1).plusDays(2), PlanSessionStatus.ERLEDIGT, "c"),
                PlanSessionFact(mon.plusWeeks(1).plusDays(5), PlanSessionStatus.ERLEDIGT, "d"),
            ),
        )
        val facts = listOf(
            fact("a", mon.plusDays(1)),
            fact("b", mon.plusDays(3)),
            fact("c", mon.plusWeeks(1).plusDays(2), km = 12.0),
            fact("d", mon.plusWeeks(1).plusDays(5), km = 14.0),
        )
        val plan = PlanAchievementFacts(listOf(week0, week1))
        val today = mon.plusWeeks(2).plusDays(2)
        val s = states(input(facts, today, plan = plan))
        assertEquals("a", s.getValue(AchievementId.PLAN_FIRST).rideId)
        assertEquals("b", s.getValue(AchievementId.PLAN_FULL_WEEK).rideId)
        // 26 km ≤ 25 × 1,1: eingehalten, am Sonntag der Woche.
        val recovery = s.getValue(AchievementId.RECOVERY_WEEK)
        assertTrue(recovery.reached)
        assertEquals(mon.plusWeeks(1).plusDays(6), recovery.date)
        // 3 von 4 Einheiten erledigt (75 %) — knapp unter 80 %.
        val finished = s.getValue(AchievementId.PLAN_FINISHED)
        assertFalse(finished.reached)
        assertEquals(75.0, finished.current!!, 1e-9)
    }

    @Test
    fun recoveryWeekOverTheCapDoesNotCount() {
        val week = PlanWeekFact(mon, WeekKind.ERHOLUNG, 25, listOf(session(2, PlanSessionStatus.ERLEDIGT, "a")))
        val facts = listOf(fact("a", mon.plusDays(2), km = 28.0))
        val s = states(input(facts, mon.plusWeeks(1).plusDays(1), plan = PlanAchievementFacts(listOf(week))))
        assertFalse(s.getValue(AchievementId.RECOVERY_WEEK).reached)
        // Ohne Tour ist es keine eingehaltene Erholung, sondern eine Pause.
        val empty = states(input(listOf(fact("x", mon.minusDays(3))), mon.plusWeeks(1).plusDays(1), plan = PlanAchievementFacts(listOf(week))))
        assertFalse(empty.getValue(AchievementId.RECOVERY_WEEK).reached)
        // Waehrend der Woche noch nicht.
        val running = states(input(listOf(fact("a", mon.plusDays(2), km = 10.0)), mon.plusDays(3), plan = PlanAchievementFacts(listOf(week))))
        assertFalse(running.getValue(AchievementId.RECOVERY_WEEK).reached)
    }

    @Test
    fun recoveryWithoutPlanUsesTheLighterWeekOfTheGrowingGoal() {
        // Drei steigende Wochen (20, 30, 40 km), dann Entlastung: Ziel 25 km.
        val facts = listOf(
            fact("w0", mon, km = 20.0),
            fact("w1", mon.plusWeeks(1), km = 30.0),
            fact("w2", mon.plusWeeks(2), km = 40.0),
            fact("w3", mon.plusWeeks(3), km = 20.0),
        )
        assertEquals(WeeklyGoalKind.ENTLASTUNG, suggestWeeklyGoal(facts, mon.plusWeeks(3))?.kind)
        val s = states(input(facts, mon.plusWeeks(4))).getValue(AchievementId.RECOVERY_WEEK)
        assertTrue(s.reached)
        assertEquals(mon.plusWeeks(3).plusDays(6), s.date)
    }

    @Test
    fun planFinishedNeedsAllWeeksOverAndEightyPercent() {
        val week = PlanWeekFact(
            mon,
            WeekKind.ZIELWOCHE,
            60,
            listOf(
                session(1, PlanSessionStatus.ERLEDIGT, "a"),
                session(3, PlanSessionStatus.ERLEDIGT, "b"),
                session(4, PlanSessionStatus.ERLEDIGT, "c"),
                session(5, PlanSessionStatus.ERLEDIGT, "d"),
                session(6, PlanSessionStatus.VERPASST),
            ),
        )
        val facts = listOf("a" to 1L, "b" to 3L, "c" to 4L, "d" to 5L).map { fact(it.first, mon.plusDays(it.second)) }
        val plan = PlanAchievementFacts(listOf(week))
        val done = states(input(facts, mon.plusDays(8), plan = plan)).getValue(AchievementId.PLAN_FINISHED)
        assertTrue(done.reached)
        assertEquals(mon.plusDays(6), done.date)
        // Am Sonntag selbst laeuft die Toleranz noch.
        assertFalse(states(input(facts, mon.plusDays(6), plan = plan)).getValue(AchievementId.PLAN_FINISHED).reached)
    }

    @Test
    fun planAchievementFactsUsesTheSessionStatusOfThePlanTab() {
        val start = dartEpochMs(mon.atStartOfDay())
        val week = TrainingWeek(
            index = 0,
            start = start,
            end = dartEpochMs(mon.plusDays(7).atStartOfDay()),
            kind = WeekKind.AUFBAU,
            targetKm = 60,
            sessions = listOf(
                TrainingSession(day = "Di", title = "GA1", description = "", targetKm = 30),
                TrainingSession(day = "Sa", title = "Lang", description = "", targetKm = 50),
            ),
        )
        val plan = TrainingPlan(
            createdAt = start,
            goal = Goal(name = "Ziel", distanceKm = 100.0, date = dartEpochMs(mon.plusDays(30).atStartOfDay())),
            level = FitnessLevel.FORTGESCHRITTEN,
            weeks = listOf(week),
        )
        val rides = listOf(
            RideSummary("t", "Di", dartEpochMs(mon.plusDays(1).atTime(9, 0)), 0, RideStats(31.0, 0.0, 0.0)),
            RideSummary("s", "Sa", dartEpochMs(mon.plusDays(5).atTime(9, 0)), 0, RideStats(20.0, 0.0, 0.0)),
        )
        val facts = planAchievementFacts(plan, rides, dartEpochMs(LocalDateTime.of(2026, 11, 10, 12, 0)))
        val sessions = facts.weeks.single().sessions
        assertEquals(mon, facts.weeks.single().monday)
        assertEquals(listOf(PlanSessionStatus.ERLEDIGT, PlanSessionStatus.TEILWEISE), sessions.map { it.status })
        assertEquals(listOf(mon.plusDays(1), mon.plusDays(5)), sessions.map { it.date })
        assertEquals(listOf("t", "s"), sessions.map { it.rideId })
    }

    // -----------------------------------------------------------------------
    // Quellen, „kommt bald", Festgehaltenes
    // -----------------------------------------------------------------------

    @Test
    fun missingSourcesAreComingSoonOrUnknown() {
        val s = states(input(listOf(fact("a", mon)), mon))
        for (id in comingSoonWhenMissing) assertEquals(AchievementStatus.COMING_SOON, s.getValue(id).status, id.key)
        for (id in listOf(AchievementId.ROUND_5, AchievementId.TILES_100, AchievementId.TILES_SQUARE_5, AchievementId.NEW_WAYS_100_KM)) {
            assertEquals(AchievementStatus.UNKNOWN, s.getValue(id).status, id.key)
        }
        val overview = achievementOverview(input(listOf(fact("a", mon)), mon))
        assertEquals(AchievementId.entries.size - comingSoonWhenMissing.size, overview.available.size)
    }

    @Test
    fun sourcesFillTheirBadges() {
        val unlock = AchievementUnlock("a", mon)
        val sources = AchievementSources(
            ghostWins = listOf(AchievementUnlock("b", mon.plusDays(1)), unlock),
            knownRouteCompletions = emptyList(),
            climbs = ClimbAchievementFacts(firstRepeatedClimb = unlock, firstImprovedBest = null),
            rounds = RoundAchievementFacts(maxRepeats = 5, fifthRide = unlock),
            exploration = ExplorationAchievementFacts(tileCount = 120, largestSquare = 4),
            distinctWaysKm = distinctRiddenWaysKm(6500),
        )
        val s = states(input(listOf(fact("a", mon)), mon.plusDays(1), sources = sources))
        assertEquals("a", s.getValue(AchievementId.GHOST_BEATEN).rideId)
        assertEquals(AchievementStatus.OPEN, s.getValue(AchievementId.KNOWN_ROUTE).status)
        assertTrue(s.getValue(AchievementId.CLIMB_REPEAT).reached)
        assertEquals(AchievementStatus.OPEN, s.getValue(AchievementId.CLIMB_FASTER).status)
        assertEquals("a", s.getValue(AchievementId.ROUND_5).rideId)
        assertTrue(s.getValue(AchievementId.TILES_100).reached)
        assertEquals(4.0, s.getValue(AchievementId.TILES_SQUARE_5).current)
        assertTrue(s.getValue(AchievementId.NEW_WAYS_100_KM).reached)
        assertEquals(100.0, distinctRiddenWaysKm(6500), 1e-9)
        assertEquals(0.0, distinctRiddenWaysKm(-3))
    }

    @Test
    fun earnedBadgesStayReached() {
        val progress = AchievementProgressData(earned = mapOf("plan_first" to mon.minusDays(40), "unbekannt" to mon))
        val s = states(input(listOf(fact("a", mon)), mon, progress = progress)).getValue(AchievementId.PLAN_FIRST)
        assertTrue(s.reached)
        assertEquals(mon.minusDays(40), s.date)
        assertNull(s.rideId)
    }

    @Test
    fun derivedDateWinsOverEarned() {
        val progress = AchievementProgressData(earned = mapOf("first_ride" to mon.minusDays(1)))
        val s = states(input(listOf(fact("a", mon)), mon, progress = progress)).getValue(AchievementId.FIRST_RIDE)
        assertEquals("a", s.rideId)
        assertEquals(mon, s.date)
    }

    @Test
    fun unlockedByRideAndUnseen() {
        val facts = listOf(fact("a", mon, km = 20.0), fact("b", mon.plusDays(1), km = 105.0))
        val overview = achievementOverview(input(facts, mon.plusDays(1)))
        // Mit 105 km auch die Bonusstufe der weitesten Tour (Startsatz 30 km, Bonus 35 km).
        assertEquals(
            listOf(AchievementId.RIDE_50_KM, AchievementId.RIDE_100_KM, AchievementId.CHALLENGE_BONUS),
            achievementsUnlockedBy("b", overview.states),
        )
        assertEquals(listOf(AchievementId.FIRST_RIDE), achievementsUnlockedBy("a", overview.states))
        val seen = AchievementProgressData(seen = setOf("first_ride", "ride_50km"))
        assertEquals(
            listOf(AchievementId.RIDE_100_KM, AchievementId.CHALLENGE_BONUS),
            unseenAchievements(overview.states, seen),
        )
        assertEquals(overview.states.count { it.reached }, overview.reachedCount)
    }

    @Test
    fun keysAreUniqueAndStable() {
        assertEquals(30, AchievementId.entries.size)
        assertEquals(AchievementId.entries.size, AchievementId.entries.map { it.key }.toSet().size)
        assertEquals(AchievementId.CHALLENGE_BONUS, AchievementId.fromKey("challenge_bonus"))
        assertNull(AchievementId.fromKey("gibt_es_nicht"))
        // Festgeschrieben: Diese Schluessel stehen in gespeicherten Staenden und Backups.
        assertEquals("first_ride", AchievementId.FIRST_RIDE.key)
        assertEquals("new_ways_100km", AchievementId.NEW_WAYS_100_KM.key)
    }

    // -----------------------------------------------------------------------
    // Highlights
    // -----------------------------------------------------------------------

    private val distance = RideHighlight.Record(RecordGain(RecordKind.DISTANCE, 80.0, 60.0))
    private val ascent = RideHighlight.Record(RecordGain(RecordKind.ASCENT, 900.0, 600.0))
    private val streak = RideHighlight.StreakExtended(3)

    @Test
    fun badgeComesAfterANewLongestRide() {
        val ids = listOf(AchievementId.RIDE_50_KM)
        val result = withAchievementHighlight(listOf(distance, ascent, streak), ids, max = 3)
        assertEquals(listOf(distance, RideHighlight.AchievementsUnlocked(ids), ascent), result)
    }

    @Test
    fun badgeComesFirstOtherwise() {
        val ids = listOf(AchievementId.STREAK_4, AchievementId.RIDES_10)
        assertEquals(
            listOf(RideHighlight.AchievementsUnlocked(ids), ascent),
            withAchievementHighlight(listOf(ascent, streak), ids, max = 2),
        )
        assertEquals(listOf(RideHighlight.AchievementsUnlocked(ids)), withAchievementHighlight(emptyList(), ids, max = 3))
    }

    @Test
    fun noBadgeLeavesTheHighlightsAlone() {
        assertEquals(listOf(distance, ascent), withAchievementHighlight(listOf(distance, ascent, streak), emptyList(), 2))
        assertEquals(emptyList<RideHighlight>(), withAchievementHighlight(listOf(distance), listOf(AchievementId.FIRST_RIDE), 0))
    }
}
