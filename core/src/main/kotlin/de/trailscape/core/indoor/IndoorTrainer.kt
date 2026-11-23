package de.trailscape.core.indoor

/**
 * # Trainer, Schalttaster und die Funkstrecke darunter
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitt 5). Besitzer: Slice indoor-core.
 * Typen und Signaturen sind eingefroren.
 *
 * ## Die Schichtung — und warum sie so geschnitten ist
 * ```
 *  :app  ui/indoor   Sitzungs-Laufzeit ──────────────┐ benutzt
 *  :core indoor      IndoorTrainer / IndoorShifter   │ (FTMS, Zwift Click, Demo)
 *  :core indoor      BleLink  ◄── Schnittstelle ─────┘
 *  :app  sensors/indoor  GattBleLink (Android-GATT)
 * ```
 * Wie bei den Sensoren (`BleSensorLogik.kt`) laesst sich die GATT-Verbindung
 * nur auf echter Hardware pruefen. Deshalb ist die Android-Seite ein **dummes
 * Rohr** ([BleLink]: verbinden, abonnieren, schreiben, Bytes weiterreichen),
 * und alles, was ein Protokoll ausmacht — FTMS-Bytes, Kontrolle anfordern,
 * Antworten abwarten, Wiederholen, der Handschlag des Schalttasters — steht in
 * `:core` und ist gegen ein Attrappen-Rohr getestet.
 *
 * ## Ohne Flow, ohne Timer, ohne Threads
 * `:core` hat keine Coroutines. Zustand kommt ueber Listener, Zeit ueber
 * `tick(nowMs)`: Die Laufzeit ruft es in ihrer Schleife (etwa 10 Hz) auf, und
 * nur dort laufen Fristen ab. Alle Zeiten sind Millisekunden einer monotonen
 * Uhr (`SystemClock.elapsedRealtime()` in der App) — niemals Wanduhrzeit.
 */

// ---------------------------------------------------------------------------
// UUIDs
// ---------------------------------------------------------------------------

/** GATT-UUIDs des Indoor-Trainings (volle 128-Bit-Form, Kleinbuchstaben). */
object IndoorUuids {
    private fun kurz(hex: String) = "0000$hex-0000-1000-8000-00805f9b34fb"

    /** Fitness Machine Service — der Scan-Filter fuer Trainer. */
    val FTMS_SERVICE = kurz("1826")
    val FTMS_FEATURE = kurz("2acc")
    val FTMS_INDOOR_BIKE_DATA = kurz("2ad2")
    val FTMS_TRAINING_STATUS = kurz("2ad3")
    val FTMS_SUPPORTED_RESISTANCE_RANGE = kurz("2ad6")
    val FTMS_SUPPORTED_POWER_RANGE = kurz("2ad8")
    val FTMS_CONTROL_POINT = kurz("2ad9")
    val FTMS_MACHINE_STATUS = kurz("2ada")

    /**
     * Dienst der Zwift-Bediengeraete (Click, Play, Ride), wie ihn die
     * Community dokumentiert (Quellen in docs/indoor.md, Abschnitt 5.4). NICHT
     * von einer Spezifikation gedeckt — der Slice indoor-core prueft die Werte
     * gegen seine Quellen und korrigiert sie notfalls **hier**.
     */
    const val ZWIFT_SERVICE = "00000001-19ca-4651-86e5-fa29dcdd09d1"

    /** Tastenereignisse (Notify). */
    const val ZWIFT_ASYNC = "00000002-19ca-4651-86e5-fa29dcdd09d1"

    /** Schreibkanal fuer den Handschlag (Write). */
    const val ZWIFT_SYNC_RX = "00000003-19ca-4651-86e5-fa29dcdd09d1"

    /** Antworten auf den Handschlag (Indicate). */
    const val ZWIFT_SYNC_TX = "00000004-19ca-4651-86e5-fa29dcdd09d1"

    /** Bluetooth-Firmenkennung in den Herstellerdaten der Werbung (zum Erkennen im Scan). */
    const val ZWIFT_COMPANY_ID: Int = 0x094A

    /**
     * Dienst neuerer Zwift-Bediengeraete (Click V2, Ride) — ergaenzt vom Slice
     * indoor-core. Darin dieselben Characteristics wie unter [ZWIFT_SERVICE]
     * (`…0002` Async, `…0003` SyncRX, `…0004` SyncTX). Quellen: BikeControl
     * `constants.dart` (`ZWIFT_RIDE_CUSTOM_SERVICE_UUID`; `zwift_device.dart`
     * sucht diesen Dienst zuerst, dann [ZWIFT_SERVICE]) und das GearUp-README.
     * Gesperrte V2-Geraete koennen ihn verbergen. Auch als Scan-Filter neben
     * [ZWIFT_SERVICE] und [ZWIFT_COMPANY_ID] gedacht.
     */
    const val ZWIFT_RIDE_SERVICE = "0000fc82-0000-1000-8000-00805f9b34fb"
}

// ---------------------------------------------------------------------------
// Das Rohr: BleLink
// ---------------------------------------------------------------------------

/** Zustand eines [BleLink]. */
enum class BleLinkState {
    /** Verbindungsaufbau oder Diensterkennung laeuft. */
    CONNECTING,

    /** Verbunden, Dienste bekannt — ab jetzt duerfen Operationen gestellt werden. */
    READY,

    /** Getrennt; das Rohr versucht es im Takt von `neuerVersuchNachMs` selbst wieder. */
    RECONNECTING,

    /** Dauerhaft blockiert, bis die Berechtigung „Geraete in der Naehe" erteilt ist. */
    NO_PERMISSION,

    /** Dauerhaft blockiert, bis Bluetooth eingeschaltet ist. */
    BLUETOOTH_OFF,

    /** Nach [BleLink.close]. */
    CLOSED,
}

/** Art einer GATT-Operation, fuer [BleLinkListener.onOperationResult]. */
enum class BleOperation { SUBSCRIBE, READ, WRITE, MTU }

/**
 * Rohverbindung zu **einem** gemerkten Bluetooth-LE-Geraet.
 *
 * Implementiert in `:app` vom Slice indoor-ble (`sensors/indoor/`), fuer Tests
 * als Attrappe in `:core`.
 *
 * ## Regeln fuer die Implementierung
 *  * **Eine Warteschlange.** Android-GATT vertraegt nur eine Operation
 *    gleichzeitig. Das Rohr reiht [subscribe], [read], [write], [requestMtu]
 *    in Aufrufreihenfolge ein und meldet jede mit
 *    [BleLinkListener.onOperationResult]. Eine Operation ohne Antwort gilt nach
 *    5 s als gescheitert (`ok = false`) — die Schlange darf nie haengen.
 *  * **Ein Thread.** Alle Listener-Aufrufe kommen nacheinander vom selben
 *    Thread (dem BLE-Thread der App). Die Methoden des Rohrs duerfen von jedem
 *    Thread gerufen werden, auch aus einem Listener-Aufruf heraus.
 *  * **Neuverbinden, aber nichts merken.** Nach einem Abbruch verbindet das
 *    Rohr selbst neu ([BleLinkState.RECONNECTING] → [BleLinkState.READY]).
 *    Abonnements stellt es dabei NICHT wieder her und die Schlange ist leer:
 *    Die Protokollschicht richtet sich nach jedem `READY` neu ein.
 *  * **Operationen ausserhalb von `READY`** werden sofort mit `ok = false`
 *    gemeldet, nie stillschweigend verworfen.
 *  * Kein Bonding, keine Systemdialoge — wie bei `BleSensors`.
 */
interface BleLink {
    /** Name des Geraets, wie beim Koppeln gemerkt; `null` ohne Namen. */
    val deviceName: String?

    /** Beginnt zu verbinden. Genau einmal je Rohr; [listener] bleibt bis [close]. */
    fun open(listener: BleLinkListener)

    /** Schaltet Notifications ([indicate] `false`) oder Indications (`true`) der Characteristic ein. */
    fun subscribe(serviceUuid: String, characteristicUuid: String, indicate: Boolean)

    /** Liest die Characteristic; der Wert kommt ueber [BleLinkListener.onValue]. */
    fun read(serviceUuid: String, characteristicUuid: String)

    /** Schreibt [value]; [withResponse] `true` = Write Request, `false` = Write Command. */
    fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean)

    /** Bittet um eine groessere MTU (der Schalttaster schickt Pakete ueber 20 Byte). */
    fun requestMtu(mtu: Int)

    /** Trennt endgueltig. Idempotent; danach kommt hoechstens noch [BleLinkState.CLOSED]. */
    fun close()
}

/** Ereignisse eines [BleLink] — alle vom selben Thread, siehe dort. */
interface BleLinkListener {
    /**
     * @param serviceUuids bei [BleLinkState.READY] die erkannten Dienste
     *   (volle UUIDs, Kleinbuchstaben), sonst leer.
     */
    fun onLinkState(state: BleLinkState, serviceUuids: Set<String>)

    /** Ein Wert kam an: Notification, Indication oder das Ergebnis von [BleLink.read]. */
    fun onValue(characteristicUuid: String, value: ByteArray, receivedMs: Long)

    /** Abschluss einer eingereihten Operation; [gattStatus] ist der rohe Android-Status (0 = Erfolg), fuer die Diagnose. */
    fun onOperationResult(operation: BleOperation, characteristicUuid: String?, ok: Boolean, gattStatus: Int)
}

// ---------------------------------------------------------------------------
// Trainer
// ---------------------------------------------------------------------------

/** Verbindungszustand eines Trainers, wie die Oberflaeche ihn zeigt. */
enum class TrainerConnection {
    /** Nicht gestartet oder gestoppt. */
    OFF,

    /** Funkverbindung wird aufgebaut bzw. nach Abbruch neu versucht. */
    CONNECTING,

    /** Verbunden, Messwerte kommen, aber (noch) keine Kontrolle ueber den Widerstand. */
    CONNECTED,

    /** Kontrolle erteilt — Befehle wirken. */
    READY,

    /** Keine Berechtigung „Geraete in der Naehe". */
    NO_PERMISSION,

    /** Bluetooth ist aus. */
    BLUETOOTH_OFF,

    /** Das Geraet spricht kein FTMS oder verweigert die Kontrolle dauerhaft. */
    UNSUPPORTED,
}

/**
 * Was der Trainer kann (aus FTMS Feature und den Range-Characteristics).
 *
 * @property simulation „Indoor Bike Simulation Parameters" wird unterstuetzt (SIM).
 * @property targetPower „Set Target Power" wird unterstuetzt (ERG).
 * @property minPowerW untere Grenze der Zielleistung, `null` = unbekannt.
 * @property maxPowerW obere Grenze der Zielleistung, `null` = unbekannt.
 * @property reportsCadence der Trainer liefert selbst eine Trittfrequenz.
 */
data class TrainerCapabilities(
    val simulation: Boolean,
    val targetPower: Boolean,
    val minPowerW: Int? = null,
    val maxPowerW: Int? = null,
    val reportsCadence: Boolean = false,
) {
    companion object {
        val NONE = TrainerCapabilities(simulation = false, targetPower = false)
    }
}

/**
 * Ein Messwert-Paket des Trainers (FTMS Indoor Bike Data).
 *
 * Jedes Feld ist `null`, wenn der Trainer es nicht sendet. Rohwerte, keine
 * Mittel — glaetten ist Sache der Sitzung.
 *
 * @property powerW momentane Leistung in Watt.
 * @property cadenceRpm Trittfrequenz in U/min.
 * @property speedKmh Radgeschwindigkeit, die der **Trainer** aus der
 *   Schwungmasse ableitet. Nicht das Tempo der virtuellen Fahrt — das rechnet
 *   die Sitzung aus der Leistung. Gebraucht fuer das Widerstandsmodell der
 *   virtuellen Gaenge.
 * @property receivedMs Empfangszeit (monotone Uhr).
 */
data class TrainerTelemetry(
    val powerW: Int?,
    val cadenceRpm: Double?,
    val speedKmh: Double?,
    val receivedMs: Long,
)

/** Ein Befehl an den Trainer. Die Sitzung erzeugt sie, die Laufzeit reicht sie an [IndoorTrainer.send]. */
sealed interface TrainerCommand {
    /**
     * SIM: „Set Indoor Bike Simulation Parameters".
     *
     * @property windMps Gegenwind in m/s (negativ = Rueckenwind).
     * @property gradePct Steigung in Prozent — bereits mit der virtuellen
     *   Uebersetzung verrechnet, nicht die der Strecke.
     * @property crr Rollwiderstandsbeiwert.
     * @property cwKgPerM Luftwiderstand `0,5 · ρ · CdA` in kg/m.
     */
    data class SetSimulation(
        val windMps: Double,
        val gradePct: Double,
        val crr: Double,
        val cwKgPerM: Double,
    ) : TrainerCommand

    /** ERG: „Set Target Power" in Watt. */
    data class SetTargetPower(val watts: Int) : TrainerCommand

    /** „Start or Resume" — vor der ersten Vorgabe und nach einer Pause. */
    data object Start : TrainerCommand

    /** „Stop or Pause" mit Pause — der Trainer gibt den Widerstand frei. */
    data object Pause : TrainerCommand

    /** „Stop or Pause" mit Stopp und „Reset" — am Ende der Fahrt. */
    data object Stop : TrainerCommand
}

/** Ereignisse eines [IndoorTrainer]. Thread: der des Rohrs bzw. der von [IndoorTrainer.tick]. */
interface TrainerListener {
    fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities)

    fun onTelemetry(telemetry: TrainerTelemetry)

    /**
     * Ein Befehl wurde endgueltig abgelehnt oder blieb nach allen
     * Wiederholungen unbeantwortet. Erfolg wird NICHT gemeldet.
     *
     * @param reason kurzer deutscher Diagnosetext (kein UI-Text, siehe docs/i18n.md „Diagnose bleibt Deutsch").
     */
    fun onCommandFailed(command: TrainerCommand, reason: String)
}

/**
 * Ein Rollentrainer, gleich ob echt (FTMS) oder simuliert ([DemoTrainer]).
 *
 * ## Vertrag
 *  * [start] baut die Verbindung auf und fordert die Kontrolle selbst an
 *    (FTMS „Request Control"), wiederholt das nach jedem Neuverbinden und
 *    meldet [TrainerConnection.READY], sobald Befehle wirken.
 *  * [send] ist jederzeit erlaubt. Vor `READY` merkt sich der Trainer je
 *    Befehlsart nur den **letzten** und schickt ihn nach, sobald er bereit
 *    ist; aeltere gleichartige Befehle verfallen (eine Steigung von vor fuenf
 *    Sekunden will niemand mehr). Nach einem Neuverbinden gilt dasselbe fuer
 *    den zuletzt gesendeten Modus.
 *  * [tick] treibt alle Fristen (Antwort-Timeout, Wiederholung). Ohne Aufruf
 *    laeuft keine Zeit.
 */
interface IndoorTrainer {
    /** Anzeigename („KICKR CORE 1A2B", „Demo-Trainer"). */
    val name: String

    fun start(listener: TrainerListener)

    fun send(command: TrainerCommand)

    fun tick(nowMs: Long)

    /** Gibt den Widerstand frei, trennt und vergisst den Listener. Idempotent. */
    fun stop()
}

/**
 * Der simulierte Trainer: Leistung und Trittfrequenz kommen vom Regler der
 * Oberflaeche statt von Beinen.
 *
 * Er verhaelt sich nach aussen wie ein echter ([TrainerConnection.READY] kurz
 * nach [start], Messwerte im Sekundentakt aus [tick]) und antwortet auf
 * Befehle plausibel: Im ERG-Modus laeuft die Leistung der Zielleistung nach,
 * im SIM-Modus gilt [riderPowerW].
 */
interface DemoTrainer : IndoorTrainer {
    /** Leistung des gedachten Fahrers in Watt (SIM-Modus). */
    var riderPowerW: Int

    /** Trittfrequenz des gedachten Fahrers in U/min. */
    var riderCadenceRpm: Int
}

// ---------------------------------------------------------------------------
// Schalttaster
// ---------------------------------------------------------------------------

/** Ein Schaltwunsch — vom Schalttaster oder von den Knoepfen auf dem Bildschirm. */
enum class ShifterEvent {
    /** Ein Gang schwerer. */
    UP,

    /** Ein Gang leichter. */
    DOWN,
}

/** Verbindungszustand des Schalttasters. */
enum class ShifterConnection { OFF, CONNECTING, READY, NO_PERMISSION, BLUETOOTH_OFF, UNSUPPORTED }

interface ShifterListener {
    fun onConnection(state: ShifterConnection)

    /** Ein Tastendruck (bei gehaltener Taste wiederholt, hoechstens alle 250 ms). */
    fun onShift(event: ShifterEvent)

    /** Ladestand in Prozent, falls das Geraet ihn meldet. */
    fun onBattery(percent: Int)
}

/**
 * Ein Schalttaster (Zwift Click). Best effort: Scheitert der Handschlag,
 * meldet er [ShifterConnection.UNSUPPORTED] und die Fahrt laeuft mit den
 * Knoepfen auf dem Bildschirm weiter.
 */
interface IndoorShifter {
    val name: String

    fun start(listener: ShifterListener)

    fun tick(nowMs: Long)

    fun stop()
}

/** Die beiden Geraeterollen, die gekoppelt werden koennen — je Rolle hoechstens ein Geraet. */
enum class IndoorDeviceRole { TRAINER, SHIFTER }
