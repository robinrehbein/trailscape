package de.trailscape.core.indoor.world

import kotlin.math.max
import kotlin.math.min

/**
 * # Waldboden unter dem Kronendach
 *
 * Das Luftbild zeigt im Wald die Baumkronen **von oben** (im Winterbild graublaue,
 * unbelaubte Kronen). Als Bodentextur unter den 3D-Baeumen wirkte das vom Rad aus
 * wie Beton oder eine graublaue Wand (Fehlerbericht Solitude, `docs/indoor/lidar.md`,
 * Abschnitt 9). Unter dichtem Bewuchs wird das Luftbild deshalb zum Waldboden
 * (Laubstreu, Moos, Erde) abgetoent; die Helligkeit des Luftbilds bleibt als leichte
 * Modulation erhalten (Lichtungen, Wege und Schatten bleiben erkennbar).
 *
 * Bewuchs: [LandMap.canopyAt] — mit Laserdaten das nDOM (Anteil ≥ 3 m in 9 × 9 m,
 * Kahlschlaege nach dem Luftbild ausgenommen), sonst die Schaetzung aus der
 * Luftbildfarbe bzw. die Landnutzung. Nur Stufe 0: Stufe 1 und 2 sieht man aus der
 * Ferne, dort ist das Kronendach von oben richtig.
 */
internal object ForestFloor {
    /** Ab dieser Bedeckung beginnt die Abtoenung, bei [FULL] ist sie voll ([STRENGTH]). */
    const val START = 0.35
    const val FULL = 0.85

    /** Anteil des Waldbodens bei voller Bedeckung (der Rest bleibt Luftbild). */
    const val STRENGTH = 0.82

    /** Raster der Bedeckung je Kachel (Meter). */
    private const val GRID_M = 2.0

    /**
     * Das abgetoente Luftbild der Kachel [spec] und seine JPEG-Kodierung, oder `null`, wenn nichts
     * abzutoenen ist (kein dichter Bewuchs) — dann bleibt das Original samt Bytes des Dienstes.
     */
    fun apply(img: RgbaImage, spec: TileSpec, land: LandMap, jpegQuality: Int = 85): Pair<RgbaImage, EncodedTexture>? {
        if (spec.level != 0) return null
        val cells = (spec.cellM / GRID_M).toInt()
        val g = cells + 1
        val wgt = FloatArray(g * g)
        var any = false
        for (j in 0 until g) for (i in 0 until g) {
            val e = spec.minE + i * GRID_M
            val n = spec.maxN - j * GRID_M
            val c = land.canopyAt(e, n)
            // Ohne Messung (nDOM) ist die Bedeckung aus der Luftbildfarbe geschaetzt — dunkle Wiesen und Schatten
            // zaehlten mit. Dort nur im Wald der Karte abtoenen.
            val measured = (land as? LandRaster)?.laser?.canopy?.invoke(e, n)?.let { !it.isNaN() } ?: false
            val w = if (c.isNaN() || (!measured && land.classAt(e, n) != LandClass.FOREST)) 0.0 else smoothstep(START, FULL, c) * STRENGTH
            wgt[j * g + i] = w.toFloat()
            if (w > 0.02) any = true
        }
        if (!any) return null
        val wpx = img.width
        val hpx = img.height
        // Mittlere Helligkeit unter dem Kronendach: Bezug der Modulation.
        var sumL = 0.0
        var cntL = 0
        for (y in 0 until hpx step 4) for (x in 0 until wpx step 4) {
            if (weightAt(wgt, g, cells, x, y, wpx, hpx) < 0.3f) continue
            sumL += luma(img[x, y]); cntL++
        }
        val meanL = if (cntL > 0) max(0.06, sumL / cntL) else 0.3
        val out = RgbaImage(wpx, hpx, img.argb.copyOf())
        val mpp = spec.cellM / wpx
        for (y in 0 until hpx) for (x in 0 until wpx) {
            val w = weightAt(wgt, g, cells, x, y, wpx, hpx)
            if (w <= 0.01f) continue
            val src = img[x, y]
            val e = spec.minE + (x + 0.5) * mpp
            val n = spec.maxN - (y + 0.5) * mpp
            // Waldboden: Laubstreu (braun) mit Moosflecken und dunkler Erde, zwei Rauschlagen (3 m / 0,8 m).
            val big = Noise.value(e / 3.0, n / 3.0, SEED)
            val fine = Noise.value(e / 0.8, n / 0.8, SEED + 7)
            val moss = smoothstep(0.55, 0.75, big)
            val soil = smoothstep(0.6, 0.85, fine) * 0.5
            var r = LITTER[0] + (MOSS[0] - LITTER[0]) * moss
            var gg = LITTER[1] + (MOSS[1] - LITTER[1]) * moss
            var b = LITTER[2] + (MOSS[2] - LITTER[2]) * moss
            r += (SOIL[0] - r) * soil; gg += (SOIL[1] - gg) * soil; b += (SOIL[2] - b) * soil
            val mod = (0.55 + 0.45 * luma(src) / meanL).coerceIn(0.45, 1.35) * (0.92 + 0.16 * fine)
            r *= mod; gg *= mod; b *= mod
            val sr = Argb.red(src).toDouble()
            val sg = Argb.green(src).toDouble()
            val sb = Argb.blue(src).toDouble()
            out[x, y] = Argb.rgb(
                mixByte(sr, r, w), mixByte(sg, gg, w), mixByte(sb, b, w),
            )
        }
        val bytes = encodeJpegBaseline(out, jpegQuality)
        return out to EncodedTexture(bytes, "image/jpeg", wpx, hpx)
    }

    private fun mixByte(a: Double, b: Double, w: Float): Int = (a + (b - a) * w).toInt().coerceIn(0, 255)

    private fun weightAt(wgt: FloatArray, g: Int, cells: Int, x: Int, y: Int, wpx: Int, hpx: Int): Float {
        val fx = (x + 0.5) / wpx * cells
        val fy = (y + 0.5) / hpx * cells
        val ix = min(fx.toInt(), cells - 1)
        val iy = min(fy.toInt(), cells - 1)
        val tx = (fx - ix).toFloat()
        val ty = (fy - iy).toFloat()
        val a = wgt[iy * g + ix]
        val b = wgt[iy * g + ix + 1]
        val c = wgt[(iy + 1) * g + ix]
        val d = wgt[(iy + 1) * g + ix + 1]
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty
    }

    private fun luma(c: Int): Double = (0.299 * Argb.red(c) + 0.587 * Argb.green(c) + 0.114 * Argb.blue(c)) / 255.0

    /** sRGB-Grundfarben. */
    private val LITTER = doubleArrayOf(98.0, 80.0, 56.0)
    private val MOSS = doubleArrayOf(72.0, 84.0, 46.0)
    private val SOIL = doubleArrayOf(62.0, 50.0, 38.0)
    private val SEED = Noise.seedOf("waldboden")
}
