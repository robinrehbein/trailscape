package de.trailscape.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests des FIT-Decoders (`Fit.kt`).
 *
 * Die Testdateien werden hier **byteweise** zusammengebaut statt als Binaer-
 * Fixture eingecheckt. Das hat zwei Vorteile: der Test dokumentiert das
 * FIT-Format (Header, Definition Message, Data Message, CRC) an Ort und Stelle,
 * und Sonderfaelle (Big Endian, Developer Fields, kaputte CRC) lassen sich
 * exakt erzeugen statt sie in echten Geraetedateien suchen zu muessen.
 */
class FitTest {

    // -----------------------------------------------------------------------
    // Baukasten: FIT-Bytes von Hand
    // -----------------------------------------------------------------------

    private companion object {
        /** 2024-03-14T10:00:00Z als Unix-Sekunden. */
        const val UNIX_2024_03_14_10H = 1_710_410_400L

        /** Derselbe Zeitpunkt in FIT-Sekunden (Epoche 1989-12-31T00:00:00Z). */
        const val FIT_2024_03_14_10H = UNIX_2024_03_14_10H - FIT_EPOCH_OFFSET_S

        const val EPS = 1e-5

        // Basistyp-Bytes: Bit 7 = "endian ability", Bits 0-4 = Basistyp-Nummer.
        const val TYPE_ENUM = 0x00
        const val TYPE_UINT8 = 0x02
        const val TYPE_SINT32 = 0x85
        const val TYPE_UINT16 = 0x84
        const val TYPE_UINT32 = 0x86
        const val TYPE_STRING = 0x07
    }

    /** Ein Feld einer Definition Message: Feldnummer, Groesse in Bytes, Basistyp. */
    private data class Field(val num: Int, val size: Int, val baseType: Int)

    private fun u8(v: Int) = byteArrayOf((v and 0xFF).toByte())

    private fun u16(v: Int, littleEndian: Boolean = true): ByteArray =
        if (littleEndian) byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        else byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u32(v: Long, littleEndian: Boolean = true): ByteArray =
        if (littleEndian) ByteArray(4) { ((v ushr (8 * it)) and 0xFF).toByte() }
        else ByteArray(4) { ((v ushr (8 * (3 - it))) and 0xFF).toByte() }

    /** Grad → Semicircles, wie es Garmin-Geraete in `position_lat/long` schreiben. */
    private fun semicircles(deg: Double): Long = (deg * 2147483648.0 / 180.0).roundToInt().toLong()

    private fun cat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /**
     * Definition Message: Record-Header (Bit 6 = Definition, Bit 5 = Developer
     * Fields), dann reserved/architecture/global-message-number/Feldzahl und je
     * Feld drei Bytes.
     */
    private fun definition(
        localType: Int,
        globalNum: Int,
        fields: List<Field>,
        littleEndian: Boolean = true,
        devFields: List<Field> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u8(0x40 or localType or (if (devFields.isNotEmpty()) 0x20 else 0)))
        out.write(u8(0x00)) // reserved
        out.write(u8(if (littleEndian) 0 else 1)) // architecture
        out.write(u16(globalNum, littleEndian))
        out.write(u8(fields.size))
        for (f in fields) out.write(cat(u8(f.num), u8(f.size), u8(f.baseType)))
        if (devFields.isNotEmpty()) {
            out.write(u8(devFields.size))
            // Developer Fields: Feldnummer, Groesse, Index der Developer-Data-ID.
            for (f in devFields) out.write(cat(u8(f.num), u8(f.size), u8(f.baseType)))
        }
        return out.toByteArray()
    }

    /** Data Message mit normalem Header (Bits 0-3 = lokaler Message-Typ). */
    private fun dataMessage(localType: Int, payload: ByteArray): ByteArray =
        cat(u8(localType and 0x0F), payload)

    /** Data Message mit Compressed-Timestamp-Header: Bit 7, 2 Bit Typ, 5 Bit Offset. */
    private fun compressedMessage(localType: Int, timeOffset: Int, payload: ByteArray): ByteArray =
        cat(u8(0x80 or ((localType and 0x03) shl 5) or (timeOffset and 0x1F)), payload)

    /**
     * Packt Datensaetze in eine vollstaendige FIT-Datei: 12-/14-Byte-Header mit
     * ".FIT"-Signatur und Datengroesse, danach die Daten, am Ende die CRC-16.
     */
    private fun fitFile(
        data: ByteArray,
        headerSize: Int = 12,
        signature: String = ".FIT",
        declaredDataSize: Long? = null,
        breakFileCrc: Boolean = false,
        breakHeaderCrc: Boolean = false,
    ): ByteArray {
        val head = ByteArrayOutputStream()
        head.write(u8(headerSize))
        head.write(u8(0x20)) // protocol version 2.0
        head.write(u16(2140)) // profile version
        head.write(u32(declaredDataSize ?: data.size.toLong()))
        head.write(signature.toByteArray(Charsets.US_ASCII))
        var header = head.toByteArray()
        if (headerSize == 14) {
            val headerCrc = if (breakHeaderCrc) 0x1234 else fitCrc16(header, 0, 12)
            header = cat(header, u16(headerCrc))
        }

        val body = cat(header, data)
        val crc = if (breakFileCrc) 0x0001 else fitCrc16(body, 0, body.size)
        return cat(body, u16(crc))
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Bausteine fuer haeufige Messages
    // -----------------------------------------------------------------------

    private val recordFields = listOf(
        Field(253, 4, TYPE_UINT32), // timestamp
        Field(0, 4, TYPE_SINT32), // position_lat
        Field(1, 4, TYPE_SINT32), // position_long
        Field(2, 2, TYPE_UINT16), // altitude (scale 5, offset 500)
        Field(3, 1, TYPE_UINT8), // heart_rate
    )

    private fun recordPayload(
        fitSeconds: Long,
        lat: Double?,
        lon: Double?,
        eleM: Double?,
        hr: Int?,
        littleEndian: Boolean = true,
    ): ByteArray = cat(
        u32(fitSeconds, littleEndian),
        u32(lat?.let { semicircles(it) } ?: 0x7FFF_FFFFL, littleEndian),
        u32(lon?.let { semicircles(it) } ?: 0x7FFF_FFFFL, littleEndian),
        u16(eleM?.let { ((it + 500.0) * 5.0).roundToInt() } ?: 0xFFFF, littleEndian),
        u8(hr ?: 0xFF),
    )

    /** file_id-Message (global 0) mit `type` = activity. */
    private fun fileIdMessages(localType: Int = 5): ByteArray = cat(
        definition(localType, 0, listOf(Field(0, 1, TYPE_ENUM))),
        dataMessage(localType, u8(FIT_FILE_TYPE_ACTIVITY)),
    )

    /** session-Message (global 18) mit `start_time` und `sport`. */
    private fun sessionMessages(
        localType: Int = 6,
        sport: Int = FIT_SPORT_CYCLING,
        startFitSeconds: Long = FIT_2024_03_14_10H,
    ): ByteArray = cat(
        definition(localType, 18, listOf(Field(2, 4, TYPE_UINT32), Field(5, 1, TYPE_ENUM))),
        dataMessage(localType, cat(u32(startFitSeconds), u8(sport))),
    )

    /** Drei Trackpunkte mit Puls, wie sie ein Radcomputer schreibt. */
    private fun threeRecords(littleEndian: Boolean = true): ByteArray = cat(
        definition(0, 20, recordFields, littleEndian),
        dataMessage(0, recordPayload(FIT_2024_03_14_10H, 47.0, 11.0, 600.0, 120, littleEndian)),
        dataMessage(0, recordPayload(FIT_2024_03_14_10H + 10, 47.001, 11.001, 610.0, 130, littleEndian)),
        dataMessage(0, recordPayload(FIT_2024_03_14_10H + 20, 47.002, 11.002, 620.0, 140, littleEndian)),
    )

    // -----------------------------------------------------------------------
    // Normalfall
    // -----------------------------------------------------------------------

    @Test
    fun `drei Records mit Position Hoehe und Puls werden gelesen`() {
        val file = fitFile(cat(fileIdMessages(), threeRecords(), sessionMessages()))
        val result = parseFit(file)

        assertEquals(3, result.points.size)
        assertTrue(result.crcValid)
        assertEquals(FIT_FILE_TYPE_ACTIVITY, result.fileType)

        val first = result.points[0]
        assertEquals(47.0, first.lat, EPS)
        assertEquals(11.0, first.lon, EPS)
        assertEquals(600.0, first.ele!!, 1e-3)
        assertEquals(120, first.hr)
        assertEquals(UNIX_2024_03_14_10H * 1000L, first.time)

        assertEquals(47.002, result.points[2].lat, EPS)
        assertEquals(140, result.points[2].hr)
        assertEquals((UNIX_2024_03_14_10H + 20) * 1000L, result.points[2].time)
    }

    @Test
    fun `Name entsteht aus Sportart und Startdatum`() {
        val file = fitFile(cat(threeRecords(), sessionMessages()))
        val result = parseFit(file, fallbackName = "1234567890")

        assertEquals(FIT_SPORT_CYCLING, result.sport)
        assertTrue(result.isCycling)
        assertEquals(UNIX_2024_03_14_10H * 1000L, result.startTime)
        assertEquals("Radfahrt 14.03.2024", result.name)
    }

    @Test
    fun `unbekannte Sportart bekommt einen neutralen Namen`() {
        val file = fitFile(cat(threeRecords(), sessionMessages(sport = 42)))
        assertEquals("Aktivität 14.03.2024", parseFit(file).name)
    }

    @Test
    fun `Sportart darf auch aus der Lap-Message kommen`() {
        val lap = cat(
            definition(7, 19, listOf(Field(2, 4, TYPE_UINT32), Field(25, 1, TYPE_ENUM))),
            dataMessage(7, cat(u32(FIT_2024_03_14_10H), u8(FIT_SPORT_CYCLING))),
        )
        val result = parseFit(fitFile(cat(threeRecords(), lap)))

        assertEquals(FIT_SPORT_CYCLING, result.sport)
        assertEquals("Radfahrt 14.03.2024", result.name)
    }

    @Test
    fun `rideFromFit berechnet Statistiken und Pulswerte`() {
        val file = fitFile(cat(threeRecords(), sessionMessages()))
        val ride = rideFromFit(file, fallbackName = "egal", id = "fest")

        assertEquals("fest", ride.id)
        assertEquals("Radfahrt 14.03.2024", ride.name)
        assertEquals(UNIX_2024_03_14_10H * 1000L, ride.createdAt)
        assertEquals(3, ride.points.size)
        assertTrue(ride.stats.distanceKm > 0.0)
        assertEquals(20, ride.stats.durationS)
        assertEquals(130, ride.stats.avgHrBpm)
        assertEquals(140, ride.stats.maxHrBpm)
        assertEquals(20.0, ride.stats.ascentM, 1e-3)
    }

    // -----------------------------------------------------------------------
    // Fehlerfaelle
    // -----------------------------------------------------------------------

    @Test
    fun `kaputte Signatur wird abgewiesen`() {
        val file = fitFile(threeRecords(), signature = ".XXX")
        val error = assertFailsWith<FormatException> { parseFit(file) }
        assertEquals("Die Datei ist keine gültige FIT-Datei.", error.message)
    }

    @Test
    fun `zu kurze Datei wird abgewiesen`() {
        assertFailsWith<FormatException> { parseFit(ByteArray(5)) }
        assertFailsWith<FormatException> { parseFit(ByteArray(0)) }
    }

    @Test
    fun `Datei ohne GPS-Punkte wird abgewiesen`() {
        // Records ohne Position: nur Zeitstempel und Puls.
        val fields = listOf(Field(253, 4, TYPE_UINT32), Field(3, 1, TYPE_UINT8))
        val file = fitFile(
            cat(
                fileIdMessages(),
                definition(0, 20, fields),
                dataMessage(0, cat(u32(FIT_2024_03_14_10H), u8(120))),
                dataMessage(0, cat(u32(FIT_2024_03_14_10H + 1), u8(121))),
            ),
        )
        val error = assertFailsWith<FormatException> { parseFit(file) }
        assertEquals("Die FIT-Datei enthält keine Trackpunkte.", error.message)
    }

    @Test
    fun `Records ohne Position werden einzeln uebersprungen`() {
        val file = fitFile(
            cat(
                definition(0, 20, recordFields),
                // Tunnel/Startphase ohne Fix: lat/lon = invalid (0x7FFFFFFF).
                dataMessage(0, recordPayload(FIT_2024_03_14_10H, null, null, 600.0, 118)),
                dataMessage(0, recordPayload(FIT_2024_03_14_10H + 5, 47.0, 11.0, 600.0, 120)),
            ),
        )
        val result = parseFit(file)

        assertEquals(1, result.points.size)
        assertEquals(47.0, result.points[0].lat, EPS)
    }

    @Test
    fun `invalid-Werte fuer Hoehe und Puls werden zu null`() {
        val file = fitFile(
            cat(
                definition(0, 20, recordFields),
                dataMessage(0, recordPayload(FIT_2024_03_14_10H, 47.0, 11.0, null, null)),
            ),
        )
        val point = parseFit(file).points.single()

        assertNull(point.ele)
        assertNull(point.hr)
        assertEquals(UNIX_2024_03_14_10H * 1000L, point.time)
    }

    // -----------------------------------------------------------------------
    // Format-Sonderfaelle
    // -----------------------------------------------------------------------

    @Test
    fun `Big-Endian-Definition wird respektiert`() {
        val file = fitFile(threeRecords(littleEndian = false))
        val result = parseFit(file)

        assertEquals(3, result.points.size)
        assertEquals(47.0, result.points[0].lat, EPS)
        assertEquals(11.002, result.points[2].lon, EPS)
        assertEquals(600.0, result.points[0].ele!!, 1e-3)
        assertEquals(UNIX_2024_03_14_10H * 1000L, result.points[0].time)
    }

    @Test
    fun `Compressed-Timestamp-Header setzt die Zeit fort`() {
        // Erst ein Record mit vollem Zeitstempel (setzt die Referenzzeit), dann
        // zwei Records im Kurzformat: 5 Bit Sekunden-Offset im Header selbst.
        val positionOnly = listOf(Field(0, 4, TYPE_SINT32), Field(1, 4, TYPE_SINT32))
        val file = fitFile(
            cat(
                definition(0, 20, recordFields),
                dataMessage(0, recordPayload(FIT_2024_03_14_10H, 47.0, 11.0, 600.0, 120)),
                definition(1, 20, positionOnly),
                compressedMessage(1, 5, cat(u32(semicircles(47.001)), u32(semicircles(11.001)))),
                // Offset kleiner als der vorige: die 5-Bit-Zeit ist uebergelaufen (+32 s).
                compressedMessage(1, 3, cat(u32(semicircles(47.002)), u32(semicircles(11.002)))),
            ),
        )
        val result = parseFit(file)

        assertEquals(3, result.points.size)
        assertEquals(UNIX_2024_03_14_10H * 1000L, result.points[0].time)
        assertEquals((UNIX_2024_03_14_10H + 5) * 1000L, result.points[1].time)
        assertEquals((UNIX_2024_03_14_10H + 35) * 1000L, result.points[2].time)
    }

    @Test
    fun `gz-Variante wird transparent entpackt`() {
        val file = fitFile(cat(threeRecords(), sessionMessages()))
        val plain = parseFit(file)
        val packed = parseFit(gzip(file))

        assertEquals(plain.points, packed.points)
        assertEquals(plain.name, packed.name)
    }

    @Test
    fun `unbekannte Messages werden uebersprungen`() {
        // Global 999 gibt es im Profil nicht — die Definition reicht, um die
        // Daten zu ueberlesen, ohne den Strom zu verlieren.
        val unknown = cat(
            definition(3, 999, listOf(Field(0, 8, TYPE_UINT32), Field(7, 3, TYPE_UINT8))),
            dataMessage(3, ByteArray(11) { 0x5A }),
        )
        val file = fitFile(cat(unknown, threeRecords(), unknown))

        assertEquals(3, parseFit(file).points.size)
    }

    @Test
    fun `Developer Fields werden ueberlesen`() {
        // Record-Definition mit zwei Developer Fields (3 + 4 Bytes), deren
        // Inhalt uns nicht interessiert — nur ihre Groesse.
        val dev = listOf(Field(0, 3, 0), Field(1, 4, 0))
        val file = fitFile(
            cat(
                definition(0, 20, recordFields, devFields = dev),
                dataMessage(
                    0,
                    cat(recordPayload(FIT_2024_03_14_10H, 47.0, 11.0, 600.0, 120), ByteArray(7) { 0x11 }),
                ),
                dataMessage(
                    0,
                    cat(recordPayload(FIT_2024_03_14_10H + 10, 47.001, 11.001, 610.0, 130), ByteArray(7) { 0x22 }),
                ),
            ),
        )
        val result = parseFit(file)

        assertEquals(2, result.points.size)
        assertEquals(130, result.points[1].hr)
    }

    @Test
    fun `String- und Array-Felder stoeren die Auswertung nicht`() {
        val fields = listOf(
            Field(253, 4, TYPE_UINT32),
            Field(0, 4, TYPE_SINT32),
            Field(1, 4, TYPE_SINT32),
            Field(3, 1, TYPE_UINT8),
            Field(200, 8, TYPE_STRING), // unbekanntes String-Feld
            Field(201, 6, TYPE_UINT16), // Array aus 3 uint16
        )
        val file = fitFile(
            cat(
                definition(0, 20, fields),
                dataMessage(
                    0,
                    cat(
                        u32(FIT_2024_03_14_10H),
                        u32(semicircles(47.0)),
                        u32(semicircles(11.0)),
                        u8(120),
                        // FIT-Strings sind nullterminiert und auf Feldgroesse aufgefuellt.
                        cat("Garmin".toByteArray(Charsets.US_ASCII), ByteArray(2)),
                        cat(u16(1), u16(2), u16(3)),
                    ),
                ),
            ),
        )
        val point = parseFit(file).points.single()

        assertEquals(47.0, point.lat, EPS)
        assertEquals(120, point.hr)
    }

    @Test
    fun `enhanced_altitude gewinnt gegen altitude`() {
        val fields = listOf(
            Field(253, 4, TYPE_UINT32),
            Field(0, 4, TYPE_SINT32),
            Field(1, 4, TYPE_SINT32),
            Field(2, 2, TYPE_UINT16),
            Field(78, 4, TYPE_UINT32),
        )
        val file = fitFile(
            cat(
                definition(0, 20, fields),
                dataMessage(
                    0,
                    cat(
                        u32(FIT_2024_03_14_10H),
                        u32(semicircles(47.0)),
                        u32(semicircles(11.0)),
                        u16(((600.0 + 500.0) * 5.0).roundToInt()),
                        u32(((1234.0 + 500.0) * 5.0).roundToInt().toLong()),
                    ),
                ),
            ),
        )

        assertEquals(1234.0, parseFit(file).points.single().ele!!, 1e-3)
    }

    // -----------------------------------------------------------------------
    // Toleranzen
    // -----------------------------------------------------------------------

    @Test
    fun `kaputte CRC wird gemeldet aber nicht als Fehler gewertet`() {
        val file = fitFile(cat(threeRecords(), sessionMessages()), breakFileCrc = true)
        val result = parseFit(file)

        assertFalse(result.crcValid)
        assertEquals(3, result.points.size)
    }

    @Test
    fun `14-Byte-Header mit Header-CRC wird gelesen`() {
        val ok = parseFit(fitFile(threeRecords(), headerSize = 14))
        assertEquals(3, ok.points.size)
        assertTrue(ok.crcValid)

        val broken = parseFit(fitFile(threeRecords(), headerSize = 14, breakHeaderCrc = true))
        assertEquals(3, broken.points.size)
        assertFalse(broken.crcValid)
    }

    @Test
    fun `falsche Datengroesse im Header wird toleriert`() {
        val data = cat(threeRecords(), sessionMessages())
        val tooBig = fitFile(data, declaredDataSize = 0xFF_FFFFL)
        val zero = fitFile(data, declaredDataSize = 0L)

        assertEquals(3, parseFit(tooBig).points.size)
        assertEquals(3, parseFit(zero).points.size)
    }

    @Test
    fun `abgeschnittene Message beendet das Lesen ohne Datenverlust`() {
        val truncated = cat(
            definition(0, 20, recordFields),
            dataMessage(0, recordPayload(FIT_2024_03_14_10H, 47.0, 11.0, 600.0, 120)),
            // halbe Data Message
            cat(u8(0x00), u32(FIT_2024_03_14_10H + 1)),
        )
        val result = parseFit(fitFile(truncated))

        assertEquals(1, result.points.size)
    }

    @Test
    fun `Data-Message ohne vorherige Definition bricht sauber ab`() {
        val file = fitFile(
            cat(
                definition(0, 20, recordFields),
                dataMessage(0, recordPayload(FIT_2024_03_14_10H, 47.0, 11.0, 600.0, 120)),
                dataMessage(9, ByteArray(4)), // lokaler Typ 9 nie definiert
                dataMessage(0, recordPayload(FIT_2024_03_14_10H + 5, 47.5, 11.5, 600.0, 120)),
            ),
        )

        assertEquals(1, parseFit(file).points.size)
    }

    @Test
    fun `aneinandergehaengte FIT-Segmente werden zusammengefuehrt`() {
        val first = fitFile(threeRecords())
        val second = fitFile(
            cat(
                definition(0, 20, recordFields),
                dataMessage(0, recordPayload(FIT_2024_03_14_10H + 60, 47.01, 11.01, 700.0, 150)),
            ),
        )
        val result = parseFit(cat(first, second))

        assertEquals(4, result.points.size)
        assertEquals(150, result.points[3].hr)
    }

    @Test
    fun `ohne Zeitstempel greift der Fallback-Name`() {
        val fields = listOf(Field(0, 4, TYPE_SINT32), Field(1, 4, TYPE_SINT32))
        val file = fitFile(
            cat(
                definition(0, 20, fields),
                dataMessage(0, cat(u32(semicircles(47.0)), u32(semicircles(11.0)))),
                dataMessage(0, cat(u32(semicircles(47.1)), u32(semicircles(11.1)))),
            ),
        )
        val result = parseFit(file, fallbackName = "  activity_42  ")

        assertEquals("activity_42", result.name)
        assertNull(result.points[0].time)
        assertNull(result.startTime)

        val ride = rideFromFit(file, fallbackName = "activity_42", id = "x")
        assertEquals("activity_42", ride.name)
        assertTrue(ride.createdAt > 0L)
    }

    @Test
    fun `unsinnige Koordinaten werden verworfen`() {
        // Semicircles ausserhalb des Wertebereichs (hier: Breitengrad > 90 Grad).
        val file = fitFile(
            cat(
                definition(0, 20, recordFields),
                dataMessage(0, cat(u32(FIT_2024_03_14_10H), u32(0x6000_0000L), u32(0L), u16(0xFFFF), u8(0xFF))),
                dataMessage(0, recordPayload(FIT_2024_03_14_10H + 1, 47.0, 11.0, 600.0, 120)),
            ),
        )
        val result = parseFit(file)

        assertEquals(1, result.points.size)
        assertEquals(47.0, result.points[0].lat, EPS)
    }

    @Test
    fun `CRC-Berechnung entspricht der FIT-Spezifikation`() {
        // Bekanntes Ergebnis: die CRC einer korrekt gebauten Datei ueber Header
        // + Daten + CRC-Bytes selbst ist wieder 0.
        val file = fitFile(threeRecords())
        assertEquals(0, fitCrc16(file, 0, file.size))

        val broken = fitFile(threeRecords(), breakFileCrc = true)
        assertTrue(fitCrc16(broken, 0, broken.size) != 0)
    }

    @Test
    fun `nicht-gz Daten laufen unveraendert durch gunzipIfNeeded`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        assertTrue(raw.contentEquals(gunzipIfNeeded(raw)))
        assertTrue(fitFile(threeRecords()).contentEquals(gunzipIfNeeded(fitFile(threeRecords()))))
    }
}
