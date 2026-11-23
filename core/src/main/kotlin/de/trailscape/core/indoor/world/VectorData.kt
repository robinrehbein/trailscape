package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.surfaceKindFromVectorTile
import java.io.IOException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # Karteninhalt fuer den Weltbau in ENU-Metern
 *
 * Aus den dekodierten Vektorkacheln ([decodeMvt]) einer Zoomstufe wird ein
 * [VectorIndex]: alle Objekte in ENU (`GeoOrigin.toEast/toNorth`), exakt an
 * den Kachelgrenzen geschnitten, Gebaeude ueber Kachelgrenzen wieder zu einem
 * Stueck vereinigt, Wege und Gewaesserlinien zu durchgehenden Zuegen
 * verkettet.
 *
 * ## Was OpenFreeMap liefert (geprueft am 23.11.2026, TileJSON-Version 3.16.0,
 * Kacheln 14/8516/5480 und 14/8516/5481, Altenberg/Odenthal)
 *  * Kantenlaenge 4096, Puffer **64** Pixel (z14 bei 51° N ≈ 24 m).
 *  * Feature-IDs sind gesetzt, aber **Objekte gleicher Merkmale sind
 *    zusammengefasst**: Eine Kachel hat z. B. 6 `building`-Objekte mit
 *    zusammen 1085 Ringen (ein Objekt = alle Gebaeude mit `render_height`
 *    5); `transportation` 97 Objekte mit 305 Linienteilen. Eine ID
 *    identifiziert also kein Gebaeude — die Wiedervereinigung ueber
 *    Kachelgrenzen geht deshalb ueber die **gemeinsame Schnittkante** auf der
 *    Grenze (bei gleichen Merkmalen), nicht ueber IDs ([rebuildRings]).
 *  * `building`: `render_height`, `render_min_height` (ganze Meter, Standard
 *    5 bzw. 0 ohne OSM-Angabe), selten `colour` (z. B. `white`, `brown`,
 *    `#5a81a0`), `hide_3d`. Keine Dachform.
 *  * `landcover`: `class`/`subclass` — im Raum Altenberg wood/forest,
 *    grass/meadow·grass·scrub·park·grassland·garden, farmland/farmland·orchard·
 *    plant_nursery, wetland/wetland. Schema-weit ausserdem sand, rock, ice.
 *  * `landuse`: `class` — dort residential, cemetery, pitch, playground,
 *    school, theme_park, track (Schema: auch industrial, commercial, retail,
 *    farmyard, quarry, …).
 *  * `park`: `class` (wildlife_refuge, conservation, historic, game_land),
 *    Flaechen und Beschriftungspunkte (`rank`).
 *  * `water`: Flaechen, `class` (river, pond, lake …), `intermittent`,
 *    `brunnel`, `id`. `waterway`: Linien, `class` (stream, river, drain,
 *    ditch, canal), `brunnel`, `intermittent`.
 *  * `transportation`: Linien (selten Flaechen, z. B. Bruecken, Plaetze);
 *    `class` (motorway … tertiary, minor, service, track, path, bridge),
 *    `subclass` (footway, cycleway, steps, platform …), `surface`
 *    (nur `paved`/`unpaved`), `brunnel`, `layer`, `ramp`, `oneway`,
 *    `service`, `access`, `bicycle`, `foot`, `horse`, `mtb_scale`, `toll`.
 *    **Kein** `tracktype`.
 *  * `mountain_peak` (Punkte: `ele`, `rank`), `place` (Punkte: `class`,
 *    `rank`, `name…`), `poi` (Punkte: `class`, `subclass`), `housenumber`.
 *  * **Nicht** enthalten: Einzelbaeume, Baumreihen, Hecken, Zaeune, Mauern,
 *    Dachformen, Gebaeudeteile mit eigener Form.
 *
 * ## Ausrichtung in ENU
 * Polygone: `parts[0]` ist der Aussenring **gegen** den Uhrzeigersinn
 * (positive Flaeche in e/n), weitere Teile sind Loecher im Uhrzeigersinn;
 * kein Schlusspunkt.
 */

internal enum class VecGeom { POINT, LINE, POLYGON }

/**
 * Ein Objekt in ENU-Metern.
 *
 * @property parts flache `e, n`-Folgen: ein Punkt, ein Linienzug oder
 *   `[aussen, loch, …]`.
 * @property tiles Kacheln, aus denen es zusammengesetzt ist.
 */
internal class VecFeature(
    val layer: String,
    val tags: Map<String, Any>,
    val geom: VecGeom,
    val parts: List<DoubleArray>,
    val tiles: List<TileId>,
) {
    val minE: Double
    val minN: Double
    val maxE: Double
    val maxN: Double

    init {
        var a = Double.POSITIVE_INFINITY
        var b = Double.POSITIVE_INFINITY
        var c = Double.NEGATIVE_INFINITY
        var d = Double.NEGATIVE_INFINITY
        for (p in parts) {
            var i = 0
            while (i < p.size) {
                a = min(a, p[i]); c = max(c, p[i])
                b = min(b, p[i + 1]); d = max(d, p[i + 1])
                i += 2
            }
        }
        minE = a; minN = b; maxE = c; maxN = d
    }

    /** `class` des OpenMapTiles-Schemas. */
    val cls: String? get() = str("class")

    fun str(key: String): String? = tags[key] as? String

    fun num(key: String): Double? = when (val v = tags[key]) {
        is Double -> v
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun bool(key: String): Boolean? = when (val v = tags[key]) {
        is Boolean -> v
        is Long -> v != 0L
        is String -> v == "true" || v == "1" || v == "yes"
        else -> null
    }

    /** Fuer Polygone: der Aussenring. */
    val outer: DoubleArray get() = parts[0]

    /** Fuer Polygone: die Loecher. */
    val holes: List<DoubleArray> get() = parts.subList(1, parts.size)

    fun intersects(minE: Double, minN: Double, maxE: Double, maxN: Double): Boolean =
        this.maxE >= minE && this.minE <= maxE && this.maxN >= minN && this.minN <= maxN
}

/** Eine dekodierte Vektorkachel samt Lage. */
internal class VectorTileData(val tile: TileId, val mvt: MvtTile)

/** Abfrage von Kartenobjekten in ENU — der Weltbau fragt nur hierueber (Tests setzen erfundene Daten ein). */
internal interface VectorSource {
    /**
     * Alle Objekte der Ebene [layer], deren Umgebungsrechteck das Rechteck
     * beruehrt. Die Geometrie ist **nicht** auf das Rechteck geschnitten.
     */
    fun features(layer: String, minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecFeature>
}

/**
 * Der Index ueber mehrere Kacheln einer Zoomstufe.
 *
 * @param mergedPolygonLayers Ebenen, deren Polygone ueber Kachelgrenzen
 *   vereinigt werden (Standard: Gebaeude — Waelder/Wiesen stossen nur
 *   nahtlos aneinander, das genuegt der Bodentextur).
 * @param chainedLineLayers Ebenen, deren Linien ueber Kachelgrenzen verkettet werden.
 * @param cellM Kantenlaenge der Rasterzellen des Suchindex in Metern.
 */
internal class VectorIndex(
    private val origin: GeoOrigin,
    tiles: Collection<VectorTileData>,
    mergedPolygonLayers: Set<String> = setOf(LAYER_BUILDING),
    chainedLineLayers: Set<String> = setOf(LAYER_TRANSPORTATION, LAYER_WATERWAY),
    private val cellM: Double = 256.0,
) : VectorSource {
    val zoom: Int = tiles.firstOrNull()?.tile?.z ?: 0

    /** Hinweise (kaputte Kacheln, Ebenen); leer, wenn alles glatt ging. */
    val diagnostics: List<String>

    private val byLayer: Map<String, List<VecFeature>>
    private val grids: Map<String, Map<Long, IntArray>>

    /** Je Ebene die belegten Zellen: minX, minY, maxX, maxY. */
    private val bounds: Map<String, IntArray>

    init {
        require(tiles.all { it.tile.z == zoom }) { "Vektorkacheln mit gemischter Zoomstufe" }
        val diag = ArrayList<String>()
        tiles.forEach { t -> t.mvt.diagnostics.forEach { diag += "${t.tile}: $it" } }
        val polys = HashMap<String, MutableList<PolyPiece>>()
        val lines = HashMap<String, MutableList<ChainPiece>>()
        val points = HashMap<String, MutableList<VecFeature>>()
        val e = VECTOR_GRID_EXTENT.toDouble()
        for (t in tiles.sortedWith(compareBy({ it.tile.y }, { it.tile.x }))) {
            val rect = PxRect(t.tile.x * e, t.tile.y * e, (t.tile.x + 1) * e, (t.tile.y + 1) * e)
            for (layer in t.mvt.layers.values) {
                val scale = e / layer.extent
                fun gx(px: Int) = rect.minX + px * scale
                fun gy(py: Int) = rect.minY + py * scale
                for (f in layer.features) when (f.type) {
                    MvtGeomType.POINT -> for (p in f.parts) {
                        val x = gx(p[0])
                        val y = gy(p[1])
                        // halb offen: Pufferpunkte gehoeren der Nachbarkachel
                        if (x >= rect.minX && x < rect.maxX && y >= rect.minY && y < rect.maxY) {
                            points.getOrPut(layer.name) { ArrayList() } +=
                                VecFeature(layer.name, f.tags, VecGeom.POINT, listOf(toEnu(doubleArrayOf(x, y))), listOf(t.tile))
                        }
                    }
                    MvtGeomType.LINESTRING -> for (part in f.parts) {
                        val g = DoubleArray(part.size) { if (it % 2 == 0) gx(part[it]) else gy(part[it]) }
                        for (clipped in clipPolylineToRect(g, rect)) {
                            lines.getOrPut(layer.name) { ArrayList() } +=
                                ChainPiece(clipped, t.tile, f.tags, isOneway(f.tags))
                        }
                    }
                    MvtGeomType.POLYGON -> for (poly in f.polygons()) {
                        val rings = (listOf(poly.outer) + poly.holes).map { ring ->
                            DoubleArray(ring.size) { if (it % 2 == 0) gx(ring[it]) else gy(ring[it]) }
                        }
                        for (piece in clipRingsToRect(rings, rect)) {
                            polys.getOrPut(layer.name) { ArrayList() } += PolyPiece(piece, t.tile, f.tags)
                        }
                    }
                    MvtGeomType.UNKNOWN -> Unit
                }
            }
        }
        val result = LinkedHashMap<String, MutableList<VecFeature>>()
        for ((layer, list) in points) result.getOrPut(layer) { ArrayList() } += list
        for ((layer, list) in lines) {
            val out = result.getOrPut(layer) { ArrayList() }
            if (layer in chainedLineLayers) {
                for ((ids, pts) in chainAcrossTiles(list)) {
                    out += VecFeature(layer, list[ids[0]].key.asTags(), VecGeom.LINE, listOf(toEnu(pts)), ids.map { list[it].tile }.distinct())
                }
            } else {
                for (p in list) out += VecFeature(layer, p.key.asTags(), VecGeom.LINE, listOf(toEnu(p.points)), listOf(p.tile))
            }
        }
        for ((layer, list) in polys) {
            val out = result.getOrPut(layer) { ArrayList() }
            val merged = if (layer in mergedPolygonLayers) mergeAcrossTiles(list) else list
            for (p in merged) out += VecFeature(layer, p.tags, VecGeom.POLYGON, p.rings.map { toEnuRing(it) }, p.tiles)
        }
        byLayer = result
        grids = result.mapValues { (_, feats) -> buildGrid(feats) }
        bounds = result.mapValues { (_, feats) ->
            if (feats.isEmpty()) intArrayOf(0, 0, -1, -1) else intArrayOf(
                feats.minOf { cell(it.minE) },
                feats.minOf { cell(it.minN) },
                feats.maxOf { cell(it.maxE) },
                feats.maxOf { cell(it.maxN) },
            )
        }
        diagnostics = diag
    }

    /** Alle Objekte einer Ebene. */
    fun all(layer: String): List<VecFeature> = byLayer[layer].orEmpty()

    val layerNames: Set<String> get() = byLayer.keys

    override fun features(layer: String, minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecFeature> {
        val feats = byLayer[layer] ?: return emptyList()
        val grid = grids.getValue(layer)
        val seen = java.util.BitSet(feats.size)
        val hits = java.util.BitSet(feats.size)
        // Suchrechteck auf die belegten Zellen begrenzen; ist es dann noch groesser
        // als die Zahl der Objekte, ist der lineare Durchlauf billiger.
        val b = bounds.getValue(layer)
        val cx0 = maxOf(cell(minE), b[0])
        val cy0 = maxOf(cell(minN), b[1])
        val cx1 = minOf(cell(maxE), b[2])
        val cy1 = minOf(cell(maxN), b[3])
        if (cx0 > cx1 || cy0 > cy1) return emptyList()
        if ((cx1 - cx0 + 1).toLong() * (cy1 - cy0 + 1) > feats.size) {
            return feats.filter { it.intersects(minE, minN, maxE, maxN) }
        }
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val ids = grid[cellKey(cx, cy)] ?: continue
            for (i in ids) {
                if (seen[i]) continue
                seen[i] = true
                if (feats[i].intersects(minE, minN, maxE, maxN)) hits.set(i)
            }
        }
        // feste Reihenfolge (die des Index), unabhaengig von der Rasterzelle
        val out = ArrayList<VecFeature>(hits.cardinality())
        var i = hits.nextSetBit(0)
        while (i >= 0) {
            out += feats[i]
            i = hits.nextSetBit(i + 1)
        }
        return out
    }

    private fun cell(v: Double): Int = floor(v / cellM).toInt()

    private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    private fun buildGrid(feats: List<VecFeature>): Map<Long, IntArray> {
        val tmp = HashMap<Long, MutableList<Int>>()
        for ((i, f) in feats.withIndex()) {
            for (cy in cell(f.minN)..cell(f.maxN)) for (cx in cell(f.minE)..cell(f.maxE)) {
                tmp.getOrPut(cellKey(cx, cy)) { ArrayList() } += i
            }
        }
        return tmp.mapValues { it.value.toIntArray() }
    }

    /** Globale Kachelpixel → ENU (ueber lat/lon und [GeoOrigin]). */
    private fun toEnu(g: DoubleArray): DoubleArray {
        val e = VECTOR_GRID_EXTENT.toDouble()
        val out = DoubleArray(g.size)
        var i = 0
        while (i < g.size) {
            out[i] = origin.toEast(TileMath.tileXToLon(g[i] / e, zoom))
            out[i + 1] = origin.toNorth(TileMath.tileYToLat(g[i + 1] / e, zoom))
            i += 2
        }
        return out
    }

    /** Wie [toEnu], dreht den Umlaufsinn um (y nach unten → n nach oben), damit aussen positiv bleibt. */
    private fun toEnuRing(g: DoubleArray): DoubleArray {
        val enu = toEnu(g)
        val n = enu.size / 2
        val out = DoubleArray(enu.size)
        for (k in 0 until n) {
            out[2 * k] = enu[2 * (n - 1 - k)]
            out[2 * k + 1] = enu[2 * (n - 1 - k) + 1]
        }
        return out
    }

    private companion object {
        fun isOneway(tags: Map<String, Any>): Boolean = when (val v = tags["oneway"]) {
            is Long -> v != 0L
            is Double -> v != 0.0
            is Boolean -> v
            is String -> v != "0" && v != "no"
            else -> false
        }

        @Suppress("UNCHECKED_CAST")
        fun Any.asTags(): Map<String, Any> = this as Map<String, Any>
    }
}

/** Ein geschnittenes Polygon in globalen Kachelpixeln: `[aussen, loch, …]`, aussen positiv (y nach unten). */
internal class PolyPiece(val rings: List<DoubleArray>, val tiles: List<TileId>, val tags: Map<String, Any>) {
    constructor(rings: List<DoubleArray>, tile: TileId, tags: Map<String, Any>) : this(rings, listOf(tile), tags)
}

/**
 * Vereinigt Polygonteile aus **verschiedenen** Kacheln mit gleichen
 * Merkmalen, die sich eine Schnittkante auf einer Kachelgrenze teilen
 * (gegenlaeufige, ueberlappende Randstuecke). Teile ohne Partner bleiben, wie
 * sie sind.
 */
internal fun mergeAcrossTiles(pieces: List<PolyPiece>): List<PolyPiece> {
    val e = VECTOR_GRID_EXTENT.toDouble()
    class BorderSeg(val lo: Double, val hi: Double, val dir: Int, val piece: Int)
    val lines = HashMap<Pair<Int, Double>, MutableList<BorderSeg>>() // (0 = senkrecht bei x, 1 = waagrecht bei y)
    for ((pi, p) in pieces.withIndex()) {
        for (ring in p.rings) {
            val n = ring.size / 2
            for (i in 0 until n) {
                val j = if (i + 1 == n) 0 else i + 1
                val x1 = ring[2 * i]
                val y1 = ring[2 * i + 1]
                val x2 = ring[2 * j]
                val y2 = ring[2 * j + 1]
                if (x1 == x2 && y1 != y2 && x1 % e == 0.0) {
                    lines.getOrPut(0 to x1) { ArrayList() } += BorderSeg(min(y1, y2), max(y1, y2), if (y2 > y1) 1 else -1, pi)
                } else if (y1 == y2 && x1 != x2 && y1 % e == 0.0) {
                    lines.getOrPut(1 to y1) { ArrayList() } += BorderSeg(min(x1, x2), max(x1, x2), if (x2 > x1) 1 else -1, pi)
                }
            }
        }
    }
    val parent = IntArray(pieces.size) { it }
    fun find(i: Int): Int {
        var r = i
        while (parent[r] != r) r = parent[r]
        var c = i
        while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
        return r
    }
    for (segs in lines.values) {
        if (segs.size < 2) continue
        segs.sortBy { it.lo }
        for (a in segs.indices) {
            val sa = segs[a]
            for (b in a + 1 until segs.size) {
                val sb = segs[b]
                if (sb.lo >= sa.hi) break
                if (sa.dir == sb.dir || sa.piece == sb.piece) continue
                val pa = pieces[sa.piece]
                val pb = pieces[sb.piece]
                if (pa.tiles == pb.tiles || pa.tags != pb.tags) continue
                parent[find(sa.piece)] = find(sb.piece)
            }
        }
    }
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    for (i in pieces.indices) groups.getOrPut(find(i)) { ArrayList() } += i
    val out = ArrayList<PolyPiece>()
    for (members in groups.values) {
        if (members.size == 1) {
            out += pieces[members[0]]
            continue
        }
        val rings = members.flatMap { pieces[it].rings }
        val tiles = members.flatMap { pieces[it].tiles }.distinct()
        for (poly in groupRings(rebuildRings(rings))) out += PolyPiece(poly, tiles, pieces[members[0]].tags)
    }
    return out
}

// ---------------------------------------------------------------------------
// Ebenen und typisierte Helfer
// ---------------------------------------------------------------------------

internal const val LAYER_BUILDING = "building"
internal const val LAYER_LANDCOVER = "landcover"
internal const val LAYER_LANDUSE = "landuse"
internal const val LAYER_PARK = "park"
internal const val LAYER_WATER = "water"
internal const val LAYER_WATERWAY = "waterway"
internal const val LAYER_TRANSPORTATION = "transportation"
internal const val LAYER_TRANSPORTATION_NAME = "transportation_name"
internal const val LAYER_PLACE = "place"
internal const val LAYER_MOUNTAIN_PEAK = "mountain_peak"
internal const val LAYER_POI = "poi"

/** Ein Gebaeude. Hoehen in Metern ueber Grund (OpenMapTiles setzt 5 bzw. 0, wenn OSM nichts angibt). */
internal class VecBuilding(val feature: VecFeature, val heightM: Double, val minHeightM: Double, val colour: String?, val hide3d: Boolean)

/** Eine Flaeche aus `landcover`, `landuse`, `park` oder `water`. */
internal class VecArea(val feature: VecFeature, val cls: String?, val subclass: String?)

/** Ein Weg der Ebene `transportation` (nur Linien). */
internal class VecRoad(val feature: VecFeature) {
    val points: DoubleArray get() = feature.parts[0]
    val cls: String? get() = feature.str("class")
    val subclass: String? get() = feature.str("subclass")

    /** `paved`/`unpaved` oder `null`. */
    val surface: String? get() = feature.str("surface")

    /** `bridge`, `tunnel`, `ford` oder `null`. */
    val brunnel: String? get() = feature.str("brunnel")
    val layer: Int get() = feature.num("layer")?.toInt() ?: 0
    val ramp: Boolean get() = feature.bool("ramp") ?: false
    val oneway: Int get() = feature.num("oneway")?.toInt() ?: 0
    val service: String? get() = feature.str("service")
    val access: String? get() = feature.str("access")
    val bicycle: String? get() = feature.str("bicycle")
    val mtbScale: String? get() = feature.str("mtb_scale")
    val surfaceKind: SurfaceKind get() = surfaceKindFromVectorTile(cls, surface)
}

/** Ein Gewaesserlauf (Linie). */
internal class VecWaterway(val feature: VecFeature) {
    val points: DoubleArray get() = feature.parts[0]
    val cls: String? get() = feature.str("class")
    val brunnel: String? get() = feature.str("brunnel")
    val intermittent: Boolean get() = feature.bool("intermittent") ?: false
}

/** Ein benannter Punkt (`place`, `mountain_peak`). */
internal class VecPlace(val feature: VecFeature) {
    val e: Double get() = feature.parts[0][0]
    val n: Double get() = feature.parts[0][1]
    val cls: String? get() = feature.str("class")
    val name: String? get() = feature.str("name:de") ?: feature.str("name") ?: feature.str("name:latin")
    val rank: Int? get() = feature.num("rank")?.toInt()
    val eleM: Double? get() = feature.num("ele")
}

internal fun VectorSource.buildings(minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecBuilding> =
    features(LAYER_BUILDING, minE, minN, maxE, maxN).filter { it.geom == VecGeom.POLYGON }.map {
        VecBuilding(
            feature = it,
            heightM = it.num("render_height") ?: 5.0,
            minHeightM = it.num("render_min_height") ?: 0.0,
            colour = it.str("colour"),
            hide3d = it.bool("hide_3d") ?: false,
        )
    }

/** Flaechen einer der Ebenen [LAYER_LANDCOVER], [LAYER_LANDUSE], [LAYER_PARK], [LAYER_WATER]. */
internal fun VectorSource.areas(layer: String, minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecArea> =
    features(layer, minE, minN, maxE, maxN).filter { it.geom == VecGeom.POLYGON }.map { VecArea(it, it.cls, it.str("subclass")) }

internal fun VectorSource.roads(minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecRoad> =
    features(LAYER_TRANSPORTATION, minE, minN, maxE, maxN).filter { it.geom == VecGeom.LINE }.map { VecRoad(it) }

internal fun VectorSource.waterways(minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecWaterway> =
    features(LAYER_WATERWAY, minE, minN, maxE, maxN).filter { it.geom == VecGeom.LINE }.map { VecWaterway(it) }

/** Orte ([LAYER_PLACE]) oder Gipfel ([LAYER_MOUNTAIN_PEAK]). */
internal fun VectorSource.places(layer: String, minE: Double, minN: Double, maxE: Double, maxN: Double): List<VecPlace> =
    features(layer, minE, minN, maxE, maxN).filter { it.geom == VecGeom.POINT }.map { VecPlace(it) }

// ---------------------------------------------------------------------------
// Laden
// ---------------------------------------------------------------------------

/** Liest das TileJSON ueber die Huelle. `null` bei Fehlerstatus, Netzfehler oder unlesbarem Inhalt. */
internal fun fetchTileJson(fetcher: GeoFetcher): TileJson? = try {
    val resp = fetcher.fetch(GeoRequest(GeoSourceKind.VECTOR, GeoEndpoints.VECTOR_TILEJSON_URL, accept = "application/json"))
    if (resp.status == 200) parseTileJson(String(resp.bytes, Charsets.UTF_8)) else null
} catch (_: IOException) {
    null
}

/**
 * Ergebnis von [loadVectorIndex]. [missing] sind Kacheln ohne Daten (Netz-
 * oder Quellfehler) — der Weltbau setzt dort `degraded = true`.
 */
internal class VectorLoadResult(val index: VectorIndex, val missing: List<TileId>)

/**
 * Holt alle Vektorkacheln der Stufe [zoom] (hoechstens `tileJson.maxZoom`),
 * die [box] beruehren, und baut den Index. 404 = leere Kachel (z. B. Meer);
 * andere Fehler und Netzfehler machen die Kachel zu „fehlt".
 */
internal fun loadVectorIndex(
    fetcher: GeoFetcher,
    tileJson: TileJson,
    origin: GeoOrigin,
    box: LatLonBox,
    zoom: Int,
    onTile: (done: Int, total: Int) -> Unit = { _, _ -> },
): VectorLoadResult {
    val z = zoom.coerceIn(tileJson.minZoom, tileJson.maxZoom)
    val ids = TileMath.tilesCovering(box, z)
    val tiles = ArrayList<VectorTileData>()
    val missing = ArrayList<TileId>()
    for ((i, id) in ids.withIndex()) {
        val resp = try {
            fetcher.fetch(GeoRequest(GeoSourceKind.VECTOR, TileMath.fillTemplate(tileJson.tileTemplate, id)))
        } catch (_: IOException) {
            null
        }
        when {
            resp == null -> missing += id
            resp.status == 200 -> tiles += VectorTileData(id, decodeMvt(resp.bytes))
            resp.status == 404 || resp.status == 204 -> tiles += VectorTileData(id, MvtTile(emptyMap(), emptyList()))
            else -> missing += id
        }
        onTile(i + 1, ids.size)
    }
    return VectorLoadResult(VectorIndex(origin, tiles), missing)
}

// ===========================================================================
// Geometrie an Kachelgrenzen
// ===========================================================================

/**
 * # Geometrie an Kachelgrenzen: Clipping, Ringe neu aufbauen, Verketten
 *
 * Gerechnet wird in **globalen Kachelpixeln** einer Zoomstufe:
 * `gx = x·E + px`, `gy = y·E + py` mit `E` = [VECTOR_GRID_EXTENT]. Die
 * Grenzen zwischen Kacheln liegen dadurch auf exakt darstellbaren Zahlen
 * (Vielfache von `E`), und dieselbe Kante wird in beiden Nachbarkacheln
 * bitgleich geschnitten (Schnittpunkte werden auf 1/[SNAP] Pixel gerundet
 * und aus den Endpunkten in fester Reihenfolge berechnet).
 *
 * Vektorkacheln tragen Geometrie bis 64 Pixel (von 4096) ueber den Rand
 * hinaus (bei OpenFreeMap z14 ≈ 24 m). Ohne Behandlung erscheinen Flaechen
 * und Wege doppelt; deshalb wird alles exakt auf `[0, E]` geschnitten
 * ([clipRingsToRect], [clipPolylineToRect]).
 */

/** Rasterkante der globalen Kachelpixel. Kacheln anderer Kantenlaenge werden darauf skaliert. */
internal const val VECTOR_GRID_EXTENT: Int = 4096

/** Schnittpunkte werden auf 1/SNAP Pixel gerundet. */
private const val SNAP = 256.0

internal fun snap(v: Double): Double = Math.rint(v * SNAP) / SNAP

/** Achsparalleles Rechteck in globalen Kachelpixeln. */
internal data class PxRect(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/** Doppelte Vorzeichenflaeche eines flachen Rings (ohne Schlusspunkt); in Kachelpixeln (y nach unten) ist aussen positiv. */
internal fun ringArea2(ring: DoubleArray): Double {
    val n = ring.size / 2
    var s = 0.0
    for (i in 0 until n) {
        val j = if (i + 1 == n) 0 else i + 1
        s += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1]
    }
    return s
}

// ---------------------------------------------------------------------------
// Sutherland–Hodgman fuer Ringe
// ---------------------------------------------------------------------------

/**
 * Schneidet einen Ring an [r] (Sutherland–Hodgman). Das Ergebnis kann bei
 * konkaven Ringen Stege der Breite null entlang des Rands enthalten — die
 * entfernt [rebuildRings].
 */
internal fun clipRingToRect(ring: DoubleArray, r: PxRect): DoubleArray {
    var cur = ring
    cur = clipHalf(cur, 0, r.minX, true)
    cur = clipHalf(cur, 0, r.maxX, false)
    cur = clipHalf(cur, 1, r.minY, true)
    cur = clipHalf(cur, 1, r.maxY, false)
    return cur
}

/** Eine Halbebene: Achse [axis] (0 = x, 1 = y), innen ist `≥ c` ([keepGreater]) bzw. `≤ c`. */
private fun clipHalf(ring: DoubleArray, axis: Int, c: Double, keepGreater: Boolean): DoubleArray {
    val n = ring.size / 2
    if (n == 0) return ring
    val out = DoubleBuilder(ring.size + 8)
    fun inside(i: Int): Boolean = if (keepGreater) ring[2 * i + axis] >= c else ring[2 * i + axis] <= c
    for (i in 0 until n) {
        val s = if (i == 0) n - 1 else i - 1
        val inE = inside(i)
        val inS = inside(s)
        if (inE) {
            if (!inS) addIntersection(out, ring, s, i, axis, c)
            out.add(ring[2 * i], ring[2 * i + 1])
        } else if (inS) {
            addIntersection(out, ring, s, i, axis, c)
        }
    }
    return out.toArray()
}

private fun addIntersection(out: DoubleBuilder, ring: DoubleArray, a: Int, b: Int, axis: Int, c: Double) {
    // feste Reihenfolge der Endpunkte, damit beide Nachbarkacheln bitgleich rechnen
    var ax = ring[2 * a]
    var ay = ring[2 * a + 1]
    var bx = ring[2 * b]
    var by = ring[2 * b + 1]
    if (ax > bx || (ax == bx && ay > by)) {
        val tx = ax; ax = bx; bx = tx
        val ty = ay; ay = by; by = ty
    }
    if (axis == 0) {
        val t = (c - ax) / (bx - ax)
        out.add(c, snap(ay + t * (by - ay)))
    } else {
        val t = (c - ay) / (by - ay)
        out.add(snap(ax + t * (bx - ax)), c)
    }
}

// ---------------------------------------------------------------------------
// Ringe neu aufbauen: Randkanten gegeneinander aufheben, neu verketten
// ---------------------------------------------------------------------------

/**
 * Baut aus den Kanten aller [rings] (gleich orientiert: aussen positiv,
 * Loecher negativ) neue Ringe. Kanten, die auf einer Kachelgrenze liegen
 * (Vielfache von [VECTOR_GRID_EXTENT]), werden je Linie aufsummiert:
 * Gegenlaeufige Stuecke heben sich auf. Das entfernt
 *  * die Stege, die Sutherland–Hodgman bei konkaven Ringen erzeugt,
 *  * Loecher, die an den Rand stossen (sie werden zur Einbuchtung), und
 *  * die Schnittkante zwischen den Teilen eines Polygons aus zwei
 *    Nachbarkacheln — so werden Gebaeude wieder ein Stueck.
 *
 * Ergebnis: Ringe ohne Schlusspunkt, Flaeche ≠ 0.
 */
internal fun rebuildRings(rings: List<DoubleArray>): List<DoubleArray> {
    val inner = ArrayList<DoubleArray>() // x1, y1, x2, y2
    val vertical = HashMap<Double, MutableList<DoubleArray>>() // x → (lo, hi, dir)
    val horizontal = HashMap<Double, MutableList<DoubleArray>>()
    val e = VECTOR_GRID_EXTENT.toDouble()
    for (ring in rings) {
        val n = ring.size / 2
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val x1 = ring[2 * i]
            val y1 = ring[2 * i + 1]
            val x2 = ring[2 * j]
            val y2 = ring[2 * j + 1]
            if (x1 == x2 && y1 == y2) continue
            when {
                x1 == x2 && x1 % e == 0.0 ->
                    vertical.getOrPut(x1) { ArrayList() } += doubleArrayOf(min(y1, y2), max(y1, y2), if (y2 > y1) 1.0 else -1.0)
                y1 == y2 && y1 % e == 0.0 ->
                    horizontal.getOrPut(y1) { ArrayList() } += doubleArrayOf(min(x1, x2), max(x1, x2), if (x2 > x1) 1.0 else -1.0)
                else -> inner += doubleArrayOf(x1, y1, x2, y2)
            }
        }
    }
    for ((x, segs) in vertical) for (s in cancel(segs)) {
        inner += if (s[2] > 0) doubleArrayOf(x, s[0], x, s[1]) else doubleArrayOf(x, s[1], x, s[0])
    }
    for ((y, segs) in horizontal) for (s in cancel(segs)) {
        inner += if (s[2] > 0) doubleArrayOf(s[0], y, s[1], y) else doubleArrayOf(s[1], y, s[0], y)
    }
    return chainEdges(inner)
}

/** Summiert gerichtete Stuecke einer Linie; uebrig bleiben die Abschnitte mit Nettorichtung ≠ 0, zusammengefasst. */
private fun cancel(segs: List<DoubleArray>): List<DoubleArray> {
    if (segs.size == 1) return segs
    val breaks = segs.flatMap { listOf(it[0], it[1]) }.distinct().sorted()
    val out = ArrayList<DoubleArray>()
    for (k in 0 until breaks.size - 1) {
        val a = breaks[k]
        val b = breaks[k + 1]
        var net = 0.0
        for (s in segs) if (s[0] <= a && s[1] >= b) net += s[2]
        if (net == 0.0) continue
        val dir = if (net > 0) 1.0 else -1.0
        val last = out.lastOrNull()
        if (last != null && last[1] == a && last[2] == dir) last[1] = b else out += doubleArrayOf(a, b, dir)
    }
    return out
}

/** Verkettet gerichtete Kanten zu Ringen (Endpunkt an Anfangspunkt). */
private fun chainEdges(edges: List<DoubleArray>): List<DoubleArray> {
    val byStart = HashMap<Pt, ArrayDeque<Int>>()
    for ((i, ed) in edges.withIndex()) byStart.getOrPut(Pt(ed[0], ed[1])) { ArrayDeque() }.addLast(i)
    val used = BooleanArray(edges.size)
    val rings = ArrayList<DoubleArray>()
    for (start in edges.indices) {
        if (used[start]) continue
        val ring = DoubleBuilder(16)
        var cur = start
        val origin = Pt(edges[start][0], edges[start][1])
        while (true) {
            used[cur] = true
            ring.add(edges[cur][0], edges[cur][1])
            val end = Pt(edges[cur][2], edges[cur][3])
            if (end == origin) break
            val queue = byStart[end] ?: break
            var next = -1
            while (queue.isNotEmpty()) {
                val cand = queue.removeFirst()
                if (!used[cand]) {
                    next = cand
                    break
                }
            }
            if (next < 0) break
            cur = next
        }
        val arr = removeCollinear(ring.toArray())
        if (arr.size >= 6 && abs(ringArea2(arr)) > 1e-9) rings += arr
    }
    return rings
}

private data class Pt(val x: Double, val y: Double)

/** Entfernt Punkte, die mit ihren Nachbarn auf einer achsparallelen Geraden liegen (Reste der Randkanten). */
private fun removeCollinear(ring: DoubleArray): DoubleArray {
    val n = ring.size / 2
    if (n < 4) return ring
    val out = DoubleBuilder(ring.size)
    for (i in 0 until n) {
        val p = if (i == 0) n - 1 else i - 1
        val q = if (i + 1 == n) 0 else i + 1
        val x = ring[2 * i]
        val y = ring[2 * i + 1]
        val sameX = ring[2 * p] == x && ring[2 * q] == x
        val sameY = ring[2 * p + 1] == y && ring[2 * q + 1] == y
        if (!sameX && !sameY) out.add(x, y)
    }
    return out.toArray()
}

/**
 * Ringe nach Umlaufsinn zu Polygonen ordnen: aussen positiv, Loecher negativ;
 * ein Loch gehoert zum kleinsten Aussenring, der seinen ersten Punkt enthaelt.
 * Ergebnis je Polygon: `[aussen, loch, loch, …]`.
 */
internal fun groupRings(rings: List<DoubleArray>): List<List<DoubleArray>> {
    val outers = rings.filter { ringArea2(it) > 0 }.sortedBy { ringArea2(it) }
    val groups = outers.map { mutableListOf(it) }
    for (hole in rings) {
        if (ringArea2(hole) >= 0) continue
        val idx = outers.indexOfFirst { pointInRing(hole[0], hole[1], it) }
        if (idx >= 0) groups[idx] += hole
    }
    return groups
}

internal fun pointInRing(x: Double, y: Double, ring: DoubleArray): Boolean {
    val n = ring.size / 2
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = ring[2 * i]
        val yi = ring[2 * i + 1]
        val xj = ring[2 * j]
        val yj = ring[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/**
 * Schneidet alle Ringe eines Polygons an [r] und baut sie neu auf
 * ([rebuildRings]). Ergebnis: saubere Polygone innerhalb des Rechtecks.
 */
internal fun clipRingsToRect(rings: List<DoubleArray>, r: PxRect): List<List<DoubleArray>> {
    val clipped = rings.map { clipRingToRect(it, r) }.filter { it.size >= 6 }
    if (clipped.isEmpty()) return emptyList()
    return groupRings(rebuildRings(clipped))
}

// ---------------------------------------------------------------------------
// Liang–Barsky fuer Linienzuege
// ---------------------------------------------------------------------------

/**
 * Schneidet einen Linienzug an [r] (Liang–Barsky je Strecke). Ein Zug, der
 * das Rechteck mehrfach verlaesst, zerfaellt in mehrere Teile.
 */
internal fun clipPolylineToRect(line: DoubleArray, r: PxRect): List<DoubleArray> {
    val out = ArrayList<DoubleArray>()
    var cur: DoubleBuilder? = null
    val seg = DoubleArray(4)
    for (i in 0 until line.size / 2 - 1) {
        if (!clipSegment(line[2 * i], line[2 * i + 1], line[2 * i + 2], line[2 * i + 3], r, seg)) {
            cur?.let { if (it.size >= 4) out += it.toArray() }
            cur = null
            continue
        }
        val c = cur
        if (c != null && c.lastX() == seg[0] && c.lastY() == seg[1]) {
            c.add(seg[2], seg[3])
        } else {
            c?.let { if (it.size >= 4) out += it.toArray() }
            cur = DoubleBuilder(16).also {
                it.add(seg[0], seg[1])
                it.add(seg[2], seg[3])
            }
        }
    }
    cur?.let { if (it.size >= 4) out += it.toArray() }
    return out.filter { it.size >= 4 && !(it.size == 4 && it[0] == it[2] && it[1] == it[3]) }
}

/** Liang–Barsky; Punkte auf einer Grenze bekommen den Grenzwert exakt. */
private fun clipSegment(x1: Double, y1: Double, x2: Double, y2: Double, r: PxRect, out: DoubleArray): Boolean {
    // feste Reihenfolge, damit Nachbarkacheln bitgleich schneiden
    val swap = x1 > x2 || (x1 == x2 && y1 > y2)
    val ax = if (swap) x2 else x1
    val ay = if (swap) y2 else y1
    val bx = if (swap) x1 else x2
    val by = if (swap) y1 else y2
    val dx = bx - ax
    val dy = by - ay
    var t0 = 0.0
    var t1 = 1.0
    var b0 = -1
    var b1 = -1
    val p = doubleArrayOf(-dx, dx, -dy, dy)
    val q = doubleArrayOf(ax - r.minX, r.maxX - ax, ay - r.minY, r.maxY - ay)
    for (k in 0 until 4) {
        if (p[k] == 0.0) {
            if (q[k] < 0) return false
        } else {
            val t = q[k] / p[k]
            if (p[k] < 0) {
                if (t > t1) return false
                if (t > t0) { t0 = t; b0 = k }
            } else {
                if (t < t0) return false
                if (t < t1) { t1 = t; b1 = k }
            }
        }
    }
    var sx = if (b0 >= 0) snap(ax + t0 * dx) else ax
    var sy = if (b0 >= 0) snap(ay + t0 * dy) else ay
    var ex = if (b1 >= 0) snap(ax + t1 * dx) else bx
    var ey = if (b1 >= 0) snap(ay + t1 * dy) else by
    when (b0) { 0 -> sx = r.minX; 1 -> sx = r.maxX; 2 -> sy = r.minY; 3 -> sy = r.maxY }
    when (b1) { 0 -> ex = r.minX; 1 -> ex = r.maxX; 2 -> ey = r.minY; 3 -> ey = r.maxY }
    if (swap) {
        out[0] = ex; out[1] = ey; out[2] = sx; out[3] = sy
    } else {
        out[0] = sx; out[1] = sy; out[2] = ex; out[3] = ey
    }
    return true
}

// ---------------------------------------------------------------------------
// Linien ueber Kachelgrenzen verketten
// ---------------------------------------------------------------------------

/**
 * Ein Linienstueck zum Verketten: [points] in globalen Kachelpixeln, [tile]
 * die Herkunftskachel, [key] die Merkmale (nur gleiche werden verbunden),
 * [oneway] = Richtung zaehlt (nur Ende an Anfang, nie umdrehen).
 */
internal class ChainPiece(val points: DoubleArray, val tile: TileId, val key: Any, val oneway: Boolean)

/**
 * Verbindet Linienstuecke, deren Enden auf einer Kachelgrenze im selben Punkt
 * liegen, aus **verschiedenen** Kacheln stammen und gleiche Merkmale haben,
 * zu durchgehenden Zuegen. An einem Grenzpunkt mit mehr als zwei passenden
 * Enden (Abzweig genau auf der Grenze) wird nichts verbunden.
 *
 * @return je Zug die Indizes der beteiligten Stuecke und die Punkte.
 */
internal fun chainAcrossTiles(pieces: List<ChainPiece>): List<Pair<List<Int>, DoubleArray>> {
    val e = VECTOR_GRID_EXTENT.toDouble()
    fun onBorder(x: Double, y: Double) = x % e == 0.0 || y % e == 0.0
    // Ende = Stueck·2 + (0 Anfang, 1 Ende)
    val byPoint = HashMap<Triple<Double, Double, Any>, MutableList<Int>>()
    for ((i, p) in pieces.withIndex()) {
        val n = p.points.size / 2
        if (n < 2) continue
        val sx = p.points[0]
        val sy = p.points[1]
        val ex = p.points[2 * n - 2]
        val ey = p.points[2 * n - 1]
        if (onBorder(sx, sy)) byPoint.getOrPut(Triple(sx, sy, p.key)) { ArrayList() } += 2 * i
        if (onBorder(ex, ey)) byPoint.getOrPut(Triple(ex, ey, p.key)) { ArrayList() } += 2 * i + 1
    }
    val partner = IntArray(pieces.size * 2) { -1 }
    for (ends in byPoint.values) {
        if (ends.size != 2) continue
        val a = ends[0]
        val b = ends[1]
        val pa = pieces[a / 2]
        val pb = pieces[b / 2]
        if (a / 2 == b / 2 || pa.tile == pb.tile) continue
        // Richtung: Ende trifft Anfang; bei Einbahn nur so.
        if (pa.oneway && (a % 2 == b % 2)) continue
        partner[a] = b
        partner[b] = a
    }
    val visited = BooleanArray(pieces.size)
    val out = ArrayList<Pair<List<Int>, DoubleArray>>()
    fun walk(startPiece: Int, startForward: Boolean) {
        val ids = ArrayList<Int>()
        val pts = DoubleBuilder(64)
        var piece = startPiece
        var forward = startForward
        while (true) {
            visited[piece] = true
            ids += piece
            val arr = pieces[piece].points
            val n = arr.size / 2
            for (k in 0 until n) {
                val idx = if (forward) k else n - 1 - k
                val x = arr[2 * idx]
                val y = arr[2 * idx + 1]
                if (pts.size >= 2 && pts.lastX() == x && pts.lastY() == y) continue
                pts.add(x, y)
            }
            val exitEnd = 2 * piece + if (forward) 1 else 0
            val next = partner[exitEnd]
            if (next < 0 || visited[next / 2]) break
            piece = next / 2
            forward = next % 2 == 0
        }
        out += ids to pts.toArray()
    }
    // zuerst Zuege mit offenem Anfang, dann Ringe (alles noch nicht Besuchte)
    for (i in pieces.indices) {
        if (visited[i]) continue
        when {
            partner[2 * i] < 0 -> walk(i, true)
            partner[2 * i + 1] < 0 -> walk(i, false)
        }
    }
    for (i in pieces.indices) if (!visited[i]) walk(i, true)
    return out
}

/** Wachsende Folge von Doubles (Punktpaare). */
internal class DoubleBuilder(capacity: Int) {
    private var data = DoubleArray(maxOf(capacity, 4))
    var size = 0
        private set

    fun add(x: Double, y: Double) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    fun lastX(): Double = data[size - 2]

    fun lastY(): Double = data[size - 1]

    fun toArray(): DoubleArray = data.copyOf(size)
}
