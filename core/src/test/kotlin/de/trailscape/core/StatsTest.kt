package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/stats.dart`.
 *
 * Direkt aus `test/stats_test.dart` uebernommen — gleiche Faelle, gleiche
 * Erwartungswerte, damit das Verhalten nachweislich deckungsgleich bleibt.
 */
class StatsTest {
    private companion object {
        const val EPS = 1e-9
    }

    // --- haversineM ---

    @Test
    fun `Berlin nach Potsdam liegt bei rund 25 km`() {
        val berlin = TrackPoint(lat = 52.5163, lon = 13.3777)
        val potsdam = TrackPoint(lat = 52.3989, lon = 13.0657)

        val distanceM = haversineM(berlin, potsdam)

        assertTrue(
            kotlin.math.abs(distanceM - 24846) < 500,
            "erwartet nahe 24846 m, war $distanceM",
        )
    }

    @Test
    fun `Distanz zu sich selbst ist 0`() {
        val p = TrackPoint(lat = 48.1, lon = 11.5)
        assertEquals(0.0, haversineM(p, p), EPS)
    }

    // --- computeStats – Randfaelle ---

    @Test
    fun `leere Liste liefert Nullwerte`() {
        val stats = computeStats(emptyList())

        assertEquals(0.0, stats.distanceKm, EPS)
        assertNull(stats.durationS)
        assertNull(stats.movingTimeS)
        assertNull(stats.avgSpeedKmh)
        assertEquals(0.0, stats.ascentM, EPS)
        assertEquals(0.0, stats.descentM, EPS)
    }

    @Test
    fun `Liste mit einem Punkt liefert Nullwerte`() {
        val stats = computeStats(listOf(TrackPoint(lat = 48.1, lon = 11.5, ele = 500.0, time = 1000L)))

        assertEquals(0.0, stats.distanceKm, EPS)
        assertNull(stats.durationS)
        assertNull(stats.movingTimeS)
        assertNull(stats.avgSpeedKmh)
        assertEquals(0.0, stats.ascentM, EPS)
        assertEquals(0.0, stats.descentM, EPS)
    }

    // --- computeStats – Distanz/Dauer/Geschwindigkeit ---

    // 9 Punkte entlang des Aequators, je 0.0001 Grad Longitude (~11.12 m) und
    // 10 s Zeitabstand -> konstante Geschwindigkeit von ca. 4 km/h.
    private fun buildMovingTrack(): List<TrackPoint> {
        val step = 0.0001
        val lon0 = 13.0
        return (0 until 9).map { i ->
            TrackPoint(lat = 0.0, lon = lon0 + i * step, time = (i * 10000).toLong())
        }
    }

    @Test
    fun `Distanz ist die Haversine-Summe der Segmente`() {
        val points = buildMovingTrack()
        val stats = computeStats(points)

        var expectedM = 0.0
        for (i in 1 until points.size) {
            expectedM += haversineM(points[i - 1], points[i])
        }

        assertEquals(expectedM / 1000, stats.distanceKm, 1e-9)
    }

    @Test
    fun `durationS aus erstem letztem Zeitstempel`() {
        val stats = computeStats(buildMovingTrack())
        assertEquals(80, stats.durationS)
    }

    @Test
    fun `movingTimeS zaehlt nur Segmente ueber 1 kmh`() {
        val stats = computeStats(buildMovingTrack())
        // Alle Segmente liegen bei ca. 4 km/h -> die komplette Dauer zaehlt.
        assertEquals(80, stats.movingTimeS)
    }

    @Test
    fun `avgSpeedKmh gleich Distanz durch movingTime wenn movingTime vorhanden ist`() {
        val stats = computeStats(buildMovingTrack())
        val expectedSpeed = stats.distanceKm / (stats.movingTimeS!! / 3600.0)
        assertEquals(expectedSpeed, stats.avgSpeedKmh!!, 1e-9)
        assertTrue(kotlin.math.abs(stats.avgSpeedKmh - 4.0) < 0.1)
    }

    @Test
    fun `Stillstand fliesst in durationS aber nicht in movingTimeS ein`() {
        val points = listOf(
            TrackPoint(lat = 0.0, lon = 13.0, time = 0L),
            // Punkt bleibt 100 s lang an derselben Stelle stehen.
            TrackPoint(lat = 0.0, lon = 13.0, time = 100000L),
            // Danach 10 s Bewegung mit klar erkennbarem Tempo.
            TrackPoint(lat = 0.0, lon = 13.0011, time = 110000L),
        )

        val stats = computeStats(points)

        assertEquals(110, stats.durationS)
        // Nur das letzte 10-Sekunden-Segment zaehlt als Bewegung.
        assertEquals(10, stats.movingTimeS)
    }

    @Test
    fun `avgSpeedKmh faellt auf durationS zurueck wenn keine Zeiten fuer Segmente vorliegen`() {
        val points = listOf(
            TrackPoint(lat = 0.0, lon = 13.0, time = 0L),
            TrackPoint(lat = 0.0, lon = 13.001), // keine Zeit -> kein Segment-dt
            TrackPoint(lat = 0.0, lon = 13.002, time = 3600000L), // 1 h spaeter
        )

        val stats = computeStats(points)

        assertNull(stats.movingTimeS)
        assertEquals(3600, stats.durationS)
        assertEquals(stats.distanceKm, stats.avgSpeedKmh!!, 1e-9)
    }

    @Test
    fun `ohne jegliche Zeitstempel bleiben Dauer Tempo null`() {
        val points = listOf(
            TrackPoint(lat = 0.0, lon = 13.0),
            TrackPoint(lat = 0.0, lon = 13.001),
        )

        val stats = computeStats(points)

        assertNull(stats.durationS)
        assertNull(stats.movingTimeS)
        assertNull(stats.avgSpeedKmh)
    }

    // --- computeStats – Hoehenmeter-Hysterese ---

    @Test
    fun `reines GPS-Zittern zaehlt nicht als Anstieg Abstieg`() {
        val eles = listOf(100.0, 100.8, 99.3, 100.5, 99.8, 100.2)
        val points = eles.map { TrackPoint(lat = 0.0, lon = 13.0, ele = it) }

        val stats = computeStats(points)

        assertEquals(0.0, stats.ascentM, EPS)
        assertEquals(0.0, stats.descentM, EPS)
    }

    @Test
    fun `echte Anstiege Abstiege ab 3 m werden trotz Rauschen erfasst`() {
        val eles = listOf(
            100.0, // Referenz
            100.5, // Rauschen, < 3 m -> ignoriert
            99.5, // Rauschen, < 3 m -> ignoriert
            100.2, // Rauschen, < 3 m -> ignoriert
            110.0, // echter Anstieg von 10 m -> neue Referenz 110
            110.5, // Rauschen -> ignoriert
            109.7, // Rauschen -> ignoriert
            100.0, // echter Abstieg von 10 m -> neue Referenz 100
        )
        val points = eles.map { TrackPoint(lat = 0.0, lon = 13.0, ele = it) }

        val stats = computeStats(points)

        assertEquals(10.0, stats.ascentM, 1e-9)
        assertEquals(10.0, stats.descentM, 1e-9)
    }

    @Test
    fun `Punkte ohne Hoehe werden fuer die Hoehenberechnung ignoriert`() {
        val points = listOf(
            TrackPoint(lat = 0.0, lon = 13.0, ele = 100.0),
            TrackPoint(lat = 0.0, lon = 13.0), // keine Hoehe
            TrackPoint(lat = 0.0, lon = 13.0, ele = 120.0),
        )

        val stats = computeStats(points)

        assertEquals(20.0, stats.ascentM, 1e-9)
        assertEquals(0.0, stats.descentM, EPS)
    }

    @Test
    fun `weniger als zwei Punkte mit Hoehe liefern 0 0`() {
        val points = listOf(
            TrackPoint(lat = 0.0, lon = 13.0, ele = 100.0),
            TrackPoint(lat = 0.0, lon = 13.001),
        )

        val stats = computeStats(points)

        assertEquals(0.0, stats.ascentM, EPS)
        assertEquals(0.0, stats.descentM, EPS)
    }

    // --- formatDuration ---

    @Test
    fun `formatDuration null wird zu Gedankenstrich`() {
        assertEquals("–", formatDuration(null))
    }

    @Test
    fun `formatDuration unter einer Stunde als M SS`() {
        assertEquals("42:05", formatDuration(42 * 60 + 5))
    }

    @Test
    fun `formatDuration ab einer Stunde als H MM SS`() {
        assertEquals("1:42:05", formatDuration(3600 + 42 * 60 + 5))
    }

    @Test
    fun `formatDuration 0 Sekunden`() {
        assertEquals("0:00", formatDuration(0))
    }

    @Test
    fun `formatDuration mehrstellige Stunden`() {
        assertEquals("12:05:09", formatDuration(12 * 3600 + 5 * 60 + 9))
    }

    // --- formatKm ---

    @Test
    fun `formatKm eine Nachkommastelle`() {
        assertEquals("42.3", formatKm(42.34))
    }

    @Test
    fun `formatKm rundet korrekt auf`() {
        assertEquals("42.4", formatKm(42.36))
    }

    @Test
    fun `formatKm ganze Zahl bekommt Punkt Null`() {
        assertEquals("0.0", formatKm(0.0))
    }
}
