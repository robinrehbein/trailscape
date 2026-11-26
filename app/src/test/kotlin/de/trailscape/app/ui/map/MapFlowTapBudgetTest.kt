package de.trailscape.app.ui.map

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.UiText
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.core.AscentPreference
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RideHighlight
import de.trailscape.core.RideStats
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Sichert die Tipp-Obergrenzen der Karten-Kernwege (Phase 4):
 *  - Pfad 2a (`Karte -> Route planen -> 2 Kartentipps -> Losfahren <= 4`):
 *    Im eingeklappten Planungsblatt steht „Losfahren" sofort bereit, ohne
 *    zusaetzliches Aufziehen; im Tablet-Seitenpanel ebenso.
 *  - Pfad 3b (`Heute -> Losfahren/Route = 1 Tipp`):
 *    `shouldAutoLaunchGeneratedRoute` startet automatisch genau bei
 *    `autoStartPending && !running && selected != null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class MapFlowTapBudgetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun sampleRoute(): PlannedRoute {
        val points = (0..40).map { i ->
            TrackPoint(
                lat = 48.4 + 0.002 * i,
                lon = 9.4 + 0.001 * i,
                ele = 650.0 + 40.0 * sin(i / 6.0),
            )
        }
        return PlannedRoute(points, distanceKm = 24.5, ascentM = 310.0)
    }

    private fun sampleWaypoints() = listOf(
        Waypoint(48.40, 9.40, name = "Start"),
        Waypoint(48.48, 9.44, name = "Ziel"),
    )

    @Test
    fun path2a_peekPlanningSheetShowsSingleStartActionWhenRouteReady() {
        var navigated = 0
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            PlanningSheet(
                                expanded = false,
                                onExpandedChange = {},
                                half = false,
                                onHalfChange = {},
                                profile = RouteProfile.GRAVEL,
                                onProfileChange = {},
                                roundTrip = false,
                                onRoundTripChange = {},
                                waypoints = sampleWaypoints(),
                                route = sampleRoute(),
                                busy = false,
                                error = null,
                                maxHeight = 400.dp,
                                onUseMyPosition = {},
                                onRemoveWaypoint = {},
                                onAddWaypointViaSearch = {},
                                onClear = {},
                                onSave = {},
                                onShare = {},
                                onNavigate = { navigated++ },
                                onHoverPoint = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
        val startText = compose.activity.getString(R.string.map_planning_start_action)
        val nodes = compose.onAllNodesWithText(startText).fetchSemanticsNodes()
        assertEquals("Genau eine Hauptaktion im eingeklappten Planungsblatt", 1, nodes.size)
        compose.onNodeWithText(startText)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, navigated)
    }

    @Test
    fun path2a_peekPlanningSheetHidesStartActionWithoutRouteAndDisablesWhenBusy() {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            PlanningSheet(
                                expanded = false,
                                onExpandedChange = {},
                                half = false,
                                onHalfChange = {},
                                profile = RouteProfile.GRAVEL,
                                onProfileChange = {},
                                roundTrip = false,
                                onRoundTripChange = {},
                                waypoints = listOf(Waypoint(48.40, 9.40)),
                                route = null,
                                busy = false,
                                error = null,
                                maxHeight = 400.dp,
                                onUseMyPosition = {},
                                onRemoveWaypoint = {},
                                onAddWaypointViaSearch = {},
                                onClear = {},
                                onSave = {},
                                onShare = {},
                                onNavigate = {},
                                onHoverPoint = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
        val startText = compose.activity.getString(R.string.map_planning_start_action)
        compose.onNodeWithText(startText).assertDoesNotExist()
    }

    @Test
    fun path2a_peekPlanningSheetDisablesStartActionWhileCalculating() {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            PlanningSheet(
                                expanded = false,
                                onExpandedChange = {},
                                half = false,
                                onHalfChange = {},
                                profile = RouteProfile.GRAVEL,
                                onProfileChange = {},
                                roundTrip = false,
                                onRoundTripChange = {},
                                waypoints = sampleWaypoints(),
                                route = sampleRoute(),
                                busy = true,
                                error = null,
                                maxHeight = 400.dp,
                                onUseMyPosition = {},
                                onRemoveWaypoint = {},
                                onAddWaypointViaSearch = {},
                                onClear = {},
                                onSave = {},
                                onShare = {},
                                onNavigate = {},
                                onHoverPoint = {},
                                onClose = {},
                            )
                        }
                    }
                }
            }
        }
        val startText = compose.activity.getString(R.string.map_planning_start_action)
        compose.onNodeWithText(startText).assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun path2a_expandedSidePanelShowsSingleStartActionImmediately() {
        var navigated = 0
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides WindowLayoutClass.Expanded) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.TopStart) {
                                PlanningSheet(
                                    expanded = false,
                                    onExpandedChange = {},
                                    half = false,
                                    onHalfChange = {},
                                    profile = RouteProfile.GRAVEL,
                                    onProfileChange = {},
                                    roundTrip = false,
                                    onRoundTripChange = {},
                                    waypoints = sampleWaypoints(),
                                    route = sampleRoute(),
                                    busy = false,
                                    error = null,
                                    maxHeight = 600.dp,
                                    onUseMyPosition = {},
                                    onRemoveWaypoint = {},
                                    onAddWaypointViaSearch = {},
                                    onClear = {},
                                    onSave = {},
                                    onShare = {},
                                    onNavigate = { navigated++ },
                                    onHoverPoint = {},
                                    onClose = {},
                                    inSidePanel = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        val startText = compose.activity.getString(R.string.map_planning_start_action)
        val nodes = compose.onAllNodesWithText(startText).fetchSemanticsNodes()
        assertEquals("Genau eine Hauptaktion im Seitenpanel", 1, nodes.size)
        compose.onNodeWithText(startText)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, navigated)
    }

    @Test
    fun path3b_autoLaunchTriggersOnlyWhenPendingAndCandidateReady() {
        val target = RouteTarget(
            distanceKm = 40.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 2.0,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Grundlage",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val candidate = RouteCandidate(
            route = sampleRoute(),
            distanceKm = 40.0,
            ascentM = 310.0,
            score = 0.0,
            bearingDeg = 45.0,
            targetKm = 40.0,
        )

        assertTrue(
            shouldAutoLaunchGeneratedRoute(
                autoStartPending = true,
                state = RouteGenerationState(
                    target = target,
                    running = false,
                    candidates = listOf(candidate),
                    selectedIndex = 0,
                ),
            ),
        )
        assertFalse(
            shouldAutoLaunchGeneratedRoute(
                autoStartPending = false,
                state = RouteGenerationState(
                    target = target,
                    running = false,
                    candidates = listOf(candidate),
                    selectedIndex = 0,
                ),
            ),
        )
        assertFalse(
            shouldAutoLaunchGeneratedRoute(
                autoStartPending = true,
                state = RouteGenerationState(
                    target = target,
                    running = true,
                    candidates = listOf(candidate),
                    selectedIndex = 0,
                ),
            ),
        )
        assertFalse(
            shouldAutoLaunchGeneratedRoute(
                autoStartPending = true,
                state = RouteGenerationState(
                    target = target,
                    running = false,
                    candidates = emptyList(),
                    selectedIndex = 0,
                ),
            ),
        )
        assertFalse(
            shouldAutoLaunchGeneratedRoute(
                autoStartPending = true,
                state = RouteGenerationState(target = null),
            ),
        )
    }

    @Test
    fun path3b_mapScreenPreservesPendingAutoStartAcrossFrame1AndLaunchesNavigation() {
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        Shadows.shadowOf(compose.activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val appViewModel = AppViewModel()
        val target = RouteTarget(
            distanceKm = 40.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 2.0,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Grundlage",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val candidate = RouteCandidate(
            route = sampleRoute(),
            distanceKm = 40.0,
            ascentM = 310.0,
            score = 0.0,
            bearingDeg = 45.0,
            targetKm = 40.0,
        )

        appViewModel.requestRouteGeneration(target, autoStart = true)
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(target, RouteGenerationController.state.value.target)

        try {
            RouteGenerationController.publishTestCandidates(listOf(candidate), selectedIndex = 0)
            compose.waitForIdle()

            assertNull(RouteGenerationController.state.value.target)
            val stopNavCd = compose.activity.getString(R.string.map_nav_stop_cd)
            compose.onNodeWithContentDescription(stopNavCd).assertIsDisplayed()
            val plannedLabel = compose.activity.getString(R.string.map_screen_planned_route_label)
            compose.onNodeWithContentDescription(plannedLabel, substring = true).assertIsDisplayed()
        } finally {
            RouteGenerationController.close()
            RecordingRepository.publishStopped(null)
        }
    }

    @Test
    fun path3b_stalePriorErrorDoesNotPreventAutoStartForNewTarget() {
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        Shadows.shadowOf(compose.activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val oldTarget = RouteTarget(
            distanceKm = 30.0,
            ascentPreference = AscentPreference.FLACH,
            durationH = 1.5,
            speedKmh = 20.0,
            intensity = SessionIntensity.LOCKER,
            label = "Alt",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val newTarget = RouteTarget(
            distanceKm = 42.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 2.0,
            speedKmh = 21.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Neu",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val candidate = RouteCandidate(
            route = sampleRoute(),
            distanceKm = 42.0,
            ascentM = 310.0,
            score = 0.0,
            bearingDeg = 90.0,
            targetKm = 42.0,
        )

        RouteGenerationController.open(oldTarget)
        RouteGenerationController.publishTestError(UiText.Plain("Network timeout"))
        assertFalse(RouteGenerationController.state.value.autoStartPending)

        val appViewModel = AppViewModel()
        appViewModel.requestRouteGeneration(newTarget, autoStart = true)
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(newTarget, RouteGenerationController.state.value.target)
        assertTrue(RouteGenerationController.state.value.autoStartPending)
        assertNull(RouteGenerationController.state.value.error)

        try {
            RouteGenerationController.publishTestCandidates(listOf(candidate), selectedIndex = 0)
            compose.waitForIdle()

            assertNull(RouteGenerationController.state.value.target)
            assertFalse(RouteGenerationController.state.value.autoStartPending)
            val stopNavCd = compose.activity.getString(R.string.map_nav_stop_cd)
            compose.onNodeWithContentDescription(stopNavCd).assertIsDisplayed()
        } finally {
            RouteGenerationController.close()
            RecordingRepository.publishStopped(null)
        }
    }

    @Test
    fun path3b_secondConsecutiveAutoStartLaunchesAfterClose() {
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        Shadows.shadowOf(compose.activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val firstTarget = RouteTarget(
            distanceKm = 32.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 1.6,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Erste",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val secondTarget = RouteTarget(
            distanceKm = 48.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 2.4,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Zweite",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val candidate = RouteCandidate(
            route = sampleRoute(),
            distanceKm = 48.0,
            ascentM = 310.0,
            score = 0.0,
            bearingDeg = 135.0,
            targetKm = 48.0,
        )

        val appViewModel = AppViewModel()
        RouteGenerationController.open(firstTarget, autoStartPending = true)
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            MapScreen(appViewModel = appViewModel)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(firstTarget, RouteGenerationController.state.value.target)

        try {
            RouteGenerationController.close()
            appViewModel.requestRouteGeneration(secondTarget, autoStart = true)
            compose.waitForIdle()

            assertEquals(secondTarget, RouteGenerationController.state.value.target)
            assertTrue(RouteGenerationController.state.value.autoStartPending)

            RouteGenerationController.publishTestCandidates(listOf(candidate), selectedIndex = 0)
            compose.waitForIdle()

            assertNull(RouteGenerationController.state.value.target)
            assertFalse(RouteGenerationController.state.value.autoStartPending)
            val stopNavCd = compose.activity.getString(R.string.map_nav_stop_cd)
            compose.onNodeWithContentDescription(stopNavCd).assertIsDisplayed()
        } finally {
            RouteGenerationController.close()
            RecordingRepository.publishStopped(null)
        }
    }

    @Test
    fun activeRecordingSuppressesPlanningSheetAndShowsOnlyLiveRecordingCta() {
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        val target = RouteTarget(
            distanceKm = 36.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 1.8,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Planungsrunde",
            source = RouteTargetSource.SELBST_GEWAEHLT,
        )
        val candidate = RouteCandidate(
            route = sampleRoute(),
            distanceKm = 36.0,
            ascentM = 310.0,
            score = 0.0,
            bearingDeg = 180.0,
            targetKm = 36.0,
        )
        val appViewModel = AppViewModel()

        try {
            RouteGenerationController.open(target)
            compose.setContent {
                CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                    TrailscapeTheme {
                        ProvideTestLanguage {
                            Surface(Modifier.fillMaxSize()) {
                                MapScreen(appViewModel = appViewModel)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()

            RouteGenerationController.publishTestCandidates(listOf(candidate), selectedIndex = 0)
            compose.waitForIdle()

            val applyText = compose.activity.getString(R.string.map_generation_apply_action)
            compose.onNodeWithText(applyText).assertIsDisplayed().performClick()
            compose.waitForIdle()

            val planningStartText = compose.activity.getString(R.string.map_planning_start_action)
            compose.onNodeWithText(planningStartText).assertIsDisplayed()

            RecordingRepository.publishStarted(
                startedAtMs = 1_700_000_000_000L,
                points = emptyList(),
                paused = false,
            )
            compose.waitForIdle()

            val rideModeText = compose.activity.getString(R.string.map_live_ride_mode_action)
            compose.onNodeWithText(rideModeText).assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithText(planningStartText).fetchSemanticsNodes().size)
        } finally {
            RouteGenerationController.close()
            RecordingRepository.publishStopped(null)
        }
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun searchPickerBackClosesSearchWithoutExitingPlanning() {
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        val appViewModel = AppViewModel()
        compose.setContent {
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
        compose.waitForIdle()

        val planAction = compose.activity.getString(R.string.map_explore_plan_route_action)
        compose.onNodeWithText(planAction).performClick()
        compose.waitForIdle()

        val addStartAction = compose.activity.getString(R.string.map_planning_add_start_action)
        compose.onNodeWithText(addStartAction).assertIsDisplayed().performClick()
        compose.waitForIdle()

        val searchCloseCd = compose.activity.getString(R.string.map_search_close_cd)
        compose.onNodeWithContentDescription(searchCloseCd).assertIsDisplayed()

        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodesWithContentDescription(searchCloseCd).fetchSemanticsNodes().size)
        compose.onNodeWithText(addStartAction).assertIsDisplayed()
        val buildLoopAction = compose.activity.getString(R.string.map_explore_build_loop_action)
        assertEquals(0, compose.onAllNodesWithText(buildLoopAction).fetchSemanticsNodes().size)
    }

    @Test
    fun activeRecordingSuppressesGenerationSheetAndPendingRouteTarget() {
        RouteGenerationController.close()
        RecordingRepository.publishStarted(
            startedAtMs = 1_700_000_000_000L,
            points = emptyList(),
            paused = false,
        )
        val appViewModel = AppViewModel()
        val target = RouteTarget(
            distanceKm = 35.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 1.75,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Grundlage",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        appViewModel.requestRouteGeneration(target, autoStart = true)

        try {
            compose.setContent {
                CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                    TrailscapeTheme {
                        ProvideTestLanguage {
                            Surface(Modifier.fillMaxSize()) {
                                MapScreen(appViewModel = appViewModel)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()

            assertNull(RouteGenerationController.state.value.target)
            val rideModeText = compose.activity.getString(R.string.map_live_ride_mode_action)
            compose.onNodeWithText(rideModeText).assertIsDisplayed()
            val genTitle = compose.activity.getString(R.string.map_generation_title)
            assertEquals(0, compose.onAllNodesWithText(genTitle).fetchSemanticsNodes().size)
        } finally {
            RouteGenerationController.close()
            RecordingRepository.publishStopped(null)
        }
    }

    @Test
    fun normalizeRoutePolyline_filtersNonFiniteCoordinates() {
        val poly = normalizeRoutePolyline(
            listOf(
                TrackPoint(lat = Double.NaN, lon = 9.4),
                TrackPoint(lat = 48.4, lon = 9.4),
                TrackPoint(lat = Double.POSITIVE_INFINITY, lon = 9.45),
                TrackPoint(lat = 48.5, lon = 9.5),
            ),
        )
        assertEquals(4, poly.size)
        assertTrue(poly.all { it.isFinite() })

        val onlyMalformed = normalizeRoutePolyline(
            listOf(
                TrackPoint(lat = Double.NaN, lon = 9.4),
                TrackPoint(lat = 48.4, lon = Double.NEGATIVE_INFINITY),
            ),
        )
        assertEquals(0, onlyMalformed.size)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun sidePanelGenerationKeepsFooterAndBodyNonZeroAt130FontScale() {
        RuntimeEnvironment.setFontScale(1.3f)
        val target = RouteTarget(
            distanceKm = 45.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 2.5,
            speedKmh = 18.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Grundlageneinheit",
            source = RouteTargetSource.PLAN,
        )
        val route = sampleRoute()
        val candidates = listOf(0.0, 120.0, 240.0).map { bearing ->
            RouteCandidate(
                route = route,
                distanceKm = 45.0,
                ascentM = 310.0,
                score = 0.0,
                bearingDeg = bearing,
                targetKm = 45.0,
            )
        }
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides WindowLayoutClass.Expanded) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(MapSidePanelWidth)
                                    .heightIn(max = 640.dp),
                            ) {
                                RouteGenerationSheet(
                                    state = RouteGenerationState(
                                        target = target,
                                        candidates = candidates,
                                        selectedIndex = 0,
                                        fromMapCenter = true,
                                    ),
                                    expanded = true,
                                    onExpandedChange = {},
                                    route = route,
                                    candidatesMaxHeight = 360.dp,
                                    bodyMaxHeight = 200.dp,
                                    onStart = {},
                                    onCancel = {},
                                    onSelect = {},
                                    onNextSuggestions = {},
                                    onApply = {},
                                    onDiscard = {},
                                    onHoverPoint = {},
                                    windEnabled = false,
                                    inSidePanel = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val applyText = compose.activity.getString(R.string.map_generation_apply_action)
        compose.onNodeWithText(applyText).assertIsDisplayed()
        val otherText = compose.activity.getString(R.string.map_generation_other_action)
        compose.onNodeWithText(otherText).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun sidePanelRideCardBodyScrollsToMoreMetricsAt130FontScale() {
        RuntimeEnvironment.setFontScale(1.3f)
        val recorded = Ride(
            id = "recorded-1",
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
            points = sampleRoute().points,
        )
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides WindowLayoutClass.Expanded) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .width(MapSidePanelWidth)
                                    .heightIn(max = 600.dp),
                            ) {
                                RideCard(
                                    ride = recorded,
                                    navigating = false,
                                    onNavigate = {},
                                    highlights = listOf(
                                        RideHighlight.WeekGoalReached(targetKm = 80, riddenKm = 95.0),
                                    ),
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
        compose.waitForIdle()
        val maxHrLabel = compose.activity.getString(R.string.map_ride_card_max_hr_label)
        compose.onNodeWithText(maxHrLabel).performScrollTo().assertIsDisplayed()
    }

    private fun sampleRecordedRideForCompact(id: String = "recorded-compact-1"): Ride = Ride(
        id = id,
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
        points = sampleRoute().points,
    )

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun rideCard_compactCollapsedAndExpanded_doNotCrashWithInfinityHeight() {
        RuntimeEnvironment.setFontScale(1.3f)
        val recorded = sampleRecordedRideForCompact()
        var expandedState by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides WindowLayoutClass.Compact) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                RideCard(
                                    ride = recorded,
                                    navigating = false,
                                    onNavigate = {},
                                    highlights = listOf(
                                        RideHighlight.WeekGoalReached(targetKm = 80, riddenKm = 95.0),
                                    ),
                                    onShare = {},
                                    onDelete = {},
                                    onOpenDetails = {},
                                    onClose = {},
                                    onHoverPoint = {},
                                    expanded = expandedState,
                                    onExpandedChange = { expandedState = it },
                                    inSidePanel = false,
                                    bodyMaxHeight = 160.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val rideAgainText = compose.activity.getString(R.string.map_ride_card_ride_again_action)
        compose.onNodeWithText(rideAgainText).assertIsDisplayed()

        expandedState = true
        compose.waitForIdle()
        val maxHrLabel = compose.activity.getString(R.string.map_ride_card_max_hr_label)
        compose.onNodeWithText(maxHrLabel).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun rideCard_mediumCollapsedAndExpanded_doNotCrashWithInfinityHeight() {
        val recorded = sampleRecordedRideForCompact("recorded-medium-1")
        var expandedState by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalWindowLayoutClass provides WindowLayoutClass.Medium) {
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                RideCard(
                                    ride = recorded,
                                    navigating = false,
                                    onNavigate = {},
                                    highlights = emptyList(),
                                    onShare = {},
                                    onDelete = {},
                                    onOpenDetails = {},
                                    onClose = {},
                                    onHoverPoint = {},
                                    expanded = expandedState,
                                    onExpandedChange = { expandedState = it },
                                    inSidePanel = false,
                                    bodyMaxHeight = 320.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val rideAgainText = compose.activity.getString(R.string.map_ride_card_ride_again_action)
        compose.onNodeWithText(rideAgainText).assertIsDisplayed()

        expandedState = true
        compose.waitForIdle()
        val maxHrLabel = compose.activity.getString(R.string.map_ride_card_max_hr_label)
        compose.onNodeWithText(maxHrLabel).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun mapScreen_compact_selectRide_rendersDockedSheetTourWithoutCrash() {
        RouteGenerationController.close()
        RecordingRepository.publishStopped(null)
        val recorded = sampleRecordedRideForCompact("recorded-mapscreen-1")
        val previous = AppServices.rideStorage.listSummaries().summaries.mapNotNull {
            AppServices.rideStorage.loadRide(it.id)
        }
        AppServices.rideStorage.saveRides(listOf(recorded))
        val appViewModel = AppViewModel()
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalMapRenderingAvailable provides false,
                    LocalWindowLayoutClass provides WindowLayoutClass.Compact,
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
            compose.waitForIdle()

            appViewModel.select(recorded.id)
            compose.waitForIdle()

            val rideAgainText = compose.activity.getString(R.string.map_ride_card_ride_again_action)
            compose.onNodeWithText(rideAgainText).assertIsDisplayed()
        } finally {
            appViewModel.select(null)
            AppServices.rideStorage.saveRides(previous)
            RouteGenerationController.close()
            RecordingRepository.publishStopped(null)
        }
    }

    @Test
    fun routeGenerationController_supersededRunDoesNotMutateStateOrEmitCancelToast() {
        RouteGenerationController.close()
        val oldTarget = RouteTarget(
            distanceKm = 30.0,
            ascentPreference = AscentPreference.FLACH,
            durationH = 1.5,
            speedKmh = 20.0,
            intensity = SessionIntensity.LOCKER,
            label = "Alt",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val newTarget = RouteTarget(
            distanceKm = 45.0,
            ascentPreference = AscentPreference.MODERAT,
            durationH = 2.25,
            speedKmh = 20.0,
            intensity = SessionIntensity.GRUNDLAGE,
            label = "Neu",
            source = RouteTargetSource.TAGESEMPFEHLUNG,
        )
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val messages = mutableListOf<String>()

        try {
            RouteGenerationController.open(oldTarget, autoStartPending = false)
            RouteGenerationController.start(
                context = compose.activity,
                start = TrackPoint(lat = 48.4, lon = 9.4),
                profile = RouteProfile.GRAVEL,
                fromMapCenter = false,
                onMessage = { synchronized(messages) { messages.add(it) } },
                exploredTiles = {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    throw kotlinx.coroutines.CancellationException("cancelled")
                },
            )
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            RouteGenerationController.open(newTarget, autoStartPending = true)
            release.countDown()
            Thread.sleep(200)

            val state = RouteGenerationController.state.value
            assertEquals(newTarget, state.target)
            assertTrue(state.autoStartPending)
            assertFalse(state.running)
            assertNull(state.error)
            assertEquals(emptyList<String>(), synchronized(messages) { messages.toList() })
        } finally {
            release.countDown()
            RouteGenerationController.close()
        }
    }
}
