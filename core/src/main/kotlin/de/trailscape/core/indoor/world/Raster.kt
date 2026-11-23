package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * # Rasterbilder, Farben und Rauschen fuer die prozeduralen Texturen
 *
 * Grundlage des Weltbaus: [RgbaImage] ist ein schlichtes ARGB-Bild auf einem
 * `IntArray` (keine Objekte je Pixel), [Argb] buendelt die Farbhelfer,
 * [Noise] das deterministische Rauschen (auch kachelbar).
 */

/**
 * Ein Bild im Arbeitsspeicher: je Pixel `0xAARRGGBB` (nicht vormultipliziert),
 * zeilenweise von oben links — dasselbe Layout wie [RasterImage].
 */
internal class RgbaImage(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "Bildgroesse muss positiv sein: ${width}x$height" }
        require(argb.size == width * height) { "Pixelzahl passt nicht: ${argb.size} statt ${width * height}" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    operator fun set(x: Int, y: Int, color: Int) {
        argb[y * width + x] = color
    }

    /** Pixel mit an den Rand geklemmten Koordinaten. */
    fun getClamped(x: Int, y: Int): Int = argb[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    /** Pixel mit umlaufenden Koordinaten (kachelnde Texturen). */
    fun getWrapped(x: Int, y: Int): Int = argb[Math.floorMod(y, height) * width + Math.floorMod(x, width)]

    fun fill(color: Int) {
        argb.fill(color)
    }

    /** Fuellt das Rechteck `[x0, x1) × [y0, y1)` (geklemmt). */
    fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val ax = max(0, x0)
        val bx = min(width, x1)
        for (y in max(0, y0) until min(height, y1)) {
            if (ax < bx) argb.fill(color, y * width + ax, y * width + bx)
        }
    }

    fun copy(): RgbaImage = RgbaImage(width, height, argb.copyOf())

    /** Teilt das Pixelfeld (keine Kopie). */
    fun toRaster(): RasterImage = RasterImage(width, height, argb)

    /**
     * Bilinear abgetastet an Pixelkoordinaten ([x], [y]); Pixelmitten liegen
     * bei `i + 0,5`. [wrap] = umlaufend (kachelnd), sonst am Rand geklemmt.
     * Gemischt wird im sRGB-Raum je Kanal.
     */
    fun sampleBilinearPx(x: Double, y: Double, wrap: Boolean = false): Int {
        val fx = x - 0.5
        val fy = y - 0.5
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val c00: Int
        val c10: Int
        val c01: Int
        val c11: Int
        if (wrap) {
            c00 = getWrapped(x0, y0); c10 = getWrapped(x0 + 1, y0)
            c01 = getWrapped(x0, y0 + 1); c11 = getWrapped(x0 + 1, y0 + 1)
        } else {
            c00 = getClamped(x0, y0); c10 = getClamped(x0 + 1, y0)
            c01 = getClamped(x0, y0 + 1); c11 = getClamped(x0 + 1, y0 + 1)
        }
        return Argb.bilinear(c00, c10, c01, c11, tx, ty)
    }

    /** Bilinear an Texturkoordinaten `u, v ∈ [0, 1]` (v = 0 oben, wie glTF). */
    fun sampleBilinear(u: Double, v: Double, wrap: Boolean = false): Int =
        sampleBilinearPx(u * width, v * height, wrap)

    /**
     * Flaechentreue Verkleinerung (Kastenfilter mit Bruchteil-Gewichten), in
     * linearem Licht und mit Alpha gewichtet — keine dunklen Saeume an
     * Laubraendern. Fuer Vergroesserung taugt [resizeBilinear] besser.
     */
    fun resizeBox(newWidth: Int, newHeight: Int, linearLight: Boolean = true): RgbaImage {
        if (newWidth == width && newHeight == height) return copy()
        val wx = BoxWeights(width, newWidth)
        val wy = BoxWeights(height, newHeight)
        // Zwischenpuffer: neue Breite × alte Hoehe, 4 Kanaele (vormultipliziert, linear).
        val tmp = FloatArray(newWidth * height * 4)
        val toLin = if (linearLight) Argb.SRGB_TO_LINEAR else Argb.BYTE_TO_UNIT
        for (y in 0 until height) {
            val row = y * width
            for (ox in 0 until newWidth) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                val s = wx.start[ox]
                val o = wx.offset[ox]
                for (k in 0 until wx.count[ox]) {
                    val c = argb[row + s + k]
                    val w = wx.weight[o + k]
                    val ca = ((c ushr 24) and 0xFF) * (1f / 255f) * w
                    r += toLin[(c shr 16) and 0xFF] * ca
                    g += toLin[(c shr 8) and 0xFF] * ca
                    b += toLin[c and 0xFF] * ca
                    a += ca
                }
                val t = (y * newWidth + ox) * 4
                tmp[t] = r; tmp[t + 1] = g; tmp[t + 2] = b; tmp[t + 3] = a
            }
        }
        val out = IntArray(newWidth * newHeight)
        for (oy in 0 until newHeight) {
            val s = wy.start[oy]
            val o = wy.offset[oy]
            for (ox in 0 until newWidth) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (k in 0 until wy.count[oy]) {
                    val t = ((s + k) * newWidth + ox) * 4
                    val w = wy.weight[o + k]
                    r += tmp[t] * w; g += tmp[t + 1] * w; b += tmp[t + 2] * w; a += tmp[t + 3] * w
                }
                val alpha = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
                val c = if (a > 1e-6f) {
                    val inv = 1f / a
                    if (linearLight) {
                        Argb.of(alpha, Argb.linearToSrgb8(r * inv), Argb.linearToSrgb8(g * inv), Argb.linearToSrgb8(b * inv))
                    } else {
                        Argb.of(alpha, Argb.unitTo8(r * inv), Argb.unitTo8(g * inv), Argb.unitTo8(b * inv))
                    }
                } else {
                    0
                }
                out[oy * newWidth + ox] = c
            }
        }
        return RgbaImage(newWidth, newHeight, out)
    }

    /** Bilineare Groessenaenderung (Pixelmitten aufeinander abgebildet). */
    fun resizeBilinear(newWidth: Int, newHeight: Int, wrap: Boolean = false): RgbaImage {
        val out = RgbaImage(newWidth, newHeight)
        val sx = width.toDouble() / newWidth
        val sy = height.toDouble() / newHeight
        for (y in 0 until newHeight) {
            val py = (y + 0.5) * sy
            for (x in 0 until newWidth) out.argb[y * newWidth + x] = sampleBilinearPx((x + 0.5) * sx, py, wrap)
        }
        return out
    }

    /** Kopiert [src] an ([dx], [dy]), ausserhalb abgeschnitten (ohne Ueberblenden). */
    fun blit(src: RgbaImage, dx: Int, dy: Int) {
        val x0 = max(0, dx)
        val x1 = min(width, dx + src.width)
        if (x0 >= x1) return
        for (y in max(0, dy) until min(height, dy + src.height)) {
            System.arraycopy(src.argb, (y - dy) * src.width + (x0 - dx), argb, y * width + x0, x1 - x0)
        }
    }

    /** Legt [src] mit Alpha („over") und zusaetzlicher Deckkraft [opacity] an ([dx], [dy]). */
    fun blitOver(src: RgbaImage, dx: Int, dy: Int, opacity: Double = 1.0) {
        val x0 = max(0, dx)
        val x1 = min(width, dx + src.width)
        for (y in max(0, dy) until min(height, dy + src.height)) {
            val so = (y - dy) * src.width - dx
            val doff = y * width
            for (x in x0 until x1) {
                argb[doff + x] = Argb.over(argb[doff + x], src.argb[so + x], opacity)
            }
        }
    }

    companion object {
        /** Teilt das Pixelfeld des Vertragsbildes (keine Kopie). */
        fun of(raster: RasterImage): RgbaImage = RgbaImage(raster.width, raster.height, raster.argb)
    }
}

/** Gewichte einer flaechentreuen 1D-Abbildung von [src] auf [dst] Pixel. */
private class BoxWeights(src: Int, dst: Int) {
    val start = IntArray(dst)
    val count = IntArray(dst)
    val offset = IntArray(dst)
    val weight: FloatArray

    init {
        val scale = src.toDouble() / dst
        val list = ArrayList<Float>(dst * (scale.toInt() + 2))
        for (o in 0 until dst) {
            var a = o * scale
            var b = (o + 1) * scale
            if (scale < 1.0) {
                // Vergroesserung: ein Pixel breites Fenster um die Mitte, sonst leer.
                val c = (o + 0.5) * scale
                a = (c - 0.5).coerceAtLeast(0.0)
                b = (a + 1.0).coerceAtMost(src.toDouble())
                a = b - 1.0
            }
            val s = floor(a).toInt().coerceIn(0, src - 1)
            val e = (kotlin.math.ceil(b).toInt()).coerceIn(s + 1, src)
            start[o] = s
            count[o] = e - s
            offset[o] = list.size
            val total = b - a
            for (i in s until e) {
                val cover = min(b, i + 1.0) - max(a, i.toDouble())
                list.add((cover.coerceAtLeast(0.0) / total).toFloat())
            }
        }
        weight = FloatArray(list.size) { list[it] }
    }
}

/** Farbhelfer fuer `0xAARRGGBB`-Werte. */
internal object Argb {
    const val WHITE: Int = -1
    const val BLACK: Int = 0xFF000000.toInt()
    const val TRANSPARENT: Int = 0

    /** sRGB-Byte → lineares Licht `[0, 1]`. */
    val SRGB_TO_LINEAR = FloatArray(256) { srgbToLinear(it / 255.0).toFloat() }

    /** Byte → `[0, 1]` ohne Gammakurve. */
    val BYTE_TO_UNIT = FloatArray(256) { it / 255f }

    private const val LIN_STEPS = 16384
    private val LINEAR_TO_SRGB = ByteArray(LIN_STEPS + 1) {
        (linearToSrgb(it.toDouble() / LIN_STEPS) * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
    }

    fun of(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun rgb(r: Int, g: Int, b: Int): Int = of(255, r, g, b)

    /** Aus Kanaelen `[0, 1]` (sRGB). */
    fun ofUnit(r: Double, g: Double, b: Double, a: Double = 1.0): Int =
        of(unitTo8(a.toFloat()), unitTo8(r.toFloat()), unitTo8(g.toFloat()), unitTo8(b.toFloat()))

    fun alpha(c: Int): Int = c ushr 24
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun unitTo8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

    /** Lineares Licht `[0, 1]` → sRGB-Byte (Tabelle, schnell). */
    fun linearToSrgb8(v: Float): Int {
        val i = (v * LIN_STEPS + 0.5f).toInt().coerceIn(0, LIN_STEPS)
        return LINEAR_TO_SRGB[i].toInt() and 0xFF
    }

    /** Lineare Mischung aller vier Kanaele, `t = 0` → [a], `t = 1` → [b]. */
    fun mix(a: Int, b: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        fun ch(s: Int): Int {
            val x = (a ushr s) and 0xFF
            val y = (b ushr s) and 0xFF
            return (x + (y - x) * u + 0.5).toInt()
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Mischung in linearem Licht (fuer Farbverlaeufe, die nicht absaufen sollen). */
    fun mixLinear(a: Int, b: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0).toFloat()
        fun ch(s: Int): Int {
            val x = SRGB_TO_LINEAR[(a ushr s) and 0xFF]
            val y = SRGB_TO_LINEAR[(b ushr s) and 0xFF]
            return linearToSrgb8(x + (y - x) * u)
        }
        val al = (alpha(a) + (alpha(b) - alpha(a)) * u + 0.5f).toInt()
        return of(al, ch(16), ch(8), ch(0))
    }

    fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, tx: Double, ty: Double): Int {
        val w00 = (1 - tx) * (1 - ty)
        val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty
        val w11 = tx * ty
        fun ch(s: Int): Int = (((c00 ushr s) and 0xFF) * w00 + ((c10 ushr s) and 0xFF) * w10 +
            ((c01 ushr s) and 0xFF) * w01 + ((c11 ushr s) and 0xFF) * w11 + 0.5).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Kanalweise Multiplikation (Abtoenung), Alpha inbegriffen. */
    fun multiply(a: Int, b: Int): Int {
        fun ch(s: Int): Int = (((a ushr s) and 0xFF) * ((b ushr s) and 0xFF) + 127) / 255
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Helligkeit mal [factor] (RGB, Alpha bleibt). */
    fun scale(c: Int, factor: Double): Int =
        of(alpha(c), (red(c) * factor + 0.5).toInt(), (green(c) * factor + 0.5).toInt(), (blue(c) * factor + 0.5).toInt())

    /** Relative Leuchtdichte `[0, 1]` (Rec. 709, linear). */
    fun luminance(c: Int): Double =
        0.2126 * SRGB_TO_LINEAR[red(c)] + 0.7152 * SRGB_TO_LINEAR[green(c)] + 0.0722 * SRGB_TO_LINEAR[blue(c)]

    /** RGB → HSV in [out] (`h` in Grad `[0, 360)`, `s`, `v` in `[0, 1]`). */
    fun toHsv(c: Int, out: DoubleArray) {
        val r = red(c) / 255.0
        val g = green(c) / 255.0
        val b = blue(c) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0.0 -> 0.0
            mx == r -> 60.0 * (((g - b) / d) % 6.0)
            mx == g -> 60.0 * ((b - r) / d + 2.0)
            else -> 60.0 * ((r - g) / d + 4.0)
        }
        if (h < 0) h += 360.0
        out[0] = h
        out[1] = if (mx == 0.0) 0.0 else d / mx
        out[2] = mx
    }

    fun fromHsv(h: Double, s: Double, v: Double, a: Int = 255): Int {
        val hh = ((h % 360.0) + 360.0) % 360.0
        val ss = s.coerceIn(0.0, 1.0)
        val vv = v.coerceIn(0.0, 1.0)
        val c = vv * ss
        val x = c * (1 - abs((hh / 60.0) % 2.0 - 1))
        val m = vv - c
        val (r, g, b) = when ((hh / 60.0).toInt()) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return of(a, ((r + m) * 255 + 0.5).toInt(), ((g + m) * 255 + 0.5).toInt(), ((b + m) * 255 + 0.5).toInt())
    }

    /** Verschiebt den Farbton um [dHue] Grad und multipliziert Saettigung und Hellwert. */
    fun shiftHsv(c: Int, dHue: Double, satFactor: Double = 1.0, valFactor: Double = 1.0): Int {
        val hsv = DoubleArray(3)
        toHsv(c, hsv)
        return fromHsv(hsv[0] + dHue, hsv[1] * satFactor, hsv[2] * valFactor, alpha(c))
    }

    /**
     * „Over"-Ueberblendung nicht vormultiplizierter Farben: [src] (mit seinem
     * Alpha mal [opacity]) ueber [dst].
     */
    fun over(dst: Int, src: Int, opacity: Double = 1.0): Int {
        val sa = alpha(src) / 255.0 * opacity
        if (sa <= 0.0) return dst
        val da = alpha(dst) / 255.0
        val oa = sa + da * (1 - sa)
        if (oa <= 0.0) return 0
        fun ch(s: Int): Int {
            val sc = (src ushr s) and 0xFF
            val dc = (dst ushr s) and 0xFF
            return ((sc * sa + dc * da * (1 - sa)) / oa + 0.5).toInt()
        }
        return of((oa * 255 + 0.5).toInt(), ch(16), ch(8), ch(0))
    }
}

/**
 * Deterministisches Rauschen. Koordinaten in Gitterzellen; `period > 0` macht
 * die Funktion in x und y periodisch mit [period] Zellen (kachelnde
 * Texturen). Fuer eine Textur von `N` Pixeln mit `C` Zellen je Kachel:
 * `x = px · C / N`, `period = C` — oder gleich [tiledFbm].
 */
internal object Noise {
    private const val GRAD_COUNT = 256
    private val GRAD_X = DoubleArray(GRAD_COUNT) { kotlin.math.cos(2 * Math.PI * it / GRAD_COUNT) }
    private val GRAD_Y = DoubleArray(GRAD_COUNT) { kotlin.math.sin(2 * Math.PI * it / GRAD_COUNT) }

    /** 32-Bit-Durchmischung (Murmur3-Finalisierer). */
    fun mix(h0: Int): Int {
        var h = h0
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }

    fun hash(x: Int, y: Int, seed: Int): Int = mix(seed xor mix(x * 0x27d4eb2d + mix(y * 0x165667b1 + 0x3c6ef372)))

    fun hash(x: Int, y: Int, z: Int, seed: Int): Int = hash(x, mix(y xor (z * -0x61c88647)), seed)

    /** Gleichverteilt in `[0, 1)`. */
    fun hash01(x: Int, y: Int, seed: Int): Double = (hash(x, y, seed) ushr 8) / 16777216.0

    fun hash01(x: Int, y: Int, z: Int, seed: Int): Double = (hash(x, y, z, seed) ushr 8) / 16777216.0

    /** Fester Samen aus einer Kennung (z. B. Kachel-ID, OSM-ID). */
    fun seedOf(text: String): Int {
        var h = 0x811c9dc5.toInt()
        for (ch in text) h = (h xor ch.code) * 0x01000193
        return mix(h)
    }

    fun seedOf(a: Long, b: Long = 0): Int = mix(mix((a xor (a ushr 32)).toInt()) + 31 * mix((b xor (b ushr 32)).toInt()))

    private fun wrap(i: Int, period: Int): Int = if (period > 0) Math.floorMod(i, period) else i

    private fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)

    /** Wertrauschen in `[0, 1]`, glatt (quintische Ueberblendung). */
    fun value(x: Double, y: Double, seed: Int, period: Int = 0): Double {
        val xf = floor(x)
        val yf = floor(y)
        val ix = xf.toInt()
        val iy = yf.toInt()
        val tx = fade(x - xf)
        val ty = fade(y - yf)
        val x0 = wrap(ix, period)
        val x1 = wrap(ix + 1, period)
        val y0 = wrap(iy, period)
        val y1 = wrap(iy + 1, period)
        val a = hash01(x0, y0, seed)
        val b = hash01(x1, y0, seed)
        val c = hash01(x0, y1, seed)
        val d = hash01(x1, y1, seed)
        val ab = a + (b - a) * tx
        val cd = c + (d - c) * tx
        return ab + (cd - ab) * ty
    }

    /** Gradientenrauschen (Perlin-Art), etwa in `[-1, 1]`. */
    fun gradient(x: Double, y: Double, seed: Int, period: Int = 0): Double {
        val xf = floor(x)
        val yf = floor(y)
        val ix = xf.toInt()
        val iy = yf.toInt()
        val fx = x - xf
        val fy = y - yf
        val x0 = wrap(ix, period)
        val x1 = wrap(ix + 1, period)
        val y0 = wrap(iy, period)
        val y1 = wrap(iy + 1, period)
        val g00 = hash(x0, y0, seed) and (GRAD_COUNT - 1)
        val g10 = hash(x1, y0, seed) and (GRAD_COUNT - 1)
        val g01 = hash(x0, y1, seed) and (GRAD_COUNT - 1)
        val g11 = hash(x1, y1, seed) and (GRAD_COUNT - 1)
        val n00 = GRAD_X[g00] * fx + GRAD_Y[g00] * fy
        val n10 = GRAD_X[g10] * (fx - 1) + GRAD_Y[g10] * fy
        val n01 = GRAD_X[g01] * fx + GRAD_Y[g01] * (fy - 1)
        val n11 = GRAD_X[g11] * (fx - 1) + GRAD_Y[g11] * (fy - 1)
        val u = fade(fx)
        val v = fade(fy)
        val a = n00 + (n10 - n00) * u
        val b = n01 + (n11 - n01) * u
        return (a + (b - a) * v) * 1.4142135623730951
    }

    /**
     * Fraktales Rauschen aus [octaves] Lagen Gradientenrauschen, etwa in
     * `[-1, 1]`. Mit `period > 0` verdoppelt jede Lage Frequenz und Periode
     * (dann bleibt das Ergebnis kachelbar; [lacunarity] wird ignoriert).
     */
    fun fbm(
        x: Double,
        y: Double,
        seed: Int,
        octaves: Int = 5,
        period: Int = 0,
        lacunarity: Double = 2.0,
        gain: Double = 0.5,
    ): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fy = y
        var p = period
        val lac = if (period > 0) 2.0 else lacunarity
        for (o in 0 until octaves) {
            sum += gradient(fx, fy, seed + o * 1013, p) * amp
            norm += amp
            amp *= gain
            fx *= lac
            fy *= lac
            if (p > 0) p *= 2
        }
        return sum / norm
    }

    /** Wie [fbm], aber aus Wertrauschen, in `[0, 1]`. */
    fun fbmValue(x: Double, y: Double, seed: Int, octaves: Int = 5, period: Int = 0, gain: Double = 0.5): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var fx = x
        var fy = y
        var p = period
        for (o in 0 until octaves) {
            sum += value(fx, fy, seed + o * 1013, p) * amp
            norm += amp
            amp *= gain
            fx *= 2.0
            fy *= 2.0
            if (p > 0) p *= 2
        }
        return sum / norm
    }

    /**
     * Kachelbares [fbm] an Pixelkoordinaten: Textur von [sizePx] Pixeln Kante
     * mit [cells] Grundzellen je Kachel. `f(px + sizePx, py) = f(px, py)`.
     */
    fun tiledFbm(px: Double, py: Double, sizePx: Int, cells: Int, seed: Int, octaves: Int = 5, gain: Double = 0.5): Double {
        val s = cells.toDouble() / sizePx
        return fbm(px * s, py * s, seed, octaves, cells, 2.0, gain)
    }

    /**
     * Zellrauschen (Worley): schreibt nach [out] den Abstand zum naechsten
     * (`out[0]`) und zweitnaechsten Zellpunkt (`out[1]`) in Zellen sowie
     * eine Kennzahl `[0, 1)` der naechsten Zelle (`out[2]`). Kachelbar mit
     * `period > 0`.
     */
    fun worley(x: Double, y: Double, seed: Int, period: Int, out: DoubleArray) {
        val xf = floor(x)
        val yf = floor(y)
        val ix = xf.toInt()
        val iy = yf.toInt()
        var f1 = Double.MAX_VALUE
        var f2 = Double.MAX_VALUE
        var id = 0.0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val cx = wrap(ix + dx, period)
                val cy = wrap(iy + dy, period)
                val h = hash(cx, cy, seed)
                val jx = (h and 0xFFFF) / 65536.0
                val jy = ((h ushr 16) and 0xFFFF) / 65536.0
                val px = ix + dx + jx - x
                val py = iy + dy + jy - y
                val d = sqrt(px * px + py * py)
                if (d < f1) {
                    f2 = f1
                    f1 = d
                    id = (mix(h) ushr 8) / 16777216.0
                } else if (d < f2) {
                    f2 = d
                }
            }
        }
        out[0] = f1
        out[1] = f2
        out[2] = id
    }

    /** Abstand zum naechsten Zellpunkt (siehe [worley]). */
    fun worleyF1(x: Double, y: Double, seed: Int, period: Int = 0): Double {
        val o = DoubleArray(3)
        worley(x, y, seed, period, o)
        return o[0]
    }
}
