package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.TurnRichtung
import de.trailscape.core.i18n.AppLanguage
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
 * Bilder des Fahrmodus (Datenseite) und der Kartenseite des Fahrmodus
 * (Navigations-HUD oben, Kompaktleiste unten) — fuer den Feinschliff der
 * Knopfformen und der abgesetzten Fuehrungsflaeche ohne Geraet. Laeuft wie
 * `RouteSurfaceScreenshotTest` nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
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
                ProvideTestLanguage {
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
    fun kartenseite() = kartenseite(label = "Geplante Route", name = "73-kartenseite-fahrmodus")

    /**
     * Fahrmodus auf Englisch — Datenseite mit Navigation (die laengsten
     * Beschriftungen: „Elevation gain ↑", „bpm · heart rate").
     */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun fahrmodusEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        fahrmodus(
            paused = true,
            nav = navigation.copy(label = context.getString(R.string.map_screen_planned_route_label)),
            name = "70-fahrmodus-navigation-en",
        )
        assertEquals("km ridden", context.getString(R.string.map_ride_distance_label))
        compose.onNodeWithText(context.getString(R.string.map_ride_distance_label), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_status_auto_paused), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("14.0 km left · Planned route", useUnmergedTree = true).assertExists()
    }

    /** HUD und Kompaktleiste auf Englisch — Restzeile mit Punkt und „approx.". */
    @Test
    @Config(qualifiers = "+en-rGB")
    fun kartenseiteEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        kartenseite(
            label = context.getString(R.string.map_screen_planned_route_label),
            name = "73-kartenseite-fahrmodus-en",
        )
        compose.onNodeWithText("14.0 km · approx. 47 min", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.map_compact_data_action), useUnmergedTree = true)[0].assertExists()
        compose.onNodeWithText(context.getString(R.string.map_compact_speed_auto_label), useUnmergedTree = true).assertExists()
    }

    private fun kartenseite(label: String, name: String) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            NavigationHud(
                                label = label,
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
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
