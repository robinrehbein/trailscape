package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * # Wetter fuer den Wochen-Fahrplan: Schalter, Ort, Zwischenspeicher
 *
 * Die Tagesvorhersage ([fetchDailyForecast]) ist eine ausgehende Anfrage an
 * Open-Meteo und folgt deshalb demselben Muster wie der Wind der
 * Rundkurs-Suche (`RouteWindPreference.kt`):
 *
 *  * **Eigener Schalter, ab Werk aus.** „Wetter für den Wochenplan" sitzt
 *    direkt an der Karte auf „Heute". Nur der exakte Wert „1" heisst an.
 *    Der Wind-Schalter der Rundkurs-Suche gilt hier bewusst nicht: Wer dort
 *    eingewilligt hat, hat in eine Anfrage beim Suchen eingewilligt, nicht in
 *    eine beim Oeffnen der Startseite.
 *  * **Nur der gerundete Ort** verlaesst das Geraet (zwei Nachkommastellen,
 *    siehe [dailyForecastUrl]).
 *  * **Hoechstens alle [WEEK_FORECAST_REUSE_MS]** fuer denselben gerundeten
 *    Ort; dazwischen kommt die Vorhersage aus dem Zwischenspeicher. Beim
 *    Ausschalten wird er geloescht.
 *
 * Reine Logik ohne Android — die App liefert nur Ort, Uhrzeit und Speicher.
 */

/** Prefs-Schluessel des Schalters „Wetter für den Wochenplan". */
const val WEEK_PLAN_WEATHER_STORAGE_KEY: String = "trailscape.weekplan.weather.v1"

/** Prefs-Schluessel der zwischengespeicherten Vorhersage. */
const val WEEK_PLAN_FORECAST_CACHE_KEY: String = "trailscape.weekplan.forecast.v1"

/**
 * Wie lange eine geholte Vorhersage fuer denselben gerundeten Ort gilt. Drei
 * Stunden: Eine Tagesvorhersage aendert sich langsam, und die Startseite wird
 * oft geoeffnet — haeufiger zu fragen braechte nichts ausser Anfragen.
 */
const val WEEK_FORECAST_REUSE_MS: Long = 3 * 60 * 60_000L

/** `true` genau dann, wenn der Schalter eingeschaltet wurde (Wert „1"). */
fun readWeekPlanWeatherEnabled(store: KeyValueStore): Boolean =
    store.getString(WEEK_PLAN_WEATHER_STORAGE_KEY) == "1"

/**
 * An schreibt „1"; aus entfernt den Schluessel **und** die zwischengespeicherte
 * Vorhersage — mit dem Ausschalten soll auch der (grobe) Ort vom Geraet
 * verschwinden, der in ihr steckt.
 */
fun writeWeekPlanWeatherEnabled(store: KeyValueStore, enabled: Boolean) {
    if (enabled) {
        store.setString(WEEK_PLAN_WEATHER_STORAGE_KEY, "1")
    } else {
        store.remove(WEEK_PLAN_WEATHER_STORAGE_KEY)
        store.remove(WEEK_PLAN_FORECAST_CACHE_KEY)
    }
}

/**
 * Der Ort fuer die Vorhersage: die zuletzt bekannte Position des Geraets,
 * sonst der Startpunkt der juengsten Tour. Beides sind Orte, die die App
 * schon hat — fuer den Wochenplan wird weder eine Berechtigung angefragt noch
 * ein frischer GPS-Fix geholt. Unbrauchbare Koordinaten zaehlen nicht.
 */
fun weekPlanWeatherPoint(lastKnown: TrackPoint?, lastRideStart: TrackPoint?): TrackPoint? =
    listOfNotNull(lastKnown, lastRideStart).firstOrNull { point ->
        point.lat.isFinite() && point.lon.isFinite() &&
            point.lat in -90.0..90.0 && point.lon in -180.0..180.0 &&
            !(point.lat == 0.0 && point.lon == 0.0)
    }

/** Eine zwischengespeicherte Vorhersage: gerundeter Ort ([windCacheKey]), Zeitpunkt, Antwort. */
data class CachedForecast(val key: String, val atMs: Long, val body: String) {
    fun encode(): String = buildJsonObject {
        put("key", key)
        put("at", atMs)
        put("body", body)
    }.toString()

    companion object {
        fun decode(text: String?): CachedForecast? {
            if (text.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                as? JsonObject ?: return null
            val key = (obj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val at = (obj["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            return CachedForecast(key, at, body)
        }
    }
}

/**
 * Ob eine zwischengespeicherte Vorhersage noch gilt: derselbe gerundete Ort
 * und juenger als [WEEK_FORECAST_REUSE_MS]. Eine zurueckgestellte Uhr ergibt
 * `false` — lieber einmal zu oft fragen als eine uralte Vorhersage zeigen.
 */
fun shouldReuseForecast(cached: CachedForecast?, key: String, nowMs: Long): Boolean {
    if (cached == null || cached.key != key) return false
    return (nowMs - cached.atMs) in 0 until WEEK_FORECAST_REUSE_MS
}

/** Warum der Wochenplan ohne Wetter rechnet — die Oberflaeche sagt es klein dazu. */
enum class WeekForecastStatus {
    /** Vorhersage liegt vor (frisch oder aus dem Zwischenspeicher). */
    VORHANDEN,

    /** Schalter aus: Es wurde nichts gefragt. */
    AUS,

    /** Weder zuletzt bekannte Position noch eine Tour mit Startpunkt. */
    KEIN_ORT,

    /** Anfrage gescheitert (offline, Zeitueberschreitung, kaputte Antwort). */
    NICHT_ERREICHBAR,
}

/** Ergebnis von [loadWeekForecast]: die Tage (leer ohne Wetter) und warum. */
data class WeekForecast(val days: List<DayForecast>, val status: WeekForecastStatus)

/**
 * Die Vorhersage fuer den Wochenplan — aus dem Zwischenspeicher, wenn sie
 * dort fuer denselben gerundeten Ort noch gilt, sonst mit genau einer Anfrage
 * ueber [fetch] (Vorgabe: [fetchDailyForecast]). Fragt **nichts**, wenn der
 * Schalter aus ist oder kein Ort bekannt ist. Wirft nie (ausser bei
 * Coroutine-Cancellation aus [fetch]); blockiert, gehoert auf einen
 * IO-Dispatcher.
 */
fun loadWeekForecast(
    store: KeyValueStore,
    point: TrackPoint?,
    nowMs: Long,
    client: HttpClient,
    fetch: (TrackPoint, HttpClient) -> FetchedForecast? = ::fetchDailyForecast,
): WeekForecast {
    if (!readWeekPlanWeatherEnabled(store)) return WeekForecast(emptyList(), WeekForecastStatus.AUS)
    if (point == null) return WeekForecast(emptyList(), WeekForecastStatus.KEIN_ORT)
    val key = windCacheKey(point)
    val cached = runCatching { CachedForecast.decode(store.getString(WEEK_PLAN_FORECAST_CACHE_KEY)) }.getOrNull()
    if (shouldReuseForecast(cached, key, nowMs)) {
        parseDailyForecast(cached!!.body)?.let { return WeekForecast(it, WeekForecastStatus.VORHANDEN) }
    }
    val fresh = fetch(point, client) ?: return WeekForecast(emptyList(), WeekForecastStatus.NICHT_ERREICHBAR)
    runCatching { store.setString(WEEK_PLAN_FORECAST_CACHE_KEY, CachedForecast(key, nowMs, fresh.body).encode()) }
    return WeekForecast(fresh.days, WeekForecastStatus.VORHANDEN)
}
