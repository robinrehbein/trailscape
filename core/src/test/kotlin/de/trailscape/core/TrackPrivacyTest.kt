package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests fuer [trimTrackEnds]: Start und Ziel eines geteilten Tour-Bilds ausblenden. */
class TrackPrivacyTest {

    private val r = SHARE_END_RADIUS_M

    /** Breitengrade je Meter nach Norden (Erdradius wie in [haversineM]). */
    private val degPerM = 1.0 / 111_194.9

    /** Gerade Strecke nach Norden, ein Punkt alle [stepM] Meter. */
    private fun north(lengthM: Double, stepM: Double = 50.0, lat0: Double = 52.0, lon: Double = 13.0): List<TrackPoint> {
        val n = (lengthM / stepM).toInt()
        return (0..n).map { TrackPoint(lat = lat0 + it * stepM * degPerM, lon = lon) }
    }

    @Test
    fun `gerade Strecke wird an beiden Enden um den Radius gekuerzt`() {
        val points = north(2000.0)
        val trimmed = trimTrackEnds(points)

        assertEquals(r, haversineM(points.first(), trimmed.first()), 1.0)
        assertEquals(r, haversineM(points.last(), trimmed.last()), 1.0)
        // Die Punkte dazwischen sind die unveraenderten Originale.
        val inner = trimmed.subList(1, trimmed.size - 1)
        assertTrue(inner.isNotEmpty())
        inner.forEach { p ->
            assertTrue(points.any { it === p }, "innerer Punkt $p ist kein Original")
            assertTrue(haversineM(points.first(), p) > r && haversineM(points.last(), p) > r)
        }
        // Reihenfolge und Vollstaendigkeit: alle Originale ausserhalb beider Kreise.
        val expectedInner = points.filter { haversineM(points.first(), it) > r && haversineM(points.last(), it) > r }
        assertEquals(expectedInner, inner)
    }

    @Test
    fun `Rundtour behaelt die Form und kommt Start und Ziel nicht naeher als den Radius`() {
        // Quadrat mit 1 km Kantenlaenge, im Uhrzeigersinn zurueck zum Start.
        val lat0 = 52.0
        val lon0 = 13.0
        val dLat = 1000.0 * degPerM
        val dLon = dLat / kotlin.math.cos(Math.toRadians(lat0))
        val corners = listOf(
            lat0 to lon0,
            lat0 + dLat to lon0,
            lat0 + dLat to lon0 + dLon,
            lat0 to lon0 + dLon,
            lat0 to lon0,
        )
        val points = corners.zipWithNext().flatMap { (a, b) ->
            (0 until 20).map { k ->
                val t = k / 20.0
                TrackPoint(lat = a.first + (b.first - a.first) * t, lon = a.second + (b.second - a.second) * t)
            }
        } + TrackPoint(lat0, lon0)

        val trimmed = trimTrackEnds(points)

        assertTrue(trimmed.size > 10)
        trimmed.forEach { p ->
            assertTrue(haversineM(points.first(), p) >= r - 1.0, "zu nah am Start: $p")
            assertTrue(haversineM(points.last(), p) >= r - 1.0, "zu nah am Ziel: $p")
        }
    }

    @Test
    fun `sehr kurze Tour ergibt keine Spur`() {
        assertEquals(emptyList(), trimTrackEnds(north(500.0)))
    }

    @Test
    fun `Spur die den Kreis nie verlaesst ergibt keine Spur`() {
        // 16 x 200 m hin und her in einem 250-m-Umkreis: 3,2 km Weg, nie weiter als 200 m vom Start.
        val a = TrackPoint(52.0, 13.0)
        val b = TrackPoint(52.0 + 200.0 * degPerM, 13.0)
        val points = (0..16).map { if (it % 2 == 0) a else b }
        assertTrue(points.zipWithNext().sumOf { (p, q) -> haversineM(p, q) } >= 2 * r)

        assertEquals(emptyList(), trimTrackEnds(points))
    }

    @Test
    fun `leere, einzelne und stehende Spuren ergeben keine Spur`() {
        assertEquals(emptyList(), trimTrackEnds(emptyList()))
        assertEquals(emptyList(), trimTrackEnds(listOf(TrackPoint(52.0, 13.0))))
        assertEquals(emptyList(), trimTrackEnds(listOf(TrackPoint(52.0, 13.0), TrackPoint(52.0, 13.0))))
    }

    @Test
    fun `Radius null gibt die Eingabe unveraendert zurueck`() {
        val points = north(400.0)
        assertSame(points, trimTrackEnds(points, 0.0))
        assertSame(points, trimTrackEnds(points, -5.0))
    }

    @Test
    fun `Randpunkt interpoliert Hoehe und Zeit und laesst den Puls weg`() {
        val points = north(2000.0, stepM = 120.0).mapIndexed { k, p ->
            p.copy(ele = 100.0 + k * 10.0, time = 1_000_000L + k * 20_000L, hr = 140)
        }
        val trimmed = trimTrackEnds(points)
        val head = trimmed.first()
        val tail = trimmed.last()

        // Punkte bei 0, 120, 240, 360 m: Der Kopf liegt zwischen Index 2 und 3.
        val ele = assertNotNull(head.ele)
        assertTrue(ele in 120.0..130.0, "ele $ele")
        val time = assertNotNull(head.time)
        assertTrue(time in 1_040_000L..1_060_000L, "time $time")
        assertNull(head.hr)
        assertNull(tail.hr)
        listOf(head, tail).forEach {
            assertTrue(!it.lat.isNaN() && !it.lon.isNaN() && it.ele?.isNaN() != true)
        }

        // Fehlt eine der beiden Zeiten, bleibt die Zeit leer statt geraten.
        val partial = points.mapIndexed { k, p -> if (k == 3) p.copy(time = null) else p }
        assertNull(trimTrackEnds(partial).first().time)
    }

    @Test
    fun `Randpunkt ohne Hoehen bleibt ohne Hoehe`() {
        val trimmed = trimTrackEnds(north(2000.0))
        assertNull(trimmed.first().ele)
        assertNull(trimmed.first().time)
    }

    @Test
    fun `lange erste Strecke wird auf dem Kreis geschnitten`() {
        val start = TrackPoint(52.0, 13.0)
        val far = TrackPoint(52.0 + 1000.0 * degPerM, 13.0)
        val points = listOf(start, far) + north(2000.0, lat0 = far.lat).drop(1)

        val head = trimTrackEnds(points).first()

        assertEquals(r, haversineM(start, head), 1.0)
        assertEquals(13.0, head.lon, 1e-9)
        assertTrue(head.lat > start.lat && head.lat < far.lat)
    }
}
