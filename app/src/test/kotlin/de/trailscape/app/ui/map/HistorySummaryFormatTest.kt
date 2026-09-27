package de.trailscape.app.ui.map

import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.ui.rides.RidesXmlStrings
import de.trailscape.core.i18n.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/** Einzahl/Mehrzahl der Kachel-Kennzahl und ganze Kilometer im Verlaufsblatt. */
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

    @Test
    fun `Kilometer werden in beiden Sprachen auf ganze Zahlen abgeschnitten`() {
        val deFormats = AppFormats(AppLanguage.DE)
        val enFormats = AppFormats(AppLanguage.EN)
        assertEquals("2345", summaryWholeKm(2345.6, deFormats))
        assertEquals("2345", summaryWholeKm(2345.6, enFormats))
        // Erst auf eine Stelle gerundet: 9,96 → „10,0" → „10".
        assertEquals("10", summaryWholeKm(9.96, deFormats))
        assertEquals("10", summaryWholeKm(9.96, enFormats))
        assertEquals("0", summaryWholeKm(0.0, enFormats))
        // Ab 1000 km kein Tausendertrenner, der den Schnitt verfaelschen koennte.
        assertEquals("12345", summaryWholeKm(12345.4, enFormats))
    }
}
