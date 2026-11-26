package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.DayRideEnd
import de.trailscape.core.indoor.ErgState
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.climb.Climb
import de.trailscape.core.indoor.climb.ClimbCategory
import de.trailscape.core.indoor.climb.ClimbLive
import de.trailscape.core.indoor.climb.TrailLive
import de.trailscape.core.indoor.climb.TrailMarker
import de.trailscape.core.indoor.world.CameraMode
import kotlin.math.sin
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi-Screenshot-Matrix fuer das M7 Indoor-Cockpit ([IndoorRideScreen]):
 * 360 dp, 412 dp, Galaxy Tab S11 hochkant (`800×1280 dp`) & quer (`1280×800 dp`),
 * Schriftgroesse `1.3f`, Deutsch & Englisch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class CockpitIndoorRideScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private fun shot(
        name: String,
        fontScale: Float = 1.0f,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val config = LocalConfiguration.current
            val layoutClass = windowLayoutClassFor(config.screenWidthDp)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalWindowLayoutClass provides layoutClass,
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
                // Aeusseres TrailscapeTheme prueft, dass IndoorRideScreen selbst CockpitTheme setzt.
                TrailscapeTheme {
                    ProvideTestLanguage {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            content()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Composable
    private fun WorldPlaceholder(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val horizon = size.height * 0.48f
            drawRect(
                Brush.verticalGradient(0f to Color(0xFF5E9BD6), 1f to Color(0xFFEAF3F9), endY = horizon),
                size = Size(size.width, horizon),
            )
            drawRect(
                Color(0xFF4F7D38),
                topLeft = Offset(0f, horizon),
                size = Size(size.width, size.height - horizon),
            )
        }
    }

    private val profile = IndoorProfileUi(
        lengthM = 12_000.0,
        distM = FloatArray(61) { it * 200f },
        eleM = FloatArray(61) { 560f + 40f * sin(it / 9.0).toFloat() + it * 1.2f },
        isLoop = false,
    )

    private val climb = Climb(
        index = 1,
        startM = 6_200.0,
        endM = 8_600.0,
        gainM = 146.0,
        avgGradePct = 6.1,
        maxGradePct = 9.4,
        startEleM = 548.0,
        topEleM = 694.0,
        category = ClimbCategory.CAT3,
    )

    private fun session(
        mode: IndoorMode = IndoorMode.SIM,
        phase: IndoorPhase = IndoorPhase.RUNNING,
        erg: ErgState? = null,
        ghost: GhostState? = GhostState(distM = 4_380.0, speedMps = 7.4, gapM = 120.0, gapS = 16.0),
    ) = IndoorSessionState(
        phase = phase,
        mode = mode,
        nowMs = 0,
        elapsedS = 1_472,
        distM = 4_260.0,
        routeDistM = 4_260.0,
        lap = 1,
        speedMps = 7.2,
        powerW = if (mode == IndoorMode.ERG) 220 else 236,
        cadenceRpm = 88,
        hrBpm = 148,
        gradePct = 4.6,
        surface = SurfaceKind.ASPHALT,
        crr = 0.0055,
        headwindMps = 1.2,
        gear = GearState(index = 14, count = 24, ratio = 2.3),
        ghost = ghost,
        erg = erg,
        totals = IndoorTotals(4_260.0, 96.0, 1_460, 228.0, 234.0, 87.0, 146.0, 332.0),
    )

    private fun rideUi(
        session: IndoorSessionState = session(),
        render: IndoorRenderUi = IndoorRenderUi.Ready,
        notice: IndoorNotice? = null,
        demoPowerW: Int? = null,
        demoCadenceRpm: Int? = null,
        ghostLabel: String? = "Fahrt vom 09.10.",
        ergTitle: String? = null,
        start: IndoorStartUi? = null,
        dayEnd: IndoorDayEndUi? = null,
        trail: TrailLive? = null,
    ) = IndoorRideUi(
        routeName = "Hausrunde Ammersee",
        session = session,
        trainerName = "KICKR CORE 7B2F",
        trainer = TrainerConnection.READY,
        shifter = ShifterConnection.READY,
        shifterBatteryPct = 82,
        render = render,
        camera = CameraMode.CHASE,
        profile = profile,
        notice = notice,
        demoPowerW = demoPowerW,
        demoCadenceRpm = demoCadenceRpm,
        ghostLabel = ghostLabel,
        ergTitle = ergTitle,
        attribution = emptyList(),
        start = start,
        dayEnd = dayEnd,
        trail = trail,
    )

    @Composable
    private fun RideScreen(ui: IndoorRideUi) {
        IndoorRideScreen(
            ui = ui,
            worldSurface = { m -> WorldPlaceholder(m) },
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

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerSim() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-tab-s11-quer-sim") {
            RideScreen(rideUi())
        }
        compose.onNodeWithContentDescription("Leistung: 236 W").assertExists()
        compose.onNodeWithContentDescription("Gang 14 / 24").assertExists()
        compose.onAllNodesWithContentDescription("Pause").onFirst().assertExists()
        compose.onNodeWithContentDescription("Fahrt beenden, gedrückt halten").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun tabletQuerSimEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("cockpit-indoor-tab-s11-quer-sim-en") {
            RideScreen(rideUi())
        }
        compose.onNodeWithContentDescription("Power: 236 W").assertExists()
        compose.onNodeWithContentDescription("Gear 14 / 24").assertExists()
        compose.onAllNodesWithContentDescription("Pause").onFirst().assertExists()
        compose.onNodeWithContentDescription("End ride, press and hold").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerErgGrosseSchrift() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        val erg = ErgState(stepIndex = 2, targetW = 220, stepRemainingS = 252, done = false)
        val trail = TrailLive(
            ClimbLive.On(
                climb = climb,
                remainingM = 1_300.0,
                remainingGainM = 79.0,
                gradePct = 6.8,
                elapsedMs = 263_000,
                deltaToBestMs = -12_000,
                ranked = true,
                bestMs = 612_000,
            ),
            null,
        )
        shot("cockpit-indoor-tab-s11-quer-erg-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(mode = IndoorMode.ERG, erg = erg, ghost = null),
                    ergTitle = "Schwelle 3 x 8 Min",
                    trail = trail,
                ),
            )
        }
        compose.onNodeWithText("Ziel 220 W").assertExists()
        compose.onNodeWithText("Am Anstieg").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerOhneWelt() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-tab-s11-quer-no-world") {
            RideScreen(
                rideUi(
                    session = session(ghost = null),
                    render = IndoorRenderUi.NoWorld,
                    demoPowerW = 180,
                    demoCadenceRpm = 85,
                ),
            )
        }
        compose.onNodeWithText("Höhenprofil").assertExists()
        compose.onNodeWithText("Leistung (Demo): 180 W").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun tabletHochkantSim() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-tab-s11-hochkant-sim") {
            RideScreen(rideUi())
        }
        compose.onAllNodesWithContentDescription("Pause").onFirst().assertExists()
        compose.onNodeWithContentDescription("Gang 14 / 24").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun tabletHochkantErgEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val erg = ErgState(stepIndex = 1, targetW = 220, stepRemainingS = 180, done = false)
        val trail = TrailLive(ClimbLive.Ahead(climb, toStartM = 312.0, bestMs = 612_000), TrailMarker.Kilometer(6))
        shot("cockpit-indoor-tab-s11-hochkant-erg-en-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(mode = IndoorMode.ERG, erg = erg, ghost = null),
                    ergTitle = "Threshold 3 x 8 min",
                    trail = trail,
                ),
            )
        }
        compose.onNodeWithText("Target 220 W").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun s25Sim() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-412dp-sim") {
            RideScreen(rideUi())
        }
        compose.onAllNodesWithContentDescription("Pause").onFirst().assertExists()
        compose.onNodeWithContentDescription("Leistung: 236 W").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.S25_EN)
    fun s25ErgEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val erg = ErgState(stepIndex = 1, targetW = 220, stepRemainingS = 180, done = false)
        shot("cockpit-indoor-412dp-erg-en") {
            RideScreen(
                rideUi(
                    session = session(mode = IndoorMode.ERG, erg = erg, ghost = null),
                    ergTitle = "Threshold 3 x 8 min",
                ),
            )
        }
        compose.onNodeWithText("Target 220 W").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun schmal360SimGrosseSchrift() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-360dp-sim-130", fontScale = 1.3f) {
            RideScreen(rideUi())
        }
        compose.onAllNodesWithContentDescription("Pause").onFirst().assertExists()
        compose.onNodeWithContentDescription("Leistung: 236 W").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun schmal360ErgDoneGrosseSchrift() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        val erg = ErgState(stepIndex = 4, targetW = 220, stepRemainingS = 0, done = true)
        shot("cockpit-indoor-360dp-erg-done-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(mode = IndoorMode.ERG, erg = erg, ghost = null),
                    ergTitle = "Schwelle 3 x 8 Min",
                ),
            )
        }
        compose.onNodeWithText("Ziel 220 W").assertExists()
        compose.onNodeWithText("Einheit geschafft, fahre locker weiter").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun schmal360EnglischGrosseSchrift() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val erg = ErgState(stepIndex = 4, targetW = 220, stepRemainingS = 0, done = true)
        val trail = TrailLive(
            ClimbLive.On(
                climb = climb,
                remainingM = 1_300.0,
                remainingGainM = 79.0,
                gradePct = 6.8,
                elapsedMs = 263_000,
                deltaToBestMs = 8_000,
                ranked = true,
                bestMs = 612_000,
            ),
            null,
        )
        shot("cockpit-indoor-360dp-erg-en-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(mode = IndoorMode.ERG, erg = erg, ghost = null),
                    ergTitle = "Threshold 3 x 8 min",
                    trail = trail,
                ),
            )
        }
        compose.onNodeWithText("Climbing").assertExists()
        compose.onNodeWithText("Target 220 W").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun schmal360PauseUndStart() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-360dp-pause-start-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(phase = IndoorPhase.PAUSED, ghost = null),
                    start = IndoorStartUi(
                        selectedM = 7_400.0,
                        maxM = 11_700.0,
                        resumeM = 7_400.0,
                        held = false,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Fahrt beenden").assertExists()
        compose.onNodeWithText("Weiter").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun schmal360AnstiegAnkuendigung() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        val trail = TrailLive(ClimbLive.Ahead(climb, toStartM = 312.0, bestMs = 612_000), TrailMarker.Kilometer(6))
        shot("cockpit-indoor-360dp-climb-ahead-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(ghost = null),
                    trail = trail,
                ),
            )
        }
        compose.onNodeWithText("Anstieg in 300 m: 2,4 km, 6,1 %").assertExists()
        compose.onNodeWithText("·  Kat. 3").assertExists()
        compose.onNodeWithText("·  Bestzeit 10:12").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun schmal360StartUndDemo() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-360dp-start-demo-130", fontScale = 1.3f) {
            RideScreen(
                rideUi(
                    session = session(phase = IndoorPhase.PAUSED, ghost = null),
                    demoPowerW = 180,
                    demoCadenceRpm = 85,
                    start = IndoorStartUi(
                        selectedM = 7_400.0,
                        maxM = 11_700.0,
                        resumeM = 7_400.0,
                        held = false,
                    ),
                ),
            )
        }
        compose.onNodeWithText("Leistung (Demo): 180 W").assertExists()
        compose.onNodeWithText("Trittfrequenz (Demo): 85 U/min").assertExists()
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w891dp-h411dp-xxhdpi")
    fun telefonQuerHinweisUndPause() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-phone-quer-notice-pause") {
            RideScreen(
                rideUi(
                    session = session(phase = IndoorPhase.PAUSED, ghost = null),
                    notice = IndoorNotice.TRAINER_LOST,
                ),
            )
        }
        compose.onNodeWithText("Die Verbindung zum Trainer ist abgebrochen. Die Fahrt ist pausiert und geht weiter, sobald der Trainer wieder da ist.").assertExists()
        compose.onNodeWithText("Pausiert").assertExists()
        compose.onNodeWithText("Weiter").assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun tabletQuerTagesende() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        shot("cockpit-indoor-tab-s11-quer-day-end") {
            RideScreen(
                rideUi(
                    session = session(ghost = null),
                    dayEnd = IndoorDayEndUi(kind = DayRideEnd.WORKOUT_DONE, remainingMin = 0),
                ),
            )
        }
        compose.onNodeWithText("Einheit geschafft").assertExists()
        compose.onNodeWithText("Ausfahren").assertExists()
    }
}
