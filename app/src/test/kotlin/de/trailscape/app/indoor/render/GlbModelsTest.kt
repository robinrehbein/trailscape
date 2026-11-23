package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.WorldModels
import de.trailscape.core.indoor.world.WorldNames
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Die im Code erzeugten GLB-Dateien (Fahrer, Geist, Testszene) gegen den
 * Vertrag 7.3/7.5: gueltiges glTF 2.0 binaer, nur erlaubte Erweiterungen,
 * Materialien ohne Metall, Animationen `pedal`/`roll`, Dreiecksbudget,
 * Wicklung passend zu den Normalen, Beine erreichen die Pedale.
 */
class GlbModelsTest {
    private class Glb(val json: JsonObject, val bin: ByteBuffer)

    private fun parse(bytes: ByteArray): Glb {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, bb.int, "Magie glTF")
        assertEquals(2, bb.int, "Version 2")
        assertEquals(bytes.size, bb.int, "Gesamtlaenge")
        val jsonLen = bb.int
        assertEquals(0x4E4F534A, bb.int, "JSON-Stueck")
        assertEquals(0, jsonLen % 4)
        val json = String(bytes, 20, jsonLen, Charsets.UTF_8)
        bb.position(20 + jsonLen)
        val binLen = bb.int
        assertEquals(0x004E4942, bb.int, "BIN-Stueck")
        assertEquals(0, binLen % 4)
        val bin = ByteBuffer.wrap(bytes, 28 + jsonLen, binLen).slice().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size, 28 + jsonLen + binLen)
        return Glb(Json.parseToJsonElement(json).jsonObject, bin)
    }

    private fun JsonObject.arr(name: String): JsonArray = this[name]?.jsonArray ?: JsonArray(emptyList())

    private fun floats(g: Glb, accessor: Int): FloatArray {
        val acc = g.json.arr("accessors")[accessor].jsonObject
        assertEquals(5126, acc["componentType"]!!.jsonPrimitive.int)
        val view = g.json.arr("bufferViews")[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
        val comps = when (acc["type"]!!.jsonPrimitive.content) { "SCALAR" -> 1; "VEC3" -> 3; "VEC4" -> 4; else -> error("Typ") }
        val n = acc["count"]!!.jsonPrimitive.int * comps
        val off = view["byteOffset"]!!.jsonPrimitive.int
        assertTrue(off + n * 4 <= g.bin.capacity())
        return FloatArray(n) { g.bin.getFloat(off + it * 4) }
    }

    private fun indices(g: Glb, accessor: Int): IntArray {
        val acc = g.json.arr("accessors")[accessor].jsonObject
        val view = g.json.arr("bufferViews")[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
        val off = view["byteOffset"]!!.jsonPrimitive.int
        val n = acc["count"]!!.jsonPrimitive.int
        return when (acc["componentType"]!!.jsonPrimitive.int) {
            5123 -> IntArray(n) { g.bin.getShort(off + it * 2).toInt() and 0xFFFF }
            5125 -> IntArray(n) { g.bin.getInt(off + it * 4) }
            else -> error("Indextyp")
        }
    }

    /** Vertragspruefung, die fuer jede GLB gilt. */
    private fun checkContract(g: Glb) {
        assertEquals("2.0", g.json["asset"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val ext = g.json.arr("extensionsUsed").map { it.jsonPrimitive.content }
        assertTrue(ext.all { it == "KHR_materials_unlit" }, "nur erlaubte Erweiterungen: $ext")
        assertTrue(g.json["textures"] == null && g.json["images"] == null)
        assertTrue(g.json["cameras"] == null && g.json["skins"] == null)
        g.json.arr("materials").forEach { m ->
            val pbr = m.jsonObject["pbrMetallicRoughness"]!!.jsonObject
            assertEquals(0.0, pbr["metallicFactor"]!!.jsonPrimitive.double)
            assertTrue(pbr["roughnessFactor"]!!.jsonPrimitive.double >= 0.6)
        }
        // Jeder Index zeigt auf einen Punkt; Dreiecke sind gegen den Uhrzeigersinn zur Normale.
        g.json.arr("meshes").forEach { mesh ->
            mesh.jsonObject.arr("primitives").forEach { p ->
                val attrs = p.jsonObject["attributes"]!!.jsonObject
                val pos = floats(g, attrs["POSITION"]!!.jsonPrimitive.int)
                val nor = floats(g, attrs["NORMAL"]!!.jsonPrimitive.int)
                val idx = indices(g, p.jsonObject["indices"]!!.jsonPrimitive.int)
                assertEquals(pos.size, nor.size)
                assertEquals(0, idx.size % 3)
                val vc = pos.size / 3
                var bad = 0
                var counted = 0
                for (t in 0 until idx.size / 3) {
                    val (a, b, c) = Triple(idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2])
                    assertTrue(a < vc && b < vc && c < vc)
                    fun v(i: Int) = V3(pos[i * 3].toDouble(), pos[i * 3 + 1].toDouble(), pos[i * 3 + 2].toDouble())
                    val geo = (v(b) - v(a)).cross(v(c) - v(a))
                    if (geo.length() < 1e-9) continue // entartet (Kegelspitze), stoert nicht
                    val n = V3(nor[a * 3].toDouble(), nor[a * 3 + 1].toDouble(), nor[a * 3 + 2].toDouble()) +
                        V3(nor[b * 3].toDouble(), nor[b * 3 + 1].toDouble(), nor[b * 3 + 2].toDouble()) +
                        V3(nor[c * 3].toDouble(), nor[c * 3 + 1].toDouble(), nor[c * 3 + 2].toDouble())
                    counted++
                    if (geo.dot(n) <= 0) bad++
                }
                assertTrue(bad == 0, "${mesh.jsonObject["name"]}: $bad von $counted Dreiecken falsch herum")
            }
        }
    }

    private fun triangles(g: Glb): Int = g.json.arr("meshes").sumOf { m ->
        m.jsonObject.arr("primitives").sumOf { p ->
            g.json.arr("accessors")[p.jsonObject["indices"]!!.jsonPrimitive.int].jsonObject["count"]!!.jsonPrimitive.int / 3
        }
    }

    @Test
    fun fahrerHaeltDenVertrag() {
        val g = parse(AvatarModel.buildGlb())
        checkContract(g)
        val names = g.json.arr("animations").map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf(WorldModels.ANIM_PEDAL, WorldModels.ANIM_ROLL), names)
        // Jede Animation dauert genau eine Sekunde (eine Umdrehung).
        g.json.arr("animations").forEach { a ->
            a.jsonObject.arr("samplers").forEach { s ->
                val times = floats(g, s.jsonObject["input"]!!.jsonPrimitive.int)
                assertEquals(0f, times.first())
                assertEquals(1f, times.last())
            }
        }
        val tris = triangles(g)
        assertTrue(tris in 500..30_000, "Dreiecke: $tris (Vertrag: Fahrer und Geist zusammen hoechstens 60 000)")
        val size = AvatarModel.buildGlb().size
        assertTrue(size < 1_000_000, "Groesse $size (zusammen hoechstens 2 MB)")
    }

    @Test
    fun geistIstDurchscheinend() {
        val g = parse(AvatarModel.buildGlb(AvatarModel.GHOST))
        checkContract(g)
        g.json.arr("materials").forEach { m ->
            assertEquals("BLEND", m.jsonObject["alphaMode"]!!.jsonPrimitive.content)
            val alpha = m.jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorFactor"]!!.jsonArray[3].jsonPrimitive.double
            assertTrue(alpha < 0.6)
        }
    }

    @Test
    fun raederPassenZumAbrollumfang() {
        assertEquals(WorldModels.WHEEL_CIRCUMFERENCE_M, 2 * PI * AvatarModel.WHEEL_RADIUS_M, 1e-9)
        val g = parse(AvatarModel.buildGlb())
        val nodes = g.json.arr("nodes").map { it.jsonObject }
        val rear = nodes.first { it["name"]!!.jsonPrimitive.content == "x_wheel_rear" }
        val t = rear["translation"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(AvatarModel.WHEEL_RADIUS_M, t[1], 1e-9) // Nabe auf Radhoehe: Reifen beruehrt den Boden bei y = 0
        // Ursprung mittig zwischen den Raedern.
        val front = nodes.first { it["name"]!!.jsonPrimitive.content == "x_wheel_front" }
        assertEquals(0.0, t[2] + front["translation"]!!.jsonArray[2].jsonPrimitive.double, 1e-9)
    }

    @Test
    fun beineErreichenDiePedale() {
        for (k in 0 until 32) {
            val crank = 2 * PI * k / 32
            val a = AvatarModel.legAngles(crank)
            // Vorwaertskette: Huefte → Knie → Fuss aus den Winkeln nachrechnen.
            fun dir(alpha: Double) = V3(0.0, -cos(alpha), -sin(alpha))
            val knee = AvatarModel.HIP + dir(a.hipRad) * AvatarModel.THIGH_M
            val foot = knee + dir(a.hipRad + a.kneeRad) * AvatarModel.SHANK_M
            assertTrue((knee - a.knee).length() < 1e-9)
            assertTrue((foot - a.foot).length() < 0.02, "Fuss bei Kurbel $k verfehlt: ${(foot - a.foot).length()}")
            assertTrue(a.knee.z > AvatarModel.HIP.z, "Knie zeigt nach vorn")
            assertTrue(a.kneeRad > 0.0, "Unterschenkel zurueck gegenueber dem Oberschenkel: ${a.kneeRad}")
        }
    }

    @Test
    fun kurbelDrehtVorwaerts() {
        // Rechte Kurbel oben bei 0, vorn bei 90°: Drehung um +X mit wachsendem Winkel.
        val top = AvatarModel.pedal(0.0)
        val front = AvatarModel.pedal(PI / 2)
        assertTrue(top.y > AvatarModel.BOTTOM_BRACKET.y + 0.1)
        assertTrue(front.z > AvatarModel.BOTTOM_BRACKET.z + 0.1)
        val q = AvatarModel.quatX(PI / 2)
        assertEquals(1.0, q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3], 1e-12)
    }

    @Test
    fun testszeneHaeltDenVertrag() {
        val g = parse(TestSceneModel.buildGlb())
        checkContract(g)
        val names = g.json.arr("nodes").map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(names.containsAll(listOf(WorldNames.NODE_TERRAIN, WorldNames.NODE_ROUTE, WorldNames.NODE_TREES, WorldNames.NODE_BUILDINGS)))
        assertTrue(triangles(g) < 100_000)
        // Das Gelaende traegt Vertex-Farben.
        val terrain = g.json.arr("meshes").first { it.jsonObject["name"]!!.jsonPrimitive.content == WorldNames.NODE_TERRAIN }
        assertNotNull(terrain.jsonObject.arr("primitives")[0].jsonObject["attributes"]!!.jsonObject["COLOR_0"])
    }

    @Test
    fun testszenenBandLiegtAufDerFahrbahn() {
        val t = TestSceneModel.track()
        assertTrue(t.loop)
        assertEquals(2 * PI * TestSceneModel.RADIUS_M, t.lengthM, 1e-9)
        for (d in listOf(0.0, 77.0, 250.0, 400.0)) {
            val p = t.poseAt(d)
            assertEquals(TestSceneModel.RADIUS_M, Math.hypot(p.e, p.n), 0.2)
            assertEquals(TestSceneModel.groundU(p.e, p.n) + TestSceneModel.ROAD_LIFT_M, p.u, 0.05)
            assertTrue(abs(p.gradePct) < 5.0)
        }
        // Start nach Osten (im Uhrzeigersinn von Nord).
        assertEquals(90.0, t.poseAt(0.1).headingDeg, 2.0)
    }

    @Test
    fun glbAusrichtung() {
        val glb = GlbWriter.glb("{}".toByteArray(), byteArrayOf(1, 2, 3))
        assertEquals(0, glb.size % 4)
        assertEquals(12 + 8 + 4 + 8 + 4, glb.size)
    }
}
