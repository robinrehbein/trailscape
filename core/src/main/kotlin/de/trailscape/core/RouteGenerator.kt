package de.trailscape.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rundkurs-Generierung: aus einem [RouteTarget] und einem Startpunkt werden
 * ueber den BRouter-Server mehrere geschlossene Runden gebaut und bewertet.
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
 *
 * ## Betrieb
 *
 * Synchron und **streng sequenziell** — der oeffentliche BRouter-Server ist
 * eine Gemeinschaftsressource. Zwischen zwei Requests liegt eine Pause von
 * [defaultRequestPauseMs]; sie ist ueber den Parameter `sleeper` injizierbar,
 * damit Tests nicht real warten. Das UI wrappt den Aufruf mit
 * `Dispatchers.IO`.
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

/** Pause zwischen zwei BRouter-Requests in ms. */
const val defaultRequestPauseMs: Long = 250

/** Goldener Winkel in Grad — verteilt aufeinanderfolgende Seeds maximal gleichmaessig. */
private const val GOLDEN_ANGLE_DEG = 137.50776405003785

/** Gewicht der relativen Distanzabweichung in Strafpunkten (100 = 1 Punkt je Prozent). */
private const val DISTANCE_WEIGHT = 100.0

/** Fehlermeldung, wenn kein einziger Kandidat zustande kommt. */
const val errorNoRouteFound: String =
    "Es ließ sich keine passende Runde berechnen. Versuche einen anderen Startpunkt " +
        "oder eine andere Zieldistanz."

/** Ein bewerteter Rundkurs-Vorschlag. */
data class RouteCandidate(
    /** Die berechnete Route (Punkte, Distanz, Hoehenmeter) wie von [fetchRoute] geliefert. */
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
) {
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

/** Gesamtstrafe eines Kandidaten: Distanzabweichung (stark gewichtet) plus [ascentScore]. */
fun scoreRoute(
    distanceKm: Double,
    ascentM: Double,
    targetKm: Double,
    preference: AscentPreference,
): Double {
    val deviation = if (targetKm > 0) abs(distanceKm - targetKm) / targetKm else 1.0
    val perKm = if (distanceKm > 0) ascentM / distanceKm else 0.0
    return DISTANCE_WEIGHT * deviation + ascentScore(perKm, preference)
}

// ---------------------------------------------------------------------------
// Generierung
// ---------------------------------------------------------------------------

/** Zwischenergebnis eines Routing-Versuchs. */
private class Attempt(val route: PlannedRoute, val deviation: Double)

/**
 * Erzeugt bewertete Rundkurs-Vorschlaege ab [start], die [target] moeglichst
 * gut treffen. Details zum Algorithmus siehe Datei-KDoc.
 *
 * Die Liste ist aufsteigend nach [RouteCandidate.score] sortiert (kleiner =
 * besser). Einzelne fehlgeschlagene Kandidaten werden stillschweigend
 * uebersprungen; erst wenn **alle** scheitern, wirft die Funktion eine
 * [Exception] mit [errorNoRouteFound].
 *
 * @param client HTTP-Abstraktion (in `:app` echter Stack, in Tests ein Fake).
 * @param start Startpunkt; der Rundkurs beginnt und endet hier.
 * @param target Zielvorgabe aus `SessionTarget.kt`.
 * @param seed Deterministische Variation — `seed + 1` liefert andere Runden.
 * @param candidates Anzahl der Vorschlaege (1…8).
 * @param profileId BRouter-Profil; Vorgabe ist das eingebettete Gravel-Profil.
 * @param pauseMs Pause zwischen zwei Server-Requests.
 * @param sleeper Wartefunktion, injizierbar fuer Tests.
 * @param onProgress Fortschritt `(erledigt, gesamt)` nach jedem Kandidaten.
 */
fun generateRoutes(
    client: HttpClient,
    start: TrackPoint,
    target: RouteTarget,
    seed: Int = 0,
    candidates: Int = 3,
    profileId: String = CUSTOM_GRAVEL_PROFILE,
    pauseMs: Long = defaultRequestPauseMs,
    sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
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

    onProgress?.invoke(0, total)

    for (i in 0 until total) {
        val bearing = ((i * 360.0 / total) + seedOffset).mod(360.0)
        // 3 oder 4 Via-Punkte, deterministisch wechselnd — aendert die Rundenform.
        val viaCount = 3 + Math.floorMod(seed + i, 2)

        var radiusM = targetKm * 1000 / (2 * PI * circuitDetourFactor)
        var best: Attempt? = null

        for (attempt in 0 until maxRadiusAttempts) {
            if (requestsMade > 0) {
                sleeper(pauseMs)
            }
            requestsMade += 1

            val waypoints = loopWaypoints(start, radiusM, bearing, viaCount, clockwise)
            val route = try {
                fetchRoute(waypoints, profileId, client)
            } catch (_: Exception) {
                // Kandidat aufgeben, spaetere Kandidaten bekommen ihre Chance.
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
                    ),
                    bearingDeg = bearing,
                    targetKm = targetKm,
                    hints = hints.toList(),
                ),
            )
        }

        onProgress?.invoke(i + 1, total)
    }

    if (results.isEmpty()) {
        throw Exception(errorNoRouteFound)
    }

    return results.sortedWith(compareBy({ it.score }, { it.bearingDeg }))
}
