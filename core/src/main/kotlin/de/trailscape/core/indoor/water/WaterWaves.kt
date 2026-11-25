package de.trailscape.core.indoor.water

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Wellen-Normalenkarte des Wassers (`water.mat`, Parameter `waveNormals`)
 *
 * Prozedural und reproduzierbar (fester Startwert, eigener Zufallsgenerator, keine
 * fremden Texturen): ein Hoehenfeld als Summe von [WAVE_COUNT] Kosinuswellen mit
 * **ganzzahligen Wellenvektoren** — dadurch ist die Karte nahtlos kachelbar, ohne
 * dass am Rand etwas geglaettet werden muss. Die Steigungen sind analytisch (keine
 * Differenzen, also kein Treppeneffekt in den Mipmaps).
 *
 * Spektrum: Wellenzahlen [K_MIN]..[K_MAX] Perioden je Kachel, Amplitude ∝ k^-1,5
 * (Steigung ∝ k^-0,5: feine Kraeuselung traegt, ohne zu rauschen), mild gerichtet
 * (cos² um die Hauptrichtung +x, aber nie null — der Shader dreht drei Lagen in
 * verschiedene Richtungen, eine reine Richtung wuerde als Streifen auffallen).
 *
 * Kodierung RGBA8, Zeile fuer Zeile (y aussen):
 *  * R, G: Steigung dh/dx, dh/dy, so skaliert, dass der groesste Betrag 1 ist
 *    (`s = (rg·255 − 127,5) / 127,5`); der Shader gewichtet je Lage.
 *  * B: Hoehe 0..1 (fuer kuenftige Schaumkronen; heute ungenutzt).
 *  * A: 255.
 *
 * Mit Mipmaps (`GEN_MIPMAPPABLE`) mittelt die Karte in der Ferne von selbst zu einer
 * glatten Flaeche — das ist der wichtigste Schutz gegen Flimmern.
 */
object WaterWaves {
    /** Kantenlaenge in Texeln. 128² RGBA8 = 64 KB (+ Mipmaps ≈ 85 KB). */
    const val SIZE = 128
    const val WAVE_COUNT = 56
    const val K_MIN = 2
    const val K_MAX = 20
    const val SEED = 0x7A7E5L

    /** Eine Kosinuswelle: Wellenvektor (ganzzahlig, Perioden je Kachel), Amplitude, Phase. */
    data class Wave(val kx: Int, val ky: Int, val amp: Double, val phase: Double)

    /** Die Wellen der Karte, reproduzierbar aus [SEED]. */
    fun waves(): List<Wave> {
        val rnd = SplitMix(SEED)
        val seen = HashSet<Long>()
        val out = ArrayList<Wave>(WAVE_COUNT)
        var guard = 0
        while (out.size < WAVE_COUNT && guard++ < 100_000) {
            val kx = rnd.nextInt(2 * K_MAX + 1) - K_MAX
            val ky = rnd.nextInt(2 * K_MAX + 1) - K_MAX
            val k = sqrt((kx * kx + ky * ky).toDouble())
            if (k < K_MIN || k > K_MAX) continue
            // (kx, ky) und (−kx, −ky) sind dieselbe Welle
            val key = if (kx > 0 || (kx == 0 && ky > 0)) kx * 1000L + ky else -kx * 1000L - ky
            if (!seen.add(key)) continue
            val c = kx / k
            val dir = 0.35 + 0.65 * c * c
            val amp = dir * Math.pow(k, -1.5) * (0.7 + 0.6 * rnd.nextDouble())
            out += Wave(kx, ky, amp, rnd.nextDouble() * 2 * PI)
        }
        return out
    }

    /** Die Karte als RGBA8 (siehe Klassenbeschreibung), [size]² Texel. */
    fun normalTexture(size: Int = SIZE): ByteArray {
        val ws = waves()
        val n = size * size
        val sx = DoubleArray(n)
        val sy = DoubleArray(n)
        val h = DoubleArray(n)
        // Tabellen je Welle und Achse: cos/sin(2π k x / size) getrennt fuer x und y
        // (cos(a+b) = cos a cos b − sin a sin b), damit je Texel nur Multiplikationen anfallen.
        val cx = DoubleArray(size)
        val snx = DoubleArray(size)
        val cy = DoubleArray(size)
        val sny = DoubleArray(size)
        for (w in ws) {
            for (i in 0 until size) {
                val ax = 2 * PI * w.kx * i / size + w.phase
                cx[i] = cos(ax)
                snx[i] = sin(ax)
                val ay = 2 * PI * w.ky * i / size
                cy[i] = cos(ay)
                sny[i] = sin(ay)
            }
            // h = a·cos(θ), dh/dx = −a·2πkx·sin(θ) (Kachel = Einheit), θ = ax + ay
            val gx = -w.amp * 2 * PI * w.kx
            val gy = -w.amp * 2 * PI * w.ky
            for (y in 0 until size) {
                val row = y * size
                val c2 = cy[y]
                val s2 = sny[y]
                for (x in 0 until size) {
                    val c = cx[x] * c2 - snx[x] * s2
                    val s = snx[x] * c2 + cx[x] * s2
                    h[row + x] += w.amp * c
                    sx[row + x] += gx * s
                    sy[row + x] += gy * s
                }
            }
        }
        var smax = 1e-12
        var hlo = Double.MAX_VALUE
        var hhi = -Double.MAX_VALUE
        for (i in 0 until n) {
            smax = max(smax, max(abs(sx[i]), abs(sy[i])))
            if (h[i] < hlo) hlo = h[i]
            if (h[i] > hhi) hhi = h[i]
        }
        val hspan = (hhi - hlo).takeIf { it > 1e-12 } ?: 1.0
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            out[i * 4] = enc(sx[i] / smax)
            out[i * 4 + 1] = enc(sy[i] / smax)
            out[i * 4 + 2] = ((h[i] - hlo) / hspan * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
            out[i * 4 + 3] = 255.toByte()
        }
        return out
    }

    /** −1..1 → 0..255 (127,5 = 0; der Shader rechnet `rg·2 − 1`). */
    private fun enc(v: Double): Byte = ((v.coerceIn(-1.0, 1.0) * 0.5 + 0.5) * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()

    /** SplitMix64 — klein, schnell, auf jeder JVM gleich (anders als `java.util.Random` nicht vertraglich, aber hier fest). */
    private class SplitMix(private var s: Long) {
        fun nextLong(): Long {
            s += -0x61c8864680b583ebL
            var z = s
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

        fun nextInt(bound: Int): Int = ((nextLong() ushr 33) % bound).toInt()
    }
}
