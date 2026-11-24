package de.trailscape.app.indoor.render

import de.trailscape.app.indoor.render.WorldMaterialRules.ALPHA_BLEND
import de.trailscape.app.indoor.render.WorldMaterialRules.ALPHA_MASK
import de.trailscape.app.indoor.render.WorldMaterialRules.ALPHA_OPAQUE
import de.trailscape.app.indoor.render.WorldMaterialRules.Kind
import de.trailscape.core.indoor.world.WorldModels
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Eigene Materialien (Regeln), andere Radfahrer, Voegel und Trikotfarben. Schreibt die
 * umgefaerbten Fahrer und die Voegel nach `app/build/indoor/` — der Filament-Referenzrenderer
 * (`filashots.mjs --riders`, `--birds`) nimmt sie von dort.
 */
class LifeAndMaterialsTest {
    // ------------------------------------------------------------------ Materialien

    @Test
    fun `Materialtausch nach Name und Mischung`() {
        assertEquals(Kind.TERRAIN, WorldMaterialRules.kindFor("mat_terrain", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("mat_terrain", ALPHA_OPAQUE, false, false))
        assertEquals(Kind.FOLIAGE, WorldMaterialRules.kindFor("x_mat_tree_cards", ALPHA_MASK, true, false))
        assertEquals(Kind.FOLIAGE, WorldMaterialRules.kindFor("mat_tree_leaves", ALPHA_MASK, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("mat_tree_trunk", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.SURFACE, WorldMaterialRules.kindFor("mat_route_asphalt", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.SURFACE, WorldMaterialRules.kindFor("x_mat_road_gravel", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_road_gravel_edge", ALPHA_BLEND, true, false))
        assertEquals(Kind.SURFACE, WorldMaterialRules.kindFor("x_mat_route_verge_grass", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.SURFACE_BLEND, WorldMaterialRules.kindFor("x_mat_route_verge_forest_fade", ALPHA_BLEND, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_route_asphalt_fade", ALPHA_MASK, true, false))
        assertEquals(Kind.SURFACE_BLEND, WorldMaterialRules.kindFor("x_mat_route_fringe", ALPHA_BLEND, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("mat_building_wall", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_kit", ALPHA_OPAQUE, false, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor(null, ALPHA_OPAQUE, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("mat_terrain", ALPHA_OPAQUE, true, true))
    }

    @Test
    fun `Bodenart der Randstreifen und Atlas des Laubs`() {
        assertEquals(1f, WorldMaterialRules.detailClass("x_mat_route_verge_grass")[0])
        assertEquals(1f, WorldMaterialRules.detailClass("x_mat_route_verge_forest_fade")[2])
        assertEquals(0f, WorldMaterialRules.detailClass("mat_route_asphalt").sum())
        assertEquals(1f, WorldMaterialRules.detailClass("x_mat_route_shoulder").sum(), 1e-6f)
        assertEquals(1f, WorldMaterialRules.atlasMode("x_mat_tree_cards"))
        assertEquals(0f, WorldMaterialRules.atlasMode("mat_tree_leaves"))
    }

    @Test
    fun `jedes eigene Material liegt als Paket in den Assets`() {
        for (k in Kind.entries) {
            val a = k.asset ?: continue
            assertTrue(File("src/main/assets/indoor/materials/$a.filamat").length() > 1000, "fehlt: $a.filamat")
            assertTrue(File("src/main/materials/$a.mat").exists(), "Quelle fehlt: $a.mat")
        }
    }

    // ------------------------------------------------------------------ andere Radfahrer

    private fun traffic(grade: (Double) -> Double = { 0.0 }, width: Double = 4.0, loop: Boolean = true, length: Double = 20_000.0) =
        OtherRiders(seed = 42, lengthM = length, loop = loop, gradePctAt = grade, widthAt = { width })

    @Test
    fun `Tempo je Steigung plausibel`() {
        val flat = OtherRiders.steadySpeed(200.0, 0.0)
        val up = OtherRiders.steadySpeed(200.0, 8.0)
        val down = OtherRiders.steadySpeed(200.0, -8.0)
        assertTrue(flat in 8.0..10.5, "flach $flat")
        assertTrue(up in 2.0..3.5, "8 % bergauf $up")
        assertTrue(down in 14.0..15.01, "8 % bergab $down")
        assertTrue(OtherRiders.steadySpeed(150.0, 0.0) < OtherRiders.steadySpeed(260.0, 0.0))
    }

    @Test
    fun `Fahrer bleiben im Fenster, kommen wieder, Zahl begrenzt`() {
        val t = traffic()
        t.maxCount = 4
        var player = 1000.0
        var maxSeen = 0
        var seen = 0
        repeat(20 * 60 * 10) { // 20 min mit 10 Bildern je Sekunde
            player += 8.0 * 0.1
            val s = t.update(0.1, player, 8.0, 0.88)
            maxSeen = maxOf(maxSeen, s.size)
            seen += s.size
            for (r in s) {
                var gap = r.distM - (player % 20_000.0)
                if (gap > 10_000) gap -= 20_000.0
                if (gap < -10_000) gap += 20_000.0
                assertTrue(gap > -OtherRiders.BEHIND_M - 30 && gap < OtherRiders.AHEAD_M + 50, "ausserhalb: $gap")
                assertTrue(r.speedMps in 1.0..15.01)
            }
        }
        assertTrue(maxSeen in 1..4, "hoechstens 4: $maxSeen")
        assertTrue(seen / (20 * 60 * 10.0) > 1.0, "im Mittel mehr als ein Fahrer sichtbar")
        t.maxCount = 0
        assertEquals(0, t.update(0.1, player, 8.0, 0.88).size)
    }

    @Test
    fun `beim Ueberholen seitlich versetzt, nie durch den Nutzer`() {
        val lane = laneOffsetM(4.0)
        for (playerSpeed in listOf(4.0, 13.0)) { // langsam: wird ueberholt; schnell: ueberholt selbst
            val t = traffic()
            t.maxCount = 6
            var player = 500.0
            var close = 0
            repeat(20 * 60 * 10) {
                player += playerSpeed * 0.1
                for (r in t.update(0.1, player, playerSpeed, lane)) {
                    if (r.oncoming) {
                        assertTrue(r.lateralM < 0, "Gegenverkehr links: ${r.lateralM}")
                        continue
                    }
                    val gap = r.distM - player
                    if (abs(gap) < 1.8) {
                        close++
                        assertTrue(abs(r.lateralM - lane) > 0.6, "Ueberlappung bei Abstand $gap: ${r.lateralM} gegen $lane")
                    }
                    assertTrue(abs(r.lateralM) <= 2.0 - OtherRiders.EDGE_MARGIN_M + 1e-6)
                }
            }
            assertTrue(close > 0, "es wurde nie ueberholt (Tempo $playerSpeed)")
        }
    }

    @Test
    fun `ohne Runde keine Fahrer jenseits der Enden`() {
        val t = traffic(loop = false, length = 3000.0)
        t.maxCount = 5
        var player = 2700.0
        repeat(600) {
            player = minOf(player + 0.8, 3000.0)
            for (r in t.update(0.1, player, 8.0, 0.8)) assertTrue(r.distM in -20.0..3020.0, "${r.distM}")
        }
    }

    // ------------------------------------------------------------------ Voegel

    @Test
    fun `Voegel kommen gelegentlich, fliegen hoch und vorn quer, gehen wieder`() {
        val f = BirdFlock(seed = 9)
        val center = V3(100.0, 50.0, -200.0)
        val fwd = V3(0.0, 0.0, -1.0)
        var flights = 0
        var wasFlying = false
        var framesFlying = 0
        val total = 10 * 60 * 20
        repeat(total) {
            val b = f.update(0.05, center, fwd)
            if (b.isNotEmpty()) {
                framesFlying++
                assertTrue(b.size <= BirdFlock.MAX_BIRDS)
                for (x in b) {
                    assertTrue(x.position.y - center.y in 20.0..60.0, "Hoehe ${x.position.y - center.y}")
                    assertTrue(abs(x.flapRad) <= 0.8)
                }
            }
            if (f.flying && !wasFlying) flights++
            wasFlying = f.flying
        }
        assertTrue(flights in 4..20, "Fluege in 10 min: $flights")
        assertTrue(framesFlying < total * 0.6, "nicht dauernd Voegel")
        assertEquals(0, BirdFlock(seed = 1).update(500.0, center, fwd, maxBirds = 0).size)
    }

    @Test
    fun `Vogelmodell mit zwei Fluegeln je Vogel`() {
        val glb = BirdModel.buildGlb()
        val json = String(glb, 20, java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12))
        for (i in 0 until BirdFlock.MAX_BIRDS) {
            assertTrue(BirdModel.wing(i, true) in json && BirdModel.wing(i, false) in json)
        }
        File("build/indoor").apply { mkdirs() }.resolve("birds.glb").writeBytes(glb)
    }

    // ------------------------------------------------------------------ Trikots

    @Test
    fun `Trikot umgefaerbt, Hose und Haut bleiben`() {
        val src = File("src/main/assets/${WorldModels.ASSET_DIR}/${WorldModels.RIDER}").readBytes()
        val blue = RiderRecolor.recolorOrThrow(src, RiderRecolor.JERSEYS[0])
        assertEquals(src.size, blue.size)
        var changed = 0
        var redLeft = 0
        // Nur der Binaerblock darf sich aendern, und dort nur Trikotrot
        for (i in src.indices) if (src[i] != blue[i]) changed++
        assertTrue(changed > 300, "zu wenig umgefaerbt: $changed")
        for (i in 0 until src.size - 3 step 1) {
            // grobe Probe: kein reines Trikotrot mehr in Folgen, die vorher Trikotrot waren
            if (RiderRecolor.isJersey(src[i].toInt() and 0xFF, src[i + 1].toInt() and 0xFF, src[i + 2].toInt() and 0xFF) &&
                src[i] != blue[i] && RiderRecolor.isJersey(blue[i].toInt() and 0xFF, blue[i + 1].toInt() and 0xFF, blue[i + 2].toInt() and 0xFF)
            ) {
                redLeft++
            }
        }
        assertEquals(0, redLeft)
        val out = File("build/indoor").apply { mkdirs() }
        RiderRecolor.JERSEYS.forEachIndexed { k, c -> File(out, "rider_$k.glb").writeBytes(RiderRecolor.recolor(src, c)) }
        // Kaputte Eingabe: unveraendert zurueck
        val junk = ByteArray(40) { 1 }
        assertTrue(RiderRecolor.recolor(junk, RiderRecolor.JERSEYS[1]).contentEquals(junk))
    }
}
