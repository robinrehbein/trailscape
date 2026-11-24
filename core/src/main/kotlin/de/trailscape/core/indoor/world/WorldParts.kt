package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.sampleAt
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Die Naht zwischen Weltbau-Rahmen und Erzeugern
 *
 * [WorldBuilderImpl] fuehrt Geodaten, Strecke und Kachelbau zusammen, weiss
 * aber nicht, **wie** das Streckenband eingepasst, der Boden eingeebnet, die
 * Landnutzung gerastert, die Kacheln geplant oder ihr Inhalt erzeugt wird.
 * Das liefern die [WorldParts]. [SimpleWorldParts] ist eine schlichte, aber
 * vollstaendige Umsetzung (Tests, Rueckfall); die echten Erzeuger stecken
 * hinter derselben Schnittstelle.
 */

/** Eine geplante Kachel mit den Laufmeter-Bereichen, in denen sie gebraucht wird. */
internal data class PlannedTile(val spec: TileSpec, val show: List<ShowInterval>)

/** Die Erzeuger, die der Weltbau-Rahmen aufruft. Alle Funktionen sind deterministisch. */
internal interface WorldParts {
    /**
     * Ursprung der Welt. Breite und Laenge duerfen **nur** von der Geometrie
     * der Strecke abhaengen (der Rahmen plant die Kacheln vor dem Laden der
     * Hoehen mit einem vorlaeufigen Ursprung), die Hoehe kommt aus
     * [heightAtLatLon] (Meter ueber dem Meer).
     */
    fun origin(route: IndoorRoute, heightAtLatLon: (Double, Double) -> Double): GeoOrigin

    /**
     * Das Streckenband: Proben alle `stepM` bei **unveraenderten** Laufmetern,
     * `u` = Fahrbahnoberflaeche. [rawHeightAt] ist das rohe Gelaende (ENU-`u`),
     * [isBridge] meldet Laufmeter auf Bruecken (dort folgt die Fahrbahn nicht
     * dem Gelaende).
     */
    fun fitTrack(
        route: IndoorRoute,
        origin: GeoOrigin,
        rawHeightAt: (e: Double, n: Double) -> Double,
        isBridge: (distM: Double) -> Boolean,
    ): WorldTrack

    /**
     * Der endgueltige Boden (fuer alle Schichten derselbe). [land] ist die
     * Landnutzung aus [landMap] (Wasserflaechen senken den Boden ab), [quality]
     * bestimmt die Zellenkante des feinsten Gelaendenetzes.
     */
    fun ground(
        rawHeightAt: (Double, Double) -> Double,
        track: TrackLine,
        map: MapData,
        land: LandMap,
        quality: IndoorQuality,
    ): GroundSurface

    /**
     * Die Landnutzung. [aerialOf] liefert das dekodierte Luftbild einer
     * Kachel (Norden oben, deckt genau das Kachelrechteck) oder `null`; es
     * ist je Kachel zwischengespeichert, jeder Aufruf kann aber einen Abruf
     * ausloesen.
     */
    fun landMap(map: MapData, track: TrackLine, aerialOf: (TileSpec) -> RgbaImage?): LandMap

    /**
     * Welche Kacheln die Welt hat und wann sie gebraucht werden. Muss
     * lueckenlos sein (Regeln in [checkWorld]) und die Grenzen der
     * [IndoorQuality] einhalten; der Rahmen rechnet nach dem Bau nach.
     */
    fun planTiles(track: TrackLine, loop: Boolean, quality: IndoorQuality): List<PlannedTile>

    /** Stabile Welt-ID: dieselbe Strecke und Stufe ergeben dieselbe ID. */
    fun worldId(route: IndoorRoute, quality: IndoorQuality): String

    /** Die Schichten einer Kachel der Stufe [level], in Aufrufreihenfolge. */
    fun layers(level: Int): List<TileLayer>

    /** Hoehe des Gelaendenetzes der Kachel, wenn es kein regelmaessiges Gitter ist ([TileContext.meshHeight]). */
    fun terrainMesh(ctx: TileContext): ((Double, Double) -> Double)? = null

    /** Die Kachel ist geschrieben; Zwischenstaende duerfen weg. */
    fun tileDone(spec: TileSpec) = Unit
}

// ---------------------------------------------------------------------------
// Planung der Kacheln (allgemein, auch fuer andere Umsetzungen nutzbar)
// ---------------------------------------------------------------------------

/**
 * Ringe der Kachelplanung (docs/indoor.md 7.2/7.4).
 *
 * @property corridorM Stufe 0: seitlicher Korridor um die Strecke.
 * @property backM Stufe 0: so weit hinter dem Fahrer bleibt der Korridor geladen.
 * @property aheadM Stufe 0: so weit voraus ist der Korridor geladen.
 * @property leadM Vorlauf aller Stufen (die Darstellung laedt asynchron).
 * @property level1M Stufe 1: alle Kacheln in diesem Umkreis um den Fahrer.
 * @property level2M Stufe 2: alle Kacheln in diesem Umkreis (Sichtweite).
 */
internal data class PlanRings(
    val corridorM: Double,
    val backM: Double,
    val aheadM: Double,
    val leadM: Double,
    val level1M: Double,
    val level2M: Double,
) {
    /**
     * Die Ringe so angehoben, dass jede gezeigte Kachel ihre Eltern-Kachel
     * gezeigt hat (sonst lege die Grosseltern-Teilflaeche unter der feinen
     * Kachel, siehe [checkWorld]).
     */
    fun consistent(stepM: Double): PlanRings {
        val l1 = max(level1M, corridorM + aheadM + leadM + stepM + PARENT_MARGIN_M)
        val l2 = max(level2M, l1 + leadM + 2 * stepM + PARENT_MARGIN_M)
        return copy(level1M = l1, level2M = l2)
    }

    companion object {
        /** Sicherheitsabstand der Eltern-Regel gegen Abtastfehler. */
        const val PARENT_MARGIN_M: Double = 50.0

        /** Richtwerte aus docs/indoor.md 7.4. */
        fun default(quality: IndoorQuality) = PlanRings(
            corridorM = 250.0,
            backM = 250.0,
            aheadM = 700.0,
            leadM = 80.0,
            level1M = 3_000.0,
            level2M = quality.viewDistanceM,
        )
    }
}

/**
 * Rechnet die Kacheln aller Stufen samt `show` aus den Ringen.
 *
 *  * Stufe 0: Eine Kachel wird an der Stelle `d` gebraucht, wenn sie naeher
 *    als [PlanRings.corridorM] an einer Stelle `s` der Strecke liegt mit
 *    `s ∈ [d − backM, d + aheadM + leadM]`.
 *  * Stufe 1 und 2: Die Kachel liegt naeher als der Ring am Fahrer (mit
 *    [PlanRings.leadM] Vorlauf).
 *
 * Gerechnet wird an den Proben des Bands; jeder Bereich wird um eine Probe
 * erweitert, damit zwischen den Proben nichts fehlt. Auf einer Runde werden
 * Bereiche ueber den Start hinweg geteilt.
 */
internal fun planTileShows(track: TrackLine, loop: Boolean, rings: PlanRings): List<PlannedTile> {
    val t = track.track
    val step = t.stepM
    val len = t.lengthM
    val radius = doubleArrayOf(rings.corridorM, rings.level1M, rings.level2M)
    val pre = doubleArrayOf(rings.aheadM + rings.leadM + step, rings.leadM + step, rings.leadM + step)
    val post = doubleArrayOf(rings.backM + step, step, step)
    val out = ArrayList<PlannedTile>()
    for (level in 0 until WORLD_LEVEL_COUNT) {
        val c = worldCellM(level)
        val r = radius[level]
        // je Kachel die Probenindizes in aufsteigender Reihenfolge
        val hits = LinkedHashMap<TileSpec, MutableList<Int>>()
        for (i in 0 until t.sampleCount) {
            val e = t.e[i].toDouble()
            val n = t.n[i].toDouble()
            val x0 = floor((e - r) / c).toInt()
            val x1 = floor((e + r) / c).toInt()
            val y0 = floor((n - r) / c).toInt()
            val y1 = floor((n + r) / c).toInt()
            for (y in y0..y1) for (x in x0..x1) {
                val spec = TileSpec(level, x, y)
                if (spec.distanceTo(e, n) <= r) hits.getOrPut(spec) { ArrayList() }.add(i)
            }
        }
        for ((spec, idx) in hits) {
            val raw = ArrayList<ShowInterval>()
            var start = idx[0]
            for (k in 1..idx.size) {
                if (k == idx.size || idx[k] != idx[k - 1] + 1) {
                    raw += ShowInterval(t.distAt(start) - pre[level], t.distAt(idx[k - 1]) + post[level])
                    if (k < idx.size) start = idx[k]
                }
            }
            out += PlannedTile(spec, foldIntervals(raw, len, loop))
        }
    }
    return out.sortedWith(compareBy({ it.spec.level }, { it.spec.iy }, { it.spec.ix }))
}

/**
 * Bringt Bereiche in `[0, lengthM]`: auf einer Runde ueber den Start hinweg
 * geteilt, sonst beschnitten; danach sortiert und zusammengefasst.
 */
internal fun foldIntervals(raw: List<ShowInterval>, lengthM: Double, loop: Boolean): List<ShowInterval> {
    val parts = ArrayList<ShowInterval>()
    for (iv in raw) {
        if (loop) {
            if (iv.toM - iv.fromM >= lengthM) {
                parts += ShowInterval(0.0, lengthM)
                continue
            }
            var a = iv.fromM
            var b = iv.toM
            if (a < 0) {
                parts += ShowInterval(a + lengthM, lengthM)
                a = 0.0
            }
            if (b > lengthM) {
                parts += ShowInterval(0.0, b - lengthM)
                b = lengthM
            }
            if (b >= a) parts += ShowInterval(a, b)
        } else {
            val a = iv.fromM.coerceIn(0.0, lengthM)
            val b = iv.toM.coerceIn(0.0, lengthM)
            if (b >= a) parts += ShowInterval(a, b)
        }
    }
    parts.sortBy { it.fromM }
    val merged = ArrayList<ShowInterval>()
    for (p in parts) {
        val last = merged.lastOrNull()
        if (last != null && p.fromM <= last.toM) {
            merged[merged.size - 1] = ShowInterval(last.fromM, max(last.toM, p.toM))
        } else {
            merged += p
        }
    }
    return merged
}

/**
 * Grobe Kosten einer Planung: groesste Summe an Texturbytes und Dreiecken an
 * irgendeiner Stelle, mit geschaetzten Werten je Stufe.
 */
internal fun estimateBudget(
    tiles: List<PlannedTile>,
    lengthM: Double,
    perTile: (level: Int) -> Pair<Long, Int>,
): Pair<Long, Int> {
    var maxTex = 0L
    var maxTri = 0
    for (d in budgetPositions(lengthM)) {
        var tex = 0L
        var tri = 0
        for (t in tiles) {
            if (t.show.any { d in it }) {
                val (x, y) = perTile(t.spec.level)
                tex += x
                tri += y
            }
        }
        maxTex = max(maxTex, tex)
        maxTri = max(maxTri, tri)
    }
    return maxTex to maxTri
}

/**
 * Plant mit den Richtwerten und verkleinert die Ringe schrittweise (erst
 * Stufe 2, dann Stufe 1, dann den Korridor), bis die geschaetzten Kosten
 * hoechstens [fill] der Grenzen der [quality] erreichen.
 */
internal fun planWithinBudget(
    track: TrackLine,
    loop: Boolean,
    quality: IndoorQuality,
    perTile: (level: Int) -> Pair<Long, Int>,
    fill: Double = 0.9,
): List<PlannedTile> {
    var rings = PlanRings.default(quality).consistent(track.stepM)
    repeat(40) {
        val tiles = planTileShows(track, loop, rings)
        val (tex, tri) = estimateBudget(tiles, track.lengthM, perTile)
        if (tex <= quality.maxTextureBytes * fill && tri <= quality.maxTriangles * fill) return tiles
        val minL2 = rings.level1M + rings.leadM + 2 * track.stepM + PlanRings.PARENT_MARGIN_M
        val minL1 = rings.corridorM + rings.aheadM + rings.leadM + track.stepM + PlanRings.PARENT_MARGIN_M
        rings = when {
            rings.level2M * 0.9 >= minL2 -> rings.copy(level2M = rings.level2M * 0.9)
            rings.level1M * 0.9 >= max(minL1, 1_500.0) -> rings.copy(level1M = rings.level1M * 0.9)
            rings.aheadM > 400.0 || rings.corridorM > 150.0 -> rings.copy(
                aheadM = max(400.0, rings.aheadM * 0.85),
                corridorM = max(150.0, rings.corridorM * 0.85),
            )
            else -> return tiles
        }.consistent(track.stepM)
    }
    return planTileShows(track, loop, rings)
}

/** Stellen, an denen das Budget nachgerechnet wird: alle 10 m und das Ende. */
internal fun budgetPositions(lengthM: Double): DoubleArray {
    val n = floor(lengthM / BUDGET_STEP_M).toInt()
    val out = ArrayList<Double>(n + 2)
    for (k in 0..n) out += k * BUDGET_STEP_M
    if (out.last() < lengthM) out += lengthM
    return out.toDoubleArray()
}

/** Abstand der Stellen, an denen Budget und Lueckenlosigkeit geprueft werden. */
internal const val BUDGET_STEP_M: Double = 10.0

// ---------------------------------------------------------------------------
// Die einfache Umsetzung
// ---------------------------------------------------------------------------

/**
 * Schlichte, vollstaendige [WorldParts]: Band bei unveraenderter Lage mit
 * geglaettetem Gelaende als Hoehe, Boden entlang der Strecke eingeebnet,
 * Landnutzung ueber Punkt-in-Polygon, Planung nach 7.2/7.4, als Inhalt nur
 * Gelaende (Luftbild oder erzeugte Bodentextur) und ein Fahrbahnband.
 */
internal object SimpleWorldParts : WorldParts {
    /** Fenster der Hoehenglaettung des Bands (± Proben). */
    private const val SMOOTH_SAMPLES = 6

    /** Steigung, die das Band hoechstens bekommt (etwas unter 25 % wegen Rundung). */
    private const val MAX_GRADE = 0.24

    /** Strecke, ueber die eine Runde am Ende auf den Start zugefuehrt wird. */
    private const val LOOP_CLOSE_BLEND_M = 200.0

    override fun origin(route: IndoorRoute, heightAtLatLon: (Double, Double) -> Double): GeoOrigin {
        val lat = (route.points.minOf { it.lat } + route.points.maxOf { it.lat }) / 2
        val lon = (route.points.minOf { it.lon } + route.points.maxOf { it.lon }) / 2
        val first = route.points.first()
        return GeoOrigin(lat, lon, heightAtLatLon(first.lat, first.lon))
    }

    override fun fitTrack(
        route: IndoorRoute,
        origin: GeoOrigin,
        rawHeightAt: (e: Double, n: Double) -> Double,
        isBridge: (distM: Double) -> Boolean,
    ): WorldTrack {
        val step = WorldTrack.DEFAULT_STEP_M
        val len = route.lengthM
        val count = ceil(len / step - 1e-9).toInt() + 1
        val open = route.copy(isLoop = false)
        val e = DoubleArray(count)
        val n = DoubleArray(count)
        for (i in 0 until count) {
            val s = open.sampleAt(min(i * step, len))
            e[i] = origin.toEast(s.lon)
            n[i] = origin.toNorth(s.lat)
        }
        val loop = route.isLoop
        if (loop) {
            // Runde schliessen: die Luecke Ziel → Start ueber das letzte Stueck verteilen.
            val ge = e[count - 1] - e[0]
            val gn = n[count - 1] - n[0]
            val blend = min(LOOP_CLOSE_BLEND_M, len / 4)
            for (i in 0 until count) {
                val w = smoothstep(len - blend, len, min(i * step, len))
                e[i] -= ge * w
                n[i] -= gn * w
            }
            e[count - 1] = e[0]
            n[count - 1] = n[0]
        }
        // Rohes Gelaende, auf Bruecken geradlinig ueberbrueckt.
        val raw = DoubleArray(count) { rawHeightAt(e[it], n[it]) }
        val bridge = BooleanArray(count) { isBridge(min(it * step, len)) }
        var i = 0
        while (i < count) {
            if (!bridge[i]) { i++; continue }
            var j = i
            while (j < count && bridge[j]) j++
            val a = if (i > 0) raw[i - 1] else if (j < count) raw[j] else raw[i]
            val b = if (j < count) raw[j] else a
            for (k in i until j) raw[k] = a + (b - a) * (k - i + 1) / (j - i + 1).toDouble()
            i = j
        }
        // Glaetten (auf der Runde ringfoermig) und Steigung begrenzen.
        val ring = if (loop) count - 1 else count
        val u = DoubleArray(count)
        for (k in 0 until ring) {
            var s = 0.0
            for (o in -SMOOTH_SAMPLES..SMOOTH_SAMPLES) {
                val j = if (loop) Math.floorMod(k + o, ring) else (k + o).coerceIn(0, count - 1)
                s += raw[j]
            }
            u[k] = s / (2 * SMOOTH_SAMPLES + 1)
        }
        if (loop) u[count - 1] = u[0]
        val dist = DoubleArray(count) { min(it * step, len) }
        repeat(200) {
            var changed = false
            val segs = if (loop) ring else count - 1
            for (k in 0 until segs) {
                val a = k
                val b = if (loop && k + 1 == ring) 0 else k + 1
                val span = if (loop && k + 1 == ring) len - dist[k] else dist[k + 1] - dist[k]
                val lim = MAX_GRADE * span
                val du = u[b] - u[a]
                if (du > lim || du < -lim) {
                    val excess = (if (du > 0) du - lim else du + lim) / 2
                    u[a] += excess
                    u[b] -= excess
                    changed = true
                }
            }
            if (loop) u[count - 1] = u[0]
            if (!changed) return@repeat
        }
        return WorldTrack(
            lengthM = len,
            stepM = step,
            loop = loop,
            e = FloatArray(count) { e[it].toFloat() },
            n = FloatArray(count) { n[it].toFloat() },
            u = FloatArray(count) { u[it].toFloat() },
            surfaces = route.surfaces,
            source = route.source,
        )
    }

    override fun ground(
        rawHeightAt: (Double, Double) -> Double,
        track: TrackLine,
        map: MapData,
        land: LandMap,
        quality: IndoorQuality,
    ): GroundSurface =
        GroundSurface { e, n ->
            val h = rawHeightAt(e, n)
            val hit = track.nearest(e, n, 40.0) ?: return@GroundSurface h
            val hw = track.halfWidthAt(hit.distM)
            val t = smoothstep(hw + 1.5, hw + 13.5, hit.distance)
            val road = hit.u - 0.12
            road + (h - road) * t
        }

    override fun landMap(map: MapData, track: TrackLine, aerialOf: (TileSpec) -> RgbaImage?): LandMap = SimpleLandMap(map)

    override fun planTiles(track: TrackLine, loop: Boolean, quality: IndoorQuality): List<PlannedTile> =
        planWithinBudget(track, loop, quality, perTile = { level -> estimateTile(quality, level) })

    override fun worldId(route: IndoorRoute, quality: IndoorQuality): String {
        val sb = StringBuilder()
        sb.append(WORLD_BUILDER_ID).append('|').append(quality.name).append('|').append(route.id).append('|')
            .append(route.isLoop).append('|').append(route.source.name)
        for (p in route.points) {
            sb.append(';').append(Math.round(p.distM * 10)).append(',').append(Math.round(p.lat * 1e6))
                .append(',').append(Math.round(p.lon * 1e6))
        }
        for (s in route.surfaces) sb.append('|').append(Math.round(s.fromM)).append(s.kind.name).append(s.highway).append(s.widthM)
        return "w" + sha256Hex(sb.toString()).substring(0, 16)
    }

    override fun layers(level: Int): List<TileLayer> =
        if (level == 0) listOf(SimpleTerrainLayer, SimpleRouteLayer) else listOf(SimpleTerrainLayer)

    /** Geschaetzte Texturbytes und Dreiecke einer Kachel dieser Umsetzung. */
    fun estimateTile(quality: IndoorQuality, level: Int): Pair<Long, Int> {
        val n = terrainCells(TileBudget.of(quality, level).terrain, level)
        val skirts = if (level == 0) 8 * n else 32 * n
        val route = if (level == 0) 600 else 0
        return textureGpuBytes(quality.texturePx(level)) to (2 * n * n + skirts + route)
    }

    /** Gitterzellen je Kachelkante, so dass Gitter und Schuerzen ins Budget passen. */
    fun terrainCells(budget: Int, level: Int): Int {
        // Stufe 0: 2N² + 8N ≤ b; ab Stufe 1 (Schuerze je Teilflaeche): 2N² + 32N ≤ b.
        val k = if (level == 0) 8.0 else 32.0
        val n = ((-k + sqrt(k * k + 8.0 * budget)) / 4).toInt()
        val rounded = if (level == 0) n else n / WORLD_LEVEL_FACTOR * WORLD_LEVEL_FACTOR
        return max(if (level == 0) 8 else 2 * WORLD_LEVEL_FACTOR, rounded)
    }
}

/** Kennung und Version des Weltbaus (Manifest-Feld `builder`). */
internal const val WORLD_BUILDER_VERSION: Int = 1

// ---------------------------------------------------------------------------
// Landnutzung ueber Punkt-in-Polygon
// ---------------------------------------------------------------------------

/**
 * Landnutzung aus [MapData]: je 256-m-Zelle die beruehrenden Flaechen
 * (einmal abgefragt, threadsicher zwischengespeichert), an einer Stelle
 * gewinnt die genaueste Klasse (Wasser vor Wald vor Wiese vor Wohngebiet).
 */
internal class SimpleLandMap(private val map: MapData) : LandMap {
    private val cells = ConcurrentHashMap<Long, List<MapArea>>()

    override fun classAt(e: Double, n: Double): LandClass = areaAt(e, n)?.kind ?: LandClass.MEADOW

    override fun coniferShare(e: Double, n: Double): Double {
        val src = areaAt(e, n)?.sourceClass?.lowercase() ?: return 0.3
        return when {
            "needle" in src || "conifer" in src -> 1.0
            "broad" in src -> 0.0
            else -> 0.3
        }
    }

    private fun areaAt(e: Double, n: Double): MapArea? {
        val cx = floor(e / CELL_M).toInt()
        val cy = floor(n / CELL_M).toInt()
        val key = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
        val list = cells.computeIfAbsent(key) {
            map.areas(cx * CELL_M, cy * CELL_M, (cx + 1) * CELL_M, (cy + 1) * CELL_M)
                .sortedBy { PRIORITY.indexOf(it.kind) }
        }
        for (a in list) if (pointInRings(a.rings, e, n)) return a
        return null
    }

    companion object {
        private const val CELL_M = 256.0

        /** Genauere Klassen zuerst: Landbedeckung schlaegt allgemeine Landnutzung. */
        val PRIORITY = listOf(
            LandClass.WATER, LandClass.WETLAND, LandClass.FOREST, LandClass.PARK, LandClass.BARE,
            LandClass.SCRUB, LandClass.FARMLAND, LandClass.MEADOW, LandClass.INDUSTRIAL, LandClass.RESIDENTIAL,
        )
    }
}

// ---------------------------------------------------------------------------
// Einfache Schichten
// ---------------------------------------------------------------------------

/**
 * Gelaendegitter mit Luftbild (CLAMP) oder aus der Landnutzung erzeugter
 * Bodentextur; ab Stufe 1 in 4×4 Teilflaechen, jede Flaeche mit Schuerze.
 */
internal object SimpleTerrainLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        val s = ctx.spec
        val cells = SimpleWorldParts.terrainCells(ctx.budget.terrain, s.level)
        val texture = ctx.aerialTexture ?: groundTexture(ctx)
        val material = TileMaterial(WorldNames.MAT_TERRAIN, texture = texture, repeat = false, roughness = 0.95f)
        val step = s.cellM / cells
        val heights = DoubleArray((cells + 1) * (cells + 1))
        for (j in 0..cells) for (i in 0..cells) heights[j * (cells + 1) + i] = ctx.ground.heightAt(s.minE + i * step, s.minN + j * step)
        fun h(i: Int, j: Int): Double {
            if (i in 0..cells && j in 0..cells) return heights[j * (cells + 1) + i]
            return ctx.ground.heightAt(s.minE + i * step, s.minN + j * step)
        }
        val skirt = when (s.level) {
            0 -> 5.0
            1 -> 15.0
            else -> 30.0
        }
        val parts = if (s.level == 0) 1 else WORLD_LEVEL_FACTOR
        val per = cells / parts
        for (si in 0 until parts) for (sj in 0 until parts) {
            val i0 = si * per
            val j0 = sj * per
            val m = out.mesh(WorldNames.NODE_TERRAIN, material, s.minE + (i0 + per / 2.0) * step, s.minN + (j0 + per / 2.0) * step)
            val base = m.vertexCount
            fun vtx(i: Int, j: Int, down: Double = 0.0): Int {
                val e = s.minE + i * step
                val n = s.minN + j * step
                val de = (h(i + 1, j) - h(i - 1, j)) / (2 * step)
                val dn = (h(i, j + 1) - h(i, j - 1)) / (2 * step)
                val nor = Vec3(-de, 1.0, dn).normalized()
                return m.vertex(ctx.x(e), ctx.y(h(i, j) - down), ctx.z(n), nor.x, nor.y, nor.z, i / cells.toDouble(), 1.0 - j / cells.toDouble())
            }
            for (j in j0..j0 + per) for (i in i0..i0 + per) vtx(i, j)
            val w = per + 1
            for (j in 0 until per) for (i in 0 until per) {
                val a = base + j * w + i
                m.quad(a, a + 1, a + w + 1, a + w)
            }
            // Schuerze: Rand gegen den Uhrzeigersinn ablaufen (aussen rechts), Viereck (P', Q', Q, P).
            val ring = ArrayList<IntArray>()
            for (i in i0 until i0 + per) ring += intArrayOf(i, j0, i + 1, j0)
            for (j in j0 until j0 + per) ring += intArrayOf(i0 + per, j, i0 + per, j + 1)
            for (i in i0 + per downTo i0 + 1) ring += intArrayOf(i, j0 + per, i - 1, j0 + per)
            for (j in j0 + per downTo j0 + 1) ring += intArrayOf(i0, j, i0, j - 1)
            for (edge in ring) {
                val p = base + (edge[1] - j0) * w + (edge[0] - i0)
                val q = base + (edge[3] - j0) * w + (edge[2] - i0)
                val pd = vtx(edge[0], edge[1], skirt)
                val qd = vtx(edge[2], edge[3], skirt)
                m.quad(pd, qd, q, p)
            }
        }
    }

    /** Bodentextur aus der Landnutzung in derselben Aufloesung wie ein Luftbild, kodiert als Baseline-JPEG. */
    private fun groundTexture(ctx: TileContext): EncodedTexture {
        val s = ctx.spec
        val px = ctx.quality.texturePx(s.level)
        val grid = 64
        val classes = arrayOfNulls<LandClass>((grid + 1) * (grid + 1))
        for (j in 0..grid) for (i in 0..grid) {
            classes[j * (grid + 1) + i] = ctx.land.classAt(s.minE + s.cellM * i / grid, s.maxN - s.cellM * j / grid)
        }
        val img = RgbaImage(px, px)
        val mpp = s.cellM / px
        val seed = 0x5eed
        for (y in 0 until px) {
            val n = s.maxN - (y + 0.5) * mpp
            for (x in 0 until px) {
                val e = s.minE + (x + 0.5) * mpp
                // Klassengrenzen mit etwas Rauschen verwischen, damit kein Raster sichtbar wird.
                val jitter = (Noise.value(e / 9.0, n / 9.0, seed) - 0.5) * 1.2
                val gi = ((x + 0.5) / px * grid + jitter).toInt().coerceIn(0, grid)
                val gj = ((y + 0.5) / px * grid - jitter).toInt().coerceIn(0, grid)
                val base = GROUND_COLORS.getValue(classes[gj * (grid + 1) + gi]!!)
                val low = Noise.value(e / 60.0, n / 60.0, seed + 1)
                val fine = Noise.hash01(floor(e * 2).toInt(), floor(n * 2).toInt(), seed + 2)
                img.argb[y * px + x] = Argb.scale(base, 0.85 + 0.2 * low + 0.06 * (fine - 0.5))
            }
        }
        return EncodedTexture(encodeJpegBaseline(img, 85), "image/jpeg", px, px)
    }

    private val GROUND_COLORS: Map<LandClass, Int> = mapOf(
        LandClass.MEADOW to Argb.rgb(98, 114, 62),
        LandClass.FARMLAND to Argb.rgb(138, 124, 84),
        LandClass.FOREST to Argb.rgb(54, 66, 40),
        LandClass.SCRUB to Argb.rgb(86, 96, 58),
        LandClass.RESIDENTIAL to Argb.rgb(118, 116, 102),
        LandClass.INDUSTRIAL to Argb.rgb(126, 124, 120),
        LandClass.PARK to Argb.rgb(90, 116, 62),
        LandClass.WATER to Argb.rgb(60, 76, 82),
        LandClass.WETLAND to Argb.rgb(84, 96, 72),
        LandClass.BARE to Argb.rgb(148, 138, 118),
    )
}

/** Schlichtes Fahrbahnband (nur Stufe 0): Fahrbahn auf Bandhoehe, Bankett schraeg zum Boden. */
internal object SimpleRouteLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        val t = ctx.track.track
        val s = ctx.spec
        val last = t.sampleCount - 1
        // Ein Stueck gehoert der Kachel, in der seine Mitte liegt.
        fun inTile(k: Int): Boolean = s.contains((t.e[k] + t.e[k + 1]) / 2.0, (t.n[k] + t.n[k + 1]) / 2.0)
        var k = 0
        while (k < last) {
            if (!inTile(k)) { k++; continue }
            val kind = ctx.track.kindAt((t.distAt(k) + t.distAt(k + 1)) / 2)
            var end = k
            while (end < last && inTile(end) && ctx.track.kindAt((t.distAt(end) + t.distAt(end + 1)) / 2) == kind) end++
            strip(ctx, out, k, end, kind)
            k = end
        }
    }

    private fun strip(ctx: TileContext, out: TileOutput, from: Int, to: Int, kind: SurfaceKind) {
        val t = ctx.track.track
        val material = TileMaterial(
            WorldNames.MAT_ROUTE_PREFIX + kind.textureKey,
            baseColor = ROUTE_COLORS[kind] ?: floatArrayOf(0.5f, 0.5f, 0.5f, 1f),
            roughness = 0.9f,
        )
        val m = out.mesh(WorldNames.NODE_ROUTE, material)
        val base = m.vertexCount
        for (k in from..to) {
            val a = max(0, k - 1)
            val b = min(t.sampleCount - 1, k + 1)
            var fe = (t.e[b] - t.e[a]).toDouble()
            var fn = (t.n[b] - t.n[a]).toDouble()
            val l = hypot(fe, fn)
            if (l > 1e-6) { fe /= l; fn /= l } else { fe = 0.0; fn = 1.0 }
            val re = fn
            val rn = -fe
            val d = t.distAt(k)
            val hw = ctx.track.halfWidthAt(d)
            val e = t.e[k].toDouble()
            val n = t.n[k].toDouble()
            val u = t.u[k] + 0.02
            val offsets = doubleArrayOf(-hw - 0.6, -hw, hw, hw + 0.6)
            for ((c, o) in offsets.withIndex()) {
                val pe = e + re * o
                val pn = n + rn * o
                val pu = if (c == 0 || c == 3) min(u - 0.05, ctx.ground.heightAt(pe, pn) + 0.01) else u
                m.vertex(ctx.x(pe), ctx.y(pu), ctx.z(pn), 0.0, 1.0, 0.0, c / 3.0, d / 4.0)
            }
        }
        for (k in 0 until to - from) for (c in 0 until 3) {
            val a = base + k * 4 + c
            m.quad(a, a + 1, a + 5, a + 4)
        }
    }

    /** Belagsfarben in sRGB; glTF erwartet `baseColorFactor` linear. */
    private val ROUTE_COLORS: Map<SurfaceKind, FloatArray> = mapOf(
        SurfaceKind.ASPHALT to srgb(0.30, 0.30, 0.31),
        SurfaceKind.CONCRETE to srgb(0.62, 0.61, 0.58),
        SurfaceKind.PAVING_STONES to srgb(0.56, 0.52, 0.48),
        SurfaceKind.COBBLESTONE to srgb(0.46, 0.44, 0.41),
        SurfaceKind.WOOD to srgb(0.50, 0.39, 0.27),
        SurfaceKind.COMPACTED to srgb(0.66, 0.61, 0.52),
        SurfaceKind.GRAVEL to srgb(0.62, 0.60, 0.56),
        SurfaceKind.DIRT to srgb(0.44, 0.36, 0.27),
        SurfaceKind.GRASS to srgb(0.36, 0.45, 0.25),
        SurfaceKind.SAND to srgb(0.76, 0.70, 0.56),
    )

    private fun srgb(r: Double, g: Double, b: Double) =
        floatArrayOf(Argb.srgbToLinear(r).toFloat(), Argb.srgbToLinear(g).toFloat(), Argb.srgbToLinear(b).toFloat(), 1f)
}
