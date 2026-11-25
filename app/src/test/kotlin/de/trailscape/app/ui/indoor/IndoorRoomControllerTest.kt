package de.trailscape.app.ui.indoor

import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.challenge.ChallengeRouteInfo
import de.trailscape.app.room.FakeRoomScheduler
import de.trailscape.app.room.FakeRoomSocket
import de.trailscape.app.room.FakeRoomTransport
import de.trailscape.app.room.ReconnectingRoomClient
import de.trailscape.app.room.TEST_HOST_SECRET
import de.trailscape.app.room.TEST_INVITE_LINK
import de.trailscape.app.room.TEST_ROOM_ID
import de.trailscape.app.room.TEST_ROOM_SECRET
import de.trailscape.app.room.testWelcome
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.core.SyncConfig
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.room.ClientMessage
import de.trailscape.core.room.RiderInfo
import de.trailscape.core.room.RiderStateEntry
import de.trailscape.core.room.RoomCreateResult
import de.trailscape.core.room.RoomInfo
import de.trailscape.core.room.RoomInvite
import de.trailscape.core.room.RoomRoute
import de.trailscape.core.room.ServerMessage
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Zustandslogik des Raums für Gruppenfahrten gegen einen Fake-Transport:
 * Anlegen, Beitreten, „Bereit", gemeinsamer Start, Abstände, Abbruch und
 * Wiederverbinden, Verlassen — und was dabei das Gerät verlässt (Datenschutz).
 * Der echte `ReconnectingRoomClient` läuft über die Attrappe; Uhr und Takt von Hand.
 */
class IndoorRoomControllerTest {

    private val transport = FakeRoomTransport()
    private val scheduler = FakeRoomScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val kv = MemoryKeyValueStore()
    private var sync: SyncConfig? = SyncConfig("https://sync.example.org", "token-geheim")
    private var me: RoomSelfState? = null
    private var starts = 0
    private var createResult: RoomCreateResult = created()
    private val createCalls = mutableListOf<Triple<String, String, RoomRoute>>()

    private fun created(): RoomCreateResult = RoomCreateResult.Created(
        room = RoomInfo(TEST_ROOM_ID, RoomRoute("testwelt", 3000.0), 6, 0, 10_000_000, null),
        invite = RoomInvite.of("https://sync.example.org", TEST_ROOM_ID, TEST_ROOM_SECRET)!!,
        hostSecret = TEST_HOST_SECRET,
        serverTime = 1_000_000L,
    )

    private val controller = IndoorRoomController(
        newClient = {
            ReconnectingRoomClient(transport = transport, scheduler = scheduler, clientClock = { scheduler.now }, random = Random(7))
        },
        createRoom = { url, token, route ->
            createCalls += Triple(url, token, route)
            createResult
        },
        syncConfig = { sync },
        routeInfo = { ref ->
            when (ref) {
                ChallengeRoute.TestWorld -> ChallengeRouteInfo(null, ChallengeAvailability.READY)
                is ChallengeRoute.Catalog -> ChallengeRouteInfo(null, ChallengeAvailability.DOWNLOAD)
            }
        },
        scope = scope,
        io = Dispatchers.Unconfined,
        kv = kv,
        clientClock = { scheduler.now },
        self = { me },
        onStart = { starts++ },
        autoTick = false,
    )

    @AfterTest
    fun cleanup() {
        controller.close()
        scope.cancel()
    }

    private val anna = RiderInfo("Rdr_anna", "Anna", host = true)
    private val cem = RiderInfo("Rdr_cem", "Cem", sharePower = false)

    /** Server-Uhr jetzt: `welcome.serverTime` kam zur Geräte-Zeit [joinedAt]. */
    private var joinedAt = 0L
    private fun serverNow(): Long = 1_000_000L + (scheduler.now - joinedAt)

    private fun joinAsGuest(others: List<RiderInfo> = listOf(anna), startAt: Long? = null): FakeRoomSocket {
        assertTrue(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        joinedAt = scheduler.now
        s.receive(testWelcome(others = others, startAt = startAt))
        return s
    }

    private fun open(): IndoorRoomUi.Open = assertIs<IndoorRoomUi.Open>(controller.ui.value)

    private fun state(id: String, distM: Double, speedMps: Double = 8.0, powerW: Int? = null, at: Long = serverNow()) =
        RiderStateEntry(id, at, distM, speedMps, powerW)

    // -----------------------------------------------------------------------
    // Ohne Server, ohne Netz
    // -----------------------------------------------------------------------

    @Test
    fun `ohne eingetragenen Server gibt es kein Anlegen und keine Verbindung`() {
        sync = null
        controller.refreshServer()
        assertFalse(controller.server.value.configured)
        controller.create(RoomRoute("testwelt", 3000.0), "Ben", sharePower = false)
        assertEquals(IndoorRoomUi.Closed(), controller.ui.value)
        assertTrue(createCalls.isEmpty())
        assertTrue(transport.sockets.isEmpty(), "nichts verbindet sich")
        // Auch der Takt tut ohne Raum nichts.
        me = RoomSelfState(10.0, 5.0, 200, held = false)
        controller.tick()
        assertTrue(transport.sockets.isEmpty())
        assertNull(controller.ride.value)
    }

    @Test
    fun `der Server zeigt nur den Rechnernamen, nie das Token`() {
        controller.refreshServer()
        assertEquals(RoomServerUi("sync.example.org"), controller.server.value)
        sync = SyncConfig("https://sync.example.org", "")
        controller.refreshServer()
        assertFalse(controller.server.value.configured, "ohne Token kann niemand anlegen")
    }

    @Test
    fun `kein Einladungslink, keine Verbindung`() {
        assertFalse(controller.join("https://example.org/irgendwas", "Ben", sharePower = false))
        assertFalse(controller.join("", "Ben", sharePower = false))
        // Ein Geheimnis in falscher Länge ist kein Link.
        assertFalse(controller.join(TEST_INVITE_LINK.dropLast(1), "Ben", sharePower = false))
        // Ohne Namen wird nicht beigetreten.
        assertFalse(controller.join(TEST_INVITE_LINK, "   ", sharePower = false))
        assertTrue(transport.sockets.isEmpty())
        assertEquals(IndoorRoomUi.Closed(), controller.ui.value)
    }

    @Test
    fun `ein Link mit Text drumherum wird erkannt`() {
        val invite = assertNotNull(IndoorRoomController.parseInvite("Komm dazu: $TEST_INVITE_LINK\nbis gleich"))
        assertEquals("https://sync.example.org", invite.serverUrl)
        assertEquals(TEST_ROOM_ID, invite.roomId)
        assertNull(IndoorRoomController.parseInvite("trailscape://strava-callback?code=1"))
    }

    // -----------------------------------------------------------------------
    // Beitreten
    // -----------------------------------------------------------------------

    @Test
    fun `Beitritt verbindet nur mit dem Server aus dem Link und sendet nur das Noetige`() {
        assertTrue(controller.join(TEST_INVITE_LINK, "  Ben\u0007  ", sharePower = false))
        assertEquals(IndoorRoomUi.Opening(creating = false, serverHost = "sync.example.org"), controller.ui.value)
        val s = transport.last
        assertEquals("wss://sync.example.org/api/rooms/ws", s.url)
        assertEquals(1, transport.sockets.size)
        s.listener.onOpen()
        val join = assertIs<ClientMessage.Join>(s.sentMessages().single())
        assertEquals("Ben", join.name)
        assertEquals(TEST_ROOM_SECRET, join.secret)
        assertFalse(join.sharePower)
        assertNull(join.avatar, "kein Aussehen")
        assertNull(join.rejoin)
        // Genau die Felder des Protokolls, nichts darueber hinaus.
        val raw = s.sent.single()
        for (key in listOf("device", "android", "model", "lat", "lon", "avatar", "token")) assertFalse(raw.contains(key), key)

        joinedAt = scheduler.now
        s.receive(testWelcome(others = listOf(anna)))
        val ui = open()
        assertFalse(ui.host)
        assertEquals("sync.example.org", ui.serverHost)
        assertEquals(RoomConnectionUi.Online, ui.connection)
        assertEquals(listOf("Ben", "Anna"), ui.riders.map { it.name })
        assertTrue(ui.riders[0].self)
        assertTrue(ui.riders[1].host)
        assertEquals(6, ui.maxRiders)
        assertEquals(ChallengeAvailability.READY, ui.route.availability)
        assertEquals("testwelt", ui.route.key)
        assertEquals(TEST_INVITE_LINK, ui.inviteLink)
        assertNull(ui.countdownS)
        assertFalse(ui.started)
        assertEquals("testwelt", controller.openRouteKey())
        // Name und Wahl sind gemerkt — sonst nichts vom Raum.
        assertEquals("Ben", controller.savedName())
        assertFalse(controller.savedSharePower())
        assertNull(kv.getString("indoor.room.invite"))
    }

    @Test
    fun `eine Strecke, die fehlt, wird als herunterladbar gezeigt`() {
        assertTrue(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        s.receive(testWelcome(route = RoomRoute("catalog:alpe-dhuez", 15_100.0, version = "3", name = "Alpe d'Huez")))
        val route = open().route
        assertEquals(ChallengeAvailability.DOWNLOAD, route.availability)
        assertEquals("alpe-dhuez", route.catalogId)
        assertEquals("Alpe d'Huez", route.fallbackName)
        assertEquals(15.1, route.lengthKm, 1e-9)
    }

    @Test
    fun `eine Strecke in unbekannter Form ist nicht fahrbar`() {
        assertTrue(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        s.receive(testWelcome(route = RoomRoute("ride:fremd", 1000.0)))
        assertEquals(ChallengeAvailability.UNKNOWN, open().route.availability)
    }

    // -----------------------------------------------------------------------
    // Anlegen
    // -----------------------------------------------------------------------

    @Test
    fun `Anlegen geht an den eigenen Server und tritt als Gastgeber bei`() {
        val route = assertNotNull(IndoorRoomController.roomRouteOf(ChallengeRoute.Catalog("alpe-dhuez", 3), 15_100.0, "Alpe d'Huez"))
        assertEquals("catalog:alpe-dhuez", route.id)
        assertEquals("3", route.version)
        controller.create(route, "Anna", sharePower = true)
        assertEquals(Triple("https://sync.example.org", "token-geheim", route), createCalls.single())
        val s = transport.last
        assertEquals("wss://sync.example.org/api/rooms/ws", s.url)
        s.listener.onOpen()
        val join = assertIs<ClientMessage.Join>(s.sentMessages().single())
        assertEquals(TEST_HOST_SECRET, join.secret, "Beitritt mit dem Gastgeber-Geheimnis")
        assertTrue(join.sharePower)
        // Das Sync-Token geht nie über den WebSocket.
        assertFalse(s.sent.single().contains("token-geheim"))
        s.receive(testWelcome(selfName = "Anna", host = true))
        val ui = open()
        assertTrue(ui.host)
        assertTrue(ui.sharePower)
        // Der Einladungslink trägt das Raum-Geheimnis, nie das des Gastgebers und nie das Token.
        assertTrue(ui.inviteLink.endsWith("#$TEST_ROOM_SECRET"))
        assertFalse(ui.inviteLink.contains(TEST_HOST_SECRET))
        assertFalse(ui.inviteLink.contains("token-geheim"))
    }

    @Test
    fun `Fehler beim Anlegen werden zu einem Satz und oeffnen keine Verbindung`() {
        val cases = listOf(
            RoomCreateResult.Unauthorized to RoomNotice.CREATE_UNAUTHORIZED,
            RoomCreateResult.Disabled to RoomNotice.CREATE_DISABLED,
            RoomCreateResult.ServerFull to RoomNotice.CREATE_SERVER_FULL,
            RoomCreateResult.Failed(0, "NETWORK") to RoomNotice.CREATE_NETWORK,
            RoomCreateResult.Failed(500, "HTTP_500") to RoomNotice.CREATE_FAILED,
        )
        for ((result, notice) in cases) {
            createResult = result
            controller.create(RoomRoute("testwelt", 3000.0), "Anna", sharePower = false)
            assertEquals(IndoorRoomUi.Closed(notice), controller.ui.value, "$result")
            controller.dismissNotice()
            assertEquals(IndoorRoomUi.Closed(), controller.ui.value)
        }
        assertTrue(transport.sockets.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Bereit, Start, Abstände
    // -----------------------------------------------------------------------

    @Test
    fun `bereit ist, wer am Start steht - man selbst und die anderen`() {
        val s = joinAsGuest(others = listOf(anna, cem))
        assertEquals(listOf(false, false, false), open().riders.map { it.atStart })

        // Anna steht am Start (ihr Gerät meldet die Stelle 0).
        s.receive(ServerMessage.Snapshot(serverNow(), listOf(state(anna.id, 0.0, 0.0))))
        assertEquals(listOf(false, true, false), open().riders.map { it.atStart })
        assertFalse(open().selfAtStart)

        // Ich fahre zum Start: Die Fahrt wird gehalten und meldet die Stelle 0.
        me = RoomSelfState(0.0, 0.0, null, held = true)
        controller.tick()
        assertEquals(listOf(true, true, false), open().riders.map { it.atStart })
        assertTrue(open().selfAtStart)
        val ride = assertNotNull(controller.ride.value)
        assertTrue(ride.waiting)
        assertEquals(2, ride.atStart)
        assertEquals(3, ride.total)
        assertFalse(ride.host)

        // Von Anna kommt nichts mehr: nach 5 s gilt sie nicht mehr als bereit.
        scheduler.advance(6_000)
        controller.tick()
        assertEquals(listOf(true, false, false), open().riders.map { it.atStart })
    }

    @Test
    fun `die eigene Stelle geht nur waehrend der Fahrt hinaus, Leistung nur wenn gezeigt`() {
        val s = joinAsGuest()
        val before = s.sent.size
        controller.tick()
        assertEquals(before, s.sent.size, "ohne Fahrt wird keine Stelle gesendet")

        me = RoomSelfState(120.456, 8.25, 231, held = false)
        controller.tick()
        val hidden = assertIs<ClientMessage.State>(s.sentMessages().last())
        assertEquals(120.46, hidden.distM, 1e-9)
        assertEquals(8.25, hidden.speedMps, 1e-9)
        assertNull(hidden.powerW, "Leistung zeigen ist aus")
        assertNull(hidden.cadenceRpm)
        assertNull(hidden.lane)
        assertFalse(s.sent.last().contains("powerW"))

        controller.setSharePower(true)
        assertEquals(ClientMessage.Settings(sharePower = true), s.sentMessages().last())
        assertTrue(open().sharePower)
        controller.tick()
        assertEquals(231.0, assertIs<ClientMessage.State>(s.sentMessages().last()).powerW)
        assertNull(assertIs<ClientMessage.State>(s.sentMessages().last()).cadenceRpm, "Trittfrequenz geht nie hinaus")
    }

    @Test
    fun `der Gastgeber startet, alle zaehlen herunter, die gehaltene Fahrt wird zum Start frei`() {
        assertTrue(controller.join(TEST_INVITE_LINK, "Anna", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        joinedAt = scheduler.now
        s.receive(testWelcome(selfName = "Anna", host = true, others = listOf(cem)))
        me = RoomSelfState(0.0, 0.0, null, held = true)
        controller.tick()
        assertTrue(assertNotNull(controller.ride.value).host)
        assertNull(open().countdownS)

        controller.startCountdown()
        assertEquals(ClientMessage.Start(10), s.sentMessages().last())
        val startAt = serverNow() + 10_000
        s.receive(ServerMessage.Countdown(startAt, serverNow()))
        assertEquals(10, open().countdownS)
        assertEquals(10, assertNotNull(controller.ride.value).countdownS)

        scheduler.advance(7_300)
        controller.tick()
        assertEquals(3, open().countdownS)
        assertEquals(0, starts, "vor dem Start bleibt die Fahrt gehalten")
        assertFalse(open().started)

        scheduler.advance(2_800)
        controller.tick()
        assertEquals(1, starts, "zum Start wird die Fahrt freigegeben")
        assertNull(open().countdownS)
        assertTrue(open().started)

        // Freigegeben: kein zweites Auslösen, die Karte „Warten" verschwindet.
        me = RoomSelfState(4.0, 3.0, null, held = false)
        controller.tick()
        assertEquals(1, starts)
        assertFalse(assertNotNull(controller.ride.value).waiting)
        assertTrue(open().riders.first().riding)
    }

    @Test
    fun `ein Gast kann weder starten noch den Raum beenden`() {
        val s = joinAsGuest()
        val before = s.sent.size
        controller.startCountdown()
        controller.endRoom()
        assertEquals(before, s.sent.size)
    }

    @Test
    fun `wer nach dem Start kommt, faehrt sofort los`() {
        joinAsGuest(startAt = 990_000L)
        assertTrue(open().started)
        me = RoomSelfState(0.0, 0.0, null, held = true)
        controller.tick()
        assertEquals(1, starts)
    }

    @Test
    fun `waehrend der Fahrt stehen die anderen mit Abstand da, der naechste zuerst`() {
        val dana = RiderInfo("Rdr_dana", "Dana")
        val emil = RiderInfo("Rdr_emil", "Emil")
        val s = joinAsGuest(others = listOf(anna, cem, dana, emil), startAt = 990_000L)
        me = RoomSelfState(1_000.0, 8.0, 200, held = false)
        s.receive(
            ServerMessage.Snapshot(
                serverNow(),
                listOf(
                    state(anna.id, 1_040.0, speedMps = 0.0, powerW = 250),
                    state(cem.id, 988.0, speedMps = 0.0, powerW = 180),
                    state(dana.id, 1_500.0, speedMps = 0.0),
                    state(emil.id, 700.0, speedMps = 0.0),
                ),
            ),
        )
        controller.tick()
        val ride = assertNotNull(controller.ride.value)
        assertFalse(ride.waiting)
        assertEquals(RoomRideUi.MAX_SHOWN, ride.riders.size)
        assertEquals(listOf("Cem", "Anna", "Emil"), ride.riders.map { it.name })
        assertEquals(-12.0, ride.riders[0].gapM, 1e-6)
        assertEquals(40.0, ride.riders[1].gapM, 1e-6)
        assertEquals(250, ride.riders[1].powerW)
        assertNull(ride.riders[0].powerW, "Cem zeigt seine Leistung nicht")
        assertTrue(ride.riders.none { it.stale })
        // Liste im Raum: Anna fährt, mit gefahrener Strecke.
        val annaRow = open().riders.first { it.name == "Anna" }
        assertTrue(annaRow.riding)
        assertEquals(1.04, assertNotNull(annaRow.distKm), 1e-6)

        // Nach 3 s ohne Meldung sind sie ausgegraut, die Stelle bleibt stehen.
        scheduler.advance(3_500)
        controller.tick()
        assertTrue(assertNotNull(controller.ride.value).riders.all { it.stale })
    }

    @Test
    fun `nach dem Ende der Fahrt verschwindet die Einblendung, der Raum bleibt offen`() {
        joinAsGuest(startAt = 990_000L)
        me = RoomSelfState(50.0, 5.0, null, held = false)
        controller.tick()
        assertNotNull(controller.ride.value)
        me = null
        controller.onRideEnded()
        assertNull(controller.ride.value)
        assertFalse(open().selfAtStart)
    }

    // -----------------------------------------------------------------------
    // Abbruch, Wiederverbinden, Ende
    // -----------------------------------------------------------------------

    @Test
    fun `Verbindungsabbruch ist sichtbar und heilt von selbst mit demselben Platz`() {
        val s = joinAsGuest()
        me = RoomSelfState(100.0, 8.0, null, held = false)
        s.drop()
        val lost = assertIs<RoomConnectionUi.Reconnecting>(open().connection)
        assertEquals(1, lost.attempt)
        assertEquals(1, lost.retryInS)
        // Während des Abbruchs geht nichts hinaus, und die Fahrt zeigt den Zustand.
        controller.tick()
        assertIs<RoomConnectionUi.Reconnecting>(assertNotNull(controller.ride.value).connection)
        assertEquals(1, transport.sockets.size)

        scheduler.advance(1_000)
        val s2 = transport.last
        assertEquals(2, transport.sockets.size)
        assertEquals(RoomConnectionUi.Reconnecting(1, null), open().connection)
        s2.listener.onOpen()
        val rejoin = assertNotNull(assertIs<ClientMessage.Join>(s2.sentMessages().single()).rejoin)
        assertEquals("Rdr_self", rejoin.id)
        s2.receive(testWelcome(others = listOf(anna), rejoinKey = "key-2"))
        assertEquals(RoomConnectionUi.Online, open().connection)
        controller.tick()
        assertIs<ClientMessage.State>(s2.sentMessages().last())
    }

    @Test
    fun `eine geaenderte Wahl zur Leistung gilt auch nach dem Wiederverbinden`() {
        val s = joinAsGuest()
        s.drop()
        // Während des Abbruchs umgeschaltet: geht noch nicht hinaus.
        controller.setSharePower(true)
        scheduler.advance(1_000)
        val s2 = transport.last
        s2.listener.onOpen()
        // Der Client tritt mit den Angaben des ersten Beitritts wieder bei …
        assertFalse(assertIs<ClientMessage.Join>(s2.sentMessages().single()).sharePower)
        s2.receive(testWelcome(others = listOf(anna), rejoinKey = "key-2"))
        // … die neue Wahl wird gleich nachgereicht.
        assertTrue(ClientMessage.Settings(sharePower = true) in s2.sentMessages())
    }

    @Test
    fun `der Gastgeber beendet den Raum - alle erfahren warum, wer wartet, haengt nicht fest`() {
        val s = joinAsGuest()
        me = RoomSelfState(0.0, 0.0, null, held = true)
        controller.tick()
        s.receive(ServerMessage.Ended("host", serverNow()))
        assertEquals(IndoorRoomUi.Closed(RoomNotice.ENDED_BY_HOST), controller.ui.value)
        assertEquals(1, starts, "die gehaltene Fahrt wird freigegeben")
        assertTrue(assertNotNull(controller.ride.value).closed, "in der Fahrt steht, dass der Raum zu Ende ist")
        assertNull(controller.openRouteKey())
        // Danach geht nichts mehr hinaus und nichts verbindet sich neu.
        val sent = s.sent.size
        me = RoomSelfState(10.0, 5.0, null, held = false)
        controller.tick()
        scheduler.advance(60_000)
        assertEquals(sent, s.sent.size)
        assertEquals(1, transport.sockets.size)
    }

    @Test
    fun `Gruende fuer das Ende werden unterschieden`() {
        for ((reason, notice) in listOf(
            "expired" to RoomNotice.ENDED_EXPIRED,
            "idle" to RoomNotice.ENDED_IDLE,
            "server" to RoomNotice.ENDED_SERVER,
            "etwas-neues" to RoomNotice.ENDED_SERVER,
        )) {
            val s = joinAsGuest()
            s.receive(ServerMessage.Ended(reason, null))
            assertEquals(IndoorRoomUi.Closed(notice), controller.ui.value, reason)
            controller.dismissNotice()
        }
    }

    @Test
    fun `beendet der Gastgeber selbst, braucht er keinen Hinweis`() {
        assertTrue(controller.join(TEST_INVITE_LINK, "Anna", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        s.receive(testWelcome(selfName = "Anna", host = true))
        controller.endRoom()
        assertEquals(ClientMessage.End, s.sentMessages().last())
        s.receive(ServerMessage.Ended("host", null))
        assertEquals(IndoorRoomUi.Closed(), controller.ui.value)
    }

    @Test
    fun `Schliessgruende des Servers werden zu Saetzen`() {
        val beforeWelcome = listOf(
            4001 to RoomNotice.DENIED,
            4002 to RoomNotice.FULL,
            4003 to RoomNotice.VERSION,
            4008 to RoomNotice.BLOCKED,
            4011 to RoomNotice.PROTOCOL,
        )
        for ((code, notice) in beforeWelcome) {
            assertTrue(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
            val s = transport.last
            s.listener.onOpen()
            s.closeFromServer(code)
            assertEquals(IndoorRoomUi.Closed(notice), controller.ui.value, "$code")
            controller.dismissNotice()
        }
        // Im Raum: derselbe Fahrer über ein zweites Gerät.
        joinAsGuest().closeFromServer(4007)
        assertEquals(IndoorRoomUi.Closed(RoomNotice.REPLACED), controller.ui.value)
        controller.dismissNotice()
        // Nach einem Neustart kennt der Server den Raum nicht mehr: „beendet", nicht „falscher Link".
        val s = joinAsGuest()
        s.drop()
        scheduler.advance(1_000)
        transport.last.listener.onOpen()
        transport.last.closeFromServer(4001)
        assertEquals(IndoorRoomUi.Closed(RoomNotice.ENDED_SERVER), controller.ui.value)
    }

    @Test
    fun `Verlassen gibt den Platz frei und trennt die Verbindung`() {
        val s = joinAsGuest()
        me = RoomSelfState(0.0, 0.0, null, held = true)
        controller.tick()
        controller.leave()
        assertTrue(ClientMessage.Leave in s.sentMessages())
        assertEquals(1000, s.closedWith)
        assertEquals(IndoorRoomUi.Closed(), controller.ui.value)
        assertEquals(1, starts, "wer am Start wartete, fährt allein weiter")
        assertTrue(assertNotNull(controller.ride.value).closed)
        controller.onRideEnded()
        assertNull(controller.ride.value)
        // Kein neuer Versuch, auch nicht später.
        scheduler.advance(120_000)
        assertEquals(1, transport.sockets.size)
        // Ein neuer Raum ist danach wieder möglich.
        assertTrue(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
        assertEquals(2, transport.sockets.size)
    }

    @Test
    fun `der Beitritt laesst sich abbrechen`() {
        assertTrue(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
        assertIs<IndoorRoomUi.Opening>(controller.ui.value)
        controller.leave()
        assertEquals(IndoorRoomUi.Closed(), controller.ui.value)
        assertFalse(transport.last.open)
        scheduler.advance(120_000)
        assertEquals(1, transport.sockets.size)
    }

    @Test
    fun `in einem offenen Raum wird kein zweiter geoeffnet`() {
        joinAsGuest()
        assertFalse(controller.join(TEST_INVITE_LINK, "Ben", sharePower = false))
        controller.create(RoomRoute("testwelt", 3000.0), "Ben", sharePower = false)
        assertEquals(1, transport.sockets.size)
        assertTrue(createCalls.isEmpty())
    }

    @Test
    fun `Namen werden bereinigt und gekuerzt`() {
        assertEquals("Ben Müller", IndoorRoomController.cleanName("  Ben \t\n Müller\u0000 "))
        assertEquals(32, IndoorRoomController.cleanName("x".repeat(80)).length)
        assertEquals("", IndoorRoomController.cleanName(" \n "))
    }
}
