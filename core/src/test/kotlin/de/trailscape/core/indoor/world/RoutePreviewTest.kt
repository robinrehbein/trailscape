package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Vorschau der Weg-Schichten: eine Strecke von 900 m mit allen zehn Belaegen
 * hintereinander, wechselnder Landnutzung, Querstrassen und ein paar Haeusern,
 * dazu eine Landstrasse, die in einen Ort fuehrt. Schreibt die Mini-Welten
 * nach `core/build/indoor-preview/route…/`; angesehen werden sie mit
 * `tool/indoor-dev/viewer/shots.mjs`.
 */
class RoutePreviewTest {
    private val layers = listOf(RouteTestScene.aerialTerrain, RouteLayer(), RoadsLayer(), PropsLayer())

    @Test
    fun `Vorschau mit allen Belaegen wird geschrieben`() {
        val scene = RouteTestScene.allSurfaces()
        val dir = PreviewSupport.write("route", layers, track = scene.track, map = scene.map, land = scene.land, aerial = scene::aerial)
        assertTrue(File(dir, WorldManifest.FILE_NAME).isFile)
        assertTrue(File(dir, WorldManifest.TILES_DIR).listFiles()!!.isNotEmpty())
    }

    @Test
    fun `Vorschau einer Landstrasse wird geschrieben`() {
        val scene = RouteTestScene.countryRoad()
        val dir = PreviewSupport.write("route-road", layers, track = scene.track, map = scene.map, land = scene.land, aerial = scene::aerial)
        assertTrue(File(dir, WorldManifest.FILE_NAME).isFile)
    }
}

/** Erfundene Szenen fuer Vorschau und Tests der Weg-Schichten. */
internal class RouteTestScene(
    val track: WorldTrack,
    private val zones: List<Zone>,
    private val roadList: List<MapRoad>,
    private val houses: List<MapBuilding>,
) {
    /** Landnutzung links und rechts der Strecke ab Laufmeter [fromM]. */
    class Zone(val fromM: Double, val left: LandClass, val right: LandClass)

    val line = TrackLine(track)
    val ground = PreviewSupport.carved(PreviewSupport.hills, line)
    private val textures = TextureCache()

    /** Kontext einer Kachel wie in [PreviewSupport.write], fuer Tests einzelner Schichten. */
    fun context(
        spec: TileSpec,
        quality: IndoorQuality = IndoorQuality.MEDIUM,
        budget: TileBudget = TileBudget.of(quality, 0),
        withAerial: Boolean = false,
    ): TileContext = TileContext(
        spec, PreviewSupport.origin, quality, ground.heightAt(spec.anchorE, spec.anchorN), ground, line, map, land, textures, budget,
        if (withAerial) aerial(spec) else null,
    )

    /** Alle Kacheln der Stufe 0, durch die die Strecke laeuft. */
    fun tiles(): List<TileSpec> =
        (0 until track.sampleCount).map { TileSpec.at(0, track.e[it].toDouble(), track.n[it].toDouble()) }.distinct()

    val land = object : LandMap {
        override fun classAt(e: Double, n: Double): LandClass {
            val hit = line.nearest(e, n, 400.0) ?: return LandClass.MEADOW
            val z = zones.last { it.fromM <= hit.distM }
            return if (hit.lateral < 0) z.left else z.right
        }
    }

    val map = object : MapData {
        override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = houses.filter { b ->
            val r = b.rings[0]
            (0 until r.size / 2).any { r[2 * it] in minE..maxE && r[2 * it + 1] in minN..maxN }
        }

        override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = roadList.filter { r ->
            var x0 = Double.MAX_VALUE
            var x1 = -Double.MAX_VALUE
            var y0 = Double.MAX_VALUE
            var y1 = -Double.MAX_VALUE
            for (i in 0 until r.line.size / 2) {
                x0 = minOf(x0, r.line[2 * i]); x1 = maxOf(x1, r.line[2 * i])
                y0 = minOf(y0, r.line[2 * i + 1]); y1 = maxOf(y1, r.line[2 * i + 1])
            }
            x1 >= minE && x0 <= maxE && y1 >= minN && y0 <= maxN
        }

        override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapArea>()
        override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapWaterway>()
    }

    /** Ein erfundenes „Luftbild" der Kachel (50 cm je Pixel): Farbe je Landnutzung, fleckig, die Strecke als heller Strich. */
    fun aerial(spec: TileSpec): RgbaImage {
        val px = 512
        val img = RgbaImage(px, px)
        for (y in 0 until px) for (x in 0 until px) {
            val e = spec.minE + (x + 0.5) * spec.cellM / px
            val n = spec.maxN - (y + 0.5) * spec.cellM / px
            val base = when (land.classAt(e, n)) {
                LandClass.FOREST -> intArrayOf(52, 74, 44)
                LandClass.FARMLAND -> intArrayOf(168, 150, 96)
                LandClass.RESIDENTIAL -> intArrayOf(120, 128, 104)
                LandClass.WETLAND, LandClass.WATER -> intArrayOf(78, 96, 70)
                LandClass.SCRUB -> intArrayOf(92, 108, 68)
                else -> intArrayOf(98, 124, 66)
            }
            val v = 1.0 + 0.16 * Noise.fbm(e / 14.0, n / 14.0, 77, 4) + 0.08 * Noise.fbm(e / 2.5, n / 2.5, 78, 2)
            var r = base[0] * v
            var g = base[1] * v
            var b = base[2] * v
            val hit = line.nearest(e, n, 12.0)
            if (hit != null) {
                // Der echte Weg ist im Luftbild als verwaschener Strich zu sehen.
                val hw = line.halfWidthAt(hit.distM)
                val w = 1.0 - smoothstep(hw - 0.5, hw + 0.8, hit.distance)
                r += (150 - r) * w * 0.7
                g += (145 - g) * w * 0.7
                b += (135 - b) * w * 0.7
            }
            img[x, y] = Argb.rgb(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
        return img
    }

    companion object {
        /** Gelaende mit dem Luftbild der Kachel als Bodentextur (wie das echte Gelaende, nur grob). */
        val aerialTerrain = TileLayer { ctx, out ->
            val img = ctx.aerial
            val tex = if (img == null) null else ctx.textures.get("aerial_${ctx.spec.id}") { img }
            val mat = TileMaterial(
                WorldNames.MAT_TERRAIN, tex, repeat = false, roughness = 1f,
                baseColor = if (tex == null) floatArrayOf(0.30f, 0.42f, 0.20f, 1f) else floatArrayOf(1f, 1f, 1f, 1f),
            )
            val m = out.mesh(WorldNames.NODE_TERRAIN, mat)
            val cells = 96
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

        /** Gerade Querstrasse durch die Strecke am Laufmeter [distM], [halfLen] Meter nach jeder Seite. */
        fun crossRoad(
            track: WorldTrack, distM: Double, cls: String, surface: String?,
            halfLen: Double = 70.0, subclass: String? = null, skewDeg: Double = 0.0,
        ): MapRoad {
            val p = track.poseAt(distM)
            val h = Math.toRadians(p.headingDeg + 90.0 + skewDeg)
            val de = sin(h)
            val dn = cos(h)
            return MapRoad(doubleArrayOf(p.e - de * halfLen, p.n - dn * halfLen, p.e, p.n, p.e + de * halfLen, p.n + dn * halfLen), cls, subclass, surface)
        }

        /** Die Strecke selbst als Strasse der Karte (so steht sie in echten Daten), leicht verrauscht. */
        fun trackAsRoad(track: WorldTrack, cls: String, from: Int = 0, to: Int = track.sampleCount - 1, jitter: Double = 0.4): MapRoad {
            val pts = ArrayList<Double>()
            var i = from
            while (i <= to) {
                pts.add(track.e[i] + jitter * (Noise.hash01(i, 1, 5) - 0.5))
                pts.add(track.n[i] + jitter * (Noise.hash01(i, 2, 5) - 0.5))
                i += 3
            }
            return MapRoad(pts.toDoubleArray(), cls, null, null)
        }

        /** Rechteckiges Haus neben der Strecke: [side] −1 links, +1 rechts, [gap] Meter Abstand zur Mitte. */
        fun house(track: WorldTrack, distM: Double, side: Int, gap: Double, id: Long, w: Double = 10.0, d: Double = 8.0): MapBuilding {
            val p = track.poseAt(distM)
            val h = Math.toRadians(p.headingDeg)
            val te = sin(h)
            val tn = cos(h)
            val re = tn * side
            val rn = -te * side
            val ce = p.e + re * (gap + d / 2)
            val cn = p.n + rn * (gap + d / 2)
            fun c(a: Double, b: Double) = listOf(ce + te * a + re * b, cn + tn * a + rn * b)
            val ring = (c(-w / 2, -d / 2) + c(w / 2, -d / 2) + c(w / 2, d / 2) + c(-w / 2, d / 2)).toDoubleArray()
            return MapBuilding(id, listOf(ring), 7.0)
        }

        /** 900 m mit allen zehn Belaegen (je 90 m), Wiese, Wald, Acker und Ort. */
        fun allSurfaces(): RouteTestScene {
            val kinds = listOf(
                SurfaceKind.ASPHALT, SurfaceKind.COMPACTED, SurfaceKind.GRAVEL, SurfaceKind.DIRT, SurfaceKind.GRASS,
                SurfaceKind.SAND, SurfaceKind.WOOD, SurfaceKind.CONCRETE, SurfaceKind.PAVING_STONES, SurfaceKind.COBBLESTONE,
            )
            val raw = PreviewSupport.curvyTrack(lengthM = 900.0, surfaces = kinds)
            val meta = mapOf(
                SurfaceKind.ASPHALT to ("secondary" to 6.5), SurfaceKind.COMPACTED to ("track" to 3.0), SurfaceKind.GRAVEL to ("track" to 3.0),
                SurfaceKind.DIRT to ("path" to 1.5), SurfaceKind.GRASS to ("track" to 2.6), SurfaceKind.SAND to ("path" to 2.0),
                SurfaceKind.WOOD to ("path" to 2.5), SurfaceKind.CONCRETE to ("unclassified" to 3.5),
                SurfaceKind.PAVING_STONES to ("residential" to 4.5), SurfaceKind.COBBLESTONE to ("residential" to 5.0),
            )
            val track = raw.copy(
                surfaces = raw.surfaces.map { s -> SurfaceSection(s.fromM, s.toM, s.kind, meta.getValue(s.kind).first, meta.getValue(s.kind).second) },
            )
            val zones = listOf(
                Zone(0.0, LandClass.MEADOW, LandClass.FARMLAND), Zone(90.0, LandClass.MEADOW, LandClass.MEADOW),
                Zone(180.0, LandClass.FARMLAND, LandClass.MEADOW), Zone(250.0, LandClass.FOREST, LandClass.FOREST),
                Zone(360.0, LandClass.MEADOW, LandClass.FOREST), Zone(450.0, LandClass.SCRUB, LandClass.FOREST),
                Zone(540.0, LandClass.WETLAND, LandClass.WETLAND), Zone(630.0, LandClass.FARMLAND, LandClass.MEADOW),
                Zone(710.0, LandClass.RESIDENTIAL, LandClass.RESIDENTIAL),
            )
            val roads = listOf(
                trackAsRoad(track, "minor"),
                crossRoad(track, 45.0, "minor", "paved"), crossRoad(track, 150.0, "track", "unpaved", skewDeg = 25.0),
                crossRoad(track, 300.0, "path", "unpaved"), crossRoad(track, 420.0, "track", null, skewDeg = -20.0),
                crossRoad(track, 680.0, "service", "paved"), crossRoad(track, 770.0, "minor", "paved"),
                crossRoad(track, 850.0, "path", "paved", subclass = "footway"),
            )
            val houses = listOf(
                house(track, 735.0, 1, 7.0, 1), house(track, 750.0, -1, 8.0, 2), house(track, 800.0, 1, 6.5, 3),
                house(track, 815.0, -1, 7.5, 4), house(track, 840.0, 1, 7.0, 5), house(track, 880.0, -1, 7.0, 6),
            )
            return RouteTestScene(track, zones, roads, houses)
        }

        /** 700 m: Landstrasse (Asphalt, 6,5 m), die in einen Ort fuehrt und als Feldweg endet. */
        fun countryRoad(): RouteTestScene {
            val raw = PreviewSupport.curvyTrack(lengthM = 700.0, surfaces = listOf(SurfaceKind.ASPHALT))
            val track = raw.copy(
                surfaces = listOf(
                    SurfaceSection(0.0, 330.0, SurfaceKind.ASPHALT, "secondary", 6.5),
                    SurfaceSection(330.0, 520.0, SurfaceKind.ASPHALT, "residential", 5.5),
                    SurfaceSection(520.0, raw.lengthM, SurfaceKind.GRAVEL, "track", 3.0),
                ),
            )
            val zones = listOf(
                Zone(0.0, LandClass.MEADOW, LandClass.FARMLAND), Zone(160.0, LandClass.FOREST, LandClass.MEADOW),
                Zone(320.0, LandClass.RESIDENTIAL, LandClass.RESIDENTIAL), Zone(520.0, LandClass.MEADOW, LandClass.MEADOW),
                Zone(610.0, LandClass.FOREST, LandClass.FOREST),
            )
            val roads = listOf(
                trackAsRoad(track, "secondary"), crossRoad(track, 120.0, "track", "unpaved", skewDeg = 15.0),
                crossRoad(track, 250.0, "tertiary", "paved"), crossRoad(track, 420.0, "minor", "paved"),
                crossRoad(track, 580.0, "path", null),
            )
            val houses = (0 until 8).map { k -> house(track, 345.0 + k * 21.0, if (k % 2 == 0) 1 else -1, 7.5 + (k % 3), k + 1L, w = 9.0 + k % 4) }
                .filter { b -> roads.drop(1).all { r -> distanceToLine(r.line, b.rings[0][0], b.rings[0][1]) > 12.0 } }
            return RouteTestScene(track, zones, roads, houses)
        }
    }
}
