package de.trailscape.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * # Sonnenauf- und -untergang, lokal gerechnet
 *
 * Fuer den Drinnen/Draussen-Vorschlag ([suggestTodayPlace]) braucht die App
 * nur eine Frage beantwortet: **Reicht das Tageslicht fuer die Einheit?** Dafuer
 * genuegen Datum und ein grober Ort — keine Netzquelle, keine Berechtigung.
 *
 * ## Rechnung
 * Die Naeherungsformeln der NOAA (Gleichung der Zeit und Deklination als
 * Fourier-Reihe im Jahreswinkel, „General Solar Position Calculations",
 * NOAA Global Monitoring Division). Sonnenauf- bzw. -untergang ist der
 * Zeitpunkt, an dem der Sonnenmittelpunkt 0,833° unter dem Horizont steht
 * (Refraktion plus Sonnenradius) — die uebliche Definition in Kalendern.
 * Genauigkeit in mittleren Breiten: etwa ±2 min, mehr braucht die Frage
 * „reicht es noch fuer 90 min?" nicht.
 *
 * ## Grenzen
 *  * Hoehe ueber dem Meer, Berge am Horizont und Daemmerung bleiben unberuecksichtigt.
 *    Die buergerliche Daemmerung (noch rund 30–40 min lesbares Licht) ist bewusst
 *    **nicht** eingerechnet: Wer Intervalle oder eine lange Runde plant, soll nicht
 *    in der Daemmerung heimkommen.
 *  * Polartag und Polarnacht werden erkannt ([SunTimes.polarDay], [SunTimes.polarNight]).
 */

/**
 * Sonnenzeiten eines Tages.
 *
 * @property sunrise Sonnenaufgang; `null` bei Polartag oder Polarnacht.
 * @property sunset Sonnenuntergang; `null` bei Polartag oder Polarnacht.
 * @property polarDay die Sonne geht an diesem Tag nicht unter.
 * @property polarNight die Sonne geht an diesem Tag nicht auf.
 */
data class SunTimes(
    val sunrise: Instant?,
    val sunset: Instant?,
    val polarDay: Boolean = false,
    val polarNight: Boolean = false,
)

/** Zenitdistanz bei Sonnenauf-/untergang in Grad (90° + 50′: Refraktion und Sonnenradius). */
const val SUN_ZENITH_DEG: Double = 90.833

/**
 * Sonnenauf- und -untergang am Kalendertag [date] fuer den Ort [lat]/[lon]
 * (Grad, Osten positiv). Gerechnet fuer den UTC-Tag [date]; fuer Orte, deren
 * Zeitzone nicht mehr als ein paar Stunden von UTC abweicht (Europa), ist das
 * derselbe Tag wie lokal.
 */
fun sunTimes(date: LocalDate, lat: Double, lon: Double): SunTimes {
    val gamma = 2.0 * PI / 365.0 * (date.dayOfYear - 1)
    // Zeitgleichung in Minuten.
    val eqTime = 229.18 * (
        0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
        )
    // Deklination in Radiant.
    val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
        0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
        0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
    val latRad = Math.toRadians(lat.coerceIn(-89.9, 89.9))
    val cosH = cos(Math.toRadians(SUN_ZENITH_DEG)) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
    if (cosH > 1.0) return SunTimes(null, null, polarNight = true)
    if (cosH < -1.0) return SunTimes(null, null, polarDay = true)
    val haDeg = Math.toDegrees(acos(cosH))
    val midnight = date.atStartOfDay().toInstant(ZoneOffset.UTC)
    val riseMin = 720.0 - 4.0 * (lon + haDeg) - eqTime
    val setMin = 720.0 - 4.0 * (lon - haDeg) - eqTime
    return SunTimes(
        sunrise = midnight.plusSeconds((riseMin * 60.0).toLong()),
        sunset = midnight.plusSeconds((setMin * 60.0).toLong()),
    )
}
