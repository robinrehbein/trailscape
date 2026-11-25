package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.Quat
import de.trailscape.core.indoor.world.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wuerfel des Generators (SplitMix64): dieselbe Folge fuer denselben Samen, auf
 * jeder JVM und auf Android gleich (keine Abhaengigkeit von `java.util.Random`).
 */
internal class TreeRandom(seed: Long) {
    private var s = seed xor 0x5DEECE66DL

    fun nextLong(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Gleichverteilt in [0, 1). */
    fun next(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))
    fun range(a: Double, b: Double): Double = a + (b - a) * next()

    /** Gleichverteilt in [−1, 1]. */
    fun sym(): Double = 2 * next() - 1
}

/**
 * Ein Ast (oder Stamm): Polylinie mit Radien, Ordnung [level] (0 = Stamm).
 * [vStart]: Rindenkoordinate v am Ansatz (in Metern Stammlaenge), damit die
 * Rinde von Elternast zu Kind ohne Sprung weiterlaeuft.
 */
internal class Branch(
    val level: Int,
    val points: List<Vec3>,
    val radii: DoubleArray,
    val parent: Branch?,
    val vStart: Double,
) {
    val length: Double = points.zipWithNext { a, b -> (b - a).length() }.sum()

    /** Lage, Richtung und Radius bei Anteil [t] der Laenge. */
    fun at(t: Double): Triple<Vec3, Vec3, Double> {
        val target = t.coerceIn(0.0, 1.0) * length
        var acc = 0.0
        for (i in 0 until points.size - 1) {
            val seg = (points[i + 1] - points[i]).length()
            if (acc + seg >= target || i == points.size - 2) {
                val f = if (seg < 1e-9) 0.0 else ((target - acc) / seg).coerceIn(0.0, 1.0)
                val p = points[i].lerp(points[i + 1], f)
                val d = (points[i + 1] - points[i]).normalized()
                val r = radii[i] + (radii[i + 1] - radii[i]) * f
                return Triple(p, d, r)
            }
            acc += seg
        }
        return Triple(points.last(), Vec3.UP, radii.last())
    }

    /** Hat Kinder (fuer Endaeste ohne Kinder setzt der Generator Laub). */
    var hasChildren = false
}

/** Ein Zweigbueschel: Ansatz [pos], Wuchsrichtung [dir], Kantenlaenge [size], Abtoenung [tint]. */
internal class LeafCluster(val pos: Vec3, val dir: Vec3, val size: Double, val tint: Double, val flip: Boolean, val spin: Double)

/** Das Geruest eines Baums vor der Vernetzung. */
internal class Skeleton(
    val kind: TreeKind,
    val variant: Int,
    val heightM: Double,
    val branches: List<Branch>,
    val clusters: List<LeafCluster>,
) {
    /** Kronenhuelle (Mitte, waagrechter und senkrechter Halbmesser) aus den Bueschellagen. */
    val crownCenter: Vec3
    val crownRx: Double
    val crownRy: Double

    init {
        if (clusters.isEmpty()) {
            crownCenter = Vec3(0.0, heightM * 0.6, 0.0)
            crownRx = heightM * 0.3
            crownRy = heightM * 0.3
        } else {
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            var sx = 0.0
            var sz = 0.0
            for (c in clusters) {
                minY = min(minY, c.pos.y)
                maxY = max(maxY, c.pos.y)
                sx += c.pos.x
                sz += c.pos.z
            }
            val cx = sx / clusters.size
            val cz = sz / clusters.size
            var rx = 0.5
            for (c in clusters) rx = max(rx, sqrt((c.pos.x - cx) * (c.pos.x - cx) + (c.pos.z - cz) * (c.pos.z - cz)))
            crownCenter = Vec3(cx, (minY + maxY) / 2, cz)
            crownRx = rx
            crownRy = max(0.5, (maxY - minY) / 2)
        }
    }

    /** Normierte Lage zur Kronenhuelle (0 = Mitte, 1 = Rand). */
    fun rel(p: Vec3): Vec3 = Vec3((p.x - crownCenter.x) / crownRx, (p.y - crownCenter.y) / crownRy, (p.z - crownCenter.z) / crownRx)
}

/**
 * # Wachstum (Weber/Penn, vereinfacht)
 *
 * Stamm mit Schraeglage und Kruemmung, Hauptaeste spiralig (137,5°) oder in
 * Quirlen, Laenge nach der Kronenform, Abgangswinkel oben steiler; Seitenaeste
 * wechselstaendig in der Astebene (flache Laubsprays), Kruemmung ueber die
 * Astlaenge (Birke: haengend). Laub als Zweigbueschel an Endaesten. Alles aus
 * [TreeRandom] — derselbe Same ergibt bitweise denselben Baum.
 */
internal object TreeGrowth {
    fun grow(kind: TreeKind, variant: Int, seed: Long, heightM: Double? = null): Skeleton {
        val p = Species.of(kind)
        val v = variant.coerceIn(0, 3)
        val rnd = TreeRandom(seed * 0x9E3779B97F4A7C15uL.toLong() + kind.ordinal * 7919L + v * 104729L)
        val h = heightM ?: (p.heights[v] * rnd.range(0.95, 1.05))
        val widthF = 1 + p.widthVar * rnd.sym()
        val branches = ArrayList<Branch>()
        val clusters = ArrayList<LeafCluster>()
        val stems = p.stems
        for (st in 0 until stems) {
            val trunk = trunk(p, h * (if (stems > 1) rnd.range(0.75, 1.0) else 1.0), rnd, st, stems)
            branches += trunk
            growChildren(p, trunk, 1, h, widthF, rnd, branches)
        }
        // Laub an Endaesten (ohne Kinder); Stammspitze bekommt bei Laubbaeumen ein Bueschel.
        for (b in branches) {
            if (b.hasChildren) continue
            if (b.level == 0 && p.whorls) {
                // Fichtenspitze: wenige Bueschel entlang des Leittriebs
                leavesAlong(p, b, 0.82, 1.0, rnd, clusters, h, density = 2.0)
                continue
            }
            if (b.level == 0) continue
            leavesAlong(p, b, if (b.level >= 2) 0.1 else if (p.stems > 1) 0.12 else 0.4, 1.0, rnd, clusters, h)
        }
        // Hauptaeste mit Kindern tragen im aeusseren Teil ebenfalls Laub (nicht modellierte Kurztriebe)
        for (b in branches) {
            if (b.level == 1 && b.hasChildren) {
                // Nadelbaeume: der ganze Ast ist benadelt; Laubbaeume: nur der aeussere Teil
                if (p.whorls) leavesAlong(p, b, 0.2, 0.98, rnd, clusters, h, density = 0.8)
                else leavesAlong(p, b, 0.55, 0.98, rnd, clusters, h, density = 0.55)
            }
        }
        return Skeleton(kind, v, h, branches, clusters)
    }

    private fun rotate(v: Vec3, axis: Vec3, deg: Double): Vec3 = Quat.fromAxisAngle(axis, deg * PI / 180).rotate(v)

    /** Eine Achse senkrecht zu [a], um [a] um [angle] gedreht (Bezug: waagrecht, sonst x). */
    fun perp(a: Vec3, angle: Double): Vec3 {
        val ref = if (abs(a.y) < 0.95) Vec3.UP else Vec3.X
        val u = (ref cross a).normalized(Vec3.X)
        val w = (a cross u).normalized(Vec3.Z)
        return (u * cos(angle) + w * sin(angle)).normalized(Vec3.X)
    }

    private fun trunk(p: SpeciesParams, h: Double, rnd: TreeRandom, stem: Int, stems: Int): Branch {
        val len = h * p.trunkTop
        val segs = 10
        val az = rnd.range(0.0, 2 * PI)
        val leanDeg = p.lean * rnd.range(0.3, 1.0)
        var dir = rotate(Vec3.UP, Vec3(cos(az), 0.0, sin(az)), leanDeg)
        val start = if (stems > 1) {
            val a = 2 * PI * stem / stems + rnd.range(-0.4, 0.4)
            Vec3(cos(a) * 0.12, -0.2, sin(a) * 0.12)
        } else {
            Vec3(0.0, -0.3, 0.0)
        }
        val pts = ArrayList<Vec3>()
        pts += start
        var cur = start
        // erstes Stueck kurz (Wurzelanlauf wird dort feiner aufgeloest)
        val segLen = (len + 0.3) / segs
        for (i in 1..segs) {
            val g = p.trunkGnarl
            dir = rotate(dir, perp(dir, rnd.range(0.0, 2 * PI)), g * rnd.range(0.3, 1.0))
            // Lichtwendigkeit: zur Senkrechten zurueck (je weniger Schraeglage gewollt, desto staerker)
            val back = if (p.lean > 8) 0.08 else 0.25
            dir = (dir * (1 - back) + Vec3.UP * back).normalized()
            cur += dir * segLen
            pts += cur
        }
        val r0 = min(p.trunkRadiusMax, 0.04 + p.trunkRadiusPerM * h) * (if (stems > 1) 0.8 else 1.0)
        val conifer = p.trunkTop >= 0.99
        val radii = DoubleArray(pts.size) { i ->
            val t = i / segs.toDouble()
            if (conifer) max(0.015, r0 * (1 - t).let { it * (0.25 + 0.75 * it) } + 0.01) else r0 * (1 - 0.62 * t)
        }
        return Branch(0, pts, radii, null, 0.0)
    }

    private fun growChildren(p: SpeciesParams, parent: Branch, level: Int, h: Double, widthF: Double, rnd: TreeRandom, out: MutableList<Branch>) {
        val lv = (if (level == 1) p.level1 else p.level2) ?: return
        val crownBase = p.baseSize * h
        if (level == 1) {
            val n = max(1, (lv.count * rnd.range(0.85, 1.15) * (if (p.stems > 1) 0.6 else 1.0)).toInt())
            // Anteil des Stamms, ab dem Hauptaeste ansetzen
            val fb = if (parent.length < 1e-6) 0.0 else ((crownBase + 0.3) / parent.length).coerceIn(0.0, 0.92)
            val whorlSize = if (p.whorls) (if (p.kind == TreeKind.PINE) 4 else 5) else 1
            val whorls = max(1, n / whorlSize)
            for (k in 0 until n) {
                val group = k / whorlSize
                val inGroup = k % whorlSize
                val f = if (p.whorls) {
                    fb + (1 - fb) * ((group + 0.5 + 0.15 * rnd.sym()) / whorls).coerceIn(0.0, 0.985)
                } else {
                    fb + (1 - fb) * ((k + 0.5 + 0.35 * rnd.sym()) / n).coerceIn(0.0, 1.0)
                }
                val az = if (p.whorls) {
                    2 * PI * inGroup / whorlSize + group * 0.9 + 0.35 * rnd.sym()
                } else {
                    k * 2.39996 + 0.4 * rnd.sym()
                }
                val (pos, pdir, pr) = parent.at(f)
                val y = pos.y
                val rel = ((y - crownBase) / max(0.5, h - crownBase)).coerceIn(0.0, 1.0)
                var len = h * lv.lengthRatio * p.shape.ratio(rel) * widthF * rnd.range(0.82, 1.18)
                // Laubbaeume mit kurzem Stamm: Aeste am Stammende streben zur Kronenspitze
                if (p.trunkTop < 0.9 && f > 0.75) len = max(len, (h - y) * rnd.range(0.7, 0.95))
                // keine Laubkugeln an Stummeln: Hauptaeste mindestens 45 % der laengsten
                len = max(len, h * lv.lengthRatio * widthF * 0.45)
                if (len < 0.25) continue
                val steep = if (p.whorls) 0.25 else 0.5
                val down = lv.downAngle * (1 - steep * rel) + lv.downAngleVar * rnd.sym()
                val child = branch(p, lv, parent, pos, pdir, pr, f, az, down, len, level, rnd, h)
                parent.hasChildren = true
                out += child
                growChildren(p, child, level + 1, h, widthF, rnd, out)
            }
        } else {
            val n = max(1, (lv.count * parent.length * rnd.range(0.8, 1.2)).toInt())
            var side = if (rnd.next() < 0.5) 1.0 else -1.0
            for (k in 0 until n) {
                val f = lv.start + (1 - lv.start) * ((k + 0.5 + 0.3 * rnd.sym()) / n).coerceIn(0.0, 0.97)
                val (pos, pdir, pr) = parent.at(f)
                // wechselstaendig in der Astebene (Spray liegt etwa waagrecht), etwas nach oben gedreht
                val az = side * PI / 2 + 0.45 * rnd.sym() + (if (p.hanging) 0.0 else -0.25 * side)
                side = -side
                val len = parent.length * lv.lengthRatio * (1 - 0.55 * f) * rnd.range(0.75, 1.2)
                if (len < 0.2) continue
                val down = lv.downAngle + lv.downAngleVar * rnd.sym()
                val child = branch(p, lv, parent, pos, pdir, pr, f, az, down, len, level, rnd, h, planar = true)
                parent.hasChildren = true
                out += child
            }
        }
    }

    /**
     * Ein Ast ab [pos] auf dem Elternast (Richtung [pdir], Radius dort [pr]) unter dem
     * Abgangswinkel [down] und Drehung [az] um die Elternachse. [planar]: Drehung bezogen
     * auf die Waagrechte der Elternachse (Seitenaeste in der Astebene).
     */
    private fun branch(
        p: SpeciesParams, lv: BranchLevel, parent: Branch, pos: Vec3, pdir: Vec3, pr: Double, f: Double, az: Double,
        down: Double, len: Double, level: Int, rnd: TreeRandom, h: Double, planar: Boolean = false,
    ): Branch {
        val side = if (planar) {
            // "Oben" der Elternachse: Anteil der Senkrechten senkrecht zur Achse; seitlich davon
            val up = (Vec3.UP - pdir * (Vec3.UP dot pdir)).normalized(perp(pdir, 0.0))
            val right = (pdir cross up).normalized(Vec3.X)
            (up * cos(az) + right * sin(az)).normalized()
        } else {
            perp(pdir, az)
        }
        var dir = rotate(pdir, (pdir cross side).normalized(Vec3.X), -down)
        // Kein Ast nach unten in den Boden, keiner (Ordnung 1) waagrecht durch die Stammmitte zurueck
        if (dir.y < -0.75) dir = (dir + Vec3.UP * 0.6).normalized()
        val segs = if (level == 1) 6 else 3
        val segLen = len / segs
        val pts = ArrayList<Vec3>()
        // Ansatz etwas im Elternast versenkt (keine Luecke an der Gabel)
        pts += pos - dir * (pr * 0.5)
        var cur = pos
        pts += cur + dir * 0.0001
        for (i in 1..segs) {
            val bendAxis = (dir cross Vec3.UP).normalized(perp(dir, 0.0))
            val c = lv.curve / segs + (if (i > segs / 2) lv.curveBack / max(1, segs - segs / 2) else 0.0)
            // positive Kruemmung hebt die Spitze
            dir = rotate(dir, bendAxis, -c)
            dir = rotate(dir, perp(dir, rnd.range(0.0, 2 * PI)), lv.gnarl * rnd.range(0.0, 1.0))
            // nicht ueber die Baumhoehe hinaus: der Ast endet unter der Spitze
            if (cur.y + dir.y * segLen > h * 0.97 && dir.y > 0.05) {
                val k = ((h * 0.97 - cur.y) / (dir.y * segLen)).coerceIn(0.15, 1.0)
                cur += dir * (segLen * k)
                pts += cur
                break
            }
            cur += dir * segLen
            if (cur.y < 0.15) cur = Vec3(cur.x, 0.15, cur.z)
            pts += cur
        }
        val r0 = (pr * lv.radiusRatio * (0.55 + 0.45 * min(1.0, len / (h * 0.35)))).coerceIn(min(0.006, pr * 0.9), max(0.006, pr * 0.9))
        val radii = DoubleArray(pts.size) { i ->
            if (i == 0) r0 else {
                val t = (i - 1) / segs.toDouble()
                max(0.004, r0 * (1 - 0.82 * t))
            }
        }
        val vStart = parent.vStart + parent.length * f
        return Branch(level, pts, radii, parent, vStart)
    }

    /** Bueschel entlang [b] von Anteil [from] bis [to], Dichte nach Art; an der Spitze immer eines. */
    private fun leavesAlong(p: SpeciesParams, b: Branch, from: Double, to: Double, rnd: TreeRandom, out: MutableList<LeafCluster>, h: Double, density: Double = 1.0) {
        val span = b.length * (to - from)
        val n = max(1, floor(span * p.clustersPerM * density + rnd.next()).toInt())
        // grobe Kronenmitte (vor dem Laub bekannt): fuer "nach aussen"
        val center = Vec3(0.0, p.baseSize * h + (1 - p.baseSize) * h * 0.5, 0.0)
        for (k in 0 until n) {
            val t = if (k == n - 1) to else from + (to - from) * ((k + 0.3 + 0.4 * rnd.next()) / n)
            val (pos0, dir, _) = b.at(t)
            // kleine Varianten mit kleineren Bueschel (Massstab der Art bleibt)
            val size = p.clusterM * sqrt(h / p.heights[0]).coerceIn(0.7, 1.12) * rnd.range(0.82, 1.18) * (if (k == n - 1) 1.0 else 0.92)
            val out0 = (pos0 - center).let { Vec3(it.x, it.y * 0.6, it.z) }.normalized(Vec3.UP)
            val jitter = perp(dir, rnd.range(0.0, 2 * PI))
            // Bueschel stehen raeumlich um den Zweig (Klumpen statt Wedel): Richtung aus Zweig, aussen, Zufall
            var d = when {
                p.hanging -> dir * 0.4 + Vec3(0.0, -1.0, 0.0) + jitter * 0.35
                p.leafMotif == LeafMotif.NEEDLE -> Vec3(dir.x, dir.y * 0.4, dir.z) + out0 * 0.2 + jitter * 0.15
                else -> dir * 0.55 + out0 * 0.55 + jitter * 0.45 + Vec3(0.0, 0.2, 0.0)
            }.normalized(dir)
            if (p.leafMotif == LeafMotif.NEEDLE) d = d.normalized(dir)
            val pos = pos0 + jitter * (size * 0.25 * rnd.next()) - d * (size * 0.15)
            out += LeafCluster(pos, d, size, rnd.range(0.9, 1.1), rnd.next() < 0.5, rnd.range(0.0, PI))
        }
    }
}
