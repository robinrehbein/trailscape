package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.world.BuildingTextures.Band
import de.trailscape.core.indoor.world.BuildingTextures.Decal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildingTexturesTest {
    private val atlas = BuildingTextures.render()

    @Test
    fun `Atlas hat die vereinbarte Groesse und die Baender fuellen ihn lueckenlos`() {
        assertEquals(512, atlas.width)
        assertEquals(1024, atlas.height)
        var y = 0
        for (b in Band.entries) {
            assertEquals(y, b.top, "${b.name} beginnt nicht am Ende des vorigen Bands")
            assertEquals(0, b.top % 16, "${b.name} beginnt nicht auf einem JPEG-Block")
            assertTrue(b.contentPx >= 8)
            y += b.pitch
        }
        assertEquals(BuildingTextures.HEIGHT, y)
        for (d in Decal.entries) {
            assertTrue(d.x0 < d.x1 && d.x1 <= BuildingTextures.WIDTH)
            assertTrue(Decal.entries.none { it != d && it.x0 < d.x1 && d.x0 < it.x1 }, "Felder ueberlappen")
        }
    }

    @Test
    fun `Atlas ist deterministisch und wird als Baseline-JPEG eingebettet`() {
        assertContentEquals(atlas.argb, BuildingTextures.render().argb)
        val cache = TextureCache()
        val tex = BuildingTextures.atlas(cache)
        assertEquals("image/jpeg", tex.mime)
        assertEquals(512, tex.width)
        assertEquals(1024, tex.height)
        assertTrue(tex.bytes.size < 400_000, "Atlas ${tex.bytes.size} Bytes")
        assertTrue(tex === BuildingTextures.atlas(cache), "Atlas wird je Welt nur einmal gerechnet")
        assertEquals(2_796_202L, tex.gpuBytes)
    }

    @Test
    fun `Baender kacheln waagerecht und der Rand wiederholt die Randzeile`() {
        for (b in Band.entries) {
            if (b == Band.SPECIAL) continue
            val y0 = b.top + BuildingTextures.GUTTER
            // Linke und rechte Randspalte unterscheiden sich nicht staerker als zwei Nachbarspalten in der Mitte.
            var seam = 0.0
            var inner = 0.0
            for (r in 0 until b.contentPx) {
                seam += diff(atlas[0, y0 + r], atlas[511, y0 + r])
                inner += diff(atlas[255, y0 + r], atlas[256, y0 + r])
            }
            assertTrue(seam <= inner * 3 + 14.0 * b.contentPx, "${b.name}: Naht $seam gegen $inner")
            for (x in 0 until 512 step 37) {
                for (g in 0 until BuildingTextures.GUTTER) {
                    assertEquals(atlas[x, y0], atlas[x, b.top + g])
                    assertEquals(atlas[x, y0 + b.contentPx - 1], atlas[x, b.top + b.pitch - 1 - g])
                }
            }
        }
    }

    private fun diff(a: Int, b: Int): Double =
        (abs(Argb.red(a) - Argb.red(b)) + abs(Argb.green(a) - Argb.green(b)) + abs(Argb.blue(a) - Argb.blue(b))).toDouble()

    @Test
    fun `mittlere Farben der Baender stimmen mit der Tabelle fuer Stufe 1`() {
        for (b in Band.entries) {
            if (b == Band.SPECIAL) continue
            val m = BuildingTextures.measureMeanLinear(atlas, b)
            val t = BuildingTextures.meanLinear(b)
            for (c in 0 until 3) assertEquals(m[c], t[c], 0.02f, "${b.name} Kanal $c: gemessen ${m.toList()}")
        }
    }

    @Test
    fun `Farben sind glaubwuerdig - Putz hell aber nicht weiss, Glas dunkel, Fugen sichtbar`() {
        val plaster = BuildingTextures.measureMeanLinear(atlas, Band.PLASTER)
        assertTrue(plaster.all { it in 0.6f..0.82f }, "Putz ${plaster.toList()}")
        // Fenstermitte der ersten Achse: dunkles, leicht blaues Glas.
        val b = Band.PLASTER_WINDOWS
        val px = (BuildingTextures.BAY_M / 2 + 0.25) / b.widthM * 512
        val row = b.top + BuildingTextures.GUTTER + ((1 - 1.4 / b.heightM) * b.contentPx).toInt()
        val glass = atlas[px.toInt(), row]
        assertTrue(Argb.luminance(glass) < 0.08, "Glas zu hell")
        assertTrue(Argb.blue(glass) > Argb.red(glass))
        // Rahmen direkt am Rand der Oeffnung: hell.
        val frameX = ((BuildingTextures.BAY_M / 2 + BuildingTextures.WINDOW_HALF_M - 0.03) / b.widthM * 512).toInt()
        assertTrue(Argb.luminance(atlas[frameX, row]) > 0.6, "kein heller Rahmen")
        // Klinker ist rotbraun, Schiefer anthrazit.
        val brick = BuildingTextures.measureMeanLinear(atlas, Band.BRICK)
        assertTrue(brick[0] > 1.8f * brick[2] && brick[0] < 0.4f)
        val slate = BuildingTextures.measureMeanLinear(atlas, Band.SLATE)
        assertTrue(slate.all { it < 0.09f })
        // Vier Fenster je Bandbreite: jede Achse hat dunkles Glas an derselben Stelle.
        for (bay in 0 until 4) {
            val x = ((bay + 0.5) * BuildingTextures.BAY_M + 0.2) / b.widthM * 512
            assertTrue(Argb.luminance(atlas[x.toInt(), row]) < 0.5, "Achse $bay ohne Fenster")
        }
    }
}
