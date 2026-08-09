package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

// ---------------------------------------------------------------------------
// Serverlast, Leg-Splitting und Fehlermeldungen
// ---------------------------------------------------------------------------

/*
 * ## Warum "operation killed by thread-priority-watchdog"?
 *
 * Der oeffentliche brouter.de laeuft mit einer festen Zahl Routing-Threads
 * (`RouteServer`-Parameter `maxthreads`). Trifft eine neue Anfrage ein,
 * waehrend alle Threads belegt sind, wartet der Server bis zu 2 Sekunden und
 * **killt dann den aeltesten laufenden Thread**. Dessen `RoutingEngine`
 * bricht daraufhin mit genau dieser Meldung ab
 * (`RoutingEngine.java`: `throw new IllegalArgumentException("operation
 * killed by thread-priority-watchdog after … seconds")`) und der Client
 * bekommt HTTP 400 mit dem Text im Body.
 *
 * Das ist also **Lastabwurf, kein Fehler in der Anfrage**. Zwei Dinge machen
 * es wahrscheinlicher, dass ausgerechnet die eigene Anfrage getroffen wird:
 *
 *  * **Laufzeit.** Gekillt wird immer der *aelteste* Thread. Je laenger die
 *    eigene Berechnung dauert, desto sicherer ist sie irgendwann die
 *    aelteste. BRouters Rechenzeit waechst ungefaehr **quadratisch** mit der
 *    Luftlinie; jenseits von rund 150 km Luftlinie je Wegpunktpaar wird es
 *    kritisch (dazu kommt der harte `maxRunningTime`-Timeout von 60 s, der
 *    sich als "… timeout after 60 seconds" meldet).
 *  * **Momentane Serverlast.** Bei Andrang trifft es auch kurze Anfragen —
 *    genau deshalb hilft ein einzelner Wiederholungsversuch nach einer
 *    kurzen Pause.
 *
 * Daraus die drei Gegenmittel unten: kleine Teilanfragen (Leg-Splitting),
 * genau ein Retry, und eine verstaendliche deutsche Meldung statt des
 * rohen Servertexts.
 */

/**
 * Luftlinie in km, ab der ein **einzelnes** Leg (Wegpunktpaar) mit
 * Zwischenpunkten auf der Geodaete unterteilt wird.
 *
 * 150 km ist die in der BRouter-Doku genannte Groessenordnung, ab der die
 * quadratisch wachsende Rechenzeit den 60-s-Timeout des Servers reisst. Wir
 * unterteilen so, dass **jedes** Teilstueck darunter bleibt.
 */
const val maxLegAirDistanceKm: Double = 150.0

/**
 * Gesamt-Luftlinie in km ueber alle Wegpunktpaare, bis zu der weiterhin
 * **genau eine** Anfrage gestellt wird.
 *
 * Bewusst grosszuegig: der Rundkurs-Generator schickt Polygone mit bis zu
 * [maxRouteTargetKm] (200 km) Sollstrecke, deren Luftlinien-Umfang bei rund
 * 155 km liegt. Mit 300 km bleibt es dort bei einem Request je Kandidat —
 * die Zahl der Server-Aufrufe steigt durch diesen Fix also nicht.
 */
const val singleRequestAirDistanceKm: Double = 300.0

/**
 * Pause vor dem einzigen Wiederholungsversuch nach einem Watchdog-Abbruch.
 *
 * Der Server wartet selbst bis zu 2 s, bevor er einen Thread killt; nach
 * rund 1,5 s ist die kurzzeitige Ueberlast in aller Regel vorbei.
 */
const val watchdogRetryPauseMs: Long = 1500

/** Pause zwischen zwei Teilanfragen einer aufgeteilten Route. */
const val legRequestPauseMs: Long = 250

/** Meldung bei Watchdog-Abbruch bzw. Server-Timeout. */
const val errorServerOverloaded: String =
    "Der Routing-Server ist gerade überlastet oder die Strecke ist zu lang. " +
        "Versuch es mit näheren Wegpunkten noch einmal."

/** Meldung, wenn der Server ohne verwertbaren Text scheitert. */
const val errorRouteFailed: String =
    "Route konnte nicht berechnet werden. Versuch es gleich noch einmal."

/** Hoechstlaenge des in die Meldung uebernommenen Servertexts. */
private const val MAX_SERVER_TEXT_CHARS = 200

/**
 * Erkennt die Server-Antworten, bei denen ein Wiederholungsversuch bzw. eine
 * kuerzere Strecke hilft: den Watchdog-Abbruch und den `maxRunningTime`-
 * Timeout der `RoutingEngine`.
 */
internal fun isServerOverloadBody(body: String): Boolean {
    val lower = body.lowercase()
    return lower.contains("thread-priority-watchdog") ||
        lower.contains("timeout after") ||
        lower.contains("killed by")
}

/**
 * Uebersetzt einen Server-Fehlerbody in eine deutsche Meldung.
 *
 * Bekannte Ueberlast-/Timeout-Faelle bekommen [errorServerOverloaded];
 * alles andere eine generische Meldung, die den Originaltext **in Klammern**
 * mitfuehrt, damit Bugreports weiterhin diagnostizierbar bleiben.
 */
internal fun routingErrorMessage(body: String): String {
    if (isServerOverloadBody(body)) {
        return errorServerOverloaded
    }
    val text = body.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty()) {
        return errorRouteFailed
    }
    val shortened = if (text.length > MAX_SERVER_TEXT_CHARS) {
        text.take(MAX_SERVER_TEXT_CHARS) + "…"
    } else {
        text
    }
    return "Route konnte nicht berechnet werden. (Servermeldung: $shortened)"
}

// ---------------------------------------------------------------------------
// Geodaetische Hilfen fuer das Leg-Splitting
// ---------------------------------------------------------------------------

private fun rad(deg: Double): Double = deg * Math.PI / 180

private fun deg(rad: Double): Double = rad * 180 / Math.PI

/** Luftlinie zwischen zwei Wegpunkten in Metern. */
internal fun airDistanceM(a: Waypoint, b: Waypoint): Double =
    haversineM(TrackPoint(lat = a.lat, lon = a.lon), TrackPoint(lat = b.lat, lon = b.lon))

/**
 * Punkt im Anteil [fraction] (0…1) auf der Geodaete (Grosskreis) von [a]
 * nach [b] — spherische lineare Interpolation.
 *
 * Bewusst *nicht* linear in lat/lon: ueber mehrere hundert Kilometer weicht
 * die Geodaete sichtbar von der Loxodrome ab, und die Zwischenpunkte sollen
 * moeglichst nah an der Ideallinie liegen, damit BRouter sie auf plausible
 * Wege snappt.
 */
internal fun geodesicPoint(a: Waypoint, b: Waypoint, fraction: Double): Waypoint {
    val lat1 = rad(a.lat)
    val lon1 = rad(a.lon)
    val lat2 = rad(b.lat)
    val lon2 = rad(b.lon)

    // Zentriwinkel ueber die Haversine-Form (numerisch stabil bei kleinen Winkeln).
    val sinDLat = sin((lat2 - lat1) / 2)
    val sinDLon = sin((lon2 - lon1) / 2)
    val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
    val delta = 2 * atan2(sqrt(h), sqrt(1 - h))
    if (delta < 1e-12) {
        return a
    }

    val ka = sin((1 - fraction) * delta) / sin(delta)
    val kb = sin(fraction * delta) / sin(delta)
    val x = ka * cos(lat1) * cos(lon1) + kb * cos(lat2) * cos(lon2)
    val y = ka * cos(lat1) * sin(lon1) + kb * cos(lat2) * sin(lon2)
    val z = ka * sin(lat1) + kb * sin(lat2)

    val lat = atan2(z, sqrt(x * x + y * y))
    val lon = atan2(y, x)
    return Waypoint(lat = deg(lat), lon = deg(lon))
}

/**
 * Zerlegt ein Leg [from] → [to] in `[from, …Zwischenpunkte…, to]`, sodass
 * kein Teilstueck laenger als [maxAirKm] Luftlinie ist. Kurze Legs kommen
 * unveraendert als Zweierliste zurueck.
 */
internal fun splitLegOnGeodesic(
    from: Waypoint,
    to: Waypoint,
    maxAirKm: Double = maxLegAirDistanceKm,
): List<Waypoint> {
    val airKm = airDistanceM(from, to) / 1000
    if (!airKm.isFinite() || maxAirKm <= 0 || airKm <= maxAirKm) {
        return listOf(from, to)
    }
    val parts = ceil(airKm / maxAirKm).toInt().coerceIn(2, 64)
    val out = mutableListOf(from)
    for (k in 1 until parts) {
        out.add(geodesicPoint(from, to, k.toDouble() / parts))
    }
    out.add(to)
    return out
}

/**
 * Plant die Server-Anfragen fuer [waypoints]: eine Liste von Legs, von denen
 * jedes **eine** BRouter-Anfrage ergibt.
 *
 * * Bleibt die Gesamt-Luftlinie unter [singleRequestAirDistanceKm] **und**
 *   kein einzelnes Wegpunktpaar ueber [maxLegAirDistanceKm], gibt es genau
 *   ein Leg mit allen Wegpunkten — exakt das Verhalten von frueher, also
 *   auch exakt eine Anfrage.
 * * Sonst wird **je Wegpunktpaar einzeln** geroutet, lange Paare zusaetzlich
 *   an Zwischenpunkten auf der Geodaete getrennt. Jedes Leg hat dann genau
 *   zwei Punkte. Entscheidend ist, dass jede *Anfrage* kurz bleibt: der
 *   Watchdog killt Threads nach Laufzeit, und ein Request mit vielen
 *   Via-Punkten laeuft weiterhin am Stueck in **einem** Server-Thread.
 */
internal fun planRouteLegs(waypoints: List<Waypoint>): List<List<Waypoint>> {
    val pairs = (1 until waypoints.size).map { waypoints[it - 1] to waypoints[it] }
    val airKm = pairs.map { (a, b) -> airDistanceM(a, b) / 1000 }
    val totalKm = airKm.sum()
    val longestKm = airKm.maxOrNull() ?: 0.0

    if (totalKm <= singleRequestAirDistanceKm && longestKm <= maxLegAirDistanceKm) {
        return listOf(waypoints)
    }

    val legs = mutableListOf<List<Waypoint>>()
    for ((a, b) in pairs) {
        val chain = splitLegOnGeodesic(a, b)
        for (i in 1 until chain.size) {
            legs.add(listOf(chain[i - 1], chain[i]))
        }
    }
    return legs
}

/** Gleicher Punkt an einer Nahtstelle? Toleranz rund 2 m. */
private fun isSeamDuplicate(a: TrackPoint, b: TrackPoint): Boolean =
    haversineM(a, b) < 2.0

/**
 * Setzt die Teilrouten zu einer Route zusammen: Distanzen und Hoehenmeter
 * werden aufsummiert, die Punktlisten aneinandergehaengt — der erste Punkt
 * eines Teilstuecks entfaellt, wenn er (bis auf Rundung) mit dem letzten
 * Punkt des vorigen identisch ist, damit an den Nahtstellen kein doppelter
 * Punkt steht.
 */
internal fun concatRouteLegs(parts: List<PlannedRoute>): PlannedRoute {
    val points = mutableListOf<TrackPoint>()
    for (part in parts) {
        var next: List<TrackPoint> = part.points
        if (points.isNotEmpty() && next.isNotEmpty() && isSeamDuplicate(points.last(), next.first())) {
            next = next.subList(1, next.size)
        }
        points.addAll(next)
    }
    return PlannedRoute(
        points = points,
        distanceKm = parts.sumOf { it.distanceKm },
        ascentM = parts.sumOf { it.ascentM },
    )
}

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

/** Setzt genau eine Routing-Anfrage ab. */
private fun requestRouteOnce(lonlats: String, profileId: String, client: HttpClient): HttpResponse {
    val url = "https://brouter.de/brouter?lonlats=$lonlats&profile=$profileId&alternativeidx=0&format=geojson"
    return try {
        client.execute(HttpRequest(method = HttpMethod.GET, url = url))
    } catch (e: Exception) {
        throw Exception("Routing-Server nicht erreichbar. Bist du online?")
    }
}

/**
 * Führt den eigentlichen Routing-Request aus und wiederholt ihn **genau
 * einmal**, wenn der Server mit dem Watchdog-/Timeout-Abbruch geantwortet hat.
 *
 * Das ist Momentlast auf einer Gemeinschaftsressource — mehr als ein
 * Wiederholungsversuch wuerde die Ueberlast nur verlaengern, deshalb wird
 * danach sauber aufgegeben. Die Pause laeuft ueber [sleeper], damit Tests
 * nicht real warten.
 */
private fun requestRoute(
    lonlats: String,
    profileId: String,
    client: HttpClient,
    sleeper: (Long) -> Unit,
): HttpResponse {
    val first = requestRouteOnce(lonlats, profileId, client)
    if (isOk(first) || !isServerOverloadBody(first.body)) {
        return first
    }
    sleeper(watchdogRetryPauseMs)
    return requestRouteOnce(lonlats, profileId, client)
}

private fun isOk(response: HttpResponse): Boolean =
    response.statusCode in 200..299

private fun parseRouteResponse(response: HttpResponse): PlannedRoute {
    if (!isOk(response)) {
        throw Exception(routingErrorMessage(response.body))
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
private fun fetchRouteWithCustomGravel(
    lonlats: String,
    client: HttpClient,
    sleeper: (Long) -> Unit,
): PlannedRoute {
    var profileId = customGravelProfileId
    if (profileId == null) {
        profileId = uploadGravelProfile(client)
        customGravelProfileId = profileId
    }

    if (profileId != null) {
        val response = requestRoute(lonlats, profileId, client, sleeper)
        if (isOk(response)) {
            return parseBrouterGeoJson(response.body)
        }
        // Ueberlast liegt nicht am Profil: [requestRoute] hat bereits einmal
        // wiederholt, weitere Anfragen wuerden den Server nur zusaetzlich
        // belasten. Also direkt mit der deutschen Meldung aufgeben.
        if (isServerOverloadBody(response.body)) {
            throw Exception(errorServerOverloaded)
        }

        // Vermutlich wurde das hochgeladene Profil serverseitig verworfen:
        // einmal neu hochladen und wiederholen.
        customGravelProfileId = null
        val freshId = uploadGravelProfile(client)
        if (freshId != null) {
            customGravelProfileId = freshId
            val retry = requestRoute(lonlats, freshId, client, sleeper)
            if (isOk(retry)) {
                return parseBrouterGeoJson(retry.body)
            }
            customGravelProfileId = null
            if (isServerOverloadBody(retry.body)) {
                throw Exception(errorServerOverloaded)
            }
        }
    }

    // Fallback: öffentliches Profil, damit immer eine Route herauskommt.
    return parseRouteResponse(requestRoute(lonlats, FALLBACK_PROFILE, client, sleeper))
}

/** Routet **ein** Leg (eine Server-Anfrage) und liefert das Teilergebnis. */
private fun fetchLeg(
    waypoints: List<Waypoint>,
    profileId: String,
    client: HttpClient,
    sleeper: (Long) -> Unit,
): PlannedRoute {
    val lonlats = waypoints.joinToString("|") { wp ->
        "${toStringAsFixed(wp.lon, 6)},${toStringAsFixed(wp.lat, 6)}"
    }
    return if (profileId == CUSTOM_GRAVEL_PROFILE) {
        fetchRouteWithCustomGravel(lonlats, client, sleeper)
    } else {
        parseRouteResponse(requestRoute(lonlats, profileId, client, sleeper))
    }
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
 *
 * ## Weite Wegpunkte
 *
 * Liegen die Wegpunkte weit auseinander, wird die Anfrage **transparent**
 * zerlegt (siehe [planRouteLegs]) und das Ergebnis mit [concatRouteLegs]
 * wieder zusammengesetzt — Aufrufer merken davon nichts ausser dem
 * optionalen [onProgress]. Kurze Routen ergeben nach wie vor genau eine
 * Server-Anfrage.
 *
 * @param sleeper Wartefunktion für die Pausen zwischen Teilanfragen und vor
 *   dem Watchdog-Retry; injizierbar, damit Tests nicht real warten.
 * @param onProgress Fortschritt `(erledigte Legs, Legs gesamt)`; wird auch
 *   bei nur einem Leg aufgerufen (`0/1`, dann `1/1`).
 */
fun fetchRoute(
    waypoints: List<Waypoint>,
    profileId: String,
    client: HttpClient,
    sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
): PlannedRoute {
    if (waypoints.size < 2) {
        throw Exception("Mindestens zwei Wegpunkte nötig.")
    }

    val legs = planRouteLegs(waypoints)
    onProgress?.invoke(0, legs.size)

    val parts = mutableListOf<PlannedRoute>()
    for ((index, leg) in legs.withIndex()) {
        if (index > 0) {
            sleeper(legRequestPauseMs)
        }
        parts.add(fetchLeg(leg, profileId, client, sleeper))
        onProgress?.invoke(index + 1, legs.size)
    }

    return if (parts.size == 1) parts.single() else concatRouteLegs(parts)
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
