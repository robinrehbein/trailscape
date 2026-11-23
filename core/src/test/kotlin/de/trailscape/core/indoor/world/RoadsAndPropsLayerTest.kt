package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadsAndPropsLayerTest {
    private val scene = RouteTestScene.countryRoad()

    private fun build(s: RouteTestScene, spec: TileSpec, layer: TileLayer, quality: IndoorQuality = IndoorQuality.MEDIUM, budget: TileBudget = TileBudget.of(quality, 0)): Pair<TileContext, TileOutput> {
        val ctx = s.context(spec, quality, budget)
        val out = TileOutput(spec)
        layer.build(ctx, out)
        return ctx to out
    }

    private fun sceneWith(roads: List<MapRoad>, houses: List<MapBuilding> = emptyList()): RouteTestScene =
        RouteTestScene(scene.track, listOf(RouteTestScene.Zone(0.0, LandClass.MEADOW, LandClass.MEADOW)), roads, houses)

    @Test
    fun `Strassen, die auf der Strecke liegen, werden ausgelassen`() {
        val onlyTrack = sceneWith(listOf(RouteTestScene.trackAsRoad(scene.track, "secondary")))
        for (spec in onlyTrack.tiles()) {
            assertEquals(0, build(onlyTrack, spec, RoadsLayer()).second.triangles(WorldNames.NODE_ROADS), "Kachel ${spec.id}")
        }
        val withCross = sceneWith(listOf(RouteTestScene.trackAsRoad(scene.track, "secondary"), RouteTestScene.crossRoad(scene.track, 250.0, "tertiary", "paved")))
        val total = withCross.tiles().sumOf { build(withCross, it, RoadsLayer()).second.triangles(WorldNames.NODE_ROADS) }
        // Zweimal 70 m Querstrasse in je 18 Schritten, je Segment 2 Dreiecke Band + 4 Dreiecke Kanten.
        assertEquals(36 * 6, total)
    }

    @Test
    fun `eine Strasse, die von der Strecke abzweigt, wird erst ab der Abzweigung gezeichnet`() {
        // Die Karte fuehrt eine Strasse 200 m auf der Strecke und dann geradeaus weiter, waehrend die Strecke abbiegt.
        val t = scene.track
        val pts = ArrayList<Double>()
        for (i in 0..40 step 2) {
            pts.add(t.e[i].toDouble()); pts.add(t.n[i].toDouble())
        }
        val p = t.poseAt(200.0)
        val h = Math.toRadians(p.headingDeg + 55.0)
        pts.add(p.e + kotlin.math.sin(h) * 60.0); pts.add(p.n + kotlin.math.cos(h) * 60.0)
        val s = sceneWith(listOf(MapRoad(pts.toDoubleArray(), "tertiary", null, "paved")))
        var drawn = 0
        for (spec in s.tiles()) {
            val (_, out) = build(s, spec, RoadsLayer())
            val m = out.mesh(WorldNames.NODE_ROADS, TileMaterial(RoadsLayer.roadMaterialName(SurfaceKind.ASPHALT)))
            for (i in 0 until m.vertexCount) {
                val hit = s.line.nearest(m.x(i) + spec.anchorE, spec.anchorN - m.z(i), 100.0)!!
                assertTrue(hit.distM > 180.0, "Strassenstueck auf der Strecke gezeichnet bei ${hit.distM}")
            }
            drawn += m.triangleCount
        }
        assertTrue(drawn in 20..40, "Abzweig gezeichnet ($drawn Dreiecke)")
    }

    @Test
    fun `kreuzende Strassen laufen unter dem Streckenband hindurch und liegen sonst knapp ueber dem Boden`() {
        var under = 0
        var free = 0
        for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec, RoadsLayer())
            for (kind in listOf(SurfaceKind.ASPHALT, SurfaceKind.GRAVEL, SurfaceKind.DIRT)) {
                val m = out.mesh(WorldNames.NODE_ROADS, TileMaterial(RoadsLayer.roadMaterialName(kind)))
                for (i in 0 until m.vertexCount) {
                    val e = m.x(i) + spec.anchorE
                    val n = spec.anchorN - m.z(i)
                    val u = m.y(i) + ctx.anchorU
                    val hit = scene.line.nearest(e, n, 100.0)
                    val hw = if (hit == null) 0.0 else scene.line.halfWidthAt(hit.distM)
                    if (hit != null && hit.distance < hw) {
                        val surface = hit.u - RouteLayer.crownOf(scene.line.surfaceAt(hit.distM).kind) * hit.distance
                        assertTrue(u < surface - 0.015, "Strasse nicht unter der Fahrbahn: $u gegen $surface")
                        under++
                    } else if (hit == null || hit.distance > hw + 6.0) {
                        val above = u - scene.ground.heightAt(e, n)
                        assertTrue(above in 0.03..0.14, "Strasse $above m ueber dem Boden")
                        free++
                    }
                }
            }
        }
        assertTrue(under > 0 && free > 50, "geprueft: $under unter der Strecke, $free frei")
    }

    @Test
    fun `eine befestigte Strasse liegt ueber einem Wiesenweg, den sie quert`() {
        val s = RouteTestScene.allSurfaces()
        // Laufmeter 405 ist Wiesenweg; die Querstrasse dort ist ein Schotterweg (haerter als Gras).
        var over = 0
        for (spec in s.tiles()) {
            val (ctx, out) = build(s, spec, RoadsLayer())
            val m = out.mesh(WorldNames.NODE_ROADS, TileMaterial(RoadsLayer.roadMaterialName(SurfaceKind.GRAVEL)))
            for (i in 0 until m.vertexCount) {
                val e = m.x(i) + spec.anchorE
                val n = spec.anchorN - m.z(i)
                val hit = s.line.nearest(e, n, 100.0) ?: continue
                if (hit.distM !in 400.0..440.0 || hit.distance > s.line.halfWidthAt(hit.distM) * 0.9) continue
                val surface = hit.u - RouteLayer.crownOf(SurfaceKind.GRASS) * hit.distance
                assertTrue(m.y(i) + ctx.anchorU > surface + 0.005, "Schotterweg nicht ueber dem Wiesenweg")
                over++
            }
        }
        assertTrue(over > 0)
        assertTrue(RoadsLayer.hardness(SurfaceKind.ASPHALT) > RoadsLayer.hardness(SurfaceKind.GRAVEL))
        assertTrue(RoadsLayer.hardness(SurfaceKind.GRAVEL) > RoadsLayer.hardness(SurfaceKind.GRASS))
    }

    @Test
    fun `Strassen haben eigene Materialnamen und halten das Budget`() {
        val names = HashSet<String>()
        for (q in IndoorQuality.entries) for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec, RoadsLayer(), q)
            assertTrue(out.triangles(WorldNames.NODE_ROADS) <= ctx.budget.roads)
            if (out.triangleCount == 0) continue
            val bytes = out.toGlb().toBytes()
            assertEquals(emptyList(), checkTileGlb(bytes))
            names.addAll(parseGlb(bytes).materials.map { it.name ?: "" })
        }
        assertTrue(names.isNotEmpty())
        for (n in names) assertTrue(n.startsWith("x_mat_road_"), "Materialname $n")
        // Knappes Budget: nahe Strassen zuerst, nichts darueber.
        val tight = TileBudget.of(IndoorQuality.MEDIUM, 0).copy(roads = 40)
        for (spec in scene.tiles()) assertTrue(build(scene, spec, RoadsLayer(), budget = tight).second.triangles(WorldNames.NODE_ROADS) <= 40)
    }

    @Test
    fun `Zeichenvorschrift je Strassenklasse`() {
        fun road(cls: String, subclass: String? = null, surface: String? = null, brunnel: String? = null) =
            roadSpec(MapRoad(doubleArrayOf(0.0, 0.0, 10.0, 0.0), cls, subclass, surface, brunnel))
        assertNull(road("primary", brunnel = "tunnel"))
        assertNull(road("ferry"))
        assertNull(road("path", "steps"))
        assertNotNull(road("primary", brunnel = "bridge"))
        assertEquals(SurfaceKind.ASPHALT, road("minor")!!.kind)
        assertEquals(SurfaceKind.GRAVEL, road("track")!!.kind)
        assertEquals(SurfaceKind.ASPHALT, road("track", surface = "paved")!!.kind)
        assertEquals(SurfaceKind.DIRT, road("path", "path")!!.kind)
        assertEquals(SurfaceKind.ASPHALT, road("path", "cycleway")!!.kind)
        assertTrue(road("primary")!!.widthM > road("minor")!!.widthM && road("minor")!!.widthM > road("track")!!.widthM)
        assertTrue(road("primary")!!.rank > road("tertiary")!!.rank && road("tertiary")!!.rank > road("path")!!.rank)
        assertTrue(road("rail")!!.rail)
    }

    @Test
    fun `Kleinteile stehen weder auf der Fahrbahn noch in Gebaeuden und halten das Budget`() {
        var total = 0
        for (s in listOf(scene, RouteTestScene.allSurfaces())) {
            val houses = s.map.buildings(-1e6, -1e6, 1e6, 1e6)
            val net = { e: Double, n: Double -> s.map.roads(e - 30, n - 30, e + 30, n + 30) }
            for (q in IndoorQuality.entries) for (spec in s.tiles()) {
                val (ctx, out) = build(s, spec, PropsLayer(), q)
                val tris = out.triangles(WorldNames.NODE_PROPS)
                assertTrue(tris <= ctx.budget.props, "${spec.id} $q: $tris > ${ctx.budget.props}")
                if (tris == 0) continue
                if (q == IndoorQuality.MEDIUM) total += tris
                assertEquals(emptyList(), checkTileGlb(out.toGlb().toBytes()))
                val m = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL))
                for (i in 0 until m.vertexCount) {
                    val e = m.x(i) + spec.anchorE
                    val n = spec.anchorN - m.z(i)
                    val above = m.y(i) + ctx.anchorU - s.ground.heightAt(e, n)
                    assertTrue(above > -0.3 && above < 6.5, "Kleinteil $above m ueber dem Boden")
                    // Ausleger von Laternen duerfen ueber der Fahrbahn haengen, alles andere steht daneben.
                    if (above > 3.0) continue
                    val hit = s.line.nearest(e, n, 100.0)
                    if (hit != null) assertTrue(hit.distance > s.line.halfWidthAt(hit.distM) + 0.1, "Kleinteil auf der Fahrbahn bei ${hit.distM} (${hit.distance} m)")
                    for (b in houses) assertTrue(!pointInRings(b.rings, e, n), "Kleinteil im Gebaeude ${b.id}")
                    for (r in net(e, n)) {
                        val spec2 = roadSpec(r) ?: continue
                        val onTrack = hit != null && hit.distance < 12.0 && r === s.map.roads(-1e6, -1e6, 1e6, 1e6).first()
                        if (!onTrack) assertTrue(distanceToLine(r.line, e, n) > spec2.widthM / 2, "Kleinteil auf einer Strasse")
                    }
                }
            }
        }
        assertTrue(total > 500, "Es gibt Kleinteile ($total Dreiecke)")
    }

    @Test
    fun `Leitpfosten stehen an der Landstrasse, Laternen im Ort`() {
        // Hoehe der hoechsten Eckpunkte je Streckenabschnitt: Leitpfosten 1 m, Laternen 5–6 m.
        var posts = 0
        var lamps = 0
        for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec, PropsLayer())
            val m = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL))
            for (i in 0 until m.vertexCount) {
                val e = m.x(i) + spec.anchorE
                val n = spec.anchorN - m.z(i)
                val above = m.y(i) + ctx.anchorU - scene.ground.heightAt(e, n)
                val hit = scene.line.nearest(e, n, 100.0) ?: continue
                if (above > 5.0) {
                    lamps++
                    assertTrue(hit.distM in 300.0..540.0, "Laterne ausserhalb des Orts bei ${hit.distM}")
                }
                if (hit.distM < 150.0 && abs(above - 1.0) < 0.03 && hit.distance < scene.line.halfWidthAt(hit.distM) + 0.8) posts++
            }
        }
        assertTrue(posts >= 8, "Leitpfosten an der Landstrasse ($posts Eckpunkte)")
        assertTrue(lamps > 0, "Laternen im Ort")
    }

    @Test
    fun `Kleinteile sind deterministisch und ueber Kacheln nicht doppelt`() {
        val spec = scene.tiles()[1]
        val a = build(scene, spec, PropsLayer()).second.toGlb().toBytes()
        val b = build(RouteTestScene.countryRoad(), spec, PropsLayer()).second.toGlb().toBytes()
        assertTrue(a.contentEquals(b))
        // Jeder Eckpunkt liegt in der Naehe seiner Kachel (Fusspunkt in der Kachel, Teile hoechstens wenige Meter gross).
        for (s in scene.tiles()) {
            val (_, out) = build(scene, s, PropsLayer())
            val m = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL))
            for (i in 0 until m.vertexCount) {
                assertTrue(s.distanceTo(m.x(i) + s.anchorE, s.anchorN - m.z(i)) < 4.5, "Kleinteil weit ausserhalb seiner Kachel")
            }
        }
    }
}
