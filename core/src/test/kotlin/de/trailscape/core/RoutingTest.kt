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
        assertEquals("Schotter & Kieswege", routeProfileLabels[RouteProfile.SCHOTTER])
        // Gravel steht zuerst, Schotter direkt danach im Dropdown.
        val keys = routeProfileLabels.keys.toList()
        assertEquals(RouteProfile.GRAVEL, keys[0])
        assertEquals(RouteProfile.SCHOTTER, keys[1])
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
        assertEquals("Route konnte nicht berechnet werden: Server explodiert", e.message)
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
        assertEquals("Route konnte nicht berechnet werden: Server explodiert", e.message)
    }
}

/** Liest den `profile`-Query-Parameter aus einer BRouter-Routing-URL. */
private fun profileParam(url: String): String {
    val match = Regex("[?&]profile=([^&]*)").find(url)
        ?: throw AssertionError("keine profile-Query in $url")
    return match.groupValues[1]
}
