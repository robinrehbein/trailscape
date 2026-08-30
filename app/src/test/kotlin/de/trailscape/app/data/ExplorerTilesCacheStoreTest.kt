package de.trailscape.app.data

import de.trailscape.core.ExplorerTile
import de.trailscape.core.StoredExplorerTiles
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [ExplorerTilesCacheStore] — die dateigestuetzte Ablage der
 * Entdeckt-Kacheln je Tour (`rides/explorer-tiles.json`). Gleicher Zuschnitt
 * wie `RideLoadCacheStoreTest` nebenan: Roundtrip, Aufraeumen, kaputte Datei.
 */
class ExplorerTilesCacheStoreTest {

    private val dir: File = createTempDirectory("explorer-tiles-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun entry(vararg tiles: Pair<Int, Int>): StoredExplorerTiles = StoredExplorerTiles(
        updatedAt = 1700000000000L,
        pointCount = 720,
        tiles = tiles.map { (x, y) -> ExplorerTile(x = x, y = y) },
    )

    @Test
    fun `Eintraege ueberleben flush und Neuinstanziierung`() {
        val store = ExplorerTilesCacheStore(dir)
        val entry = entry(8712 to 5623, 8713 to 5623, 8713 to 5624)
        store.put("a", entry)
        store.flush()
        assertTrue(File(dir, ExplorerTilesCacheStore.FILE_NAME).exists())

        val reloaded = ExplorerTilesCacheStore(dir)
        val roundtripped = assertNotNull(reloaded.get("a"))
        assertEquals(entry.updatedAt, roundtripped.updatedAt)
        assertEquals(entry.pointCount, roundtripped.pointCount)
        assertEquals(entry.tiles, roundtripped.tiles)
    }

    @Test
    fun `eine Tour ohne Kacheln bleibt eine Tour ohne Kacheln`() {
        // Wichtig, weil ein leerer Eintrag genau der Fall ist, den ein
        // „irgendwas fehlt, ueberspringen" faelschlich als „nie gerechnet"
        // deuten wuerde — die Tour liefe dann bei jedem Start neu durch.
        val store = ExplorerTilesCacheStore(dir)
        store.put("leer", entry())
        store.flush()

        val roundtripped = assertNotNull(ExplorerTilesCacheStore(dir).get("leer"))
        assertTrue(roundtripped.tiles.isEmpty())
    }

    @Test
    fun `retainAll wirft Eintraege geloeschter Touren weg`() {
        val store = ExplorerTilesCacheStore(dir)
        store.put("a", entry(1 to 1))
        store.put("b", entry(2 to 2))
        store.retainAll(setOf("a"))
        store.flush()

        val reloaded = ExplorerTilesCacheStore(dir)
        assertNotNull(reloaded.get("a"))
        assertNull(reloaded.get("b"))
    }

    @Test
    fun `eine kaputte Cache-Datei liest sich als leerer Cache`() {
        File(dir, ExplorerTilesCacheStore.FILE_NAME).writeText("kein json", Charsets.UTF_8)
        val store = ExplorerTilesCacheStore(dir)
        assertNull(store.get("a"))
        // Und laesst sich danach normal beschreiben.
        store.put("a", entry(3 to 4))
        store.flush()
        assertNotNull(ExplorerTilesCacheStore(dir).get("a"))
    }

    @Test
    fun `ein Eintrag mit schiefer Kachelliste wird verworfen, der Rest bleibt`() {
        val store = ExplorerTilesCacheStore(dir)
        store.put("gut", entry(5 to 6))
        store.flush()
        val file = File(dir, ExplorerTilesCacheStore.FILE_NAME)
        file.writeText(
            file.readText(Charsets.UTF_8).replace(
                """{"id":"gut"""",
                """{"id":"kaputt","tiles":[[7]]},{"id":"gut"""",
            ),
            Charsets.UTF_8,
        )

        val reloaded = ExplorerTilesCacheStore(dir)
        assertNull(reloaded.get("kaputt"))
        assertEquals(listOf(ExplorerTile(5, 6)), assertNotNull(reloaded.get("gut")).tiles)
    }
}
