package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.ActorState
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fortschreiben zwischen Zustaenden, Kurbel-/Radphase und Kameras (docs/indoor.md 7.6). */
class MotionAndCameraTest {
    @Test
    fun schreibtMitDemTempoFort() {
        val m = MotionSmoother()
        m.update(ActorState(100.0, 10.0, 90.0), timeMs = 1_000, paused = false)
        // 10 Bilder zu 16 ms nach dem Zustand
        var now = 1_000L
        repeat(10) {
            now += 16
            m.advance(now, 0.016)
        }
        assertTrue(abs(m.distM - 101.6) < 0.3, "etwa 1,6 m weiter: ${m.distM}")
    }

    @Test
    fun fortschreibenHoechstensEineHalbeSekunde() {
        val m = MotionSmoother()
        m.update(ActorState(0.0, 10.0, 90.0), timeMs = 0, paused = false)
        // Lange kein neuer Zustand: Ziel bleibt bei 5 m (0,5 s × 10 m/s), die Anzeige holt nur ein.
        var now = 0L
        repeat(300) {
            now += 16
            m.advance(now, 0.016)
        }
        // Danach rollt nichts weiter: die Anzeige steht am Ziel, Beine und Raeder stehen.
        assertTrue(abs(m.distM - 5.0) < 0.05, "steht bei 5 m: ${m.distM}")
        assertEquals(0.0, m.speedMps)
    }

    @Test
    fun gleichtKleineAbweichungWeichAus() {
        val m = MotionSmoother()
        m.update(ActorState(0.0, 0.0, 0.0), timeMs = 0, paused = false)
        m.advance(0, 0.0)
        m.update(ActorState(10.0, 0.0, 0.0), timeMs = 16, paused = false)
        m.advance(16, 0.016)
        assertTrue(m.distM in 0.1..5.0, "kein Sprung, sondern ein Stueck: ${m.distM}")
        var now = 16L
        repeat(120) {
            now += 16
            m.advance(now, 0.016)
        }
        assertTrue(abs(m.distM - 10.0) < 0.05, "nach 2 s angekommen: ${m.distM}")
    }

    @Test
    fun springtBeiGrosserAbweichung() {
        val m = MotionSmoother()
        m.update(ActorState(0.0, 0.0, 0.0), timeMs = 0, paused = false)
        m.advance(0, 0.016)
        m.update(ActorState(500.0, 0.0, 0.0), timeMs = 10, paused = false)
        m.advance(10, 0.016)
        assertEquals(500.0, m.distM, 1e-9)
    }

    @Test
    fun pauseHaeltAllesAn() {
        val m = MotionSmoother()
        m.update(ActorState(50.0, 10.0, 90.0), timeMs = 0, paused = true)
        m.advance(200, 0.2)
        val crank = m.crankPhase
        val wheel = m.wheelPhase
        m.advance(400, 0.2)
        assertEquals(50.0, m.distM, 1e-9)
        assertEquals(crank, m.crankPhase)
        assertEquals(wheel, m.wheelPhase)
        assertEquals(0.0, m.speedMps)
    }

    @Test
    fun phasenAusTrittfrequenzUndTempo() {
        val m = MotionSmoother()
        // 60 U/min = 1 Umdrehung je Sekunde; 2,2 m/s = 1 Radumdrehung je Sekunde.
        m.update(ActorState(0.0, 2.2, 60.0), timeMs = 0, paused = false)
        m.advance(0, 0.25)
        assertEquals(0.25, m.crankPhase, 1e-9)
        assertEquals(0.25, m.wheelPhase, 1e-9)
        m.advance(0, 0.5)
        assertEquals(0.75, m.crankPhase, 1e-9)
        m.advance(0, 0.5)
        assertEquals(0.25, m.crankPhase, 1e-9) // wieder bei 0..1
        assertEquals(0.0, MotionSmoother.wrap01(Double.NaN))
    }

    @Test
    fun ohneZustandStehtAllesAmStart() {
        val m = MotionSmoother()
        m.advance(1000, 0.016)
        assertEquals(0.0, m.distM)
    }

    // -----------------------------------------------------------------------

    /** Ein Band nach Osten, 200 m, erst flach, dann steil bergab. */
    private fun eastTrack() = WorldTrack(
        lengthM = 200.0,
        stepM = 5.0,
        loop = false,
        e = FloatArray(41) { it * 5f },
        n = FloatArray(41) { 0f },
        u = FloatArray(41) { i -> if (i < 20) 0f else -(i - 20) * 1f },
        surfaces = listOf(SurfaceSection(0.0, 200.0, SurfaceKind.ASPHALT)),
        source = IndoorRouteSource.NONE,
    )

    @Test
    fun verfolgerkameraHinterUndUeberDemFahrer() {
        val t = eastTrack()
        val rig = CameraRig()
        val p = rig.update(t, 50.0, 0.0, 0.016)
        // Fahrer bei e = 50 (x = 50), Kamera 6 m dahinter, 2,5 m drueber, Blick nach vorn.
        assertEquals(44.0, p.eye.x, 1e-6)
        assertEquals(2.5, p.eye.y, 1e-6)
        assertTrue(p.target.x > 50.0)
        assertTrue(p.target.y > 0.5)
    }

    @Test
    fun kameraNieUnterDerFahrbahn() {
        val t = eastTrack()
        for (mode in CameraMode.entries) {
            val rig = CameraRig()
            rig.mode = mode
            var d = 0.0
            repeat(400) {
                d += 0.5
                val p = rig.update(t, d, 0.5, 0.05)
                val ground = maxOf(t.poseExtended(d - CameraRig.CHASE_BACK_M).u, t.poseExtended(d).u)
                val minAbove = if (mode == CameraMode.CHASE) ground else t.poseExtended(d).u
                assertTrue(p.eye.y >= minAbove + 0.99, "$mode bei $d: Auge ${p.eye.y} ueber $minAbove")
            }
        }
    }

    @Test
    fun kameraIstGedaempftUndSpringtBeiModuswechsel() {
        val t = eastTrack()
        val rig = CameraRig()
        val first = rig.update(t, 50.0, 0.0, 0.016)
        val second = rig.update(t, 60.0, 0.0, 0.016)
        // 10 m weiter, aber nach einem Bild hat die Kamera nur ein Stueck aufgeholt.
        assertTrue(second.eye.x - first.eye.x in 0.1..5.0)
        rig.mode = CameraMode.DRONE
        val drone = rig.update(t, 60.0, 0.0, 0.016)
        assertEquals(CameraRig.desired(t, 60.0, 0.0, CameraMode.DRONE).eye, drone.eye)
        assertTrue(drone.eye.y > 20.0)
    }

    @Test
    fun lenkerkameraAufAugenhoehe() {
        val p = CameraRig.desired(eastTrack(), 50.0, 0.0, CameraMode.HANDLEBAR)
        assertEquals(CameraRig.HANDLEBAR_EYE_M, p.eye.y, 1e-6)
        assertTrue(p.target.x > p.eye.x + 10.0)
    }
}
