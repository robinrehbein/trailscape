package de.trailscape.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.indoor.testing.FakeWorldBuilder
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.record.RecordingService
import de.trailscape.app.record.merkeBatterieHinweisGezeigt
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.indoor.ContextIndoorTexts
import de.trailscape.app.ui.indoor.IndoorDayUi
import de.trailscape.app.ui.indoor.IndoorFlowMode
import de.trailscape.app.ui.indoor.IndoorFlowScreen
import de.trailscape.app.ui.indoor.IndoorGhostOption
import de.trailscape.app.ui.indoor.IndoorHomeScreen
import de.trailscape.app.ui.indoor.IndoorPairUi
import de.trailscape.app.ui.indoor.IndoorRouteOption
import de.trailscape.app.ui.indoor.IndoorRouteUi
import de.trailscape.app.ui.indoor.IndoorStage
import de.trailscape.app.ui.indoor.IndoorViewModel
import de.trailscape.app.ui.indoor.IndoorWorldUi
import de.trailscape.app.ui.rides.RideDetailScreen
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.map.MapScreen
import de.trailscape.app.ui.map.PlanningSheet
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.map.RouteGenerationController
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.core.AscentPreference
import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.Waypoint
import de.trailscape.core.WeekKind
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.DayTerrain
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.loadPlan
import de.trailscape.core.savePlan
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * E2E-Pruefung der 5 Kernwege auf Tipp-Obergrenzen (R4) sowie der
 * nahtlosen `MapScreen ↔ RideModeScreen`-Integration auf `Expanded`:
 *  - Pfad 1 (Erste Indoor-Fahrt): <= 2 Tipps ab Indoor, <= 3 Tipps ab Heute (inkl. Kaltstart-Koppeln)
 *  - Pfad 2 (Route planen & losfahren): <= 4 Tipps ab Karte (2a Wegpunkte, 2b Runde bauen, 2c Ortskarte)
 *  - Pfad 3 (Trainingseinheit heute): = 1 Tipp ab Heute (3a Indoor DayCard, 3b Outdoor HeroCard)
 *  - Pfad 4 (Nach der Fahrt): <= 2 Tipps (4a Indoor-Auswertung, 4b Outdoor Halten -> Tour-Karte)
 *  - Pfad 5 (Verlauf): <= 3 Tipps (Compact = 3, Expanded = 2)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class CorePathsTapBudgetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var savedRides: List<Ride> = emptyList()
    private var savedPlan: TrainingPlan? = null
    private var prevOnboarding: String? = null
    private var prevLongPressHint: String? = null
    private var prevDemoTrainer: String? = null
    private var prevRoutingServerUrl: String = ""
    private var createdTestWorldDir: File? = null
    private var createdCatalogWorldDir: File? = null
    private var mockServer: MockWebServer? = null

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runCatching {
            WorkManager.initialize(
                app,
                Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build(),
            )
        }
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = Shadows.shadowOf(locationManager)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowLm.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 48.40
                longitude = 9.40
                accuracy = 5f
                time = System.currentTimeMillis()
            },
        )
        merkeBatterieHinweisGezeigt(app)

        val kv = AppServices.keyValueStore
        prevOnboarding = kv.getString(ONBOARDING_STORAGE_KEY)
        prevLongPressHint = kv.getString(LONG_PRESS_HINT_STORAGE_KEY)
        prevDemoTrainer = kv.getString(IndoorViewModel.KEY_DEMO_TRAINER)
        prevRoutingServerUrl = AppServices.routingServerSettings.url

        kv.setString(ONBOARDING_STORAGE_KEY, "1")
        kv.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        kv.remove(IndoorViewModel.KEY_DEMO_TRAINER)

        val storage = AppServices.rideStorage
        val ids = storage.listSummaries().summaries.map { it.id }
        savedRides = ids.mapNotNull { storage.loadRide(it) }
        ids.forEach { storage.deleteRide(it) }

        savedPlan = loadPlan(AppServices.trainingPlanStore)
        savePlan(AppServices.trainingPlanStore, null)

        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        mockServer?.shutdown()
        mockServer = null

        val storage = AppServices.rideStorage
        storage.listSummaries().summaries.forEach { storage.deleteRide(it.id) }
        if (savedRides.isNotEmpty()) storage.saveRides(savedRides)
        savePlan(AppServices.trainingPlanStore, savedPlan)

        AppServices.routingServerSettings.url = prevRoutingServerUrl
        restoreKey(ONBOARDING_STORAGE_KEY, prevOnboarding)
        restoreKey(LONG_PRESS_HINT_STORAGE_KEY, prevLongPressHint)
        restoreKey(IndoorViewModel.KEY_DEMO_TRAINER, prevDemoTrainer)
        createdTestWorldDir?.deleteRecursively()
        createdTestWorldDir = null
        createdCatalogWorldDir?.deleteRecursively()
        createdCatalogWorldDir = null
    }

    private fun restoreKey(key: String, previous: String?) {
        if (previous == null) AppServices.keyValueStore.remove(key) else AppServices.keyValueStore.setString(key, previous)
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun settle(advanceMs: Long = 1_500L) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(advanceMs)
        compose.waitForIdle()
    }

    private fun ensureTestWorldReady() {
        val dir = AppServices.indoorWorldLibrary.testWorldDir()
        if (!File(dir, "manifest.json").isFile) {
            val manifest = FakeWorldBuilder.manifestFor(
                worldId = "testwelt",
                route = FakeWorldBuilder.testLoopRoute(),
                quality = IndoorQuality.LOW,
            )
            FakeWorldBuilder.writeManifest(dir, manifest)
            createdTestWorldDir = dir
        }
    }

    private fun flat24kmLoopRoute(): de.trailscape.core.indoor.IndoorRoute {
        val count = 2413
        val stepM = 10.0
        val lengthM = (count - 1) * stepM
        val radiusLat = 0.0345
        val radiusLon = 0.0525
        val points = List(count) { i ->
            val angle = i.toDouble() / (count - 1) * 2.0 * Math.PI
            de.trailscape.core.indoor.IndoorRoutePoint(
                distM = i * stepM,
                lat = 48.93 + radiusLat * sin(angle),
                lon = 9.26 + radiusLon * cos(angle),
                eleM = 250.0,
            )
        }
        return de.trailscape.core.indoor.IndoorRoute(
            id = "marbach-lemberg",
            name = "Marbach und Lemberg",
            points = points,
            surfaces = listOf(
                de.trailscape.core.indoor.SurfaceSection(0.0, lengthM, de.trailscape.core.indoor.SurfaceKind.ASPHALT),
            ),
            isLoop = true,
            source = de.trailscape.core.indoor.IndoorRouteSource.BROUTER_WAY_TAGS,
        )
    }

    private fun ensureDayCatalogWorldReady() {
        val worldId = "wdace7a7886e2e6ae28ace70e"
        val dir = File(AppServices.indoorWorldLibrary.testWorldDir().parentFile, worldId)
        if (!File(dir, "manifest.json").isFile) {
            val manifest = FakeWorldBuilder.manifestFor(
                worldId = worldId,
                route = flat24kmLoopRoute(),
                quality = IndoorQuality.LOW,
            )
            FakeWorldBuilder.writeManifest(dir, manifest)
            de.trailscape.app.indoor.world.WorldLibrary.writeCatalogMarker(dir, "marbach-lemberg", 3)
            createdCatalogWorldDir = dir
        }
    }

    private fun startMockBrouterServer() {
        val geoJson = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "geometry": {
                  "type": "LineString",
                  "coordinates": [[9.40, 48.40, 500.0], [9.44, 48.44, 540.0], [9.48, 48.48, 580.0]]
                },
                "properties": {
                  "track-length": "12500",
                  "filtered ascend": "160"
                }
              }]
            }
        """.trimIndent()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(geoJson)
        }
        server.start()
        mockServer = server
        AppServices.routingServerSettings.url = server.url("/brouter").toString()
    }

    private fun samplePoints(lat0: Double = 48.40, lon0: Double = 9.40): List<TrackPoint> =
        List(50) { i ->
            val t = i / 49.0 * 2 * Math.PI
            TrackPoint(
                lat = lat0 + 0.02 * sin(t),
                lon = lon0 + 0.03 * cos(t),
                ele = 520.0 + 35.0 * sin(2 * t),
                time = 1_730_000_000_000L + i * 20_000L,
                hr = 138,
            )
        }

    private fun samplePlannedRoute(): PlannedRoute =
        PlannedRoute(samplePoints(), distanceKm = 24.5, ascentM = 310.0)

    private fun sampleCandidate(): RouteCandidate = RouteCandidate(
        route = samplePlannedRoute(),
        distanceKm = 24.5,
        ascentM = 310.0,
        score = 0.0,
        bearingDeg = 45.0,
        targetKm = 25.0,
    )

    private fun sampleRides(): List<Ride> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        return listOf(
            Ride(
                id = "cp-ride-1",
                name = "Feierabendrunde",
                createdAt = now - 2 * dayMs,
                stats = RideStats(
                    distanceKm = 32.4,
                    ascentM = 292.0,
                    descentM = 290.0,
                    durationS = 5_040,
                    movingTimeS = 4_920,
                    avgSpeedKmh = 23.1,
                    avgHrBpm = 140,
                    maxHrBpm = 170,
                ),
                points = samplePoints(),
            ),
            Ride(
                id = "cp-ride-2",
                name = "GA1 Kanalrunde",
                createdAt = now - 5 * dayMs,
                stats = RideStats(
                    distanceKm = 46.1,
                    ascentM = 415.0,
                    descentM = 415.0,
                    durationS = 7_200,
                    movingTimeS = 7_050,
                    avgSpeedKmh = 23.0,
                    avgHrBpm = 132,
                    maxHrBpm = 160,
                ),
                points = samplePoints(48.44, 9.45),
            ),
        )
    }

    private fun activeTodayPlan(
        restToday: Boolean = false,
        durationMin: Int = 80,
        targetKm: Int = 36,
    ): TrainingPlan {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val codes = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val todayCode = codes[today.dayOfWeek.value - 1]
        val startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val goalMs = monday.plusWeeks(8).plusDays(5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val sessions = if (restToday) {
            listOf(
                TrainingSession(
                    day = codes[(today.dayOfWeek.value) % 7],
                    title = "Grundlage",
                    description = "Ruhig fahren.",
                    targetKm = 40,
                    intensity = SessionIntensity.GRUNDLAGE,
                    durationMin = 90,
                ),
            )
        } else {
            listOf(
                TrainingSession(
                    day = todayCode,
                    title = "Grundlage",
                    description = "Ruhig fahren.",
                    targetKm = targetKm,
                    intensity = SessionIntensity.GRUNDLAGE,
                    durationMin = durationMin,
                ),
            )
        }
        return TrainingPlan(
            createdAt = startMs,
            goal = Goal(name = "Alb-Gold", distanceKm = 60.0, ascentM = 700.0, date = goalMs, targetDurationMin = 130),
            level = FitnessLevel.entries.first(),
            weeks = listOf(
                TrainingWeek(
                    index = 0,
                    start = startMs,
                    end = endMs,
                    kind = WeekKind.AUFBAU,
                    targetKm = 95,
                    sessions = sessions,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ Path 1

    @Test
    fun path1a_indoorHome_dayCardAndRoutePick_within2Taps() {
        var dayRides = 0
        var routeRides = 0
        var selected by mutableStateOf<IndoorRouteOption?>(null)
        val dayOffer = IndoorDayUi.Offer(
            kind = DaySessionKind.PLANNED,
            title = "Grundlage",
            minutes = 60,
            plannedMinutes = 60,
            terrain = DayTerrain.FLAT,
            suggestions = listOf(
                de.trailscape.app.ui.indoor.IndoorDaySuggestionUi(
                    key = IndoorRouteOption.TestWorld.key,
                    name = string(R.string.indoor_app_route_testworld_title),
                    availability = DayRouteAvailability.READY,
                    rideKm = 3.0,
                ),
            ),
            index = 0,
        )

        compose.setContent {
            CompositionLocalProvider(
                LocalWindowLayoutClass provides windowLayoutClassFor(LocalConfiguration.current.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            IndoorHomeScreen(
                                route = IndoorRouteUi(
                                    options = listOf(IndoorRouteOption.TestWorld),
                                    selected = selected,
                                    mode = IndoorMode.SIM,
                                    ergTitle = "Grundlage",
                                    ergMinutes = 60,
                                    ghost = IndoorGhostOption.None,
                                    ghostPastRideAvailable = false,
                                    defaultGhostWatts = 180,
                                ),
                                pair = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE", shifterName = null),
                                trainerIssue = null,
                                onSelect = { selected = it },
                                onModeChange = {},
                                onGhostChange = {},
                                onRide = { routeRides++ },
                                onBack = null,
                                onOpenPair = {},
                                onOpenSettings = {},
                                day = dayOffer,
                                onDayRide = { dayRides++ },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // 1a-1: DayCard leads -> 1 tap on "Fahren"
        var dayTaps = 0
        compose.onNodeWithText(string(R.string.indoor_app_day_ride_action)).performClick()
        dayTaps++
        compose.waitForIdle()
        assertEquals(1, dayRides)
        assertTrue("Path 1 DayCard must be <= 2 taps (was $dayTaps)", dayTaps <= 2)

        // 1a-2: Pick route in list -> tap "Fahren" -> 2 taps
        var listTaps = 0
        compose.onAllNodes(
            hasText(string(R.string.indoor_app_route_testworld_title)) and hasClickAction(),
        ).onFirst().performClick()
        listTaps++
        compose.waitForIdle()
        compose.onAllNodes(
            hasText(string(R.string.indoor_app_home_ride_action)) and hasClickAction(),
        ).onLast().performClick()
        listTaps++
        compose.waitForIdle()
        assertEquals(1, routeRides)
        assertTrue("Path 1 route pick + ride must be <= 2 taps (was $listTaps)", listTaps <= 2)
    }

    @Test
    fun path1a_indoorTab_realApp_testWorldWithin2Taps() {
        ensureTestWorldReady()
        AppServices.keyValueStore.setString(IndoorViewModel.KEY_DEMO_TRAINER, "true")

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.INDOOR)
        settle()

        val testWorldLabel = string(R.string.indoor_app_route_testworld_title)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(testWorldLabel).fetchSemanticsNodes().isNotEmpty()
        }

        var taps = 0
        compose.onAllNodesWithText(testWorldLabel).onFirst().performClick()
        taps++
        settle(500)

        val rideAction = string(R.string.indoor_app_home_ride_action)
        compose.onNodeWithText(rideAction).assertIsEnabled().performClick()
        taps++

        val pauseCd = string(R.string.indoor_app_ride_pause_action)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription(pauseCd).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Path 1 from Indoor tab must enter IndoorRideScreen in <= 2 taps (was $taps)", taps <= 2)
    }

    @Test
    fun path1c_todayScreen_coldPairToIndoorRide_within3Taps() {
        ensureTestWorldReady()
        ensureDayCatalogWorldReady()
        savePlan(AppServices.trainingPlanStore, activeTodayPlan())
        // No paired trainer & demo off -> triggers cold-pair flow on tap 1.
        AppServices.keyValueStore.remove(IndoorViewModel.KEY_DEMO_TRAINER)

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()

        compose.onNode(hasScrollToNodeAction()).performScrollToKey("indoor")
        settle(500)

        val dayEyebrow = string(R.string.indoor_app_day_eyebrow)
        val rideLabel = string(R.string.indoor_app_day_ride_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(dayEyebrow).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(rideLabel).fetchSemanticsNodes().isNotEmpty()
        }

        var taps = 0
        // Tap 1: DayCard "Fahren" -> scroll past bottom nav bar so click hits DayCard, not floating nav pill
        compose.onNode(hasScrollToNodeAction()).performScrollToKey("sec-ziel")
        settle(300)
        compose.onAllNodesWithText(rideLabel).onFirst().performScrollTo().performClick()
        taps++
        settle(500)
        val demoTitle = string(R.string.indoor_app_pair_demo_title)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(demoTitle).fetchSemanticsNodes().isNotEmpty()
        }

        // Tap 2: Enable Demo-Trainer
        compose.onNodeWithText(demoTitle).performClick()
        taps++
        settle(500)

        // Tap 3: Tap "Fertig" -> continueFromPair -> enters IndoorRideScreen
        compose.onNodeWithText(string(R.string.indoor_app_action_done)).performClick()
        taps++

        val pauseCd = string(R.string.indoor_app_ride_pause_action)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription(pauseCd).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("Path 1 cold-pair from TodayScreen must reach IndoorRideScreen in <= 3 taps (was $taps)", taps <= 3)
    }

    // ------------------------------------------------------------------ Path 2

    @Test
    fun path2a_mapScreen_planRouteAndPeekStart_within4Taps() {
        startMockBrouterServer()
        val appViewModel = AppViewModel()
        compose.setContent {
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides windowLayoutClassFor(LocalConfiguration.current.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        settle()

        var taps = 0
        // Tap 1: "Route planen" on ExploreSheet opens PlanningSheet
        compose.onNodeWithText(string(R.string.map_explore_plan_route_action)).performClick()
        taps++
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.map_planning_add_start_action)).assertIsDisplayed()

        // Tap 2: First map tap sets waypoint 1 and collapses PlanningSheet to Peek
        compose.onNodeWithTag("map_screen_map_pane").performTouchInput {
            click(Offset(width * 0.30f, height * 0.25f))
        }
        taps++
        compose.waitForIdle()

        // Tap 3: Second map tap sets waypoint 2 and triggers routing
        compose.onNodeWithTag("map_screen_map_pane").performTouchInput {
            click(Offset(width * 0.65f, height * 0.35f))
        }
        taps++

        val startAction = string(R.string.map_planning_start_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(startAction).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        // Tap 4: "Losfahren" directly in Peek PlanningSheet starts active navigation
        compose.onNodeWithText(startAction)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        taps++
        compose.waitForIdle()

        compose.onNodeWithContentDescription(string(R.string.map_nav_stop_cd)).assertIsDisplayed()
        assertEquals("Path 2a must complete in 4 taps", 4, taps)
        assertTrue("Path 2a total taps must be <= 4 (was $taps)", taps <= 4)
    }

    @Test
    fun path2b_mapScreen_roundTripBuildApplyStart_within4Taps() {
        val appViewModel = AppViewModel()
        compose.setContent {
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides windowLayoutClassFor(LocalConfiguration.current.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        settle()

        var taps = 0
        // Tap 1: "Runde bauen"
        compose.onNodeWithText(string(R.string.map_explore_build_loop_action)).performClick()
        taps++
        compose.waitForIdle()

        // Tap 2: "Vorschläge zeigen"
        compose.onNodeWithText(string(R.string.map_round_trip_show_action)).performClick()
        taps++
        compose.waitForIdle()

        RouteGenerationController.publishTestCandidates(listOf(sampleCandidate()), selectedIndex = 0)
        compose.waitForIdle()

        // Tap 3: "Übernehmen" -> expands PlanningSheet with "Losfahren" immediately clickable (no Snackbar occlusion)
        compose.onNodeWithText(string(R.string.map_generation_apply_action)).assertIsDisplayed().performClick()
        taps++
        compose.waitForIdle()

        // Tap 4: "Losfahren" immediately without waiting 4.5s for Snackbar dismissal
        compose.onNodeWithText(string(R.string.map_planning_start_action)).assertIsDisplayed().performClick()
        taps++
        settle(1_000L)

        compose.onNodeWithContentDescription(string(R.string.map_nav_stop_cd)).assertIsDisplayed()
        assertTrue("Path 2b must complete in <= 4 taps (was $taps)", taps <= 4)
    }

    @Test
    fun path2c_mapScreen_placeCardRouteHereAndStart_within4Taps() {
        startMockBrouterServer()
        val appViewModel = AppViewModel()
        compose.setContent {
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides windowLayoutClassFor(LocalConfiguration.current.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        settle()
        // Seed after AppViewModel.init's async restore completes so it is not overwritten.
        appViewModel.recordPlaceSearchHistory(
            PlaceSearchHistoryEntry("Tübingen, Baden-Württemberg", 48.52, 9.05),
        )
        settle(500L)

        var taps = 0
        // Focus search & pick recent place (simulates picking place on map / search -> 1 tap from Place)
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNode(hasSetTextAction()).performClick()
        settle(500)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Tübingen", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Tap 1: Select Place -> PlaceCard opens
        compose.onAllNodesWithText("Tübingen", substring = true).onFirst().performClick()
        taps++
        compose.waitForIdle()

        // Tap 2: "Route hierher" -> PlanningSheet with computed route
        compose.onNodeWithText(string(R.string.map_place_route_here_action)).assertIsDisplayed().performClick()
        taps++

        val startAction = string(R.string.map_planning_start_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(startAction).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        // Tap 3: "Losfahren"
        compose.onNodeWithText(startAction).assertIsEnabled().performClick()
        taps++
        compose.waitForIdle()

        compose.onNodeWithContentDescription(string(R.string.map_nav_stop_cd)).assertIsDisplayed()
        assertTrue("Path 2c from Place selection must be <= 4 taps (was $taps)", taps <= 4)
    }

    // ------------------------------------------------------------------ Path 3

    @Test
    fun path3a_todayScreen_indoorDayCard_oneTapToRide() {
        ensureTestWorldReady()
        ensureDayCatalogWorldReady()
        AppServices.keyValueStore.setString(IndoorViewModel.KEY_DEMO_TRAINER, "true")
        savePlan(AppServices.trainingPlanStore, activeTodayPlan())

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()

        compose.onNode(hasScrollToNodeAction()).performScrollToKey("indoor")
        settle(500)

        val rideLabel = string(R.string.indoor_app_day_ride_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(string(R.string.indoor_app_day_eyebrow)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(rideLabel).fetchSemanticsNodes().isNotEmpty()
        }

        var taps = 0
        compose.onNode(hasScrollToNodeAction()).performScrollToKey("sec-ziel")
        settle(300)
        compose.onAllNodesWithText(rideLabel).onFirst().performScrollTo().performClick()
        taps++
        settle(500)

        val pauseCd = string(R.string.indoor_app_ride_pause_action)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription(pauseCd).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Path 3a from TodayScreen must take exactly 1 tap", 1, taps)
    }

    @Test
    fun path3b_todayScreen_outdoorHeroCard_oneTapAutoStartsNavigation() {
        AppServices.rideStorage.saveRides(sampleRides())
        savePlan(AppServices.trainingPlanStore, activeTodayPlan())

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()

        val offerAction = string(R.string.today_offer_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(offerAction).fetchSemanticsNodes().isNotEmpty()
        }

        var taps = 0
        compose.onAllNodesWithText(offerAction).onFirst().performClick()
        taps++

        compose.waitUntil(timeoutMillis = 10_000) {
            RouteGenerationController.state.value.target != null
        }
        RouteGenerationController.publishTestCandidates(listOf(sampleCandidate()), selectedIndex = 0)
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(string(R.string.map_nav_stop_cd)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Path 3b from TodayScreen must take exactly 1 tap", 1, taps)
    }

    // ------------------------------------------------------------------ Path 4

    @Test
    fun path4a_indoorRideFinish_saveAndViewTour_within2Taps() {
        ensureTestWorldReady()
        val appViewModel = AppViewModel()
        var clockMs = 1_000L
        val vm = IndoorViewModel(
            parts = AppServices.indoorParts,
            rideStorage = AppServices.rideStorage,
            worlds = AppServices.indoorWorldLibrary,
            store = AppServices.keyValueStore,
            texts = ContextIndoorTexts(
                context = { AppServices.localizedContext() },
                language = { AppServices.appLanguage.value },
            ),
            profile = { appViewModel.profile.value },
            plan = { appViewModel.plan.value },
            coreTexts = { AppServices.coreTexts() },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            clock = { clockMs },
            wallClock = { 1_765_000_000_000L },
            runtimeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            autoLoop = false,
            onRideSaved = appViewModel::onIndoorRideSaved,
        )
        vm.setDemoTrainer(true)
        vm.ensureRouteOptions()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()

        compose.waitUntil(timeoutMillis = 15_000) {
            vm.stage.value == IndoorStage.RIDE && vm.activeRuntime != null
        }
        vm.releaseStart()
        val runtime = vm.activeRuntime!!
        repeat(700) {
            clockMs += 100L
            runtime.tickOnce()
        }

        var openedRide by mutableStateOf<Ride?>(null)
        compose.setContent {
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides windowLayoutClassFor(LocalConfiguration.current.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        LaunchedEffect(appViewModel) {
                            appViewModel.pendingRideDetail.collect { rideId ->
                                if (rideId != null) {
                                    openedRide = AppServices.rideStorage.loadRide(rideId)
                                    appViewModel.consumeRideDetailRequest()
                                }
                            }
                        }
                        Surface(Modifier.fillMaxSize()) {
                            val detail = openedRide
                            if (detail != null) {
                                RideDetailScreen(
                                    ride = detail,
                                    appViewModel = appViewModel,
                                    snackbarHostState = null,
                                    onBack = {},
                                    onShowOnMap = {},
                                    onRideAgain = {},
                                    onRename = {},
                                    onShare = {},
                                    onDelete = {},
                                )
                            } else {
                                IndoorFlowScreen(
                                    appViewModel = appViewModel,
                                    onExit = {},
                                    onOpenDiagnostics = {},
                                    mode = IndoorFlowMode.TAB,
                                    vmOverride = vm,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // End active IndoorRideScreen via real touch press-and-hold -> IndoorSummaryScreen
        compose.onNodeWithContentDescription(string(R.string.indoor_app_hud_finish_cd))
            .performTouchInput {
                down(center)
                advanceEventTime(1_300L)
                up()
            }
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.stage.value == IndoorStage.SUMMARY
        }
        compose.waitForIdle()

        // Tap 1: "Speichern"
        var taps = 0
        compose.onNodeWithText(string(R.string.indoor_app_summary_save_action))
            .assertIsDisplayed()
            .performClick()
        taps++
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(string(R.string.indoor_app_summary_open_tour_action))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap 2: "Tour ansehen" -> RideDetailScreen
        compose.onNodeWithText(string(R.string.indoor_app_summary_open_tour_action))
            .assertIsDisplayed()
            .performClick()
        taps++
        compose.waitUntil(timeoutMillis = 10_000) { openedRide != null }
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.rides_detail_ride_again_action)).assertIsDisplayed()
        assertTrue("Path 4a post-ride save + open tour must be <= 2 taps (was $taps)", taps <= 2)
    }

    @Test
    fun path4b_outdoorHoldToEnd_autoSavesAndOpensRideDetail_within2Taps() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val finishedRide = sampleRides().first()
        AppServices.rideStorage.saveRide(finishedRide)
        RecordingRepository.attach(app)
        RecordingRepository.publishStarted(
            startedAtMs = System.currentTimeMillis() - 3_600_000L,
            points = finishedRide.points,
            paused = false,
        )

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.MAP)
        settle()

        var actions = 0
        // Action 1: Genuine touch press-and-hold on HoldToEndButton in LiveRecordingCard
        compose.onNodeWithContentDescription(string(R.string.shell_hold_to_end_cd))
            .performTouchInput {
                down(center)
                advanceEventTime(1_300L)
                up()
            }
        actions++
        compose.waitForIdle()
        val stopIntent = Shadows.shadowOf(app).nextStartedService
        assertEquals(RecordingService.ACTION_STOP, stopIntent?.action)
        RecordingRepository.publishStopped(finishedRide.id)
        settle()

        val detailsAction = string(R.string.map_ride_card_details_action)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(detailsAction).fetchSemanticsNodes().isNotEmpty()
        }
        // Dismiss any active save Snackbar if overlapping, then Action 2: Click "Auswertung" -> RideDetailScreen
        compose.onNodeWithText(detailsAction).assertIsDisplayed().performClick()
        actions++
        settle()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(string(R.string.rides_detail_ride_again_action)).assertIsDisplayed()
        assertTrue("Path 4b HoldToEnd -> RideDetail must be <= 2 actions (was $actions)", actions <= 2)
    }

    // ------------------------------------------------------------------ Path 5

    @Test
    fun path5_ridesScreen_compact_within3Taps() {
        AppServices.rideStorage.saveRides(sampleRides())
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.RIDES)
        settle()

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Feierabendrunde").fetchSemanticsNodes().isNotEmpty()
        }

        var taps = 0
        // Tap 1: Open ride detail
        compose.onAllNodesWithText("Feierabendrunde").onFirst().performClick()
        taps++
        settle(500)

        // Tap 2: "Nochmal fahren"
        compose.onAllNodesWithText(string(R.string.rides_detail_ride_again_action)).onFirst().performClick()
        taps++
        settle(500)

        // Tap 3: Choose option ("Indoor fahren")
        compose.onAllNodesWithText(string(R.string.rides_again_indoor_title)).onFirst().performClick()
        taps++
        settle(500)

        assertTrue("Path 5 Compact must complete in <= 3 taps (was $taps)", taps <= 3)
        compose.onAllNodesWithText(string(R.string.rides_again_indoor_title)).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun path5_ridesScreen_expandedMasterDetail_within2Taps() {
        AppServices.rideStorage.saveRides(sampleRides())
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.RIDES)
        settle()

        val rideAgainAction = string(R.string.rides_detail_ride_again_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Feierabendrunde").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(rideAgainAction).fetchSemanticsNodes().isNotEmpty()
        }

        // First ride is auto-selected in Master-Detail (0 taps to open detail)
        var taps = 0
        // Tap 1: "Nochmal fahren"
        compose.onAllNodesWithText(rideAgainAction).onFirst().performClick()
        taps++
        settle(500)

        // Tap 2: Choose option ("Draußen fahren")
        compose.onAllNodesWithText(string(R.string.rides_again_outdoor_title)).onFirst().performClick()
        taps++
        settle(500)

        assertTrue("Path 5 Expanded auto-selected ride must complete in <= 3 taps (was $taps)", taps <= 3)
        assertEquals(2, taps)
    }

    // ------------------------------------------------- Map <-> RideMode Expanded

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun mapAndRideMode_expandedTwoPane_inlineMountToggleAndStop() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val finishedRide = sampleRides().first()
        AppServices.rideStorage.saveRide(finishedRide)
        val appViewModel = AppViewModel()
        RecordingRepository.attach(app)

        compose.setContent {
            val language = languageOf(LocalConfiguration.current)
            LaunchedEffect(language) { AppServices.setAppLanguage(language) }
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides WindowLayoutClass.Expanded,
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        settle()

        // Start navigated roundtrip so NavigationPanel + RideCompactBar appear
        compose.onNodeWithText(string(R.string.map_explore_build_loop_action)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.map_round_trip_show_action)).performClick()
        compose.waitForIdle()
        RouteGenerationController.publishTestCandidates(listOf(sampleCandidate()), selectedIndex = 0)
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.map_generation_apply_action)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.map_planning_start_action)).performClick()
        settle(500L)
        assertEquals(RecordingService.ACTION_START, Shadows.shadowOf(app).nextStartedService?.action)
        Shadows.shadowOf(app).clearStartedServices()

        // Activate recording state -> NAVI_KARTE shows RideCompactBar
        RecordingRepository.publishStarted(
            startedAtMs = System.currentTimeMillis() - 120_000L,
            points = finishedRide.points,
            paused = false,
        )
        settle()

        val dataAction = string(R.string.map_compact_data_action)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(dataAction).fetchSemanticsNodes().isNotEmpty()
        }

        // Switch to DATEN -> RideModeScreen mounts INLINE (useDialog = false)
        compose.onNodeWithText(dataAction).performClick()
        settle()

        // 1. Not a separate Dialog window
        compose.onAllNodes(isDialog()).assertCountEquals(0)

        // 2. MapScreen underlying Scaffold overlays suppressed -> ZERO duplicate live nodes!
        val pauseLabel = string(R.string.map_ride_pause_action)
        val showMapLabel = string(R.string.map_ride_show_map_action)
        val holdCd = string(R.string.shell_hold_to_end_cd)
        compose.onAllNodesWithText(pauseLabel).assertCountEquals(1)
        compose.onNodeWithText(pauseLabel).assertIsDisplayed()
        compose.onNodeWithText(showMapLabel).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(holdCd).assertCountEquals(1)
        compose.onNodeWithContentDescription(holdCd).assertIsDisplayed()

        // Distinct mapPane testTag proves real MapViewHost is mounted instead of RideModeRightPaneFallback
        compose.onNodeWithTag("map_screen_map_pane").assertIsDisplayed()
        compose.onNodeWithTag("ride_mode_map_content").assertIsDisplayed()
        compose.onAllNodesWithTag("ride_mode_right_pane_fallback").assertCountEquals(0)
        compose.onAllNodesWithContentDescription(string(R.string.map_screen_planned_route_label), substring = true)
            .assertCountEquals(1)

        // Check 88 dp minimum height on HoldToEndButton in RideModeScreen
        val density = compose.density
        val holdNode = compose.onNodeWithContentDescription(holdCd).fetchSemanticsNode()
        val holdHeightDp = with(density) { holdNode.size.height.toDp() }
        assertTrue(
            "HoldToEndButton height in RideModeScreen must be >= 88.dp (was $holdHeightDp)",
            holdHeightDp >= 88.dp,
        )

        // 3. Clicking "Karte" (onShowMap) switches to NAVI_KARTE with RideCompactBar
        compose.onNodeWithText(showMapLabel).performClick()
        settle()
        compose.onNodeWithText(dataAction).assertIsDisplayed()
        compose.onAllNodesWithText(showMapLabel).assertCountEquals(0)

        // 4. Switch back to DATEN and perform genuine press-and-hold on HoldToEndButton -> stops recording & shows RideCard
        compose.onNodeWithText(dataAction).performClick()
        settle()
        compose.onNodeWithContentDescription(holdCd).performTouchInput {
            down(center)
            advanceEventTime(1_300L)
            up()
        }
        compose.waitForIdle()
        assertEquals(RecordingService.ACTION_STOP, Shadows.shadowOf(app).nextStartedService?.action)
        RecordingRepository.publishStopped(finishedRide.id)
        settle()

        val detailsAction = string(R.string.map_ride_card_details_action)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(detailsAction).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(detailsAction).assertIsDisplayed()
    }

    // --------------------------------------------------------- Edge Variants

    @Test
    fun edge1b_unbuiltSavedWorld_loadWorldAutoStartsRide_within2Taps() {
        val unbuiltRide = sampleRides().first()
        AppServices.rideStorage.saveRide(unbuiltRide)
        val appViewModel = AppViewModel()
        val vm = IndoorViewModel(
            parts = AppServices.indoorParts,
            rideStorage = AppServices.rideStorage,
            worlds = AppServices.indoorWorldLibrary,
            store = AppServices.keyValueStore,
            texts = ContextIndoorTexts(
                context = { AppServices.localizedContext() },
                language = { AppServices.appLanguage.value },
            ),
            profile = { appViewModel.profile.value },
            plan = { appViewModel.plan.value },
            coreTexts = { AppServices.coreTexts() },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            runtimeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            autoLoop = false,
        )
        vm.setDemoTrainer(true)
        vm.ensureRouteOptions()
        val savedOption = vm.route.value.options.filterIsInstance<IndoorRouteOption.Saved>().first()
        vm.selectRoute(savedOption)

        // Tap 1: "Fahren" (continueFromRoute) -> stops at Plan because world isn't built yet
        vm.continueFromRoute()
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertTrue(vm.world.value is IndoorWorldUi.Plan)

        // Tap 2: "Welt laden" (loadWorld) -> preserves autoStart = true from "Fahren" and enters RIDE directly
        vm.loadWorld()
        compose.waitUntil(timeoutMillis = 15_000) {
            vm.stage.value == IndoorStage.RIDE
        }
        assertEquals(IndoorStage.RIDE, vm.stage.value)
    }

    @Test
    fun edge1d_indoorTab_coldPair_returnsToHomeBeforeRide() {
        ensureTestWorldReady()
        AppServices.keyValueStore.remove(IndoorViewModel.KEY_DEMO_TRAINER)

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.INDOOR)
        settle()

        val rideAction = string(R.string.indoor_app_home_ride_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(rideAction).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(rideAction).assertIsNotEnabled()

        var taps = 0
        // Tap 1: "Koppeln" from ReadinessLines / TrainerCard -> opens PairStage (pairFromRideFlow = false)
        compose.onAllNodesWithText(string(R.string.indoor_app_home_trainer_pair_action)).onFirst().performClick()
        taps++
        settle(400)

        // Tap 2: Enable Demo-Trainer
        compose.onNodeWithText(string(R.string.indoor_app_pair_demo_title)).performClick()
        taps++
        settle(400)

        // Tap 3: "Fertig" returns to IndoorHomeScreen (not straight into ride)
        compose.onNodeWithText(string(R.string.indoor_app_action_done)).performClick()
        taps++
        settle(400)

        // Tap 4 & 5: Pick TestWorld & tap "Fahren"
        compose.onAllNodesWithText(string(R.string.indoor_app_route_testworld_title)).onFirst().performClick()
        taps++
        settle(300)
        compose.onNodeWithText(rideAction).assertIsEnabled().performClick()
        taps++

        val pauseCd = string(R.string.indoor_app_ride_pause_action)
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithContentDescription(pauseCd).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Cold-pair from Indoor tab takes 5 taps (documented edge variant)", 5, taps)
    }

    @Test
    fun edge2d_todayScreen_routeBuildAutoStartsWithinBudget() {
        AppServices.rideStorage.saveRides(sampleRides())
        savePlan(AppServices.trainingPlanStore, activeTodayPlan())

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()

        val offerAction = string(R.string.today_offer_action)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(offerAction).fetchSemanticsNodes().isNotEmpty()
        }
        var taps = 0
        compose.onAllNodesWithText(offerAction).onFirst().performClick()
        taps++
        compose.waitUntil(timeoutMillis = 10_000) {
            RouteGenerationController.state.value.target != null
        }
        RouteGenerationController.publishTestCandidates(listOf(sampleCandidate()), selectedIndex = 0)
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(string(R.string.map_nav_stop_cd)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("2d from Heute must complete within <= 4 taps (was $taps)", taps <= 4)
    }

    @Test
    fun edge4a_indoorPause_finishOpensConfirmationDialog() {
        ensureTestWorldReady()
        val appViewModel = AppViewModel()
        var clockMs = 1_000L
        val vm = IndoorViewModel(
            parts = AppServices.indoorParts,
            rideStorage = AppServices.rideStorage,
            worlds = AppServices.indoorWorldLibrary,
            store = AppServices.keyValueStore,
            texts = ContextIndoorTexts(
                context = { AppServices.localizedContext() },
                language = { AppServices.appLanguage.value },
            ),
            profile = { appViewModel.profile.value },
            plan = { appViewModel.plan.value },
            coreTexts = { AppServices.coreTexts() },
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            clock = { clockMs },
            wallClock = { 1_765_000_000_000L },
            runtimeDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            autoLoop = false,
        )
        vm.setDemoTrainer(true)
        vm.ensureRouteOptions()
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.continueFromRoute()
        compose.waitUntil(timeoutMillis = 15_000) {
            vm.stage.value == IndoorStage.RIDE && vm.activeRuntime != null
        }
        vm.releaseStart()
        val runtime = vm.activeRuntime!!
        repeat(50) {
            clockMs += 100L
            runtime.tickOnce()
        }

        compose.setContent {
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides windowLayoutClassFor(LocalConfiguration.current.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            IndoorFlowScreen(
                                appViewModel = appViewModel,
                                onExit = {},
                                onOpenDiagnostics = {},
                                mode = IndoorFlowMode.TAB,
                                vmOverride = vm,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        // Tap 1: Pause (advance runtime tick so PhaseCard appears with "Beenden")
        compose.onNodeWithContentDescription(string(R.string.indoor_app_ride_pause_action)).performClick()
        compose.waitForIdle()
        clockMs += 100L
        runtime.tickOnce()
        compose.waitForIdle()
        // Tap 2: Finish from pause overlay -> opens confirmation dialog
        compose.onNodeWithText(string(R.string.indoor_app_start_finish_action)).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.indoor_app_flow_leave_title)).assertIsDisplayed()
        // Tap 3: Confirm finish in confirmation dialog -> enters IndoorSummaryScreen
        compose.onNode(
            hasText(string(R.string.indoor_app_flow_leave_finish)) and hasAnyAncestor(isDialog()),
        ).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            vm.stage.value == IndoorStage.SUMMARY
        }
        assertEquals(IndoorStage.SUMMARY, vm.stage.value)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun mapAndRideMode_expandedShell_hidesAtlasNavigationRailDuringDaten() {
        val finishedRide = sampleRides().first()
        AppServices.rideStorage.saveRide(finishedRide)
        RecordingRepository.publishStarted(
            startedAtMs = System.currentTimeMillis() - 120_000L,
            points = finishedRide.points,
            paused = false,
        )

        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.MAP)
        settle()

        // Rail visible on MAP when NAVI_KARTE / LiveRecordingCard is shown
        val todayNav = string(R.string.shell_nav_today_label)
        compose.onNodeWithText(todayNav).assertIsDisplayed()

        // Open RideModeScreen (DATEN) via LiveRecordingCard -> Rail hides for full-bleed CockpitTheme
        compose.onNodeWithText(string(R.string.map_live_ride_mode_action)).performClick()
        settle()
        compose.onAllNodesWithText(todayNav).assertCountEquals(0)
    }
}
