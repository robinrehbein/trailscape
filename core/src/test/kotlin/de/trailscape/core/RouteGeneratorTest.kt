package de.trailscape.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests fuer `RouteGenerator.kt`.
 *
 * [FakeBrouter] erzeugt die BRouter-Antworten synthetisch im Format, das
 * [parseBrouterGeoJson] liest: Es liest die `lonlats` aus der Anfrage-URL,
 * misst den Umfang des Wegpunkt-Polygons mit [haversineM] und meldet
 * `track-length = Umfang × Umwegfaktor`. Damit ist die Radius-Iteration
 * exakt vorhersagbar — mit dem Standard-Umwegfaktor 1,3 trifft schon der
 * erste Versuch die 10-%-Toleranz, mit groesseren Faktoren braucht es
 * nachweisbar mehrere Runden.
 */
class RouteGeneratorTest {
    private companion object {
        const val EPS = 1e-9

        /** Startpunkt irgendwo im Muenchner Umland. */
        val START = TrackPoint(lat = 48.1372, lon = 11.5756)

        fun target(
            distanceKm: Double,
            ascent: AscentPreference = AscentPreference.FLACH,
        ): RouteTarget = RouteTarget(
            distanceKm = distanceKm,
            ascentPreference = ascent,
            durationH = null,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Testeinheit",
            source = RouteTargetSource.PLAN,
        )

        /** Anfangskurs von [from] nach [to] in Grad (0 = Nord), eben genaehert. */
        fun bearingTo(from: TrackPoint, to: TrackPoint): Double {
            val north = to.lat - from.lat
            val east = (to.lon - from.lon) * cos(from.lat * Math.PI / 180)
            return (Math.toDegrees(atan2(east, north)) + 360) % 360
        }

        /** Schwerpunkt der Via-Punkte (ohne den doppelten Start am Anfang und Ende). */
        fun viaCentroid(points: List<TrackPoint>): TrackPoint {
            val vias = points.subList(1, points.size - 1)
            return TrackPoint(
                lat = vias.sumOf { it.lat } / vias.size,
                lon = vias.sumOf { it.lon } / vias.size,
            )
        }
    }

    /**
     * Synthetischer BRouter-Server.
     *
     * @param detourFactor Verhaeltnis der gemeldeten Streckenlaenge zum Umfang
     *   des Wegpunkt-Polygons.
     * @param fixedDistanceKm Wenn gesetzt, meldet der Server immer diese Laenge
     *   — der Radius hat dann keinen Einfluss (Test der Versuchsobergrenze).
     * @param ascentPerKm Hoehenmeter je Kilometer, abhaengig von den Wegpunkten.
     * @param failAt Indizes von Routing-Anfragen, die mit HTTP 500 antworten.
     * @param throwAt Indizes von Routing-Anfragen, die als Netzwerkfehler werfen.
     */
    private class FakeBrouter(
        val detourFactor: Double = 1.30,
        val fixedDistanceKm: Double? = null,
        val ascentPerKm: (List<TrackPoint>) -> Double = { 5.0 },
        val failAt: Set<Int> = emptySet(),
        val throwAt: Set<Int> = emptySet(),
    ) : HttpClient {
        var routeRequests = 0
        var profileUploads = 0
        val waypointSets = mutableListOf<List<TrackPoint>>()
        val reportedKm = mutableListOf<Double>()

        override fun execute(request: HttpRequest): HttpResponse {
            if (request.method == HttpMethod.POST) {
                profileUploads += 1
                return HttpResponse(200, """{"profileid":"fake-gravel"}""")
            }

            val index = routeRequests
            routeRequests += 1

            val raw = request.url.substringAfter("lonlats=").substringBefore("&")
            val points = raw.split("|").map { pair ->
                val parts = pair.split(",")
                TrackPoint(lat = parts[1].toDouble(), lon = parts[0].toDouble())
            }
            waypointSets.add(points)

            if (index in throwAt) {
                throw RuntimeException("Netzwerk weg")
            }
            if (index in failAt) {
                return HttpResponse(500, "kaputt")
            }

            var perimeterM = 0.0
            for (i in 1 until points.size) {
                perimeterM += haversineM(points[i - 1], points[i])
            }
            val distanceM = fixedDistanceKm?.let { it * 1000 } ?: (perimeterM * detourFactor)
            reportedKm.add(distanceM / 1000)
            val ascentM = (distanceM / 1000) * ascentPerKm(points)

            return HttpResponse(200, geoJson(points, distanceM, ascentM))
        }

        private fun geoJson(points: List<TrackPoint>, distanceM: Double, ascentM: Double): String {
            val coords = points.joinToString(",") { "[${it.lon},${it.lat},500.0]" }
            return """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "track-length": "${distanceM.toInt()}",
                    "filtered ascend": "${ascentM.toInt()}"
                  },
                  "geometry": { "type": "LineString", "coordinates": [$coords] }
                }
              ]
            }
            """
        }
    }

    /** Zaehlt die Wartepausen, ohne real zu warten. */
    private class FakeSleeper : (Long) -> Unit {
        var calls = 0
        var totalMs = 0L

        override fun invoke(ms: Long) {
            calls += 1
            totalMs += ms
        }
    }

    @BeforeTest
    fun resetProfileCache() = resetCustomProfileCacheForTesting()

    @AfterTest
    fun clearProfileCache() = resetCustomProfileCacheForTesting()

    private fun generate(
        client: HttpClient,
        target: RouteTarget,
        seed: Int = 0,
        candidates: Int = 3,
        sleeper: (Long) -> Unit = {},
        onProgress: ((Int, Int) -> Unit)? = null,
    ): List<RouteCandidate> = generateRoutes(
        client = client,
        start = START,
        target = target,
        seed = seed,
        candidates = candidates,
        profileId = "trekking",
        pauseMs = 0,
        sleeper = sleeper,
        onProgress = onProgress,
    )

    // --- Geometrie ---

    @Test
    fun `Rundkurs-Wegpunkte bilden einen geschlossenen Kreis durch den Start`() {
        val radiusM = 5000.0
        val wps = loopWaypoints(START, radiusM, bearingDeg = 90.0, viaCount = 4, clockwise = true)

        assertEquals(6, wps.size)
        assertEquals(wps.first(), wps.last())

        // Der Mittelpunkt liegt in Radiusentfernung oestlich des Starts …
        val center = destinationPoint(START, 90.0, radiusM)
        assertEquals(radiusM, haversineM(START, TrackPoint(center.lat, center.lon)), 1.0)

        // … und alle Via-Punkte liegen auf dem Kreis um diesen Mittelpunkt.
        for (wp in wps.subList(1, wps.size - 1)) {
            assertEquals(radiusM, haversineM(center, TrackPoint(wp.lat, wp.lon)), 1.0)
        }
    }

    // --- Konvergenz ---

    @Test
    fun `trifft die Zieldistanz innerhalb der Toleranz`() {
        val client = FakeBrouter()

        val results = generate(client, target(40.0))

        assertEquals(3, results.size)
        for (r in results) {
            assertTrue(
                r.distanceDeviation <= routeToleranceRatio,
                "Abweichung ${r.distanceDeviation} bei ${r.distanceKm} km",
            )
        }
        // Mit Umwegfaktor 1,3 passt der Startradius bereits: ein Request je Kandidat.
        assertEquals(3, client.routeRequests)
    }

    @Test
    fun `Radius wird proportional nachgefuehrt, bis die Distanz passt`() {
        // Umwegfaktor 2,0 -> erster Versuch rund 44 % zu lang.
        val client = FakeBrouter(detourFactor = 2.0)

        val results = generate(client, target(60.0), candidates = 1)

        assertEquals(1, results.size)
        assertEquals(2, client.routeRequests)
        assertTrue(client.reportedKm[0] > 60.0 * 1.10, "erster Versuch war ${client.reportedKm[0]} km")
        assertTrue(results[0].distanceDeviation <= routeToleranceRatio)
        assertEquals(60.0, results[0].distanceKm, 60.0 * routeToleranceRatio)
    }

    @Test
    fun `hoechstens drei Versuche je Kandidat, bester Versuch gewinnt`() {
        // Die gemeldete Laenge haengt nicht vom Radius ab -> nie konvergent.
        val client = FakeBrouter(fixedDistanceKm = 100.0)

        val results = generate(client, target(40.0), candidates = 1)

        assertEquals(maxRadiusAttempts, client.routeRequests)
        assertEquals(1, results.size)
        assertEquals(100.0, results[0].distanceKm, EPS)
        assertEquals(40.0, results[0].targetKm, EPS)
    }

    // --- Bewertung ---

    @Test
    fun `Hoehen-Strafpunkte folgen der Praeferenz`() {
        // FLACH: bis 8 m/km straffrei, darueber 2 Punkte je m/km.
        assertEquals(0.0, ascentScore(4.0, AscentPreference.FLACH), EPS)
        assertEquals(0.0, ascentScore(8.0, AscentPreference.FLACH), EPS)
        assertEquals(24.0, ascentScore(20.0, AscentPreference.FLACH), EPS)

        // MODERAT: Band 8…16 m/km straffrei, Abstand kostet 1,5 Punkte.
        assertEquals(0.0, ascentScore(12.0, AscentPreference.MODERAT), EPS)
        assertEquals(6.0, ascentScore(4.0, AscentPreference.MODERAT), EPS)
        assertEquals(6.0, ascentScore(20.0, AscentPreference.MODERAT), EPS)

        // BERGIG: Bonus bis 15 m/km, ab 25 m/km wieder Strafe.
        assertEquals(-10.0, ascentScore(10.0, AscentPreference.BERGIG), EPS)
        assertEquals(-15.0, ascentScore(15.0, AscentPreference.BERGIG), EPS)
        assertEquals(-15.0, ascentScore(20.0, AscentPreference.BERGIG), EPS)
        assertEquals(-10.0, ascentScore(30.0, AscentPreference.BERGIG), EPS)
    }

    @Test
    fun `Distanzabweichung dominiert die Hoehenmeter`() {
        val nah = scoreRoute(distanceKm = 41.0, ascentM = 800.0, targetKm = 40.0, preference = AscentPreference.FLACH)
        val fern = scoreRoute(distanceKm = 55.0, ascentM = 0.0, targetKm = 40.0, preference = AscentPreference.FLACH)

        assertTrue(nah < fern, "nah=$nah fern=$fern")
    }

    /** Hoehenmeter je Kilometer nach Startkurs: Nord flach, Ost bergig, Suedwest mittel. */
    private fun ascentByBearing(points: List<TrackPoint>): Double {
        val bearing = bearingTo(points.first(), viaCentroid(points))
        return when {
            bearing < 30 || bearing > 330 -> 2.0
            abs(bearing - 120) < 30 -> 18.0
            else -> 9.0
        }
    }

    @Test
    fun `FLACH stellt die flache Runde nach vorn`() {
        val client = FakeBrouter(ascentPerKm = ::ascentByBearing)

        val results = generate(client, target(40.0, AscentPreference.FLACH))

        assertEquals(3, results.size)
        assertEquals(0.0, results.first().bearingDeg, EPS)
        assertTrue(results.first().ascentPerKm < 3.0)
        // Aufsteigend sortiert: kleiner Score = besser.
        assertTrue(results[0].score <= results[1].score)
        assertTrue(results[1].score <= results[2].score)
    }

    @Test
    fun `BERGIG stellt die hoehenmeterreiche Runde nach vorn`() {
        val client = FakeBrouter(ascentPerKm = ::ascentByBearing)

        val results = generate(client, target(40.0, AscentPreference.BERGIG))

        assertEquals(3, results.size)
        assertEquals(120.0, results.first().bearingDeg, EPS)
        assertTrue(results.first().ascentPerKm > 15.0)
        // Dieselbe Runde ist unter FLACH die schlechteste.
        val flach = generate(FakeBrouter(ascentPerKm = ::ascentByBearing), target(40.0, AscentPreference.FLACH))
        assertEquals(120.0, flach.last().bearingDeg, EPS)
    }

    // --- Fehlertoleranz ---

    @Test
    fun `einzelner Serverfehler kostet nur diesen Kandidaten`() {
        // Kandidat 2 (Request-Index 1) antwortet mit HTTP 500.
        val client = FakeBrouter(failAt = setOf(1))

        val results = generate(client, target(40.0))

        assertEquals(2, results.size)
        assertTrue(results.none { it.bearingDeg == 120.0 })
    }

    @Test
    fun `einzelner Netzwerkfehler kostet nur diesen Kandidaten`() {
        val client = FakeBrouter(throwAt = setOf(0))

        val results = generate(client, target(40.0))

        assertEquals(2, results.size)
        assertTrue(results.none { it.bearingDeg == 0.0 })
    }

    @Test
    fun `scheitern alle Kandidaten, wirft es auf Deutsch`() {
        val client = FakeBrouter(failAt = setOf(0, 1, 2))

        val error = assertFailsWith<Exception> { generate(client, target(40.0)) }

        assertEquals(errorNoRouteFound, error.message)
        assertTrue(error.message!!.contains("Runde"))
    }

    // --- Reproduzierbarkeit ---

    @Test
    fun `Seed 0 verteilt die Kandidaten auf 0, 120 und 240 Grad`() {
        val results = generate(FakeBrouter(), target(40.0))

        assertEquals(listOf(0.0, 120.0, 240.0), results.map { it.bearingDeg }.sorted())
    }

    @Test
    fun `gleicher Seed liefert exakt dieselben Runden`() {
        val a = generate(FakeBrouter(), target(40.0), seed = 7)
        val b = generate(FakeBrouter(), target(40.0), seed = 7)

        assertEquals(a.map { it.bearingDeg }, b.map { it.bearingDeg })
        assertEquals(a.map { it.distanceKm }, b.map { it.distanceKm })
        assertEquals(a.first().route.points, b.first().route.points)
    }

    @Test
    fun `Seed plus eins wuerfelt andere Runden`() {
        val a = generate(FakeBrouter(), target(40.0), seed = 7)
        val b = generate(FakeBrouter(), target(40.0), seed = 8)

        assertTrue(a.map { it.bearingDeg }.toSet() != b.map { it.bearingDeg }.toSet())
        assertTrue(a.first().route.points != b.first().route.points)
    }

    // --- Grenzen ---

    @Test
    fun `zu kurze Zieldistanz wird auf 5 km angehoben`() {
        val client = FakeBrouter()

        val results = generate(client, target(1.5), candidates = 1)

        assertEquals(minRouteTargetKm, results[0].targetKm, EPS)
        assertEquals(1, results[0].hints.size)
        assertTrue(results[0].hints[0].contains("5 km angehoben"))
        assertEquals(5.0, results[0].distanceKm, 5.0 * routeToleranceRatio)
    }

    @Test
    fun `zu lange Zieldistanz wird auf 200 km gedeckelt`() {
        val client = FakeBrouter()

        val results = generate(client, target(500.0), candidates = 1)

        assertEquals(maxRouteTargetKm, results[0].targetKm, EPS)
        assertTrue(results[0].hints.single().contains("200 km gedeckelt"))
        assertEquals(200.0, results[0].distanceKm, 200.0 * routeToleranceRatio)
    }

    @Test
    fun `im Normalfall gibt es keine Hinweise`() {
        val results = generate(FakeBrouter(), target(40.0), candidates = 1)

        assertTrue(results[0].hints.isEmpty())
    }

    @Test
    fun `Kandidatenzahl wird auf 1 bis 8 begrenzt`() {
        assertEquals(8, generate(FakeBrouter(), target(40.0), candidates = 20).size)
        assertEquals(1, generate(FakeBrouter(), target(40.0), candidates = 0).size)
    }

    // --- Betrieb ---

    @Test
    fun `onProgress meldet Start und jeden Kandidaten`() {
        val progress = mutableListOf<Pair<Int, Int>>()

        generate(FakeBrouter(), target(40.0), onProgress = { done, total -> progress.add(done to total) })

        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun `zwischen zwei Server-Anfragen wird gewartet, davor nicht`() {
        val sleeper = FakeSleeper()
        // Umwegfaktor 2,0 -> zwei Requests je Kandidat, zwei Kandidaten = 4 Requests.
        val client = FakeBrouter(detourFactor = 2.0)

        generate(client, target(40.0), candidates = 2, sleeper = sleeper)

        assertEquals(4, client.routeRequests)
        assertEquals(client.routeRequests - 1, sleeper.calls)
    }

    @Test
    fun `mit dem Gravel-Profil wird das Custom-Profil einmal hochgeladen`() {
        val client = FakeBrouter()

        val results = generateRoutes(
            client = client,
            start = START,
            target = target(40.0),
            candidates = 3,
            pauseMs = 0,
            sleeper = {},
        )

        assertEquals(3, results.size)
        assertEquals(1, client.profileUploads)
        assertEquals(3, client.routeRequests)
    }

    @Test
    fun `die Route traegt die Punkte der Server-Antwort`() {
        val client = FakeBrouter()

        val results = generate(client, target(40.0), candidates = 1)
        val route = results[0].route

        // 3 oder 4 Via-Punkte plus Start am Anfang und am Ende.
        assertTrue(route.points.size in 5..6, "Punkte: ${route.points.size}")
        assertEquals(START.lat, route.points.first().lat, 1e-6)
        assertEquals(START.lon, route.points.first().lon, 1e-6)
        assertEquals(route.points.first().lat, route.points.last().lat, 1e-6)
        assertEquals(route.distanceKm, results[0].distanceKm, EPS)
        assertEquals(route.ascentM, results[0].ascentM, EPS)
    }
}
