package de.trailscape.app.ui.map

import de.trailscape.core.ExplorerSquare
import de.trailscape.core.ExplorerTile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests der reinen Geometrie/GeoJSON-Erzeugung fuer die „Entdeckt-Kacheln"
 * (`ExplorerTileLayer.kt`). Reiner JVM-Test: Die Datei kennt weder Android
 * noch MapLibre, genau wie `OfflineTileMathTest.kt` nebenan.
 */
class ExplorerTileLayerTest {

    private val gridMax = (1 shl de.trailscape.core.EXPLORER_TILE_ZOOM) - 1

    // ------------------------------------------------------------ fogRectangles

    @Test
    fun `leere Menge ergibt ein Rechteck ueber das ganze Gitter`() {
        val rects = fogRectangles(emptySet())
        assertEquals(listOf(ExplorerTileRect(0, 0, gridMax, gridMax)), rects)
    }

    @Test
    fun `eine entdeckte Kachel ergibt disjunkte Rechtecke, die genau das Gitter minus der Kachel abdecken`() {
        val tile = ExplorerTile(100, 200)
        val rects = fogRectangles(setOf(tile))

        assertNoOverlap(rects)
        assertFalse(rects.any { it.contains(tile) }, "Die entdeckte Kachel darf in keinem Nebel-Rechteck liegen")

        val totalGridArea = (gridMax + 1).toLong() * (gridMax + 1).toLong()
        val fogArea = rects.sumOf { it.area() }
        assertEquals(totalGridArea - 1, fogArea)
    }

    @Test
    fun `ein 3x3-Muster mit Loch - jede unentdeckte Kachel im Begrenzungsrechteck liegt in genau einem Rechteck`() {
        val baseX = 500
        val baseY = 800
        val explored = (baseX..baseX + 2).flatMap { x -> (baseY..baseY + 2).map { y -> ExplorerTile(x, y) } }
            .filterNot { it.x == baseX + 1 && it.y == baseY + 1 }
            .toSet()

        val rects = fogRectangles(explored)
        assertNoOverlap(rects)

        for (x in baseX..baseX + 2) {
            for (y in baseY..baseY + 2) {
                val tile = ExplorerTile(x, y)
                val hits = rects.count { it.contains(tile) }
                if (tile in explored) {
                    assertEquals(0, hits, "Entdeckte Kachel $tile darf nicht im Nebel liegen")
                } else {
                    assertEquals(1, hits, "Unentdeckte Kachel $tile muss in genau einem Rechteck liegen")
                }
            }
        }
    }

    @Test
    fun `die Luecke zwischen zwei entdeckten Kacheln derselben Zeile ist genau ein Rechteck`() {
        val explored = setOf(ExplorerTile(50, 50), ExplorerTile(60, 50))
        val rects = fogRectangles(explored)
        assertNoOverlap(rects)
        assertFalse(rects.any { it.contains(ExplorerTile(50, 50)) })
        assertFalse(rects.any { it.contains(ExplorerTile(60, 50)) })
        assertEquals(1, rects.count { it.contains(ExplorerTile(55, 50)) })
    }

    @Test
    fun `eine Luecke, die ueber mehrere Zeilen dieselbe x-Spanne hat, wird zu einem Rechteck verschmolzen`() {
        // Ring aus entdeckten Kacheln um ein 3x3-Loch: Die drei Zeilen des
        // Lochs haben alle dieselbe x-Spanne — muessen also zu EINEM
        // Rechteck verschmelzen statt zu drei einzelnen.
        val baseX = 200
        val baseY = 300
        val ringTiles = (baseX - 1..baseX + 3).flatMap { x -> (baseY - 1..baseY + 3).map { y -> ExplorerTile(x, y) } }
            .filterNot { it.x in baseX..baseX + 2 && it.y in baseY..baseY + 2 }
            .toSet()

        val rects = fogRectangles(ringTiles)
        val hole = rects.singleOrNull {
            it.minX == baseX && it.maxX == baseX + 2 && it.minY == baseY && it.maxY == baseY + 2
        }
        assertTrue(hole != null, "Erwartet ein einziges verschmolzenes 3x3-Lochrechteck, war: $rects")
    }

    private fun ExplorerTileRect.contains(tile: ExplorerTile): Boolean =
        tile.x in minX..maxX && tile.y in minY..maxY

    private fun ExplorerTileRect.area(): Long = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()

    private fun assertNoOverlap(rects: List<ExplorerTileRect>) {
        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val overlapsX = a.minX <= b.maxX && b.minX <= a.maxX
                val overlapsY = a.minY <= b.maxY && b.minY <= a.maxY
                assertFalse(overlapsX && overlapsY, "Rechtecke ueberlappen: $a und $b")
            }
        }
    }

    // ------------------------------------------------------------------ GeoJSON

    private fun parseFeatureCollection(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject

    private fun ring(feature: JsonObject): JsonArray =
        feature.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray[0].jsonArray

    @Test
    fun `fogFeatureCollection liefert gueltiges GeoJSON mit geschlossenen Ringen`() {
        val explored = setOf(ExplorerTile(10, 10))
        val parsed = parseFeatureCollection(fogFeatureCollection(explored))

        assertEquals("FeatureCollection", parsed["type"]!!.jsonPrimitive.content)
        val features = parsed["features"]!!.jsonArray
        assertTrue(features.isNotEmpty())

        for (feature in features) {
            val obj = feature.jsonObject
            assertEquals("Feature", obj["type"]!!.jsonPrimitive.content)
            val geometry = obj["geometry"]!!.jsonObject
            assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)
            val outerRing = ring(obj)
            assertTrue(outerRing.size >= 4, "Ein Ring braucht mindestens vier Punkte")
            assertEquals(outerRing.first(), outerRing.last(), "Der Ring muss geschlossen sein")
        }
    }

    @Test
    fun `fogFeatureCollection einer leeren Menge liefert genau ein Feature ueber das ganze Gitter`() {
        val parsed = parseFeatureCollection(fogFeatureCollection(emptySet()))
        assertEquals(1, parsed["features"]!!.jsonArray.size)
    }

    @Test
    fun `exploredOutlineFeatureCollection hat genau ein Feature je entdeckter Kachel`() {
        val explored = setOf(ExplorerTile(1, 1), ExplorerTile(2, 2), ExplorerTile(3, 3))
        val parsed = parseFeatureCollection(exploredOutlineFeatureCollection(explored))
        assertEquals(explored.size, parsed["features"]!!.jsonArray.size)

        val empty = parseFeatureCollection(exploredOutlineFeatureCollection(emptySet()))
        assertEquals(0, empty["features"]!!.jsonArray.size)
    }

    @Test
    fun `maxSquareFeatureCollection ist leer bei null und hat genau ein Feature sonst`() {
        val empty = parseFeatureCollection(maxSquareFeatureCollection(null))
        assertEquals(0, empty["features"]!!.jsonArray.size)

        val parsed = parseFeatureCollection(maxSquareFeatureCollection(ExplorerSquare(x = 10, y = 20, size = 3)))
        val features = parsed["features"]!!.jsonArray
        assertEquals(1, features.size)
        val outerRing = ring(features[0].jsonObject)
        assertEquals(outerRing.first(), outerRing.last())
    }
}
