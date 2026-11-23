package de.trailscape.core.indoor.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * # Eigener Dekoder fuer Mapbox Vector Tiles (Spezifikation 2.1)
 *
 * Reines Kotlin ohne Abhaengigkeit: Protobuf-Varints, Laengenfelder,
 * unbekannte Felder werden uebersprungen. Ergebnis je Ebene: Objekte mit
 * Merkmalen ([MvtFeature.tags]) und Geometrie in **Kachelkoordinaten**
 * (`0 .. extent`, y nach unten; Puffer ausserhalb davon moeglich).
 *
 * Robust gegen kaputte Bytes: jede Laenge wird gegen den Rest geprueft, ein
 * Varint hat hoechstens 10 Bytes, Geometriebefehle koennen nicht mehr Werte
 * lesen als vorhanden sind — es gibt keine Endlosschleife und keine
 * Riesen-Allokation. Fehler landen als deutscher Text in
 * [MvtTile.diagnostics]; was bis dahin lesbar war, bleibt erhalten.
 *
 * gzip-komprimierte Antworten (Magic `1F 8B`) werden erkannt und entpackt
 * (hoechstens [MAX_MVT_BYTES], gegen Kompressionsbomben).
 */

/** Groesste entpackte Kachel, die angenommen wird. */
internal const val MAX_MVT_BYTES: Int = 32 * 1024 * 1024

internal enum class MvtGeomType { UNKNOWN, POINT, LINESTRING, POLYGON }

/**
 * Ein Polygon in Kachelkoordinaten: [outer] und [holes] als flache
 * `x, y`-Folgen ohne Wiederholung des ersten Punkts. Der Aussenring hat in
 * Kachelkoordinaten (y nach unten) eine **positive** Vorzeichenflaeche
 * ([ringArea2]), Loecher eine negative.
 */
internal class MvtPolygon(val outer: IntArray, val holes: List<IntArray>)

/**
 * Ein Objekt einer Ebene.
 *
 * @property id Objekt-ID der Kachel oder `null`. Achtung bei OpenFreeMap: Objekte
 *   gleicher Merkmale sind **zusammengefasst** (ein `building`-Objekt kann
 *   hunderte Gebaeude enthalten, die ID gehoert nur einem davon) — zum
 *   Wiedererkennen ueber Kachelgrenzen taugt sie nicht.
 * @property tags Merkmale: [String], [Double] (float/double), [Long] (int/uint/sint) oder [Boolean].
 * @property parts Geometrie in Kachelkoordinaten als flache `x, y`-Folgen:
 *   Punkte (je Teil genau ein Punkt), Linienzuege oder Ringe (geschlossen
 *   durch `ClosePath`, der erste Punkt wird nicht wiederholt).
 */
internal class MvtFeature(
    val id: Long?,
    val type: MvtGeomType,
    val tags: Map<String, Any>,
    val parts: List<IntArray>,
) {
    fun str(key: String): String? = tags[key] as? String

    fun num(key: String): Double? = when (val v = tags[key]) {
        is Double -> v
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val v = tags[key]) {
        is Boolean -> v
        is Long -> v != 0L
        is String -> v == "true" || v == "1" || v == "yes"
        else -> null
    }

    /**
     * Die Ringe nach Umlaufsinn gruppiert: Jeder Ring mit positiver
     * Vorzeichenflaeche beginnt ein Polygon, Ringe mit negativer Flaeche sind
     * dessen Loecher. Ringe ohne Flaeche fallen weg, ebenso Loecher vor dem
     * ersten Aussenring (die Spezifikation verbietet sie).
     */
    fun polygons(): List<MvtPolygon> {
        if (type != MvtGeomType.POLYGON) return emptyList()
        val out = ArrayList<MvtPolygon>()
        var outer: IntArray? = null
        var holes = ArrayList<IntArray>()
        for (ring in parts) {
            if (ring.size < 6) continue
            val a = ringArea2(ring)
            when {
                a > 0 -> {
                    outer?.let { out += MvtPolygon(it, holes) }
                    outer = ring
                    holes = ArrayList()
                }
                a < 0 -> if (outer != null) holes += ring
            }
        }
        outer?.let { out += MvtPolygon(it, holes) }
        return out
    }
}

internal class MvtLayer(val name: String, val version: Int, val extent: Int, val features: List<MvtFeature>)

/**
 * Eine dekodierte Kachel. [diagnostics] ist leer, wenn alles lesbar war.
 */
internal class MvtTile(val layers: Map<String, MvtLayer>, val diagnostics: List<String>) {
    val ok: Boolean get() = diagnostics.isEmpty()

    fun layer(name: String): MvtLayer? = layers[name]
}

/** Doppelte Vorzeichenflaeche eines flachen `x, y`-Rings (Schnuerformel, ohne Schlusspunkt). */
internal fun ringArea2(ring: IntArray): Long {
    val n = ring.size / 2
    var s = 0L
    for (i in 0 until n) {
        val j = if (i + 1 == n) 0 else i + 1
        s += ring[2 * i].toLong() * ring[2 * j + 1] - ring[2 * j].toLong() * ring[2 * i + 1]
    }
    return s
}

/** `true`, wenn [bytes] mit der gzip-Signatur `1F 8B` beginnen. */
internal fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

/**
 * Entpackt gzip, hoechstens [limit] Bytes. `null` bei kaputten Daten oder
 * Ueberschreitung der Grenze.
 */
internal fun gunzipOrNull(bytes: ByteArray, limit: Int = MAX_MVT_BYTES): ByteArray? = try {
    GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream(bytes.size * 3)
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
} catch (_: java.io.IOException) {
    null
}

/**
 * Dekodiert eine Vektorkachel (roh oder gzip). Wirft nie: Kaputtes steht in
 * [MvtTile.diagnostics], lesbare Ebenen davor bleiben erhalten.
 */
internal fun decodeMvt(input: ByteArray): MvtTile {
    val diagnostics = ArrayList<String>()
    val bytes = if (isGzip(input)) {
        gunzipOrNull(input) ?: return MvtTile(emptyMap(), listOf("Vektorkachel: gzip-Daten unlesbar oder zu gross"))
    } else {
        input
    }
    val layers = LinkedHashMap<String, MvtLayer>()
    try {
        val r = ProtoReader(bytes, 0, bytes.size)
        while (r.hasMore()) {
            val key = r.varint()
            val field = (key ushr 3).toInt()
            val wire = (key and 7L).toInt()
            if (field == 3 && wire == WIRE_LEN) {
                val sub = r.subReader()
                val layer = try {
                    decodeLayer(sub, diagnostics)
                } catch (e: MvtFormatException) {
                    diagnostics += "Ebene unlesbar: ${e.message}"
                    null
                }
                if (layer != null) layers[layer.name] = layer
            } else {
                r.skip(wire)
            }
        }
    } catch (e: MvtFormatException) {
        diagnostics += "Vektorkachel kaputt: ${e.message}"
    }
    return MvtTile(layers, diagnostics)
}

private const val WIRE_VARINT = 0
private const val WIRE_I64 = 1
private const val WIRE_LEN = 2
private const val WIRE_I32 = 5

private class MvtFormatException(message: String) : Exception(message)

/** Liest Protobuf aus einem Ausschnitt `[pos, end)` von [buf]. */
private class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    fun hasMore(): Boolean = pos < end

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 70) {
            if (pos >= end) throw MvtFormatException("Varint ueber das Ende hinaus")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw MvtFormatException("Varint laenger als 10 Bytes")
    }

    fun length(): Int {
        val len = varint()
        if (len < 0 || len > end - pos) throw MvtFormatException("Laengenfeld $len groesser als der Rest (${end - pos})")
        return len.toInt()
    }

    fun subReader(): ProtoReader {
        val len = length()
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    fun bytes(): ByteArray {
        val len = length()
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun string(): String = String(bytes(), Charsets.UTF_8)

    fun fixed64(): Long {
        if (end - pos < 8) throw MvtFormatException("fixed64 ueber das Ende hinaus")
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun fixed32(): Int {
        if (end - pos < 4) throw MvtFormatException("fixed32 ueber das Ende hinaus")
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    /** Gepackte Varints; die Zahl der Werte ist durch die Bytezahl begrenzt. */
    fun packedVarints(): LongArray {
        val sub = subReader()
        var out = LongArray(16)
        var n = 0
        while (sub.hasMore()) {
            if (n == out.size) out = out.copyOf(out.size * 2)
            out[n++] = sub.varint()
        }
        return out.copyOf(n)
    }

    fun skip(wire: Int) {
        when (wire) {
            WIRE_VARINT -> varint()
            WIRE_I64 -> fixed64()
            WIRE_LEN -> {
                // erst die Laenge lesen (verschiebt pos), dann weiterruecken
                val n = length()
                pos += n
            }
            WIRE_I32 -> fixed32()
            else -> throw MvtFormatException("unbekannter Feldtyp $wire")
        }
    }
}

private fun decodeLayer(r: ProtoReader, diagnostics: MutableList<String>): MvtLayer? {
    var name: String? = null
    var version = 1
    var extent = 4096
    val keys = ArrayList<String>()
    val values = ArrayList<Any?>()
    val rawFeatures = ArrayList<ProtoReader>()
    while (r.hasMore()) {
        val key = r.varint()
        val field = (key ushr 3).toInt()
        val wire = (key and 7L).toInt()
        when {
            field == 15 && wire == WIRE_VARINT -> version = r.varint().toInt()
            field == 1 && wire == WIRE_LEN -> name = r.string()
            field == 2 && wire == WIRE_LEN -> rawFeatures += r.subReader()
            field == 3 && wire == WIRE_LEN -> keys += r.string()
            field == 4 && wire == WIRE_LEN -> values += decodeValue(r.subReader())
            field == 5 && wire == WIRE_VARINT -> extent = r.varint().toInt()
            else -> r.skip(wire)
        }
    }
    if (name == null) {
        diagnostics += "Ebene ohne Namen uebersprungen"
        return null
    }
    if (extent <= 0) {
        diagnostics += "Ebene $name: ungueltige Kantenlaenge $extent"
        return null
    }
    val features = ArrayList<MvtFeature>(rawFeatures.size)
    for (fr in rawFeatures) {
        try {
            features += decodeFeature(fr, keys, values, name, diagnostics)
        } catch (e: MvtFormatException) {
            diagnostics += "Ebene $name: Objekt unlesbar (${e.message})"
        }
    }
    return MvtLayer(name, version, extent, features)
}

private fun decodeValue(r: ProtoReader): Any? {
    var v: Any? = null
    while (r.hasMore()) {
        val key = r.varint()
        val field = (key ushr 3).toInt()
        val wire = (key and 7L).toInt()
        v = when {
            field == 1 && wire == WIRE_LEN -> r.string()
            field == 2 && wire == WIRE_I32 -> java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
            field == 3 && wire == WIRE_I64 -> java.lang.Double.longBitsToDouble(r.fixed64())
            field == 4 && wire == WIRE_VARINT -> r.varint()
            field == 5 && wire == WIRE_VARINT -> r.varint()
            field == 6 && wire == WIRE_VARINT -> r.varint().let { (it ushr 1) xor -(it and 1) }
            field == 7 && wire == WIRE_VARINT -> r.varint() != 0L
            else -> {
                r.skip(wire)
                v
            }
        }
    }
    return v
}

private fun decodeFeature(
    r: ProtoReader,
    keys: List<String>,
    values: List<Any?>,
    layer: String,
    diagnostics: MutableList<String>,
): MvtFeature {
    var id: Long? = null
    var tagIdx = LongArray(0)
    var type = MvtGeomType.UNKNOWN
    var geometry = LongArray(0)
    while (r.hasMore()) {
        val key = r.varint()
        val field = (key ushr 3).toInt()
        val wire = (key and 7L).toInt()
        when {
            field == 1 && wire == WIRE_VARINT -> id = r.varint()
            field == 2 && wire == WIRE_LEN -> tagIdx = r.packedVarints()
            field == 3 && wire == WIRE_VARINT -> type = when (r.varint()) {
                1L -> MvtGeomType.POINT
                2L -> MvtGeomType.LINESTRING
                3L -> MvtGeomType.POLYGON
                else -> MvtGeomType.UNKNOWN
            }
            field == 4 && wire == WIRE_LEN -> geometry = r.packedVarints()
            else -> r.skip(wire)
        }
    }
    val tags = LinkedHashMap<String, Any>()
    var badTag = false
    var i = 0
    while (i + 1 < tagIdx.size) {
        val k = tagIdx[i]
        val v = tagIdx[i + 1]
        val value = if (v in 0 until values.size) values[v.toInt()] else null
        if (k in 0 until keys.size && value != null) tags[keys[k.toInt()]] = value else badTag = true
        i += 2
    }
    if (badTag || tagIdx.size % 2 != 0) diagnostics += "Ebene $layer: Merkmalsverweis ausserhalb der Tabelle"
    return MvtFeature(id, type, tags, decodeGeometry(geometry, type))
}

private fun zigzag(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()

/**
 * Wandelt die Befehlsfolge (MoveTo = 1, LineTo = 2, ClosePath = 7; Laenge in
 * den oberen Bits, Koordinaten als Zickzack-Deltas) in Teile um. Ein Befehl,
 * der mehr Werte verlangt als vorhanden, beendet die Dekodierung.
 */
internal fun decodeGeometry(cmds: LongArray, type: MvtGeomType): List<IntArray> {
    val parts = ArrayList<IntArray>()
    var cur = IntArrayBuilder()
    var x = 0
    var y = 0
    var i = 0
    fun flush() {
        if (cur.size > 0) parts += cur.toArray()
        cur = IntArrayBuilder()
    }
    while (i < cmds.size) {
        val c = cmds[i++]
        val id = (c and 7L).toInt()
        val count = c ushr 3
        when (id) {
            1, 2 -> {
                if (count > (cmds.size - i) / 2) break
                repeat(count.toInt()) {
                    x += zigzag(cmds[i])
                    y += zigzag(cmds[i + 1])
                    i += 2
                    if (id == 1) {
                        if (type == MvtGeomType.POINT) {
                            parts += intArrayOf(x, y)
                        } else {
                            flush()
                            cur.add(x, y)
                        }
                    } else {
                        cur.add(x, y)
                    }
                }
            }
            7 -> flush()
            else -> break
        }
    }
    flush()
    return parts
}

/** Wachsende `x, y`-Folge ohne Boxen. */
internal class IntArrayBuilder {
    private var data = IntArray(16)
    var size = 0
        private set

    fun add(x: Int, y: Int) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    fun toArray(): IntArray = data.copyOf(size)
}
