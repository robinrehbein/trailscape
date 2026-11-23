package de.trailscape.app.indoor.render

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * # Ein kleiner GLB-Schreiber
 *
 * Erzeugt glTF 2.0 binaer im Rahmen des Vertrags (docs/indoor.md, 7.3):
 * nur Kernmaterial mit `metallicFactor = 0`, keine Texturen, keine
 * Erweiterungen ausser optional `KHR_materials_unlit`, Attribute `POSITION`,
 * `NORMAL`, optional `COLOR_0`, Indizes 16 oder 32 Bit. Dazu Knoten-
 * Animationen (Drehungen, `LINEAR`) — genug fuer den eingebauten Fahrer und
 * die Testszene, die die Darstellung **ohne Dateien** braucht
 * (`WorldRenderer.loadTestScene`). Geladen wird das Ergebnis wie jede Kachel
 * ueber gltfio mit dem Ubershader — ein einziger Ladeweg.
 */
class GlbWriter {
    /** Material: Grundfarbe linear RGBA. */
    data class Material(
        val name: String,
        val color: FloatArray,
        val roughness: Float = 0.8f,
        val blend: Boolean = false,
        val doubleSided: Boolean = false,
        val unlit: Boolean = false,
    )

    /** Ein Netz-Teil: Dreiecke mit einem Material. */
    class Primitive(
        val positions: FloatArray,
        val normals: FloatArray,
        val indices: IntArray,
        val material: Int,
        val colors: FloatArray? = null,
    )

    /**
     * Ein Knoten. [rotation] als Quaternion `(x, y, z, w)`.
     * [children] verweisen auf Knotenindizes.
     */
    class Node(
        val name: String,
        val mesh: Int? = null,
        val translation: DoubleArray? = null,
        val rotation: DoubleArray? = null,
        val children: MutableList<Int> = mutableListOf(),
    )

    /** Drehkanal: je Schluesselzeit eine Quaternion `(x, y, z, w)`. */
    class RotationChannel(val node: Int, val times: FloatArray, val quaternions: FloatArray)

    class Animation(val name: String, val channels: List<RotationChannel>)

    private val materials = mutableListOf<Material>()
    private val meshes = mutableListOf<Pair<String, List<Primitive>>>()
    private val nodes = mutableListOf<Node>()
    private val animations = mutableListOf<Animation>()
    private val roots = mutableListOf<Int>()

    fun material(m: Material): Int {
        materials += m
        return materials.size - 1
    }

    fun mesh(name: String, primitives: List<Primitive>): Int {
        require(primitives.isNotEmpty()) { "Netz $name ohne Dreiecke" }
        meshes += name to primitives
        return meshes.size - 1
    }

    /** Neuer Knoten; mit [parent] als Kind, sonst als Wurzel der Szene. */
    fun node(node: Node, parent: Int? = null): Int {
        nodes += node
        val i = nodes.size - 1
        if (parent == null) roots += i else nodes[parent].children += i
        return i
    }

    fun animation(a: Animation) {
        animations += a
    }

    /** Zahl der Dreiecke ueber alle Netze. */
    fun triangleCount(): Int = meshes.sumOf { (_, ps) -> ps.sumOf { it.indices.size / 3 } }

    fun build(): ByteArray {
        val bin = BinBuffer()
        val accessors = mutableListOf<JsonObject>()
        val views = mutableListOf<JsonObject>()

        fun view(offset: Int, length: Int, target: Int?): Int {
            views += buildJsonObject {
                put("buffer", 0)
                put("byteOffset", offset)
                put("byteLength", length)
                if (target != null) put("target", target)
            }
            return views.size - 1
        }

        fun floatAccessor(data: FloatArray, comps: Int, target: Int?, withBounds: Boolean): Int {
            val off = bin.floats(data)
            val v = view(off, data.size * 4, target)
            val count = data.size / comps
            accessors += buildJsonObject {
                put("bufferView", v)
                put("componentType", 5126)
                put("count", count)
                put("type", when (comps) { 1 -> "SCALAR"; 3 -> "VEC3"; 4 -> "VEC4"; else -> error("comps") })
                if (withBounds) {
                    val mn = FloatArray(comps) { Float.POSITIVE_INFINITY }
                    val mx = FloatArray(comps) { Float.NEGATIVE_INFINITY }
                    for (i in 0 until count) for (c in 0 until comps) {
                        val x = data[i * comps + c]
                        if (x < mn[c]) mn[c] = x
                        if (x > mx[c]) mx[c] = x
                    }
                    putJsonArray("min") { mn.forEach { add(it) } }
                    putJsonArray("max") { mx.forEach { add(it) } }
                }
            }
            return accessors.size - 1
        }

        fun indexAccessor(data: IntArray, vertexCount: Int): Int {
            val short = vertexCount <= 65535
            val off = if (short) bin.shorts(data) else bin.ints(data)
            val v = view(off, data.size * (if (short) 2 else 4), 34963)
            accessors += buildJsonObject {
                put("bufferView", v)
                put("componentType", if (short) 5123 else 5125)
                put("count", data.size)
                put("type", "SCALAR")
            }
            return accessors.size - 1
        }

        val meshJson = meshes.map { (name, prims) ->
            buildJsonObject {
                put("name", name)
                putJsonArray("primitives") {
                    prims.forEach { p ->
                        require(p.positions.size == p.normals.size) { "Normalen passen nicht zu den Punkten ($name)" }
                        val vc = p.positions.size / 3
                        val pos = floatAccessor(p.positions, 3, 34962, withBounds = true)
                        val nor = floatAccessor(p.normals, 3, 34962, withBounds = false)
                        val col = p.colors?.let {
                            require(it.size == vc * 4) { "Farben passen nicht zu den Punkten ($name)" }
                            floatAccessor(it, 4, 34962, withBounds = false)
                        }
                        val idx = indexAccessor(p.indices, vc)
                        addJsonObject {
                            putJsonObject("attributes") {
                                put("POSITION", pos)
                                put("NORMAL", nor)
                                if (col != null) put("COLOR_0", col)
                            }
                            put("indices", idx)
                            put("material", p.material)
                            put("mode", 4)
                        }
                    }
                }
            }
        }

        val animJson = animations.map { a ->
            buildJsonObject {
                put("name", a.name)
                val samplers = mutableListOf<JsonObject>()
                val channels = mutableListOf<JsonObject>()
                a.channels.forEach { ch ->
                    require(ch.quaternions.size == ch.times.size * 4) { "Animation ${a.name}: Schluessel passen nicht" }
                    val input = floatAccessor(ch.times, 1, null, withBounds = true)
                    val output = floatAccessor(ch.quaternions, 4, null, withBounds = false)
                    samplers += buildJsonObject {
                        put("input", input)
                        put("output", output)
                        put("interpolation", "LINEAR")
                    }
                    channels += buildJsonObject {
                        put("sampler", samplers.size - 1)
                        putJsonObject("target") {
                            put("node", ch.node)
                            put("path", "rotation")
                        }
                    }
                }
                putJsonArray("samplers") { samplers.forEach { add(it) } }
                putJsonArray("channels") { channels.forEach { add(it) } }
            }
        }

        val usesUnlit = materials.any { it.unlit }
        val json = buildJsonObject {
            putJsonObject("asset") {
                put("version", "2.0")
                put("generator", "Trailscape indoor-render")
            }
            if (usesUnlit) putJsonArray("extensionsUsed") { add("KHR_materials_unlit") }
            put("scene", 0)
            putJsonArray("scenes") { addJsonObject { putJsonArray("nodes") { roots.forEach { add(it) } } } }
            putJsonArray("nodes") {
                nodes.forEach { n ->
                    addJsonObject {
                        put("name", n.name)
                        n.mesh?.let { put("mesh", it) }
                        n.translation?.let { t -> putJsonArray("translation") { t.forEach { add(it) } } }
                        n.rotation?.let { r -> putJsonArray("rotation") { r.forEach { add(it) } } }
                        if (n.children.isNotEmpty()) putJsonArray("children") { n.children.forEach { add(it) } }
                    }
                }
            }
            putJsonArray("meshes") { meshJson.forEach { add(it) } }
            putJsonArray("materials") {
                materials.forEach { m ->
                    addJsonObject {
                        put("name", m.name)
                        putJsonObject("pbrMetallicRoughness") {
                            putJsonArray("baseColorFactor") { m.color.forEach { add(it) } }
                            put("metallicFactor", 0.0)
                            put("roughnessFactor", m.roughness.coerceAtLeast(0.6f))
                        }
                        if (m.blend) put("alphaMode", "BLEND")
                        if (m.doubleSided) put("doubleSided", true)
                        if (m.unlit) putJsonObject("extensions") { putJsonObject("KHR_materials_unlit") { } }
                    }
                }
            }
            putJsonArray("accessors") { accessors.forEach { add(it) } }
            putJsonArray("bufferViews") { views.forEach { add(it) } }
            putJsonArray("buffers") { addJsonObject { put("byteLength", bin.size) } }
            if (animJson.isNotEmpty()) putJsonArray("animations") { animJson.forEach { add(it) } }
        }
        return glb(json.toString().toByteArray(Charsets.UTF_8), bin.bytes())
    }

    private class BinBuffer {
        private val out = ByteArrayOutputStream()
        val size: Int get() = out.size()

        private fun align4() {
            while (out.size() % 4 != 0) out.write(0)
        }

        fun floats(a: FloatArray): Int {
            align4()
            val off = out.size()
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            a.forEach { bb.putFloat(it) }
            out.write(bb.array())
            return off
        }

        fun shorts(a: IntArray): Int {
            align4()
            val off = out.size()
            val bb = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            a.forEach { bb.putShort(it.toShort()) }
            out.write(bb.array())
            return off
        }

        fun ints(a: IntArray): Int {
            align4()
            val off = out.size()
            val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            a.forEach { bb.putInt(it) }
            out.write(bb.array())
            return off
        }

        fun bytes(): ByteArray {
            align4()
            return out.toByteArray()
        }
    }

    companion object {
        private const val MAGIC = 0x46546C67 // "glTF"
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942

        /** Setzt JSON- und Binaerteil zu einer GLB-Datei zusammen (Kopf, zwei Stuecke, 4-Byte-Ausrichtung). */
        fun glb(json: ByteArray, bin: ByteArray): ByteArray {
            val jsonPad = (4 - json.size % 4) % 4
            val binPad = (4 - bin.size % 4) % 4
            val jsonLen = json.size + jsonPad
            val binLen = bin.size + binPad
            val total = 12 + 8 + jsonLen + (if (bin.isNotEmpty()) 8 + binLen else 0)
            val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(MAGIC)
            bb.putInt(2)
            bb.putInt(total)
            bb.putInt(jsonLen)
            bb.putInt(CHUNK_JSON)
            bb.put(json)
            repeat(jsonPad) { bb.put(0x20) }
            if (bin.isNotEmpty()) {
                bb.putInt(binLen)
                bb.putInt(CHUNK_BIN)
                bb.put(bin)
                repeat(binPad) { bb.put(0) }
            }
            return bb.array()
        }
    }
}
