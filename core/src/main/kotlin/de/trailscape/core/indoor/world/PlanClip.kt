package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Sperrflaechen im Grundriss
 *
 * Konvexe Vielecke in (e, n) — die Baender der uebrigen Strassen und anderer Streckenteile —, gegen
 * die die Randgestaltung der Strecke (Gehweg, Bordstein, Bankett, Fransen, Uebergangsstreifen,
 * Randlinien) geschnitten wird ([clipInto]).
 *
 * Vorher wurden diese Raender an Einmuendungen per Vertex-Deckung ausgeblendet: je Station, also in
 * Stufen von 1–3 m. Das gab gezackte Graskeile auf der Fahrbahn der Nebenstrasse, und die
 * durchsichtigen Eckpunkte erzeugten auf dem Geraet helle Nahtlinien (siehe
 * [RouteTextures.roadEdgeTexture]). Jetzt endet der Rand genau an der Kante der Strasse.
 *
 * Jedes Vieleck traegt einen Laufmeter-Bereich der Strecke ([fromM]..[toM]); bei der Abfrage
 * ([candidates]) lassen sich Vielecke ausschliessen, die zum selben Streckenstueck gehoeren (fuer
 * die Sperrflaechen anderer Streckenteile). Strassen haben einen leeren Bereich.
 */
internal class PlanMask {
    class Poly(val pts: DoubleArray, val fromM: Double, val toM: Double) {
        var minE = Double.MAX_VALUE
        var minN = Double.MAX_VALUE
        var maxE = -Double.MAX_VALUE
        var maxN = -Double.MAX_VALUE

        init {
            for (i in 0 until pts.size / 2) {
                minE = min(minE, pts[2 * i]); maxE = max(maxE, pts[2 * i])
                minN = min(minN, pts[2 * i + 1]); maxN = max(maxN, pts[2 * i + 1])
            }
        }
    }

    private val polys = ArrayList<Poly>()
    private val grid = HashMap<Long, ArrayList<Poly>>()

    val isEmpty: Boolean get() = polys.isEmpty()
    val size: Int get() = polys.size

    /**
     * Fuegt ein konvexes Vieleck hinzu (Eckpunkte in beliebigem Umlaufsinn, werden gegen den
     * Uhrzeigersinn gelegt). Entartete Vielecke (Flaeche < 1 cm²) entfallen.
     */
    fun add(pts: DoubleArray, fromM: Double = 0.0, toM: Double = -1.0) {
        val k = pts.size / 2
        if (k < 3) return
        var area2 = 0.0
        for (i in 0 until k) {
            val j = (i + 1) % k
            area2 += pts[2 * i] * pts[2 * j + 1] - pts[2 * j] * pts[2 * i + 1]
        }
        if (abs(area2) < 2e-4) return
        val ccw = if (area2 > 0) pts else DoubleArray(pts.size).also { r ->
            for (i in 0 until k) { r[2 * i] = pts[2 * (k - 1 - i)]; r[2 * i + 1] = pts[2 * (k - 1 - i) + 1] }
        }
        val p = Poly(ccw, fromM, toM)
        polys.add(p)
        val i0 = floor(p.minE / CELL_M).toInt()
        val i1 = floor(p.maxE / CELL_M).toInt()
        val j0 = floor(p.minN / CELL_M).toInt()
        val j1 = floor(p.maxN / CELL_M).toInt()
        for (i in i0..i1) for (j in j0..j1) grid.getOrPut(key(i, j)) { ArrayList() }.add(p)
    }

    /** Ein Viereck aus zwei Querschnitten (links/rechts je Station), z. B. ein Strassensegment. */
    fun addQuad(aLe: Double, aLn: Double, aRe: Double, aRn: Double, bLe: Double, bLn: Double, bRe: Double, bRn: Double, fromM: Double = 0.0, toM: Double = -1.0) {
        add(doubleArrayOf(aLe, aLn, bLe, bLn, bRe, bRn, aRe, aRn), fromM, toM)
    }

    /** Vielecke, deren Huelle das Rechteck beruehrt; [skipFrom]..[skipTo] = Laufmeter, die nicht sperren. */
    fun candidates(minE: Double, minN: Double, maxE: Double, maxN: Double, skipFrom: Double = 1.0, skipTo: Double = -1.0, out: ArrayList<Poly>) {
        out.clear()
        if (polys.isEmpty()) return
        val i0 = floor(minE / CELL_M).toInt()
        val i1 = floor(maxE / CELL_M).toInt()
        val j0 = floor(minN / CELL_M).toInt()
        val j1 = floor(maxN / CELL_M).toInt()
        for (i in i0..i1) for (j in j0..j1) {
            val list = grid[key(i, j)] ?: continue
            for (p in list) {
                if (p.maxE < minE || p.minE > maxE || p.maxN < minN || p.minN > maxN) continue
                if (p.toM >= p.fromM && p.toM >= skipFrom && p.fromM <= skipTo) continue
                if (p !in out) out.add(p)
            }
        }
    }

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)

    companion object {
        const val CELL_M = 16.0
    }
}

/**
 * Schneidet alle Dreiecke von [src] gegen [mask] und haengt, was ausserhalb aller Sperrflaechen
 * liegt, an [dst] an — Lage, Normale, UV und Farbe linear im Dreieck interpoliert (also genau so,
 * wie die Grafikkarte das ungeschnittene Dreieck gezeichnet haette). [toE]/[toN] rechnen die
 * Netzkoordinaten (x, z) in den Grundriss um. [skipFrom]..[skipTo]: siehe [PlanMask.candidates].
 */
internal fun clipInto(
    src: MeshBuilder,
    dst: MeshBuilder,
    mask: PlanMask,
    toE: (Double) -> Double,
    toN: (Double) -> Double,
    skipFrom: Double = 1.0,
    skipTo: Double = -1.0,
) {
    if (src.isEmpty) return
    val cand = ArrayList<PlanMask.Poly>()
    val idx = src.indices
    val tri = src.triangleCount
    val map = IntArray(src.vertexCount) { -1 }
    fun copy(v: Int): Int {
        if (map[v] >= 0) return map[v]
        val p = v * 3
        val i = dst.vertex(
            src.positions[p].toDouble(), src.positions[p + 1].toDouble(), src.positions[p + 2].toDouble(),
            src.normals[p].toDouble(), src.normals[p + 1].toDouble(), src.normals[p + 2].toDouble(),
            src.uvs[v * 2].toDouble(), src.uvs[v * 2 + 1].toDouble(), src.colors[v],
        )
        map[v] = i
        return i
    }
    for (t in 0 until tri) {
        val a = idx[3 * t]
        val b = idx[3 * t + 1]
        val c = idx[3 * t + 2]
        val ae = toE(src.x(a).toDouble()); val an = toN(src.z(a).toDouble())
        val be = toE(src.x(b).toDouble()); val bn = toN(src.z(b).toDouble())
        val ce = toE(src.x(c).toDouble()); val cn = toN(src.z(c).toDouble())
        mask.candidates(min(ae, min(be, ce)), min(an, min(bn, cn)), max(ae, max(be, ce)), max(an, max(bn, cn)), skipFrom, skipTo, cand)
        if (cand.isEmpty()) {
            dst.tri(copy(a), copy(b), copy(c))
            continue
        }
        // Teilstuecke als Vielecke in baryzentrischen Koordinaten (wa, wb) — wc = 1 − wa − wb.
        var pieces = listOf(Piece(doubleArrayOf(ae, be, ce), doubleArrayOf(an, bn, cn), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0)))
        for (p in cand) {
            val next = ArrayList<Piece>()
            for (piece in pieces) piece.subtract(p, next)
            pieces = next
            if (pieces.isEmpty()) break
        }
        if (pieces.size == 1 && pieces[0].isOriginal()) {
            dst.tri(copy(a), copy(b), copy(c))
            continue
        }
        for (piece in pieces) {
            if (piece.area() < 1e-4) continue
            val first = dst.vertexCount
            for (k in 0 until piece.size) emitInterpolated(src, dst, a, b, c, piece.wa[k], piece.wb[k])
            for (k in 1 until piece.size - 1) dst.tri(first, first + k, first + k + 1)
        }
    }
}

private fun emitInterpolated(src: MeshBuilder, dst: MeshBuilder, a: Int, b: Int, c: Int, wa: Double, wb: Double) {
    val wc = 1.0 - wa - wb
    fun f3(arr: FloatArray, k: Int) = arr[a * 3 + k] * wa + arr[b * 3 + k] * wb + arr[c * 3 + k] * wc
    fun f2(arr: FloatArray, k: Int) = arr[a * 2 + k] * wa + arr[b * 2 + k] * wb + arr[c * 2 + k] * wc
    var nx = f3(src.normals, 0)
    var ny = f3(src.normals, 1)
    var nz = f3(src.normals, 2)
    val len = sqrt(nx * nx + ny * ny + nz * nz)
    if (len > 1e-9) { nx /= len; ny /= len; nz /= len } else { nx = 0.0; ny = 1.0; nz = 0.0 }
    val ca = src.colors[a]
    val cb = src.colors[b]
    val cc = src.colors[c]
    fun ch(sh: Int) = (((ca ushr sh) and 0xFF) * wa + ((cb ushr sh) and 0xFF) * wb + ((cc ushr sh) and 0xFF) * wc + 0.5).toInt().coerceIn(0, 255)
    val col = (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    dst.vertex(f3(src.positions, 0), f3(src.positions, 1), f3(src.positions, 2), nx, ny, nz, f2(src.uvs, 0), f2(src.uvs, 1), col)
}

/** Ein konvexes Teilstueck eines Dreiecks: Grundriss (e, n) und baryzentrische Gewichte je Ecke. */
private class Piece(val e: DoubleArray, val n: DoubleArray, val wa: DoubleArray, val wb: DoubleArray) {
    val size: Int get() = e.size

    fun isOriginal(): Boolean = size == 3 && wa[0] == 1.0 && wb[1] == 1.0 && wa[1] == 0.0 && wa[2] == 0.0 && wb[0] == 0.0 && wb[2] == 0.0

    fun area(): Double {
        var s = 0.0
        for (i in 0 until size) {
            val j = (i + 1) % size
            s += e[i] * n[j] - e[j] * n[i]
        }
        return abs(s) / 2
    }

    /** Haengt die Teile dieses Stuecks ausserhalb des konvexen [p] an [out] an. */
    fun subtract(p: PlanMask.Poly, out: ArrayList<Piece>) {
        var minE = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE; var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (i in 0 until size) { minE = min(minE, e[i]); maxE = max(maxE, e[i]); minN = min(minN, n[i]); maxN = max(maxN, n[i]) }
        if (maxE <= p.minE || minE >= p.maxE || maxN <= p.minN || minN >= p.maxN) { out.add(this); return }
        var rest: Piece? = this
        val k = p.pts.size / 2
        for (i in 0 until k) {
            val r = rest ?: break
            val x0 = p.pts[2 * i]; val y0 = p.pts[2 * i + 1]
            val x1 = p.pts[2 * ((i + 1) % k)]; val y1 = p.pts[2 * ((i + 1) % k) + 1]
            // Gegen den Uhrzeigersinn: innen liegt links der Kante (Kreuzprodukt > 0).
            val dx = x1 - x0; val dy = y1 - y0
            val s = DoubleArray(r.size) { j -> dx * (r.n[j] - y0) - dy * (r.e[j] - x0) }
            val outside = r.split(s, keepPositive = false)
            if (outside != null) out.add(outside)
            rest = r.split(s, keepPositive = true)
        }
        // Was nach allen Kanten uebrig bleibt, liegt innen und entfaellt.
    }

    /** Der Teil mit `s ≥ 0` ([keepPositive]) bzw. `s < 0`; `null`, wenn leer; `this`, wenn ganz. */
    private fun split(s: DoubleArray, keepPositive: Boolean): Piece? {
        fun inside(v: Double) = if (keepPositive) v > EPS else v < -EPS
        var all = true
        var none = true
        for (v in s) { if (inside(v)) none = false else all = false }
        if (all) return this
        if (none) return null
        val oe = ArrayList<Double>(); val on = ArrayList<Double>(); val oa = ArrayList<Double>(); val ob = ArrayList<Double>()
        for (i in 0 until size) {
            val j = (i + 1) % size
            val si = s[i]; val sj = s[j]
            if (inside(si)) { oe.add(e[i]); on.add(n[i]); oa.add(wa[i]); ob.add(wb[i]) }
            if (inside(si) != inside(sj)) {
                val t = (si / (si - sj)).coerceIn(0.0, 1.0)
                oe.add(e[i] + (e[j] - e[i]) * t); on.add(n[i] + (n[j] - n[i]) * t)
                oa.add(wa[i] + (wa[j] - wa[i]) * t); ob.add(wb[i] + (wb[j] - wb[i]) * t)
            }
        }
        if (oe.size < 3) return null
        return Piece(oe.toDoubleArray(), on.toDoubleArray(), oa.toDoubleArray(), ob.toDoubleArray())
    }

    companion object {
        const val EPS = 1e-9
    }
}
