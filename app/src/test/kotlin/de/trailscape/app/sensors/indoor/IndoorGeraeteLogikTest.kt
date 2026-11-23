package de.trailscape.app.sensors.indoor

import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.IndoorUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndoorGeraeteLogikTest {

    private val trainer = IndoorDeviceRef(IndoorDeviceRole.TRAINER, "AA:BB:CC:DD:EE:01", "KICKR CORE 5A1F")
    private val taster = IndoorDeviceRef(IndoorDeviceRole.SHIFTER, "AA:BB:CC:DD:EE:02", null)

    @Test
    fun speicherformatRundreise() {
        val geraete = mapOf(IndoorDeviceRole.SHIFTER to taster, IndoorDeviceRole.TRAINER to trainer)
        assertEquals(geraete, dekodiereIndoorGeraete(kodiereIndoorGeraete(geraete)))
    }

    @Test
    fun speicherformatIstStabil() {
        assertEquals(
            """[{"rolle":"TRAINER","adresse":"AA:BB:CC:DD:EE:01","name":"KICKR CORE 5A1F"},""" +
                """{"rolle":"SHIFTER","adresse":"AA:BB:CC:DD:EE:02"}]""",
            kodiereIndoorGeraete(mapOf(IndoorDeviceRole.SHIFTER to taster, IndoorDeviceRole.TRAINER to trainer)),
        )
        assertEquals("[]", kodiereIndoorGeraete(emptyMap()))
    }

    @Test
    fun kaputtesOderLeeresJsonHeisstNichtsGemerkt() {
        assertTrue(dekodiereIndoorGeraete(null).isEmpty())
        assertTrue(dekodiereIndoorGeraete("").isEmpty())
        assertTrue(dekodiereIndoorGeraete("{kaputt").isEmpty())
        assertTrue(dekodiereIndoorGeraete("""{"rolle":"TRAINER"}""").isEmpty())
    }

    @Test
    fun unbekannteRollenUndEintraegeOhneAdresseWerdenUebersprungen() {
        val raw = """[{"rolle":"LENKER","adresse":"X"},{"rolle":"TRAINER","adresse":""},""" +
            """{"rolle":"TRAINER"},5,{"rolle":"SHIFTER","adresse":"AA:BB:CC:DD:EE:02","name":"  "}]"""
        assertEquals(mapOf(IndoorDeviceRole.SHIFTER to taster), dekodiereIndoorGeraete(raw))
    }

    @Test
    fun jeRolleGewinntDerLetzteEintrag() {
        val raw = """[{"rolle":"TRAINER","adresse":"A"},{"rolle":"TRAINER","adresse":"B","name":"Neu"}]"""
        assertEquals(
            IndoorDeviceRef(IndoorDeviceRole.TRAINER, "B", "Neu"),
            dekodiereIndoorGeraete(raw)[IndoorDeviceRole.TRAINER],
        )
    }

    @Test
    fun trainerAmFtmsDienstErkannt() {
        assertEquals(
            setOf(IndoorDeviceRole.TRAINER),
            indoorRollenAusWerbung(listOf("00001818-0000-1000-8000-00805F9B34FB", IndoorUuids.FTMS_SERVICE.uppercase()), emptyList()),
        )
    }

    @Test
    fun schalttasterAmDienstOderAnDerFirmenkennung() {
        assertEquals(setOf(IndoorDeviceRole.SHIFTER), indoorRollenAusWerbung(listOf(IndoorUuids.ZWIFT_SERVICE), emptyList()))
        assertEquals(setOf(IndoorDeviceRole.SHIFTER), indoorRollenAusWerbung(emptyList(), listOf(0x004C, 0x094A)))
    }

    @Test
    fun geraetMitBeidemStehtInBeidenRollen() {
        assertEquals(
            setOf(IndoorDeviceRole.TRAINER, IndoorDeviceRole.SHIFTER),
            indoorRollenAusWerbung(listOf(IndoorUuids.FTMS_SERVICE), listOf(IndoorUuids.ZWIFT_COMPANY_ID)),
        )
    }

    @Test
    fun fremdeGeraeteHabenKeineRolle() {
        assertTrue(indoorRollenAusWerbung(listOf("0000180d-0000-1000-8000-00805f9b34fb"), listOf(0x0059)).isEmpty())
    }

    @Test
    fun fundeJeAdresseZusammengefasstUndNachSignalSortiert() {
        var liste = emptyList<IndoorFund>()
        liste = fuegeIndoorFundHinzu(liste, IndoorFund("A", "KICKR CORE", setOf(IndoorDeviceRole.TRAINER), -70))
        liste = fuegeIndoorFundHinzu(liste, IndoorFund("B", null, setOf(IndoorDeviceRole.SHIFTER), -50))
        // Werbung ohne Namen, aber mit weiterer Rolle und neuem Signal.
        liste = fuegeIndoorFundHinzu(liste, IndoorFund("A", null, setOf(IndoorDeviceRole.SHIFTER), -40))
        assertEquals(listOf("A", "B"), liste.map { it.adresse })
        assertEquals("KICKR CORE", liste[0].name)
        assertEquals(setOf(IndoorDeviceRole.TRAINER, IndoorDeviceRole.SHIFTER), liste[0].rollen)
        assertEquals(-40, liste[0].rssi)
    }
}
