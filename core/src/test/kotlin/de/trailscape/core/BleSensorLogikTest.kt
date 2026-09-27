package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Zustandslogik der Bluetooth-Sensoren: Drehzahl, Leistungsmittel, Frische, Neuversuch, Vorrang, Prefs. */
class BleSensorLogikTest {

    // ------------------------------------------------------- DrehzahlRechner

    @Test
    fun `erster Wert ist nur Basis`() {
        assertNull(DrehzahlRechner.kurbel().biete(Umdrehungen(10, 1000), 0))
    }

    @Test
    fun `zwei Umdrehungen in 1024 Ticks sind 120 U pro min`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 1000), 0)
        assertEquals(120.0, r.biete(Umdrehungen(12, 2024), 1000)!!, 1e-9)
    }

    @Test
    fun `Ueberlauf der Ereigniszeit`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 65000), 0)
        // 65000 → 440: 976 Ticks fuer eine Umdrehung.
        assertEquals(60.0 * 1024 / 976, r.biete(Umdrehungen(11, 440), 1000)!!, 1e-9)
    }

    @Test
    fun `Ueberlauf des 16-Bit-Kurbelzaehlers`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(65535, 0), 0)
        // 65535 → 1: zwei Umdrehungen in einer Sekunde.
        assertEquals(120.0, r.biete(Umdrehungen(1, 1024), 1000)!!, 1e-9)
    }

    @Test
    fun `Ueberlauf des 32-Bit-Radzaehlers`() {
        val r = DrehzahlRechner.cscRad()
        r.biete(Umdrehungen(4294967294L, 0), 0)
        // 4 Umdrehungen in 1 s = 240 U/min.
        assertEquals(240.0, r.biete(Umdrehungen(2, 1024), 1000)!!, 1e-9)
    }

    @Test
    fun `CP-Rad laeuft mit 2048 Ticks`() {
        val r = DrehzahlRechner.cpRad()
        r.biete(Umdrehungen(100, 0), 0)
        assertEquals(180.0, r.biete(Umdrehungen(103, 2048), 1000)!!, 1e-9)
        // Dieselben Ticks am CSC-Rad waeren die halbe Zeit — doppelte Drehzahl.
        val csc = DrehzahlRechner.cscRad()
        csc.biete(Umdrehungen(100, 0), 0)
        assertEquals(90.0, csc.biete(Umdrehungen(103, 2048), 1000)!!, 1e-9)
    }

    @Test
    fun `identisches Paket haelt den Wert und faellt nach 3 s auf 0`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 0), 0)
        assertEquals(60.0, r.biete(Umdrehungen(11, 1024), 1000)!!, 1e-9)
        assertEquals(60.0, r.biete(Umdrehungen(11, 1024), 2000)!!, 1e-9)
        assertEquals(60.0, r.biete(Umdrehungen(11, 1024), 3999)!!, 1e-9)
        assertEquals(0.0, r.biete(Umdrehungen(11, 1024), 4000)!!, 1e-9)
        // Weitertreten rechnet wieder ab der letzten Umdrehung.
        assertEquals(60.0, r.biete(Umdrehungen(12, 2048), 5000)!!, 1e-9)
    }

    @Test
    fun `nach 20 s Rollen kein Scheinwert, danach wieder 90 U pro min`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 0), 0)
        // 90 U/min: eine Umdrehung je 2/3 s = 683 Ticks.
        assertEquals(90.0, r.biete(Umdrehungen(11, 683), 667)!!, 0.2)
        // 20 s Rollen: der Sensor wiederholt das letzte Paket.
        assertEquals(0.0, r.biete(Umdrehungen(11, 683), 10_000)!!, 1e-9)
        // Erste Umdrehung nach der Pause: 20 s Ereigniszeit — kein Wert zwischen 1 und 10.
        val erster = r.biete(Umdrehungen(12, 683 + 20_480), 20_700)
        assertEquals(0.0, erster!!, 1e-9)
        // Die zweite rechnet wieder echt.
        assertEquals(90.0, r.biete(Umdrehungen(13, 683 + 20_480 + 683), 21_367)!!, 0.2)
    }

    @Test
    fun `dTicks 0 bei neuer Umdrehung wird ignoriert`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 0), 0)
        assertEquals(60.0, r.biete(Umdrehungen(11, 1024), 1000)!!, 1e-9)
        assertEquals(60.0, r.biete(Umdrehungen(12, 1024), 2000)!!, 1e-9)
        // Das naechste gueltige Paket rechnet ueber beide Umdrehungen.
        assertEquals(60.0, r.biete(Umdrehungen(13, 3072), 3000)!!, 1e-9)
    }

    @Test
    fun `Unplausibles ergibt eine neue Basis`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 0), 0)
        // 10 Umdrehungen in 1/1024 s — Unsinn.
        assertNull(r.biete(Umdrehungen(20, 1), 1000))
        assertEquals(60.0, r.biete(Umdrehungen(21, 1025), 2000)!!, 1e-9)
    }

    @Test
    fun `Luecke laenger als die Ueberlaufperiode ergibt null`() {
        val r = DrehzahlRechner.kurbel()
        r.biete(Umdrehungen(10, 0), 0)
        assertNull(r.biete(Umdrehungen(11, 1024), 70_000))
        assertEquals(60.0, r.biete(Umdrehungen(12, 2048), 71_000)!!, 1e-9)
    }

    @Test
    fun `Radtempo aus Drehzahl und Umfang`() {
        assertEquals(22.734, radTempoKmh(180.0), 0.001)
        assertEquals(0.0, radTempoKmh(0.0), 0.0)
    }

    // ------------------------------------------------------ LeistungsPuffer

    @Test
    fun `Anzeigemittel ueber 3 s`() {
        val p = LeistungsPuffer()
        p.add(0, 100)
        p.add(1000, 200)
        p.add(2000, 300)
        p.add(3000, 400)
        // Fenster (500, 3500]: 200, 300, 400.
        assertEquals(300.0, p.anzeigeMittel(3500)!!, 1e-9)
    }

    @Test
    fun `Punktmittel im Intervall`() {
        val p = LeistungsPuffer()
        listOf(100, 200, 300, 400, 500).forEachIndexed { i, w -> p.add(i * 1000L, w) }
        assertEquals(400.0, p.punktMittel(1000, 4000)!!, 1e-9)
    }

    @Test
    fun `ohne neue Werte gilt der frische letzte`() {
        val p = LeistungsPuffer()
        p.add(1000, 250)
        assertEquals(250.0, p.punktMittel(1500, 3000)!!, 1e-9)
        assertEquals(250.0, p.punktMittel(1500, 6000)!!, 1e-9)
    }

    @Test
    fun `still ergibt null`() {
        val p = LeistungsPuffer()
        assertNull(p.anzeigeMittel(0))
        assertNull(p.punktMittel(0, 1000))
        p.add(1000, 250)
        assertNull(p.anzeigeMittel(10_000))
        assertNull(p.punktMittel(2000, 10_000))
    }

    @Test
    fun `negative Leistung wird 0`() {
        val p = LeistungsPuffer()
        p.add(1000, -20)
        assertEquals(0.0, p.anzeigeMittel(1000)!!, 1e-9)
    }

    @Test
    fun `alte Werte fallen heraus`() {
        val p = LeistungsPuffer(halteMs = 10_000)
        p.add(0, 1000)
        p.add(20_000, 100)
        assertEquals(100.0, p.punktMittel(-1, 20_000)!!, 1e-9)
    }

    // ---------------------------------------------------------------- Frische

    @Test
    fun `Frische-Grenze genau 5 s`() {
        assertTrue(istFrisch(0, 5000))
        assertTrue(!istFrisch(0, 5001))
        assertTrue(!istFrisch(null, 0))
        assertEquals(12, stillSeitS(0, 12_999))
        assertNull(stillSeitS(null, 1000))
        assertEquals(0, stillSeitS(2000, 1000))
    }

    // ------------------------------------------------------------ Neuversuch

    @Test
    fun `Neuversuch-Takt 2, 5, 10, 30, 30 s`() {
        val z = BleVerbindungsZustand()
        z.start()
        assertEquals(BleVerbindung.VERBINDE, z.status)
        val folge = (1..5).map { z.getrennt(0)!! }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L), folge)
        assertEquals(BleVerbindung.WARTET, z.status)
        assertEquals(30_000L, z.naechsterVersuchMs)
    }

    @Test
    fun `Reset nach erhaltenen Daten`() {
        val z = BleVerbindungsZustand()
        z.start()
        z.getrennt(0)
        z.getrennt(0)
        z.neuerVersuch()
        z.verbunden()
        assertEquals(BleVerbindung.VERBUNDEN, z.status)
        // Nur verbunden reicht nicht — erst Daten beweisen eine gesunde Verbindung.
        assertEquals(10_000L, z.getrennt(100)!! - 100)
        z.neuerVersuch()
        z.verbunden()
        z.datenErhalten()
        assertEquals(1_000L + 2_000L, z.getrennt(1_000))
    }

    @Test
    fun `stop verhindert Neuversuch`() {
        val z = BleVerbindungsZustand()
        z.start()
        z.stop()
        assertNull(z.getrennt(0))
        assertEquals(BleVerbindung.AUS, z.status)
        assertNull(z.naechsterVersuchMs)
    }

    // ------------------------------------------------------ liveSensorAnzeige

    private val jetzt = 100_000L
    private fun kanal(wert: Int?, vorMs: Long?) =
        BleKanal(aktiv = true, wert = wert, zeitMs = vorMs?.let { jetzt - it })

    @Test
    fun `frischer Gurt schlaegt die Uhr`() {
        val a = liveSensorAnzeige(jetzt, kanal(142, 1000), BleKanal.AUS, BleKanal.AUS, uhrBpm = 120, uhrVerbunden = true)
        assertEquals(LiveKachel(142, null), a.puls)
        assertEquals(PulsQuelle.GURT, a.pulsQuelle)
        assertNull(a.leistung)
        assertNull(a.trittfrequenz)
        assertEquals(1, a.anzahl)
    }

    @Test
    fun `stiller Gurt mit Uhr - die Uhr springt ein`() {
        val a = liveSensorAnzeige(jetzt, kanal(142, 9000), BleKanal.AUS, BleKanal.AUS, uhrBpm = 120, uhrVerbunden = true)
        assertEquals(LiveKachel(120, null), a.puls)
        assertEquals(PulsQuelle.UHR, a.pulsQuelle)
    }

    @Test
    fun `stiller Gurt ohne Uhr - Strich mit Stillzeit`() {
        val a = liveSensorAnzeige(jetzt, kanal(142, 9000), BleKanal.AUS, BleKanal.AUS, uhrBpm = null, uhrVerbunden = false)
        assertEquals(LiveKachel(null, 9), a.puls)
        assertEquals(PulsQuelle.GURT, a.pulsQuelle)
    }

    @Test
    fun `nur Uhr`() {
        val a = liveSensorAnzeige(jetzt, BleKanal.AUS, BleKanal.AUS, BleKanal.AUS, uhrBpm = 131, uhrVerbunden = true)
        assertEquals(LiveKachel(131, null), a.puls)
        assertEquals(PulsQuelle.UHR, a.pulsQuelle)
    }

    @Test
    fun `Uhr-Wert ohne watchConnected - keine Kachel`() {
        val a = liveSensorAnzeige(jetzt, BleKanal.AUS, BleKanal.AUS, BleKanal.AUS, uhrBpm = 131, uhrVerbunden = false)
        assertEquals(LiveSensorAnzeige.LEER, a)
        assertEquals(0, a.anzahl)
    }

    @Test
    fun `bpm 0 zaehlt nicht`() {
        val a = liveSensorAnzeige(jetzt, kanal(0, 500), BleKanal.AUS, BleKanal.AUS, uhrBpm = 0, uhrVerbunden = true)
        assertEquals(LiveKachel(null, 0), a.puls)
        assertEquals(PulsQuelle.GURT, a.pulsQuelle)
    }

    @Test
    fun `Leistung aktiv, aber noch nie ein Wert - Strich ohne Stillzeit`() {
        val a = liveSensorAnzeige(jetzt, BleKanal.AUS, kanal(null, null), BleKanal.AUS, null, false)
        assertNull(a.puls)
        assertEquals(LiveKachel(null, null), a.leistung)
    }

    @Test
    fun `Leistung frisch, Trittfrequenz still`() {
        val a = liveSensorAnzeige(jetzt, BleKanal.AUS, kanal(215, 1000), kanal(88, 12_000), null, false)
        assertEquals(LiveKachel(215, null), a.leistung)
        assertEquals(LiveKachel(null, 12), a.trittfrequenz)
        assertEquals(2, a.anzahl)
    }

    // ------------------------------------------------------- Punkt-Werte

    @Test
    fun `Punktwerte - Gurt vor Uhr, Uhr auch ohne Verbindungsstatus`() {
        val mitGurt = punktSensorWerte(jetzt, kanal(150, 1000), 212.6, kanal(90, 1000), uhrBpm = 120, uhrVerbunden = true)
        assertEquals(PunktSensorWerte(150, 213, 90), mitGurt)
        val stillerGurt = punktSensorWerte(jetzt, kanal(150, 8000), null, kanal(90, 8000), uhrBpm = 120, uhrVerbunden = true)
        assertEquals(PunktSensorWerte(120, null, null), stillerGurt)
        assertEquals(PunktSensorWerte.LEER, punktSensorWerte(jetzt, BleKanal.AUS, null, BleKanal.AUS, null, uhrVerbunden = false))
        // Reiner Uhr-Nutzer: der Uhr-Wert zaehlt auch ohne Verbindungsstatus.
        assertEquals(120, punktSensorWerte(jetzt, BleKanal.AUS, null, BleKanal.AUS, 120, uhrVerbunden = false).hr)
    }

    @Test
    fun `Punktwerte - Gurt aktiv, still, Uhr getrennt ergibt keinen Puls`() {
        val w = punktSensorWerte(jetzt, kanal(150, 8000), null, BleKanal.AUS, uhrBpm = 120, uhrVerbunden = false)
        assertNull(w.hr)
    }

    // ---------------------------------------------------- Gemerkte Sensoren

    @Test
    fun `gemerkte Sensoren Roundtrip`() {
        val liste = listOf(
            GemerkterSensor(BleSensorTyp.PULS, "AA:BB:CC:DD:EE:FF", "Polar H10 „Test“"),
            GemerkterSensor(BleSensorTyp.LEISTUNG, "11:22:33:44:55:66", null),
        )
        assertEquals(liste, dekodiereGemerkteSensoren(kodiereGemerkteSensoren(liste)))
    }

    @Test
    fun `Muell und leer ergeben eine leere Liste`() {
        assertTrue(dekodiereGemerkteSensoren(null).isEmpty())
        assertTrue(dekodiereGemerkteSensoren("").isEmpty())
        assertTrue(dekodiereGemerkteSensoren("{kaputt").isEmpty())
        assertTrue(dekodiereGemerkteSensoren("{\"typ\":\"PULS\"}").isEmpty())
        assertTrue(dekodiereGemerkteSensoren("[1, \"x\", {\"typ\":\"PULS\"}]").isEmpty())
    }

    @Test
    fun `unbekannter Typ wird uebersprungen, je Typ gewinnt der letzte`() {
        val raw = """[{"typ":"RADAR","adresse":"A"},{"typ":"PULS","adresse":"B"},{"typ":"PULS","adresse":"C","name":"Neu"}]"""
        assertEquals(listOf(GemerkterSensor(BleSensorTyp.PULS, "C", "Neu")), dekodiereGemerkteSensoren(raw))
    }

    @Test
    fun `merke ersetzt denselben Typ, vergiss entfernt ihn`() {
        val a = GemerkterSensor(BleSensorTyp.PULS, "A", "Alt")
        val b = GemerkterSensor(BleSensorTyp.TRITTFREQUENZ, "B", null)
        val c = GemerkterSensor(BleSensorTyp.PULS, "C", "Neu")
        val liste = merke(merke(merke(emptyList(), a), b), c)
        assertEquals(listOf(b, c), liste)
        assertEquals(listOf(c), vergiss(liste, BleSensorTyp.TRITTFREQUENZ))
        assertEquals(liste, vergiss(liste, BleSensorTyp.LEISTUNG))
    }
}
