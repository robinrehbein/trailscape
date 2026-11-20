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
 *  * **Hoechstens alle [WEEK_FORECAST_REUSE_MS]** fuer denselben ungefaehren
 *    Ort (bis [WEEK_FORECAST_NEAR_DEG] daneben zaehlt als derselbe); dazwischen
 *    kommt die Vorhersage aus dem Zwischenspeicher. Beim Ausschalten wird er
 *    geloescht, nach Ablauf beim naechsten Oeffnen von „Heute" ebenso
 *    ([pruneExpiredWeekForecast]) — auch wenn die Karte gar nicht erscheint.
 *  * **Nur frische Orte**: die zuletzt bekannte Position bis
 *    [WEEK_PLAN_LAST_KNOWN_MAX_AGE_MS], der Start der juengsten Tour bis
 *    [WEEK_PLAN_RIDE_START_MAX_AGE_MS] alt.
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

/**
 * So weit (in Grad, je Achse) darf der Ort neben dem zwischengespeicherten
 * liegen, damit die Vorhersage trotzdem gilt — gut 5 km. Die zuletzt bekannte
 * Position springt zwischen Funkzelle, WLAN und GPS gern um ein, zwei
 * Rundungsstufen; fuer eine Tagesvorhersage ist das derselbe Ort.
 */
const val WEEK_FORECAST_NEAR_DEG: Double = 0.05

/**
 * Bis zu diesem Alter zaehlt die zuletzt bekannte Position des Geraets. Zwei
 * Tage: Wer seitdem verreist ist, hat die App meist mit Standort geoeffnet;
 * eine Position aus dem letzten Urlaub ist kein Ort fuer die Vorhersage.
 */
const val WEEK_PLAN_LAST_KNOWN_MAX_AGE_MS: Long = 2 * 24 * 60 * 60_000L

/**
 * Bis zu diesem Alter zaehlt der Start der juengsten Tour. Zwei Wochen: Wer
 * regelmaessig faehrt, startet meist in derselben Gegend; aeltere Touren
 * sagen wenig darueber, wo man jetzt ist.
 */
const val WEEK_PLAN_RIDE_START_MAX_AGE_MS: Long = 14 * 24 * 60 * 60_000L

/** Kleiner Spielraum fuer Zeitstempel knapp in der Zukunft (Uhrabgleich). */
private const val FUTURE_TOLERANCE_MS: Long = 10 * 60_000L

/**
 * Sperre fuer Schalter und Zwischenspeicher: Das Ausschalten (Schluessel und
 * Cache loeschen) und das Schreiben einer gerade angekommenen Antwort duerfen
 * sich nicht ueberholen — sonst stuende nach dem Ausschalten wieder ein Ort
 * auf dem Geraet.
 */
private val weekPlanWeatherLock = Any()

/** `true` genau dann, wenn der Schalter eingeschaltet wurde (Wert „1"). */
fun readWeekPlanWeatherEnabled(store: KeyValueStore): Boolean =
    store.getString(WEEK_PLAN_WEATHER_STORAGE_KEY) == "1"

/**
 * An schreibt „1"; aus entfernt den Schluessel **und** die zwischengespeicherte
 * Vorhersage — mit dem Ausschalten soll auch der (grobe) Ort vom Geraet
 * verschwinden, der in ihr steckt. Gesperrt gegen das Schreiben in
 * [loadWeekForecast].
 */
fun writeWeekPlanWeatherEnabled(store: KeyValueStore, enabled: Boolean) {
    synchronized(weekPlanWeatherLock) {
        if (enabled) {
            store.setString(WEEK_PLAN_WEATHER_STORAGE_KEY, "1")
        } else {
            store.remove(WEEK_PLAN_WEATHER_STORAGE_KEY)
            store.remove(WEEK_PLAN_FORECAST_CACHE_KEY)
        }
    }
}

/** Brauchbare Koordinate: endlich, im Wertebereich, nicht genau (0, 0). */
private fun isUsableCoordinate(point: TrackPoint): Boolean =
    point.lat.isFinite() && point.lon.isFinite() &&
        point.lat in -90.0..90.0 && point.lon in -180.0..180.0 &&
        !(point.lat == 0.0 && point.lon == 0.0)

/** Ob [atMs] hoechstens [maxAgeMs] vor [nowMs] liegt (und nicht deutlich danach). */
private fun isRecent(atMs: Long?, nowMs: Long, maxAgeMs: Long): Boolean =
    atMs != null && (nowMs - atMs) in -FUTURE_TOLERANCE_MS..maxAgeMs

/**
 * Der Ort fuer die Vorhersage: die zuletzt bekannte Position des Geraets,
 * wenn sie hoechstens [WEEK_PLAN_LAST_KNOWN_MAX_AGE_MS] alt ist (Alter aus
 * [TrackPoint.time]); sonst der Startpunkt der juengsten Tour, wenn diese
 * hoechstens [WEEK_PLAN_RIDE_START_MAX_AGE_MS] alt ist ([lastRideAtMs]).
 * Beides sind Orte, die die App schon hat — fuer den Wochenplan wird weder
 * eine Berechtigung angefragt noch ein frischer GPS-Fix geholt. Unbrauchbare
 * Koordinaten zaehlen nicht.
 *
 * [lastRideStart] wird nur aufgerufen, wenn die Position nicht taugt und die
 * Tour jung genug ist — das Lesen einer Tour kostet.
 */
fun weekPlanWeatherPoint(
    nowMs: Long,
    lastKnown: TrackPoint?,
    lastRideAtMs: Long?,
    lastRideStart: () -> TrackPoint?,
): TrackPoint? {
    if (lastKnown != null && isUsableCoordinate(lastKnown) &&
        isRecent(lastKnown.time, nowMs, WEEK_PLAN_LAST_KNOWN_MAX_AGE_MS)
    ) {
        return lastKnown
    }
    if (!isRecent(lastRideAtMs, nowMs, WEEK_PLAN_RIDE_START_MAX_AGE_MS)) return null
    return lastRideStart()?.takeIf(::isUsableCoordinate)
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

/** Liest einen Schluessel „48.14,11.58" zurueck; `null`, wenn er nicht so aussieht. */
private fun parseForecastKey(key: String): Pair<Double, Double>? {
    val parts = key.split(',')
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val lon = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return lat to lon
}

/**
 * Ob zwei gerundete Orte ([windCacheKey]) fuer die Vorhersage derselbe sind:
 * je Achse hoechstens [WEEK_FORECAST_NEAR_DEG] auseinander.
 */
fun isNearForecastKey(a: String, b: String): Boolean {
    if (a == b) return true
    val (latA, lonA) = parseForecastKey(a) ?: return false
    val (latB, lonB) = parseForecastKey(b) ?: return false
    return kotlin.math.abs(latA - latB) <= WEEK_FORECAST_NEAR_DEG + 1e-9 &&
        kotlin.math.abs(lonA - lonB) <= WEEK_FORECAST_NEAR_DEG + 1e-9
}

/** Ob eine zwischengespeicherte Vorhersage juenger als [WEEK_FORECAST_REUSE_MS] ist. */
private fun isFresh(cached: CachedForecast, nowMs: Long): Boolean =
    (nowMs - cached.atMs) in 0 until WEEK_FORECAST_REUSE_MS

/**
 * Ob eine zwischengespeicherte Vorhersage noch gilt: ungefaehr derselbe Ort
 * ([isNearForecastKey]) und juenger als [WEEK_FORECAST_REUSE_MS]. Eine
 * zurueckgestellte Uhr ergibt `false` — lieber einmal zu oft fragen als eine
 * uralte Vorhersage zeigen.
 */
fun shouldReuseForecast(cached: CachedForecast?, key: String, nowMs: Long): Boolean {
    if (cached == null || !isNearForecastKey(cached.key, key)) return false
    return isFresh(cached, nowMs)
}

/**
 * Loescht die zwischengespeicherte Vorhersage, sobald sie nicht mehr genutzt
 * wuerde (aelter als [WEEK_FORECAST_REUSE_MS], Uhr zurueckgestellt oder
 * unlesbar). Laeuft bei jedem Oeffnen von „Heute" — auch wenn die Karte gar
 * nicht erscheint (Ziel geschafft, Trainingsplan), damit der grobe Ort nicht
 * liegen bleibt. Wirft nie.
 */
fun pruneExpiredWeekForecast(store: KeyValueStore, nowMs: Long) {
    runCatching {
        synchronized(weekPlanWeatherLock) {
            val text = store.getString(WEEK_PLAN_FORECAST_CACHE_KEY) ?: return@synchronized
            val cached = CachedForecast.decode(text)
            if (cached == null || !isFresh(cached, nowMs)) store.remove(WEEK_PLAN_FORECAST_CACHE_KEY)
        }
    }
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
 * dort fuer ungefaehr denselben Ort noch gilt, sonst mit genau einer Anfrage
 * ueber [fetch] (Vorgabe: [fetchDailyForecast]). Fragt **nichts**, wenn der
 * Schalter aus ist oder kein Ort bekannt ist. Wirft nie (ausser bei
 * Coroutine-Cancellation aus [fetch]); blockiert, gehoert auf einen
 * IO-Dispatcher.
 *
 * Wurde der Schalter waehrend der Anfrage ausgeschaltet, wird die Antwort
 * verworfen: nichts gespeichert, Ergebnis [WeekForecastStatus.AUS]. Die
 * Pruefung direkt vor dem Schreiben ist gegen [writeWeekPlanWeatherEnabled]
 * gesperrt.
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
    val stillEnabled = synchronized(weekPlanWeatherLock) {
        val enabled = runCatching { readWeekPlanWeatherEnabled(store) }.getOrDefault(false)
        if (enabled) {
            runCatching {
                store.setString(WEEK_PLAN_FORECAST_CACHE_KEY, CachedForecast(key, nowMs, fresh.body).encode())
            }
        }
        enabled
    }
    if (!stillEnabled) return WeekForecast(emptyList(), WeekForecastStatus.AUS)
    return WeekForecast(fresh.days, WeekForecastStatus.VORHANDEN)
}
