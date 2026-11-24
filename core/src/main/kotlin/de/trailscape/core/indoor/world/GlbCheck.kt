package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * # GLB lesen und gegen den Vertrag pruefen
 *
 * [parseGlb] liest eine GLB-Datei in eine Uebersicht ([GlbInfo]);
 * [checkTileGlb] und [checkModelGlb] pruefen sie gegen docs/indoor.md 7.3
 * und 7.5 und liefern deutsche Verstosstexte — eine leere Liste heisst in
 * Ordnung. Benutzt vom Entwicklerwerkzeug und von den Tests.
 */

/** Ein Knoten der GLB. */
class GlbNodeInfo(
    val index: Int,
    val name: String?,
    val parent: Int?,
    val children: List<Int>,
    val mesh: Int?,
    /** Der Knoten hat `translation`, `rotation`, `scale` oder `matrix`. */
    val hasTransform: Boolean,
)

/** Ein Material der GLB (fehlende Faktoren mit den glTF-Vorgaben). */
class GlbMaterialInfo(
    val name: String?,
    val metallicFactor: Double,
    val roughnessFactor: Double,
    val alphaMode: String,
    val doubleSided: Boolean,
    val unlit: Boolean,
    val baseColorTexture: Int?,
)

/**
 * Ein eingebettetes Bild.
 *
 * @property format `"png"`, `"jpeg"` oder `null` (unbekannt/unlesbar).
 * @property baseline JPEG mit `SOF0`-Rahmen bzw. PNG.
 */
class GlbImageInfo(
    val index: Int,
    val mimeType: String?,
    val format: String?,
    val width: Int,
    val height: Int,
    val progressive: Boolean,
    val baseline: Boolean,
    val byteLength: Int,
)

/** Eine Animation mit ihrer Laufzeit (groesster Zeitpunkt aller Kanaele). */
class GlbAnimationInfo(val name: String?, val startS: Double, val durationS: Double, val channelCount: Int)

/** Uebersicht ueber eine GLB-Datei. */
class GlbInfo(
    val generator: String?,
    val nodes: List<GlbNodeInfo>,
    /** Wurzelknoten der Standardszene. */
    val roots: List<Int>,
    val sceneCount: Int,
    val meshCount: Int,
    val materials: List<GlbMaterialInfo>,
    val images: List<GlbImageInfo>,
    val textureCount: Int,
    val extensionsUsed: List<String>,
    val extensionsRequired: List<String>,
    val animations: List<GlbAnimationInfo>,
    val cameraCount: Int,
    val skinCount: Int,
    /** Dreiecke je Knoten-Verwendung eines Netzes, summiert. */
    val triangleCount: Int,
    /** GPU-Speicher der Bilder nach [textureGpuBytes]. */
    val textureGpuBytes: Long,
    /** Achsparalleler Umriss in Szenenkoordinaten `[minX, minY, minZ]`, `null` ohne Netze. */
    val boundsMin: DoubleArray?,
    val boundsMax: DoubleArray?,
    val byteLength: Int,
) {
    fun node(name: String): GlbNodeInfo? = nodes.firstOrNull { it.name == name }
    fun animation(name: String): GlbAnimationInfo? = animations.firstOrNull { it.name == name }
}

/**
 * Liest [bytes] als GLB.
 *
 * @throws IllegalArgumentException wenn es keine lesbare GLB ist (Kopf, Bloecke, JSON).
 */
fun parseGlb(bytes: ByteArray): GlbInfo = ParsedGlb.read(bytes).info

/** Prueft eine Kachel-GLB gegen docs/indoor.md 7.3; leer = in Ordnung. */
fun checkTileGlb(bytes: ByteArray): List<String> {
    val p = try {
        ParsedGlb.read(bytes)
    } catch (e: IllegalArgumentException) {
        return listOf("Keine gueltige GLB: ${e.message}")
    }
    return GlbChecker(p, model = false).run()
}

/**
 * Prueft ein Modell (Fahrer, Geist) gegen docs/indoor.md 7.3 und 7.5: dazu
 * Animationen [WorldModels.ANIM_PEDAL] und [WorldModels.ANIM_ROLL] von genau
 * 1,0 s, hoechstens [maxTriangles] Dreiecke und [maxBytes] Bytes.
 */
fun checkModelGlb(bytes: ByteArray, maxTriangles: Int = 60_000, maxBytes: Int = 2 * 1024 * 1024): List<String> {
    val p = try {
        ParsedGlb.read(bytes)
    } catch (e: IllegalArgumentException) {
        return listOf("Keine gueltige GLB: ${e.message}")
    }
    val out = GlbChecker(p, model = true).run().toMutableList()
    if (p.info.triangleCount > maxTriangles) out.add("Modell hat ${p.info.triangleCount} Dreiecke, erlaubt sind hoechstens $maxTriangles")
    if (bytes.size > maxBytes) out.add("Modell ist ${bytes.size} Bytes gross, erlaubt sind hoechstens $maxBytes")
    return out
}

// ---------------------------------------------------------------------------
// Lesen
// ---------------------------------------------------------------------------

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): JsonArray? = this as? JsonArray
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
private fun JsonElement?.int(): Int? = num()?.takeIf { it == Math.floor(it) && abs(it) < Int.MAX_VALUE }?.toInt()
private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
private fun JsonObject.list(key: String): List<JsonObject> = this[key].arr()?.mapNotNull { it.obj() } ?: emptyList()

private fun componentSize(ct: Int): Int = when (ct) {
    5120, 5121 -> 1
    5122, 5123 -> 2
    5125, 5126 -> 4
    else -> 0
}

private fun typeWidth(type: String?): Int = when (type) {
    "SCALAR" -> 1
    "VEC2" -> 2
    "VEC3" -> 3
    "VEC4" -> 4
    "MAT2" -> 4
    "MAT3" -> 9
    "MAT4" -> 16
    else -> 0
}

/** Lage eines Accessors im BIN-Block, falls gueltig. */
private class AccessorView(val offset: Int, val stride: Int, val count: Int, val width: Int, val componentType: Int, val normalized: Boolean)

private class ParsedGlb(val json: JsonObject, val bin: ByteArray?, val byteLength: Int) {
    lateinit var info: GlbInfo

    val nodes = json.list("nodes")
    val meshes = json.list("meshes")
    val accessors = json.list("accessors")
    val bufferViews = json.list("bufferViews")
    val buffers = json.list("buffers")
    val imagesJson = json.list("images")

    /** Ergebnis von [accessorView]; `null` + Fehlertext, wenn ungueltig. */
    fun accessorView(index: Int): Pair<AccessorView?, String?> {
        val a = accessors.getOrNull(index) ?: return null to "Accessor $index fehlt"
        val ct = a["componentType"].int() ?: return null to "Accessor $index ohne componentType"
        val cs = componentSize(ct)
        if (cs == 0) return null to "Accessor $index: unbekannter componentType $ct"
        val w = typeWidth(a["type"].str())
        if (w == 0) return null to "Accessor $index: unbekannter type ${a["type"]}"
        val count = a["count"].int() ?: return null to "Accessor $index ohne count"
        if (count < 1) return null to "Accessor $index: count $count"
        val viewIdx = a["bufferView"].int() ?: return null to "Accessor $index ohne bufferView"
        val v = bufferViews.getOrNull(viewIdx) ?: return null to "Accessor $index: bufferView $viewIdx fehlt"
        val vr = viewRange(viewIdx) ?: return null to "bufferView $viewIdx liegt nicht im eingebetteten Puffer"
        val elem = cs * w
        val stride = v["byteStride"].int() ?: elem
        if (stride < elem) return null to "bufferView $viewIdx: byteStride $stride kleiner als Element ($elem)"
        val aoff = a["byteOffset"].int() ?: 0
        if (aoff < 0 || aoff % cs != 0) return null to "Accessor $index: byteOffset $aoff nicht ausgerichtet"
        val need = aoff.toLong() + stride.toLong() * (count - 1) + elem
        if (need > vr.second) return null to "Accessor $index ragt ueber bufferView $viewIdx hinaus ($need > ${vr.second} Bytes)"
        if ((vr.first + aoff) % cs != 0) return null to "Accessor $index: Daten nicht auf $cs Bytes ausgerichtet"
        return AccessorView(vr.first + aoff, stride, count, w, ct, a["normalized"].bool() == true) to null
    }

    /** (Anfang, Laenge) einer bufferView im BIN-Block. */
    fun viewRange(viewIdx: Int): Pair<Int, Int>? {
        val v = bufferViews.getOrNull(viewIdx) ?: return null
        val b = v["buffer"].int() ?: return null
        if (b != 0 || bin == null) return null
        val buf = buffers.getOrNull(0) ?: return null
        if (buf["uri"] != null) return null
        val off = v["byteOffset"].int() ?: 0
        val len = v["byteLength"].int() ?: return null
        val bufLen = buf["byteLength"].int() ?: return null
        if (off < 0 || len < 0 || off.toLong() + len > bufLen || off.toLong() + len > bin.size) return null
        return off to len
    }

    private val bb: ByteBuffer by lazy { ByteBuffer.wrap(bin ?: ByteArray(0)).order(ByteOrder.LITTLE_ENDIAN) }

    fun read(view: AccessorView, element: Int, component: Int): Double {
        val o = view.offset + element * view.stride + component * componentSize(view.componentType)
        return when (view.componentType) {
            5120 -> bb.get(o).toDouble()
            5121 -> (bb.get(o).toInt() and 0xFF).toDouble()
            5122 -> bb.getShort(o).toDouble()
            5123 -> (bb.getShort(o).toInt() and 0xFFFF).toDouble()
            5125 -> (bb.getInt(o).toLong() and 0xFFFFFFFFL).toDouble()
            else -> bb.getFloat(o).toDouble()
        }
    }

    fun imageBytes(img: JsonObject): ByteArray? {
        val vi = img["bufferView"].int() ?: return null
        val r = viewRange(vi) ?: return null
        return bin!!.copyOfRange(r.first, r.first + r.second)
    }

    companion object {
        fun read(bytes: ByteArray): ParsedGlb {
            require(bytes.size >= 20) { "Datei zu kurz (${bytes.size} Bytes)" }
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bb.getInt(0) == 0x46546C67) { "Kennung \"glTF\" fehlt" }
            require(bb.getInt(4) == 2) { "Version ${bb.getInt(4)} statt 2" }
            val total = bb.getInt(8)
            require(total.toLong() <= bytes.size && total >= 20) { "Laengenangabe $total passt nicht zur Datei (${bytes.size})" }
            val jsonLen = bb.getInt(12)
            require(bb.getInt(16) == 0x4E4F534A) { "Erster Block ist kein JSON" }
            require(jsonLen >= 0 && 20L + jsonLen <= total) { "JSON-Block ragt ueber das Dateiende" }
            val jsonText = String(bytes, 20, jsonLen, Charsets.UTF_8)
            val json = try {
                Json.parseToJsonElement(jsonText) as? JsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("JSON unlesbar: ${e.message}", e)
            } ?: throw IllegalArgumentException("JSON ist kein Objekt")
            var p = 20 + jsonLen
            var bin: ByteArray? = null
            if (p + 8 <= total) {
                val binLen = bb.getInt(p)
                val type = bb.getInt(p + 4)
                require(binLen >= 0 && p.toLong() + 8 + binLen <= total) { "BIN-Block ragt ueber das Dateiende" }
                if (type == 0x004E4942) bin = bytes.copyOfRange(p + 8, p + 8 + binLen)
                p += 8 + binLen
            }
            val parsed = ParsedGlb(json, bin, total)
            parsed.info = parsed.summarize()
            return parsed
        }
    }

    private fun summarize(): GlbInfo {
        val parent = arrayOfNulls<Int>(nodes.size)
        nodes.forEachIndexed { i, n -> n["children"].arr()?.forEach { c -> c.int()?.takeIf { it in nodes.indices }?.let { parent[it] = i } } }
        val nodeInfos = nodes.mapIndexed { i, n ->
            GlbNodeInfo(
                index = i,
                name = n["name"].str(),
                parent = parent[i],
                children = n["children"].arr()?.mapNotNull { it.int() } ?: emptyList(),
                mesh = n["mesh"].int(),
                hasTransform = listOf("translation", "rotation", "scale", "matrix").any { n.containsKey(it) },
            )
        }
        val scenes = json.list("scenes")
        val sceneIdx = json["scene"].int() ?: 0
        val roots = scenes.getOrNull(sceneIdx)?.get("nodes").arr()?.mapNotNull { it.int() } ?: emptyList()
        val mats = json.list("materials").map { m ->
            val pbr = m["pbrMetallicRoughness"].obj()
            GlbMaterialInfo(
                name = m["name"].str(),
                metallicFactor = pbr?.get("metallicFactor").num() ?: 1.0,
                roughnessFactor = pbr?.get("roughnessFactor").num() ?: 1.0,
                alphaMode = m["alphaMode"].str() ?: "OPAQUE",
                doubleSided = m["doubleSided"].bool() ?: false,
                unlit = m["extensions"].obj()?.containsKey(KHR_MATERIALS_UNLIT) == true,
                baseColorTexture = pbr?.get("baseColorTexture").obj()?.get("index").int(),
            )
        }
        val imgs = imagesJson.mapIndexed { i, img ->
            val data = imageBytes(img)
            val ii = data?.let { imageInfo(it) }
            GlbImageInfo(
                index = i,
                mimeType = img["mimeType"].str(),
                format = ii?.format?.name?.lowercase(),
                width = ii?.width ?: 0,
                height = ii?.height ?: 0,
                progressive = ii?.progressive ?: false,
                baseline = ii?.baseline ?: false,
                byteLength = data?.size ?: 0,
            )
        }
        val anims = json.list("animations").map { a ->
            var start = Double.MAX_VALUE
            var end = 0.0
            for (s in a.list("samplers")) {
                val acc = accessors.getOrNull(s["input"].int() ?: -1) ?: continue
                acc["max"].arr()?.firstOrNull().num()?.let { end = max(end, it) }
                acc["min"].arr()?.firstOrNull().num()?.let { start = minOf(start, it) }
            }
            GlbAnimationInfo(a["name"].str(), if (start == Double.MAX_VALUE) 0.0 else start, end, a.list("channels").size)
        }
        // Dreiecke und Umriss je Knoten-Verwendung.
        var tris = 0
        var bmin: DoubleArray? = null
        var bmax: DoubleArray? = null
        val world = arrayOfNulls<Mat4>(nodes.size)
        fun worldOf(i: Int, depth: Int = 0): Mat4 {
            world[i]?.let { return it }
            val local = localMatrix(nodes[i])
            val p = parent[i]
            val m = if (p != null && depth < nodes.size) worldOf(p, depth + 1) * local else local
            world[i] = m
            return m
        }
        for ((i, n) in nodes.withIndex()) {
            val mesh = meshes.getOrNull(n["mesh"].int() ?: continue) ?: continue
            for (prim in mesh.list("primitives")) {
                val mode = prim["mode"].int() ?: 4
                val posIdx = prim["attributes"].obj()?.get("POSITION").int()
                val posAcc = posIdx?.let { accessors.getOrNull(it) }
                val count = prim["indices"].int()?.let { accessors.getOrNull(it)?.get("count").int() } ?: posAcc?.get("count").int() ?: 0
                if (mode == 4) tris += count / 3
                val mn = posAcc?.get("min").arr()?.mapNotNull { it.num() }
                val mx = posAcc?.get("max").arr()?.mapNotNull { it.num() }
                if (mn != null && mx != null && mn.size == 3 && mx.size == 3) {
                    val wm = worldOf(i)
                    for (corner in 0 until 8) {
                        val c = wm.transformPoint(
                            Vec3(
                                if (corner and 1 == 0) mn[0] else mx[0],
                                if (corner and 2 == 0) mn[1] else mx[1],
                                if (corner and 4 == 0) mn[2] else mx[2],
                            ),
                        )
                        val lo = bmin ?: doubleArrayOf(c.x, c.y, c.z).also { bmin = it }
                        val hi = bmax ?: doubleArrayOf(c.x, c.y, c.z).also { bmax = it }
                        lo[0] = minOf(lo[0], c.x); lo[1] = minOf(lo[1], c.y); lo[2] = minOf(lo[2], c.z)
                        hi[0] = max(hi[0], c.x); hi[1] = max(hi[1], c.y); hi[2] = max(hi[2], c.z)
                    }
                }
            }
        }
        val gpu = imgs.filter { it.width > 0 && it.height > 0 }.sumOf { textureGpuBytes(it.width, it.height) }
        return GlbInfo(
            generator = json["asset"].obj()?.get("generator").str(),
            nodes = nodeInfos,
            roots = roots,
            sceneCount = scenes.size,
            meshCount = meshes.size,
            materials = mats,
            images = imgs,
            textureCount = json.list("textures").size,
            extensionsUsed = json["extensionsUsed"].arr()?.mapNotNull { it.str() } ?: emptyList(),
            extensionsRequired = json["extensionsRequired"].arr()?.mapNotNull { it.str() } ?: emptyList(),
            animations = anims,
            cameraCount = json.list("cameras").size,
            skinCount = json.list("skins").size,
            triangleCount = tris,
            textureGpuBytes = gpu,
            boundsMin = bmin,
            boundsMax = bmax,
            byteLength = byteLength,
        )
    }

    private fun localMatrix(n: JsonObject): Mat4 {
        n["matrix"].arr()?.mapNotNull { it.num() }?.takeIf { it.size == 16 }?.let { return Mat4(it.toDoubleArray()) }
        val t = n["translation"].arr()?.mapNotNull { it.num() }?.takeIf { it.size == 3 }
        val r = n["rotation"].arr()?.mapNotNull { it.num() }?.takeIf { it.size == 4 }
        val s = n["scale"].arr()?.mapNotNull { it.num() }?.takeIf { it.size == 3 }
        return Mat4.trs(
            t?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ZERO,
            r?.let { Quat(it[0], it[1], it[2], it[3]) } ?: Quat.IDENTITY,
            s?.let { Vec3(it[0], it[1], it[2]) } ?: Vec3.ONE,
        )
    }
}

// ---------------------------------------------------------------------------
// Pruefen
// ---------------------------------------------------------------------------

private val TILE_NODE_NAMES = setOf(
    WorldNames.NODE_TERRAIN, WorldNames.NODE_ROUTE, WorldNames.NODE_ROADS, WorldNames.NODE_BUILDINGS,
    WorldNames.NODE_TREES, WorldNames.NODE_WATER, WorldNames.NODE_PROPS,
)

private val TERRAIN_SUB_NAMES: Set<String> =
    (0 until WORLD_LEVEL_FACTOR).flatMap { i -> (0 until WORLD_LEVEL_FACTOR).map { j -> WorldNames.terrainSubName(i, j) } }.toSet()

private val MATERIAL_NAMES: Set<String> = setOf(
    WorldNames.MAT_TERRAIN, WorldNames.MAT_BUILDING_WALL, WorldNames.MAT_BUILDING_ROOF,
    WorldNames.MAT_TREE_TRUNK, WorldNames.MAT_TREE_LEAVES, WorldNames.MAT_WATER,
) + SurfaceKind.entries.map { WorldNames.MAT_ROUTE_PREFIX + it.textureKey }

private val ALLOWED_ATTRIBUTES = setOf("POSITION", "NORMAL", "TEXCOORD_0", "COLOR_0")
/**
 * Erlaubte Erweiterungen: `KHR_materials_unlit` (Vertrag 7.3) und — Abweichung, siehe
 * docs/indoor/world.md — `KHR_materials_specular` (nur `specularFactor` 0 fuer Vegetation;
 * gltfio 1.75.1 hat dafuer die Ubershader-Variante `specular_*`).
 */
private val ALLOWED_EXTENSIONS = setOf(KHR_MATERIALS_UNLIT, KHR_MATERIALS_SPECULAR)

private class GlbChecker(private val p: ParsedGlb, private val model: Boolean) {
    private val out = ArrayList<String>()
    private val json = p.json

    fun run(): List<String> {
        if (json["asset"].obj()?.get("version").str() != "2.0") out.add("asset.version ist nicht \"2.0\"")
        checkExtensions()
        checkScenes()
        checkNames()
        checkMaterials()
        checkImages()
        checkMeshes()
        if (p.info.cameraCount > 0) out.add("${what()} enthaelt ${p.info.cameraCount} Kamera(s)")
        if (model) {
            checkModelAnimations()
        } else {
            if (p.info.animations.isNotEmpty()) out.add("Kachel enthaelt ${p.info.animations.size} Animation(en)")
            if (p.info.skinCount > 0) out.add("Kachel enthaelt ${p.info.skinCount} Skin(s)")
        }
        checkAnimationData()
        return out
    }

    private fun what() = if (model) "Modell" else "Kachel"

    private fun checkExtensions() {
        for (e in p.info.extensionsUsed) if (e !in ALLOWED_EXTENSIONS) out.add("Verbotene Erweiterung in extensionsUsed: $e")
        for (e in p.info.extensionsRequired) if (e !in ALLOWED_EXTENSIONS) out.add("Verbotene Erweiterung in extensionsRequired: $e")
        // Auch nicht angemeldete Erweiterungen an einzelnen Objekten finden.
        val seen = LinkedHashSet<String>()
        fun walk(e: JsonElement, path: String) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == "extensions" && v is JsonObject) {
                        for (name in v.keys) if (name !in ALLOWED_EXTENSIONS && seen.add(name)) out.add("Verbotene Erweiterung $name an $path")
                        if (KHR_MATERIALS_UNLIT in v.keys && KHR_MATERIALS_UNLIT !in p.info.extensionsUsed && seen.add("unlit-used")) {
                            out.add("$KHR_MATERIALS_UNLIT wird benutzt, fehlt aber in extensionsUsed")
                        }
                    } else {
                        walk(v, "$path/$k")
                    }
                }
                is JsonArray -> e.forEachIndexed { i, v -> walk(v, "$path[$i]") }
                else -> Unit
            }
        }
        walk(json, "")
        if (json["extensions"].obj()?.containsKey("KHR_lights_punctual") == true) out.add("${what()} enthaelt Lichter (KHR_lights_punctual)")
    }

    private fun checkScenes() {
        if (p.info.sceneCount != 1) out.add("Erwartet genau eine Szene, gefunden: ${p.info.sceneCount}")
        if (p.nodes.isNotEmpty() && p.info.roots.isEmpty()) out.add("Die Szene hat keine Wurzelknoten")
        for (r in p.info.roots) if (r !in p.nodes.indices) out.add("Szene verweist auf fehlenden Knoten $r")
        for (n in p.info.nodes) {
            for (c in n.children) if (c !in p.nodes.indices) out.add("Knoten ${n.index} verweist auf fehlendes Kind $c")
            if (n.mesh != null && n.mesh !in p.meshes.indices) out.add("Knoten ${n.index} verweist auf fehlendes Netz ${n.mesh}")
        }
    }

    private fun checkNames() {
        val seen = HashMap<String, Int>()
        for (n in p.info.nodes) {
            val name = n.name
            if (name == null) {
                out.add("Knoten ${n.index} hat keinen Namen")
                continue
            }
            if (model) {
                if (!name.startsWith("x_") && name !in TILE_NODE_NAMES) out.add("Knotenname \"$name\" ist nicht aus WorldNames und beginnt nicht mit x_")
                continue
            }
            val isLayer = name in TILE_NODE_NAMES
            val isSub = name in TERRAIN_SUB_NAMES
            if (!isLayer && !isSub && !name.startsWith("x_")) out.add("Knotenname \"$name\" ist nicht aus WorldNames und beginnt nicht mit x_")
            if (isLayer || isSub) {
                val c = (seen[name] ?: 0) + 1
                seen[name] = c
                if (c == 2) out.add("Knoten \"$name\" kommt mehrfach vor")
            }
            if (isSub && (n.parent == null || p.info.nodes[n.parent].name != WorldNames.NODE_TERRAIN)) {
                out.add("Teilflaeche \"$name\" ist kein Kind von \"${WorldNames.NODE_TERRAIN}\"")
            }
            if (n.hasTransform) out.add("Kachel-Knoten \"$name\" hat eine eigene Transformation")
        }
        for ((i, m) in p.info.materials.withIndex()) {
            val name = m.name
            if (name == null) out.add("Material $i hat keinen Namen")
            else if (name !in MATERIAL_NAMES && !name.startsWith("x_")) out.add("Materialname \"$name\" ist nicht aus WorldNames und beginnt nicht mit x_")
        }
    }

    private fun checkMaterials() {
        val mats = json.list("materials")
        for ((i, m) in p.info.materials.withIndex()) {
            val label = "Material \"${m.name ?: i}\""
            if (m.metallicFactor != 0.0) out.add("$label: metallicFactor ${m.metallicFactor} statt 0")
            if (m.roughnessFactor < 0.6) out.add("$label: roughnessFactor ${m.roughnessFactor} < 0,6")
            if (m.alphaMode !in setOf("OPAQUE", "MASK", "BLEND")) out.add("$label: unbekannter alphaMode ${m.alphaMode}")
            val raw = mats[i]
            if (raw.containsKey("normalTexture")) out.add("$label: normalTexture ist nicht erlaubt")
            if (raw.containsKey("occlusionTexture")) out.add("$label: occlusionTexture ist nicht erlaubt")
            if (raw.containsKey("emissiveTexture")) out.add("$label: emissiveTexture ist nicht erlaubt")
            if (raw["pbrMetallicRoughness"].obj()?.containsKey("metallicRoughnessTexture") == true) {
                out.add("$label: metallicRoughnessTexture ist nicht erlaubt")
            }
            val tex = m.baseColorTexture
            if (tex != null && tex !in 0 until p.info.textureCount) out.add("$label verweist auf fehlende Textur $tex")
            val texCoord = raw["pbrMetallicRoughness"].obj()?.get("baseColorTexture").obj()?.get("texCoord").int() ?: 0
            if (texCoord != 0) out.add("$label: baseColorTexture nutzt TEXCOORD_$texCoord")
        }
        json.list("textures").forEachIndexed { i, t ->
            val src = t["source"].int()
            if (src == null || src !in p.imagesJson.indices) out.add("Textur $i hat kein gueltiges Bild (source)")
        }
    }

    private fun checkImages() {
        for ((i, img) in p.imagesJson.withIndex()) {
            val info = p.info.images[i]
            if (img["uri"] != null) {
                out.add("Bild $i ist nicht in der GLB eingebettet (uri)")
                continue
            }
            if (img["bufferView"].int() == null || info.byteLength == 0) {
                out.add("Bild $i hat keine lesbaren Daten")
                continue
            }
            when {
                info.format == null -> out.add("Bild $i ist weder JPEG noch PNG")
                info.format == "jpeg" && info.progressive -> out.add("Bild $i ist ein progressives JPEG (FF C2)")
                info.format == "jpeg" && !info.baseline -> out.add("Bild $i ist kein Baseline-JPEG")
            }
            if (info.format != null) {
                val expectMime = if (info.format == "png") "image/png" else "image/jpeg"
                if (info.mimeType != expectMime) out.add("Bild $i: mimeType ${info.mimeType} passt nicht zum Inhalt ($expectMime)")
                if (!pow2(info.width) || !pow2(info.height)) out.add("Bild $i: ${info.width}x${info.height} ist keine Zweierpotenz")
                if (info.width > 2048 || info.height > 2048) out.add("Bild $i: ${info.width}x${info.height} ist groesser als 2048")
            }
        }
    }

    private fun pow2(v: Int) = v > 0 && (v and (v - 1)) == 0

    private fun checkMeshes() {
        val checkedAccessors = HashSet<Int>()
        for ((mi, mesh) in p.meshes.withIndex()) {
            val prims = mesh.list("primitives")
            if (prims.isEmpty()) out.add("Netz $mi hat keine Primitive")
            for ((pi, prim) in prims.withIndex()) {
                val label = "Netz \"${mesh["name"].str() ?: mi}\" Primitiv $pi"
                val mode = prim["mode"].int() ?: 4
                if (mode != 4) out.add("$label: mode $mode statt Dreiecke (4)")
                if (prim["material"].int() == null) out.add("$label hat kein Material")
                else if (prim["material"].int()!! !in p.info.materials.indices) out.add("$label verweist auf fehlendes Material")
                if (prim["targets"] != null) out.add("$label hat Morph-Ziele")
                val attrs = prim["attributes"].obj()
                if (attrs == null) {
                    out.add("$label hat keine Attribute")
                    continue
                }
                for (a in attrs.keys) if (a !in ALLOWED_ATTRIBUTES) out.add("$label: Attribut $a ist nicht erlaubt")
                for (req in listOf("POSITION", "NORMAL", "TEXCOORD_0")) if (!attrs.containsKey(req)) out.add("$label: $req fehlt")
                var vertexCount = -1
                for ((name, el) in attrs) {
                    val ai = el.int()
                    if (ai == null) {
                        out.add("$label: Attribut $name ohne Accessor")
                        continue
                    }
                    val (view, err) = p.accessorView(ai)
                    if (view == null) {
                        if (checkedAccessors.add(ai)) out.add("$label: $err")
                        continue
                    }
                    if (vertexCount < 0) vertexCount = view.count
                    else if (view.count != vertexCount) out.add("$label: Attribut $name hat ${view.count} statt $vertexCount Eintraege")
                    val acc = p.accessors[ai]
                    when (name) {
                        "POSITION", "NORMAL" -> {
                            if (view.componentType != 5126 || view.width != 3) out.add("$label: $name muss float VEC3 sein")
                            else if (checkedAccessors.add(ai)) checkFloatData(ai, view, name, acc, label)
                        }
                        "TEXCOORD_0" -> if (view.width != 2) out.add("$label: TEXCOORD_0 muss VEC2 sein")
                        "COLOR_0" -> if (view.width != 4) out.add("$label: COLOR_0 muss RGBA (VEC4) sein")
                    }
                }
                val ii = prim["indices"].int()
                if (ii != null && vertexCount > 0) checkIndices(ii, vertexCount, label)
            }
        }
    }

    private fun checkFloatData(ai: Int, view: AccessorView, name: String, acc: JsonObject, label: String) {
        val mn = DoubleArray(3) { Double.MAX_VALUE }
        val mx = DoubleArray(3) { -Double.MAX_VALUE }
        var bad = 0
        for (e in 0 until view.count) {
            for (c in 0 until 3) {
                val v = p.read(view, e, c)
                if (!v.isFinite()) {
                    bad++
                } else {
                    if (v < mn[c]) mn[c] = v
                    if (v > mx[c]) mx[c] = v
                }
            }
        }
        if (bad > 0) out.add("$label: $bad nicht endliche Werte (NaN/Inf) in $name")
        if (name != "POSITION") return
        val dmin = acc["min"].arr()?.mapNotNull { it.num() }
        val dmax = acc["max"].arr()?.mapNotNull { it.num() }
        if (dmin == null || dmax == null || dmin.size != 3 || dmax.size != 3) {
            out.add("$label: POSITION-Accessor $ai ohne min/max")
            return
        }
        if (bad > 0) return
        for (c in 0 until 3) {
            val tol = 1e-4 * max(1.0, abs(mx[c]) + abs(mn[c]))
            if (abs(dmin[c] - mn[c]) > tol || abs(dmax[c] - mx[c]) > tol) {
                out.add("$label: POSITION-Accessor $ai min/max stimmen nicht mit den Daten ueberein")
                return
            }
        }
    }

    private fun checkIndices(ii: Int, vertexCount: Int, label: String) {
        val (view, err) = p.accessorView(ii)
        if (view == null) {
            out.add("$label: Indizes: $err")
            return
        }
        if (view.width != 1 || view.componentType !in setOf(5121, 5123, 5125)) {
            out.add("$label: Indizes muessen vorzeichenlose Ganzzahlen (SCALAR) sein")
            return
        }
        if (view.count % 3 != 0) out.add("$label: Indexzahl ${view.count} ist kein Vielfaches von 3")
        var outside = 0
        var first = -1L
        for (e in 0 until view.count) {
            val v = p.read(view, e, 0).toLong()
            if (v < 0 || v >= vertexCount) {
                if (outside == 0) first = v
                outside++
            }
        }
        if (outside > 0) out.add("$label: $outside Indizes ausserhalb von 0..${vertexCount - 1} (z. B. $first)")
    }

    private fun checkModelAnimations() {
        for (name in listOf(WorldModels.ANIM_PEDAL, WorldModels.ANIM_ROLL)) {
            val a = p.info.animation(name)
            if (a == null) {
                out.add("Animation \"$name\" fehlt")
                continue
            }
            if (abs(a.durationS - 1.0) > 1e-4) out.add("Animation \"$name\" dauert ${a.durationS} s statt genau 1,0 s")
            if (abs(a.startS) > 1e-4) out.add("Animation \"$name\" beginnt bei ${a.startS} s statt 0")
        }
    }

    private fun checkAnimationData() {
        for ((ai, a) in json.list("animations").withIndex()) {
            val label = "Animation \"${a["name"].str() ?: ai}\""
            val samplers = a.list("samplers")
            for ((si, s) in samplers.withIndex()) {
                val input = s["input"].int()
                val output = s["output"].int()
                if (input == null || output == null) {
                    out.add("$label: Sampler $si ohne input/output")
                    continue
                }
                val (iv, ierr) = p.accessorView(input)
                if (iv == null) {
                    out.add("$label: $ierr")
                    continue
                }
                val acc = p.accessors[input]
                if (acc["min"] == null || acc["max"] == null) out.add("$label: Zeit-Accessor $input ohne min/max")
                var prev = -Double.MAX_VALUE
                for (e in 0 until iv.count) {
                    val t = p.read(iv, e, 0)
                    if (!t.isFinite() || t <= prev) {
                        out.add("$label: Zeiten in Sampler $si sind nicht streng steigend")
                        break
                    }
                    prev = t
                }
                val (ov, oerr) = p.accessorView(output)
                if (ov == null) out.add("$label: $oerr")
            }
            for ((ci, c) in a.list("channels").withIndex()) {
                val si = c["sampler"].int()
                if (si == null || si !in samplers.indices) out.add("$label: Kanal $ci ohne gueltigen Sampler")
                val node = c["target"].obj()?.get("node").int()
                if (node == null || node !in p.nodes.indices) out.add("$label: Kanal $ci zielt auf keinen gueltigen Knoten")
                val path = c["target"].obj()?.get("path").str()
                if (path !in setOf("translation", "rotation", "scale")) out.add("$label: Kanal $ci mit Pfad $path (erlaubt: translation, rotation, scale)")
            }
        }
    }
}
