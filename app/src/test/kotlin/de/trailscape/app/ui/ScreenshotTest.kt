package de.trailscape.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import de.trailscape.app.ui.map.LocalMapRenderingAvailable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.Goal
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.assessFitness
import de.trailscape.core.generatePlan
import de.trailscape.core.savePlan
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Screenshots der vier Tabs und der Einstellungen — das Werkzeug fuer den
 * visuellen Feinschliff ohne Geraet. Rendert die echte [TrailscapeApp] mit
 * Beispieldaten (sechs Touren, ein Ziel mit Plan) auf der JVM.
 *
 * Laeuft nur auf Wunsch:
 * `./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'`.
 * Die PNGs landen in `app/build/outputs/roborazzi/`.
 *
 * Geraet: Galaxy S25 (1080 × 2340 px, rund 411 × 891 dp).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun seed() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
        AppServices.keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1")
        val rides = sampleRides()
        AppServices.rideStorage.saveRides(rides)
        val goal = Goal(
            name = "Alb-Gold",
            distanceKm = 60.0,
            ascentM = 700.0,
            date = NOW + 8 * WEEK_MS,
            targetDurationMin = 130,
        )
        savePlan(AppServices.trainingPlanStore, generatePlan(goal, assessFitness(rides), now = NOW))
    }

    @Test
    fun tabs() {
        start()
        shot("01-heute")
        tab("Verlauf")
        shot("02-verlauf")
        tab("Training")
        shot("03-training")
        tab("Heute")
        compose.onAllNodesWithContentDescription("Einstellungen", substring = true)[0].performClick()
        settle()
        shot("04-einstellungen")
    }

    @Test
    fun details() {
        start()
        compose.onAllNodesWithText("Warum", substring = true)[0].performClick()
        settle()
        shot("05-warum")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
        tab("Verlauf")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        settle()
        shot("06-tour")
    }

    /** Ganze Seiten auf einem sehr hohen Bildschirm — zeigt, was sonst unter dem Rand liegt. */
    @Test
    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    fun tall() {
        start()
        shot("11-heute-lang")
        tab("Training")
        shot("13-training-lang")
        tab("Heute")
        compose.onAllNodesWithContentDescription("Einstellungen", substring = true)[0].performClick()
        settle()
        shot("14-einstellungen-lang")
    }

    /** Grosse Systemschrift (130 %) — findet abgeschnittene Beschriftungen. */
    @Test
    fun grosseSchrift() {
        RuntimeEnvironment.setFontScale(1.3f)
        start()
        shot("21-heute-gross")
        tab("Karte")
        shot("22-karte-gross")
        tab("Verlauf")
        shot("23-verlauf-gross")
        tab("Training")
        shot("24-training-gross")
    }

    /**
     * Einzelbilder mitten in den Uebergaengen (M3: Top level, Forward and
     * backward) — zeigt, dass ausgeblendet wird, bevor eingeblendet wird.
     */
    @Test
    fun uebergaenge() {
        start()
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Verlauf")[0].performClick()
        frames("31-tab")
        compose.onAllNodesWithText("Feierabendrunde")[0].performClick()
        frames("32-detail")
    }

    @Test
    fun karte() {
        start()
        tab("Karte")
        shot("07-karte")
    }

    private fun start() {
        compose.setContent {
            CompositionLocalProvider(LocalMapRenderingAvailable provides false) {
                TrailscapeTheme(darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize()) { TrailscapeApp() }
                }
            }
        }
        settle()
    }

    private fun tab(label: String) {
        compose.onAllNodesWithText(label)[0].performClick()
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun frames(name: String) {
        for (ms in listOf(60L, 150L, 240L, 400L)) {
            compose.mainClock.advanceTimeBy(if (ms == 60L) 60L else if (ms == 400L) 160L else 90L)
            shot("$name-${ms}ms")
        }
    }

    private fun shot(name: String) {
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}

/** Minimale Application: nur die Dienste, ohne Hintergrundarbeit. */
class ScreenshotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServices.init(this)
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 7 * DAY_MS
private val NOW = System.currentTimeMillis()

/** Sechs gefahrene Touren der letzten Wochen, jede mit einer kleinen Schleife als Spur. */
private fun sampleRides(): List<Ride> {
    val specs = listOf(
        Triple("Feierabendrunde", 2, 32.4),
        Triple("GA1 Kanalrunde", 4, 46.1),
        Triple("Schotterschleife Nord", 12, 61.0),
        Triple("Waldkante West", 16, 28.7),
        Triple("Intervalle am Deich", 28, 38.9),
        Triple("Sonntagsrunde", 32, 55.2),
    )
    return specs.mapIndexed { index, (name, daysAgo, km) ->
        val start = NOW - daysAgo * DAY_MS
        val durationS = (km / 23.0 * 3600).toInt()
        val points = List(120) { i ->
            val t = i / 119.0 * 2 * Math.PI
            TrackPoint(
                lat = 48.40 + 0.03 * sin(t) * (1 + index * 0.1),
                lon = 9.05 + 0.05 * cos(t) + 0.01 * sin(3 * t),
                ele = 500.0 + 40 * sin(2 * t),
                time = start + (durationS * 1000L * i / 119),
                hr = 128 + (10 * sin(t)).toInt(),
            )
        }
        Ride(
            id = "sample-$index",
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
