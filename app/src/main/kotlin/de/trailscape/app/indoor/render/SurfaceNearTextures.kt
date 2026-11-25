package de.trailscape.app.indoor.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Die Texturen des Nahdetails fuer [WorldMaterialProvider]: Karten erst beim ersten Belag, der sie
 * braucht (JPEG → RGBA8 mit Mip-Stufen, rund 1,4 MB Grafikspeicher je Karte), und eine neutrale
 * 1×1-Textur, damit `nearMap` auf jeder Instanz gebunden ist. Hauptthread wie der Provider.
 */
internal class SurfaceNearTextures(
    private val engine: Engine,
    private val packages: () -> Map<String, ByteArray>,
) {
    private var spec: SurfaceNearDetail.Spec? = null
    private val textures = HashMap<String, Texture>()
    private val failed = HashSet<String>()
    private var neutral: Texture? = null

    /** Eigene Textur: Anisotropie geht hier (anders als bei den gltfio-Texturen). */
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
    ).apply { anisotropy = WorldMaterialProvider.DETAIL_ANISOTROPY }

    /** Geladene Karten (Diagnose, Tests). */
    val loadedMaps: Set<String> get() = textures.keys

    /** Erst bekannt, wenn die Pakete geladen sind (nach dem Start); bis dahin `null`, nicht gemerkt. */
    private fun spec(): SurfaceNearDetail.Spec? {
        spec?.let { return it }
        spec = packages()[SurfaceNearDetail.PACKAGE_PREFIX + SurfaceNearDetail.META]?.let { SurfaceNearDetail.parse(it.decodeToString()) }
        return spec
    }

    /**
     * Bindet Karte und Parameter an eine Belags-Instanz. Nur, wenn das Material die Parameter
     * wirklich deklariert (aeltere `.filamat` ohne Nahdetail): ein unbekannter Parameter beendet
     * Filament im Release nativ (Issue 106), deshalb jede Zuweisung hinter `hasParameter`.
     */
    fun setup(mi: MaterialInstance, label: String?, on: Boolean) {
        val m = mi.material
        if (!m.hasParameter(NEAR_MAP) || !m.hasParameter(NEAR_A) || !m.hasParameter(NEAR_B) || !m.hasParameter(NEAR_C)) return
        val s = spec()
        val kind = s?.let { sp -> SurfaceNearDetail.keyFor(label, sp)?.let { sp.kinds[it] } }
        val tex = (if (s != null && kind != null) map(s, kind.map) else null) ?: neutral()
        mi.setParameter(NEAR_MAP, tex, sampler)
        // Ohne geladene Karte bleibt das Detail aus (apply prueft das).
        apply(mi, label, on)
    }

    /** Schaltet das Nahdetail einer Instanz um („Bodendetails“, Qualitaetsstufe). */
    fun apply(mi: MaterialInstance, label: String?, on: Boolean) {
        val m = mi.material
        if (!m.hasParameter(NEAR_A) || !m.hasParameter(NEAR_B) || !m.hasParameter(NEAR_C)) return
        val s = spec()
        val kind = s?.let { sp -> SurfaceNearDetail.keyFor(label, sp)?.let { sp.kinds[it] } }
        val loaded = kind != null && kind.map in textures
        val p = if (s != null && kind != null) s.params(kind, on && loaded) else Array(3) { FloatArray(4) }
        mi.setParameter(NEAR_A, p[0][0], p[0][1], p[0][2], p[0][3])
        mi.setParameter(NEAR_B, p[1][0], p[1][1], p[1][2], p[1][3])
        mi.setParameter(NEAR_C, p[2][0], p[2][1], p[2][2], p[2][3])
    }

    fun destroy() {
        for (t in textures.values) runCatching { engine.destroyTexture(t) }
        textures.clear()
        failed.clear()
        neutral?.let { runCatching { engine.destroyTexture(it) } }
        neutral = null
    }

    private fun map(s: SurfaceNearDetail.Spec, name: String): Texture? {
        textures[name]?.let { return it }
        if (name in failed) return null
        val t = runCatching {
            val bytes = packages()[SurfaceNearDetail.PACKAGE_PREFIX + s.maps.getValue(name)] ?: error("Karte $name fehlt")
            upload(decode(bytes))
        }.onFailure {
            Log.w(TAG, "Nahdetail-Karte $name nicht nutzbar", it)
            failed += name
        }.getOrNull() ?: return null
        textures[name] = t
        return t
    }

    private fun neutral(): Texture {
        neutral?.let { return it }
        val px = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        px.put(byteArrayOf(128.toByte(), 128.toByte(), 128.toByte(), 255.toByte())).flip()
        val t = Texture.Builder()
            .width(1).height(1).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)
        try {
            t.setImage(engine, 0, Texture.PixelBufferDescriptor(px, Texture.Format.RGBA, Texture.Type.UBYTE))
        } catch (e: Throwable) {
            runCatching { engine.destroyTexture(t) }
            throw e
        }
        neutral = t
        return t
    }

    private class Pixels(val w: Int, val h: Int, val rgba: ByteBuffer)

    /** JPEG → RGBA8 (Bytes R, G, B, A; die Karte ist keine Farbe — keine Farbraum-Umrechnung). */
    private fun decode(bytes: ByteArray): Pixels {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("JPEG nicht lesbar")
        try {
            require(bmp.width in 1..2048 && bmp.height in 1..2048) { "Karte ${bmp.width}×${bmp.height}" }
            val b = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(b)
            b.flip()
            return Pixels(bmp.width, bmp.height, b)
        } finally {
            bmp.recycle()
        }
    }

    private fun upload(p: Pixels): Texture {
        val t = Texture.Builder()
            .width(p.w).height(p.h).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE)
            .build(engine)
        try {
            t.setImage(engine, 0, Texture.PixelBufferDescriptor(p.rgba, Texture.Format.RGBA, Texture.Type.UBYTE))
            t.generateMipmaps(engine)
        } catch (e: Throwable) {
            runCatching { engine.destroyTexture(t) }
            throw e
        }
        return t
    }

    companion object {
        /**
         * Liest `detail.json` und die Karten aus den Assets als Eintraege fuer das Paket-Verzeichnis
         * ([SurfaceNearDetail.PACKAGE_PREFIX] + Dateiname). Fehlt etwas, fehlt es eben (Nahdetail aus bzw. neutrale Karte).
         */
        fun loadAssets(assets: AssetManager): Map<String, ByteArray> {
            val out = HashMap<String, ByteArray>()
            val meta = runCatching { assets.open("${SurfaceNearDetail.DIR}/${SurfaceNearDetail.META}").use { it.readBytes() } }.getOrNull() ?: return out
            out[SurfaceNearDetail.PACKAGE_PREFIX + SurfaceNearDetail.META] = meta
            val spec = SurfaceNearDetail.parse(meta.decodeToString()) ?: return out
            for (file in spec.maps.values.toSet()) {
                runCatching { assets.open("${SurfaceNearDetail.DIR}/$file").use { it.readBytes() } }.onSuccess { out[SurfaceNearDetail.PACKAGE_PREFIX + file] = it }
            }
            return out
        }

        private const val TAG = "SurfaceNearDetail"
        const val NEAR_MAP = "nearMap"
        const val NEAR_A = "nearA"
        const val NEAR_B = "nearB"
        const val NEAR_C = "nearC"
    }
}
