package de.trailscape.app.indoor.render

import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import de.trailscape.core.indoor.sky.SkyClouds
import de.trailscape.core.indoor.sky.SkyShading
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

    /** Eigene Materialien (Dateien `.filamat` in `assets/indoor/materials`), vom Renderer gesetzt; leer = Filaments Skybox. */
    var materialPackages: Map<String, ByteArray> = emptyMap()
    private var skyMaterial: Material? = null
    private var skyInstance: MaterialInstance? = null
    private var skyEntity = 0
    private var skyVertices: VertexBuffer? = null
    private var skyIndices: IndexBuffer? = null
    private var skyMaterialShown = false
    private var skyMaterialFailed = false
    private var cloudTexture: Texture? = null
    private var waterMaterial: Material? = null
    private var waterMaterialFailed = false

    /** Das gemeinsame Wassermaterial fuer die Knoten `water` der Kacheln (`null`: Material der Kachel lassen). */
    var waterInstance: MaterialInstance? = null
        private set
    private var colorGrading: ColorGrading? = null
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

    /** Lenkerperspektive: eigener Bildwinkel, nahe Ebene und Verschiebung des Bildes (siehe [projectionFor]). */
    var handlebarView: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateProjection()
            }
        }

    private fun updateProjection() {
        if (viewportW <= 0 || viewportH <= 0) return
        val aspect = viewportW.toDouble() / viewportH
        val p = projectionFor(aspect, handlebarView)
        camera.setProjection(p.fovDeg, aspect, p.near, FAR_M, Camera.Fov.VERTICAL)
        runCatching { camera.setShift(0.0, p.shiftY) }
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
            inScatteringStart = fogInScatteringStart
            inScatteringSize = fogInScatteringSize
        }
    }

    /** Sonnenlicht im Dunst (`FogOptions.inScattering…`) aus der Stimmung; vor [applyFog] gesetzt. */
    private var fogInScatteringStart = 0f
    private var fogInScatteringSize = -1f

    fun setFogInScattering(start: Double, size: Double) {
        fogInScatteringStart = start.toFloat()
        fogInScatteringSize = size.toFloat()
    }

    /**
     * Tonwertabbildung PBR Neutral statt Filaments `ACES_LEGACY` (bleichte helle Felder und
     * Himmel weiss) mit Kontrast und Lebendigkeit der Stimmung. Ein Fehler kostet nur die
     * Farbgebung (Filament bleibt beim Standard).
     */
    private fun applyGrading(l: SkyLighting) {
        val g = l.daylight.grade
        runCatching {
            val cg = ColorGrading.Builder()
                .toneMapper(ToneMapper.PBRNeutralToneMapper())
                .contrast(g.contrast.toFloat())
                .vibrance(g.vibrance.toFloat())
                .saturation(g.saturation.toFloat())
                .build(engine)
            view.colorGrading = cg
            colorGrading?.let { old -> runCatching { engine.destroyColorGrading(old) } }
            colorGrading = cg
        }
    }

    /** Laedt einmal die Wolken-/Wellentextur (R8, kachelbar, mit Mipmaps). */
    private fun ensureCloudTexture(): Texture {
        cloudTexture?.let { return it }
        val n = SkyClouds.CLOUD_TEX_SIZE
        val px = SkyClouds.cloudTexture(n)
        val tex = Texture.Builder()
            .width(n).height(n).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.R8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        try {
            val b = ByteBuffer.allocateDirect(px.size).order(ByteOrder.nativeOrder())
            b.put(px)
            b.flip()
            tex.setImage(engine, 0, Texture.PixelBufferDescriptor(b, Texture.Format.R, Texture.Type.UBYTE))
            tex.generateMipmaps(engine)
        } catch (t: Throwable) {
            runCatching { engine.destroyTexture(tex) }
            throw t
        }
        cloudTexture = tex
        return tex
    }

    private val cloudSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    )

    /**
     * Zeigt das Himmelsmaterial (Vollbild-Dreieck auf der fernen Ebene wie Filaments
     * Skybox) mit den Werten von [l]. `false`, wenn es fehlt oder nicht geht — dann
     * nimmt [applySky] die Cubemap. Einmal gescheitert, wird es nicht wieder versucht.
     */
    private fun showSkyMaterial(l: SkyLighting): Boolean {
        if (skyMaterialFailed) return false
        val bytes = materialPackages[SKY_MATERIAL] ?: return false
        return runCatching {
            val mi = skyInstance ?: run {
                val m = buildMaterial(bytes).also { skyMaterial = it }
                val inst = m.createInstance()
                inst.setParameter("clouds", ensureCloudTexture(), cloudSampler)
                skyInstance = inst
                buildSkyRenderable(inst)
                inst
            }
            setParameters(mi, SkyShading.sky(l.daylight))
            if (!skyMaterialShown) {
                scene.addEntity(skyEntity)
                skyMaterialShown = true
            }
            true
        }.getOrElse {
            skyMaterialFailed = true
            hideSkyMaterial()
            false
        }
    }

    private fun hideSkyMaterial() {
        if (skyMaterialShown) {
            runCatching { scene.removeEntity(skyEntity) }
            skyMaterialShown = false
        }
    }

    private fun buildMaterial(bytes: ByteArray): Material {
        val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        b.put(bytes)
        b.flip()
        return Material.Builder().payload(b, bytes.size).build(engine)
    }

    private fun buildSkyRenderable(mi: MaterialInstance) {
        // Ein Dreieck, das den ganzen Bildschirm deckt, in Geraetekoordinaten (z = 1 → ferne Ebene).
        val verts = floatArrayOf(-1f, -1f, 1f, 1f, 3f, -1f, 1f, 1f, -1f, 3f, 1f, 1f)
        val vb = VertexBuffer.Builder()
            .vertexCount(3)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT4, 0, 16)
            .build(engine)
        val vbuf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        vbuf.asFloatBuffer().put(verts)
        vb.setBufferAt(engine, 0, vbuf)
        val ib = IndexBuffer.Builder().indexCount(3).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        val ibuf = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder())
        ibuf.asShortBuffer().put(shortArrayOf(0, 1, 2))
        ib.setBuffer(engine, ibuf)
        skyVertices = vb
        skyIndices = ib
        skyEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib)
            .material(0, mi)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(7)
            .build(engine, skyEntity)
    }

    /** Wassermaterial einmal laden; `null`, wenn es fehlt oder nicht geht (Kacheln behalten ihr Material). */
    fun ensureWaterInstance(): MaterialInstance? {
        waterInstance?.let { return it }
        if (waterMaterialFailed) return null
        val bytes = materialPackages[WATER_MATERIAL] ?: return null
        return runCatching {
            val m = buildMaterial(bytes).also { waterMaterial = it }
            val mi = m.createInstance()
            mi.setParameter("waves", ensureCloudTexture(), cloudSampler)
            waterInstance = mi
            lastLighting?.let { applyWaterParameters(it) }
            mi
        }.getOrElse {
            waterMaterialFailed = true
            null
        }
    }

    private var lastLighting: SkyLighting? = null

    private fun applyWaterParameters(l: SkyLighting) {
        lastLighting = l
        val mi = waterInstance ?: return
        runCatching { setParameters(mi, SkyShading.water(l.daylight)) }
    }

    /** Uhr der Materialien (Wolkenzug, Wellen) in Sekunden; je Bild. */
    fun setMaterialTime(seconds: Double) {
        val t = (seconds % MATERIAL_TIME_WRAP_S).toFloat()
        skyInstance?.let { if (skyMaterialShown) runCatching { it.setParameter("time", t) } }
        waterInstance?.let { runCatching { it.setParameter("time", t) } }
    }

    private fun setParameters(mi: MaterialInstance, params: Map<String, Any>) {
        for ((k, v) in params) {
            when (v) {
                is Double -> mi.setParameter(k, v.toFloat())
                is DoubleArray -> when (v.size) {
                    2 -> mi.setParameter(k, v[0].toFloat(), v[1].toFloat())
                    3 -> mi.setParameter(k, v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
                }
            }
        }
    }

    /**
     * Sonne, Umgebungslicht und Himmel fuer [l]. Mit [skyGradient] eine zur
     * Laufzeit gefuellte Cubemap (Verlauf und Wolken; die Sonnenscheibe samt
     * Hof zeichnet Filament), sonst eine einfarbige Skybox in der Horizontfarbe.
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

        // Belichtung je Tageszeit (gemessen, docs/indoor/render.md „Licht und Farbe")
        runCatching { camera.setExposure(SkyModel.APERTURE, l.shutterS, SkyModel.ISO) }
        applyGrading(l)

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
        // Eigenes Himmelsmaterial (Verlauf, Hof, ziehende Wolken); ohne es wie bisher die Cubemap.
        if (skyGradient && showSkyMaterial(l)) {
            scene.skybox = null
            skybox = null
            skyTexture = null
            oldSky?.let { runCatching { engine.destroySkybox(it) } }
            oldTex?.let { runCatching { engine.destroyTexture(it) } }
            applyWaterParameters(l)
            return
        }
        hideSkyMaterial()
        applyWaterParameters(l)
        val newSky = if (skyGradient) {
            tex = buildSkyTexture(l)
            Skybox.Builder().environment(tex).showSun(true).build(engine)
        } else {
            // Die einfarbige Skybox wird (anders als Cubemap und Dunst) nicht mit der
            // Staerke des Umgebungslichts multipliziert: den sichtbaren Horizont (schon
            // szenenlinear, nach der Belichtung) direkt nehmen, dann passt er zum Dunst.
            val h = l.look.horizon
            Skybox.Builder().color(h.r.toFloat(), h.g.toFloat(), h.b.toFloat(), 1f).build(engine)
        }
        scene.skybox = newSky
        skybox = newSky
        skyTexture = tex
        oldSky?.let { runCatching { engine.destroySkybox(it) } }
        oldTex?.let { runCatching { engine.destroyTexture(it) } }
    }

    /**
     * Himmels-Cubemap mit Verlauf und weichen Wolken. Zuerst als HDR-Textur
     * (`R11F_G11F_B10F`, in OpenGL ES 3.0 filterbar, 4 Byte je Texel): Horizont
     * und Wolken sind heller als 1 und wuerden in RGBA8 abgeschnitten. Geht
     * das nicht, dieselben Farben als `SRGB8_A8` (abgeschnitten, aber sichtbar).
     */
    private fun buildSkyTexture(l: SkyLighting): Texture {
        val size = SKY_CUBE_PX
        val half = SkyModel.cubemapHalfRgb(l, size)
        val hdr = runCatching { Texture.isTextureFormatSupported(engine, Texture.InternalFormat.R11F_G11F_B10F) }.getOrDefault(false)
        return runCatching {
            check(hdr) { "R11F_G11F_B10F nicht unterstuetzt" }
            uploadCube(size, Texture.InternalFormat.R11F_G11F_B10F) {
                val b = ByteBuffer.allocateDirect(half.size * 2).order(ByteOrder.nativeOrder())
                b.asShortBuffer().put(half) // Position des ByteBuffers bleibt 0
                Texture.PixelBufferDescriptor(b, Texture.Format.RGB, Texture.Type.HALF)
            }
        }.getOrElse {
            uploadCube(size, Texture.InternalFormat.SRGB8_A8) {
                val px = SkyModel.cubemapSrgbBytes(half)
                val b = ByteBuffer.allocateDirect(px.size).order(ByteOrder.nativeOrder())
                b.put(px)
                b.flip()
                Texture.PixelBufferDescriptor(b, Texture.Format.RGBA, Texture.Type.UBYTE)
            }
        }
    }

    private fun uploadCube(size: Int, format: Texture.InternalFormat, pixels: () -> Texture.PixelBufferDescriptor): Texture {
        val tex = Texture.Builder()
            .width(size)
            .height(size)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(format)
            .build(engine)
        try {
            // Cubemaps gelten als 2D-Feld mit sechs Ebenen (+X, −X, +Y, −Y, +Z, −Z).
            tex.setImage(engine, 0, 0, 0, 0, size, size, 6, pixels())
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
        if (skyEntity != 0) {
            runCatching { scene.removeEntity(skyEntity) }
            runCatching { engine.destroyEntity(skyEntity) }
            runCatching { EntityManager.get().destroy(skyEntity) }
        }
        skyInstance?.let { runCatching { engine.destroyMaterialInstance(it) } }
        skyMaterial?.let { runCatching { engine.destroyMaterial(it) } }
        skyVertices?.let { runCatching { engine.destroyVertexBuffer(it) } }
        skyIndices?.let { runCatching { engine.destroyIndexBuffer(it) } }
        colorGrading?.let { runCatching { engine.destroyColorGrading(it) } }
        runCatching { resourceLoader.asyncCancelLoad() }
        runCatching { resourceLoader.destroy() }
        runCatching { assetLoader.destroy() }
        // Wasser erst nach den Kacheln (die es noch benutzen koennten) freigeben.
        waterInstance?.let { runCatching { engine.destroyMaterialInstance(it) } }
        waterMaterial?.let { runCatching { engine.destroyMaterial(it) } }
        cloudTexture?.let { runCatching { engine.destroyTexture(it) } }
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

        /**
         * Lenkerperspektive: etwas weiter (Geschwindigkeitseindruck, Cockpit im Bild) und
         * die Bildmitte nach oben verschoben (`Camera.setShift`, ohne zu kippen — Senkrechte
         * bleiben senkrecht): im Hochformat liegt der Horizont bei rund 37 % der Hoehe, weil
         * die untere Haelfte von Bedienelementen bedeckt ist; quer bei rund 43 %.
         */
        const val HANDLEBAR_FOV_PORTRAIT_DEG = 74.0
        const val HANDLEBAR_FOV_LANDSCAPE_DEG = 64.0

        /**
         * Wert fuer `Camera.setShift`. Gemessen mit dem Referenzrenderer (Filament 1.75.1):
         * die Bildmitte wandert um das **Doppelte** des Werts in Geraetekoordinaten
         * (−1..1), 0,105 hebt sie also um 0,21 = 10,5 % der Bildhoehe.
         */
        const val HANDLEBAR_SHIFT_PORTRAIT = 0.105
        const val HANDLEBAR_SHIFT_LANDSCAPE = 0.04

        /** Das Cockpit liegt 0,6–0,9 m vor dem Auge; die Unterarme reichen naeher heran. */
        const val HANDLEBAR_NEAR_M = 0.2

        data class Projection(val fovDeg: Double, val near: Double, val shiftY: Double)

        /** Bildwinkel (senkrecht), nahe Ebene und Verschiebung fuer Seitenverhaeltnis [aspect]. */
        fun projectionFor(aspect: Double, handlebar: Boolean): Projection {
            val portrait = aspect < 1.0
            return if (handlebar) {
                Projection(
                    if (portrait) HANDLEBAR_FOV_PORTRAIT_DEG else HANDLEBAR_FOV_LANDSCAPE_DEG,
                    HANDLEBAR_NEAR_M,
                    if (portrait) HANDLEBAR_SHIFT_PORTRAIT else HANDLEBAR_SHIFT_LANDSCAPE,
                )
            } else {
                // Hochformat: waagrecht genug sehen — senkrechter Bildwinkel groesser.
                Projection(if (portrait) FOV_PORTRAIT_DEG else FOV_LANDSCAPE_DEG, NEAR_M, 0.0)
            }
        }
        const val SHADOW_FAR_M = 300f
        const val SHADOW_MAP_PX = 2048
        const val MSAA_SAMPLES = 4
        const val FOG_HEIGHT_FALLOFF = 0.0012f
        const val SKY_CUBE_PX = de.trailscape.core.indoor.sky.SkyClouds.CUBE_SIZE
        const val SKY_MATERIAL = "sky"
        const val WATER_MATERIAL = "water"

        /** Die Uhr der Materialien springt alle zwei Stunden zurueck (Float-Genauigkeit im Shader). */
        const val MATERIAL_TIME_WRAP_S = 7200.0

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
