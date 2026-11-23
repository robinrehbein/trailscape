package de.trailscape.core.indoor.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Testhilfen fuer die Geodaten-Bausteine: ImageIO als Decoder, Testdaten, ein kleiner MVT-Schreiber. */

internal val imageIoDecoder = RasterDecoder { bytes ->
    runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()?.let { img ->
        RasterImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }
}

internal fun resourceBytes(name: String): ByteArray =
    requireNotNull(GeoTestSupportAnchor::class.java.getResourceAsStream("/indoor/world/$name")) { "Testdatei fehlt: $name" }
        .use { it.readBytes() }

private object GeoTestSupportAnchor

/** Minimaler Protobuf-Schreiber fuer handgebaute Vektorkacheln. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(v: Long): ProtoWriter {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) {
                out.write(x.toInt())
                return this
            }
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    fun tag(field: Int, wire: Int): ProtoWriter = varint(((field shl 3) or wire).toLong())

    fun uint(field: Int, v: Long): ProtoWriter = tag(field, 0).varint(v)

    fun bytes(field: Int, b: ByteArray): ProtoWriter {
        tag(field, 2).varint(b.size.toLong())
        out.write(b)
        return this
    }

    fun string(field: Int, s: String): ProtoWriter = bytes(field, s.toByteArray(Charsets.UTF_8))

    fun packed(field: Int, values: List<Long>): ProtoWriter {
        val inner = ProtoWriter()
        values.forEach { inner.varint(it) }
        return bytes(field, inner.toByteArray())
    }

    fun fixed64(field: Int, bits: Long): ProtoWriter {
        tag(field, 1)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        return this
    }

    fun fixed32(field: Int, bits: Int): ProtoWriter {
        tag(field, 5)
        for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal fun zz(v: Int): Long = ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL

internal fun cmd(id: Int, count: Int): Long = ((count shl 3) or id).toLong()

/** Geometriebefehle fuer Ringe/Linien aus absoluten Punkten. */
internal fun encodeGeometry(parts: List<IntArray>, closeRings: Boolean): List<Long> {
    val out = ArrayList<Long>()
    var cx = 0
    var cy = 0
    for (p in parts) {
        val n = p.size / 2
        out += cmd(1, 1)
        out += zz(p[0] - cx); out += zz(p[1] - cy)
        cx = p[0]; cy = p[1]
        out += cmd(2, n - 1)
        for (i in 1 until n) {
            out += zz(p[2 * i] - cx); out += zz(p[2 * i + 1] - cy)
            cx = p[2 * i]; cy = p[2 * i + 1]
        }
        if (closeRings) out += cmd(7, 1)
    }
    return out
}

/** Rechteck als Ring (y nach unten, aussen positiv = im Uhrzeigersinn auf dem Bildschirm). */
internal fun rectRing(x0: Int, y0: Int, x1: Int, y1: Int): IntArray = intArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

internal fun feature(type: MvtGeomType, tags: Map<String, Any>, vararg parts: IntArray) =
    MvtFeature(null, type, tags, parts.toList())

internal fun tileOf(z: Int, x: Int, y: Int, vararg layers: Pair<String, List<MvtFeature>>) =
    VectorTileData(TileId(z, x, y), MvtTile(layers.associate { (n, f) -> n to MvtLayer(n, 2, 4096, f) }, emptyList()))
