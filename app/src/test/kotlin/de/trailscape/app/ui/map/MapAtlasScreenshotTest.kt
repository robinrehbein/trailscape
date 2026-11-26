package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.OverlayScreenPadding
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.core.AscentPreference
import de.trailscape.core.GeoResult
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RideHighlight
import de.trailscape.core.RideStats
import de.trailscape.core.RideSummary
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.RoutingSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.i18n.AppLanguage
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot-Matrix fuer M4 (`MapScreen` Atlas & 380-dp-Seitenpanel):
 * deckt 360 dp (`PHONE_NARROW`), 412 dp (`S25`), Galaxy Tab S11 hochkant (`800×1280 dp`),
 * Galaxy Tab S11 quer (`1280×800 dp` mit 380-dp-Seitenpanel), 1,3x-Schrift,
 * DE/EN sowie Dunkelmodus ab.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class MapAtlasScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private fun loopPoints(lat0: Double = 48.40, lon0: Double = 9.40): List<TrackPoint> =
        (0..80).map { i ->
            val t = i / 80.0 * 2.0 * Math.PI
            TrackPoint(
                lat = lat0 + 0.03 * sin(t),
                lon = lon0 + 0.05 * cos(t),
                ele = 650.0 + 60.0 * sin(2.0 * t),
            )
        }

    private fun samplePlannedRide(): Ride = Ride(
        id = "m4-planned-1",
        name = "Alb-Runde über Hayingen",
        createdAt = 1_730_000_000_000L,
        stats = RideStats(distanceKm = 58.3, ascentM = 640.0, descentM = 640.0),
        planned = true,
        points = loopPoints(),
    )

    private fun sampleCandidateTarget() = RouteTarget(
        distanceKm = 45.0,
        ascentPreference = AscentPreference.MODERAT,
        durationH = 2.5,
        speedKmh = 18.0,
        intensity = SessionIntensity.GRUNDLAGE,
        label = "Grundlageneinheit",
        source = RouteTargetSource.PLAN,
    )

    private fun sampleCandidates(): List<RouteCandidate> = listOf(
        RouteCandidate(
            route = PlannedRoute(loopPoints(), 46.2, 540.0, pavedKm = 18.0, unpavedKm = 28.2),
            distanceKm = 46.2,
            ascentM = 540.0,
            score = 0.0,
            bearingDeg = 20.0,
            targetKm = 45.0,
        ),
        RouteCandidate(
            route = PlannedRoute(loopPoints(48.42, 9.45), 43.8, 380.0, pavedKm = 35.0, unpavedKm = 8.8),
            distanceKm = 43.8,
            ascentM = 380.0,
            score = 1.0,
            bearingDeg = 140.0,
            targetKm = 45.0,
        ),
    )

    private fun renderExplore(file: String, dark: Boolean = false, inSidePanel: Boolean = false) {
        val ride = samplePlannedRide()
        compose.setContent {
            val layoutClass = windowLayoutClassFor(LocalConfiguration.current.screenWidthDp)
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides layoutClass,
            ) {
                TrailscapeTheme(darkTheme = dark) {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (inSidePanel) Modifier.padding(OverlayScreenPadding) else Modifier),
                                contentAlignment = if (inSidePanel) Alignment.TopStart else Alignment.BottomCenter,
                            ) {
                                Box(modifier = if (inSidePanel) Modifier.width(MapSidePanelWidth) else Modifier) {
                                    ExploreSheet(
                                        searchMaxHeight = 220.dp,
                                        searchQuery = "",
                                        onSearchQueryChange = {},
                                        onSubmitSearch = {},
                                        searching = false,
                                        onSearchingChange = {},
                                        onEndSearch = {},
                                        searchBusy = false,
                                        searchError = null,
                                        searchResults = emptyList(),
                                        searchHistory = listOf(Place("Tübingen, Baden-Württemberg", 48.52, 9.05)),
                                        onSelectPlace = {},
                                        onPlanRoute = {},
                                        onRoundTripHere = {},
                                        onOpenSettings = {},
                                        expanded = true,
                                        onExpandedChange = {},
                                        savedRoutes = listOf(
                                            RideSummary(
                                                id = ride.id,
                                                name = ride.name,
                                                createdAt = ride.createdAt,
                                                updatedAt = ride.updatedAt,
                                                stats = ride.stats,
                                                planned = ride.planned,
                                                pointCount = ride.points.size,
                                            ),
                                        ),
                                        onSelectRoute = {},
                                        expandedHeight = 520.dp,
                                        bottomInset = 0.dp,
                                        loadRide = { ride },
                                        inSidePanel = inSidePanel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    // --------------------------------------------------------- ExploreSheet

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun exploreSheet_narrow360_font130_de() {
        RuntimeEnvironment.setFontScale(1.3f)
        renderExplore("m4-explore-360-130-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun exploreSheet_narrow360_font130_en() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        RuntimeEnvironment.setFontScale(1.3f)
        renderExplore("m4-explore-360-130-en.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun exploreSheet_tabS11Portrait_de() {
        renderExplore("m4-explore-tab-portrait-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun exploreSheet_tabS11Landscape_sidePanel_de() {
        renderExplore("m4-explore-tab-landscape-de.png", inSidePanel = true)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun exploreSheet_tabS11Landscape_sidePanel_en() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        renderExplore("m4-explore-tab-landscape-en.png", inSidePanel = true)
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w1280dp-h800dp-night-xhdpi")
    fun exploreSheet_tabS11Landscape_sidePanel_night() {
        renderExplore("m4-explore-tab-landscape-night.png", dark = true, inSidePanel = true)
    }

    // -------------------------------------------------------- PlanningSheet

    private fun renderPlanning(
        file: String,
        expanded: Boolean,
        inSidePanel: Boolean = false,
        dark: Boolean = false,
    ) {
        val route = PlannedRoute(loopPoints(), 38.4, 420.0, pavedKm = 20.0, unpavedKm = 18.4)
        compose.setContent {
            val layoutClass = windowLayoutClassFor(LocalConfiguration.current.screenWidthDp)
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides layoutClass,
            ) {
                TrailscapeTheme(darkTheme = dark) {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (inSidePanel) Modifier.padding(OverlayScreenPadding) else Modifier),
                                contentAlignment = if (inSidePanel) Alignment.TopStart else Alignment.BottomCenter,
                            ) {
                                Box(modifier = if (inSidePanel) Modifier.width(MapSidePanelWidth) else Modifier) {
                                    PlanningSheet(
                                        expanded = expanded,
                                        onExpandedChange = {},
                                        half = false,
                                        onHalfChange = {},
                                        profile = RouteProfile.GRAVEL,
                                        onProfileChange = {},
                                        roundTrip = false,
                                        onRoundTripChange = {},
                                        waypoints = listOf(
                                            Waypoint(48.4, 9.4, name = "Mein Standort"),
                                            Waypoint(48.5, 9.45, name = "Bärenschlössle"),
                                        ),
                                        route = route,
                                        busy = false,
                                        error = null,
                                        maxHeight = 560.dp,
                                        source = RoutingSource.SERVER,
                                        onUseMyPosition = {},
                                        onRemoveWaypoint = {},
                                        onAddWaypointViaSearch = {},
                                        onClear = {},
                                        onSave = {},
                                        onShare = {},
                                        onNavigate = {},
                                        onHoverPoint = {},
                                        onClose = {},
                                        inSidePanel = inSidePanel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun planningSheet_peek_narrow360_font130_de() {
        RuntimeEnvironment.setFontScale(1.3f)
        renderPlanning("m4-planning-peek-360-130-de.png", expanded = false)
    }

    @Test
    @Config(qualifiers = TestLocales.S25_EN)
    fun planningSheet_expanded_412_en() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        renderPlanning("m4-planning-expanded-412-en.png", expanded = true)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun planningSheet_tabS11Landscape_sidePanel_de() {
        renderPlanning("m4-planning-tab-landscape-de.png", expanded = true, inSidePanel = true)
    }

    // ----------------------------------- RouteGenerationSheet & RoundTripSetup

    private fun renderGeneration(file: String, inSidePanel: Boolean = false) {
        val list = sampleCandidates()
        compose.setContent {
            val layoutClass = windowLayoutClassFor(LocalConfiguration.current.screenWidthDp)
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalWindowLayoutClass provides layoutClass,
            ) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (inSidePanel) Modifier.padding(OverlayScreenPadding) else Modifier),
                                contentAlignment = if (inSidePanel) Alignment.TopStart else Alignment.BottomCenter,
                            ) {
                                Box(modifier = if (inSidePanel) Modifier.width(MapSidePanelWidth) else Modifier) {
                                    RouteGenerationSheet(
                                        state = RouteGenerationState(
                                            target = sampleCandidateTarget(),
                                            candidates = list,
                                            selectedIndex = 0,
                                        ),
                                        expanded = inSidePanel,
                                        onExpandedChange = {},
                                        route = list[0].route,
                                        candidatesMaxHeight = 360.dp,
                                        bodyMaxHeight = 240.dp,
                                        onStart = {},
                                        onCancel = {},
                                        onSelect = {},
                                        onNextSuggestions = {},
                                        onApply = {},
                                        onDiscard = {},
                                        onHoverPoint = {},
                                        inSidePanel = inSidePanel,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun generationSheet_narrow360_font130_de() {
        RuntimeEnvironment.setFontScale(1.3f)
        renderGeneration("m4-generation-360-130-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun generationSheet_tabS11Landscape_sidePanel_de() {
        renderGeneration("m4-generation-tab-landscape-de.png", inSidePanel = true)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun generationSheet_tabS11Landscape_sidePanel_en() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        renderGeneration("m4-generation-tab-landscape-en.png", inSidePanel = true)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun roundTripSetup_tabS11Landscape_sidePanel_de() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides windowLayoutClassFor(1280)) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(OverlayScreenPadding),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Box(Modifier.width(MapSidePanelWidth)) {
                                    RoundTripSetupSheet(
                                        startLabel = compose.activity.getString(R.string.map_screen_round_trip_from_position),
                                        distanceKm = 50,
                                        onDistanceChange = {},
                                        profile = RouteProfile.SCHOTTER,
                                        onProfileChange = {},
                                        discoveryMode = DiscoveryMode.NEW_AREAS,
                                        onDiscoveryModeChange = {},
                                        considerWind = true,
                                        onConsiderWindChange = {},
                                        onShowSuggestions = {},
                                        onClose = {},
                                        bottomInset = 0.dp,
                                        inSidePanel = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/m4-roundtrip-tab-landscape-de.png")
    }

    // --------------------- PlaceCard, RideCard, HistorySummary, SearchPicker

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun placeCard_tabS11Landscape_sidePanel_de() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides windowLayoutClassFor(1280)) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(OverlayScreenPadding),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Box(Modifier.width(MapSidePanelWidth)) {
                                    PlaceCard(
                                        place = Place("Bärenschlössle, Stuttgart, Baden-Württemberg", 48.75, 9.09),
                                        mode = MapMode.ERKUNDEN,
                                        distanceKm = 8.4,
                                        onRouteHere = {},
                                        onRoundTripHere = {},
                                        onAddWaypoint = {},
                                        onClose = {},
                                        inSidePanel = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/m4-place-tab-landscape-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun rideCard_tabS11Landscape_sidePanel_de() {
        val ride = samplePlannedRide()
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides windowLayoutClassFor(1280)) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(OverlayScreenPadding),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Box(Modifier.width(MapSidePanelWidth)) {
                                    RideCard(
                                        ride = ride,
                                        highlights = listOf(
                                            RideHighlight.WeekGoalReached(targetKm = 80, riddenKm = 92.0),
                                        ),
                                        navigating = false,
                                        onNavigate = {},
                                        onShare = {},
                                        onDelete = {},
                                        onOpenDetails = {},
                                        onClose = {},
                                        onHoverPoint = {},
                                        expanded = true,
                                        onExpandedChange = {},
                                        inSidePanel = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/m4-ridecard-tab-landscape-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun rideCard_compact360_130_de() {
        RuntimeEnvironment.setFontScale(1.3f)
        val ride = Ride(
            id = "m4-recorded-compact-1",
            name = "Abendrunde über die Alb",
            createdAt = 1_730_000_000_000L,
            stats = RideStats(
                distanceKm = 52.4,
                durationS = 7_200,
                movingTimeS = 6_900,
                ascentM = 620.0,
                descentM = 615.0,
                avgSpeedKmh = 26.2,
                avgHrBpm = 148,
                maxHrBpm = 176,
            ),
            planned = false,
            points = loopPoints(),
        )
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides windowLayoutClassFor(360)) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                RideCard(
                                    ride = ride,
                                    highlights = listOf(
                                        RideHighlight.WeekGoalReached(targetKm = 80, riddenKm = 95.0),
                                    ),
                                    navigating = false,
                                    onNavigate = {},
                                    onShare = {},
                                    onDelete = {},
                                    onOpenDetails = {},
                                    onClose = {},
                                    onHoverPoint = {},
                                    expanded = true,
                                    onExpandedChange = {},
                                    inSidePanel = false,
                                    bodyMaxHeight = 200.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/m4-ridecard-360-130-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun historySummary_tabS11Landscape_sidePanel_de() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides windowLayoutClassFor(1280)) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(OverlayScreenPadding),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Box(Modifier.width(MapSidePanelWidth)) {
                                    HistorySummarySheet(
                                        rideCount = 42,
                                        totalKm = 2345.6,
                                        tileCount = 312,
                                        squareSize = 6,
                                        clusterSize = 37,
                                        onClose = {},
                                        bottomInset = 0.dp,
                                        inSidePanel = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/m4-history-tab-landscape-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun searchPicker_tabS11Landscape_sidePanel_de() {
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides windowLayoutClassFor(1280)) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(OverlayScreenPadding),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Box(Modifier.width(MapSidePanelWidth)) {
                                    SearchPickerPanel(
                                        query = "Tübingen",
                                        onQueryChange = {},
                                        onSearch = {},
                                        busy = false,
                                        error = null,
                                        results = listOf(
                                            GeoResult("Tübingen, Baden-Württemberg, Deutschland", 48.52, 9.05),
                                            GeoResult("Tübinger Tor, Reutlingen, Deutschland", 48.49, 9.21),
                                        ),
                                        history = emptyList(),
                                        onSelect = {},
                                        onDismiss = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/m4-searchpicker-tab-landscape-de.png")
    }

    // --------------------------------------- Full TrailscapeApp Map Tab

    private fun captureFullMapApp(file: String) {
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        AppServices.rideStorage.saveRides(listOf(samplePlannedRide()))
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    Surface(Modifier.fillMaxSize()) {
                        TrailscapeApp()
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val mapLabel = compose.activity.getString(R.string.shell_nav_map_label)
        compose.onAllNodesWithText(mapLabel)[0].performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun fullMapScreen_tabS11Landscape_de() {
        captureFullMapApp("m4-mapscreen-tab-landscape-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun fullMapScreen_tabS11Landscape_en() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        captureFullMapApp("m4-mapscreen-tab-landscape-en.png")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun fullMapScreen_tabS11Portrait_de() {
        captureFullMapApp("m4-mapscreen-tab-portrait-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun fullMapScreen_narrow360_font130_de() {
        RuntimeEnvironment.setFontScale(1.3f)
        captureFullMapApp("m4-mapscreen-360-130-de.png")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun fullMapScreen_412_de() {
        captureFullMapApp("m4-mapscreen-412-de.png")
    }
}
