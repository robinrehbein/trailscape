package de.trailscape.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.rides.PlanFlipConfirmDialog
import de.trailscape.app.ui.theme.TrailscapeTheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder und Verhalten der Dialoge zu „GPX ohne Zeitstempel als geplante
 * Route öffnen": der Erkennungsdialog nach Teilen/Öffnen (als Planung bzw. als
 * Tour erkannt, einmal umdrehbar) und die Rückfrage beim Umwandeln in der
 * Tourdetailansicht. Dialoge sind eigene Fenster, daher `captureScreenRoboImage`.
 * Läuft nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class ImportRecognitionScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Test
    fun alsPlanungErkannt() {
        var flipped = 0
        compose.setContent {
            TrailscapeTheme {
                ImportRecognitionDialog(ImportRecognition("r1", "Albtrauf", planned = true), onKeep = {}, onFlip = { flipped++ })
            }
        }
        compose.onNodeWithText(text(R.string.gpxplan_recognized_planned_title)).assertExists()
        compose.onNodeWithText(text(R.string.gpxplan_recognized_planned_body, "Albtrauf")).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/83-gpx-als-planung-erkannt.png")
        compose.onNodeWithText(text(R.string.gpxplan_flip_to_ridden_action)).performClick()
        assertEquals(1, flipped)
    }

    @Test
    fun alsTourErkannt() {
        compose.setContent {
            TrailscapeTheme {
                ImportRecognitionDialog(ImportRecognition("r2", "Feierabend", planned = false), onKeep = {}, onFlip = {})
            }
        }
        compose.onNodeWithText(text(R.string.gpxplan_recognized_ridden_title)).assertExists()
        compose.onNodeWithText(text(R.string.gpxplan_flip_to_planned_action)).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/84-gpx-als-tour-erkannt.png")
    }

    @Test
    fun umwandelnRueckfrage() {
        compose.setContent {
            TrailscapeTheme {
                PlanFlipConfirmDialog(toPlanned = true, rideName = "Albtrauf", onDismiss = {}, onConfirm = {})
            }
        }
        compose.onNodeWithText(text(R.string.gpxplan_to_planned_confirm_title)).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/85-in-planung-umwandeln.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = TestLocales.S25_EN, application = ScreenshotApplication::class)
    fun alsPlanungErkanntEnglisch() {
        compose.setContent {
            TrailscapeTheme {
                ImportRecognitionDialog(ImportRecognition("r1", "Albtrauf", planned = true), onKeep = {}, onFlip = {})
            }
        }
        compose.onNodeWithText("Opened as a planned route").assertExists()
        compose.onNodeWithText(text(R.string.gpxplan_flip_to_ridden_action)).assertExists()
        captureScreenRoboImage("build/outputs/roborazzi/83-gpx-als-planung-erkannt-en.png")
    }
}
