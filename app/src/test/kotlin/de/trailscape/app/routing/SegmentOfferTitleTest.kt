package de.trailscape.app.routing

import de.trailscape.app.ui.map.MapTestStrings.de
import de.trailscape.app.ui.map.MapTestStrings.en
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import de.trailscape.core.parseSegmentTile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Der Titel eines Kartendaten-Angebots wird erst beim Anzeigen aufgeloest —
 * so folgt er einem Sprachwechsel, obwohl das Angebot im ViewModel liegt.
 */
class SegmentOfferTitleTest {

    private val tiles = listOf("E5_N50", "E10_N50", "E10_N45", "E15_N50").map { checkNotNull(parseSegmentTile(it)) }

    @Test
    fun `eine Kachel steht allein`() {
        val one = tiles.take(1)
        assertEquals(tiles[0].title(CoreTextsDe), de(segmentOfferTitle(one, CoreTextsDe)))
        assertEquals(tiles[0].title(CoreTextsEn), en(segmentOfferTitle(one, CoreTextsEn)))
    }

    @Test
    fun `zwei Kacheln werden verbunden`() {
        val two = tiles.take(2)
        assertEquals(
            "${tiles[0].title(CoreTextsDe)} und ${tiles[1].title(CoreTextsDe)}",
            de(segmentOfferTitle(two, CoreTextsDe)),
        )
        assertEquals(
            "${tiles[0].title(CoreTextsEn)} and ${tiles[1].title(CoreTextsEn)}",
            en(segmentOfferTitle(two, CoreTextsEn)),
        )
    }

    @Test
    fun `weitere Kacheln werden gezaehlt`() {
        assertEquals(
            "${tiles[0].title(CoreTextsDe)} und ${tiles[1].title(CoreTextsDe)} (+ 2 weitere)",
            de(segmentOfferTitle(tiles, CoreTextsDe)),
        )
        assertEquals(
            "${tiles[0].title(CoreTextsEn)} and ${tiles[1].title(CoreTextsEn)} (+ 2 more)",
            en(segmentOfferTitle(tiles, CoreTextsEn)),
        )
    }
}
