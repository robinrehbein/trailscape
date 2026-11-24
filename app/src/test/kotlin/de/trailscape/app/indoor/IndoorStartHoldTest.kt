package de.trailscape.app.indoor

import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeDemoTrainer
import de.trailscape.app.indoor.testing.testIndoorRoute
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.createIndoorSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers

/**
 * Warten auf „Losfahren" in der Laufzeit (docs/indoor/app.md, „Startpunkt
 * und Fortsetzen"): echte Sitzung, Trainer-Attrappe, die nach 1 s tritt.
 */
class IndoorStartHoldTest {
    private val clock = FakeClock()

    private fun runtime(releaseOnFirstStroke: Boolean) = IndoorRideRuntime(
        session = createIndoorSession(
            IndoorSessionConfig(
                route = testIndoorRoute(),
                mode = IndoorMode.SIM,
                rider = RiderSetup(riderMassKg = 75.0, bikeMassKg = 10.0, cdaM2 = 0.4, profileCrr = 0.008, ftpW = 200.0),
                startedAtEpochMs = 1_760_000_000_000L,
            ),
        ),
        trainer = FakeDemoTrainer(),
        shifter = null,
        renderer = null,
        dispatcher = Dispatchers.Unconfined,
        clock = clock,
        autoLoop = false,
        holdStart = true,
        releaseOnFirstStroke = releaseOnFirstStroke,
    ).also { it.start() }

    private fun IndoorRideRuntime.ticks(count: Int) = repeat(count) {
        clock.advance(IndoorRideRuntime.TICK_MS)
        tickOnce()
    }

    private fun IndoorRideRuntime.session() = assertNotNull(state.value.session)

    @Test
    fun `Demo wartet auf Losfahren, auch wenn schon getreten wird`() {
        val rt = runtime(releaseOnFirstStroke = false)
        rt.ticks(50)
        assertEquals(IndoorPhase.READY, rt.session().phase)
        assertTrue(rt.state.value.startHeld)
        assertFalse(rt.state.value.pedalStarts)
        assertEquals(0.0, rt.session().distM)
        rt.releaseStart()
        rt.ticks(30)
        assertEquals(IndoorPhase.RUNNING, rt.session().phase)
        assertFalse(rt.state.value.startHeld)
    }

    @Test
    fun `echter Trainer - erster Tritt startet, solange der Startpunkt unberuehrt ist`() {
        val rt = runtime(releaseOnFirstStroke = true)
        rt.ticks(1)
        assertTrue(rt.state.value.pedalStarts)
        rt.ticks(30)
        assertEquals(IndoorPhase.RUNNING, rt.session().phase)
        assertTrue(rt.session().distM > 0.0)
    }

    @Test
    fun `echter Trainer - nach dem Verschieben startet nur Losfahren`() {
        val rt = runtime(releaseOnFirstStroke = true)
        rt.moveStart(1_000.0)
        rt.ticks(50)
        assertEquals(IndoorPhase.READY, rt.session().phase)
        assertEquals(1_000.0, rt.session().distM)
        assertFalse(rt.state.value.pedalStarts)
        rt.releaseStart()
        rt.ticks(30)
        assertEquals(IndoorPhase.RUNNING, rt.session().phase)
        assertEquals(rt.session().distM - 1_000.0, rt.session().totals.distanceM, 1e-6)
    }
}
