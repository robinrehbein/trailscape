package de.trailscape.app.indoor.render

import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import de.trailscape.core.indoor.world.RenderSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * # Die Filament-Teile einer Darstellung
 *
 * Engine, Renderer, Szene, View, Kamera, Sonne, Umgebungslicht, Himmel und
 * die gltfio-Lader. **Nur auf dem Hauptthread** benutzen — dort entsteht die
 * Engine, dort laeuft die Choreographer-Schleife, dort wird zerstoert
 * (Filament verlangt einen Thread fuer alle Aufrufe an eine Engine).
 *
 * Der Konstruktor wirft, wenn Filament nicht startet (fehlende native
 * Bibliothek, kein OpenGL ES 3.0); der Aufrufer faengt das und meldet
 * `NO_GRAPHICS`. Halb gebaute Teile raeumt er mit [destroy] ab, das jeden
 * Schritt einzeln absichert.
 */
internal class FilamentCore {
    val engine: Engine
    val renderer: Renderer
    val scene: Scene
    val view: View
    val cameraEntity: Int
    val camera: Camera
    val sunEntity: Int
    val worldRoot: Int
    private val materialProvider: UbershaderProvider
    val assetLoader: AssetLoader
    var resourceLoader: ResourceLoader
        private set

    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private var skyTexture: Texture? = null
    var swapChain: SwapChain? = null
        private set

    private var viewportW = 0
    private var viewportH = 0
    private var destroyed = false

    init {
        ensureNativeLoaded()
        engine = Engine.create(Engine.Backend.OPENGL)
        try {
            renderer = engine.createRenderer()
            scene = engine.createScene()
            view = engine.createView()
            cameraEntity = EntityManager.get().create()
            camera = engine.createCamera(cameraEntity)
            view.scene = scene
            view.camera = camera
            camera.setExposure(SkyModel.APERTURE, SkyModel.SHUTTER_S, SkyModel.ISO)

            sunEntity = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.SUN)
                .color(1f, 0.97f, 0.92f)
                .intensity(100_000f)
                .direction(0f, -1f, 0f)
                .castShadows(true)
                .shadowOptions(
                    LightManager.ShadowOptions().apply {
                        // 2048² je Kaskade, Grenzen bei rund 15 m und 60 m: scharfe, ruhige
                        // Schatten nah am Fahrer statt Treppenkanten (vorher 1024², Filament-Standardgrenzen).
                        mapSize = SHADOW_MAP_PX
                        shadowCascades = 3
                        cascadeSplitPositions = floatArrayOf(0.05f, 0.2f)
                        // Vertrag 7.6: Schattenreichweite hoechstens rund 300 m.
                        shadowFar = SHADOW_FAR_M
                        shadowFarHint = SHADOW_FAR_M
                        shadowNearHint = 1f
                        stable = false
                    },
                )
                .sunAngularRadius(1.2f)
                .sunHaloSize(8f)
                .sunHaloFalloff(80f)
                .build(engine, sunEntity)
            scene.addEntity(sunEntity)

            worldRoot = EntityManager.get().create()
            engine.transformManager.create(worldRoot)

            materialProvider = UbershaderProvider(engine)
            assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
            resourceLoader = ResourceLoader(engine)
        } catch (t: Throwable) {
            runCatching { engine.destroy() }
            throw t
        }
    }

    val backendName: String get() = runCatching { engine.backend.name }.getOrDefault("")

    // -----------------------------------------------------------------------
    // Zeichenflaeche
    // -----------------------------------------------------------------------

    fun createSwapChain(surface: Any, flags: Long) {
        destroySwapChain(wait = false)
        swapChain = engine.createSwapChain(surface, flags)
    }

    /** Gibt die Swapchain frei; [wait] = auf Filament warten, bevor Android die Flaeche abbaut. */
    fun destroySwapChain(wait: Boolean) {
        val sc = swapChain ?: return
        swapChain = null
        runCatching { engine.destroySwapChain(sc) }
        if (wait) runCatching { engine.flushAndWait() }
    }

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewportW = width
        viewportH = height
        view.viewport = Viewport(0, 0, width, height)
        updateProjection()
    }

    private fun updateProjection() {
        if (viewportW <= 0 || viewportH <= 0) return
        val aspect = viewportW.toDouble() / viewportH
        // Hochformat: waagrecht genug sehen — senkrechter Bildwinkel groesser.
        val fov = if (aspect < 1.0) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG
        camera.setProjection(fov, aspect, NEAR_M, FAR_M, Camera.Fov.VERTICAL)
    }

    /** Zeichnet ein Bild. `false`, wenn nichts gezeichnet wurde (keine Swapchain, GPU im Rueckstand). */
    fun render(frameTimeNanos: Long): Boolean {
        val sc = swapChain ?: return false
        if (viewportW <= 0) return false
        if (!renderer.beginFrame(sc, frameTimeNanos)) return false
        renderer.render(view)
        renderer.endFrame()
        return true
    }

    // -----------------------------------------------------------------------
    // Einstellungen, Licht, Himmel
    // -----------------------------------------------------------------------

    fun applySettings(s: RenderSettings, fogDensity: Double, fogColor: Rgb, fogHeight: Double) {
        view.setShadowingEnabled(s.shadows)
        engine.lightManager.getInstance(sunEntity).takeIf { it != 0 }?.let {
            engine.lightManager.setShadowCaster(it, s.shadows)
        }
        view.isPostProcessingEnabled = s.postProcessing
        applyAntiAliasing(s.antiAliasing)
        // Weiche Schattenkanten (DPCF) statt hartem PCF; faellt einzeln auf PCF zurueck.
        runCatching {
            view.setShadowType(if (s.shadows) View.ShadowType.DPCF else View.ShadowType.PCF)
            view.softShadowOptions = view.softShadowOptions.apply {
                penumbraScale = 1.5f
                penumbraRatioScale = 1.0f
            }
        }.onFailure { runCatching { view.setShadowType(View.ShadowType.PCF) } }
        view.dithering = if (s.postProcessing) View.Dithering.TEMPORAL else View.Dithering.NONE
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = s.ambientOcclusion
            radius = 0.4f
            intensity = 1.0f
        }
        view.bloomOptions = view.bloomOptions.apply {
            enabled = s.bloom
            strength = 0.08f
        }
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = s.dynamicResolution
            minScale = 0.6f
            maxScale = 1.0f
        }
        applyFog(s.fog, fogDensity, fogColor, fogHeight)
        val hz = runCatching { renderer.displayInfo.refreshRate }.getOrDefault(60f).takeIf { it > 1f } ?: 60f
        renderer.frameRateOptions = renderer.frameRateOptions.apply {
            // In Einheiten der Anzeigerate: bei 60 Hz und 30 Bildern ist das 2 (fuer die dynamische Aufloesung).
            interval = (hz / s.maxFps.toFloat()).coerceAtLeast(1f)
        }
    }

    /**
     * Kantenglaettung: 4× MSAA (auf Kachel-GPUs guenstig, glaettet Geometriekanten von
     * Baeumen, Daechern, Fahrbahnraendern), dazu FXAA nur, wenn MSAA nicht geht.
     * Jeder Schritt einzeln abgesichert — ein Fehler hier kostet nur die Glaettung.
     */
    private fun applyAntiAliasing(enabled: Boolean) {
        val msaa = enabled && runCatching {
            view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply {
                this.enabled = true
                sampleCount = MSAA_SAMPLES
            }
        }.isSuccess
        if (!enabled) {
            runCatching {
                view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { this.enabled = false }
            }
        }
        view.antiAliasing = if (enabled && !msaa) View.AntiAliasing.FXAA else View.AntiAliasing.NONE
    }

    fun applyFog(enabled: Boolean, density: Double, color: Rgb, height: Double) {
        view.fogOptions = view.fogOptions.apply {
            this.enabled = enabled
            this.density = density.toFloat()
            this.color = color.toFloatArray()
            this.height = height.toFloat()
            heightFalloff = FOG_HEIGHT_FALLOFF
            distance = 30f
            maximumOpacity = 0.92f
            // Die Skybox liegt sehr weit weg: dort keinen Dunst (Vertrag 7.7 Punkt 5).
            cutOffDistance = FAR_M.toFloat() * 4f
            fogColorFromIbl = false
        }
    }

    /**
     * Sonne, Umgebungslicht und Himmel fuer [l]. Mit [skyGradient] eine zur
     * Laufzeit gefuellte Cubemap (Verlauf, Sonnenscheibe zeichnet Filament),
     * sonst eine einfarbige Skybox in der Horizontfarbe.
     */
    fun applySky(l: SkyLighting, skyGradient: Boolean) {
        val lm = engine.lightManager
        val li = lm.getInstance(sunEntity)
        if (li != 0) {
            val d = l.sunDirection * -1.0 // Licht faellt von der Sonne her
            lm.setDirection(li, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
            lm.setColor(li, l.sunColor.r.toFloat(), l.sunColor.g.toFloat(), l.sunColor.b.toFloat())
            lm.setIntensity(li, l.sunLux.toFloat())
        }

        val newIbl = IndirectLight.Builder()
            .radiance(3, SkyModel.radianceSh(l))
            .intensity(l.ambientLux.toFloat())
            .build(engine)
        scene.indirectLight = newIbl
        indirectLight?.let { old -> runCatching { engine.destroyIndirectLight(old) } }
        indirectLight = newIbl

        val oldSky = skybox
        val oldTex = skyTexture
        var tex: Texture? = null
        val newSky = if (skyGradient) {
            tex = buildSkyTexture(l)
            Skybox.Builder().environment(tex).showSun(true).build(engine)
        } else {
            // Die einfarbige Skybox wird (anders als Cubemap und Dunst) nicht mit der
            // Staerke des Umgebungslichts multipliziert: selbst skalieren, damit Himmel und Dunst gleich hell sind.
            val k = (l.ambientLux * exposure()).toFloat()
            Skybox.Builder().color(l.horizon.r.toFloat() * k, l.horizon.g.toFloat() * k, l.horizon.b.toFloat() * k, 1f).build(engine)
        }
        scene.skybox = newSky
        skybox = newSky
        skyTexture = tex
        oldSky?.let { runCatching { engine.destroySkybox(it) } }
        oldTex?.let { runCatching { engine.destroyTexture(it) } }
    }

    /** Belichtung der Kamera: `1 / (1,2 · 2^EV100)` mit EV100 = log2(N² / t · 100 / ISO). */
    private fun exposure(): Double {
        val n = SkyModel.APERTURE.toDouble()
        val t = SkyModel.SHUTTER_S.toDouble()
        val ev100 = Math.log(n * n / t * 100.0 / SkyModel.ISO) / Math.log(2.0)
        return 1.0 / (1.2 * Math.pow(2.0, ev100))
    }

    private fun buildSkyTexture(l: SkyLighting): Texture {
        val size = SKY_CUBE_PX
        val tex = Texture.Builder()
            .width(size)
            .height(size)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
        try {
            val pixels = SkyModel.cubemapPixels(l, size)
            val buf = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder())
            buf.put(pixels)
            buf.flip()
            // Cubemaps gelten als 2D-Feld mit sechs Ebenen (+X, −X, +Y, −Y, +Z, −Z).
            tex.setImage(
                engine, 0, 0, 0, 0, size, size, 6,
                Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
            )
        } catch (t: Throwable) {
            runCatching { engine.destroyTexture(tex) }
            throw t
        }
        return tex
    }

    /**
     * Bricht eine laufende asynchrone Ladung ab und ersetzt den `ResourceLoader`.
     * Noetig, weil `StbProvider::cancelDecoding` abgebrochene Texturen nicht
     * mitzaehlt: Nach einem Abbruch erreicht `asyncGetLoadProgress` nie
     * wieder 1 (gltfio 1.75.1, StbProvider.cpp). Ein frischer Lader zaehlt neu.
     */
    fun resetResourceLoader() {
        val old = resourceLoader
        runCatching { old.asyncCancelLoad() }
        runCatching { old.destroy() }
        resourceLoader = ResourceLoader(engine)
    }

    /** Bildwiederholrate der Anzeige fuer Filaments Taktung (`FrameRateOptions.interval` zaehlt in deren Einheiten). */
    fun setDisplayRefreshRate(hz: Float) {
        if (!(hz > 1f)) return
        renderer.displayInfo = renderer.displayInfo.apply { refreshRate = hz }
    }

    /** Setzt die Verschiebung des Weltwurzelknotens (schwebender Ursprung). */
    fun setWorldOffset(o: V3) {
        val tm = engine.transformManager
        val i = tm.getInstance(worldRoot)
        if (i != 0) tm.setTransform(i, translationMatrix(-o.x, -o.y, -o.z))
    }

    fun lookAt(p: CameraPose) {
        camera.lookAt(p.eye.x, p.eye.y, p.eye.z, p.target.x, p.target.y, p.target.z, p.up.x, p.up.y, p.up.z)
    }

    // -----------------------------------------------------------------------
    // Assets
    // -----------------------------------------------------------------------

    /** Fuegt die Entities eines fertig geladenen Assets der Szene hinzu. */
    fun show(asset: FilamentAsset) {
        scene.addEntities(asset.entities)
    }

    fun hide(asset: FilamentAsset) {
        scene.removeEntities(asset.entities)
    }

    fun destroyAsset(asset: FilamentAsset) {
        runCatching { scene.removeEntities(asset.entities) }
        runCatching { assetLoader.destroyAsset(asset) }
    }

    /** Gibt alles frei. Jeder Schritt einzeln abgesichert; danach ist das Objekt verbraucht. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        destroySwapChain(wait = true)
        runCatching { scene.skybox = null }
        runCatching { scene.indirectLight = null }
        skybox?.let { runCatching { engine.destroySkybox(it) } }
        skyTexture?.let { runCatching { engine.destroyTexture(it) } }
        indirectLight?.let { runCatching { engine.destroyIndirectLight(it) } }
        runCatching { resourceLoader.asyncCancelLoad() }
        runCatching { resourceLoader.destroy() }
        runCatching { assetLoader.destroy() }
        runCatching { materialProvider.destroyMaterials() }
        runCatching { materialProvider.destroy() }
        runCatching { engine.lightManager.destroy(sunEntity) }
        runCatching { engine.transformManager.destroy(worldRoot) }
        runCatching { engine.destroyCameraComponent(cameraEntity) }
        runCatching { engine.destroyView(view) }
        runCatching { engine.destroyScene(scene) }
        runCatching { engine.destroyRenderer(renderer) }
        runCatching { EntityManager.get().destroy(sunEntity) }
        runCatching { EntityManager.get().destroy(worldRoot) }
        runCatching { EntityManager.get().destroy(cameraEntity) }
        runCatching { engine.destroy() }
    }

    companion object {
        // 0,5 statt 0,3 m: Tiefenauflösung in der Ferne fast doppelt so gut (weniger Flimmern von
        // Strasse auf Gelände); die Lenkerkamera sitzt 1,45 m hoch, nichts kommt näher.
        const val NEAR_M = 0.5
        const val FAR_M = 25_000.0
        const val FOV_LANDSCAPE_DEG = 50.0
        const val FOV_PORTRAIT_DEG = 72.0
        const val SHADOW_FAR_M = 300f
        const val SHADOW_MAP_PX = 2048
        const val MSAA_SAMPLES = 4
        const val FOG_HEIGHT_FALLOFF = 0.0012f
        const val SKY_CUBE_PX = 64

        /** Stand der Filament-Bibliotheken (app/build.gradle.kts). */
        const val FILAMENT_VERSION = "1.75.1"

        @Volatile
        private var nativeLoaded = false

        /** Laedt die nativen Bibliotheken einmal je Prozess (Filament, dann gltfio). Wirft bei Fehler. */
        @Synchronized
        fun ensureNativeLoaded() {
            if (nativeLoaded) return
            Gltfio.init()
            nativeLoaded = true
        }
    }
}
