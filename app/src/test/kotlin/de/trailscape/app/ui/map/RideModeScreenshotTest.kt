package de.trailscape.app.ui.map

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.core.LiveKachel
import de.trailscape.core.LiveSensorAnzeige
import de.trailscape.core.PulsQuelle
import de.trailscape.core.TurnRichtung
import de.trailscape.core.ZielZeile
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

    private fun fahrmodus(
        paused: Boolean,
        nav: RideModeNavigation?,
        name: String,
        sensoren: LiveSensorAnzeige = LiveSensorAnzeige.LEER,
        zielZeile: ZielZeile? = null,
        fontScale: Float = 1.0f,
        useDialog: Boolean = true,
        mapContent: (@Composable (Modifier) -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            val layoutClass = windowLayoutClassFor(LocalConfiguration.current.screenWidthDp)
            CompositionLocalProvider(
                LocalWindowLayoutClass provides layoutClass,
                LocalDensity provides Density(density.density, fontScale = fontScale),
            ) {
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
                            sensoren = sensoren,
                            zielZeile = zielZeile,
                            useDialog = useDialog,
                            mapContent = mapContent,
                        )
                    }
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

    /** Pulsgurt 142, Leistung 215 W, Trittfrequenzsensor seit 12 s still. */
    private val dreiSensoren = LiveSensorAnzeige(
        puls = LiveKachel(142, null),
        pulsQuelle = PulsQuelle.GURT,
        leistung = LiveKachel(215, null),
        trittfrequenz = LiveKachel(null, 12),
    )

    @Test
    fun fahrmodusSensoren() = fahrmodus(
        paused = false,
        nav = navigation,
        name = "74-fahrmodus-sensoren",
        sensoren = dreiSensoren,
    )

    @Test
    fun fahrmodusNurPuls() = fahrmodus(
        paused = false,
        nav = null,
        name = "75-fahrmodus-nur-puls",
        sensoren = LiveSensorAnzeige(LiveKachel(131, null), PulsQuelle.UHR, null, null),
    )

    @Test
    fun kartenseiteSensoren() {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            RideCompactBar(
                                speedKmh = 27.4,
                                distanceKm = 112.8,
                                ascentM = 1284.0,
                                elapsedS = 15_310,
                                paused = false,
                                autoPaused = false,
                                onTogglePause = {},
                                onStop = {},
                                onShowData = {},
                                sensoren = dreiSensoren,
                            )
                            RideCompactBar(
                                speedKmh = 21.0,
                                distanceKm = 8.2,
                                ascentM = 40.0,
                                elapsedS = 1_210,
                                paused = false,
                                autoPaused = false,
                                onTogglePause = {},
                                onStop = {},
                                onShowData = {},
                                sensoren = dreiSensoren.copy(
                                    puls = LiveKachel(null, 7),
                                    leistung = LiveKachel(null, null),
                                ),
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/76-kartenseite-sensoren.png")
    }

    /**
     * Zielzeile im Fahrmodus — im vollsten Fall (Navigation und drei
     * Sensoren), damit sie auch dann noch Platz hat.
     */
    @Test
    fun fahrmodusZielzeile() {
        fahrmodus(
            paused = false,
            nav = navigation,
            name = "77-fahrmodus-zielzeile",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.BestweiteRest(7.6),
        )
        compose.onNodeWithText("Noch 8\u00A0km bis zur neuen Bestweite", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun fahrmodusZielzeileEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        fahrmodus(
            paused = true,
            nav = null,
            name = "77-fahrmodus-zielzeile-en",
            zielZeile = ZielZeile.WochenzielRest(0.34),
        )
        compose.onNodeWithText("0.4\u00A0km to your weekly goal", useUnmergedTree = true).assertExists()
    }

    /** Die Live-Leiste auf der Karte mit [zeilen] untereinander (je eine Leiste). */
    private fun liveLeisteZielzeilen(name: String, zeilen: List<ZielZeile>) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            zeilen.forEach { zeile ->
                                LiveRecordingCard(
                                    speedKmh = 23.4,
                                    distanceKm = 48.7,
                                    elapsedS = 7_310,
                                    ascentM = 412.0,
                                    pointCount = 1_812,
                                    paused = false,
                                    onTogglePause = {},
                                    onStop = {},
                                    onOpenRideMode = {},
                                    zielZeile = zeile,
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun liveLeisteZielzeilen() {
        liveLeisteZielzeilen(
            "78-live-leiste-zielzeilen",
            listOf(ZielZeile.WochenzielRest(11.3), ZielZeile.WochenzielGeschafft, ZielZeile.NeueBestweite),
        )
        compose.onNodeWithText("Noch 12\u00A0km bis zum Wochenziel", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Wochenziel geschafft ✓", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun liveLeisteZielzeilenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        liveLeisteZielzeilen(
            "78-live-leiste-zielzeilen-en",
            listOf(ZielZeile.BestweiteRest(7.6), ZielZeile.WochenzielGeschafft, ZielZeile.NeueBestweite),
        )
        compose.onNodeWithText("8\u00A0km to a new longest ride", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Weekly goal reached ✓", useUnmergedTree = true).assertExists()
    }

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

    private fun mockMapContent(label: String): @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier = modifier.background(Color(0xFF1A2820)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = Color(0xFF9CD3B0))
        }
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_DE)
    fun fahrmodusSchmalGrosseSchrift() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        fahrmodus(
            paused = true,
            nav = navigation.copy(naechsteKurve = null, naechsteKurveM = null),
            name = "79-fahrmodus-360-13x-de",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.BestweiteRest(7.6),
            fontScale = 1.3f,
        )
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.map_ride_status_auto_paused), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_show_map_action), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_resume_action), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_end_action), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_end_hold_hint), useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.PHONE_NARROW_EN)
    fun fahrmodusSchmalGrosseSchriftEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        fahrmodus(
            paused = true,
            nav = navigation.copy(
                label = context.getString(R.string.map_screen_planned_route_label),
                naechsteKurve = null,
                naechsteKurveM = null,
            ),
            name = "79-fahrmodus-360-13x-en",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.WochenzielRest(0.34),
            fontScale = 1.3f,
        )
        compose.onNodeWithText(context.getString(R.string.map_ride_status_auto_paused), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_show_map_action), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_resume_action), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_end_action), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.map_ride_end_hold_hint), useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.S25_DE)
    fun fahrmodusS25GrosseSchrift() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        fahrmodus(
            paused = false,
            nav = navigation,
            name = "80-fahrmodus-412-13x-de",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.BestweiteRest(7.6),
            fontScale = 1.3f,
        )
    }

    @Test
    @Config(qualifiers = TestLocales.S25_EN)
    fun fahrmodusS25GrosseSchriftEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        fahrmodus(
            paused = false,
            nav = navigation.copy(label = context.getString(R.string.map_screen_planned_route_label)),
            name = "80-fahrmodus-412-13x-en",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.WochenzielRest(0.34),
            fontScale = 1.3f,
        )
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_DE)
    fun fahrmodusTabS11Hochkant() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        fahrmodus(
            paused = false,
            nav = navigation,
            name = "81-fahrmodus-tab-portrait-de",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.BestweiteRest(7.6),
        )
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_PORTRAIT_EN)
    fun fahrmodusTabS11HochkantEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        fahrmodus(
            paused = true,
            nav = navigation.copy(label = context.getString(R.string.map_screen_planned_route_label)),
            name = "81-fahrmodus-tab-portrait-en",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.WochenzielRest(0.34),
        )
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun fahrmodusTabS11QuerMitKarte() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        fahrmodus(
            paused = false,
            nav = navigation,
            name = "82-fahrmodus-tab-landscape-map-de",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.BestweiteRest(7.6),
            useDialog = false,
            mapContent = mockMapContent("Live-Karte"),
        )
        compose.onNodeWithText("Live-Karte", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun fahrmodusTabS11QuerMitKarteEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        val context = compose.activity
        fahrmodus(
            paused = true,
            nav = navigation.copy(label = context.getString(R.string.map_screen_planned_route_label)),
            name = "82-fahrmodus-tab-landscape-map-en",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.WochenzielRest(0.34),
            useDialog = false,
            mapContent = mockMapContent("Live Map"),
        )
        compose.onNodeWithText("Live Map", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_DE)
    fun fahrmodusTabS11QuerOhneKarte() {
        TestLocales.assertTestLocale(AppLanguage.DE)
        fahrmodus(
            paused = false,
            nav = navigation.copy(offRoute = true),
            name = "83-fahrmodus-tab-landscape-offroute-de",
            sensoren = dreiSensoren,
            zielZeile = ZielZeile.BestweiteRest(7.6),
            mapContent = null,
        )
        val context = compose.activity
        compose.onNodeWithText(context.getString(R.string.map_nav_off_route_title), useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = TestLocales.TAB_S11_LANDSCAPE_EN)
    fun fahrmodusTabS11QuerOhneNavigationEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        fahrmodus(
            paused = true,
            nav = null,
            name = "83-fahrmodus-tab-landscape-nonav-en",
            sensoren = LiveSensorAnzeige(LiveKachel(131, null), PulsQuelle.UHR, null, null),
            zielZeile = ZielZeile.WochenzielRest(0.34),
            mapContent = null,
        )
    }
}
