package de.trailscape.app.sensors.indoor

import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.BleLinkListener
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.IndoorUuids
import de.trailscape.core.indoor.TrainerCapabilities
import de.trailscape.core.indoor.TrainerCommand
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.TrainerListener
import de.trailscape.core.indoor.TrainerTelemetry
import de.trailscape.core.indoor.createFtmsTrainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Das Rohr ([GattLinkKern]) zusammen mit dem **echten** FTMS-Trainer aus
 * `:core` (`createFtmsTrainer`). Attrappe ist nur noch das Android-GATT:
 * [SimTrainer] beantwortet Lesen, Abonnieren und Schreiben wie ein
 * FTMS-Trainer, mit Indication `80 <opcode> 01` auf jeden Control-Point-Write.
 *
 * Wie in der App laufen alle Aufrufe ueber einen einzigen „BLE-Thread"
 * ([Schleife]): Die Rohr-Methoden posten dorthin (wie [GattBleLink]), die
 * GATT-Callbacks ebenso.
 */
class GattLinkFtmsZusammenspielTest {

    private val schleife = Schleife()
    private val takt = Takt(schleife)
    private val sim = SimTrainer(schleife)
    private val kern = GattLinkKern(sim, takt)
    private val link = SchleifenLink(kern, schleife)
    private val trainer: IndoorTrainer = createFtmsTrainer(link)
    private val ereignisse = Mitschrift()

    init {
        sim.kern = kern
    }

    /** Laesst [ms] vergehen, in Schritten von 100 ms wie die 10-Hz-Schleife der App. */
    private fun laufe(ms: Long) {
        var rest = ms
        while (rest > 0) {
            takt.weiter(100)
            trainer.tick(takt.jetztMs())
            schleife.abarbeiten()
            rest -= 100
        }
    }

    private fun bisBereit() {
        trainer.start(ereignisse)
        schleife.abarbeiten()
        laufe(1_000)
        assertEquals(TrainerConnection.READY, ereignisse.verbindungen.last())
    }

    @Test
    fun einrichtungBisReadyUndBefehlKommtAn() {
        bisBereit()
        // Feature lesen, Ranges lesen, abonnieren, Request Control — in dieser Reihenfolge durchs Rohr.
        assertEquals(
            listOf(
                "READ ${IndoorUuids.FTMS_FEATURE}",
                "READ ${IndoorUuids.FTMS_SUPPORTED_POWER_RANGE}",
                "SUBSCRIBE ${IndoorUuids.FTMS_INDOOR_BIKE_DATA} notify",
                "SUBSCRIBE ${IndoorUuids.FTMS_MACHINE_STATUS} notify",
                "SUBSCRIBE ${IndoorUuids.FTMS_CONTROL_POINT} indicate",
                "WRITE 00",
            ),
            sim.operationen,
        )
        val faehig = ereignisse.faehigkeiten.last()
        assertTrue(faehig.targetPower && faehig.simulation)

        trainer.send(TrainerCommand.SetTargetPower(200))
        laufe(500)
        assertEquals("WRITE 05c800", sim.operationen.last())
        assertTrue(ereignisse.fehler.isEmpty())
    }

    @Test
    fun readyMeldetDiensteKleinUndDerTrainerErkenntFtms() {
        sim.dienste = setOf(IndoorUuids.FTMS_SERVICE.uppercase(), "00001818-0000-1000-8000-00805F9B34FB")
        bisBereit()
    }

    @Test
    fun fristablaufImRohrBautNeuAufUndDerTrainerRichtetSichNeuEin() {
        bisBereit()
        val vorher = sim.verbindungen
        // Der Stack verschluckt den naechsten Write (kein Callback, keine Indication).
        sim.schreibenStumm = true
        trainer.send(TrainerCommand.SetTargetPower(250))
        laufe(5_200)
        // Rohr: nach 5 s neu aufbauen. Trainer: zwischenzeitlich nicht mehr READY.
        assertTrue(TrainerConnection.CONNECTING in ereignisse.verbindungen.drop(1))
        sim.schreibenStumm = false
        laufe(4_000)
        assertEquals(vorher + 1, sim.verbindungen)
        assertEquals(TrainerConnection.READY, ereignisse.verbindungen.last())
        // Nach dem Neuaufbau: Einrichtung von vorn, Kontrolle neu, die gemerkte Zielleistung wirkt.
        val nachNeuaufbau = sim.operationen.drop(sim.operationen.lastIndexOf("READ ${IndoorUuids.FTMS_FEATURE}"))
        assertEquals("WRITE 00", nachNeuaufbau[5])
        assertEquals("WRITE 05fa00", nachNeuaufbau.last())
        assertTrue("keine endgueltige Absage: ${ereignisse.fehler}", ereignisse.fehler.isEmpty())
    }

    @Test
    fun stoppErreichtDenTrainerBevorDasRohrTrennt() {
        bisBereit()
        trainer.send(TrainerCommand.SetTargetPower(180))
        laufe(500)
        trainer.stop()
        schleife.abarbeiten()
        laufe(500)
        val i = sim.operationen.indexOf("WRITE 0801")
        assertTrue("Stopp geschrieben: ${sim.operationen}", i >= 0)
        assertTrue(sim.schliessen.isNotEmpty())
        // Getrennt wird erst nach dem Stopp-Write.
        assertTrue(sim.schliessen.last() > i)
        laufe(10_000)
        assertEquals(1, sim.verbindungen)
    }

    @Test
    fun abbruchMittenInDerFahrtUndNeuverbindenOhneAbsage() {
        bisBereit()
        trainer.send(TrainerCommand.SetSimulation(0.0, 3.0, 0.004, 0.25))
        laufe(500)
        sim.trenne(8)
        schleife.abarbeiten()
        laufe(3_000)
        assertEquals(TrainerConnection.READY, ereignisse.verbindungen.last())
        assertTrue(sim.operationen.last().startsWith("WRITE 11"))
        assertTrue(ereignisse.fehler.isEmpty())
    }

    // ---------------------------------------------------------------- Attrappen

    /** Der „BLE-Thread": eine Schlange von Aufgaben, nacheinander abgearbeitet. */
    private class Schleife {
        private val aufgaben = ArrayDeque<() -> Unit>()

        fun post(a: () -> Unit) {
            aufgaben.addLast(a)
        }

        fun abarbeiten() {
            var n = 0
            while (true) {
                val a = aufgaben.removeFirstOrNull() ?: return
                a()
                check(++n < 100_000) { "Endlosschleife" }
            }
        }
    }

    private class Takt(private val schleife: Schleife) : LinkTakt {
        private var jetzt = 0L
        private var naechste = 0L
        private val faellig = mutableListOf<Triple<Long, Long, () -> Unit>>()

        override fun jetztMs() = jetzt

        override fun nach(verzoegerungMs: Long, block: () -> Unit): Abbrechbar {
            val e = Triple(jetzt + verzoegerungMs, naechste++, block)
            faellig += e
            return Abbrechbar { faellig.remove(e) }
        }

        fun weiter(ms: Long) {
            val ziel = jetzt + ms
            while (true) {
                val n = faellig.filter { it.first <= ziel }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                faellig.remove(n)
                jetzt = n.first
                n.third()
                schleife.abarbeiten()
            }
            jetzt = ziel
        }
    }

    /** Wie [GattBleLink]: jeder Aufruf wird auf den BLE-Thread gepostet. */
    private class SchleifenLink(private val kern: GattLinkKern, private val schleife: Schleife) : BleLink {
        override val deviceName = "KICKR CORE 5A1F"

        override fun open(listener: BleLinkListener) = schleife.post { kern.open(listener) }

        override fun subscribe(serviceUuid: String, characteristicUuid: String, indicate: Boolean) =
            schleife.post { kern.enqueue(GattOp.Abonnieren(serviceUuid, characteristicUuid.lowercase(), indicate)) }

        override fun read(serviceUuid: String, characteristicUuid: String) =
            schleife.post { kern.enqueue(GattOp.Lesen(serviceUuid, characteristicUuid.lowercase())) }

        override fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
            val kopie = value.copyOf()
            schleife.post { kern.enqueue(GattOp.Schreiben(serviceUuid, characteristicUuid.lowercase(), kopie, withResponse)) }
        }

        override fun requestMtu(mtu: Int) = schleife.post { kern.enqueue(GattOp.Mtu(mtu)) }

        override fun close() = schleife.post { kern.close() }
    }

    /** Das Android-GATT eines FTMS-Trainers, so weit das Rohr es sieht. */
    private class SimTrainer(private val schleife: Schleife) : GattPort {
        lateinit var kern: GattLinkKern
        var dienste = setOf(IndoorUuids.FTMS_SERVICE)
        var schreibenStumm = false
        var verbindungen = 0
        val operationen = mutableListOf<String>()

        /** Index in [operationen], an dem jeweils geschlossen wurde. */
        val schliessen = mutableListOf<Int>()
        private var verbunden = false

        /** Generation der Verbindung: Callbacks einer alten Verbindung verfallen wie in [GattBleLink]. */
        private var generation = 0

        private fun callback(block: () -> Unit) {
            val g = generation
            schleife.post { if (g == generation && verbunden) block() }
        }

        override fun bluetoothEin() = true

        override fun darfVerbinden() = true

        override fun verbinde(): Boolean {
            verbindungen++
            generation++
            verbunden = true
            callback { kern.onVerbindung(0, verbunden = true) }
            return true
        }

        override fun discoverServices(): Boolean {
            callback { kern.onDiensteErkannt(0) }
            return true
        }

        override fun serviceUuids() = dienste

        override fun starte(op: GattOp): Int {
            when (op) {
                is GattOp.Lesen -> {
                    operationen += "READ ${op.characteristicUuid}"
                    val wert = when (op.characteristicUuid) {
                        // Machine Features: Trittfrequenz (Bit 1), Leistung (Bit 14);
                        // Target Setting: Zielleistung (Bit 3), Simulation (Bit 13).
                        IndoorUuids.FTMS_FEATURE -> le32((1 shl 1) or (1 shl 14)) + le32((1 shl 3) or (1 shl 13))
                        IndoorUuids.FTMS_SUPPORTED_POWER_RANGE -> byteArrayOf(0, 0, 0xD0.toByte(), 0x07, 1, 0)
                        else -> ByteArray(0)
                    }
                    callback { kern.onGelesen(op.characteristicUuid, wert, 0) }
                }
                is GattOp.Abonnieren -> {
                    operationen += "SUBSCRIBE ${op.characteristicUuid} " + if (op.indicate) "indicate" else "notify"
                    callback { kern.onDescriptorGeschrieben(op.characteristicUuid, 0) }
                }
                is GattOp.Schreiben -> {
                    operationen += "WRITE " + op.wert.joinToString("") { "%02x".format(it) }
                    if (!schreibenStumm) {
                        val opcode = op.wert[0]
                        callback { kern.onGeschrieben(op.characteristicUuid, 0) }
                        callback {
                            kern.onWert(IndoorUuids.FTMS_CONTROL_POINT, byteArrayOf(0x80.toByte(), opcode, 0x01), 0)
                        }
                    }
                }
                is GattOp.Mtu -> {
                    operationen += "MTU ${op.mtu}"
                    callback { kern.onMtu(0) }
                }
            }
            return GattLinkKern.GATT_SUCCESS
        }

        override fun schliessen() {
            if (!verbunden) return
            verbunden = false
            generation++
            schliessen += operationen.size
        }

        /** Das Geraet trennt von sich aus. */
        fun trenne(status: Int) {
            callback { kern.onVerbindung(status, verbunden = false) }
        }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }

    private class Mitschrift : TrainerListener {
        val verbindungen = mutableListOf<TrainerConnection>()
        val faehigkeiten = mutableListOf<TrainerCapabilities>()
        val fehler = mutableListOf<String>()

        override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
            verbindungen += state
            faehigkeiten += capabilities
        }

        override fun onTelemetry(telemetry: TrainerTelemetry) = Unit

        override fun onCommandFailed(command: TrainerCommand, reason: String) {
            fehler += "$command: $reason"
        }
    }
}
