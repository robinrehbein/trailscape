package de.trailscape.core.indoor

import de.trailscape.core.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Strecken fuer die Tests, direkt per Konstruktor gebaut (ohne
 * `buildIndoorRoute`, das ein anderer Slice-Teil umsetzt). Sie halten die
 * Zusicherungen aus `IndoorRoute.kt` ein: Abstand 10 m, Hoehen endlich,
 * Belaege lueckenlos.
 */
internal object IndoorTestRoutes {
    const val LAT0 = 50.0
    const val LON0 = 7.0
    private const val EARTH_RADIUS_M = 6371000.0
    val M_PER_DEG_LAT = Math.toRadians(1.0) * EARTH_RADIUS_M

    /** Lage [d] Meter vom Startpunkt in Richtung [headingDeg] (eben, fuer kurze Strecken genau genug). */
    fun offset(d: Double, headingDeg: Double, lat0: Double = LAT0, lon0: Double = LON0): Pair<Double, Double> {
        val h = Math.toRadians(headingDeg)
        val lat = lat0 + d * cos(h) / M_PER_DEG_LAT
        val lon = lon0 + d * sin(h) / (M_PER_DEG_LAT * cos(Math.toRadians(lat0)))
        return lat to lon
    }

    /**
     * Eine gerade Strecke in Richtung [headingDeg] mit Hoehenprofil [ele]
     * (Laufmeter → Hoehe) und Belaegen [surfaces] (ohne: ein Abschnitt UNKNOWN).
     */
    fun straight(
        lengthM: Double = 2000.0,
        stepM: Double = 10.0,
        headingDeg: Double = 0.0,
        surfaces: List<SurfaceSection>? = null,
        isLoop: Boolean = false,
        ele: (Double) -> Double = { 0.0 },
    ): IndoorRoute {
        val n = Math.round(lengthM / stepM).toInt()
        val pts = List(n + 1) { i ->
            val d = if (i == n) lengthM else i * stepM
            val (lat, lon) = offset(d, headingDeg)
            IndoorRoutePoint(d, lat, lon, ele(d))
        }
        return IndoorRoute(
            id = "rtest",
            name = "Teststrecke",
            points = pts,
            surfaces = surfaces ?: listOf(SurfaceSection(0.0, lengthM, SurfaceKind.UNKNOWN)),
            isLoop = isLoop,
            source = if (surfaces == null) IndoorRouteSource.NONE else IndoorRouteSource.BROUTER_WAY_TAGS,
        )
    }

    /** Flach bis [startM], dann [gradePct] bis [endM], danach wieder flach. */
    fun climbProfile(startM: Double, endM: Double, gradePct: Double): (Double) -> Double = { d ->
        (min(max(d, startM), endM) - startM) * gradePct / 100.0
    }

    /** Eine Runde als Kreis mit Umfang [lengthM] (Start = Ziel), Fahrtrichtung im Uhrzeigersinn ab Nord. */
    fun loop(lengthM: Double = 1000.0, stepM: Double = 10.0, surfaces: List<SurfaceSection>? = null): IndoorRoute {
        val n = Math.round(lengthM / stepM).toInt()
        val r = lengthM / (2 * Math.PI)
        // Mittelpunkt oestlich vom Start: Start im Westen des Kreises, erste Richtung Nord.
        val (cLat, cLon) = offset(r, 90.0)
        val pts = List(n + 1) { i ->
            val d = if (i == n) lengthM else i * stepM
            val phi = d / r // Winkel ab West, im Uhrzeigersinn
            val bearingFromCenter = 270.0 + Math.toDegrees(phi)
            val (lat, lon) = offset(r, bearingFromCenter, cLat, cLon)
            IndoorRoutePoint(d, lat, lon, 100.0 + 5.0 * sin(phi))
        }
        return IndoorRoute(
            id = "rloop",
            name = "Testrunde",
            points = pts,
            surfaces = surfaces ?: listOf(SurfaceSection(0.0, lengthM, SurfaceKind.ASPHALT)),
            isLoop = true,
            source = IndoorRouteSource.BROUTER_WAY_TAGS,
        )
    }

    /** Punkte einer frueheren Fahrt entlang [route]: Laufmeter je Sekunde aus [distAt], Zeit ab [t0]. */
    fun pastRide(
        route: IndoorRoute,
        seconds: Int,
        t0: Long = 1_700_000_000_000L,
        lateralM: Double = 0.0,
        distAt: (Int) -> Double,
    ): List<TrackPoint> = List(seconds + 1) { s ->
        val sample = route.sampleAt(distAt(s))
        val (lat, lon) = if (lateralM != 0.0) {
            offset(lateralM, sample.headingDeg + 90.0, sample.lat, sample.lon)
        } else {
            sample.lat to sample.lon
        }
        TrackPoint(lat = lat, lon = lon, ele = sample.eleM, time = t0 + s * 1000L)
    }

    val RIDER = RiderSetup(
        riderMassKg = 75.0,
        bikeMassKg = 12.0,
        cdaM2 = 0.38,
        profileCrr = 0.008,
        ftpW = 250.0,
    )
}
