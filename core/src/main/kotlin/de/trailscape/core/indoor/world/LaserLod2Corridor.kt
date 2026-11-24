package de.trailscape.core.indoor.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # LoD2, auf den Korridor zugeschnitten
 *
 * LoD2 gibt es in NRW nur als ganze Quadratkilometer (Stadt 15–30 MB). Fuer
 * den Weltbau zaehlen nur die Gebaeude im Streifen um die Strecke. Dieses
 * kompakte Format haelt genau die: Das Werkzeug schneidet sie aus den
 * Kacheln (`indoor-dev lod2-cut`, [cutLod2Corridor]), ein Server koennte es
 * fuer den Handy-Bau so ausliefern; der Weltbau liest es statt der Kacheln
 * ([LaserOptions.lod2Corridor]).
 *
 * Inhalt (gzip): Magie `TSL2`, Version 1, UTM-Zone, Bezugspunkt (Rechts-,
 * Hoch-, Hoehenwert in ganzen Metern), Zahl der Gebaeude; je Gebaeude
 * Kennung, Dachform, gemessene Hoehe und Flaechen (Art, Ringe); je Ring die
 * Punkte als Zentimeter-Differenzen (ZigZag-Varint). Kollineare
 * Zwischenpunkte und Abschlussflaechen entfallen beim Schreiben.
 */

private const val MAGIC = 0x54534C32 // "TSL2"
private const val FORMAT = 1

/** Schreibt [list] (UTM-Zone [zone]) im Korridorformat. */
internal fun encodeLod2Corridor(zone: Int, list: List<Lod2UtmBuilding>): ByteArray {
    var bx = Double.MAX_VALUE
    var by = Double.MAX_VALUE
    var bz = Double.MAX_VALUE
    for (b in list) for (sf in b.surfaces) for (r in sf.rings) for (i in 0 until r.size / 3) {
        bx = min(bx, r[3 * i]); by = min(by, r[3 * i + 1]); bz = min(bz, r[3 * i + 2])
    }
    if (list.isEmpty()) { bx = 0.0; by = 0.0; bz = 0.0 }
    val ox = floor(bx).toLong()
    val oy = floor(by).toLong()
    val oz = floor(bz).toLong()
    val buf = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(buf)).use { out ->
        out.writeInt(MAGIC)
        out.writeByte(FORMAT)
        out.writeByte(zone)
        out.writeLong(ox); out.writeLong(oy); out.writeLong(oz)
        out.writeInt(list.size)
        for (b in list) {
            out.writeUTF(b.id)
            out.writeShort(b.roofType)
            out.writeFloat(b.measuredHeightM.toFloat())
            val surfaces = b.surfaces.filter { it.kind != Lod2SurfaceKind.CLOSURE }
            out.writeShort(surfaces.size)
            for (sf in surfaces) {
                out.writeByte(sf.kind.ordinal)
                val rings = sf.rings.map { Lod2Mesher.simplify(it) }.filter { it.size >= 9 }
                out.writeByte(rings.size)
                for (r in rings) {
                    val n = r.size / 3
                    writeVar(out, n.toLong())
                    var px = 0L; var py = 0L; var pz = 0L
                    for (i in 0 until n) {
                        val x = Math.round((r[3 * i] - ox) * 100)
                        val y = Math.round((r[3 * i + 1] - oy) * 100)
                        val z = Math.round((r[3 * i + 2] - oz) * 100)
                        writeVar(out, zig(x - px)); writeVar(out, zig(y - py)); writeVar(out, zig(z - pz))
                        px = x; py = y; pz = z
                    }
                }
            }
        }
    }
    return buf.toByteArray()
}

/** Liest das Korridorformat: (UTM-Zone, Gebaeude). Wirft [IllegalArgumentException] bei fremden Daten. */
internal fun decodeLod2Corridor(bytes: ByteArray): Pair<Int, List<Lod2UtmBuilding>> {
    DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { inp ->
        require(inp.readInt() == MAGIC) { "kein LoD2-Korridor" }
        val version = inp.readUnsignedByte()
        require(version == FORMAT) { "LoD2-Korridor Version $version" }
        val zone = inp.readUnsignedByte()
        val ox = inp.readLong(); val oy = inp.readLong(); val oz = inp.readLong()
        val count = inp.readInt()
        require(count in 0..2_000_000) { "LoD2-Korridor: $count Gebaeude" }
        val out = ArrayList<Lod2UtmBuilding>(count)
        repeat(count) {
            val id = inp.readUTF()
            val roof = inp.readShort().toInt()
            val measured = inp.readFloat().toDouble()
            val ns = inp.readUnsignedShort()
            val surfaces = ArrayList<Lod2Surface>(ns)
            repeat(ns) {
                val kind = Lod2SurfaceKind.entries[inp.readUnsignedByte().coerceAtMost(Lod2SurfaceKind.entries.size - 1)]
                val nr = inp.readUnsignedByte()
                val rings = ArrayList<DoubleArray>(nr)
                repeat(nr) {
                    val n = readVar(inp).toInt()
                    require(n in 0..100_000) { "LoD2-Korridor: Ring mit $n Punkten" }
                    val r = DoubleArray(3 * n)
                    var px = 0L; var py = 0L; var pz = 0L
                    for (i in 0 until n) {
                        px += unzig(readVar(inp)); py += unzig(readVar(inp)); pz += unzig(readVar(inp))
                        r[3 * i] = ox + px / 100.0; r[3 * i + 1] = oy + py / 100.0; r[3 * i + 2] = oz + pz / 100.0
                    }
                    rings += r
                }
                surfaces += Lod2Surface(kind, rings)
            }
            out += Lod2UtmBuilding(id, roof, measured, surfaces)
        }
        return zone to out
    }
}

/**
 * Schneidet LoD2 fuer die Strecke [routeLatLon] (Breite, Laenge) auf den
 * Streifen [LaserOptions.lod2RadiusM]: laedt die beruehrten Kacheln ueber
 * [LaserOptions.fetcher] und behaelt Gebaeude, von denen ein Punkt naeher als
 * der Radius an der Strecke liegt. `null`, wenn keine Quelle die Strecke abdeckt.
 *
 * @return das Korridorformat und die Kennzahlen (Kacheln, Bytes der Kacheln, Gebaeude vorher/nachher)
 */
fun cutLod2Corridor(options: LaserOptions, routeLatLon: List<Pair<Double, Double>>): Pair<ByteArray, Map<String, Long>>? {
    if (routeLatLon.size < 2) return null
    val source = options.sources
        .map { s -> s to routeLatLon.count { s.covers(it.first, it.second) } }
        .filter { it.second * 2 >= routeLatLon.size && it.first.lod2UrlTemplate != null }
        .maxByOrNull { it.second }?.first ?: return null
    val xs = DoubleArray(routeLatLon.size)
    val ys = DoubleArray(routeLatLon.size)
    for ((i, p) in routeLatLon.withIndex()) {
        val u = Utm.forward(p.first, p.second, source.utmZone)
        xs[i] = u[0]; ys[i] = u[1]
    }
    // Dichter Polygonzug (≤ 20 m) fuer die Kachelwahl.
    val dx = ArrayList<Double>()
    val dy = ArrayList<Double>()
    for (i in xs.indices) {
        if (i > 0) {
            val steps = max(1, (kotlin.math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) / 20.0).toInt())
            for (k in 1 until steps) { dx += xs[i - 1] + (xs[i] - xs[i - 1]) * k / steps; dy += ys[i - 1] + (ys[i] - ys[i - 1]) * k / steps }
        }
        dx += xs[i]; dy += ys[i]
    }
    val line = PolylineDistance(dx.toDoubleArray(), dy.toDoubleArray())
    val r = options.lod2RadiusM
    val kms = LinkedHashSet<Long>()
    for (i in dx.indices) for (ox in listOf(-r, 0.0, r)) for (oy in listOf(-r, 0.0, r)) {
        kms += LaserData.key(floor((dx[i] + ox) / 1000).toInt(), floor((dy[i] + oy) / 1000).toInt())
    }
    var tileBytes = 0L
    var tiles = 0L
    var before = 0L
    val seen = HashSet<String>()
    val kept = ArrayList<Lod2UtmBuilding>()
    for (k in kms) {
        val url = source.lod2Url((k shr 32).toInt(), k.toInt()) ?: continue
        val resp = options.fetcher.fetch(url)
        if (resp.status != 200) continue
        tiles++
        tileBytes += resp.bytes.size
        for (b in parseLod2Utm(resp.bytes)) {
            before++
            if (!seen.add(b.id)) continue
            val near = b.surfaces.any { sf -> sf.rings.any { ring -> (0 until ring.size / 3).any { i -> line.distance(ring[3 * i], ring[3 * i + 1], r) <= r } } }
            if (near) kept += b
        }
    }
    val bytes = encodeLod2Corridor(source.utmZone, kept.sortedBy { it.id })
    return bytes to linkedMapOf("kacheln" to tiles, "kachelBytes" to tileBytes, "gebaeudeKacheln" to before, "gebaeudeKorridor" to kept.size.toLong(), "korridorBytes" to bytes.size.toLong())
}

private fun zig(v: Long): Long = (v shl 1) xor (v shr 63)
private fun unzig(v: Long): Long = (v ushr 1) xor -(v and 1)

private fun writeVar(out: DataOutputStream, v0: Long) {
    var v = v0
    while (v and 0x7FL.inv() != 0L) {
        out.writeByte(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
    out.writeByte(v.toInt())
}

private fun readVar(inp: DataInputStream): Long {
    var shift = 0
    var v = 0L
    while (shift < 64) {
        val b = inp.readUnsignedByte()
        v = v or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return v
        shift += 7
    }
    throw IllegalArgumentException("LoD2-Korridor: Varint zu lang")
}
