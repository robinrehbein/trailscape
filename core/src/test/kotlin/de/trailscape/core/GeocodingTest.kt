package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/geocoding.dart`.
 *
 * Direkt aus `test/geocoding_test.dart` uebernommen — gleiche Faelle, gleiche
 * Erwartungswerte.
 *
 * [HttpClient] wird durch ein Fake ersetzt (Analogon zu Darts
 * `package:http/testing.dart`-`MockClient`).
 */
class GeocodingTest {
    private companion object {
        const val EPS = 1e-9
    }

    @Test
    fun `baut die URL und Header korrekt auf`() {
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null
        val client = HttpClient { request ->
            capturedUrl = request.url
            capturedHeaders = request.headers
            HttpResponse(200, "[]")
        }

        searchPlaces("München", client)

        assertEquals(
            "https://nominatim.openstreetmap.org/search?q=M%C3%BCnchen&format=jsonv2&limit=5&accept-language=de",
            capturedUrl,
        )
        assertEquals(
            "Trailscape/1.0 (github.com/robinrehbein/trailscape)",
            capturedHeaders?.get("User-Agent"),
        )
    }

    @Test
    fun `kodiert Leerzeichen als Plus wie Darts Uri-encodeQueryComponent`() {
        var capturedUrl: String? = null
        val client = HttpClient { request ->
            capturedUrl = request.url
            HttpResponse(200, "[]")
        }

        searchPlaces("Bad Reichenhall", client)

        assertTrue(capturedUrl!!.contains("q=Bad+Reichenhall"))
    }

    @Test
    fun `Normalfall parst zwei Ergebnisse mit lat-lon als Strings`() {
        val client = HttpClient {
            HttpResponse(
                200,
                """
                [
                  {"display_name": "München, Bayern, Deutschland", "lat": "48.137154", "lon": "11.576124"},
                  {"display_name": "Münchenbernsdorf, Thüringen, Deutschland", "lat": "50.816", "lon": "12.048"}
                ]
                """,
            )
        }

        val results = searchPlaces("München", client)

        assertEquals(2, results.size)
        assertEquals("München, Bayern, Deutschland", results[0].displayName)
        assertEquals(48.137154, results[0].lat, EPS)
        assertEquals(11.576124, results[0].lon, EPS)
        assertEquals("Münchenbernsdorf, Thüringen, Deutschland", results[1].displayName)
        assertEquals(50.816, results[1].lat, EPS)
        assertEquals(12.048, results[1].lon, EPS)
    }

    @Test
    fun `ueberspringt unparsebare Eintraege`() {
        val client = HttpClient {
            HttpResponse(
                200,
                """
                [
                  {"display_name": "Gültig", "lat": "1.0", "lon": "2.0"},
                  {"display_name": "Kaputt", "lat": "nicht-numerisch", "lon": "2.0"},
                  {"display_name": "Fehlt lon", "lat": "1.0"},
                  {"lat": "1.0", "lon": "2.0"}
                ]
                """,
            )
        }

        val results = searchPlaces("irgendwas", client)

        assertEquals(1, results.size)
        assertEquals("Gültig", results.single().displayName)
    }

    @Test
    fun `leere Antwort ergibt leere Liste`() {
        val client = HttpClient { HttpResponse(200, "[]") }

        val results = searchPlaces("nirgendwo", client)

        assertTrue(results.isEmpty())
    }

    @Test
    fun `leerer Query loest keinen Request aus`() {
        val client = HttpClient { throw AssertionError("sollte nicht aufgerufen werden") }

        assertTrue(searchPlaces("", client).isEmpty())
        assertTrue(searchPlaces("   ", client).isEmpty())
    }

    @Test
    fun `wirft bei HTTP-Fehler`() {
        val client = HttpClient { HttpResponse(500, "Server explodiert") }

        val e = assertFailsWith<Exception> { searchPlaces("München", client) }
        assertEquals("Ortssuche fehlgeschlagen (HTTP 500).", e.message)
    }

    @Test
    fun `wirft bei kaputtem JSON`() {
        val client = HttpClient { HttpResponse(200, "kaputtes json{{{") }

        val e = assertFailsWith<Exception> { searchPlaces("München", client) }
        assertEquals("Unerwartete Antwort der Ortssuche.", e.message)
    }

    @Test
    fun `wirft bei Netzwerkfehler`() {
        val client = HttpClient { throw RuntimeException("Netzwerkfehler") }

        val e = assertFailsWith<Exception> { searchPlaces("München", client) }
        assertEquals("Ortssuche nicht erreichbar. Bist du online?", e.message)
    }
}
