package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * # Baeume und Wald (Knoten `trees`)
 *
 * **Stufe 0:** Einzelbaeume im Band bis [NEAR_M] neben der Strecke, in vier
 * Arten ([TreeModels]: runder Laubbaum, schlanker Laubbaum, Fichte, kleiner
 * Feld-/Obstbaum) mit Groessen-, Breiten- und Farbvariation. Wo ein Baum steht,
 * entscheidet die Kronenbedeckung ([LandMap.canopyAt]): aus dem Luftbild der
 * Kachel geschaetzt ([canopyFromAerial]), sonst aus der Landnutzung (Wald
 * voll, Siedlung und Park vereinzelt). Ausserhalb des Bands steht der Wald als
 * **Waldkoerper**: ein huegeliges Kronendach in Baumhoehe ueber den
 * Waldzellen, das am Waldrand bis auf den Boden abfaellt (keine senkrechten
 * Waende), mit geglaetteten Normalen.
 *
 * **Stufe 1:** nur Waldkoerper (aus der Landnutzung), je Teilflaeche
 * gehaengt — sie verschwinden mit ihrem Boden, sobald die Kachel der Stufe 0
 * darunter gezeigt wird.
 *
 * Alles ist aus Welt-Koordinaten gewuerfelt (Gitter am Ursprung): Nachbar-
 * kacheln setzen dieselben Baeume, jede nur die mit Fusspunkt in ihrem
 * Rechteck. Kein Baum steht auf Fahrbahn, Strasse, Wasser oder in einem
 * Gebaeude.
 *
 * **Budget:** [TileBudget.vegetation] plus, was Fahrbahn, Strassen, Gebaeude
 * und Wasser dieser Kachel von ihrem Anteil nicht gebraucht haben (die
 * Schichten laufen davor; die Summe je Kachel, mit der die Planung rechnet,
 * bleibt eingehalten). Erst der Waldkoerper, dann bekommen alle Einzelbaeume
 * die einfachste Stufe, danach werden sie vom Weg nach aussen aufgewertet,
 * solange das Budget reicht.
 *
 * Materialien: [WorldNames.MAT_TREE_TRUNK] (Vertexfarben) und
 * [WorldNames.MAT_TREE_LEAVES] (Vertexfarben × kachelnde Laubtextur,
 * `MASK`, doppelseitig, wie im Vertrag; die Textur ist voll deckend).
 */
internal class TreesLayer : TileLayer {

    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.budget.vegetation <= 0) return
        val land = ctx.land
        if (ctx.level == 0 && ctx.aerialRaw != null && land is LandRaster) {
            land.setCanopy(ctx.spec, canopyFromAerial(ctx.aerialRaw))
        }
        val b = ctx.budget
        fun spare(budget: Int, node: String) = max(0, budget - out.triangles(node))
        val total = b.vegetation + spare(b.route, WorldNames.NODE_ROUTE) + spare(b.roads, WorldNames.NODE_ROADS) +
            spare(b.buildings, WorldNames.NODE_BUILDINGS) + spare(b.water, WorldNames.NODE_WATER)
        val leaves = if (ctx.level == 0) leavesMaterial(ctx) else farLeavesMaterial(ctx)
        val canopy = canopyMaterial(ctx)
        val volumeBudget = if (ctx.level == 0) b.vegetation / 4 else total
        var used = forestVolume(ctx, out, canopy, leaves, volumeBudget)
        if (ctx.level == 0) {
            used += undergrowth(ctx, out, leaves, min(UNDERGROWTH_MAX, (total - used) / 6))
            // Karten haben je Dreieck rund dreimal so viele Eckpunkt-Bytes wie geschlossene Koerper:
            // gedeckelt, damit die Welt auf der Platte nicht waechst (Richtwert 15 MB/km).
            singleTrees(ctx, out, leaves, min(total - used, SINGLE_TREE_MAX))
        }
    }

    // -----------------------------------------------------------------------
    // Waldkoerper
    // -----------------------------------------------------------------------

    /** Baut die Waldkoerper der Kachel; gibt die Zahl der Dreiecke zurueck. */
    private fun forestVolume(ctx: TileContext, out: TileOutput, canopy: TileMaterial, leaves: TileMaterial, budget: Int): Int {
        val s = ctx.spec
        var cell = if (ctx.level == 0) 8.0 else 32.0
        while (true) {
            val cells = (s.cellM / cell).toInt()
            val forest = forestCells(ctx, cells, cell)
            val count = forest.count { it }
            // Kronendach 2 Dreiecke je Zelle, Kreuzbilder am Rand und obenauf (Schaetzung nach oben)
            val tris = count * 2 + count * TreeModels.TUFT_TRIANGLES * 7 / 10
            if (tris <= budget || cell >= s.cellM / 4) {
                // In Stufe 0 lieber kein Waldkoerper als grobe Zellen (32–64 m lasen sich als flache Scherben).
                if (count == 0 || tris > budget || (ctx.level == 0 && cell > 16.0)) return 0
                return emitVolume(ctx, out, canopy, leaves, forest, cells, cell, budget)
            }
            cell *= 2
        }
    }

    private fun forestCells(ctx: TileContext, cells: Int, cell: Double): BooleanArray {
        val s = ctx.spec
        val out = BooleanArray(cells * cells)
        for (j in 0 until cells) for (i in 0 until cells) {
            out[j * cells + i] = isVolumeCell(ctx, s.minE + (i + 0.5) * cell, s.minN + (j + 0.5) * cell, cell)
        }
        return out
    }

    /**
     * Ob eine Zelle (Mitte `e`, `n`) zum Waldkoerper gehoert: Waldzelle mit mindestens drei
     * Waldnachbarn. Vereinzelte Zellen (Schatten im Luftbild, Baumgruppen) standen sonst als
     * dunkle Rauten und Zelte in offenen Flaechen.
     */
    private fun isVolumeCell(ctx: TileContext, e: Double, n: Double, cell: Double): Boolean {
        if (!isRawVolumeCell(ctx, e, n, cell)) return false
        var k = 0
        for (dj in -1..1) for (di in -1..1) {
            if (di == 0 && dj == 0) continue
            if (isRawVolumeCell(ctx, e + di * cell, n + dj * cell, cell) && ++k >= 3) return true
        }
        return false
    }

    private fun isRawVolumeCell(ctx: TileContext, e: Double, n: Double, cell: Double): Boolean {
        val forest = if (ctx.level == 0) ctx.land.canopyAt(e, n) >= 0.55 else ctx.land.classAt(e, n) == LandClass.FOREST
        if (!forest) return false
        if (ctx.level != 0) {
            // Kein Waldkoerper ueber der Strecke selbst (sie laeuft dort durch eine Schneise).
            val hit = ctx.track.nearest(e, n, cell)
            return hit == null || hit.distance > cell * 0.75
        }
        val hit = ctx.track.nearest(e, n, NEAR_M + cell)
        return hit == null || hit.distance > NEAR_M
    }

    private fun emitVolume(ctx: TileContext, out: TileOutput, canopy: TileMaterial, leaves: TileMaterial, forest: BooleanArray, cells: Int, cell: Double, budget: Int): Int {
        var used = 0
        val s = ctx.spec
        // Zellen ausserhalb der Kachel werden nach derselben Regel bewertet: Raender passen ueber Kachelgrenzen.
        val outside = HashMap<Long, Boolean>()
        fun isF(i: Int, j: Int): Boolean {
            if (i in 0 until cells && j in 0 until cells) return forest[j * cells + i]
            val key = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)
            return outside.getOrPut(key) { isVolumeCell(ctx, s.minE + (i + 0.5) * cell, s.minN + (j + 0.5) * cell, cell) }
        }
        // Ein Gitterpunkt liegt am Waldrand, wenn eine der vier Zellen um ihn kein Wald ist: dort liegt er am Boden.
        fun edgeVertex(i: Int, j: Int): Boolean = !(isF(i - 1, j - 1) && isF(i, j - 1) && isF(i - 1, j) && isF(i, j))
        // Abstand zum Waldrand in Gitterpunkten (0 = am Rand, 1, 2 = innen): das Kronendach steigt
        // ueber zwei Zellen an statt ueber eine — kein Kasten mit Steilwand.
        fun ringOf(i: Int, j: Int): Int {
            if (edgeVertex(i, j)) return 0
            for (dj in -1..1) for (di in -1..1) if (edgeVertex(i + di, j + dj)) return 1
            if (cell < 16.0) for (dj in -2..2) for (di in -2..2) if (edgeVertex(i + di, j + dj)) return 2
            return 3
        }
        val rings = HashMap<Long, Int>()
        fun edgeRing(i: Int, j: Int): Int = rings.getOrPut((i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)) { ringOf(i, j) }
        // Gitterpunkte waagrecht verschoben (aus Welt-Gitterzahlen, also ueber Kachelgrenzen gleich):
        // Der Waldrand folgt nicht mehr sichtbar dem Zellenraster.
        fun jitter(i: Int, j: Int, north: Boolean): Double {
            val wi = Math.round((s.minE + i * cell) / cell).toInt()
            val wj = Math.round((s.minN + j * cell) / cell).toInt()
            val amp = if (edgeRing(i, j) == 0) 0.32 else 0.2
            return (Noise.hash01(wi, wj, if (north) SEED_JIT_N else SEED_JIT_E) - 0.5) * 2 * amp * cell
        }
        fun fill(i: Int, j: Int): Double = when (edgeRing(i, j)) {
            0 -> 0.0
            1 -> if (cell < 16.0) 0.5 else 0.62
            2 -> 0.86
            else -> 1.0
        }
        val tops = HashMap<Long, Double>()
        fun top(i: Int, j: Int): Double {
            val key = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)
            return tops.getOrPut(key) {
                val e = s.minE + i * cell + jitter(i, j, false)
                val n = s.minN + j * cell + jitter(i, j, true)
                val g = ctx.ground.heightAt(e, n)
                if (edgeRing(i, j) == 0) {
                    g - 0.4
                } else {
                    // Stetige Wellen im Kronendach (statt Zufall je Gitterpunkt, der Facetten zeigte).
                    val bump = (Noise.value(e / 21.0, n / 21.0, SEED_BUMP) - 0.5) * 4.0
                    val h = canopyHeight(ctx, e, n) + bump
                    // Anteil der vollen Hoehe, mit den vier Nachbarn geglaettet: Kleine Waldflecken
                    // werden flache Kuppen statt spitzer Zelte.
                    val f = 0.5 * fill(i, j) + 0.125 * (fill(i - 1, j) + fill(i + 1, j) + fill(i, j - 1) + fill(i, j + 1))
                    g + h * f
                }
            }
        }
        for (j in 0 until cells) for (i in 0 until cells) {
            if (!forest[j * cells + i]) continue
            val e0 = s.minE + i * cell
            val n0 = s.minN + j * cell
            val m = out.mesh(WorldNames.NODE_TREES, canopy, e0 + cell / 2, n0 + cell / 2)
            val corners = arrayOf(intArrayOf(i, j), intArrayOf(i + 1, j), intArrayOf(i + 1, j + 1), intArrayOf(i, j + 1))
            val idx = IntArray(4)
            for ((k, c) in corners.withIndex()) {
                val e = s.minE + c[0] * cell + jitter(c[0], c[1], false)
                val n = s.minN + c[1] * cell + jitter(c[0], c[1], true)
                val y = top(c[0], c[1])
                val de = (top(c[0] + 1, c[1]) - top(c[0] - 1, c[1])) / (2 * cell)
                val dn = (top(c[0], c[1] + 1) - top(c[0], c[1] - 1)) / (2 * cell)
                // Normalen flacher als die Geometrie: das Kronendach wirkt weich, Raender nicht wie Wande.
                val nor = Vec3(-de * 0.55, 1.0, dn * 0.55).normalized()
                val ring = edgeRing(c[0], c[1])
                val shade = when (ring) {
                    0 -> 0.5
                    1 -> 0.62
                    else -> 0.68 + 0.26 * Noise.value(e / 13.0, n / 13.0, SEED_TINT)
                }
                idx[k] = m.vertex(ctx.x(e), ctx.y(y), ctx.z(n), nor.x, nor.y, nor.z, e / FoliageAtlas.CANOPY_M, -n / FoliageAtlas.CANOPY_M, crownColor(ctx, e, n, shade))
            }
            // (i,j) → (i+1,j) → (i+1,j+1) → (i,j+1) laeuft in (e, n) gegen den Uhrzeigersinn: oben sichtbar.
            // Diagonale entlang der kleineren Hoehendifferenz, damit Raender nicht zackig einknicken.
            if (abs(top(i, j) - top(i + 1, j + 1)) <= abs(top(i + 1, j) - top(i, j + 1))) {
                m.quad(idx[0], idx[1], idx[2], idx[3])
            } else {
                m.quad(idx[1], idx[2], idx[3], idx[0])
            }
            used += 2
        }
        // Kronen statt glatter Huegel: Am Waldrand stehen Kreuzbilder ganzer Baeume vor der
        // Rampe des Kronendachs (Vorderseite nach draussen), im Innern ragen vereinzelt
        // Kronenspitzen aus dem Dach — die Silhouette gegen den Himmel wird kronig.
        val conColor = TreeModels.leafBase(TreeModels.Species.CONIFER)
        val broadColor = TreeModels.leafBase(TreeModels.Species.BROAD)
        for (j in 0 until cells) for (i in 0 until cells) {
            if (!forest[j * cells + i]) continue
            val e0 = s.minE + i * cell
            val n0 = s.minN + j * cell
            val wi = Math.round(e0 / cell).toInt()
            val wj = Math.round(n0 / cell).toInt()
            var oe = 0.0
            var on = 0.0
            if (!isF(i - 1, j)) oe -= 1.0
            if (!isF(i + 1, j)) oe += 1.0
            if (!isF(i, j - 1)) on -= 1.0
            if (!isF(i, j + 1)) on += 1.0
            val edge = oe != 0.0 || on != 0.0
            val coarse = cell > 16.0
            val topShare = if (coarse) 0.0 else 0.3
            val top = Noise.hash01(wi, wj, SEED_TOP)
            val per = when {
                edge -> if (coarse) 1 else 2 // nah zwei Randbaeume je Zelle: die Rampe des Kronendachs bleibt am Hang verdeckt
                top < topShare -> 1
                else -> 0
            }
            for (k in 0 until per) {
                if (used + TreeModels.TUFT_TRIANGLES > budget) return used
                val r1 = Noise.hash01(wi, wj, SEED_BB + k)
                val r2 = Noise.hash01(wi, wj, SEED_BB + 17 + k)
                val r3 = Noise.hash01(wi, wj, SEED_BB + 31 + k)
                var e = e0 + cell * (0.15 + 0.7 * r1)
                var n = n0 + cell * (0.15 + 0.7 * r2)
                val ol = kotlin.math.hypot(oe, on)
                if (edge) {
                    // nach aussen geschoben: deckt die Rampe des Kronendachs
                    e += oe / ol * cell * 0.45
                    n += on / ol * cell * 0.45
                }
                val conifer = Noise.hash01(wi, wj, SEED_BB + 47 + k) < ctx.land.coniferShare(e, n)
                val ch = canopyHeight(ctx, e, n) * (0.85 + 0.3 * r3)
                val g = ctx.ground.heightAt(e, n)
                val height: Double
                val base: Double
                if (edge) {
                    height = ch * 1.05
                    base = g
                } else {
                    height = ch * 0.75
                    base = g + ch - height * 0.62
                }
                val width = height * (if (conifer) 0.42 else 0.62)
                val toward = if (edge) Vec3(oe / ol, 0.0, -on / ol) else towardTrack(ctx, e, n)
                val m = out.mesh(WorldNames.NODE_TREES, leaves, e, n)
                TreeModels.billboardTree(
                    m, ctx.x(e), ctx.y(base), ctx.z(n), height, width, region(ctx.level, conifer), r1 * 6.283, toward,
                    if (conifer) conColor else broadColor, 0.8 + 0.25 * r2, (r3 - 0.5) * 0.6,
                    up = if (edge) 0.35 else 1.1,
                )
                used += TreeModels.TUFT_TRIANGLES
            }
        }
        return used
    }

    private fun region(level: Int, conifer: Boolean): FoliageAtlas.Region = when {
        level == 0 && conifer -> FoliageAtlas.TREE_CONIFER
        level == 0 -> FoliageAtlas.TREE_BROAD
        conifer -> FoliageAtlas.FAR_TREE_CONIFER
        else -> FoliageAtlas.FAR_TREE_BROAD
    }

    /** Hoehe des Kronendachs ueber dem Boden, sanft wellig (Bestaende von einigen hundert Metern). */
    private fun canopyHeight(ctx: TileContext, e: Double, n: Double): Double {
        ctx.land.canopyHeightAt(e, n).let { if (it.isFinite()) return it.coerceIn(4.0, 40.0) }
        val f = Noise.fbm(e / 160.0, n / 160.0, SEED_HEIGHT, 3)
        val conifer = ctx.land.coniferShare(e, n)
        return (17.0 + 3.0 * conifer + 5.0 * f).coerceIn(11.0, 26.0)
    }

    // -----------------------------------------------------------------------
    // Einzelbaeume
    // -----------------------------------------------------------------------

    private class Candidate(val e: Double, val n: Double, val dist: Double, val spec: TreeModels.Spec) {
        var lod = TreeModels.Lod.FAR
    }

    /** Setzt Einzelbaeume (nur Stufe 0) bis [budget] Dreiecke; gibt die verbrauchten zurueck. */
    private fun singleTrees(ctx: TileContext, out: TileOutput, leaves: TileMaterial, budget: Int): Int {
        if (budget <= 0) return 0
        val s = ctx.spec
        // Die Kachel muss das Band beruehren.
        if (ctx.track.nearest(s.anchorE, s.anchorN, s.cellM * 0.71 + NEAR_M) == null) return 0
        val net = RoadNet(ctx, marginM = 6.0)
        val buildings = ctx.map.buildings(s.minE - 4, s.minN - 4, s.maxE + 4, s.maxN + 4)
        val cands = ArrayList<Candidate>()
        // Gemessene Baeume (Laserdaten): echte Lage und Hoehe statt Wuerfelgitter.
        val laserTrees = ctx.land.laserTrees(s)
        laserTrees?.forEach { t ->
            val hit = ctx.track.nearest(t.e, t.n, NEAR_M + 4.0) ?: return@forEach
            if (hit.distance < ctx.track.halfWidthAt(hit.distM) + LASER_TRACK_CLEARANCE_M) return@forEach
            val cls = ctx.land.classAt(t.e, t.n)
            if (cls == LandClass.WATER || net.clearance(t.e, t.n) < ROAD_CLEARANCE_M || nearBuilding(buildings, t.e, t.n)) return@forEach
            val spec = laserTreeSpec(specFor(ctx, cls, t.e, t.n, floor(t.e).toInt(), floor(t.n).toInt(), 1.0, hit.distance), t, hit.distance - ctx.track.halfWidthAt(hit.distM), ctx.land.coniferShare(t.e, t.n), ctx.ground.heightAt(t.e, t.n) - ctx.track.poseAt(hit.distM).u) ?: return@forEach
            cands += Candidate(t.e, t.n, hit.distance, spec)
        }
        // Ohne Laserdaten: Obstwiesen in Reihen, Alleen dort, wo das Luftbild eine Baumreihe am Strassenrand zeigt.
        val allee = HashSet<Long>()
        if (laserTrees == null) {
            orchardTrees(ctx, net, buildings, cands)
            alleeTrees(ctx, net, buildings, cands, allee)
        }
        forestEdgeShrubs(ctx, net, buildings, cands)
        if (laserTrees == null) for ((spacing, from, to) in listOf(Triple(DENSE_SPACING_M, 0.0, DENSE_M), Triple(OPEN_SPACING_M, DENSE_M, NEAR_M + 4.0))) {
            val i0 = floor(s.minE / spacing).toInt()
            val i1 = floor(s.maxE / spacing).toInt()
            val j0 = floor(s.minN / spacing).toInt()
            val j1 = floor(s.maxN / spacing).toInt()
            for (j in j0..j1) for (i in i0..i1) {
                val h = Noise.hash(i, j, if (spacing == DENSE_SPACING_M) SEED_DENSE else SEED_OPEN)
                val e = (i + 0.1 + 0.8 * ((h ushr 8 and 0xFFF) / 4096.0)) * spacing
                val n = (j + 0.1 + 0.8 * ((h ushr 20 and 0xFFF) / 4096.0)) * spacing
                if (!s.contains(e, n)) continue
                val hit = ctx.track.nearest(e, n, to + 1.0) ?: continue
                if (hit.distance < from || hit.distance >= to) continue
                if (hit.distance < ctx.track.halfWidthAt(hit.distM) + TRACK_CLEARANCE_M) continue
                // Auch geschlossener Wald hat Luecken (Licht auf dem Waldboden).
                val p = ctx.land.canopyAt(e, n) * 0.85
                if ((h and 0xFF) / 256.0 >= p) continue
                val cls = ctx.land.classAt(e, n)
                if (cls == LandClass.WATER || cls == LandClass.WETLAND || cls == LandClass.ORCHARD) continue
                if (allee.isNotEmpty() && hit.distance - ctx.track.halfWidthAt(hit.distM) < 12.0 && inAllee(ctx, allee, e, n, hit.distM)) continue
                if (net.clearance(e, n) < ROAD_CLEARANCE_M) continue
                if (nearBuilding(buildings, e, n)) continue
                var spec = specFor(ctx, cls, e, n, i, j, spacing, hit.distance)
                // Fichten nah am Weg nicht ueber die Fahrbahn ragen lassen (Krone ~0,22 × Hoehe):
                // sonst standen sie als dunkle Wand vor der Kamera.
                if (spec.species == TreeModels.Species.CONIFER) {
                    val room = hit.distance - ctx.track.halfWidthAt(hit.distM) + 0.5
                    val maxH = room / (0.22 * spec.width)
                    if (maxH < 6.0) continue
                    if (spec.heightM > maxH) spec = TreeModels.Spec(spec.species, maxH, spec.width, spec.tint, spec.seed, spec.turn, spec.pale, spec.bare, spec.hue)
                }
                cands += Candidate(e, n, hit.distance, spec)
            }
        }
        cands.sortBy { it.dist }
        // Stufen vergeben: alle einfach, dann vom Weg nach aussen aufwerten, solange das Budget reicht.
        var used = 0
        val placed = ArrayList<Candidate>()
        for (c in cands) {
            val cost = TreeModels.cost(c.spec, TreeModels.Lod.FAR)
            if (used + cost > budget) break
            used += cost
            placed += c
        }
        // Vom Weg nach aussen: die naechsten Baeume bekommen zuerst die volle Stufe (sonst verbrauchte
        // im dichten Wald schon die mittlere Stufe das Budget, und neben dem Weg stuende keine Blattkrone).
        for (c in placed) {
            if (c.dist > MID_M) break
            val targets = if (c.dist <= DETAIL_M) listOf(TreeModels.Lod.NEAR, TreeModels.Lod.MID) else listOf(TreeModels.Lod.MID)
            for (target in targets) {
                val delta = TreeModels.cost(c.spec, target) - TreeModels.cost(c.spec, c.lod)
                if (used + delta <= budget) {
                    used += delta
                    c.lod = target
                    break
                }
            }
        }
        val bark = ctx.textures.get("x_bark", jpegQuality = 84) { FoliageAtlas.barkImage() }
        val trunkMat = TileMaterial(WorldNames.MAT_TREE_TRUNK, texture = bark, repeat = true, roughness = 0.95f, noSpecular = true)
        val trunks = out.mesh(WorldNames.NODE_TREES, trunkMat)
        val crowns = out.mesh(WorldNames.NODE_TREES, leaves)
        val scratchTrunk = MeshBuilder(64, 64)
        val scratchCrown = MeshBuilder(256, 256)
        for (c in placed) {
            scratchTrunk.clear()
            scratchCrown.clear()
            val hit = ctx.track.nearest(c.e, c.n, NEAR_M + 10.0)
            val toward = if (hit == null) Vec3(0.0, 0.0, 0.0) else {
                val p = ctx.track.poseAt(hit.distM)
                Vec3(p.e - c.e, 0.0, -(p.n - c.n)).normalized(Vec3(0.0, 0.0, 0.0))
            }
            TreeModels.build(c.spec, c.lod, scratchTrunk, scratchCrown, toward)
            val g = ctx.ground.heightAt(c.e, c.n)
            val m = Mat4.translation(ctx.x(c.e), ctx.y(g), ctx.z(c.n))
            trunks.append(scratchTrunk, m)
            crowns.append(scratchCrown, m)
        }
        return used
    }

    /**
     * Obstwiese ([LandClass.ORCHARD]): Obstbaeume in Reihen (10 × 8,5 m, Richtung regional gewuerfelt,
     * wenige Meter Versatz, einzelne Luecken), dazwischen gelegentlich ein alter, grosser Birnbaum.
     * Aus Welt-Koordinaten gewuerfelt — Nachbarkacheln setzen dieselben Baeume.
     */
    private fun orchardTrees(ctx: TileContext, net: RoadNet, buildings: List<MapBuilding>, cands: MutableList<Candidate>) {
        val s = ctx.spec
        val ang = Noise.value(s.anchorE / 900.0, s.anchorN / 900.0, SEED_ORCH) * Math.PI
        val ca = kotlin.math.cos(ang)
        val sa = kotlin.math.sin(ang)
        // Kachelrechteck im gedrehten Gitter (Zeilen u entlang, Reihen v quer)
        val corners = listOf(s.minE to s.minN, s.maxE to s.minN, s.maxE to s.maxN, s.minE to s.maxN)
        val us = corners.map { (e, n) -> e * ca + n * sa }
        val vs = corners.map { (e, n) -> -e * sa + n * ca }
        val i0 = floor(us.min() / ORCHARD_STEP_M).toInt()
        val i1 = floor(us.max() / ORCHARD_STEP_M).toInt()
        val j0 = floor(vs.min() / ORCHARD_ROW_M).toInt()
        val j1 = floor(vs.max() / ORCHARD_ROW_M).toInt()
        for (j in j0..j1) for (i in i0..i1) {
            val h = Noise.hash(i, j, SEED_ORCH)
            if ((h and 0xFF) < 28) continue // Luecke
            val u = (i + 0.5 + ((h ushr 8 and 0xFF) / 255.0 - 0.5) * 0.18) * ORCHARD_STEP_M
            val v = (j + 0.5 + ((h ushr 16 and 0xFF) / 255.0 - 0.5) * 0.12) * ORCHARD_ROW_M
            val e = u * ca - v * sa
            val n = u * sa + v * ca
            if (!s.contains(e, n)) continue
            if (ctx.land.classAt(e, n) != LandClass.ORCHARD) continue
            val hit = ctx.track.nearest(e, n, NEAR_M + 4.0) ?: continue
            if (hit.distance < ctx.track.halfWidthAt(hit.distM) + TRACK_CLEARANCE_M) continue
            if (net.clearance(e, n) < ROAD_CLEARANCE_M || nearBuilding(buildings, e, n)) continue
            val base = specFor(ctx, LandClass.ORCHARD, e, n, i, j, ORCHARD_STEP_M, hit.distance)
            val old = Noise.hash01(i, j, SEED_ORCH + 1) < 0.1
            val spec = TreeModels.Spec(
                TreeModels.Species.SMALL,
                if (old) 9.0 + 3.0 * Noise.hash01(i, j, SEED_ORCH + 2) else 5.0 + 3.2 * Noise.hash01(i, j, SEED_ORCH + 2),
                0.9 + 0.25 * Noise.hash01(i, j, SEED_ORCH + 3), base.tint, base.seed, base.turn, false, 0.05, base.hue, variant = 1,
            )
            cands += Candidate(e, n, hit.distance, spec)
        }
    }

    /**
     * Allee (ohne Laserdaten): Wo das Luftbild ueber mindestens vier Plaetze hintereinander
     * (≈ 38 m) eine Baumreihe am Fahrbahnrand zeigt (Kronenbedeckung ≥ 0,45 in 3–6 m Abstand) und
     * die Strecke eine Strasse ausserhalb des Walds ist, stehen dort Baeume derselben Art und
     * Groesse in festem Abstand ([ALLEE_STEP_M]) statt gewuerfelter Einzelbaeume. Die belegten
     * Plaetze kommen nach [allee] (Seite, Platz), damit das Wuerfelgitter dort nichts dazusetzt.
     */
    private fun alleeTrees(ctx: TileContext, net: RoadNet, buildings: List<MapBuilding>, cands: MutableList<Candidate>, allee: MutableSet<Long>) {
        val s = ctx.spec
        val t = ctx.track.track
        var k0 = Int.MAX_VALUE
        var k1 = Int.MIN_VALUE
        for (i in 0 until t.sampleCount) {
            val e = t.e[i].toDouble()
            val n = t.n[i].toDouble()
            if (e < s.minE - 20 || e > s.maxE + 20 || n < s.minN - 20 || n > s.maxN + 20) continue
            val k = Math.round(t.distAt(i) / ALLEE_STEP_M).toInt()
            k0 = min(k0, k); k1 = max(k1, k)
        }
        if (k0 > k1) return
        fun spot(k: Int, side: Int): DoubleArray? {
            val d = k * ALLEE_STEP_M
            if (d < 0 || d > t.lengthM) return null
            val sec = ctx.track.surfaceAt(d)
            if (sec.highway !in ALLEE_ROADS) return null
            val p = ctx.track.poseAt(d)
            val hw = ctx.track.halfWidthAt(d)
            val th = Math.toRadians(p.headingDeg)
            val fe = kotlin.math.sin(th)
            val fn = kotlin.math.cos(th)
            val off = hw + 3.4
            val e = p.e + fn * side * off
            val n = p.n - fe * side * off
            val cls = ctx.land.classAt(e, n)
            if (cls == LandClass.FOREST || cls == LandClass.WATER || cls == LandClass.WETLAND) return null
            // Krone in 3–6 m Abstand vom Rand (zwei Proben)
            val c1 = ctx.land.canopyAt(p.e + fn * side * (hw + 3.0), p.n - fe * side * (hw + 3.0))
            val c2 = ctx.land.canopyAt(p.e + fn * side * (hw + 6.0), p.n - fe * side * (hw + 6.0))
            return if (max(c1, c2) >= 0.45) doubleArrayOf(e, n) else null
        }
        for (side in intArrayOf(-1, 1)) {
            val ok = HashMap<Int, DoubleArray?>()
            fun at(k: Int) = ok.getOrPut(k) { spot(k, side) }
            for (k in k0..k1) {
                val here = at(k) ?: continue
                // Lauf von mindestens vier belegten Plaetzen, in dem k liegt
                var a = k
                while (a > k - 4 && at(a - 1) != null) a--
                var b = k
                while (b < k + 4 && at(b + 1) != null) b++
                if (b - a + 1 < 4) continue
                val e = here[0]
                val n = here[1]
                if (!s.contains(e, n)) { allee += alleeKey(side, k); continue }
                if (net.clearance(e, n) < 1.5 || nearBuilding(buildings, e, n)) continue
                val hit = ctx.track.nearest(e, n, NEAR_M) ?: continue
                if (hit.distance < ctx.track.halfWidthAt(hit.distM) + 2.0) continue
                // gleiche Art und Groesse je Abschnitt von 200 m und Seite
                val run = Math.floorDiv(k, 21)
                val rH = Noise.hash01(run, side, SEED_ALLEE)
                val species = if (Noise.hash01(run, side, SEED_ALLEE + 1) < 0.25) TreeModels.Species.TALL else TreeModels.Species.BROAD
                val spec = TreeModels.Spec(
                    species, (13.0 + 6.0 * rH) * (0.94 + 0.12 * Noise.hash01(k, side, SEED_ALLEE + 2)),
                    0.78, 0.92 + 0.12 * Noise.hash01(k, side, SEED_ALLEE + 3), Noise.hash(k, side, SEED_ALLEE + 4),
                    Noise.hash01(k, side, SEED_ALLEE + 5) * 6.283, false, 0.3, (rH - 0.5) * 0.4, variant = 0,
                )
                cands += Candidate(e, n, hit.distance, spec)
                allee += alleeKey(side, k)
            }
        }
    }

    /**
     * Waldsaum: Wo die Strecke an einem Waldrand vorbeifuehrt (Kronenbedeckung springt quer zur
     * Strecke ueber 0,6), stehen vor dem Rand Straeucher und junge Baeume (Mantel) statt einer harten
     * Kante aus Staemmen. Plaetze alle [EDGE_STEP_M] je Seite, aus dem Laufmeter gewuerfelt (ueber
     * Kachelgrenzen gleich); nur wo der Rand mindestens 4 m neben der Fahrbahn liegt.
     */
    private fun forestEdgeShrubs(ctx: TileContext, net: RoadNet, buildings: List<MapBuilding>, cands: MutableList<Candidate>) {
        val s = ctx.spec
        val t = ctx.track.track
        var k0 = Int.MAX_VALUE
        var k1 = Int.MIN_VALUE
        for (i in 0 until t.sampleCount) {
            val e = t.e[i].toDouble()
            val n = t.n[i].toDouble()
            if (e < s.minE - 50 || e > s.maxE + 50 || n < s.minN - 50 || n > s.maxN + 50) continue
            val k = Math.round(t.distAt(i) / EDGE_STEP_M).toInt()
            k0 = min(k0, k); k1 = max(k1, k)
        }
        if (k0 > k1) return
        for (k in k0..k1) for (side in intArrayOf(-1, 1)) {
            val d = k * EDGE_STEP_M + (Noise.hash01(k, side, SEED_EDGE) - 0.5) * EDGE_STEP_M * 0.6
            if (d < 0 || d > t.lengthM) continue
            val p = ctx.track.poseAt(d)
            val hw = ctx.track.halfWidthAt(d)
            val th = Math.toRadians(p.headingDeg)
            val fe = kotlin.math.sin(th)
            val fn = kotlin.math.cos(th)
            // erster Sprung in den Bestand quer zur Strecke
            var edge = -1.0
            var off = hw + 3.0
            if (ctx.land.canopyAt(p.e + fn * side * off, p.n - fe * side * off) >= 0.6) continue // Strecke im Wald
            while (off < 40.0) {
                off += 2.0
                if (ctx.land.canopyAt(p.e + fn * side * off, p.n - fe * side * off) >= 0.6) { edge = off; break }
            }
            if (edge < hw + 4.0) continue
            val count = 1 + (Noise.hash01(k, side, SEED_EDGE + 1) * 2.6).toInt()
            for (q in 0 until count) {
                val r1 = Noise.hash01(k * 4 + q, side, SEED_EDGE + 2)
                val r2 = Noise.hash01(k * 4 + q, side, SEED_EDGE + 3)
                val o = edge - 0.8 - 2.6 * r1
                val along = (r2 - 0.5) * EDGE_STEP_M * 0.8
                val e = p.e + fn * side * o + fe * along
                val n = p.n - fe * side * o + fn * along
                if (!s.contains(e, n)) continue
                val cls = ctx.land.classAt(e, n)
                if (cls == LandClass.WATER || cls == LandClass.WETLAND) continue
                val hit = ctx.track.nearest(e, n, NEAR_M) ?: continue
                if (hit.distance < ctx.track.halfWidthAt(hit.distM) + 2.5) continue
                if (net.clearance(e, n) < 1.5 || nearBuilding(buildings, e, n, 1.5)) continue
                val young = Noise.hash01(k * 4 + q, side, SEED_EDGE + 4) < 0.3
                val spec = TreeModels.Spec(
                    if (young) TreeModels.Species.SMALL else TreeModels.Species.BUSH,
                    if (young) 4.5 + 3.0 * r2 else 1.6 + 2.4 * r1,
                    0.85 + 0.35 * r2, 0.86 + 0.24 * r1, Noise.hash(k * 4 + q, side, SEED_EDGE + 5), r2 * 6.283, false, 0.05,
                    (Noise.value(e / 160.0, n / 160.0, SEED_HUE) - 0.5) * 0.9 + (r1 - 0.5) * 0.4, variant = 0,
                )
                cands += Candidate(e, n, hit.distance, spec)
            }
        }
    }

    private fun alleeKey(side: Int, k: Int): Long = (k.toLong() shl 1) or (if (side > 0) 1L else 0L)

    /** Ob (e, n) neben einem Alleeplatz liegt (Laufmeter [distM] der naechsten Stelle der Strecke). */
    private fun inAllee(ctx: TileContext, allee: Set<Long>, e: Double, n: Double, distM: Double): Boolean {
        val p = ctx.track.poseAt(distM)
        val th = Math.toRadians(p.headingDeg)
        val side = if ((e - p.e) * kotlin.math.cos(th) - (n - p.n) * kotlin.math.sin(th) >= 0) 1 else -1
        val k = Math.round(distM / ALLEE_STEP_M).toInt()
        return (k - 1..k + 1).any { alleeKey(side, it) in allee }
    }

    /** Art, Groesse und Farbe eines Baums — aus Landnutzung, Kronenbedeckung und Zufall des Gitterpunkts. */
    private fun specFor(ctx: TileContext, cls: LandClass, e: Double, n: Double, i: Int, j: Int, spacing: Double, trackDist: Double): TreeModels.Spec {
        val seed = Noise.hash(i, j, (spacing * 10).toInt() + 0x5eed)
        fun r(k: Int) = Noise.hash01(i, j, seed + k)
        val inForest = cls == LandClass.FOREST
        // Kiefern regional gehaeuft (Bestaende von einigen hundert Metern), sonst Fichten.
        val pineShare = 0.55 * smoothstep(0.45, 0.75, Noise.value(e / 420.0, n / 420.0, SEED_PINE))
        val canopy = ctx.land.canopyAt(e, n)
        val species = if (inForest) {
            when {
                // Unterholz und junge Baeume: verdecken im Mittelgrund die Staemme
                r(8) < (if (trackDist < DENSE_M) 0.24 else 0.14) -> TreeModels.Species.BUSH
                r(12) < 0.12 -> TreeModels.Species.SMALL
                r(1) < ctx.land.coniferShare(e, n) -> if (r(9) < pineShare) TreeModels.Species.PINE else TreeModels.Species.CONIFER
                r(2) < 0.22 -> TreeModels.Species.TALL
                else -> TreeModels.Species.BROAD
            }
        } else {
            val x = r(1)
            val bush = if (canopy < 0.5) 0.2 else 0.1
            when {
                x < bush -> TreeModels.Species.BUSH
                x < bush + 0.06 -> if (r(9) < pineShare) TreeModels.Species.PINE else TreeModels.Species.CONIFER
                x < bush + 0.22 -> TreeModels.Species.TALL
                x < bush + (if (cls == LandClass.RESIDENTIAL || cls == LandClass.MEADOW) 0.5 else 0.38) -> TreeModels.Species.SMALL
                else -> TreeModels.Species.BROAD
            }
        }
        val h = when (species) {
            TreeModels.Species.BROAD -> if (inForest) 13.0 + 8.0 * r(3) else 8.0 + 8.0 * r(3)
            TreeModels.Species.TALL -> if (inForest) 14.0 + 7.0 * r(3) else 10.0 + 9.0 * r(3)
            TreeModels.Species.CONIFER -> if (inForest) 14.0 + 10.0 * r(3) else 9.0 + 8.0 * r(3)
            TreeModels.Species.PINE -> if (inForest) 15.0 + 8.0 * r(3) else 10.0 + 7.0 * r(3)
            TreeModels.Species.SMALL -> 4.5 + 3.5 * r(3)
            TreeModels.Species.BUSH -> 1.2 + 2.0 * r(3)
        }
        val regional = (Noise.value(e / 160.0, n / 160.0, SEED_HUE) - 0.5) * 0.9
        // Wuchsform: Eichen frei im Offenland und regional gehaeuft im Wald, Saeulenpappeln nur im
        // Offenland, Obstbaeume an Siedlung, Wiese, Obstwiese, Park; Birken im Wald seltener.
        val pale = species == TreeModels.Species.TALL && r(7) < (if (inForest) 0.32 else 0.5)
        val oakShare = if (inForest) 0.12 + 0.36 * Noise.value(e / 520.0, n / 520.0, SEED_OAK) else 0.45
        val variant = when (species) {
            TreeModels.Species.BROAD -> if (r(13) < oakShare) 1 else 0
            TreeModels.Species.TALL -> if (!inForest && !pale && r(14) < 0.35) 1 else 0
            TreeModels.Species.SMALL -> if (cls in FRUIT_LAND && r(15) < 0.7) 1 else 0
            else -> 0
        }
        return TreeModels.Spec(
            species = species,
            heightM = h,
            width = 0.82 + 0.36 * r(4),
            tint = 0.84 + 0.3 * r(5),
            seed = seed,
            turn = r(6) * 6.283,
            pale = pale,
            // Fichte: kahler Stammanteil; Laubbaum im Bestand: Kronenansatz (tiefer und damit breiter als frei, Kronendach schliesst)
            bare = when {
                species == TreeModels.Species.CONIFER && inForest -> (if (trackDist < DENSE_M) 0.26 else 0.14) + 0.2 * r(10)
                species == TreeModels.Species.BROAD && inForest -> 0.2 + 0.06 * r(10)
                else -> 0.05
            },
            hue = regional + (r(11) - 0.5) * 0.5,
            variant = variant,
        )
    }

    // -----------------------------------------------------------------------
    // Bodenbewuchs
    // -----------------------------------------------------------------------

    /**
     * Gras-, Kraut- und Farnbueschel am Wegrand (nur Stufe 0): Plaetze in festen
     * Abstaenden entlang der Strecke (aus dem Laufmeter gewuerfelt, also ueber
     * Kachelgrenzen gleich), beidseits 0,5–5 m neben dem Fahrbahnrand (im Wald bis
     * 9 m), fleckig verteilt, nie auf Strassen, Wasser oder in Gebaeuden. Gibt die
     * verbrauchten Dreiecke zurueck.
     */
    private fun undergrowth(ctx: TileContext, out: TileOutput, leaves: TileMaterial, budget: Int): Int {
        if (budget < TreeModels.TUFT_TRIANGLES) return 0
        val s = ctx.spec
        val tl = ctx.track
        val t = tl.track
        // Laufmeter-Bereiche, in denen die Strecke die (erweiterte) Kachel beruehrt
        val margin = 14.0
        val ranges = ArrayList<DoubleArray>()
        for (i in 0 until t.sampleCount) {
            val e = t.e[i].toDouble()
            val n = t.n[i].toDouble()
            if (e < s.minE - margin || e > s.maxE + margin || n < s.minN - margin || n > s.maxN + margin) continue
            val a = t.distAt(i) - t.stepM
            val b = t.distAt(i) + t.stepM
            if (ranges.isNotEmpty() && ranges.last()[1] >= a) ranges.last()[1] = b else ranges += doubleArrayOf(a, b)
        }
        if (ranges.isEmpty()) return 0
        val net = RoadNet(ctx, marginM = 4.0)
        val buildings = ctx.map.buildings(s.minE - 4, s.minN - 4, s.maxE + 4, s.maxN + 4)
        val m = out.mesh(WorldNames.NODE_TREES, leaves)
        val grassColor = intArrayOf(126, 150, 70)
        val fernColor = intArrayOf(96, 140, 56)
        var used = 0
        for (r in ranges) {
            var k = kotlin.math.ceil(max(0.0, r[0]) / UG_STEP_M).toInt()
            while (k * UG_STEP_M <= min(r[1], t.lengthM)) {
                val d = k * UG_STEP_M
                for (side in intArrayOf(-1, 1)) {
                    val h1 = Noise.hash01(k, side, SEED_UG)
                    val h2 = Noise.hash01(k, side, SEED_UG + 1)
                    val h3 = Noise.hash01(k, side, SEED_UG + 2)
                    val h4 = Noise.hash01(k, side, SEED_UG + 3)
                    val pose = tl.poseAt(d + (h4 - 0.5) * UG_STEP_M)
                    val hw = tl.halfWidthAt(d)
                    val th = Math.toRadians(pose.headingDeg)
                    val fe = kotlin.math.sin(th)
                    val fn = kotlin.math.cos(th)
                    // rechts der Fahrtrichtung = (fn, −fe)
                    val probeE = pose.e + fn * side * (hw + 3.0)
                    val probeN = pose.n - fe * side * (hw + 3.0)
                    val forest = ctx.land.classAt(probeE, probeN) == LandClass.FOREST
                    val span = if (forest) 7.0 else 3.8
                    val off = hw + 0.5 + span * h1.pow(1.5)
                    val e = pose.e + fn * side * off
                    val n = pose.n - fe * side * off
                    if (!s.contains(e, n)) continue
                    val cls = ctx.land.classAt(e, n)
                    val density = when (cls) {
                        LandClass.WATER, LandClass.WETLAND -> 0.0
                        LandClass.FOREST -> 0.85
                        LandClass.MEADOW -> 0.75
                        LandClass.FARMLAND -> 0.5
                        LandClass.RESIDENTIAL -> 0.22
                        else -> 0.45
                    } * (0.35 + 1.1 * Noise.value(e / 7.0, n / 7.0, SEED_UG + 4))
                    if (h2 >= density) continue
                    // Strecke selbst (auch andere Teile der Runde), Strassen, Gebaeude freihalten
                    val hit = tl.nearest(e, n, off + 2.0) ?: continue
                    if (hit.distance < tl.halfWidthAt(hit.distM) + 0.4) continue
                    if (net.clearance(e, n) < 0.7) continue
                    if (nearBuilding(buildings, e, n, 1.0)) continue
                    val toward = Vec3(pose.e - e, 0.0, -(pose.n - n)).normalized(Vec3(0.0, 0.0, -1.0))
                    val fern = forest && h3 < 0.5 || (!forest && h3 < 0.06)
                    // unter Kronen dunkler (Bewuchs empfaengt keine Schatten)
                    val shadow = 1.0 - 0.45 * ctx.land.canopyAt(e, n).coerceIn(0.0, 1.0)
                    val tint = (0.8 + 0.3 * Noise.hash01(k, side, SEED_UG + 5)) * shadow
                    val hue = (Noise.value(e / 23.0, n / 23.0, SEED_UG + 6) - 0.45) * 2.2
                    // Gruppen aus 1–3 Bueschen statt einzelner Stempel
                    val group = 1 + (h3 * 2.99).toInt()
                    for (q in 0 until group) {
                        if (used + TreeModels.TUFT_TRIANGLES > budget) return used
                        val qa = Noise.hash01(k * 3 + q, side, SEED_UG + 7) * 6.283
                        val qr = if (q == 0) 0.0 else 0.35 + 0.4 * Noise.hash01(k * 3 + q, side, SEED_UG + 8)
                        val qe = e + kotlin.math.cos(qa) * qr
                        val qn = n + kotlin.math.sin(qa) * qr
                        val g = ctx.ground.heightAt(qe, qn)
                        val sz = 0.75 + 0.35 * Noise.hash01(k * 3 + q, side, SEED_UG + 9)
                        if (fern) {
                            TreeModels.fernTuft(m, ctx.x(qe), ctx.y(g), ctx.z(qn), (0.6 + 0.5 * h3) * sz, h4 * 6.283 + q, toward, fernColor, tint, hue * 0.4)
                        } else {
                            val tall = (0.26 + 0.3 * h3 + (if (off > hw + 2.0) 0.12 else 0.0)) * sz
                            TreeModels.grassTuft(m, ctx.x(qe), ctx.y(g), ctx.z(qn), tall * 1.8, tall, h4 * 6.283 + q, toward, grassColor, tint, hue)
                        }
                        used += TreeModels.TUFT_TRIANGLES
                    }
                }
                k++
            }
        }
        return used
    }

    private fun nearBuilding(buildings: List<MapBuilding>, e: Double, n: Double, clearance: Double = BUILDING_CLEARANCE_M): Boolean {
        for (b in buildings) {
            if (pointInRings(b.rings, e, n)) return true
            val r = b.rings.firstOrNull() ?: continue
            val closed = DoubleArray(r.size + 2)
            r.copyInto(closed)
            closed[r.size] = r[0]
            closed[r.size + 1] = r[1]
            if (distanceToLine(closed, e, n) < clearance) return true
        }
        return false
    }

    // -----------------------------------------------------------------------
    // Farben und Material
    // -----------------------------------------------------------------------

    private fun crownColor(ctx: TileContext, e: Double, n: Double, shade: Double): Int {
        val conifer = ctx.land.coniferShare(e, n).coerceIn(0.0, 1.0)
        val v = 0.85 + 0.3 * Noise.value(e / 23.0, n / 23.0, SEED_TINT)
        val f = (v * shade).coerceIn(0.0, 1.4)
        fun ch(k: Int) = min(255, ((BROADLEAF[k] + (CONIFER[k] - BROADLEAF[k]) * conifer) * f).toInt())
        return lin(Argb.rgb(ch(0), ch(1), ch(2)))
    }

    /**
     * Blattkarten: Atlas mit Alpha-Ausschnitt, `MASK` (Schwelle 0,5), **einseitig** — jede Karte, die
     * von beiden Seiten zu sehen ist, steht mit beiden Wicklungen im Netz ([TreeModels]). Nicht
     * `mat_tree_leaves`, weil der Vertrag dafuer doppelseitig nennt; das traegt das Kronendach.
     */
    private fun leavesMaterial(ctx: TileContext): TileMaterial {
        val tex = FoliageAtlas.atlasTexture
        return TileMaterial(
            MAT_CARDS,
            texture = tex,
            repeat = false,
            roughness = 0.95f,
            alphaMode = GlbAlphaMode.MASK,
            alphaCutoff = 0.5f,
            doubleSided = false,
            noSpecular = true,
        )
    }

    /** Stufe 1: nur Kreuzbilder ganzer Baeume, kleiner Fern-Atlas (gleiche Einstellungen). */
    private fun farLeavesMaterial(ctx: TileContext): TileMaterial {
        val tex = FoliageAtlas.farTexture
        return TileMaterial(
            MAT_CARDS,
            texture = tex,
            repeat = false,
            roughness = 0.95f,
            alphaMode = GlbAlphaMode.MASK,
            alphaCutoff = 0.5f,
            doubleSided = false,
            noSpecular = true,
        )
    }

    /** Kronendach der Waldkoerper: `mat_tree_leaves` wie im Vertrag (MASK, doppelseitig) mit deckendem, kachelndem Kronenmosaik (JPEG). */
    private fun canopyMaterial(ctx: TileContext): TileMaterial {
        val tex = ctx.textures.get("x_canopy", jpegQuality = 86) { FoliageAtlas.canopyImage() }
        return TileMaterial(
            WorldNames.MAT_TREE_LEAVES, texture = tex, repeat = true, roughness = 0.95f,
            alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, doubleSided = true, noSpecular = true,
        )
    }

    /** Waagrechte Richtung (glTF-Achsen) von (e, n) zur naechsten Stelle der Strecke (grob, Stuetzpunkte alle 50 m). */
    private fun towardTrack(ctx: TileContext, e: Double, n: Double): Vec3 {
        val t = ctx.track.track
        val step = max(1, (50.0 / t.stepM).toInt())
        var best = Double.MAX_VALUE
        var be = e
        var bn = n + 1
        var i = 0
        while (i < t.sampleCount) {
            val de = t.e[i] - e
            val dn = t.n[i] - n
            val d = de * de + dn * dn
            if (d < best) { best = d; be = t.e[i].toDouble(); bn = t.n[i].toDouble() }
            i += step
        }
        return Vec3(be - e, 0.0, -(bn - n)).normalized(Vec3(0.0, 0.0, -1.0))
    }

    companion object {
        /** Einzelbaeume bis zu diesem Abstand von der Mittellinie (Stufe 0). */
        const val NEAR_M = 70.0

        /** Bis hier dicht gesetzt. */
        const val DENSE_M = 28.0

        /** Hoechster Abstand fuer die mittlere bzw. feinste Stufe (falls das Budget reicht). */
        const val MID_M = 40.0
        const val DETAIL_M = 22.0
        const val DENSE_SPACING_M = 6.5
        const val OPEN_SPACING_M = 8.0

        /** Mindestabstand des Stamms vom Fahrbahnrand. */
        const val TRACK_CLEARANCE_M = 2.8

        /** Gemessene Baeume duerfen naeher stehen (Alleen); der Stamm steht dort wirklich. */
        const val LASER_TRACK_CLEARANCE_M = 1.2
        const val ROAD_CLEARANCE_M = 2.0
        const val BUILDING_CLEARANCE_M = 3.0

        /** Material der Blattkarten (einseitig, deshalb nicht `mat_tree_leaves`). */
        const val MAT_CARDS = "x_mat_tree_cards"

        /** Abstand der Plaetze fuer Bodenbewuchs entlang der Strecke (je Seite). */
        const val UG_STEP_M = 2.2

        /** Hoechstens so viele Dreiecke Einzelbaeume je Kachel (Stufe 0), Blattkarten mit beiden Wicklungen gezaehlt. */
        const val SINGLE_TREE_MAX = 30_000

        /** Hoechstens so viele Dreiecke Bodenbewuchs je Kachel. */
        const val UNDERGROWTH_MAX = 1_600

        private const val SEED_DENSE = 0x7ee1
        private const val SEED_OPEN = 0x7ee2
        private const val SEED_HEIGHT = 0x7ee3
        private const val SEED_TINT = 0x7ee4
        private const val SEED_BUMP = 0x7ee5
        private const val SEED_JIT_E = 0x7ee6
        private const val SEED_JIT_N = 0x7ee7
        private const val SEED_TOP = 0x7ee8
        private const val SEED_BB = 0x7ee9
        private const val SEED_PINE = 0x7eea
        private const val SEED_HUE = 0x7eeb
        private const val SEED_UG = 0x7eec
        private const val SEED_OAK = 0x7eed
        private const val SEED_ORCH = 0x7eee
        private const val SEED_ALLEE = 0x7eef
        private const val SEED_EDGE = 0x7ef0

        /** Abstand der Saum-Plaetze vor Waldraendern entlang der Strecke (je Seite). */
        const val EDGE_STEP_M = 7.0

        /** Flaechen, auf denen kleine Baeume meist Obstbaeume sind. */
        private val FRUIT_LAND = setOf(LandClass.RESIDENTIAL, LandClass.MEADOW, LandClass.ORCHARD, LandClass.PARK)

        /** Strassenarten, an denen Alleen erkannt werden. */
        private val ALLEE_ROADS = setOf("primary", "secondary", "tertiary", "unclassified", "residential", "primary_link", "secondary_link", "tertiary_link")

        /** Abstand der Alleebaeume entlang der Strecke. */
        const val ALLEE_STEP_M = 9.5

        /** Obstwiese: Abstand der Reihen und in der Reihe. */
        const val ORCHARD_ROW_M = 10.0
        const val ORCHARD_STEP_M = 8.5

        /** Grundfarben des Waldkoerpers in sRGB. */
        val CONIFER = intArrayOf(50, 72, 46)
        val BROADLEAF = intArrayOf(76, 102, 50)
    }
}
