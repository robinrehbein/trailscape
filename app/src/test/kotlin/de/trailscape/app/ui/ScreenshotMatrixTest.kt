package de.trailscape.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.record.merkeBatterieHinweisGezeigt
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.indoor.CatalogDetailContent
import de.trailscape.app.ui.indoor.CatalogRow
import de.trailscape.app.ui.indoor.IndoorCatalogItemUi
import de.trailscape.app.ui.indoor.IndoorCatalogState
import de.trailscape.app.ui.indoor.IndoorCatalogUi
import de.trailscape.app.ui.indoor.IndoorDaySuggestionUi
import de.trailscape.app.ui.indoor.IndoorDayUi
import de.trailscape.app.ui.indoor.IndoorGhostOption
import de.trailscape.app.ui.indoor.IndoorHomeScreen
import de.trailscape.app.ui.indoor.IndoorPairUi
import de.trailscape.app.ui.indoor.IndoorProfileUi
import de.trailscape.app.ui.indoor.IndoorRenderUi
import de.trailscape.app.ui.indoor.IndoorRideScreen
import de.trailscape.app.ui.indoor.IndoorRideUi
import de.trailscape.app.ui.indoor.IndoorRouteOption
import de.trailscape.app.ui.indoor.IndoorRouteUi
import de.trailscape.app.ui.indoor.IndoorSummaryScreen
import de.trailscape.app.ui.indoor.IndoorSummaryUi
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.map.MapScreen
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.map.RideModeNavigation
import de.trailscape.app.ui.map.RideModeScreen
import de.trailscape.app.ui.more.MoreScreen
import de.trailscape.app.ui.rides.RideDetailScreen
import de.trailscape.app.ui.rides.RidesScreen
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TabletScreenPadding
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.app.ui.today.LocalTestHourNow
import de.trailscape.app.ui.today.TodayScreen
import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.LiveKachel
import de.trailscape.core.LiveSensorAnzeige
import de.trailscape.core.PulsQuelle
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.TurnRichtung
import de.trailscape.core.WeekKind
import de.trailscape.core.ZielZeile
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.DayTerrain
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.catalog.CatalogProfile
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.indoor.catalog.CatalogTimeBasis
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.loadPlan
import de.trailscape.core.savePlan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/**
 * Volle Screenshot-Matrix ueber alle 9 Hauptbildschirme x 4 Geraete x 2 Schriftgroessen x 2 Sprachen
 * (144 Zellen total) nach `build/outputs/roborazzi/matrix-<screen>-<device>-<scale>-<locale>.png`.
 *
 * Wird nur bei gesetztem `-Pscreenshots` (`trailscape.screenshots == "true"`) ausgefuehrt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ScreenshotApplication::class)
class ScreenshotMatrixTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private enum class MatrixScreen(val slug: String) {
        SHELL("shell"),
        TODAY("today"),
        INDOOR_HOME("indoor-home"),
        MAP("map"),
        RIDES("rides"),
        RIDE_DETAIL("ride-detail"),
        MORE("more"),
        INDOOR_RIDE("indoor-ride"),
        RIDE_MODE("ride-mode"),
    }

    private var savedRides: List<Ride> = emptyList()
    private var savedPlan: TrainingPlan? = null
    private var prevOnboarding: String? = null
    private var prevLongPressHint: String? = null
    private var prevUpdateAutoCheck: String? = null
    private var prevUpdateLatestKnown: String? = null
    private var prevUpdateAnnounced: String? = null
    private var prevUpdateDismissed: String? = null

    @Before
    fun setUp() {
        assumeTrue(
            "Screenshot matrix only runs with -Pscreenshots (trailscape.screenshots=true)",
            System.getProperty("trailscape.screenshots") == "true",
        )
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
        @Suppress("DEPRECATION")
        shadowLm.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 48.52
                longitude = 9.05
                accuracy = 5f
                time = System.currentTimeMillis()
            },
        )
        merkeBatterieHinweisGezeigt(app)

        val kv = AppServices.keyValueStore
        prevOnboarding = kv.getString(ONBOARDING_STORAGE_KEY)
        prevLongPressHint = kv.getString(LONG_PRESS_HINT_STORAGE_KEY)
        prevUpdateAutoCheck = kv.getString(UPDATE_AUTO_CHECK_KEY)
        prevUpdateLatestKnown = kv.getString(UPDATE_LATEST_KNOWN_KEY)
        prevUpdateAnnounced = kv.getString(UPDATE_ANNOUNCED_KEY)
        prevUpdateDismissed = kv.getString(UPDATE_DISMISSED_KEY)
        kv.setString(ONBOARDING_STORAGE_KEY, "1")
        kv.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        // Suppress update-check network/toast notice in matrix captures
        kv.setString(UPDATE_AUTO_CHECK_KEY, "false")
        kv.remove(UPDATE_LATEST_KNOWN_KEY)
        kv.remove(UPDATE_ANNOUNCED_KEY)
        kv.remove(UPDATE_DISMISSED_KEY)

        val storage = AppServices.rideStorage
        val ids = storage.listSummaries().summaries.map { it.id }
        savedRides = ids.mapNotNull { storage.loadRide(it) }
        ids.forEach { storage.deleteRide(it) }

        savedPlan = loadPlan(AppServices.trainingPlanStore)
        savePlan(AppServices.trainingPlanStore, null)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        if (System.getProperty("trailscape.screenshots") != "true") return
        val storage = AppServices.rideStorage
        storage.listSummaries().summaries.forEach { storage.deleteRide(it.id) }
        if (savedRides.isNotEmpty()) storage.saveRides(savedRides)
        savePlan(AppServices.trainingPlanStore, savedPlan)

        restoreKey(ONBOARDING_STORAGE_KEY, prevOnboarding)
        restoreKey(LONG_PRESS_HINT_STORAGE_KEY, prevLongPressHint)
        restoreKey(UPDATE_AUTO_CHECK_KEY, prevUpdateAutoCheck)
        restoreKey(UPDATE_LATEST_KNOWN_KEY, prevUpdateLatestKnown)
        restoreKey(UPDATE_ANNOUNCED_KEY, prevUpdateAnnounced)
        restoreKey(UPDATE_DISMISSED_KEY, prevUpdateDismissed)
    }

    private companion object {
        const val UPDATE_AUTO_CHECK_KEY = "trailscape.updates.autoCheck"
        const val UPDATE_LATEST_KNOWN_KEY = "trailscape.update.latestKnown"
        const val UPDATE_ANNOUNCED_KEY = "trailscape.update.announced"
        const val UPDATE_DISMISSED_KEY = "trailscape.update.dismissed"
    }

    private fun restoreKey(key: String, previous: String?) {
        if (previous == null) AppServices.keyValueStore.remove(key) else AppServices.keyValueStore.setString(key, previous)
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun runMatrixCell(
        screen: MatrixScreen,
        device: String,
        scale: Float,
        scaleLabel: String,
        expectedLang: AppLanguage,
        localeSlug: String,
        darkTheme: Boolean = false,
        outPathOverride: String? = null,
    ) {
        TestLocales.assertTestLocale(expectedLang)
        RuntimeEnvironment.setFontScale(scale)

        val isEn = localeSlug == "en"
        val seededRides = sampleRides(isEn)
        val seededPlan = activeTodayPlan(isEn)
        AppServices.rideStorage.saveRides(seededRides)
        savePlan(AppServices.trainingPlanStore, seededPlan)

        var useScreenCapture = false
        var rideDetailVm: AppViewModel? = null
        val fixedNoon = LocalDateTime.of(LocalDate.now(), LocalTime.of(12, 0))
        compose.setContent {
            val baseDensity = LocalDensity.current
            val config = LocalConfiguration.current
            val language = languageOf(config)
            val layoutClass = windowLayoutClassFor(config.screenWidthDp)
            val isExpanded = layoutClass == WindowLayoutClass.Expanded
            LaunchedEffect(language) { AppServices.setAppLanguage(language) }

            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = scale),
                LocalWindowLayoutClass provides layoutClass,
                LocalMapRenderingAvailable provides false,
                LocalTestHourNow provides fixedNoon,
            ) {
                TrailscapeTheme(darkTheme = darkTheme) {
                    ProvideTestLanguage {
                        when (screen) {
                            MatrixScreen.SHELL -> {
                                Surface(Modifier.fillMaxSize()) {
                                    TrailscapeApp()
                                }
                            }

                            MatrixScreen.TODAY -> {
                                val vm = remember { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                                Surface(Modifier.fillMaxSize()) {
                                    TodayScreen(appViewModel = vm)
                                }
                            }

                            MatrixScreen.INDOOR_HOME -> {
                                Surface(Modifier.fillMaxSize()) {
                                    IndoorHomeScreen(
                                        route = IndoorRouteUi(
                                            options = listOf(IndoorRouteOption.TestWorld),
                                            selected = IndoorRouteOption.TestWorld,
                                            mode = IndoorMode.SIM,
                                            ergTitle = null,
                                            ergMinutes = null,
                                            ghost = IndoorGhostOption.None,
                                            ghostPastRideAvailable = true,
                                            defaultGhostWatts = 200,
                                        ),
                                        pair = IndoorPairUi(
                                            demoTrainer = true,
                                            trainerName = if (isEn) "Demo Trainer" else "Demo-Trainer",
                                            shifterName = null,
                                        ),
                                        trainerIssue = null,
                                        onSelect = {},
                                        onModeChange = {},
                                        onGhostChange = {},
                                        onRide = {},
                                        onBack = null,
                                        onOpenPair = {},
                                        onOpenSettings = {},
                                        catalog = sampleCatalogUi(),
                                        day = sampleDayOffer(isEn),
                                        title = "Indoor",
                                        headerExtra = {
                                            PillSegments(
                                                options = listOf(
                                                    if (isEn) "Routes" else "Strecken",
                                                    if (isEn) "Garage" else "Werkstatt",
                                                ),
                                                selectedIndex = 0,
                                                onSelect = {},
                                            )
                                        },
                                        onOpenGarage = {},
                                    )
                                }
                            }

                            MatrixScreen.MAP -> {
                                val vm = remember { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                                Surface(Modifier.fillMaxSize()) {
                                    MapScreen(appViewModel = vm)
                                }
                            }

                            MatrixScreen.RIDES -> {
                                val vm = remember { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                                Surface(Modifier.fillMaxSize()) {
                                    RidesScreen(appViewModel = vm)
                                }
                            }

                            MatrixScreen.RIDE_DETAIL -> {
                                val vm = remember { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                                rideDetailVm = vm
                                val snackbar = remember { SnackbarHostState() }
                                Surface(Modifier.fillMaxSize()) {
                                    RideDetailScreen(
                                        ride = seededRides.first(),
                                        appViewModel = vm,
                                        snackbarHostState = snackbar,
                                        onBack = {},
                                        onShowOnMap = {},
                                        onRideAgain = {},
                                        onRename = {},
                                        onShare = {},
                                        onDelete = {},
                                        inline = false,
                                        onRideIndoor = {},
                                        onPreview3d = {},
                                    )
                                }
                            }

                            MatrixScreen.MORE -> {
                                val vm = remember { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                                Surface(Modifier.fillMaxSize()) {
                                    MoreScreen(appViewModel = vm)
                                }
                            }

                            MatrixScreen.INDOOR_RIDE -> {
                                IndoorRideScreen(
                                    ui = sampleIndoorRideUi(isEn),
                                    worldSurface = { mod -> MatrixWorldPlaceholder(mod) },
                                    onShift = {},
                                    onTogglePause = {},
                                    onFinish = {},
                                    onCameraChange = {},
                                    onDemoPower = {},
                                    onDemoCadence = {},
                                    onErgAdjust = {},
                                    onDismissNotice = {},
                                )
                            }

                            MatrixScreen.RIDE_MODE -> {
                                val useDialog = !isExpanded
                                useScreenCapture = useDialog
                                val nav = RideModeNavigation(
                                    label = if (isEn) "Schönbuch Gravel Loop" else "Schönbuch-Schotterrunde",
                                    remainingKm = 14.2,
                                    offRoute = false,
                                    naechsteKurve = TurnRichtung.RECHTS,
                                    naechsteKurveM = 180.0,
                                )
                                val sensors = LiveSensorAnzeige(
                                    puls = LiveKachel(wert = 144, stillSeitS = null),
                                    pulsQuelle = PulsQuelle.GURT,
                                    leistung = LiveKachel(wert = 212, stillSeitS = null),
                                    trittfrequenz = LiveKachel(wert = 86, stillSeitS = null),
                                )
                                val mapStub: (@Composable (Modifier) -> Unit)? = if (isExpanded) {
                                    { mod -> MatrixRideMapStub(navigation = nav, modifier = mod) }
                                } else {
                                    null
                                }
                                Surface(Modifier.fillMaxSize()) {
                                    RideModeScreen(
                                        speedKmh = 28.4,
                                        distanceKm = 18.7,
                                        elapsedS = 2_410,
                                        ascentM = 340.0,
                                        paused = false,
                                        navigation = nav,
                                        onTogglePause = {},
                                        onStop = {},
                                        onClose = {},
                                        onShowMap = {},
                                        sensoren = sensors,
                                        zielZeile = ZielZeile.BestweiteRest(7.6),
                                        useDialog = useDialog,
                                        mapContent = mapStub,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Wait for Compose & async ViewModel loads (plans/rides/insights) before capturing.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()

        when (screen) {
            MatrixScreen.SHELL, MatrixScreen.TODAY -> {
                val whyAction = compose.activity.getString(R.string.today_hero_why_action)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(whyAction, ignoreCase = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }

            MatrixScreen.RIDES -> {
                val rideTitle = seededRides.first().name
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(rideTitle).fetchSemanticsNodes().isNotEmpty()
                }
            }

            MatrixScreen.MAP -> {
                val buildLoop = compose.activity.getString(R.string.map_explore_build_loop_action)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(buildLoop).fetchSemanticsNodes().isNotEmpty()
                }
            }

            MatrixScreen.MORE -> {
                val moreTitle = compose.activity.getString(R.string.more_screen_title)
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(moreTitle).fetchSemanticsNodes().isNotEmpty()
                }
            }

            MatrixScreen.RIDE_DETAIL -> {
                val routeTitle = compose.activity.getString(R.string.rides_route_title)
                val targetRideId = seededRides.first().id
                compose.waitUntil(timeoutMillis = 15_000) {
                    val insightsLoaded = rideDetailVm?.insights?.value?.rideLoads?.containsKey(targetRideId) == true
                    val comparisonShown = compose.onAllNodesWithText(routeTitle).fetchSemanticsNodes().isNotEmpty()
                    insightsLoaded && comparisonShown
                }
            }

            else -> Unit
        }
        compose.waitForIdle()

        if (screen == MatrixScreen.SHELL && device == "360dp" && scale > 1.1f) {
            val navLabels = listOf(
                compose.activity.getString(R.string.shell_nav_today_label),
                compose.activity.getString(R.string.shell_nav_map_label),
                compose.activity.getString(R.string.shell_nav_indoor_label),
                compose.activity.getString(R.string.shell_nav_history_label),
            )
            for (label in navLabels) {
                val node = compose.onNode(
                    hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                )
                node.assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Expected TextLayoutResult for nav tab '$label'", layouts.isNotEmpty())
                for (layout in layouts) {
                    assertEquals("Nav tab '$label' must stay single-line", 1, layout.lineCount)
                    assertFalse(
                        "Nav tab '$label' must not truncate with ellipsis at $device $scaleLabel $localeSlug",
                        layout.hasVisualOverflow,
                    )
                }
            }
        }

        val outPath = outPathOverride
            ?: "build/outputs/roborazzi/matrix-${screen.slug}-$device-$scaleLabel-$localeSlug.png"
        if (useScreenCapture) {
            captureScreenRoboImage(outPath)
        } else {
            compose.onRoot().captureRoboImage(outPath)
        }
    }

    // ------------------------------------------------------------------ Fixtures

    private fun samplePoints(): List<TrackPoint> = List(48) { i ->
        val t = i / 47.0 * 2 * Math.PI
        TrackPoint(
            lat = 48.52 + 0.025 * sin(t),
            lon = 9.05 + 0.035 * cos(t),
            ele = 460.0 + 45.0 * sin(2 * t),
            time = 1_730_000_000_000L + i * 25_000L,
            hr = 142,
        )
    }

    private fun sampleRides(isEn: Boolean): List<Ride> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        return listOf(
            Ride(
                id = "mx-ride-1",
                name = if (isEn) "Evening Gravel Loop" else "Feierabendrunde",
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
                id = "mx-ride-2",
                name = if (isEn) "Neckar Valley Endurance" else "Neckartal-Ausdauer",
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
                points = samplePoints(),
            ),
        )
    }

    private fun activeTodayPlan(isEn: Boolean): TrainingPlan {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val codes = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val todayCode = codes[today.dayOfWeek.value - 1]
        val startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val goalMs = monday.plusWeeks(8).plusDays(5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
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
                    sessions = listOf(
                        TrainingSession(
                            day = todayCode,
                            title = if (isEn) "Base Endurance" else "Grundlage",
                            description = if (isEn) "Ride steady endurance pace." else "Ruhig und gleichmäßig fahren.",
                            targetKm = 36,
                            intensity = SessionIntensity.GRUNDLAGE,
                            durationMin = 80,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun sampleCatalogUi(): IndoorCatalogUi = IndoorCatalogUi(
        items = listOf(
            IndoorCatalogItemUi(
                id = "marbach-lemberg",
                name = CatalogText("Marbach und Lemberg", "Marbach and Lemberg"),
                region = CatalogText("Neckartal", "Neckar Valley"),
                description = CatalogText("Wellige Runde durch Weinberge.", "Rolling loop through vineyards."),
                country = "DE",
                kind = CatalogRideKind.ROAD,
                distanceKm = 24.1,
                ascentM = 390.0,
                surfaces = listOf(SurfaceKind.ASPHALT to 0.95, SurfaceKind.GRAVEL to 0.05),
                packageBytes = 253_600_000L,
                aerial = true,
                state = IndoorCatalogState.NotLoaded,
            ),
        ),
    )

    private fun sampleDayOffer(isEn: Boolean): IndoorDayUi.Offer = IndoorDayUi.Offer(
        kind = DaySessionKind.PLANNED,
        title = if (isEn) "Base Endurance" else "Grundlage",
        minutes = 72,
        plannedMinutes = 80,
        terrain = DayTerrain.ROLLING,
        suggestions = listOf(
            IndoorDaySuggestionUi(
                key = "marbach-lemberg",
                name = if (isEn) "Marbach and Lemberg" else "Marbach und Lemberg",
                availability = DayRouteAvailability.READY,
                rideKm = 24.1,
                laps = 1,
                ascentM = 390.0,
                durationMinutes = 72,
                profileElevM = listOf(220f, 260f, 340f, 290f, 370f, 250f, 220f),
            ),
        ),
    )

    private fun sampleIndoorRideUi(isEn: Boolean): IndoorRideUi = IndoorRideUi(
        routeName = if (isEn) "Neckar Valley Loop" else "Neckartal-Runde",
        session = IndoorSessionState(
            phase = IndoorPhase.RUNNING,
            mode = IndoorMode.SIM,
            nowMs = 1_760_000_000_000L,
            elapsedS = 1_425,
            distM = 12_450.0,
            routeDistM = 12_450.0,
            lap = 1,
            speedMps = 8.7,
            powerW = 224,
            cadenceRpm = 88,
            hrBpm = 148,
            gradePct = 3.4,
            surface = SurfaceKind.GRAVEL,
            crr = 0.006,
            headwindMps = 0.0,
            gear = GearState(index = 14, count = 24, ratio = 2.6),
            ghost = GhostState(distM = 12_540.0, speedMps = 8.9, gapM = 90.0, gapS = 10.3, powerW = 228.0),
            erg = null,
            totals = IndoorTotals(
                distanceM = 12_450.0,
                ascentM = 195.0,
                movingS = 1_420,
                avgPowerW = 216.0,
                normalizedPowerW = 224.0,
                avgCadenceRpm = 86.0,
                avgHrBpm = 144.0,
                workKj = 307.0,
            ),
        ),
        trainerName = if (isEn) "Demo Trainer" else "Demo-Trainer",
        trainer = TrainerConnection.READY,
        shifter = ShifterConnection.OFF,
        shifterBatteryPct = null,
        render = IndoorRenderUi.Ready,
        camera = CameraMode.CHASE,
        profile = IndoorProfileUi(
            lengthM = 24_130.0,
            distM = floatArrayOf(0f, 6_000f, 12_000f, 18_000f, 24_130f),
            eleM = floatArrayOf(220f, 280f, 360f, 270f, 220f),
            isLoop = true,
        ),
        notice = null,
        demoPowerW = 224,
        demoCadenceRpm = 88,
        ghostLabel = if (isEn) "Personal Best" else "Bestzeit",
        ergTitle = null,
        attribution = emptyList(),
    )

    @Composable
    private fun MatrixWorldPlaceholder(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF4F8CC9), Color(0xFFA7CDE8), Color(0xFF5D7A4C)),
                ),
            )
            val horizonY = size.height * 0.45f
            val hill = Path().apply {
                moveTo(0f, horizonY)
                lineTo(size.width * 0.3f, horizonY - size.height * 0.09f)
                lineTo(size.width * 0.65f, horizonY - size.height * 0.04f)
                lineTo(size.width, horizonY - size.height * 0.11f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(hill, color = Color(0xFF3E5F34))
            val road = Path().apply {
                moveTo(size.width * 0.47f, horizonY)
                lineTo(size.width * 0.53f, horizonY)
                lineTo(size.width * 0.78f, size.height)
                lineTo(size.width * 0.22f, size.height)
                close()
            }
            drawPath(road, color = Color(0xFF4E5258))
        }
    }

    @Composable
    private fun MatrixRideMapStub(
        navigation: RideModeNavigation,
        modifier: Modifier = Modifier,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0xFFE9E6DC))
                val routePath = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.8f)
                    cubicTo(
                        size.width * 0.35f,
                        size.height * 0.55f,
                        size.width * 0.65f,
                        size.height * 0.6f,
                        size.width * 0.78f,
                        size.height * 0.28f,
                    )
                }
                drawPath(
                    path = routePath,
                    color = Color(0xFF1F6FEB),
                    style = Stroke(width = 10f, cap = StrokeCap.Round),
                )
                drawCircle(
                    color = Color(0xFF1F6FEB),
                    radius = 16f,
                    center = Offset(size.width * 0.48f, size.height * 0.58f),
                )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(TabletScreenPadding),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "${navigation.naechsteKurveM?.toInt() ?: 0} m · ${navigation.label}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${navigation.remainingKm} km",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ================================================================== 360dp DE

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun shell_360dp_1_0x_de() = runMatrixCell(MatrixScreen.SHELL, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun shell_360dp_1_3x_de() = runMatrixCell(MatrixScreen.SHELL, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun today_360dp_1_0x_de() = runMatrixCell(MatrixScreen.TODAY, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun today_360dp_1_3x_de() = runMatrixCell(MatrixScreen.TODAY, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun indoor_home_360dp_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun indoor_home_360dp_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun map_360dp_1_0x_de() = runMatrixCell(MatrixScreen.MAP, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun map_360dp_1_3x_de() = runMatrixCell(MatrixScreen.MAP, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun rides_360dp_1_0x_de() = runMatrixCell(MatrixScreen.RIDES, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun rides_360dp_1_3x_de() = runMatrixCell(MatrixScreen.RIDES, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun ride_detail_360dp_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun ride_detail_360dp_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun more_360dp_1_0x_de() = runMatrixCell(MatrixScreen.MORE, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun more_360dp_1_3x_de() = runMatrixCell(MatrixScreen.MORE, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun indoor_ride_360dp_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun indoor_ride_360dp_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun ride_mode_360dp_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "360dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun ride_mode_360dp_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "360dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    // ================================================================== 360dp EN

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun shell_360dp_1_0x_en() = runMatrixCell(MatrixScreen.SHELL, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun shell_360dp_1_3x_en() = runMatrixCell(MatrixScreen.SHELL, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun today_360dp_1_0x_en() = runMatrixCell(MatrixScreen.TODAY, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun today_360dp_1_3x_en() = runMatrixCell(MatrixScreen.TODAY, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun indoor_home_360dp_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun indoor_home_360dp_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun map_360dp_1_0x_en() = runMatrixCell(MatrixScreen.MAP, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun map_360dp_1_3x_en() = runMatrixCell(MatrixScreen.MAP, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun rides_360dp_1_0x_en() = runMatrixCell(MatrixScreen.RIDES, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun rides_360dp_1_3x_en() = runMatrixCell(MatrixScreen.RIDES, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun ride_detail_360dp_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun ride_detail_360dp_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun more_360dp_1_0x_en() = runMatrixCell(MatrixScreen.MORE, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun more_360dp_1_3x_en() = runMatrixCell(MatrixScreen.MORE, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun indoor_ride_360dp_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun indoor_ride_360dp_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun ride_mode_360dp_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "360dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun ride_mode_360dp_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "360dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    // ================================================================== 412dp DE

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun shell_412dp_1_0x_de() = runMatrixCell(MatrixScreen.SHELL, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun shell_412dp_1_3x_de() = runMatrixCell(MatrixScreen.SHELL, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun today_412dp_1_0x_de() = runMatrixCell(MatrixScreen.TODAY, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun today_412dp_1_3x_de() = runMatrixCell(MatrixScreen.TODAY, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun indoor_home_412dp_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun indoor_home_412dp_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun map_412dp_1_0x_de() = runMatrixCell(MatrixScreen.MAP, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun map_412dp_1_3x_de() = runMatrixCell(MatrixScreen.MAP, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun rides_412dp_1_0x_de() = runMatrixCell(MatrixScreen.RIDES, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun rides_412dp_1_3x_de() = runMatrixCell(MatrixScreen.RIDES, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun ride_detail_412dp_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun ride_detail_412dp_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun more_412dp_1_0x_de() = runMatrixCell(MatrixScreen.MORE, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun more_412dp_1_3x_de() = runMatrixCell(MatrixScreen.MORE, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun indoor_ride_412dp_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun indoor_ride_412dp_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun ride_mode_412dp_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "412dp", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.S25_DE)
    fun ride_mode_412dp_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "412dp", 1.3f, "1.3x", AppLanguage.DE, "de")

    // ================================================================== 412dp EN

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun shell_412dp_1_0x_en() = runMatrixCell(MatrixScreen.SHELL, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun shell_412dp_1_3x_en() = runMatrixCell(MatrixScreen.SHELL, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun today_412dp_1_0x_en() = runMatrixCell(MatrixScreen.TODAY, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun today_412dp_1_3x_en() = runMatrixCell(MatrixScreen.TODAY, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun indoor_home_412dp_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun indoor_home_412dp_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun map_412dp_1_0x_en() = runMatrixCell(MatrixScreen.MAP, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun map_412dp_1_3x_en() = runMatrixCell(MatrixScreen.MAP, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun rides_412dp_1_0x_en() = runMatrixCell(MatrixScreen.RIDES, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun rides_412dp_1_3x_en() = runMatrixCell(MatrixScreen.RIDES, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun ride_detail_412dp_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun ride_detail_412dp_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun more_412dp_1_0x_en() = runMatrixCell(MatrixScreen.MORE, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun more_412dp_1_3x_en() = runMatrixCell(MatrixScreen.MORE, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun indoor_ride_412dp_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun indoor_ride_412dp_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun ride_mode_412dp_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "412dp", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.S25_EN)
    fun ride_mode_412dp_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "412dp", 1.3f, "1.3x", AppLanguage.EN, "en")

    // ===================================================== tab-s11-portrait DE

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun shell_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun shell_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun today_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun today_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun indoor_home_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun indoor_home_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun map_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.MAP, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun map_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.MAP, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun rides_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun rides_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun ride_detail_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun ride_detail_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun more_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.MORE, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun more_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.MORE, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun indoor_ride_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun indoor_ride_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun ride_mode_tab_s11_portrait_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun ride_mode_tab_s11_portrait_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.DE, "de")

    // ===================================================== tab-s11-portrait EN

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun shell_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun shell_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun today_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun today_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun indoor_home_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun indoor_home_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun map_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.MAP, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun map_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.MAP, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun rides_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun rides_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun ride_detail_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun ride_detail_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun more_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.MORE, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun more_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.MORE, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun indoor_ride_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun indoor_ride_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun ride_mode_tab_s11_portrait_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-portrait", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun ride_mode_tab_s11_portrait_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-portrait", 1.3f, "1.3x", AppLanguage.EN, "en")

    // ==================================================== tab-s11-landscape DE

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun shell_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun shell_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun today_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun today_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun indoor_home_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun indoor_home_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun map_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.MAP, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun map_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.MAP, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun rides_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun rides_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun ride_detail_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun ride_detail_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun more_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.MORE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun more_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.MORE, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun indoor_ride_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun indoor_ride_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun ride_mode_tab_s11_landscape_1_0x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun ride_mode_tab_s11_landscape_1_3x_de() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.DE, "de")

    // ==================================================== tab-s11-landscape EN

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun shell_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun shell_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.SHELL, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun today_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun today_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.TODAY, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun indoor_home_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun indoor_home_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_HOME, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun map_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.MAP, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun map_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.MAP, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun rides_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun rides_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.RIDES, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun ride_detail_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun ride_detail_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_DETAIL, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun more_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.MORE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun more_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.MORE, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun indoor_ride_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun indoor_ride_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.INDOOR_RIDE, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun ride_mode_tab_s11_landscape_1_0x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun ride_mode_tab_s11_landscape_1_3x_en() = runMatrixCell(MatrixScreen.RIDE_MODE, "tab-s11-landscape", 1.3f, "1.3x", AppLanguage.EN, "en")

    // ============================================== Targeted Dark & Edge Cells

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun dark_today_tab_s11_landscape_1_0x_de() = runMatrixCell(
        MatrixScreen.TODAY, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de",
        darkTheme = true,
        outPathOverride = "build/outputs/roborazzi/matrix-dark-today-tab-s11-landscape-1.0x-de.png",
    )

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun dark_indoor_home_tab_s11_landscape_1_0x_de() = runMatrixCell(
        MatrixScreen.INDOOR_HOME, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de",
        darkTheme = true,
        outPathOverride = "build/outputs/roborazzi/matrix-dark-indoor-home-tab-s11-landscape-1.0x-de.png",
    )

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun dark_map_tab_s11_landscape_1_0x_de() = runMatrixCell(
        MatrixScreen.MAP, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de",
        darkTheme = true,
        outPathOverride = "build/outputs/roborazzi/matrix-dark-map-tab-s11-landscape-1.0x-de.png",
    )

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun dark_rides_tab_s11_landscape_1_0x_de() = runMatrixCell(
        MatrixScreen.RIDES, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de",
        darkTheme = true,
        outPathOverride = "build/outputs/roborazzi/matrix-dark-rides-tab-s11-landscape-1.0x-de.png",
    )

    @Test @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun dark_more_tab_s11_landscape_1_0x_de() = runMatrixCell(
        MatrixScreen.MORE, "tab-s11-landscape", 1.0f, "1.0x", AppLanguage.DE, "de",
        darkTheme = true,
        outPathOverride = "build/outputs/roborazzi/matrix-dark-more-tab-s11-landscape-1.0x-de.png",
    )

    private fun runCatalogDetailEdge(expectedLang: AppLanguage, localeSlug: String) {
        TestLocales.assertTestLocale(expectedLang)
        RuntimeEnvironment.setFontScale(1.3f)
        val row = CatalogRow(
            item = IndoorCatalogItemUi(
                id = "marbach-lemberg",
                name = CatalogText("Marbach und Lemberg", "Marbach and Lemberg"),
                region = CatalogText("Neckartal", "Neckar Valley"),
                description = CatalogText("Wellige Runde durch Weinberge.", "Rolling loop through vineyards."),
                country = "DE",
                kind = CatalogRideKind.ROAD,
                distanceKm = 24.1,
                ascentM = 390.0,
                surfaces = listOf(SurfaceKind.ASPHALT to 0.95, SurfaceKind.GRAVEL to 0.05),
                packageBytes = 253_600_000L,
                aerial = true,
                state = IndoorCatalogState.NotLoaded,
                profile = CatalogProfile(stepM = 500.0, elevM = listOf(210.0, 265.0, 340.0, 290.0, 385.0, 240.0, 210.0)),
            ),
            estimateS = 4_320,
            gradeAvgPct = 1.6,
            gradeMaxPct = 8.4,
            bestS = 4_110,
            lengthM = 24_100.0,
            timeBasis = CatalogTimeBasis.PROFILE,
        )
        compose.setContent {
            val baseDensity = LocalDensity.current
            val config = LocalConfiguration.current
            val language = languageOf(config)
            LaunchedEffect(language) { AppServices.setAppLanguage(language) }
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = 1.3f),
                LocalWindowLayoutClass provides windowLayoutClassFor(config.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            CatalogDetailContent(
                                row = row,
                                selected = false,
                                canRide = true,
                                onSelect = {},
                                onRide = {},
                                onDownload = {},
                                onCancel = {},
                                onDiscard = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "build/outputs/roborazzi/matrix-edge-indoor-catalog-detail-360dp-1.3x-$localeSlug.png",
        )
    }

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun edge_indoor_catalog_detail_360dp_1_3x_de() = runCatalogDetailEdge(AppLanguage.DE, "de")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun edge_indoor_catalog_detail_360dp_1_3x_en() = runCatalogDetailEdge(AppLanguage.EN, "en")

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun edge_indoor_summary_tooshort() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        RuntimeEnvironment.setFontScale(1.0f)
        compose.setContent {
            val config = LocalConfiguration.current
            val language = languageOf(config)
            LaunchedEffect(language) { AppServices.setAppLanguage(language) }
            CompositionLocalProvider(
                LocalWindowLayoutClass provides windowLayoutClassFor(config.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            IndoorSummaryScreen(
                                ui = IndoorSummaryUi(
                                    routeName = "Teststrecke",
                                    mode = IndoorMode.SIM,
                                    distanceKm = 0.2,
                                    movingS = 24,
                                    ascentM = 3.0,
                                    avgPowerW = 165,
                                    normalizedPowerW = 170,
                                    avgCadenceRpm = 82,
                                    avgHrBpm = 128,
                                    workKj = 4,
                                    avgSpeedKmh = 25.0,
                                    laps = 0,
                                    ghostLabel = null,
                                    ghostGapS = null,
                                    tooShort = true,
                                    savedRideId = null,
                                ),
                                onSave = {},
                                onDiscard = {},
                                onDone = {},
                                onOpenTour = null,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/matrix-edge-indoor-summary-tooshort.png")
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun edge_ride_mode_offroute() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        RuntimeEnvironment.setFontScale(1.0f)
        compose.setContent {
            val config = LocalConfiguration.current
            val language = languageOf(config)
            LaunchedEffect(language) { AppServices.setAppLanguage(language) }
            CompositionLocalProvider(
                LocalWindowLayoutClass provides windowLayoutClassFor(config.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            RideModeScreen(
                                speedKmh = 24.6,
                                distanceKm = 11.3,
                                elapsedS = 1_620,
                                ascentM = 185.0,
                                paused = false,
                                navigation = RideModeNavigation(
                                    label = "Schönbuch-Schotterrunde",
                                    remainingKm = 14.2,
                                    offRoute = true,
                                    naechsteKurve = TurnRichtung.RECHTS,
                                    naechsteKurveM = 180.0,
                                ),
                                onTogglePause = {},
                                onStop = {},
                                onClose = {},
                                onShowMap = {},
                                sensoren = LiveSensorAnzeige(
                                    puls = LiveKachel(wert = 148, stillSeitS = null),
                                    pulsQuelle = PulsQuelle.GURT,
                                    leistung = LiveKachel(wert = 205, stillSeitS = null),
                                    trittfrequenz = LiveKachel(wert = 84, stillSeitS = null),
                                ),
                                zielZeile = ZielZeile.BestweiteRest(7.6),
                                useDialog = true,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/matrix-edge-ride-mode-offroute.png")
    }

    @Test @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun edge_rides_empty() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        RuntimeEnvironment.setFontScale(1.0f)
        compose.setContent {
            val config = LocalConfiguration.current
            val language = languageOf(config)
            LaunchedEffect(language) { AppServices.setAppLanguage(language) }
            CompositionLocalProvider(
                LocalWindowLayoutClass provides windowLayoutClassFor(config.screenWidthDp),
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        val vm = remember { ViewModelProvider(compose.activity)[AppViewModel::class.java] }
                        Surface(Modifier.fillMaxSize()) {
                            RidesScreen(appViewModel = vm)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/matrix-edge-rides-empty.png")
    }
}
