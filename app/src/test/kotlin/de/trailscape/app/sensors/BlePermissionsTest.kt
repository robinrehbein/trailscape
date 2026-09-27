package de.trailscape.app.sensors

import android.Manifest
import de.trailscape.core.BleSensorTyp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Die Versionsweiche der Bluetooth-Berechtigungen und das Zusammenfassen der Suchtreffer. */
class BlePermissionsTest {

    @Test
    fun `bis API 30 nur die Standortfreigabe`() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), bluetoothPermissions(26))
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), bluetoothPermissions(30))
    }

    @Test
    fun `ab API 31 Geraete in der Naehe`() {
        val erwartet = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(erwartet, bluetoothPermissions(31))
        assertEquals(erwartet, bluetoothPermissions(35))
    }

    @Test
    fun `Standortdienst nur unter API 31 noetig`() {
        assertTrue(locationServicesNeeded(30))
        assertFalse(locationServicesNeeded(31))
    }

    @Test
    fun `Suchtreffer werden je Adresse zusammengefasst und nach Staerke sortiert`() {
        var liste = emptyList<BleFund>()
        liste = fuegeFundHinzu(liste, BleFund("A", "Gurt", setOf(BleSensorTyp.PULS), -80))
        liste = fuegeFundHinzu(liste, BleFund("B", null, setOf(BleSensorTyp.LEISTUNG), -60))
        // Dasselbe Geraet noch einmal, jetzt ohne Namen, mit weiterem Dienst und staerker.
        liste = fuegeFundHinzu(liste, BleFund("B", null, setOf(BleSensorTyp.TRITTFREQUENZ), -50))
        liste = fuegeFundHinzu(liste, BleFund("A", null, setOf(BleSensorTyp.PULS), -40))
        assertEquals(listOf("A", "B"), liste.map { it.adresse })
        assertEquals("Gurt", liste[0].name)
        assertEquals(setOf(BleSensorTyp.LEISTUNG, BleSensorTyp.TRITTFREQUENZ), liste[1].typen)
        assertEquals(-50, liste[1].rssi)
    }
}
