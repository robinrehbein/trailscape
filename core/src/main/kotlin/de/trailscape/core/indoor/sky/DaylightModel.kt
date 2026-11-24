package de.trailscape.core.indoor.sky

import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Licht und Himmel fuer einen [SkySetting] — ohne Filament, ohne Koordinaten
 * der Darstellung (Richtungen in ENU: Ost, Nord, oben).
 *
 * @property sunEast, sunNorth, sunUp Richtung **zur** Sonne (Einheitsvektor),
 *   Hoehe schon auf mindestens [IndoorDaylight.DUSK_FLOOR_DEG] angehoben.
 * @property sunAltitudeDeg wirksame Sonnenhoehe.
 * @property sunColor Farbe des Sonnenlichts (linear, hellster Kanal 1).
 * @property sunLux Beleuchtungsstaerke der Sonne.
 * @property ambientLux Staerke des Umgebungslichts (`IndirectLight.intensity`);
 *   Filament multipliziert Skybox und Dunst damit.
 * @property iblZenith, iblHorizon, iblGround Verlauf, aus dem das
 *   **Umgebungslicht** gerechnet wird (relativ zu [ambientLux]). Bewusst
 *   getrennt vom sichtbaren Himmel [look]: Die Welt ist mit diesem Licht
 *   abgestimmt, und ein kraeftig blauer Himmel soll sie nicht blau faerben.
 * @property look der sichtbare Himmel (szenenlinear).
 * @property fogDensity Ausloeschung des Dunsts in 1/m auf Fahrerhoehe.
 */
data class DaylightLighting(
    val sunEast: Double,
    val sunNorth: Double,
    val sunUp: Double,
    val sunAltitudeDeg: Double,
    val sunColor: SkyRgb,
    val sunLux: Double,
    val ambientLux: Double,
    val iblZenith: SkyRgb,
    val iblHorizon: SkyRgb,
    val iblGround: SkyRgb,
    val look: SkyLook,
    val fogDensity: Double,
) {
    /** Wert, mit dem Filament die Skybox und die Dunstfarbe multipliziert (`ambientLux × Belichtung`). */
    val skyScale: Double get() = ambientLux * SkyLookModel.EXPOSURE

    /** Dunstfarbe fuer `View.fogOptions.color`: der sichtbare Horizont, geteilt durch [skyScale]. */
    val fogColor: SkyRgb get() = look.horizon * (1.0 / skyScale)

    /** Sonnenazimut in Grad (0 = Nord, 90 = Ost) — fuer den Betrachter. */
    val sunAzimuthDeg: Double
        get() = (Math.toDegrees(Math.atan2(sunEast, sunNorth)) + 360.0) % 360.0
}

object DaylightModel {
    /**
     * Licht fuer [sky] an der Breite [latDeg]. [viewDistanceM] ist die
     * Sichtweite der Welt (`IndoorQuality.viewDistanceM`): Der Dunst ist so
     * dicht, dass das Gelaende dort fast ganz in der Horizontfarbe
     * verschwindet — sonst bricht die Welt am Rand der Kacheln sichtbar ab.
     */
    fun lighting(sky: SkySetting, latDeg: Double, viewDistanceM: Double? = null): DaylightLighting {
        val (e, n, u) = IndoorDaylight.sunEnu(sky.hourOfDay, sky.dayOfYear, latDeg)
        val rawAlt = Math.toDegrees(asin(u.coerceIn(-1.0, 1.0)))
        val alt = max(rawAlt, IndoorDaylight.DUSK_FLOOR_DEG)
        // Waagrechte Richtung behalten, Hoehe auf mindestens 8° anheben.
        val horiz = sqrt(e * e + n * n).takeIf { it > 1e-9 }
        val (he, hn) = if (horiz != null) (e / horiz) to (n / horiz) else 0.0 to -1.0
        val altR = Math.toRadians(alt)

        // 0 bei tiefer Sonne, 1 ab etwa 35° Hoehe.
        val high = ((alt - IndoorDaylight.DUSK_FLOOR_DEG) / 27.0).coerceIn(0.0, 1.0)
        val haze = sky.haze.coerceIn(0.0, 1.0)
        val sunColor = SkyRgb(1.0, 0.76, 0.52).lerp(SkyRgb(1.0, 0.97, 0.92), high)
        val sunLux = (70_000.0 + 40_000.0 * high) * (1.0 - 0.35 * haze)

        // Umgebungslicht wie bisher (die Welt ist damit abgestimmt).
        val clearZenith = SkyRgb(0.16, 0.34, 0.78).lerp(SkyRgb(0.10, 0.28, 0.75), high)
        val zenith = clearZenith.lerp(SkyRgb(0.45, 0.55, 0.70), haze * 0.7)
        val clearHorizon = SkyRgb(0.85, 0.72, 0.62).lerp(SkyRgb(0.62, 0.74, 0.90), high)
        val horizon = clearHorizon.lerp(SkyRgb(0.78, 0.80, 0.82), haze * 0.6)

        // Sichtweite 30 km (klar) .. 3 km (dicht); Koschmieder: Dichte ≈ 3,9 / Sichtweite.
        val visibilityM = 30_000.0 * (1.0 - haze) + 3_000.0 * haze
        val byHaze = 3.9 / visibilityM
        // Am Rand der Welt rund 90 % Dunst (optischer Weg 2,3).
        val byWorld = viewDistanceM?.takeIf { it > 0.0 }?.let { 2.3 / it } ?: 0.0
        return DaylightLighting(
            sunEast = he * cos(altR),
            sunNorth = hn * cos(altR),
            sunUp = sin(altR),
            sunAltitudeDeg = alt,
            sunColor = sunColor,
            sunLux = sunLux,
            // Unter dem Kronendach (Waldweg im Schatten) gibt es nur dieses Licht —
            // Filament verdeckt das Umgebungslicht nicht durch Geometrie. Bei tiefer
            // Sonne deshalb nicht unter 26 000 (Bitte aus docs/indoor/world.md).
            ambientLux = 26_000.0 + 8_000.0 * high,
            iblZenith = zenith,
            iblHorizon = horizon,
            iblGround = SkyRgb(0.20, 0.22, 0.14),
            look = SkyLookModel.look(alt, sunColor, haze),
            fogDensity = max(byHaze, byWorld),
        )
    }
}
