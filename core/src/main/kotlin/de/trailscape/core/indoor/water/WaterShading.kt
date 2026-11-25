package de.trailscape.core.indoor.water

import de.trailscape.core.indoor.sky.DaylightLighting
import de.trailscape.core.indoor.sky.SkyLookModel
import de.trailscape.core.indoor.sky.SkyRgb
import de.trailscape.core.indoor.sky.SkyShading

/**
 * # Parameter des Wassermaterials (`app/src/main/materials/water.mat`)
 *
 * An einer Stelle fuer Darstellung (`FilamentCore`) und Filament-Referenzrenderer
 * (`indoor-dev sky` schreibt sie ueber [SkyShading.water] in `sky.json`). Die Namen
 * sind **genau** die Parameter, die `water.mat` deklariert (geprueft in
 * `WaterShadingTest` gegen die .mat-Datei) — Filament bricht nativ ab, wenn ein
 * nicht deklarierter Parameter gesetzt wird. Die Uhr (`time`) und die beiden
 * Texturen (`waves` = Wolkenrauschen, `waveNormals` = [WaterWaves]) setzt der Aufrufer.
 *
 * Himmel, Wolken und Sonnenhof der Spiegelung kommen aus demselben Himmelsmodell wie
 * `sky.mat` ([SkyShading.sky]) — die Spiegelung passt dadurch zu Tageszeit und Wetter.
 *
 * [detail] (0..1) ist die Qualitaetsstufe: 1 = drei Wellenlagen und gespiegelte Wolken,
 * 0 = eine Wellenlage, keine Wolken (niedrige Stufe ohne Boden-Detail).
 */
object WaterShading {
    /** Streuung tief im Wasserkoerper (Albedo, linear): sehr dunkles Gruenblau. */
    val DEEP = SkyRgb(0.010, 0.026, 0.030)

    /** Streuung im flachen, truebem Wasser am Ufer (linear): oliv-gruenlich wie Fluesse im Luftbild. */
    val SHALLOW = SkyRgb(0.045, 0.068, 0.058)

    /** Wellenlaengen der drei Lagen (m je Kachel der Normalenkarte), bewusst krumm zueinander. */
    val LAYER_M = doubleArrayOf(19.3, 6.1, 1.93)

    fun params(l: DaylightLighting, detail: Double = 1.0): Map<String, Any> {
        val sky = SkyShading.sky(l)
        return linkedMapOf(
            "deepColor" to DEEP.arr(),
            "shallowColor" to SHALLOW.arr(),
            "zenith" to l.look.zenith.arr(),
            "horizon" to l.look.horizon.arr(),
            "horizonBand" to SkyLookModel.HORIZON_BAND,
            "bankColor" to bankColor(l).arr(),
            "cloudLit" to sky.getValue("cloudLit"),
            "cloudShade" to sky.getValue("cloudShade"),
            "cloudCover" to sky.getValue("cloudCover"),
            "cloudScale" to sky.getValue("cloudScale"),
            "wind" to sky.getValue("wind"),
            "glow" to sky.getValue("glow"),
            "glowSize" to sky.getValue("glowSize"),
            "waveScale" to 1.0 / LAYER_M[0],
            "waveStrength" to 1.0,
            "reflectivity" to 1.0,
            "clarity" to 0.35,
            "detail" to detail.coerceIn(0.0, 1.0),
        )
    }

    /** Albedo der Uferkulisse (Baeume, Gebuesch, Wiese; linear). */
    val BANK_ALBEDO = SkyRgb(0.10, 0.14, 0.11)

    /**
     * Belichtete Leuchtdichte der Uferkulisse, die sich bei flachem Blick im Wasser spiegelt:
     * Lambert `albedo/π · E`, E = Umgebungslicht (Farbe des IBL-Horizonts) + 15 % Sonne
     * (steile, teils beschattete Flaechen), mal Belichtung — dieselbe Einheit wie
     * `zenith`/`horizon`. Haengt an der Stimmung, nicht an der Horizontfarbe: abends
     * goldgruen statt orange-braun.
     */
    fun bankColor(l: DaylightLighting): SkyRgb {
        val s = l.exposure / Math.PI
        fun ch(albedo: Double, ibl: Double, sun: Double) = albedo * s * (l.ambientLux * ibl + 0.15 * l.sunLux * sun)
        return SkyRgb(
            ch(BANK_ALBEDO.r, l.iblHorizon.r, l.sunColor.r),
            ch(BANK_ALBEDO.g, l.iblHorizon.g, l.sunColor.g),
            ch(BANK_ALBEDO.b, l.iblHorizon.b, l.sunColor.b),
        )
    }

    private fun SkyRgb.arr() = doubleArrayOf(r, g, b)
}
