package de.trailscape.app.indoor.render

import com.google.android.filament.Engine
import com.google.android.filament.LightManager
import com.google.android.filament.Texture
import com.google.android.filament.View
import de.trailscape.core.indoor.sky.SkyReflections
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.RenderTiers
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * # Bildentstehung der Grafikstufen Hoch/Ultra
 *
 * Alles, was [FilamentCore] fuer die neuen Felder von [RenderSettings] an
 * Filament (1.75.1) weitergibt — hier gesammelt, damit `FilamentCore` nur
 * wenige Zeilen dazubekommt. Jeder Schritt ist einzeln abgesichert: Ein Fehler
 * kostet nur diesen Effekt. Es werden **keine Materialparameter** gesetzt
 * (Issue 106) — nur View-, Licht- und Texturoptionen.
 *
 * Alle Vorgaben (`RenderSettings`-Felder aus) ergeben genau das Bild von vorher;
 * der sichere Modus ([RenderGuard]) schaltet die Felder ab
 * ([RenderTiers.withoutTierExtras]).
 */
internal object RenderQuality {
    /** Filament-Guete zu `RenderSettings.effectQuality` (0..2). */
    fun level(effectQuality: Int): View.QualityLevel = when {
        effectQuality >= 2 -> View.QualityLevel.HIGH
        effectQuality == 1 -> View.QualityLevel.MEDIUM
        else -> View.QualityLevel.LOW
    }

    /**
     * Umgebungsverdeckung: ab Guete 1 bessere Filter und etwas groesserer Radius.
     * Abgestimmt im Referenzrenderer (Alpe d'Huez abends): Guete 2 mit Staerke 1,0
     * und Exponent 1,2 dunkelte die ganze Szene merklich; 0,9 / 1,0 behaelt die
     * Tiefe im Laub ohne grauen Schleier.
     */
    fun applyAmbientOcclusion(view: View, s: RenderSettings) {
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = s.ambientOcclusion
            radius = 0.4f
            intensity = 1.0f
            power = 1.0f
            if (s.effectQuality >= 1) {
                val q = level(s.effectQuality)
                quality = q
                lowPassFilter = q
                upsampling = q
                radius = if (s.effectQuality >= 2) 0.55f else 0.5f
                intensity = 0.9f
            } else {
                quality = View.QualityLevel.LOW
                lowPassFilter = View.QualityLevel.MEDIUM
                upsampling = View.QualityLevel.LOW
            }
        }
    }

    fun applyBloom(view: View, s: RenderSettings) {
        view.bloomOptions = view.bloomOptions.apply {
            enabled = s.bloom
            strength = 0.08f
            quality = if (s.effectQuality >= 1) level(s.effectQuality) else View.QualityLevel.LOW
        }
    }

    /** Dynamische Aufloesung: ab Guete 1 mit FSR-Hochskalierung statt bilinear (Filament MEDIUM/HIGH). */
    fun applyDynamicResolution(view: View, s: RenderSettings) {
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = s.dynamicResolution
            minScale = s.dynamicResolutionMinScale.coerceIn(0.5, 1.0).toFloat()
            maxScale = 1.0f
            quality = if (s.effectQuality >= 1) level(s.effectQuality) else View.QualityLevel.LOW
        }
    }

    /**
     * Zeitliche Kantenglaettung statt 4× MSAA: glaettet auch die Raender der
     * Laubkarten (Alpha-Maske, die MSAA nicht erfasst) und das Flimmern feiner
     * Texturen bei Fahrt. Varianz-Box gegen Schlieren, leicht geschaerft.
     * `true`, wenn TAA wirklich an ist.
     */
    fun applyTemporalAA(view: View, enabled: Boolean): Boolean = runCatching {
        view.temporalAntiAliasingOptions = view.temporalAntiAliasingOptions.apply {
            this.enabled = enabled
            feedback = 0.1f
            boxType = View.TemporalAntiAliasingOptions.BoxType.AABB_VARIANCE
            sharpness = 0.25f
        }
        enabled
    }.getOrDefault(false)

    fun applyVignette(view: View, enabled: Boolean) {
        view.vignetteOptions = view.vignetteOptions.apply {
            this.enabled = enabled
            midPoint = 0.6f
            roundness = 0.4f
            feather = 0.6f
            color = floatArrayOf(0f, 0f, 0f, 1f)
        }
    }

    /**
     * Schattenoptionen der Sonne. Filament 1.75.1 kennt in Java keine Aenderung
     * zur Laufzeit (`LightManager.setShadowOptions` fehlt) — [FilamentCore] baut die
     * Sonne mit diesen Werten neu, wenn sich Kaskaden, Reichweite oder Stabilitaet aendern.
     */
    fun shadowOptions(cascades: Int, farM: Double, stable: Boolean): LightManager.ShadowOptions =
        LightManager.ShadowOptions().apply {
            mapSize = FilamentCore.SHADOW_MAP_PX
            shadowCascades = cascades.coerceIn(1, 4)
            cascadeSplitPositions = RenderTiers.cascadeSplits(cascades, farM)
            shadowFar = farM.toFloat()
            shadowFarHint = farM.toFloat()
            shadowNearHint = 1f
            this.stable = stable
        }

    /**
     * Spiegelungs-Umgebungslicht: vorgefilterte Cubemap aus dem Himmelsmodell
     * ([SkyReflections], 64², 7 Mip-Stufen, `R11F_G11F_B10F` ≈ 130 kB).
     * `null`, wenn das Format fehlt oder etwas schiefgeht (dann bleibt es beim SH-Licht).
     */
    fun buildReflections(engine: Engine, l: SkyLighting): Texture? {
        val ok = runCatching { Texture.isTextureFormatSupported(engine, Texture.InternalFormat.R11F_G11F_B10F) }.getOrDefault(false)
        if (!ok) return null
        val levels = runCatching { SkyReflections.prefilteredHalf(l.daylight) }.getOrNull() ?: return null
        val size = SkyReflections.BASE_SIZE
        val tex = runCatching {
            Texture.Builder()
                .width(size).height(size).levels(levels.size)
                .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
                .format(Texture.InternalFormat.R11F_G11F_B10F)
                .build(engine)
        }.getOrNull() ?: return null
        return runCatching {
            levels.forEachIndexed { lv, half ->
                val n = maxOf(1, size shr lv)
                val b = ByteBuffer.allocateDirect(half.size * 2).order(ByteOrder.nativeOrder())
                b.asShortBuffer().put(half)
                // Cubemaps gelten als 2D-Feld mit sechs Ebenen (+X, −X, +Y, −Y, +Z, −Z).
                tex.setImage(engine, lv, 0, 0, 0, n, n, 6, Texture.PixelBufferDescriptor(b, Texture.Format.RGB, Texture.Type.HALF))
            }
            tex
        }.getOrElse {
            runCatching { engine.destroyTexture(tex) }
            null
        }
    }
}
