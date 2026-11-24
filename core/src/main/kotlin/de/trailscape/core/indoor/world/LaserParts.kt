package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.world.BuildingTextures.Band
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # Die Erzeuger mit Laserdaten
 *
 * Huelle um die bisherigen Erzeuger ([base], ueblich [FullWorldParts]), die
 * dort, wo [laser] Daten hat, die genaueren Quellen nimmt — sonst bleibt alles
 * wie ohne Laser:
 *
 *  * **Boden:** [CarvedGround] ueber dem ueberblendeten DGM1 mit kaum
 *    Daempfung (1 m statt 8 m) und dem 1-m-Netz als Zellenkante.
 *  * **Gelaende Stufe 0:** [LaserTerrainLayer] (adaptives Netz im bisherigen
 *    Dreiecksbudget); Stufe 1/2 tasten den Boden wie bisher regelmaessig ab.
 *  * **Bewuchs:** Kronenbedeckung, Kronenhoehe und Einzelbaeume aus dem nDOM
 *    ([LandRaster.laser]); die Baumschicht selbst bleibt [TreesLayer].
 *  * **Gebaeude Stufe 0:** LoD2-Geometrie ([Lod2Mesher]) fuer alle Gebaeude mit
 *    LoD2, nach Abstand zur Strecke bis zum Budget; was keinen Platz hat oder
 *    keine LoD2-Daten hat, baut [BuildingsLayer] aus OSM mit der LoD2-Hoehe.
 *  * **Gebaeude Stufe 1 und ohne LoD2:** [BuildingsLayer] aus OSM mit der
 *    gemessenen Hoehe — `measuredHeight` des LoD2-Gebaeudes unter dem
 *    OSM-Schwerpunkt, sonst das 90. Perzentil des nDOM im Grundriss (die
 *    Zwischenstufe „Hoehe aus Laserdaten ueber dem OSM-Grundriss").
 */
internal class LaserWorldParts(
    private val base: WorldParts,
    val laser: LaserData,
) : WorldParts by base {

    private val meshes = ConcurrentHashMap<TileSpec, Holder>()
    private val trees = ConcurrentHashMap<TileSpec, List<LaserTree>>()
    private val lod2Index = FootprintIndex(laser.buildings)

    private class Holder(val mesh: LaserTerrainMesh?)

    override fun ground(
        rawHeightAt: (Double, Double) -> Double,
        track: TrackLine,
        map: MapData,
        land: LandMap,
        quality: IndoorQuality,
    ): GroundSurface = CarvedGround(
        raw = RawHeights { e, n -> rawHeightAt(e, n) },
        track = track,
        map = map,
        terrainCellM = 1.0,
        water = land as? WaterMask,
        bridges = trackBridges(track, map),
        dampM = 1.0,
    )

    override fun landMap(map: MapData, track: TrackLine, aerialOf: (TileSpec) -> RgbaImage?): LandMap {
        val land = base.landMap(map, track, aerialOf)
        // Gebaeude aus dem Kronenmodell nehmen: OSM-Grundrisse und LoD2.
        synchronized(this) {
            if (!masked) {
                masked = true
                val b = laser.bounds()
                val osm = if (b != null) map.buildings(b[0], b[1], b[2], b[3]) else emptyList()
                // Zwischenstufe: Hoehe aus dem nDOM ueber dem OSM-Grundriss — vor dem Ausblenden messen.
                for (o in osm) ndomHeightOf(o)?.let { ndomHeights[footKey(o)] = it }
                laser.maskBuildings(osm.flatMap { it.rings.take(1) }, 1.0)
                laser.maskBuildings(laser.buildings.flatMap { it.footprint }, 1.0)
            }
        }
        if (land is LandRaster) {
            land.laser = LaserVegetation(
                canopy = { e, n -> if (laser.fullAt(e, n)) laser.canopyAt(e, n) else Double.NaN },
                canopyHeight = { e, n -> if (laser.fullAt(e, n)) laser.canopyHeightAt(e, n) else Double.NaN },
                trees = { spec -> treesOf(spec) },
            )
        }
        return land
    }

    @Volatile private var masked = false
    private val ndomHeights = ConcurrentHashMap<Long, Double>()

    private fun footKey(b: MapBuilding): Long {
        val c = FootprintShape.centroid(b.rings.first())
        return LaserData.key(Math.round(c[0] * 4).toInt(), Math.round(c[1] * 4).toInt())
    }

    /** Hoehe aus dem nDOM (Zwischenstufe ohne LoD2), falls gemessen. */
    fun ndomHeight(b: MapBuilding): Double? = if (b.rings.isEmpty()) null else ndomHeights[footKey(b)]

    /** 90. Perzentil des nDOM auf dem 1-m-Gitter im Grundriss; `null` bei zu wenig Daten oder unter 2,5 m. */
    private fun ndomHeightOf(b: MapBuilding): Double? {
        val r = b.rings.firstOrNull() ?: return null
        if (r.size < 6) return null
        var minE = Double.MAX_VALUE; var minN = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (i in r.indices step 2) { minE = min(minE, r[i]); maxE = max(maxE, r[i]); minN = min(minN, r[i + 1]); maxN = max(maxN, r[i + 1]) }
        val vals = ArrayList<Double>()
        var inside = 0
        for (y in floor(minN).toInt()..floor(maxN).toInt()) for (x in floor(minE).toInt()..floor(maxE).toInt()) {
            if (!pointInRings(listOf(r), x.toDouble(), y.toDouble())) continue
            inside++
            val v = laser.ndomAt(x, y)
            if (!v.isNaN()) vals += v
        }
        if (vals.size < 8 || vals.size < inside * 0.6) return null
        vals.sort()
        val h = vals[(vals.size * 0.9).toInt().coerceAtMost(vals.size - 1)]
        return if (h >= 2.5) h else null
    }

    private fun treesOf(spec: TileSpec): List<LaserTree>? {
        if (spec.level != 0 || !laser.hasBlock(spec.ix, spec.iy)) return null
        return trees.getOrPut(spec) {
            laser.detectTrees(spec.minE, spec.minN, spec.maxE, spec.maxN).filter { laser.fullAt(it.e, it.n) }
        }
    }

    override fun terrainMesh(ctx: TileContext): ((Double, Double) -> Double)? {
        val m = meshOf(ctx) ?: return null
        return m::heightAt
    }

    private fun meshOf(ctx: TileContext): LaserTerrainMesh? {
        val s = ctx.spec
        if (s.level != 0 || !laser.hasBlock(s.ix, s.iy)) return null
        return meshes.getOrPut(s) {
            Holder(
                LaserTerrainMesh.build(ctx.ground, s.minE, s.minN, ctx.budget.terrain) { e, n ->
                    val hit = ctx.track.nearest(e, n, 12.0)
                    hit != null && hit.distance <= ctx.track.halfWidthAt(hit.distM) + CarvedGround.TRACK_SHOULDER_M + 0.3
                },
            )
        }.mesh
    }

    /** Netz freigeben, sobald die Kachel geschrieben ist (sonst 260 kB je Kachel im Speicher). */
    override fun tileDone(spec: TileSpec) {
        meshes.remove(spec)
        trees.remove(spec)
    }

    private val layers0: List<TileLayer> = base.layers(0).map { layer ->
        when (layer) {
            is TerrainLayer -> LaserTerrainLayer(::meshOf, layer)
            is BuildingsLayer -> LaserBuildingsLayer(this, layer)
            else -> layer
        }
    }
    private val layers1: List<TileLayer> = base.layers(1).map { layer ->
        if (layer is BuildingsLayer) LaserBuildingsLayer(this, layer) else layer
    }

    override fun layers(level: Int): List<TileLayer> = when (level) {
        0 -> layers0
        1 -> layers1
        else -> base.layers(level)
    }

    override fun origin(route: IndoorRoute, heightAtLatLon: (Double, Double) -> Double): GeoOrigin = base.origin(route, heightAtLatLon)

    /** Die LoD2-Gebaeude mit Schwerpunkt im Rechteck. */
    fun lod2In(minE: Double, minN: Double, maxE: Double, maxN: Double): List<LaserBuilding> = lod2Index.query(minE, minN, maxE, maxN)
        .filter { it.ce >= minE && it.ce < maxE && it.cn >= minN && it.cn < maxN }

    /** Das LoD2-Gebaeude, in dessen Grundriss (`e`, `n`) liegt. */
    fun lod2At(e: Double, n: Double): LaserBuilding? = lod2Index.query(e - 1, n - 1, e + 1, n + 1)
        .firstOrNull { b -> b.footprint.any { pointInRings(listOf(it), e, n) } }
}

/** Was die Baumschicht aus dem nDOM erfaehrt ([LandRaster.laser]). Jede Funktion liefert `NaN`/`null`, wo es keine Daten gibt. */
internal class LaserVegetation(
    val canopy: (Double, Double) -> Double,
    val canopyHeight: (Double, Double) -> Double,
    val trees: (TileSpec) -> List<LaserTree>?,
)

/**
 * Ein Baum aus dem nDOM als [TreeModels.Spec]: Farbe und Zufall wie bisher
 * ([base]), Hoehe und Kronenbreite gemessen, die Art aus Hoehe und
 * Kronenform: schmale Krone (Radius unter 13 % der Hoehe) wird in Nadel- und
 * Mischwald ([conifer] ≥ 0,5) zur Fichte, sonst zur schlanken Pappelform.
 * Die Kronenradien der Modelle sind etwa `k · Hoehe · width` (k je Art aus
 * [TreeModels]); daraus der Breitenfaktor.
 *
 * [roomM] ist der Abstand des Stamms vom Fahrbahnrand: Kronen, die tiefer als
 * 4,5 m ansetzen (Fichte, Pappel, kleiner Baum), werden so schmal, dass sie
 * nicht ueber die Fahrbahn ragen — sonst stuende die Kamera in der Krone.
 * `null`, wenn der Baum dafuer zu schmal wuerde oder unter 3 m ist.
 */
internal fun laserTreeSpec(base: TreeModels.Spec, t: LaserTree, roomM: Double = Double.MAX_VALUE, conifer: Double = 0.3): TreeModels.Spec? {
    val h = t.heightM.coerceAtMost(42.0)
    if (h < LaserData.TREE_MIN_HEIGHT_M) return null
    val slim = t.crownRadiusM / h < 0.13
    var species = when {
        slim && conifer >= 0.5 -> TreeModels.Species.CONIFER
        slim -> TreeModels.Species.TALL
        base.species == TreeModels.Species.CONIFER && conifer >= 0.5 -> TreeModels.Species.CONIFER
        else -> TreeModels.Species.BROAD
    }
    if (h < 7.0 && species != TreeModels.Species.CONIFER) species = TreeModels.Species.SMALL
    val k = when (species) {
        TreeModels.Species.BROAD -> 0.32
        TreeModels.Species.TALL -> 0.165
        TreeModels.Species.CONIFER -> 0.22
        TreeModels.Species.SMALL -> 0.37
    }
    var width = (t.crownRadiusM / (k * h)).coerceIn(0.6, 1.5)
    val lowCrown = species != TreeModels.Species.BROAD || 0.3 * h < 4.5
    if (lowCrown && k * h * width > roomM + 0.3) {
        width = (roomM + 0.3) / (k * h)
        if (width < 0.5) return null
    }
    return TreeModels.Spec(species, h, width, base.tint, base.seed, base.turn, base.pale)
}

/** Raster der LoD2-Grundrisse (64-m-Zellen) fuer schnelle Rechteckabfragen. */
private class FootprintIndex(list: List<LaserBuilding>) {
    private val cell = 64.0
    private val grid = HashMap<Long, MutableList<LaserBuilding>>()

    init {
        for (b in list) {
            var minE = Double.MAX_VALUE; var minN = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
            for (r in b.footprint) for (i in r.indices step 2) {
                minE = min(minE, r[i]); maxE = max(maxE, r[i]); minN = min(minN, r[i + 1]); maxN = max(maxN, r[i + 1])
            }
            if (minE > maxE) { minE = b.ce; maxE = b.ce; minN = b.cn; maxN = b.cn }
            for (x in floor(minE / cell).toInt()..floor(maxE / cell).toInt()) for (y in floor(minN / cell).toInt()..floor(maxN / cell).toInt()) {
                grid.getOrPut(LaserData.key(x, y)) { ArrayList() }.add(b)
            }
        }
    }

    fun query(minE: Double, minN: Double, maxE: Double, maxN: Double): List<LaserBuilding> {
        val out = LinkedHashSet<LaserBuilding>()
        for (x in floor(minE / cell).toInt()..floor(maxE / cell).toInt()) for (y in floor(minN / cell).toInt()..floor(maxN / cell).toInt()) {
            grid[LaserData.key(x, y)]?.let { out.addAll(it) }
        }
        return out.toList()
    }
}

/**
 * Gebaeude mit Laserdaten.
 *
 * **Stufe 0:** LoD2-Gebaeude mit Schwerpunkt in der Kachel als echte
 * Geometrie ([Lod2Mesher]), nach Abstand zur Strecke, solange das Budget
 * reicht. OSM-Gebaeude, deren Schwerpunkt in einem gebauten LoD2-Grundriss
 * liegt, entfallen; die uebrigen baut [base] mit dem Restbudget und der
 * Hoehe aus LoD2.
 *
 * **Stufe 1:** [base] mit den Hoehen aus LoD2.
 */
internal class LaserBuildingsLayer(
    private val parts: LaserWorldParts,
    private val base: BuildingsLayer,
) : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level >= 2) return
        val s = ctx.spec
        var budget = ctx.budget.buildings
        val built = ArrayList<LaserBuilding>()
        if (ctx.level == 0 && budget > 0) {
            val list = parts.lod2In(s.minE, s.minN, s.maxE, s.maxN)
                .map { it to (ctx.track.nearest(it.ce, it.cn, 2000.0)?.distance ?: 2000.0) }
                .sortedWith(compareBy({ it.second }, { it.first.id }))
            if (list.isNotEmpty()) {
                val atlas = BuildingTextures.atlas(ctx.textures)
                val wallMat = TileMaterial(WorldNames.MAT_BUILDING_WALL, atlas, repeat = true, roughness = 0.92f)
                val roofMat = TileMaterial(WorldNames.MAT_BUILDING_ROOF, atlas, repeat = true, roughness = 0.85f)
                val mesher = Lod2Mesher(ctx, list.map { it.first })
                for ((b, _) in list) {
                    val m = mesher.mesh(b) ?: continue
                    if (m.triangles > budget) continue
                    budget -= m.triangles
                    out.mesh(WorldNames.NODE_BUILDINGS, wallMat, b.ce, b.cn).append(m.wall)
                    out.mesh(WorldNames.NODE_BUILDINGS, roofMat, b.ce, b.cn).append(m.roof)
                    built += b
                }
            }
        }
        val builtSet = built.toHashSet()
        val map = object : MapData by ctx.map {
            override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapBuilding> =
                ctx.map.buildings(minE, minN, maxE, maxN).mapNotNull { b ->
                    val outer = b.rings.firstOrNull() ?: return@mapNotNull b
                    val c = FootprintShape.centroid(outer)
                    val l = parts.lod2At(c[0], c[1])
                    when {
                        l != null && l in builtSet -> null
                        l != null && l.measuredHeightM > 1.0 -> MapBuilding(b.id, b.rings, l.measuredHeightM, b.minHeightM, b.colour)
                        else -> parts.ndomHeight(b)?.let { MapBuilding(b.id, b.rings, it, b.minHeightM, b.colour) } ?: b
                    }
                }
        }
        val sub = TileContext(
            spec = ctx.spec, origin = ctx.origin, quality = ctx.quality, anchorU = ctx.anchorU, ground = ctx.ground,
            track = ctx.track, map = map, land = ctx.land, textures = ctx.textures,
            budget = ctx.budget.copy(buildings = max(0, budget)), aerial = ctx.aerial, aerialTexture = ctx.aerialTexture, seed = ctx.seed,
        )
        sub.meshHeight = ctx.meshHeight
        base.build(sub, out)
    }
}

internal class Lod2Mesh(val wall: MeshBuilder, val roof: MeshBuilder) {
    val triangles: Int get() = wall.triangleCount + roof.triangleCount
}

/**
 * Baut LoD2-Gebaeude als Netz: Dach- und Wandflaechen trianguliert
 * ([Triangulator] in der Ebene der Flaeche), Bodenflaechen entfallen.
 *
 *  * **Waende** reichen 1,5 m unter den Fusspunkt (unter das vereinfachte
 *    Gelaende, nie schwebend), Textur [Band.PLASTER] (gestreckt, ohne
 *    Fenster — die Fassaden von LoD2 kennen keine Oeffnungen).
 *  * **Daecher:** geneigte Flaechen mit [Band.PANTILE] in Fallrichtung, in
 *    Streifen von einer Bandhoehe geschnitten (der Atlas kachelt nur waagrecht);
 *    flache Flaechen mit [Band.BITUMEN]. Dachfarbe aus dem Luftbild ueber dem
 *    Grundriss, sonst ein ruhiges Ziegelrot.
 *  * **Innenwaende** zwischen aneinandergebauten Haeusern (gleiche Flaeche,
 *    entgegengesetzte Normale) entfallen beide — sie liegen koplanar
 *    aufeinander und sind nie zu sehen.
 *  * Ringe werden von kollinearen Zwischenpunkten befreit (LoD2 setzt an jedem
 *    Meter des Kachelrasters einen Punkt).
 */
internal class Lod2Mesher(private val ctx: TileContext, all: List<LaserBuilding>) {
    private val hidden = HashSet<String>()

    init {
        // Innenwaende: Schluessel aus gerundetem Schwerpunkt und Flaecheninhalt.
        val seen = HashMap<String, Pair<String, Vec3>>()
        for (b in all) for ((k, sf) in b.surfaces.withIndex()) {
            if (sf.kind != Lod2SurfaceKind.WALL) continue
            val r = sf.rings[0]
            val nrm = newell(r)
            val area = nrm.length()
            if (area < 1e-6) continue
            var ce = 0.0; var cn = 0.0; var cu = 0.0
            val cnt = r.size / 3
            for (i in 0 until cnt) { ce += r[3 * i]; cn += r[3 * i + 1]; cu += r[3 * i + 2] }
            val key = "${Math.round(ce / cnt * 5)}|${Math.round(cn / cnt * 5)}|${Math.round(cu / cnt * 5)}|${Math.round(area * 2)}"
            val id = "${b.id}#$k"
            val prev = seen[key]
            if (prev != null && prev.second.dot(nrm) < 0) {
                hidden += prev.first
                hidden += id
            } else {
                seen[key] = id to nrm
            }
        }
    }

    fun mesh(b: LaserBuilding): Lod2Mesh? {
        val wall = MeshBuilder(64, 64)
        val roof = MeshBuilder(64, 64)
        val wallTint = wallColor(b)
        val roofTint = roofColor(b)
        for ((k, sf) in b.surfaces.withIndex()) {
            if (sf.kind == Lod2SurfaceKind.GROUND || sf.kind == Lod2SurfaceKind.CLOSURE) continue
            if (sf.kind == Lod2SurfaceKind.WALL && "${b.id}#$k" in hidden) continue
            val rings = sf.rings.map { simplify(it) }.filter { it.size >= 9 }
            if (rings.isEmpty()) continue
            val raw = newell(rings[0])
            if (raw.length() < 0.05) continue
            val n = raw.normalized()
            if (sf.kind == Lod2SurfaceKind.WALL || abs(n.z) < 0.17) {
                emitWall(wall, rings, n, b, wallTint)
            } else {
                emitRoof(roof, rings, n, roofTint)
            }
        }
        if (wall.triangleCount + roof.triangleCount == 0) return null
        return Lod2Mesh(wall, roof)
    }

    private fun emitWall(m: MeshBuilder, rings: List<DoubleArray>, n: Vec3, b: LaserBuilding, tint: Int) {
        // Horizontale Achse in der Wand, senkrecht dazu die Hochachse der Ebene.
        val ua = Vec3(-n.y, n.x, 0.0).let { if (it.length() < 1e-6) Vec3(1.0, 0.0, 0.0) else it.normalized() }
        val va = n.cross(ua).normalized()
        val lowest = b.groundU + 0.1
        val pts = rings.map { r ->
            DoubleArray(r.size).also { c ->
                for (i in 0 until r.size / 3) {
                    c[3 * i] = r[3 * i]; c[3 * i + 1] = r[3 * i + 1]
                    c[3 * i + 2] = if (r[3 * i + 2] <= lowest) r[3 * i + 2] - 1.5 else r[3 * i + 2]
                }
            }
        }
        val style = styleOf(b)
        val plain = style.plain ?: Band.PLASTER
        fun u(p: Vec3) = (p.x * ua.x + p.y * ua.y)
        val outer = pts[0]
        var wMin = Double.MAX_VALUE
        var wMax = -Double.MAX_VALUE
        var zMin = Double.MAX_VALUE
        var zMax = -Double.MAX_VALUE
        for (i in 0 until outer.size / 3) {
            val p = Vec3(outer[3 * i], outer[3 * i + 1], outer[3 * i + 2])
            wMin = min(wMin, u(p)); wMax = max(wMax, u(p)); zMin = min(zMin, p.z); zMax = max(zMax, p.z)
        }
        val windows = if (isSmall(b)) null else style.windows
        val eave = eaveOf(b)
        if (windows == null || pts.size > 1 || wMax - wMin < 2.2 || eave - b.groundU < 2.2) {
            emitBanded(m, pts, n, ua, va, tint, plain, b.groundU, u = ::u)
            return
        }
        // Geschosse mit Fenstern bis zur Traufe, darueber (Giebel) die schlichte Flaeche.
        val up = Vec3(0.0, 0.0, 1.0)
        val storeys = max(1, ((eave - b.groundU) / BuildingTextures.STOREY_M + 0.35).toInt())
        val storeyH = (eave - b.groundU) / storeys
        for (k in -1 until storeys) {
            val lo = if (k < 0) zMin - 1.0 else b.groundU + k * storeyH
            val hi = if (k < 0) b.groundU else b.groundU + (k + 1) * storeyH
            val piece = clipSlab(outer, up, lo, hi)
            if (piece.size < 9) continue
            val band = if (k < 0) Band.SOCLE else windows
            emitPolygon(m, listOf(piece), n, ua, va, tint) { p ->
                doubleArrayOf(u(p) / band.widthM, if (k < 0) band.v(band.heightM * 0.5) else band.v(((p.z - lo) / storeyH * band.heightM).coerceIn(0.0, band.heightM)))
            }
        }
        if (zMax > eave + 0.05) {
            val gable = clipSlab(outer, up, eave, zMax + 1.0)
            if (gable.size >= 9) emitBanded(m, listOf(gable), n, ua, va, tint, plain, eave, u = ::u)
        }
    }

    /** Flaeche mit einem schlichten Band: gestreckt in einem Stueck, sonst in Streifen der Bandhoehe ab [z0]. */
    private fun emitBanded(m: MeshBuilder, rings: List<DoubleArray>, n: Vec3, ua: Vec3, va: Vec3, tint: Int, band: Band, z0: Double, u: (Vec3) -> Double) {
        if (band.stretch || rings.size > 1) {
            val v = band.v(band.heightM * 0.5)
            emitPolygon(m, rings, n, ua, va, tint) { p -> doubleArrayOf(u(p) / band.widthM, v) }
            return
        }
        val outer = rings[0]
        var zMin = Double.MAX_VALUE
        var zMax = -Double.MAX_VALUE
        for (i in 0 until outer.size / 3) { zMin = min(zMin, outer[3 * i + 2]); zMax = max(zMax, outer[3 * i + 2]) }
        val up = Vec3(0.0, 0.0, 1.0)
        val h = band.heightM
        val k0 = floor((zMin - z0) / h).toInt()
        val k1 = floor((zMax - z0 - 1e-6) / h).toInt()
        for (k in k0..k1) {
            val lo = z0 + k * h
            val piece = clipSlab(outer, up, lo, lo + h)
            if (piece.size < 9) continue
            emitPolygon(m, listOf(piece), n, ua, va, tint) { p -> doubleArrayOf(u(p) / band.widthM, band.v((p.z - lo).coerceIn(0.0, h))) }
        }
    }

    /** Traufhoehe: tiefster Punkt der Dachflaechen (ohne Dach: Oberkante). */
    private fun eaveOf(b: LaserBuilding): Double = eaves.getOrPut(b.id) {
        var e = Double.MAX_VALUE
        for (sf in b.surfaces) if (sf.kind == Lod2SurfaceKind.ROOF) for (r in sf.rings) for (i in 2 until r.size step 3) e = min(e, r[i])
        if (e == Double.MAX_VALUE) b.topU else e
    }

    private val eaves = HashMap<String, Double>()

    /** Garage, Schuppen, Carport: keine Fenster. */
    private fun isSmall(b: LaserBuilding): Boolean = footprintArea(b) < 35 || b.topU - b.groundU < 3.2

    private fun footprintArea(b: LaserBuilding): Double = b.footprint.sumOf { r ->
        var a = 0.0
        val k = r.size / 2
        for (i in 0 until k) {
            val j = (i + 1) % k
            a += r[2 * i] * r[2 * j + 1] - r[2 * j] * r[2 * i + 1]
        }
        abs(a) / 2
    }

    /** Fassade je Gebaeude fest aus der ID: meist Putz, im Bergischen auch Schiefer, dazu Klinker und Fachwerk; Kleinbauten ohne Fenster. */
    private fun styleOf(b: LaserBuilding): FacadeStyle {
        val r = Noise.hash01(b.id.hashCode(), 0x57e1, 3)
        val area = footprintArea(b)
        if (isSmall(b)) return if (r < 0.3) FacadeStyle.WOOD else FacadeStyle.PLASTER
        if (area > 600) return if (r < 0.5) FacadeStyle.SHEET else FacadeStyle.PLASTER
        return when {
            r < 0.55 -> FacadeStyle.PLASTER
            r < 0.75 -> FacadeStyle.SLATE
            r < 0.9 -> FacadeStyle.BRICK
            else -> FacadeStyle.TIMBER
        }
    }

    private fun emitRoof(m: MeshBuilder, rings: List<DoubleArray>, n: Vec3, tint: Int) {
        val flat = n.z > 0.97
        if (flat) {
            val band = Band.BITUMEN
            val ua = Vec3(1.0, 0.0, 0.0)
            val va = n.cross(ua).normalized()
            val v = band.v(band.heightM * 0.5)
            emitPolygon(m, rings, n, ua, va, tint) { p -> doubleArrayOf(p.x / band.widthM, v) }
            return
        }
        // Fallrichtung (abwaerts in der Ebene) und Traufrichtung.
        val along = Vec3(-n.y, n.x, 0.0).normalized()
        val down = n.cross(along).normalized().let { if (it.z > 0) it * -1.0 else it }
        val ua = along
        val va = n.cross(ua).normalized()
        val band = Band.PANTILE
        val hgt = band.heightM
        // Projektion auf die Fallinie (Hangabwaerts-Meter) — Streifen [k·hgt, (k+1)·hgt).
        val outer = rings[0]
        var sMin = Double.MAX_VALUE
        var sMax = -Double.MAX_VALUE
        for (i in 0 until outer.size / 3) {
            val s = outer[3 * i] * down.x + outer[3 * i + 1] * down.y + outer[3 * i + 2] * down.z
            sMin = min(sMin, s); sMax = max(sMax, s)
        }
        val k0 = floor(sMin / hgt).toInt()
        val k1 = floor((sMax - 1e-6) / hgt).toInt()
        if (k1 - k0 > 12 || rings.size > 1) {
            // Sehr lange Flaechen oder Loecher: ohne Streifen, Band einmal gestreckt.
            emitPolygon(m, rings, n, ua, va, tint) { p ->
                val s = p.x * down.x + p.y * down.y + p.z * down.z
                doubleArrayOf((p.x * along.x + p.y * along.y) / band.widthM, band.v(((s - sMin) / (sMax - sMin + 1e-9)) * hgt))
            }
            return
        }
        for (k in k0..k1) {
            val lo = k * hgt
            val hi = (k + 1) * hgt
            val piece = clipSlab(outer, down, lo, hi)
            if (piece.size < 9) continue
            emitPolygon(m, listOf(piece), n, ua, va, tint) { p ->
                val s = p.x * down.x + p.y * down.y + p.z * down.z
                doubleArrayOf((p.x * along.x + p.y * along.y) / band.widthM, band.v((hi - s).coerceIn(0.0, hgt)))
            }
        }
    }

    /** Trianguliert ebene Ringe in der Ebene (ua, va) und haengt sie mit Normale [n] an. */
    private fun emitPolygon(
        m: MeshBuilder,
        rings: List<DoubleArray>,
        n: Vec3,
        ua: Vec3,
        va: Vec3,
        tint: Int,
        uv: (Vec3) -> DoubleArray,
    ) {
        val flat = rings.map { r -> DoubleArray(r.size / 3 * 2) { i -> val p = i / 2; val c = Vec3(r[3 * p], r[3 * p + 1], r[3 * p + 2]); if (i % 2 == 0) c.dot(ua) else c.dot(va) } }
        val idx = Triangulator.triangulate(flat)
        if (idx.isEmpty()) return
        val all = ArrayList<Vec3>()
        for (r in rings) for (i in 0 until r.size / 3) all += Vec3(r[3 * i], r[3 * i + 1], r[3 * i + 2])
        val base = m.vertexCount
        // ENU (e, n, u) → glTF (x, y, z) = (e, u, −n); Normale ebenso.
        for (p in all) {
            val t = uv(p)
            m.vertex(ctx.x(p.x), ctx.y(p.z - ctx.origin.eleM), ctx.z(p.y), n.x, n.z, -n.y, t[0], t[1], tint)
        }
        // Triangulator liefert gegen den Uhrzeigersinn in (ua, va); (ua, va, n) ist rechtshaendig.
        for (i in idx.indices step 3) m.tri(base + idx[i], base + idx[i + 1], base + idx[i + 2])
    }

    private fun wallColor(b: LaserBuilding): Int {
        val r = Noise.hash01(b.id.hashCode(), 0x1a5e, 7)
        val q = Noise.hash01(b.id.hashCode(), 0x1a5f, 9)
        // Die Baender tragen ihre Farbe selbst; die Vertexfarbe toent nur leicht (Weiss bis Sand).
        val c = 0.86 + 0.14 * r
        return BuildingDesign.lin(c, c * (0.96 + 0.04 * q), c * (0.88 + 0.1 * q))
    }

    private fun roofColor(b: LaserBuilding): Int {
        val mean = BuildingTextures.meanLinear(Band.PANTILE)
        val px = ctx.aerialMeanAt(b.ce, b.cn, 2.0)
        val target = if (px != null) {
            doubleArrayOf(
                Argb.SRGB_TO_LINEAR[Argb.red(px)].toDouble(),
                Argb.SRGB_TO_LINEAR[Argb.green(px)].toDouble(),
                Argb.SRGB_TO_LINEAR[Argb.blue(px)].toDouble(),
            )
        } else {
            doubleArrayOf(0.20, 0.07, 0.05)
        }
        // Luftbildpixel ueber Gauben, Solarflaechen und Oberlichtern sind oft zu hell: Dachhaut hoechstens 0,22 linear hell.
        val lum = 0.2126 * target[0] + 0.7152 * target[1] + 0.0722 * target[2]
        val k = if (lum > 0.22) 0.22 / lum else 1.0
        return BuildingDesign.lin(target[0] * k / mean[0], target[1] * k / mean[1], target[2] * k / mean[2])
    }

    companion object {
        fun newell(r: DoubleArray): Vec3 {
            var x = 0.0; var y = 0.0; var z = 0.0
            val k = r.size / 3
            for (i in 0 until k) {
                val j = (i + 1) % k
                val ax = r[3 * i]; val ay = r[3 * i + 1]; val az = r[3 * i + 2]
                val bx = r[3 * j]; val by = r[3 * j + 1]; val bz = r[3 * j + 2]
                x += (ay - by) * (az + bz)
                y += (az - bz) * (ax + bx)
                z += (ax - bx) * (ay + by)
            }
            return Vec3(x / 2, y / 2, z / 2)
        }

        /** Entfernt Punkte, die weniger als 3 cm neben der Geraden ihrer Nachbarn liegen, und Doppelpunkte. */
        fun simplify(r: DoubleArray): DoubleArray {
            val pts = ArrayList((0 until r.size / 3).map { Vec3(r[3 * it], r[3 * it + 1], r[3 * it + 2]) })
            var i = 0
            var guard = 0
            while (pts.size > 3 && guard < 4 * r.size) {
                guard++
                val k = pts.size
                val a = pts[(i - 1 + k) % k]
                val p = pts[i % k]
                val b = pts[(i + 1) % k]
                val ab = b - a
                val l = ab.length()
                val d = if (l < 1e-9) (p - a).length() else ((p - a) cross ab).length() / l
                if (d < 0.03) {
                    pts.removeAt(i % k)
                    if (i > 0) i--
                } else {
                    i++
                    if (i >= pts.size) break
                }
            }
            val res = DoubleArray(pts.size * 3)
            for ((q, p) in pts.withIndex()) { res[3 * q] = p.x; res[3 * q + 1] = p.y; res[3 * q + 2] = p.z }
            return res
        }

        /** Schneidet den ebenen Ring auf das Band `lo ≤ p·dir < hi` zu (Sutherland–Hodgman an zwei parallelen Ebenen). */
        fun clipSlab(r: DoubleArray, dir: Vec3, lo: Double, hi: Double): DoubleArray {
            var pts = (0 until r.size / 3).map { Vec3(r[3 * it], r[3 * it + 1], r[3 * it + 2]) }
            fun clip(input: List<Vec3>, keep: (Double) -> Boolean, edge: Double): List<Vec3> {
                val out = ArrayList<Vec3>()
                for (i in input.indices) {
                    val a = input[i]
                    val b = input[(i + 1) % input.size]
                    val sa = a.dot(dir)
                    val sb = b.dot(dir)
                    val ka = keep(sa)
                    val kb = keep(sb)
                    if (ka) out += a
                    if (ka != kb) {
                        val t = (edge - sa) / (sb - sa)
                        out += a + (b - a) * t
                    }
                }
                return out
            }
            pts = clip(pts, { it >= lo }, lo)
            if (pts.size >= 3) pts = clip(pts, { it <= hi }, hi)
            val res = DoubleArray(pts.size * 3)
            for ((i, p) in pts.withIndex()) { res[3 * i] = p.x; res[3 * i + 1] = p.y; res[3 * i + 2] = p.z }
            return res
        }
    }
}
