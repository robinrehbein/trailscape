package de.trailscape.app.sensors.indoor

import de.trailscape.core.indoor.BleLinkListener
import de.trailscape.core.indoor.BleLinkState
import de.trailscape.core.indoor.BleOperation
import de.trailscape.core.indoor.IndoorUuids
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Automat des Rohrs gegen eine Attrappe der Plattform ([FakePort]) und
 * eine Hand-Uhr ([FakeTakt]) — alles, was ohne Funk pruefbar ist: Phasen,
 * Schlange, Fristen, Neuverbinden, Blockaden, Schliessen.
 */
class GattLinkKernTest {

    private val svc = IndoorUuids.FTMS_SERVICE
    private val cp = IndoorUuids.FTMS_CONTROL_POINT
    private val feature = IndoorUuids.FTMS_FEATURE
    private val bikeData = IndoorUuids.FTMS_INDOOR_BIKE_DATA

    private val port = FakePort()
    private val takt = FakeTakt()
    private val log = Protokoll()
    private val kern = GattLinkKern(port, takt)

    /** Oeffnet und bringt das Rohr bis READY. */
    private fun bereit() {
        kern.open(log)
        assertEquals(1, port.verbindeAufrufe)
        kern.onVerbindung(0, verbunden = true)
        assertEquals(1, port.dienstsuchen)
        kern.onDiensteErkannt(0)
    }

    @Test
    fun oeffnenVerbindetUndMeldetReadyMitDiensten() {
        bereit()
        assertEquals(listOf(BleLinkState.CONNECTING, BleLinkState.READY), log.zustaende)
        assertEquals(setOf(svc), log.dienste.last())
        assertEquals(BleLinkState.READY, kern.zustand)
    }

    @Test
    fun zweitesOpenWirdIgnoriert() {
        bereit()
        kern.open(log)
        assertEquals(1, port.verbindeAufrufe)
    }

    @Test
    fun operationenLaufenNacheinanderInAufrufreihenfolge() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.enqueue(GattOp.Abonnieren(svc, cp, indicate = true))
        kern.enqueue(GattOp.Schreiben(svc, cp, byteArrayOf(0x00), mitAntwort = true))
        // Nur die erste ist gestartet.
        assertEquals(listOf("READ $feature"), port.gestartet)

        kern.onGelesen(feature, byteArrayOf(1, 2), 0)
        assertEquals(listOf("READ $feature", "SUBSCRIBE $cp indicate"), port.gestartet)
        assertArrayEquals(byteArrayOf(1, 2), log.werte.single().second)

        kern.onDescriptorGeschrieben(cp, 0)
        kern.onGeschrieben(cp, 0)
        assertEquals(3, port.gestartet.size)
        assertEquals(
            listOf("READ $feature ok", "SUBSCRIBE $cp ok", "WRITE $cp ok"),
            log.ergebnisse,
        )
    }

    @Test
    fun wertDesLesensKommtVorDemErgebnis() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.onGelesen(feature, byteArrayOf(7), 0)
        assertEquals(listOf("value $feature", "result READ"), log.reihenfolge.takeLast(2))
    }

    @Test
    fun operationAusserhalbVonReadyScheitertSofort() {
        kern.open(log)
        kern.enqueue(GattOp.Lesen(svc, feature))
        assertEquals(listOf("READ $feature fail -8"), log.ergebnisse)
        assertTrue(port.gestartet.isEmpty())
    }

    @Test
    fun abgelehnterStartScheitertSofortUndDieNaechsteLaeuft() {
        bereit()
        port.startCode = GattLinkKern.STATUS_DIENST_FEHLT
        kern.enqueue(GattOp.Lesen(svc, feature))
        port.startCode = 0
        kern.enqueue(GattOp.Lesen(svc, bikeData))
        assertEquals(listOf("READ $feature fail -5"), log.ergebnisse)
        assertEquals(2, port.gestartet.size)
    }

    @Test
    fun operationOhneAntwortScheitertNachFuenfSekunden() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.enqueue(GattOp.Lesen(svc, bikeData))
        takt.weiter(4_999)
        assertTrue(log.ergebnisse.isEmpty())
        takt.weiter(1)
        assertEquals(listOf("READ $feature fail -1"), log.ergebnisse)
        // Die naechste laeuft, die Verbindung bleibt.
        assertEquals(2, port.gestartet.size)
        assertEquals(0, port.schliessAufrufe)
        // Eine spaete Antwort der abgelaufenen gehoert nicht zur laufenden.
        kern.onGelesen(feature, byteArrayOf(1), 0)
        assertEquals(1, log.ergebnisse.size)
        kern.onGelesen(bikeData, byteArrayOf(2), 0)
        assertEquals("READ $bikeData ok", log.ergebnisse.last())
    }

    @Test
    fun zweiFristablaeufeInFolgeVerbindenNeu() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.enqueue(GattOp.Lesen(svc, bikeData))
        kern.enqueue(GattOp.Mtu(247))
        takt.weiter(5_000)
        takt.weiter(5_000)
        assertEquals(
            listOf("READ $feature fail -1", "READ $bikeData fail -1", "MTU null fail -1"),
            log.ergebnisse,
        )
        assertEquals(BleLinkState.RECONNECTING, kern.zustand)
        assertTrue(port.schliessAufrufe >= 1)
        takt.weiter(2_000)
        assertEquals(2, port.verbindeAufrufe)
    }

    @Test
    fun abbruchMeldetOffeneOperationenUndVerbindetNachTaktNeu() {
        bereit()
        kern.enqueue(GattOp.Abonnieren(svc, cp, indicate = true))
        kern.enqueue(GattOp.Schreiben(svc, cp, byteArrayOf(0), mitAntwort = true))
        kern.onVerbindung(8, verbunden = false)

        assertEquals(listOf("SUBSCRIBE $cp fail 8", "WRITE $cp fail 8"), log.ergebnisse)
        // Erst die Operationen, dann der Zustand.
        assertEquals("state RECONNECTING", log.reihenfolge.last())
        assertEquals(1, port.schliessAufrufe)

        // Takt: 2 s, dann neu verbinden; die Schlange ist leer.
        takt.weiter(1_999)
        assertEquals(1, port.verbindeAufrufe)
        takt.weiter(1)
        assertEquals(2, port.verbindeAufrufe)
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(0)
        assertEquals(BleLinkState.READY, kern.zustand)
        assertEquals(1, port.gestartet.size) // nichts wiederhergestellt, nichts nachgeholt
    }

    @Test
    fun status133BeimVerbindenSchliesstUndVersuchtImTaktNeu() {
        kern.open(log)
        kern.onVerbindung(133, verbunden = false)
        assertEquals(1, port.schliessAufrufe)
        // Vor der ersten Verbindung bleibt der Zustand CONNECTING.
        assertEquals(listOf(BleLinkState.CONNECTING), log.zustaende)
        takt.weiter(2_000)
        assertEquals(2, port.verbindeAufrufe)
        kern.onVerbindung(133, verbunden = false)
        takt.weiter(4_999)
        assertEquals(2, port.verbindeAufrufe)
        takt.weiter(1)
        assertEquals(3, port.verbindeAufrufe)
        kern.onVerbindung(133, verbunden = false)
        takt.weiter(10_000)
        assertEquals(4, port.verbindeAufrufe)
        kern.onVerbindung(133, verbunden = false)
        takt.weiter(29_999)
        assertEquals(4, port.verbindeAufrufe)
        takt.weiter(1)
        assertEquals(5, port.verbindeAufrufe)
    }

    @Test
    fun geraetDasSofortWiederAbbrichtHaemmertNicht() {
        // READY ohne erfolgreiche Operation zaehlt nicht als gesund.
        bereit()
        kern.onVerbindung(19, verbunden = false)
        takt.weiter(2_000)
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(0)
        kern.onVerbindung(19, verbunden = false)
        takt.weiter(4_999)
        assertEquals(2, port.verbindeAufrufe)
        takt.weiter(1)
        assertEquals(3, port.verbindeAufrufe)
    }

    @Test
    fun ersteErfolgreicheOperationSetztDenTaktZurueck() {
        bereit()
        kern.onVerbindung(19, verbunden = false)
        takt.weiter(2_000)
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(0)
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.onGelesen(feature, byteArrayOf(1), 0)
        kern.onVerbindung(19, verbunden = false)
        takt.weiter(2_000)
        assertEquals(3, port.verbindeAufrufe)
    }

    @Test
    fun verbindungsaufbauOhneAntwortScheitertNachZwanzigSekunden() {
        kern.open(log)
        takt.weiter(19_999)
        assertEquals(0, port.schliessAufrufe)
        takt.weiter(1)
        assertEquals(1, port.schliessAufrufe)
        takt.weiter(2_000)
        assertEquals(2, port.verbindeAufrufe)
    }

    @Test
    fun diensterkennungOhneAntwortScheitertInDerselbenFrist() {
        kern.open(log)
        takt.weiter(10_000)
        kern.onVerbindung(0, verbunden = true)
        takt.weiter(10_000)
        assertEquals(1, port.schliessAufrufe)
    }

    @Test
    fun bereitHebtDieVerbindungsfristAuf() {
        bereit()
        takt.weiter(60_000)
        assertEquals(0, port.schliessAufrufe)
        assertEquals(BleLinkState.READY, kern.zustand)
    }

    @Test
    fun fehlgeschlageneDiensterkennungIstEinAbbruch() {
        kern.open(log)
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(129)
        assertEquals(1, port.schliessAufrufe)
        port.dienstsucheGeht = false
        takt.weiter(2_000)
        kern.onVerbindung(0, verbunden = true)
        assertEquals(2, port.schliessAufrufe)
    }

    @Test
    fun connectGattOhneErgebnisWirdNeuVersucht() {
        port.verbindeGeht = false
        kern.open(log)
        assertEquals(1, port.schliessAufrufe)
        port.verbindeGeht = true
        takt.weiter(2_000)
        assertEquals(2, port.verbindeAufrufe)
    }

    @Test
    fun bluetoothAusBlockiertUndWirdAlleZweiSekundenGeprueft() {
        port.bluetooth = false
        kern.open(log)
        assertEquals(listOf(BleLinkState.CONNECTING, BleLinkState.BLUETOOTH_OFF), log.zustaende)
        assertEquals(0, port.verbindeAufrufe)
        takt.weiter(10_000)
        assertEquals(0, port.verbindeAufrufe)
        port.bluetooth = true
        takt.weiter(2_000)
        assertEquals(1, port.verbindeAufrufe)
        assertEquals(BleLinkState.CONNECTING, log.zustaende.last())
    }

    @Test
    fun fehlendeBerechtigungBlockiertBisSieErteiltIst() {
        port.berechtigung = false
        kern.open(log)
        assertEquals(BleLinkState.NO_PERMISSION, kern.zustand)
        kern.enqueue(GattOp.Lesen(svc, feature))
        assertEquals(listOf("READ $feature fail -8"), log.ergebnisse)
        port.berechtigung = true
        takt.weiter(2_000)
        assertEquals(1, port.verbindeAufrufe)
    }

    @Test
    fun blockadeKannIhrenGrundWechseln() {
        port.bluetooth = false
        port.berechtigung = false
        kern.open(log)
        assertEquals(BleLinkState.BLUETOOTH_OFF, kern.zustand)
        port.bluetooth = true
        takt.weiter(2_000)
        assertEquals(BleLinkState.NO_PERMISSION, kern.zustand)
        port.berechtigung = true
        takt.weiter(2_000)
        assertEquals(1, port.verbindeAufrufe)
    }

    @Test
    fun bluetoothAusWaehrendDerFahrtWirdBeimNeuversuchErkannt() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        port.bluetooth = false
        kern.onVerbindung(8, verbunden = false)
        takt.weiter(2_000)
        assertEquals(BleLinkState.BLUETOOTH_OFF, kern.zustand)
        assertEquals(1, port.verbindeAufrufe)
        port.bluetooth = true
        takt.weiter(2_000)
        // Nach einer Verbindung heisst der neue Anlauf RECONNECTING.
        assertEquals(BleLinkState.RECONNECTING, kern.zustand)
        assertEquals(2, port.verbindeAufrufe)
    }

    @Test
    fun securityExceptionMittenImBetriebWirdZurBlockade() {
        bereit()
        port.wirftBeimStart = true
        kern.enqueue(GattOp.Lesen(svc, feature))
        assertEquals(BleLinkState.NO_PERMISSION, kern.zustand)
        assertEquals(listOf("READ $feature fail -9"), log.ergebnisse)
        assertEquals(1, port.schliessAufrufe)
    }

    @Test
    fun securityExceptionBeimVerbindenWirdZurBlockade() {
        port.wirftBeimVerbinden = true
        kern.open(log)
        assertEquals(BleLinkState.NO_PERMISSION, kern.zustand)
        assertTrue(port.schliessAufrufe >= 1)
        // Keine Frist bleibt haengen.
        takt.weiter(60_000)
        assertEquals(BleLinkState.NO_PERMISSION, kern.zustand)
    }

    @Test
    fun closeGibtFreiMeldetNurNochClosedUndStopptAlleFristen() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.enqueue(GattOp.Lesen(svc, bikeData))
        kern.close()
        assertEquals(BleLinkState.CLOSED, log.zustaende.last())
        assertTrue(log.ergebnisse.isEmpty())
        assertEquals(1, port.schliessAufrufe)
        val vorher = log.reihenfolge.size
        // Spaete Callbacks, Fristen, neue Operationen: nichts mehr.
        kern.onGelesen(feature, byteArrayOf(1), 0)
        kern.onWert(bikeData, byteArrayOf(1), 1)
        kern.onVerbindung(8, verbunden = false)
        kern.enqueue(GattOp.Lesen(svc, feature))
        takt.weiter(120_000)
        assertEquals(vorher, log.reihenfolge.size)
        assertEquals(1, port.verbindeAufrufe)
        kern.close()
        assertEquals(vorher, log.reihenfolge.size)
    }

    @Test
    fun closeWaehrendDesWartensVerhindertDenNeuversuch() {
        bereit()
        kern.onVerbindung(8, verbunden = false)
        kern.close()
        takt.weiter(60_000)
        assertEquals(1, port.verbindeAufrufe)
    }

    @Test
    fun closeVorOpenMeldetNichtsUndOpenDanachBleibtWirkungslos() {
        kern.close()
        kern.open(log)
        assertTrue(log.reihenfolge.isEmpty())
        assertEquals(0, port.verbindeAufrufe)
    }

    @Test
    fun notificationsKommenNurInReady() {
        kern.open(log)
        kern.onWert(bikeData, byteArrayOf(9), 5)
        assertTrue(log.werte.isEmpty())
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(0)
        kern.onWert(bikeData.uppercase(), byteArrayOf(9), 5)
        assertEquals(bikeData to 5L, log.werte.single().first to log.zeiten.single())
    }

    @Test
    fun ungefragteMtuAenderungHatKeineFolgen() {
        bereit()
        kern.enqueue(GattOp.Lesen(svc, feature))
        kern.onMtu(0)
        assertTrue(log.ergebnisse.isEmpty())
        kern.onGelesen(feature, byteArrayOf(), 0)
        kern.enqueue(GattOp.Mtu(247))
        kern.onMtu(0)
        assertEquals("MTU null ok", log.ergebnisse.last())
    }

    @Test
    fun fehlerstatusEinerAntwortIstEinScheitern() {
        bereit()
        kern.enqueue(GattOp.Schreiben(svc, cp, byteArrayOf(0x05), mitAntwort = true))
        kern.onGeschrieben(cp, 3)
        assertEquals(listOf("WRITE $cp fail 3"), log.ergebnisse)
        assertEquals(BleLinkState.READY, kern.zustand)
    }

    @Test
    fun antwortFuerAndereCharacteristicWirdNichtZugeordnet() {
        bereit()
        kern.enqueue(GattOp.Abonnieren(svc, cp, indicate = true))
        kern.onDescriptorGeschrieben(bikeData, 0)
        assertTrue(log.ergebnisse.isEmpty())
        kern.onDescriptorGeschrieben(cp.uppercase(), 0)
        assertEquals(listOf("SUBSCRIBE $cp ok"), log.ergebnisse)
    }

    @Test
    fun readyKommtNachJedemNeuverbindenErneut() {
        bereit()
        kern.onVerbindung(8, verbunden = false)
        takt.weiter(2_000)
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(0)
        assertEquals(
            listOf(BleLinkState.CONNECTING, BleLinkState.READY, BleLinkState.RECONNECTING, BleLinkState.READY),
            log.zustaende,
        )
    }

    @Test
    fun einProtokollDasImListenerZurueckruftBringtDieSchlangeNichtDurcheinander() {
        // Attrappe einer Protokollschicht wie FTMS: liest nach READY, abonniert
        // danach und schreibt „Request Control" — jeweils aus dem Ergebnis
        // der vorigen Operation heraus (in der App ueber den BLE-Thread gepostet).
        val schritte = ArrayDeque<GattOp>()
        val protokoll = object : BleLinkListener by log {
            override fun onLinkState(state: BleLinkState, serviceUuids: Set<String>) {
                log.onLinkState(state, serviceUuids)
                if (state == BleLinkState.READY) {
                    schritte += GattOp.Lesen(svc, feature)
                }
            }

            override fun onOperationResult(
                operation: BleOperation,
                characteristicUuid: String?,
                ok: Boolean,
                gattStatus: Int,
            ) {
                log.onOperationResult(operation, characteristicUuid, ok, gattStatus)
                when (operation) {
                    BleOperation.READ -> schritte += GattOp.Abonnieren(svc, cp, indicate = true)
                    BleOperation.SUBSCRIBE -> schritte += GattOp.Schreiben(svc, cp, byteArrayOf(0x00), true)
                    else -> Unit
                }
            }
        }
        kern.open(protokoll)
        kern.onVerbindung(0, verbunden = true)
        kern.onDiensteErkannt(0)
        fun abarbeiten() {
            while (schritte.isNotEmpty()) kern.enqueue(schritte.removeFirst())
        }
        abarbeiten()
        kern.onGelesen(feature, byteArrayOf(0x02, 0x40, 0, 0), 0)
        abarbeiten()
        kern.onDescriptorGeschrieben(cp, 0)
        abarbeiten()
        // Indication der Antwort vor dem Write-Callback — so kommt es auf manchen Stacks.
        kern.onWert(cp, byteArrayOf(0x80.toByte(), 0x00, 0x01), 10)
        kern.onGeschrieben(cp, 0)
        assertEquals(listOf("READ $feature ok", "SUBSCRIBE $cp ok", "WRITE $cp ok"), log.ergebnisse)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x00, 0x01), log.werte.last().second)
        assertFalse(port.gestartet.isEmpty())
    }

    // ---------------------------------------------------------------- Attrappen

    private class FakePort : GattPort {
        var bluetooth = true
        var berechtigung = true
        var verbindeGeht = true
        var dienstsucheGeht = true
        var startCode = 0
        var wirftBeimStart = false
        var wirftBeimVerbinden = false
        var verbindeAufrufe = 0
        var dienstsuchen = 0
        var schliessAufrufe = 0
        val gestartet = mutableListOf<String>()

        override fun bluetoothEin() = bluetooth

        override fun darfVerbinden() = berechtigung

        override fun verbinde(): Boolean {
            verbindeAufrufe++
            if (wirftBeimVerbinden) throw SecurityException("test")
            return verbindeGeht
        }

        override fun discoverServices(): Boolean {
            dienstsuchen++
            return dienstsucheGeht
        }

        override fun serviceUuids() = setOf(IndoorUuids.FTMS_SERVICE)

        override fun starte(op: GattOp): Int {
            if (wirftBeimStart) throw SecurityException("test")
            gestartet += when (op) {
                is GattOp.Abonnieren -> "SUBSCRIBE ${op.characteristicUuid}" + if (op.indicate) " indicate" else ""
                is GattOp.Lesen -> "READ ${op.characteristicUuid}"
                is GattOp.Schreiben -> "WRITE ${op.characteristicUuid}"
                is GattOp.Mtu -> "MTU ${op.mtu}"
            }
            return startCode
        }

        override fun schliessen() {
            schliessAufrufe++
        }
    }

    private class FakeTakt : LinkTakt {
        private var jetzt = 0L
        private var naechsteId = 0L
        private val faellig = mutableListOf<Triple<Long, Long, () -> Unit>>() // Zeit, Reihenfolge, Block

        override fun jetztMs() = jetzt

        override fun nach(verzoegerungMs: Long, block: () -> Unit): Abbrechbar {
            val eintrag = Triple(jetzt + verzoegerungMs, naechsteId++, block)
            faellig += eintrag
            return Abbrechbar { faellig.remove(eintrag) }
        }

        fun weiter(ms: Long) {
            val ziel = jetzt + ms
            while (true) {
                val n = faellig.filter { it.first <= ziel }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                faellig.remove(n)
                jetzt = n.first
                n.third()
            }
            jetzt = ziel
        }
    }

    private class Protokoll : BleLinkListener {
        val zustaende = mutableListOf<BleLinkState>()
        val dienste = mutableListOf<Set<String>>()
        val werte = mutableListOf<Pair<String, ByteArray>>()
        val zeiten = mutableListOf<Long>()
        val ergebnisse = mutableListOf<String>()
        val reihenfolge = mutableListOf<String>()

        override fun onLinkState(state: BleLinkState, serviceUuids: Set<String>) {
            zustaende += state
            dienste += serviceUuids
            reihenfolge += "state $state"
        }

        override fun onValue(characteristicUuid: String, value: ByteArray, receivedMs: Long) {
            werte += characteristicUuid to value
            zeiten += receivedMs
            reihenfolge += "value $characteristicUuid"
        }

        override fun onOperationResult(operation: BleOperation, characteristicUuid: String?, ok: Boolean, gattStatus: Int) {
            ergebnisse += "$operation $characteristicUuid " + if (ok) "ok" else "fail $gattStatus"
            reihenfolge += "result $operation"
        }
    }
}
