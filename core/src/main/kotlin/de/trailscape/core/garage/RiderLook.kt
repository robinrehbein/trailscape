package de.trailscape.core.garage

import kotlin.math.pow

/**
 * Einfaerbbare Zonen des Fahrermodells (`rider.glb`, `rider_lod.glb`): je Zone
 * ein **eigenes Material** mit neutral grauen Vertexfarben, die Farbe kommt aus
 * `baseColorFactor` (glTF multipliziert ihn mit `COLOR_0`; Kernmaterial, keine
 * Erweiterung). Der Generator (`RiderModel.kt`) schreibt als Vorgabe die
 * Farben von [RiderAvatar.DEFAULT]; die Darstellung setzt je Fahrer andere.
 *
 * Die Trikotzonen sind Felder fuer die Muster ([JerseyPattern]), von unten
 * nach oben: [JERSEY_LOW], [JERSEY_MID], [JERSEY_BAND] (Brust),
 * [JERSEY_UP], [JERSEY_YOKE] (Schultern und Aermel); dazu Seitenteile und
 * Saeume (Bund, Kragen, Aermelabschluss). [materialName] aendert sich nie.
 */
enum class RiderZone(val materialName: String) {
    SKIN("x_mat_skin"),
    JERSEY_LOW("x_mat_jersey_low"),
    JERSEY_MID("x_mat_jersey_mid"),
    JERSEY_BAND("x_mat_jersey_band"),
    JERSEY_UP("x_mat_jersey_up"),
    JERSEY_YOKE("x_mat_jersey_yoke"),
    JERSEY_SIDE("x_mat_jersey_side"),
    JERSEY_TRIM("x_mat_jersey_trim"),
    BIBS("x_mat_bibs"),
    HELMET("x_mat_helmet"),
    SHOES("x_mat_shoes"),
    FRAME("x_mat_frame"),
    FRAME_ACCENT("x_mat_frame_accent"),
    BAR_TAPE("x_mat_bar_tape"),
    ;

    companion object {
        fun byMaterial(name: String?): RiderZone? = entries.firstOrNull { it.materialName == name }
    }
}

/**
 * # Vom Avatar zu den Farben der Zonen
 *
 * Reine Rechnung ohne Filament, getestet; dieselben Werte nutzen die
 * Darstellung (je Instanz), der sichere Grafikmodus (in die Datei
 * geschrieben) und die 2D-Vorschau der Garage.
 */
object RiderLook {
    /**
     * Abdunklung fuer Seitenteile und Saeume (linear), so wie das bisherige
     * dunkle Trikotrot `0x8C2519` zum Trikotrot `0xD8432C` stand.
     */
    const val TRIM_SHADE = 0.38

    /** Farbe je Zone in sRGB `0xRRGGBB`. */
    fun zoneColors(a: RiderAvatar): Map<RiderZone, Int> {
        val p = a.jersey
        val acc = a.jerseyAccent
        val dark = shade(p, TRIM_SHADE)
        fun mix(t: Double) = mixSrgb(p, acc, t)
        val jersey: Map<RiderZone, Int> = when (a.pattern) {
            JerseyPattern.SOLID -> mapOf(
                RiderZone.JERSEY_LOW to p, RiderZone.JERSEY_MID to p, RiderZone.JERSEY_BAND to p,
                RiderZone.JERSEY_UP to p, RiderZone.JERSEY_YOKE to p, RiderZone.JERSEY_SIDE to p,
            )
            JerseyPattern.BAND -> mapOf(
                RiderZone.JERSEY_LOW to p, RiderZone.JERSEY_MID to p, RiderZone.JERSEY_BAND to acc,
                RiderZone.JERSEY_UP to p, RiderZone.JERSEY_YOKE to p, RiderZone.JERSEY_SIDE to dark,
            )
            JerseyPattern.SPLIT -> mapOf(
                RiderZone.JERSEY_LOW to p, RiderZone.JERSEY_MID to p, RiderZone.JERSEY_BAND to acc,
                RiderZone.JERSEY_UP to acc, RiderZone.JERSEY_YOKE to acc, RiderZone.JERSEY_SIDE to dark,
            )
            JerseyPattern.FADE -> mapOf(
                RiderZone.JERSEY_LOW to p, RiderZone.JERSEY_MID to mix(0.25), RiderZone.JERSEY_BAND to mix(0.5),
                RiderZone.JERSEY_UP to mix(0.75), RiderZone.JERSEY_YOKE to acc, RiderZone.JERSEY_SIDE to dark,
            )
            JerseyPattern.STRIPES -> mapOf(
                RiderZone.JERSEY_LOW to p, RiderZone.JERSEY_MID to acc, RiderZone.JERSEY_BAND to p,
                RiderZone.JERSEY_UP to acc, RiderZone.JERSEY_YOKE to p, RiderZone.JERSEY_SIDE to dark,
            )
        }
        val out = LinkedHashMap<RiderZone, Int>()
        out[RiderZone.SKIN] = GaragePalettes.SKIN[a.skin]
        out.putAll(jersey)
        out[RiderZone.JERSEY_TRIM] = dark
        out[RiderZone.BIBS] = a.bibs
        out[RiderZone.HELMET] = a.helmet
        out[RiderZone.SHOES] = a.shoes
        out[RiderZone.FRAME] = a.frame
        out[RiderZone.FRAME_ACCENT] = a.frameAccent
        out[RiderZone.BAR_TAPE] = a.barTape
        check(out.size == RiderZone.entries.size)
        return out
    }

    /** `baseColorFactor` je Materialname (linear RGBA, Deckung 1). */
    fun materialFactors(a: RiderAvatar): Map<String, FloatArray> =
        zoneColors(a).entries.associate { (zone, srgb) -> zone.materialName to linearRgba(srgb) }

    /**
     * Die anderen Radfahrer der Strecke (dekorativ): sechs gut unterscheidbare
     * Trikots in festen Farben, jeweils mit eigenem Rad und Helm. Faellt eine davon
     * mit dem Trikot des Nutzers ([own]) zusammen, nimmt sie die Ersatzfarbe —
     * so bleibt der eigene Fahrer immer erkennbar.
     */
    fun otherRiders(own: RiderAvatar): List<RiderAvatar> {
        val c = GaragePalettes
        val base = listOf(
            RiderAvatar(skin = 4, jersey = c.BLUE.srgb, pattern = JerseyPattern.SOLID, frame = c.BLACK.srgb, helmet = c.BLACK.srgb),
            RiderAvatar(skin = 1, jersey = c.YELLOW.srgb, jerseyAccent = c.BLACK.srgb, pattern = JerseyPattern.BAND, frame = c.SILVER.srgb, bibs = c.BLACK.srgb),
            RiderAvatar(skin = 6, jersey = c.GREEN.srgb, jerseyAccent = c.WHITE.srgb, pattern = JerseyPattern.SPLIT, frame = c.WHITE.srgb, helmet = c.GREEN.srgb),
            RiderAvatar(skin = 3, jersey = c.WHITE.srgb, jerseyAccent = c.RED.srgb, pattern = JerseyPattern.STRIPES, frame = c.RED.srgb, bibs = c.NAVY.srgb),
            RiderAvatar(skin = 0, jersey = c.BLACK.srgb, jerseyAccent = c.ORANGE.srgb, pattern = JerseyPattern.BAND, frame = c.ORANGE.srgb, shoes = c.BLACK.srgb),
            RiderAvatar(skin = 5, jersey = c.TEAL.srgb, jerseyAccent = c.SKY.srgb, pattern = JerseyPattern.FADE, frame = c.NAVY.srgb, helmet = c.SKY.srgb),
        )
        val spare = c.PURPLE.srgb
        return base.map { if (it.jersey == own.jersey) it.copy(jersey = if (own.jersey == spare) c.PINK.srgb else spare) else it }
    }

    // ------------------------------------------------------------------ Farbrechnung

    fun srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(c: Double): Double = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055

    /** sRGB `0xRRGGBB` → lineares RGBA (Deckung 1), jeder Kanal in `[0, 1]`. */
    fun linearRgba(srgb: Int): FloatArray = floatArrayOf(
        srgbToLinear(((srgb shr 16) and 0xFF) / 255.0).toFloat(),
        srgbToLinear(((srgb shr 8) and 0xFF) / 255.0).toFloat(),
        srgbToLinear((srgb and 0xFF) / 255.0).toFloat(),
        1f,
    )

    /** Mischung zweier sRGB-Farben in linearem Licht. */
    fun mixSrgb(a: Int, b: Int, t: Double): Int = channels(a, b) { x, y -> x + (y - x) * t }

    /** sRGB-Farbe in linearem Licht mit [k] multipliziert (abgedunkelt). */
    fun shade(srgb: Int, k: Double): Int = channels(srgb, srgb) { x, _ -> x * k }

    private inline fun channels(a: Int, b: Int, f: (Double, Double) -> Double): Int {
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val x = srgbToLinear(((a shr shift) and 0xFF) / 255.0)
            val y = srgbToLinear(((b shr shift) and 0xFF) / 255.0)
            val v = (linearToSrgb(f(x, y).coerceIn(0.0, 1.0)) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            out = out or (v shl shift)
        }
        return out
    }
}
