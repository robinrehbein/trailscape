package de.trailscape.app.data

import de.trailscape.core.ROUTE_SIGNATURE_CACHE_VERSION
import de.trailscape.core.RouteSignature
import de.trailscape.core.StoredRouteSignature
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [RouteSignatureCacheStore] — die dateigestuetzte Ablage der
 * Strecken-Signaturen je Tour (`route-cache/route-signatures.json`). Gleicher
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
            cells = cells.sortedArray(),
            distanceKm = 32.4,
            startCell = cells.min(),
            endCell = cells.max(),
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
    fun `ein Eintrag mit unsortierter Zellliste wird verworfen, der Rest bleibt`() {
        val file = File(dir, RouteSignatureCacheStore.FILE_NAME)
        file.writeText(
            """{"version":$ROUTE_SIGNATURE_CACHE_VERSION,"entries":[""" +
                """{"id":"kaputt","updatedAt":1,"pointCount":2,""" +
                """"signature":{"distanceKm":3.0,"startCell":1,"endCell":1,"cells":[7,3]}},""" +
                """{"id":"gut","updatedAt":1,"pointCount":2,""" +
                """"signature":{"distanceKm":3.0,"startCell":9,"endCell":10,"cells":[9,10]}}]}""",
            Charsets.UTF_8,
        )

        val reloaded = RouteSignatureCacheStore(dir)
        assertNull(reloaded.read("kaputt"))
        assertContentEquals(longArrayOf(9, 10), assertNotNull(reloaded.read("gut")).signature?.cells)
    }

    @Test
    fun `die Datei traegt die Formatversion`() {
        val store = RouteSignatureCacheStore(dir)
        store.write("a", entry(1, 2))
        store.flush()
        val text = File(dir, RouteSignatureCacheStore.FILE_NAME).readText(Charsets.UTF_8)
        assertTrue(""""version":$ROUTE_SIGNATURE_CACHE_VERSION""" in text, text)
    }

    @Test
    fun `eine Datei mit alter oder fehlender Version gilt als leer und wird ersetzt`() {
        val file = File(dir, RouteSignatureCacheStore.FILE_NAME)
        val old = """{"id":"a","updatedAt":1,"pointCount":2,""" +
            """"signature":{"distanceKm":3.0,"startCell":1,"endCell":2,"cells":[1,2]}}"""
        for (header in listOf("""{"version":1,""", """{""")) {
            file.writeText("""$header"entries":[$old]}""", Charsets.UTF_8)
            val store = RouteSignatureCacheStore(dir)
            assertNull(store.read("a"), header)
            store.flush()
            // Die Altlast ist ersetzt: Eine neue Instanz sieht eine gueltige, leere Datei.
            assertTrue(""""version":$ROUTE_SIGNATURE_CACHE_VERSION""" in file.readText(Charsets.UTF_8))
            assertNull(RouteSignatureCacheStore(dir).read("a"))
        }
    }

    @Test
    fun `flush ohne Aenderung schreibt nicht`() {
        val store = RouteSignatureCacheStore(dir)
        store.write("a", entry(1, 2))
        store.flush()
        val file = File(dir, RouteSignatureCacheStore.FILE_NAME)
        // Marker: Wuerde flush neu schreiben, verschwaende er.
        file.setLastModified(1_000_000L)
        val before = file.lastModified()

        assertNotNull(store.read("a"))
        store.retainAll(setOf("a"))
        store.remove("gibts-nicht")
        store.flush()
        assertEquals(before, file.lastModified())
        assertFalse(File(dir, "${RouteSignatureCacheStore.FILE_NAME}.tmp").exists())
    }

    @Test
    fun `nach flush liegt nichts mehr im Speicher - die naechste Benutzung liest die Datei neu`() {
        val store = RouteSignatureCacheStore(dir)
        store.write("a", entry(1, 2))
        store.flush()
        // Von aussen ausgetauscht: Haette der Store seine Eintraege behalten, saehe er weiter „a".
        File(dir, RouteSignatureCacheStore.FILE_NAME).writeText(
            """{"version":$ROUTE_SIGNATURE_CACHE_VERSION,"entries":[]}""",
            Charsets.UTF_8,
        )
        assertNull(store.read("a"))
        // Auch nach reiner Lesebenutzung wird freigegeben.
        File(dir, RouteSignatureCacheStore.FILE_NAME).writeText(
            """{"version":$ROUTE_SIGNATURE_CACHE_VERSION,"entries":[""" +
                """{"id":"b","updatedAt":1,"pointCount":2,"signature":null}]}""",
            Charsets.UTF_8,
        )
        store.flush()
        assertNotNull(store.read("b"))
    }
}
