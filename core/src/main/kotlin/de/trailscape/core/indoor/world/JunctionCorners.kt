package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * # Ausgerundete Ecken an Einmuendungen
 *
 * Wo eine uebrige Strasse aus der Fahrbahn der Strecke herauslaeuft, liegt zwischen der Kante der
 * Strecke und der Kante der Strasse auf beiden Seiten eine Ecke. Echte Einmuendungen sind dort mit
 * einem Radius ausgerundet (Schleppkurve), im Ort mit einem Bordstein im Bogen. Ohne diese Flaeche
 * lagen dort Gras und Gehweg mit eckigem Ende — oder, an Kreiseleinfahrten mit spitzem Winkel, ein
 * Graskeil mitten in der gemalten Fahrbahn.
 *
 * [find] liefert je Ecke den Eckpunkt `P` (Schnitt der beiden Kanten) und den Bogen (Radius [radius],
 * tangential an beide Kanten). Die Flaeche zwischen `P` und dem Bogen ist Fahrbahn: [RoadsLayer]
 * zeichnet sie (und im Ort den Bordstein entlang des Bogens), [RouteEnv] nimmt sie in die
 * Sperrflaechen auf (Gras und Gehweg enden am Bogen bzw. hinter dem Bordstein). Beide rechnen aus
 * denselben Eingaben (Strecke, Karte, Landnutzung, Abtastung [RoadsLayer.STEP_M]) dieselben Ecken.
 */
internal object JunctionCorners {
    class Corner(
        val road: RoadNet.Road,
        /** Station der Strasse an der Einmuendung (fuer Hoehe und Belag). */
        val station: Int,
        val pe: Double,
        val pn: Double,
        /** Bogenpunkte (e, n) vom Fahrbahnrand der Strecke zur Kante der Strasse. */
        val arc: DoubleArray,
        /** Derselbe Bogen um [KERB_WIDTH_M] zur Mitte versetzt (Hinterkante des Bordsteins). */
        val arcBack: DoubleArray,
        val urban: Boolean,
        val radius: Double,
    ) {
        val minE: Double get() = minOf(pe, arc.filterIndexed { i, _ -> i % 2 == 0 }.min())
        val maxE: Double get() = maxOf(pe, arc.filterIndexed { i, _ -> i % 2 == 0 }.max())
        val minN: Double get() = minOf(pn, arc.filterIndexed { i, _ -> i % 2 == 1 }.min())
        val maxN: Double get() = maxOf(pn, arc.filterIndexed { i, _ -> i % 2 == 1 }.max())

        /**
         * Die Flaeche bis zum Bogen als Faecher-Dreiecke in [mask], im Ort dazu der Bordsteinstreifen als
         * Vierecke zwischen Bogen und Hinterkante. (Ein Faecher bis zur Hinterkante deckte den Bordstein am
         * Bogenende nicht: Die Hinterkante ist dort senkrecht zur Strassenkante versetzt, der Strahl vom
         * Eckpunkt laeuft am Bordstein vorbei — dort blieb Gras 1–4 mm unter dem Bordstein liegen.)
         */
        fun addTo(mask: PlanMask) {
            val a = arc
            for (k in 0 until a.size / 2 - 1) {
                mask.add(doubleArrayOf(pe, pn, a[2 * k], a[2 * k + 1], a[2 * k + 2], a[2 * k + 3]))
            }
            if (!urban) return
            val b = arcBack
            for (k in 0 until a.size / 2 - 1) {
                mask.add(doubleArrayOf(a[2 * k], a[2 * k + 1], a[2 * k + 2], a[2 * k + 3], b[2 * k + 2], b[2 * k + 3], b[2 * k], b[2 * k + 1]))
            }
        }
    }

    const val KERB_WIDTH_M = 0.15
    private const val ARC_SEGMENTS = 8

    /** Radius im Ort (Bordstein) und ausserorts. */
    const val RADIUS_URBAN_M = 5.0
    const val RADIUS_RURAL_M = 4.0

    /** Laengste Tangente vom Eckpunkt zum Bogenanfang: bei spitzen Winkeln wird der Radius kleiner. */
    private const val MAX_TANGENT_M = 7.0

    fun find(track: TrackLine, land: LandMap, net: RoadNet): List<Corner> {
        val out = ArrayList<Corner>()
        for (r in net.roads) {
            if (r.spec.rail) continue
            for (i in 0 until r.size - 1) {
                val ha = track.nearest(r.e[i], r.n[i], 40.0) ?: continue
                val hb = track.nearest(r.e[i + 1], r.n[i + 1], 40.0) ?: continue
                val wa = track.halfWidthAt(ha.distM)
                val wb = track.halfWidthAt(hb.distM)
                val inA = ha.distance < wa
                val inB = hb.distance < wb
                if (inA == inB) continue
                // Richtung von innen nach aussen, Einmuendungspunkt J auf der Fahrbahnkante.
                val (ii, oo) = if (inA) i to i + 1 else i + 1 to i
                val hi = if (inA) ha else hb
                val ho = if (inA) hb else ha
                val wi = if (inA) wa else wb
                val wo = if (inA) wb else wa
                var dx = r.e[oo] - r.e[ii]
                var dy = r.n[oo] - r.n[ii]
                val len = hypot(dx, dy)
                if (len < 1e-6) continue
                dx /= len; dy /= len
                val fi = hi.distance - wi
                val fo = ho.distance - wo
                val f = (-fi / (fo - fi)).coerceIn(0.0, 1.0)
                val je = r.e[ii] + (r.e[oo] - r.e[ii]) * f
                val jn = r.n[ii] + (r.n[oo] - r.n[ii]) * f
                val hit = track.nearest(je, jn, 40.0) ?: continue
                val pose = track.poseAt(hit.distM)
                val h = Math.toRadians(pose.headingDeg)
                val te = sin(h)
                val tn = cos(h)
                // Fast gleichlaufende Strassen (Abzweig im spitzen Winkel ueber viele Meter) bekommen keine Ecke.
                if (abs(dx * te + dy * tn) > 0.96) continue
                val re = tn
                val rn = -te
                val side = if (dx * re + dy * rn >= 0) 1.0 else -1.0
                val thw = track.halfWidthAt(hit.distM)
                val ce = pose.e + re * side * thw
                val cn = pose.n + rn * side * thw
                val urban = isUrban(track, land, hit.distM, side)
                val rhw = r.halfWidth
                val k = if (inA) i + 1 else i
                for (q in listOf(-1.0, 1.0)) {
                    // Kante der Strasse: J + q·rechts·rhw, Richtung (dx, dy). Kante der Strecke: C + T·a.
                    val rre = dy
                    val rrn = -dx
                    val le = je + rre * q * rhw
                    val ln = jn + rrn * q * rhw
                    val p = intersect(ce, cn, te, tn, le, ln, dx, dy) ?: continue
                    val pe = p[0]
                    val pn = p[1]
                    if (hypot(pe - je, pn - jn) > 15.0) continue
                    // Strahl entlang der Strecke zur Seite q der Strasse hin.
                    val sgn = if (te * rre * q + tn * rrn * q >= 0) 1.0 else -1.0
                    val ate = te * sgn
                    val atn = tn * sgn
                    val cosT = (ate * dx + atn * dy).coerceIn(-1.0, 1.0)
                    val theta = kotlin.math.acos(cosT)
                    if (theta < 0.35 || theta > PI - 0.2) continue
                    var radius = if (urban) RADIUS_URBAN_M else RADIUS_RURAL_M
                    val half = theta / 2
                    var tang = radius / tan(half)
                    if (tang > MAX_TANGENT_M) {
                        tang = MAX_TANGENT_M
                        radius = tang * tan(half)
                    }
                    if (radius < 0.8) continue
                    val ae = pe + ate * tang
                    val an = pn + atn * tang
                    val be = pe + dx * tang
                    val bn = pn + dy * tang
                    var bx = ate + dx
                    var by = atn + dy
                    val bl = hypot(bx, by)
                    if (bl < 1e-6) continue
                    bx /= bl; by /= bl
                    val od = radius / sin(half)
                    val oe = pe + bx * od
                    val on = pn + by * od
                    val arc = arcPoints(oe, on, ae, an, be, bn, radius)
                    val back = arcPoints(oe, on, ae, an, be, bn, radius - KERB_WIDTH_M)
                    out.add(Corner(r, k, pe, pn, arc, back, urban, radius))
                }
            }
        }
        return out
    }

    /** Im Ort? Dieselbe Regel wie der Rand der Strecke ([edgeStyleOf]), Landnutzung ueber ±10 m gemittelt. */
    private fun isUrban(track: TrackLine, land: LandMap, distM: Double, side: Double): Boolean {
        val counts = IntArray(LandClass.entries.size)
        for (o in listOf(-10.0, 0.0, 10.0)) {
            val d = (distM + o).coerceIn(0.0, track.lengthM)
            val p = track.track.poseAt(d)
            val h = Math.toRadians(p.headingDeg)
            val off = (track.halfWidthAt(d) + 3.0) * side
            counts[land.classAt(p.e + cos(h) * off, p.n - sin(h) * off).ordinal]++
        }
        var best = 0
        for (c in counts.indices) if (counts[c] > counts[best]) best = c
        val sec = track.surfaceAt(distM)
        return edgeStyleOf(sec, LandClass.entries[best]) == EdgeStyle.URBAN
    }

    private fun intersect(ax: Double, ay: Double, adx: Double, ady: Double, bx: Double, by: Double, bdx: Double, bdy: Double): DoubleArray? {
        val den = adx * bdy - ady * bdx
        if (abs(den) < 0.15) return null
        val t = ((bx - ax) * bdy - (by - ay) * bdx) / den
        return doubleArrayOf(ax + adx * t, ay + ady * t)
    }

    private fun arcPoints(oe: Double, on: Double, ae: Double, an: Double, be: Double, bn: Double, radius: Double): DoubleArray {
        val a0 = atan2(an - on, ae - oe)
        var a1 = atan2(bn - on, be - oe)
        var d = a1 - a0
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        a1 = a0 + d
        val pts = DoubleArray((ARC_SEGMENTS + 1) * 2)
        for (k in 0..ARC_SEGMENTS) {
            val a = a0 + (a1 - a0) * k / ARC_SEGMENTS
            pts[2 * k] = oe + cos(a) * radius
            pts[2 * k + 1] = on + sin(a) * radius
        }
        return pts
    }

    /** Belag der Eckflaeche: der der Strasse. */
    fun kindOf(c: Corner): SurfaceKind = c.road.spec.kind
}
