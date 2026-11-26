package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.testing.FakeWorldRenderer
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorResumePoint
import de.trailscape.core.indoor.createIndoorSession
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
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
 * Startpunkt waehlen und Fortsetzen im ganzen Ablauf (docs/indoor/app.md,
 * „Startpunkt und Fortsetzen"): echte Sitzung aus `:core`, Demo-Trainer-
 * Attrappe, Testwelt (Runde von 3 km), Fake-Uhr, Takt von Hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorStartFlowTest {

    private val tmp: File = createTempDirectory("indoor-start").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MemoryKeyValueStore()
    private val parts = FakeIndoorParts(sessionFactory = { createIndoorSession(it) })
    private val resumeKey = IndoorViewModel.KEY_RESUME_PREFIX + IndoorRouteOption.TestWorld.key
    private var vm: IndoorViewModel? = null

    @After
    fun cleanup() {
        vm?.release()
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun viewModel() = IndoorViewModel(
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
    ).also { vm = it }

    private fun IndoorViewModel.tick(times: Int = 1) {
        val runtime = assertNotNull(activeRuntime, "Fahrt laeuft")
        repeat(times) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }

    private fun IndoorViewModel.startTestWorld(withWorld: Boolean) {
        setDemoTrainer(true)
        continueFromPair()
        selectRoute(IndoorRouteOption.TestWorld)
        if (withWorld) {
            continueFromRoute()
        } else {
            prepareWorld(autoStart = false)
            assertTrue(world.value is IndoorWorldUi.Ready)
            startRide(withWorld = false)
        }
        assertEquals(IndoorStage.RIDE, stage.value)
    }

    private fun session() = assertNotNull(vm?.ride?.value?.session)

    @Test
    fun ohneGemerkteStelleAmAnfangVersetzenInDerPauseZaehltNicht() {
        val vm = viewModel()
        vm.startTestWorld(withWorld = false)
        vm.tick()
        val before = assertNotNull(vm.ride.value?.start, "vor dem ersten Tritt laesst sich der Start waehlen")
        assertEquals(0.0, before.selectedM)
        assertNull(before.resumeM)
        assertTrue(before.held, "auch ohne Angebot wartet die Fahrt auf Losfahren")
        vm.tick(30) // Demo „tritt", bewegt aber nichts
        assertEquals(IndoorPhase.READY, session().phase)

        vm.releaseStart()
        vm.tick(700) // 70 s Fahrt
        assertEquals(IndoorPhase.RUNNING, session().phase)
        assertNull(vm.ride.value?.start, "waehrend der Fahrt nicht")
        val ridden = session().totals.distanceM
        assertTrue(ridden > 100.0)

        vm.pauseRide()
        vm.tick()
        assertNotNull(vm.ride.value?.start, "in der Pause wieder")
        vm.moveStart(1_500.0)
        vm.tick()
        assertEquals(1_500.0, session().routeDistM, 1e-9)
        assertEquals(ridden, session().totals.distanceM, 1e-9)
        vm.nudgeStart(100.0)
        vm.tick()
        assertEquals(1_600.0, session().routeDistM, 1e-9)

        vm.finishRide()
        val summary = assertNotNull(vm.summary.value)
        assertEquals(ridden / 1000.0, summary.distanceKm, 1e-6)
        assertEquals(null, summary.startKm, "am Anfang gestartet")
        val saved = assertNotNull(IndoorResumePoint.fromJson(store.getString(resumeKey)))
        assertEquals(1_600.0, saved.routeDistM, 1e-9)
    }

    @Test
    fun gemerkteStelleIstVorauswahlUndWartetAufLosfahren() {
        store.setString(resumeKey, IndoorResumePoint(1_200.0, 3_000.0, 1L).toJson())
        val vm = viewModel()
        vm.startTestWorld(withWorld = true)
        vm.tick()
        val start = assertNotNull(vm.ride.value?.start)
        assertEquals(1_200.0, start.resumeM)
        assertEquals(1_200.0, start.selectedM)
        assertTrue(start.held)
        // Darstellung und Kacheln: schon der erste Zustand steht an der Startstelle.
        val renderer = parts.renderers.single() as FakeWorldRenderer
        assertEquals(1_200.0, renderer.states.first().rider.distM, 1e-9)

        vm.tick(50) // Demo tritt, aber „Losfahren" fehlt noch
        assertEquals(IndoorPhase.READY, session().phase)
        assertEquals(1_200.0, session().distM, 1e-9)

        vm.moveStart(0.0) // „Von vorn"
        vm.tick()
        assertEquals(0.0, session().distM)
        vm.moveStart(1_200.0) // zurueck zu „Weiter bei km 1,2"
        vm.tick()
        assertEquals(1_200.0, session().distM, 1e-9)

        vm.releaseStart()
        vm.tick(700)
        val s = session()
        assertEquals(IndoorPhase.RUNNING, s.phase)
        assertNull(vm.ride.value?.start)
        assertEquals(s.distM - 1_200.0, s.totals.distanceM, 1e-6)
        assertTrue(renderer.states.last().rider.distM > 1_200.0)

        vm.finishRide()
        val summary = assertNotNull(vm.summary.value)
        assertEquals(s.totals.distanceM / 1000.0, summary.distanceKm, 0.01)
        assertEquals(1.2, assertNotNull(summary.startKm), 0.01)
        vm.saveRide()
        val ride = assertNotNull(rideStorage.loadRide(assertNotNull(vm.summary.value?.savedRideId)))
        assertEquals(summary.distanceKm, ride.stats.distanceKm, 1e-9)
        assertEquals("Indoor · Teststrecke (ab km 1,2)", ride.name)
        val saved = assertNotNull(IndoorResumePoint.fromJson(store.getString(resumeKey)))
        assertTrue(saved.routeDistM > 1_200.0, "neue Stelle ${saved.routeDistM}")
    }

    @Test
    fun ueberDieZiellinieGefahrenLoeschtDieStelle() {
        store.setString(resumeKey, IndoorResumePoint(2_900.0, 3_000.0, 1L).toJson())
        val vm = viewModel()
        vm.startTestWorld(withWorld = false)
        vm.tick()
        vm.releaseStart()
        var guard = 0
        while (session().lap < 2 && guard++ < 5_000) vm.tick()
        assertEquals(2, session().lap)
        vm.finishRide() // kurz hinter der Ziellinie
        assertNull(store.getString(resumeKey))
    }

    @Test
    fun kaputteGemerkteStelleWirdIgnoriert() {
        store.setString(resumeKey, "{kaputt")
        val vm = viewModel()
        vm.startTestWorld(withWorld = false)
        vm.tick()
        val start = assertNotNull(vm.ride.value?.start)
        assertNull(start.resumeM)
        assertEquals(0.0, session().distM)
    }
}
