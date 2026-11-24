package de.trailscape.core.indoor.sky

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Boden-Detail aus der Naehe
 *
 * Eine kachelbare Hoehen-/Helligkeitstextur fuer das Gelaende-Material der
 * Darstellung (`app/src/main/materials/terrain.mat`): **vier Bodenarten in den
 * vier Kanaelen** einer RGBA8-Textur, jede eine Hoehe 0..1 mit Mittel 0,5 —
 *
 *  * **R Wiese:** dichte Grasbueschel und Halme, dazwischen dunkle Luecken,
 *  * **G Acker/Erde:** Schollen und Kluten, feine Kruemel,
 *  * **B Waldboden:** uebereinanderliegende Blaetter, Nadeln und Zweige,
 *  * **A Schotter/Fels:** gerundete Steine 5–30 cm mit dunklen Fugen.
 *
 * Das Material waehlt die Bodenart je Pixel aus der Farbe des Luftbilds (und der
 * Hangneigung), moduliert damit nur die **Helligkeit** des Luftbilds (das Mittel
 * bleibt, die Farbe des Luftbilds bleibt) und leitet aus Differenzen derselben
 * Textur eine Detail-Normale ab.
 *
 * Alles im Code gezeichnet (kein fremdes Bild, keine Lizenzfrage); 256² Texel
 * decken [REPEAT_M] Meter, also 2 cm je Texel: Strukturen von 5–60 cm, die man auch in
 * 10–50 m Abstand noch sieht (feineres verschwindet dort in den Mip-Stufen). Deterministisch.
 */
object GroundDetail {
    /** Kantenlaenge der Textur in Texeln. */
    const val SIZE = 256

    /** Meter je Wiederholung; [TILE_REPEATS] Wiederholungen je Kachel der Stufe 0 (256 m). */
    const val REPEAT_M = 5.12
    const val TILE_REPEATS = 50.0

    /** Wiederholungen der groben Helligkeitsvariation (Wolkenrauschen) je Kachel: 32 m. */
    const val MACRO_REPEATS = 8.0

    /** Die Textur als RGBA8, Zeile fuer Zeile (linear, kein sRGB). */
    fun texture(size: Int = SIZE): ByteArray {
        val ch = arrayOf(grass(size), soil(size), litter(size), stones(size))
        for (c in ch) normalize(c)
        val out = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            for (k in 0 until 4) out[i * 4 + k] = (ch[k][i] * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Bodenarten
    // ------------------------------------------------------------------------------------------

    /** Wiese von oben: Bueschel (helle Flecken), Halme (kurze helle Striche), dunkle Luecken. */
    private fun grass(n: Int): DoubleArray {
        val h = DoubleArray(n * n)
        val rnd = Rng(11)
        // Grundrauschen: Bueschel 6–12 cm, fein darunter
        for (y in 0 until n) for (x in 0 until n) {
            h[y * n + x] = 0.45 * fbm(x, y, n, 8, 3, 101) + 0.35 * fbm(x, y, n, 32, 2, 102)
        }
        // Halme: kurze, leicht gebogene Striche in alle Richtungen, hell an der Spitze
        repeat(n * n / 28) {
            val x0 = rnd.next() * n
            val y0 = rnd.next() * n
            val a = rnd.next() * 2 * PI
            val len = 4 + rnd.next() * 8
            val bright = 0.25 + 0.35 * rnd.next()
            val steps = (len * 2).toInt()
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val bend = 0.25 * t * t
                val px = x0 + cos(a + bend) * len * t
                val py = y0 + sin(a + bend) * len * t
                add(h, n, px, py, bright * (0.4 + 0.6 * t), 0.7)
            }
        }
        return h
    }

    /** Acker/Erde: Schollen (Zellen mit gewoelbter Oberflaeche), Kruemel, dunkle Risse. */
    private fun soil(n: Int): DoubleArray {
        val h = DoubleArray(n * n)
        val cells = cellNoise(n, 11, 201)
        val fine = DoubleArray(n * n) { i -> fbm(i % n, i / n, n, 32, 2, 202) }
        for (i in 0 until n * n) {
            val (f1, f2) = cells[i]
            val edge = smooth(0.0, 0.30, f2 - f1) // 0 an der Fuge (weich: Schollen, keine Risse)
            val dome = 1.0 - min(1.0, f1 * 1.4)
            h[i] = 0.22 * edge + 0.28 * dome + 0.50 * fine[i]
        }
        // Kleine Kluten
        val rnd = Rng(23)
        repeat(n * n / 120) {
            val x = rnd.next() * n
            val y = rnd.next() * n
            val r = 1.0 + rnd.next() * 2.5
            blob(h, n, x, y, r, r, 0.0, 0.18 + 0.15 * rnd.next())
        }
        return h
    }

    /** Waldboden: Blaetter (Ellipsen mit Mittelrippe), Nadeln, Zweige, Schatten dazwischen. */
    private fun litter(n: Int): DoubleArray {
        val h = DoubleArray(n * n) { i -> 0.35 * fbm(i % n, i / n, n, 4, 3, 301) }
        val rnd = Rng(31)
        // Blaetter: uebereinander, die spaeteren liegen oben (hoeher und heller)
        repeat(n * n / 45) { k ->
            val x = rnd.next() * n
            val y = rnd.next() * n
            val a = rnd.next() * PI
            val rl = 2.0 + rnd.next() * 3.0
            val rw = rl * (0.45 + 0.2 * rnd.next())
            val top = 0.35 + 0.5 * k / (n * n / 45.0) + 0.15 * rnd.next()
            leaf(h, n, x, y, rl, rw, a, top)
        }
        // Zweige und Nadeln: duenne Striche
        repeat(n * n / 400) {
            val x0 = rnd.next() * n
            val y0 = rnd.next() * n
            val a = rnd.next() * 2 * PI
            val len = 6 + rnd.next() * 30
            val steps = (len * 2).toInt()
            val v = 0.5 + 0.4 * rnd.next()
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                add(h, n, x0 + cos(a) * len * t, y0 + sin(a) * len * t, v, 0.9)
            }
        }
        return h
    }

    /** Schotter/Fels: gerundete Steine verschiedener Groesse, dunkle Fugen, leichte Kanten. */
    private fun stones(n: Int): DoubleArray {
        val h = DoubleArray(n * n)
        val big = cellNoise(n, 8, 401)
        val small = cellNoise(n, 24, 402)
        val bigTone = DoubleArray(n * n)
        for (i in 0 until n * n) {
            val (b1, b2) = big[i]
            val (s1, s2) = small[i]
            val bigStone = smooth(0.0, 0.12, b2 - b1) * (1.0 - 0.6 * b1)
            val smallStone = smooth(0.0, 0.10, s2 - s1) * (1.0 - 0.6 * s1)
            bigTone[i] = bigStone
            h[i] = 0.55 * bigStone + 0.35 * smallStone + 0.10 * fbm(i % n, i / n, n, 64, 1, 403)
        }
        return h
    }

    // ------------------------------------------------------------------------------------------
    // Bausteine (alles periodisch mit Periode n)
    // ------------------------------------------------------------------------------------------

    /** Kleiner, deterministischer Zufall (xorshift). */
    private class Rng(seed: Int) {
        private var s = (seed.toLong() * 0x9E3779B97F4A7C15uL.toLong()) or 1L
        fun next(): Double {
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble())
        }
    }

    private fun hash(ix: Int, iy: Int, seed: Int): Double {
        var h = ix * 374761393 + iy * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF) / 2147483647.0
    }

    /** Periodisches Wertrauschen mit [period] Zellen ueber die Textur. */
    private fun valueNoise(x: Int, y: Int, n: Int, period: Int, seed: Int): Double {
        val fx = x.toDouble() / n * period
        val fy = y.toDouble() / n * period
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        val sx = tx * tx * (3 - 2 * tx)
        val sy = ty * ty * (3 - 2 * ty)
        fun c(i: Int, j: Int) = hash(Math.floorMod(i, period), Math.floorMod(j, period), seed)
        val a = c(ix, iy) + (c(ix + 1, iy) - c(ix, iy)) * sx
        val b = c(ix, iy + 1) + (c(ix + 1, iy + 1) - c(ix, iy + 1)) * sx
        return a + (b - a) * sy
    }

    private fun fbm(x: Int, y: Int, n: Int, period: Int, octaves: Int, seed: Int): Double {
        var sum = 0.0
        var amp = 1.0
        var norm = 0.0
        var p = period
        for (k in 0 until octaves) {
            if (p > n) break
            sum += amp * valueNoise(x, y, n, p, seed + k * 17)
            norm += amp
            amp *= 0.5
            p *= 2
        }
        return sum / norm
    }

    /** Zellrauschen (Worley): je Texel Abstand zum naechsten und zweitnaechsten Punkt, in Zellbreiten. */
    private fun cellNoise(n: Int, cells: Int, seed: Int): Array<Pair<Double, Double>> {
        val cw = n.toDouble() / cells
        return Array(n * n) { i ->
            val x = (i % n + 0.5) / cw
            val y = (i / n + 0.5) / cw
            val cx = floor(x).toInt()
            val cy = floor(y).toInt()
            var d1 = 9.0
            var d2 = 9.0
            for (j in -1..1) for (k in -1..1) {
                val gx = cx + k
                val gy = cy + j
                val wx = Math.floorMod(gx, cells)
                val wy = Math.floorMod(gy, cells)
                val px = gx + 0.15 + 0.7 * hash(wx, wy, seed)
                val py = gy + 0.15 + 0.7 * hash(wx, wy, seed + 1)
                val d = sqrt((px - x) * (px - x) + (py - y) * (py - y))
                if (d < d1) {
                    d2 = d1
                    d1 = d
                } else if (d < d2) {
                    d2 = d
                }
            }
            d1 to d2
        }
    }

    /** Weicher Punkt (Radius [r] Texel) mit Hoehe [v]: das Maximum gewinnt (ueberdeckt). */
    private fun add(h: DoubleArray, n: Int, x: Double, y: Double, v: Double, r: Double) {
        val x0 = floor(x - r - 1).toInt()
        val y0 = floor(y - r - 1).toInt()
        for (j in y0..(y0 + (2 * r + 2).toInt())) for (i in x0..(x0 + (2 * r + 2).toInt())) {
            val d = sqrt((i + 0.5 - x) * (i + 0.5 - x) + (j + 0.5 - y) * (j + 0.5 - y))
            val w = 1.0 - smooth(r * 0.5, r + 0.5, d)
            if (w <= 0) continue
            val idx = Math.floorMod(j, n) * n + Math.floorMod(i, n)
            h[idx] = max(h[idx], h[idx] * (1 - w) + v * w)
        }
    }

    /** Gedrehte Ellipse ([rx], [ry] Texel, Winkel [a]) als gewoelbter Huegel der Hoehe [v] (Maximum). */
    private fun blob(h: DoubleArray, n: Int, x: Double, y: Double, rx: Double, ry: Double, a: Double, v: Double) {
        val r = max(rx, ry) + 1
        val ca = cos(a)
        val sa = sin(a)
        for (j in floor(y - r).toInt()..(y + r).toInt()) for (i in floor(x - r).toInt()..(x + r).toInt()) {
            val dx = i + 0.5 - x
            val dy = j + 0.5 - y
            val u = (dx * ca + dy * sa) / rx
            val w = (-dx * sa + dy * ca) / ry
            val q = u * u + w * w
            if (q >= 1) continue
            val idx = Math.floorMod(j, n) * n + Math.floorMod(i, n)
            h[idx] += v * sqrt(1 - q)
        }
    }

    /** Ein Blatt: flache Ellipse mit Mittelrippe, liegt auf dem Bisherigen ([top] = Hoehe der Oberseite). */
    private fun leaf(h: DoubleArray, n: Int, x: Double, y: Double, rl: Double, rw: Double, a: Double, top: Double) {
        val r = rl + 1
        val ca = cos(a)
        val sa = sin(a)
        for (j in floor(y - r).toInt()..(y + r).toInt()) for (i in floor(x - r).toInt()..(x + r).toInt()) {
            val dx = i + 0.5 - x
            val dy = j + 0.5 - y
            val u = (dx * ca + dy * sa) / rl
            val w = (-dx * sa + dy * ca) / rw
            // Blattform: spitz an beiden Enden
            val width = 1.0 - u * u
            if (abs(u) >= 1 || abs(w) >= width) continue
            val idx = Math.floorMod(j, n) * n + Math.floorMod(i, n)
            val rib = exp(-(w * w) * 60.0) * 0.06
            val curl = 0.08 * (abs(w) / width)
            h[idx] = top + rib + curl
        }
    }

    private fun smooth(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Auf Mittel 0,5 und eine Streuung von rund ±0,22 (2σ ≈ 0,9 der Spanne) bringen. */
    private fun normalize(h: DoubleArray) {
        var mean = 0.0
        for (v in h) mean += v
        mean /= h.size
        var varSum = 0.0
        for (v in h) varSum += (v - mean) * (v - mean)
        val sd = sqrt(varSum / h.size).takeIf { it > 1e-9 } ?: 1.0
        for (i in h.indices) h[i] = (0.5 + (h[i] - mean) / sd * 0.2).coerceIn(0.0, 1.0)
    }
}
