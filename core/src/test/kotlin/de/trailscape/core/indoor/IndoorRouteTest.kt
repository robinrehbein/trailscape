package de.trailscape.core.indoor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Tests fuer `IndoorRoute.kt`: `sampleAt`, `surfaceAt`, `lengthM`, `effectiveWidthM`. */
class IndoorRouteTest {

    private val asphalt = SurfaceSection(0.0, 15.0, SurfaceKind.ASPHALT, highway = "residential")
    private val gravel = SurfaceSection(15.0, 20.0, SurfaceKind.GRAVEL, highway = "track", widthM = 2.2)

    /** Drei Punkte nach Norden, 10 m Abstand, Hoehe 0 → 1 → 3 (10 %, dann 20 %). */
    private fun route(isLoop: Boolean = false) = IndoorRoute(
        id = "rtest",
        name = "Test",
        points = listOf(
            IndoorRoutePoint(0.0, 51.0, 13.0, 0.0),
            IndoorRoutePoint(10.0, 51.0001, 13.0, 1.0),
            IndoorRoutePoint(20.0, 51.0002, 13.0, 3.0),
        ),
        surfaces = listOf(asphalt, gravel),
        isLoop = isLoop,
        source = IndoorRouteSource.BROUTER_WAY_TAGS,
    )

    @Test
    fun `lengthM ist der letzte Laufmeter`() {
        assertEquals(20.0, route().lengthM)
    }

    @Test
    fun `lineare Interpolation und Steigung des Stuecks`() {
        val s = route().sampleAt(5.0)
        assertEquals(5.0, s.distM)
        assertEquals(0.5, s.eleM, 1e-12)
        assertEquals(51.00005, s.lat, 1e-12)
        assertEquals(13.0, s.lon, 1e-12)
        assertEquals(10.0, s.gradePct, 1e-9)
        val t = route().sampleAt(15.0)
        assertEquals(2.0, t.eleM, 1e-12)
        assertEquals(20.0, t.gradePct, 1e-9)
    }

    @Test
    fun `auf einem Punkt gilt das Stueck danach`() {
        val s = route().sampleAt(10.0)
        assertEquals(1.0, s.eleM, 1e-12)
        assertEquals(20.0, s.gradePct, 1e-9)
        assertEquals(10.0, route().sampleAt(0.0).gradePct, 1e-9)
    }

    @Test
    fun `offene Strecke wird an den Enden geklemmt`() {
        val r = route()
        val before = r.sampleAt(-5.0)
        assertEquals(0.0, before.distM)
        assertEquals(0.0, before.eleM)
        val after = r.sampleAt(25.0)
        assertEquals(20.0, after.distM)
        assertEquals(3.0, after.eleM, 1e-12)
        assertEquals(20.0, after.gradePct, 1e-9)
        assertEquals(51.0002, after.lat, 1e-12)
        assertEquals(20.0, r.sampleAt(1e12).distM)
    }

    @Test
    fun `nicht endliche Stelle gilt als Start`() {
        for (r in listOf(route(), route(isLoop = true))) {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                val s = r.sampleAt(bad)
                assertEquals(0.0, s.distM, "$bad")
                assertEquals(0.0, s.eleM, "$bad")
            }
        }
    }

    @Test
    fun `Runde faltet die Stelle in die Runde`() {
        val r = route(isLoop = true)
        assertEquals(5.0, r.sampleAt(25.0).distM, 1e-12)
        assertEquals(0.5, r.sampleAt(25.0).eleM, 1e-12)
        assertEquals(15.0, r.sampleAt(-5.0).distM, 1e-12)
        assertEquals(0.0, r.sampleAt(40.0).distM, 1e-12)
        assertEquals(0.0, r.sampleAt(20.0).distM, 1e-12)
        assertEquals(7.0, r.sampleAt(20.0 * 1000 + 7.0).distM, 1e-6)
        assertEquals(SurfaceKind.GRAVEL, r.sampleAt(37.0).surface.kind)
        assertEquals(SurfaceKind.ASPHALT, r.sampleAt(41.0).surface.kind)
    }

    @Test
    fun `Richtung in Grad, 0 Nord, 90 Ost`() {
        fun dir(dLat: Double, dLon: Double): Double = IndoorRoute(
            id = "r",
            name = "",
            points = listOf(
                IndoorRoutePoint(0.0, 51.0, 13.0, 0.0),
                IndoorRoutePoint(10.0, 51.0 + dLat, 13.0 + dLon, 0.0),
            ),
            surfaces = listOf(SurfaceSection(0.0, 10.0, SurfaceKind.UNKNOWN)),
            isLoop = false,
            source = IndoorRouteSource.NONE,
        ).sampleAt(5.0).headingDeg
        assertEquals(0.0, dir(0.0001, 0.0), 1e-9)
        assertEquals(90.0, dir(0.0, 0.0001), 1e-9)
        assertEquals(180.0, dir(-0.0001, 0.0), 1e-9)
        assertEquals(270.0, dir(0.0, -0.0001), 1e-9)
        // Nordost: Laengengrade sind bei 51° N enger — Laengenmassstab cos(lat).
        val ne = dir(0.0001, 0.0001 / Math.cos(Math.toRadians(51.0)))
        assertEquals(45.0, ne, 0.01)
    }

    @Test
    fun `surfaceAt an den Grenzen`() {
        val r = route()
        assertSame(asphalt, r.surfaceAt(0.0))
        assertSame(asphalt, r.surfaceAt(14.999))
        assertSame(gravel, r.surfaceAt(15.0), "fromM einschliesslich")
        assertSame(gravel, r.surfaceAt(20.0), "Ende gehoert zum letzten")
        assertSame(asphalt, r.surfaceAt(-1.0), "davor gilt der erste")
        assertSame(gravel, r.surfaceAt(100.0), "danach gilt der letzte")
        assertSame(gravel, r.sampleAt(15.0).surface)
    }

    @Test
    fun `surfaceAt mit einem einzigen Abschnitt`() {
        val single = route().copy(surfaces = listOf(SurfaceSection(0.0, 20.0, SurfaceKind.UNKNOWN)))
        for (d in listOf(-1.0, 0.0, 10.0, 20.0, 30.0)) assertEquals(SurfaceKind.UNKNOWN, single.surfaceAt(d).kind)
    }

    @Test
    fun `surfaceAt mit vielen Abschnitten findet jeden`() {
        val sections = (0 until 100).map { SurfaceSection(it * 2.0, it * 2.0 + 2.0, SurfaceKind.entries[it % SurfaceKind.entries.size]) }
        val r = route().copy(surfaces = sections)
        for (i in sections.indices) {
            assertSame(sections[i], r.surfaceAt(i * 2.0))
            assertSame(sections[i], r.surfaceAt(i * 2.0 + 1.999))
        }
    }

    @Test
    fun `effektive Breite`() {
        assertEquals(SurfaceKind.ASPHALT.defaultWidthM, asphalt.effectiveWidthM)
        assertEquals(2.2, gravel.effectiveWidthM)
    }

    @Test
    fun `gleiche Laufmeter zweier Punkte ergeben Steigung 0 statt NaN`() {
        // Kein gueltiger Bau (distM nicht streng steigend), aber sampleAt soll nicht NaN liefern.
        val r = IndoorRoute(
            id = "r",
            name = "",
            points = listOf(IndoorRoutePoint(0.0, 51.0, 13.0, 0.0), IndoorRoutePoint(0.0, 51.0, 13.0, 5.0)),
            surfaces = listOf(SurfaceSection(0.0, 0.0, SurfaceKind.UNKNOWN)),
            isLoop = false,
            source = IndoorRouteSource.NONE,
        )
        val s = r.sampleAt(0.0)
        assertEquals(0.0, s.gradePct)
        assertEquals(0.0, s.eleM)
    }

    @Test
    fun `gebaute Strecke laesst sich ueberall abtasten`() {
        val pts = (0..100).map { de.trailscape.core.TrackPoint(lat = 51.0 + it * 0.0001, lon = 13.0, ele = it * 0.3) }
        val r = buildIndoorRouteImpl("x", pts, null, IndoorRouteSource.NONE)!!
        var d = -50.0
        while (d < r.lengthM + 50) {
            val s = r.sampleAt(d)
            assertTrue(s.eleM.isFinite() && s.gradePct in IndoorRoute.MIN_GRADE_PCT..IndoorRoute.MAX_GRADE_PCT)
            assertEquals(0.0, s.headingDeg, 1e-6)
            d += 3.7
        }
    }
}
