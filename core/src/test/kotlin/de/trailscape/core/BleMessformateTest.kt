package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Die drei GATT-Messformate mit Byte-Arrays nach der Bluetooth-Spezifikation.
 * Alles, was am Funkweg nicht pruefbar ist, haengt an diesen Parsern — sie
 * muessen jede Flag-Kombination und jede zu kurze Nutzlast ohne Ausnahme
 * ueberstehen.
 */
class BleMessformateTest {
    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    // ------------------------------------------------------------ Herzfrequenz

    @Test
    fun `Puls mit 8 Bit`() {
        val m = parsePulsMessung(bytes(0x00, 0x48))!!
        assertEquals(72, m.bpm)
        assertNull(m.sensorKontakt)
        assertNull(m.energieKj)
        assertTrue(m.rrMs.isEmpty())
    }

    @Test
    fun `Puls mit 16 Bit`() {
        assertEquals(180, parsePulsMessung(bytes(0x01, 0xB4, 0x00))!!.bpm)
        assertEquals(300, parsePulsMessung(bytes(0x01, 0x2C, 0x01))!!.bpm)
    }

    @Test
    fun `Kontaktstatus aus Bit 1 und 2`() {
        assertEquals(true, parsePulsMessung(bytes(0x06, 60))!!.sensorKontakt)
        assertEquals(false, parsePulsMessung(bytes(0x04, 60))!!.sensorKontakt)
        assertNull(parsePulsMessung(bytes(0x00, 60))!!.sensorKontakt)
        // Bit 1 ohne Bit 2 (Erkennung nicht unterstuetzt) zaehlt nicht.
        assertNull(parsePulsMessung(bytes(0x02, 60))!!.sensorKontakt)
    }

    @Test
    fun `Energie wird vor den RR-Intervallen richtig uebersprungen`() {
        // Flags: Energie (Bit 3) + RR (Bit 4); HF 8 Bit = 100; Energie 0x0102; RR 1024.
        val m = parsePulsMessung(bytes(0x18, 100, 0x02, 0x01, 0x00, 0x04))!!
        assertEquals(100, m.bpm)
        assertEquals(258, m.energieKj)
        assertEquals(listOf(1000.0), m.rrMs)
    }

    @Test
    fun `zwei RR-Intervalle in ms`() {
        val m = parsePulsMessung(bytes(0x10, 90, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(2, m.rrMs.size)
        assertEquals(1000.0, m.rrMs[0], 1e-9)
        assertEquals(500.0, m.rrMs[1], 1e-9)
    }

    @Test
    fun `ungerades RR-Restbyte wird ignoriert`() {
        val m = parsePulsMessung(bytes(0x10, 90, 0x00, 0x04, 0x07))!!
        assertEquals(listOf(1000.0), m.rrMs)
    }

    @Test
    fun `16-Bit-Puls mit RR`() {
        val m = parsePulsMessung(bytes(0x11, 0x8C, 0x00, 0x00, 0x03))!!
        assertEquals(140, m.bpm)
        assertEquals(750.0, m.rrMs.single(), 1e-9)
    }

    @Test
    fun `Puls 0 wird geliefert, nicht verworfen`() {
        // Ob 0 zaehlt, entscheidet die Anzeigelogik, nicht der Parser.
        assertEquals(0, parsePulsMessung(bytes(0x00, 0x00))!!.bpm)
    }

    @Test
    fun `zu kurze Puls-Nutzlast ergibt null`() {
        assertNull(parsePulsMessung(bytes()))
        assertNull(parsePulsMessung(bytes(0x00)))
        assertNull(parsePulsMessung(bytes(0x01, 0x48)))
        // Energie angekuendigt, aber abgeschnitten.
        assertNull(parsePulsMessung(bytes(0x08, 70, 0x01)))
    }

    // ---------------------------------------------------------- Cycling Power

    @Test
    fun `nur Leistung`() {
        val m = parseLeistungsMessung(bytes(0x00, 0x00, 0xFA, 0x00))!!
        assertEquals(250, m.watt)
        assertNull(m.rad)
        assertNull(m.kurbel)
    }

    @Test
    fun `negative Leistung als sint16`() {
        assertEquals(-5, parseLeistungsMessung(bytes(0x00, 0x00, 0xFB, 0xFF))!!.watt)
    }

    @Test
    fun `Balance, Drehmoment, Rad und Kurbel mit korrekten Offsets`() {
        val m = parseLeistungsMessung(
            bytes(
                0x35, 0x00, // Flags: Bit 0, 2, 4, 5
                0x2C, 0x01, // 300 W
                0x32, // Balance
                0x10, 0x20, // Drehmoment
                0x01, 0x02, 0x03, 0x04, // Radzaehler 0x04030201
                0x00, 0x08, // Rad-Ereigniszeit 2048
                0x0A, 0x00, // Kurbelzaehler 10
                0x00, 0x04, // Kurbel-Ereigniszeit 1024
            ),
        )!!
        assertEquals(300, m.watt)
        assertEquals(Umdrehungen(0x04030201L, 2048), m.rad)
        assertEquals(Umdrehungen(10L, 1024), m.kurbel)
    }

    @Test
    fun `nur Kurbeldaten`() {
        val m = parseLeistungsMessung(bytes(0x20, 0x00, 0x64, 0x00, 0xFF, 0xFF, 0x34, 0x12))!!
        assertEquals(100, m.watt)
        assertNull(m.rad)
        assertEquals(Umdrehungen(65535L, 0x1234), m.kurbel)
    }

    @Test
    fun `nur Raddaten`() {
        val m = parseLeistungsMessung(bytes(0x10, 0x00, 0x64, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x00))!!
        assertEquals(Umdrehungen(4294967295L, 1), m.rad)
        assertNull(m.kurbel)
    }

    @Test
    fun `abgeschnittene Kurbeldaten ergeben null`() {
        assertNull(parseLeistungsMessung(bytes(0x20, 0x00, 0x64, 0x00, 0x0A, 0x00, 0x00)))
        assertNull(parseLeistungsMessung(bytes(0x00, 0x00, 0x64)))
        assertNull(parseLeistungsMessung(bytes(0x00)))
        assertNull(parseLeistungsMessung(bytes()))
        // Balance angekuendigt, fehlt.
        assertNull(parseLeistungsMessung(bytes(0x01, 0x00, 0x64, 0x00)))
    }

    @Test
    fun `Folgefelder nach der Kurbel werden ignoriert`() {
        // Bit 5 Kurbel + Bit 6/7 (Extremwerte), Zusatzbytes am Ende.
        val m = parseLeistungsMessung(
            bytes(0xE0, 0x00, 0x64, 0x00, 0x05, 0x00, 0x00, 0x02, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
        )!!
        assertEquals(100, m.watt)
        assertEquals(Umdrehungen(5L, 512), m.kurbel)
    }

    // ------------------------------------------------------------------- CSC

    @Test
    fun `CSC nur Rad`() {
        val m = parseCscMessung(bytes(0x01, 0x64, 0x00, 0x00, 0x00, 0x00, 0x04))!!
        assertEquals(Umdrehungen(100L, 1024), m.rad)
        assertNull(m.kurbel)
    }

    @Test
    fun `CSC nur Kurbel`() {
        val m = parseCscMessung(bytes(0x02, 0x03, 0x00, 0x00, 0x08))!!
        assertNull(m.rad)
        assertEquals(Umdrehungen(3L, 2048), m.kurbel)
    }

    @Test
    fun `CSC beides, Kurbel nach sechs Radbytes`() {
        val m = parseCscMessung(bytes(0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x01, 0x07, 0x00, 0x00, 0x02))!!
        assertEquals(Umdrehungen(10L, 256), m.rad)
        assertEquals(Umdrehungen(7L, 512), m.kurbel)
    }

    @Test
    fun `CSC uint32 wird vorzeichenlos gelesen`() {
        val m = parseCscMessung(bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00))!!
        assertEquals(4294967295L, m.rad!!.zaehler)
    }

    @Test
    fun `CSC zu kurz ergibt null`() {
        assertNull(parseCscMessung(bytes()))
        assertNull(parseCscMessung(bytes(0x01, 0x00, 0x00, 0x00)))
        assertNull(parseCscMessung(bytes(0x03, 0x0A, 0x00, 0x00, 0x00, 0x00, 0x01, 0x07)))
        // Nur Flags ohne Felder ist gueltig und leer.
        assertNotNull(parseCscMessung(bytes(0x00)))
    }

    @Test
    fun `kein Parser wirft bei beliebigen Bytes`() {
        val rnd = java.util.Random(42)
        repeat(2000) {
            val b = ByteArray(rnd.nextInt(24)).also { rnd.nextBytes(it) }
            parsePulsMessung(b)
            parseLeistungsMessung(b)
            parseCscMessung(b)
        }
    }

    // --------------------------------------------------------- Dienst-UUIDs

    @Test
    fun `Sensortypen aus Dienst-UUIDs`() {
        assertEquals(
            setOf(BleSensorTyp.PULS),
            sensorTypenAusDiensten(listOf("0000180d-0000-1000-8000-00805f9b34fb")),
        )
        assertEquals(
            setOf(BleSensorTyp.LEISTUNG, BleSensorTyp.TRITTFREQUENZ),
            sensorTypenAusDiensten(listOf("00001818-0000-1000-8000-00805F9B34FB", "0x1816")),
        )
        assertEquals(setOf(BleSensorTyp.PULS), sensorTypenAusDiensten(listOf("180D")))
        assertTrue(sensorTypenAusDiensten(listOf("0000180f-0000-1000-8000-00805f9b34fb", "fremd")).isEmpty())
    }
}
