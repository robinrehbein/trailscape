package de.trailscape.core.indoor.sky

import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spiegelungs-Umgebungslicht aus dem Himmelsmodell ([SkyReflections]). */
class SkyReflectionsTest {
    private fun lighting(hour: Double) =
        DaylightModel.lighting(SkySetting.DEFAULT.copy(hourOfDay = hour), 48.8, IndoorQuality.MEDIUM.viewDistanceM)

    @Test
    fun `Stufen bis 1x1 mit Filaments Rauheitsabbildung`() {
        assertEquals(7, SkyReflections.levelCount(64))
        assertEquals(1, SkyReflections.levelCount(1))
        assertEquals(0.0, SkyReflections.perceptualRoughness(0, 7), 1e-12)
        assertEquals(1.0, SkyReflections.perceptualRoughness(6, 7), 1e-12)
        // Umkehrung von lod = (N-1) · p · (2 - p) (surface_light_indirect.fs)
        for (l in 0..6) {
            val p = SkyReflections.perceptualRoughness(l, 7)
            assertEquals(l.toDouble(), 6.0 * p * (2.0 - p), 1e-9)
        }
    }

    @Test
    fun `Groessen der Mip-Kette`() {
        val levels = SkyReflections.prefiltered(lighting(13.0).look, lighting(13.0).skyScale, lighting(13.0).iblGround, 32)
        assertEquals(6, levels.size)
        var s = 32
        for (lv in levels) {
            assertEquals(6 * s * s * 3, lv.size)
            assertTrue(lv.all { it.isFinite() && it >= 0f })
            s = s shr 1
        }
        val half = SkyReflections.prefilteredHalf(lighting(13.0), 16)
        assertEquals(5, half.size)
        assertEquals(6 * 16 * 16 * 3, half[0].size)
    }

    @Test
    fun `oben Himmel im Mass des Umgebungslichts, unten Boden`() {
        val l = lighting(13.0)
        val n = 32
        val lv = SkyReflections.prefiltered(l.look, l.skyScale, l.iblGround, n, strength = 1.0)[0]
        // +Y (Flaeche 2) Mitte: Zenit; −Y (Flaeche 3) Mitte: Boden.
        val up = SkyReflections.texel(lv, n, 2, n / 2, n / 2)
        val down = SkyReflections.texel(lv, n, 3, n / 2, n / 2)
        assertEquals(l.iblGround.r, down.r, 1e-3)
        assertEquals(l.iblGround.g, down.g, 1e-3)
        assertTrue(up.b > up.r, "Zenit blau: $up")
        // Horizont (Seitenflaeche, mittlere Zeile knapp ueber der Mitte) etwa so hell wie iblHorizon.
        val hz = SkyReflections.texel(lv, n, 4, n / 2 - 1, n / 2)
        assertTrue(abs(hz.luminance - l.iblHorizon.luminance) < 0.35 * l.iblHorizon.luminance, "Horizont $hz gegen ${l.iblHorizon}")
    }

    @Test
    fun `Vorfilterung erhaelt die Energie und glaettet`() {
        val l = lighting(17.5)
        val levels = SkyReflections.prefiltered(l.look, l.skyScale, SkyReflections.groundFor(l), 64)
        val m0 = SkyReflections.meanLuminance(levels[0])
        for (lv in levels) {
            val m = SkyReflections.meanLuminance(lv)
            assertTrue(abs(m - m0) < 0.2 * m0, "Mittel je Stufe aehnlich: $m gegen $m0")
        }
        // Die raueste Stufe: oben hell, unten dunkler (Himmel gegen Boden), aber weich.
        val last = levels.last()
        val up = SkyReflections.texel(last, 1, 2, 0, 0)
        val down = SkyReflections.texel(last, 1, 3, 0, 0)
        assertTrue(up.luminance > down.luminance)
    }

    @Test
    fun `Boden abends dunkler als der sichtbare Horizont`() {
        for (h in listOf(8.0, 13.0, 18.5)) {
            val l = lighting(h)
            val g = SkyReflections.groundFor(l)
            val hz = l.look.horizon * (1.0 / l.skyScale)
            assertTrue(g.luminance < hz.luminance, "Boden $g gegen Horizont $hz um $h Uhr")
        }
    }

    @Test
    fun `Staerke skaliert linear`() {
        val l = lighting(10.0)
        val a = SkyReflections.prefiltered(l.look, l.skyScale, l.iblGround, 16, strength = 1.0)
        val b = SkyReflections.prefiltered(l.look, l.skyScale, l.iblGround, 16, strength = 0.5)
        for (k in a.indices) {
            assertEquals(SkyReflections.meanLuminance(a[k]) * 0.5, SkyReflections.meanLuminance(b[k]), 1e-4)
        }
    }

    @Test
    fun `schnell genug fuer den Hauptthread`() {
        val l = lighting(12.0)
        SkyReflections.prefiltered(l.look, l.skyScale, l.iblGround) // Wolkenrauschen einmal je Prozess
        val t0 = System.nanoTime()
        SkyReflections.prefilteredHalf(l)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(ms < 400.0, "Vorfilterung dauerte $ms ms")
    }
}
