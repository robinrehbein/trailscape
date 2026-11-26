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
 * Die Darstellung zeigt nah an der Kamera — nur auf den hoeheren Grafikstufen —
 * Instanzen der Bibliothek ([TreeLibrary]) an genau diesen Stellen und weiter
 * weg die gebackenen Baeume; welche, entscheidet [TreeInstancePlanner]. Der
 * Wechsel ist ein Ring um die Kamera, in dem das Material beide Darstellungen
 * ineinander rastert ([TreeInstancePlanner.selectFading]); kann das Material
 * das nicht, schaltet ein Abschnitt als Ganzes um ([TreeInstancePlanner.select]).
 * Eine App, die die Knoten nicht kennt, zeigt die gebackenen Baeume (die
 * Abschnitte liegen unter `trees`).
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

        /** Breite des Rings (m), in dem gebackene Baeume und Instanzen ineinander gerastert werden. */
        const val FADE_BAND_M = 10.0

        /** Der Ring ist hoechstens dieser Anteil des Wechselabstands breit (enger Wald: kleiner Abstand, schmaler Ring). */
        const val FADE_BAND_SHARE = 0.5

        /** Zuschlag (m) auf die Ausladung, bevor ein gebackener Abschnitt ganz abgeschaltet wird (die gebackene Krone kann breiter sein). */
        const val BAKED_EXTRA_M = 3.0

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

/**
 * Wie die Darstellung den Wechsel zeichnet (`foliage.mat`, Parameter `fade…`): Im Ring rastert das
 * Material beide Darstellungen ineinander, und die gebackenen Baeume nehmen bis zum Ring den Ton der
 * Bibliothek an — die Welt bleibt, wie sie gebaut ist, nur ihr Material wird nah am Wechsel getoent.
 * Ein Abschnitt hat fuer alle Arten **ein** Material, deshalb je zwei Faktoren nach der Helligkeit
 * der Vertexfarbe (linear, Rec. 709): „dunkel" fuer Nadelbaeume bzw. gewoehnliche Staemme, „hell"
 * fuer Laubbaeume bzw. Birkenstaemme; Nadelzweige und Nadelbaum-Kreuzbilder erkennt das Material
 * zusaetzlich an ihren Feldern im Laubatlas der Welt. Gemessen an der Testwelt (mittlere Kronenfarbe im Bild und mittlere Stammfarbe je Art,
 * gebacken gegen Bibliothek — docs/indoor/baeume.md). `filarender.cpp` spiegelt die Werte.
 */
object TreeFadeLook {
    /** Hinter dem Ring laeuft die Toenung der gebackenen Baeume ueber diese Strecke (m) aus. */
    const val TINT_FALLOFF_M = 30.0

    /** Laub: Faktor fuer Nadelbaeume (Fichte, Kiefer: die Bibliothek ist etwas dunkler und blauer). */
    val LEAF_DARK = floatArrayOf(0.7f, 0.96f, 0.8f)

    /** Laub: Faktor fuer Laubbaeume und Straeucher (die Bibliothek ist heller und gelber). */
    val LEAF_LIGHT = floatArrayOf(1.9f, 1.35f, 2.0f)

    /** Laub: Helligkeit der Vertexfarbe, zwischen der von „dunkel" (Fichtenkegel) nach „hell" (Laub) geblendet wird. */
    val LEAF_LUMA = floatArrayOf(0.022f, 0.045f)

    /** Rinde: Faktor fuer gewoehnliche Staemme (gebacken hell-beige, Bibliothek dunkle Borke). */
    val BARK_DARK = floatArrayOf(0.44f, 0.47f, 0.56f)

    /** Rinde: Faktor fuer helle Staemme (Birke: die Bibliothek ist weisser). */
    val BARK_LIGHT = floatArrayOf(1.67f, 1.69f, 2.0f)

    val BARK_LUMA = floatArrayOf(0.45f, 0.60f)

    /** So schnell (m/s) waechst der Ring nach dem Laden oder einem Stufenwechsel, statt aufzuspringen. */
    const val GROW_M_PER_S = 20.0
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

    /**
     * Waagrechte Ausladung (m) des Baums um seinen Fusspunkt — Umriss der Vorlage × [scale]; die
     * Darstellung setzt sie, sobald sie die Bibliothek kennt. Nur [TreeInstancePlanner.selectFading]
     * braucht sie: ein Baum bekommt seine Instanz, bevor seine Krone in den Wechselring ragt.
     */
    var reachM: Double = DEFAULT_REACH_M

    internal var dist = 0.0
    internal var edge = 0.0

    companion object {
        /**
         * Ausladung, solange die Vorlage nicht bekannt ist: die breiteste Krone der Bibliothek (Eiche,
         * 24 m hoch: 14,2 m) im groessten Massstab (der Weltbau waehlt die Variante bis Faktor 1,3).
         */
        const val DEFAULT_REACH_M = 19.0
    }
}

/**
 * Der Wechselring eines Bilds ([TreeInstancePlanner.selectFading]), waagrecht um die Kamera:
 * bis [startM] nur Instanzen, ab [endM] nur gebackene Baeume, dazwischen beide gerastert.
 *
 * @property targetEndM wohin [endM] will; groesser als [endM], solange der Ring noch waechst
 */
class TreeFade(val startM: Double, val endM: Double, val targetEndM: Double) {
    val growing: Boolean get() = targetEndM > endM + 1e-6
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

    /**
     * Wie [select], aber fuer ein Material, das beide Darstellungen **nach dem Abstand zur Kamera**
     * ineinander rastert (`foliage.mat`, `fadeMode`): der Wechsel ist ein Ring um die Kamera statt
     * eines Sprungs je Abschnitt.
     *
     *  * Der Wechselabstand `endM` ist `midM` — oder kleiner, wenn `maxTotal` nicht reicht: dann der
     *    Abstand, in dem der erste Baum **ohne** Instanz in den Ring ragen wuerde (Abstand minus
     *    [TreePlace.reachM]). Er haengt stetig an der Kamera; nichts springt.
     *  * Eine Instanz bekommt jeder Baum, dessen Krone in den Wechselabstand ragt — die naechsten
     *    zuerst, hoechstens `maxTotal`; wer eine hat, behaelt sie bis [TreeInstanceBudget.HYSTERESIS_M]
     *    dahinter (dort ist sie unsichtbar, das Halten spart nur Anlegen und Abraeumen).
     *  * Nahstufe wie bei [select].
     *  * [TreeBin.active] heisst hier: der gebackene Abschnitt liegt **ganz** innerhalb des Rings und
     *    kann abgeschaltet werden (reine Ersparnis — sein Material zeichnete dort ohnehin nichts).
     *
     * [limitEndM] deckelt `endM` (die Darstellung laesst den Ring nach dem Laden wachsen statt
     * aufzuspringen). `null`, wenn [budget] `null` ist — dann ist alles gebacken.
     */
    fun selectFading(
        bins: Collection<TreeBin>,
        camX: Double,
        camZ: Double,
        budget: TreeInstanceBudget?,
        limitEndM: Double = Double.MAX_VALUE,
    ): TreeFade? {
        if (budget == null) {
            for (b in bins) deactivate(b)
            return null
        }
        val h = TreeInstanceBudget.HYSTERESIS_M
        val all = ArrayList<TreePlace>()
        for (b in bins) {
            for (t in b.trees) {
                val dx = t.x - camX
                val dz = t.z - camZ
                t.dist = sqrt(dx * dx + dz * dz)
                t.edge = (t.dist - t.reachM).coerceAtLeast(0.0)
                all += t
            }
        }
        all.sortBy { it.edge }
        var target = budget.midM
        if (all.size > budget.maxTotal) target = minOf(target, all[budget.maxTotal].edge)
        val end = minOf(target, limitEndM).coerceAtLeast(0.0)
        val start = end - minOf(TreeInstanceBudget.FADE_BAND_M, end * TreeInstanceBudget.FADE_BAND_SHARE)
        val shown = ArrayList<TreePlace>()
        for ((i, t) in all.withIndex()) {
            val limit = if (t.lod != null) end + h else end
            if (i < budget.maxTotal && t.edge <= limit) shown += t else t.lod = null
        }
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
        for (b in bins) {
            b.active = b.trees.isNotEmpty() &&
                b.trees.all { it.lod != null && it.dist + it.reachM + TreeInstanceBudget.BAKED_EXTRA_M <= start }
        }
        return TreeFade(start, end, target)
    }

    private fun deactivate(b: TreeBin) {
        if (!b.active && b.trees.all { it.lod == null }) return
        b.active = false
        for (t in b.trees) t.lod = null
    }
}
