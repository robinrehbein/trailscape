package de.trailscape.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Kurvenaufbereitung der Tour-Detailansicht
 * (`RideCurves.kt`). Kein Dart-Vorbild — die Faelle bilden ab, was in echten
 * Dateien vorkommt: fehlende Zeitstempel (GPX-Import), fehlende Herzfrequenz,
 * sehr kurze Touren und GPS-Ausreisser.
 */
class RideCurvesTest {

    private companion object {
        const val EPS = 1e-6

        /** Ein Grad Laenge am Aequator sind rund 111,32 km. */
        const val METERS_PER_DEGREE_LON = 111_319.49

        /**
         * Baut eine Spur entlang des Aequators: [count] Punkte im Abstand von
         * [stepM] Metern und [stepS] Sekunden.
         */
        fun straightRide(
            count: Int,
            stepM: Double,
            stepS: Long,
            startMs: Long = 1_700_000_000_000L,
            hrAt: (Int) -> Int? = { null },
        ): List<TrackPoint> = (0 until count).map { index ->
            TrackPoint(
                lat = 0.0,
                lon = index * stepM / METERS_PER_DEGREE_LON,
                time = startMs + index * stepS * 1000,
                hr = hrAt(index),
            )
        }
    }

    // --- speedCurveKmh ---

    @Test
    fun `konstantes Tempo ergibt eine flache Kurve`() {
        // 10 m/s = 36 km/h.
        val curve = speedCurveKmh(straightRide(count = 60, stepM = 10.0, stepS = 1))

        assertNotNull(curve)
        assertTrue(curve.samples.size >= 2)
        curve.samples.forEach {
            assertTrue(abs(it.value - 36.0) < 0.5, "erwartet rund 36 km/h, war ${it.value}")
        }
        assertTrue(abs(curve.maxValue - curve.minValue) < 0.5)
    }

    @Test
    fun `die Distanzachse endet bei der Gesamtdistanz der Tour`() {
        val points = straightRide(count = 30, stepM = 20.0, stepS = 2)
        val curve = speedCurveKmh(points)

        assertNotNull(curve)
        assertEquals(computeStats(points).distanceKm, curve.totalKm, 1e-3)
        assertTrue(curve.samples.first().distanceKm < curve.totalKm)
    }

    @Test
    fun `ohne Zeitstempel gibt es keine Tempokurve`() {
        val points = listOf(
            TrackPoint(lat = 48.1, lon = 11.5),
            TrackPoint(lat = 48.2, lon = 11.6),
            TrackPoint(lat = 48.3, lon = 11.7),
        )

        assertNull(speedCurveKmh(points))
    }

    @Test
    fun `zwei Punkte liefern zu wenig fuer eine Tempokurve`() {
        assertNull(speedCurveKmh(straightRide(count = 2, stepM = 10.0, stepS = 1)))
    }

    @Test
    fun `eine Tour ohne Streckenlaenge hat keine Distanzachse`() {
        val standing = (0 until 10).map { index ->
            TrackPoint(lat = 48.1, lon = 11.5, time = 1_000L + index * 1000)
        }

        assertNull(speedCurveKmh(standing))
    }

    @Test
    fun `Segmente ohne Zeitstempel zaehlen zur Distanz aber nicht zum Tempo`() {
        val points = straightRide(count = 20, stepM = 10.0, stepS = 1).mapIndexed { index, point ->
            // Mitten in der Tour fehlt die Zeit — typisch fuer zusammengefuegte
            // oder von Hand bearbeitete GPX-Dateien.
            if (index in 8..11) point.copy(time = null) else point
        }

        val curve = speedCurveKmh(points)

        assertNotNull(curve)
        // Die Luecke faellt aus den Messwerten heraus, die Strecke bleibt ganz.
        assertEquals(computeStats(points).distanceKm, curve.totalKm, 1e-3)
        curve.samples.forEach {
            assertTrue(it.value < 100.0, "kein Sprungwert erwartet, war ${it.value}")
        }
    }

    @Test
    fun `zurueckspringende Zeitstempel werden uebersprungen`() {
        val points = straightRide(count = 10, stepM = 10.0, stepS = 1).toMutableList()
        points[5] = points[5].copy(time = points[0].time!! - 60_000)

        val curve = speedCurveKmh(points)

        assertNotNull(curve)
        curve.samples.forEach { assertTrue(it.value.isFinite() && it.value >= 0.0) }
    }

    @Test
    fun `die Glaettung daempft einen GPS-Ausreisser`() {
        // Ein einzelner Sprung von 200 m in einer Sekunde (720 km/h).
        val points = straightRide(count = 60, stepM = 10.0, stepS = 1).mapIndexed { index, point ->
            if (index >= 30) {
                point.copy(lon = point.lon + 190.0 / METERS_PER_DEGREE_LON)
            } else {
                point
            }
        }

        val smoothed = speedCurveKmh(points, smoothingWindowS = 30.0)
        val unsmoothed = speedCurveKmh(points, smoothingWindowS = 0.0)

        assertNotNull(smoothed)
        assertNotNull(unsmoothed)
        assertTrue(unsmoothed.maxValue > 600.0, "Rohwert war ${unsmoothed.maxValue}")
        assertTrue(
            smoothed.maxValue < unsmoothed.maxValue / 4,
            "geglaettet war ${smoothed.maxValue}, roh ${unsmoothed.maxValue}",
        )
    }

    @Test
    fun `lange Touren werden auf die gewuenschte Aufloesung ausgeduennt`() {
        val curve = speedCurveKmh(
            straightRide(count = 5_000, stepM = 5.0, stepS = 1),
            maxSamples = 100,
        )

        assertNotNull(curve)
        assertTrue(curve.samples.size <= 101, "waren ${curve.samples.size}")
        assertTrue(curve.samples.size >= 50)
        // Der letzte Punkt bleibt erhalten, sonst braeche die Kurve vor dem Ziel ab.
        assertEquals(
            computeStats(straightRide(count = 5_000, stepM = 5.0, stepS = 1)).distanceKm,
            curve.totalKm,
            1e-3,
        )
    }

    @Test
    fun `die Stuetzstellen bleiben auf der Distanzachse aufsteigend`() {
        val curve = speedCurveKmh(straightRide(count = 500, stepM = 8.0, stepS = 1))

        assertNotNull(curve)
        curve.samples.zipWithNext().forEach { (a, b) ->
            assertTrue(b.distanceKm >= a.distanceKm - EPS, "${a.distanceKm} → ${b.distanceKm}")
        }
    }

    // --- heartRateCurve ---

    @Test
    fun `Pulskurve uebernimmt die Werte samt Wertebereich`() {
        val points = straightRide(count = 10, stepM = 10.0, stepS = 1) { index -> 120 + index }

        val curve = heartRateCurve(points)

        assertNotNull(curve)
        assertEquals(10, curve.samples.size)
        assertEquals(120.0, curve.minValue, EPS)
        assertEquals(129.0, curve.maxValue, EPS)
        assertEquals(0.0, curve.samples.first().distanceKm, EPS)
    }

    @Test
    fun `ohne Herzfrequenz gibt es keine Pulskurve`() {
        assertNull(heartRateCurve(straightRide(count = 20, stepM = 10.0, stepS = 1)))
    }

    @Test
    fun `ein einzelner Pulswert reicht fuer keine Kurve`() {
        val points = straightRide(count = 20, stepM = 10.0, stepS = 1) { index ->
            if (index == 3) 130 else null
        }

        assertNull(heartRateCurve(points))
    }

    @Test
    fun `Punkte ohne Herzfrequenz zaehlen weiter zur Distanz`() {
        val points = straightRide(count = 21, stepM = 10.0, stepS = 1) { index ->
            if (index % 10 == 0) 140 else null
        }

        val curve = heartRateCurve(points)

        assertNotNull(curve)
        assertEquals(3, curve.samples.size)
        assertEquals(0.0, curve.samples[0].distanceKm, 1e-6)
        assertEquals(0.1, curve.samples[1].distanceKm, 1e-3)
        assertEquals(0.2, curve.samples[2].distanceKm, 1e-3)
    }

    @Test
    fun `Pulskurve ohne Streckenlaenge entfaellt`() {
        val standing = (0 until 10).map { index ->
            TrackPoint(lat = 48.1, lon = 11.5, time = 1_000L + index * 1000, hr = 60 + index)
        }

        assertNull(heartRateCurve(standing))
    }
}
