package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorRematchRouter
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.testing.FakeWorldBuilder
import de.trailscape.app.indoor.testing.FakeWorldRenderer
import de.trailscape.app.indoor.testing.RealOrFakeIndoorParts
import de.trailscape.app.indoor.testing.testSavedRide
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldBuildFailed
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
 * Der ganze Indoor-Ablauf im Demo-Modus — Koppeln → Strecke → Welt → Fahren
 * → Auswertung → Speichern — gegen echte Ressourcen (Robolectric, fuer den
 * Tournamen „Indoor · …") und eine echte [RideStorage] im Temp-Verzeichnis.
 *
 * Zeit: Fake-Uhr, keine Schleife (`autoLoop = false`) — der Test ruft je
 * 100 ms simulierter Zeit einen Takt. Koroutinen laufen ueber
 * `Dispatchers.Unconfined` sofort im Testthread.
 *
 * Die Variante „echt wenn vorhanden" nimmt die echten Teile aus `:core`
 * (Demo-Trainer, Sitzung, Strecke, Testwelt), sobald sie nicht mehr
 * `NotImplementedError` werfen ([RealOrFakeIndoorParts]); ihre Pruefungen
 * sind so locker, dass echte Physik besteht.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorViewModelDemoFlowTest {

    private val tmp: File = createTempDirectory("indoor-flow").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val savedIds = mutableListOf<String>()
    private val viewModels = mutableListOf<IndoorViewModel>()

    @After
    fun cleanup() {
        viewModels.forEach { it.release() }
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun texts() = ContextIndoorTexts(
        context = { ApplicationProvider.getApplicationContext<Application>() },
        language = { AppLanguage.DE },
    )

    private fun viewModel(parts: IndoorParts, router: IndoorRematchRouter? = null) = IndoorViewModel(
        parts = parts,
        rideStorage = rideStorage,
        worlds = worlds,
        store = MemoryKeyValueStore(),
        texts = texts(),
        profile = { TrainingProfile(ageYears = 40, weightKg = 75.0) },
        plan = { null },
        coreTexts = { CoreTextsDe },
        rematchRouter = router,
        io = Dispatchers.Unconfined,
        clock = clock,
        wallClock = { 1_765_000_000_000L },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
        onRideSaved = { savedIds += it },
    ).also { viewModels += it }

    /** Faehrt [seconds] simulierte Sekunden in 100-ms-Takten. */
    private fun IndoorViewModel.rideFor(seconds: Int) {
        val runtime = assertNotNull(activeRuntime, "Fahrt laeuft")
        repeat(seconds * 10) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }

    /** Koppeln mit Demo-Trainer und weiter zur Streckenwahl. */
    private fun IndoorViewModel.pairWithDemo() {
        assertEquals(IndoorStage.PAIR, stage.value)
        assertFalse(pair.value.canContinue, "ohne Trainer und ohne Demo geht es nicht weiter")
        setDemoTrainer(true)
        assertTrue(pair.value.canContinue)
        continueFromPair()
        assertEquals(IndoorStage.ROUTE, stage.value)
        assertFalse(route.value.loadingOptions)
    }

    /** Beenden, Auswertung, Speichern — liefert die gespeicherte Tour. */
    private fun IndoorViewModel.finishAndSave(): de.trailscape.core.Ride {
        finishRide()
        assertEquals(IndoorStage.SUMMARY, stage.value)
        val summary = assertNotNull(summary.value)
        assertFalse(summary.tooShort, "eine Fahrt ueber 60 s wird gespeichert")
        saveRide()
        val id = assertNotNull(this.summary.value?.savedRideId)
        assertEquals(listOf(id), savedIds)
        assertNull(activeRuntime)
        return assertNotNull(rideStorage.loadRide(id))
    }

    @Test
    fun demoDurchlaufMitTestweltEndetMitGespeicherterIndoorTour() {
        val parts = FakeIndoorParts()
        val vm = viewModel(parts)
        vm.pairWithDemo()

        assertTrue(IndoorRouteOption.TestWorld in vm.route.value.options)
        vm.selectRoute(IndoorRouteOption.TestWorld)
        assertTrue(vm.route.value.canContinue)
        vm.continueFromRoute()
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        val ready = assertIs<IndoorWorldUi.Ready>(vm.world.value)
        assertEquals("Testwelt", ready.routeName)
        assertTrue(File(worlds.testWorldDir(), "manifest.json").isFile, "Testwelt liegt unter indoor/worlds/testwelt")

        vm.startRide(withWorld = true)
        assertEquals(IndoorStage.RIDE, vm.stage.value)
        assertNotNull(vm.renderer.value, "mit Welt gibt es eine Darstellung")
        vm.rideFor(95)

        val ride = assertNotNull(vm.ride.value)
        assertEquals(IndoorRenderUi.Ready, ride.render)
        assertEquals(IndoorViewModel.DEMO_DEFAULT_POWER_W, ride.demoPowerW)
        assertEquals(IndoorViewModel.DEMO_DEFAULT_CADENCE_RPM, ride.demoCadenceRpm)
        assertTrue(assertNotNull(ride.profile).distM.size in 2..300)
        assertTrue(assertNotNull(ride.session).distM > 0.0)
        val renderer = parts.renderers.single() as FakeWorldRenderer
        assertTrue(renderer.states.isNotEmpty())

        val saved = vm.finishAndSave()

        assertTrue(saved.indoor)
        assertFalse(saved.planned)
        assertTrue(saved.points.size >= 60, "ein Punkt je gefahrener Sekunde, hier ${saved.points.size}")
        assertEquals("Indoor · Testwelt", saved.name)
        assertTrue(renderer.destroyed, "die Darstellung wird nach der Fahrt freigegeben")
        assertTrue(rideStorage.listSummaries().summaries.single { it.id == saved.id }.indoor)
    }

    @Test
    fun demoDurchlaufMitEchtenTeilenWennVorhanden() {
        val parts = RealOrFakeIndoorParts()
        val vm = viewModel(parts)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        assertIs<IndoorWorldUi.Ready>(vm.world.value, "Testwelt: ${vm.world.value}")

        vm.startRide(withWorld = true)
        assertNull(vm.ride.value?.startProblem, "Start: ${vm.ride.value?.startProblem} (echt: ${parts.realParts})")
        vm.rideFor(150)
        assertTrue(assertNotNull(vm.ride.value?.session).distM > 0.0, "echt: ${parts.realParts}")

        val saved = vm.finishAndSave()
        assertTrue(saved.indoor)
        assertTrue(saved.points.size >= 2)
        assertTrue(saved.stats.distanceKm > 0.0)
    }

    @Test
    fun darstellungScheitertUndWeltbauWirftTrotzdemGespeicherteTour() {
        // a) Gespeicherte Tour: Voranschlag klappt, der Bau scheitert → ohne Welt fahren.
        rideStorage.saveRide(testSavedRide())
        val builder = FakeWorldBuilder(buildError = WorldBuildFailed(WorldBuildFailed.Reason.SOURCE_ERROR, "Attrappe"))
        val parts = FakeIndoorParts(worldBuilderFactory = { builder })
        var routed = 0
        val router = IndoorRematchRouter { _ ->
            routed++
            PlannedRoute(
                points = emptyList(),
                distanceKm = 2.0,
                ascentM = 0.0,
                waySections = listOf(RouteWaySection(2_000.0, "highway=track surface=gravel")),
            )
        }
        val vm = viewModel(parts, router)
        vm.pairWithDemo()
        val saved = vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().single()
        assertTrue(vm.route.value.ghostPastRideAvailable.not())
        vm.selectRoute(saved)
        assertTrue(vm.route.value.ghostPastRideAvailable, "gefahrene Tour ist als Geist waehlbar")
        vm.setGhost(IndoorGhostOption.PastRide(saved.rideId, ""))
        vm.continueFromRoute()
        val plan = assertIs<IndoorWorldUi.Plan>(vm.world.value)
        assertEquals(0, routed, "vor der Nutzeraktion kein Abgleich (kein Netz)")
        assertEquals(listOf("s3.amazonaws.com", "tiles.openfreemap.org"), plan.hosts)

        vm.loadWorld()
        assertEquals(1, routed, "Belagsabgleich nach „Welt laden“")
        assertTrue(IndoorRouteSource.BROUTER_REMATCH in parts.builtRouteSources)
        val failed = assertIs<IndoorWorldUi.Failed>(vm.world.value)
        assertEquals(WorldBuildFailed.Reason.SOURCE_ERROR, failed.reason)
        assertTrue(failed.canRetry)

        vm.startRide(withWorld = true) // keine Welt da → ohne Welt
        assertEquals(IndoorRenderUi.NoWorld, assertNotNull(vm.ride.value).render)
        assertNotNull(vm.ride.value?.ghostLabel)
        vm.rideFor(95)
        val first = vm.finishAndSave()
        assertTrue(first.indoor)
        assertTrue(first.points.size >= 60)
        assertTrue(first.name.startsWith("Indoor · "))

        // b) Testwelt, aber die Darstellung meldet onFailure → Meldung statt Bild, Fahrt laeuft.
        savedIds.clear()
        val failingParts = FakeIndoorParts(rendererFactory = { FakeWorldRenderer(failure = RenderFailure.NO_GRAPHICS) })
        val vm2 = viewModel(failingParts)
        vm2.pairWithDemo()
        vm2.selectRoute(IndoorRouteOption.TestWorld)
        vm2.continueFromRoute()
        vm2.startRide(withWorld = true)
        val render = assertIs<IndoorRenderUi.Failed>(assertNotNull(vm2.ride.value).render)
        assertEquals(RenderFailure.NO_GRAPHICS, render.failure)
        vm2.rideFor(95)
        assertIs<IndoorRenderUi.Failed>(assertNotNull(vm2.ride.value).render)
        val second = vm2.finishAndSave()
        assertTrue(second.indoor)
        assertTrue(second.points.size >= 60)
    }

    @Test
    fun platzhalterImWeltbauWirdZurMeldungStattAbsturz() {
        val parts = FakeIndoorParts(
            worldBuilderFactory = { throw NotImplementedError("Indoor: createWorldBuilder ist noch nicht umgesetzt") },
        )
        val vm = viewModel(parts)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()

        val unavailable = assertIs<IndoorWorldUi.RouteUnavailable>(vm.world.value)
        val problem = assertNotNull(unavailable.problem)
        assertEquals(IndoorPart.WORLD, problem.part)
        assertTrue(problem.notImplemented)
        assertTrue(vm.back())
        assertEquals(IndoorStage.ROUTE, vm.stage.value)
    }

    @Test
    fun kurzeFahrtWirdNichtGespeichert() {
        val vm = viewModel(FakeIndoorParts())
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        assertEquals(IndoorRenderUi.NoWorld, assertNotNull(vm.ride.value).render)
        assertNull(vm.renderer.value)
        vm.rideFor(20)
        vm.finishRide()
        val summary = assertNotNull(vm.summary.value)
        assertTrue(summary.tooShort)
        vm.saveRide()
        assertNull(vm.summary.value?.savedRideId)
        assertTrue(rideStorage.listSummaries().summaries.isEmpty())
    }

    @Test
    fun indoorFahrtenSindKeineStrecke() {
        rideStorage.saveRide(testSavedRide(id = "draussen"))
        rideStorage.saveRide(testSavedRide(id = "drinnen").copy(indoor = true))
        val vm = viewModel(FakeIndoorParts())
        vm.pairWithDemo()
        val ids = vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().map { it.rideId }
        assertEquals(listOf("draussen"), ids)
    }
}
