package de.trailscape.app.indoor.render

import android.content.res.AssetManager
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * # PBR-Texturen der 3D-Ansicht
 *
 * Laedt und bindet die Textur-Arrays ([PbrRules], docs/indoor/pbr-texturen.md) fuer
 * [WorldMaterialProvider]: eine Gruppe erst, wenn ein Material sie braucht (nur tatsaechlich
 * vorkommende Belaege kosten Grafikspeicher), komprimiert (ETC2 + EAC, alle Mip-Stufen aus der
 * Datei). Hauptthread wie der Provider.
 *
 * Sicherheit (Filament 1.75.1 bricht bei Fehlern nativ ab, das laesst sich nicht abfangen):
 *  - Parameter nur, wenn das Material sie deklariert (`hasParameter`, aeltere `.filamat`).
 *  - `pbrMapC`/`pbrMapN` sind auf jeder Instanz gebunden: Gruppe oder neutrale 1×1-Array-Textur.
 *  - Formate vorher pruefen (`Texture.isTextureFormatSupported`): ohne ETC2/EAC (z. B. manche
 *    Vulkan-Treiber, lavapipe) bleibt PBR aus — sonst bricht `Texture.Builder.build` ab.
 *  - Datei kaputt oder unvollstaendig ([PbrRules.readKtx] prueft jede Groesse): Gruppe aus.
 *  - Sicherer Modus: `pbr.json` wird nicht geladen, die Materialien nicht benutzt.
 */
internal class PbrTextures(
    private val engine: Engine,
    private val packages: () -> Map<String, ByteArray>,
) {
    private var spec: PbrRules.Spec? = null
    private val groups = HashMap<String, Pair<Texture, Texture>>()
    private val failed = HashSet<String>()
    private var neutral: Texture? = null
    private var formatsOk: Boolean? = null

    /** Eigene Texturen: Anisotropie geht hier (anders als bei den gltfio-Texturen). */
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    ).apply { anisotropy = WorldMaterialProvider.DETAIL_ANISOTROPY }

    /** Geladene Gruppen und ihr Grafikspeicher in Bytes (Diagnose). */
    val loadedGroups: Set<String> get() = groups.keys
    val gpuBytes: Long get() = groups.keys.sumOf { g -> spec?.groups?.get(g)?.bytes ?: 0L }

    private fun spec(): PbrRules.Spec? {
        spec?.let { return it }
        spec = packages()[PbrRules.PACKAGE_KEY]?.let { PbrRules.parse(it.decodeToString()) }
        return spec
    }

    private fun formatsSupported(): Boolean = formatsOk ?: runCatching {
        Texture.isTextureFormatSupported(engine, Texture.InternalFormat.ETC2_EAC_SRGBA8) &&
            Texture.isTextureFormatSupported(engine, Texture.InternalFormat.EAC_RG11)
    }.getOrDefault(false).also {
        formatsOk = it
        if (!it) Log.i(TAG, "ETC2/EAC nicht unterstuetzt — PBR-Texturen aus")
    }

    /** Gelaende ([terrain] = true) oder Belag/Randstreifen nach dem Materialnamen: Texturen und Parameter binden. */
    fun setup(mi: MaterialInstance, label: String?, terrain: Boolean, on: Boolean) {
        val m = mi.material
        if (!m.hasParameter(MAP_C) || !m.hasParameter(MAP_N)) return
        val group = groupFor(label, terrain)
        val tex = group?.let { load(it) }
        val n = neutral() ?: return
        mi.setParameter(MAP_C, tex?.first ?: n, sampler)
        mi.setParameter(MAP_N, tex?.second ?: n, sampler)
        apply(mi, label, terrain, on)
    }

    /** Schaltet PBR einer Instanz um (Schalter, Grafikstufe) — sofort, ohne neu zu laden. */
    fun apply(mi: MaterialInstance, label: String?, terrain: Boolean, on: Boolean) {
        val m = mi.material
        val s = spec()
        val group = groupFor(label, terrain)
        val loaded = group != null && group in groups
        if (terrain) {
            if (!m.hasParameter(A) || !m.hasParameter(B) || !m.hasParameter(C)) return
            val p = s?.terrainParams(on && loaded) ?: Array(3) { FloatArray(4) }
            set(mi, A, p[0]); set(mi, B, p[1]); set(mi, C, p[2])
        } else {
            if (!m.hasParameter(A) || !m.hasParameter(B) || !m.hasParameter(C) || !m.hasParameter(D)) return
            val k = s?.let { sp -> PbrRules.keyFor(label, sp.kinds.keys)?.let { sp.kinds[it] } }
            val p = if (s != null && k != null) s.surfaceParams(k, on && loaded) else Array(4) { FloatArray(4) }
            set(mi, A, p[0]); set(mi, B, p[1]); set(mi, C, p[2]); set(mi, D, p[3])
        }
    }

    fun destroy() {
        for ((c, n) in groups.values) {
            runCatching { engine.destroyTexture(c) }
            runCatching { engine.destroyTexture(n) }
        }
        groups.clear()
        failed.clear()
        neutral?.let { runCatching { engine.destroyTexture(it) } }
        neutral = null
    }

    private fun set(mi: MaterialInstance, name: String, v: FloatArray) = mi.setParameter(name, v[0], v[1], v[2], v[3])

    private fun groupFor(label: String?, terrain: Boolean): String? {
        val s = spec() ?: return null
        return if (terrain) s.terrain?.group else PbrRules.keyFor(label, s.kinds.keys)?.let { s.kinds[it]?.group }
    }

    private fun load(name: String): Pair<Texture, Texture>? {
        groups[name]?.let { return it }
        if (name in failed || !formatsSupported()) return null
        val g = spec()?.groups?.get(name) ?: return null
        var c: Texture? = null
        val r = runCatching {
            val open = opener ?: error("keine Assets")
            c = upload(open("${PbrRules.DIR}/${g.color}") ?: error("${g.color} fehlt"), PbrRules.GL_ETC2_SRGBA8, g)
            val n = upload(open("${PbrRules.DIR}/${g.normal}") ?: error("${g.normal} fehlt"), PbrRules.GL_EAC_RG11, g)
            c!! to n
        }.onFailure {
            Log.w(TAG, "PBR-Gruppe $name nicht nutzbar", it)
            c?.let { t -> runCatching { engine.destroyTexture(t) } }
            failed += name
        }.getOrNull() ?: return null
        groups[name] = r
        return r
    }

    private fun upload(bytes: ByteArray, wantFormat: Int, g: PbrRules.Group): Texture {
        val k = PbrRules.readKtx(bytes) ?: error("KTX nicht lesbar")
        require(k.glInternalFormat == wantFormat && k.size == g.size && k.layers == g.layers) { "KTX passt nicht zu pbr.json" }
        val srgb = wantFormat == PbrRules.GL_ETC2_SRGBA8
        val t = Texture.Builder()
            .width(k.size).height(k.size).depth(k.layers).levels(k.levels.size)
            .sampler(Texture.Sampler.SAMPLER_2D_ARRAY)
            .format(if (srgb) Texture.InternalFormat.ETC2_EAC_SRGBA8 else Texture.InternalFormat.EAC_RG11)
            .build(engine)
        try {
            for ((lv, level) in k.levels.withIndex()) {
                val d = maxOf(1, k.size shr lv)
                val buf = ByteBuffer.allocateDirect(level.size).order(ByteOrder.nativeOrder())
                buf.put(bytes, level.offset, level.size).flip()
                // Groesse = alle Schichten der Stufe (glCompressedTexSubImage3D, OpenGLDriver.cpp)
                val pbd = Texture.PixelBufferDescriptor(
                    buf,
                    if (srgb) Texture.CompressedFormat.ETC2_EAC_SRGBA8 else Texture.CompressedFormat.EAC_RG11,
                    level.size,
                )
                t.setImage(engine, lv, 0, 0, 0, d, d, k.layers, pbd)
            }
        } catch (e: Throwable) {
            runCatching { engine.destroyTexture(t) }
            throw e
        }
        return t
    }

    private fun neutral(): Texture? {
        neutral?.let { return it }
        return runCatching {
            val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            px.put(byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 255.toByte())).flip()
            val t = Texture.Builder()
                .width(1).height(1).depth(1).levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D_ARRAY)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)
            try {
                t.setImage(engine, 0, 0, 0, 0, 1, 1, 1, Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE))
            } catch (e: Throwable) {
                runCatching { engine.destroyTexture(t) }
                throw e
            }
            t
        }.onFailure { Log.w(TAG, "neutrale PBR-Textur nicht anlegbar", it) }.getOrNull()?.also { neutral = it }
    }

    companion object {
        private const val TAG = "PbrTextures"
        const val MAP_C = "pbrMapC"
        const val MAP_N = "pbrMapN"
        const val A = "pbrA"
        const val B = "pbrB"
        const val C = "pbrC"
        const val D = "pbrD"

        /**
         * Liest Dateien aus `assets/indoor/pbr` bei Bedarf (die Texturen sind zusammen rund 19 MB
         * und werden nicht vorab gelesen). Gesetzt von [FilamentWorldRenderer] beim Start; `null`:
         * PBR aus. Der Application-Context haelt den AssetManager ohnehin.
         */
        @Volatile
        var opener: ((String) -> ByteArray?)? = null

        /** `pbr.json` fuer das Paket-Verzeichnis und den Leser der Texturen; fehlt etwas, bleibt PBR aus. */
        fun loadAssets(assets: AssetManager): Map<String, ByteArray> {
            opener = { path -> runCatching { assets.open(path).use { it.readBytes() } }.getOrNull() }
            val meta = runCatching { assets.open("${PbrRules.DIR}/${PbrRules.META}").use { it.readBytes() } }.getOrNull() ?: return emptyMap()
            return mapOf(PbrRules.PACKAGE_KEY to meta)
        }
    }
}
