package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
        if (ctx.level == 0 && ctx.aerial != null && land is LandRaster) {
            land.setCanopy(ctx.spec, canopyFromAerial(ctx.aerial))
        }
        val b = ctx.budget
        fun spare(budget: Int, node: String) = max(0, budget - out.triangles(node))
        val total = b.vegetation + spare(b.route, WorldNames.NODE_ROUTE) + spare(b.roads, WorldNames.NODE_ROADS) +
            spare(b.buildings, WorldNames.NODE_BUILDINGS) + spare(b.water, WorldNames.NODE_WATER)
        val leaves = leavesMaterial(ctx)
        val volumeBudget = if (ctx.level == 0) b.vegetation / 4 else total
        val used = forestVolume(ctx, out, leaves, volumeBudget)
        if (ctx.level == 0) singleTrees(ctx, out, leaves, total - used)
    }

    // -----------------------------------------------------------------------
    // Waldkoerper
    // -----------------------------------------------------------------------

    /** Baut die Waldkoerper der Kachel; gibt die Zahl der Dreiecke zurueck. */
    private fun forestVolume(ctx: TileContext, out: TileOutput, leaves: TileMaterial, budget: Int): Int {
        val s = ctx.spec
        var cell = if (ctx.level == 0) 8.0 else 32.0
        while (true) {
            val cells = (s.cellM / cell).toInt()
            val forest = forestCells(ctx, cells, cell)
            val count = forest.count { it }
            val tris = count * 2
            if (tris <= budget || cell >= s.cellM / 4) {
                if (count == 0 || tris > budget) return 0
                emitVolume(ctx, out, leaves, forest, cells, cell)
                return tris
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

    /** Ob eine Zelle (Mitte `e`, `n`) zum Waldkoerper gehoert. */
    private fun isVolumeCell(ctx: TileContext, e: Double, n: Double, cell: Double): Boolean {
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

    private fun emitVolume(ctx: TileContext, out: TileOutput, leaves: TileMaterial, forest: BooleanArray, cells: Int, cell: Double) {
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
            val m = out.mesh(WorldNames.NODE_TREES, leaves, e0 + cell / 2, n0 + cell / 2)
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
                idx[k] = m.vertex(ctx.x(e), ctx.y(y), ctx.z(n), nor.x, nor.y, nor.z, e / UV_M, -n / UV_M, crownColor(ctx, e, n, shade))
            }
            // (i,j) → (i+1,j) → (i+1,j+1) → (i,j+1) laeuft in (e, n) gegen den Uhrzeigersinn: oben sichtbar.
            // Diagonale entlang der kleineren Hoehendifferenz, damit Raender nicht zackig einknicken.
            if (abs(top(i, j) - top(i + 1, j + 1)) <= abs(top(i + 1, j) - top(i, j + 1))) {
                m.quad(idx[0], idx[1], idx[2], idx[3])
            } else {
                m.quad(idx[1], idx[2], idx[3], idx[0])
            }
        }
    }

    /** Hoehe des Kronendachs ueber dem Boden, sanft wellig (Bestaende von einigen hundert Metern). */
    private fun canopyHeight(ctx: TileContext, e: Double, n: Double): Double {
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
        for ((spacing, from, to) in listOf(Triple(DENSE_SPACING_M, 0.0, DENSE_M), Triple(OPEN_SPACING_M, DENSE_M, NEAR_M + 4.0))) {
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
                if (cls == LandClass.WATER || cls == LandClass.WETLAND) continue
                if (net.clearance(e, n) < ROAD_CLEARANCE_M) continue
                if (nearBuilding(buildings, e, n)) continue
                cands += Candidate(e, n, hit.distance, specFor(ctx, cls, e, n, i, j, spacing))
            }
        }
        cands.sortBy { it.dist }
        // Stufen vergeben: alle einfach, dann vom Weg nach aussen aufwerten, solange das Budget reicht.
        var used = 0
        val placed = ArrayList<Candidate>()
        for (c in cands) {
            val cost = TreeModels.cost(c.spec.species, TreeModels.Lod.FAR)
            if (used + cost > budget) break
            used += cost
            placed += c
        }
        for ((target, maxDist) in listOf(TreeModels.Lod.MID to MID_M, TreeModels.Lod.NEAR to DETAIL_M)) {
            for (c in placed) {
                if (c.dist > maxDist) break
                val delta = TreeModels.cost(c.spec.species, target) - TreeModels.cost(c.spec.species, c.lod)
                if (used + delta > budget) break
                used += delta
                c.lod = target
            }
        }
        val trunkMat = TileMaterial(WorldNames.MAT_TREE_TRUNK, roughness = 0.95f)
        val trunks = out.mesh(WorldNames.NODE_TREES, trunkMat)
        val crowns = out.mesh(WorldNames.NODE_TREES, leaves)
        val scratchTrunk = MeshBuilder(64, 64)
        val scratchCrown = MeshBuilder(256, 256)
        for (c in placed) {
            scratchTrunk.clear()
            scratchCrown.clear()
            TreeModels.build(c.spec, c.lod, scratchTrunk, scratchCrown)
            val g = ctx.ground.heightAt(c.e, c.n)
            val m = Mat4.translation(ctx.x(c.e), ctx.y(g), ctx.z(c.n))
            trunks.append(scratchTrunk, m)
            crowns.append(scratchCrown, m)
        }
        return used
    }

    /** Art, Groesse und Farbe eines Baums — aus Landnutzung und Zufall des Gitterpunkts. */
    private fun specFor(ctx: TileContext, cls: LandClass, e: Double, n: Double, i: Int, j: Int, spacing: Double): TreeModels.Spec {
        val seed = Noise.hash(i, j, (spacing * 10).toInt() + 0x5eed)
        fun r(k: Int) = Noise.hash01(i, j, seed + k)
        val inForest = cls == LandClass.FOREST
        val species = if (inForest) {
            when {
                r(1) < ctx.land.coniferShare(e, n) -> TreeModels.Species.CONIFER
                r(2) < 0.22 -> TreeModels.Species.TALL
                else -> TreeModels.Species.BROAD
            }
        } else {
            val x = r(1)
            when {
                x < 0.08 -> TreeModels.Species.CONIFER
                x < 0.28 -> TreeModels.Species.TALL
                x < (if (cls == LandClass.RESIDENTIAL || cls == LandClass.MEADOW) 0.6 else 0.45) -> TreeModels.Species.SMALL
                else -> TreeModels.Species.BROAD
            }
        }
        val h = when (species) {
            TreeModels.Species.BROAD -> if (inForest) 12.0 + 7.0 * r(3) else 8.0 + 7.0 * r(3)
            TreeModels.Species.TALL -> if (inForest) 14.0 + 6.0 * r(3) else 10.0 + 8.0 * r(3)
            TreeModels.Species.CONIFER -> if (inForest) 13.0 + 8.0 * r(3) else 9.0 + 7.0 * r(3)
            TreeModels.Species.SMALL -> 4.5 + 3.0 * r(3)
        }
        return TreeModels.Spec(
            species = species,
            heightM = h,
            width = 0.85 + 0.3 * r(4),
            tint = 0.85 + 0.3 * r(5),
            seed = seed,
            turn = r(6) * 6.283,
            pale = species == TreeModels.Species.TALL && r(7) < 0.5,
        )
    }

    private fun nearBuilding(buildings: List<MapBuilding>, e: Double, n: Double): Boolean {
        for (b in buildings) {
            if (pointInRings(b.rings, e, n)) return true
            val r = b.rings.firstOrNull() ?: continue
            val closed = DoubleArray(r.size + 2)
            r.copyInto(closed)
            closed[r.size] = r[0]
            closed[r.size + 1] = r[1]
            if (distanceToLine(closed, e, n) < BUILDING_CLEARANCE_M) return true
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

    private fun leavesMaterial(ctx: TileContext): TileMaterial {
        val tex = ctx.textures.get("x_leaves", alpha = true) { leafTexture() }
        return TileMaterial(
            WorldNames.MAT_TREE_LEAVES,
            texture = tex,
            repeat = true,
            roughness = 0.95f,
            alphaMode = GlbAlphaMode.MASK,
            alphaCutoff = 0.5f,
            doubleSided = true,
        )
    }

    companion object {
        /** Einzelbaeume bis zu diesem Abstand von der Mittellinie (Stufe 0). */
        const val NEAR_M = 70.0

        /** Bis hier dicht gesetzt. */
        const val DENSE_M = 28.0

        /** Hoechster Abstand fuer die mittlere bzw. feinste Stufe (falls das Budget reicht). */
        const val MID_M = 45.0
        const val DETAIL_M = 24.0
        const val DENSE_SPACING_M = 6.0
        const val OPEN_SPACING_M = 8.0

        /** Mindestabstand des Stamms vom Fahrbahnrand. */
        const val TRACK_CLEARANCE_M = 2.8
        const val ROAD_CLEARANCE_M = 2.0
        const val BUILDING_CLEARANCE_M = 3.0

        /** Kachelung der Laubtextur in Metern (Waldkoerper). */
        const val UV_M = 6.0

        private const val SEED_DENSE = 0x7ee1
        private const val SEED_OPEN = 0x7ee2
        private const val SEED_HEIGHT = 0x7ee3
        private const val SEED_TINT = 0x7ee4
        private const val SEED_BUMP = 0x7ee5
        private const val SEED_JIT_E = 0x7ee6
        private const val SEED_JIT_N = 0x7ee7

        /** Grundfarben des Waldkoerpers in sRGB. */
        val CONIFER = intArrayOf(50, 72, 46)
        val BROADLEAF = intArrayOf(76, 102, 50)

        /**
         * Laubtextur 128², hellgrau gesprenkelt (die Farbe kommt aus den
         * Eckpunkten), voll deckend (Loecher zeigten die dunklen Innenseiten als Flecken).
         */
        fun leafTexture(): RgbaImage {
            val size = 128
            val img = RgbaImage(size, size)
            for (y in 0 until size) for (x in 0 until size) {
                val blob = Noise.value(x / 8.0, y / 8.0, 0x1eaf, size / 8)
                val fine = Noise.hash01(x, y, 0x1eb0)
                val v = (0.66 + 0.34 * blob - 0.1 * fine).coerceIn(0.0, 1.0)
                val c = (v * 255).toInt()
                img.argb[y * size + x] = Argb.of(255, c, min(255, (c * 1.04).toInt()), (c * 0.9).toInt())
            }
            return img
        }
    }
}
