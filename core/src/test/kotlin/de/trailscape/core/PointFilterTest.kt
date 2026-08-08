package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung der Filterlogik aus `lib/recorder.dart`.
 *
 * Das Dart-Original hat fuer `_handlePosition` keine eigenen Tests (die Logik
 * haengt dort am `Geolocator`-Stream); die Faelle hier bilden deshalb die
 * einzelnen Zweige des Originals nach, jeweils mit dem dort verankerten
 * Schwellwert.
 */
class PointFilterTest {

    private companion object {
        const val EPS = 1e-9
        const val T0 = 1_700_000_000_000L
    }

    private fun sample(
        lat: Double = 52.0,
        lon: Double = 13.0,
        altitudeM: Double = 100.0,
        accuracyM: Double = 5.0,
        speedMps: Double = 0.0,
        timeMs: Long = T0,
    ) = LocationSample(
        lat = lat,
        lon = lon,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
        speedMps = speedMps,
        timeMs = timeMs,
    )

    private fun accepted(result: PointFilterResult): TrackPoint {
        val hit = assertIs<PointFilterResult.Accepted>(result)
        return hit.point
    }

    private fun rejection(result: PointFilterResult): PointRejection {
        val miss = assertIs<PointFilterResult.Rejected>(result)
        return miss.reason
    }

    // --- Genauigkeitsfilter ---

    @Test
    fun `Punkt mit genau 50 m Genauigkeit wird noch aufgenommen`() {
        val filter = PointFilter()

        val point = accepted(filter.offer(sample(accuracyM = 50.0)))

        assertEquals(52.0, point.lat, EPS)
        assertEquals(1, filter.acceptedCount)
    }

    @Test
    fun `Punkt oberhalb von 50 m Genauigkeit wird verworfen`() {
        val filter = PointFilter()

        assertEquals(
            PointRejection.LOW_ACCURACY,
            rejection(filter.offer(sample(accuracyM = 50.1))),
        )
        assertEquals(0, filter.acceptedCount)
        assertNull(filter.lastPoint)
    }

    @Test
    fun `fehlende Genauigkeit kommt als 0 an und passiert den Filter`() {
        val filter = PointFilter()

        // Entspricht Position.fromMap: fehlende Felder werden zu 0.0, nicht null.
        val point = accepted(filter.offer(LocationSample(lat = 1.0, lon = 2.0, timeMs = T0)))

        assertEquals(1.0, point.lat, EPS)
        assertEquals(0.0, point.ele!!, EPS)
    }

    // --- Pause ---

    @Test
    fun `waehrend der Pause wird kein Punkt aufgenommen`() {
        val filter = PointFilter()
        accepted(filter.offer(sample(lat = 52.0)))

        filter.paused = true
        assertEquals(
            PointRejection.PAUSED,
            rejection(filter.offer(sample(lat = 52.1))),
        )
        assertEquals(1, filter.acceptedCount)

        filter.paused = false
        accepted(filter.offer(sample(lat = 52.1)))
        assertEquals(2, filter.acceptedCount)
    }

    @Test
    fun `Genauigkeitsfilter greift vor der Pausenpruefung`() {
        val filter = PointFilter()
        filter.paused = true

        assertEquals(
            PointRejection.LOW_ACCURACY,
            rejection(filter.offer(sample(accuracyM = 99.0))),
        )
    }

    // --- Duplikate ---

    @Test
    fun `identische Position wird verworfen`() {
        val filter = PointFilter()
        accepted(filter.offer(sample(lat = 52.0, lon = 13.0, timeMs = T0)))

        assertEquals(
            PointRejection.DUPLICATE,
            rejection(filter.offer(sample(lat = 52.0, lon = 13.0, timeMs = T0 + 5000))),
        )
        assertEquals(1, filter.acceptedCount)
    }

    @Test
    fun `minimal verschobene Position wird aufgenommen`() {
        val filter = PointFilter()
        accepted(filter.offer(sample(lat = 52.0, lon = 13.0)))

        // Der Mindestabstand von 3 m ist Sache des Standort-Providers, nicht
        // dieses Filters: was hier ankommt, wird auch aufgenommen.
        accepted(filter.offer(sample(lat = 52.000001, lon = 13.0, timeMs = T0 + 5000)))
        assertEquals(2, filter.acceptedCount)
    }

    @Test
    fun `Duplikat wird nur gegen den letzten Punkt geprueft`() {
        val filter = PointFilter()
        accepted(filter.offer(sample(lat = 52.0)))
        accepted(filter.offer(sample(lat = 52.1)))

        // Rueckkehr auf eine frueher besuchte Position ist erlaubt.
        accepted(filter.offer(sample(lat = 52.0)))
        assertEquals(3, filter.acceptedCount)
    }

    // --- Punktinhalt ---

    @Test
    fun `aufgenommener Punkt uebernimmt Hoehe und Zeitstempel`() {
        val filter = PointFilter()

        val point = accepted(
            filter.offer(sample(altitudeM = 512.25, timeMs = T0 + 1234)),
        )

        assertEquals(512.25, point.ele!!, EPS)
        assertEquals(T0 + 1234, point.time)
        assertNull(point.hr)
    }

    @Test
    fun `nicht endliche Hoehe wird zu null`() {
        val filter = PointFilter()

        val point = accepted(filter.offer(sample(altitudeM = Double.NaN)))

        assertNull(point.ele)
    }

    // --- Geschwindigkeit ---

    @Test
    fun `Geraetegeschwindigkeit wird in km pro h umgerechnet`() {
        val filter = PointFilter()
        filter.offer(sample(speedMps = 5.0))

        assertEquals(18.0, filter.currentSpeedKmh!!, EPS)
    }

    @Test
    fun `Geschwindigkeit wird auch bei verworfenen Punkten mitgefuehrt`() {
        val filter = PointFilter()

        // Zu ungenau -> verworfen, die Geschwindigkeit zaehlt trotzdem.
        rejection(filter.offer(sample(accuracyM = 200.0, speedMps = 10.0)))

        assertEquals(36.0, filter.currentSpeedKmh!!, EPS)
        assertEquals(0, filter.acceptedCount)
    }

    @Test
    fun `ohne jede Messung ist die Geschwindigkeit unbekannt`() {
        assertNull(PointFilter().currentSpeedKmh)
    }

    @Test
    fun `Fallback rechnet die Geschwindigkeit aus den letzten beiden Punkten`() {
        val filter = PointFilter()
        // speedMps < 0 => das Geraet liefert keine Geschwindigkeit.
        filter.offer(sample(lat = 52.0, lon = 13.0, speedMps = -1.0, timeMs = T0))
        filter.offer(sample(lat = 52.0, lon = 13.001, speedMps = -1.0, timeMs = T0 + 5000))

        val speed = assertNotNull(filter.currentSpeedKmh)
        val expected = haversineM(
            TrackPoint(lat = 52.0, lon = 13.0),
            TrackPoint(lat = 52.0, lon = 13.001),
        ) / 1000 / (5.0 / 3600)
        assertEquals(expected, speed, EPS)
        assertTrue(speed > 0)
    }

    @Test
    fun `Fallback greift nicht ueber Luecken ab 10 Sekunden`() {
        val filter = PointFilter()
        filter.offer(sample(lat = 52.0, lon = 13.0, speedMps = -1.0, timeMs = T0))
        filter.offer(sample(lat = 52.0, lon = 13.001, speedMps = -1.0, timeMs = T0 + 10_000))

        assertNull(filter.currentSpeedKmh)
    }

    @Test
    fun `Fallback braucht zwei Punkte`() {
        val filter = PointFilter()
        filter.offer(sample(speedMps = -1.0))

        assertNull(filter.currentSpeedKmh)
    }

    // --- restore / reset ---

    @Test
    fun `restore setzt Punktezahl Duplikatpruefung und Fallback fort`() {
        val filter = PointFilter()
        val history = listOf(
            TrackPoint(lat = 52.0, lon = 13.0, ele = 10.0, time = T0),
            TrackPoint(lat = 52.0, lon = 13.001, ele = 11.0, time = T0 + 5000),
        )

        filter.restore(history)

        assertEquals(2, filter.acceptedCount)
        assertEquals(history.last(), filter.lastPoint)
        // Geschwindigkeit nach Neustart: nur noch ueber den Fallback.
        assertNotNull(filter.currentSpeedKmh)
        assertEquals(
            PointRejection.DUPLICATE,
            rejection(filter.offer(sample(lat = 52.0, lon = 13.001, speedMps = -1.0))),
        )
    }

    @Test
    fun `restore mit leerer Liste verhaelt sich wie ein frischer Filter`() {
        val filter = PointFilter()
        filter.offer(sample())

        filter.restore(emptyList())

        assertEquals(0, filter.acceptedCount)
        assertNull(filter.lastPoint)
        assertNull(filter.currentSpeedKmh)
    }

    @Test
    fun `reset loescht Punkte Pause und Geschwindigkeit`() {
        val filter = PointFilter()
        filter.offer(sample(speedMps = 4.0))
        filter.paused = true

        filter.reset()

        assertEquals(0, filter.acceptedCount)
        assertNull(filter.lastPoint)
        assertNull(filter.currentSpeedKmh)
        assertTrue(!filter.paused)
    }

    // --- Zusammenspiel mit computeStats ---

    @Test
    fun `aufgenommene Punkte ergeben plausible Tour-Statistik`() {
        val filter = PointFilter()
        val points = mutableListOf<TrackPoint>()
        repeat(5) { i ->
            val result = filter.offer(
                sample(
                    lat = 52.0 + i * 0.001,
                    lon = 13.0,
                    altitudeM = 100.0 + i * 10,
                    timeMs = T0 + i * 5000L,
                ),
            )
            (result as? PointFilterResult.Accepted)?.let { points.add(it.point) }
        }

        assertEquals(5, points.size)
        val stats = computeStats(points)
        assertTrue(stats.distanceKm > 0.4, "erwartet > 0,4 km, war ${stats.distanceKm}")
        assertEquals(20, stats.durationS)
        assertEquals(40.0, stats.ascentM, EPS)
    }
}
