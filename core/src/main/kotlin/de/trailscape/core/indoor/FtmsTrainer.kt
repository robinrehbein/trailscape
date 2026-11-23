package de.trailscape.core.indoor

/** Der FTMS-Trainer hinter dem Rohr [link]; Einstieg fuer `createFtmsTrainer`. */
internal fun ftmsTrainer(link: BleLink): IndoorTrainer = FtmsTrainer(link)

/**
 * # FTMS-Trainer: Einrichtung, Kontrolle, Befehle
 *
 * Der Automat ueber dem dummen Rohr [BleLink] (docs/indoor.md, Abschnitt 5.2).
 * Bytes und Quellen stehen in [FtmsCodec]; hier steht der Ablauf.
 *
 * ## Einrichtung nach jedem Rohr-`READY`
 * Fehlt der Dienst 0x1826 → [TrainerConnection.UNSUPPORTED] fuer diese
 * Verbindung. Sonst nacheinander, je erst nach dem Ergebnis der vorigen
 * Operation: Feature lesen → Supported Power Range lesen → Indoor Bike Data
 * abonnieren (Notify) → Machine Status abonnieren (Notify) → Control Point
 * abonnieren (Indicate). Das CCCD des Control Point muss vor dem ersten
 * Schreiben stehen, sonst antwortet der Trainer mit dem ATT-Fehler „CCCD
 * Improperly Configured“ (FTMS 1.0 §4.16.1).
 *
 *  * Lesefehler kosten nur Wissen: ohne Feature gilt konservativ
 *    [TrainerCapabilities.NONE], Befehle werden trotzdem versucht.
 *  * Nach dem Bike-Data-Abo (auch wenn es scheitert — Befehle koennen dann
 *    noch wirken) meldet der Trainer [TrainerConnection.CONNECTED].
 *  * Scheitert das Control-Point-Abo, bleibt er `CONNECTED` (nur Messwerte);
 *    jeder Befehl endet sofort in [TrainerListener.onCommandFailed].
 *
 * ## Kontrolle
 * Danach „Request Control“. Erfolg → [TrainerConnection.READY], gemerkte
 * Befehle gehen raus. Fehlschlag, „Control Not Permitted“ (0x05; etwa wenn
 * eine andere App die Kontrolle haelt) oder keine Antwort binnen 3 s → nach
 * 1 s ein zweiter Versuch; scheitert auch der, oder antwortet der Trainer
 * „Op Code not supported“ (0x02), gilt die Kontrolle fuer diese Verbindung
 * als dauerhaft verweigert → `UNSUPPORTED` (Messwerte laufen weiter).
 *
 * Die Kontrolle geht verloren durch Verbindungsabbruch, Machine Status 0xFF
 * „Control Permission Lost“ und durch einen Reset des Clients (FTMS 1.0
 * §4.16). Nach 0xFF meldet der Trainer `CONNECTED`, fordert neu an und
 * schickt danach den letzten Zustand und Modus nach. Nach dem eigenen
 * [TrainerCommand.Stop] (Stopp + Reset) meldet er ebenfalls `CONNECTED` und
 * fordert die Kontrolle erst wieder an, wenn nach dem Stop noch ein Befehl
 * kommt — so bleibt der Trainer am Ende einer Fahrt frei. `send(Stop)`
 * verwirft deshalb einen noch wartenden (und den gemerkten) Modus. Meldet der
 * Trainer waehrend des eigenen Stop (oder danach, ohne neueren Befehl) 0xFF,
 * ist das der Erfolg des Reset und kein Grund, neu anzufordern.
 *
 * ## Befehle
 * Hoechstens einer ist unterwegs. Zwei Plaetze, je nur der neueste Befehl:
 * ZUSTAND (Start, Pause, Stop) vor MODUS (SetSimulation, SetTargetPower).
 * Jeder Schritt ist ein Write Request auf den Control Point und wartet auf die
 * Indication `80 <opcode> <result>` mit **passendem** Opcode — andere
 * Antworten werden ignoriert. Frist 3 s; bei Frist oder Schreibfehler
 * wiederholen. Gezaehlt werden unbeantwortete Versuche je Platz in Folge,
 * ueber Ersetzungen hinweg; beim dritten → `onCommandFailed` fuer den
 * neuesten Befehl des Platzes, jede passende Antwort setzt zurueck. Wartet
 * hinter einem scheiternden Befehl schon ein neuerer derselben Art, verfaellt
 * der alte (sofern der Zaehler noch nicht bei drei ist) still. Spaete
 * Doppelantworten nach einer Frist-Wiederholung werden verworfen, damit sie
 * nicht den naechsten gleichartigen Befehl bestaetigen. Ergebnis
 * 0x02/0x03/0x04 → sofort `onCommandFailed`. 0x05 → Kontrolle einmal neu
 * anfordern, der Befehl bleibt gemerkt; ein zweites 0x05 fuer denselben
 * Befehl → `onCommandFailed`. Endgueltig abgelehnte Befehle werden auch nach
 * Neuverbinden nicht nachgeschickt. [TrainerCommand.Stop] besteht aus zwei
 * Schritten (08 01, dann 01 Reset); der Reset geht auch raus, wenn der
 * Trainer den Stopp ablehnt.
 *
 * Nach einem Neuverbinden oder Kontrollverlust werden der zuletzt gegebene
 * Zustand und Modus nachgeschickt — ausser `Stop` (der Reset wuerde die
 * frisch erhaltene Kontrolle gleich wieder abgeben; das Ende der Fahrt
 * erledigt [stop]).
 *
 * ## Zeit
 * Einzige Uhr ist [tick]. Rohr-Ereignisse tragen keine eigene Zeit, also
 * wird jede Frist (Antwort 3 s, erneute Kontrollanforderung 1 s) erst beim
 * **naechsten** tick verankert — nie an einer womoeglich veralteten
 * tick-Zeit (bei 10 Hz hoechstens ~100 ms zu spaet).
 *
 * ## Threads
 * Ein Lock schuetzt den Zustand. Rohr-Aufrufe und Listener-Aufrufe laufen
 * **ausserhalb** des Locks: Jeder Eingang rechnet unter dem Lock und legt
 * seine Wirkungen in eine Schlange; genau ein Thread arbeitet sie zur Zeit
 * ab, in Entstehungsreihenfolge. Ruft das Rohr aus `write` synchron zurueck
 * oder der Listener aus `onConnection` `send`, landet das Neue hinten in
 * derselben Schlange — keine Rekursion, kein Deadlock, Reihenfolge erhalten.
 * Folge: Ein Listener-Aufruf kann auf dem Thread eines anderen Eingangs
 * laufen (dem, der gerade abarbeitet).
 */
internal class FtmsTrainer(private val link: BleLink) : IndoorTrainer {
    override val name: String = link.deviceName ?: "FTMS-Trainer"

    private enum class Einrichtung { KEINE, FEATURE, POWER_RANGE, BIKE_DATA, MACHINE_STATUS, CONTROL_POINT, FERTIG }

    private enum class Kontrolle { KEINE, ANGEFORDERT, WARTET, ERTEILT, VERWEIGERT }

    private enum class Platz { ZUSTAND, MODUS }

    /** Ein gemerkter Befehl; [neuAngefordert] = fuer ihn wurde nach 0x05 schon einmal Kontrolle angefordert. */
    private class Eintrag(val command: TrainerCommand, val neuAngefordert: Boolean = false)

    private class Unterwegs(val eintrag: Eintrag, val platz: Platz, val schritte: List<ByteArray>) {
        var schritt = 0
        var token = 0
        var frist: Long? = null

        /** Waehrend dieses Befehls wurde eine Antwort seines Opcodes als Duplikat verworfen. */
        var verschluckt = false
        val erwarteterOpcode: Int get() = schritte[schritt][0].toInt() and 0xFF
    }

    private val lock = Any()

    // --- Wirkungsschlange (unter lock) ---
    private val wirkungen = ArrayDeque<() -> Unit>()
    private var arbeitetAb = false

    @Volatile
    private var listener: TrainerListener? = null

    // --- Zustand (unter lock) ---
    private var gestartet = false
    private var gestoppt = false
    private var linkZustand: BleLinkState? = null
    private var ftmsFehlt = false
    private var einrichtung = Einrichtung.KEINE
    private var cpVerfuegbar = false
    private var merkmale = TrainerCapabilities.NONE
    private var leistungsBereich: Pair<Int, Int>? = null

    private var kontrolle = Kontrolle.KEINE
    private var anforderungsFehlschlaege = 0
    private var kontrolleToken = 0
    private var kontrolleFrist: Long? = null
    private var wiederAnfordernAm: Long? = null

    private var platzZustand: Eintrag? = null
    private var platzModus: Eintrag? = null
    private var letzterZustand: TrainerCommand? = null
    private var letzterModus: TrainerCommand? = null
    private var unterwegs: Unterwegs? = null

    /** Unbeantwortete Versuche je [Platz] in Folge, ueber Ersetzungen hinweg; jede Antwort setzt zurueck. */
    private val unbeantwortet = IntArray(Platz.entries.size)

    /** Noch erwartete verspaetete Doppelantworten je Opcode (nach Frist-Wiederholungen). */
    private val duplikate = mutableMapOf<Int, Int>()

    /** Tokens der Control-Point-Writes in Rohr-Reihenfolge (das Rohr meldet FIFO). */
    private val schreibTokens = ArrayDeque<Int>()
    private var naechsterToken = 1

    private var gemeldet: Pair<TrainerConnection, TrainerCapabilities>? = null

    private val rohr = object : BleLinkListener {
        override fun onLinkState(state: BleLinkState, serviceUuids: Set<String>) =
            ausfuehren { linkZustandGeaendert(state, serviceUuids) }

        override fun onValue(characteristicUuid: String, value: ByteArray, receivedMs: Long) =
            ausfuehren { wertErhalten(characteristicUuid.lowercase(), value, receivedMs) }

        override fun onOperationResult(operation: BleOperation, characteristicUuid: String?, ok: Boolean, gattStatus: Int) =
            ausfuehren { operationBeendet(operation, characteristicUuid?.lowercase(), ok) }
    }

    // ======================================================================
    // IndoorTrainer
    // ======================================================================

    override fun start(listener: TrainerListener) = ausfuehren {
        if (!gestartet && !gestoppt) {
            gestartet = true
            this.listener = listener
            linkZustand = BleLinkState.CONNECTING
            wirke { link.open(rohr) }
        }
    }

    override fun send(command: TrainerCommand) = ausfuehren {
        if (!gestoppt) {
            val platz = platzVon(command)
            if (platz == Platz.ZUSTAND) letzterZustand = command else letzterModus = command
            if (command == TrainerCommand.Stop) {
                // Nach dem Stop zaehlt kein aelterer Modus mehr; er wuerde sonst die Kontrolle zurueckholen.
                platzModus = null
                letzterModus = null
            }
            val grund = dauerhafterHinderungsgrund()
            if (grund != null) {
                scheitere(command, grund)
            } else {
                if (platz == Platz.ZUSTAND) platzZustand = Eintrag(command) else platzModus = Eintrag(command)
                pumpe()
            }
        }
    }

    override fun tick(nowMs: Long) = ausfuehren {
        if (!gestoppt) {
            if (linkZustand == BleLinkState.READY) fristenPruefen(nowMs)
        }
    }

    /**
     * Schreibt im READY „Stop“ (08 01), ohne auf die Antwort zu warten, und
     * schliesst gleich danach das Rohr. Damit der Stopp den Trainer erreicht,
     * sollte das Rohr bereits eingereihte Writes vor dem Trennen noch
     * abarbeiten (Bitte an indoor-ble); sonst bleibt der Widerstand stehen,
     * bis der Trainer das Verbindungsende bemerkt.
     */
    override fun stop() = ausfuehren {
        if (!gestoppt) {
            val warBereit = verbindung() == TrainerConnection.READY
            gestoppt = true
            if (gestartet) {
                if (warBereit) {
                    wirke { link.write(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_CONTROL_POINT, FtmsCodec.stop(), true) }
                }
                wirke { link.close() }
            }
            listener = null
            verbindungWeg()
            platzZustand = null
            platzModus = null
        }
    }

    // ======================================================================
    // Rohr-Ereignisse
    // ======================================================================

    private fun linkZustandGeaendert(state: BleLinkState, services: Set<String>) {
        if (gestoppt || !gestartet) return
        if (state == BleLinkState.READY) {
            verbindungWeg()
            merkmale = TrainerCapabilities.NONE
            leistungsBereich = null
            linkZustand = state
            if (services.none { it.lowercase() == IndoorUuids.FTMS_SERVICE }) {
                ftmsFehlt = true
                alleScheitern("Trainer ohne FTMS-Dienst")
            } else {
                einrichtung = Einrichtung.FEATURE
                wirke { link.read(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_FEATURE) }
            }
        } else {
            // RECONNECTING, NO_PERMISSION, BLUETOOTH_OFF, CLOSED, CONNECTING: Laufendes verwerfen
            // (ohne onCommandFailed), Gemerktes behalten — nach dem naechsten READY geht es raus.
            linkZustand = state
            verbindungWeg()
        }
    }

    private fun wertErhalten(uuid: String, value: ByteArray, receivedMs: Long) {
        if (gestoppt || !gestartet) return
        when (uuid) {
            IndoorUuids.FTMS_FEATURE -> merkmale = FtmsCodec.parseFeature(value)
            IndoorUuids.FTMS_SUPPORTED_POWER_RANGE -> leistungsBereich = FtmsCodec.parsePowerRange(value)
            IndoorUuids.FTMS_INDOOR_BIKE_DATA -> {
                val t = FtmsCodec.parseIndoorBikeData(value, receivedMs)
                // Teilpakete ohne jeden der drei Werte (nur Distanz, Energie …) waeren reines Rauschen.
                if (t != null && (t.powerW != null || t.cadenceRpm != null || t.speedKmh != null)) {
                    wirke { listener?.onTelemetry(t) }
                }
            }
            IndoorUuids.FTMS_MACHINE_STATUS -> {
                if (FtmsCodec.parseMachineStatus(value) == FtmsCodec.STATUS_CONTROL_PERMISSION_LOST &&
                    kontrolle == Kontrolle.ERTEILT
                ) {
                    val u = unterwegs
                    val stopOffen = u?.eintrag?.command == TrainerCommand.Stop ||
                        (u == null && letzterZustand == TrainerCommand.Stop && letzterModus == null)
                    unterwegs = null
                    kontrolle = Kontrolle.KEINE
                    anforderungsFehlschlaege = 0
                    if (stopOffen) {
                        // Der Trainer meldet den Kontrollverlust durch unseren eigenen Reset,
                        // womoeglich vor dessen Indication: das ist der Erfolg des Stop.
                        if (platzZustand?.command == TrainerCommand.Stop) platzZustand = null
                        pumpe()
                    } else {
                        fordereKontrolleAn()
                    }
                }
            }
            IndoorUuids.FTMS_CONTROL_POINT -> FtmsCodec.parseControlPointResponse(value)?.let { antwortErhalten(it) }
        }
    }

    private fun operationBeendet(op: BleOperation, uuid: String?, ok: Boolean) {
        if (gestoppt || linkZustand != BleLinkState.READY) return
        if (op == BleOperation.WRITE && uuid == IndoorUuids.FTMS_CONTROL_POINT) {
            val token = schreibTokens.removeFirstOrNull()
            if (!ok && token != null) schreibenFehlgeschlagen(token)
            return
        }
        when {
            einrichtung == Einrichtung.FEATURE && op == BleOperation.READ && uuid == IndoorUuids.FTMS_FEATURE -> {
                einrichtung = Einrichtung.POWER_RANGE
                wirke { link.read(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_SUPPORTED_POWER_RANGE) }
            }
            einrichtung == Einrichtung.POWER_RANGE && op == BleOperation.READ &&
                uuid == IndoorUuids.FTMS_SUPPORTED_POWER_RANGE -> {
                einrichtung = Einrichtung.BIKE_DATA
                wirke { link.subscribe(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_INDOOR_BIKE_DATA, false) }
            }
            einrichtung == Einrichtung.BIKE_DATA && op == BleOperation.SUBSCRIBE &&
                uuid == IndoorUuids.FTMS_INDOOR_BIKE_DATA -> {
                einrichtung = Einrichtung.MACHINE_STATUS
                wirke { link.subscribe(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_MACHINE_STATUS, false) }
            }
            einrichtung == Einrichtung.MACHINE_STATUS && op == BleOperation.SUBSCRIBE &&
                uuid == IndoorUuids.FTMS_MACHINE_STATUS -> {
                einrichtung = Einrichtung.CONTROL_POINT
                wirke { link.subscribe(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_CONTROL_POINT, true) }
            }
            einrichtung == Einrichtung.CONTROL_POINT && op == BleOperation.SUBSCRIBE &&
                uuid == IndoorUuids.FTMS_CONTROL_POINT -> {
                einrichtung = Einrichtung.FERTIG
                cpVerfuegbar = ok
                if (ok) fordereKontrolleAn() else alleScheitern(GRUND_KEIN_CP)
            }
        }
    }

    // ======================================================================
    // Kontrolle
    // ======================================================================

    private fun fordereKontrolleAn() {
        kontrolle = Kontrolle.ANGEFORDERT
        wiederAnfordernAm = null
        kontrolleToken = schreibe(FtmsCodec.requestControl())
        kontrolleFrist = null // verankert beim naechsten tick
    }

    private fun kontrolleBeantwortet(result: Int) {
        kontrolleFrist = null
        when (result) {
            FtmsCodec.RESULT_SUCCESS -> {
                kontrolle = Kontrolle.ERTEILT
                anforderungsFehlschlaege = 0
                // Letzten Zustand und Modus nachschicken, sofern nichts Neueres wartet.
                if (platzZustand == null) {
                    letzterZustand?.takeIf { it != TrainerCommand.Stop }?.let { platzZustand = Eintrag(it) }
                }
                if (platzModus == null) letzterModus?.let { platzModus = Eintrag(it) }
                pumpe()
            }
            FtmsCodec.RESULT_NOT_SUPPORTED -> kontrolleVerweigert()
            else -> anforderungFehlgeschlagen()
        }
    }

    private fun anforderungFehlgeschlagen() {
        kontrolleFrist = null
        anforderungsFehlschlaege++
        if (anforderungsFehlschlaege >= 2) {
            kontrolleVerweigert()
        } else {
            kontrolle = Kontrolle.WARTET
            wiederAnfordernAm = null // verankert beim naechsten tick
        }
    }

    private fun kontrolleVerweigert() {
        kontrolle = Kontrolle.VERWEIGERT
        wiederAnfordernAm = null
        alleScheitern(GRUND_VERWEIGERT)
    }

    // ======================================================================
    // Befehle
    // ======================================================================

    private fun pumpe() {
        if (linkZustand != BleLinkState.READY || unterwegs != null) return
        if (kontrolle == Kontrolle.KEINE && einrichtung == Einrichtung.FERTIG && cpVerfuegbar &&
            (platzZustand != null || platzModus != null)
        ) {
            // Nach dem eigenen Reset (Stop): Kontrolle erst jetzt, da wieder ein Befehl wartet.
            anforderungsFehlschlaege = 0
            fordereKontrolleAn()
            return
        }
        if (kontrolle != Kontrolle.ERTEILT) return
        val zustand = platzZustand
        val u = if (zustand != null) {
            platzZustand = null
            Unterwegs(zustand, Platz.ZUSTAND, schritte(zustand.command))
        } else {
            val modus = platzModus ?: return
            platzModus = null
            Unterwegs(modus, Platz.MODUS, schritte(modus.command))
        }
        unterwegs = u
        sendeSchritt(u)
    }

    private fun sendeSchritt(u: Unterwegs) {
        u.token = schreibe(u.schritte[u.schritt])
        u.frist = null // verankert beim naechsten tick
    }

    private fun antwortErhalten(a: FtmsCodec.Antwort) {
        val dup = duplikate[a.opcode] ?: 0
        if (dup > 0) {
            // Ueberzaehlige spaete Antwort auf einen wiederholten Versuch: verwerfen, damit sie
            // nicht den naechsten gleichartigen Befehl bestaetigt.
            if (dup == 1) duplikate.remove(a.opcode) else duplikate[a.opcode] = dup - 1
            unterwegs?.takeIf { it.erwarteterOpcode == a.opcode }?.verschluckt = true
            return
        }
        if (kontrolle == Kontrolle.ANGEFORDERT) {
            if (a.opcode == FtmsCodec.OP_REQUEST_CONTROL) kontrolleBeantwortet(a.result)
            return
        }
        val u = unterwegs ?: return
        if (a.opcode != u.erwarteterOpcode) return
        unbeantwortet[u.platz.ordinal] = 0
        when (a.result) {
            FtmsCodec.RESULT_SUCCESS -> {
                if (u.schritt + 1 < u.schritte.size) {
                    u.schritt++
                    sendeSchritt(u)
                } else {
                    unterwegs = null
                    // Der Reset am Ende von Stop gibt die Kontrolle ab (FTMS 1.0 §4.16).
                    if (u.eintrag.command == TrainerCommand.Stop) kontrolle = Kontrolle.KEINE
                    pumpe()
                }
            }
            FtmsCodec.RESULT_CONTROL_NOT_PERMITTED -> {
                unterwegs = null
                if (u.eintrag.neuAngefordert) {
                    vergiss(u)
                    scheitere(u.eintrag.command, "Trainer meldet: " + FtmsCodec.resultName(a.result))
                    pumpe()
                } else {
                    if (platz(u.platz) == null) setzePlatz(u.platz, Eintrag(u.eintrag.command, neuAngefordert = true))
                    anforderungsFehlschlaege = 0
                    fordereKontrolleAn()
                }
            }
            else -> {
                if (u.eintrag.command == TrainerCommand.Stop && u.schritt == 0) {
                    // Stopp abgelehnt: der Reset gibt den Widerstand trotzdem frei.
                    u.schritt = 1
                    sendeSchritt(u)
                } else {
                    unterwegs = null
                    vergiss(u)
                    scheitere(u.eintrag.command, "Trainer lehnt ab: " + FtmsCodec.resultName(a.result))
                    pumpe()
                }
            }
        }
    }

    private fun schreibenFehlgeschlagen(token: Int) {
        if (kontrolle == Kontrolle.ANGEFORDERT && token == kontrolleToken) {
            anforderungFehlgeschlagen()
            return
        }
        val u = unterwegs ?: return
        if (u.token == token) versuchFehlgeschlagen(u, zeitueberschreitung = false)
    }

    /** Endgueltig abgelehnt: der Befehl wird auch nach Neuverbinden nicht wiederholt. */
    private fun vergiss(u: Unterwegs) {
        if (u.platz == Platz.ZUSTAND && letzterZustand == u.eintrag.command) letzterZustand = null
        if (u.platz == Platz.MODUS && letzterModus == u.eintrag.command) letzterModus = null
    }

    /**
     * Ein Versuch blieb unbeantwortet ([zeitueberschreitung]) oder liess sich nicht schreiben.
     *
     * Gezaehlt wird je Platz ueber Ersetzungen hinweg: Ein Trainer, der nie
     * antwortet, waehrend die Sitzung jede Sekunde eine neue Steigung schickt,
     * fuehrt so nach dem dritten unbeantworteten Versuch in Folge zu
     * `onCommandFailed` fuer den neuesten Befehl, statt dass jeder still
     * verfaellt. Nach einer Zeitueberschreitung kann die Antwort noch spaet
     * kommen; sie wird als Duplikat vorgemerkt ([duplikate]), es sei denn,
     * waehrend dieses Versuchs wurde schon eine Antwort seines Opcodes
     * verworfen (dann war die wohl seine — so schaukelt sich nichts auf).
     */
    private fun versuchFehlgeschlagen(u: Unterwegs, zeitueberschreitung: Boolean) {
        if (zeitueberschreitung && !u.verschluckt) {
            duplikate[u.erwarteterOpcode] = (duplikate[u.erwarteterOpcode] ?: 0) + 1
        }
        u.verschluckt = false
        val i = u.platz.ordinal
        unbeantwortet[i]++
        val neuer = platz(u.platz)
        if (unbeantwortet[i] >= MAX_VERSUCHE) {
            unterwegs = null
            unbeantwortet[i] = 0
            if (neuer != null) setzePlatz(u.platz, null)
            scheitere(neuer?.command ?: u.eintrag.command, "nach $MAX_VERSUCHE Versuchen unbeantwortet")
            pumpe()
        } else if (neuer != null) {
            // Ein neuerer Befehl derselben Art wartet schon: der alte verfaellt still.
            unterwegs = null
            pumpe()
        } else {
            sendeSchritt(u)
        }
    }

    private fun fristenPruefen(now: Long) {
        when (kontrolle) {
            Kontrolle.ANGEFORDERT -> {
                val f = kontrolleFrist
                if (f == null) kontrolleFrist = now + ANTWORT_FRIST_MS else if (now >= f) anforderungFehlgeschlagen()
            }
            Kontrolle.WARTET -> {
                val w = wiederAnfordernAm
                if (w == null) wiederAnfordernAm = now + WIEDER_ANFORDERN_MS else if (now >= w) fordereKontrolleAn()
            }
            else -> Unit
        }
        val u = unterwegs ?: return
        val f = u.frist
        if (f == null) u.frist = now + ANTWORT_FRIST_MS else if (now >= f) versuchFehlgeschlagen(u, zeitueberschreitung = true)
    }

    // ======================================================================
    // Hilfen (alle unter lock)
    // ======================================================================

    private fun verbindung(): TrainerConnection = when (linkZustand) {
        null, BleLinkState.CLOSED -> TrainerConnection.OFF
        BleLinkState.CONNECTING, BleLinkState.RECONNECTING -> TrainerConnection.CONNECTING
        BleLinkState.NO_PERMISSION -> TrainerConnection.NO_PERMISSION
        BleLinkState.BLUETOOTH_OFF -> TrainerConnection.BLUETOOTH_OFF
        BleLinkState.READY -> when {
            ftmsFehlt -> TrainerConnection.UNSUPPORTED
            einrichtung <= Einrichtung.BIKE_DATA -> TrainerConnection.CONNECTING
            kontrolle == Kontrolle.VERWEIGERT -> TrainerConnection.UNSUPPORTED
            kontrolle == Kontrolle.ERTEILT -> TrainerConnection.READY
            else -> TrainerConnection.CONNECTED
        }
    }

    private fun faehigkeiten(): TrainerCapabilities =
        merkmale.copy(minPowerW = leistungsBereich?.first, maxPowerW = leistungsBereich?.second)

    /** Warum ein Befehl auf dieser Verbindung nie wirken kann, oder `null`. */
    private fun dauerhafterHinderungsgrund(): String? {
        if (linkZustand != BleLinkState.READY) return null
        return when {
            ftmsFehlt -> "Trainer ohne FTMS-Dienst"
            einrichtung == Einrichtung.FERTIG && !cpVerfuegbar -> GRUND_KEIN_CP
            kontrolle == Kontrolle.VERWEIGERT -> GRUND_VERWEIGERT
            else -> null
        }
    }

    private fun verbindungWeg() {
        unterwegs = null
        schreibTokens.clear()
        unbeantwortet.fill(0)
        duplikate.clear()
        kontrolle = Kontrolle.KEINE
        anforderungsFehlschlaege = 0
        kontrolleFrist = null
        wiederAnfordernAm = null
        einrichtung = Einrichtung.KEINE
        cpVerfuegbar = false
        ftmsFehlt = false
    }

    private fun alleScheitern(grund: String) {
        unterwegs?.let { scheitere(it.eintrag.command, grund) }
        unterwegs = null
        platzZustand?.let { scheitere(it.command, grund) }
        platzModus?.let { scheitere(it.command, grund) }
        platzZustand = null
        platzModus = null
    }

    private fun platzVon(c: TrainerCommand): Platz = when (c) {
        TrainerCommand.Start, TrainerCommand.Pause, TrainerCommand.Stop -> Platz.ZUSTAND
        is TrainerCommand.SetSimulation, is TrainerCommand.SetTargetPower -> Platz.MODUS
    }

    private fun platz(p: Platz): Eintrag? = if (p == Platz.ZUSTAND) platzZustand else platzModus

    private fun setzePlatz(p: Platz, e: Eintrag?) {
        if (p == Platz.ZUSTAND) platzZustand = e else platzModus = e
    }

    private fun schritte(c: TrainerCommand): List<ByteArray> = when (c) {
        TrainerCommand.Start -> listOf(FtmsCodec.startOrResume())
        TrainerCommand.Pause -> listOf(FtmsCodec.pause())
        TrainerCommand.Stop -> listOf(FtmsCodec.stop(), FtmsCodec.reset())
        is TrainerCommand.SetTargetPower -> listOf(FtmsCodec.setTargetPower(c.watts))
        is TrainerCommand.SetSimulation -> listOf(FtmsCodec.setSimulation(c.windMps, c.gradePct, c.crr, c.cwKgPerM))
    }

    private fun schreibe(bytes: ByteArray): Int {
        val token = naechsterToken++
        schreibTokens.addLast(token)
        wirke { link.write(IndoorUuids.FTMS_SERVICE, IndoorUuids.FTMS_CONTROL_POINT, bytes, true) }
        return token
    }

    private fun scheitere(command: TrainerCommand, grund: String) {
        wirke { listener?.onCommandFailed(command, grund) }
    }

    private fun wirke(w: () -> Unit) {
        wirkungen.addLast(w)
    }

    /**
     * Rechnet [block] unter dem Lock, meldet einen geaenderten Verbindungszustand
     * und arbeitet danach die Wirkungsschlange ausserhalb des Locks ab — sofern
     * nicht schon ein (auch derselbe, reentrante) Aufrufer dabei ist.
     */
    private fun ausfuehren(block: () -> Unit) {
        synchronized(lock) {
            block()
            if (!gestoppt && gestartet) {
                val z = verbindung() to faehigkeiten()
                val alt = gemeldet
                // Waehrend CONNECTING fuellen sich die Faehigkeiten erst; gemeldet wird dann beim Zustandswechsel.
                if (alt == null || z.first != alt.first ||
                    (z.second != alt.second && z.first != TrainerConnection.CONNECTING)
                ) {
                    gemeldet = z
                    wirke { listener?.onConnection(z.first, z.second) }
                }
            }
            if (arbeitetAb) return
            arbeitetAb = true
        }
        while (true) {
            val w = synchronized(lock) {
                wirkungen.removeFirstOrNull().also { if (it == null) arbeitetAb = false }
            } ?: return
            try {
                w()
            } catch (t: Throwable) {
                synchronized(lock) { arbeitetAb = false }
                throw t
            }
        }
    }

    private companion object {
        const val ANTWORT_FRIST_MS = 3_000L
        const val WIEDER_ANFORDERN_MS = 1_000L
        const val MAX_VERSUCHE = 3
        const val GRUND_KEIN_CP = "Control Point nicht abonnierbar"
        const val GRUND_VERWEIGERT = "Kontrolle dauerhaft verweigert"
    }
}
