package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.ShowInterval
import de.trailscape.core.indoor.world.WorldNames
import de.trailscape.core.indoor.world.WorldTile
import de.trailscape.core.indoor.world.worldTileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kachelauswahl nach `WorldTile.show`, Hysterese, Runden und Teilflaechen (docs/indoor.md 7.2). */
class TilePlannerTest {
    private fun tile(level: Int, ix: Int, iy: Int, vararg show: Pair<Double, Double>) = WorldTile(
        id = worldTileId(level, ix, iy),
        level = level,
        ix = ix,
        iy = iy,
        file = "tiles/${worldTileId(level, ix, iy)}.glb",
        bytes = 1000,
        anchorE = 0.0,
        anchorN = 0.0,
        anchorU = 0.0,
        minU = 0.0,
        maxU = 1.0,
        show = show.map { ShowInterval(it.first, it.second) },
        textureBytes = 10,
        triangles = 10,
    )

    private val a = tile(0, 0, 0, 0.0 to 300.0)
    private val b = tile(0, 1, 0, 250.0 to 600.0)
    private val c = tile(0, 2, 0, 550.0 to 1000.0)
    private val far = tile(2, 0, 0, 0.0 to 1000.0)
    private val mid = tile(1, 0, 0, 0.0 to 700.0)
    private val planner = TilePlanner(listOf(c, b, a, far, mid), lengthM = 1000.0, loop = false, lookaheadM = 200.0, hysteresisM = 50.0)

    @Test
    fun gebrauchtAnDerStelle() {
        assertEquals(setOf(a.id, far.id, mid.id), planner.neededNow(100.0).map { it.id }.toSet())
        assertEquals(setOf(a.id, b.id, far.id, mid.id), planner.neededNow(280.0).map { it.id }.toSet())
    }

    @Test
    fun reihenfolgeErstDieStelleDannVorlaufFeinZuerst() {
        val w = planner.wanted(100.0).map { it.id }
        // Stelle: a (Stufe 0), mid (1), far (2); dann Vorlauf: b (bei 250 m).
        assertEquals(listOf(a.id, mid.id, far.id, b.id), w)
        assertFalse(c.id in w)
    }

    @Test
    fun naechsteKachelUeberspringtGeladeneUndGescheiterte() {
        assertEquals(a.id, planner.nextToLoad(100.0, emptySet(), emptySet())?.id)
        assertEquals(mid.id, planner.nextToLoad(100.0, setOf(a.id), emptySet())?.id)
        assertEquals(b.id, planner.nextToLoad(100.0, setOf(a.id, far.id), setOf(mid.id))?.id)
        assertNull(planner.nextToLoad(100.0, setOf(a.id, far.id, mid.id, b.id), emptySet()))
    }

    @Test
    fun entladenMitHysterese() {
        // Bei 320 m braucht niemand a mehr (bis 300), aber die Hysterese von 50 m haelt sie.
        assertTrue(planner.keep(a, 320.0))
        assertFalse(planner.keep(a, 360.0))
        assertEquals(listOf(a.id), planner.toUnload(360.0, listOf(a.id, b.id)))
        // Unbekannte Kacheln (andere Welt) fliegen raus.
        assertEquals(listOf("L0_9_9"), planner.toUnload(100.0, listOf("L0_9_9")))
    }

    @Test
    fun rundeFaltetDasFenster() {
        val start = tile(0, 0, 0, 0.0 to 100.0)
        val end = tile(0, 5, 5, 900.0 to 1000.0)
        val loop = TilePlanner(listOf(start, end), lengthM = 1000.0, loop = true, lookaheadM = 200.0, hysteresisM = 20.0)
        // Kurz vor dem Ziel liegt der Start im Vorlauf.
        assertTrue(start.id in loop.wanted(950.0).map { it.id })
        // In Runde 3 ist alles wie in Runde 1.
        assertEquals(loop.neededNow(50.0).map { it.id }, loop.neededNow(2050.0).map { it.id })
        // Kurz nach dem Start haelt die Hysterese das Ende der vorigen Runde.
        assertTrue(loop.keep(end, 1010.0))
        assertFalse(loop.keep(end, 1100.0))
    }

    @Test
    fun offeneStreckeAmEndeBegrenzt() {
        assertEquals(setOf(c.id, far.id), planner.neededNow(5000.0).map { it.id }.toSet())
        assertEquals(setOf(a.id, far.id, mid.id), planner.neededNow(-10.0).map { it.id }.toSet())
    }

    @Test
    fun teilflaechenNurUnterGezeigtenFeinerenKacheln() {
        val coarse = tile(1, 0, 0)
        val hidden = TilePlanner.hiddenSubs(coarse, setOf(worldTileId(0, 1, 2), worldTileId(0, 3, 3), worldTileId(0, 4, 0)))
        assertEquals(setOf(WorldNames.terrainSubName(1, 2), WorldNames.terrainSubName(3, 3)), hidden)
        // Stufe 0 hat keine Teilflaechen.
        assertTrue(TilePlanner.hiddenSubs(tile(0, 0, 0), setOf(worldTileId(0, 0, 0))).isEmpty())
    }

    @Test
    fun teilflaechenBeiNegativenIndizes() {
        // Kachel (−1, −1) der Stufe 2 deckt die Stufe-1-Kacheln −4..−1.
        val coarse = tile(2, -1, -1)
        val hidden = TilePlanner.hiddenSubs(coarse, setOf(worldTileId(1, -4, -1), worldTileId(1, 0, 0)))
        assertEquals(setOf(WorldNames.terrainSubName(0, 3)), hidden)
    }

    @Test
    fun knotenarten() {
        assertEquals(WorldNames.NODE_TREES, nodeCategory(listOf("x_mesh", WorldNames.NODE_TREES, null)))
        assertEquals("terrain_sub_1_2", nodeCategory(listOf("terrain_sub_1_2", WorldNames.NODE_TERRAIN)))
        assertEquals(WorldNames.NODE_TERRAIN, baseCategory("terrain_sub_1_2"))
        assertNull(nodeCategory(listOf("x_rider", null)))
        val safe = de.trailscape.core.indoor.world.RenderSettings.SAFE
        assertEquals(safe.trees, nodeVisible(WorldNames.NODE_TREES, safe))
        assertTrue(nodeVisible(WorldNames.NODE_TERRAIN, safe))
        assertEquals(true to true, shadowFlags(WorldNames.NODE_BUILDINGS))
        assertEquals(false to true, shadowFlags(WorldNames.NODE_TERRAIN))
    }
}
