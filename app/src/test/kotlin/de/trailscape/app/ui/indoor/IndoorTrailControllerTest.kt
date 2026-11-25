package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.ClimbBestStore
import de.trailscape.core.KeyValueStore
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.climb.ClimbBest
import de.trailscape.core.indoor.climb.ClimbBestBook
import de.trailscape.core.indoor.climb.ClimbLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Die App-Huelle der Bergwertung: Bestzeiten landen im Speicher, Fehler stoeren die Fahrt nicht. */
class IndoorTrailControllerTest {
    private class FakeStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun setString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** 1 km flach, 1 km bei 6 %, 1 km flach. */
    private val route: IndoorRoute = run {
        val pts = List(301) { i ->
            val d = i * 10.0
            IndoorRoutePoint(d, 50.0 + d * 1e-5, 7.0, 100.0 + (d.coerceIn(1_000.0, 2_000.0) - 1_000.0) * 0.06)
        }
        IndoorRoute("r", "R", pts, listOf(SurfaceSection(0.0, 3_000.0, SurfaceKind.ASPHALT)), false, IndoorRouteSource.NONE)
    }

    private fun state(nowMs: Long, at: Double, phase: IndoorPhase = IndoorPhase.RUNNING) = IndoorSessionState(
        phase = phase, mode = IndoorMode.SIM, nowMs = nowMs, elapsedS = (nowMs / 1000).toInt(), distM = at, routeDistM = at,
        lap = 1, speedMps = 5.0, powerW = 200, cadenceRpm = 85, hrBpm = null, gradePct = 0.0, surface = SurfaceKind.ASPHALT,
        crr = 0.004, headwindMps = 0.0, gear = GearState(0, 24, 2.0), ghost = null, erg = null,
        totals = IndoorTotals(at, 0.0, 0, null, null, null, null, 0.0),
    )

    @Test
    fun `Bestzeit wird gespeichert und in der Auswertung gefuehrt`() {
        val store = FakeStore()
        val c = IndoorTrailController(store) { 99L }
        c.start(route, "ride:x")
        var seenOn = false
        var now = 0L
        var at = 0.0
        while (at < 2_100.0) {
            val live = c.update(state(now, at))
            if (live?.climb is ClimbLive.On) seenOn = true
            now += 1_000
            at += 10.0
        }
        assertTrue(seenOn)
        val efforts = c.efforts()
        assertEquals(1, efforts.size)
        assertTrue(efforts[0].newBest)
        val book = ClimbBestStore.read(store)
        assertEquals(1, book.bests.size)
        assertEquals("ride:x", book.bests[0].routeKey)
        assertEquals(99L, book.bests[0].achievedAtEpochMs)

        c.stop()
        assertNull(c.update(state(now, at)))
        assertTrue(c.efforts().isEmpty())
    }

    @Test
    fun `kaputter Speicher stoert nicht, Import fuehrt zusammen`() {
        val store = FakeStore()
        store.map[ClimbBestStore.KEY] = "{kaputt"
        val c = IndoorTrailController(store) { 0L }
        c.start(route, "ride:x")
        assertNull(c.update(state(0, 0.0)))

        ClimbBestStore.merge(store, ClimbBestBook().record(ClimbBest("ride:x", 1_000.0, 2_000.0, 5_000, emptyList(), 1)))
        assertEquals(5_000L, ClimbBestStore.read(store).bests.single().timeMs)
        ClimbBestStore.merge(store, ClimbBestBook().record(ClimbBest("ride:x", 1_010.0, 2_000.0, 9_000, emptyList(), 1)))
        assertEquals(5_000L, ClimbBestStore.read(store).bests.single().timeMs)
    }

    @Test
    fun `Schalter Einblendungen, Vorgabe an`() {
        val store = FakeStore()
        assertTrue(IndoorTrailController.readOverlays(store))
        IndoorTrailController.writeOverlays(store, false)
        assertFalse(IndoorTrailController.readOverlays(store))
    }
}
