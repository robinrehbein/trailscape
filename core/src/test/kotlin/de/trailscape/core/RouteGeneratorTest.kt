package de.trailscape.core

import de.trailscape.core.i18n.CoreTextsDe
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests fuer `RouteGenerator.kt`.
 *
 * [FakeBackend] ist das [RoutingBackend] der Tests: Es misst den Umfang des
 * hereingereichten Wegpunkt-Polygons mit [haversineM] und meldet
 * `distanceKm = Umfang × Umwegfaktor`. Damit ist die Radius-Iteration exakt
 * vorhersagbar — mit dem Standard-Umwegfaktor 1,3 trifft schon der erste
 * Versuch die 10-%-Toleranz, mit groesseren Faktoren braucht es nachweisbar
 * mehrere Runden. HTTP kommt nicht mehr vor: Der Generator kennt seit dem
 * [RoutingBackend]-Umbau nur noch Wegpunkte, Profil und Strecke.
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

        /**
         * Fuehrt [block] synchron aus. `:core` haengt bewusst nicht an
         * kotlinx-coroutines (siehe `build.gradle.kts`), also gibt es hier
         * kein `runBlocking`; die Fakes suspendieren nie, deshalb liegt das
         * Ergebnis unmittelbar nach `startCoroutine` vor — andernfalls
         * schlaegt der `checkNotNull` laut fehl, statt still zu haengen.
         */
        fun <T> runSync(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(
                object : Continuation<T> {
                    override val context: CoroutineContext = EmptyCoroutineContext
                    override fun resumeWith(result: Result<T>) {
                        outcome = result
                    }
                },
            )
            return checkNotNull(outcome) {
                "Der Test-Fake hat suspendiert — diese Tests rechnen synchron."
            }.getOrThrow()
        }
    }

    /**
     * Synthetisches Routing-Backend.
     *
     * @param detourFactor Verhaeltnis der gemeldeten Streckenlaenge zum Umfang
     *   des Wegpunkt-Polygons.
     * @param fixedDistanceKm Wenn gesetzt, meldet das Backend immer diese
     *   Laenge — der Radius hat dann keinen Einfluss (Test der
     *   Versuchsobergrenze).
     * @param ascentPerKm Hoehenmeter je Kilometer, abhaengig von den Wegpunkten.
     * @param failAt Indizes von Routing-Aufrufen, die mit [failureMessage]
     *   werfen (Netzfehler, Serverfehler — fuer den Generator dasselbe).
     */
    private class FakeBackend(
        val detourFactor: Double = 1.30,
        val fixedDistanceKm: Double? = null,
        val ascentPerKm: (List<TrackPoint>) -> Double = { 5.0 },
        val failAt: Set<Int> = emptySet(),
        val failureMessage: String = "Netzwerk weg",
    ) : RoutingBackend {
        var calls = 0
        val profiles = mutableListOf<RouteProfile>()
        val waypointSets = mutableListOf<List<TrackPoint>>()
        val reportedKm = mutableListOf<Double>()

        override suspend fun route(waypoints: List<Waypoint>, profile: RouteProfile): PlannedRoute {
            val index = calls
            calls += 1
            profiles.add(profile)

            val points = waypoints.map { TrackPoint(lat = it.lat, lon = it.lon, ele = 500.0) }
            waypointSets.add(points)

            if (index in failAt) {
                throw RuntimeException(failureMessage)
            }

            var perimeterM = 0.0
            for (i in 1 until points.size) {
                perimeterM += haversineM(points[i - 1], points[i])
            }
            val distanceM = fixedDistanceKm?.let { it * 1000 } ?: (perimeterM * detourFactor)
            reportedKm.add(distanceM / 1000)
            val ascentM = (distanceM / 1000) * ascentPerKm(points)

            return PlannedRoute(points = points, distanceKm = distanceM / 1000, ascentM = ascentM)
        }
    }

    private fun generate(
        backend: RoutingBackend,
        target: RouteTarget,
        seed: Int = 0,
        candidates: Int = 3,
        profile: RouteProfile = RouteProfile.SCHOTTER,
        sleeper: suspend (Long) -> Unit = {},
        onProgress: ((Int, Int) -> Unit)? = null,
        exploredTiles: Set<ExplorerTile> = emptySet(),
        preferNewAreas: Boolean = false,
        wind: WindConditions? = null,
        followUpSearch: Boolean = true,
    ): List<RouteCandidate> = runSync {
        generateRoutes(
            backend = backend,
            start = START,
            target = target,
            profile = profile,
            seed = seed,
            candidates = candidates,
            pauseMs = 0,
            sleeper = sleeper,
            onProgress = onProgress,
            exploredTiles = exploredTiles,
            preferNewAreas = preferNewAreas,
            wind = wind,
            texts = CoreTextsDe,
            followUpSearch = followUpSearch,
        )
    }

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
        val backend = FakeBackend()

        val results = generate(backend, target(40.0))

        assertEquals(3, results.size)
        for (r in results) {
            assertTrue(
                r.distanceDeviation <= routeToleranceRatio,
                "Abweichung ${r.distanceDeviation} bei ${r.distanceKm} km",
            )
        }
        // Mit Umwegfaktor 1,3 passt der Startradius bereits: ein Aufruf je Kandidat.
        assertEquals(3, backend.calls)
    }

    @Test
    fun `Radius wird proportional nachgefuehrt, bis die Distanz passt`() {
        // Umwegfaktor 2,0 -> erster Versuch rund 44 % zu lang.
        val backend = FakeBackend(detourFactor = 2.0)

        val results = generate(backend, target(60.0), candidates = 1)

        assertEquals(1, results.size)
        assertEquals(2, backend.calls)
        assertTrue(backend.reportedKm[0] > 60.0 * 1.10, "erster Versuch war ${backend.reportedKm[0]} km")
        assertTrue(results[0].distanceDeviation <= routeToleranceRatio)
        assertEquals(60.0, results[0].distanceKm, 60.0 * routeToleranceRatio)
    }

    @Test
    fun `hoechstens drei Versuche je Kandidat, bester Versuch gewinnt`() {
        // Die gemeldete Laenge haengt nicht vom Radius ab -> nie konvergent.
        val backend = FakeBackend(fixedDistanceKm = 100.0)

        // Ohne Nachsuche (Schritt 9): Die Runde liegt 150 % daneben und loeste
        // sonst einen zweiten Durchgang aus — hier geht es um einen Kandidaten.
        val results = generate(backend, target(40.0), candidates = 1, followUpSearch = false)

        assertEquals(maxRadiusAttempts, backend.calls)
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
        val backend = FakeBackend(ascentPerKm = ::ascentByBearing)

        val results = generate(backend, target(40.0, AscentPreference.FLACH))

        assertEquals(3, results.size)
        assertEquals(0.0, results.first().bearingDeg, EPS)
        assertTrue(results.first().ascentPerKm < 3.0)
        // Aufsteigend sortiert: kleiner Score = besser.
        assertTrue(results[0].score <= results[1].score)
        assertTrue(results[1].score <= results[2].score)
    }

    @Test
    fun `BERGIG stellt die hoehenmeterreiche Runde nach vorn`() {
        val backend = FakeBackend(ascentPerKm = ::ascentByBearing)

        val results = generate(backend, target(40.0, AscentPreference.BERGIG))

        assertEquals(3, results.size)
        assertEquals(120.0, results.first().bearingDeg, EPS)
        assertTrue(results.first().ascentPerKm > 15.0)
        // Dieselbe Runde ist unter FLACH die schlechteste.
        val flach = generate(FakeBackend(ascentPerKm = ::ascentByBearing), target(40.0, AscentPreference.FLACH))
        assertEquals(120.0, flach.last().bearingDeg, EPS)
    }

    // --- Profil-Durchreichung ---

    @Test
    fun `reicht das gewaehlte Profil bei jedem Routing-Aufruf durch`() {
        val backend = FakeBackend()

        val results = generate(backend, target(40.0), profile = RouteProfile.ASPHALT)

        assertEquals(3, results.size)
        assertEquals(3, backend.profiles.size)
        assertTrue(backend.profiles.all { it == RouteProfile.ASPHALT })
    }

    @Test
    fun `ohne Angabe wird mit dem Gravel-Profil gerechnet`() {
        val backend = FakeBackend()

        runSync {
            generateRoutes(
                backend = backend,
                start = START,
                target = target(40.0),
                pauseMs = 0,
                sleeper = {},
                texts = CoreTextsDe,
            )
        }

        assertTrue(backend.profiles.isNotEmpty())
        assertTrue(backend.profiles.all { it == RouteProfile.SCHOTTER })
    }

    // --- Fehlertoleranz ---

    @Test
    fun `einzelner Routing-Fehler kostet nur diesen Kandidaten`() {
        // Kandidat 2 (Aufruf-Index 1) scheitert.
        val backend = FakeBackend(failAt = setOf(1))

        val results = generate(backend, target(40.0))

        assertEquals(2, results.size)
        assertTrue(results.none { it.bearingDeg == 120.0 })
    }

    @Test
    fun `scheitern alle Kandidaten, wirft es auf Deutsch samt Ursache`() {
        val backend = FakeBackend(
            failAt = setOf(0, 1, 2),
            failureMessage = "Routing-Server nicht erreichbar. Bist du online?",
        )

        val error = assertFailsWith<Exception> { generate(backend, target(40.0)) }

        // Generische Meldung vorn, die konkrete Backend-Ursache in Klammern —
        // daran unterscheidet die Oberflaeche „kein Netz" von „kein Weg".
        assertTrue(error.message!!.startsWith(errorNoRouteFound(CoreTextsDe)), "war: ${error.message}")
        assertTrue(error.message!!.contains("Routing-Server nicht erreichbar"))
        assertTrue(error.cause is RuntimeException)
    }

    @Test
    fun `eine Coroutine-Cancellation wird nicht als Kandidatenfehler geschluckt`() {
        val backend = RoutingBackend { _, _ -> throw CancellationException("abgebrochen") }

        assertFailsWith<CancellationException> { generate(backend, target(40.0)) }
    }

    @Test
    fun `wirft der Sleeper, verlaesst die Suche sofort`() {
        class Abort : Exception("Routensuche abgebrochen.")

        // Umwegfaktor 2,0 -> der erste Kandidat braeuchte einen zweiten
        // Versuch; vor dem zweiten Aufruf laeuft der Sleeper und wirft.
        val backend = FakeBackend(detourFactor = 2.0)

        assertFailsWith<Abort> {
            generate(backend, target(40.0), candidates = 2, sleeper = { throw Abort() })
        }
        assertEquals(1, backend.calls)
    }

    // --- Reproduzierbarkeit ---

    @Test
    fun `Seed 0 verteilt die Kandidaten auf 0, 120 und 240 Grad`() {
        val results = generate(FakeBackend(), target(40.0))

        assertEquals(listOf(0.0, 120.0, 240.0), results.map { it.bearingDeg }.sorted())
    }

    @Test
    fun `gleicher Seed liefert exakt dieselben Runden`() {
        val a = generate(FakeBackend(), target(40.0), seed = 7)
        val b = generate(FakeBackend(), target(40.0), seed = 7)

        assertEquals(a.map { it.bearingDeg }, b.map { it.bearingDeg })
        assertEquals(a.map { it.distanceKm }, b.map { it.distanceKm })
        assertEquals(a.first().route.points, b.first().route.points)
    }

    @Test
    fun `Seed plus eins wuerfelt andere Runden`() {
        val a = generate(FakeBackend(), target(40.0), seed = 7)
        val b = generate(FakeBackend(), target(40.0), seed = 8)

        assertTrue(a.map { it.bearingDeg }.toSet() != b.map { it.bearingDeg }.toSet())
        assertTrue(a.first().route.points != b.first().route.points)
    }

    // --- Grenzen ---

    @Test
    fun `zu kurze Zieldistanz wird auf 5 km angehoben`() {
        val backend = FakeBackend()

        val results = generate(backend, target(1.5), candidates = 1)

        assertEquals(minRouteTargetKm, results[0].targetKm, EPS)
        assertEquals(1, results[0].hints.size)
        assertTrue(results[0].hints[0].contains("5 km angehoben"))
        assertEquals(5.0, results[0].distanceKm, 5.0 * routeToleranceRatio)
    }

    @Test
    fun `zu lange Zieldistanz wird auf 200 km gedeckelt`() {
        val backend = FakeBackend()

        val results = generate(backend, target(500.0), candidates = 1)

        assertEquals(maxRouteTargetKm, results[0].targetKm, EPS)
        assertTrue(results[0].hints.single().contains("200 km gedeckelt"))
        assertEquals(200.0, results[0].distanceKm, 200.0 * routeToleranceRatio)
    }

    @Test
    fun `im Normalfall gibt es keine Hinweise`() {
        val results = generate(FakeBackend(), target(40.0), candidates = 1)

        assertTrue(results[0].hints.isEmpty())
    }

    @Test
    fun `Kandidatenzahl wird auf 1 bis 8 begrenzt`() {
        assertEquals(8, generate(FakeBackend(), target(40.0), candidates = 20).size)
        assertEquals(1, generate(FakeBackend(), target(40.0), candidates = 0).size)
    }

    // --- Betrieb ---

    @Test
    fun `onProgress meldet Start und jeden Kandidaten`() {
        val progress = mutableListOf<Pair<Int, Int>>()

        generate(FakeBackend(), target(40.0), onProgress = { done, total -> progress.add(done to total) })

        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun `zwischen zwei Routing-Aufrufen wird gewartet, davor nicht`() {
        val pauses = mutableListOf<Long>()
        // Umwegfaktor 2,0 -> zwei Aufrufe je Kandidat, zwei Kandidaten = 4 Aufrufe.
        val backend = FakeBackend(detourFactor = 2.0)

        generate(backend, target(40.0), candidates = 2, sleeper = { ms -> pauses.add(ms) })

        assertEquals(4, backend.calls)
        assertEquals(backend.calls - 1, pauses.size)
    }

    @Test
    fun `die Route traegt die Punkte der Backend-Antwort`() {
        val backend = FakeBackend()

        val results = generate(backend, target(40.0), candidates = 1)
        val route = results[0].route

        // 3 oder 4 Via-Punkte plus Start am Anfang und am Ende.
        assertTrue(route.points.size in 5..6, "Punkte: ${route.points.size}")
        assertEquals(START.lat, route.points.first().lat, 1e-6)
        assertEquals(START.lon, route.points.first().lon, 1e-6)
        assertEquals(route.points.first().lat, route.points.last().lat, 1e-6)
        assertEquals(route.distanceKm, results[0].distanceKm, EPS)
        assertEquals(route.ascentM, results[0].ascentM, EPS)
    }

    // --- Neue Gegenden (Entdeckt-Kacheln) ---

    @Test
    fun `neue Kacheln werden gegen die entdeckte Menge gezaehlt`() {
        // Gut 6 km nach Osten, quert mehrere Kacheln der Stufe 14.
        val track = listOf(START, TrackPoint(lat = START.lat, lon = START.lon + 0.085))
        val all = explorerTilesForTrack(track)
        assertTrue(all.size >= 4, "Kacheln: ${all.size}")

        val known = all.take(2).toSet()
        assertEquals(all.size - 2 to all.size, countNewTiles(track, known))
        // Ohne entdeckte Kacheln ist alles neu, mit allen nichts.
        assertEquals(all.size to all.size, countNewTiles(track, emptySet()))
        assertEquals(0 to all.size, countNewTiles(track, all))
        assertEquals(0 to 0, countNewTiles(emptyList(), known))
    }

    @Test
    fun `Kandidaten tragen neue und gesamte Kacheln`() {
        val baseline = generate(FakeBackend(), target(40.0))
        for (r in baseline) {
            assertTrue(r.totalTileCount > 0)
            assertEquals(r.totalTileCount, r.newTileCount, "ohne Menge ist jede Kachel neu")
            assertEquals(explorerTilesForTrack(r.route.points).size, r.totalTileCount)
        }

        // Die Kacheln der besten Runde gelten als entdeckt.
        val explored = explorerTilesForTrack(baseline.first().route.points)
        val results = generate(FakeBackend(), target(40.0), exploredTiles = explored)
        val same = results.single { it.bearingDeg == baseline.first().bearingDeg }
        assertEquals(0, same.newTileCount)
        assertEquals(0.0, same.newTileShare, EPS)
        assertTrue(results.any { it.newTileCount > 0 })
    }

    @Test
    fun `Neu-Bonus gewinnt bei aehnlicher Distanz`() {
        // Bekannt: 2,5 % daneben. Komplett neu: 10 % daneben -> 10 − 12 = −2.
        val bekannt = scoreRoute(41.0, 0.0, 40.0, AscentPreference.FLACH, newTileShare = 0.0)
        val neu = scoreRoute(44.0, 0.0, 40.0, AscentPreference.FLACH, newTileShare = 1.0)
        assertTrue(neu < bekannt, "neu=$neu bekannt=$bekannt")

        // Halb neu (Bonus 6) schlaegt eine bekannte Runde, die 5 Prozentpunkte besser passt.
        val halb = scoreRoute(42.0, 0.0, 40.0, AscentPreference.FLACH, newTileShare = 0.5)
        val exakt = scoreRoute(40.0, 0.0, 40.0, AscentPreference.FLACH)
        assertTrue(halb < exakt, "halb=$halb exakt=$exakt")
    }

    @Test
    fun `Distanz dominiert den Neu-Bonus bei grosser Abweichung`() {
        val exakt = scoreRoute(40.0, 0.0, 40.0, AscentPreference.FLACH, newTileShare = 0.0)
        // 15 % daneben, aber komplett neu: 15 − 12 = 3 > 0.
        val neu15 = scoreRoute(46.0, 0.0, 40.0, AscentPreference.FLACH, newTileShare = 1.0)
        assertTrue(exakt < neu15, "exakt=$exakt neu15=$neu15")
        val neu30 = scoreRoute(52.0, 0.0, 40.0, AscentPreference.FLACH, newTileShare = 1.0)
        assertTrue(scoreRoute(41.0, 0.0, 40.0, AscentPreference.FLACH) < neu30)

        // Der Bonus ist gedeckelt und robust gegen Unsinn.
        assertEquals(-noveltyScoreWeight, noveltyScore(3.0), EPS)
        assertEquals(0.0, noveltyScore(-1.0), EPS)
        assertEquals(0.0, noveltyScore(Double.NaN), EPS)
    }

    @Test
    fun `ohne Schalter bleiben Scores und Kurse unveraendert`() {
        val plain = generate(FakeBackend(), target(40.0))
        val explored = explorerTilesForTrack(plain.first().route.points)
        val withTiles = generate(FakeBackend(), target(40.0), exploredTiles = explored)

        assertEquals(plain.map { it.bearingDeg }, withTiles.map { it.bearingDeg })
        assertEquals(plain.map { it.score }, withTiles.map { it.score })
        // Der Score entspricht exakt der alten Formel ohne Neu-Anteil.
        for (r in plain) {
            assertEquals(scoreRoute(r.distanceKm, r.ascentM, r.targetKm, AscentPreference.FLACH), r.score, EPS)
        }
    }

    @Test
    fun `mit Schalter gewinnt die Runde durch neue Gegenden`() {
        // Alle Runden treffen die Zieldistanz exakt -> ohne Schalter Gleichstand,
        // sortiert nach Kurs (0°, 120°, 240°).
        val plain = generate(FakeBackend(fixedDistanceKm = 40.0), target(40.0))
        assertEquals(listOf(0.0, 120.0, 240.0), plain.map { it.bearingDeg })
        // Die Runden bei 0° und 120° sind schon abgefahren.
        val explored = explorerTilesForTrack(plain[0].route.points) + explorerTilesForTrack(plain[1].route.points)

        val results = generate(
            FakeBackend(fixedDistanceKm = 40.0),
            target(40.0),
            exploredTiles = explored,
            preferNewAreas = true,
        )

        assertEquals(3, results.size)
        val best = results.first()
        assertTrue(results.all { best.newTileShare >= it.newTileShare }, results.map { it.newTileShare }.toString())
        assertTrue(best.newTileShare > 0.5, "Anteil ${best.newTileShare}")
        assertTrue(best.bearingDeg in 210.0..270.0, "Kurs ${best.bearingDeg}")
        assertTrue(results[0].score <= results[1].score && results[1].score <= results[2].score)
        for (r in results) {
            val expected = scoreRoute(r.distanceKm, r.ascentM, r.targetKm, AscentPreference.FLACH, r.newTileShare)
            assertEquals(expected, r.score, EPS)
        }
    }

    @Test
    fun `mit Schalter neigt sich der Startkurs zu unentdeckten Kacheln`() {
        // Alles oestlich der Startspalte (inkl.) gilt als entdeckt.
        val s = explorerTileAt(START.lat, START.lon)
        val explored = buildSet {
            for (x in s.x..s.x + 30) for (y in s.y - 30..s.y + 30) add(ExplorerTile(x, y))
        }

        val plain = generate(FakeBackend(), target(40.0), exploredTiles = explored)
        assertEquals(setOf(0.0, 120.0, 240.0), plain.map { it.bearingDeg }.toSet())

        val biased = generate(FakeBackend(), target(40.0), exploredTiles = explored, preferNewAreas = true)
        val bearings = biased.map { it.bearingDeg }.toSet()
        // Die Nordrunde dreht um δ = 30° nach Westen, die Suedost-Runde nach Sueden.
        assertTrue(330.0 in bearings, "Kurse: $bearings")
        assertTrue(150.0 in bearings, "Kurse: $bearings")
    }

    // --- Wind (Schritt 7) ---

    /** Kleinster Winkelabstand zweier Kurse in Grad (0…180). */
    private fun angleBetween(a: Double, b: Double): Double {
        val d = abs(((a - b) % 360 + 360) % 360)
        return if (d > 180) 360 - d else d
    }

    @Test
    fun `ohne Wind bleiben Kandidaten bitgenau gleich`() {
        for (seed in 0..3) {
            val plain = generate(FakeBackend(), target(40.0), seed = seed)
            val nullWind = generate(FakeBackend(), target(40.0), seed = seed, wind = null)
            assertEquals(plain.map { it.bearingDeg }, nullWind.map { it.bearingDeg })
            assertEquals(plain.map { it.score }, nullWind.map { it.score })
            assertEquals(plain, nullWind)
            assertTrue(nullWind.all { it.windShape == null })
        }
    }

    @Test
    fun `schwacher Wind wird ignoriert, auch bei den Kursen`() {
        for (seed in 0..3) {
            val plain = generate(FakeBackend(), target(40.0), seed = seed)
            val weak = generate(FakeBackend(), target(40.0), seed = seed, wind = WindConditions(5.0, 240.0, 30.0))
            assertEquals(plain, weak)
            assertTrue(weak.all { it.windShape == null })
        }
    }

    @Test
    fun `starker Wind stellt die Runde gegen den Wind nach vorn`() {
        // Alle Distanzen gleich gut -> ohne Wind Gleichstand, sortiert nach Kurs.
        val plain = generate(FakeBackend(fixedDistanceKm = 40.0), target(40.0))
        assertEquals(0.0, plain.first().bearingDeg, EPS)

        val wind = WindConditions(speedKmh = 30.0, fromDeg = 240.0, gustsKmh = 45.0)
        val results = generate(FakeBackend(fixedDistanceKm = 40.0), target(40.0), wind = wind)

        val best = results.first()
        val closest = results.minBy { angleBetween(it.bearingDeg, 240.0) }
        assertEquals(closest.bearingDeg, best.bearingDeg, EPS)
        assertEquals(240.0, best.bearingDeg, EPS)
        val shape = best.windShape
        assertTrue(shape != null && shape > windTailwindHomeMinShape, "Form $shape")
        assertTrue(results.all { it.windShape != null })
        for (r in results) {
            val expected = scoreRoute(
                r.distanceKm, r.ascentM, r.targetKm, AscentPreference.FLACH,
                windBonus = windScore(r.route.points, wind),
            )
            assertEquals(expected, r.score, EPS)
        }
        // Auch mit realistischem Umwegfaktor (Distanzen leicht verschieden).
        val realistic = generate(FakeBackend(), target(40.0), wind = wind)
        assertEquals(240.0, realistic.first().bearingDeg, EPS)
    }

    @Test
    fun `Wind schlaegt die Distanz nicht`() {
        val wind = WindConditions(speedKmh = 40.0, fromDeg = 240.0, gustsKmh = null)
        // Die windguenstige Runde (Schwerpunkt Richtung 240°) ist 20 % zu lang,
        // alle anderen treffen das Ziel genau.
        val backend = RoutingBackend { waypoints, _ ->
            val points = waypoints.map { TrackPoint(lat = it.lat, lon = it.lon, ele = 500.0) }
            val windward = angleBetween(bearingTo(START, viaCentroid(points)), 240.0) < 45
            PlannedRoute(points = points, distanceKm = if (windward) 48.0 else 40.0, ascentM = 0.0)
        }

        val results = generate(backend, target(40.0), wind = wind)

        assertEquals(3, results.size)
        val windward = results.single { angleBetween(it.bearingDeg, 240.0) < 45 }
        assertTrue(windward.windShape!! > windTailwindHomeMinShape)
        assertTrue(results.first() !== windward, "Kurse: ${results.map { it.bearingDeg }}")
        assertEquals(results.last(), windward)
    }

    @Test
    fun `Kursneigung mit Wind bleibt im Fenster und ist deterministisch`() {
        val wind = WindConditions(speedKmh = 25.0, fromDeg = 200.0, gustsKmh = null)
        for (seed in 0..3) {
            val plain = generate(FakeBackend(), target(40.0), seed = seed)
            val first = generate(FakeBackend(), target(40.0), seed = seed, wind = wind)
            val second = generate(FakeBackend(), target(40.0), seed = seed, wind = wind)
            assertEquals(first, second)

            val base = plain.map { it.bearingDeg }.sorted()
            for (r in first) {
                val offset = base.minOf { angleBetween(it, r.bearingDeg) }
                assertTrue(
                    abs(offset) < 1e-6 || abs(offset - 30.0) < 1e-6,
                    "seed $seed: Kurs ${r.bearingDeg} gegen $base",
                )
            }
        }
        val s0 = generate(FakeBackend(), target(40.0), seed = 0, wind = wind).map { it.bearingDeg }.toSet()
        val s1 = generate(FakeBackend(), target(40.0), seed = 1, wind = wind).map { it.bearingDeg }.toSet()
        assertTrue(s0 != s1, "$s0 / $s1")
    }

    @Test
    fun `mit neuen Gegenden entscheidet die Neuheit, der Wind nur den Gleichstand`() {
        // Wie oben: alles oestlich der Startspalte ist entdeckt. Wind aus Ost
        // zoege die Kurse nach Osten — die Neuheit gewinnt trotzdem.
        val s = explorerTileAt(START.lat, START.lon)
        val eastExplored = buildSet {
            for (x in s.x..s.x + 30) for (y in s.y - 30..s.y + 30) add(ExplorerTile(x, y))
        }
        val eastWind = WindConditions(speedKmh = 30.0, fromDeg = 90.0, gustsKmh = null)
        val biased = generate(
            FakeBackend(), target(40.0),
            exploredTiles = eastExplored, preferNewAreas = true, wind = eastWind,
        ).map { it.bearingDeg }.toSet()
        assertTrue(330.0 in biased, "Kurse: $biased")
        assertTrue(150.0 in biased, "Kurse: $biased")

        // Alles ringsum ist entdeckt -> Gleichstand bei der Neuheit (ueberall
        // 0 unentdeckte Kacheln), jetzt entscheidet der Wind (aus 240°).
        val all = buildSet {
            for (x in s.x - 30..s.x + 30) for (y in s.y - 30..s.y + 30) add(ExplorerTile(x, y))
        }
        val noWind = generate(FakeBackend(), target(40.0), exploredTiles = all, preferNewAreas = true)
        assertEquals(setOf(0.0, 120.0, 240.0), noWind.map { it.bearingDeg }.toSet())
        val withWind = generate(
            FakeBackend(), target(40.0),
            exploredTiles = all, preferNewAreas = true,
            wind = WindConditions(speedKmh = 30.0, fromDeg = 240.0, gustsKmh = null),
        )
        assertEquals(setOf(330.0, 150.0, 240.0), withWind.map { it.bearingDeg }.toSet())
    }

    @Test
    fun `scoreRoute mit Windbonus`() {
        val plain = scoreRoute(42.0, 300.0, 40.0, AscentPreference.MODERAT, newTileShare = 0.3)
        assertEquals(plain, scoreRoute(42.0, 300.0, 40.0, AscentPreference.MODERAT, 0.3, windBonus = 0.0))
        assertEquals(plain, scoreRoute(42.0, 300.0, 40.0, AscentPreference.MODERAT, 0.3, windBonus = Double.NaN))
        assertEquals(plain - 4.0, scoreRoute(42.0, 300.0, 40.0, AscentPreference.MODERAT, 0.3, windBonus = -4.0), EPS)
    }

    // ------------------------------------------------------------------
    // Rundenguete (Schritt 8)
    // ------------------------------------------------------------------

    /** Wie [FakeBackend], faehrt aber jeden Via-Punkt als 200-m-Sackgasse an. */
    private val spurBackend = RoutingBackend { waypoints, _ ->
        val points = mutableListOf<TrackPoint>()
        waypoints.forEachIndexed { index, w ->
            val at = TrackPoint(lat = w.lat, lon = w.lon, ele = 500.0)
            points.add(at)
            if (index in 1 until waypoints.size - 1) {
                points.add(destinationPoint(at, bearingTo(START, at), 200.0).copy(ele = 500.0))
                points.add(at)
            }
        }
        val km = points.zipWithNext { a, b -> haversineM(a, b) }.sum() / 1000
        PlannedRoute(points = points, distanceKm = km, ascentM = 0.0)
    }

    @Test
    fun `Stichstrecken zu den Via-Punkten werden herausgeschnitten`() {
        val results = generate(spurBackend, target(40.0))

        assertEquals(3, results.size)
        for (r in results) {
            // Kein Punkt wird mehr zweimal angefahren, die Runde ist sauber.
            assertTrue(r.retraceShare < 0.01, "Anteil ${r.retraceShare}")
            assertEquals(r.route.distanceKm, r.distanceKm, EPS)
            // Die Zieldistanz gilt fuer die bereinigte Runde.
            assertTrue(r.distanceDeviation <= routeToleranceRatio, "Abweichung ${r.distanceDeviation}")
            val measured = r.route.points.zipWithNext { a, b -> haversineM(a, b) }.sum() / 1000
            assertEquals(measured, r.distanceKm, 0.05)
        }
    }

    @Test
    fun `eine Hin-und-zurueck-Strecke verliert gegen eine echte Runde`() {
        // Der Kandidat Richtung Norden kommt als reine Pendelstrecke zurueck
        // und trifft die Distanz genau; die Runden liegen 5 % daneben.
        val backend = RoutingBackend { waypoints, _ ->
            val loop = waypoints.map { TrackPoint(lat = it.lat, lon = it.lon, ele = 500.0) }
            val north = angleBetween(bearingTo(START, viaCentroid(loop)), 0.0) < 45
            if (north) {
                val far = destinationPoint(START, 0.0, 20_000.0).copy(ele = 500.0)
                PlannedRoute(points = listOf(START, far, START), distanceKm = 40.0, ascentM = 0.0)
            } else {
                PlannedRoute(points = loop, distanceKm = 42.0, ascentM = 0.0)
            }
        }

        val results = generate(backend, target(40.0))

        val pendel = results.single { it.retraceShare > 0.9 }
        assertEquals(results.last(), pendel)
        assertTrue(results.first().retraceShare < 0.01)
    }

    @Test
    fun `scoreRoute rechnet den doppelt befahrenen Anteil ein`() {
        val plain = scoreRoute(42.0, 300.0, 40.0, AscentPreference.MODERAT)
        assertEquals(plain + 10.0, scoreRoute(42.0, 300.0, 40.0, AscentPreference.MODERAT, retraceShare = 0.25), EPS)
    }

    @Test
    fun `im Gravel-Profil gewinnt die Runde mit mehr Schotter, im Asphalt-Profil die ohne`() {
        // Alle drei Runden sind gleich lang; nur die nach Norden ist zu 60 % unbefestigt.
        val backend = RoutingBackend { waypoints, _ ->
            val points = waypoints.map { TrackPoint(lat = it.lat, lon = it.lon, ele = 500.0) }
            val north = angleBetween(bearingTo(START, viaCentroid(points)), 0.0) < 45
            PlannedRoute(
                points = points,
                distanceKm = 40.0,
                ascentM = 0.0,
                pavedKm = if (north) 16.0 else 40.0,
                unpavedKm = if (north) 24.0 else 0.0,
            )
        }
        fun northFirst(profile: RouteProfile): Boolean {
            val results = generate(backend, target(40.0), profile = profile)
            return results.first().route.unpavedKm == 24.0
        }

        assertTrue(northFirst(RouteProfile.SCHOTTER))
        assertTrue(northFirst(RouteProfile.GRAVEL))
        val road = generate(backend, target(40.0), profile = RouteProfile.ASPHALT)
        assertEquals(24.0, road.last().route.unpavedKm)
    }

    // ------------------------------------------------------------------
    // Guete-Schwelle und Nachsuche (Schritt 9)
    // ------------------------------------------------------------------

    /** Kurse des regulaeren Durchgangs bei Seed 0 und drei Kandidaten. */
    private val seed0Bearings = listOf(0.0, 120.0, 240.0)

    /** Ob [points] eine Runde unter einem der Kurse von Seed 0 ist (Schwerpunkt der Via-Punkte). */
    private fun isSeed0Loop(points: List<TrackPoint>): Boolean {
        val bearing = bearingTo(START, viaCentroid(points))
        return seed0Bearings.any { angleBetween(bearing, it) < 10.0 }
    }

    /**
     * Ein Start am Seeufer: Die drei Kurse von Seed 0 laufen schlecht — mit
     * [poorKm] als feste Laenge (die Radius-Iteration hilft nicht, drei
     * Versuche je Kandidat) oder, ohne [poorKm], als exakt passende
     * Pendelstrecke. Alle anderen Kurse verhalten sich wie [FakeBackend].
     */
    private class LakeShoreBackend(
        val isPoor: (List<TrackPoint>) -> Boolean,
        val poorKm: Double? = null,
        val targetKm: Double = 40.0,
    ) : RoutingBackend {
        var calls = 0
        private val fake = FakeBackend()

        override suspend fun route(waypoints: List<Waypoint>, profile: RouteProfile): PlannedRoute {
            calls += 1
            val points = waypoints.map { TrackPoint(lat = it.lat, lon = it.lon, ele = 500.0) }
            if (!isPoor(points)) return fake.route(waypoints, profile)
            if (poorKm != null) return PlannedRoute(points = points, distanceKm = poorKm, ascentM = 0.0)
            val far = destinationPoint(START, bearingTo(START, viaCentroid(points)), targetKm * 500).copy(ele = 500.0)
            return PlannedRoute(points = listOf(START, far, START), distanceKm = targetKm, ascentM = 0.0)
        }
    }

    @Test
    fun `ist der erste Durchgang gut, gibt es keine Nachsuche und bitgenau dasselbe Ergebnis`() {
        for (seed in 0..3) {
            val withFollowUp = FakeBackend()
            val without = FakeBackend()
            val a = generate(withFollowUp, target(40.0), seed = seed)
            val b = generate(without, target(40.0), seed = seed, followUpSearch = false)

            assertEquals(b, a)
            assertTrue(a.first().quality == RouteQualityLevel.GUT)
            // Mit Umwegfaktor 1,3 ein Aufruf je Kandidat — keiner mehr.
            assertEquals(3, withFollowUp.calls)
            assertEquals(without.calls, withFollowUp.calls)
        }
    }

    @Test
    fun `liegt der erste Durchgang 18 Prozent daneben, gewinnt die gedrehte Runde`() {
        val backend = LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18)
        val pauses = mutableListOf<Long>()

        val results = generate(backend, target(40.0), sleeper = { pauses.add(it) })

        assertEquals(3, results.size)
        val best = results.first()
        assertEquals(RouteQualityLevel.GUT, best.quality)
        assertTrue(seed0Bearings.none { angleBetween(best.bearingDeg, it) < 1.0 }, "Kurs ${best.bearingDeg}")
        // Die Nachsuche dreht um den goldenen Winkel: 137,5°, 257,5°, 17,5°.
        val rotated = listOf(137.50776405003785, 257.50776405003785, 17.50776405003785)
        assertTrue(rotated.any { abs(it - best.bearingDeg) < 1e-6 }, "Kurs ${best.bearingDeg}")
        assertTrue(results.zipWithNext().all { (x, y) -> x.score <= y.score })
        // 3 × 3 Versuche im ersten Durchgang (die Laenge haengt nicht am
        // Radius), dann je einer — innerhalb der harten Grenze.
        assertEquals(9 + 3, backend.calls)
        assertTrue(backend.calls <= maxRoutingCallsPerSearch(3))
        // Die Pause gilt auch fuer die Nachsuche: vor jedem Aufruf ausser dem ersten.
        assertEquals(backend.calls - 1, pauses.size)
        assertTrue(pauses.all { it == 0L })
    }

    @Test
    fun `eine Pendelstrecke im ersten Durchgang loest die Nachsuche aus`() {
        val backend = LakeShoreBackend(::isSeed0Loop)

        val results = generate(backend, target(40.0))

        // Die Pendelstrecken treffen die Distanz exakt, aber keine ist gut.
        assertEquals(3 + 3, backend.calls)
        assertEquals(RouteQualityLevel.GUT, results.first().quality)
        assertTrue(results.first().retraceShare < 0.01)
        assertTrue(results.none { it.retraceShare > 0.9 }, "die Pendelstrecken fallen aus der Liste")
    }

    @Test
    fun `abgeschaltet bleibt es beim alten Verhalten`() {
        val backend = LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18)

        val results = generate(backend, target(40.0), followUpSearch = false)

        assertEquals(9, backend.calls)
        assertEquals(seed0Bearings, results.map { it.bearingDeg }.sorted())
        assertTrue(results.all { it.quality == RouteQualityLevel.BRAUCHBAR })
        assertTrue(results.all { it.qualityIssue == RouteQualityIssue.DISTANZ })
    }

    @Test
    fun `hoechstens eine Nachsuche, auch wenn sie nichts Besseres findet`() {
        for (candidates in listOf(1, 3, 8)) {
            // Nie konvergent, nie gut.
            val backend = FakeBackend(fixedDistanceKm = 100.0)
            val pauses = mutableListOf<Long>()

            val results = generate(backend, target(40.0), candidates = candidates, sleeper = { pauses.add(it) })

            assertEquals(maxRoutingCallsPerSearch(candidates), backend.calls)
            assertEquals(2 * candidates * maxRadiusAttempts, backend.calls)
            assertEquals(backend.calls - 1, pauses.size)
            assertEquals(candidates, results.size)
            assertTrue(results.all { it.quality == RouteQualityLevel.SCHWACH })
        }
    }

    @Test
    fun `scheitert der erste Durchgang ganz, gibt es keine Nachsuche`() {
        val backend = FakeBackend(failAt = setOf(0, 1, 2))

        assertFailsWith<Exception> { generate(backend, target(40.0)) }
        assertEquals(3, backend.calls)
    }

    @Test
    fun `ein Abbruch in der Nachsuche verlaesst die Suche sofort`() {
        class Abort : Exception("Routensuche abgebrochen.")
        val backend = LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18)
        var pauses = 0

        assertFailsWith<Abort> {
            // Der erste Durchgang braucht 9 Aufrufe und 8 Pausen; die 9. Pause
            // steht vor dem ersten Aufruf der Nachsuche.
            generate(backend, target(40.0), sleeper = { if (++pauses == 9) throw Abort() })
        }
        assertEquals(9, backend.calls)

        // Ebenso eine Coroutine-Cancellation aus dem Backend mitten in der Nachsuche.
        var calls = 0
        val cancelling = RoutingBackend { waypoints, profile ->
            calls += 1
            if (calls == 11) throw CancellationException("abgebrochen")
            LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18).route(waypoints, profile)
        }
        assertFailsWith<CancellationException> { generate(cancelling, target(40.0)) }
        assertEquals(11, calls)
    }

    @Test
    fun `onProgress waechst mit der Nachsuche, ohne zurueckzulaufen`() {
        val progress = mutableListOf<Pair<Int, Int>>()

        generate(
            LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18),
            target(40.0),
            onProgress = { done, total -> progress.add(done to total) },
        )

        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 6, 4 to 6, 5 to 6, 6 to 6), progress)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.first >= a.first && b.second >= a.second })
    }

    @Test
    fun `die Nachsuche ist deterministisch`() {
        val a = generate(LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18), target(40.0))
        val b = generate(LakeShoreBackend(::isSeed0Loop, poorKm = 40.0 * 1.18), target(40.0))

        assertEquals(a, b)
    }

    @Test
    fun `Kandidaten tragen das Profil fuer das Belags-Urteil`() {
        val results = generate(FakeBackend(), target(40.0), profile = RouteProfile.ASPHALT)

        assertTrue(results.all { it.profile == RouteProfile.ASPHALT })
    }
}
