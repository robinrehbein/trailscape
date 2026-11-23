package de.trailscape.core.indoor

import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrackPoint
import de.trailscape.core.haversineM
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests fuer `IndoorRouteBuilder.kt`: Zusicherungen aus `IndoorRoute.kt`, Belag, Runde, Kennung, Stuetzpunkte. */
class IndoorRouteBuilderTest {

    // -----------------------------------------------------------------------
    // Hilfen: Punkte in Metern um einen Ursprung bei 51° N / 13° O
    // -----------------------------------------------------------------------

    private val lat0 = 51.0
    private val lon0 = 13.0
    private val mPerDegLat = 6371000.0 * Math.PI / 180

    /** Punkt [eastM] Meter oestlich und [northM] Meter noerdlich des Ursprungs. */
    private fun pt(eastM: Double, northM: Double = 0.0, ele: Double? = null) = TrackPoint(
        lat = lat0 + northM / mPerDegLat,
        lon = lon0 + eastM / (mPerDegLat * cos(Math.toRadians(lat0))),
        ele = ele,
    )

    /** Gerade nach Osten, Punkte alle [stepM] Meter bis [lengthM]; Hoehe aus [ele]. */
    private fun line(lengthM: Double, stepM: Double = 10.0, ele: (Double) -> Double? = { null }): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        var x = 0.0
        while (x < lengthM) {
            out.add(pt(x, ele = ele(x)))
            x += stepM
        }
        out.add(pt(lengthM, ele = ele(lengthM)))
        return out
    }

    private fun build(
        points: List<TrackPoint>,
        sections: List<RouteWaySection>? = null,
        source: IndoorRouteSource = IndoorRouteSource.BROUTER_WAY_TAGS,
        name: String = "Test",
    ) = buildIndoorRouteImpl(name, points, sections, source)

    private fun grades(r: IndoorRoute): List<Double> = (1 until r.points.size).map { i ->
        (r.points[i].eleM - r.points[i - 1].eleM) / (r.points[i].distM - r.points[i - 1].distM) * 100.0
    }

    /** Prueft jede Zusicherung aus dem KDoc von `IndoorRoute.kt`. */
    private fun assertContract(r: IndoorRoute) {
        assertTrue(r.points.size >= 2, "mindestens zwei Punkte")
        assertEquals(0.0, r.points[0].distM, "erster Punkt bei 0 m")
        for (i in 1 until r.points.size) {
            val gap = r.points[i].distM - r.points[i - 1].distM
            assertTrue(gap > 0, "distM streng steigend bei $i")
            assertTrue(gap in 1.0..15.0, "Abstand $gap bei $i ausserhalb 1..15 m")
        }
        for (p in r.points) {
            assertTrue(p.eleM.isFinite() && p.lat.isFinite() && p.lon.isFinite(), "endlich: $p")
            assertTrue(p.lon in -180.0..180.0 && p.lat in -90.0..90.0, "WGS84-Bereich: $p")
        }
        // Benachbarte Punkte liegen auch geometrisch auseinander (sonst Richtung undefiniert).
        for (i in 1 until r.points.size) {
            val a = TrackPoint(lat = r.points[i - 1].lat, lon = r.points[i - 1].lon)
            val b = TrackPoint(lat = r.points[i].lat, lon = r.points[i].lon)
            assertTrue(haversineM(a, b) > 1e-3, "Punkte ${i - 1}/$i an derselben Stelle")
        }
        for ((i, g) in grades(r).withIndex()) {
            assertTrue(g >= IndoorRoute.MIN_GRADE_PCT && g <= IndoorRoute.MAX_GRADE_PCT, "Steigung $g bei $i")
        }
        assertTrue(r.surfaces.isNotEmpty())
        assertEquals(0.0, r.surfaces.first().fromM, "Belag beginnt bei 0")
        assertEquals(r.lengthM, r.surfaces.last().toM, "Belag endet exakt bei lengthM")
        for (s in r.surfaces) assertTrue(s.toM > s.fromM, "leerer Abschnitt $s")
        for (i in 1 until r.surfaces.size) {
            assertEquals(r.surfaces[i - 1].toM, r.surfaces[i].fromM, "lueckenlos bei $i")
        }
        val start = TrackPoint(lat = r.points.first().lat, lon = r.points.first().lon)
        val end = TrackPoint(lat = r.points.last().lat, lon = r.points.last().lon)
        assertEquals(haversineM(start, end) <= IndoorRoute.LOOP_CLOSE_M, r.isLoop, "isLoop passt zum Abstand")
        assertTrue(r.id.matches(Regex("r[0-9a-f]{16}")), "id-Format: ${r.id}")
    }

    // -----------------------------------------------------------------------
    // Grundfaelle und Zusicherungen
    // -----------------------------------------------------------------------

    @Test
    fun `gerade flache Strecke ohne Hoehen liegt auf 0 m`() {
        val r = assertNotNull(build(line(1000.0)))
        assertContract(r)
        assertEquals(1000.0, r.lengthM, 0.5)
        assertTrue(r.points.all { it.eleM == 0.0 })
        assertFalse(r.isLoop)
        assertEquals("Test", r.name)
        // Schritt etwa 10 m, nie mehr.
        val gaps = (1 until r.points.size).map { r.points[it].distM - r.points[it - 1].distM }
        assertTrue(gaps.all { it <= 10.0 + 1e-9 && it > 5.0 }, "Schritte $gaps")
        // Gleiche Schritte.
        assertTrue(gaps.max() - gaps.min() < 1e-6)
    }

    @Test
    fun `Start und Ziel bleiben exakt erhalten, Laufmeter sind die Bogenlaenge`() {
        val input = line(750.0, stepM = 37.0)
        val r = assertNotNull(build(input))
        assertEquals(input.first().lat, r.points.first().lat)
        assertEquals(input.first().lon, r.points.first().lon)
        assertEquals(input.last().lat, r.points.last().lat)
        assertEquals(input.last().lon, r.points.last().lon)
        val arc = (1 until input.size).sumOf { haversineM(input[it - 1], input[it]) }
        assertEquals(arc, r.lengthM, 1e-6)
    }

    @Test
    fun `grob abgetastete Spur wird fein neu abgetastet`() {
        // Nur zwei Punkte, 3 km auseinander.
        val r = assertNotNull(build(listOf(pt(0.0, ele = 100.0), pt(3000.0, ele = 130.0))))
        assertContract(r)
        assertTrue(r.points.size >= 300)
        // 1 % gleichmaessig — im Inneren unveraendert durch die Glaettung.
        // 1 % gleichmaessig — die Glaettung erhaelt das ueberall, auch am Rand
        // (symmetrisch schrumpfendes Fenster).
        assertTrue(grades(r).all { abs(it - 1.0) < 1e-3 }, "Steigung ${grades(r)}")
    }

    @Test
    fun `Ecke einer L-Strecke wird abgetastet ohne die Zusicherungen zu verletzen`() {
        val pts = (0..40).map { pt(it * 10.0) } + (1..40).map { pt(400.0, it * 10.0) }
        val r = assertNotNull(build(pts))
        assertContract(r)
        assertEquals(800.0, r.lengthM, 0.5)
    }

    @Test
    fun `zu kurz oder zu wenige Punkte ergibt null`() {
        assertNull(build(emptyList()))
        assertNull(build(listOf(pt(0.0))))
        assertNull(build(line(199.0)))
        // Alle Punkte praktisch an derselben Stelle.
        assertNull(build((0..50).map { pt(it * 0.001) }))
        // Nur ungueltige Punkte.
        assertNull(build(listOf(TrackPoint(Double.NaN, 13.0), TrackPoint(51.0, Double.POSITIVE_INFINITY))))
        // Knapp ueber 200 m ist fahrbar.
        val r = assertNotNull(build(line(201.0)))
        assertContract(r)
    }

    @Test
    fun `genau 200 m ist fahrbar, Schritt 10 m`() {
        val r = assertNotNull(build(listOf(pt(0.0), pt(200.5))))
        assertContract(r)
        assertTrue(r.points.size in 21..22)
    }

    @Test
    fun `ungueltige Punkte werden verworfen`() {
        val clean = line(600.0)
        val dirty = clean.flatMapIndexed { i, p ->
            when (i % 7) {
                3 -> listOf(p, TrackPoint(Double.NaN, 13.0), TrackPoint(95.0, 13.0), TrackPoint(51.0, 200.0))
                else -> listOf(p)
            }
        }
        val a = assertNotNull(build(clean))
        val b = assertNotNull(build(dirty))
        assertEquals(a, b)
    }

    @Test
    fun `Duplikate werden zusammengefasst`() {
        val clean = line(600.0)
        // Jeder Punkt doppelt und mit 0,2 m Zittern.
        val dup = clean.flatMap { listOf(it, it, it.copy(lat = it.lat + 0.2 / mPerDegLat)) }
        val a = assertNotNull(build(clean))
        val b = assertNotNull(build(dup))
        assertEquals(a.points.size, b.points.size)
        assertEquals(a.lengthM, b.lengthM, 1e-6)
    }

    @Test
    fun `Duplikat liefert die Hoehe nach, wenn der behaltene Punkt keine hat`() {
        val pts = listOf(pt(0.0), pt(0.1, ele = 50.0), pt(500.0, ele = 50.0))
        val r = assertNotNull(build(pts))
        assertTrue(r.points.all { abs(it.eleM - 50.0) < 1e-9 })
    }

    // -----------------------------------------------------------------------
    // Hoehen
    // -----------------------------------------------------------------------

    @Test
    fun `Hoehenluecken werden linear ueber die Laufmeter gefuellt`() {
        // 0..1000 m: nur bei 0 m (100) und 1000 m (150) eine Hoehe, dazwischen
        // nichts bzw. NaN — ergibt 5 % gleichmaessig.
        val pts = line(1000.0) { x ->
            when {
                x == 0.0 -> 100.0
                x == 1000.0 -> 150.0
                x.toInt() % 30 == 0 -> Double.NaN
                else -> null
            }
        }
        val r = assertNotNull(build(pts))
        assertContract(r)
        val g = grades(r)
        assertTrue(g.all { abs(it - 5.0) < 1e-3 }, "Steigung $g")
        assertTrue(r.points.all { it.eleM in 100.0..150.0 })
    }

    @Test
    fun `am Rand gilt der naechste bekannte Wert`() {
        // Hoehe nur zwischen 300 und 400 m bekannt (konstant 80 m).
        val pts = line(1000.0) { x -> if (x in 300.0..400.0) 80.0 else null }
        val r = assertNotNull(build(pts))
        assertTrue(r.points.all { abs(it.eleM - 80.0) < 1e-9 })
    }

    @Test
    fun `eine einzige bekannte Hoehe macht die Strecke flach auf dieser Hoehe`() {
        val pts = line(500.0) { x -> if (x == 250.0) 333.0 else null }
        val r = assertNotNull(build(pts))
        assertTrue(r.points.all { abs(it.eleM - 333.0) < 1e-9 })
    }

    @Test
    fun `GPS-Rauschen wird geglaettet`() {
        // ±3 m im 10-m-Takt: roh 60 % Steigung je Stueck.
        val pts = line(1000.0) { x -> 200.0 + if ((x / 10).toInt() % 2 == 0) 3.0 else -3.0 }
        val r = assertNotNull(build(pts))
        assertContract(r)
        // Im Inneren; an Start und Ziel schrumpft das Fenster auf 0 (die
        // Endpunkte bleiben roh), dort greift nur die Steigungsgrenze.
        val g = grades(r)
        val inner = (1 until r.points.size).filter { r.points[it - 1].distM >= 25.0 && r.points[it].distM <= r.lengthM - 25.0 }
        val maxAbs = inner.maxOf { abs(g[it - 1]) }
        // Roh 60 %; das Fenster von rund 60 m (7 Punkte) laesst bei diesem
        // schlimmsten Fall (Rauschen genau im Abtasttakt) 6/7 m je 10 m stehen.
        assertTrue(maxAbs < 10.0, "nach Glaettung noch $maxAbs %")
        assertTrue(r.points.all { it.eleM in 196.0..204.0 })
    }

    @Test
    fun `Steilwand wird auf 25 Prozent begrenzt`() {
        // 300 m flach auf 0 m, dann Sprung auf 100 m, dann 500 m flach.
        val pts = line(800.0) { x -> if (x < 300.0) 0.0 else 100.0 }
        val r = assertNotNull(build(pts))
        assertContract(r)
        val g = grades(r)
        assertTrue(g.max() > 24.99, "Grenze wird ausgeschoepft: ${g.max()}")
        assertTrue(g.max() <= IndoorRoute.MAX_GRADE_PCT)
        // Die Wand wird zur Rampe: am Ende wird die volle Hoehe erreicht.
        assertEquals(100.0, r.points.last().eleM, 1e-6)
    }

    @Test
    fun `steiler Abhang wird auf minus 25 Prozent begrenzt`() {
        val pts = line(800.0) { x -> if (x < 300.0) 500.0 else 300.0 }
        val r = assertNotNull(build(pts))
        assertContract(r)
        val g = grades(r)
        assertTrue(g.min() < -24.99)
        assertTrue(g.min() >= IndoorRoute.MIN_GRADE_PCT)
    }

    @Test
    fun `dauerhaft 40 Prozent wird auf 25 Prozent begrenzt`() {
        val pts = line(400.0) { x -> x * 0.4 }
        val r = assertNotNull(build(pts))
        assertContract(r)
        // Nach dem Glaettungsrand: ueberall genau an der Grenze.
        val g = grades(r)
        assertTrue(g.subList(5, g.size - 5).all { it > 24.99 })
    }

    @Test
    fun `clampGrades haelt die Grenze auch mit krummen Schritten`() {
        val dists = DoubleArray(1000) { it * 7.123456789 }
        val eles = DoubleArray(1000) { if (it % 2 == 0) 0.0 else 1e6 }
        for (cyclic in listOf(false, true)) {
            val input = eles.copyOf().also { if (cyclic) it[it.size - 1] = it[0] }
            val out = clampGrades(input, dists, cyclic)
            for (i in 1 until out.size) {
                val g = (out[i] - out[i - 1]) / (dists[i] - dists[i - 1]) * 100
                assertTrue(g in IndoorRoute.MIN_GRADE_PCT..IndoorRoute.MAX_GRADE_PCT, "Steigung $g bei $i (zyklisch $cyclic)")
            }
            // Nie ueber der Eingabe.
            for (i in out.indices) assertTrue(out[i] <= input[i] + 1e-9)
            if (cyclic) assertEquals(out.first(), out.last())
        }
    }

    @Test
    fun `smoothElevations laesst eine Gerade ueberall unveraendert, auch bei ungleichen Schritten`() {
        val dists = DoubleArray(50) { it * 10.0 + if (it % 3 == 1) 4.0 else 0.0 }
        val eles = DoubleArray(50) { dists[it] * 0.05 + 7.0 }
        val s = smoothElevations(eles, dists, cyclic = false)
        for (i in eles.indices) assertEquals(eles[i], s[i], 1e-9)
    }

    @Test
    fun `smoothElevations zyklisch gleicht Start und Ziel an`() {
        val dists = DoubleArray(101) { it * 10.0 }
        val eles = DoubleArray(101) { if (it == 100) 20.0 else 10.0 } // Ziel 10 m hoeher als Start
        val s = smoothElevations(eles, dists, cyclic = true)
        assertEquals(s.first(), s.last())
        assertTrue(s.all { it in 10.0..20.0 })
    }

    // -----------------------------------------------------------------------
    // Belag
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Wegabschnitte ein Abschnitt UNKNOWN mit Quelle NONE`() {
        val variants: List<List<RouteWaySection>?> = listOf(
            null,
            emptyList(),
            listOf(RouteWaySection(0.0, "highway=track surface=gravel")),
            listOf(RouteWaySection(500.0, "surface=gravel"), RouteWaySection(-1.0, "surface=asphalt")),
            listOf(RouteWaySection(Double.NaN, "surface=gravel")),
            listOf(RouteWaySection(Double.POSITIVE_INFINITY, "surface=gravel")),
        )
        for (v in variants) {
            val r = assertNotNull(build(line(1000.0), v, IndoorRouteSource.BROUTER_WAY_TAGS))
            assertContract(r)
            assertEquals(IndoorRouteSource.NONE, r.source, "Abschnitte $v")
            assertEquals(listOf(SurfaceSection(0.0, r.lengthM, SurfaceKind.UNKNOWN)), r.surfaces, "Abschnitte $v")
        }
    }

    @Test
    fun `Wegabschnitte werden anteilig auf die Linienlaenge gestreckt`() {
        // Abschnitte summieren sich zu 500 m, die Linie ist 1000 m lang.
        val sections = listOf(
            RouteWaySection(100.0, "highway=tertiary"),
            RouteWaySection(250.0, "highway=track surface=gravel tracktype=grade3"),
            RouteWaySection(150.0, "highway=path"),
        )
        val r = assertNotNull(build(line(1000.0), sections, IndoorRouteSource.BROUTER_WAY_TAGS))
        assertContract(r)
        assertEquals(IndoorRouteSource.BROUTER_WAY_TAGS, r.source)
        val f = r.lengthM / 500.0
        assertEquals(3, r.surfaces.size)
        assertEquals(SurfaceKind.ASPHALT, r.surfaces[0].kind)
        assertEquals("tertiary", r.surfaces[0].highway)
        assertEquals(100.0 * f, r.surfaces[0].toM, 1e-9)
        assertEquals(SurfaceKind.GRAVEL, r.surfaces[1].kind)
        assertEquals("track", r.surfaces[1].highway)
        assertEquals(350.0 * f, r.surfaces[1].toM, 1e-9)
        assertEquals(SurfaceKind.DIRT, r.surfaces[2].kind)
        assertEquals("path", r.surfaces[2].highway)
        assertEquals(r.lengthM, r.surfaces[2].toM)
        assertTrue(r.surfaces.all { it.widthM == null })
    }

    @Test
    fun `Abgleich mit unplausiblem Streckfaktor verwirft den Belag`() {
        val line = line(1000.0)
        val lengthM = assertNotNull(build(line)).lengthM
        // Faktor = Linie / Summe der Abschnitte; Grenzen knapp innerhalb.
        val cases = listOf(
            1.2 to true, 1.25 * (1 - 1e-9) to true, 1.3 to false,
            0.85 to true, 0.8 * (1 + 1e-9) to true, 0.75 to false, 2.0 to false,
        )
        for ((factor, kept) in cases) {
            val r = assertNotNull(
                build(line, listOf(RouteWaySection(lengthM / factor, "surface=gravel")), IndoorRouteSource.BROUTER_REMATCH),
            )
            assertContract(r)
            if (kept) {
                assertEquals(IndoorRouteSource.BROUTER_REMATCH, r.source, "Faktor $factor")
                assertEquals(SurfaceKind.GRAVEL, r.surfaces.single().kind, "Faktor $factor")
            } else {
                assertEquals(IndoorRouteSource.NONE, r.source, "Faktor $factor")
                assertEquals(SurfaceKind.UNKNOWN, r.surfaces.single().kind, "Faktor $factor")
            }
        }
        // Fuer die eigenen Wegabschnitte der geplanten Route gilt die Grenze nicht.
        val planned = assertNotNull(
            build(line, listOf(RouteWaySection(lengthM / 2, "surface=gravel")), IndoorRouteSource.BROUTER_WAY_TAGS),
        )
        assertEquals(IndoorRouteSource.BROUTER_WAY_TAGS, planned.source)
    }

    @Test
    fun `gleichartige Nachbarn werden zusammengefasst, verschiedene Wegart nicht`() {
        val sections = listOf(
            RouteWaySection(200.0, "highway=track surface=gravel"),
            RouteWaySection(100.0, "highway=track tracktype=grade3 bicycle=yes"), // GRAVEL, track → zusammen
            RouteWaySection(100.0, "highway=service surface=gravel"), // GRAVEL, aber service → eigener
            RouteWaySection(100.0, "highway=residential"), // ASPHALT
            RouteWaySection(100.0, "highway=residential surface=asphalt"), // ASPHALT, residential → zusammen
            RouteWaySection(100.0, "highway=residential"),
            RouteWaySection(300.0, "surface=dirt"), // kein highway
        )
        val r = assertNotNull(build(line(1000.0), sections))
        assertContract(r)
        assertEquals(
            listOf(
                SurfaceKind.GRAVEL to "track",
                SurfaceKind.GRAVEL to "service",
                SurfaceKind.ASPHALT to "residential",
                SurfaceKind.DIRT to null,
            ),
            r.surfaces.map { it.kind to it.highway },
        )
        assertEquals(300.0, r.surfaces[0].toM, 0.01)
        assertEquals(400.0, r.surfaces[1].toM, 0.01)
        assertEquals(700.0, r.surfaces[2].toM, 0.01)
    }

    @Test
    fun `winzige Abschnitte entfallen ohne Luecke`() {
        val sections = listOf(
            RouteWaySection(0.004, "highway=path"), // erster winzig → entfaellt, naechster beginnt bei 0
            RouteWaySection(500.0, "highway=track surface=gravel"),
            RouteWaySection(0.005, "surface=asphalt"), // mittendrin winzig
            RouteWaySection(500.0, "highway=track surface=gravel"), // verschmilzt mit dem vorvorigen
            RouteWaySection(0.003, "surface=sand"), // am Ende winzig
        )
        val r = assertNotNull(build(line(1000.0), sections))
        assertContract(r)
        assertEquals(1, r.surfaces.size)
        assertEquals(SurfaceKind.GRAVEL, r.surfaces.single().kind)
        assertEquals(0.0, r.surfaces.single().fromM)
        assertEquals(r.lengthM, r.surfaces.single().toM)
    }

    @Test
    fun `Abschnitte mit Laenge 0 werden uebergangen`() {
        val sections = listOf(
            RouteWaySection(400.0, "surface=asphalt"),
            RouteWaySection(0.0, "surface=sand"),
            RouteWaySection(600.0, "surface=grass"),
        )
        val r = assertNotNull(build(line(1000.0), sections))
        assertContract(r)
        assertEquals(listOf(SurfaceKind.ASPHALT, SurfaceKind.GRASS), r.surfaces.map { it.kind })
    }

    @Test
    fun `Quelle NONE mit Abschnitten wird trotzdem ausgewertet`() {
        val r = assertNotNull(
            build(line(1000.0), listOf(RouteWaySection(1000.0, "surface=sand")), IndoorRouteSource.NONE),
        )
        assertEquals(IndoorRouteSource.NONE, r.source)
        assertEquals(SurfaceKind.SAND, r.surfaces.single().kind)
    }

    @Test
    fun `Quelle wird bei vorhandenen Abschnitten durchgereicht`() {
        for (src in IndoorRouteSource.entries) {
            val r = assertNotNull(build(line(500.0), listOf(RouteWaySection(500.0, "surface=wood")), src))
            assertEquals(src, r.source)
        }
    }

    @Test
    fun `sehr viele Abschnitte bleiben lueckenlos`() {
        val kinds = listOf("surface=asphalt", "surface=gravel", "surface=sand", "highway=path")
        val sections = (0 until 997).map { RouteWaySection(1.0 + it % 5, kinds[it % kinds.size]) }
        val r = assertNotNull(build(line(2345.0), sections))
        assertContract(r)
        assertTrue(r.surfaces.size > 900)
    }

    // -----------------------------------------------------------------------
    // Ecken und Wenden
    // -----------------------------------------------------------------------

    @Test
    fun `Wende eines Stichwegs wird als Punkt eingerastet`() {
        // Hin und auf demselben Weg zurueck: ohne Einrasten laege die Wende
        // zwischen zwei Abtastpunkten an derselben Stelle (z. B. 505 m).
        for (tipM in listOf(505.0, 503.3, 500.0, 497.0)) {
            val steps = (tipM / 10).toInt()
            val pts = (0..steps).map { pt(it * 10.0) } + pt(tipM) +
                (1..steps).map { pt(tipM - it * 10.0) } + pt(0.0)
            val r = assertNotNull(build(pts), "Spitze $tipM")
            assertContract(r)
            val tipIdx = r.points.indexOfFirst { abs(it.distM - tipM) < 1e-6 }
            assertTrue(tipIdx > 0, "kein Punkt an der Wende ($tipM)")
            assertTrue(haversineM(TrackPoint(r.points[tipIdx].lat, r.points[tipIdx].lon), pt(tipM)) < 1e-3)
            assertEquals(90.0, r.sampleAt(tipM - 3).headingDeg, 0.01)
            assertEquals(270.0, r.sampleAt(tipM + 3).headingDeg, 0.01)
            assertEquals(2 * tipM, r.lengthM, 0.01)
            assertTrue(r.isLoop)
        }
    }

    @Test
    fun `rechtwinklige Ecke wird eingerastet, Schritte bleiben in 1 bis 15 m`() {
        val pts = listOf(pt(0.0), pt(203.0), pt(203.0, 157.0))
        val r = assertNotNull(build(pts))
        assertContract(r)
        assertTrue(r.points.any { abs(it.distM - 203.0) < 0.01 })
    }

    @Test
    fun `dicht beieinanderliegende Ecken werden nicht zu Mini-Schritten`() {
        // Treppe mit Ecken alle 1,5 m zwischen zwei Geraden.
        val stairs = (0..20).map { k -> pt(100.0 + ((k + 1) / 2) * 1.5, (k / 2) * 1.5) }
        val pts = listOf(pt(0.0)) + stairs + listOf(pt(400.0, 15.0))
        val r = assertNotNull(build(pts))
        assertContract(r)
    }

    // -----------------------------------------------------------------------
    // Datumsgrenze
    // -----------------------------------------------------------------------

    @Test
    fun `Strecke ueber die Datumsgrenze`() {
        fun lonAt(xM: Double): Double {
            var l = 180.0 - 0.005 + xM / mPerDegLat // am Aequator
            if (l > 180.0) l -= 360.0
            return l
        }
        val pts = (0..100).map { TrackPoint(lat = 0.0, lon = lonAt(it * 10.0), ele = 5.0) }
        val r = assertNotNull(build(pts))
        assertContract(r)
        assertEquals(1000.0, r.lengthM, 0.5)
        assertTrue(r.points.any { it.lon > 179.9 } && r.points.any { it.lon < -179.9 })
        for (i in 1 until r.points.size) {
            val d = haversineM(TrackPoint(r.points[i - 1].lat, r.points[i - 1].lon), TrackPoint(r.points[i].lat, r.points[i].lon))
            assertTrue(d < 15.0, "Sprung $d m bei $i")
        }
    }

    // -----------------------------------------------------------------------
    // Runde
    // -----------------------------------------------------------------------

    @Test
    fun `Runde hat keinen Hoehensprung an der Naht und volle Steigung durch den Start`() {
        // Quadrat 4 × 300 m, Hoehe als Sinus ueber die Runde: durch den Start
        // mit 8 % bergauf, Start- und Zielhoehe gleich.
        val l = 1200.0
        val amp = 0.08 * l / (2 * Math.PI)
        fun ele(c: Double) = 300.0 + amp * kotlin.math.sin(2 * Math.PI * c / l)
        val sq = (0..30).map { pt(it * 10.0, ele = ele(it * 10.0)) } +
            (1..30).map { pt(300.0, it * 10.0, ele = ele(300.0 + it * 10.0)) } +
            (1..30).map { pt(300.0 - it * 10.0, 300.0, ele = ele(600.0 + it * 10.0)) } +
            (1..30).map { pt(0.0, 300.0 - it * 10.0, ele = ele(900.0 + it * 10.0)) }
        val r = assertNotNull(build(sq))
        assertContract(r)
        assertTrue(r.isLoop)
        assertEquals(r.points.first().eleM, r.points.last().eleM, "Start und Ziel gleich hoch")
        val g = grades(r)
        assertEquals(8.0, g.first(), 0.3)
        assertEquals(8.0, g.last(), 0.3)
        // Ueber die Naht gelesen laeuft die Runde glatt weiter.
        assertEquals(r.sampleAt(r.lengthM - 0.01).eleM, r.sampleAt(r.lengthM + 0.01).eleM, 0.01)
    }

    @Test
    fun `Runde mit unterschiedlicher Start- und Zielhoehe wird angeglichen`() {
        // Start 100 m, Ziel 112 m (Hoehendaten driften), sonst flach.
        val raw = outAndBack(30.0)
        val pts = raw.mapIndexed { i, t -> t.copy(ele = if (i == raw.size - 1) 112.0 else 100.0) }
        val r = assertNotNull(build(pts))
        assertContract(r)
        assertTrue(r.isLoop)
        assertEquals(r.points.first().eleM, r.points.last().eleM)
    }

    @Test
    fun `Runde mit Steilwand haelt die Grenze auch ueber die Naht`() {
        val pts = outAndBack(20.0).mapIndexed { i, t -> t.copy(ele = if (i in 40..60) 150.0 else 100.0) }
        val r = assertNotNull(build(pts))
        assertContract(r)
        assertTrue(r.isLoop)
        assertEquals(r.points.first().eleM, r.points.last().eleM)
    }


    /** Hin und zurueck mit [gapM] Meter Versatz nach Norden: Start–Ziel-Abstand = gapM. */
    private fun outAndBack(gapM: Double): List<TrackPoint> =
        (0..50).map { pt(it * 10.0) } + (0..50).map { pt(500.0 - it * 10.0, gapM) }

    @Test
    fun `Runde bis 60 m Start-Ziel-Abstand`() {
        val loop = assertNotNull(build(outAndBack(58.0)))
        assertContract(loop)
        assertTrue(loop.isLoop)
        val open = assertNotNull(build(outAndBack(62.0)))
        assertContract(open)
        assertFalse(open.isLoop)
    }

    @Test
    fun `geschlossenes Quadrat ist eine Runde`() {
        val sq = (0..30).map { pt(it * 10.0) } + (1..30).map { pt(300.0, it * 10.0) } +
            (1..30).map { pt(300.0 - it * 10.0, 300.0) } + (1..30).map { pt(0.0, 300.0 - it * 10.0) }
        val r = assertNotNull(build(sq))
        assertContract(r)
        assertTrue(r.isLoop)
        assertEquals(1200.0, r.lengthM, 0.5)
    }

    // -----------------------------------------------------------------------
    // Kennung
    // -----------------------------------------------------------------------

    @Test
    fun `id ist stabil, unabhaengig vom Namen und haengt an der Geometrie`() {
        val a = assertNotNull(build(line(1000.0), name = "Heide"))
        val b = assertNotNull(build(line(1000.0), name = "Andere"))
        val c = assertNotNull(build(line(1000.0), listOf(RouteWaySection(1000.0, "surface=sand"))))
        assertEquals(a.id, b.id)
        assertEquals(a.id, c.id, "Belag geht nicht in die id ein")
        assertEquals("Heide", a.name)
        assertEquals("Andere", b.name)
        val d = assertNotNull(build(line(1001.0)))
        assertNotEquals(a.id, d.id)
        val e = assertNotNull(build(line(1000.0).map { it.copy(lat = it.lat + 0.001) }))
        assertNotEquals(a.id, e.id)
        // Wiederholt gebaut: identisch.
        assertEquals(a, build(line(1000.0), name = "Heide"))
    }

    @Test
    fun `leerer Name ist erlaubt`() {
        assertEquals("", assertNotNull(build(line(300.0), name = "")).name)
    }

    @Test
    fun `Zeitstempel und Sensorwerte beeinflussen nichts`() {
        val plain = line(800.0)
        val rich = plain.mapIndexed { i, p -> p.copy(time = 1_000L * i, hr = 140, power = 200) }
        assertEquals(build(plain), build(rich))
    }

    // -----------------------------------------------------------------------
    // Zusicherungen ueber viele Formen
    // -----------------------------------------------------------------------

    @Test
    fun `Zusicherungen halten fuer eine kurvige huegelige Strecke mit Luecken`() {
        val rnd = java.util.Random(42)
        val pts = ArrayList<TrackPoint>()
        var x = 0.0
        var y = 0.0
        var heading = 0.0
        var ele = 300.0
        for (i in 0 until 2000) {
            heading += (rnd.nextDouble() - 0.5) * 0.6
            val step = 2.0 + rnd.nextDouble() * 30.0
            x += step * cos(heading)
            y += step * kotlin.math.sin(heading)
            ele += (rnd.nextDouble() - 0.5) * 8.0
            pts.add(pt(x, y, ele = if (rnd.nextInt(5) == 0) null else ele))
            if (rnd.nextInt(10) == 0) pts.add(pts.last()) // Duplikat
        }
        val r = assertNotNull(build(pts, listOf(RouteWaySection(1234.0, "surface=gravel"), RouteWaySection(4321.0, "highway=residential"))))
        assertContract(r)
        assertEquals(2, r.surfaces.size)
    }

    // -----------------------------------------------------------------------
    // Stuetzpunkte fuer den Belagsabgleich
    // -----------------------------------------------------------------------

    @Test
    fun `Abgleich zu kurz oder leer ergibt keine Stuetzpunkte`() {
        assertTrue(surfaceRematchWaypointsImpl(emptyList()).isEmpty())
        assertTrue(surfaceRematchWaypointsImpl(listOf(pt(0.0))).isEmpty())
        assertTrue(surfaceRematchWaypointsImpl(line(150.0)).isEmpty())
        assertTrue(surfaceRematchWaypointsImpl((0..20).map { pt(0.0) }).isEmpty())
    }

    @Test
    fun `gerade Spur bekommt etwa alle 300 m einen Stuetzpunkt auf echten Spurpunkten`() {
        val track = line(3000.0)
        val wps = surfaceRematchWaypointsImpl(track)
        assertEquals(11, wps.size)
        assertEquals(track.first().lat, wps.first().lat)
        assertEquals(track.first().lon, wps.first().lon)
        assertEquals(track.last().lat, wps.last().lat)
        assertEquals(track.last().lon, wps.last().lon)
        for (w in wps) {
            assertTrue(track.any { it.lat == w.lat && it.lon == w.lon }, "Stuetzpunkt nicht auf der Spur: $w")
            assertNull(w.name)
        }
        for (i in 1 until wps.size) {
            val d = haversineM(TrackPoint(wps[i - 1].lat, wps[i - 1].lon), TrackPoint(wps[i].lat, wps[i].lon))
            assertEquals(300.0, d, 10.0)
        }
    }

    @Test
    fun `kurze Spur hat Start, einen Zwischenpunkt und Ziel`() {
        val wps = surfaceRematchWaypointsImpl(line(250.0))
        assertEquals(3, wps.size)
        assertTrue(haversineM(TrackPoint(wps[1].lat, wps[1].lon), pt(125.0)) <= 5.0 + 1e-6)
    }

    @Test
    fun `kurze geschlossene Runde besteht nicht nur aus Start gleich Ziel`() {
        val sq = (0..6).map { pt(it * 10.0) } + (1..6).map { pt(60.0, it * 10.0) } +
            (1..6).map { pt(60.0 - it * 10.0, 60.0) } + (1..6).map { pt(0.0, 60.0 - it * 10.0) }
        val wps = surfaceRematchWaypointsImpl(sq)
        assertTrue(wps.size >= 3, "$wps")
        assertEquals(wps.first(), wps.last())
    }

    @Test
    fun `lange Spur haelt die Obergrenze und vergroessert den Abstand`() {
        // 100 km gerade, Punkt alle 50 m.
        val track = line(100_000.0, stepM = 50.0)
        val wps = surfaceRematchWaypointsImpl(track)
        assertEquals(MAX_REMATCH_WAYPOINTS, wps.size)
        val gaps = (1 until wps.size).map {
            haversineM(TrackPoint(wps[it - 1].lat, wps[it - 1].lon), TrackPoint(wps[it].lat, wps[it].lon))
        }
        // Regelpunkte im Abstand ~1010 m, die Haelfte der Luecken danach halbiert.
        val regular = 100_000.0 / (MAX_REMATCH_REGULAR - 1)
        assertTrue(gaps.all { it <= regular + 50.0 && it >= regular / 2 - 50.0 }, "Abstaende $gaps")
    }

    @Test
    fun `Wendepunkt eines Stichwegs bekommt einen Stuetzpunkt`() {
        // 400 m nach Osten und auf demselben Weg zurueck (1 m Versatz) —
        // die Regelpunkte bei rund 267 und 534 m Spur liegen beide bei rund
        // 267 m Ost, der Router wuerde den Stich abkuerzen.
        val track = (0..40).map { pt(it * 10.0) } + (1..40).map { pt(400.0 - it * 10.0, 1.0) }
        val wps = surfaceRematchWaypointsImpl(track)
        assertEquals(5, wps.size, "Start, zwei Regelpunkte, Wendepunkt, Ziel: $wps")
        val tip = pt(400.0)
        assertTrue(
            wps.any { haversineM(TrackPoint(it.lat, it.lon), tip) < 15.0 },
            "kein Stuetzpunkt am Wendepunkt: $wps",
        )
        // Reihenfolge der Spur bleibt: der Wendepunkt liegt zwischen Start und Ziel.
        assertEquals(track.first().lat, wps.first().lat)
        assertEquals(track.last().lat, wps.last().lat)
    }

    @Test
    fun `rechtwinklige Ecke bekommt einen Stuetzpunkt, sanfte Kurve nicht`() {
        val corner = (0..14).map { pt(it * 10.0) } + (1..14).map { pt(140.0, it * 10.0) }
        val wps = surfaceRematchWaypointsImpl(corner)
        assertEquals(3, wps.size, "Start, Ecke, Ziel: $wps")
        assertTrue(haversineM(TrackPoint(wps[1].lat, wps[1].lon), pt(140.0)) < 1.0)

        // Grosser Bogen: 90° auf rund 597 m verteilt — keine scharfe Stelle.
        val arc = (0..60).map { k ->
            val a = Math.toRadians(90.0 * k / 60)
            pt(380.0 * kotlin.math.sin(a), 380.0 * (1 - cos(a)))
        }
        val arcWps = surfaceRematchWaypointsImpl(arc)
        assertEquals(3, arcWps.size, "Start, ein Regelpunkt, Ziel: $arcWps")
    }

    @Test
    fun `Kurvenpunkte respektieren die Obergrenze`() {
        // 60 km Zickzack mit Ecken alle 100 m.
        val track = ArrayList<TrackPoint>()
        for (k in 0 until 600) {
            for (j in 0 until 10) {
                track.add(pt(k * 100.0 + j * 10.0, if (k % 2 == 0) j * 10.0 else 100.0 - j * 10.0))
            }
        }
        val wps = surfaceRematchWaypointsImpl(track)
        assertTrue(wps.size <= MAX_REMATCH_WAYPOINTS)
        assertEquals(MAX_REMATCH_WAYPOINTS, wps.size)
    }

    @Test
    fun `lange Hin-und-zurueck-Spur behaelt die Wende und hat keine Duplikate`() {
        // 30 km hin, auf demselben Weg 30 km zurueck, Punkt alle 50 m.
        val out = (0..600).map { pt(it * 50.0) }
        val back = (1..600).map { pt(30_000.0 - it * 50.0) }
        val wps = surfaceRematchWaypointsImpl(out + back)
        assertTrue(wps.size <= MAX_REMATCH_WAYPOINTS)
        assertTrue(
            wps.any { haversineM(TrackPoint(it.lat, it.lon), pt(30_000.0)) < 1.0 },
            "Wende fehlt",
        )
        for (i in 1 until wps.size) {
            val d = haversineM(TrackPoint(wps[i - 1].lat, wps[i - 1].lon), TrackPoint(wps[i].lat, wps[i].lon))
            assertTrue(d >= REMATCH_MERGE_M, "Stuetzpunkte ${i - 1}/$i nur $d m auseinander")
        }
    }

    @Test
    fun `Kurven bekommen auch auf langer Spur Platz`() {
        // 80 km gerade mit zehn Stichen (je 100 m hin und zurueck) — die
        // Regelpunkte allein haetten frueher alle 150 Plaetze belegt.
        val track = ArrayList<TrackPoint>()
        val tips = ArrayList<TrackPoint>()
        for (step in 0..1600) {
            val x = step * 50.0
            track.add(pt(x))
            if (step % 160 == 80) {
                for (k in 1..10) track.add(pt(x, k * 10.0))
                tips.add(pt(x, 100.0))
                for (k in 9 downTo 1) track.add(pt(x + 0.6, k * 10.0))
            }
        }
        val wps = surfaceRematchWaypointsImpl(track)
        assertTrue(wps.size <= MAX_REMATCH_WAYPOINTS)
        assertEquals(10, tips.size)
        for (tip in tips) {
            assertTrue(wps.any { haversineM(TrackPoint(it.lat, it.lon), tip) < 1.0 }, "Stich-Ende fehlt: $tip")
        }
    }

    @Test
    fun `Stuetzpunkte folgen der Spur in Fahrtrichtung`() {
        val track = (0..60).map { pt(it * 10.0) } + (1..60).map { pt(600.0, it * 10.0) } +
            (1..60).map { pt(600.0 - it * 10.0, 600.0) }
        val wps = surfaceRematchWaypointsImpl(track)
        val idx = wps.map { w -> track.indexOfFirst { it.lat == w.lat && it.lon == w.lon } }
        assertTrue(idx.all { it >= 0 })
        assertEquals(idx.sorted(), idx)
        assertEquals(idx.distinct(), idx)
        // Beide Ecken sind dabei.
        assertTrue(idx.contains(60) && idx.contains(120), "Ecken fehlen: $idx")
    }

    @Test
    fun `ungueltige Punkte und Duplikate stoeren den Abgleich nicht`() {
        val clean = line(1500.0)
        val dirty = clean.flatMap { listOf(it, it, TrackPoint(Double.NaN, Double.NaN)) }
        assertEquals(surfaceRematchWaypointsImpl(clean), surfaceRematchWaypointsImpl(dirty))
    }
}
