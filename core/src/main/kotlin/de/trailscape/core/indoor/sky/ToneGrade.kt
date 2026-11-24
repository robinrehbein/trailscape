package de.trailscape.core.indoor.sky

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Tonwertabbildung und Farbgebung der Darstellung (Filaments `ColorGrading`).
 *
 * @property toneMapper Name der Filament-Abbildung; die Darstellung nimmt
 *   `PBR_NEUTRAL` (Khronos): Farben bis rund 0,76 bleiben unveraendert, erst
 *   darueber wird weich zu Weiss verdichtet. Filaments Standard `ACES_LEGACY`
 *   bleichte helle, wenig gesaettigte Flaechen (Felder, Wege, Himmel) fast weiss
 *   und machte das Bild trueb (Befund des Gruenders, docs/indoor/render.md).
 * @property contrast, vibrance, saturation wie `ColorGrading.Builder`.
 */
data class ToneGradeLook(
    val toneMapper: String = PBR_NEUTRAL,
    val contrast: Double = 1.0,
    val vibrance: Double = 1.0,
    val saturation: Double = 1.0,
) {
    companion object {
        const val PBR_NEUTRAL = "PBR_NEUTRAL"
    }
}

/**
 * Nachbildung von Filaments Farbweg (ColorGrading.cpp / ToneMapper.cpp, v1.75.1)
 * fuer `PBR_NEUTRAL` mit Kontrast, Lebendigkeit und Saettigung: sRGB linear →
 * Rec.2020 → Kontrast (LogC) → Lebendigkeit → Saettigung → Abbildung → sRGB.
 * Dient dazu, Himmelsfarben als **Zielwerte auf dem Bildschirm** anzugeben
 * ([invert]) — der Himmel sieht dann genau so aus, egal wie die Abbildung
 * eingestellt ist.
 */
object ToneGrade {
    private val SRGB_TO_REC2020 = doubleArrayOf(
        0.6274040, 0.3292820, 0.0433136,
        0.0690970, 0.9195400, 0.0113612,
        0.0163916, 0.0880132, 0.8955950,
    )
    private val REC2020_TO_SRGB = doubleArrayOf(
        1.6604910, -0.5876411, -0.0728499,
        -0.1245505, 1.1328999, -0.0083494,
        -0.0181508, -0.1005789, 1.1187297,
    )
    private val LUM_REC2020 = doubleArrayOf(0.2627002, 0.6779981, 0.0593017)
    private const val MIDDLE_GRAY_ACESCCT = 0.4135884

    private fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun toLogC(x: Double) = 0.244161 * log10(5.555556 * x + 0.047996) + 0.386036
    private fun fromLogC(x: Double) = (10.0.pow((x - 0.386036) / 0.244161) - 0.047996) / 5.555556

    /** PBR Neutral (Khronos), wie `PBRNeutralToneMapper::operator()`. */
    fun pbrNeutral(c: DoubleArray): DoubleArray {
        val start = 0.8 - 0.04
        val desat = 0.15
        val x = min(c[0], min(c[1], c[2]))
        val offset = if (x < 0.08) x - 6.25 * x * x else 0.04
        val v = DoubleArray(3) { c[it] - offset }
        val peak = max(v[0], max(v[1], v[2]))
        if (peak < start) return v
        val d = 1.0 - start
        val newPeak = 1.0 - d * d / (peak + d - start)
        for (i in 0..2) v[i] *= newPeak / peak
        val g = 1.0 - 1.0 / (desat * (peak - newPeak) + 1.0)
        return DoubleArray(3) { v[it] + (newPeak - v[it]) * g }
    }

    /** Lineares sRGB (belichtet) → lineares sRGB auf dem Bildschirm, 0..1 (vor der sRGB-Kennlinie). */
    fun display(c: SkyRgb, look: ToneGradeLook): SkyRgb {
        var v = mul(SRGB_TO_REC2020, doubleArrayOf(c.r, c.g, c.b)).map { max(it, 0.0) }.toDoubleArray()
        if (look.contrast != 1.0) {
            val off = MIDDLE_GRAY_ACESCCT * (1.0 - look.contrast)
            v = DoubleArray(3) { fromLogC(toLogC(v[it]) * look.contrast + off) }
        }
        if (look.vibrance != 1.0) {
            val r = v[0] - max(v[1], v[2])
            val s = (look.vibrance - 1.0) / (1.0 + exp(-r * 3.0)) + 1.0
            val l = DoubleArray(3) { (1.0 - s) * LUM_REC2020[it] }
            v = DoubleArray(3) { i -> dot(v, DoubleArray(3) { j -> l[j] + if (i == j) s else 0.0 }) }
        }
        if (look.saturation != 1.0) {
            val y = dot(v, LUM_REC2020)
            v = DoubleArray(3) { y + look.saturation * (v[it] - y) }
        }
        v = v.map { max(it, 0.0) }.toDoubleArray()
        val t = pbrNeutral(v)
        val o = mul(REC2020_TO_SRGB, t)
        return SkyRgb(o[0].coerceIn(0.0, 1.0), o[1].coerceIn(0.0, 1.0), o[2].coerceIn(0.0, 1.0))
    }

    /** Wie [display], als sRGB-Bytes. */
    fun displaySrgb(c: SkyRgb, look: ToneGradeLook): IntArray {
        val d = display(c, look)
        return intArrayOf(oetf(d.r), oetf(d.g), oetf(d.b))
    }

    /**
     * Umkehrung von [display]: welche belichtete Farbe ergibt auf dem Bildschirm die
     * sRGB-Farbe [srgb] (Bytes)? Werte ueber rund 240 sind nur mit sehr hellen
     * Eingaben erreichbar (die Abbildung naehert sich 1).
     */
    fun invert(srgb: IntArray, look: ToneGradeLook): SkyRgb {
        val target = DoubleArray(3) { eotf(srgb[it].coerceIn(0, 250)) }
        val x = target.copyOf()
        // Kanalweise Bisektion (logarithmisch), mehrfach im Wechsel: die Kanaele haengen ueber
        // Lebendigkeit und Rec.2020 voneinander ab, jeder fuer sich ist aber monoton. Ein
        // multiplikatives Verfahren blieb bei fast schwarzen Kanaelen (Rot im tiefen Blau)
        // haengen — der Zenit wurde lavendelfarben.
        repeat(12) {
            for (i in 0..2) {
                var lo = Math.log(1e-5)
                var hi = Math.log(60.0)
                repeat(40) {
                    val mid = (lo + hi) / 2
                    x[i] = exp(mid)
                    val d = display(SkyRgb(x[0], x[1], x[2]), look)
                    val got = doubleArrayOf(d.r, d.g, d.b)[i]
                    if (got < target[i]) lo = mid else hi = mid
                }
                x[i] = exp((lo + hi) / 2)
            }
        }
        return SkyRgb(x[0], x[1], x[2])
    }

    fun eotf(byte: Int): Double {
        val s = byte / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun oetf(linear: Double): Int {
        val c = linear.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }
}
