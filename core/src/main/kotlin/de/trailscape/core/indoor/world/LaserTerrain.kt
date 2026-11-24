package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.max

/**
 * # Adaptives Gelaendenetz der Stufe 0 aus 1-m-Daten
 *
 * Ein regelmaessiges Gitter mit 1 m Kante haette je 256-m-Kachel 131 000
 * Dreiecke — 15× das Gelaendebudget. Stattdessen ein **RTIN** (right-
 * triangulated irregular network, Evans et al. 2001; Umsetzung nach Mapbox
 * „Martini"): rechtwinklig-gleichschenklige Dreiecke ueber dem 257²-Gitter der
 * Kachel, die nur dort geteilt werden, wo die Flaeche vom Netz um mehr als
 * eine Schwelle abweicht. Ebene Felder, Waldboden und Fahrbahn werden so zu
 * wenigen grossen Dreiecken; Boeschungskanten, Graeben und Daemme bleiben
 * scharf, weil dort der Fehler gross ist. Das Netz ist in sich rissfrei (die
 * Fehler werden zu den Eltern hochgereicht, Teilen erzwingt die Nachbarn).
 *
 * **Budget:** Die Schwelle wird je Kachel per Bisektion so gewaehlt, dass
 * Netz + Schuerze in [budget] passen (MEDIUM: das bisherige Gelaendebudget
 * von 9 000 Dreiecken) — die Welt hat damit nie mehr Gelaendedreiecke als ohne
 * Laserdaten, nur besser verteilt.
 *
 * **Fahrbahn:** Unter der Strecke (halbe Breite + Bankett) zaehlt eine Abweichung
 * **nach oben** vierfach, und nach dem Vernetzen wird jede dieser Proben gegen
 * das Netz geprueft; liegt das Netz mehr als [ROUTE_MAX_RISE_M] ueber dem wahren
 * Boden (der dort 0,25 m unter der Fahrbahn liegt), wird an dieser Probe
 * zwangsweise geteilt. So sticht kein Dreieck durch die Fahrbahn, ohne das
 * Bankett-Gitter von [CarvedGround].
 *
 * [heightAt] liefert die Hoehe **des gezeichneten Netzes** (Abstieg im
 * Dreiecksbaum bis zum Blatt, linear im Dreieck) — die Wege- und Randschichten
 * legen sich damit auf das Netz statt auf das Raster (siehe [terrainMeshHeightAt]).
 */
internal class LaserTerrainMesh private constructor(
    private val minE: Double,
    private val minN: Double,
    private val h: FloatArray,
    private val err: FloatArray,
    val threshold: Float,
    val triangles: Int,
) {
    /** Hoehe des Netzes an (`e`, `n`) (ENU-`u`); ausserhalb der Kachel am Rand geklemmt. */
    fun heightAt(e: Double, n: Double): Double {
        val x = (e - minE).coerceIn(0.0, S.toDouble())
        val y = (n - minN).coerceIn(0.0, S.toDouble())
        // Wurzeln wie beim Vernetzen: (0,0)–(S,S) mit rechtem Winkel bei (S,0) bzw. (0,S).
        return if (x >= y) descend(x, y, 0, 0, S, S, S, 0) else descend(x, y, S, S, 0, 0, 0, S)
    }

    private fun descend(px: Double, py: Double, ax0: Int, ay0: Int, bx0: Int, by0: Int, cx0: Int, cy0: Int): Double {
        var ax = ax0; var ay = ay0; var bx = bx0; var by = by0; var cx = cx0; var cy = cy0
        while (true) {
            val mx = (ax + bx) shr 1
            val my = (ay + by) shr 1
            if (abs(ax - cx) + abs(ay - cy) > 1 && err[my * G + mx] > threshold) {
                // Kind 1: (c, a, m), Kind 2: (b, c, m). Trennlinie c–m.
                val side = (mx - cx).toDouble() * (py - cy) - (my - cy).toDouble() * (px - cx)
                val sideA = (mx - cx).toDouble() * (ay - cy) - (my - cy).toDouble() * (ax - cx)
                if ((side >= 0) == (sideA >= 0)) {
                    val nax = cx; val nay = cy; val nbx = ax; val nby = ay
                    ax = nax; ay = nay; bx = nbx; by = nby
                } else {
                    val nax = bx; val nay = by; val nbx = cx; val nby = cy
                    ax = nax; ay = nay; bx = nbx; by = nby
                }
                cx = mx
                cy = my
                continue
            }
            // Blatt: baryzentrisch
            val d = (by - cy).toDouble() * (ax - cx) + (cx - bx).toDouble() * (ay - cy)
            val wa = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / d
            val wb = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / d
            val wc = 1 - wa - wb
            return wa * h[ay * G + ax] + wb * h[by * G + bx] + wc * h[cy * G + cx]
        }
    }

    /** Ruft [emit] fuer jedes Dreieck (Gitterindizes `j·G + i`, gegen den Uhrzeigersinn von oben). */
    fun forEachTriangle(emit: (Int, Int, Int) -> Unit) {
        walk(err, threshold, 0, 0, S, S, S, 0, emit)
        walk(err, threshold, S, S, 0, 0, 0, S, emit)
    }

    companion object {
        /** Kantenlaenge der Kachel in Gitterschritten (1 m). */
        const val S = 256
        const val G = S + 1

        /** Hoechstens so weit darf das Netz unter der Fahrbahn ueber dem wahren Boden liegen. */
        const val ROUTE_MAX_RISE_M = 0.08

        private const val ROUTE_WEIGHT = 4f

        /** Ecken aller Dreiecke des vollstaendigen Baums (je Dreieck ax, ay, bx, by), einmal gerechnet. */
        private val coords: IntArray by lazy {
            val num = S * S * 2 - 2
            val c = IntArray(num * 4)
            for (i in 0 until num) {
                var id = i + 2
                var ax = 0; var ay = 0; var bx = 0; var by = 0; var cx = 0; var cy = 0
                if (id and 1 == 1) {
                    bx = S; by = S; cx = S
                } else {
                    ax = S; ay = S; cy = S
                }
                id = id shr 1
                while (id > 1) {
                    val mx = (ax + bx) shr 1
                    val my = (ay + by) shr 1
                    if (id and 1 == 1) {
                        bx = ax; by = ay; ax = cx; ay = cy
                    } else {
                        ax = bx; ay = by; bx = cx; by = cy
                    }
                    cx = mx; cy = my
                    id = id shr 1
                }
                c[4 * i] = ax; c[4 * i + 1] = ay; c[4 * i + 2] = bx; c[4 * i + 3] = by
            }
            c
        }

        private fun errors(h: FloatArray, weightUp: FloatArray?, force: BooleanArray?): FloatArray {
            val num = S * S * 2 - 2
            val parents = num - S * S
            val c = coords
            val e = FloatArray(G * G)
            for (i in num - 1 downTo 0) {
                val ax = c[4 * i]; val ay = c[4 * i + 1]; val bx = c[4 * i + 2]; val by = c[4 * i + 3]
                val mx = (ax + bx) shr 1
                val my = (ay + by) shr 1
                val cx = mx + my - ay
                val cy = my + ax - mx
                val mid = my * G + mx
                val interp = (h[ay * G + ax] + h[by * G + bx]) / 2
                var me = abs(interp - h[mid])
                if (weightUp != null && interp > h[mid]) me *= weightUp[mid]
                if (force != null && force[mid]) me = Float.MAX_VALUE / 4
                if (me > e[mid]) e[mid] = me
                if (i < parents) {
                    val l = ((ay + cy) shr 1) * G + ((ax + cx) shr 1)
                    val r = ((by + cy) shr 1) * G + ((bx + cx) shr 1)
                    e[mid] = max(e[mid], max(e[l], e[r]))
                }
            }
            return e
        }

        private fun walk(err: FloatArray, thr: Float, ax: Int, ay: Int, bx: Int, by: Int, cx: Int, cy: Int, emit: (Int, Int, Int) -> Unit) {
            val mx = (ax + bx) shr 1
            val my = (ay + by) shr 1
            if (abs(ax - cx) + abs(ay - cy) > 1 && err[my * G + mx] > thr) {
                walk(err, thr, cx, cy, ax, ay, mx, my, emit)
                walk(err, thr, bx, by, cx, cy, mx, my, emit)
            } else {
                // Umlaufsinn in (Ost, Nord) gegen den Uhrzeigersinn
                val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                if (cross > 0) emit(ay * G + ax, by * G + bx, cy * G + cx) else emit(ay * G + ax, cy * G + cx, by * G + bx)
            }
        }

        private fun count(err: FloatArray, thr: Float): Int {
            var k = 0
            var border = 0
            val f: (Int, Int, Int) -> Unit = { a, b, c ->
                k++
                border += borderEdges(a, b, c)
            }
            walk(err, thr, 0, 0, S, S, S, 0, f)
            walk(err, thr, S, S, 0, 0, 0, S, f)
            return k + 2 * border
        }

        /** Kanten des Dreiecks, die auf dem Kachelrand liegen. */
        fun borderEdges(a: Int, b: Int, c: Int): Int {
            var n = 0
            if (onSameBorder(a, b)) n++
            if (onSameBorder(b, c)) n++
            if (onSameBorder(c, a)) n++
            return n
        }

        fun onSameBorder(p: Int, q: Int): Boolean {
            val px = p % G; val py = p / G; val qx = q % G; val qy = q / G
            return (px == 0 && qx == 0) || (px == S && qx == S) || (py == 0 && qy == 0) || (py == S && qy == S)
        }

        /**
         * Vernetzt die Kachel mit Suedwestecke (`minE`, `minN`) ueber [ground] in
         * hoechstens [budget] Dreiecken (Netz + Schuerze). [underRoute] meldet
         * Gitterpunkte unter der Fahrbahn.
         */
        fun build(ground: GroundSurface, minE: Double, minN: Double, budget: Int, underRoute: (Double, Double) -> Boolean): LaserTerrainMesh {
            val h = FloatArray(G * G)
            val wUp = FloatArray(G * G) { 1f }
            val road = ArrayList<Int>()
            for (j in 0..S) for (i in 0..S) {
                val e = minE + i
                val n = minN + j
                h[j * G + i] = ground.heightAt(e, n).toFloat()
                if (underRoute(e, n)) {
                    wUp[j * G + i] = ROUTE_WEIGHT
                    road += j * G + i
                }
            }
            val force = if (road.isEmpty()) null else BooleanArray(G * G)
            var err = errors(h, if (road.isEmpty()) null else wUp, null)
            var thr = threshold(err, budget)
            var round = 0
            while (force != null && round++ < 4) {
                val mesh = LaserTerrainMesh(minE, minN, h, err, thr, 0)
                var bad = 0
                for (k in road) {
                    val e = minE + k % G
                    val n = minN + k / G
                    if (mesh.heightAt(e, n) - h[k] > ROUTE_MAX_RISE_M) {
                        force[k] = true
                        bad++
                    }
                }
                if (bad == 0) break
                err = errors(h, wUp, force)
                thr = threshold(err, budget)
            }
            return LaserTerrainMesh(minE, minN, h, err, thr, count(err, thr))
        }

        /** Kleinste Schwelle (auf 1 cm), bei der das Netz in [budget] passt. */
        private fun threshold(err: FloatArray, budget: Int): Float {
            var lo = 0.01f
            var hi = 64f
            if (count(err, lo) <= budget) return lo
            repeat(16) {
                val mid = (lo + hi) / 2
                if (count(err, mid) <= budget) hi = mid else lo = mid
            }
            return hi
        }
    }
}

/**
 * Gelaendeschicht mit [LaserTerrainMesh] in Stufe 0 (Kacheln mit Laserdaten);
 * alles andere wie [TerrainLayer]. Gleiche Materialien, UV und Schuerze.
 */
internal class LaserTerrainLayer(
    private val meshOf: (TileContext) -> LaserTerrainMesh?,
    private val fallback: TerrainLayer = TerrainLayer(),
) : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        val rtin = if (ctx.level == 0) meshOf(ctx) else null
        if (rtin == null) {
            fallback.build(ctx, out)
            return
        }
        val spec = ctx.spec
        val tex = ctx.aerialTexture ?: defaultGroundTexture(ctx)
        val mat = TileMaterial(WorldNames.MAT_TERRAIN, texture = tex, repeat = false, roughness = 0.95f)
        val texPx = tex.width
        val inset = 0.5 / texPx
        val scale = 1.0 - 2 * inset
        val mesh = out.mesh(WorldNames.NODE_TERRAIN, mat, spec.anchorE, spec.anchorN)
        val G = LaserTerrainMesh.G
        val S = LaserTerrainMesh.S
        val index = HashMap<Int, Int>()
        val tris = ArrayList<IntArray>()
        rtin.forEachTriangle { a, b, c -> tris += intArrayOf(a, b, c) }
        mesh.ensureVertexCapacity(mesh.vertexCount + tris.size + 8 * S)
        mesh.ensureTriangleCapacity(mesh.triangleCount + tris.size + 8 * S)
        fun vertexOf(k: Int): Int = index.getOrPut(k) {
            val e = spec.minE + k % G
            val n = spec.minN + k / G
            val hgt = rtin.heightAt(e, n)
            val nor = ctx.ground.normalAt(e, n, 1.0)
            mesh.vertex(ctx.x(e), ctx.y(hgt), ctx.z(n), nor.x, nor.y, nor.z, inset + (e - spec.minE) / spec.cellM * scale, inset + (spec.maxN - n) / spec.cellM * scale)
        }
        val borderEdges = ArrayList<IntArray>()
        for (t in tris) {
            val a = vertexOf(t[0]); val b = vertexOf(t[1]); val c = vertexOf(t[2])
            mesh.tri(a, b, c)
            // Randkanten in Umlaufrichtung des Dreiecks (gegen den Uhrzeigersinn) = Umlauf des Rands.
            if (LaserTerrainMesh.onSameBorder(t[0], t[1])) borderEdges += intArrayOf(t[0], t[1])
            if (LaserTerrainMesh.onSameBorder(t[1], t[2])) borderEdges += intArrayOf(t[1], t[2])
            if (LaserTerrainMesh.onSameBorder(t[2], t[0])) borderEdges += intArrayOf(t[2], t[0])
        }
        val depth = TerrainLayer.skirtDepthM(0)
        for (edge in borderEdges) {
            val p = index.getValue(edge[0])
            val q = index.getValue(edge[1])
            fun dup(v: Int, down: Double): Int = mesh.vertex(
                mesh.x(v).toDouble(), mesh.y(v).toDouble() - down, mesh.z(v).toDouble(),
                mesh.normals[v * 3].toDouble(), mesh.normals[v * 3 + 1].toDouble(), mesh.normals[v * 3 + 2].toDouble(),
                mesh.uvs[v * 2].toDouble(), mesh.uvs[v * 2 + 1].toDouble(),
            )
            val tp = dup(p, 0.0); val bp = dup(p, depth); val tq = dup(q, 0.0); val bq = dup(q, depth)
            mesh.tri(tp, bp, bq)
            mesh.tri(tp, bq, tq)
        }
    }
}
