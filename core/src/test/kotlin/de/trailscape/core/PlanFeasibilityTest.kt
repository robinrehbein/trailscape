package de.trailscape.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plausibilitaet der Plangenerierung — die Tests, die der Auswertung des
 * Reviews zufolge komplett fehlten.
 *
 * Geprueft werden die beiden Faelle, die frueher lautlos durchgingen:
 *
 *  * ein Plan, dessen laengste Fahrt die Zieldistanz nicht annaehernd erreicht
 *    (und der trotzdem als „Plan mit 12 Wochen erstellt" gemeldet wurde), und
 *  * ein Plan, der in Woche 1 auf das Spitzenvolumen springt, weil es nur eine
 *    einzige Aufbauwoche gibt.
 */
class PlanFeasibilityTest {

    /** Mittwoch, 7. Januar 2026, 12:00 lokal — wie in [TrainingTest]. */
    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    /** Zieltermin am Samstag der Woche [weeks] nach der aktuellen. */
    private fun goalIn(weeks: Int, distanceKm: Double, ascentM: Double? = null): Goal = Goal(
        name = "Zielevent",
        distanceKm = distanceKm,
        ascentM = ascentM,
        date = dartEpochMs(firstMonday.plusDays(weeks * 7L + 5).atTime(9, 0)),
    )

    private fun assessment(level: FitnessLevel, weeklyKm: Double) = FitnessAssessment(
        level = level,
        weeklyKm = weeklyKm,
        weeklyHm = weeklyKm * 8,
        weeklyRides = 3.0,
        longestRideKm = weeklyKm / 2,
        rideCount = 20,
    )

    /** Laengste geplante Trainingsfahrt (ohne das Zielevent). */
    private fun longestRide(plan: TrainingPlan): Int =
        plan.weeks.flatMap { it.sessions }.filterNot { it.isEvent }.maxOf { it.targetKm }

    // -----------------------------------------------------------------------
    // K2a — unerreichbare Ziele werden benannt
    // -----------------------------------------------------------------------

    @Test
    fun `Einsteiger ohne Historie mit 200-km-Ziel in 12 Wochen wird gewarnt`() {
        val plan = generatePlan(
            goalIn(11, 200.0),
            assessment(FitnessLevel.EINSTEIGER, 0.0),
            now = now,
        )
        val verdict = assessPlanFeasibility(plan)

        assertFalse(verdict.feasible)
        // Die laengste Trainingsfahrt bleibt weit unter der Zieldistanz.
        assertEquals(longestRide(plan), verdict.longestRideKm)
        assertTrue(verdict.coverage < 0.3, "Abdeckung war ${verdict.coverage}")

        // Der Hinweis nennt Zahl, Anteil und einen konkreten Gegenvorschlag.
        val message = assertNotNull(verdict.message)
        assertTrue(message.contains("${verdict.longestRideKm} km"))
        assertTrue(message.contains("200 km"))
        val suggested = assertNotNull(verdict.suggestedDistanceKm)
        assertTrue(message.contains("$suggested km"))
        assertTrue(suggested < 200)

        // … und die Zeit, die es fuer das eigentliche Ziel braeuchte.
        val weeks = assertNotNull(verdict.suggestedWeeks)
        assertTrue(weeks > plan.weeks.size, "Es sollten mehr als 12 Wochen noetig sein, waren $weeks")
        assertTrue(message.contains("$weeks Wochen"))
    }

    @Test
    fun `der Gegenvorschlag ist selbst tragfaehig`() {
        val level = FitnessLevel.EINSTEIGER
        val start = assessment(level, 0.0)
        val plan = generatePlan(goalIn(11, 200.0), start, now = now)
        val verdict = assessPlanFeasibility(plan)

        // Dieselbe Laufzeit, aber die vorgeschlagene Distanz: Der Plan traegt.
        val reduced = generatePlan(
            goalIn(11, verdict.suggestedDistanceKm!!.toDouble()),
            start,
            now = now,
        )
        assertTrue(assessPlanFeasibility(reduced).feasible)

        // Und mit der vorgeschlagenen Laufzeit traegt auch das urspruengliche Ziel.
        val longer = generatePlan(goalIn(verdict.suggestedWeeks!! - 1, 200.0), start, now = now)
        assertEquals(verdict.suggestedWeeks, longer.weeks.size)
        assertTrue(assessPlanFeasibility(longer).feasible)
    }

    @Test
    fun `ein tragfaehiger Plan bekommt keine Warnung`() {
        val plan = generatePlan(
            goalIn(23, 200.0),
            assessment(FitnessLevel.AMBITIONIERT, 150.0),
            now = now,
        )
        val verdict = assessPlanFeasibility(plan)

        assertTrue(verdict.feasible)
        assertNull(verdict.message)
        assertNull(verdict.suggestedDistanceKm)
        assertNull(verdict.suggestedWeeks)
        assertTrue(verdict.coverage >= minLongestRideShare)
    }

    @Test
    fun `ein Plan, der seinen Peak erreicht, nimmt die Schwelle in jeder Stufe`() {
        // Der Zusammenhang aus dem KDoc von aufbauSessions: Die Anteile der
        // Schluesseleinheit sind so gewaehlt, dass ein voll ausgefahrener Plan
        // die Machbarkeitsschwelle nicht knapp verfehlt.
        for (level in FitnessLevel.entries) {
            val plan = generatePlan(
                // 40 Wochen sind fuer jede Stufe genug, um 1,3 × Ziel zu erreichen.
                goalIn(39, 120.0),
                assessment(level, 60.0),
                now = now,
            )
            val verdict = assessPlanFeasibility(plan)
            assertTrue(verdict.feasible, "$level: Abdeckung nur ${verdict.coverage}")
        }
    }

    @Test
    fun `unsinnige Zieldistanz erzeugt keine Warnung`() {
        val plan = generatePlan(goalIn(11, 160.0), assessment(FitnessLevel.FORTGESCHRITTEN, 95.0), now)
        val broken = plan.copy(goal = plan.goal.copy(distanceKm = 0.0))

        val verdict = assessPlanFeasibility(broken)
        assertTrue(verdict.feasible)
        assertNull(verdict.message)
    }

    // -----------------------------------------------------------------------
    // K2b — keine absurden Wochenspruenge
    // -----------------------------------------------------------------------

    @Test
    fun `Ambitioniert mit 3 Wochen springt nicht mehr auf den Peak`() {
        val start = 150.0
        val plan = generatePlan(
            goalIn(2, 200.0),
            assessment(FitnessLevel.AMBITIONIERT, start),
            now = now,
        )

        // Frueher: buildCount = 1 → progress = 1,0 → Woche 1 auf 260 km (+73 %).
        val firstWeek = plan.weeks.first().targetKm
        assertEquals(WeekKind.AUFBAU, plan.weeks.first().kind)
        assertTrue(
            firstWeek <= start * 1.15,
            "Woche 1 sprang auf $firstWeek km (Start $start km)",
        )
        assertTrue(firstWeek > start, "Eine Aufbauwoche soll trotzdem aufbauen")
    }

    @Test
    fun `keine Aufbauwoche springt um mehr als 15 Prozent - ueber alle Stufen und Laufzeiten`() {
        for (level in FitnessLevel.entries) {
            for (weeks in listOf(3, 4, 6, 12, 24, 52)) {
                val plan = generatePlan(
                    goalIn(weeks - 1, 300.0),
                    assessment(level, 80.0),
                    now = now,
                )
                var lastBuild: Int? = null
                for (week in plan.weeks.filter { it.kind == WeekKind.AUFBAU }) {
                    val previous = lastBuild
                    if (previous != null) {
                        assertTrue(
                            week.targetKm <= previous * 1.15,
                            "$level/$weeks Wochen: $previous → ${week.targetKm} km",
                        )
                    }
                    lastBuild = week.targetKm
                }
            }
        }
    }

    @Test
    fun `der Taper rechnet mit dem erreichten und nicht mit dem angestrebten Peak`() {
        // Kurzer Plan: Der angestrebte Peak (1,3 × 200 = 260 km) wird nie
        // erreicht — die Taperwoche darf trotzdem nicht 130 km vorschreiben.
        val plan = generatePlan(
            goalIn(2, 200.0),
            assessment(FitnessLevel.AMBITIONIERT, 150.0),
            now = now,
        )
        val peak = plan.weeks.filter { it.kind == WeekKind.AUFBAU }.maxOf { it.targetKm }
        val taper = plan.weeks.first { it.kind == WeekKind.TAPER }

        assertEquals(round5ForTest(peak * 0.5), taper.targetKm)
        assertTrue(taper.targetKm < 130)
    }

    /** Spiegelt das `round5` der Plangenerierung fuer die Erwartungswerte. */
    private fun round5ForTest(km: Double): Int = maxOf(5, dartRound(km / 5).toInt() * 5)

    // -----------------------------------------------------------------------
    // weeksForFeasibleGoal
    // -----------------------------------------------------------------------

    @Test
    fun `weeksForFeasibleGoal liefert die erste tragfaehige Laufzeit`() {
        val weeks = assertNotNull(
            weeksForFeasibleGoal(goalIn(11, 120.0), FitnessLevel.FORTGESCHRITTEN, 70.0),
        )
        assertTrue(weeks in 3..52)

        // Eine Woche weniger traegt noch nicht, die gemeldete traegt.
        val start = assessment(FitnessLevel.FORTGESCHRITTEN, 70.0)
        assertTrue(assessPlanFeasibility(generatePlan(goalIn(weeks - 1, 120.0), start, now)).feasible)
        if (weeks > 3) {
            assertFalse(
                assessPlanFeasibility(generatePlan(goalIn(weeks - 2, 120.0), start, now)).feasible,
            )
        }
    }

    @Test
    fun `voellig unerreichbare Ziele melden keine Wochenzahl`() {
        // 20 000 km (Tippfehler beim Eintragen, oder ein Etappenrennen) sind aus
        // 40 km Wochenvolumen auch in einem Jahr nicht aufzubauen — dann gibt es
        // keine Zahl, sondern einen Rat.
        val plan = generatePlan(goalIn(11, 20_000.0), assessment(FitnessLevel.EINSTEIGER, 0.0), now)
        val verdict = assessPlanFeasibility(plan)

        assertFalse(verdict.feasible)
        assertNull(verdict.suggestedWeeks)
        assertTrue(assertNotNull(verdict.message).contains("Zwischendistanz"))
    }
}
