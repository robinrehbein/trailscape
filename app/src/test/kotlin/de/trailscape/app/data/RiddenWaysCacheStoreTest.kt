package de.trailscape.app.data

import de.trailscape.core.StoredRiddenWays
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [RiddenWaysCacheStore] — die Binaerdatei der gefahrenen Wege je
 * Tour (`route-cache/ridden-ways.bin`). Zuschnitt wie
 * `RouteSignatureCacheStoreTest`: Roundtrip, Aufraeumen, kaputte Datei, nur
 * bei Aenderungen schreiben.
 */
class RiddenWaysCacheStoreTest {

    private val dir: File = createTempDirectory("ridden-ways-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Zellen in Fahrtreihenfolge, wie `riddenWayCells` sie liefert (Spalte/Zeile gepackt). */
    private fun entry(vararg colRow: Pair<Int, Int>) = StoredRiddenWays(
        updatedAt = 1_700_000_000_000L,
        pointCount = 3600,
        cells = colRow.map { (c, r) -> (c.toLong() shl 32) or (r.toLong() and 0xffffffffL) }.toLongArray(),
    )

    @Test
    fun `Eintraege ueberleben flush und Neuinstanziierung`() {
        val store = RiddenWaysCacheStore(dir)
        val a = entry(33_500 to 269_000, 33_501 to 269_000, 33_501 to 269_001)
        val south = entry(-410_000 to -188_000, -410_001 to -187_999)
        store.put("a", a)
        store.put("sued", south)
        store.flush()
        assertTrue(File(dir, RiddenWaysCacheStore.FILE_NAME).exists())

        val reloaded = RiddenWaysCacheStore(dir)
        assertEquals(a, reloaded.get("a"))
        assertEquals(south, reloaded.get("sued"))
    }

    @Test
    fun `retainAll wirft geloeschte Touren weg`() {
        val store = RiddenWaysCacheStore(dir)
        store.put("a", entry(1 to 1))
        store.put("b", entry(2 to 2))
        store.flush()
        val second = RiddenWaysCacheStore(dir)
        second.retainAll(setOf("b"))
        second.flush()
        val third = RiddenWaysCacheStore(dir)
        assertNull(third.get("a"))
        assertNotNull(third.get("b"))
    }

    @Test
    fun `kaputte Datei gilt als leerer Cache und wird ersetzt`() {
        File(dir, RiddenWaysCacheStore.FILE_NAME).writeText("kein Cache", Charsets.UTF_8)
        val store = RiddenWaysCacheStore(dir)
        assertNull(store.get("a"))
        store.put("a", entry(5 to 5))
        store.flush()
        assertNotNull(RiddenWaysCacheStore(dir).get("a"))
    }

    @Test
    fun `ohne Aenderung wird nicht geschrieben`() {
        val store = RiddenWaysCacheStore(dir)
        store.put("a", entry(1 to 1))
        store.flush()
        val file = File(dir, RiddenWaysCacheStore.FILE_NAME)
        file.setLastModified(1_000_000L)
        store.get("a")
        store.flush()
        assertEquals(1_000_000L, file.lastModified())
        assertFalse(File(dir, "${RiddenWaysCacheStore.FILE_NAME}.tmp").exists())
    }
}
