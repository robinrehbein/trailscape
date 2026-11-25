package de.trailscape.core.indoor.world

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * # Echte Punktobjekte am Weg (Detailtreue)
 *
 * Die Kleinteile von [PropsLayer] sind bis auf Ortstafel und Bruecke **erfunden** (Zaeune, Baenke,
 * Wegweiser an 45 % der Einmuendungen …). Die Vektorkacheln fuehren aber eine POI-Ebene mit Dingen, die
 * man sich auf einer bekannten Runde merkt — diese Schicht stellt sie **an ihrer echten Stelle** auf
 * (`docs/indoor/detailtreue.md`, Mangel 5). Einfache Modelle aus Vertexfarben im Material von
 * [PropsLayer] (kein Zeichenaufruf mehr), Vorrang vor den erfundenen Kleinteilen (laeuft vorher;
 * [PropsLayer] rechnet das Verbrauchte ins Budget).
 *
 * | POI (`class`/`subclass`) | Modell | Lage |
 * |---|---|---|
 * | `bus`/`bus_stop` | Haltestellenmast mit gruen-gelbem H-Schild | am Punkt; liegt er auf einer Fahrbahn, an deren Rand |
 * | `lift_gate` | Schranke, Baum hochgeklappt | Pfosten am Rand des Wegs |
 * | `gate` | Tor: zwei Pfosten, Torfluegel offen am Rand | quer zum Weg |
 * | `cycle_barrier` | Umlaufsperre: zwei rot-weisse Buegel von den Raendern, Mitte frei | quer zum Weg |
 * | `bollard` | Poller | nur abseits der Strecke (auf der Strecke stuende er im Fahrweg) |
 * | `information`/`guidepost` | Wanderwegweiser mit zwei Pfeilen | am Punkt, an den Wegrand geschoben |
 * | `information`/`board`, `map` | Infotafel auf zwei Holzpfosten | am Punkt |
 * | `shelter` | Schutzhuette (Holz, offen zur Strecke) | nur ohne Gebaeudegrundriss an der Stelle |
 *
 * Nicht gezeigt: Kirchen (`place_of_worship`) — mit Laser ohnehin als LoD2-Koerper, ohne Laser steht der
 * Grundriss, aber die Vektorkacheln nennen weder Turm noch Hoehe; ein erfundener Turm waere falsch platziert.
 * Baenke fehlen in der POI-Ebene von OpenMapTiles.
 */
internal class PoiLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level != 0 || ctx.budget.props <= 0) return
        val s = ctx.spec
        val pois = ctx.map.pois(s.minE, s.minN, s.maxE, s.maxN)
        if (pois.isEmpty()) return
        Builder(ctx, out).run(pois)
    }

    private class Builder(private val ctx: TileContext, private val out: TileOutput) {
        private val track = ctx.track
        private val net by lazy { RoadNet(ctx, marginM = 12.0) }
        private val buildings by lazy { ctx.spec.let { s -> ctx.map.buildings(s.minE - 8.0, s.minN - 8.0, s.maxE + 8.0, s.maxN + 8.0) } }
        private val mesh by lazy { out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL, roughness = 0.85f)) }
        private val scratch = MeshBuilder(64, 64)
        private val budget = min(min(ctx.budget.props / 3, MAX_TRIANGLES), ctx.budget.props - out.triangles(WorldNames.NODE_PROPS))
        private var used = 0
        var placed = 0
            private set

        fun run(pois: List<MapPoi>) {
            for (p in pois.sortedWith(compareBy({ it.e }, { it.n }, { it.cls }))) {
                val hit = track.nearest(p.e, p.n, NEAR_M) ?: continue
                when {
                    p.cls == "bus" && p.subclass == "bus_stop" -> busStop(p, hit)
                    p.cls == "lift_gate" -> liftGate(p, hit)
                    p.cls == "gate" -> gate(p, hit)
                    p.cls == "cycle_barrier" -> cycleBarrier(p, hit)
                    p.cls == "bollard" -> bollard(p, hit)
                    p.cls == "information" && p.subclass == "guidepost" -> guidepost(p, hit)
                    p.cls == "information" && (p.subclass == "board" || p.subclass == "map") -> board(p, hit)
                    p.cls == "shelter" -> shelter(p, hit)
                }
            }
        }

        // --- Lage ----------------------------------------------------------------

        /** Fahrtrichtung (Einheitsvektor e, n) der Strecke am Laufmeter. */
        private fun dirAt(distM: Double): DoubleArray {
            val h = Math.toRadians(track.poseAt(distM).headingDeg)
            return doubleArrayOf(sin(h), cos(h))
        }

        /** Liegt der Punkt auf der Strecke (Fahrbahn + [margin])? */
        private fun onTrack(hit: TrackHit, margin: Double = 0.4) = hit.distance < track.halfWidthAt(hit.distM) + margin

        /** Naechste Strassenstation (nicht Strecke): Lage, Rechtsvektor, halbe Breite, Abstand. */
        private fun nearestRoad(e: Double, n: Double, maxD: Double): DoubleArray? {
            var best: DoubleArray? = null
            var bestD = maxD
            for (r in net.roads) {
                if (r.spec.rail) continue
                if (e < r.minE - maxD || e > r.maxE + maxD || n < r.minN - maxD || n > r.maxN + maxD) continue
                for (i in 0 until r.size) {
                    if (r.onTrack[i]) continue
                    val d = hypot(r.e[i] - e, r.n[i] - n)
                    if (d < bestD) { bestD = d; best = doubleArrayOf(r.e[i], r.n[i], r.re[i], r.rn[i], r.halfWidth, d) }
                }
            }
            return best
        }

        /**
         * Stelle neben dem Weg, an dem der POI steht: auf der Strecke an deren Rand ([extra] hinter der Kante,
         * auf der Seite, auf der der Punkt liegt), auf einer anderen Strasse an deren Rand, sonst der Punkt.
         * Ergebnis: e, n, Richtung des Wegs (e, n), Seite (+1 rechts).
         */
        private fun roadside(p: MapPoi, hit: TrackHit, extra: Double): DoubleArray? {
            if (onTrack(hit, 0.6)) {
                val pose = track.poseAt(hit.distM)
                val d = dirAt(hit.distM)
                val side = if (hit.lateral >= 0) 1.0 else -1.0
                val off = track.halfWidthAt(hit.distM) + extra
                // rechts in Fahrtrichtung = (dn, −de)
                return doubleArrayOf(pose.e + d[1] * off * side, pose.n - d[0] * off * side, d[0], d[1], side)
            }
            val r = nearestRoad(p.e, p.n, 6.0)
            if (r != null && r[5] < r[4] + 0.4) {
                val side = if ((p.e - r[0]) * r[2] + (p.n - r[1]) * r[3] >= 0) 1.0 else -1.0
                val off = r[4] + extra
                return doubleArrayOf(r[0] + r[2] * off * side, r[1] + r[3] * off * side, -r[3], r[2], side)
            }
            val d = dirAt(hit.distM)
            if (onTrack(hit, extra)) return null
            return doubleArrayOf(p.e, p.n, d[0], d[1], if (hit.lateral >= 0) 1.0 else -1.0)
        }

        /** Querlage auf dem Weg, auf dem der POI liegt (Strecke oder Strasse): Mitte, Richtung, halbe Breite. */
        private fun across(p: MapPoi, hit: TrackHit): DoubleArray? {
            if (onTrack(hit, 0.6)) {
                val pose = track.poseAt(hit.distM)
                val d = dirAt(hit.distM)
                return doubleArrayOf(pose.e, pose.n, d[0], d[1], track.halfWidthAt(hit.distM))
            }
            val r = nearestRoad(p.e, p.n, 4.0) ?: return null
            if (r[5] > r[4] + 0.5) return null
            return doubleArrayOf(r[0], r[1], -r[3], r[2], r[4])
        }

        private fun freeOfBuildings(e: Double, n: Double, margin: Double): Boolean =
            buildings.none { pointInRings(it.rings, e, n) || ringNear(it, e, n, margin) }

        private fun ringNear(b: MapBuilding, e: Double, n: Double, d: Double): Boolean {
            val r = b.rings[0]
            val k = r.size / 2
            for (i in 0 until k) {
                val j = (i + 1) % k
                if (segDist(e, n, r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1]) < d) return true
            }
            return false
        }

        private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-12) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
            return hypot(px - ax - t * dx, py - ay - t * dy)
        }

        // --- Objekte -------------------------------------------------------------

        private fun busStop(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 30.0) return
            val at = roadside(p, hit, 0.7) ?: return
            val e = at[0]; val n = at[1]; val de = at[2]; val dn = at[3]
            if (!ctx.spec.contains(e, n)) return
            val g = ctx.ground.heightAt(e, n)
            pole(e, n, g, 2.75, 0.035, METAL)
            // Schild quer zur Fahrbahn (sichtbar fuer den Ankommenden), beidseitig.
            val cy = g + 2.42
            for (s in doubleArrayOf(-1.0, 1.0)) {
                val fe = de * s
                val fn = dn * s
                disc(e + fe * 0.03, n + fn * 0.03, cy, fe, fn, 0.23, BUS_GREEN)
                disc(e + fe * 0.035, n + fn * 0.035, cy, fe, fn, 0.20, BUS_YELLOW)
                // „H“: zwei Balken und Quersteg, gruen
                val ue = dn
                val un = -de
                val o = 0.04
                slab(e + fe * o + ue * -0.07, n + fn * o + un * -0.07, cy, ue, un, fe, fn, 0.022, 0.12, BUS_GREEN)
                slab(e + fe * o + ue * 0.07, n + fn * o + un * 0.07, cy, ue, un, fe, fn, 0.022, 0.12, BUS_GREEN)
                slab(e + fe * o, n + fn * o, cy, ue, un, fe, fn, 0.07, 0.02, BUS_GREEN)
            }
            // Fahrplankasten
            obox(e, n, at[2], at[3], 0.17, 0.04, g + 1.25, g + 1.75, lin(0.70, 0.70, 0.66), offN = 0.06)
            commit()
        }

        private fun liftGate(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 40.0) return
            val at = roadside(p, hit, 0.5) ?: return
            val e = at[0]; val n = at[1]
            if (!ctx.spec.contains(e, n)) return
            val g = ctx.ground.heightAt(e, n)
            obox(e, n, at[2], at[3], 0.13, 0.13, g, g + 1.0, WHITE_PAINT)
            obox(e, n, at[2], at[3], 0.135, 0.135, g + 0.55, g + 0.75, RED_PAINT)
            // Baum hochgeklappt: senkrechte Stange mit rot-weissen Feldern
            var y = g + 1.0
            var red = true
            while (y < g + 4.6) {
                val y1 = min(g + 4.6, y + 0.5)
                obox(e, n, at[2], at[3], 0.05, 0.05, y, y1, if (red) RED_PAINT else WHITE_PAINT)
                red = !red
                y = y1
            }
            commit()
        }

        private fun gate(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 40.0) return
            val a = across(p, hit) ?: return
            val ce = a[0]; val cn = a[1]; val de = a[2]; val dn = a[3]; val hw = a[4]
            if (!ctx.spec.contains(ce, cn)) return
            val re = dn
            val rn = -de
            val off = hw + 0.35
            val lE = ce - re * off; val lN = cn - rn * off
            val rE = ce + re * off; val rN = cn + rn * off
            for ((e, n) in listOf(lE to lN, rE to rN)) pole(e, n, ctx.ground.heightAt(e, n), 1.35, 0.06, GALV)
            // Torfluegel offen: am rechten Pfosten angeschlagen, laengs am Wegrand nach vorn geschwenkt
            val len = 2 * off - 0.2
            val g = ctx.ground.heightAt(rE, rN)
            val endE = rE + de * len
            val endN = rN + dn * len
            val g2 = ctx.ground.heightAt(endE, endN)
            for (h in doubleArrayOf(0.25, 1.1)) rail(rE, rN, g + h, endE, endN, g2 + h, 0.03, GALV)
            rail(endE, endN, g2 + 0.25, endE, endN, g2 + 1.1, 0.03, GALV)
            rail(rE + de * len * 0.5, rN + dn * len * 0.5, (g + g2) / 2 + 0.25, rE + de * len * 0.5, rN + dn * len * 0.5, (g + g2) / 2 + 1.1, 0.025, GALV)
            commit()
        }

        private fun cycleBarrier(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 40.0) return
            val a = across(p, hit) ?: return
            val ce = a[0]; val cn = a[1]; val de = a[2]; val dn = a[3]; val hw = a[4]
            if (!ctx.spec.contains(ce, cn)) return
            val re = dn
            val rn = -de
            // Zwei Buegel von beiden Raendern, versetzt; die Mitte (≥ 40 % der Breite) bleibt frei.
            val reach = min(1.1, hw * 0.6)
            for ((side, along) in listOf(-1.0 to -0.9, 1.0 to 0.9)) {
                val oE = ce + re * (hw + 0.2) * side + de * along
                val oN = cn + rn * (hw + 0.2) * side + dn * along
                val iE = ce + re * (hw + 0.2 - reach) * side + de * along
                val iN = cn + rn * (hw + 0.2 - reach) * side + dn * along
                val g0 = ctx.ground.heightAt(oE, oN)
                val g1 = ctx.ground.heightAt(iE, iN)
                rail(oE, oN, g0, oE, oN, g0 + 1.0, 0.03, RED_PAINT)
                rail(iE, iN, g1, iE, iN, g1 + 1.0, 0.03, RED_PAINT)
                rail(oE, oN, g0 + 1.0, iE, iN, g1 + 1.0, 0.03, WHITE_PAINT)
                rail(oE, oN, g0 + 0.5, iE, iN, g1 + 0.5, 0.025, RED_PAINT)
            }
            commit()
        }

        private fun bollard(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 40.0 || onTrack(hit, 0.8)) return
            if (!ctx.spec.contains(p.e, p.n)) return
            val g = ctx.ground.heightAt(p.e, p.n)
            pole(p.e, p.n, g, 0.9, 0.08, lin(0.30, 0.30, 0.31))
            scratch.addCylinder(v(p.e, g + 0.62, p.n), v(p.e, g + 0.74, p.n), 0.083, 0.083, 8, capStart = false, capEnd = false, color = RED_PAINT)
            commit()
        }

        private fun guidepost(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 35.0) return
            val at = roadside(p, hit, 0.9) ?: return
            val e = at[0]; val n = at[1]; val de = at[2]; val dn = at[3]
            if (!ctx.spec.contains(e, n)) return
            val g = ctx.ground.heightAt(e, n)
            pole(e, n, g, 2.45, 0.04, METAL)
            arrow(e, n, g + 2.15, de, dn)
            arrow(e, n, g + 1.93, -de, -dn)
            // dritter Pfeil entlang eines abzweigenden Wegs, wenn einer nahe ist
            nearestRoad(e, n, 12.0)?.let { r -> arrow(e, n, g + 1.71, -r[3], r[2]) }
            commit()
        }

        private fun board(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 30.0) return
            val at = roadside(p, hit, 1.2) ?: return
            val e = at[0]; val n = at[1]; val de = at[2]; val dn = at[3]
            if (!ctx.spec.contains(e, n)) return
            val g = ctx.ground.heightAt(e, n)
            // Tafel laengs zum Weg, Vorderseite zur Strecke
            for (s in doubleArrayOf(-0.62, 0.62)) obox(e + de * s, n + dn * s, de, dn, 0.05, 0.05, g - 0.05, g + 1.85, WOOD)
            obox(e, n, de, dn, 0.66, 0.03, g + 0.95, g + 1.78, WOOD_DARK)
            obox(e, n, de, dn, 0.60, 0.035, g + 1.0, g + 1.73, lin(0.62, 0.60, 0.52))
            commit()
        }

        private fun shelter(p: MapPoi, hit: TrackHit) {
            if (hit.distance > 70.0 || onTrack(hit, 3.0)) return
            if (!ctx.spec.contains(p.e, p.n) || !freeOfBuildings(p.e, p.n, 2.5)) return
            if (net.clearance(p.e, p.n) < 2.5) return
            val g = ctx.ground.heightAt(p.e, p.n)
            // Offene Seite zur Strecke
            val pose = track.poseAt(hit.distM)
            var fe = pose.e - p.e
            var fn = pose.n - p.n
            val l = hypot(fe, fn)
            if (l < 1e-6) { fe = 0.0; fn = 1.0 } else { fe /= l; fn /= l }
            val ue = fn
            val un = -fe
            val w = 1.8 // halbe Breite (laengs der offenen Seite)
            val d = 1.4 // halbe Tiefe
            // Rueckwand und Seitenwaende
            obox(p.e - fe * d, p.n - fn * d, ue, un, w, 0.06, g - 0.1, g + 2.1, WOOD)
            for (s in doubleArrayOf(-1.0, 1.0)) obox(p.e + ue * w * s, p.n + un * w * s, fe, fn, d, 0.06, g - 0.1, g + 2.1, WOOD)
            // Satteldach, First quer zur offenen Seite (Giebel vorn)
            val ridge = g + 3.2
            val eave = g + 2.1
            for (s in doubleArrayOf(-1.0, 1.0)) {
                val a = v(p.e + ue * (w + 0.35) * s + fe * (d + 0.4), eave, p.n + un * (w + 0.35) * s + fn * (d + 0.4))
                val b = v(p.e + fe * (d + 0.4), ridge, p.n + fn * (d + 0.4))
                val c = v(p.e - fe * (d + 0.4), ridge, p.n - fn * (d + 0.4))
                val dd = v(p.e + ue * (w + 0.35) * s - fe * (d + 0.4), eave, p.n + un * (w + 0.35) * s - fn * (d + 0.4))
                quadBoth(a, b, c, dd, WOOD_DARK)
            }
            commit()
        }

        // --- Geometrie -----------------------------------------------------------

        private fun v(e: Double, u: Double, n: Double) = Vec3(ctx.x(e), ctx.y(u), ctx.z(n))

        private fun pole(e: Double, n: Double, g: Double, h: Double, r: Double, color: Int) {
            scratch.addCylinder(v(e, g - 0.05, n), v(e, g + h, n), r, r * 0.9, 6, capStart = false, color = color)
        }

        private fun rail(e0: Double, n0: Double, y0: Double, e1: Double, n1: Double, y1: Double, r: Double, color: Int) {
            scratch.addCylinder(v(e0, y0, n0), v(e1, y1, n1), r, r, 6, capStart = false, capEnd = false, color = color)
        }

        /** Senkrechter Quader um ([e], [n]): halbe Laenge [w] entlang ([ue], [un]), halbe Tiefe [d], von [y0] bis [y1]. */
        private fun obox(e: Double, n: Double, ue: Double, un: Double, w: Double, d: Double, y0: Double, y1: Double, color: Int, offN: Double = 0.0) {
            val ve = un
            val vn = -ue
            val cx = e + ve * offN
            val cy = n + vn * offN
            val ce = doubleArrayOf(cx - ue * w - ve * d, cx + ue * w - ve * d, cx + ue * w + ve * d, cx - ue * w + ve * d)
            val cn = doubleArrayOf(cy - un * w - vn * d, cy + un * w - vn * d, cy + un * w + vn * d, cy - un * w + vn * d)
            val mid = v(cx, 0.0, cy)
            for (k in 0..3) {
                val j = (k + 1) % 4
                val a = v(ce[k], y0, cn[k])
                val b = v(ce[j], y0, cn[j])
                val c = v(ce[j], y1, cn[j])
                val dd = v(ce[k], y1, cn[k])
                val out = v((ce[k] + ce[j]) / 2, 0.0, (cn[k] + cn[j]) / 2) - mid
                if ((b - a).cross(dd - a).dot(out) >= 0) quad(a, b, c, dd, color) else quad(b, a, dd, c, color)
            }
            val a = v(ce[0], y1, cn[0]); val b = v(ce[1], y1, cn[1]); val c = v(ce[2], y1, cn[2]); val dd = v(ce[3], y1, cn[3])
            if ((b - a).cross(dd - a).y >= 0) quad(a, b, c, dd, color) else quad(dd, c, b, a, color)
        }

        /** Flaches Rechteck (Halbbreite [hw] entlang ([ue], [un]), Halbhoehe [hh]) um die Mitte, Vorderseite nach ([fe], [fn]). */
        private fun slab(e: Double, n: Double, cy: Double, ue: Double, un: Double, fe: Double, fn: Double, hw: Double, hh: Double, color: Int) {
            val a = v(e - ue * hw, cy - hh, n - un * hw)
            val b = v(e + ue * hw, cy - hh, n + un * hw)
            val c = v(e + ue * hw, cy + hh, n + un * hw)
            val d = v(e - ue * hw, cy + hh, n - un * hw)
            val front = Vec3(fe, 0.0, -fn)
            if ((b - a).cross(d - a).dot(front) >= 0) quad(a, b, c, d, color) else quad(b, a, d, c, color)
        }

        /** Kreisscheibe (10 Segmente) mit Normale ([fe], [fn]) waagrecht. */
        private fun disc(e: Double, n: Double, cy: Double, fe: Double, fn: Double, r: Double, color: Int) {
            val ue = fn
            val un = -fe
            val nor = Vec3(fe, 0.0, -fn)
            val c = v(e, cy, n)
            val base = scratch.vertex(c.x, c.y, c.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            val seg = 10
            for (k in 0 until seg) {
                val a = 2 * Math.PI * k / seg
                val p = v(e + ue * r * cos(a), cy + r * sin(a), n + un * r * cos(a))
                scratch.vertex(p.x, p.y, p.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            }
            for (k in 0 until seg) {
                val i1 = base + 1 + k
                val i2 = base + 1 + (k + 1) % seg
                val t = (scratch.positions.let { Vec3(it[3 * i1].toDouble(), it[3 * i1 + 1].toDouble(), it[3 * i1 + 2].toDouble()) } - c)
                    .cross(scratch.positions.let { Vec3(it[3 * i2].toDouble(), it[3 * i2 + 1].toDouble(), it[3 * i2 + 2].toDouble()) } - c)
                if (t.dot(nor) >= 0) scratch.tri(base, i1, i2) else scratch.tri(base, i2, i1)
            }
        }

        /** Pfeilschild (0,62 m, weiss mit gruener Spitze) am Mast, zeigt nach ([de], [dn]). */
        private fun arrow(e: Double, n: Double, y: Double, de: Double, dn: Double) {
            val l = hypot(de, dn)
            if (l < 1e-6) return
            val ue = de / l
            val un = dn / l
            obox(e + ue * 0.30, n + un * 0.30, ue, un, 0.27, 0.012, y - 0.07, y + 0.07, lin(0.80, 0.80, 0.76))
            obox(e + ue * 0.60, n + un * 0.60, ue, un, 0.045, 0.013, y - 0.05, y + 0.05, BUS_GREEN)
        }

        private fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: Int) {
            val nor = (b - a).cross(d - a).normalized(Vec3(0.0, 1.0, 0.0))
            val i = scratch.vertex(a.x, a.y, a.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(b.x, b.y, b.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(c.x, c.y, c.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(d.x, d.y, d.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.quad(i, i + 1, i + 2, i + 3)
        }

        private fun quadBoth(a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: Int) {
            quad(a, b, c, d, color)
            quad(b, a, d, c, color)
        }

        private fun commit(): Boolean {
            val tris = scratch.triangleCount
            val ok = tris > 0 && used + tris <= budget
            if (ok) {
                mesh.append(scratch)
                used += tris
                placed++
            }
            scratch.clear()
            return ok
        }
    }

    companion object {
        /** Nur POIs bis zu diesem Abstand (m) von der Mittellinie. */
        const val NEAR_M = 80.0

        /** Hoechstens so viele Dreiecke je Kachel (und hoechstens ein Drittel des Kleinteil-Budgets). */
        const val MAX_TRIANGLES = 2400

        private fun lin(r: Double, g: Double, b: Double) = RouteGeometry.linColor(r, g, b)
        private val METAL = lin(0.34, 0.35, 0.36)
        private val GALV = lin(0.45, 0.46, 0.46)
        private val RED_PAINT = lin(0.55, 0.035, 0.03)
        private val WHITE_PAINT = lin(0.80, 0.80, 0.78)
        private val BUS_GREEN = lin(0.03, 0.26, 0.10)
        private val BUS_YELLOW = lin(0.85, 0.66, 0.04)
        private val WOOD = lin(0.22, 0.15, 0.09)
        private val WOOD_DARK = lin(0.15, 0.10, 0.065)
    }
}
