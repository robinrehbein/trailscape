package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.FeelPhase
import de.trailscape.core.indoor.FeelRejection
import de.trailscape.core.indoor.FeelResult
import de.trailscape.core.indoor.FeelSuggestion
import de.trailscape.core.indoor.RideFeel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bilder der Fahrgefühl-Oberflächen (idee-06): Regler, Abgleich (Messen, Vorschlag, Ablehnung), Fahrprotokolle. Nur mit `-Pscreenshots`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorFeelScreenshotTest {

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
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val suggestion = FeelSuggestion(
        currentResistance = 1.0, suggestedResistance = 0.85, clamped = false, powerRatio = 0.93,
        medianPowerW = 168.0, medianCadenceRpm = 86.0, expectedPowerW = 156.0, uncertaintyPct = 6.0, samples = 148,
    )

    private fun cal(phase: FeelPhase, phaseS: Int, result: FeelResult? = null, accepted: Boolean = false) = IndoorCalibrationUi(
        phase = phase, phaseS = phaseS, remainingS = 150 - phaseS, powerW = 168, cadenceRpm = 86, gearIndex = 14,
        result = result, accepted = accepted,
    )

    @Composable
    private fun Calibration(ui: IndoorCalibrationUi) = IndoorCalibrationScreen(
        ui = ui, onGearLess = {}, onGearMore = {}, onFinishNow = {}, onAccept = {}, onLeave = {},
    )

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun regler() = shot("feel-regler") {
        IndoorPage(title = "Einstellungen", onBack = {}) {
            FeelCard(RideFeel(0.9, 1.4), {}, {}, {})
        }
    }

    @Test
    fun reglerStandard() = shot("feel-regler-standard") {
        IndoorPage(title = "Einstellungen", onBack = {}) { FeelCard(RideFeel.DEFAULT, {}, {}, {}) }
    }

    @Test
    fun abgleichMessen() = shot("feel-abgleich-messen") { Calibration(cal(FeelPhase.MEASURING, 70)) }

    @Test
    fun abgleichEinlaufen() = shot("feel-abgleich-einlaufen") { Calibration(cal(FeelPhase.WARMUP, 12)) }

    @Test
    fun abgleichVorschlag() = shot("feel-abgleich-vorschlag") {
        Calibration(cal(FeelPhase.DONE, 150, FeelResult.Suggest(suggestion)))
    }

    @Test
    fun abgleichAbgelehnt() = shot("feel-abgleich-abgelehnt") {
        Calibration(cal(FeelPhase.DONE, 150, FeelResult.Reject(FeelRejection.UNSTEADY)))
    }

    @Test
    fun protokolleListe() = shot("feel-protokolle-liste") {
        IndoorProtocolScreen(
            rows = listOf(ProtocolRowUi("a", "25.11.2026, 18:05", 24), ProtocolRowUi("b", "23.11.2026, 07:40", 61)),
            openedId = null, openedText = null,
            onOpen = {}, onShare = {}, onDelete = {}, onDeleteAll = {}, onCloseOpened = {}, onBack = {},
        )
    }

    @Test
    fun protokollAnsicht() = shot("feel-protokoll-ansicht") {
        IndoorProtocolScreen(
            rows = emptyList(), openedId = "a",
            openedText = "# Trailscape Fahrprotokoll\n# Fahrgefuehl: trainerScale 1.00\nzeit_s;strecke_pct;gesetzt_pct;crr\n5;0.0;0.10;0.0080\n10;1.2;2.40;0.0120\n",
            onOpen = {}, onShare = {}, onDelete = {}, onDeleteAll = {}, onCloseOpened = {}, onBack = {},
        )
    }

    @Test
    fun protokolleLeer() = shot("feel-protokolle-leer") {
        IndoorProtocolScreen(
            rows = emptyList(), openedId = null, openedText = null,
            onOpen = {}, onShare = {}, onDelete = {}, onDeleteAll = {}, onCloseOpened = {}, onBack = {},
        )
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun abgleichEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("feel-abgleich-vorschlag-en") { Calibration(cal(FeelPhase.DONE, 150, FeelResult.Suggest(suggestion))) }
        compose.onNodeWithText("Accept").assertExists()
    }
}
