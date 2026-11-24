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
 * Alle Farben sind szenenlinear, **vor** der Tonwertabbildung — also so hell,
 * wie sie nach der Belichtung der Kamera in Filaments ACES-Abbildung gehen.
 * Die Zielwerte sind gegen eine Nachbildung von Filaments `ACES_LEGACY`
 * (Standard der `View`, mit Helligkeit 1/0,6) ausgesucht: Die vorige, blasse
 * Cubemap wurde dort zu einem fast einheitlichen Grau (Bilder vom Geraet,
 * siehe docs/indoor/render.md, „Himmel").
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
    /** Belichtung der Kamera (Sonnige-16-Regel, Blende 16, 1/125 s, ISO 100): `1 / (1,2 · 2^EV100)`. */
    val EXPOSURE: Double = run {
        val ev100 = Math.log(16.0 * 16.0 / (1.0 / 125.0) * 100.0 / 100.0) / Math.log(2.0)
        1.0 / (1.2 * 2.0.pow(ev100))
    }

    /** Hoehe, bis zu der der helle Horizontstreifen weitgehend ins Blau uebergegangen ist (sin der Hoehe). */
    const val HORIZON_BAND = 0.16

    // Stuetzstellen bei 8°, 20° und 45° Sonnenhoehe. Zielfarben nach der Abbildung (sRGB):
    // Zenit 78/118/190, 62/118/200, 52/112/204; Horizont 222/205/189, 212/209/208, 187/208/234.
    private val ALT = doubleArrayOf(8.0, 20.0, 45.0)
    private val ZENITH = arrayOf(SkyRgb(0.078, 0.156, 0.438), SkyRgb(0.055, 0.156, 0.516), SkyRgb(0.044, 0.143, 0.553))
    private val HORIZON = arrayOf(SkyRgb(0.85, 0.55, 0.40), SkyRgb(0.62, 0.62, 0.66), SkyRgb(0.33, 0.60, 1.40))
    private val CLOUD_SHADE = arrayOf(SkyRgb(0.24, 0.21, 0.25), SkyRgb(0.27, 0.29, 0.36), SkyRgb(0.30, 0.34, 0.43))

    /**
     * Der Himmel bei der wirksamen Sonnenhoehe [sunAltitudeDeg] (die
     * Darstellung hebt sie auf mindestens 8°), Sonnenfarbe [sunColor]
     * (hellster Kanal 1) und Dunst [haze] 0..1.
     */
    fun look(sunAltitudeDeg: Double, sunColor: SkyRgb, haze: Double): SkyLook {
        val h = haze.coerceIn(0.0, 1.0)
        val zenithClear = interpolate(ZENITH, sunAltitudeDeg)
        val horizonClear = interpolate(HORIZON, sunAltitudeDeg)
        // Dunst: Horizont zum hellen Grau gleicher Helligkeit, Zenit etwas zum Horizont hin.
        val grey = horizonClear.luminance
        val horizon = horizonClear.lerp(SkyRgb(grey, grey, grey * 1.04), 0.45 * h)
        // Zenit zu einem hellen Graublau hin (nicht zum Horizont: abends wuerde er lila).
        val zl = zenithClear.luminance
        val zenith = zenithClear.lerp(SkyRgb(zl * 1.6, zl * 1.75, zl * 2.1), 0.3 * h)
        return SkyLook(
            zenith = zenith,
            horizon = horizon,
            cloudLit = sunColor * 1.35 + SkyRgb(0.05, 0.08, 0.15),
            cloudShade = interpolate(CLOUD_SHADE, sunAltitudeDeg).lerp(horizon * 0.45, 0.3 * h),
            cloudCover = 0.30 + 0.15 * h,
        )
    }

    private fun interpolate(values: Array<SkyRgb>, altDeg: Double): SkyRgb {
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
        return look.horizon.lerp(look.zenith, t)
    }

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
