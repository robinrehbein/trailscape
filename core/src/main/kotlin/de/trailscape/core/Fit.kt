package de.trailscape.core

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/**
 * Minimaler FIT-Decoder fuer Trailscape (Garmin/Wahoo/Zwift-Aktivitaeten).
 *
 * Bewusst **ohne** das offizielle Garmin-FIT-SDK: dessen Lizenz ist fuer eine
 * quelloffene App sperrig und es bringt den kompletten Message-Katalog samt
 * Encoder mit — fuer den Import einer Tour brauchen wir davon einen winzigen
 * Bruchteil. Dieser Decoder ist reines Kotlin/JVM (nur `java.util.zip` fuer
 * `.fit.gz`) und liest genau die Felder, die [TrackPoint] fuellen.
 *
 * ## Was gelesen wird
 *  * **file_id (global 0)**: `type` (4 = activity) — nur zur Information.
 *  * **session (18)** / **lap (19)**: `sport` (2 = cycling) und `start_time`
 *    fuer Name und Startzeitpunkt.
 *  * **record (20)**: `timestamp`, `position_lat`/`position_long`
 *    (Semicircles), `altitude` bzw. `enhanced_altitude`, `heart_rate`.
 *
 * ## Was der Decoder bewusst NICHT kann
 *  * Keine weiteren Messages (Power, Trittfrequenz, Temperatur, Runden-
 *    Kennzahlen, HRV, Events, Kurse/Workouts) — alles andere wird anhand
 *    seiner Definition uebersprungen.
 *  * Keine **Developer Fields** inhaltlich: ihre Groesse wird aus der
 *    Definition gelesen und der Block ueberlesen (`field_description`,
 *    global 206, wird ignoriert).
 *  * Keine **String-/Array-Felder**: von einem Feld wird nur das erste
 *    Element des Basistyps ausgewertet, Strings werden ignoriert.
 *  * Keine **accumulated/component-Felder** (z. B. `compressed_speed_distance`)
 *    und keine Profil-Skalierung ausser den hier fest verdrahteten
 *    Hoehen-/Semicircle-Umrechnungen.
 *  * Kein Encoder — Trailscape schreibt weiterhin GPX ([buildGpx]).
 *
 * ## Toleranzen
 * Viele Geraete schreiben kaputte CRCs oder eine falsche `dataSize` im Header.
 * Beides wird geprueft, aber **nicht** als Fehler gewertet: die CRC-Lage steht
 * in [FitParseResult.crcValid], die Datengroesse wird auf das tatsaechlich
 * Vorhandene begrenzt. Erst wenn gar keine Trackpunkte herauskommen, wirft der
 * Decoder eine [FormatException] (deutsche Meldung, Stil wie [parseGpx]).
 */

// ---------------------------------------------------------------------------
// Konstanten aus dem FIT-Profil
// ---------------------------------------------------------------------------

/** Sekunden zwischen Unix-Epoche und FIT-Epoche (1989-12-31T00:00:00Z). */
internal const val FIT_EPOCH_OFFSET_S: Long = 631_065_600L

/** Semicircles → Grad: `wert * 180 / 2^31`. */
private const val SEMICIRCLES_TO_DEGREES: Double = 180.0 / 2147483648.0

/** `sport`-Code fuer Radfahren im FIT-Profil. */
const val FIT_SPORT_CYCLING: Int = 2

/** `file_id.type`-Code fuer eine Aktivitaetsdatei. */
const val FIT_FILE_TYPE_ACTIVITY: Int = 4

private const val MSG_FILE_ID = 0
private const val MSG_SESSION = 18
private const val MSG_LAP = 19
private const val MSG_RECORD = 20

/** Feldnummer des vollen Zeitstempels — in jeder Message dieselbe. */
private const val FIELD_TIMESTAMP = 253

/**
 * Deutsche Bezeichnungen der FIT-Sportarten, soweit fuer Trailscape relevant.
 * Alles Uebrige faellt auf "Aktivitaet" zurueck.
 */
private val FIT_SPORT_LABELS: Map<Int, String> = mapOf(
    1 to "Lauf",
    FIT_SPORT_CYCLING to "Radfahrt",
    5 to "Schwimmen",
    11 to "Spaziergang",
    12 to "Skilanglauf",
    15 to "Rudern",
    17 to "Wanderung",
)

/** Datum im Namen einer importierten FIT-Tour — bewusst UTC, damit reproduzierbar. */
private val FIT_NAME_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC)

// ---------------------------------------------------------------------------
// Ergebnis
// ---------------------------------------------------------------------------

/**
 * Ergebnis von [parseFit] — dieselben Kernfelder wie [GpxParseResult]
 * ([name], [points]) plus das, was nur FIT liefert.
 */
data class FitParseResult(
    /** Aus Sportart + Startdatum gebildet, sonst der uebergebene Fallback-Name. */
    val name: String?,
    /** Alle Punkte **mit** Position, in Dateireihenfolge. */
    val points: List<TrackPoint>,
    /** `sport` aus session/lap (siehe [FIT_SPORT_CYCLING]), null wenn nicht angegeben. */
    val sport: Int? = null,
    /** `start_time` aus session/lap in ms seit Epoch, null wenn nicht angegeben. */
    val startTime: Long? = null,
    /** `file_id.type` (siehe [FIT_FILE_TYPE_ACTIVITY]), null wenn nicht angegeben. */
    val fileType: Int? = null,
    /** false, wenn die Datei-CRC nicht stimmte — der Inhalt wurde trotzdem gelesen. */
    val crcValid: Boolean = true,
) {
    /** true, wenn die Datei sich selbst als Radfahrt ausweist. */
    val isCycling: Boolean get() = sport == FIT_SPORT_CYCLING
}

// ---------------------------------------------------------------------------
// GZIP
// ---------------------------------------------------------------------------

/**
 * Entpackt GZIP-Daten transparent (Strava exportiert `.fit.gz`/`.gpx.gz`),
 * laesst alles andere unveraendert durch.
 */
internal fun gunzipIfNeeded(bytes: ByteArray): ByteArray {
    if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) return bytes
    return try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
    } catch (e: Exception) {
        throw FormatException("Die Datei ist GZIP-komprimiert, konnte aber nicht entpackt werden.")
    }
}

// ---------------------------------------------------------------------------
// CRC-16 (FIT-Variante, Nibble-Tabelle aus der Spezifikation)
// ---------------------------------------------------------------------------

private val CRC_TABLE = intArrayOf(
    0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
    0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
)

/** Fuehrt die FIT-CRC-16 ueber `bytes[from until to]` fort. */
internal fun fitCrc16(bytes: ByteArray, from: Int, to: Int, seed: Int = 0): Int {
    var crc = seed
    for (i in from until to) {
        val byte = bytes[i].toInt() and 0xFF
        var tmp = CRC_TABLE[crc and 0xF]
        crc = (crc ushr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
        tmp = CRC_TABLE[crc and 0xF]
        crc = (crc ushr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[(byte ushr 4) and 0xF]
    }
    return crc and 0xFFFF
}

// ---------------------------------------------------------------------------
// Basistypen
// ---------------------------------------------------------------------------

/** Groesse eines FIT-Basistyps in Bytes; 0 = unbekannt (Feld wird uebersprungen). */
private fun baseTypeSize(baseType: Int): Int = when (baseType) {
    0, 1, 2, 7, 10, 13 -> 1
    3, 4, 11 -> 2
    5, 6, 8, 12 -> 4
    9, 14, 15, 16 -> 8
    else -> 0
}

/** Roh-Bitmuster, das laut FIT-Spezifikation "kein Wert" bedeutet. */
private fun invalidRaw(baseType: Int): Long = when (baseType) {
    1 -> 0x7FL
    3 -> 0x7FFFL
    5 -> 0x7FFF_FFFFL
    7, 10, 11, 12, 16 -> 0L
    9, 15 -> -1L
    14 -> 0x7FFF_FFFF_FFFF_FFFFL
    // enum/uint8/byte -> 0xFF, uint16 -> 0xFFFF, uint32/float32 -> 0xFFFFFFFF
    0, 2, 13 -> 0xFFL
    4 -> 0xFFFFL
    6, 8 -> 0xFFFF_FFFFL
    else -> Long.MIN_VALUE // unbekannt: kein Wert kann "invalid" treffen
}

/** true fuer Basistypen, die keine Zahl sind (String) und darum ignoriert werden. */
private fun isStringType(baseType: Int): Boolean = baseType == 7

// ---------------------------------------------------------------------------
// Definitionen
// ---------------------------------------------------------------------------

private class FitFieldDef(val num: Int, val size: Int, val baseType: Int)

private class FitMessageDef(
    val globalNum: Int,
    val littleEndian: Boolean,
    val fields: List<FitFieldDef>,
    /** Summe der Groessen aller Developer Fields — wird nur ueberlesen. */
    val devBytes: Int,
) {
    val dataSize: Int = fields.sumOf { it.size } + devBytes
}

// ---------------------------------------------------------------------------
// Decoder
// ---------------------------------------------------------------------------

/** Sammelt einen Record, bis die Message zu Ende ist. */
private class RecordAccumulator {
    var lat: Long? = null
    var lon: Long? = null
    var ele: Double? = null
    var enhancedEle: Double? = null
    var timeS: Long? = null
    var hr: Int? = null

    fun reset() {
        lat = null; lon = null; ele = null; enhancedEle = null; timeS = null; hr = null
    }
}

private class FitDecoder(private val buf: ByteArray) {
    val points = mutableListOf<TrackPoint>()
    var sport: Int? = null
    var startTimeS: Long? = null
    var fileType: Int? = null
    var crcValid: Boolean = true

    private val defs = HashMap<Int, FitMessageDef>()
    private var lastTimestampS: Long? = null
    private val record = RecordAccumulator()

    /**
     * Liest ein Feld als Zahl. Liefert null bei "invalid", bei String-/
     * unbekannten Basistypen und bei zu kurz deklarierten Feldern. Von
     * Array-Feldern wird nur das erste Element gelesen.
     */
    private fun readNumeric(pos: Int, field: FitFieldDef, littleEndian: Boolean): Double? {
        val baseType = field.baseType and 0x1F
        if (isStringType(baseType)) return null
        val size = baseTypeSize(baseType)
        if (size == 0 || size > field.size) return null

        var raw = 0L
        if (littleEndian) {
            for (i in size - 1 downTo 0) raw = (raw shl 8) or (buf[pos + i].toLong() and 0xFF)
        } else {
            for (i in 0 until size) raw = (raw shl 8) or (buf[pos + i].toLong() and 0xFF)
        }
        if (raw == invalidRaw(baseType)) return null

        return when (baseType) {
            1 -> raw.toByte().toDouble()
            3 -> raw.toShort().toDouble()
            5 -> raw.toInt().toDouble()
            14 -> raw.toDouble()
            8 -> Float.fromBits(raw.toInt()).toDouble().takeIf { it.isFinite() }
            9 -> Double.fromBits(raw).takeIf { it.isFinite() }
            else -> raw.toDouble() // vorzeichenlose Typen: raw ist bereits korrekt
        }
    }

    /** Liest eine Definition-Message ab [pos]; liefert die neue Position oder -1 bei Abbruch. */
    private fun readDefinition(pos: Int, localType: Int, hasDevFields: Boolean, end: Int): Int {
        var p = pos
        if (p + 5 > end) return -1
        p++ // reserved
        val littleEndian = (buf[p].toInt() and 0xFF) == 0
        p++
        val globalNum = if (littleEndian) {
            (buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8)
        } else {
            ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        }
        p += 2
        val fieldCount = buf[p].toInt() and 0xFF
        p++
        if (p + fieldCount * 3 > end) return -1

        val fields = ArrayList<FitFieldDef>(fieldCount)
        for (i in 0 until fieldCount) {
            fields.add(
                FitFieldDef(
                    num = buf[p].toInt() and 0xFF,
                    size = buf[p + 1].toInt() and 0xFF,
                    baseType = buf[p + 2].toInt() and 0xFF,
                ),
            )
            p += 3
        }

        var devBytes = 0
        if (hasDevFields) {
            if (p >= end) return -1
            val devCount = buf[p].toInt() and 0xFF
            p++
            if (p + devCount * 3 > end) return -1
            for (i in 0 until devCount) {
                devBytes += buf[p + 1].toInt() and 0xFF
                p += 3
            }
        }

        defs[localType] = FitMessageDef(globalNum, littleEndian, fields, devBytes)
        return p
    }

    /** Wertet eine Data-Message aus; liefert die neue Position oder -1 bei Abbruch. */
    private fun readData(pos: Int, def: FitMessageDef, forcedTimestampS: Long?, end: Int): Int {
        if (pos + def.dataSize > end) return -1

        record.reset()
        if (def.globalNum == MSG_RECORD) record.timeS = forcedTimestampS

        var p = pos
        for (field in def.fields) {
            val value = readNumeric(p, field, def.littleEndian)
            p += field.size
            if (value == null) continue

            if (field.num == FIELD_TIMESTAMP) {
                val ts = value.toLong()
                lastTimestampS = ts
                if (def.globalNum == MSG_RECORD) record.timeS = ts
                continue
            }

            when (def.globalNum) {
                MSG_RECORD -> when (field.num) {
                    0 -> record.lat = value.toLong()
                    1 -> record.lon = value.toLong()
                    2 -> record.ele = value / 5.0 - 500.0
                    3 -> record.hr = dartRound(value).toInt()
                    78 -> record.enhancedEle = value / 5.0 - 500.0
                }

                MSG_SESSION -> when (field.num) {
                    2 -> if (startTimeS == null) startTimeS = value.toLong()
                    5 -> if (sport == null) sport = value.toInt()
                }

                MSG_LAP -> when (field.num) {
                    2 -> if (startTimeS == null) startTimeS = value.toLong()
                    25 -> if (sport == null) sport = value.toInt()
                }

                MSG_FILE_ID -> when (field.num) {
                    0 -> if (fileType == null) fileType = value.toInt()
                }

                else -> Unit // alle anderen Messages: nur ueberlesen
            }
        }

        if (def.globalNum == MSG_RECORD) emitRecord()
        return pos + def.dataSize
    }

    /** Uebernimmt den gesammelten Record — Punkte ohne GPS werden verworfen. */
    private fun emitRecord() {
        val lat = record.lat ?: return
        val lon = record.lon ?: return
        val latDeg = lat * SEMICIRCLES_TO_DEGREES
        val lonDeg = lon * SEMICIRCLES_TO_DEGREES
        if (!latDeg.isFinite() || !lonDeg.isFinite()) return
        if (latDeg < -90.0 || latDeg > 90.0 || lonDeg < -180.0 || lonDeg > 180.0) return

        points.add(
            TrackPoint(
                lat = latDeg,
                lon = lonDeg,
                // enhanced_altitude gewinnt: gleiche Skalierung, groesserer Wertebereich.
                ele = record.enhancedEle ?: record.ele,
                time = record.timeS?.let { (it + FIT_EPOCH_OFFSET_S) * 1000L },
                hr = record.hr,
            ),
        )
    }

    /**
     * Dekodiert den Datenblock `[from, to)`. Bricht still ab, sobald der Strom
     * nicht mehr interpretierbar ist (fehlende Definition, abgeschnittene
     * Message) — das bereits Gelesene bleibt erhalten.
     */
    fun decodeRecords(from: Int, to: Int) {
        var pos = from
        while (pos < to) {
            val header = buf[pos].toInt() and 0xFF
            pos++

            if (header and 0x80 != 0) {
                // Compressed-Timestamp-Header: 2 Bit lokaler Typ, 5 Bit Sekunden-Offset.
                val localType = (header ushr 5) and 0x03
                val offset = header and 0x1F
                val def = defs[localType] ?: return
                val last = lastTimestampS ?: 0L
                var ts = (last and 0x1FL.inv()) + offset
                if (offset < (last and 0x1FL).toInt()) ts += 0x20
                lastTimestampS = ts
                pos = readData(pos, def, ts, to)
                if (pos < 0) return
                continue
            }

            val localType = header and 0x0F
            if (header and 0x40 != 0) {
                pos = readDefinition(pos, localType, hasDevFields = header and 0x20 != 0, end = to)
                if (pos < 0) return
            } else {
                val def = defs[localType] ?: return
                pos = readData(pos, def, forcedTimestampS = null, end = to)
                if (pos < 0) return
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Oeffentliche API
// ---------------------------------------------------------------------------

private fun uint32Le(bytes: ByteArray, at: Int): Long =
    (bytes[at].toLong() and 0xFF) or
        ((bytes[at + 1].toLong() and 0xFF) shl 8) or
        ((bytes[at + 2].toLong() and 0xFF) shl 16) or
        ((bytes[at + 3].toLong() and 0xFF) shl 24)

private fun uint16Le(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

private fun hasFitSignature(bytes: ByteArray, at: Int): Boolean =
    at + 12 <= bytes.size &&
        bytes[at + 8] == '.'.code.toByte() &&
        bytes[at + 9] == 'F'.code.toByte() &&
        bytes[at + 10] == 'I'.code.toByte() &&
        bytes[at + 11] == 'T'.code.toByte()

/**
 * Liest eine FIT-Datei (roh oder GZIP-komprimiert) und liefert Name, Punkte
 * sowie Sportart/Startzeit.
 *
 * Aneinandergehaengte FIT-Dateien (das Format erlaubt mehrere Header
 * hintereinander in einer Datei) werden fortlaufend gelesen und die Punkte
 * zusammengefuehrt.
 *
 * [fallbackName] wird nur verwendet, wenn sich aus der Datei kein Name
 * (Sportart + Startdatum) bilden laesst — ueblicherweise der Dateiname ohne
 * Endung.
 *
 * @throws FormatException wenn die Datei keine FIT-Signatur traegt oder keine
 *   Trackpunkte mit Position enthaelt.
 */
fun parseFit(bytes: ByteArray, fallbackName: String? = null): FitParseResult {
    val data = gunzipIfNeeded(bytes)

    if (data.size < 14 || !hasFitSignature(data, 0)) {
        throw FormatException("Die Datei ist keine gültige FIT-Datei.")
    }

    val decoder = FitDecoder(data)
    var offset = 0
    var segments = 0

    while (offset + 12 <= data.size && hasFitSignature(data, offset)) {
        val headerSize = data[offset].toInt() and 0xFF
        if (headerSize < 12 || offset + headerSize > data.size) break

        // Header-CRC (nur im 14-Byte-Header, 0 = "nicht gesetzt") — nur melden.
        if (headerSize >= 14 && offset + 14 <= data.size) {
            val headerCrc = uint16Le(data, offset + 12)
            if (headerCrc != 0 && headerCrc != fitCrc16(data, offset, offset + 12)) {
                decoder.crcValid = false
            }
        }

        val dataStart = offset + headerSize
        val declared = uint32Le(data, offset + 4)
        // Kaputte/zu grosse Groessenangabe tolerieren: auf das Vorhandene begrenzen.
        val available = (data.size - dataStart - 2).coerceAtLeast(0).toLong()
        val dataSize = if (declared in 1..available) declared else available
        val dataEnd = dataStart + dataSize.toInt()

        if (dataEnd + 2 <= data.size) {
            val fileCrc = uint16Le(data, dataEnd)
            if (fileCrc != fitCrc16(data, offset, dataEnd)) decoder.crcValid = false
        } else {
            decoder.crcValid = false
        }

        decoder.decodeRecords(dataStart, dataEnd)
        segments++

        val next = dataEnd + 2
        if (next <= offset) break
        offset = next
    }

    if (segments == 0 || decoder.points.isEmpty()) {
        throw FormatException("Die FIT-Datei enthält keine Trackpunkte.")
    }

    val startMs = decoder.startTimeS?.let { (it + FIT_EPOCH_OFFSET_S) * 1000L }
    val name = fitRideName(decoder.sport, startMs ?: decoder.points.firstOrNull { it.time != null }?.time)
        ?: fallbackName?.trim()?.ifEmpty { null }

    return FitParseResult(
        name = name,
        points = decoder.points.toList(),
        sport = decoder.sport,
        startTime = startMs,
        fileType = decoder.fileType,
        crcValid = decoder.crcValid,
    )
}

/**
 * Baut den Tournamen aus Sportart und Startdatum, z. B. `Radfahrt 14.03.2024`.
 * Ohne Zeitstempel gibt es keinen sinnvollen Namen — dann null.
 */
private fun fitRideName(sport: Int?, startMs: Long?): String? {
    if (startMs == null) return null
    val label = FIT_SPORT_LABELS[sport] ?: "Aktivität"
    return "$label ${FIT_NAME_DATE_FORMAT.format(Instant.ofEpochMilli(startMs))}"
}

/**
 * Baut aus einer FIT-Datei eine vollstaendige Tour inklusive berechneter
 * Statistiken — das FIT-Gegenstueck zu [rideFromGpx] und mit identischer
 * Semantik: [computeStats] fuer Distanz/Dauer/Hoehenmeter, Ø-/Max-Puls aus
 * den Trackpunkten.
 *
 * [Ride.createdAt] ist der Zeitstempel des ersten Trackpunkts — genau wie bei
 * [rideFromGpx], damit dieselbe Tour als GPX **und** als FIT von der
 * Duplikatpruefung ([findDuplicateRide]) erkannt wird. Nur wenn die Punkte
 * keine Zeit tragen, greift `session.start_time` bzw. die aktuelle Uhrzeit.
 *
 * @throws FormatException bei ungueltiger Datei (siehe [parseFit]).
 */
fun rideFromFit(bytes: ByteArray, fallbackName: String? = null, id: String? = null): Ride {
    val parsed = parseFit(bytes, fallbackName)
    val points = parsed.points
    val baseStats = computeStats(points)

    val name = parsed.name?.trim()?.ifEmpty { null }
        ?: fallbackName?.trim()?.ifEmpty { null }
        ?: "Tour"
    val createdAt = points.first().time ?: parsed.startTime ?: System.currentTimeMillis()

    val hrValues = points.mapNotNull { it.hr }
    var avgHr: Int? = null
    var maxHr: Int? = null
    if (hrValues.isNotEmpty()) {
        avgHr = dartRound(hrValues.sum().toDouble() / hrValues.size).toInt()
        maxHr = hrValues.max()
    }

    return Ride(
        id = id ?: System.currentTimeMillis().toString(),
        name = name,
        createdAt = createdAt,
        points = points,
        stats = RideStats(
            distanceKm = baseStats.distanceKm,
            durationS = baseStats.durationS,
            movingTimeS = baseStats.movingTimeS,
            avgSpeedKmh = baseStats.avgSpeedKmh,
            ascentM = baseStats.ascentM,
            descentM = baseStats.descentM,
            avgHrBpm = avgHr,
            maxHrBpm = maxHr,
        ),
    )
}
