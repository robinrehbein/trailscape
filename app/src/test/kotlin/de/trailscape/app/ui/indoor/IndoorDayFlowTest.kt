package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.IndoorDayRouteFinder
import de.trailscape.app.indoor.catalog.CatalogDownloader
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.AscentPreference
import de.trailscape.core.Ride
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.TrainingSession
import de.trailscape.core.computeStats
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.DayRideEnd
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.indoor.catalog.IndoorCatalog
import de.trailscape.core.indoor.catalog.sha256Hex
import de.trailscape.core.indoor.catalog.writeCatalogPackage
import de.trailscape.core.indoor.createIndoorSession
import de.trailscape.core.indoor.ergWorkoutFor
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.createWorldBuilder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Einheit des Tages im ganzen Ablauf (docs/indoor/app.md, „Einheit des
 * Tages"): Karte im Schritt Strecke, „Fahren" waehlt Strecke, ERG und
 * Startpunkt, Ruhetag und Tag ohne Plan, Verhalten am Ende. Echte Sitzung und
 * echte ERG-Einheit aus `:core`, Demo-Trainer-Attrappe, Takt von Hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorDayFlowTest {

    private val tmp: File = createTempDirectory("indoor-day").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val parts = FakeIndoorParts(
        sessionFactory = { createIndoorSession(it) },
        ergWorkoutFactory = { s, t -> ergWorkoutFor(s, t) },
    )
    private var vm: IndoorViewModel? = null

    /** Attrappe fuer den Bau der generierten Welt: gerade Strecke mit den Steigungen der Abschnitte. */
    private val generatedWorlds = object : de.trailscape.app.indoor.IndoorGeneratedWorlds {
        var builds = 0
        val plans = mutableListOf<de.trailscape.core.indoor.generated.GeneratedProfile>()

        fun route(profile: de.trailscape.core.indoor.generated.GeneratedProfile): de.trailscape.core.indoor.IndoorRoute {
            val pts = mutableListOf<TrackPoint>()
            var d = 0.0
            var e = 100.0
            pts += TrackPoint(50.0, 7.0, e)
            for (leg in profile.legs) {
                var left = leg.lengthM
                while (left > 1e-6) {
                    val step = minOf(10.0, left)
                    d += step
                    e += step * leg.gradePct / 100
                    left -= step
                    pts += TrackPoint(50.0 + d / 111_195.0, 7.0, e)
                }
            }
            return assertNotNull(de.trailscape.app.indoor.testing.fakeRouteFromPoints("gen", pts, de.trailscape.core.indoor.IndoorRouteSource.NONE))
        }

        override fun plan(
            profile: de.trailscape.core.indoor.generated.GeneratedProfile,
            quality: de.trailscape.core.indoor.world.IndoorQuality,
            name: String,
        ): de.trailscape.core.indoor.world.WorldPlan {
            plans += profile
            return de.trailscape.core.indoor.world.WorldPlan(
                worldId = "gen-${java.lang.Long.toHexString(profile.seed)}-${quality.name.lowercase()}",
                route = route(profile), quality = quality, tileCount = 4, requests = emptyMap(),
                downloadBytes = 0L, diskBytes = 1_000_000L, hosts = emptyList(), aerial = emptyList(),
            )
        }

        override fun build(
            plan: de.trailscape.core.indoor.world.WorldPlan,
            profile: de.trailscape.core.indoor.generated.GeneratedProfile,
            outDir: File,
            listener: de.trailscape.core.indoor.world.WorldBuildListener,
        ): de.trailscape.core.indoor.world.WorldManifest {
            builds++
            val m = de.trailscape.app.indoor.testing.FakeWorldBuilder.manifestFor(plan.worldId, plan.route, plan.quality)
            de.trailscape.app.indoor.testing.FakeWorldBuilder.writeManifest(outDir, m)
            return m
        }
    }

    @After
    fun cleanup() {
        vm?.release()
        scope.cancel()
        tmp.deleteRecursively()
    }

    /** 3 × 8 min mit 4 min Pause, 10 min Ein- und Ausfahren (52 min). */
    private val intervals = TrainingSession(
        day = "2026-11-24", title = "Schwelle", description = "", targetKm = 25,
        intensity = SessionIntensity.HART, durationMin = 52,
        textKey = SessionTextKey.INTERVALS, textArgs = listOf(10, 3, 8, 4, 10, 52, 0),
    )

    private fun target(intensity: SessionIntensity, hours: Double = 1.0) = RouteTarget(
        distanceKm = 30.0, ascentPreference = AscentPreference.BERGIG, durationH = hours, speedKmh = 28.0,
        intensity = intensity, label = "Runde", source = RouteTargetSource.PLAN,
    )

    private fun viewModel(today: TodayRoute?) = IndoorViewModel(
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
        today = { today },
        dayFinder = IndoorDayRouteFinder(rideStorage, worlds, null),
        generatedWorlds = generatedWorlds,
        io = Dispatchers.Unconfined,
        clock = clock,
        wallClock = { 1_765_000_000_000L },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
    ).also { vm = it }

    /** Eine gerade Strecke nach Norden, Hoehe nach [ele] (Laufmeter → m), Punkte alle 50 m. */
    private fun ride(id: String, name: String, lengthM: Double, ele: (Double) -> Double): Ride {
        val n = (lengthM / 50).toInt()
        val points = List(n + 1) { i ->
            val d = i * 50.0
            TrackPoint(lat = 50.0 + d / 111_195.0, lon = 7.0, ele = ele(d), time = 1_700_000_000_000L + i * 10_000L)
        }
        return Ride(id = id, name = name, createdAt = 1_700_000_000_000L, stats = computeStats(points), points = points, planned = true)
    }

    /**
     * 12 km flach, dann drei Anstiege von 1,8 km mit 5 % (bei 171 W rund 8 min),
     * je 1,5 km flach dazwischen.
     */
    private val hilly: (Double) -> Double = { d ->
        var e = 0.0
        var at = 12_000.0
        repeat(3) {
            if (d > at) e += minOf(d - at, 1_800.0) * 0.05
            at += 1_800.0 + 1_500.0
        }
        e
    }

    private fun IndoorViewModel.toRoute() {
        setDemoTrainer(true)
        continueFromPair()
        assertEquals(IndoorStage.ROUTE, stage.value)
    }

    private fun IndoorViewModel.tick(times: Int) {
        val runtime = assertNotNull(activeRuntime)
        repeat(times) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }

    @Test
    fun fahrenWaehltPassendeStreckeErgUndStartpunkt() {
        rideStorage.saveRide(ride("flach", "Flache Runde", 30_000.0) { 0.0 })
        rideStorage.saveRide(ride("berge", "Drei Anstiege", 40_000.0, hilly))
        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.toRoute()

        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        assertEquals(DaySessionKind.PLANNED, offer.kind)
        assertEquals(52, offer.minutes)
        val best = assertNotNull(offer.current)
        assertEquals("ride:berge", best.key)
        assertEquals(DayRouteAvailability.BUILD, best.availability)
        assertNotNull(best.buildMinutes)
        val climbs = assertNotNull(best.climbs)
        assertEquals(3, climbs.reps)
        assertEquals(8, climbs.workMinutes)
        val startKm = assertNotNull(best.startKm, "Start so, dass die Intervalle in die Anstiege fallen")
        assertTrue(startKm > 2.0, "$startKm")
        assertTrue(offer.suggestions.any { it.key == IndoorDayRouteFinder.PLAN_KEY }, "neue Runde als Rueckfall")
        assertTrue(offer.suggestions.indexOfFirst { it.key == "ride:flach" } > 0)

        // Andere Strecke blaettert und kommt zurueck.
        vm.nextDaySuggestion()
        assertEquals(1, (vm.day.value as IndoorDayUi.Offer).index)
        repeat(offer.suggestions.size - 1) { vm.nextDaySuggestion() }
        assertEquals("ride:berge", (vm.day.value as IndoorDayUi.Offer).current?.key)

        vm.rideDaySuggestion()
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertEquals(IndoorMode.ERG, vm.route.value.mode)
        assertEquals("ride:berge", vm.route.value.selected?.key)
        assertIs<IndoorWorldUi.Plan>(vm.world.value, "eigene Strecke ohne Welt: Voranschlag zum Bauen")

        vm.startRide(withWorld = false)
        vm.tick(1)
        val start = assertNotNull(vm.ride.value?.start)
        assertEquals(startKm * 1000, start.selectedM, 60.0)
        val session = assertNotNull(vm.ride.value?.session)
        assertEquals(IndoorMode.ERG, session.mode)
        assertEquals(startKm * 1000, session.routeDistM, 60.0)
    }

    @Test
    fun ruhetagZeigtKeineEinheitUndKeinErg() {
        val vm = viewModel(TodayRoute(null, intervals, 25, 0.0, true, null))
        vm.toRoute()
        val rest = assertIs<IndoorDayUi.Rest>(vm.day.value)
        assertTrue(rest.plannedTitle!!.isNotBlank())
        assertNull(vm.route.value.ergTitle, "am Ruhetag gibt es auch keine ERG-Einheit")
        vm.setMode(IndoorMode.ERG)
        assertEquals(IndoorMode.SIM, vm.route.value.mode)
    }

    @Test
    fun ohnePlaneinheitEineKurzeLockereRunde() {
        rideStorage.saveRide(ride("flach", "Flache Runde", 25_000.0) { 0.0 })
        val vm = viewModel(TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        assertEquals(DaySessionKind.EASY, offer.kind)
        assertEquals(45, offer.minutes)
        assertEquals("Lockere Runde", offer.title)
        assertEquals("ride:flach", offer.current?.key)
        assertEquals(45, vm.route.value.ergMinutes)
    }

    @Test
    fun streckeOhneRundschlussEndetVorDerEinheit() {
        rideStorage.saveRide(ride("kurz", "Kurze Strecke", 1_000.0) { 0.0 })
        val easy = TrainingSession(day = "", title = "Locker", description = "", targetKm = 10, intensity = SessionIntensity.LOCKER, durationMin = 30)
        val vm = viewModel(TodayRoute(target(SessionIntensity.LOCKER), easy, 10, 1.0, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        val short = offer.suggestions.first { it.key == "ride:kurz" }
        assertNotNull(short.endsEarlyMin, "die Karte sagt vorher, dass die Strecke nicht reicht")
        vm.preferDaySuggestion("ride:kurz")
        vm.rideDaySuggestion()
        vm.startRide(withWorld = false)
        vm.releaseStart()
        var guard = 0
        while (vm.ride.value?.session?.phase != IndoorPhase.FINISHED && guard++ < 6000) vm.tick(10)
        val end = assertNotNull(vm.ride.value?.dayEnd)
        assertEquals(DayRideEnd.ROUTE_DONE_EARLY, end.kind)
        assertTrue(end.remainingMin in 1..30, "${end.remainingMin}")
        vm.dismissDayEnd()
        assertNull(vm.ride.value?.dayEnd)
    }

    @Test
    fun einheitVorDerStreckeZuEndeBietetAusfahrenAn() {
        rideStorage.saveRide(ride("lang", "Lange Strecke", 30_000.0) { 0.0 })
        val short = TrainingSession(day = "", title = "Kurz", description = "", targetKm = 5, intensity = SessionIntensity.LOCKER, durationMin = 10)
        val vm = viewModel(TodayRoute(target(SessionIntensity.LOCKER), short, 5, 1.0, false, null))
        vm.toRoute()
        vm.preferDaySuggestion("ride:lang")
        vm.rideDaySuggestion()
        vm.startRide(withWorld = false)
        vm.releaseStart()
        vm.tick(9 * 600)
        assertNull(vm.ride.value?.dayEnd, "nach 9 Minuten laeuft die Einheit noch")
        vm.tick(2 * 600)
        val end = assertNotNull(vm.ride.value?.dayEnd)
        assertEquals(DayRideEnd.WORKOUT_DONE, end.kind)
        assertEquals(IndoorPhase.RUNNING, vm.ride.value?.session?.phase, "es wird weitergefahren")
        vm.dismissDayEnd() // „Ausfahren"
        vm.tick(10)
        assertNull(vm.ride.value?.dayEnd)
    }

    @Test
    fun ohneTagesentscheidungKeineKarte() {
        val vm = viewModel(null)
        vm.toRoute()
        assertNull(vm.day.value)
    }

    @Test
    fun ohnePassendeStreckeIstDieGenerierteWeltDerVorschlag() {
        rideStorage.saveRide(ride("flach", "Flache Runde", 30_000.0) { 0.0 })
        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        val gen = assertNotNull(offer.generated)
        assertEquals(IndoorDayPath.GENERATED, offer.path, "flache Strecke passt nicht zu Intervallen")
        assertEquals(3, gen.climbs)
        assertEquals(3, gen.reps)
        assertTrue(!gen.ready)

        vm.setDayPath(IndoorDayPath.REAL)
        assertEquals(IndoorDayPath.REAL, (vm.day.value as IndoorDayUi.Offer).path)
        vm.setDayPath(IndoorDayPath.GENERATED)

        vm.rideDayGenerated()
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertEquals(IndoorMode.ERG, vm.route.value.mode)
        val plan = assertIs<IndoorWorldUi.Plan>(vm.world.value)
        assertTrue(plan.hosts.isEmpty(), "ohne Netz")
        vm.loadWorld()
        assertEquals(1, generatedWorlds.builds)
        assertIs<IndoorWorldUi.Ready>(vm.world.value)
        val info = worlds.listWorlds().single()
        assertTrue(!worlds.isOrphaned(info, emptySet()), "generierte Welten sind nie verwaist")

        // Dieselbe Einheit ergibt dieselbe Welt: beim naechsten Mal liegt sie bereit und es geht direkt los.
        assertTrue(generatedWorlds.plans.distinct().size == 1)
        vm.startRide(withWorld = true)
        vm.releaseStart()
        vm.tick(10)
        assertEquals(IndoorMode.ERG, vm.ride.value?.session?.mode)
        vm.release()

        val again = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        again.toRoute()
        assertTrue(assertNotNull((again.day.value as IndoorDayUi.Offer).generated).ready)
        again.rideDayGenerated()
        assertEquals(IndoorStage.RIDE, again.stage.value, "fertige generierte Welt → direkt in die Fahrt")
        assertEquals(1, generatedWorlds.builds)
    }

    @Test
    fun passendeEchteStreckeBleibtVorschlagGenerierteWeltWaehlbar() {
        rideStorage.saveRide(ride("berge", "Drei Anstiege", 40_000.0, hilly))
        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        assertEquals(IndoorDayPath.REAL, offer.path)
        assertNotNull(offer.generated)
        vm.preferDaySuggestion(INDOOR_DAY_GENERATED_KEY)
        assertEquals(IndoorDayPath.GENERATED, (vm.day.value as IndoorDayUi.Offer).path)
    }

    @Test
    fun startDaySuggestion_readyWorld_autoStartsRide() {
        rideStorage.saveRide(ride("ready", "Fertige Runde", 40_000.0, hilly))
        val setup = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        setup.toRoute()
        val saved = setup.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().single { it.rideId == "ready" }
        setup.selectRoute(saved)
        setup.continueFromRoute()
        setup.loadWorld()
        assertIs<IndoorWorldUi.Ready>(setup.world.value)
        setup.release()

        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.setDemoTrainer(true)
        vm.ensureRouteOptions()
        vm.startDaySuggestion("ride:ready")

        assertEquals(IndoorStage.RIDE, vm.stage.value)
        vm.tick(1)
        assertEquals(IndoorMode.ERG, vm.ride.value?.session?.mode)
        assertNotNull(vm.renderer.value)
    }

    @Test
    fun startDaySuggestion_beforeDayLoads_autoStartsAfterLoad() {
        val setup = viewModel(null)
        setup.toRoute()
        setup.selectRoute(IndoorRouteOption.TestWorld)
        setup.continueFromRoute()
        setup.release()

        val vm = viewModel(TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null))
        vm.setDemoTrainer(true)
        vm.startDaySuggestion(IndoorRouteOption.TestWorld.key)
        assertEquals(IndoorStage.ROUTE, vm.stage.value)

        vm.ensureRouteOptions()
        assertEquals(IndoorStage.RIDE, vm.stage.value)
        assertEquals(IndoorRouteOption.TestWorld, vm.route.value.selected)
    }

    @Test
    fun startDaySuggestion_generatedKey_launchesGeneratedRide() {
        rideStorage.saveRide(ride("flach", "Flache Runde", 30_000.0) { 0.0 })
        val setup = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        setup.toRoute()
        setup.rideDayGenerated()
        setup.loadWorld()
        setup.release()

        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.setDemoTrainer(true)
        vm.ensureRouteOptions()
        vm.startDaySuggestion(INDOOR_DAY_GENERATED_KEY)

        assertEquals(IndoorStage.RIDE, vm.stage.value)
        vm.tick(1)
        assertEquals(IndoorMode.ERG, vm.ride.value?.session?.mode)
    }

    @Test
    fun startDaySuggestion_unpaired_opensPairAndResumesOnContinueFromPair() {
        val setup = viewModel(null)
        setup.toRoute()
        setup.selectRoute(IndoorRouteOption.TestWorld)
        setup.continueFromRoute()
        setup.release()

        val devices = kotlinx.coroutines.flow.MutableStateFlow<Map<de.trailscape.core.indoor.IndoorDeviceRole, de.trailscape.app.sensors.indoor.IndoorDeviceRef>>(emptyMap())
        val vm = IndoorViewModel(
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
            today = { TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null) },
            dayFinder = IndoorDayRouteFinder(rideStorage, worlds, null),
            generatedWorlds = generatedWorlds,
            devices = devices,
            io = Dispatchers.Unconfined,
            clock = clock,
            wallClock = { 1_765_000_000_000L },
            runtimeDispatcher = Dispatchers.Unconfined,
            autoLoop = false,
            scope = scope,
        ).also { vm = it }

        vm.ensureRouteOptions()
        vm.startDaySuggestion(IndoorRouteOption.TestWorld.key)
        assertEquals(IndoorStage.PAIR, vm.stage.value)
        assertTrue(vm.pairFromRideFlow.value)

        devices.value = mapOf(
            de.trailscape.core.indoor.IndoorDeviceRole.TRAINER to
                de.trailscape.app.sensors.indoor.IndoorDeviceRef(
                    de.trailscape.core.indoor.IndoorDeviceRole.TRAINER,
                    "AA:BB:CC:DD:EE:FF",
                    "KICKR CORE",
                ),
        )
        assertTrue(vm.pair.value.canContinue)
        vm.continueFromPair()
        assertEquals(IndoorStage.RIDE, vm.stage.value)
    }

    @Test
    fun manualOpenPair_doesNotAutoStartRideOnCloseSubPage() {
        val vm = viewModel(TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null))
        vm.ensureRouteOptions()
        vm.openPair()
        assertEquals(IndoorStage.PAIR, vm.stage.value)
        assertTrue(!vm.pairFromRideFlow.value)

        vm.setDemoTrainer(true)
        vm.closeSubPage()
        assertEquals(IndoorStage.ROUTE, vm.stage.value)
    }

    @Test
    fun continueFromRoute_unbuiltSaved_stopsAtWorldPlan() {
        rideStorage.saveRide(ride("unbuilt", "Ungebaute Runde", 20_000.0, hilly))
        val vm = viewModel(null)
        vm.toRoute()
        val saved = vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().single { it.rideId == "unbuilt" }
        vm.selectRoute(saved)
        vm.continueFromRoute()

        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertIs<IndoorWorldUi.Plan>(vm.world.value)
    }

    @Test
    fun startDaySuggestion_missingKey_doesNotAutoStartFirstSuggestion() {
        rideStorage.saveRide(ride("berge", "Drei Anstiege", 40_000.0, hilly))
        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        assertTrue(offer.suggestions.isNotEmpty())
        assertEquals(0, offer.index)

        vm.startDaySuggestion("missing:key")

        assertEquals(IndoorStage.ROUTE, vm.stage.value)
        assertNull(vm.route.value.selected)
    }

    @Test
    fun startDaySuggestion_bluetoothOffOrNoPermissionWithRememberedTrainer_opensPairAndResumesAfterFix() {
        val setup = viewModel(null)
        setup.toRoute()
        setup.selectRoute(IndoorRouteOption.TestWorld)
        setup.continueFromRoute()
        setup.release()

        val devices = kotlinx.coroutines.flow.MutableStateFlow(
            mapOf(
                de.trailscape.core.indoor.IndoorDeviceRole.TRAINER to
                    de.trailscape.app.sensors.indoor.IndoorDeviceRef(
                        de.trailscape.core.indoor.IndoorDeviceRole.TRAINER,
                        "AA:BB:CC:DD:EE:FF",
                        "KICKR CORE",
                    ),
            ),
        )
        val vm = IndoorViewModel(
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
            today = { TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null) },
            dayFinder = IndoorDayRouteFinder(rideStorage, worlds, null),
            generatedWorlds = generatedWorlds,
            devices = devices,
            io = Dispatchers.Unconfined,
            clock = clock,
            wallClock = { 1_765_000_000_000L },
            runtimeDispatcher = Dispatchers.Unconfined,
            autoLoop = false,
            scope = scope,
        ).also { vm = it }

        vm.updateBluetoothState(bluetoothOn = false, permissionGranted = true)
        vm.ensureRouteOptions()
        vm.startDaySuggestion(IndoorRouteOption.TestWorld.key)

        assertEquals(IndoorStage.PAIR, vm.stage.value)
        assertTrue(vm.pairFromRideFlow.value)

        vm.continueFromPair()
        assertEquals(IndoorStage.PAIR, vm.stage.value)

        vm.updateBluetoothState(bluetoothOn = true, permissionGranted = false)
        vm.continueFromPair()
        assertEquals(IndoorStage.PAIR, vm.stage.value)

        vm.updateBluetoothState(bluetoothOn = true, permissionGranted = true)
        vm.continueFromPair()
        assertEquals(IndoorStage.RIDE, vm.stage.value)
    }

    @Test
    fun startDaySuggestion_catalogWithDelayedStoreLoad_triggersDownload() {
        val worldSrc = File(tmp, "src-world")
        val manifest = createWorldBuilder().buildTestWorld(worldSrc, IndoorQuality.LOW)
        val pkg = File(tmp, "testrunde-v1.zip")
        writeCatalogPackage(worldSrc, "testrunde", 1, pkg)
        val sha = sha256Hex(pkg)

        val server = MockWebServer()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when (path) {
                        "/release/catalog.json" -> {
                            val entry = CatalogEntry(
                                id = "testrunde",
                                version = 1,
                                worldId = manifest.worldId,
                                name = CatalogText("Testrunde am See", "Test loop by the lake"),
                                region = CatalogText("Erfunden", "Made up"),
                                country = "DE",
                                kind = CatalogRideKind.GRAVEL,
                                lengthM = 3000.0,
                                ascentM = 40.0,
                                surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
                                description = CatalogText("Zum Testen.", "For tests."),
                                packageFile = pkg.name,
                                packageBytes = pkg.length(),
                                sha256 = sha,
                                aerial = false,
                            )
                            MockResponse().setBody(IndoorCatalog(server.url("/release/").toString(), 1, listOf(entry)).toJson())
                        }
                        "/release/${pkg.name}" -> MockResponse().setBody(Buffer().write(pkg.readBytes()))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/release/").toString()
            val allow: (String) -> Boolean = { it.startsWith(server.url("/").toString()) }
            val catalogJson = IndoorCatalog(
                base,
                0,
                listOf(
                    CatalogEntry(
                        id = "testrunde",
                        version = 1,
                        worldId = manifest.worldId,
                        name = CatalogText("Testrunde am See", "Test loop by the lake"),
                        region = CatalogText("Erfunden", "Made up"),
                        country = "DE",
                        kind = CatalogRideKind.GRAVEL,
                        lengthM = 3000.0,
                        ascentM = 40.0,
                        surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
                        description = CatalogText("Zum Testen.", "For tests."),
                        packageFile = pkg.name,
                        packageBytes = pkg.length(),
                        sha256 = sha,
                        aerial = false,
                    ),
                ),
            ).toJson()

            val releaseCatalogRead = CountDownLatch(1)
            val readCount = AtomicInteger(0)
            val delayedStore = CatalogStore(
                baseDir = File(tmp, "files/indoor/catalog"),
                worlds = worlds,
                worldsDir = File(tmp, "files/indoor/worlds"),
                bundled = {
                    if (readCount.incrementAndGet() == 1) {
                        releaseCatalogRead.await(2, TimeUnit.SECONDS)
                    }
                    catalogJson
                },
                allowUrl = allow,
            )
            val downloader = CatalogDownloader(OkHttpClient(), "TrailscapeTest/1.0", allow)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val vm = IndoorViewModel(
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
                    today = { TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null) },
                    dayFinder = IndoorDayRouteFinder(rideStorage, worlds, delayedStore),
                    generatedWorlds = generatedWorlds,
                    io = Dispatchers.IO,
                    clock = clock,
                    wallClock = { 1_765_000_000_000L },
                    runtimeDispatcher = Dispatchers.Unconfined,
                    autoLoop = false,
                    scope = ioScope,
                    catalogStore = delayedStore,
                    catalogDownloader = downloader,
                ).also { vm = it }

                vm.setDemoTrainer(true)
                vm.ensureRouteOptions()
                // Jetzt ist der Katalog-Plattenlesevorgang noch blockiert; erst loslassen,
                // nachdem startDaySuggestion bereits registriert wurde.
                vm.startDaySuggestion("catalog:testrunde")
                releaseCatalogRead.countDown()

                val deadline = System.currentTimeMillis() + 5_000L
                while (vm.stage.value != IndoorStage.RIDE && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25)
                }
                assertEquals(IndoorStage.RIDE, vm.stage.value)
                assertEquals("catalog:testrunde", vm.route.value.selected?.key)
                val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
                val catalogSuggestion = offer.suggestions.first { it.key == "catalog:testrunde" }
                assertEquals(DayRouteAvailability.READY, catalogSuggestion.availability)
                assertNull(catalogSuggestion.downloadBytes)
                assertTrue(server.requestCount >= 2)
            } finally {
                vm?.release()
                ioScope.cancel()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun downloadCatalog_beforeInitialLoadFinishes_stillDownloadsAndInstalls() {
        val worldSrc = File(tmp, "src-world-direct")
        val manifest = createWorldBuilder().buildTestWorld(worldSrc, IndoorQuality.LOW)
        val pkg = File(tmp, "testrunde-direct.zip")
        writeCatalogPackage(worldSrc, "testrunde", 1, pkg)
        val sha = sha256Hex(pkg)

        val server = MockWebServer()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when (path) {
                        "/release/catalog.json" -> {
                            val entry = CatalogEntry(
                                id = "testrunde",
                                version = 1,
                                worldId = manifest.worldId,
                                name = CatalogText("Testrunde am See", "Test loop by the lake"),
                                region = CatalogText("Erfunden", "Made up"),
                                country = "DE",
                                kind = CatalogRideKind.GRAVEL,
                                lengthM = 3000.0,
                                ascentM = 40.0,
                                surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
                                description = CatalogText("Zum Testen.", "For tests."),
                                packageFile = pkg.name,
                                packageBytes = pkg.length(),
                                sha256 = sha,
                                aerial = false,
                            )
                            MockResponse().setBody(IndoorCatalog(server.url("/release/").toString(), 1, listOf(entry)).toJson())
                        }
                        "/release/${pkg.name}" -> MockResponse().setBody(Buffer().write(pkg.readBytes()))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/release/").toString()
            val allow: (String) -> Boolean = { it.startsWith(server.url("/").toString()) }
            val catalogJson = IndoorCatalog(
                base,
                0,
                listOf(
                    CatalogEntry(
                        id = "testrunde",
                        version = 1,
                        worldId = manifest.worldId,
                        name = CatalogText("Testrunde am See", "Test loop by the lake"),
                        region = CatalogText("Erfunden", "Made up"),
                        country = "DE",
                        kind = CatalogRideKind.GRAVEL,
                        lengthM = 3000.0,
                        ascentM = 40.0,
                        surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
                        description = CatalogText("Zum Testen.", "For tests."),
                        packageFile = pkg.name,
                        packageBytes = pkg.length(),
                        sha256 = sha,
                        aerial = false,
                    ),
                ),
            ).toJson()

            val store = CatalogStore(
                baseDir = File(tmp, "files/indoor/catalog-direct"),
                worlds = worlds,
                worldsDir = File(tmp, "files/indoor/worlds"),
                bundled = { catalogJson },
                allowUrl = allow,
            )
            val downloader = CatalogDownloader(OkHttpClient(), "TrailscapeTest/1.0", allow)
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val vm = IndoorViewModel(
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
                    today = { null },
                    dayFinder = null,
                    generatedWorlds = generatedWorlds,
                    io = Dispatchers.IO,
                    clock = clock,
                    wallClock = { 1_765_000_000_000L },
                    runtimeDispatcher = Dispatchers.Unconfined,
                    autoLoop = false,
                    scope = ioScope,
                    catalogStore = store,
                    catalogDownloader = downloader,
                ).also { vm = it }

                // Direkt ohne vorheriges loadRouteOptions() aufrufen — catalog ist noch null.
                vm.downloadCatalog("testrunde")

                val deadline = System.currentTimeMillis() + 5_000L
                while (vm.catalog.value.items.firstOrNull { it.id == "testrunde" }?.ready != true &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(25)
                }
                assertTrue(vm.catalog.value.items.firstOrNull { it.id == "testrunde" }?.ready == true)
                assertTrue(server.requestCount >= 2)
            } finally {
                vm?.release()
                ioScope.cancel()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun rideDaySuggestion_catalogDownloadWithBluetoothOff_transitionsToReadyAndResumesWithoutRedownload() {
        val worldSrc = File(tmp, "src-world-pair-resume")
        val manifest = createWorldBuilder().buildTestWorld(worldSrc, IndoorQuality.LOW)
        val pkg = File(tmp, "testrunde-pair-resume.zip")
        writeCatalogPackage(worldSrc, "testrunde", 1, pkg)
        val sha = sha256Hex(pkg)
        val pkgRequests = AtomicInteger(0)

        val server = MockWebServer()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when (path) {
                        "/release/catalog.json" -> {
                            val entry = CatalogEntry(
                                id = "testrunde",
                                version = 1,
                                worldId = manifest.worldId,
                                name = CatalogText("Testrunde am See", "Test loop by the lake"),
                                region = CatalogText("Erfunden", "Made up"),
                                country = "DE",
                                kind = CatalogRideKind.GRAVEL,
                                lengthM = 3000.0,
                                ascentM = 40.0,
                                surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
                                description = CatalogText("Zum Testen.", "For tests."),
                                packageFile = pkg.name,
                                packageBytes = pkg.length(),
                                sha256 = sha,
                                aerial = false,
                            )
                            MockResponse().setBody(IndoorCatalog(server.url("/release/").toString(), 1, listOf(entry)).toJson())
                        }
                        "/release/${pkg.name}" -> {
                            pkgRequests.incrementAndGet()
                            MockResponse().setBody(Buffer().write(pkg.readBytes()))
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val base = server.url("/release/").toString()
            val allow: (String) -> Boolean = { it.startsWith(server.url("/").toString()) }
            val catalogJson = IndoorCatalog(
                base,
                0,
                listOf(
                    CatalogEntry(
                        id = "testrunde",
                        version = 1,
                        worldId = manifest.worldId,
                        name = CatalogText("Testrunde am See", "Test loop by the lake"),
                        region = CatalogText("Erfunden", "Made up"),
                        country = "DE",
                        kind = CatalogRideKind.GRAVEL,
                        lengthM = 3000.0,
                        ascentM = 40.0,
                        surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
                        description = CatalogText("Zum Testen.", "For tests."),
                        packageFile = pkg.name,
                        packageBytes = pkg.length(),
                        sha256 = sha,
                        aerial = false,
                    ),
                ),
            ).toJson()

            val catalogStore = CatalogStore(
                baseDir = File(tmp, "files/indoor/catalog-pair-resume"),
                worlds = worlds,
                worldsDir = File(tmp, "files/indoor/worlds"),
                bundled = { catalogJson },
                allowUrl = allow,
            )
            val downloader = CatalogDownloader(OkHttpClient(), "TrailscapeTest/1.0", allow)
            val devices = kotlinx.coroutines.flow.MutableStateFlow(
                mapOf(
                    de.trailscape.core.indoor.IndoorDeviceRole.TRAINER to
                        de.trailscape.app.sensors.indoor.IndoorDeviceRef(
                            de.trailscape.core.indoor.IndoorDeviceRole.TRAINER,
                            "AA:BB:CC:DD:EE:FF",
                            "KICKR CORE",
                        ),
                ),
            )
            val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val vm = IndoorViewModel(
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
                    today = { TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null) },
                    dayFinder = IndoorDayRouteFinder(rideStorage, worlds, catalogStore),
                    generatedWorlds = generatedWorlds,
                    devices = devices,
                    io = Dispatchers.IO,
                    clock = clock,
                    wallClock = { 1_765_000_000_000L },
                    runtimeDispatcher = Dispatchers.Unconfined,
                    autoLoop = false,
                    scope = ioScope,
                    catalogStore = catalogStore,
                    catalogDownloader = downloader,
                ).also { vm = it }

                vm.updateBluetoothState(bluetoothOn = false, permissionGranted = true)
                vm.ensureRouteOptions()

                val offerDeadline = System.currentTimeMillis() + 5_000L
                while (
                    (vm.day.value as? IndoorDayUi.Offer)?.suggestions?.any { it.key == "catalog:testrunde" } != true &&
                    System.currentTimeMillis() < offerDeadline
                ) {
                    Thread.sleep(25)
                }
                vm.preferDaySuggestion("catalog:testrunde")
                val initialOffer = assertIs<IndoorDayUi.Offer>(vm.day.value)
                assertEquals("catalog:testrunde", initialOffer.current?.key)
                assertEquals(DayRouteAvailability.DOWNLOAD, initialOffer.current?.availability)

                vm.rideDaySuggestion()

                val pairDeadline = System.currentTimeMillis() + 5_000L
                while (vm.stage.value != IndoorStage.PAIR && System.currentTimeMillis() < pairDeadline) {
                    Thread.sleep(25)
                }
                assertEquals(IndoorStage.PAIR, vm.stage.value)
                assertEquals(1, pkgRequests.get())
                assertTrue(vm.catalog.value.items.first { it.id == "testrunde" }.ready)
                val offerAfterDownload = assertIs<IndoorDayUi.Offer>(vm.day.value)
                val afterItem = offerAfterDownload.suggestions.first { it.key == "catalog:testrunde" }
                assertEquals(DayRouteAvailability.READY, afterItem.availability)
                assertNull(afterItem.downloadBytes)

                // Offline-Sicherheit: Server abschalten, bevor Bluetooth behoben wird.
                server.shutdown()
                vm.updateBluetoothState(bluetoothOn = true, permissionGranted = true)
                vm.continueFromPair()

                val rideDeadline = System.currentTimeMillis() + 5_000L
                while (vm.stage.value != IndoorStage.RIDE && System.currentTimeMillis() < rideDeadline) {
                    Thread.sleep(25)
                }
                assertEquals(IndoorStage.RIDE, vm.stage.value)
                assertEquals(1, pkgRequests.get())
                assertEquals(IndoorCatalogState.Ready, vm.catalog.value.items.first { it.id == "testrunde" }.state)
                assertEquals(
                    DayRouteAvailability.READY,
                    (vm.day.value as IndoorDayUi.Offer).suggestions.first { it.key == "catalog:testrunde" }.availability,
                )
            } finally {
                vm?.release()
                ioScope.cancel()
            }
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
