package de.trailscape.core

import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
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
