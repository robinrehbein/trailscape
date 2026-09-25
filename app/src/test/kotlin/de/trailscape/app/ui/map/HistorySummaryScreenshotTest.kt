package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
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
 * Bild des Verlaufsblatts der Karte mit der Kennzahl „Größte Fläche"
 * (Max-Cluster). Direkt gerendert wie in `RouteSurfaceScreenshotTest.kt` —
 * ueber die ganze App braeuchte man echte Touren mit flaechiger Abdeckung.
 * Laeuft nur mit `-Pscreenshots`; das PNG landet in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
class HistorySummaryScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    fun verlaufGroessteFlaeche() {
        compose.setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        HistorySummarySheet(
                            rideCount = 42,
                            totalKm = 2345.6,
                            tileCount = 812,
                            squareSize = 7,
                            clusterSize = 37,
                            onClose = {},
                            bottomInset = 0.dp,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Größte Fläche: 37 Kacheln").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/63-verlauf-groesste-flaeche.png")
    }
}
