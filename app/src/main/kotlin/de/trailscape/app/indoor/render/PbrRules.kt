package de.trailscape.app.indoor.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * # PBR-Texturen: Regeln
 *
 * Reine Regeln ohne Android und Filament (getestet; `filarender.cpp` spiegelt [keyFor]) fuer die
 * PBR-Texturen der 3D-Ansicht (docs/indoor/pbr-texturen.md): `pbr.json` lesen, Material der
 * Strecke → Satz, Parameter `pbrA…D` fuer `surface.mat`/`surface_blend.mat` und `terrain.mat`,
 * KTX1-Kopf lesen. Laden und Binden: [PbrTextures].
 *
 * Die Texturen sind Textur-Arrays je Gruppe (z. B. „asphalt“ = neu + alt), erzeugt von
 * `tool/indoor-dev/filament/pbr.py` aus CC0-Saetzen von ambientCG (LICENSES.md).
 */
internal object PbrRules {
    const val DIR = "indoor/pbr"
    const val META = "pbr.json"

    /** Schluessel im Paket-Verzeichnis von [FilamentCore.materialPackages] (nur `pbr.json`; die Texturen laedt [PbrTextures] bei Bedarf). */
    const val PACKAGE_KEY = "pbr/pbr.json"

    /** OpenGL-Formate in der KTX-Datei. */
    const val GL_ETC2_SRGBA8 = 0x9279
    const val GL_EAC_RG11 = 0x9272

    data class Group(val size: Int, val layers: Int, val color: String, val normal: String, val bytes: Long)

    /** Parameter eines Belags oder Randstreifens (Bedeutung: `pbr.py`, Funktion `kind`). */
    data class Kind(
        val group: String,
        val layer: Int,
        val meters: Float,
        val alt: Int,
        val altMeters: Float,
        val altMix: Float,
        val coord: Int,
        val uvMetersU: Float,
        val uvMetersV: Float,
        val normal: Float,
        val albedo: Float,
        val replace: Float,
        val keepLod: Float,
        val roughScale: Float,
        val roughBias: Float,
        val roughMix: Float,
    )

    data class Terrain(
        val group: String,
        val repeats: FloatArray,
        val normal: Float,
        val albedo: Float,
        val roughMix: Float,
        val roughScale: Float,
        val roughBias: Float,
    )

    data class Spec(
        val rangeM: Float,
        val terrainRangeM: Float,
        val groups: Map<String, Group>,
        val kinds: Map<String, Kind>,
        val terrain: Terrain?,
    ) {
        /** `pbrA…D` fuer einen Belag/Randstreifen; [on] = false: alles 0 (Material wie vorher). */
        fun surfaceParams(k: Kind, on: Boolean): Array<FloatArray> = if (!on) {
            Array(4) { FloatArray(4) }
        } else {
            arrayOf(
                floatArrayOf(rangeM, k.normal, k.albedo, k.replace),
                floatArrayOf(k.meters, k.layer.toFloat(), k.alt.toFloat(), k.altMix),
                floatArrayOf(k.roughScale, k.roughBias, k.roughMix, k.keepLod),
                floatArrayOf(k.coord.toFloat(), k.uvMetersU, k.uvMetersV, k.altMeters),
            )
        }

        /** `pbrA…C` fuer das Gelaende; [on] = false: alles 0. */
        fun terrainParams(on: Boolean): Array<FloatArray> {
            val t = terrain
            if (!on || t == null) return Array(3) { FloatArray(4) }
            return arrayOf(
                floatArrayOf(terrainRangeM, t.normal, t.albedo, t.roughMix),
                t.repeats.copyOf(4),
                floatArrayOf(t.roughScale, t.roughBias, 0f, 0f),
            )
        }
    }

    /**
     * Schluessel aus dem Materialnamen der Strecke: Belag (`mat_route_<b>`, `x_mat_road_<b>`,
     * Auslauf `x_mat_route_<b>_fade`) oder Randstreifen (`x_mat_route_verge_grass[_fade]`,
     * `x_mat_route_shoulder`, `x_mat_route_fringe` …). Strassenkanten (`_edge`), Bordsteine,
     * Inseln und alles Unbekannte: `null`.
     */
    fun keyFor(label: String?, kinds: Set<String>): String? {
        val l = label ?: return null
        var k = when {
            l.startsWith("mat_route_") -> l.removePrefix("mat_route_")
            l.startsWith("x_mat_road_") -> l.removePrefix("x_mat_road_")
            l.startsWith("x_mat_route_") -> l.removePrefix("x_mat_route_")
            else -> return null
        }
        if (k.endsWith("_edge")) return null
        k = k.removeSuffix("_fade")
        return k.takeIf { it in kinds }
    }

    /** Liest `pbr.json`; `null` bei jedem Fehler (dann bleibt PBR aus). */
    fun parse(text: String): Spec? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.f(name: String): Float = getValue(name).jsonPrimitive.content.toFloat()
        fun JsonObject.i(name: String): Int = f(name).toInt()
        fun JsonObject.s(name: String): String = getValue(name).jsonPrimitive.content
        val groups = root.getValue("groups").jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Group(o.i("size"), o.getValue("layers").jsonArray.size, o.s("color"), o.s("normal"), o.f("bytes").toLong())
        }.filterValues { it.size in 4..4096 && it.layers in 1..16 && safeName(it.color) && safeName(it.normal) }
        val kinds = root.getValue("kinds").jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Kind(
                group = o.s("group"), layer = o.i("layer"), meters = o.f("meters"),
                alt = o.i("alt"), altMeters = o.f("altMeters"), altMix = o.f("altMix"),
                coord = o.i("coord"), uvMetersU = o.f("uvMetersU"), uvMetersV = o.f("uvMetersV"),
                normal = o.f("normal"), albedo = o.f("albedo"), replace = o.f("replace"), keepLod = o.f("keepLod"),
                roughScale = o.f("roughScale"), roughBias = o.f("roughBias"), roughMix = o.f("roughMix"),
            )
        }.filterValues { k ->
            val g = groups[k.group]
            g != null && k.layer in 0 until g.layers && k.alt < g.layers && k.meters > 0f && k.altMeters > 0f
        }
        val terrain = root["terrain"]?.jsonObject?.let { o ->
            Terrain(
                group = o.s("group"),
                repeats = o.getValue("repeats").jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray(),
                normal = o.f("normal"), albedo = o.f("albedo"), roughMix = o.f("roughMix"),
                roughScale = o.f("roughScale"), roughBias = o.f("roughBias"),
            )
        }?.takeIf { t -> groups[t.group]?.let { it.layers >= 4 } == true && t.repeats.size >= 4 && t.repeats.all { it >= 1f } }
        Spec(root.f("rangeM"), root.f("terrainRangeM"), groups, kinds, terrain)
    }.getOrNull()

    /** Nur einfache Dateinamen im Verzeichnis (keine Pfade). */
    private fun safeName(n: String): Boolean = n.isNotEmpty() && n.length < 64 && n.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } && !n.startsWith(".")

    /** Eine Mip-Stufe: Bytes aller Schichten hintereinander. */
    class Level(val offset: Int, val size: Int)

    class Ktx(val glInternalFormat: Int, val size: Int, val layers: Int, val levels: List<Level>)

    /**
     * Liest den Kopf einer KTX1-Datei (komprimiert, quadratisch, Array oder einzeln) und die Lage
     * der Mip-Stufen; `null`, wenn etwas nicht passt (dann bleibt die Gruppe aus).
     */
    fun readKtx(bytes: ByteArray): Ktx? {
        if (bytes.size < 64) return null
        val id = byteArrayOf(0xAB.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), 'X'.code.toByte(), ' '.code.toByte(), '1'.code.toByte(), '1'.code.toByte(), 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in id.indices) if (bytes[i] != id[i]) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(12) != 0x04030201) return null
        val glType = b.getInt(16)
        val ifmt = b.getInt(28)
        val w = b.getInt(36)
        val h = b.getInt(40)
        val layers = maxOf(1, b.getInt(48))
        val faces = b.getInt(52)
        val nLevels = b.getInt(56)
        val kv = b.getInt(60)
        if (glType != 0 || w != h || w !in 4..4096 || layers > 16 || faces != 1 || nLevels !in 1..13 || kv < 0) return null
        if (ifmt != GL_ETC2_SRGBA8 && ifmt != GL_EAC_RG11) return null
        var off = 64L + kv
        val out = ArrayList<Level>()
        for (lv in 0 until nLevels) {
            if (off + 4 > bytes.size) return null
            val n = b.getInt(off.toInt()).toLong() and 0xFFFFFFFFL
            off += 4
            val d = maxOf(1, w shr lv)
            val blocks = ((d + 3) / 4).toLong() * ((d + 3) / 4)
            if (n != blocks * 16 * layers || off + n > bytes.size) return null
            out += Level(off.toInt(), n.toInt())
            off += (n + 3) and 3L.inv()
        }
        return Ktx(ifmt, w, layers, out)
    }
}
