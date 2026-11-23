package de.trailscape.core.indoor.world

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * # glTF 2.0 binaer schreiben
 *
 * [GlbScene] sammelt Texturen, Materialien, Knoten, Netze und Animationen und
 * schreibt sie als eine GLB-Datei nach docs/indoor.md 7.3: nur Kernmaterial
 * mit `metallicFactor = 0` und `roughnessFactor ≥ 0,6`, optional
 * `KHR_materials_unlit`, Texturen eingebettet (Baseline-JPEG oder PNG,
 * Zweierpotenzen ≤ 2048). Netze werden beim [GlbScene.addMesh] sofort in den
 * Binaerpuffer kopiert — der [MeshBuilder] kann danach geleert und
 * weiterverwendet werden.
 */

internal enum class GlbAlphaMode { OPAQUE, MASK, BLEND }

/**
 * Ein Material. [baseColor] RGBA linear `[0, 1]`, [texture] Index aus
 * [GlbScene.addTexture], [emissive] RGB linear oder `null`.
 */
internal class GlbMaterial(
    val name: String,
    val baseColor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val texture: Int? = null,
    val roughness: Float = 0.9f,
    val alphaMode: GlbAlphaMode = GlbAlphaMode.OPAQUE,
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
    val unlit: Boolean = false,
    val emissive: FloatArray? = null,
)

internal enum class GlbPath(val key: String, val width: Int) {
    TRANSLATION("translation", 3),
    ROTATION("rotation", 4),
    SCALE("scale", 3),
}

internal enum class GlbInterpolation { LINEAR, STEP }

/**
 * Ein Animationskanal: [times] streng steigend in Sekunden, [values] je
 * Zeitpunkt [GlbPath.width] Werte (Drehungen als Quaternion `x, y, z, w`).
 */
internal class GlbChannel(
    val node: Int,
    val path: GlbPath,
    val times: FloatArray,
    val values: FloatArray,
    val interpolation: GlbInterpolation = GlbInterpolation.LINEAR,
)

private const val GL_FLOAT = 5126
private const val GL_UNSIGNED_BYTE = 5121
private const val GL_UNSIGNED_SHORT = 5123
private const val GL_UNSIGNED_INT = 5125
private const val GL_ARRAY_BUFFER = 34962
private const val GL_ELEMENT_ARRAY_BUFFER = 34963
private const val GL_LINEAR = 9729
private const val GL_LINEAR_MIPMAP_LINEAR = 9987
private const val GL_REPEAT = 10497
private const val GL_CLAMP_TO_EDGE = 33071

internal const val GLB_GENERATOR = "Trailscape indoor-world"
internal const val KHR_MATERIALS_UNLIT = "KHR_materials_unlit"

/** Wachsender Little-Endian-Binaerpuffer. */
private class BinWriter {
    var bytes = ByteArray(1 shl 16)
        private set
    var size = 0
        private set

    fun ensure(extra: Int) {
        val need = size + extra
        if (need <= bytes.size) return
        var cap = bytes.size
        while (cap < need) cap = if (cap >= (1 shl 30)) need else cap * 2
        bytes = bytes.copyOf(cap)
    }

    fun align4() {
        val pad = (4 - (size and 3)) and 3
        ensure(pad)
        repeat(pad) { bytes[size++] = 0 }
    }

    private fun view(n: Int): ByteBuffer {
        ensure(n)
        val bb = ByteBuffer.wrap(bytes, size, n).order(ByteOrder.LITTLE_ENDIAN)
        size += n
        return bb
    }

    fun floats(src: FloatArray, count: Int) {
        view(count * 4).asFloatBuffer().put(src, 0, count)
    }

    fun shorts(src: ShortArray, count: Int) {
        view(count * 2).asShortBuffer().put(src, 0, count)
    }

    fun ints(src: IntArray, count: Int) {
        view(count * 4).asIntBuffer().put(src, 0, count)
    }

    fun raw(src: ByteArray, count: Int = src.size) {
        ensure(count)
        System.arraycopy(src, 0, bytes, size, count)
        size += count
    }
}

/** Schluessel fuer inhaltsgleiche Bytes (Texturen nicht doppelt einbetten). */
private class ContentKey(val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean = other is ContentKey && other.hash == hash && other.bytes.contentEquals(bytes)
}

internal class GlbScene {
    private class ImageEntry(val bufferView: Int, val mime: String, val width: Int, val height: Int)
    private class NodeEntry(
        val name: String,
        val parent: Int?,
        val translation: Vec3?,
        val rotation: Quat?,
        val scale: Vec3?,
    ) {
        val children = ArrayList<Int>()
        val primitives = ArrayList<JsonObject>()
    }

    private val bin = BinWriter()
    private val bufferViews = ArrayList<JsonObject>()
    private val accessors = ArrayList<JsonObject>()
    private val images = ArrayList<ImageEntry>()
    private val imageByContent = HashMap<ContentKey, Int>()
    private val textures = ArrayList<IntArray>() // (image, sampler)
    private val textureByKey = HashMap<Long, Int>()
    private val samplers = arrayOfNulls<Int>(2) // [clamp, repeat] → Sampler-Index
    private val samplerList = ArrayList<Boolean>() // repeat je Sampler
    private val materials = ArrayList<JsonObject>()
    private val materialNames = ArrayList<String>()
    private var anyUnlit = false
    private val nodes = ArrayList<NodeEntry>()
    private val animations = ArrayList<JsonObject>()
    private val timeAccessors = HashMap<ContentKey, Int>()

    /** Dreiecke aller bisher hinzugefuegten Netze. */
    var triangleCount = 0
        private set

    val nodeCount: Int get() = nodes.size
    val materialCount: Int get() = materials.size

    /**
     * Bettet ein Bild ein und gibt den Texturindex zurueck. Gleiche Bytes
     * werden nur einmal eingebettet, gleiche Bytes mit gleichem Sampler
     * ergeben denselben Index. Verlangt PNG oder Baseline-JPEG mit
     * Zweierpotenz-Kanten ≤ 2048 (docs/indoor.md 7.3).
     */
    fun addTexture(bytes: ByteArray, mime: String, repeat: Boolean): Int {
        val info = requireNotNull(imageInfo(bytes)) { "Textur ist weder PNG noch JPEG" }
        require(info.format.mime == mime) { "MIME-Typ $mime passt nicht zum Inhalt (${info.format.mime})" }
        require(info.baseline) { "JPEG ist nicht Baseline (progressiv oder erweitert)" }
        require(isPow2Max2048(info.width) && isPow2Max2048(info.height)) {
            "Texturkanten muessen Zweierpotenzen bis 2048 sein: ${info.width}x${info.height}"
        }
        val key = ContentKey(bytes)
        val image = imageByContent.getOrPut(key) {
            bin.align4()
            val off = bin.size
            bin.raw(bytes)
            val view = addBufferView(off, bytes.size, null)
            images.add(ImageEntry(view, mime, info.width, info.height))
            images.size - 1
        }
        val sampler = samplers[if (repeat) 1 else 0] ?: run {
            samplerList.add(repeat)
            (samplerList.size - 1).also { samplers[if (repeat) 1 else 0] = it }
        }
        val tk = (image.toLong() shl 8) or sampler.toLong()
        return textureByKey.getOrPut(tk) {
            textures.add(intArrayOf(image, sampler))
            textures.size - 1
        }
    }

    fun addMaterial(m: GlbMaterial): Int {
        require(m.roughness >= 0.6f) { "roughnessFactor ${m.roughness} < 0,6 ist nicht erlaubt (${m.name})" }
        require(m.roughness <= 1f) { "roughnessFactor ${m.roughness} > 1 (${m.name})" }
        require(m.baseColor.size == 4 && m.baseColor.all { it.isFinite() && it in 0f..1f }) { "baseColor braucht 4 Werte in [0, 1] (${m.name})" }
        require(m.emissive == null || (m.emissive.size == 3 && m.emissive.all { it.isFinite() && it in 0f..1f })) { "emissive braucht 3 Werte in [0, 1]" }
        require(m.texture == null || m.texture in textures.indices) { "Unbekannte Textur ${m.texture}" }
        require(m.alphaCutoff >= 0f) { "alphaCutoff negativ" }
        val obj = buildJsonObject {
            put("name", m.name)
            put(
                "pbrMetallicRoughness",
                buildJsonObject {
                    put("baseColorFactor", floatArray(m.baseColor))
                    if (m.texture != null) put("baseColorTexture", buildJsonObject { put("index", m.texture) })
                    put("metallicFactor", 0)
                    put("roughnessFactor", m.roughness)
                },
            )
            if (m.emissive != null) put("emissiveFactor", floatArray(m.emissive))
            if (m.alphaMode != GlbAlphaMode.OPAQUE) put("alphaMode", m.alphaMode.name)
            if (m.alphaMode == GlbAlphaMode.MASK) put("alphaCutoff", m.alphaCutoff)
            if (m.doubleSided) put("doubleSided", true)
            if (m.unlit) put("extensions", buildJsonObject { put(KHR_MATERIALS_UNLIT, JsonObject(emptyMap())) })
        }
        if (m.unlit) anyUnlit = true
        materials.add(obj)
        materialNames.add(m.name)
        return materials.size - 1
    }

    /** Index des ersten Materials mit [name] oder `null`. */
    fun findMaterial(name: String): Int? = materialNames.indexOf(name).takeIf { it >= 0 }

    /** Legt einen Knoten an (Kind von [parent], sonst Wurzel der Szene). */
    fun addNode(
        name: String,
        parent: Int? = null,
        translation: Vec3? = null,
        rotation: Quat? = null,
        scale: Vec3? = null,
    ): Int {
        require(parent == null || parent in nodes.indices) { "Unbekannter Elternknoten $parent" }
        val idx = nodes.size
        nodes.add(NodeEntry(name, parent, translation, rotation?.normalized(), scale))
        if (parent != null) nodes[parent].children.add(idx)
        return idx
    }

    /**
     * Kopiert [mesh] als Primitiv mit [material] an [node]. Leere Netze
     * werden uebersprungen. Indizes 16 Bit, solange es geht.
     */
    fun addMesh(node: Int, mesh: MeshBuilder, material: Int) {
        require(node in nodes.indices) { "Unbekannter Knoten $node" }
        require(material in materials.indices) { "Unbekanntes Material $material" }
        if (mesh.isEmpty) return
        val n = mesh.vertexCount
        val idx = mesh.indices
        val ic = mesh.triangleCount * 3
        for (k in 0 until ic) require(idx[k] in 0 until n) { "Index ${idx[k]} ausserhalb von $n Eckpunkten" }

        // POSITION mit min/max
        val pos = mesh.positions
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until n * 3) {
            val v = pos[i]
            require(v.isFinite()) { "Nicht endliche Lage in Eckpunkt ${i / 3}" }
            val c = i % 3
            if (v < mn[c]) mn[c] = v
            if (v > mx[c]) mx[c] = v
        }
        val posAcc = writeFloatAccessor(pos, n, 3, "VEC3", GL_ARRAY_BUFFER, mn, mx)

        // NORMAL, normiert (der Validator verlangt Einheitslaenge)
        val nrm = FloatArray(n * 3)
        val src = mesh.normals
        for (i in 0 until n) {
            val x = src[i * 3]; val y = src[i * 3 + 1]; val z = src[i * 3 + 2]
            val l = sqrt(x * x + y * y + z * z)
            if (l > 1e-12f && l.isFinite()) {
                nrm[i * 3] = x / l; nrm[i * 3 + 1] = y / l; nrm[i * 3 + 2] = z / l
            } else {
                nrm[i * 3 + 1] = 1f
            }
        }
        val nrmAcc = writeFloatAccessor(nrm, n, 3, "VEC3", GL_ARRAY_BUFFER, null, null)
        val uv = mesh.uvs
        for (i in 0 until n * 2) require(uv[i].isFinite()) { "Nicht endliche Texturkoordinate in Eckpunkt ${i / 2}" }
        val uvAcc = writeFloatAccessor(uv, n, 2, "VEC2", GL_ARRAY_BUFFER, null, null)

        var colAcc = -1
        if (mesh.hasColors) {
            val col = mesh.colors
            val rgba = ByteArray(n * 4)
            for (i in 0 until n) {
                val c = col[i]
                rgba[i * 4] = (c shr 16).toByte()
                rgba[i * 4 + 1] = (c shr 8).toByte()
                rgba[i * 4 + 2] = c.toByte()
                rgba[i * 4 + 3] = (c ushr 24).toByte()
            }
            bin.align4()
            val off = bin.size
            bin.raw(rgba)
            val view = addBufferView(off, rgba.size, GL_ARRAY_BUFFER)
            colAcc = addAccessor(view, GL_UNSIGNED_BYTE, n, "VEC4", normalized = true)
        }

        bin.align4()
        val off = bin.size
        val idxAcc = if (n <= 65535) {
            val s = ShortArray(ic) { idx[it].toShort() }
            bin.shorts(s, ic)
            addAccessor(addBufferView(off, ic * 2, GL_ELEMENT_ARRAY_BUFFER), GL_UNSIGNED_SHORT, ic, "SCALAR")
        } else {
            bin.ints(idx, ic)
            addAccessor(addBufferView(off, ic * 4, GL_ELEMENT_ARRAY_BUFFER), GL_UNSIGNED_INT, ic, "SCALAR")
        }

        val prim = buildJsonObject {
            put(
                "attributes",
                buildJsonObject {
                    put("POSITION", posAcc)
                    put("NORMAL", nrmAcc)
                    put("TEXCOORD_0", uvAcc)
                    if (colAcc >= 0) put("COLOR_0", colAcc)
                },
            )
            put("indices", idxAcc)
            put("material", material)
        }
        nodes[node].primitives.add(prim)
        triangleCount += mesh.triangleCount
    }

    /** Fuegt eine Animation aus [channels] hinzu (Zeiten gleicher Kanaele werden geteilt). */
    fun addAnimation(name: String, channels: List<GlbChannel>) {
        require(channels.isNotEmpty()) { "Animation $name ohne Kanaele" }
        val samplersJson = ArrayList<JsonObject>()
        val channelsJson = ArrayList<JsonObject>()
        for (ch in channels) {
            require(ch.node in nodes.indices) { "Animation $name: unbekannter Knoten ${ch.node}" }
            val t = ch.times
            require(t.isNotEmpty()) { "Animation $name: keine Zeitpunkte" }
            for (i in t.indices) {
                require(t[i].isFinite() && t[i] >= 0f) { "Animation $name: ungueltige Zeit ${t[i]}" }
                if (i > 0) require(t[i] > t[i - 1]) { "Animation $name: Zeiten nicht streng steigend" }
            }
            val w = ch.path.width
            require(ch.values.size == t.size * w) { "Animation $name: ${ch.values.size} Werte statt ${t.size * w}" }
            require(ch.values.all { it.isFinite() }) { "Animation $name: nicht endliche Werte" }
            val values = if (ch.path == GlbPath.ROTATION) {
                FloatArray(ch.values.size).also { out ->
                    for (k in 0 until t.size) {
                        val x = ch.values[k * 4]; val y = ch.values[k * 4 + 1]; val z = ch.values[k * 4 + 2]; val q = ch.values[k * 4 + 3]
                        val l = sqrt(x * x + y * y + z * z + q * q)
                        require(l > 1e-6f) { "Animation $name: Quaternion der Laenge 0" }
                        out[k * 4] = x / l; out[k * 4 + 1] = y / l; out[k * 4 + 2] = z / l; out[k * 4 + 3] = q / l
                    }
                }
            } else {
                ch.values
            }
            val inAcc = timeAccessors.getOrPut(ContentKey(floatsToBytes(t))) {
                writeFloatAccessor(t, t.size, 1, "SCALAR", null, floatArrayOf(t.first()), floatArrayOf(t.last()))
            }
            val outAcc = writeFloatAccessor(values, t.size, w, if (w == 4) "VEC4" else "VEC3", null, null, null)
            samplersJson.add(
                buildJsonObject {
                    put("input", inAcc)
                    put("output", outAcc)
                    put("interpolation", ch.interpolation.name)
                },
            )
            channelsJson.add(
                buildJsonObject {
                    put("sampler", samplersJson.size - 1)
                    put("target", buildJsonObject { put("node", ch.node); put("path", ch.path.key) })
                },
            )
        }
        animations.add(
            buildJsonObject {
                put("name", name)
                put("channels", JsonArray(channelsJson))
                put("samplers", JsonArray(samplersJson))
            },
        )
    }

    /** GPU-Speicher aller eingebetteten Bilder ([textureGpuBytes] je Bild). */
    fun textureGpuBytes(): Long = images.sumOf { textureGpuBytes(it.width, it.height) }

    /** Schreibt die GLB-Datei. */
    fun toBytes(): ByteArray {
        val meshes = ArrayList<JsonObject>()
        val nodeJson = nodes.map { nd ->
            buildJsonObject {
                put("name", nd.name)
                if (nd.children.isNotEmpty()) put("children", buildJsonArray { nd.children.forEach { add(JsonPrimitive(it)) } })
                if (nd.primitives.isNotEmpty()) {
                    meshes.add(buildJsonObject { put("name", nd.name); put("primitives", JsonArray(nd.primitives)) })
                    put("mesh", meshes.size - 1)
                }
                nd.translation?.let { put("translation", floatArray(floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()))) }
                nd.rotation?.let {
                    put("rotation", floatArray(floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat(), it.w.toFloat())))
                }
                nd.scale?.let { put("scale", floatArray(floatArrayOf(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()))) }
            }
        }
        val roots = nodes.indices.filter { nodes[it].parent == null }
        val json = buildJsonObject {
            put("asset", buildJsonObject { put("version", "2.0"); put("generator", GLB_GENERATOR) })
            if (anyUnlit) put("extensionsUsed", buildJsonArray { add(JsonPrimitive(KHR_MATERIALS_UNLIT)) })
            put("scene", 0)
            put(
                "scenes",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            if (roots.isNotEmpty()) put("nodes", buildJsonArray { roots.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                },
            )
            putList("nodes", nodeJson)
            putList("meshes", meshes)
            putList("materials", materials)
            putList(
                "textures",
                textures.map { buildJsonObject { put("sampler", it[1]); put("source", it[0]) } },
            )
            putList(
                "samplers",
                samplerList.map { repeat ->
                    buildJsonObject {
                        put("magFilter", GL_LINEAR)
                        put("minFilter", GL_LINEAR_MIPMAP_LINEAR)
                        put("wrapS", if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE)
                        put("wrapT", if (repeat) GL_REPEAT else GL_CLAMP_TO_EDGE)
                    }
                },
            )
            putList("images", images.map { buildJsonObject { put("bufferView", it.bufferView); put("mimeType", it.mime) } })
            putList("accessors", accessors)
            putList("bufferViews", bufferViews)
            if (bin.size > 0) put("buffers", buildJsonArray { add(buildJsonObject { put("byteLength", bin.size) }) })
            putList("animations", animations)
        }
        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonLen = (jsonBytes.size + 3) and 3.inv()
        val binLen = (bin.size + 3) and 3.inv()
        val total = 12 + 8 + jsonLen + (if (bin.size > 0) 8 + binLen else 0)
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67) // "glTF"
        out.putInt(2)
        out.putInt(total)
        out.putInt(jsonLen)
        out.putInt(0x4E4F534A) // "JSON"
        out.put(jsonBytes)
        repeat(jsonLen - jsonBytes.size) { out.put(0x20) }
        if (bin.size > 0) {
            out.putInt(binLen)
            out.putInt(0x004E4942) // "BIN\0"
            out.put(bin.bytes, 0, bin.size)
            repeat(binLen - bin.size) { out.put(0) }
        }
        return out.array()
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putList(key: String, list: List<JsonElement>) {
        if (list.isNotEmpty()) put(key, JsonArray(list))
    }

    private fun addBufferView(offset: Int, length: Int, target: Int?): Int {
        bufferViews.add(
            buildJsonObject {
                put("buffer", 0)
                put("byteOffset", offset)
                put("byteLength", length)
                if (target != null) put("target", target)
            },
        )
        return bufferViews.size - 1
    }

    private fun addAccessor(
        view: Int,
        componentType: Int,
        count: Int,
        type: String,
        min: FloatArray? = null,
        max: FloatArray? = null,
        normalized: Boolean = false,
    ): Int {
        accessors.add(
            buildJsonObject {
                put("bufferView", view)
                put("componentType", componentType)
                if (normalized) put("normalized", true)
                put("count", count)
                put("type", type)
                if (min != null) put("min", floatArray(min))
                if (max != null) put("max", floatArray(max))
            },
        )
        return accessors.size - 1
    }

    private fun writeFloatAccessor(
        data: FloatArray,
        count: Int,
        width: Int,
        type: String,
        target: Int?,
        min: FloatArray?,
        max: FloatArray?,
    ): Int {
        bin.align4()
        val off = bin.size
        bin.floats(data, count * width)
        return addAccessor(addBufferView(off, count * width * 4, target), GL_FLOAT, count, type, min, max)
    }

    private fun floatsToBytes(f: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        b.asFloatBuffer().put(f)
        return b.array()
    }

    private fun floatArray(v: FloatArray): JsonArray = buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }

    private fun isPow2Max2048(v: Int): Boolean = v in 1..2048 && (v and (v - 1)) == 0
}
