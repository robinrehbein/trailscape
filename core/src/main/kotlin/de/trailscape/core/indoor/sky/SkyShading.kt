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
    const val CLOUD_SCALE = 0.22

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

    /**
     * Gelaende-Material (`terrain.mat`): Boden-Detail nah am Fahrer ([GroundDetail]), weich aus
     * bei [TERRAIN_DETAIL_RANGE_M]; Waldboden waermer bis [TERRAIN_WARM_RANGE_M]. Unabhaengig
     * von der Tageszeit; `detailRange` 0 schaltet das Detail ab (Qualitaet, Einstellungen).
     */
    fun terrain(detail: Boolean = true): Map<String, Any> = linkedMapOf(
        "detailRange" to if (detail) TERRAIN_DETAIL_RANGE_M else 0.0,
        "detailRepeat" to GroundDetail.TILE_REPEATS,
        "macroRepeat" to GroundDetail.MACRO_REPEATS,
        "detailStrength" to 0.42,
        "bumpStrength" to 3.5,
        "macroStrength" to 0.16,
        "forestWarmth" to 0.55,
        "warmRange" to TERRAIN_WARM_RANGE_M,
    )

    const val TERRAIN_DETAIL_RANGE_M = 55.0
    const val TERRAIN_WARM_RANGE_M = 260.0

    /**
     * Laub mit Wind (`foliage.mat`). [windToward]: waagrechte Windrichtung im glTF-Raum (x Osten,
     * z Sueden), wird normiert. `windStrength` 0 = Stillstand (Qualitaet, Einstellungen).
     */
    fun foliage(wind: Boolean = true, windToward: DoubleArray = doubleArrayOf(0.8, 0.6)): Map<String, Any> {
        val len = kotlin.math.hypot(windToward[0], windToward[1]).takeIf { it > 1e-6 } ?: 1.0
        return linkedMapOf(
            "windDir" to doubleArrayOf(windToward[0] / len, windToward[1] / len),
            "windStrength" to if (wind) 1.0 else 0.0,
            "windRange" to FOLIAGE_WIND_RANGE_M,
        )
    }

    const val FOLIAGE_WIND_RANGE_M = 110.0

    /**
     * Belaege und Randstreifen (`surface.mat`, `surface_blend.mat`): Software-Anisotropie bis
     * [SURFACE_ANISO_RANGE_M], Boden-Detail auf den Randstreifen bis [SURFACE_DETAIL_RANGE_M].
     * `anisoRange` bzw. `detailRange` 0 = aus (ein Texturzugriff wie der Ubershader).
     */
    fun surface(detail: Boolean = true, aniso: Boolean = true): Map<String, Any> = linkedMapOf(
        "detailRange" to if (detail) SURFACE_DETAIL_RANGE_M else 0.0,
        "detailStrength" to 0.30,
        "bumpStrength" to 2.5,
        "anisoRange" to if (aniso) SURFACE_ANISO_RANGE_M else 0.0,
        "forestWarmth" to 0.5,
    )

    const val SURFACE_DETAIL_RANGE_M = 35.0
    const val SURFACE_ANISO_RANGE_M = 45.0

    private fun SkyRgb.arr() = doubleArrayOf(r, g, b)
}
