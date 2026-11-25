package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.rides.RideAgainSheet
import de.trailscape.app.ui.rides.rideAgainOptions
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.GhostSection
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder zu „Draußen gefahren → drinnen nachfahren" (docs/indoor/app.md,
 * „Drinnen nachfahren"): Einstieg im Blatt „Nochmal fahren" des Tourdetails, Mitfahrer-Chip mit
 * Leistung der Außenfahrt, Vergleich in der Auswertung. Nur mit `-Pscreenshots`;
 * Ablage unter `docs/indoor/bilder/nachfahren/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorOutdoorScreenshotTest {

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
        captureScreenRoboImage("build/outputs/roborazzi/nachfahren-$name.png")
    }

    private val section = GhostSection(
        startM = 0.0, endM = 18_400.0, riderS = 2_948.0, ghostS = 2_990.0,
        riderAvgPowerW = 214.0, ghostAvgPowerW = 197.0,
    )

    private val summary = IndoorSummaryUi(
        routeName = "Hausrunde Ammersee", mode = IndoorMode.SIM, distanceKm = 18.4, movingS = 2_948,
        ascentM = 212.0, avgPowerW = 214, normalizedPowerW = 228, avgCadenceRpm = 88, avgHrBpm = 146,
        workKj = 631, avgSpeedKmh = 22.5, laps = 1, ghostLabel = "Außenfahrt vom 09.10.", ghostGapS = -42.0,
        tooShort = false, ghostOutdoor = true, ghostSection = section,
    )

    /**
     * Der Einstieg liegt seit dem Umbau nach der Seitenkarte im Blatt
     * „Nochmal fahren" des Tourdetails (`ui/rides/RideAgain.kt`), nicht mehr
     * als eigener Knopf im Inhalt.
     */
    @Composable
    private fun Detail(planned: Boolean) {
        RideAgainSheet(
            options = rideAgainOptions(indoor = false, pointCount = 2),
            planned = planned,
            onPick = {},
            onDismiss = {},
        )
    }

    @Composable
    private fun Chips(label: String) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF7FA7C9)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudGhostChip(GhostState(4_380.0, 7.4, 120.0, 16.0, powerW = 238.0), label, showPower = true)
            HudGhostChip(GhostState(4_100.0, 6.9, -160.0, -23.0, powerW = 185.0), label, showPower = true)
            HudGhostChip(GhostState(4_100.0, 0.0, -40.0, null, powerW = null), label, showPower = true)
        }
    }

    @Test
    fun detailGefahren() {
        shot("01-detail-gefahren") { Detail(planned = false) }
        compose.onNodeWithText("Indoor fahren").assertExists()
        compose.onNodeWithText("Drinnen auf dem Trainer, deine Außenfahrt fährt als Mitfahrer mit.").assertExists()
    }

    @Test
    fun detailGeplant() {
        shot("02-detail-geplant") { Detail(planned = true) }
        compose.onNodeWithText("Diese Planung drinnen auf dem Trainer fahren.").assertExists()
    }

    @Test
    fun hudMitfahrer() {
        shot("03-hud-mitfahrer") { Chips("Außenfahrt vom 09.10.") }
        compose.onNodeWithText("Außenfahrt vom 09.10. · 0:16 vor dir · 238 W").assertExists()
    }

    @Test
    fun auswertungVor() {
        shot("04-auswertung-vor") { IndoorSummaryScreen(summary, {}, {}, {}) }
        compose.onNodeWithText("Du warst 0:42 vor deiner Außenfahrt.").assertExists()
    }

    @Test
    fun auswertungHinterOhneLeistung() {
        shot("05-auswertung-hinter-ohne-leistung") {
            IndoorSummaryScreen(
                summary.copy(
                    ghostGapS = 75.0,
                    ghostSection = section.copy(startM = 7_400.0, endM = 18_400.0, riderS = 1_820.0, ghostS = 1_745.0, ghostAvgPowerW = null),
                ),
                {}, {}, {},
            )
        }
        compose.onNodeWithText("Du warst 1:15 hinter deiner Außenfahrt.").assertExists()
        compose.onNodeWithText("Die Außenfahrt hat keine Leistungsdaten.").assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun auswertungEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("06-auswertung-en") { IndoorSummaryScreen(summary.copy(ghostLabel = "Outdoor ride on 9 Oct"), {}, {}, {}) }
        compose.onNodeWithText("You were 0:42 ahead of your outdoor ride.").assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun detailEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("07-detail-en") { Detail(planned = false) }
        compose.onNodeWithText("Ride indoors").assertExists()
    }
}
