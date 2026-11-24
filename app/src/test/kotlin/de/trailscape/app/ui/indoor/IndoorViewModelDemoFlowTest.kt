package de.trailscape.app.ui.indoor

import android.app.Application
import android.os.Looper
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.app.indoor.IndoorRematchRouter
import de.trailscape.app.indoor.render.FilamentWorldRenderer
import de.trailscape.app.indoor.render.createWorldRenderer
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.testing.FakeWorldBuilder
import de.trailscape.app.indoor.testing.FakeWorldRenderer
import de.trailscape.app.indoor.testing.RealOrFakeIndoorParts
import de.trailscape.app.indoor.testing.testSavedRide
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.indoor.worldRouteOf
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.sky.IndoorDaylight
import de.trailscape.core.indoor.sky.IndoorTimeOfDay
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldRenderer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    private fun viewModel(
        parts: IndoorParts,
        router: IndoorRematchRouter? = null,
        store: MemoryKeyValueStore = MemoryKeyValueStore(),
        io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ) = IndoorViewModel(
        parts = parts,
        rideStorage = rideStorage,
        worlds = worlds,
        store = store,
        texts = texts(),
        profile = { TrainingProfile(ageYears = 40, weightKg = 75.0) },
        plan = { null },
        coreTexts = { CoreTextsDe },
        rematchRouter = router,
        io = io,
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
        // Vor jeder Fahrt wartet die Startpunkt-Karte; der Demo-Trainer braucht „Losfahren".
        releaseStart()
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

    /** Koppeln (Demo), Testwelt, Start — liefert die Konfiguration der Sitzung. */
    private fun IndoorViewModel.startTestWorldRide(parts: FakeIndoorParts): de.trailscape.core.indoor.IndoorSessionConfig {
        pairWithDemo()
        selectRoute(IndoorRouteOption.TestWorld)
        continueFromRoute()
        startRide(withWorld = false)
        assertEquals(IndoorStage.RIDE, stage.value)
        return parts.sessionConfigs.single()
    }

    @Test
    fun kettenblattVorgabe48AufZwiftCogGehtAnDieSitzung() {
        val parts = FakeIndoorParts()
        val vm = viewModel(parts)
        assertEquals(48, vm.pair.value.chainringTeeth)

        val config = vm.startTestWorldRide(parts)

        assertEquals(48.0 / 14.0, config.rider.realGearRatio, 1e-9)
        assertEquals(IndoorViewModel.realGearRatioOf(48), config.rider.realGearRatio, 1e-9)
        // Uebrige Werte aus IndoorParts.riderSetup bleiben unberuehrt.
        assertEquals(75.0, config.rider.riderMassKg, 1e-9)
    }

    @Test
    fun geaendertesKettenblattWirdGemerktUndGehtAnDieSitzung() {
        val store = MemoryKeyValueStore()
        val first = viewModel(FakeIndoorParts(), store = store)
        first.setChainringTeeth(50)
        assertEquals(50, first.pair.value.chainringTeeth)
        assertEquals("50", store.getString(IndoorViewModel.KEY_CHAINRING_TEETH))

        // Ein neuer Ablauf liest den gemerkten Wert.
        val parts = FakeIndoorParts()
        val vm = viewModel(parts, store = store)
        assertEquals(50, vm.pair.value.chainringTeeth)
        val config = vm.startTestWorldRide(parts)
        assertEquals(50.0 / 14.0, config.rider.realGearRatio, 1e-9)
    }

    @Test
    fun kettenblattWirdAufSinnvollenBereichBegrenzt() {
        val store = MemoryKeyValueStore()
        val vm = viewModel(FakeIndoorParts(), store = store)
        vm.setChainringTeeth(100)
        assertEquals(IndoorViewModel.CHAINRING_MAX_TEETH, vm.pair.value.chainringTeeth)
        vm.setChainringTeeth(3)
        assertEquals(IndoorViewModel.CHAINRING_MIN_TEETH, vm.pair.value.chainringTeeth)

        // Unsinn im Speicher faellt auf die Vorgabe zurueck.
        store.setString(IndoorViewModel.KEY_CHAINRING_TEETH, "kein Wert")
        assertEquals(48, viewModel(FakeIndoorParts(), store = store).pair.value.chainringTeeth)
    }

    @Test
    fun kettenblattGiltAuchWennRiderSetupDesKernsWirft() {
        val parts = FakeIndoorParts(riderSetupFactory = { throw NotImplementedError("Kern fehlt") })
        val vm = viewModel(parts)
        vm.setChainringTeeth(31)

        val config = vm.startTestWorldRide(parts)

        assertEquals(31.0 / 14.0, config.rider.realGearRatio, 1e-9)
        assertNull(vm.ride.value?.startProblem, "der Ersatz-Aufbau faehrt ohne Startproblem")
    }

    @Test
    fun demoDurchlaufMitEchtenTeilenWennVorhanden() {
        val fake = FakeIndoorParts()
        val parts = RealOrFakeIndoorParts(fake)
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

    /**
     * Abschluss der Integration (docs/indoor.md §12): der Demo-Durchlauf mit
     * dem **echten** Weltbau (Testwelt ohne Netz, GLB-Kacheln auf der Platte),
     * der Strecke aus dem Manifest (`worldRouteOf`) und der **echten**
     * Darstellung. Unter Robolectric fehlt die native Bibliothek: Die
     * Darstellung meldet `NO_GRAPHICS`, statt die App abstuerzen zu lassen,
     * die Fahrt laeuft mit HUD weiter und endet als gespeicherte Indoor-Tour
     * auf den Koordinaten der Testwelt.
     */
    @Test
    fun demoDurchlaufMitEchterTestweltUndEchterDarstellung() {
        val fake = FakeIndoorParts()
        val real = RealOrFakeIndoorParts(fake)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val renderers = mutableListOf<WorldRenderer>()
        val parts = object : IndoorParts by real {
            override fun createRenderer(): WorldRenderer = createWorldRenderer(app).also { renderers += it }
        }
        val vm = viewModel(parts)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        assertIs<IndoorWorldUi.Ready>(vm.world.value, "Testwelt: ${vm.world.value}")
        assertTrue("createWorldBuilder" in real.realParts, "echter Weltbau: ${real.realParts}")

        // Die echte Testwelt: Manifest und je Kachel eine GLB-Datei (die Attrappe schreibt keine Kacheln).
        val dir = worlds.testWorldDir()
        val manifest = WorldManifest.parse(File(dir, WorldManifest.FILE_NAME).readText(Charsets.UTF_8))
        assertTrue(manifest.tiles.size >= 10, "Kacheln: ${manifest.tiles.size}")
        assertEquals(setOf(0, 1, 2), manifest.tiles.map { it.level }.toSet(), "drei Detailstufen")
        for (tile in manifest.tiles) {
            val file = File(dir, tile.file)
            assertTrue(file.isFile, "Kachel ${tile.id} fehlt: ${tile.file}")
            val magic = file.inputStream().use { it.readNBytes(4) }
            assertEquals("glTF", String(magic, Charsets.US_ASCII), "GLB-Kennung von ${tile.file}")
        }

        vm.startRide(withWorld = true)
        assertNull(vm.ride.value?.startProblem, "Start: ${vm.ride.value?.startProblem}")
        // Die Sitzung faehrt auf dem Streckenband des Manifests.
        val expected = worldRouteOf(manifest, "Testwelt")
        val driven = fake.sessionConfigs.first().route
        assertEquals(expected.id, driven.id)
        assertEquals(expected.name, driven.name)
        assertEquals(expected.isLoop, driven.isLoop)
        assertEquals(expected.source, driven.source)
        assertEquals(expected.surfaces, driven.surfaces)
        assertEquals(expected.points.size, driven.points.size)
        // Gefahren wird mit dem Manifest, das der Bau zurueckgab; gelesen hier aus der Datei —
        // gleich bis auf die Rundung der Zahlen im JSON, die Laufmeter genau.
        for ((e, d) in expected.points.zip(driven.points)) {
            assertEquals(e.distM, d.distM, 1e-9)
            assertEquals(e.lat, d.lat, 1e-6)
            assertEquals(e.lon, d.lon, 1e-6)
            assertEquals(e.eleM, d.eleM, 0.01)
        }
        assertTrue(abs(driven.lengthM - manifest.track.lengthM) < 1.0)

        // Die echte Darstellung bekommt eine Flaeche — und faellt ohne Grafik sauber aus.
        val renderer = assertIs<FilamentWorldRenderer>(renderers.single())
        assertEquals(renderer, vm.renderer.value)
        assertFalse(renderer.attach(SurfaceView(app)), "ohne native Bibliothek keine Engine")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RenderFailure.NO_GRAPHICS, renderer.failure)
        vm.rideFor(1)
        val failed = assertIs<IndoorRenderUi.Failed>(vm.ride.value?.render)
        assertEquals(RenderFailure.NO_GRAPHICS, failed.failure)

        vm.rideFor(120)
        val session = assertNotNull(vm.ride.value?.session)
        assertTrue(session.distM > 100.0, "die Fahrt laeuft ohne 3D-Bild weiter, distM=${session.distM}")

        val saved = vm.finishAndSave()
        assertTrue(saved.indoor)
        assertEquals("Indoor · Testwelt", saved.name)
        assertTrue(saved.points.size >= 60, "ein Punkt je gefahrener Sekunde, hier ${saved.points.size}")
        val o = manifest.origin
        assertTrue(
            saved.points.all { abs(it.lat - o.lat) < 0.05 && abs(it.lon - o.lon) < 0.05 },
            "die Punkte liegen in der Testwelt um ${o.lat}, ${o.lon}",
        )
    }

    /**
     * Nagelt fest, dass der Demo-Durchlauf nach dem Zusammenfuehren mit den
     * **echten** Teilen aus `:core` faehrt (Demo-Trainer, Sitzung, Fahrer-
     * Aufbau) — keine Attrappe springt still ein. Die Welt darf noch die
     * Attrappe sein, solange der Weltbau fehlt.
     */
    @Test
    fun demoDurchlaufNutztEchtenDemoTrainerUndEchteSitzung() {
        val fake = FakeIndoorParts()
        val parts = RealOrFakeIndoorParts(fake)
        val vm = viewModel(parts)
        vm.setGearTable(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = true)
        vm.rideFor(120)

        assertTrue(
            parts.realParts.containsAll(listOf("createDemoTrainer", "createIndoorSession", "riderSetupFrom")),
            "echt: ${parts.realParts}",
        )
        val trainer = fake.trainers.single()
        assertFalse(trainer is de.trailscape.app.indoor.testing.FakeDemoTrainer, "echter Demo-Trainer: ${trainer::class}")
        assertTrue(fake.sessions.none { it is de.trailscape.app.indoor.testing.FakeIndoorSession }, "echte Sitzung")

        // Die Sitzung faehrt mit den Faehigkeiten, die der Demo-Trainer meldet
        // (Leistungsbereich), nicht mit der Annahme vom Start.
        assertEquals(2, fake.sessionConfigs.size, "Annahme vom Start, dann einmal mit den gemeldeten Faehigkeiten")
        assertEquals(IndoorViewModel.ASSUMED_CAPABILITIES, fake.sessionConfigs.first().capabilities)
        val effective = fake.sessionConfigs.last()
        assertNotNull(effective.capabilities.maxPowerW, "Leistungsbereich des Demo-Trainers: ${effective.capabilities}")
        // Einstellungen aus dem Schritt „Koppeln“ und echte Fahrerdaten aus riderSetupFrom.
        assertEquals(48.0 / 14.0, effective.rider.realGearRatio, 1e-9)
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12, effective.rider.gearTable)
        assertEquals(75.0, effective.rider.riderMassKg, 1e-9)

        val session = assertNotNull(vm.ride.value?.session)
        assertEquals(de.trailscape.core.indoor.IndoorPhase.RUNNING, session.phase)
        assertTrue(session.distM > 100.0, "echte Physik mit 180 W, distM=${session.distM}")
        assertEquals(de.trailscape.core.indoor.TrainerConnection.READY, vm.ride.value?.trainer)

        val saved = vm.finishAndSave()
        assertTrue(saved.indoor)
        assertTrue(saved.points.size >= 60, "ein Punkt je gefahrener Sekunde, hier ${saved.points.size}")
        assertTrue(saved.points.any { (it.power ?: 0) > 0 }, "Leistung steht an den Punkten")
    }

    @Test
    fun gangtabelleWirdGemerktUndGehtAnDieSitzung() {
        val store = MemoryKeyValueStore()
        val first = viewModel(FakeIndoorParts(), store = store)
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.EVEN, first.pair.value.gearTable, "Vorgabe gleichmaessig")
        first.setGearTable(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12)
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12, first.pair.value.gearTable)
        assertEquals("BIKE_GRX_2X12", store.getString(IndoorViewModel.KEY_GEAR_TABLE))

        val parts = FakeIndoorParts()
        val vm = viewModel(parts, store = store)
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12, vm.pair.value.gearTable)
        val config = vm.startTestWorldRide(parts)
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12, config.rider.gearTable)
        assertEquals(48.0 / 14.0, config.rider.realGearRatio, 1e-9, "Kettenblatt bleibt daneben erhalten")

        // Unbekannter Wert im Speicher → Vorgabe.
        store.setString(IndoorViewModel.KEY_GEAR_TABLE, "KASSETTE_11_50")
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.EVEN, viewModel(FakeIndoorParts(), store = store).pair.value.gearTable)
    }

    @Test
    fun tageszeitWirdGemerktUndGehtAnDieDarstellung() {
        val store = MemoryKeyValueStore()
        val first = viewModel(FakeIndoorParts(), store = store)
        assertEquals(IndoorTimeOfDay.AUTO, first.pair.value.timeOfDay, "Vorgabe automatisch")
        first.setTimeOfDay(IndoorTimeOfDay.EVENING)
        assertEquals("EVENING", store.getString(IndoorViewModel.KEY_TIME_OF_DAY))

        val parts = FakeIndoorParts()
        val vm = viewModel(parts, store = store)
        assertEquals(IndoorTimeOfDay.EVENING, vm.pair.value.timeOfDay)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = true)
        val renderer = parts.renderers.single() as FakeWorldRenderer
        val evening = renderer.skies.last()
        assertEquals(IndoorDaylight.PRESET_DAY_OF_YEAR, evening.dayOfYear)
        assertTrue(evening.hourOfDay > 16.0, "Abendsonne: ${evening.hourOfDay}")

        // Waehrend der Fahrt umgestellt: wirkt sofort; „Automatisch“ folgt der Uhr (Testuhr: 6.12.2025).
        vm.setTimeOfDay(IndoorTimeOfDay.AUTO)
        val auto = renderer.skies.last()
        assertEquals(340, auto.dayOfYear)
        vm.rideFor(2)
        assertEquals(2, renderer.skies.size, "ohne Aenderung kein neuer Himmel (Nachfuehren erst nach 10 min)")

        // Unbekannter Wert im Speicher → automatisch.
        store.setString(IndoorViewModel.KEY_TIME_OF_DAY, "MITTERNACHT")
        assertEquals(IndoorTimeOfDay.AUTO, viewModel(FakeIndoorParts(), store = store).pair.value.timeOfDay)
    }

    @Test
    fun gangtabelleGiltAuchWennRiderSetupDesKernsWirft() {
        val parts = FakeIndoorParts(riderSetupFactory = { throw NotImplementedError("Kern fehlt") })
        val vm = viewModel(parts)
        vm.setGearTable(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12)
        val config = vm.startTestWorldRide(parts)
        assertEquals(de.trailscape.core.indoor.VirtualGearTable.BIKE_GRX_2X12, config.rider.gearTable)
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
    fun errorImWeltbauWirdZurFehlerkarteMitTechnischerEinzelheit() {
        val errors = listOf<Throwable>(
            OutOfMemoryError("Failed to allocate a 67108880 byte allocation"),
            NoSuchMethodError("No static method foo()V"),
            StackOverflowError(),
            WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, "Kachel kaputt", IllegalStateException("Index 70000")),
        )
        for (error in errors) {
            rideStorage.saveRide(testSavedRide())
            val builder = FakeWorldBuilder(buildError = error)
            val vm = viewModel(FakeIndoorParts(worldBuilderFactory = { builder }))
            vm.pairWithDemo()
            vm.selectRoute(vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().single())
            vm.continueFromRoute()
            assertIs<IndoorWorldUi.Plan>(vm.world.value)
            vm.loadWorld()
            val failed = assertIs<IndoorWorldUi.Failed>(vm.world.value, "nach ${error.javaClass.simpleName}")
            assertTrue(failed.canRetry)
            val detail = assertNotNull(failed.detail)
            val expected = if (error is WorldBuildFailed) "IllegalStateException: Index 70000" else error.javaClass.simpleName
            assertTrue(expected in detail, "Einzelheit nennt die Ursache: $detail")
            assertTrue(detail.length <= de.trailscape.app.indoor.TECHNICAL_DETAIL_MAX_CHARS)
        }
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
        assertFalse(summary.recordMissing)
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

    // -----------------------------------------------------------------------
    // Robustheit: Weltbau abloesen, haengende Sitzung, fehlende Aufzeichnung
    // -----------------------------------------------------------------------

    /** Wartet (echte Zeit), bis [condition] gilt. */
    private fun awaitTrue(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Zeitueberschreitung: $what")
            Thread.sleep(5)
        }
    }

    @Test
    fun abgeloesterWeltbauSchreibtNichtsMehrUndLaeuftNieParallel() {
        val tourA = testSavedRide(id = "a", name = "Tour A")
        val tourB = testSavedRide(id = "b", name = "Tour B").let { r ->
            r.copy(points = r.points.map { it.copy(lat = it.lat + 0.5) })
        }
        rideStorage.saveRide(tourA)
        rideStorage.saveRide(tourB)

        val firstBuildEntered = java.util.concurrent.CountDownLatch(1)
        val releaseFirstBuild = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicInteger(0)
        val maxRunning = java.util.concurrent.atomic.AtomicInteger(0)
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val delegate = FakeWorldBuilder()
        val builder = object : de.trailscape.core.indoor.world.WorldBuilder by delegate {
            override fun build(
                plan: de.trailscape.core.indoor.world.WorldPlan,
                deps: de.trailscape.core.indoor.world.WorldBuildDeps,
                outDir: File,
                listener: de.trailscape.core.indoor.world.WorldBuildListener,
            ): de.trailscape.core.indoor.world.WorldManifest {
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                try {
                    if (calls.incrementAndGet() == 1) {
                        // Der erste Bau haengt (langsames Netz) und fragt den Abbruch nicht ab.
                        firstBuildEntered.countDown()
                        releaseFirstBuild.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        listener.onProgress(de.trailscape.core.indoor.world.WorldBuildPhase.WRITING, 1, 1)
                        return FakeWorldBuilder.manifestFor(plan.worldId, plan.route, plan.quality)
                    }
                    return delegate.build(plan, deps, outDir, listener)
                } finally {
                    running.decrementAndGet()
                }
            }
        }
        val parts = FakeIndoorParts(worldBuilderFactory = { builder })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val vm = viewModel(parts, io = pool.asCoroutineDispatcher())
            vm.setDemoTrainer(true)
            vm.continueFromPair()
            awaitTrue("Streckenliste") { !vm.route.value.loadingOptions }
            val options = vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>()
            val a = options.single { it.rideId == "a" }
            val b = options.single { it.rideId == "b" }

            // Tour A: Voranschlag, dann „Welt laden" — der Bau haengt.
            vm.selectRoute(a)
            vm.continueFromRoute()
            awaitTrue("Voranschlag A") { vm.world.value is IndoorWorldUi.Plan }
            vm.loadWorld()
            assertTrue(firstBuildEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

            // Zurueck, Tour B waehlen: B wartet, bis der alte Bau endet.
            assertTrue(vm.back())
            vm.selectRoute(b)
            vm.continueFromRoute()
            Thread.sleep(100)
            assertEquals(IndoorWorldUi.Preparing, vm.world.value, "B wartet auf das Ende des abgeloesten Baus")

            releaseFirstBuild.countDown()
            val plan = awaitPlan(vm)
            assertEquals("Tour B", plan.routeName, "der spaete Bau von A ueberschreibt nichts")
            vm.loadWorld()
            awaitTrue("Welt B") { vm.world.value is IndoorWorldUi.Ready }
            assertEquals("Tour B", (vm.world.value as IndoorWorldUi.Ready).routeName)
            assertEquals(1, maxRunning.get(), "nie zwei Bauten gleichzeitig")
            assertEquals(2, calls.get())

            // „Losfahren" faehrt die Welt von B, nicht die von A.
            vm.startRide(withWorld = true)
            val route = parts.sessionConfigs.single().route
            val lat = route.points.first().lat
            assertTrue(kotlin.math.abs(lat - tourB.points.first().lat) < 0.01, "Strecke von B erwartet, lat=$lat")
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * „Abbrechen" waehrend „Welt laden": sofort sichtbar („Wird abgebrochen …",
     * Knopf aus), auch wenn der Weltbau seine laufende Kachel noch zu Ende
     * rechnet; danach der Voranschlag mit „Fortsetzen".
     */
    @Test
    fun abbruchDesWeltbausIstSofortSichtbar() {
        rideStorage.saveRide(testSavedRide(id = "a", name = "Tour A"))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val delegate = FakeWorldBuilder()
        val builder = object : de.trailscape.core.indoor.world.WorldBuilder by delegate {
            override fun build(
                plan: de.trailscape.core.indoor.world.WorldPlan,
                deps: de.trailscape.core.indoor.world.WorldBuildDeps,
                outDir: File,
                listener: de.trailscape.core.indoor.world.WorldBuildListener,
            ): de.trailscape.core.indoor.world.WorldManifest {
                listener.onProgress(de.trailscape.core.indoor.world.WorldBuildPhase.MESHING, 3, 10)
                entered.countDown()
                // Die laufende Kachel wird noch fertig gerechnet, erst dann zaehlt der Abbruch.
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                listener.onProgress(de.trailscape.core.indoor.world.WorldBuildPhase.MESHING, 4, 10)
                if (listener.isCancelled()) throw de.trailscape.core.indoor.world.WorldBuildCancelled()
                return delegate.build(plan, deps, outDir, listener)
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val vm = viewModel(FakeIndoorParts(worldBuilderFactory = { builder }), io = pool.asCoroutineDispatcher())
            vm.setDemoTrainer(true)
            vm.continueFromPair()
            awaitTrue("Streckenliste") { !vm.route.value.loadingOptions }
            vm.selectRoute(vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().single())
            vm.continueFromRoute()
            awaitPlan(vm)
            vm.loadWorld()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(assertIs<IndoorWorldUi.Building>(vm.world.value).cancelling)

            vm.cancelWorldBuild()
            val building = assertIs<IndoorWorldUi.Building>(vm.world.value)
            assertTrue(building.cancelling, "Abbruch sofort sichtbar, waehrend die Kachel noch laeuft")

            release.countDown()
            val plan = awaitPlan(vm)
            assertTrue(plan.resumable, "nach dem Abbruch laesst sich der Bau fortsetzen")
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitPlan(vm: IndoorViewModel): IndoorWorldUi.Plan {
        awaitTrue("Voranschlag") { vm.world.value is IndoorWorldUi.Plan }
        return vm.world.value as IndoorWorldUi.Plan
    }

    @Test
    fun sitzungOhneZustandIstKeineSackgasse() {
        val parts = FakeIndoorParts(
            sessionFactory = { config ->
                object : de.trailscape.core.indoor.IndoorSession by de.trailscape.app.indoor.testing.FakeIndoorSession(config) {
                    override fun step(nowMs: Long, input: de.trailscape.core.indoor.SessionInput): de.trailscape.core.indoor.IndoorStep =
                        throw IllegalStateException("Attrappe: jeder Schritt scheitert")
                }
            },
        )
        val vm = viewModel(parts)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        vm.rideFor(2)

        val ride = assertNotNull(vm.ride.value)
        assertNull(ride.session, "die Sitzung liefert keinen Zustand")
        assertNull(ride.startProblem)
        assertEquals(IndoorPart.CORE, assertNotNull(ride.runtimeProblem, "der Fehler wird sichtbar").part)

        assertTrue(vm.back())
        assertEquals(IndoorStage.WORLD, vm.stage.value, "Zurueck fuehrt aus der haengenden Fahrt")
        assertNull(vm.activeRuntime)
        assertNull(vm.ride.value)
    }

    @Test
    fun laufendeFahrtVerschlucktZurueck() {
        val vm = viewModel(FakeIndoorParts())
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        vm.rideFor(2)
        assertNotNull(vm.ride.value?.session)
        assertTrue(vm.back())
        assertEquals(IndoorStage.RIDE, vm.stage.value)
        assertNotNull(vm.activeRuntime)
    }

    @Test
    fun fehlendeAufzeichnungIstNichtZuKurz() {
        val parts = FakeIndoorParts(
            sessionFactory = { config ->
                object : de.trailscape.core.indoor.IndoorSession by de.trailscape.app.indoor.testing.FakeIndoorSession(config) {
                    override fun finish(): de.trailscape.core.indoor.IndoorRideRecord =
                        throw IllegalStateException("Attrappe: finish scheitert")
                }
            },
        )
        val vm = viewModel(parts)
        vm.pairWithDemo()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        vm.rideFor(95)
        vm.finishRide()

        val summary = assertNotNull(vm.summary.value)
        assertFalse(summary.tooShort, "eine lange Fahrt ist nicht „zu kurz“")
        assertTrue(summary.recordMissing)
        assertEquals(IndoorPart.CORE, assertNotNull(summary.recordProblem).part)
        assertTrue(summary.movingS >= 60, "Kennzahlen aus dem letzten Sitzungszustand")
        vm.saveRide()
        assertNull(vm.summary.value?.savedRideId)
    }
}
