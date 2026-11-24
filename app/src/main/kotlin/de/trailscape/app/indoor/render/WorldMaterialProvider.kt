package de.trailscape.app.indoor.render

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.UbershaderProvider
import de.trailscape.core.indoor.sky.GroundDetail
import de.trailscape.core.indoor.sky.SkyShading
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * # Eigene Materialien fuer die Kacheln
 *
 * gltfio-`MaterialProvider`, der ausgewaehlte Materialien der Welt ([WorldMaterialRules]) durch
 * eigene Filament-Materialien ersetzt — Gelaende mit Boden-Detail, Laub mit Wind, Belaege mit
 * Software-Anisotropie — und alles andere an den Ubershader weitergibt. gltfio setzt danach auf
 * jede Instanz dieselben Parameter wie beim Ubershader (`baseColorFactor` …, AssetLoader.cpp)
 * und bindet Luftbild bzw. Atlas an `baseColorMap`; die eigenen Materialien deklarieren diese
 * Parameter deshalb alle.
 *
 * **JNI:** Weil diese Klasse kein `getNativeObject()` hat, nimmt gltfio den langsamen Weg
 * (`JavaMaterialProvider` in AssetLoader.cpp) und ruft die Methoden unten beim Namen auf —
 * im Release-Build halten `proguard-rules.pro` sie. Alles laeuft auf dem Hauptthread (dort
 * entstehen die Assets). Eine Ausnahme darf nicht in den nativen Code zurueck: jeder Fehler
 * faellt auf den Ubershader zurueck, und das Material wird nicht wieder versucht.
 *
 * **Laufzeit:** [apply] stellt Detail, Wind und Anisotropie auf allen lebenden Instanzen um
 * (Qualitaet, Schalter); [forget] vergisst die Instanzen eines Assets, bevor es zerstoert wird.
 */
internal class WorldMaterialProvider(
    private val engine: Engine,
    private val uber: UbershaderProvider,
    /** Die Pakete `.filamat` nach Namen (`terrain`, `foliage`, …), erst nach dem Start bekannt. */
    private val packages: () -> Map<String, ByteArray>,
    /** Wolkenrauschen (R8, kachelbar), dieselbe Textur wie Himmel und Wasser. */
    private val noiseTexture: () -> Texture,
) : MaterialProvider {
    private val materials = HashMap<WorldMaterialRules.Kind, Material>()
    private val failed = HashSet<WorldMaterialRules.Kind>()
    private var detailTexture: Texture? = null

    /** Lebende eigene Instanzen: nativer Zeiger → (Art, Name). */
    private val live = HashMap<Long, Pair<MaterialInstance, Pair<WorldMaterialRules.Kind, String?>>>()

    var groundDetail = true
        private set
    var wind = true
        private set
    var aniso = true
        private set

    /** Zaehler fuer Diagnose und Tests: eigene Instanzen je Art seit dem Start. */
    val created = HashMap<WorldMaterialRules.Kind, Int>()

    private val repeatSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    )

    /** Das Detail ist unsere eigene Textur — anders als bei den gltfio-Texturen geht hier Anisotropie. */
    private val detailSampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    ).apply { anisotropy = DETAIL_ANISOTROPY }

    // ---------------------------------------------------------------- MaterialProvider (JNI)

    override fun createMaterialInstance(
        config: MaterialProvider.MaterialKey,
        uvmap: IntArray,
        label: String?,
        extras: String?,
    ): MaterialInstance? {
        val kind = WorldMaterialRules.kindFor(label, config.alphaMode, config.hasBaseColorTexture, config.unlit)
        if (kind != WorldMaterialRules.Kind.UBER) {
            try {
                val m = material(kind)
                if (m != null) {
                    config.constrainMaterial(uvmap)
                    val mi = m.createInstance(label ?: kind.name)
                    if (kind == WorldMaterialRules.Kind.FOLIAGE && !config.doubleSided) mi.setDoubleSided(false)
                    setup(mi, kind, label)
                    live[mi.nativeObject] = mi to (kind to label)
                    created[kind] = (created[kind] ?: 0) + 1
                    return mi
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Material ${kind.asset} nicht nutzbar, nehme den Ubershader", t)
                failed += kind
            }
        }
        return uber.createMaterialInstance(config, uvmap, label, extras)
    }

    override fun getMaterial(config: MaterialProvider.MaterialKey, uvmap: IntArray, label: String?): Material? =
        uber.getMaterial(config, uvmap, label)

    override fun getMaterials(): Array<Material> = uber.materials + materials.values

    override fun needsDummyData(attrib: Int): Boolean = uber.needsDummyData(attrib)

    override fun destroyMaterials() {
        uber.destroyMaterials()
        live.clear()
        for (m in materials.values) runCatching { engine.destroyMaterial(m) }
        materials.clear()
        detailTexture?.let { runCatching { engine.destroyTexture(it) } }
        detailTexture = null
    }

    override fun destroy() {
        destroyMaterials()
        uber.destroy()
    }

    // ---------------------------------------------------------------- Laufzeit

    /** Stellt Detail, Wind und Anisotropie um — sofort auf allen lebenden Instanzen. */
    fun apply(groundDetail: Boolean, wind: Boolean, aniso: Boolean) {
        if (groundDetail == this.groundDetail && wind == this.wind && aniso == this.aniso) return
        this.groundDetail = groundDetail
        this.wind = wind
        this.aniso = aniso
        for ((mi, info) in live.values) runCatching { setSwitches(mi, info.first) }
    }

    /** Vergisst die Instanzen, die mit einem Asset verschwinden (vor dessen Zerstoerung rufen). */
    fun forget(instances: Array<MaterialInstance>) {
        for (mi in instances) live.remove(mi.nativeObject)
    }

    val liveCount: Int get() = live.size

    // ---------------------------------------------------------------- intern

    private fun material(kind: WorldMaterialRules.Kind): Material? {
        materials[kind]?.let { return it }
        if (kind in failed) return null
        val bytes = packages()[kind.asset] ?: return null
        val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        b.put(bytes)
        b.flip()
        val m = Material.Builder().payload(b, bytes.size).build(engine)
        materials[kind] = m
        return m
    }

    private fun ensureDetailTexture(): Texture {
        detailTexture?.let { return it }
        val n = GroundDetail.SIZE
        val px = GroundDetail.texture(n)
        val tex = Texture.Builder()
            .width(n).height(n).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        try {
            val b = ByteBuffer.allocateDirect(px.size).order(ByteOrder.nativeOrder())
            b.put(px)
            b.flip()
            tex.setImage(engine, 0, Texture.PixelBufferDescriptor(b, Texture.Format.RGBA, Texture.Type.UBYTE))
            tex.generateMipmaps(engine)
        } catch (t: Throwable) {
            runCatching { engine.destroyTexture(tex) }
            throw t
        }
        detailTexture = tex
        return tex
    }

    private fun setup(mi: MaterialInstance, kind: WorldMaterialRules.Kind, label: String?) {
        when (kind) {
            WorldMaterialRules.Kind.TERRAIN -> {
                setParameters(mi, SkyShading.terrain())
                mi.setParameter("detailMap", ensureDetailTexture(), detailSampler)
                mi.setParameter("macroMap", noiseTexture(), repeatSampler)
            }
            WorldMaterialRules.Kind.FOLIAGE -> {
                setParameters(mi, SkyShading.foliage())
                mi.setParameter("time", 0f)
                mi.setParameter("atlasMode", WorldMaterialRules.atlasMode(label))
            }
            WorldMaterialRules.Kind.SURFACE, WorldMaterialRules.Kind.SURFACE_BLEND -> {
                setParameters(mi, SkyShading.surface())
                mi.setParameter("detailMap", ensureDetailTexture(), detailSampler)
                val c = WorldMaterialRules.detailClass(label)
                mi.setParameter("detailClass", c[0], c[1], c[2], c[3])
            }
            WorldMaterialRules.Kind.UBER -> Unit
        }
        setSwitches(mi, kind)
    }

    private fun setSwitches(mi: MaterialInstance, kind: WorldMaterialRules.Kind) {
        when (kind) {
            WorldMaterialRules.Kind.TERRAIN ->
                mi.setParameter("detailRange", if (groundDetail) SkyShading.TERRAIN_DETAIL_RANGE_M.toFloat() else 0f)
            WorldMaterialRules.Kind.FOLIAGE -> mi.setParameter("windStrength", if (wind) 1f else 0f)
            WorldMaterialRules.Kind.SURFACE, WorldMaterialRules.Kind.SURFACE_BLEND -> {
                mi.setParameter("detailRange", if (groundDetail) SkyShading.SURFACE_DETAIL_RANGE_M.toFloat() else 0f)
                mi.setParameter("anisoRange", if (aniso) SkyShading.SURFACE_ANISO_RANGE_M.toFloat() else 0f)
            }
            WorldMaterialRules.Kind.UBER -> Unit
        }
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

    companion object {
        private const val TAG = "WorldMaterials"
        const val DETAIL_ANISOTROPY = 8f
    }
}
