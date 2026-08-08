package de.trailscape.app.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests des Aufraeumens im Freigabe-Cache (`ui/ShareFiles.kt`).
 *
 * Reiner JVM-Test: Die Aufraeumlogik bekommt das Cache-Verzeichnis als
 * [File] herein und kennt deshalb keinen Android-`Context`.
 */
class ShareFilesTest {

    private lateinit var cacheDir: File

    @BeforeTest
    fun setUp() {
        cacheDir = Files.createTempDirectory("trailscape-share").toFile()
    }

    @AfterTest
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun fileIn(dir: File, name: String, ageMs: Long): File {
        val file = File(dir, name)
        file.writeText("<gpx/>", Charsets.UTF_8)
        file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    @Test
    fun `frische Exporte bleiben liegen`() {
        val dir = prepareShareDirectory(cacheDir)
        val fresh = fileIn(dir, "gerade-geteilt.gpx", ageMs = 5 * 60 * 1000L)

        val deleted = pruneShareDirectory(dir)

        assertEquals(0, deleted)
        assertTrue(fresh.exists())
    }

    @Test
    fun `Exporte aelter als eine Stunde fliegen raus`() {
        val dir = prepareShareDirectory(cacheDir)
        val old = fileIn(dir, "vorgestern.gpx", ageMs = 26 * 60 * 60 * 1000L)
        val fresh = fileIn(dir, "eben.gpx", ageMs = 0L)

        val deleted = pruneShareDirectory(dir)

        assertEquals(1, deleted)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `prepareShareDirectory legt das Verzeichnis an und raeumt dabei auf`() {
        val dir = File(cacheDir, SHARE_DIR_NAME)
        dir.mkdirs()
        val old = fileIn(dir, "alt.gpx", ageMs = 2 * SHARE_FILE_MAX_AGE_MS)

        val prepared = prepareShareDirectory(cacheDir)

        assertTrue(prepared.isDirectory)
        assertEquals(dir.absolutePath, prepared.absolutePath)
        assertFalse(old.exists())
    }

    @Test
    fun `ein nicht existierendes Verzeichnis ist kein Fehler`() {
        assertEquals(0, pruneShareDirectory(File(cacheDir, "gibt-es-nicht")))
    }
}
