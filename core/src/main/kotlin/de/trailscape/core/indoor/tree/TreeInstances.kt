package de.trailscape.core.indoor.tree

import kotlin.math.sqrt

/**
 * # Bauminstanzen: Vertrag zwischen Weltbau und Darstellung
 *
 * Der Weltbau backt die Baeume im Band neben der Strecke wie bisher in die
 * Kachel, aber **je Streckenabschnitt** in einen eigenen Knoten
 * `x_trees_bin_<k>` unter `trees` (k = Laufmeter der naechsten Streckenstelle /
 * [BIN_M]). Zusaetzlich schreibt er je Baum einen **leeren** Knoten
 * `x_treei_<art>_<variante>_<k>` unter `trees` mit Verschiebung, Drehung um die
 * Hochachse und gleichmaessigem Massstab (Baumhoehe / Hoehe der Vorlage) — reines
 * glTF, keine Erweiterung.
 *
 * Die Darstellung zeigt fuer einen Abschnitt entweder die gebackenen Baeume
 * oder — nah an der Kamera und nur auf den hoeheren Grafikstufen — Instanzen
 * der Bibliothek ([TreeLibrary]) an genau diesen Stellen; welche, entscheidet
 * [TreeInstancePlanner]. Eine App, die die Knoten nicht kennt, zeigt die
 * gebackenen Baeume (die Abschnitte liegen unter `trees`).
 *
 * Die Kennung der Bibliothek steckt im Praefix: aendern sich die Vorlagen so,
 * dass alte Welten nicht mehr passen, bekommt der Instanzknoten ein neues
 * Praefix — dann bleibt es in alten Apps bei den gebackenen Baeumen.
 */
object TreeInstanceNames {
    /** Praefix der Instanzknoten (Bibliothek `trees/1`). */
    const val INSTANCE_PREFIX = "x_treei_"

    /** Praefix der Abschnittsknoten mit den gebackenen Bandbaeumen. */
    const val BIN_PREFIX = "x_trees_bin_"

    /** Laenge eines Abschnitts entlang der Strecke (m). */
    const val BIN_M = 32.0

    /** Baeume bis zu diesem Abstand zur Streckenmitte (m) bekommen eine Instanz. */
    const val BAND_M = 20.0

    fun binName(bin: Int): String = "$BIN_PREFIX$bin"

    fun instanceName(kind: TreeKind, variant: Int, bin: Int): String = "$INSTANCE_PREFIX${kind.name.lowercase()}_${variant}_$bin"

    /** Abschnitt eines Abschnittsknotens; `null`, wenn [name] keiner ist. */
    fun parseBin(name: String?): Int? {
        if (name == null || !name.startsWith(BIN_PREFIX)) return null
        return name.substring(BIN_PREFIX.length).toIntOrNull()
    }

    /** Art, Variante und Abschnitt eines Instanzknotens; `null`, wenn [name] keiner ist oder die Art unbekannt. */
    fun parseInstance(name: String?): Triple<TreeKind, Int, Int>? {
        if (name == null || !name.startsWith(INSTANCE_PREFIX)) return null
        val parts = name.substring(INSTANCE_PREFIX.length).split('_')
        if (parts.size < 3) return null
        val bin = parts[parts.size - 1].toIntOrNull() ?: return null
        val variant = parts[parts.size - 2].toIntOrNull() ?: return null
        if (variant !in 0 until TreeLibrary.VARIANTS) return null
        val kindName = parts.subList(0, parts.size - 2).joinToString("_")
        val kind = TreeKind.entries.firstOrNull { it.name.lowercase() == kindName } ?: return null
        return Triple(kind, variant, bin)
    }
}

/**
 * Wie viele Instanzen eine Grafikstufe zeigt (`RenderSettings.treeDetail`).
 *
 * @property nearM bis zu diesem Abstand zur Kamera die Nahstufe
 * @property midM Abschnitte mit einem Baum bis zu diesem Abstand werden instanziert
 * @property maxNear hoechstens so viele Baeume gleichzeitig in der Nahstufe
 * @property maxTotal hoechstens so viele Instanzen insgesamt (nah + mittel)
 */
class TreeInstanceBudget(val nearM: Double, val midM: Double, val maxNear: Int, val maxTotal: Int) {
    companion object {
        /** Abstand, um den eine Grenze ueberschritten sein muss, bevor zurueckgeschaltet wird (gegen Flackern). */
        const val HYSTERESIS_M = 3.0

        /**
         * Budget zur Stufe [treeDetail]: 0 = keine Instanzen (gebackene Baeume wie bisher),
         * 1 = Hoch, 2 = Ultra (die Werte des Einbauplans: 25 m / 60 m, 16 nah, 56 insgesamt).
         */
        fun forDetail(treeDetail: Int): TreeInstanceBudget? = when {
            treeDetail <= 0 -> null
            treeDetail == 1 -> TreeInstanceBudget(nearM = 18.0, midM = 45.0, maxNear = 10, maxTotal = 40)
            else -> TreeInstanceBudget(nearM = 25.0, midM = 60.0, maxNear = 16, maxTotal = 56)
        }
    }
}

/** Ein Baum eines Abschnitts in Weltkoordinaten der Darstellung (x Ost, y hoch, z −Nord). */
class TreePlace(
    val x: Double,
    val y: Double,
    val z: Double,
    val yawRad: Double,
    val scale: Double,
    val kind: TreeKind,
    val variant: Int,
) {
    /** Stufe, die der Planer zuletzt gewaehlt hat; `null` = keine Instanz (Abschnitt gebacken). */
    var lod: TreeLod? = null
        internal set

    internal var dist = 0.0
}

/** Alle Instanzbaeume eines Abschnitts einer Kachel. */
class TreeBin(val trees: List<TreePlace>) {
    /** `true`: der Planer zeigt Instanzen, die gebackenen Baeume des Abschnitts sind auszublenden. */
    var active: Boolean = false
        internal set

    internal var dist = 0.0
}

/**
 * Waehlt je Bild, welche Abschnitte instanziert werden und welche Baeume die
 * Nahstufe bekommen — reine Rechnung, getestet.
 *
 *  * Ein Abschnitt wird **als Ganzes** umgeschaltet: entweder alle seine Baeume
 *    als Instanz oder alle gebacken (kein Baum fehlt, keiner steht doppelt).
 *  * Abschnitte kommen von der Kamera nach aussen dran, solange einer ihrer
 *    Baeume naeher als `midM` ist und `maxTotal` reicht; der naechste Abschnitt
 *    wird immer instanziert.
 *  * In den aktiven Abschnitten bekommen die naechsten `maxNear` Baeume bis
 *    `nearM` die Nahstufe, alle anderen die mittlere.
 *  * Wer schon aktiv bzw. nah ist, bleibt es bis [TreeInstanceBudget.HYSTERESIS_M]
 *    hinter der Grenze und geht bei der Vergabe vor.
 */
object TreeInstancePlanner {
    /** Setzt [TreeBin.active] und [TreePlace.lod] fuer die Kamera an (`camX`, `camZ`); [budget] `null` schaltet alles ab. */
    fun select(bins: Collection<TreeBin>, camX: Double, camZ: Double, budget: TreeInstanceBudget?) {
        if (budget == null) {
            for (b in bins) deactivate(b)
            return
        }
        val h = TreeInstanceBudget.HYSTERESIS_M
        val candidates = ArrayList<TreeBin>()
        for (b in bins) {
            var best = Double.MAX_VALUE
            for (t in b.trees) {
                val dx = t.x - camX
                val dz = t.z - camZ
                t.dist = sqrt(dx * dx + dz * dz)
                if (t.dist < best) best = t.dist
            }
            // Aktive gehen vor: ihr Abstand zaehlt um die Hysterese kleiner.
            b.dist = if (b.active) best - h else best
            if (b.trees.isNotEmpty() && b.dist <= budget.midM) candidates += b else deactivate(b)
        }
        candidates.sortBy { it.dist }
        var total = 0
        val shown = ArrayList<TreePlace>()
        for ((i, b) in candidates.withIndex()) {
            if (i > 0 && total + b.trees.size > budget.maxTotal) {
                deactivate(b)
                continue
            }
            total += b.trees.size
            b.active = true
            shown += b.trees
        }
        // Nahstufe: die naechsten zuerst, wer schon nah ist mit Hysterese.
        shown.sortBy { if (it.lod == TreeLod.NEAR) it.dist - h else it.dist }
        var near = 0
        for (t in shown) {
            val limit = if (t.lod == TreeLod.NEAR) budget.nearM + h else budget.nearM
            if (near < budget.maxNear && t.dist <= limit) {
                t.lod = TreeLod.NEAR
                near++
            } else {
                t.lod = TreeLod.MID
            }
        }
    }

    private fun deactivate(b: TreeBin) {
        if (!b.active && b.trees.all { it.lod == null }) return
        b.active = false
        for (t in b.trees) t.lod = null
    }
}
