package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ElevationTest {
    private val z = 13

    /** Kachel, deren Werte eine Funktion der globalen Pixelmitte sind. */
    private fun tile(x: Int, y: Int, f: (gx: Int, gy: Int) -> Float): ElevationTile =
        ElevationTile(TileId(z, x, y), 256, FloatArray(256 * 256) { f(x * 256 + it % 256, y * 256 + it / 256) })

    /** lat/lon zur globalen Pixelposition (Pixelmitte bei ganzen Zahlen). */
    private fun latLonAt(gx: Double, gy: Double): Pair<Double, Double> =
        TileMath.tileYToLat((gy + 0.5) / 256, z) to TileMath.tileXToLon((gx + 0.5) / 256, z)

    private val origin = GeoOrigin(51.055, 7.133, 100.0)

    @Test
    fun `Terrarium-Formel`() {
        assertEquals(0f, terrariumMeters(0xFF800000.toInt()))
        assertEquals(100.5f, terrariumMeters((0xFF shl 24) or (128 shl 16) or (100 shl 8) or 128))
        assertEquals(-32768f, terrariumMeters(0xFF000000.toInt()))
        val img = RasterImage(2, 2, intArrayOf(0xFF800000.toInt(), 0xFF800100.toInt(), 0xFF800080.toInt(), 0xFF810000.toInt()))
        val h = decodeTerrarium(img)
        assertEquals(listOf(0f, 1f, 0.5f, 256f), h.toList())
    }

    @Test
    fun `Interpolation ist ueber die Kachelgrenze nahtlos und exakt fuer Rampen`() {
        val ramp = { gx: Int, gy: Int -> 0.5f * (gx - 4258 * 256) + 0.25f * (gy - 2740 * 256) }
        val tiles = listOf(tile(4258, 2740, ramp), tile(4259, 2740, ramp), tile(4258, 2741, ramp), tile(4259, 2741, ramp))
        for (bicubic in listOf(true, false)) {
            val field = ElevationField(origin, z, tiles, bicubic = bicubic)
            // ueber die senkrechte Grenze bei gx = 4259·256 (Pixelmitten 4259·256 − 1 und 4259·256)
            val border = 4259 * 256.0
            var gx = border - 3.0
            while (gx <= border + 3.0) {
                for (gy in listOf(2740 * 256 + 100.3, 2741 * 256 - 0.5, 2741 * 256 + 0.25)) {
                    val (lat, lon) = latLonAt(gx, gy)
                    val expected = 0.5 * (gx - 4258 * 256) + 0.25 * (gy - 2740 * 256)
                    assertEquals(expected, field.heightAtLatLon(lat, lon).toDouble(), 0.02, "gx=$gx gy=$gy bikubisch=$bicubic")
                }
                gx += 0.125
            }
        }
    }

    @Test
    fun `Gitterabfrage gleich Einzelabfrage`() {
        val f = { gx: Int, gy: Int -> ((gx * 7 + gy * 13) % 50).toFloat() }
        val field = ElevationField(GeoOrigin(TileMath.tileYToLat(2740.5, z), TileMath.tileXToLon(4258.5, z), 0.0), z, listOf(tile(4258, 2740, f)))
        val grid = field.heightGrid(-500.0, -400.0, 37.5, 41.0, 20, 17)
        for (j in 0 until 17) for (i in 0 until 20) {
            assertEquals(field.heightAt(-500.0 + i * 37.5, -400.0 + j * 41.0), grid[j * 20 + i])
        }
    }

    @Test
    fun `Glaettung baut Treppenstufen ab und erhaelt Rampen, nahtlos ueber Kacheln`() {
        val terraces = { gx: Int, _: Int -> floor(gx / 8.0).toFloat() }
        val tiles = listOf(tile(4258, 2740, terraces), tile(4259, 2740, terraces))
        val raw = ElevationField(origin, z, tiles, bicubic = false)
        val smooth = ElevationField(origin, z, tiles, smoothingSigmaPx = 3.0, bicubic = false)
        fun maxStep(field: ElevationField): Double {
            var m = 0.0
            var gx = 4258 * 256 + 200.0
            var prev = Double.NaN
            while (gx < 4259 * 256 + 50.0) {
                val (lat, lon) = latLonAt(gx, 2740 * 256 + 128.0)
                val h = field.heightAtLatLon(lat, lon).toDouble()
                if (!prev.isNaN()) m = maxOf(m, abs(h - prev))
                prev = h
                gx += 0.5
            }
            return m
        }
        assertTrue(maxStep(raw) > 0.45)
        assertTrue(maxStep(smooth) < 0.12, "Glaettung zu schwach: ${maxStep(smooth)}")
        // Rampe bleibt Rampe, auch an der Kachelgrenze (Nachbar ist geladen)
        val ramp = { gx: Int, _: Int -> 0.3f * (gx - 4258 * 256) }
        val sm = ElevationField(origin, z, listOf(tile(4258, 2740, ramp), tile(4259, 2740, ramp)), smoothingSigmaPx = 2.0)
        for (gx in listOf(4259 * 256 - 2.0, 4259 * 256 - 0.5, 4259 * 256 + 1.0)) {
            val (lat, lon) = latLonAt(gx, 2740 * 256 + 128.0)
            assertEquals(0.3 * (gx - 4258 * 256), sm.heightAtLatLon(lat, lon).toDouble(), 0.01)
        }
    }

    @Test
    fun `fehlende Kachel ist ein Fehler, Meer ist null`() {
        val field = ElevationField(origin, z, listOf(tile(4258, 2740) { _, _ -> 50f }, ElevationTile(TileId(z, 4259, 2740), 256, null)))
        val (lat, lon) = latLonAt(4258 * 256 + 10.0, 2740 * 256 + 10.0)
        assertEquals(50f, field.heightAtLatLon(lat, lon), 1e-4f)
        val (sLat, sLon) = latLonAt(4259 * 256 + 100.0, 2740 * 256 + 10.0)
        assertEquals(0f, field.heightAtLatLon(sLat, sLon))
        // am Rand zur nicht geladenen Kachel wird geklemmt, nicht geworfen
        val (eLat, eLon) = latLonAt(4258 * 256 + 10.0, 2740 * 256 - 0.4)
        assertEquals(50f, field.heightAtLatLon(eLat, eLon), 1e-4f)
        val (mLat, mLon) = latLonAt(4258 * 256 + 10.0, 2741 * 256 + 10.0)
        val e = assertFailsWith<ElevationMissingException> { field.heightAtLatLon(mLat, mLon) }
        assertEquals(TileId(z, 4258, 2741), e.tile)
    }

    @Test
    fun `echte Terrarium-Kachel Altenberg`() {
        val bytes = resourceBytes("terrarium_13_4258_2740.png")
        val fetcher = GeoFetcher { req ->
            if (req.url.endsWith("/13/4258/2740.png")) GeoResponse(200, "image/png", bytes) else GeoResponse(404, null, ByteArray(0))
        }
        val t = fetchElevationTile(fetcher, imageIoDecoder, TileId(13, 4258, 2740))
        val h = t.heights!!
        assertTrue(h.min() in 70f..100f && h.max() in 200f..260f, "${h.min()} .. ${h.max()}")
        // Altenberger Dom liegt im Dhuenntal auf etwa 90–110 m
        val field = ElevationField(origin, 13, listOf(t), smoothingSigmaPx = 0.8)
        val dom = field.heightAtLatLon(51.0547, 7.1325)
        assertTrue(dom in 80f..120f, "Dom: $dom")
        assertEquals(TileId(13, 4259, 2740), runCatching { field.heightAtLatLon(51.06, 7.18) }
            .exceptionOrNull().let { (it as ElevationMissingException).tile })
        // Meer
        val sea = fetchElevationTile(fetcher, imageIoDecoder, TileId(13, 1, 1))
        assertEquals(null, sea.heights)
        val broken = GeoFetcher { GeoResponse(200, "image/png", byteArrayOf(1, 2, 3)) }
        assertFailsWith<ElevationMissingException> { fetchElevationTile(broken, imageIoDecoder, TileId(13, 1, 1)) }
        val error = GeoFetcher { GeoResponse(500, null, ByteArray(0)) }
        assertEquals(500, assertFailsWith<ElevationMissingException> { fetchElevationTile(error, imageIoDecoder, TileId(13, 1, 1)) }.status)
    }
}
