package de.trailscape.core.indoor.world

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Laser-Stufe mit Kachel-Download (ZIP, Baden-Wuerttemberg): Kachelschema,
 * Positivliste, XYZ-Leser, ZIP-Eintraege, LoD2 im ZIP und — mit denselben
 * kuenstlichen Hoehen wie [SyntheticLaser] — dass die Bloecke aus Kacheln
 * dieselben Werte bekommen wie ueber den WCS.
 */
class LaserTilesTest {
    private val bw = laserSources.first { it.id == "bw" }

    @Test
    fun kachelschemaBadenWuerttemberg() {
        val t = bw.tiles!!
        // Geprueft: 501_5404, 503_5404, 501_5406 gibt es, 500_5404 und 501_5405 nicht.
        assertEquals(501, t.tileE(501)); assertEquals(501, t.tileE(502)); assertEquals(503, t.tileE(503)); assertEquals(499, t.tileE(500))
        assertEquals(5404, t.tileN(5404)); assertEquals(5404, t.tileN(5405)); assertEquals(5406, t.tileN(5406))
        assertEquals("https://opengeodata.lgl-bw.de/data/dgm/dgm1_32_501_5404_2_bw.zip", t.url(false, 501, 5404))
        assertEquals("https://opengeodata.lgl-bw.de/data/dom1/dom1_32_501_5404_2_bw.zip", t.url(true, 501, 5404))
        assertEquals("dgm1_32_502_5405_1_bw", t.entryPrefix(false, 502, 5405))
        assertEquals("https://opengeodata.lgl-bw.de/data/lod2/LoD2_32_501_5404_2_bw.zip", bw.lod2Url(502, 5405))
        assertEquals("LoD2_32_502_5405_1_BW.gml", bw.lod2EntryName(502, 5405))
        assertEquals(listOf("opengeodata.lgl-bw.de"), bw.hosts)
        // Die Quellen schliessen einander aus: kein Punkt in NRW und BW zugleich.
        val nw = laserSources.first { it.id == "nw" }
        assertTrue(nw.maxLat < bw.minLat || bw.maxLat < nw.minLat)
        assertTrue(bw.covers(48.80, 9.10)); assertFalse(nw.covers(48.80, 9.10))
        // NRW unveraendert: eine GML je Kilometer, kein Eintrag.
        assertEquals("https://www.opengeodata.nrw.de/produkte/geobasis/3dg/lod2_gml/lod2_gml/LoD2_32_464_5669_1_NW.gml", nw.lod2Url(464, 5669))
        assertNull(nw.lod2EntryName(464, 5669))
    }

    @Test
    fun positivlisteKacheln() {
        val t = bw.tiles!!
        assertTrue(isAllowedLaserUrl(t.url(false, 501, 5404)))
        assertTrue(isAllowedLaserUrl(t.url(true, 501, 5404)))
        assertTrue(isAllowedLaserUrl(bw.lod2Url(502, 5405)!!))
        assertFalse(isAllowedLaserUrl("https://opengeodata.lgl-bw.de/data/dgm/dgm1_32_501_5404_2_bw.zip?x=1"))
        assertFalse(isAllowedLaserUrl("https://opengeodata.lgl-bw.de/data/dop20/dop20rgb_32_501_5404_2_bw.zip"))
        assertFalse(isAllowedLaserUrl("https://opengeodata.lgl-bw.de/data/dgm/dgm1_32_x_5404_2_bw.zip"))
        assertFalse(isAllowedLaserUrl("https://opengeodata.lgl-bw.de/data/dgm/../alkis/a.zip"))
        assertFalse(isAllowedLaserUrl("http://opengeodata.lgl-bw.de/data/dgm/dgm1_32_501_5404_2_bw.zip"))
        assertFalse(isAllowedLaserUrl("https://evil.example/data/dgm/dgm1_32_501_5404_2_bw.zip"))
        assertFalse(isAllowedGeoUrl(t.url(false, 501, 5404), aerialSources))
    }

    @Test
    fun xyzLeser() {
        // Echte Zeilen aus dgm1_32_501_5404_1_bw_2019.xyz (LGL, abgerufen am 24.11.2026, dl-de/by-2-0).
        val text = "501000.50 5404999.50 374.46\n501001.50 5404999.50 374.40\r\n501998.50 5404000.50 392.27\n" +
            "501999.50 5404000.50 392.28\nunsinn\n503000.50 5404000.50 1.0\n"
        val r = parseXyzKm(text.toByteArray(), 501, 5404)
        assertEquals(1000, r.width)
        assertEquals(374.46f, r[0, 0], 1e-4f)
        assertEquals(374.40f, r[1, 0], 1e-4f)
        assertEquals(392.28f, r[999, 999], 1e-4f)
        assertTrue(r[5, 5].isNaN())
        // Am aeussersten Rand der naechste Pixel.
        assertEquals(392.28f, r.sample(501999.9, 5404000.1), 1e-4f)
    }

    @Test
    fun zipEintragOhneVerzeichnisUndMitEndung() {
        val zip = zipOf(
            "dgm1_32_501_5404_2_bw/dgm1_32_501_5404_1_bw_2019.csv" to "Kachelname".toByteArray(),
            "dgm1_32_501_5404_2_bw/dgm1_32_501_5404_1_bw_2019.xyz" to "501000.50 5404999.50 374.46\n".toByteArray(),
        )
        val r = readTileRaster(zip, bw.tiles!!, false, 501, 5404)!!
        assertEquals(374.46f, r[0, 0], 1e-4f)
        assertNull(readTileRaster(zip, bw.tiles!!, false, 502, 5404))
        assertNull(readTileRaster(zip, bw.tiles!!, true, 501, 5404))
    }

    @Test
    fun lod2AusDemZip() {
        val gml = javaClass.getResourceAsStream("/indoor/laser/lod2-nw-ein-haus.gml")!!.use { it.readBytes() }
        val zip = zipOf(
            "LoD2_32_501_5404_2_bw/INFO_OpenData_LoD2_2026.txt" to "x".toByteArray(),
            "LoD2_32_501_5404_2_bw/LoD2_32_502_5405_1_BW.gml" to gml,
        )
        var calls = 0
        val fetcher = LaserFetcher { url ->
            calls++
            if (url == bw.lod2Url(502, 5405)) GeoResponse(200, "application/zip", zip) else GeoResponse(404, null, ByteArray(0))
        }
        val kms = listOf(LaserData.key(501, 5404), LaserData.key(502, 5405), LaserData.key(503, 5404))
        val groups = lod2Downloads(bw, kms)
        assertEquals(2, groups.size, "zwei Archive fuer drei Kilometer")
        val (size, gmls) = fetchLod2(bw, fetcher, groups[0].first, groups[0].second)!!
        assertEquals(zip.size.toLong(), size)
        assertEquals(1, gmls.size)
        assertEquals(1, parseLod2Utm(gmls[0]).size)
        assertNull(fetchLod2(bw, fetcher, groups[1].first, groups[1].second))
        assertEquals(2, calls)
    }

    @Test
    fun kachelnFuellenDieBloeckeWieDerWcs() {
        val route = shortRoute()
        val origin = GeoOrigin(51.0525, 7.1280, 0.0)
        val lineE = route.points.map { origin.toEast(it.lon) }.toDoubleArray()
        val lineN = route.points.map { origin.toNorth(it.lat) }.toDoubleArray()
        val wcs = loadLaserData(LaserOptions(SyntheticLaser(), sources = listOf(TEST_LASER), radiusM = 140.0, fadeM = 40.0, buildings = false, threads = 2), origin, lineE, lineN, { _, _ -> 150.0 })!!
        val zips = SyntheticTiles()
        val tiles = loadLaserData(LaserOptions(zips, sources = listOf(TEST_TILES), radiusM = 140.0, fadeM = 40.0, buildings = false, threads = 2), origin, lineE, lineN, { _, _ -> 150.0 })!!
        assertEquals(wcs.blockCount, tiles.blockCount)
        assertEquals("test-zip", tiles.source.id)
        assertTrue(zips.requests in 2..16, "${zips.requests} Anfragen")
        assertEquals(zips.requests, tiles.stats.requests)
        var n = 0
        var sum = 0.0
        var worst = 0.0
        var ndomSame = 0
        var ndomAll = 0
        for (ie in -800..800 step 3) for (inn in -500..300 step 3) {
            val a = wcs.dgmAt(ie.toDouble(), inn.toDouble())
            val b = tiles.dgmAt(ie.toDouble(), inn.toDouble())
            assertEquals(a.isNaN(), b.isNaN(), "Daten an $ie/$inn")
            if (a.isNaN()) continue
            n++
            sum += abs(a - b)
            worst = max(worst, abs(a - b))
            val da = wcs.ndomAt(ie, inn)
            val db = tiles.ndomAt(ie, inn)
            ndomAll++
            if (abs(da - db) <= 0.21) ndomSame++
        }
        println("Kacheln gegen WCS: $n Proben, mittel ${sum / n} m, hoechstens $worst m, nDOM gleich $ndomSame/$ndomAll")
        assertTrue(n > 10_000)
        assertTrue(sum / n < 0.005, "mittlere Abweichung ${sum / n}")
        assertTrue(worst < 0.6, "hoechste Abweichung $worst (nur am Kilometerrand)")
        assertTrue(ndomSame >= ndomAll * 0.99, "nDOM $ndomSame/$ndomAll")
        assertEquals(wcs.offsetM, tiles.offsetM, 0.01)
    }

    @Test
    fun voranschlagZaehltGanzeArchive() {
        val opts = LaserOptions(SyntheticTiles(), sources = listOf(TEST_TILES), radiusM = 140.0)
        val builder = WorldBuilderImpl(FullWorldParts, aerialRegister = listOf(TEST_AERIAL), clock = { 1_700_000_000_000L }, laser = opts)
        val plain = WorldBuilderImpl(FullWorldParts, aerialRegister = listOf(TEST_AERIAL), clock = { 1_700_000_000_000L })
        val route = shortRoute()
        val plan = builder.plan(route, IndoorQuality.LOW)
        val extra = plan.downloadBytes - plain.plan(route, IndoorQuality.LOW).downloadBytes
        assertTrue(extra > 0 && extra % TEST_TILES.tiles!!.bytesPerTile == 0L, "$extra")
        assertTrue("laser-zip.example.org" in plan.hosts)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bo.toByteArray()
    }
}

/** Wie [TEST_LASER], aber als Kachel-Download (2-km-Archive mit Kilometerdateien, wie Baden-Wuerttemberg). */
internal val TEST_TILES = LaserSource(
    id = "test-zip",
    label = "Testland (Kacheln)",
    minLat = 50.0,
    minLon = 6.0,
    maxLat = 52.0,
    maxLon = 8.0,
    utmZone = 32,
    dgmWcsUrl = null,
    dgmCoverage = null,
    domWcsUrl = null,
    domCoverage = null,
    lod2UrlTemplate = null,
    tiles = LaserTiles(
        dgmUrl = "https://laser-zip.example.org/dgm/dgm_{e}_{n}_2.zip",
        dgmEntry = "dgm_{e}_{n}_1",
        dgmFormat = LaserRasterFormat.XYZ,
        domUrl = "https://laser-zip.example.org/dom/dom_{e}_{n}_2.zip",
        domEntry = "dom_{e}_{n}_1",
        domFormat = LaserRasterFormat.TIFF,
        sizeKm = 2,
        originE = 1,
        originN = 0,
        bytesPerTile = 1_000_000L,
    ),
    attribution = "Gelände: Testdienst (Kacheln)",
    license = "CC0",
    licenseUrl = "https://example.org/lizenz",
)

/** Kuenstliche ZIP-Archive zu [TEST_TILES] mit den Hoehen von [SyntheticLaser.value]. */
internal class SyntheticTiles : LaserFetcher {
    @Volatile var requests = 0

    override fun fetch(url: String): GeoResponse {
        synchronized(this) { requests++ }
        val m = Regex("/(dgm|dom)/(dgm|dom)_(\\d+)_(\\d+)_2\\.zip$").find(url) ?: return GeoResponse(404, null, ByteArray(0))
        val dom = m.groupValues[1] == "dom"
        val e0 = m.groupValues[3].toInt()
        val n0 = m.groupValues[4].toInt()
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for (kx in e0 until e0 + 2) for (ky in n0 until n0 + 2) {
                val name = "${if (dom) "dom" else "dgm"}_${kx}_${ky}_1"
                // Kurzer Koeder mit demselben Anfang (wie die CSV-Metadaten des LGL).
                z.putNextEntry(ZipEntry("archiv/${name}_2019.csv"))
                z.write("Kachelname".toByteArray())
                z.closeEntry()
                if (dom) {
                    val data = FloatArray(1000 * 1000)
                    for (j in 0 until 1000) for (i in 0 until 1000) data[j * 1000 + i] = SyntheticLaser.value(kx * 1000 + i + 0.5, ky * 1000 + 999.5 - j, true).toFloat()
                    z.putNextEntry(ZipEntry("archiv/${name}_2019.tif"))
                    z.write(SyntheticLaser.floatTiff(1000, 1000, kx * 1000.0, ky * 1000.0 + 1000.0, data))
                } else {
                    z.putNextEntry(ZipEntry("archiv/${name}_2019.xyz"))
                    val sb = StringBuilder(30 * 1000 * 1000)
                    for (j in 0 until 1000) for (i in 0 until 1000) {
                        val x = kx * 1000 + i
                        val y = ky * 1000 + 999 - j
                        val v = Math.round(SyntheticLaser.value(x + 0.5, y + 0.5, false) * 1000)
                        sb.append(x).append(".50 ").append(y).append(".50 ").append(v / 1000).append('.')
                        val f = (v % 1000).toInt()
                        if (f < 100) sb.append('0')
                        if (f < 10) sb.append('0')
                        sb.append(f).append('\n')
                    }
                    z.write(sb.toString().toByteArray())
                }
                z.closeEntry()
            }
        }
        return GeoResponse(200, "application/zip", bo.toByteArray())
    }
}
