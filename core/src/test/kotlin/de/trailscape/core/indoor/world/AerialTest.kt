package de.trailscape.core.indoor.world

import java.io.IOException
import kotlin.test.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AerialTest {
    private val nwJpeg = resourceBytes("aerial_nw_256.jpg")
    private val blank = resourceBytes("aerial_nw_blank_256.jpg")
    private val progressive = resourceBytes("aerial_be_progressive_256.jpg")
    private val xml = resourceBytes("wms_exception.xml")
    private val origin = GeoOrigin(51.0547, 7.1325, 100.0)

    @Test
    fun `Progressiv und Baseline werden unterschieden`() {
        assertFalse(isProgressiveJpeg(nwJpeg))
        assertTrue(isProgressiveJpeg(progressive))
        assertFalse(isProgressiveJpeg(xml))
        assertFalse(isProgressiveJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertTrue(isJpeg(nwJpeg))
        assertFalse(isPng(nwJpeg))
    }

    @Test
    fun `Leerbilder und WMS-Fehler werden erkannt`() {
        assertTrue(isBlankImage(imageIoDecoder.decode(blank)!!))
        assertFalse(isBlankImage(imageIoDecoder.decode(nwJpeg)!!))
        // fast einfarbig mit leichtem Rauschen
        val noisy = IntArray(64 * 64) { 0xFF000000.toInt() or (0xF0 + (it * 7919) % 2 shl 16) or (0xF0 shl 8) or 0xF0 }
        assertTrue(isBlankImage(RasterImage(64, 64, noisy)))
        // durchsichtig
        assertTrue(isBlankImage(RasterImage(4, 4, IntArray(16) { it * 1234567 and 0x00FFFFFF })))
        assertTrue(isWmsError("text/xml; charset=UTF-8", xml))
        assertTrue(isWmsError(null, xml))
        assertTrue(isWmsError("image/jpeg", "﻿<ServiceExceptionReport/>".toByteArray()))
        assertFalse(isWmsError("image/jpeg", nwJpeg))
    }

    private val nw = aerialSources.first { it.id == "nw" }

    @Test
    fun `Abruf je Rechteck mit Groessengrenze und URL der Positivliste`() {
        val seen = ArrayList<GeoRequest>()
        val fetcher = GeoFetcher { req ->
            seen += req
            assertTrue(isAllowedGeoUrl(req.url, aerialSources), req.url)
            GeoResponse(200, "image/jpeg", nwJpeg)
        }
        val img = assertNotNull(fetchAerial(fetcher, imageIoDecoder, origin, -128.0, -128.0, 128.0, 128.0, 4096, aerialSources))
        assertEquals("image/jpeg", img.mimeType)
        assertContentEquals(nwJpeg, img.bytes)
        assertEquals(256, img.width)
        assertEquals("nw", img.source.id)
        assertEquals(1, seen.size)
        assertTrue(seen[0].url.contains("&WIDTH=${nw.maxSizePx}&HEIGHT=${nw.maxSizePx}&"), seen[0].url)
        assertEquals(GeoSourceKind.AERIAL, seen[0].kind)
        // Seitenverhaeltnis
        seen.clear()
        fetchAerial(fetcher, imageIoDecoder, origin, 0.0, 0.0, 200.0, 100.0, 512, aerialSources)
        assertTrue(seen[0].url.contains("&WIDTH=512&HEIGHT=256&"), seen[0].url)
    }

    @Test
    fun `progressives JPEG wird als PNG neu angefragt`() {
        val pngBytes = java.io.ByteArrayOutputStream().also { out ->
            javax.imageio.ImageIO.write(javax.imageio.ImageIO.read(nwJpeg.inputStream()), "png", out)
        }.toByteArray()
        val formats = ArrayList<String>()
        val fetcher = GeoFetcher { req ->
            val fmt = req.url.substringAfter("FORMAT=")
            formats += fmt
            if (fmt == "image/png") GeoResponse(200, "image/png", pngBytes) else GeoResponse(200, "image/jpeg", progressive)
        }
        val img = assertNotNull(fetchAerial(fetcher, imageIoDecoder, origin, -100.0, -100.0, 100.0, 100.0, 256, aerialSources))
        assertEquals(listOf("image/jpeg", "image/png"), formats)
        assertEquals("image/png", img.mimeType)
        assertContentEquals(pngBytes, img.bytes)
    }

    @Test
    fun `ausserhalb, Leerbild, Fehler und Netzausfall ergeben null`() {
        val blankFetcher = GeoFetcher { GeoResponse(200, "image/jpeg", blank) }
        assertNull(fetchAerial(blankFetcher, imageIoDecoder, origin, 0.0, 0.0, 100.0, 100.0, 256, aerialSources))
        val xmlFetcher = GeoFetcher { GeoResponse(200, "text/xml", xml) }
        assertNull(fetchAerial(xmlFetcher, imageIoDecoder, origin, 0.0, 0.0, 100.0, 100.0, 256, aerialSources))
        val errFetcher = GeoFetcher { GeoResponse(500, null, ByteArray(0)) }
        assertNull(fetchAerial(errFetcher, imageIoDecoder, origin, 0.0, 0.0, 100.0, 100.0, 256, aerialSources))
        val offline = GeoFetcher { throw IOException("kein Netz") }
        assertNull(fetchAerial(offline, imageIoDecoder, origin, 0.0, 0.0, 100.0, 100.0, 256, aerialSources))
        // Bayern liegt nicht im Register → kein Abruf
        val never = GeoFetcher { throw AssertionError("kein Abruf erwartet") }
        assertNull(fetchAerial(never, imageIoDecoder, GeoOrigin(48.14, 11.57, 500.0), 0.0, 0.0, 100.0, 100.0, 256, listOf(nw)))
        assertTrue(selectAerialSources(listOf(nw), LatLonBox(48.0, 11.0, 48.1, 11.1)).isEmpty())
    }

    @Test
    fun `bei Leerbild wird der naechste passende Dienst gefragt`() {
        val other = nw.copy(id = "xx", wmsUrl = "https://example.org/wms", layer = "dop")
        val asked = ArrayList<String>()
        val fetcher = GeoFetcher { req ->
            asked += req.url.substringBefore("?")
            if (req.url.startsWith(nw.wmsUrl)) GeoResponse(200, "image/jpeg", blank) else GeoResponse(200, "image/jpeg", nwJpeg)
        }
        val img = assertNotNull(fetchAerial(fetcher, imageIoDecoder, origin, 0.0, 0.0, 100.0, 100.0, 256, listOf(nw, other)))
        assertEquals("xx", img.source.id)
        assertEquals(listOf(nw.wmsUrl, "https://example.org/wms"), asked)
    }

    private val bw = aerialSources.first { it.id == "bw" }
    private val bwJpeg = resourceBytes("aerial_bw_256.jpg")
    private val bwBlank = resourceBytes("aerial_bw_blank_256.jpg")

    @Test
    fun `Baden-Wuerttemberg steht im Register mit LGL-Dienst und Pflichtvermerk`() {
        assertEquals("https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C", bw.wmsUrl)
        assertEquals("IMAGES_DOP_20_RGB", bw.layer)
        assertEquals("dl-de/by-2-0", bw.license)
        assertTrue(bw.attribution.contains("Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0"), bw.attribution)
        assertTrue(Regex("""Datenbezug 20\d\d""").containsMatchIn(bw.attribution), bw.attribution)
        // Strohgaeu, Schauinsland, Maulbronn, Bodensee (Konstanz) drin; NRW, Sachsen, Muenchen, Strassburg-Ost nicht
        for ((lat, lon) in listOf(48.84 to 9.03, 47.91 to 7.90, 49.00 to 8.81, 47.66 to 9.17)) assertTrue(bw.covers(lat, lon), "$lat,$lon")
        for ((lat, lon) in listOf(51.05 to 7.13, 51.05 to 13.74, 48.14 to 11.57)) assertFalse(bw.covers(lat, lon), "$lat,$lon")
        // Kein anderer Dienst der App ueberlappt BW: dort fragt der Weltbau nur das LGL.
        assertEquals(listOf("bw"), selectAerialSources(aerialSources, LatLonBox(48.83, 9.02, 48.85, 9.04)).map { it.id })
        val others = aerialSources.filter { it.id != "bw" }
        assertTrue(others.none { it.maxLat >= bw.minLat && it.minLat <= bw.maxLat && it.maxLon >= bw.minLon && it.minLon <= bw.maxLon })
    }

    @Test
    fun `Luftbild Baden-Wuerttemberg wird abgerufen und eingebettet, Leerbild ausserhalb ergibt null`() {
        val strohgaeu = GeoOrigin(48.8392, 9.0537, 300.0)
        val urls = ArrayList<String>()
        val fetcher = GeoFetcher { req ->
            urls += req.url
            assertTrue(isAllowedGeoUrl(req.url, aerialSources), req.url)
            GeoResponse(200, "image/jpeg", bwJpeg)
        }
        val img = assertNotNull(fetchAerial(fetcher, imageIoDecoder, strohgaeu, -128.0, -128.0, 128.0, 128.0, 256, aerialSources))
        assertEquals("bw", img.source.id)
        assertEquals("image/jpeg", img.mimeType)
        assertFalse(isProgressiveJpeg(bwJpeg))
        assertContentEquals(bwJpeg, img.bytes)
        assertEquals(1, urls.size)
        assertTrue(urls.single().startsWith(bw.wmsUrl + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=IMAGES_DOP_20_RGB&STYLES=&CRS=EPSG:4326&BBOX=48.83"), urls.single())
        assertFalse(isBlankImage(imageIoDecoder.decode(bwJpeg)!!))
        // Das LGL liefert ausserhalb der Landesgrenze (hier Strassburg) ein weisses Bild.
        assertTrue(isBlankImage(imageIoDecoder.decode(bwBlank)!!))
        val strassburg = GeoOrigin(48.571, 7.742, 140.0)
        assertNull(fetchAerial(GeoFetcher { GeoResponse(200, "image/jpeg", bwBlank) }, imageIoDecoder, strassburg, 0.0, 0.0, 100.0, 100.0, 256, aerialSources))
        // Nur der Dienstpfad ist erlaubt, nicht andere Pfade auf demselben Host.
        assertFalse(isAllowedGeoUrl("https://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DGM?SERVICE=WMS", aerialSources))
        assertFalse(isAllowedGeoUrl("http://owsproxy.lgl-bw.de/owsproxy/ows/WMS_LGL-BW_ATKIS_DOP_20_C?SERVICE=WMS", aerialSources))
    }
}
