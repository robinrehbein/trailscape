package de.trailscape.app.sensors.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.more.SettingsSection
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.indoor.IndoorDeviceRole
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Der Abschnitt „Indoor-Training" (Mehr → Sensoren) in mehreren Zustaenden,
 * mit Beispieldaten statt Bluetooth (das Robolectric nicht hat).
 *
 * Sprache fest Deutsch ueber [TestLocales]; der englische Fall schaltet nur
 * diese Methode mit „+en-rGB" um (docs/i18n.md, Abschnitt E). Laeuft nur mit
 * `-Pscreenshots`; die PNGs landen in `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorDevicesScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private val basis = IndoorDevicesUiState(
        bleSupported = true,
        bluetoothOn = true,
        permissionGranted = true,
        permissionPermanentlyDenied = false,
        legacyPermission = false,
        systemHinweise = false,
        gemerkt = emptyMap(),
        scanning = false,
        scanFehler = null,
        funde = emptyList(),
    )

    private val trainer = IndoorDeviceRef(IndoorDeviceRole.TRAINER, "AA:BB:CC:DD:EE:01", "KICKR CORE 5A1F")
    private val shifter = IndoorDeviceRef(IndoorDeviceRole.SHIFTER, "AA:BB:CC:DD:EE:04", "Zwift Click")

    private val sucheMitFunden = basis.copy(
        gemerkt = mapOf(IndoorDeviceRole.TRAINER to trainer),
        scanning = true,
        funde = listOf(
            IndoorFund("AA:BB:CC:DD:EE:01", "KICKR CORE 5A1F", setOf(IndoorDeviceRole.TRAINER), -48),
            IndoorFund("AA:BB:CC:DD:EE:02", "Wahoo KICKR 0C2D", setOf(IndoorDeviceRole.TRAINER), -63),
            IndoorFund("AA:BB:CC:DD:EE:04", "Zwift Click", setOf(IndoorDeviceRole.SHIFTER), -55),
            IndoorFund("AA:BB:CC:DD:EE:05", null, setOf(IndoorDeviceRole.SHIFTER), -71),
        ),
    )

    private fun show(state: IndoorDevicesUiState, name: String) {
        compose.setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        SettingsSection {
                            IndoorDevicesContent(
                                state = state,
                                onScan = {},
                                onStopScan = {},
                                onPair = { _, _ -> },
                                onForget = {},
                                onGrant = {},
                                onEnableBluetooth = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun text(id: Int, vararg args: Any): String =
        compose.activity.getString(id, *args)

    @Test
    fun nichtsGekoppeltOhneBerechtigung() {
        show(basis.copy(permissionGranted = false, systemHinweise = true), "indoor-ble-leer-berechtigung")
        compose.onNodeWithText("Erlauben").assertExists()
    }

    @Test
    fun trainerGekoppeltUndSuche() {
        show(sucheMitFunden, "indoor-ble-trainer-suche")
        compose.onNodeWithText("Suche läuft …").assertExists()
        // Der schon gekoppelte Trainer sagt „Gekoppelt“ statt eines toten „Koppeln“.
        compose.onNodeWithText("Gekoppelt").assertExists()
        compose.onNodeWithText("Unbenanntes Gerät").assertExists()
    }

    @Test
    fun beideGekoppeltBluetoothAus() {
        show(
            basis.copy(
                bluetoothOn = false,
                systemHinweise = true,
                gemerkt = mapOf(IndoorDeviceRole.TRAINER to trainer, IndoorDeviceRole.SHIFTER to shifter),
            ),
            "indoor-ble-beide-bluetooth-aus",
        )
        compose.onNodeWithText("Einschalten").assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun suchlaufEnglisch() {
        show(sucheMitFunden, "indoor-ble-trainer-suche-en")
        compose.onNodeWithText(text(R.string.indoor_ble_scan_running_label)).assertExists()
        compose.onNodeWithText(text(R.string.indoor_ble_section_title)).assertExists()
        compose.onNodeWithText(text(R.string.indoor_ble_paired_label)).assertExists()
    }

    @Test
    fun vergessenDialog() {
        show(basis.copy(gemerkt = mapOf(IndoorDeviceRole.TRAINER to trainer)), "indoor-ble-trainer-gekoppelt")
        compose.onNodeWithText("Vergessen").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.indoor_ble_forget_confirm_title, "KICKR CORE 5A1F")).assertExists()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/indoor-ble-vergessen-dialog.png")
    }
}
