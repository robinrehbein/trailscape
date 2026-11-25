package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * # Fahrbahnmarkierung der Strecke (Plan)
 *
 * Welche Linien wo liegen, nach den Richtlinien für die Markierung von Straßen (RMS) **sinngemäß** —
 * abgeleitet nur aus dem, was die Daten hergeben: Wegklasse (`highway`), Belag, Breite, Landnutzung
 * (innerorts/außerorts) und der Geometrie der Strecke (Lage und Höhe, daraus die Sichtweite).
 *
 * | Linie | Regel | Maße |
 * |---|---|---|
 * | Leitlinie (Z 340) | Kraftverkehrsstraße mit festem Belag ([RouteLayer.isMotorRoad]); außerorts ab 5,5 m (Bundes-/Landes-/Kreisstraße) bzw. 6,0 m (Gemeindestraße), innerorts nur Durchgangsstraßen (ab `tertiary`) ab 6,0 m | außerorts 6 m Strich / 12 m Lücke, innerorts 3 m / 6 m, 12 cm |
 * | Fahrstreifenbegrenzung (Z 295) | wo die Sichtweite in einer der beiden Richtungen unter [sightNeeded] liegt (Kurve, Kuppe, Wald/Ort an der Innenseite); kurze Lücken dazwischen werden geschlossen | 12 cm, durchgezogen |
 * | Warnlinie | vor und hinter jeder Fahrstreifenbegrenzung | außerorts 6 m / 3 m über 27 m, innerorts 3 m / 1,5 m über 18 m |
 * | Randlinie | außerorts: `secondary` und höher ab 6,0 m, `tertiary` ab 6,0 m, Gemeindestraßen ab 6,5 m; nie innerorts (Bordstein) | 12 cm, 25 cm vom Fahrbahnrand; an Einmündungen unterbrochen ([RoadDecals]) |
 *
 * Sichtweite: von einem Punkt der Strecke (Augenhöhe 1 m) bis zum entferntesten Punkt in
 * Fahrtrichtung (Zielhöhe 1 m, Gegenverkehr), der noch zu sehen ist — die Sichtlinie darf die
 * Fahrbahn (+ 0,25 m) nicht schneiden und seitlich nicht weiter als bis zum Rand des freien
 * Raums neben der Fahrbahn ausweichen: 3 m, wo an der Kurveninnenseite Wald, Wohn- oder
 * Gewerbefläche liegt, sonst 20 m (Feld, Wiese). Gebäude, Hecken und einzelne Bäume kennt die
 * Rechnung nicht. Die Schwelle ist eine Annahme in der Größe der Überholsichtweiten auf Landstraßen,
 * keine Bemessung.
 *
 * Der Plan hängt nur von der Strecke und der Landnutzung ab — in jeder Kachel gleich — und wird je
 * Strecke einmal gerechnet ([of]).
 */
internal class MarkingPlan private constructor(private val track: TrackLine, land: LandMap) {
    enum class Centre { NONE, DASHED, WARN, SOLID }

    private val count = track.count

    /** Je Segment `i → i + 1`: Art der Mittellinie. */
    val centre: Array<Centre> = Array(max(0, count - 1)) { Centre.NONE }

    /** Je Segment: Randlinien. */
    val edges = BooleanArray(max(0, count - 1))

    /** Je Segment: innerorts (Wohn-/Gewerbefläche an mindestens einer Seite, über ±25 m gemittelt). */
    val urban = BooleanArray(max(0, count - 1))

    /** Sichtweite in Fahrtrichtung und dagegen (nur, wo eine Mittellinie liegt; sonst 0). */
    val sightFwd = DoubleArray(count)
    val sightBack = DoubleArray(count)

    init {
        if (count >= 2) build(land)
    }

    private fun build(land: LandMap) {
        val t = track.track
        val n = count - 1
        // Landnutzung je Probe und Seite: innerorts, Sichthindernis an dieser Seite.
        val urbanRaw = BooleanArray(count)
        val blocked = Array(2) { BooleanArray(count) }
        val tmp = DoubleArray(5)
        for (i in 0 until count) {
            val d = t.distAt(i)
            RouteGeometry.centerAtDist(track, d, tmp)
            val hw = track.halfWidthAt(d)
            var u = false
            for (side in 0..1) {
                val sgn = if (side == 0) -1.0 else 1.0
                val off = (hw + 4.0) * sgn
                val c = land.classAt(tmp[0] + tmp[4] * off, tmp[1] - tmp[3] * off)
                if (c == LandClass.RESIDENTIAL || c == LandClass.INDUSTRIAL) u = true
                blocked[side][i] = c == LandClass.FOREST || c == LandClass.RESIDENTIAL || c == LandClass.INDUSTRIAL
            }
            urbanRaw[i] = u
        }
        val reach = ceil(25.0 / track.stepM).toInt()
        for (i in 0 until n) {
            var k = 0
            var m = 0
            for (q in i - reach..i + 1 + reach) {
                if (q < 0 || q >= count) continue
                m++
                if (urbanRaw[q]) k++
            }
            urban[i] = m > 0 && 2 * k > m
        }
        val wants = BooleanArray(n)
        for (i in 0 until n) {
            val sec = t.surfaces[RouteGeometry.segSection(track, i)]
            wants[i] = wantsCentre(sec, urban[i])
            edges[i] = wantsEdges(sec, urban[i])
        }
        // Sichtweiten nur, wo eine Mittellinie liegt (halbe Breiten je Probe vorab: die innere Schleife laeuft oft).
        val hws = DoubleArray(count) { track.halfWidthAt(t.distAt(it)) }
        for (i in 0 until count) {
            val need = (i < n && wants[i]) || (i > 0 && wants[i - 1])
            if (!need) continue
            sightFwd[i] = sight(i, +1, blocked, hws)
            sightBack[i] = sight(i, -1, blocked, hws)
        }
        // Grundzustand je Segment.
        for (i in 0 until n) {
            if (!wants[i]) continue
            val sec = t.surfaces[RouteGeometry.segSection(track, i)]
            val req = sightNeeded(sec, urban[i])
            val s = min(min(sightFwd[i], sightBack[i]), min(sightFwd[i + 1], sightBack[i + 1]))
            centre[i] = if (s < req) Centre.SOLID else Centre.DASHED
        }
        val step = track.stepM
        // Kurze Lücken zwischen zwei Begrenzungen schließen (keine einzelnen Striche zwischen zwei Kurven).
        fillGaps(Centre.DASHED, Centre.SOLID) { i -> (if (urban[i]) 40.0 else 110.0) / step }
        // Begrenzung an beiden Enden um 10 m verlängern (Sichtweite am Kurvenbeginn).
        val ext = ceil(10.0 / step).toInt()
        val solid0 = BooleanArray(n) { centre[it] == Centre.SOLID }
        for (i in 0 until n) {
            if (!solid0[i]) continue
            for (q in i - ext..i + ext) if (q in 0 until n && centre[q] == Centre.DASHED) centre[q] = Centre.SOLID
        }
        // Warnlinie vor und hinter jeder Begrenzung.
        for (i in 0 until n) {
            if (centre[i] != Centre.SOLID) continue
            val w = ceil((if (urban[i]) 18.0 else 27.0) / step).toInt()
            if (i == 0 || centre[i - 1] != Centre.SOLID) for (q in i - w until i) if (q >= 0 && centre[q] == Centre.DASHED) centre[q] = Centre.WARN
            if (i == n - 1 || centre[i + 1] != Centre.SOLID) for (q in i + 1..i + w) if (q < n && centre[q] == Centre.DASHED) centre[q] = Centre.WARN
        }
    }

    /** Ersetzt Läufe von [what] zwischen zwei [by], die kürzer als [maxLen] Segmente sind, durch [by]. */
    private inline fun fillGaps(what: Centre, by: Centre, maxLen: (Int) -> Double) {
        val n = centre.size
        var i = 0
        while (i < n) {
            if (centre[i] != what) { i++; continue }
            var j = i
            while (j + 1 < n && centre[j + 1] == what) j++
            val before = i > 0 && centre[i - 1] == by
            val after = j < n - 1 && centre[j + 1] == by
            if (before && after && (j - i + 1) < maxLen(i)) for (q in i..j) centre[q] = by
            i = j + 1
        }
    }

    /**
     * Sichtweite ab Probe [i] in Richtung [dir] (±1), höchstens [MAX_SIGHT_M]: der Abstand bis zur ersten
     * Probe, deren Sichtlinie von [i] aus durch die Fahrbahn oder seitlich aus dem freien Raum läuft.
     */
    private fun sight(i: Int, dir: Int, blocked: Array<BooleanArray>, hws: DoubleArray): Double {
        val t = track.track
        val ei = t.e[i].toDouble()
        val ni = t.n[i].toDouble()
        val ui = t.u[i] + EYE_M
        val di = t.distAt(i)
        var k = i + dir
        var lastOk = di
        while (k in 0 until count) {
            val dk = t.distAt(k)
            if (abs(dk - di) > MAX_SIGHT_M) return MAX_SIGHT_M
            val ek = t.e[k].toDouble()
            val nk = t.n[k].toDouble()
            val uk = t.u[k] + EYE_M
            val dx = ek - ei
            val dy = nk - ni
            val len = hypot(dx, dy)
            var ok = true
            if (len > 1e-6) {
                var j = i + dir
                while (j != k) {
                    val ej = t.e[j] - ei
                    val nj = t.n[j] - ni
                    // Seitlicher Abstand der Probe j von der Sichtlinie (positiv = j links der Sichtlinie in deren
                    // Richtung, die Sichtlinie also rechts von j).
                    val cross = (dx * nj - dy * ej) / len
                    val along = ((dx * ej + dy * nj) / len / len).coerceIn(0.0, 1.0)
                    val hw = hws[j]
                    // Seite in Fahrtrichtung der Strecke (Kurveninnenseite): Bei dir = −1 laeuft die Sichtlinie gegen sie.
                    val lineRightOfJ = if (dir > 0) cross > 0 else cross < 0
                    val side = if (lineRightOfJ) 1 else 0
                    val free = hw + if (blocked[side][j]) 3.0 else 20.0
                    if (abs(cross) > free) { ok = false; break }
                    val hLine = ui + (uk - ui) * along
                    if (hLine < t.u[j] + 0.25) { ok = false; break }
                    j += dir
                }
            }
            if (!ok) return abs(lastOk - di)
            lastOk = dk
            k += dir
        }
        return MAX_SIGHT_M
    }

    /** Striche/Abschnitte der Mittellinie im Laufmeter-Bereich [from]..[to]: `(von, bis)` je Strich. */
    fun centreDashes(from: Double, to: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        if (count < 2) return out
        val step = track.stepM
        val i0 = max(0, floor(from / step).toInt() - 1)
        val i1 = min(centre.size - 1, ceil(to / step).toInt())
        var i = i0
        while (i <= i1) {
            val c = centre[i]
            if (c == Centre.NONE) { i++; continue }
            val u = urban[i]
            var j = i
            while (j + 1 <= i1 && centre[j + 1] == c && urban[j + 1] == u) j++
            val a = max(from, track.track.distAt(i))
            val b = min(to, track.track.distAt(j + 1))
            if (b > a) when (c) {
                Centre.SOLID -> out.add(doubleArrayOf(a, b))
                Centre.DASHED -> pattern(a, b, if (u) 3.0 else 6.0, if (u) 9.0 else 18.0, out)
                Centre.WARN -> pattern(a, b, if (u) 3.0 else 6.0, if (u) 4.5 else 9.0, out)
                Centre.NONE -> {}
            }
            i = j + 1
        }
        return out
    }

    /** Randlinien-Abschnitte im Bereich (durchgehend; Einmündungen schneidet [RoadDecals] heraus). */
    fun edgeRuns(from: Double, to: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        if (count < 2) return out
        val step = track.stepM
        val i0 = max(0, floor(from / step).toInt() - 1)
        val i1 = min(edges.size - 1, ceil(to / step).toInt())
        var i = i0
        while (i <= i1) {
            if (!edges[i]) { i++; continue }
            var j = i
            while (j + 1 <= i1 && edges[j + 1]) j++
            val a = max(from, track.track.distAt(i))
            val b = min(to, track.track.distAt(j + 1))
            if (b > a) out.add(doubleArrayOf(a, b))
            i = j + 1
        }
        return out
    }

    fun centreAt(distM: Double): Centre {
        if (centre.isEmpty()) return Centre.NONE
        return centre[min(centre.size - 1, max(0, (distM / track.stepM).toInt()))]
    }

    fun urbanAt(distM: Double): Boolean {
        if (urban.isEmpty()) return false
        return urban[min(urban.size - 1, max(0, (distM / track.stepM).toInt()))]
    }

    companion object {
        const val EYE_M = 1.0
        const val MAX_SIGHT_M = 420.0

        private val cache = WeakHashMap<TrackLine, MarkingPlan>()

        /** Der Plan der Strecke (je Strecke einmal gerechnet, threadsicher). */
        fun of(track: TrackLine, land: LandMap): MarkingPlan {
            synchronized(cache) { cache[track]?.let { return it } }
            val p = MarkingPlan(track, land)
            synchronized(cache) { return cache.getOrPut(track) { p } }
        }

        /** Für Tests: ohne Zwischenspeicher. */
        internal fun build(track: TrackLine, land: LandMap): MarkingPlan = MarkingPlan(track, land)

        /** Rang der Wegklasse (wie [roadSpec]): 6 primary … 3 Gemeindestraße, 2 service, 1 track, 0 Wege. */
        fun rankOf(s: SurfaceSection): Int = when (s.highway?.removeSuffix("_link")) {
            "motorway" -> 8
            "trunk" -> 7
            "primary" -> 6
            "secondary" -> 5
            "tertiary" -> 4
            "unclassified", "residential", "road", "living_street", "minor" -> 3
            "service" -> 2
            "track" -> 1
            null -> if (s.effectiveWidthM >= 5.5) 4 else 3
            else -> 0
        }

        fun wantsCentre(s: SurfaceSection, urban: Boolean): Boolean {
            if (!RouteLayer.isMotorRoad(s)) return false
            val r = rankOf(s)
            val w = s.effectiveWidthM
            return if (urban) r >= 4 && w >= 6.0 else w >= (if (r >= 4) 5.5 else 6.0)
        }

        fun wantsEdges(s: SurfaceSection, urban: Boolean): Boolean {
            if (urban || !RouteLayer.isMotorRoad(s)) return false
            val r = rankOf(s)
            val w = s.effectiveWidthM
            return w >= (if (r >= 4) 6.0 else 6.5)
        }

        /** Benötigte Sichtweite für eine unterbrochene Mittellinie. */
        fun sightNeeded(s: SurfaceSection, urban: Boolean): Double = when {
            urban -> 70.0
            rankOf(s) >= 5 -> 400.0
            else -> 300.0
        }

        /** Striche der Länge [dash] im Raster [period] (Phase an den Laufmetern), geschnitten auf [a]..[b]. */
        internal fun pattern(a: Double, b: Double, dash: Double, period: Double, out: MutableList<DoubleArray>) {
            var k = floor(a / period).toInt() - 1
            while (true) {
                k++
                val s0 = k * period
                if (s0 >= b) break
                val x0 = max(a, s0)
                val x1 = min(b, s0 + dash)
                if (x1 - x0 >= 0.8) out.add(doubleArrayOf(x0, x1))
            }
        }

        /** Belag mit Markierung (Asphalt, Beton). */
        fun paved(kind: SurfaceKind): Boolean = kind.visual == SurfaceKind.ASPHALT || kind.visual == SurfaceKind.CONCRETE
    }
}
