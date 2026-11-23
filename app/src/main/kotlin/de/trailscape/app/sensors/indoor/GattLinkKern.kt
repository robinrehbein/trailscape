package de.trailscape.app.sensors.indoor

import de.trailscape.core.indoor.BleLinkListener
import de.trailscape.core.indoor.BleLinkState
import de.trailscape.core.indoor.BleOperation
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.neuerVersuchNachMs

/**
 * # Der Automat hinter dem Rohr — ohne Android
 *
 * [GattLinkKern] ist die ganze Zustandslogik von [GattBleLink]: Verbinden,
 * Diensterkennung, die Warteschlange der GATT-Operationen mit ihren Fristen,
 * Neuverbinden nach Abbruch und die Blockaden (Berechtigung, Bluetooth aus).
 * Er spricht mit der Plattform nur ueber [GattPort] und mit der Zeit nur ueber
 * [LinkTakt] — so laeuft er im JVM-Test gegen Attrappen
 * (`GattLinkKernTest`), und auf dem Geraet bleibt fuer [GattBleLink] nur das
 * Uebersetzen der Android-Aufrufe.
 *
 * ## Ein Thread
 * Jede Methode wird auf demselben Thread gerufen (dem BLE-Thread
 * „trailscape-ble"), und von dort ruft der Kern den [BleLinkListener]. Der
 * Kern selbst synchronisiert nichts: Die Huelle postet jeden Aufruf von
 * aussen und jeden GATT-Callback auf diesen Thread. Ruft der Listener
 * waehrend eines Ereignisses zurueck ins Rohr, kommt der Aufruf deshalb erst
 * nach dem laufenden Ereignis an — keine Rekursion, keine halben Zustaende.
 *
 * ## Ablauf
 * ```
 * open ─► pruefen ─┬─ Bluetooth aus ─────► BLUETOOTH_OFF ─┐ alle 2 s
 *                  ├─ keine Berechtigung ► NO_PERMISSION ─┤ neu pruefen
 *                  └─ connect ─► CONNECTING ─► discover ─► READY
 *                        ▲                                  │ Abbruch, 133,
 *                        └── neuerVersuchNachMs ◄─ RECONNECTING ◄┘ Frist, 2 Timeouts
 * ```
 *  * Verbindungsaufbau und Diensterkennung zusammen haben [VERBINDUNGS_FRIST_MS].
 *  * Jede Operation hat [OPERATIONS_FRIST_MS]. Laeuft die Frist ab, gilt der
 *    Stack als haengend: Android haelt intern „beschaeftigt", bis der
 *    Callback doch noch kommt, jede weitere Operation scheiterte sofort, und
 *    ein spaeter Callback koennte der naechsten gleichartigen Operation
 *    zugeordnet werden. Deshalb trennen und neu verbinden — die neue
 *    GATT-Instanz verwirft alles Spaete sicher.
 *  * [SOFORT_ABLEHNUNGEN_BIS_NEUVERBINDEN] synchrone Ablehnungen in Folge
 *    (Stack beschaeftigt, Adapter weg) zaehlen ebenso als Abbruch.
 *  * In jeder aktiven Phase prueft der Kern alle [WACHE_MS], ob Bluetooth
 *    noch an ist — nicht jedes Geraet meldet beim Ausschalten einen
 *    Verbindungsabbruch.
 *  * Der Takt der Fehlversuche faellt erst auf null, wenn eine Verbindung
 *    [GESUND_NACH_MS] lang gehalten hat. Ein Geraet, das nach READY (oder
 *    nach dem ersten Handschlag) jedesmal abbricht, wird so nicht im
 *    2-s-Takt behaemmert.
 *  * Bei jedem Abbruch werden die offenen Operationen gescheitert gemeldet,
 *    die Schlange ist danach leer, und Abonnements stellt der Kern nicht
 *    wieder her (Vertrag in `BleLink`).
 *  * Status 133 (`GATT_ERROR`) behandelt er wie jeden Abbruch: GATT schliessen
 *    (nicht nur trennen — sonst bleibt der Client-Slot belegt) und nach Takt
 *    neu versuchen.
 */
internal class GattLinkKern(
    private val port: GattPort,
    private val takt: LinkTakt,
) {
    private enum class Phase { NEU, VERBINDE, ENTDECKE, BEREIT, WARTET, KEINE_BERECHTIGUNG, BLUETOOTH_AUS, SCHLIESST, ZU }

    private var phase = Phase.NEU
    private var listener: BleLinkListener? = null

    /** Zuletzt gemeldeter Zustand — gemeldet wird nur bei Aenderung (READY immer). */
    private var gemeldet: BleLinkState? = null

    /** Ob je eine Verbindung bestand: Danach heisst jeder neue Anlauf RECONNECTING. */
    private var warSchonVerbunden = false

    private var fehlversuche = 0
    private var sofortAblehnungenInFolge = 0

    /** Seit wann die aktuelle Verbindung READY ist (monotone Uhr), sonst `null`. */
    private var bereitSeitMs: Long? = null

    private var verbindungsFrist: Abbrechbar? = null
    private var neuversuch: Abbrechbar? = null
    private var blockadePruefung: Abbrechbar? = null
    private var operationsFrist: Abbrechbar? = null
    private var wache: Abbrechbar? = null
    private var schliessFrist: Abbrechbar? = null

    private val schlange = ArrayDeque<GattOp>()
    private var unterwegs: GattOp? = null

    /** Fuer Tests und Diagnose. */
    val zustand: BleLinkState?
        get() = gemeldet

    // ------------------------------------------------------------------ aussen

    fun open(l: BleLinkListener) {
        if (phase != Phase.NEU) return
        listener = l
        phase = Phase.VERBINDE
        melde(BleLinkState.CONNECTING)
        versuche()
    }

    fun enqueue(op: GattOp) {
        if (phase != Phase.BEREIT) {
            // Ausserhalb von READY sofort scheitern, nie still verwerfen.
            if (phase != Phase.ZU) ergebnis(op, ok = false, status = STATUS_NICHT_BEREIT)
            return
        }
        schlange.addLast(op)
        starteNaechste()
    }

    /**
     * Schliesst das Rohr. `CLOSED` kommt sofort, danach nichts mehr. Sind in
     * READY noch Writes eingereiht (etwa der Stopp-Befehl, den
     * `IndoorTrainer.stop()` direkt vor `close()` schreibt), arbeitet der Kern
     * sie still ab, bevor er trennt — hoechstens [SCHLIESS_FRIST_MS] lang;
     * alles andere in der Schlange verfaellt.
     */
    fun close() {
        if (phase == Phase.ZU || phase == Phase.SCHLIESST) return
        val warOffen = phase != Phase.NEU
        if (phase == Phase.BEREIT && (unterwegs is GattOp.Schreiben || schlange.any { it is GattOp.Schreiben })) {
            phase = Phase.SCHLIESST
            melde(BleLinkState.CLOSED)
            listener = null
            schlange.retainAll { it is GattOp.Schreiben }
            neuversuch?.abbrechen()
            blockadePruefung?.abbrechen()
            verbindungsFrist?.abbrechen()
            schliessFrist = takt.nach(SCHLIESS_FRIST_MS) { endgueltigSchliessen() }
            starteNaechste()
            return
        }
        phase = Phase.ZU
        alleFristenAbbrechen()
        schlange.clear()
        unterwegs = null
        port.schliessen()
        if (warOffen) melde(BleLinkState.CLOSED)
        listener = null
    }

    /** Ende des stillen Abarbeitens beim Schliessen (fertig, Frist, Abbruch). */
    private fun endgueltigSchliessen() {
        if (phase != Phase.SCHLIESST) return
        phase = Phase.ZU
        alleFristenAbbrechen()
        schlange.clear()
        unterwegs = null
        port.schliessen()
    }

    // ----------------------------------------------------------- GATT-Ereignisse

    fun onVerbindung(status: Int, verbunden: Boolean) {
        when (phase) {
            Phase.VERBINDE -> if (verbunden && status == GATT_SUCCESS) {
                phase = Phase.ENTDECKE
                plattform { if (!port.discoverServices()) scheitern(STATUS_KEINE_DIENSTSUCHE) }
            } else {
                scheitern(status)
            }
            // Jede Meldung ausser „verbunden" ist hier ein Abbruch; ein
            // doppeltes „verbunden" ignorieren wir.
            Phase.ENTDECKE, Phase.BEREIT -> if (!verbunden || status != GATT_SUCCESS) scheitern(status)
            Phase.SCHLIESST -> if (!verbunden || status != GATT_SUCCESS) endgueltigSchliessen()
            else -> Unit
        }
    }

    fun onDiensteErkannt(status: Int) {
        if (phase != Phase.ENTDECKE) return
        if (status != GATT_SUCCESS) {
            scheitern(status)
            return
        }
        val dienste = plattform { port.serviceUuids() }?.mapTo(mutableSetOf()) { it.lowercase() } ?: return
        verbindungsFrist?.abbrechen()
        verbindungsFrist = null
        phase = Phase.BEREIT
        sofortAblehnungenInFolge = 0
        bereitSeitMs = takt.jetztMs()
        warSchonVerbunden = true
        melde(BleLinkState.READY, dienste)
    }

    fun onDescriptorGeschrieben(characteristicUuid: String, status: Int) {
        val op = unterwegs as? GattOp.Abonnieren ?: return
        if (!op.characteristicUuid.equals(characteristicUuid, ignoreCase = true)) return
        abschliessen(op, status == GATT_SUCCESS, status)
    }

    fun onGelesen(characteristicUuid: String, wert: ByteArray, status: Int) {
        val op = unterwegs as? GattOp.Lesen ?: return
        if (!op.characteristicUuid.equals(characteristicUuid, ignoreCase = true)) return
        if (status == GATT_SUCCESS) {
            // Erst der Wert, dann das Ergebnis — so steht er beim Abschluss schon da.
            listener?.onValue(op.characteristicUuid, wert, takt.jetztMs())
            if (unterwegs !== op) return
        }
        abschliessen(op, status == GATT_SUCCESS, status)
    }

    fun onGeschrieben(characteristicUuid: String, status: Int) {
        val op = unterwegs as? GattOp.Schreiben ?: return
        if (!op.characteristicUuid.equals(characteristicUuid, ignoreCase = true)) return
        abschliessen(op, status == GATT_SUCCESS, status)
    }

    /** Auch ungefragt moeglich (das Geraet handelt die MTU selbst aus) — dann ohne Folgen. */
    fun onMtu(status: Int) {
        val op = unterwegs as? GattOp.Mtu ?: return
        abschliessen(op, status == GATT_SUCCESS, status)
    }

    fun onWert(characteristicUuid: String, wert: ByteArray, empfangenMs: Long) {
        if (phase != Phase.BEREIT) return
        listener?.onValue(characteristicUuid.lowercase(), wert, empfangenMs)
    }

    /** Die Plattform hat eine `SecurityException` geworfen (Berechtigung entzogen). */
    fun onKeineBerechtigung() {
        if (phase == Phase.ZU || phase == Phase.NEU) return
        if (phase == Phase.SCHLIESST) return endgueltigSchliessen()
        blockiert(Phase.KEINE_BERECHTIGUNG)
    }

    // ---------------------------------------------------------------- Verbinden

    private fun versuche() {
        if (phase == Phase.ZU) return
        neuversuch = null
        when {
            !port.bluetoothEin() -> blockiert(Phase.BLUETOOTH_AUS)
            !port.darfVerbinden() -> blockiert(Phase.KEINE_BERECHTIGUNG)
            else -> {
                phase = Phase.VERBINDE
                planeWache()
                verbindungsFrist?.abbrechen()
                verbindungsFrist = takt.nach(VERBINDUNGS_FRIST_MS) {
                    verbindungsFrist = null
                    scheitern(STATUS_ZEITUEBERSCHREITUNG)
                }
                val gestartet = plattform { port.verbinde() } ?: return
                if (!gestartet) scheitern(STATUS_KEIN_GATT)
            }
        }
    }

    /**
     * Abbruch oder Fehlschlag: GATT freigeben, offene Operationen scheitern
     * lassen, nach Takt neu versuchen. Nur aus einer aktiven Phase.
     */
    private fun scheitern(status: Int) {
        if (phase != Phase.VERBINDE && phase != Phase.ENTDECKE && phase != Phase.BEREIT) return
        DiagLog.shared.log(DiagEvent.BLE_GATT_ERROR, code = status)
        val seit = bereitSeitMs
        if (seit != null && takt.jetztMs() - seit >= GESUND_NACH_MS) fehlversuche = 0
        bereitSeitMs = null
        alleFristenAbbrechen()
        port.schliessen()
        phase = Phase.WARTET
        fehlversuche++
        val faellig = neuerVersuchNachMs(fehlversuche)
        neuversuch = takt.nach(faellig) { if (phase == Phase.WARTET) versuche() }
        offeneScheitern(status)
        if (phase == Phase.WARTET) {
            melde(if (warSchonVerbunden) BleLinkState.RECONNECTING else BleLinkState.CONNECTING)
        }
    }

    /** Dauerhaftes Hindernis: GATT frei, kein Neuversuch nach Takt, aber alle 2 s nachsehen. */
    private fun blockiert(grund: Phase) {
        bereitSeitMs = null
        alleFristenAbbrechen()
        port.schliessen()
        phase = grund
        offeneScheitern(STATUS_BLOCKIERT)
        if (phase != grund) return
        melde(if (grund == Phase.BLUETOOTH_AUS) BleLinkState.BLUETOOTH_OFF else BleLinkState.NO_PERMISSION)
        planeBlockadePruefung()
    }

    private fun planeBlockadePruefung() {
        blockadePruefung = takt.nach(BLOCKADE_PRUEFUNG_MS) {
            blockadePruefung = null
            if (phase != Phase.BLUETOOTH_AUS && phase != Phase.KEINE_BERECHTIGUNG) return@nach
            val frei = port.bluetoothEin() && port.darfVerbinden()
            if (!frei) {
                // Der Grund kann wechseln (Berechtigung erteilt, Bluetooth noch aus).
                val neu = if (!port.bluetoothEin()) Phase.BLUETOOTH_AUS else Phase.KEINE_BERECHTIGUNG
                if (neu != phase) blockiert(neu) else planeBlockadePruefung()
                return@nach
            }
            fehlversuche = 0
            phase = Phase.VERBINDE
            melde(if (warSchonVerbunden) BleLinkState.RECONNECTING else BleLinkState.CONNECTING)
            versuche()
        }
    }

    /** Prueft in den aktiven Phasen, ob Bluetooth noch an ist. */
    private fun planeWache() {
        wache?.abbrechen()
        wache = takt.nach(WACHE_MS) {
            wache = null
            if (phase == Phase.SCHLIESST) {
                if (!port.bluetoothEin()) endgueltigSchliessen() else planeWache()
                return@nach
            }
            if (phase != Phase.VERBINDE && phase != Phase.ENTDECKE && phase != Phase.BEREIT) return@nach
            if (!port.bluetoothEin()) blockiert(Phase.BLUETOOTH_AUS) else planeWache()
        }
    }

    // ------------------------------------------------------------ Warteschlange

    private fun starteNaechste() {
        if ((phase != Phase.BEREIT && phase != Phase.SCHLIESST) || unterwegs != null) return
        val op = schlange.removeFirstOrNull()
        if (op == null) {
            if (phase == Phase.SCHLIESST) endgueltigSchliessen()
            return
        }
        unterwegs = op
        operationsFrist = takt.nach(OPERATIONS_FRIST_MS) {
            operationsFrist = null
            fristAbgelaufen(op)
        }
        val code = plattform { port.starte(op) } ?: return
        if (code == GATT_SUCCESS) return
        if (phase == Phase.SCHLIESST) return abschliessen(op, ok = false, status = code, sofort = true)
        if (++sofortAblehnungenInFolge >= SOFORT_ABLEHNUNGEN_BIS_NEUVERBINDEN) {
            scheitern(code)
        } else {
            abschliessen(op, ok = false, status = code, sofort = true)
        }
    }

    private fun fristAbgelaufen(op: GattOp) {
        if (unterwegs !== op) return
        if (phase == Phase.SCHLIESST) return endgueltigSchliessen()
        // Der Stack antwortet nicht — neu verbinden raeumt ihn auf (siehe Klassenkommentar).
        scheitern(STATUS_ZEITUEBERSCHREITUNG)
    }

    private fun abschliessen(op: GattOp, ok: Boolean, status: Int, sofort: Boolean = false) {
        if (unterwegs !== op) return
        operationsFrist?.abbrechen()
        operationsFrist = null
        unterwegs = null
        // Eine Antwort per Callback (auch eine negative) zeigt: Der Stack lebt.
        if (!sofort) sofortAblehnungenInFolge = 0
        ergebnis(op, ok, status)
        starteNaechste()
    }

    /** Meldet die laufende und alle wartenden Operationen als gescheitert und leert die Schlange. */
    private fun offeneScheitern(status: Int) {
        val offen = listOfNotNull(unterwegs) + schlange
        unterwegs = null
        schlange.clear()
        operationsFrist?.abbrechen()
        operationsFrist = null
        for (op in offen) {
            if (phase == Phase.ZU) return
            ergebnis(op, ok = false, status = status)
        }
    }

    private fun ergebnis(op: GattOp, ok: Boolean, status: Int) {
        listener?.onOperationResult(op.art, op.characteristicUuid, ok, status)
    }

    // -------------------------------------------------------------------- Kram

    private fun melde(state: BleLinkState, dienste: Set<String> = emptySet()) {
        if (state == gemeldet && state != BleLinkState.READY) return
        gemeldet = state
        listener?.onLinkState(state, dienste)
    }

    private fun alleFristenAbbrechen() {
        verbindungsFrist?.abbrechen()
        neuversuch?.abbrechen()
        blockadePruefung?.abbrechen()
        operationsFrist?.abbrechen()
        wache?.abbrechen()
        wache = null
        schliessFrist?.abbrechen()
        schliessFrist = null
        verbindungsFrist = null
        neuversuch = null
        blockadePruefung = null
        operationsFrist = null
    }

    /**
     * Fuehrt einen Plattformaufruf aus; eine `SecurityException` (Berechtigung
     * mitten im Betrieb entzogen) wird zur Blockade statt zum Absturz.
     */
    private inline fun <T> plattform(block: () -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        DiagLog.shared.log(DiagEvent.BLE_NO_PERMISSION, error = e)
        if (phase == Phase.SCHLIESST) endgueltigSchliessen() else blockiert(Phase.KEINE_BERECHTIGUNG)
        null
    }

    companion object {
        const val GATT_SUCCESS = 0

        /** Frist fuer Verbindungsaufbau plus Diensterkennung — wie bei den Sensoren. */
        const val VERBINDUNGS_FRIST_MS = 20_000L

        /** Frist je Operation (Vertrag `BleLink`). */
        const val OPERATIONS_FRIST_MS = 5_000L

        /** Takt, in dem eine Blockade (Bluetooth aus, keine Berechtigung) neu geprueft wird. */
        const val BLOCKADE_PRUEFUNG_MS = 2_000L

        /** Hoechstdauer, in der `close()` eingereihte Writes noch abarbeitet. */
        const val SCHLIESS_FRIST_MS = 2_000L

        /** Synchrone Ablehnungen in Folge, ab denen der Kern neu verbindet. */
        const val SOFORT_ABLEHNUNGEN_BIS_NEUVERBINDEN = 3

        /** Takt der Pruefung „Bluetooth noch an?" in den aktiven Phasen. */
        const val WACHE_MS = 2_000L

        /** So lange muss eine Verbindung READY halten, bis der Takt der Fehlversuche zurueckfaellt. */
        const val GESUND_NACH_MS = 15_000L

        // Eigene Codes fuer `gattStatus` und das Diagnoseprotokoll — negativ,
        // damit sie sich nie mit einem echten GATT-Status (0…255) oder einem
        // `BluetoothStatusCodes`-Wert ueberschneiden.
        const val STATUS_ZEITUEBERSCHREITUNG = -1
        const val STATUS_KEIN_ADAPTER = -2
        const val STATUS_KEIN_GATT = -3
        const val STATUS_UNGUELTIGE_ADRESSE = -4
        const val STATUS_DIENST_FEHLT = -5
        const val STATUS_ABGELEHNT = -6
        const val STATUS_KEINE_DIENSTSUCHE = -7
        const val STATUS_NICHT_BEREIT = -8
        const val STATUS_BLOCKIERT = -9
        const val STATUS_NICHT_UNTERSTUETZT = -10
        const val STATUS_UNGUELTIGE_UUID = -11
    }
}

/** Eine eingereihte GATT-Operation. */
internal sealed class GattOp {
    abstract val art: BleOperation
    abstract val characteristicUuid: String?

    class Abonnieren(val serviceUuid: String, override val characteristicUuid: String, val indicate: Boolean) :
        GattOp() {
        override val art get() = BleOperation.SUBSCRIBE
    }

    class Lesen(val serviceUuid: String, override val characteristicUuid: String) : GattOp() {
        override val art get() = BleOperation.READ
    }

    class Schreiben(
        val serviceUuid: String,
        override val characteristicUuid: String,
        val wert: ByteArray,
        val mitAntwort: Boolean,
    ) : GattOp() {
        override val art get() = BleOperation.WRITE
    }

    class Mtu(val mtu: Int) : GattOp() {
        override val art get() = BleOperation.MTU
        override val characteristicUuid: String? get() = null
    }
}

/**
 * Was der Kern von der Plattform braucht. Alle Methoden laufen auf dem
 * BLE-Thread und duerfen `SecurityException` werfen.
 */
internal interface GattPort {
    fun bluetoothEin(): Boolean

    fun darfVerbinden(): Boolean

    /** `connectGatt(autoConnect = false, TRANSPORT_LE)`; `false`, wenn kein GATT entstand. */
    fun verbinde(): Boolean

    fun discoverServices(): Boolean

    /** Die erkannten Dienste, volle UUIDs in Kleinbuchstaben. */
    fun serviceUuids(): Set<String>

    /**
     * Startet [op]. [GattLinkKern.GATT_SUCCESS], wenn die Antwort als Callback
     * folgt; sonst ein Fehlercode, und es kommt keiner.
     */
    fun starte(op: GattOp): Int

    /** `disconnect()` + `close()` des aktuellen GATT, falls es eines gibt. Idempotent, wirft nie. */
    fun schliessen()
}

/** Zeit und Fristen fuer den Kern; die Bloecke laufen auf dem BLE-Thread. */
internal interface LinkTakt {
    /** Monotone Uhr (`SystemClock.elapsedRealtime()`). */
    fun jetztMs(): Long

    fun nach(verzoegerungMs: Long, block: () -> Unit): Abbrechbar
}

internal fun interface Abbrechbar {
    fun abbrechen()
}
