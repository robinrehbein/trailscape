package de.trailscape.core.indoor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verbindungsautomat des Schalttasters gegen die Attrappe [ClickFakeLink]. */
class ZwiftClickShifterTest {

    private val link = ClickFakeLink()
    private val rec = ClickRecorder()
    private val shifter = ZwiftClickShifter(link)

    private val echo = "52 69 64 65 4F 6E"
    private val v1Plus = "37 08 00 10 01"
    private val v1Minus = "37 08 01 10 00"
    private val v1Both = "37 08 00 10 00"
    private val v1None = "37 08 01 10 01"

    private val v1Setup = listOf(
        "sub zwift async notify",
        "sub zwift tx indicate",
        "write zwift rx 52 69 64 65 4F 6E req",
    )
    private val v2Setup = listOf(
        "sub fc82 async notify",
        "sub fc82 tx indicate",
        "write fc82 rx 52 69 64 65 4F 6E 02 03 req",
    )

    /** Bis READY ueber V1; danach sind Ereignisse und Operationen geleert. */
    private fun readyV1() {
        shifter.start(rec)
        shifter.tick(0)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.syncTx(echo)
        assertEquals(listOf("conn CONNECTING", "conn READY"), rec.take())
        link.ops.clear()
    }

    // ------------------------------------------------------------ Ablauf

    @Test
    fun `Ablauf V1 bis READY`() {
        shifter.start(rec)
        assertEquals(listOf("conn CONNECTING"), rec.take())
        assertEquals(1, link.opens)
        assertTrue(link.ops.isEmpty())

        link.state(BleLinkState.CONNECTING)
        assertEquals(emptyList(), rec.take(), "CONNECTING wird nicht doppelt gemeldet")

        link.ready(IndoorUuids.ZWIFT_SERVICE, IndoorUuids.FTMS_SERVICE)
        assertEquals(v1Setup, link.ops)
        assertEquals(ClickVariant.V1, shifter.detectedVariant)
        assertEquals(emptyList(), rec.take())

        link.syncTx(echo)
        assertEquals(listOf("conn READY"), rec.take())

        link.async(v1Plus, 100)
        assertEquals(listOf("shift UP"), rec.take())
        link.async(v1None, 200)
        link.async(v1Minus, 300)
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `Ablauf V2 - Dienst fc82 hat Vorrang`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE, IndoorUuids.ZWIFT_RIDE_SERVICE)
        assertEquals(v2Setup, link.ops)
        assertEquals(ClickVariant.V2, shifter.detectedVariant)

        link.syncTx("52 69 64 65 4F 6E 02 03")
        assertEquals(listOf("conn CONNECTING", "conn READY"), rec.take())

        link.async("23 08 FF FF FF FF 0F 12 18", 10) // Leerlauf, abgeschnitten
        assertEquals(emptyList(), rec.take())
        link.async("23 08 FF BF FF 7F", 20) // hoch rechts
        assertEquals(listOf("shift UP"), rec.take())
        link.async("23 08 FF FF FF FF 0F", 30)
        link.async("23 08 FF F7 FF 7F", 40) // runter links (0x400)
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `Dienste in Grossbuchstaben werden erkannt`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_RIDE_SERVICE.uppercase())
        assertEquals(v2Setup, link.ops)
    }

    @Test
    fun `ohne Zwift-Dienst UNSUPPORTED, Rohr wird geschlossen, danach Ruhe`() {
        shifter.start(rec)
        link.ready(IndoorUuids.FTMS_SERVICE)
        assertEquals(listOf("conn CONNECTING", "conn UNSUPPORTED"), rec.take())
        assertEquals(listOf("close"), link.ops)
        assertNotNull(shifter.diagnose)

        link.state(BleLinkState.CLOSED)
        link.state(BleLinkState.RECONNECTING)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.async(v1Plus)
        shifter.tick(100_000)
        assertEquals(emptyList(), rec.take())
        assertEquals(listOf("close"), link.ops)
    }

    @Test
    fun `zweites start wird ignoriert`() {
        shifter.start(rec)
        shifter.start(ClickRecorder())
        assertEquals(1, link.opens)
        assertEquals(listOf("conn CONNECTING"), rec.take())
    }

    @Test
    fun `Name aus dem Rohr oder Rueckfall`() {
        assertEquals("Zwift Click", shifter.name)
        assertEquals("Schalttaster", ZwiftClickShifter(ClickFakeLink(deviceName = null)).name)
    }

    // ------------------------------------------------------------ Frist und Wiederholung

    @Test
    fun `Frist 5 s, eine Wiederholung, dann UNSUPPORTED`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        assertEquals(v1Setup, link.ops)
        link.ops.clear()
        shifter.tick(1_000) // verankert die Frist

        shifter.tick(5_999)
        assertTrue(link.ops.isEmpty())
        shifter.tick(6_000)
        assertEquals(v1Setup, link.ops, "Einrichtung komplett wiederholt")
        link.ops.clear()
        assertEquals(listOf("conn CONNECTING"), rec.take())

        shifter.tick(10_999)
        assertTrue(link.ops.isEmpty())
        shifter.tick(11_000)
        assertEquals(listOf("conn UNSUPPORTED"), rec.take())
        assertEquals(listOf("close"), link.ops)
        assertTrue(shifter.diagnose!!.contains("keine Antwort"))
    }

    @Test
    fun `Echo im zweiten Versuch reicht`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        shifter.tick(0)
        shifter.tick(5_000)
        link.syncTx(echo)
        shifter.tick(20_000)
        assertEquals(listOf("conn CONNECTING", "conn READY"), rec.take())
    }

    @Test
    fun `ohne vorherigen tick beginnt die Frist mit dem ersten tick`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.ops.clear()
        shifter.tick(100)
        shifter.tick(5_099)
        assertTrue(link.ops.isEmpty())
        shifter.tick(5_100)
        assertEquals(v1Setup, link.ops)
    }

    @Test
    fun `gescheiterte Operation bricht nicht ab, nur Diagnose`() {
        shifter.start(rec)
        shifter.tick(0)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.result(BleOperation.SUBSCRIBE, IndoorUuids.ZWIFT_SYNC_TX, ok = false, status = 133)
        assertTrue(shifter.diagnose!!.contains("133"))
        assertEquals(listOf("conn CONNECTING"), rec.take())
        link.syncTx(echo)
        assertEquals(listOf("conn READY"), rec.take())
    }

    // ------------------------------------------------------------ Antworten

    @Test
    fun `Echo fehlt, aber Tastenrahmen kommen - READY und Druck zaehlt`() {
        shifter.start(rec)
        shifter.tick(0)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.async(v1Minus, 1_000)
        assertEquals(listOf("conn CONNECTING", "conn READY", "shift DOWN"), rec.take())
        shifter.tick(1_500)
        assertEquals(listOf("shift DOWN"), rec.take(), "nur die Wiederholung der gehaltenen Taste")
        shifter.tick(20_000) // keine Frist mehr
        link.async(v1None, 20_001)
        shifter.tick(30_000)
        assertEquals(emptyList(), rec.take())
        assertTrue(link.ops.none { it == "close" })
    }

    @Test
    fun `Leerlauf und Batterie vor dem Echo machen nicht READY`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.async("15")
        link.async("19 10 5D")
        assertEquals(listOf("conn CONNECTING", "battery 93"), rec.take())
    }

    @Test
    fun `verschluesselte Antwort - UNSUPPORTED`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.syncTx("52 69 64 65 4F 6E 01 03 01 02 03 04 05 06 07 08 09 0A 0B 0C")
        assertEquals(listOf("conn CONNECTING", "conn UNSUPPORTED"), rec.take())
        assertTrue(shifter.diagnose!!.contains("verschluesselt"))
        assertEquals("close", link.ops.last())
    }

    @Test
    fun `gesperrte Antwort - UNSUPPORTED`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_RIDE_SERVICE)
        link.syncTx("FF 04 00 0A 15 40 C2 31")
        assertEquals(listOf("conn CONNECTING", "conn UNSUPPORTED"), rec.take())
        assertTrue(shifter.diagnose!!.contains("gesperrt"))
        assertEquals("close", link.ops.last())
    }

    @Test
    fun `unbekannte Antwort - weiter warten`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.syncTx("01 02 03")
        assertEquals(listOf("conn CONNECTING"), rec.take())
        link.syncTx(echo)
        assertEquals(listOf("conn READY"), rec.take())
    }

    @Test
    fun `Rahmen vor READY des Rohrs werden ignoriert`() {
        shifter.start(rec)
        link.async(v1Plus)
        link.syncTx(echo)
        link.async("19 10 5D")
        assertEquals(listOf("conn CONNECTING"), rec.take())
    }

    @Test
    fun `weiteres Echo oder Unbekanntes auf SyncTX nach READY stoert nicht`() {
        readyV1()
        link.syncTx(echo)
        link.syncTx("01 02")
        assertEquals(emptyList(), rec.take())
        assertTrue(link.ops.isEmpty())
    }

    @Test
    fun `Leerlauf vor dem Echo macht nicht READY, Sperre danach wirkt`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_RIDE_SERVICE)
        link.async("23 08 FF FF FF FF 0F", 100)
        link.async("37 08 01 10 01", 150)
        assertEquals(listOf("conn CONNECTING"), rec.take())
        link.syncTx("FF 04 00 0A")
        assertEquals(listOf("conn UNSUPPORTED"), rec.take())
        assertEquals("close", link.ops.last())
    }

    @Test
    fun `Sperr-Antwort nach READY durch Tastendruck - UNSUPPORTED`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_RIDE_SERVICE)
        link.async("23 08 FF BF FF 7F", 100)
        assertEquals(listOf("conn CONNECTING", "conn READY", "shift UP"), rec.take())
        link.syncTx("FF 04 00 0A")
        assertEquals(listOf("conn UNSUPPORTED"), rec.take())
        assertTrue(shifter.diagnose!!.contains("gesperrt"))
        shifter.tick(5_000)
        assertEquals(emptyList(), rec.take(), "keine Wiederholung mehr")
    }

    @Test
    fun `Schluessel-Antwort nach READY - UNSUPPORTED`() {
        readyV1()
        link.syncTx("52 69 64 65 4F 6E 01 03 01 02 03 04 05 06 07 08 09 0A 0B 0C")
        assertEquals(listOf("conn UNSUPPORTED"), rec.take())
        assertEquals(listOf("close"), link.ops)
    }

    @Test
    fun `veraltete tick-Zeit verankert die Frist nicht`() {
        shifter.start(rec)
        shifter.tick(0)
        // tick-Schleife pausiert lange; dann meldet das Rohr READY
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.ops.clear()
        shifter.tick(60_000) // verankert erst jetzt, kein Versuch verloren
        assertTrue(link.ops.isEmpty())
        shifter.tick(64_999)
        assertTrue(link.ops.isEmpty())
        shifter.tick(65_000)
        assertEquals(v1Setup, link.ops)
        assertEquals(listOf("conn CONNECTING"), rec.take())
    }

    // ------------------------------------------------------------ Tasten

    @Test
    fun `gehaltene Taste wiederholt nach 500 ms, dann alle 250 ms - exakt`() {
        readyV1()
        link.async(v1Plus, 1_000)
        assertEquals(listOf("shift UP"), rec.take())
        shifter.tick(1_499)
        assertEquals(emptyList(), rec.take())
        shifter.tick(1_500)
        assertEquals(listOf("shift UP"), rec.take())
        link.async(v1Plus, 1_600) // Wiederholung des Zustands: entprellt
        shifter.tick(1_749)
        assertEquals(emptyList(), rec.take())
        shifter.tick(1_750)
        assertEquals(listOf("shift UP"), rec.take())
        shifter.tick(2_000)
        assertEquals(listOf("shift UP"), rec.take())
        link.async(v1None, 2_100)
        shifter.tick(2_250)
        shifter.tick(5_000)
        assertEquals(emptyList(), rec.take())
    }

    @Test
    fun `spaeter tick holt nicht nach - keine Salven, Abstand mindestens 250 ms`() {
        readyV1()
        link.async(v1Minus, 1_000)
        link.async(v1Minus, 2_900) // Geraet wiederholt den Zustand
        rec.take()
        shifter.tick(3_000) // lange kein tick
        assertEquals(listOf("shift DOWN"), rec.take())
        shifter.tick(3_249)
        assertEquals(emptyList(), rec.take())
        shifter.tick(3_250)
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `Wiederholungen bei 10-Hz-tick zaehlen`() {
        readyV1()
        link.async(v1Plus, 0)
        var t = 0L
        while (t <= 2_000) {
            if (t % 500 == 0L) link.async(v1Plus, t) // Wiederholung bestaetigt den Druck
            shifter.tick(t)
            t += 100
        }
        // Druck bei 0, dann 500, 800, 1100, 1400, 1700, 2000: Der naechste Termin
        // liegt 250 ms nach der tatsaechlichen Meldung, im 100-ms-Takt also 300 ms.
        assertEquals(List(7) { "shift UP" }, rec.take())
    }

    @Test
    fun `gleiche Zustandsmeldungen werden entprellt`() {
        readyV1()
        repeat(5) { link.async(v1Plus, 1_000L + it) }
        link.async("15", 1_010)
        link.async(v1Plus, 1_020)
        assertEquals(listOf("shift UP"), rec.take())
        repeat(3) { link.async(v1None, 1_100L + it) }
        repeat(3) { link.async(v1Plus, 1_200L + it) }
        assertEquals(listOf("shift UP"), rec.take())
    }

    @Test
    fun `kaputter Rahmen aendert den Tastenzustand nicht`() {
        readyV1()
        link.async(v1Plus, 1_000)
        link.async("37 08 00 10", 1_050) // abgeschnitten
        link.async(v1Plus, 1_100)
        assertEquals(listOf("shift UP"), rec.take())
        assertTrue(shifter.diagnose!!.contains("verworfen"))
        // Danach geht das Loslassen verloren (nur noch kaputte Rahmen):
        link.async("37 08 01 10", 1_300)
        shifter.tick(1_600)
        assertEquals(listOf("shift UP"), rec.take())
        for (t in listOf(1_850L, 2_100L, 2_350L, 2_600L, 2_850L, 3_099L)) shifter.tick(t)
        assertEquals(List(5) { "shift UP" }, rec.take()) // 1850 … 2850; 3099 liegt vor 3100
        shifter.tick(3_100) // 2 s ohne bestaetigenden Tastenrahmen seit 1100
        shifter.tick(10_000)
        assertEquals(emptyList(), rec.take())
        assertTrue(shifter.diagnose!!.contains("losgelassen"))
        link.async(v1Plus, 10_100)
        assertEquals(listOf("shift UP"), rec.take(), "naechster Druck ist wieder eine Flanke")
    }

    @Test
    fun `Haltezeit-Obergrenze gilt auch fuer beide Tasten`() {
        readyV1()
        link.async(v1Both, 1_000)
        rec.take()
        shifter.tick(3_000)
        link.async(v1Minus, 3_100) // nach der Freigabe: Minus ist eine neue Flanke
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `Leerlaufrahmen loest gehaltene Taste nicht`() {
        readyV1()
        link.async(v1Plus, 1_000)
        link.async("15", 1_100)
        link.async(v1Plus, 1_200)
        assertEquals(listOf("shift UP"), rec.take())
    }

    @Test
    fun `Click-Rahmen mit fehlendem Feld landet in der Diagnose`() {
        readyV1()
        link.async("37 08 00", 1_000)
        assertEquals(listOf("shift UP"), rec.take())
        assertTrue(shifter.diagnose!!.contains("Tastenfeld"))
    }

    @Test
    fun `beide gleichzeitig - beide Flanken, keine Wiederholung`() {
        readyV1()
        link.async(v1Both, 1_000)
        assertEquals(listOf("shift UP", "shift DOWN"), rec.take())
        shifter.tick(1_500)
        shifter.tick(3_000)
        assertEquals(emptyList(), rec.take())
    }

    @Test
    fun `zweite Taste dazu, dann eine los - die verbliebene wiederholt mit frischem Vorlauf`() {
        readyV1()
        link.async(v1Plus, 1_000)
        link.async(v1Both, 1_200)
        assertEquals(listOf("shift UP", "shift DOWN"), rec.take())
        shifter.tick(1_500)
        shifter.tick(2_000)
        assertEquals(emptyList(), rec.take())
        link.async(v1Minus, 2_100) // Plus los, Minus bleibt
        assertEquals(emptyList(), rec.take())
        shifter.tick(2_599)
        assertEquals(emptyList(), rec.take())
        shifter.tick(2_600)
        assertEquals(listOf("shift DOWN"), rec.take())
        shifter.tick(2_850)
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `Wechsel direkt von Plus auf Minus`() {
        readyV1()
        link.async(v1Plus, 1_000)
        link.async(v1Minus, 1_100)
        assertEquals(listOf("shift UP", "shift DOWN"), rec.take())
        shifter.tick(1_599)
        assertEquals(emptyList(), rec.take())
        shifter.tick(1_600)
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `Play-Rahmen nach READY werden ignoriert`() {
        readyV1()
        link.async("07-08-00-10-01-18-01-20-00-28-01-30-01-38-01-40-00-48-00", 1_000)
        shifter.tick(5_000)
        assertEquals(emptyList(), rec.take())
    }

    @Test
    fun `Batterie nur bei Aenderung`() {
        readyV1()
        link.async("19 10 5D")
        link.async("19 10 5D")
        link.async("19 10 5C")
        link.async("19 10 65") // unplausibel
        assertEquals(listOf("battery 93", "battery 92"), rec.take())
    }

    // ------------------------------------------------------------ Rohrzustaende

    @Test
    fun `Neuverbinden - CONNECTING, gehaltene Taste vergessen, Einrichtung neu`() {
        readyV1()
        link.async(v1Plus, 1_000)
        assertEquals(listOf("shift UP"), rec.take())

        link.state(BleLinkState.RECONNECTING)
        assertEquals(listOf("conn CONNECTING"), rec.take())
        shifter.tick(2_000)
        assertEquals(emptyList(), rec.take(), "keine Wiederholung waehrend der Trennung")
        link.async(v1Plus, 2_100)
        assertEquals(emptyList(), rec.take(), "Rahmen ohne Verbindung zaehlen nicht")

        link.ready(IndoorUuids.ZWIFT_SERVICE)
        assertEquals(v1Setup, link.ops)
        link.syncTx(echo)
        assertEquals(listOf("conn READY"), rec.take())
        link.async(v1Plus, 3_000)
        assertEquals(listOf("shift UP"), rec.take(), "frische Flanke nach dem Neuverbinden")
    }

    @Test
    fun `Neuverbinden waehrend des Handschlags setzt die Frist zurueck`() {
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        shifter.tick(0)
        shifter.tick(5_000) // Versuch 2
        link.state(BleLinkState.RECONNECTING)
        shifter.tick(10_000)
        assertEquals(listOf("conn CONNECTING"), rec.take())
        link.ops.clear()
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        shifter.tick(10_001)
        shifter.tick(15_000)
        shifter.tick(15_001) // wieder Versuch 2, nicht UNSUPPORTED
        assertEquals(v1Setup + v1Setup, link.ops)
        assertEquals(emptyList(), rec.take())
    }

    @Test
    fun `NO_PERMISSION, BLUETOOTH_OFF und CLOSED werden durchgereicht`() {
        shifter.start(rec)
        link.state(BleLinkState.NO_PERMISSION)
        link.state(BleLinkState.BLUETOOTH_OFF)
        link.state(BleLinkState.CONNECTING)
        link.state(BleLinkState.CLOSED)
        assertEquals(
            listOf("conn CONNECTING", "conn NO_PERMISSION", "conn BLUETOOTH_OFF", "conn CONNECTING", "conn OFF"),
            rec.take(),
        )
    }

    // ------------------------------------------------------------ stop

    @Test
    fun `stop schliesst einmal und vergisst den Listener`() {
        readyV1()
        link.async(v1Plus, 1_000)
        rec.take()
        shifter.stop()
        shifter.stop()
        assertEquals(1, link.closes)
        link.async(v1Minus, 1_100)
        link.state(BleLinkState.CLOSED)
        shifter.tick(5_000)
        shifter.start(rec)
        assertEquals(emptyList(), rec.take())
        assertEquals(1, link.opens)
    }

    @Test
    fun `stop vor start schliesst nichts und start danach bleibt wirkungslos`() {
        shifter.stop()
        shifter.start(rec)
        assertEquals(0, link.closes)
        assertEquals(0, link.opens)
        assertEquals(emptyList(), rec.take())
    }

    @Test
    fun `stop nach UNSUPPORTED - close ist idempotent im Rohr`() {
        shifter.start(rec)
        link.ready(IndoorUuids.FTMS_SERVICE)
        shifter.stop()
        assertEquals(2, link.closes) // einmal vom Automaten, einmal von stop; das Rohr vertraegt das
        assertNull(link.ops.firstOrNull { it.startsWith("write") })
    }

    // ------------------------------------------------------------ Reentranz und Threads

    @Test
    fun `Rohr antwortet synchron aus write`() {
        link.onWrite = { l, _ -> l.syncTx(echo) }
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        assertEquals(listOf("conn CONNECTING", "conn READY"), rec.take())
        assertEquals(v1Setup, link.ops)
    }

    @Test
    fun `Rohr meldet synchron aus open`() {
        val l = object : BleLink by link {
            override fun open(listener: BleLinkListener) {
                link.open(listener)
                listener.onLinkState(BleLinkState.READY, setOf(IndoorUuids.ZWIFT_SERVICE))
            }
        }
        link.onWrite = { f, _ -> f.syncTx(echo) }
        val s = ZwiftClickShifter(l)
        s.start(rec)
        assertEquals(listOf("conn CONNECTING", "conn READY"), rec.take())
    }

    @Test
    fun `Listener ruft stop aus onConnection`() {
        rec.onConnectionHook = { if (it == ShifterConnection.READY) shifter.stop() }
        link.onWrite = { l, _ ->
            l.syncTx(echo)
            l.async(v1Plus) // kommt nach dem stop nicht mehr an
        }
        shifter.start(rec)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        assertEquals(listOf("conn CONNECTING", "conn READY"), rec.take())
        assertEquals(1, link.closes)
    }

    @Test
    fun `wirft der Listener, bleibt der Automat bedienbar`() {
        var wirf = true
        val boese = object : ShifterListener {
            override fun onConnection(state: ShifterConnection) {
                rec.onConnection(state)
            }

            override fun onShift(event: ShifterEvent) {
                if (wirf) {
                    wirf = false
                    throw IllegalStateException("Listener-Fehler")
                }
                rec.onShift(event)
            }

            override fun onBattery(percent: Int) = rec.onBattery(percent)
        }
        shifter.start(boese)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.syncTx(echo)
        rec.take()
        runCatching { link.async(v1Plus, 1_000) }
        link.async(v1None, 1_100)
        link.async(v1Minus, 1_200)
        assertEquals(listOf("shift DOWN"), rec.take())
    }

    @Test
    fun `zwei Threads - tick und Rahmen - ohne Verklemmung`() {
        val sicher = object : ShifterListener {
            val n = java.util.concurrent.atomic.AtomicInteger()
            override fun onConnection(state: ShifterConnection) = Unit
            override fun onShift(event: ShifterEvent) {
                n.incrementAndGet()
            }
            override fun onBattery(percent: Int) = Unit
        }
        shifter.start(sicher)
        link.ready(IndoorUuids.ZWIFT_SERVICE)
        link.syncTx(echo)
        val fertig = CountDownLatch(2)
        val a = thread {
            for (i in 0 until 20_000) shifter.tick(i.toLong())
            fertig.countDown()
        }
        val b = thread {
            for (i in 0 until 20_000) {
                link.async(if (i % 2 == 0) v1Plus else v1None, i.toLong())
            }
            fertig.countDown()
        }
        assertTrue(fertig.await(30, TimeUnit.SECONDS), "Verklemmung")
        a.join()
        b.join()
        assertTrue(sicher.n.get() >= 10_000, "jede Flanke gemeldet")
    }
}
