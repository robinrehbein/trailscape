package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * # Das Streckenband: die Strecke in die Welt einpassen
 *
 * Aus der [IndoorRoute] (WGS84, Hoehen von BRouter/GPS) wird das
 * [WorldTrack] des Manifests: Proben alle [WorldTrack.DEFAULT_STEP_M] Meter
 * **bei unveraenderten Laufmetern** (docs/indoor.md 4), Lage leicht
 * geglaettet, Hoehe auf dem Gelaende.
 *
 * ## Entscheidung: Hoehen der Route werden nicht verwendet
 * Die Fahrbahn liegt auf dem Gelaende ([RawHeights]); die `eleM` der Route
 * stammen aus einem anderen Hoehenmodell (BRouter: SRTM) oder vom GPS, sind
 * um Meter versetzt und seitlich verschoben. Schon als schwache
 * Zusatzinformation zoegen sie die Fahrbahn vom Gelaende weg — genau dort,
 * wo das Auge es sieht (Einschnitte, Daemme). Bruecken, der einzige Fall, in
 * dem die Route mehr weiss als das Gelaende, kommen ausdruecklich ueber
 * `bridges`. Die `eleM` dienen deshalb nur als Notnagel, wenn das
 * Hoehenmodell an einer Stelle keinen endlichen Wert liefert.
 */

/** Das rohe Hoehenmodell als ENU-`u` (Meter ueber dem Ursprung der Welt). */
internal fun interface RawHeights {
    fun heightAt(e: Double, n: Double): Double
}

/** Hoechste Abweichung der geglaetteten Lage von der Eingabe in Metern. */
internal const val TRACK_MAX_SHIFT_M: Double = 1.2

/** Breite der Hoehenglaettung (Gauss-Sigma in Metern; wirksames Fenster rund 60 m). */
internal const val TRACK_HEIGHT_SIGMA_M: Double = 12.0

/** Steigungsgrenze des Bands mit Sicherheitsabstand zu [IndoorRoute.MAX_GRADE_PCT]. */
internal const val TRACK_MAX_GRADE: Double = 0.245

/**
 * Ursprung der Welt (docs/indoor.md 7.1): Mitte des Umgebungsrechtecks der
 * Strecke, Hoehe = Gelaendehoehe am Streckenstart (Meter ueber dem Meer, aus
 * [heightAtLatLon]); ist sie nicht endlich, die Hoehe des ersten Punkts.
 */
internal fun worldOrigin(route: IndoorRoute, heightAtLatLon: (lat: Double, lon: Double) -> Double): GeoOrigin {
    val pts = route.points
    val lat = (pts.minOf { it.lat } + pts.maxOf { it.lat }) / 2
    val lon = (pts.minOf { it.lon } + pts.maxOf { it.lon }) / 2
    val h = heightAtLatLon(pts[0].lat, pts[0].lon)
    return GeoOrigin(lat, lon, if (h.isFinite()) h else pts[0].eleM)
}

/**
 * Passt [route] in die Welt um [origin] ein.
 *
 *  * Proben bei `min(i · 5 m, lengthM)`; `lengthM = route.lengthM`, die
 *    letzte Probe liegt genau am Ende, auf einer Runde auf der ersten.
 *  * Lage: GPS- und Routing-Zacken werden geglaettet, ohne Kurven zu
 *    schneiden — hoechstens [TRACK_MAX_SHIFT_M] Abweichung. Eine Runde,
 *    deren Ende neben dem Start liegt, wird auf den letzten Metern weich
 *    geschlossen.
 *  * Hoehe: das Gelaende unter der Mittellinie (quer gemittelt ueber ±2 m),
 *    Brueckenstuecke ([bridges]) linear zwischen den Widerlagern, dann
 *    gaussgeglaettet (σ = [TRACK_HEIGHT_SIGMA_M]: keine Treppenstufen des
 *    Hoehenmodells, keine Wellen unter 30 m) und auf ±[TRACK_MAX_GRADE] je
 *    Stueck begrenzt.
 */
internal fun fitTrack(
    route: IndoorRoute,
    origin: GeoOrigin,
    heights: RawHeights,
    bridges: (distM: Double) -> Boolean = { false },
): WorldTrack {
    val length = route.lengthM
    val step = WorldTrack.DEFAULT_STEP_M
    val count = ceil(length / step - 1e-9).toInt().coerceAtLeast(1) + 1
    val dist = DoubleArray(count) { min(it * step, length) }

    // 1. Neu abtasten bei denselben Laufmetern.
    val pts = route.points
    val pe = DoubleArray(pts.size) { origin.toEast(pts[it].lon) }
    val pn = DoubleArray(pts.size) { origin.toNorth(pts[it].lat) }
    val e = DoubleArray(count)
    val n = DoubleArray(count)
    val ele = DoubleArray(count)
    var k = 0
    for (i in 0 until count) {
        val d = dist[i]
        while (k < pts.size - 2 && pts[k + 1].distM <= d) k++
        val a = pts[k]
        val b = pts[min(k + 1, pts.size - 1)]
        val span = b.distM - a.distM
        val t = if (span > 0) ((d - a.distM) / span).coerceIn(0.0, 1.0) else 0.0
        val j = min(k + 1, pts.size - 1)
        e[i] = pe[k] + (pe[j] - pe[k]) * t
        n[i] = pn[k] + (pn[j] - pn[k]) * t
        ele[i] = origin.toUp(a.eleM + (b.eleM - a.eleM) * t)
    }
    val loop = route.isLoop && count > 3
    if (loop) closeLoop(e, n, dist, length)

    // 2. Lage glaetten.
    smoothPlan(e, n, loop)

    // 3. Hoehe vom Gelaende.
    val raw = DoubleArray(count)
    for (i in 0 until count) {
        val (te, tn) = tangent(e, n, i, loop)
        // Quer gemittelt: Mitte doppelt, ±2 m einfach.
        val c = heights.heightAt(e[i], n[i])
        val l = heights.heightAt(e[i] - tn * 2.0, n[i] + te * 2.0)
        val r = heights.heightAt(e[i] + tn * 2.0, n[i] - te * 2.0)
        val vals = doubleArrayOf(c, c, l, r).filter { it.isFinite() }
        raw[i] = if (vals.isEmpty()) Double.NaN else vals.average()
    }
    fillGaps(raw, ele)
    if (loop) raw[count - 1] = raw[0]
    applyBridges(raw, dist, bridges, loop)
    val u = gaussAlong(raw, dist, TRACK_HEIGHT_SIGMA_M, loop)
    limitGrade(u, dist, TRACK_MAX_GRADE, loop)

    if (loop) {
        e[count - 1] = e[0]
        n[count - 1] = n[0]
        u[count - 1] = u[0]
    }
    return WorldTrack(
        lengthM = length,
        stepM = step,
        loop = route.isLoop,
        e = FloatArray(count) { e[it].toFloat() },
        n = FloatArray(count) { n[it].toFloat() },
        u = FloatArray(count) { u[it].toFloat() },
        surfaces = route.surfaces,
        source = route.source,
    )
}

/** Verteilt den Abstand Ende → Start einer Runde weich ueber die letzten Meter. */
private fun closeLoop(e: DoubleArray, n: DoubleArray, dist: DoubleArray, length: Double) {
    val last = e.size - 1
    val ge = e[0] - e[last]
    val gn = n[0] - n[last]
    if (ge == 0.0 && gn == 0.0) return
    val w = min(300.0, length / 3)
    for (i in 0..last) {
        val t = smoothstep(length - w, length, dist[i])
        e[i] += ge * t
        n[i] += gn * t
    }
    e[last] = e[0]
    n[last] = n[0]
}

/**
 * Glaettet die Lage: wiederholt jeden Punkt zum Mittel seiner Nachbarn
 * ziehen (Laplace) und die Verschiebung gegen die Eingabe auf
 * [TRACK_MAX_SHIFT_M] begrenzen. Zacken von wenigen Metern verschwinden,
 * echte Kurven (Radien ab etwa 10 m) bleiben, weil die Begrenzung sie haelt.
 */
private fun smoothPlan(e: DoubleArray, n: DoubleArray, loop: Boolean) {
    val count = e.size
    val e0 = e.copyOf()
    val n0 = n.copyOf()
    val ne = DoubleArray(count)
    val nn = DoubleArray(count)
    val cyc = if (loop) count - 1 else count
    repeat(12) {
        for (i in 0 until cyc) {
            val a: Int
            val b: Int
            if (loop) {
                a = (i - 1 + cyc) % cyc
                b = (i + 1) % cyc
            } else {
                if (i == 0 || i == count - 1) {
                    ne[i] = e[i]
                    nn[i] = n[i]
                    continue
                }
                a = i - 1
                b = i + 1
            }
            var x = 0.5 * e[i] + 0.25 * (e[a] + e[b])
            var y = 0.5 * n[i] + 0.25 * (n[a] + n[b])
            val dx = x - e0[i]
            val dy = y - n0[i]
            val d = hypot(dx, dy)
            if (d > TRACK_MAX_SHIFT_M) {
                x = e0[i] + dx / d * TRACK_MAX_SHIFT_M
                y = n0[i] + dy / d * TRACK_MAX_SHIFT_M
            }
            ne[i] = x
            nn[i] = y
        }
        for (i in 0 until cyc) {
            e[i] = ne[i]
            n[i] = nn[i]
        }
    }
    if (loop) {
        e[count - 1] = e[0]
        n[count - 1] = n[0]
    }
}

/** Einheits-Tangente (Ost, Nord) an Probe [i]. */
private fun tangent(e: DoubleArray, n: DoubleArray, i: Int, loop: Boolean): Pair<Double, Double> {
    val count = e.size
    val cyc = count - 1
    val a = if (i > 0) i - 1 else if (loop) cyc - 1 else 0
    val b = if (i < count - 1) i + 1 else if (loop) 1 else count - 1
    val dx = e[b] - e[a]
    val dy = n[b] - n[a]
    val l = hypot(dx, dy)
    return if (l > 1e-9) (dx / l) to (dy / l) else 0.0 to 1.0
}

/** Ersetzt nicht endliche Hoehen: linear zwischen endlichen Nachbarn, sonst relativ aus [ele]. */
private fun fillGaps(raw: DoubleArray, ele: DoubleArray) {
    val count = raw.size
    if (raw.none { it.isFinite() }) {
        for (i in 0 until count) raw[i] = if (ele[i].isFinite()) ele[i] else 0.0
        return
    }
    var i = 0
    while (i < count) {
        if (raw[i].isFinite()) {
            i++
            continue
        }
        val a = i - 1
        var b = i
        while (b < count && !raw[b].isFinite()) b++
        for (j in i until b) {
            raw[j] = when {
                a < 0 -> raw[b] + (ele[j] - ele[b]).finiteOr(0.0)
                b >= count -> raw[a] + (ele[j] - ele[a]).finiteOr(0.0)
                else -> raw[a] + (raw[b] - raw[a]) * (j - a).toDouble() / (b - a)
            }
        }
        i = b
    }
}

private fun Double.finiteOr(v: Double) = if (isFinite()) this else v

/** Brueckenstuecke: Hoehe linear zwischen dem letzten und naechsten Nicht-Brueckenpunkt. */
private fun applyBridges(raw: DoubleArray, dist: DoubleArray, bridges: (Double) -> Boolean, loop: Boolean) {
    val count = raw.size
    val on = BooleanArray(count) { bridges(dist[it]) }
    if (on.all { it } || on.none { it }) return
    if (loop) {
        on[count - 1] = on[0]
    }
    var i = 0
    while (i < count) {
        if (!on[i]) {
            i++
            continue
        }
        var b = i
        while (b < count && on[b]) b++
        val a = i - 1
        when {
            a >= 0 && b < count -> for (j in i until b) raw[j] = raw[a] + (raw[b] - raw[a]) * (dist[j] - dist[a]) / (dist[b] - dist[a])
            a >= 0 -> for (j in i until b) raw[j] = raw[a]
            b < count -> for (j in i until b) raw[j] = raw[b]
        }
        i = b
    }
}

/**
 * Gausssche Glaettung entlang der Laufmeter (Fenster ±3σ). Auf einer Runde
 * umlaufend; sonst wird am Rand renormiert und das Ende linear fortgesetzt,
 * damit Start und Ziel nicht zur Mitte hin gezogen werden.
 */
internal fun gaussAlong(v: DoubleArray, dist: DoubleArray, sigma: Double, loop: Boolean): DoubleArray {
    val count = v.size
    if (count < 3) return v.copyOf()
    val step = if (count > 1) dist[1] - dist[0] else 1.0
    val r = max(1, ceil(3 * sigma / step).toInt())
    val w = DoubleArray(r + 1) { exp(-0.5 * (it * step / sigma) * (it * step / sigma)) }
    val out = DoubleArray(count)
    val cyc = count - 1
    for (i in 0 until count) {
        if (loop && i == count - 1) {
            out[i] = out[0]
            continue
        }
        var s = 0.0
        var ws = 0.0
        for (o in -r..r) {
            val j = i + o
            val x = when {
                loop -> v[Math.floorMod(j, cyc)]
                // Punktgespiegelt fortsetzen: erhaelt die Randsteigung.
                j < 0 -> 2 * v[0] - v[min(-j, count - 1)]
                j > count - 1 -> 2 * v[count - 1] - v[max(2 * (count - 1) - j, 0)]
                else -> v[j]
            }
            val wt = w[abs(o)]
            s += x * wt
            ws += wt
        }
        out[i] = s / ws
    }
    return out
}

/**
 * Begrenzt die Steigung je Stueck auf ±[g]: Mittel aus der groessten
 * g-Lipschitz-Funktion unter dem Profil und der kleinsten darueber (beide in
 * zwei Durchlaeufen je Richtung, auf einer Runde ueber zwei Umlaeufe). Das
 * Mittel zweier Funktionen mit Steigung ≤ g hat selbst Steigung ≤ g und
 * weicht nur dort ab, wo das Profil zu steil ist; die Knicke an den
 * Uebergaengen werden leicht geglaettet und die Grenze danach erneut gesichert.
 */
internal fun limitGrade(u: DoubleArray, dist: DoubleArray, g: Double, loop: Boolean) {
    if (u.size < 2) return
    envelope(u, dist, g, loop)
    val sm = gaussAlong(u, dist, 6.0, loop)
    System.arraycopy(sm, 0, u, 0, u.size)
    envelope(u, dist, g, loop)
}

private fun envelope(u: DoubleArray, dist: DoubleArray, g: Double, loop: Boolean) {
    val count = u.size
    val n = if (loop) count - 1 else count
    if (n < 2) return
    // Laenge des Stuecks von i nach i+1 (auf einer Runde schliesst n−1 → 0).
    fun len(i: Int): Double = max(1e-6, dist[i + 1] - dist[i])
    val lo = DoubleArray(n) { u[it] }
    val hi = DoubleArray(n) { u[it] }
    val laps = if (loop) 2 else 1
    repeat(laps) {
        for (k in 1..(if (loop) n else n - 1)) {
            val i = k % n
            val p = k - 1
            val l = len(p)
            lo[i] = min(lo[i], lo[p] + g * l)
            hi[i] = max(hi[i], hi[p] - g * l)
        }
    }
    repeat(laps) {
        for (k in (if (loop) n else n - 1) downTo 1) {
            val i = k - 1
            val q = k % n
            val l = len(i)
            lo[i] = min(lo[i], lo[q] + g * l)
            hi[i] = max(hi[i], hi[q] - g * l)
        }
    }
    for (i in 0 until n) u[i] = (lo[i] + hi[i]) / 2
    if (loop) u[count - 1] = u[0]
}
