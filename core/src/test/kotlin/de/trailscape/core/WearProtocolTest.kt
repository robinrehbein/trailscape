package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals("trailscape_telefon", FAEHIGKEIT_TELEFON)
        assertEquals("trailscape_uhr", FAEHIGKEIT_UHR)
    }

    @Test
    fun `alle vier Pfade sind paarweise verschieden`() {
        val pfade = setOf(PFAD_SENSOR, PFAD_BEFEHL_AN_TELEFON, PFAD_BEFEHL_AN_UHR, PFAD_ZUSTAND)
        assertEquals(4, pfade.size)
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

    // --- Bytes sind echtes UTF-8-JSON ---

    @Test
    fun `kodierte Bytes sind lesbares UTF-8-JSON`() {
        val bytes = kodiereBefehl(Befehl(Befehl.START))
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("\"cmd\""))
        assertTrue(text.contains("\"start\""))
    }
}
