package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer den Wear-Protokoll-Vertrag (WearProtocol.kt): Pfad-/Faehigkeits-
 * konstanten sowie Kodieren/Dekodieren-Roundtrips fuer alle vier
 * Nachrichtentypen.
 *
 * Die Pfad- und Faehigkeitsnamen sind Vertrag zwischen `:wear` und `:app` —
 * eine Aenderung hier bricht stillschweigend die Kompatibilitaet zwischen
 * einer aktualisierten und einer nicht aktualisierten Geraeteseite. Diese
 * Tests fixieren die exakten String-Werte deshalb genauso hart wie
 * ModelsTest die Byte-Kompatibilitaet zum Dart-Original.
 */
class WearProtocolTest {

    // --- Pfade und Faehigkeiten ---

    @Test
    fun `Pfad- und Faehigkeitskonstanten haben die vereinbarten Werte`() {
        assertEquals("/trailscape/sensor", PFAD_SENSOR)
        assertEquals("/trailscape/befehl-an-telefon", PFAD_BEFEHL_AN_TELEFON)
        assertEquals("/trailscape/befehl-an-uhr", PFAD_BEFEHL_AN_UHR)
        assertEquals("/trailscape/zustand", PFAD_ZUSTAND)
        assertEquals("/trailscape/abschluss", PFAD_ABSCHLUSS)
        assertEquals("trailscape_telefon", FAEHIGKEIT_TELEFON)
        assertEquals("trailscape_uhr", FAEHIGKEIT_UHR)
    }

    @Test
    fun `alle Pfade sind paarweise verschieden`() {
        val pfade = setOf(PFAD_SENSOR, PFAD_BEFEHL_AN_TELEFON, PFAD_BEFEHL_AN_UHR, PFAD_ZUSTAND, PFAD_ABSCHLUSS)
        assertEquals(5, pfade.size)
    }

    // --- SensorSample / SensorBatch ---

    @Test
    fun `SensorSample Roundtrip mit allen Feldern`() {
        val sample = SensorSample(
            zeitMs = 1_700_000_000_000L,
            lat = 52.5163,
            lon = 13.3777,
            hoeheM = 45.2,
            genauigkeitM = 6.5,
            tempoMps = 4.2,
            hf = 142,
        )

        val bytes = kodiereSensorBatch(SensorBatch(listOf(sample)))
        val decoded = dekodiereSensorBatch(bytes)

        assertEquals(SensorBatch(listOf(sample)), decoded)
    }

    @Test
    fun `SensorSample Roundtrip ohne optionale Felder laesst deren Schluessel weg`() {
        val sample = SensorSample(zeitMs = 1000L)

        val json = sample.toJson()
        assertFalse(json.containsKey("lat"))
        assertFalse(json.containsKey("lon"))
        assertFalse(json.containsKey("hoeheM"))
        assertFalse(json.containsKey("genauigkeitM"))
        assertFalse(json.containsKey("tempoMps"))
        assertFalse(json.containsKey("hf"))

        assertEquals(sample, SensorSample.fromJson(json))
    }

    @Test
    fun `SensorBatch Roundtrip mit mehreren Proben und leerer Liste`() {
        val batch = SensorBatch(
            listOf(
                SensorSample(zeitMs = 1000L, lat = 52.0, lon = 13.0, hf = 120),
                SensorSample(zeitMs = 2000L, tempoMps = 3.1),
                SensorSample(zeitMs = 3000L),
            ),
        )

        assertEquals(batch, dekodiereSensorBatch(kodiereSensorBatch(batch)))
        assertEquals(SensorBatch(emptyList()), dekodiereSensorBatch(kodiereSensorBatch(SensorBatch(emptyList()))))
    }

    @Test
    fun `SensorBatch ohne zeitMs in einer Probe wirft`() {
        assertFailsWith<MissingOrInvalidFieldException> {
            dekodiereSensorBatch(
                """{"samples":[{"lat":52.0,"lon":13.0}]}""".toByteArray(Charsets.UTF_8),
            )
        }
    }

    // --- Befehl ---

    @Test
    fun `Befehl Roundtrip fuer alle vier Kommandokonstanten`() {
        for (cmd in listOf(Befehl.START, Befehl.PAUSE, Befehl.WEITER, Befehl.STOPP)) {
            val befehl = Befehl(cmd)
            assertEquals(befehl, dekodiereBefehl(kodiereBefehl(befehl)))
        }
    }

    @Test
    fun `Befehl-Konstanten haben die vereinbarten Werte`() {
        assertEquals("start", Befehl.START)
        assertEquals("pause", Befehl.PAUSE)
        assertEquals("weiter", Befehl.WEITER)
        assertEquals("stopp", Befehl.STOPP)
        assertEquals("telefon-beendet", Befehl.TELEFON_BEENDET)
    }

    // --- Ende am Telefon (Befehl.TELEFON_BEENDET) ---

    @Test
    fun `Befehl mit Zeitstempel Roundtrip, ohne Zeitstempel kein zeitMs im JSON`() {
        val mit = Befehl(Befehl.TELEFON_BEENDET, zeitMs = 1_700_000_000_000L)
        assertEquals(mit, dekodiereBefehl(kodiereBefehl(mit)))
        assertFalse(String(kodiereBefehl(Befehl(Befehl.STOPP)), Charsets.UTF_8).contains("zeitMs"))
    }

    @Test
    fun `eine alte Uhr liest beim Ende-Befehl nur cmd, und den Wert kennt sie nicht`() {
        // So dekodiert eine heutige Uhr: nur `cmd`, unbekannte Felder egal.
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            String(kodiereBefehl(Befehl(Befehl.TELEFON_BEENDET, zeitMs = 5L)), Charsets.UTF_8),
        ) as kotlinx.serialization.json.JsonObject
        val cmd = (json["cmd"] as kotlinx.serialization.json.JsonPrimitive).content
        assertFalse(cmd in setOf(Befehl.START, Befehl.PAUSE, Befehl.WEITER, Befehl.STOPP))
    }

    @Test
    fun `ein Befehl einer alten Gegenseite ohne zeitMs dekodiert weiter`() {
        val befehl = dekodiereBefehl("""{"cmd":"stopp"}""".toByteArray(Charsets.UTF_8))
        assertEquals(Befehl(Befehl.STOPP), befehl)
        assertNull(befehl.zeitMs)
    }

    private val start = 1_700_000_000_000L
    private val ende = start + 3_600_000L
    private fun endeBefehl(zeitMs: Long? = ende) = Befehl(Befehl.TELEFON_BEENDET, zeitMs)

    @Test
    fun `frisches Ende am Telefon beendet eine vorher gestartete Uhr-Aufzeichnung`() {
        assertTrue(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = start, jetztMs = ende + 2_000L))
        assertTrue(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = start, jetztMs = ende))
    }

    @Test
    fun `ohne laufende Uhr-Aufzeichnung passiert nichts`() {
        assertFalse(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = null, jetztMs = ende + 1_000L))
    }

    @Test
    fun `eine nach dem Ende gestartete neue Uhr-Aufzeichnung bleibt unberuehrt`() {
        // Verspaetete Nachricht der vorigen Tour, die neue begann 10 s danach.
        assertFalse(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = ende + 10_000L, jetztMs = ende + 20_000L))
        assertFalse(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = ende, jetztMs = ende + 1_000L))
    }

    @Test
    fun `zu alte Nachricht wirkt nicht mehr, die Grenze selbst noch`() {
        val grenze = ende + TELEFON_ENDE_MAX_ALTER_MS
        assertTrue(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = start, jetztMs = grenze))
        assertFalse(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = start, jetztMs = grenze + 1L))
    }

    @Test
    fun `Telefonuhr leicht voraus wird toleriert, weit voraus nicht`() {
        val knapp = ende - TELEFON_ENDE_UHRZEIT_TOLERANZ_MS
        assertTrue(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = start, jetztMs = knapp))
        assertFalse(telefonEndeBeendetUhr(endeBefehl(), eigenerStartMs = start, jetztMs = knapp - 1L))
    }

    @Test
    fun `ohne Zeitstempel oder mit anderem Befehl passiert nichts`() {
        assertFalse(telefonEndeBeendetUhr(endeBefehl(zeitMs = null), eigenerStartMs = start, jetztMs = ende))
        for (cmd in listOf(Befehl.START, Befehl.PAUSE, Befehl.WEITER, Befehl.STOPP, "unbekannt")) {
            assertFalse(telefonEndeBeendetUhr(Befehl(cmd, ende), eigenerStartMs = start, jetztMs = ende))
        }
    }

    // --- AufzeichnungsZustand ---

    @Test
    fun `AufzeichnungsZustand Roundtrip mit Herzfrequenz`() {
        val zustand = AufzeichnungsZustand(
            laeuft = true,
            pausiert = false,
            dauerMs = 1_234_000L,
            distanzKm = 12.5,
            hf = 138,
        )

        assertEquals(zustand, dekodiereAufzeichnungsZustand(kodiereAufzeichnungsZustand(zustand)))
    }

    @Test
    fun `AufzeichnungsZustand Roundtrip ohne Herzfrequenz laesst den Schluessel weg`() {
        val zustand = AufzeichnungsZustand(laeuft = false, pausiert = true, dauerMs = 0L, distanzKm = 0.0)

        val json = zustand.toJson()
        assertFalse(json.containsKey("hf"))
        assertTrue(json.containsKey("laeuft"))
        assertTrue(json.containsKey("pausiert"))

        assertEquals(zustand, dekodiereAufzeichnungsZustand(kodiereAufzeichnungsZustand(zustand)))
    }

    @Test
    fun `AufzeichnungsZustand ohne dauerMs wirft`() {
        assertFailsWith<MissingOrInvalidFieldException> {
            dekodiereAufzeichnungsZustand("""{"laeuft":true,"pausiert":false,"distanzKm":1.0}""".toByteArray())
        }
    }

    // --- AufzeichnungsZustand: Zielzeile, alt gegen neu ---

    /**
     * Woertliche Kopie des Dekoders, wie ihn eine heutige (alte) Uhr ohne
     * Zielzeile hat — eingefroren, damit der Test auch dann noch alt gegen
     * neu prueft, wenn [AufzeichnungsZustand.fromJson] weiterwaechst.
     */
    private fun alterDekoder(bytes: ByteArray): AufzeichnungsZustand {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()).asRequiredObject()
        return AufzeichnungsZustand(
            laeuft = json.optionalBoolean("laeuft") ?: false,
            pausiert = json.optionalBoolean("pausiert") ?: false,
            dauerMs = json.requiredLong("dauerMs"),
            distanzKm = json.requiredDouble("distanzKm"),
            hf = json.optionalInt("hf"),
        )
    }

    /** Woertlich das Telegramm eines heutigen (alten) Telefons ohne Zielzeile. */
    private val altesTelegramm =
        """{"laeuft":true,"pausiert":false,"dauerMs":1234000,"distanzKm":12.5,"hf":138}""".toByteArray()

    private val mitZiel = AufzeichnungsZustand(
        laeuft = true,
        pausiert = false,
        dauerMs = 1_234_000L,
        distanzKm = 12.5,
        hf = 138,
        ziel = ZielSnapshot(wochenRestKm = 24.3, laengsteKm = 61.0),
    )

    @Test
    fun `AufzeichnungsZustand Roundtrip mit Zielsnapshot`() {
        assertEquals(mitZiel, dekodiereAufzeichnungsZustand(kodiereAufzeichnungsZustand(mitZiel)))
        val nurLaengste = mitZiel.copy(ziel = ZielSnapshot(wochenRestKm = null, laengsteKm = 61.0))
        assertEquals(nurLaengste, dekodiereAufzeichnungsZustand(kodiereAufzeichnungsZustand(nurLaengste)))
        assertFalse(nurLaengste.toJson().containsKey("zielRest"))
    }

    @Test
    fun `Zielfelder haben die vereinbarten Namen und sind Zahlen, kein Text`() {
        val json = mitZiel.toJson()
        assertEquals("24.3", (json["zielRest"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("61.0", (json["laengste"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse((json["zielRest"] as kotlinx.serialization.json.JsonPrimitive).isString)
    }

    @Test
    fun `ohne Snapshot schreibt das neue Telefon genau das alte Telegramm`() {
        val ohne = mitZiel.copy(ziel = null)
        assertEquals(String(altesTelegramm), String(kodiereAufzeichnungsZustand(ohne)))
    }

    @Test
    fun `alte Uhr liest das neue Telegramm und uebergeht die Zielfelder`() {
        val alt = alterDekoder(kodiereAufzeichnungsZustand(mitZiel))
        assertEquals(mitZiel.copy(ziel = null), alt)
    }

    @Test
    fun `neue Uhr liest das alte Telegramm ohne Zielzeile`() {
        val neu = dekodiereAufzeichnungsZustand(altesTelegramm)
        assertEquals(alterDekoder(altesTelegramm), neu)
        assertNull(neu.ziel)
        assertNull(zielZeile(neu.ziel, neu.distanzKm))
    }

    @Test
    fun `Zielfelder auch in der Pause`() {
        val pausiert = mitZiel.copy(pausiert = true)
        val zurueck = dekodiereAufzeichnungsZustand(kodiereAufzeichnungsZustand(pausiert))
        assertTrue(zurueck.pausiert)
        assertEquals(pausiert.ziel, zurueck.ziel)
    }

    @Test
    fun `kaputte Zielfelder fallen einzeln weg, der Zustand bleibt`() {
        val basis = """"laeuft":true,"pausiert":false,"dauerMs":1000,"distanzKm":3.0"""
        for (wert in listOf("\"zwoelf\"", "-5", "0", "1e9", "\"NaN\"", "\"Infinity\"", "null", "{}", "[1]", "true")) {
            val zustand = dekodiereAufzeichnungsZustand("""{$basis,"zielRest":$wert,"laengste":40}""".toByteArray())
            assertEquals(3.0, zustand.distanzKm, "Wert $wert")
            assertEquals(ZielSnapshot(wochenRestKm = null, laengsteKm = 40.0), zustand.ziel, "Wert $wert")
        }
        val beideKaputt = dekodiereAufzeichnungsZustand("""{$basis,"zielRest":-1,"laengste":"x"}""".toByteArray())
        assertNull(beideKaputt.ziel)
    }

    @Test
    fun `Zielfelder werden auf 0,01 km gerundet und unplausible nicht geschrieben`() {
        val roh = mitZiel.copy(ziel = ZielSnapshot(wochenRestKm = 12.345678, laengsteKm = Double.NaN))
        val json = roh.toJson()
        assertEquals("12.35", (json["zielRest"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse(json.containsKey("laengste"))
    }

    // --- AbschlussInfo (tolerant) ---

    @Test
    fun `AbschlussInfo Roundtrip mit zwei Zeilen und Umlauten`() {
        val info = AbschlussInfo(
            rideId = "r-42",
            zeilen = listOf("Neue Bestweite: 82,4\u00A0km (vorher 61,0)", "Wochenziel geschafft: 152 von 150\u00A0km"),
        )
        val bytes = assertNotNull(kodiereAbschlussInfo(info))
        assertEquals(info, dekodiereAbschlussInfo(bytes))
    }

    @Test
    fun `AbschlussInfo kuerzt auf zwei Zeilen zu hoechstens 60 Zeichen`() {
        val lang = "x".repeat(80)
        val info = AbschlussInfo("r", listOf(lang, "  zwei  ", "drei"))
        val decoded = assertNotNull(dekodiereAbschlussInfo(assertNotNull(kodiereAbschlussInfo(info))))
        assertEquals(2, decoded.zeilen.size)
        assertEquals(AbschlussInfo.MAX_ZEICHEN, decoded.zeilen[0].length)
        assertTrue(decoded.zeilen[0].endsWith("…"))
        assertEquals("zwei", decoded.zeilen[1])
    }

    @Test
    fun `AbschlussInfo zerschneidet beim Kuerzen kein Surrogatpaar`() {
        // 58 Zeichen, dann ein Emoji (zwei UTF-16-Einheiten) genau auf der Schnittkante.
        val zeile = "a".repeat(58) + "\uD83D\uDEB4" + "b".repeat(10)
        val gekuerzt = bereinigeAbschlussZeilen(listOf(zeile)).single()
        assertFalse(Character.isHighSurrogate(gekuerzt[gekuerzt.length - 2]))
        assertTrue(gekuerzt.length <= AbschlussInfo.MAX_ZEICHEN)
        assertEquals("a".repeat(58) + "…", gekuerzt)
    }

    @Test
    fun `AbschlussInfo dekodiert auch zu lange Nutzlasten einer neueren Gegenseite gekuerzt`() {
        val text = """{"rideId":"r","zeilen":["eins","zwei","drei"],"neu":1}"""
        assertEquals(AbschlussInfo("r", listOf("eins", "zwei")), dekodiereAbschlussInfo(text.toByteArray()))
    }

    @Test
    fun `AbschlussInfo ohne Zeilen wird nicht kodiert`() {
        assertNull(kodiereAbschlussInfo(AbschlussInfo("r", emptyList())))
        assertNull(kodiereAbschlussInfo(AbschlussInfo("r", listOf("", "   "))))
    }

    @Test
    fun `AbschlussInfo dekodiert leer kaputt oder unvollstaendig zu null statt zu werfen`() {
        val faelle = listOf(
            "",
            "{",
            "null",
            "[]",
            "\"text\"",
            "{}",
            """{"rideId":"r"}""",
            """{"zeilen":["eins"]}""",
            """{"rideId":"r","zeilen":[]}""",
            """{"rideId":"r","zeilen":"eins"}""",
            """{"rideId":7,"zeilen":["eins"]}""",
            """{"rideId":"r","zeilen":[1,null,{}," "]}""",
        )
        for (fall in faelle) assertNull(dekodiereAbschlussInfo(fall.toByteArray()), fall)
        assertNull(dekodiereAbschlussInfo(byteArrayOf(0xFF.toByte(), 0x00, 0x13)))
    }

    @Test
    fun `AbschlussInfo ueberspringt einzelne Nicht-Text-Eintraege`() {
        val text = """{"rideId":"r","zeilen":[1,"eins",null]}"""
        assertEquals(AbschlussInfo("r", listOf("eins")), dekodiereAbschlussInfo(text.toByteArray()))
    }

    // --- Bytes sind echtes UTF-8-JSON ---

    @Test
    fun `kodierte Bytes sind lesbares UTF-8-JSON`() {
        val bytes = kodiereBefehl(Befehl(Befehl.START))
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"cmd\""))
        assertTrue(text.contains("\"start\""))
    }
}
