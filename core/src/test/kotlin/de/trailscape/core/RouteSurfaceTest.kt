package de.trailscape.core

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests fuer den Schotteranteil (`RouteSurface.kt`).
 *
 * ## Die Fixtures
 * Unter `src/test/resources/brouter/` liegen **echte** Antworten fuer dieselbe
 * Strecke durch die Dresdner Heide (13,78° O / 51,093° N → 13,88° O /
 * 51,066° N), aufgenommen am 25.09.2026:
 *
 *  * `online-*.geojson` — brouter.de (BRouter 1.7.10) mit dem hochgeladenen
 *    Gravel-Profil, `trekking` und `shortest`.
 *  * `offline-*.geojson` — die eingebettete Engine (`routeOffline` bzw.
 *    derselbe `RoutingEngine`/`FormatJson`-Weg) mit `gravel.brf` und
 *    `shortest.brf` gegen die Kachel `E10_N50.rd5`.
 *
 * Sie sind der Nachweis, dass beide Wege `WayTags` liefern (siehe Datei-KDoc
 * von `RouteSurface.kt`), und halten fest, dass `shortest` kein `surface`
 * kennt. Klein genug fuers Repository (je rund 20 kB), weil die Strecke nur
 * rund 10 km lang ist.
 */
class RouteSurfaceTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/brouter/$name")?.readText()
            ?: fail("Fixture $name fehlt unter src/test/resources/brouter/")

    // -----------------------------------------------------------------------
    // Echte Antworten
    // -----------------------------------------------------------------------

    @Test
    fun `Serverantwort mit Gravel-Profil liefert den Schotteranteil`() {
        val route = parseBrouterGeoJson(fixture("online-gravel.geojson"))

        assertEquals(10.710, route.distanceKm, 1e-9)
        assertEquals(1.186, assertNotNull(route.pavedKm), 1e-9)
        assertEquals(9.511, assertNotNull(route.unpavedKm), 1e-9)
        // 9511 / (1186 + 9511) = 88,9 % — die Heide ist fast nur Forstweg.
        assertEquals(89, route.unpavedPercent)
        assertEquals("ca. 89 % unbefestigt", unpavedLabel(route))
    }

    @Test
    fun `eingebettete Engine liefert dieselben Belaege wie der Server`() {
        val online = parseBrouterGeoJson(fixture("online-gravel.geojson"))
        val offline = parseBrouterGeoJson(fixture("offline-gravel.geojson"))

        assertEquals(online.pavedKm, offline.pavedKm)
        assertEquals(online.unpavedKm, offline.unpavedKm)
        assertEquals(online.distanceKm, offline.distanceKm)
        assertEquals(89, offline.unpavedPercent)
    }

    @Test
    fun `trekking liefert ebenfalls Belaege`() {
        val route = parseBrouterGeoJson(fixture("online-trekking.geojson"))

        assertEquals(1.736, assertNotNull(route.pavedKm), 1e-9)
        assertEquals(9.600, assertNotNull(route.unpavedKm), 1e-9)
        assertEquals(85, route.unpavedPercent)
    }

    @Test
    fun `shortest kennt kein surface und faellt auf tracktype und highway zurueck`() {
        for (name in listOf("online-shortest.geojson", "offline-shortest.geojson")) {
            val body = fixture(name)
            // Festhalten, *warum* hier weniger klassifiziert wird: Das Profil
            // fragt `surface` nie ab, also gibt die Engine es nicht aus.
            assertTrue("surface=" !in body, "$name: shortest sollte kein surface ausgeben")

            val route = parseBrouterGeoJson(body)
            assertEquals(0.384, assertNotNull(route.pavedKm), 1e-9, name)
            assertEquals(7.257, assertNotNull(route.unpavedKm), 1e-9, name)
            // 1,45 km Pfade ohne Tracktyp bleiben unklassifiziert (16 %) —
            // unter der Ausblend-Schwelle, die Anzeige bleibt also.
            assertEquals(95, route.unpavedPercent, name)
        }
    }

    @Test
    fun `fetchRoute reicht den Belag der Serverantwort durch`() {
        val body = fixture("online-trekking.geojson")
        val route = fetchRoute(
            waypoints = listOf(Waypoint(51.093, 13.78), Waypoint(51.066, 13.88)),
            profileId = "trekking",
            client = { HttpResponse(200, body) },
            sleeper = {},
        )
        assertEquals(85, route.unpavedPercent)
    }

    // -----------------------------------------------------------------------
    // classifySurface
    // -----------------------------------------------------------------------

    @Test
    fun `classifySurface Tabelle`() {
        val cases = listOf(
            // surface: befestigt
            "highway=track surface=asphalt" to SurfaceClass.PAVED,
            "highway=path surface=paved" to SurfaceClass.PAVED,
            "highway=service surface=concrete" to SurfaceClass.PAVED,
            "highway=residential surface=paving_stones" to SurfaceClass.PAVED,
            "highway=cycleway surface=sett smoothness=intermediate" to SurfaceClass.PAVED,
            "highway=residential surface=cobblestone" to SurfaceClass.PAVED,
            "highway=footway surface=wood" to SurfaceClass.PAVED,
            // surface: unbefestigt
            "highway=track surface=gravel" to SurfaceClass.UNPAVED,
            "highway=cycleway surface=fine_gravel" to SurfaceClass.UNPAVED,
            "highway=track surface=compacted" to SurfaceClass.UNPAVED,
            "highway=path surface=dirt" to SurfaceClass.UNPAVED,
            "highway=path surface=ground" to SurfaceClass.UNPAVED,
            "highway=track surface=unpaved" to SurfaceClass.UNPAVED,
            "highway=track surface=grass" to SurfaceClass.UNPAVED,
            "highway=path surface=sand" to SurfaceClass.UNPAVED,
            "highway=path surface=earth" to SurfaceClass.UNPAVED,
            "highway=track surface=pebblestone" to SurfaceClass.UNPAVED,
            // surface schlaegt tracktype und highway
            "highway=track tracktype=grade3 surface=asphalt" to SurfaceClass.PAVED,
            "highway=secondary surface=gravel" to SurfaceClass.UNPAVED,
            // tracktype ohne surface
            "highway=track tracktype=grade1" to SurfaceClass.PAVED,
            "highway=track tracktype=grade2" to SurfaceClass.UNPAVED,
            "highway=track tracktype=grade3 smoothness=bad" to SurfaceClass.UNPAVED,
            "reversedirection=yes highway=track tracktype=grade4" to SurfaceClass.UNPAVED,
            "highway=path tracktype=grade5 foot=yes bicycle=yes" to SurfaceClass.UNPAVED,
            // highway allein: nur Strassen gelten als befestigt
            "highway=secondary smoothness=intermediate" to SurfaceClass.PAVED,
            "highway=residential route_bicycle_lcn=yes" to SurfaceClass.PAVED,
            "highway=service vehicle=no" to SurfaceClass.PAVED,
            "highway=unclassified" to SurfaceClass.PAVED,
            "highway=track" to SurfaceClass.UNKNOWN,
            "highway=path foot=yes" to SurfaceClass.UNKNOWN,
            "highway=cycleway" to SurfaceClass.UNKNOWN,
            "highway=footway" to SurfaceClass.UNKNOWN,
            // unbekannte Werte fallen zur naechsten Stufe durch
            "highway=track tracktype=unknown" to SurfaceClass.UNKNOWN,
            "highway=tertiary surface=woodchips" to SurfaceClass.PAVED,
            "highway=path surface=woodchips" to SurfaceClass.UNKNOWN,
            // kaputte oder leere Zelle
            "" to SurfaceClass.UNKNOWN,
            "   " to SurfaceClass.UNKNOWN,
            "surface= =gravel highway" to SurfaceClass.UNKNOWN,
        )
        for ((tags, expected) in cases) {
            assertEquals(expected, classifySurface(tags), "WayTags: '$tags'")
        }
    }

    // -----------------------------------------------------------------------
    // Kaputte oder fehlende messages
    // -----------------------------------------------------------------------

    /** Minimalantwort mit frei waehlbarem `messages`-Eintrag (roh als JSON). */
    private fun geoJson(messages: String?): String {
        val messagesPart = if (messages == null) "" else """, "messages": $messages"""
        return """
        {"type":"FeatureCollection","features":[{"type":"Feature",
          "properties":{"track-length":"1000","filtered ascend":"10"$messagesPart},
          "geometry":{"type":"LineString","coordinates":[[13.0,51.0,100],[13.01,51.0,101]]}}]}
        """
    }

    private val header =
        """["Longitude","Latitude","Elevation","Distance","CostPerKm","ElevCost","TurnCost",""" +
            """"NodeCost","InitialCost","WayTags","NodeTags","Time","Energy"]"""

    private fun row(distance: String, wayTags: String): String =
        """["13000000","51000000","100","$distance","1000","0","0","0","0","$wayTags","","0","0"]"""

    @Test
    fun `gueltige Minimaltabelle wird gelesen`() {
        val route = parseBrouterGeoJson(
            geoJson("[$header, ${row("600", "highway=track surface=gravel")}, ${row("400", "highway=tertiary")}]"),
        )
        assertEquals(0.4, assertNotNull(route.pavedKm), 1e-9)
        assertEquals(0.6, assertNotNull(route.unpavedKm), 1e-9)
        assertEquals(60, route.unpavedPercent)
    }

    @Test
    fun `fehlende oder kaputte messages ergeben null, die Route bleibt`() {
        val broken = listOf(
            null, // fehlt ganz
            "null",
            "\"kaputt\"",
            "{}",
            "[]", // nicht einmal eine Kopfzeile
            "[$header]", // nur Kopfzeile, keine Laenge
            // Kopfzeile ohne WayTags bzw. ohne Distance
            """[["Longitude","Latitude","Distance"], ["1","2","300"]]""",
            """[["Longitude","WayTags"], ["1","highway=track"]]""",
            // Zeile kein Array / zu kurz
            "[$header, \"x\"]",
            """[$header, ["13000000","51000000","100","300"]]""",
            // Laenge nicht lesbar, negativ, nicht endlich
            "[$header, ${row("abc", "highway=track surface=gravel")}]",
            "[$header, ${row("-5", "highway=track surface=gravel")}]",
            "[$header, ${row("NaN", "highway=track surface=gravel")}]",
            // eine gute, eine kaputte Zeile: alles oder nichts
            "[$header, ${row("500", "surface=gravel")}, ${row("x", "surface=asphalt")}]",
            // WayTags-Zelle kein Primitive
            """[$header, ["13000000","51000000","100","300","1000","0","0","0","0",{},"","0","0"]]""",
            // Gesamtlaenge 0
            "[$header, ${row("0", "highway=track surface=gravel")}]",
        )
        for (messages in broken) {
            val route = parseBrouterGeoJson(geoJson(messages))
            assertEquals(1.0, route.distanceKm, 1e-9, "messages=$messages")
            assertEquals(2, route.points.size, "messages=$messages")
            assertNull(route.pavedKm, "messages=$messages")
            assertNull(route.unpavedKm, "messages=$messages")
            assertNull(route.unpavedPercent, "messages=$messages")
            assertNull(unpavedLabel(route), "messages=$messages")
        }
    }

    @Test
    fun `surfaceBreakdownFromMessages direkt`() {
        assertNull(surfaceBreakdownFromMessages(null))
        val ok = surfaceBreakdownFromMessages(
            Json.parseToJsonElement("[$header, ${row("250", "highway=path")}, ${row("750", "surface=asphalt")}]"),
        )
        assertEquals(SurfaceBreakdown(pavedKm = 0.75, unpavedKm = 0.0, unknownKm = 0.25), ok)
    }

    // -----------------------------------------------------------------------
    // Anteil und Ausblenden
    // -----------------------------------------------------------------------

    @Test
    fun `mehr als die Haelfte unbekannt blendet den Anteil aus`() {
        // 10 km, davon 4,9 km klassifiziert → 51 % unbekannt.
        assertNull(unpavedPercent(distanceKm = 10.0, pavedKm = 1.0, unpavedKm = 3.9))
        // Genau die Haelfte unbekannt: noch sichtbar, Anteil an der
        // klassifizierten Strecke (3 von 5 km).
        assertEquals(60, unpavedPercent(distanceKm = 10.0, pavedKm = 2.0, unpavedKm = 3.0))
        // Nichts unbekannt.
        assertEquals(62, unpavedPercent(distanceKm = 50.0, pavedKm = 19.0, unpavedKm = 31.0))
    }

    @Test
    fun `unpavedPercent Randfaelle`() {
        assertNull(unpavedPercent(10.0, null, 5.0))
        assertNull(unpavedPercent(10.0, 5.0, null))
        assertNull(unpavedPercent(10.0, 0.0, 0.0))
        assertNull(unpavedPercent(10.0, -1.0, 5.0))
        assertNull(unpavedPercent(10.0, Double.NaN, 5.0))
        // Reine Strasse und reiner Schotter.
        assertEquals(0, unpavedPercent(10.0, 10.0, 0.0))
        assertEquals(100, unpavedPercent(10.0, 0.0, 10.0))
        // Fehlt die Gesamtdistanz (track-length 0), zaehlt die klassifizierte.
        assertEquals(50, unpavedPercent(0.0, 5.0, 5.0))
    }

    // -----------------------------------------------------------------------
    // Zusammensetzen von Legs
    // -----------------------------------------------------------------------

    private fun leg(lat: Double, paved: Double?, unpaved: Double?) = PlannedRoute(
        points = listOf(TrackPoint(lat = lat, lon = 13.0), TrackPoint(lat = lat + 0.1, lon = 13.0)),
        distanceKm = 10.0,
        ascentM = 50.0,
        pavedKm = paved,
        unpavedKm = unpaved,
    )

    @Test
    fun `concatRouteLegs summiert den Belag aller Legs`() {
        val merged = concatRouteLegs(listOf(leg(50.0, 4.0, 6.0), leg(50.1, 2.0, 8.0)))
        assertEquals(6.0, assertNotNull(merged.pavedKm), 1e-9)
        assertEquals(14.0, assertNotNull(merged.unpavedKm), 1e-9)
        assertEquals(70, merged.unpavedPercent)
    }

    @Test
    fun `ein Leg ohne Belag macht die ganze Summe null`() {
        val merged = concatRouteLegs(listOf(leg(50.0, 4.0, 6.0), leg(50.1, null, null), leg(50.2, 1.0, 9.0)))
        assertNull(merged.pavedKm)
        assertNull(merged.unpavedKm)
        assertNull(merged.unpavedPercent)
        // Distanz und Hoehe bleiben davon unberuehrt.
        assertEquals(30.0, merged.distanceKm, 1e-9)
    }

    // -----------------------------------------------------------------------
    // Gelaendeart
    // -----------------------------------------------------------------------

    @Test
    fun `terrainClass nutzt die Schwellen der Rundenbewertung`() {
        assertEquals(AscentPreference.FLACH, terrainClass(0.0))
        assertEquals(AscentPreference.FLACH, terrainClass(7.99))
        assertEquals(AscentPreference.MODERAT, terrainClass(8.0))
        assertEquals(AscentPreference.MODERAT, terrainClass(16.0))
        assertEquals(AscentPreference.BERGIG, terrainClass(16.01))
        assertEquals(AscentPreference.BERGIG, terrainClass(40.0))
        // Unsinn gilt als flach statt zu werfen.
        assertEquals(AscentPreference.FLACH, terrainClass(Double.NaN))
        assertEquals(AscentPreference.FLACH, terrainClass(-3.0))

        assertEquals("Flach", terrainLabel(5.0))
        assertEquals("Wellig", terrainLabel(12.0))
        assertEquals("Bergig", terrainLabel(20.0))
    }

    @Test
    fun `Gelaendeart stimmt mit der straffreien Zone der Bewertung ueberein`() {
        // Was als „Flach" beschriftet ist, kostet unter FLACH nichts; was als
        // „Wellig" beschriftet ist, kostet unter MODERAT nichts.
        for (mkm in listOf(0.0, 3.0, 7.9)) {
            assertEquals(AscentPreference.FLACH, terrainClass(mkm))
            assertEquals(0.0, ascentScore(mkm, AscentPreference.FLACH), 1e-9)
        }
        for (mkm in listOf(8.0, 12.0, 16.0)) {
            assertEquals(AscentPreference.MODERAT, terrainClass(mkm))
            assertEquals(0.0, ascentScore(mkm, AscentPreference.MODERAT), 1e-9)
        }
    }

    @Test
    fun `RouteCandidate reicht den Anteil seiner Route durch`() {
        val candidate = RouteCandidate(
            route = leg(50.0, 3.8, 6.2),
            distanceKm = 10.0,
            ascentM = 50.0,
            score = 0.0,
            bearingDeg = 0.0,
            targetKm = 10.0,
        )
        assertEquals(62, candidate.unpavedPercent)
    }

    // -----------------------------------------------------------------------
    // Von Hand: echte Kachel
    // -----------------------------------------------------------------------

    /**
     * Volltest gegen die eingebettete Engine — wie
     * `OfflineRoutingTest.manualRouteWithRealSegment` nur mit Kachel, also
     * von Hand:
     *
     * ```
     * TRAILSCAPE_SEGMENT_DIR=/pfad/mit/E10_N50.rd5 \
     *   ./gradlew :core:test --tests '*RouteSurfaceTest*'
     * ```
     *
     * Prueft, dass `routeOffline` den Belag durch denselben Parser bekommt.
     */
    @Test
    fun manualOfflineRouteCarriesSurface() {
        val dir = System.getenv("TRAILSCAPE_SEGMENT_DIR")
        if (dir.isNullOrBlank()) {
            println("RouteSurfaceTest: Volltest uebersprungen — TRAILSCAPE_SEGMENT_DIR nicht gesetzt.")
            return
        }
        val profileDir = File.createTempFile("trailscape-surface", "").apply {
            check(delete() && mkdirs())
            deleteOnExit()
        }
        val profile = File(profileDir, "gravel.brf").apply { writeText(gravelProfileText()) }
        lookupsDat().copyTo(File(profileDir, "lookups.dat"))

        val route = routeOffline(
            waypoints = listOf(Waypoint(51.0930, 13.7800), Waypoint(51.0660, 13.8800)),
            segmentDir = File(dir),
            profileFile = profile,
        )
        println("RouteSurfaceTest: offline ${route.unpavedPercent} % unbefestigt")
        assertNotNull(route.pavedKm)
        assertNotNull(route.unpavedKm)
        assertNotNull(route.unpavedPercent)
    }

    private fun lookupsDat(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "third_party/brouter/misc/profiles2/lookups.dat")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("lookups.dat nicht gefunden (BRouter-Submodul fehlt?)")
    }
}
