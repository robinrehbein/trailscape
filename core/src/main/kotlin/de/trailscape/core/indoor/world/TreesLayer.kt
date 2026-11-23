package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * # Baeume und Wald (Knoten `trees`)
 *
 * **Stufe 0:** Einzelbaeume im Band bis [NEAR_M] neben der Strecke — Fichten
 * (Stamm und zwei gestapelte Kegel) und Laubbaeume (Stamm und eine unregelmaessige
 * Krone), dichter am Weg ([DENSE_M]), dahinter lockerer und einfacher. Wo ein
 * Baum steht, entscheidet die Kronenbedeckung ([LandMap.canopyAt]): aus dem
 * Luftbild der Kachel geschaetzt ([canopyFromAerial]), sonst aus der
 * Landnutzung (Wald voll, Siedlung und Park vereinzelt). Ausserhalb des Bands
 * steht der Wald als **Waldkoerper**: eine geschlossene, leicht wellige
 * Kronenflaeche in Baumhoehe ueber den Waldzellen, mit Waenden an den
 * Waldraendern.
 *
 * **Stufe 1:** nur Waldkoerper (aus der Landnutzung), je Teilflaeche
 * gehaengt — sie verschwinden mit ihrem Boden, sobald die Kachel der Stufe 0
 * darunter gezeigt wird.
 *
 * Alles ist aus Welt-Koordinaten gewuerfelt (Gitter am Ursprung): Nachbar-
 * kacheln setzen dieselben Baeume, jede nur die mit Fusspunkt in ihrem
 * Rechteck. Kein Baum steht auf Fahrbahn, Strasse, Wasser oder in einem
 * Gebaeude. Erst der Waldkoerper, dann die Baeume vom Weg nach aussen, bis
 * [TileBudget.vegetation] erreicht ist.
 *
 * Materialien: [WorldNames.MAT_TREE_TRUNK] (Vertexfarben) und
 * [WorldNames.MAT_TREE_LEAVES] (Vertexfarben × kachelnde Laubtextur,
 * `MASK`, doppelseitig — einzelne Loecher in der Textur geben der Krone Tiefe).
 */
internal class TreesLayer : TileLayer {

    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.budget.vegetation <= 0) return
        val land = ctx.land
        if (ctx.level == 0 && ctx.aerial != null && land is LandRaster) {
            land.setCanopy(ctx.spec, canopyFromAerial(ctx.aerial))
        }
        val leaves = leavesMaterial(ctx)
        var used = forestVolume(ctx, out, leaves)
        if (ctx.level == 0) used += singleTrees(ctx, out, leaves, ctx.budget.vegetation - used)
    }

    // -----------------------------------------------------------------------
    // Waldkoerper
    // -----------------------------------------------------------------------

    /** Baut die Waldkoerper der Kachel; gibt die Zahl der Dreiecke zurueck. */
    private fun forestVolume(ctx: TileContext, out: TileOutput, leaves: TileMaterial): Int {
        val s = ctx.spec
        val budget = if (ctx.level == 0) ctx.budget.vegetation / 4 else ctx.budget.vegetation
        var cell = if (ctx.level == 0) 8.0 else 32.0
        while (true) {
            val cells = (s.cellM / cell).toInt()
            val forest = forestCells(ctx, cells, cell)
            val count = forest.count { it }
            // Grob: zwei Dreiecke je Zelle plus Waende am Rand.
            val estimate = count * 2 + edgeCount(forest, cells) * 2
            if (estimate <= budget || cell >= s.cellM / 4) {
                if (count == 0 || estimate > budget) return 0
                emitVolume(ctx, out, leaves, forest, cells, cell)
                return estimate
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

    private fun edgeCount(forest: BooleanArray, cells: Int): Int {
        var c = 0
        for (j in 0 until cells) for (i in 0 until cells) {
            if (!forest[j * cells + i]) continue
            if (i == 0 || !forest[j * cells + i - 1]) c++
            if (i == cells - 1 || !forest[j * cells + i + 1]) c++
            if (j == 0 || !forest[(j - 1) * cells + i]) c++
            if (j == cells - 1 || !forest[(j + 1) * cells + i]) c++
        }
        return c
    }

    private fun emitVolume(ctx: TileContext, out: TileOutput, leaves: TileMaterial, forest: BooleanArray, cells: Int, cell: Double) {
        val s = ctx.spec
        fun isF(i: Int, j: Int): Boolean = i in 0 until cells && j in 0 until cells && forest[j * cells + i]
        // Ein Gitterpunkt liegt am Waldrand, wenn eine der vier Zellen um ihn innerhalb der Kachel kein Wald ist.
        fun edgeVertex(i: Int, j: Int): Boolean {
            for (dj in -1..0) for (di in -1..0) {
                val ci = i + di
                val cj = j + dj
                if (ci in 0 until cells && cj in 0 until cells && !forest[cj * cells + ci]) return true
            }
            return false
        }
        fun topAt(e: Double, n: Double, edge: Boolean): Double {
            val h = canopyHeight(ctx, e, n)
            return ctx.ground.heightAt(e, n) + if (edge) h * 0.6 else h
        }
        fun normalAt(e: Double, n: Double): Vec3 {
            val d = cell * 0.5
            val de = (topAt(e + d, n, false) - topAt(e - d, n, false)) / (2 * d)
            val dn = (topAt(e, n + d, false) - topAt(e, n - d, false)) / (2 * d)
            return Vec3(-de, 1.0, dn).normalized()
        }
        for (j in 0 until cells) for (i in 0 until cells) {
            if (!forest[j * cells + i]) continue
            val e0 = s.minE + i * cell
            val n0 = s.minN + j * cell
            val m = out.mesh(WorldNames.NODE_TREES, leaves, e0 + cell / 2, n0 + cell / 2)
            val corners = arrayOf(intArrayOf(i, j), intArrayOf(i + 1, j), intArrayOf(i + 1, j + 1), intArrayOf(i, j + 1))
            val idx = IntArray(4)
            val tops = DoubleArray(4)
            for ((k, c) in corners.withIndex()) {
                val e = s.minE + c[0] * cell
                val n = s.minN + c[1] * cell
                val edge = edgeVertex(c[0], c[1])
                tops[k] = topAt(e, n, edge)
                val nor = normalAt(e, n)
                idx[k] = m.vertex(ctx.x(e), ctx.y(tops[k]), ctx.z(n), nor.x, nor.y, nor.z, e / UV_M, -n / UV_M, crownColor(ctx, e, n, 1.0))
            }
            // (i,j) → (i+1,j) → (i+1,j+1) → (i,j+1) laeuft in (e, n) gegen den Uhrzeigersinn: oben sichtbar.
            m.quad(idx[0], idx[1], idx[2], idx[3])
            // Waende an Waldraendern und Kachelgrenzen: Kante (a → b) mit Wald links, aussen rechts.
            val edges = arrayOf(
                intArrayOf(0, 1, 0, -1), // Sued
                intArrayOf(1, 2, 1, 0), // Ost
                intArrayOf(2, 3, 0, 1), // Nord
                intArrayOf(3, 0, -1, 0), // West
            )
            for (ed in edges) {
                if (isF(i + ed[2], j + ed[3])) continue
                val a = corners[ed[0]]
                val b = corners[ed[1]]
                val ea = s.minE + a[0] * cell
                val na = s.minN + a[1] * cell
                val eb = s.minE + b[0] * cell
                val nb = s.minN + b[1] * cell
                val nx = ed[2].toDouble()
                val nz = -ed[3].toDouble()
                val ga = ctx.ground.heightAt(ea, na) - 0.5
                val gb = ctx.ground.heightAt(eb, nb) - 0.5
                val len = hypot(eb - ea, nb - na)
                val dark = crownColor(ctx, ea, na, 0.55)
                val pa = m.vertex(ctx.x(ea), ctx.y(ga), ctx.z(na), nx, 0.0, nz, 0.0, 0.0, dark)
                val pb = m.vertex(ctx.x(eb), ctx.y(gb), ctx.z(nb), nx, 0.0, nz, len / UV_M, 0.0, dark)
                val qb = m.vertex(ctx.x(eb), ctx.y(tops[ed[1]]), ctx.z(nb), nx, 0.0, nz, len / UV_M, (tops[ed[1]] - gb) / UV_M, crownColor(ctx, eb, nb, 0.9))
                val qa = m.vertex(ctx.x(ea), ctx.y(tops[ed[0]]), ctx.z(na), nx, 0.0, nz, 0.0, (tops[ed[0]] - ga) / UV_M, crownColor(ctx, ea, na, 0.9))
                // Von aussen gesehen: unten a, unten b, oben b, oben a — gegen den Uhrzeigersinn.
                m.quad(pb, pa, qa, qb)
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

    private class Candidate(val e: Double, val n: Double, val dist: Double, val rnd: Int)

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
                val e = (i + 0.15 + 0.7 * ((h ushr 8 and 0xFFF) / 4096.0)) * spacing
                val n = (j + 0.15 + 0.7 * ((h ushr 20 and 0xFFF) / 4096.0)) * spacing
                if (!s.contains(e, n)) continue
                val hit = ctx.track.nearest(e, n, to + 1.0) ?: continue
                if (hit.distance < from || hit.distance >= to) continue
                if (hit.distance < ctx.track.halfWidthAt(hit.distM) + TRACK_CLEARANCE_M) continue
                val p = ctx.land.canopyAt(e, n)
                if ((h and 0xFF) / 256.0 >= p) continue
                when (ctx.land.classAt(e, n)) {
                    LandClass.WATER, LandClass.WETLAND -> continue
                    else -> Unit
                }
                if (net.clearance(e, n) < ROAD_CLEARANCE_M) continue
                if (nearBuilding(buildings, e, n)) continue
                cands += Candidate(e, n, hit.distance, h)
            }
        }
        cands.sortBy { it.dist }
        val trunkMat = TileMaterial(WorldNames.MAT_TREE_TRUNK, roughness = 0.95f)
        val trunks = out.mesh(WorldNames.NODE_TREES, trunkMat)
        val crowns = out.mesh(WorldNames.NODE_TREES, leaves)
        val scratchTrunk = MeshBuilder(32, 32)
        val scratchCrown = MeshBuilder(64, 64)
        var used = 0
        for (c in cands) {
            scratchTrunk.clear()
            scratchCrown.clear()
            tree(ctx, c, scratchTrunk, scratchCrown)
            val tris = scratchTrunk.triangleCount + scratchCrown.triangleCount
            if (used + tris > budget) continue
            trunks.append(scratchTrunk)
            crowns.append(scratchCrown)
            used += tris
        }
        return used
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

    /** Ein Baum an [c] in Stamm- und Kronennetz. */
    private fun tree(ctx: TileContext, c: Candidate, trunk: MeshBuilder, crown: MeshBuilder) {
        val r1 = ((c.rnd ushr 4) and 0xFF) / 255.0
        val r2 = ((c.rnd ushr 12) and 0xFF) / 255.0
        val r3 = ((c.rnd ushr 24) and 0x7F) / 127.0
        val inForest = ctx.land.classAt(c.e, c.n) == LandClass.FOREST
        val coniferP = if (inForest) ctx.land.coniferShare(c.e, c.n) else 0.15
        val conifer = r1 < coniferP
        val detailed = c.dist <= DETAIL_M
        val h = if (inForest) (if (conifer) 15.0 + 9.0 * r2 else 12.0 + 8.0 * r2) else 7.0 + 7.0 * r2
        val g = ctx.ground.heightAt(c.e, c.n) - 0.25
        val x = ctx.x(c.e)
        val y = ctx.y(g)
        val z = ctx.z(c.n)
        val tint = 0.85 + 0.3 * r3
        if (conifer) {
            val trunkColor = lin(Argb.rgb(84, 66, 54))
            val leaf = { shade: Double -> crownColorRaw(CONIFER, tint * shade) }
            if (detailed) {
                trunk.addCylinder(Vec3(x, y, z), Vec3(x, y + 0.4 * h, z), 0.022 * h, 0.014 * h, 5, false, false, trunkColor)
                cone(crown, x, y + 0.22 * h, z, 0.21 * h, y + 0.82 * h, 8, leaf, r1)
                cone(crown, x, y + 0.55 * h, z, 0.13 * h, y + h, 7, leaf, r2)
            } else {
                trunk.addCylinder(Vec3(x, y, z), Vec3(x, y + 0.3 * h, z), 0.02 * h, 0.015 * h, 3, false, false, trunkColor)
                cone(crown, x, y + 0.25 * h, z, 0.2 * h, y + h, 6, leaf, r1)
            }
        } else {
            val trunkColor = lin(Argb.rgb(96, 86, 72))
            val base = if (inForest) BROADLEAF else BROADLEAF_OPEN
            val crownBottom = if (inForest) 0.42 * h else 0.32 * h
            val cy = y + (crownBottom + h) / 2
            val ry = (h - crownBottom) / 2
            val rx = ry * (if (inForest) 0.75 else 0.95) * (0.9 + 0.2 * r3)
            trunk.addCylinder(Vec3(x, y, z), Vec3(x, cy, z), 0.03 * h, 0.018 * h, if (detailed) 5 else 3, false, false, trunkColor)
            val first = crown.vertexCount
            if (detailed) crown.addEllipsoid(x, cy, z, rx, ry, rx, 8, 4) else crown.addEllipsoid(x, cy, z, rx, ry, rx, 5, 3)
            // Unregelmaessige Krone: Eckpunkte radial verschieben, unten dunkler, UV kacheln.
            for (v in first until crown.vertexCount) {
                val px = crown.positions[v * 3] - x
                val py = crown.positions[v * 3 + 1] - cy
                val pz = crown.positions[v * 3 + 2] - z
                val k = 0.82 + 0.36 * Noise.hash01(Math.round(px * 7).toInt(), Math.round(py * 7 + pz * 13).toInt(), c.rnd)
                crown.positions[v * 3] = (x + px * k).toFloat()
                crown.positions[v * 3 + 1] = (cy + py * (0.9 + 0.1 * k)).toFloat()
                crown.positions[v * 3 + 2] = (z + pz * k).toFloat()
                val t = ((py / ry) + 1) / 2
                crown.setColor(v, crownColorRaw(base, tint * (0.6 + 0.45 * t)))
                crown.uvs[v * 2] *= 3f
                crown.uvs[v * 2 + 1] *= 2f
            }
        }
    }

    /** Kegel mit Boden: Grundkreis auf [y0] mit Radius [r], Spitze auf [yTop]. */
    private fun cone(m: MeshBuilder, x: Double, y0: Double, z: Double, r: Double, yTop: Double, seg: Int, color: (Double) -> Int, rot: Double) {
        val h = yTop - y0
        val sl = hypot(r, h)
        val ny = r / sl
        val nr = h / sl
        val base = m.vertexCount
        for (k in 0..seg) {
            val a = 2 * PI * (k + rot) / seg
            val ca = cos(a)
            val sa = sin(a)
            // Grundkreis leicht gezackt, damit die Silhouette nicht gedrechselt wirkt.
            val rr = r * (if (k % 2 == 0) 1.0 else 0.86)
            m.vertex(x + ca * rr, y0 + (if (k % 2 == 0) 0.0 else 0.04 * h), z - sa * rr, ca * nr, ny, -sa * nr, k / seg.toDouble() * 3, 2.0, color(0.62))
        }
        for (k in 0 until seg) {
            val a = 2 * PI * (k + 0.5 + rot) / seg
            m.vertex(x, yTop, z, cos(a) * nr, ny, -sin(a) * nr, (k + 0.5) / seg * 3, 0.0, color(1.05))
        }
        for (k in 0 until seg) m.tri(base + k, base + k + 1, base + seg + 1 + k)
        // Boden (von unten sichtbar).
        val center = m.vertex(x, y0 + 0.08 * h, z, 0.0, -1.0, 0.0, 0.5, 0.5, color(0.45))
        val ring = m.vertexCount
        for (k in 0..seg) {
            val a = 2 * PI * (k + rot) / seg
            val rr = r * (if (k % 2 == 0) 1.0 else 0.86)
            m.vertex(x + cos(a) * rr, y0 + (if (k % 2 == 0) 0.0 else 0.04 * h), z - sin(a) * rr, 0.0, -1.0, 0.0, 0.5 + cos(a) / 2, 0.5 + sin(a) / 2, color(0.45))
        }
        for (k in 0 until seg) m.tri(center, ring + k + 1, ring + k)
    }

    // -----------------------------------------------------------------------
    // Farben und Material
    // -----------------------------------------------------------------------

    private fun crownColor(ctx: TileContext, e: Double, n: Double, shade: Double): Int {
        val conifer = ctx.land.coniferShare(e, n)
        val v = 0.85 + 0.3 * Noise.value(e / 23.0, n / 23.0, SEED_TINT)
        val c = mix(BROADLEAF, CONIFER, conifer)
        return crownColorRaw(c, v * shade)
    }

    private fun crownColorRaw(srgb: IntArray, factor: Double): Int {
        val f = factor.coerceIn(0.0, 1.4)
        return lin(Argb.rgb(min(255, (srgb[0] * f).toInt()), min(255, (srgb[1] * f).toInt()), min(255, (srgb[2] * f).toInt())))
    }

    private fun mix(a: IntArray, b: IntArray, t: Double): IntArray =
        IntArray(3) { (a[it] + (b[it] - a[it]) * t.coerceIn(0.0, 1.0)).toInt() }

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

        /** Bis hier dicht gesetzt und ausfuehrlich gebaut. */
        const val DENSE_M = 28.0
        const val DETAIL_M = 32.0
        const val DENSE_SPACING_M = 5.0
        const val OPEN_SPACING_M = 7.5

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

        /** Grundfarben in sRGB. */
        val CONIFER = intArrayOf(44, 64, 42)
        val BROADLEAF = intArrayOf(70, 96, 46)
        val BROADLEAF_OPEN = intArrayOf(82, 108, 52)

        /**
         * Laubtextur 128², hellgrau gesprenkelt (die Farbe kommt aus den
         * Eckpunkten), mit einzelnen Loechern (Alpha 0) fuer etwas Tiefe.
         */
        fun leafTexture(): RgbaImage {
            val size = 128
            val img = RgbaImage(size, size)
            for (y in 0 until size) for (x in 0 until size) {
                val blob = Noise.value(x / 8.0, y / 8.0, 0x1eaf, size / 8)
                val fine = Noise.hash01(x, y, 0x1eb0)
                val v = (0.62 + 0.38 * blob - 0.12 * fine).coerceIn(0.0, 1.0)
                val c = (v * 255).toInt()
                val hole = blob < 0.16 && fine > 0.35
                img.argb[y * size + x] = Argb.of(if (hole) 0 else 255, c, min(255, (c * 1.04).toInt()), (c * 0.9).toInt())
            }
            return img
        }
    }
}
