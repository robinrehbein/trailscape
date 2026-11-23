package de.trailscape.core.indoor.world

import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TileMathTest {
    @Test
    fun `Kachelrechnung gegen bekannte Werte`() {
        // Altenberg (Odenthal): Referenz aus der Standardformel (OSM-Wiki „Slippy map tilenames")
        assertEquals(8516.630755555556, TileMath.lonToTileX(7.133, 14), 1e-9)
        assertEquals(5481.015012121217, TileMath.latToTileY(51.055, 14), 1e-9)
        assertEquals(TileId(14, 8516, 5481), TileMath.tileOf(51.055, 7.133, 14))
        assertEquals(TileId(13, 4258, 2740), TileMath.tileOf(51.055, 7.133, 13))
        assertEquals(TileId(0, 0, 0), TileMath.tileOf(0.0, 0.0, 0))
        assertEquals(85.0511287798066, TileMath.tileYToLat(0.0, 3), 1e-9)
        assertEquals(0.0, TileMath.tileYToLat(4.0, 3), 1e-12)
        assertEquals(-180.0, TileMath.tileXToLon(0.0, 5), 0.0)
        // Hin und zurueck
        for (lat in listOf(-60.0, 0.0, 47.3, 51.055, 54.9)) {
            assertEquals(lat, TileMath.tileYToLat(TileMath.latToTileY(lat, 13), 13), 1e-9)
        }
        val (fx, fy) = TileMath.fractionInTile(51.055, 7.133, 14)
        assertEquals(0.630755555556, fx, 1e-6)
        assertEquals(0.015012121217, fy, 1e-6)
    }

    @Test
    fun `Kachelgrenzen und Abdeckung`() {
        val b = TileMath.tileBounds(TileId(14, 8516, 5481))
        assertTrue(b.minLat < 51.055 && 51.055 < b.maxLat && b.minLon < 7.133 && 7.133 < b.maxLon)
        // Nachbarn teilen die Grenze exakt
        val north = TileMath.tileBounds(TileId(14, 8516, 5480))
        assertEquals(b.maxLat, north.minLat, 0.0)
        val tiles = TileMath.tilesCovering(LatLonBox(51.05, 7.12, 51.06, 7.14), 14)
        assertTrue(TileId(14, 8516, 5481) in tiles)
        assertTrue(tiles.all { t -> tiles.count { it == t } == 1 })
        assertEquals(tiles.first(), TileId(14, tiles.minOf { it.x }, tiles.minOf { it.y }))
    }

    @Test
    fun `URL-Bau Terrarium und Vorlage`() {
        assertEquals(
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/13/4258/2740.png",
            TileMath.elevationUrl(TileId(13, 4258, 2740)),
        )
        assertEquals(
            "https://tiles.openfreemap.org/planet/20261115_080022_pt/14/8516/5481.pbf",
            TileMath.fillTemplate("https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf", TileId(14, 8516, 5481)),
        )
    }

    @Test
    fun `WMS-URL exakt nach Muster, Locale-unabhaengig und auf der Positivliste`() {
        val nw = aerialSources.first { it.id == "nw" }
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val url = TileMath.wmsGetMapUrl(nw, LatLonBox(51.05355, 7.13067, 51.05585, 7.13433), 256, 256)
            assertEquals(
                "https://www.wms.nrw.de/geobasis/wms_nw_dop?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=nw_dop_rgb" +
                    "&STYLES=&CRS=EPSG:4326&BBOX=51.05355,7.13067,51.05585,7.13433&WIDTH=256&HEIGHT=256&FORMAT=image/jpeg",
                url,
            )
            assertTrue(isAllowedGeoUrl(url, aerialSources))
            val png = TileMath.wmsGetMapUrl(nw, LatLonBox(1e-7, -0.0000001, 2.0, 3.0), 10, 20, "image/png")
            assertTrue(png.contains("&BBOX=0.0000001,-0.0000001,2,3&WIDTH=10&HEIGHT=20&FORMAT=image/png"), png)
        } finally {
            Locale.setDefault(old)
        }
        assertEquals("0", TileMath.coord(-0.0))
        assertEquals("7.123456789", TileMath.coord(7.1234567891))
        assertTrue(TileMath.coord(1e-12).none { it == 'E' || it == 'e' })
    }

    @Test
    fun `TileJSON wird gelesen`() {
        val tj = assertNotNull(parseTileJson(String(resourceBytes("tilejson.json"), Charsets.UTF_8)))
        assertEquals("https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf", tj.tileTemplate)
        assertEquals(14, tj.maxZoom)
        assertEquals(0, tj.minZoom)
        assertTrue(tj.attribution!!.contains("OpenStreetMap"))
        assertTrue(isAllowedGeoUrl(TileMath.fillTemplate(tj.tileTemplate, TileId(14, 1, 2)), emptyList()))
        assertNull(parseTileJson("kein json"))
        assertNull(parseTileJson("""{"tiles":[]}"""))
        assertNull(parseTileJson("""{"tiles":["http://x/{z}/{x}/{y}"]}"""))
        assertTrue(abs(TileMath.lonToTileX(180.0, 2) - 4.0) < 1e-12)
    }
}
