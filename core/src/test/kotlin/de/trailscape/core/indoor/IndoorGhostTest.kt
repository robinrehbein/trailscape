package de.trailscape.core.indoor

import de.trailscape.core.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Geist: konstante Leistung, fruehere Fahrt (Projektion, Rueckfall, Pausen), Abstand. */
class IndoorGhostTest {
    private val rider = IndoorTestRoutes.RIDER
    private val route = IndoorTestRoutes.straight(lengthM = 3000.0)

    @Test
    fun `konstante Leistung faehrt dieselbe Physik wie der Fahrer`() {
        val g = ConstantPowerGhost(route, rider, IndoorWind.CALM, 200.0)
        var v = 0.0
        var d = 0.0
        for (i in 1..600) {
            g.advance(i * 100L, 0.1)
            val s = advancePhysics(v, 200.0, 0.0, 0.008, 0.0, 0.0, rider, 0.1)
            v = s.speedMps
            d += s.distanceM
        }
        assertEquals(d, g.distM, 1e-9)
        assertEquals(v, g.speedMps, 1e-12)
    }

    @Test
    fun `konstante Leistung bleibt am Ziel stehen, faehrt auf der Runde weiter`() {
        val short = IndoorTestRoutes.straight(lengthM = 300.0)
        val g = ConstantPowerGhost(short, rider, IndoorWind.CALM, 300.0)
        repeat(1000) { g.advance(it * 100L, 0.1) }
        assertEquals(300.0, g.distM)
        assertEquals(0.0, g.speedMps)
        val loop = IndoorTestRoutes.loop(lengthM = 300.0)
        val lg = ConstantPowerGhost(loop, rider, IndoorWind.CALM, 300.0)
        repeat(1000) { lg.advance(it * 100L, 0.1) }
        assertTrue(lg.distM > 600.0, "${lg.distM}")
    }

    @Test
    fun `Abstand mit Vorzeichen und Sekunden beim Tempo des Hinteren`() {
        val ahead = object : GhostRunner {
            override val distM = 150.0
            override val speedMps = 9.0
            override fun advance(runningMs: Long, dtS: Double) = Unit
        }
        val s = ghostState(ahead, 100.0, 8.0)
        assertEquals(50.0, s.gapM)
        assertEquals(50.0 / 8.0, s.gapS!!, 1e-12) // Fahrer hinten → sein Tempo
        val behind = ghostState(ahead, 200.0, 8.0)
        assertEquals(-50.0, behind.gapM)
        assertEquals(-50.0 / 9.0, behind.gapS!!, 1e-12) // Geist hinten → sein Tempo
        assertNull(ghostState(ahead, 100.0, 0.4).gapS)
        assertEquals(150.0, s.distM)
        assertEquals(9.0, s.speedMps)
    }

    @Test
    fun `fruehere Fahrt wird auf die Laufmeter projiziert`() {
        val pts = IndoorTestRoutes.pastRide(route, 300, lateralM = 12.0) { s -> 5.0 * s }
        val c = pastRideCurve(route, pts)!!
        assertEquals(301, c.timesS.size)
        assertEquals(0.0, c.timesS[0])
        assertEquals(300.0, c.timesS.last())
        for (i in c.distsM.indices) {
            assertEquals(5.0 * i, c.distsM[i], 0.5)
        }
        val (d, v) = c.at(100.5)
        assertEquals(502.5, d, 0.5)
        assertEquals(5.0, v, 0.05)
        // Nach dem Ende bleibt der Geist stehen.
        assertEquals(c.distsM.last() to 0.0, c.at(10_000.0))
        assertEquals(c.distsM.first() to 0.0, c.at(-5.0))
    }

    @Test
    fun `Projektion laeuft nie rueckwaerts und ueberspringt Ausreisser`() {
        val base = IndoorTestRoutes.pastRide(route, 100) { s -> 5.0 * s }
        val pts = base.toMutableList()
        // Ein Punkt 5 m zurueck (auf 240 m), einer 300 m neben der Strecke.
        pts[50] = TrackPoint(lat = route.sampleAt(240.0).lat, lon = route.sampleAt(240.0).lon, time = base[50].time)
        val (fLat, fLon) = IndoorTestRoutes.offset(300.0, 90.0, base[60].lat, base[60].lon)
        pts[60] = TrackPoint(lat = fLat, lon = fLon, time = base[60].time)
        val c = pastRideCurve(route, pts)!!
        assertEquals(100, c.distsM.size) // der Ausreisser entfaellt
        for (i in 1 until c.distsM.size) assertTrue(c.distsM[i] >= c.distsM[i - 1])
        assertEquals(245.0, c.distsM[50], 1e-6) // Rueckschritt auf der Stelle davor festgehalten
    }

    @Test
    fun `passt die Fahrt nicht, wird ihre Distanz auf die Strecke gestreckt`() {
        // Eine gerade Fahrt 400 m oestlich, doppelt so lang wie die Strecke.
        val pts = List(201) { s ->
            val (lat, lon) = IndoorTestRoutes.offset(10.0 * s, 0.0, IndoorTestRoutes.offset(400.0, 90.0).first, IndoorTestRoutes.offset(400.0, 90.0).second)
            TrackPoint(lat = lat, lon = lon, time = 1_000_000L + s * 1000L)
        }
        val short = IndoorTestRoutes.straight(lengthM = 1000.0)
        val c = pastRideCurve(short, pts)!!
        assertEquals(1000.0, c.distsM.last(), 1e-6)
        assertEquals(500.0, c.distsM[100], 0.5)
    }

    @Test
    fun `gespeicherte Pausen werden entfernt`() {
        val pts = IndoorTestRoutes.pastRide(route, 100) { s -> 5.0 * s }.toMutableList()
        // Ab Punkt 50 alles 90 s spaeter (Aufzeichnung pausiert am selben Ort).
        for (i in 50 until pts.size) pts[i] = pts[i].copy(time = pts[i].time!! + 90_000L)
        val c = pastRideCurve(route, pts)!!
        assertEquals(100.0, c.timesS.last(), 1e-9) // die Luecke von 91 s schrumpft auf 1 s
        // Eine lange Luecke MIT Fortschritt (Funkloch beim Fahren) bleibt.
        val moving = IndoorTestRoutes.pastRide(route, 10) { s -> if (s < 5) 5.0 * s else 25.0 + 5.0 * 40 + 5.0 * (s - 5) }.toMutableList()
        for (i in 5 until moving.size) moving[i] = moving[i].copy(time = moving[i].time!! + 40_000L)
        val mc = pastRideCurve(route, moving)!!
        assertEquals(50.0, mc.timesS.last(), 1e-9)
    }

    @Test
    fun `ohne zwei Punkte mit Zeit kein Geist`() {
        val one = IndoorTestRoutes.pastRide(route, 0) { 0.0 }
        assertNull(pastRideCurve(route, one))
        val untimed = IndoorTestRoutes.pastRide(route, 50) { s -> 5.0 * s }.map { it.copy(time = null) }
        assertNull(pastRideCurve(route, untimed))
        assertNull(ghostRunner(GhostSpec.PastRide("x", untimed), route, rider, IndoorWind.CALM))
        assertNull(ghostRunner(GhostSpec.None, route, rider, IndoorWind.CALM))
        // Punkte ohne Zeit dazwischen stoeren nicht.
        val mixed = IndoorTestRoutes.pastRide(route, 50) { s -> 5.0 * s }.mapIndexed { i, p -> if (i % 2 == 1) p.copy(time = null) else p }
        assertEquals(26, pastRideCurve(route, mixed)!!.timesS.size)
        // Gleiche Zeitstempel: der spaetere entfaellt.
        val dup = IndoorTestRoutes.pastRide(route, 10) { s -> 5.0 * s }.toMutableList()
        dup[3] = dup[3].copy(time = dup[2].time)
        assertEquals(10, pastRideCurve(route, dup)!!.timesS.size)
    }

    @Test
    fun `fruehere Fahrt ueber zwei Runden`() {
        val loop = IndoorTestRoutes.loop(lengthM = 1000.0)
        val pts = IndoorTestRoutes.pastRide(loop, 400) { s -> 5.0 * s }
        val c = pastRideCurve(loop, pts)!!
        assertEquals(2000.0, c.distsM.last(), 1.0)
        assertEquals(1000.0, c.distsM[200], 1.0)
        val g = assertNotNull(ghostRunner(GhostSpec.PastRide("x", pts), loop, rider, IndoorWind.CALM))
        g.advance(300_000L, 0.1)
        assertEquals(1500.0, g.distM, 1.0)
        assertEquals(5.0, g.speedMps, 0.05)
    }
}
