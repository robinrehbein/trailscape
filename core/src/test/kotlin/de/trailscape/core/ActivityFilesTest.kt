package de.trailscape.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Einzeldatei-Erkennung und des Sammelimports (`ActivityFiles.kt`)
 * — der Kern hinter „Teilen an Trailscape" und der Mehrfachauswahl.
 */
class ActivityFilesTest {

    private fun gpxBytes(name: String, startMs: Long?, count: Int = 3): ByteArray {
        val points = (0 until count).map {
            TrackPoint(
                lat = 47.0 + it * 0.001,
                lon = 11.0 + it * 0.001,
                ele = 600.0 + it,
                time = startMs?.let { s -> s + it * 10_000L },
            )
        }
        return buildGpx(name, points).toByteArray(Charsets.UTF_8)
    }

    /** Minimal-FIT: nur Header, reicht fuer die Magic-Byte-Pruefung. */
    private fun fitHeaderOnly(): ByteArray {
        val header = ByteArray(14)
        header[0] = 14
        ".FIT".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        return header
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun file(name: String?, bytes: ByteArray, mime: String? = null) =
        ActivityFileInput(displayName = name, mimeType = mime, bytes = bytes)

    // -----------------------------------------------------------------------
    // Magic Bytes
    // -----------------------------------------------------------------------

    @Test
    fun `erkennt GPX und FIT am Inhalt auch gepackt`() {
        assertEquals(ActivityFileKind.GPX, sniffActivityFileKind(gpxBytes("A", 1_000L)))
        assertEquals(ActivityFileKind.GPX, sniffActivityFileKind(gzip(gpxBytes("A", 1_000L))))
        assertEquals(ActivityFileKind.FIT, sniffActivityFileKind(fitHeaderOnly()))
        assertEquals(ActivityFileKind.FIT, sniffActivityFileKind(gzip(fitHeaderOnly())))
    }

    @Test
    fun `GPX mit BOM und Kommentar vor dem Wurzelelement wird erkannt`() {
        val xml = "﻿\n<?xml version=\"1.0\"?>\n<!-- Export von irgendwo -->\n<gpx version=\"1.1\"></gpx>"
        assertEquals(ActivityFileKind.GPX, sniffActivityFileKind(xml.toByteArray()))
    }

    @Test
    fun `fremde Inhalte werden nicht erkannt`() {
        assertNull(sniffActivityFileKind("<html><body>gpx</body></html>".toByteArray()))
        assertNull(sniffActivityFileKind(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertNull(sniffActivityFileKind(ByteArray(0)))
        // ".FIT" an der richtigen Stelle, aber unmoegliche Header-Laenge.
        val fakeFit = fitHeaderOnly().also { it[0] = 99 }
        assertNull(sniffActivityFileKind(fakeFit))
    }

    // -----------------------------------------------------------------------
    // Endung UND Inhalt
    // -----------------------------------------------------------------------

    @Test
    fun `Endung muss zum Inhalt passen`() {
        val error = assertFailsWith<FormatException> {
            classifyActivityFile(file("tour.fit", gpxBytes("A", 1_000L)))
        }
        assertTrue(error.message!!.contains("Dateiendung"))
        assertEquals(ActivityFileKind.GPX, classifyActivityFile(file("tour.GPX.gz", gzip(gpxBytes("A", 1_000L)))))
    }

    @Test
    fun `octet-stream mit fremder Endung wird abgewiesen, ohne Namen entscheidet der Inhalt`() {
        assertFailsWith<FormatException> {
            classifyActivityFile(file("foto.jpg", fitHeaderOnly(), mime = "application/octet-stream"))
        }
        assertEquals(
            ActivityFileKind.FIT,
            classifyActivityFile(file("export.bin", fitHeaderOnly(), mime = "application/vnd.ant.fit")),
        )
        assertEquals(ActivityFileKind.GPX, classifyActivityFile(file(null, gpxBytes("A", 1_000L))))
        assertEquals(ActivityFileKind.GPX, classifyActivityFile(file("anhang", gpxBytes("A", 1_000L))))
    }

    @Test
    fun `Datei ohne GPX- oder FIT-Inhalt wird mit deutscher Meldung abgewiesen`() {
        val error = assertFailsWith<FormatException> {
            classifyActivityFile(file("tour.gpx", "hallo".toByteArray()))
        }
        assertEquals(NOT_AN_ACTIVITY_FILE_MESSAGE, error.message)
    }

    // -----------------------------------------------------------------------
    // GPX ohne Zeitstempel
    // -----------------------------------------------------------------------

    @Test
    fun `GPX ohne time wird als Planung importiert, mit time als Fahrt`() {
        val route = rideFromActivityFile(file("Komoot-Route.gpx", gpxBytes("Albtrauf", startMs = null)))
        assertTrue(route.planned)
        assertEquals("Albtrauf", route.name)

        val ride = rideFromActivityFile(file("fahrt.gpx", gpxBytes("Feierabend", startMs = 1_700_000_000_000L)))
        assertFalse(ride.planned)
        assertEquals(1_700_000_000_000L, ride.createdAt)
    }

    // -----------------------------------------------------------------------
    // Sammelimport
    // -----------------------------------------------------------------------

    @Test
    fun `Sammelimport zaehlt Importe, Duplikate und defekte Dateien`() {
        val existing = listOf(
            rideFromGpx(gpxBytes("Bestand", 1_600_000_000_000L).decodeToString(), "x", id = "alt").toSummary(),
        )
        val files = listOf(
            file("neu-1.gpx", gpxBytes("Neu 1", 1_700_000_000_000L)),
            file("bestand.gpx", gpxBytes("Bestand", 1_600_000_000_000L)),
            file("neu-2.gpx.gz", gzip(gpxBytes("Neu 2", 1_700_100_000_000L))),
            // Dieselbe Datei zweimal markiert: nur einmal importieren.
            file("neu-2-kopie.gpx", gpxBytes("Neu 2", 1_700_100_000_000L)),
            file("kaputt.gpx", "<gpx><trk>".toByteArray()),
            file("cloud.gpx", ByteArray(0)).let {
                ActivityFileInput(it.displayName, null, ByteArray(0), readError = "Nicht lokal verfügbar.")
            },
        )

        val result = importActivityFiles(files, existing)

        assertEquals(listOf("Neu 1", "Neu 2"), result.rides.map { it.name })
        assertEquals(listOf("bestand.gpx", "neu-2-kopie.gpx"), result.duplicates)
        assertEquals(listOf("kaputt.gpx", "cloud.gpx"), result.errors.map { it.path })
        assertEquals("Nicht lokal verfügbar.", result.errors[1].message)
        assertEquals(2, result.rides.map { it.id }.distinct().size)
        assertEquals("2 importiert · 2 schon vorhanden · 2 unlesbar", bulkImportMessage(result))
        assertNull(bulkImportFailureText(result))
    }

    @Test
    fun `dieselbe Planung zweimal importiert gilt als Duplikat`() {
        val first = importActivityFiles(listOf(file("route.gpx", gpxBytes("Route", startMs = null))))
        assertEquals(1, first.importedCount)

        val again = importActivityFiles(
            listOf(file("route.gpx", gpxBytes("Route", startMs = null))),
            existing = first.rides.map { it.toSummary() },
        )
        assertEquals(0, again.importedCount)
        assertEquals(1, again.duplicateCount)
        assertEquals(DUPLICATE_RIDE_MESSAGE, bulkImportMessage(again))
    }

    @Test
    fun `Meldungen fuer eine einzelne Datei sind konkret`() {
        val planned = importActivityFiles(listOf(file("r.gpx", gpxBytes("Route", startMs = null))))
        assertEquals("„Route“ als Planung importiert", bulkImportMessage(planned))

        val ridden = importActivityFiles(listOf(file("f.gpx", gpxBytes("Fahrt", 1_000L))))
        assertEquals("„Fahrt“ importiert", bulkImportMessage(ridden))

        val broken = importActivityFiles(listOf(file("x.gpx", "nix".toByteArray())))
        assertEquals(NOT_AN_ACTIVITY_FILE_MESSAGE, bulkImportMessage(broken))
        assertTrue(bulkImportFailureText(broken)!!.startsWith("Die Datei konnte nicht importiert werden."))

        val allBroken = importActivityFiles(
            listOf(file("x.gpx", "nix".toByteArray()), file("y.fit", "nix".toByteArray())),
        )
        assertTrue(bulkImportFailureText(allBroken)!!.startsWith("Keine der 2 Dateien"))
        assertEquals("2 unlesbar", bulkImportMessage(allBroken))
    }

    @Test
    fun `Sammelmeldung nennt Planungen`() {
        val allPlanned = importActivityFiles(
            listOf(
                file("a.gpx", gpxBytes("A", startMs = null, count = 3)),
                file("b.gpx", gpxBytes("B", startMs = null, count = 4)),
            ),
        )
        assertEquals("2 als Planung importiert", bulkImportMessage(allPlanned))

        val mixed = importActivityFiles(
            listOf(
                file("a.gpx", gpxBytes("A", startMs = null)),
                file("f.gpx", gpxBytes("F", 1_000L)),
                file("g.gpx", gpxBytes("G", 2_000_000L)),
            ),
        )
        assertEquals("3 importiert (1 als Planung)", bulkImportMessage(mixed))
    }

    // -----------------------------------------------------------------------
    // Groessengrenzen und Zeichensaetze
    // -----------------------------------------------------------------------

    /** Gueltiger GPX-Kopf, dahinter [padding] Leerzeichen — gepackt winzig. */
    private fun gzipBomb(padding: Int): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            gz.write("<?xml version=\"1.0\"?><gpx version=\"1.1\">".toByteArray())
            val chunk = ByteArray(1024 * 1024) { ' '.code.toByte() }
            repeat(padding / chunk.size) { gz.write(chunk) }
            gz.write("</gpx>".toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun `GZIP-Bombe wird als zu gross abgewiesen statt den Speicher zu fluten`() {
        val bomb = gzipBomb(padding = 40 * 1024 * 1024)
        assertTrue(bomb.size < 1024 * 1024, "gepackt ${bomb.size} Bytes")
        // Die Erkennung entpackt nur den Kopf und sieht eine GPX-Datei.
        assertEquals(ActivityFileKind.GPX, sniffActivityFileKind(bomb))

        val result = importActivityFiles(listOf(file("bombe.gpx.gz", bomb)))

        assertEquals(0, result.importedCount)
        assertEquals(listOf(FILE_TOO_LARGE_MESSAGE), result.errors.map { it.message })
    }

    @Test
    fun `GPX in UTF-16 und mit BOM wird erkannt und gelesen`() {
        val xml = gpxBytes("Fenster", 1_000L).decodeToString()
        for (bytes in listOf(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(Charsets.UTF_16LE),
            xml.toByteArray(Charsets.UTF_16BE),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray(Charsets.UTF_8),
        )) {
            assertEquals(ActivityFileKind.GPX, sniffActivityFileKind(bytes))
            assertEquals("Fenster", rideFromActivityFile(file("w.gpx", bytes)).name)
        }
    }

    @Test
    fun `Planungen mit minimal abweichender Distanz gelten als Duplikat`() {
        val route = rideFromActivityFile(file("r.gpx", gpxBytes("Route", startMs = null)), id = "1")
        // Frueher importiert: anderer Importzeitpunkt, also andere Startzeit.
        val summary = route.toSummary().copy(createdAt = route.createdAt - 86_400_000L)
        val rounded = summary.copy(
            id = "gespeichert",
            stats = summary.stats.copy(distanceKm = summary.stats.distanceKm + 0.0004),
        )
        assertTrue(findDuplicateRide(listOf(rounded), route.copy(id = "neu")) != null)

        val other = summary.copy(
            id = "andere",
            stats = summary.stats.copy(distanceKm = summary.stats.distanceKm + 0.01),
        )
        assertNull(findDuplicateRide(listOf(other), route.copy(id = "neu")))
    }
}
