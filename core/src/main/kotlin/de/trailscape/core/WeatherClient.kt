package de.trailscape.core

import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Aktueller Wind am Startpunkt einer Runde, ueber die freie Vorhersage-API
 * von Open-Meteo (`api.open-meteo.com`, ohne API-Schluessel).
 *
 * ## Datenschutz
 * Das ist eine ausgehende Anfrage und deshalb **nur mit Schalter** erlaubt
 * („Wind berücksichtigen" im Blatt *Runde ab hier*, ab Werk aus, siehe
 * [readRouteWindEnabled]) — und auch dann nur, wenn die Nutzerin eine Suche
 * startet. Mitgeschickt wird allein der Startpunkt, **auf zwei
 * Nachkommastellen gerundet** (in Mitteleuropa etwa 1 km), dazu die Namen der
 * abgefragten Werte. Keine Kennung, keine eigenen Header, kein Body. Beschrieben
 * in `PRIVACY.md`, Abschnitt 4.
 *
 * ## Fehler
 * Der Wind ist eine Zugabe, kein Muss: [fetchCurrentWind] wirft nie (ausser
 * bei einer Coroutine-Cancellation) und liefert bei Offline, Timeout,
 * Nicht-2xx oder unerwarteter Antwort einfach `null` — die Suche rechnet dann
 * still ohne Wind weiter.
 *
 * ## Tagesvorhersage
 * Unten in dieser Datei steht dieselbe Anfrage noch einmal fuer `daily`
 * ([fetchDailyForecast]) — fuer den Wochen-Fahrplan auf „Heute", mit eigenem
 * Schalter und Zwischenspeicher (`WeekPlanWeather.kt`). Gleiche Regeln:
 * gerundeter Punkt, keine Header, wirft nie.
 */

/**
 * Wind in 10 m Hoehe.
 *
 * @property speedKmh Mittelwind in km/h.
 * @property fromDeg Meteorologische Richtung: die Richtung, **aus** der der
 *   Wind weht (0…360, 0 = Nord, 270 = West).
 * @property gustsKmh Boeen in km/h, falls geliefert.
 */
data class WindConditions(
    val speedKmh: Double,
    val fromDeg: Double,
    val gustsKmh: Double? = null,
)

/** Nachkommastellen, auf die der Startpunkt vor dem Senden gerundet wird (≈ 1 km). */
const val WIND_COORDINATE_DECIMALS: Int = 2

/** Wie lange ein geholter Wind fuer denselben gerundeten Startpunkt wiederverwendet wird. */
const val WIND_REUSE_MS: Long = 30 * 60_000L

private const val OPEN_METEO_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

/**
 * Koordinate auf [WIND_COORDINATE_DECIMALS] Nachkommastellen, immer mit Punkt
 * (`Locale.ROOT` — ein deutsches Geraete-Locale wuerde sonst ein Komma in die
 * URL schreiben). „-0.00" wird zu „0.00".
 */
internal fun roundedCoordinate(value: Double): String {
    val text = String.format(Locale.ROOT, "%.${WIND_COORDINATE_DECIMALS}f", value)
    return if (text == "-0.00") "0.00" else text
}

/** Die Open-Meteo-URL fuer den aktuellen Wind am (gerundeten) [point]. */
fun windForecastUrl(point: TrackPoint): String =
    "$OPEN_METEO_FORECAST_URL?latitude=${roundedCoordinate(point.lat)}" +
        "&longitude=${roundedCoordinate(point.lon)}" +
        "&current=wind_speed_10m,wind_direction_10m,wind_gusts_10m&wind_speed_unit=kmh"

/**
 * Liest `current.wind_speed_10m`, `current.wind_direction_10m` und optional
 * `current.wind_gusts_10m` aus einer Open-Meteo-Antwort.
 *
 * `null`, wenn etwas fehlt, nicht endlich ist, die Geschwindigkeit negativ
 * ist oder die gemeldete Einheit nicht km/h ist — lieber kein Wind als ein
 * falscher. Unbrauchbare Boeen machen nur die Boeen `null`.
 */
fun parseWindResponse(body: String): WindConditions? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null

    val units = root["current_units"] as? JsonObject
    val unit = (units?.get("wind_speed_10m") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (unit != null && unit != "km/h") return null

    val current = root["current"] as? JsonObject ?: return null
    val speed = current.finiteNumber("wind_speed_10m") ?: return null
    val direction = current.finiteNumber("wind_direction_10m") ?: return null
    if (speed < 0) return null
    val gusts = current.finiteNumber("wind_gusts_10m")?.takeIf { it >= 0 }

    // Winzige negative Werte ergeben in `normalisiereKurs` genau 360 — daher der Waechter unten.
    val from = normalisiereKurs(direction)
    return WindConditions(
        speedKmh = speed,
        fromDeg = if (from >= 360.0) 0.0 else from,
        gustsKmh = gusts,
    )
}

/** Endliche Zahl unter [key] — Strings wie „18.2" zaehlen bewusst nicht. */
private fun JsonObject.finiteNumber(key: String): Double? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.doubleOrNull?.takeIf { it.isFinite() }
}

/**
 * Holt den aktuellen Wind am gerundeten [point] — genau eine GET-Anfrage
 * ohne Header und ohne Body. Blockiert; gehoert auf einen IO-Dispatcher.
 *
 * Liefert bei jedem Fehler `null` (siehe Datei-KDoc); nur eine
 * [CancellationException] wird weitergeworfen.
 */
fun fetchCurrentWind(point: TrackPoint, client: HttpClient): WindConditions? {
    if (!point.lat.isFinite() || !point.lon.isFinite()) return null
    return try {
        val response = client.execute(HttpRequest(method = HttpMethod.GET, url = windForecastUrl(point)))
        if (response.statusCode !in 200..299) null else parseWindResponse(response.body)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/** Cache-Schluessel eines Startpunkts: die gerundeten Koordinaten, „48.14,11.58". */
fun windCacheKey(point: TrackPoint): String =
    "${roundedCoordinate(point.lat)},${roundedCoordinate(point.lon)}"

/**
 * Ob ein zuvor geholter Wind wiederverwendet werden darf: derselbe gerundete
 * Startpunkt und juenger als [WIND_REUSE_MS]. Eine zurueckgestellte Uhr
 * (`nowMs < cachedAtMs`) ergibt `false` — lieber einmal zu oft fragen als
 * einen uralten Wind zeigen.
 */
fun shouldReuseWind(cachedKey: String?, cachedAtMs: Long, key: String, nowMs: Long): Boolean {
    if (cachedKey == null || cachedKey != key) return false
    val age = nowMs - cachedAtMs
    return age in 0 until WIND_REUSE_MS
}

// ---------------------------------------------------------------------------
// Tagesvorhersage fuer den Wochen-Fahrplan
// ---------------------------------------------------------------------------

/**
 * Vorhersage fuer einen Kalendertag am (gerundeten) Ort, wie sie der
 * Wochen-Fahrplan ([planWeek]) braucht. Jeder Wert darf fehlen — Open-Meteo
 * liefert fuer spaete Tage gelegentlich `null`, und ein fehlender Wert ist
 * besser als ein geratener.
 *
 * @property precipProbabilityPct hoechste stuendliche Regenwahrscheinlichkeit des Tages (0…100).
 * @property precipMm Niederschlagssumme des Tages in mm.
 * @property windMaxKmh staerkster Mittelwind des Tages in 10 m Hoehe, km/h.
 * @property tempMinC Tiefstwert in °C.
 * @property tempMaxC Hoechstwert in °C.
 */
data class DayForecast(
    val date: LocalDate,
    val precipProbabilityPct: Int? = null,
    val precipMm: Double? = null,
    val windMaxKmh: Double? = null,
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
)

/** Mehr Tage fragt der Wochen-Fahrplan nie ab: Er plant hoechstens bis Sonntag. */
const val DAILY_FORECAST_DAYS: Int = 7

/**
 * Die Open-Meteo-URL fuer die Tagesvorhersage am (gerundeten) [point]:
 * fuenf Tageswerte, [DAILY_FORECAST_DAYS] Tage, Zeitzone `auto` (die
 * Kalendertage gelten dann am Ort selbst, nicht in UTC).
 */
fun dailyForecastUrl(point: TrackPoint): String =
    "$OPEN_METEO_FORECAST_URL?latitude=${roundedCoordinate(point.lat)}" +
        "&longitude=${roundedCoordinate(point.lon)}" +
        "&daily=precipitation_probability_max,precipitation_sum,wind_speed_10m_max," +
        "temperature_2m_min,temperature_2m_max" +
        "&wind_speed_unit=kmh&timezone=auto&forecast_days=$DAILY_FORECAST_DAYS"

/**
 * Liest `daily` aus einer Open-Meteo-Antwort.
 *
 * `null`, wenn die Antwort kaputt ist, `daily.time` fehlt oder eine gemeldete
 * Einheit nicht passt (Wind nicht km/h, Temperatur nicht °C, Regen nicht mm)
 * — dann lieber ohne Wetter planen als mit falschem. Einzelne unbrauchbare
 * Werte (fehlend, `null`, nicht endlich, ausserhalb des Sinnvollen) werden
 * nur fuer sich `null`. Mehr als [DAILY_FORECAST_DAYS] Tage werden
 * abgeschnitten, Tage mit unlesbarem Datum uebersprungen.
 */
fun parseDailyForecast(body: String): List<DayForecast>? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null

    val units = root["daily_units"] as? JsonObject
    fun unitOf(key: String): String? = (units?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    val expectedUnits = mapOf(
        "wind_speed_10m_max" to "km/h",
        "temperature_2m_min" to "°C",
        "temperature_2m_max" to "°C",
        "precipitation_sum" to "mm",
    )
    if (expectedUnits.any { (key, unit) -> unitOf(key).let { it != null && it != unit } }) return null

    val daily = root["daily"] as? JsonObject ?: return null
    val times = daily["time"] as? JsonArray ?: return null

    fun numberAt(key: String, index: Int): Double? {
        val array = daily[key] as? JsonArray ?: return null
        val primitive = array.getOrNull(index) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull?.takeIf { it.isFinite() }
    }

    return times.take(DAILY_FORECAST_DAYS).mapIndexedNotNull { index, element ->
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapIndexedNotNull null
        val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return@mapIndexedNotNull null
        val min = numberAt("temperature_2m_min", index)?.takeIf { it in -60.0..60.0 }
        val max = numberAt("temperature_2m_max", index)?.takeIf { it in -60.0..60.0 }
        // Vertauschte Werte waeren ein Lesefehler: dann beide verwerfen.
        val swapped = min != null && max != null && min > max
        DayForecast(
            date = date,
            precipProbabilityPct = numberAt("precipitation_probability_max", index)
                ?.takeIf { it in 0.0..100.0 }
                ?.let { round(it).toInt() },
            precipMm = numberAt("precipitation_sum", index)?.takeIf { it >= 0 },
            windMaxKmh = numberAt("wind_speed_10m_max", index)?.takeIf { it >= 0 },
            tempMinC = min.takeUnless { swapped },
            tempMaxC = max.takeUnless { swapped },
        )
    }
}

/** Ergebnis von [fetchDailyForecast]: die Antwort wie geliefert und ihre Tage. */
data class FetchedForecast(val body: String, val days: List<DayForecast>)

/**
 * Holt die Tagesvorhersage am gerundeten [point] — genau eine GET-Anfrage
 * ohne Header und ohne Body, gleiche Regeln wie [fetchCurrentWind]: wirft nie
 * (ausser [CancellationException]), liefert bei jedem Fehler `null`.
 * Blockiert; gehoert auf einen IO-Dispatcher.
 *
 * @return die rohe Antwort (zum Zwischenspeichern) und die gelesenen Tage.
 */
fun fetchDailyForecast(point: TrackPoint, client: HttpClient): FetchedForecast? {
    if (!point.lat.isFinite() || !point.lon.isFinite()) return null
    return try {
        val response = client.execute(HttpRequest(method = HttpMethod.GET, url = dailyForecastUrl(point)))
        if (response.statusCode !in 200..299) return null
        val days = parseDailyForecast(response.body) ?: return null
        FetchedForecast(body = response.body, days = days)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
