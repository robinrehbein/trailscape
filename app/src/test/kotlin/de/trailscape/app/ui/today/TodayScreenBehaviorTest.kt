package de.trailscape.app.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.LightAtlasColors
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.core.AscentPreference
import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.ReadinessBand
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.WeekKind
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.savePlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral tests for `TodayScreen` and `HeroCard`:
 * - Core Path 3: single tap on Hero CTA passes `autoStart = true` and requests `AppTab.MAP`
 * - Responsive header: `SettingsAction` is visible on `Compact` and omitted on `Medium`/`Expanded` (rail hosts it)
 * - Two-pane layout renders left + right sections on `Expanded`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class TodayScreenBehaviorTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        AppServices.rideStorage.saveRides(seedRides())
        savePlan(AppServices.trainingPlanStore, seedPlanWithTodayActive())
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
        AppServices.rideStorage.saveRides(emptyList())
        savePlan(AppServices.trainingPlanStore, null)
    }

    @Test
    fun `HeroCard primary button passes autoStart true on single tap for Core Path 3`() {
        lateinit var viewModel: AppViewModel
        compose.setContent {
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            ProvideTodayLocals(WindowLayoutClass.Compact) {
                TodayScreen(viewModel)
            }
        }
        compose.waitForIdle()

        val cta = compose.activity.getString(R.string.today_offer_action)
        compose.onNodeWithText(cta).assertExists().performClick()
        compose.waitForIdle()

        assertNotNull("pendingRouteTarget should be set after single tap", viewModel.pendingRouteTarget.value)
        assertTrue("autoStart must be true for Core Path 3 (1 tap)", viewModel.consumeRouteTarget())
        assertEquals(AppTab.MAP, viewModel.tabRequest.value)
    }

    @Test
    fun `HeroCard displays distance ascent and duration metrics in tabular Atlas card`() {
        val offer = TodayOffer(
            target = RouteTarget(
                distanceKm = 42.5,
                ascentPreference = AscentPreference.MODERAT,
                durationH = 1.75,
                speedKmh = 24.0,
                intensity = SessionIntensity.GRUNDLAGE,
                label = "Grundlage",
                source = RouteTargetSource.PLAN,
            ),
            restDay = false,
            firstRound = false,
        )
        var built = false
        compose.setContent {
            ProvideTodayLocals(WindowLayoutClass.Compact) {
                HeroCard(
                    score = 82,
                    band = ReadinessBand.HART,
                    headline = UiText.Plain("Gut erholt. Heute eine lockere Runde."),
                    sentence = UiText.Plain("43 km ruhig fahren."),
                    offer = offer,
                    onBuildRoute = { built = true },
                    onWhy = {},
                    healthHint = HealthHint.NONE,
                    onOpenHealth = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("42,5 km").assertExists()
        compose.onNodeWithText("~510 hm").assertExists()
        compose.onNodeWithText("1:45 h").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.today_offer_action)).performClick()
        assertTrue(built)
    }

    @Test
    fun `SettingsAction is shown on Compact and hidden on Expanded where NavigationRail owns it`() {
        val settingsCd = compose.activity.getString(R.string.common_settings_cd)
        lateinit var viewModel: AppViewModel

        compose.setContent {
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            ProvideTodayLocals(WindowLayoutClass.Expanded) {
                TodayScreen(viewModel)
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription(settingsCd).assertCountEquals(0)
        val weekEyebrow = compose.activity.getString(R.string.today_week_eyebrow)
        val goalEyebrow = compose.activity.getString(R.string.today_goal_eyebrow)
        compose.onAllNodesWithText(weekEyebrow)[0].assertExists()
        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText(weekEyebrow)))
            .performScrollToKey("sec-ziel")
        compose.onAllNodesWithText(goalEyebrow)[0].assertExists()
    }

    @Test
    fun `TodayScreen shows SettingsAction on Compact`() {
        val settingsCd = compose.activity.getString(R.string.common_settings_cd)
        compose.setContent {
            val viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            ProvideTodayLocals(WindowLayoutClass.Compact) {
                TodayScreen(viewModel)
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription(settingsCd).assertCountEquals(1)
    }

    @Test
    fun `Plan rest day renders zero orange buttons and suppresses outdoor TodayPlaceCard`() {
        savePlan(AppServices.trainingPlanStore, seedPlanWithTodayRest())
        lateinit var viewModel: AppViewModel
        compose.setContent {
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            ProvideTodayLocals(WindowLayoutClass.Compact) {
                TodayScreen(viewModel)
            }
        }
        compose.waitUntil(timeoutMillis = 25_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.today_rest_headline_plan))
                .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(compose.activity.getString(R.string.indoor_app_day_eyebrow))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        compose.onAllNodesWithText(compose.activity.getString(R.string.idee02_place_title_outdoor)).assertCountEquals(0)
        compose.onAllNodesWithText(compose.activity.getString(R.string.idee02_place_title_indoor)).assertCountEquals(0)
        assertEquals("Plan rest day must render 0 orange primary buttons", 0, countOrangeButtons())
    }

    @Test
    fun `Zieltag renders zero orange buttons and suppresses TodayPlaceCard`() {
        savePlan(AppServices.trainingPlanStore, seedPlanWithGoalToday())
        lateinit var viewModel: AppViewModel
        compose.setContent {
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            ProvideTodayLocals(WindowLayoutClass.Compact) {
                TodayScreen(viewModel)
            }
        }
        compose.waitUntil(timeoutMillis = 25_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.today_headline_event))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        compose.onAllNodesWithText(compose.activity.getString(R.string.idee02_place_title_outdoor)).assertCountEquals(0)
        assertEquals("Zieltag must render 0 orange primary buttons", 0, countOrangeButtons())
    }

    @Test
    fun `HeroCard hides goal-day route metrics when goalDistanceKm is zero`() {
        compose.setContent {
            ProvideTodayLocals(WindowLayoutClass.Compact) {
                HeroCard(
                    score = null,
                    band = null,
                    headline = UiText.Plain("Heute ist dein Zieltag."),
                    sentence = UiText.Plain("Viel Erfolg."),
                    offer = null,
                    onBuildRoute = {},
                    onWhy = {},
                    healthHint = HealthHint.NONE,
                    onOpenHealth = {},
                    goalDistanceKm = 0.0,
                    goalAscentM = null,
                    goalDurationMin = null,
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("0,0 km").assertCountEquals(0)
        compose.onAllNodesWithText("0:01 h").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun `Expanded right pane preserves scroll position across SaveableStateHolder tab round-trip`() {
        var activeTab by mutableStateOf("today")
        lateinit var viewModel: AppViewModel
        compose.setContent {
            viewModel = ViewModelProvider(compose.activity)[AppViewModel::class.java]
            val stateHolder = rememberSaveableStateHolder()
            ProvideTodayLocals(WindowLayoutClass.Expanded) {
                if (activeTab == "today") {
                    stateHolder.SaveableStateProvider("today") {
                        TodayScreen(viewModel)
                    }
                } else {
                    stateHolder.SaveableStateProvider("other") {
                        Text("Other")
                    }
                }
            }
        }
        val weekEyebrow = compose.activity.getString(R.string.today_week_eyebrow)
        val goalEyebrow = compose.activity.getString(R.string.today_goal_eyebrow)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(weekEyebrow).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        val weekTopInitial = compose.onNodeWithText(weekEyebrow).fetchSemanticsNode().boundsInRoot.top

        compose.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasText(weekEyebrow)))
            .performScrollToKey("ziel")
        compose.waitForIdle()

        compose.onNodeWithText(goalEyebrow).assertIsDisplayed()
        val goalTopBefore = compose.onNodeWithText(goalEyebrow).fetchSemanticsNode().boundsInRoot.top
        val weekTopAfterScroll = compose.onAllNodesWithText(weekEyebrow).fetchSemanticsNodes()
            .firstOrNull()?.boundsInRoot?.top
        assertTrue(
            "Right pane should have scrolled down before tab switch",
            weekTopAfterScroll == null || weekTopAfterScroll < weekTopInitial - 50f,
        )

        activeTab = "other"
        compose.waitForIdle()
        activeTab = "today"
        compose.waitForIdle()

        compose.onNodeWithText(goalEyebrow).assertIsDisplayed()
        val goalTopAfter = compose.onNodeWithText(goalEyebrow).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "Right pane scroll should be preserved (before=$goalTopBefore, after=$goalTopAfter)",
            abs(goalTopBefore - goalTopAfter) < 2f,
        )
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun `360 dp with 1_3x font scale renders German first-round CTA without ellipsis`() {
        RuntimeEnvironment.setFontScale(1.3f)
        val offer = TodayOffer(
            target = RouteTarget(
                distanceKm = 26.0,
                ascentPreference = AscentPreference.FLACH,
                durationH = 1.2,
                speedKmh = 22.0,
                intensity = SessionIntensity.LOCKER,
                label = "Erste Runde",
                source = RouteTargetSource.TAGESEMPFEHLUNG,
            ),
            restDay = false,
            firstRound = true,
        )
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale = 1.3f)) {
                ProvideTodayLocals(WindowLayoutClass.Compact) {
                    HeroCard(
                        score = null,
                        band = null,
                        headline = UiText.Plain("Für den Anfang: eine ruhige Runde."),
                        sentence = UiText.Plain("26 km locker fahren."),
                        offer = offer,
                        onBuildRoute = {},
                        onWhy = {},
                        healthHint = HealthHint.NONE,
                        onOpenHealth = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        val ctaText = compose.activity.getString(R.string.today_offer_first_round_action, 26)
        val layout = textLayoutOf(ctaText)
        assertFalse("First-round CTA should not visually overflow at 360dp + 1.3x", layout.hasVisualOverflow)
        assertFalse(
            "First-round CTA must not ellipsize at 360dp + 1.3x",
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) },
        )
    }

    private fun textLayoutOf(text: String): TextLayoutResult {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun countOrangeButtons(): Int {
        val targetArgb = LightAtlasColors.action.toArgb()
        val contentView = compose.activity.findViewById<android.view.View>(android.R.id.content)
        val bitmap = android.graphics.Bitmap.createBitmap(
            contentView.width.coerceAtLeast(1),
            contentView.height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        contentView.draw(android.graphics.Canvas(bitmap))
        return try {
            val buttons = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .fetchSemanticsNodes()
            var orangeCount = 0
            for (node in buttons) {
                val b = node.boundsInRoot
                if (b.width <= 4f || b.height <= 4f) continue
                val cx = ((b.left + b.right) / 2f).roundToInt().coerceIn(0, bitmap.width - 1)
                val cy = ((b.top + b.bottom) / 2f).roundToInt().coerceIn(0, bitmap.height - 1)
                val lx = (b.left + 8f).roundToInt().coerceIn(0, bitmap.width - 1)
                val sample = bitmap.getPixel(cx, cy)
                val edgeSample = bitmap.getPixel(lx, cy)
                if (sample == targetArgb || edgeSample == targetArgb) {
                    orangeCount++
                }
            }
            orangeCount
        } finally {
            bitmap.recycle()
        }
    }

    @androidx.compose.runtime.Composable
    private fun ProvideTodayLocals(layoutClass: WindowLayoutClass, content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(
            LocalAppLanguage provides AppLanguage.DE,
            LocalCoreTexts provides CoreTextsDe,
            LocalAppFormats provides AppFormats(AppLanguage.DE),
            LocalWindowLayoutClass provides layoutClass,
        ) {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }

    private fun seedRides(): List<Ride> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        return listOf(2, 5, 10).mapIndexed { i, daysAgo ->
            val start = now - daysAgo * dayMs
            Ride(
                id = "behavior-$i",
                name = "Runde $i",
                createdAt = start,
                stats = RideStats(
                    distanceKm = 36.0,
                    ascentM = 320.0,
                    descentM = 320.0,
                    durationS = 5400,
                    movingTimeS = 5400,
                    avgSpeedKmh = 24.0,
                ),
                points = List(40) { idx ->
                    val t = idx / 39.0 * 2 * Math.PI
                    TrackPoint(lat = 48.4 + 0.02 * sin(t), lon = 9.1 + 0.03 * cos(t), ele = 500.0, time = start + idx * 1000L)
                },
            )
        }
    }

    private fun seedPlanWithTodayActive(): TrainingPlan {
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
            goal = Goal(name = "Alb-Gold", distanceKm = 65.0, ascentM = 750.0, date = goalMs, targetDurationMin = 150),
            level = FitnessLevel.entries.first(),
            weeks = listOf(
                TrainingWeek(
                    index = 0,
                    start = startMs,
                    end = endMs,
                    kind = WeekKind.AUFBAU,
                    targetKm = 90,
                    sessions = listOf(
                        TrainingSession(
                            day = todayCode,
                            title = "Grundlage",
                            description = "Ruhig fahren.",
                            targetKm = 45,
                            intensity = SessionIntensity.GRUNDLAGE,
                            durationMin = 105,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun seedPlanWithTodayRest(): TrainingPlan {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val codes = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val otherDayCode = codes[today.dayOfWeek.value % 7]
        val startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val goalMs = monday.plusWeeks(8).plusDays(5).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return TrainingPlan(
            createdAt = startMs,
            goal = Goal(name = "Alb-Gold", distanceKm = 65.0, ascentM = 750.0, date = goalMs, targetDurationMin = 150),
            level = FitnessLevel.entries.first(),
            weeks = listOf(
                TrainingWeek(
                    index = 0,
                    start = startMs,
                    end = endMs,
                    kind = WeekKind.AUFBAU,
                    targetKm = 90,
                    sessions = listOf(
                        TrainingSession(
                            day = otherDayCode,
                            title = "Grundlage",
                            description = "Ruhig fahren.",
                            targetKm = 45,
                            intensity = SessionIntensity.GRUNDLAGE,
                            durationMin = 105,
                        ),
                    ),
                ),
            ),
        )
    }

    private fun seedPlanWithGoalToday(): TrainingPlan {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val codes = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val todayCode = codes[today.dayOfWeek.value - 1]
        val startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val goalMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return TrainingPlan(
            createdAt = startMs,
            goal = Goal(name = "Alb-Gold", distanceKm = 100.0, ascentM = 1200.0, date = goalMs, targetDurationMin = 240),
            level = FitnessLevel.entries.first(),
            weeks = listOf(
                TrainingWeek(
                    index = 0,
                    start = startMs,
                    end = endMs,
                    kind = WeekKind.ZIELWOCHE,
                    targetKm = 100,
                    sessions = listOf(
                        TrainingSession(
                            day = todayCode,
                            title = "Alb-Gold",
                            description = "Zieltag.",
                            targetKm = 100,
                            intensity = SessionIntensity.HART,
                            durationMin = 240,
                            isEvent = true,
                        ),
                    ),
                ),
            ),
        )
    }
}
