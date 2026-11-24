package de.trailscape.core.indoor.world

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kreisverkehre und koplanare Flaechen (Rueckmeldung vom Geraet: „Kreisverkehr komisch“, „flackernd“). */
class QualityTest {
    private fun ring(ce: Double, cn: Double, ra: Double, rb: Double, n: Int, from: Int = 0, to: Int = n): DoubleArray {
        val pts = ArrayList<Double>()
        for (k in from..to) {
            val a = 2 * PI * k / n
            pts += ce + ra * cos(a); pts += cn + rb * sin(a)
        }
        return pts.toDoubleArray()
    }

    private fun mapOf(vararg roads: MapRoad): MapData = object : MapData {
        override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapBuilding>()
        override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = roads.toList()
        override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapArea>()
        override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapWaterway>()
    }

    @Test
    fun `geschlossener runder Ring wird zur Mittelinsel, auch aus zwei Stuecken`() {
        val whole = Roundabouts.islands(mapOf(MapRoad(ring(10.0, 20.0, 18.0, 18.0, 24), "secondary")), -100.0, -100.0, 100.0, 100.0)
        assertEquals(1, whole.size)
        val isl = whole[0]
        assertEquals(10.0, isl.ce, 0.5)
        assertEquals(20.0, isl.cn, 0.5)
        // halbe Breite secondary 4,0 m + 0,4 m Bordsteinabstand
        assertEquals(18.0 - 4.4, isl.radius, 0.6)
        assertTrue(isl.contains(10.0, 20.0))
        assertTrue(!isl.contains(10.0 + 17.0, 20.0))

        val halves = Roundabouts.islands(
            mapOf(MapRoad(ring(0.0, 0.0, 20.0, 20.0, 24, 0, 12), "minor"), MapRoad(ring(0.0, 0.0, 20.0, 20.0, 24, 12, 24), "minor")),
            -100.0, -100.0, 100.0, 100.0,
        )
        assertEquals(1, halves.size, "an der Einmuendung zerlegter Ring")
    }

    @Test
    fun `lange Schleifen, Wege und offene Linien sind keine Kreisverkehre`() {
        val long = MapRoad(ring(0.0, 0.0, 50.0, 10.0, 32), "service")
        val path = MapRoad(ring(0.0, 0.0, 15.0, 15.0, 24), "path")
        val open = MapRoad(ring(0.0, 0.0, 15.0, 15.0, 24, 0, 18), "minor")
        val tiny = MapRoad(ring(0.0, 0.0, 4.0, 4.0, 16), "minor")
        assertEquals(0, Roundabouts.islands(mapOf(long, path, open, tiny), -100.0, -100.0, 100.0, 100.0).size)
    }

    @Test
    fun `Gelaendenetz-Hoehe trifft die Gitterpunkte und liegt dazwischen im Dreieck`() {
        val g = GroundSurface { e, n -> 0.01 * e * e + 0.3 * n }
        assertEquals(g.heightAt(8.0, 12.0), terrainMeshHeightAt(g, 8.0, 12.0, 4.0), 1e-9)
        // Zwischen den Gitterpunkten: linear, also bei konvexem Boden darueber.
        val mid = terrainMeshHeightAt(g, 10.0, 12.0, 4.0)
        assertTrue(mid > g.heightAt(10.0, 12.0))
        assertEquals((g.heightAt(8.0, 12.0) + g.heightAt(12.0, 12.0)) / 2, mid, 1e-9)
    }

    @Test
    fun `Testwelt hat keine deckenden Flaechen in derselben Ebene`() {
        val dir = tempDir("koplanar")
        val m = createWorldBuilder().buildTestWorld(dir, IndoorQuality.MEDIUM)
        val opaque = HashMap<String, Int>()
        var all = 0
        for (t in m.tiles.filter { it.level == 0 }) {
            for ((k, v) in coplanarOverlaps(File(dir, t.file).readBytes())) {
                all += v
                // Weich auslaufende Raender (BLEND, Deckung am Aussenrand null) und Uferlinien zaehlen nicht.
                if (listOf("_edge", "verge", "fringe", "water", "fade").any { it in k }) continue
                opaque[k] = (opaque[k] ?: 0) + v
            }
        }
        println("Koplanare Treffer in der Testwelt: $all, davon deckend: $opaque")
        assertTrue(opaque.values.sum() == 0, "deckende koplanare Flaechen: $opaque")
    }

    @Test
    fun `durchsichtige Flaechen der Testwelt holen ihre Deckung aus der Textur, nicht aus den Eckpunkten`() {
        // Auf dem Geraet (Filament, MSAA, Float-Puffer) wird eine zum Rand auslaufende Vertex-Deckung jenseits
        // der Dreieckskante negativ und hellt den Hintergrund auf: weisse gepunktete Naehte. Siehe translucentVertexAlpha.
        val dir = tempDir("vertexdeckung")
        val m = createWorldBuilder().buildTestWorld(dir, IndoorQuality.MEDIUM)
        val low = HashMap<String, Int>()
        for (t in m.tiles.filter { it.level == 0 }) {
            for ((k, v) in translucentVertexAlpha(File(dir, t.file).readBytes())) low[k] = (low[k] ?: 0) + v
        }
        assertTrue(low.isEmpty(), "BLEND-Eckpunkte mit Deckung < 1: $low")
    }

    @Test
    fun `Detektor fuer auslaufende Vertex-Deckung schlaegt an`() {
        val scene = GlbScene()
        val mb = MeshBuilder(4, 2)
        val a = mb.vertex(0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val b = mb.vertex(4.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val c = mb.vertex(4.0, 0.0, -4.0, 0.0, 1.0, 0.0, color = Argb.of(0, 255, 255, 255))
        mb.tri(a, b, c)
        scene.addMesh(scene.addNode(WorldNames.NODE_ROUTE), mb, scene.addMaterial(GlbMaterial(name = "x_weich", alphaMode = GlbAlphaMode.BLEND)))
        assertEquals(mapOf("x_weich" to 1), translucentVertexAlpha(scene.toBytes()))
    }

    @Test
    fun `Detektor findet zwei uebereinanderliegende Flaechen`() {
        val scene = GlbScene()
        fun quad(name: String, y: Double) {
            val mb = MeshBuilder(4, 2)
            val a = mb.vertex(0.0, y, 0.0, 0.0, 1.0, 0.0)
            val b = mb.vertex(4.0, y, 0.0, 0.0, 1.0, 0.0)
            val c = mb.vertex(4.0, y, -4.0, 0.0, 1.0, 0.0)
            val d = mb.vertex(0.0, y, -4.0, 0.0, 1.0, 0.0)
            mb.quad(a, b, c, d)
            scene.addMesh(scene.addNode(name), mb, scene.addMaterial(GlbMaterial(name = "x_$name")))
        }
        quad(WorldNames.NODE_ROADS, 1.0)
        quad(WorldNames.NODE_TERRAIN, 1.001)
        val found = coplanarOverlaps(scene.toBytes())
        assertTrue(found.values.sum() >= 2, "gefunden: $found")
    }
}
