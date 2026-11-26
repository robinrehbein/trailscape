package de.trailscape.core.indoor.tree

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wechselring ([TreeInstancePlanner.selectFading]): der Wechsel gebackener Baum ↔ Instanz ist ein
 * Ring um die Kamera. Geprueft wird, was die Darstellung braucht, damit nie ein Loch entsteht und
 * nichts springt: jede Krone im Ring hat eine Instanz, der Ring haengt stetig an der Kamera, und
 * ein gebackener Abschnitt wird nur abgeschaltet, wenn er ganz innerhalb liegt.
 */
class TreeFadeTest {
    private fun place(x: Double, z: Double = 0.0, reach: Double = 4.0) =
        TreePlace(x, 0.0, z, 0.0, 1.0, TreeKind.BEECH, 0).also { it.reachM = reach }

    private val ultra = TreeInstanceBudget.forDetail(2)!!
    private val high = TreeInstanceBudget.forDetail(1)!!
    private val band = TreeInstanceBudget.FADE_BAND_M
    private val h = TreeInstanceBudget.HYSTERESIS_M

    /** Ein Waldstueck: Baeume beidseits einer Strecke entlang x, je 32 m ein Abschnitt. */
    private fun forest(lengthM: Int, everyM: Double, reach: Double = 4.0): List<TreeBin> {
        val bins = HashMap<Int, ArrayList<TreePlace>>()
        var x = -lengthM / 2.0
        var i = 0
        while (x < lengthM / 2.0) {
            val z = (if (i % 2 == 0) 1 else -1) * (5.0 + (i * 37 % 13))
            bins.getOrPut(Math.floorDiv(x.toInt(), 32)) { ArrayList() } += place(x, z, reach)
            x += everyM
            i++
        }
        return bins.values.map { TreeBin(it) }
    }

    @Test
    fun `ohne Budget kein Ring und alles gebacken`() {
        val b = TreeBin(listOf(place(5.0)))
        assertNotNull(TreeInstancePlanner.selectFading(listOf(b), 0.0, 0.0, ultra))
        assertNotNull(b.trees[0].lod)
        assertNull(TreeInstancePlanner.selectFading(listOf(b), 0.0, 0.0, null))
        assertFalse(b.active)
        assertNull(b.trees[0].lod)
    }

    @Test
    fun `wenige Baeume, der Ring liegt bei midM und ist FADE_BAND_M breit`() {
        val bins = listOf(TreeBin(listOf(place(10.0), place(30.0), place(58.0), place(70.0), place(200.0))))
        for (budget in listOf(high, ultra)) {
            val ring = TreeInstancePlanner.selectFading(bins, 0.0, 0.0, budget)!!
            assertEquals(budget.midM, ring.endM, 1e-9)
            assertEquals(budget.midM - band, ring.startM, 1e-9)
            assertFalse(ring.growing)
        }
        val trees = bins[0].trees
        TreeInstancePlanner.selectFading(bins, 0.0, 0.0, ultra)
        assertEquals(TreeLod.NEAR, trees[0].lod)
        assertEquals(TreeLod.MID, trees[1].lod)
        assertEquals(TreeLod.MID, trees[2].lod)
        assertNull(trees[3].lod, "Krone (70 − 4 m) erreicht den Ring (60 m) nicht")
        assertNull(trees[4].lod)
    }

    @Test
    fun `ein Baum bekommt seine Instanz, bevor seine Krone in den Ring ragt`() {
        // Fusspunkt 8 m hinter dem Ring, Krone 10 m ausladend: ragt 2 m hinein
        val wide = place(ultra.midM + 8.0, reach = 10.0)
        val slim = place(0.0, ultra.midM + 8.0, reach = 2.0)
        TreeInstancePlanner.selectFading(listOf(TreeBin(listOf(wide, slim))), 0.0, 0.0, ultra)
        assertEquals(TreeLod.MID, wide.lod)
        assertNull(slim.lod)
        // Ausladung unbekannt (Bibliothek noch nicht gelesen): vorsichtige Vorgabe = breiteste Krone
        val unknown = TreePlace(ultra.midM + 18.0, 0.0, 0.0, 0.0, 1.0, TreeKind.OAK, 0)
        assertEquals(TreePlace.DEFAULT_REACH_M, unknown.reachM)
        TreeInstancePlanner.selectFading(listOf(TreeBin(listOf(unknown))), 0.0, 0.0, ultra)
        assertNotNull(unknown.lod)
    }

    @Test
    fun `im Wald haelt die Obergrenze und der Ring rueckt naeher, ohne Loch`() {
        val bins = forest(800, 1.5)
        for (budget in listOf(high, ultra)) for (cam in listOf(0.0, 13.7, -101.3, 250.0)) {
            TreeInstancePlanner.selectFading(bins, 0.0, 0.0, null)
            val ring = TreeInstancePlanner.selectFading(bins, cam, 0.0, budget)!!
            val all = bins.flatMap { it.trees }
            val shown = all.filter { it.lod != null }
            assertTrue(shown.size <= budget.maxTotal, "${shown.size} Instanzen")
            assertTrue(shown.count { it.lod == TreeLod.NEAR } <= budget.maxNear)
            assertTrue(ring.endM < budget.midM, "dichter Wald: Ring naeher als midM (${ring.endM})")
            assertTrue(ring.endM > 10.0, "aber nicht weg (${ring.endM})")
            assertTrue(ring.startM in 0.0..ring.endM)
            assertTrue(ring.endM - ring.startM <= band + 1e-9)
            // Kein Loch: jeder Baum, dessen Krone in den Ring ragt, hat eine Instanz
            for (t in all) {
                val edge = hypot(t.x - cam, t.z) - t.reachM
                if (edge < ring.endM - 1e-6) assertNotNull(t.lod, "Baum bei ${t.x}/${t.z}, Krone ab $edge m, Ring ${ring.endM}")
            }
            // Abgeschaltete Abschnitte liegen ganz innerhalb des Rings (mit Zuschlag fuer die gebackene Krone)
            for (b in bins.filter { it.active }) for (t in b.trees) {
                assertTrue(hypot(t.x - cam, t.z) + t.reachM + TreeInstanceBudget.BAKED_EXTRA_M <= ring.startM + 1e-9)
            }
            assertTrue(bins.any { !it.active })
        }
    }

    @Test
    fun `der Ring haengt stetig an der Kamera`() {
        // Fahrt durch Wald wechselnder Dichte: je 0,5 m Weg aendert sich der Ring um hoechstens 0,5 m
        // (Abstaende aendern sich hoechstens um den Weg der Kamera) — kein Sprung beim Ueberfahren von Abschnittsgrenzen.
        val bins = forest(400, 1.5) + forest(400, 6.0).map { b -> TreeBin(b.trees.map { place(it.x + 400.0, it.z, it.reachM) }) }
        var last: TreeFade? = null
        var x = -150.0
        var minEnd = Double.MAX_VALUE
        var maxEnd = 0.0
        while (x < 550.0) {
            val ring = TreeInstancePlanner.selectFading(bins, x, 0.0, ultra)!!
            last?.let { assertTrue(abs(ring.endM - it.endM) <= 0.5 + 1e-6, "Sprung bei x=$x: ${it.endM} → ${ring.endM}") }
            last = ring
            minEnd = minOf(minEnd, ring.endM)
            maxEnd = maxOf(maxEnd, ring.endM)
            x += 0.5
        }
        assertTrue(minEnd < 40.0 && maxEnd == ultra.midM, "Ring zwischen $minEnd und $maxEnd m")
    }

    @Test
    fun `Instanzen bleiben bis zur Hysterese hinter dem Ring, neue entstehen erst am Ring`() {
        val t = place(0.0, reach = 0.0)
        val bins = listOf(TreeBin(listOf(t)))
        TreeInstancePlanner.selectFading(bins, ultra.midM + 1.0, 0.0, ultra)
        assertNull(t.lod, "von aussen kommend: erst am Ring")
        TreeInstancePlanner.selectFading(bins, ultra.midM - 0.5, 0.0, ultra)
        assertEquals(TreeLod.MID, t.lod)
        TreeInstancePlanner.selectFading(bins, ultra.midM + h - 0.5, 0.0, ultra)
        assertEquals(TreeLod.MID, t.lod, "hinter dem Ring unsichtbar, bleibt aber stehen")
        TreeInstancePlanner.selectFading(bins, ultra.midM + h + 0.5, 0.0, ultra)
        assertNull(t.lod)
        // Nahstufe mit derselben Hysterese wie beim Wechsel je Abschnitt
        TreeInstancePlanner.selectFading(bins, ultra.nearM - 0.5, 0.0, ultra)
        assertEquals(TreeLod.NEAR, t.lod)
        TreeInstancePlanner.selectFading(bins, ultra.nearM + h - 0.5, 0.0, ultra)
        assertEquals(TreeLod.NEAR, t.lod)
        TreeInstancePlanner.selectFading(bins, ultra.nearM + h + 0.5, 0.0, ultra)
        assertEquals(TreeLod.MID, t.lod)
    }

    @Test
    fun `nach dem Laden waechst der Ring, statt aufzuspringen`() {
        val bins = forest(200, 8.0)
        var end = 0.0
        var steps = 0
        while (true) {
            val ring = TreeInstancePlanner.selectFading(bins, 0.0, 0.0, ultra, limitEndM = end + TreeFadeLook.GROW_M_PER_S / 60.0)!!
            assertTrue(ring.endM <= end + TreeFadeLook.GROW_M_PER_S / 60.0 + 1e-9)
            assertTrue(ring.endM >= end, "waechst nur")
            assertEquals(ultra.midM, ring.targetEndM, 1e-9)
            // Auch waehrend des Wachsens: keine Instanz fehlt im Ring, keine steht weit dahinter
            for (t in bins.flatMap { it.trees }) {
                val edge = hypot(t.x, t.z) - t.reachM
                if (edge < ring.endM - 1e-6) assertNotNull(t.lod)
                if (edge > ring.endM + h + 1e-6) assertNull(t.lod)
            }
            end = ring.endM
            steps++
            if (!ring.growing) break
            assertTrue(steps < 1000)
        }
        assertEquals(ultra.midM, end, 1e-9)
        assertTrue(steps in 150..200, "rund drei Sekunden bei 60 Bildern je Sekunde: $steps")
        // Schrumpfen (weniger Budget) geht sofort: sonst fehlten Instanzen
        val ring = TreeInstancePlanner.selectFading(bins, 0.0, 0.0, high, limitEndM = end + 1.0)!!
        assertEquals(high.midM, ring.endM, 1e-9)
    }

    @Test
    fun `sehr enger Ring bleibt gueltig`() {
        // 100 Baeume im Umkreis von 6 m: der Ring rueckt bis an die Kamera, die Breite schrumpft mit
        val crowd = TreeBin((0 until 100).map { place(1.0 + it * 0.05, 0.0, reach = 0.5) })
        val ring = TreeInstancePlanner.selectFading(listOf(crowd), 0.0, 0.0, ultra)!!
        assertTrue(ring.endM in 0.0..6.0)
        assertTrue(ring.startM >= ring.endM * (1 - TreeInstanceBudget.FADE_BAND_SHARE) - 1e-9 && ring.startM <= ring.endM)
        assertEquals(ultra.maxTotal, crowd.trees.count { it.lod != null })
        assertFalse(crowd.active)
        // leere Welt
        val empty = TreeInstancePlanner.selectFading(emptyList(), 0.0, 0.0, ultra)!!
        assertEquals(ultra.midM, empty.endM, 1e-9)
        val none = TreeBin(emptyList())
        TreeInstancePlanner.selectFading(listOf(none), 0.0, 0.0, ultra)
        assertFalse(none.active)
    }

    @Test
    fun `Auswahl ist stabil, wenn sich nichts bewegt`() {
        val bins = forest(300, 2.5)
        TreeInstancePlanner.selectFading(bins, 3.0, 1.0, ultra)
        val first = bins.map { b -> b.active to b.trees.map { it.lod } }
        repeat(3) { TreeInstancePlanner.selectFading(bins, 3.0, 1.0, ultra) }
        assertEquals(first, bins.map { b -> b.active to b.trees.map { it.lod } })
    }

    @Test
    fun `Wechsel zwischen beiden Verfahren hinterlaesst keinen Rest`() {
        val bins = forest(300, 4.0)
        TreeInstancePlanner.selectFading(bins, 0.0, 0.0, ultra)
        TreeInstancePlanner.select(bins, 0.0, 0.0, ultra)
        for (b in bins) assertEquals(b.active, b.trees.all { it.lod != null }, "je Abschnitt: alle oder keiner")
        TreeInstancePlanner.select(bins, 0.0, 0.0, null)
        assertTrue(bins.none { it.active } && bins.all { b -> b.trees.all { it.lod == null } })
    }

    @Test
    fun `Ausladung der Vorlagen aus der Bibliothek`() {
        val path = "app/src/main/assets/${TreeLibrary.ASSET_PATH}"
        val file = listOf(File("../$path"), File(path)).firstOrNull { it.isFile }
        assertNotNull(file, "Bibliothek fehlt: $path")
        val lib = TreeLibraryFile(file.readBytes())
        for (k in TreeKind.entries) for (v in 0 until TreeLibrary.VARIANTS) {
            val reach = lib.reachM(k, v)
            assertNotNull(reach, "$k/$v")
            // deckt beide Stufen: keine Ecke liegt weiter draussen
            for (lod in TreeLod.entries) {
                val t = lib.template(k, v, lod)!!
                for (p in listOfNotNull(t.bark, t.leaves)) for (i in 0 until p.vertexCount) {
                    assertTrue(hypot(p.positions[i * 3].toDouble(), p.positions[i * 3 + 2].toDouble()) <= reach + 1e-4)
                }
            }
            // Die Vorgabe deckt jede Vorlage auch im groessten Massstab (Variante passt bis Faktor 1,3)
            assertTrue(reach * 1.3 in 0.5..TreePlace.DEFAULT_REACH_M, "$k/$v: $reach m (Vorgabe ${TreePlace.DEFAULT_REACH_M} m muss alle decken)")
        }
        assertTrue(lib.reachM(TreeKind.SHRUB, 0)!! < lib.reachM(TreeKind.OAK, 0)!!)
    }

    @Test
    fun `Toenung hat geordnete Grenzen und die gemessene Richtung`() {
        // Die Grenzen muessen geordnet sein, sonst rechnet der Shader (smoothstep) Unsinn. Gebackene
        // Nadelzweige werden etwas dunkler, Laub heller, gewoehnliche Borke dunkler, Birkenstaemme heller.
        assertTrue(TreeFadeLook.LEAF_LUMA[0] < TreeFadeLook.LEAF_LUMA[1])
        assertTrue(TreeFadeLook.BARK_LUMA[0] < TreeFadeLook.BARK_LUMA[1])
        assertTrue(TreeFadeLook.LEAF_DARK.all { it > 0.5f && it <= 1f })
        assertTrue(TreeFadeLook.LEAF_LIGHT.all { it > 1f })
        assertTrue(TreeFadeLook.BARK_DARK.all { it < 1f } && TreeFadeLook.BARK_LIGHT.all { it > 1f })
        assertTrue(TreeFadeLook.TINT_FALLOFF_M > 0 && TreeFadeLook.GROW_M_PER_S > 0)
    }
}
