package de.trailscape.core.indoor.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vertrag der Instanzknoten ([TreeInstanceNames]) und Auswahl je Bild ([TreeInstancePlanner]). */
class TreeInstancesTest {
    private fun place(x: Double, z: Double = 0.0) = TreePlace(x, 0.0, z, 0.0, 1.0, TreeKind.BEECH, 0)

    /** Abschnitt mit [n] Baeumen ab [x0], alle 4 m. */
    private fun bin(x0: Double, n: Int = 4) = TreeBin((0 until n).map { place(x0 + it * 4.0) })

    private val ultra = TreeInstanceBudget.forDetail(2)!!

    @Test
    fun `Namen der Knoten lassen sich fuer jede Art zuruecklesen`() {
        for (k in TreeKind.entries) for (v in 0 until TreeLibrary.VARIANTS) for (b in listOf(0, 7, 1234)) {
            assertEquals(Triple(k, v, b), TreeInstanceNames.parseInstance(TreeInstanceNames.instanceName(k, v, b)))
            for (l in TreeLod.entries) assertEquals(Triple(k, v, l), TreeLibrary.parseNodeName(TreeLibrary.nodeName(k, v, l)))
        }
        assertEquals(42, TreeInstanceNames.parseBin(TreeInstanceNames.binName(42)))
        assertNull(TreeInstanceNames.parseBin("x_trees_bin_"))
        assertNull(TreeInstanceNames.parseBin("trees"))
        assertNull(TreeInstanceNames.parseInstance("x_treei_palme_0_3"), "unbekannte Art: Abschnitt bleibt gebacken")
        assertNull(TreeInstanceNames.parseInstance("x_treei_beech_9_3"), "unbekannte Variante")
        assertNull(TreeInstanceNames.parseInstance("x_treei_beech_0"))
        assertNull(TreeInstanceNames.parseInstance(null))
        // Abschnitts- und Instanzknoten sind am Praefix zu unterscheiden.
        assertNull(TreeInstanceNames.parseInstance(TreeInstanceNames.binName(3)))
        assertNull(TreeInstanceNames.parseBin(TreeInstanceNames.instanceName(TreeKind.ALEPPO_PINE, 1, 3)))
    }

    @Test
    fun `Stufe 0 zeigt keine Instanzen, hoehere Stufen mehr und weiter`() {
        assertNull(TreeInstanceBudget.forDetail(0))
        assertNull(TreeInstanceBudget.forDetail(-1))
        val h = TreeInstanceBudget.forDetail(1)!!
        assertTrue(h.nearM < ultra.nearM && h.midM < ultra.midM && h.maxNear < ultra.maxNear && h.maxTotal < ultra.maxTotal)
        assertTrue(h.nearM < h.midM && h.maxNear < h.maxTotal)
        // Obergrenze des Einbauplans: 16 nah + 40 mittel.
        assertEquals(16, ultra.maxNear)
        assertEquals(56, ultra.maxTotal)
    }

    @Test
    fun `ein Abschnitt wird als Ganzes umgeschaltet`() {
        val near = bin(10.0)
        val far = bin(200.0)
        val bins = listOf(near, far)
        TreeInstancePlanner.select(bins, 0.0, 0.0, ultra)
        assertTrue(near.active)
        assertTrue(near.trees.all { it.lod != null }, "aktiver Abschnitt: jeder Baum hat eine Instanz")
        assertFalse(far.active)
        assertTrue(far.trees.all { it.lod == null }, "gebackener Abschnitt: keine Instanz")
        // 10, 14, 18, 22 m: alle in der Nahstufe (25 m)
        assertTrue(near.trees.all { it.lod == TreeLod.NEAR })
        // Baeume eines aktiven Abschnitts jenseits der Nahgrenze: mittlere Stufe, auch hinter midM
        val long = TreeBin(listOf(place(20.0), place(40.0), place(70.0)))
        TreeInstancePlanner.select(listOf(long), 0.0, 0.0, ultra)
        assertEquals(listOf(TreeLod.NEAR, TreeLod.MID, TreeLod.MID), long.trees.map { it.lod })
    }

    @Test
    fun `ohne Budget ist alles gebacken`() {
        val b = bin(5.0)
        TreeInstancePlanner.select(listOf(b), 0.0, 0.0, ultra)
        assertTrue(b.active)
        TreeInstancePlanner.select(listOf(b), 0.0, 0.0, null)
        assertFalse(b.active)
        assertTrue(b.trees.all { it.lod == null })
    }

    @Test
    fun `Obergrenzen halten, die naechsten Abschnitte und Baeume gehen vor`() {
        // 8 Abschnitte je 10 Baeume in einer Reihe, alle in Reichweite (bis 60 m)
        val bins = (0 until 8).map { i -> TreeBin((0 until 10).map { place(2.0 + i * 7.0, it * 0.5) }) }
        TreeInstancePlanner.select(bins, 0.0, 0.0, ultra)
        val active = bins.filter { it.active }
        assertEquals(5, active.size, "56 / 10 Baeume je Abschnitt")
        assertEquals(bins.take(5), active, "von der Kamera nach aussen")
        val shown = active.flatMap { it.trees }
        assertTrue(shown.size <= ultra.maxTotal)
        assertEquals(ultra.maxNear, shown.count { it.lod == TreeLod.NEAR })
        val farthestNear = shown.filter { it.lod == TreeLod.NEAR }.maxOf { it.x }
        val nearestMid = shown.filter { it.lod == TreeLod.MID }.minOf { it.x }
        assertTrue(farthestNear <= nearestMid, "nah $farthestNear, mittel $nearestMid")
        assertTrue(bins.drop(5).all { b -> b.trees.all { it.lod == null } })
    }

    @Test
    fun `der naechste Abschnitt wird auch instanziert, wenn er allein die Obergrenze sprengt`() {
        val big = TreeBin((0 until 80).map { place(5.0 + it * 0.3) })
        val next = bin(40.0)
        TreeInstancePlanner.select(listOf(big, next), 0.0, 0.0, ultra)
        assertTrue(big.active, "sonst stuenden direkt an der Kamera nur gebackene Baeume")
        assertFalse(next.active)
        assertEquals(ultra.maxNear, big.trees.count { it.lod == TreeLod.NEAR })
    }

    @Test
    fun `Hysterese haelt Abschnitt und Nahstufe hinter der Grenze`() {
        val h = TreeInstanceBudget.HYSTERESIS_M
        val b = TreeBin(listOf(place(0.0)))
        // Kamera entfernt sich: erst hinter midM + Hysterese wird zurueckgeschaltet
        TreeInstancePlanner.select(listOf(b), ultra.midM + 1.0, 0.0, ultra)
        assertFalse(b.active, "von aussen kommend: erst ab midM")
        TreeInstancePlanner.select(listOf(b), ultra.midM - 0.5, 0.0, ultra)
        assertTrue(b.active)
        TreeInstancePlanner.select(listOf(b), ultra.midM + h - 0.5, 0.0, ultra)
        assertTrue(b.active, "bleibt innerhalb der Hysterese")
        TreeInstancePlanner.select(listOf(b), ultra.midM + h + 0.5, 0.0, ultra)
        assertFalse(b.active)

        TreeInstancePlanner.select(listOf(b), ultra.nearM + 1.0, 0.0, ultra)
        assertEquals(TreeLod.MID, b.trees[0].lod)
        TreeInstancePlanner.select(listOf(b), ultra.nearM - 0.5, 0.0, ultra)
        assertEquals(TreeLod.NEAR, b.trees[0].lod)
        TreeInstancePlanner.select(listOf(b), ultra.nearM + h - 0.5, 0.0, ultra)
        assertEquals(TreeLod.NEAR, b.trees[0].lod)
        TreeInstancePlanner.select(listOf(b), ultra.nearM + h + 0.5, 0.0, ultra)
        assertEquals(TreeLod.MID, b.trees[0].lod)
    }

    @Test
    fun `Auswahl ist stabil, wenn sich nichts bewegt`() {
        val bins = (0 until 12).map { i -> TreeBin((0 until 9).map { place(3.0 + i * 5.0, it * 1.5 - 6.0) }) }
        TreeInstancePlanner.select(bins, 0.0, 0.0, ultra)
        val first = bins.map { b -> b.active to b.trees.map { it.lod } }
        repeat(3) { TreeInstancePlanner.select(bins, 0.0, 0.0, ultra) }
        assertEquals(first, bins.map { b -> b.active to b.trees.map { it.lod } })
    }

    @Test
    fun `leere Abschnitte und leere Welt stoeren nicht`() {
        TreeInstancePlanner.select(emptyList(), 0.0, 0.0, ultra)
        val empty = TreeBin(emptyList())
        TreeInstancePlanner.select(listOf(empty), 0.0, 0.0, ultra)
        assertFalse(empty.active)
    }
}
