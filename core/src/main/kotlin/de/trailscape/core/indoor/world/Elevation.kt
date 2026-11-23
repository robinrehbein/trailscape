package de.trailscape.core.indoor.world

import java.io.IOException
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor

/**
 * # Hoehen aus Terrarium-Kacheln
 *
 * Terrarium-PNG (256², ohne Ueberlappung): `Hoehe = R·256 + G + B/256 − 32768`
 * Meter. Ein Pixel steht fuer die Flaeche des Pixels, sein Wert gilt fuer die
 * **Pixelmitte** — deshalb wird bei `(x − 0,5, y − 0,5)` in Pixeln
 * interpoliert, und benachbarte Kacheln werden ueber eine globale
 * Pixelzaehlung angesprochen: Die Interpolation ist ueber Kachelgrenzen
 * hinweg nahtlos.
 *
 * ## Was die Daten im Raum Altenberg/Odenthal hergeben (geprueft 23.11.2026)
 *  * Die Werte sind **nicht** auf ganze Meter gerundet (B-Kanal voll
 *    genutzt, kaum gleiche Nachbarn) — Terrassen wie bei rohen SRTM-Daten
 *    gibt es dort nicht. Die Glaettung ([ElevationField.smoothingSigmaPx])
 *    ist trotzdem einstellbar, fuer Gegenden mit grober Quelle.
 *  * z13 (≈ 12 m je Pixel bei 51° N) traegt echte Information gegenueber z12
 *    (Abweichung von hochgerechnetem z12: 1,0 m RMS); z14 weicht vom
 *    hochgerechneten z13 nur 0,13 m RMS ab, z15 vom z14 0,07 m — z14/z15 sind
 *    hochgerechnet und lohnen nicht.
 */

/** Terrarium-Pixel `0xAARRGGBB` → Meter. */
internal fun terrariumMeters(argb: Int): Float {
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 256 + g + b / 256.0 - 32768.0).toFloat()
}

/** Dekodiert ein Terrarium-Bild zeilenweise (von Norden) in Meter. */
internal fun decodeTerrarium(img: RasterImage): FloatArray {
    require(img.width == img.height && img.width > 0) { "Hoehenkachel ist nicht quadratisch (${img.width}×${img.height})" }
    require(img.argb.size == img.width * img.height) { "Hoehenkachel hat ${img.argb.size} statt ${img.width * img.height} Pixel" }
    return FloatArray(img.argb.size) { terrariumMeters(img.argb[it]) }
}

/**
 * Eine Hoehenkachel. [heights] zeilenweise von Norden, `size²` Werte;
 * `null` = die Quelle hat keine Kachel (404, Meer) → Hoehe 0.
 */
internal class ElevationTile(val id: TileId, val size: Int, val heights: FloatArray?) {
    init {
        require(heights == null || heights.size == size * size) { "Hoehenkachel $id: ${heights?.size} statt ${size * size} Werte" }
    }
}

/**
 * Fuer eine Stelle fehlt die Hoehenkachel (nicht geladen, Quelle mit Fehler
 * oder unlesbar). Der Weltbau scheitert daran ([WorldBuildFailed]).
 *
 * @property status HTTP-Status der letzten Antwort, falls es eine gab.
 */
internal class ElevationMissingException(val tile: TileId, val status: Int?, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Eine Hoehenquelle in ENU — der Weltbau fragt nur hierueber, damit Tests eine
 * erfundene Landschaft einsetzen koennen.
 */
internal interface ElevationSource {
    /** Hoehe ueber dem Meer in Metern an `(e, n)`. */
    fun heightAt(e: Double, n: Double): Float

    /**
     * Ein ganzes Gitter: Wert `[j·cols + i]` = Hoehe bei
     * `(e0 + i·stepE, n0 + j·stepN)`.
     */
    fun heightGrid(e0: Double, n0: Double, stepE: Double, stepN: Double, cols: Int, rows: Int): FloatArray {
        val out = FloatArray(cols * rows)
        for (j in 0 until rows) {
            val n = n0 + j * stepN
            for (i in 0 until cols) out[j * cols + i] = heightAt(e0 + i * stepE, n)
        }
        return out
    }
}

/**
 * Hoehenfeld ueber ein (luecken­haftes) Mosaik von Terrarium-Kacheln einer
 * Zoomstufe [zoom].
 *
 * Interpolation bikubisch (Catmull-Rom) bzw. bilinear ueber Pixelmitten,
 * nahtlos ueber Kachelgrenzen. Liegt ein Nachbarpixel in einer nicht
 * geladenen Kachel, wird am Rand der Kachel des Abfragepunkts geklemmt; fehlt
 * die Kachel des Abfragepunkts selbst, gibt es [ElevationMissingException].
 *
 * @param smoothingSigmaPx Gauss-Glaettung (Standardabweichung in Pixeln der
 *   Zoomstufe) ueber das Mosaik **vor** der Interpolation, gegen
 *   Treppenstufen ganzzahliger Quelldaten; 0 = aus. Richtwert bei z13:
 *   0,7–1,0 (≈ 8–12 m), bei sichtbaren Terrassen bis 1,5.
 */
internal class ElevationField(
    private val origin: GeoOrigin,
    val zoom: Int,
    tiles: Collection<ElevationTile>,
    val smoothingSigmaPx: Double = 0.0,
    private val bicubic: Boolean = true,
) : ElevationSource {
    private val size: Int = tiles.firstOrNull()?.size ?: TERRARIUM_TILE_PX
    private val raw: Map<Long, ElevationTile> = tiles.associateBy { key(it.id.x, it.id.y) }
    private val data: Map<Long, FloatArray?>

    init {
        require(tiles.all { it.id.z == zoom && it.size == size }) { "Hoehenkacheln mit gemischter Zoomstufe oder Groesse" }
        require(smoothingSigmaPx >= 0.0) { "Glaettung darf nicht negativ sein" }
        data = if (smoothingSigmaPx > 0.0) {
            raw.mapValues { (_, t) -> t.heights?.let { smooth(t) } }
        } else {
            raw.mapValues { it.value.heights }
        }
    }

    /** Die geladenen Kacheln. */
    val tileIds: Set<TileId> get() = raw.values.mapTo(LinkedHashSet()) { it.id }

    override fun heightAt(e: Double, n: Double): Float = heightAtLatLon(origin.toLat(n), origin.toLon(e))

    fun heightAtLatLon(lat: Double, lon: Double): Float =
        sampleAt(TileMath.lonToTileX(lon, zoom) * size - 0.5, TileMath.latToTileY(lat, zoom) * size - 0.5)

    override fun heightGrid(e0: Double, n0: Double, stepE: Double, stepN: Double, cols: Int, rows: Int): FloatArray {
        // e haengt nur von der Laenge, n nur von der Breite ab: je Spalte und Zeile einmal rechnen.
        val gx = DoubleArray(cols) { TileMath.lonToTileX(origin.toLon(e0 + it * stepE), zoom) * size - 0.5 }
        val gy = DoubleArray(rows) { TileMath.latToTileY(origin.toLat(n0 + it * stepN), zoom) * size - 0.5 }
        val out = FloatArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) out[j * cols + i] = sampleAt(gx[i], gy[j])
        return out
    }

    /** Wert an einer globalen Pixelposition (Pixelmitte = ganze Zahl). */
    private fun sampleAt(px: Double, py: Double): Float {
        // Kachel des Abfragepunkts: die, in der die Stelle liegt (Pixelmitte + 0,5 zurueck).
        val homeX = Math.floorDiv(floor(px + 0.5).toInt(), size)
        val homeY = Math.floorDiv(floor(py + 0.5).toInt(), size)
        val homeKey = key(homeX, homeY)
        if (!data.containsKey(homeKey)) {
            val id = TileId(zoom, homeX, homeY)
            throw ElevationMissingException(id, null, "Hoehenkachel $id fehlt")
        }
        val home = data[homeKey] ?: return 0f
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val tx = px - x0
        val ty = py - y0
        if (!bicubic) {
            val a = pixel(x0, y0, homeX, homeY, home)
            val b = pixel(x0 + 1, y0, homeX, homeY, home)
            val c = pixel(x0, y0 + 1, homeX, homeY, home)
            val d = pixel(x0 + 1, y0 + 1, homeX, homeY, home)
            return ((a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty).toFloat()
        }
        val col = DoubleArray(4)
        for (j in 0 until 4) {
            val y = y0 - 1 + j
            col[j] = catmullRom(
                pixel(x0 - 1, y, homeX, homeY, home),
                pixel(x0, y, homeX, homeY, home),
                pixel(x0 + 1, y, homeX, homeY, home),
                pixel(x0 + 2, y, homeX, homeY, home),
                tx,
            )
        }
        return catmullRom(col[0], col[1], col[2], col[3], ty).toFloat()
    }

    /** Pixel an globaler Position; fehlt die Kachel dort, wird in die Heimatkachel geklemmt. */
    private fun pixel(gx: Int, gy: Int, homeX: Int, homeY: Int, home: FloatArray): Double {
        val tx = Math.floorDiv(gx, size)
        val ty = Math.floorDiv(gy, size)
        val k = key(tx, ty)
        if (data.containsKey(k)) {
            val arr = data[k] ?: return 0.0
            return arr[(gy - ty * size) * size + (gx - tx * size)].toDouble()
        }
        val cx = (gx - homeX * size).coerceIn(0, size - 1)
        val cy = (gy - homeY * size).coerceIn(0, size - 1)
        return home[cy * size + cx].toDouble()
    }

    /** Rohwert fuer die Glaettung, mit derselben Klemmregel. */
    private fun rawPixel(gx: Int, gy: Int, home: ElevationTile): Float {
        val tx = Math.floorDiv(gx, size)
        val ty = Math.floorDiv(gy, size)
        val t = raw[key(tx, ty)]
        if (t != null) {
            val arr = t.heights ?: return 0f
            return arr[(gy - ty * size) * size + (gx - tx * size)]
        }
        val cx = (gx - home.id.x * size).coerceIn(0, size - 1)
        val cy = (gy - home.id.y * size).coerceIn(0, size - 1)
        return home.heights!![cy * size + cx]
    }

    /** Separierbarer Gauss ueber die Kachel samt Rand aus den Nachbarkacheln. */
    private fun smooth(t: ElevationTile): FloatArray {
        val r = ceil(3.0 * smoothingSigmaPx).toInt().coerceAtLeast(1)
        val kernel = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2.0 * smoothingSigmaPx * smoothingSigmaPx)) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        val w = size + 2 * r
        val bx = t.id.x * size - r
        val by = t.id.y * size - r
        val padded = FloatArray(w * w)
        for (j in 0 until w) for (i in 0 until w) padded[j * w + i] = rawPixel(bx + i, by + j, t)
        // waagrecht: nur die inneren Spalten, alle Zeilen
        val horiz = FloatArray(w * size)
        for (j in 0 until w) for (i in 0 until size) {
            var s = 0.0
            for (k in kernel.indices) s += kernel[k] * padded[j * w + i + k]
            horiz[j * size + i] = s.toFloat()
        }
        val out = FloatArray(size * size)
        for (j in 0 until size) for (i in 0 until size) {
            var s = 0.0
            for (k in kernel.indices) s += kernel[k] * horiz[(j + k) * size + i]
            out[j * size + i] = s.toFloat()
        }
        return out
    }

    private companion object {
        fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        fun catmullRom(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double =
            p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))
    }
}

/** Kantenlaenge der Terrarium-Kacheln in Pixeln. */
internal const val TERRARIUM_TILE_PX: Int = 256

/**
 * Holt alle Terrarium-Kacheln der Stufe [zoom], die [box] beruehren, ueber
 * die Huelle [fetcher] und baut daraus ein [ElevationField].
 *
 * 404 gilt als Meer (Hoehe 0). Jeder andere Fehlerstatus und unlesbare Bilder
 * ergeben [ElevationMissingException]; Netzfehler ([IOException]) gehen
 * unveraendert durch — der Weltbau macht daraus `OFFLINE`.
 *
 * @param onTile Fortschritt `(erledigt, gesamt)` nach jeder Kachel.
 */
internal fun loadElevationField(
    fetcher: GeoFetcher,
    decoder: RasterDecoder,
    origin: GeoOrigin,
    box: LatLonBox,
    zoom: Int,
    smoothingSigmaPx: Double = 0.0,
    onTile: (done: Int, total: Int) -> Unit = { _, _ -> },
): ElevationField {
    val ids = TileMath.tilesCovering(box, zoom)
    val tiles = ArrayList<ElevationTile>(ids.size)
    for ((i, id) in ids.withIndex()) {
        tiles += fetchElevationTile(fetcher, decoder, id)
        onTile(i + 1, ids.size)
    }
    return ElevationField(origin, zoom, tiles, smoothingSigmaPx)
}

/** Eine Hoehenkachel holen und dekodieren (Regeln wie [loadElevationField]). */
@Throws(IOException::class)
internal fun fetchElevationTile(fetcher: GeoFetcher, decoder: RasterDecoder, id: TileId): ElevationTile {
    val resp = fetcher.fetch(GeoRequest(GeoSourceKind.ELEVATION, TileMath.elevationUrl(id)))
    return when (resp.status) {
        200 -> {
            val img = decoder.decode(resp.bytes)
                ?: throw ElevationMissingException(id, 200, "Hoehenkachel $id ist kein lesbares Bild")
            if (img.width != img.height) throw ElevationMissingException(id, 200, "Hoehenkachel $id ist nicht quadratisch")
            ElevationTile(id, img.width, decodeTerrarium(img))
        }
        404 -> ElevationTile(id, TERRARIUM_TILE_PX, null)
        else -> throw ElevationMissingException(id, resp.status, "Hoehenkachel $id: HTTP ${resp.status}")
    }
}
