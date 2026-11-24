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
 * @property ev100 Belichtung der Kamera als EV100 (Blende 16, ISO 100; die
 *   Darstellung rechnet daraus die Verschlusszeit). Gemessen am Licht der
 *   Tageszeit, nicht fest: frueher galt immer EV 15 (Sonnige-16-Regel), dadurch
 *   war die Daemmerung rund zwei Blenden zu dunkel.
 * @property grade Tonwertabbildung und Farbgebung.
 * @property fogInScatteringStart, fogInScatteringSize Sonnenlicht im Dunst
 *   (`FogOptions.inScattering…`): ein warmer Schein gegen die tiefe Sonne.
 * @property skyGlow, skyGlowSize breiter Hof um die Sonne im Himmelsmaterial.
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
    val ev100: Double = DEFAULT_EV100,
    val grade: ToneGradeLook = ToneGradeLook(),
    val fogInScatteringStart: Double = 0.0,
    val fogInScatteringSize: Double = -1.0,
    val skyGlow: Double = 0.0,
    val skyGlowSize: Double = 8.0,
) {
    /** Filaments Belichtung `1 / (1,2 · 2^EV100)`. */
    val exposure: Double get() = SkyLookModel.exposureForEv100(ev100)

    /** Wert, mit dem Filament die Skybox und die Dunstfarbe multipliziert (`ambientLux × Belichtung`). */
    val skyScale: Double get() = ambientLux * exposure

    /** Dunstfarbe fuer `View.fogOptions.color`: der sichtbare Horizont, geteilt durch [skyScale]. */
    val fogColor: SkyRgb get() = look.horizon * (1.0 / skyScale)

    /** Sonnenazimut in Grad (0 = Nord, 90 = Ost) — fuer den Betrachter. */
    val sunAzimuthDeg: Double
        get() = (Math.toDegrees(Math.atan2(sunEast, sunNorth)) + 360.0) % 360.0

    companion object {
        /** Sonnige-16-Regel: Blende 16, 1/125 s, ISO 100. */
        val DEFAULT_EV100: Double = Math.log(16.0 * 16.0 * 125.0) / Math.log(2.0)
    }
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
        // Goldene Stunde: tiefe Sonne kraeftig warm, Mittag fast weiss.
        val sunColor = SkyRgb(1.0, 0.70, 0.44).lerp(SkyRgb(1.0, 0.96, 0.90), high)
        val sunLux = (70_000.0 + 40_000.0 * high) * (1.0 - 0.35 * haze)

        // Umgebungslicht: Himmel oben, warmer Horizont, Rueckstrahlung vom Boden. Bewusst
        // weniger blau als der sichtbare Himmel — frueher (Zenit 0,16/0,34/0,78, Boden
        // 0,20/0,22/0,14) beleuchtete bei tiefer Sonne fast nur noch der blaue Himmel die
        // Welt: Wiese und Weg wurden graublau, Schatten schwarzblau.
        val clearZenith = SkyRgb(0.50, 0.48, 0.56).lerp(SkyRgb(0.40, 0.49, 0.68), high)
        val zenith = clearZenith.lerp(SkyRgb(0.52, 0.57, 0.66), haze * 0.6)
        val clearHorizon = SkyRgb(1.00, 0.80, 0.62).lerp(SkyRgb(0.72, 0.77, 0.84), high)
        val horizon = clearHorizon.lerp(SkyRgb(0.80, 0.80, 0.80), haze * 0.5)
        val ground = SkyRgb(0.36, 0.30, 0.20).lerp(SkyRgb(0.32, 0.31, 0.22), high)
        // Unter dem Kronendach (Waldweg im Schatten) gibt es nur dieses Licht —
        // Filament verdeckt das Umgebungslicht nicht durch Geometrie. Bei tiefer
        // Sonne deshalb nicht unter 26 000 (Bitte aus docs/indoor/world.md).
        val ambientLux = 26_000.0 + 8_000.0 * high

        // Belichtung wie ein Handbelichtungsmesser: Leuchtdichte einer weissen, waagrechten
        // Flaeche aus Sonne (E·cos/π) und Umgebungslicht (Filament: albedo × Strahldichte-
        // Mittel; fuer die Normale nach oben ≈ 0,2·Horizont + 0,8·Zenit, Boden ausgenommen).
        val sunOnGround = sunLux * sin(altR) * sunColor.luminance / Math.PI
        val ambientOnGround = ambientLux * (horizon * 0.2 + zenith * 0.8).luminance
        val whiteLuminance = sunOnGround + ambientOnGround
        // Weiss → 1,0 nach der Belichtung (Mittelgrau 0,18); abends eine Spur dunkler fuer die
        // Stimmung, aber hell genug zum Fahren.
        val ev100 = log2(whiteLuminance / (1.2 * WHITE_TARGET)) + DUSK_EV_BIAS * (1.0 - high)
        val grade = ToneGradeLook(vibrance = 1.18 - 0.05 * haze, contrast = 1.06)

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
            ambientLux = ambientLux,
            iblZenith = zenith,
            iblHorizon = horizon,
            iblGround = ground,
            look = SkyLookModel.look(alt, sunColor, haze, grade),
            fogDensity = max(byHaze, byWorld),
            ev100 = ev100,
            grade = grade,
            // Warmer Schein im Dunst gegen die tiefe Sonne, erst ab 300 m (nicht auf dem Weg).
            fogInScatteringStart = 300.0,
            fogInScatteringSize = 18.0 + 30.0 * high,
            skyGlow = 0.10 * (1.0 - high) + 0.03,
            skyGlowSize = 6.0 + 10.0 * high,
        )
    }

    /** Helligkeit einer weissen Flaeche nach der Belichtung. */
    const val WHITE_TARGET = 1.05

    /** Daemmerung so viel dunkler als gemessen (EV; positiv = dunkler). */
    const val DUSK_EV_BIAS = 0.05

    private fun log2(x: Double) = Math.log(x) / Math.log(2.0)
}
