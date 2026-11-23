package de.trailscape.core.indoor

import de.trailscape.core.indoor.FakeBleLink.Op
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Der FTMS-Automat gegen die Attrappe [FakeBleLink]: Einrichtung, Kontrolle,
 * Befehlsschlange, Fristen, Neuverbinden, Reentranz und Nebenlaeufigkeit.
 */
class FtmsTrainerTest {
    private val svc = IndoorUuids.FTMS_SERVICE
    private val feature = IndoorUuids.FTMS_FEATURE
    private val range = IndoorUuids.FTMS_SUPPORTED_POWER_RANGE
    private val bike = IndoorUuids.FTMS_INDOOR_BIKE_DATA
    private val status = IndoorUuids.FTMS_MACHINE_STATUS
    private val cp = IndoorUuids.FTMS_CONTROL_POINT

    private val featureKickr = intArrayOf(0x02, 0x40, 0x00, 0x00, 0x08, 0x20, 0x00, 0x00)
    private val rangeKickr = intArrayOf(0x00, 0x00, 0xD0, 0x07, 0x01, 0x00)
    private val capsKickr = TrainerCapabilities(
        simulation = true,
        targetPower = true,
        minPowerW = 0,
        maxPowerW = 2000,
        reportsCadence = true,
    )

    private val sim1 = TrainerCommand.SetSimulation(0.0, 1.0, 0.004, 0.51)
    private val sim2 = TrainerCommand.SetSimulation(0.0, 2.0, 0.004, 0.51)
    private val sim2Hex = "11 00 00 C8 00 28 33"

    /** Zeichnet alle Ereignisse auf (threadsicher). */
    private class Aufzeichner : TrainerListener {
        val ereignisse = mutableListOf<Any>()
        var beiVerbindung: ((TrainerConnection) -> Unit)? = null

        data class Verbindung(val state: TrainerConnection, val caps: TrainerCapabilities)

        data class Fehler(val command: TrainerCommand, val reason: String)

        override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
            synchronized(ereignisse) { ereignisse += Verbindung(state, capabilities) }
            beiVerbindung?.invoke(state)
        }

        override fun onTelemetry(telemetry: TrainerTelemetry) {
            synchronized(ereignisse) { ereignisse += telemetry }
        }

        override fun onCommandFailed(command: TrainerCommand, reason: String) {
            synchronized(ereignisse) { ereignisse += Fehler(command, reason) }
        }

        fun zustaende() = synchronized(ereignisse) { ereignisse.filterIsInstance<Verbindung>().map { it.state } }

        fun letzterZustand() = zustaende().lastOrNull()

        fun fehler() = synchronized(ereignisse) { ereignisse.filterIsInstance<Fehler>() }

        fun telemetrie() = synchronized(ereignisse) { ereignisse.filterIsInstance<TrainerTelemetry>() }
    }

    private class Aufbau(val link: FakeBleLink, val trainer: IndoorTrainer, val l: Aufzeichner)

    private fun neu(name: String? = "KICKR CORE 1A2B"): Aufbau {
        val link = FakeBleLink(name)
        return Aufbau(link, ftmsTrainer(link), Aufzeichner())
    }

    /** Rohr READY bis einschliesslich Control-Point-Abo; danach steht Request Control aus. */
    private fun Aufbau.einrichten(ok: Boolean = true) {
        link.linkState(BleLinkState.READY, setOf(svc, "00001800-0000-1000-8000-00805f9b34fb"))
        link.value(feature, *featureKickr)
        link.result(BleOperation.READ, feature)
        link.value(range, *rangeKickr)
        link.result(BleOperation.READ, range)
        link.result(BleOperation.SUBSCRIBE, bike)
        link.result(BleOperation.SUBSCRIBE, status)
        link.result(BleOperation.SUBSCRIBE, cp, ok)
    }

    private fun Aufbau.antworte(opcode: Int, result: Int = 0x01) {
        link.result(BleOperation.WRITE, cp)
        link.value(cp, 0x80, opcode, result)
    }

    /** Start + tick(0) + Einrichtung + Kontrolle erteilt. */
    private fun Aufbau.bisReady() {
        trainer.start(l)
        trainer.tick(0)
        einrichten()
        antworte(0x00)
        assertEquals(TrainerConnection.READY, l.letzterZustand())
    }

    // ================================================================== Ablauf

    @Test
    fun `kompletter Ablauf bis READY in der vorgeschriebenen Reihenfolge`() {
        val a = neu()
        a.trainer.start(a.l)
        assertEquals(listOf(TrainerConnection.CONNECTING), a.l.zustaende())
        assertEquals(listOf<Op>(Op.Open), a.link.ops)

        a.einrichten()
        val ops = a.link.ops
        assertEquals(Op.Read(svc, feature), ops[1])
        assertEquals(Op.Read(svc, range), ops[2])
        assertEquals(Op.Subscribe(svc, bike, false), ops[3])
        assertEquals(Op.Subscribe(svc, status, false), ops[4])
        assertEquals(Op.Subscribe(svc, cp, true), ops[5])
        val w = ops[6] as Op.Write
        assertEquals("00", w.hex)
        assertTrue(w.withResponse)
        assertEquals(svc, w.service)
        assertEquals(7, ops.size)
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())

        a.antworte(0x00)
        assertEquals<List<Any>>(
            listOf(
                Aufzeichner.Verbindung(TrainerConnection.CONNECTING, TrainerCapabilities.NONE),
                Aufzeichner.Verbindung(TrainerConnection.CONNECTED, capsKickr),
                Aufzeichner.Verbindung(TrainerConnection.READY, capsKickr),
            ),
            a.l.ereignisse.toList<Any>(),
        )
    }

    @Test
    fun `Einrichtung wartet auf das Ergebnis jeder Operation`() {
        val a = neu()
        a.trainer.start(a.l)
        a.link.linkState(BleLinkState.READY, setOf(svc))
        assertEquals(2, a.link.ops.size)
        // Fremde Ergebnisse treiben die Einrichtung nicht weiter.
        a.link.result(BleOperation.SUBSCRIBE, bike)
        a.link.result(BleOperation.READ, range)
        assertEquals(2, a.link.ops.size)
        a.link.result(BleOperation.READ, feature)
        assertEquals(Op.Read(svc, range), a.link.ops.last())
    }

    @Test
    fun `Name aus dem Rohr, sonst Ersatzname`() {
        assertEquals("KICKR CORE 1A2B", neu().trainer.name)
        assertEquals("FTMS-Trainer", neu(null).trainer.name)
    }

    @Test
    fun `zweites start wird ignoriert`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.start(Aufzeichner())
        assertEquals(1, a.link.ops.count { it == Op.Open })
        assertEquals(1, a.l.zustaende().size)
    }

    @Test
    fun `Lesefehler beim Feature - konservativ NONE, Befehle trotzdem`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.link.linkState(BleLinkState.READY, setOf(svc))
        a.link.result(BleOperation.READ, feature, ok = false)
        a.link.result(BleOperation.READ, range, ok = false)
        a.link.result(BleOperation.SUBSCRIBE, bike)
        a.link.result(BleOperation.SUBSCRIBE, status, ok = false)
        a.link.result(BleOperation.SUBSCRIBE, cp)
        a.antworte(0x00)
        assertEquals(
            Aufzeichner.Verbindung(TrainerConnection.READY, TrainerCapabilities.NONE),
            a.l.ereignisse.last(),
        )
        a.trainer.send(TrainerCommand.SetTargetPower(200))
        assertEquals("05 C8 00", a.link.writeHex(cp).last())
    }

    @Test
    fun `fehlender FTMS-Dienst ergibt UNSUPPORTED und Befehle scheitern`() {
        val a = neu()
        a.trainer.send(sim1)
        a.trainer.start(a.l)
        a.link.linkState(BleLinkState.READY, setOf("0000180d-0000-1000-8000-00805f9b34fb"))
        assertEquals(TrainerConnection.UNSUPPORTED, a.l.letzterZustand())
        assertEquals(listOf<Op>(Op.Open), a.link.ops)
        assertEquals(listOf(sim1), a.l.fehler().map { it.command })
        a.trainer.send(TrainerCommand.Start)
        assertEquals(TrainerCommand.Start, a.l.fehler().last().command)
        assertEquals("Trainer ohne FTMS-Dienst", a.l.fehler().last().reason)
    }

    @Test
    fun `Dienst-UUID in Grossbuchstaben wird erkannt`() {
        val a = neu()
        a.trainer.start(a.l)
        a.link.linkState(BleLinkState.READY, setOf(svc.uppercase()))
        assertEquals(Op.Read(svc, feature), a.link.ops.last())
    }

    @Test
    fun `Control-Point-Abo scheitert - CONNECTED mit Messwerten, Befehle scheitern`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.send(TrainerCommand.Start)
        a.einrichten(ok = false)
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        assertTrue(a.link.writes().isEmpty())
        assertEquals(listOf(TrainerCommand.Start), a.l.fehler().map { it.command })
        a.trainer.send(sim1)
        assertEquals(sim1, a.l.fehler().last().command)
        a.link.value(bike, 0x44, 0x00, 0x18, 0x0B, 0x9C, 0x00, 0xFA, 0x00, receivedMs = 77)
        assertEquals(TrainerTelemetry(250, 78.0, 28.4, 77), a.l.telemetrie().single())
    }

    // ================================================================== Telemetrie

    @Test
    fun `Indoor Bike Data wird weitergereicht, leere Teilpakete nicht`() {
        val a = neu()
        a.bisReady()
        a.link.value(bike, 0x44, 0x00, 0x18, 0x0B, 0x9C, 0x00, 0xFA, 0x00, receivedMs = 1000)
        a.link.value(bike, 0x11, 0x00, 0x01, 0x02, 0x03, receivedMs = 1001) // nur Distanz
        a.link.value(bike, 0x05, 0x00, 0xB4, 0x00, receivedMs = 1002) // nur Kadenz
        a.link.value(bike, 0x44, receivedMs = 1003) // kaputt
        assertEquals(
            listOf(TrainerTelemetry(250, 78.0, 28.4, 1000), TrainerTelemetry(null, 90.0, null, 1002)),
            a.l.telemetrie(),
        )
    }

    // ================================================================== Befehle

    @Test
    fun `vor READY nur gemerkt - je Platz der neueste, Zustand vor Modus`() {
        val a = neu()
        a.trainer.send(sim1)
        a.trainer.send(TrainerCommand.Pause)
        a.trainer.send(sim2)
        a.trainer.send(TrainerCommand.Start)
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.einrichten()
        assertEquals(listOf("00"), a.link.writeHex())
        a.antworte(0x00)
        assertEquals(listOf("00", "07"), a.link.writeHex())
        a.antworte(0x07)
        assertEquals(listOf("00", "07", sim2Hex), a.link.writeHex())
        a.antworte(0x11)
        assertEquals(3, a.link.writes().size)
        assertTrue(a.l.fehler().isEmpty())
    }

    @Test
    fun `hoechstens ein Befehl unterwegs`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.SetTargetPower(250))
        a.trainer.send(TrainerCommand.Start)
        a.trainer.send(TrainerCommand.SetTargetPower(260))
        assertEquals(listOf("00", "05 FA 00"), a.link.writeHex())
        a.antworte(0x05)
        assertEquals("07", a.link.writeHex().last())
        a.antworte(0x07)
        assertEquals("05 04 01", a.link.writeHex().last())
        a.antworte(0x05)
        assertEquals(4, a.link.writes().size)
    }

    @Test
    fun `Antworten mit falschem Opcode werden ignoriert`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Start)
        a.trainer.send(sim1)
        a.link.value(cp, 0x80, 0x11, 0x01) // falscher Opcode
        a.link.value(cp, 0x80, 0x00, 0x01) // verspaetete Request-Control-Antwort
        a.link.value(cp, 0x07, 0x01) // keine Antwort
        assertEquals("07", a.link.writeHex().last())
        a.link.value(cp, 0x80, 0x07, 0x01)
        assertEquals("11", a.link.writeHex().last().take(2))
    }

    @Test
    fun `Frist 3 s, zwei Wiederholungen, dann onCommandFailed`() {
        val a = neu()
        a.bisReady()
        a.trainer.tick(1000)
        a.trainer.send(TrainerCommand.Start)
        a.trainer.tick(3999)
        assertEquals(1, a.link.writeHex().count { it == "07" })
        a.trainer.tick(4000)
        assertEquals(2, a.link.writeHex().count { it == "07" })
        a.trainer.tick(6999)
        assertEquals(2, a.link.writeHex().count { it == "07" })
        a.trainer.tick(7000)
        assertEquals(3, a.link.writeHex().count { it == "07" })
        assertTrue(a.l.fehler().isEmpty())
        a.trainer.tick(10000)
        assertEquals(3, a.link.writeHex().count { it == "07" })
        assertEquals(listOf(TrainerCommand.Start), a.l.fehler().map { it.command })
        // Danach geht der naechste Befehl normal raus.
        a.trainer.send(sim1)
        assertEquals("11", a.link.writeHex().last().take(2))
    }

    @Test
    fun `Frist beginnt vor dem ersten tick erst mit dem ersten tick`() {
        val a = neu()
        a.trainer.start(a.l)
        a.einrichten()
        a.antworte(0x00)
        a.trainer.send(TrainerCommand.Start)
        a.trainer.tick(50_000)
        assertEquals(1, a.link.writeHex().count { it == "07" })
        a.trainer.tick(52_999)
        assertEquals(1, a.link.writeHex().count { it == "07" })
        a.trainer.tick(53_000)
        assertEquals(2, a.link.writeHex().count { it == "07" })
    }

    @Test
    fun `Schreibfehler zaehlt als Fehlversuch`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Pause)
        a.link.result(BleOperation.WRITE, cp, ok = false)
        a.link.result(BleOperation.WRITE, cp, ok = false)
        assertEquals(3, a.link.writeHex().count { it == "08 02" })
        a.link.result(BleOperation.WRITE, cp, ok = false)
        assertEquals(3, a.link.writeHex().count { it == "08 02" })
        assertEquals(TrainerCommand.Pause, a.l.fehler().single().command)
    }

    @Test
    fun `verspaetetes Schreib-Ok nach der Antwort stoert nicht`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Start)
        a.trainer.send(sim1)
        a.link.value(cp, 0x80, 0x07, 0x01) // Indication vor dem Write-Ergebnis
        a.link.result(BleOperation.WRITE, cp, ok = true) // gehoert zu 07
        a.link.result(BleOperation.WRITE, cp, ok = false) // gehoert zu 11 → Wiederholung
        assertEquals(2, a.link.writeHex().count { it.startsWith("11") })
    }

    @Test
    fun `neuerer Befehl ersetzt einen scheiternden still`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(sim1)
        a.trainer.send(sim2)
        a.trainer.tick(3000)
        assertEquals(sim2Hex, a.link.writeHex().last())
        assertEquals(2, a.link.writeHex().count { it.startsWith("11") })
        assertTrue(a.l.fehler().isEmpty())
    }

    @Test
    fun `Ergebnis 02, 03, 04 scheitert sofort ohne Wiederholung`() {
        for (code in listOf(0x02, 0x03, 0x04)) {
            val a = neu()
            a.bisReady()
            a.trainer.send(TrainerCommand.SetTargetPower(300))
            a.antworte(0x05, code)
            a.trainer.tick(10_000)
            assertEquals(1, a.link.writeHex().count { it.startsWith("05") }, "Code $code")
            val f = a.l.fehler().single()
            assertEquals(TrainerCommand.SetTargetPower(300), f.command)
            assertTrue(f.reason.contains("0x0$code"), f.reason)
        }
    }

    @Test
    fun `abgelehnter Modus wird nach Neuverbinden nicht nachgeschickt`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.SetTargetPower(300))
        a.antworte(0x05, 0x02)
        a.link.linkState(BleLinkState.RECONNECTING)
        a.link.clearOps()
        a.einrichten()
        a.antworte(0x00)
        assertEquals(listOf("00"), a.link.writeHex())
    }

    @Test
    fun `Control Not Permitted - einmal Kontrolle neu, dann Befehl erneut`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(sim1)
        a.antworte(0x11, 0x05)
        assertEquals("00", a.link.writeHex().last())
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        a.antworte(0x00)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
        assertEquals("11", a.link.writeHex().last().take(2))
        a.antworte(0x11)
        assertTrue(a.l.fehler().isEmpty())
    }

    @Test
    fun `zweites Control Not Permitted fuer denselben Befehl scheitert`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(sim1)
        a.antworte(0x11, 0x05)
        a.antworte(0x00)
        a.antworte(0x11, 0x05)
        assertEquals(sim1, a.l.fehler().single().command)
        assertEquals(1, a.link.writeHex().drop(1).count { it == "00" })
    }

    @Test
    fun `Stop besteht aus Stopp und Reset und gibt die Kontrolle ab`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Stop)
        assertEquals("08 01", a.link.writeHex().last())
        a.antworte(0x01) // falsch: erst muss 08 beantwortet werden
        assertEquals("08 01", a.link.writeHex().last())
        a.antworte(0x08)
        assertEquals("01", a.link.writeHex().last())
        a.antworte(0x01)
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        val n = a.link.writes().size
        a.trainer.tick(60_000)
        assertEquals(n, a.link.writes().size)

        // Kommt danach noch ein Befehl: erst Kontrolle, dann der Befehl.
        a.trainer.send(TrainerCommand.Start)
        assertEquals("00", a.link.writeHex().last())
        a.antworte(0x00)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
        assertEquals("07", a.link.writeHex().last())
    }

    // ================================================================== Kontrolle

    @Test
    fun `Request Control erst abgelehnt, nach 1 s erteilt`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.einrichten()
        a.antworte(0x00, 0x05)
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        a.trainer.tick(999)
        assertEquals(1, a.link.writes().size)
        a.trainer.tick(1000)
        assertEquals(listOf("00", "00"), a.link.writeHex())
        a.antworte(0x00)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
    }

    @Test
    fun `Request Control dauerhaft abgelehnt - UNSUPPORTED, Messwerte laufen`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.trainer.send(sim1)
        a.einrichten()
        a.antworte(0x00, 0x04)
        a.trainer.tick(1000)
        a.antworte(0x00, 0x05)
        assertEquals(TrainerConnection.UNSUPPORTED, a.l.letzterZustand())
        assertEquals(listOf(sim1), a.l.fehler().map { it.command })
        a.trainer.tick(10_000)
        assertEquals(2, a.link.writes().size)
        a.trainer.send(TrainerCommand.Start)
        assertEquals("Kontrolle dauerhaft verweigert", a.l.fehler().last().reason)
        a.link.value(bike, 0x44, 0x00, 0x18, 0x0B, 0x9C, 0x00, 0xFA, 0x00)
        assertEquals(1, a.l.telemetrie().size)
    }

    @Test
    fun `Request Control nicht unterstuetzt - sofort UNSUPPORTED`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.einrichten()
        a.antworte(0x00, 0x02)
        assertEquals(TrainerConnection.UNSUPPORTED, a.l.letzterZustand())
        a.trainer.tick(5000)
        assertEquals(1, a.link.writes().size)
    }

    @Test
    fun `Request Control ohne Antwort - nach Frist und 1 s neu`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.einrichten()
        a.link.result(BleOperation.WRITE, cp)
        a.trainer.tick(2999)
        a.trainer.tick(3000) // Frist
        a.trainer.tick(3999)
        assertEquals(1, a.link.writes().size)
        a.trainer.tick(4000)
        assertEquals(2, a.link.writes().size)
        a.trainer.tick(7000) // zweite Frist → UNSUPPORTED
        assertEquals(TrainerConnection.UNSUPPORTED, a.l.letzterZustand())
    }

    @Test
    fun `Schreibfehler bei Request Control zaehlt als Fehlschlag`() {
        val a = neu()
        a.trainer.start(a.l)
        a.trainer.tick(0)
        a.einrichten()
        a.link.result(BleOperation.WRITE, cp, ok = false)
        a.trainer.tick(1000)
        assertEquals(2, a.link.writes().size)
        a.antworte(0x00)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
    }

    @Test
    fun `Control Permission Lost - CONNECTED, neu anfordern, Zustand und Modus nachschicken`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Start)
        a.antworte(0x07)
        a.trainer.send(sim1)
        a.antworte(0x11)
        a.trainer.send(sim2) // unterwegs, als 0xFF kommt
        a.link.value(status, 0xFF)
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        assertEquals("00", a.link.writeHex().last())
        a.link.result(BleOperation.WRITE, cp) // Ergebnis des verworfenen sim2-Writes
        a.antworte(0x00)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
        assertEquals("07", a.link.writeHex().last())
        a.antworte(0x07)
        assertEquals(sim2Hex, a.link.writeHex().last())
        a.antworte(0x11)
        assertTrue(a.l.fehler().isEmpty())
    }

    @Test
    fun `andere Machine-Status-Meldungen aendern nichts`() {
        val a = neu()
        a.bisReady()
        a.link.value(status, 0x08, 0xFA, 0x00)
        a.link.value(status)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
        assertEquals(1, a.link.writes().size)
    }

    // ================================================================== Neuverbinden, Rohrzustaende

    @Test
    fun `Neuverbinden - laufender Befehl verworfen ohne Fehler, danach nachgeschickt`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Start)
        a.antworte(0x07)
        a.trainer.send(TrainerCommand.SetTargetPower(220))
        a.link.linkState(BleLinkState.RECONNECTING)
        assertEquals(TrainerConnection.CONNECTING, a.l.letzterZustand())
        a.trainer.tick(30_000)
        assertTrue(a.l.fehler().isEmpty())
        a.trainer.send(TrainerCommand.SetTargetPower(230))
        a.link.clearOps()
        a.link.linkState(BleLinkState.CONNECTING)
        a.einrichten()
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        assertEquals(listOf("00"), a.link.writeHex())
        a.antworte(0x00)
        assertEquals(listOf("00", "07"), a.link.writeHex())
        a.antworte(0x07)
        assertEquals(listOf("00", "07", "05 E6 00"), a.link.writeHex())
        assertTrue(a.l.fehler().isEmpty())
    }

    @Test
    fun `Stop wird nach Neuverbinden nicht nachgeschickt`() {
        val a = neu()
        a.bisReady()
        a.trainer.send(TrainerCommand.Stop)
        a.link.linkState(BleLinkState.RECONNECTING)
        a.link.clearOps()
        a.einrichten()
        a.antworte(0x00)
        assertEquals(listOf("00"), a.link.writeHex())
    }

    @Test
    fun `Rohrzustaende werden abgebildet`() {
        val a = neu()
        a.trainer.start(a.l)
        a.link.linkState(BleLinkState.NO_PERMISSION)
        a.link.linkState(BleLinkState.BLUETOOTH_OFF)
        a.link.linkState(BleLinkState.RECONNECTING)
        a.link.linkState(BleLinkState.CLOSED)
        assertEquals(
            listOf(
                TrainerConnection.CONNECTING,
                TrainerConnection.NO_PERMISSION,
                TrainerConnection.BLUETOOTH_OFF,
                TrainerConnection.CONNECTING,
                TrainerConnection.OFF,
            ),
            a.l.zustaende(),
        )
    }

    // ================================================================== stop()

    @Test
    fun `stop im READY - Stopp schreiben, schliessen, Listener vergessen, idempotent`() {
        val a = neu()
        a.bisReady()
        a.link.clearOps()
        val vorher = a.l.ereignisse.size
        a.trainer.stop()
        assertEquals(2, a.link.ops.size)
        assertEquals("08 01", (a.link.ops[0] as Op.Write).hex)
        assertEquals(Op.Close, a.link.ops[1])
        a.trainer.stop()
        assertEquals(2, a.link.ops.size)
        // Nach stop: nichts mehr an den Listener, keine Befehle mehr.
        a.link.linkState(BleLinkState.CLOSED)
        a.link.value(bike, 0x44, 0x00, 0x18, 0x0B, 0x9C, 0x00, 0xFA, 0x00)
        a.trainer.send(TrainerCommand.Start)
        a.trainer.tick(100_000)
        assertEquals(2, a.link.ops.size)
        assertEquals(vorher, a.l.ereignisse.size)
    }

    @Test
    fun `stop ohne Kontrolle schreibt nichts, nur schliessen`() {
        val a = neu()
        a.trainer.start(a.l)
        a.einrichten()
        a.link.clearOps()
        a.trainer.stop()
        assertEquals(listOf<Op>(Op.Close), a.link.ops)
    }

    @Test
    fun `stop vor start beruehrt das Rohr nicht`() {
        val a = neu()
        a.trainer.stop()
        a.trainer.start(a.l)
        assertTrue(a.link.ops.isEmpty())
        assertTrue(a.l.ereignisse.isEmpty())
    }

    // ================================================================== Reentranz

    /** Ein Rohr, das jede Operation sofort und synchron aus dem Aufruf heraus beantwortet. */
    private fun FakeBleLink.antwortetSynchron(controlResult: (Int) -> Int = { 0x01 }) {
        onOperation = { op ->
            when (op) {
                is Op.Read -> {
                    if (op.characteristic == feature) value(feature, *featureKickr)
                    if (op.characteristic == range) value(range, *rangeKickr)
                    result(BleOperation.READ, op.characteristic)
                }
                is Op.Subscribe -> result(BleOperation.SUBSCRIBE, op.characteristic)
                is Op.Write -> {
                    result(BleOperation.WRITE, op.characteristic)
                    val opcode = op.value[0].toInt() and 0xFF
                    value(cp, 0x80, opcode, controlResult(opcode))
                }
                is Op.Open -> linkState(BleLinkState.READY, setOf(svc))
                else -> Unit
            }
        }
    }

    @Test
    fun `Reentranz - Rohr antwortet synchron aus jeder Operation`() {
        val a = neu()
        a.link.antwortetSynchron()
        a.trainer.send(TrainerCommand.Start)
        a.trainer.send(sim2)
        a.trainer.start(a.l)
        assertEquals(
            listOf(TrainerConnection.CONNECTING, TrainerConnection.CONNECTED, TrainerConnection.READY),
            a.l.zustaende(),
        )
        assertEquals(listOf("00", "07", sim2Hex), a.link.writeHex())
        a.trainer.send(TrainerCommand.Stop)
        assertEquals(listOf("00", "07", sim2Hex, "08 01", "01"), a.link.writeHex())
        assertEquals(TrainerConnection.CONNECTED, a.l.letzterZustand())
        assertTrue(a.l.fehler().isEmpty())
    }

    @Test
    fun `Reentranz - Listener ruft send aus onConnection`() {
        val a = neu()
        a.link.antwortetSynchron()
        a.l.beiVerbindung = { s ->
            if (s == TrainerConnection.READY) {
                a.trainer.send(TrainerCommand.Start)
                a.trainer.send(TrainerCommand.SetTargetPower(150))
            }
        }
        a.trainer.start(a.l)
        assertEquals(listOf("00", "07", "05 96 00"), a.link.writeHex())
    }

    @Test
    fun `Reentranz - synchrones Control Not Permitted bis zum endgueltigen Fehler`() {
        val a = neu()
        a.link.antwortetSynchron { op -> if (op == 0x11) 0x05 else 0x01 }
        a.trainer.start(a.l)
        a.trainer.send(sim1)
        assertEquals(sim1, a.l.fehler().single().command)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())
    }

    // ================================================================== Nebenlaeufigkeit

    @Test
    fun `zwei Threads - send und Rohr-Ereignisse gleichzeitig, kein Deadlock`() {
        val a = neu()
        a.link.antwortetSynchron()
        a.trainer.start(a.l)
        assertEquals(TrainerConnection.READY, a.l.letzterZustand())

        val fehler = AtomicReference<Throwable?>(null)
        val los = CountDownLatch(1)
        val runden = 2000
        val sender = Thread {
            try {
                los.await()
                for (i in 0 until runden) {
                    a.trainer.send(TrainerCommand.SetTargetPower(100 + i % 200))
                    if (i % 50 == 0) a.trainer.send(TrainerCommand.Start)
                    a.trainer.tick(i.toLong())
                }
                a.trainer.send(TrainerCommand.SetTargetPower(999))
            } catch (t: Throwable) {
                fehler.set(t)
            }
        }
        val rohr = Thread {
            try {
                los.await()
                for (i in 0 until runden) {
                    a.link.value(bike, 0x44, 0x00, 0x18, 0x0B, 0x9C, 0x00, 0xFA, 0x00, receivedMs = i.toLong())
                    a.link.value(cp, 0x80, 0x05, 0x01) // verirrte Antworten
                    if (i % 100 == 0) a.link.value(status, 0x08, 0x00, 0x00)
                }
            } catch (t: Throwable) {
                fehler.set(t)
            }
        }
        sender.start()
        rohr.start()
        los.countDown()
        sender.join(TimeUnit.SECONDS.toMillis(20))
        rohr.join(TimeUnit.SECONDS.toMillis(20))
        assertTrue(!sender.isAlive && !rohr.isAlive, "Deadlock: Threads haengen")
        assertNull(fehler.get())
        assertEquals(runden, a.l.telemetrie().size)
        assertEquals("05 E7 03", a.link.writeHex().last())
        assertTrue(a.l.fehler().isEmpty())
    }
}
