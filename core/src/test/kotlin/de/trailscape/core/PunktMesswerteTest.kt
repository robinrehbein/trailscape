package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunktMesswerteTest {
    private fun p(tS: Long, power: Int? = null, cad: Int? = null) =
        TrackPoint(lat = 47.0, lon = 11.0, time = T0 + tS * 1000, power = power, cad = cad)

    @Test
    fun `Leistungsmittel schliesst Nullen ein`() {
        val m = sensorMittelwerte(listOf(p(0), p(10, 200), p(20, 0), p(30, 100)))
        assertEquals(100, m.avgPowerW)
    }

    @Test
    fun `Trittfrequenzmittel schliesst Nullen aus`() {
        val m = sensorMittelwerte(listOf(p(0), p(10, cad = 90), p(20, cad = 0), p(30, cad = 80)))
        assertEquals(85, m.avgCadenceRpm)
        assertNull(m.avgPowerW)
    }

    @Test
    fun `zeitgewichtet ueber die Punktabstaende`() {
        // 20 s bei 300 W, 5 s bei 100 W → (6000 + 500) / 25 = 260 W.
        val m = sensorMittelwerte(listOf(p(0), p(20, 300), p(25, 100)))
        assertEquals(260, m.avgPowerW)
    }

    @Test
    fun `Luecke ueber 30 s zaehlt nicht`() {
        val m = sensorMittelwerte(listOf(p(0), p(10, 200), p(100, 1000), p(110, 100)))
        assertEquals(150, m.avgPowerW)
    }

    @Test
    fun `ohne Werte null`() {
        assertEquals(SensorMittelwerte(null, null), sensorMittelwerte(listOf(p(0), p(10), p(20))))
        assertEquals(SensorMittelwerte(null, null), sensorMittelwerte(emptyList()))
        // Ohne Zeitstempel laesst sich nichts gewichten.
        assertEquals(
            SensorMittelwerte(null, null),
            sensorMittelwerte(listOf(TrackPoint(1.0, 1.0, power = 200), TrackPoint(1.0, 1.0, power = 200))),
        )
    }
}
