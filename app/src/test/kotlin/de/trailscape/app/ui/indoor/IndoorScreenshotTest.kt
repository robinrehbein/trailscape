package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.app.sensors.indoor.IndoorDeviceRef
import de.trailscape.app.sensors.indoor.IndoorDevicesContent
import de.trailscape.app.sensors.indoor.IndoorDevicesUiState
import de.trailscape.app.sensors.indoor.IndoorFund
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.ErgState
import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildPhase
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.sin

/**
 * Bilder der Indoor-Bildschirme (docs/indoor.md, 10.6) mit festen Zustaenden.
 * Die Slots (Geraeteliste, 3D-Flaeche) sind durch Platzhalter ersetzt. Laeuft
 * wie die anderen Screenshot-Tests nur mit `-Pscreenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorScreenshotTest {

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
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /**
     * Platzhalter fuer die 3D-Welt: eine gezeichnete Landschaft mit hellem
     * Himmel bis zum Horizont, Huegeln, Wiese und Strasse — damit sich die
     * Lesbarkeit des HUD ueber einem echten Bild beurteilen laesst (der
     * schwierigste Fall ist der fast weisse Himmel am Horizont).
     */
    @Composable
    private fun WorldPlaceholder(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            val horizon = h * 0.46f
            drawRect(
                Brush.verticalGradient(
                    0f to Color(0xFF5E9BD6), 0.55f to Color(0xFFA9CDED), 1f to Color(0xFFEAF3F9),
                    startY = 0f, endY = horizon,
                ),
                size = Size(w, horizon),
            )
            // Wolken
            listOf(Offset(w * 0.22f, h * 0.12f), Offset(w * 0.70f, h * 0.20f), Offset(w * 0.45f, h * 0.31f)).forEach { c ->
                val u = minOf(w, h)
                for (i in -2..2) drawCircle(Color.White.copy(alpha = 0.85f), radius = u * 0.06f, center = Offset(c.x + i * u * 0.05f, c.y + abs(i) * u * 0.012f))
            }
            // Ferne und nahe Huegel
            fun hills(base: Float, amp: Float, freq: Float, phase: Float, color: Color) {
                val path = Path().apply {
                    moveTo(0f, h)
                    for (i in 0..60) {
                        val x = w * i / 60f
                        lineTo(x, base - amp * (0.6f + 0.4f * sin(x / w * freq + phase)))
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path, color)
            }
            hills(horizon + h * 0.01f, h * 0.06f, 7f, 0.4f, Color(0xFF8FA9BE))
            hills(horizon + h * 0.04f, h * 0.05f, 5f, 2.1f, Color(0xFF6E9A5B))
            drawRect(
                Brush.verticalGradient(0f to Color(0xFF6FA34E), 1f to Color(0xFF3F7A2C), startY = horizon + h * 0.03f, endY = h),
                topLeft = Offset(0f, horizon + h * 0.03f),
                size = Size(w, h - horizon),
            )
            // Strasse in die Tiefe
            val vx = w * 0.52f
            val vy = horizon + h * 0.035f
            val road = Path().apply {
                moveTo(vx - w * 0.01f, vy)
                lineTo(vx + w * 0.01f, vy)
                lineTo(w * 0.95f, h)
                lineTo(w * 0.05f, h)
                close()
            }
            drawPath(road, Brush.verticalGradient(0f to Color(0xFF8C8C8C), 1f to Color(0xFF4B4B4B), startY = vy, endY = h))
            var t = 0.05f
            while (t < 1f) {
                val t2 = t + 0.025f + t * 0.05f
                val y1 = vy + (h - vy) * t * t
                val y2 = vy + (h - vy) * t2 * t2
                drawLine(Color(0xFFF2F2F2), Offset(vx + (w * 0.5f - vx) * t * t, y1), Offset(vx + (w * 0.5f - vx) * t2 * t2, y2), strokeWidth = 2f + 10f * t)
                t = t2 + 0.03f + t * 0.06f
            }
            // Baeume links und rechts
            listOf(0.12f to 0.62f, 0.2f to 0.56f, 0.82f to 0.58f, 0.9f to 0.66f, 0.3f to 0.52f).forEach { (fx, fy) ->
                val u = minOf(w, h)
                val r = u * 0.03f + (fy - 0.5f) * u * 0.25f
                drawRect(Color(0xFF5A4030), topLeft = Offset(w * fx - r * 0.12f, h * fy), size = Size(r * 0.24f, r * 1.1f))
                drawCircle(Color(0xFF2F6B2A), radius = r, center = Offset(w * fx, h * fy - r * 0.2f))
            }
        }
    }

    @Composable
    private fun Placeholder(label: String, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.background(Color(0xFF3E5C76)),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White) }
    }

    // ------------------------------------------------------------------ Daten

    private val saved = IndoorRouteOption.Saved(
        rideId = "r1", name = "Hausrunde Ammersee", distanceKm = 42.3, ascentM = 310.0,
        createdAt = 1_760_000_000_000, planned = false, hasWorld = true,
    )
    private val planned = IndoorRouteOption.Saved(
        rideId = "r2", name = "Wochenende Allgäu", distanceKm = 96.8, ascentM = 1240.0,
        createdAt = 1_759_000_000_000, planned = true,
    )
    private val routeSim = IndoorRouteUi(
        options = listOf(IndoorRouteOption.TestWorld, saved, planned),
        selected = saved,
        mode = IndoorMode.SIM,
        ergTitle = null,
        ergMinutes = null,
        ghost = IndoorGhostOption.PastRide("r1", "Hausrunde Ammersee"),
        ghostPastRideAvailable = true,
        defaultGhostWatts = 180,
    )

    private val problem = IndoorProblem(IndoorPart.WORLD, notImplemented = false, detail = "Zeitueberschreitung")

    private val attribution = listOf(
        WorldAttribution(GeoSourceKind.ELEVATION, "Hoehendaten: Copernicus DEM", "CC-BY 4.0", "https://example.org"),
        WorldAttribution(GeoSourceKind.VECTOR, "Karte: OpenStreetMap-Mitwirkende", "ODbL", "https://example.org"),
    )

    private val profile = IndoorProfileUi(
        lengthM = 12_000.0,
        distM = FloatArray(61) { it * 200f },
        eleM = FloatArray(61) { 560f + 40f * sin(it / 9.0).toFloat() + it * 1.2f },
        isLoop = false,
    )

    private fun session(
        mode: IndoorMode = IndoorMode.SIM,
        ghost: GhostState? = GhostState(distM = 4_380.0, speedMps = 7.4, gapM = 120.0, gapS = 16.0),
        erg: ErgState? = null,
        phase: IndoorPhase = IndoorPhase.RUNNING,
    ) = IndoorSessionState(
        phase = phase, mode = mode, nowMs = 0, elapsedS = 1_472, distM = 4_260.0, routeDistM = 4_260.0, lap = 1,
        speedMps = 7.2, powerW = if (mode == IndoorMode.ERG) 212 else 236, cadenceRpm = 88, hrBpm = 148,
        gradePct = 4.6, surface = SurfaceKind.ASPHALT, crr = 0.0055, headwindMps = 1.2,
        gear = GearState(index = 14, count = 24, ratio = 2.3), ghost = ghost, erg = erg,
        totals = IndoorTotals(4_260.0, 96.0, 1_460, 228.0, 234.0, 87.0, 146.0, 332.0),
    )

    private fun ride(
        session: IndoorSessionState? = session(),
        render: IndoorRenderUi = IndoorRenderUi.Ready,
        notice: IndoorNotice? = null,
        ghostLabel: String? = "Fahrt vom 09.10.",
        ergTitle: String? = null,
        startProblem: IndoorProblem? = null,
    ) = IndoorRideUi(
        routeName = "Hausrunde Ammersee", session = session, trainerName = "KICKR CORE 7B2F",
        trainer = TrainerConnection.READY, shifter = ShifterConnection.READY, shifterBatteryPct = 82,
        render = render, camera = CameraMode.CHASE, profile = profile, notice = notice,
        demoPowerW = null, demoCadenceRpm = null, ghostLabel = ghostLabel, ergTitle = ergTitle,
        attribution = attribution, startProblem = startProblem,
    )

    @Composable
    private fun RideScreen(ui: IndoorRideUi, chromeHidden: Boolean = false) {
        IndoorRideScreen(
            ui = ui,
            worldSurface = { m -> WorldPlaceholder(m) },
            onShift = {}, onTogglePause = {}, onFinish = {}, onCameraChange = {},
            onDemoPower = {}, onDemoCadence = {}, onErgAdjust = {}, onDismissNotice = {},
            initialChromeHidden = chromeHidden,
        )
    }

    private val summary = IndoorSummaryUi(
        routeName = "Hausrunde Ammersee", mode = IndoorMode.SIM, distanceKm = 18.4, movingS = 2_948,
        ascentM = 212.0, avgPowerW = 214, normalizedPowerW = 228, avgCadenceRpm = 88, avgHrBpm = 146,
        workKj = 631, avgSpeedKmh = 22.5, laps = 1, ghostLabel = "Fahrt vom 09.10.", ghostGapS = -42.0,
        tooShort = false,
    )

    // ------------------------------------------------------------------ Bilder

    @Test
    fun heuteKarte() = shot("90-indoor-heute-karte") {
        Column(modifier = Modifier.padding(16.dp)) {
            IndoorEntryCard(ergTitle = null, onClick = {})
            Box(modifier = Modifier.padding(8.dp))
            IndoorEntryCard(ergTitle = "Schwelle 3 x 8 Min", onClick = {})
        }
    }

    @Test
    fun koppeln() = shot("91-indoor-koppeln") {
        IndoorPairScreen(
            ui = IndoorPairUi(
                demoTrainer = false,
                trainerName = "KICKR CORE 7B2F",
                shifterName = "Zwift Click",
                chainringTeeth = 48,
            ),
            devicesSection = { Placeholder("Geräteliste", Modifier.fillMaxWidth().height(180.dp)) },
            onDemoChange = {}, onDone = {}, onBack = {},
        )
    }

    private val pairKickr = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE 7B2F", shifterName = "Zwift Click")
    private val pairNone = IndoorPairUi(demoTrainer = false, trainerName = null, shifterName = null)

    /** Die Startseite (docs/indoor/app.md, „Nutzerfluss“). */
    @Composable
    private fun Home(
        route: IndoorRouteUi = routeSim,
        pair: IndoorPairUi = pairKickr,
        issue: IndoorTrainerIssue? = null,
        catalog: IndoorCatalogUi = IndoorCatalogUi(emptyList()),
        day: IndoorDayUi? = null,
        optionsOpen: Boolean = false,
    ) = IndoorHomeScreen(
        route = route, pair = pair, trainerIssue = issue,
        onSelect = {}, onModeChange = {}, onGhostChange = {}, onRide = {}, onBack = {}, onOpenPair = {}, onOpenSettings = {},
        catalog = catalog, day = day, initialOptionsOpen = optionsOpen,
    )

    @Test
    fun streckeSim() = shot("92-indoor-strecke-sim") { Home(optionsOpen = true) }

    @Test
    fun streckeErg() = shot("93-indoor-strecke-erg") {
        Home(
            route = routeSim.copy(
                mode = IndoorMode.ERG, ergTitle = "Schwelle 3 x 8 Min", ergMinutes = 62,
                ghost = IndoorGhostOption.ConstantPower(180),
            ),
            optionsOpen = true,
        )
    }

    // ------------------------------------------- Startseite: Zustaende vor „Fahren"
    // Bilder unter docs/indoor/bilder/nutzerfluss/ (nachher-*).

    private val flowFile = "nutzerfluss"

    /** Nichts bereit: kein Trainer, keine Strecke gewaehlt. */
    @Test
    fun startseiteNichtsBereit() = shot("$flowFile-nachher-01-nichts-bereit") {
        Home(route = routeSim.copy(selected = null, ghost = IndoorGhostOption.None, ghostPastRideAvailable = false), pair = pairNone, issue = IndoorTrainerIssue.NOT_PAIRED, catalog = catalogUi)
    }

    /** Strecke bereit, Trainer fehlt. */
    @Test
    fun startseiteTrainerFehlt() = shot("$flowFile-nachher-02-trainer-fehlt") {
        Home(pair = pairNone, issue = IndoorTrainerIssue.NOT_PAIRED, catalog = catalogUi)
    }

    /** Trainer gemerkt, aber Bluetooth aus. */
    @Test
    fun startseiteBluetoothAus() = shot("$flowFile-nachher-03-bluetooth-aus") {
        Home(issue = IndoorTrainerIssue.BLUETOOTH_OFF, catalog = catalogUi)
    }

    /** Bekannte Strecke gewaehlt, noch nicht geladen: „Herunterladen". */
    @Test
    fun startseiteHerunterladen() = shot("$flowFile-nachher-04-herunterladen") {
        Home(route = routeCatalog.copy(selected = IndoorRouteOption.Catalog("monte-sante-marie", "Schotter von Monte Sante Marie", 13.2, 248.0)), catalog = catalogUi)
    }

    /** Bekannte Strecke laedt. */
    @Test
    fun startseiteLaedt() = shot("$flowFile-nachher-05-laedt") {
        Home(route = routeCatalog.copy(selected = IndoorRouteOption.Catalog("vlaamse-ardennen", "Oude Kwaremont", 10.0, 200.0)), catalog = catalogUi)
    }

    /** Alles bereit (Demo-Trainer, bekannte Strecke geladen). */
    @Test
    fun startseiteBereit() = shot("$flowFile-nachher-06-bereit") {
        Home(route = routeCatalog, pair = IndoorPairUi(demoTrainer = true, trainerName = null, shifterName = null), catalog = catalogUi)
    }

    /** Download gescheitert. */
    @Test
    fun startseiteFehler() = shot("$flowFile-nachher-07-fehler") {
        Home(route = routeCatalog.copy(selected = IndoorRouteOption.Catalog("rennsteig", "Rennsteig bei Oberhof", 10.0, 200.0)), catalog = catalogUi)
    }

    /** Eigene Strecke ohne 3D-Ansicht: „Fahren" fuehrt erst zu „Strecke vorbereiten". */
    @Test
    fun startseiteVorbereiten() = shot("$flowFile-nachher-08-vorbereiten") {
        Home(route = routeSim.copy(selected = planned, ghost = IndoorGhostOption.None, ghostPastRideAvailable = false), catalog = catalogUi)
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun einstellungen() = shot("$flowFile-nachher-09-einstellungen") {
        IndoorSettingsScreen(
            ui = pairKickr.copy(chainringTeeth = 48),
            catalog = IndoorCatalogUi(emptyList(), refreshResult = IndoorCatalogRefresh.UPDATED),
            onChainringChange = {}, onGearTableChange = {}, onTimeOfDayChange = {}, onOtherRidersChange = {},
            onOpenWorlds = {}, onRefreshCatalog = {}, onOpenDiagnostics = {}, onBack = {},
        )
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun startseiteEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("$flowFile-nachher-10-englisch") {
            Home(pair = pairNone, issue = IndoorTrainerIssue.NOT_PAIRED, catalog = catalogUi)
        }
        compose.onNodeWithText("Pair your trainer first.").assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun einstellungenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("$flowFile-nachher-11-einstellungen-en") {
            IndoorSettingsScreen(
                ui = pairKickr, catalog = IndoorCatalogUi(emptyList()),
                onChainringChange = {}, onGearTableChange = {}, onTimeOfDayChange = {}, onOtherRidersChange = {},
                onOpenWorlds = {}, onRefreshCatalog = {}, onOpenDiagnostics = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Ride feel").assertExists()
    }

    /** Pause: „Weiter" und „Fahrt beenden" auf der Startpunkt-Karte. */
    @Test
    fun pauseMitBeenden() = shot("$flowFile-nachher-12-pause-beenden") {
        RideScreen(
            ride(session = session(phase = IndoorPhase.PAUSED, ghost = null), ghostLabel = null).copy(
                start = IndoorStartUi(selectedM = 4_260.0, maxM = 11_900.0, resumeM = null, held = false),
            ),
        )
    }

    private fun catalogItem(
        id: String, de: String, en: String, regionDe: String, regionEn: String, country: String,
        kind: CatalogRideKind, km: Double, hm: Double, surfaces: List<Pair<SurfaceKind, Double>>, mb: Long, aerial: Boolean,
        descDe: String, descEn: String, state: IndoorCatalogState,
    ) = IndoorCatalogItemUi(
        id = id, name = CatalogText(de, en), region = CatalogText(regionDe, regionEn), description = CatalogText(descDe, descEn),
        country = country, kind = kind, distanceKm = km, ascentM = hm, surfaces = surfaces, packageBytes = mb * 1024 * 1024,
        aerial = aerial, state = state,
    )

    /** Alle Zustaende einer Katalogkarte untereinander. */
    private val catalogUi = IndoorCatalogUi(
        items = listOf(
            catalogItem(
                "alpe-dhuez", "Alpe d'Huez", "Alpe d'Huez", "Bourg-d'Oisans, Französische Alpen", "Bourg-d'Oisans, French Alps", "FR",
                CatalogRideKind.ROAD, 15.1, 1105.0, listOf(SurfaceKind.ASPHALT to 1.0), 77, true,
                "Die D211 hinauf nach L'Alpe d'Huez: 21 nummerierte Kehren.", "The D211 up to L'Alpe d'Huez: 21 numbered hairpins.",
                IndoorCatalogState.Ready,
            ),
            catalogItem(
                "vlaamse-ardennen", "Oude Kwaremont, Paterberg und Koppenberg", "Oude Kwaremont, Paterberg and Koppenberg",
                "Flämische Ardennen bei Oudenaarde", "Flemish Ardennes near Oudenaarde", "BE",
                CatalogRideKind.COBBLES, 9.9, 180.0, listOf(SurfaceKind.ASPHALT to 0.59, SurfaceKind.COBBLESTONE to 0.29, SurfaceKind.CONCRETE to 0.12), 71, true,
                "Drei kurze, steile Pflasteranstiege hintereinander.", "Three short, steep cobbled climbs in a row.",
                IndoorCatalogState.Downloading(doneBytes = 31L * 1024 * 1024, totalBytes = 71L * 1024 * 1024),
            ),
            catalogItem(
                "monte-sante-marie", "Schotter von Monte Sante Marie", "Monte Sante Marie gravel", "Crete Senesi bei Asciano, Toskana",
                "Crete Senesi near Asciano, Tuscany", "IT", CatalogRideKind.GRAVEL, 12.0, 280.0,
                listOf(SurfaceKind.COMPACTED to 0.85, SurfaceKind.GRAVEL to 0.14), 64, false,
                "Weiße Schotterstraßen über die Hügel südöstlich von Siena.", "White gravel roads over the hills south-east of Siena.",
                IndoorCatalogState.NotLoaded,
            ),
            catalogItem(
                "rennsteig", "Rennsteig bei Oberhof", "Rennsteig near Oberhof", "Thüringer Wald", "Thuringian Forest", "DE",
                CatalogRideKind.GRAVEL, 13.3, 300.0, listOf(SurfaceKind.GRAVEL to 0.84, SurfaceKind.ASPHALT to 0.09), 108, true,
                "Schotterwege auf dem Kamm des Thüringer Waldes.", "Gravel roads along the crest of the Thuringian Forest.",
                IndoorCatalogState.Failed(IndoorCatalogFailure.NOT_AVAILABLE),
            ),
            catalogItem(
                "kahler-asten", "Waldwege am Kahlen Asten", "Forest roads at Kahler Asten", "Winterberg, Sauerland", "Winterberg, Sauerland", "DE",
                CatalogRideKind.GRAVEL, 13.2, 250.0, listOf(SurfaceKind.COMPACTED to 0.6, SurfaceKind.ASPHALT to 0.21, SurfaceKind.GRAVEL to 0.19), 81, true,
                "Feste Forstwege durch das Rothaargebirge.", "Firm forest roads through the Rothaar mountains.",
                IndoorCatalogState.Paused(doneBytes = 12L * 1024 * 1024, totalBytes = 81L * 1024 * 1024),
            ),
            catalogItem(
                "arenberg", "Wald von Arenberg", "Arenberg Forest", "Wallers bei Valenciennes", "Wallers near Valenciennes", "FR",
                CatalogRideKind.COBBLES, 10.0, 45.0, listOf(SurfaceKind.ASPHALT to 0.68, SurfaceKind.COBBLESTONE to 0.23), 82, true,
                "2,3 km grobes Kopfsteinpflaster durch den Wald.", "2.3 km of rough cobbles through the forest.",
                IndoorCatalogState.UpdateAvailable(installedVersion = 1, newVersion = 2),
            ),
        ),
    )

    private val routeCatalog get() = routeSim.copy(
        selected = IndoorRouteOption.Catalog("alpe-dhuez", "Alpe d'Huez", 15.1, 1105.0),
        ghost = IndoorGhostOption.None,
        ghostPastRideAvailable = false,
    )

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h2600dp-xxhdpi")
    fun streckeBekannteStrecken() = shot("116-indoor-strecke-katalog") {
        Home(route = routeCatalog, catalog = catalogUi)
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun streckeBekannteStreckenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("117-indoor-strecke-katalog-en") { Home(route = routeCatalog, catalog = catalogUi) }
        compose.onNodeWithText("Known routes").assertExists()
    }

    // -----------------------------------------------------------------------
    // Katalog-Vorschau (docs/indoor/catalog.md, „Vorschau je Strecke")
    // -----------------------------------------------------------------------

    /** Echtes Hoehenprofil aus der Strecke im Repo (GPX mit Hoehen), ausgeduennt wie im Katalogbau. */
    private fun gpxProfile(id: String): de.trailscape.core.indoor.catalog.CatalogProfile {
        val text = java.io.File("../tool/indoor-dev/catalog/routes/$id.gpx").readText()
        val pts = Regex("""<trkpt lat="([-0-9.]+)" lon="([-0-9.]+)"><ele>([-0-9.]+)</ele>""").findAll(text)
            .map { Triple(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }.toList()
        val dist = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            val (la1, lo1) = pts[i - 1]
            val (la2, lo2) = pts[i]
            val dLat = Math.toRadians(la2 - la1)
            val dLon = Math.toRadians(lo2 - lo1)
            val a = sin(dLat / 2) * sin(dLat / 2) + kotlin.math.cos(Math.toRadians(la1)) * kotlin.math.cos(Math.toRadians(la2)) * sin(dLon / 2) * sin(dLon / 2)
            dist[i] = dist[i - 1] + 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a))
        }
        return requireNotNull(
            de.trailscape.core.indoor.catalog.CatalogProfile.thinned(dist.last(), pts.size, { dist[it] }, { pts[it].third }),
        )
    }

    private fun previewFile(name: String): String =
        requireNotNull(javaClass.getResource("/indoor/catalog-preview/$name")) { "Testbild $name fehlt" }.path

    /** Die vier Formen der Vorschau: Bild + Laser + Profil, Bild + „Neu", nur Profil, Platzhalter. */
    private val previewUi get() = IndoorCatalogUi(
        items = listOf(
            catalogItem(
                "kahler-asten", "Waldwege am Kahlen Asten", "Forest roads at Kahler Asten", "Winterberg, Sauerland", "Winterberg, Sauerland", "DE",
                CatalogRideKind.GRAVEL, 13.2, 248.0, listOf(SurfaceKind.COMPACTED to 0.6, SurfaceKind.ASPHALT to 0.21, SurfaceKind.GRAVEL to 0.19), 106, true,
                "Feste Forstwege durch das Rothaargebirge rund um den Kahlen Asten.", "Firm forest roads through the Rothaar mountains around Kahler Asten.",
                IndoorCatalogState.NotLoaded,
            ).copy(laser = true, profile = gpxProfile("kahler-asten"), previewPath = previewFile("kahler-asten-v9.jpg")),
            catalogItem(
                "alpe-dhuez", "Alpe d'Huez", "Alpe d'Huez", "Bourg-d'Oisans, Französische Alpen", "Bourg-d'Oisans, French Alps", "FR",
                CatalogRideKind.ROAD, 15.1, 1253.0, listOf(SurfaceKind.ASPHALT to 1.0), 101, true,
                "Die D211 hinauf nach L'Alpe d'Huez: 21 nummerierte Kehren.", "The D211 up to L'Alpe d'Huez: 21 numbered hairpins.",
                IndoorCatalogState.Ready,
            ).copy(isNew = true, profile = gpxProfile("alpe-dhuez"), previewPath = previewFile("alpe-dhuez-v9.jpg")),
            catalogItem(
                "arenberg", "Wald von Arenberg", "Arenberg Forest", "Wallers bei Valenciennes", "Wallers near Valenciennes", "FR",
                CatalogRideKind.COBBLES, 10.0, 20.0, listOf(SurfaceKind.ASPHALT to 0.68, SurfaceKind.COBBLESTONE to 0.23), 108, true,
                "2,3 km grobes Kopfsteinpflaster durch den Wald.", "2.3 km of rough cobbles through the forest.",
                IndoorCatalogState.NotLoaded,
            ).copy(profile = gpxProfile("arenberg")),
            catalogItem(
                "monte-sante-marie", "Schotter von Monte Sante Marie", "Monte Sante Marie gravel", "Crete Senesi bei Asciano, Toskana",
                "Crete Senesi near Asciano, Tuscany", "IT", CatalogRideKind.GRAVEL, 12.0, 284.0,
                listOf(SurfaceKind.COMPACTED to 0.85, SurfaceKind.GRAVEL to 0.14), 79, false,
                "Weiße Schotterstraßen über die Hügel südöstlich von Siena.", "White gravel roads over the hills south-east of Siena.",
                IndoorCatalogState.NotLoaded,
            ),
        ),
    )

    private fun previewShot(name: String, waitFor: String) {
        compose.setContent {
            TrailscapeTheme {
                ProvideTestLanguage {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        ) {
                            IndoorCatalogList(previewUi, "catalog:alpe-dhuez") {}
                        }
                    }
                }
            }
        }
        // Auf den Text warten statt einmal zu pruefen (stabil auch bei langsamem Bilddecodieren).
        compose.waitUntil(10_000) { compose.onAllNodesWithText(waitFor).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h2400dp-xxhdpi")
    fun katalogVorschau() {
        previewShot("katalog-vorschau-01-karten", "Noch keine Vorschau")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Extra detailliert").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Neu").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Herunterladen · 106,0 MB").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Vorschaubild der 3D-Welt von Alpe d'Huez").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp-h2400dp-xxhdpi")
    fun katalogVorschauEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        previewShot("katalog-vorschau-02-karten-en", "No preview yet")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("New").fetchSemanticsNodes().isNotEmpty() }
    }

    private val plan = IndoorWorldUi.Plan(
        routeName = "Hausrunde Ammersee", distanceKm = 42.3, quality = IndoorQuality.MEDIUM,
        downloadBytes = 186L * 1024 * 1024, diskBytes = 240L * 1024 * 1024,
        hosts = listOf("elevation-tiles-prod.s3.amazonaws.com", "tiles.openfreemap.org", "gis.bayern.de"),
        aerialLabels = listOf("Bayern"), surfaceSource = IndoorRouteSource.BROUTER_REMATCH,
    )

    @Composable
    private fun World(ui: IndoorWorldUi) = IndoorWorldScreen(ui, {}, {}, {}, {}, {}, {})

    @Test
    fun weltPlan() = shot("94-indoor-welt-plan") { World(plan) }

    @Test
    fun weltBau() = shot("95-indoor-welt-bau") {
        World(IndoorWorldUi.Building(WorldBuildPhase.AERIAL, done = 34, total = 96, fraction = 0.46f))
    }

    @Test
    fun weltFehler() = shot("96-indoor-welt-fehler") {
        World(IndoorWorldUi.Failed(WorldBuildFailed.Reason.OFFLINE, null, canRetry = true))
    }

    @Test
    fun weltFehlerIntern() = shot("96b-indoor-welt-fehler-intern") {
        World(
            IndoorWorldUi.Failed(
                WorldBuildFailed.Reason.INTERNAL, null, canRetry = true,
                detail = "IllegalStateException: Budget ueberschritten @ de.trailscape.core.indoor.world.WorldBuilderImpl.assemble:356",
            ),
        )
    }

    @Test
    fun fahrenSimGeist() = shot("97-indoor-fahren-sim-geist") { RideScreen(ride()) }

    @Test
    fun fahrenErg() = shot("98-indoor-fahren-erg") {
        RideScreen(
            ride(
                session = session(IndoorMode.ERG, ghost = null, erg = ErgState(2, 220, 214, false)),
                ghostLabel = null, ergTitle = "Schwelle 3 x 8 Min",
            ),
        )
    }

    @Test
    fun fahrenAusgeblendet() = shot("112-indoor-fahren-ausgeblendet") {
        // Nach einem Tipp auf die Welt: nur Kernwerte und Schalten.
        RideScreen(ride(), chromeHidden = true)
    }

    @Test
    fun fahrenGrosseSchrift() = shot("113-indoor-fahren-grosse-schrift") {
        // Systemschrift 130 %: nichts bricht um oder wird abgeschnitten.
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            RideScreen(ride())
        }
    }

    @Test
    fun fahrenPause() = shot("114-indoor-fahren-pause") {
        RideScreen(ride(session = session(phase = IndoorPhase.PAUSED)))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w891dp-h411dp-xxhdpi")
    fun fahrenQuerErg() = shot("115-indoor-fahren-quer-erg") {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            RideScreen(
                ride(
                    session = session(IndoorMode.ERG, ghost = null, erg = ErgState(2, 220, 214, false)),
                    ghostLabel = null, ergTitle = "Schwelle 3 x 8 Min",
                ),
            )
        }
    }

    @Test
    fun fahrenDarstellungFehlt() = shot("99-indoor-fahren-darstellung-fehlt") {
        RideScreen(
            ride(
                render = IndoorRenderUi.Failed(
                    RenderFailure.NO_GRAPHICS,
                    de.trailscape.app.indoor.IndoorProblem(
                        de.trailscape.app.indoor.IndoorPart.RENDER, false, "GlProbe: OpenGL ES 3.0 fehlt (2.0)",
                    ),
                ),
            ),
        )
    }

    @Test
    fun fahrenOhneWelt() = shot("100-indoor-fahren-ohne-welt") {
        RideScreen(ride(render = IndoorRenderUi.NoWorld))
    }

    @Test
    fun fahrenHinweis() = shot("101-indoor-fahren-hinweis") {
        RideScreen(ride(notice = IndoorNotice.TRAINER_LOST, session = session(phase = IndoorPhase.AUTO_PAUSED)))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w360dp-h640dp-xxhdpi")
    fun fahrenDemoOhneWeltKlein() = shot("108-indoor-fahren-demo-klein") {
        // Kleines Geraet, Demo-Regler, Hinweis eingeblendet, ohne Welt: Die
        // untere Bedienung scrollt, das Hoehenprofil behaelt Platz.
        RideScreen(
            ride(render = IndoorRenderUi.NoWorld, notice = IndoorNotice.TRAINER_LOST)
                .copy(demoPowerW = 180, demoCadenceRpm = 85, trainerName = "Demo-Trainer"),
        )
    }

    @Test
    fun koppelnDemo() = shot("109-indoor-koppeln-demo") {
        IndoorPairScreen(
            ui = IndoorPairUi(demoTrainer = true, trainerName = null, shifterName = null, chainringTeeth = 50),
            devicesSection = { Placeholder("Geräteliste", Modifier.fillMaxWidth().height(180.dp)) },
            onDemoChange = {}, onDone = {}, onBack = {},
        )
    }

    // Koppeln mit dem echten Abschnitt des Slices indoor-ble (zustandslos,
    // Beispieldaten statt Bluetooth) — prueft, dass im eingebetteten Zustand
    // nichts doppelt steht oder abgeschnitten ist. Hohes Bild, damit die ganze
    // Seite ohne Scrollen zu sehen ist.

    private val bleState = IndoorDevicesUiState(
        bleSupported = true,
        bluetoothOn = true,
        permissionGranted = true,
        permissionPermanentlyDenied = false,
        legacyPermission = false,
        systemHinweise = true,
        gemerkt = emptyMap(),
        scanning = false,
        scanFehler = null,
        funde = emptyList(),
    )

    @Composable
    private fun PairWithDevices(ui: IndoorPairUi, state: IndoorDevicesUiState) {
        IndoorPairScreen(
            ui = ui,
            devicesSection = {
                IndoorDevicesContent(
                    state = state,
                    onScan = {}, onStopScan = {}, onPair = { _, _ -> }, onForget = {}, onGrant = {}, onEnableBluetooth = {},
                )
            },
            onDemoChange = {}, onDone = {}, onBack = {},
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1900dp-xxhdpi")
    fun koppelnMitGeraeteabschnittOhneBerechtigung() = shot("110-indoor-koppeln-geraete-berechtigung") {
        PairWithDevices(
            ui = IndoorPairUi(demoTrainer = false, trainerName = null, shifterName = null),
            state = bleState.copy(permissionGranted = false),
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1900dp-xxhdpi")
    fun koppelnMitGeraeteabschnittGekoppelt() = shot("111-indoor-koppeln-geraete-gekoppelt") {
        val trainer = IndoorDeviceRef(IndoorDeviceRole.TRAINER, "AA:BB:CC:DD:EE:01", "KICKR CORE 7B2F")
        val shifter = IndoorDeviceRef(IndoorDeviceRole.SHIFTER, "AA:BB:CC:DD:EE:04", "Zwift Click")
        PairWithDevices(
            ui = IndoorPairUi(
                demoTrainer = false,
                trainerName = trainer.name,
                shifterName = shifter.name,
                gearTable = VirtualGearTable.BIKE_GRX_2X12,
            ),
            state = bleState.copy(
                gemerkt = mapOf(IndoorDeviceRole.TRAINER to trainer, IndoorDeviceRole.SHIFTER to shifter),
                scanning = true,
                funde = listOf(IndoorFund("AA:BB:CC:DD:EE:02", "Wahoo KICKR 0C2D", setOf(IndoorDeviceRole.TRAINER), -63)),
            ),
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w891dp-h411dp-xxhdpi")
    fun fahrenQuer() = shot("102-indoor-fahren-quer") { RideScreen(ride()) }

    @Test
    fun auswertungGespeichert() = shot("103-indoor-auswertung-gespeichert") {
        IndoorSummaryScreen(summary.copy(savedRideId = "r9"), {}, {}, {})
    }

    @Test
    fun auswertungZuKurz() = shot("104-indoor-auswertung-zu-kurz") {
        IndoorSummaryScreen(
            summary.copy(tooShort = true, movingS = 41, distanceKm = 0.2, ascentM = 1.0, ghostGapS = null, ghostLabel = null),
            {}, {}, {},
        )
    }

    @Test
    fun weltverwaltung() = shot("105-indoor-weltverwaltung") {
        IndoorWorldsScreen(
            IndoorWorldsUi(
                worlds = listOf(
                    IndoorWorldEntryUi("w1", "Hausrunde Ammersee", IndoorQuality.MEDIUM, 238L * 1024 * 1024, 1_760_000_000_000, attribution, false, true),
                    IndoorWorldEntryUi("w3", "Alte Runde (Strecke gelöscht)", IndoorQuality.HIGH, 410L * 1024 * 1024, 1_740_000_000_000, attribution, true, true),
                    IndoorWorldEntryUi("testwelt", "Testwelt", IndoorQuality.LOW, 12L * 1024 * 1024, null, emptyList(), false, true),
                ),
                totalBytes = 660L * 1024 * 1024,
                cacheBytes = 85L * 1024 * 1024,
            ),
            {}, {}, {},
        )
    }

    // ------------------------------------------------------------------ Startpunkt und Fortsetzen

    /** Vor dem Losfahren an der gemerkten Stelle, Zahlen noch auf null. */
    private fun startSession(phase: IndoorPhase, atM: Double) = session(phase = phase, ghost = null).copy(
        elapsedS = if (phase == IndoorPhase.READY) 0 else 1_472,
        distM = atM, routeDistM = atM, speedMps = 0.0,
        powerW = if (phase == IndoorPhase.READY) null else 0,
        totals = if (phase == IndoorPhase.READY) IndoorTotals(0.0, 0.0, 0, null, null, null, null, 0.0) else session().totals,
    )

    private fun startRide(phase: IndoorPhase, atM: Double, start: IndoorStartUi, render: IndoorRenderUi = IndoorRenderUi.Ready) =
        ride(session = startSession(phase, atM), render = render, ghostLabel = null).copy(start = start)

    private val resumeStart = IndoorStartUi(selectedM = 7_400.0, maxM = 11_900.0, resumeM = 7_400.0, held = true)

    @Test
    fun startWeiter() = shot("118-indoor-start-weiter") {
        RideScreen(startRide(IndoorPhase.READY, 7_400.0, resumeStart))
    }

    @Test
    fun startPauseVerschieben() = shot("119-indoor-start-pause") {
        RideScreen(
            startRide(
                IndoorPhase.PAUSED,
                9_200.0,
                IndoorStartUi(selectedM = 9_200.0, maxM = 11_900.0, resumeM = null, held = false),
                render = IndoorRenderUi.NoWorld,
            ),
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w891dp-h411dp-xxhdpi")
    fun startQuer() = shot("120-indoor-start-quer") {
        RideScreen(startRide(IndoorPhase.READY, 7_400.0, resumeStart))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w891dp-h411dp-xxhdpi")
    fun startQuerPause() = shot("122-indoor-start-quer-pause") {
        RideScreen(
            startRide(
                IndoorPhase.PAUSED,
                3_000.0,
                IndoorStartUi(selectedM = 3_000.0, maxM = 11_900.0, resumeM = null, held = false),
            ),
        )
    }

    @Test
    fun startOhneGemerkteStelle() = shot("123-indoor-start-anfang") {
        // Vor jeder Fahrt: Vorauswahl km 0, echter Trainer (Treten startet ebenso).
        RideScreen(
            startRide(
                IndoorPhase.READY,
                0.0,
                IndoorStartUi(selectedM = 0.0, maxM = 11_900.0, resumeM = null, held = true, pedalStarts = true),
            ),
        )
    }

    @Test
    fun auswertungAbKm() = shot("124-indoor-auswertung-ab-km") {
        IndoorSummaryScreen(
            summary.copy(routeName = "Hausrunde Ammersee", distanceKm = 4.6, startKm = 7.4, ghostLabel = null, ghostGapS = null),
            {}, {}, {},
        )
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun startEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("121-indoor-start-en") { RideScreen(startRide(IndoorPhase.READY, 7_400.0, resumeStart)) }
        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(app.getString(R.string.indoor_app_start_go_action)).assertExists()
        compose.onNodeWithText(app.getString(R.string.indoor_app_start_from_beginning_chip)).assertExists()
    }

    // ------------------------------------------------------------------ Englisch

    @Test
    @Config(qualifiers = "+en-rGB")
    fun fahrenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("106-indoor-fahren-en") { RideScreen(ride()) }
        val text = RuntimeEnvironment.getApplication().getString(R.string.indoor_app_ride_pause_action)
        compose.onAllNodesWithContentDescription(text).onFirst().assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun streckeEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("107-indoor-strecke-en") { Home(optionsOpen = true) }
        val title = RuntimeEnvironment.getApplication().getString(R.string.indoor_app_route_title)
        compose.onNodeWithText(title).assertExists()
    }

    // ------------------------------------------------- Einheit des Tages

    private val dayClimbs = de.trailscape.core.indoor.DayClimbs(count = 3, minMinutes = 6, maxMinutes = 8, reps = 3, workMinutes = 8)

    private val dayOffer = IndoorDayUi.Offer(
        kind = de.trailscape.core.indoor.DaySessionKind.PLANNED,
        title = "Schwelle 3 x 8 Min",
        minutes = 52,
        plannedMinutes = null,
        terrain = de.trailscape.core.indoor.DayTerrain.CLIMBS,
        suggestions = listOf(
            IndoorDaySuggestionUi(
                key = "ride:allgaeu", name = "Wochenende Allgäu",
                availability = de.trailscape.core.indoor.DayRouteAvailability.BUILD,
                rideKm = 24.6, startKm = 12.4, climbs = dayClimbs, buildMinutes = 14,
            ),
            IndoorDaySuggestionUi(
                key = "catalog:grosser-feldberg", name = "Großer Feldberg",
                availability = de.trailscape.core.indoor.DayRouteAvailability.READY,
                rideKm = 11.7, climbs = dayClimbs.copy(count = 1, minMinutes = 38, maxMinutes = 38), endsEarlyMin = 9,
            ),
            IndoorDaySuggestionUi(
                key = "catalog:schauinsland", name = "Schauinsland",
                availability = de.trailscape.core.indoor.DayRouteAvailability.DOWNLOAD,
                rideKm = 15.7, climbs = dayClimbs.copy(count = 1, minMinutes = 52, maxMinutes = 52),
                downloadBytes = 114L * 1024 * 1024, estimated = true,
            ),
            IndoorDaySuggestionUi(
                key = "plan", name = "Neue Runde ab deinem Startpunkt",
                availability = de.trailscape.core.indoor.DayRouteAvailability.PLAN,
                rideKm = 24.0, buildMinutes = 8,
            ),
        ),
    )

    private val dayFile = "tageseinheit"

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun tageseinheitStrecke() = shot("$dayFile-01-strecke-intervalle") {
        Home(route = routeSim.copy(ergTitle = "Schwelle 3 x 8 Min", ergMinutes = 52), day = dayOffer)
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun tageseinheitKarten() = shot("$dayFile-02-karten-verfuegbarkeit") {
        Column(modifier = Modifier.padding(16.dp)) {
            for (i in 1..3) {
                IndoorDayCard(ui = dayOffer.copy(index = i), onRide = {}, onNext = {})
                Box(modifier = Modifier.padding(6.dp))
            }
        }
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun tageseinheitTagesform() = shot("$dayFile-03-tagesform-ruhetag") {
        Column(modifier = Modifier.padding(16.dp)) {
            IndoorDayCard(
                ui = dayOffer.copy(
                    kind = de.trailscape.core.indoor.DaySessionKind.PLANNED_REDUCED,
                    title = "Grundlage", minutes = 81, plannedMinutes = 90,
                    terrain = de.trailscape.core.indoor.DayTerrain.ROLLING,
                    suggestions = listOf(
                        dayOffer.suggestions[0].copy(name = "Hausrunde Ammersee", availability = de.trailscape.core.indoor.DayRouteAvailability.READY, rideKm = 38.2, startKm = null, climbs = null, buildMinutes = null),
                    ),
                ),
                onRide = {}, onNext = {},
            )
            Box(modifier = Modifier.padding(6.dp))
            IndoorDayCard(
                ui = dayOffer.copy(
                    kind = de.trailscape.core.indoor.DaySessionKind.EASY,
                    title = "Lockere Runde", minutes = 45,
                    terrain = de.trailscape.core.indoor.DayTerrain.FLAT,
                    suggestions = listOf(
                        IndoorDaySuggestionUi(
                            key = "testwelt", name = "Testwelt",
                            availability = de.trailscape.core.indoor.DayRouteAvailability.READY,
                            rideKm = 19.3, laps = 7,
                        ),
                    ),
                ),
                onRide = {}, onNext = {},
            )
            Box(modifier = Modifier.padding(6.dp))
            IndoorDayCard(ui = IndoorDayUi.Rest("Schwelle 3 x 8 Min"), onRide = {}, onNext = {}, onOpenIndoor = {})
            Box(modifier = Modifier.padding(6.dp))
            IndoorDayCard(ui = IndoorDayUi.Loading, onRide = {}, onNext = {})
        }
    }

    private fun dayEndRide(end: IndoorDayEndUi, phase: IndoorPhase) = ride(
        session = session(IndoorMode.ERG, ghost = null, erg = ErgState(6, 110, 0, end.kind == de.trailscape.core.indoor.DayRideEnd.WORKOUT_DONE), phase = phase),
        ghostLabel = null, ergTitle = "Schwelle 3 x 8 Min",
    ).copy(dayEnd = end)

    @Test
    fun tageseinheitGeschafft() = shot("$dayFile-04-fahren-einheit-geschafft") {
        RideScreen(dayEndRide(IndoorDayEndUi(de.trailscape.core.indoor.DayRideEnd.WORKOUT_DONE), IndoorPhase.RUNNING))
    }

    @Test
    fun tageseinheitStreckeZuEnde() = shot("$dayFile-05-fahren-strecke-zu-ende") {
        RideScreen(dayEndRide(IndoorDayEndUi(de.trailscape.core.indoor.DayRideEnd.ROUTE_DONE_EARLY, 12), IndoorPhase.FINISHED))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w891dp-h411dp-xxhdpi")
    fun tageseinheitGeschafftQuer() = shot("$dayFile-06-fahren-einheit-geschafft-quer") {
        RideScreen(dayEndRide(IndoorDayEndUi(de.trailscape.core.indoor.DayRideEnd.WORKOUT_DONE), IndoorPhase.RUNNING))
    }

    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h1100dp-xxhdpi")
    fun tageseinheitEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("$dayFile-07-strecke-en") {
            Home(route = routeSim.copy(ergTitle = "Threshold 3 x 8 min", ergMinutes = 52), day = dayOffer.copy(title = "Threshold 3 x 8 min"))
        }
        compose.onAllNodesWithText("Today’s session").onFirst().assertExists()
    }

    private val dayGenerated = IndoorDayGeneratedUi(km = 23.8, climbs = 3, reps = 3, workMinutes = 8, buildMinutes = 4)

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun tageseinheitGeneriert() = shot("$dayFile-08-zwei-wege") {
        Column(modifier = Modifier.padding(16.dp)) {
            IndoorDayCard(ui = dayOffer.copy(generated = dayGenerated, path = IndoorDayPath.GENERATED), onRide = {}, onNext = {})
            Box(modifier = Modifier.padding(6.dp))
            IndoorDayCard(ui = dayOffer.copy(generated = dayGenerated.copy(ready = true), path = IndoorDayPath.REAL), onRide = {}, onNext = {})
        }
    }

    @Test
    @Config(qualifiers = "${TestLocales.EN}-w411dp-h1500dp-xxhdpi")
    fun tageseinheitGeneriertEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("$dayFile-09-zwei-wege-en") {
            Column(modifier = Modifier.padding(16.dp)) {
                IndoorDayCard(ui = dayOffer.copy(title = "Threshold 3 x 8 min", generated = dayGenerated, path = IndoorDayPath.GENERATED), onRide = {}, onNext = {})
            }
        }
        compose.onAllNodesWithText("Generated route").onFirst().assertExists()
    }

    // ------------------------------------------------------------------ Server-Bau (docs/indoor/serverbau.md)

    private val serverPlan = plan.copy(
        routeName = "Balkantrasse", distanceKm = 7.7, downloadBytes = 19L * 1024 * 1024, diskBytes = 130L * 1024 * 1024,
        hosts = listOf("elevation-tiles-prod.s3.amazonaws.com", "tiles.openfreemap.org", "www.wms.nrw.de"),
        aerialLabels = listOf("Nordrhein-Westfalen"),
    )
    private val nrwLaser = IndoorServerLaserUi(de.trailscape.core.indoor.server.LaserExpectation.FULL, "nw", 100)
    private val serverFile = "serverbau"

    @Composable
    private fun ServerWorld(ui: IndoorWorldUi, server: IndoorServerUi) =
        IndoorWorldScreen(ui, {}, {}, {}, {}, {}, {}, server = server, initialAdvancedOpen = true)

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun serverAngebot() = shot("$serverFile-01-angebot") {
        ServerWorld(serverPlan, IndoorServerUi.Offer(nrwLaser, queueLength = 0))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun serverGrenze() = shot("$serverFile-02-grenze-teilweise") {
        ServerWorld(
            serverPlan.copy(routeName = "Grenzrunde Kraichgau", distanceKm = 24.0, aerialLabels = emptyList()),
            IndoorServerUi.Offer(IndoorServerLaserUi(de.trailscape.core.indoor.server.LaserExpectation.PARTIAL, "bw", 72), 0),
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun serverOhneLaser() = shot("$serverFile-03-ohne-laser") {
        ServerWorld(plan, IndoorServerUi.Offer(IndoorServerLaserUi(de.trailscape.core.indoor.server.LaserExpectation.NONE, null, 0), 0))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun serverWarteschlange() = shot("$serverFile-04-warteschlange") {
        ServerWorld(serverPlan, IndoorServerUi.Working(IndoorServerStep.QUEUED, nrwLaser, queuePosition = 2))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun serverBau() = shot("$serverFile-05-bau") {
        ServerWorld(serverPlan, IndoorServerUi.Working(IndoorServerStep.BUILDING, nrwLaser, fraction = 0.43f))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun serverDownload() = shot("$serverFile-06-download") {
        ServerWorld(
            serverPlan,
            IndoorServerUi.Working(IndoorServerStep.DOWNLOADING, nrwLaser, downloadedBytes = 31L * 1024 * 1024, totalBytes = 78L * 1024 * 1024),
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1100dp-xxhdpi")
    fun serverFertig() = shot("$serverFile-07-fertig") {
        ServerWorld(
            IndoorWorldUi.Ready("Balkantrasse", attribution, fromServer = true, serverLaser = "nw", replacedDeviceWorld = true),
            IndoorServerUi.Hidden,
        )
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun serverVorhandeneGeraetewelt() = shot("$serverFile-08-geraetewelt-ersetzen") {
        ServerWorld(IndoorWorldUi.Ready("Balkantrasse", attribution), IndoorServerUi.Offer(nrwLaser, 0, replacesDeviceWorld = true))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun serverVersion() = shot("$serverFile-09-version") {
        ServerWorld(serverPlan, IndoorServerUi.Incompatible(de.trailscape.core.indoor.server.BuilderMismatch.UPDATE_SERVER, "indoor-world/8"))
    }

    @Test
    @Config(qualifiers = "${TestLocales.DE}-w411dp-h1500dp-xxhdpi")
    fun serverFehler() = shot("$serverFile-10-fehler-offline") {
        ServerWorld(serverPlan, IndoorServerUi.Failed(IndoorServerFailure.OFFLINE, "java.net.ConnectException: Failed to connect", canResume = true))
    }

    @Test
    fun serverWeltverwaltung() = shot("$serverFile-11-weltverwaltung") {
        IndoorWorldsScreen(
            IndoorWorldsUi(
                worlds = listOf(
                    IndoorWorldEntryUi("w1", "Balkantrasse", IndoorQuality.MEDIUM, 131L * 1024 * 1024, 1_795_500_000_000, attribution, false, true, fromServer = true, serverLaser = "nw"),
                    IndoorWorldEntryUi("w2", "Hausrunde Ammersee", IndoorQuality.MEDIUM, 238L * 1024 * 1024, 1_760_000_000_000, attribution, false, true),
                ),
                totalBytes = 369L * 1024 * 1024,
                cacheBytes = 85L * 1024 * 1024,
            ),
            {}, {}, {},
        )
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp-h1500dp-xxhdpi")
    fun serverAngebotEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("$serverFile-12-angebot-en") { ServerWorld(serverPlan, IndoorServerUi.Offer(nrwLaser, 0)) }
        compose.onAllNodesWithText("Build on server in laser quality").onFirst().assertExists()
    }
}
