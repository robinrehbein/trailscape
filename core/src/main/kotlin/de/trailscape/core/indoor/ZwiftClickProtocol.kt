package de.trailscape.core.indoor

/**
 * # Protokoll des Schalttasters (Zwift Click) — rein, ohne Zustand
 *
 * Proprietaer und nirgends spezifiziert; alles hier stuetzt sich auf die
 * Community-Quellen aus docs/indoor.md, Abschnitt 5.4. Kuerzel im Text:
 *  * **BikeControl** — OpenBikeControl/bikecontrol (frueher SwiftControl),
 *    `lib/bluetooth/devices/zwift/constants.dart`, `zwift_device.dart`,
 *    `zwift_click.dart`, `zwift_clickv2.dart`.
 *  * **zwiftplay** — ajchellew/zwiftplay, README „BLE Spec“.
 *  * **Makinolo** — makinolo.com/blog/2023/10/08/connecting-to-zwift-play-controllers/
 *    und makinolo.com/blog/2024/07/26/zwift-ride-protocol/.
 *  * **Torqa** — Torqa-Indoor-Cycling/Torqa PR #267 (an einer echten Click V1 geprueft).
 *  * **GearUp** — OBellon/GearUp (README, Click V2).
 *  * **jat255**, **click2keyboard** (thezenox) — kleine Click-Werkzeuge mit Mitschnitt-Beschreibungen.
 *  * **qdomyos-zwift** PR #4743 — gesperrte Click V2.
 *
 * ## Rahmen auf `ZWIFT_ASYNC`
 * Jede Notification ist `[1 Typbyte][Protobuf-Nachricht]` (zwiftplay, Makinolo).
 * Die Protobuf-Nachricht wird hier **als Protobuf gelesen** (Tag-Varint, dann
 * Wert nach Wire-Typ), nicht ueber feste Offsets — mehrbytige Varints und
 * unbekannte Felder verschieben sonst alles.
 *
 * | Typ  | Bedeutung | Quelle |
 * |------|-----------|--------|
 * | 0x37 | Tastenzustand Click V1: Feld 1 = Plus, Feld 2 = Minus, Wert 0 = gedrueckt | BikeControl (ON = 0), jat255, click2keyboard |
 * | 0x23 | Tastenzustand Ride / Click V2: Feld 1 = ButtonMap (uint32, invertiert) | Makinolo (Ride), GearUp |
 * | 0x19 | Batterie: Feld 2 = Prozent | click2keyboard, jat255 („93“) |
 * | 0x15 | Leerlauf, etwa 1 Hz, ohne Inhalt von Belang | BikeControl |
 * | 0x07 | Zwift Play (Steuerkreuz) — kein Schalttaster, wird ignoriert | Makinolo |
 * | 0x3C, 0xFE, … | unbekannt bzw. Trennung — wird ignoriert | BikeControl |
 *
 * **Wichtig:** Woertliche Hex-Mitschnitte von Click-V1-Tastenrahmen gibt es in
 * den Quellen nicht. Die Testvektoren sind aus der beschriebenen
 * Protobuf-Semantik abgeleitet (z. B. jat255: Minus gedrueckt = `{1:1, 2:0}`
 * → `37 08 01 10 00`). Woertlich belegt sind nur der Play-Rahmen bei Makinolo
 * und der Ride-Leerlauf `23 08 FF FF FF FF 0F 12 18 …` bei GearUp.
 *
 * Nichts hier wirft: kaputte oder abgeschnittene Rahmen werden zu
 * [ClickFrame.Malformed] bzw. [ClickFrame.Ignored]. Abgeschnitten wird in der
 * Praxis durchaus — die Ride-/V2-Rahmen sind laenger als die 20 Byte, die bei
 * der Standard-MTU in eine Notification passen.
 */
internal object ZwiftClickProtocol {

    /** „RideOn“ in ASCII — Kern jedes Handschlags (zwiftplay, Makinolo, BikeControl). */
    val RIDE_ON: ByteArray = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E)

    /**
     * Handschlag Click V1, unverschluesselt: genau „RideOn“ auf `ZWIFT_SYNC_RX`
     * (BikeControl, Torqa, jat255). Die verschluesselte Variante haengt
     * `01 02` und einen 64-Byte-Schluessel an — die schicken wir nie.
     */
    val HANDSHAKE_V1: ByteArray = RIDE_ON

    /** Handschlag Click V2 / Ride: „RideOn“ + `02 03`, unverschluesselt (BikeControl `zwift_clickv2.dart`, GearUp). */
    val HANDSHAKE_V2: ByteArray = RIDE_ON + byteArrayOf(0x02, 0x03)

    const val TYPE_CLICK_BUTTONS = 0x37
    const val TYPE_RIDE_BUTTONS = 0x23
    const val TYPE_BATTERY = 0x19
    const val TYPE_IDLE = 0x15
    const val TYPE_PLAY_BUTTONS = 0x07

    /*
     * Schaltbits der Ride-ButtonMap nach BikeControl `RideButtonMask`
     * (deckt sich mit der Aufzaehlung bei Makinolo, Ride-Protokoll).
     * UNSICHER: JustFabi/zwiftclickpair nennt abweichend 0x100/0x200 (links)
     * und 0x1000/0x2000 (rechts). Die beiden Belegungen ueberschneiden sich
     * (0x200 waere dort „hoch links“, hier „runter links“), also ist nur eine
     * waehlbar — gewaehlt ist die von zwei Quellen gestuetzte. Auf dem Geraet
     * zu pruefen.
     */
    const val RIDE_SHIFT_UP_LEFT = 0x200L
    const val RIDE_SHIFT_DOWN_LEFT = 0x400L
    const val RIDE_SHIFT_UP_RIGHT = 0x2000L
    const val RIDE_SHIFT_DOWN_RIGHT = 0x4000L

    /**
     * Ab so vielen Bytes nach „RideOn“ gilt eine Handschlag-Antwort als
     * verschluesselt. Die Klartext-Antworten der Quellen tragen hoechstens zwei
     * Kopfbytes (`01 03`, `00 09`, `02 03`); die verschluesselte Antwort
     * zwei Kopfbytes plus 64 Byte Schluessel (Makinolo). Bei der Standard-MTU
     * kommen davon nur 20 Byte an, also 14 nach „RideOn“ — die Schwelle 10
     * liegt sicher zwischen beiden Faellen.
     */
    const val ENCRYPTED_MIN_TAIL = 10

    /** Handschlag-Bytes fuer die Variante. */
    fun handshake(variant: ClickVariant): ByteArray = when (variant) {
        ClickVariant.V1 -> HANDSHAKE_V1.copyOf()
        ClickVariant.V2 -> HANDSHAKE_V2.copyOf()
    }

    /**
     * Ordnet eine Indication auf `ZWIFT_SYNC_TX` ein.
     *  * Erstes Byte 0xFF → [HandshakeAnswer.LOCKED]: gesperrte Click V2 schickt
     *    eine Challenge statt des Echos (qdomyos-zwift PR #4743).
     *  * Praefix „RideOn“ und hoechstens ein paar Kopfbytes → [HandshakeAnswer.OK]
     *    (Torqa, GearUp: Echo). Die genaue Laenge ist unsicher, deshalb nur
     *    das Praefix pruefen (BikeControl prueft `RideOn 01 03`).
     *  * Praefix „RideOn“ mit angehaengtem Schluessel (siehe
     *    [ENCRYPTED_MIN_TAIL]) → [HandshakeAnswer.ENCRYPTED]: alte Firmware,
     *    die ECDH + AES-CCM erwartet (Makinolo, Torqa-Warnung).
     *  * Alles andere → [HandshakeAnswer.UNKNOWN] (nicht entscheidbar; warten).
     */
    fun classifyHandshakeAnswer(bytes: ByteArray): HandshakeAnswer {
        if (bytes.isEmpty()) return HandshakeAnswer.UNKNOWN
        if (bytes[0] == 0xFF.toByte()) return HandshakeAnswer.LOCKED
        if (bytes.size < RIDE_ON.size) return HandshakeAnswer.UNKNOWN
        for (i in RIDE_ON.indices) if (bytes[i] != RIDE_ON[i]) return HandshakeAnswer.UNKNOWN
        val tail = bytes.size - RIDE_ON.size
        return if (tail >= ENCRYPTED_MIN_TAIL) HandshakeAnswer.ENCRYPTED else HandshakeAnswer.OK
    }

    /** Liest einen Rahmen von `ZWIFT_ASYNC`. Wirft nie. */
    fun parseFrame(bytes: ByteArray): ClickFrame {
        if (bytes.isEmpty()) return ClickFrame.Malformed("leerer Rahmen")
        val type = bytes[0].toInt() and 0xFF
        return when (type) {
            TYPE_CLICK_BUTTONS -> parseClickButtons(bytes)
            TYPE_RIDE_BUTTONS -> parseRideButtons(bytes)
            TYPE_BATTERY -> parseBattery(bytes)
            TYPE_IDLE -> ClickFrame.Idle
            TYPE_PLAY_BUTTONS -> ClickFrame.Ignored(type, "Zwift-Play-Rahmen, kein Schalttaster")
            else -> ClickFrame.Ignored(type, "unbekannter Rahmentyp")
        }
    }

    /*
     * Click V1. Wert 0 = gedrueckt, alles andere = losgelassen (BikeControl).
     * Fehlendes Feld = losgelassen (click2keyboard) — streng nach proto3 waere
     * ein fehlendes Feld der Vorgabewert 0, also „gedrueckt“; die Mitschnitte
     * (jat255) zeigen aber immer beide Felder ausdruecklich, auch die Nullen.
     * Ein unvollstaendiger Rahmen wird verworfen: Er koennte eine gedrueckte
     * Taste als losgelassen vortaeuschen und so einen Doppelschalter ausloesen.
     * UNSICHER bleibt die Deutung „fehlend = losgelassen“: Sendet eine Firmware
     * doch proto3-sparsam, hiesse ein fehlendes Feld „gedrueckt“ und die Taste
     * waere stumm. Deshalb markiert [ClickFrame.Buttons.missingFields] solche
     * Rahmen; der Automat schreibt sie in seine Diagnose, damit es im
     * Geraetetest sofort auffaellt.
     */
    private fun parseClickButtons(bytes: ByteArray): ClickFrame {
        val f = parseProtoFields(bytes, 1)
        if (!f.complete) return ClickFrame.Malformed("Click-Rahmen unvollstaendig")
        val plus = f.varints[1]?.let { it == 0L } ?: false
        val minus = f.varints[2]?.let { it == 0L } ?: false
        val missing = (if (1 in f.varints) 0 else 1) + (if (2 in f.varints) 0 else 1)
        return ClickFrame.Buttons(plus = plus, minus = minus, variant = ClickVariant.V1, missingFields = missing)
    }

    /*
     * Ride / Click V2. ButtonMap invertiert: ein Bit 0 heisst gedrueckt
     * (Makinolo). Leerlauf `08 FF FF FF FF 0F` = 0xFFFFFFFF (GearUp; als
     * 0x0FFFFFFF notiert waere es fuer die Schaltbits gleichwertig). Beide
     * Pods: oben = UP, unten = DOWN. Hier genuegt Feld 1 — die analogen Felder dahinter werden bei
     * kleiner MTU oft abgeschnitten, das schadet nicht, solange Feld 1 ganz da
     * ist. Fehlt Feld 1, gibt es keine Aussage (proto3-Vorgabe 0 hiesse „alles
     * gedrueckt“ — unplausibel), also verwerfen.
     */
    private fun parseRideButtons(bytes: ByteArray): ClickFrame {
        val f = parseProtoFields(bytes, 1)
        val map = f.varints[1] ?: return ClickFrame.Malformed("Ride-Rahmen ohne ButtonMap")
        val pressed = map.inv() and 0xFFFFFFFFL
        val up = pressed and (RIDE_SHIFT_UP_LEFT or RIDE_SHIFT_UP_RIGHT) != 0L
        val down = pressed and (RIDE_SHIFT_DOWN_LEFT or RIDE_SHIFT_DOWN_RIGHT) != 0L
        return ClickFrame.Buttons(plus = up, minus = down, variant = ClickVariant.V2)
    }

    private fun parseBattery(bytes: ByteArray): ClickFrame {
        val f = parseProtoFields(bytes, 1)
        val p = f.varints[2] ?: return ClickFrame.Malformed("Batterierahmen ohne Feld 2")
        if (p !in 0L..100L) return ClickFrame.Malformed("Batteriewert ausserhalb 0..100")
        return ClickFrame.Battery(p.toInt())
    }
}

/** Generation des Schalttasters: V1 = Click mit Dienst `00000001-…`, V2 = Click V2 / Ride mit Dienst `fc82`. */
internal enum class ClickVariant { V1, V2 }

/** Einordnung der Handschlag-Antwort, siehe [ZwiftClickProtocol.classifyHandshakeAnswer]. */
internal enum class HandshakeAnswer { OK, ENCRYPTED, LOCKED, UNKNOWN }

/** Ergebnis von [ZwiftClickProtocol.parseFrame]. */
internal sealed interface ClickFrame {
    /**
     * Vollstaendiger Tastenzustand.
     *
     * @property plus „Plus“ (V1) bzw. ein Hoch-Schalter (V2) ist gedrueckt → [ShifterEvent.UP].
     * @property minus „Minus“ (V1) bzw. ein Runter-Schalter (V2) ist gedrueckt → [ShifterEvent.DOWN].
     * @property missingFields nur V1: wie viele der beiden Tastenfelder fehlten
     *   (als losgelassen gewertet, siehe Unsicherheit bei proto3); V2 immer 0.
     */
    data class Buttons(
        val plus: Boolean,
        val minus: Boolean,
        val variant: ClickVariant,
        val missingFields: Int = 0,
    ) : ClickFrame

    /** Ladestand in Prozent (0..100). */
    data class Battery(val percent: Int) : ClickFrame

    /** Leerlaufrahmen (0x15). */
    data object Idle : ClickFrame

    /** Wohlgeformt genug, aber nicht fuer uns (Play, unbekannter Typ). */
    data class Ignored(val type: Int, val why: String) : ClickFrame

    /** Kaputt oder abgeschnitten; verworfen. */
    data class Malformed(val why: String) : ClickFrame
}

/** Ergebnis von [readVarint]: Wert und Index hinter dem letzten Byte. */
internal data class Varint(val value: Long, val next: Int)

/**
 * Liest einen Protobuf-Varint ab [start] (7 Bit je Byte, niedrigstes zuerst,
 * hoechstes Bit = „es folgt mehr“). `null`, wenn die Bytes vorher enden oder
 * der Varint laenger als 10 Byte waere (mehr passt nicht in 64 Bit).
 */
internal fun readVarint(bytes: ByteArray, start: Int): Varint? {
    if (start < 0) return null
    var result = 0L
    var shift = 0
    var i = start
    while (i < bytes.size && shift <= 63) {
        val b = bytes[i].toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        i++
        if (b and 0x80 == 0) return Varint(result, i)
        shift += 7
    }
    return null
}

/**
 * Die Varint-Felder einer Protobuf-Nachricht.
 *
 * @property varints Feldnummer → Wert (bei Wiederholung gilt wie bei Protobuf der letzte).
 * @property complete `false`, wenn die Nachricht vorzeitig endet oder Unsinn
 *   enthaelt; [varints] haelt dann alles, was bis dahin sauber gelesen war.
 */
internal data class ProtoFields(val varints: Map<Int, Long>, val complete: Boolean)

/**
 * Zerlegt [bytes] ab [start] als Protobuf. Andere Wire-Typen (64 Bit,
 * laengenbegrenzt, 32 Bit) werden uebersprungen; Gruppen (3/4) und Feldnummer 0
 * gibt es hier nicht und gelten als kaputt.
 */
internal fun parseProtoFields(bytes: ByteArray, start: Int): ProtoFields {
    val out = LinkedHashMap<Int, Long>()
    var i = start
    while (i < bytes.size) {
        val tag = readVarint(bytes, i) ?: return ProtoFields(out, false)
        val field = tag.value ushr 3
        if (field <= 0L || field > Int.MAX_VALUE) return ProtoFields(out, false)
        i = tag.next
        when ((tag.value and 7L).toInt()) {
            0 -> {
                val v = readVarint(bytes, i) ?: return ProtoFields(out, false)
                out[field.toInt()] = v.value
                i = v.next
            }
            1 -> i = skip(bytes, i, 8L) ?: return ProtoFields(out, false)
            2 -> {
                val len = readVarint(bytes, i) ?: return ProtoFields(out, false)
                i = skip(bytes, len.next, len.value) ?: return ProtoFields(out, false)
            }
            5 -> i = skip(bytes, i, 4L) ?: return ProtoFields(out, false)
            else -> return ProtoFields(out, false)
        }
    }
    return ProtoFields(out, true)
}

private fun skip(bytes: ByteArray, from: Int, count: Long): Int? {
    if (count < 0 || count > bytes.size - from) return null
    return from + count.toInt()
}
