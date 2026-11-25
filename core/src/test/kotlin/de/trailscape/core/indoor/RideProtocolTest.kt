package de.trailscape.core.indoor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Das Fahrprotokoll: aufgezeichnet wird, was gesetzt und gemessen wurde; Obergrenze; nichts ausserhalb laufender Phasen. */
class RideProtocolTest {
    private val rider = IndoorTestRoutes.RIDER.copy(trainerScale = 1.1, surfaceStrength = 1.5)
    private val route = IndoorTestRoutes.straight(lengthM = 5000.0)

    private fun session() = IndoorSessionImpl(
        IndoorSessionConfig(route, IndoorMode.SIM, rider, capabilities = TrainerCapabilities(simulation = true, targetPower = true), startedAtEpochMs = 0),
    )

    private fun ride(rec: RideProtocolRecorder, seconds: Int, power: Int = 200) {
        val s = session()
        var now = 0L
        rec.onStep(s.step(now, SessionInput(null)))
        repeat(seconds * 10) {
            now += 100
            rec.onStep(s.step(now, SessionInput(TrainerTelemetry(power, 90.0, null, now))))
        }
    }

    @Test
    fun recordsSetMeasuredAndSimulatedEveryInterval() {
        val rec = RideProtocolRecorder(RideProtocolRecorder.headerOf(rider, IndoorMode.SIM, "Test", 1234L))
        ride(rec, 62)
        val lines = rec.toText().lines().filter { it.isNotBlank() }
        val dataRows = lines.filter { !it.startsWith("#") && it != RideProtocolRecorder.COLUMNS }
        // 62 s laufende Zeit -> etwa 12 Zeilen je 5 s
        assertTrue(dataRows.size in 10..13, "Zeilen: ${dataRows.size}")
        val cols = dataRows.last().split(";")
        assertEquals(RideProtocolRecorder.COLUMNS.split(";").size, cols.size)
        assertEquals("200", cols[9])
        assertEquals("90", cols[10])
        assertTrue(cols[11].toDouble() > 10.0)
        assertTrue(cols[2].isNotEmpty(), "gesetzte Steigung fehlt")
        assertEquals("UNKNOWN", cols[6].let { if (it == "UNKNOWN" || it == "ASPHALT") "UNKNOWN" else it })
        val text = rec.toText()
        assertTrue("trainerScale 1.10" in text && "Belagsstaerke 1.50" in text)
        assertTrue(text.contains("# Mittel: Leistung 200 W"))
    }

    @Test
    fun noRowsWhileNotRunning() {
        val rec = RideProtocolRecorder(emptyList())
        val s = session()
        // READY: keine Telemetrie, nie gestartet
        repeat(100) { rec.onStep(s.step(it * 100L, SessionInput(null))) }
        assertFalse(rec.hasData)
    }

    @Test
    fun capsRowsAndSaysSo() {
        val rec = RideProtocolRecorder(emptyList(), maxRows = 3)
        ride(rec, 60)
        assertEquals(3, rec.rowCount)
        assertTrue("nicht aufgezeichnet" in rec.toText())
    }
}
