package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.SurfaceKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PBR-Texturen: Regeln, `pbr.json` und die KTX-Dateien aus `pbr.py`, Grenzen fuer App-Groesse
 * und Grafikspeicher, und dass die Materialien jeden Parameter deklarieren, den die App setzt
 * (Issue 106: ein nicht deklarierter Parameter beendet Filament im Release nativ).
 */
class PbrRulesTest {
    private val dir = File("src/main/assets/${PbrRules.DIR}")
    private val spec = assertNotNull(PbrRules.parse(File(dir, PbrRules.META).readText()), "pbr.json nicht lesbar")

    @Test
    fun `jeder gezeichnete Belag hat einen Satz`() {
        for (k in SurfaceKind.entries) {
            val key = k.visual.textureKey
            val kind = assertNotNull(spec.kinds[key], "kein PBR-Satz fuer $key")
            assertTrue(kind.meters in 0.5f..5f, "$key: meters ${kind.meters}")
            assertTrue(kind.roughBias >= 0.2f && kind.roughBias + kind.roughScale <= 1.05f, "$key: Rauheit")
            assertTrue(kind.replace in 0f..1f && kind.albedo in 0f..1f && kind.normal in 0f..3f, key)
        }
        for (v in listOf("verge_grass", "verge_forest", "verge_shoulder", "shoulder", "sidewalk", "fringe")) {
            assertNotNull(spec.kinds[v], "kein PBR-Satz fuer Randstreifen $v")
        }
        val t = assertNotNull(spec.terrain, "Gelaende fehlt")
        // ganzzahlige Wiederholungen je Kachel: nahtlos ueber Kachelgrenzen
        assertTrue(t.repeats.all { it == Math.round(it).toFloat() && it >= 3f }, t.repeats.joinToString())
        assertTrue(spec.rangeM in 10f..80f && spec.terrainRangeM in 10f..120f)
    }

    @Test
    fun `KTX-Dateien passen zu pbr json`() {
        var total = 0L
        for ((name, g) in spec.groups) {
            for ((file, fmt) in listOf(g.color to PbrRules.GL_ETC2_SRGBA8, g.normal to PbrRules.GL_EAC_RG11)) {
                val bytes = File(dir, file).readBytes()
                val k = assertNotNull(PbrRules.readKtx(bytes), "$file nicht lesbar")
                assertEquals(fmt, k.glInternalFormat, file)
                assertEquals(g.size, k.size, file)
                assertEquals(g.layers, k.layers, file)
                // volle Mip-Kette bis 1×1
                assertEquals(Integer.numberOfTrailingZeros(g.size) + 1, k.levels.size, file)
                total += bytes.size
            }
            assertTrue(g.size in setOf(256, 512, 1024), "$name: ${g.size}")
        }
        // Ziel: hoechstens +25 MB App (unkomprimiert gemessen; im APK etwas kleiner)
        assertTrue(total < 25L * 1024 * 1024, "zusammen $total Bytes")
    }

    @Test
    fun `kaputte KTX werden abgelehnt`() {
        val g = spec.groups.getValue("concrete")
        val bytes = File(dir, g.color).readBytes()
        assertNull(PbrRules.readKtx(bytes.copyOf(bytes.size - 100)), "abgeschnitten")
        assertNull(PbrRules.readKtx(bytes.copyOf(40)), "nur Kopf")
        val bad = bytes.copyOf()
        bad[28] = 0x58  // anderes Format
        assertNull(PbrRules.readKtx(bad))
    }

    @Test
    fun `Schluessel aus dem Materialnamen`() {
        val keys = spec.kinds.keys
        assertEquals("asphalt", PbrRules.keyFor("mat_route_asphalt", keys))
        assertEquals("cobble", PbrRules.keyFor("mat_route_cobble", keys))
        assertEquals("gravel", PbrRules.keyFor("x_mat_road_gravel", keys))
        assertEquals("compacted", PbrRules.keyFor("x_mat_route_compacted_fade", keys))
        assertEquals("verge_grass", PbrRules.keyFor("x_mat_route_verge_grass", keys))
        assertEquals("verge_forest", PbrRules.keyFor("x_mat_route_verge_forest_fade", keys))
        assertEquals("shoulder", PbrRules.keyFor("x_mat_route_shoulder", keys))
        assertEquals("fringe", PbrRules.keyFor("x_mat_route_fringe", keys))
        assertNull(PbrRules.keyFor("x_mat_road_gravel_edge", keys))
        assertNull(PbrRules.keyFor("x_mat_road_kerb", keys))
        assertNull(PbrRules.keyFor("x_mat_road_island", keys))
        assertNull(PbrRules.keyFor("x_mat_route_plain", keys))
        assertNull(PbrRules.keyFor("mat_terrain", keys))
        assertNull(PbrRules.keyFor(null, keys))
    }

    @Test
    fun `aus heisst alle Parameter null`() {
        val k = spec.kinds.getValue("asphalt")
        for (v in spec.surfaceParams(k, on = false)) assertTrue(v.all { it == 0f })
        for (v in spec.terrainParams(on = false)) assertTrue(v.all { it == 0f })
        val on = spec.surfaceParams(k, on = true)
        assertEquals(spec.rangeM, on[0][0])
        assertEquals(k.meters, on[1][0])
        assertEquals(k.layer.toFloat(), on[1][1])
        assertEquals(spec.terrainRangeM, spec.terrainParams(on = true)[0][0])
    }

    @Test
    fun `kaputtes pbr json heisst aus`() {
        assertNull(PbrRules.parse("{"))
        assertNull(PbrRules.parse("{}"))
        // Pfade in Dateinamen werden verworfen
        val text = File(dir, PbrRules.META).readText().replace("\"asphalt_c.ktx\"", "\"../x.ktx\"")
        val s = assertNotNull(PbrRules.parse(text))
        assertNull(s.groups["asphalt"])
        assertNull(s.kinds["asphalt"])
    }

    @Test
    fun `Materialien deklarieren alle PBR-Parameter`() {
        val mats = File("src/main/materials")
        for (m in listOf("surface", "surface_blend")) {
            val src = File(mats, "$m.mat").readText()
            for (p in listOf("pbrMapC", "pbrMapN", "pbrA", "pbrB", "pbrC", "pbrD")) assertTrue("name : $p" in src, "$m.mat: $p fehlt")
            assertTrue("sampler2dArray, name : pbrMapC" in src)
        }
        val t = File(mats, "terrain.mat").readText()
        for (p in listOf("pbrMapC", "pbrMapN", "pbrA", "pbrB", "pbrC")) assertTrue("name : $p" in t, "terrain.mat: $p fehlt")
        assertTrue("sampler2dArray, name : pbrMapN" in t)
    }
}
