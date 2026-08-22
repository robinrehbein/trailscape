package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/**
 * Wetter fuer die Tageseinheit: Abruf und die Frage, die der Startseite damit
 * zu beantworten hat — **wann ist heute das beste Fenster zu fahren?**
 *
 * ## Quelle
 * Open-Meteo (`api.open-meteo.com`): frei, ohne API-Schluessel, fuer
 * nicht-kommerzielle Nutzung ohne Registrierung. Die Anfrage ist eine einzige
 * GET-Anfrage mit Koordinaten; was dabei hinausgeht, steht in `PRIVACY.md`.
 *
 * ## Das Fenstermodell
 * [bestRideWindow] schiebt ein Fenster von der Dauer der heutigen Einheit
 * stuendlich durch die Prognose und bewertet jedes nach Regen
 * (Wahrscheinlichkeit und Menge), Temperaturkomfort und Wind. Das beste
 * Fenster gewinnt — nicht das trockenste: Regenwahrscheinlichkeit 0 % bei
 * 3 °C und Sturm ist trocken und trotzdem keine Empfehlung. Die Gewichte sind
 * Hausverstand, keine Meteorologie; sie sortieren Fenster, sie prophezeien
 * nichts.
 *
 * ## Kein Android, keine IO
 * [fetchWeatherForecast] nimmt den [HttpClient] entgegen (in `:app` OkHttp, in
 * Tests ein Fake) — dasselbe Muster wie [searchPlaces] und `calculateRoute`.
 */

/** Wetter einer vollen Stunde (Open-Meteo liefert ein stuendliches Raster). */
data class HourWeather(
    /** Stundenbeginn in ms seit Epoch (UTC-Stunde × 1000). */
    val timeMs: Long,
    val tempC: Double,
    /** Regenwahrscheinlichkeit in Prozent; `null`, wenn der Server sie nicht liefert. */
    val precipProbPct: Int?,
    /** Niederschlagsmenge der Stunde in mm; `null`, wenn nicht geliefert. */
    val precipMm: Double?,
    /** Wind in km/h in 10 m Hoehe; `null`, wenn nicht geliefert. */
    val windKmh: Double?,
)

/** Stuendliche Prognose, aufsteigend nach [HourWeather.timeMs]. */
data class WeatherForecast(val hours: List<HourWeather>)

/** Ein bewertetes Fahrfenster innerhalb einer [WeatherForecast]. */
data class RideWindow(
    /** Fensterbeginn in ms seit Epoch (volle Stunde). */
    val startMs: Long,
    /** Fensterende in ms seit Epoch: `startMs + Dauer`. */
    val endMs: Long,
    /** Mittel der Regenwahrscheinlichkeit im Fenster in Prozent (0 ohne Angabe). */
    val avgPrecipProbPct: Int,
    /** Niederschlagssumme im Fenster in mm (0 ohne Angabe). */
    val precipSumMm: Double,
    /** Mittel der Temperatur im Fenster in °C. */
    val avgTempC: Double,
    /** Hoechster Wind im Fenster in km/h (0 ohne Angabe). */
    val maxWindKmh: Double,
    /** Je kleiner, desto besser (Zusammensetzung siehe [bestRideWindow]). */
    val score: Double,
)

/** Baut die Open-Meteo-URL (Reihenfolge der Parameter stabil, wie bei Nominatim). */
internal fun buildForecastUrl(lat: Double, lon: Double): String =
    "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$lat" +
        "&longitude=$lon" +
        "&hourly=temperature_2m,precipitation_probability,precipitation,windspeed_10m" +
        "&forecast_days=2" +
        "&timeformat=unixtime"

/**
 * Laedt die stuendliche Prognose der naechsten zwei Tage fuer die Koordinaten.
 *
 * Wirft mit deutscher Meldung, wenn der Server nicht erreicht wird oder etwas
 * zurueckschickt, das keine Prognose ist — dasselbe Fehlerbild wie
 * [searchPlaces].
 */
fun fetchWeatherForecast(lat: Double, lon: Double, client: HttpClient): WeatherForecast {
    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.GET,
                url = buildForecastUrl(lat, lon),
                headers = mapOf("User-Agent" to "Trailscape/1.0 (github.com/robinrehbein/trailscape)"),
            ),
        )
    } catch (_: Exception) {
        throw Exception("Wetterdienst nicht erreichbar. Bist du online?")
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception("Wetterdienst antwortete mit HTTP ${response.statusCode}.")
    }

    val root = try {
        Json.parseToJsonElement(response.body)
    } catch (_: Exception) {
        throw Exception("Unerwartete Antwort des Wetterdienstes.")
    }
    val hourly = (root as? JsonObject)?.get("hourly") as? JsonObject
        ?: throw Exception("Unerwartete Antwort des Wetterdienstes.")

    val times = hourly.doubleArray("time")
    val temps = hourly.doubleArray("temperature_2m")
    if (times.isEmpty() || temps.size != times.size) {
        throw Exception("Unerwartete Antwort des Wetterdienstes.")
    }

    val precipProb = hourly.doubleArray("precipitation_probability")
    val precip = hourly.doubleArray("precipitation")
    val wind = hourly.doubleArray("windspeed_10m")

    val hours = times.indices.map { i ->
        HourWeather(
            timeMs = times[i].toLong() * 1000,
            tempC = temps[i],
            precipProbPct = precipProb.getOrNull(i)?.toInt(),
            precipMm = precip.getOrNull(i),
            windKmh = wind.getOrNull(i),
        )
    }
    return WeatherForecast(hours)
}

/** Liest ein optionales Zahlen-Feld des `hourly`-Objekts; fehlt es, ist die Liste leer. */
private fun JsonObject.doubleArray(key: String): List<Double> {
    val array = this[key]?.jsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()
    }
}

// ---------------------------------------------------------------------------
// Fensterbewertung
// ---------------------------------------------------------------------------

/** Fenster kuerzer als eine Stunde gibt es nicht — kuerzere Einheiten bekommen ein volles. */
internal const val windowMinHours: Int = 1

/** Gewicht der Regenwahrscheinlichkeit (Prozentpunkt → Score-Punkte). */
internal const val windowPrecipProbWeight: Double = 0.5

/** Gewicht des Niederschlags (mm → Score-Punkte; 1 mm schlaegt 40 % Wahrscheinlichkeit). */
internal const val windowPrecipMmWeight: Double = 20.0

/** Untere Kante des Temperatur-Komfortbands in °C. */
internal const val windowTempComfortMinC: Double = 12.0

/** Obere Kante des Temperatur-Komfortbands in °C. */
internal const val windowTempComfortMaxC: Double = 26.0

/** Gewicht je Grad Temperatur ausserhalb des Komfortbands (°C → Score-Punkte). */
internal const val windowTempWeight: Double = 1.5

/** Ab dieser Windgeschwindigkeit kostet Wind Punkte (km/h). */
internal const val windowWindThresholdKmh: Double = 25.0

/** Gewicht des Winds ueber der Schwelle (km/h → Score-Punkte). */
internal const val windowWindWeight: Double = 0.8

/**
 * Das beste Fahrfenster der naechsten Stunden.
 *
 * Kandidaten sind alle Fenster, die
 *  * zur vollen Stunde **ab jetzt** beginnen (die naechste kommende Stunde;
 *    die laufende angebrochene Stunde ist kein Start mehr),
 *  * vollstaendig innerhalb der Prognose liegen und
 *  * `durationH` lang sind, auf ganze Stunden aufgerundet und mindestens
 *    [windowMinHours].
 *
 * Score je Stunde des Fensters (niedriger ist besser):
 * `0,5 × Regenwahrscheinlichkeit % + 20 × Niederschlag mm +
 *  1,5 × Grad ausserhalb 12–26 °C + 0,8 × km/h Wind ueber 25`
 * Fehlende Angaben zaehlen als 0, statt das Fenster zu diskriminieren.
 *
 * `null`, wenn [durationH] nicht positiv ist oder die Prognose kein
 * vollstaendiges Fenster mehr hergibt (z. B. abends, wenn die Einheit nicht
 * mehr in den Prognosehorizont passt).
 */
fun bestRideWindow(
    forecast: WeatherForecast,
    durationH: Double,
    nowMs: Long,
): RideWindow? {
    if (!durationH.isFinite() || durationH <= 0) {
        return null
    }
    val sorted = forecast.hours.sortedBy { it.timeMs }
    if (sorted.isEmpty()) {
        return null
    }
    val hours = maxOf(windowMinHours, kotlin.math.ceil(durationH).toInt())

    // Erste Stunde, die noch komplett vor einem liegt: die naechste volle.
    val firstStartMs = sorted.firstOrNull { it.timeMs >= nowMs }?.timeMs ?: return null
    val lastStartIndex = sorted.lastIndex - hours + 1
    if (lastStartIndex < 0) {
        return null
    }

    var best: RideWindow? = null
    var bestScore = Double.POSITIVE_INFINITY

    for (startIdx in 0..lastStartIndex) {
        val start = sorted[startIdx]
        if (start.timeMs < firstStartMs) {
            continue
        }
        val slice = sorted.subList(startIdx, startIdx + hours)

        var probSum = 0.0
        var probCount = 0
        var precipSum = 0.0
        var precipCount = 0
        var tempSum = 0.0
        var windMax = 0.0
        var score = 0.0

        for (hour in slice) {
            hour.precipProbPct?.let {
                probSum += it
                probCount++
                score += windowPrecipProbWeight * it
            }
            hour.precipMm?.let {
                precipSum += it
                precipCount++
                score += windowPrecipMmWeight * it
            }
            tempSum += hour.tempC
            val degreesOutside = maxOf(
                windowTempComfortMinC - hour.tempC,
                hour.tempC - windowTempComfortMaxC,
                0.0,
            )
            score += windowTempWeight * degreesOutside
            hour.windKmh?.let {
                windMax = maxOf(windMax, it)
                score += windowWindWeight * maxOf(0.0, it - windowWindThresholdKmh)
            }
        }

        if (score < bestScore) {
            bestScore = score
            best = RideWindow(
                startMs = start.timeMs,
                endMs = start.timeMs + hours * 3_600_000L,
                avgPrecipProbPct = if (probCount > 0) (probSum / probCount).toInt() else 0,
                precipSumMm = if (precipCount > 0) precipSum else 0.0,
                avgTempC = tempSum / slice.size,
                maxWindKmh = windMax,
                score = score,
            )
        }
    }
    return best
}
