package de.trailscape.app

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import de.trailscape.app.ui.MAX_ACTIVITY_FILE_BYTES
import de.trailscape.app.ui.MAX_IMPORT_FILES
import de.trailscape.app.ui.TOO_MANY_FILES_MESSAGE
import de.trailscape.app.ui.readActivityFiles
import de.trailscape.app.ui.readBounded
import de.trailscape.core.FILE_TOO_LARGE_MESSAGE
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

        assertEquals(listOf(ImportSource(gpx, "application/gpx+xml")), importSourcesFromIntent(intent, OWN))
    }

    @Test
    fun `SEND liest EXTRA_STREAM und ignoriert dieselbe URI in clipData`() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.ant.fit")
            .putExtra(Intent.EXTRA_STREAM, fit)
        intent.clipData = ClipData.newRawUri("", fit)

        assertEquals(listOf(ImportSource(fit, "application/vnd.ant.fit")), importSourcesFromIntent(intent, OWN))
    }

    @Test
    fun `SEND nur mit clipData wird trotzdem gelesen`() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
        intent.clipData = ClipData.newRawUri("", gpx)

        assertEquals(listOf(ImportSource(gpx, "application/octet-stream")), importSourcesFromIntent(intent, OWN))
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

        val sources = importSourcesFromIntent(intent, OWN)

        assertEquals(listOf(gpx, fit, third), sources.map { it.uri })
        // `*/*` ist kein Typ, nur der gemeinsame Nenner — nicht weiterreichen.
        assertTrue(sources.all { it.mimeType == null })
    }

    @Test
    fun `Weblinks, fremde Aktionen und leere Intents ergeben nichts`() {
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.komoot.com/tour/123"))
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Schau mal")
        val main = Intent(Intent.ACTION_MAIN)

        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(link, OWN))
        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(text, OWN))
        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(main, OWN))
        assertEquals(emptyList<ImportSource>(), importSourcesFromIntent(null, OWN))
    }

    @Test
    fun `file-URIs und eigene Anbieter werden abgewiesen`() {
        val privateFile = Uri.parse("file:///data/data/$OWN/files/rides/1.json")
        val ownProvider = Uri.parse("content://$OWN.fileprovider/exports/tour.gpx")
        val ownProviderOtherUser = Uri.parse("content://10@$OWN.fileprovider/exports/tour.gpx")
        val ownPackageAuthority = Uri.parse("content://$OWN/x.gpx")

        for (uri in listOf(privateFile, ownProvider, ownProviderOtherUser, ownPackageAuthority)) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/gpx+xml")
            val send = Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, uri)
            assertEquals(uri.toString(), emptyList<ImportSource>(), importSourcesFromIntent(view, OWN))
            assertEquals(uri.toString(), emptyList<ImportSource>(), importSourcesFromIntent(send, OWN))
        }

        // Ein fremder Anbieter, dessen Name nur aehnlich beginnt, bleibt erlaubt.
        val lookalike = Uri.parse("content://${OWN}x.files/tour.gpx")
        assertEquals(
            listOf(lookalike),
            importSourcesFromIntent(Intent(Intent.ACTION_VIEW, lookalike), OWN).map { it.uri },
        )
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

    @Test
    fun `readBounded bricht bei zu grossen Streams ab`() {
        val data = ByteArray(100_000) { 1 }

        assertEquals(100_000, readBounded(java.io.ByteArrayInputStream(data), 100_000L)?.size)
        assertEquals(null, readBounded(java.io.ByteArrayInputStream(data), 99_999L))
    }

    @Test
    fun `zu grosse Datei und zu viele Dateien werden unlesbar statt gelesen`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val dir = java.io.File(context.cacheDir, "import-limit").apply { mkdirs() }
        val huge = java.io.File(dir, "video.gpx").apply {
            outputStream().use { out ->
                val chunk = ByteArray(1024 * 1024)
                repeat((MAX_ACTIVITY_FILE_BYTES / chunk.size).toInt() + 1) { out.write(chunk) }
            }
        }
        val small = java.io.File(dir, "klein.gpx").apply { writeText("<gpx></gpx>") }
        val uris = listOf(Uri.fromFile(huge)) + List(MAX_IMPORT_FILES) { Uri.fromFile(small) }

        val files = kotlinx.coroutines.runBlocking { readActivityFiles(context, uris) }

        assertEquals(FILE_TOO_LARGE_MESSAGE, files[0].readError)
        assertEquals(0, files[0].bytes.size)
        assertEquals(null, files[1].readError)
        // Die 201. Datei wird nicht mehr gelesen.
        assertEquals(TOO_MANY_FILES_MESSAGE, files.last().readError)
        assertEquals(MAX_IMPORT_FILES - 1, files.count { it.readError == null })
        huge.delete()
    }

    private companion object {
        const val OWN = "io.github.robinrehbein.trailscape"
    }
}
