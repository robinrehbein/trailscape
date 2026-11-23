package de.trailscape.core.indoor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FTMS-Bytes nach Fitness Machine Service 1.0 und GATT Specification
 * Supplement (Quellen im KDoc von [FtmsCodec]). Jede Flag-Kombination und
 * jedes gekuerzte Paket muss ohne Ausnahme durchgehen.
 */
class FtmsCodecTest {
    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun bike(vararg v: Int) = FtmsCodec.parseIndoorBikeData(b(*v), 1234L)

    // ------------------------------------------------------------ Indoor Bike Data

    @Test
    fun `KICKR-typisches Paket mit Tempo, Kadenz und Leistung`() {
        val t = assertNotNull(bike(0x44, 0x00, 0x18, 0x0B, 0x9C, 0x00, 0xFA, 0x00))
        assertEquals(28.40, t.speedKmh!!, 1e-9)
        assertEquals(78.0, t.cadenceRpm!!, 1e-9)
        assertEquals(250, t.powerW)
        assertEquals(1234L, t.receivedMs)
    }

    @Test
    fun `Kadenz in halben Umdrehungen`() {
        assertEquals(78.5, bike(0x45, 0x00, 0x9D, 0x00)!!.cadenceRpm!!, 1e-9)
    }

    @Test
    fun `More Data gesetzt heisst ohne Tempo`() {
        val t = assertNotNull(bike(0x45, 0x00, 0x9C, 0x00, 0xFA, 0x00))
        assertNull(t.speedKmh)
        assertEquals(78.0, t.cadenceRpm)
        assertEquals(250, t.powerW)
    }

    @Test
    fun `aufgeteilter Datensatz - jedes Paket fuer sich`() {
        // Paket 1: More Data = 1, nur Kadenz; Paket 2: More Data = 0, Tempo und Leistung.
        val p1 = assertNotNull(bike(0x05, 0x00, 0xB4, 0x00))
        assertEquals(90.0, p1.cadenceRpm)
        assertNull(p1.speedKmh)
        assertNull(p1.powerW)
        val p2 = assertNotNull(bike(0x40, 0x00, 0x10, 0x0E, 0x2C, 0x01))
        assertEquals(36.0, p2.speedKmh!!, 1e-9)
        assertNull(p2.cadenceRpm)
        assertEquals(300, p2.powerW)
    }

    @Test
    fun `alle Flags gesetzt`() {
        val t = assertNotNull(
            bike(
                0xFE, 0x1F, // Flags: Bit 1..12, Bit 0 = 0 (Tempo da)
                0x10, 0x27, // Instantaneous Speed 100,00 km/h
                0x00, 0x00, // Average Speed
                0xB4, 0x00, // Instantaneous Cadence 90
                0x00, 0x00, // Average Cadence
                0x01, 0x02, 0x03, // Total Distance
                0x05, 0x00, // Resistance Level
                0x2C, 0x01, // Instantaneous Power 300
                0x00, 0x00, // Average Power
                0x00, 0x00, 0x00, 0x00, 0x00, // Expended Energy
                0x8C, // Heart Rate
                0x0A, // MET
                0x3C, 0x00, // Elapsed Time
                0x00, 0x00, // Remaining Time
            ),
        )
        assertEquals(100.0, t.speedKmh!!, 1e-9)
        assertEquals(90.0, t.cadenceRpm)
        assertEquals(300, t.powerW)
    }

    @Test
    fun `Durchschnitte, Distanz und Widerstand werden richtig uebersprungen`() {
        // Bit 1 (Avg Speed), 3 (Avg Cadence), 4 (Distanz), 5 (Widerstand) vor der Leistung.
        val t = assertNotNull(
            bike(0x7E, 0x00, 0x18, 0x0B, 0x11, 0x11, 0x9C, 0x00, 0x22, 0x22, 0x01, 0x02, 0x03, 0x05, 0x00, 0xFA, 0x00),
        )
        assertEquals(28.4, t.speedKmh!!, 1e-9)
        assertEquals(78.0, t.cadenceRpm)
        assertEquals(250, t.powerW)
    }

    @Test
    fun `gekuerzte Pakete liefern alles bis zur Luecke`() {
        val nurTempo = assertNotNull(bike(0x44, 0x00, 0x18, 0x0B))
        assertEquals(28.4, nurTempo.speedKmh!!, 1e-9)
        assertNull(nurTempo.cadenceRpm)
        assertNull(nurTempo.powerW)

        val halbeKadenz = assertNotNull(bike(0x44, 0x00, 0x18, 0x0B, 0x9C))
        assertNull(halbeKadenz.cadenceRpm)
        assertNull(halbeKadenz.powerW)

        val halbesTempo = assertNotNull(bike(0x44, 0x00, 0x18))
        assertNull(halbesTempo.speedKmh)

        // Distanz (uint24) abgeschnitten: die Leistung dahinter ist verloren, die Kadenz davor nicht.
        val t = assertNotNull(bike(0x55, 0x00, 0x9C, 0x00, 0x01, 0x02))
        assertEquals(78.0, t.cadenceRpm)
        assertNull(t.powerW)
    }

    @Test
    fun `Pakete unter 2 Byte sind keine Telemetrie`() {
        assertNull(bike())
        assertNull(bike(0x44))
        val leer = assertNotNull(bike(0x01, 0x00))
        assertNull(leer.speedKmh)
        assertNull(leer.cadenceRpm)
        assertNull(leer.powerW)
    }

    @Test
    fun `unplausible Leistung und Kadenz werden null`() {
        assertNull(bike(0x41, 0x00, 0xF6, 0xFF)!!.powerW) // -10 W
        assertNull(bike(0x41, 0x00, 0xB9, 0x0B)!!.powerW) // 3001 W
        assertEquals(3000, bike(0x41, 0x00, 0xB8, 0x0B)!!.powerW)
        assertEquals(0, bike(0x41, 0x00, 0x00, 0x00)!!.powerW)
        assertNull(bike(0x05, 0x00, 0xF6, 0x01)!!.cadenceRpm) // 251
        assertEquals(250.0, bike(0x05, 0x00, 0xF4, 0x01)!!.cadenceRpm)
    }

    @Test
    fun `Leistung als sint16 mit Vorzeichen gelesen`() {
        // 0xFFFF waere vorzeichenlos 65535 und damit „plausibel gross“ — als sint16 ist es -1.
        assertNull(bike(0x41, 0x00, 0xFF, 0xFF)!!.powerW)
    }

    // ------------------------------------------------------------ Feature und Ranges

    @Test
    fun `Feature mit Kadenz, Leistung, ERG und SIM`() {
        val bytes = b(0x02, 0x40, 0x00, 0x00, 0x08, 0x20, 0x00, 0x00)
        val c = FtmsCodec.parseFeature(bytes)
        assertTrue(c.simulation)
        assertTrue(c.targetPower)
        assertTrue(c.reportsCadence)
        assertNull(c.minPowerW)
        assertNull(c.maxPowerW)
        assertTrue(FtmsCodec.reportsPower(bytes))
    }

    @Test
    fun `Feature nur ERG ohne Kadenz`() {
        val c = FtmsCodec.parseFeature(b(0x00, 0x40, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00))
        assertFalse(c.simulation)
        assertTrue(c.targetPower)
        assertFalse(c.reportsCadence)
    }

    @Test
    fun `Feature gekuerzt`() {
        val nurMaschine = FtmsCodec.parseFeature(b(0x02, 0x00, 0x00, 0x00))
        assertTrue(nurMaschine.reportsCadence)
        assertFalse(nurMaschine.simulation)
        assertFalse(nurMaschine.targetPower)
        assertEquals(TrainerCapabilities.NONE, FtmsCodec.parseFeature(b(0x02, 0x00, 0x00)))
        assertEquals(TrainerCapabilities.NONE, FtmsCodec.parseFeature(b()))
        assertFalse(FtmsCodec.reportsPower(b(0x00)))
    }

    @Test
    fun `Power Range`() {
        assertEquals(0 to 2000, FtmsCodec.parsePowerRange(b(0x00, 0x00, 0xD0, 0x07, 0x01, 0x00)))
        assertEquals(-10 to 2000, FtmsCodec.parsePowerRange(b(0xF6, 0xFF, 0xD0, 0x07, 0x01, 0x00)))
        assertEquals(25 to 25, FtmsCodec.parsePowerRange(b(0x19, 0x00, 0x19, 0x00)))
        assertNull(FtmsCodec.parsePowerRange(b(0xD0, 0x07, 0x00, 0x00, 0x01, 0x00)))
        assertNull(FtmsCodec.parsePowerRange(b(0x00, 0x00, 0xD0)))
    }

    // ------------------------------------------------------------ Kodierer

    @Test
    fun `einfache Opcodes`() {
        assertEquals("00", hex(FtmsCodec.requestControl()))
        assertEquals("01", hex(FtmsCodec.reset()))
        assertEquals("07", hex(FtmsCodec.startOrResume()))
        assertEquals("08 01", hex(FtmsCodec.stop()))
        assertEquals("08 02", hex(FtmsCodec.pause()))
    }

    @Test
    fun `Zielleistung mit Vorzeichen und Klemmen`() {
        assertEquals("05 FA 00", hex(FtmsCodec.setTargetPower(250)))
        assertEquals("05 FB FF", hex(FtmsCodec.setTargetPower(-5)))
        assertEquals("05 FF 7F", hex(FtmsCodec.setTargetPower(40000)))
        assertEquals("05 01 80", hex(FtmsCodec.setTargetPower(-40000)))
    }

    @Test
    fun `Simulationsparameter byteweise`() {
        assertEquals("11 00 00 F4 01 28 33", hex(FtmsCodec.setSimulation(0.0, 5.0, 0.004, 0.51)))
        // Rueckenwind -2,5 m/s → -2500; Gefaelle -3,456 % → -345,6 → -346; Crr 0,00406 → 41; Cw 0,004 → 0.
        assertEquals("11 3C F6 A6 FE 29 00", hex(FtmsCodec.setSimulation(-2.5, -3.456, 0.00406, 0.004)))
    }

    @Test
    fun `Simulationsparameter runden`() {
        assertEquals("11 00 00 7B 00 00 00", hex(FtmsCodec.setSimulation(0.0, 1.234, 0.0, 0.0)))
        assertEquals("11 00 00 7C 00 00 00", hex(FtmsCodec.setSimulation(0.0, 1.236, 0.0, 0.0)))
        assertEquals("11 01 00 00 00 00 00", hex(FtmsCodec.setSimulation(0.0012, 0.0, 0.0, 0.0)))
    }

    @Test
    fun `Simulationsparameter klemmen und NaN`() {
        assertEquals("11 FF 7F 01 80 FF 00", hex(FtmsCodec.setSimulation(100.0, -500.0, 0.1, -1.0)))
        assertEquals("11 00 00 00 00 00 FF", hex(FtmsCodec.setSimulation(Double.NaN, Double.NaN, Double.NaN, 9.0)))
        assertEquals(
            "11 01 80 FF 7F FF 00",
            hex(FtmsCodec.setSimulation(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)),
        )
        assertEquals("11 00 00 00 00 FF FF", hex(FtmsCodec.setSimulation(0.0, 0.0, 0.0255, 2.55)))
    }

    // ------------------------------------------------------------ Antworten

    @Test
    fun `Control-Point-Antworten`() {
        assertEquals(FtmsCodec.Antwort(0x05, 0x01), FtmsCodec.parseControlPointResponse(b(0x80, 0x05, 0x01)))
        assertEquals(FtmsCodec.Antwort(0x00, 0x05), FtmsCodec.parseControlPointResponse(b(0x80, 0x00, 0x05)))
        assertEquals(FtmsCodec.Antwort(0x08, 0x01), FtmsCodec.parseControlPointResponse(b(0x80, 0x08, 0x01, 0xFF)))
        assertNull(FtmsCodec.parseControlPointResponse(b(0x80, 0x07)))
        assertNull(FtmsCodec.parseControlPointResponse(b(0x81, 0x05, 0x01)))
        assertNull(FtmsCodec.parseControlPointResponse(b()))
    }

    @Test
    fun `Ergebniscodes benannt`() {
        assertEquals(1, FtmsCodec.RESULT_SUCCESS)
        assertEquals(2, FtmsCodec.RESULT_NOT_SUPPORTED)
        assertEquals(3, FtmsCodec.RESULT_INVALID_PARAMETER)
        assertEquals(4, FtmsCodec.RESULT_FAILED)
        assertEquals(5, FtmsCodec.RESULT_CONTROL_NOT_PERMITTED)
        assertEquals("Kontrolle nicht erlaubt (0x05)", FtmsCodec.resultName(5))
        assertEquals("unbekannt (0x7f)", FtmsCodec.resultName(0x7F))
    }

    @Test
    fun `Machine Status`() {
        assertEquals(FtmsCodec.STATUS_CONTROL_PERMISSION_LOST, FtmsCodec.parseMachineStatus(b(0xFF)))
        assertEquals(FtmsCodec.STATUS_TARGET_POWER_CHANGED, FtmsCodec.parseMachineStatus(b(0x08, 0xFA, 0x00)))
        assertNull(FtmsCodec.parseMachineStatus(b()))
    }
}
