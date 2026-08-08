package de.trailscape.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fahrt-Statistiken: Distanz, Dauer, Geschwindigkeit, Hoehenmeter.
 *
 * 1:1-Portierung von `lib/stats.dart` und damit semantisch identisch zur
 * Web-App-Referenz (stats.ts), damit Ride-Daten zwischen Selfhost-Sync-Server,
 * Web-App und dieser App konsistent bleiben. Gleiche Konstanten, gleicher
 * Algorithmus wie das Original.
 */

private const val EARTH_RADIUS_M = 6371000.0
private const val MOVING_SPEED_THRESHOLD_KMH = 1.0
private const val ELEVATION_HYSTERESIS_M = 3.0

private fun toRad(deg: Double): Double = deg * Math.PI / 180

/** Distanz zwischen zwei Punkten in Metern (Haversine-Formel). */
fun haversineM(a: TrackPoint, b: TrackPoint): Double {
    val dLat = toRad(b.lat - a.lat)
    val dLon = toRad(b.lon - a.lon)
    val lat1 = toRad(a.lat)
    val lat2 = toRad(b.lat)

    val sinDLat = sin(dLat / 2)
    val sinDLon = sin(dLon / 2)
    val h = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
    val c = 2 * atan2(sqrt(h), sqrt(1 - h))

    return EARTH_RADIUS_M * c
}

private val emptyStats = RideStats(
    distanceKm = 0.0,
    durationS = null,
    movingTimeS = null,
    avgSpeedKmh = null,
    ascentM = 0.0,
    descentM = 0.0,
)

private class Elevation(val ascentM: Double, val descentM: Double)

private fun computeElevation(points: List<TrackPoint>): Elevation {
    val withEle = points.filter { it.ele != null }

    if (withEle.size < 2) {
        return Elevation(0.0, 0.0)
    }

    var ascentM = 0.0
    var descentM = 0.0
    var referenceEle = withEle[0].ele!!

    for (i in 1 until withEle.size) {
        val diff = withEle[i].ele!! - referenceEle

        if (kotlin.math.abs(diff) >= ELEVATION_HYSTERESIS_M) {
            if (diff > 0) {
                ascentM += diff
            } else {
                descentM += -diff
            }
            referenceEle = withEle[i].ele!!
        }
    }

    return Elevation(ascentM, descentM)
}

/**
 * Berechnet Fahrt-Statistiken aus einer Liste von Trackpunkten.
 * Hoehenmeter werden mit einer Hysterese-Schwelle von 3 m geglaettet,
 * um GPS-Rauschen nicht als Anstieg/Abstieg zu zaehlen.
 */
fun computeStats(points: List<TrackPoint>): RideStats {
    if (points.size < 2) {
        return emptyStats
    }

    var distanceM = 0.0
    var movingTimeS = 0.0
    var hasMovingTimeData = false

    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val segmentM = haversineM(prev, curr)
        distanceM += segmentM

        if (prev.time != null && curr.time != null) {
            val dtS = (curr.time - prev.time) / 1000.0
            if (dtS > 0) {
                hasMovingTimeData = true
                val speedKmh = (segmentM / 1000 / dtS) * 3600
                if (speedKmh > MOVING_SPEED_THRESHOLD_KMH) {
                    movingTimeS += dtS
                }
            }
        }
    }

    val distanceKm = distanceM / 1000

    val firstTime = points.first().time
    val lastTime = points.last().time
    val durationS: Double? = if (firstTime != null && lastTime != null) {
        (lastTime - firstTime) / 1000.0
    } else {
        null
    }

    val resolvedMovingTimeS: Double? = if (hasMovingTimeData) movingTimeS else null

    var avgSpeedKmh: Double? = null
    if (resolvedMovingTimeS != null && resolvedMovingTimeS > 0) {
        avgSpeedKmh = distanceKm / (resolvedMovingTimeS / 3600)
    } else if (durationS != null && durationS > 0) {
        avgSpeedKmh = distanceKm / (durationS / 3600)
    }

    val elevation = computeElevation(points)

    return RideStats(
        distanceKm = distanceKm,
        durationS = durationS?.let { dartRound(it).toInt() },
        movingTimeS = resolvedMovingTimeS?.let { dartRound(it).toInt() },
        avgSpeedKmh = avgSpeedKmh,
        ascentM = elevation.ascentM,
        descentM = elevation.descentM,
    )
}

/** Formatiert Sekunden als "H:MM:SS" bzw. "M:SS", "–" bei null. */
fun formatDuration(s: Int?): String {
    if (s == null) {
        return "–"
    }

    val totalS = max(0, s)
    val hours = totalS / 3600
    val minutes = (totalS % 3600) / 60
    val seconds = totalS % 60

    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')

    if (hours > 0) {
        return "$hours:$mm:$ss"
    }

    return "$minutes:$ss"
}

/** Formatiert Kilometer mit einer Nachkommastelle, z. B. "42.3". */
fun formatKm(km: Double): String =
    java.math.BigDecimal.valueOf(km).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
