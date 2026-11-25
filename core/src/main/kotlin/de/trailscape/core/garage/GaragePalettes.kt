package de.trailscape.core.garage

/**
 * Eine Farbe der Garage: [id] fuer Texte (`garage_color_<id>`) und
 * Freischaltungen, [srgb] als `0xRRGGBB`.
 */
data class GarageColor(val id: String, val srgb: Int)

/** Was sich in der Garage einfaerben laesst; jede Stelle hat ihre Palette ([GaragePalettes.forSlot]). */
enum class GarageSlot {
    JERSEY,
    JERSEY_ACCENT,
    BIBS,
    HELMET,
    SHOES,
    FRAME,
    FRAME_ACCENT,
    BAR_TAPE,
}

/**
 * # Kuratierte Paletten der Garage
 *
 * Bewusst wenige, gut unterscheidbare Farben statt eines freien Farbrads:
 * Sie sehen auf dem Fahrermodell (Vertexfarbe × Grundfarbe, Licht der
 * Strecke) alle ordentlich aus, und jede hat einen Namen fuer Bildschirmleser.
 *
 * Der Hautton ist eine eigene Palette, ohne Namen und ohne Reihenfolge-Wertung
 * („Ton 1" … „Ton 8", hell nach dunkel).
 */
object GaragePalettes {
    val RED = GarageColor("red", 0xD8432C)
    val ORANGE = GarageColor("orange", 0xE07A1F)
    val YELLOW = GarageColor("yellow", 0xE8B923)
    val LIME = GarageColor("lime", 0x8DB33A)
    val GREEN = GarageColor("green", 0x1F7A4D)
    val TEAL = GarageColor("teal", 0x1F6272)
    val SKY = GarageColor("sky", 0x3E8FC9)
    val BLUE = GarageColor("blue", 0x2C5BB8)
    val NAVY = GarageColor("navy", 0x1E2E5C)
    val PURPLE = GarageColor("purple", 0x6A3D9A)
    val PINK = GarageColor("pink", 0xD2467E)
    val WHITE = GarageColor("white", 0xF1EEE8)
    val SILVER = GarageColor("silver", 0xA9ACB1)
    val GREY = GarageColor("grey", 0x6B6F76)
    val ANTHRACITE = GarageColor("anthracite", 0x3C3E45)
    val BLACK = GarageColor("black", 0x25262B)
    val OLIVE = GarageColor("olive", 0x4B5233)
    val BROWN = GarageColor("brown", 0x6A4B35)
    val CORK = GarageColor("cork", 0xB28B5C)

    /** Sonderfarben: zum Freischalten vorgesehen ([GarageUnlocks]), bis dahin frei. */
    val GOLD = GarageColor("gold", 0xC9A227)
    val COPPER = GarageColor("copper", 0xB0683A)

    /** Grundfarben fuer Trikot, Helm, Schuhe, Rahmen und Akzente. */
    private val BASE = listOf(RED, ORANGE, YELLOW, LIME, GREEN, TEAL, SKY, BLUE, NAVY, PURPLE, PINK, WHITE, SILVER, GREY, BLACK)

    private val KIT = BASE + listOf(GOLD, COPPER)
    private val BIBS = listOf(BLACK, ANTHRACITE, NAVY, BLUE, OLIVE, BROWN, RED, WHITE)
    private val SHOES = listOf(WHITE, BLACK, GREY, RED, YELLOW, BLUE, LIME, GOLD)
    private val BAR_TAPE = listOf(BLACK, WHITE, CORK, BROWN, RED, BLUE, YELLOW, LIME, GOLD)

    /** Alle Farben, nach [GarageColor.id] eindeutig. */
    val ALL: List<GarageColor> = (KIT + BIBS + SHOES + BAR_TAPE + listOf(CORK)).distinctBy { it.id }

    fun forSlot(slot: GarageSlot): List<GarageColor> = when (slot) {
        GarageSlot.JERSEY, GarageSlot.JERSEY_ACCENT, GarageSlot.HELMET, GarageSlot.FRAME, GarageSlot.FRAME_ACCENT -> KIT
        GarageSlot.BIBS -> BIBS
        GarageSlot.SHOES -> SHOES
        GarageSlot.BAR_TAPE -> BAR_TAPE
    }

    /** Die Farbe mit genau diesem Wert, sonst `null` (dann ist es eine Farbe ausserhalb der Paletten). */
    fun find(srgb: Int): GarageColor? = ALL.firstOrNull { it.srgb == srgb }

    /** Hauttoene, hell nach dunkel. Index 2 ist der Ton des bisherigen Fahrermodells. */
    val SKIN: List<Int> = listOf(0xF1D3BF, 0xE6BC9C, 0xD6A07E, 0xC08861, 0xA26B45, 0x85532F, 0x66402A, 0x4A2E20)

    /** Feste Farbe der Haare (nicht einfaerbbar). */
    const val HAIR = 0x4A3526
}
