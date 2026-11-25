package de.trailscape.core.indoor.climb

import de.trailscape.core.buildBackupJson
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.parseBackupJson
import de.trailscape.core.writeBackupJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Die Bestzeiten an Anstiegen im Backup: additiv, formatstabil, byteidentisch zwischen beiden Schreibwegen. */
class ClimbBackupTest {
    private val book = ClimbBestBook().record(ClimbBest("ride:a", 1_000.0, 2_000.0, 200_000, List(11) { it * 20_000L }, 7L))

    @Test
    fun `Bestzeiten hin und zurueck`() {
        val json = buildBackupJson(emptyList(), null, exportedAtMs = 0, climbBests = book)
        assertTrue(json.contains("\"climbBests\""))
        assertEquals(book, parseBackupJson(json, texts = CoreTextsDe).climbBests)
    }

    @Test
    fun `streamend und am Stueck byteidentisch`() {
        val streamed = StringBuilder()
        writeBackupJson(streamed, emptySequence(), null, exportedAtMs = 5, climbBests = book)
        assertEquals(buildBackupJson(emptyList(), null, exportedAtMs = 5, climbBests = book), streamed.toString())
    }

    @Test
    fun `ohne oder mit leerem Buch bleibt das Backup wie bisher`() {
        val plain = buildBackupJson(emptyList(), null, exportedAtMs = 0)
        assertEquals(plain, buildBackupJson(emptyList(), null, exportedAtMs = 0, climbBests = ClimbBestBook()))
        assertFalse(plain.contains("climbBests"))
        assertTrue(parseBackupJson(plain, texts = CoreTextsDe).climbBests.isEmpty())
    }

    @Test
    fun `kaputtes Feld bricht den Import nicht ab`() {
        val raw = """{"app": "trailscape", "backupVersion": 1, "climbBests": "quatsch", "rides": []}"""
        assertTrue(parseBackupJson(raw, texts = CoreTextsDe).climbBests.isEmpty())
    }
}
