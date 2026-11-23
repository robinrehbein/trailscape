package de.trailscape.app.indoor

import android.content.Context
import de.trailscape.app.routing.planRouteOfflineFirst
import de.trailscape.app.ui.indoor.IndoorProfileUi
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RouteProfile
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.world.WorldManifest
import kotlinx.coroutines.CancellationException

/**
 * # Woher die Strecke einer Indoor-Fahrt kommt (docs/indoor.md, Abschnitt 4)
 *
 * | Quelle | Belag | `IndoorRouteSource` |
 * |---|---|---|
 * | gespeicherte Planung oder Tour, Abgleich gelungen | `waySections` der Routing-Antwort | `BROUTER_REMATCH` |
 * | Abgleich nicht gewuenscht oder gescheitert | (spaeter der Weltbau aus Vektorkacheln) | `NONE` |
 * | Testwelt | Streckenband des Manifests | wie im Manifest |
 *
 * Der Belagsabgleich ist eine Routing-Anfrage mit den **vorhandenen** Mitteln
 * der App (offline zuerst, Server als Rueckfall, kein neuer Host) und laeuft
 * nur nach einer Nutzeraktion (`rematch = true`: „Welt laden" bzw. Start).
 *
 * Alle fremden Einstiege laufen ueber [IndoorParts] und [indoorCatching].
 */
class IndoorRouteSourcing(
    private val parts: IndoorParts,
    private val router: IndoorRematchRouter?,
) {
    /**
     * Ergebnis der Aufbereitung.
     *
     * @property rematchProblem warum der Abgleich nicht gelang (nur Diagnose),
     *   `null` wenn er gelang oder gar nicht versucht wurde.
     */
    data class Sourced(val route: IndoorRoute, val rematchProblem: IndoorProblem?)

    /**
     * Baut die [IndoorRoute] aus einer gespeicherten Planung oder Tour.
     *
     * @param rematch Belagsabgleich ueber den Router versuchen (nur nach
     *   Nutzeraktion; braucht ggf. Netz).
     * @return `Result.failure` mit [IndoorPartException], wenn sich gar keine
     *   fahrbare Strecke ergibt.
     */
    suspend fun fromRide(ride: Ride, name: String, rematch: Boolean): Result<Sourced> {
        val points = ride.points
        if (points.size < 2) {
            return Result.failure(
                IndoorPartException(
                    IndoorProblem(IndoorPart.APP, false, "Tour ${ride.id} hat weniger als zwei Punkte"),
                    IllegalArgumentException("zu wenige Punkte"),
                ),
            )
        }
        var rematchProblem: IndoorProblem? = null
        if (rematch) {
            when (val matched = rematched(points, name)) {
                is RematchOutcome.Done -> return Result.success(Sourced(matched.route, null))
                is RematchOutcome.Failed -> rematchProblem = matched.problem
            }
        }
        val plain = indoorCatching(IndoorPart.CORE) {
            parts.buildRoute(name, points, null, IndoorRouteSource.NONE)
        }
        return plain.fold(
            onSuccess = { route ->
                if (route != null) {
                    Result.success(Sourced(route, rematchProblem))
                } else {
                    Result.failure(
                        IndoorPartException(
                            IndoorProblem(IndoorPart.CORE, false, "buildIndoorRoute lieferte nichts Fahrbares"),
                            IllegalStateException("keine fahrbare Strecke"),
                        ),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )
    }

    private sealed interface RematchOutcome {
        data class Done(val route: IndoorRoute) : RematchOutcome

        data class Failed(val problem: IndoorProblem) : RematchOutcome
    }

    private suspend fun rematched(points: List<TrackPoint>, name: String): RematchOutcome {
        val router = router ?: return RematchOutcome.Failed(
            IndoorProblem(IndoorPart.APP, false, "kein Router fuer den Belagsabgleich"),
        )
        val waypoints = indoorCatching(IndoorPart.CORE) { parts.rematchWaypoints(points) }
            .getOrElse { return RematchOutcome.Failed(problemOf(IndoorPart.CORE, it)) }
        if (waypoints.size < 2) {
            return RematchOutcome.Failed(IndoorProblem(IndoorPart.CORE, false, "Spur taugt nicht fuer den Abgleich"))
        }
        val planned: PlannedRoute = try {
            router.route(waypoints)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return RematchOutcome.Failed(problemOf(IndoorPart.APP, e))
        }
        val sections = planned.waySections
        if (sections.isNullOrEmpty()) {
            return RematchOutcome.Failed(IndoorProblem(IndoorPart.APP, false, "Routing-Antwort ohne Wegabschnitte"))
        }
        // Linie der gefahrenen Spur, Belag aus dem Abgleich — buildIndoorRoute
        // streckt die Abschnitte anteilig auf die Laenge der Spur.
        val route = indoorCatching(IndoorPart.CORE) {
            parts.buildRoute(name, points, sections, IndoorRouteSource.BROUTER_REMATCH)
        }.getOrElse { return RematchOutcome.Failed(problemOf(IndoorPart.CORE, it)) }
            ?: return RematchOutcome.Failed(IndoorProblem(IndoorPart.CORE, false, "Abgleich ergab nichts Fahrbares"))
        return RematchOutcome.Done(route)
    }
}

/**
 * Der Router fuer den Belagsabgleich — in der App [OfflineFirstRematchRouter],
 * im Test eine Attrappe. Wirft bei jedem Fehlschlag.
 */
fun interface IndoorRematchRouter {
    suspend fun route(waypoints: List<Waypoint>): PlannedRoute
}

/**
 * Der Abgleich ueber den vorhandenen Weg der App ([planRouteOfflineFirst]:
 * lokal, wenn die Kacheln da sind, sonst der eingestellte Server).
 *
 * Profil `KUERZESTER` (BRouter `shortest`): Bei eng gesetzten Stuetzpunkten
 * folgt der kuerzeste Weg der Spur am treuesten — die anderen Profile
 * meiden schlechte Wege und wuerden genau dort abkuerzen, wo der Belag
 * interessant ist. Annahme, siehe docs/indoor/app.md.
 */
class OfflineFirstRematchRouter(context: Context) : IndoorRematchRouter {
    private val appContext = context.applicationContext

    override suspend fun route(waypoints: List<Waypoint>): PlannedRoute =
        planRouteOfflineFirst(appContext, waypoints, RouteProfile.KUERZESTER).route
}

/** Die Strecke der Testwelt bzw. jeder fertigen Welt: das Streckenband des Manifests. */
fun worldRouteOf(manifest: WorldManifest, name: String): IndoorRoute =
    manifest.track.toIndoorRoute(manifest.origin, manifest.routeId, name)

/** Stuetzpunkte des Hoehenprofils im HUD. */
const val INDOOR_PROFILE_POINTS: Int = 300

/**
 * Das Hoehenprofil der Strecke fuer das HUD: hoechstens [maxPoints]
 * gleichabstaendige Stuetzpunkte ueber die Laufmeter, linear interpoliert.
 */
fun indoorProfileOf(route: IndoorRoute, maxPoints: Int = INDOOR_PROFILE_POINTS): IndoorProfileUi {
    val pts = route.points
    val length = route.lengthM
    val n = minOf(maxOf(maxPoints, 2), maxOf(pts.size, 2))
    val dist = FloatArray(n)
    val ele = FloatArray(n)
    var j = 0
    for (i in 0 until n) {
        val d = if (n == 1) 0.0 else length * i / (n - 1)
        while (j < pts.size - 2 && pts[j + 1].distM < d) j++
        val a = pts[j]
        val b = pts[minOf(j + 1, pts.size - 1)]
        val span = b.distM - a.distM
        val t = if (span > 0) ((d - a.distM) / span).coerceIn(0.0, 1.0) else 0.0
        dist[i] = d.toFloat()
        ele[i] = (a.eleM + (b.eleM - a.eleM) * t).toFloat()
    }
    return IndoorProfileUi(lengthM = length, distM = dist, eleM = ele, isLoop = route.isLoop)
}
