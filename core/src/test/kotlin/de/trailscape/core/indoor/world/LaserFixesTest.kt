package de.trailscape.core.indoor.world

import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Befunde der Fahrt auf „solitude" (Welt-Version 9), `docs/indoor/lidar.md`, Abschnitt 9. */
class LaserFixesTest {
    private val scene = RouteTestScene.countryRoad()

    /**
     * Befund 2: Im Scheitel einer Kurve liegt der Knoten der Karte neben der geglaetteten Strecke und quer zu ihr.
     * Das Endstueck des eigenen Wegs darf weder als Strasse ueber die Fahrbahn gezeichnet werden noch Ecken bekommen.
     */
    @Test
    fun `Endstueck des eigenen Wegs im Kurvenscheitel ist kein Abzweig`() {
        val t = scene.track
        val line = scene.line
        val pts = ArrayList<Double>()
        var d = 0.0
        while (d <= 196.0) {
            val p = t.poseAt(d)
            pts.add(p.e); pts.add(p.n)
            d += 14.0
        }
        val p = t.poseAt(200.0)
        val h = Math.toRadians(p.headingDeg + 90.0)
        val off = line.halfWidthAt(200.0) + 0.6
        pts.add(p.e + kotlin.math.sin(h) * off); pts.add(p.n + kotlin.math.cos(h) * off)
        val s = RouteTestScene(t, listOf(RouteTestScene.Zone(0.0, LandClass.MEADOW, LandClass.MEADOW)), listOf(MapRoad(pts.toDoubleArray(), "service", null, "unpaved")), emptyList())
        var drawn = 0
        var corners = 0
        for (spec in s.tiles()) {
            val ctx = s.context(spec, IndoorQuality.MEDIUM, TileBudget.of(IndoorQuality.MEDIUM, 0))
            val out = TileOutput(spec)
            RoadsLayer().build(ctx, out)
            drawn += out.triangles(WorldNames.NODE_ROADS)
            corners += JunctionCorners.find(ctx.track, ctx.land, RoadNet(ctx, marginM = 30.0, maxStepM = RoadsLayer.STEP_M)).size
        }
        assertEquals(0, corners, "Ecken am Endstueck")
        assertEquals(0, drawn, "Endstueck als Strasse gezeichnet")
    }

    /** Befund 3: Keine Blattkarte eines grossen Laubbaums ist laenger als die Grenzen (keine „Federn"). */
    @Test
    fun `grosse Kronen haben keine Riesenkarten und fuellen die Kronenhoehe`() {
        for (lod in TreeModels.Lod.entries) for (species in listOf(TreeModels.Species.BROAD, TreeModels.Species.TALL, TreeModels.Species.PINE)) {
            val spec = TreeModels.Spec(species, 30.0, 1.4, 1.0, 7, 0.3, false, 0.22)
            val trunk = MeshBuilder(64, 64)
            val crown = MeshBuilder(256, 256)
            TreeModels.build(spec, lod, trunk, crown)
            var longest = 0.0
            var minY = Double.MAX_VALUE
            for (k in 0 until crown.triangleCount) {
                val v = IntArray(3) { crown.indices[k * 3 + it] }
                // Kerne der Kronenlappen (Texturkoordinaten im deckenden Innern der Laubmasse) sind keine Karten
                if (v.all { i -> isCore(crown, i) }) continue
                for (a in 0..2) {
                    val p = v[a]; val q = v[(a + 1) % 3]
                    val l = hypot(hypot((crown.x(p) - crown.x(q)).toDouble(), (crown.y(p) - crown.y(q)).toDouble()), (crown.z(p) - crown.z(q)).toDouble())
                    longest = max(longest, l)
                }
            }
            for (i in 0 until crown.vertexCount) minY = minOf(minY, crown.y(i).toDouble())
            // Diagonale einer quadratischen Karte: fern hoechstens 6,5 m Kante, sonst 4,5 m × 1,35.
            assertTrue(longest <= 6.5 * 1.42 + 0.01, "$species $lod: Karte ${"%.1f".format(longest)} m")
            if (species == TreeModels.Species.BROAD) assertTrue(minY < 0.45 * 30.0, "$species $lod: Krone beginnt erst bei ${"%.1f".format(minY)} m")
        }
    }

    private fun isCore(m: MeshBuilder, i: Int): Boolean {
        val u = m.uvs[i * 2].toDouble()
        val v = m.uvs[i * 2 + 1].toDouble()
        val r = FoliageAtlas.MASS
        return u > r.u(0.29) && u < r.u(0.71) && v < r.v(0.29) && v > r.v(0.71)
    }

    /** Befund 4: Unter dichtem Bewuchs wird das Luftbild zum Waldboden abgetoent, ohne Bewuchs bleibt es unveraendert. */
    @Test
    fun `Waldboden nur unter dichtem Bewuchs`() {
        val spec = TileSpec(0, 0, 0)
        val img = RgbaImage(64, 64, IntArray(64 * 64) { Argb.rgb(120, 140, 170) })
        val open = object : LandMap {
            override fun classAt(e: Double, n: Double) = LandClass.MEADOW
            override fun canopyAt(e: Double, n: Double) = 0.0
        }
        assertNull(ForestFloor.apply(img, spec, open))
        val forest = object : LandMap {
            override fun classAt(e: Double, n: Double) = LandClass.FOREST
            override fun canopyAt(e: Double, n: Double) = if (e < 128.0) 1.0 else 0.0
        }
        val (out, tex) = assertNotNull(ForestFloor.apply(img, spec, forest))
        assertEquals("image/jpeg", tex.mime)
        val left = out[8, 32]
        val right = out[56, 32]
        // Links Waldboden: rot vor blau (Laubstreu), rechts unveraendert graublau.
        assertTrue(Argb.red(left) > Argb.blue(left), "Waldboden ${Integer.toHexString(left)}")
        assertEquals(img[56, 32], right)
    }
}
