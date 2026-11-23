package de.trailscape.core.indoor.world

import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LandRasterTest {
    private val r = TerrainTestSupport::rect

    private val map = ListMapData(
        areas = listOf(
            MapArea(listOf(r(-500.0, -500.0, 500.0, 500.0)), LandClass.FARMLAND, "farmland"),
            MapArea(listOf(r(-100.0, -100.0, 100.0, 100.0), r(-20.0, -20.0, 20.0, 20.0)), LandClass.FOREST, "wood"),
            MapArea(listOf(r(40.0, 40.0, 80.0, 80.0)), LandClass.WATER, "lake"),
            MapArea(listOf(r(200.0, 200.0, 300.0, 300.0)), LandClass.MEADOW, "grass"),
            MapArea(listOf(r(150.0, 150.0, 260.0, 260.0)), LandClass.RESIDENTIAL, "residential"),
            MapArea(listOf(r(-300.0, 200.0, -200.0, 300.0)), LandClass.FOREST, "needleleaved"),
        ),
        buildings = listOf(MapBuilding(1, listOf(r(170.0, 170.0, 180.0, 182.0)), 8.0)),
        roads = listOf(MapRoad(doubleArrayOf(-400.0, -300.0, 400.0, -300.0), "secondary")),
    )
    private val land = LandRaster(map)

    @Test
    fun `Klassen nach Rang, Loecher bleiben frei`() {
        assertEquals(LandClass.FARMLAND, land.classAt(300.0, -100.0))
        assertEquals(LandClass.FOREST, land.classAt(-50.0, 50.0))
        assertEquals(LandClass.WATER, land.classAt(60.0, 60.0))
        // Loch im Wald: darunter der Acker.
        assertEquals(LandClass.FARMLAND, land.classAt(0.0, 0.0))
        // Wohngebiet schlaegt Wiese, Acker schlaegt Wiese, wo beide liegen.
        assertEquals(LandClass.RESIDENTIAL, land.classAt(220.0, 220.0))
        assertEquals(LandClass.FARMLAND, land.classAt(280.0, 280.0))
        // Ausserhalb aller Flaechen: Wiese.
        assertEquals(LandClass.MEADOW, land.classAt(900.0, 900.0))
        // Ueber Blockgrenzen (256 m) und negative Koordinaten hinweg.
        assertEquals(LandClass.FARMLAND, land.classAt(-257.0, -259.0))
        assertEquals(LandClass.FARMLAND, land.classAt(255.9, -0.1))
    }

    @Test
    fun `Zusatzmasken fuer Strassen und Gebaeude`() {
        assertTrue(land.isRoad(0.0, -300.0))
        assertTrue(land.isRoad(0.0, -297.0))
        assertTrue(!land.isRoad(0.0, -290.0))
        assertTrue(land.isBuilding(175.0, 175.0))
        assertTrue(!land.isBuilding(185.0, 175.0))
        // Die Klasse bleibt unter den Masken erhalten.
        assertEquals(LandClass.RESIDENTIAL, land.classAt(175.0, 175.0))
        assertEquals(LandClass.FARMLAND, land.classAt(0.0, -300.0))
    }

    @Test
    fun `Wasseranteil ist stetig`() {
        assertEquals(1.0, land.waterAt(60.0, 60.0))
        assertEquals(0.0, land.waterAt(0.0, 60.0))
        var prev = land.waterAt(30.0, 60.0)
        var e = 30.0
        while (e < 50.0) {
            e += 0.01
            val w = land.waterAt(e, 60.0)
            assertTrue(abs(w - prev) < 0.01, "Sprung bei $e")
            prev = w
        }
    }

    @Test
    fun `Nadelanteil aus Daten oder Rauschen`() {
        assertEquals(0.92, land.coniferShare(-250.0, 250.0))
        val values = (0 until 200).map { land.coniferShare(it * 37.0, -it * 21.0) }
        assertTrue(values.all { it in 0.0..1.0 })
        assertTrue(values.max() - values.min() > 0.4, "Rauschen ohne Mischwald-Inseln")
        assertEquals(1.0, land.canopyAt(-50.0, 50.0))
        assertEquals(0.0, land.canopyAt(300.0, -100.0))
    }

    @Test
    fun `Baumkronen aus dem Luftbild`() {
        val img = javaClass.getResourceAsStream("/indoor/world/aerial_forest_meadow.jpg")!!.use { ImageIO.read(it) }
        val rgba = RgbaImage(img.width, img.height, IntArray(img.width * img.height) { img.getRGB(it % img.width, it / img.width) })
        val g = canopyFromAerial(rgba, 32)
        fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var s = 0.0
            var c = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                s += (g.values[y * 32 + x].toInt() and 0xFF) / 255.0
                c++
            }
            return s / c
        }
        val forest = mean(0, 0, 13, 32)
        val meadow = mean(27, 19, 32, 32)
        assertTrue(forest > 0.6, "Wald nur $forest")
        assertTrue(meadow < 0.2, "Wiese $meadow")
        // Eingehaengt ersetzt es den Klassenwert.
        val spec = TileSpec(0, 0, 0)
        land.setCanopy(spec, g)
        assertTrue(land.canopyAt(10.0, 250.0) > 0.5)
        assertTrue(land.canopyAt(250.0, 10.0) < 0.3)
    }
}
