package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RasterNoiseTest {
    @Test
    fun tiledFbmIsPeriodicAndContinuousAcrossTheSeam() {
        val n = 128
        val cells = 4
        val seed = Noise.seedOf("gras")
        var maxInterior = 0.0
        var maxSeam = 0.0
        for (y in 0 until n) {
            val py = y + 0.5
            for (x in 0 until n - 1) {
                val d = abs(Noise.tiledFbm(x + 1.5, py, n, cells, seed) - Noise.tiledFbm(x + 0.5, py, n, cells, seed))
                maxInterior = max(maxInterior, d)
            }
            // Periodisch: um eine ganze Kachel verschoben derselbe Wert.
            assertEquals(Noise.tiledFbm(0.5, py, n, cells, seed), Noise.tiledFbm(n + 0.5, py, n, cells, seed), 1e-9)
            assertEquals(Noise.tiledFbm(py, 3.5, n, cells, seed), Noise.tiledFbm(py, n + 3.5, n, cells, seed), 1e-9)
            // Stetig: Sprung ueber den Rand nicht groesser als innen.
            maxSeam = max(maxSeam, abs(Noise.tiledFbm(0.5, py, n, cells, seed) - Noise.tiledFbm(n - 0.5, py, n, cells, seed)))
            maxSeam = max(maxSeam, abs(Noise.tiledFbm(py, 0.5, n, cells, seed) - Noise.tiledFbm(py, n - 0.5, n, cells, seed)))
        }
        assertTrue(maxInterior > 0.0)
        assertTrue(maxSeam <= maxInterior * 1.25, "Naht $maxSeam, innen $maxInterior")
    }

    @Test
    fun periodicValueAndWorleyNoiseRepeat() {
        val out1 = DoubleArray(3)
        val out2 = DoubleArray(3)
        for (i in 0 until 200) {
            val x = i * 0.137 - 3.0
            val y = i * 0.291 + 1.0
            assertEquals(Noise.value(x, y, 7, 5), Noise.value(x + 5, y - 10, 7, 5), 1e-9)
            assertEquals(Noise.fbmValue(x, y, 7, 4, 3), Noise.fbmValue(x + 3, y, 7, 4, 3), 1e-9)
            Noise.worley(x, y, 11, 6, out1)
            Noise.worley(x + 6, y + 12, 11, 6, out2)
            assertEquals(out1[0], out2[0], 1e-9)
            assertEquals(out1[1], out2[1], 1e-9)
            assertEquals(out1[2], out2[2], 1e-12)
            assertTrue(out1[0] <= out1[1])
            val g = Noise.gradient(x, y, 3)
            assertTrue(g in -1.05..1.05, "Gradientenrauschen $g")
            val v = Noise.value(x, y, 3)
            assertTrue(v in 0.0..1.0)
        }
        // Deterministisch und samenabhaengig.
        assertEquals(Noise.hash(3, 4, 5), Noise.hash(3, 4, 5))
        assertTrue(Noise.hash(3, 4, 5) != Noise.hash(3, 4, 6))
        assertEquals(Noise.seedOf("L0_1_2"), Noise.seedOf("L0_1_2"))
    }

    @Test
    fun boxResizeAveragesInLinearLightAndWeightsAlpha() {
        val checker = RgbaImage(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) checker[x, y] = if ((x + y) % 2 == 0) Argb.BLACK else Argb.WHITE
        val half = checker.resizeBox(2, 2)
        // Mittel von Schwarz und Weiss in linearem Licht = 0,5 → sRGB 188.
        for (c in half.argb) {
            assertEquals(255, Argb.alpha(c))
            assertTrue(abs(Argb.red(c) - 188) <= 1, "rot ${Argb.red(c)}")
        }
        val gamma = checker.resizeBox(1, 1, linearLight = false)
        assertTrue(abs(Argb.red(gamma[0, 0]) - 128) <= 1)

        // Durchsichtiges Rot neben deckendem Gruen: kein roter Saum.
        val leaf = RgbaImage(2, 1, intArrayOf(Argb.of(0, 255, 0, 0), Argb.rgb(0, 200, 0)))
        val one = leaf.resizeBox(1, 1)[0, 0]
        assertEquals(0, Argb.red(one))
        assertTrue(abs(Argb.green(one) - 200) <= 1)
        assertTrue(abs(Argb.alpha(one) - 128) <= 1)

        // Ungerade Verhaeltnisse: Farbe bleibt erhalten.
        val flat = RgbaImage(7, 5).apply { fill(Argb.rgb(10, 120, 240)) }
        for (c in flat.resizeBox(3, 2).argb) {
            assertTrue(abs(Argb.red(c) - 10) <= 1 && abs(Argb.green(c) - 120) <= 1 && abs(Argb.blue(c) - 240) <= 1)
        }
        assertEquals(Argb.rgb(10, 120, 240), flat.resizeBilinear(16, 16)[5, 5])
    }

    @Test
    fun sampleBlitAndColorHelpers() {
        val img = RgbaImage(2, 1, intArrayOf(Argb.rgb(0, 0, 0), Argb.rgb(200, 100, 50)))
        val mid = img.sampleBilinearPx(1.0, 0.5)
        assertEquals(100, Argb.red(mid))
        // Mit Umlauf mischt der linke Rand mit dem rechten Pixel.
        val wrapped = img.sampleBilinearPx(0.0, 0.5, wrap = true)
        assertEquals(100, Argb.red(wrapped))
        val clamped = img.sampleBilinearPx(0.0, 0.5, wrap = false)
        assertEquals(0, Argb.red(clamped))

        val dst = RgbaImage(4, 4).apply { fill(Argb.rgb(0, 0, 255)) }
        val src = RgbaImage(2, 2).apply { fill(Argb.of(128, 255, 0, 0)) }
        dst.blitOver(src, 3, 3)
        val over = dst[3, 3]
        assertTrue(abs(Argb.red(over) - 128) <= 1 && abs(Argb.blue(over) - 127) <= 1)
        assertEquals(Argb.rgb(0, 0, 255), dst[2, 2])
        dst.blit(src, -1, -1)
        assertEquals(src[0, 0], dst[0, 0])

        val c = Argb.rgb(180, 60, 30)
        val hsv = DoubleArray(3)
        Argb.toHsv(c, hsv)
        assertEquals(c, Argb.fromHsv(hsv[0], hsv[1], hsv[2]))
        val shifted = Argb.shiftHsv(Argb.rgb(255, 0, 0), 120.0)
        assertEquals(Argb.rgb(0, 255, 0), shifted)
        assertEquals(Argb.rgb(128, 128, 128), Argb.mix(Argb.BLACK, Argb.WHITE, 0.5).let { Argb.withAlpha(it, 255) })
        assertEquals(Argb.rgb(90, 30, 15), Argb.scale(c, 0.5))
        assertEquals(Argb.rgb(90, 30, 15), Argb.multiply(c, Argb.rgb(128, 128, 128)))
        for (v in 0..255) assertEquals(v, Argb.linearToSrgb8(Argb.SRGB_TO_LINEAR[v]))

        val raster = img.toRaster()
        assertEquals(img.argb, RgbaImage.of(raster).argb)
    }
}
