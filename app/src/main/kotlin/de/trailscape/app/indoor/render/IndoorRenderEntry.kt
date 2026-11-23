package de.trailscape.app.indoor.render

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.SkySetting
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.WorldRendererListener
import de.trailscape.core.indoor.world.WorldState
import java.io.File

/**
 * # Einstiegspunkte des Slices indoor-render
 *
 * EINSTIEGSDATEI (docs/indoor.md, Abschnitt 3). Besitzer: Slice indoor-render.
 *
 * Die **Signaturen** der drei Funktionen hier sind Vertrag: Der Slice
 * indoor-app ruft genau sie auf. Der Architekt hat Platzhalter hinterlegt, die
 * bauen und laufen, aber nichts zeichnen — indoor-render ersetzt die Ruempfe
 * (Umsetzung in eigenen Dateien dieses Pakets) und laesst Namen, Parameter und
 * Rueckgabetypen unveraendert.
 */

/**
 * Erzeugt die Filament-Darstellung. Billig und ohne Nebenwirkung: Engine und
 * Zeichenflaeche entstehen erst, wenn [WorldSurface] sie zeigt. Scheitert der
 * Start (kein OpenGL ES 3.0, Treiberfehler), kommt das ueber
 * `WorldRendererListener.onFailure` — diese Funktion wirft nie.
 */
fun createWorldRenderer(context: Context): WorldRenderer = PlatzhalterRenderer()

/**
 * Die Zeichenflaeche zu [renderer]. Fuellt [modifier]; die Oberflaeche legt ihr
 * HUD darueber. Haengt den Renderer an den Lebenszyklus (pausiert im
 * Hintergrund), zerstoert ihn aber nicht — das tut, wer ihn erzeugt hat.
 */
@Composable
fun WorldSurface(renderer: WorldRenderer, modifier: Modifier = Modifier) {
    Box(modifier)
}

/**
 * Die Diagnoseseite der Darstellung: Geraet und Grafiktreiber, Bildrate,
 * Schalter fuer jede Stufe aus `RenderSettings`, die Testszene ohne Geodaten.
 * Eine ganze Seite mit eigener Kopfzeile; [onBack] schliesst sie.
 */
@Composable
fun IndoorDiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier)
}

/** Zeichnet nichts und meldet das ehrlich — bis indoor-render die echte Darstellung einsetzt. */
private class PlatzhalterRenderer : WorldRenderer {
    private var listener: WorldRendererListener? = null

    private fun melde() {
        listener?.onFailure(RenderFailure.INTERNAL, "Die 3D-Darstellung ist noch nicht umgesetzt (Slice indoor-render).")
    }

    override fun setListener(listener: WorldRendererListener?) {
        this.listener = listener
    }

    override fun loadWorld(worldDir: File, manifest: WorldManifest) = melde()

    override fun loadTestScene() = melde()

    override fun unloadWorld() = Unit

    override fun setState(state: WorldState) = Unit

    override fun setCamera(mode: CameraMode) = Unit

    override fun setSky(sky: SkySetting) = Unit

    override fun setSettings(settings: RenderSettings) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun destroy() {
        listener = null
    }
}
