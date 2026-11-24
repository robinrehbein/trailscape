package de.trailscape.core.indoor.sky

/**
 * Parameter der eigenen Filament-Materialien (`app/src/main/materials/sky.mat`,
 * `water.mat`) zu einem [DaylightLighting]. An einer Stelle, damit Darstellung
 * (`FilamentCore`) und Filament-Referenzrenderer (`indoor-dev sky` →
 * `tool/indoor-dev/filament`) genau dieselben Werte setzen.
 *
 * Werte sind `Double` (Skalar), `DoubleArray(2)` oder `DoubleArray(3)`.
 */
object SkyShading {
    /** Massstab der Wolkenebene (Texturwiederholungen je Einheit der Projektion). */
    const val CLOUD_SCALE = 0.32

    /** Wolkenzug in Textureinheiten je Sekunde: ein Wolkenfeld quert den Himmel in einigen Minuten. */
    val WIND = doubleArrayOf(0.0022, 0.0009)

    /** Anteil der Dunst-Einstreuung, den der Himmel direkt am Horizont zeigt (≈ Deckkraft des Dunsts in der Ferne). */
    const val SKY_IN_SCATTERING = 0.7

    /** Bedeckung des Materials: Schwelle im Shader, abgestimmt auf die Verteilung von [SkyClouds.cloudTexture]. */
    fun cloudCover(look: SkyLook): Double = 0.12 + 0.75 * look.cloudCover

    fun sky(l: DaylightLighting): Map<String, Any> = linkedMapOf(
        "zenith" to l.look.zenith.arr(),
        "horizon" to l.look.horizon.arr(),
        "cloudLit" to l.look.cloudLit.arr(),
        "cloudShade" to l.look.cloudShade.arr(),
        "cloudCover" to cloudCover(l.look),
        "cloudScale" to CLOUD_SCALE,
        "wind" to WIND,
        "horizonBand" to SkyLookModel.HORIZON_BAND,
        "glow" to l.skyGlow,
        "glowSize" to l.skyGlowSize,
        "inScatteringSize" to (if (l.fogInScatteringSize > 0) l.fogInScatteringSize else 32.0),
        "inScatteringStrength" to (if (l.fogInScatteringSize > 0) SKY_IN_SCATTERING else 0.0),
        "sunDisk" to 1.0,
    )

    /** Albedo des Wasserkoerpers (linear): dunkles Gruenblau, damit die Spiegelung traegt. */
    val WATER_DEEP = SkyRgb(0.018, 0.040, 0.046)

    fun water(l: DaylightLighting): Map<String, Any> = linkedMapOf(
        "deepColor" to WATER_DEEP.arr(),
        "zenith" to l.look.zenith.arr(),
        "horizon" to l.look.horizon.arr(),
        "horizonBand" to SkyLookModel.HORIZON_BAND,
        "waveScale" to 0.045,
        "waveStrength" to 1.0,
        "reflectivity" to 0.85,
    )

    private fun SkyRgb.arr() = doubleArrayOf(r, g, b)
}
