package de.trailscape.core.indoor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Die laufend abfragbaren Punkte der Sitzung (`recordedPointsFrom`) — fuer die
 * Sicherung einer Fahrt gegen einen Prozesstod (docs/indoor/hintergrund.md).
 */
class IndoorSessionRecordedPointsTest {

    private val config = IndoorSessionConfig(
        IndoorTestRoutes.straight(lengthM = 5000.0),
        IndoorMode.SIM,
        IndoorTestRoutes.RIDER,
        IndoorWind.CALM,
        null,
        GhostSpec.None,
        TrainerCapabilities(simulation = true, targetPower = true),
        1_800_000_000_000L,
    )

    private fun IndoorSession.ride(fromMs: Long, seconds: Int, power: Int): Long {
        var now = fromMs
        repeat(seconds * 10) {
            now += 100
            step(now, SessionInput(TrainerTelemetry(power, 90.0, null, now), null, true))
        }
        return now
    }

    @Test
    fun `stueckweise abgefragt ergeben die Punkte genau die Aufzeichnung`() {
        val s = IndoorSessionImpl(config)
        assertTrue(s.recordedPointsFrom(0).isEmpty(), "vor dem ersten Tritt nichts")
        var now = s.ride(10_000L, 20, 200)
        val first = s.recordedPointsFrom(0)
        assertTrue(first.size >= 19, "rund ein Punkt je Sekunde, hier ${first.size}")
        now = s.ride(now, 15, 220)
        val second = s.recordedPointsFrom(first.size)
        assertTrue(second.isNotEmpty())
        assertEquals(first, s.recordedPointsFrom(0).take(first.size), "frueher gelieferte Punkte aendern sich nicht")
        assertTrue(s.recordedPointsFrom(10_000).isEmpty(), "ein Index hinter dem Ende liefert nichts")
        assertTrue(s.recordedPointsFrom(-3).size == first.size + second.size)

        val record = s.finish()
        val rest = s.recordedPointsFrom(first.size + second.size)
        assertEquals(record.points, first + second + rest)
    }
}
