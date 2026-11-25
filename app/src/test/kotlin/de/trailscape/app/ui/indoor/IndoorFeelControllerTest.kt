package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.IndoorProtocolStore
import de.trailscape.core.KeyValueStore
import de.trailscape.core.indoor.FEEL_TARGET_WINDOW_S
import de.trailscape.core.indoor.FEEL_WARMUP_S
import de.trailscape.core.indoor.FeelPhase
import de.trailscape.core.indoor.FeelResult
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorStep
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.RideFeel
import de.trailscape.core.indoor.RideProtocolRecorder
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.SurfaceKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndoorFeelControllerTest {
    private class MapStore : KeyValueStore {
        val m = HashMap<String, String>()
        override fun getString(key: String) = m[key]
        override fun setString(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }

    private val rider = RiderSetup(75.0, 12.0, 0.38, 0.008, 250.0)

    private fun step(elapsedS: Int, power: Int, cadence: Int, speed: Double, gear: Int = 15): IndoorStep {
        val state = IndoorSessionState(
            phase = IndoorPhase.RUNNING, mode = IndoorMode.SIM, nowMs = elapsedS * 1000L, elapsedS = elapsedS,
            distM = 0.0, routeDistM = 0.0, lap = 1, speedMps = speed, powerW = power, cadenceRpm = cadence, hrBpm = null,
            gradePct = 0.0, surface = SurfaceKind.COMPACTED, crr = 0.008, headwindMps = 0.0,
            gear = GearState(gear, 24, 2.5), ghost = null, erg = null,
            totals = IndoorTotals(0.0, 0.0, elapsedS, null, null, null, null, 0.0),
        )
        return IndoorStep(state, emptyList())
    }

    @Test
    fun feelPersistsClampsAndResets() {
        val store = MapStore()
        val c = IndoorFeelController(store)
        assertTrue(c.feel.value.isDefault)
        c.setResistance(1.2)
        c.setSurfaceStrength(9.0)
        assertEquals(1.2, c.feel.value.resistance, 1e-9)
        assertEquals(RideFeel.SURFACE_MAX, c.feel.value.surfaceStrength, 1e-9)
        // neu gelesen
        val again = IndoorFeelController(store)
        assertEquals(1.2, again.feel.value.resistance, 1e-9)
        again.reset()
        assertTrue(again.feel.value.isDefault)
        // Vorgabe wird nicht gespeichert (keine Altlast)
        assertNull(store.getString(IndoorFeelController.KEY_RESISTANCE))
        assertNull(store.getString(IndoorFeelController.KEY_SURFACE))
    }

    @Test
    fun brokenStoredValuesFallBackToDefault() {
        val store = MapStore()
        store.m[IndoorFeelController.KEY_RESISTANCE] = "abc"
        store.m[IndoorFeelController.KEY_SURFACE] = "NaN"
        assertTrue(IndoorFeelController(store).feel.value.isDefault)
    }

    @Test
    fun rideWritesProtocolOnEnd() {
        val dir = kotlin.io.path.createTempDirectory("feel").toFile().also { it.deleteOnExit() }
        val protocols = IndoorProtocolStore(File(dir, "p"))
        val c = IndoorFeelController(MapStore(), protocols)
        c.beginRide(rider, RideProtocolRecorder.headerOf(rider, IndoorMode.SIM, "R", 5L), 5L, "R", calibrate = false)
        for (t in 1..30) c.onStep(step(t, 200, 90, 9.0))
        assertTrue(protocols.list().isEmpty())
        c.endRide()
        assertEquals(1, protocols.list().size)
        c.endRide() // zweimal: harmlos
        assertEquals(1, protocols.list().size)
    }

    @Test
    fun rideWithoutDataWritesNothing() {
        val dir = kotlin.io.path.createTempDirectory("feel").toFile().also { it.deleteOnExit() }
        val protocols = IndoorProtocolStore(File(dir, "p"))
        val c = IndoorFeelController(MapStore(), protocols)
        c.beginRide(rider, emptyList(), 1L, "R", calibrate = false)
        c.endRide()
        assertTrue(protocols.list().isEmpty())
    }

    @Test
    fun calibrationRunsToResultAndAccepts() {
        val store = MapStore()
        val c = IndoorFeelController(store)
        c.beginRide(rider, emptyList(), 1L, "Abgleich", calibrate = true)
        assertEquals(FeelPhase.WAITING, c.calibration.value?.phase)
        // 85 U/min im Gang 15 (2,5): ~ v = 85/60*2.5*2.1 = 7,4 m/s, Leistung "passend" -> hier bewusst ein Trainer, der 25 % zu schwer ist
        val v = 7.4375
        for (t in 1..(1 + FEEL_WARMUP_S + 1 + FEEL_TARGET_WINDOW_S)) c.onStep(step(t, 150, 85, v))
        val ui = assertNotNull(c.calibration.value)
        assertEquals(FeelPhase.DONE, ui.phase)
        assertNotNull(ui.result)
        if (ui.result is FeelResult.Suggest) {
            c.acceptSuggestion()
            assertTrue(c.calibration.value!!.accepted)
        }
        c.endCalibration()
        assertNull(c.calibration.value)
        assertTrue(!c.calibrating)
    }

    @Test
    fun calibrationIgnoresRepeatedStepsOfTheSameSecond() {
        val c = IndoorFeelController(MapStore())
        c.beginRide(rider, emptyList(), 1L, "Abgleich", calibrate = true)
        repeat(50) { c.onStep(step(1, 150, 85, 7.4)) }
        assertEquals(1, c.calibration.value?.phaseS)
    }
}
