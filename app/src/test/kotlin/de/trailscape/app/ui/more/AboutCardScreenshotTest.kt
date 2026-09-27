package de.trailscape.app.ui.more

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sichtpruefung von „Mehr → Über" in beiden Verteilungswegen: als APK von
 * GitHub mit „Nach Updates suchen" und Auto-Check-Schalter, als
 * Play-Installation nur mit dem Hinweis „Updates kommen über Google Play.".
 *
 * Der Play-Fall wird ueber den Parameter `updatesViaPlay` erzwungen —
 * Robolectric meldet keinen Installer, im Test gilt also sonst immer der
 * Sideload-Fall.
 *
 * Mit Sprach-Qualifier `de`: Robolectric laeuft sonst in `en-US`, und der
 * Hinweis kommt als Ressource (mit englischer Fassung in `values-en/`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
class AboutCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    fun ueberAlsApk() {
        show(updatesViaPlay = false)
        compose.onNodeWithText("Nach Updates suchen").assertExists()
        compose.onNodeWithText("Täglich still nach Updates suchen").assertExists()
        compose.onNodeWithText("Updates kommen über Google Play.").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/74-ueber-apk.png")
    }

    @Test
    fun ueberAusPlay() {
        show(updatesViaPlay = true)
        compose.onNodeWithText("Updates kommen über Google Play.").assertExists()
        compose.onNodeWithText("Nach Updates suchen").assertDoesNotExist()
        compose.onNodeWithText("Täglich still nach Updates suchen").assertDoesNotExist()
        compose.onNodeWithText("Quellcode auf GitHub").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/75-ueber-play.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun ueberAusPlayDunkel() {
        show(updatesViaPlay = true, dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/76-ueber-play-dunkel.png")
    }

    private fun show(updatesViaPlay: Boolean, dark: Boolean = false) {
        val viewModel = AppViewModel()
        compose.setContent {
            TrailscapeTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        AboutCardContent(viewModel, updatesViaPlay = updatesViaPlay)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
