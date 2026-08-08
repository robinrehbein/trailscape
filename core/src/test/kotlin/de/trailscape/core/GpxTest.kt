package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/gpx.dart`.
 *
 * Direkt aus `test/gpx_test.dart` uebernommen — gleiche Faelle, gleiche
 * Erwartungswerte, damit das Verhalten nachweislich deckungsgleich bleibt.
 */
class GpxTest {
    private companion object {
        const val EPS = 1e-9
    }

    // --- buildGpx / parseGpx roundtrip ---

    @Test
    fun `roundtrip erhaelt Name und Punkte inkl ele und time`() {
        val points = listOf(
            TrackPoint(lat = 47.123456, lon = 11.654321, ele = 1234.5, time = 1700000000000L),
            TrackPoint(lat = 47.2, lon = 11.7),
            TrackPoint(lat = 47.3, lon = 11.8, time = 1700000600000L),
        )

        val xml = buildGpx("Meine Tour", points)
        assertTrue(xml.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("version=\"1.1\""))
        assertTrue(xml.contains("creator=\"Trailscape\""))
        assertTrue(xml.contains("http://www.topografix.com/GPX/1/1"))

        val result = parseGpx(xml)
        assertEquals("Meine Tour", result.name)
        assertEquals(3, result.points.size)

        assertEquals(47.123456, result.points[0].lat, EPS)
        assertEquals(11.654321, result.points[0].lon, EPS)
        assertEquals(1234.5, result.points[0].ele!!, EPS)
        assertEquals(1700000000000L, result.points[0].time)

        assertNull(result.points[1].ele)
        assertNull(result.points[1].time)

        assertEquals(1700000600000L, result.points[2].time)
        assertNull(result.points[2].ele)
    }

    @Test
    fun `escaped Name wird korrekt gebaut und wieder geparst`() {
        val points = listOf(TrackPoint(lat = 1.0, lon = 2.0))
        val xml = buildGpx("Tour & <Test> \"Zitat\" 'Apostroph'", points)

        assertTrue(!xml.contains("<Test>"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&lt;Test"))

        val result = parseGpx(xml)
        assertEquals("Tour & <Test> \"Zitat\" 'Apostroph'", result.name)
    }

    @Test
    fun `leere Punktliste erzeugt GPX ohne Trackpunkte parseGpx wirft`() {
        val xml = buildGpx("Leer", emptyList())
        assertFailsWith<FormatException> { parseGpx(xml) }
    }

    // --- parseGpx mit handgeschriebenem GPX ---

    private val handwritten = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Testsuite" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>Metadata-Name</name>
  </metadata>
  <trk>
    <name>Zwei Segmente Tour</name>
    <trkseg>
      <trkpt lat="47.1" lon="11.1">
        <ele>500.0</ele>
        <time>2023-05-01T10:00:00Z</time>
      </trkpt>
      <trkpt lat="47.2" lon="11.2">
        <ele>510.5</ele>
        <time>2023-05-01T10:01:00Z</time>
      </trkpt>
    </trkseg>
    <trkseg>
      <trkpt lat="47.3" lon="11.3">
        <ele>520.0</ele>
        <time>2023-05-01T10:05:00Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    @Test
    fun `liest alle trkpt aus beiden Segmenten in Reihenfolge`() {
        val result = parseGpx(handwritten)

        assertEquals(3, result.points.size)
        assertEquals(47.1, result.points[0].lat, EPS)
        assertEquals(11.1, result.points[0].lon, EPS)
        assertEquals(500.0, result.points[0].ele!!, EPS)
        assertEquals(
            java.time.Instant.parse("2023-05-01T10:00:00Z").toEpochMilli(),
            result.points[0].time,
        )

        assertEquals(47.2, result.points[1].lat, EPS)
        assertEquals(47.3, result.points[2].lat, EPS)
        assertEquals(520.0, result.points[2].ele!!, EPS)
    }

    @Test
    fun `Name kommt aus trk name nicht aus metadata name`() {
        val result = parseGpx(handwritten)
        assertEquals("Zwei Segmente Tour", result.name)
    }

    @Test
    fun `Name faellt auf metadata name zurueck wenn trk name fehlt`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>Nur Metadata</name>
  </metadata>
  <trk>
    <trkseg>
      <trkpt lat="1.0" lon="2.0"/>
    </trkseg>
  </trk>
</gpx>
"""
        val result = parseGpx(xml)
        assertEquals("Nur Metadata", result.name)
    }

    @Test
    fun `Name ist null wenn weder trk name noch metadata name existiert`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="1.0" lon="2.0"/>
    </trkseg>
  </trk>
</gpx>
"""
        val result = parseGpx(xml)
        assertNull(result.name)
    }

    // --- rtept-Fallback ---

    @Test
    fun `nutzt rtept wenn keine trkpt vorhanden sind`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.0">
  <rte>
    <name>Route</name>
    <rtept lat="10.0" lon="20.0">
      <ele>100</ele>
    </rtept>
    <rtept lat="10.5" lon="20.5"/>
  </rte>
</gpx>
"""
        val result = parseGpx(xml)
        assertEquals(2, result.points.size)
        assertEquals(10.0, result.points[0].lat, EPS)
        assertEquals(100.0, result.points[0].ele!!, EPS)
        assertEquals(10.5, result.points[1].lat, EPS)
        assertNull(result.points[1].ele)
    }

    // --- Fehlerfaelle ---

    @Test
    fun `kaputtes XML wirft FormatException`() {
        val brokenXml = "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\">"
        assertFailsWith<FormatException> { parseGpx(brokenXml) }
    }

    @Test
    fun `gueltiges XML ohne gpx-Wurzel wirft FormatException`() {
        val xml = "<?xml version=\"1.0\"?><notgpx></notgpx>"
        assertFailsWith<FormatException> { parseGpx(xml) }
    }

    @Test
    fun `gueltiges GPX ohne Trackpunkte wirft FormatException`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Leer</name>
    <trkseg></trkseg>
  </trk>
</gpx>
"""
        assertFailsWith<FormatException> { parseGpx(xml) }
    }

    @Test
    fun `ungueltige Koordinaten werfen FormatException`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="nicht-numerisch" lon="2.0"/>
    </trkseg>
  </trk>
</gpx>
"""
        assertFailsWith<FormatException> { parseGpx(xml) }
    }

    @Test
    fun `leerer String wirft FormatException`() {
        assertFailsWith<FormatException> { parseGpx("") }
    }
}
