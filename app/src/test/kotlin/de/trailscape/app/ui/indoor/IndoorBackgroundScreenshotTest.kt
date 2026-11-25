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
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
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
 * Einstellungen des Indoor-Trainings mit dem Schalter „Im Hintergrund
 * weiterfahren" (docs/indoor/hintergrund.md). Laeuft wie die anderen
 * Screenshot-Tests nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi", application = ScreenshotApplication::class)
class IndoorBackgroundScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private val pair = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE 7B2F", shifterName = "Zwift Click")

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

    @Composable
    private fun Settings(backgroundRide: Boolean) {
        IndoorSettingsScreen(
            ui = pair,
            catalog = IndoorCatalogUi(emptyList()),
            onChainringChange = {}, onGearTableChange = {}, onTimeOfDayChange = {}, onOtherRidersChange = {},
            onOpenWorlds = {}, onRefreshCatalog = {}, onOpenDiagnostics = {}, onBack = {},
            backgroundRide = backgroundRide,
        )
    }

    private fun text(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun einstellungenHintergrundAn() {
        shot("idee05-01-einstellungen-hintergrund-an") { Settings(backgroundRide = true) }
        compose.onNodeWithText(text(R.string.idee05_settings_background_title)).assertExists()
    }

    @Test
    fun einstellungenHintergrundAus() {
        shot("idee05-02-einstellungen-hintergrund-aus") { Settings(backgroundRide = false) }
        compose.onNodeWithText(text(R.string.idee05_settings_background_title)).assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun einstellungenHintergrundEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("idee05-03-einstellungen-hintergrund-en") { Settings(backgroundRide = true) }
        compose.onNodeWithText("Keep riding in the background").assertExists()
    }
}
