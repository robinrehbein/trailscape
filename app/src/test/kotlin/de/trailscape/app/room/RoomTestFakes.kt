package de.trailscape.app.room

import de.trailscape.core.room.ClientMessage
import de.trailscape.core.room.RiderInfo
import de.trailscape.core.room.RoomCodec
import de.trailscape.core.room.RoomInfo
import de.trailscape.core.room.RoomLimits
import de.trailscape.core.room.RoomRoute
import de.trailscape.core.room.ServerMessage

/**
 * Attrappen für Tests der Raum-Oberfläche: ein WebSocket, der nichts sendet,
 * und ein Zeitgeber, der von Hand weitergedreht wird. Der echte
 * [ReconnectingRoomClient] läuft darüber — so prüfen die Tests Zustandslogik
 * und Client zusammen, ohne Netz.
 */
internal class FakeRoomSocket(val url: String, val listener: RoomSocketListener) : RoomSocket {
    val sent = mutableListOf<String>()
    var closedWith: Int? = null
    var open = true

    override fun send(text: String): Boolean {
        if (!open) return false
        sent += text
        return true
    }

    override fun close(code: Int, reason: String) {
        closedWith = code
        open = false
    }

    override fun cancel() {
        open = false
    }

    fun sentMessages(): List<ClientMessage> = sent.mapNotNull { RoomCodec.decodeClient(it) }

    fun receive(message: ServerMessage) = listener.onMessage(RoomCodec.encode(message))

    /** Die Verbindung reißt ab (Funkloch). */
    fun drop() {
        open = false
        listener.onFailure(java.io.IOException("weg"))
    }

    /** Der Server schließt mit [code]. */
    fun closeFromServer(code: Int) {
        open = false
        listener.onClosed(code, "")
    }
}

internal class FakeRoomTransport : RoomTransport {
    val sockets = mutableListOf<FakeRoomSocket>()

    override fun open(url: String, listener: RoomSocketListener): RoomSocket =
        FakeRoomSocket(url, listener).also { sockets += it }

    val last: FakeRoomSocket get() = sockets.last()
}

internal class FakeRoomScheduler : RoomScheduler {
    var now = 50_000L
    private data class Job(val at: Long, val task: () -> Unit, var cancelled: Boolean = false)
    private val jobs = mutableListOf<Job>()

    override fun schedule(delayMs: Long, task: () -> Unit): RoomScheduler.Cancellable {
        val job = Job(now + delayMs, task)
        jobs += job
        return RoomScheduler.Cancellable { job.cancelled = true }
    }

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = jobs.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
            jobs.remove(next)
            now = next.at
            next.task()
        }
        now = until
    }
}

internal const val TEST_ROOM_ID = "AbCdEfGhIjKlMnOp"
internal const val TEST_ROOM_SECRET = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x"
internal const val TEST_HOST_SECRET = "h0st-h0st-h0st-h0st-h0st-h0st-h0st-h0st-h0s"
internal const val TEST_INVITE_LINK =
    "trailscape://room?server=https%3A%2F%2Fsync.example.org&room=$TEST_ROOM_ID#$TEST_ROOM_SECRET"

/** Ein `welcome` für die Teststrecke; [others] sind schon im Raum. */
internal fun testWelcome(
    selfId: String = "Rdr_self",
    selfName: String = "Ben",
    host: Boolean = false,
    serverTime: Long = 1_000_000L,
    startAt: Long? = null,
    route: RoomRoute = RoomRoute("testwelt", 3000.0, name = "Teststrecke"),
    others: List<RiderInfo> = emptyList(),
    rejoinKey: String = "key-1",
) = ServerMessage.Welcome(
    v = 1,
    serverTime = serverTime,
    you = ServerMessage.You(selfId, rejoinKey, host = host),
    room = RoomInfo(TEST_ROOM_ID, route, 6, 0, 10_000_000, startAt),
    riders = listOf(RiderInfo(selfId, selfName, host = host)) + others,
    states = emptyList(),
    limits = RoomLimits(),
)
