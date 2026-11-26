package de.trailscape.app.ui.today

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.WeekKind
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.savePlan
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Screenshot-Matrix fuer M2 (`TodayScreen` im Atlas-Stil) ueber alle geforderten
 * Geraeteklassen, Schriftgroessen (1,3x), Dunkelmodus und Sprachen (DE / EN).
 *
 * Laeuft mit `-Pscreenshots`; die Bilder landen als `today-atlas-*.png` in
 * `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class TodayAtlasScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        AppServices.rideStorage.saveRides(sampleRides())
        savePlan(AppServices.trainingPlanStore, activeTodayPlan())
    }

    @After
    fun cleanup() {
        RuntimeEnvironment.setFontScale(1.0f)
        AppServices.rideStorage.saveRides(emptyList())
        savePlan(AppServices.trainingPlanStore, null)
        AppServices.keyValueStore.remove(ONBOARDING_STORAGE_KEY)
        AppServices.keyValueStore.remove(LONG_PRESS_HINT_STORAGE_KEY)
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun narrow360De() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-360-de")
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun narrow360En() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        captureToday("today-atlas-360-en")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun phone412De() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-412-de")
    }

    @Test
    @Config(qualifiers = TestLocales.S25_EN)
    fun phone412En() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        captureToday("today-atlas-412-en")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun tabPortraitDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-tab-s11-portrait-de")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun tabPortraitEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        captureToday("today-atlas-tab-s11-portrait-en")
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabLandscapeDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-tab-s11-landscape-de", expectTwoPane = true)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun tabLandscapeEn() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        captureToday("today-atlas-tab-s11-landscape-en", expectTwoPane = true)
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun narrow360Font13De() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-360-font13-de", fontScale = 1.3f)
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun narrow360Font13En() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        captureToday("today-atlas-360-font13-en", fontScale = 1.3f)
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabLandscapeFont13De() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-tab-s11-landscape-font13-de", fontScale = 1.3f, expectTwoPane = true)
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h891dp-night-xxhdpi")
    fun phone412DarkDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-412-dark-de", dark = true)
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w1280dp-h800dp-night-xhdpi")
    fun tabLandscapeDarkDe() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        captureToday("today-atlas-tab-s11-landscape-dark-de", dark = true, expectTwoPane = true)
    }

    private fun captureToday(
        fileName: String,
        fontScale: Float = 1.0f,
        dark: Boolean = false,
        expectTwoPane: Boolean = false,
    ) {
        if (fontScale != 1.0f) {
            RuntimeEnvironment.setFontScale(fontScale)
        }
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalMapRenderingAvailable provides false,
                LocalDensity provides Density(baseDensity.density, fontScale = fontScale),
            ) {
                TrailscapeTheme(darkTheme = dark) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        TrailscapeApp()
                    }
                }
            }
        }
        val title = compose.activity.getString(R.string.today_screen_title)
        val why = compose.activity.getString(R.string.today_hero_why_action)
        val weekEyebrow = compose.activity.getString(R.string.today_week_eyebrow)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(why).fetchSemanticsNodes().isNotEmpty() &&
                (!expectTwoPane || compose.onAllNodesWithText(weekEyebrow).fetchSemanticsNodes().isNotEmpty())
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$fileName.png")

        compose.onAllNodesWithText(title)[0].assertExists()
        compose.onAllNodesWithText(why)[0].assertExists()
        compose.onAllNodesWithText(compose.activity.getString(R.string.today_offer_action))[0].assertExists()
        if (expectTwoPane) {
            compose.onAllNodesWithText(weekEyebrow)[0].assertExists()
            compose.onAllNodesWithText(compose.activity.getString(R.string.progress_next_eyebrow))[0].assertExists()
            // SettingsAction sits in AtlasNavigationRail only (count = 1), not duplicated in ScreenHeader.
            val settingsCd = compose.activity.getString(R.string.common_settings_cd)
            assertEquals(1, compose.onAllNodesWithContentDescription(settingsCd).fetchSemanticsNodes().size)
        }
    }

    private fun sampleRides(): List<Ride> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60 * 60 * 1000
        val specs = listOf(
            Triple("Feierabendrunde", 2, 32.4),
            Triple("GA1 Kanalrunde", 4, 46.1),
            Triple("Schotterschleife Nord", 12, 61.0),
            Triple("Waldkante West", 16, 28.7),
            Triple("Intervalle am Deich", 28, 38.9),
            Triple("Sonntagsrunde", 32, 55.2),
        )
        return specs.mapIndexed { index, (name, daysAgo, km) ->
            val start = now - daysAgo * dayMs
            val durationS = (km / 23.0 * 3600).toInt()
            val points = List(80) { i ->
                val t = i / 79.0 * 2 * Math.PI
                TrackPoint(
                    lat = 48.40 + 0.03 * sin(t) * (1 + index * 0.1),
                    lon = 9.05 + 0.05 * cos(t) + 0.01 * sin(3 * t),
                    ele = 500.0 + 40 * sin(2 * t),
                    time = start + (durationS * 1000L * i / 79),
                    hr = 128 + (10 * sin(t)).toInt(),
                )
            }
            Ride(
                id = "atlas-$index",
                name = name,
                createdAt = start,
                stats = RideStats(
                    distanceKm = km,
                    ascentM = km * 9,
                    descentM = km * 9,
                    durationS = durationS,
                    movingTimeS = durationS,
                    avgSpeedKmh = 23.0,
                    avgHrBpm = 132,
                    maxHrBpm = 168,
                ),
                points = points,
            )
        }
    }

    private fun activeTodayPlan(): TrainingPlan {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val monday = today.with(DayOfWeek.MONDAY)
        val codes = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val todayCode = codes[today.dayOfWeek.value - 1]
        val extraCode = codes[(today.dayOfWeek.value + 1) % 7]
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
                            title = "Grundlage",
                            description = "Ruhig fahren.",
                            targetKm = 42,
                            intensity = SessionIntensity.GRUNDLAGE,
                            durationMin = 100,
                        ),
                        TrainingSession(
                            day = extraCode,
                            title = "Lange Tour",
                            description = "Gleichmaessig fahren.",
                            targetKm = 53,
                            intensity = SessionIntensity.GRUNDLAGE,
                            durationMin = 140,
                        ),
                    ),
                ),
            ),
        )
    }
}
