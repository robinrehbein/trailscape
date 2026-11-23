package de.trailscape.tool.indoor

import de.trailscape.core.indoor.world.GeoFetcher
import de.trailscape.core.indoor.world.GeoRequest
import de.trailscape.core.indoor.world.GeoResponse
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.RasterDecoder
import de.trailscape.core.indoor.world.RasterImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Roher Geodaten-Abruf des Werkzeugs (docs/indoor.md 8.3) ueber
 * `java.net.http`: **keine** Weiterleitungen, Timeouts 15 s (Verbindung) und
 * 30 s (Antwort), `User-Agent: Trailscape-indoor-dev`, kein
 * `Accept-Encoding` (der Client entpackt nicht selbst; die Quellen liefern
 * dann unkomprimiert). Proxy und Truststore kommen aus der JVM
 * (`JAVA_TOOL_OPTIONS`), hier wird nichts davon gesetzt — nur der
 * Standard-Proxy-Waehler der JVM benutzt.
 *
 * Positivliste, Cache und Drosselung legt `createGeoPipeline` darum.
 */
class JavaHttpFetcher : GeoFetcher {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .proxy(ProxySelector.getDefault())
        .build()

    override fun fetch(request: GeoRequest): GeoResponse {
        val b = HttpRequest.newBuilder(URI(request.url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .GET()
        request.accept?.let { b.header("Accept", it) }
        val resp = try {
            client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Abruf unterbrochen: ${request.url}", e)
        }
        return GeoResponse(resp.statusCode(), resp.headers().firstValue("Content-Type").orElse(null), resp.body())
    }

    companion object {
        const val USER_AGENT = "Trailscape-indoor-dev"
    }
}

/** Fuer `--offline`: jeder Netzabruf scheitert, die Huelle bedient dann aus dem Cache (auch Abgelaufenes). */
class OfflineFetcher : GeoFetcher {
    override fun fetch(request: GeoRequest): GeoResponse = throw IOException("offline (--offline): ${request.url}")
}

/** Zaehlt die Antworten der Huelle je Quelle, getrennt nach Netz und Cache. */
class CountingFetcher(private val inner: GeoFetcher) : GeoFetcher {
    val network = ConcurrentHashMap<GeoSourceKind, AtomicInteger>()
    val cache = ConcurrentHashMap<GeoSourceKind, AtomicInteger>()
    val failed = AtomicInteger()

    override fun fetch(request: GeoRequest): GeoResponse {
        val resp = try {
            inner.fetch(request)
        } catch (e: IOException) {
            failed.incrementAndGet()
            throw e
        }
        (if (resp.fromCache) cache else network).computeIfAbsent(request.kind) { AtomicInteger() }.incrementAndGet()
        return resp
    }

    fun summary(): String = GeoSourceKind.entries.joinToString(", ") { k ->
        "${k.name.lowercase()} ${network[k]?.get() ?: 0} Netz / ${cache[k]?.get() ?: 0} Cache"
    } + if (failed.get() > 0) ", ${failed.get()} gescheitert" else ""
}

/**
 * `ImageIO` als [RasterDecoder] — **ohne Farbraumumwandlung**: Die Pixel
 * werden roh aus dem Raster gelesen (Palette ueber die Palette selbst), damit
 * die Terrarium-Hoehen exakt stimmen. Nur unbekannte Bildarten gehen ueber
 * `getRGB`.
 */
object ImageIoDecoder : RasterDecoder {
    override fun decode(bytes: ByteArray): RasterImage? = try {
        ImageIO.read(ByteArrayInputStream(bytes))?.let { img ->
            val w = img.width
            val h = img.height
            val raster = img.raster
            val cm = img.colorModel
            val out = IntArray(w * h)
            val bands = raster.numBands
            val bits = raster.sampleModel.getSampleSize(0)
            val shift = (bits - 8).coerceAtLeast(0)
            when {
                cm is IndexColorModel -> {
                    val row = IntArray(w)
                    for (y in 0 until h) {
                        raster.getSamples(0, y, w, 1, 0, row)
                        for (x in 0 until w) out[y * w + x] = cm.getRGB(row[x])
                    }
                }
                bits in 8..16 && (bands == 3 || bands == 4) -> {
                    val px = IntArray(w * bands)
                    for (y in 0 until h) {
                        raster.getPixels(0, y, w, 1, px)
                        for (x in 0 until w) {
                            val r = px[x * bands] ushr shift
                            val g = px[x * bands + 1] ushr shift
                            val b = px[x * bands + 2] ushr shift
                            val a = if (bands == 4) px[x * bands + 3] ushr shift else 255
                            out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                bits in 8..16 && (bands == 1 || bands == 2) -> {
                    val px = IntArray(w * bands)
                    for (y in 0 until h) {
                        raster.getPixels(0, y, w, 1, px)
                        for (x in 0 until w) {
                            val v = px[x * bands] ushr shift
                            val a = if (bands == 2) px[x * bands + 1] ushr shift else 255
                            out[y * w + x] = (a shl 24) or (v shl 16) or (v shl 8) or v
                        }
                    }
                }
                else -> img.getRGB(0, 0, w, h, out, 0, w)
            }
            RasterImage(w, h, out)
        }
    } catch (_: Exception) {
        null
    }
}
