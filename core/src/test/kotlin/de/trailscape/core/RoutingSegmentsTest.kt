package de.trailscape.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Kachel-Rechnung (`RoutingSegments.kt`) und des schmalen
 * Format-Wrappers (`SegmentDelta.kt`).
 *
 * Beides laeuft ohne Netz und ohne echte Kacheldatei: Die Umkehrung
 * Name → Flaeche ist reine Arithmetik, die Bezeichnung reine Zeichenkette und
 * die Entscheidung „Delta oder Vollabzug" eine Tabelle. Was eine echte
 * 120-MB-Kachel braucht (Delta anwenden, Integritaet pruefen), steht als
 * Handtest in `app/.../SegmentDownloadManualTest.kt`.
 */
class RoutingSegmentsTest {

    // -----------------------------------------------------------------------
    // Name → Flaeche
    // -----------------------------------------------------------------------

    @Test
    fun `parseSegmentTile liest die Suedwestecke aus dem Namen`() {
        val tile = assertNotNull(parseSegmentTile("E10_N50.rd5"))
        assertEquals(10, tile.westLon)
        assertEquals(50, tile.southLat)
        assertEquals(15, tile.eastLon)
        assertEquals(55, tile.northLat)
        assertEquals("E10_N50", tile.name)
        assertEquals("E10_N50.rd5", tile.fileName)
        assertEquals(52.5, tile.centerLat)
        assertEquals(12.5, tile.centerLon)
    }

    @Test
    fun `parseSegmentTile kommt mit und ohne Endung sowie mit Rand- und Suedwestwerten klar`() {
        assertEquals(parseSegmentTile("E10_N50"), parseSegmentTile("E10_N50.rd5"))

        val southWest = assertNotNull(parseSegmentTile("W15_S35.rd5"))
        assertEquals(-15, southWest.westLon)
        assertEquals(-35, southWest.southLat)
        assertEquals(-10, southWest.eastLon)
        assertEquals(-30, southWest.northLat)
        assertEquals("W15_S35", southWest.name)

        // Die Raender der Erde.
        assertNotNull(parseSegmentTile("W180_S90.rd5"))
        assertNotNull(parseSegmentTile("E175_N85.rd5"))
    }

    @Test
    fun `parseSegmentTile weist alles zurueck, was keine Kachel ist`() {
        // Fremde Dateien im Kachelverzeichnis duerfen die Liste nicht sprengen.
        assertNull(parseSegmentTile("lookups.dat"))
        assertNull(parseSegmentTile("E10_N50.rd5.part"))
        assertNull(parseSegmentTile(""))
        // Kein Vielfaches der Rasterweite.
        assertNull(parseSegmentTile("E11_N50.rd5"))
        assertNull(parseSegmentTile("E10_N51.rd5"))
        // Ausserhalb der Erde.
        assertNull(parseSegmentTile("E180_N50.rd5"))
        assertNull(parseSegmentTile("E10_N90.rd5"))
        assertNull(parseSegmentTile("W185_N50.rd5"))
    }

    @Test
    fun `segmentTileAt und segmentFileName sind zwei Seiten derselben Rechnung`() {
        val points = listOf(
            51.0504 to 13.7373, // Dresden
            48.137 to 11.576, // Muenchen
            -33.925 to 18.424, // Kapstadt
            40.713 to -74.006, // New York
            0.0 to 0.0,
            -0.5 to -0.5,
        )
        for ((lat, lon) in points) {
            assertEquals(segmentFileName(lat, lon), segmentTileAt(lat, lon).fileName)
        }
    }

    // -----------------------------------------------------------------------
    // Bezeichnung
    // -----------------------------------------------------------------------

    @Test
    fun `boundsLabel nennt das Gradfeld mit dem kleineren Betrag zuerst`() {
        assertEquals("50°–55° N, 10°–15° O", parseSegmentTile("E10_N50")!!.boundsLabel)
        // Westlich von Greenwich zaehlen die Betraege aufwaerts nach Westen.
        assertEquals("50°–55° N, 5°–10° W", parseSegmentTile("W10_N50")!!.boundsLabel)
        // Suedhalbkugel ebenso.
        assertEquals("30°–35° S, 15°–20° O", parseSegmentTile("E15_S35")!!.boundsLabel)
        // Die Kacheln direkt am Nullpunkt.
        assertEquals("0°–5° N, 0°–5° O", parseSegmentTile("E0_N0")!!.boundsLabel)
        assertEquals("0°–5° S, 0°–5° W", parseSegmentTile("W5_S5")!!.boundsLabel)
    }

    @Test
    fun `die Bezeichnung nennt Orte in der Kachel und behauptet keine Vollstaendigkeit`() {
        val tile = assertNotNull(parseSegmentTile("E10_N50.rd5"))
        // Genau der Fall aus dem Klassendoc: eine Kachel ueber drei Laender.
        assertEquals(listOf("Berlin", "Dresden", "Prag"), tile.landmarks)
        assertEquals("Berlin, Dresden, Prag u. a.", tile.title)
        assertEquals("Berlin, Dresden, Prag u. a. · 50°–55° N, 10°–15° O", tile.description)
    }

    @Test
    fun `ohne hinterlegten Ort bleibt es beim Gradfeld statt bei einer Erfindung`() {
        // Mitten im Nordatlantik.
        val tile = assertNotNull(parseSegmentTile("W35_N45.rd5"))
        assertTrue(tile.landmarks.isEmpty())
        assertEquals(tile.boundsLabel, tile.title)
        assertEquals(tile.boundsLabel, tile.description)
    }

    @Test
    fun `jeder hinterlegte Ort liegt wirklich in der Kachel, der er zugeordnet wird`() {
        // Die Zuordnung entsteht ueber segmentFileName; dieser Test sichert,
        // dass die Koordinaten der Ortsliste keine Tippfehler enthalten, indem
        // er fuer jeden genannten Ort die Umkehrung nachrechnet.
        var checked = 0
        for (tileName in knownLandmarkTiles) {
            val tile = assertNotNull(parseSegmentTile(tileName))
            for (name in tile.landmarks) {
                val point = assertNotNull(landmarkTestCoordinates[name], "Ort $name unbekannt")
                assertEquals(
                    tile.fileName,
                    segmentFileName(point.first, point.second),
                    "$name liegt nicht in $tileName",
                )
                checked++
            }
        }
        assertTrue(checked >= 20, "zu wenige geprueft: $checked")
    }

    @Test
    fun `hoechstens drei Orte je Kachel`() {
        for (tileName in knownLandmarkTiles) {
            val tile = assertNotNull(parseSegmentTile(tileName))
            assertTrue(tile.landmarks.size <= 3, "$tileName nennt ${tile.landmarks.size} Orte")
        }
    }

    // -----------------------------------------------------------------------
    // Ausschnitt → Kacheln
    // -----------------------------------------------------------------------

    @Test
    fun `segmentTilesForBounds deckt Deutschland mit den vier bekannten Kacheln ab`() {
        // Der Kern Deutschlands. Die aeussersten Zipfel (Sylt bei 55,06° N,
        // die Neisse bei 15,04° O) ragen knapp in die Nachbarkacheln — genau
        // deshalb fragt man den Kartenausschnitt und nicht „das Land".
        val tiles = segmentTilesForBounds(north = 54.5, south = 47.5, east = 14.5, west = 6.0)
        assertEquals(
            listOf("E5_N45.rd5", "E10_N45.rd5", "E5_N50.rd5", "E10_N50.rd5"),
            tiles.map { it.fileName },
        )
    }

    @Test
    fun `ein Ausschnitt innerhalb einer Kachel ergibt genau diese eine`() {
        val tiles = segmentTilesForBounds(north = 51.2, south = 50.9, east = 13.9, west = 13.5)
        assertEquals(listOf("E10_N50.rd5"), tiles.map { it.fileName })
    }

    @Test
    fun `Kachelgrenzen zaehlen zur noerdlich bzw oestlich anschliessenden Kachel`() {
        // Ein Punkt genau auf 50 N gehoert laut segmentFileName zu N50.
        assertEquals("E10_N50.rd5", segmentFileName(50.0, 10.0))
        val tiles = segmentTilesForBounds(north = 50.0, south = 50.0, east = 10.0, west = 10.0)
        assertEquals(listOf("E10_N50.rd5"), tiles.map { it.fileName })
    }

    @Test
    fun `ein Ausschnitt ueber den 180 Grad hinweg laeuft um statt leer zu bleiben`() {
        val tiles = segmentTilesForBounds(north = 66.0, south = 65.0, east = -175.0, west = 175.0)
        assertEquals(listOf("E175_N65.rd5", "W180_N65.rd5", "W175_N65.rd5"), tiles.map { it.fileName })
    }

    @Test
    fun `verdrehte Breitengrade ergeben eine leere Liste`() {
        assertTrue(segmentTilesForBounds(north = 40.0, south = 50.0, east = 10.0, west = 5.0).isEmpty())
    }

    @Test
    fun `die ganze Welt ergibt das vollstaendige Raster ohne Doppelte`() {
        // Ost- und Westgrenze duerfen nicht auf dieselbe Spalte fallen — 180°
        // und -180° sind derselbe Meridian und ergaeben eine einzige Spalte.
        val tiles = segmentTilesForBounds(north = 90.0, south = -90.0, east = 179.9, west = -180.0)
        // 36 Reihen (-90 bis 85) mal 72 Spalten.
        assertEquals(36 * 72, tiles.size)
        assertEquals(tiles.size, tiles.map { it.fileName }.toSet().size)
    }

    // -----------------------------------------------------------------------
    // Adressen
    // -----------------------------------------------------------------------

    @Test
    fun `die Adressen entsprechen dem Aufbau aus DownloadWorker`() {
        assertEquals(
            "https://brouter.de/brouter/segments4/E10_N50.rd5",
            segmentDownloadUrl("E10_N50.rd5"),
        )
        assertEquals(
            "https://brouter.de/brouter/segments4/diff/E10_N50/abc123.df5",
            segmentDeltaUrl("E10_N50.rd5", "abc123"),
        )
        // Mit eigener Basis (Tests, Spiegelserver).
        assertEquals(
            "http://localhost:8080/E10_N50.rd5",
            segmentDownloadUrl("E10_N50.rd5", "http://localhost:8080/"),
        )
    }

    // -----------------------------------------------------------------------
    // Delta oder Vollabzug?
    // -----------------------------------------------------------------------

    private val remote = RemoteSegment(
        fileName = "E10_N50.rd5",
        sizeBytes = 124_551_246L,
        eTag = "\"6a7d17c5-76c804e\"",
        lastModified = "Thu, 13 Aug 2026 01:03:01 GMT",
    )

    @Test
    fun `ohne lokale Datei bleibt nur der Vollabzug`() {
        assertEquals(SegmentUpdateAction.FULL, planSegmentUpdate(null, remote))
        assertEquals(
            SegmentUpdateAction.FULL,
            planSegmentUpdate(LocalSegment("E10_N50.rd5", sizeBytes = 0L), remote),
        )
    }

    @Test
    fun `gleicher ETag und gleiche Groesse heisst nichts zu tun`() {
        val local = LocalSegment(
            fileName = "E10_N50.rd5",
            sizeBytes = remote.sizeBytes,
            eTag = remote.eTag,
            lastModified = remote.lastModified,
        )
        assertEquals(SegmentUpdateAction.UP_TO_DATE, planSegmentUpdate(local, remote))
        assertTrue(isSameSegmentVersion(local, remote))
    }

    @Test
    fun `abweichende Groesse schlaegt jede Kopfzeile`() {
        // Der Fall der abgebrochenen Vollabzugs, dessen Teildatei versehentlich
        // unter dem richtigen Namen landete: ETag passt, Bytes fehlen.
        val local = LocalSegment(
            fileName = "E10_N50.rd5",
            sizeBytes = 12_345L,
            eTag = remote.eTag,
            lastModified = remote.lastModified,
        )
        assertFalse(isSameSegmentVersion(local, remote))
        assertEquals(SegmentUpdateAction.DELTA, planSegmentUpdate(local, remote))
    }

    @Test
    fun `ein juengerer Serverstand fuehrt zum Delta`() {
        val local = LocalSegment(
            fileName = "E10_N50.rd5",
            sizeBytes = 124_000_000L,
            eTag = "\"alt\"",
            lastModified = "Mon, 10 Aug 2026 01:03:01 GMT",
        )
        assertEquals(SegmentUpdateAction.DELTA, planSegmentUpdate(local, remote))
    }

    @Test
    fun `aelter als die Delta-Geschichte des Servers heisst Vollabzug`() {
        val local = LocalSegment(
            fileName = "E10_N50.rd5",
            sizeBytes = 124_000_000L,
            eTag = "\"alt\"",
            // Zehn Tage alt — der Server haelt nur neun Tage Delta-Geschichte.
            lastModified = "Mon, 03 Aug 2026 01:03:01 GMT",
        )
        assertEquals(SegmentUpdateAction.FULL, planSegmentUpdate(local, remote))
    }

    @Test
    fun `unbekannte Herkunft gilt als veraltet, nicht als aktuell`() {
        // Datei da, aber keine gemerkten Kopfzeilen (z. B. von Hand kopiert).
        val local = LocalSegment(fileName = "E10_N50.rd5", sizeBytes = remote.sizeBytes)
        assertFalse(isSameSegmentVersion(local, remote))
        // Delta, nicht Vollabzug: Der Server beantwortet das mit dem leeren
        // Dummy-Delta, falls die Datei doch aktuell ist.
        assertEquals(SegmentUpdateAction.DELTA, planSegmentUpdate(local, remote))
    }

    @Test
    fun `ohne ETag entscheidet Last-Modified`() {
        val remoteNoTag = remote.copy(eTag = null)
        val same = LocalSegment(
            fileName = "E10_N50.rd5",
            sizeBytes = remote.sizeBytes,
            lastModified = remote.lastModified,
        )
        assertTrue(isSameSegmentVersion(same, remoteNoTag))
        assertFalse(
            isSameSegmentVersion(
                same.copy(lastModified = "Wed, 12 Aug 2026 01:03:01 GMT"),
                remoteNoTag,
            ),
        )
    }

    @Test
    fun `parseHttpDateMs liest das RFC-1123-Format und schluckt Unsinn`() {
        assertEquals(
            1_786_582_981_000L,
            parseHttpDateMs("Thu, 13 Aug 2026 01:03:01 GMT"),
        )
        assertNull(parseHttpDateMs(null))
        assertNull(parseHttpDateMs(""))
        assertNull(parseHttpDateMs("gestern"))
    }

    // -----------------------------------------------------------------------
    // Format-Wrapper
    // -----------------------------------------------------------------------

    @Test
    fun `das leere Delta ist das Signal fuer bereits aktuell`() {
        assertTrue(segmentDeltaIsDummy(0L))
        assertFalse(segmentDeltaIsDummy(597_575L))
    }

    @Test
    fun `ein leeres Delta kopiert die Kachel unveraendert`() {
        // Genau der Weg, den `recoverFromDelta` fuer die Dummy-Datei nimmt.
        val dir = createTempDir()
        val base = File(dir, "base.rd5").apply { writeText("Kacheldaten") }
        val delta = File(dir, "delta.df5").apply { writeBytes(ByteArray(0)) }
        val out = File(dir, "out.rd5")

        assertTrue(applySegmentDelta(base, delta, out))
        assertEquals("Kacheldaten", out.readText())
    }

    @Test
    fun `ein unpassendes Delta wirft eine deutsche Meldung statt eines Stacktrace`() {
        val dir = createTempDir()
        val base = File(dir, "base.rd5").apply { writeText("Kacheldaten") }
        val delta = File(dir, "delta.df5").apply { writeText("kein gueltiges Delta") }
        val out = File(dir, "out.rd5")

        val error = try {
            applySegmentDelta(base, delta, out)
            null
        } catch (e: OfflineRoutingException) {
            e
        }
        assertNotNull(error)
        assertTrue(error.message!!.startsWith("Die Karten-Aktualisierung"), error.message!!)
        // Die halbfertige Ausgabe darf nicht liegen bleiben.
        assertFalse(out.exists())
    }

    @Test
    fun `checkSegmentIntegrity meldet eine kaputte Kachel als Text`() {
        val dir = createTempDir()
        val broken = File(dir, "E10_N50.rd5").apply { writeText("das ist keine Kachel") }
        val message = checkSegmentIntegrity(broken)
        assertNotNull(message)
        assertTrue(message.contains("E10_N50.rd5"), message)
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    private fun createTempDir(): File {
        val dir = File.createTempFile("trailscape-segmente", "")
        check(dir.delete() && dir.mkdirs())
        dir.deleteOnExit()
        return dir
    }

    /**
     * Kacheln, fuer die die Ortsliste etwas hergibt — bewusst als feste Liste
     * im Test und nicht aus der Produktivliste abgeleitet, damit ein
     * versehentliches Leeren der Ortsliste hier auffliegt.
     */
    private val knownLandmarkTiles = listOf(
        "E10_N50", "E5_N50", "E5_N45", "E10_N45", "E15_N45", "E0_N50",
        "E10_N55", "E15_N50", "E20_N50", "E0_N45", "W5_N50", "E10_N40",
        "E15_N55", "W5_N40", "W10_N35", "E20_N35", "E25_N40", "W75_N40",
    )

    /** Koordinaten zur Gegenprobe, unabhaengig von der Produktivliste getippt. */
    private val landmarkTestCoordinates = mapOf(
        "Berlin" to (52.520 to 13.405),
        "Hamburg" to (53.551 to 9.994),
        "München" to (48.137 to 11.576),
        "Köln" to (50.938 to 6.960),
        "Frankfurt am Main" to (50.110 to 8.682),
        "Stuttgart" to (48.776 to 9.182),
        "Dresden" to (51.050 to 13.738),
        "Wien" to (48.208 to 16.373),
        "Zürich" to (47.377 to 8.540),
        "Prag" to (50.075 to 14.437),
        "Amsterdam" to (52.370 to 4.895),
        "Brüssel" to (50.851 to 4.352),
        "Kopenhagen" to (55.676 to 12.568),
        "Warschau" to (52.230 to 21.012),
        "Danzig" to (54.352 to 18.646),
        "Krakau" to (50.065 to 19.945),
        "Budapest" to (47.498 to 19.040),
        "Ljubljana" to (46.056 to 14.506),
        "Zagreb" to (45.815 to 15.982),
        "Innsbruck" to (47.269 to 11.404),
        "Paris" to (48.857 to 2.352),
        "London" to (51.507 to -0.128),
        "Madrid" to (40.417 to -3.704),
        "Rom" to (41.903 to 12.496),
        "Mailand" to (45.464 to 9.190),
        "Barcelona" to (41.385 to 2.173),
        "Lissabon" to (38.722 to -9.139),
        "Porto" to (41.150 to -8.611),
        "Sevilla" to (37.389 to -5.984),
        "Valencia" to (39.470 to -0.377),
        "Bilbao" to (43.263 to -2.935),
        "Bordeaux" to (44.838 to -0.579),
        "Lyon" to (45.764 to 4.836),
        "Marseille" to (43.296 to 5.370),
        "Toulouse" to (43.605 to 1.444),
        "Nizza" to (43.700 to 7.265),
        "Neapel" to (40.852 to 14.268),
        "Palermo" to (38.116 to 13.361),
        "Dublin" to (53.350 to -6.260),
        "Edinburgh" to (55.953 to -3.188),
        "Oslo" to (59.914 to 10.752),
        "Stockholm" to (59.329 to 18.069),
        "Göteborg" to (57.709 to 11.974),
        "Helsinki" to (60.170 to 24.938),
        "Tromsø" to (69.649 to 18.956),
        "Reykjavík" to (64.147 to -21.942),
        "Riga" to (56.949 to 24.105),
        "Tallinn" to (59.437 to 24.754),
        "Vilnius" to (54.687 to 25.280),
        "Minsk" to (53.902 to 27.562),
        "Kiew" to (50.451 to 30.523),
        "Moskau" to (55.756 to 37.617),
        "Bukarest" to (44.427 to 26.103),
        "Belgrad" to (44.787 to 20.449),
        "Sofia" to (42.698 to 23.322),
        "Athen" to (37.984 to 23.728),
        "Istanbul" to (41.009 to 28.978),
        "New York" to (40.713 to -74.006),
        "Los Angeles" to (34.052 to -118.244),
        "Toronto" to (43.653 to -79.383),
        "Mexiko-Stadt" to (19.433 to -99.133),
        "Buenos Aires" to (-34.604 to -58.382),
        "São Paulo" to (-23.551 to -46.633),
        "Kapstadt" to (-33.925 to 18.424),
        "Kairo" to (30.044 to 31.236),
        "Marrakesch" to (31.630 to -7.981),
        "Dubai" to (25.205 to 55.271),
        "Bangkok" to (13.756 to 100.502),
        "Tokio" to (35.690 to 139.692),
        "Sydney" to (-33.869 to 151.209),
    )
}
