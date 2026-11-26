package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.room.FakeRoomScheduler
import de.trailscape.app.room.FakeRoomSocket
import de.trailscape.app.room.FakeRoomTransport
import de.trailscape.app.room.ReconnectingRoomClient
import de.trailscape.app.room.TEST_INVITE_LINK
import de.trailscape.app.room.testWelcome
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.SyncConfig
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.createIndoorSession
import de.trailscape.core.room.ClientMessage
import de.trailscape.core.room.RiderInfo
import de.trailscape.core.room.RiderStateEntry
import de.trailscape.core.room.RoomCreateResult
import de.trailscape.core.room.ServerMessage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Der Raum für Gruppenfahrten im ganzen Indoor-Ablauf (docs/raum-protokoll.md,
 * docs/ux/umbau.md): beitreten, „Zum Start", gehalten bis zum gemeinsamen
 * Start, fahren mit Abständen, Ende. Echte Sitzung aus `:core`, Demo-Trainer,
 * Teststrecke; WebSocket und Zeitgeber sind Attrappen, Takte von Hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorRoomFlowTest {

    private val tmp: File = createTempDirectory("indoor-raum").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MemoryKeyValueStore()
    private val parts = FakeIndoorParts(sessionFactory = { createIndoorSession(it) })
    private val transport = FakeRoomTransport()
    private val scheduler = FakeRoomScheduler()
    private var sync: SyncConfig? = null
    private var vm: IndoorViewModel? = null

    @After
    fun cleanup() {
        vm?.release()
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun viewModel(withRoom: Boolean = true) = IndoorViewModel(
        parts = parts,
        rideStorage = rideStorage,
        worlds = worlds,
        store = store,
        texts = ContextIndoorTexts(
            context = { ApplicationProvider.getApplicationContext<Application>() },
            language = { AppLanguage.DE },
        ),
        profile = { TrainingProfile(ageYears = 40, weightKg = 75.0) },
        plan = { null },
        coreTexts = { CoreTextsDe },
        io = Dispatchers.Unconfined,
        clock = clock,
        wallClock = { 1_765_000_000_000L },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
        roomDeps = if (withRoom) {
            IndoorRoomDeps(
                newClient = { c -> ReconnectingRoomClient(transport = transport, scheduler = scheduler, clientClock = c, random = Random(3)) },
                createRoom = { _, _, _ -> RoomCreateResult.Disabled },
                syncConfig = { sync },
                autoTick = false,
            )
        } else {
            null
        },
    ).also { vm = it }

    private val anna = RiderInfo("Rdr_anna", "Anna", host = true)

    /** Geräte-Uhr beim `welcome`: Server-Uhr 1 000 000 gehört dazu. */
    private var joinedAt = 0L
    private fun serverNow(): Long = 1_000_000L + (clock() - joinedAt)

    private fun IndoorViewModel.enterRoom(startAt: Long? = null): FakeRoomSocket {
        assertTrue(joinRoom(TEST_INVITE_LINK, "Ben", sharePower = false))
        val s = transport.last
        s.listener.onOpen()
        joinedAt = clock()
        s.receive(testWelcome(others = listOf(anna), startAt = startAt))
        assertIs<IndoorRoomUi.Open>(room.value)
        return s
    }

    /** Ein Takt der Fahrt (100 ms) und, alle drei, einer des Raums. */
    private fun IndoorViewModel.tick(times: Int = 1) {
        val runtime = assertNotNull(activeRuntime, "Fahrt laeuft")
        repeat(times) { i ->
            clock.advance(100)
            runtime.tickOnce()
            if (i % 3 == 0) tickRoom()
        }
    }

    private fun states(s: FakeRoomSocket): List<ClientMessage.State> = s.sentMessages().filterIsInstance<ClientMessage.State>()

    @Test
    fun ohneRaumAbhaengigkeitenBleibtAllesWieBisher() {
        val vm = viewModel(withRoom = false)
        assertEquals(IndoorRoomUi.Closed(), vm.room.value)
        assertFalse(vm.roomServer.value.configured)
        assertFalse(vm.joinRoom(TEST_INVITE_LINK, "Ben", sharePower = false))
        vm.createRoom("Ben", sharePower = false)
        assertEquals(IndoorRoomUi.Closed(), vm.room.value)
        assertTrue(transport.sockets.isEmpty())
        // Eine Fahrt allein läuft wie immer: Startpunkt-Karte, „Losfahren", Bewegung.
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        vm.tick()
        assertNull(assertNotNull(vm.ride.value).room)
        vm.releaseStart()
        vm.tick(50)
        assertTrue(assertNotNull(vm.ride.value?.session).totals.distanceM > 0.0)
    }

    @Test
    fun ohneServerUndOhneRaumVerbindetSichNichts() {
        val vm = viewModel()
        assertFalse(vm.roomServer.value.configured)
        vm.selectRoute(IndoorRouteOption.TestWorld)
        assertNotNull(vm.roomRouteOfSelection(), "die Teststrecke taugt für einen Raum")
        vm.createRoom("Ben", sharePower = false)
        assertEquals(IndoorRoomUi.Closed(), vm.room.value)
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        vm.tick()
        vm.releaseStart()
        vm.tick(30)
        assertTrue(transport.sockets.isEmpty(), "eine Fahrt ohne Raum öffnet keine Verbindung")
        assertNull(assertNotNull(vm.ride.value).room)
    }

    @Test
    fun eigeneTourenTaugenNichtFuerEinenRaum() {
        val vm = viewModel()
        vm.selectRoute(IndoorRouteOption.Saved("r1", "Hausrunde", 10.0, 100.0, 1L, planned = false))
        assertNull(vm.roomRouteOfSelection())
        vm.selectRoute(IndoorRouteOption.TestWorld)
        val route = assertNotNull(vm.roomRouteOfSelection())
        assertEquals("testwelt", route.id)
        assertNull(route.version)
    }

    @Test
    fun zumStartHaeltDieFahrtBisZumGemeinsamenStartUndZeigtDieAnderenMitAbstand() {
        val vm = viewModel()
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        // Vorher war etwas anderes gewählt: Im Raum gilt freie Fahrt ohne Mitfahrer.
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.setGhost(IndoorGhostOption.ConstantPower(200))
        val s = vm.enterRoom()

        assertTrue(vm.rideRoomRoute(""), "mit Trainer geht es weiter zur Fahrt")
        assertEquals(IndoorRouteOption.TestWorld, vm.route.value.selected)
        assertEquals(IndoorGhostOption.None, vm.route.value.ghost)
        assertEquals(IndoorMode.SIM, vm.route.value.mode)
        vm.startRide(withWorld = false)
        assertEquals(IndoorStage.RIDE, vm.stage.value)
        vm.tick(6)

        // Am Start: keine Startpunkt-Karte, die Fahrt wartet, die Stelle 0 geht hinaus.
        val waiting = assertNotNull(vm.ride.value)
        assertNull(waiting.start, "im Raum gibt es keine Startpunkt-Wahl")
        assertNull(waiting.session?.ghost)
        val room = assertNotNull(waiting.room)
        assertTrue(room.waiting)
        assertEquals(1, room.atStart)
        assertEquals(2, room.total)
        assertEquals(0.0, states(s).last().distM)
        assertTrue(assertIs<IndoorRoomUi.Open>(vm.room.value).riders.first().atStart)

        // „Losfahren" und Treten lösen im Raum nichts aus.
        vm.releaseStart()
        vm.tick(20)
        assertEquals(0.0, assertNotNull(vm.ride.value?.session).totals.distanceM)
        assertTrue(assertNotNull(vm.ride.value?.room).waiting)

        // Der Gastgeber startet: Countdown, dann los — ohne weiteres Zutun.
        val startAt = serverNow() + 3_000
        s.receive(ServerMessage.Countdown(startAt, serverNow()))
        vm.tick(3)
        assertEquals(3, assertNotNull(vm.ride.value?.room).countdownS)
        vm.tick(100)
        val riding = assertNotNull(vm.ride.value)
        assertFalse(assertNotNull(riding.room).waiting)
        val dist = assertNotNull(riding.session).totals.distanceM
        assertTrue(dist > 1.0, "nach dem Start wird gefahren ($dist m)")
        val sent = states(s).last()
        assertTrue(sent.distM > 0.0)
        assertNull(sent.powerW, "Leistung zeigen ist aus")

        // Anna ist 25 m voraus.
        s.receive(ServerMessage.Snapshot(serverNow(), listOf(RiderStateEntry(anna.id, serverNow(), dist + 25.0, 0.0, 210))))
        vm.tick(1)
        val gap = assertNotNull(vm.ride.value?.room).riders.single()
        assertEquals("Anna", gap.name)
        assertTrue(gap.gapM > 15.0 && gap.gapM < 25.5, "Abstand ${gap.gapM}")
        assertEquals(210, gap.powerW)

        // Ende der Fahrt: die Einblendung ist weg, der Raum bleibt offen, nichts geht mehr hinaus.
        vm.finishRide()
        assertEquals(IndoorStage.SUMMARY, vm.stage.value)
        val after = states(s).size
        vm.tickRoom()
        vm.tickRoom()
        assertEquals(after, states(s).size)
        val open = assertIs<IndoorRoomUi.Open>(vm.room.value)
        assertFalse(open.selfAtStart)
        vm.startOver()
        assertIs<IndoorRoomUi.Open>(vm.room.value)
    }

    @Test
    fun endetDerRaumVorDemStartFaehrtManAlleinWeiter() {
        val vm = viewModel()
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        val s = vm.enterRoom()
        assertTrue(vm.rideRoomRoute(""))
        vm.startRide(withWorld = false)
        vm.tick(6)
        assertTrue(assertNotNull(vm.ride.value?.room).waiting)

        s.receive(ServerMessage.Ended("host", null))
        assertEquals(IndoorRoomUi.Closed(RoomNotice.ENDED_BY_HOST), vm.room.value)
        vm.tick(40)
        val ride = assertNotNull(vm.ride.value)
        assertTrue(assertNotNull(ride.room).closed, "die Fahransicht sagt, dass der Raum zu Ende ist")
        assertTrue(assertNotNull(ride.session).totals.distanceM > 1.0, "niemand hängt am Start fest")
    }

    @Test
    fun ohneTrainerFuehrtZumStartNichtInDieFahrt() {
        val vm = viewModel()
        vm.enterRoom()
        assertFalse(vm.rideRoomRoute(""), "die Sicht „Strecken“ zeigt dann, dass der Trainer fehlt")
        assertEquals(IndoorStage.ROUTE, vm.stage.value)
        // Die Strecke des Raums ist trotzdem schon gewählt.
        assertEquals(IndoorRouteOption.TestWorld, vm.route.value.selected)
    }

    @Test
    fun nachDemVerlassenIstDieFahrtWiederAllein() {
        val vm = viewModel()
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        val s = vm.enterRoom()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.leaveRoom()
        assertEquals(IndoorRoomUi.Closed(), vm.room.value)
        assertTrue(ClientMessage.Leave in s.sentMessages())
        // Dieselbe Strecke, aber kein Raum mehr: eine Fahrt wie immer, „Losfahren" gilt wieder.
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        vm.tick()
        assertNull(assertNotNull(vm.ride.value).room)
        vm.releaseStart()
        vm.tick(50)
        assertTrue(assertNotNull(vm.ride.value?.session).totals.distanceM > 0.0)
        assertTrue(states(s).isEmpty(), "nach dem Verlassen geht keine Stelle mehr hinaus")
    }

    @Test
    fun derAblaufVerlaesstDenRaumBeimAbbau() {
        val vm = viewModel()
        val s = vm.enterRoom()
        vm.release()
        this.vm = null
        assertTrue(ClientMessage.Leave in s.sentMessages())
        assertFalse(s.open)
        assertEquals(IndoorRoomUi.Closed(), vm.room.value)
    }
}
