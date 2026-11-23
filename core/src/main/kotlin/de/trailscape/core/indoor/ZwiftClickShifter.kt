package de.trailscape.core.indoor

/** Der Schalttaster hinter dem Rohr [link]; Einstieg fuer `createZwiftClickShifter`. */
internal fun zwiftClickShifter(link: BleLink): IndoorShifter = ZwiftClickShifter(link)

/**
 * # Verbindungsautomat des Schalttasters (Zwift Click V1, Click V2 / Ride)
 *
 * Bytes und Quellen: [ZwiftClickProtocol]. Hier steht nur der Ablauf.
 *
 * ## Ablauf
 * 1. [start] → `CONNECTING`, `link.open`.
 * 2. Rohr `READY(services)`: Dienst `fc82` ([IndoorUuids.ZWIFT_RIDE_SERVICE])
 *    → Handschlag V2 (`RideOn 02 03`); sonst [IndoorUuids.ZWIFT_SERVICE] →
 *    Handschlag V1 (`RideOn`); keiner von beiden → `UNSUPPORTED`. Den
 *    fc82-Dienst zuerst zu nehmen folgt BikeControl `zwift_device.dart`.
 * 3. Einrichtung in dieser Reihenfolge ueber die Rohr-Schlange: Async
 *    abonnieren (Notify), SyncTX abonnieren (Indicate), dann den Handschlag auf
 *    SyncRX schreiben (Write Request — BikeControl schreibt ohne Antwort,
 *    jat255 und click2keyboard mit; beides geht laut Quellen, mit Antwort
 *    meldet das Rohr ein ehrliches Ergebnis). Abonnieren VOR dem Schreiben,
 *    sonst geht das Echo verloren (BikeControl `handleServices`).
 * 4. Indication auf SyncTX mit Praefix „RideOn“ → `READY`.
 *    Verschluesselte Antwort (Schluessel angehaengt: alte Click-Firmware bzw.
 *    Play, verlangt ECDH + AES-CCM) oder gesperrte Antwort (0xFF: Click V2,
 *    die erst von Zwift entsperrt werden muss) → `UNSUPPORTED`. Die
 *    Verschluesselung nachzubauen lohnt sich nicht (docs/indoor.md 5.4): die
 *    Knoepfe auf dem Bildschirm sind immer da.
 * 5. Frist 5 s je Versuch (ueber [tick]); nach Ablauf die Einrichtung einmal
 *    komplett wiederholen, danach `UNSUPPORTED`.
 *
 * **Annahme:** Kommen Tastenrahmen (0x37/0x23) schon vor dem Echo, gilt der
 * Taster ebenfalls als `READY` — er spricht dann offensichtlich Klartext.
 * Einige Firmware echot moeglicherweise nicht (die Laenge des Echos ist in den
 * Quellen schon unsicher); nichts in den Quellen spricht dagegen, den
 * Tastenrahmen als Beweis zu nehmen.
 *
 * `UNSUPPORTED` ist fuer diese Instanz endgueltig, und das Rohr wird
 * geschlossen: Verschluesselung, Sperre oder Schweigen aendern sich durch
 * Neuverbinden nicht, Click V2 trennt aber nach rund 61 s ohne Druck von selbst
 * (GearUp) — ohne Endgueltigkeit wuerde die Anzeige zwischen „verbinde“ und
 * „nicht unterstuetzt“ pendeln, und die Knopfzelle des Tasters leidet unnoetig.
 * Wer den Taster entsperrt hat, startet den Ablauf neu (neue Instanz).
 *
 * ## Neuverbinden und Rohrzustaende
 * Rohr `CONNECTING`/`RECONNECTING` → `CONNECTING`; gehaltene Tasten gelten als
 * losgelassen (ohne Ereignis), Handschlag beginnt nach dem naechsten `READY`
 * von vorn. `NO_PERMISSION`/`BLUETOOTH_OFF` werden durchgereicht, `CLOSED` →
 * `OFF`. Jeder Zustand wird nur bei Aenderung gemeldet.
 *
 * ## Tasten
 * Die Rahmen tragen den **ganzen** Zustand und kommen gehaeuft wiederholt
 * (jat255: „the same messages are sent multiple times“). Deshalb zaehlt nur
 * die Flanke: losgelassen → gedrueckt meldet sofort [ShifterEvent.UP] (Plus /
 * Hoch) bzw. [ShifterEvent.DOWN] (Minus / Runter); gleiche Zustaende werden
 * entprellt. Gehalten wiederholt [tick] den Schaltwunsch: erstmals 500 ms nach
 * dem Druck, dann alle 250 ms (Vertrag: hoechstens alle 250 ms). Der naechste
 * Termin liegt immer 250 ms nach der tatsaechlich gemeldeten Wiederholung —
 * ein spaeter tick holt nichts nach, es gibt also nie Salven. Beide Tasten
 * gleichzeitig: beide Flanken melden, aber keine Wiederholung, solange beide
 * gehalten sind (wohl ein Griff, kein Wunsch nach zehn Gaengen). Bleibt danach
 * eine gehalten, beginnt deren Wiederholung wieder mit 500 ms Vorlauf.
 *
 * Die Druckzeit ist `receivedMs` des Rahmens — laut Vertrag dieselbe monotone
 * Uhr wie [tick]. Fristen des Handschlags beginnen bei der letzten bekannten
 * tick-Zeit (das Rohr liefert ausser `receivedMs` keine Zeit); gab es noch
 * keinen tick, beginnt die Frist mit dem ersten.
 *
 * ## Threads
 * Ein Lock. Alles, was nach aussen geht (Listener, Rohr), wird unter dem Lock
 * nur in eine Schlange gelegt und danach ausserhalb abgearbeitet — von dem
 * Aufrufer, der gerade abarbeitet. Ruft das Rohr synchron aus `write`
 * zurueck, landet die Folge hinten in derselben Schlange; die Reihenfolge
 * bleibt erhalten und niemand haelt das Lock, waehrend fremder Code laeuft.
 */
internal class ZwiftClickShifter(private val link: BleLink) : IndoorShifter {

    override val name: String
        get() = link.deviceName ?: "Schalttaster"

    private enum class Phase { IDLE, CONNECTING, HANDSHAKE, READY, BLOCKED, UNSUPPORTED }

    private val lock = Any()
    private val pending = ArrayDeque<() -> Unit>()
    private var draining = false

    private var listener: ShifterListener? = null
    private var started = false
    private var stopped = false

    private var phase = Phase.IDLE
    private var reported: ShifterConnection? = null
    private var nowMs: Long? = null

    private var serviceUuid: String? = null
    private var variant: ClickVariant? = null
    private var attempt = 0
    private var deadlineMs: Long? = null

    private var plusHeld = false
    private var minusHeld = false
    private var plusNextMs = 0L
    private var minusNextMs = 0L
    private var battery: Int? = null

    /** Letzte Diagnose (Deutsch, kein UI-Text) — warum `UNSUPPORTED` oder was zuletzt schiefging. */
    @Volatile
    internal var diagnose: String? = null
        private set

    /** Erkannte Variante, sobald das Rohr seine Dienste gemeldet hat (fuer Diagnose und Tests). */
    internal val detectedVariant: ClickVariant?
        get() = synchronized(lock) { variant }

    private val rohr = object : BleLinkListener {
        override fun onLinkState(state: BleLinkState, serviceUuids: Set<String>) =
            act { linkState(state, serviceUuids) }

        override fun onValue(characteristicUuid: String, value: ByteArray, receivedMs: Long) =
            act { value(characteristicUuid.lowercase(), value.copyOf(), receivedMs) }

        override fun onOperationResult(
            operation: BleOperation,
            characteristicUuid: String?,
            ok: Boolean,
            gattStatus: Int,
        ) = act { operationResult(operation, characteristicUuid, ok, gattStatus) }
    }

    override fun start(listener: ShifterListener) = act {
        if (started || stopped) return@act
        started = true
        this.listener = listener
        phase = Phase.CONNECTING
        report(ShifterConnection.CONNECTING)
        pending.addLast { link.open(rohr) }
    }

    override fun tick(nowMs: Long) = act {
        if (stopped) return@act
        this.nowMs = nowMs
        when (phase) {
            Phase.HANDSHAKE -> {
                val d = deadlineMs
                when {
                    d == null -> deadlineMs = nowMs + HANDSHAKE_TIMEOUT_MS
                    nowMs >= d && attempt < HANDSHAKE_ATTEMPTS -> {
                        attempt++
                        diagnose = "keine Antwort auf den Handschlag, Versuch $attempt"
                        deadlineMs = nowMs + HANDSHAKE_TIMEOUT_MS
                        queueSetup()
                    }
                    nowMs >= d -> unsupported(
                        "keine Antwort auf den Handschlag nach $HANDSHAKE_ATTEMPTS Versuchen " +
                            "(stumm oder unbekannte Firmware)",
                    )
                }
            }
            Phase.READY -> repeatHeld(nowMs)
            else -> Unit
        }
    }

    override fun stop() = act {
        if (stopped) return@act
        stopped = true
        listener = null
        val wasStarted = started
        if (wasStarted) pending.addLast { link.close() }
    }

    // ------------------------------------------------------------ unter dem Lock

    private fun linkState(state: BleLinkState, services: Set<String>) {
        if (stopped || phase == Phase.UNSUPPORTED) return
        when (state) {
            BleLinkState.CONNECTING, BleLinkState.RECONNECTING -> {
                resetConnection()
                phase = Phase.CONNECTING
                report(ShifterConnection.CONNECTING)
            }
            BleLinkState.READY -> {
                resetConnection()
                val lower = services.mapTo(HashSet()) { it.lowercase() }
                val (svc, v) = when {
                    IndoorUuids.ZWIFT_RIDE_SERVICE in lower -> IndoorUuids.ZWIFT_RIDE_SERVICE to ClickVariant.V2
                    IndoorUuids.ZWIFT_SERVICE in lower -> IndoorUuids.ZWIFT_SERVICE to ClickVariant.V1
                    else -> {
                        unsupported("kein Zwift-Dienst (weder fc82 noch 00000001-…) gefunden")
                        return
                    }
                }
                serviceUuid = svc
                variant = v
                phase = Phase.HANDSHAKE
                attempt = 1
                deadlineMs = nowMs?.plus(HANDSHAKE_TIMEOUT_MS)
                report(ShifterConnection.CONNECTING)
                queueSetup()
            }
            BleLinkState.NO_PERMISSION -> {
                resetConnection()
                phase = Phase.BLOCKED
                report(ShifterConnection.NO_PERMISSION)
            }
            BleLinkState.BLUETOOTH_OFF -> {
                resetConnection()
                phase = Phase.BLOCKED
                report(ShifterConnection.BLUETOOTH_OFF)
            }
            BleLinkState.CLOSED -> {
                resetConnection()
                phase = Phase.IDLE
                report(ShifterConnection.OFF)
            }
        }
    }

    private fun value(uuid: String, bytes: ByteArray, receivedMs: Long) {
        if (stopped) return
        if (phase != Phase.HANDSHAKE && phase != Phase.READY) return
        when (uuid) {
            IndoorUuids.ZWIFT_SYNC_TX -> {
                if (phase != Phase.HANDSHAKE) return
                when (ZwiftClickProtocol.classifyHandshakeAnswer(bytes)) {
                    HandshakeAnswer.OK -> becomeReady()
                    HandshakeAnswer.ENCRYPTED -> unsupported(
                        "Handschlag-Antwort mit Schluessel: verschluesselte Firmware (ECDH/AES-CCM), nicht unterstuetzt",
                    )
                    HandshakeAnswer.LOCKED -> unsupported(
                        "Handschlag-Antwort beginnt mit 0xFF: Geraet gesperrt (muss erst in Zwift entsperrt werden)",
                    )
                    HandshakeAnswer.UNKNOWN -> diagnose = "unerwartete Handschlag-Antwort (${bytes.size} Byte), warte weiter"
                }
            }
            IndoorUuids.ZWIFT_ASYNC -> when (val frame = ZwiftClickProtocol.parseFrame(bytes)) {
                is ClickFrame.Buttons -> {
                    if (phase == Phase.HANDSHAKE) becomeReady()
                    applyButtons(frame.plus, frame.minus, receivedMs)
                }
                is ClickFrame.Battery -> if (battery != frame.percent) {
                    battery = frame.percent
                    val p = frame.percent
                    toListener { it.onBattery(p) }
                }
                is ClickFrame.Malformed -> diagnose = "verworfener Rahmen: ${frame.why}"
                ClickFrame.Idle, is ClickFrame.Ignored -> Unit
            }
            else -> Unit
        }
    }

    private fun operationResult(op: BleOperation, uuid: String?, ok: Boolean, status: Int) {
        if (stopped || ok || phase != Phase.HANDSHAKE) return
        // Kein Abbruch: die Frist mit ihrer einen Wiederholung deckt das ab, und
        // ein Abbruch der Verbindung kommt ohnehin als RECONNECTING.
        diagnose = "$op gescheitert (${uuid ?: "-"}, GATT $status)"
    }

    private fun queueSetup() {
        val svc = serviceUuid ?: return
        val hs = ZwiftClickProtocol.handshake(variant ?: ClickVariant.V1)
        pending.addLast { link.subscribe(svc, IndoorUuids.ZWIFT_ASYNC, indicate = false) }
        pending.addLast { link.subscribe(svc, IndoorUuids.ZWIFT_SYNC_TX, indicate = true) }
        pending.addLast { link.write(svc, IndoorUuids.ZWIFT_SYNC_RX, hs, withResponse = true) }
    }

    private fun becomeReady() {
        phase = Phase.READY
        deadlineMs = null
        report(ShifterConnection.READY)
    }

    private fun unsupported(reason: String) {
        diagnose = reason
        resetConnection()
        phase = Phase.UNSUPPORTED
        report(ShifterConnection.UNSUPPORTED)
        pending.addLast { link.close() }
    }

    private fun resetConnection() {
        deadlineMs = null
        attempt = 0
        plusHeld = false
        minusHeld = false
    }

    private fun applyButtons(plus: Boolean, minus: Boolean, t: Long) {
        val wasBoth = plusHeld && minusHeld
        if (plus && !plusHeld) {
            plusNextMs = t + REPEAT_FIRST_MS
            toListener { it.onShift(ShifterEvent.UP) }
        }
        if (minus && !minusHeld) {
            minusNextMs = t + REPEAT_FIRST_MS
            toListener { it.onShift(ShifterEvent.DOWN) }
        }
        if (wasBoth && plus != minus) {
            // Von beiden auf eine: Die verbliebene bekommt wieder den vollen Vorlauf.
            if (plus) plusNextMs = t + REPEAT_FIRST_MS else minusNextMs = t + REPEAT_FIRST_MS
        }
        plusHeld = plus
        minusHeld = minus
    }

    private fun repeatHeld(now: Long) {
        if (plusHeld == minusHeld) return // keine oder beide: nichts wiederholen
        if (plusHeld && now >= plusNextMs) {
            plusNextMs = now + REPEAT_EVERY_MS
            toListener { it.onShift(ShifterEvent.UP) }
        }
        if (minusHeld && now >= minusNextMs) {
            minusNextMs = now + REPEAT_EVERY_MS
            toListener { it.onShift(ShifterEvent.DOWN) }
        }
    }

    private fun report(state: ShifterConnection) {
        if (reported == state) return
        reported = state
        toListener { it.onConnection(state) }
    }

    /** Listener-Aufruf einreihen; der Listener wird erst beim Ausfuehren geholt (nach [stop] ist er weg). */
    private fun toListener(call: (ShifterListener) -> Unit) {
        pending.addLast {
            val l = synchronized(lock) { listener }
            if (l != null) call(l)
        }
    }

    // ------------------------------------------------------------ Schlange

    private inline fun act(block: () -> Unit) {
        val drainHere: Boolean
        synchronized(lock) {
            block()
            drainHere = !draining && pending.isNotEmpty()
            if (drainHere) draining = true
        }
        if (drainHere) drain()
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (pending.isEmpty()) {
                    draining = false
                    null
                } else {
                    pending.removeFirst()
                }
            } ?: return
            try {
                next()
            } catch (t: Throwable) {
                // Fremder Code warf: Schlange freigeben, damit der naechste Aufrufer weitermacht.
                synchronized(lock) { draining = false }
                throw t
            }
        }
    }

    companion object {
        /** Frist je Handschlag-Versuch. */
        const val HANDSHAKE_TIMEOUT_MS = 5_000L

        /** Versuche insgesamt (einer plus eine Wiederholung). */
        const val HANDSHAKE_ATTEMPTS = 2

        /** Erste Wiederholung einer gehaltenen Taste. */
        const val REPEAT_FIRST_MS = 500L

        /** Abstand weiterer Wiederholungen (Vertrag: hoechstens alle 250 ms). */
        const val REPEAT_EVERY_MS = 250L
    }
}
