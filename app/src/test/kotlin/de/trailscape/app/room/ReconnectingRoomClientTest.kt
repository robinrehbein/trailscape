package de.trailscape.app.room

import de.trailscape.core.room.ClientMessage
import de.trailscape.core.room.RiderInfo
import de.trailscape.core.room.RoomCodec
import de.trailscape.core.room.RoomInfo
import de.trailscape.core.room.RoomInvite
import de.trailscape.core.room.RoomLimits
import de.trailscape.core.room.RoomRoute
import de.trailscape.core.room.ServerMessage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wiederverbinden, Beitritt, Ping und Abbruchgründe gegen eine Attrappe des WebSockets. */
class ReconnectingRoomClientTest {

    private class FakeSocket(val url: String, val listener: RoomSocketListener) : RoomSocket {
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
    }

    private class FakeTransport : RoomTransport {
        val sockets = mutableListOf<FakeSocket>()
        override fun open(url: String, listener: RoomSocketListener): RoomSocket =
            FakeSocket(url, listener).also { sockets += it }
        val last get() = sockets.last()
    }

    private class FakeScheduler : RoomScheduler {
        var now = 0L
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
        val pending: Int get() = jobs.count { !it.cancelled }
    }

    private val roomId = "AbCdEfGhIjKlMnOp"
    private val secret = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x"
    private val invite = RoomInvite.of("https://sync.example.org", roomId, secret)!!
    private val transport = FakeTransport()
    private val scheduler = FakeScheduler()
    private val received = mutableListOf<ServerMessage>()
    private val client = ReconnectingRoomClient(
        transport = transport,
        scheduler = scheduler,
        clientClock = { scheduler.now },
        random = Random(42),
    ).also { c -> c.setListener { received += it } }

    private fun welcome(id: String = "Rdr_0001", key: String = "key-1", serverTime: Long = 1_000_000L) = ServerMessage.Welcome(
        v = 1,
        serverTime = serverTime,
        you = ServerMessage.You(id, key, host = false),
        room = RoomInfo(roomId, RoomRoute("katalog/probe", 5000.0), 10, 0, 10_000_000, null),
        riders = listOf(RiderInfo(id, "Anna")),
        states = emptyList(),
        limits = RoomLimits(),
    )

    private fun connectAndJoin(): FakeSocket {
        client.connect(RoomJoinParams(invite, "Anna", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        s.receive(welcome())
        return s
    }

    @Test
    fun `verbindet ohne Geheimnis in der URL und tritt mit der ersten Nachricht bei`() {
        client.connect(RoomJoinParams(invite, "Anna", sharePower = false))
        assertEquals(RoomConnectionStatus.Connecting(1), client.status.value)
        val s = transport.last
        assertEquals("wss://sync.example.org/api/rooms/ws", s.url)
        assertFalse(s.url.contains(secret))
        s.listener.onOpen()
        val join = assertIs<ClientMessage.Join>(s.sentMessages().single())
        assertEquals(roomId, join.room)
        assertEquals(secret, join.secret)
        assertEquals("Anna", join.name)
        assertFalse(join.sharePower)
        assertNull(join.rejoin)
        s.receive(welcome())
        assertEquals(RoomConnectionStatus.Connected("Rdr_0001", false), client.status.value)
        assertIs<ServerMessage.Welcome>(received.single())
    }

    @Test
    fun `Gastgeber tritt mit dem Gastgeber-Geheimnis bei`() {
        client.connect(RoomJoinParams(invite, "Ben", hostSecret = "h0st"))
        transport.last.listener.onOpen()
        assertEquals("h0st", assertIs<ClientMessage.Join>(transport.last.sentMessages().single()).secret)
    }

    @Test
    fun `senden erst nach welcome, join nie von aussen`() {
        client.connect(RoomJoinParams(invite, "Anna"))
        val s = transport.last
        s.listener.onOpen()
        assertFalse(client.send(ClientMessage.State(1.0, 1.0)))
        s.receive(welcome())
        assertTrue(client.send(ClientMessage.State(1.0, 1.0)))
        assertFalse(client.send(ClientMessage.Join(roomId, secret, "x")))
        assertIs<ClientMessage.State>(s.sentMessages().last())
    }

    @Test
    fun `Ping sofort und dann alle 5 s, Uhrenabgleich aus dem Pong`() {
        val s = connectAndJoin()
        scheduler.advance(0)
        assertEquals(ClientMessage.Ping(0), s.sentMessages().last())
        scheduler.advance(5_000)
        assertEquals(ClientMessage.Ping(5_000), s.sentMessages().last())
        // Antwort nach 100 ms: Server-Uhr 1 005 050 → Versatz 1 000 000.
        scheduler.advance(100)
        s.receive(ServerMessage.Pong(c = 5_000, s = 1_005_050))
        assertEquals(1_010_000L, client.serverTimeMs(10_000))
        assertEquals(2, s.sentMessages().count { it is ClientMessage.Ping })
    }

    @Test
    fun `Abbruch - Backoff wachsend mit Zufall, Wiederbeitritt mit rejoin`() {
        val first = connectAndJoin()
        first.listener.onFailure(java.io.IOException("Funkloch"))
        val r1 = assertIs<RoomConnectionStatus.Reconnecting>(client.status.value)
        assertTrue(r1.inMs in 500..1_000, "erster Versuch nach 0,5–1 s: ${r1.inMs}")
        assertEquals(1, transport.sockets.size)
        scheduler.advance(r1.inMs)
        assertEquals(2, transport.sockets.size)
        // Zweiter Versuch scheitert gleich wieder (Server weg).
        transport.last.listener.onFailure(java.io.IOException("weg"))
        scheduler.advance(1_000)
        transport.last.listener.onFailure(java.io.IOException("weg"))
        val r3 = assertIs<RoomConnectionStatus.Reconnecting>(client.status.value)
        assertTrue(r3.inMs in 1_000..2_000, "dritter Versuch nach 1–2 s: ${r3.inMs}")
        scheduler.advance(r3.inMs)
        val back = transport.last
        back.listener.onOpen()
        val join = assertIs<ClientMessage.Join>(back.sentMessages().single())
        assertEquals("Rdr_0001", join.rejoin?.id)
        assertEquals("key-1", join.rejoin?.key)
        back.receive(welcome(key = "key-2"))
        assertIs<RoomConnectionStatus.Connected>(client.status.value)
        // Nächster Abbruch nutzt den neuen Schlüssel und fängt mit dem Backoff von vorn an.
        back.listener.onClosed(1006, "")
        val again = assertIs<RoomConnectionStatus.Reconnecting>(client.status.value)
        assertTrue(again.inMs <= 1_000)
        scheduler.advance(again.inMs)
        transport.last.listener.onOpen()
        assertEquals("key-2", assertIs<ClientMessage.Join>(transport.last.sentMessages().single()).rejoin?.key)
    }

    @Test
    fun `Backoff-Kurve`() {
        val r = Random(1)
        for (attempt in 1..20) {
            val d = reconnectDelayMs(attempt, r)
            val cap = minOf(1_000L shl (attempt - 1).coerceAtMost(16), 30_000L)
            assertTrue(d in cap / 2..cap, "Versuch $attempt: $d")
        }
        assertTrue(reconnectDelayMs(50, r) <= 30_000)
    }

    @Test
    fun `endgueltige Schliesscodes - kein neuer Versuch`() {
        val cases = mapOf(
            4001 to CloseReason.DENIED,
            4002 to CloseReason.ROOM_FULL,
            4003 to CloseReason.VERSION,
            4004 to CloseReason.ENDED,
            4007 to CloseReason.REPLACED,
            4008 to CloseReason.BLOCKED,
            4011 to CloseReason.PROTOCOL,
        )
        for ((code, reason) in cases) {
            val t = FakeTransport()
            val sch = FakeScheduler()
            val c = ReconnectingRoomClient(t, sch, clientClock = { sch.now }, random = Random(0))
            c.connect(RoomJoinParams(invite, "Anna"))
            t.last.listener.onOpen()
            t.last.listener.onClosed(code, "")
            assertEquals(RoomConnectionStatus.Closed(reason, code), c.status.value, "Code $code")
            sch.advance(60_000)
            assertEquals(1, t.sockets.size, "Code $code: kein zweiter Versuch")
        }
        // Vorübergehende Gründe (Rate, Zeitüberschreitung, Server voll, Abriss) → neuer Versuch.
        for (code in listOf(4005, 4006, 4010, 4012, 1001, 1006, 1011)) {
            assertNull(ReconnectingRoomClient.permanentReason(code), "Code $code")
        }
    }

    @Test
    fun `ended vom Server beendet ohne Wiederverbinden`() {
        val s = connectAndJoin()
        s.receive(ServerMessage.Ended("host", null))
        assertEquals(RoomConnectionStatus.Closed(CloseReason.ENDED, null), client.status.value)
        s.listener.onClosed(4004, "")
        scheduler.advance(60_000)
        assertEquals(1, transport.sockets.size)
        assertIs<ServerMessage.Ended>(received.last())
    }

    @Test
    fun `leave sendet leave und schliesst, close schliesst ohne leave`() {
        val s = connectAndJoin()
        client.leave()
        assertEquals(ClientMessage.Leave, s.sentMessages().last())
        assertEquals(1000, s.closedWith)
        assertEquals(RoomConnectionStatus.Closed(CloseReason.LEFT, null), client.status.value)

        val s2 = connectAndJoin()
        val before = s2.sent.size
        client.close()
        assertEquals(before, s2.sent.size)
        assertEquals(1000, s2.closedWith)
        assertEquals(RoomConnectionStatus.Closed(CloseReason.BY_USER, null), client.status.value)
        // Späte Rückrufe der alten Verbindung ändern nichts mehr.
        s2.listener.onClosed(1006, "")
        s2.receive(welcome())
        scheduler.advance(60_000)
        assertEquals(RoomConnectionStatus.Closed(CloseReason.BY_USER, null), client.status.value)
        assertEquals(0, scheduler.pending)
    }

    @Test
    fun `gibt nach der Hoechstzahl von Versuchen auf`() {
        val t = FakeTransport()
        val sch = FakeScheduler()
        val c = ReconnectingRoomClient(t, sch, clientClock = { sch.now }, random = Random(0), maxAttempts = 3)
        c.connect(RoomJoinParams(invite, "Anna"))
        repeat(3) {
            t.last.listener.onFailure(java.io.IOException("weg"))
            sch.advance(60_000)
        }
        assertEquals(RoomConnectionStatus.Closed(CloseReason.GAVE_UP, null), c.status.value)
        assertEquals(3, t.sockets.size)
    }

    @Test
    fun `kaputte Nachrichten werden still verworfen`() {
        val s = connectAndJoin()
        received.clear()
        s.listener.onMessage("{kaputt")
        s.listener.onMessage("""{"type":"emote"}""")
        assertEquals(listOf<ServerMessage>(ServerMessage.Unknown("emote")), received)
        assertIs<RoomConnectionStatus.Connected>(client.status.value)
    }

    @Test
    fun `neuer Raum vergisst die alte Kennung`() {
        connectAndJoin()
        val other = assertNotNull(RoomInvite.of("https://sync.example.org", "ZZZZZZZZZZZZZZZZ", secret))
        client.connect(RoomJoinParams(other, "Anna"))
        transport.last.listener.onOpen()
        assertNull(assertIs<ClientMessage.Join>(transport.last.sentMessages().single()).rejoin)
        assertEquals(1000, transport.sockets[0].closedWith, "alte Verbindung geschlossen")
    }

    @Test
    fun `toString der Beitrittsdaten ohne Geheimnisse`() {
        val p = RoomJoinParams(invite, "Anna", hostSecret = "h0st-geheim")
        assertFalse(p.toString().contains(secret))
        assertFalse(p.toString().contains("h0st-geheim"))
    }
}
