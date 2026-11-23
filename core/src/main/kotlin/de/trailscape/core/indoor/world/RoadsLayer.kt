package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.floor
import kotlin.math.min

/**
 * # Uebrige Strassen und Wege (Knoten `roads`)
 *
 * Alle Strassen der Karte ausser der befahrenen Strecke als flache Baender
 * auf dem Boden: Breite und Belag aus Klasse und `surface` ([roadSpec]),
 * dieselben kachelnden Texturen wie die Strecke (Materialien
 * `x_mat_road_<key>`), weiche Alpha-Kanten.
 *
 *  * Hoehe: Boden + 4 cm + 1 cm je Rang der Klasse — an Kreuzungen liegt die
 *    wichtigere Strasse oben, nichts flimmert.
 *  * Stuecke, die mit der Strecke zusammenfallen, entfallen ([RoadNet]); wo
 *    eine Strasse in die Strecke muendet, steigt sie auf deren Hoehe und
 *    laeuft knapp unter dem Streckenband hindurch — ausser sie ist haerter
 *    befestigt als die Strecke dort ([hardness]): dann liegt sie obenauf.
 *  * Jedes Segment gehoert der Kachel, in der seine Mitte liegt.
 *  * Budget: Segmente nach Abstand zur Strecke; reicht es nicht, entfallen
 *    erst die Alpha-Kanten ferner Strassen, dann die fernsten Strassen ganz.
 *  * Tunnel fehlen, Bruecken liegen auf Gelaendehoehe.
 */
internal class RoadsLayer : TileLayer {
    private class Seg(val road: RoadNet.Road, val i: Int, val dist: Double)

    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level != 0 || ctx.budget.roads <= 0) return
        val net = RoadNet(ctx, marginM = 10.0, maxStepM = STEP_M)
        val segs = ArrayList<Seg>()
        for (r in net.roads) {
            for (i in 0 until r.size - 1) {
                if (r.segOnTrack(i)) continue
                val me = (r.e[i] + r.e[i + 1]) / 2
                val mn = (r.n[i] + r.n[i + 1]) / 2
                if (!ctx.spec.contains(me, mn)) continue
                segs.add(Seg(r, i, min(r.trackDist[i], r.trackDist[i + 1])))
            }
        }
        if (segs.isEmpty()) return
        segs.sortBy { it.dist }
        // Budget: nah mit Kanten (und Schienen), fern ohne; was dann noch nicht passt, entfaellt von aussen her.
        var used = 0
        var edgeLimit = Double.MAX_VALUE
        fun cost(s: Seg, edges: Boolean) = 2 + (if (edges) 4 else 0) + (if (s.road.spec.rail) 4 else 0)
        if (segs.sumOf { cost(it, true) } > ctx.budget.roads) edgeLimit = EDGE_NEAR_M
        val emitter = Emitter(ctx, out)
        for (s in segs) {
            val edges = s.dist <= edgeLimit
            val c = cost(s, edges)
            if (used + c > ctx.budget.roads) break
            used += c
            emitter.segment(s.road, s.i, edges)
        }
    }

    private class Emitter(private val ctx: TileContext, private val out: TileOutput) {
        private val materials = HashMap<String, TileMaterial>()

        private fun mesh(kind: SurfaceKind, edge: Boolean): MeshBuilder {
            val name = if (edge) edgeMaterialName(kind) else roadMaterialName(kind)
            return out.mesh(
                WorldNames.NODE_ROADS,
                materials.getOrPut(name) {
                    TileMaterial(
                        name, RouteTextures.surfaceTexture(ctx.textures, kind, ctx.quality == IndoorQuality.LOW),
                        roughness = if (kind.visual == SurfaceKind.ASPHALT) 0.86f else 0.95f,
                        alphaMode = if (edge) GlbAlphaMode.BLEND else GlbAlphaMode.OPAQUE,
                    )
                },
            )
        }

        /**
         * Hoehe des Bands an einer Stelle: Boden + Staffel; zur Strecke hin auf
         * deren Hoehe gezogen und unter ihrem Band hindurch (je Eckpunkt
         * gerechnet — die Strecke steigt auch quer zur kreuzenden Strasse).
         */
        private fun heightAt(e: Double, n: Double, r: RoadNet.Road, nearTrack: Boolean): Double {
            val v = ctx.ground.heightAt(e, n) + BASE_LIFT_M + r.spec.rank * RANK_LIFT_M
            if (!nearTrack) return v
            val hit = ctx.track.nearest(e, n, 30.0) ?: return v
            val hw = ctx.track.halfWidthAt(hit.distM)
            val kind = ctx.track.surfaceAt(hit.distM).kind
            // Ein Wiesen- oder Erdweg, der eine befestigte Strasse quert, laeuft nicht als Teppich darueber:
            // Die haertere Strasse liegt dann obenauf.
            val over = hardness(r.spec.kind) > hardness(kind) && !r.spec.rail
            val level = hit.u - RouteLayer.crownOf(kind) * min(hit.distance, hw) + (if (over) OVER_TRACK_M else -UNDER_TRACK_M)
            val w = 1.0 - smoothstep(hw + 0.3, hw + 4.5, hit.distance)
            return v + (level - v) * w
        }

        fun segment(r: RoadNet.Road, i: Int, edges: Boolean) {
            val spec = r.spec
            val kind = spec.kind
            val tex = RouteTextures.spec(kind)
            val hw = r.halfWidth
            val shift = if (tex.twoTrack && spec.widthM < 2.2) RouteTextures.TRACK_OFFSET_M else 0.0
            val vBase = floor(r.s[i] / tex.lengthM) * tex.lengthM
            val m = mesh(kind, false)
            val idx = IntArray(4)
            val he = DoubleArray(4)
            for (q in 0..1) {
                val k = i + q
                val off = hw * min(r.miter[k], 2.0)
                for (side in 0..1) {
                    val sgn = if (side == 0) -1.0 else 1.0
                    val e = r.e[k] + r.re[k] * off * sgn
                    val n = r.n[k] + r.rn[k] * off * sgn
                    val h = heightAt(e, n, r, r.trackDist[k] < 40.0)
                    val tone = RouteGeometry.tone(e, n, 0x70e)
                    val c = (if (spec.rail) 0.52 else 0.80) + 0.14 * tone
                    val col = RouteGeometry.linColor(c, c * (if (spec.rail) 0.94 else 1.0), c * (if (spec.rail) 0.88 else 1.0))
                    he[q * 2 + side] = h
                    idx[q * 2 + side] = m.vertex(
                        ctx.x(e), ctx.y(h), ctx.z(n), 0.0, 1.0, 0.0,
                        0.5 + (sgn * hw + shift) / tex.widthM, (r.s[k] - vBase) / tex.lengthM, col,
                    )
                }
            }
            m.quad(idx[0], idx[1], idx[3], idx[2])
            if (edges) edgeStrips(r, i, kind, tex, shift, vBase, he)
            if (spec.rail) rails(r, i)
        }

        /** Weiche Kanten: je Seite ein Streifen, dessen Deckung nach aussen auf null faellt. */
        private fun edgeStrips(r: RoadNet.Road, i: Int, kind: SurfaceKind, tex: RouteTextures.Spec, shift: Double, vBase: Double, he: DoubleArray) {
            val m = mesh(kind, true)
            val hw = r.halfWidth
            for (side in 0..1) {
                val sgn = if (side == 0) -1.0 else 1.0
                val idx = IntArray(4)
                for (q in 0..1) {
                    val k = i + q
                    val mit = min(r.miter[k], 2.0)
                    for (o in 0..1) {
                        val t = hw + o * EDGE_WIDTH_M
                        val e = r.e[k] + r.re[k] * t * mit * sgn
                        val n = r.n[k] + r.rn[k] * t * mit * sgn
                        val h = if (o == 0) he[q * 2 + side] else heightAt(e, n, r, r.trackDist[k] < 40.0) - 0.015
                        val tone = RouteGeometry.tone(e, n, 0x70e)
                        val c = (if (r.spec.rail) 0.52 else 0.80) + 0.14 * tone
                        idx[q * 2 + o] = m.vertex(
                            ctx.x(e), ctx.y(h), ctx.z(n), 0.0, 1.0, 0.0,
                            0.5 + (sgn * t + shift) / tex.widthM, (r.s[k] - vBase) / tex.lengthM,
                            RouteGeometry.linColor(c, c, c, if (o == 0) 1.0 else 0.0),
                        )
                    }
                }
                // idx: [a innen, a aussen, b innen, b aussen]; rechts liegt aussen rechts, links aussen links.
                if (side == 1) m.quad(idx[0], idx[1], idx[3], idx[2]) else m.quad(idx[1], idx[0], idx[2], idx[3])
            }
        }

        /** Zwei Schienen als schmale dunkle Baender ueber dem Schotterbett. */
        private fun rails(r: RoadNet.Road, i: Int) {
            val name = "x_mat_road_rail"
            val m = out.mesh(WorldNames.NODE_ROADS, materials.getOrPut(name) { TileMaterial(name, roughness = 0.7f) })
            val col = RouteGeometry.linColor(0.16, 0.14, 0.13)
            for (side in 0..1) {
                val sgn = if (side == 0) -1.0 else 1.0
                val idx = IntArray(4)
                for (q in 0..1) {
                    val k = i + q
                    val h = heightAt(r.e[k], r.n[k], r, r.trackDist[k] < 40.0) + 0.16
                    for (o in 0..1) {
                        val t = (RAIL_GAUGE_M / 2 + (o - 0.5) * 0.07) * sgn * min(r.miter[k], 2.0)
                        idx[q * 2 + o] = m.vertex(ctx.x(r.e[k] + r.re[k] * t), ctx.y(h), ctx.z(r.n[k] + r.rn[k] * t), 0.0, 1.0, 0.0, 0.0, 0.0, col)
                    }
                }
                if (side == 1) m.quad(idx[0], idx[1], idx[3], idx[2]) else m.quad(idx[1], idx[0], idx[2], idx[3])
            }
        }
    }

    companion object {
        /** Abtastschritt der Strassen in Metern. */
        const val STEP_M = 4.0
        const val BASE_LIFT_M = 0.04
        const val RANK_LIFT_M = 0.01

        /** So tief liegt eine einmuendende Strasse unter der Fahrbahn der Strecke. */
        const val UNDER_TRACK_M = 0.035

        /** So hoch liegt eine haertere Strasse ueber der Fahrbahn der Strecke, die sie quert. */
        const val OVER_TRACK_M = 0.014

        /** Rangfolge der Belaege an einer Querung: Der haertere liegt oben. */
        fun hardness(kind: SurfaceKind): Int = when (kind.visual) {
            SurfaceKind.ASPHALT -> 9
            SurfaceKind.CONCRETE -> 8
            SurfaceKind.PAVING_STONES -> 7
            SurfaceKind.COBBLESTONE -> 6
            SurfaceKind.WOOD -> 5
            SurfaceKind.COMPACTED -> 4
            SurfaceKind.GRAVEL -> 3
            SurfaceKind.DIRT -> 2
            SurfaceKind.SAND -> 1
            else -> 0
        }
        const val EDGE_WIDTH_M = 0.35

        /** Ist das Budget knapp, bekommen nur Strassen bis zu diesem Abstand zur Strecke weiche Kanten. */
        const val EDGE_NEAR_M = 60.0
        const val RAIL_GAUGE_M = 1.435

        fun roadMaterialName(kind: SurfaceKind): String = "x_mat_road_${kind.visual.textureKey}"
        fun edgeMaterialName(kind: SurfaceKind): String = "x_mat_road_${kind.visual.textureKey}_edge"
    }
}
