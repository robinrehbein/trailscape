package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
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
        // 30 m Rand wie die Sperrflaechen des Streckenrands (RouteEnv): dieselben Ecken an Einmuendungen.
        val net = RoadNet(ctx, marginM = 30.0, maxStepM = STEP_M)
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
        islands(ctx, out)
        val corners = JunctionCorners.find(ctx.track, ctx.land, net).filter { ctx.spec.contains(it.pe, it.pn) }
        if (segs.isEmpty() && corners.isEmpty()) return
        segs.sortBy { it.dist }
        // Budget: nah mit Kanten (und Schienen), fern ohne; was dann noch nicht passt, entfaellt von aussen her.
        // Ecken an Einmuendungen zuerst (sie liegen an der Strecke): 8 Dreiecke, im Ort 32 fuer den Bordstein dazu.
        var used = 0
        val drawCorners = ArrayList<JunctionCorners.Corner>()
        for (c in corners) {
            val cost = 8 + (if (c.urban) 32 else 0)
            if (used + cost > ctx.budget.roads) break
            used += cost
            drawCorners.add(c)
        }
        var edgeLimit = Double.MAX_VALUE
        fun cost(s: Seg, edges: Boolean) = 2 + (if (edges) 4 else 0) + (if (s.road.spec.rail) 4 else 0)
        if (used + segs.sumOf { cost(it, true) } > ctx.budget.roads) edgeLimit = EDGE_NEAR_M
        // Sperrflaechen fuer die weichen Kanten: die Baender aller Strassen, je Strasse eine Nummer — eine Kante
        // laeuft nicht ueber eine andere Strasse (dort lag sie Millimeter ueber deren Fahrbahn: Flimmern).
        val mask = PlanMask()
        for ((ri, r) in net.roads.withIndex()) {
            val hw = r.halfWidth
            for (i in 0 until r.size - 1) {
                if (r.segOnTrack(i)) continue
                val oa = hw * min(r.miter[i], 2.0)
                val ob = hw * min(r.miter[i + 1], 2.0)
                mask.addQuad(
                    r.e[i] - r.re[i] * oa, r.n[i] - r.rn[i] * oa, r.e[i] + r.re[i] * oa, r.n[i] + r.rn[i] * oa,
                    r.e[i + 1] - r.re[i + 1] * ob, r.n[i + 1] - r.rn[i + 1] * ob, r.e[i + 1] + r.re[i + 1] * ob, r.n[i + 1] + r.rn[i + 1] * ob,
                    fromM = ri.toDouble(), toM = ri.toDouble(),
                )
            }
        }
        val index = HashMap<RoadNet.Road, Int>()
        for ((ri, r) in net.roads.withIndex()) index[r] = ri
        val emitter = Emitter(ctx, out, mask)
        for (s in segs) {
            // Im Randbereich der Strecke (Gras, Gehweg, Bankett) keine weichen Kanten: Der Rand der Strecke stoesst
            // dort genau an die Fahrbahn der Strasse (RouteLayer schneidet ihn an deren Band), eine Kante darueber
            // laege Millimeter ueber dem Gras.
            val edges = s.dist <= edgeLimit && s.dist > EDGE_FREE_TRACK_M
            val c = cost(s, edges)
            if (used + c > ctx.budget.roads) break
            used += c
            emitter.segment(s.road, s.i, edges, index.getValue(s.road))
        }
        for (c in drawCorners) emitter.corner(c)
    }

    /**
     * Mittelinseln der Kreisverkehre ([Roundabouts]): Grasflaeche 14 cm ueber dem Boden (bzw.
     * 10 cm ueber der Fahrbahn der Strecke, wo sie den Ring befaehrt), leicht gewoelbt, mit
     * grauem Bordstein ringsum, der unter das Gelaende reicht — keine Kante, durch die man
     * hindurchsieht, keine Flaeche in derselben Ebene wie die Fahrbahn.
     */
    private fun islands(ctx: TileContext, out: TileOutput) {
        val s = ctx.spec
        val list = Roundabouts.islands(ctx.map, s.minE, s.minN, s.maxE, s.maxN)
        if (list.isEmpty()) return
        val grassTex = RouteTextures.vergeTexture(ctx.textures, RouteTextures.Verge.GRASS, if (ctx.quality == IndoorQuality.LOW) 256 else 512)
        val grass = out.mesh(WorldNames.NODE_ROADS, TileMaterial(MAT_ISLAND, grassTex, repeat = true, roughness = 0.95f))
        val kerb = out.mesh(WorldNames.NODE_ROADS, TileMaterial(MAT_ISLAND_KERB, roughness = 0.85f))
        val uvM = RouteTextures.Verge.GRASS.sizeM
        val tint = RouteGeometry.linColor(0.74, 0.8, 0.62)
        val kerbCol = RouteGeometry.linColor(0.42, 0.41, 0.39)
        for (isl in list) {
            val cnt = isl.ring.size / 2
            val top = DoubleArray(cnt)
            for (k in 0 until cnt) {
                val e = isl.ring[2 * k]
                val n = isl.ring[2 * k + 1]
                var h = ctx.ground.heightAt(e, n) + ISLAND_RISE_M
                val hit = ctx.track.nearest(e, n, 20.0)
                if (hit != null && hit.distance < ctx.track.halfWidthAt(hit.distM) + 3.0) h = maxOf(h, hit.u + ISLAND_RISE_M - 0.04)
                top[k] = h
            }
            val mid = top.average() + min(0.35, isl.radius * 0.04)
            val c = grass.vertex(ctx.x(isl.ce), ctx.y(mid), ctx.z(isl.cn), 0.0, 1.0, 0.0, isl.ce / uvM, -isl.cn / uvM, tint)
            val first = grass.vertexCount
            for (k in 0 until cnt) {
                val e = isl.ring[2 * k]
                val n = isl.ring[2 * k + 1]
                grass.vertex(ctx.x(e), ctx.y(top[k]), ctx.z(n), 0.0, 1.0, 0.0, e / uvM, -n / uvM, tint)
            }
            // Ring gegen den Uhrzeigersinn in (e, n): Faecher von oben sichtbar.
            for (k in 0 until cnt) grass.tri(c, first + k, first + (k + 1) % cnt)
            // Bordstein: vom Inselrand senkrecht bis unter das Gelaende, nach aussen geneigt.
            val kb = kerb.vertexCount
            for (k in 0 until cnt) {
                val e = isl.ring[2 * k]
                val n = isl.ring[2 * k + 1]
                val dx = e - isl.ce
                val dy = n - isl.cn
                val d = maxOf(1e-6, kotlin.math.hypot(dx, dy))
                val oe = e + dx / d * 0.12
                val on = n + dy / d * 0.12
                val nx = dx / d
                val nz = -dy / d
                kerb.vertex(ctx.x(e), ctx.y(top[k]), ctx.z(n), nx, 0.3, nz, 0.0, 0.0, kerbCol)
                kerb.vertex(ctx.x(oe), ctx.y(ctx.ground.heightAt(oe, on) - 0.2), ctx.z(on), nx, 0.3, nz, 0.0, 0.0, kerbCol)
            }
            for (k in 0 until cnt) {
                val a = kb + 2 * k
                val b = kb + 2 * ((k + 1) % cnt)
                // von aussen gesehen gegen den Uhrzeigersinn
                kerb.quad(a, a + 1, b + 1, b)
            }
        }
    }

    private class Emitter(private val ctx: TileContext, private val out: TileOutput, private val mask: PlanMask) {
        private val temp = MeshBuilder()
        private val materials = HashMap<String, TileMaterial>()
        private val meshCell = level0TerrainCellM(ctx.quality)

        private fun mesh(kind: SurfaceKind, edge: Boolean): MeshBuilder {
            val name = if (edge) edgeMaterialName(kind) else roadMaterialName(kind)
            return out.mesh(
                WorldNames.NODE_ROADS,
                materials.getOrPut(name) {
                    TileMaterial(
                        name,
                        if (edge) RouteTextures.roadEdgeTexture(ctx.textures, kind) else RouteTextures.surfaceTexture(ctx.textures, kind, ctx.quality == IndoorQuality.LOW),
                        roughness = if (kind.visual == SurfaceKind.ASPHALT) 0.86f else 0.95f,
                        alphaMode = if (edge) GlbAlphaMode.BLEND else GlbAlphaMode.OPAQUE,
                        clampV = edge,
                    )
                },
            )
        }

        /**
         * Hoehe des Bands an einer Stelle: Boden + Staffel; zur Strecke hin auf
         * deren Hoehe gezogen und unter ihrem Band hindurch (je Eckpunkt
         * gerechnet — die Strecke steigt auch quer zur kreuzenden Strasse).
         */
        private fun heightAt(e: Double, n: Double, r: RoadNet.Road, nearTrack: Boolean, k: Int): Double {
            // Wie weit das Netz ueber dem wahren Boden liegt — an der Station und einen halben Schritt
            // vor und zurueck (liegt ein Gitterpunkt zwischen zwei Stationen, woelbt es sich dort).
            val te = r.rn[k] * STEP_M / 2
            val tn = -r.re[k] * STEP_M / 2
            fun bulge(x: Double, y: Double) = ctx.meshHeightAt(x, y, meshCell) - ctx.ground.heightAt(x, y)
            val g0 = ctx.ground.heightAt(e, n)
            var mesh = g0 + max(0.0, max(bulge(e, n), max(bulge(e + te, n + tn), bulge(e - te, n - tn))))
            // Bruecken: geradlinig zwischen den Enden, nie unter das Gelaende — sonst taucht die
            // Strasse ins Tal unter der Bruecke (das DGM1 kennt keine Brueckendecks).
            if (r.source.brunnel == "bridge") bridgeDeckAt(r.source.line, e, n)?.let { mesh = max(mesh, it) }
            val v = mesh + BASE_LIFT_M + r.spec.rank * RANK_LIFT_M + dirLift(r, k)
            if (!nearTrack) return v
            val hit = ctx.track.nearest(e, n, 40.0) ?: return v
            val hw = ctx.track.halfWidthAt(hit.distM)
            val kind = ctx.track.surfaceAt(hit.distM).kind
            // Ein Wiesen- oder Erdweg, der eine befestigte Strasse quert, laeuft nicht als Teppich darueber:
            // Die haertere Strasse liegt dann obenauf.
            val over = hardness(r.spec.kind) > hardness(kind) && !r.spec.rail
            val level = hit.u - RouteLayer.crownOf(kind) * min(hit.distance, hw) + (if (over) OVER_TRACK_M else -UNDER_TRACK_M)
            // Ueber JOIN_M stetig von der Fahrbahnkante auf die eigene Hoehe: Neben der Strecke liegt das
            // Gelaende in einer flachen Mulde (CarvedGround: Bankett 25 cm unter der Fahrbahn) — folgte die
            // Strasse ihr, wellte sie sich an jeder Einmuendung (frueher Uebergang ueber nur 4,2 m: Rampe).
            // Liegt die Strecke tiefer (Einschnitt), bleibt es beim kurzen Uebergang: Sonst taeuchte die
            // Strasse unter die Boeschung.
            val w = 1.0 - smoothstep(hw + 0.3, hw + (if (level >= v) JOIN_M else 4.5), hit.distance)
            return v + (level - v) * w
        }

        /** Hoehe eines Brueckendecks: linear zwischen dem Boden an Anfang und Ende der Linie (hoechstens 400 m lang). */
        private fun bridgeDeckAt(line: DoubleArray, e: Double, n: Double): Double? {
            val k = line.size / 2
            if (k < 2) return null
            var total = 0.0
            for (i in 1 until k) total += kotlin.math.hypot(line[2 * i] - line[2 * i - 2], line[2 * i + 1] - line[2 * i - 1])
            if (total < 1.0 || total > 400.0) return null
            var best = Double.MAX_VALUE
            var bestS = 0.0
            var acc = 0.0
            for (i in 1 until k) {
                val ax = line[2 * i - 2]; val ay = line[2 * i - 1]
                val dx = line[2 * i] - ax; val dy = line[2 * i + 1] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((e - ax) * dx + (n - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = kotlin.math.hypot(ax + dx * t - e, ay + dy * t - n)
                val sl = kotlin.math.sqrt(l2)
                if (d < best) { best = d; bestS = acc + t * sl }
                acc += sl
            }
            val h0 = ctx.ground.heightAt(line[0], line[1])
            val h1 = ctx.ground.heightAt(line[2 * k - 2], line[2 * k - 1])
            return h0 + (h1 - h0) * (bestS / total)
        }

        /**
         * Kleiner Hoehenversatz nach der Richtung der Strasse (0..[DIR_LIFT_M], gleich fuer beide
         * Fahrtrichtungen, stetig entlang der Strasse und ueber Kachelgrenzen). Zwei gleichrangige
         * Strassen, die sich kreuzen, liegen so nicht mehr in derselben Ebene — vorher flimmerten
         * ihre Ueberlappungen an jedem Knoten (Z-Fighting).
         */
        private fun dirLift(r: RoadNet.Road, k: Int): Double {
            val a = atan2(r.rn[k], r.re[k])
            return DIR_LIFT_M * 0.5 * (1.0 + cos(2.0 * a))
        }

        fun segment(r: RoadNet.Road, i: Int, edges: Boolean, roadIndex: Int = -1) {
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
                    val h = heightAt(e, n, r, r.trackDist[k] < 50.0, k)
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
            if (edges) {
                edgeStrips(r, i, kind, temp, tex, vBase, he)
                clipInto(temp, mesh(kind, true), mask, { x -> x + ctx.spec.anchorE }, { z -> ctx.spec.anchorN - z }, roadIndex.toDouble(), roadIndex.toDouble())
                temp.clear()
            }
            if (spec.rail) rails(r, i)
        }

        /**
         * Ausgerundete Ecke an einer Einmuendung ([JunctionCorners]): Faecher vom Eckpunkt zum Bogen im Belag der
         * Strasse, Hoehe wie die Strasse dort; im Ort ein Bordstein entlang des Bogens.
         */
        fun corner(c: JunctionCorners.Corner) {
            val r = c.road
            val kind = r.spec.kind
            val tex = RouteTextures.spec(kind)
            val m = mesh(kind, false)
            val k = c.station.coerceIn(0, r.size - 1)
            fun h(e: Double, n: Double) = heightAt(e, n, r, true, k)
            fun col(e: Double, n: Double): Int {
                val cc = 0.80 + 0.14 * RouteGeometry.tone(e, n, 0x70e)
                return RouteGeometry.linColor(cc, cc, cc)
            }
            val a = c.arc
            val cnt = a.size / 2
            // Umlaufsinn: von oben gegen den Uhrzeigersinn in (e, n).
            val area = (a[0] - c.pe) * (a[2 * cnt - 1] - c.pn) - (a[1] - c.pn) * (a[2 * cnt - 2] - c.pe)
            val p = m.vertex(ctx.x(c.pe), ctx.y(h(c.pe, c.pn)), ctx.z(c.pn), 0.0, 1.0, 0.0, c.pe / tex.widthM, c.pn / tex.lengthM, col(c.pe, c.pn))
            val first = m.vertexCount
            for (q in 0 until cnt) {
                val e = a[2 * q]
                val n = a[2 * q + 1]
                m.vertex(ctx.x(e), ctx.y(h(e, n)), ctx.z(n), 0.0, 1.0, 0.0, e / tex.widthM, n / tex.lengthM, col(e, n))
            }
            for (q in 0 until cnt - 1) if (area > 0) m.tri(p, first + q, first + q + 1) else m.tri(p, first + q + 1, first + q)
            if (!c.urban) return
            // Bordstein: Ansicht vom Bogen bis 2 cm dahinter auf 12 cm, Oberseite bis zur Hinterkante.
            val kerb = out.mesh(WorldNames.NODE_ROADS, materials.getOrPut(MAT_KERB) { TileMaterial(MAT_KERB, roughness = 0.85f) })
            val b = c.arcBack
            val g = 0.30
            val top = RouteGeometry.linColor(g, g, g * 0.97)
            val face = RouteGeometry.linColor(g * 0.8, g * 0.8, g * 0.78)
            val base = kerb.vertexCount
            for (q in 0 until cnt) {
                val e = a[2 * q]; val n = a[2 * q + 1]
                val be = b[2 * q]; val bn = b[2 * q + 1]
                val fe = e + (be - e) * (0.02 / JunctionCorners.KERB_WIDTH_M)
                val fn = n + (bn - n) * (0.02 / JunctionCorners.KERB_WIDTH_M)
                val hh = h(e, n)
                val ox = e - be
                val oy = n - bn
                val ol = kotlin.math.max(1e-6, kotlin.math.hypot(ox, oy))
                // Normale der Ansicht: vom Bogen weg zum Eckpunkt hin (glTF: x = e, z = -n).
                kerb.vertex(ctx.x(e), ctx.y(hh - 0.01), ctx.z(n), ox / ol, 0.3, -oy / ol, 0.0, 0.0, face)
                kerb.vertex(ctx.x(fe), ctx.y(hh + RouteLayer.KERB_HEIGHT_M), ctx.z(fn), ox / ol, 0.3, -oy / ol, 0.0, 0.0, face)
                kerb.vertex(ctx.x(fe), ctx.y(hh + RouteLayer.KERB_HEIGHT_M), ctx.z(fn), 0.0, 1.0, 0.0, 0.0, 0.0, top)
                kerb.vertex(ctx.x(be), ctx.y(hh + RouteLayer.KERB_HEIGHT_M), ctx.z(bn), 0.0, 1.0, 0.0, 0.0, 0.0, top)
            }
            for (q in 0 until cnt - 1) {
                val i0 = base + 4 * q
                val i1 = base + 4 * (q + 1)
                if (area > 0) {
                    kerb.quad(i0, i1, i1 + 1, i0 + 1)
                    kerb.quad(i0 + 2, i1 + 2, i1 + 3, i0 + 3)
                } else {
                    kerb.quad(i1, i0, i0 + 1, i1 + 1)
                    kerb.quad(i1 + 2, i0 + 2, i0 + 3, i1 + 3)
                }
            }
        }

        /**
         * Weiche Kanten: je Seite ein Streifen, dessen Deckung nach aussen auf null faellt — die Deckung
         * kommt aus der Textur ([RouteTextures.roadEdgeTexture], `v` quer), die Vertexfarben sind deckend.
         */
        private fun edgeStrips(r: RoadNet.Road, i: Int, kind: SurfaceKind, m: MeshBuilder, tex: RouteTextures.Spec, vBase: Double, he: DoubleArray) {
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
                        // Innenkante knapp unter der Fahrbahn: Wo der Streifen an Knoten und in Kurven auf einer
                        // anderen Fahrbahn liegt, gewinnt die deckende Fahrbahn statt zu flimmern.
                        val h = if (o == 0) he[q * 2 + side] - EDGE_DROP_M else heightAt(e, n, r, r.trackDist[k] < 50.0, k) - 0.015
                        val tone = RouteGeometry.tone(e, n, 0x70e)
                        val c = (if (r.spec.rail) 0.52 else 0.80) + 0.14 * tone
                        idx[q * 2 + o] = m.vertex(
                            ctx.x(e), ctx.y(h), ctx.z(n), 0.0, 1.0, 0.0,
                            (r.s[k] - vBase) / tex.lengthM, o.toDouble(),
                            RouteGeometry.linColor(c, c, c),
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
                    val h = heightAt(r.e[k], r.n[k], r, r.trackDist[k] < 50.0, k) + 0.16
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
        const val MAT_ISLAND = "x_mat_road_island"
        const val MAT_ISLAND_KERB = "x_mat_road_island_kerb"

        /** Bordstein entlang ausgerundeter Ecken an Einmuendungen. */
        const val MAT_KERB = "x_mat_road_kerb"

        /** So hoch liegt eine Kreisel-Mittelinsel ueber dem Boden. */
        const val ISLAND_RISE_M = 0.14

        /** Abtastschritt der Strassen in Metern. */
        const val STEP_M = 4.0
        const val BASE_LIFT_M = 0.05
        const val RANK_LIFT_M = 0.012

        /** Groesster Versatz nach der Richtung, kleiner als ein Rangschritt. */
        const val DIR_LIFT_M = 0.008

        /** So tief liegt die Innenkante des weichen Randstreifens unter der Fahrbahn. */
        const val EDGE_DROP_M = 0.02

        /** So tief liegt eine einmuendende Strasse unter der Fahrbahn der Strecke. */
        const val UNDER_TRACK_M = 0.05

        /** Ueber so viele Meter ab dem Fahrbahnrand laeuft eine einmuendende Strasse auf ihre eigene Hoehe. */
        const val JOIN_M = 12.0

        /** So hoch liegt eine haertere Strasse ueber der Fahrbahn der Strecke, die sie quert. */
        const val OVER_TRACK_M = 0.03

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

        /** Naeher an der Strecke (Mitte) bekommen Strassen keine weichen Kanten — dort liegt der Rand der Strecke. */
        const val EDGE_FREE_TRACK_M = 16.0
        const val RAIL_GAUGE_M = 1.435

        fun roadMaterialName(kind: SurfaceKind): String = "x_mat_road_${kind.visual.textureKey}"
        fun edgeMaterialName(kind: SurfaceKind): String = "x_mat_road_${kind.visual.textureKey}_edge"
    }
}
