package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.TurnRichtung
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder des Fahrmodus (Datenseite) und der Kartenseite des Fahrmodus
 * (Navigations-HUD oben, Kompaktleiste unten) — fuer den Feinschliff der
 * Knopfformen und der abgesetzten Fuehrungsflaeche ohne Geraet. Laeuft wie
 * `RouteSurfaceScreenshotTest` nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
class RideModeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyOnRequest() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private val navigation = RideModeNavigation(
        label = "Geplante Route",
        remainingKm = 14.0,
        offRoute = false,
        naechsteKurve = TurnRichtung.LINKS,
        naechsteKurveM = 610.0,
    )

    private fun fahrmodus(paused: Boolean, nav: RideModeNavigation?, name: String) {
        compose.setContent {
            TrailscapeTheme {
                RideModeScreen(
                    speedKmh = if (paused) 0.0 else 23.4,
                    distanceKm = 12.3,
                    elapsedS = 2710,
                    ascentM = 184.0,
                    paused = paused,
                    navigation = nav,
                    onTogglePause = {},
                    onStop = {},
                    onClose = {},
                    onShowMap = {},
                    autoPaused = paused,
                )
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun fahrmodusNavigation() = fahrmodus(paused = true, nav = navigation, name = "70-fahrmodus-navigation")

    @Test
    fun fahrmodusAbseits() =
        fahrmodus(paused = false, nav = navigation.copy(offRoute = true), name = "71-fahrmodus-abseits")

    @Test
    fun fahrmodusOhneNavigation() = fahrmodus(paused = false, nav = null, name = "72-fahrmodus-ohne-navigation")

    @Test
    fun kartenseite() {
        compose.setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        NavigationHud(
                            label = "Geplante Route",
                            remainingKm = 14.0,
                            doneKm = 0.0,
                            offRoute = false,
                            naechsteKurve = TurnRichtung.LINKS,
                            kurveAbstandM = 610.0,
                            tempoKmh = 18.0,
                            sprachansagenAn = true,
                            onToggleSprachansagen = {},
                            onStop = {},
                        )
                        RideCompactBar(
                            speedKmh = 0.6,
                            distanceKm = 0.0,
                            ascentM = 0.0,
                            elapsedS = 7,
                            paused = false,
                            autoPaused = false,
                            onTogglePause = {},
                            onStop = {},
                            onShowData = {},
                        )
                        RideCompactBar(
                            speedKmh = 0.0,
                            distanceKm = 0.0,
                            ascentM = 0.0,
                            elapsedS = 7,
                            paused = true,
                            autoPaused = true,
                            onTogglePause = {},
                            onStop = {},
                            onShowData = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/73-kartenseite-fahrmodus.png")
    }
}
