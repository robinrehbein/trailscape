package de.trailscape.app.indoor.render

import android.content.Context
import android.os.Looper
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.ScreenshotApplication
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.ActorState
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderDeviceInfo
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.RenderStats
import de.trailscape.core.indoor.world.SkySetting
import de.trailscape.core.indoor.world.WorldBounds
import de.trailscape.core.indoor.world.WorldBudget
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.WorldRendererListener
import de.trailscape.core.indoor.world.WorldState
import de.trailscape.core.indoor.world.WorldTrack
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Die Darstellung unter Robolectric — also ohne Grafik und ohne native
 * Filament-Bibliothek: Sie darf nie werfen, meldet den Ausfall genau einmal
 * ueber `onFailure` (auf dem Hauptthread, spaeter), und `WorldSurface` zeigt
 * die 2D-Ersatzansicht. Genau das passiert auch auf einem Geraet, auf dem
 * Filament nicht startet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = TestLocales.DE, application = ScreenshotApplication::class)
class RendererWithoutGraphicsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Recorder : WorldRendererListener {
        val events = mutableListOf<String>()
        override fun onReady(info: RenderDeviceInfo) {
            events += "ready"
        }

        override fun onLoading(loadedTiles: Int, neededTiles: Int) {
            events += "loading"
        }

        override fun onTileFailed(tileId: String, detail: String) {
            events += "tile:$tileId"
        }

        override fun onFailure(failure: RenderFailure, detail: String) {
            assertTrue(detail.isNotBlank())
            events += "failure:$failure"
        }

        override fun onStats(stats: RenderStats) {
            events += "stats"
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun exercise(r: WorldRenderer) {
        r.setSettings(RenderSettings.forQuality(IndoorQuality.HIGH))
        r.setSettings(RenderSettings.SAFE)
        r.setCamera(CameraMode.DRONE)
        r.setSky(SkySetting(hourOfDay = 3.0, dayOfYear = 1, haze = 1.0))
        r.setState(WorldState(timeMs = 1, rider = ActorState(10.0, 5.0, 80.0), ghost = ActorState(5.0, 5.0, 80.0)))
        r.pause()
        r.resume()
    }

    @Test
    fun ohneZeichenflaecheGeschiehtNichtsNatives() {
        val r = createWorldRenderer(context)
        val rec = Recorder()
        r.setListener(rec)
        exercise(r)
        r.loadTestScene()
        idle()
        // Ohne Flaeche keine Engine, also auch kein Ausfall.
        assertTrue(rec.events.none { it.startsWith("failure") }, "${rec.events}")
        r.unloadWorld()
        r.destroy()
        r.destroy()
        exercise(r)
        idle()
    }

    @Test
    fun mitZeichenflaecheMeldetEsKeinGrafik() {
        val r = createWorldRenderer(context) as FilamentWorldRenderer
        val rec = Recorder()
        r.setListener(rec)
        r.loadTestScene()
        val attached = r.attach(SurfaceView(context))
        assertFalse(attached)
        // Der Rueckruf kommt spaeter auf dem Hauptthread, nicht mitten im Aufruf.
        assertTrue(rec.events.isEmpty())
        idle()
        assertEquals(listOf("failure:${RenderFailure.NO_GRAPHICS}"), rec.events)
        assertEquals(RenderFailure.NO_GRAPHICS, r.failure)
        // Danach ist alles erlaubt und wirft nicht; ein zweiter Ausfall wird nicht gemeldet.
        exercise(r)
        r.loadTestScene()
        assertFalse(r.attach(SurfaceView(context)))
        r.detach()
        r.setSurfacePaused(true)
        idle()
        assertEquals(1, rec.events.size)
        // Wer spaeter zuhoert, erfaehrt den Ausfall trotzdem.
        val late = Recorder()
        r.setListener(late)
        idle()
        assertEquals(listOf("failure:${RenderFailure.NO_GRAPHICS}"), late.events)
        r.destroy()
    }

    @Test
    fun kaputteWeltMeldetBadWorld() {
        val r = createWorldRenderer(context)
        val rec = Recorder()
        r.setListener(rec)
        val dir = createTempDirectory("welt").toFile()
        val track = WorldTrack(
            lengthM = 10.0, stepM = 5.0, loop = false,
            e = FloatArray(3), n = FloatArray(3) { it * 5f }, u = FloatArray(3),
            surfaces = listOf(SurfaceSection(0.0, 10.0, SurfaceKind.ASPHALT)), source = IndoorRouteSource.NONE,
        )
        val noTiles = WorldManifest(
            "w", "test", 0, IndoorQuality.LOW, "r", "Runde", GeoOrigin(51.0, 13.0, 0.0),
            WorldBounds(0.0, 0.0, 1.0, 1.0, 0.0, 1.0), track, emptyList(), emptyList(), WorldBudget(1, 1, 1), emptyList(),
        )
        r.loadWorld(dir, noTiles)
        idle()
        assertEquals(listOf("failure:${RenderFailure.BAD_WORLD}"), rec.events)
        r.loadWorld(File(dir, "gibt-es-nicht"), noTiles)
        r.destroy()
        dir.deleteRecursively()
    }

    @Test
    fun weltflaecheZeigtDieErsatzansicht() {
        val r = createWorldRenderer(context)
        r.loadTestScene()
        r.setState(WorldState(timeMs = 1, rider = ActorState(120.0, 5.0, 80.0)))
        compose.setContent { WorldSurface(renderer = r) }
        compose.waitForIdle()
        compose.onNodeWithText("Keine 3D-Ansicht").assertExists()
        compose.runOnIdle { r.destroy() }
    }
}
