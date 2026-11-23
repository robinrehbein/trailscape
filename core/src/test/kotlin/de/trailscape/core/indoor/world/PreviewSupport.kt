package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals

/**
 * Testhilfe der Erzeuger: baut ohne Netz eine Mini-Welt (ein paar Kacheln der
 * Stufe 0 um eine kurze erfundene Strecke) aus beliebigen [TileLayer] und
 * schreibt sie nach `core/build/indoor-preview/<name>/`. Der Betrachter
 * (`tool/indoor-dev/viewer/shots.mjs`) zeigt sie; jede Kachel wird dabei mit
 * [checkTileGlb] gegen den Vertrag geprueft.
 */
internal object PreviewSupport {
    val origin = GeoOrigin(51.0575, 7.1485, 120.0)

    /** Sanfte Huegel (rund ±12 m auf 400 m). */
    val hills = GroundSurface { e, n ->
        6.0 * sin(e / 130.0) * cos(n / 170.0) + 4.0 * sin((e + n) / 90.0) + 0.02 * e
    }

    /**
     * Eine geschwungene Strecke von [lengthM] Metern durch den Ursprung nach
     * Nordosten, Hoehe auf [base] gelegt und geglaettet. [surfaces] werden
     * gleichmaessig ueber die Laenge verteilt.
     */
    fun curvyTrack(
        lengthM: Double = 600.0,
        base: GroundSurface = hills,
        surfaces: List<SurfaceKind> = listOf(SurfaceKind.ASPHALT, SurfaceKind.GRAVEL, SurfaceKind.DIRT),
    ): WorldTrack {
        val step = 5.0
        val count = (lengthM / step).toInt() + 1
        val e = FloatArray(count)
        val n = FloatArray(count)
        var x = -lengthM * 0.3
        var y = -lengthM * 0.3
        for (i in 0 until count) {
            e[i] = x.toFloat()
            n[i] = y.toFloat()
            val heading = PI / 4 + 0.7 * sin(i * step / 90.0)
            x += sin(heading) * step
            y += cos(heading) * step
        }
        val raw = DoubleArray(count) { base.heightAt(e[it].toDouble(), n[it].toDouble()) }
        val u = FloatArray(count) { i ->
            var s = 0.0
            var c = 0
            for (k in -6..6) {
                val j = (i + k).coerceIn(0, count - 1)
                s += raw[j]
                c++
            }
            (s / c).toFloat()
        }
        val len = (count - 1) * step
        val part = len / surfaces.size
        val sections = surfaces.mapIndexed { i, k -> SurfaceSection(i * part, if (i == surfaces.size - 1) len else (i + 1) * part, k) }
        return WorldTrack(len, step, false, e, n, u, sections, IndoorRouteSource.NONE)
    }

    /** [base], entlang der Strecke auf Fahrbahnhoehe eingeebnet (weicher Uebergang ueber 12 m). */
    fun carved(base: GroundSurface, track: TrackLine): GroundSurface = GroundSurface { e, n ->
        val h = base.heightAt(e, n)
        val hit = track.nearest(e, n, 40.0) ?: return@GroundSurface h
        val hw = track.halfWidthAt(hit.distM)
        val t = smoothstep(hw + 1.5, hw + 13.5, hit.distance)
        hit.u - 0.12 + (h - (hit.u - 0.12)) * t
    }

    /** Einfarbiges Gelaende als Platzhalter, solange eine Vorschau kein echtes Gelaende braucht. */
    val plainTerrain = TileLayer { ctx, out ->
        val m = out.mesh(WorldNames.NODE_TERRAIN, TileMaterial(WorldNames.MAT_TERRAIN, baseColor = floatArrayOf(0.30f, 0.42f, 0.20f, 1f)))
        val cells = 64
        val s = ctx.spec
        for (j in 0..cells) for (i in 0..cells) {
            val e = s.minE + s.cellM * i / cells
            val n = s.minN + s.cellM * j / cells
            val nor = ctx.ground.normalAt(e, n, 2.0)
            m.vertex(ctx.x(e), ctx.y(ctx.ground.heightAt(e, n)), ctx.z(n), nor.x, nor.y, nor.z, i / cells.toDouble(), 1 - j / cells.toDouble())
        }
        for (j in 0 until cells) for (i in 0 until cells) {
            val a = j * (cells + 1) + i
            m.quad(a, a + 1, a + cells + 2, a + cells + 1)
        }
    }

    /**
     * Schreibt die Mini-Welt und liefert ihr Verzeichnis. Gebaut werden alle
     * Kacheln der Stufe 0, die naeher als [corridorM] an der Strecke liegen.
     */
    fun write(
        name: String,
        layers: List<TileLayer>,
        track: WorldTrack = curvyTrack(),
        base: GroundSurface = hills,
        map: MapData = MapData.EMPTY,
        land: LandMap = object : LandMap {
            override fun classAt(e: Double, n: Double) = LandClass.MEADOW
        },
        quality: IndoorQuality = IndoorQuality.MEDIUM,
        corridorM: Double = 120.0,
        aerial: (TileSpec) -> RgbaImage? = { null },
    ): File {
        val dir = File("build/indoor-preview/$name")
        dir.deleteRecursively()
        File(dir, WorldManifest.TILES_DIR).mkdirs()
        val line = TrackLine(track)
        val ground = carved(base, line)
        val textures = TextureCache()
        val specs = LinkedHashSet<TileSpec>()
        for (i in 0 until track.sampleCount) {
            val e = track.e[i].toDouble()
            val n = track.n[i].toDouble()
            var de = -corridorM
            while (de <= corridorM) {
                var dn = -corridorM
                while (dn <= corridorM) {
                    if (hypot(de, dn) <= corridorM) specs.add(TileSpec.at(0, e + de, n + dn))
                    dn += 32.0
                }
                de += 32.0
            }
        }
        val tiles = ArrayList<WorldTile>()
        for (spec in specs) {
            val anchorU = ground.heightAt(spec.anchorE, spec.anchorN)
            val ctx = TileContext(
                spec, origin, quality, anchorU, ground, line, map, land, textures,
                TileBudget.of(quality, 0), aerial(spec),
            )
            val out = TileOutput(spec)
            for (l in layers) l.build(ctx, out)
            val scene = out.toGlb()
            val bytes = scene.toBytes()
            assertEquals(emptyList(), checkTileGlb(bytes), "Kachel ${spec.id} verstoesst gegen den Vertrag")
            val file = "${WorldManifest.TILES_DIR}/${spec.id}.glb"
            File(dir, file).writeBytes(bytes)
            val info = parseGlb(bytes)
            tiles.add(
                WorldTile(
                    id = spec.id, level = 0, ix = spec.ix, iy = spec.iy, file = file, bytes = bytes.size.toLong(),
                    anchorE = spec.anchorE, anchorN = spec.anchorN, anchorU = anchorU,
                    minU = anchorU + (info.boundsMin?.get(1) ?: 0.0), maxU = anchorU + (info.boundsMax?.get(1) ?: 0.0),
                    show = listOf(ShowInterval(0.0, track.lengthM)),
                    textureBytes = scene.textureGpuBytes(), triangles = scene.triangleCount,
                ),
            )
        }
        val manifest = WorldManifest(
            worldId = "preview-$name", builder = "preview", createdAtMs = 0, quality = quality,
            routeId = "preview", routeName = "Vorschau $name", origin = origin,
            bounds = WorldBounds(
                specs.minOf { it.minE }, specs.minOf { it.minN }, specs.maxOf { it.maxE }, specs.maxOf { it.maxN },
                tiles.minOf { it.minU }, tiles.maxOf { it.maxU },
            ),
            track = track,
            levels = (0 until WORLD_LEVEL_COUNT).map { WorldLevel(it, worldCellM(it), quality.texturePx(it)) },
            tiles = tiles,
            budget = WorldBudget(tiles.sumOf { it.textureBytes }, tiles.sumOf { it.triangles }, tiles.sumOf { it.bytes }),
            attribution = listOf(WorldAttribution(GeoSourceKind.VECTOR, "Erfundene Vorschau-Welt", "–", "https://example.invalid")),
        )
        File(dir, WorldManifest.FILE_NAME).writeText(manifest.toJson())
        return dir
    }
}
