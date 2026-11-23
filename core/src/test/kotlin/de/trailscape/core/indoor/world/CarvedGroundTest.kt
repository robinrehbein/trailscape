package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarvedGroundTest {
    private val o = TerrainTestSupport.origin
    private val hills = RawHeights { e, n -> PreviewSupport.hills.heightAt(e, n) }

    /** Steiler Hang quer zur Strecke (30 % nach Norden) mit Huegeln. */
    private val slope = RawHeights { e, n -> 0.30 * n + PreviewSupport.hills.heightAt(e, n) }

    private fun fitted(raw: RawHeights, line: DoubleArray, loop: Boolean = false): TrackLine =
        TrackLine(fitTrack(TerrainTestSupport.routeFromEnu(line, loop), o, raw))

    /** Prueft jeden Fahrbahnpunkt im 0,5-m-Raster: Boden ≤ Fahrbahn − 0,1 m. */
    private fun assertBelowRoad(ground: GroundSurface, track: TrackLine) {
        var worst = -Double.MAX_VALUE
        var d = 0.0
        while (d <= track.lengthM) {
            val p = track.poseAt(d)
            val h = Math.toRadians(p.headingDeg)
            val hw = track.halfWidthAt(d)
            var l = -hw
            while (l <= hw + 1e-9) {
                val e = p.e + cos(h) * l
                val n = p.n - sin(h) * l
                // Die Fahrbahn ist quer eben auf Hoehe der naechsten Mittellinienstelle.
                val hit = track.nearest(e, n, 50.0)!!
                val g = ground.heightAt(e, n)
                worst = max(worst, g - (hit.u - 0.1))
                l += 0.5
            }
            d += 0.5
        }
        assertTrue(worst <= 0.0, "Boden ragt bis ${worst} m ueber Fahrbahn − 0,1 m")
    }

    @Test
    fun `unter der Fahrbahn liegt der Boden nirgends ueber der Fahrbahn`() {
        val line = TrackLine(PreviewSupport.curvyTrack(lengthM = 900.0))
        assertBelowRoad(CarvedGround(hills, line), line)
    }

    @Test
    fun `auch am steilen Hang und in engen Kurven`() {
        val pts = TerrainTestSupport.polyline(0.0, 0.0, 300.0, 0.0, 330.0, 10.0, 345.0, 35.0, 345.0, 60.0, 330.0, 85.0, 300.0, 95.0, 0.0, 120.0)
        val line = fitted(slope, pts)
        assertBelowRoad(CarvedGround(slope, line), line)
    }

    @Test
    fun `weit weg bleibt das gedaempfte Gelaende unveraendert`() {
        val line = TrackLine(PreviewSupport.curvyTrack())
        val g = CarvedGround(hills, line)
        for (k in 0 until 50) {
            val e = 900.0 + k * 13.0
            val n = -700.0 + k * 7.0
            assertEquals(g.dampedAt(e, n), g.heightAt(e, n), 1e-12)
            assertTrue(abs(g.dampedAt(e, n) - hills.heightAt(e, n)) < 0.5)
        }
    }

    @Test
    fun `die Flaeche ist stetig, auch zwischen den Aesten einer Kehre`() {
        // Kehre: zwei Aeste 30 m auseinander, der obere 6 m hoeher.
        val pts = TerrainTestSupport.polyline(0.0, 0.0, 300.0, 0.0, 320.0, 8.0, 330.0, 15.0, 320.0, 22.0, 300.0, 30.0, 0.0, 30.0)
        val raw = RawHeights { e, n -> 0.2 * n + 0.01 * e }
        val line = fitted(raw, pts)
        val g = CarvedGround(raw, line)
        // Dicht abgetastete Linien quer und laengs durch das ganze Gebiet.
        var worst = 0.0
        val step = 0.05
        for (k in 0 until 40) {
            val e = -20.0 + k * 9.3
            var n = -60.0
            var prev = g.heightAt(e, n)
            while (n < 90.0) {
                n += step
                val h = g.heightAt(e, n)
                worst = max(worst, abs(h - prev))
                prev = h
            }
        }
        for (k in 0 until 30) {
            val n = -40.0 + k * 4.1
            var e = -20.0
            var prev = g.heightAt(e, n)
            while (e < 380.0) {
                e += step
                val h = g.heightAt(e, n)
                worst = max(worst, abs(h - prev))
                prev = h
            }
        }
        // 5 cm Schritt: Boeschung hoechstens 1 : 1,5 plus Gelaende → unter 6 cm je Schritt.
        assertTrue(worst < 0.06, "Sprung von $worst m auf $step m")
    }

    @Test
    fun `uebrige Strassen liegen quer eben am Hang`() {
        val raw = RawHeights { _, n -> 0.25 * n }
        val far = TrackLine(PreviewSupport.curvyTrack().let { it.copy(e = FloatArray(it.sampleCount) { i -> it.e[i] + 5000f }) })
        val road = MapRoad(doubleArrayOf(-200.0, 0.0, 0.0, 3.0, 200.0, -2.0), "minor")
        val g = CarvedGround(raw, far, ListMapData(roads = listOf(road)))
        for (e in listOf(-150.0, -60.0, 40.0, 120.0)) {
            val nr = if (e < 0) 3 * (e + 200) / 200 else 3 - 5 * e / 200
            val a = g.heightAt(e, nr - 2.0)
            val b = g.heightAt(e, nr + 2.0)
            assertTrue(abs(a - b) < 0.02, "Strasse kippt bei e=$e: ${a - b}")
        }
        // Weiter weg wieder das Gelaende.
        assertEquals(raw.heightAt(0.0, 30.0), g.heightAt(0.0, 30.0), 1e-9)
    }

    @Test
    fun `unter Wasser liegt der Boden tiefer als am Ufer`() {
        val line = TrackLine(PreviewSupport.curvyTrack().let { it.copy(e = FloatArray(it.sampleCount) { i -> it.e[i] + 5000f }) })
        val water = WaterMask { e, n -> if (e * e + n * n < 50.0 * 50.0) 1.0 else 0.0 }
        val g = CarvedGround(RawHeights { _, _ -> 10.0 }, line, water = water)
        assertEquals(10.0, g.heightAt(100.0, 0.0), 1e-9)
        assertEquals(10.0, g.dryHeightAt(0.0, 0.0), 1e-9)
        assertEquals(10.0 - CarvedGround.WATER_GROUND_DEPTH_M, g.heightAt(0.0, 0.0), 1e-9)
    }

    @Test
    fun `Bankett ist mindestens eine Zellendiagonale breit`() {
        val line = TrackLine(PreviewSupport.curvyTrack())
        for (q in IndoorQuality.entries) {
            val cell = level0TerrainCellM(q)
            val g = CarvedGround(hills, line, terrainCellM = cell)
            assertTrue(g.bermM >= cell * Math.sqrt(2.0), "$q: Bankett ${g.bermM} m bei Zelle $cell m")
        }
    }

    @Test
    fun `eine Abfrage ist schnell genug fuer Millionen Aufrufe`() {
        val line = TrackLine(PreviewSupport.curvyTrack(lengthM = 900.0))
        val g = CarvedGround(hills, line)
        var s = 0.0
        val t0 = System.nanoTime()
        for (i in 0 until 200_000) s += g.heightAt(-300.0 + (i % 500) * 1.3, -300.0 + (i / 500) * 1.6)
        val us = (System.nanoTime() - t0) / 1000.0 / 200_000
        println("CarvedGround: $us µs je Abfrage")
        assertTrue(s.isFinite())
        assertTrue(us < 30.0, "$us µs je Abfrage")
    }
}
