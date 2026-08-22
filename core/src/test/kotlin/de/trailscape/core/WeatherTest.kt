package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `Weather.kt` — Abruf der Open-Meteo-Prognose und die Suche nach
 * dem besten Fahrfenster.
 *
 * [HttpClient] ist ein Fake (siehe `RoutingTest`): Es prueft die gebaute URL
 * und spielt vorbereitete Koerper zurueck.
 */
class WeatherTest {
    private companion object {
        const val HOUR_MS = 3_600_000L

        /** 2026-08-22T12:00:00Z als ms — ein fester „jetzt"-Zeitstempel. */
        const val NOW_MS = 1_787_428_800_000L

        fun hour(
            offsetH: Long,
            tempC: Double = 18.0,
            precipProbPct: Int? = 0,
            precipMm: Double? = 0.0,
            windKmh: Double? = 10.0,
        ): HourWeather = HourWeather(
            timeMs = NOW_MS + offsetH * HOUR_MS,
            tempC = tempC,
            precipProbPct = precipProbPct,
            precipMm = precipMm,
            windKmh = windKmh,
        )

        fun forecast(vararg hours: HourWeather) = WeatherForecast(hours.toList())

        /** Minimaler Open-Meteo-Koerper: Zeit und Temperatur, mehr nicht. */
        fun openMeteoBody(times: String, temps: String): String =
            """{"hourly":{"time":[$times],"temperature_2m":[$temps]}}"""
    }

    // --- fetchWeatherForecast ---

    @Test
    fun `baut die Open-Meteo-URL mit allen stündlichen Feldern`() {
        var capturedUrl: String? = null
        val client = HttpClient { request ->
            capturedUrl = request.url
            HttpResponse(
                200,
                openMeteoBody(
                    times = "1787428800,1787432400",
                    temps = "18.2,19.0",
                ),
            )
        }

        fetchWeatherForecast(52.52, 13.405, client)

        val url = capturedUrl!!
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue(url.contains("latitude=52.52"))
        assertTrue(url.contains("longitude=13.405"))
        assertTrue(url.contains("hourly=temperature_2m,precipitation_probability,precipitation,windspeed_10m"))
        assertTrue(url.contains("timeformat=unixtime"))
    }

    @Test
    fun `parst Unix-Zeiten in ms und ordnet optionale Felder zu`() {
        val body = """
            {"hourly":{
              "time":[1787428800,1787432400],
              "temperature_2m":[18.2,19.0],
              "precipitation_probability":[10,null],
              "precipitation":[0.0,0.3],
              "windspeed_10m":[8.1,12.4]
            }}
        """.trimIndent()
        val forecast = fetchWeatherForecast(52.0, 13.0, HttpClient { HttpResponse(200, body) })

        assertEquals(2, forecast.hours.size)
        assertEquals(1_787_428_800_000L, forecast.hours[0].timeMs)
        assertEquals(18.2, forecast.hours[0].tempC, 1e-9)
        assertEquals(10, forecast.hours[0].precipProbPct)
        assertNull(forecast.hours[1].precipProbPct)
        assertEquals(0.3, forecast.hours[1].precipMm!!, 1e-9)
        assertEquals(12.4, forecast.hours[1].windKmh!!, 1e-9)
    }

    @Test
    fun `Netzwerkfehler werden zu einer deutschen Meldung`() {
        val client = HttpClient { throw java.io.IOException("offline") }

        val error = assertFailsWith<Exception> { fetchWeatherForecast(52.0, 13.0, client) }
        assertEquals("Wetterdienst nicht erreichbar. Bist du online?", error.message)
    }

    @Test
    fun `HTTP-Fehler und ungueltiger Koerper werden zu einer Meldung`() {
        assertEquals(
            "Wetterdienst antwortete mit HTTP 503.",
            assertFailsWith<Exception> {
                fetchWeatherForecast(52.0, 13.0, HttpClient { HttpResponse(503, "") })
            }.message,
        )
        assertEquals(
            "Unerwartete Antwort des Wetterdienstes.",
            assertFailsWith<Exception> {
                fetchWeatherForecast(52.0, 13.0, HttpClient { HttpResponse(200, "nicht json") })
            }.message,
        )
        assertEquals(
            "Unerwartete Antwort des Wetterdienstes.",
            assertFailsWith<Exception> {
                fetchWeatherForecast(52.0, 13.0, HttpClient { HttpResponse(200, "{\"stunde\":[]}") })
            }.message,
        )
    }

    // --- bestRideWindow ---

    @Test
    fun `das trockenste angenehmste Fenster gewinnt`() {
        val forecast = forecast(
            // Naechste volle Stunde: Regen.
            hour(0, precipProbPct = 80, precipMm = 2.0),
            // Spaeter: trocken und angenehm → muss gewinnen.
            hour(1, tempC = 19.0, precipProbPct = 0, precipMm = 0.0, windKmh = 8.0),
            hour(2, tempC = 20.0, precipProbPct = 0, precipMm = 0.0, windKmh = 8.0),
            hour(3, tempC = 21.0, precipProbPct = 0, precipMm = 0.0, windKmh = 8.0),
        )

        val window = bestRideWindow(forecast, durationH = 2.0, nowMs = NOW_MS)

        assertNotNull(window)
        assertEquals(NOW_MS + HOUR_MS, window.startMs)
        assertEquals(NOW_MS + 3 * HOUR_MS, window.endMs)
        assertEquals(0, window.avgPrecipProbPct)
        assertEquals(19.5, window.avgTempC, 1e-9)
        assertEquals(8.0, window.maxWindKmh, 1e-9)
    }

    @Test
    fun `startet nicht in der laufenden Stunde`() {
        val forecast = forecast(
            hour(-1, tempC = 25.0), // laufende, angebrochene Stunde
            hour(0, tempC = 25.0),
            hour(1, tempC = 25.0),
        )

        val window = bestRideWindow(forecast, durationH = 1.0, nowMs = NOW_MS)

        assertNotNull(window)
        assertEquals(NOW_MS, window.startMs)
    }

    @Test
    fun `Fenster muessen vollstaendig in der Prognose liegen`() {
        val forecast = forecast(hour(0), hour(1))

        assertNull(bestRideWindow(forecast, durationH = 3.0, nowMs = NOW_MS))
    }

    @Test
    fun `kuerzere Einheiten bekommen mindestens ein volles Stundenfenster`() {
        val forecast = forecast(hour(0, tempC = 20.0), hour(1, tempC = 20.0))

        val window = bestRideWindow(forecast, durationH = 0.5, nowMs = NOW_MS)

        assertNotNull(window)
        assertEquals(NOW_MS, window.startMs)
        assertEquals(NOW_MS + HOUR_MS, window.endMs)
    }

    @Test
    fun `ungueltige Dauer und leere Prognose geben null`() {
        assertNull(bestRideWindow(forecast(), durationH = 1.0, nowMs = NOW_MS))
        assertNull(bestRideWindow(forecast(hour(0)), durationH = 0.0, nowMs = NOW_MS))
        assertNull(bestRideWindow(forecast(hour(0)), durationH = Double.NaN, nowMs = NOW_MS))
    }

    @Test
    fun `kalte und windige Stunden kosten trotz Trockenheit`() {
        // Beide Fenster trocken; das spaetere ist kalt und windig — das
        // fruehere muss gewinnen, obwohl beide 0 % Regen haben.
        val forecast = forecast(
            hour(0, tempC = 20.0, windKmh = 10.0),
            hour(1, tempC = 20.0, windKmh = 10.0),
            hour(2, tempC = 3.0, windKmh = 40.0),
            hour(3, tempC = 3.0, windKmh = 40.0),
        )

        val window = bestRideWindow(forecast, durationH = 2.0, nowMs = NOW_MS)

        assertNotNull(window)
        assertEquals(NOW_MS, window.startMs)
    }

    @Test
    fun `fehlende Werte diskriminieren ein Fenster nicht`() {
        // Ganz ohne optionale Felder (alles null) bleibt nur die Temperatur in
        // der Bewertung — das angenehme Fenster gewinnt.
        val forecast = forecast(
            hour(0, tempC = 30.0, precipProbPct = null, precipMm = null, windKmh = null),
            hour(1, tempC = 30.0, precipProbPct = null, precipMm = null, windKmh = null),
            hour(2, tempC = 18.0, precipProbPct = null, precipMm = null, windKmh = null),
            hour(3, tempC = 18.0, precipProbPct = null, precipMm = null, windKmh = null),
        )

        val window = bestRideWindow(forecast, durationH = 2.0, nowMs = NOW_MS)

        assertNotNull(window)
        assertEquals(NOW_MS + 2 * HOUR_MS, window.startMs)
        assertEquals(0, window.avgPrecipProbPct)
        assertEquals(0.0, window.precipSumMm, 1e-9)
    }
}
