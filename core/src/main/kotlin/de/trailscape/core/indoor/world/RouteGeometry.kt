package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.surfaceKindFromVectorTile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Gemeinsame Geometrie der Weg-Schichten
 *
 * Helfer fuer [RouteLayer], [RoadsLayer] und [PropsLayer]: die verfeinerte
 * Mittellinie der Strecke (Kurven rund, seitlicher Versatz ohne
 * Ueberschneidung an Innenkurven), die Zuordnung von Streckenstuecken zu
 * Kacheln, die uebrigen Strassen der Karte als abgetastete Linienzuege und
 * stetiges Weltrauschen fuer die Vertexfarben.
 *
 * ## Welche Kachel zeichnet welches Stueck der Strecke?
 * Die Strecke ist in **Einheiten** von zwei Proben-Abstaenden (ueblich 10 m)
 * geteilt: Einheit `k` reicht von Probe `2k` bis `2k + 2`. Sie gehoert genau
 * der Kachel, in der ihre mittlere Probe liegt. So schliessen die Baender
 * benachbarter Kacheln an einer gemeinsamen Probe lueckenlos und ohne
 * Doppelung an — unabhaengig davon, wie fein jede Kachel ihr Stueck unterteilt.
 */
internal object RouteGeometry {
    /** Zahl der Einheiten der Strecke. */
    fun unitCount(track: TrackLine): Int = (track.count - 1 + 1) / 2

    /** Erste Probe der Einheit [k]. */
    fun unitStart(k: Int): Int = 2 * k

    /** Letzte Probe der Einheit [k] (einschliesslich). */
    fun unitEnd(track: TrackLine, k: Int): Int = min(2 * k + 2, track.count - 1)

    /** Die Kachel der Stufe 0, die Einheit [k] zeichnet. */
    fun unitOwner(track: TrackLine, k: Int): TileSpec {
        val m = min(2 * k + 1, track.count - 1)
        return TileSpec.at(0, track.track.e[m].toDouble(), track.track.n[m].toDouble())
    }

    /** Alle Einheiten, die [spec] zeichnet, aufsteigend. */
    fun unitsOf(track: TrackLine, spec: TileSpec): IntArray {
        val t = track.track
        val out = ArrayList<Int>()
        for (k in 0 until unitCount(track)) {
            val m = min(2 * k + 1, track.count - 1)
            if (spec.contains(t.e[m].toDouble(), t.n[m].toDouble())) out.add(k)
        }
        return out.toIntArray()
    }

    /** Index des Belagsabschnitts, zu dem das Segment `i → i + 1` gehoert (nach seiner Mitte). */
    fun segSection(track: TrackLine, i: Int): Int {
        val s = track.track.surfaces
        val d = (i.coerceIn(0, max(0, track.count - 2)) + 0.5) * track.stepM
        var lo = 0
        var hi = s.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid].toM <= d) lo = mid + 1 else hi = mid
        }
        return lo
    }

    // -----------------------------------------------------------------------
    // Mittellinie
    // -----------------------------------------------------------------------

    private fun closed(t: WorldTrack): Boolean {
        val l = t.sampleCount - 1
        return t.loop && l >= 3 && hypot((t.e[0] - t.e[l]).toDouble(), (t.n[0] - t.n[l]).toDouble()) < 0.5
    }

    /** Probe [i] auch jenseits der Enden: auf einer geschlossenen Runde umlaufend, sonst gespiegelt fortgesetzt. */
    private fun pe(t: WorldTrack, i: Int, north: Boolean): Double {
        val l = t.sampleCount - 1
        val a = if (north) t.n else t.e
        if (i in 0..l) return a[i].toDouble()
        if (closed(t)) return a[Math.floorMod(i, l)].toDouble()
        return if (i < 0) 2.0 * a[0] - a[min(l, -i)] else 2.0 * a[l] - a[max(0, 2 * l - i)]
    }

    /** Richtungsaenderung an Probe [i] in Radiant, positiv = Rechtskurve. */
    fun turnAt(track: TrackLine, i: Int): Double {
        val t = track.track
        val ax = pe(t, i, false) - pe(t, i - 1, false)
        val ay = pe(t, i, true) - pe(t, i - 1, true)
        val bx = pe(t, i + 1, false) - pe(t, i, false)
        val by = pe(t, i + 1, true) - pe(t, i, true)
        return -atan2(ax * by - ay * bx, ax * bx + ay * by)
    }

    /** Vorzeichenbehaftete Kruemmung an Probe [i] in 1/m, positiv = Rechtskurve. */
    fun curvatureAt(track: TrackLine, i: Int): Double = turnAt(track, i) / max(1.0, track.stepM)

    /** In wie viele Stuecke das Segment `i → i + 1` fuer runde Kurven geteilt wird (1..[maxSub]). */
    fun subdivisions(track: TrackLine, i: Int, maxSub: Int): Int {
        val a = Math.toDegrees(max(abs(turnAt(track, i)), abs(turnAt(track, i + 1))))
        val n = when {
            a < 3.0 -> 1
            a < 8.0 -> 2
            a < 16.0 -> 3
            else -> 4
        }
        return min(n, maxSub)
    }

    /**
     * Lage der Mittellinie im Segment `i → i + 1` bei [f] in `0..1`
     * (Catmull-Rom durch die Proben; die Hoehe bleibt linear wie in
     * `WorldTrack.poseAt`). Schreibt nach [out]: `e, n, u, te, tn` (Tangente
     * als Einheitsvektor).
     */
    fun centerAt(track: TrackLine, i: Int, f: Double, out: DoubleArray) {
        val t = track.track
        val j = i.coerceIn(0, max(0, t.sampleCount - 2))
        for (c in 0..1) {
            val p0 = pe(t, j - 1, c == 1)
            val p1 = pe(t, j, c == 1)
            val p2 = pe(t, j + 1, c == 1)
            val p3 = pe(t, j + 2, c == 1)
            val f2 = f * f
            val f3 = f2 * f
            out[c] = 0.5 * (2 * p1 + (-p0 + p2) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (-p0 + 3 * p1 - 3 * p2 + p3) * f3)
            out[3 + c] = 0.5 * ((-p0 + p2) + 2 * (2 * p0 - 5 * p1 + 4 * p2 - p3) * f + 3 * (-p0 + 3 * p1 - 3 * p2 + p3) * f2)
        }
        val len = hypot(out[3], out[4])
        if (len > 1e-9) {
            out[3] /= len
            out[4] /= len
        } else {
            out[3] = 0.0
            out[4] = 1.0
        }
        out[2] = t.u[j] + (t.u[min(j + 1, t.sampleCount - 1)] - t.u[j]) * f
    }

    /** Wie [centerAt], aber am Laufmeter [distM]. */
    fun centerAtDist(track: TrackLine, distM: Double, out: DoubleArray) {
        val x = (distM / track.stepM).coerceIn(0.0, (track.count - 1).toDouble())
        val i = min(floor(x).toInt(), max(0, track.count - 2))
        centerAt(track, i, x - i, out)
    }

    /**
     * Begrenzt den seitlichen Versatz [t] (positiv = rechts) so, dass sich
     * Querschnitte an der Innenseite einer Kurve mit Kruemmung [kappa] nicht
     * ueberschneiden.
     */
    fun limitOffset(t: Double, kappa: Double): Double {
        if (t * kappa <= 0) return t
        val r = 0.8 / abs(kappa)
        return if (abs(t) <= r) t else if (t > 0) r else -r
    }

    // -----------------------------------------------------------------------
    // Andere Teile derselben Strecke (Hin- und Rueckweg, Kehren, Kreuzungen)
    // -----------------------------------------------------------------------

    /** Die Segmente der Strecke in der Naehe einer Kachel, fuer die Suche nach **anderen** Streckenteilen. */
    class Neighbourhood(private val track: TrackLine, spec: TileSpec, marginM: Double = 40.0) {
        private val segs: IntArray

        init {
            val t = track.track
            val list = ArrayList<Int>()
            for (i in 0 until t.sampleCount - 1) {
                if (spec.distanceTo(t.e[i].toDouble(), t.n[i].toDouble()) <= marginM) list.add(i)
            }
            segs = list.toIntArray()
        }

        /**
         * Naechster Streckenteil, dessen Laufmeter um mehr als [minGapM] von
         * [distM] abweicht. Ergebnis in [out]: `out[0]` Abstand zur
         * Mittellinie (unendlich, wenn es keinen gibt), `out[1]` Index des
         * Segments, `out[2]` halbe Wegbreite dort.
         */
        fun other(e: Double, n: Double, distM: Double, minGapM: Double, out: DoubleArray) {
            val t = track.track
            var best = Double.MAX_VALUE
            var bestI = -1
            for (i in segs) {
                if (abs((i + 0.5) * track.stepM - distM) <= minGapM) continue
                val ax = t.e[i].toDouble()
                val ay = t.n[i].toDouble()
                val dx = t.e[i + 1] - ax
                val dy = t.n[i + 1] - ay
                val l2 = dx * dx + dy * dy
                val f = if (l2 > 0) (((e - ax) * dx + (n - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val px = ax + dx * f - e
                val py = ay + dy * f - n
                val d2 = px * px + py * py
                if (d2 < best) {
                    best = d2
                    bestI = i
                }
            }
            if (bestI < 0) {
                out[0] = Double.POSITIVE_INFINITY
                out[1] = -1.0
                out[2] = 0.0
            } else {
                out[0] = sqrt(best)
                out[1] = bestI.toDouble()
                out[2] = track.halfWidthAt((bestI + 0.5) * track.stepM)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rauschen in Weltkoordinaten (ueber Kachelgrenzen stetig)
    // -----------------------------------------------------------------------

    /** Grobe Helligkeitsvariation `0..1` einer Stelle — fuer Vertexfarben, die die Kachelung der Texturen brechen. */
    fun tone(e: Double, n: Double, seed: Int): Double {
        val a = Noise.fbm(e / 37.0, n / 37.0, seed, 3)
        val b = Noise.fbm(e / 9.0, n / 9.0, seed + 17, 2)
        return (0.5 + 0.42 * a + 0.2 * b).coerceIn(0.0, 1.0)
    }

    /** Vertexfarbe aus linearen Kanaelen `0..1` (so, wie `COLOR_0` multipliziert) und Deckung. */
    fun linColor(r: Double, g: Double, b: Double, a: Double = 1.0): Int = Argb.of(
        (a * 255 + 0.5).toInt(), (r * 255 + 0.5).toInt(), (g * 255 + 0.5).toInt(), (b * 255 + 0.5).toInt(),
    )
}

// ---------------------------------------------------------------------------
// Uebrige Strassen
// ---------------------------------------------------------------------------

/**
 * Wie eine Strasse der Karte gezeichnet wird.
 *
 * @property rank Rang fuer die Hoehenstaffel an Kreuzungen (hoeher liegt oben).
 */
internal class RoadSpec(val kind: SurfaceKind, val widthM: Double, val rank: Int, val rail: Boolean = false)

/** Zeichenvorschrift einer Strasse aus Klasse und Belag; `null` = wird nicht gezeichnet (Tunnel, Faehre, Baustelle …). */
internal fun roadSpec(r: MapRoad): RoadSpec? {
    if (r.brunnel == "tunnel") return null
    val kind = surfaceKindFromVectorTile(r.cls, r.surface).visual
    return when (r.cls) {
        "motorway" -> RoadSpec(SurfaceKind.ASPHALT, 9.0, 8)
        "trunk" -> RoadSpec(SurfaceKind.ASPHALT, 8.0, 7)
        "primary" -> RoadSpec(kind, 7.0, 6)
        "secondary" -> RoadSpec(kind, 6.5, 5)
        "tertiary" -> RoadSpec(kind, 6.0, 4)
        "minor", "raceway" -> RoadSpec(kind, 4.8, 3)
        "busway" -> RoadSpec(kind, 3.5, 3)
        "service" -> RoadSpec(kind, if (r.service == "driveway" || r.service == "parking_aisle") 2.8 else 3.2, 2)
        "track" -> RoadSpec(kind, 2.8, 1)
        "path" -> when (r.subclass) {
            "steps", "platform", "corridor" -> null
            "cycleway" -> RoadSpec(if (r.surface == null) SurfaceKind.ASPHALT else kind, 2.2, 0)
            "footway", "pedestrian" -> RoadSpec(kind, 1.8, 0)
            else -> RoadSpec(if (r.surface == null) SurfaceKind.DIRT else kind, 1.2, 0)
        }
        "rail" -> if (r.service == null && (r.subclass == null || r.subclass == "rail" || r.subclass == "light_rail" || r.subclass == "narrow_gauge")) {
            RoadSpec(SurfaceKind.GRAVEL, 3.4, 9, rail = true)
        } else {
            null
        }
        else -> null
    }
}

/**
 * Die uebrigen Strassen im Umkreis einer Kachel, in gleichmaessigen Schritten
 * abgetastet. Stuecke, die mit der befahrenen Strecke zusammenfallen (die
 * Strecke steht ja selbst in den Kartendaten), sind als [Road.onTrack]
 * markiert und zaehlen weder als Hindernis noch werden sie gezeichnet.
 */
internal class RoadNet(ctx: TileContext, marginM: Double = 24.0, maxStepM: Double = 6.0) {
    class Road(
        val source: MapRoad,
        val spec: RoadSpec,
        /** Stationen: Lage, Laufmeter ab Linienanfang, Einheitsvektor nach rechts (Gehrung), Gehrungsfaktor. */
        val e: DoubleArray,
        val n: DoubleArray,
        val s: DoubleArray,
        val re: DoubleArray,
        val rn: DoubleArray,
        val miter: DoubleArray,
        /** Station liegt auf der befahrenen Strecke und laeuft mit ihr. */
        val onTrack: BooleanArray,
        /** Abstand der Station zur Mittellinie der Strecke (gross, wenn fern). */
        val trackDist: DoubleArray,
    ) {
        val size: Int get() = e.size
        val halfWidth: Double get() = spec.widthM / 2
        var minE = Double.MAX_VALUE
        var minN = Double.MAX_VALUE
        var maxE = -Double.MAX_VALUE
        var maxN = -Double.MAX_VALUE

        init {
            for (i in e.indices) {
                minE = min(minE, e[i]); maxE = max(maxE, e[i]); minN = min(minN, n[i]); maxN = max(maxN, n[i])
            }
        }

        /** Segment `i → i + 1` faellt mit der Strecke zusammen. */
        fun segOnTrack(i: Int): Boolean = onTrack[i] && onTrack[i + 1]
    }

    val roads: List<Road>

    init {
        val s = ctx.spec
        val raw = ctx.map.roads(s.minE - marginM, s.minN - marginM, s.maxE + marginM, s.maxN + marginM)
        val list = ArrayList<Road>()
        for (r in raw) {
            val spec = roadSpec(r) ?: continue
            if (r.line.size < 4) continue
            list.add(sample(ctx.track, r, spec, maxStepM) ?: continue)
        }
        roads = list
    }

    /**
     * Freier Abstand der Stelle zum Rand der naechsten Strasse (negativ = auf
     * der Strasse); Stuecke auf der Strecke und Bahnen zaehlen mit, wenn
     * [withRail]. Unendlich, wenn keine in der Naehe ist.
     */
    fun clearance(e: Double, n: Double, withRail: Boolean = true): Double {
        var best = Double.POSITIVE_INFINITY
        for (r in roads) {
            if (r.spec.rail && !withRail) continue
            val reach = min(best, 60.0) + r.halfWidth
            if (e < r.minE - reach || e > r.maxE + reach || n < r.minN - reach || n > r.maxN + reach) continue
            for (i in 0 until r.size - 1) {
                if (r.segOnTrack(i)) continue
                val ax = r.e[i]
                val ay = r.n[i]
                val dx = r.e[i + 1] - ax
                val dy = r.n[i + 1] - ay
                val l2 = dx * dx + dy * dy
                val f = if (l2 > 0) (((e - ax) * dx + (n - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                val d = hypot(ax + dx * f - e, ay + dy * f - n) - r.halfWidth
                if (d < best) best = d
            }
        }
        return best
    }

    private fun sample(track: TrackLine, r: MapRoad, spec: RoadSpec, maxStepM: Double): Road? {
        val line = r.line
        val cnt = line.size / 2
        val es = ArrayList<Double>()
        val ns = ArrayList<Double>()
        val ss = ArrayList<Double>()
        var run = 0.0
        for (i in 0 until cnt - 1) {
            val ax = line[2 * i]
            val ay = line[2 * i + 1]
            val bx = line[2 * i + 2]
            val by = line[2 * i + 3]
            val len = hypot(bx - ax, by - ay)
            if (len < 1e-6) continue
            val parts = max(1, ceil(len / maxStepM).toInt())
            for (k in 0 until parts) {
                es.add(ax + (bx - ax) * k / parts)
                ns.add(ay + (by - ay) * k / parts)
                ss.add(run + len * k / parts)
            }
            run += len
        }
        if (es.isEmpty()) return null
        es.add(line[2 * cnt - 2]); ns.add(line[2 * cnt - 1]); ss.add(run)
        val m = es.size
        val e = es.toDoubleArray()
        val n = ns.toDoubleArray()
        val re = DoubleArray(m)
        val rn = DoubleArray(m)
        val miter = DoubleArray(m)
        val on = BooleanArray(m)
        val td = DoubleArray(m)
        for (i in 0 until m) {
            val p = max(0, i - 1)
            val q = min(m - 1, i + 1)
            var ax = e[i] - e[p]
            var ay = n[i] - n[p]
            var bx = e[q] - e[i]
            var by = n[q] - n[i]
            val al = hypot(ax, ay)
            val bl = hypot(bx, by)
            if (al < 1e-9) {
                ax = bx; ay = by
            } else {
                ax /= al; ay /= al
            }
            if (bl < 1e-9) {
                bx = ax; by = ay
            } else {
                bx /= bl; by /= bl
            }
            var tx = ax + bx
            var ty = ay + by
            val tl = hypot(tx, ty)
            if (tl < 1e-6) {
                tx = bx; ty = by
            } else {
                tx /= tl; ty /= tl
            }
            re[i] = ty
            rn[i] = -tx
            val cosHalf = (tx * bx + ty * by).coerceIn(0.4, 1.0)
            miter[i] = 1.0 / cosHalf
            val hit = track.nearest(e[i], n[i], 60.0)
            if (hit == null) {
                td[i] = 1e9
            } else {
                td[i] = hit.distance
                val pose = track.poseAt(hit.distM)
                val h = Math.toRadians(pose.headingDeg)
                val par = abs(tx * kotlin.math.sin(h) + ty * kotlin.math.cos(h))
                on[i] = hit.distance < track.halfWidthAt(hit.distM) + 1.0 && par > 0.86
            }
        }
        // Einzelne Ausreisser glaetten: eine Station zwischen zwei Stationen auf der Strecke gehoert dazu.
        for (i in 1 until m - 1) if (!on[i] && on[i - 1] && on[i + 1] && td[i] < 6.0) on[i] = true
        // Enden eines Laufs auf der Strecke: Im Scheitel einer Kurve (Knick der Karte an einer Einmuendung) schneidet
        // die geglaettete Strecke die Ecke, der Knoten liegt bis ~2 m neben ihr und quer zu ihrer Richtung. Eine
        // Station neben einer Station auf der Strecke, die noch im Band der Strecke liegt, gehoert dazu — sonst
        // wurde das Endstueck der eigenen Wege als Strasse ueber die Fahrbahn gezeichnet und bekam Eckflaechen.
        var grew = true
        while (grew) {
            grew = false
            for (i in 0 until m) {
                if (on[i] || td[i] >= 1e8) continue
                val nb = (i > 0 && on[i - 1]) || (i < m - 1 && on[i + 1])
                if (!nb) continue
                val hit = track.nearest(e[i], n[i], 60.0) ?: continue
                if (hit.distance < track.halfWidthAt(hit.distM) + 1.0) {
                    on[i] = true
                    grew = true
                }
            }
        }
        return Road(r, spec, e, n, ss.toDoubleArray(), re, rn, miter, on, td)
    }
}
