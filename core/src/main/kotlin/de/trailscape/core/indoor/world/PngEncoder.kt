package de.trailscape.core.indoor.world

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * # PNG schreiben, Bildkoepfe lesen
 *
 * [encodePng] schreibt 8-Bit-RGB oder -RGBA ohne Zeilensprung, mit je Zeile
 * adaptiv gewaehltem Filter (kleinste Summe der Betraege). [imageInfo] liest
 * Format und Groesse aus PNG- und JPEG-Bytes, ohne zu dekodieren — robust
 * gegen kaputte Daten, nie eine Ausnahme.
 */

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * Kodiert [img] als PNG. [withAlpha] = RGBA (Farbtyp 6), sonst RGB (Farbtyp 2,
 * Alpha wird verworfen). [level] ist die Deflate-Stufe (0–9).
 */
internal fun encodePng(img: RgbaImage, withAlpha: Boolean, level: Int = 9): ByteArray {
    val w = img.width
    val h = img.height
    val bpp = if (withAlpha) 4 else 3
    val stride = w * bpp
    var prev = ByteArray(stride)
    var cur = ByteArray(stride)
    val candidates = Array(5) { ByteArray(stride) }
    val raw = ByteArray((stride + 1) * h)
    var rp = 0
    for (y in 0 until h) {
        val row = y * w
        var o = 0
        for (x in 0 until w) {
            val c = img.argb[row + x]
            cur[o++] = (c shr 16).toByte()
            cur[o++] = (c shr 8).toByte()
            cur[o++] = c.toByte()
            if (withAlpha) cur[o++] = (c ushr 24).toByte()
        }
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (f in 0 until 5) {
            val out = candidates[f]
            var sum = 0L
            for (i in 0 until stride) {
                val x = cur[i].toInt() and 0xFF
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val cc = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (f) {
                    0 -> x
                    1 -> x - a
                    2 -> x - b
                    3 -> x - ((a + b) ushr 1)
                    else -> x - paeth(a, b, cc)
                }
                out[i] = v.toByte()
                sum += abs(out[i].toInt())
                if (sum >= bestSum) break
            }
            if (sum < bestSum) {
                bestSum = sum
                best = f
            }
        }
        raw[rp++] = best.toByte()
        System.arraycopy(candidates[best], 0, raw, rp, stride)
        rp += stride
        val t = prev
        prev = cur
        cur = t
    }
    val deflater = Deflater(level)
    val compressed = try {
        deflater.setInput(raw)
        deflater.finish()
        val bos = ByteArrayOutputStream(raw.size / 4 + 64)
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            bos.write(buf, 0, n)
        }
        bos.toByteArray()
    } finally {
        deflater.end()
    }
    val out = ByteArrayOutputStream(compressed.size + 64)
    out.write(PNG_SIGNATURE)
    val ihdr = ByteArray(13)
    putIntBE(ihdr, 0, w)
    putIntBE(ihdr, 4, h)
    ihdr[8] = 8
    ihdr[9] = if (withAlpha) 6 else 2
    writeChunk(out, "IHDR", ihdr)
    writeChunk(out, "IDAT", compressed)
    writeChunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
}

private fun paeth(a: Int, b: Int, c: Int): Int {
    val p = a + b - c
    val pa = abs(p - a)
    val pb = abs(p - b)
    val pc = abs(p - c)
    return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
}

private fun putIntBE(b: ByteArray, o: Int, v: Int) {
    b[o] = (v ushr 24).toByte()
    b[o + 1] = (v ushr 16).toByte()
    b[o + 2] = (v ushr 8).toByte()
    b[o + 3] = v.toByte()
}

private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val head = ByteArray(8)
    putIntBE(head, 0, data.size)
    for (i in 0 until 4) head[4 + i] = type[i].code.toByte()
    out.write(head)
    out.write(data)
    val crc = CRC32()
    crc.update(head, 4, 4)
    crc.update(data)
    val c = ByteArray(4)
    putIntBE(c, 0, crc.value.toInt())
    out.write(c)
}

/** Bildformat eingebetteter Texturen. */
internal enum class ImageFormat(val mime: String) { PNG("image/png"), JPEG("image/jpeg") }

/**
 * Kopfdaten eines Bildes.
 *
 * @property baseline JPEG: Rahmen `SOF0` (`FF C0`) — das Einzige, was gltfio sicher kann. PNG: immer `true`.
 * @property progressive JPEG: progressiver Rahmen (`FF C2`, `C6`, `CA`, `CE`).
 * @property hasAlpha PNG mit Alphakanal (Farbtyp 4 oder 6) oder Transparenz-Chunk ist nicht geprueft; JPEG: `false`.
 */
internal data class ImageInfo(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val baseline: Boolean,
    val progressive: Boolean,
    val hasAlpha: Boolean,
)

/** Liest Format und Groesse aus PNG- oder JPEG-Bytes; `null`, wenn unlesbar. */
internal fun imageInfo(bytes: ByteArray): ImageInfo? = runCatching { pngInfo(bytes) ?: jpegInfo(bytes) }.getOrNull()

/** Breite und Hoehe eines PNG, sonst `null`. */
internal fun pngSize(bytes: ByteArray): Pair<Int, Int>? = runCatching { pngInfo(bytes) }.getOrNull()?.let { it.width to it.height }

/** Breite und Hoehe eines JPEG, sonst `null`. */
internal fun jpegSize(bytes: ByteArray): Pair<Int, Int>? = runCatching { jpegInfo(bytes) }.getOrNull()?.let { it.width to it.height }

private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

private fun u32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
        ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

private fun pngInfo(b: ByteArray): ImageInfo? {
    if (b.size < 33) return null
    for (i in PNG_SIGNATURE.indices) if (b[i] != PNG_SIGNATURE[i]) return null
    if (u32(b, 8) != 13L || b[12] != 'I'.code.toByte() || b[13] != 'H'.code.toByte() ||
        b[14] != 'D'.code.toByte() || b[15] != 'R'.code.toByte()
    ) {
        return null
    }
    val w = u32(b, 16)
    val h = u32(b, 20)
    if (w <= 0 || h <= 0 || w > Int.MAX_VALUE || h > Int.MAX_VALUE) return null
    val colorType = b[25].toInt() and 0xFF
    return ImageInfo(ImageFormat.PNG, w.toInt(), h.toInt(), baseline = true, progressive = false, hasAlpha = colorType == 4 || colorType == 6)
}

private fun jpegInfo(b: ByteArray): ImageInfo? {
    if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
    var p = 2
    while (p + 3 < b.size) {
        if ((b[p].toInt() and 0xFF) != 0xFF) return null
        var marker = b[p + 1].toInt() and 0xFF
        // Fuellbytes 0xFF ueberspringen.
        while (marker == 0xFF && p + 2 < b.size) {
            p++
            marker = b[p + 1].toInt() and 0xFF
        }
        p += 2
        if (marker == 0x01 || marker in 0xD0..0xD7) continue
        if (marker == 0xD9 || marker == 0xDA) return null
        if (p + 1 >= b.size) return null
        val len = u16(b, p)
        if (len < 2) return null
        val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isSof) {
            if (p + 7 > b.size) return null
            val h = u16(b, p + 3)
            val w = u16(b, p + 5)
            if (w <= 0 || h <= 0) return null
            val progressive = marker == 0xC2 || marker == 0xC6 || marker == 0xCA || marker == 0xCE
            return ImageInfo(ImageFormat.JPEG, w, h, baseline = marker == 0xC0, progressive = progressive, hasAlpha = false)
        }
        p += len
    }
    return null
}
