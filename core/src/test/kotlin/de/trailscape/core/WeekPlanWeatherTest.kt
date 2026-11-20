package de.trailscape.core

import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Tagesvorhersage in `WeatherClient.kt` und fuer Schalter, Ort
 * und Zwischenspeicher in `WeekPlanWeather.kt`.
 */
class WeekPlanWeatherTest {
    private companion object {
        val MUNICH = TrackPoint(lat = 48.13721, lon = 11.57559)

        const val EXPECTED_URL =
            "https://api.open-meteo.com/v1/forecast?latitude=48.14&longitude=11.58" +
                "&daily=precipitation_probability_max,precipitation_sum,wind_speed_10m_max," +
                "temperature_2m_min,temperature_2m_max&wind_speed_unit=kmh&timezone=auto&forecast_days=7"

        /** Aufgebaut wie eine echte Open-Meteo-Antwort mit `daily`. */
        const val SAMPLE = """{"latitude":48.14,"longitude":11.58,"timezone":"Europe/Berlin",
            "daily_units":{"time":"iso8601","precipitation_probability_max":"%","precipitation_sum":"mm",
            "wind_speed_10m_max":"km/h","temperature_2m_min":"°C","temperature_2m_max":"°C"},
            "daily":{"time":["2026-11-19","2026-11-20","2026-11-21"],
            "precipitation_probability_max":[10,85,null],
            "precipitation_sum":[0.0,6.4,0.2],
            "wind_speed_10m_max":[12.5,31.0,9.0],
            "temperature_2m_min":[3.1,5.0,-1.0],
            "temperature_2m_max":[13.6,9.2,4.0]}}"""

        const val NOW = 1_800_000_000_000L
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

    // --- URL und Antwort ---

    @Test
    fun `URL enthaelt nur gerundete Koordinaten, wenige Felder, sieben Tage und Zeitzone auto`() {
        assertEquals(EXPECTED_URL, dailyForecastUrl(MUNICH))
    }

    @Test
    fun `Antwort wird je Tag gelesen`() {
        val days = assertNotNull(parseDailyForecast(SAMPLE))
        assertEquals(3, days.size)
        assertEquals(
            DayForecast(LocalDate.of(2026, 11, 19), 10, 0.0, 12.5, 3.1, 13.6),
            days[0],
        )
        assertEquals(85, days[1].precipProbabilityPct)
        assertNull(days[2].precipProbabilityPct)
        assertEquals(-1.0, days[2].tempMinC)
    }

    @Test
    fun `falsche Einheit verwirft die ganze Antwort`() {
        assertNull(parseDailyForecast(SAMPLE.replace("\"wind_speed_10m_max\":\"km/h\"", "\"wind_speed_10m_max\":\"mp/h\"")))
        assertNull(parseDailyForecast(SAMPLE.replace("\"temperature_2m_max\":\"°C\"", "\"temperature_2m_max\":\"°F\"")))
    }

    @Test
    fun `kaputte Antworten ergeben null`() {
        assertNull(parseDailyForecast(""))
        assertNull(parseDailyForecast("[]"))
        assertNull(parseDailyForecast("""{"daily":{}}"""))
        assertNull(parseDailyForecast("""{"daily":{"time":"2026-11-19"}}"""))
    }

    @Test
    fun `unbrauchbare Einzelwerte werden nur fuer sich null`() {
        val body = """{"daily":{"time":["2026-11-19","kein Datum","2026-11-21"],
            "precipitation_probability_max":[150,10,"20"],
            "precipitation_sum":[-1.0,0.0,0.0],
            "wind_speed_10m_max":[-3.0,5.0,5.0],
            "temperature_2m_min":[20.0,1.0,1.0],
            "temperature_2m_max":[10.0,2.0,2.0]}}"""
        val days = assertNotNull(parseDailyForecast(body))
        assertEquals(2, days.size)
        assertEquals(DayForecast(LocalDate.of(2026, 11, 19)), days[0])
        assertNull(days[1].precipProbabilityPct)
    }

    @Test
    fun `mehr als sieben Tage werden abgeschnitten`() {
        val times = (1..10).joinToString(",") { "\"2026-11-${it.toString().padStart(2, '0')}\"" }
        val days = assertNotNull(parseDailyForecast("""{"daily":{"time":[$times]}}"""))
        assertEquals(DAILY_FORECAST_DAYS, days.size)
    }

    @Test
    fun `fetch schickt genau eine GET-Anfrage ohne Header`() {
        val requests = mutableListOf<HttpRequest>()
        val result = fetchDailyForecast(MUNICH, HttpClient { requests += it; HttpResponse(200, SAMPLE) })
        assertEquals(3, assertNotNull(result).days.size)
        assertEquals(SAMPLE, result.body)
        val request = requests.single()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals(EXPECTED_URL, request.url)
        assertTrue(request.headers.isEmpty())
        assertNull(request.body)
    }

    @Test
    fun `fetch wirft nie und liefert bei Fehlern null`() {
        assertNull(fetchDailyForecast(MUNICH, HttpClient { HttpResponse(500, SAMPLE) }))
        assertNull(fetchDailyForecast(MUNICH, HttpClient { HttpResponse(200, "kaputt") }))
        assertNull(fetchDailyForecast(MUNICH, HttpClient { throw IOException("offline") }))
        assertNull(fetchDailyForecast(TrackPoint(lat = Double.NaN, lon = 1.0), HttpClient { error("nicht fragen") }))
    }

    @Test
    fun `fetch reicht Cancellation weiter`() {
        assertFailsWith<CancellationException> {
            fetchDailyForecast(MUNICH, HttpClient { throw CancellationException("weg") })
        }
    }

    // --- Schalter ---

    @Test
    fun `Schalter ist ab Werk aus und nur 1 heisst an`() {
        val store = FakeKeyValueStore()
        assertFalse(readWeekPlanWeatherEnabled(store))
        store.setString(WEEK_PLAN_WEATHER_STORAGE_KEY, "true")
        assertFalse(readWeekPlanWeatherEnabled(store))
        writeWeekPlanWeatherEnabled(store, true)
        assertTrue(readWeekPlanWeatherEnabled(store))
    }

    @Test
    fun `Wind-Schalter der Rundkurs-Suche gilt nicht fuer den Wochenplan`() {
        val store = FakeKeyValueStore()
        writeRouteWindEnabled(store, true)
        assertFalse(readWeekPlanWeatherEnabled(store))
    }

    @Test
    fun `Ausschalten loescht auch die zwischengespeicherte Vorhersage`() {
        val store = FakeKeyValueStore()
        writeWeekPlanWeatherEnabled(store, true)
        store.setString(WEEK_PLAN_FORECAST_CACHE_KEY, "x")
        writeWeekPlanWeatherEnabled(store, false)
        assertTrue(store.values.isEmpty())
    }

    // --- Ort ---

    @Test
    fun `Ort ist die zuletzt bekannte Position, sonst der Start der letzten Tour`() {
        val ride = TrackPoint(lat = 52.5, lon = 13.4)
        assertEquals(MUNICH, weekPlanWeatherPoint(MUNICH, ride))
        assertEquals(ride, weekPlanWeatherPoint(null, ride))
        assertEquals(ride, weekPlanWeatherPoint(TrackPoint(lat = 0.0, lon = 0.0), ride))
        assertEquals(ride, weekPlanWeatherPoint(TrackPoint(lat = Double.NaN, lon = 1.0), ride))
        assertNull(weekPlanWeatherPoint(null, null))
    }

    // --- Zwischenspeicher und Laden ---

    @Test
    fun `Cache-Eintrag uebersteht Hin und Rueck, Fremdes ergibt null`() {
        val cached = CachedForecast("48.14,11.58", NOW, SAMPLE)
        assertEquals(cached, CachedForecast.decode(cached.encode()))
        assertNull(CachedForecast.decode(null))
        assertNull(CachedForecast.decode("{}"))
        assertNull(CachedForecast.decode("kaputt"))
    }

    @Test
    fun `Cache gilt drei Stunden am selben gerundeten Ort`() {
        val cached = CachedForecast("48.14,11.58", NOW, SAMPLE)
        assertTrue(shouldReuseForecast(cached, "48.14,11.58", NOW + WEEK_FORECAST_REUSE_MS - 1))
        assertFalse(shouldReuseForecast(cached, "48.14,11.58", NOW + WEEK_FORECAST_REUSE_MS))
        assertFalse(shouldReuseForecast(cached, "48.15,11.58", NOW))
        assertFalse(shouldReuseForecast(cached, "48.14,11.58", NOW - 1))
        assertFalse(shouldReuseForecast(null, "48.14,11.58", NOW))
    }

    @Test
    fun `ohne Schalter wird nichts gefragt`() {
        var calls = 0
        val result = loadWeekForecast(FakeKeyValueStore(), MUNICH, NOW, HttpClient { error("nie") }) { _, _ ->
            calls++
            null
        }
        assertEquals(WeekForecastStatus.AUS, result.status)
        assertEquals(0, calls)
    }

    @Test
    fun `ohne Ort wird nichts gefragt`() {
        val store = FakeKeyValueStore().also { writeWeekPlanWeatherEnabled(it, true) }
        val result = loadWeekForecast(store, null, NOW, HttpClient { error("nie") })
        assertEquals(WeekForecastStatus.KEIN_ORT, result.status)
    }

    @Test
    fun `holt einmal, speichert und nimmt danach den Zwischenspeicher`() {
        val store = FakeKeyValueStore().also { writeWeekPlanWeatherEnabled(it, true) }
        var calls = 0
        val client = HttpClient { calls++; HttpResponse(200, SAMPLE) }
        val first = loadWeekForecast(store, MUNICH, NOW, client)
        val second = loadWeekForecast(store, MUNICH, NOW + 60_000, client)
        assertEquals(WeekForecastStatus.VORHANDEN, first.status)
        assertEquals(first, second)
        assertEquals(1, calls)
        // Nach Ablauf wird wieder gefragt.
        loadWeekForecast(store, MUNICH, NOW + WEEK_FORECAST_REUSE_MS, client)
        assertEquals(2, calls)
    }

    @Test
    fun `gescheiterte Anfrage meldet nicht erreichbar und speichert nichts`() {
        val store = FakeKeyValueStore().also { writeWeekPlanWeatherEnabled(it, true) }
        val result = loadWeekForecast(store, MUNICH, NOW, HttpClient { throw IOException("offline") })
        assertEquals(WeekForecastStatus.NICHT_ERREICHBAR, result.status)
        assertTrue(result.days.isEmpty())
        assertNull(store.getString(WEEK_PLAN_FORECAST_CACHE_KEY))
    }

    @Test
    fun `im Zwischenspeicher steht nur der gerundete Ort`() {
        val store = FakeKeyValueStore().also { writeWeekPlanWeatherEnabled(it, true) }
        loadWeekForecast(store, MUNICH, NOW, HttpClient { HttpResponse(200, SAMPLE) })
        val cached = assertNotNull(CachedForecast.decode(store.getString(WEEK_PLAN_FORECAST_CACHE_KEY)))
        assertEquals("48.14,11.58", cached.key)
    }
}
