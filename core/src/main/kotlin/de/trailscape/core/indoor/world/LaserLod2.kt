package de.trailscape.core.indoor.world

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # 3D-Gebaeudemodell LoD2 (CityGML) lesen
 *
 * Die Laender geben LoD2 als CityGML 1.0 in 1-km-Kacheln heraus (NRW:
 * `LoD2_32_<km Ost>_<km Nord>_1_NW.gml`, ETRS89/UTM32, Hoehen in DHHN2016
 * wie das DGM1). Gelesen werden je `bldg:Building` (samt `BuildingPart`)
 * die Begrenzungsflaechen unter `bldg:boundedBy` — Dach, Wand, Boden,
 * Abschluss — als ebene Polygone mit Loechern, dazu `measuredHeight` und
 * `roofType`. Alles andere (Adressen, Texturen, Funktion) bleibt liegen.
 *
 * Ein kleiner Text-Scanner statt eines XML-Parsers: Die Dateien sind
 * maschinell und regelmaessig erzeugt; ein DOM fuer 20 MB Stadtkachel waere
 * auf dem Handy zu teuer, und `javax.xml.stream` gibt es dort nicht.
 */

internal enum class Lod2SurfaceKind { ROOF, WALL, GROUND, CLOSURE }

/** Ein ebenes Polygon: Ringe als `e, n, u` (u = Meter ueber dem Meer), erster Ring aussen. */
internal class Lod2Surface(val kind: Lod2SurfaceKind, val rings: List<DoubleArray>)

/**
 * Ein Gebaeude aus LoD2 in ENU.
 *
 * @property footprint Grundriss als 2D-Ringe (`e, n, …`), aus den Bodenflaechen (oder, wenn
 *   es keine gibt, der Projektion der Dachflaechen).
 * @property groundU niedrigste Hoehe der Bodenflaechen (Meter ueber dem Meer).
 * @property roofType ALKIS-Schluessel der Dachform (1000 Flach, 2100 Pult, 3100 Sattel, 3200 Walm, …), 0 unbekannt.
 */
internal class LaserBuilding(
    val id: String,
    val surfaces: List<Lod2Surface>,
    val footprint: List<DoubleArray>,
    val groundU: Double,
    val topU: Double,
    val measuredHeightM: Double,
    val roofType: Int,
) {
    val ce: Double
    val cn: Double

    init {
        val r = footprint.firstOrNull() ?: surfaces.first().rings.first().let { ring -> DoubleArray(ring.size / 3 * 2) { ring[it / 2 * 3 + it % 2] } }
        var se = 0.0
        var sn = 0.0
        val k = r.size / 2
        for (i in 0 until k) {
            se += r[2 * i]
            sn += r[2 * i + 1]
        }
        ce = se / k
        cn = sn / k
    }
}

internal class Lod2Result(val buildings: List<LaserBuilding>, val tiles: Int, val bytes: Long)

/** Laedt die LoD2-Kacheln, die die Bloecke beruehren; Gebaeude ausserhalb der Bloecke fallen weg. */
internal fun loadLod2(
    source: LaserSource,
    origin: GeoOrigin,
    fetcher: LaserFetcher,
    blocks: Collection<LaserBlock>,
    threads: Int,
    isCancelled: () -> Boolean,
): Lod2Result {
    val kms = LinkedHashSet<Long>()
    for (b in blocks) {
        val e0 = b.bx * LaserData.LASER_BLOCK_M
        val n0 = b.by * LaserData.LASER_BLOCK_M
        val frame = EnuUtmFrame(origin, source.utmZone, e0 + 128, n0 + 128)
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for ((ce, cn) in listOf(e0 to n0, e0 + 256 to n0, e0 to n0 + 256, e0 + 256 to n0 + 256)) {
            val x = frame.utmX(ce, cn); val y = frame.utmY(ce, cn)
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        for (kx in floor(x0 / 1000).toInt()..floor(x1 / 1000).toInt()) {
            for (ky in floor(y0 / 1000).toInt()..floor(y1 / 1000).toInt()) kms += LaserData.key(kx, ky)
        }
    }
    val blockKeys = blocks.map { LaserData.key(it.bx, it.by) }.toHashSet()
    val pool = Executors.newFixedThreadPool(max(1, threads)) { t -> Thread(t, "lod2").apply { isDaemon = true } }
    val out = ArrayList<LaserBuilding>()
    var bytes = 0L
    var tiles = 0
    try {
        val futures = kms.map { k ->
            val kx = (k shr 32).toInt()
            val ky = k.toInt()
            pool.submit(Callable {
                if (isCancelled()) throw WorldBuildCancelled()
                val url = source.lod2Url(kx, ky) ?: return@Callable null
                val resp = fetcher.fetch(url)
                if (resp.status != 200) return@Callable null
                resp.bytes.size.toLong() to parseLod2(resp.bytes, origin, source.utmZone)
            })
        }
        for (f in futures) {
            val r = try {
                f.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            } ?: continue
            tiles++
            bytes += r.first
            for (b in r.second) {
                val key = LaserData.key(floor(b.ce / LaserData.LASER_BLOCK_M).toInt(), floor(b.cn / LaserData.LASER_BLOCK_M).toInt())
                if (key in blockKeys) out += b
            }
        }
    } finally {
        pool.shutdownNow()
    }
    // Gleiche ID aus zwei Kacheln (Gebaeude auf der Kachelgrenze): einmal.
    val seen = HashSet<String>()
    return Lod2Result(out.filter { seen.add(it.id) }.sortedWith(compareBy({ it.ce }, { it.cn }, { it.id })), tiles, bytes)
}

/** Liest eine CityGML-Kachel; Koordinaten werden von UTM [zone] nach ENU um [origin] umgerechnet. */
internal fun parseLod2(bytes: ByteArray, origin: GeoOrigin, zone: Int): List<LaserBuilding> {
    val text = String(bytes, Charsets.UTF_8)
    val out = ArrayList<LaserBuilding>()
    var p = 0
    while (true) {
        val s = text.indexOf("<bldg:Building ", p).let { if (it < 0) text.indexOf("<bldg:Building>", p) else it }
        if (s < 0) break
        val e = text.indexOf("</bldg:Building>", s)
        if (e < 0) break
        parseBuilding(text, s, e, origin, zone)?.let { out += it }
        p = e + 16
    }
    return out
}

private val SURFACE = Regex("<bldg:(RoofSurface|WallSurface|GroundSurface|ClosureSurface)[\\s>]")

private fun parseBuilding(t: String, s: Int, e: Int, origin: GeoOrigin, zone: Int): LaserBuilding? {
    val head = t.substring(s, min(e, s + 400))
    val id = Regex("gml:id=\"([^\"]+)\"").find(head)?.groupValues?.get(1) ?: "b$s"
    val body = t.substring(s, e)
    val measured = Regex("<bldg:measuredHeight[^>]*>([-0-9.eE]+)<").find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val roofType = Regex("<bldg:roofType[^>]*>([0-9]+)<").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    // Ein linearer Rahmen je Gebaeude: UTM → ENU auf Millimeter genau.
    var frame: EnuUtmFrame? = null
    val surfaces = ArrayList<Lod2Surface>()
    for (m in SURFACE.findAll(body)) {
        val kind = when (m.groupValues[1]) {
            "RoofSurface" -> Lod2SurfaceKind.ROOF
            "WallSurface" -> Lod2SurfaceKind.WALL
            "GroundSurface" -> Lod2SurfaceKind.GROUND
            else -> Lod2SurfaceKind.CLOSURE
        }
        val end = body.indexOf("</bldg:${m.groupValues[1]}>", m.range.first)
        if (end < 0) continue
        var q = m.range.first
        while (true) {
            val ps = body.indexOf("<gml:Polygon", q)
            if (ps < 0 || ps > end) break
            val pe = body.indexOf("</gml:Polygon>", ps)
            if (pe < 0 || pe > end) break
            val rings = ArrayList<DoubleArray>()
            var r = ps
            while (true) {
                val ext = body.indexOf("<gml:exterior", r)
                val int = body.indexOf("<gml:interior", r)
                val next = listOf(ext, int).filter { it in 0 until pe }.minOrNull() ?: break
                val closeTag = if (next == ext) "</gml:exterior>" else "</gml:interior>"
                val ce = body.indexOf(closeTag, next)
                if (ce < 0 || ce > pe) break
                val utm = coords(body, next, ce)
                if (utm != null && utm.size >= 9) {
                    if (frame == null) {
                        val ll = Utm.inverse(utm[0], utm[1], zone)
                        frame = EnuUtmFrame(origin, zone, origin.toEast(ll[1]), origin.toNorth(ll[0]))
                    }
                    val f = frame!!
                    val ring = DoubleArray(utm.size)
                    for (i in 0 until utm.size / 3) {
                        ring[3 * i] = f.east(utm[3 * i], utm[3 * i + 1])
                        ring[3 * i + 1] = f.north(utm[3 * i], utm[3 * i + 1])
                        ring[3 * i + 2] = utm[3 * i + 2]
                    }
                    rings += dropClosing(ring)
                }
                r = ce
            }
            if (rings.isNotEmpty() && rings[0].size >= 9) surfaces += Lod2Surface(kind, rings)
            q = pe
        }
    }
    if (surfaces.isEmpty()) return null
    val grounds = surfaces.filter { it.kind == Lod2SurfaceKind.GROUND }
    val footprint = (if (grounds.isNotEmpty()) grounds else surfaces.filter { it.kind == Lod2SurfaceKind.ROOF })
        .map { sf -> sf.rings[0].let { r -> DoubleArray(r.size / 3 * 2) { r[it / 2 * 3 + it % 2] } } }
    var groundU = Double.MAX_VALUE
    var topU = -Double.MAX_VALUE
    for (sf in surfaces) for (r in sf.rings) for (i in 2 until r.size step 3) {
        topU = max(topU, r[i])
        if (sf.kind == Lod2SurfaceKind.GROUND || grounds.isEmpty()) groundU = min(groundU, r[i])
    }
    if (grounds.isEmpty()) {
        groundU = surfaces.filter { it.kind == Lod2SurfaceKind.WALL }.flatMap { s2 -> s2.rings[0].filterIndexed { i, _ -> i % 3 == 2 } }.minOrNull() ?: groundU
    }
    return LaserBuilding(id, surfaces, footprint, groundU, topU, measured, roofType)
}

/** `posList` oder eine Folge von `pos` zwischen [from] und [to]; Werte als x, y, z. */
private fun coords(t: String, from: Int, to: Int): DoubleArray? {
    val pl = t.indexOf("<gml:posList", from)
    val vals = ArrayList<Double>()
    if (pl in from until to) {
        val a = t.indexOf('>', pl) + 1
        val b = t.indexOf("</gml:posList>", a)
        if (a <= 0 || b < 0 || b > to) return null
        splitNumbers(t, a, b, vals)
    } else {
        var q = from
        while (true) {
            val ps = t.indexOf("<gml:pos", q)
            if (ps < 0 || ps >= to) break
            val a = t.indexOf('>', ps) + 1
            val b = t.indexOf("</gml:pos>", a)
            if (b < 0 || b > to) break
            splitNumbers(t, a, b, vals)
            q = b
        }
    }
    if (vals.size % 3 != 0) return null
    return vals.toDoubleArray()
}

private fun splitNumbers(t: String, a: Int, b: Int, out: MutableList<Double>) {
    var i = a
    while (i < b) {
        while (i < b && t[i].isWhitespace()) i++
        val s = i
        while (i < b && !t[i].isWhitespace()) i++
        if (i > s) t.substring(s, i).toDoubleOrNull()?.let { out += it }
    }
}

/** Ring ohne den doppelten Schlusspunkt (GML wiederholt den ersten Punkt). */
private fun dropClosing(r: DoubleArray): DoubleArray {
    val k = r.size / 3
    if (k >= 2 && r[0] == r[3 * (k - 1)] && r[1] == r[3 * (k - 1) + 1] && r[2] == r[3 * (k - 1) + 2]) return r.copyOf(3 * (k - 1))
    return r
}
