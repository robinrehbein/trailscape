package de.trailscape.core.i18n

import de.trailscape.core.RouteProfile

/**
 * Texte rund um Routing: Profile, Fehlermeldungen von Server und
 * Geraete-Engine, Rundkurs-Generator, Belag, Wind, Offline-Kacheln und
 * Ortssuche.
 *
 * Fehlermeldungen, die Fremdtext mitfuehren (Server- oder Engine-Meldung),
 * bekommen ihn als Argument und setzen ihn in Klammern — so bleiben
 * Fehlerberichte diagnostizierbar, egal in welcher Sprache die App laeuft.
 */
interface RoutingTexts {
    fun routeProfile(profile: RouteProfile): String

    // Server
    fun serverOverloaded(): String
    fun routeFailed(): String
    fun routeFailedWithServerText(serverText: String): String
    fun serverUnreachable(): String
    fun needTwoWaypoints(): String
    fun unexpectedServerResponse(): String

    // Geraete-Engine
    fun offlineProfileMissing(): String
    fun offlineLookupsMissing(): String
    fun offlineNoSegments(): String
    fun offlineNoTrack(): String
    fun offlineTimeout(): String
    fun offlineMissingTile(tileFile: String): String
    fun routeFailedWithEngineText(engineText: String): String

    /** Beide Wege gescheitert: die lokale Ursache zuerst, die Servermeldung dahinter. */
    fun offlineThenServerFailed(offlineMessage: String, serverMessage: String): String

    // Kacheln
    fun segmentUpdateFailed(detail: String): String
    fun segmentFileDamaged(fileName: String, detail: String): String

    /** Beispielorte einer Kachel, z. B. „Berlin, Leipzig u. a.". */
    fun tileLandmarks(names: List<String>): String

    /** Buchstabe der Himmelsrichtung fuer Gradangaben: N, S, W, O/E. */
    fun hemisphereEast(): String

    /** Ortsname in dieser Sprache (Exonym), sonst unveraendert. */
    fun landmarkName(name: String): String

    /**
     * Namensvorschlag eines Anstiegs-Segments: „Anstieg 4,2 km / 180 Hm" bzw.
     * unter einem Kilometer „Anstieg 800 m / 45 Hm". [distanceKm] ist `null`
     * unter 1 km, dann gilt [distanceM] (auf 10 m gerundet).
     */
    fun climbSegmentName(distanceKm: Double?, distanceM: Int, ascentM: Int): String

    // Rundkurs-Generator
    fun noRouteFound(): String
    fun noRouteFoundWithDetail(detail: String): String
    fun targetDistanceRaised(km: Int): String
    fun targetDistanceCapped(km: Int): String

    // Belag und Gelaende
    fun unpavedShare(percent: Int): String

    // Wind
    fun windOptimisedLabel(): String

    /** Richtung, aus der der Wind kommt (acht Stufen, Index 0 = Nord, im Uhrzeigersinn). */
    fun windDirection(index: Int): String
    fun windDirectionVariable(): String
    fun windLine(speedKmh: Int, direction: String, gustsKmh: Int?, verdict: WindVerdict): String

    // Ortssuche
    fun placeSearchUnreachable(): String
    fun placeSearchFailed(httpStatus: Int): String
    fun placeSearchUnexpected(): String
}

/** Einordnung des Winds fuer eine Runde (Schluss der Windzeile). */
enum class WindVerdict { TOO_WEAK, TAILWIND_HOME, HEADWIND_HOME, CROSSWIND }

internal object RoutingTextsDe : RoutingTexts {
    override fun routeProfile(profile: RouteProfile) = when (profile) {
        RouteProfile.SCHOTTER -> "Gravel (Schotter & unbefestigt)"
        RouteProfile.GRAVEL -> "Trekking (Asphalt & feste Wege gemischt)"
        RouteProfile.ASPHALT -> "Rennrad / Asphalt"
        RouteProfile.RADWEGE -> "Radwege bevorzugt"
        RouteProfile.KUERZESTER -> "Kürzeste Route"
    }

    override fun serverOverloaded() =
        "Der Routing-Server ist gerade überlastet oder die Strecke ist zu lang. " +
            "Versuch es mit näheren Wegpunkten noch einmal."
    override fun routeFailed() = "Route konnte nicht berechnet werden. Versuch es gleich noch einmal."
    override fun routeFailedWithServerText(serverText: String) =
        "Route konnte nicht berechnet werden. (Servermeldung: $serverText)"
    override fun serverUnreachable() = "Routing-Server nicht erreichbar. Bist du online?"
    override fun needTwoWaypoints() = "Mindestens zwei Wegpunkte nötig."
    override fun unexpectedServerResponse() = "Unerwartete Antwort vom Routing-Server."

    override fun offlineProfileMissing() =
        "Das Routing-Profil fehlt. Starte die App neu, damit sie es neu anlegt."
    override fun offlineLookupsMissing() =
        "Die Routing-Merkmalstabelle (lookups.dat) fehlt neben dem Profil. " +
            "Starte die App neu, damit sie sie neu anlegt."
    override fun offlineNoSegments() =
        "Es sind noch keine Offline-Karten gespeichert. Lade zuerst die Karte für " +
            "deine Gegend herunter."
    override fun offlineNoTrack() =
        "Zwischen diesen Punkten wurde keine Route gefunden. Setz sie näher an " +
            "einen befahrbaren Weg."
    override fun offlineTimeout() =
        "Die Berechnung hat zu lange gedauert. Versuch es mit näheren Wegpunkten " +
            "noch einmal."
    override fun offlineMissingTile(tileFile: String) =
        "Für diesen Bereich fehlen die Offline-Kartendaten (Kachel $tileFile). " +
            "Lade sie herunter, um hier ohne Netz zu routen."
    override fun routeFailedWithEngineText(engineText: String) =
        "Route konnte nicht berechnet werden. (Meldung der Routing-Engine: $engineText)"
    override fun offlineThenServerFailed(offlineMessage: String, serverMessage: String) =
        "$offlineMessage Der Routing-Server war anschließend ebenfalls nicht erreichbar " +
            "($serverMessage)."

    override fun segmentUpdateFailed(detail: String) =
        "Die Karten-Aktualisierung ließ sich nicht anwenden ($detail)."
    override fun segmentFileDamaged(fileName: String, detail: String) =
        "Die Kacheldatei $fileName ist beschädigt ($detail)."
    override fun tileLandmarks(names: List<String>) = "${names.joinToString(", ")} u. a."
    override fun hemisphereEast() = "O"
    override fun landmarkName(name: String) = name

    override fun climbSegmentName(distanceKm: Double?, distanceM: Int, ascentM: Int) =
        if (distanceKm != null) {
            "Anstieg ${formatDecimal(distanceKm, 1, AppLanguage.DE)} km / $ascentM Hm"
        } else {
            "Anstieg $distanceM m / $ascentM Hm"
        }

    override fun noRouteFound() =
        "Es ließ sich keine passende Runde berechnen. Versuche einen anderen Startpunkt " +
            "oder eine andere Zieldistanz."
    override fun noRouteFoundWithDetail(detail: String) = "${noRouteFound()} ($detail)"
    override fun targetDistanceRaised(km: Int) =
        "Zieldistanz auf $km km angehoben – kürzere Rundkurse lassen sich nicht sinnvoll planen."
    override fun targetDistanceCapped(km: Int) =
        "Zieldistanz auf $km km gedeckelt – längere Runden berechnet der Routing-Server " +
            "nicht zuverlässig."

    override fun unpavedShare(percent: Int) = "ca. $percent % unbefestigt"

    override fun windOptimisedLabel() = "Rückenwind heim"
    private val directions = listOf("Nord", "Nordost", "Ost", "Südost", "Süd", "Südwest", "West", "Nordwest")
    override fun windDirection(index: Int) = directions[index]
    override fun windDirectionVariable() = "wechselnder Richtung"
    override fun windLine(speedKmh: Int, direction: String, gustsKmh: Int?, verdict: WindVerdict): String {
        val gusts = gustsKmh?.let { ", Böen bis $it km/h" }.orEmpty()
        val tail = when (verdict) {
            WindVerdict.TOO_WEAK -> "zu schwach, um die Runde danach auszurichten"
            WindVerdict.TAILWIND_HOME -> "Rückenwind auf dem Heimweg"
            WindVerdict.HEADWIND_HOME -> "Gegenwind auf dem Heimweg"
            WindVerdict.CROSSWIND -> "Seitenwind, kein klarer Vorteil"
        }
        return "Wind $speedKmh km/h aus $direction$gusts – $tail"
    }

    override fun placeSearchUnreachable() = "Ortssuche nicht erreichbar. Bist du online?"
    override fun placeSearchFailed(httpStatus: Int) = "Ortssuche fehlgeschlagen (HTTP $httpStatus)."
    override fun placeSearchUnexpected() = "Unerwartete Antwort der Ortssuche."
}

internal object RoutingTextsEn : RoutingTexts {
    override fun routeProfile(profile: RouteProfile) = when (profile) {
        RouteProfile.SCHOTTER -> "Gravel (loose & unpaved)"
        RouteProfile.GRAVEL -> "Trekking (mixed tarmac & firm tracks)"
        RouteProfile.ASPHALT -> "Road bike / tarmac"
        RouteProfile.RADWEGE -> "Prefer cycle paths"
        RouteProfile.KUERZESTER -> "Shortest route"
    }

    override fun serverOverloaded() =
        "The routing server is overloaded right now or the route is too long. " +
            "Try again with waypoints closer together."
    override fun routeFailed() = "The route couldn’t be calculated. Try again in a moment."
    override fun routeFailedWithServerText(serverText: String) =
        "The route couldn’t be calculated. (Server message: $serverText)"
    override fun serverUnreachable() = "Can’t reach the routing server. Are you online?"
    override fun needTwoWaypoints() = "At least two waypoints are needed."
    override fun unexpectedServerResponse() = "Unexpected response from the routing server."

    override fun offlineProfileMissing() =
        "The routing profile is missing. Restart the app so it can create it again."
    override fun offlineLookupsMissing() =
        "The routing lookup table (lookups.dat) is missing next to the profile. " +
            "Restart the app so it can create it again."
    override fun offlineNoSegments() =
        "No routing data is stored yet. First download the data for your area."
    override fun offlineNoTrack() =
        "No route was found between these points. Place them closer to a rideable path."
    override fun offlineTimeout() =
        "The calculation took too long. Try again with waypoints closer together."
    override fun offlineMissingTile(tileFile: String) =
        "Routing data for this area is missing (tile $tileFile). " +
            "Download it to route here without a connection."
    override fun routeFailedWithEngineText(engineText: String) =
        "The route couldn’t be calculated. (Routing engine message: $engineText)"
    override fun offlineThenServerFailed(offlineMessage: String, serverMessage: String) =
        "$offlineMessage The routing server couldn’t be reached either ($serverMessage)."

    override fun segmentUpdateFailed(detail: String) =
        "The map data update couldn’t be applied ($detail)."
    override fun segmentFileDamaged(fileName: String, detail: String) =
        "The tile file $fileName is damaged ($detail)."
    override fun tileLandmarks(names: List<String>) = "${names.joinToString(", ")} and more"
    override fun hemisphereEast() = "E"

    /** Englische Namen, wo sie vom deutschen abweichen. */
    private val exonyms = mapOf(
        "München" to "Munich",
        "Köln" to "Cologne",
        "Frankfurt am Main" to "Frankfurt",
        "Wien" to "Vienna",
        "Zürich" to "Zurich",
        "Prag" to "Prague",
        "Brüssel" to "Brussels",
        "Kopenhagen" to "Copenhagen",
        "Warschau" to "Warsaw",
        "Danzig" to "Gdańsk",
        "Krakau" to "Kraków",
        "Rom" to "Rome",
        "Mailand" to "Milan",
        "Lissabon" to "Lisbon",
        "Sevilla" to "Seville",
        "Nizza" to "Nice",
        "Neapel" to "Naples",
        "Göteborg" to "Gothenburg",
        "Kiew" to "Kyiv",
        "Moskau" to "Moscow",
        "Bukarest" to "Bucharest",
        "Belgrad" to "Belgrade",
        "Athen" to "Athens",
        "Mexiko-Stadt" to "Mexico City",
        "Kapstadt" to "Cape Town",
        "Kairo" to "Cairo",
        "Marrakesch" to "Marrakesh",
        "Tokio" to "Tokyo",
    )

    override fun landmarkName(name: String) = exonyms[name] ?: name

    override fun climbSegmentName(distanceKm: Double?, distanceM: Int, ascentM: Int) =
        if (distanceKm != null) {
            "Climb ${formatDecimal(distanceKm, 1, AppLanguage.EN)} km / $ascentM m"
        } else {
            "Climb $distanceM m / $ascentM m"
        }

    override fun noRouteFound() =
        "No suitable loop could be calculated. Try a different starting point " +
            "or a different target distance."
    override fun noRouteFoundWithDetail(detail: String) = "${noRouteFound()} ($detail)"
    override fun targetDistanceRaised(km: Int) =
        "Target distance raised to $km km – shorter loops can’t be planned sensibly."
    override fun targetDistanceCapped(km: Int) =
        "Target distance capped at $km km – the routing server can’t reliably " +
            "calculate longer loops."

    override fun unpavedShare(percent: Int) = "approx. $percent% unpaved"

    override fun windOptimisedLabel() = "Tailwind home"
    private val directions =
        listOf("the north", "the north-east", "the east", "the south-east", "the south", "the south-west", "the west", "the north-west")
    override fun windDirection(index: Int) = directions[index]
    override fun windDirectionVariable() = "varying directions"
    override fun windLine(speedKmh: Int, direction: String, gustsKmh: Int?, verdict: WindVerdict): String {
        val gusts = gustsKmh?.let { ", gusts up to $it km/h" }.orEmpty()
        val tail = when (verdict) {
            WindVerdict.TOO_WEAK -> "too light to plan the loop around"
            WindVerdict.TAILWIND_HOME -> "tailwind on the way home"
            WindVerdict.HEADWIND_HOME -> "headwind on the way home"
            WindVerdict.CROSSWIND -> "crosswind, no clear advantage"
        }
        return "Wind $speedKmh km/h from $direction$gusts – $tail"
    }

    override fun placeSearchUnreachable() = "Can’t reach place search. Are you online?"
    override fun placeSearchFailed(httpStatus: Int) = "Place search failed (HTTP $httpStatus)."
    override fun placeSearchUnexpected() = "Unexpected response from place search."
}
