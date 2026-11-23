package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.WorldNames
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Die eingebaute Testszene
 *
 * Braucht **keine** Datei (Vertrag `WorldRenderer.loadTestScene`): ein
 * geneigtes Stueck Wiese von 1,2 km Kantenlaenge, darauf eine runde
 * Fahrbahn (Radius [RADIUS_M]), ein paar Baeume und Haeuser, dazu ein
 * passendes [WorldTrack] — so laufen Fahrer, Kamera, Licht und Dunst
 * genau wie in einer echten Welt. Wenn selbst das nichts zeigt, liegt es an
 * Geraet oder Treiber.
 *
 * Gelaende: Ebene mit 3,75 % Gefaelle nach Sueden (`u = 0,0375 · n`), die
 * Runde steigt und faellt also sanft (±3 m).
 */
object TestSceneModel {
    const val RADIUS_M = 80.0
    const val STEP_M = 5.0
    const val SLOPE = 0.0375
    const val ROAD_LIFT_M = 0.04
    const val ROAD_WIDTH_M = 4.0
    const val GROUND_HALF_M = 600.0

    fun groundU(e: Double, n: Double): Double = SLOPE * n

    /** Das Streckenband der Runde, im Uhrzeigersinn von Nord ueber Ost. */
    fun track(): WorldTrack {
        val length = 2 * PI * RADIUS_M
        val count = ceil(length / STEP_M).toInt() + 1
        val e = FloatArray(count)
        val n = FloatArray(count)
        val u = FloatArray(count)
        for (i in 0 until count) {
            val d = minOf(i * STEP_M, length)
            val th = d / RADIUS_M
            val ee = RADIUS_M * sin(th)
            val nn = RADIUS_M * cos(th)
            e[i] = ee.toFloat()
            n[i] = nn.toFloat()
            u[i] = (groundU(ee, nn) + ROAD_LIFT_M).toFloat()
        }
        return WorldTrack(
            lengthM = length,
            stepM = STEP_M,
            loop = true,
            e = e,
            n = n,
            u = u,
            surfaces = listOf(SurfaceSection(0.0, length, SurfaceKind.ASPHALT, widthM = ROAD_WIDTH_M)),
            source = IndoorRouteSource.NONE,
        )
    }

    fun buildGlb(): ByteArray = build().build()

    fun build(): GlbWriter {
        val w = GlbWriter()
        val mGround = w.material(GlbWriter.Material(WorldNames.MAT_TERRAIN, floatArrayOf(1f, 1f, 1f, 1f), roughness = 0.95f))
        val mRoad = w.material(GlbWriter.Material(WorldNames.MAT_ROUTE_PREFIX + SurfaceKind.ASPHALT.textureKey, floatArrayOf(0.09f, 0.09f, 0.095f, 1f), roughness = 0.85f))
        val mLine = w.material(GlbWriter.Material("x_mat_line", floatArrayOf(0.8f, 0.8f, 0.75f, 1f), roughness = 0.7f))
        val mTrunk = w.material(GlbWriter.Material(WorldNames.MAT_TREE_TRUNK, floatArrayOf(0.12f, 0.07f, 0.04f, 1f), roughness = 0.95f))
        val mLeaves = w.material(GlbWriter.Material(WorldNames.MAT_TREE_LEAVES, floatArrayOf(0.05f, 0.16f, 0.04f, 1f), roughness = 0.9f))
        val mWall = w.material(GlbWriter.Material(WorldNames.MAT_BUILDING_WALL, floatArrayOf(0.55f, 0.50f, 0.44f, 1f), roughness = 0.9f))
        val mRoof = w.material(GlbWriter.Material(WorldNames.MAT_BUILDING_ROOF, floatArrayOf(0.35f, 0.10f, 0.07f, 1f), roughness = 0.85f))

        // Gelaende: Gitter mit Vertex-Farben (zwei Gruentoene im Schachbrett, sanft verrauscht).
        val ground = MeshBuilder()
        val cells = 48
        val cell = 2 * GROUND_HALF_M / cells
        val normal = V3(0.0, 1.0, SLOPE).normalized() // glTF: Z = −n, also kippt die Normale nach +Z
        for (i in 0 until cells) for (j in 0 until cells) {
            val e0 = -GROUND_HALF_M + i * cell
            val n0 = -GROUND_HALF_M + j * cell
            val tone = if ((i + j) % 2 == 0) 0.0f else 0.025f
            val jitter = (hash(i, j) * 0.03).toFloat()
            ground.vertexColor(floatArrayOf(0.10f + tone + jitter, 0.20f + tone + jitter, 0.06f + tone * 0.5f, 1f))
            fun p(e: Double, n: Double) = enuToGltf(e, n, groundU(e, n))
            ground.quad(p(e0, n0), p(e0 + cell, n0), p(e0 + cell, n0 + cell), p(e0, n0 + cell), normal)
        }
        w.node(GlbWriter.Node(WorldNames.NODE_TERRAIN, mesh = w.mesh(WorldNames.NODE_TERRAIN, listOf(ground.primitive(mGround, withColors = true)))))

        // Fahrbahn als Ring mit Mittellinie (gestrichelt).
        val road = MeshBuilder()
        val line = MeshBuilder()
        val segs = 160
        for (k in 0 until segs) {
            val a0 = 2 * PI * k / segs
            val a1 = 2 * PI * (k + 1) / segs
            fun ring(a: Double, r: Double, lift: Double): V3 {
                val e = r * sin(a)
                val n = r * cos(a)
                return enuToGltf(e, n, groundU(e, n) + lift)
            }
            val ri = RADIUS_M - ROAD_WIDTH_M / 2
            val ro = RADIUS_M + ROAD_WIDTH_M / 2
            road.quad(ring(a0, ro, ROAD_LIFT_M), ring(a1, ro, ROAD_LIFT_M), ring(a1, ri, ROAD_LIFT_M), ring(a0, ri, ROAD_LIFT_M), normal)
            if (k % 2 == 0) {
                line.quad(
                    ring(a0, RADIUS_M + 0.06, ROAD_LIFT_M + 0.005), ring(a1, RADIUS_M + 0.06, ROAD_LIFT_M + 0.005),
                    ring(a1, RADIUS_M - 0.06, ROAD_LIFT_M + 0.005), ring(a0, RADIUS_M - 0.06, ROAD_LIFT_M + 0.005), normal,
                )
            }
        }
        w.node(GlbWriter.Node(WorldNames.NODE_ROUTE, mesh = w.mesh(WorldNames.NODE_ROUTE, listOf(road.primitive(mRoad), line.primitive(mLine)))))

        // Baeume: Stamm und zwei Kegel aus Rohren mit spitzem Ende, ausserhalb und innerhalb der Runde.
        val trunks = MeshBuilder()
        val leaves = MeshBuilder()
        var placed = 0
        var k = 0
        while (placed < 90 && k < 2000) {
            val a = hash(k, 7) * 2 * PI
            val r = 20.0 + hash(k, 13) * 260.0
            k++
            if (kotlin.math.abs(r - RADIUS_M) < 8.0) continue
            val e = r * sin(a)
            val n = r * cos(a)
            val base = enuToGltf(e, n, groundU(e, n))
            val h = 7.0 + hash(k, 3) * 9.0
            trunks.tube(base, base + V3(0.0, h * 0.35, 0.0), 0.18, seg = 6)
            leaves.tube(base + V3(0.0, h * 0.25, 0.0), base + V3(0.0, h * 0.75, 0.0), h * 0.22, seg = 8, r2 = h * 0.06)
            leaves.tube(base + V3(0.0, h * 0.6, 0.0), base + V3(0.0, h, 0.0), h * 0.14, seg = 8, r2 = 0.02)
            placed++
        }
        w.node(GlbWriter.Node(WorldNames.NODE_TREES, mesh = w.mesh(WorldNames.NODE_TREES, listOf(trunks.primitive(mTrunk), leaves.primitive(mLeaves)))))

        // Haeuser: Quader mit flachem Dach, an festen Stellen.
        val walls = MeshBuilder()
        val roofs = MeshBuilder()
        listOf(
            Triple(-130.0, 40.0, 9.0), Triple(-150.0, 70.0, 7.0), Triple(140.0, -60.0, 12.0),
            Triple(160.0, -20.0, 8.0), Triple(30.0, 150.0, 10.0), Triple(-40.0, -160.0, 6.0),
        ).forEach { (e, n, h) ->
            val g = groundU(e, n)
            walls.box(enuToGltf(e, n, g + h / 2 - 1.0), V3(10.0, h + 2.0, 8.0))
            roofs.box(enuToGltf(e, n, g + h + 0.3), V3(11.0, 0.6, 9.0))
        }
        w.node(GlbWriter.Node(WorldNames.NODE_BUILDINGS, mesh = w.mesh(WorldNames.NODE_BUILDINGS, listOf(walls.primitive(mWall), roofs.primitive(mRoof)))))
        return w
    }

    /** Fester Pseudozufall 0..1 aus zwei Zahlen. */
    fun hash(a: Int, b: Int): Double {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647.0
    }
}
