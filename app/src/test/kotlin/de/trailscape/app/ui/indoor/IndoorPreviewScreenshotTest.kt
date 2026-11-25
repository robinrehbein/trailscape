package de.trailscape.app.ui.indoor

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.app.ui.map.ProvideTestLanguage
import de.trailscape.app.ui.theme.TrailscapeTheme
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.preview.PreviewFrame
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldAttribution
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bilder und Bedienung der 3D-Vorschau (Idee 4, docs/indoor/vorschau.md). Die
 * 3D-Flaeche ist ein gezeichneter Platzhalter. Bilder nur mit `-Pscreenshots`
 * (Ablage `docs/indoor/bilder/vorschau/`); die Bedienungstests laufen immer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TestLocales.S25_DE, application = ScreenshotApplication::class)
class IndoorPreviewScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val screenshots get() = System.getProperty("trailscape.screenshots") == "true"

    private fun show(content: @Composable () -> Unit) {
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
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        assumeTrue(screenshots)
        show(content)
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Platzhalter fuer die 3D-Ansicht aus der Drohnenperspektive: Himmel, Huegel, die Strasse von oben. */
    @Composable
    private fun WorldPlaceholder(modifier: Modifier = Modifier) {
        Canvas(modifier = modifier) {
            val w = size.width
            val h = size.height
            val horizon = h * 0.30f
            drawRect(
                Brush.verticalGradient(0f to Color(0xFF6FA6DA), 1f to Color(0xFFE6F0F7), startY = 0f, endY = horizon),
                size = Size(w, horizon),
            )
            drawRect(
                Brush.verticalGradient(0f to Color(0xFF7FA35A), 1f to Color(0xFF4F7A33), startY = horizon, endY = h),
                topLeft = Offset(0f, horizon),
                size = Size(w, h - horizon),
            )
            val hills = Path().apply {
                moveTo(0f, horizon + h * 0.04f)
                for (i in 0..40) {
                    val x = w * i / 40f
                    lineTo(x, horizon - h * 0.03f * (1 + sin(i / 4.0).toFloat()))
                }
                lineTo(w, horizon + h * 0.04f)
                close()
            }
            drawPath(hills, Color(0xFF5C7F4A))
            // Die Strasse windet sich vom unteren Rand zum Horizont.
            val road = Path().apply {
                moveTo(w * 0.42f, h)
                cubicTo(w * 0.20f, h * 0.75f, w * 0.80f, h * 0.55f, w * 0.52f, horizon + h * 0.02f)
            }
            drawPath(road, Color(0xFF6B6B6B), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.05f))
            drawPath(road, Color(0xFFEDEDED), style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.004f))
            // Die Markierung (Fahrer) auf der Strasse.
            drawCircle(Color(0xFFFF7043), radius = w * 0.018f, center = Offset(w * 0.47f, h * 0.70f))
        }
    }

    private val profile = IndoorProfileUi(
        lengthM = 38_400.0,
        distM = FloatArray(97) { it * 400f },
        eleM = FloatArray(97) { 420f + 120f * sin(it / 14.0).toFloat() + (if (it in 40..60) (it - 40) * 9f else if (it > 60) 180f else 0f) },
        isLoop = false,
    )

    private val attribution = listOf(
        WorldAttribution(GeoSourceKind.ELEVATION, "Hoehendaten: Copernicus DEM", "CC-BY 4.0", "https://example.org"),
        WorldAttribution(GeoSourceKind.VECTOR, "Karte: OpenStreetMap-Mitwirkende", "ODbL", "https://example.org"),
    )

    private fun frame(
        distM: Double = 18_200.0,
        playing: Boolean = true,
        factor: Int = 4,
        waiting: Boolean = false,
        atEnd: Boolean = false,
    ) = PreviewFrame(
        distM = distM, lengthM = 38_400.0, eleM = 612.0, gradePct = 6.4, speedMps = if (playing) 21.6 else 0.0,
        elapsedS = 2_880.0, totalS = 6_120.0, remainingS = (6_120.0 - 2_880.0) / factor,
        playing = playing, speedFactor = factor, waitingForTiles = waiting, atEnd = atEnd,
    )

    private fun ui(
        render: IndoorRenderUi = IndoorRenderUi.Ready,
        frame: PreviewFrame = frame(),
        camera: CameraMode = CameraMode.DRONE,
        paceFromRide: Boolean = false,
    ) = IndoorPreviewUi(
        routeName = "Schwarzwald-Runde über den Kandel", profile = profile, frame = frame, camera = camera,
        render = render, attribution = attribution, paceFromRide = paceFromRide, paceWatts = 162,
    )

    @Composable
    private fun Preview(
        ui: IndoorPreviewUi,
        onTogglePlay: () -> Unit = {},
        onCycleSpeed: () -> Unit = {},
        onSeek: (Double) -> Unit = {},
        onCameraChange: (CameraMode) -> Unit = {},
    ) = IndoorPreviewScreen(
        ui = ui,
        worldSurface = { m -> WorldPlaceholder(m) },
        onBack = {},
        onTogglePlay = onTogglePlay,
        onCycleSpeed = onCycleSpeed,
        onSeek = onSeek,
        onCameraChange = onCameraChange,
    )

    // -----------------------------------------------------------------------
    // Bilder
    // -----------------------------------------------------------------------

    @Test
    fun vorschauLaeuft() = shot("vorschau-01-drohne-4x") { Preview(ui()) }

    @Test
    fun vorschauAngehaltenVerfolger() = shot("vorschau-02-angehalten-verfolger") {
        Preview(ui(frame = frame(playing = false, factor = 1), camera = CameraMode.CHASE, paceFromRide = true))
    }

    @Test
    fun vorschauLaedt() = shot("vorschau-03-laedt") {
        Preview(ui(render = IndoorRenderUi.Loading(3, 9), frame = frame(distM = 0.0, playing = false, factor = 1)))
    }

    @Test
    fun vorschauWartetAufKacheln() = shot("vorschau-04-wartet") { Preview(ui(frame = frame(factor = 8, waiting = true))) }

    @Test
    fun vorschauOhne3d() = shot("vorschau-05-ohne-3d") {
        Preview(ui(render = IndoorRenderUi.Failed(RenderFailure.NO_GRAPHICS, IndoorProblem(IndoorPart.RENDER, false, "Attrappe"))))
    }

    @Test
    fun vorschauNurProfil() = shot("vorschau-06-nur-profil") {
        Preview(ui(render = IndoorRenderUi.NoWorld, frame = frame(factor = 2)))
    }

    @Test
    fun vorschauAmZiel() = shot("vorschau-07-ziel") {
        Preview(ui(frame = frame(distM = 38_400.0, playing = false, atEnd = true)))
    }

    @Test
    fun vorbereitenFuerDieVorschau() = shot("vorschau-08-vorbereiten") {
        IndoorWorldScreen(
            ui = IndoorWorldUi.Plan(
                routeName = "Schwarzwald-Runde über den Kandel", distanceKm = 38.4, quality = IndoorQuality.MEDIUM,
                downloadBytes = 164L * 1024 * 1024, diskBytes = 210L * 1024 * 1024,
                hosts = listOf("elevation-tiles-prod.s3.amazonaws.com", "tiles.openfreemap.org"),
                aerialLabels = listOf("Baden-Württemberg"), surfaceSource = IndoorRouteSource.BROUTER_REMATCH,
            ),
            onLoad = {}, onCancel = {}, onRideWithoutWorld = {}, onRetry = {}, onBack = {}, onContinue = {},
            withoutWorldLabel = context.getString(R.string.idee04_world_without_action),
            continueLabel = context.getString(R.string.idee04_world_continue_action),
        )
    }

    @Test
    @Config(qualifiers = "+en-rGB")
    fun vorschauEnglisch() {
        TestLocales.assertTestLocale(AppLanguage.EN)
        shot("vorschau-09-en") { Preview(ui()) }
        compose.onNodeWithText(context.getString(R.string.idee04_preview_eyebrow)).assertExists()
        assertEquals("3D preview", context.getString(R.string.idee04_preview_eyebrow))
    }

    // -----------------------------------------------------------------------
    // Bedienung (laeuft immer)
    // -----------------------------------------------------------------------

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun knoepfeRufenIhreAktion() {
        var toggled = 0
        var cycled = 0
        var camera: CameraMode? = null
        show {
            Preview(
                ui(),
                onTogglePlay = { toggled++ },
                onCycleSpeed = { cycled++ },
                onCameraChange = { camera = it },
            )
        }
        compose.onNodeWithContentDescription(context.getString(R.string.idee04_preview_pause_cd)).performClick()
        compose.onNodeWithTag(PREVIEW_SPEED_TAG).performClick()
        compose.onNodeWithContentDescription(
            context.getString(R.string.idee04_preview_camera_cd, context.getString(R.string.idee04_preview_camera_drone)),
        ).performClick()
        assertEquals(1, toggled)
        assertEquals(1, cycled)
        assertEquals(CameraMode.CHASE, camera)
        compose.onNodeWithContentDescription(context.getString(R.string.idee04_preview_speed_cd, 4)).assertExists()
    }

    @Test
    fun tippenAufsProfilSpringt() {
        var seekedTo: Double? = null
        show { Preview(ui(), onSeek = { seekedTo = it }) }
        compose.onNodeWithTag(PREVIEW_SCRUBBER_TAG).performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * 0.75f, height / 2f))
        }
        compose.waitForIdle()
        val d = kotlin.test.assertNotNull(seekedTo)
        assertTrue(d in 38_400.0 * 0.70..38_400.0 * 0.80, "Sprung auf etwa 75 %: $d")
    }

    @Test
    fun kameraReihenfolge() {
        assertEquals(CameraMode.CHASE, nextPreviewCamera(CameraMode.DRONE))
        assertEquals(CameraMode.HANDLEBAR, nextPreviewCamera(CameraMode.CHASE))
        assertEquals(CameraMode.DRONE, nextPreviewCamera(CameraMode.HANDLEBAR))
    }
}
