package de.trailscape.app.ui.more

import de.trailscape.app.R
import de.trailscape.app.sensors.BleKanaele
import de.trailscape.core.BleKanal
import de.trailscape.core.BleSensorTyp
import de.trailscape.core.BleVerbindung
import kotlin.test.Test
import kotlin.test.assertEquals

/** Die Statuszeile eines gekoppelten Sensors auf der Seite Mehr → Sensoren. */
class SensorsStatusTest {
    private val jetzt = 50_000L

    private fun kanaele(puls: BleKanal = BleKanal.AUS, naechster: Map<BleSensorTyp, Long> = emptyMap()) =
        BleKanaele(puls = puls, leistung = BleKanal.AUS, trittfrequenz = BleKanal.AUS, naechsterVersuchMs = naechster)

    @Test
    fun `ohne Nutzer verbindet er sich bei der naechsten Aufzeichnung`() {
        assertEquals(StatusZeile(R.string.ble_state_idle), sensorStatusZeile(BleSensorTyp.PULS, null, kanaele(), jetzt))
        assertEquals(
            StatusZeile(R.string.ble_state_idle),
            sensorStatusZeile(BleSensorTyp.PULS, BleVerbindung.AUS, kanaele(), jetzt),
        )
    }

    @Test
    fun `verbunden mit frischem Wert, still und wartend`() {
        val frisch = kanaele(BleKanal(true, 142, jetzt - 1_000))
        assertEquals(
            StatusZeile(R.string.ble_state_connected_hr, 142),
            sensorStatusZeile(BleSensorTyp.PULS, BleVerbindung.VERBUNDEN, frisch, jetzt),
        )
        val still = kanaele(BleKanal(true, 142, jetzt - 9_500))
        assertEquals(
            StatusZeile(R.string.ble_state_silent, 9),
            sensorStatusZeile(BleSensorTyp.PULS, BleVerbindung.VERBUNDEN, still, jetzt),
        )
        assertEquals(
            StatusZeile(R.string.ble_state_connected_waiting),
            sensorStatusZeile(BleSensorTyp.PULS, BleVerbindung.VERBUNDEN, kanaele(BleKanal(true, null, null)), jetzt),
        )
    }

    @Test
    fun `getrennt zeigt den Countdown bis zum Neuversuch`() {
        val k = kanaele(naechster = mapOf(BleSensorTyp.LEISTUNG to jetzt + 9_200))
        assertEquals(
            StatusZeile(R.string.ble_state_retry, 10),
            sensorStatusZeile(BleSensorTyp.LEISTUNG, BleVerbindung.WARTET, k, jetzt),
        )
    }

    @Test
    fun `Hindernisse sind Warnungen`() {
        assertEquals(
            StatusZeile(R.string.ble_state_no_permission, warnung = true),
            sensorStatusZeile(BleSensorTyp.TRITTFREQUENZ, BleVerbindung.KEINE_BERECHTIGUNG, kanaele(), jetzt),
        )
        assertEquals(
            StatusZeile(R.string.ble_state_bluetooth_off, warnung = true),
            sensorStatusZeile(BleSensorTyp.TRITTFREQUENZ, BleVerbindung.BLUETOOTH_AUS, kanaele(), jetzt),
        )
    }
}
