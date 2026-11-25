package de.trailscape.core.indoor

import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * „Draussen gefahren → drinnen nachfahren": Leistungsverlauf der Aussenfahrt,
 * Vergleich auf demselben Abschnitt und das Ergebnis „x s vor/hinter".
 */
class IndoorGhostSectionTest {
    private val rider = IndoorTestRoutes.RIDER
    private val flat = IndoorTestRoutes.straight(lengthM = 5000.0)

    private fun config(route: IndoorRoute = flat, ghost: GhostSpec) =
        IndoorSessionConfig(
            route, IndoorMode.SIM, rider, IndoorWind.CALM, null, ghost,
            TrainerCapabilities(simulation = true, targetPower = true), 1_800_000_000_000L,
        )

    /** Fruehere Fahrt mit [mps] m/s und Leistung je Sekunde aus [powerAt]. */
    private fun pastWithPower(route: IndoorRoute, seconds: Int, mps: Double, powerAt: (Int) -> Int?): List<TrackPoint> =
        IndoorTestRoutes.pastRide(route, seconds) { s -> mps * s }
            .mapIndexed { i, p -> if (i == 0) p else p.copy(power = powerAt(i)) }

    private class Driver(val session: IndoorSession, var now: Long = 10_000L) {
        fun step(power: Int) {
            now += 100
            session.step(now, SessionInput(TrainerTelemetry(power, if (power > 0) 90.0 else 0.0, null, now)))
        }

        fun ride(seconds: Double, power: Int) = repeat(Math.round(seconds * 10).toInt()) { step(power) }
    }

    private fun started(c: IndoorSessionConfig): Driver {
        val d = Driver(IndoorSessionImpl(c))
        d.session.step(d.now, SessionInput(null))
        return d
    }

    // ----------------------------------------------------------- Kurve

    @Test
    fun `Arbeit der frueheren Fahrt wird mitgefuehrt`() {
        val past = pastWithPower(flat, 100, 8.0) { 200 }
        val c = assertNotNull(pastRideCurve(flat, past))
        val w = assertNotNull(c.workJ)
        assertEquals(0.0, w.first())
        assertEquals(200.0 * 100, w.last(), 1e-6)
        assertEquals(200.0, c.avgPowerW(10.0, 60.0)!!, 1e-9)
    }

    @Test
    fun `ohne Leistung keine Arbeit, wenig Leistung auch nicht`() {
        val none = IndoorTestRoutes.pastRide(flat, 100) { s -> 8.0 * s }
        assertNull(pastRideCurve(flat, none)!!.workJ)
        // Nur jeder dritte Punkt mit Leistung: unter der Haelfte.
        val sparse = pastWithPower(flat, 100, 8.0) { s -> if (s % 3 == 0) 250 else null }
        assertNull(pastRideCurve(flat, sparse)!!.workJ)
        assertNull(pastRideCurve(flat, none)!!.avgPowerW(0.0, 50.0))
    }

    @Test
    fun `gespeicherte Pause zaehlt keine Arbeit`() {
        // 50 s fahren, 120 s stehen (ein Punkt mit Leistung ueber der Luecke), 50 s fahren.
        val first = pastWithPower(flat, 50, 8.0) { 200 }
        val t0 = first.last().time!!
        val second = IndoorTestRoutes.pastRide(flat, 50, t0 = t0 + 120_000L) { s -> 400.0 + 8.0 * s }
            .map { it.copy(power = 300) }
        val c = assertNotNull(pastRideCurve(flat, first + second))
        // 50 s · 200 W + 50 s · 300 W; die Luecke (120 s) zaehlt nicht.
        assertEquals(50.0 * 200 + 50.0 * 300, c.workJ!!.last(), 1e-6)
    }

    // ----------------------------------------------------------- Geist live

    @Test
    fun `Geist der Aussenfahrt traegt ihren Leistungsverlauf`() {
        val past = pastWithPower(flat, 600, 8.0) { s -> if (s <= 60) 150 else 300 }
        val d = started(config(ghost = GhostSpec.PastRide("x", past)))
        d.ride(30.0, 200)
        assertEquals(150.0, d.session.state.ghost!!.powerW!!, 1e-6)
        d.ride(60.0, 200)
        assertEquals(300.0, d.session.state.ghost!!.powerW!!, 1e-6)
    }

    @Test
    fun `konstanter Geist meldet seine Leistung, fruehere Fahrt ohne Leistung keine`() {
        val k = started(config(ghost = GhostSpec.ConstantPower(240.0)))
        k.ride(5.0, 200)
        assertEquals(240.0, k.session.state.ghost!!.powerW)
        val past = IndoorTestRoutes.pastRide(flat, 600) { s -> 8.0 * s }
        val p = started(config(ghost = GhostSpec.PastRide("x", past)))
        p.ride(5.0, 200)
        assertNull(p.session.state.ghost!!.powerW)
    }

    @Test
    fun `im Ziel hat der Geist keine Leistung mehr`() {
        val short = IndoorTestRoutes.straight(lengthM = 300.0)
        val past = pastWithPower(short, 30, 10.0) { 350 }
        val d = started(config(route = short, ghost = GhostSpec.PastRide("x", past)))
        d.ride(60.0, 150)
        val g = d.session.state.ghost!!
        assertEquals(300.0, g.distM)
        assertNull(g.powerW)
    }

    // ----------------------------------------------------------- Abschnitt

    @Test
    fun `Abschnitt - Fahrer langsamer als die Aussenfahrt`() {
        // Aussenfahrt: 10 m/s, 250 W. Drinnen mit 150 W deutlich langsamer.
        val past = pastWithPower(flat, 500, 10.0) { 250 }
        val d = started(config(ghost = GhostSpec.PastRide("x", past)))
        d.ride(120.0, 150)
        val dist = d.session.state.distM
        val rec = d.session.finish()
        val sec = assertNotNull(rec.ghostSection)
        assertEquals(0.0, sec.startM)
        assertEquals(dist, sec.endM)
        assertEquals(120.0, sec.riderS, 0.11)
        assertEquals(dist / 10.0, sec.ghostS!!, 1e-6)
        assertEquals(150.0, sec.riderAvgPowerW!!, 1.0)
        assertEquals(250.0, sec.ghostAvgPowerW!!, 1e-6)
        assertTrue(sec.riderAheadS!! < 0)
        val result = assertNotNull(ghostResultOf(rec))
        assertEquals(GhostOutcome.BEHIND, result.outcome)
        assertEquals(sec.riderS - sec.ghostS!!, result.seconds, 1e-9)
        assertEquals(dist / sec.riderS * 3.6, sec.riderAvgSpeedKmh!!, 1e-9)
        assertEquals(36.0, sec.ghostAvgSpeedKmh!!, 1e-6)
    }

    @Test
    fun `Abschnitt - Fahrer schneller, Aussenfahrt liegt am Ende hinten`() {
        // Aussenfahrt gemuetlich mit 4 m/s; drinnen mit 300 W schneller.
        val past = pastWithPower(flat, 1250, 4.0) { 120 }
        val d = started(config(ghost = GhostSpec.PastRide("x", past)))
        d.ride(90.0, 300)
        val rec = d.session.finish()
        val sec = assertNotNull(rec.ghostSection)
        // Die Aussenfahrt kennt auch ihre Zeit bis zur Stelle des Fahrers.
        assertEquals(sec.endM / 4.0, sec.ghostS!!, 1e-6)
        val result = assertNotNull(ghostResultOf(rec))
        assertEquals(GhostOutcome.AHEAD, result.outcome)
        assertEquals(sec.ghostS!! - sec.riderS, result.seconds, 1e-9)
    }

    @Test
    fun `Abschnitt beginnt am versetzten Startpunkt`() {
        val past = pastWithPower(flat, 500, 10.0) { 250 }
        val s = createIndoorSessionAt(config(ghost = GhostSpec.PastRide("x", past)), 1000.0)
        val d = Driver(s)
        d.session.step(d.now, SessionInput(null))
        d.ride(60.0, 200)
        val rec = d.session.finish()
        val sec = assertNotNull(rec.ghostSection)
        assertEquals(1000.0, sec.startM, 1e-6)
        assertEquals((sec.endM - 1000.0) / 10.0, sec.ghostS!!, 1e-6)
        assertEquals(60.0, sec.riderS, 0.11)
    }

    @Test
    fun `Aussenfahrt fuhr den Abschnitt nicht ganz - Rueckfall auf den Endabstand`() {
        // Aussenfahrt nur 200 m lang, drinnen weiter.
        val past = pastWithPower(flat, 20, 10.0) { 250 }
        val d = started(config(ghost = GhostSpec.PastRide("x", past)))
        d.ride(80.0, 250)
        val rec = d.session.finish()
        assertNull(rec.ghostSection!!.ghostS)
        val result = ghostResultOf(rec)
        if (rec.ghostFinalGapS == null) assertNull(result) else assertNotNull(result)
    }

    @Test
    fun `konstanter Geist - Abschnitt ohne Zeit, Ergebnis aus dem Endabstand`() {
        val d = started(config(ghost = GhostSpec.ConstantPower(250.0)))
        d.ride(60.0, 200)
        val rec = d.session.finish()
        val sec = assertNotNull(rec.ghostSection)
        assertNull(sec.ghostS)
        assertEquals(250.0, sec.ghostAvgPowerW)
        val result = assertNotNull(ghostResultOf(rec))
        assertEquals(GhostOutcome.BEHIND, result.outcome)
        assertEquals(rec.ghostFinalGapS!!, result.seconds, 1e-9)
    }

    @Test
    fun `ohne Geist kein Abschnitt und kein Ergebnis`() {
        val d = started(config(ghost = GhostSpec.None))
        d.ride(30.0, 200)
        val rec = d.session.finish()
        assertNull(rec.ghostSection)
        assertNull(ghostResultOf(rec))
    }

    // ----------------------------------------------------------- Ergebnis

    private fun record(section: GhostSection?, finalGapS: Double?) = IndoorRideRecord(
        startedAtEpochMs = 0L,
        routeId = "r",
        routeName = "r",
        mode = IndoorMode.SIM,
        points = emptyList(),
        stats = RideStats(0.0, 0.0, 0.0),
        totals = IndoorTotals(0.0, 0.0, 0, null, null, null, null, 0.0),
        laps = 1,
        ghostFinalGapS = finalGapS,
        ghostSection = section,
    )

    @Test
    fun `Ergebnis - gleichauf unter einer Sekunde, Abschnitt geht vor`() {
        val even = GhostSection(0.0, 1000.0, riderS = 100.4, ghostS = 100.0, riderAvgPowerW = null, ghostAvgPowerW = null)
        assertEquals(GhostOutcome.EVEN, ghostResultOf(record(even, finalGapS = 30.0))!!.outcome)
        val ahead = even.copy(riderS = 88.0)
        val r = ghostResultOf(record(ahead, finalGapS = 30.0))!!
        assertEquals(GhostOutcome.AHEAD, r.outcome)
        assertEquals(12.0, r.seconds, 1e-9)
        // Ohne Abschnittszeit: Endabstand (positiv = Geist vorn → Fahrer hinten).
        val r2 = ghostResultOf(record(even.copy(ghostS = null), finalGapS = 7.5))!!
        assertEquals(GhostOutcome.BEHIND, r2.outcome)
        assertEquals(7.5, r2.seconds, 1e-9)
        assertNull(ghostResultOf(record(null, null)))
        assertNull(ghostResultOf(record(null, Double.NaN)))
    }
}
