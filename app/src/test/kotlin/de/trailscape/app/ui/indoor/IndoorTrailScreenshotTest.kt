package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.climb.Climb
import de.trailscape.core.indoor.climb.ClimbCategory
import de.trailscape.core.indoor.climb.ClimbEffort
import de.trailscape.core.indoor.climb.ClimbLive
import de.trailscape.core.indoor.climb.TrailLive
import de.trailscape.core.indoor.climb.TrailMarker
import de.trailscape.core.indoor.world.CameraMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/**
 * Bilder zu „Leben auf der Strecke" (Bergwertung, Kilometer): Einblendungen
 * im Fahr-HUD, Anstiege in der Auswertung, Schalter in den Einstellungen.
 * Laeuft wie die anderen Screenshot-Tests nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorTrailScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        content()
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Heller Himmel ueber Wiese: der schwierigste Hintergrund fuer das HUD. */
    @Composable
    private fun World(modifier: Modifier) {
        Canvas(modifier = modifier) {
            val horizon = size.height * 0.5f
            drawRect(Brush.verticalGradient(0f to Color(0xFF5E9BD6), 1f to Color(0xFFEAF3F9), endY = horizon), size = Size(size.width, horizon))
            drawRect(Color(0xFF6E9A4A), topLeft = Offset(0f, horizon), size = Size(size.width, size.height - horizon))
        }
    }

    private val climb = Climb(
        index = 1, startM = 6_200.0, endM = 8_600.0, gainM = 146.0, avgGradePct = 6.1, maxGradePct = 9.4,
        startEleM = 548.0, topEleM = 694.0, category = ClimbCategory.CAT3,
    )

    private val profile = IndoorProfileUi(
        lengthM = 12_000.0,
        distM = FloatArray(61) { it * 200f },
        eleM = FloatArray(61) { 560f + 40f * sin(it / 9.0).toFloat() + it * 1.2f },
        isLoop = false,
    )

    private fun session() = IndoorSessionState(
        phase = IndoorPhase.RUNNING, mode = IndoorMode.SIM, nowMs = 0, elapsedS = 1_472, distM = 7_300.0,
        routeDistM = 7_300.0, lap = 1, speedMps = 4.1, powerW = 262, cadenceRpm = 82, hrBpm = 158,
        gradePct = 6.8, surface = SurfaceKind.ASPHALT, crr = 0.0055, headwindMps = 0.4,
        gear = GearState(index = 9, count = 24, ratio = 1.7), ghost = null, erg = null,
        totals = IndoorTotals(7_300.0, 168.0, 1_460, 228.0, 234.0, 85.0, 150.0, 332.0),
    )

    private fun ride(trail: TrailLive?) = IndoorRideUi(
        routeName = "Hausrunde Ammersee", session = session(), trainerName = "KICKR CORE 7B2F",
        trainer = TrainerConnection.READY, shifter = ShifterConnection.READY, shifterBatteryPct = 82,
        render = IndoorRenderUi.Ready, camera = CameraMode.CHASE, profile = profile, notice = null,
        demoPowerW = null, demoCadenceRpm = null, ghostLabel = null, ergTitle = null,
        attribution = emptyList(), trail = trail,
    )

    @Composable
    private fun RideScreen(ui: IndoorRideUi) {
        IndoorRideScreen(
            ui = ui,
            worldSurface = { m -> World(m) },
            onShift = {}, onTogglePause = {}, onFinish = {}, onCameraChange = {},
            onDemoPower = {}, onDemoCadence = {}, onErgAdjust = {}, onDismissNotice = {},
        )
    }

    private fun effort(timeMs: Long, prev: Long?, newBest: Boolean, ranked: Boolean = true, lap: Int = 1, c: Climb = climb) = ClimbEffort(
        routeKey = "ride:r1", climbKey = "ride:r1@6200-8600", climb = c, lap = lap, ranked = ranked,
        timeMs = timeMs, previousBestMs = prev, newBest = newBest, achievedAtEpochMs = 0,
    )

    @Test
    fun ankuendigung() = shot("idee11-01-anstieg-ankuendigung") {
        RideScreen(ride(TrailLive(ClimbLive.Ahead(climb, toStartM = 312.0, bestMs = 612_000), TrailMarker.Kilometer(6))))
    }

    @Test
    fun amAnstieg() {
        shot("idee11-02-am-anstieg") {
            RideScreen(
                ride(
                    TrailLive(
                        ClimbLive.On(
                            climb = climb, remainingM = 1_300.0, remainingGainM = 79.0, gradePct = 6.8,
                            elapsedMs = 263_000, deltaToBestMs = -12_000, ranked = true, bestMs = 612_000,
                        ),
                        null,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Am Anstieg").assertExists()
        compose.onNodeWithText("−0:12").assertExists()
    }

    @Test
    fun gipfelNeueBestzeit() {
        shot("idee11-03-gipfel-neue-bestzeit") {
            RideScreen(ride(TrailLive(ClimbLive.Done(effort(589_000, 612_000, newBest = true)), null)))
        }
        compose.onNodeWithText("Neue Bestzeit · 0:23 schneller").assertExists()
    }

    @Test
    fun auswertung() = shot("idee11-04-auswertung-anstiege") {
        val first = climb.copy(index = 0, startM = 1_200.0, endM = 2_100.0, gainM = 41.0, avgGradePct = 4.6, topEleM = 602.0, category = ClimbCategory.UNCATEGORIZED)
        IndoorSummaryScreen(
            IndoorSummaryUi(
                routeName = "Hausrunde Ammersee", mode = IndoorMode.SIM, distanceKm = 18.4, movingS = 2_948,
                ascentM = 212.0, avgPowerW = 214, normalizedPowerW = 228, avgCadenceRpm = 88, avgHrBpm = 146,
                workKj = 631, avgSpeedKmh = 22.5, laps = 1, ghostLabel = null, ghostGapS = null, tooShort = false,
                climbs = listOf(
                    effort(201_000, null, newBest = true, c = first),
                    effort(589_000, 612_000, newBest = true),
                ),
            ),
            {}, {}, {},
        )
    }

    @Test
    fun einstellungen() {
        shot("idee11-05-einstellungen") {
            IndoorSettingsScreen(
                ui = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE 7B2F", shifterName = null),
                catalog = IndoorCatalogUi(emptyList()),
                onChainringChange = {}, onGearTableChange = {}, onTimeOfDayChange = {}, onOtherRidersChange = {},
                onOpenWorlds = {}, onRefreshCatalog = {}, onOpenDiagnostics = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Einblendungen auf der Strecke").assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun amAnstiegEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("idee11-06-am-anstieg-en") {
            RideScreen(
                ride(
                    TrailLive(
                        ClimbLive.On(
                            climb = climb, remainingM = 1_300.0, remainingGainM = 79.0, gradePct = 6.8,
                            elapsedMs = 263_000, deltaToBestMs = 8_000, ranked = true, bestMs = 612_000,
                        ),
                        null,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Climbing").assertExists()
    }
}
