package de.trailscape.core.indoor.world

import java.util.zip.Inflater

/**
 * # GeoTIFF mit Gleitkomma-Hoehen lesen
 *
 * Die WCS-Dienste der Laender liefern Hoehen als einkanaliges GeoTIFF mit
 * 32-Bit-Gleitkomma (NRW: DGM mit LZW, DOM unkomprimiert). `ImageIO` gibt es
 * in `:core` nicht (Android), deshalb dieser kleine Leser in reinem Kotlin:
 *
 *  * Byte-Reihenfolge `II` und `MM`, klassisches TIFF (kein BigTIFF),
 *  * Streifen **und** Kacheln (`TileWidth`/`TileLength`),
 *  * Kompression keine (1), LZW (5), Deflate (8 und 32946),
 *  * Praediktor keiner (1), horizontal (2) und Gleitkomma (3),
 *  * Abtastwerte 32-Bit-Gleitkomma (`SampleFormat` 3), dazu 16/32-Bit-Ganzzahl.
 *
 * GeoTIFF: `ModelPixelScale` (33550) und `ModelTiepoint` (33922) geben die
 * Lage, `GDAL_NODATA` (42113) den Fehlwert — Fehlwerte werden zu `NaN`.
 */
internal class FloatRaster(
    val width: Int,
    val height: Int,
    /** Zeilenweise von oben (Norden) nach unten. */
    val data: FloatArray,
    /** Rechtswert der **linken Kante** der ersten Spalte (nicht der Pixelmitte). */
    val originX: Double,
    /** Hochwert der **oberen Kante** der ersten Zeile. */
    val originY: Double,
    val pixelX: Double,
    val pixelY: Double,
) {
    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    /**
     * Bilinear an (`x`, `y`) in Projektionsmetern; Pixelwerte gelten in der
     * Pixelmitte. `NaN` ausserhalb oder wenn ein Nachbar fehlt.
     */
    fun sample(x: Double, y: Double): Float {
        val fx = (x - originX) / pixelX - 0.5
        val fy = (originY - y) / pixelY - 0.5
        val ix = kotlin.math.floor(fx).toInt()
        val iy = kotlin.math.floor(fy).toInt()
        if (ix < 0 || iy < 0 || ix + 1 >= width || iy + 1 >= height) {
            // Am aeussersten Rand: naechster Wert, sonst NaN.
            val nx = Math.round(fx).toInt()
            val ny = Math.round(fy).toInt()
            return if (nx in 0 until width && ny in 0 until height) get(nx, ny) else Float.NaN
        }
        val tx = (fx - ix).toFloat()
        val ty = (fy - iy).toFloat()
        val a = get(ix, iy)
        val b = get(ix + 1, iy)
        val c = get(ix, iy + 1)
        val d = get(ix + 1, iy + 1)
        val top = a + (b - a) * tx
        val bottom = c + (d - c) * tx
        return top + (bottom - top) * ty
    }
}

/** Ungueltiges oder nicht unterstuetztes TIFF. */
internal class TiffFormatException(message: String) : Exception(message)

/** Liest ein einkanaliges Hoehen-GeoTIFF; wirft [TiffFormatException]. */
internal fun decodeFloatTiff(bytes: ByteArray): FloatRaster = TiffReader(bytes).read()

private class TiffReader(private val b: ByteArray) {
    private val little: Boolean = when {
        b.size >= 8 && b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() -> true
        b.size >= 8 && b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() -> false
        else -> throw TiffFormatException("kein TIFF")
    }

    private fun u8(p: Int): Int = b[p].toInt() and 0xFF
    private fun u16(p: Int): Int = if (little) u8(p) or (u8(p + 1) shl 8) else (u8(p) shl 8) or u8(p + 1)
    private fun u32(p: Int): Long {
        val v = if (little) {
            u8(p).toLong() or (u8(p + 1).toLong() shl 8) or (u8(p + 2).toLong() shl 16) or (u8(p + 3).toLong() shl 24)
        } else {
            (u8(p).toLong() shl 24) or (u8(p + 1).toLong() shl 16) or (u8(p + 2).toLong() shl 8) or u8(p + 3).toLong()
        }
        return v
    }

    private fun f64(p: Int): Double {
        var v = 0L
        for (i in 0 until 8) {
            val byte = u8(if (little) p + 7 - i else p + i).toLong()
            v = (v shl 8) or byte
        }
        return java.lang.Double.longBitsToDouble(v)
    }

    private class Entry(val type: Int, val count: Long, val valueOffset: Int)

    private val tags = HashMap<Int, Entry>()

    private fun typeSize(type: Int) = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> throw TiffFormatException("Feldtyp $type")
    }

    private fun need(p: Long, len: Long) {
        if (p < 0 || len < 0 || p + len > b.size) throw TiffFormatException("Verweis ausserhalb der Datei")
    }

    private fun ints(tag: Int): LongArray? {
        val e = tags[tag] ?: return null
        val size = typeSize(e.type)
        need(e.valueOffset.toLong(), size * e.count)
        return LongArray(e.count.toInt()) { i ->
            val p = e.valueOffset + i * size
            when (e.type) {
                1, 7 -> u8(p).toLong()
                3 -> u16(p).toLong()
                4 -> u32(p)
                else -> throw TiffFormatException("Feld $tag: Typ ${e.type} statt Ganzzahl")
            }
        }
    }

    private fun int(tag: Int, default: Int): Int = ints(tag)?.firstOrNull()?.toInt() ?: default

    private fun doubles(tag: Int): DoubleArray? {
        val e = tags[tag] ?: return null
        if (e.type != 12) return null
        need(e.valueOffset.toLong(), 8 * e.count)
        return DoubleArray(e.count.toInt()) { f64(e.valueOffset + 8 * it) }
    }

    private fun ascii(tag: Int): String? {
        val e = tags[tag] ?: return null
        if (e.type != 2) return null
        need(e.valueOffset.toLong(), e.count)
        return String(b, e.valueOffset, e.count.toInt(), Charsets.US_ASCII).trimEnd('\u0000', ' ')
    }

    fun read(): FloatRaster {
        if (u16(2) != 42) throw TiffFormatException("kein klassisches TIFF")
        val ifd = u32(4)
        need(ifd, 2)
        val count = u16(ifd.toInt())
        need(ifd + 2, 12L * count)
        for (i in 0 until count) {
            val p = ifd.toInt() + 2 + 12 * i
            val tag = u16(p)
            val type = u16(p + 2)
            val n = u32(p + 4)
            val size = runCatching { typeSize(type) }.getOrNull() ?: continue
            val off = if (size * n <= 4) p + 8 else u32(p + 8).toInt()
            tags[tag] = Entry(type, n, off)
        }
        val width = int(256, 0)
        val height = int(257, 0)
        if (width <= 0 || height <= 0 || width.toLong() * height > 64L * 1024 * 1024) throw TiffFormatException("Groesse ${width}x$height")
        val bits = int(258, 1)
        val compression = int(259, 1)
        val spp = int(277, 1)
        val predictor = int(317, 1)
        val format = int(339, 1)
        val planar = int(284, 1)
        if (spp != 1 && planar != 2) throw TiffFormatException("$spp Kanaele")
        val bytesPer = when {
            format == 3 && bits == 32 -> 4
            (format == 1 || format == 2) && (bits == 16 || bits == 32) -> bits / 8
            else -> throw TiffFormatException("Abtastformat $format/$bits Bit")
        }

        val tiled = tags.containsKey(322)
        val blockW: Int
        val blockH: Int
        val offsets: LongArray
        val counts: LongArray
        if (tiled) {
            blockW = int(322, 0)
            blockH = int(323, 0)
            offsets = ints(324) ?: throw TiffFormatException("TileOffsets fehlt")
            counts = ints(325) ?: throw TiffFormatException("TileByteCounts fehlt")
        } else {
            blockW = width
            blockH = int(278, height).coerceAtMost(height)
            offsets = ints(273) ?: throw TiffFormatException("StripOffsets fehlt")
            counts = ints(279) ?: throw TiffFormatException("StripByteCounts fehlt")
        }
        if (blockW <= 0 || blockH <= 0) throw TiffFormatException("Blockgroesse")
        val across = (width + blockW - 1) / blockW
        val down = (height + blockH - 1) / blockH
        if (offsets.size < across * down || counts.size < across * down) throw TiffFormatException("zu wenige Bloecke")

        val out = FloatArray(width * height)
        val rowBytes = blockW * bytesPer
        val blockBytes = rowBytes * blockH
        for (by in 0 until down) for (bx in 0 until across) {
            val k = by * across + bx
            need(offsets[k], counts[k])
            val raw = decompress(compression, offsets[k].toInt(), counts[k].toInt(), blockBytes)
            unpredict(raw, predictor, blockW, blockH, bytesPer)
            for (y in 0 until blockH) {
                val gy = by * blockH + y
                if (gy >= height) break
                for (x in 0 until blockW) {
                    val gx = bx * blockW + x
                    if (gx >= width) break
                    out[gy * width + gx] = sampleOf(raw, (y * blockW + x) * bytesPer, bytesPer, format)
                }
            }
        }

        val nodata = ascii(42113)?.toDoubleOrNull()
        if (nodata != null) {
            val nd = nodata.toFloat()
            for (i in out.indices) if (out[i] == nd || out[i] < -1.0e30f) out[i] = Float.NaN
        }
        val scale = doubles(33550)
        val tie = doubles(33922)
        var ox = 0.0
        var oy = 0.0
        var sx = 1.0
        var sy = 1.0
        if (scale != null && scale.size >= 2) {
            sx = scale[0]
            sy = scale[1]
        }
        if (tie != null && tie.size >= 6) {
            ox = tie[3] - tie[0] * sx
            oy = tie[4] + tie[1] * sy
        }
        // RasterPixelIsPoint (GeoKey 1025 = 2): Tiepoint meint die Pixelmitte.
        val keys = ints(34735)
        if (keys != null && keys.size >= 4) {
            val n = keys[3].toInt()
            for (i in 0 until n) {
                val base = 4 + 4 * i
                if (base + 3 >= keys.size) break
                if (keys[base] == 1025L && keys[base + 1] == 0L && keys[base + 3] == 2L) {
                    ox -= sx / 2
                    oy += sy / 2
                }
            }
        }
        return FloatRaster(width, height, out, ox, oy, sx, sy)
    }

    private fun sampleOf(raw: ByteArray, p: Int, bytesPer: Int, format: Int): Float {
        fun ub(i: Int) = raw[p + i].toInt() and 0xFF
        val bitsV: Long = if (little) {
            var v = 0L
            for (i in bytesPer - 1 downTo 0) v = (v shl 8) or ub(i).toLong()
            v
        } else {
            var v = 0L
            for (i in 0 until bytesPer) v = (v shl 8) or ub(i).toLong()
            v
        }
        return when {
            format == 3 -> java.lang.Float.intBitsToFloat(bitsV.toInt())
            bytesPer == 2 && format == 2 -> bitsV.toInt().toShort().toFloat()
            bytesPer == 2 -> bitsV.toFloat()
            format == 2 -> bitsV.toInt().toFloat()
            else -> bitsV.toFloat()
        }
    }

    private fun decompress(compression: Int, off: Int, len: Int, expected: Int): ByteArray = when (compression) {
        1 -> {
            val r = ByteArray(expected)
            System.arraycopy(b, off, r, 0, minOf(len, expected))
            r
        }
        5 -> lzw(off, len, expected)
        8, 32946 -> {
            val inf = Inflater()
            try {
                inf.setInput(b, off, len)
                val r = ByteArray(expected)
                var p = 0
                while (p < expected && !inf.finished()) {
                    val k = inf.inflate(r, p, expected - p)
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    p += k
                }
                r
            } finally {
                inf.end()
            }
        }
        else -> throw TiffFormatException("Kompression $compression")
    }

    /** TIFF-LZW (MSB zuerst, Codes 9–12 Bit, „early change"). */
    private fun lzw(off: Int, len: Int, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var op = 0
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) {
            suffix[i] = i.toByte()
            length[i] = 1
            prefix[i] = -1
        }
        var next = 258
        var width = 9
        var bitPos = 0L
        val endBit = len.toLong() * 8
        var old = -1
        val stack = ByteArray(4096)
        fun readCode(): Int {
            if (bitPos + width > endBit) return 257
            var v = 0
            for (i in 0 until width) {
                val p = bitPos + i
                val byte = b[off + (p ushr 3).toInt()].toInt() and 0xFF
                v = (v shl 1) or ((byte ushr (7 - (p and 7).toInt())) and 1)
            }
            bitPos += width
            return v
        }
        fun emit(code: Int): Byte {
            var c = code
            var sp = 0
            while (c >= 0 && sp < stack.size) {
                stack[sp++] = suffix[c]
                c = prefix[c]
            }
            val first = stack[sp - 1]
            while (sp > 0 && op < expected) out[op++] = stack[--sp]
            return first
        }
        while (op < expected) {
            val code = readCode()
            if (code == 257) break
            if (code == 256) {
                next = 258
                width = 9
                old = -1
                continue
            }
            if (old == -1) {
                if (code > 255) throw TiffFormatException("LZW: erster Code $code")
                emit(code)
                old = code
                continue
            }
            val first: Byte
            if (code < next) {
                first = emit(code)
                if (next < 4096) {
                    prefix[next] = old
                    suffix[next] = first
                    length[next] = length[old] + 1
                    next++
                }
            } else if (code == next) {
                // KwKwK: alter String + sein erstes Zeichen
                var c = old
                while (prefix[c] >= 0) c = prefix[c]
                val f = suffix[c]
                if (next < 4096) {
                    prefix[next] = old
                    suffix[next] = f
                    length[next] = length[old] + 1
                    next++
                }
                emit(code)
            } else {
                throw TiffFormatException("LZW: Code $code > $next")
            }
            old = code
            if (next + 1 >= (1 shl width) && width < 12) width++
        }
        return out
    }

    private fun unpredict(raw: ByteArray, predictor: Int, w: Int, h: Int, bytesPer: Int) {
        when (predictor) {
            1 -> Unit
            2 -> for (y in 0 until h) {
                // Horizontale Differenzen je Abtastwert in Datei-Byte-Reihenfolge.
                val row = y * w * bytesPer
                for (x in 1 until w) {
                    val p = row + x * bytesPer
                    val q = p - bytesPer
                    if (bytesPer == 1) {
                        raw[p] = (raw[p] + raw[q]).toByte()
                    } else {
                        var carry = 0
                        val order = if (little) (0 until bytesPer) else (bytesPer - 1 downTo 0)
                        for (i in order) {
                            val s = (raw[p + i].toInt() and 0xFF) + (raw[q + i].toInt() and 0xFF) + carry
                            raw[p + i] = s.toByte()
                            carry = s ushr 8
                        }
                    }
                }
            }
            3 -> {
                // Gleitkomma-Praediktor: Bytes je Zeile nach Wertigkeit gruppiert, bytweise differenziert.
                val rowLen = w * bytesPer
                val tmp = ByteArray(rowLen)
                for (y in 0 until h) {
                    val row = y * rowLen
                    for (i in 1 until rowLen) raw[row + i] = (raw[row + i] + raw[row + i - 1]).toByte()
                    for (x in 0 until w) for (k in 0 until bytesPer) {
                        // Gruppe k enthaelt das k-hoechstwertige Byte (Big-Endian-Gruppierung).
                        val v = raw[row + k * w + x]
                        val dst = if (little) x * bytesPer + (bytesPer - 1 - k) else x * bytesPer + k
                        tmp[dst] = v
                    }
                    System.arraycopy(tmp, 0, raw, row, rowLen)
                }
            }
            else -> throw TiffFormatException("Praediktor $predictor")
        }
    }
}
