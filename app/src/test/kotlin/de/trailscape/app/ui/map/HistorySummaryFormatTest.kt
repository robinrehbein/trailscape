package de.trailscape.app.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals

/** Einzahl/Mehrzahl der Kachel-Kennzahl im Verlaufsblatt. */
class HistorySummaryFormatTest {
    @Test
    fun `eine Kachel steht in der Einzahl, alles andere in der Mehrzahl`() {
        assertEquals("0 Kacheln", formatTileCount(0))
        assertEquals("1 Kachel", formatTileCount(1))
        assertEquals("37 Kacheln", formatTileCount(37))
    }
}
