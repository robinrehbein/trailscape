package de.trailscape.core.indoor.world

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainLayerTest {
    private val raw = RawHeights { e, n -> PreviewSupport.hills.heightAt(e, n) + 0.12 * n }
    private val track = TrackLine(fitTrack(
        TerrainTestSupport.routeFromEnu(
            TerrainTestSupport.polyline(-200.0, -150.0, 60.0, -40.0, 140.0, 20.0, 170.0, 60.0, 150.0, 110.0, -40.0, 230.0, -120.0, 400.0),
            surfaces = listOf(de.trailscape.core.indoor.SurfaceKind.ASPHALT, de.trailscape.core.indoor.SurfaceKind.GRAVEL),
        ),
        TerrainTestSupport.origin, raw,
    ))

    /** Billige Einheitstextur, damit die Geometrie-Tests schnell bleiben. */
    private val flatTexture: (TileContext) -> EncodedTexture = { ctx ->
        val px = ctx.quality.texturePx(ctx.level)
        EncodedTexture(encodeJpegBaseline(RgbaImage(px, px).also { it.fill(Argb.rgb(100, 120, 80)) }, 60), "image/jpeg", px, px)
    }

    private fun ctx(spec: TileSpec, ground: GroundSurface, quality: IndoorQuality = IndoorQuality.MEDIUM): TileContext {
        val land = object : LandMap {
            override fun classAt(e: Double, n: Double) = LandClass.MEADOW
        }
        return TileContext(
            spec, TerrainTestSupport.origin, quality, ground.heightAt(spec.anchorE, spec.anchorN), ground, track,
            MapData.EMPTY, land, TextureCache(), TileBudget.of(quality, spec.level),
        )
    }

    private fun ground(q: IndoorQuality = IndoorQuality.MEDIUM) = CarvedGround(raw, track, terrainCellM = level0TerrainCellM(q))

    private fun terrainMesh(c: TileContext, out: TileOutput, sub: Int = -1): MeshBuilder {
        // Gleicher Materialname → derselbe Netzbaustein, den die Schicht befuellt hat.
        val mat = TileMaterial(WorldNames.MAT_TERRAIN)
        if (sub < 0) return out.mesh(WorldNames.NODE_TERRAIN, mat)
        val f = WORLD_LEVEL_FACTOR
        val s = c.spec.cellM / f
        return out.mesh(WorldNames.NODE_TERRAIN, mat, c.spec.minE + (sub / f + 0.5) * s, c.spec.minN + (sub % f + 0.5) * s)
    }

    @Test
    fun `Nachbarkacheln haben identische Randpunkte`() {
        val g = ground()
        for (level in 0..2) {
            val a = TileSpec(level, 0, 0)
            val b = TileSpec(level, 1, 0)
            val c = TileSpec(level, 0, 1)
            fun edge(spec: TileSpec, e: Double?, n: Double?): Set<Triple<Long, Long, Long>> {
                val cx = ctx(spec, g)
                val out = TileOutput(spec)
                TerrainLayer(flatTexture).build(cx, out)
                val set = HashSet<Triple<Long, Long, Long>>()
                val subs = if (level == 0) listOf(-1) else (0 until 16).toList()
                for (s in subs) {
                    val m = terrainMesh(cx, out, s)
                    for (v in 0 until m.vertexCount) {
                        val we = m.x(v) + spec.anchorE
                        val wn = -m.z(v) + spec.anchorN
                        val wu = m.y(v) + cx.anchorU
                        val onE = e != null && kotlin.math.abs(we - e) < 1e-3
                        val onN = n != null && kotlin.math.abs(wn - n) < 1e-3
                        // Nur Oberflaechenpunkte (die Schuerze haengt tiefer).
                        if ((onE || onN) && kotlin.math.abs(wu - g.heightAt(we, wn)) < 0.01) {
                            set.add(Triple(Math.round(we * 100), Math.round(wn * 100), Math.round(wu * 100)))
                        }
                    }
                }
                return set
            }
            val cell = worldCellM(level)
            assertEquals(edge(a, cell, null), edge(b, cell, null), "Stufe $level: Ost-West-Rand")
            assertEquals(edge(a, null, cell), edge(c, null, cell), "Stufe $level: Nord-Sued-Rand")
            assertTrue(edge(a, cell, null).size > 8)
        }
    }

    @Test
    fun `Stufe 1 hat genau 16 Teilflaechen und die GLB ist vertragsgemaess`() {
        val g = ground()
        for (level in 1..2) {
            val spec = TileSpec(level, 0, 0)
            val out = TileOutput(spec)
            TerrainLayer(flatTexture).build(ctx(spec, g), out)
            val bytes = out.toGlb().toBytes()
            assertEquals(emptyList(), checkTileGlb(bytes))
            val info = parseGlb(bytes)
            val subs = info.nodes.filter { it.name?.startsWith(WorldNames.NODE_TERRAIN_SUB_PREFIX) == true }
            assertEquals(16, subs.size)
            for (i in 0 until 4) for (j in 0 until 4) assertTrue(info.node(WorldNames.terrainSubName(i, j)) != null)
        }
    }

    @Test
    fun `Dreiecke bleiben im Budget fuer alle Qualitaeten und Stufen`() {
        for (q in IndoorQuality.entries) {
            val g = ground(q)
            for (level in 0..2) {
                val spec = TileSpec(level, 0, 0)
                val out = TileOutput(spec)
                TerrainLayer(flatTexture).build(ctx(spec, g, q), out)
                val tris = out.triangles(WorldNames.NODE_TERRAIN)
                val budget = TileBudget.of(q, level).terrain
                assertTrue(tris <= budget, "$q Stufe $level: $tris > $budget")
                assertTrue(tris > budget * 0.8, "$q Stufe $level: Budget kaum genutzt ($tris von $budget)")
            }
        }
    }

    @Test
    fun `Stufe-0-Gitter hat bei MEDIUM 4-m-Zellen`() {
        assertTrue(level0TerrainCellM(IndoorQuality.MEDIUM) in 3.9..4.0)
        assertTrue(level0TerrainCellM(IndoorQuality.LOW) < 6.0)
        assertTrue(level0TerrainCellM(IndoorQuality.HIGH) < 3.0)
    }

    @Test
    fun `kein Gelaendedreieck liegt ueber der Fahrbahn`() {
        for (q in IndoorQuality.entries) {
            val g = ground(q)
            // Alle Kacheln der Stufe 0, die die Strecke beruehrt.
            val specs = LinkedHashSet<TileSpec>()
            var d = 0.0
            while (d <= track.lengthM) {
                val p = track.poseAt(d)
                for (de in listOf(-6.0, 6.0)) for (dn in listOf(-6.0, 6.0)) specs.add(TileSpec.at(0, p.e + de, p.n + dn))
                d += 5.0
            }
            var worst = -Double.MAX_VALUE
            var checked = 0
            for (spec in specs) {
                val cx = ctx(spec, g, q)
                val out = TileOutput(spec)
                TerrainLayer(flatTexture).build(cx, out)
                val m = terrainMesh(cx, out)
                val idx = TriIndex(m, spec, cx.anchorU)
                // Fahrbahnpunkte im 0,25-m-Raster.
                d = 0.0
                while (d <= track.lengthM) {
                    val p = track.poseAt(d)
                    val h = Math.toRadians(p.headingDeg)
                    val hw = track.halfWidthAt(d)
                    var l = -hw
                    while (l <= hw + 1e-9) {
                        val e = p.e + cos(h) * l
                        val n = p.n - sin(h) * l
                        if (spec.contains(e, n)) {
                            val road = track.nearest(e, n, 50.0)!!.u
                            val th = idx.heightAt(e, n)
                            if (th != null) {
                                worst = max(worst, th - (road - 0.1))
                                checked++
                            }
                        }
                        l += 0.25
                    }
                    d += 0.25
                }
            }
            assertTrue(checked > 1000, "$q: zu wenige Fahrbahnpunkte geprueft ($checked)")
            assertTrue(worst <= 0.0, "$q: Gelaendedreieck ragt bis $worst m ueber Fahrbahn − 0,1 m")
        }
    }

    /** Hoehe der Netzdreiecke an einer Stelle (Welt-ENU); groesster Wert, falls mehrere Dreiecke treffen. */
    private class TriIndex(val m: MeshBuilder, val spec: TileSpec, val anchorU: Double) {
        private val cell = 8.0
        private val grid = HashMap<Long, MutableList<Int>>()

        init {
            for (t in 0 until m.triangleCount) {
                val a = m.indices[t * 3]; val b = m.indices[t * 3 + 1]; val c = m.indices[t * 3 + 2]
                val xs = doubleArrayOf(m.x(a).toDouble(), m.x(b).toDouble(), m.x(c).toDouble())
                val zs = doubleArrayOf(m.z(a).toDouble(), m.z(b).toDouble(), m.z(c).toDouble())
                // Senkrechte Schuerzen-Dreiecke haben keine Grundflaeche.
                val area = (xs[1] - xs[0]) * (zs[2] - zs[0]) - (xs[2] - xs[0]) * (zs[1] - zs[0])
                if (kotlin.math.abs(area) < 1e-6) continue
                for (gx in floor(xs.min() / cell).toInt()..floor(xs.max() / cell).toInt())
                    for (gz in floor(zs.min() / cell).toInt()..floor(zs.max() / cell).toInt())
                        grid.getOrPut(key(gx, gz)) { ArrayList() }.add(t)
            }
        }

        private fun key(x: Int, z: Int) = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

        fun heightAt(e: Double, n: Double): Double? {
            val x = e - spec.anchorE
            val z = -(n - spec.anchorN)
            val list = grid[key(floor(x / cell).toInt(), floor(z / cell).toInt())] ?: return null
            var best: Double? = null
            for (t in list) {
                val a = m.indices[t * 3]; val b = m.indices[t * 3 + 1]; val c = m.indices[t * 3 + 2]
                val x0 = m.x(a).toDouble(); val z0 = m.z(a).toDouble()
                val x1 = m.x(b).toDouble(); val z1 = m.z(b).toDouble()
                val x2 = m.x(c).toDouble(); val z2 = m.z(c).toDouble()
                val det = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2)
                val w0 = ((z1 - z2) * (x - x2) + (x2 - x1) * (z - z2)) / det
                val w1 = ((z2 - z0) * (x - x2) + (x0 - x2) * (z - z2)) / det
                val w2 = 1 - w0 - w1
                val eps = -1e-7
                if (w0 < eps || w1 < eps || w2 < eps) continue
                val y = w0 * m.y(a) + w1 * m.y(b) + w2 * m.y(c) + anchorU
                best = if (best == null) y else max(best, y)
            }
            return best
        }
    }

    @Test
    fun `UV laeuft nach Osten und Sueden mit halbem Texel Einzug`() {
        val g = ground()
        val spec = TileSpec(0, 0, 0)
        val cx = ctx(spec, g)
        val out = TileOutput(spec)
        TerrainLayer(flatTexture).build(cx, out)
        val m = terrainMesh(cx, out)
        val px = cx.quality.texturePx(0)
        var minU = 1.0; var maxU = 0.0
        for (v in 0 until m.vertexCount) {
            val e = m.x(v) + spec.anchorE
            val n = -m.z(v) + spec.anchorN
            val u = m.uvs[v * 2].toDouble()
            val w = m.uvs[v * 2 + 1].toDouble()
            minU = min(minU, u); maxU = max(maxU, u)
            val inset = 0.5 / px
            assertEquals(inset + (e - spec.minE) / spec.cellM * (1 - 2 * inset), u, 1e-5)
            assertEquals(inset + (spec.maxN - n) / spec.cellM * (1 - 2 * inset), w, 1e-5)
        }
        assertEquals(0.5 / px, minU, 1e-6)
        assertEquals(1 - 0.5 / px, maxU, 1e-6)
    }
}
