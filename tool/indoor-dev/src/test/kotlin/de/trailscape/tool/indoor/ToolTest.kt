package de.trailscape.tool.indoor

import de.trailscape.core.TrackPoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import org.junit.jupiter.api.Timeout
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ToolTest {
    /** Die abgelegte echte Terrarium-Kachel aus den `:core`-Testdaten (Arbeitsverzeichnis = `tool/indoor-dev`). */
    private val terrarium = File("../../core/src/test/resources/indoor/world/terrarium_13_4258_2740.png")

    @Test
    fun `ImageIO-Decoder liefert die Terrarium-Werte roh`() {
        val bytes = terrarium.readBytes()
        val img = ImageIoDecoder.decode(bytes)!!
        assertEquals(256, img.width)
        // Roh gelesene Kanaele, ohne jede Farbverwaltung
        val raw = ImageIO.read(terrarium)
        val px = raw.raster.getPixel(17, 200, IntArray(raw.raster.numBands))
        val v = img.argb[200 * 256 + 17]
        if (raw.colorModel !is java.awt.image.IndexColorModel) {
            assertEquals(listOf(px[0], px[1], px[2]), listOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF))
        }
        val meters = img.argb.map { ((it shr 16) and 0xFF) * 256.0 + ((it shr 8) and 0xFF) + (it and 0xFF) / 256.0 - 32768 }
        assertTrue(meters.min() in 70.0..100.0 && meters.max() in 200.0..260.0, "${meters.min()} .. ${meters.max()}")
        assertEquals(null, ImageIoDecoder.decode("kein Bild".toByteArray()))
    }

    @Test
    fun `Rueckfall tastet neu ab und erkennt Runden`() {
        // Kreis mit 400 m Radius, Punkte alle ~35 m, Start = Ziel
        val pts = (0..72).map { k ->
            val a = 2 * Math.PI * k / 72
            TrackPoint(51.0 + 400 * sin(a) / 6_371_000 * 180 / Math.PI, 7.0 + 400 * cos(a) / (6_371_000 * cos(Math.toRadians(51.0))) * 180 / Math.PI, 100.0 + 10 * sin(a))
        }
        val r = fallbackRoute("Kreis", pts)
        assertTrue(r.isLoop)
        assertTrue(r.lengthM in 2_480.0..2_520.0, "${r.lengthM}")
        assertEquals(0.0, r.points.first().distM)
        for (i in 1 until r.points.size) {
            val d = r.points[i].distM - r.points[i - 1].distM
            assertTrue(d in 1.0..10.0001, "Abstand $d")
            assertTrue(Math.abs(r.points[i].eleM - r.points[i - 1].eleM) <= 0.25 * d + 1e-9)
        }
        assertEquals(listOf(SurfaceSection(0.0, r.lengthM, SurfaceKind.UNKNOWN)), r.surfaces)
        val open = fallbackRoute("Bogen", pts.take(40))
        assertTrue(!open.isLoop)
    }

    @Test
    fun `Belaege der Beidatei werden gestreckt und ausgeschnitten`() {
        val file = SurfaceFile(
            IndoorRouteSource.BROUTER_WAY_TAGS,
            1_000.0,
            listOf(
                SurfaceSection(0.0, 400.0, SurfaceKind.ASPHALT, "service"),
                SurfaceSection(400.0, 1_000.0, SurfaceKind.GRAVEL, "track"),
            ),
        )
        val full = stretchSurfaces(file, 2_000.0, 0.0, 2_000.0)
        assertEquals(listOf(SurfaceSection(0.0, 800.0, SurfaceKind.ASPHALT, "service"), SurfaceSection(800.0, 2_000.0, SurfaceKind.GRAVEL, "track")), full)
        val cut = stretchSurfaces(file, 2_000.0, 500.0, 1_500.0)
        assertEquals(listOf(SurfaceSection(0.0, 300.0, SurfaceKind.ASPHALT, "service"), SurfaceSection(300.0, 1_000.0, SurfaceKind.GRAVEL, "track")), cut)
        val sample = readSurfaceFile(File("samples/altenberg-kurz.surfaces.json"))
        assertEquals(IndoorRouteSource.BROUTER_WAY_TAGS, sample.source)
        assertEquals(3_011.0, sample.lengthM)
    }
}
