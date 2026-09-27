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

    // --- Sensorwerte: Puls, Trittfrequenz, Leistung ---

    @Test
    fun `Export nur mit Puls bleibt byteidentisch zum bisherigen Format`() {
        val xml = buildGpx("T", listOf(TrackPoint(lat = 1.0, lon = 2.0, time = 1700000000000L, hr = 140)))
        val erwartet = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Trailscape" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
  <metadata>
    <name>T</name>
  </metadata>
  <trk>
    <name>T</name>
    <trkseg>
      <trkpt lat="1.0" lon="2.0">
        <time>2023-11-14T22:13:20.000Z</time>
        <extensions>
          <gpxtpx:TrackPointExtension>
            <gpxtpx:hr>140</gpxtpx:hr>
          </gpxtpx:TrackPointExtension>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""
        assertEquals(erwartet, xml)
    }

    @Test
    fun `Export ohne Sensorwerte deklariert keinen Namespace`() {
        val xml = buildGpx("T", listOf(TrackPoint(lat = 1.0, lon = 2.0), TrackPoint(lat = 1.1, lon = 2.1, ele = 3.0)))
        assertTrue(!xml.contains("gpxtpx"))
        assertTrue(!xml.contains("extensions"))
        assertTrue(xml.contains("<trkpt lat=\"1.0\" lon=\"2.0\"/>"))
    }

    @Test
    fun `Export mit Trittfrequenz und Leistung`() {
        val xml = buildGpx(
            "T",
            listOf(
                TrackPoint(lat = 1.0, lon = 2.0, time = 1700000000000L, hr = 140, power = 230, cad = 88),
                TrackPoint(lat = 1.1, lon = 2.1, power = 0),
            ),
        )
        assertTrue(xml.contains("xmlns:gpxtpx="))
        assertTrue(
            xml.contains(
                """        <extensions>
          <power>230</power>
          <gpxtpx:TrackPointExtension>
            <gpxtpx:hr>140</gpxtpx:hr>
            <gpxtpx:cad>88</gpxtpx:cad>
          </gpxtpx:TrackPointExtension>
        </extensions>""",
            ),
        )
        // Nur Leistung: keine leere TrackPointExtension.
        assertTrue(
            xml.contains(
                """      <trkpt lat="1.1" lon="2.1">
        <extensions>
          <power>0</power>
        </extensions>
      </trkpt>""",
            ),
        )
        val back = parseGpx(xml).points
        assertEquals(140, back[0].hr)
        assertEquals(230, back[0].power)
        assertEquals(88, back[0].cad)
        assertEquals(0, back[1].power)
        assertNull(back[1].cad)
        assertNull(back[1].hr)
    }

    @Test
    fun `Namespace auch bei Trittfrequenz ohne Puls`() {
        val xml = buildGpx("T", listOf(TrackPoint(lat = 1.0, lon = 2.0, cad = 90)))
        assertTrue(xml.contains("xmlns:gpxtpx="))
        assertTrue(xml.contains("<gpxtpx:cad>90</gpxtpx:cad>"))
        assertTrue(!xml.contains("<gpxtpx:hr>"))
        assertEquals(90, parseGpx(xml).points.single().cad)
    }

    @Test
    fun `Import liest PowerInWatts und verwirft Unplausibles`() {
        val xml = """
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1" xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
  <trk>
    <trkseg>
      <trkpt lat="1.0" lon="2.0">
        <extensions>
          <ns3:TrackPointExtension><ns3:cad>91.4</ns3:cad></ns3:TrackPointExtension>
          <pwr:PowerInWatts xmlns:pwr="http://www.garmin.com/xmlschemas/PowerExtension/v1">305</pwr:PowerInWatts>
        </extensions>
      </trkpt>
      <trkpt lat="1.1" lon="2.1">
        <extensions>
          <power>9999</power>
          <ns3:TrackPointExtension><ns3:cad>400</ns3:cad></ns3:TrackPointExtension>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""
        val points = parseGpx(xml).points
        assertEquals(305, points[0].power)
        assertEquals(91, points[0].cad)
        assertNull(points[1].power)
        assertNull(points[1].cad)
    }
}
