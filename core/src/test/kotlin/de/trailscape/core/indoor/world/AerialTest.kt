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
}
