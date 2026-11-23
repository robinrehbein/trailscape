package de.trailscape.core.indoor

import de.trailscape.core.TrackPoint
import de.trailscape.core.haversineM
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Der Geist
 *
 * Ein Mitfahrer, gegen den die Sitzung den Abstand misst ([GhostSpec]).
 * Seine Uhr ist die **Fahrzeit des Fahrers** (nur Phase RUNNING), beide
 * starten am Laufmeter 0 — Pausen und Auto-Pausen halten also beide an.
 *
 *  * [ConstantPowerGhost]: dieselbe Physik wie der Fahrer (gleicher
 *    [RiderSetup], Wind, Strecke, Belag), nur mit konstanter Leistung. Er
 *    startet wie der Fahrer aus dem Stand (der Entwurf erwog einen Start im
 *    Gleichgewichtstempo; aus dem Stand ist der Vergleich fair, denn auch der
 *    Fahrer muss erst beschleunigen).
 *  * [PastRideGhost]: eine fruehere Fahrt als Zeit-Weg-Kurve auf den Laufmetern
 *    dieser Strecke ([pastRideCurve]).
 *
 * Auf einer Strecke ohne Rundschluss bleibt der Geist am Ziel stehen.
 */
internal interface GhostRunner {
    /** Laufmeter seit dem Start (ueber Runden fortlaufend). */
    val distM: Double
    val speedMps: Double

    /**
     * Ein Schritt der Fahrzeit: [dtS] Sekunden, danach steht die Fahrzeit des
     * Fahrers bei [runningMs].
     */
    fun advance(runningMs: Long, dtS: Double)
}

/** Ein gedachter Fahrer mit konstanter Leistung (siehe Dateikopf). */
internal class ConstantPowerGhost(
    private val route: IndoorRoute,
    private val rider: RiderSetup,
    private val wind: IndoorWind,
    watts: Double,
) : GhostRunner {
    private val watts = if (watts.isFinite()) max(watts, 0.0) else 0.0
    override var distM: Double = 0.0
        private set
    override var speedMps: Double = 0.0
        private set

    override fun advance(runningMs: Long, dtS: Double) {
        if (!route.isLoop && distM >= route.lengthM) {
            speedMps = 0.0
            return
        }
        val s = route.sampleAt(distM)
        val step = advancePhysics(
            speedMps = speedMps,
            powerW = watts,
            gradePct = s.gradePct,
            crr = effectiveCrr(s.surface.kind, rider.profileCrr),
            eleM = s.eleM,
            headwindMps = headwindMps(wind, s.headingDeg),
            rider = rider,
            dtS = dtS,
        )
        distM += step.distanceM
        speedMps = step.speedMps
        if (!route.isLoop && distM >= route.lengthM) {
            distM = route.lengthM
            speedMps = 0.0
        }
    }
}

/**
 * Eine Zeit-Weg-Kurve: [timesS] streng steigend ab 0, [distsM] monoton
 * steigend (Laufmeter der Strecke).
 */
internal class GhostCurve(val timesS: DoubleArray, val distsM: DoubleArray) {
    init {
        require(timesS.size >= 2 && timesS.size == distsM.size)
    }

    /** Laufmeter und Tempo zur Zeit [tS]; vor dem Anfang der erste, nach dem Ende der letzte Punkt (Tempo 0). */
    fun at(tS: Double): Pair<Double, Double> {
        if (tS <= timesS[0]) {
            return distsM[0] to 0.0
        }
        val last = timesS.size - 1
        if (tS >= timesS[last]) {
            return distsM[last] to 0.0
        }
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (timesS[mid] <= tS) lo = mid else hi = mid
        }
        val span = timesS[hi] - timesS[lo]
        val speed = (distsM[hi] - distsM[lo]) / span
        return distsM[lo] + speed * (tS - timesS[lo]) to speed
    }
}

/** Eine fruehere Fahrt (siehe [pastRideCurve]). */
internal class PastRideGhost(private val curve: GhostCurve) : GhostRunner {
    override var distM: Double = curve.distsM[0]
        private set
    override var speedMps: Double = 0.0
        private set

    override fun advance(runningMs: Long, dtS: Double) {
        val (d, v) = curve.at(runningMs / 1000.0)
        distM = d
        speedMps = v
    }
}

/** Der Geist zu [spec], `null` ohne Geist oder wenn die fruehere Fahrt unbrauchbar ist. */
internal fun ghostRunner(spec: GhostSpec, route: IndoorRoute, rider: RiderSetup, wind: IndoorWind): GhostRunner? =
    when (spec) {
        GhostSpec.None -> null
        is GhostSpec.ConstantPower -> ConstantPowerGhost(route, rider, wind, spec.watts)
        is GhostSpec.PastRide -> pastRideCurve(route, spec.points)?.let { PastRideGhost(it) }
    }

/**
 * Stand des Geists relativ zum Fahrer. [GhostState.gapS] ist der Abstand
 * geteilt durch das Tempo des **Hinteren** (der braucht so lange bis zur Stelle
 * des Vorderen); `null`, wenn der langsamer als [GHOST_MIN_GAP_SPEED_MPS] ist.
 */
internal fun ghostState(ghost: GhostRunner, riderDistM: Double, riderSpeedMps: Double): GhostState {
    val gapM = ghost.distM - riderDistM
    val behindSpeed = if (gapM >= 0) riderSpeedMps else ghost.speedMps
    val gapS = if (behindSpeed >= GHOST_MIN_GAP_SPEED_MPS) gapM / behindSpeed else null
    return GhostState(distM = ghost.distM, speedMps = ghost.speedMps, gapM = gapM, gapS = gapS)
}

internal const val GHOST_MIN_GAP_SPEED_MPS: Double = 0.5

/** Suchfenster der Projektion ab der letzten Stelle, in Laufmetern. */
internal const val GHOST_SEARCH_WINDOW_M: Double = 500.0

/** Groesster Abstand eines Punkts der frueheren Fahrt zur Strecke, damit er zaehlt. */
internal const val GHOST_MAX_OFFSET_M: Double = 50.0

/** Anteil der Punkte, die auf die Strecke passen muessen, sonst Rueckfall auf die gestreckte Distanz. */
internal const val GHOST_MIN_MATCH_SHARE: Double = 0.8

/** Zeitluecken ueber dieser Dauer ohne Fortschritt gelten als gespeicherte Pause. */
internal const val GHOST_PAUSE_GAP_S: Double = 30.0

/** „Ohne Fortschritt": unter diesem mittleren Tempo ueber die Luecke. */
private const val GHOST_PAUSE_MAX_SPEED_MPS: Double = 1.0

/** Auf diese Laenge schrumpft eine entfernte Pause (die Zeit bleibt streng steigend). */
private const val GHOST_PAUSE_KEEP_S: Double = 1.0

private const val EARTH_RADIUS_M = 6371000.0

/** Strafmeter je Laufmeter Vorgriff bei der Projektion (500 m voraus zaehlen wie 5 m Abstand). */
private const val GHOST_AHEAD_PENALTY = 0.01

/**
 * Bildet die Punkte einer frueheren Fahrt auf die Laufmeter von [route] ab.
 *
 *  1. Nur Punkte mit Zeitstempel und endlicher Lage, Zeit streng steigend
 *     (andere entfallen); unter zwei solchen Punkten → `null`.
 *  2. Jeder Punkt wird auf die Strecke projiziert, **monoton vorwaerts**: gesucht
 *     wird nur von der letzten Stelle bis [GHOST_SEARCH_WINDOW_M] dahinter (auf
 *     einer Runde ueber das Ziel hinaus), und ein Punkt weiter als
 *     [GHOST_MAX_OFFSET_M] von der Strecke wird uebersprungen.
 *  3. Passen weniger als [GHOST_MIN_MATCH_SHARE] der Punkte, ist die Fahrt wohl
 *     eine andere Linie: Rueckfall auf die kumulierte Haversine-Distanz der
 *     Punkte, anteilig auf die Streckenlaenge gestreckt.
 *  4. Zeitluecken ueber [GHOST_PAUSE_GAP_S] ohne Fortschritt (gespeicherte
 *     Pausen) werden auf 1 s verkuerzt; die Zeit beginnt bei 0.
 */
internal fun pastRideCurve(route: IndoorRoute, points: List<TrackPoint>): GhostCurve? {
    val timed = ArrayList<TrackPoint>(points.size)
    for (p in points) {
        val t = p.time ?: continue
        if (!p.lat.isFinite() || !p.lon.isFinite()) continue
        if (timed.isNotEmpty() && t <= timed.last().time!!) continue
        timed += p
    }
    if (timed.size < 2) {
        return null
    }

    val projTimes = ArrayList<Long>(timed.size)
    val projDists = ArrayList<Double>(timed.size)
    var last = 0.0
    for (p in timed) {
        val d = projectForward(route, p.lat, p.lon, last) ?: continue
        last = d
        projTimes += p.time!!
        projDists += d
    }

    val times: List<Long>
    val dists: List<Double>
    if (projTimes.size >= 2 && projTimes.size >= GHOST_MIN_MATCH_SHARE * timed.size) {
        times = projTimes
        dists = projDists
    } else {
        val cum = DoubleArray(timed.size)
        for (i in 1 until timed.size) {
            cum[i] = cum[i - 1] + haversineM(timed[i - 1], timed[i])
        }
        val total = cum.last()
        if (!(total > 0)) {
            return null
        }
        times = timed.map { it.time!! }
        dists = cum.map { it * route.lengthM / total }
    }

    val outT = DoubleArray(times.size)
    var offsetS = 0.0
    for (i in 1 until times.size) {
        val gapS = (times[i] - times[i - 1]) / 1000.0
        val progress = dists[i] - dists[i - 1]
        if (gapS > GHOST_PAUSE_GAP_S && progress < GHOST_PAUSE_MAX_SPEED_MPS * gapS) {
            offsetS += gapS - GHOST_PAUSE_KEEP_S
        }
        outT[i] = (times[i] - times[0]) / 1000.0 - offsetS
    }
    return GhostCurve(outT, dists.toDoubleArray())
}

/**
 * Die naechste Stelle der Strecke zu (lat, lon) im Fenster
 * `from .. from + GHOST_SEARCH_WINDOW_M` (Laufmeter, auf einer Runde
 * fortlaufend), nie kleiner als [from]; `null`, wenn der Punkt weiter als
 * [GHOST_MAX_OFFSET_M] von der Strecke liegt.
 */
private fun projectForward(route: IndoorRoute, lat: Double, lon: Double, from: Double): Double? {
    val pts = route.points
    val len = route.lengthM
    val to = if (route.isLoop) from + GHOST_SEARCH_WINDOW_M else min(from + GHOST_SEARCH_WINDOW_M, len)
    val mPerDegLat = Math.toRadians(1.0) * EARTH_RADIUS_M
    val mPerDegLon = mPerDegLat * cos(Math.toRadians(lat))
    var bestScore = Double.MAX_VALUE
    var bestOffset = Double.MAX_VALUE
    var bestDist = from
    val firstLap = if (route.isLoop && len > 0) floor(from / len).toInt() else 0
    val lastLap = if (route.isLoop && len > 0) floor(to / len).toInt() else 0
    for (lap in firstLap..lastLap) {
        val base = lap * len
        // Erstes Stueck, dessen Ende hinter `from` liegt (binaere Suche).
        var lo = 0
        var hi = pts.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (base + pts[mid + 1].distM < from) lo = mid + 1 else hi = mid
        }
        var j = lo
        while (j < pts.size - 1 && base + pts[j].distM <= to) {
            val a = pts[j]
            val b = pts[j + 1]
            val ax = (a.lon - lon) * mPerDegLon
            val ay = (a.lat - lat) * mPerDegLat
            val bx = (b.lon - lon) * mPerDegLon
            val by = (b.lat - lat) * mPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val offset = sqrt(px * px + py * py)
            val cand = base + a.distM + t * (b.distM - a.distM)
            // Bei gleich guter Lage gewinnt die naehere Stelle — sonst spraenge ein
            // Punkt am Start einer kurzen Runde gleich in die naechste.
            val score = offset + GHOST_AHEAD_PENALTY * max(0.0, cand - from)
            if (score < bestScore) {
                bestScore = score
                bestOffset = offset
                bestDist = cand
            }
            j++
        }
    }
    if (bestOffset > GHOST_MAX_OFFSET_M) {
        return null
    }
    return max(from, bestDist)
}
