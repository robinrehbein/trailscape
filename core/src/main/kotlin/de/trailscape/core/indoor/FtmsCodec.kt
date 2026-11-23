package de.trailscape.core.indoor

import kotlin.math.roundToLong

/**
 * # Die Bytes des Fitness Machine Service (FTMS)
 *
 * Reine Parser und Kodierer ohne Zustand, Grundlage von [FtmsTrainer]. Wie in
 * `BleMessformate.kt` gilt: little-endian, vorzeichenlose Felder vorzeichenlos
 * gelesen, eine zu kurze Nutzlast wirft nie — ein kaputtes Paket eines
 * Trainers darf die Fahrt nicht beenden.
 *
 * ## Quellen
 *  * **[FTMS]** Bluetooth SIG, „Fitness Machine Service 1.0“ (2017):
 *    §4.3 Fitness Machine Feature, §4.9 Indoor Bike Data (mit §4.1 und §4.19
 *    zum Verteilen eines Datensatzes auf mehrere Notifications), §4.14
 *    Supported Power Range, §4.16 Fitness Machine Control Point (§4.16.1
 *    Anforderungen, §4.16.2.1 Request Control, §4.16.2.9 Stop or Pause,
 *    §4.16.2.18 Set Indoor Bike Simulation Parameters, §4.16.2.22 Procedure
 *    Complete, §4.16.4 Procedure Timeout), §4.17 Fitness Machine Status.
 *  * **[GSS]** Bluetooth SIG, „GATT Specification Supplement“ (v9): Felder,
 *    Einheiten und Aufloesungen der Characteristics, u. a. §3.206 Supported
 *    Power Range sowie die Abschnitte „Indoor Bike Data“, „Fitness Machine
 *    Feature“, „Fitness Machine Control Point“, „Fitness Machine Status“
 *    (deren Abschnittsnummern sind hier nicht gegengeprueft — Annahme).
 *  * **[PYC]** pycycling (`ftms_parsers/indoor_bike_data.py`,
 *    `fitness_machine_feature.py`, `control_point.py`) als unabhaengige
 *    Gegenprobe der Bitbelegungen und Antwortcodes.
 *
 * ## Die bekannte Falle: Bit 2 „Instantaneous Cadence“
 * Die Tabelle 4.10 in [FTMS] 1.0 zeigt fuer Bit 2 dieselbe *invertierte*
 * Polaritaet wie fuer Bit 0 („More Data“). [GSS] v9 sagt dagegen
 * „present if set“, und die SIG hat das als Erratum (Errata 23224) bestaetigt
 * (vgl. openzigs/onyourleft Issue #43, [PYC]). Alle verbreiteten Trainer
 * senden die Kadenz mit gesetztem Bit 2 — so wird hier gelesen. Allein
 * Bit 0 ist invertiert: 0 = Instantaneous Speed folgt.
 */
internal object FtmsCodec {
    // ------------------------------------------------------------ Control Point, [FTMS] §4.16.2

    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_OR_RESUME = 0x07
    const val OP_STOP_OR_PAUSE = 0x08
    const val OP_SET_SIMULATION = 0x11
    const val OP_RESPONSE_CODE = 0x80

    // ------------------------------------------------------------ Ergebniscodes, [FTMS] §4.16.2.22

    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_FAILED = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    // ------------------------------------------------------------ Machine Status, [FTMS] §4.17

    const val STATUS_RESET = 0x01
    const val STATUS_STOPPED_OR_PAUSED_BY_USER = 0x02
    const val STATUS_STOPPED_BY_SAFETY_KEY = 0x03
    const val STATUS_STARTED_OR_RESUMED_BY_USER = 0x04
    const val STATUS_TARGET_POWER_CHANGED = 0x08
    const val STATUS_SIMULATION_PARAMETERS_CHANGED = 0x12
    const val STATUS_CONTROL_PERMISSION_LOST = 0xFF

    /** Obergrenze plausibler Leistung; was darueber liegt, ist ein Messfehler. */
    const val MAX_PLAUSIBLE_POWER_W = 3000

    /** Obergrenze plausibler Trittfrequenz. */
    const val MAX_PLAUSIBLE_CADENCE_RPM = 250.0

    // ======================================================================
    // Indoor Bike Data (0x2AD2)
    // ======================================================================

    /**
     * Parst ein Indoor-Bike-Data-Paket ([FTMS] §4.9, [GSS] „Indoor Bike Data“).
     *
     * Flags uint16; dann in Bitreihenfolge:
     *
     * | Bit | Feld | Format |
     * |---|---|---|
     * | 0 = **0** | Instantaneous Speed | uint16, 0,01 km/h |
     * | 1 | Average Speed | uint16 |
     * | 2 | Instantaneous Cadence | uint16, 0,5 /min (siehe Datei-KDoc zur Polaritaet) |
     * | 3 | Average Cadence | uint16 |
     * | 4 | Total Distance | uint24, m |
     * | 5 | Resistance Level | sint16 (Annahme; neuere GSS-Fassungen aendern Breite/Aufloesung — fuer Trailscape egal, wird uebersprungen) |
     * | 6 | Instantaneous Power | sint16, W |
     * | 7 | Average Power | sint16 |
     * | 8 | Expended Energy | uint16 + uint16 + uint8 |
     * | 9 | Heart Rate | uint8 |
     * | 10 | Metabolic Equivalent | uint8 |
     * | 11 | Elapsed Time | uint16 |
     * | 12 | Remaining Time | uint16 |
     *
     * Nach [FTMS] §4.19 darf ein Trainer einen Datensatz auf mehrere
     * Notifications verteilen („More Data“ = 1 in allen ausser der letzten);
     * jede traegt nur einen Teil der Felder. Jedes Paket wird hier fuer sich
     * gelesen: Was fehlt, ist `null` (ein Paket mit „More Data“ = 1 hat kein
     * Tempo). Ist ein Paket kuerzer, als die Flags versprechen, gilt alles bis
     * zur Luecke; der Rest ist `null`. Weniger als 2 Byte → `null`.
     *
     * Unplausible Werte (Leistung < 0 oder > [MAX_PLAUSIBLE_POWER_W],
     * Kadenz > [MAX_PLAUSIBLE_CADENCE_RPM]) werden `null` statt geraten.
     */
    fun parseIndoorBikeData(bytes: ByteArray, receivedMs: Long): TrainerTelemetry? {
        val r = FtmsLeser(bytes)
        val flags = r.u16() ?: return null
        var speed: Double? = null
        var cadence: Double? = null
        var power: Int? = null
        run lesen@{
            if (!bit(flags, 0)) speed = (r.u16() ?: return@lesen) / 100.0
            if (bit(flags, 1) && !r.ueberspringe(2)) return@lesen
            if (bit(flags, 2)) cadence = (r.u16() ?: return@lesen) / 2.0
            if (bit(flags, 3) && !r.ueberspringe(2)) return@lesen
            if (bit(flags, 4) && !r.ueberspringe(3)) return@lesen
            if (bit(flags, 5) && !r.ueberspringe(2)) return@lesen
            if (bit(flags, 6)) power = r.s16() ?: return@lesen
            // Bit 7..12 braucht Trailscape nicht; sie stehen hinter allem Gelesenen.
        }
        val p = power?.takeIf { it in 0..MAX_PLAUSIBLE_POWER_W }
        val c = cadence?.takeIf { it <= MAX_PLAUSIBLE_CADENCE_RPM }
        return TrainerTelemetry(powerW = p, cadenceRpm = c, speedKmh = speed, receivedMs = receivedMs)
    }

    // ======================================================================
    // Fitness Machine Feature (0x2ACC), Supported Power Range (0x2AD8)
    // ======================================================================

    /**
     * Fitness Machine Feature ([FTMS] §4.3): zwei uint32, erst die
     * Maschinenmerkmale (Bit 1 Cadence Supported, Bit 14 Power Measurement
     * Supported, §4.3.1.1), dann die Zielvorgaben (Bit 3 Power Target Setting
     * Supported, Bit 13 Indoor Bike Simulation Parameters Supported,
     * §4.3.1.2). Ohne Ranges — die kommen aus [parsePowerRange].
     *
     * Zu kurz fuer das erste Feld → [TrainerCapabilities.NONE]; nur das erste
     * Feld → keine Zielvorgaben.
     */
    fun parseFeature(bytes: ByteArray): TrainerCapabilities {
        val r = FtmsLeser(bytes)
        val machine = r.u32() ?: return TrainerCapabilities.NONE
        val target = r.u32() ?: 0L
        return TrainerCapabilities(
            simulation = bitL(target, 13),
            targetPower = bitL(target, 3),
            reportsCadence = bitL(machine, 1),
        )
    }

    /** Bit 14 der Maschinenmerkmale: der Trainer misst Leistung. Nur zur Diagnose. */
    fun reportsPower(bytes: ByteArray): Boolean = FtmsLeser(bytes).u32()?.let { bitL(it, 14) } ?: false

    /**
     * Supported Power Range ([FTMS] §4.14, [GSS] §3.206): sint16 Minimum,
     * sint16 Maximum, uint16 Schrittweite (ignoriert). `null` bei weniger als
     * 4 Byte oder Minimum > Maximum.
     */
    fun parsePowerRange(bytes: ByteArray): Pair<Int, Int>? {
        val r = FtmsLeser(bytes)
        val min = r.s16() ?: return null
        val max = r.s16() ?: return null
        return if (min <= max) min to max else null
    }

    // ======================================================================
    // Control Point (0x2AD9): Kodierer
    // ======================================================================

    /** Request Control ([FTMS] §4.16.2.1). */
    fun requestControl(): ByteArray = bytes(OP_REQUEST_CONTROL)

    /** Reset ([FTMS] §4.16.2.2). Achtung: Danach ist die Kontrolle weg (§4.16). */
    fun reset(): ByteArray = bytes(OP_RESET)

    /** Set Target Power ([FTMS] §4.16.2.6): sint16 in W, gerundet und geklemmt. */
    fun setTargetPower(watts: Int): ByteArray = bytes(OP_SET_TARGET_POWER) + s16(watts.toDouble())

    /** Start or Resume ([FTMS] §4.16.2.8). */
    fun startOrResume(): ByteArray = bytes(OP_START_OR_RESUME)

    /** Stop or Pause mit Parameter 0x01 = Stopp ([FTMS] §4.16.2.9). */
    fun stop(): ByteArray = bytes(OP_STOP_OR_PAUSE, 0x01)

    /** Stop or Pause mit Parameter 0x02 = Pause ([FTMS] §4.16.2.9). */
    fun pause(): ByteArray = bytes(OP_STOP_OR_PAUSE, 0x02)

    /**
     * Set Indoor Bike Simulation Parameters ([FTMS] §4.16.2.18):
     * Wind sint16 (0,001 m/s), Steigung sint16 (0,01 %), Crr uint8 (0,0001),
     * Cw uint8 (0,01 kg/m). Jeder Wert wird gerundet und auf den Bereich
     * seines Feldes geklemmt (sint16 ±32767, uint8 0..255); NaN → 0.
     *
     * Hinweis: Trainer begrenzen die Steigung selbst, ein KICKR v5 etwa auf
     * −10..+20 % (Wahoo-Support-Artikel 29235861059986). Das ist kein
     * Fehler und wird nicht gemeldet.
     */
    fun setSimulation(windMps: Double, gradePct: Double, crr: Double, cwKgPerM: Double): ByteArray =
        bytes(OP_SET_SIMULATION) +
            s16(windMps / 0.001) +
            s16(gradePct / 0.01) +
            u8(crr / 0.0001) +
            u8(cwKgPerM / 0.01)

    // ======================================================================
    // Antworten
    // ======================================================================

    /** Eine Control-Point-Antwort `80 <opcode> <result>` ([FTMS] §4.16.2.22). */
    data class Antwort(val opcode: Int, val result: Int)

    /** `null`, wenn es keine Antwort ist (kuerzer als 3 Byte oder erstes Byte nicht 0x80). Zusatzbytes werden ignoriert. */
    fun parseControlPointResponse(bytes: ByteArray): Antwort? {
        if (bytes.size < 3 || (bytes[0].toInt() and 0xFF) != OP_RESPONSE_CODE) return null
        return Antwort(bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
    }

    /** Opcode einer Machine-Status-Meldung ([FTMS] §4.17), `null` bei leerer Nutzlast. */
    fun parseMachineStatus(bytes: ByteArray): Int? = if (bytes.isEmpty()) null else bytes[0].toInt() and 0xFF

    /** Kurzer Diagnosename eines Ergebniscodes. */
    fun resultName(result: Int): String = when (result) {
        RESULT_SUCCESS -> "Erfolg"
        RESULT_NOT_SUPPORTED -> "nicht unterstuetzt"
        RESULT_INVALID_PARAMETER -> "ungueltiger Parameter"
        RESULT_FAILED -> "fehlgeschlagen"
        RESULT_CONTROL_NOT_PERMITTED -> "Kontrolle nicht erlaubt"
        else -> "unbekannt"
    } + " (0x" + result.toString(16).padStart(2, '0') + ")"

    // ------------------------------------------------------------ Hilfen

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun gerundet(v: Double, min: Long, max: Long): Long =
        if (v.isNaN()) 0L else v.coerceIn(min.toDouble(), max.toDouble()).roundToLong().coerceIn(min, max)

    private fun s16(v: Double): ByteArray {
        val x = gerundet(v, -32767, 32767).toInt()
        return byteArrayOf((x and 0xFF).toByte(), ((x shr 8) and 0xFF).toByte())
    }

    private fun u8(v: Double): ByteArray = byteArrayOf(gerundet(v, 0, 255).toInt().toByte())

    private fun bit(flags: Int, n: Int): Boolean = (flags shr n) and 1 == 1

    private fun bitL(flags: Long, n: Int): Boolean = (flags shr n) and 1L == 1L
}

/** Little-endian-Leser; jede Methode liefert `null`, wenn die Bytes nicht reichen. */
private class FtmsLeser(private val b: ByteArray) {
    private var pos = 0
    private val rest: Int get() = b.size - pos

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
        for (i in 0 until 4) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun ueberspringe(n: Int): Boolean {
        if (rest < n) return false
        pos += n
        return true
    }
}
