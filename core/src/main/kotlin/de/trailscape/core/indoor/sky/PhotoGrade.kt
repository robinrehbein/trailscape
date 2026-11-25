package de.trailscape.core.indoor.sky

/**
 * # Weissabgleich je Tageszeit (Grafikstufen Hoch/Ultra)
 *
 * Fotos bei hoher Sonne wirken mit einer Spur Waerme natuerlicher: Das blaue
 * Himmelslicht faerbt Schatten und graue Flaechen sonst kalt (im Referenzrenderer
 * deutlich auf Asphalt). Bei tiefer Sonne ist das Licht schon warm; dort
 * bleibt der Abgleich fast neutral, damit der Abend nicht orange kippt. Ein
 * Hauch Magenta nimmt den Gruenstich von Wiese und Laub.
 *
 * Werte fuer Filaments `ColorGrading.Builder.whiteBalance(temperature, tint)`
 * (je −1..1; +Temperatur = waermer, +Tint = magenta).
 */
object PhotoGrade {
    /** Waerme bei hoher Sonne. */
    const val NOON_TEMPERATURE = 0.06

    /** Waerme bei tiefer Sonne (Licht ist schon warm). */
    const val LOW_SUN_TEMPERATURE = -0.01

    const val TINT = 0.02

    /** `(temperature, tint)` fuer die Sonnenhoehe [sunAltitudeDeg]. */
    fun whiteBalance(sunAltitudeDeg: Double): Pair<Double, Double> {
        val high = SkyLookModel.smooth((sunAltitudeDeg - 10.0) / 35.0)
        return (LOW_SUN_TEMPERATURE + (NOON_TEMPERATURE - LOW_SUN_TEMPERATURE) * high) to TINT
    }

    fun whiteBalance(l: DaylightLighting): Pair<Double, Double> = whiteBalance(l.sunAltitudeDeg)
}
