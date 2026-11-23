package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Das endgueltige Gelaende
 *
 * [CarvedGround] ist die [GroundSurface] der Welt: das Hoehenmodell, an
 * Strecke und Strassen eingeebnet. Alle Schichten und alle Detailstufen
 * fragen **dieselbe** Flaeche ab; Stufe 1 und 2 tasten sie nur groeber ab.
 */

/** Wasseranteil einer Stelle, stetig in `0..1` (z. B. bilinear aus einem Klassenraster). */
internal fun interface WaterMask {
    fun waterAt(e: Double, n: Double): Double
}

/**
 * Das Gelaende, wie es in der Welt steht.
 *
 * ## Aufbau (von innen nach aussen)
 * 1. **Daempfung** des Hoehenmodells: 3×3-Zeltfilter mit [dampM] Abstand —
 *    nimmt die Knicke der bilinearen Hoehenkacheln und die Kanten von
 *    Hoehenterrassen, ohne Formen ueber 30 m zu veraendern.
 * 2. **Uebrige Strassen und Wege** ([MapRoad], ohne Bruecken und Tunnel):
 *    quer eben innerhalb halber Breite ([roadHalfWidthM]) + 0,3 m, laengs das
 *    geglaettete Gelaende entlang der Linie (σ = 8 m), Uebergang ueber 4 m.
 *    Damit kippen Strassen am Hang nicht seitlich.
 * 3. **Die Strecke** gewinnt gegen alles: innerhalb halber Fahrbahnbreite +
 *    [TRACK_SHOULDER_M] liegt der Boden quer eben genau [TRACK_GROUND_DEPTH_M]
 *    unter der Fahrbahn (`track.u`). Liegt das Gelaende hoeher (Einschnitt),
 *    folgt zuerst ein **Bankett** von [bermM] Breite, das hoechstens
 *    [TRACK_BERM_RISE_M] ansteigt, dann die Boeschung; liegt es tiefer
 *    (Damm), beginnt die Boeschung sofort. Boeschungsbreite
 *    `max(10 + 0,5·|Δ|, 2,25·|Δ|)`, hoechstens 45 m — die steilste Stelle des
 *    weichen Profils bleibt so bei hoechstens 1 : 1,5.
 *
 *    **Warum das Geländenetz nie durch die Fahrbahn sticht:** Ein Punkt der
 *    Fahrbahn (seitlich ≤ halbe Breite) liegt in einem Netzdreieck, dessen
 *    Ecken hoechstens eine Zellendiagonale `√2·c` entfernt sind. [bermM] ist
 *    `√2·c + 0,3 m`, also liegen alle diese Ecken auf Fahrbahnhoehe − 0,25 m
 *    + hoechstens 0,06 m (Bankett). Linear interpoliert bleibt das Dreieck
 *    damit ≥ 0,19 m unter der Fahrbahn; Laengskruemmung der Fahrbahn
 *    (σ = 12 m geglaettet) kostet auf 6 m Sehne nur wenige Zentimeter. Auf
 *    Daemmen liegen die aeusseren Ecken tiefer — das Dreieck sinkt nur.
 *    Mehrere Aeste der Strecke (Kehren, Achten) werden ueber ihre lokalen
 *    Abstands-Minima stetig gemischt; unter jedem Fahrbahnpunkt gilt exakt
 *    sein eigener Ast.
 * 4. **Wasser** ([water]): Innerhalb von Wasserflaechen liegt der Boden
 *    [WATER_GROUND_DEPTH_M] tiefer, stetig ueber den Rand des Wasseranteils
 *    (0,25 → 0,75). **Regel fuer den Wasser-Erzeuger:** Die Wasseroberflaeche
 *    liegt auf Uferhoehe = [dryHeightAt] − 0,05 m an jeder Stelle (folgt so
 *    auch dem Gefaelle eines Flusses); der Boden ([heightAt]) liegt dort
 *    0,35 m darunter und sticht nie durch.
 *
 * Unter Brueckenstuecken der Strecke ([bridges]) wird nicht eingeebnet.
 * Threadsicher; ein Aufruf kostet einige Mikrosekunden (Rasterindex fuer
 * Strecke und Strassen, Strassen-Blöcke werden beim ersten Bedarf gebaut).
 *
 * @param terrainCellM Zellenkante des feinsten Gelaendenetzes (Stufe 0) —
 *   bestimmt die Bankettbreite, siehe [level0TerrainCellM].
 */
internal class CarvedGround(
    private val raw: RawHeights,
    private val track: TrackLine,
    private val map: MapData = MapData.EMPTY,
    terrainCellM: Double = 4.0,
    private val water: WaterMask? = null,
    private val bridges: (distM: Double) -> Boolean = { false },
    private val dampM: Double = 8.0,
) : GroundSurface {

    /** Breite des Banketts neben der Fahrbahn in Einschnitten. */
    val bermM: Double = max(1.5, sqrt(2.0) * terrainCellM + 0.3)

    private val wt = track.track
    private val segCount = wt.sampleCount - 1
    private val maxHalfWidth = wt.surfaces.maxOf { it.effectiveWidthM } / 2
    private val reachM = maxHalfWidth + TRACK_SHOULDER_M + bermM + SLOPE_MAX_M + PROMINENCE_M
    private val segGrid = HashMap<Long, IntArray>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (i in 0 until segCount) {
            val x0 = floor(min(wt.e[i], wt.e[i + 1]) / SEG_CELL).toInt()
            val x1 = floor(max(wt.e[i], wt.e[i + 1]) / SEG_CELL).toInt()
            val y0 = floor(min(wt.n[i], wt.n[i + 1]) / SEG_CELL).toInt()
            val y1 = floor(max(wt.n[i], wt.n[i + 1]) / SEG_CELL).toInt()
            for (x in x0..x1) for (y in y0..y1) tmp.getOrPut(cellKey(x, y)) { ArrayList() }.add(i)
        }
        for ((k, v) in tmp) segGrid[k] = v.toIntArray()
    }

    override fun heightAt(e: Double, n: Double): Double {
        val dry = dryHeightAt(e, n)
        val w = water ?: return dry
        val f = w.waterAt(e, n)
        return if (f <= 0.25) dry else dry - WATER_GROUND_DEPTH_M * smoothstep(0.25, 0.75, f)
    }

    /** Das Gelaende ohne die Absenkung unter Wasserflaechen — die Uferhoehe fuer den Wasser-Erzeuger. */
    fun dryHeightAt(e: Double, n: Double): Double {
        val base = roadCarved(e, n)
        return trackCarved(e, n, base)
    }

    /** Das gedaempfte Hoehenmodell (Schritt 1). */
    fun dampedAt(e: Double, n: Double): Double {
        val d = dampM
        var s = 0.0
        for (j in -1..1) {
            val wj = if (j == 0) 2.0 else 1.0
            for (i in -1..1) {
                val wi = if (i == 0) 2.0 else 1.0
                s += raw.heightAt(e + i * d, n + j * d) * wi * wj
            }
        }
        return s / 16.0
    }

    // -----------------------------------------------------------------------
    // Strecke
    // -----------------------------------------------------------------------

    private class Scratch {
        var stamp = IntArray(0)
        var query = 0
        var segs = IntArray(64)
        var dist = DoubleArray(64)
        var param = DoubleArray(64)
        var count = 0
    }

    private val scratch = ThreadLocal.withInitial { Scratch() }

    private fun trackCarved(e: Double, n: Double, base: Double): Double {
        val sc = scratch.get()
        if (sc.stamp.size < segCount) sc.stamp = IntArray(segCount)
        sc.query++
        if (sc.query == Int.MAX_VALUE) {
            sc.stamp.fill(0)
            sc.query = 1
        }
        sc.count = 0
        val x0 = floor((e - reachM) / SEG_CELL).toInt()
        val x1 = floor((e + reachM) / SEG_CELL).toInt()
        val y0 = floor((n - reachM) / SEG_CELL).toInt()
        val y1 = floor((n + reachM) / SEG_CELL).toInt()
        for (x in x0..x1) for (y in y0..y1) {
            val list = segGrid[cellKey(x, y)] ?: continue
            for (s in list) {
                if (sc.stamp[s] == sc.query) continue
                sc.stamp[s] = sc.query
                if (sc.count == sc.segs.size) {
                    sc.segs = sc.segs.copyOf(sc.count * 2)
                    sc.dist = sc.dist.copyOf(sc.count * 2)
                    sc.param = sc.param.copyOf(sc.count * 2)
                }
                sc.segs[sc.count++] = s
            }
        }
        if (sc.count == 0) return base
        java.util.Arrays.sort(sc.segs, 0, sc.count)
        for (k in 0 until sc.count) {
            val i = sc.segs[k]
            val ax = wt.e[i].toDouble()
            val ay = wt.n[i].toDouble()
            val dx = wt.e[i + 1] - ax
            val dy = wt.n[i + 1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((e - ax) * dx + (n - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            sc.param[k] = t
            sc.dist[k] = hypot(ax + dx * t - e, ay + dy * t - n)
        }

        // Laeufe aufeinanderfolgender Segmente; auf einer Runde schliesst das letzte an das erste an.
        val cnt = sc.count
        var startRun = 0
        var firstRunEnd = -1
        var sumW = 0.0
        var sumWS = 0.0
        var exactSum = 0.0
        var exactCnt = 0
        val wrap = wt.loop && cnt > 1 && sc.segs[0] == 0 && sc.segs[cnt - 1] == segCount - 1
        // Laeufe sammeln als (Anfang, Ende) im sortierten Feld.
        val runs = ArrayList<IntArray>(4)
        for (k in 1..cnt) {
            if (k == cnt || sc.segs[k] != sc.segs[k - 1] + 1) {
                runs.add(intArrayOf(startRun, k))
                startRun = k
            }
        }
        if (wrap && runs.size > 1) firstRunEnd = runs[0][1]
        val order = IntArray(cnt)
        fun segDist(k: Int): Double {
            val i = sc.segs[k]
            return wt.distAt(i) + (wt.distAt(i + 1) - wt.distAt(i)) * sc.param[k]
        }
        fun evalRun(seq: IntArray, len: Int) {
            for (j in 0 until len) {
                val dj = sc.dist[seq[j]]
                val leftLower = j == 0 || dj < sc.dist[seq[j - 1]]
                val rightOk = j == len - 1 || dj <= sc.dist[seq[j + 1]]
                if (!leftLower || !rightOk) continue
                // Prominenz gegen die naechsten tieferen Minima (Laufende zaehlt als unendlich).
                var maxL = dj
                var promL = Double.POSITIVE_INFINITY
                for (q in j - 1 downTo 0) {
                    val v = sc.dist[seq[q]]
                    if (v < dj) {
                        promL = maxL - dj
                        break
                    }
                    if (v > maxL) maxL = v
                }
                var maxR = dj
                var promR = Double.POSITIVE_INFINITY
                for (q in j + 1 until len) {
                    val v = sc.dist[seq[q]]
                    if (v < dj) {
                        promR = maxR - dj
                        break
                    }
                    if (v > maxR) maxR = v
                }
                val prom = min(promL, promR)
                // Laufmeter des Asts: auf der Innenseite einer Kurve springt der
                // naechste Punkt zwischen Nachbarsegmenten — deshalb ueber die
                // fast gleich nahen Segmente gemittelt (stetig).
                val dm0 = segDist(seq[j])
                var sw = 0.0
                var swd = 0.0
                for (q in max(0, j - 3)..min(len - 1, j + 3)) {
                    val ds = sc.dist[seq[q]] - dj
                    if (ds >= CORNER_BLEND_M) continue
                    val w = (1 - ds / CORNER_BLEND_M).let { it * it }
                    var dq = segDist(seq[q])
                    if (wt.loop) {
                        if (dq - dm0 > wt.lengthM / 2) dq -= wt.lengthM
                        if (dm0 - dq > wt.lengthM / 2) dq += wt.lengthM
                    }
                    sw += w
                    swd += w * dq
                }
                var distM = swd / sw
                if (wt.loop) distM = ((distM % wt.lengthM) + wt.lengthM) % wt.lengthM
                if (bridges(distM)) continue
                val u = uAt(distM)
                val f0 = track.halfWidthAt(distM) + TRACK_SHOULDER_M
                val x = dj - f0
                val b = u - TRACK_GROUND_DEPTH_M
                if (x <= 0) {
                    exactSum += b
                    exactCnt++
                    continue
                }
                val delta = base - b
                val w = slopeWidth(delta)
                val reach = bermM + w
                if (x >= reach) continue
                val s = if (delta >= 0) {
                    b + min(delta, TRACK_BERM_RISE_M) * smoothstep(0.0, bermM, x) +
                        max(0.0, delta - TRACK_BERM_RISE_M) * smoothstep(bermM, bermM + w, x)
                } else {
                    b + delta * smoothstep(0.0, w, x)
                }
                val c = x / reach
                val weight = (1.0 / (c * c) - 1.0) * smoothstep(0.0, PROMINENCE_M, prom)
                if (weight <= 0) continue
                sumW += weight
                sumWS += weight * s
            }
        }
        if (firstRunEnd >= 0) {
            // Letzter Lauf + erster Lauf bilden einen Ast.
            val last = runs[runs.size - 1]
            var p = 0
            for (k in last[0] until last[1]) order[p++] = k
            for (k in 0 until firstRunEnd) order[p++] = k
            evalRun(order, p)
            for (r in 1 until runs.size - 1) {
                val run = runs[r]
                var q = 0
                for (k in run[0] until run[1]) order[q++] = k
                evalRun(order, q)
            }
        } else {
            for (run in runs) {
                var q = 0
                for (k in run[0] until run[1]) order[q++] = k
                evalRun(order, q)
            }
        }
        if (exactCnt > 0) return exactSum / exactCnt
        return if (sumW > 0) sumWS / sumW else base
    }

    /** Fahrbahnhoehe am Laufmeter (linear zwischen den Proben). */
    private fun uAt(distM: Double): Double {
        val i = (distM / wt.stepM).toInt().coerceIn(0, segCount - 1)
        val a = wt.distAt(i)
        val span = wt.distAt(i + 1) - a
        val t = if (span > 0) ((distM - a) / span).coerceIn(0.0, 1.0) else 0.0
        return wt.u[i] + (wt.u[i + 1] - wt.u[i]) * t
    }

    // -----------------------------------------------------------------------
    // Uebrige Strassen
    // -----------------------------------------------------------------------

    /** Strassen eines Blocks mit geglaettetem Laengsprofil und Segmentraster. */
    private class RoadBlock(
        /** je Segment: e0, n0, e1, n1, h0, h1, halbe Breite. */
        val seg: DoubleArray,
        /** je Segment die Strasse (fuer „naechstes Segment je Strasse"). */
        val road: IntArray,
        val roadCount: Int,
        val grid: HashMap<Long, IntArray>,
    )

    private val roadBlocks = BoundedCache<Long, RoadBlock>(MAX_ROAD_BLOCKS)

    private fun roadBlock(bx: Int, by: Int): RoadBlock = roadBlocks.getOrPut(cellKey(bx, by)) { buildRoadBlock(bx, by) }

    private fun buildRoadBlock(bx: Int, by: Int): RoadBlock {
        val minE = bx * ROAD_BLOCK
        val minN = by * ROAD_BLOCK
        val m = ROAD_REACH + 1.0
        val roads = map.roads(minE - m, minN - m, minE + ROAD_BLOCK + m, minN + ROAD_BLOCK + m)
        val seg = ArrayList<Double>()
        val owner = ArrayList<Int>()
        var roadIdx = 0
        for (r in roads) {
            if (r.brunnel == "bridge" || r.brunnel == "tunnel") continue
            val hw = roadHalfWidthM(r.cls) ?: continue
            if (r.line.size < 4) continue
            val (pe, pn, ph) = roadProfile(r.line)
            for (i in 0 until pe.size - 1) {
                // Nur Segmente nahe dem Block.
                val sMinE = min(pe[i], pe[i + 1])
                val sMaxE = max(pe[i], pe[i + 1])
                val sMinN = min(pn[i], pn[i + 1])
                val sMaxN = max(pn[i], pn[i + 1])
                if (sMaxE < minE - m || sMinE > minE + ROAD_BLOCK + m || sMaxN < minN - m || sMinN > minN + ROAD_BLOCK + m) continue
                seg.add(pe[i]); seg.add(pn[i]); seg.add(pe[i + 1]); seg.add(pn[i + 1])
                seg.add(ph[i]); seg.add(ph[i + 1]); seg.add(hw)
                owner.add(roadIdx)
            }
            roadIdx++
        }
        val arr = seg.toDoubleArray()
        val grid = HashMap<Long, MutableList<Int>>()
        for (s in owner.indices) {
            val o = s * 7
            val reach = arr[o + 6] + ROAD_FLAT_EXTRA_M + ROAD_BLEND_M
            val x0 = floor((min(arr[o], arr[o + 2]) - reach) / ROAD_CELL).toInt()
            val x1 = floor((max(arr[o], arr[o + 2]) + reach) / ROAD_CELL).toInt()
            val y0 = floor((min(arr[o + 1], arr[o + 3]) - reach) / ROAD_CELL).toInt()
            val y1 = floor((max(arr[o + 1], arr[o + 3]) + reach) / ROAD_CELL).toInt()
            for (x in x0..x1) for (y in y0..y1) grid.getOrPut(cellKey(x, y)) { ArrayList() }.add(s)
        }
        val g = HashMap<Long, IntArray>(grid.size * 2)
        for ((k, v) in grid) g[k] = v.toIntArray()
        return RoadBlock(arr, owner.toIntArray(), roadIdx, g)
    }

    /** Linie alle 5 m neu abgetastet, Hoehe gedaempftes Gelaende, laengs gaussgeglaettet (σ = 8 m). */
    private fun roadProfile(line: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val cnt = line.size / 2
        val cum = DoubleArray(cnt)
        for (i in 1 until cnt) cum[i] = cum[i - 1] + hypot(line[2 * i] - line[2 * i - 2], line[2 * i + 1] - line[2 * i - 1])
        val total = cum[cnt - 1]
        val steps = max(1, ceil(total / 5.0).toInt())
        val pe = DoubleArray(steps + 1)
        val pn = DoubleArray(steps + 1)
        val dist = DoubleArray(steps + 1)
        var k = 0
        for (s in 0..steps) {
            val d = total * s / steps
            while (k < cnt - 2 && cum[k + 1] < d) k++
            val span = cum[k + 1] - cum[k]
            val t = if (span > 0) ((d - cum[k]) / span).coerceIn(0.0, 1.0) else 0.0
            pe[s] = line[2 * k] + (line[2 * k + 2] - line[2 * k]) * t
            pn[s] = line[2 * k + 1] + (line[2 * k + 3] - line[2 * k + 1]) * t
            dist[s] = d
        }
        val h = DoubleArray(steps + 1) { dampedAt(pe[it], pn[it]) }
        val sm = if (steps >= 2) gaussAlong(h, dist, 8.0, false) else h
        return Triple(pe, pn, sm)
    }

    private fun roadCarved(e: Double, n: Double): Double {
        val base = dampedAt(e, n)
        if (map === MapData.EMPTY) return base
        val bx = floor(e / ROAD_BLOCK).toInt()
        val by = floor(n / ROAD_BLOCK).toInt()
        val blk = roadBlock(bx, by)
        if (blk.roadCount == 0) return base
        val list = blk.grid[cellKey(floor(e / ROAD_CELL).toInt(), floor(n / ROAD_CELL).toInt())] ?: return base
        // Naechstes Segment je Strasse.
        var bestRoad = IntArray(0)
        var bestD = DoubleArray(0)
        var bestH = DoubleArray(0)
        var bestHw = DoubleArray(0)
        var used = 0
        for (s in list) {
            val o = s * 7
            val ax = blk.seg[o]
            val ay = blk.seg[o + 1]
            val dx = blk.seg[o + 2] - ax
            val dy = blk.seg[o + 3] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((e - ax) * dx + (n - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(ax + dx * t - e, ay + dy * t - n)
            val hw = blk.seg[o + 6]
            if (d >= hw + ROAD_FLAT_EXTRA_M + ROAD_BLEND_M) continue
            val h = blk.seg[o + 4] + (blk.seg[o + 5] - blk.seg[o + 4]) * t
            val r = blk.road[s]
            var idx = -1
            for (q in 0 until used) if (bestRoad[q] == r) {
                idx = q
                break
            }
            if (idx < 0) {
                if (used == bestRoad.size) {
                    val cap = max(4, used * 2)
                    bestRoad = bestRoad.copyOf(cap); bestD = bestD.copyOf(cap); bestH = bestH.copyOf(cap); bestHw = bestHw.copyOf(cap)
                }
                idx = used++
                bestRoad[idx] = r
                bestD[idx] = Double.MAX_VALUE
            }
            if (d < bestD[idx]) {
                bestD[idx] = d
                bestH[idx] = h
                bestHw[idx] = hw
            }
        }
        if (used == 0) return base
        var exact = 0.0
        var exactCnt = 0
        var sw = 0.0
        var sws = 0.0
        for (q in 0 until used) {
            val x = bestD[q] - bestHw[q] - ROAD_FLAT_EXTRA_M
            if (x <= 0) {
                exact += bestH[q]
                exactCnt++
                continue
            }
            val c = x / ROAD_BLEND_M
            if (c >= 1) continue
            val s = bestH[q] + (base - bestH[q]) * smoothstep(0.0, ROAD_BLEND_M, x)
            val w = 1.0 / (c * c) - 1.0
            sw += w
            sws += w * s
        }
        if (exactCnt > 0) return exact / exactCnt
        return if (sw > 0) sws / sw else base
    }

    companion object {
        /** Der Boden unter der Fahrbahn liegt so tief unter ihrer Oberflaeche. */
        const val TRACK_GROUND_DEPTH_M = 0.25

        /** Ebener Streifen neben der Fahrbahnkante. */
        const val TRACK_SHOULDER_M = 0.5

        /** Hoechster Anstieg im Bankett eines Einschnitts. */
        const val TRACK_BERM_RISE_M = 0.06

        /** Absenkung des Bodens unter Wasserflaechen. */
        const val WATER_GROUND_DEPTH_M = 0.4

        private const val SLOPE_MAX_M = 45.0
        private const val CORNER_BLEND_M = 0.75
        private const val PROMINENCE_M = 3.0
        private const val SEG_CELL = 32.0
        private const val ROAD_BLOCK = 512.0

        /** Strassenbloecke (je 512 m) im Speicher; in der Stadt je Block einige 100 KB. */
        private const val MAX_ROAD_BLOCKS = 192
        private const val ROAD_CELL = 16.0
        private const val ROAD_FLAT_EXTRA_M = 0.3
        private const val ROAD_BLEND_M = 4.0
        private const val ROAD_REACH = 7.0 + ROAD_FLAT_EXTRA_M + ROAD_BLEND_M

        /** Boeschungsbreite fuer einen Hoehenunterschied [delta]. */
        fun slopeWidth(delta: Double): Double {
            val a = abs(delta)
            return max(10.0 + 0.5 * a, 2.25 * a).coerceIn(10.0, SLOPE_MAX_M)
        }

        /** Halbe Breite, mit der eine Strasse der Klasse [cls] eingeebnet (und gezeichnet) wird; `null` = nicht eingeebnet. */
        fun roadHalfWidthM(cls: String): Double? = when (cls) {
            "motorway" -> 7.0
            "trunk" -> 5.5
            "primary" -> 4.5
            "secondary" -> 4.0
            "tertiary" -> 3.25
            "minor" -> 2.75
            "service" -> 1.75
            "track" -> 1.5
            "path" -> 0.75
            "rail", "transit" -> 2.0
            "busway", "raceway" -> 3.0
            else -> null
        }

        private fun cellKey(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    }
}
