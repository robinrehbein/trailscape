package de.trailscape.core.indoor.world

import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Beschriftung von Schildern (Ortstafel)
 *
 * Eigene Strichschrift in der Art einer serifenlosen Verkehrsschrift (Grossbuchstaben,
 * Kleinbuchstaben, Umlaute, ß, Ziffern, Bindestrich), selbst entworfen — keine fremde
 * Schriftdatei. Jede Glyphe sind Linienzuege in Einheiten (Grundlinie 0,
 * Versalhoehe 6, x-Hoehe 4,2, Unterlaenge −2). [townSign] zeichnet daraus eine
 * Ortstafel (gelb, schwarzer Rand, Name schwarz) als Bild; Akzente fallen auf den
 * Grundbuchstaben zurueck, Unbekanntes wird zum Leerzeichen.
 */
internal object SignText {
    private class Glyph(val width: Double, val strokes: List<DoubleArray>)

    private fun arc(cx: Double, cy: Double, rx: Double, ry: Double, a0: Double, a1: Double, n: Int = 14): DoubleArray {
        val out = DoubleArray((n + 1) * 2)
        for (i in 0..n) {
            val a = Math.toRadians(a0 + (a1 - a0) * i / n)
            out[2 * i] = cx + rx * cos(a)
            out[2 * i + 1] = cy + ry * sin(a)
        }
        return out
    }

    private fun p(vararg v: Double) = v

    private fun cat(vararg parts: DoubleArray): DoubleArray {
        val n = parts.sumOf { it.size }
        val out = DoubleArray(n)
        var k = 0
        for (q in parts) { q.copyInto(out, k); k += q.size }
        return out
    }

    private val GLYPHS: Map<Char, Glyph> by lazy {
        val m = HashMap<Char, Glyph>()
        fun g(c: Char, w: Double, vararg s: DoubleArray) { m[c] = Glyph(w, s.toList()) }
        val ovalO = arc(2.0, 3.0, 2.0, 3.0, 0.0, 360.0, 24)
        val ovalLow = arc(1.6, 2.1, 1.6, 2.1, 0.0, 360.0, 20)
        // Grossbuchstaben
        g('A', 3.6, p(0.0, 0.0, 1.8, 6.0, 3.6, 0.0), p(0.6, 2.0, 3.0, 2.0))
        g('B', 3.8, cat(p(0.0, 0.0, 0.0, 6.0, 2.1, 6.0), arc(2.1, 4.55, 1.45, 1.45, 90.0, -90.0), p(0.0, 3.1)), cat(p(2.1, 3.1), arc(2.2, 1.55, 1.6, 1.55, 90.0, -90.0), p(0.0, 0.0)))
        g('C', 3.8, arc(2.0, 3.0, 2.0, 3.0, 45.0, 315.0, 20))
        g('D', 3.8, cat(p(0.0, 0.0, 0.0, 6.0, 1.6, 6.0), arc(1.6, 3.0, 2.0, 3.0, 90.0, -90.0), p(0.0, 0.0)))
        g('E', 3.1, p(3.1, 6.0, 0.0, 6.0, 0.0, 0.0, 3.1, 0.0), p(0.0, 3.1, 2.6, 3.1))
        g('F', 3.0, p(3.0, 6.0, 0.0, 6.0, 0.0, 0.0), p(0.0, 3.1, 2.5, 3.1))
        g('G', 4.0, arc(2.0, 3.0, 2.0, 3.0, 40.0, 330.0, 20), p(2.3, 2.7, 4.0, 2.7, 4.0, 0.6))
        g('H', 3.5, p(0.0, 0.0, 0.0, 6.0), p(3.5, 0.0, 3.5, 6.0), p(0.0, 3.1, 3.5, 3.1))
        g('I', 0.6, p(0.3, 0.0, 0.3, 6.0))
        g('J', 2.6, cat(p(2.4, 6.0, 2.4, 1.4), arc(1.2, 1.4, 1.2, 1.4, 0.0, -180.0, 10)))
        g('K', 3.4, p(0.0, 0.0, 0.0, 6.0), p(3.3, 6.0, 0.0, 2.4), p(1.2, 3.6, 3.4, 0.0))
        g('L', 2.9, p(0.0, 6.0, 0.0, 0.0, 2.9, 0.0))
        g('M', 4.4, p(0.0, 0.0, 0.0, 6.0, 2.2, 1.4, 4.4, 6.0, 4.4, 0.0))
        g('N', 3.5, p(0.0, 0.0, 0.0, 6.0, 3.5, 0.0, 3.5, 6.0))
        g('O', 4.0, ovalO)
        g('P', 3.5, cat(p(0.0, 0.0, 0.0, 6.0, 1.9, 6.0), arc(1.9, 4.4, 1.6, 1.6, 90.0, -90.0), p(0.0, 2.8)))
        g('Q', 4.1, ovalO, p(2.5, 1.2, 4.1, -0.4))
        g('R', 3.5, cat(p(0.0, 0.0, 0.0, 6.0, 1.9, 6.0), arc(1.9, 4.4, 1.6, 1.6, 90.0, -90.0), p(0.0, 2.8)), p(1.8, 2.8, 3.5, 0.0))
        g('S', 3.5, p(3.3, 5.3, 2.7, 5.95, 1.0, 5.95, 0.2, 5.2, 0.2, 3.9, 1.0, 3.2, 2.6, 2.8, 3.4, 2.0, 3.4, 0.8, 2.6, 0.05, 0.9, 0.05, 0.1, 0.7))
        g('T', 3.6, p(0.0, 6.0, 3.6, 6.0), p(1.8, 6.0, 1.8, 0.0))
        g('U', 3.5, cat(p(0.0, 6.0, 0.0, 1.7), arc(1.75, 1.7, 1.75, 1.7, 180.0, 360.0, 12), p(3.5, 6.0)))
        g('V', 3.6, p(0.0, 6.0, 1.8, 0.0, 3.6, 6.0))
        g('W', 5.0, p(0.0, 6.0, 1.25, 0.0, 2.5, 5.0, 3.75, 0.0, 5.0, 6.0))
        g('X', 3.5, p(0.0, 6.0, 3.5, 0.0), p(0.0, 0.0, 3.5, 6.0))
        g('Y', 3.5, p(0.0, 6.0, 1.75, 3.0, 3.5, 6.0), p(1.75, 3.0, 1.75, 0.0))
        g('Z', 3.3, p(0.0, 6.0, 3.2, 6.0, 0.0, 0.0, 3.3, 0.0))
        // Kleinbuchstaben
        g('a', 3.0, cat(arc(1.5, 3.0, 1.4, 1.2, 160.0, 0.0, 8), p(2.9, 0.0)), p(2.9, 2.1, 1.2, 2.2, 0.2, 1.5, 0.2, 0.6, 1.0, 0.0, 2.2, 0.2, 2.9, 0.9))
        g('b', 3.2, p(0.0, 6.0, 0.0, 0.0), ovalLow)
        g('c', 3.1, arc(1.6, 2.1, 1.6, 2.1, 40.0, 320.0, 16))
        g('d', 3.2, p(3.2, 6.0, 3.2, 0.0), ovalLow)
        g('e', 3.2, cat(p(0.0, 2.2, 3.2, 2.2), arc(1.6, 2.1, 1.6, 2.1, 0.0, 320.0, 18)))
        g('f', 2.2, p(2.2, 5.9, 1.6, 6.0, 1.0, 5.6, 1.0, 0.0), p(0.0, 4.2, 2.1, 4.2))
        g('g', 3.2, ovalLow, cat(p(3.2, 4.2, 3.2, -0.8), arc(1.6, -0.8, 1.6, 1.2, 0.0, -180.0, 10)))
        g('h', 3.0, p(0.0, 6.0, 0.0, 0.0), cat(arc(1.5, 2.7, 1.5, 1.5, 180.0, 0.0, 10), p(3.0, 0.0)))
        g('i', 0.6, p(0.3, 0.0, 0.3, 4.2), p(0.3, 5.3, 0.3, 5.9))
        g('j', 1.4, p(1.0, 4.2, 1.0, -1.3, 0.5, -1.95, 0.0, -1.95), p(1.0, 5.3, 1.0, 5.9))
        g('k', 3.0, p(0.0, 0.0, 0.0, 6.0), p(2.8, 4.2, 0.0, 1.6), p(1.0, 2.5, 3.0, 0.0))
        g('l', 1.0, p(0.0, 6.0, 0.0, 0.6, 0.4, 0.0, 1.0, 0.0))
        g('m', 4.8, p(0.0, 0.0, 0.0, 4.2), cat(arc(1.2, 3.0, 1.2, 1.2, 180.0, 0.0, 8), p(2.4, 0.0)), cat(arc(3.6, 3.0, 1.2, 1.2, 180.0, 0.0, 8), p(4.8, 0.0)))
        g('n', 3.0, p(0.0, 0.0, 0.0, 4.2), cat(arc(1.5, 2.7, 1.5, 1.5, 180.0, 0.0, 10), p(3.0, 0.0)))
        g('o', 3.2, ovalLow)
        g('p', 3.2, p(0.0, 4.2, 0.0, -2.0), ovalLow)
        g('q', 3.2, p(3.2, 4.2, 3.2, -2.0), ovalLow)
        g('r', 2.3, p(0.0, 0.0, 0.0, 4.2), arc(1.6, 2.8, 1.6, 1.4, 180.0, 60.0, 8))
        g('s', 2.9, p(2.7, 3.7, 2.0, 4.2, 0.9, 4.2, 0.2, 3.6, 0.3, 2.7, 1.2, 2.2, 2.1, 2.0, 2.8, 1.4, 2.7, 0.5, 1.9, 0.0, 0.8, 0.0, 0.1, 0.5))
        g('t', 2.1, p(0.9, 5.6, 0.9, 0.7, 1.4, 0.0, 2.1, 0.1), p(0.0, 4.2, 2.0, 4.2))
        g('u', 3.0, cat(p(0.0, 4.2, 0.0, 1.5), arc(1.5, 1.5, 1.5, 1.5, 180.0, 360.0, 10)), p(3.0, 4.2, 3.0, 0.0))
        g('v', 3.1, p(0.0, 4.2, 1.55, 0.0, 3.1, 4.2))
        g('w', 4.6, p(0.0, 4.2, 1.15, 0.0, 2.3, 3.6, 3.45, 0.0, 4.6, 4.2))
        g('x', 3.0, p(0.0, 4.2, 3.0, 0.0), p(0.0, 0.0, 3.0, 4.2))
        g('y', 3.2, p(0.0, 4.2, 1.6, 0.0), p(3.2, 4.2, 1.2, -1.4, 0.6, -2.0, 0.0, -2.0))
        g('z', 3.0, p(0.0, 4.2, 2.9, 4.2, 0.0, 0.0, 3.0, 0.0))
        g('ß', 3.1, p(0.0, 0.0, 0.0, 4.8), cat(arc(1.3, 4.8, 1.3, 1.2, 180.0, 0.0, 8), p(2.4, 3.8, 1.4, 3.4, 2.6, 2.8, 3.0, 1.6, 2.5, 0.4, 1.6, 0.0, 1.0, 0.1)))
        // Umlaute: Grundbuchstabe und zwei Punkte
        for ((u, base, y) in listOf(Triple('Ä', 'A', 7.0), Triple('Ö', 'O', 7.0), Triple('Ü', 'U', 7.0), Triple('ä', 'a', 5.3), Triple('ö', 'o', 5.3), Triple('ü', 'u', 5.3))) {
            val b = m.getValue(base)
            val c = b.width / 2
            m[u] = Glyph(b.width, b.strokes + listOf(p(c - 0.8, y, c - 0.8, y + 0.5), p(c + 0.8, y, c + 0.8, y + 0.5)))
        }
        // Ziffern und Zeichen
        g('0', 3.2, arc(1.6, 3.0, 1.6, 3.0, 0.0, 360.0, 20))
        g('1', 2.0, p(0.0, 4.6, 1.6, 6.0, 1.6, 0.0))
        g('2', 3.2, cat(arc(1.6, 4.4, 1.5, 1.5, 160.0, -20.0, 10), p(0.0, 0.0, 3.2, 0.0)))
        g('3', 3.2, cat(arc(1.5, 4.6, 1.4, 1.35, 150.0, -90.0, 10)), cat(arc(1.5, 1.6, 1.6, 1.6, 90.0, -150.0, 12)))
        g('4', 3.4, p(2.6, 0.0, 2.6, 6.0, 0.0, 1.8, 3.4, 1.8))
        g('5', 3.2, cat(p(3.0, 6.0, 0.4, 6.0, 0.2, 3.4), arc(1.6, 1.9, 1.6, 1.9, 120.0, -150.0, 12)))
        g('6', 3.2, cat(p(2.8, 6.0, 1.6, 6.0), arc(1.6, 3.0, 1.6, 3.0, 90.0, 180.0, 6)), arc(1.6, 1.8, 1.6, 1.8, 0.0, 360.0, 16))
        g('7', 3.2, p(0.0, 6.0, 3.2, 6.0, 1.0, 0.0))
        g('8', 3.2, arc(1.6, 4.6, 1.3, 1.4, 0.0, 360.0, 14), arc(1.6, 1.6, 1.6, 1.6, 0.0, 360.0, 16))
        g('9', 3.2, arc(1.6, 4.2, 1.6, 1.8, 0.0, 360.0, 16), cat(arc(1.6, 3.0, 1.6, 3.0, 0.0, -90.0, 6), p(0.4, 0.0)))
        g('-', 2.2, p(0.2, 2.4, 2.0, 2.4))
        g('.', 0.6, p(0.3, 0.0, 0.3, 0.4))
        g('/', 2.4, p(0.0, -0.5, 2.4, 6.3))
        g('(', 1.4, arc(2.0, 2.6, 1.8, 4.0, 115.0, 245.0, 8))
        g(')', 1.4, arc(-0.6, 2.6, 1.8, 4.0, 65.0, -65.0, 8))
        g(' ', 1.8)
        m
    }

    /** Text auf darstellbare Zeichen gebracht (Akzente → Grundbuchstabe). */
    fun normalize(text: String): String {
        val sb = StringBuilder()
        for (ch in text.trim()) {
            if (GLYPHS.containsKey(ch)) { sb.append(ch); continue }
            val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ' '
            sb.append(if (GLYPHS.containsKey(base)) base else ' ')
        }
        return sb.toString().replace(Regex(" +"), " ")
    }

    /** Breite des Texts in Einheiten (Zeichenabstand 1,0, Wortabstand im Leerzeichen). */
    fun width(text: String): Double {
        var w = 0.0
        for ((i, ch) in text.withIndex()) {
            w += (GLYPHS[ch] ?: GLYPHS.getValue(' ')).width
            if (i < text.length - 1) w += 1.0
        }
        return w
    }

    /**
     * Ortstafel ([w] × [h] Pixel, Zweierpotenzen): gelb (RAL 1023), schwarzer Rand mit runden Ecken,
     * der Name schwarz, so gross, dass er hineinpasst. [aspect] ist das Seitenverhaeltnis der Platte
     * im Modell (Breite/Hoehe): Die Schrift wird so gestaucht, dass sie dort unverzerrt steht.
     */
    fun townSign(name: String, aspect: Double, w: Int = 512, h: Int = 256): RgbaImage {
        val text = normalize(name)
        val img = RgbaImage(w, h)
        val yellow = intArrayOf(247, 186, 11)
        val black = intArrayOf(22, 22, 22)
        val cov = FloatArray(w * h)
        // Rand: abgerundetes Rechteck, 4 % innen, 3 % breit
        val inset = h * 0.07
        val bw = h * 0.045
        val rad = h * 0.14
        for (y in 0 until h) for (x in 0 until w) {
            val d = roundRectDist(x + 0.5, y + 0.5, inset, inset, w - inset, h - inset, rad)
            val c = (bw / 2 + 0.5 - kotlin.math.abs(d + bw / 2)).coerceIn(0.0, 1.0)
            cov[y * w + x] = max(cov[y * w + x], c.toFloat())
        }
        if (text.isNotBlank()) {
            // Masstab: Versalhoehe 42 % der Hoehe, hoechstens 84 % der Breite (im Modell gemessen)
            val sx = w / (aspect * h) // Pixel je Modell-Pixel waagrecht
            val tw = width(text)
            var unit = h * 0.42 / 6.0
            if (tw * unit * sx > w * 0.84) unit = w * 0.84 / (tw * sx)
            val stroke = unit * 0.78
            val x0 = (w - tw * unit * sx) / 2
            val base = h * 0.5 + 3.0 * unit - (if (text.any { it in "gjpqy" }) unit * 0.3 else 0.0)
            var cx = x0
            for (ch in text) {
                val gl = GLYPHS[ch] ?: GLYPHS.getValue(' ')
                for (s in gl.strokes) {
                    if (s.size == 2) {
                        drawSegment(cov, w, h, cx + s[0] * unit * sx, base - s[1] * unit, cx + s[0] * unit * sx, base - s[1] * unit, stroke)
                    }
                    for (i in 0 until s.size / 2 - 1) {
                        drawSegment(cov, w, h, cx + s[2 * i] * unit * sx, base - s[2 * i + 1] * unit, cx + s[2 * i + 2] * unit * sx, base - s[2 * i + 3] * unit, stroke)
                    }
                }
                cx += (gl.width + 1.0) * unit * sx
            }
        }
        for (i in cov.indices) {
            val c = cov[i].toDouble()
            img.argb[i] = Argb.rgb(
                (yellow[0] + (black[0] - yellow[0]) * c).toInt(),
                (yellow[1] + (black[1] - yellow[1]) * c).toInt(),
                (yellow[2] + (black[2] - yellow[2]) * c).toInt(),
            )
        }
        return img
    }

    private fun roundRectDist(x: Double, y: Double, x0: Double, y0: Double, x1: Double, y1: Double, r: Double): Double {
        val qx = max(x0 + r - x, x - (x1 - r))
        val qy = max(y0 + r - y, y - (y1 - r))
        val ox = max(qx, 0.0)
        val oy = max(qy, 0.0)
        return hypot(ox, oy) + min(max(qx, qy), 0.0) - r
    }

    /** Dicker Strich mit runden Enden, Kantenglaettung ueber den Abstand. */
    private fun drawSegment(cov: FloatArray, w: Int, h: Int, x0: Double, y0: Double, x1: Double, y1: Double, width: Double) {
        val r = width / 2
        val minX = max(0, (min(x0, x1) - r - 1).toInt())
        val maxX = min(w - 1, (max(x0, x1) + r + 1).toInt())
        val minY = max(0, (min(y0, y1) - r - 1).toInt())
        val maxY = min(h - 1, (max(y0, y1) + r + 1).toInt())
        val dx = x1 - x0
        val dy = y1 - y0
        val l2 = dx * dx + dy * dy
        for (y in minY..maxY) for (x in minX..maxX) {
            val px = x + 0.5
            val py = y + 0.5
            val t = if (l2 < 1e-9) 0.0 else (((px - x0) * dx + (py - y0) * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(px - (x0 + t * dx), py - (y0 + t * dy))
            val c = (r + 0.5 - d).coerceIn(0.0, 1.0).toFloat()
            if (c > cov[y * w + x]) cov[y * w + x] = c
        }
    }
}
