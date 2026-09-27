package de.trailscape.app.ui.map

import de.trailscape.app.ui.rides.RidesXmlStrings
import kotlin.test.Test
import kotlin.test.assertEquals

/** Einzahl/Mehrzahl der Kachel-Kennzahl im Verlaufsblatt. */
class HistorySummaryFormatTest {
    private val de = RidesXmlStrings.DE
    private val en = RidesXmlStrings.EN

    @Test
    fun `eine Kachel steht in der Einzahl, alles andere in der Mehrzahl`() {
        assertEquals("0 Kacheln", de.resolve(formatTileCount(0)))
        assertEquals("1 Kachel", de.resolve(formatTileCount(1)))
        assertEquals("37 Kacheln", de.resolve(formatTileCount(37)))
    }

    @Test
    fun `auf Englisch ebenso`() {
        assertEquals("0 tiles", en.resolve(formatTileCount(0)))
        assertEquals("1 tile", en.resolve(formatTileCount(1)))
        assertEquals("37 tiles", en.resolve(formatTileCount(37)))
    }
}
