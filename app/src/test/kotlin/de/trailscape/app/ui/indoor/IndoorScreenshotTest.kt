package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.ErgState
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
    private fun RideScreen(ui: IndoorRideUi) {
        IndoorRideScreen(
            ui = ui,
            worldSurface = { m -> Placeholder("3D-Darstellung", m) },
            onShift = {}, onTogglePause = {}, onFinish = {}, onCameraChange = {},
            onDemoPower = {}, onDemoCadence = {}, onErgAdjust = {}, onDismissNotice = {},
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
            onDemoChange = {}, onChainringChange = {}, onContinue = {}, onBack = {}, onOpenDiagnostics = {},
        )
    }

    @Test
    fun streckeSim() = shot("92-indoor-strecke-sim") {
        IndoorRouteScreen(routeSim, {}, {}, {}, {}, {})
    }

    @Test
    fun streckeErg() = shot("93-indoor-strecke-erg") {
        IndoorRouteScreen(
            routeSim.copy(
                mode = IndoorMode.ERG, ergTitle = "Schwelle 3 x 8 Min", ergMinutes = 62,
                ghost = IndoorGhostOption.ConstantPower(180),
            ),
            {}, {}, {}, {}, {},
        )
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
    fun fahrenDarstellungFehlt() = shot("99-indoor-fahren-darstellung-fehlt") {
        RideScreen(ride(render = IndoorRenderUi.Failed(RenderFailure.NO_GRAPHICS, null)))
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
            onDemoChange = {}, onChainringChange = {}, onContinue = {}, onBack = {}, onOpenDiagnostics = {},
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

    // ------------------------------------------------------------------ Englisch

    @Test
    @Config(qualifiers = "+en-rGB")
    fun fahrenEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("106-indoor-fahren-en") { RideScreen(ride()) }
        val text = RuntimeEnvironment.getApplication().getString(R.string.indoor_app_ride_pause_action)
        compose.onAllNodesWithText(text, substring = true).onFirst().assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun streckeEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("107-indoor-strecke-en") { IndoorRouteScreen(routeSim, {}, {}, {}, {}, {}) }
        val title = RuntimeEnvironment.getApplication().getString(R.string.indoor_app_route_title)
        compose.onNodeWithText(title).assertExists()
    }
}
