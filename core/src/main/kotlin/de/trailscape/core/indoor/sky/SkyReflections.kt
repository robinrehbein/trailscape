package de.trailscape.core.indoor.sky

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

/**
 * # Spiegelungs-Umgebungslicht aus dem Himmelsmodell
 *
 * Filament beleuchtet glaenzende Anteile (Straße, Daecher, Laub, Fahrer) mit einer
 * vorgefilterten Cubemap (`IndirectLight.Builder.reflections`). Ohne sie bindet
 * Filament 1.75.1 eine schwarze 1×1-Cubemap (`Engine.cpp`, `mDefaultIblTexture`):
 * Dann gibt es **kein** spekulares Umgebungslicht — kein heller Himmelsglanz auf
 * Asphalt im Gegenlicht, keine Fresnel-Aufhellung an flachen Winkeln, Metall
 * schwarz. Diese Datei rechnet die Cubemap aus demselben Himmel, den man sieht
 * ([SkyLook], Wolken aus [SkyClouds]); unter dem Horizont liegt der Boden des
 * Umgebungslichts (`iblGround`), zum Horizont hin im Dunst verblendet.
 *
 * **Einheiten:** wie die SH-Koeffizienten des Umgebungslichts relativ zu
 * `ambientLux` (`IndirectLight.intensity` multipliziert beides). Der sichtbare
 * Himmel ist szenenlinear nach der Belichtung; geteilt durch `skyScale`
 * (= `ambientLux × Belichtung`) liegt er im selben Mass (geprueft: Mittag
 * Horizont 0,74 / 0,86 / 1,02 gegen `iblHorizon` 0,73 / 0,77 / 0,83).
 *
 * **Vorfilterung** wie Filaments `cmgen`/`IBLPrefilterContext`: Mip-Stufe `L`
 * von `N` Stufen gehoert zur wahrgenommenen Rauheit `p` mit
 * `L = (N − 1) · p · (2 − p)` (Shader `perceptualRoughnessToLod`,
 * `surface_light_indirect.fs`, v1.75.1), also `p = 1 − √(1 − L/(N − 1))`.
 * Gefiltert wird mit der GGX-Verteilung unter der Annahme `n = v = r`
 * (Gewicht `D(n·h) · (n·l) · dΩ`). Stufen mit einer Keule schmaler als ein
 * Texel der Stufe werden nur gemittelt (Box) — dort sieht man keinen
 * Unterschied, und das haelt die Rechnung unter wenigen Millisekunden.
 *
 * Kein Android, keine Filament-Typen: Die Darstellung laedt die Stufen als
 * RGB-Halbfloats in eine `R11F_G11F_B10F`-Cubemap; das Werkzeug schreibt sie
 * fuer den Referenzrenderer.
 */
object SkyReflections {
    /** Kantenlaenge der Stufe 0 auf dem Geraet: 6 × 64² Texel, mit Mips ≈ 130 kB (R11G11B10F). */
    const val BASE_SIZE = 64

    /**
     * Staerke der Spiegelung gegenueber dem sichtbaren Himmel. Etwas unter 1, weil
     * Filament das Umgebungslicht nicht durch Geometrie verdeckt (unter dem
     * Kronendach saehe der Waldweg sonst den offenen Himmel glaenzen).
     */
    const val DEFAULT_STRENGTH = 0.85

    /** Unterhalb dieser Hoehe (sin) ist nur noch Boden zu sehen. */
    private const val GROUND_BLEND = 0.10

    /** Anzahl der Mip-Stufen fuer Kantenlaenge [size] (bis 1×1). */
    fun levelCount(size: Int): Int {
        var n = 1
        var s = size
        while (s > 1) {
            s = s shr 1
            n++
        }
        return n
    }

    /** Wahrgenommene Rauheit der Stufe [level] von [levels] Stufen (Umkehrung von Filaments Abbildung). */
    fun perceptualRoughness(level: Int, levels: Int): Double {
        if (levels <= 1) return 0.0
        val t = (level.toDouble() / (levels - 1)).coerceIn(0.0, 1.0)
        return 1.0 - sqrt(1.0 - t)
    }

    /**
     * Strahldichte in Richtung `(x, y, z)` (normiert, glTF: y oben), relativ zu
     * `ambientLux`: Himmel aus [look] samt Wolken ([cloudRaw] = rohe Dichte aus
     * [SkyClouds.rawDensity]) geteilt durch [skyScale]; darunter [ground].
     */
    fun radiance(look: SkyLook, skyScale: Double, ground: SkyRgb, y: Double, cloudRaw: Double): SkyRgb {
        val inv = 1.0 / skyScale
        if (y >= 0.0) {
            return SkyLookModel.color(look, y, SkyClouds.density(cloudRaw, look.cloudCover)) * inv
        }
        // Unter dem Horizont: ferner Boden im Dunst (Horizontfarbe), weiter unten der Boden.
        val t = SkyLookModel.smooth(-y / GROUND_BLEND)
        return (look.horizon * inv).lerp(ground, t)
    }

    /**
     * Die vorgefilterte Mip-Kette: je Stufe ein Feld RGB-Float (6 Flaechen +X, −X,
     * +Y, −Y, +Z, −Z, Zeile fuer Zeile, wie [SkyClouds.cubeDirection]), Stufe 0
     * mit Kantenlaenge [size], dann halbiert bis 1×1.
     */
    fun prefiltered(
        look: SkyLook,
        skyScale: Double,
        ground: SkyRgb,
        size: Int = BASE_SIZE,
        strength: Double = DEFAULT_STRENGTH,
    ): List<FloatArray> {
        require(size >= 1 && (size and (size - 1)) == 0) { "Kantenlaenge muss eine Zweierpotenz sein: $size" }
        val levels = levelCount(size)
        val raw = SkyClouds.rawCube(size)
        val base = FloatArray(6 * size * size * 3)
        var o = 0
        var i = 0
        for (f in 0 until 6) for (row in 0 until size) {
            val tc = 2.0 * (row + 0.5) / size - 1.0
            for (col in 0 until size) {
                val sc = 2.0 * (col + 0.5) / size - 1.0
                val d = SkyClouds.cubeDirection(f, sc, tc)
                val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                val c = radiance(look, skyScale, ground, d[1] / len, raw[i++].toDouble()) * strength
                base[o++] = c.r.toFloat()
                base[o++] = c.g.toFloat()
                base[o++] = c.b.toFloat()
            }
        }
        val box = ArrayList<FloatArray>(levels)
        box += base
        var s = size
        while (s > 1) {
            box += downsample(box.last(), s)
            s = s shr 1
        }
        // Quelle der Faltung: hoechstens 16² je Flaeche (6 × 256 Texel), genug fuer breite Keulen.
        val srcLevel = (0 until levels).first { (size shr it) <= 16 }
        val src = Texels(box[srcLevel], size shr srcLevel)
        val out = ArrayList<FloatArray>(levels)
        for (level in 0 until levels) {
            val ls = size shr level
            val p = perceptualRoughness(level, levels)
            val alpha = p * p
            // Grob die halbe Breite der gespiegelten Keule (doppelte Streuung der Halbvektoren)
            // gegen die halbe Texelbreite der Stufe: schmaler → Mitteln genuegt.
            val lobe = Math.atan(alpha) * 2.0
            val texel = (PI / 2.0) / ls
            out += if (level == 0 || lobe < texel * 0.5 || ls > 16) box[level] else convolve(src, ls, alpha)
        }
        return out
    }

    /** Wie [prefiltered], als RGB-Halbfloats (IEEE 754 binary16) fuer `Texture.Type.HALF`. */
    fun prefilteredHalf(
        look: SkyLook,
        skyScale: Double,
        ground: SkyRgb,
        size: Int = BASE_SIZE,
        strength: Double = DEFAULT_STRENGTH,
    ): List<ShortArray> = prefiltered(look, skyScale, ground, size, strength).map { lv ->
        ShortArray(lv.size) { SkyClouds.toHalf(lv[it].toDouble()) }
    }

    /** Bequem fuer die Darstellung: alles aus einem [DaylightLighting]. */
    fun prefilteredHalf(l: DaylightLighting, size: Int = BASE_SIZE, strength: Double = DEFAULT_STRENGTH): List<ShortArray> =
        prefilteredHalf(l.look, l.skyScale, groundFor(l), size, strength)

    /**
     * Boden fuer die Spiegelung: `iblGround` im Verhaeltnis sichtbarer Horizont zu
     * `iblHorizon`. Das Umgebungslicht ist bei tiefer Sonne bewusst heller als der
     * sichtbare Himmel (abends Faktor ~0,5); ohne den Abgleich glaenzte der Boden
     * in der Spiegelung heller als der Himmel darueber.
     */
    fun groundFor(l: DaylightLighting): SkyRgb {
        val visible = (l.look.horizon * (1.0 / l.skyScale)).luminance
        val ibl = l.iblHorizon.luminance
        val k = if (ibl > 0.0) (visible / ibl).coerceIn(0.3, 1.2) else 1.0
        return l.iblGround * k
    }

    private fun downsample(src: FloatArray, size: Int): FloatArray {
        val h = size / 2
        val out = FloatArray(6 * h * h * 3)
        for (f in 0 until 6) for (r in 0 until h) for (c in 0 until h) {
            for (k in 0 until 3) {
                fun at(rr: Int, cc: Int) = src[((f * size + rr) * size + cc) * 3 + k]
                out[((f * h + r) * h + c) * 3 + k] =
                    (at(2 * r, 2 * c) + at(2 * r, 2 * c + 1) + at(2 * r + 1, 2 * c) + at(2 * r + 1, 2 * c + 1)) * 0.25f
            }
        }
        return out
    }

    /** Richtungen, Raumwinkel und Farben der Texel einer Stufe. */
    private class Texels(val rgb: FloatArray, size: Int) {
        val n = 6 * size * size
        val dir = DoubleArray(n * 3)
        val solid = DoubleArray(n)

        init {
            var i = 0
            for (f in 0 until 6) for (row in 0 until size) {
                val tc = 2.0 * (row + 0.5) / size - 1.0
                for (col in 0 until size) {
                    val sc = 2.0 * (col + 0.5) / size - 1.0
                    val d = SkyClouds.cubeDirection(f, sc, tc)
                    val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                    dir[i * 3] = d[0] / len
                    dir[i * 3 + 1] = d[1] / len
                    dir[i * 3 + 2] = d[2] / len
                    val q = 1.0 + sc * sc + tc * tc
                    solid[i] = (2.0 / size) * (2.0 / size) / (q * sqrt(q))
                    i++
                }
            }
        }
    }

    private fun convolve(src: Texels, size: Int, alpha: Double): FloatArray {
        val out = FloatArray(6 * size * size * 3)
        val a2 = max(alpha * alpha, 1e-6)
        var o = 0
        for (f in 0 until 6) for (row in 0 until size) {
            val tc = 2.0 * (row + 0.5) / size - 1.0
            for (col in 0 until size) {
                val sc = 2.0 * (col + 0.5) / size - 1.0
                val d = SkyClouds.cubeDirection(f, sc, tc)
                val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                val nx = d[0] / len
                val ny = d[1] / len
                val nz = d[2] / len
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var w = 0.0
                for (j in 0 until src.n) {
                    val lx = src.dir[j * 3]
                    val ly = src.dir[j * 3 + 1]
                    val lz = src.dir[j * 3 + 2]
                    val nl = nx * lx + ny * ly + nz * lz
                    if (nl <= 0.0) continue
                    // n = v: h = normalize(n + l), n·h = √((1 + n·l) / 2)
                    val nh2 = (1.0 + nl) * 0.5
                    val dd = nh2 * (a2 - 1.0) + 1.0
                    val weight = a2 / (PI * dd * dd) * nl * src.solid[j]
                    r += src.rgb[j * 3] * weight
                    g += src.rgb[j * 3 + 1] * weight
                    b += src.rgb[j * 3 + 2] * weight
                    w += weight
                }
                val inv = if (w > 0.0) 1.0 / w else 0.0
                out[o++] = (r * inv).toFloat()
                out[o++] = (g * inv).toFloat()
                out[o++] = (b * inv).toFloat()
            }
        }
        return out
    }

    /** Mittlere Helligkeit (Luminanz) einer Stufe, fuer Tests und Diagnose. */
    fun meanLuminance(level: FloatArray): Double {
        var s = 0.0
        var i = 0
        while (i < level.size) {
            s += 0.2126 * level[i] + 0.7152 * level[i + 1] + 0.0722 * level[i + 2]
            i += 3
        }
        return s / (level.size / 3)
    }

    /** Wert eines Texels (Flaeche [face], Zeile, Spalte) einer Stufe der Kantenlaenge [size]. */
    fun texel(level: FloatArray, size: Int, face: Int, row: Int, col: Int): SkyRgb {
        val i = ((face * size + row) * size + col) * 3
        return SkyRgb(level[i].toDouble(), level[i + 1].toDouble(), level[i + 2].toDouble())
    }
}
