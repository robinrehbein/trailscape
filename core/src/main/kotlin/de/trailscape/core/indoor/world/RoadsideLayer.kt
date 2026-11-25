package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Leitpfosten und Schutzplanken an der Strecke (Teil des Knotens `props`)
 *
 * Nach Regeln, wo die Karte nichts weiß (die Vektorkacheln kennen keine `barrier`):
 *
 *  * **Schutzplanke** (einfache Distanzschutzplanke, Holm 0,44–0,75 m, Pfosten alle 4 m) an
 *    Kraftverkehrsstraßen außerorts **auf Brücken** der Karte (mit 20 m Vorlauf davor und dahinter;
 *    auf der Brücke vor dem Geländer, ohne eigene Pfosten) und an **hohen, steilen Böschungen**:
 *    Gelände 6 m neben dem Fahrbahnrand mehr als 3 m tiefer oder 10 m daneben mehr als 4,5 m (mindestens
 *    15 m lang, Lücken bis 30 m geschlossen). An beiden Enden 12 m **Absenkung** in den Boden.
 *  * **Leitpfosten** an Kraftverkehrsstraßen außerorts mit Bankett, im Abstand nach Kurvenradius:
 *    50 m auf gerader Strecke, 25 m unter R 300 m, 16,7 m unter R 150 m, 12,5 m unter R 60 m (Teiler von
 *    50 m, damit das Raster stetig bleibt; Annahme in Anlehnung an die Praxis, nicht wörtlich nach Regelwerk).
 *    Nicht an einer Planke, nicht in Einmündungen, nicht auf Brücken.
 *
 * **Echte Daten gehen vor** ([RealRoadside], Detailtreue): Liegt eine echte Planke höchstens
 * [RealRoadside.GUARD_RAIL_MATCH_M] neben der Stelle, entfallen dort Regel-Planke und Leitpfosten (die
 * echte Planke zeichnet die Datenschicht); bei [RealRoadside.authoritative] entfallen alle Regel-Planken.
 *
 * Material wie die Kleinteile ([PropsLayer.MATERIAL], nur Vertexfarben). Jedes Teil gehört der Kachel,
 * in der sein Anfangs- bzw. Fußpunkt liegt; das Raster hängt nur vom Laufmeter ab.
 */
internal object RoadsideLayer {
    /** Zeichnet in [out] (Knoten `props`), höchstens [room] Dreiecke; gibt die Zahl zurück. */
    fun emit(ctx: TileContext, out: TileOutput, room: Int): Int {
        if (ctx.level != 0 || room <= 0) return 0
        return Builder(ctx, out, room).run()
    }

    const val RAIL_STEP_M = 4.0
    const val RAIL_OFF_M = 0.55
    const val RAIL_OFF_BRIDGE_M = 0.05
    const val RAMP_M = 12.0
    const val POST_GRID_M = 50.0 / 12.0

    /** Querschnitt des Holms: Höhe über dem Bankett und Ausbauchung zur Fahrbahn hin (W-Profil). */
    private val PROFILE_H = doubleArrayOf(0.44, 0.50, 0.595, 0.69, 0.75)
    private val PROFILE_B = doubleArrayOf(0.0, 0.045, 0.0, 0.045, 0.0)

    private class Builder(private val ctx: TileContext, private val out: TileOutput, private val room: Int) {
        private val track = ctx.track
        private val t = track.track
        private val step = track.stepM
        private val plan = MarkingPlan.of(track, ctx.land)
        private val real = ctx.map.roadside()
        private val net = RoadNet(ctx, marginM = 12.0)
        private val s = ctx.spec
        private val buildings = ctx.map.buildings(s.minE - 12.0, s.minN - 12.0, s.maxE + 12.0, s.maxN + 12.0)
        private val realRails = real.guardRails(s.minE - 30, s.minN - 30, s.maxE + 30, s.maxN + 30)
        private val mesh by lazy { out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL, roughness = 0.85f)) }
        private val scratch = MeshBuilder(64, 64)
        private val tmp = DoubleArray(5)
        private var used = 0

        /** Proben im Umkreis der Kachel (Regeln reichen bis 60 m weit, Rechnung bis 150 m). */
        private val i0: Int
        private val i1: Int

        init {
            var lo = Int.MAX_VALUE
            var hi = -1
            for (i in 0 until t.sampleCount) {
                if (s.distanceTo(t.e[i].toDouble(), t.n[i].toDouble()) <= 16.0) {
                    lo = min(lo, i)
                    hi = max(hi, i)
                }
            }
            val pad = ceil(150.0 / step).toInt()
            i0 = if (hi < 0) 0 else max(0, lo - pad)
            i1 = if (hi < 0) -1 else min(t.sampleCount - 1, hi + pad)
        }

        fun run(): Int {
            if (i1 < i0) return 0
            // Bei vollstaendigen echten Daten keine Regel-Planken — dann stehen dort auch Leitpfosten (ausser an echten Planken).
            val rails = if (real.authoritative) emptyList() else railIntervals()
            rails(rails)
            posts(rails)
            return used
        }

        private fun commit(): Boolean {
            val tris = scratch.triangleCount
            val ok = tris > 0 && used + tris <= room
            if (ok) {
                mesh.append(scratch)
                used += tris
            }
            scratch.clear()
            return ok
        }

        // --- Wo Planken stehen ------------------------------------------------------

        private class Rail(val from: Double, val to: Double, val side: Int, val bridgeFrom: Double, val bridgeTo: Double)

        private fun motorAt(i: Int): Boolean {
            val d = t.distAt(i)
            val sec = track.surfaceAt(d)
            return RouteLayer.isMotorRoad(sec) && sec.effectiveWidthM >= 4.5 && !plan.urbanAt(d)
        }

        private fun bridgeAt(i: Int): Boolean {
            val e = t.e[i].toDouble()
            val n = t.n[i].toDouble()
            val roads = ctx.map.roads(e - 10, n - 10, e + 10, n + 10).filter { it.brunnel == "bridge" }
            if (roads.isEmpty()) return false
            val a = max(0, i - 1)
            val b = min(t.sampleCount - 1, i + 1)
            var he = (t.e[b] - t.e[a]).toDouble()
            var hn = (t.n[b] - t.n[a]).toDouble()
            val l = hypot(he, hn)
            if (l > 1e-9) { he /= l; hn /= l } else { he = 0.0; hn = 1.0 }
            return nearestAligned(roads, e, n, he, hn, 8.0, 0.8) != null
        }

        private fun edgeH(d: Double): Double {
            RouteGeometry.centerAtDist(track, d, tmp)
            return tmp[2] - RouteLayer.crownOf(track.surfaceAt(d).kind) * track.halfWidthAt(d)
        }

        private fun embankment(i: Int, side: Int): Boolean {
            val d = t.distAt(i)
            RouteGeometry.centerAtDist(track, d, tmp)
            val hw = track.halfWidthAt(d)
            val top = tmp[2] - RouteLayer.crownOf(track.surfaceAt(d).kind) * hw
            val re = tmp[4] * side
            val rn = -tmp[3] * side
            val e = tmp[0]
            val n = tmp[1]
            val g6 = ctx.ground.heightAt(e + re * (hw + 6.0), n + rn * (hw + 6.0))
            val g10 = ctx.ground.heightAt(e + re * (hw + 10.0), n + rn * (hw + 10.0))
            return top - g6 > 3.0 || top - g10 > 4.5
        }

        /** Planken je Seite (±1) als Laufmeter-Bereiche, nur aus Proben in der Nähe — in jeder Kachel gleich. */
        private fun railIntervals(): List<Rail> {
            val n = i1 - i0 + 1
            val motor = BooleanArray(n) { motorAt(i0 + it) }
            val bridge = BooleanArray(n) { motor[it] && bridgeAt(i0 + it) }
            val out = ArrayList<Rail>()
            for (side in intArrayOf(-1, 1)) {
                val want = BooleanArray(n) { motor[it] && (bridge[it] || embankment(i0 + it, side)) }
                // Luecken bis 30 m schliessen.
                val gap = ceil(30.0 / step).toInt()
                var k = 0
                while (k < n) {
                    if (want[k]) { k++; continue }
                    var j = k
                    while (j + 1 < n && !want[j + 1]) j++
                    if (k > 0 && j < n - 1 && j - k + 1 <= gap) for (q in k..j) if (motor[q]) want[q] = true
                    k = j + 1
                }
                k = 0
                while (k < n) {
                    if (!want[k]) { k++; continue }
                    var j = k
                    while (j + 1 < n && want[j + 1]) j++
                    var bFrom = Double.MAX_VALUE
                    var bTo = -Double.MAX_VALUE
                    for (q in k..j) if (bridge[q]) { bFrom = min(bFrom, t.distAt(i0 + q) - step / 2); bTo = max(bTo, t.distAt(i0 + q) + step / 2) }
                    val hasBridge = bTo > bFrom
                    var a = t.distAt(i0 + k) - step / 2
                    var b = t.distAt(i0 + j) + step / 2
                    // Vorlauf an Bruecken, Absenkung an beiden Enden ausserhalb des eigentlichen Bereichs.
                    val lead = if (hasBridge) 20.0 else 4.0
                    a = max(0.0, a - lead - RAMP_M)
                    b = min(track.lengthM, b + lead + RAMP_M)
                    if (b - a >= 15.0 + 2 * RAMP_M || hasBridge) out.add(Rail(a, b, side, bFrom, bTo))
                    k = j + 1
                }
            }
            return out
        }

        /** Echte Planke ([RealRoadside]) in der Nähe dieser Stelle? */
        private fun realRailNear(e: Double, n: Double): Boolean {
            if (realRails.isEmpty()) return false
            for (l in realRails) {
                val k = l.size / 2
                for (i in 0 until k - 1) {
                    val ax = l[2 * i]; val ay = l[2 * i + 1]
                    val dx = l[2 * i + 2] - ax; val dy = l[2 * i + 3] - ay
                    val l2 = dx * dx + dy * dy
                    val f = if (l2 > 0) (((e - ax) * dx + (n - ay) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
                    if (hypot(ax + dx * f - e, ay + dy * f - n) <= RealRoadside.GUARD_RAIL_MATCH_M) return true
                }
            }
            return false
        }

        // --- Planken zeichnen -------------------------------------------------------

        private class RailPt(val e: Double, val n: Double, val y: Double, val fe: Double, val fn: Double, val k: Double, val onBridge: Boolean)

        private fun railPt(r: Rail, d: Double): RailPt {
            RouteGeometry.centerAtDist(track, d, tmp)
            val hw = track.halfWidthAt(d)
            val onBridge = d >= r.bridgeFrom && d <= r.bridgeTo
            val off = hw + if (onBridge) RAIL_OFF_BRIDGE_M else RAIL_OFF_M
            val re = tmp[4] * r.side
            val rn = -tmp[3] * r.side
            val k = smoothstep(r.from, r.from + RAMP_M, d) * (1 - smoothstep(r.to - RAMP_M, r.to, d))
            return RailPt(tmp[0] + re * off, tmp[1] + rn * off, edgeH(d) - 0.03, -re, -rn, k, onBridge)
        }

        private fun rails(rails: List<Rail>) {
            val steel = RouteGeometry.linColor(0.40, 0.41, 0.42)
            val lip = RouteGeometry.linColor(0.27, 0.28, 0.29)
            for (r in rails) {
                var k = floor(r.from / RAIL_STEP_M).toInt()
                while (k * RAIL_STEP_M < r.to) {
                    val d0 = max(r.from, k * RAIL_STEP_M)
                    val d1 = min(r.to, (k + 1) * RAIL_STEP_M)
                    k++
                    if (d1 - d0 < 0.3) continue
                    val a = railPt(r, d0)
                    if (!s.contains(a.e, a.n)) continue
                    val b = railPt(r, d1)
                    if (realRailNear(a.e, a.n) || net.clearance(a.e, a.n) < 0.3 || net.clearance(b.e, b.n) < 0.3) continue
                    // Holm: fuenf Reihen (W-Profil), Vorderseite zur Fahrbahn.
                    val first = scratch.vertexCount
                    for (p in listOf(a, b)) {
                        for (q in PROFILE_H.indices) {
                            val y = p.y + PROFILE_H[q] * p.k - (1 - p.k) * 0.3
                            val bulge = PROFILE_B[q] * p.k
                            // Normale: zur Fahrbahn, an Ober-/Unterkante etwas nach oben/unten geneigt.
                            val tilt = when (q) { 0 -> -0.5; 1 -> -0.2; 2 -> 0.0; 3 -> 0.2; else -> 0.5 }
                            val len = sqrt(1 + tilt * tilt)
                            scratch.vertex(
                                ctx.x(p.e + p.fe * bulge), ctx.y(y), ctx.z(p.n + p.fn * bulge),
                                p.fe / len, tilt / len, -p.fn / len, 0.0, 0.0, if (q == 0) lip else steel,
                            )
                        }
                    }
                    val m = PROFILE_H.size
                    for (q in 0 until m - 1) {
                        // Umlauf so, dass die Vorderseite zur Fahrbahn zeigt (Fahrbahn links: von unten nach oben, a → b).
                        val a0 = first + q
                        val a1 = first + q + 1
                        val b0 = first + m + q
                        val b1 = first + m + q + 1
                        if (facesRoad(a, b)) scratch.quad(a0, b0, b1, a1) else scratch.quad(a0, a1, b1, b0)
                    }
                    if (!commit()) return
                }
                // Pfosten alle 4 m hinter dem Holm (nicht auf der Bruecke: dort auf der Kappe verschraubt, ohne Pfosten).
                var j = ceil(r.from / RAIL_STEP_M).toInt()
                while (j * RAIL_STEP_M <= r.to) {
                    val d = j * RAIL_STEP_M
                    j++
                    val p = railPt(r, d)
                    if (p.onBridge || p.k < 0.35 || !s.contains(p.e, p.n)) continue
                    if (realRailNear(p.e, p.n) || net.clearance(p.e, p.n) < 0.3) continue
                    val be = p.e - p.fe * 0.20
                    val bn = p.n - p.fn * 0.20
                    val g = ctx.ground.heightAt(be, bn)
                    post(be, bn, p.fe, p.fn, min(g, p.y) - 0.15, p.y + 0.70 * p.k, RouteGeometry.linColor(0.33, 0.34, 0.35))
                    if (!commit()) return
                }
            }
        }

        /** Die Vierecke `a0 b0 b1 a1` zeigen zur Fahrbahn, wenn b in Fahrtrichtung vor a liegt und die Fahrbahn links davon ist. */
        private fun facesRoad(a: RailPt, b: RailPt): Boolean {
            val le = b.e - a.e
            val ln = b.n - a.n
            // In ENU: (le, ln, 0) × (0, 0, 1) = (ln, −le, 0).
            val ne = ln
            val nn = -le
            // Das Viereck a0 b0 b1 a1 (unten a → unten b → oben b → oben a) hat die Normale (ne, nn) — zeigt sie zur Fahrbahn?
            return ne * a.fe + nn * a.fn > 0
        }

        /** C-Pfosten (6 × 11 cm): drei Seiten (zur Fahrbahn und beide Stirnseiten). */
        private fun post(e: Double, n: Double, fe: Double, fn: Double, y0: Double, y1: Double, color: Int) {
            val ue = -fn
            val un = fe
            val w = 0.03
            val dd = 0.055
            // Ecken: vorn (zur Fahrbahn) links/rechts, hinten links/rechts.
            val fl = doubleArrayOf(e + fe * dd - ue * w, n + fn * dd - un * w)
            val fr = doubleArrayOf(e + fe * dd + ue * w, n + fn * dd + un * w)
            val bl = doubleArrayOf(e - fe * dd - ue * w, n - fn * dd - un * w)
            val br = doubleArrayOf(e - fe * dd + ue * w, n - fn * dd + un * w)
            face(fl, fr, y0, y1, fe, fn, color)
            face(bl, fl, y0, y1, -ue, -un, color)
            face(fr, br, y0, y1, ue, un, color)
        }

        /** Senkrechte Fläche zwischen [p] und [q], Vorderseite nach ([fe], [fn]). */
        private fun face(p: DoubleArray, q: DoubleArray, y0: Double, y1: Double, fe: Double, fn: Double, color: Int) {
            val a = scratch.vertex(ctx.x(p[0]), ctx.y(y0), ctx.z(p[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            scratch.vertex(ctx.x(q[0]), ctx.y(y0), ctx.z(q[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            scratch.vertex(ctx.x(q[0]), ctx.y(y1), ctx.z(q[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            scratch.vertex(ctx.x(p[0]), ctx.y(y1), ctx.z(p[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            // Umlauf: Das Viereck p0 q0 q1 p1 hat (in ENU, wie glTF rechtshaendig) die Normale ((q − p) × oben) =
            // (qn − pn, −(qe − pe)); zeigt sie nicht nach (fe, fn), wird umgedreht.
            val ne = q[1] - p[1]
            val nn = -(q[0] - p[0])
            if (ne * fe + nn * fn > 0) scratch.quad(a, a + 1, a + 2, a + 3) else scratch.quad(a + 1, a, a + 3, a + 2)
        }

        // --- Leitpfosten ------------------------------------------------------------

        private fun spacingAt(d: Double): Int {
            val i = (d / step).toInt()
            var kappa = 0.0
            val r = ceil(15.0 / step).toInt()
            for (q in i - r..i + r) if (q in 0 until t.sampleCount) kappa = max(kappa, abs(RouteGeometry.curvatureAt(track, q)))
            val radius = if (kappa > 1e-9) 1.0 / kappa else Double.MAX_VALUE
            return when {
                radius < 60.0 -> 3
                radius < 150.0 -> 4
                radius < 300.0 -> 6
                else -> 12
            }
        }

        private fun posts(rails: List<Rail>) {
            val from = t.distAt(i0)
            val to = t.distAt(i1)
            var k = ceil(from / POST_GRID_M).toInt()
            while (k * POST_GRID_M <= to) {
                val kk = k++
                val d = kk * POST_GRID_M
                if (d < 0 || d > track.lengthM) continue
                val i = min((d / step).toInt(), t.sampleCount - 1)
                if (!motorAt(i)) continue
                if (kk % spacingAt(d) != 0) continue
                val sec = track.surfaceAt(d)
                RouteGeometry.centerAtDist(track, d, tmp)
                val hw = track.halfWidthAt(d)
                for (side in intArrayOf(-1, 1)) {
                    val re = tmp[4] * side
                    val rn = -tmp[3] * side
                    val land = ctx.land.classAt(tmp[0] + re * (hw + 3.0), tmp[1] + rn * (hw + 3.0))
                    if (edgeStyleOf(sec, land) != EdgeStyle.BANKETT) continue
                    // Nicht an einer Planke (Regel oder echt).
                    if (rails.any { it.side == side && d >= it.from && d <= it.to }) continue
                    val off = hw + 0.75
                    val e = tmp[0] + re * off
                    val n = tmp[1] + rn * off
                    if (!s.contains(e, n) || realRailNear(e, n) || !free(e, n, 0.1)) continue
                    guidePost(e, n, tmp[3], tmp[4], re, rn, Noise.hash01(kk, side, 0x1e1))
                    if (!commit()) return
                }
            }
        }

        private fun free(e: Double, n: Double, radius: Double): Boolean {
            val hit = track.nearest(e, n, 30.0)
            if (hit != null && hit.distance < track.halfWidthAt(hit.distM) + 0.25 + radius) return false
            if (net.clearance(e, n) < 0.35 + radius) return false
            for (b in buildings) if (pointInRings(b.rings, e, n)) return false
            return true
        }

        /**
         * Leitpfosten (12 × 8 cm, 1 m hoch): weiß, schwarzes Schrägband zur Fahrbahn fallend, Rückstrahler auf
         * beiden Stirnseiten (rechts ein Rechteck, links zwei Punkte). Ohne Rückseite (von der Fahrbahn unsichtbar).
         */
        private fun guidePost(e: Double, n: Double, te: Double, tn: Double, re: Double, rn: Double, rnd: Double) {
            val g = ctx.ground.heightAt(e, n)
            val white = RouteGeometry.linColor(0.70 + 0.08 * rnd, 0.70 + 0.08 * rnd, 0.67 + 0.08 * rnd)
            val black = RouteGeometry.linColor(0.035, 0.035, 0.04)
            val refl = RouteGeometry.linColor(0.86, 0.86, 0.82)
            // fe/fn = zur Fahrbahn hin.
            val fe = -re
            val fn = -rn
            val w = 0.06
            val dd = 0.04
            val slant = 0.05
            fun corners(): Array<DoubleArray> = arrayOf(
                doubleArrayOf(e + fe * w - te * dd, n + fn * w - tn * dd), // vorn, hinten in Fahrtrichtung
                doubleArrayOf(e + fe * w + te * dd, n + fn * w + tn * dd), // vorn, vorn
                doubleArrayOf(e - fe * w + te * dd, n - fn * w + tn * dd), // hinten (feldseitig), vorn
                doubleArrayOf(e - fe * w - te * dd, n - fn * w - tn * dd), // hinten, hinten
            )
            val c = corners()
            // Drei Bänder: weiß, schwarz (schräg: zur Fahrbahn tiefer), weiß.
            val bands = listOf(
                doubleArrayOf(g - 0.02, g - 0.02, g + 0.66 - slant, g + 0.66 + slant) to white,
                doubleArrayOf(g + 0.66 - slant, g + 0.66 + slant, g + 0.86 - slant, g + 0.86 + slant) to black,
                doubleArrayOf(g + 0.86 - slant, g + 0.86 + slant, g + 0.98, g + 1.0) to white,
            )
            for ((y, col) in bands) {
                // y: unten vorn, unten hinten, oben vorn, oben hinten (vorn = zur Fahrbahn).
                face2(c[0], c[1], y[0], y[0], y[2], y[2], fe, fn, col) // Fahrbahnseite
                face2(c[1], c[2], y[0], y[1], y[2], y[3], te, tn, col) // Stirn in Fahrtrichtung
                face2(c[3], c[0], y[1], y[0], y[3], y[2], -te, -tn, col) // Stirn gegen Fahrtrichtung
            }
            // Deckel.
            val top = scratch.vertexCount
            scratch.vertex(ctx.x(c[0][0]), ctx.y(g + 0.98), ctx.z(c[0][1]), 0.0, 1.0, 0.0, 0.0, 0.0, white)
            scratch.vertex(ctx.x(c[1][0]), ctx.y(g + 0.98), ctx.z(c[1][1]), 0.0, 1.0, 0.0, 0.0, 0.0, white)
            scratch.vertex(ctx.x(c[2][0]), ctx.y(g + 1.0), ctx.z(c[2][1]), 0.0, 1.0, 0.0, 0.0, 0.0, white)
            scratch.vertex(ctx.x(c[3][0]), ctx.y(g + 1.0), ctx.z(c[3][1]), 0.0, 1.0, 0.0, 0.0, 0.0, white)
            // Von oben gegen den Uhrzeigersinn (ENU): Reihenfolge aus dem Kreuzprodukt.
            val ax = c[1][0] - c[0][0]; val ay = c[1][1] - c[0][1]
            val bx = c[3][0] - c[0][0]; val by = c[3][1] - c[0][1]
            if (ax * by - ay * bx > 0) scratch.quad(top, top + 1, top + 2, top + 3) else scratch.quad(top + 3, top + 2, top + 1, top)
            // Rueckstrahler: auf der Stirn in Fahrtrichtung sieht ihn der Gegenverkehr, auf der anderen der Fahrer.
            for (dir in intArrayOf(-1, 1)) {
                val ce = e + te * dir * (dd + 0.004)
                val cn = n + tn * dir * (dd + 0.004)
                // Von vorn gesehen: steht der Pfosten rechts des Betrachters, ein Rechteck; links zwei Punkte.
                val rightOfViewer = -dir * sideSign(te, tn, re, rn) > 0
                val ue = fe
                val un = fn
                if (rightOfViewer) {
                    plate(ce, cn, ue, un, te * dir, tn * dir, 0.02, g + 0.68, g + 0.84, refl)
                } else {
                    plate(ce, cn, ue, un, te * dir, tn * dir, 0.02, g + 0.69, g + 0.745, refl)
                    plate(ce, cn, ue, un, te * dir, tn * dir, 0.02, g + 0.775, g + 0.83, refl)
                }
            }
        }

        /** +1, wenn (re, rn) rechts der Fahrtrichtung (te, tn) liegt, sonst −1. */
        private fun sideSign(te: Double, tn: Double, re: Double, rn: Double): Double = if (te * rn - tn * re < 0) 1.0 else -1.0

        /** Senkrechte Fläche p → q mit Höhen unten/oben je Ende, Vorderseite nach ([fe], [fn]). */
        private fun face2(p: DoubleArray, q: DoubleArray, yp0: Double, yq0: Double, yp1: Double, yq1: Double, fe: Double, fn: Double, color: Int) {
            val a = scratch.vertex(ctx.x(p[0]), ctx.y(yp0), ctx.z(p[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            scratch.vertex(ctx.x(q[0]), ctx.y(yq0), ctx.z(q[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            scratch.vertex(ctx.x(q[0]), ctx.y(yq1), ctx.z(q[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            scratch.vertex(ctx.x(p[0]), ctx.y(yp1), ctx.z(p[1]), fe, 0.0, -fn, 0.0, 0.0, color)
            val ne = q[1] - p[1]
            val nn = -(q[0] - p[0])
            if (ne * fe + nn * fn > 0) scratch.quad(a, a + 1, a + 2, a + 3) else scratch.quad(a + 1, a, a + 3, a + 2)
        }

        /** Senkrechte Platte um ([e], [n]) entlang ([ue], [un]), halbe Breite [w], Vorderseite nach ([fe], [fn]). */
        private fun plate(e: Double, n: Double, ue: Double, un: Double, fe: Double, fn: Double, w: Double, y0: Double, y1: Double, color: Int) {
            face(doubleArrayOf(e - ue * w, n - un * w), doubleArrayOf(e + ue * w, n + un * w), y0, y1, fe, fn, color)
        }
    }
}
