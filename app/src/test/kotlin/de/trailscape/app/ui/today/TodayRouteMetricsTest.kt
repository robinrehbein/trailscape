package de.trailscape.app.ui.today

import de.trailscape.core.AscentPreference
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for deterministic ascent estimation and duration calculation on
 * `TodayScreen` / `TodayRoutePreview`.
 */
class TodayRouteMetricsTest {

    private fun target(
        distanceKm: Double = 40.0,
        ascentPreference: AscentPreference = AscentPreference.MODERAT,
        durationH: Double? = null,
        speedKmh: Double = 24.0,
    ) = RouteTarget(
        distanceKm = distanceKm,
        ascentPreference = ascentPreference,
        durationH = durationH,
        speedKmh = speedKmh,
        intensity = SessionIntensity.GRUNDLAGE,
        label = "Grundlage",
        source = RouteTargetSource.PLAN,
    )

    @Test
    fun `estimatedAscentM scales deterministically with distance and preference`() {
        assertEquals(200, estimatedAscentM(40.0, AscentPreference.FLACH))
        assertEquals(480, estimatedAscentM(40.0, AscentPreference.MODERAT))
        assertEquals(800, estimatedAscentM(40.0, AscentPreference.BERGIG))
    }

    @Test
    fun `estimatedAscentM handles zero negative and non-finite distances`() {
        assertEquals(0, estimatedAscentM(0.0, AscentPreference.BERGIG))
        assertEquals(0, estimatedAscentM(-15.0, AscentPreference.MODERAT))
        assertEquals(0, estimatedAscentM(Double.NaN, AscentPreference.FLACH))
        assertEquals(0, estimatedAscentM(Double.POSITIVE_INFINITY, AscentPreference.MODERAT))
    }

    @Test
    fun `estimatedAscentM rounds to nearest 10 m`() {
        // 26 km * 5 m/km = 130 m
        assertEquals(130, estimatedAscentM(26.0, AscentPreference.FLACH))
        // 21.4 km * 12 m/km = 256.8 -> 260 m
        assertEquals(260, estimatedAscentM(21.4, AscentPreference.MODERAT))
    }

    @Test
    fun `routeDurationMinutes prefers explicit durationH over speed fallback`() {
        val t = target(distanceKm = 40.0, durationH = 1.5, speedKmh = 20.0)
        assertEquals(90, routeDurationMinutes(t))
    }

    @Test
    fun `routeDurationMinutes falls back to fallbackMinutes then distance over speed`() {
        val withoutDuration = target(distanceKm = 36.0, durationH = null, speedKmh = 24.0)
        assertEquals(75, routeDurationMinutes(withoutDuration, fallbackMinutes = 75))
        assertEquals(90, routeDurationMinutes(withoutDuration, fallbackMinutes = null))
    }

    @Test
    fun `routeDurationMinutes clamps invalid speed and tiny duration to at least 1 minute`() {
        val tiny = target(distanceKm = 0.1, durationH = null, speedKmh = 0.0)
        assertTrue(routeDurationMinutes(tiny) >= 1)
    }

    @Test
    fun `estimatedAscentM clamps Double MAX_VALUE and NEGATIVE_INFINITY without overflow`() {
        val maxAscent = estimatedAscentM(Double.MAX_VALUE, AscentPreference.BERGIG)
        assertTrue(maxAscent > 0)
        assertEquals((Int.MAX_VALUE / 10) * 10, maxAscent)
        assertEquals(0, estimatedAscentM(Double.NEGATIVE_INFINITY, AscentPreference.BERGIG))
    }

    @Test
    fun `routeDurationMinutes handles NaN Infinity MAX_VALUE and zero distances safely`() {
        assertEquals(1, routeDurationMinutes(target(distanceKm = Double.NaN, durationH = null, speedKmh = 24.0)))
        assertEquals(1, routeDurationMinutes(target(distanceKm = Double.POSITIVE_INFINITY, durationH = null, speedKmh = 24.0)))
        assertEquals(1, routeDurationMinutes(target(distanceKm = Double.NEGATIVE_INFINITY, durationH = null, speedKmh = 24.0)))
        assertEquals(1, routeDurationMinutes(target(distanceKm = 0.0, durationH = null, speedKmh = 24.0)))
        assertEquals(
            45,
            routeDurationMinutes(target(distanceKm = Double.NaN, durationH = null, speedKmh = 24.0), fallbackMinutes = 45),
        )
        assertEquals(90, routeDurationMinutes(target(distanceKm = 36.0, durationH = Double.NaN, speedKmh = 24.0)))
        assertEquals(
            90,
            routeDurationMinutes(target(distanceKm = 36.0, durationH = Double.POSITIVE_INFINITY, speedKmh = 24.0)),
        )
        assertEquals(
            98,
            routeDurationMinutes(target(distanceKm = 36.0, durationH = Double.POSITIVE_INFINITY, speedKmh = Double.NaN)),
        )
        assertEquals(Int.MAX_VALUE, routeDurationMinutes(target(distanceKm = Double.MAX_VALUE, durationH = null, speedKmh = 24.0)))
        assertEquals(Int.MAX_VALUE, routeDurationMinutes(target(distanceKm = 40.0, durationH = Double.MAX_VALUE, speedKmh = 24.0)))
    }
}
