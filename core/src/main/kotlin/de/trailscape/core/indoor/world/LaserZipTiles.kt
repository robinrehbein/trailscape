package de.trailscape.core.indoor.world

import java.io.ByteArrayInputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # Laser-Raster aus Kachel-Downloads (ZIP)
 *
 * Fuer Laender ohne WCS (Baden-Wuerttemberg): DGM und DOM kommen als
 * ZIP-Archive fester Kacheln ([LaserTiles], 2 km × 2 km) mit je einer Datei
 * pro Kilometer. Je Archiv werden beide Downloads einmal geholt, die
 * benoetigten Kilometer nacheinander entpackt und dekodiert und daraus alle
 * Bloecke gefuellt, die den Kilometer beruehren; danach ist der Kilometer
 * wieder frei (je Faden hoechstens zwei Archive und zwei 1-km-Raster im
 * Speicher, rund 35 MB).
 *
 * Jede Probe eines Blocks nimmt den Kilometer, in dem ihr UTM-Punkt liegt; am
 * Kilometerrand (halber Meter) gilt dort der naechste Pixel statt der
 * bilinearen Mitte mit dem Nachbarkilometer — Fehler hoechstens die
 * Hoehendifferenz zweier Nachbarpixel, gewichtet mit ≤ 0,5.
 */
internal fun fillBlocksFromTiles(
    blocks: Collection<LaserBlock>,
    source: LaserSource,
    origin: GeoOrigin,
    fetcher: LaserFetcher,
    threads: Int,
    requests: AtomicInteger,
    cached: AtomicInteger,
    bytes: AtomicLong,
    isCancelled: () -> Boolean,
) {
    val tiles = checkNotNull(source.tiles) { "Laser-Quelle ${source.id} hat kein Kachelschema" }
    // Je Block sein Rahmen und die Kilometer, die sein UTM-Rechteck beruehrt.
    val frames = HashMap<LaserBlock, EnuUtmFrame>()
    val byKm = LinkedHashMap<Long, MutableList<LaserBlock>>()
    for (b in blocks) {
        val e0 = b.bx * LaserData.LASER_BLOCK_M
        val n0 = b.by * LaserData.LASER_BLOCK_M
        val frame = EnuUtmFrame(origin, source.utmZone, e0 + 128, n0 + 128)
        frames[b] = frame
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for ((ce, cn) in listOf(e0 to n0, e0 + 256 to n0, e0 to n0 + 256, e0 + 256 to n0 + 256)) {
            val x = frame.utmX(ce, cn)
            val y = frame.utmY(ce, cn)
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        for (ky in floor((y0 - 1) / 1000).toInt()..floor((y1 + 1) / 1000).toInt()) {
            for (kx in floor((x0 - 1) / 1000).toInt()..floor((x1 + 1) / 1000).toInt()) {
                byKm.getOrPut(LaserData.key(kx, ky)) { ArrayList() } += b
            }
        }
    }
    val byArchive = byKm.keys.groupBy { k -> LaserData.key(tiles.tileE((k shr 32).toInt()), tiles.tileN(k.toInt())) }
    val pool = Executors.newFixedThreadPool(max(1, min(threads, byArchive.size))) { t -> Thread(t, "laser-zip").apply { isDaemon = true } }
    try {
        val futures = byArchive.map { (archive, kms) ->
            pool.submit(Callable {
                if (isCancelled()) throw WorldBuildCancelled()
                val ae = (archive shr 32).toInt()
                val an = archive.toInt()
                fun get(dom: Boolean): ByteArray? {
                    val resp = fetcher.fetch(tiles.url(dom, ae, an))
                    requests.incrementAndGet()
                    if (resp.fromCache) cached.incrementAndGet()
                    bytes.addAndGet(resp.bytes.size.toLong())
                    return if (resp.status == 200) resp.bytes else null
                }
                val dgmZip = get(false) ?: return@Callable Unit
                val domZip = get(true)
                for (k in kms) {
                    if (isCancelled()) throw WorldBuildCancelled()
                    val kx = (k shr 32).toInt()
                    val ky = k.toInt()
                    val dgm = readTileRaster(dgmZip, tiles, false, kx, ky) ?: continue
                    val dom = domZip?.let { readTileRaster(it, tiles, true, kx, ky) }
                    for (b in byKm.getValue(k)) fillFromKm(b, frames.getValue(b), kx, ky, dgm, dom)
                }
            })
        }
        for (f in futures) {
            try {
                f.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    } finally {
        pool.shutdownNow()
    }
}

/** Setzt die Proben von [b], deren UTM-Punkt im Kilometer ([kx], [ky]) liegt (wie `fillBlock` beim WCS). */
private fun fillFromKm(b: LaserBlock, frame: EnuUtmFrame, kx: Int, ky: Int, dgm: FloatRaster, dom: FloatRaster?) {
    val e0 = b.bx * LaserData.LASER_BLOCK_M
    val n0 = b.by * LaserData.LASER_BLOCK_M
    val n = LaserBlock.N
    val xMin = kx * 1000.0
    val yMin = ky * 1000.0
    for (j in 0 until n) for (i in 0 until n) {
        val e = e0 + i
        val nn = n0 + j
        val x = frame.utmX(e, nn)
        val y = frame.utmY(e, nn)
        if (x < xMin || x >= xMin + 1000 || y < yMin || y >= yMin + 1000) continue
        val g = dgm.sample(x, y)
        val k = j * n + i
        if (g.isNaN() || g < -500f) continue
        b.dgm[k] = g
        if (dom != null) {
            val s = dom.sample(x, y)
            if (!s.isNaN() && s > -500f) {
                val h = ((s - g) / 0.2f).toInt().coerceIn(0, 255)
                b.ndomQ[k] = h.toByte()
            }
        }
    }
}

/** Das 1-km-Raster ([kx], [ky]) aus dem Archiv [zip]; `null`, wenn die Datei fehlt oder nicht lesbar ist. */
internal fun readTileRaster(zip: ByteArray, tiles: LaserTiles, dom: Boolean, kx: Int, ky: Int): FloatRaster? {
    val prefix = tiles.entryPrefix(dom, kx, ky)
    val format = tiles.format(dom)
    val ext = if (format == LaserRasterFormat.XYZ) ".xyz" else ".tif"
    val data = zipEntry(zip) { name -> name.startsWith(prefix) && name.endsWith(ext, ignoreCase = true) } ?: return null
    return runCatching {
        when (format) {
            LaserRasterFormat.XYZ -> parseXyzKm(data, kx, ky)
            LaserRasterFormat.TIFF -> decodeFloatTiff(data)
        }
    }.getOrNull()
}

/**
 * Die erste Datei im ZIP [zip], deren Name **ohne Verzeichnis** [match]
 * erfuellt; `null`, wenn keine passt. Groesse je Datei hoechstens 256 MB.
 */
internal fun zipEntry(zip: ByteArray, match: (String) -> Boolean): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zip)).use { z ->
        while (true) {
            val e = z.nextEntry ?: return null
            if (e.isDirectory) continue
            val name = e.name.substringAfterLast('/')
            if (!match(name)) continue
            val out = java.io.ByteArrayOutputStream(if (e.size in 1..MAX_ENTRY) e.size.toInt() else 1 shl 20)
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val r = z.read(buf)
                if (r < 0) break
                total += r
                require(total <= MAX_ENTRY) { "ZIP-Eintrag $name zu gross" }
                out.write(buf, 0, r)
            }
            return out.toByteArray()
        }
    }
}

private const val MAX_ENTRY = 256L shl 20

/**
 * XYZ-Text eines 1-m-Rasters (je Zeile `Rechtswert Hochwert Hoehe`, Werte der
 * Pixelmitten auf ,5 m) fuer den Kilometer ([kx], [ky]) als [FloatRaster]
 * 1000 × 1000; fehlende Punkte bleiben `NaN`, Punkte ausserhalb des
 * Kilometers werden uebergangen.
 */
internal fun parseXyzKm(data: ByteArray, kx: Int, ky: Int): FloatRaster {
    val w = 1000
    val out = FloatArray(w * w) { Float.NaN }
    val x0 = kx * 1000.0
    val yTop = ky * 1000.0 + 1000.0
    var p = 0
    val vals = DoubleArray(3)
    val end = data.size
    while (p < end) {
        var f = 0
        // Eine Zeile: bis zu drei Zahlen, getrennt durch Leerzeichen, Tabulator, Komma oder Semikolon.
        while (p < end && data[p] != '\n'.code.toByte()) {
            val c = data[p].toInt()
            if (c == ' '.code || c == '\t'.code || c == '\r'.code || c == ','.code || c == ';'.code) {
                p++
                continue
            }
            val s = p
            while (p < end) {
                val d = data[p].toInt()
                if (d == ' '.code || d == '\t'.code || d == '\r'.code || d == '\n'.code || d == ','.code || d == ';'.code) break
                p++
            }
            if (f < 3) vals[f] = parseNumber(data, s, p)
            f++
        }
        p++
        if (f < 3 || vals[0].isNaN() || vals[1].isNaN() || vals[2].isNaN()) continue
        val col = floor(vals[0] - x0).toInt()
        val row = floor(yTop - vals[1]).toInt()
        if (col !in 0 until w || row !in 0 until w) continue
        out[row * w + col] = vals[2].toFloat()
    }
    return FloatRaster(w, w, out, x0, yTop, 1.0, 1.0)
}

/** Dezimalzahl aus [b] zwischen [s] und [e] (Vorzeichen, Ziffern, Punkt; sonst ueber `toDouble`). `NaN`, wenn keine Zahl. */
private fun parseNumber(b: ByteArray, s: Int, e: Int): Double {
    var i = s
    var neg = false
    if (i < e && (b[i] == '-'.code.toByte() || b[i] == '+'.code.toByte())) {
        neg = b[i] == '-'.code.toByte()
        i++
    }
    var v = 0L
    var scale = 1.0
    var frac = false
    var digits = 0
    while (i < e) {
        val c = b[i].toInt()
        when {
            c in '0'.code..'9'.code -> {
                if (digits < 17) {
                    v = v * 10 + (c - '0'.code)
                    if (frac) scale *= 10.0
                } else if (!frac) {
                    return String(b, s, e - s, Charsets.US_ASCII).toDoubleOrNull() ?: Double.NaN
                }
                digits++
            }
            c == '.'.code && !frac -> frac = true
            else -> return String(b, s, e - s, Charsets.US_ASCII).toDoubleOrNull() ?: Double.NaN
        }
        i++
    }
    if (digits == 0) return Double.NaN
    val r = v / scale
    return if (neg) -r else r
}

/** Voranschlag: die Archive (Ost-, Nord-Kilometer der Suedwestecke), die die Bloecke [wanted] beruehren. */
internal fun tileArchivesFor(source: LaserSource, origin: GeoOrigin, wanted: List<IntArray>): Set<Long> {
    val tiles = source.tiles ?: return emptySet()
    val out = HashSet<Long>()
    for (w in wanted) {
        val e0 = w[0] * LaserData.LASER_BLOCK_M
        val n0 = w[1] * LaserData.LASER_BLOCK_M
        val frame = EnuUtmFrame(origin, source.utmZone, e0 + 128, n0 + 128)
        for ((ce, cn) in listOf(e0 to n0, e0 + 256 to n0, e0 to n0 + 256, e0 + 256 to n0 + 256)) {
            val kx = floor(frame.utmX(ce, cn) / 1000).toInt()
            val ky = floor(frame.utmY(ce, cn) / 1000).toInt()
            out += LaserData.key(tiles.tileE(kx), tiles.tileN(ky))
        }
    }
    return out
}

