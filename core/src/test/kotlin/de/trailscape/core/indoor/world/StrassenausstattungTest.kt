package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Straßenausstattung: Markierungsplan, Abziehbilder, Leitpfosten und Schutzplanken. */
class StrassenausstattungTest {
    /** Gerade [a] m, Kreisbogen Radius [r] über [deg] Grad, Gerade [b] m; Höhe aus [height] (Laufmeter → u). */
    private fun track(a: Double, r: Double, deg: Double, b: Double, highway: String = "tertiary", width: Double = 6.5, onHills: Boolean = false, height: (Double) -> Double = { 0.0 }): WorldTrack {
        val step = 5.0
        val arc = r * Math.toRadians(deg)
        val len = a + arc + b
        val count = (len / step).toInt() + 1
        val e = FloatArray(count)
        val n = FloatArray(count)
        val u = FloatArray(count)
        for (i in 0 until count) {
            val d = i * step
            var x: Double
            var y: Double
            when {
                d <= a -> { x = 0.0; y = d }
                d <= a + arc -> {
                    val phi = (d - a) / r
                    x = r - r * cos(phi); y = a + r * sin(phi)
                }
                else -> {
                    val phi = arc / r
                    val ex = r - r * cos(phi)
                    val ey = a + r * sin(phi)
                    x = ex + sin(phi) * (d - a - arc); y = ey + cos(phi) * (d - a - arc)
                }
            }
            e[i] = x.toFloat(); n[i] = y.toFloat()
            u[i] = (if (onHills) PreviewSupport.hills.heightAt(x, y) else height(d)).toFloat()
        }
        val l = (count - 1) * step
        return WorldTrack(l, step, false, e, n, u, listOf(SurfaceSection(0.0, l, SurfaceKind.ASPHALT, highway, width)), IndoorRouteSource.NONE)
    }

    private val meadow = object : LandMap {
        override fun classAt(e: Double, n: Double) = LandClass.MEADOW
    }

    @Test
    fun `Leitlinie auf gerader Landstrasse, Fahrstreifenbegrenzung in enger Kurve, Warnlinie davor`() {
        val line = TrackLine(track(600.0, 60.0, 90.0, 600.0))
        val plan = MarkingPlan.build(line, meadow)
        assertEquals(MarkingPlan.Centre.DASHED, plan.centreAt(150.0))
        assertEquals(MarkingPlan.Centre.SOLID, plan.centreAt(600.0 + 60.0 * PI / 4))
        // Kurz vor der Begrenzung liegt eine Warnlinie.
        val firstSolid = (0 until line.count - 1).first { plan.centre[it] == MarkingPlan.Centre.SOLID }
        assertEquals(MarkingPlan.Centre.WARN, plan.centre[firstSolid - 1])
        // Am Kurvenanfang (90° bei R 60 m) ist die Sicht kurz. Mitten im Bogen reicht sie ueber eine offene
        // Wiese an der Innenseite (Versatz zur Ausfahrgeraden ~19 m < 20 m freier Raum) weit — mit Wald nicht.
        val entry = (600.0 / line.stepM).toInt()
        assertTrue(plan.sightFwd[entry] < 200.0, "Sichtweite am Kurvenanfang ${plan.sightFwd[entry]}")
        val forest = object : LandMap {
            override fun classAt(e: Double, n: Double) = LandClass.FOREST
        }
        val wooded = MarkingPlan.build(line, forest)
        val mid = line.count / 2
        assertTrue(wooded.sightFwd[mid] < 100.0, "Sichtweite im Waldbogen ${wooded.sightFwd[mid]}")
        assertTrue(wooded.sightFwd[mid] < plan.sightFwd[mid], "Wald an der Innenseite verkuerzt die Sicht")
    }

    @Test
    fun `Kuppe begrenzt die Sichtweite`() {
        // 12 m hohe Kuppe ueber ±80 m um Laufmeter 600.
        val line = TrackLine(track(1200.0, 1000.0, 0.0, 0.0, highway = "secondary") { d -> 12.0 * exp(-((d - 600.0) / 80.0).let { it * it }) })
        val plan = MarkingPlan.build(line, meadow)
        assertEquals(MarkingPlan.Centre.SOLID, plan.centreAt(600.0))
        assertEquals(MarkingPlan.Centre.DASHED, plan.centreAt(80.0))
    }

    @Test
    fun `Striche im Raster der RMS`() {
        val out = ArrayList<DoubleArray>()
        MarkingPlan.pattern(0.0, 180.0, 6.0, 18.0, out)
        assertEquals(10, out.size)
        assertTrue(out.all { abs(it[1] - it[0] - 6.0) < 1e-9 })
        assertEquals(36.0, out[2][0], 1e-9)
        // Ein Ausschnitt schneidet die Striche an den Enden, ohne die Phase zu verschieben:
        // Strich 18–24 wird zu 20–24, Strich 36–42 zu 36–40.
        val cut = ArrayList<DoubleArray>()
        MarkingPlan.pattern(20.0, 40.0, 6.0, 18.0, cut)
        assertEquals(2, cut.size)
        assertEquals(20.0, cut[0][0], 1e-9)
        assertEquals(24.0, cut[0][1], 1e-9)
        assertEquals(36.0, cut[1][0], 1e-9)
        assertEquals(40.0, cut[1][1], 1e-9)
        // Reste unter 0,8 m entfallen (Strich 18–24 ab 23,5 m).
        val tiny = ArrayList<DoubleArray>()
        MarkingPlan.pattern(23.5, 30.0, 6.0, 18.0, tiny)
        assertTrue(tiny.isEmpty())
    }

    @Test
    fun `Linien je Wegklasse, Breite und Ort`() {
        fun s(h: String?, w: Double, k: SurfaceKind = SurfaceKind.ASPHALT) = SurfaceSection(0.0, 1.0, k, h, w)
        assertTrue(MarkingPlan.wantsCentre(s("tertiary", 5.5), urban = false))
        assertTrue(!MarkingPlan.wantsCentre(s("unclassified", 5.5), urban = false))
        assertTrue(!MarkingPlan.wantsCentre(s("tertiary", 5.5), urban = true))
        assertTrue(MarkingPlan.wantsCentre(s("secondary", 7.0), urban = true))
        assertTrue(!MarkingPlan.wantsCentre(s("residential", 7.0), urban = true))
        assertTrue(!MarkingPlan.wantsCentre(s("tertiary", 6.5, SurfaceKind.GRAVEL), urban = false))
        assertTrue(MarkingPlan.wantsEdges(s("secondary", 6.5), urban = false))
        assertTrue(!MarkingPlan.wantsEdges(s("secondary", 6.5), urban = true))
        assertTrue(!MarkingPlan.wantsEdges(s("unclassified", 6.0), urban = false))
        assertEquals(6, MarkingPlan.rankOf(s("primary_link", 7.0)))
        assertEquals(0, MarkingPlan.rankOf(s("cycleway", 2.5)))
    }

    // --- Abziehbilder in der Landstrasse der Tests ----------------------------------------

    private fun buildRoute(s: RouteTestScene, spec: TileSpec, q: IndoorQuality = IndoorQuality.MEDIUM): Pair<TileContext, TileOutput> {
        val ctx = s.context(spec, q)
        val out = TileOutput(spec)
        RouteLayer().build(ctx, out)
        return ctx to out
    }

    @Test
    fun `Markierung liegt auf der Fahrbahn, knapp darueber, im Budget und deterministisch`() {
        val scene = RouteTestScene.countryRoad()
        var marking = 0
        for (spec in scene.tiles()) {
            val (ctx, out) = buildRoute(scene, spec)
            assertTrue(out.triangles(WorldNames.NODE_ROUTE) <= ctx.budget.route)
            if (out.triangleCount == 0) continue
            assertEquals(emptyList(), checkTileGlb(out.toGlb().toBytes()), "Kachel ${spec.id}")
            val m = out.mesh(WorldNames.NODE_ROUTE, TileMaterial(RoadDecals.MAT_MARKING))
            marking += m.triangleCount
            for (i in 0 until m.vertexCount) {
                val e = m.x(i) + spec.anchorE
                val n = spec.anchorN - m.z(i)
                val hit = scene.line.nearest(e, n, 20.0)!!
                val hw = scene.line.halfWidthAt(hit.distM)
                assertTrue(hit.distance < hw, "Markierung neben der Fahrbahn bei ${hit.distM} (${hit.distance} m)")
                val road = hit.u - RouteLayer.crownOf(SurfaceKind.ASPHALT) * hit.distance
                val above = m.y(i) + ctx.anchorU - road
                assertTrue(above > 0.008 && above < 0.03, "Markierung $above m ueber der Fahrbahn bei ${hit.distM}")
            }
        }
        assertTrue(marking > 100, "Es gibt Markierung ($marking Dreiecke)")
        val spec = scene.tiles()[1]
        val a = buildRoute(scene, spec).second.toGlb().toBytes()
        val b = buildRoute(RouteTestScene.countryRoad(), spec).second.toGlb().toBytes()
        assertTrue(a.contentEquals(b))
    }

    /** Fläche der Markierung je Seite in einem Laufmeter-Fenster, als Anteil eines durchgehenden 12-cm-Strichs. */
    private fun edgeCoverage(scene: RouteTestScene, from: Double, to: Double, side: Int): Double {
        var area = 0.0
        for (spec in scene.tiles()) {
            val (_, out) = buildRoute(scene, spec)
            val m = out.mesh(WorldNames.NODE_ROUTE, TileMaterial(RoadDecals.MAT_MARKING))
            for (t in 0 until m.triangleCount) {
                val a = m.indices[3 * t]; val b = m.indices[3 * t + 1]; val c = m.indices[3 * t + 2]
                val ce = (m.x(a) + m.x(b) + m.x(c)) / 3.0 + spec.anchorE
                val cn = spec.anchorN - (m.z(a) + m.z(b) + m.z(c)) / 3.0
                val hit = scene.line.nearest(ce, cn, 20.0) ?: continue
                if (hit.distM < from || hit.distM > to) continue
                val hw = scene.line.halfWidthAt(hit.distM)
                if (abs(hit.lateral) < hw - 0.5 || (hit.lateral > 0) != (side > 0)) continue
                area += abs((m.x(b) - m.x(a)).toDouble() * (m.z(c) - m.z(a)) - (m.x(c) - m.x(a)).toDouble() * (m.z(b) - m.z(a))) / 2
            }
        }
        return area / ((to - from) * 0.12)
    }

    @Test
    fun `Randlinie laeuft durch und ist an der Einmuendung unterbrochen`() {
        val scene = RouteTestScene.countryRoad()
        // Freie Strecke: durchgehend (Toleranz fuer Kurven und Abnutzung der Geometrie: keine).
        val open = edgeCoverage(scene, 170.0, 220.0, 1)
        assertTrue(open > 0.9 && open < 1.1, "Randlinie auf freier Strecke deckt $open")
        // Kreisstrasse kreuzt bei 250 m: unterbrochen (etwa die Haelfte).
        val mouth = edgeCoverage(scene, 247.0, 253.0, 1)
        assertTrue(mouth > 0.2 && mouth < 0.8, "Randlinie in der Einmuendung deckt $mouth")
    }

    @Test
    fun `Wartelinie, wo die Strecke auf eine hoeherrangige Strasse trifft, und die Linien enden dort`() {
        val raw = PreviewSupport.curvyTrack(lengthM = 400.0, surfaces = listOf(SurfaceKind.ASPHALT))
        val t = raw.copy(surfaces = listOf(SurfaceSection(0.0, raw.lengthM, SurfaceKind.ASPHALT, "unclassified", 6.0)))
        val roads = listOf(RouteTestScene.trackAsRoad(t, "minor"), RouteTestScene.crossRoad(t, 200.0, "primary", "paved"))
        val scene = RouteTestScene(t, listOf(RouteTestScene.Zone(0.0, LandClass.MEADOW, LandClass.MEADOW)), roads, emptyList())
        var warte = 0
        var centreInside = 0
        val primaryHalf = roadSpec(roads[1])!!.widthM / 2
        for (spec in scene.tiles()) {
            val (_, out) = buildRoute(scene, spec)
            val m = out.mesh(WorldNames.NODE_ROUTE, TileMaterial(RoadDecals.MAT_MARKING))
            for (k in 0 until m.triangleCount) {
                val a = m.indices[3 * k]; val b = m.indices[3 * k + 1]; val c = m.indices[3 * k + 2]
                val ce = (m.x(a) + m.x(b) + m.x(c)) / 3.0 + spec.anchorE
                val cn = spec.anchorN - (m.z(a) + m.z(b) + m.z(c)) / 3.0
                val hit = scene.line.nearest(ce, cn, 20.0) ?: continue
                if (hit.distM in (200.0 - primaryHalf - 1.5)..(200.0 - primaryHalf) && hit.lateral > 0.1) warte++
                if (abs(hit.distM - 200.0) < primaryHalf - 0.5 && abs(hit.lateral) < 0.1) centreInside++
            }
        }
        assertTrue(warte > 0, "Wartelinie vor der Bundesstrasse fehlt")
        assertEquals(0, centreInside, "Mittellinie laeuft ueber die Bundesstrasse")
    }

    // --- Leitpfosten und Schutzplanken -----------------------------------------------------

    @Test
    fun `Leitpfosten stehen in der engen Kurve dichter als auf der Geraden`() {
        val t = track(400.0, 50.0, 120.0, 400.0, onHills = true)
        val scene = RouteTestScene(t, listOf(RouteTestScene.Zone(0.0, LandClass.MEADOW, LandClass.MEADOW)), emptyList(), emptyList())
        val posts = HashSet<Long>()
        for (spec in scene.tiles()) {
            val ctx = scene.context(spec)
            val out = TileOutput(spec)
            PropsLayer().build(ctx, out)
            val m = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL))
            for (i in 0 until m.vertexCount) {
                val e = m.x(i) + spec.anchorE
                val n = spec.anchorN - m.z(i)
                val above = m.y(i) + ctx.anchorU - scene.ground.heightAt(e, n)
                // Oberkante der Leitpfosten (0,98–1,00 m), zwischen 0,6 und 0,9 m neben dem Fahrbahnrand — nicht
                // Weidezaun- oder Plankenpfosten.
                if (above < 0.95 || above > 1.03) continue
                val hit = scene.line.nearest(e, n, 20.0) ?: continue
                val off = hit.distance - scene.line.halfWidthAt(hit.distM)
                if (off < 0.6 || off > 0.9) continue
                // Ein Pfosten je 2 m und Seite (ein Pfosten hat mehrere Eckpunkte).
                posts.add(Math.round(hit.distM / 2.0) * 2 + if (hit.lateral > 0) 1 else 0)
            }
        }
        val arcLen = 50.0 * Math.toRadians(120.0)
        fun count(a: Double, b: Double) = posts.count { (it / 2) * 2.0 in a..b }
        val straight = count(20.0, 380.0) / 360.0
        val curve = count(410.0, 400.0 + arcLen - 10.0) / (arcLen - 20.0)
        // Gerade: 2 Pfosten je 50 m (0,04/m); R 50 m: 2 je 12,5 m (0,16/m).
        assertTrue(straight in 0.02..0.06, "Leitpfosten je Meter auf der Geraden: $straight")
        assertTrue(curve > straight * 2.5, "Leitpfosten je Meter: Gerade $straight, Kurve $curve")
    }

    private class SlopeScene(val real: RealRoadside) {
        // Gerade nach Norden auf u = 0; neben der Fahrbahn faellt das Gelaende 1 : 1 ab (Damm).
        val track = WorldTrack(400.0, 5.0, false, FloatArray(81) { 0f }, FloatArray(81) { it * 5f }, FloatArray(81) { 0f }, listOf(SurfaceSection(0.0, 400.0, SurfaceKind.ASPHALT, "secondary", 6.5)), IndoorRouteSource.NONE)
        val line = TrackLine(track)
        val ground = GroundSurface { e, _ -> if (abs(e) < 4.0) -0.05 else -0.05 - (abs(e) - 4.0) }
        val map = object : MapData {
            override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapBuilding>()
            override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapRoad>()
            override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapArea>()
            override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapWaterway>()
            override fun roadside() = real
        }
        val textures = TextureCache()

        /** Eckpunkte der Kleinteile auf Holmhöhe (Oberkante 0,59–0,65 m über dem Ursprung) und Holmlage. */
        fun railVertices(): Int {
            var count = 0
            val spec = TileSpec.at(0, 0.0, 200.0)
            val ctx = TileContext(spec, PreviewSupport.origin, IndoorQuality.MEDIUM, 0.0, ground, line, map, meadowLand, textures, TileBudget.of(IndoorQuality.MEDIUM, 0))
            val out = TileOutput(spec)
            RoadsideLayer.emit(ctx, out, 3000)
            val m = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL))
            for (i in 0 until m.vertexCount) {
                val y = m.y(i) + ctx.anchorU
                val e = m.x(i) + spec.anchorE
                // Holm 0,55 m neben dem Fahrbahnrand (3,25 m): 3,75–3,80 m; Pfosten und Leitpfosten stehen weiter draussen.
                if (y > 0.55 && y < 0.8 && abs(e) > 3.4 && abs(e) < 3.85) count++
            }
            return count
        }

        companion object {
            val meadowLand = object : LandMap {
                override fun classAt(e: Double, n: Double) = LandClass.MEADOW
            }
        }
    }

    @Test
    fun `Schutzplanke am hohen Damm, echte Planken ersetzen sie`() {
        val rule = SlopeScene(RealRoadside.NONE).railVertices()
        assertTrue(rule > 20, "Regel-Planke am Damm ($rule Eckpunkte)")
        val realRails = object : RealRoadside {
            override fun guardRails(minE: Double, minN: Double, maxE: Double, maxN: Double) =
                listOf(doubleArrayOf(-3.9, -10.0, -3.9, 410.0), doubleArrayOf(3.9, -10.0, 3.9, 410.0))
        }
        assertEquals(0, SlopeScene(realRails).railVertices(), "Regel-Planke trotz echter Planke")
        val authoritative = object : RealRoadside {
            override val authoritative = true
        }
        assertEquals(0, SlopeScene(authoritative).railVertices(), "Regel-Planke trotz vollständiger echter Daten")
    }
}
