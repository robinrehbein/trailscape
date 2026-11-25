package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.background.IndoorRideHost
import de.trailscape.app.indoor.background.IndoorRideJournal
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
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
 * Indoor-Fahrt im Hintergrund (docs/indoor/hintergrund.md) im ViewModel:
 * Die Fahrt ueberlebt das Aufraeumen des Bildschirms, ein neues ViewModel
 * uebernimmt sie samt Darstellung, und der Schalter stellt das alte
 * Verhalten wieder her.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorBackgroundFlowTest {

    private val tmp: File = createTempDirectory("indoor-bg-flow").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val host = IndoorRideHost()
    private val journal = IndoorRideJournal(File(tmp, "files/indoor/" + IndoorRideJournal.FILE_NAME))
    private val store = MemoryKeyValueStore()
    private val viewModels = mutableListOf<IndoorViewModel>()
    private var serviceStarts = 0
    private var serviceWorks = true

    @After
    fun cleanup() {
        viewModels.forEach { it.release() }
        host.ride.value?.runtime?.close()
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun viewModel(parts: FakeIndoorParts) = IndoorViewModel(
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
        rideHost = host,
        journal = journal,
        startBackgroundService = {
            serviceStarts++
            serviceWorks
        },
    ).also { viewModels += it }

    private fun IndoorViewModel.startDemoRide(withWorld: Boolean) {
        openPair()
        setDemoTrainer(true)
        continueFromPair()
        selectRoute(IndoorRouteOption.TestWorld)
        continueFromRoute()
        startRide(withWorld = withWorld)
        assertEquals(IndoorStage.RIDE, stage.value)
        releaseStart()
    }

    private fun ride(seconds: Int) {
        val runtime = assertNotNull(host.ride.value).runtime
        repeat(seconds * 10) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }

    @Test
    fun vorgabeIstAnUndDerSchalterWirdGemerkt() {
        val vm = viewModel(FakeIndoorParts())
        assertTrue(vm.backgroundRide.value, "Vorgabe: im Hintergrund weiterfahren")
        vm.setBackgroundRide(false)
        assertEquals("false", store.getString(IndoorViewModel.KEY_BACKGROUND_RIDE))
        assertFalse(viewModel(FakeIndoorParts()).backgroundRide.value)
    }

    @Test
    fun appImHintergrundPausiertNichtUndDieFahrtUeberlebtDenBildschirm() {
        val parts = FakeIndoorParts()
        val first = viewModel(parts)
        first.startDemoRide(withWorld = true)
        assertEquals(1, serviceStarts, "der Vordergrunddienst startet mit der Fahrt")
        val runtime = assertNotNull(first.activeRuntime)
        assertSame(runtime, host.ride.value?.runtime)
        ride(30)

        first.onAppBackground()
        ride(30)
        assertEquals(IndoorPhase.RUNNING, runtime.state.value.session?.phase, "im Hintergrund laeuft die Fahrt weiter")
        assertTrue(journal.exists(), "die Fahrt wird gesichert")

        // Die Activity geht (aus den Letzten Apps gewischt): Bildschirm weg, Fahrt bleibt.
        val firstRenderer = parts.renderers.single() as FakeWorldRenderer
        first.release()
        assertTrue(firstRenderer.destroyed, "die Darstellung geht mit der Oberflaeche")
        assertFalse(runtime.state.value.finished, "die Fahrt laeuft im Dienst weiter")
        assertTrue(host.isRiding)
        val distBefore = assertNotNull(runtime.state.value.session).distM
        ride(60)
        assertTrue(assertNotNull(runtime.state.value.session).distM > distBefore)

        // Zurueck in die App: ein neues ViewModel uebernimmt die Fahrt an der richtigen Stelle.
        val second = viewModel(parts)
        assertEquals(IndoorStage.RIDE, second.stage.value)
        assertSame(runtime, second.activeRuntime)
        val secondRenderer = parts.renderers.last() as FakeWorldRenderer
        assertTrue(secondRenderer !== firstRenderer, "eine neue Darstellung")
        assertSame(secondRenderer, second.renderer.value)
        ride(1)
        val shown = assertNotNull(second.ride.value?.session)
        assertEquals(shown.distM, secondRenderer.states.last().rider.distM, 1e-9)
        assertEquals("Teststrecke", second.ride.value?.routeName)

        // Beenden und Speichern wie immer; danach ist die Sicherung weg und der Dienst frei.
        second.finishRide()
        assertEquals(IndoorStage.SUMMARY, second.stage.value)
        assertFalse(host.isRiding)
        assertTrue(journal.exists(), "bis zum Speichern bleibt die Sicherung")
        second.saveRide()
        assertNotNull(second.summary.value?.savedRideId)
        assertFalse(journal.exists())
    }

    @Test
    fun beendenAusDerBenachrichtigungOhneOberflaecheSpeichertDieFahrt() {
        val parts = FakeIndoorParts()
        val vm = viewModel(parts)
        vm.startDemoRide(withWorld = false)
        ride(90)
        vm.release()
        assertTrue(host.isRiding)

        val outcome = kotlinx.coroutines.runBlocking { host.finishFromNotification() }

        val ok = outcome as IndoorRideHost.Outcome.Saved
        val saved = assertNotNull(rideStorage.loadRide(ok.rideId))
        assertTrue(saved.indoor)
        assertEquals("Indoor · Teststrecke", saved.name)
        assertFalse(journal.exists())
        // Ein neues ViewModel beginnt wieder auf der Startseite.
        assertEquals(IndoorStage.ROUTE, viewModel(parts).stage.value)
    }

    @Test
    fun schalterAusVerhaeltSichWieFrueher() {
        val parts = FakeIndoorParts()
        val vm = viewModel(parts)
        vm.setBackgroundRide(false)
        vm.startDemoRide(withWorld = false)
        assertEquals(0, serviceStarts, "ohne Schalter kein Vordergrunddienst")
        assertNull(host.ride.value)
        ride0(vm, 20)
        vm.onAppBackground()
        ride0(vm, 1)
        assertEquals(IndoorPhase.PAUSED, vm.activeRuntime?.state?.value?.session?.phase, "Verlassen der App pausiert")
        vm.onAppForeground()
        ride0(vm, 1)
        assertEquals(IndoorPhase.RUNNING, vm.activeRuntime?.state?.value?.session?.phase)
        val runtime = assertNotNull(vm.activeRuntime)
        vm.release()
        assertTrue(runtime.state.value.finished, "der Bildschirm geht, die Fahrt endet")
    }

    @Test
    fun scheitertDerDienstPausiertDasVerlassenDieFahrtWieFrueher() {
        serviceWorks = false
        val vm = viewModel(FakeIndoorParts())
        vm.startDemoRide(withWorld = false)
        assertEquals(1, serviceStarts)
        ride0(vm, 20)
        vm.onAppBackground()
        ride0(vm, 1)
        assertEquals(IndoorPhase.PAUSED, vm.activeRuntime?.state?.value?.session?.phase)
        val runtime = assertNotNull(vm.activeRuntime)
        vm.release()
        assertTrue(runtime.state.value.finished)
        assertFalse(host.isRiding)
        assertTrue(journal.exists(), "eine ungeplant beendete Fahrt bleibt fuer die Wiederherstellung gesichert")
    }

    private fun ride0(vm: IndoorViewModel, seconds: Int) {
        val runtime = assertNotNull(vm.activeRuntime)
        repeat(seconds * 10) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }
}
