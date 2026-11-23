package de.trailscape.core.indoor.world

/**
 * # Baseline-JPEG in reinem Kotlin
 *
 * gltfio dekodiert nur Baseline-JPEG (`SOF0`), Android hat in `:core` keinen
 * Encoder — also ein eigener: JFIF, YCbCr, Standard-Huffman-Tabellen,
 * Quantisierung skaliert wie libjpeg, schnelle Gleitkomma-DCT (AAN).
 * Alpha wird verworfen.
 */

/**
 * Kodiert [img] als Baseline-JPEG. [quality] 1–100 wie libjpeg;
 * [subsample420] halbiert die Farbaufloesung in beide Richtungen (kleiner,
 * fuer Fotos/Gelaende unsichtbar), sonst 4:4:4.
 */
internal fun encodeJpegBaseline(img: RgbaImage, quality: Int = 85, subsample420: Boolean = true): ByteArray =
    JpegWriter(quality.coerceIn(1, 100), subsample420).encode(img)

private object JpegTables {
    /** Zickzack-Position → natuerlicher Index im 8×8-Block. */
    val NATURAL = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    val STD_LUMA_Q = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    val STD_CHROMA_Q = intArrayOf(
        17, 18, 24, 47, 99, 99, 99, 99,
        18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
    )

    // Anzahl Codes je Laenge 1..16 und Symbole (ITU T.81, Anhang K.3).
    val DC_LUMA_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    val DC_LUMA_VALS = IntArray(12) { it }
    val DC_CHROMA_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
    val DC_CHROMA_VALS = IntArray(12) { it }
    val AC_LUMA_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
    val AC_LUMA_VALS = intArrayOf(
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
        0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
        0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
        0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
        0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
        0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa,
    )
    val AC_CHROMA_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
    val AC_CHROMA_VALS = intArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
        0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
        0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
        0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
        0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
        0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
        0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
        0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
        0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
        0xf9, 0xfa,
    )

    /** AAN-Skalierung je Zeile/Spalte. */
    val AAN = doubleArrayOf(1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379)
}

/** Huffman-Codes je Symbol (Code und Laenge). */
private class HuffTable(val bits: IntArray, val vals: IntArray) {
    val code = IntArray(256)
    val size = IntArray(256)

    init {
        var c = 0
        var k = 0
        for (len in 1..16) {
            repeat(bits[len - 1]) {
                val sym = vals[k++]
                code[sym] = c
                size[sym] = len
                c++
            }
            c = c shl 1
        }
    }
}

private class JpegWriter(quality: Int, private val sub420: Boolean) {
    private val qLuma = scaledTable(JpegTables.STD_LUMA_Q, quality)
    private val qChroma = scaledTable(JpegTables.STD_CHROMA_Q, quality)
    private val divLuma = divisors(qLuma)
    private val divChroma = divisors(qChroma)
    private val dcLuma = HuffTable(JpegTables.DC_LUMA_BITS, JpegTables.DC_LUMA_VALS)
    private val acLuma = HuffTable(JpegTables.AC_LUMA_BITS, JpegTables.AC_LUMA_VALS)
    private val dcChroma = HuffTable(JpegTables.DC_CHROMA_BITS, JpegTables.DC_CHROMA_VALS)
    private val acChroma = HuffTable(JpegTables.AC_CHROMA_BITS, JpegTables.AC_CHROMA_VALS)

    private var out = ByteArray(1 shl 16)
    private var len = 0
    private var bitBuf = 0L
    private var bitCnt = 0

    private val block = FloatArray(64)
    private val quant = IntArray(64)

    private fun scaledTable(std: IntArray, quality: Int): IntArray {
        val scale = if (quality < 50) 5000 / quality else 200 - quality * 2
        return IntArray(64) { ((std[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    /** Kehrwert der Teiler je natuerlichem Index (AAN-Skalierung eingerechnet). */
    private fun divisors(q: IntArray): FloatArray = FloatArray(64) {
        val r = it / 8
        val c = it % 8
        (1.0 / (q[it] * JpegTables.AAN[r] * JpegTables.AAN[c] * 8.0)).toFloat()
    }

    fun encode(img: RgbaImage): ByteArray {
        val w = img.width
        val h = img.height
        val mcu = if (sub420) 16 else 8
        val pw = (w + mcu - 1) / mcu * mcu
        val ph = (h + mcu - 1) / mcu * mcu
        // Farbumrechnung in volle Ebenen (Rand wiederholt), Pegel um −128 verschoben.
        val yp = FloatArray(pw * ph)
        val cbp = FloatArray(pw * ph)
        val crp = FloatArray(pw * ph)
        for (y in 0 until ph) {
            val sy = if (y < h) y else h - 1
            val srow = sy * w
            val drow = y * pw
            for (x in 0 until pw) {
                val c = img.argb[srow + (if (x < w) x else w - 1)]
                val r = ((c shr 16) and 0xFF).toFloat()
                val g = ((c shr 8) and 0xFF).toFloat()
                val b = (c and 0xFF).toFloat()
                yp[drow + x] = 0.299f * r + 0.587f * g + 0.114f * b - 128f
                cbp[drow + x] = -0.168736f * r - 0.331264f * g + 0.5f * b
                crp[drow + x] = 0.5f * r - 0.418688f * g - 0.081312f * b
            }
        }
        val cw: Int
        val cb: FloatArray
        val cr: FloatArray
        if (sub420) {
            cw = pw / 2
            val chh = ph / 2
            cb = FloatArray(cw * chh)
            cr = FloatArray(cw * chh)
            for (y in 0 until chh) {
                val r0 = 2 * y * pw
                val r1 = r0 + pw
                for (x in 0 until cw) {
                    val i = 2 * x
                    cb[y * cw + x] = 0.25f * (cbp[r0 + i] + cbp[r0 + i + 1] + cbp[r1 + i] + cbp[r1 + i + 1])
                    cr[y * cw + x] = 0.25f * (crp[r0 + i] + crp[r0 + i + 1] + crp[r1 + i] + crp[r1 + i + 1])
                }
            }
        } else {
            cw = pw
            cb = cbp
            cr = crp
        }

        writeHeaders(w, h)
        var dcY = 0
        var dcCb = 0
        var dcCr = 0
        for (my in 0 until ph / mcu) {
            for (mx in 0 until pw / mcu) {
                if (sub420) {
                    val x0 = mx * 16
                    val y0 = my * 16
                    dcY = codeBlock(yp, pw, x0, y0, divLuma, dcY, dcLuma, acLuma)
                    dcY = codeBlock(yp, pw, x0 + 8, y0, divLuma, dcY, dcLuma, acLuma)
                    dcY = codeBlock(yp, pw, x0, y0 + 8, divLuma, dcY, dcLuma, acLuma)
                    dcY = codeBlock(yp, pw, x0 + 8, y0 + 8, divLuma, dcY, dcLuma, acLuma)
                    dcCb = codeBlock(cb, cw, mx * 8, my * 8, divChroma, dcCb, dcChroma, acChroma)
                    dcCr = codeBlock(cr, cw, mx * 8, my * 8, divChroma, dcCr, dcChroma, acChroma)
                } else {
                    val x0 = mx * 8
                    val y0 = my * 8
                    dcY = codeBlock(yp, pw, x0, y0, divLuma, dcY, dcLuma, acLuma)
                    dcCb = codeBlock(cb, cw, x0, y0, divChroma, dcCb, dcChroma, acChroma)
                    dcCr = codeBlock(cr, cw, x0, y0, divChroma, dcCr, dcChroma, acChroma)
                }
            }
        }
        // Restbits mit Einsen auffuellen.
        if (bitCnt > 0) writeBits(0x7F, 8 - bitCnt)
        putByte(0xFF)
        putByte(0xD9)
        return out.copyOf(len)
    }

    private fun writeHeaders(w: Int, h: Int) {
        putByte(0xFF); putByte(0xD8)
        // APP0 JFIF 1.01, Seitenverhaeltnis 1:1.
        putByte(0xFF); putByte(0xE0); putShort(16)
        for (ch in "JFIF") putByte(ch.code)
        putByte(0); putByte(1); putByte(1); putByte(0); putShort(1); putShort(1); putByte(0); putByte(0)
        // DQT: zwei 8-Bit-Tabellen in Zickzack-Reihenfolge.
        putByte(0xFF); putByte(0xDB); putShort(2 + 2 * 65)
        putByte(0)
        for (k in 0 until 64) putByte(qLuma[JpegTables.NATURAL[k]])
        putByte(1)
        for (k in 0 until 64) putByte(qChroma[JpegTables.NATURAL[k]])
        // SOF0 Baseline.
        putByte(0xFF); putByte(0xC0); putShort(17)
        putByte(8); putShort(h); putShort(w); putByte(3)
        putByte(1); putByte(if (sub420) 0x22 else 0x11); putByte(0)
        putByte(2); putByte(0x11); putByte(1)
        putByte(3); putByte(0x11); putByte(1)
        // DHT: vier Standardtabellen.
        val tables = listOf(
            0x00 to dcLuma, 0x10 to acLuma, 0x01 to dcChroma, 0x11 to acChroma,
        )
        val dhtLen = 2 + tables.sumOf { 17 + it.second.vals.size }
        putByte(0xFF); putByte(0xC4); putShort(dhtLen)
        for ((cls, t) in tables) {
            putByte(cls)
            for (b in t.bits) putByte(b)
            for (v in t.vals) putByte(v)
        }
        // SOS.
        putByte(0xFF); putByte(0xDA); putShort(12); putByte(3)
        putByte(1); putByte(0x00)
        putByte(2); putByte(0x11)
        putByte(3); putByte(0x11)
        putByte(0); putByte(63); putByte(0)
    }

    private fun codeBlock(
        plane: FloatArray,
        stride: Int,
        x0: Int,
        y0: Int,
        div: FloatArray,
        prevDc: Int,
        dcT: HuffTable,
        acT: HuffTable,
    ): Int {
        val d = block
        for (r in 0 until 8) {
            System.arraycopy(plane, (y0 + r) * stride + x0, d, r * 8, 8)
        }
        fdct(d)
        for (k in 0 until 64) {
            val n = JpegTables.NATURAL[k]
            val v = d[n] * div[n]
            quant[k] = if (v >= 0f) (v + 0.5f).toInt() else -((-v + 0.5f).toInt())
        }
        // DC
        val diff = quant[0] - prevDc
        val dcCat = category(diff)
        writeBits(dcT.code[dcCat], dcT.size[dcCat])
        if (dcCat > 0) writeBits(if (diff < 0) diff - 1 else diff, dcCat)
        // AC
        var last = 63
        while (last > 0 && quant[last] == 0) last--
        var run = 0
        for (k in 1..last) {
            val v = quant[k]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                writeBits(acT.code[0xF0], acT.size[0xF0])
                run -= 16
            }
            val cat = category(v)
            val sym = (run shl 4) or cat
            writeBits(acT.code[sym], acT.size[sym])
            writeBits(if (v < 0) v - 1 else v, cat)
            run = 0
        }
        if (last < 63) writeBits(acT.code[0x00], acT.size[0x00])
        return quant[0]
    }

    private fun category(v: Int): Int {
        val a = if (v < 0) -v else v
        return 32 - Integer.numberOfLeadingZeros(a)
    }

    /** Vorwaerts-DCT nach Arai/Agui/Nakajima (wie libjpeg jfdctflt), ungeskaliert. */
    private fun fdct(d: FloatArray) {
        for (p in 0 until 64 step 8) pass(d, p, 1)
        for (p in 0 until 8) pass(d, p, 8)
    }

    private fun pass(d: FloatArray, o: Int, s: Int) {
        val d0 = d[o]; val d1 = d[o + s]; val d2 = d[o + 2 * s]; val d3 = d[o + 3 * s]
        val d4 = d[o + 4 * s]; val d5 = d[o + 5 * s]; val d6 = d[o + 6 * s]; val d7 = d[o + 7 * s]
        val tmp0 = d0 + d7
        val tmp7 = d0 - d7
        val tmp1 = d1 + d6
        val tmp6 = d1 - d6
        val tmp2 = d2 + d5
        val tmp5 = d2 - d5
        val tmp3 = d3 + d4
        val tmp4 = d3 - d4
        // Gerader Teil
        var tmp10 = tmp0 + tmp3
        val tmp13 = tmp0 - tmp3
        var tmp11 = tmp1 + tmp2
        var tmp12 = tmp1 - tmp2
        d[o] = tmp10 + tmp11
        d[o + 4 * s] = tmp10 - tmp11
        val z1 = (tmp12 + tmp13) * 0.70710677f
        d[o + 2 * s] = tmp13 + z1
        d[o + 6 * s] = tmp13 - z1
        // Ungerader Teil
        tmp10 = tmp4 + tmp5
        tmp11 = tmp5 + tmp6
        tmp12 = tmp6 + tmp7
        val z5 = (tmp10 - tmp12) * 0.38268343f
        val z2 = 0.5411961f * tmp10 + z5
        val z4 = 1.306563f * tmp12 + z5
        val z3 = tmp11 * 0.70710677f
        val z11 = tmp7 + z3
        val z13 = tmp7 - z3
        d[o + 5 * s] = z13 + z2
        d[o + 3 * s] = z13 - z2
        d[o + s] = z11 + z4
        d[o + 7 * s] = z11 - z4
    }

    private fun writeBits(value: Int, n: Int) {
        if (n == 0) return
        bitBuf = (bitBuf shl n) or (value.toLong() and ((1L shl n) - 1))
        bitCnt += n
        while (bitCnt >= 8) {
            val b = ((bitBuf shr (bitCnt - 8)) and 0xFF).toInt()
            putByte(b)
            if (b == 0xFF) putByte(0)
            bitCnt -= 8
        }
        bitBuf = bitBuf and ((1L shl bitCnt) - 1)
    }

    private fun putByte(b: Int) {
        if (len == out.size) out = out.copyOf(out.size * 2)
        out[len++] = b.toByte()
    }

    private fun putShort(v: Int) {
        putByte(v ushr 8)
        putByte(v and 0xFF)
    }
}
