package de.trailscape.core.indoor.sky

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Weiche Wolken und die Himmels-Cubemap
 *
 * Prozedural (Rauschen, keine Bilddatei): Die Wolken liegen auf einer
 * gedachten Ebene ueber dem Betrachter, eine Richtung `d` trifft sie bei
 * etwa `(d.x, d.z) / |d.y|` (zum Horizont hin weniger gestaucht). Deshalb ist das Bild **spiegelgleich zur
 * Waagrechten** (haengt nur von `|y|` ab) und ein Spiegeln der Seitenflaechen
 * durch einen Treiber aendert nur das Wolkenmuster, nie oben und unten.
 * Zum Horizont hin laufen die Wolken aus, damit der Horizont rein bleibt und
 * zum Dunst passt.
 *
 * Das Rauschen haengt nicht von der Tageszeit ab und wird je Kantenlaenge
 * einmal je Prozess gerechnet ([rawCube]); das Einfaerben je Tageszeit ist
 * billig.
 */
object SkyClouds {
    /** Kantenlaenge der Cubemap-Flaechen auf dem Geraet (6 × 256² Texel, R11G11B10F ≈ 1,5 MB). */
    const val CUBE_SIZE = 256

    /** Groesse der Wolkenzellen auf der Ebene (Einheit: Hoehe der Ebene). */
    private const val SCALE = 0.9

    /** Unter dieser Hoehe (sin) gibt es keine Wolken mehr. */
    private const val FADE_START = 0.02
    private const val FADE_FULL = 0.13

    private val cache = HashMap<Int, FloatArray>()

    /**
     * OpenGL-Cubemap: Richtung fuer Flaeche [face] (+X, −X, +Y, −Y, +Z, −Z) und
     * Flaechenkoordinaten `sc`, `tc` in −1..1 (Spezifikation, Abschnitt „Cube
     * Map Texture Selection“). Zeile 0 einer Seitenflaeche ist oben.
     */
    fun cubeDirection(face: Int, sc: Double, tc: Double): DoubleArray = when (face) {
        0 -> doubleArrayOf(1.0, -tc, -sc)
        1 -> doubleArrayOf(-1.0, -tc, sc)
        2 -> doubleArrayOf(sc, 1.0, tc)
        3 -> doubleArrayOf(sc, -1.0, -tc)
        4 -> doubleArrayOf(sc, -tc, 1.0)
        else -> doubleArrayOf(-sc, -tc, -1.0)
    }

    /**
     * Rohe Wolkendichte 0..1 (vor der Bedeckung) in Richtung `(x, y, z)`
     * (muss nicht normiert sein), schon zum Horizont hin ausgeblendet.
     */
    fun rawDensity(x: Double, y: Double, z: Double): Double {
        val len = sqrt(x * x + y * y + z * z)
        if (len <= 0.0) return 0.0
        val ay = abs(y) / len
        val fade = SkyLookModel.smooth((ay - FADE_START) / (FADE_FULL - FADE_START))
        if (fade <= 0.0) return 0.0
        // Wie eine Ebene, zum Horizont aber nur maessig gestaucht: sonst zerfallen die
        // Wolken dort (wo man im Hochformat hinsieht) in duenne Streifen.
        val s = SCALE / ((ay + 0.18) * len)
        val u = x * s
        val v = z * s
        // Leichte Verwirbelung des Gebiets: rundliche, unregelmaessige Haufen statt Gitter.
        val wu = fbm(u * 0.5 + 3.1, v * 0.5 + 7.7, 3) - 0.5
        val wv = fbm(u * 0.5 + 11.3, v * 0.5 + 1.9, 3) - 0.5
        val n = fbm(u + 0.8 * wu, v + 0.8 * wv, 5)
        return n * fade
    }

    /** Dichte nach Bedeckung [cover]: 0 = klar, 1 = dichter Kern. */
    fun density(raw: Double, cover: Double): Double {
        if (raw <= 0.0) return 0.0
        // Abgestimmt auf die Verteilung von [rawDensity] (Median etwa 0,44, 90 % unter 0,67):
        // Bedeckung 0,35 → etwa ein Drittel des Himmels ueber 20° mit Wolken.
        val threshold = 0.44 + (0.5 - cover.coerceIn(0.0, 1.0)) * 0.55
        return ((raw - threshold) / 0.3).coerceIn(0.0, 1.0)
    }

    /**
     * Rohe Dichten aller Texel der sechs Flaechen (+X … −Z, Zeile fuer
     * Zeile), einmal je Kantenlaenge gerechnet. Untere Haelften sind die
     * gespiegelten oberen (siehe Klassenkommentar) und werden nur kopiert.
     */
    fun rawCube(size: Int): FloatArray = synchronized(cache) {
        cache.getOrPut(size) { computeRawCube(size) }
    }

    private fun computeRawCube(size: Int): FloatArray {
        val out = FloatArray(6 * size * size)
        val face = size * size
        fun fill(f: Int, row: Int) {
            val tc = 2.0 * (row + 0.5) / size - 1.0
            for (col in 0 until size) {
                val sc = 2.0 * (col + 0.5) / size - 1.0
                val d = cubeDirection(f, sc, tc)
                out[f * face + row * size + col] = rawDensity(d[0], d[1], d[2]).toFloat()
            }
        }
        // +Y ganz, die Seitenflaechen oben; der Rest gespiegelt.
        for (row in 0 until size) fill(2, row)
        for (f in intArrayOf(0, 1, 4, 5)) {
            for (row in 0 until (size + 1) / 2) fill(f, row)
            for (row in (size + 1) / 2 until size) {
                System.arraycopy(out, f * face + (size - 1 - row) * size, out, f * face + row * size, size)
            }
        }
        for (row in 0 until size) {
            System.arraycopy(out, 2 * face + (size - 1 - row) * size, out, 3 * face + row * size, size)
        }
        return out
    }

    /**
     * Die Himmels-Cubemap als RGB-Halbfloats (je Texel drei `Short` nach IEEE
     * 754 binary16), Flaechen +X, −X, +Y, −Y, +Z, −Z, Zeile fuer Zeile. Die
     * Farben aus [look] werden durch [scale] geteilt — auf dem Geraet durch
     * `IndirectLight.intensity × Belichtung`, weil Filament die Skybox damit
     * multipliziert.
     */
    fun cubemapHalfRgb(look: SkyLook, size: Int, scale: Double): ShortArray {
        val raw = rawCube(size)
        val out = ShortArray(6 * size * size * 3)
        val inv = 1.0 / scale
        var o = 0
        var i = 0
        for (f in 0 until 6) {
            for (row in 0 until size) {
                val tc = 2.0 * (row + 0.5) / size - 1.0
                for (col in 0 until size) {
                    val sc = 2.0 * (col + 0.5) / size - 1.0
                    val d = cubeDirection(f, sc, tc)
                    val ay = abs(d[1]) / sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                    val c = SkyLookModel.color(look, ay, density(raw[i++].toDouble(), look.cloudCover))
                    out[o++] = toHalf(c.r * inv)
                    out[o++] = toHalf(c.g * inv)
                    out[o++] = toHalf(c.b * inv)
                }
            }
        }
        return out
    }

    /**
     * Der Himmel als Rundumbild (Laenge × Breite, `width` × `height`,
     * Zeile 0 = Zenit), RGB-Gleitkomma, szenenlinear — fuer den Betrachter
     * auf dem Entwicklerrechner. Spalte 0 blickt nach −Z (Norden im glTF-Raum).
     */
    fun equirectRgb(look: SkyLook, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height * 3)
        var o = 0
        for (row in 0 until height) {
            val lat = PI / 2 - PI * (row + 0.5) / height
            val y = sin(lat)
            val r = cos(lat)
            for (col in 0 until width) {
                val lon = 2 * PI * (col + 0.5) / width
                val x = r * sin(lon)
                val z = -r * cos(lon)
                val c = SkyLookModel.color(look, abs(y), density(rawDensity(x, y, z), look.cloudCover))
                out[o++] = c.r.toFloat()
                out[o++] = c.g.toFloat()
                out[o++] = c.b.toFloat()
            }
        }
        return out
    }

    /** Kantenlaenge der kachelbaren Wolken-/Wellentextur. */
    const val CLOUD_TEX_SIZE = 256

    /**
     * Kachelbares Wolkenrauschen fuer das Himmelsmaterial (und die Wellen des
     * Wassers): [size]² Werte 0..255 (R8), Zeile fuer Zeile. Wertrauschen mit
     * 5 Lagen, je Lage periodisch auf dem Gitter — die Textur wiederholt sich
     * nahtlos (`REPEAT`). Auf etwa 0..1 gedehnt, damit die Bedeckung im Shader
     * mit einer Schwelle einstellbar ist. Kein Bild aus fremder Quelle.
     */
    fun cloudTexture(size: Int = CLOUD_TEX_SIZE): ByteArray {
        val raw = DoubleArray(size * size)
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (y in 0 until size) for (x in 0 until size) {
            var sum = 0.0
            var amp = 0.5
            var norm = 0.0
            var period = 4
            for (k in 0 until 5) {
                val fx = x.toDouble() / size * period
                val fy = y.toDouble() / size * period
                sum += amp * periodicNoise(fx, fy, period, k)
                norm += amp
                amp *= 0.5
                period *= 2
            }
            val v = sum / norm
            raw[y * size + x] = v
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        return ByteArray(size * size) { ((raw[it] - lo) / span * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte() }
    }

    private fun periodicNoise(x: Double, y: Double, period: Int, seed: Int): Double {
        val ix = floor(x)
        val iy = floor(y)
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
        val uy = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
        val x0 = Math.floorMod(ix.toInt(), period)
        val y0 = Math.floorMod(iy.toInt(), period)
        val x1 = (x0 + 1) % period
        val y1 = (y0 + 1) % period
        val a = hash(x0, y0, seed + 7)
        val b = hash(x1, y0, seed + 7)
        val c = hash(x0, y1, seed + 7)
        val d = hash(x1, y1, seed + 7)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    // -----------------------------------------------------------------------
    // Rauschen
    // -----------------------------------------------------------------------

    /** Summe von [octaves] Lagen Wertrauschen, Ergebnis etwa 0..1. */
    fun fbm(x: Double, y: Double, octaves: Int): Double {
        var sum = 0.0
        var amp = 0.5
        var norm = 0.0
        var fx = x
        var fy = y
        for (k in 0 until octaves) {
            sum += amp * valueNoise(fx, fy, k)
            norm += amp
            amp *= 0.5
            // Lagen gegeneinander gedreht und verschoben, damit kein Gitter durchscheint.
            val nx = 1.6 * fx - 1.2 * fy + 17.0
            val ny = 1.2 * fx + 1.6 * fy + 31.0
            fx = nx
            fy = ny
        }
        return sum / norm
    }

    private fun valueNoise(x: Double, y: Double, seed: Int): Double {
        val ix = floor(x)
        val iy = floor(y)
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
        val uy = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
        val xi = ix.toInt()
        val yi = iy.toInt()
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    private fun hash(x: Int, y: Int, seed: Int): Double {
        var h = x * 374_761_393 + y * 668_265_263 + seed * 1_274_126_177
        h = (h xor (h ushr 13)) * 1_103_515_245
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16_777_216.0
    }

    /** Gleitkomma → IEEE 754 binary16 (gerundet, ohne Subnormale; negativ → 0, zu gross → groesster Wert). */
    fun toHalf(v: Double): Short {
        if (!(v > 0.0)) return 0
        if (v >= 65_504.0) return 0x7BFF
        val bits = java.lang.Float.floatToIntBits(v.toFloat())
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        if (exp <= 0) return 0
        var mant = bits and 0x7FFFFF
        var e = exp
        // Auf 10 Bit runden.
        mant += 0x1000
        if (mant and 0x800000 != 0) {
            mant = 0
            e += 1
        }
        if (e >= 31) return 0x7BFF
        return ((e shl 10) or (mant ushr 13)).toShort()
    }
}
