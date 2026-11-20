package de.trailscape.core

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests der Monats-Challenges (`Challenges.kt`). */
class ChallengesTest {

    private val nov = YearMonth.of(2026, 11)

    private fun fact(date: LocalDate, km: Double = 30.0, ascent: Double = 200.0, id: String = "r$date-$km") =
        RideFact(id, date, km, ascent, timeS = 5400, avgSpeedKmh = 20.0)

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun target(list: List<Challenge>, kind: ChallengeKind) = list.firstOrNull { it.kind == kind }?.target

    /** Eine Tour je Monat im Referenzzeitraum von November 2026. */
    private fun reference(km: Double, ascent: Double = 0.0) = listOf(
        fact(d(2026, 10, 5), km, ascent, "o"),
        fact(d(2026, 9, 5), km, ascent, "s"),
        fact(d(2026, 8, 5), km, ascent, "a"),
    )

    @Test
    fun starterSetWithoutHistory() {
        val c = monthlyChallenges(emptyList(), nov)
        assertEquals(listOf(ChallengeKind.DISTANCE, ChallengeKind.ACTIVE_DAYS, ChallengeKind.LONG_RIDE), c.map { it.kind })
        assertEquals(listOf(100.0, 4.0, 30.0), c.map { it.target })
        assertTrue(c.all { it.month == nov })
    }

    @Test
    fun starterSetWhenReferenceMonthsAreEmptyEvenWithOlderRides() {
        // Juli liegt vier Monate vor dem November: nicht mehr Referenz.
        val c = monthlyChallenges(listOf(fact(d(2026, 7, 10), 200.0, 3000.0)), nov)
        assertEquals(listOf(100.0, 4.0, 30.0), c.map { it.target })
    }

    @Test
    fun distanceRoundsUpToNext25AndNext10BelowHundred() {
        // 100 -> 110 -> 125; die Gleitkomma-Ungenauigkeit darf keinen Schritt springen.
        assertEquals(125.0, target(monthlyChallenges(reference(100.0), nov), ChallengeKind.DISTANCE))
        // 150 -> 165 -> 175
        assertEquals(175.0, target(monthlyChallenges(reference(150.0), nov), ChallengeKind.DISTANCE))
        // 50 -> 55 -> 60 (feiner Schritt)
        assertEquals(60.0, target(monthlyChallenges(reference(50.0), nov), ChallengeKind.DISTANCE))
        // 90 -> 99 -> 100
        assertEquals(100.0, target(monthlyChallenges(reference(90.0), nov), ChallengeKind.DISTANCE))
    }

    @Test
    fun floatingPointDoesNotJumpARoundingStep() {
        // 250 × 1,1 = 275,00000000000006 in Double: muss 275 bleiben, nicht 300.
        assertEquals(275.0, target(monthlyChallenges(reference(250.0), nov), ChallengeKind.DISTANCE))
        // 1000 m × 1,1 = 1100 -> 1250 (grober Schritt); 100 × 1,1 bleibt 125.
        assertEquals(1250.0, target(monthlyChallenges(reference(30.0, 1000.0), nov), ChallengeKind.ASCENT))
    }

    @Test
    fun ascentRoundsUpToNext250AndNext100BelowThousand() {
        // 2000 -> 2200 -> 2250
        assertEquals(2250.0, target(monthlyChallenges(reference(30.0, 2000.0), nov), ChallengeKind.ASCENT))
        // 500 -> 550 -> 600
        assertEquals(600.0, target(monthlyChallenges(reference(30.0, 500.0), nov), ChallengeKind.ASCENT))
        // 300 -> 330 -> 400
        assertEquals(400.0, target(monthlyChallenges(reference(30.0, 300.0), nov), ChallengeKind.ASCENT))
    }

    @Test
    fun ascentDroppedBelowTwoHundredAverage() {
        val c = monthlyChallenges(reference(30.0, 150.0), nov)
        assertFalse(c.any { it.kind == ChallengeKind.ASCENT })
        assertEquals(
            listOf(ChallengeKind.DISTANCE, ChallengeKind.ACTIVE_DAYS, ChallengeKind.LONG_RIDE),
            c.map { it.kind },
        )
        // Genau 200 reicht.
        assertTrue(monthlyChallenges(reference(30.0, 200.0), nov).any { it.kind == ChallengeKind.ASCENT })
    }

    @Test
    fun activeDaysRoundedPlusOneWithBounds() {
        fun days(vararg perMonth: Int): Double? {
            val facts = perMonth.withIndex().flatMap { (i, n) ->
                val month = nov.minusMonths(i + 1L)
                (1..n).map { fact(month.atDay(it)) }
            }
            return target(monthlyChallenges(facts, nov), ChallengeKind.ACTIVE_DAYS)
        }
        assertEquals(4.0, days(1, 1, 1))     // 1 + 1 = 2 -> mindestens 4
        assertEquals(9.0, days(8, 8, 8))     // 8 + 1
        assertEquals(10.0, days(8, 9, 9))    // Schnitt 8,67 -> 9 + 1
        assertEquals(20.0, days(25, 25, 25)) // hoechstens 20
    }

    @Test
    fun longRideUsesMaxRoundedUpTo5AndCapped() {
        val facts = listOf(
            fact(d(2026, 10, 3), 40.0, id = "a"),
            fact(d(2026, 9, 3), 80.0, id = "b"),
            fact(d(2026, 8, 3), 60.0, id = "c"),
        )
        // 80 × 1,05 = 84 -> 85
        assertEquals(85.0, target(monthlyChallenges(facts, nov), ChallengeKind.LONG_RIDE))
        // 100 × 1,05 = 105 (Gleitkomma 105,00000000000001) -> 105
        assertEquals(105.0, target(monthlyChallenges(listOf(fact(d(2026, 10, 3), 100.0)), nov), ChallengeKind.LONG_RIDE))
        // 300 km -> gedeckelt
        assertEquals(200.0, target(monthlyChallenges(listOf(fact(d(2026, 10, 3), 300.0)), nov), ChallengeKind.LONG_RIDE))
    }

    @Test
    fun emptyReferenceMonthsAreSkippedNotCountedAsZero() {
        // Nur der Oktober hat Touren (100 km); September und August sind leer (Verletzung).
        val facts = listOf(fact(d(2026, 10, 5), 100.0, 600.0))
        val c = monthlyChallenges(facts, nov)
        assertEquals(125.0, target(c, ChallengeKind.DISTANCE))   // nicht 100/3 × 1,1
        assertEquals(700.0, target(c, ChallengeKind.ASCENT))     // 660 -> 700
    }

    @Test
    fun targetsDoNotMoveWhenRidingDuringTheMonth() {
        val history = reference(100.0, 800.0)
        val before = monthlyChallenges(history, nov)
        val during = history + listOf(
            fact(d(2026, 11, 2), 180.0, 3000.0, "x"),
            fact(d(2026, 11, 9), 90.0, 900.0, "y"),
        )
        assertEquals(before, monthlyChallenges(during, nov))
        // Auch der Fortschritt rechnet mit denselben Zielen.
        assertEquals(before, challengeProgress(during, d(2026, 11, 10)).map { it.challenge })
    }

    @Test
    fun orderFollowsEnumOrder() {
        val kinds = monthlyChallenges(reference(100.0, 800.0), nov).map { it.kind }
        assertEquals(ChallengeKind.entries, kinds)
    }

    @Test
    fun progressLongRideUsesMaxNotSum() {
        val facts = reference(100.0, 800.0) + listOf(
            fact(d(2026, 11, 2), 30.0, 100.0, "x"),
            fact(d(2026, 11, 4), 50.0, 200.0, "y"),
        )
        val p = challengeProgress(facts, d(2026, 11, 10)).associateBy { it.challenge.kind }
        assertEquals(50.0, p.getValue(ChallengeKind.LONG_RIDE).current)
        assertEquals(80.0, p.getValue(ChallengeKind.DISTANCE).current)
        assertEquals(300.0, p.getValue(ChallengeKind.ASCENT).current)
    }

    @Test
    fun activeDaysCountsTwoRidesOnOneDayOnce() {
        val facts = listOf(
            fact(d(2026, 11, 2), 10.0, id = "x"),
            fact(d(2026, 11, 2), 12.0, id = "y"),
            fact(d(2026, 11, 3), 12.0, id = "z"),
        )
        val p = challengeProgress(facts, d(2026, 11, 10)).first { it.challenge.kind == ChallengeKind.ACTIVE_DAYS }
        assertEquals(2.0, p.current)
        assertEquals(2.0, p.remaining)
    }

    @Test
    fun progressFractionReachedRemaining() {
        // Startsatz: 100 km, 4 Tage, 30 km.
        val facts = listOf(fact(d(2026, 11, 2), 40.0), fact(d(2026, 11, 3), 40.0))
        val p = challengeProgress(facts, d(2026, 11, 10)).associateBy { it.challenge.kind }
        val dist = p.getValue(ChallengeKind.DISTANCE)
        assertEquals(0.8, dist.fraction, 1e-9)
        assertFalse(dist.reached)
        assertEquals(20.0, dist.remaining, 1e-9)
        val long = p.getValue(ChallengeKind.LONG_RIDE)
        assertTrue(long.reached)
        assertEquals(1.0, long.fraction)
        assertEquals(0.0, long.remaining)
    }

    @Test
    fun daysLeftCountsTodayAndLastDayIsOne() {
        assertEquals(1, challengeProgress(emptyList(), d(2026, 11, 30)).first().daysLeft)
        assertEquals(30, challengeProgress(emptyList(), d(2026, 11, 1)).first().daysLeft)
        assertEquals(31, challengeProgress(emptyList(), d(2026, 12, 1)).first().daysLeft)
        assertEquals(1, challengeProgress(emptyList(), d(2024, 2, 29)).first().daysLeft)
    }

    @Test
    fun futureRidesAreIgnored() {
        val facts = listOf(
            fact(d(2026, 11, 5), 20.0, id = "x"),
            fact(d(2026, 11, 25), 90.0, id = "future"),
        )
        val p = challengeProgress(facts, d(2026, 11, 10)).associateBy { it.challenge.kind }
        assertEquals(20.0, p.getValue(ChallengeKind.DISTANCE).current)
        assertEquals(20.0, p.getValue(ChallengeKind.LONG_RIDE).current)
        assertEquals(1.0, p.getValue(ChallengeKind.ACTIVE_DAYS).current)
    }

    @Test
    fun historyAcrossYearBoundaryNewestFirstWithOwnTargets() {
        // Dezember 2025: Startsatz (100 km / 4 Tage / 30 km); 120 km an 4 Tagen, Spitze 35 km -> alle drei erreicht.
        val dec = listOf(
            fact(d(2025, 12, 1), 35.0, id = "d1"),
            fact(d(2025, 12, 2), 35.0, id = "d2"),
            fact(d(2025, 12, 3), 25.0, id = "d3"),
            fact(d(2025, 12, 4), 25.0, id = "d4"),
        )
        // Januar 2026: Ziele aus dem Dezember (150 km, 800 m -> 900, 5 Tage, 40 km);
        // gefahren wird nur wenig -> nichts erreicht.
        val jan = listOf(fact(d(2026, 1, 10), 20.0, id = "j1"))
        val h = challengeHistory(dec + jan, d(2026, 2, 15))
        assertEquals(listOf(YearMonth.of(2026, 1), YearMonth.of(2025, 12)), h.map { it.month })
        assertEquals(ChallengeMonth(YearMonth.of(2025, 12), reached = 3, total = 3), h[1])
        assertEquals(0, h[0].reached)
        assertEquals(4, h[0].total)
    }

    @Test
    fun historyExcludesCurrentMonthEmptyMonthsAndLimit() {
        val facts = listOf(
            fact(d(2026, 11, 3), 500.0, id = "now"),
            fact(d(2026, 9, 3), 50.0, id = "sep"),
            fact(d(2026, 3, 3), 50.0, id = "mar"),
        )
        val h = challengeHistory(facts, d(2026, 11, 10))
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 3)), h.map { it.month })
        // Nur die letzten 2 Kalendermonate vor dem laufenden: Oktober (leer) und September.
        assertEquals(listOf(YearMonth.of(2026, 9)), challengeHistory(facts, d(2026, 11, 10), months = 2).map { it.month })
        assertEquals(emptyList(), challengeHistory(emptyList(), d(2026, 11, 10)))
    }
}
