package de.trailscape.app.indoor.render

import android.util.Log
import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.FilamentAsset
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.garage.RiderGlbColors
import de.trailscape.core.garage.RiderLook
import de.trailscape.core.garage.RiderZone

/**
 * # Fahrer einfaerben (Garage, docs/indoor/garage.md)
 *
 * Das Fahrermodell hat je einfaerbbarer Zone ein eigenes Material ([RiderZone]);
 * die Farbe ist `baseColorFactor`. Jede geladene Instanz hat bei gltfio eigene
 * `MaterialInstance`s — ein Asset, beliebig viele Farben, keine Kopie je Farbe.
 *
 * **Sicherheit (Issue 106).** Filament beendet den Prozess, wenn ein Parameter
 * gesetzt wird, den das Material nicht hat — das faengt kein `try/catch`. Darum
 * wird vor **jedem** Setzen am Material gefragt, ob es [PARAM] deklariert
 * (`Material.hasParameter`, Filament 1.75.1); fehlt er, passiert still nichts.
 * Jede Instanz ist einzeln abgesichert: Ein Fehler an einer laesst die anderen
 * unberuehrt und kommt nie nach aussen.
 *
 * Im **sicheren Grafikmodus** ([RenderGuard]) wird zur Laufzeit gar nichts
 * gesetzt: Die Farben stehen dann schon in der Datei ([RiderGlbColors]), gltfio
 * liest sie wie bisher ([prepare] mit `safeMode = true`).
 */
internal object RiderTint {
    /** Der einzige Parameter, den die Garage setzt. Alle Ubershader-Varianten haben ihn. */
    const val PARAM = "baseColorFactor"

    /** Was am Material gebraucht wird — abstrahiert, damit sich die Regeln ohne Filament testen lassen. */
    interface Target {
        val materialName: String?

        /** Deklariert das Material [param]? */
        fun declares(param: String): Boolean

        fun setFloat4(param: String, r: Float, g: Float, b: Float, a: Float)
    }

    /**
     * Setzt je Ziel, dessen Material [factors] nennt, die Farbe — nur wenn das
     * Material [PARAM] deklariert und der Wert gueltig ist. Gibt die Zahl der
     * gesetzten Ziele zurueck. Wirft nie.
     */
    fun applyTo(targets: List<Target>, factors: Map<String, FloatArray>): Int {
        var n = 0
        for (t in targets) {
            val ok = runCatching {
                val name = t.materialName ?: return@runCatching false
                val f = factors[name] ?: return@runCatching false
                if (f.size != 4 || f.any { !it.isFinite() || it < 0f || it > 1f }) return@runCatching false
                if (!t.declares(PARAM)) return@runCatching false
                t.setFloat4(PARAM, f[0], f[1], f[2], f[3])
                true
            }.getOrDefault(false)
            if (ok) n++
        }
        return n
    }

    /**
     * Wie [applyTo] mit den Farben von [avatar]. Hat das Modell keine Zonen (der
     * eingebaute Ersatzfahrer `AvatarModel`), bekommt wenigstens dessen Trikot
     * (`x_mat_kit`) die Trikotfarbe — beim echten Modell ist `x_mat_kit` fest
     * (Socken, Handschuhe) und bleibt deshalb unberuehrt.
     */
    fun applyAvatar(targets: List<Target>, avatar: RiderAvatar): Int {
        val n = applyTo(targets, RiderLook.materialFactors(avatar))
        if (n > 0) return n
        return applyTo(targets, mapOf(FALLBACK_KIT to RiderLook.linearRgba(avatar.jersey)))
    }

    /** Die Materialinstanzen eines geladenen Assets als [Target]s (leer, wenn nicht lesbar). */
    fun targetsOf(asset: FilamentAsset): List<Target> {
        val instances = runCatching { asset.instance.materialInstances }.getOrNull() ?: return emptyList()
        return instances.map { FilamentTarget(it) }
    }

    /** Faerbt ein geladenes Asset ein; gibt die Zahl der gesetzten Instanzen zurueck. Wirft nie. */
    fun apply(asset: FilamentAsset, avatar: RiderAvatar): Int {
        val n = runCatching { applyAvatar(targetsOf(asset), avatar) }.getOrDefault(0)
        if (n == 0) runCatching { Log.w(TAG, "Fahrer ohne einfaerbbare Zonen — Farben aus der Datei") }
        return n
    }

    /**
     * Bytes vor dem Laden: im sicheren Modus mit eingeschriebenen Farben
     * (sonst unveraendert — dann faerbt [apply] nach dem Laden).
     */
    fun prepare(glb: ByteArray, avatar: RiderAvatar, safeMode: Boolean): ByteArray =
        if (safeMode) RiderGlbColors.apply(glb, RiderLook.materialFactors(avatar)) else glb

    private class FilamentTarget(private val mi: MaterialInstance) : Target {
        override val materialName: String? get() = runCatching { mi.name }.getOrNull()

        override fun declares(param: String): Boolean = mi.material.hasParameter(param)

        override fun setFloat4(param: String, r: Float, g: Float, b: Float, a: Float) = mi.setParameter(param, r, g, b, a)
    }

    private const val TAG = "RiderTint"
    private const val FALLBACK_KIT = "x_mat_kit"
}
