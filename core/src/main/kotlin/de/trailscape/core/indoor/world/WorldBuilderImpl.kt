package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.sampleAt
import de.trailscape.core.optionalBoolean
import de.trailscape.core.optionalString
import de.trailscape.core.requiredDouble
import de.trailscape.core.requiredInt
import de.trailscape.core.requiredLong
import de.trailscape.core.requiredString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * # Der Weltbau-Rahmen
 *
 * Fuehrt Strecke, Geodaten und Erzeuger ([WorldParts]) zu einer fertigen Welt
 * zusammen (docs/indoor.md 7 und 8):
 *
 *  1. **ELEVATION** – Hoehenkacheln fuer das Gebiet der vorlaeufigen Planung
 *     (z13 innen, z11 aussen), daraus der Ursprung.
 *  2. **VECTOR** – TileJSON und Vektorkacheln (z14 / z12); daraus Bruecken
 *     und, falls die Strecke keine Belaege hat, die Belaege. Danach wird das
 *     Band eingepasst, endgueltig geplant und nachgeladen, was die
 *     endgueltige Planung zusaetzlich braucht (selten; der Fortschritt der
 *     Phase laeuft dann mit groesserer Gesamtzahl weiter).
 *  3. **AERIAL** + **MESHING** + **WRITING** – je Kachel Luftbild holen,
 *     Schichten bauen, GLB atomar schreiben; parallel auf
 *     `min(Kerne, 4)` Threads. Neben den Kacheln liegt `build/` mit den
 *     Kennzahlen je fertiger Kachel: Ein zweiter Lauf mit demselben Plan
 *     ueberspringt sie (Hoehen und Vektorkacheln werden fuer das ganze
 *     Gebiet erneut gelesen — aus dem Plattencache der Huelle).
 *  4. Manifest mit nachgerechnetem Budget zuletzt und atomar, danach wird
 *     `build/` geloescht.
 *
 * Fortschritt und Abbruchfragen kommen immer vom aufrufenden Thread.
 */
internal class WorldBuilderImpl(
    private val parts: WorldParts,
    private val aerialRegister: List<AerialSource> = aerialSources,
    private val clock: () -> Long = System::currentTimeMillis,
    private val threads: Int = min(Runtime.getRuntime().availableProcessors(), 4).coerceAtLeast(1),
) : WorldBuilder {

    // -----------------------------------------------------------------------
    // Voranschlag
    // -----------------------------------------------------------------------

    override fun plan(route: IndoorRoute, quality: IndoorQuality): WorldPlan {
        validate(route)
        val pre = preliminary(route, quality)
        val specs = pre.tiles.map { it.spec }
        val sets = GeoTileSets.of(pre.origin, specs)
        val aerial = aerialRegister.filter { s -> route.points.any { s.covers(it.lat, it.lon) } }
        val aerialTiles = specs.filter { s ->
            val box = TileMath.enuToLatLon(pre.origin, s.minE, s.minN, s.maxE, s.maxN)
            selectAerialSources(aerial, box).isNotEmpty()
        }
        val requests = linkedMapOf(
            GeoSourceKind.ELEVATION to sets.elevationNear.size + sets.elevationFar.size,
            GeoSourceKind.VECTOR to 1 + sets.vectorNear.size + sets.vectorFar.size,
            GeoSourceKind.AERIAL to aerialTiles.size,
        )
        val download = sets.elevationNear.size * WorldEstimates.ELEVATION_TILE_BYTES +
            sets.elevationFar.size * WorldEstimates.ELEVATION_TILE_BYTES +
            WorldEstimates.TILEJSON_BYTES +
            sets.vectorNear.size * WorldEstimates.VECTOR_TILE_NEAR_BYTES +
            sets.vectorFar.size * WorldEstimates.VECTOR_TILE_FAR_BYTES +
            aerialTiles.sumOf { WorldEstimates.aerialBytes(quality.texturePx(it.level)) }
        val aerialSet = aerialTiles.toSet()
        val disk = specs.sumOf { s ->
            val texture = if (s in aerialSet) {
                WorldEstimates.aerialBytes(quality.texturePx(s.level))
            } else {
                WorldEstimates.generatedGroundBytes(quality.texturePx(s.level))
            }
            texture + WorldEstimates.meshBytes(quality, s.level) + WorldEstimates.sharedTextureBytes(s.level)
        } + WorldEstimates.MANIFEST_BYTES_PER_KM * ceil(route.lengthM / 1000.0).toLong()
        val hosts = LinkedHashSet<String>()
        hosts += GeoEndpoints.ELEVATION_HOST
        hosts += GeoEndpoints.VECTOR_HOST
        aerial.forEach { s -> java.net.URI(s.wmsUrl).host?.let { hosts += it.lowercase() } }
        return WorldPlan(
            worldId = parts.worldId(route, quality),
            route = route,
            quality = quality,
            tileCount = specs.size,
            requests = requests,
            downloadBytes = download,
            diskBytes = disk,
            hosts = hosts.toList(),
            aerial = aerial,
        )
    }

    // -----------------------------------------------------------------------
    // Bau mit echten Geodaten
    // -----------------------------------------------------------------------

    override fun build(plan: WorldPlan, deps: WorldBuildDeps, outDir: File, listener: WorldBuildListener): WorldManifest {
        try {
            validate(plan.route)
            if (!outDir.isDirectory && !outDir.mkdirs()) throw WorldBuildFailed(WorldBuildFailed.Reason.NO_SPACE, "Verzeichnis $outDir laesst sich nicht anlegen")
            val free = outDir.usableSpace
            if (free in 1 until plan.diskBytes) {
                throw WorldBuildFailed(WorldBuildFailed.Reason.NO_SPACE, "Frei sind $free Bytes, geschaetzt gebraucht ${plan.diskBytes}")
            }
            if (listener.isCancelled()) throw WorldBuildCancelled()
            val route0 = plan.route
            val quality = plan.quality
            val pre = preliminary(route0, quality)
            val loader = GeoLoader(deps, pre.origin, listener, threads)

            loader.loadElevation(GeoTileSets.of(pre.origin, pre.tiles.map { it.spec }))
            val heights0 = loader.heights(fineCellsOf(pre.tiles.map { it.spec }))
            val origin = parts.origin(route0, heights0::aboveSeaLatLon)
            if (origin.lat != pre.origin.lat || origin.lon != pre.origin.lon) {
                throw WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, "Ursprung haengt von den Hoehen ab (${pre.origin} → $origin)")
            }

            loader.loadVector(GeoTileSets.of(pre.origin, pre.tiles.map { it.spec }))
            val map0 = loader.mapData()
            val route = if (needsVectorSurfaces(route0)) withVectorSurfaces(route0, origin, map0) else route0
            val isBridge = bridgeLookup(route, origin, map0)
            val track = parts.fitTrack(route, origin, { e, n -> heights0.aboveSea(e, n) - origin.eleM }, isBridge)
            val planned = parts.planTiles(TrackLine(track), track.loop, quality)
            val specs = planned.map { it.spec }
            val finalSets = GeoTileSets.of(origin, specs)
            loader.loadElevation(finalSets)
            val vectorAdded = loader.loadVector(finalSets)
            val heights = loader.heights(fineCellsOf(specs))
            val map = if (vectorAdded) loader.mapData() else map0

            val attribution = ArrayList<WorldAttribution>()
            attribution += WorldAttribution(GeoSourceKind.ELEVATION, ELEVATION_ATTRIBUTION, ELEVATION_LICENSE, ELEVATION_ATTRIBUTION_URL)
            if (loader.vectorUsed) attribution += WorldAttribution(GeoSourceKind.VECTOR, VECTOR_ATTRIBUTION, VECTOR_LICENSE, VECTOR_ATTRIBUTION_URL)

            val inputs = WorldInputs(
                worldId = plan.worldId,
                quality = quality,
                routeId = route.id,
                routeName = route.name,
                origin = origin,
                track = track,
                planned = planned,
                rawHeightAt = { e, n -> heights.aboveSea(e, n) - origin.eleM },
                map = map,
                degradedOf = loader::vectorMissing,
                aerial = if (plan.aerial.isEmpty()) null else AerialStore(deps.fetcher, deps.decoder, origin, plan.aerial, quality),
                aerialSources = plan.aerial,
                baseAttribution = attribution,
                createdAtMs = clock(),
            )
            return assemble(inputs, outDir, listener)
        } catch (e: WorldBuildCancelled) {
            throw e
        } catch (e: WorldBuildFailed) {
            throw e
        } catch (e: Exception) {
            throw WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, e.message ?: e.javaClass.simpleName, e)
        }
    }

    override fun buildTestWorld(outDir: File, quality: IndoorQuality): WorldManifest {
        val scene = TestWorld.create()
        val route = scene.route
        val origin = parts.origin(route, scene::heightAtLatLon)
        val raw = { e: Double, n: Double -> scene.heightAtLatLon(origin.toLat(n), origin.toLon(e)) - origin.eleM }
        val track = parts.fitTrack(route, origin, raw, scene::isBridge)
        val planned = parts.planTiles(TrackLine(track), track.loop, quality)
        val inputs = WorldInputs(
            worldId = parts.worldId(route, quality),
            quality = quality,
            routeId = route.id,
            routeName = route.name,
            origin = origin,
            track = track,
            planned = planned,
            rawHeightAt = raw,
            map = scene.mapData(origin),
            degradedOf = { false },
            aerial = null,
            aerialSources = emptyList(),
            baseAttribution = listOf(TestWorld.ATTRIBUTION),
            createdAtMs = 0L,
        )
        outDir.mkdirs()
        return assemble(inputs, outDir, object : WorldBuildListener {
            override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) = Unit
        })
    }

    // -----------------------------------------------------------------------
    // Gemeinsamer Kachelbau
    // -----------------------------------------------------------------------

    /** Alles, was der Kachelbau braucht — aus echten Geodaten oder aus der Testwelt. */
    internal class WorldInputs(
        val worldId: String,
        val quality: IndoorQuality,
        val routeId: String,
        val routeName: String,
        val origin: GeoOrigin,
        val track: WorldTrack,
        val planned: List<PlannedTile>,
        val rawHeightAt: (Double, Double) -> Double,
        val map: MapData,
        val degradedOf: (TileSpec) -> Boolean,
        val aerial: AerialStore?,
        val aerialSources: List<AerialSource>,
        val baseAttribution: List<WorldAttribution>,
        val createdAtMs: Long,
    )

    private fun assemble(inputs: WorldInputs, outDir: File, listener: WorldBuildListener): WorldManifest {
        val quality = inputs.quality
        val line = TrackLine(inputs.track)
        val ground = parts.ground(inputs.rawHeightAt, line, inputs.map)
        val land = parts.landMap(inputs.map, line) { spec -> inputs.aerial?.get(spec)?.rgba }
        val textures = TextureCache()

        val tilesDir = File(outDir, WorldManifest.TILES_DIR)
        val stateDir = File(outDir, STATE_DIR)
        val metricsDir = File(stateDir, "tiles")
        val manifestFile = File(outDir, WorldManifest.FILE_NAME)
        val fingerprint = fingerprint(inputs)
        try {
            // Ein altes Manifest gehoert zu einer anderen (oder dieser) fertigen Welt: Ab jetzt ist sie angefangen.
            if (manifestFile.exists() && !manifestFile.delete()) throw IOException("$manifestFile laesst sich nicht loeschen")
            val stateFile = File(stateDir, STATE_FILE)
            val previous = if (stateFile.isFile) runCatching { stateFile.readText() }.getOrNull() else null
            if (previous?.trim() != fingerprint) {
                stateDir.deleteRecursively()
                tilesDir.deleteRecursively()
            }
            if (!tilesDir.isDirectory && !tilesDir.mkdirs()) throw IOException("$tilesDir laesst sich nicht anlegen")
            if (!metricsDir.isDirectory && !metricsDir.mkdirs()) throw IOException("$metricsDir laesst sich nicht anlegen")
            if (previous?.trim() != fingerprint) writeAtomic(stateFile, fingerprint.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw WorldBuildFailed(WorldBuildFailed.Reason.NO_SPACE, "Schreiben in $outDir gescheitert: ${e.message}", e)
        }

        val done = HashMap<TileSpec, TileMetrics>()
        for (p in inputs.planned) {
            val m = readMetrics(File(metricsDir, "${p.spec.id}.json")) ?: continue
            val glb = File(tilesDir, "${p.spec.id}.glb")
            if (m.id == p.spec.id && glb.isFile && glb.length() == m.bytes) done[p.spec] = m
        }
        val total = inputs.planned.size
        val todo = inputs.planned.filter { it.spec !in done }
        val aerialDone = AtomicInteger(done.size)
        var reportedAerial = -1
        var meshed = done.size
        listener.onProgress(WorldBuildPhase.AERIAL, done.size, total)
        listener.onProgress(WorldBuildPhase.MESHING, done.size, total)
        listener.onProgress(WorldBuildPhase.WRITING, done.size, total + 1)
        fun tickAerial() {
            val a = aerialDone.get()
            if (a != reportedAerial) {
                reportedAerial = a
                listener.onProgress(WorldBuildPhase.AERIAL, a, total)
            }
        }

        val built = runParallel(
            items = todo,
            threads = threads,
            listener = listener,
            onTick = ::tickAerial,
            onDone = { _ ->
                tickAerial()
                meshed++
                listener.onProgress(WorldBuildPhase.MESHING, meshed, total)
                listener.onProgress(WorldBuildPhase.WRITING, meshed, total + 1)
            },
        ) { p ->
            val spec = p.spec
            val aerial = inputs.aerial?.get(spec)
            aerialDone.incrementAndGet()
            val anchorU = Math.round(ground.heightAt(spec.anchorE, spec.anchorN) * 100.0) / 100.0
            val ctx = TileContext(
                spec = spec,
                origin = inputs.origin,
                quality = quality,
                anchorU = anchorU,
                ground = ground,
                track = line,
                map = inputs.map,
                land = land,
                textures = textures,
                budget = TileBudget.of(quality, spec.level),
                aerial = aerial?.rgba,
                aerialTexture = aerial?.texture,
            )
            val out = TileOutput(spec)
            for (layer in parts.layers(spec.level)) layer.build(ctx, out)
            val bytes = out.toGlb().toBytes()
            val info = parseGlb(bytes)
            val metrics = TileMetrics(
                id = spec.id,
                bytes = bytes.size.toLong(),
                textureBytes = info.textureGpuBytes,
                triangles = info.triangleCount,
                minU = anchorU + (info.boundsMin?.get(1) ?: 0.0),
                maxU = anchorU + (info.boundsMax?.get(1) ?: 0.0),
                aerial = aerial != null,
                degraded = inputs.degradedOf(spec),
                anchorU = anchorU,
                aerialSource = aerial?.image?.source?.id,
            )
            try {
                writeAtomic(File(tilesDir, "${spec.id}.glb"), bytes)
                writeAtomic(File(metricsDir, "${spec.id}.json"), metrics.toJson().toByteArray(Charsets.UTF_8))
            } catch (e: IOException) {
                throw WorldBuildFailed(WorldBuildFailed.Reason.NO_SPACE, "Kachel ${spec.id} laesst sich nicht schreiben: ${e.message}", e)
            }
            metrics
        }
        built.forEachIndexed { i, m -> done[todo[i].spec] = m }

        val tiles = inputs.planned.map { p ->
            val s = p.spec
            val m = done.getValue(s)
            WorldTile(
                id = s.id, level = s.level, ix = s.ix, iy = s.iy,
                file = "${WorldManifest.TILES_DIR}/${s.id}.glb",
                bytes = m.bytes,
                anchorE = s.anchorE, anchorN = s.anchorN, anchorU = m.anchorU,
                minU = m.minU, maxU = m.maxU,
                show = p.show,
                textureBytes = m.textureBytes, triangles = m.triangles,
                aerial = m.aerial, degraded = m.degraded,
            )
        }
        val (maxTex, maxTri) = worldBudgetOf(tiles, inputs.track.lengthM)
        if (maxTex > quality.maxTextureBytes || maxTri > quality.maxTriangles) {
            throw WorldBuildFailed(
                WorldBuildFailed.Reason.INTERNAL,
                "Budget ueberschritten: $maxTex Texturbytes (erlaubt ${quality.maxTextureBytes}), $maxTri Dreiecke (erlaubt ${quality.maxTriangles}) — Fehler der Kachelplanung",
            )
        }
        val usedAerial = done.values.mapNotNull { it.aerialSource }.toSet()
        val attribution = inputs.baseAttribution + inputs.aerialSources.filter { it.id in usedAerial }.map {
            WorldAttribution(GeoSourceKind.AERIAL, it.attribution, it.license, it.licenseUrl)
        }
        val bounds = WorldBounds(
            minE = inputs.planned.minOf { it.spec.minE },
            minN = inputs.planned.minOf { it.spec.minN },
            maxE = inputs.planned.maxOf { it.spec.maxE },
            maxN = inputs.planned.maxOf { it.spec.maxN },
            minU = tiles.minOf { it.minU },
            maxU = tiles.maxOf { it.maxU },
        )
        val tileBytes = tiles.sumOf { it.bytes }
        var manifest = WorldManifest(
            worldId = inputs.worldId,
            builder = WORLD_BUILDER_ID,
            createdAtMs = inputs.createdAtMs,
            quality = quality,
            routeId = inputs.routeId,
            routeName = inputs.routeName,
            origin = inputs.origin,
            bounds = bounds,
            track = inputs.track,
            levels = (0 until WORLD_LEVEL_COUNT).map { WorldLevel(it, worldCellM(it), quality.texturePx(it)) },
            tiles = tiles,
            budget = WorldBudget(maxTex, maxTri, tileBytes),
            attribution = attribution,
        )
        // diskBytes enthaelt das Manifest selbst: so lange rechnen, bis die Laenge stabil ist.
        var json = manifest.toJson()
        repeat(4) {
            val disk = tileBytes + json.toByteArray(Charsets.UTF_8).size
            if (disk == manifest.budget.diskBytes) return@repeat
            manifest = manifest.copy(budget = manifest.budget.copy(diskBytes = disk))
            json = manifest.toJson()
        }
        try {
            writeAtomic(manifestFile, json.toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            throw WorldBuildFailed(WorldBuildFailed.Reason.NO_SPACE, "Manifest laesst sich nicht schreiben: ${e.message}", e)
        }
        stateDir.deleteRecursively()
        listener.onProgress(WorldBuildPhase.WRITING, total + 1, total + 1)
        return manifest
    }

    /** Fingerabdruck eines Baus: gleiche Eingaben → gleiche Kacheln; sonst wird neu begonnen. */
    private fun fingerprint(inputs: WorldInputs): String {
        val sb = StringBuilder()
        sb.append(WORLD_BUILDER_ID).append('|').append(inputs.worldId).append('|').append(inputs.quality.name)
        sb.append('|').append(inputs.aerialSources.joinToString(",") { it.id })
        sb.append('|').append(sha256Hex(inputs.track.toJson().toString()))
        sb.append('|').append(inputs.origin.toJson().toString())
        for (p in inputs.planned) sb.append(';').append(p.spec.id)
        return sha256Hex(sb.toString())
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    private class Preliminary(val origin: GeoOrigin, val tiles: List<PlannedTile>)

    /**
     * Planung ohne Netz: Ursprung mit der Hoehe des Streckenstarts aus der
     * Strecke selbst, Band = Strecke (Lage unveraendert, Hoehe der Strecke).
     */
    private fun preliminary(route: IndoorRoute, quality: IndoorQuality): Preliminary {
        val origin = parts.origin(route) { _, _ -> route.points.first().eleM }
        val step = WorldTrack.DEFAULT_STEP_M
        val len = route.lengthM
        val count = ceil(len / step - 1e-9).toInt() + 1
        val open = route.copy(isLoop = false)
        val e = FloatArray(count)
        val n = FloatArray(count)
        val u = FloatArray(count)
        for (i in 0 until count) {
            val s = open.sampleAt(min(i * step, len))
            e[i] = origin.toEast(s.lon).toFloat()
            n[i] = origin.toNorth(s.lat).toFloat()
            u[i] = origin.toUp(s.eleM).toFloat()
        }
        val track = WorldTrack(len, step, route.isLoop, e, n, u, route.surfaces, route.source)
        return Preliminary(origin, parts.planTiles(TrackLine(track), route.isLoop, quality))
    }

    private fun validate(route: IndoorRoute) {
        val len = if (route.points.size >= 2) route.lengthM else 0.0
        if (route.points.size < 2 || !len.isFinite() || len < MIN_WORLD_ROUTE_M || len > MAX_WORLD_ROUTE_M) {
            throw WorldBuildFailed(
                WorldBuildFailed.Reason.ROUTE_UNSUPPORTED,
                "Strecke mit ${route.points.size} Punkten und ${len.roundToLong()} m (erlaubt ${MIN_WORLD_ROUTE_M.toInt()} m bis ${MAX_WORLD_ROUTE_M.toInt()} m)",
            )
        }
    }

    companion object {
        /** Kuerzeste Strecke, fuer die eine Welt gebaut wird. */
        const val MIN_WORLD_ROUTE_M: Double = 200.0

        /** Zustandsverzeichnis eines angefangenen Baus (neben `tiles/`). */
        const val STATE_DIR: String = "build"
        private const val STATE_FILE = "plan.txt"

        const val ELEVATION_ATTRIBUTION: String =
            "Höhen: Mapzen/Tilezen Terrarium (AWS Open Data); enthält u. a. Copernicus-Daten (EU-DEM, " +
                "gefördert von der Europäischen Union), SRTM und GMTED2010 (U.S. Geological Survey), ETOPO1 (NOAA)"
        const val ELEVATION_LICENSE: String = "Quellenmix, Namensnennung nach Tilezen"
        const val ELEVATION_ATTRIBUTION_URL: String = "https://github.com/tilezen/joerd/blob/master/docs/attribution.md"
        const val VECTOR_ATTRIBUTION: String = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
        const val VECTOR_LICENSE: String = "ODbL"
        const val VECTOR_ATTRIBUTION_URL: String = "https://www.openstreetmap.org/copyright"
    }
}

/** Durchschnittsgroessen fuer den Voranschlag ([WorldPlan.downloadBytes], [WorldPlan.diskBytes]). */
internal object WorldEstimates {
    /** Terrarium-PNG 256² (Mittel- und Bergland). */
    const val ELEVATION_TILE_BYTES: Long = 105_000

    /** OpenFreeMap-Vektorkachel z14 (laendlich bis Dorf). */
    const val VECTOR_TILE_NEAR_BYTES: Long = 35_000

    /** OpenFreeMap-Vektorkachel z12. */
    const val VECTOR_TILE_FAR_BYTES: Long = 65_000

    /** Das TileJSON (mit allen Namensfeldern). */
    const val TILEJSON_BYTES: Long = 40_000

    /** Manifest je Streckenkilometer (Band, Kachelliste). */
    const val MANIFEST_BYTES_PER_KM: Long = 20_000

    /** Bytes je Dreieck in einer Kachel-GLB (Eckpunkte mit Normale, UV, Farbe, Indizes). */
    const val GLB_BYTES_PER_TRIANGLE: Long = 48

    /** Wie weit die Erzeuger das Dreiecksbudget einer Kachel im Mittel ausschoepfen. */
    const val BUDGET_USE: Double = 0.5

    /** Luftbild als Baseline-JPEG je Kantenlaenge. */
    fun aerialBytes(px: Int): Long = when {
        px >= 2048 -> 700_000
        px >= 1024 -> 200_000
        px >= 512 -> 60_000
        else -> 20_000
    }

    /** Erzeugte Bodentextur (rauschige Flaechen packen schlechter als Luftbilder). */
    fun generatedGroundBytes(px: Int): Long = aerialBytes(px) * 3 / 2

    fun meshBytes(quality: IndoorQuality, level: Int): Long =
        (TileBudget.of(quality, level).total * BUDGET_USE).toLong() * GLB_BYTES_PER_TRIANGLE

    /** Kachelnde Texturen der Schichten, die jede Kachel selbst einbettet. */
    fun sharedTextureBytes(level: Int): Long = when (level) {
        0 -> 600_000
        1 -> 200_000
        else -> 0
    }
}

// ---------------------------------------------------------------------------
// Geodaten laden
// ---------------------------------------------------------------------------

/**
 * Laedt Hoehen- und Vektorkacheln parallel ueber die Huelle und haelt sie
 * fuer den ganzen Bau. Fortschritt je Phase ist monoton; kommt spaeter noch
 * etwas hinzu, waechst die Gesamtzahl.
 */
private class GeoLoader(
    private val deps: WorldBuildDeps,
    private val origin: GeoOrigin,
    private val listener: WorldBuildListener,
    private val threads: Int,
) {
    private val elevNear = LinkedHashMap<TileId, ElevationTile>()
    private val elevFar = LinkedHashMap<TileId, ElevationTile>()
    private var elevDone = 0
    private var elevTotal = 0
    private var fieldsKey = ""
    private var fields: Pair<ElevationField?, ElevationField?> = null to null

    private var tileJson: TileJson? = null
    private var tileJsonTried = false
    private val vecNear = LinkedHashMap<TileId, VectorTileData?>()
    private val vecFar = LinkedHashMap<TileId, VectorTileData?>()
    private var vecDone = 0
    private var vecTotal = 0

    /** Gibt es irgendeine gelesene Vektorkachel (dann gehoert die Quellenangabe dazu)? */
    val vectorUsed: Boolean get() = vecNear.values.any { it != null } || vecFar.values.any { it != null }

    /** Holt die noch fehlenden Hoehenkacheln. Netzfehler → `OFFLINE`, Quellfehler → `SOURCE_ERROR`. */
    fun loadElevation(sets: GeoTileSets): Boolean {
        val todo = sets.elevationNear.filter { it !in elevNear }.map { it to true } +
            sets.elevationFar.filter { it !in elevFar }.map { it to false }
        elevTotal += todo.size
        listener.onProgress(WorldBuildPhase.ELEVATION, elevDone, elevTotal)
        if (todo.isEmpty()) return false
        val got = try {
            runParallel(todo, threads, listener, onDone = { _ ->
                elevDone++
                listener.onProgress(WorldBuildPhase.ELEVATION, elevDone, elevTotal)
            }) { (id, _) -> fetchElevationTile(deps.fetcher, deps.decoder, id) }
        } catch (e: IOException) {
            throw WorldBuildFailed(WorldBuildFailed.Reason.OFFLINE, "Hoehenkacheln nicht erreichbar: ${e.message}", e)
        } catch (e: ElevationMissingException) {
            throw WorldBuildFailed(WorldBuildFailed.Reason.SOURCE_ERROR, e.message ?: "Hoehenkachel ${e.tile} fehlt", e)
        }
        for ((i, t) in got.withIndex()) if (todo[i].second) elevNear[t.id] = t else elevFar[t.id] = t
        return true
    }

    /** Hoehenfunktion mit den feinen Zellen [fineCells]. */
    fun heights(fineCells: Set<TileSpec>): TerrainHeights {
        val key = "${elevNear.size}/${elevFar.size}"
        if (key != fieldsKey) {
            fields = Pair(
                if (elevNear.isEmpty()) null else ElevationField(origin, ELEVATION_ZOOM_NEAR, elevNear.values, ELEVATION_SMOOTHING_PX),
                if (elevFar.isEmpty()) null else ElevationField(origin, ELEVATION_ZOOM_FAR, elevFar.values, ELEVATION_SMOOTHING_PX),
            )
            fieldsKey = key
        }
        return TerrainHeights(fields.first, fields.second, fineCells)
    }

    /** Holt TileJSON und die noch fehlenden Vektorkacheln; `true`, wenn etwas dazukam. */
    fun loadVector(sets: GeoTileSets): Boolean {
        val todo = sets.vectorNear.filter { it !in vecNear }.map { it to true } +
            sets.vectorFar.filter { it !in vecFar }.map { it to false }
        val needJson = !tileJsonTried && todo.isNotEmpty()
        vecTotal += todo.size + if (needJson) 1 else 0
        listener.onProgress(WorldBuildPhase.VECTOR, vecDone, vecTotal)
        if (todo.isEmpty()) return false
        if (listener.isCancelled()) throw WorldBuildCancelled()
        if (needJson) {
            tileJsonTried = true
            tileJson = fetchTileJson(deps.fetcher)
            vecDone++
            listener.onProgress(WorldBuildPhase.VECTOR, vecDone, vecTotal)
        }
        val json = tileJson
        val got = if (json == null) {
            vecDone += todo.size
            listener.onProgress(WorldBuildPhase.VECTOR, vecDone, vecTotal)
            todo.map { null }
        } else {
            runParallel(todo, threads, listener, onDone = { _ ->
                vecDone++
                listener.onProgress(WorldBuildPhase.VECTOR, vecDone, vecTotal)
            }) { (id, _) -> Box(fetchVectorTile(deps.fetcher, json, id)) }.map { it.value }
        }
        for ((i, t) in got.withIndex()) {
            val (id, near) = todo[i]
            if (near) vecNear[id] = t else vecFar[id] = t
        }
        return true
    }

    fun mapData(): MapData {
        val near = vecNear.values.filterNotNull()
        val far = vecFar.values.filterNotNull()
        return VectorMapData(
            origin,
            if (near.isEmpty()) null else VectorIndex(origin, near),
            vecNear.filterValues { it != null }.keys.toSet(),
            if (far.isEmpty()) null else VectorIndex(origin, far),
        )
    }

    /** Fehlt einer Kachel eine ihrer Vektorkacheln? */
    fun vectorMissing(spec: TileSpec): Boolean {
        val near = spec.level <= 1
        val ids = tilesForSpecs(origin, listOf(spec), if (near) VECTOR_ZOOM_NEAR else VECTOR_ZOOM_FAR, 0.0)
        val loaded = if (near) vecNear else vecFar
        return ids.any { loaded[it] == null }
    }

    private class Box<T>(val value: T)
}

// ---------------------------------------------------------------------------
// Parallele Ausfuehrung mit Fortschritt und Abbruch vom aufrufenden Thread
// ---------------------------------------------------------------------------

private val WORKER_FACTORY = ThreadFactory { r ->
    Thread(r, "indoor-world").apply { isDaemon = true }
}

/**
 * Fuehrt [work] fuer alle [items] auf hoechstens [threads] Threads aus.
 * [onDone] und [onTick] laufen auf dem aufrufenden Thread; zwischen den
 * Ergebnissen wird [WorldBuildListener.isCancelled] gefragt. Bei Abbruch oder
 * Fehler startet nichts Neues mehr, Laufendes wird zu Ende gebracht (Dateien
 * bleiben ganz), dann geht die Ausnahme weiter. Ergebnis in Eingabereihenfolge.
 */
internal fun <T, R> runParallel(
    items: List<T>,
    threads: Int,
    listener: WorldBuildListener,
    onTick: () -> Unit = {},
    onDone: (R) -> Unit = {},
    work: (T) -> R,
): List<R> {
    if (items.isEmpty()) return emptyList()
    if (listener.isCancelled()) throw WorldBuildCancelled()
    val pool = Executors.newFixedThreadPool(min(threads, items.size).coerceAtLeast(1), WORKER_FACTORY)
    val service = ExecutorCompletionService<Pair<Int, R>>(pool)
    val stop = AtomicBoolean(false)
    val futures = ArrayList<Future<Pair<Int, R>>>(items.size)
    val results = arrayOfNulls<Any?>(items.size)
    try {
        for ((i, item) in items.withIndex()) {
            futures += service.submit(Callable<Pair<Int, R>> {
                if (stop.get()) throw CancellationException()
                i to work(item)
            })
        }
        var remaining = items.size
        while (remaining > 0) {
            if (listener.isCancelled()) throw WorldBuildCancelled()
            val f = service.poll(50, TimeUnit.MILLISECONDS)
            onTick()
            if (f == null) continue
            remaining--
            val (i, r) = try {
                f.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            results[i] = r
            onDone(r)
        }
    } catch (t: Throwable) {
        stop.set(true)
        futures.forEach { it.cancel(false) }
        throw t
    } finally {
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.MINUTES)
    }
    @Suppress("UNCHECKED_CAST")
    return results.toList() as List<R>
}

// ---------------------------------------------------------------------------
// Kennzahlen je Kachel (Zustand eines angefangenen Baus)
// ---------------------------------------------------------------------------

/** Was das Manifest von einer fertigen Kachel braucht. */
internal class TileMetrics(
    val id: String,
    val bytes: Long,
    val textureBytes: Long,
    val triangles: Int,
    val minU: Double,
    val maxU: Double,
    val aerial: Boolean,
    val degraded: Boolean,
    val anchorU: Double,
    val aerialSource: String?,
) {
    fun toJson(): String = buildJsonObject {
        put("id", id)
        put("bytes", bytes)
        put("textureBytes", textureBytes)
        put("triangles", triangles)
        put("minU", minU)
        put("maxU", maxU)
        put("aerial", aerial)
        put("degraded", degraded)
        put("anchorU", anchorU)
        aerialSource?.let { put("aerialSource", it) }
    }.toString()
}

private fun readMetrics(file: File): TileMetrics? {
    if (!file.isFile) return null
    return try {
        val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
        TileMetrics(
            id = o.requiredString("id"),
            bytes = o.requiredLong("bytes"),
            textureBytes = o.requiredLong("textureBytes"),
            triangles = o.requiredInt("triangles"),
            minU = o.requiredDouble("minU"),
            maxU = o.requiredDouble("maxU"),
            aerial = o.optionalBoolean("aerial") ?: false,
            degraded = o.optionalBoolean("degraded") ?: false,
            anchorU = o.requiredDouble("anchorU"),
            aerialSource = o.optionalString("aerialSource"),
        )
    } catch (_: Exception) {
        null
    }
}

/** Schreibt ueber eine Temp-Datei im selben Verzeichnis und benennt sie um. */
internal fun writeAtomic(file: File, bytes: ByteArray) {
    val dir = file.absoluteFile.parentFile
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("$dir laesst sich nicht anlegen")
    val tmp = File.createTempFile(file.name, ".tmp", dir)
    try {
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        tmp.delete()
    }
}
