package de.trailscape.core

import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `WeatherClient.kt` (Open-Meteo) und `RouteWindPreference.kt`.
 *
 * [HttpClient] ist ein Fake, der die Anfragen auffaengt — so laesst sich
 * pruefen, dass wirklich nur die gerundete Position das Geraet verlaesst.
 */
class WeatherClientTest {
    private companion object {
        val MUNICH = TrackPoint(lat = 48.13721, lon = 11.57559)

        const val EXPECTED_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=48.14&longitude=11.58" +
                "&current=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=kmh"

        /** Eine echte Open-Meteo-Antwort (gekuerzt auf die abgefragten Werte). */
        const val SAMPLE = """{"latitude":48.14,"longitude":11.58,"generationtime_ms":0.03,
            "utc_offset_seconds":0,"timezone":"GMT","timezone_abbreviation":"GMT","elevation":524.0,
            "current_units":{"time":"iso8601","interval":"seconds","wind_speed_10m":"km/h",
            "wind_direction_10m":"°","wind_gusts_10m":"km/h"},
            "current":{"time":"2026-09-27T10:00","interval":900,"wind_speed_10m":18.2,
            "wind_direction_10m":265,"wind_gusts_10m":35.3}}"""

        fun current(fields: String, units: String = "\"km/h\""): String =
            """{"current_units":{"wind_speed_10m":$units},"current":{$fields}}"""
    }

    private class FakeKeyValueStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun setString(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }

    // --- URL ---

    @Test
    fun `URL enthaelt nur die gerundeten Koordinaten`() {
        assertEquals(EXPECTED_URL, windForecastUrl(MUNICH))
    }

    @Test
    fun `URL bleibt unter deutschem Locale mit Punkt`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val url = windForecastUrl(MUNICH)
            assertEquals(EXPECTED_URL, url)
            assertFalse(url.contains("48,14"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `negative Koordinaten und Rundungsgrenzen`() {
        val url = windForecastUrl(TrackPoint(lat = -33.8688, lon = -151.2093))
        assertTrue(url.contains("latitude=-33.87&longitude=-151.21"), url)
        // Locale.ROOT-Format rundet HALF_UP auf der Dezimaldarstellung: 0,005 → 0.01.
        assertEquals("0.01", roundedCoordinate(0.005))
        assertEquals("0.00", roundedCoordinate(0.004))
        // Kein „-0.00" fuer winzige negative Werte.
        assertEquals("0.00", roundedCoordinate(-0.001))
        assertEquals("48.14,11.58", windCacheKey(MUNICH))
    }

    // --- Anfrage ---

    @Test
    fun `genau eine GET-Anfrage ohne Header und Body`() {
        val requests = mutableListOf<HttpRequest>()
        val client = HttpClient { req ->
            requests.add(req)
            HttpResponse(200, SAMPLE)
        }

        val wind = fetchCurrentWind(MUNICH, client)

        assertEquals(WindConditions(18.2, 265.0, 35.3), wind)
        assertEquals(1, requests.size)
        val req = requests.single()
        assertEquals(HttpMethod.GET, req.method)
        assertTrue(req.headers.isEmpty())
        assertNull(req.body)
        assertEquals(EXPECTED_URL, req.url)
        // Privatsphaere: die ungerundeten Nachkommastellen verlassen das Geraet nicht.
        assertFalse(req.url.contains("13721"))
        assertFalse(req.url.contains("57559"))
    }

    @Test
    fun `Fehler fuehren still zu null`() {
        assertNull(fetchCurrentWind(MUNICH, HttpClient { HttpResponse(500, SAMPLE) }))
        assertNull(fetchCurrentWind(MUNICH, HttpClient { HttpResponse(429, "") }))
        assertNull(fetchCurrentWind(MUNICH, HttpClient { throw IOException("offline") }))
        assertNull(fetchCurrentWind(MUNICH, HttpClient { throw RuntimeException("kaputt") }))
        assertNull(fetchCurrentWind(MUNICH, HttpClient { HttpResponse(200, "<html>") }))
    }

    @Test
    fun `nicht endliche Koordinaten schicken nichts`() {
        var calls = 0
        val client = HttpClient {
            calls += 1
            HttpResponse(200, SAMPLE)
        }
        assertNull(fetchCurrentWind(TrackPoint(Double.NaN, 11.0), client))
        assertEquals(0, calls)
    }

    @Test
    fun `Cancellation wird weitergeworfen`() {
        assertFailsWith<CancellationException> {
            fetchCurrentWind(MUNICH, HttpClient { throw CancellationException("weg") })
        }
    }

    // --- Parser ---

    @Test
    fun `parst eine echte Antwort`() {
        assertEquals(WindConditions(18.2, 265.0, 35.3), parseWindResponse(SAMPLE))
    }

    @Test
    fun `Richtung wird normalisiert, Boeen sind optional`() {
        assertEquals(
            WindConditions(12.0, 0.0, null),
            parseWindResponse(current("\"wind_speed_10m\":12,\"wind_direction_10m\":360")),
        )
        assertEquals(
            WindConditions(12.0, 270.0, null),
            parseWindResponse(current("\"wind_speed_10m\":12,\"wind_direction_10m\":-90")),
        )
        // Ohne current_units wird km/h angenommen (so wurde gefragt).
        assertEquals(
            WindConditions(12.0, 90.0, 20.0),
            parseWindResponse(
                """{"current":{"wind_speed_10m":12,"wind_direction_10m":90,"wind_gusts_10m":20}}""",
            ),
        )
        // Unbrauchbare Boeen machen nur die Boeen null.
        assertEquals(
            WindConditions(12.0, 90.0, null),
            parseWindResponse(current("\"wind_speed_10m\":12,\"wind_direction_10m\":90,\"wind_gusts_10m\":\"x\"")),
        )
    }

    @Test
    fun `unbrauchbare Antworten ergeben null`() {
        assertNull(parseWindResponse(current("\"wind_speed_10m\":12,\"wind_direction_10m\":90", "\"m/s\"")))
        assertNull(parseWindResponse("""{"current_units":{"wind_speed_10m":"km/h"}}"""))
        assertNull(parseWindResponse(current("\"wind_speed_10m\":\"abc\",\"wind_direction_10m\":90")))
        assertNull(parseWindResponse(current("\"wind_speed_10m\":\"12\",\"wind_direction_10m\":90")))
        assertNull(parseWindResponse(current("\"wind_speed_10m\":-1,\"wind_direction_10m\":90")))
        assertNull(parseWindResponse(current("\"wind_direction_10m\":90")))
        assertNull(parseWindResponse(current("\"wind_speed_10m\":12")))
        assertNull(parseWindResponse(current("\"wind_speed_10m\":null,\"wind_direction_10m\":90")))
        assertNull(parseWindResponse("kein JSON"))
        assertNull(parseWindResponse("[1,2,3]"))
        assertNull(parseWindResponse(""))
    }

    // --- Cache ---

    @Test
    fun `Wind wird 30 Minuten am selben Ort wiederverwendet`() {
        val key = windCacheKey(MUNICH)
        val t0 = 1_000_000_000L
        assertTrue(shouldReuseWind(key, t0, key, t0 + 10 * 60_000L))
        assertTrue(shouldReuseWind(key, t0, key, t0))
        assertFalse(shouldReuseWind(key, t0, key, t0 + 31 * 60_000L))
        assertFalse(shouldReuseWind(key, t0, key, t0 + WIND_REUSE_MS))
        assertFalse(shouldReuseWind(key, t0, "48.15,11.58", t0 + 60_000L))
        assertFalse(shouldReuseWind(null, t0, key, t0 + 60_000L))
        // Zurueckgestellte Uhr.
        assertFalse(shouldReuseWind(key, t0, key, t0 - 1))
    }

    // --- Einstellung ---

    @Test
    fun `Schalter ist ab Werk aus und wird exakt gespeichert`() {
        val store = FakeKeyValueStore()
        assertFalse(readRouteWindEnabled(store))

        writeRouteWindEnabled(store, true)
        assertTrue(readRouteWindEnabled(store))
        assertEquals("1", store.values[ROUTE_WIND_STORAGE_KEY])

        writeRouteWindEnabled(store, false)
        assertFalse(readRouteWindEnabled(store))
        assertFalse(ROUTE_WIND_STORAGE_KEY in store.values)

        store.values[ROUTE_WIND_STORAGE_KEY] = "true"
        assertFalse(readRouteWindEnabled(store))
    }
}
