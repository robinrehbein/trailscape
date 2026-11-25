package de.trailscape.core.indoor.world

/**
 * Grafikstufe der 3D-Ansicht, in den Indoor-Einstellungen waehlbar — getrennt von
 * der Qualitaet, mit der eine Welt gebaut ist ([IndoorQuality]: Texturen, Dreiecke).
 *
 *  * [STANDARD]: wie bisher, die Voreinstellung der Weltqualitaet
 *    ([RenderSettings.forQuality]); **Vorgabe**, bis Hoch/Ultra auf dem Geraet
 *    geprueft sind.
 *  * [HIGH]: Umgebungsverdeckung, Bloom, Spiegelungs-Umgebungslicht,
 *    Weissabgleich je Tageszeit.
 *  * [ULTRA]: dazu zeitliche Kantenglaettung, hoehere Effektguete, Gelaende wirft
 *    Schatten (4 Kaskaden bis 800 m), dezente Vignette — fuer starke Geraete.
 */
enum class IndoorGraphics {
    STANDARD,
    HIGH,
    ULTRA,
    ;

    companion object {
        fun fromNameOrNull(name: String?): IndoorGraphics? = entries.firstOrNull { it.name == name }
    }
}

/** Zuordnung der Effekte zu den Grafikstufen — reine Rechnung, getestet. */
object RenderTiers {
    /** Einstellungen fuer [tier] bei einer Welt der Qualitaet [worldQuality]. */
    fun settingsFor(tier: IndoorGraphics, worldQuality: IndoorQuality): RenderSettings = when (tier) {
        IndoorGraphics.STANDARD -> RenderSettings.forQuality(worldQuality)
        IndoorGraphics.HIGH -> high()
        IndoorGraphics.ULTRA -> ultra()
    }

    /** Stufe Hoch: Grundlage ist HIGH (SSAO, Bloom, bis 5 Mitfahrer). */
    fun high(): RenderSettings = RenderSettings.forQuality(IndoorQuality.HIGH).copy(
        reflections = true,
        effectQuality = 1,
        whiteBalance = true,
    )

    /** Stufe Ultra. */
    fun ultra(): RenderSettings = high().copy(
        temporalAA = true,
        effectQuality = 2,
        shadowCascades = 4,
        shadowFarM = ULTRA_SHADOW_FAR_M,
        stableShadows = true,
        terrainShadows = true,
        vignette = true,
    )

    /** Schattenreichweite der Stufe Ultra (Gelaendeschatten brauchen mehr als 300 m). */
    const val ULTRA_SHADOW_FAR_M = 800.0

    /**
     * Kaskadengrenzen als Anteil der Reichweite [farM]: 3 Kaskaden wie bisher
     * (5 % / 20 %), 4 Kaskaden bei 8 m, 30 m und 100 m (scharf am Fahrer,
     * grob fuer Haenge in der Ferne).
     */
    fun cascadeSplits(cascades: Int, farM: Double): FloatArray =
        if (cascades >= 4) {
            floatArrayOf((8.0 / farM).toFloat(), (30.0 / farM).toFloat(), (100.0 / farM).toFloat())
        } else {
            floatArrayOf(0.05f, 0.2f, 0.5f)
        }

    /**
     * Was im sicheren Grafikmodus ([RenderSettings] nach einem nativen Absturz)
     * von den Zusaetzen der Grafikstufen uebrig bleibt: nichts.
     */
    fun withoutTierExtras(s: RenderSettings): RenderSettings = s.copy(
        reflections = false,
        temporalAA = false,
        effectQuality = 0,
        shadowCascades = 3,
        shadowFarM = 300.0,
        stableShadows = false,
        terrainShadows = false,
        whiteBalance = false,
        vignette = false,
    )
}
