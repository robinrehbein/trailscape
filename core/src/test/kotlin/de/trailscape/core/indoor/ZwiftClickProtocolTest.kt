package de.trailscape.core.indoor

import de.trailscape.core.indoor.ClickFakeLink.Companion.bytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parser und Handschlag des Schalttasters. Vektoren aus der Recherche
 * (Quellen im KDoc von [ZwiftClickProtocol]); Click-V1-Rahmen sind aus der
 * beschriebenen Protobuf-Semantik ABGELEITET, woertlich sind nur der
 * Play-Rahmen (Makinolo) und der Ride-Leerlauf (GearUp).
 */
class ZwiftClickProtocolTest {

    private fun frame(hex: String) = ZwiftClickProtocol.parseFrame(bytes(hex))

    private fun buttons(plus: Boolean, minus: Boolean, v: ClickVariant) = ClickFrame.Buttons(plus, minus, v)

    // ------------------------------------------------------------ Varint

    @Test
    fun `einbytiger Varint`() {
        assertEquals(Varint(0, 1), readVarint(bytes("00"), 0))
        assertEquals(Varint(127, 1), readVarint(bytes("7F"), 0))
        assertEquals(Varint(1, 3), readVarint(bytes("AA BB 01"), 2))
    }

    @Test
    fun `mehrbytige Varints`() {
        assertEquals(Varint(300, 2), readVarint(bytes("AC 02"), 0))
        assertEquals(Varint(0x0FFFFFFF, 4), readVarint(bytes("FF FF FF 7F"), 0))
        assertEquals(Varint(0x0FFFFFFFL, 5).value, readVarint(bytes("FF FF FF FF 00"), 0)!!.value)
        assertEquals(Varint(0xFFFFFFFFL, 5), readVarint(bytes("FF FF FF FF 0F"), 0))
        // 10 Byte: groesster 64-Bit-Wert (-1 als unsigned)
        assertEquals(-1L, readVarint(bytes("FF FF FF FF FF FF FF FF FF 01"), 0)!!.value)
    }

    @Test
    fun `abgeschnittener oder ueberlanger Varint ist null`() {
        assertNull(readVarint(bytes("AC"), 0))
        assertNull(readVarint(bytes(""), 0))
        assertNull(readVarint(bytes("01"), 1))
        assertNull(readVarint(bytes("01"), -1))
        assertNull(readVarint(bytes("FF FF FF FF FF FF FF FF FF FF 01"), 0))
    }

    @Test
    fun `Protobuf-Felder mit uebersprungenen Wire-Typen`() {
        // Feld 1 = 5, Feld 3 laengenbegrenzt (2 Byte), Feld 4 fixed32, Feld 5 fixed64, Feld 2 = 300
        val f = parseProtoFields(bytes("08 05 1A 02 AA BB 25 01 02 03 04 29 01 02 03 04 05 06 07 08 10 AC 02"), 0)
        assertTrue(f.complete)
        assertEquals(mapOf(1 to 5L, 2 to 300L), f.varints)
    }

    @Test
    fun `wiederholtes Feld - der letzte Wert gilt`() {
        assertEquals(mapOf(1 to 1L), parseProtoFields(bytes("08 00 08 01"), 0).varints)
    }

    @Test
    fun `kaputte Protobuf-Nachrichten`() {
        // Feldnummer 0
        assertFalse(parseProtoFields(bytes("00 01"), 0).complete)
        // Gruppe (Wire-Typ 3)
        assertFalse(parseProtoFields(bytes("0B"), 0).complete)
        // Laenge groesser als der Rest
        val f = parseProtoFields(bytes("08 07 12 05 01"), 0)
        assertFalse(f.complete)
        assertEquals(mapOf(1 to 7L), f.varints)
        // fixed32 abgeschnitten
        assertFalse(parseProtoFields(bytes("0D 01 02"), 0).complete)
        // Wert fehlt
        assertFalse(parseProtoFields(bytes("08"), 0).complete)
        // leer ist vollstaendig und ohne Felder
        assertEquals(ProtoFields(emptyMap(), true), parseProtoFields(bytes(""), 0))
    }

    // ------------------------------------------------------------ Click V1 (0x37)

    @Test
    fun `Click V1 Plus gedrueckt`() {
        assertEquals(buttons(true, false, ClickVariant.V1), frame("37 08 00 10 01"))
    }

    @Test
    fun `Click V1 Minus gedrueckt`() {
        assertEquals(buttons(false, true, ClickVariant.V1), frame("37 08 01 10 00"))
    }

    @Test
    fun `Click V1 beide los und beide gedrueckt`() {
        assertEquals(buttons(false, false, ClickVariant.V1), frame("37 08 01 10 01"))
        assertEquals(buttons(true, true, ClickVariant.V1), frame("37 08 00 10 00"))
    }

    @Test
    fun `Click V1 fehlendes Feld gilt als losgelassen`() {
        assertEquals(buttons(true, false, ClickVariant.V1), frame("37 08 00"))
        assertEquals(buttons(false, true, ClickVariant.V1), frame("37 10 00"))
        assertEquals(buttons(false, false, ClickVariant.V1), frame("37"))
    }

    @Test
    fun `Click V1 Felder in anderer Reihenfolge und mit Zusatzfeld`() {
        assertEquals(buttons(false, true, ClickVariant.V1), frame("37 10 00 08 01 18 05"))
    }

    @Test
    fun `Click V1 jeder Wert ungleich 0 ist losgelassen, auch mehrbytig`() {
        assertEquals(buttons(false, true, ClickVariant.V1), frame("37 08 AC 02 10 00"))
    }

    @Test
    fun `Click V1 abgeschnitten wird verworfen`() {
        assertIs<ClickFrame.Malformed>(frame("37 08 00 10"))
        assertIs<ClickFrame.Malformed>(frame("37 08 80"))
    }

    // ------------------------------------------------------------ Ride / Click V2 (0x23)

    @Test
    fun `V2 Leerlauf von GearUp - keine Taste gedrueckt`() {
        // woertlich (GearUp): 23 08 FF FF FF FF 0F 12 18 … — hier mit abgeschnittenem Rest
        assertEquals(buttons(false, false, ClickVariant.V2), frame("23 08 FF FF FF FF 0F"))
        assertEquals(buttons(false, false, ClickVariant.V2), frame("23 08 FF FF FF FF 0F 12 18"))
        assertEquals(buttons(false, false, ClickVariant.V2), frame("23 08 FF FF FF FF 0F 12 18 08 00 10 00"))
    }

    @Test
    fun `V2 Leerlauf mit 0x0FFFFFFF als 4-Byte-Varint`() {
        assertEquals(buttons(false, false, ClickVariant.V2), frame("23 08 FF FF FF 7F"))
    }

    private fun rideMap(pressedMask: Long): String {
        val map = 0xFFFFFFFFL and pressedMask.inv()
        val out = StringBuilder("23 08")
        var v = map
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.append(" %02X".format(b))
                break
            }
            out.append(" %02X".format(b or 0x80))
        }
        return out.toString()
    }

    @Test
    fun `V2 Schaltbits beider Pods`() {
        assertEquals(buttons(true, false, ClickVariant.V2), frame(rideMap(ZwiftClickProtocol.RIDE_SHIFT_UP_LEFT)))
        assertEquals(buttons(true, false, ClickVariant.V2), frame(rideMap(ZwiftClickProtocol.RIDE_SHIFT_UP_RIGHT)))
        assertEquals(buttons(false, true, ClickVariant.V2), frame(rideMap(ZwiftClickProtocol.RIDE_SHIFT_DOWN_LEFT)))
        assertEquals(buttons(false, true, ClickVariant.V2), frame(rideMap(ZwiftClickProtocol.RIDE_SHIFT_DOWN_RIGHT)))
        assertEquals(
            buttons(true, true, ClickVariant.V2),
            frame(rideMap(ZwiftClickProtocol.RIDE_SHIFT_UP_LEFT or ZwiftClickProtocol.RIDE_SHIFT_DOWN_RIGHT)),
        )
    }

    @Test
    fun `V2 Schaltbits als Bytes - hoch rechts gedrueckt`() {
        // 0x0FFFFFFF ohne Bit 0x2000 = 0x0FFFDFFF → FF BF FF 7F
        assertEquals(buttons(true, false, ClickVariant.V2), frame("23 08 FF BF FF 7F"))
    }

    @Test
    fun `V2 andere Tasten loesen keinen Schaltwunsch aus`() {
        // Steuerkreuz, A/B/Y/Z, Power-up, Ein/Aus
        val other = 0x1L or 0x2L or 0x4L or 0x8L or 0x10L or 0x20L or 0x40L or 0x100L or 0x800L or 0x1000L
        assertEquals(buttons(false, false, ClickVariant.V2), frame(rideMap(other)))
    }

    @Test
    fun `V2 ohne ButtonMap oder abgeschnitten im Feld 1 wird verworfen`() {
        assertIs<ClickFrame.Malformed>(frame("23"))
        assertIs<ClickFrame.Malformed>(frame("23 08 FF FF"))
        assertIs<ClickFrame.Malformed>(frame("23 12 02 01 02"))
    }

    // ------------------------------------------------------------ Batterie, Leerlauf, Fremdes

    @Test
    fun `Batterie 93 Prozent`() {
        assertEquals(ClickFrame.Battery(93), frame("19 10 5D"))
        assertEquals(ClickFrame.Battery(100), frame("19 08 01 10 64"))
        assertEquals(ClickFrame.Battery(0), frame("19 10 00"))
    }

    @Test
    fun `Batterie unplausibel oder ohne Feld 2`() {
        assertIs<ClickFrame.Malformed>(frame("19 10 65"))
        assertIs<ClickFrame.Malformed>(frame("19 08 5D"))
        assertIs<ClickFrame.Malformed>(frame("19"))
    }

    @Test
    fun `Leerlaufrahmen`() {
        assertEquals(ClickFrame.Idle, frame("15"))
        assertEquals(ClickFrame.Idle, frame("15 08 01"))
    }

    @Test
    fun `Play-Rahmen von Makinolo wird nicht als Click gewertet`() {
        val f = frame("07-08-00-10-01-18-01-20-00-28-01-30-01-38-01-40-00-48-00")
        assertIs<ClickFrame.Ignored>(f)
        assertEquals(0x07, f.type)
    }

    @Test
    fun `unbekannte Typen und leere Rahmen`() {
        assertEquals(0x3C, assertIs<ClickFrame.Ignored>(frame("3C 08 01")).type)
        assertEquals(0xFE, assertIs<ClickFrame.Ignored>(frame("FE")).type)
        assertIs<ClickFrame.Malformed>(frame(""))
    }

    @Test
    fun `Parser wirft bei keinem Byte-Muster`() {
        val rnd = java.util.Random(4711)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(24)) { rnd.nextInt(256).toByte() }
            if (b.isNotEmpty() && rnd.nextInt(3) == 0) {
                b[0] = listOf(0x37, 0x23, 0x19, 0x15, 0x07)[rnd.nextInt(5)].toByte()
            }
            ZwiftClickProtocol.parseFrame(b)
            ZwiftClickProtocol.classifyHandshakeAnswer(b)
        }
    }

    // ------------------------------------------------------------ Handschlag

    @Test
    fun `Handschlag-Bytes`() {
        assertContentEquals(bytes("52 69 64 65 4F 6E"), ZwiftClickProtocol.handshake(ClickVariant.V1))
        assertContentEquals(bytes("52 69 64 65 4F 6E 02 03"), ZwiftClickProtocol.handshake(ClickVariant.V2))
        assertContentEquals("RideOn".toByteArray(Charsets.US_ASCII), ZwiftClickProtocol.RIDE_ON)
    }

    @Test
    fun `Handschlag-Bytes sind Kopien`() {
        val a = ZwiftClickProtocol.handshake(ClickVariant.V1)
        a[0] = 0
        assertEquals(0x52.toByte(), ZwiftClickProtocol.handshake(ClickVariant.V1)[0])
    }

    private fun answer(hex: String) = ZwiftClickProtocol.classifyHandshakeAnswer(bytes(hex))

    @Test
    fun `Echo RideOn ist OK - mit und ohne Kopfbytes`() {
        assertEquals(HandshakeAnswer.OK, answer("52 69 64 65 4F 6E"))
        assertEquals(HandshakeAnswer.OK, answer("52 69 64 65 4F 6E 01 03"))
        assertEquals(HandshakeAnswer.OK, answer("52 69 64 65 4F 6E 00 09"))
        assertEquals(HandshakeAnswer.OK, answer("52 69 64 65 4F 6E 02 03"))
    }

    @Test
    fun `Antwort mit Schluessel ist verschluesselt`() {
        val key = (1..64).joinToString(" ") { "%02X".format(it) }
        assertEquals(HandshakeAnswer.ENCRYPTED, answer("52 69 64 65 4F 6E 01 03 $key"))
        // bei Standard-MTU auf 20 Byte abgeschnitten
        assertEquals(HandshakeAnswer.ENCRYPTED, answer("52 69 64 65 4F 6E 01 03 01 02 03 04 05 06 07 08 09 0A 0B 0C"))
    }

    @Test
    fun `Antwort ab 0xFF ist gesperrt`() {
        assertEquals(HandshakeAnswer.LOCKED, answer("FF 04 00 0A 15 40"))
        assertEquals(HandshakeAnswer.LOCKED, answer("FF"))
    }

    @Test
    fun `sonstige Antworten sind unbekannt`() {
        assertEquals(HandshakeAnswer.UNKNOWN, answer(""))
        assertEquals(HandshakeAnswer.UNKNOWN, answer("52 69 64 65 4F"))
        assertEquals(HandshakeAnswer.UNKNOWN, answer("52 69 64 65 4F 6F"))
        assertEquals(HandshakeAnswer.UNKNOWN, answer("37 08 00 10 01"))
    }
}
