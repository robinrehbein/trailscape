package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute

/**
 * # Die vollstaendigen Erzeuger des Weltbaus
 *
 * Verdrahtet die einzelnen Bausteine hinter der Naht [WorldParts]:
 *
 *  * Ursprung und Streckenband: [worldOrigin], [fitTrack] (Lage geglaettet,
 *    Hoehe vom Gelaende, Bruecken ueberbrueckt, Steigung begrenzt),
 *  * Boden: [CarvedGround] mit Wassermaske aus dem Landnutzungsraster,
 *  * Landnutzung: [LandRaster] (Baumkronen aus dem Luftbild haengt die
 *    Baumschicht je Kachel selbst ein, siehe [TreesLayer]),
 *  * Kachelplanung: [planTiles] nach docs/indoor.md 7.2/7.4,
 *  * Welt-ID: [worldIdOf],
 *  * Schichten je Stufe: Gelaende, Fahrbahn, Strassen, Gebaeude, Baeume,
 *    Wasser, Kleinteile (Stufe 0); Gelaende, Wasser, grobe Gebaeude,
 *    Waldkoerper (Stufe 1); Gelaende (Stufe 2).
 *
 * Zustandslos und threadsicher; alles Weltbezogene steckt in den Objekten,
 * die die Funktionen zurueckgeben.
 */
internal object FullWorldParts : WorldParts {
    private val terrain = TerrainLayer()
    private val route = RouteLayer()
    private val roads = RoadsLayer()
    private val buildings = BuildingsLayer()
    private val trees = TreesLayer()
    private val water = WaterLayer()
    private val props = PropsLayer()
    private val pois = PoiLayer()
    private val vines = VineyardLayer()

    private val level0 = listOf<TileLayer>(terrain, water, route, roads, buildings, trees, vines, pois, props)
    private val level1 = listOf<TileLayer>(terrain, water, buildings, trees)
    private val level2 = listOf<TileLayer>(terrain)

    override fun origin(route: IndoorRoute, heightAtLatLon: (Double, Double) -> Double): GeoOrigin =
        worldOrigin(route, heightAtLatLon)

    override fun fitTrack(
        route: IndoorRoute,
        origin: GeoOrigin,
        rawHeightAt: (e: Double, n: Double) -> Double,
        isBridge: (distM: Double) -> Boolean,
    ): WorldTrack = fitTrack(route, origin, RawHeights { e, n -> rawHeightAt(e, n) }, isBridge)

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
        terrainCellM = level0TerrainCellM(quality),
        water = land as? WaterMask,
        bridges = trackBridges(track, map),
    )

    override fun landMap(map: MapData, track: TrackLine, aerialOf: (TileSpec) -> RgbaImage?): LandMap = LandRaster(map)

    override fun planTiles(track: TrackLine, loop: Boolean, quality: IndoorQuality): List<PlannedTile> =
        planTiles(PlanPath.of(track.track), quality).plans.map { PlannedTile(it.spec, it.show) }

    override fun worldId(route: IndoorRoute, quality: IndoorQuality): String = worldIdOf(route, quality)

    override fun layers(level: Int): List<TileLayer> = when (level) {
        0 -> level0
        1 -> level1
        else -> level2
    }
}

/**
 * Laufmeter des Streckenbands auf Bruecken (wie [bridgeLookup], aber am fertigen
 * Band): eine `transportation`-Linie mit `brunnel=bridge` hoechstens 8 m neben
 * der Mittellinie und etwa gleich ausgerichtet. Dort ebnet [CarvedGround] nicht ein.
 */
internal fun trackBridges(track: TrackLine, map: MapData): (Double) -> Boolean {
    val t = track.track
    val count = t.sampleCount
    val flags = BooleanArray(count)
    for (i in 0 until count) {
        val e = t.e[i].toDouble()
        val n = t.n[i].toDouble()
        val roads = map.roads(e - 10, n - 10, e + 10, n + 10).filter { it.brunnel == "bridge" }
        if (roads.isEmpty()) continue
        val a = maxOf(0, i - 1)
        val b = minOf(count - 1, i + 1)
        var he = (t.e[b] - t.e[a]).toDouble()
        var hn = (t.n[b] - t.n[a]).toDouble()
        val l = kotlin.math.hypot(he, hn)
        if (l > 1e-9) { he /= l; hn /= l } else { he = 0.0; hn = 1.0 }
        flags[i] = nearestAligned(roads, e, n, he, hn, 8.0, 0.8) != null
    }
    if (flags.none { it }) return { false }
    return { d -> flags[Math.round(d / t.stepM).toInt().coerceIn(0, count - 1)] }
}
