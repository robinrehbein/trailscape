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
    fun longRideUsesAverageOfMonthlyLongestRoundedUpTo5AndCapped() {
        val facts = listOf(
            fact(d(2026, 10, 3), 40.0, id = "a"),
            fact(d(2026, 9, 3), 80.0, id = "b"),
            fact(d(2026, 8, 3), 60.0, id = "c"),
        )
        // Schnitt der Monatslaengsten: (40 + 80 + 60) / 3 = 60; × 1,05 = 63 -> 65
        assertEquals(65.0, target(monthlyChallenges(facts, nov), ChallengeKind.LONG_RIDE))
        // Ein Ausreisser praegt nicht drei Monate: (180 + 60 + 60) / 3 = 100 -> 105, nicht 190.
        val withEvent = listOf(
            fact(d(2026, 10, 3), 60.0, id = "a"),
            fact(d(2026, 9, 3), 180.0, id = "b"),
            fact(d(2026, 8, 3), 60.0, id = "c"),
        )
        assertEquals(105.0, target(monthlyChallenges(withEvent, nov), ChallengeKind.LONG_RIDE))
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
        assertEquals(ChallengeMonth(YearMonth.of(2025, 12), reached = 3, total = 3, bonusReached = 1), h[1])
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

    // --- Bonus-Stufe -------------------------------------------------------

    private fun ch(kind: ChallengeKind, target: Double, month: YearMonth = nov) = Challenge(kind, month, target)

    private fun bonus(kind: ChallengeKind, target: Double, month: YearMonth = nov) =
        bonusTargetFor(ch(kind, target, month))

    @Test
    fun bonusDistanceRoundsUpWithSameStepRule() {
        // 125 × 1,25 = 156,25 -> 175 (grober Schritt)
        assertEquals(175.0, bonus(ChallengeKind.DISTANCE, 125.0))
        // 100 × 1,25 = 125 exakt -> bleibt 125, kein Sprung
        assertEquals(125.0, bonus(ChallengeKind.DISTANCE, 100.0))
        // 60 × 1,25 = 75 < 100 -> feiner Schritt 10 -> 80
        assertEquals(80.0, bonus(ChallengeKind.DISTANCE, 60.0))
        // 10 × 1,25 = 12,5 -> 20, strikt groesser
        assertEquals(20.0, bonus(ChallengeKind.DISTANCE, 10.0))
        // 80 × 1,25 = 100 -> nicht unter 100: grober Schritt, 100 bleibt 100
        assertEquals(100.0, bonus(ChallengeKind.DISTANCE, 80.0))
    }

    @Test
    fun bonusAscentRoundsUpWithSameStepRule() {
        // 800 × 1,25 = 1000 -> grober Schritt, 1000
        assertEquals(1000.0, bonus(ChallengeKind.ASCENT, 800.0))
        // 900 × 1,25 = 1125 -> 1250
        assertEquals(1250.0, bonus(ChallengeKind.ASCENT, 900.0))
        // 400 × 1,25 = 500 -> feiner Schritt 100 -> 500
        assertEquals(500.0, bonus(ChallengeKind.ASCENT, 400.0))
        // 300 × 1,25 = 375 -> 400
        assertEquals(400.0, bonus(ChallengeKind.ASCENT, 300.0))
    }

    @Test
    fun bonusActiveDaysAddsThreeCappedByMonthLength() {
        assertEquals(10.0, bonus(ChallengeKind.ACTIVE_DAYS, 7.0))
        // November hat 30 Tage: 28 + 3 -> 30
        assertEquals(30.0, bonus(ChallengeKind.ACTIVE_DAYS, 28.0))
        // Februar 2027 hat 28 Tage: 20 + 3 = 23, unter der Kappe
        assertEquals(23.0, bonus(ChallengeKind.ACTIVE_DAYS, 20.0, YearMonth.of(2027, 2)))
    }

    @Test
    fun bonusLongRideRoundsUpToFiveAndIsCapped() {
        // 40 × 1,15 = 46 -> 50
        assertEquals(50.0, bonus(ChallengeKind.LONG_RIDE, 40.0))
        // 100 × 1,15 = 115 (Gleitkomma 114,99999…) -> 115, nicht 120
        assertEquals(115.0, bonus(ChallengeKind.LONG_RIDE, 100.0))
        // 180 × 1,15 = 207 -> gekappt auf 200
        assertEquals(200.0, bonus(ChallengeKind.LONG_RIDE, 180.0))
        // 5 × 1,15 = 5,75 -> 10
        assertEquals(10.0, bonus(ChallengeKind.LONG_RIDE, 5.0))
    }

    @Test
    fun bonusIsStrictlyGreaterUnlessCapped() {
        for (kind in listOf(ChallengeKind.DISTANCE, ChallengeKind.ASCENT)) {
            var t = 10.0
            while (t < 3000) {
                assertTrue(bonus(kind, t) > t, "$kind $t")
                t += 10.0
            }
        }
        for (t in 4..27) assertTrue(bonus(ChallengeKind.ACTIVE_DAYS, t.toDouble()) > t)
        var t = 5.0
        while (t < 200.0) {
            assertTrue(bonus(ChallengeKind.LONG_RIDE, t) > t, "long $t")
            t += 5.0
        }
    }

    @Test
    fun noBonusWhenCapMakesItImpossible() {
        // Tour: Kappe 200 = Ziel
        val long = ChallengeProgress(ch(ChallengeKind.LONG_RIDE, 200.0), current = 150.0, daysLeft = 5)
        assertEquals(200.0, long.bonusTarget)
        assertFalse(long.hasBonus)
        assertFalse(long.bonusReached)
        assertEquals(0.0, long.bonusFraction)
        val longDone = long.copy(current = 210.0)
        assertTrue(longDone.reached)
        assertEquals(longDone.reached, longDone.bonusReached)
        assertEquals(0.0, longDone.bonusFraction)
        // Tage: Ziel = Monatslaenge
        val days = ChallengeProgress(ch(ChallengeKind.ACTIVE_DAYS, 30.0), current = 30.0, daysLeft = 1)
        assertEquals(30.0, days.bonusTarget)
        assertFalse(days.hasBonus)
        assertEquals(days.reached, days.bonusReached)
    }

    @Test
    fun tierSwitchesExactlyAtBaseTarget() {
        val base = ch(ChallengeKind.DISTANCE, 100.0) // Bonus 125
        val below = ChallengeProgress(base, 99.99, 10)
        assertEquals(ChallengeTier.BASE, below.tier)
        assertEquals(0.0, below.bonusFraction)
        val at = ChallengeProgress(base, 100.0, 10)
        assertEquals(ChallengeTier.BONUS, at.tier)
        assertFalse(at.bonusReached)
        assertEquals(0.0, at.bonusFraction)
        assertEquals(25.0, at.bonusRemaining)
    }

    @Test
    fun bonusFractionRunsFromTargetToBonus() {
        val base = ch(ChallengeKind.DISTANCE, 100.0) // Bonus 125
        assertEquals(0.4, ChallengeProgress(base, 110.0, 10).bonusFraction, 1e-9)
        assertEquals(15.0, ChallengeProgress(base, 110.0, 10).bonusRemaining, 1e-9)
        val done = ChallengeProgress(base, 130.0, 10)
        assertTrue(done.bonusReached)
        assertEquals(1.0, done.bonusFraction)
        assertEquals(0.0, done.bonusRemaining)
        // Basis-Member bleiben unveraendert
        assertEquals(1.0, done.fraction)
        assertEquals(0.0, done.remaining)
    }

    @Test
    fun historyCountsBonusReached() {
        // Oktober: Startsatz (100 km / 4 Tage / 30 km), Boni 125 km / 7 Tage / 35 km.
        // 9 Touren an 9 Tagen, 5 km mehr als... 130 km gesamt, Spitze 40 km.
        val oct = (1..9).map { fact(d(2026, 10, it), 10.0, id = "o$it") } +
            fact(d(2026, 10, 20), 40.0, id = "long")
        val h = challengeHistory(oct, d(2026, 11, 10)).single()
        // km 130 >= 125 (Bonus), Tage 10 >= 7 (Bonus), Tour 40 >= 35 (Bonus)
        assertEquals(3, h.reached)
        assertEquals(3, h.bonusReached)
    }

    @Test
    fun historyBonusNotCountedWhenOnlyBaseReached() {
        // 4 Tage, 120 km, Spitze 35: Ziele erreicht, Bonus 125 km / 7 Tage nicht, Tour-Bonus 35 ja.
        val dec = listOf(
            fact(d(2025, 12, 1), 35.0, id = "d1"),
            fact(d(2025, 12, 2), 35.0, id = "d2"),
            fact(d(2025, 12, 3), 25.0, id = "d3"),
            fact(d(2025, 12, 4), 25.0, id = "d4"),
        )
        val h = challengeHistory(dec, d(2026, 1, 15)).single()
        assertEquals(3, h.reached)
        assertEquals(1, h.bonusReached)
    }
}
