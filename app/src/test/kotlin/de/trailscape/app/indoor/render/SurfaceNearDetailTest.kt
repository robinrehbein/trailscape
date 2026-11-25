package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.SurfaceKind
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Nahdetail der Fahrbahn: Regeln, `detail.json` und die Karten aus `nahdetail.py`, und dass die
 * Belags-Materialien jeden Parameter deklarieren, den gltfio oder die App setzt (Issue 106: ein
 * nicht deklarierter Parameter beendet Filament im Release nativ).
 */
class SurfaceNearDetailTest {
    private val dir = File("src/main/assets/${SurfaceNearDetail.DIR}")
    private val spec = assertNotNull(SurfaceNearDetail.parse(File(dir, SurfaceNearDetail.META).readText()), "detail.json nicht lesbar")

    @Test
    fun `jeder gezeichnete Belag hat Nahdetail und eine vorhandene Karte`() {
        for (k in SurfaceKind.entries) {
            val key = k.visual.textureKey
            val kind = assertNotNull(spec.kinds[key], "kein Nahdetail fuer $key")
            val file = File(dir, assertNotNull(spec.maps[kind.map], "Karte ${kind.map} fehlt in maps"))
            assertTrue(file.isFile, "Karte fehlt: $file")
            val img = assertNotNull(ImageIO.read(file), "nicht lesbar: $file")
            // Zweierpotenz (Mip-Stufen), hoechstens 1024 (Grafikspeicher)
            assertTrue(img.width == img.height && img.width in setOf(128, 256, 512, 1024), "${file.name}: ${img.width}×${img.height}")
            assertTrue(kind.repeatM in 0.2f..4f, "$key: repeatM ${kind.repeatM}")
            assertTrue(kind.rough - kind.roughVar >= 0.45f && kind.rough <= 1f, "$key: Rauheit ${kind.rough} ± ${kind.roughVar}")
            assertTrue(kind.trackU in 0f..0.45f, "$key: trackU ${kind.trackU}")
        }
        assertTrue(spec.rangeM in 10f..80f && spec.reliefRangeM in 10f..80f)
    }

    @Test
    fun `nur Fahrbahnen bekommen Nahdetail`() {
        assertEquals("asphalt", SurfaceNearDetail.keyFor("mat_route_asphalt", spec))
        assertEquals("cobble", SurfaceNearDetail.keyFor("mat_route_cobble", spec))
        assertEquals("gravel", SurfaceNearDetail.keyFor("x_mat_road_gravel", spec))
        assertNull(SurfaceNearDetail.keyFor("x_mat_road_kerb", spec))
        assertNull(SurfaceNearDetail.keyFor("x_mat_road_island", spec))
        assertNull(SurfaceNearDetail.keyFor("x_mat_route_verge_grass", spec))
        assertNull(SurfaceNearDetail.keyFor("x_mat_route_shoulder", spec))
        assertNull(SurfaceNearDetail.keyFor("x_mat_route_fringe", spec))
        assertNull(SurfaceNearDetail.keyFor("mat_terrain", spec))
        assertNull(SurfaceNearDetail.keyFor(null, spec))
    }

    @Test
    fun `aus heisst alle Parameter null`() {
        val k = spec.kinds.getValue("asphalt")
        for (v in spec.params(k, on = false)) assertTrue(v.all { it == 0f })
        val on = spec.params(k, on = true)
        assertEquals(spec.rangeM, on[0][0])
        assertEquals(k.repeatM, on[1][0])
        assertEquals(spec.reliefRangeM, on[2][1])
    }

    @Test
    fun `kaputtes detail json schaltet nur das Nahdetail ab`() {
        assertNull(SurfaceNearDetail.parse("{"))
        assertNull(SurfaceNearDetail.parse("""{"rangeM": 30}"""))
        // Belaege mit unbekannter Karte fallen heraus
        val s = assertNotNull(
            SurfaceNearDetail.parse(
                """{"rangeM": 30, "reliefRangeM": 40, "maps": {"a": {"file": "a.jpg"}},
                   "kinds": {"asphalt": {"map": "a", "repeatM": 1, "normal": 1, "albedo": 0.2, "relief": 1,
                     "rough": 0.9, "roughVar": 0.1, "trackGloss": 0, "trackU": 0},
                     "gravel": {"map": "fehlt", "repeatM": 1, "normal": 1, "albedo": 0.2, "relief": 1,
                     "rough": 0.9, "roughVar": 0.1, "trackGloss": 0, "trackU": 0}}}""",
            ),
        )
        assertEquals(setOf("asphalt"), s.kinds.keys)
        assertEquals(1f, s.kinds.getValue("asphalt").reliefTexels)
    }

    @Test
    fun `Belags-Materialien deklarieren alle gesetzten Parameter`() {
        // gltfio (AssetLoader.cpp) setzt diese auf jede Instanz; die App setzt die uebrigen.
        val gltfio = listOf("baseColorMap", "baseColorFactor", "emissiveFactor", "metallicFactor", "roughnessFactor", "normalScale", "aoStrength")
        val app = listOf("detailMap", "detailClass", "detailRange", "detailStrength", "bumpStrength", "anisoRange", "forestWarmth") +
            listOf(SurfaceNearTextures.NEAR_MAP, SurfaceNearTextures.NEAR_A, SurfaceNearTextures.NEAR_B, SurfaceNearTextures.NEAR_C)
        for (m in listOf("surface", "surface_blend")) {
            val src = File("src/main/materials/$m.mat").readText()
            val declared = Regex("""name\s*:\s*(\w+)\s*[,}]""").findAll(src.substringBefore("fragment")).map { it.groupValues[1] }.toSet()
            for (p in gltfio + app) assertTrue(p in declared, "$m.mat deklariert $p nicht")
            assertTrue(File("src/main/assets/indoor/materials/$m.filamat").isFile, "$m.filamat fehlt")
        }
    }
}
