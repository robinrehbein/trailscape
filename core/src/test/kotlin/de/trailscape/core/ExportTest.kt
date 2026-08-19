package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/export.dart`.
 *
 * Direkt aus `test/export_test.dart` uebernommen — gleiche Faelle, gleiche
 * Erwartungswerte, damit das Verhalten nachweislich deckungsgleich bleibt.
 */
class ExportTest {
    private companion object {
        const val EPS = 1e-9

        fun isoUtc(epochMs: Long): String {
            val instant = java.time.Instant.ofEpochMilli(epochMs)
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
            return formatter.format(instant)
        }
    }

    private fun ride(
        id: String = "r1",
        name: String = "Alpencross",
        createdAt: Long = 1700000000000L,
        points: List<TrackPoint>? = null,
    ): Ride {
        val pts = points ?: listOf(
            TrackPoint(lat = 47.123456, lon = 11.654321, ele = 1234.5, time = 1700000000000L, hr = 142),
            TrackPoint(lat = 47.2, lon = 11.7, time = 1700000060000L, hr = 150),
            TrackPoint(lat = 47.3, lon = 11.8, ele = 1300.0, time = 1700000600000L, hr = 138),
        )
        return Ride(
            id = id,
            name = name,
            createdAt = createdAt,
            points = pts,
            stats = RideStats(
                distanceKm = 12.3,
                durationS = 600,
                movingTimeS = 580,
                avgSpeedKmh = 21.0,
                ascentM = 300.0,
                descentM = 100.0,
                avgHrBpm = 143,
                maxHrBpm = 150,
            ),
        )
    }

    // --- rideToGpx / GPX-Roundtrip ---

    @Test
    fun `erhaelt Punktzahl Zeiten und Herzfrequenz je Punkt`() {
        val r = ride()

        val xml = rideToGpx(r)
        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("version=\"1.1\""))
        assertTrue(xml.contains("gpxtpx:hr"))

        val parsed = parseGpx(xml)
        assertEquals(r.name, parsed.name)
        assertEquals(r.points.size, parsed.points.size)

        for (i in r.points.indices) {
            val original = r.points[i]
            val roundtripped = parsed.points[i]
            assertEquals(original.lat, roundtripped.lat, EPS)
            assertEquals(original.lon, roundtripped.lon, EPS)
            assertEquals(original.time, roundtripped.time)
            assertEquals(original.hr, roundtripped.hr)
            if (original.ele != null) {
                assertEquals(original.ele, roundtripped.ele!!, EPS)
            } else {
                assertNull(roundtripped.ele)
            }
        }
    }

    @Test
    fun `enthaelt Metadaten mit Name und Aufnahmezeitpunkt`() {
        val r = ride(createdAt = 1700000000000L)
        val xml = rideToGpx(r)

        assertTrue(xml.contains("<metadata>"))
        assertTrue(xml.contains(r.name))
        assertTrue(xml.contains(isoUtc(r.createdAt)))
    }

    @Test
    fun `ohne Herzfrequenz wird kein gpxtpx-Namespace geschrieben`() {
        val r = ride(
            points = listOf(
                TrackPoint(lat = 47.0, lon = 11.0),
                TrackPoint(lat = 47.1, lon = 11.1),
            ),
        )
        val xml = rideToGpx(r)
        assertTrue(!xml.contains("gpxtpx"))
    }

    // --- XML-Escaping ---

    @Test
    fun `Sonderzeichen im Tournamen werden escaped und korrekt zurueckgelesen`() {
        val r = ride(name = "Tour & <Test> \"Zitat\" 'Apostroph'")
        val xml = rideToGpx(r)

        assertTrue(!xml.contains("<Test>"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;Test"))

        val parsed = parseGpx(xml)
        assertEquals(r.name, parsed.name)
    }

    // --- rideFromGpx ---

    @Test
    fun `berechnet Statistiken inkl Oe- und Max-Puls aus den Trackpunkten`() {
        val original = ride()
        val xml = rideToGpx(original)

        val imported = rideFromGpx(xml, fallbackName = "egal", id = "imported")

        assertEquals("imported", imported.id)
        assertEquals(original.name, imported.name)
        assertEquals(original.points.size, imported.points.size)
        assertEquals(143, imported.stats.avgHrBpm) // Mittel aus 142, 150, 138
        assertEquals(150, imported.stats.maxHrBpm)
        assertTrue(imported.stats.distanceKm > 0)
    }

    @Test
    fun `funktioniert ohne ele time hr nur Koordinaten`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="47.0" lon="11.0"/>
      <trkpt lat="47.01" lon="11.01"/>
    </trkseg>
  </trk>
</gpx>
"""
        val r = rideFromGpx(xml, fallbackName = "Ohne Extras")

        assertEquals("Ohne Extras", r.name)
        assertEquals(2, r.points.size)
        assertTrue(r.points.all { it.ele == null })
        assertTrue(r.points.all { it.time == null })
        assertTrue(r.points.all { it.hr == null })
        assertNull(r.stats.avgHrBpm)
        assertNull(r.stats.maxHrBpm)
        assertNull(r.stats.durationS)
        assertTrue(r.stats.distanceKm > 0)
    }

    @Test
    fun `nutzt den GPX-Namen wenn vorhanden statt fallbackName`() {
        val original = ride(name = "Original-Name")
        val xml = rideToGpx(original)
        val imported = rideFromGpx(xml, fallbackName = "Fallback")
        assertEquals("Original-Name", imported.name)
    }

    @Test
    fun `kaputtes GPX wirft FormatException`() {
        assertFailsWith<FormatException> { rideFromGpx("<gpx><trk>", fallbackName = "x") }
    }

    // --- Backup: buildBackupJson / parseBackupJson ---

    @Test
    fun `Roundtrip erhaelt Touren und Profil`() {
        val rides = listOf(ride(id = "a"), ride(id = "b", name = "Feierabendrunde"))
        val profile = TrainingProfile(
            ageYears = 34,
            sex = Sex.WEIBLICH,
            weightKg = 62.0,
            hrMaxOverride = 188.0,
        )

        val json = buildBackupJson(rides, profile)
        assertTrue(json.contains("\"app\": \"trailscape\""))
        assertTrue(json.contains("\"backupVersion\": 1"))

        val data = parseBackupJson(json)
        assertEquals(2, data.rides.size)
        assertTrue(data.rides.map { it.id }.containsAll(listOf("a", "b")))
        assertEquals(rides.first().points.size, data.rides.first().points.size)
        assertNotNull(data.profile)
        assertEquals(34, data.profile.ageYears)
        assertEquals(Sex.WEIBLICH, data.profile.sex)
        assertEquals(62.0, data.profile.weightKg, EPS)
        assertEquals(188.0, data.profile.hrMaxOverride!!, EPS)
    }

    @Test
    fun `Roundtrip ohne Profil liefert null`() {
        val json = buildBackupJson(listOf(ride()), null)
        val data = parseBackupJson(json)
        assertNull(data.profile)
        assertEquals(1, data.rides.size)
    }

    @Test
    fun `leere Tourenliste erzeugt gueltiges Backup mit leerer Liste`() {
        val json = buildBackupJson(emptyList(), null)
        val data = parseBackupJson(json)
        assertTrue(data.rides.isEmpty())
        assertNull(data.profile)
    }

    @Test
    fun `kaputtes JSON wirft FormatException`() {
        assertFailsWith<FormatException> { parseBackupJson("{ das ist kein json") }
    }

    @Test
    fun `valides JSON ohne Trailscape-Signatur wirft FormatException`() {
        assertFailsWith<FormatException> { parseBackupJson("""{"foo": "bar"}""") }
    }

    @Test
    fun `fremdes app-Feld wirft FormatException`() {
        assertFailsWith<FormatException> {
            parseBackupJson("""{"app": "andereApp", "backupVersion": 1, "rides": []}""")
        }
    }

    @Test
    fun `hoehere unbekannte backupVersion wirft FormatException`() {
        assertFailsWith<FormatException> {
            parseBackupJson("""{"app": "trailscape", "backupVersion": 999, "rides": []}""")
        }
    }

    @Test
    fun `fehlende Touren-Liste wirft FormatException`() {
        assertFailsWith<FormatException> {
            parseBackupJson("""{"app": "trailscape", "backupVersion": 1}""")
        }
    }

    // --- Streamendes Backup: byteidentisch zu buildBackupJson ---

    @Test
    fun `writeBackupJson ist byteidentisch zu buildBackupJson`() {
        val rides = listOf(
            ride(id = "a"),
            ride(id = "b", name = "Feierabendrunde & \"Test\" <ä>"),
            // Eine Tour ohne Punkte und mit gesetztem planned/updatedAt, damit
            // auch leeres Punkt-Array und Sonderfelder verglichen werden.
            ride(id = "c", points = emptyList()).copy(planned = true, updatedAt = 1700000999000L),
        )
        val profile = TrainingProfile(
            ageYears = 34,
            sex = Sex.WEIBLICH,
            weightKg = 62.0,
            hrMaxOverride = 188.0,
        )
        val at = 1700000123456L

        val expected = buildBackupJson(rides, profile, exportedAtMs = at)
        val streamed = StringBuilder()
        writeBackupJson(streamed, rides.asSequence(), profile, exportedAtMs = at)

        assertEquals(expected, streamed.toString())
        // Und das Ergebnis bleibt eine gueltige Sicherung.
        assertEquals(3, parseBackupJson(streamed.toString()).rides.size)
    }

    @Test
    fun `writeBackupJson ohne Touren und ohne Profil ist byteidentisch`() {
        val at = 1700000123456L
        val expected = buildBackupJson(emptyList(), null, exportedAtMs = at)
        val streamed = StringBuilder()
        writeBackupJson(streamed, emptySequence(), null, exportedAtMs = at)
        assertEquals(expected, streamed.toString())
        assertTrue(parseBackupJson(streamed.toString()).rides.isEmpty())
    }

    @Test
    fun `writeBackupJson mit genau einer Tour ist byteidentisch`() {
        val at = 1700000123456L
        val rides = listOf(ride(id = "solo"))
        val expected = buildBackupJson(rides, null, exportedAtMs = at)
        val streamed = StringBuilder()
        writeBackupJson(streamed, rides.asSequence(), null, exportedAtMs = at)
        assertEquals(expected, streamed.toString())
    }

    // --- safeFileName / backupFileName ---

    @Test
    fun `ersetzt Sonderzeichen und trimmt Unterstriche`() {
        assertEquals("Alpen_Cross_2026_Tag_1", safeFileName("Alpen Cross 2026 (Tag 1)!"))
        assertEquals("tour", safeFileName("   "))
    }

    @Test
    fun `backupFileName formatiert Datum zweistellig`() {
        assertEquals("trailscape-backup-2026-08-08.json", backupFileName(LocalDate.of(2026, 8, 8)))
        assertEquals("trailscape-backup-2026-01-02.json", backupFileName(LocalDate.of(2026, 1, 2)))
    }
}
