package de.trailscape.core

/**
 * Die drei Bluetooth-LE-Messformate, die Trailscape von Sensoren liest —
 * als reine Parser ohne Android-Bezug.
 *
 * ## Warum hier und nicht in `:app`
 * Die Bytes eines Pulsgurts oder Leistungsmessers sind das Einzige an der
 * Bluetooth-Anbindung, das sich ohne Funk exakt pruefen laesst. Die GATT-Seite
 * in `:app` reicht die Nutzlast einer Notification unveraendert hierher; alle
 * Fallunterscheidungen (Flags, Feldbreiten, Einheiten) stehen damit an einer
 * Stelle und sind mit den Byte-Beispielen der Bluetooth-Spezifikation getestet.
 *
 * ## Grundsaetze aller Parser
 *  * little-endian, wie jedes GATT-Feld;
 *  * vorzeichenlose Felder werden vorzeichenlos gelesen (`uint32` als [Long]);
 *  * eine zu kurze Nutzlast ergibt `null` — niemals eine Ausnahme, denn ein
 *    kaputtes Paket eines billigen Sensors darf die Aufzeichnung nicht beenden;
 *  * Felder hinter den gelesenen werden ignoriert (neuere Sensoren haengen an).
 *
 * ## Zeitbasen
 * Die „letzte Ereigniszeit" der Umdrehungen ist ein 16-Bit-Tickzaehler. Bei
 * Cycling Speed and Cadence (0x2A5B) und bei den Kurbeldaten von Cycling Power
 * (0x2A63) laeuft er mit 1/1024 s, bei den **Rad**daten von Cycling Power aber
 * mit 1/2048 s — so steht es in der Spezifikation, und wer das uebersieht,
 * rechnet das Radtempo doppelt so schnell. Die Umrechnung macht
 * [DrehzahlRechner] mit der passenden Fabrik.
 */

/** Volle 128-Bit-UUIDs der verwendeten GATT-Dienste und -Characteristics. */
object BleUuids {
    private fun kurz(hex: String) = "0000$hex-0000-1000-8000-00805f9b34fb"

    val DIENST_HERZFREQUENZ = kurz("180d")
    val DIENST_CYCLING_POWER = kurz("1818")
    val DIENST_CSC = kurz("1816")

    val MESSUNG_HERZFREQUENZ = kurz("2a37")
    val MESSUNG_CYCLING_POWER = kurz("2a63")
    val MESSUNG_CSC = kurz("2a5b")

    /** Client Characteristic Configuration Descriptor — schaltet Notifications ein. */
    val CCCD = kurz("2902")
}

/** Die drei Sensorarten, je mit ihrem Dienst und ihrer Messwert-Characteristic. */
enum class BleSensorTyp(val dienstUuid: String, val messungUuid: String) {
    PULS(BleUuids.DIENST_HERZFREQUENZ, BleUuids.MESSUNG_HERZFREQUENZ),
    LEISTUNG(BleUuids.DIENST_CYCLING_POWER, BleUuids.MESSUNG_CYCLING_POWER),
    TRITTFREQUENZ(BleUuids.DIENST_CSC, BleUuids.MESSUNG_CSC),
}

/**
 * Welche Sensorarten eine Liste beworbener Dienst-UUIDs abdeckt.
 *
 * Versteht volle UUIDs in beliebiger Schreibweise und die 16-Bit-Kurzform
 * (`"180D"`, `"0x180d"`), weil Scan-Ergebnisse je nach Stack beides liefern.
 */
fun sensorTypenAusDiensten(uuids: Collection<String>): Set<BleSensorTyp> {
    val normiert = uuids.map { normiereUuid(it) }.toSet()
    return BleSensorTyp.entries.filter { it.dienstUuid in normiert }.toSet()
}

private fun normiereUuid(raw: String): String {
    val s = raw.trim().lowercase().removePrefix("0x")
    return if (s.length == 4 && s.all { it.isDigit() || it in 'a'..'f' }) {
        "0000$s-0000-1000-8000-00805f9b34fb"
    } else {
        s
    }
}

// ---------------------------------------------------------------------------
// Byte-Leser
// ---------------------------------------------------------------------------

/** Liest little-endian aus [b]; jede Methode liefert `null`, wenn die Bytes nicht reichen. */
private class Leser(private val b: ByteArray) {
    var pos = 0
        private set

    val rest: Int get() = b.size - pos

    fun u8(): Int? {
        if (rest < 1) return null
        return b[pos++].toInt() and 0xFF
    }

    fun u16(): Int? {
        if (rest < 2) return null
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun s16(): Int? = u16()?.let { if (it >= 0x8000) it - 0x10000 else it }

    fun u32(): Long? {
        if (rest < 4) return null
        var v = 0L
        for (i in 0 until 4) {
            v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
        }
        pos += 4
        return v
    }

    fun ueberspringe(n: Int): Boolean {
        if (rest < n) return false
        pos += n
        return true
    }
}

private fun bit(flags: Int, n: Int): Boolean = (flags shr n) and 1 == 1

// ---------------------------------------------------------------------------
// Herzfrequenz (0x2A37)
// ---------------------------------------------------------------------------

/**
 * Eine Herzfrequenz-Messung.
 *
 * @property sensorKontakt `true`/`false`, wenn der Gurt Hautkontakt meldet;
 *   `null`, wenn er die Erkennung nicht unterstuetzt.
 * @property energieKj kumulierter Energieumsatz, falls der Gurt ihn mitsendet.
 * @property rrMs RR-Intervalle in Millisekunden (Spezifikation: 1/1024 s).
 */
data class PulsMessung(
    val bpm: Int,
    val sensorKontakt: Boolean?,
    val energieKj: Int?,
    val rrMs: List<Double>,
)

/**
 * Parst eine Heart Rate Measurement.
 *
 * Flags: Bit 0 = HF als uint16 statt uint8, Bit 1 = Kontakt erkannt,
 * Bit 2 = Kontakterkennung unterstuetzt, Bit 3 = Energie (uint16) folgt,
 * Bit 4 = RR-Intervalle (je uint16) folgen bis zum Ende. Ein ungerades
 * Restbyte am Ende gehoert zu keinem RR-Wert und wird ignoriert.
 */
fun parsePulsMessung(b: ByteArray): PulsMessung? {
    val r = Leser(b)
    val flags = r.u8() ?: return null
    val bpm = (if (bit(flags, 0)) r.u16() else r.u8()) ?: return null
    val kontakt = if (bit(flags, 2)) bit(flags, 1) else null
    val energie = if (bit(flags, 3)) (r.u16() ?: return null) else null
    val rr = mutableListOf<Double>()
    if (bit(flags, 4)) {
        while (r.rest >= 2) {
            val roh = r.u16() ?: break
            rr += roh * 1000.0 / 1024.0
        }
    }
    return PulsMessung(bpm = bpm, sensorKontakt = kontakt, energieKj = energie, rrMs = rr)
}

// ---------------------------------------------------------------------------
// Umdrehungen (Cycling Power, CSC)
// ---------------------------------------------------------------------------

/**
 * Kumulierter Umdrehungszaehler plus Zeitpunkt der letzten Umdrehung.
 *
 * @property zaehler Anzahl Umdrehungen seit Sensorstart (Rad: uint32,
 *   Kurbel: uint16 — beide laufen ueber).
 * @property ereignisTicks 16-Bit-Zeitstempel der letzten Umdrehung in der
 *   Zeitbasis des Formats (siehe Datei-KDoc).
 */
data class Umdrehungen(val zaehler: Long, val ereignisTicks: Int)

// ---------------------------------------------------------------------------
// Cycling Power (0x2A63)
// ---------------------------------------------------------------------------

/** Eine Cycling-Power-Messung: Momentanleistung plus optionale Rad-/Kurbeldaten. */
data class LeistungsMessung(
    val watt: Int,
    /** Radumdrehungen, Ereigniszeit in 1/2048 s. */
    val rad: Umdrehungen?,
    /** Kurbelumdrehungen, Ereigniszeit in 1/1024 s. */
    val kurbel: Umdrehungen?,
)

/**
 * Parst eine Cycling Power Measurement.
 *
 * Aufbau: Flags (uint16), momentane Leistung (sint16, W), dann die optionalen
 * Felder in Spezifikationsreihenfolge — Bit 0 Pedal-Balance (1 B), Bit 2
 * akkumuliertes Drehmoment (2 B), Bit 4 Raddaten (uint32 + uint16),
 * Bit 5 Kurbeldaten (uint16 + uint16). Alles danach (Extremwerte, Winkel,
 * Totpunkte, Energie) braucht Trailscape nicht und wird ignoriert.
 */
fun parseLeistungsMessung(b: ByteArray): LeistungsMessung? {
    val r = Leser(b)
    val flags = r.u16() ?: return null
    val watt = r.s16() ?: return null
    if (bit(flags, 0) && !r.ueberspringe(1)) return null
    if (bit(flags, 2) && !r.ueberspringe(2)) return null
    val rad = if (bit(flags, 4)) {
        val zaehler = r.u32() ?: return null
        val ticks = r.u16() ?: return null
        Umdrehungen(zaehler, ticks)
    } else {
        null
    }
    val kurbel = if (bit(flags, 5)) {
        val zaehler = r.u16() ?: return null
        val ticks = r.u16() ?: return null
        Umdrehungen(zaehler.toLong(), ticks)
    } else {
        null
    }
    return LeistungsMessung(watt = watt, rad = rad, kurbel = kurbel)
}

// ---------------------------------------------------------------------------
// Cycling Speed and Cadence (0x2A5B)
// ---------------------------------------------------------------------------

/** Eine CSC-Messung; beide Ereigniszeiten in 1/1024 s. */
data class CscMessung(val rad: Umdrehungen?, val kurbel: Umdrehungen?)

/**
 * Parst eine CSC Measurement: Flags (uint8), Bit 0 Raddaten (uint32 + uint16),
 * Bit 1 Kurbeldaten (uint16 + uint16).
 */
fun parseCscMessung(b: ByteArray): CscMessung? {
    val r = Leser(b)
    val flags = r.u8() ?: return null
    val rad = if (bit(flags, 0)) {
        val zaehler = r.u32() ?: return null
        val ticks = r.u16() ?: return null
        Umdrehungen(zaehler, ticks)
    } else {
        null
    }
    val kurbel = if (bit(flags, 1)) {
        val zaehler = r.u16() ?: return null
        val ticks = r.u16() ?: return null
        Umdrehungen(zaehler.toLong(), ticks)
    } else {
        null
    }
    return CscMessung(rad = rad, kurbel = kurbel)
}
