package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests fuer die Portierung von `lib/fitness.dart`.
 *
 * Zur Dart-Fassung existierte kein eigener Test; die Erwartungswerte hier sind
 * direkt aus dem Dart-Code abgeleitet (8-Wochen-Fenster, Mittelwerte ueber das
 * Fenster, Rundung auf eine Nachkommastelle bzw. ganze Hoehenmeter).
 */
class FitnessTest {
    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val WINDOW_MS = 8L * 7L * DAY_MS
        const val EPS = 1e-9
    }

    private fun ride(
        distanceKm: Double,
        ascentM: Double = 0.0,
        createdAt: Long = NOW - DAY_MS,
        id: String = "r",
    ) = Ride(
        id = id,
        name = "Testfahrt",
        createdAt = createdAt,
        stats = RideStats(distanceKm = distanceKm, ascentM = ascentM, descentM = 0.0),
    )

    private fun rides(count: Int, distanceKm: Double, ascentM: Double = 0.0) =
        (0 until count).map { ride(distanceKm, ascentM, id = "r$it") }

    @Test
    fun `ohne Fahrten Einsteiger mit Nullwerten`() {
        val result = assessFitness(emptyList(), now = NOW)

        assertEquals(FitnessLevel.EINSTEIGER, result.level)
        assertEquals(0.0, result.weeklyKm, EPS)
        assertEquals(0.0, result.weeklyHm, EPS)
        assertEquals(0.0, result.weeklyRides, EPS)
        assertEquals(0.0, result.longestRideKm, EPS)
        assertEquals(0, result.rideCount)
    }

    @Test
    fun `Fahrten ausserhalb des Fensters zaehlen nicht`() {
        val zuAlt = ride(120.0, createdAt = NOW - WINDOW_MS - 1)
        val inZukunft = ride(120.0, createdAt = NOW + 1)

        val result = assessFitness(listOf(zuAlt, inZukunft), now = NOW)

        assertEquals(0, result.rideCount)
        assertEquals(FitnessLevel.EINSTEIGER, result.level)
    }

    @Test
    fun `Fahrt genau auf der Fenstergrenze zaehlt`() {
        val result = assessFitness(listOf(ride(40.0, createdAt = NOW - WINDOW_MS)), now = NOW)

        assertEquals(1, result.rideCount)
        assertEquals(40.0, result.longestRideKm, EPS)
    }

    @Test
    fun `Fahrten ohne Distanz werden ignoriert`() {
        val result = assessFitness(listOf(ride(0.0, ascentM = 500.0), ride(10.0)), now = NOW)

        assertEquals(1, result.rideCount)
        assertEquals(0.0, result.weeklyHm, EPS)
        assertEquals(10.0, result.longestRideKm, EPS)
    }

    @Test
    fun `Mittelwerte laufen ueber acht Wochen nicht ueber gefahrene Wochen`() {
        // Eine einzige Fahrt: 12,34 km / 8 = 1,5425 -> 1,5; 100 hm / 8 = 12,5 -> 13.
        val result = assessFitness(listOf(ride(12.34, ascentM = 100.0)), now = NOW)

        assertEquals(1.5, result.weeklyKm, EPS)
        assertEquals(13.0, result.weeklyHm, EPS)
        assertEquals(0.1, result.weeklyRides, EPS)
        assertEquals(12.3, result.longestRideKm, EPS)
        assertEquals(1, result.rideCount)
    }

    @Test
    fun `Einsteiger bei geringem Umfang`() {
        val result = assessFitness(rides(4, 20.0), now = NOW)

        assertEquals(FitnessLevel.EINSTEIGER, result.level)
        assertEquals(10.0, result.weeklyKm, EPS)
        assertEquals(0.5, result.weeklyRides, EPS)
        assertEquals(20.0, result.longestRideKm, EPS)
    }

    @Test
    fun `Fortgeschritten ab 50 km, 35 km Longride und 1,5 Fahrten pro Woche`() {
        val result = assessFitness(rides(12, 40.0, ascentM = 300.0), now = NOW)

        assertEquals(FitnessLevel.FORTGESCHRITTEN, result.level)
        assertEquals(60.0, result.weeklyKm, EPS)
        assertEquals(450.0, result.weeklyHm, EPS)
        assertEquals(1.5, result.weeklyRides, EPS)
        assertEquals(40.0, result.longestRideKm, EPS)
        assertEquals(12, result.rideCount)
    }

    @Test
    fun `Ambitioniert ab 100 km, 70 km Longride und 2,5 Fahrten pro Woche`() {
        val alltag = rides(23, 40.0)
        val longride = ride(80.0, id = "long")

        val result = assessFitness(alltag + longride, now = NOW)

        assertEquals(FitnessLevel.AMBITIONIERT, result.level)
        assertEquals(125.0, result.weeklyKm, EPS)
        assertEquals(3.0, result.weeklyRides, EPS)
        assertEquals(80.0, result.longestRideKm, EPS)
        assertEquals(24, result.rideCount)
    }

    @Test
    fun `ohne Longride bleibt es trotz Umfang fortgeschritten`() {
        // 24 Fahrten a 45 km: 135 km/Woche und 3 Fahrten/Woche, aber der
        // laengste Ritt liegt unter den geforderten 70 km.
        val result = assessFitness(rides(24, 45.0), now = NOW)

        assertEquals(FitnessLevel.FORTGESCHRITTEN, result.level)
        assertEquals(135.0, result.weeklyKm, EPS)
        assertEquals(45.0, result.longestRideKm, EPS)
    }
}
