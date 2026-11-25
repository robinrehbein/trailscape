package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.Argb
import de.trailscape.core.indoor.world.MeshBuilder
import de.trailscape.core.indoor.world.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Dreiecksbudget je Art und Stufe
 *
 * Gezaehlt sind **alle** Dreiecke einer Vorlage, Laubkarten mit beiden
 * Wicklungen (das Laubmaterial ist einseitig, jede Karte steht zweimal im
 * Netz, siehe [TreeMesher]). Herleitung in `docs/indoor/baeume.md`: hoechstens
 * rund 24 Nahbaeume gleichzeitig im Bild × 6 000 ≈ 150 000 Dreiecke, das ist
 * knapp ein Drittel dessen, was eine Welt heute gleichzeitig zeigt.
 */
object TreeBudget {
    fun triangles(kind: TreeKind, lod: TreeLod): Int = when (lod) {
        TreeLod.NEAR -> when (kind) {
            TreeKind.BEECH, TreeKind.OAK, TreeKind.SPRUCE -> 6_000
            TreeKind.BIRCH, TreeKind.POPLAR, TreeKind.PINE, TreeKind.ALEPPO_PINE -> 4_500
            TreeKind.FRUIT -> 3_000
            TreeKind.SHRUB -> 1_800
        }
        TreeLod.MID -> when (kind) {
            TreeKind.BEECH, TreeKind.OAK, TreeKind.SPRUCE -> 1_500
            TreeKind.BIRCH, TreeKind.POPLAR, TreeKind.PINE, TreeKind.ALEPPO_PINE -> 1_100
            TreeKind.FRUIT -> 800
            TreeKind.SHRUB -> 500
        }
    }

    /** Hoechstens so viele Ringseiten je Astordnung und Stufe. */
    internal fun maxSides(level: Int, lod: TreeLod): Int = when (lod) {
        TreeLod.NEAR -> when (level) { 0 -> 12; 1 -> 6; else -> 4 }
        TreeLod.MID -> when (level) { 0 -> 7; 1 -> 4; else -> 3 }
    }

    /** Ringseiten nach Umfang: Kantenlaenge rund 12 cm (nah) bzw. 30 cm (mittel), 3 bis [maxSides]. */
    internal fun sides(level: Int, lod: TreeLod, radiusM: Double): Int {
        val edge = if (lod == TreeLod.NEAR) 0.12 else 0.3
        return kotlin.math.ceil(2 * Math.PI * radiusM / edge).toInt().coerceIn(3, maxSides(level, lod))
    }
}

/** Eine fertige Vorlage: Rinde und Laub als getrennte Netze (zwei Primitive, zwei Zeichenaufrufe). */
internal class TreeMeshes(val bark: MeshBuilder, val leaves: MeshBuilder, val skeleton: Skeleton, val lod: TreeLod) {
    val triangles: Int get() = bark.triangleCount + leaves.triangleCount
}

/**
 * # Vernetzung eines [Skeleton]
 *
 * **Rinde:** Jeder Ast ist eine sich verjuengende Roehre mit parallel
 * mitgefuehrtem Rahmen (kein Verdrillen), Ringseiten nach Ordnung und Stufe
 * ([TreeBudget.sides]), am Stammfuss feinere Ringe mit Wurzelanlauf. Rinden-UV:
 * u einmal rundum **innerhalb der Spalte** der Art im Rindenatlas, v 0,5 je
 * Meter (kachelnd) und vom Elternast fortgefuehrt. Vertexfarbe: Rindenton ×
 * Verschattung (Fuss dunkler, Aeste im Kroneninnern dunkler; Baeume empfangen
 * keine Schatten, `render.md`).
 *
 * **Laub:** Jedes Zweigbueschel sind [SpeciesParams.cardsPerCluster]
 * gekreuzte Karten entlang der Wuchsrichtung, das Motiv beginnt am Ansatz.
 * Normalen **kugelig aufgeweicht**: 65 % Richtung von der Kronenmitte (Huelle
 * als Ellipsoid), 25 % Kartennormale (nach aussen gedreht), dazu etwas nach
 * oben — die Krone wird wie ein weicher Koerper beleuchtet. Beide Wicklungen
 * auf denselben Eckpunkten (Material einseitig, wie `x_mat_tree_cards` heute).
 * Keine Neigung „zur Strecke" wie in `TreeModels`: die Vorlagen werden gedreht
 * instanziert und kennen die Strecke nicht.
 *
 * **Mittlere Stufe:** Stamm und Hauptaeste mit weniger Seiten, keine
 * Seitenaeste; die Nah-Bueschel werden raeumlich zusammengefasst (Raster nach
 * Bueschelgroesse × [SpeciesParams.midClusterScale]) — gleiche Silhouette,
 * groessere Karten.
 *
 * **Budget:** Rinde zuerst (hoechstens 30 %; bei Ueberschreitung entfallen die duennsten
 * Seitenaeste), das Laub bekommt den Rest; reicht er nicht, werden Bueschel
 * gleichmaessig ausgeduennt und dafuer vergroessert (Deckung bleibt etwa gleich).
 */
internal object TreeMesher {
    fun mesh(s: Skeleton, lod: TreeLod): TreeMeshes {
        val p = Species.of(s.kind)
        val budget = TreeBudget.triangles(s.kind, lod)
        val bark = MeshBuilder(1024, 1024)
        val leaves = MeshBuilder(1024, 1024)
        val maxLevel = if (lod == TreeLod.NEAR) 2 else 1
        // Rinde: hoechstens 30 % des Budgets; zuerst alle Aeste der Stufe, sonst die duennsten weglassen
        var branches = s.branches.filter { it.level <= maxLevel }
        val barkCap = (budget * 0.3).toInt()
        while (true) {
            val est = branches.sumOf { tubeTriangles(it, lod) }
            if (est <= barkCap) break
            val top = branches.filter { it.level > 0 }.maxOfOrNull { it.level } ?: break
            val thin = branches.filter { it.level == top }.sortedBy { it.radii[0] * it.length }
            val drop = max(1, thin.size / 8)
            val gone = thin.take(drop).toHashSet()
            branches = branches.filter { it !in gone }
        }
        for (b in branches) tube(bark, b, p, s, lod)
        // Laub
        var clusters = if (lod == TreeLod.NEAR) s.clusters else merge(s.clusters, p.clusterM * p.midClusterScale)
        val cardTris = 4 // zwei Dreiecke, beide Wicklungen
        val cards = if (lod == TreeLod.NEAR) p.cardsPerCluster else 2
        val room = budget - bark.triangleCount
        val maxClusters = max(1, room / (cardTris * cards))
        if (clusters.size > maxClusters) {
            val keep = maxClusters.toDouble() / clusters.size
            val grow = sqrt(1 / keep).coerceAtMost(1.6)
            val picked = ArrayList<LeafCluster>(maxClusters)
            var acc = 0.0
            for (c in clusters) {
                acc += keep
                if (acc >= 1.0) {
                    acc -= 1.0
                    picked += LeafCluster(c.pos, c.dir, c.size * grow, c.tint, c.flip, c.spin)
                }
            }
            clusters = picked.take(maxClusters)
        }
        for (c in clusters) cluster(leaves, c, p, s, cards)
        return TreeMeshes(bark, leaves, s, lod)
    }

    // -----------------------------------------------------------------------
    // Rinde
    // -----------------------------------------------------------------------

    private fun ringCount(b: Branch): Int = b.points.size + (if (b.level == 0) 3 else 0)

    private fun tubeTriangles(b: Branch, lod: TreeLod): Int = (ringCount(b) - 1) * TreeBudget.sides(b.level, lod, b.radii[0]) * 2

    /** Stuetzstellen der Ringe: die Polylinie, am Stamm unten zusaetzlich 0,15 / 0,45 / 0,9 m ueber dem Boden. */
    private fun stations(b: Branch): List<Triple<Vec3, Vec3, Double>> {
        val out = ArrayList<Triple<Vec3, Vec3, Double>>()
        if (b.level != 0) {
            for (i in b.points.indices) {
                val d = if (i < b.points.size - 1) b.points[i + 1] - b.points[i] else b.points[i] - b.points[i - 1]
                out += Triple(b.points[i], d.normalized(), b.radii[i])
            }
            return out
        }
        val ts = ArrayList<Double>()
        for (i in b.points.indices) ts += i / (b.points.size - 1).toDouble()
        // zusaetzliche Ringe im Wurzelanlauf (Laenge ab Fusspunkt −0,3 m)
        for (y in doubleArrayOf(0.45, 0.75, 1.2)) ts += (y / b.length).coerceIn(0.0, 1.0)
        ts.sort()
        for (t in ts.distinct()) out += b.at(t)
        return out
    }

    private fun tube(m: MeshBuilder, b: Branch, p: SpeciesParams, s: Skeleton, lod: TreeLod) {
        val sides = TreeBudget.sides(b.level, lod, b.radii[0])
        val st = stations(b)
        if (st.size < 2) return
        val col = p.barkMotif.column
        // Spalte im Atlas (4 Spalten), etwas eingerueckt gegen Uebersprechen der Nachbarspalten
        val u0 = (col + 0.04) / 4.0
        val u1 = (col + 0.96) / 4.0
        var frameU = TreeGrowth.perp(st[0].second, 0.0)
        var vAcc = b.vStart
        var prevP = st[0].first
        val base = IntArray(st.size)
        for ((k, station) in st.withIndex()) {
            val (pos, dir, r0) = station
            if (k > 0) vAcc += (pos - prevP).length()
            prevP = pos
            // parallel mitgefuehrter Rahmen
            frameU = (frameU - dir * (frameU dot dir)).normalized(TreeGrowth.perp(dir, 0.0))
            val frameW = (dir cross frameU).normalized()
            var r = r0
            if (b.level == 0) {
                val y = max(0.0, pos.y)
                r *= 1 + p.flare * exp(-y / 0.35)
            }
            val color = barkColor(p, s, b, pos)
            base[k] = m.vertexCount
            for (j in 0..sides) {
                val a = 2 * PI * j / sides
                // Wurzelanlauf mit Rippen (nur Stamm, nur unten)
                val rib = if (b.level == 0 && pos.y < 0.9) 1 + 0.12 * p.flare * cos(5 * a) * exp(-max(0.0, pos.y) / 0.3) else 1.0
                val nrm = frameU * cos(a) + frameW * sin(a)
                val q = pos + nrm * (r * rib)
                val u = u0 + (u1 - u0) * j / sides
                m.vertex(q.x, q.y, q.z, nrm.x, nrm.y, nrm.z, u, vAcc * 0.5, color)
            }
        }
        for (k in 0 until st.size - 1) {
            val a = base[k]
            val c = base[k + 1]
            for (j in 0 until sides) m.quad(a + j, a + j + 1, c + j + 1, c + j)
        }
    }

    private fun barkColor(p: SpeciesParams, s: Skeleton, b: Branch, pos: Vec3): Int {
        var c = p.barkSrgb
        val top = p.barkTopSrgb
        if (top != null) {
            val t = ((pos.y - 0.45 * s.heightM) / (0.25 * s.heightM)).coerceIn(0.0, 1.0)
            c = intArrayOf(mix(c[0], top[0], t), mix(c[1], top[1], t), mix(c[2], top[2], t))
        }
        // Verschattung: Fuss (Bodennaehe) und Kroneninneres dunkler
        val foot = 0.62 + 0.38 * smooth(((pos.y - 0.0) / 1.6).coerceIn(0.0, 1.0))
        val inner = if (b.level == 0) 1.0 else {
            val r = s.rel(pos).length()
            0.62 + 0.38 * smooth(r.coerceIn(0.0, 1.0))
        }
        val crownShade = if (b.level == 0 && pos.y > s.crownCenter.y - s.crownRy) 0.8 else 1.0
        return linColor(c, foot * inner * crownShade)
    }

    // -----------------------------------------------------------------------
    // Laub
    // -----------------------------------------------------------------------

    /** Fasst Bueschel in einem Raster der Kante [cell] zusammen (Mittel der Lage und Richtung, groessere Kante). */
    private fun merge(clusters: List<LeafCluster>, cell: Double): List<LeafCluster> {
        class Acc { var x = 0.0; var y = 0.0; var z = 0.0; var dx = 0.0; var dy = 0.0; var dz = 0.0; var n = 0; var size = 0.0; var tint = 0.0; var first: LeafCluster? = null }
        val cells = LinkedHashMap<Long, Acc>()
        for (c in clusters) {
            val i = kotlin.math.floor(c.pos.x / cell).toLong()
            val j = kotlin.math.floor(c.pos.y / cell).toLong()
            val k = kotlin.math.floor(c.pos.z / cell).toLong()
            val key = (i and 0x1FFFFF) or ((j and 0x1FFFFF) shl 21) or ((k and 0x1FFFFF) shl 42)
            val a = cells.getOrPut(key) { Acc() }
            if (a.first == null) a.first = c
            a.x += c.pos.x; a.y += c.pos.y; a.z += c.pos.z
            a.dx += c.dir.x; a.dy += c.dir.y; a.dz += c.dir.z
            a.n++
            a.size = max(a.size, c.size)
            a.tint += c.tint
        }
        return cells.values.map { a ->
            val n = a.n.toDouble()
            val f = a.first!!
            LeafCluster(
                Vec3(a.x / n, a.y / n, a.z / n), Vec3(a.dx, a.dy, a.dz).normalized(f.dir),
                max(a.size, cell * 0.85) * min(1.35, 0.85 + 0.12 * a.n), a.tint / n, f.flip, f.spin,
            )
        }
    }

    private fun cluster(m: MeshBuilder, c: LeafCluster, p: SpeciesParams, s: Skeleton, cards: Int) {
        val reg = TreeTextures.region(p.leafMotif)
        val d = c.dir
        // Richtung von der Kronenmitte (Huelle): die erste Karte steht quer dazu, die anderen gedreht
        val radial = s.rel(c.pos).let { Vec3(it.x, it.y * 0.7, it.z) }.normalized(Vec3.UP)
        val face0 = (radial - d * (radial dot d)).normalized(TreeGrowth.perp(d, c.spin))
        for (k in 0 until cards) {
            val ang = c.spin * 0.3 + PI * k / cards
            // Kartennormale: face0 um die Wuchsrichtung gedreht; jede weitere Karte etwas gekippt
            val w0 = (d cross face0).normalized()
            val nCard0 = (face0 * cos(ang) + w0 * sin(ang)).normalized()
            val dk = if (k == 0) d else (d + nCard0 * (if (k % 2 == 1) 0.45 else -0.45)).normalized(d)
            val nCard = (nCard0 - dk * (nCard0 dot dk)).normalized(nCard0)
            val across = (nCard cross dk).normalized()
            val len = c.size
            val wid = c.size * reg.aspect
            val start = c.pos
            val corners = arrayOf(
                start - across * (wid / 2), start + across * (wid / 2),
                start + dk * len + across * (wid / 2), start + dk * len - across * (wid / 2),
            )
            val ss = doubleArrayOf(0.0, 1.0, 1.0, 0.0)
            val ts = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            val idx = IntArray(4)
            for (q in 0 until 4) {
                val pt = corners[q]
                val rel = s.rel(pt)
                val rl = rel.length()
                val rad = Vec3(rel.x, if (rel.y < 0) rel.y * 0.55 else rel.y, rel.z).normalized(Vec3.UP)
                val facing = if ((nCard dot rad) >= 0) nCard else -nCard
                val n = (rad * 0.65 + facing * 0.25 + Vec3(0.0, 0.22, 0.0)).normalized()
                // Verschattung: innen und unten dunkler, Kartenansatz etwas dunkler
                val depth = smooth(((rl - 0.25) / 0.85).coerceIn(0.0, 1.0))
                val ht = ((pt.y - (s.crownCenter.y - s.crownRy)) / (2 * s.crownRy)).coerceIn(0.0, 1.0)
                var shade = c.tint * (0.5 + 0.36 * depth + 0.2 * ht)
                if (ts[q] == 0.0) shade *= 0.86
                val sCoord = if (c.flip) 1 - ss[q] else ss[q]
                idx[q] = m.vertex(pt.x, pt.y, pt.z, n.x, n.y, n.z, reg.u(sCoord, ts[q]), reg.v(sCoord, ts[q]), linColor(p.leafSrgb, shade))
            }
            m.quad(idx[0], idx[1], idx[2], idx[3])
            m.quad(idx[0], idx[3], idx[2], idx[1])
        }
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    private fun mix(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).toInt()
    private fun smooth(t: Double): Double = t * t * (3 - 2 * t)

    /** sRGB-Grundfarbe × Helligkeit als lineare Vertexfarbe (`COLOR_0` ist linear). */
    fun linColor(srgb: IntArray, factor: Double): Int {
        val f = factor.coerceIn(0.0, 1.6)
        fun ch(v: Int): Int {
            val s = (v * f / 255.0).coerceIn(0.0, 1.0)
            val l = if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            return (l * 255 + 0.5).toInt().coerceIn(0, 255)
        }
        return Argb.of(255, ch(srgb[0]), ch(srgb[1]), ch(srgb[2]))
    }
}
