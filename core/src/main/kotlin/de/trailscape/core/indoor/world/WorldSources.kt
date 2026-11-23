package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.sampleAt
import de.trailscape.core.indoor.surfaceKindFromVectorTile
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * # Geodaten → Eingaben der Erzeuger
 *
 * Adapter von den geladenen Geodaten (Hoehenkacheln, Vektorkacheln,
 * Luftbilder) zu den Typen, mit denen [WorldParts] arbeiten: [MapData],
 * eine Hoehenfunktion, Luftbilder je Kachel, Bruecken und — als Rueckfall —
 * Belaege der Strecke aus den Vektorkacheln.
 *
 * ## Zoomstufen (geprueft am 23.11.2026 an OpenFreeMap, Raum Altenberg)
 *  * Vektor **z14** fuer Stufe 0 **und** Stufe 1: Gebaeude stehen erst ab z13
 *    in den Kacheln, und z13 enthaelt nur die groessten (eine z13-Kachel hatte
 *    51 Gebaeuderinge gegen rund 1 000 je z14-Viertel); z12 hat gar keine.
 *    Fuer Stufe 1 sollen Gebaeude und Wald stimmen, also z14.
 *  * Vektor **z12** fuer Stufe 2: nur Landbedeckung fuer die erzeugte
 *    Bodentextur ohne Luftbild (Wald, Felder), eine Kachel deckt rund 6 km.
 *  * Hoehen **z13** (≈ 12 m je Pixel, Glaettung σ = 0,8 px) im Gebiet der
 *    Stufen 0/1, **z11** (≈ 48 m) fuer Stufe 2; dazwischen weich ueberblendet.
 */

/** Zoomstufe der Vektorkacheln fuer die Stufen 0 und 1. */
internal const val VECTOR_ZOOM_NEAR: Int = 14

/** Zoomstufe der Vektorkacheln fuer Stufe 2. */
internal const val VECTOR_ZOOM_FAR: Int = 12

/** Zoomstufe der Hoehenkacheln im Gebiet der Stufen 0 und 1. */
internal const val ELEVATION_ZOOM_NEAR: Int = 13

/** Zoomstufe der Hoehenkacheln fuer Stufe 2. */
internal const val ELEVATION_ZOOM_FAR: Int = 11

/** Gauss-Glaettung der Hoehen in Pixeln der jeweiligen Zoomstufe. */
internal const val ELEVATION_SMOOTHING_PX: Double = 0.8

/** Feine Hoehen ueber jede Stufe-1-Zelle hinaus (die Ueberblendung reicht eine halbe Zelle nach aussen). */
internal const val FINE_ELEVATION_MARGIN_M: Double = 600.0

/** Grobe Hoehen und Vektorkacheln ueber jede Kachel hinaus (Normalen, Schuerzen, Nachbarn). */
internal const val DATA_MARGIN_M: Double = 300.0

// ---------------------------------------------------------------------------
// Rechteck-Index
// ---------------------------------------------------------------------------

/** Schneller Rechteck-Index ueber Objekte mit Umgebungsrechteck; Abfragen liefern die Index-Reihenfolge. */
internal class RectIndex<T>(private val items: List<T>, private val box: (T) -> DoubleArray, private val cellM: Double = 256.0) {
    private val boxes = items.map(box)
    private val grid = HashMap<Long, IntArray>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for ((i, b) in boxes.withIndex()) {
            if (!b.all { it.isFinite() }) continue
            for (cy in cell(b[1])..cell(b[3])) for (cx in cell(b[0])..cell(b[2])) tmp.getOrPut(key(cx, cy)) { ArrayList() } += i
        }
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    fun query(minE: Double, minN: Double, maxE: Double, maxN: Double): List<T> {
        val cx0 = cell(minE)
        val cx1 = cell(maxE)
        val cy0 = cell(minN)
        val cy1 = cell(maxN)
        if ((cx1 - cx0 + 1).toLong() * (cy1 - cy0 + 1) > items.size) {
            return items.filterIndexed { i, _ -> hit(boxes[i], minE, minN, maxE, maxN) }
        }
        val found = java.util.BitSet(items.size)
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val ids = grid[key(cx, cy)] ?: continue
            for (i in ids) if (!found[i] && hit(boxes[i], minE, minN, maxE, maxN)) found.set(i)
        }
        val out = ArrayList<T>(found.cardinality())
        var i = found.nextSetBit(0)
        while (i >= 0) {
            out += items[i]
            i = found.nextSetBit(i + 1)
        }
        return out
    }

    private fun hit(b: DoubleArray, minE: Double, minN: Double, maxE: Double, maxN: Double) =
        b[2] >= minE && b[0] <= maxE && b[3] >= minN && b[1] <= maxN

    private fun cell(v: Double): Int = floor(v / cellM).toInt()

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
}

/** Umgebungsrechteck flacher `e, n`-Folgen. */
internal fun boundsOf(parts: List<DoubleArray>): DoubleArray {
    var a = Double.POSITIVE_INFINITY
    var b = Double.POSITIVE_INFINITY
    var c = Double.NEGATIVE_INFINITY
    var d = Double.NEGATIVE_INFINITY
    for (p in parts) {
        var i = 0
        while (i + 1 < p.size) {
            a = min(a, p[i]); c = max(c, p[i])
            b = min(b, p[i + 1]); d = max(d, p[i + 1])
            i += 2
        }
    }
    return doubleArrayOf(a, b, c, d)
}

// ---------------------------------------------------------------------------
// Kartendaten
// ---------------------------------------------------------------------------

/**
 * Klasse einer Flaeche aus den Vektorkacheln, `null` = nicht verwendet.
 *
 * Begruendete Zuordnungen: Obstwiese (`orchard`) und Baumschule
 * (`plant_nursery`) sind niedriger, unregelmaessiger Gehoelzbewuchs auf
 * Gruenland — naeher an [LandClass.SCRUB] als an gepflegtem Parkrasen.
 * Steinbruch (`quarry`) ist offener Fels ([LandClass.BARE]). Die
 * Schutzgebiete der Ebene `park` (`nature_reserve`, `protected_area` …)
 * ueberdecken Wald und Wiese und werden nicht verwendet; nur `park` selbst
 * ist [LandClass.PARK].
 */
internal fun landClassOf(layer: String, cls: String?, subclass: String?): LandClass? = when (layer) {
    LAYER_WATER -> LandClass.WATER
    LAYER_PARK -> if (cls == "park") LandClass.PARK else null
    LAYER_LANDCOVER -> when (cls) {
        "wood", "forest" -> LandClass.FOREST
        "farmland" -> when (subclass) {
            "orchard", "plant_nursery", "vineyard" -> LandClass.SCRUB
            else -> LandClass.FARMLAND
        }
        "grass" -> when (subclass) {
            "scrub", "heath" -> LandClass.SCRUB
            "park", "garden", "village_green", "recreation_ground" -> LandClass.PARK
            else -> LandClass.MEADOW
        }
        "wetland" -> LandClass.WETLAND
        "sand", "rock", "ice" -> LandClass.BARE
        else -> null
    }
    LAYER_LANDUSE -> when (cls) {
        "residential", "suburb", "neighbourhood" -> LandClass.RESIDENTIAL
        "industrial", "commercial", "retail" -> LandClass.INDUSTRIAL
        "cemetery", "pitch", "playground", "school" -> LandClass.PARK
        "quarry" -> LandClass.BARE
        else -> null
    }
    else -> null
}

/**
 * [MapData] ueber die Vektorkacheln: [fine] (z14) fuer die Stufen 0/1,
 * [coarse] (z12) fuer Stufe 2. Ein grobes Objekt kommt nur dazu, wenn das
 * Abfragerechteck nicht ganz im feinen Gebiet liegt und die Mitte des Objekts
 * ausserhalb des feinen Gebiets liegt — so erscheint nichts doppelt, wo es
 * feine Daten gibt.
 */
internal class VectorMapData(
    private val origin: GeoOrigin,
    fine: VectorIndex?,
    private val fineTiles: Set<TileId>,
    coarse: VectorIndex?,
) : MapData {
    private class Layered(val buildings: RectIndex<MapBuilding>, val roads: RectIndex<MapRoad>, val areas: RectIndex<MapArea>, val waterways: RectIndex<MapWaterway>)

    private val fineData = fine?.let { convert(it) }
    private val coarseData = coarse?.let { convert(it) }

    override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) =
        query(minE, minN, maxE, maxN, { it.buildings }) { boundsOf(it.rings) }

    override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) =
        query(minE, minN, maxE, maxN, { it.roads }) { boundsOf(listOf(it.line)) }

    override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) =
        query(minE, minN, maxE, maxN, { it.areas }) { boundsOf(it.rings) }

    override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) =
        query(minE, minN, maxE, maxN, { it.waterways }) { boundsOf(listOf(it.line)) }

    /** Liegt die Stelle im Gebiet der feinen Vektorkacheln? */
    fun isFine(e: Double, n: Double): Boolean =
        TileMath.tileOf(origin.toLat(n), origin.toLon(e), VECTOR_ZOOM_NEAR) in fineTiles

    private fun <T> query(
        minE: Double,
        minN: Double,
        maxE: Double,
        maxN: Double,
        pick: (Layered) -> RectIndex<T>,
        box: (T) -> DoubleArray,
    ): List<T> {
        val out = ArrayList<T>()
        fineData?.let { out += pick(it).query(minE, minN, maxE, maxN) }
        val coarse = coarseData ?: return out
        val allFine = isFine(minE, minN) && isFine(maxE, minN) && isFine(minE, maxN) && isFine(maxE, maxN)
        if (allFine && fineData != null) return out
        for (o in pick(coarse).query(minE, minN, maxE, maxN)) {
            val b = box(o)
            if (fineData == null || !isFine((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)) out += o
        }
        return out
    }

    private fun convert(index: VectorIndex): Layered {
        val buildings = index.all(LAYER_BUILDING).filter { it.geom == VecGeom.POLYGON && it.bool("hide_3d") != true }.map {
            val outer = it.outer
            MapBuilding(
                id = ((outer[0] * 10).roundToLong() shl 32) xor ((outer[1] * 10).roundToLong() and 0xFFFFFFFFL),
                rings = it.parts,
                heightM = it.num("render_height") ?: 5.0,
                minHeightM = it.num("render_min_height") ?: 0.0,
                colour = it.str("colour"),
            )
        }
        val roads = index.all(LAYER_TRANSPORTATION).filter { it.geom == VecGeom.LINE && it.cls != null && it.parts[0].size >= 4 }.map {
            val r = VecRoad(it)
            MapRoad(r.points, r.cls!!, r.subclass, r.surface, r.brunnel, r.layer, r.service)
        }
        val areas = ArrayList<MapArea>()
        for (layer in listOf(LAYER_LANDCOVER, LAYER_LANDUSE, LAYER_PARK, LAYER_WATER)) {
            for (f in index.all(layer)) {
                if (f.geom != VecGeom.POLYGON || f.str("brunnel") == "tunnel") continue
                val kind = landClassOf(layer, f.cls, f.str("subclass")) ?: continue
                areas += MapArea(f.parts, kind, f.str("subclass") ?: f.cls)
            }
        }
        val waterways = index.all(LAYER_WATERWAY).filter { it.geom == VecGeom.LINE && it.parts[0].size >= 4 }.map {
            val w = VecWaterway(it)
            MapWaterway(w.points, w.cls ?: "stream", w.brunnel)
        }
        return Layered(
            RectIndex(buildings, { boundsOf(it.rings) }),
            RectIndex(roads, { boundsOf(listOf(it.line)) }),
            RectIndex(areas, { boundsOf(it.rings) }, 512.0),
            RectIndex(waterways, { boundsOf(listOf(it.line)) }),
        )
    }
}

// ---------------------------------------------------------------------------
// Hoehen
// ---------------------------------------------------------------------------

/**
 * Gelaendehoehe ueber dem Meer: innen ([fineCells], die Stufe-1-Zellen) aus
 * dem feinen Hoehenfeld, aussen aus dem groben, dazwischen ueber eine halbe
 * Zelle weich ueberblendet (bilinear ueber die Zellmitten, geglaettet).
 */
internal class TerrainHeights(
    private val fine: ElevationField?,
    private val coarse: ElevationField?,
    private val fineCells: Set<TileSpec>,
) {
    fun aboveSea(e: Double, n: Double): Double {
        val w = when {
            coarse == null -> 1.0
            fine == null -> 0.0
            else -> fineWeight(e, n)
        }
        if (w >= 1.0) return fine!!.heightAt(e, n).toDouble()
        if (w <= 0.0) return coarse!!.heightAt(e, n).toDouble()
        return coarse!!.heightAt(e, n) + (fine!!.heightAt(e, n) - coarse.heightAt(e, n)) * w
    }

    fun aboveSeaLatLon(lat: Double, lon: Double): Double {
        val f = fine ?: coarse ?: error("Keine Hoehendaten geladen")
        return f.heightAtLatLon(lat, lon).toDouble()
    }

    private fun fineWeight(e: Double, n: Double): Double {
        val c = worldCellM(1)
        val gx = e / c - 0.5
        val gy = n / c - 0.5
        val x0 = floor(gx).toInt()
        val y0 = floor(gy).toInt()
        val tx = gx - x0
        val ty = gy - y0
        fun ind(x: Int, y: Int) = if (TileSpec(1, x, y) in fineCells) 1.0 else 0.0
        val v = (ind(x0, y0) * (1 - tx) + ind(x0 + 1, y0) * tx) * (1 - ty) + (ind(x0, y0 + 1) * (1 - tx) + ind(x0 + 1, y0 + 1) * tx) * ty
        return smoothstep(0.0, 1.0, v)
    }
}

/** Stufe-1-Zellen mit feinen Hoehen: die Stufe-1-Kacheln und die Eltern aller Stufe-0-Kacheln. */
internal fun fineCellsOf(specs: Collection<TileSpec>): Set<TileSpec> {
    val out = LinkedHashSet<TileSpec>()
    for (s in specs) when (s.level) {
        0 -> out += TileSpec(1, Math.floorDiv(s.ix, WORLD_LEVEL_FACTOR), Math.floorDiv(s.iy, WORLD_LEVEL_FACTOR))
        1 -> out += s
    }
    return out
}

/** Web-Mercator-Kacheln der Stufe [zoom], die die ENU-Rechtecke (je um [marginM] erweitert) beruehren. */
internal fun tilesForSpecs(origin: GeoOrigin, specs: Collection<TileSpec>, zoom: Int, marginM: Double): Set<TileId> {
    val out = LinkedHashSet<TileId>()
    for (s in specs) {
        val box = TileMath.enuToLatLon(origin, s.minE - marginM, s.minN - marginM, s.maxE + marginM, s.maxN + marginM)
        out += TileMath.tilesCovering(box, zoom)
    }
    return out
}

/** Die vier Kachelmengen, die eine Planung an Geodaten braucht. */
internal class GeoTileSets(val elevationNear: Set<TileId>, val elevationFar: Set<TileId>, val vectorNear: Set<TileId>, val vectorFar: Set<TileId>) {
    companion object {
        fun of(origin: GeoOrigin, specs: Collection<TileSpec>): GeoTileSets {
            val nearSpecs = specs.filter { it.level <= 1 }
            val farSpecs = specs.filter { it.level >= 2 }
            return GeoTileSets(
                elevationNear = tilesForSpecs(origin, fineCellsOf(specs), ELEVATION_ZOOM_NEAR, FINE_ELEVATION_MARGIN_M),
                elevationFar = tilesForSpecs(origin, farSpecs, ELEVATION_ZOOM_FAR, DATA_MARGIN_M),
                vectorNear = tilesForSpecs(origin, nearSpecs, VECTOR_ZOOM_NEAR, DATA_MARGIN_M),
                vectorFar = tilesForSpecs(origin, farSpecs, VECTOR_ZOOM_FAR, DATA_MARGIN_M),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bruecken und Belaege aus den Vektorkacheln
// ---------------------------------------------------------------------------

/** Naechste Linie in der Naehe einer Stelle, mit aehnlicher Richtung. */
internal fun nearestAligned(
    roads: List<MapRoad>,
    e: Double,
    n: Double,
    he: Double,
    hn: Double,
    maxDist: Double,
    minCos: Double,
): MapRoad? {
    var best: MapRoad? = null
    var bestD = maxDist
    for (r in roads) {
        val l = r.line
        for (i in 0 until l.size / 2 - 1) {
            val ax = l[2 * i]
            val ay = l[2 * i + 1]
            val dx = l[2 * i + 2] - ax
            val dy = l[2 * i + 3] - ay
            val len2 = dx * dx + dy * dy
            if (len2 <= 1e-6) continue
            val t = (((e - ax) * dx + (n - ay) * dy) / len2).coerceIn(0.0, 1.0)
            val d = hypot(ax + dx * t - e, ay + dy * t - n)
            if (d > bestD) continue
            val cos = abs(dx * he + dy * hn) / kotlin.math.sqrt(len2)
            if (cos < minCos) continue
            bestD = d
            best = r
        }
    }
    return best
}

/** Lage und Richtung (Einheitsvektor) der Strecke bei [d] in ENU. */
private fun routePose(route: IndoorRoute, origin: GeoOrigin, d: Double): DoubleArray {
    val open = if (route.isLoop) route.copy(isLoop = false) else route
    val a = open.sampleAt(max(0.0, d - 5.0))
    val b = open.sampleAt(min(route.lengthM, d + 5.0))
    val p = open.sampleAt(d)
    var he = origin.toEast(b.lon) - origin.toEast(a.lon)
    var hn = origin.toNorth(b.lat) - origin.toNorth(a.lat)
    val l = hypot(he, hn)
    if (l > 1e-9) { he /= l; hn /= l } else { he = 0.0; hn = 1.0 }
    return doubleArrayOf(origin.toEast(p.lon), origin.toNorth(p.lat), he, hn)
}

/** Abstand der Proben fuer Bruecken (in Metern). */
private const val BRIDGE_STEP_M: Double = 5.0

/**
 * Laufmeter auf Bruecken: eine `transportation`-Linie mit `brunnel=bridge`
 * hoechstens 8 m neben der Mittellinie und etwa gleich ausgerichtet.
 */
internal fun bridgeLookup(route: IndoorRoute, origin: GeoOrigin, map: MapData): (Double) -> Boolean {
    val count = (route.lengthM / BRIDGE_STEP_M).toInt() + 1
    val flags = BooleanArray(count)
    for (i in 0 until count) {
        val p = routePose(route, origin, min(i * BRIDGE_STEP_M, route.lengthM))
        val roads = map.roads(p[0] - 10, p[1] - 10, p[0] + 10, p[1] + 10).filter { it.brunnel == "bridge" }
        if (roads.isNotEmpty()) flags[i] = nearestAligned(roads, p[0], p[1], p[2], p[3], 8.0, 0.8) != null
    }
    if (flags.none { it }) return { false }
    return { d -> flags[(d / BRIDGE_STEP_M).let { if (it.isFinite()) Math.round(it).toInt() else 0 }.coerceIn(0, count - 1)] }
}

/** Ob die Belaege der Strecke aus den Vektorkacheln geschaetzt werden sollen (docs/indoor.md 4). */
internal fun needsVectorSurfaces(route: IndoorRoute): Boolean =
    route.source == IndoorRouteSource.NONE || route.surfaces.all { it.kind == SurfaceKind.UNKNOWN }

/** Probenabstand der Belagsschaetzung. */
private const val SURFACE_STEP_M: Double = 10.0

/** Kuerzere Abschnitte werden dem Nachbarn zugeschlagen. */
private const val SURFACE_MIN_SECTION_M: Double = 30.0

/**
 * Belaege aus den Vektorkacheln: Jede Stelle (alle 10 m) bekommt die Klasse
 * der naechsten `transportation`-Linie (hoechstens 15 m, Richtung hoechstens
 * etwa 45° verschieden) ueber [surfaceKindFromVectorTile]; gleiche Nachbarn
 * werden zusammengefasst, Abschnitte unter 30 m dem Vorgaenger zugeschlagen.
 * Passt nichts, bleibt die Strecke, wie sie ist.
 */
internal fun withVectorSurfaces(route: IndoorRoute, origin: GeoOrigin, map: MapData): IndoorRoute {
    val len = route.lengthM
    val count = (len / SURFACE_STEP_M).toInt() + 1
    val kinds = arrayOfNulls<SurfaceSection>(count)
    var matched = false
    for (i in 0 until count) {
        val d = min(i * SURFACE_STEP_M, len)
        val p = routePose(route, origin, d)
        val roads = map.roads(p[0] - 20, p[1] - 20, p[0] + 20, p[1] + 20).filter { it.cls != "rail" && it.cls != "transit" }
        val r = nearestAligned(roads, p[0], p[1], p[2], p[3], 15.0, 0.7)
        kinds[i] = if (r == null) {
            SurfaceSection(0.0, 0.0, SurfaceKind.UNKNOWN)
        } else {
            matched = matched || surfaceKindFromVectorTile(r.cls, r.surface) != SurfaceKind.UNKNOWN
            SurfaceSection(0.0, 0.0, surfaceKindFromVectorTile(r.cls, r.surface), highway = r.subclass ?: r.cls)
        }
    }
    if (!matched) return route
    // Abschnittsgrenzen in der Mitte zwischen zwei Proben.
    val sections = ArrayList<SurfaceSection>()
    var start = 0
    for (i in 1..count) {
        if (i == count || kinds[i]!!.kind != kinds[start]!!.kind || kinds[i]!!.highway != kinds[start]!!.highway) {
            val from = if (start == 0) 0.0 else (start - 0.5) * SURFACE_STEP_M
            val to = if (i == count) len else min(len, (i - 0.5) * SURFACE_STEP_M)
            if (to > from) sections += SurfaceSection(from, to, kinds[start]!!.kind, kinds[start]!!.highway)
            start = i
        }
    }
    val merged = ArrayList<SurfaceSection>()
    for (s in sections) {
        val last = merged.lastOrNull()
        when {
            last == null -> merged += s
            s.toM - s.fromM < SURFACE_MIN_SECTION_M || (last.kind == s.kind && last.highway == s.highway) ->
                merged[merged.size - 1] = last.copy(toM = s.toM)
            else -> merged += s
        }
    }
    // Ein zu kurzer erster Abschnitt geht im zweiten auf.
    if (merged.size > 1 && merged[0].toM - merged[0].fromM < SURFACE_MIN_SECTION_M) {
        merged[1] = merged[1].copy(fromM = 0.0)
        merged.removeAt(0)
    }
    return route.copy(surfaces = merged, source = IndoorRouteSource.VECTOR_TILES)
}

// ---------------------------------------------------------------------------
// Luftbilder je Kachel
// ---------------------------------------------------------------------------

/** Ein geholtes Luftbild samt dekodiertem Bild. */
internal class AerialTile(val image: AerialImage, val rgba: RgbaImage) {
    val texture: EncodedTexture get() = EncodedTexture(image.bytes, image.mimeType, image.width, image.height)
}

/**
 * Luftbilder je Kachel ([fetchAerial] in `quality.texturePx(level)`, Stufe 2
 * ein Bild je 4096-m-Kachel). Jede Kachel wird hoechstens einmal gleichzeitig
 * geholt; die letzten [capacity] Ergebnisse (auch „keines") bleiben im
 * Speicher, damit Kachelbau und Landnutzung dasselbe Bild nicht doppelt holen.
 * Bilder, deren Kanten keine Zweierpotenz ≤ 2048 sind, gelten als fehlend.
 * Threadsicher.
 */
internal class AerialStore(
    private val fetcher: GeoFetcher,
    private val decoder: RasterDecoder,
    private val origin: GeoOrigin,
    private val sources: List<AerialSource>,
    private val quality: IndoorQuality,
    private val capacity: Int = 12,
) {
    private class Slot(val tile: AerialTile?)

    private val lru = object : LinkedHashMap<TileSpec, Slot>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileSpec, Slot>?): Boolean = size > capacity
    }
    private val locks = java.util.concurrent.ConcurrentHashMap<TileSpec, Any>()

    fun get(spec: TileSpec): AerialTile? {
        if (sources.isEmpty()) return null
        synchronized(lru) { lru[spec] }?.let { return it.tile }
        val lock = locks.computeIfAbsent(spec) { Any() }
        synchronized(lock) {
            synchronized(lru) { lru[spec] }?.let { return it.tile }
            val tile = fetch(spec)
            synchronized(lru) { lru[spec] = Slot(tile) }
            locks.remove(spec)
            return tile
        }
    }

    private fun fetch(spec: TileSpec): AerialTile? {
        val px = quality.texturePx(spec.level)
        val img = fetchAerial(fetcher, decoder, origin, spec.minE, spec.minN, spec.maxE, spec.maxN, px, sources) ?: return null
        if (img.width != px || img.height != px) return null
        val raster = decoder.decode(img.bytes) ?: return null
        return AerialTile(img, RgbaImage.of(raster))
    }
}

/**
 * Holt die Kacheln [ids] eines Vektor-Kachelsatzes. 404/204 = leer, andere
 * Fehler und Netzfehler = fehlt. Ergebnis je Kachel: Daten oder `null`.
 */
internal fun fetchVectorTile(fetcher: GeoFetcher, tileJson: TileJson, id: TileId): VectorTileData? {
    val resp = try {
        fetcher.fetch(GeoRequest(GeoSourceKind.VECTOR, TileMath.fillTemplate(tileJson.tileTemplate, id)))
    } catch (_: IOException) {
        return null
    }
    return when (resp.status) {
        200 -> VectorTileData(id, decodeMvt(resp.bytes))
        204, 404 -> VectorTileData(id, MvtTile(emptyMap(), emptyList()))
        else -> null
    }
}
