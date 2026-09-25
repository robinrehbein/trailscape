package de.trailscape.app

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Auslesen der Datei-URIs aus „Teilen"/„Oeffnen mit"-Intents
 * ([importSourcesFromIntent]) und die Uebergabe an die MainActivity
 * ([PendingImports]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ImportIntentsTest {

    private val gpx = Uri.parse("content://downloads/public/1/Albtrauf.gpx")
    private val fit = Uri.parse("content://media/external/file/2")

    @Test
    fun `VIEW liefert die Daten-URI samt Typ`() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(gpx, "application/gpx+xml")

        assertEquals(listOf(ImportSource(gpx, "application/gpx+xml")), importSourcesFromIntent(intent))
    }

    @Test
    fun `SEND liest EXTRA_STREAM und ignoriert dieselbe URI in clipData`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.ant.fit")
            .putExtra(Intent.EXTRA_STREAM, fit)
        intent.clipData = ClipData.newRawUri("", fit)

        assertEquals(listOf(ImportSource(fit, "application/vnd.ant.fit")), importSourcesFromIntent(intent))
    }

    @Test
    fun `SEND nur mit clipData wird trotzdem gelesen`() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        intent.clipData = ClipData.newRawUri("", gpx)

        assertEquals(listOf(ImportSource(gpx, "application/octet-stream")), importSourcesFromIntent(intent))
    }

    @Test
    fun `SEND_MULTIPLE liefert alle URIs in Reihenfolge ohne Doppelte`() {
        val third = Uri.parse("content://com.example.files/3.gpx")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(gpx, fit))
        intent.clipData = ClipData.newRawUri("", gpx).apply {
            addItem(ClipData.Item(fit))
            addItem(ClipData.Item(third))
        }

        val sources = importSourcesFromIntent(intent)

        assertEquals(listOf(gpx, fit, third), sources.map { it.uri })
        // `*/*` ist kein Typ, nur der gemeinsame Nenner — nicht weiterreichen.
        assertTrue(sources.all { it.mimeType == null })
    }

    @Test
    fun `Weblinks, fremde Aktionen und leere Intents ergeben nichts`() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.komoot.com/tour/123"))
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Schau mal")
        val main = Intent(Intent.ACTION_MAIN)

        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(link))
        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(text))
        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(main))
        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(null))
    }

    @Test
    fun `PendingImports gibt einen Stapel genau einmal heraus`() {
        PendingImports.take() // Reste anderer Tests
        val file = de.trailscape.core.ActivityFileInput("a.gpx", null, ByteArray(1))
        PendingImports.offer(listOf(file))

        assertEquals(listOf(file), PendingImports.take())
        // Zweiter Zugriff (z. B. nach Drehung der MainActivity): leer.
        assertEquals(emptyList<Any>(), PendingImports.take())
    }

    @Test
    fun `readActivityFiles liest sofort ein und meldet Unlesbares statt abzubrechen`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = java.io.File(context.cacheDir, "import-test").apply { mkdirs() }
        val good = java.io.File(dir, "Runde.gpx").apply { writeText("<gpx></gpx>") }
        val missing = java.io.File(dir, "weg.gpx")

        val files = kotlinx.coroutines.runBlocking {
            de.trailscape.app.ui.readActivityFiles(context, listOf(Uri.fromFile(good), Uri.fromFile(missing)))
        }

        assertEquals(listOf("Runde.gpx", "weg.gpx"), files.map { it.displayName })
        assertEquals("<gpx></gpx>", files[0].bytes.decodeToString())
        assertEquals(null, files[0].readError)
        assertTrue(files[1].readError != null)
    }
}
