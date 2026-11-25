package de.trailscape.app.indoor.render

import de.trailscape.app.indoor.render.WorldMaterialRules.ALPHA_BLEND
import de.trailscape.app.indoor.render.WorldMaterialRules.ALPHA_OPAQUE
import de.trailscape.app.indoor.render.WorldMaterialRules.Kind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Streckenband (docs/indoor/luftbild-band.md): Materialtausch und `bandCell` aus dem Namen. */
class TerrainBandRulesTest {
    @Test
    fun `Bandfelder bekommen das Bandmaterial, kaputte Namen den Ubershader`() {
        assertEquals(Kind.TERRAIN_BAND, WorldMaterialRules.kindFor("x_mat_terrain_band_8_3_5", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_terrain_band_8_3_5", ALPHA_BLEND, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_terrain_band_8_3_5", ALPHA_OPAQUE, false, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_terrain_band_8_9_5", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.UBER, WorldMaterialRules.kindFor("x_mat_terrain_band_x", ALPHA_OPAQUE, true, false))
        assertEquals(Kind.TERRAIN, WorldMaterialRules.kindFor("mat_terrain", ALPHA_OPAQUE, true, false))
    }

    @Test
    fun `bandCell aus dem Namen`() {
        assertContentEquals(floatArrayOf(3f, 5f, 8f), WorldMaterialRules.bandCell("x_mat_terrain_band_8_3_5"))
        assertContentEquals(floatArrayOf(0f, 0f, 1f), WorldMaterialRules.bandCell("x_mat_terrain_band_1_0_0"))
        assertNull(WorldMaterialRules.bandCell("x_mat_terrain_band_8_3"))
        assertNull(WorldMaterialRules.bandCell("x_mat_terrain_band_0_0_0"))
        assertNull(WorldMaterialRules.bandCell("mat_terrain"))
        assertNull(WorldMaterialRules.bandCell(null))
    }
}
