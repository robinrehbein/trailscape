package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.testing.FakeWorldRenderer
import de.trailscape.app.indoor.testing.testSavedRide
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.RenderFailure
import java.io.File
import kotlin.io.path.createTempDirectory
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
 * „Strecke vor der Tour ansehen" (Idee 4, docs/indoor/vorschau.md): der Weg
 * aus dem Tourdetail ueber „Strecke vorbereiten" in die Vorschau — ohne
 * Trainer, mit Attrappen fuer Weltbau und Darstellung, Fake-Uhr und Takt von
 * Hand (`autoLoop = false`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorPreviewFlowTest {

    private val tmp: File = createTempDirectory("indoor-preview").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val viewModels = mutableListOf<IndoorViewModel>()

    @After
    fun cleanup() {
        viewModels.forEach { it.release() }
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun viewModel(parts: IndoorParts) = IndoorViewModel(
        parts = parts,
        rideStorage = rideStorage,
        worlds = worlds,
        store = MemoryKeyValueStore(),
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
    ).also { viewModels += it }

    /** [seconds] simulierte Sekunden in 50-ms-Takten. */
    private fun IndoorViewModel.playFor(seconds: Int) {
        val preview = assertNotNull(activePreview, "Vorschau laeuft")
        repeat(seconds * 20) {
            clock.advance(50)
            preview.tickOnce()
        }
    }

    @Test
    fun ohneTrainerUeberVorbereitenInDieVorschauUndZurueck() {
        rideStorage.saveRide(testSavedRide())
        val parts = FakeIndoorParts()
        val vm = viewModel(parts)
        assertFalse(vm.pair.value.canContinue, "kein Trainer gekoppelt, kein Demo-Trainer")

        vm.openPreview("tour-1")
        assertTrue(vm.previewMode)
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        // Ohne vorhandene 3D-Ansicht: derselbe Voranschlag wie vor einer Fahrt.
        val plan = assertIs<IndoorWorldUi.Plan>(vm.world.value)
        assertTrue(plan.downloadBytes > 0)
        assertEquals(IndoorServerUi.Hidden, vm.server.value, "kein Server-Bau in der Vorschau")

        // „Vorbereiten" → Bau → bereit → direkt in die Vorschau.
        vm.loadWorld()
        assertEquals(IndoorStage.PREVIEW, vm.stage.value)
        val renderer = assertIs<FakeWorldRenderer>(assertNotNull(vm.renderer.value))
        assertNotNull(renderer.loadedDir, "dieselbe Darstellung laedt die vorbereitete Strecke")
        assertEquals(CameraMode.DRONE, renderer.camera)
        assertNull(vm.activeRuntime, "keine Fahrt, keine Sitzung")
        assertTrue(parts.trainers.isEmpty() && parts.sessions.isEmpty())

        var ui = assertNotNull(vm.preview.value)
        assertEquals(IndoorRenderUi.Ready, ui.render)
        assertEquals("Feierabendrunde", ui.routeName)
        assertTrue(ui.paceFromRide, "gefahrene Tour: 1× = ihr Schnitt")
        assertNotNull(ui.profile)
        assertFalse(ui.frame.playing)
        assertEquals(0.0, ui.frame.distM, 1e-9)

        // Wiedergabe: die Markierung faehrt, die Darstellung bekommt die Stelle.
        vm.previewTogglePlay()
        vm.playFor(2)
        ui = assertNotNull(vm.preview.value)
        assertTrue(ui.frame.playing)
        assertTrue(ui.frame.distM > 5.0, "nach 2 s bei 1× unterwegs: ${ui.frame.distM}")
        val last = renderer.states.last()
        assertEquals(ui.frame.distM, last.rider.distM, 1e-9)
        assertFalse(last.paused)

        // 2× ist doppelt so schnell.
        val before = ui.frame.distM
        vm.playFor(1)
        val oneX = assertNotNull(vm.preview.value).frame.distM - before
        vm.previewCycleSpeed()
        assertEquals(2, assertNotNull(vm.preview.value).frame.speedFactor)
        val before2 = assertNotNull(vm.preview.value).frame.distM
        vm.playFor(1)
        val twoX = assertNotNull(vm.preview.value).frame.distM - before2
        assertEquals(2.0, twoX / oneX, 0.15)

        // Springen ueber das Hoehenprofil, Kamera wechseln, anhalten.
        val length = ui.frame.lengthM
        vm.previewSeek(length / 2)
        assertEquals(length / 2, assertNotNull(vm.preview.value).frame.distM, 1e-6)
        assertEquals(length / 2, renderer.states.last().rider.distM, 1e-6)
        vm.previewSetCamera(CameraMode.CHASE)
        assertEquals(CameraMode.CHASE, renderer.camera)
        assertEquals(CameraMode.CHASE, assertNotNull(vm.preview.value).camera)
        vm.previewTogglePlay()
        assertFalse(assertNotNull(vm.preview.value).frame.playing)
        assertTrue(renderer.states.last().paused, "die Darstellung erfaehrt sofort, dass es steht")

        // Hintergrund haelt an.
        vm.previewTogglePlay()
        vm.onAppBackground()
        assertFalse(assertNotNull(vm.preview.value).frame.playing)

        // Zurueck schliesst die Vorschau und verlaesst den Ablauf; nichts wird gespeichert.
        assertFalse(vm.back())
        assertNull(vm.preview.value)
        assertNull(vm.renderer.value)
        assertTrue(renderer.destroyed)
        assertEquals(1, rideStorage.listSummaries().summaries.size)
    }

    @Test
    fun vorhandene3dAnsichtStartetDieVorschauSofort() {
        rideStorage.saveRide(testSavedRide())
        val vm = viewModel(FakeIndoorParts())
        vm.openPreview("tour-1")
        vm.loadWorld()
        assertEquals(IndoorStage.PREVIEW, vm.stage.value)
        vm.back()

        // Neuer Ablauf (wie nach erneutem „In 3D ansehen"): kein Voranschlag, kein Netz.
        val vm2 = viewModel(FakeIndoorParts())
        vm2.openPreview("tour-1")
        assertEquals(IndoorStage.PREVIEW, vm2.stage.value)
        assertNotNull(vm2.preview.value)
    }

    @Test
    fun nurHoehenprofilOhne3dAnsicht() {
        rideStorage.saveRide(testSavedRide(planned = true))
        val parts = FakeIndoorParts()
        val vm = viewModel(parts)
        vm.openPreview("tour-1")
        assertIs<IndoorWorldUi.Plan>(vm.world.value)
        vm.startPreview(withWorld = false)
        assertEquals(IndoorStage.PREVIEW, vm.stage.value)
        assertNull(vm.renderer.value)
        assertTrue(parts.renderers.isEmpty(), "ohne 3D-Ansicht keine Darstellung")
        val ui = assertNotNull(vm.preview.value)
        assertEquals(IndoorRenderUi.NoWorld, ui.render)
        assertFalse(ui.paceFromRide, "Planung: 1× = Modell")
        assertTrue(ui.paceWatts > 0)
        vm.previewTogglePlay()
        vm.playFor(2)
        assertTrue(assertNotNull(vm.preview.value).frame.distM > 0.0)
    }

    @Test
    fun ausfallDerDarstellungLaesstDieVorschauWeiterlaufen() {
        rideStorage.saveRide(testSavedRide())
        val vm = viewModel(FakeIndoorParts(rendererFactory = { FakeWorldRenderer(failure = RenderFailure.NO_GRAPHICS) }))
        vm.openPreview("tour-1")
        vm.loadWorld()
        assertEquals(IndoorStage.PREVIEW, vm.stage.value)
        val failed = assertIs<IndoorRenderUi.Failed>(assertNotNull(vm.preview.value).render)
        assertEquals(RenderFailure.NO_GRAPHICS, failed.failure)
        vm.previewTogglePlay()
        vm.playFor(2)
        assertTrue(assertNotNull(vm.preview.value).frame.distM > 0.0, "auf dem Hoehenprofil weiter")

        // Eine Darstellung, die bei setState wirft, wird abgeschaltet statt abzustuerzen.
        val throwing = FakeWorldRenderer(throwOnSetState = true)
        val vm2 = viewModel(FakeIndoorParts(rendererFactory = { throwing }))
        vm2.openPreview("tour-1")
        assertEquals(IndoorStage.PREVIEW, vm2.stage.value)
        val failed2 = assertIs<IndoorRenderUi.Failed>(assertNotNull(vm2.preview.value).render)
        assertEquals(RenderFailure.INTERNAL, failed2.failure)
        val calls = throwing.states.size
        vm2.previewTogglePlay()
        vm2.playFor(1)
        assertEquals(calls, throwing.states.size, "nach dem Fehler nicht mehr gerufen")
        assertTrue(assertNotNull(vm2.preview.value).frame.distM > 0.0)
    }

    @Test
    fun zurueckVonVorbereitenVerlaesstDenAblauf() {
        rideStorage.saveRide(testSavedRide())
        val vm = viewModel(FakeIndoorParts())
        vm.openPreview("tour-1")
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertFalse(vm.back(), "aus dem Tourdetail gekommen: Zurueck verlaesst den Ablauf")
        assertNull(vm.preview.value)
    }

    @Test
    fun unbekannteTourZeigtMeldung() {
        val vm = viewModel(FakeIndoorParts())
        vm.openPreview("gibt-es-nicht")
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertIs<IndoorWorldUi.RouteUnavailable>(vm.world.value)
    }
}
