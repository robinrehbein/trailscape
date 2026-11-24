package de.trailscape.core.indoor

import de.trailscape.core.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Startpunkt waehlen, Versetzen in der Pause, Fortsetzen (IndoorStartPoint.kt). */
class IndoorStartPointTest {
    private val rider = IndoorTestRoutes.RIDER
    private val startedAt = 1_800_000_000_000L

    /** Flach bis 1000 m, 5 % bis 2000 m, dann flach bis 5000 m. */
    private val hilly = IndoorTestRoutes.straight(lengthM = 5000.0, ele = IndoorTestRoutes.climbProfile(1000.0, 2000.0, 5.0))

    private fun config(route: IndoorRoute = hilly, ghost: GhostSpec = GhostSpec.None) = IndoorSessionConfig(
        route = route,
        mode = IndoorMode.SIM,
        rider = rider,
        ghost = ghost,
        capabilities = TrainerCapabilities(simulation = true, targetPower = true),
        startedAtEpochMs = startedAt,
    )

    private class Driver(val session: IndoorSession, var now: Long = 10_000L) {
        fun step(power: Int?, dt: Long = 100): IndoorStep {
            now += dt
            val t = TrainerTelemetry(power, if ((power ?: 0) > 0) 90.0 else 0.0, null, now)
            return session.step(now, SessionInput(t, null, true))
        }

        fun ride(seconds: Double, power: Int): List<IndoorStep> = List(Math.round(seconds * 10).toInt()) { step(power) }
    }

    // ------------------------------------------------------------ Start mittendrin

    @Test
    fun `Start mittendrin - Stelle, Steigung und Vorgabe stimmen ab dem ersten Schritt`() {
        val s = createIndoorSessionAt(config(), 1500.0)
        assertEquals(1500.0, s.state.distM)
        assertEquals(1500.0, s.state.routeDistM)
        assertEquals(5.0, s.state.gradePct, 0.01)
        assertEquals(0.0, s.state.totals.distanceM)
        assertEquals(IndoorPhase.READY, s.state.phase)

        val first = Driver(s).step(power = null, dt = 0)
        val sim = first.commands.filterIsInstance<TrainerCommand.SetSimulation>().single()
        val fromStart = Driver(createIndoorSession(config())).step(power = null, dt = 0)
            .commands.filterIsInstance<TrainerCommand.SetSimulation>().single()
        assertTrue(sim.gradePct > fromStart.gradePct + 3.0, "Vorgabe am Hang ${sim.gradePct}, am Start ${fromStart.gradePct}")
    }

    @Test
    fun `nur das Gefahrene zaehlt - Strecke, Hoehenmeter, Schnitt`() {
        val s = createIndoorSessionAt(config(), 1500.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        d.ride(120.0, 250)
        val st = s.state
        val ridden = st.distM - 1500.0
        assertTrue(ridden > 300.0, "gefahren $ridden")
        assertEquals(ridden, st.totals.distanceM, 1e-6)
        // Hoehenmeter nur ab 1500 m: hoechstens die 25 m bis 2000 m (statt 50 m ab dem Start).
        val expectedUp = (minOf(st.distM, 2000.0) - 1500.0) * 0.05
        assertEquals(expectedUp, st.totals.ascentM, 0.5)
        val record = s.finish()
        assertEquals(ridden / 1000.0, record.stats.distanceKm, 1e-6)
        assertEquals(st.totals.ascentM, record.stats.ascentM, 1e-9)
        val avg = assertNotNull(record.stats.avgSpeedKmh)
        assertEquals(ridden / 1000.0 / (record.totals.movingS / 3600.0), avg, 1e-6)
        // Der erste Punkt liegt an der Startstelle, nicht am Anfang der Strecke.
        val p0 = record.points.first()
        val at = hilly.sampleAt(1500.0)
        assertEquals(at.lat, p0.lat, 1e-9)
        assertEquals(at.eleM, p0.ele!!, 1e-9)
    }

    @Test
    fun `Strecke ohne Rundschluss - Ziel kommt nach dem Rest, Gefahrenes ist der Rest`() {
        val short = IndoorTestRoutes.straight(lengthM = 2000.0)
        val s = createIndoorSessionAt(config(short), 1700.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        var guard = 0
        while (s.state.phase != IndoorPhase.FINISHED && guard++ < 3000) d.step(300)
        assertEquals(IndoorPhase.FINISHED, s.state.phase)
        assertEquals(2000.0, s.state.distM, 1e-9)
        assertEquals(300.0, s.state.totals.distanceM, 1e-6)
        assertEquals(1, s.finish().laps)
    }

    @Test
    fun `Runde - Start in der Mitte ist Runde 1 bis zur Ziellinie`() {
        val loop = IndoorTestRoutes.loop(lengthM = 1000.0)
        val s = createIndoorSessionAt(config(loop), 600.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        assertEquals(1, s.state.lap)
        var guard = 0
        while (s.state.totals.distanceM < 300.0 && guard++ < 3000) d.step(300)
        assertEquals(1, s.state.lap)
        while (s.state.totals.distanceM < 600.0 && guard++ < 6000) d.step(300)
        assertEquals(2, s.state.lap)
        assertTrue(s.state.routeDistM in 150.0..300.0, "in Runde 2 bei ${s.state.routeDistM}")
        assertEquals(s.state.distM - 600.0, s.state.totals.distanceM, 1e-6)
        assertEquals(2, s.finish().laps)
    }

    @Test
    fun `Startstelle wird begrenzt und gerastert`() {
        val s = createIndoorSessionAt(config(IndoorTestRoutes.straight(lengthM = 2000.0)), 5000.0)
        assertEquals(1900.0, s.state.distM)
        val n = createIndoorSessionAt(config(), -20.0)
        assertEquals(0.0, n.state.distM)
        assertEquals(1230.0, IndoorStartPoints.clamp(1234.0, 5000.0, false))
        assertEquals(0.0, IndoorStartPoints.clamp(Double.NaN, 5000.0, false))
        assertEquals(990.0, IndoorStartPoints.clamp(999.0, 1000.0, true))
        assertEquals(1330.0, IndoorStartPoints.nudge(1234.0, IndoorStartPoints.NUDGE_M, 5000.0, false))
        assertEquals(0.0, IndoorStartPoints.nudge(40.0, -IndoorStartPoints.NUDGE_M, 5000.0, false))
    }

    // ------------------------------------------------------------ Versetzen

    @Test
    fun `Versetzen nur vor dem ersten Tritt und in der Pause`() {
        val s = createIndoorSession(config()) as MovableIndoorSession
        val d = Driver(s)
        d.step(power = null, dt = 0)
        assertTrue(s.canMove)
        assertTrue(s.moveTo(800.0))
        assertEquals(800.0, s.state.distM)
        d.ride(5.0, 250)
        assertEquals(IndoorPhase.RUNNING, s.state.phase)
        assertFalse(s.canMove)
        val before = s.state.distM
        assertFalse(s.moveStartTo(3000.0))
        assertEquals(before, s.state.distM)
        s.pause()
        assertTrue(s.moveStartTo(3000.0))
        assertEquals(3000.0, s.state.distM)
        s.finish()
        assertFalse(s.moveStartTo(100.0))
    }

    @Test
    fun `Versetzen in der Pause zaehlt nicht als Strecke und nicht als Hoehe`() {
        val s = createIndoorSessionAt(config(), 500.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        d.ride(60.0, 250)
        val ridden1 = s.state.totals.distanceM
        val ascent1 = s.state.totals.ascentM
        s.pause()
        d.step(0)
        assertTrue(s.moveStartTo(2500.0)) // ueber den ganzen Anstieg hinweg
        assertEquals(ridden1, s.state.totals.distanceM, 1e-9)
        assertEquals(ascent1, s.state.totals.ascentM, 1e-9)
        assertEquals(0.0, s.state.speedMps)
        s.resume()
        d.ride(30.0, 250)
        val st = s.state
        assertEquals(ridden1 + (st.distM - 2500.0), st.totals.distanceM, 1e-6)
        assertEquals(ascent1, st.totals.ascentM, 1e-6) // dort ist es flach
        val rec = s.finish()
        assertEquals(st.totals.distanceM / 1000.0, rec.stats.distanceKm, 1e-6)
    }

    @Test
    fun `Versetzen auf der Runde bleibt in der laufenden Runde`() {
        val loop = IndoorTestRoutes.loop(lengthM = 1000.0)
        val s = createIndoorSessionAt(config(loop), 900.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        var guard = 0
        while (s.state.lap < 2 && guard++ < 3000) d.step(300)
        s.pause()
        d.step(0)
        assertTrue(s.moveStartTo(500.0))
        assertEquals(2, s.state.lap)
        assertEquals(1500.0, s.state.distM, 1e-9)
        assertEquals(500.0, s.state.routeDistM, 1e-9)
    }

    // ------------------------------------------------------------ Geist

    @Test
    fun `Vergleichsleistung startet an derselben Stelle`() {
        val s = createIndoorSessionAt(config(ghost = GhostSpec.ConstantPower(200.0)), 1500.0)
        val g0 = assertNotNull(s.state.ghost)
        assertEquals(1500.0, g0.distM)
        assertEquals(0.0, g0.gapM)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        d.ride(30.0, 200)
        val g = assertNotNull(s.state.ghost)
        // Gleiche Leistung, gleiche Physik, gleiche Stelle: Kopf an Kopf.
        assertEquals(0.0, g.gapM, 1.0)
        assertTrue(g.distM > 1550.0)
    }

    @Test
    fun `fruehere Fahrt wird auf die Startstelle verschoben`() {
        val route = IndoorTestRoutes.straight(lengthM = 3000.0)
        // Frueher: konstant 5 m/s, also bei 1000 m nach 200 s.
        val past = List(601) { i ->
            val p = route.sampleAt(i * 5.0)
            TrackPoint(lat = p.lat, lon = p.lon, ele = p.eleM, time = 1_000_000L + i * 1000L)
        }
        val s = createIndoorSessionAt(config(route, GhostSpec.PastRide("frueher", past)), 1000.0)
        val g0 = assertNotNull(s.state.ghost)
        assertEquals(1000.0, g0.distM, 5.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        d.ride(60.0, 250)
        val g = assertNotNull(s.state.ghost)
        val runningS = s.state.totals.movingS
        assertEquals(1000.0 + 5.0 * runningS, g.distM, 15.0)

        // In der Pause versetzt: der Vergleich beginnt dort neu.
        s.pause()
        d.step(0)
        assertTrue(s.moveStartTo(2000.0))
        assertEquals(2000.0, assertNotNull(s.state.ghost).distM, 5.0)
    }

    @Test
    fun `fruehere Fahrt, die die Stelle nie erreichte - kein Geist`() {
        val route = IndoorTestRoutes.straight(lengthM = 3000.0)
        val past = List(101) { i ->
            val p = route.sampleAt(i * 5.0)
            TrackPoint(lat = p.lat, lon = p.lon, ele = p.eleM, time = 1_000_000L + i * 1000L)
        }
        val s = createIndoorSessionAt(config(route, GhostSpec.PastRide("frueher", past)), 2000.0)
        assertNull(s.state.ghost)
        assertNull(s.finish().ghostFinalGapS)
    }

    // ------------------------------------------------------------ Merken

    @Test
    fun `nach dem Beenden - Stelle merken oder loeschen`() {
        val now = 42L
        assertEquals(1234.0, IndoorStartPoints.afterStop(1234.0, 5000.0, false, now)?.routeDistM)
        assertNull(IndoorStartPoints.afterStop(5000.0, 5000.0, false, now), "Ziel erreicht")
        assertNull(IndoorStartPoints.afterStop(4950.0, 5000.0, false, now), "praktisch im Ziel")
        assertNull(IndoorStartPoints.afterStop(150.0, 5000.0, false, now), "kaum vom Start weg")
        // Runde: in Runde 2 bei 300 m → 300 m merken; kurz vor der Ziellinie → loeschen.
        assertEquals(300.0, IndoorStartPoints.afterStop(1300.0, 1000.0, true, now)!!.routeDistM, 1e-9)
        assertNull(IndoorStartPoints.afterStop(1900.0, 1000.0, true, now))
        assertNull(IndoorStartPoints.afterStop(Double.NaN, 1000.0, true, now))
    }

    @Test
    fun `aus dem Zustand der Sitzung - Ziel loescht, Abbruch merkt`() {
        val short = IndoorTestRoutes.straight(lengthM = 2000.0)
        val s = createIndoorSessionAt(config(short), 1500.0)
        val d = Driver(s)
        d.step(power = null, dt = 0)
        d.ride(20.0, 250)
        val mid = IndoorStartPoints.afterStop(s.state, short, 7L)
        assertEquals(s.state.routeDistM, assertNotNull(mid).routeDistM, 1e-9)
        assertEquals(2000.0, mid.routeLengthM)
        var guard = 0
        while (s.state.phase != IndoorPhase.FINISHED && guard++ < 3000) d.step(300)
        s.finish()
        assertNull(IndoorStartPoints.afterStop(s.state, short, 7L))
    }

    @Test
    fun `Angebot - passt nur zur gleich langen Strecke, kleine Abweichung anteilig`() {
        val p = IndoorResumePoint(routeDistM = 2500.0, routeLengthM = 5000.0, savedAtEpochMs = 1L)
        assertEquals(2500.0, IndoorStartPoints.offer(p, 5000.0, false))
        assertEquals(2550.0, IndoorStartPoints.offer(p, 5100.0, false)) // 2 % laenger
        assertNull(IndoorStartPoints.offer(p, 6000.0, false)) // andere Strecke
        assertNull(IndoorStartPoints.offer(null, 5000.0, false))
        val nearStart = IndoorResumePoint(routeDistM = 200.0, routeLengthM = 5000.0, savedAtEpochMs = 1L)
        assertNull(IndoorStartPoints.offer(nearStart, 4800.0, false)) // anteilig 192 m, unter 200 m
    }

    @Test
    fun `gemerkte Stelle als JSON hin und zurueck, Kaputtes ergibt null`() {
        val p = IndoorResumePoint(routeDistM = 1234.5, routeLengthM = 5000.0, savedAtEpochMs = 1_800_000_000_123L)
        assertEquals(p, IndoorResumePoint.fromJson(p.toJson()))
        assertNull(IndoorResumePoint.fromJson(null))
        assertNull(IndoorResumePoint.fromJson(""))
        assertNull(IndoorResumePoint.fromJson("{kaputt"))
        assertNull(IndoorResumePoint.fromJson("[1,2]"))
        assertNull(IndoorResumePoint.fromJson("""{"routeDistM":-1,"routeLengthM":5000}"""))
        assertNull(IndoorResumePoint.fromJson("""{"routeDistM":"x","routeLengthM":5000}"""))
    }
}
