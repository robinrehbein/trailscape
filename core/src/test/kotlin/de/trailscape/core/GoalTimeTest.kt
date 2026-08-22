package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `GoalTime.kt` — die Bewertung einer Zielzeit gegen die eigene
 * Historie: noetiger Schnitt, Prognose, Urteil.
 *
 * Die Historie wird wie in `SessionTargetTest` ueber einzelne Touren mit
 * gesetztem `avgSpeedKmh` aufgebaut; [typicalAvgSpeedKmh] nimmt den Median der
 * zehn neuesten verwertbaren Touren — drei Touren mit gleichem Schnitt
 * reichen also, um die Prognose deterministisch zu machen.
 */
class GoalTimeTest {
    private companion object {
        const val EPS = 1e-9
        const val DAY_MS = 24L * 60 * 60 * 1000

        fun ride(createdAt: Long, avgSpeedKmh: Double?): Ride = Ride(
            id = "r$createdAt",
            name = "Tour",
            createdAt = createdAt,
            stats = RideStats(
                distanceKm = 40.0,
                ascentM = 0.0,
                descentM = 0.0,
                avgSpeedKmh = avgSpeedKmh,
            ),
        )

        fun goal(timeMin: Int? = null, distanceKm: Double = 120.0): Goal = Goal(
            name = "Gravel-Ziel",
            distanceKm = distanceKm,
            ascentM = null,
            targetTimeMin = timeMin,
            date = System.currentTimeMillis() + 60 * DAY_MS,
        )
    }

    @Test
    fun `ohne Zielzeit gibt es keine Bewertung`() {
        assertNull(assessGoalTime(goal(timeMin = null), listOf(ride(0, 20.0))))
    }

    @Test
    fun `noetiger Schnitt ist Distanz durch Zeit`() {
        // 120 km in 6:00 h → 20 km/h.
        val assessment = assessGoalTime(goal(timeMin = 360), listOf(ride(0, 20.0)))

        assertNotNull(assessment)
        assertEquals(20.0, assessment.requiredAvgSpeedKmh, EPS)
        assertEquals(360, assessment.targetTimeMin)
    }

    @Test
    fun `Prognose aus dem Historien-Median und Urteil knapp`() {
        // Historie 20 km/h → 120 km in 6:00 h prognostiziert; Zielzeit 6:00 h
        // → Verhaeltnis 1,0 → knapp.
        val assessment = assessGoalTime(goal(timeMin = 360), listOf(ride(0, 20.0)))

        assertNotNull(assessment)
        assertTrue(assessment.basedOnHistory)
        assertEquals(360, assessment.estimatedTimeMin)
        assertEquals(GoalTimeVerdict.KNAPP, assessment.verdict)
    }

    @Test
    fun `Prognose deutlich unter der Zielzeit ist komfortabel`() {
        // Zielzeit 8:00 h (480 min), Historie 20 km/h → Prognose 6:00 h =
        // 75 % der Zielzeit → komfortabel.
        val assessment = assessGoalTime(goal(timeMin = 480), listOf(ride(0, 20.0)))

        assertNotNull(assessment)
        assertEquals(GoalTimeVerdict.KOMFORTABEL, assessment.verdict)
    }

    @Test
    fun `Prognose etwas ueber der Zielzeit ist ambitios`() {
        // Zielzeit 5:30 h (330 min), Historie 20 km/h → Prognose 6:00 h =
        // 109 % → ambitios.
        val assessment = assessGoalTime(goal(timeMin = 330), listOf(ride(0, 20.0)))

        assertNotNull(assessment)
        assertEquals(GoalTimeVerdict.AMBITIOS, assessment.verdict)
    }

    @Test
    fun `Prognose weit ueber der Zielzeit ist unrealistisch`() {
        // Zielzeit 4:00 h (240 min), Historie 20 km/h → Prognose 6:00 h =
        // 150 % → unrealistisch.
        val assessment = assessGoalTime(goal(timeMin = 240), listOf(ride(0, 20.0)))

        assertNotNull(assessment)
        assertEquals(GoalTimeVerdict.UNREALISTISCH, assessment.verdict)
    }

    @Test
    fun `ohne Historie prognostiziert der Fallback-Schnitt und die Basis ist markiert`() {
        val assessment = assessGoalTime(goal(timeMin = 360), emptyList())

        assertNotNull(assessment)
        assertFalse(assessment.basedOnHistory)
        // 120 km / 18 km/h = 6,667 h = 400 min.
        assertEquals(400, assessment.estimatedTimeMin)
        // 400/360 > 1,15 → unrealistisch? Nein: 1,111 → ambitios.
        assertEquals(GoalTimeVerdict.AMBITIOS, assessment.verdict)
    }

    @Test
    fun `gespeicherte Planungen verfaelschen die Prognose nicht`() {
        // Eine geplante (nicht gefahrene) 40-km/h-Tour darf den Median nicht
        // hochziehen — `planned = true` faellt aus der Historie heraus.
        val plannedRide = ride(0, 40.0).copy(planned = true)
        val assessment = assessGoalTime(goal(timeMin = 360), listOf(plannedRide, ride(DAY_MS, 20.0)))

        assertNotNull(assessment)
        assertTrue(assessment.basedOnHistory)
        assertEquals(360, assessment.estimatedTimeMin)
    }

    @Test
    fun `requiredPaceKmh lehnt unsinnige Eingaben ab`() {
        assertNull(requiredPaceKmh(0.0, 360))
        assertNull(requiredPaceKmh(120.0, 0))
        assertNull(requiredPaceKmh(-5.0, 360))
        assertNull(requiredPaceKmh(Double.NaN, 360))
        assertEquals(2.0, requiredPaceKmh(10.0, 300)!!, EPS)
    }
}
