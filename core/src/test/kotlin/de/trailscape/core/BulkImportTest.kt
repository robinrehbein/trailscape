package de.trailscape.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests des Massenimports (`BulkImport.kt`).
 *
 * Die Test-Archive werden mit [ZipOutputStream] gebaut — so lassen sich genau
 * die Faelle erzeugen, die ein Strava-Export mitbringt: Unterordner, `.gz`,
 * gemischte Formate, Duplikate und einzelne kaputte Dateien.
 */
class BulkImportTest {

    // -----------------------------------------------------------------------
    // Archiv-Baukasten
    // -----------------------------------------------------------------------

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in entries) {
                zip.putNextEntry(ZipEntry(path))
                if (!path.endsWith("/")) zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Minimale, aber gueltige GPX-Datei mit [count] Punkten ab [startMs]. */
    private fun gpxBytes(name: String, startMs: Long, count: Int = 3): ByteArray {
        val points = (0 until count).map {
            TrackPoint(lat = 47.0 + it * 0.001, lon = 11.0 + it * 0.001, ele = 600.0 + it, time = startMs + it * 10_000L)
        }
        return buildGpx(name, points).toByteArray(Charsets.UTF_8)
    }

    // --- FIT-Baukasten (siehe FitTest fuer die Format-Details) ---

    private fun u8(v: Int) = byteArrayOf((v and 0xFF).toByte())
    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    private fun u32(v: Long) = ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }
    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** FIT-Aktivitaet mit [count] Punkten ab [startMs] (record: timestamp, lat, lon, altitude). */
    private fun fitBytes(startMs: Long, count: Int = 3): ByteArray {
        val startFit = startMs / 1000L - FIT_EPOCH_OFFSET_S
        val body = ByteArrayOutputStream()
        // Definition Message fuer record (global 20), Little Endian.
        body.write(
            cat(
                u8(0x40), u8(0x00), u8(0x00), u16(20), u8(4),
                u8(253), u8(4), u8(0x86), // timestamp, uint32
                u8(0), u8(4), u8(0x85), // position_lat, sint32
                u8(1), u8(4), u8(0x85), // position_long, sint32
                u8(2), u8(2), u8(0x84), // altitude, uint16
            ),
        )
        for (i in 0 until count) {
            val lat = ((47.0 + i * 0.001) * 2147483648.0 / 180.0).toLong()
            val lon = ((11.0 + i * 0.001) * 2147483648.0 / 180.0).toLong()
            body.write(
                cat(
                    u8(0x00),
                    u32(startFit + i * 10),
                    u32(lat),
                    u32(lon),
                    u16(((600.0 + i + 500.0) * 5.0).toInt()),
                ),
            )
        }
        val data = body.toByteArray()
        val header = cat(u8(12), u8(0x20), u16(2140), u32(data.size.toLong()), ".FIT".toByteArray(Charsets.US_ASCII))
        val withData = cat(header, data)
        return cat(withData, u16(fitCrc16(withData, 0, withData.size)))
    }

    private fun ride(id: String, createdAt: Long, pointCount: Int) = Ride(
        id = id,
        name = "Bestand $id",
        createdAt = createdAt,
        stats = RideStats.empty,
        points = (0 until pointCount).map { TrackPoint(lat = 1.0, lon = 2.0, time = createdAt + it) },
    )

    // -----------------------------------------------------------------------
    // scanArchive
    // -----------------------------------------------------------------------

    @Test
    fun `scanArchive findet Aktivitaeten in Unterordnern und ignoriert alles andere`() {
        val zip = zipOf(
            "activities/" to ByteArray(0),
            "activities/1.gpx" to gpxBytes("Eins", 1_700_000_000_000L),
            "activities/2.fit.gz" to gzip(fitBytes(1_700_100_000_000L)),
            "activities/nested/3.gpx.gz" to gzip(gpxBytes("Drei", 1_700_200_000_000L)),
            "activities.csv" to "id,name\n".toByteArray(),
            "media/foto.jpg" to ByteArray(10),
            "__MACOSX/activities/._1.gpx" to ByteArray(4),
        )

        val entries = scanArchive(zip)

        assertEquals(3, entries.size)
        assertEquals(listOf("activities/1.gpx", "activities/2.fit.gz", "activities/nested/3.gpx.gz"), entries.map { it.path })
        assertEquals(listOf(ArchiveEntryKind.GPX, ArchiveEntryKind.FIT, ArchiveEntryKind.GPX), entries.map { it.kind })
        assertEquals(listOf(false, true, true), entries.map { it.gzipped })
        assertEquals(listOf("1", "2", "3"), entries.map { it.baseName })
    }

    @Test
    fun `scanArchive weist Nicht-ZIP-Dateien ab`() {
        val error = assertFailsWith<FormatException> { scanArchive("kein zip".toByteArray()) }
        assertEquals("Die Datei ist kein gültiges ZIP-Archiv.", error.message)
        assertFailsWith<FormatException> { importArchive("kein zip".toByteArray()) }
    }

    // -----------------------------------------------------------------------
    // importArchive
    // -----------------------------------------------------------------------

    @Test
    fun `gemischtes Archiv aus GPX und FIT wird vollstaendig importiert`() {
        val zip = zipOf(
            "activities/1.gpx" to gpxBytes("Runde am Fluss", 1_700_000_000_000L),
            "activities/2.fit" to fitBytes(1_700_100_000_000L),
            "activities/nested/3.gpx.gz" to gzip(gpxBytes("Bergtour", 1_700_200_000_000L)),
            "activities/4.fit.gz" to gzip(fitBytes(1_700_300_000_000L)),
        )

        val result = importArchive(zip)

        assertEquals(4, result.importedCount)
        assertEquals(0, result.duplicateCount)
        assertEquals(0, result.errorCount)
        assertEquals(4, result.totalCount)
        // GPX bringt seinen Tracknamen mit, FIT nur Sportart + Datum.
        assertEquals("Runde am Fluss", result.rides[0].name)
        assertEquals("Bergtour", result.rides[2].name)
        assertTrue(result.rides[1].name.endsWith(".2023"))
        assertTrue(result.rides.all { it.points.size == 3 })
        assertTrue(result.rides.all { it.stats.distanceKm > 0.0 })
        // Jede Tour bekommt eine eigene ID — sonst haelt die Duplikatpruefung
        // zwei im selben Millisekunden-Tick gelesene Touren fuer dieselbe.
        assertEquals(4, result.rides.map { it.id }.toSet().size)
    }

    @Test
    fun `kaputte Dateien landen einzeln in der Fehlerliste`() {
        val zip = zipOf(
            "activities/1.gpx" to gpxBytes("Gut", 1_700_000_000_000L),
            "activities/2.gpx" to "<html>keine GPX-Datei</html>".toByteArray(),
            "activities/3.fit" to ByteArray(40) { 0x7A },
            "activities/4.fit" to fitBytes(1_700_100_000_000L),
        )

        val result = importArchive(zip)

        assertEquals(2, result.importedCount)
        assertEquals(2, result.errorCount)
        assertEquals(listOf("activities/2.gpx", "activities/3.fit"), result.errors.map { it.path })
        assertTrue(result.errors.all { it.message.isNotBlank() })
        assertEquals("Die Datei ist keine gültige FIT-Datei.", result.errors[1].message)
    }

    @Test
    fun `Duplikate gegen den Bestand werden uebersprungen`() {
        val start = 1_700_000_000_000L
        val existing = listOf(ride("bestand", createdAt = start, pointCount = 3))
        val zip = zipOf(
            "activities/1.gpx" to gpxBytes("Schon da", start),
            "activities/2.gpx" to gpxBytes("Neu", start + 86_400_000L),
        )

        val result = importArchive(zip, existing)

        assertEquals(1, result.importedCount)
        assertEquals("Neu", result.rides.single().name)
        assertEquals(listOf("activities/1.gpx"), result.duplicates)
        assertEquals(0, result.errorCount)
    }

    @Test
    fun `Duplikate innerhalb des Archivs werden erkannt`() {
        val start = 1_700_000_000_000L
        val zip = zipOf(
            "activities/1.gpx" to gpxBytes("Tour", start),
            "export/kopie/1.gpx" to gpxBytes("Tour", start), // dieselbe Tour, anderer Pfad
            "activities/1.fit" to fitBytes(start), // dieselbe Tour als FIT
            "activities/2.gpx" to gpxBytes("Andere", start + 3_600_000L),
        )

        val result = importArchive(zip)

        assertEquals(2, result.importedCount)
        assertEquals(2, result.duplicateCount)
        assertEquals(listOf("export/kopie/1.gpx", "activities/1.fit"), result.duplicates)
    }

    @Test
    fun `Fortschritt wird mit bekannter Gesamtzahl gemeldet`() {
        val zip = zipOf(
            "activities/1.gpx" to gpxBytes("A", 1_700_000_000_000L),
            "readme.txt" to "hallo".toByteArray(),
            "activities/2.fit" to fitBytes(1_700_100_000_000L),
            "activities/3.gpx" to gpxBytes("C", 1_700_200_000_000L),
        )

        val steps = mutableListOf<Pair<Int, Int>>()
        val result = importArchive(zip) { done, total -> steps.add(done to total) }

        assertEquals(3, result.importedCount)
        // Nur Aktivitaetsdateien zaehlen — readme.txt taucht im Fortschritt nicht auf.
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), steps)
    }

    @Test
    fun `Streaming-Variante liest ohne vorherigen Scan und meldet done als Nenner`() {
        val zip = zipOf(
            "activities/1.gpx" to gpxBytes("A", 1_700_000_000_000L),
            "activities/2.gpx" to gpxBytes("B", 1_700_100_000_000L),
        )

        val steps = mutableListOf<Pair<Int, Int>>()
        val result = importArchive(ByteArrayInputStream(zip), onProgress = { done, total -> steps.add(done to total) })

        assertEquals(2, result.importedCount)
        assertEquals(listOf(1 to 1, 2 to 2), steps)
    }

    @Test
    fun `leeres Archiv liefert ein leeres Ergebnis statt eines Fehlers`() {
        val zip = zipOf("readme.txt" to "nichts zu holen".toByteArray())
        val result = importArchive(zip)

        assertTrue(result.isEmpty)
        assertEquals(0, result.totalCount)
        assertTrue(result.rides.isEmpty() && result.duplicates.isEmpty() && result.errors.isEmpty())
    }

    @Test
    fun `Dateiname ohne Endung dient als Fallback-Name`() {
        // GPX ohne <name>: dann greift der Dateiname aus dem Archiv.
        val nameless = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk><trkseg>
                <trkpt lat="47.0" lon="11.0"><time>2023-11-14T22:13:20Z</time></trkpt>
                <trkpt lat="47.001" lon="11.001"><time>2023-11-14T22:13:30Z</time></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val result = importArchive(zipOf("activities/12345678.gpx" to nameless))

        assertEquals(1, result.importedCount)
        assertEquals("12345678", result.rides.single().name)
    }

    @Test
    fun `Eintragsklassifizierung erkennt Endungen unabhaengig von Gross-Kleinschreibung`() {
        assertEquals(ArchiveEntryKind.GPX, classifyArchivePath("A/B/Tour.GPX")?.kind)
        assertEquals(ArchiveEntryKind.FIT, classifyArchivePath("Tour.Fit.GZ")?.kind)
        assertEquals(true, classifyArchivePath("Tour.Fit.GZ")?.gzipped)
        assertEquals(null, classifyArchivePath("activities/"))
        assertEquals(null, classifyArchivePath("activities/tour.tcx"))
        assertEquals(null, classifyArchivePath("__MACOSX/activities/._tour.gpx"))
        assertEquals(null, classifyArchivePath("activities/.hidden.gpx"))
        assertEquals("tour", archiveBaseName("a/b/tour.gpx.gz"))
        assertEquals("Tour", archiveBaseName("a/b/"))
    }

    @Test
    fun `rideFromArchiveEntry entpackt gz und waehlt den passenden Parser`() {
        val gpxEntry = ArchiveEntry("activities/9.gpx.gz", ArchiveEntryKind.GPX, gzipped = true)
        val fitEntry = ArchiveEntry("activities/9.fit.gz", ArchiveEntryKind.FIT, gzipped = true)

        val fromGpx = rideFromArchiveEntry(gpxEntry, gzip(gpxBytes("Aus GPX", 1_700_000_000_000L)), id = "g")
        val fromFit = rideFromArchiveEntry(fitEntry, gzip(fitBytes(1_700_000_000_000L)), id = "f")

        assertEquals("Aus GPX", fromGpx.name)
        assertEquals(1_700_000_000_000L, fromGpx.createdAt)
        assertEquals(3, fromFit.points.size)
        assertEquals(1_700_000_000_000L, fromFit.createdAt)
        assertEquals("f", fromFit.id)
    }
}
