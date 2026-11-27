package de.trailscape.app.indoor.render

import android.content.Context
import android.os.Build
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.indoor.sky.SkyIrradiance
import de.trailscape.core.indoor.sky.SkyRgb
import de.trailscape.core.indoor.world.WorldModels
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * # Eigenstaendige 3D-Studio-Buehne fuer die Garage
 *
 * Zeichnet `rider.glb` (oder den eingebauten `AvatarModel`) auf einem runden
 * Podest in eine [TextureView], damit die Vorschau sauber in einer abgerundeten
 * Karte scrollt. Alles, was Filament beruehrt, bleibt streng auf dem Hauptthread
 * und wird beim Verlassen der Garage ueber [destroy] vollstaendig freigegeben.
 *
 * Scheitert das Erzeugen oder Zeichnen (z. B. fehlendes OpenGL ES 3.0 oder
 * Testumgebung ohne native Bibliotheken), setzt die Klasse [failed] = `true`,
 * raeumt auf und der Aufrufer zeigt die gezeichnete 2D-Vorschau.
 */
internal class GarageStageRenderer(
    context: Context,
    private val safeMode: Boolean,
) {
    private val appContext = context.applicationContext ?: context
    private val orbit = GarageOrbitCamera()

    var failed: Boolean by mutableStateOf(false)
        private set

    private var currentAvatar: RiderAvatar = RiderAvatar.DEFAULT
    private var darkTheme: Boolean = false
    private var paused: Boolean = false
    private var destroyed: Boolean = false

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var cameraEntity: Int = 0
    private var camera: Camera? = null
    private var keyLightEntity: Int = 0
    private var fillLightEntity: Int = 0
    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private var colorGrading: ColorGrading? = null
    private var materialProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var swapChain: SwapChain? = null

    private var uiHelper: UiHelper? = null
    private var textureView: TextureView? = null
    private var viewportW: Int = 0
    private var viewportH: Int = 0

    private var rawRiderGlb: ByteArray? = null
    private var riderStage: RiderStageAsset? = null
    private var pedestalAsset: FilamentAsset? = null
    private var appliedDarkTheme: Boolean? = null

    private var frameScheduled: Boolean = false
    private var lastFrameNs: Long = 0L
    private var crankPhase: Double = 0.0
    private var wheelPhase: Double = 0.0

    fun setAvatar(avatar: RiderAvatar) {
        if (destroyed || failed) return
        if (currentAvatar == avatar && riderStage != null) return
        currentAvatar = avatar
        val asset = riderStage ?: return
        if (safeMode) {
            // Sicherer Modus: nie Materialparameter zur Laufzeit setzen, sondern GLB neu laden.
            reloadRider()
        } else {
            runCatching { RiderTint.apply(asset.asset, avatar) }
                .onFailure { markFailed() }
        }
    }

    fun setDarkTheme(dark: Boolean) {
        if (destroyed || failed) return
        if (darkTheme == dark && appliedDarkTheme == dark) return
        darkTheme = dark
        if (engine != null && appliedDarkTheme != dark) {
            runCatching { applyStudioTheme(dark) }
                .onFailure { markFailed() }
        }
    }

    fun onDrag(deltaXDp: Float) {
        orbit.onDrag(deltaXDp)
    }

    fun resetCamera() {
        orbit.reset()
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        updateLoop()
    }

    fun attach(tv: TextureView): Boolean {
        if (destroyed || failed) return false
        return try {
            if (textureView === tv && uiHelper != null) return true
            detach()
            ensureEngine()
            val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
            helper.isOpaque = true
            helper.renderCallback = surfaceCallback
            uiHelper = helper
            textureView = tv
            refreshDisplayRate(tv)
            if (tv.width > 0 && tv.height > 0) {
                resizeViewport(tv.width, tv.height)
            }
            helper.attachTo(tv)
            updateLoop()
            true
        } catch (_: Throwable) {
            markFailed()
            false
        }
    }

    fun detach(tv: TextureView) {
        if (tv === textureView) detach()
    }

    fun detach() {
        stopLoop()
        runCatching {
            uiHelper?.let { h ->
                h.renderCallback = null
                h.detach()
            }
        }
        destroySwapChain(wait = true)
        uiHelper = null
        textureView = null
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        detach()
        teardownFilament()
    }

    // -----------------------------------------------------------------------
    // Aufbau & Szene
    // -----------------------------------------------------------------------

    private fun ensureEngine() {
        if (engine != null) return
        FilamentCore.ensureNativeLoaded()
        val eng = Engine.create(Engine.Backend.OPENGL)
        engine = eng
        val ren = eng.createRenderer()
        renderer = ren
        val scn = eng.createScene()
        scene = scn
        val vw = eng.createView()
        view = vw
        val camE = EntityManager.get().create()
        cameraEntity = camE
        val cam = eng.createCamera(camE)
        camera = cam
        vw.scene = scn
        vw.camera = cam
        cam.setExposure(SkyModel.APERTURE, SkyModel.SHUTTER_S, SkyModel.ISO)

        val cg = ColorGrading.Builder()
            .toneMapper(ToneMapper.PBRNeutralToneMapper())
            .build(eng)
        colorGrading = cg
        vw.colorGrading = cg
        applyViewQuality(vw)

        setupLights(eng, scn)

        val provider = UbershaderProvider(eng)
        materialProvider = provider
        assetLoader = AssetLoader(eng, provider, EntityManager.get())
        resourceLoader = ResourceLoader(eng)

        applyStudioTheme(darkTheme)
        loadRider()
    }

    private fun applyViewQuality(vw: View) {
        vw.setShadowingEnabled(true)
        vw.isPostProcessingEnabled = true
        runCatching {
            vw.setShadowType(View.ShadowType.PCF)
            vw.softShadowOptions = vw.softShadowOptions.apply {
                penumbraScale = 1.6f
                penumbraRatioScale = 1.0f
            }
        }
        val msaa = runCatching {
            vw.multiSampleAntiAliasingOptions = vw.multiSampleAntiAliasingOptions.apply {
                enabled = true
                sampleCount = 4
            }
        }.isSuccess
        vw.antiAliasing = if (msaa) View.AntiAliasing.NONE else View.AntiAliasing.FXAA
    }

    private fun setupLights(eng: Engine, scn: Scene) {
        val key = EntityManager.get().create()
        keyLightEntity = key
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(95_000f)
            .direction(-0.45f, -0.78f, -0.43f)
            .castShadows(true)
            .shadowOptions(RenderQuality.shadowOptions(cascades = 1, farM = 8.0, stable = true))
            .sunAngularRadius(2.0f)
            .build(eng, key)
        scn.addEntity(key)

        val fill = EntityManager.get().create()
        fillLightEntity = fill
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.90f, 0.94f, 1.0f)
            .intensity(28_000f)
            .direction(0.60f, -0.40f, 0.60f)
            .castShadows(false)
            .build(eng, fill)
        scn.addEntity(fill)

        val studioSh = SkyIrradiance.sh(
            zenith = SkyRgb(0.85, 0.88, 0.94),
            horizon = SkyRgb(0.75, 0.78, 0.82),
            ground = SkyRgb(0.32, 0.30, 0.28),
            steps = 32,
        )
        val ibl = IndirectLight.Builder()
            .radiance(3, studioSh)
            .intensity(26_000f)
            .build(eng)
        indirectLight = ibl
        scn.indirectLight = ibl
    }

    private fun applyStudioTheme(dark: Boolean) {
        val eng = engine ?: return
        val scn = scene ?: return
        val look = GarageStageModel.studioLook(dark)

        val oldSky = skybox
        val newSky = Skybox.Builder()
            .color(look.skyLinear[0], look.skyLinear[1], look.skyLinear[2], 1f)
            .build(eng)
        scn.skybox = newSky
        skybox = newSky
        oldSky?.let { runCatching { eng.destroySkybox(it) } }

        val oldPedestal = pedestalAsset
        val newPedestal = loadGlbAsset(GarageStageModel.buildPedestalGlb(dark))
        applyAssetShadows(eng, newPedestal, cast = false, receive = true)
        scn.addEntities(newPedestal.entities)
        pedestalAsset = newPedestal
        oldPedestal?.let { destroyGlbAsset(it) }

        appliedDarkTheme = dark
    }

    private fun loadRider() {
        val eng = engine ?: return
        val scn = scene ?: return
        val base = rawRiderGlb
            ?: (readRiderModel(appContext, WorldModels.RIDER) ?: AvatarModel.buildGlb()).also { rawRiderGlb = it }
        val bytes = RiderTint.prepare(base, currentAvatar, safeMode)
        val asset = loadGlbAsset(bytes)
        if (!safeMode) {
            RiderTint.apply(asset, currentAvatar)
        }
        applyAssetShadows(eng, asset, cast = true, receive = true)
        scn.addEntities(asset.entities)
        riderStage = RiderStageAsset(asset)
    }

    private fun reloadRider() {
        val old = riderStage
        riderStage = null
        old?.let { destroyGlbAsset(it.asset) }
        runCatching { loadRider() }
            .onFailure { markFailed() }
    }

    private fun loadGlbAsset(bytes: ByteArray): FilamentAsset {
        val loader = assetLoader ?: error("AssetLoader fehlt")
        val res = resourceLoader ?: error("ResourceLoader fehlt")
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            flip()
            GlbPatch.neutralizeSpecular(this)
        }
        val asset = loader.createAsset(buf) ?: error("gltfio konnte Garagenmodell nicht lesen")
        return try {
            res.loadResources(asset)
            asset.releaseSourceData()
            asset
        } catch (t: Throwable) {
            runCatching { loader.destroyAsset(asset) }
            throw t
        }
    }

    private fun destroyGlbAsset(asset: FilamentAsset) {
        runCatching { scene?.removeEntities(asset.entities) }
        runCatching { assetLoader?.destroyAsset(asset) }
    }

    private fun applyAssetShadows(eng: Engine, asset: FilamentAsset, cast: Boolean, receive: Boolean) {
        val rm = eng.renderableManager
        for (e in asset.renderableEntities) {
            val ri = rm.getInstance(e)
            if (ri != 0) {
                rm.setCastShadows(ri, cast)
                rm.setReceiveShadows(ri, receive)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Zeichenflaeche & Bildschleife
    // -----------------------------------------------------------------------

    private fun refreshDisplayRate(tv: TextureView) {
        val ren = renderer ?: return
        val hz = (if (Build.VERSION.SDK_INT >= 30) runCatching { tv.context.display.refreshRate }.getOrNull() else null)
            ?: runCatching {
                @Suppress("DEPRECATION")
                (tv.context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
            }.getOrNull()
        if (hz != null && hz > 1f) {
            ren.displayInfo = ren.displayInfo.apply { refreshRate = hz }
        }
    }

    private fun createSwapChain(surface: Surface, flags: Long) {
        val eng = engine ?: return
        destroySwapChain(wait = false)
        swapChain = eng.createSwapChain(surface, flags)
    }

    private fun destroySwapChain(wait: Boolean) {
        val eng = engine ?: return
        val sc = swapChain ?: return
        swapChain = null
        runCatching { eng.destroySwapChain(sc) }
        if (wait) runCatching { eng.flushAndWait() }
    }

    private fun resizeViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportW = width
        viewportH = height
        view?.viewport = Viewport(0, 0, width, height)
        val aspect = width.toDouble() / height.toDouble()
        camera?.setProjection(GarageOrbitCamera.FOV_DEG, aspect, NEAR_M, FAR_M, Camera.Fov.VERTICAL)
    }

    private val surfaceCallback = object : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            try {
                val h = uiHelper ?: return
                createSwapChain(surface, h.swapChainFlags)
                updateLoop()
            } catch (_: Throwable) {
                markFailed()
            }
        }

        override fun onDetachedFromSurface() {
            destroySwapChain(wait = true)
        }

        override fun onResized(width: Int, height: Int) {
            try {
                resizeViewport(width, height)
            } catch (_: Throwable) {
                markFailed()
            }
        }
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameScheduled = false
        if (destroyed || failed || paused || swapChain == null) return@FrameCallback
        try {
            stepAndRender(frameTimeNanos)
            scheduleNextFrame()
        } catch (_: Throwable) {
            markFailed()
        }
    }

    private fun updateLoop() {
        if (!destroyed && !failed && !paused && swapChain != null) {
            scheduleNextFrame()
        } else {
            stopLoop()
        }
    }

    private fun scheduleNextFrame() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!frameScheduled) return
        frameScheduled = false
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
    }

    private fun stepAndRender(frameTimeNanos: Long) {
        val ren = renderer ?: return
        val vw = view ?: return
        val sc = swapChain ?: return
        val cam = camera ?: return
        if (viewportW <= 0 || viewportH <= 0) return

        val dt = if (lastFrameNs == 0L) 0.016 else ((frameTimeNanos - lastFrameNs) / 1_000_000_000.0).coerceIn(0.0, 0.05)
        lastFrameNs = frameTimeNanos

        orbit.tick(dt)
        crankPhase = (crankPhase + dt * PEDAL_REV_PER_SEC) % 1.0
        wheelPhase = (wheelPhase + dt * WHEEL_REV_PER_SEC) % 1.0
        riderStage?.animate(crankPhase, wheelPhase)

        val pose = orbit.pose(viewportW.toDouble() / viewportH.toDouble())
        cam.lookAt(pose.eye.x, pose.eye.y, pose.eye.z, pose.target.x, pose.target.y, pose.target.z, pose.up.x, pose.up.y, pose.up.z)

        if (ren.beginFrame(sc, frameTimeNanos)) {
            ren.render(vw)
            ren.endFrame()
        }
    }

    private fun markFailed() {
        if (failed) return
        failed = true
        detach()
        teardownFilament()
    }

    private fun teardownFilament() {
        val eng = engine ?: return
        engine = null
        riderStage?.let { destroyGlbAsset(it.asset) }
        riderStage = null
        pedestalAsset?.let { destroyGlbAsset(it) }
        pedestalAsset = null
        runCatching { scene?.skybox = null }
        runCatching { scene?.indirectLight = null }
        skybox?.let { runCatching { eng.destroySkybox(it) } }
        skybox = null
        indirectLight?.let { runCatching { eng.destroyIndirectLight(it) } }
        indirectLight = null
        if (keyLightEntity != 0) {
            runCatching { scene?.removeEntity(keyLightEntity) }
            runCatching { eng.lightManager.destroy(keyLightEntity) }
            runCatching { EntityManager.get().destroy(keyLightEntity) }
            keyLightEntity = 0
        }
        if (fillLightEntity != 0) {
            runCatching { scene?.removeEntity(fillLightEntity) }
            runCatching { eng.lightManager.destroy(fillLightEntity) }
            runCatching { EntityManager.get().destroy(fillLightEntity) }
            fillLightEntity = 0
        }
        colorGrading?.let { runCatching { eng.destroyColorGrading(it) } }
        colorGrading = null
        runCatching { resourceLoader?.destroy() }
        resourceLoader = null
        runCatching { assetLoader?.destroy() }
        assetLoader = null
        materialProvider?.let { mp ->
            runCatching { mp.destroyMaterials() }
            runCatching { mp.destroy() }
        }
        materialProvider = null
        if (cameraEntity != 0) {
            runCatching { eng.destroyCameraComponent(cameraEntity) }
            runCatching { EntityManager.get().destroy(cameraEntity) }
            cameraEntity = 0
        }
        camera = null
        view?.let { runCatching { eng.destroyView(it) } }
        view = null
        scene?.let { runCatching { eng.destroyScene(it) } }
        scene = null
        renderer?.let { runCatching { eng.destroyRenderer(it) } }
        renderer = null
        runCatching { eng.destroy() }
    }

    private class RiderStageAsset(val asset: FilamentAsset) {
        private val animator: Animator? = runCatching { asset.instance.animator }.getOrNull()
        private val pedalIndex: Int
        private val rollIndex: Int
        private val pedalDuration: Float
        private val rollDuration: Float

        init {
            var p = -1
            var r = -1
            val a = animator
            if (a != null) {
                val count = runCatching { a.animationCount }.getOrDefault(0)
                for (i in 0 until count) {
                    when (runCatching { a.getAnimationName(i) }.getOrNull()) {
                        WorldModels.ANIM_PEDAL -> p = i
                        WorldModels.ANIM_ROLL -> r = i
                    }
                }
            }
            pedalIndex = p
            rollIndex = r
            pedalDuration = if (p >= 0) runCatching { a!!.getAnimationDuration(p) }.getOrDefault(1f) else 1f
            rollDuration = if (r >= 0) runCatching { a!!.getAnimationDuration(r) }.getOrDefault(1f) else 1f
        }

        fun animate(crankPhase: Double, wheelPhase: Double) {
            val a = animator ?: return
            if (pedalIndex >= 0) a.applyAnimation(pedalIndex, (crankPhase * pedalDuration).toFloat().coerceIn(0f, pedalDuration))
            if (rollIndex >= 0) a.applyAnimation(rollIndex, (wheelPhase * rollDuration).toFloat().coerceIn(0f, rollDuration))
            if (pedalIndex >= 0 || rollIndex >= 0) a.updateBoneMatrices()
        }
    }

    private companion object {
        const val NEAR_M = 0.15
        const val FAR_M = 25.0
        const val PEDAL_REV_PER_SEC = 0.90
        const val WHEEL_REV_PER_SEC = 2.80
    }
}
