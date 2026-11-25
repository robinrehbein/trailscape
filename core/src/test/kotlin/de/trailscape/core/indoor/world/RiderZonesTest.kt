package de.trailscape.core.indoor.world

import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.garage.RiderLook
import de.trailscape.core.garage.RiderZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Zonen als eigene Materialien (Garage) und die LOD-Stufe des Fahrermodells. */
class RiderZonesTest {
    private val rider = buildRiderGlb()
    private val lod = buildRiderLodGlb()

    /** JSON-Teil und Beginn des Binaerblocks. */
    private fun json(glb: ByteArray): Pair<JsonObject, Int> {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val len = bb.getInt(12)
        return Json.parseToJsonElement(String(glb, 20, len, Charsets.UTF_8)).jsonObject to 20 + len + 8
    }

    /** Vertexfarben (RGBA-Bytes) je Materialname. */
    private fun colorsByMaterial(glb: ByteArray): Map<String, List<IntArray>> {
        val (j, bin) = json(glb)
        val names = j["materials"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        val acc = j["accessors"]!!.jsonArray
        val views = j["bufferViews"]!!.jsonArray
        val out = HashMap<String, MutableList<IntArray>>()
        for (mesh in j["meshes"]!!.jsonArray) for (prim in mesh.jsonObject["primitives"]!!.jsonArray) {
            val po = prim.jsonObject
            val name = names[po["material"]!!.jsonPrimitive.int]
            val ci = po["attributes"]!!.jsonObject["COLOR_0"]?.jsonPrimitive?.int ?: continue
            val a = acc[ci].jsonObject
            assertEquals(5121, a["componentType"]!!.jsonPrimitive.int, "COLOR_0 als Bytes")
            val view = views[a["bufferView"]!!.jsonPrimitive.int].jsonObject
            val stride = view["byteStride"]?.jsonPrimitive?.int ?: 4
            val base = bin + (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (a["byteOffset"]?.jsonPrimitive?.int ?: 0)
            val list = out.getOrPut(name) { ArrayList() }
            for (k in 0 until a["count"]!!.jsonPrimitive.int) {
                val o = base + k * stride
                list += IntArray(4) { glb[o + it].toInt() and 0xFF }
            }
        }
        return out
    }

    @Test
    fun `jede Zone ist ein eigenes Material mit grauen Vertexfarben`() {
        for (glb in listOf(rider, lod)) {
            val colors = colorsByMaterial(glb)
            for (z in RiderZone.entries) {
                // Die grobe LOD-Stufe hat keinen Querschnitt im schmalen Saum; dann fehlt nur diese Zone.
                if (glb === lod && z == RiderZone.JERSEY_TRIM && colors[z.materialName] == null) continue
                val list = assertNotNull(colors[z.materialName], "Zone ${z.materialName} fehlt oder hat kein COLOR_0")
                assertTrue(list.size > 3, "Zone ${z.materialName} hat nur ${list.size} Eckpunkte")
                for (c in list) {
                    assertTrue(c[0] == c[1] && c[1] == c[2], "Zone ${z.materialName}: Vertexfarbe ${c.toList()} ist nicht grau")
                    assertTrue(c[0] in 100..254, "Zone ${z.materialName}: Helligkeit ${c[0]}")
                }
            }
        }
    }

    @Test
    fun `keine Markierungsfarbe bleibt im Modell`() {
        for (glb in listOf(rider, lod)) {
            for ((name, list) in colorsByMaterial(glb)) {
                for (c in list) {
                    // Markierungen: (251, 9k, 3) und (3, 251, 9).
                    assertTrue(!(c[0] == 251 && c[2] == 3) && !(c[0] == 3 && c[1] == 251), "$name: Markierung ${c.toList()} uebrig")
                }
            }
        }
    }

    @Test
    fun `Vorgabefarben stehen im baseColorFactor`() {
        val (j, _) = json(rider)
        val defaults = RiderLook.materialFactors(RiderAvatar.DEFAULT)
        for (m in j["materials"]!!.jsonArray) {
            val name = m.jsonObject["name"]!!.jsonPrimitive.content
            val f = m.jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorFactor"]!!.jsonArray.map { it.jsonPrimitive.float }
            val expected = defaults[name]
            if (expected == null) {
                assertEquals(listOf(1f, 1f, 1f, 1f), f, "festes Material $name")
            } else {
                for (k in 0..3) assertEquals(expected[k], f[k], 1e-5f, "$name Kanal $k")
            }
        }
    }

    @Test
    fun `LOD hat 3000 bis 5000 Dreiecke und dieselben Knoten, Animationen und Materialien`() {
        assertEquals(emptyList(), checkModelGlb(lod))
        val l = parseGlb(lod)
        val r = parseGlb(rider)
        assertTrue(l.triangleCount in 3_000..5_000, "LOD hat ${l.triangleCount} Dreiecke")
        assertEquals(r.nodes.map { it.name }, l.nodes.map { it.name })
        assertEquals(r.animations.map { it.name to it.channelCount }, l.animations.map { it.name to it.channelCount })
        assertEquals(RIDER_MATERIAL_NAMES, l.materials.map { it.name })
        assertTrue(lod.size < 300 * 1024, "LOD ist ${lod.size} Bytes gross")
        // Gleiche Lage und Groesse wie das volle Modell (Reifen am Boden, Laenge).
        val mn = assertNotNull(l.boundsMin)
        val mx = assertNotNull(l.boundsMax)
        val rmn = assertNotNull(r.boundsMin)
        val rmx = assertNotNull(r.boundsMax)
        for (k in 0..2) {
            assertEquals(rmn[k], mn[k], 0.02, "min $k")
            assertEquals(rmx[k], mx[k], 0.02, "max $k")
        }
    }

    @Test
    fun `Draw Calls je Fahrer bleiben ueberschaubar`() {
        // Ein Primitiv je Knoten und Material, das dort vorkommt.
        for (glb in listOf(rider, lod)) {
            val (j, _) = json(glb)
            val prims = j["meshes"]!!.jsonArray.sumOf { it.jsonObject["primitives"]!!.jsonArray.size }
            assertTrue(prims <= 48, "$prims Primitive")
        }
    }
}
