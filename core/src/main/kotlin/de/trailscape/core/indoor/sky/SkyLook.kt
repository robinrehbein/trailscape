package de.trailscape.core.indoor.sky

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/** Eine lineare RGB-Farbe (nicht sRGB). */
data class SkyRgb(val r: Double, val g: Double, val b: Double) {
    operator fun times(s: Double) = SkyRgb(r * s, g * s, b * s)
    operator fun plus(o: SkyRgb) = SkyRgb(r + o.r, g + o.g, b + o.b)
    fun lerp(o: SkyRgb, t: Double) = SkyRgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
    val luminance: Double get() = 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * Wie der **sichtbare** Himmel bei einer Sonnenhoehe aussieht.
 *
 * Alle Farben sind szenenlinear und schon **belichtet** — so hell, wie sie in
 * Filaments Farbweg gehen (bei der Skybox nach `iblLuminance`, beim
 * Himmelsmaterial direkt). Angegeben werden sie als Zielfarben **auf dem
 * Bildschirm** (sRGB); [SkyLookModel] rechnet sie ueber [ToneGrade.invert]
 * zurueck. So bleibt der Himmel richtig, wenn Abbildung oder Farbgebung sich
 * aendern (frueher gegen `ACES_LEGACY` ausgesucht).
 *
 * @property zenith Farbe oben.
 * @property horizon Farbe am Horizont — zugleich die Dunstfarbe.
 * @property cloudLit sonnenbeschienene Wolkenraender.
 * @property cloudShade Wolkenunterseite.
 * @property cloudCover Bedeckung 0..1 (Anteil des Himmels mit Wolken, grob).
 */
data class SkyLook(
    val zenith: SkyRgb,
    val horizon: SkyRgb,
    val cloudLit: SkyRgb,
    val cloudShade: SkyRgb,
    val cloudCover: Double,
)

/** Farben des sichtbaren Himmels — reine Rechnung, getestet. */
object SkyLookModel {
    /** Bezugsbelichtung (Sonnige-16-Regel, Blende 16, 1/125 s, ISO 100): `1 / (1,2 · 2^15)`. Die Darstellung belichtet je Tageszeit, siehe [DaylightModel]. */
    val EXPOSURE: Double = exposureForEv100(Math.log(16.0 * 16.0 * 125.0) / Math.log(2.0))

    /** Filaments Belichtung zu einem EV100: `1 / (1,2 · 2^EV100)`. */
    fun exposureForEv100(ev100: Double): Double = 1.0 / (1.2 * 2.0.pow(ev100))

    /** Hoehe, bis zu der der helle Horizontstreifen weitgehend ins Blau uebergegangen ist (sin der Hoehe). */
    const val HORIZON_BAND = 0.16

    // Stuetzstellen bei 8°, 20° und 45° Sonnenhoehe; Zielfarben auf dem Bildschirm (sRGB).
    // Abends: tiefes Blau oben, pfirsichwarmer Horizont; mittags: kraeftiges Blau ueber hellem Dunst.
    private val ALT = doubleArrayOf(8.0, 20.0, 45.0)
    private val ZENITH = arrayOf(intArrayOf(74, 108, 182), intArrayOf(62, 118, 206), intArrayOf(54, 120, 214))
    private val HORIZON = arrayOf(intArrayOf(242, 192, 150), intArrayOf(224, 218, 210), intArrayOf(194, 214, 236))
    private val CLOUD_LIT = arrayOf(intArrayOf(255, 222, 186), intArrayOf(250, 246, 238), intArrayOf(250, 251, 252))
    private val CLOUD_SHADE = arrayOf(intArrayOf(132, 116, 136), intArrayOf(150, 154, 170), intArrayOf(172, 182, 200))

    /**
     * Der Himmel bei der wirksamen Sonnenhoehe [sunAltitudeDeg] (die
     * Darstellung hebt sie auf mindestens 8°), Sonnenfarbe [sunColor]
     * (hellster Kanal 1), Dunst [haze] 0..1 und Farbweg [grade].
     */
    fun look(sunAltitudeDeg: Double, sunColor: SkyRgb, haze: Double, grade: ToneGradeLook = ToneGradeLook()): SkyLook {
        val h = haze.coerceIn(0.0, 1.0)
        val inv = { c: IntArray -> ToneGrade.invert(c, grade) }
        val zenithClear = interpolate(ZENITH.map(inv), sunAltitudeDeg)
        val horizonClear = interpolate(HORIZON.map(inv), sunAltitudeDeg)
        // Dunst: Horizont zum hellen Grau gleicher Helligkeit, Zenit etwas zum Horizont hin.
        val grey = horizonClear.luminance
        val horizon = horizonClear.lerp(SkyRgb(grey, grey, grey * 1.04), 0.45 * h)
        // Zenit zu einem hellen Graublau hin (nicht zum Horizont: abends wuerde er lila).
        val zl = zenithClear.luminance
        val zenith = zenithClear.lerp(SkyRgb(zl * 1.6, zl * 1.75, zl * 2.1), 0.3 * h)
        // Wolkenraender in der Sonnenfarbe: Zielfarbe mit der Sonnenfarbe getoent.
        val lit = interpolate(CLOUD_LIT.map(inv), sunAltitudeDeg)
        val tint = SkyRgb(sunColor.r, 0.5 + 0.5 * sunColor.g, 0.4 + 0.6 * sunColor.b)
        return SkyLook(
            zenith = zenith,
            horizon = horizon,
            cloudLit = SkyRgb(lit.r * tint.r, lit.g * tint.g, lit.b * tint.b),
            cloudShade = interpolate(CLOUD_SHADE.map(inv), sunAltitudeDeg).lerp(horizon * 0.45, 0.3 * h),
            cloudCover = 0.30 + 0.15 * h,
        )
    }

    private fun interpolate(values: List<SkyRgb>, altDeg: Double): SkyRgb {
        if (altDeg <= ALT[0]) return values[0]
        for (i in 1 until ALT.size) {
            if (altDeg <= ALT[i]) {
                val t = smooth((altDeg - ALT[i - 1]) / (ALT[i] - ALT[i - 1]))
                return values[i - 1].lerp(values[i], t)
            }
        }
        return values.last()
    }

    /** Verlauf ohne Wolken bei der Hoehe `y = sin(Hoehe)` (0 = Horizont, 1 = Zenit; Betrag). */
    fun gradient(look: SkyLook, y: Double): SkyRgb {
        val t = 1.0 - exp(-max(0.0, y) / HORIZON_BAND)
        val c = look.horizon.lerp(look.zenith, t)
        // Im Uebergang entsaettigen: zwischen warmem Horizont und blauem Zenit liegt ein
        // heller, blasser Streifen, kein Lila (lineares Mischen von Orange und Blau).
        val l = c.luminance
        return c.lerp(SkyRgb(l, l, l), MID_DESATURATION * 4.0 * t * (1.0 - t))
    }

    /** Entsaettigung in der Mitte des Verlaufs (gleich in sky.mat und water.mat). */
    const val MID_DESATURATION = 0.55

    /**
     * Himmel in einer Richtung mit `|y| = `[absY] und der Wolkendichte
     * [cloud] (0..1, aus [SkyClouds]).
     */
    fun color(look: SkyLook, absY: Double, cloud: Double): SkyRgb {
        val sky = gradient(look, absY)
        if (cloud <= 0.0) return sky
        // Dicke Wolkenkerne sind unten grau, duenne Raender leuchten.
        val thick = smooth((cloud - 0.25) / 0.75)
        val cloudColor = look.cloudLit.lerp(look.cloudShade, 0.7 * thick)
        val alpha = smooth(cloud / 0.6)
        return sky.lerp(cloudColor, alpha)
    }

    internal fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}
