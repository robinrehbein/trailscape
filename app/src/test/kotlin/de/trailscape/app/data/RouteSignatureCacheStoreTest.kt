package de.trailscape.app.data

import de.trailscape.core.RouteSignature
import de.trailscape.core.StoredRouteSignature
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [RouteSignatureCacheStore] — die dateigestuetzte Ablage der
 * Strecken-Signaturen je Tour (`rides/route-signatures.json`). Gleicher
 * Zuschnitt wie `ExplorerTilesCacheStoreTest` nebenan: Roundtrip, Aufraeumen,
 * kaputte Datei, kaputter Einzeleintrag.
 */
class RouteSignatureCacheStoreTest {

    private val dir: File = createTempDirectory("route-signatures-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun entry(vararg cells: Long): StoredRouteSignature = StoredRouteSignature(
        updatedAt = 1700000000000L,
        pointCount = 720,
        signature = RouteSignature(
            cells = cells.toSet(),
            distanceKm = 32.4,
            startCell = cells.first(),
            endCell = cells.last(),
        ),
    )

    @Test
    fun `Eintraege ueberleben flush und Neuinstanziierung`() {
        val store = RouteSignatureCacheStore(dir)
        val big = (137_000L shl 32) or 91_000L
        val entry = entry(big, big + 1, big + (1L shl 32))
        store.write("a", entry)
        store.flush()
        assertTrue(File(dir, RouteSignatureCacheStore.FILE_NAME).exists())

        val roundtripped = assertNotNull(RouteSignatureCacheStore(dir).read("a"))
        assertEquals(entry, roundtripped)
    }

    @Test
    fun `eine Tour ohne Signatur bleibt eine Tour ohne Signatur`() {
        // Sonst wuerde eine zu kurze Tour als „nie gerechnet" gelten und bei
        // jedem Lauf erneut geladen.
        val store = RouteSignatureCacheStore(dir)
        store.write("kurz", StoredRouteSignature(updatedAt = 1L, pointCount = 12, signature = null))
        store.flush()

        val roundtripped = assertNotNull(RouteSignatureCacheStore(dir).read("kurz"))
        assertNull(roundtripped.signature)
        assertEquals(12, roundtripped.pointCount)
    }

    @Test
    fun `retainAll und remove werfen Eintraege geloeschter Touren weg`() {
        val store = RouteSignatureCacheStore(dir)
        store.write("a", entry(1, 2))
        store.write("b", entry(3, 4))
        store.write("c", entry(5, 6))
        store.retainAll(setOf("a", "c"))
        store.remove("c")
        store.flush()

        val reloaded = RouteSignatureCacheStore(dir)
        assertNotNull(reloaded.read("a"))
        assertNull(reloaded.read("b"))
        assertNull(reloaded.read("c"))
    }

    @Test
    fun `eine kaputte Cache-Datei liest sich als leerer Cache`() {
        File(dir, RouteSignatureCacheStore.FILE_NAME).writeText("kein json", Charsets.UTF_8)
        val store = RouteSignatureCacheStore(dir)
        assertNull(store.read("a"))
        // Und laesst sich danach normal beschreiben.
        store.write("a", entry(7, 8))
        store.flush()
        assertNotNull(RouteSignatureCacheStore(dir).read("a"))
    }

    @Test
    fun `ein Eintrag mit schiefer Zellliste wird verworfen, der Rest bleibt`() {
        val store = RouteSignatureCacheStore(dir)
        store.write("gut", entry(9, 10))
        store.flush()
        val file = File(dir, RouteSignatureCacheStore.FILE_NAME)
        file.writeText(
            file.readText(Charsets.UTF_8).replace(
                """{"id":"gut"""",
                """{"id":"kaputt","updatedAt":1,"pointCount":2,""" +
                    """"signature":{"distanceKm":3.0,"startCell":1,"endCell":1,"cells":["x"]}},{"id":"gut"""",
            ),
            Charsets.UTF_8,
        )

        val reloaded = RouteSignatureCacheStore(dir)
        assertNull(reloaded.read("kaputt"))
        assertEquals(setOf(9L, 10L), assertNotNull(reloaded.read("gut")).signature?.cells)
    }
}
