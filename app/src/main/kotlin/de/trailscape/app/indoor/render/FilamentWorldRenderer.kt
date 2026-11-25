package de.trailscape.app.indoor.render

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.android.UiHelper
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderDeviceInfo
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.RenderStats
import de.trailscape.core.indoor.world.RenderTiers
import de.trailscape.core.indoor.sky.SkyClouds
import de.trailscape.core.indoor.world.SkySetting
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldModels
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.WorldRendererListener
import de.trailscape.core.indoor.world.WorldState
import de.trailscape.core.indoor.world.WorldTrack
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * # Die Filament-Darstellung
 *
 * Umsetzung von [WorldRenderer] (Vertrag in `core/…/world/WorldRenderer.kt`,
 * docs/indoor.md 7.6). Grundsaetze:
 *
 *  * **Ein Thread.** Alles, was Filament beruehrt, laeuft auf dem
 *    Hauptthread: Engine erzeugen, Swapchain, Assets, die Bildschleife
 *    (Choreographer), Zerstoeren. Nur [setState] darf von jedem Thread kommen
 *    (landet in einer [AtomicReference]), Dateien liest ein eigener
 *    E/A-Thread.
 *  * **Faul.** Der Konstruktor beruehrt keine native Bibliothek. Die Engine
 *    entsteht beim ersten [attach] (wenn `WorldSurface` eine Flaeche zeigt).
 *    Unter Robolectric und in Tests ohne Flaeche passiert also nichts Natives.
 *  * **Defensiv.** Jede Stufe, jeder Bildschritt und jeder Rueckruf steckt in
 *    `try/catch (Throwable)`. Ein Fehler wird zu `onTileFailed` (Fahrt geht
 *    weiter) oder zu `onFailure` (Meldung statt Bild); danach ist die
 *    Darstellung aus, gibt ihre Filament-Teile frei und `WorldSurface` zeigt
 *    die 2D-Ersatzansicht. Nichts wirft nach aussen.
 *  * **Ueberlebt Drehung und Flaechenverlust.** [detach] gibt nur die
 *    Swapchain frei; Engine, Szene und Kacheln bleiben, bis [destroy] kommt.
 */
internal class FilamentWorldRenderer(context: Context) : WorldRenderer {
    private val appContext = context.applicationContext ?: context
    private val main = Handler(Looper.getMainLooper())
    private val pendingState = AtomicReference<WorldState?>(null)

    private var listener: WorldRendererListener? = null
    private val guard = RenderGuard(appContext)

    /** Im sicheren Modus (siehe [RenderGuard]) bleiben die Zusaetze der Grafik-Pakete aus. */
    @set:JvmName("assignSettings")
    private var settings: RenderSettings = RenderSettings.forQuality(IndoorQuality.MEDIUM)
        set(value) {
            field = if (guard.safeMode) {
                // Sicherer Modus: auch alle Zusaetze der Grafikstufen Hoch/Ultra aus.
                RenderTiers.withoutTierExtras(value.copy(groundDetail = false, wind = false, birds = false, otherRiders = 0))
            } else {
                value
            }
        }
    private var settingsExplicit = false
    private var sky: SkySetting = SkySetting.DEFAULT
    private var cameraMode: CameraMode = CameraMode.CHASE

    private sealed class Request {
        data object Test : Request()
        class World(val dir: File, val manifest: WorldManifest) : Request()
    }

    private var request: Request? = null
    private var appliedRequest: Request? = null
    private var destroyed = false
    private var pausedByApi = false
    private var pausedBySurface = false

    // Filament (nur Hauptthread) -------------------------------------------
    private var core: FilamentCore? = null
    private var uiHelper: UiHelper? = null
    private var surfaceView: SurfaceView? = null
    private var rider: AvatarActor? = null
    private var cockpit: CockpitActor? = null
    private var ghost: AvatarActor? = null
    private var life: LifeLayer? = null
    private var otherRidersEnabled = true
    private var staticScene: LoadedAsset? = null
    private var streamer: TileStreamer? = null
    private var track: WorldTrack? = null
    private var originLatDeg = 51.0

    /** Sichtweite der Welt (`IndoorQuality.viewDistanceM`): Der Dunst reicht bis an ihren Rand. */
    private var viewDistanceM: Double? = null
    private var skyLighting: SkyLighting? = null
    private var io: ExecutorService? = null

    // Bewegung und Kamera ---------------------------------------------------
    private val riderMotion = MotionSmoother()
    private val ghostMotion = MotionSmoother()
    private val riderLane = Smoothed(1.0)
    private val ghostLane = Smoothed(1.0)
    private val rig = CameraRig()
    private var offset = V3.ZERO
    private var lastFrameNs = 0L
    private var fogBaseU = 0.0
    private var lastRenderedNs = 0L
    private var frameScheduled = false
    private val meter = FrameMeter()
    private var readySent = false
    private var lastProgress: Pair<Int, Int>? = null
    private var deviceInfo: RenderDeviceInfo? = null
    /** Seit wann (ms) bei laufender Schleife und vorhandener Swapchain kein Bild durchkam; 0 = zuletzt gezeichnet. */
    private var noFrameSinceMs = 0L
    private var stage = RenderStage.ENGINE

    /** Fuer `WorldSurface`: `null` = alles gut, sonst warum die 2D-Ersatzansicht laeuft. */
    var failure: RenderFailure? by mutableStateOf(null)
        private set

    /** Fuer die 2D-Ersatzansicht: das Streckenband der geladenen Welt (oder Testszene). */
    var fallbackTrack: WorldTrack? by mutableStateOf(null)
        private set

    /** Gezeigte Laufmeter (nur Hauptthread lesen; fuer die Ersatzansicht). */
    val shownDistM: Double get() = riderMotion.distM

    /** Letzter Zustand (fuer die Ersatzansicht, wenn keine Bildschleife laeuft). */
    val lastState: WorldState? get() = pendingState.get()

    // Bildzeitmessung der Diagnose (RenderBench) ---------------------------
    private var bench: RenderBench? = null
    private var benchDone: ((BenchResult) -> Unit)? = null
    private val gpuTimes = GpuFrameTimes()

    /** Fortschritt der laufenden Messung 0..1, `null` = keine. */
    fun benchProgress(): Double? = bench?.progress(System.nanoTime())

    /** Misst [seconds] s lang die Bildzeit (nach 3 s Vorlauf) und meldet das Ergebnis auf dem Hauptthread. */
    fun startBench(label: String, seconds: Int = 30, onDone: (BenchResult) -> Unit) {
        bench = RenderBench(label, seconds * 1_000_000_000L)
        benchDone = onDone
    }

    fun cancelBench() {
        bench = null
        benchDone = null
    }

    /** Tatsaechliche Renderaufloesung, etwa einmal je Sekunde (Diagnose; Compose-Zustand). */
    var resolution: ResolutionInfo? by mutableStateOf(null)
        private set

    /** Bisherige Messwerte, fuer die Diagnoseseite (Compose-Zustand). */
    var lastStats: RenderStats? by mutableStateOf(null)
        private set

    var info: RenderDeviceInfo? by mutableStateOf(null)
        private set

    // =======================================================================
    // WorldRenderer
    // =======================================================================

    override fun setListener(listener: WorldRendererListener?) = guardApi("setListener") {
        this.listener = listener
        // Wer spaeter zuhoert, erfaehrt einen schon eingetretenen Ausfall trotzdem.
        failure?.let { f -> postFailure(f, failureNote ?: "frueherer Ausfall") }
    }

    override fun loadWorld(worldDir: File, manifest: WorldManifest) = guardApi("loadWorld") {
        if (destroyed) return@guardApi
        // Auch nach einem frueheren Ausfall: die 2D-Ersatzansicht zeigt dann das Profil dieser Welt.
        if (manifest.track.sampleCount >= 2) fallbackTrack = manifest.track
        if (failure != null) return@guardApi
        stage = RenderStage.MANIFEST
        val problem = manifestProblem(manifest) ?: if (!worldDir.isDirectory) "Weltverzeichnis fehlt" else null
        if (problem != null) {
            fail(RenderFailure.BAD_WORLD, "Darstellung, Stufe „${RenderStage.MANIFEST.label}“: $problem")
            return@guardApi
        }
        request = Request.World(worldDir, manifest)
        guard.arm()
        fallbackTrack = manifest.track
        warmUpSky()
        // Ohne ausdrueckliche Einstellungen gilt die Voreinstellung der Qualitaet, mit der die Welt gebaut ist.
        if (!settingsExplicit) {
            settings = RenderSettings.forQuality(manifest.quality)
            core?.let { c -> runStage(RenderStage.SCENE) { applySettings(c); applyResolution() } }
        }
        applyRequestIfPossible()
    }

    /**
     * Rechnet das Wolkenrauschen der Himmels-Cubemap (einmal je Prozess, einige
     * hundert Millisekunden) auf einem Hintergrund-Thread vor, waehrend Engine
     * und Kacheln anlaufen — sonst taete es `applySky` auf dem Hauptthread.
     */
    private fun warmUpSky() {
        if (skyWarmStarted) return
        skyWarmStarted = true
        runCatching {
            Thread({ runCatching { SkyClouds.rawCube(SkyClouds.CUBE_SIZE) } }, "trailscape-sky").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
        }
    }

    private var skyWarmStarted = false

    override fun loadTestScene() = guardApi("loadTestScene") {
        warmUpSky()
        if (destroyed || failure != null) return@guardApi
        request = Request.Test
        fallbackTrack = TestSceneModel.track()
        applyRequestIfPossible()
    }

    override fun unloadWorld() = guardApi("unloadWorld") {
        guard.disarm()
        request = null
        fallbackTrack = null
        clearScene()
        rider?.setVisible(false)
        ghost?.setVisible(false)
        appliedRequest = null
    }

    override fun setState(state: WorldState) {
        // Von jedem Thread erlaubt: nur ablegen.
        pendingState.set(state)
    }

    override fun setCamera(mode: CameraMode) = guardApi("setCamera") {
        cameraMode = mode
        rig.mode = mode
    }

    override fun setSky(sky: SkySetting) = guardApi("setSky") {
        this.sky = sky
        core?.let { c -> runStage(RenderStage.SCENE) { applySky(c) } }
    }

    override fun setOtherRidersEnabled(enabled: Boolean) = guardApi("setOtherRidersEnabled") {
        otherRidersEnabled = enabled
    }

    override fun setSettings(settings: RenderSettings) = guardApi("setSettings") {
        settingsExplicit = true
        val skyChanged = settings.sky != this.settings.sky
        this.settings = settings.copy(
            resolutionScale = settings.resolutionScale.coerceIn(0.5, 1.0),
            maxFps = if (settings.maxFps <= 30) 30 else 60,
        )
        core?.let { c ->
            runStage(RenderStage.SCENE) {
                applySettings(c)
                if (skyChanged) applySky(c)
                applyResolution()
            }
        }
    }

    override fun pause() = guardApi("pause") {
        guard.disarm()
        pausedByApi = true
        updateLoop()
    }

    override fun resume() = guardApi("resume") {
        if (request is Request.World && !pausedBySurface) guard.arm()
        pausedByApi = false
        updateLoop()
    }

    override fun destroy() = guardApi("destroy") {
        if (destroyed) return@guardApi
        guard.disarm()
        destroyed = true
        listener = null
        teardownFilament()
        io?.shutdownNow()
        io = null
    }

    // =======================================================================
    // Zeichenflaeche (von WorldSurface, Hauptthread)
    // =======================================================================

    /**
     * Haengt die Darstellung an [view]. Startet beim ersten Mal die Engine.
     * Liefert `false`, wenn es nichts zu zeichnen gibt (Ausfall, zerstoert) —
     * dann zeigt `WorldSurface` die Ersatzansicht.
     */
    fun attach(view: SurfaceView): Boolean {
        if (destroyed || failure != null) return false
        return try {
            if (surfaceView === view && uiHelper != null) return true
            detach()
            if (!ensureCore()) return false
            stage = RenderStage.SURFACE
            val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            helper.renderCallback = surfaceCallback
            // Vor attachTo zuweisen: Ist die Flaeche schon da, ruft attachTo sofort onNativeWindowChanged.
            uiHelper = helper
            surfaceView = view
            refreshDisplayRate(view)
            helper.attachTo(view)
            scaledSurface = false
            view.addOnLayoutChangeListener(layoutListener)
            applyResolution()
            applyRequestIfPossible()
            updateLoop()
            true
        } catch (t: Throwable) {
            fail(classifyFailure(t, stage), failureDetail(stage, t))
            false
        }
    }

    /**
     * Loest [view], falls sie noch die angehaengte Flaeche ist. Compose kann
     * beim Umbau die neue View anhaengen, bevor es die alte freigibt — deren
     * `onRelease` darf dann die neue nicht abhaengen.
     */
    fun detach(view: SurfaceView) {
        if (view === surfaceView) detach()
    }

    private fun refreshDisplayRate(view: SurfaceView) {
        val hz = (if (Build.VERSION.SDK_INT >= 30) runCatching { view.context.display.refreshRate }.getOrNull() else null)
            ?: runCatching {
                @Suppress("DEPRECATION")
                (view.context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
            }.getOrNull()
        if (hz != null && hz > 1f) {
            displayHz = hz
            core?.let { c ->
                runCatching { c.setDisplayRefreshRate(hz) }
                applySettings(c)
            }
        }
    }

    private var displayHz = 60f

    /** Loest die Flaeche (Compose entfernt die View, Drehung). Engine und Welt bleiben. */
    fun detach() {
        try {
            stopLoop()
            uiHelper?.let { h ->
                h.renderCallback = null
                h.detach()
            }
            core?.destroySwapChain(wait = true)
        } catch (t: Throwable) {
            Log.w(TAG, "detach", t)
        } finally {
            surfaceView?.let { v -> runCatching { v.removeOnLayoutChangeListener(layoutListener) } }
            uiHelper = null
            surfaceView = null
        }
    }

    /** Lebenszyklus der Seite (WorldSurface): im Hintergrund nicht zeichnen. */
    fun setSurfacePaused(paused: Boolean) {
        pausedBySurface = paused
        if (paused) guard.disarm() else if (request is Request.World && !pausedByApi && !destroyed) guard.arm()
        try {
            updateLoop()
        } catch (t: Throwable) {
            Log.w(TAG, "setSurfacePaused", t)
        }
    }

    private val surfaceCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            guardFrame(RenderStage.SURFACE) {
                val c = core ?: return@guardFrame
                val h = uiHelper ?: return@guardFrame
                c.createSwapChain(surface, h.swapChainFlags)
                noFrameSinceMs = 0L
                updateLoop()
            }
        }

        override fun onDetachedFromSurface() {
            guardFrame(RenderStage.SURFACE) {
                // Android baut die Flaeche gleich ab: Filament muss vorher fertig sein.
                core?.destroySwapChain(wait = true)
            }
        }

        override fun onResized(width: Int, height: Int) {
            guardFrame(RenderStage.SURFACE) {
                core?.let { c ->
                    runCatching { c.engine.flushAndWait() }
                    c.resize(width, height)
                }
            }
        }
    }

    // =======================================================================
    // Aufbau
    // =======================================================================

    private fun ensureCore(): Boolean {
        if (core != null) return true
        stage = RenderStage.ENGINE
        if (deviceInfo == null) deviceInfo = probeDevice()
        val c = try {
            FilamentCore()
        } catch (t: Throwable) {
            fail(classifyFailure(t, RenderStage.ENGINE), failureDetail(RenderStage.ENGINE, t))
            return false
        }
        core = c
        c.materialPackages = loadMaterialPackages()
        life = LifeLayer(c) {
            runCatching { appContext.assets.open("${WorldModels.ASSET_DIR}/${WorldModels.RIDER}").use { it.readBytes() } }.getOrNull()
                ?: runCatching { AvatarModel.buildGlb(AvatarModel.RIDER) }.getOrNull()
        }
        deviceInfo = deviceInfo?.copy(backend = c.backendName)
        info = deviceInfo
        if (io == null) {
            io = Executors.newSingleThreadExecutor { r ->
                Thread(r, "trailscape-render-io").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
        }
        stage = RenderStage.SCENE
        applySettings(c)
        applySky(c)
        return true
    }

    /** Eigene Materialien aus `assets/indoor/materials` (fehlen sie, bleibt es bei Filaments Skybox und dem Wasser der Kachel). */
    private fun loadMaterialPackages(): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        for (name in FilamentCore.MATERIALS) {
            // Sicherer Modus: nur der Himmel (laeuft auch in der Testszene), alles andere macht der Ubershader.
            if (guard.safeMode && name != FilamentCore.SKY_MATERIAL) continue
            runCatching { appContext.assets.open("$MATERIAL_DIR/$name.filamat").use { it.readBytes() } }
                .onSuccess { out[name] = it }
        }
        return out
    }

    private fun applySettings(c: FilamentCore) {
        val l = skyLighting ?: SkyModel.lighting(sky, originLatDeg, viewDistanceM).also { skyLighting = it }
        // Die Schalter fuer Baeume, Gebaeude, Kleinteile prueft der Streamer in jedem Bild selbst.
        c.applySettings(settings, l.fogDensity, l.fogColor, fogHeight())
        // Boden-Detail, Software-Anisotropie und Wind der eigenen Materialien (Qualitaet)
        c.worldMaterials.apply(groundDetail = settings.groundDetail, wind = settings.wind, aniso = settings.groundDetail)
    }

    private fun applySky(c: FilamentCore) {
        val l = SkyModel.lighting(sky, originLatDeg, viewDistanceM)
        skyLighting = l
        c.setFogInScattering(l.daylight.fogInScatteringStart, l.daylight.fogInScatteringSize)
        c.applySky(l, settings.sky)
        c.applyFog(settings.fog, l.fogDensity, l.fogColor, fogHeight())
    }

    private fun fogHeight(): Double {
        // Dunst bezogen auf die Hoehe des Fahrers (schwebender Ursprung abgezogen) — 40 m darunter liegt der „Boden“ des Dunsts.
        val t = track ?: return -40.0
        return t.poseAt(riderMotion.distM).u - offset.y - 40.0
    }

    private fun applyResolution() {
        val h = uiHelper ?: return
        val v = surfaceView ?: return
        val w = v.width
        val hh = v.height
        if (w <= 0 || hh <= 0) {
            // Groesse noch unbekannt: nach dem Layout nachholen.
            v.post { if (surfaceView === v) runCatching { applyResolution() } }
            return
        }
        val s = settings.resolutionScale
        if (s >= 0.999) {
            if (scaledSurface) {
                // Zurueck auf volle Groesse: Puffer folgt wieder dem Layout.
                v.holder.setSizeFromLayout()
                scaledSurface = false
            }
        } else {
            // Feste, kleinere Puffergroesse; der Compositor skaliert hoch.
            h.setDesiredSize((w * s).toInt().coerceAtLeast(1), (hh * s).toInt().coerceAtLeast(1))
            scaledSurface = true
        }
    }

    private var scaledSurface = false

    private val layoutListener = android.view.View.OnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
        if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
            if (v === surfaceView && settings.resolutionScale < 0.999) runCatching { applyResolution() }
        }
    }

    /** Baut Szene, Fahrer und ggf. Kacheln fuer die angeforderte Welt — sobald die Engine laeuft. */
    private fun applyRequestIfPossible() {
        val c = core ?: return
        val req = request ?: return
        if (req === appliedRequest) return
        clearScene()
        appliedRequest = req
        readySent = false
        lastProgress = null
        riderMotion.reset()
        ghostMotion.reset()
        rig.snapNext()

        // Stufe: Modelle
        stage = RenderStage.MODELS
        val newTrack = when (req) {
            is Request.Test -> TestSceneModel.track()
            is Request.World -> req.manifest.track
        }
        originLatDeg = when (req) {
            is Request.Test -> 51.0
            is Request.World -> req.manifest.origin.lat
        }
        viewDistanceM = when (req) {
            is Request.Test -> null
            is Request.World -> req.manifest.quality.viewDistanceM
        }
        track = newTrack
        life?.setTrack(newTrack)
        offset = startOffset(newTrack)
        c.setWorldOffset(offset)
        runStage(RenderStage.SCENE) { applySky(c) }
        if (failure != null) return
        loadAvatars(c) ?: return
        rider?.setVisible(true)

        when (req) {
            is Request.Test -> {
                val scene = runStage(RenderStage.MODELS) { LoadedAsset.loadSync(c, TestSceneModel.buildGlb()) } ?: return
                scene.setParent(c.worldRoot)
                scene.applyShadows()
                scene.addToScene()
                staticScene = scene
            }
            is Request.World -> {
                stage = RenderStage.TILES
                streamer = TileStreamer(
                    core = c,
                    worldDir = req.dir,
                    manifest = req.manifest,
                    io = io ?: return,
                    post = { r -> main.post { if (!destroyed) runCatching { r.run() }.onFailure { Log.w(TAG, "Kachel", it) } } },
                    callbacks = object : TileStreamer.Callbacks {
                        override fun onTileFailed(tileId: String, detail: String) {
                            postListener { it.onTileFailed(tileId, detail) }
                        }
                    },
                )
            }
        }
        updateLoop()
    }

    private fun startOffset(t: WorldTrack): V3 {
        val p = t.poseAt(0.0)
        return V3(Math.round(p.e / 256.0) * 256.0, 0.0, -Math.round(p.n / 256.0) * 256.0)
    }

    /** Laedt Fahrer und Geist: erst die Modelle aus den Assets, sonst den eingebauten Fahrer. */
    private fun loadAvatars(c: FilamentCore): Unit? {
        if (rider == null) {
            val r = loadModel(c, WorldModels.RIDER, AvatarModel.RIDER) ?: run {
                fail(RenderFailure.BAD_ASSETS, "Darstellung, Stufe „${RenderStage.MODELS.label}“: kein Fahrermodell ladbar")
                return null
            }
            rider = r
        }
        if (ghost == null) {
            ghost = loadModel(c, WorldModels.GHOST, AvatarModel.GHOST)
            ghost?.setVisible(false)
        }
        if (cockpit == null) {
            // Cockpit der Lenkerperspektive (im Code gebaut); fehlt es, bleibt nur die Kamera.
            cockpit = runCatching { CockpitActor(LoadedAsset.loadSync(c, CockpitModel.buildGlb())) }
                .onFailure { Log.w(TAG, "Cockpit nicht ladbar", it) }
                .getOrNull()
            cockpit?.setVisible(false)
        }
        return Unit
    }

    private fun loadModel(c: FilamentCore, assetName: String, palette: AvatarModel.Palette): AvatarActor? {
        val fromAssets = runCatching {
            appContext.assets.open("${WorldModels.ASSET_DIR}/$assetName").use { it.readBytes() }
        }.getOrNull()
        if (fromAssets != null) {
            val actor = runCatching { actorOf(c, fromAssets) }
                .onFailure { Log.w(TAG, "Modell $assetName nicht ladbar, nehme den eingebauten Fahrer", it) }
                .getOrNull()
            if (actor != null) return actor
        }
        return runCatching { actorOf(c, AvatarModel.buildGlb(palette)) }
            .onFailure { Log.w(TAG, "Eingebauter Fahrer nicht ladbar", it) }
            .getOrNull()
    }

    private fun actorOf(c: FilamentCore, glb: ByteArray): AvatarActor {
        val loaded = LoadedAsset.loadSync(c, glb)
        return try {
            AvatarActor(loaded)
        } catch (t: Throwable) {
            runCatching { loaded.destroy() }
            throw t
        }
    }

    /** Raeumt die Welt ab (Kacheln, Testszene); Fahrer und Geist bleiben. */
    private fun clearScene() {
        streamer?.let { s -> runCatching { s.destroy() } }
        streamer = null
        staticScene?.let { s -> runCatching { s.destroy() } }
        staticScene = null
        track = null
        life?.setTrack(null)
    }

    private fun teardownFilament() {
        stopLoop()
        detach()
        clearScene()
        rider?.let { runCatching { it.destroy() } }
        life?.let { runCatching { it.destroy() } }
        life = null
        cockpit?.let { runCatching { it.destroy() } }
        cockpit = null
        ghost?.let { runCatching { it.destroy() } }
        rider = null
        ghost = null
        core?.let { runCatching { it.destroy() } }
        core = null
        appliedRequest = null
    }

    // =======================================================================
    // Bildschleife
    // =======================================================================

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!shouldRun()) return
            scheduleFrame()
            guardFrame(RenderStage.FRAME) { frame(frameTimeNanos) }
        }
    }

    private fun shouldRun(): Boolean =
        !destroyed && failure == null && core != null && uiHelper != null && !pausedByApi && !pausedBySurface

    private fun updateLoop() {
        if (shouldRun()) {
            scheduleFrame()
        } else {
            stopLoop()
        }
    }

    private fun scheduleFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (frameScheduled) {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            frameScheduled = false
        }
        lastFrameNs = 0L
        // Die Frist fuer „kein Bild" zaehlt nur, solange die Schleife laeuft: Nach einer
        // Pause (Hintergrund, Dialog) beginnt sie neu, ein einzelnes ausgelassenes Bild
        // beim Wiederanlauf ist kein Ausfall.
        noFrameSinceMs = 0L
        lastRenderedNs = 0L
        meter.reset()
        bench?.reset()
    }

    private fun frame(frameTimeNanos: Long) {
        val c = core ?: return
        // Bildratengrenze (30 Bilder je Sekunde): zu fruehe Bilder auslassen.
        // Obergrenze 30 oder 60 Bilder je Sekunde — auch auf 90/120-Hz-Anzeigen.
        val minIntervalNs = if (displayHz <= settings.maxFps + 1f) 0L else 1_000_000_000L / settings.maxFps - 4_000_000L
        if (lastRenderedNs != 0L && frameTimeNanos - lastRenderedNs < minIntervalNs) return

        val dt = if (lastFrameNs == 0L) 0.0 else (frameTimeNanos - lastFrameNs) / 1e9
        lastFrameNs = frameTimeNanos
        val nowMs = SystemClock.elapsedRealtime()

        val t = track
        if (t != null) {
            stage = RenderStage.FRAME
            val state = pendingState.get()
            if (state != null) {
                riderMotion.update(state.rider, state.timeMs, state.paused)
                state.ghost?.let { ghostMotion.update(it, state.timeMs, state.paused) }
            }
            riderMotion.advance(nowMs, dt)
            val ghostState = state?.ghost
            if (ghostState != null) ghostMotion.advance(nowMs, dt)

            // Schwebender Ursprung: weit weg vom Ursprung neu setzen (Float-Genauigkeit).
            val here = t.poseAt(riderMotion.distM)
            val riderWorld = here.position()
            if ((riderWorld - offset).length() > RECENTER_M) {
                offset = V3(Math.round(riderWorld.x / 256.0) * 256.0, 0.0, Math.round(riderWorld.z / 256.0) * 256.0)
                c.setWorldOffset(offset)
                c.applyFog(settings.fog, skyLighting?.fogDensity ?: 1e-4, skyLighting?.fogColor ?: Rgb(0.7, 0.75, 0.8), fogHeight())
            }

            // Dunsthoehe dem Fahrer nachfuehren (bei langen Anstiegen), alle 25 Hoehenmeter.
            if (kotlin.math.abs(here.u - fogBaseU) > 25.0) {
                fogBaseU = here.u
                c.applyFog(settings.fog, skyLighting?.fogDensity ?: 1e-4, skyLighting?.fogColor ?: Rgb(0.7, 0.75, 0.8), fogHeight())
            }

            // Fahrer
            val laneR = riderLane.step(laneOffsetM(widthAt(t, riderMotion.distM)), dt)
            rider?.let { a ->
                val p = here.lateral(laneR) - offset
                a.place(modelMatrix(p, here.headingDeg, here.gradePct))
                a.animate(riderMotion.crankPhase, riderMotion.wheelPhase)
            }
            // Geist
            ghost?.let { g ->
                if (ghostState != null) {
                    val gp = t.poseAt(ghostMotion.distM)
                    val laneG = ghostLane.step(-laneOffsetM(widthAt(t, ghostMotion.distM)), dt)
                    g.place(modelMatrix(gp.lateral(laneG) - offset, gp.headingDeg, gp.gradePct))
                    g.animate(ghostMotion.crankPhase, ghostMotion.wheelPhase)
                    g.setVisible(true)
                } else {
                    g.setVisible(false)
                }
            }

            // Andere Radfahrer und Voegel (rein dekorativ)
            life?.let { l ->
                stage = RenderStage.FRAME
                l.update(dt, riderMotion.distM, riderMotion.speedMps, laneR, riderWorld, headingForward(here.headingDeg), offset, settings, otherRidersEnabled)
            }

            // Kamera; am Lenker statt des Fahrers das Cockpit (die Kamera saesse sonst in seinem Kopf)
            val pose = rig.update(t, riderMotion.distM, laneR, dt, riderMotion.speedMps)
            c.handlebarView = cameraMode == CameraMode.HANDLEBAR
            c.lookAt(CameraPose(pose.eye - offset, pose.target - offset, pose.up))
            val cp = rig.cockpit
            rider?.setVisible(cp == null)
            cockpit?.let { k ->
                if (cp != null) {
                    val m = cp.matrix.copyOf()
                    m[12] -= offset.x.toFloat()
                    m[13] -= offset.y.toFloat()
                    m[14] -= offset.z.toFloat()
                    k.place(m)
                    k.steer(cp.steerRad)
                }
                k.setVisible(cp != null)
            }

            // Kacheln
            streamer?.let { s ->
                stage = RenderStage.TILES
                s.update(riderMotion.distM, settings)
                reportLoading(s)
            }
            if (staticScene != null && !readySent) sendReady()
        }
        // Ein Ausfall oben hat Filament schon abgebaut: dann nichts mehr anfassen.
        if (failure != null || core !== c) return

        stage = RenderStage.FRAME
        c.setMaterialTime(nowMs / 1000.0)
        val drawn = c.render(frameTimeNanos)
        if (drawn) {
            lastRenderedNs = frameTimeNanos
            noFrameSinceMs = 0L
            meter.frame(frameTimeNanos)?.let { (fps, p95) -> reportStats(fps, p95) }
            bench?.let { b ->
                b.frame(frameTimeNanos, gpuTimes.latestMs(c.renderer), c.lastDynamicResolutionScale(), c.viewportWidth, c.viewportHeight)
                b.result?.let { r ->
                    bench = null
                    val done = benchDone
                    benchDone = null
                    main.post { done?.invoke(r) }
                }
            }
        } else if (c.swapChain != null) {
            // Eine Flaeche ist da, aber seit langem kommt kein Bild durch: nicht schwarz schweigen.
            if (noFrameSinceMs == 0L) {
                noFrameSinceMs = nowMs
            } else if (nowMs - noFrameSinceMs > NO_FRAME_TIMEOUT_MS) {
                fail(RenderFailure.NO_GRAPHICS, "Darstellung, Stufe „${RenderStage.FRAME.label}“: seit ${NO_FRAME_TIMEOUT_MS / 1000} s kein Bild gezeichnet")
            }
        }
    }

    private fun widthAt(t: WorldTrack, distM: Double): Double {
        val d = t.fold(distM)
        return t.surfaces.firstOrNull { d >= it.fromM && d <= it.toM }?.effectiveWidthM ?: 3.0
    }

    private fun reportLoading(s: TileStreamer) {
        val d = riderMotion.distM
        val progress = s.progressAt(d)
        if (progress != lastProgress) {
            lastProgress = progress
            postListener { it.onLoading(progress.first, progress.second) }
        }
        if (!readySent) {
            if (s.readyAt(d)) {
                sendReady()
            } else if (s.allFailedAt(d)) {
                fail(RenderFailure.BAD_WORLD, "Darstellung, Stufe „${RenderStage.TILES.label}“: keine Kachel am Start ladbar")
            }
        }
    }

    private fun sendReady() {
        readySent = true
        val i = deviceInfo ?: emptyInfo()
        postListener { it.onReady(i) }
    }

    private fun reportStats(fps: Double, p95: Double) {
        val s = streamer
        val stats = RenderStats(
            fps = fps,
            frameMsP95 = p95,
            resolutionScale = settings.resolutionScale,
            loadedTiles = s?.readyCount ?: if (staticScene != null) 1 else 0,
            pendingTiles = s?.pendingCount ?: 0,
            textureBytes = s?.readyTextureBytes() ?: 0L,
            triangles = s?.readyTriangles() ?: 0,
        )
        lastStats = stats
        core?.let { c ->
            resolution = ResolutionInfo(
                c.viewportWidth, c.viewportHeight,
                if (settings.dynamicResolution) c.lastDynamicResolutionScale() else 1.0,
                settings.dynamicResolution,
            )
        }
        postListener { it.onStats(stats) }
    }

    // =======================================================================
    // Fehler
    // =======================================================================

    private var failureNote: String? = null

    /** Ausfall: Meldung, Filament abbauen, Ersatzansicht. Einmalig. */
    private fun fail(kind: RenderFailure, detail: String) {
        if (failure != null || destroyed) return
        Log.e(TAG, "Darstellung faellt aus ($kind): $detail")
        guard.disarm()
        failureNote = detail
        failure = kind
        runCatching { teardownFilament() }.onFailure { Log.w(TAG, "Abbau nach Ausfall", it) }
        postFailure(kind, detail)
    }

    private fun postFailure(kind: RenderFailure, detail: String) = postListener { it.onFailure(kind, detail) }

    /** Rueckrufe immer spaeter auf dem Hauptthread — nie mitten in einem Aufruf des Zuhoerers. */
    private fun postListener(block: (WorldRendererListener) -> Unit) {
        main.post {
            val l = listener ?: return@post
            try {
                block(l)
            } catch (t: Throwable) {
                Log.w(TAG, "Zuhoerer der Darstellung warf", t)
            }
        }
    }

    private inline fun guardApi(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail(classifyFailure(t, stage), failureDetail(stage, t) + " ($what)")
        }
    }

    private inline fun guardFrame(s: RenderStage, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail(classifyFailure(t, s), failureDetail(s, t))
        }
    }

    private inline fun <T> runStage(s: RenderStage, block: () -> T): T? {
        stage = s
        return try {
            block()
        } catch (t: Throwable) {
            fail(classifyFailure(t, s), failureDetail(s, t))
            null
        }
    }

    private fun emptyInfo() = RenderDeviceInfo(Build.MODEL ?: "", Build.VERSION.RELEASE ?: "", "", "", "", "", FilamentCore.FILAMENT_VERSION)

    private fun probeDevice(): RenderDeviceInfo {
        val gl = runCatching { GlProbe.query() }.getOrNull()
        return RenderDeviceInfo(
            deviceModel = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            backend = "",
            glVendor = gl?.vendor ?: "",
            glRenderer = gl?.renderer ?: "",
            glVersion = gl?.version ?: "",
            filamentVersion = FilamentCore.FILAMENT_VERSION,
        )
    }

    companion object {
        /** Verzeichnis der eigenen Filament-Materialien in den Assets. */
        const val MATERIAL_DIR = "indoor/materials"

        private const val TAG = "IndoorRender"
        const val RECENTER_M = 2_000.0
        const val NO_FRAME_TIMEOUT_MS = 8_000L
    }
}
