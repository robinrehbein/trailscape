package de.trailscape.core.indoor

import de.trailscape.core.AscentPreference
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Einheit des Tages: Einheit, Ziel, Nachrechnung, Bewertung, Startpunkt, Reihenfolge, Ende. */
class DayRouteTest {
    private val rider = RiderSetup(riderMassKg = 75.0, bikeMassKg = 10.0, cdaM2 = 0.4, profileCrr = 0.008, ftpW = 250.0)

    private fun session(
        intensity: SessionIntensity = SessionIntensity.GRUNDLAGE,
        durationMin: Int? = 60,
        textKey: SessionTextKey? = null,
        textArgs: List<Int> = emptyList(),
        isEvent: Boolean = false,
    ) = TrainingSession(
        day = "2026-11-24",
        title = "Einheit",
        description = "",
        targetKm = 30,
        intensity = intensity,
        durationMin = durationMin,
        isEvent = isEvent,
        textKey = textKey,
        textArgs = textArgs,
    )

    /** 3 × 8 min mit 4 min Pause, 10 min Ein-, 10 min Ausfahren (52 min). */
    private val intervals = session(SessionIntensity.HART, 52, SessionTextKey.INTERVALS, listOf(10, 3, 8, 4, 10, 52, 0))

    private fun target(intensity: SessionIntensity, km: Double = 30.0, hours: Double? = 1.2) = RouteTarget(
        distanceKm = km,
        ascentPreference = AscentPreference.MODERAT,
        durationH = hours,
        speedKmh = 25.0,
        intensity = intensity,
        label = "Runde",
        source = RouteTargetSource.PLAN,
    )

    private fun today(
        target: RouteTarget?,
        session: TrainingSession?,
        factor: Double = 1.0,
        downgraded: Boolean = false,
    ) = TodayRoute(target, session, session?.targetKm, factor, downgraded, note = null)

    private fun workout(s: TrainingSession) = ergWorkoutForImpl(s, "t")!!

    private fun profile(lengthM: Double, isLoop: Boolean = false, ele: (Double) -> Double): DayRouteProfile {
        val n = (lengthM / 10).toInt()
        val d = DoubleArray(n + 1) { minOf(it * 10.0, lengthM) }
        return DayRouteProfile.fromSamples(d, DoubleArray(n + 1) { ele(d[it]) }, isLoop)!!
    }

    /** Waechst stueckweise: [climbs] Anstiege von [climbM] mit [gradePct], dazwischen [flatM] flach. */
    private fun climbsEle(flatM: Double, climbM: Double, gradePct: Double, climbs: Int, leadM: Double = flatM): (Double) -> Double = { d ->
        var e = 0.0
        var at = leadM
        for (i in 0 until climbs) {
            if (d > at) e += minOf(d - at, climbM) * gradePct / 100
            at += climbM + flatM
        }
        e
    }

    // -----------------------------------------------------------------------
    // Welche Einheit
    // -----------------------------------------------------------------------

    @Test
    fun `Zieltag und Ruhetag bieten nichts an`() {
        val event = daySessionFor(today(target(SessionIntensity.HART), session(isEvent = true)))
        assertEquals(DaySessionKind.EVENT, event.kind)
        assertNull(event.session)
        val rest = daySessionFor(today(null, intervals, factor = 0.0, downgraded = true))
        assertEquals(DaySessionKind.REST, rest.kind)
        assertNull(rest.session)
        assertEquals(intervals, rest.planned)
        assertEquals(DaySessionKind.REST, daySessionFor(today(null, null, factor = 0.0)).kind)
    }

    @Test
    fun `ohne Planeinheit eine kurze lockere Runde`() {
        val easy = daySessionFor(today(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null))
        assertEquals(DaySessionKind.EASY, easy.kind)
        assertEquals(SessionIntensity.LOCKER, easy.session!!.intensity)
        assertEquals(DAY_EASY_MAX_MIN, easy.session!!.durationMin)
        assertNull(easy.planned)
        val shorter = daySessionFor(today(target(SessionIntensity.LOCKER, hours = 0.5), null)).session!!
        assertEquals(30, shorter.durationMin)
        val tiny = daySessionFor(today(target(SessionIntensity.LOCKER, hours = 0.1), null)).session!!
        assertEquals(DAY_EASY_MIN_MIN, tiny.durationMin)
        assertEquals(DayTerrain.FLAT, dayRouteGoal(workout(easy.session!!), rider).terrain)
    }

    @Test
    fun `Planeinheit unveraendert oder von der Tagesform gedaempft`() {
        val plain = daySessionFor(today(target(SessionIntensity.HART), intervals))
        assertEquals(DaySessionKind.PLANNED, plain.kind)
        assertEquals(intervals, plain.session)

        // Grundlagentag: Intervalle werden gleichmaessig, Dauer mal 0,9 (wie die Distanz in decideTodayRoute).
        val cut = daySessionFor(today(target(SessionIntensity.GRUNDLAGE), intervals, factor = 0.9, downgraded = true))
        assertEquals(DaySessionKind.PLANNED_REDUCED, cut.kind)
        val s = cut.session!!
        assertEquals(SessionIntensity.GRUNDLAGE, s.intensity)
        assertEquals(47, s.durationMin)
        assertNull(s.textKey)
        assertEquals(intervals, cut.planned)
        val w = workout(s)
        assertEquals(47 * 60, w.totalS)
        assertTrue(w.steps.none { it.kind == ErgStepKind.WORK })

        // Nur die Distanz gedaempft, Intensitaet bleibt: kuerzer, gleiche Art.
        val base = session(SessionIntensity.GRUNDLAGE, 100)
        val shorter = daySessionFor(today(target(SessionIntensity.GRUNDLAGE), base, factor = 0.6, downgraded = true)).session!!
        assertEquals(60, shorter.durationMin)
        assertEquals(SessionIntensity.GRUNDLAGE, shorter.intensity)
    }

    // -----------------------------------------------------------------------
    // Ziel
    // -----------------------------------------------------------------------

    @Test
    fun `Gelaende nach Art der Einheit`() {
        val hard = dayRouteGoal(workout(intervals), rider)
        assertEquals(DayTerrain.CLIMBS, hard.terrain)
        assertEquals(listOf(600, 1320, 2040), hard.intervals.map { it.startS })
        assertTrue(hard.intervals.all { it.durationS == 480 })
        assertEquals(DayTerrain.ROLLING, dayRouteGoal(workout(session(SessionIntensity.GRUNDLAGE)), rider).terrain)
        assertEquals(DayTerrain.FLAT, dayRouteGoal(workout(session(SessionIntensity.LOCKER)), rider).terrain)
        // Erwartete Distanz: Grundlage 60 min mit 250 W FTP flach — zwischen 25 und 40 km.
        val km = dayRouteGoal(workout(session(SessionIntensity.GRUNDLAGE)), rider).expectedFlatM / 1000
        assertTrue(km in 25.0..40.0, "$km")
    }

    // -----------------------------------------------------------------------
    // Profil
    // -----------------------------------------------------------------------

    @Test
    fun `Profil aus Stichproben, Track und Schaetzung`() {
        val p = profile(2000.0) { if (it < 1000) 0.0 else (it - 1000) * 0.05 }
        assertEquals(2000.0, p.lengthM, 1e-9)
        assertEquals(40, p.steps)
        assertEquals(0.0, p.gradePct[5], 1e-9)
        assertEquals(5.0, p.gradePct[30], 1e-9)
        assertEquals(50.0, p.ascentM, 3.0)
        assertNull(DayRouteProfile.fromSamples(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, 0.0), false), "zu kurz")

        // Track: ohne Hoehe kein Profil; Runde, wenn Start und Ziel nah beieinander liegen.
        val line = List(101) { i -> TrackPoint(lat = 50.0 + i * 0.0001, lon = 7.0) }
        assertNull(DayRouteProfile.ofTrack(line))
        val withEle = line.mapIndexed { i, t -> t.copy(ele = if (i % 10 == 0) i * 0.3 else null) }
        val tp = DayRouteProfile.ofTrack(withEle)!!
        assertFalse(tp.isLoop)
        assertEquals(1112.0, tp.lengthM, 5.0)
        val outAndBack = (line + line.reversed().drop(1)).map { it.copy(ele = 100.0) }
        assertTrue(DayRouteProfile.ofTrack(outAndBack)!!.isLoop)

        // Schaetzung: Pass als ein Anstieg, wellig im Wechsel.
        val pass = DayRouteProfile.estimate(15000.0, 1200.0, isLoop = false)!!
        assertTrue(pass.estimated)
        assertTrue(pass.gradePct.all { abs(it - 8.0) < 1e-9 })
        val hills = DayRouteProfile.estimate(10000.0, 100.0, isLoop = false)!!
        assertEquals(2.0, hills.gradePct[0], 1e-9)
        assertEquals(-2.0, hills.gradePct[25], 1e-9)
    }

    // -----------------------------------------------------------------------
    // Nachrechnung
    // -----------------------------------------------------------------------

    @Test
    fun `Nachrechnung trifft die Sitzung im ERG-Modus`() {
        val w = workout(session(SessionIntensity.GRUNDLAGE, 30))
        val route = IndoorTestRoutes.straight(lengthM = 30_000.0, ele = IndoorTestRoutes.climbProfile(5000.0, 8000.0, 4.0))
        val goal = dayRouteGoal(w, rider)
        val sim = simulateDayRide(goal, DayRouteProfile.of(route)!!, 0.0)

        val s = createIndoorSession(
            IndoorSessionConfig(
                route = route,
                mode = IndoorMode.ERG,
                rider = rider,
                workout = w,
                capabilities = TrainerCapabilities(simulation = true, targetPower = true),
                startedAtEpochMs = 0L,
            ),
        )
        var target = 100
        var now = 0L
        var state: IndoorSessionState? = null
        while (now <= w.totalS * 1000L) {
            val step = s.step(now, SessionInput(TrainerTelemetry(target, 85.0, null, now)))
            step.commands.filterIsInstance<TrainerCommand.SetTargetPower>().lastOrNull()?.let { target = it.watts }
            state = step.state
            now += 100
        }
        val rel = abs(state!!.distM - sim.distanceM) / state.distM
        assertTrue(rel < 0.03, "Sitzung ${state.distM} m, Nachrechnung ${sim.distanceM} m")
    }

    @Test
    fun `Strecke zu kurz, Runde wird wiederholt`() {
        val goal = dayRouteGoal(workout(session(SessionIntensity.GRUNDLAGE, 60)), rider)
        val short = profile(5000.0) { 0.0 }
        val sim = simulateDayRide(goal, short, 0.0)
        assertNotNull(sim.routeEndsAtS)
        assertEquals(5000.0, sim.distanceM, 1.0)
        assertTrue(scoreDayRoute(goal, short, sim) < 0.75)

        val loop = profile(5000.0, isLoop = true) { 0.0 }
        val lap = simulateDayRide(goal, loop, 0.0)
        assertNull(lap.routeEndsAtS)
        assertTrue(lap.laps >= 6, "${lap.laps}")
        assertEquals(goal.expectedFlatM, lap.distanceM, goal.expectedFlatM * 0.01)

        val long = profile(goal.expectedFlatM + 1500) { 0.0 }
        val fit = scoreDayRoute(goal, long, simulateDayRide(goal, long, 0.0))
        assertTrue(fit > 0.95, "$fit")
    }

    // -----------------------------------------------------------------------
    // Bewertung und Startpunkt
    // -----------------------------------------------------------------------

    @Test
    fun `Intervalle gehoeren an den Berg`() {
        val goal = dayRouteGoal(workout(intervals), rider)
        val len = 32_000.0
        val flat = profile(len) { 0.0 }
        // Anstiege von 2,2 km mit 5 %: bei rund 238 W etwa 8 min.
        val hilly = profile(len, ele = climbsEle(flatM = 2800.0, climbM = 2200.0, gradePct = 5.0, climbs = 5, leadM = 5200.0))
        val sFlat = bestDayStart(goal, flat)
        val sHilly = bestDayStart(goal, hilly)
        assertTrue(sHilly.score > sFlat.score + 0.2, "Berg ${sHilly.score}, flach ${sFlat.score}")
        val climbs = dayClimbs(goal, hilly, sHilly.sim)!!
        assertEquals(3, climbs.reps)
        assertEquals(8, climbs.workMinutes)
        assertTrue(climbs.count >= 3)
        assertTrue(climbs.minMinutes in 6..10 && climbs.maxMinutes in 6..10, "$climbs")
    }

    @Test
    fun `Startpunkt verschiebt die Intervalle in die Anstiege`() {
        val goal = dayRouteGoal(workout(intervals), rider)
        // 12 km flach, dann drei Anstiege — von vorn fielen die Intervalle ins Flache.
        val p = profile(40_000.0, ele = climbsEle(flatM = 2500.0, climbM = 2200.0, gradePct = 5.0, climbs = 3, leadM = 12_000.0))
        val zero = simulateDayRide(goal, p, 0.0)
        val best = bestDayStart(goal, p)
        assertTrue(best.startM > 0)
        assertEquals(best.startM, IndoorStartPoints.clamp(best.startM, p.lengthM, p.isLoop))
        assertTrue(best.score > scoreDayRoute(goal, p, zero) + DAY_START_MIN_GAIN)
        assertTrue(best.sim.intervalClimbShare.average() > 0.6, "${best.sim.intervalClimbShare}")

        // Flache Einheit auf flacher Strecke: kein Grund, spaeter zu starten.
        val easy = dayRouteGoal(workout(session(SessionIntensity.LOCKER, 40)), rider)
        assertEquals(0.0, bestDayStart(easy, profile(30_000.0) { 0.0 }).startM)
        // Geschaetztes Profil: immer vorn.
        assertEquals(0.0, bestDayStart(goal, DayRouteProfile.estimate(40_000.0, 400.0, false)!!).startM)
    }

    @Test
    fun `Grundlage meidet steile Berge, Erholung bleibt flach`() {
        val base = dayRouteGoal(workout(session(SessionIntensity.GRUNDLAGE, 60)), rider)
        val len = base.expectedFlatM
        val rolling = profile(len) { 15 * kotlin.math.sin(it / 800) }
        val steep = profile(len, ele = climbsEle(flatM = 1500.0, climbM = 2500.0, gradePct = 9.0, climbs = 6))
        assertTrue(bestDayStart(base, rolling).score > bestDayStart(base, steep).score + 0.15)

        val easy = dayRouteGoal(workout(session(SessionIntensity.LOCKER, 40)), rider)
        val flat = profile(easy.expectedFlatM + 500) { 0.0 }
        val hills = profile(easy.expectedFlatM + 500, ele = climbsEle(flatM = 800.0, climbM = 800.0, gradePct = 4.0, climbs = 10))
        assertTrue(bestDayStart(easy, flat).score > bestDayStart(easy, hills).score + 0.2)
    }

    // -----------------------------------------------------------------------
    // Reihenfolge
    // -----------------------------------------------------------------------

    @Test
    fun `bei annaehernd gleicher Passung gewinnt die Strecke ohne Bauzeit`() {
        val goal = dayRouteGoal(workout(session(SessionIntensity.LOCKER, 40)), rider)
        val len = goal.expectedFlatM
        val exact = profile(len + 200) { 0.0 }
        // Etwas laenger: wenige Prozent schlechter.
        val longer = profile(len * 1.25) { 0.0 }
        val ready = DayRouteCandidate("catalog:a", "A", DayRouteAvailability.READY, longer)
        val build = DayRouteCandidate("ride:b", "B", DayRouteAvailability.BUILD, exact)
        val ranked = rankDayRoutes(goal, listOf(build, ready))
        assertTrue(ranked[1].score > ranked[0].score, "Neubau ist knapp besser")
        assertEquals("catalog:a", ranked[0].candidate.key, "trotzdem gewinnt die fertige Welt")

        // Deutlich besser schlaegt die Bauzeit.
        val muchLonger = profile(len * 3) { 0.0 }
        val ranked2 = rankDayRoutes(goal, listOf(ready.copy(profile = muchLonger), build))
        assertEquals("ride:b", ranked2[0].candidate.key)
    }

    @Test
    fun `neue Runde gewinnt nur gegen schlecht passende Strecken`() {
        val goal = dayRouteGoal(workout(intervals), rider)
        val plan = DayRouteCandidate("plan", "Neue Runde", DayRouteAvailability.PLAN, null)
        val flat = DayRouteCandidate("ride:f", "Flach", DayRouteAvailability.BUILD, profile(32_000.0) { 0.0 })
        val noProfile = DayRouteCandidate("ride:x", "Ohne Hoehe", DayRouteAvailability.BUILD, null)
        val ranked = rankDayRoutes(goal, listOf(flat, noProfile, plan))
        assertEquals(listOf("plan", "ride:f"), ranked.map { it.candidate.key })
        assertNull(ranked[0].start)

        val hilly = DayRouteCandidate(
            "testwelt", "Berge", DayRouteAvailability.READY,
            profile(32_000.0, ele = climbsEle(flatM = 2800.0, climbM = 2200.0, gradePct = 5.0, climbs = 5, leadM = 5200.0)),
        )
        assertEquals("testwelt", rankDayRoutes(goal, listOf(plan, flat, hilly)).first().candidate.key)
    }

    // -----------------------------------------------------------------------
    // Ende
    // -----------------------------------------------------------------------

    private fun state(phase: IndoorPhase, erg: ErgState?, mode: IndoorMode = IndoorMode.ERG) = IndoorSessionState(
        phase = phase, mode = mode, nowMs = 0, elapsedS = 0, distM = 0.0, routeDistM = 0.0, lap = 1,
        speedMps = 0.0, powerW = null, cadenceRpm = null, hrBpm = null, gradePct = 0.0,
        surface = SurfaceKind.ASPHALT, crr = 0.005, headwindMps = 0.0,
        gear = gearState(0, VirtualGearTable.EVEN), ghost = null, erg = erg,
        totals = IndoorTotals(0.0, 0.0, 0, null, null, null, null, 0.0),
    )

    @Test
    fun `Einheit vor der Strecke zu Ende, Strecke vor der Einheit zu Ende`() {
        val running = ErgState(stepIndex = 1, targetW = 200, stepRemainingS = 90, done = false)
        val done = ErgState(stepIndex = 2, targetW = 110, stepRemainingS = 0, done = true)
        assertEquals(DayRideEnd.NONE, dayRideEnd(state(IndoorPhase.RUNNING, running)))
        assertEquals(DayRideEnd.WORKOUT_DONE, dayRideEnd(state(IndoorPhase.RUNNING, done)))
        assertEquals(DayRideEnd.WORKOUT_DONE, dayRideEnd(state(IndoorPhase.PAUSED, done)))
        assertEquals(DayRideEnd.ROUTE_DONE_EARLY, dayRideEnd(state(IndoorPhase.FINISHED, running)))
        assertEquals(DayRideEnd.NONE, dayRideEnd(state(IndoorPhase.FINISHED, done)))
        assertEquals(DayRideEnd.NONE, dayRideEnd(state(IndoorPhase.FINISHED, null, IndoorMode.SIM)))

        val w = ErgWorkout("t", listOf(ErgStep(ErgStepKind.WARMUP, 300, 0.5), ErgStep(ErgStepKind.STEADY, 600, 0.7), ErgStep(ErgStepKind.COOLDOWN, 300, 0.45)))
        assertEquals(90 + 300, ergRemainingS(w, running))
        assertEquals(0, ergRemainingS(w, done))
    }

    @Test
    fun `Sitzung auf einer Strecke ohne Rundschluss endet vor der Einheit`() {
        val w = workout(session(SessionIntensity.GRUNDLAGE, 30))
        val route = IndoorTestRoutes.straight(lengthM = 3000.0)
        val s = createIndoorSession(
            IndoorSessionConfig(route, IndoorMode.ERG, rider, workout = w, capabilities = TrainerCapabilities(true, true), startedAtEpochMs = 0L),
        )
        var now = 0L
        var last: IndoorSessionState? = null
        while (now < w.totalS * 1000L && last?.phase != IndoorPhase.FINISHED) {
            last = s.step(now, SessionInput(TrainerTelemetry(200, 85.0, null, now))).state
            now += 200
        }
        assertEquals(DayRideEnd.ROUTE_DONE_EARLY, dayRideEnd(last!!))
        assertTrue(ergRemainingS(w, last.erg!!) > 0)

        // Auf einer Runde endet sie nie vorzeitig, die Einheit wird fertig.
        val loopSession = createIndoorSession(
            IndoorSessionConfig(IndoorTestRoutes.loop(1000.0), IndoorMode.ERG, rider, workout = w, capabilities = TrainerCapabilities(true, true), startedAtEpochMs = 0L),
        )
        now = 0L
        var lastLoop: IndoorSessionState? = null
        while (now <= (w.totalS + 5) * 1000L) {
            lastLoop = loopSession.step(now, SessionInput(TrainerTelemetry(200, 85.0, null, now))).state
            now += 200
        }
        assertEquals(DayRideEnd.WORKOUT_DONE, dayRideEnd(lastLoop!!))
        assertTrue(lastLoop.lap > 1)
    }

    @Test
    fun `neue Runde folgt der Einheit und der Kappe der Tagesform`() {
        val goal = dayRouteGoal(workout(intervals), rider)
        val t = dayPlanTarget(goal, intervals, target(SessionIntensity.HART).copy(ascentPreference = AscentPreference.MODERAT))
        assertEquals(AscentPreference.MODERAT, t.ascentPreference, "nicht bergiger als die Tagesform erlaubt")
        assertEquals(goal.expectedFlatM / 1000, t.distanceKm, 0.06)
        assertEquals(52 / 60.0, t.durationH!!, 1e-9)
        assertEquals(SessionIntensity.HART, t.intensity)
        val easy = session(SessionIntensity.LOCKER, 40)
        val flat = dayPlanTarget(dayRouteGoal(workout(easy), rider), easy, target(SessionIntensity.LOCKER).copy(ascentPreference = AscentPreference.BERGIG))
        assertEquals(AscentPreference.FLACH, flat.ascentPreference)
    }
}
