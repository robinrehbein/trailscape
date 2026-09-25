package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime

/**
 * GPS-Routen, fuer die Health Connect eine Freigabe **je Route** verlangt.
 *
 * ## Hintergrund
 * Health Connect behandelt Trainingsrouten als besonders sensibel. Hat die
 * Nutzerin fuer Trailscape nicht „Immer erlauben" bei den Routen gesetzt,
 * liefert `ExerciseSessionRecord.exerciseRouteResult` statt der Route nur
 * `ConsentRequired` — die Route ist da, darf aber nur nach einem eigenen
 * Dialog (`ExerciseRouteRequestContract`) gelesen werden. Frueher fielen
 * solche Touren still unter „ohne Route". Jetzt merkt sich der Import sie als
 * [RouteConsentRequest]; die App kann den Dialog spaeter zeigen und die Route
 * mit [attachRouteToRide] nachtragen.
 */

/** Eine importierte Tour, deren Route nur mit Einzel-Freigabe zu haben ist. */
data class RouteConsentRequest(
    /** `ExerciseSessionRecord.metadata.id` — Eingabe fuer den Freigabedialog. */
    val sessionId: String,
    /** ID der daraus importierten Tour ([healthRideId]). */
    val rideId: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** Quell-App der Session, falls bekannt. */
    val source: String? = null,
)

/**
 * Ergebnis eines Routen-Abrufs mit Status: die freigegebenen Routen und die
 * Session-IDs, deren Route eine Einzel-Freigabe braucht.
 */
data class HealthRouteReadResult(
    val routes: Map<String, List<HealthRoutePoint>>,
    val consentRequired: Set<String> = emptySet(),
)

/** Schluessel im [KeyValueStore] fuer die noch offenen Freigaben. */
const val routeConsentStorageKey: String = "trailscape.health.routeConsent.v1"

/** Hoechstens so viele offene Freigaben werden gehalten (die neuesten). */
const val routeConsentMaxPending: Int = 50

/**
 * Vereinigt bestehende und neue offene Freigaben (je Session hoechstens ein
 * Eintrag, der neue gewinnt), neueste zuerst, gedeckelt auf
 * [routeConsentMaxPending].
 */
fun mergeRouteConsentRequests(
    existing: List<RouteConsentRequest>,
    fresh: List<RouteConsentRequest>,
): List<RouteConsentRequest> {
    val bySession = linkedMapOf<String, RouteConsentRequest>()
    for (entry in existing) bySession[entry.sessionId] = entry
    for (entry in fresh) bySession[entry.sessionId] = entry
    return bySession.values
        .sortedByDescending { dartEpochMs(it.start) }
        .take(routeConsentMaxPending)
}

/** Serialisiert die offenen Freigaben fuer den [KeyValueStore]. */
fun encodeRouteConsentRequests(requests: List<RouteConsentRequest>): String =
    buildJsonArray {
        for (r in requests) {
            add(
                buildJsonObject {
                    put("sessionId", r.sessionId)
                    put("rideId", r.rideId)
                    put("startMs", dartEpochMs(r.start))
                    put("endMs", dartEpochMs(r.end))
                    r.source?.let { put("source", it) }
                },
            )
        }
    }.toString()

/** Gegenstueck zu [encodeRouteConsentRequests]; Unlesbares wird uebersprungen. */
fun decodeRouteConsentRequests(raw: String?): List<RouteConsentRequest> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        ?: return emptyList()
    val out = mutableListOf<RouteConsentRequest>()
    for (element in array) {
        val obj = element as? JsonObject ?: continue
        val sessionId = obj.optionalString("sessionId") ?: continue
        val rideId = obj.optionalString("rideId") ?: continue
        val startMs = obj.optionalLong("startMs") ?: continue
        val endMs = obj.optionalLong("endMs") ?: startMs
        out.add(
            RouteConsentRequest(
                sessionId = sessionId,
                rideId = rideId,
                start = dartLocalOf(startMs),
                end = dartLocalOf(endMs),
                source = obj.optionalString("source"),
            ),
        )
    }
    return out
}

/**
 * Traegt eine nachtraeglich freigegebene Route in eine bereits importierte
 * Tour ein.
 *
 * Die Trackpunkte entstehen genau wie in [buildRideFromWorkout] (Zeit,
 * Hoehe, naechstgelegene Herzfrequenz aus [heartRate]). Von der Tour bleiben
 * ID, Name, Zeitpunkt, Dauer, Planungskennzeichen und die Herzfrequenz-
 * Kennzahlen; die vom Geraet gemessene Distanz wird bevorzugt und nur ohne
 * sie aus der Route berechnet. Bewegungszeit, Schnitt und Hoehenmeter kommen
 * aus der Route. Ist [route] leer, bleibt die Tour unveraendert.
 */
fun attachRouteToRide(
    ride: Ride,
    route: List<HealthRoutePoint>,
    heartRate: List<HealthHeartRateSample> = emptyList(),
): Ride {
    if (route.isEmpty()) return ride

    val range = rideTimeRange(ride)
    val samples = heartRate
        .filter { !it.time.isBefore(range.start) && !it.time.isAfter(range.end) }
        .sortedBy { dartEpochMs(it.time) }
    val points = route
        .sortedBy { dartEpochMs(it.time) }
        .map { p ->
            TrackPoint(
                lat = p.lat,
                lon = p.lon,
                ele = p.ele,
                time = dartEpochMs(p.time),
                hr = nearestHr(samples, p.time),
            )
        }
    val geo = if (points.size >= 2) computeStats(points) else null

    val distanceKm = if (ride.stats.distanceKm > 0) ride.stats.distanceKm else geo?.distanceKm ?: 0.0
    val movingTimeS = geo?.movingTimeS ?: ride.stats.movingTimeS
    val durationS = ride.stats.durationS
    val avgSpeedKmh = when {
        movingTimeS != null && movingTimeS > 0 -> distanceKm / (movingTimeS / 3600.0)
        durationS != null && durationS > 0 -> distanceKm / (durationS / 3600.0)
        else -> ride.stats.avgSpeedKmh
    }

    return ride.copy(
        points = points,
        stats = ride.stats.copy(
            distanceKm = distanceKm,
            movingTimeS = movingTimeS,
            avgSpeedKmh = avgSpeedKmh,
            ascentM = geo?.ascentM ?: ride.stats.ascentM,
            descentM = geo?.descentM ?: ride.stats.descentM,
        ),
    )
}
