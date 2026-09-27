package de.trailscape.app.ui.more

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.strava.StravaAuthMessage
import de.trailscape.app.strava.StravaConnection
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.rides.StravaRideActionContent
import de.trailscape.app.ui.rides.StravaRideActionState
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.StravaError
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sichtpruefung der Strava-Teile: die Einstellungsseite (nicht verbunden /
 * verbunden mit Auto-Upload) und die Strava-Zeile der Tour-Detailansicht in
 * ihren Zustaenden.
 *
 * Eigene Klasse mit den zustandslosen Bausteinen: In der echten App sind
 * beide nur in Builds mit Strava-Zugangsdaten sichtbar, die Test- und
 * CI-Builds nicht haben — die bestehenden Screenshots bleiben dadurch
 * unveraendert. Locale `de` vorsorglich: Kommt mit der Uebersetzung eine
 * englische Fassung hinzu, bleiben diese Bilder deutsch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class StravaCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    @Test
    fun einstellungenNichtVerbunden() {
        show(dark = false) {
            SettingsSection {
                StravaCardBody(
                    connection = StravaConnection.Disconnected,
                    autoUpload = false,
                    message = StravaAuthMessage.MISSING_SCOPE,
                    onConnect = {},
                    onDisconnect = {},
                    onAutoUploadChange = {},
                )
            }
        }
        compose.onNodeWithText("Mit Strava verbinden").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/71-strava-nicht-verbunden.png")
    }

    @Test
    fun einstellungenVerbunden() {
        show(dark = false) {
            SettingsSection {
                StravaCardBody(
                    connection = StravaConnection.Connected("Robin"),
                    autoUpload = true,
                    message = null,
                    onConnect = {},
                    onDisconnect = {},
                    onAutoUploadChange = {},
                )
            }
        }
        compose.onNodeWithText("Verbunden als „Robin“.").assertExists()
        compose.onNodeWithText("Neue Touren automatisch hochladen").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/72-strava-verbunden.png")
    }

    @Test
    fun einstellungenZugangBeendet() {
        show(dark = false) {
            SettingsSection {
                StravaCardBody(
                    connection = StravaConnection.Disconnected,
                    autoUpload = false,
                    message = StravaAuthMessage.REVOKED,
                    onConnect = {},
                    onDisconnect = {},
                    onAutoUploadChange = {},
                )
            }
            // Die Tour nach abgewiesenem Zugang: Hinweis ohne Aktion.
            StravaRideActionContent(
                state = StravaRideActionState.Failed(StravaError.UNAUTHORIZED),
                onUpload = {},
                onOpen = {},
                onRetry = {},
                canUpload = false,
            )
        }
        compose.onNodeWithText("Strava hat den Zugang beendet.", substring = true).assertExists()
        compose.onNodeWithText("Einstellungen → Strava", substring = true).assertExists()
        compose.onNodeWithText("Erneut versuchen").assertDoesNotExist()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/75-strava-zugang-beendet.png")
    }

    @Test
    fun tourZeilen() {
        showRideStates(dark = false)
        compose.onNodeWithText("Zu Strava hochladen").assertExists()
        compose.onNodeWithText("Auf Strava hochgeladen.").assertExists()
        compose.onNodeWithText("Hochladen zu Strava fehlgeschlagen").assertExists()
        compose.onNodeWithText("Das Hochladen ist nicht fertig geworden.").assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/73-strava-tour.png")
    }

    @Test
    @Config(qualifiers = "+night")
    fun tourZeilenDunkel() {
        showRideStates(dark = true)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/74-strava-tour-dunkel.png")
    }

    private fun showRideStates(dark: Boolean) {
        show(dark) {
            listOf(
                StravaRideActionState.NotUploaded,
                StravaRideActionState.Uploading,
                StravaRideActionState.Stale,
                StravaRideActionState.Uploaded("https://www.strava.com/activities/1"),
                StravaRideActionState.Duplicate(null),
                StravaRideActionState.Failed(StravaError.RATE_LIMITED),
            ).forEach { state ->
                StravaRideActionContent(state = state, onUpload = {}, onOpen = {}, onRetry = {})
            }
        }
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            TrailscapeTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) { content() }
                }
            }
        }
        compose.waitForIdle()
    }
}
