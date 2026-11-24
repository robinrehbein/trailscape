package de.trailscape.core.indoor.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Datenstufe „Laser": UTM, TIFF-Leser, LoD2-Leser, Positivliste — mit kleinen,
 * echten Ausschnitten der Dienste von Geobasis NRW unter `resources/indoor/laser/`
 * (abgerufen am 24.11.2026, dl-de/zero-2-0):
 *
 *  * `dgm1-nw-64.tif`: WCS `nw_dgm`, UTM32 464100…464164 / 5669900…5669964 (Kahler Asten), LZW,
 *  * `dom1-nw-16.tif`: WCS `nw_dom`, 464100…464116 / 5669900…5669916, unkomprimiert,
 *  * `lod2-nw-ein-haus.gml`: ein Gebaeude (Satteldach) aus `LoD2_32_464_5669_1_NW.gml`.
 *
 * Die Vergleichswerte stammen aus Pillow/NumPy (unabhaengiger Leser).
 */
class LaserTest {
    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/indoor/laser/$name")!!.use { it.readBytes() }

    @Test
    fun utmHinUndZurueck() {
        // Referenz: Snyder-Reihen (unabhaengig gerechnet), Kahler Asten.
        val xy = Utm.forward(51.17919, 8.49, 32)
        assertEquals(464351.624, xy[0], 0.01)
        assertEquals(5669875.413, xy[1], 0.01)
        for ((lat, lon) in listOf(51.17919 to 8.49, 50.5 to 6.1, 52.4 to 9.4, 51.0 to 7.0)) {
            val p = Utm.forward(lat, lon, 32)
            val ll = Utm.inverse(p[0], p[1], 32)
            assertEquals(lat, ll[0], 1e-8)
            assertEquals(lon, ll[1], 1e-8)
        }
    }

    @Test
    fun linearerRahmenIstImBlockZentimetergenau() {
        val origin = GeoOrigin(51.18, 8.48, 800.0)
        val f = EnuUtmFrame(origin, 32, 1000.0, -500.0)
        for ((e, n) in listOf(1000.0 to -500.0, 1180.0 to -320.0, 820.0 to -680.0)) {
            val exact = Utm.forward(origin.toLat(n), origin.toLon(e), 32)
            assertEquals(exact[0], f.utmX(e, n), 0.01)
            assertEquals(exact[1], f.utmY(e, n), 0.01)
            assertEquals(e, f.east(exact[0], exact[1]), 0.01)
            assertEquals(n, f.north(exact[0], exact[1]), 0.01)
        }
    }

    @Test
    fun tiffMitLzwWieReferenz() {
        val r = decodeFloatTiff(res("dgm1-nw-64.tif"))
        assertEquals(64, r.width)
        assertEquals(64, r.height)
        assertEquals(835.45f, r[0, 0], 1e-3f)
        assertEquals(838.31f, r[63, 0], 1e-3f)
        assertEquals(834.85f, r[0, 63], 1e-3f)
        assertEquals(838.59f, r[63, 63], 1e-3f)
        assertEquals(836.03f, r[13, 10], 1e-3f)
        assertEquals(3428085.5, r.data.sumOf { it.toDouble() }, 1.0)
        assertEquals(464100.0, r.originX, 1e-9)
        assertEquals(5669964.0, r.originY, 1e-9)
        assertEquals(1.0, r.pixelX, 1e-9)
        // Pixelmitte der Ecke oben links
        assertEquals(835.45f, r.sample(464100.5, 5669963.5), 1e-3f)
    }

    @Test
    fun tiffUnkomprimiert() {
        val r = decodeFloatTiff(res("dom1-nw-16.tif"))
        assertEquals(16, r.width)
        assertEquals(834.92f, r[0, 0], 1e-3f)
        assertEquals(835.99f, r[15, 0], 1e-3f)
        assertEquals(835.78f, r[15, 15], 1e-3f)
        assertEquals(835.81f, r[13, 10], 1e-3f)
        assertEquals(213868.125, r.data.sumOf { it.toDouble() }, 0.1)
    }

    @Test
    fun lod2EinHaus() {
        val origin = GeoOrigin(51.17, 8.49, 770.0)
        val list = parseLod2(res("lod2-nw-ein-haus.gml"), origin, 32)
        assertEquals(1, list.size)
        val b = list[0]
        assertEquals(3100, b.roofType)
        assertEquals(2.649, b.measuredHeightM, 1e-6)
        assertEquals(6, b.surfaces.count { it.kind == Lod2SurfaceKind.WALL })
        assertEquals(2, b.surfaces.count { it.kind == Lod2SurfaceKind.ROOF })
        assertEquals(1, b.surfaces.count { it.kind == Lod2SurfaceKind.GROUND })
        assertEquals(1, b.footprint.size)
        // Firsthoehe ueber dem Boden ungefaehr die gemessene Hoehe.
        assertEquals(b.measuredHeightM, b.topU - b.groundU, 0.3)
        // Lage: UTM 464077 / 5669040 liegt suedwestlich des Ursprungs.
        val p = Utm.forward(origin.lat, origin.lon, 32)
        assertEquals(464077.0 - p[0], b.ce, 15.0)
        assertEquals(5669040.0 - p[1], b.cn, 15.0)
    }

    @Test
    fun lod2KorridorRundreise() {
        val utm = parseLod2Utm(res("lod2-nw-ein-haus.gml"))
        val bytes = encodeLod2Corridor(32, utm)
        val (zone, back) = decodeLod2Corridor(bytes)
        assertEquals(32, zone)
        assertEquals(1, back.size)
        assertEquals(utm[0].id, back[0].id)
        assertEquals(utm[0].roofType, back[0].roofType)
        assertEquals(utm[0].measuredHeightM, back[0].measuredHeightM, 1e-4)
        // Gleiche Flaechen (ohne kollineare Zwischenpunkte), Punkte auf 1 cm.
        assertEquals(utm[0].surfaces.size, back[0].surfaces.size)
        for ((a, b) in utm[0].surfaces.zip(back[0].surfaces)) {
            assertEquals(a.kind, b.kind)
            val sa = Lod2Mesher.simplify(a.rings[0])
            assertEquals(sa.size, b.rings[0].size)
            for (i in sa.indices) assertEquals(sa[i], b.rings[0][i], 0.006)
        }
        // Umgerechnet wie direkt aus CityGML.
        val origin = GeoOrigin(51.17, 8.49, 770.0)
        val direct = parseLod2(res("lod2-nw-ein-haus.gml"), origin, 32)[0]
        val viaCorridor = back[0].toEnu(origin, zone)!!
        assertEquals(direct.groundU, viaCorridor.groundU, 0.01)
        assertEquals(direct.topU, viaCorridor.topU, 0.01)
        assertEquals(direct.ce, viaCorridor.ce, 0.05)
        assertTrue(bytes.size < res("lod2-nw-ein-haus.gml").size / 10, "Korridor ${bytes.size} Bytes")
    }

    @Test
    fun positivliste() {
        val nw = laserSources.first { it.id == "nw" }
        assertTrue(isAllowedLaserUrl(nw.coverageUrl(false, 464000, 5669000, 464256, 5669256)))
        assertTrue(isAllowedLaserUrl(nw.coverageUrl(true, 464000, 5669000, 464256, 5669256)))
        assertTrue(isAllowedLaserUrl(nw.lod2Url(464, 5669)!!))
        assertFalse(isAllowedLaserUrl("http://www.wcs.nrw.de/geobasis/wcs_nw_dgm?SERVICE=WCS&REQUEST=GetCoverage"))
        assertFalse(isAllowedLaserUrl("https://www.wcs.nrw.de/geobasis/wcs_nw_other?SERVICE=WCS&REQUEST=GetCoverage"))
        assertFalse(isAllowedLaserUrl("https://www.wcs.nrw.de/geobasis/wcs_nw_dgm?REQUEST=GetCoverage"))
        assertFalse(isAllowedLaserUrl("https://www.opengeodata.nrw.de/produkte/geobasis/3dg/lod2_gml/lod2_gml/LoD2_32_464_5669_1_NW.gml?x=1"))
        assertFalse(isAllowedLaserUrl("https://www.opengeodata.nrw.de/produkte/geobasis/3dg/lod2_gml/lod2_gml/../../secret"))
        assertFalse(isAllowedLaserUrl("https://evil.example/geobasis/wcs_nw_dgm?SERVICE=WCS&"))
        assertFalse(isAllowedGeoUrl(nw.coverageUrl(false, 0, 0, 1, 1), aerialSources))
    }

    @Test
    fun polylinienAbstand() {
        val d = PolylineDistance(doubleArrayOf(0.0, 100.0, 100.0), doubleArrayOf(0.0, 0.0, 100.0))
        assertEquals(10.0, d.distance(50.0, 10.0, 50.0), 1e-9)
        assertEquals(5.0, d.distance(105.0, 50.0, 50.0), 1e-9)
        assertEquals(Double.MAX_VALUE, d.distance(500.0, 500.0, 50.0))
    }
}
