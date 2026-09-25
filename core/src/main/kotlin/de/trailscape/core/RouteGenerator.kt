package de.trailscape.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rundkurs-Generierung: aus einem [RouteTarget] und einem Startpunkt werden
 * ueber ein [RoutingBackend] mehrere geschlossene Runden gebaut und bewertet.
 *
 * ## Algorithmus
 *
 * **1. Kreiskonstruktion.** Eine Runde der Laenge `d` entspricht grob einem
 * Kreis mit Umfang `d`, also Radius `d / 2π`. Der Kreis liegt *nicht* um den
 * Start herum, sondern der **Start liegt auf dem Kreis**: der Mittelpunkt `C`
 * ist der Punkt in Entfernung `r` vom Start `S` in Richtung des Start-Bearings
 * `β`. Auf diesem Kreis werden `N` Via-Punkte gleichmaessig verteilt (Start
 * ist der `N+1`-te Punkt), die Route ist `S → v1 → … → vN → S`.
 *
 * **2. Korrekturfaktor.** Der reale Weg ist laenger als das Polygon durch die
 * Via-Punkte, weil BRouter Wegen folgt statt Luftlinien; das einbeschriebene
 * Polygon ist umgekehrt kuerzer als der Kreis (bei `N+1 = 5` Ecken rund 94 %
 * des Umfangs). Netto liegt die gefahrene Strecke ueber dem Kreisumfang,
 * deshalb wird der Radius **verkleinert**:
 * `r = d / (2π × `[circuitDetourFactor]`)`. Der Startwert 1,25 ist die Mitte
 * der beobachteten Spanne (Polygonverkuerzung ≈ 0,94 × Umwegfaktor ≈ 1,3).
 * Er ist nur ein Startwert — Schritt 3 zieht ihn ohnehin nach.
 *
 * **3. Radius-Iteration.** Weicht die gelieferte Distanz um mehr als
 * [routeToleranceRatio] (10 %) vom Ziel ab, wird der Radius proportional
 * nachgefuehrt (`r × Ziel/Ist`, geklemmt auf Faktor 0,5…2,0, damit ein
 * Ausreisser die Suche nicht zerreisst) und erneut geroutet — hoechstens
 * [maxRadiusAttempts] Versuche je Kandidat. Behalten wird immer der Versuch
 * mit der kleinsten relativen Abweichung, auch wenn die Toleranz nie erreicht
 * wird.
 *
 * **4. Kandidaten.** [candidates] Runden mit gleichmaessig verteilten
 * Start-Bearings (`0°, 120°, 240°` bei drei Kandidaten). Die Variation ueber
 * den [seed] laeuft ueber den **goldenen Winkel** (137,508°): aufeinander
 * folgende Seeds legen die Runden maximal weit auseinander, statt sich zu
 * wiederholen — das ist die "Neu wuerfeln"-UX (`seed + 1`). Zusaetzlich
 * wechselt mit der Seed-Paritaet der Umlaufsinn und mit `seed + Index` die
 * Zahl der Via-Punkte zwischen 3 und 4, was die Rundenform sichtbar aendert.
 * **Kein `Math.random`** — gleicher Seed, gleiche Runden, testbar.
 *
 * **5. Bewertung.** [RouteCandidate.score] sind **Strafpunkte, kleiner ist
 * besser**; sortiert wird aufsteigend.
 *
 *  * Distanz: `100 × |Ist − Ziel| / Ziel` — 10 % Abweichung kosten 10 Punkte
 *    und dominieren damit alles andere.
 *  * Hoehenmeter, gemessen als Steigungsdichte `m/km`:
 *    * [AscentPreference.FLACH]: `2 × max(0, m/km − 8)`. Bis 8 m/km ist eine
 *      Runde im Flachland/leicht welligem Terrain straffrei, darueber wird es
 *      teuer.
 *    * [AscentPreference.MODERAT]: `1,5 ×` Abstand zum Band 8…16 m/km — welliges
 *      Terrain ist erwuenscht, flach wie bergig kostet.
 *    * [AscentPreference.BERGIG]: `−1 × min(m/km, 15)` — Hoehenmeter werden
 *      belohnt, aber nur bis 15 m/km; jenseits von 25 m/km wird es mit
 *      `1 × (m/km − 25)` wieder bestraft, damit keine unfahrbare Rampenrunde
 *      gewinnt.
 *  * Neue Gegenden (nur mit `preferNewAreas = true`): `−12 × Neu-Anteil`,
 *    wobei Neu-Anteil = [RouteCandidate.newTileCount] /
 *    [RouteCandidate.totalTileCount] (Entdeckt-Kacheln, siehe
 *    `ExplorerTiles.kt`). Der Bonus ist bewusst **gedeckelt** und am Anteil
 *    statt an der absoluten Zahl festgemacht — eine laengere Runde beruehrt
 *    automatisch mehr Kacheln und soll dafuer nicht zusaetzlich belohnt
 *    werden. Das Gewicht [noveltyScoreWeight] = 12 entspricht 12 Prozentpunkten
 *    Distanzabweichung: Eine komplett neue Runde schlaegt eine voellig
 *    bekannte, die bis zu 12 Prozentpunkte besser passt; eine halb neue
 *    (Anteil 0,5) wiegt noch 6 Prozentpunkte auf. Liegt eine Runde aber mehr
 *    als ~12 Prozentpunkte weiter daneben als die Alternative, gewinnt immer
 *    die passendere — die Zieldistanz bleibt das Hauptkriterium, auch knapp
 *    jenseits der 10-%-Toleranz aus Schritt 3. Ohne den Schalter bleibt der
 *    Score exakt wie zuvor.
 *
 * **6. Kursneigung zu neuen Gegenden.** Mit `preferNewAreas = true` (und einer
 * nicht leeren Menge entdeckter Kacheln) prueft der Generator je Kandidat vor
 * dem ersten Routing drei Start-Bearings — den regulaeren `β` sowie `β ± δ`
 * mit `δ = 360° / (4 × Kandidaten)` — und nimmt den, dessen Luftlinien-Polygon
 * die meisten unentdeckten Kacheln streift ([unexploredTilesAlong]). Das kostet
 * keinen einzigen zusaetzlichen Routing-Aufruf. Das Fenster ist klein genug,
 * dass sich benachbarte Kandidaten nie ueberholen und "Neu wuerfeln" weiterhin
 * andere Runden liefert; bei Gleichstand bleibt es bei `β`.
 *
 * ## Betrieb
 *
 * **Streng sequenziell** — laeuft das Routing ueber den oeffentlichen
 * BRouter-Server, ist der eine Gemeinschaftsressource. Zwischen zwei
 * Routing-Aufrufen liegt deshalb eine Pause von [defaultRequestPauseMs]; sie
 * ist ueber den Parameter `sleeper` injizierbar, damit Tests nicht real
 * warten. Wie geroutet wird, weiss die Generierung selbst nicht mehr: Sie
 * bekommt ein [RoutingBackend] hereingereicht (in der App das
 * Offline-zuerst-Routing der manuellen Planung, in Tests ein Fake) und ist
 * dafuer `suspend` — der Aufrufer waehlt den Dispatcher.
 */

/** Mittlerer Erdradius in Metern (wie in `Stats.kt`). */
private const val EARTH_RADIUS_M = 6371000.0

/** Untergrenze der Zieldistanz in km — darunter ist kein sinnvoller Rundkurs planbar. */
const val minRouteTargetKm: Double = 5.0

/** Obergrenze der Zieldistanz in km. */
const val maxRouteTargetKm: Double = 200.0

/** Zulaessige relative Abweichung vom Ziel, ab der der Radius nachgefuehrt wird. */
const val routeToleranceRatio: Double = 0.10

/** Maximale Routing-Versuche je Kandidat (inkl. erstem Versuch). */
const val maxRadiusAttempts: Int = 3

/** Startwert des Korrekturfaktors zwischen Kreisumfang und real gefahrener Strecke. */
const val circuitDetourFactor: Double = 1.25

/** Pause zwischen zwei Routing-Aufrufen in ms (Ruecksicht auf den geteilten Server). */
const val defaultRequestPauseMs: Long = 250

/** Goldener Winkel in Grad — verteilt aufeinanderfolgende Seeds maximal gleichmaessig. */
private const val GOLDEN_ANGLE_DEG = 137.50776405003785

/** Gewicht der relativen Distanzabweichung in Strafpunkten (100 = 1 Punkt je Prozent). */
private const val DISTANCE_WEIGHT = 100.0

/**
 * Maximaler Bonus in Strafpunkten fuer eine Runde, deren Kacheln **alle**
 * unentdeckt sind (nur mit `preferNewAreas`). 12 Punkte = 12 Prozentpunkte
 * Distanzabweichung — Begruendung siehe Datei-KDoc, Schritt 5.
 */
const val noveltyScoreWeight: Double = 12.0

/** Fehlermeldung, wenn kein einziger Kandidat zustande kommt. */
const val errorNoRouteFound: String =
    "Es ließ sich keine passende Runde berechnen. Versuche einen anderen Startpunkt " +
        "oder eine andere Zieldistanz."

/** Ein bewerteter Rundkurs-Vorschlag. */
data class RouteCandidate(
    /** Die berechnete Route (Punkte, Distanz, Hoehenmeter) wie vom [RoutingBackend] geliefert. */
    val route: PlannedRoute,
    /** Distanz in km — identisch mit [PlannedRoute.distanceKm], hier fuer bequemes Sortieren. */
    val distanceKm: Double,
    /** Hoehenmeter im Anstieg. */
    val ascentM: Double,
    /** Strafpunkte — **kleiner ist besser**, die Liste aus [generateRoutes] ist aufsteigend sortiert. */
    val score: Double,
    /** Start-Bearing dieses Kandidaten in Grad (0 = Nord, im Uhrzeigersinn). */
    val bearingDeg: Double,
    /** Tatsaechlich verwendete Zieldistanz in km (nach Anheben/Deckeln, siehe [hints]). */
    val targetKm: Double,
    /** Deutschsprachige Hinweise zum Ergebnis, z. B. wenn die Zieldistanz angepasst wurde. */
    val hints: List<String> = emptyList(),
    /**
     * Entdeckt-Kacheln (Stufe [EXPLORER_TILE_ZOOM]) auf dieser Route, die noch
     * **nicht** in der uebergebenen Menge entdeckter Kacheln liegen — die Zahl
     * hinter „+9 neu". Ohne uebergebene Menge zaehlt jede Kachel als neu.
     */
    val newTileCount: Int = 0,
    /** Alle Entdeckt-Kacheln, durch die die Route fuehrt (neue wie bekannte). */
    val totalTileCount: Int = 0,
) {
    /** Anteil neuer Kacheln an allen Kacheln der Route (0…1, 0 ohne Kacheln). */
    val newTileShare: Double get() = if (totalTileCount > 0) newTileCount.toDouble() / totalTileCount else 0.0

    /** Steigungsdichte in Hoehenmetern pro Kilometer. */
    val ascentPerKm: Double get() = if (distanceKm > 0) ascentM / distanceKm else 0.0

    /** Relative Abweichung vom Ziel (0,1 = 10 % zu lang oder zu kurz). */
    val distanceDeviation: Double get() = if (targetKm > 0) abs(distanceKm - targetKm) / targetKm else 0.0
}

// ---------------------------------------------------------------------------
// Geometrie
// ---------------------------------------------------------------------------

private fun toRad(deg: Double): Double = deg * PI / 180

private fun toDeg(rad: Double): Double = rad * 180 / PI

/**
 * Zielpunkt in [distanceM] Entfernung von [from] unter dem Kurs [bearingDeg]
 * (0 = Nord, im Uhrzeigersinn), auf der Kugel gerechnet — Gegenstueck zu
 * [haversineM].
 */
internal fun destinationPoint(from: TrackPoint, bearingDeg: Double, distanceM: Double): TrackPoint {
    val angular = distanceM / EARTH_RADIUS_M
    val bearing = toRad(bearingDeg)
    val lat1 = toRad(from.lat)
    val lon1 = toRad(from.lon)

    val sinLat2 = sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing)
    val lat2 = asin(clamp(sinLat2, -1.0, 1.0))
    val lon2 = lon1 + atan2(
        sin(bearing) * sin(angular) * cos(lat1),
        cos(angular) - sin(lat1) * sinLat2,
    )

    // Laenge auf −180…180 normalisieren, damit BRouter-URLs nicht ueberlaufen.
    val lonDeg = ((toDeg(lon2) + 540) % 360) - 180
    return TrackPoint(lat = toDeg(lat2), lon = lonDeg)
}

/**
 * Wegpunkte einer Runde: Start, [viaCount] Punkte auf dem Kreis durch den
 * Start, wieder Start.
 *
 * Der Kreismittelpunkt liegt in [radiusM] Entfernung unter dem Kurs
 * [bearingDeg]; der Start liegt damit selbst auf dem Kreis. Die Via-Punkte
 * sind gleichmaessig ueber die restlichen `viaCount` von `viaCount + 1`
 * Kreisabschnitten verteilt; [clockwise] dreht den Umlaufsinn um.
 */
internal fun loopWaypoints(
    start: TrackPoint,
    radiusM: Double,
    bearingDeg: Double,
    viaCount: Int,
    clockwise: Boolean,
): List<Waypoint> {
    val vias = viaCount.coerceIn(1, 12)
    val center = destinationPoint(start, bearingDeg, radiusM)
    // Der Start liegt vom Mittelpunkt aus in Gegenrichtung.
    val startAngle = bearingDeg + 180
    val step = 360.0 / (vias + 1)
    val sign = if (clockwise) 1.0 else -1.0

    val points = mutableListOf(Waypoint(lat = start.lat, lon = start.lon))
    for (k in 1..vias) {
        val p = destinationPoint(center, startAngle + sign * k * step, radiusM)
        points.add(Waypoint(lat = p.lat, lon = p.lon))
    }
    points.add(Waypoint(lat = start.lat, lon = start.lon))
    return points
}

// ---------------------------------------------------------------------------
// Bewertung
// ---------------------------------------------------------------------------

/** Obergrenze der straffreien Steigungsdichte bei [AscentPreference.FLACH]. */
const val flatAscentPerKmLimit: Double = 8.0

/** Untere Bandgrenze bei [AscentPreference.MODERAT]. */
const val moderateAscentPerKmLow: Double = 8.0

/** Obere Bandgrenze bei [AscentPreference.MODERAT]. */
const val moderateAscentPerKmHigh: Double = 16.0

/** Bis hierhin werden Hoehenmeter bei [AscentPreference.BERGIG] belohnt. */
const val hillyAscentPerKmReward: Double = 15.0

/** Ab hier wird es auch bei [AscentPreference.BERGIG] wieder bestraft. */
const val hillyAscentPerKmLimit: Double = 25.0

/**
 * Strafpunkte fuer das Hoehenprofil, gemessen an der Steigungsdichte
 * [ascentPerKm]. Negative Werte sind Bonus (nur bei [AscentPreference.BERGIG]).
 */
fun ascentScore(ascentPerKm: Double, preference: AscentPreference): Double {
    val mkm = if (ascentPerKm.isFinite() && ascentPerKm > 0) ascentPerKm else 0.0
    return when (preference) {
        AscentPreference.FLACH -> 2.0 * kotlin.math.max(0.0, mkm - flatAscentPerKmLimit)
        AscentPreference.MODERAT -> when {
            mkm < moderateAscentPerKmLow -> 1.5 * (moderateAscentPerKmLow - mkm)
            mkm > moderateAscentPerKmHigh -> 1.5 * (mkm - moderateAscentPerKmHigh)
            else -> 0.0
        }
        AscentPreference.BERGIG ->
            -min(mkm, hillyAscentPerKmReward) +
                kotlin.math.max(0.0, mkm - hillyAscentPerKmLimit)
    }
}

/**
 * Bonus (als negative Strafpunkte) fuer den Anteil neuer Kacheln:
 * `−`[noveltyScoreWeight]` × Anteil`, Anteil auf 0…1 geklemmt. Nicht endliche
 * Werte zaehlen als 0 — ein kaputter Anteil darf nie die Sortierung kippen.
 */
fun noveltyScore(newTileShare: Double): Double {
    val share = if (newTileShare.isFinite()) newTileShare.coerceIn(0.0, 1.0) else 0.0
    return -noveltyScoreWeight * share
}

/**
 * Gesamtstrafe eines Kandidaten: Distanzabweichung (stark gewichtet) plus
 * [ascentScore] plus — nur wenn uebergeben — [noveltyScore] fuer den Anteil
 * neuer Kacheln. Mit dem Vorgabewert `newTileShare = 0` ist das Ergebnis
 * identisch mit der Bewertung ohne Entdecker-Modus.
 */
fun scoreRoute(
    distanceKm: Double,
    ascentM: Double,
    targetKm: Double,
    preference: AscentPreference,
    newTileShare: Double = 0.0,
): Double {
    val deviation = if (targetKm > 0) abs(distanceKm - targetKm) / targetKm else 1.0
    val perKm = if (distanceKm > 0) ascentM / distanceKm else 0.0
    return DISTANCE_WEIGHT * deviation + ascentScore(perKm, preference) + noveltyScore(newTileShare)
}

/**
 * Kacheln entlang [points], die nicht in [explored] liegen, und die
 * Gesamtzahl der beruehrten Kacheln — als `(neu, gesamt)`.
 */
internal fun countNewTiles(points: List<TrackPoint>, explored: Set<ExplorerTile>): Pair<Int, Int> {
    val tiles = explorerTilesForTrack(points)
    val fresh = if (explored.isEmpty()) tiles.size else tiles.count { it !in explored }
    return fresh to tiles.size
}

/**
 * Unentdeckte Kacheln entlang des Luftlinien-Polygons einer geplanten Runde
 * (vor dem Routing) — Grundlage der Kursneigung (Datei-KDoc, Schritt 6).
 */
internal fun unexploredTilesAlong(waypoints: List<Waypoint>, explored: Set<ExplorerTile>): Int =
    countNewTiles(waypoints.map { TrackPoint(lat = it.lat, lon = it.lon) }, explored).first

// ---------------------------------------------------------------------------
// Generierung
// ---------------------------------------------------------------------------

/**
 * Die Routing-Abstraktion der Rundkurs-Generierung.
 *
 * Der Generator weiss damit nichts mehr ueber HTTP oder lokale Kacheln — er
 * reicht Wegpunkte und das gewaehlte [RouteProfile] hinein und bekommt eine
 * fertige Strecke zurueck. In der App steckt dahinter dasselbe
 * Offline-zuerst-Routing wie hinter der manuellen Planung
 * (`routing/OfflineFirstPlanner.kt`), in Tests ein Fake.
 */
fun interface RoutingBackend {
    /**
     * Routet [waypoints] mit [profile] zu einer Strecke.
     *
     * @throws Exception mit deutschsprachiger Meldung, wenn keine Route
     *   zustande kommt (kein Netz, keine Kacheln, kein Weg — die Meldung
     *   traegt die Ursache).
     */
    suspend fun route(waypoints: List<Waypoint>, profile: RouteProfile): PlannedRoute
}

/** Zwischenergebnis eines Routing-Versuchs. */
private class Attempt(val route: PlannedRoute, val deviation: Double)

/**
 * Erzeugt bewertete Rundkurs-Vorschlaege ab [start], die [target] moeglichst
 * gut treffen. Details zum Algorithmus siehe Datei-KDoc.
 *
 * Die Liste ist aufsteigend nach [RouteCandidate.score] sortiert (kleiner =
 * besser). Einzelne fehlgeschlagene Kandidaten werden stillschweigend
 * uebersprungen; erst wenn **alle** scheitern, wirft die Funktion eine
 * [Exception], deren Meldung mit [errorNoRouteFound] beginnt und — wenn das
 * Backend eine Ursache genannt hat — diese in Klammern anfuegt (z. B. „kein
 * Netz“ vs. „kein Weg gefunden“; die letzte Backend-Ausnahme haengt
 * zusaetzlich als `cause` daran).
 *
 * @param backend Routing-Abstraktion (in `:app` das Offline-zuerst-Routing
 *   der manuellen Planung, in Tests ein Fake).
 * @param start Startpunkt; der Rundkurs beginnt und endet hier.
 * @param target Zielvorgabe aus `SessionTarget.kt`.
 * @param profile Fahrprofil, mit dem alle Kandidaten gerechnet werden;
 *   Vorgabe ist das Gravel-Profil [RouteProfile.SCHOTTER].
 * @param seed Deterministische Variation — `seed + 1` liefert andere Runden.
 * @param candidates Anzahl der Vorschlaege (1…8).
 * @param pauseMs Pause zwischen zwei Routing-Aufrufen.
 * @param sleeper Wartefunktion, injizierbar fuer Tests und fuer den Abbruch
 *   von aussen (wirft sie, verlaesst die Generierung sofort).
 * @param onProgress Fortschritt `(erledigt, gesamt)` nach jedem Kandidaten.
 * @param exploredTiles Bereits entdeckte Kacheln (z. B. aus
 *   [collectExplorerTiles]); daraus ergibt sich [RouteCandidate.newTileCount].
 * @param preferNewAreas Schalter „Neue Gegenden bevorzugen": Bonus fuer neue
 *   Kacheln im Score (Schritt 5) und Kursneigung (Schritt 6). Aus (Vorgabe),
 *   bleiben Bearings und Scores unveraendert.
 */
suspend fun generateRoutes(
    backend: RoutingBackend,
    start: TrackPoint,
    target: RouteTarget,
    profile: RouteProfile = RouteProfile.SCHOTTER,
    seed: Int = 0,
    candidates: Int = 3,
    pauseMs: Long = defaultRequestPauseMs,
    sleeper: suspend (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
    exploredTiles: Set<ExplorerTile> = emptySet(),
    preferNewAreas: Boolean = false,
): List<RouteCandidate> {
    val hints = mutableListOf<String>()
    val rawKm = if (target.distanceKm.isFinite()) target.distanceKm else 0.0
    var targetKm = rawKm
    if (targetKm < minRouteTargetKm) {
        targetKm = minRouteTargetKm
        hints.add(
            "Zieldistanz auf ${minRouteTargetKm.toInt()} km angehoben – kürzere Rundkurse " +
                "lassen sich nicht sinnvoll planen.",
        )
    }
    if (targetKm > maxRouteTargetKm) {
        targetKm = maxRouteTargetKm
        hints.add(
            "Zieldistanz auf ${maxRouteTargetKm.toInt()} km gedeckelt – längere Runden " +
                "berechnet der Routing-Server nicht zuverlässig.",
        )
    }

    val total = candidates.coerceIn(1, 8)
    val seedOffset = (seed * GOLDEN_ANGLE_DEG) % 360.0
    // Umlaufsinn wechselt mit der Seed-Paritaet (Math.floorMod: auch fuer negative Seeds).
    val clockwise = Math.floorMod(seed, 2) == 0

    val results = mutableListOf<RouteCandidate>()
    var requestsMade = 0

    // Die letzte Backend-Ausnahme — scheitern ALLE Kandidaten, ist sie die
    // konkreteste Auskunft ueber das Warum und gehoert in die Fehlermeldung.
    var lastFailure: Exception? = null

    onProgress?.invoke(0, total)

    for (i in 0 until total) {
        val baseBearing = ((i * 360.0 / total) + seedOffset).mod(360.0)
        // 3 oder 4 Via-Punkte, deterministisch wechselnd — aendert die Rundenform.
        val viaCount = 3 + Math.floorMod(seed + i, 2)

        var radiusM = targetKm * 1000 / (2 * PI * circuitDetourFactor)

        // Kursneigung zu unentdeckten Kacheln (Schritt 6): nur Geometrie, kein Routing.
        val bearing = if (preferNewAreas && exploredTiles.isNotEmpty()) {
            val delta = 360.0 / (4 * total)
            // Reihenfolge = Tie-Break: maxBy nimmt das erste Maximum, bei
            // Gleichstand bleibt es also beim regulaeren Kurs.
            listOf(0.0, -delta, delta)
                .map { (baseBearing + it).mod(360.0) }
                .maxBy { b ->
                    unexploredTilesAlong(loopWaypoints(start, radiusM, b, viaCount, clockwise), exploredTiles)
                }
        } else {
            baseBearing
        }
        var best: Attempt? = null

        for (attempt in 0 until maxRadiusAttempts) {
            if (requestsMade > 0) {
                sleeper(pauseMs)
            }
            requestsMade += 1

            val waypoints = loopWaypoints(start, radiusM, bearing, viaCount, clockwise)
            val route = try {
                backend.route(waypoints, profile)
            } catch (e: CancellationException) {
                // Ein Abbruch der umgebenden Coroutine ist kein gescheiterter
                // Kandidat — er muss die Generierung als Ganzes verlassen.
                throw e
            } catch (e: Exception) {
                // Kandidat aufgeben, spaetere Kandidaten bekommen ihre Chance.
                lastFailure = e
                break
            }

            if (!route.distanceKm.isFinite() || route.distanceKm <= 0) {
                break
            }

            val deviation = abs(route.distanceKm - targetKm) / targetKm
            if (best == null || deviation < best.deviation) {
                best = Attempt(route, deviation)
            }
            if (deviation <= routeToleranceRatio) {
                break
            }

            // Proportionale Nachfuehrung, gegen Ausreisser geklemmt.
            radiusM *= clamp(targetKm / route.distanceKm, 0.5, 2.0)
        }

        val found = best
        if (found != null) {
            val (newTiles, totalTiles) = countNewTiles(found.route.points, exploredTiles)
            val share = if (totalTiles > 0) newTiles.toDouble() / totalTiles else 0.0
            results.add(
                RouteCandidate(
                    route = found.route,
                    distanceKm = found.route.distanceKm,
                    ascentM = found.route.ascentM,
                    score = scoreRoute(
                        distanceKm = found.route.distanceKm,
                        ascentM = found.route.ascentM,
                        targetKm = targetKm,
                        preference = target.ascentPreference,
                        newTileShare = if (preferNewAreas) share else 0.0,
                    ),
                    bearingDeg = bearing,
                    targetKm = targetKm,
                    hints = hints.toList(),
                    newTileCount = newTiles,
                    totalTileCount = totalTiles,
                ),
            )
        }

        onProgress?.invoke(i + 1, total)
    }

    if (results.isEmpty()) {
        // Die Backend-Ursache anfuegen, damit die Oberflaeche „kein Netz /
        // Server nicht erreichbar" von „kein Weg gefunden" unterscheiden kann.
        val detail = lastFailure?.message?.takeIf(String::isNotBlank)
        throw if (detail != null) {
            Exception("$errorNoRouteFound ($detail)", lastFailure)
        } else {
            Exception(errorNoRouteFound, lastFailure)
        }
    }

    return results.sortedWith(compareBy({ it.score }, { it.bearingDeg }))
}
