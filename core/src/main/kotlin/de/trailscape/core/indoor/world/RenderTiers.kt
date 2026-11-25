package de.trailscape.core.indoor.world

/**
 * Grafikstufe der 3D-Ansicht, in den Indoor-Einstellungen waehlbar — getrennt von
 * der Qualitaet, mit der eine Welt gebaut ist ([IndoorQuality]: Texturen, Dreiecke).
 *
 *  * [STANDARD]: wie bisher, die Voreinstellung der Weltqualitaet
 *    ([RenderSettings.forQuality]); **Vorgabe**, bis Hoch/Ultra auf dem Geraet
 *    geprueft sind.
 *  * [HIGH]: Umgebungsverdeckung, Bloom, Spiegelungs-Umgebungslicht,
 *    Weissabgleich je Tageszeit, nah an der Strecke Baeume mit echter
 *    Astgeometrie aus der Bibliothek (docs/indoor/baeume.md).
 *  * [ULTRA]: dazu zeitliche Kantenglaettung, hoehere Effektguete, Gelaende wirft
 *    Schatten (4 Kaskaden bis 800 m), dezente Vignette, mehr und weiter
 *    reichende Bibliotheksbaeume — fuer starke Geraete.
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
        dynamicResolutionMinScale = HIGH_DYNRES_MIN_SCALE,
        treeDetail = 1,
    )

    /**
     * Untergrenze der dynamischen Aufloesung fuer Hoch/Ultra. Mit FSR-Hochskalierung
     * gilt 0,75 je Achse (≈ 56 % der Pixel, AMDs Stufe „Quality“ 0,67–0,77) als Grenze,
     * unter der das Bild sichtbar weich wird; 0,6 (Standard) entspricht schon
     * „Balanced/Performance“. Hoeher heisst weniger Reserve: Faellt die GPU unter
     * 60 B/s, sinkt bei Hoch/Ultra eher die Bildrate als die Schaerfe. Nicht auf dem
     * Geraet gemessen — in der Diagnose einstellbar.
     */
    const val HIGH_DYNRES_MIN_SCALE = 0.75

    /** Stufe Ultra. */
    fun ultra(): RenderSettings = high().copy(
        temporalAA = true,
        effectQuality = 2,
        shadowCascades = 4,
        shadowFarM = ULTRA_SHADOW_FAR_M,
        // Nicht `stableShadows`: zusammen mit 800 m verschwanden im Referenzrenderer alle Schatten.
        terrainShadows = true,
        vignette = true,
        treeDetail = 2,
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
        dynamicResolutionMinScale = 0.6,
        treeDetail = 0,
    )
}
