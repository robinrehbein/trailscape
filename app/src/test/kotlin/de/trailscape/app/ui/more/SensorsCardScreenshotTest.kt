package de.trailscape.app.ui.more

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
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import de.trailscape.app.sensors.BleFund
import de.trailscape.app.sensors.BleKanaele
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.BleKanal
import de.trailscape.core.BleSensorTyp
import de.trailscape.core.BleVerbindung
import de.trailscape.core.GemerkterSensor
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Die Seite Mehr → Sensoren in drei Zustaenden, mit Beispieldaten statt
 * Bluetooth (das Robolectric nicht hat): nichts gekoppelt mit
 * Berechtigungshinweis, zwei gekoppelte Sensoren waehrend einer laufenden
 * Suche, Bluetooth aus.
 *
 * Qualifier `de`: Robolectric laeuft sonst in en-US und naehme, sobald es
 * ein `values-en` gibt, die englischen Texte.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "de-w411dp-h891dp-xxhdpi", application = ScreenshotApplication::class)
class SensorsCardScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun nurMitScreenshots() {
        assumeTrue(System.getProperty("trailscape.screenshots") == "true")
    }

    private val jetzt = 1_000_000L

    private val basis = SensorsUiState(
        bleSupported = true,
        bluetoothOn = true,
        permissionGranted = true,
        permissionPermanentlyDenied = false,
        legacyPermission = false,
        gekoppelt = emptyList(),
        status = emptyMap(),
        kanaele = BleKanaele.AUS,
        jetzt = jetzt,
        scanning = false,
        scanFehler = null,
        funde = emptyList(),
    )

    private fun show(state: SensorsUiState, name: String) {
        compose.setContent {
            TrailscapeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        SettingsSection {
                            SensorsPageContent(
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

    @Test
    fun nichtsGekoppeltOhneBerechtigung() {
        show(basis.copy(permissionGranted = false), "80-sensoren-leer-berechtigung")
        compose.onNodeWithText("Erlauben").assertExists()
    }

    @Test
    fun gekoppeltUndSuche() {
        show(
            basis.copy(
                gekoppelt = listOf(
                    GemerkterSensor(BleSensorTyp.PULS, "AA:BB:CC:DD:EE:01", "Polar H10 7A3B2C1D"),
                    GemerkterSensor(BleSensorTyp.LEISTUNG, "AA:BB:CC:DD:EE:02", "ASSIOMA12345L"),
                ),
                status = mapOf(
                    BleSensorTyp.PULS to BleVerbindung.VERBUNDEN,
                    BleSensorTyp.LEISTUNG to BleVerbindung.WARTET,
                ),
                kanaele = BleKanaele(
                    puls = BleKanal(true, 142, jetzt - 800),
                    leistung = BleKanal(true, null, null),
                    trittfrequenz = BleKanal.AUS,
                    naechsterVersuchMs = mapOf(BleSensorTyp.LEISTUNG to jetzt + 10_000),
                ),
                scanning = true,
                funde = listOf(
                    BleFund("AA:BB:CC:DD:EE:03", "Wahoo CADENCE 1A2B", setOf(BleSensorTyp.TRITTFREQUENZ), -52),
                    BleFund("AA:BB:CC:DD:EE:02", "ASSIOMA12345L", setOf(BleSensorTyp.LEISTUNG, BleSensorTyp.TRITTFREQUENZ), -60),
                ),
            ),
            "81-sensoren-gekoppelt-suche",
        )
        compose.onNodeWithText("Verbunden · 142 bpm").assertExists()
        compose.onNodeWithText("Getrennt · neuer Versuch in 10 s").assertExists()
        compose.onNodeWithText("Suche läuft …").assertExists()
        // Der schon gekoppelte Leistungsmesser sagt „Gekoppelt“ statt eines toten „Koppeln“.
        compose.onNodeWithText("Gekoppelt").assertExists()
    }

    @Test
    fun bluetoothAus() {
        show(
            basis.copy(
                bluetoothOn = false,
                gekoppelt = listOf(GemerkterSensor(BleSensorTyp.TRITTFREQUENZ, "AA:BB:CC:DD:EE:03", null)),
                status = mapOf(BleSensorTyp.TRITTFREQUENZ to BleVerbindung.BLUETOOTH_AUS),
            ),
            "82-sensoren-bluetooth-aus",
        )
        compose.onNodeWithText("Bluetooth einschalten").assertExists()
    }
}
