package de.trailscape.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/routing.dart`.
 *
 * Direkt aus `test/routing_test.dart` uebernommen — gleiche Faelle, gleiche
 * Erwartungswerte. Die `gravelProfileText`-Gruppe ist NICHT hier, sondern
 * bereits in [BrouterProfilesTest] portiert (siehe dort).
 *
 * [HttpClient] wird durch ein Fake ersetzt, das eingehende [HttpRequest]s
 * inspiziert und vorbereitete [HttpResponse]s zurueckgibt — das Analogon zu
 * Darts `package:http/testing.dart`-`MockClient`.
 */
class RoutingTest {
    private companion object {
        const val EPS = 1e-9

        const val SAMPLE_GEO_JSON = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {
                "creator": "BRouter-1.7.0",
                "track-length": "1234",
                "filtered ascend": "56.7",
                "plain-ascend": "50"
              },
              "geometry": {
                "type": "LineString",
                "coordinates": [
                  [11.111111, 48.111111, 500.0],
                  [11.222222, 48.222222],
                  [11.333333, 48.333333, 520.5]
                ]
              }
            }
          ]
        }
        """

        /** Wirft bei jedem Request — fuer Faelle, die keinen Netzwerkaufruf erwarten. */
        fun failingClient(): HttpClient = HttpClient { throw AssertionError("sollte nicht aufgerufen werden") }
    }

    // --- parseBrouterGeoJson ---

    @Test
    fun `parst Koordinaten und String-Properties`() {
        val route = parseBrouterGeoJson(SAMPLE_GEO_JSON)

        assertEquals(3, route.points.size)
        assertEquals(48.111111, route.points[0].lat, EPS)
        assertEquals(11.111111, route.points[0].lon, EPS)
        assertEquals(500.0, route.points[0].ele!!, EPS)
        // Fehlende ele -> null statt 0.
        assertNull(route.points[1].ele)
        assertEquals(520.5, route.points[2].ele!!, EPS)

        // "track-length" (String) in Metern -> distanceKm.
        assertEquals(1.234, route.distanceKm, EPS)
        // "filtered ascend" (String) -> ascentM.
        assertEquals(56.7, route.ascentM, EPS)
    }

    @Test
    fun `fehlende Properties werden zu 0`() {
        val body = """
        {
          "features": [
            {
              "geometry": {"coordinates": [[1.0, 2.0]]},
              "properties": {}
            }
          ]
        }
        """
        val route = parseBrouterGeoJson(body)
        assertEquals(0.0, route.distanceKm, EPS)
        assertEquals(0.0, route.ascentM, EPS)
    }

    @Test
    fun `wirft bei kaputtem JSON`() {
        val e = assertFailsWith<Exception> { parseBrouterGeoJson("not json{") }
        assertEquals("Unerwartete Antwort vom Routing-Server.", e.message)
    }

    @Test
    fun `wirft bei unerwartetem Format fehlende features`() {
        val e = assertFailsWith<Exception> { parseBrouterGeoJson("""{"foo": "bar"}""") }
        assertEquals("Unerwartete Antwort vom Routing-Server.", e.message)
    }

    // --- brouterProfile ---

    @Test
    fun `bildet jedes RouteProfile 1 zu 1 auf seine Profil-ID ab`() {
        assertEquals("trekking", brouterProfile(RouteProfile.GRAVEL))
        assertEquals("custom:gravel", brouterProfile(RouteProfile.SCHOTTER))
        assertEquals("fastbike", brouterProfile(RouteProfile.ASPHALT))
        assertEquals("safety", brouterProfile(RouteProfile.RADWEGE))
        assertEquals("shortest", brouterProfile(RouteProfile.KUERZESTER))
    }

    @Test
    fun `liefert fuer jedes RouteProfile einen nicht-leeren Profilnamen`() {
        for (profile in RouteProfile.entries) {
            assertTrue(brouterProfile(profile).isNotEmpty())
        }
    }

    @Test
    fun `jedes Routenprofil hat ein Label`() {
        for (profile in RouteProfile.entries) {
            assertNotNull(routeProfileLabels[profile])
        }
        // Die Beschriftung muss zum tatsaechlich benutzten BRouter-Profil
        // passen: SCHOTTER faehrt das eingebettete Gravel-Custom-Profil und
        // heisst deshalb „Gravel", GRAVEL faehrt `trekking` und heisst so.
        assertTrue(routeProfileLabels.getValue(RouteProfile.SCHOTTER).startsWith("Gravel"))
        assertEquals(CUSTOM_GRAVEL_PROFILE, brouterProfile(RouteProfile.SCHOTTER))
        assertTrue(routeProfileLabels.getValue(RouteProfile.GRAVEL).startsWith("Trekking"))
        assertEquals("trekking", brouterProfile(RouteProfile.GRAVEL))
        // Kein anderer Modus darf „Gravel" fuer sich beanspruchen.
        assertEquals(
            listOf(RouteProfile.SCHOTTER),
            routeProfileLabels.filterValues { it.contains("Gravel") }.keys.toList(),
        )
        // Das echte Gravel-Profil steht zuerst im Dropdown.
        assertEquals(RouteProfile.SCHOTTER, routeProfileLabels.keys.first())
    }

    // --- fetchRoute ---

    @Test
    fun `wirft bei weniger als 2 Wegpunkten ohne Netzwerkaufruf`() {
        val e = assertFailsWith<Exception> {
            fetchRoute(listOf(Waypoint(lat = 48.1, lon = 11.1)), "trekking", failingClient())
        }
        assertEquals("Mindestens zwei Wegpunkte nötig.", e.message)
    }

    @Test
    fun `baut die URL mit lon,lat 6 Nachkommastellen und Profil auf`() {
        var capturedUrl: String? = null
        val client = HttpClient { request ->
            capturedUrl = request.url
            HttpResponse(200, SAMPLE_GEO_JSON)
        }

        val waypoints = listOf(
            Waypoint(lat = 48.1, lon = 11.1),
            Waypoint(lat = 48.2, lon = 11.2),
        )

        val route = fetchRoute(waypoints, "fastbike", client)

        assertEquals(
            "https://brouter.de/brouter?lonlats=11.100000,48.100000|11.200000,48.200000" +
                "&profile=fastbike&alternativeidx=0&format=geojson",
            capturedUrl,
        )
        assertEquals(3, route.points.size)
    }

    @Test
    fun `wirft bei HTTP-Fehler mit Servertext`() {
        val client = HttpClient { HttpResponse(500, "Server explodiert") }

        val e = assertFailsWith<Exception> {
            fetchRoute(
                listOf(Waypoint(lat = 48.1, lon = 11.1), Waypoint(lat = 48.2, lon = 11.2)),
                "trekking",
                client,
            )
        }
        // Unbekannte Servertexte werden nicht mehr roh durchgereicht, sondern in
        // eine deutsche Meldung eingebettet — der Originaltext bleibt in
        // Klammern erhalten, damit Bugreports diagnostizierbar bleiben.
        assertEquals("Route konnte nicht berechnet werden. (Servermeldung: Server explodiert)", e.message)
    }

    @Test
    fun `wirft bei kaputtem JSON in der Antwort`() {
        val client = HttpClient { HttpResponse(200, "kaputtes json{{{") }

        val e = assertFailsWith<Exception> {
            fetchRoute(
                listOf(Waypoint(lat = 48.1, lon = 11.1), Waypoint(lat = 48.2, lon = 11.2)),
                "shortest",
                client,
            )
        }
        assertEquals("Unerwartete Antwort vom Routing-Server.", e.message)
    }

    @Test
    fun `wirft bei Netzwerkfehler`() {
        val client = HttpClient { throw RuntimeException("Netzwerkfehler") }

        val e = assertFailsWith<Exception> {
            fetchRoute(
                listOf(Waypoint(lat = 48.1, lon = 11.1), Waypoint(lat = 48.2, lon = 11.2)),
                "trekking",
                client,
            )
        }
        assertEquals("Routing-Server nicht erreichbar. Bist du online?", e.message)
    }

    // --- fetchRoute mit Custom-Gravel-Profil ---

    private val customWaypoints = listOf(
        Waypoint(lat = 48.1, lon = 11.1),
        Waypoint(lat = 48.2, lon = 11.2),
    )

    @BeforeTest
    fun resetCache() = resetCustomProfileCacheForTesting()

    @AfterTest
    fun resetCacheAfter() = resetCustomProfileCacheForTesting()

    @Test
    fun `laedt das Profil hoch und routet mit der zurueckgegebenen ID`() {
        val uploadBodies = mutableListOf<String>()
        val routedProfiles = mutableListOf<String>()

        val client = HttpClient { request ->
            when {
                request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile") -> {
                    uploadBodies.add(request.body ?: "")
                    HttpResponse(200, """{"profileid":"custom_1234","error":""}""")
                }
                request.method == HttpMethod.GET && request.url.startsWith("https://brouter.de/brouter?") -> {
                    routedProfiles.add(profileParam(request.url))
                    HttpResponse(200, SAMPLE_GEO_JSON)
                }
                else -> throw AssertionError("unerwarteter Request: ${request.method} ${request.url}")
            }
        }

        val route = fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)

        assertEquals(1, uploadBodies.size)
        assertTrue(uploadBodies.single().contains("prefer_unpaved_paths"))
        assertTrue(uploadBodies.single().contains("assign prefer_unpaved_paths true"))
        assertEquals(listOf("custom_1234"), routedProfiles)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `zweiter Aufruf nutzt die gecachte profileid`() {
        var uploads = 0
        val routedProfiles = mutableListOf<String>()

        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile")) {
                uploads++
                HttpResponse(200, """{"profileid":"custom_abc"}""")
            } else {
                routedProfiles.add(profileParam(request.url))
                HttpResponse(200, SAMPLE_GEO_JSON)
            }
        }

        fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)
        fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)

        assertEquals(1, uploads)
        assertEquals(listOf("custom_abc", "custom_abc"), routedProfiles)
    }

    @Test
    fun `laedt nach Routing-Fehler neu hoch und wiederholt einmal`() {
        var uploads = 0
        val routedProfiles = mutableListOf<String>()

        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile")) {
                uploads++
                HttpResponse(200, """{"profileid":"custom_v$uploads"}""")
            } else {
                val profile = profileParam(request.url)
                routedProfiles.add(profile)
                // Die erste (angeblich verworfene) ID schlägt fehl.
                if (profile == "custom_v1") {
                    HttpResponse(500, "profile not found")
                } else {
                    HttpResponse(200, SAMPLE_GEO_JSON)
                }
            }
        }

        val route = fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)

        assertEquals(2, uploads)
        assertEquals(listOf("custom_v1", "custom_v2"), routedProfiles)
        assertEquals(3, route.points.size)

        // Nach dem erfolgreichen Retry ist die neue ID gecacht.
        fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)
        assertEquals(2, uploads)
        assertEquals(listOf("custom_v1", "custom_v2", "custom_v2"), routedProfiles)
    }

    @Test
    fun `faellt bei fehlgeschlagenem Upload auf trekking zurueck`() {
        val routedProfiles = mutableListOf<String>()

        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile")) {
                HttpResponse(500, "upload kaputt")
            } else {
                routedProfiles.add(profileParam(request.url))
                HttpResponse(200, SAMPLE_GEO_JSON)
            }
        }

        val route = fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)

        assertEquals(listOf("trekking"), routedProfiles)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `faellt bei Fehler-Feld in der Upload-Antwort auf trekking zurueck`() {
        val routedProfiles = mutableListOf<String>()

        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile")) {
                HttpResponse(200, """{"error":"syntax error"}""")
            } else {
                routedProfiles.add(profileParam(request.url))
                HttpResponse(200, SAMPLE_GEO_JSON)
            }
        }

        val route = fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)

        assertEquals(listOf("trekking"), routedProfiles)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `faellt auf trekking zurueck, wenn auch der Retry scheitert`() {
        var uploads = 0
        val routedProfiles = mutableListOf<String>()

        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile")) {
                uploads++
                HttpResponse(200, """{"profileid":"custom_v$uploads"}""")
            } else {
                val profile = profileParam(request.url)
                routedProfiles.add(profile)
                if (profile.startsWith("custom_")) {
                    HttpResponse(500, "profile not found")
                } else {
                    HttpResponse(200, SAMPLE_GEO_JSON)
                }
            }
        }

        val route = fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client)

        assertEquals(2, uploads)
        assertEquals(listOf("custom_v1", "custom_v2", "trekking"), routedProfiles)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `wirft, wenn auch trekking fehlschlaegt`() {
        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST && request.url.startsWith("https://brouter.de/brouter/profile")) {
                HttpResponse(500, "nope")
            } else {
                HttpResponse(500, "Server explodiert")
            }
        }

        val e = assertFailsWith<Exception> { fetchRoute(customWaypoints, CUSTOM_GRAVEL_PROFILE, client) }
        assertEquals("Route konnte nicht berechnet werden. (Servermeldung: Server explodiert)", e.message)
    }

    // -----------------------------------------------------------------------
    // Watchdog: Retry und deutsche Meldung
    // -----------------------------------------------------------------------

    /** Der Text, mit dem brouter.de eine per Lastabwurf gekillte Anfrage quittiert. */
    private val watchdogBody = "operation killed by thread-priority-watchdog after 1 seconds"

    private val shortWaypoints = listOf(
        Waypoint(lat = 48.1372, lon = 11.5756),
        Waypoint(lat = 48.3705, lon = 10.8978),
    )

    @Test
    fun `wiederholt nach Watchdog-Abbruch genau einmal und liefert dann die Route`() {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val client = HttpClient {
            calls += 1
            if (calls == 1) HttpResponse(400, watchdogBody) else HttpResponse(200, SAMPLE_GEO_JSON)
        }

        val route = fetchRoute(shortWaypoints, "trekking", client, sleeper = { pauses.add(it) })

        assertEquals(2, calls)
        assertEquals(listOf(watchdogRetryPauseMs), pauses)
        assertEquals(3, route.points.size)
    }

    @Test
    fun `gibt nach dem zweiten Watchdog-Abbruch mit deutscher Meldung auf`() {
        var calls = 0
        val client = HttpClient {
            calls += 1
            HttpResponse(400, watchdogBody)
        }

        val e = assertFailsWith<Exception> {
            fetchRoute(shortWaypoints, "trekking", client, sleeper = {})
        }

        // Genau ein Retry — danach sauber scheitern, statt den Server weiter zu belasten.
        assertEquals(2, calls)
        assertEquals(errorServerOverloaded, e.message)
        assertTrue(e.message!!.contains("näheren Wegpunkten"))
        // Der rohe Servertext taucht nicht mehr im UI auf.
        assertTrue(!e.message!!.contains("watchdog"))
    }

    @Test
    fun `behandelt den Server-Timeout wie den Watchdog`() {
        var calls = 0
        val client = HttpClient {
            calls += 1
            HttpResponse(400, "operation timeout after 60 seconds")
        }

        val e = assertFailsWith<Exception> {
            fetchRoute(shortWaypoints, "trekking", client, sleeper = {})
        }

        assertEquals(2, calls)
        assertEquals(errorServerOverloaded, e.message)
    }

    @Test
    fun `wiederholt bei anderen Serverfehlern nicht`() {
        var calls = 0
        val client = HttpClient {
            calls += 1
            HttpResponse(400, "position not mapped in existing datafile")
        }

        val e = assertFailsWith<Exception> {
            fetchRoute(shortWaypoints, "trekking", client, sleeper = {})
        }

        assertEquals(1, calls)
        assertEquals(
            "Route konnte nicht berechnet werden. (Servermeldung: position not mapped in existing datafile)",
            e.message,
        )
    }

    @Test
    fun `kuerzt sehr lange Servertexte in der Meldung`() {
        val long = "x".repeat(500)
        val message = routingErrorMessage(long)

        assertTrue(message.startsWith("Route konnte nicht berechnet werden. (Servermeldung: "))
        assertTrue(message.endsWith("…)"))
        assertTrue(message.length < 300, "zu lang: ${message.length}")
    }

    @Test
    fun `leerer Servertext ergibt die generische Meldung ohne Klammern`() {
        assertEquals(errorRouteFailed, routingErrorMessage("   \n "))
    }

    @Test
    fun `Watchdog beim Custom-Profil laedt nicht sinnlos neu hoch`() {
        var uploads = 0
        var routeCalls = 0
        val client = HttpClient { request ->
            if (request.method == HttpMethod.POST) {
                uploads += 1
                HttpResponse(200, """{"profileid":"custom_x"}""")
            } else {
                routeCalls += 1
                HttpResponse(400, watchdogBody)
            }
        }

        val e = assertFailsWith<Exception> {
            fetchRoute(shortWaypoints, CUSTOM_GRAVEL_PROFILE, client, sleeper = {})
        }

        assertEquals(errorServerOverloaded, e.message)
        assertEquals(1, uploads)
        // Ein Versuch plus ein Retry — kein zweiter Upload, kein trekking-Fallback.
        assertEquals(2, routeCalls)
    }

    // -----------------------------------------------------------------------
    // Leg-Splitting
    // -----------------------------------------------------------------------

    @Test
    fun `kurze Route ergibt genau eine Server-Anfrage`() {
        val counter = CountingBrouter()

        fetchRoute(shortWaypoints, "trekking", counter, sleeper = {})

        assertEquals(1, counter.routeRequests)
        assertEquals(listOf(shortWaypoints), planRouteLegs(shortWaypoints))
    }

    @Test
    fun `mehrere nahe Wegpunkte bleiben eine einzige Anfrage mit Via-Punkten`() {
        val counter = CountingBrouter()
        val waypoints = listOf(
            Waypoint(lat = 48.1372, lon = 11.5756),
            Waypoint(lat = 48.2500, lon = 11.4000),
            Waypoint(lat = 48.3705, lon = 10.8978),
        )

        fetchRoute(waypoints, "trekking", counter, sleeper = {})

        assertEquals(1, counter.routeRequests)
        assertEquals(3, counter.legWaypoints.single().size)
    }

    @Test
    fun `weit auseinanderliegende Wegpunkte werden in kurze Einzel-Legs zerlegt`() {
        val counter = CountingBrouter()

        // Hamburg -> Muenchen: rund 610 km Luftlinie.
        val legs = planRouteLegs(listOf(HAMBURG, MUENCHEN))

        assertEquals(5, legs.size)
        for (leg in legs) {
            // Jede Anfrage hat genau zwei Punkte — ein Request mit vielen
            // Via-Punkten liefe weiterhin am Stueck in einem Server-Thread.
            assertEquals(2, leg.size)
            val km = airDistanceM(leg[0], leg[1]) / 1000
            assertTrue(km <= maxLegAirDistanceKm, "Teil-Leg ist $km km lang")
        }

        fetchRoute(listOf(HAMBURG, MUENCHEN), "trekking", counter, sleeper = {})
        assertEquals(5, counter.routeRequests)
    }

    @Test
    fun `Zwischenpunkte liegen auf der Geodaete zwischen Start und Ziel`() {
        val chain = splitLegOnGeodesic(HAMBURG, MUENCHEN)

        assertEquals(6, chain.size)
        assertEquals(HAMBURG, chain.first())
        assertEquals(MUENCHEN, chain.last())

        val direct = airDistanceM(HAMBURG, MUENCHEN)
        var along = 0.0
        for (i in 1 until chain.size) {
            along += airDistanceM(chain[i - 1], chain[i])
        }
        // Auf dem Grosskreis ist die Summe der Teilstuecke die Gesamtstrecke.
        assertEquals(direct, along, direct * 1e-6)
    }

    @Test
    fun `unterhalb der Schwelle gibt es keine Zwischenpunkte`() {
        val chain = splitLegOnGeodesic(shortWaypoints[0], shortWaypoints[1])
        assertEquals(2, chain.size)

        // Auch knapp unterhalb der Schwelle: 149 km bleiben ungeteilt, 151 km nicht.
        val a = Waypoint(lat = 48.0, lon = 11.0)
        assertEquals(2, splitLegOnGeodesic(a, geodesicPointAtKm(a, 149.0)).size)
        assertEquals(3, splitLegOnGeodesic(a, geodesicPointAtKm(a, 151.0)).size)
    }

    @Test
    fun `identische Wegpunkte erzeugen keine Zwischenpunkte`() {
        val p = Waypoint(lat = 48.0, lon = 11.0)
        assertEquals(listOf(p, p), splitLegOnGeodesic(p, p))
    }

    // -----------------------------------------------------------------------
    // Zusammensetzen der Teilrouten
    // -----------------------------------------------------------------------

    @Test
    fun `summiert Distanz und Hoehenmeter und entdoppelt die Nahtstellen`() {
        val a = PlannedRoute(
            points = listOf(
                TrackPoint(lat = 48.0, lon = 11.0),
                TrackPoint(lat = 48.5, lon = 11.5),
            ),
            distanceKm = 10.0,
            ascentM = 100.0,
        )
        val b = PlannedRoute(
            points = listOf(
                // Nahtstelle: identisch mit dem letzten Punkt von a.
                TrackPoint(lat = 48.5, lon = 11.5),
                TrackPoint(lat = 49.0, lon = 12.0),
            ),
            distanceKm = 20.0,
            ascentM = 250.0,
        )

        val merged = concatRouteLegs(listOf(a, b))

        assertEquals(30.0, merged.distanceKm, EPS)
        assertEquals(350.0, merged.ascentM, EPS)
        assertEquals(3, merged.points.size)
        assertEquals(48.0, merged.points[0].lat, EPS)
        assertEquals(48.5, merged.points[1].lat, EPS)
        assertEquals(49.0, merged.points[2].lat, EPS)
    }

    @Test
    fun `behaelt Punkte, wenn die Nahtstelle nicht zusammenfaellt`() {
        val a = PlannedRoute(listOf(TrackPoint(lat = 48.0, lon = 11.0)), 1.0, 1.0)
        val b = PlannedRoute(listOf(TrackPoint(lat = 48.1, lon = 11.0)), 1.0, 1.0)

        assertEquals(2, concatRouteLegs(listOf(a, b)).points.size)
    }

    @Test
    fun `setzt eine zerlegte Route lueckenlos und ohne Doppelpunkte zusammen`() {
        // Jeder Teil-Request meldet 100 km und 50 Hm und liefert seine beiden
        // Wegpunkte als Geometrie zurueck — wie der echte Server, der an den
        // Nahtstellen denselben Punkt zweimal liefern wuerde.
        val client = CountingBrouter(distanceM = 100_000.0, ascentM = 50.0)

        val route = fetchRoute(listOf(HAMBURG, MUENCHEN), "trekking", client, sleeper = {})

        assertEquals(5, client.routeRequests)
        assertEquals(500.0, route.distanceKm, EPS)
        assertEquals(250.0, route.ascentM, EPS)
        // 5 Legs a 2 Punkte, 4 Nahtstellen entdoppelt.
        assertEquals(6, route.points.size)
        for (i in 1 until route.points.size) {
            assertTrue(
                haversineM(route.points[i - 1], route.points[i]) > 1.0,
                "doppelter Punkt an Index $i",
            )
        }
        assertEquals(HAMBURG.lat, route.points.first().lat, 1e-5)
        assertEquals(MUENCHEN.lat, route.points.last().lat, 1e-5)
    }

    @Test
    fun `pausiert zwischen den Teil-Anfragen, davor nicht`() {
        val pauses = mutableListOf<Long>()
        val client = CountingBrouter()

        fetchRoute(listOf(HAMBURG, MUENCHEN), "trekking", client, sleeper = { pauses.add(it) })

        assertEquals(5, client.routeRequests)
        assertEquals(List(4) { legRequestPauseMs }, pauses)
    }

    @Test
    fun `meldet den Fortschritt ueber die Legs`() {
        val progress = mutableListOf<Pair<Int, Int>>()

        fetchRoute(
            listOf(HAMBURG, MUENCHEN),
            "trekking",
            CountingBrouter(),
            sleeper = {},
            onProgress = { done, total -> progress.add(done to total) },
        )

        assertEquals(listOf(0 to 5, 1 to 5, 2 to 5, 3 to 5, 4 to 5, 5 to 5), progress)
    }

    @Test
    fun `das Custom-Profil wird fuer alle Legs nur einmal hochgeladen`() {
        val client = CountingBrouter()

        fetchRoute(listOf(HAMBURG, MUENCHEN), CUSTOM_GRAVEL_PROFILE, client, sleeper = {})

        assertEquals(1, client.profileUploads)
        assertEquals(5, client.routeRequests)
    }
}

/** Hamburg — rund 610 km Luftlinie von [MUENCHEN] entfernt. */
private val HAMBURG = Waypoint(lat = 53.5511, lon = 9.9937)

/** Muenchen. */
private val MUENCHEN = Waypoint(lat = 48.1372, lon = 11.5756)

/** Punkt [km] noerdlich von [from] — nur fuer Schwellwert-Tests. */
private fun geodesicPointAtKm(from: Waypoint, km: Double): Waypoint =
    Waypoint(lat = from.lat + km / 111.19492664455873, lon = from.lon)

/**
 * Fake-Server, der Routing-Anfragen zaehlt, ihre Wegpunkte mitschreibt und
 * eine synthetische GeoJSON-Antwort mit genau diesen Wegpunkten als Geometrie
 * liefert — damit lassen sich Nahtstellen und Summen pruefen.
 */
private class CountingBrouter(
    val distanceM: Double = 1000.0,
    val ascentM: Double = 10.0,
) : HttpClient {
    var routeRequests = 0
    var profileUploads = 0
    val legWaypoints = mutableListOf<List<Waypoint>>()

    override fun execute(request: HttpRequest): HttpResponse {
        if (request.method == HttpMethod.POST) {
            profileUploads += 1
            return HttpResponse(200, """{"profileid":"fake-gravel"}""")
        }
        routeRequests += 1

        val raw = request.url.substringAfter("lonlats=").substringBefore("&")
        val points = raw.split("|").map { pair ->
            val parts = pair.split(",")
            Waypoint(lat = parts[1].toDouble(), lon = parts[0].toDouble())
        }
        legWaypoints.add(points)

        val coords = points.joinToString(",") { "[${it.lon},${it.lat},500.0]" }
        return HttpResponse(
            200,
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "track-length": "${distanceM.toInt()}",
                    "filtered ascend": "${ascentM.toInt()}"
                  },
                  "geometry": { "type": "LineString", "coordinates": [$coords] }
                }
              ]
            }
            """,
        )
    }
}

/** Liest den `profile`-Query-Parameter aus einer BRouter-Routing-URL. */
private fun profileParam(url: String): String {
    val match = Regex("[?&]profile=([^&]*)").find(url)
        ?: throw AssertionError("keine profile-Query in $url")
    return match.groupValues[1]
}
