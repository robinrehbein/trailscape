package de.trailscape.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Navigation entlang einer festen Route.
 *
 * 1:1-Portierung von `lib/navigation.dart`, das selbst eine getestete
 * 1:1-Portierung der Web-Referenz (`web/src/navigation.ts`) ist: Die Position
 * wird auf die Route projiziert und liefert Fortschritt sowie Abweichung
 * inklusive Hysterese fuer den Off-Route-Zustand.
 */

/** Meter pro Breitengrad (aequirektangulaere Naeherung). */
private const val M_PER_DEG_LAT = 111320.0

/** Halbe Fensterbreite in Segmenten fuer die lokale Suche. */
private const val SEARCH_WINDOW_SEGMENTS = 50

/** Ab diesem Fenster-Abstand wird einmalig global gesucht. */
private const val GLOBAL_SEARCH_THRESHOLD_M = 200.0

/** Abstand, ab dem die Position als "abseits" gilt. */
private const val OFF_ROUTE_ENTER_M = 60.0

/** Abstand, ab dem die Position wieder als "auf Route" gilt. */
private const val OFF_ROUTE_EXIT_M = 35.0

/** Wie lange der Abstand durchgehend zu gross sein muss. */
private const val OFF_ROUTE_DELAY_MS = 5000L

private fun toRad(deg: Double): Double = deg * Math.PI / 180

/** Ergebnis der Projektion auf ein Routensegment. */
private data class Projection(
    val segmentIndex: Int,
    /** Parameter auf dem Segment, 0 = Anfang, 1 = Ende. */
    val t: Double,
    val distanceM: Double,
)

/**
 * Navigation entlang einer festen Route: projiziert die aktuelle Position auf
 * die Route und liefert Fortschritt sowie Abweichung.
 *
 * Die Projektion rechnet lokal in einer aequirektangulaeren Naeherung (Meter
 * pro Grad), was fuer Abstaende von wenigen Kilometern ausreichend genau und
 * deutlich schneller als Haversine pro Segment ist. Die Distanzen entlang der
 * Route stammen dagegen aus der exakten Haversine-Vorberechnung.
 */
class RouteNavigator(private val route: List<TrackPoint>) {
    private val cumulativeM: DoubleArray = buildCumulative(route)
    private val totalM: Double = cumulativeM.last()

    /** Zuletzt getroffenes Segment, Startpunkt der gefensterten Suche. */
    private var lastSegmentIndex = 0
    private var offRouteState = false

    /** Zeitpunkt, seit dem der Abstand durchgehend zu gross ist. */
    private var farSinceMs: Long? = null

    /** Gesamtlaenge der Route in Kilometern. */
    val totalKm: Double get() = totalM / 1000

    /** Aktualisiert den Navigationszustand fuer die aktuelle Position. */
    fun update(lat: Double, lon: Double, now: Long? = null): NavState {
        val nowMs = now ?: System.currentTimeMillis()
        val mPerDegLon = M_PER_DEG_LAT * cos(toRad(lat))
        val lastSegment = route.size - 2

        val from = max(0, lastSegmentIndex - SEARCH_WINDOW_SEGMENTS)
        val to = min(lastSegment, lastSegmentIndex + SEARCH_WINDOW_SEGMENTS)

        var best = searchRange(lat, lon, mPerDegLon, from, to)

        // Der Nutzer koennte die Route weit verlassen haben oder gesprungen sein:
        // dann lohnt sich eine einmalige globale Suche.
        if (best.distanceM > GLOBAL_SEARCH_THRESHOLD_M && (from > 0 || to < lastSegment)) {
            val global = searchRange(lat, lon, mPerDegLon, 0, lastSegment)
            if (global.distanceM < best.distanceM) {
                best = global
            }
        }

        lastSegmentIndex = best.segmentIndex

        val segmentStartM = cumulativeM[best.segmentIndex]
        val segmentLengthM = cumulativeM[best.segmentIndex + 1] - segmentStartM
        val doneM = min(totalM, segmentStartM + best.t * segmentLengthM)
        val remainingM = max(0.0, totalM - doneM)

        return NavState(
            nearestIndex = if (best.t <= 0.5) best.segmentIndex else best.segmentIndex + 1,
            distanceToRouteM = best.distanceM,
            doneKm = doneM / 1000,
            remainingKm = remainingM / 1000,
            offRoute = updateOffRoute(best.distanceM, nowMs),
        )
    }

    /** Bestes Segment im Indexbereich [from, to] (jeweils einschliesslich). */
    private fun searchRange(
        lat: Double,
        lon: Double,
        mPerDegLon: Double,
        from: Int,
        to: Int,
    ): Projection {
        var bestIndex = from
        var bestT = 0.0
        var bestDistanceM = Double.POSITIVE_INFINITY

        for (i in from..to) {
            val a = route[i]
            val b = route[i + 1]

            // Lokales Meter-Koordinatensystem mit der Position im Ursprung.
            val ax = (a.lon - lon) * mPerDegLon
            val ay = (a.lat - lat) * M_PER_DEG_LAT
            val bx = (b.lon - lon) * mPerDegLon
            val by = (b.lat - lat) * M_PER_DEG_LAT

            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy

            var t = 0.0
            if (lengthSq > 0) {
                t = (-ax * dx - ay * dy) / lengthSq
                t = if (t < 0) 0.0 else if (t > 1) 1.0 else t
            }

            val px = ax + t * dx
            val py = ay + t * dy
            val distanceM = sqrt(px * px + py * py)

            if (distanceM < bestDistanceM) {
                bestDistanceM = distanceM
                bestIndex = i
                bestT = t
            }
        }

        return Projection(segmentIndex = bestIndex, t = bestT, distanceM = bestDistanceM)
    }

    /**
     * Hysterese: abseits erst, wenn der Abstand seit mindestens 5 Sekunden
     * durchgehend ueber 60 m liegt; zurueck auf der Route, sobald er einmal
     * unter 35 m faellt. Dazwischen bleibt der Zustand unveraendert.
     */
    private fun updateOffRoute(distanceM: Double, now: Long): Boolean {
        if (distanceM < OFF_ROUTE_EXIT_M) {
            farSinceMs = null
            offRouteState = false
            return offRouteState
        }

        if (distanceM > OFF_ROUTE_ENTER_M) {
            val farSince = farSinceMs
            if (farSince == null) {
                farSinceMs = now
            } else if (now - farSince >= OFF_ROUTE_DELAY_MS) {
                offRouteState = true
            }
            return offRouteState
        }

        // 35 m <= Abstand <= 60 m: Zustand halten, Zaehler zuruecksetzen.
        farSinceMs = null
        return offRouteState
    }

    private companion object {
        fun buildCumulative(route: List<TrackPoint>): DoubleArray {
            require(route.size >= 2) { "Route benoetigt mindestens 2 Punkte." }

            val cumulativeM = DoubleArray(route.size)
            for (i in 1 until route.size) {
                cumulativeM[i] = cumulativeM[i - 1] + haversineM(route[i - 1], route[i])
            }
            return cumulativeM
        }
    }
}
