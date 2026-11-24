package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteLayerTest {
    private val scene = RouteTestScene.allSurfaces()
    private val kinds = SurfaceKind.entries.filter { it != SurfaceKind.UNKNOWN }

    private fun build(s: RouteTestScene, spec: TileSpec, quality: IndoorQuality = IndoorQuality.MEDIUM, budget: TileBudget = TileBudget.of(quality, 0)): Pair<TileContext, TileOutput> {
        val ctx = s.context(spec, quality, budget)
        val out = TileOutput(spec)
        RouteLayer().build(ctx, out)
        return ctx to out
    }

    private fun roadMesh(out: TileOutput, kind: SurfaceKind): MeshBuilder =
        out.mesh(WorldNames.NODE_ROUTE, TileMaterial(RouteLayer.routeMaterialName(kind)))

    /** Grundflaeche aller Dreiecke eines Netzes (in der Waagerechten). */
    private fun planArea(m: MeshBuilder): Double {
        var a = 0.0
        for (t in 0 until m.triangleCount) {
            val i = m.indices[t * 3]
            val j = m.indices[t * 3 + 1]
            val k = m.indices[t * 3 + 2]
            a += abs((m.x(j) - m.x(i)).toDouble() * (m.z(k) - m.z(i)) - (m.x(k) - m.x(i)).toDouble() * (m.z(j) - m.z(i))) / 2
        }
        return a
    }

    private fun bandArea(s: RouteTestScene, quality: IndoorQuality = IndoorQuality.MEDIUM, budget: (TileSpec) -> TileBudget = { TileBudget.of(quality, 0) }): Double =
        s.tiles().sumOf { spec ->
            val (_, out) = build(s, spec, quality, budget(spec))
            kinds.sumOf { planArea(roadMesh(out, it)) }
        }

    private fun expectedArea(s: RouteTestScene): Double {
        var a = 0.0
        val t = s.track
        for (i in 0 until t.sampleCount - 1) {
            a += (t.distAt(i + 1) - t.distAt(i)) * (s.line.halfWidthAt(t.distAt(i)) + s.line.halfWidthAt(t.distAt(i + 1)))
        }
        return a
    }

    @Test
    fun `Baender benachbarter Kacheln schliessen lueckenlos und ohne Doppelung an`() {
        assertTrue(scene.tiles().size >= 4, "Die Teststrecke soll ueber mehrere Kacheln laufen")
        val expected = expectedArea(scene)
        val actual = bandArea(scene)
        assertEquals(expected, actual, expected * 0.015, "Summe der Fahrbahnflaeche ueber alle Kacheln")
    }

    @Test
    fun `jede Einheit der Strecke gehoert genau einer Kachel`() {
        val owned = scene.tiles().flatMap { RouteGeometry.unitsOf(scene.line, it).asList() }
        assertEquals((0 until RouteGeometry.unitCount(scene.line)).toList(), owned.sorted())
    }

    @Test
    fun `Fahrbahn liegt auf der Hoehe der Strecke und ueber dem Boden`() {
        var checked = 0
        for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec)
            for (kind in kinds) {
                val m = roadMesh(out, kind)
                for (i in 0 until m.vertexCount) {
                    val e = m.x(i) + spec.anchorE
                    val n = spec.anchorN - m.z(i)
                    val u = m.y(i) + ctx.anchorU
                    val hit = scene.line.nearest(e, n, 20.0)!!
                    val hw = scene.line.halfWidthAt(hit.distM)
                    val crownDrop = RouteLayer.crownOf(kind) * hw
                    assertTrue(u <= hit.u + 0.05, "Fahrbahn zu hoch: $u gegen ${hit.u} bei ${hit.distM}")
                    assertTrue(u >= hit.u - crownDrop - 0.05, "Fahrbahn zu tief: $u gegen ${hit.u} bei ${hit.distM}")
                    assertTrue(u >= scene.ground.heightAt(e, n) - 0.01, "Fahrbahn unter dem Boden bei ${hit.distM}")
                    assertTrue(hit.distance <= hw + 0.15, "Fahrbahn breiter als die Strecke bei ${hit.distM}: ${hit.distance} > $hw")
                    checked++
                }
            }
        }
        assertTrue(checked > 500)
    }

    @Test
    fun `Aussenkante des Uebergangsstreifens liegt durchsichtig auf dem Boden`() {
        var outer = 0
        for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec)
            for (v in RouteTextures.Verge.entries) {
                val m = out.mesh(WorldNames.NODE_ROUTE, TileMaterial(RouteLayer.vergeMaterialName(v)))
                for (i in 0 until m.vertexCount) {
                    val e = m.x(i) + spec.anchorE
                    val n = spec.anchorN - m.z(i)
                    val hit = scene.line.nearest(e, n, 40.0)!!
                    if (Argb.alpha(m.colors[i]) != 0 || hit.distance < scene.line.halfWidthAt(hit.distM) + 2.2) continue
                    // Boden = was gezeichnet wird: das Maximum aus wahrem Boden und 4-m-Gelaendenetz.
                    val floor = maxOf(scene.ground.heightAt(e, n), terrainMeshHeightAt(scene.ground, e, n, level0TerrainCellM(ctx.quality)))
                    val above = m.y(i) + ctx.anchorU - floor
                    assertTrue(above in 0.015..0.065, "Aussenkante $above m ueber dem Boden bei ${hit.distM}")
                    outer++
                }
            }
        }
        assertTrue(outer > 200, "Es gibt Aussenkanten ($outer)")
    }

    @Test
    fun `Texturkoordinaten laufen ueber Kachelgrenzen stetig weiter`() {
        // Eckpunkte an derselben Stelle in zwei Kacheln: gleiches u, v bis auf ganze Wiederholungen gleich.
        val seen = HashMap<String, Pair<TileSpec, FloatArray>>()
        var shared = 0
        for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec)
            for (kind in kinds) {
                val m = roadMesh(out, kind)
                for (i in 0 until m.vertexCount) {
                    val key = "$kind ${((m.x(i) + spec.anchorE) * 200).roundToLong()} ${((spec.anchorN - m.z(i)) * 200).roundToLong()} ${((m.y(i) + ctx.anchorU) * 200).roundToLong()}"
                    val uv = floatArrayOf(m.uvs[i * 2], m.uvs[i * 2 + 1])
                    val other = seen[key]
                    if (other == null) {
                        seen[key] = spec to uv
                    } else if (other.first != spec) {
                        shared++
                        assertEquals(other.second[0], uv[0], 2e-3f, "u an der Kachelgrenze")
                        val dv = (uv[1] - other.second[1]).toDouble()
                        assertEquals(0.0, dv - Math.rint(dv), 2e-3, "v an der Kachelgrenze")
                    }
                }
            }
        }
        assertTrue(shared >= 6, "Es gibt gemeinsame Eckpunkte an Kachelgrenzen ($shared)")
    }

    @Test
    fun `Materialnamen je Belag und saubere GLB`() {
        val names = HashSet<String>()
        for (spec in scene.tiles()) {
            val (_, out) = build(scene, spec)
            if (out.triangleCount == 0) continue
            val bytes = out.toGlb().toBytes()
            assertEquals(emptyList(), checkTileGlb(bytes), "Kachel ${spec.id}")
            val info = parseGlb(bytes)
            assertEquals(listOf(WorldNames.NODE_ROUTE), info.nodes.map { it.name })
            names.addAll(info.materials.map { it.name ?: "" })
        }
        for (kind in kinds) assertTrue(WorldNames.MAT_ROUTE_PREFIX + kind.textureKey in names, "Material fuer $kind fehlt in $names")
        for (n in names) assertTrue(n.startsWith(WorldNames.MAT_ROUTE_PREFIX) || n.startsWith("x_mat_route_"), "Unerwarteter Materialname $n")
        assertTrue(names.any { it.endsWith("_fade") }, "Belagswechsel haben ein Ueberblend-Material")
    }

    @Test
    fun `Budget wird in jeder Qualitaet eingehalten`() {
        for (q in IndoorQuality.entries) for (spec in scene.tiles()) {
            val (ctx, out) = build(scene, spec, q)
            assertTrue(out.triangles(WorldNames.NODE_ROUTE) <= ctx.budget.route, "${spec.id} $q: ${out.triangles(WorldNames.NODE_ROUTE)} > ${ctx.budget.route}")
        }
    }

    @Test
    fun `knappes Budget vereinfacht ohne Loecher`() {
        val full = scene.tiles().maxOf { build(scene, it).second.triangles(WorldNames.NODE_ROUTE) }
        val tight = TileBudget.of(IndoorQuality.MEDIUM, 0).copy(route = full / 2)
        var reduced = false
        for (spec in scene.tiles()) {
            val n = build(scene, spec, budget = tight).second.triangles(WorldNames.NODE_ROUTE)
            assertTrue(n <= tight.route, "${spec.id}: $n > ${tight.route}")
            if (n < build(scene, spec).second.triangles(WorldNames.NODE_ROUTE)) reduced = true
        }
        assertTrue(reduced)
        val expected = expectedArea(scene)
        assertEquals(expected, bandArea(scene, budget = { tight }), expected * 0.03, "Flaeche bei knappem Budget")
    }

    @Test
    fun `Hin- und Rueckweg auf demselben Weg wird nur einmal gezeichnet`() {
        val base = PreviewSupport.curvyTrack(lengthM = 300.0, surfaces = listOf(SurfaceKind.GRAVEL))
        val c = base.sampleCount
        // Hinweg, kurze Wendeschleife ausgelassen: der Rueckweg laeuft exakt auf denselben Proben zurueck.
        val e = FloatArray(2 * c - 1) { if (it < c) base.e[it] else base.e[2 * c - 2 - it] }
        val n = FloatArray(2 * c - 1) { if (it < c) base.n[it] else base.n[2 * c - 2 - it] }
        val u = FloatArray(2 * c - 1) { if (it < c) base.u[it] else base.u[2 * c - 2 - it] }
        val len = (2 * c - 2) * base.stepM
        val there = WorldTrack(len, base.stepM, false, e, n, u, listOf(SurfaceSection(0.0, len, SurfaceKind.GRAVEL)), IndoorRouteSource.NONE)
        val s = RouteTestScene(there, listOf(RouteTestScene.Zone(0.0, LandClass.MEADOW, LandClass.MEADOW)), emptyList(), emptyList())
        val single = base.lengthM * SurfaceKind.GRAVEL.defaultWidthM
        val actual = bandArea(s)
        // Um den Wendepunkt (±50 m) gilt der Rueckweg noch nicht als „anderer" Streckenteil.
        assertTrue(actual < single * 1.25, "Doppelt gezeichnet: $actual gegen einfach $single")
        assertTrue(actual > single * 0.97, "Zu wenig gezeichnet: $actual gegen einfach $single")
    }

    @Test
    fun `Randgestaltung folgt Belag und Umgebung`() {
        val road = SurfaceSection(0.0, 100.0, SurfaceKind.ASPHALT, "secondary", 6.5)
        assertEquals(EdgeStyle.BANKETT, edgeStyleOf(road, LandClass.MEADOW))
        assertEquals(EdgeStyle.URBAN, edgeStyleOf(road, LandClass.RESIDENTIAL))
        assertEquals(EdgeStyle.SOFT, edgeStyleOf(SurfaceSection(0.0, 1.0, SurfaceKind.GRAVEL, "track"), LandClass.RESIDENTIAL))
        assertEquals(EdgeStyle.SOFT, edgeStyleOf(SurfaceSection(0.0, 1.0, SurfaceKind.ASPHALT, "track", 3.0), LandClass.FARMLAND))
        assertEquals(EdgeStyle.PLAIN, edgeStyleOf(SurfaceSection(0.0, 1.0, SurfaceKind.WOOD), LandClass.WETLAND))
        assertTrue(RouteLayer.hasCentreLine(road) && RouteLayer.hasEdgeLines(road))
        assertTrue(!RouteLayer.hasEdgeLines(SurfaceSection(0.0, 1.0, SurfaceKind.ASPHALT, "residential", 6.5)))
        assertTrue(!RouteLayer.hasCentreLine(SurfaceSection(0.0, 1.0, SurfaceKind.ASPHALT, "tertiary", 5.6)))
        assertTrue(!RouteLayer.hasEdgeLines(SurfaceSection(0.0, 1.0, SurfaceKind.GRAVEL, "tertiary", 6.5)))
    }

    @Test
    fun `Bau ist deterministisch`() {
        val spec = scene.tiles()[1]
        val a = build(scene, spec).second.toGlb().toBytes()
        val b = build(RouteTestScene.allSurfaces(), spec).second.toGlb().toBytes()
        assertTrue(a.contentEquals(b))
    }
}
