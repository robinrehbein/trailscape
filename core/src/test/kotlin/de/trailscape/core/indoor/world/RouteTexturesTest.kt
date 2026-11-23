package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteTexturesTest {
    private val kinds = SurfaceKind.entries.filter { it != SurfaceKind.UNKNOWN }

    private fun isPow2(v: Int) = v > 0 && (v and (v - 1)) == 0

    private fun diff(a: Int, b: Int): Double =
        (abs(Argb.red(a) - Argb.red(b)) + abs(Argb.green(a) - Argb.green(b)) + abs(Argb.blue(a) - Argb.blue(b))) / 3.0

    /** Mittlerer Unterschied zwischen den Spalten [x0] und [x1]. */
    private fun columnDiff(img: RgbaImage, x0: Int, x1: Int): Double = (0 until img.height).sumOf { diff(img[x0, it], img[x1, it]) } / img.height

    private fun rowDiff(img: RgbaImage, y0: Int, y1: Int): Double = (0 until img.width).sumOf { diff(img[it, y0], img[it, y1]) } / img.width

    /**
     * Der Sprung ueber den Rand ist nicht groesser als der groesste zwischen
     * Nachbarn im Inneren (Fugen sind auch dort harte Kanten) und im Mittel
     * nicht auffaelliger als ueblich.
     */
    private fun assertTileable(name: String, img: RgbaImage) {
        val w = img.width
        val h = img.height
        val innerX = (0 until w - 1).map { columnDiff(img, it, it + 1) }
        val innerY = (0 until h - 1).map { rowDiff(img, it, it + 1) }
        val seamX = columnDiff(img, w - 1, 0)
        val seamY = rowDiff(img, h - 1, 0)
        assertTrue(seamX <= innerX.max() * 1.05 + 0.5, "$name: Naht in x ($seamX gegen hoechstens ${innerX.max()} im Inneren)")
        assertTrue(seamY <= innerY.max() * 1.05 + 0.5, "$name: Naht in y ($seamY gegen hoechstens ${innerY.max()} im Inneren)")
        assertTrue(seamX <= innerX.sorted()[innerX.size * 9 / 10] * 3.5 + 1.0, "$name: Naht in x faellt auf ($seamX)")
        assertTrue(seamY <= innerY.sorted()[innerY.size * 9 / 10] * 3.5 + 1.0, "$name: Naht in y faellt auf ($seamY)")
    }

    private fun luminance(img: RgbaImage): Double {
        val m = RouteTextures.meanLinear(img)
        return 0.2126 * m[0] + 0.7152 * m[1] + 0.0722 * m[2]
    }

    @Test
    fun `Belagstexturen sind kachelbar und haben Zweierpotenz-Groesse`() {
        for (kind in kinds) {
            val img = RouteTextures.surface(kind)
            assertEquals(RouteTextures.SURFACE_W, img.width)
            assertEquals(RouteTextures.SURFACE_H, img.height)
            assertTrue(isPow2(img.width) && isPow2(img.height))
            assertTrue(img.argb.all { Argb.alpha(it) == 255 }, "$kind ist deckend")
            assertTileable(kind.name, img)
        }
    }

    @Test
    fun `mittlere Albedo je Belag ist plausibel`() {
        val ranges = mapOf(
            SurfaceKind.ASPHALT to 0.14..0.30, SurfaceKind.CONCRETE to 0.25..0.45, SurfaceKind.PAVING_STONES to 0.17..0.35,
            SurfaceKind.COBBLESTONE to 0.12..0.30, SurfaceKind.WOOD to 0.10..0.26, SurfaceKind.COMPACTED to 0.24..0.42,
            SurfaceKind.GRAVEL to 0.13..0.30, SurfaceKind.DIRT to 0.05..0.14, SurfaceKind.GRASS to 0.15..0.30,
            SurfaceKind.SAND to 0.20..0.45,
        )
        for (kind in kinds) {
            val l = luminance(RouteTextures.surface(kind))
            assertTrue(l in ranges.getValue(kind), "$kind: mittlere Albedo $l ausserhalb ${ranges.getValue(kind)}")
        }
        // Reihenfolge der Helligkeit, wie man sie draussen sieht.
        assertTrue(luminance(RouteTextures.surface(SurfaceKind.DIRT)) < luminance(RouteTextures.surface(SurfaceKind.GRAVEL)))
        assertTrue(luminance(RouteTextures.surface(SurfaceKind.ASPHALT)) < luminance(RouteTextures.surface(SurfaceKind.CONCRETE)))
        assertTrue(luminance(RouteTextures.surface(SurfaceKind.GRAVEL)) < luminance(RouteTextures.surface(SurfaceKind.COMPACTED)))
    }

    @Test
    fun `Belaege sind nicht bunt`() {
        for (kind in listOf(SurfaceKind.ASPHALT, SurfaceKind.CONCRETE, SurfaceKind.PAVING_STONES, SurfaceKind.COBBLESTONE)) {
            val m = RouteTextures.meanLinear(RouteTextures.surface(kind))
            val spread = (maxOf(m[0], m[1], m[2]) - minOf(m[0], m[1], m[2])) / maxOf(m[0], m[1], m[2])
            assertTrue(spread < 0.15, "$kind ist farbstichig ($spread)")
        }
    }

    @Test
    fun `Randtexturen treffen ihre erklaerte mittlere Farbe und kacheln`() {
        for (v in RouteTextures.Verge.entries) {
            for (px in intArrayOf(512, 256)) {
                val img = RouteTextures.verge(v, px)
                assertTrue(isPow2(img.width) && img.width == img.height && img.width <= 512)
                val m = RouteTextures.meanLinear(img)
                for (c in 0..2) assertEquals(v.meanLinear[c], m[c], v.meanLinear[c] * 0.04f, "${v.key} Kanal $c bei $px px")
                assertTileable("${v.key}_$px", img)
            }
        }
        val g = RouteTextures.Verge.GRASS.meanLinear
        assertTrue(g[1] > g[0] && g[0] > g[2], "Gras ist gruen, nicht neon")
        assertTrue(g[1] / g[0] < 1.6f, "Gras ist entsaettigt")
    }

    @Test
    fun `Wiesenweg hat die Helligkeit der Wiese`() {
        val m = RouteTextures.meanLinear(RouteTextures.surface(SurfaceKind.GRASS))
        for (c in 0..2) assertEquals(RouteTextures.GRASS_WAY_MEAN[c], m[c], RouteTextures.GRASS_WAY_MEAN[c] * 0.04f)
    }

    @Test
    fun `Fransenkante deckt zum Rand hin und ist zur Wegmitte durchsichtig`() {
        val img = RouteTextures.fringe()
        assertTrue(isPow2(img.width) && isPow2(img.height))
        assertTrue((0 until img.width).all { Argb.alpha(img[it, 0]) == 0 }, "Wegseite durchsichtig")
        assertTrue((0 until img.width).all { Argb.alpha(img[it, img.height - 1]) == 255 }, "Randseite deckend")
        val mid = (0 until img.width).map { Argb.alpha(img[it, img.height / 2]) }
        assertTrue(mid.any { it < 40 } && mid.any { it > 215 }, "Die Kante ist ausgefranst")
        // Laengs kachelbar: Deckung der ersten und letzten Spalte passen zusammen.
        val seam = (0 until img.height).sumOf { abs(Argb.alpha(img[0, it]) - Argb.alpha(img[img.width - 1, it])) } / img.height
        assertTrue(seam < 40, "Naht der Fransenkante ($seam)")
    }

    @Test
    fun `Texturen sind deterministisch und werden je Welt einmal kodiert`() {
        assertTrue(RouteTextures.surface(SurfaceKind.GRAVEL).argb.contentEquals(RouteTextures.surface(SurfaceKind.GRAVEL).argb))
        val cache = TextureCache()
        val a = RouteTextures.surfaceTexture(cache, SurfaceKind.UNKNOWN)
        val b = RouteTextures.surfaceTexture(cache, SurfaceKind.COMPACTED)
        assertTrue(a === b, "UNKNOWN sieht aus wie COMPACTED")
        assertEquals("image/jpeg", a.mime)
        assertEquals("image/png", RouteTextures.fringeTexture(cache).mime)
        assertTrue(RouteTextures.vergeTexture(cache, RouteTextures.Verge.GRASS, 256).gpuBytes < RouteTextures.vergeTexture(cache, RouteTextures.Verge.GRASS).gpuBytes)
    }

    @Test
    fun `Massstab jeder Belagstextur ist rund ein Zentimeter je Pixel und mindestens acht Meter lang`() {
        for (kind in kinds) {
            val s = RouteTextures.spec(kind)
            assertTrue(s.lengthM >= 8.0, "$kind wiederholt sich nach ${s.lengthM} m")
            val across = s.widthM / RouteTextures.SURFACE_W
            val along = s.lengthM / RouteTextures.SURFACE_H
            assertEquals(across, along, 1e-9, "$kind: Pixel sind quadratisch")
            assertTrue(across in 0.007..0.016, "$kind: $across m je Pixel")
        }
    }
}
