package de.trailscape.app.ui.rides

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.ONBOARDING_STORAGE_KEY
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.TrailscapeApp
import de.trailscape.app.ui.map.LONG_PRESS_HINT_STORAGE_KEY
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.i18n.AppLanguage
import kotlin.math.cos
import kotlin.math.sin
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Idee 3 „Eine Auswertung für alles": die Verlaufsliste mit einer
 * Indoor-Fahrt — Pille „Indoor" und Art-Filter (Alle · Outdoor · Indoor) auf
 * Englisch, dazu die gefilterte Liste. Eigene Klasse nach dem Muster von
 * `RidesScreenshotTest`; laeuft nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_EN, application = ScreenshotApplication::class)
class AuswertungScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private var saved: List<Ride> = emptyList()

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        AppServices.keyValueStore.setString(LONG_PRESS_HINT_STORAGE_KEY, "1")
        val storage = AppServices.rideStorage
        val ids = storage.listSummaries().summaries.map { it.id }
        saved = ids.mapNotNull { storage.loadRide(it) }
        ids.forEach { storage.deleteRide(it) }
        storage.saveRides(sampleRides())
    }

    @After
    fun cleanUp() {
        if (System.getProperty("trailscape.screenshots") != "true") return
        val storage = AppServices.rideStorage
        storage.listSummaries().summaries.forEach { storage.deleteRide(it.id) }
        if (saved.isNotEmpty()) storage.saveRides(saved)
    }

    @Test
    fun verlaufMitIndoorFilter() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        start()
        awaitText(string(R.string.idee03_filter_indoor))
        shot("72-verlauf-indoor-filter-en")

        compose.onAllNodesWithText(string(R.string.idee03_filter_indoor)).onFirst().performClick()
        settle()
        shot("73-verlauf-indoor-gefiltert-en")
    }

    private fun string(id: Int): String = compose.activity.getString(id)

    private fun start() {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
        ViewModelProvider(compose.activity)[AppViewModel::class.java].requestTab(AppTab.RIDES)
        settle()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Zwei Fahrten draussen, eine auf der Rolle (mit gemessener Leistung). */
    private fun sampleRides(): List<Ride> {
        val day = 24L * 60 * 60 * 1000
        val specs = listOf(
            Triple("Feierabendrunde", 2, false),
            Triple("Sweet-Spot auf der Rolle", 3, true),
            Triple("GA1 Kanalrunde", 5, false),
        )
        return specs.mapIndexed { index, (name, daysAgo, indoor) ->
            val start = now - daysAgo * day
            val km = 30.0 + index * 6
            val durationS = (km / 25.0 * 3600).toInt()
            val points = List(120) { i ->
                val t = i / 119.0 * 2 * Math.PI
                TrackPoint(
                    lat = 48.40 + 0.03 * sin(t) * (1 + index * 0.1),
                    lon = 9.05 + 0.05 * cos(t) + 0.01 * sin(3 * t),
                    ele = 500.0 + 40 * sin(2 * t),
                    time = start + (durationS * 1000L * i / 119),
                    hr = 130 + (10 * sin(t)).toInt(),
                    power = if (indoor) 220 else null,
                )
            }
            Ride(
                id = "idee03-$index",
                name = name,
                createdAt = start,
                stats = RideStats(
                    distanceKm = km,
                    ascentM = km * 9,
                    descentM = km * 9,
                    durationS = durationS,
                    movingTimeS = durationS,
                    avgSpeedKmh = 25.0,
                    avgHrBpm = 135,
                    maxHrBpm = 165,
                ),
                points = points,
                indoor = indoor,
            )
        }
    }
}
