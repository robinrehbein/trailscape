package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Testhilfen des Weltbau-Rahmens: eine Attrappe aller Geodatenquellen (ohne
 * Netz), eine kurze Strecke bei Altenberg (dort liegen die abgelegten echten
 * Vektorkacheln) und kleine Datei-Helfer.
 */

/** Luftbild-Dienst der Attrappe (Host nur fuer Tests). */
internal val TEST_AERIAL = AerialSource(
    id = "test",
    label = "Testland",
    wmsUrl = "https://aerial.example.org/wms",
    layer = "rgb",
    minLat = 50.0,
    minLon = 6.0,
    maxLat = 52.0,
    maxLon = 8.0,
    attribution = "Luftbild: Testdienst",
    license = "CC0",
    licenseUrl = "https://example.org/lizenz",
)

/**
 * Attrappe der Geodatenquellen: synthetische Terrarium-PNGs, die abgelegten
 * echten Vektorkacheln 14/8516/5480 und 5481 (sonst 404), Luftbilder als
 * erzeugte Baseline-JPEGs. Zaehlt die Anfragen je Quelle.
 */
internal class FakeGeo(
    var elevation: Mode = Mode.OK,
    var aerial: Mode = Mode.OK,
    val missingVector: Set<String> = emptySet(),
) : GeoFetcher {
    enum class Mode { OK, IO_ERROR, HTTP_ERROR, BLANK }

    val counts = ConcurrentHashMap<GeoSourceKind, AtomicInteger>()
    val urls: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val pngs = ConcurrentHashMap<String, ByteArray>()
    private val jpegs = ConcurrentHashMap<String, ByteArray>()

    fun count(kind: GeoSourceKind): Int = counts[kind]?.get() ?: 0

    fun reset() {
        counts.clear()
        urls.clear()
    }

    override fun fetch(request: GeoRequest): GeoResponse {
        require(isAllowedGeoUrl(request.url, listOf(TEST_AERIAL))) { "nicht erlaubt: ${request.url}" }
        counts.computeIfAbsent(request.kind) { AtomicInteger() }.incrementAndGet()
        urls += request.url
        return when (request.kind) {
            GeoSourceKind.ELEVATION -> elevation(request.url)
            GeoSourceKind.VECTOR -> vector(request.url)
            GeoSourceKind.AERIAL -> aerial(request.url)
        }
    }

    private fun elevation(url: String): GeoResponse {
        when (elevation) {
            Mode.IO_ERROR -> throw IOException("kein Netz (Attrappe)")
            Mode.HTTP_ERROR -> return GeoResponse(500, "text/plain", "Fehler".toByteArray())
            else -> Unit
        }
        val m = Regex("/terrarium/(\\d+)/(\\d+)/(\\d+)\\.png$").find(url) ?: return GeoResponse(404, null, ByteArray(0))
        val (z, x, y) = m.destructured.toList().map { it.toInt() }
        val png = pngs.computeIfAbsent(url) {
            val img = RgbaImage(256, 256)
            for (py in 0 until 256) for (px in 0 until 256) {
                val lat = TileMath.tileYToLat(y + (py + 0.5) / 256, z)
                val lon = TileMath.tileXToLon(x + (px + 0.5) / 256, z)
                val v = syntheticHeight(lat, lon) + 32768.0
                val r = (v / 256).toInt()
                val g = floor(v).toInt() % 256
                val b = ((v - floor(v)) * 256).toInt()
                img[px, py] = Argb.rgb(r, g, b)
            }
            encodePng(img, withAlpha = false, level = 1)
        }
        return GeoResponse(200, "image/png", png)
    }

    private fun vector(url: String): GeoResponse {
        if (url == GeoEndpoints.VECTOR_TILEJSON_URL) return GeoResponse(200, "application/json", resourceBytes("tilejson.json"))
        val m = Regex("/(\\d+)/(\\d+)/(\\d+)\\.pbf$").find(url) ?: return GeoResponse(404, null, ByteArray(0))
        val key = m.destructured.toList().joinToString("/")
        if (key in missingVector) return GeoResponse(500, "text/plain", ByteArray(0))
        return when (key) {
            "14/8516/5480" -> GeoResponse(200, "application/x-protobuf", resourceBytes("vector_14_8516_5480.pbf"))
            "14/8516/5481" -> GeoResponse(200, "application/x-protobuf", resourceBytes("vector_14_8516_5481.pbf"))
            else -> GeoResponse(404, null, ByteArray(0))
        }
    }

    private fun aerial(url: String): GeoResponse {
        when (aerial) {
            Mode.IO_ERROR -> throw IOException("kein Netz (Attrappe)")
            Mode.HTTP_ERROR -> return GeoResponse(503, "text/plain", ByteArray(0))
            else -> Unit
        }
        val w = Regex("WIDTH=(\\d+)").find(url)!!.groupValues[1].toInt()
        val h = Regex("HEIGHT=(\\d+)").find(url)!!.groupValues[1].toInt()
        val blank = aerial == Mode.BLANK
        val bytes = jpegs.computeIfAbsent("$w/$h/$blank") {
            val img = RgbaImage(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                img[x, y] = if (blank) Argb.WHITE else {
                    val n = Noise.hash01(x / 4, y / 4, 99)
                    Argb.rgb((70 + 60 * n).toInt(), (90 + 50 * n).toInt(), (50 + 30 * n).toInt())
                }
            }
            encodeJpegBaseline(img, 80)
        }
        return GeoResponse(200, "image/jpeg", bytes)
    }

    companion object {
        /** Sanfte Huegel um 150 m ueber dem Meer. */
        fun syntheticHeight(lat: Double, lon: Double): Double =
            150.0 + 18.0 * sin(lat * 520.0) * cos(lon * 330.0) + 6.0 * sin((lat + lon) * 1500.0)
    }
}

/** ImageIO-Decoder der Tests als [WorldBuildDeps]. */
internal fun fakeDeps(geo: FakeGeo) = WorldBuildDeps(geo, imageIoDecoder)

/**
 * Kurze Strecke (gut 1 km) bei Altenberg, ohne Belagsdaten — der Weltbau
 * schaetzt die Belaege aus den Vektorkacheln.
 */
internal fun shortRoute(lengthM: Double = 1_100.0): IndoorRoute {
    val origin = GeoOrigin(51.0525, 7.1280, 0.0)
    val pts = ArrayList<IndoorRoutePoint>()
    var e = -lengthM / 2
    var n = -150.0
    var d = 0.0
    val step = 10.0
    while (d <= lengthM + 1e-9) {
        val lat = origin.toLat(n)
        val lon = origin.toLon(e)
        pts += IndoorRoutePoint(d, lat, lon, FakeGeo.syntheticHeight(lat, lon))
        val heading = 1.2 + 0.5 * sin(d / 160.0)
        val de = sin(heading) * step
        val dn = cos(heading) * step
        e += de
        n += dn
        d += hypot(de, dn)
    }
    val len = pts.last().distM
    return IndoorRoute(
        id = "kurz-1",
        name = "Kurze Teststrecke",
        points = pts,
        surfaces = listOf(SurfaceSection(0.0, len, SurfaceKind.UNKNOWN)),
        isLoop = false,
        source = IndoorRouteSource.NONE,
    )
}

/** Fortschritt mitschreiben, wahlweise abbrechen. */
internal class RecordingListener(private val cancelWhen: (WorldBuildPhase, Int, Int) -> Boolean = { _, _, _ -> false }) : WorldBuildListener {
    val events: MutableList<Triple<WorldBuildPhase, Int, Int>> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var cancelled = false

    override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
        events += Triple(phase, done, total)
        if (cancelWhen(phase, done, total)) cancelled = true
    }

    override fun isCancelled(): Boolean = cancelled
}

internal fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

/** Kopiert ein Weltverzeichnis. */
internal fun copyWorld(from: File, to: File): File {
    from.copyRecursively(to, overwrite = true)
    return to
}
