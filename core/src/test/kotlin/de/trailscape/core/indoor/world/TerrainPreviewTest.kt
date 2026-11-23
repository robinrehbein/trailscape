package de.trailscape.core.indoor.world

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vorschau: Mini-Welt aus Gelaende und erzeugter Bodentextur ueber
 * gemischter Landnutzung. Ergebnis unter `core/build/indoor-preview/terrain/`
 * (Manifest, Kacheln, dazu die Bodentexturen als PNG) — anzusehen mit
 * `node shots.mjs <dir> --cameras chase,drone --no-rider`.
 */
class TerrainPreviewTest {
    private val r = TerrainTestSupport::rect
    private fun blob(ce: Double, cn: Double, rr: Double, seed: Int) = TerrainTestSupport.blob(ce, cn, rr, seed)

    /** Synthetische Kulturlandschaft rund um [PreviewSupport.curvyTrack]. */
    private fun landscape(): MapData {
        val areas = ArrayList<MapArea>()
        areas += MapArea(listOf(r(60.0, -420.0, 520.0, 40.0)), LandClass.FARMLAND, "farmland")
        areas += MapArea(listOf(r(-480.0, -480.0, -40.0, -170.0)), LandClass.FARMLAND, "farmland")
        areas += MapArea(listOf(blob(-170.0, 140.0, 150.0, 3)), LandClass.FOREST, "wood")
        areas += MapArea(listOf(blob(330.0, -120.0, 70.0, 9)), LandClass.FOREST, "needleleaved")
        areas += MapArea(listOf(r(-40.0, 380.0, 120.0, 520.0)), LandClass.FOREST, "wood")
        areas += MapArea(listOf(r(150.0, 150.0, 400.0, 340.0)), LandClass.RESIDENTIAL, "residential")
        areas += MapArea(listOf(r(160.0, 345.0, 230.0, 420.0)), LandClass.PARK, "park")
        areas += MapArea(listOf(blob(-40.0, 300.0, 48.0, 5)), LandClass.WATER, "lake")
        areas += MapArea(listOf(r(-300.0, -120.0, -120.0, -40.0)), LandClass.MEADOW, "grass")
        areas += MapArea(listOf(r(420.0, 150.0, 520.0, 300.0)), LandClass.INDUSTRIAL, "industrial")
        areas += MapArea(listOf(blob(-330.0, 330.0, 60.0, 11)), LandClass.SCRUB, "scrub")
        val buildings = ArrayList<MapBuilding>()
        var id = 1L
        for (i in 0 until 9) for (j in 0 until 5) {
            val e = 165.0 + i * 26.0 + (j % 2) * 6
            val n = 160.0 + j * 34.0
            if (hypot(e - 270, n - 250) < 30) continue
            buildings += MapBuilding(id++, listOf(r(e, n, e + 11.0 + (i % 3), n + 9.0 + (j % 2) * 3)), 8.0)
        }
        for (k in 0 until 4) buildings += MapBuilding(id++, listOf(r(430.0 + k * 22, 170.0, 448.0 + k * 22, 230.0)), 12.0)
        // Hof am Feldrand.
        buildings += MapBuilding(id++, listOf(r(40.0, -140.0, 64.0, -128.0)), 9.0)
        buildings += MapBuilding(id++, listOf(r(70.0, -150.0, 80.0, -120.0)), 7.0)
        val roads = listOf(
            MapRoad(doubleArrayOf(-500.0, 60.0, -100.0, 70.0, 140.0, 120.0, 520.0, 130.0), "tertiary"),
            MapRoad(doubleArrayOf(160.0, 155.0, 400.0, 155.0), "minor"),
            MapRoad(doubleArrayOf(160.0, 223.0, 400.0, 223.0), "minor"),
            MapRoad(doubleArrayOf(160.0, 290.0, 400.0, 290.0), "minor"),
            MapRoad(doubleArrayOf(270.0, 120.0, 270.0, 340.0), "minor"),
            MapRoad(doubleArrayOf(200.0, -420.0, 230.0, -200.0, 300.0, 40.0), "track", surface = "unpaved"),
            MapRoad(doubleArrayOf(-480.0, -175.0, -40.0, -175.0), "track", surface = "unpaved"),
            MapRoad(doubleArrayOf(-200.0, 20.0, -150.0, 140.0, -120.0, 280.0), "path"),
            MapRoad(doubleArrayOf(52.0, -134.0, 120.0, -60.0), "service"),
        )
        val waterways = listOf(MapWaterway(doubleArrayOf(-40.0, 250.0, -80.0, 150.0, -60.0, 0.0, -120.0, -200.0, -150.0, -480.0), "stream"))
        return ListMapData(buildings, roads, areas, waterways)
    }

    @Test
    fun `Gelaende mit erzeugter Bodentextur ueber gemischter Landnutzung`() {
        val map = landscape()
        val land = LandRaster(map)
        val track = PreviewSupport.curvyTrack(lengthM = 700.0)
        val line = TrackLine(track)
        val quality = IndoorQuality.MEDIUM
        val raw = RawHeights { e, n -> PreviewSupport.hills.heightAt(e, n) }
        val ground = CarvedGround(raw, line, map, level0TerrainCellM(quality), land)
        val dir = File("build/indoor-preview/terrain")
        dir.deleteRecursively()
        File(dir, WorldManifest.TILES_DIR).mkdirs()

        val specs = LinkedHashSet<TileSpec>()
        for (i in 0 until track.sampleCount) {
            for (de in listOf(-150.0, 0.0, 150.0)) for (dn in listOf(-150.0, 0.0, 150.0)) {
                specs.add(TileSpec.at(0, track.e[i] + de, track.n[i] + dn))
            }
        }
        val tiles = ArrayList<WorldTile>()
        val textures = TextureCache()
        val t0 = System.nanoTime()
        // Stufe 1 darunter, fuer den Blick aus der Drohne bis zum Horizont.
        val l1 = specs.map { TileSpec(1, Math.floorDiv(it.ix, 4), Math.floorDiv(it.iy, 4)) }.toSet()
        for (spec in specs + l1) {
            val anchorU = ground.heightAt(spec.anchorE, spec.anchorN)
            val ctx = TileContext(spec, PreviewSupport.origin, quality, anchorU, ground, line, map, land, textures, TileBudget.of(quality, spec.level))
            val out = TileOutput(spec)
            TerrainLayer().build(ctx, out)
            val scene = out.toGlb()
            val bytes = scene.toBytes()
            assertEquals(emptyList(), checkTileGlb(bytes), "Kachel ${spec.id}")
            val file = "${WorldManifest.TILES_DIR}/${spec.id}.glb"
            File(dir, file).writeBytes(bytes)
            val info = parseGlb(bytes)
            tiles += WorldTile(
                id = spec.id, level = spec.level, ix = spec.ix, iy = spec.iy, file = file, bytes = bytes.size.toLong(),
                anchorE = spec.anchorE, anchorN = spec.anchorN, anchorU = anchorU,
                minU = anchorU + (info.boundsMin?.get(1) ?: 0.0), maxU = anchorU + (info.boundsMax?.get(1) ?: 0.0),
                show = listOf(ShowInterval(0.0, track.lengthM)), textureBytes = scene.textureGpuBytes(), triangles = scene.triangleCount,
            )
        }
        val secs = (System.nanoTime() - t0) / 1e9
        println("Vorschau: ${tiles.size} Kacheln in ${"%.1f".format(secs)} s")
        // Bodentexturen zum Ansehen.
        for (spec in listOf(TileSpec(0, -1, 0), TileSpec(0, 0, 0), TileSpec(0, -1, -1), TileSpec(0, 0, -1), TileSpec(1, -1, -1), TileSpec(1, 0, 0))) {
            val img = groundTexture(spec, land, map, line, quality.texturePx(spec.level))
            val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
            bi.setRGB(0, 0, img.width, img.height, img.argb, 0, img.width)
            ImageIO.write(bi, "png", File(dir, "ground_${spec.id}.png"))
        }
        val manifest = WorldManifest(
            worldId = "preview-terrain", builder = "preview", createdAtMs = 0, quality = quality,
            routeId = "preview", routeName = "Vorschau Gelaende", origin = PreviewSupport.origin,
            bounds = WorldBounds(
                tiles.minOf { it.anchorE - worldCellM(it.level) / 2 }, tiles.minOf { it.anchorN - worldCellM(it.level) / 2 },
                tiles.maxOf { it.anchorE + worldCellM(it.level) / 2 }, tiles.maxOf { it.anchorN + worldCellM(it.level) / 2 },
                tiles.minOf { it.minU }, tiles.maxOf { it.maxU },
            ),
            track = track,
            levels = (0 until WORLD_LEVEL_COUNT).map { WorldLevel(it, worldCellM(it), quality.texturePx(it)) },
            tiles = tiles,
            budget = WorldBudget(tiles.sumOf { it.textureBytes }, tiles.sumOf { it.triangles }, tiles.sumOf { it.bytes }),
            attribution = listOf(WorldAttribution(GeoSourceKind.VECTOR, "Erfundene Vorschau-Welt", "–", "https://example.invalid")),
        )
        File(dir, WorldManifest.FILE_NAME).writeText(manifest.toJson())
        assertTrue(tiles.count { it.level == 0 } >= 6)
    }

    @Test
    fun `Bodentextur ist ueber Kachelgrenzen nahtlos`() {
        val map = landscape()
        val land = LandRaster(map)
        val line = TrackLine(PreviewSupport.curvyTrack(lengthM = 700.0))
        val a = TileSpec(0, 0, 0)
        val b = TileSpec(0, 1, 0)
        val ia = groundTexture(a, land, map, line, 512)
        val ib = groundTexture(b, land, map, line, 512)
        // Letzte Spalte von a und erste von b sind Nachbarpixel: im Mittel nah beieinander,
        // so wie zwei Nachbarspalten innerhalb einer Kachel.
        fun colDiff(x0: RgbaImage, cx0: Int, x1: RgbaImage, cx1: Int): Double {
            var s = 0.0
            for (y in 0 until 512) {
                val p = x0[cx0, y]
                val q = x1[cx1, y]
                s += kotlin.math.abs(Argb.red(p) - Argb.red(q)) + kotlin.math.abs(Argb.green(p) - Argb.green(q)) + kotlin.math.abs(Argb.blue(p) - Argb.blue(q))
            }
            return s / 512
        }
        val across = colDiff(ia, 511, ib, 0)
        val inside = colDiff(ia, 510, ia, 511)
        assertTrue(across < inside * 1.5 + 3, "Naht: $across gegen $inside innerhalb")
    }
}
