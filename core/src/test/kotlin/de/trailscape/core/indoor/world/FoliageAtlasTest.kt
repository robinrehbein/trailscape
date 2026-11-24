package de.trailscape.core.indoor.world

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FoliageAtlasTest {
    private val atlas by lazy { FoliageAtlas.image() }

    /** Kastenfilter wie `generateMipmaps`: Alpha der Stufe [level] fuer das Rechteck [r]. */
    private fun alphaMip(img: RgbaImage, r: FoliageAtlas.Region, level: Int): Array<DoubleArray> {
        var cur = Array(r.h) { y -> DoubleArray(r.w) { x -> (img[r.x + x, r.y + y] ushr 24) / 255.0 } }
        repeat(level) {
            val h = cur.size / 2
            val w = cur[0].size / 2
            val prev = cur
            cur = Array(h) { y -> DoubleArray(w) { x -> (prev[2 * y][2 * x] + prev[2 * y][2 * x + 1] + prev[2 * y + 1][2 * x] + prev[2 * y + 1][2 * x + 1]) / 4 } }
        }
        return cur
    }

    private fun coverage(a: Array<DoubleArray>): Double = a.sumOf { row -> row.count { it >= 0.5 }.toDouble() } / (a.size * a[0].size)

    @Test
    fun alphaDeckungBleibtInDenMipStufen() {
        val regions = mapOf(
            "BROAD" to FoliageAtlas.BROAD, "NEEDLE" to FoliageAtlas.NEEDLE, "SKIRT" to FoliageAtlas.SKIRT, "MASS" to FoliageAtlas.MASS,
            "TREE_BROAD" to FoliageAtlas.TREE_BROAD, "TREE_CONIFER" to FoliageAtlas.TREE_CONIFER,
            "GRASS" to FoliageAtlas.GRASS, "FERN" to FoliageAtlas.FERN,
        )
        val report = StringBuilder()
        for ((name, r) in regions) {
            val c0 = coverage(alphaMip(atlas, r, 0))
            assertTrue(c0 > 0.12, "$name: Motiv fast leer ($c0)")
            for (level in 1..3) {
                val ck = coverage(alphaMip(atlas, r, level))
                report.append("$name L$level ${"%.2f".format(ck / c0)}  ")
                // Grashalme und Farnfiedern duerfen in der Ferne duenner werden, Kronenmotive kaum.
                val min = if (name == "GRASS" || name == "FERN") 0.55 else 0.8
                assertTrue(ck / c0 >= min, "$name: Deckung in Mip-Stufe $level nur ${ck / c0} der vollen ($report)")
            }
        }
        println(report)
    }

    @Test
    fun durchsichtigePixelTragenMotivfarbe() {
        // Kein schwarzer oder weisser Saum in den Mip-Stufen: transparente Pixel sind gruenlich-hell.
        var dark = 0
        for (y in 0 until atlas.height) for (x in 0 until atlas.width) {
            val c = atlas[x, y]
            if (Argb.alpha(c) == 0 && Argb.green(c) < 60) dark++
        }
        assertEquals(0, dark)
    }

    @Test
    fun kronendachKacheltNahtlos() {
        val img = FoliageAtlas.canopyImage()
        val n = img.width
        var diff = 0.0
        for (i in 0 until n) {
            diff += kotlin.math.abs(Argb.green(img[0, i]) - Argb.green(img[n - 1, i]))
            diff += kotlin.math.abs(Argb.green(img[i, 0]) - Argb.green(img[i, n - 1]))
        }
        // Mittlerer Sprung ueber die Naht wie zwischen zwei Nachbarpixeln im Innern.
        var inner = 0.0
        for (i in 0 until n) {
            inner += kotlin.math.abs(Argb.green(img[n / 2, i]) - Argb.green(img[n / 2 + 1, i]))
            inner += kotlin.math.abs(Argb.green(img[i, n / 2]) - Argb.green(img[i, n / 2 + 1]))
        }
        assertTrue(diff <= inner * 1.6 + n, "Naht: $diff, innen: $inner")
    }

    /** Legt die Bilder zum Ansehen ab: `-Dfoliage.out=<dir>` (sonst nichts). */
    @Test
    fun bilderAblegen() {
        val dir = System.getProperty("foliage.out") ?: System.getenv("FOLIAGE_OUT") ?: return
        File(dir).mkdirs()
        fun save(img: RgbaImage, name: String, onGrey: Boolean) {
            val b = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until img.height) for (x in 0 until img.width) {
                val c = img[x, y]
                val a = Argb.alpha(c) / 255.0
                b.setRGB(x, y, if (!onGrey) c else {
                    fun m(v: Int, bg: Int) = (v * a + bg * (1 - a)).toInt()
                    Argb.rgb(m(Argb.red(c), 90), m(Argb.green(c), 120), m(Argb.blue(c), 170))
                })
            }
            ImageIO.write(b, "png", File(dir, name))
        }
        save(atlas, "atlas.png", false)
        save(atlas, "atlas-auf-blau.png", true)
        save(FoliageAtlas.canopyImage(), "kronendach.png", false)
    }
}
