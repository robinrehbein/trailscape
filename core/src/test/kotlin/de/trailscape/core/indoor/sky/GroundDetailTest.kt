package de.trailscape.core.indoor.sky

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boden-Detailtextur: Groesse, Mittel je Bodenart, Kachelbarkeit, ganzzahlige Wiederholungen. */
class GroundDetailTest {
    private val tex = GroundDetail.texture()
    private val n = GroundDetail.SIZE

    private fun at(x: Int, y: Int, c: Int): Int = tex[((Math.floorMod(y, n) * n + Math.floorMod(x, n)) * 4) + c].toInt() and 0xFF

    @Test
    fun `RGBA 256 im Quadrat, jede Bodenart mit Mittel um 0,5 und Struktur`() {
        assertEquals(n * n * 4, tex.size)
        for (c in 0 until 4) {
            var sum = 0.0
            var sq = 0.0
            for (y in 0 until n) for (x in 0 until n) {
                val v = at(x, y, c) / 255.0
                sum += v
                sq += v * v
            }
            val mean = sum / (n * n)
            val sd = kotlin.math.sqrt(sq / (n * n) - mean * mean)
            assertTrue(abs(mean - 0.5) < 0.04, "Mittel Kanal $c: $mean")
            assertTrue(sd in 0.12..0.26, "Streuung Kanal $c: $sd")
        }
    }

    @Test
    fun `kachelbar — die Naht ist nicht staerker als die staerkste Spalte im Inneren`() {
        for (c in 0 until 4) {
            var seam = 0.0
            var worst = 0.0
            for (k in 0 until n) {
                var col = 0.0
                for (i in 0 until n) col += abs(at(k - 1, i, c) - at(k, i, c)) + abs(at(i, k - 1, c) - at(i, k, c))
                if (k == 0) seam = col else worst = maxOf(worst, col)
            }
            assertTrue(seam <= worst, "Naht Kanal $c: $seam gegen staerkste Spalte $worst")
        }
    }

    @Test
    fun `deterministisch und nahtlos ueber Kacheln (ganzzahlige Wiederholungen je 256 m)`() {
        assertTrue(GroundDetail.texture().contentEquals(tex))
        assertEquals(256.0, GroundDetail.REPEAT_M * GroundDetail.TILE_REPEATS, 1e-9)
        assertEquals(0.0, GroundDetail.TILE_REPEATS % 1.0)
        assertEquals(0.0, GroundDetail.MACRO_REPEATS % 1.0)
    }

    @Test
    fun `Materialparameter schalten ab`() {
        assertEquals(0.0, SkyShading.terrain(detail = false)["detailRange"])
        assertEquals(0.0, SkyShading.foliage(wind = false)["windStrength"])
        assertEquals(0.0, SkyShading.surface(detail = false, aniso = false)["anisoRange"])
        val dir = SkyShading.foliage(windToward = doubleArrayOf(3.0, 4.0))["windDir"] as DoubleArray
        assertEquals(1.0, dir[0] * dir[0] + dir[1] * dir[1], 1e-9)
    }
}
