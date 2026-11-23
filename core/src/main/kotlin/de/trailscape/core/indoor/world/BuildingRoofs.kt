package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Grundriss und Dachform
 *
 * Die Grundrisse aus den Vektorkacheln sind auf rund 0,4 m gerastert: Rechtecke
 * sind leicht schief, gerade Waende haben kleine Versaetze. [FootprintShape]
 * richtet einen Grundriss deshalb an seiner Hauptachse aus und macht ihn
 * **rechtwinklig** (alle Kanten laengs oder quer), solange er dabei nicht
 * weiter als etwa einen Meter vom Original abweicht.
 *
 * Ein Dach besteht aus **Fluegeln** ([RoofWing]): Rechtecken des Grundrisses
 * mit je eigenem, in sich geschlossenem Dach. Die Daecher durchdringen einander
 * und die Tiefenpruefung der Grafikkarte bildet die Vereinigung — Kehlen, Grate
 * und Verschneidungen der Firste entstehen von selbst, ohne dass je ein Loch
 * oder eine nach innen gestuelpte Flaeche entstehen kann. [RoofWings] kennt
 * zwei Zerlegungen:
 *
 *  * [RoofWings.mainAndAnnexes] — der Normalfall, so wie solche Haeuser gebaut
 *    sind: das groesste Rechteck als Hauptbau mit Satteldach von Giebel zu
 *    Giebel, der Rest als Querfluegel, Verlaengerung oder niedriger Anbau.
 *  * [RoofWings.decompose] — fuer alles Verwinkelte (Innenhof, Reste, die keine
 *    Rechtecke sind): Fuer rechtwinklige Grundrisse ist das gleich geneigte
 *    Walmdach genau die Abstandsfunktion zum Rand in der Maximumsnorm, und die
 *    ist die Huelle der Walmdaecher aller **groessten einbeschriebenen
 *    Rechtecke**. Wo ein solches Rechteck frei endet (Stirnseite und beide
 *    Laengsseiten am Rand), wird aus dem Walm ein Giebel.
 *
 * Grundrisse, die sich nicht ausrichten lassen, bekommen ein Satteldach ueber
 * ihr Huellrechteck ([RoofWing.loose]) oder ein Flachdach.
 */

/** Ein ausgerichteter, rechtwinkliger Grundriss im Gebaeudesystem (x laengs der Hauptachse, y quer). */
internal class SquaredFootprint(
    /** Aussenring gegen den Uhrzeigersinn, Loecher im Uhrzeigersinn; je Ring `x0, y0, x1, y1, …`. */
    val rings: List<DoubleArray>,
)

internal object FootprintShape {
    /** Schwerpunkt und Flaeche eines Rings: `[e, n, |Flaeche|]`; bei entarteter Flaeche das Mittel der Punkte. */
    fun centroid(ring: DoubleArray): DoubleArray {
        val n = ring.size / 2
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        // Um den ersten Punkt rechnen: stabil auch weit weg vom Ursprung.
        val ox = ring[0]
        val oy = ring[1]
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = ring[2 * i] - ox
            val y0 = ring[2 * i + 1] - oy
            val x1 = ring[2 * j] - ox
            val y1 = ring[2 * j + 1] - oy
            val c = x0 * y1 - x1 * y0
            a += c
            cx += (x0 + x1) * c
            cy += (y0 + y1) * c
        }
        if (abs(a) < 1e-9) {
            var sx = 0.0
            var sy = 0.0
            for (i in 0 until n) {
                sx += ring[2 * i]
                sy += ring[2 * i + 1]
            }
            return doubleArrayOf(sx / n, sy / n, 0.0)
        }
        return doubleArrayOf(ox + cx / (3 * a), oy + cy / (3 * a), abs(a) / 2)
    }

    /** Ring ohne doppelte Punkte und ohne Punkte auf gerader Strecke; `null`, wenn weniger als drei bleiben. */
    fun clean(ring: DoubleArray, eps: Double = 0.05): DoubleArray? {
        if (!ring.all { it.isFinite() }) return null
        val pts = ArrayList<DoubleArray>()
        for (i in 0 until ring.size / 2) {
            val p = doubleArrayOf(ring[2 * i], ring[2 * i + 1])
            if (pts.isEmpty() || hypot(pts.last()[0] - p[0], pts.last()[1] - p[1]) > eps) pts.add(p)
        }
        while (pts.size > 1 && hypot(pts.last()[0] - pts[0][0], pts.last()[1] - pts[0][1]) <= eps) pts.removeAt(pts.size - 1)
        var changed = true
        while (changed && pts.size >= 3) {
            changed = false
            var i = 0
            while (i < pts.size && pts.size >= 3) {
                val a = pts[(i + pts.size - 1) % pts.size]
                val b = pts[i]
                val c = pts[(i + 1) % pts.size]
                val cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0])
                val len = hypot(c[0] - a[0], c[1] - a[1])
                // Abstand von b zur Sehne a–c unter 2 cm: der Punkt traegt nichts.
                if (len < eps || abs(cross) / len < 0.02) {
                    pts.removeAt(i)
                    changed = true
                } else {
                    i++
                }
            }
        }
        if (pts.size < 3) return null
        return DoubleArray(pts.size * 2) { pts[it / 2][it % 2] }
    }

    /** Hauptrichtung der Kanten (Bogenmass, modulo 90°): laengengewichtetes Mittel der Kantenrichtungen. */
    fun dominantAngle(ring: DoubleArray): Double {
        val n = ring.size / 2
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = ring[2 * j] - ring[2 * i]
            val dy = ring[2 * j + 1] - ring[2 * i + 1]
            val len = hypot(dx, dy)
            if (len < 1e-9) continue
            val a = atan2(dy, dx)
            // Lange Kanten zaehlen ueberproportional: Sie tragen die Richtung, kurze nur Rauschen.
            sx += len * len * cos(4 * a)
            sy += len * len * sin(4 * a)
        }
        return atan2(sy, sx) / 4
    }

    /** Dreht [ring] um (`ox`, `oy`) ins Gebaeudesystem des Winkels [angle]. */
    fun toFrame(ring: DoubleArray, ox: Double, oy: Double, angle: Double): DoubleArray {
        val c = cos(angle)
        val s = sin(angle)
        val out = DoubleArray(ring.size)
        for (i in 0 until ring.size / 2) {
            val dx = ring[2 * i] - ox
            val dy = ring[2 * i + 1] - oy
            out[2 * i] = dx * c + dy * s
            out[2 * i + 1] = -dx * s + dy * c
        }
        return out
    }

    fun reversed(ring: DoubleArray): DoubleArray {
        val n = ring.size / 2
        return DoubleArray(ring.size) { ring[2 * (n - 1 - it / 2) + it % 2] }
    }

    /** `true`, wenn sich keine zwei nicht benachbarten Kanten des Rings schneiden oder beruehren. */
    fun isSimple(ring: DoubleArray): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        if (n > 200) return false
        for (i in 0 until n) {
            val i2 = (i + 1) % n
            for (j in i + 1 until n) {
                val j2 = (j + 1) % n
                if (i == j || i2 == j || j2 == i) continue
                if (segmentsTouch(
                        ring[2 * i], ring[2 * i + 1], ring[2 * i2], ring[2 * i2 + 1],
                        ring[2 * j], ring[2 * j + 1], ring[2 * j2], ring[2 * j2 + 1],
                    )
                ) {
                    return false
                }
            }
        }
        return true
    }

    private fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSegment(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Boolean =
        px >= min(ax, bx) - 1e-9 && px <= max(ax, bx) + 1e-9 && py >= min(ay, by) - 1e-9 && py <= max(ay, by) + 1e-9

    fun segmentsTouch(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double,
    ): Boolean {
        val d1 = orient(cx, cy, dx, dy, ax, ay)
        val d2 = orient(cx, cy, dx, dy, bx, by)
        val d3 = orient(ax, ay, bx, by, cx, cy)
        val d4 = orient(ax, ay, bx, by, dx, dy)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        val eps = 1e-9
        if (abs(d1) <= eps && onSegment(cx, cy, dx, dy, ax, ay)) return true
        if (abs(d2) <= eps && onSegment(cx, cy, dx, dy, bx, by)) return true
        if (abs(d3) <= eps && onSegment(ax, ay, bx, by, cx, cy)) return true
        if (abs(d4) <= eps && onSegment(ax, ay, bx, by, dx, dy)) return true
        return false
    }

    /** Konvexe Huelle (gegen den Uhrzeigersinn) der Punkte eines Rings. */
    fun convexHull(ring: DoubleArray): DoubleArray {
        val pts = (0 until ring.size / 2).map { doubleArrayOf(ring[2 * it], ring[2 * it + 1]) }
            .sortedWith(compareBy({ it[0] }, { it[1] }))
        if (pts.size < 3) return ring
        val hull = ArrayList<DoubleArray>()
        for (pass in 0..1) {
            val start = hull.size
            val seq = if (pass == 0) pts else pts.asReversed()
            for (p in seq) {
                while (hull.size >= start + 2) {
                    val a = hull[hull.size - 2]
                    val b = hull[hull.size - 1]
                    if (orient(a[0], a[1], b[0], b[1], p[0], p[1]) <= 1e-12) hull.removeAt(hull.size - 1) else break
                }
                hull.add(p)
            }
            hull.removeAt(hull.size - 1)
        }
        return DoubleArray(hull.size * 2) { hull[it / 2][it % 2] }
    }

    /** Abstand eines Punkts zum Rand eines Rings. */
    fun distanceToRing(ring: DoubleArray, x: Double, y: Double): Double {
        val n = ring.size / 2
        var best = Double.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ax = ring[2 * i]
            val ay = ring[2 * i + 1]
            val dx = ring[2 * j] - ax
            val dy = ring[2 * j + 1] - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            best = min(best, hypot(ax + dx * t - x, ay + dy * t - y))
        }
        return best
    }

    /**
     * Macht einen Ring im Gebaeudesystem rechtwinklig: Jede Kante wird der
     * naeheren Achse zugeordnet, aufeinanderfolgende Kanten gleicher Richtung
     * verschmelzen zu einer Geraden, Versaetze unter [minStep] werden
     * ausgeglichen und schmale Nasen entfernt. `null`, wenn der Ring nicht
     * rechtwinklig genug ist oder das Ergebnis weiter als [maxDev] abweicht.
     */
    fun squareRing(ring: DoubleArray, maxDev: Double, minStep: Double = 0.7): DoubleArray? {
        val n = ring.size / 2
        if (n < 4) return null
        // 0 = laengs (x), 1 = quer (y)
        val type = IntArray(n)
        val len = DoubleArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = ring[2 * j] - ring[2 * i]
            val dy = ring[2 * j + 1] - ring[2 * i + 1]
            len[i] = hypot(dx, dy)
            type[i] = if (abs(dx) >= abs(dy)) 0 else 1
            val dev = atan2(min(abs(dx), abs(dy)), max(abs(dx), abs(dy)))
            if (len[i] > 2.5 && dev > Math.toRadians(20.0)) return null
        }
        var start = -1
        for (i in 0 until n) if (type[i] != type[(i + n - 1) % n]) {
            start = i
            break
        }
        if (start < 0) return null
        // Gruppen gleicher Richtung → je eine Gerade (laengengewichtete Lage).
        val gType = ArrayList<Int>()
        val gCoord = ArrayList<Double>()
        var k = 0
        while (k < n) {
            val i0 = (start + k) % n
            val t = type[i0]
            var sum = 0.0
            var w = 0.0
            var m = 0
            while (k + m < n && type[(start + k + m) % n] == t) {
                val i = (start + k + m) % n
                val j = (i + 1) % n
                val mid = if (t == 0) (ring[2 * i + 1] + ring[2 * j + 1]) / 2 else (ring[2 * i] + ring[2 * j]) / 2
                val wt = len[i] + 1e-6
                sum += mid * wt
                w += wt
                m++
            }
            gType.add(t)
            gCoord.add(sum / w)
            k += m
        }
        val g = gType.size
        if (g < 4 || g % 2 != 0) return null
        val pts = ArrayList<DoubleArray>(g)
        for (i in 0 until g) {
            val p = (i + g - 1) % g
            // Eckpunkt zwischen Gerade p und Gerade i.
            pts.add(if (gType[i] == 0) doubleArrayOf(gCoord[p], gCoord[i]) else doubleArrayOf(gCoord[i], gCoord[p]))
        }
        if (!tidyRectilinear(pts)) return null
        // Kurze Kanten ausgleichen, bis keine mehr unter der Schwelle liegt.
        var guard = 4 * g + 8
        while (guard-- > 0) {
            val m = pts.size
            var best = -1
            var bestLen = minStep
            for (i in 0 until m) {
                val a = pts[i]
                val b = pts[(i + 1) % m]
                val l = abs(a[0] - b[0]) + abs(a[1] - b[1])
                if (l < bestLen) {
                    bestLen = l
                    best = i
                }
            }
            if (best < 0) break
            if (m <= 4) return null
            val vp = pts[(best + m - 1) % m]
            val va = pts[best]
            val vb = pts[(best + 1) % m]
            val vn = pts[(best + 2) % m]
            val pdx = va[0] - vp[0]
            val pdy = va[1] - vp[1]
            val ndx = vn[0] - vb[0]
            val ndy = vn[1] - vb[1]
            val lp = abs(pdx) + abs(pdy)
            val ln = abs(ndx) + abs(ndy)
            if (pdx * ndx + pdy * ndy > 0) {
                // Versatz (Z-Form): beide Nachbarn auf eine gemeinsame Gerade legen.
                if (abs(va[0] - vb[0]) > abs(va[1] - vb[1])) {
                    // kurze Kante laengs → Nachbarn quer, gemeinsames x
                    val x = (va[0] * lp + vb[0] * ln) / (lp + ln)
                    vp[0] = x
                    vn[0] = x
                } else {
                    val y = (va[1] * lp + vb[1] * ln) / (lp + ln)
                    vp[1] = y
                    vn[1] = y
                }
                va[0] = Double.NaN
                vb[0] = Double.NaN
            } else if (lp <= ln) {
                // Nase oder Kerbe (U-Form): um die kuerzere Flanke zuruecknehmen.
                vb[0] -= pdx
                vb[1] -= pdy
                va[0] = Double.NaN
            } else {
                va[0] += ndx
                va[1] += ndy
                vb[0] = Double.NaN
            }
            pts.removeAll { it[0].isNaN() }
            if (!tidyRectilinear(pts)) return null
        }
        if (guard <= 0) return null
        // Fast gleiche Fluchten zusammenlegen (zwei Wandstuecke, die bis auf das Rauschen der Daten in einer Linie liegen).
        snapCoordinates(pts, 0)
        snapCoordinates(pts, 1)
        if (!tidyRectilinear(pts)) return null
        val out = DoubleArray(pts.size * 2) { pts[it / 2][it % 2] }
        if (!isSimple(out)) return null
        val a0 = abs(ringArea(ring))
        val a1 = abs(ringArea(out))
        if (a0 < 1e-6 || a1 / a0 < 0.80 || a1 / a0 > 1.25) return null
        for (i in 0 until n) if (distanceToRing(out, ring[2 * i], ring[2 * i + 1]) > maxDev) return null
        for (i in 0 until out.size / 2) if (distanceToRing(ring, out[2 * i], out[2 * i + 1]) > maxDev * 1.3) return null
        return if (ringArea(out) > 0) out else reversed(out)
    }

    /**
     * Legt Koordinaten der Achse [axis], die weniger als 0,35 m auseinander
     * liegen, auf ihren (nach Kantenlaenge gewichteten) Mittelwert.
     */
    private fun snapCoordinates(pts: ArrayList<DoubleArray>, axis: Int) {
        val m = pts.size
        // Gewicht einer Koordinate: Laenge der Kanten, die auf ihr liegen (also quer zur Achse verlaufen).
        val weight = HashMap<Double, Double>()
        for (i in 0 until m) {
            val a = pts[i]
            val b = pts[(i + 1) % m]
            if (abs(a[axis] - b[axis]) < 1e-9) weight[a[axis]] = (weight[a[axis]] ?: 0.0) + abs(a[1 - axis] - b[1 - axis]) + 1e-6
        }
        val values = pts.map { it[axis] }.distinct().sorted()
        val target = HashMap<Double, Double>()
        var i = 0
        while (i < values.size) {
            var j = i
            while (j + 1 < values.size && values[j + 1] - values[i] < 0.35) j++
            var sum = 0.0
            var w = 0.0
            for (k in i..j) {
                val wt = weight[values[k]] ?: 1e-6
                sum += values[k] * wt
                w += wt
            }
            for (k in i..j) target[values[k]] = sum / w
            i = j + 1
        }
        for (p in pts) p[axis] = target[p[axis]] ?: p[axis]
    }

    /** Entfernt doppelte Punkte und Punkte auf gerader Strecke; `false`, wenn weniger als vier Ecken bleiben. */
    private fun tidyRectilinear(pts: ArrayList<DoubleArray>): Boolean {
        var changed = true
        while (changed) {
            changed = false
            var i = 0
            while (i < pts.size && pts.size >= 3) {
                val m = pts.size
                val a = pts[(i + m - 1) % m]
                val b = pts[i]
                val c = pts[(i + 1) % m]
                val dup = abs(a[0] - b[0]) < 1e-7 && abs(a[1] - b[1]) < 1e-7
                val straight = (abs(a[0] - b[0]) < 1e-7 && abs(b[0] - c[0]) < 1e-7) || (abs(a[1] - b[1]) < 1e-7 && abs(b[1] - c[1]) < 1e-7)
                if (dup || straight) {
                    pts.removeAt(i)
                    changed = true
                } else {
                    i++
                }
            }
        }
        return pts.size >= 4
    }

    /**
     * Richtet [rings] (im Gebaeudesystem) rechtwinklig aus. Loecher, die sich
     * nicht ausrichten lassen oder den Rand beruehren, lassen das Ganze
     * scheitern (`null`); winzige Loecher verschwinden.
     */
    fun square(rings: List<DoubleArray>): SquaredFootprint? {
        val outerIn = rings.firstOrNull() ?: return null
        val outer = squareRing(outerIn, if (outerIn.size == 8) 1.6 else 1.0) ?: return null
        val out = ArrayList<DoubleArray>()
        out.add(outer)
        for (h in rings.drop(1)) {
            if (abs(ringArea(h)) < 6.0) continue
            val sq = squareRing(h, 1.0) ?: return null
            // Loch muss ganz im Aussenring liegen und Abstand zum Rand halten.
            for (i in 0 until sq.size / 2) {
                if (!pointInRings(listOf(outer), sq[2 * i], sq[2 * i + 1])) return null
                if (distanceToRing(outer, sq[2 * i], sq[2 * i + 1]) < 1.0) return null
            }
            for (o in out.drop(1)) {
                for (i in 0 until sq.size / 2) if (pointInRings(listOf(o), sq[2 * i], sq[2 * i + 1]) || distanceToRing(o, sq[2 * i], sq[2 * i + 1]) < 1.0) return null
                for (i in 0 until o.size / 2) if (pointInRings(listOf(sq), o[2 * i], o[2 * i + 1])) return null
            }
            out.add(if (ringArea(sq) < 0) sq else reversed(sq))
        }
        return SquaredFootprint(out)
    }
}

/** Rolle eines Dachfluegels im Gebaeude. */
internal enum class WingRole {
    /** Hauptbau: das groesste Rechteck des Grundrisses, Satteldach ueber die Laengsachse. */
    MAIN,

    /** Querfluegel an der Traufseite des Hauptbaus; sein First laeuft ins Hauptdach. */
    CROSS,

    /** Verlaengerung an der Giebelseite des Hauptbaus mit gleicher Firstrichtung, schmaler. */
    EXTENSION,

    /** Niedriger Anbau mit Pult- oder Flachdach. */
    ANNEX,

    /** Eines von mehreren gleichrangigen Rechtecken (Walmdach-Zerlegung verwinkelter Grundrisse). */
    PART,
}

/**
 * Ein Dachfluegel: ein Rechteck des Grundrisses mit eigenem Dach. Der First
 * laeuft bei [alongX] in x-Richtung (sonst in y) ueber die Mitte. Jedes Ende
 * ist Giebel oder Walm; [pent] ≠ 0 macht daraus ein Pultdach (hohe Seite bei
 * `+1` an der oberen, bei `−1` an der unteren Querkoordinate).
 *
 * (`x0`, `y0`)–(`x1`, `y1`) ist die Flaeche, die das Dach deckt. Querfluegel
 * und Verlaengerungen reichen damit in den Hauptbau hinein; ihr eigener Teil
 * des Grundrisses ist (`ownX0`, `ownY0`)–(`ownX1`, `ownY1`).
 */
internal class RoofWing(
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val alongX: Boolean,
    var hipLo: Boolean,
    var hipHi: Boolean,
    val pent: Int = 0,
    val role: WingRole = WingRole.PART,
    val ownX0: Double = x0,
    val ownY0: Double = y0,
    val ownX1: Double = x1,
    val ownY1: Double = y1,
) {
    /** Dachneigung als Tangens. */
    var tan = 0.0

    /** Traufhoehe relativ zur Traufe des Hauptbaus (Anbauten liegen tiefer). */
    var base = 0.0

    /** Flachdach mit Attika statt Schraege (nur Anbauten). */
    var flat = false

    /**
     * Das Dach liegt ueber dem Huellrechteck eines schiefwinkligen Grundrisses: Die
     * Waende stehen nicht unter Traufe und Ortgang, sondern reichen ueberall bis
     * unter die Dachhaut.
     */
    var loose = false

    /** Das Ende steckt im Dach des Hauptbaus: keine Wand, kein Ortgang. */
    var buriedLo = false
    var buriedHi = false

    /** Bei Anbauten mit Flachdach: Seiten am Rand des Grundrisses, Reihenfolge `x0`, `x1`, `y0`, `y1`. */
    val open = BooleanArray(4)

    /** Halbe Breite quer zum First. */
    val halfWidth: Double get() = if (alongX) (y1 - y0) / 2 else (x1 - x0) / 2

    /** Laenge entlang des Firsts. */
    val length: Double get() = if (alongX) x1 - x0 else y1 - y0
    val area: Double get() = (x1 - x0) * (y1 - y0)

    /** Anfang und Ende entlang des Firsts, Mitte quer dazu. */
    val a0: Double get() = if (alongX) x0 else y0
    val a1: Double get() = if (alongX) x1 else y1
    val mid: Double get() = if (alongX) (y0 + y1) / 2 else (x0 + x1) / 2

    fun contains(x: Double, y: Double, eps: Double = 1e-9): Boolean = x >= x0 - eps && x <= x1 + eps && y >= y0 - eps && y <= y1 + eps

    /** Firsthoehe ueber der eigenen Traufe. */
    val rise: Double get() = if (flat) 0.0 else (if (pent != 0) 2 * halfWidth else halfWidth) * tan

    /**
     * Hoehe der Dachhaut ueber der Traufe des Hauptbaus an der Stelle
     * (`x`, `y`) — auch ausserhalb des Rechtecks (im Dachueberstand tiefer).
     */
    fun topAt(x: Double, y: Double): Double {
        if (flat) return base
        val a = if (alongX) x else y
        val b = (if (alongX) y else x) - mid
        if (pent != 0) return base + (halfWidth + pent * b) * tan
        var h = (halfWidth - abs(b)) * tan
        if (hipLo) h = min(h, (a - a0) * tan)
        if (hipHi) h = min(h, (a1 - a) * tan)
        return base + h
    }

    /** Punkt (`a` entlang des Firsts, `b` quer ab Mitte) im Gebaeudesystem. */
    fun px(a: Double, b: Double): Double = if (alongX) a else mid + b
    fun py(a: Double, b: Double): Double = if (alongX) mid + b else a
}

internal enum class RoofFaceKind {
    /** Dachhaut. */
    TOP,

    /** Untersicht (nur im Ueberstand sichtbar). */
    SOFFIT,

    /** Stirn an der Traufe (Traufbrett, Rinne). */
    FASCIA_EAVE,

    /** Stirn am Ortgang. */
    FASCIA_VERGE,

    /** Innenseite der Attika eines Flachdachs. */
    PARAPET,
}

/**
 * Eine ebene Flaeche eines Dachs: konvexes Vieleck mit Punkten `x, y, h` im
 * Gebaeudesystem (`h` ueber der Traufe des Hauptbaus) und der Normalen, zu
 * der es zeigen soll. [downX]/[downY]: waagerechte Fallrichtung der
 * Dachflaeche (bei [RoofFaceKind.TOP] und [RoofFaceKind.SOFFIT]; 0/0 = eben).
 */
internal class RoofFace(
    val kind: RoofFaceKind,
    val pts: DoubleArray,
    val nx: Double,
    val ny: Double,
    val nz: Double,
    val downX: Double = 0.0,
    val downY: Double = 0.0,
)

internal object RoofWings {
    /** Mehr Fluegel ergeben kein glaubwuerdiges Dach mehr (und kosten zu viele Dreiecke). */
    const val MAX_WINGS = 10

    /** Der Grundriss als Gitter ueber seine Eckkoordinaten. */
    private class Grid(fp: SquaredFootprint) {
        val xs = coords(fp.rings, 0)
        val ys = coords(fp.rings, 1)
        val nx = xs.size - 1
        val ny = ys.size - 1
        val ok = nx in 1..24 && ny in 1..24

        /** inside[i][j]: Zelle zwischen xs[i]..xs[i+1] und ys[j]..ys[j+1] */
        val inside: Array<BooleanArray> =
            if (ok) Array(nx) { i -> BooleanArray(ny) { j -> pointInRings(fp.rings, (xs[i] + xs[i + 1]) / 2, (ys[j] + ys[j + 1]) / 2) } } else emptyArray()
        private val sum = Array(nx + 1) { IntArray(ny + 1) }

        init {
            if (ok) {
                for (i in 0 until nx) for (j in 0 until ny) {
                    sum[i + 1][j + 1] = sum[i][j + 1] + sum[i + 1][j] - sum[i][j] + (if (inside[i][j]) 1 else 0)
                }
            }
        }

        fun full(i0: Int, i1: Int, j0: Int, j1: Int): Boolean {
            if (i0 < 0 || j0 < 0 || i1 >= nx || j1 >= ny) return false
            val c = sum[i1 + 1][j1 + 1] - sum[i0][j1 + 1] - sum[i1 + 1][j0] + sum[i0][j0]
            return c == (i1 - i0 + 1) * (j1 - j0 + 1)
        }

        fun cell(i: Int, j: Int): Boolean = i in 0 until nx && j in 0 until ny && inside[i][j]

        /** Alle groessten einbeschriebenen Rechtecke (in Zellen). */
        fun maximal(): List<Cells> {
            val rects = ArrayList<Cells>()
            for (i0 in 0 until nx) for (i1 in i0 until nx) for (j0 in 0 until ny) for (j1 in j0 until ny) {
                if (!full(i0, i1, j0, j1)) continue
                if (full(i0 - 1, i0 - 1, j0, j1) || full(i1 + 1, i1 + 1, j0, j1) || full(i0, i1, j0 - 1, j0 - 1) || full(i0, i1, j1 + 1, j1 + 1)) continue
                rects.add(Cells(this, i0, i1, j0, j1))
            }
            return rects
        }
    }

    private class Cells(g: Grid, val i0: Int, val i1: Int, val j0: Int, val j1: Int) {
        val x0 = g.xs[i0]
        val x1 = g.xs[i1 + 1]
        val y0 = g.ys[j0]
        val y1 = g.ys[j1 + 1]
        val w = x1 - x0
        val h = y1 - y0
        val minSide = min(w, h)
        val area = w * h
    }

    /**
     * Zerlegt einen rechtwinkligen Grundriss in **Hauptbau, Fluegel und
     * Anbauten** — so, wie solche Haeuser gebaut sind: Das groesste Rechteck
     * traegt ein Satteldach von Giebel zu Giebel; was uebrig bleibt, ist je
     * nach Groesse ein Querfluegel (First laeuft ins Hauptdach), eine
     * Verlaengerung oder ein niedriger Anbau mit eigenem Pult- oder Flachdach.
     *
     * `null`, wenn der Grundriss dafuer zu verwinkelt ist (Innenhof, Reste, die
     * keine Rechtecke sind) — dann hilft [decompose]. Hoehen und Neigungen
     * setzt der Aufrufer; der Hauptbau steht an erster Stelle.
     */
    fun mainAndAnnexes(fp: SquaredFootprint): List<RoofWing>? {
        if (fp.rings.size != 1) return null
        val g = Grid(fp)
        if (!g.ok) return null
        val rects = g.maximal()
        if (rects.isEmpty()) return null
        val m = rects.sortedWith(compareBy({ -it.area }, { -it.minSide }, { it.i0 }, { it.j0 })).first()
        // Reste: zusammenhaengende Zellen ausserhalb des Hauptbaus.
        val comp = Array(g.nx) { IntArray(g.ny) { -1 } }
        val parts = ArrayList<IntArray>()
        for (i in 0 until g.nx) for (j in 0 until g.ny) {
            if (!g.inside[i][j] || comp[i][j] >= 0 || (i in m.i0..m.i1 && j in m.j0..m.j1)) continue
            val id = parts.size
            val box = intArrayOf(i, i, j, j, 0)
            val stack = ArrayDeque<IntArray>()
            stack.add(intArrayOf(i, j))
            comp[i][j] = id
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                box[0] = min(box[0], c[0]); box[1] = max(box[1], c[0]); box[2] = min(box[2], c[1]); box[3] = max(box[3], c[1])
                box[4]++
                for (dir in 0 until 4) {
                    val ni = c[0] + (if (dir == 0) 1 else if (dir == 1) -1 else 0)
                    val nj = c[1] + (if (dir == 2) 1 else if (dir == 3) -1 else 0)
                    if (!g.cell(ni, nj) || comp[ni][nj] >= 0 || (ni in m.i0..m.i1 && nj in m.j0..m.j1)) continue
                    comp[ni][nj] = id
                    stack.add(intArrayOf(ni, nj))
                }
            }
            if (box[4] != (box[1] - box[0] + 1) * (box[3] - box[2] + 1)) return null
            parts.add(box)
        }
        if (parts.size + 1 > MAX_WINGS) return null
        val mainAlongX = m.w >= m.h * 0.999
        val main = RoofWing(m.x0, m.y0, m.x1, m.y1, mainAlongX, hipLo = false, hipHi = false, role = WingRole.MAIN)
        val hwM = main.halfWidth
        val out = ArrayList<RoofWing>()
        out.add(main)
        for (box in parts) {
            val a = Cells(g, box[0], box[1], box[2], box[3])
            // An welcher Seite des Hauptbaus haengt der Rest?
            val below = a.j1 + 1 == m.j0 && a.i1 >= m.i0 && a.i0 <= m.i1
            val above = a.j0 == m.j1 + 1 && a.i1 >= m.i0 && a.i0 <= m.i1
            val left = a.i1 + 1 == m.i0 && a.j1 >= m.j0 && a.j0 <= m.j1
            val right = a.i0 == m.i1 + 1 && a.j1 >= m.j0 && a.j0 <= m.j1
            if (listOf(below, above, left, right).count { it } != 1) return null
            val horizontal = below || above
            val depth = if (horizontal) a.h else a.w
            val width = if (horizontal) a.w else a.h
            val lo = if (horizontal) a.x0 else a.y0
            val hi = if (horizontal) a.x1 else a.y1
            val mLo = if (horizontal) m.x0 else m.y0
            val mHi = if (horizontal) m.x1 else m.y1
            val within = lo >= mLo - 1e-6 && hi <= mHi + 1e-6
            val onEave = horizontal == mainAlongX
            val plus = above || right
            if (onEave && within && depth >= 3.0 && width >= 3.5 && depth + 2 * hwM >= 0.9 * width && a.area >= 0.2 * m.area) {
                // Querfluegel: Dach bis zur Firstlinie des Hauptbaus verlaengert.
                val w = if (horizontal) {
                    if (plus) RoofWing(a.x0, main.mid, a.x1, a.y1, false, false, false, role = WingRole.CROSS, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                    else RoofWing(a.x0, a.y0, a.x1, main.mid, false, false, false, role = WingRole.CROSS, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                } else {
                    if (plus) RoofWing(main.mid, a.y0, a.x1, a.y1, true, false, false, role = WingRole.CROSS, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                    else RoofWing(a.x0, a.y0, main.mid, a.y1, true, false, false, role = WingRole.CROSS, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                }
                if (plus) w.buriedLo = true else w.buriedHi = true
                // Buendig mit einem Giebel des Hauptbaus: dort walmt der Hauptbau ab (Winkelhaus).
                if (lo - mLo < 1.0) main.hipLo = true
                if (mHi - hi < 1.0) main.hipHi = true
                out.add(w)
            } else if (!onEave && within && depth >= 3.0 && width >= 0.6 * 2 * hwM && width <= 2 * hwM - 0.5) {
                // Verlaengerung: gleiche Firstrichtung, ein Stueck in den Hauptbau gesteckt.
                val bury = 0.4
                val w = if (horizontal) {
                    if (plus) RoofWing(a.x0, a.y0 - bury, a.x1, a.y1, false, false, false, role = WingRole.EXTENSION, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                    else RoofWing(a.x0, a.y0, a.x1, a.y1 + bury, false, false, false, role = WingRole.EXTENSION, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                } else {
                    if (plus) RoofWing(a.x0 - bury, a.y0, a.x1, a.y1, true, false, false, role = WingRole.EXTENSION, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                    else RoofWing(a.x0, a.y0, a.x1 + bury, a.y1, true, false, false, role = WingRole.EXTENSION, ownX0 = a.x0, ownY0 = a.y0, ownX1 = a.x1, ownY1 = a.y1)
                }
                if (plus) w.buriedLo = true else w.buriedHi = true
                out.add(w)
            } else {
                // Anbau: Pultdach faellt vom Hauptbau weg (hohe Seite am Hauptbau).
                val w = RoofWing(a.x0, a.y0, a.x1, a.y1, alongX = horizontal, hipLo = false, hipHi = false, pent = if (plus) -1 else 1, role = WingRole.ANNEX)
                w.open[0] = !right
                w.open[1] = !left
                w.open[2] = !above
                w.open[3] = !below
                out.add(w)
            }
        }
        return out
    }

    /**
     * Zerlegt einen rechtwinkligen Grundriss in seine groessten einbeschriebenen
     * Rechtecke. `null`, wenn er dafuer zu verwinkelt ist. [gables] = freie
     * Enden als Giebel (sonst ueberall Walm).
     */
    fun decompose(fp: SquaredFootprint, gables: Boolean): List<RoofWing>? {
        val g = Grid(fp)
        if (!g.ok) return null
        val xs = g.xs
        val ys = g.ys
        val rects = g.maximal()
        if (rects.isEmpty()) return null
        // Schmale Streifen streichen, wenn breitere Rechtecke alle ihre Zellen decken (sonst entstuenden Zwillingsfirste).
        val keep = rects.sortedBy { it.minSide }.toMutableList()
        var idx = 0
        while (idx < keep.size) {
            val r = keep[idx]
            var redundant = r.minSide < 2.5 && keep.size > 1
            if (redundant) {
                outer@ for (i in r.i0..r.i1) for (j in r.j0..r.j1) {
                    val covered = keep.any { it !== r && it.minSide >= r.minSide && i >= it.i0 && i <= it.i1 && j >= it.j0 && j <= it.j1 }
                    if (!covered) {
                        redundant = false
                        break@outer
                    }
                }
            }
            if (redundant) keep.removeAt(idx) else idx++
        }
        if (keep.size > MAX_WINGS) return null
        // Reihenfolge fest und unabhaengig von der Sortierung oben: groesste zuerst.
        val ordered = keep.sortedWith(compareBy({ -it.area }, { it.i0 }, { it.j0 }, { it.i1 }, { it.j1 }))
        return ordered.map { r ->
            val alongX = r.w >= r.h * 0.999
            val hw = (if (alongX) r.h else r.w) / 2
            // Ein Ende ist frei, wenn die Stirnseite und beide Laengsseiten auf halbe Breite am Rand liegen.
            fun free(lo: Boolean): Boolean {
                if (!gables) return false
                if (alongX) {
                    val iOut = if (lo) r.i0 - 1 else r.i1 + 1
                    for (j in r.j0..r.j1) if (g.cell(iOut, j)) return false
                    val range = if (lo) (r.i0..r.i1) else (r.i1 downTo r.i0)
                    for (i in range) {
                        val dist = if (lo) xs[i] - xs[r.i0] else xs[r.i1 + 1] - xs[i + 1]
                        if (dist >= hw - 1e-6) break
                        if (g.cell(i, r.j0 - 1) || g.cell(i, r.j1 + 1)) return false
                    }
                } else {
                    val jOut = if (lo) r.j0 - 1 else r.j1 + 1
                    for (i in r.i0..r.i1) if (g.cell(i, jOut)) return false
                    val range = if (lo) (r.j0..r.j1) else (r.j1 downTo r.j0)
                    for (j in range) {
                        val dist = if (lo) ys[j] - ys[r.j0] else ys[r.j1 + 1] - ys[j + 1]
                        if (dist >= hw - 1e-6) break
                        if (g.cell(r.i0 - 1, j) || g.cell(r.i1 + 1, j)) return false
                    }
                }
                return true
            }
            RoofWing(r.x0, r.y0, r.x1, r.y1, alongX, hipLo = !free(true), hipHi = !free(false))
        }
    }

    private fun coords(rings: List<DoubleArray>, axis: Int): DoubleArray {
        val all = ArrayList<Double>()
        for (r in rings) for (i in 0 until r.size / 2) all.add(r[2 * i + axis])
        all.sort()
        val out = ArrayList<Double>()
        for (v in all) if (out.isEmpty() || v - out.last() > 1e-6) out.add(v)
        return out.toDoubleArray()
    }

    /**
     * Die Flaechen eines Fluegeldachs, Hoehen ueber der Traufe des Hauptbaus.
     * Mit [thickness] > 0 ein Koerper (Dachhaut, Untersicht, Stirnen) mit
     * Ueberstand [eave] an Traufe und Walm und [verge] am Ortgang; mit
     * `thickness = 0` nur die Dachhaut buendig mit den Waenden.
     */
    fun faces(w: RoofWing, eave: Double, verge: Double, thickness: Double): List<RoofFace> {
        val out = ArrayList<RoofFace>()
        val hw = w.halfWidth
        val tan = w.tan
        val solid = thickness > 0
        val oe = if (solid) eave else 0.0
        val ov = if (solid) verge else 0.0
        val bb = hw + oe
        // Richtungen im Gebaeudesystem: a = entlang des Firsts, b = quer.
        val ax = if (w.alongX) 1.0 else 0.0
        val ay = if (w.alongX) 0.0 else 1.0
        val bx = if (w.alongX) 0.0 else 1.0
        val by = if (w.alongX) 1.0 else 0.0

        fun p(a: Double, b: Double, h: Double) = doubleArrayOf(w.px(a, b), w.py(a, b), w.base + h)
        fun poly(vararg q: DoubleArray) = DoubleArray(q.size * 3) { q[it / 3][it % 3] }
        fun lowered(pts: DoubleArray) = DoubleArray(pts.size) { if (it % 3 == 2) pts[it] - thickness else pts[it] }

        fun slab(pts: DoubleArray, dx: Double, dy: Double) {
            out.add(RoofFace(RoofFaceKind.TOP, pts, dx * tan, dy * tan, 1.0, dx, dy))
            if (solid) out.add(RoofFace(RoofFaceKind.SOFFIT, lowered(pts), -dx * tan, -dy * tan, -1.0, dx, dy))
        }

        fun rim(kind: RoofFaceKind, p0: DoubleArray, p1: DoubleArray, nx: Double, ny: Double) {
            if (!solid) return
            out.add(
                RoofFace(
                    kind,
                    doubleArrayOf(p0[0], p0[1], p0[2] - thickness, p1[0], p1[1], p1[2] - thickness, p1[0], p1[1], p1[2], p0[0], p0[1], p0[2]),
                    nx, ny, 0.0,
                ),
            )
        }

        if (w.flat) {
            // Flachdach des Anbaus: Dachflaeche etwas unter der Attika, Innenseiten nur an freien Seiten.
            val recess = if (solid) 0.12 else 0.0
            val h = -recess
            out.add(RoofFace(RoofFaceKind.TOP, doubleArrayOf(w.x0, w.y0, w.base + h, w.x1, w.y0, w.base + h, w.x1, w.y1, w.base + h, w.x0, w.y1, w.base + h), 0.0, 0.0, 1.0))
            if (solid) {
                fun inner(xa: Double, ya: Double, xb: Double, yb: Double, nx: Double, ny: Double) {
                    out.add(RoofFace(RoofFaceKind.PARAPET, doubleArrayOf(xa, ya, w.base + h, xb, yb, w.base + h, xb, yb, w.base, xa, ya, w.base), nx, ny, 0.0))
                }
                if (w.open[0]) inner(w.x0, w.y0, w.x0, w.y1, 1.0, 0.0)
                if (w.open[1]) inner(w.x1, w.y0, w.x1, w.y1, -1.0, 0.0)
                if (w.open[2]) inner(w.x0, w.y0, w.x1, w.y0, 0.0, 1.0)
                if (w.open[3]) inner(w.x0, w.y1, w.x1, w.y1, 0.0, -1.0)
            }
            return out
        }

        if (w.pent != 0) {
            val s = w.pent.toDouble()
            val aL = w.a0 - ov
            val aH = w.a1 + ov
            // Ein Anbau stoesst mit der hohen Seite an die Wand des Hauptbaus: dort kein Ueberstand.
            val oHigh = if (w.role == WingRole.ANNEX) 0.0 else oe
            val hLow = -oe * tan
            val hHigh = (2 * hw + oHigh) * tan
            val lo = -s * bb
            val hi = s * (hw + oHigh)
            slab(poly(p(aL, lo, hLow), p(aH, lo, hLow), p(aH, hi, hHigh), p(aL, hi, hHigh)), -s * bx, -s * by)
            rim(RoofFaceKind.FASCIA_EAVE, p(aL, lo, hLow), p(aH, lo, hLow), -s * bx, -s * by)
            rim(RoofFaceKind.FASCIA_VERGE, p(aL, hi, hHigh), p(aH, hi, hHigh), s * bx, s * by)
            rim(RoofFaceKind.FASCIA_VERGE, p(aL, lo, hLow), p(aL, hi, hHigh), -ax, -ay)
            rim(RoofFaceKind.FASCIA_VERGE, p(aH, lo, hLow), p(aH, hi, hHigh), ax, ay)
            return out
        }

        val hR = hw * tan
        val hE = -oe * tan
        val e0 = w.a0 - (if (w.buriedLo) 0.0 else if (w.hipLo) oe else ov)
        val e1 = w.a1 + (if (w.buriedHi) 0.0 else if (w.hipHi) oe else ov)
        var r0 = if (w.hipLo) w.a0 + hw else e0
        var r1 = if (w.hipHi) w.a1 - hw else e1
        if (r0 > r1) {
            // Quadratischer Fluegel mit zwei Walmen: Zeltdach, der First schrumpft auf einen Punkt.
            r0 = (r0 + r1) / 2
            r1 = r0
        }
        for (s in doubleArrayOf(1.0, -1.0)) {
            slab(poly(p(r0, 0.0, hR), p(r1, 0.0, hR), p(e1, s * bb, hE), p(e0, s * bb, hE)), s * bx, s * by)
            rim(RoofFaceKind.FASCIA_EAVE, p(e0, s * bb, hE), p(e1, s * bb, hE), s * bx, s * by)
        }
        if (w.hipLo) {
            slab(poly(p(r0, 0.0, hR), p(e0, bb, hE), p(e0, -bb, hE)), -ax, -ay)
            rim(RoofFaceKind.FASCIA_EAVE, p(e0, -bb, hE), p(e0, bb, hE), -ax, -ay)
        } else if (!w.buriedLo) {
            rim(RoofFaceKind.FASCIA_VERGE, p(e0, -bb, hE), p(e0, 0.0, hR), -ax, -ay)
            rim(RoofFaceKind.FASCIA_VERGE, p(e0, 0.0, hR), p(e0, bb, hE), -ax, -ay)
        }
        if (w.hipHi) {
            slab(poly(p(r1, 0.0, hR), p(e1, -bb, hE), p(e1, bb, hE)), ax, ay)
            rim(RoofFaceKind.FASCIA_EAVE, p(e1, -bb, hE), p(e1, bb, hE), ax, ay)
        } else if (!w.buriedHi) {
            rim(RoofFaceKind.FASCIA_VERGE, p(e1, -bb, hE), p(e1, 0.0, hR), ax, ay)
            rim(RoofFaceKind.FASCIA_VERGE, p(e1, 0.0, hR), p(e1, bb, hE), ax, ay)
        }
        return out
    }
}
