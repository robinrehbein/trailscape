package de.trailscape.app.indoor.render

import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.Texture
import de.trailscape.core.indoor.water.WaterWaves
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hilfen fuer das Wassermaterial (`water.mat`), getrennt von [FilamentCore], damit dort nur
 * wenige Zeilen hinzukommen.
 *
 * **Sicherheit (Issue 106):** Filament bricht nativ ab, wenn ein nicht deklarierter Parameter
 * gesetzt wird oder ein deklarierter Sampler ungebunden bleibt. Darum
 *  * filtert [declared] jede Parameterliste gegen [Material.hasParameter] — passt ein
 *    altes `water.filamat` nicht zur Liste, fehlen nur Werte, es stuerzt nichts;
 *  * bindet der Aufrufer die Wellenkarte, sobald das Material sie deklariert, **bevor** die
 *    Instanz an ein Renderable geht (siehe `FilamentCore.ensureWaterInstance`).
 */
internal object WaterSurface {
    const val WAVE_NORMALS = "waveNormals"

    /** Nur die Eintraege, die [material] deklariert. */
    fun declared(material: Material?, params: Map<String, Any>): Map<String, Any> {
        if (material == null) return emptyMap()
        return params.filterKeys { runCatching { material.hasParameter(it) }.getOrDefault(false) }
    }

    /**
     * Die Wellen-Normalenkarte ([WaterWaves], 128² RGBA8, kachelbar) mit Mipmaps. RGBA8 ist in
     * OpenGL ES 3.0 filterbar und farbrenderbar (Voraussetzung fuer `generateMipmaps`).
     * Erzeugen kostet einmalig rund 10–20 ms (56 Wellen × 16 384 Texel, nur Multiplikationen).
     */
    fun buildWaveNormals(engine: Engine): Texture {
        val n = WaterWaves.SIZE
        val px = WaterWaves.normalTexture(n)
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
        return tex
    }
}
