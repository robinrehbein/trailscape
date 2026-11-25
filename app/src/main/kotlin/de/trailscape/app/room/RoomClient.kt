package de.trailscape.app.room

import de.trailscape.core.room.ClientMessage
import de.trailscape.core.room.ClockSample
import de.trailscape.core.room.RoomClock
import de.trailscape.core.room.RoomCodec
import de.trailscape.core.room.RoomInvite
import de.trailscape.core.room.ServerMessage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlin.math.min
import kotlin.random.Random

/**
 * # Netz-Client für einen Raum (docs/raum-protokoll.md)
 *
 * Bewusst schmal und **ohne Oberfläche**: verbinden, beitreten, senden,
 * empfangen, bei Abbruch mit Backoff wieder verbinden (mit `rejoin`, damit
 * man denselben Platz und dieselbe Kennung behält). Was mit den Nachrichten
 * passiert, entscheidet der Aufrufer — der Raumzustand ist die reine
 * Funktion `RoomState.reduce` in `:core`.
 *
 * Noch nirgends eingebaut; Einbau in Fahrt, Hintergrunddienst und
 * Oberfläche sind eigene Schritte (Bericht zu Etappe 5).
 */
interface RoomClient {
    val status: StateFlow<RoomConnectionStatus>

    /** Server-Zeit für eine Geräte-Zeit (monotone Uhr des Clients), sobald bekannt. */
    fun serverTimeMs(clientMs: Long): Long?

    /** Verbindet und tritt bei. Ein zweiter Aufruf ersetzt die alte Verbindung. */
    fun connect(params: RoomJoinParams)

    /** Sendet, wenn verbunden und beigetreten; sonst `false` (Zustände sind ersetzbar, nichts wird gepuffert). */
    fun send(message: ClientMessage): Boolean

    /** Raum verlassen (`leave`) und nicht wieder verbinden. */
    fun leave()

    /** Verbindung beenden, ohne den Raum zu verlassen (Platz bleibt kurz reserviert). */
    fun close()

    fun setListener(listener: RoomClientListener?)
}

fun interface RoomClientListener {
    /** Jede gültige Nachricht vom Server. Läuft auf einem Netz-Thread: nicht blockieren. */
    fun onMessage(message: ServerMessage)
}

class RoomJoinParams(
    val invite: RoomInvite,
    val name: String,
    val avatar: JsonElement? = null,
    val sharePower: Boolean = true,
    /** Statt des Gast-Geheimnisses aus [invite]: Gastgeber (Countdown, Beenden). */
    val hostSecret: String? = null,
) {
    override fun toString(): String = "RoomJoinParams(invite=$invite, name=$name, host=${hostSecret != null})"
}

sealed interface RoomConnectionStatus {
    data object Idle : RoomConnectionStatus
    data class Connecting(val attempt: Int) : RoomConnectionStatus
    data class Connected(val riderId: String, val host: Boolean) : RoomConnectionStatus
    /** Verbindung weg, nächster Versuch in [inMs]. */
    data class Reconnecting(val attempt: Int, val inMs: Long, val lastCode: Int?) : RoomConnectionStatus
    /** Endgültig zu; [reason] sagt warum. */
    data class Closed(val reason: CloseReason, val code: Int?) : RoomConnectionStatus
}

enum class CloseReason {
    /** Selbst beendet ([RoomClient.close]). */
    BY_USER,
    /** Raum verlassen ([RoomClient.leave]). */
    LEFT,
    /** Raum unbekannt, beendet oder Geheimnis falsch. */
    DENIED,
    ROOM_FULL,
    /** Server spricht eine andere Protokollversion. */
    VERSION,
    ENDED,
    /** Dieselbe Kennung ist über eine andere Verbindung beigetreten. */
    REPLACED,
    /** Zu viele Fehlversuche von dieser Adresse. */
    BLOCKED,
    /** Server lehnt unsere Nachrichten ab (Fehler in der App). */
    PROTOCOL,
    /** Zu viele Versuche ohne Erfolg. */
    GAVE_UP,
}

/** Zeitgeber, in Tests von Hand weitergedreht. */
interface RoomScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): Cancellable
    fun interface Cancellable { fun cancel() }
}

class ExecutorRoomScheduler(
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "trailscape-raum").apply { isDaemon = true }
    },
) : RoomScheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): RoomScheduler.Cancellable {
        val f = executor.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS)
        return RoomScheduler.Cancellable { f.cancel(false) }
    }
}

/**
 * Wartezeit vor Versuch [attempt] (1, 2, …): exponentiell ab [baseMs] bis
 * [maxMs], davon zufällig die obere Hälfte („equal jitter“), damit nicht
 * alle Geräte nach einem Server-Neustart im selben Takt anklopfen.
 */
fun reconnectDelayMs(attempt: Int, random: Random, baseMs: Long = 1_000L, maxMs: Long = 30_000L): Long {
    val exp = baseMs shl min(attempt - 1, 16).coerceAtLeast(0)
    val capped = min(exp, maxMs)
    return capped / 2 + random.nextLong(capped / 2 + 1)
}

/**
 * Standard-Umsetzung von [RoomClient].
 *
 * Alle Zustandsänderungen laufen unter einer Sperre; Rückrufe kommen von
 * OkHttp- bzw. Zeitgeber-Threads. Veraltete Rückrufe einer ersetzten
 * Verbindung werden über eine laufende Nummer erkannt und verworfen.
 */
class ReconnectingRoomClient(
    private val transport: RoomTransport,
    private val scheduler: RoomScheduler = ExecutorRoomScheduler(),
    /** Monotone Uhr des Geräts in ms (Betrieb: `SystemClock.elapsedRealtime`). */
    private val clientClock: () -> Long,
    private val random: Random = Random.Default,
    private val pingIntervalMs: Long = 5_000L,
    private val maxAttempts: Int = 30,
) : RoomClient {
    private val lock = Any()
    private val _status = MutableStateFlow<RoomConnectionStatus>(RoomConnectionStatus.Idle)
    override val status: StateFlow<RoomConnectionStatus> = _status.asStateFlow()

    private var listener: RoomClientListener? = null
    private var params: RoomJoinParams? = null
    private var socket: RoomSocket? = null
    private var generation = 0
    private var attempt = 0
    private var joined = false
    private var stopped = true
    private var riderId: String? = null
    private var rejoinKey: String? = null
    private var clock = RoomClock()
    private var pingTask: RoomScheduler.Cancellable? = null
    private var retryTask: RoomScheduler.Cancellable? = null

    override fun setListener(listener: RoomClientListener?) {
        synchronized(lock) { this.listener = listener }
    }

    override fun serverTimeMs(clientMs: Long): Long? {
        synchronized(lock) { return clock.offsetMs?.let { clientMs + it } }
    }

    override fun connect(params: RoomJoinParams) {
        synchronized(lock) { connectLocked(params) }
    }

    private fun connectLocked(params: RoomJoinParams) {
        shutdownSocket(1000, "Neu verbunden")
        if (this.params?.invite != params.invite) {
            // Anderer Raum: alte Kennung vergessen.
            riderId = null
            rejoinKey = null
            clock = RoomClock()
        }
        this.params = params
        stopped = false
        attempt = 0
        openSocket()
    }

    override fun send(message: ClientMessage): Boolean {
        synchronized(lock) {
            if (!joined || message is ClientMessage.Join) return false
            return socket?.send(RoomCodec.encode(message)) ?: false
        }
    }

    override fun leave() {
        synchronized(lock) {
            if (joined) socket?.send(RoomCodec.encode(ClientMessage.Leave))
            stopWith(CloseReason.LEFT, null, closeCode = 1000)
        }
    }

    override fun close() {
        synchronized(lock) { stopWith(CloseReason.BY_USER, null, closeCode = 1000) }
    }

    // ------------------------------------------------------------------

    private fun openSocket() {
        val p = params ?: return
        attempt += 1
        val gen = ++generation
        joined = false
        _status.value = RoomConnectionStatus.Connecting(attempt)
        socket = transport.open(p.invite.webSocketUrl(), object : RoomSocketListener {
            override fun onOpen() {
                synchronized(lock) { if (gen == generation) sendJoin() }
            }
            override fun onMessage(text: String) {
                synchronized(lock) { if (gen == generation) handleText(text) }
            }
            override fun onClosed(code: Int, reason: String) {
                synchronized(lock) { if (gen == generation) handleDrop(code) }
            }
            override fun onFailure(error: Throwable) {
                synchronized(lock) { if (gen == generation) handleDrop(null) }
            }
        })
    }

    private fun sendJoin() {
        val p = params ?: return
        val id = riderId
        val key = rejoinKey
        val join = ClientMessage.Join(
            room = p.invite.roomId,
            secret = p.hostSecret ?: p.invite.secret,
            name = p.name,
            avatar = p.avatar,
            sharePower = p.sharePower,
            rejoin = if (id != null && key != null) ClientMessage.Rejoin(id, key) else null,
        )
        socket?.send(RoomCodec.encode(join))
    }

    private fun handleText(text: String) {
        val msg = RoomCodec.decodeServer(text) ?: return // kaputt: still verwerfen
        when (msg) {
            is ServerMessage.Welcome -> {
                joined = true
                attempt = 0
                riderId = msg.you.id
                rejoinKey = msg.you.rejoinKey
                clock = clock.withServerTime(msg.serverTime, clientClock())
                _status.value = RoomConnectionStatus.Connected(msg.you.id, msg.you.host)
                schedulePing(0L)
            }
            is ServerMessage.Pong -> {
                val c = msg.c
                if (c != null) clock = clock.add(ClockSample(c, msg.s, clientClock()))
            }
            else -> Unit
        }
        listener?.onMessage(msg)
        if (msg is ServerMessage.Ended) stopWith(CloseReason.ENDED, null, closeCode = 1000)
    }

    private fun schedulePing(delayMs: Long) {
        pingTask?.cancel()
        val gen = generation
        pingTask = scheduler.schedule(delayMs) {
            synchronized(lock) {
                if (gen == generation && joined) {
                    socket?.send(RoomCodec.encode(ClientMessage.Ping(clientClock())))
                    schedulePing(pingIntervalMs)
                }
            }
        }
    }

    private fun handleDrop(code: Int?) {
        if (stopped) return
        socket = null
        joined = false
        pingTask?.cancel()
        permanentReason(code)?.let { reason ->
            stopWith(reason, code, closeCode = null)
            return
        }
        if (attempt >= maxAttempts) {
            stopWith(CloseReason.GAVE_UP, code, closeCode = null)
            return
        }
        val delay = reconnectDelayMs(attempt.coerceAtLeast(1), random)
        _status.value = RoomConnectionStatus.Reconnecting(attempt + 1, delay, code)
        val gen = ++generation
        retryTask = scheduler.schedule(delay) {
            synchronized(lock) { if (gen == generation && !stopped) openSocket() }
        }
    }

    private fun stopWith(reason: CloseReason, code: Int?, closeCode: Int?) {
        stopped = true
        shutdownSocket(closeCode, "")
        if (reason == CloseReason.LEFT || reason == CloseReason.ENDED || reason == CloseReason.DENIED) {
            riderId = null
            rejoinKey = null
        }
        _status.value = RoomConnectionStatus.Closed(reason, code)
    }

    private fun shutdownSocket(code: Int?, reason: String) {
        generation++ // alle Rückrufe der alten Verbindung verwerfen
        pingTask?.cancel()
        retryTask?.cancel()
        pingTask = null
        retryTask = null
        val s = socket
        socket = null
        joined = false
        if (s != null && code != null) s.close(code, reason)
    }

    companion object {
        /** Schließcodes, nach denen ein neuer Versuch nichts bringt (docs/raum-protokoll.md). */
        fun permanentReason(code: Int?): CloseReason? = when (code) {
            4001 -> CloseReason.DENIED
            4004 -> CloseReason.ENDED
            4002 -> CloseReason.ROOM_FULL
            4003 -> CloseReason.VERSION
            4007 -> CloseReason.REPLACED
            4008 -> CloseReason.BLOCKED
            4009 -> CloseReason.LEFT
            4011, 1002, 1003, 1007, 1009 -> CloseReason.PROTOCOL
            else -> null
        }
    }
}
