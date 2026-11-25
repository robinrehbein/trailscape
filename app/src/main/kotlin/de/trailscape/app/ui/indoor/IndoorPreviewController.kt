package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.app.indoor.indoorCatching
import de.trailscape.app.indoor.problemOf
import de.trailscape.core.indoor.preview.PreviewFrame
import de.trailscape.core.indoor.preview.PreviewPlayer
import de.trailscape.core.indoor.preview.nextPreviewSpeedFactor
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.RenderDeviceInfo
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderStats
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.WorldRendererListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Was der Vorschau-Bildschirm zeigt (Idee 4, docs/indoor/vorschau.md).
 *
 * @property render Zustand der 3D-Ansicht; [IndoorRenderUi.NoWorld] = Vorschau
 *   nur auf dem Hoehenprofil (gewaehlt oder ohne Darstellung).
 * @property paceFromRide 1× folgt dem Schnitt der gefahrenen Tour (sonst dem Modell).
 * @property paceWatts Leistung der gedachten Fahrt, wenn 1× dem Modell folgt.
 */
data class IndoorPreviewUi(
    val routeName: String,
    val profile: IndoorProfileUi?,
    val frame: PreviewFrame,
    val camera: CameraMode,
    val render: IndoorRenderUi,
    val attribution: List<WorldAttribution>,
    val paceFromRide: Boolean,
    val paceWatts: Int,
)

/**
 * Treibt die 3D-Vorschau: ein [PreviewPlayer] (reine Logik in `:core`) und
 * — wenn vorhanden — die vorhandene Darstellung ([WorldRenderer]) ueber
 * `setState`/`setCamera`, wie die Fahrt es tut. Kein Trainer, keine Sitzung,
 * keine Aufzeichnung.
 *
 * Alles vom Hauptthread. Jeder Aufruf der Darstellung steckt in
 * [indoorCatching]; wirft sie, wird sie fuer den Rest der Vorschau nicht mehr
 * gerufen, und die Vorschau laeuft auf dem Hoehenprofil weiter (dasselbe
 * Verhalten wie die Fahrt, `IndoorRideRuntime`). Die Darstellung selbst
 * erzeugt und zerstoert das [IndoorViewModel] — hier wird sie nur benutzt.
 *
 * @param autoLoop `false` = keine eigene Schleife; Tests rufen [tickOnce].
 */
class IndoorPreviewController(
    private val player: PreviewPlayer,
    private val renderer: WorldRenderer?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val autoLoop: Boolean,
    routeName: String,
    profile: IndoorProfileUi?,
    attribution: List<WorldAttribution>,
    initialRender: IndoorRenderUi,
    paceFromRide: Boolean,
    paceWatts: Int,
    initialCamera: CameraMode = CameraMode.DRONE,
) {
    private var renderEnabled = renderer != null && initialRender !is IndoorRenderUi.Failed
    private var loopJob: Job? = null
    private var closed = false

    private val _ui = MutableStateFlow(
        IndoorPreviewUi(
            routeName = routeName,
            profile = profile,
            frame = player.frame(clock()),
            camera = initialCamera,
            render = if (renderer == null && initialRender !is IndoorRenderUi.Failed) IndoorRenderUi.NoWorld else initialRender,
            attribution = attribution,
            paceFromRide = paceFromRide,
            paceWatts = paceWatts,
        ),
    )
    val ui: StateFlow<IndoorPreviewUi> = _ui.asStateFlow()

    /** Der Listener fuer die Darstellung (meldet Bereitschaft, Kacheln, Ausfall). */
    val rendererListener: WorldRendererListener = object : WorldRendererListener {
        override fun onReady(info: RenderDeviceInfo) {
            if (!renderEnabled || closed) return
            _ui.update { if (it.render is IndoorRenderUi.Failed) it else it.copy(render = IndoorRenderUi.Ready) }
        }

        override fun onLoading(loadedTiles: Int, neededTiles: Int) {
            if (!renderEnabled || closed) return
            // Wie ein Video beim Puffern: Fehlen Kacheln fuer die Stelle, wartet die Wiedergabe (mit Obergrenze).
            player.setTilesMissing(neededTiles > 0 && loadedTiles < neededTiles)
            _ui.update {
                when (it.render) {
                    is IndoorRenderUi.Loading -> it.copy(render = IndoorRenderUi.Loading(loadedTiles, neededTiles))
                    else -> it
                }
            }
        }

        override fun onTileFailed(tileId: String, detail: String) = Unit

        override fun onFailure(failure: RenderFailure, detail: String) {
            if (closed) return
            disableRenderer(IndoorRenderUi.Failed(failure, IndoorProblem(IndoorPart.RENDER, false, detail)))
        }

        override fun onStats(stats: RenderStats) = Unit
    }

    /** Erster Zustand an die Darstellung, dann die Schleife (etwa 20 Hz, wie die Fahrt). */
    fun start() {
        if (closed) return
        withRenderer { it.setCamera(_ui.value.camera) }
        tickOnce()
        if (autoLoop) {
            loopJob = scope.launch {
                while (isActive && !closed) {
                    tickOnce()
                    delay(TICK_MS)
                }
            }
        }
    }

    /** Ein Schritt: Abspieler fortschreiben, Darstellung fuettern, Zustand veroeffentlichen. */
    fun tickOnce() {
        if (closed) return
        val now = clock()
        val frame = player.tick(now)
        withRenderer { it.setState(player.worldState(now)) }
        _ui.update { it.copy(frame = frame) }
    }

    fun togglePlay() {
        if (closed) return
        player.togglePlay(clock())
        publish()
    }

    fun pause() {
        if (closed) return
        player.pause()
        publish()
    }

    /** Naechste Geschwindigkeit (1× → 2× → 4× → 8× → 1×). */
    fun cycleSpeed() {
        if (closed) return
        player.setSpeedFactor(nextPreviewSpeedFactor(player.speedFactor))
        publish()
    }

    fun setSpeed(factor: Int) {
        if (closed) return
        player.setSpeedFactor(factor)
        publish()
    }

    /** Sprung an die Stelle [distM] (Schieber ueber dem Hoehenprofil). */
    fun seekTo(distM: Double) {
        if (closed) return
        player.seekTo(distM)
        val now = clock()
        withRenderer { it.setState(player.worldState(now)) }
        publish()
    }

    fun setCamera(mode: CameraMode) {
        if (closed) return
        _ui.update { it.copy(camera = mode) }
        withRenderer { it.setCamera(mode) }
    }

    /** Die App geht in den Hintergrund: anhalten (fortsetzen nur auf Wunsch). */
    fun onAppBackground() = pause()

    /** Haelt die Schleife an; die Darstellung gibt das [IndoorViewModel] frei. */
    fun close() {
        closed = true
        loopJob?.cancel()
        loopJob = null
    }

    private fun publish() {
        _ui.update { it.copy(frame = player.frame(clock())) }
    }

    private fun withRenderer(block: (WorldRenderer) -> Unit) {
        val r = renderer ?: return
        if (!renderEnabled) return
        indoorCatching(IndoorPart.RENDER) { block(r) }.onFailure { e ->
            disableRenderer(IndoorRenderUi.Failed(RenderFailure.INTERNAL, problemOf(IndoorPart.RENDER, e)))
        }
    }

    private fun disableRenderer(failed: IndoorRenderUi.Failed) {
        renderEnabled = false
        // Ohne 3D-Ansicht nicht mehr auf Kacheln warten.
        player.setTilesMissing(false)
        _ui.update { it.copy(render = failed) }
    }

    companion object {
        /** Takt der Schleife (wie die Fahrt: `setState` mit 10–20 Hz). */
        const val TICK_MS: Long = 50L
    }
}
