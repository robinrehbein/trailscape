package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * # Kreisverkehre
 *
 * Die Vektorkacheln kennen kein `junction=roundabout`; ein Kreisverkehr ist dort
 * eine kleine, geschlossene Strassenlinie (oft in mehrere Stuecke zerlegt, die an
 * den Einmuendungen aneinanderstossen). [islands] setzt solche Stuecke derselben
 * Klasse wieder zusammen und liefert fuer jeden runden, geschlossenen Ring die
 * **Mittelinsel**: den Ring, um halbe Fahrbahnbreite plus Bordstein nach innen
 * versetzt.
 *
 * Vorher stand dort nur das Luftbild (mit dem Grasrand der Strecke in weissem
 * Auslauf) und gelegentlich ein Weidezaun der Kleinteile — die Rueckmeldung vom
 * Geraet war „Kreisverkehr … noch komisch“. Jetzt zeichnet [RoadsLayer] die Insel
 * als leicht erhoehte Grasflaeche mit Bordstein, und [PropsLayer] setzt dort keine
 * Zaeune.
 *
 * Mini-Kreisel (nur ein Punkt in OSM) kommen in den Vektorkacheln nicht vor.
 */
internal object Roundabouts {
    /** Eine Mittelinsel: Ring in (e, n) gegen den Uhrzeigersinn, Mitte, mittlerer Radius. */
    class Island(val ring: DoubleArray, val ce: Double, val cn: Double, val radius: Double) {
        fun contains(e: Double, n: Double, marginM: Double = 0.0): Boolean {
            if (hypot(e - ce, n - cn) > radius * 1.6 + marginM) return false
            if (pointInRings(listOf(ring), e, n)) return true
            if (marginM <= 0.0) return false
            val closed = DoubleArray(ring.size + 2)
            ring.copyInto(closed)
            closed[ring.size] = ring[0]
            closed[ring.size + 1] = ring[1]
            return distanceToLine(closed, e, n) < marginM
        }
    }

    /** Groesster Durchmesser eines Rings, der noch als Kreisverkehr gilt. */
    const val MAX_DIAMETER_M = 110.0
    const val MIN_DIAMETER_M = 12.0

    /** Mittelinseln aller Kreisverkehre, deren Mitte in (minE, minN)–(maxE, maxN) liegt. */
    fun islands(map: MapData, minE: Double, minN: Double, maxE: Double, maxN: Double): List<Island> {
        val m = MAX_DIAMETER_M
        val pieces = ArrayList<Piece>()
        for (r in map.roads(minE - m, minN - m, maxE + m, maxN + m)) {
            if (r.brunnel == "bridge" || r.brunnel == "tunnel") continue
            if (r.cls !in RING_CLASSES || r.line.size < 6) continue
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (k in 0 until r.line.size / 2) {
                x0 = min(x0, r.line[2 * k]); x1 = max(x1, r.line[2 * k])
                y0 = min(y0, r.line[2 * k + 1]); y1 = max(y1, r.line[2 * k + 1])
            }
            if (x1 - x0 > m || y1 - y0 > m) continue
            pieces += Piece(r.cls, r.line.toMutableList())
        }
        // Stuecke derselben Klasse an gemeinsamen Enden verketten.
        var merged = true
        while (merged) {
            merged = false
            loop@ for (a in pieces.indices) for (b in pieces.indices) {
                if (a == b) continue
                val pa = pieces[a]
                val pb = pieces[b]
                if (pa.cls != pb.cls || pa.closed() || pb.closed()) continue
                if (near(pa.endE(), pa.endN(), pb.startE(), pb.startN())) {
                    pa.pts.addAll(pb.pts.subList(2, pb.pts.size))
                    pieces.removeAt(b)
                    merged = true
                    break@loop
                }
            }
        }
        val out = ArrayList<Island>()
        for (p in pieces) {
            if (!p.closed() || p.pts.size < 12) continue
            val cnt = p.pts.size / 2 - 1
            // Flaechenschwerpunkt (die Eckpunkte liegen an den Einmuendungen dichter).
            var a2 = 0.0
            var ce = 0.0
            var cn = 0.0
            for (k in 0 until cnt) {
                val x0 = p.pts[2 * k]; val y0 = p.pts[2 * k + 1]
                val x1 = p.pts[2 * k + 2]; val y1 = p.pts[2 * k + 3]
                val c = x0 * y1 - x1 * y0
                a2 += c; ce += (x0 + x1) * c; cn += (y0 + y1) * c
            }
            if (abs(a2) < 1e-6) continue
            ce /= 3 * a2; cn /= 3 * a2
            if (ce < minE || ce >= maxE || cn < minN || cn >= maxN) continue
            var rMin = Double.MAX_VALUE
            var rMax = 0.0
            var rSum = 0.0
            for (k in 0 until cnt) {
                val d = hypot(p.pts[2 * k] - ce, p.pts[2 * k + 1] - cn)
                rMin = min(rMin, d); rMax = max(rMax, d); rSum += d
            }
            val rMean = rSum / cnt
            if (2 * rMean < MIN_DIAMETER_M || rMin < 0.4 * rMax) continue // zu laenglich: Block, Schleife (ovale Kreisel bis 1 : 2,5 bleiben) 
            val inset = (CarvedGround.roadHalfWidthM(p.cls) ?: 3.0) + KERB_GAP_M
            if (rMean - inset < 1.5) continue
            val ring = DoubleArray(cnt * 2)
            for (k in 0 until cnt) {
                val dx = p.pts[2 * k] - ce
                val dy = p.pts[2 * k + 1] - cn
                val d = max(1e-6, hypot(dx, dy))
                val f = max(0.0, d - inset) / d
                ring[2 * k] = ce + dx * f
                ring[2 * k + 1] = cn + dy * f
            }
            if (signedArea(ring) < 0) reverse(ring)
            if (out.none { hypot(it.ce - ce, it.cn - cn) < 3.0 }) out += Island(ring, ce, cn, rMean - inset)
        }
        return out
    }

    /** Abstand zwischen Mittellinie des Rings und Inselrand ueber die halbe Breite hinaus. */
    const val KERB_GAP_M = 0.4

    private val RING_CLASSES = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service")

    private class Piece(val cls: String, val pts: MutableList<Double>) {
        fun startE() = pts[0]
        fun startN() = pts[1]
        fun endE() = pts[pts.size - 2]
        fun endN() = pts[pts.size - 1]
        fun closed() = pts.size >= 8 && near(startE(), startN(), endE(), endN())
    }

    private fun near(ax: Double, ay: Double, bx: Double, by: Double) = hypot(ax - bx, ay - by) < 0.8

    private fun signedArea(r: DoubleArray): Double {
        var a = 0.0
        val c = r.size / 2
        for (k in 0 until c) {
            val j = (k + 1) % c
            a += r[2 * k] * r[2 * j + 1] - r[2 * j] * r[2 * k + 1]
        }
        return a / 2
    }

    private fun reverse(r: DoubleArray) {
        val c = r.size / 2
        for (k in 0 until c / 2) {
            val j = c - 1 - k
            val e = r[2 * k]; val n = r[2 * k + 1]
            r[2 * k] = r[2 * j]; r[2 * k + 1] = r[2 * j + 1]
            r[2 * j] = e; r[2 * j + 1] = n
        }
    }
}
