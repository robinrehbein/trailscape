package de.trailscape.core.indoor.generated

import de.trailscape.core.indoor.world.Noise
import de.trailscape.core.indoor.world.pointInRings
import de.trailscape.core.indoor.world.smoothstep
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Das erfundene Gelaende um eine generierte Strecke
 *
 * `H = E + M · g(d)`:
 *  * **E** — die Hoehe der Strecke, ins Gelaende fortgesetzt: gewichtetes
 *    Mittel ueber die Streckenstuecke im Umkreis von 500 m (Gewicht
 *    `1/(d² + 4)^1,5`, Hoehe am Lotfusspunkt, also ohne Plateaus an den
 *    Stuetzstellen), dazu ein grobes Mittel ueber 4 km als Hintergrund. Auf
 *    der Linie ist E genau die Streckenhoehe; zwischen zwei Rampen einer
 *    Serpentine entsteht von selbst der Hang.
 *  * **M** — Berge bzw. Huegel aus Rauschen (Mittelgebirge: Ruecken bis
 *    ~220 m ueber dem Tal; Huegelland ±50 m), in Ortsnaehe gedaempft.
 *  * **g(d)** — 0 an der Strecke, steigt mit dem Abstand d zur Linie auf 1:
 *    Die Strecke liegt im Talgrund bzw. auf sanften Wellen, nie an einer
 *    Steilwand, die nicht zu ihrer Steigung passt.
 *
 * Seen werden als Mulden mit ebenem Grund eingeschnitten. Abgefragt wird ein
 * Raster (10 m bis 2,2 km neben der Strecke, sonst 40 m), bikubisch
 * (Catmull-Rom), blockweise beim ersten Bedarf gerechnet — rein aus den
 * Koordinaten, also unabhaengig von Reihenfolge und Threads.
 */
internal class GenTerrain(private val plan: GenRoutePlan) {
    private val landscape = plan.landscape
    private val seedA = genSeed(plan.profile.seed, 20)
    private val seedB = genSeed(plan.profile.seed, 21)
    private val seedC = genSeed(plan.profile.seed, 22)

    // Streckenstuecke alle 25 m
    private val segStride = 5
    private val vE: DoubleArray
    private val vN: DoubleArray
    private val vU: DoubleArray
    private val buckets = HashMap<Long, IntArray>()
    private val bucketM = 100.0
    private val nearR = 500.0

    // grob: alle 250 m
    private val cE: DoubleArray
    private val cN: DoubleArray
    private val cU: DoubleArray
    private val cBuckets = HashMap<Long, IntArray>()
    private val cBucketM = 1_000.0
    private val farR = 4_000.0
    private val meanEle: Double
    private val scratch: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(2) }

    /** Seen: Ring (Entwurfs-ENU), Spiegelhoehe des Ufers, Mitte, Radius. */
    val lakes: List<Lake>

    class Lake(val ring: DoubleArray, val level: Double, val ce: Double, val cn: Double, val radius: Double)

    init {
        val idx = (0 until plan.count step segStride).toMutableList()
        if (idx.last() != plan.count - 1) idx += plan.count - 1
        vE = DoubleArray(idx.size) { plan.e[idx[it]] }
        vN = DoubleArray(idx.size) { plan.n[idx[it]] }
        vU = DoubleArray(idx.size) { plan.ele[idx[it]] }
        val tmp = HashMap<Long, MutableList<Int>>()
        for (k in 0 until vE.size - 1) {
            val x0 = floor(min(vE[k], vE[k + 1]) / bucketM).toLong()
            val x1 = floor(max(vE[k], vE[k + 1]) / bucketM).toLong()
            val y0 = floor(min(vN[k], vN[k + 1]) / bucketM).toLong()
            val y1 = floor(max(vN[k], vN[k + 1]) / bucketM).toLong()
            for (x in x0..x1) for (y in y0..y1) tmp.getOrPut(key(x, y)) { ArrayList() }.add(k)
        }
        for ((k, v) in tmp) buckets[k] = v.toIntArray()

        val cIdx = (0 until plan.count step 50).toMutableList()
        cE = DoubleArray(cIdx.size) { plan.e[cIdx[it]] }
        cN = DoubleArray(cIdx.size) { plan.n[cIdx[it]] }
        cU = DoubleArray(cIdx.size) { plan.ele[cIdx[it]] }
        val ctmp = HashMap<Long, MutableList<Int>>()
        for (k in cE.indices) ctmp.getOrPut(key(floor(cE[k] / cBucketM).toLong(), floor(cN[k] / cBucketM).toLong())) { ArrayList() }.add(k)
        for ((k, v) in ctmp) cBuckets[k] = v.toIntArray()
        meanEle = plan.ele.average()
        lakes = placeLakes()
    }

    private fun key(x: Long, y: Long): Long = (x shl 32) xor (y and 0xffffffffL)

    // -----------------------------------------------------------------------
    // Abstand zur Strecke
    // -----------------------------------------------------------------------

    /** Abstand zur Linie (genau bis [nearR], sonst grob ueber die 250-m-Stuetzstellen, hoechstens 10 km). */
    fun distanceToRoute(e: Double, n: Double): Double {
        val near = nearDistance(e, n)
        if (near < nearR) return near
        return coarseDistance(e, n)
    }

    private fun coarseDistance(e: Double, n: Double): Double {
        var best = 10_000.0
        val bx = floor(e / cBucketM).toLong()
        val by = floor(n / cBucketM).toLong()
        val r = 3L
        for (dx in -r..r) for (dy in -r..r) {
            val list = cBuckets[key(bx + dx, by + dy)] ?: continue
            for (k in list) best = min(best, hypot(cE[k] - e, cN[k] - n))
        }
        return (best - 130.0).coerceAtLeast(nearR)
    }

    private fun nearDistance(e: Double, n: Double): Double {
        var best = Double.MAX_VALUE
        val bx = floor(e / bucketM).toLong()
        val by = floor(n / bucketM).toLong()
        val r = (nearR / bucketM).toLong()
        for (dx in -r..r) for (dy in -r..r) {
            val list = buckets[key(bx + dx, by + dy)] ?: continue
            for (k in list) {
                val d = segDist(k, e, n)
                if (d < best) best = d
            }
        }
        return best
    }

    private fun segDist(k: Int, e: Double, n: Double): Double {
        val ax = vE[k]
        val ay = vN[k]
        val dx = vE[k + 1] - ax
        val dy = vN[k + 1] - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) (((e - ax) * dx + (n - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
        return hypot(ax + dx * t - e, ay + dy * t - n)
    }

    // -----------------------------------------------------------------------
    // Analytisches Feld
    // -----------------------------------------------------------------------

    /** Hoehe der Strecke ins Gelaende fortgesetzt (E) und Abstand zur Linie. */
    private fun routeField(e: Double, n: Double, out: DoubleArray) {
        var ws = 0.0
        var vs = 0.0
        var dmin = Double.MAX_VALUE
        val bx = floor(e / bucketM).toLong()
        val by = floor(n / bucketM).toLong()
        val r = (nearR / bucketM).toLong()
        for (dx in -r..r) for (dy in -r..r) {
            val list = buckets[key(bx + dx, by + dy)] ?: continue
            for (k in list) {
                // Ein Stueck liegt in mehreren Eimern: nur im Eimer seines Anfangspunkts zaehlen.
                if (floor(vE[k] / bucketM).toLong() != bx + dx || floor(vN[k] / bucketM).toLong() != by + dy) continue
                val ax = vE[k]
                val ay = vN[k]
                val sx = vE[k + 1] - ax
                val sy = vN[k + 1] - ay
                val l2 = sx * sx + sy * sy
                val t = if (l2 > 0) (((e - ax) * sx + (n - ay) * sy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(ax + sx * t - e, ay + sy * t - n)
                if (d < dmin) dmin = d
                if (d > nearR) continue
                val q = d * d + 4.0
                val fade = 1 - smoothstep(nearR * 0.6, nearR, d)
                val w = fade / (q * sqrt(q))
                ws += w
                vs += w * (vU[k] + (vU[k + 1] - vU[k]) * t)
            }
        }
        val far = farField(e, n)
        val d0 = 300.0
        val q0 = d0 * d0 + 4.0
        val wBg = 1 / (q0 * sqrt(q0))
        out[0] = (vs + wBg * far) / (ws + wBg)
        out[1] = if (dmin < nearR) dmin else distanceToRoute(e, n)
    }

    private fun farField(e: Double, n: Double): Double {
        var ws = 1e-6
        var vs = 1e-6 * meanEle
        val bx = floor(e / cBucketM).toLong()
        val by = floor(n / cBucketM).toLong()
        val r = (farR / cBucketM).toLong()
        for (dx in -r..r) for (dy in -r..r) {
            val list = cBuckets[key(bx + dx, by + dy)] ?: continue
            for (k in list) {
                val d = hypot(cE[k] - e, cN[k] - n)
                if (d >= farR) continue
                val u = 1 - (d / farR) * (d / farR)
                val w = u * u
                ws += w
                vs += w * cU[k]
            }
        }
        return vs / ws
    }

    /** Berge/Huegel neben der Strecke (vor g(d)). */
    private fun relief(e: Double, n: Double, d: Double): Double {
        return when (landscape) {
            GenLandscape.MITTELGEBIRGE -> {
                val ridge = Noise.fbm(e / 2_600.0, n / 2_600.0, seedA, 5)
                val hills = Noise.fbm(e / 700.0, n / 700.0, seedB, 3)
                val fine = Noise.fbm(e / 180.0, n / 180.0, seedC, 2) * (1 - smoothstep(1_200.0, 1_800.0, d))
                (175.0 * (0.42 + 0.75 * ridge) + 38.0 * hills + 5.0 * fine).coerceAtLeast(-25.0)
            }
            GenLandscape.HUEGELLAND -> {
                val big = Noise.fbm(e / 1_900.0, n / 1_900.0, seedA, 4)
                val hills = Noise.fbm(e / 620.0, n / 620.0, seedB, 3)
                val fine = Noise.fbm(e / 170.0, n / 170.0, seedC, 2) * (1 - smoothstep(1_200.0, 1_800.0, d))
                60.0 * big + 16.0 * hills + 3.0 * fine + 6.0
            }
        }
    }

    private fun rise(d: Double): Double = when (landscape) {
        GenLandscape.MITTELGEBIRGE -> smoothstep(12.0, 420.0, d)
        GenLandscape.HUEGELLAND -> smoothstep(15.0, 320.0, d)
    }

    /** Daempfung in Ortsnaehe (0 = voll, 1 = keine). */
    private fun villageCalm(e: Double, n: Double): Double {
        var c = 1.0
        for (v in plan.villages) {
            val i0 = plan.indexAt(v[0])
            val i1 = plan.indexAt(v[1])
            // grobe Vorpruefung ueber Mitte
            val im = (i0 + i1) / 2
            val half = (v[1] - v[0]) / 2
            if (hypot(plan.e[im] - e, plan.n[im] - n) > half + 500) continue
            var best = Double.MAX_VALUE
            var i = i0
            while (i <= i1) {
                best = min(best, hypot(plan.e[i] - e, plan.n[i] - n))
                i += 4
            }
            c = min(c, 0.3 + 0.7 * smoothstep(90.0, 420.0, best))
        }
        return c
    }

    /** Gelaende ohne Seen. */
    private fun dryAnalytic(e: Double, n: Double): Double {
        val o = scratch.get()
        routeField(e, n, o)
        val d = o[1]
        return o[0] + relief(e, n, d) * rise(d) * villageCalm(e, n)
    }

    /** Das analytische Gelaende (Meter ueber dem Meer, Entwurfs-ENU). */
    fun analytic(e: Double, n: Double): Double {
        var h = dryAnalytic(e, n)
        for (l in lakes) {
            val dc = hypot(e - l.ce, n - l.cn)
            if (dc > l.radius * 1.6 + 40) continue
            val inside = pointInRings(listOf(l.ring), e, n)
            val dEdge = ringDistance(l.ring, e, n)
            h = if (inside) {
                min(h, l.level - 0.6 - 3.5 * smoothstep(0.0, l.radius * 0.5, dEdge))
            } else {
                val t = 1 - smoothstep(0.0, 30.0, dEdge)
                val target = min(h, l.level + 0.5)
                h + (target - h) * t
            }
        }
        return h
    }

    // -----------------------------------------------------------------------
    // Seen
    // -----------------------------------------------------------------------

    private fun placeLakes(): List<Lake> {
        val sd = genSeed(plan.profile.seed, 23)
        val want = when (landscape) {
            GenLandscape.HUEGELLAND -> 1 + (plan.lengthM / 9_000).toInt().coerceAtMost(5)
            GenLandscape.MITTELGEBIRGE -> if (Noise.hash01(sd, 0, 0) < 0.6) 1 + (plan.lengthM / 25_000).toInt().coerceAtMost(2) else 0
        }
        val out = ArrayList<Lake>()
        var tries = 0
        while (out.size < want && tries < want * 25) {
            val s = plan.lengthM * Noise.hash01(sd, tries, 1)
            val i = plan.indexAt(s)
            val j = min(plan.count - 1, i + 2)
            val i0 = max(0, i - 2)
            val te = plan.e[j] - plan.e[i0]
            val tn = plan.n[j] - plan.n[i0]
            val tl = hypot(te, tn).coerceAtLeast(1e-6)
            val side = if (Noise.hash01(sd, tries, 2) < 0.5) 1.0 else -1.0
            val radius = if (landscape == GenLandscape.HUEGELLAND) 80.0 + 170.0 * Noise.hash01(sd, tries, 3) else 70.0 + 120.0 * Noise.hash01(sd, tries, 3)
            val off = radius + 70.0 + 200.0 * Noise.hash01(sd, tries, 4)
            val ce = plan.e[i] - tn / tl * off * side
            val cn = plan.n[i] + te / tl * off * side
            tries++
            if (plan.inVillage(s, 400.0)) continue
            if (out.any { hypot(it.ce - ce, it.cn - cn) < it.radius + radius + 150 }) continue
            val k = 36
            val ring = DoubleArray(2 * k)
            val ph1 = 2 * PI * Noise.hash01(sd, tries, 5)
            val ph2 = 2 * PI * Noise.hash01(sd, tries, 6)
            val stretch = 0.7 + 0.5 * Noise.hash01(sd, tries, 7)
            val rot = PI * Noise.hash01(sd, tries, 8)
            var ok = true
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (q in 0 until k) {
                val a = 2 * PI * q / k
                val r = radius * (1 + 0.16 * sin(2 * a + ph1) + 0.09 * cos(3 * a + ph2) + 0.04 * sin(5 * a))
                val x = r * cos(a) * stretch
                val y = r * sin(a) / stretch
                val px = ce + x * cos(rot) - y * sin(rot)
                val py = cn + x * sin(rot) + y * cos(rot)
                ring[2 * q] = px
                ring[2 * q + 1] = py
                if (nearDistance(px, py) < 55.0) {
                    ok = false
                    break
                }
                val h = dryAnalytic(px, py)
                lo = min(lo, h)
                hi = max(hi, h)
            }
            if (!ok) continue
            if (hi - lo > (if (landscape == GenLandscape.HUEGELLAND) 14.0 else 20.0)) continue
            // Spiegel unter dem niedrigsten Ufer, aber nie ueber der Strecke daneben
            val level = lo - 0.4
            out += Lake(ring, level, ce, cn, radius * max(stretch, 1 / stretch))
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Raster
    // -----------------------------------------------------------------------

    private class Block(val e0: Double, val n0: Double, val step: Double, val h: FloatArray)

    private val blocks = ConcurrentHashMap<Long, Block>()

    private val fineStep = 10.0
    private val coarseStep = 40.0
    private val cells = 64
    private val apron = 2
    private val side = cells + 2 * apron + 1

    private fun fineWanted(bx: Long, by: Long): Boolean {
        val size = fineStep * cells
        val ce = (bx + 0.5) * size
        val cn = (by + 0.5) * size
        return distanceToRoute(ce, cn) < 2_200.0
    }

    private fun block(fine: Boolean, bx: Long, by: Long): Block {
        val k = (if (fine) 1L shl 62 else 0L) xor key(bx, by)
        return blocks.computeIfAbsent(k) {
            val step = if (fine) fineStep else coarseStep
            val e0 = bx * cells * step - apron * step
            val n0 = by * cells * step - apron * step
            val h = FloatArray(side * side)
            for (j in 0 until side) for (i in 0 until side) h[j * side + i] = analytic(e0 + i * step, n0 + j * step).toFloat()
            Block(e0, n0, step, h)
        }
    }

    private val fineFlag = ConcurrentHashMap<Long, Boolean>()

    /** Gelaendehoehe ueber dem Meer (Entwurfs-ENU), bikubisch aus dem Raster. */
    fun heightAt(e: Double, n: Double): Double {
        val fs = fineStep * cells
        val fbx = floor(e / fs).toLong()
        val fby = floor(n / fs).toLong()
        val fine = fineFlag.computeIfAbsent(key(fbx, fby)) { fineWanted(fbx, fby) }
        val b = if (fine) block(true, fbx, fby) else {
            val cs = coarseStep * cells
            block(false, floor(e / cs).toLong(), floor(n / cs).toLong())
        }
        val fx = (e - b.e0) / b.step
        val fy = (n - b.n0) / b.step
        val ix = floor(fx).toInt().coerceIn(1, side - 3)
        val iy = floor(fy).toInt().coerceIn(1, side - 3)
        val tx = fx - ix
        val ty = fy - iy
        val h = b.h
        fun row(j: Int): Double {
            val o = j * side + ix
            return catmull(h[o - 1].toDouble(), h[o].toDouble(), h[o + 1].toDouble(), h[o + 2].toDouble(), tx)
        }
        return catmull(row(iy - 1), row(iy), row(iy + 1), row(iy + 2), ty)
    }

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double =
        p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))

    /** Hangneigung (Betrag des Gradienten, 0.1 = 10 %) aus dem Raster. */
    fun slopeAt(e: Double, n: Double): Double {
        val d = 12.0
        val gx = (heightAt(e + d, n) - heightAt(e - d, n)) / (2 * d)
        val gy = (heightAt(e, n + d) - heightAt(e, n - d)) / (2 * d)
        return hypot(gx, gy)
    }

    companion object {
        fun ringDistance(ring: DoubleArray, e: Double, n: Double): Double {
            var best = Double.MAX_VALUE
            val k = ring.size / 2
            for (q in 0 until k) {
                val a = q
                val b = (q + 1) % k
                val ax = ring[2 * a]
                val ay = ring[2 * a + 1]
                val dx = ring[2 * b] - ax
                val dy = ring[2 * b + 1] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 > 0) (((e - ax) * dx + (n - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                best = min(best, hypot(ax + dx * t - e, ay + dy * t - n))
            }
            return best
        }
    }
}
