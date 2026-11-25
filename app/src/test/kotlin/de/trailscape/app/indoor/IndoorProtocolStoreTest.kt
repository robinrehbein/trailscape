package de.trailscape.app.indoor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IndoorProtocolStoreTest {
    private fun tmp(): File = kotlin.io.path.createTempDirectory("protokolle").toFile().also { it.deleteOnExit() }

    @Test
    fun savesListsReadsDeletes() {
        val store = IndoorProtocolStore(File(tmp(), "diag/fahrprotokolle"))
        assertTrue(store.list().isEmpty())
        val f = assertNotNull(store.save(1_000L, "Test Strecke/ä", "Zeile 1\nZeile 2\n"))
        assertTrue(f.name.startsWith("fahrt-1000-"))
        assertTrue(!f.name.contains('/') && !f.name.contains(' '))
        val entries = store.list()
        assertEquals(1, entries.size)
        assertEquals("Zeile 1\nZeile 2\n", store.read(entries.single()))
        assertTrue(store.delete(entries.single()))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun keepsOnlyNewestFiles() {
        val dir = File(tmp(), "p")
        val store = IndoorProtocolStore(dir, maxFiles = 3)
        for (i in 1..6) {
            val f = assertNotNull(store.save(i * 1000L, "r", "x$i"))
            f.setLastModified(1_000_000L + i * 1000L)
        }
        val names = store.list().map { it.file.name }
        assertEquals(3, names.size)
        assertTrue(names.all { n -> listOf("fahrt-4000", "fahrt-5000", "fahrt-6000").any { n.startsWith(it) } })
    }

    @Test
    fun capsFileSizeAndSurvivesBrokenTarget() {
        val store = IndoorProtocolStore(File(tmp(), "p"))
        val big = "a".repeat(IndoorProtocolStore.MAX_FILE_BYTES + 100)
        val f = assertNotNull(store.save(1L, "r", big))
        assertEquals(IndoorProtocolStore.MAX_FILE_BYTES.toLong(), f.length())
        // ein Datei statt Verzeichnis: kein Absturz, nur null
        val blocker = File(tmp(), "blocked").also { it.writeText("x") }
        assertEquals(null, IndoorProtocolStore(File(blocker, "sub")).save(1L, "r", "x"))
    }
}
