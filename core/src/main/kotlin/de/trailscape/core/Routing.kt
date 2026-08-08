package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Routenberechnung ueber den oeffentlichen BRouter-Server.
 *
 * 1:1-Portierung von `lib/routing.dart`. Spiegelt die Logik der frueheren
 * Web-App (routing.ts), damit Routen gleich berechnet werden, egal ob ueber
 * die Flutter-/native App oder den Browser.
 *
 * Nutzt fuer Netzwerk-Requests die plattformneutrale [HttpClient]-Abstraktion
 * (siehe HttpClient.kt) statt eines konkreten HTTP-Stacks — eine echte
 * Implementierung folgt in Phase 3 in `:app`.
 */

/**
 * Routenprofil zur Auswahl in der Planung.
 *
 * Ersetzt die frühere 2x5-Matrix aus Fahrradtyp × Wegpräferenz durch eine
 * einzige, direkte Auswahl mit 1:1-Mapping auf BRouter-Profil-IDs.
 */
enum class RouteProfile { GRAVEL, SCHOTTER, ASPHALT, RADWEGE, KUERZESTER }

/**
 * Entspricht der Dart-Konstante `routeProfileLabels`. Reihenfolge ist
 * bedeutsam (z. B. fuer Dropdowns): Gravel steht zuerst, Schotter direkt
 * danach.
 */
val routeProfileLabels: Map<RouteProfile, String> = linkedMapOf(
    RouteProfile.GRAVEL to "Gravel (gemischt)",
    RouteProfile.SCHOTTER to "Schotter & Kieswege",
    RouteProfile.ASPHALT to "Rennrad / Asphalt",
    RouteProfile.RADWEGE to "Radwege bevorzugt",
    RouteProfile.KUERZESTER to "Kürzeste Route",
)

/**
 * Sentinel-Profilname für das eingebettete Gravel-Custom-Profil.
 *
 * Kein echter BRouter-Profilname: [fetchRoute] lädt dafür zunächst
 * [gravelProfileText] auf den Server hoch und routet dann mit der vom
 * Server vergebenen `profileid`.
 */
const val CUSTOM_GRAVEL_PROFILE: String = "custom:gravel"

/**
 * Öffentliches Profil, auf das zurückgefallen wird, wenn das Hochladen
 * des Custom-Profils scheitert.
 */
private const val FALLBACK_PROFILE = "trekking"

/** Ermittelt den öffentlichen BRouter-Profilnamen für ein [RouteProfile]. */
fun brouterProfile(profile: RouteProfile): String = when (profile) {
    RouteProfile.GRAVEL -> "trekking"
    RouteProfile.SCHOTTER -> CUSTOM_GRAVEL_PROFILE
    RouteProfile.ASPHALT -> "fastbike"
    RouteProfile.RADWEGE -> "safety"
    RouteProfile.KUERZESTER -> "shortest"
}

/**
 * Entspricht Darts `_parseNumericProperty`: liest eine GeoJSON-`properties`-
 * Zahl, egal ob sie als JSON-Zahl oder als String codiert ist. Alles andere
 * (fehlend, Bool, nicht-finite) faellt auf 0 zurueck.
 */
private fun parseNumericProperty(element: JsonElement?): Double {
    val prim = element as? JsonPrimitive ?: return 0.0
    val value = prim.content.toDoubleOrNull() ?: return 0.0
    return if (value.isFinite()) value else 0.0
}

/**
 * Zwischengespeicherte `profileid` des hochgeladenen Gravel-Custom-Profils.
 *
 * Der öffentliche Server verwirft hochgeladene Profile nach einiger Zeit,
 * deshalb ist das nur ein Best-Effort-Cache: schlägt ein Routing damit
 * fehl, wird das Profil neu hochgeladen.
 */
private var customGravelProfileId: String? = null

/** Setzt den Profil-Cache zurück (nur für Tests gedacht). */
fun resetCustomProfileCacheForTesting() {
    customGravelProfileId = null
}

/**
 * Lädt das Gravel-Custom-Profil hoch und liefert die vom Server vergebene
 * `profileid` – oder `null`, wenn das Hochladen scheitert.
 */
private fun uploadGravelProfile(client: HttpClient): String? {
    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = "https://brouter.de/brouter/profile",
                body = gravelProfileText(),
            ),
        )
    } catch (e: Exception) {
        return null
    }

    if (!isOk(response)) {
        return null
    }

    val data = try {
        Json.parseToJsonElement(response.body)
    } catch (e: Exception) {
        return null
    }
    if (data !is JsonObject) {
        return null
    }

    // Der Server liefert im Erfolgsfall teils ein leeres `error`-Feld mit.
    val error = data["error"]
    val errorIndicatesFailure = when {
        error == null || error is JsonNull -> false
        error is JsonPrimitive && error.isString && error.content.trim().isEmpty() -> false
        else -> true
    }
    if (errorIndicatesFailure) {
        return null
    }

    val id = data["profileid"] as? JsonPrimitive
    if (id == null || !id.isString || id.content.isEmpty()) {
        return null
    }
    return id.content
}

/** Führt den eigentlichen Routing-Request aus. */
private fun requestRoute(lonlats: String, profileId: String, client: HttpClient): HttpResponse {
    val url = "https://brouter.de/brouter?lonlats=$lonlats&profile=$profileId&alternativeidx=0&format=geojson"
    return try {
        client.execute(HttpRequest(method = HttpMethod.GET, url = url))
    } catch (e: Exception) {
        throw Exception("Routing-Server nicht erreichbar. Bist du online?")
    }
}

private fun isOk(response: HttpResponse): Boolean =
    response.statusCode in 200..299

private fun parseRouteResponse(response: HttpResponse): PlannedRoute {
    if (!isOk(response)) {
        throw Exception("Route konnte nicht berechnet werden: ${response.body}")
    }
    return parseBrouterGeoJson(response.body)
}

/**
 * Routet mit dem eingebetteten Gravel-Custom-Profil.
 *
 * Ablauf: Profil hochladen (bzw. gecachte `profileid` nutzen) → routen.
 * Schlägt das Routing mit der Custom-ID fehl (der Server verwirft Profile
 * nach einiger Zeit), wird einmal neu hochgeladen und erneut versucht.
 * Klappt auch das nicht, wird auf das öffentliche Profil `trekking`
 * zurückgefallen, damit der Nutzer trotzdem eine Route bekommt.
 */
private fun fetchRouteWithCustomGravel(lonlats: String, client: HttpClient): PlannedRoute {
    var profileId = customGravelProfileId
    if (profileId == null) {
        profileId = uploadGravelProfile(client)
        customGravelProfileId = profileId
    }

    if (profileId != null) {
        val response = requestRoute(lonlats, profileId, client)
        if (isOk(response)) {
            return parseBrouterGeoJson(response.body)
        }

        // Vermutlich wurde das hochgeladene Profil serverseitig verworfen:
        // einmal neu hochladen und wiederholen.
        customGravelProfileId = null
        val freshId = uploadGravelProfile(client)
        if (freshId != null) {
            customGravelProfileId = freshId
            val retry = requestRoute(lonlats, freshId, client)
            if (isOk(retry)) {
                return parseBrouterGeoJson(retry.body)
            }
            customGravelProfileId = null
        }
    }

    // Fallback: öffentliches Profil, damit immer eine Route herauskommt.
    return parseRouteResponse(requestRoute(lonlats, FALLBACK_PROFILE, client))
}

/**
 * Berechnet eine Route über den öffentlichen BRouter-Server.
 *
 * [profileId] ist entweder ein öffentlicher BRouter-Profilname oder der
 * Sentinel [CUSTOM_GRAVEL_PROFILE]; im zweiten Fall wird das eingebettete
 * Gravel-Profil zunächst auf den Server hochgeladen.
 *
 * [client] wird injiziert (in `:app` produktiv ein echter HTTP-Stack, in
 * Tests ein Fake) — anders als in Dart gibt es hier keinen intern erzeugten
 * Standard-Client, da `:core` keine konkrete HTTP-Implementierung enthält.
 */
fun fetchRoute(waypoints: List<Waypoint>, profileId: String, client: HttpClient): PlannedRoute {
    if (waypoints.size < 2) {
        throw Exception("Mindestens zwei Wegpunkte nötig.")
    }

    val lonlats = waypoints.joinToString("|") { wp ->
        "${toStringAsFixed(wp.lon, 6)},${toStringAsFixed(wp.lat, 6)}"
    }

    return if (profileId == CUSTOM_GRAVEL_PROFILE) {
        fetchRouteWithCustomGravel(lonlats, client)
    } else {
        parseRouteResponse(requestRoute(lonlats, profileId, client))
    }
}

/**
 * Parst ein GeoJSON-Antwortdokument des BRouter-Servers in eine
 * [PlannedRoute]. Öffentlich, damit Tests direkt gegen gecannte
 * Server-Antworten prüfen können.
 */
fun parseBrouterGeoJson(body: String): PlannedRoute {
    val unexpectedFormat = "Unerwartete Antwort vom Routing-Server."

    val data = try {
        Json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw Exception(unexpectedFormat)
    }

    if (data !is JsonObject || data["features"] !is JsonArray) {
        throw Exception(unexpectedFormat)
    }

    val features = data["features"] as JsonArray
    if (features.isEmpty()) {
        throw Exception(unexpectedFormat)
    }
    val feature = features[0]
    if (feature !is JsonObject || !feature.containsKey("geometry") || !feature.containsKey("properties")) {
        throw Exception(unexpectedFormat)
    }

    val geometry = feature["geometry"]
    if (geometry !is JsonObject || geometry["coordinates"] !is JsonArray) {
        throw Exception(unexpectedFormat)
    }

    val coordinates = geometry["coordinates"] as JsonArray
    val points = mutableListOf<TrackPoint>()
    for (coord in coordinates) {
        if (coord !is JsonArray || coord.size < 2) {
            throw Exception(unexpectedFormat)
        }
        val lonPrim = coord[0] as? JsonPrimitive
        val latPrim = coord[1] as? JsonPrimitive
        if (lonPrim == null || lonPrim.isString || latPrim == null || latPrim.isString) {
            throw Exception(unexpectedFormat)
        }
        val lon = lonPrim.content.toDoubleOrNull() ?: throw Exception(unexpectedFormat)
        val lat = latPrim.content.toDoubleOrNull() ?: throw Exception(unexpectedFormat)

        var ele: Double? = null
        if (coord.size > 2) {
            val rawEle = coord[2] as? JsonPrimitive
            if (rawEle != null && !rawEle.isString) {
                val eleValue = rawEle.content.toDoubleOrNull()
                if (eleValue != null && eleValue.isFinite()) {
                    ele = eleValue
                }
            }
        }
        points.add(TrackPoint(lat = lat, lon = lon, ele = ele))
    }

    val properties = feature["properties"]
    val props = properties as? JsonObject ?: JsonObject(emptyMap())

    val distanceM = parseNumericProperty(props["track-length"])
    val ascentM = parseNumericProperty(props["filtered ascend"])

    return PlannedRoute(
        points = points,
        distanceKm = distanceM / 1000,
        ascentM = ascentM,
    )
}
