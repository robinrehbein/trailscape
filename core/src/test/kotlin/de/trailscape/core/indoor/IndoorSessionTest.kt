package de.trailscape.core.indoor

import de.trailscape.core.computeStats
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Die Sitzung Sekunde fuer Sekunde: Phasen, Befehle, Physik, ERG, Runden, Ziel, Aufzeichnung, Geist. */
class IndoorSessionTest {
    private val rider = IndoorTestRoutes.RIDER
    private val startedAt = 1_800_000_000_000L
    private val flat = IndoorTestRoutes.straight(lengthM = 5000.0)

    private fun config(
        route: IndoorRoute = flat,
        mode: IndoorMode = IndoorMode.SIM,
        workout: ErgWorkout? = null,
        ghost: GhostSpec = GhostSpec.None,
        capabilities: TrainerCapabilities = TrainerCapabilities(simulation = true, targetPower = true),
        wind: IndoorWind = IndoorWind.CALM,
        r: RiderSetup = rider,
    ) = IndoorSessionConfig(route, mode, r, wind, workout, ghost, capabilities, startedAt)

    /** Treibt eine Sitzung mit 10 Hz (oder anderem Takt) und frischen Messwerten. */
    private class Driver(val session: IndoorSession, var now: Long = 10_000L) {
        val sent = ArrayList<Pair<Long, TrainerCommand>>()

        fun step(
            power: Int? = 0,
            cadence: Double? = if ((power ?: 0) > 0) 90.0 else 0.0,
            dt: Long = 100,
            ready: Boolean = true,
            hr: Int? = null,
            telemetry: TrainerTelemetry? = null,
            noTelemetry: Boolean = false,
        ): IndoorStep {
            now += dt
            val t = if (noTelemetry) null else telemetry ?: TrainerTelemetry(power, cadence, null, now)
            val s = session.step(now, SessionInput(t, hr, ready))
            s.commands.forEach { sent += now to it }
            return s
        }

        /** [seconds] Sekunden mit 10 Hz; liefert alle Schritte. */
        fun ride(seconds: Double, power: Int?, ready: Boolean = true, hr: Int? = null): List<IndoorStep> =
            List(Math.round(seconds * 10).toInt()) { step(power, ready = ready, hr = hr) }

        fun commandsBetween(from: Long, to: Long) = sent.filter { it.first in from..to }.map { it.second }
    }

    private fun startedDriver(c: IndoorSessionConfig = config()): Driver {
        val d = Driver(IndoorSessionImpl(c))
        d.step(noTelemetry = true, dt = 0)
        return d
    }

    // ------------------------------------------------------------------ Start

    @Test
    fun `vor dem ersten Schritt bereit im Startgang`() {
        val s = IndoorSessionImpl(config())
        val st = s.state
        assertEquals(IndoorPhase.READY, st.phase)
        assertEquals(IndoorMode.SIM, st.mode)
        assertEquals(0.0, st.distM)
        assertEquals(1, st.lap)
        assertEquals(16, st.gear.index)
        assertEquals(24, st.gear.count)
        assertNull(st.erg)
        assertNull(st.ghost)
        assertNull(st.powerW)
        assertEquals(SurfaceKind.UNKNOWN, st.surface)
        assertEquals(0.008, st.crr, 1e-12)
        assertNull(st.totals.avgPowerW)
        assertNull(st.totals.normalizedPowerW)
    }

    @Test
    fun `erster Schritt liefert Start und die erste Vorgabe`() {
        val climb = IndoorTestRoutes.straight(lengthM = 2000.0, ele = IndoorTestRoutes.climbProfile(0.0, 2000.0, 4.0))
        val d = Driver(IndoorSessionImpl(config(route = climb)))
        val first = d.step(noTelemetry = true)
        val expectedSim = simulationCommand(4.0, 0.008, aeroCoefficient(0.38, 0.0), 0.0, VIRTUAL_GEAR_RATIOS[15], rider)
        assertEquals(listOf(TrainerCommand.Start, expectedSim), first.commands)
        assertEquals(IndoorPhase.READY, first.state.phase)
        assertEquals(4.0, first.state.gradePct, 1e-9)
        assertNull(first.state.powerW)
        // Ohne Tritt passiert nichts.
        val idle = d.ride(5.0, 0)
        assertTrue(idle.all { it.state.phase == IndoorPhase.READY && it.commands.isEmpty() })
        assertEquals(0, idle.last().state.elapsedS)
        assertEquals(0.0, idle.last().state.distM)
        assertEquals(0, d.session.finish().points.size)
    }

    @Test
    fun `erster Tritt startet Uhr, Physik und Aufzeichnung`() {
        val d = startedDriver()
        val steps = d.ride(60.0, 200)
        assertEquals(IndoorPhase.RUNNING, steps.first().state.phase)
        val st = steps.last().state
        assertEquals(60, st.elapsedS)
        assertEquals(60, st.totals.movingS)
        assertEquals(200, st.powerW)
        assertEquals(90, st.cadenceRpm)
        // Dieselbe Physik von Hand, mit demselben Takt.
        var v = 0.0
        var dist = 0.0
        repeat(600) {
            val s = advancePhysics(v, 200.0, 0.0, 0.008, 0.0, 0.0, rider, 0.1)
            v = s.speedMps
            dist += s.distanceM
        }
        assertEquals(dist, st.distM, 1e-9)
        assertEquals(v, st.speedMps, 1e-12)
        assertEquals(dist, st.totals.distanceM, 1e-9)
        assertEquals(12.0, st.totals.workKj, 1e-9)
        assertEquals(200.0, st.totals.avgPowerW!!, 1e-9)
        assertEquals(200.0, st.totals.normalizedPowerW!!, 1e-9)
        assertEquals(90.0, st.totals.avgCadenceRpm!!, 1e-9)
    }

    // ------------------------------------------------------------ Aufzeichnung

    @Test
    fun `ein Punkt je Sekunde mit Wanduhrzeit und Sekundenmittel der Leistung`() {
        val d = startedDriver()
        val t0 = d.now
        // Je Sekunde: 5 Schritte 100 W, 5 Schritte 300 W → Mittel 200 W.
        repeat(30) {
            repeat(5) { d.step(100, hr = 140) }
            repeat(5) { d.step(300, hr = 150) }
        }
        val rec = d.session.finish()
        assertEquals(30, rec.points.size)
        rec.points.forEachIndexed { i, p ->
            assertEquals(startedAt + (i + 1) * 1000L, p.time)
            assertEquals(200, p.power)
            assertEquals(90, p.cad)
            assertEquals(150, p.hr)
            assertEquals(IndoorTestRoutes.LON0, p.lon, 1e-9) // die Strecke laeuft genau nach Norden
        }
        assertEquals(t0 + 30_000L, d.now)
        // Lage = virtuelle Stelle: Punkte liegen auf der Strecke, Abstand steigt.
        for (i in 1 until rec.points.size) assertTrue(rec.points[i].lat > rec.points[i - 1].lat)
        // Stats exakt aus den Punkten, Puls ergaenzt.
        val base = computeStats(rec.points)
        assertEquals(base.distanceKm, rec.stats.distanceKm)
        assertEquals(29, rec.stats.durationS)
        assertEquals(150, rec.stats.avgHrBpm)
        assertEquals(150, rec.stats.maxHrBpm)
        assertEquals(rec.totals, d.session.state.totals)
        assertEquals(145.0, rec.totals.avgHrBpm!!, 1e-9)
        assertEquals(1, rec.laps)
        assertEquals(flat.id, rec.routeId)
        assertEquals(IndoorMode.SIM, rec.mode)
        assertEquals(startedAt, rec.startedAtEpochMs)
        assertNull(rec.ghostFinalGapS)
    }

    @Test
    fun `lange Luecke zaehlt hoechstens 2 s, Punkte bekommen Wanduhrzeit`() {
        val d = startedDriver()
        val t0 = d.now
        d.ride(1.0, 200)
        val before = d.session.state
        val jump = d.step(200, dt = 10_000)
        assertEquals(before.elapsedS + 2, jump.state.elapsedS)
        assertEquals(before.totals.movingS + 2, jump.state.totals.movingS)
        val rec = d.session.finish()
        assertEquals(3, rec.points.size)
        val wall = startedAt + (d.now - t0)
        assertEquals(listOf(startedAt + 1000, wall - 1000, wall), rec.points.map { it.time })
        // Zwei Punkte aus einem Schritt: Lage linear dazwischen.
        val p1 = rec.points[1].lat - rec.points[0].lat
        val p2 = rec.points[2].lat - rec.points[1].lat
        assertEquals(p1, p2, p1 * 0.5)
    }

    @Test
    fun `nicht steigendes nowMs aendert nichts`() {
        val d = startedDriver()
        d.ride(3.0, 200)
        val st = d.session.state
        val same = d.session.step(d.now, SessionInput(TrainerTelemetry(500, 90.0, null, d.now)))
        assertSame(st, same.state)
        assertTrue(same.commands.isEmpty())
        val back = d.session.step(d.now - 500, SessionInput(TrainerTelemetry(500, 90.0, null, d.now)))
        assertSame(st, back.state)
        d.session.shift(ShifterEvent.UP)
        assertTrue(d.session.step(d.now - 1, SessionInput(null)).commands.isEmpty()) // Schaltwunsch wartet
        assertEquals(17, d.step(200).state.gear.index)
    }

    // -------------------------------------------------------------- Messwerte

    @Test
    fun `alte Messwerte zaehlen als 0 W, Anzeige als 3-s-Mittel`() {
        val d = startedDriver()
        repeat(30) { d.step(100) }
        assertEquals(100, d.session.state.powerW)
        repeat(10) { d.step(400) }
        assertEquals(200, d.session.state.powerW) // (2 s · 100 + 1 s · 400) / 3
        // Ein Paket bleibt stehen: 3 s gilt es noch, danach 0 W.
        val stale = TrainerTelemetry(400, 90.0, null, d.now)
        repeat(30) { d.step(telemetry = stale) }
        assertEquals(400, d.session.state.powerW)
        val after = d.step(telemetry = stale)
        assertTrue(after.state.powerW!! < 400)
        assertNull(after.state.cadenceRpm)
        repeat(29) { d.step(telemetry = stale) }
        assertEquals(0, d.session.state.powerW)
    }

    @Test
    fun `aufgeteilte Pakete - null heisst nicht 0 W`() {
        val d = startedDriver()
        // Abwechselnd ein Paket nur mit Leistung und eines nur mit Kadenz.
        repeat(100) { i ->
            val t = if (i % 2 == 0) TrainerTelemetry(250, null, 30.0, d.now + 100) else TrainerTelemetry(null, 85.0, null, d.now + 100)
            val s = d.step(telemetry = t)
            if (i > 0) {
                assertEquals(250, s.state.powerW, "Schritt $i")
                assertEquals(85, s.state.cadenceRpm, "Schritt $i")
            }
        }
        assertEquals(250.0, d.session.state.totals.avgPowerW!!, 1e-9)
        assertEquals(85.0, d.session.state.totals.avgCadenceRpm!!, 1e-9)
        // Kommt nur noch Kadenz, verfaellt die Leistung nach 3 s je Feld.
        repeat(30) { d.step(telemetry = TrainerTelemetry(null, 85.0, null, d.now + 100)) }
        assertEquals(85, d.session.state.cadenceRpm)
        assertTrue(d.session.state.powerW!! > 0)
        repeat(30) { d.step(telemetry = TrainerTelemetry(null, 85.0, null, d.now + 100)) }
        assertEquals(0, d.session.state.powerW)
        assertEquals(85, d.session.state.cadenceRpm)
    }

    @Test
    fun `Trainer ohne Leistungswert zeigt keine Leistung`() {
        val d = startedDriver()
        val s = d.step(power = null, cadence = 80.0)
        assertNull(s.state.powerW)
        assertEquals(IndoorPhase.RUNNING, s.state.phase) // Kadenz zaehlt als Tritt
        d.ride(2.0, null)
        assertNull(d.session.state.totals.avgPowerW)
        assertTrue(d.session.finish().points.all { it.power == null })
    }

    // -------------------------------------------------------------- Auto-Pause

    @Test
    fun `Auto-Pause ohne Leistung und Tempo nach genau 3 s`() {
        val d = startedDriver()
        d.step(30) // ein kurzer Tritt, kaum Tempo
        assertEquals(IndoorPhase.RUNNING, d.session.state.phase)
        val steps = List(30) { d.step(0, cadence = 0.0) }
        assertTrue(steps.take(29).all { it.state.phase == IndoorPhase.RUNNING })
        val paused = steps.last().state
        assertEquals(IndoorPhase.AUTO_PAUSED, paused.phase)
        assertEquals(0.0, paused.speedMps)
        assertTrue(steps.all { it.commands.isEmpty() })
        // In der Auto-Pause: Uhr laeuft, Fahrzeit, Weg und Punkte stehen.
        d.ride(10.0, 0)
        val later = d.session.state
        assertEquals(paused.distM, later.distM)
        assertEquals(paused.totals.movingS, later.totals.movingS)
        assertEquals(paused.elapsedS + 10, later.elapsedS)
        // Erster Tritt: weiter.
        assertEquals(IndoorPhase.RUNNING, d.step(150).state.phase)
    }

    @Test
    fun `bergab ohne Leistung rollt man weiter statt anzuhalten`() {
        val down = IndoorTestRoutes.straight(lengthM = 3000.0, ele = { d -> 300.0 - d * 0.06 })
        val d = startedDriver(config(route = down))
        d.ride(5.0, 200)
        val coast = d.ride(30.0, 0)
        assertTrue(coast.all { it.state.phase == IndoorPhase.RUNNING })
        assertTrue(coast.last().state.speedMps > coast.first().state.speedMps)
    }

    @Test
    fun `Auto-Pause wenn der Trainer laenger als 5 s nicht bereit ist`() {
        val d = startedDriver()
        d.ride(5.0, 200)
        val steps = List(51) { d.step(200, ready = false) }
        assertTrue(steps.take(50).all { it.state.phase == IndoorPhase.RUNNING })
        assertEquals(IndoorPhase.AUTO_PAUSED, steps.last().state.phase)
        // Tritt ohne bereiten Trainer reicht nicht.
        assertEquals(IndoorPhase.AUTO_PAUSED, d.step(200, ready = false).state.phase)
        assertEquals(IndoorPhase.RUNNING, d.step(200, ready = true).state.phase)
    }

    // ---------------------------------------------------------- Pause/Resume

    @Test
    fun `pause und resume liefern Pause bzw Start mit Vorgabe`() {
        val d = startedDriver()
        d.ride(5.0, 200)
        d.session.pause()
        val p = d.step(200)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Pause), p.commands)
        assertEquals(IndoorPhase.PAUSED, p.state.phase)
        assertEquals(0.0, p.state.speedMps)
        val dist = p.state.distM
        // Waehrend der Pause: kein Weg, keine Vorgaben (auch nicht nach dem Schalten), Tritt weckt nicht.
        d.session.shift(ShifterEvent.UP)
        val during = d.ride(5.0, 200)
        assertTrue(during.all { it.commands.isEmpty() && it.state.phase == IndoorPhase.PAUSED })
        assertEquals(dist, d.session.state.distM)
        assertEquals(17, d.session.state.gear.index)
        d.session.pause() // doppelt: ohne Wirkung
        assertTrue(d.step(200).commands.isEmpty())
        d.session.resume()
        val r = d.step(200)
        assertEquals(2, r.commands.size)
        assertEquals(TrainerCommand.Start, r.commands[0])
        assertEquals(simulationCommand(0.0, 0.008, aeroCoefficient(0.38, 0.0), 0.0, VIRTUAL_GEAR_RATIOS[16], rider), r.commands[1])
        assertEquals(IndoorPhase.RUNNING, r.state.phase)
        d.session.resume() // nicht pausiert: ohne Wirkung
        assertTrue(d.step(200).commands.isEmpty())
    }

    @Test
    fun `Pause vor dem ersten Tritt kehrt nach READY zurueck`() {
        val d = startedDriver()
        d.session.pause()
        assertEquals(IndoorPhase.PAUSED, d.step(0).state.phase)
        d.session.resume()
        assertEquals(IndoorPhase.READY, d.step(0).state.phase)
        assertEquals(0, d.session.state.elapsedS)
    }

    // ---------------------------------------------------------------- SIM

    @Test
    fun `SIM-Vorgaben sparsam - flach keine, bei Steigungswechsel hoechstens 1 je Sekunde`() {
        val d = startedDriver()
        d.ride(60.0, 200)
        assertEquals(1, d.sent.count { it.second is TrainerCommand.SetSimulation }) // nur die erste
        // Wellige Strecke: Steigung aendert sich laufend.
        val wavy = IndoorTestRoutes.straight(lengthM = 5000.0, ele = { x -> 5 * kotlin.math.sin(x / 50.0) })
        val w = startedDriver(config(route = wavy))
        w.ride(120.0, 250)
        val sims = w.sent.filter { it.second is TrainerCommand.SetSimulation }
        assertTrue(sims.size > 20, "${sims.size}")
        for (i in 1 until sims.size) assertTrue(sims[i].first - sims[i - 1].first >= 1000)
        // Jede neue Vorgabe weicht merklich von der vorigen ab.
        for (i in 1 until sims.size) {
            val a = sims[i].second as TrainerCommand.SetSimulation
            val b = sims[i - 1].second as TrainerCommand.SetSimulation
            assertTrue(kotlin.math.abs(a.gradePct - b.gradePct) >= 0.2 - 1e-9 || kotlin.math.abs(a.cwKgPerM - b.cwKgPerM) > 0.01)
        }
    }

    @Test
    fun `Steigungsabschnitt und Belagswechsel`() {
        val surfaces = listOf(
            SurfaceSection(0.0, 300.0, SurfaceKind.ASPHALT, "residential"),
            SurfaceSection(300.0, 3000.0, SurfaceKind.GRAVEL, "track"),
        )
        val route = IndoorTestRoutes.straight(lengthM = 3000.0, surfaces = surfaces, ele = IndoorTestRoutes.climbProfile(300.0, 3000.0, 6.0))
        val d = startedDriver(config(route = route))
        val steps = d.ride(120.0, 250)
        val onGravel = steps.first { it.state.distM > 300.0 }
        assertEquals(SurfaceKind.GRAVEL, onGravel.state.surface)
        assertEquals(0.012, onGravel.state.crr, 1e-12)
        assertEquals(6.0, onGravel.state.gradePct, 1e-9)
        val sims = d.sent.map { it.second }.filterIsInstance<TrainerCommand.SetSimulation>()
        assertEquals(2, sims.size)
        assertEquals(simulationCommand(6.0, 0.012, sims[1].cwKgPerM, 0.0, VIRTUAL_GEAR_RATIOS[15], rider).gradePct, sims[1].gradePct, 1e-9)
        assertTrue(steps.last().state.totals.ascentM > 5.0)
        // Hoehenmeter = gefahrene Hoehe der virtuellen Stelle.
        assertEquals((steps.last().state.distM - 300.0) * 0.06, steps.last().state.totals.ascentM, 1e-6)
    }

    @Test
    fun `Schalten wirkt sofort im naechsten Schritt und klemmt`() {
        val d = startedDriver()
        d.ride(2.0, 200)
        d.session.shift(ShifterEvent.UP)
        val s = d.step(200)
        assertEquals(17, s.state.gear.index)
        assertEquals(VIRTUAL_GEAR_RATIOS[16], s.state.gear.ratio)
        assertEquals(1, s.commands.size)
        val sim = s.commands[0] as TrainerCommand.SetSimulation
        assertEquals(simulationCommand(0.0, 0.008, aeroCoefficient(0.38, 0.0), 0.0, VIRTUAL_GEAR_RATIOS[16], rider), sim)
        // Gleich danach (100 ms spaeter) noch einmal: wieder sofort.
        d.session.shift(ShifterEvent.UP)
        d.session.shift(ShifterEvent.UP)
        val s2 = d.step(200)
        assertEquals(19, s2.state.gear.index)
        assertEquals(1, s2.commands.size)
        repeat(20) { d.session.shift(ShifterEvent.UP) }
        assertEquals(24, d.step(200).state.gear.index)
        repeat(40) { d.session.shift(ShifterEvent.DOWN) }
        assertEquals(1, d.step(200).state.gear.index)
        // Der Gang aendert das Tempo nicht (das rechnet die Leistung).
    }

    @Test
    fun `Gegenwind steht im Zustand und in der Vorgabe`() {
        val d = startedDriver(config(wind = IndoorWind(5.0, 0.0))) // aus Nord, Strecke nach Nord
        val s = d.step(200)
        assertEquals(5.0, s.state.headwindMps, 1e-9)
        val first = d.sent.first { it.second is TrainerCommand.SetSimulation }.second as TrainerCommand.SetSimulation
        assertEquals(5.0 / (VIRTUAL_GEAR_RATIOS[15] / RiderSetup.DEFAULT_REAL_GEAR_RATIO), first.windMps, 1e-9)
    }

    // ---------------------------------------------------------------- ERG

    private val workout = ErgWorkout(
        "Test",
        listOf(
            ErgStep(ErgStepKind.WARMUP, 60, 0.5),
            ErgStep(ErgStepKind.WORK, 120, 1.0),
            ErgStep(ErgStepKind.COOLDOWN, 60, 0.45),
        ),
    )

    @Test
    fun `ERG-Abschnitte nach Fahrzeit`() {
        val d = Driver(IndoorSessionImpl(config(mode = IndoorMode.ERG, workout = workout)))
        val first = d.step(noTelemetry = true)
        assertEquals(listOf(TrainerCommand.Start, TrainerCommand.SetTargetPower(125)), first.commands)
        assertEquals(ErgState(0, 125, 60, false), first.state.erg)
        assertEquals(IndoorMode.ERG, first.state.mode)
        // Warten zaehlt nicht.
        d.ride(10.0, 0)
        assertEquals(60, d.session.state.erg!!.stepRemainingS)
        val warm = d.ride(59.9, 125)
        assertTrue(warm.all { it.commands.isEmpty() })
        assertEquals(ErgState(0, 125, 1, false), warm.last().state.erg)
        val change = d.step(125)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(250)), change.commands)
        assertEquals(ErgState(1, 250, 120, false), change.state.erg)
        // Pause haelt die ERG-Uhr an.
        d.session.pause()
        d.ride(30.0, 0)
        d.session.resume()
        val resumed = d.step(250)
        assertEquals(listOf(TrainerCommand.Start, TrainerCommand.SetTargetPower(250)), resumed.commands)
        assertEquals(120, resumed.state.erg!!.stepRemainingS)
        d.ride(119.9, 250)
        assertEquals(TrainerCommand.SetTargetPower(113), d.sent.last().second) // 0,45 · 250 = 112,5
        d.ride(60.0, 113)
        val done = d.session.state.erg!!
        assertEquals(ErgState(2, 113, 0, true), done)
        val sentBefore = d.sent.size
        d.ride(30.0, 113)
        assertEquals(sentBefore, d.sent.size) // nach dem Ende bleibt das letzte Ziel
        // Schalten ist im ERG ohne Wirkung.
        d.session.shift(ShifterEvent.UP)
        val s = d.step(113)
        assertTrue(s.commands.isEmpty())
        assertEquals(16, s.state.gear.index)
        assertEquals(IndoorMode.ERG, d.session.finish().mode)
    }

    @Test
    fun `ERG-Ziel geklemmt auf die Grenzen des Trainers`() {
        val caps = TrainerCapabilities(simulation = true, targetPower = true, minPowerW = 150, maxPowerW = 200)
        val d = Driver(IndoorSessionImpl(config(mode = IndoorMode.ERG, workout = workout, capabilities = caps)))
        assertEquals(TrainerCommand.SetTargetPower(150), d.step(noTelemetry = true).commands[1])
        d.ride(60.0, 150)
        assertEquals(TrainerCommand.SetTargetPower(200), d.sent.last().second)
        assertEquals(200, d.session.state.erg!!.targetW)
    }

    @Test
    fun `adjustErg verschiebt um Prozentpunkte der FTP und klemmt`() {
        val d = Driver(IndoorSessionImpl(config(mode = IndoorMode.ERG, workout = workout)))
        d.step(noTelemetry = true)
        d.session.adjustErg(10)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(150)), d.step(0).commands) // 250 · (0,5 + 0,1)
        d.session.adjustErg(50)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(200)), d.step(0).commands) // +30 → 0,8
        d.session.adjustErg(-100)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(50)), d.step(0).commands) // −30 → 0,2
        d.session.adjustErg(-5)
        assertTrue(d.step(0).commands.isEmpty()) // schon am Anschlag: nichts Neues
        d.session.adjustErg(Int.MIN_VALUE)
        assertTrue(d.step(0).commands.isEmpty())
        d.session.adjustErg(30)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(125)), d.step(0).commands)
    }

    @Test
    fun `ERG ohne Einheit faehrt wie SIM`() {
        val d = Driver(IndoorSessionImpl(config(mode = IndoorMode.ERG, workout = null)))
        val first = d.step(noTelemetry = true)
        assertEquals(IndoorMode.SIM, first.state.mode)
        assertTrue(first.commands[1] is TrainerCommand.SetSimulation)
        assertNull(first.state.erg)
        val empty = Driver(IndoorSessionImpl(config(mode = IndoorMode.ERG, workout = ErgWorkout("leer", emptyList()))))
        assertEquals(IndoorMode.SIM, empty.step(noTelemetry = true).state.mode)
    }

    // ----------------------------------------------------- Runden und Ziel

    @Test
    fun `Runden auf einer Rundstrecke`() {
        val loop = IndoorTestRoutes.loop(lengthM = 300.0)
        val d = startedDriver(config(route = loop))
        val steps = d.ride(120.0, 300)
        val st = steps.last().state
        assertTrue(st.distM > 600.0, "${st.distM}")
        assertEquals((st.distM / 300.0).toInt() + 1, st.lap)
        assertEquals(st.distM % 300.0, st.routeDistM, 1e-6)
        assertEquals(IndoorPhase.RUNNING, st.phase)
        // Laufmeter steigen ueber die Runden fortlaufend.
        for (i in 1 until steps.size) assertTrue(steps[i].state.distM >= steps[i - 1].state.distM)
        // Hoehenmeter: je Runde der Anstieg der Sinuswelle (10 m), auch ueber das Ziel hinweg.
        val laps = st.distM / 300.0
        assertTrue(st.totals.ascentM in (laps - 1) * 10.0..(laps + 1) * 10.0, "${st.totals.ascentM}")
        assertEquals(st.lap, d.session.finish().laps)
    }

    @Test
    fun `Ziel einer Strecke ohne Rundschluss beendet mit einmal Pause, finish liefert einmal Stop`() {
        val short = IndoorTestRoutes.straight(lengthM = 300.0)
        val d = startedDriver(config(route = short))
        var goal: IndoorStep? = null
        var n = 0
        while (goal == null && n < 2000) {
            val s = d.step(300)
            if (s.state.phase == IndoorPhase.FINISHED) goal = s
            n++
        }
        assertNotNull(goal)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Pause), goal.commands)
        assertEquals(300.0, goal.state.distM)
        val after = d.ride(5.0, 300)
        assertTrue(after.all { it.commands.isEmpty() && it.state === goal.state })
        d.session.pause()
        d.session.shift(ShifterEvent.UP)
        assertTrue(d.step(300).commands.isEmpty())
        val rec = d.session.finish()
        assertSame(rec, d.session.finish())
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Stop), d.step(300).commands)
        assertTrue(d.step(300).commands.isEmpty())
        assertEquals(rec.totals.movingS, rec.points.size)
        assertEquals(1, rec.laps)
    }

    @Test
    fun `finish mitten in der Fahrt - endgueltig, Stop genau einmal`() {
        val d = startedDriver()
        d.ride(10.5, 200)
        val rec = d.session.finish()
        assertEquals(IndoorPhase.FINISHED, d.session.state.phase)
        assertEquals(10, rec.points.size)
        assertSame(rec, d.session.finish())
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Stop), d.step(200).commands)
        val frozen = d.session.state
        repeat(20) { assertTrue(d.step(200).commands.isEmpty()) }
        assertSame(frozen, d.session.state)
        d.session.resume()
        assertTrue(d.step(200).commands.isEmpty())
        assertSame(rec, d.session.finish())
    }

    @Test
    fun `Normalleistung erst ab 30 s und nach der vierten Potenz`() {
        val d = startedDriver()
        d.ride(29.0, 200)
        assertNull(d.session.state.totals.normalizedPowerW)
        d.ride(1.0, 200)
        assertEquals(200.0, d.session.state.totals.normalizedPowerW!!, 1e-9)
        // 30 s 100 W + 30 s 300 W: Rollmittel 100…300 linear → NP > Mittel.
        val e = startedDriver()
        e.ride(30.0, 100)
        e.ride(30.0, 300)
        val rolling = (0..30).map { k -> (100.0 * (30 - k) + 300.0 * k) / 30 }
        val np = (rolling.sumOf { it.pow(4) } / rolling.size).pow(0.25)
        assertEquals(np, e.session.state.totals.normalizedPowerW!!, 1e-6)
        assertEquals(200.0, e.session.state.totals.avgPowerW!!, 1e-9)
    }

    // --------------------------------------------------------------- Geist

    @Test
    fun `Geist mit gleicher Leistung bleibt gleichauf, staerkerer zieht davon`() {
        val same = startedDriver(config(ghost = GhostSpec.ConstantPower(200.0)))
        same.ride(60.0, 200)
        assertEquals(0.0, same.session.state.ghost!!.gapM, 1e-9)
        val stronger = startedDriver(config(ghost = GhostSpec.ConstantPower(250.0)))
        stronger.ride(60.0, 200)
        val g = stronger.session.state.ghost!!
        val st = stronger.session.state
        assertTrue(g.gapM > 10.0, "${g.gapM}")
        assertEquals(g.gapM / st.speedMps, g.gapS!!, 1e-9)
        val weaker = startedDriver(config(ghost = GhostSpec.ConstantPower(150.0)))
        weaker.ride(60.0, 200)
        val w = weaker.session.state.ghost!!
        assertTrue(w.gapM < -10.0)
        assertEquals(w.gapM / w.speedMps, w.gapS!!, 1e-9)
        assertEquals(w.gapS, weaker.session.finish().ghostFinalGapS)
    }

    @Test
    fun `Geist steht in Pausen und vor dem Start`() {
        val d = startedDriver(config(ghost = GhostSpec.ConstantPower(250.0)))
        assertEquals(0.0, d.session.state.ghost!!.distM)
        d.ride(5.0, 0)
        assertEquals(0.0, d.session.state.ghost!!.distM)
        assertNull(d.session.state.ghost!!.gapS) // beide stehen
        d.ride(10.0, 200)
        val before = d.session.state.ghost!!.distM
        d.session.pause()
        d.ride(10.0, 200)
        assertEquals(before, d.session.state.ghost!!.distM)
    }

    @Test
    fun `fruehere Fahrt als Geist nach der Fahrzeit`() {
        val past = IndoorTestRoutes.pastRide(flat, 600) { s -> 6.0 * s }
        val d = startedDriver(config(ghost = GhostSpec.PastRide("12.10.2026", past)))
        d.ride(3.0, 0) // Warten zaehlt nicht
        d.ride(60.0, 200)
        val g = d.session.state.ghost!!
        assertEquals(360.0, g.distM, 0.5)
        assertEquals(6.0, g.speedMps, 0.01)
        assertEquals(g.distM - d.session.state.distM, g.gapM, 1e-9)
        // Unbrauchbare fruehere Fahrt: kein Geist.
        val none = startedDriver(config(ghost = GhostSpec.PastRide("x", past.take(1))))
        assertNull(none.step(200).state.ghost)
    }

    @Test
    fun `beide im Ziel - Endabstand ist die Differenz der Ankunftszeiten`() {
        val short = IndoorTestRoutes.straight(lengthM = 300.0)
        val d = startedDriver(config(route = short, ghost = GhostSpec.ConstantPower(400.0)))
        var ghostArrivalS: Double? = null
        var riderArrivalS: Double? = null
        var t = 0
        while (riderArrivalS == null && t < 3000) {
            val s = d.step(200)
            t++
            if (ghostArrivalS == null && s.state.ghost!!.distM >= 300.0) ghostArrivalS = s.state.totals.movingS.toDouble()
            if (s.state.phase == IndoorPhase.FINISHED) riderArrivalS = t / 10.0
        }
        assertNotNull(riderArrivalS)
        val gap = d.session.state.ghost!!
        assertEquals(0.0, gap.gapM)
        assertTrue(gap.gapS!! > 5.0, "${gap.gapS}")
        assertEquals(gap.gapS, d.session.finish().ghostFinalGapS)
        assertTrue(ghostArrivalS!! < riderArrivalS)
        assertEquals((riderArrivalS - ghostArrivalS), gap.gapS!!, 1.0)
        assertEquals(200, (d.session.finish().totals.avgPowerW!!).roundToInt())
    }
}
