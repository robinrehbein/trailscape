package de.trailscape.core.indoor.sky

import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Tageszeit der Indoor-Fahrt, wie sie in den Indoor-Einstellungen gewaehlt
 * wird. [AUTO] folgt der echten Uhrzeit am Ort der Strecke, die drei anderen
 * sind feste, gut aussehende Sonnenstaende.
 */
enum class IndoorTimeOfDay {
    AUTO,
    MORNING,
    NOON,
    EVENING,
    ;

    companion object {
        /** Gespeicherter Name → Wert; Unbekanntes oder `null` wird [AUTO]. */
        fun fromName(name: String?): IndoorTimeOfDay =
            entries.firstOrNull { it.name == name?.trim() } ?: AUTO
    }
}

/**
 * # Sonnenstand und Tageszeit
 *
 * Reine Rechnung (keine Uhr, kein Android): Aus der Wahl in den Einstellungen,
 * dem Zeitpunkt und dem Ort der Strecke wird ein [SkySetting] fuer
 * `WorldRenderer.setSky`. `SkySetting.hourOfDay` ist dabei **wahre
 * Sonnenzeit** (12 = Sonne im Sueden), so rechnet die Darstellung.
 *
 * Nachts wird nicht dunkel gefahren: Steht die echte Sonne tiefer als
 * [DUSK_FLOOR_DEG], nimmt [AUTO] den naechsten Zeitpunkt, an dem sie genau so
 * hoch steht — abends die Abenddaemmerung, nachts nach Mitternacht die
 * Morgendaemmerung. Die Darstellung hebt die Sonne ohnehin nie unter 8°.
 */
object IndoorDaylight {
    /** Untergrenze der Sonnenhoehe (helle Daemmerung), gleich der Untergrenze der Darstellung. */
    const val DUSK_FLOOR_DEG = 8.0

    /** Tag der festen Tageszeiten: kurz vor Sommeranfang — lange Tage, kraeftiges Licht. */
    const val PRESET_DAY_OF_YEAR = 165

    /** Sonnenhoehe der festen Morgensonne (steigend, im Osten). */
    const val MORNING_ALTITUDE_DEG = 16.0

    /** Sonnenhoehe der festen Abendsonne (fallend, im Westen) — warmes, flaches Licht. */
    const val EVENING_ALTITUDE_DEG = 11.0

    /** Mittag: kurz nach dem hoechsten Stand, damit Schatten nicht ganz senkrecht fallen. */
    const val NOON_SOLAR_HOUR = 12.75

    private const val DAY_MS = 86_400_000L

    /**
     * Der Himmel fuer die Wahl [choice].
     *
     * @param epochMs Zeitpunkt (nur fuer [IndoorTimeOfDay.AUTO]).
     * @param latDeg, lonDeg Ort der Strecke (Ursprung der Welt).
     * @param haze Dunst 0..1, wie [SkySetting.haze].
     */
    fun skySetting(
        choice: IndoorTimeOfDay,
        epochMs: Long,
        latDeg: Double,
        lonDeg: Double,
        haze: Double = SkySetting.DEFAULT.haze,
    ): SkySetting = when (choice) {
        IndoorTimeOfDay.AUTO -> {
            val (hour, day) = solarTime(epochMs, lonDeg)
            SkySetting(hourOfDay = daylightHour(hour, day, latDeg), dayOfYear = day, haze = haze)
        }
        IndoorTimeOfDay.MORNING -> preset(hourAtAltitude(MORNING_ALTITUDE_DEG, PRESET_DAY_OF_YEAR, latDeg, morning = true), haze)
        IndoorTimeOfDay.NOON -> preset(NOON_SOLAR_HOUR, haze)
        IndoorTimeOfDay.EVENING -> preset(hourAtAltitude(EVENING_ALTITUDE_DEG, PRESET_DAY_OF_YEAR, latDeg, morning = false), haze)
    }

    private fun preset(hour: Double, haze: Double) =
        SkySetting(hourOfDay = hour, dayOfYear = PRESET_DAY_OF_YEAR, haze = haze)

    /**
     * Wahre Sonnenzeit (0..24) und Tag im Jahr (1..366) an der Laenge [lonDeg]
     * zum Zeitpunkt [epochMs]: Weltzeit + Laenge/15 + Zeitgleichung.
     */
    fun solarTime(epochMs: Long, lonDeg: Double): Pair<Double, Int> {
        // Mittlere Ortszeit, dann den Tag dieser Ortszeit bestimmen.
        val localMs = epochMs + (lonDeg / 15.0 * 3_600_000.0).toLong()
        val dayIndex = Math.floorDiv(localMs, DAY_MS)
        val day = dayOfYear(dayIndex)
        var hour = Math.floorMod(localMs, DAY_MS) / 3_600_000.0 + equationOfTimeMin(day) / 60.0
        hour -= 24.0 * floor(hour / 24.0)
        return hour to day
    }

    /** Tag im Jahr (1..366) fuer Tage seit 1.1.1970 (proleptischer Gregorianischer Kalender). */
    fun dayOfYear(daysSinceEpoch: Long): Int {
        // Civil-from-days nach Howard Hinnant.
        val z = daysSinceEpoch + 719_468
        val era = Math.floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        val leap = (year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        return (cum[(m - 1).toInt()] + d + if (leap && m > 2) 1 else 0).toInt()
    }

    /** Zeitgleichung in Minuten (wahre minus mittlere Sonnenzeit), Naeherung auf etwa eine Minute. */
    fun equationOfTimeMin(dayOfYear: Int): Double {
        val b = 2 * PI * (dayOfYear - 81) / 364.0
        return 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
    }

    /**
     * Richtung zur Sonne in ENU (`e`, `n`, `u`) fuer wahre Sonnenzeit
     * [hourOfDay], Tag [dayOfYear] und Breite [latDeg]. Dieselbe Naeherung
     * wie in der Darstellung.
     */
    fun sunEnu(hourOfDay: Double, dayOfYear: Int, latDeg: Double): Triple<Double, Double, Double> {
        val decl = Math.toRadians(-23.44 * cos(2 * PI / 365.0 * (dayOfYear.coerceIn(1, 366) + 10)))
        val hourAngle = Math.toRadians(15.0 * (hourOfDay.coerceIn(0.0, 24.0) - 12.0))
        val lat = Math.toRadians(latDeg.coerceIn(-89.9, 89.9))
        val east = -cos(decl) * sin(hourAngle)
        val north = sin(decl) * cos(lat) - cos(decl) * cos(hourAngle) * sin(lat)
        val up = sin(decl) * sin(lat) + cos(decl) * cos(hourAngle) * cos(lat)
        return Triple(east, north, up)
    }

    /** Sonnenhoehe in Grad (ohne Untergrenze). */
    fun sunAltitudeDeg(hourOfDay: Double, dayOfYear: Int, latDeg: Double): Double =
        Math.toDegrees(asin(sunEnu(hourOfDay, dayOfYear, latDeg).third.coerceIn(-1.0, 1.0)))

    /**
     * Sonnenzeit, zu der die Sonne am Tag [day] die Hoehe [altDeg] erreicht —
     * vormittags ([morning]) steigend, sonst nachmittags fallend. Erreicht sie
     * die Hoehe nie, der Mittag; steht sie den ganzen Tag hoeher, Mitternacht
     * bzw. kurz davor (Polarsommer).
     */
    fun hourAtAltitude(altDeg: Double, day: Int, latDeg: Double, morning: Boolean): Double {
        val noonAlt = sunAltitudeDeg(12.0, day, latDeg)
        if (noonAlt <= altDeg) return 12.0
        val edge = if (morning) 0.0 else 24.0
        if (sunAltitudeDeg(edge, day, latDeg) >= altDeg) return if (morning) 0.0 else 23.99
        // Die Hoehe faellt vom Mittag zum Rand monoton: halbieren.
        var lo = 12.0 // Hoehe > altDeg
        var hi = edge // Hoehe < altDeg
        repeat(48) {
            val mid = (lo + hi) / 2
            if (sunAltitudeDeg(mid, day, latDeg) > altDeg) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Die Stunde [hour], wenn die Sonne dann mindestens [DUSK_FLOOR_DEG] hoch
     * steht — sonst der naechstgelegene Zeitpunkt (im Kreis der 24 Stunden),
     * an dem sie genau so hoch steht. Erreicht sie die Untergrenze den ganzen
     * Tag nicht (Polarwinter), der Mittag.
     */
    fun daylightHour(hour: Double, day: Int, latDeg: Double): Double {
        if (sunAltitudeDeg(hour, day, latDeg) >= DUSK_FLOOR_DEG) return hour
        if (sunAltitudeDeg(12.0, day, latDeg) <= DUSK_FLOOR_DEG) return 12.0
        val rise = hourAtAltitude(DUSK_FLOOR_DEG, day, latDeg, morning = true)
        val set = hourAtAltitude(DUSK_FLOOR_DEG, day, latDeg, morning = false)
        fun dist(a: Double, b: Double): Double {
            val d = abs(a - b) % 24.0
            return if (d > 12.0) 24.0 - d else d
        }
        return if (dist(hour, set) <= dist(hour, rise)) set else rise
    }
}
