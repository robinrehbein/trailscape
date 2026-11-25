package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.tree.TreeKind
import de.trailscape.core.indoor.tree.TreeLibrary
import kotlin.math.abs
import kotlin.math.ln

/**
 * Welche Vorlage der Baumbibliothek ([TreeLibrary]) fuer einen Baum des Weltbaus ([TreeModels.Spec])
 * steht — reine Rechnung, getestet.
 *
 * Art: Wuchsform des gebackenen Baums ([TreeModels.formOf]) → Art der Bibliothek (Esche ≈ Buche,
 * Feldbaum ≈ Obstbaum; suedlich von [TreesLayer.ALEPPO_MAX_LAT] die Aleppo-Kiefer statt der
 * Waldkiefer). Variante: aus dem Samen unter den Varianten, deren Hoehe hoechstens
 * [MAX_STRETCH]-fach abweicht, sonst die naechste. Massstab: Baumhoehe / Vorlagenhoehe
 * (gleichmaessig — die Breitenstreuung des Weltbaus traegt die Variante).
 */
internal object TreeInstanceMapping {
    /** Bis zu diesem Faktor wird eine Vorlage gestreckt oder gestaucht, bevor die naechstpassende Pflicht ist. */
    const val MAX_STRETCH = 1.3

    fun kindOf(form: TreeModels.Form, aleppo: Boolean): TreeKind = when (form) {
        TreeModels.Form.BEECH, TreeModels.Form.ASH -> TreeKind.BEECH
        TreeModels.Form.OAK -> TreeKind.OAK
        TreeModels.Form.BIRCH -> TreeKind.BIRCH
        TreeModels.Form.POPLAR -> TreeKind.POPLAR
        TreeModels.Form.FRUIT, TreeModels.Form.FIELD -> TreeKind.FRUIT
        TreeModels.Form.SPRUCE -> TreeKind.SPRUCE
        TreeModels.Form.PINE -> if (aleppo) TreeKind.ALEPPO_PINE else TreeKind.PINE
        TreeModels.Form.BUSH -> TreeKind.SHRUB
    }

    /** `(Art, Variante, Massstab)` fuer [spec]. */
    fun instanceOf(spec: TreeModels.Spec, aleppo: Boolean): Triple<TreeKind, Int, Double> {
        val kind = kindOf(TreeModels.formOf(spec), aleppo)
        val variants = 0 until TreeLibrary.VARIANTS
        fun stretch(v: Int) = abs(ln(spec.heightM / TreeLibrary.templateHeightM(kind, v)))
        val fitting = variants.filter { stretch(it) <= ln(MAX_STRETCH) }
        val variant = if (fitting.isEmpty()) variants.minBy { stretch(it) } else fitting[Math.floorMod(Noise.hash(spec.seed, 17, 0x7a11), fitting.size)]
        return Triple(kind, variant, spec.heightM / TreeLibrary.templateHeightM(kind, variant))
    }
}
