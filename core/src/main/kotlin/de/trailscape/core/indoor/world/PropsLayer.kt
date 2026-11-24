package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * # Kleinteile am Weg (Knoten `props`)
 *
 * Was deutsche Strassen und Wege glaubwuerdig macht — sparsam und nur
 * unmittelbar an der Strecke:
 *
 *  * **Leitpfosten** alle 50 m beidseitig an Kraftverkehrsstrassen ausserorts,
 *  * **Strassenlaternen** im Ort, wechselseitig alle 36 m,
 *  * **Ortstafel** (gelb, ohne Text) am Uebergang in den Ort,
 *  * **Weidezaeune** abschnittsweise, wo Wiese an den Weg grenzt,
 *  * **Holzpolter** vereinzelt am Waldweg, **Baenke** selten,
 *  * **Wegweiser** an Einmuendungen von Wegen.
 *
 * Alles ist deterministisch aus Laufmetern und Koordinaten gewuerfelt, ein
 * Material ([MATERIAL], nur Vertexfarben). Jedes Teil gehoert der Kachel, in
 * der sein Fusspunkt liegt; nichts steht auf einer Fahrbahn oder in einem
 * Gebaeude. Das Budget wird in der Reihenfolge der Liste oben verbraucht —
 * was nicht mehr passt, entfaellt als ganzes Teil.
 */
internal class PropsLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level != 0 || ctx.budget.props <= 0) return
        val t = ctx.track.track
        // Proben der Strecke im Umkreis der Kachel (Teile stehen bis rund 12 m neben der Mitte).
        var any = false
        val near = BooleanArray(t.sampleCount)
        for (i in 0 until t.sampleCount) {
            if (ctx.spec.distanceTo(t.e[i].toDouble(), t.n[i].toDouble()) <= 16.0) {
                near[i] = true
                any = true
            }
        }
        if (!any) return
        Builder(ctx, out, near).run()
    }

    private class Builder(private val ctx: TileContext, private val out: TileOutput, private val near: BooleanArray) {
        private val track = ctx.track
        private val step = track.stepM
        private val net = RoadNet(ctx, marginM = 12.0)
        private val islands = Roundabouts.islands(ctx.map, ctx.spec.minE - 60, ctx.spec.minN - 60, ctx.spec.maxE + 60, ctx.spec.maxN + 60)
        private val buildings = ctx.spec.let { s -> ctx.map.buildings(s.minE - 12.0, s.minN - 12.0, s.maxE + 12.0, s.maxN + 12.0) }
        private val mesh by lazy { out.mesh(WorldNames.NODE_PROPS, TileMaterial(MATERIAL, roughness = 0.85f)) }
        private val scratch = MeshBuilder(64, 64)
        private val tmp = DoubleArray(5)
        private var used = 0

        /** Lage neben der Strecke: Mitte, Richtung, Rechtsvektor am Laufmeter. */
        private class Pose(val e: Double, val n: Double, val u: Double, val te: Double, val tn: Double, val hw: Double, val sec: SurfaceSection)

        private fun poseAt(d: Double): Pose? {
            if (d < 0 || d > track.lengthM) return null
            val i = min((d / step).toInt(), track.count - 2)
            if (!near[i] && !near[i + 1]) return null
            RouteGeometry.centerAtDist(track, d, tmp)
            return Pose(tmp[0], tmp[1], tmp[2], tmp[3], tmp[4], track.halfWidthAt(d), track.surfaceAt(d))
        }

        private fun sideE(p: Pose, side: Int, off: Double): Double = p.e + p.tn * off * side
        private fun sideN(p: Pose, side: Int, off: Double): Double = p.n - p.te * off * side
        private fun landAt(p: Pose, side: Int, off: Double = p.hw + 3.0): LandClass = ctx.land.classAt(sideE(p, side, off), sideN(p, side, off))

        /** Fusspunkt ist frei: in dieser Kachel, nicht auf Strecke oder Strasse, nicht in einem Gebaeude. */
        private fun free(e: Double, n: Double, radius: Double): Boolean {
            if (!ctx.spec.contains(e, n)) return false
            val hit = track.nearest(e, n, 30.0)
            if (hit != null && hit.distance < track.halfWidthAt(hit.distM) + 0.25 + radius) return false
            if (net.clearance(e, n) < 0.35 + radius) return false
            for (b in buildings) if (pointInRings(b.rings, e, n) || nearRing(b, e, n, radius + 0.3)) return false
            return true
        }

        private fun nearRing(b: MapBuilding, e: Double, n: Double, d: Double): Boolean {
            val r = b.rings.firstOrNull() ?: return false
            val closed = DoubleArray(r.size + 2)
            r.copyInto(closed)
            closed[r.size] = r[0]
            closed[r.size + 1] = r[1]
            return distanceToLine(closed, e, n) < d
        }

        /** Uebernimmt das Teil aus dem Zwischenspeicher, wenn das Budget reicht. */
        private fun commit(): Boolean {
            val tris = scratch.triangleCount
            val ok = tris > 0 && used + tris <= ctx.budget.props
            if (ok) {
                mesh.append(scratch)
                used += tris
            }
            scratch.clear()
            return ok
        }

        fun run() {
            guidePosts()
            streetLamps()
            townSigns()
            fences()
            logPiles()
            benches()
            signposts()
        }

        private inline fun eachGrid(period: Double, f: (Int, Double) -> Unit) {
            var k = 0
            while (k * period <= track.lengthM) {
                f(k, k * period)
                k++
            }
        }

        // --- Leitpfosten ---------------------------------------------------------

        private fun guidePosts() = eachGrid(50.0) { k, d ->
            val p = poseAt(d + 25.0) ?: return@eachGrid
            if (!RouteLayer.isMotorRoad(p.sec) || p.sec.effectiveWidthM < 4.5) return@eachGrid
            for (side in intArrayOf(-1, 1)) {
                if (edgeStyleOf(p.sec, landAt(p, side)) != EdgeStyle.BANKETT) continue
                val e = sideE(p, side, p.hw + 0.5)
                val n = sideN(p, side, p.hw + 0.5)
                if (!free(e, n, 0.1)) continue
                guidePost(e, n, p, side, Noise.hash01(k, side, 0x1e1))
                commit()
            }
        }

        /** Leitpfosten: 12 × 8 cm, 1 m hoch, weiss, schwarzes Schraegband (zur Fahrbahn fallend) mit Rueckstrahler. */
        private fun guidePost(e: Double, n: Double, p: Pose, side: Int, rnd: Double) {
            val g = ctx.ground.heightAt(e, n)
            val white = RouteGeometry.linColor(0.74 + 0.06 * rnd, 0.74 + 0.06 * rnd, 0.72 + 0.06 * rnd)
            val black = RouteGeometry.linColor(0.035, 0.035, 0.04)
            // Breite quer zur Fahrtrichtung, Tiefe laengs; a = zur Fahrbahn, b = von ihr weg.
            val ue = -p.tn * side
            val un = p.te * side
            val w = 0.06
            val dpt = 0.04
            val slant = 0.05
            prism(e, n, ue, un, w, dpt, g - 0.02, g - 0.02, g + 0.66 + slant, g + 0.66 - slant, white, false)
            prism(e, n, ue, un, w, dpt, g + 0.66 + slant, g + 0.66 - slant, g + 0.86 + slant, g + 0.86 - slant, black, false)
            prism(e, n, ue, un, w, dpt, g + 0.86 + slant, g + 0.86 - slant, g + 1.0, g + 0.98, white, true)
            // Rueckstrahler auf beiden Stirnseiten: rechts ein Rechteck, links zwei Punkte (hier: zwei kleine Rechtecke).
            val refl = RouteGeometry.linColor(0.86, 0.86, 0.82)
            for (dir in intArrayOf(-1, 1)) {
                val fe = p.te * dir
                val fn = p.tn * dir
                val ce = e + fe * (dpt + 0.004)
                val cn = n + fn * (dpt + 0.004)
                // Von vorn gesehen steht der Pfosten rechts, wenn er in Blickrichtung rechts der Fahrbahn steht.
                val rightOfViewer = side * -dir == 1
                if (rightOfViewer) {
                    plate(ce, cn, ue, un, fe, fn, 0.02, g + 0.68, g + 0.84, refl)
                } else {
                    plate(ce, cn, ue, un, fe, fn, 0.02, g + 0.69, g + 0.745, refl)
                    plate(ce, cn, ue, un, fe, fn, 0.02, g + 0.775, g + 0.83, refl)
                }
            }
        }

        // --- Laternen und Ortstafel ---------------------------------------------

        private fun streetLamps() = eachGrid(36.0) { k, d ->
            val p = poseAt(d + 11.0) ?: return@eachGrid
            val side = if (k % 2 == 0) 1 else -1
            if (edgeStyleOf(p.sec, landAt(p, side)) != EdgeStyle.URBAN) return@eachGrid
            val off = p.hw + RouteLayer.KERB_WIDTH_M + 1.2
            val e = sideE(p, side, off)
            val n = sideN(p, side, off)
            if (!free(e, n, 0.15)) return@eachGrid
            val g = ctx.ground.heightAt(e, n) + 0.05
            val steel = RouteGeometry.linColor(0.30, 0.31, 0.32)
            val top = 5.6
            scratch.addCylinder(v(e, g, n), v(e, g + top, n), 0.075, 0.045, 8, capStart = false, capEnd = false, color = steel)
            // Ausleger zur Fahrbahn, Leuchte am Ende.
            val ae = e - p.tn * side * 1.1
            val an = n + p.te * side * 1.1
            scratch.addCylinder(v(e, g + top - 0.05, n), v(ae, g + top + 0.12, an), 0.04, 0.035, 6, capStart = false, capEnd = false, color = steel)
            box(ae - p.tn * side * 0.2, an + p.te * side * 0.2, -p.tn * side, p.te * side, 0.32, 0.11, g + top + 0.06, g + top + 0.16, RouteGeometry.linColor(0.20, 0.21, 0.22))
            plateDown(ae - p.tn * side * 0.2, an + p.te * side * 0.2, -p.tn * side, p.te * side, 0.26, 0.08, g + top + 0.055, RouteGeometry.linColor(0.80, 0.80, 0.74))
            commit()
        }

        /** Ortstafel: gelbe Tafel mit schwarzem Rand auf zwei Pfosten, rechts am Ortseingang; Rueckseite grau. */
        private fun townSigns() {
            val t = track.track
            for (i in 4 until t.sampleCount - 6) {
                if (!near[i]) continue
                val d = t.distAt(i)
                val p = poseAt(d) ?: continue
                val k = p.sec.kind.visual
                if (k != SurfaceKind.ASPHALT && k != SurfaceKind.CONCRETE && k != SurfaceKind.PAVING_STONES && k != SurfaceKind.COBBLESTONE) continue
                if (p.sec.effectiveWidthM < 4.0) continue
                fun res(j: Int): Boolean {
                    val q = poseAt(t.distAt(j)) ?: return false
                    return landAt(q, 1) == LandClass.RESIDENTIAL
                }
                // Genau hier beginnt der Ort: davor 20 m keiner, danach 25 m durchgehend.
                if (!res(i) || res(i - 1) || res(i - 2) || res(i - 4)) continue
                if (!(res(i + 1) && res(i + 3) && res(i + 5))) continue
                val off = p.hw + 1.25
                val e = sideE(p, 1, off)
                val n = sideN(p, 1, off)
                if (!free(e, n, 0.6)) continue
                val g = ctx.ground.heightAt(e, n)
                val steel = RouteGeometry.linColor(0.33, 0.34, 0.35)
                val ue = p.tn
                val un = -p.te
                for (s in intArrayOf(-1, 1)) {
                    scratch.addCylinder(v(e + ue * 0.38 * s, g, n + un * 0.38 * s), v(e + ue * 0.38 * s, g + 2.25, n + un * 0.38 * s), 0.03, 0.03, 6, capStart = false, color = steel)
                }
                // Vorderseite zeigt dem Ankommenden entgegen (gegen die Fahrtrichtung).
                val fe = -p.te
                val fn = -p.tn
                val ce = e + fe * 0.035
                val cn = n + fn * 0.035
                plate(ce, cn, ue, un, fe, fn, 0.53, g + 1.52, g + 2.22, RouteGeometry.linColor(0.03, 0.03, 0.03))
                plate(ce + fe * 0.004, cn + fn * 0.004, ue, un, fe, fn, 0.50, g + 1.55, g + 2.19, RouteGeometry.linColor(0.78, 0.60, 0.03))
                plate(e - fe * 0.035, n - fn * 0.035, ue, un, -fe, -fn, 0.53, g + 1.52, g + 2.22, RouteGeometry.linColor(0.36, 0.37, 0.38))
                commit()
            }
        }

        // --- Weidezaun -----------------------------------------------------------

        private fun fences() {
            val block = 120.0
            val spacing = 3.0
            val perBlock = (block / spacing).toInt()
            eachGrid(block) { b, d0 ->
                for (side in intArrayOf(-1, 1)) {
                    val h = Noise.hash01(b, side, 0xfe2ce)
                    if (h > 0.34) continue
                    val rails = h < 0.14
                    var prev: DoubleArray? = null
                    for (j in 0..perBlock) {
                        val d = d0 + j * spacing
                        val p = poseAt(d)
                        val style = if (p == null) null else edgeStyleOf(p.sec, landAt(p, side))
                        if (p == null || style == EdgeStyle.URBAN || style == EdgeStyle.PLAIN || landAt(p, side) != LandClass.MEADOW || landAt(p, side, p.hw + 6.0) != LandClass.MEADOW) {
                            prev = null
                            continue
                        }
                        val off = p.hw + (if (style == EdgeStyle.BANKETT) 0.6 else 0.5) + 1.7 + (Noise.hash01(b * 64 + j, side, 0xfe3) - 0.5) * 0.2
                        val e = sideE(p, side, off)
                        val n = sideN(p, side, off)
                        // Kein Weidezaun auf einer Kreisel-Mittelinsel.
                        if (islands.any { it.contains(e, n, 3.0) }) {
                            prev = null
                            continue
                        }
                        if (!free(e, n, 0.3) && !(net.clearance(e, n) > 0.7 && freeOutside(e, n))) {
                            prev = null
                            continue
                        }
                        val g = ctx.ground.heightAt(e, n)
                        val top = 1.12 + (Noise.hash01(b * 64 + j, side, 0xfe4) - 0.5) * 0.14
                        val tone = 0.8 + 0.4 * Noise.hash01(b * 64 + j, side, 0xfe5)
                        val own = ctx.spec.contains(e, n)
                        if (own) {
                            val wood = RouteGeometry.linColor(0.20 * tone, 0.16 * tone, 0.115 * tone)
                            prism(e, n, p.te, p.tn, 0.04, 0.04, g - 0.05, g - 0.05, g + top, g + top - 0.02, wood, true)
                        }
                        val cur = doubleArrayOf(e, n, g)
                        // Das Feld zwischen zwei Pfosten gehoert der Kachel des ersten Pfostens.
                        val pr = prev
                        if (pr != null && ctx.spec.contains(pr[0], pr[1])) {
                            if (rails) {
                                val wood = RouteGeometry.linColor(0.25 * tone, 0.20 * tone, 0.14 * tone)
                                for (hgt in doubleArrayOf(0.55, 0.98)) beam(pr[0], pr[1], pr[2] + hgt, e, n, g + hgt, 0.05, 0.016, wood)
                            } else {
                                val tape = RouteGeometry.linColor(0.70, 0.68, 0.58)
                                for (hgt in doubleArrayOf(0.62, 1.0)) ribbon(pr[0], pr[1], pr[2] + hgt, e, n, g + hgt, 0.017, tape)
                            }
                        }
                        if (own || (pr != null && ctx.spec.contains(pr[0], pr[1]))) commit() else scratch.clear()
                        prev = cur
                    }
                }
            }
        }

        /** Wie [free], aber fuer Punkte ausserhalb dieser Kachel (Zaunfelder ueber die Kachelgrenze). */
        private fun freeOutside(e: Double, n: Double): Boolean {
            if (ctx.spec.contains(e, n)) return false
            val hit = track.nearest(e, n, 30.0)
            if (hit != null && hit.distance < track.halfWidthAt(hit.distM) + 0.55) return false
            for (b in buildings) if (pointInRings(b.rings, e, n) || nearRing(b, e, n, 0.6)) return false
            return true
        }

        // --- Holzpolter und Baenke ----------------------------------------------

        private fun logPiles() = eachGrid(150.0) { b, d0 ->
            for (side in intArrayOf(-1, 1)) {
                val h = Noise.hash01(b, side, 0x10c5)
                if (h > 0.26) continue
                val d = d0 + 30.0 + Noise.hash01(b, side, 0x10c6) * 90.0
                val p = poseAt(d) ?: continue
                val k = p.sec.kind.visual
                if (k != SurfaceKind.GRAVEL && k != SurfaceKind.COMPACTED && k != SurfaceKind.DIRT && k != SurfaceKind.GRASS) continue
                if (p.sec.effectiveWidthM < 2.2) continue
                if (landAt(p, side) != LandClass.FOREST || landAt(p, side, p.hw + 7.0) != LandClass.FOREST) continue
                val radius = 0.17 + 0.05 * Noise.hash01(b, side, 0x10c7)
                val rows = intArrayOf(5, 4, 3)
                val off = p.hw + 1.4 + rows[0] * radius
                val e = sideE(p, side, off)
                val n = sideN(p, side, off)
                // Der ganze Stapel (4 m lang, gut 2 m tief) muss frei liegen.
                var ok = true
                for (a in doubleArrayOf(-2.0, 0.0, 2.0)) for (c in doubleArrayOf(-1.0, 1.0)) {
                    val qe = e + p.te * a - p.tn * side * c
                    val qn = n + p.tn * a + p.te * side * c
                    if (!freeAnywhere(qe, qn, 0.3)) ok = false
                }
                if (!ok || !ctx.spec.contains(e, n)) continue
                val g = ctx.ground.heightAt(e, n)
                for ((row, cnt) in rows.withIndex()) {
                    for (q in 0 until cnt) {
                        val id = b * 100 + row * 10 + q
                        val r = radius * (0.88 + 0.24 * Noise.hash01(id, side, 0x10c8))
                        val lat = (q - (cnt - 1) / 2.0) * radius * 2.02
                        val along = (Noise.hash01(id, side, 0x10c9) - 0.5) * 0.3
                        val len = 2.0 + (Noise.hash01(id, side, 0x10ca) - 0.5) * 0.16
                        val ce = e - p.tn * side * lat + p.te * along
                        val cn = n + p.te * side * lat + p.tn * along
                        val y = g + radius * 0.95 + row * radius * 1.74
                        val tone = 0.8 + 0.4 * Noise.hash01(id, side, 0x10cb)
                        val bark = RouteGeometry.linColor(0.085 * tone, 0.062 * tone, 0.042 * tone)
                        val cut = RouteGeometry.linColor(0.50 * tone, 0.36 * tone, 0.19 * tone)
                        val p0 = v(ce - p.te * len, y, cn - p.tn * len)
                        val p1 = v(ce + p.te * len, y, cn + p.tn * len)
                        scratch.addCylinder(p0, p1, r, r * 0.94, 8, capStart = false, capEnd = false, color = bark)
                        disc(p0, p0 - p1, r, cut)
                        disc(p1, p1 - p0, r * 0.94, cut)
                    }
                }
                commit()
            }
        }

        private fun freeAnywhere(e: Double, n: Double, radius: Double): Boolean {
            val hit = track.nearest(e, n, 30.0)
            if (hit != null && hit.distance < track.halfWidthAt(hit.distM) + 0.25 + radius) return false
            if (net.clearance(e, n) < 0.35 + radius) return false
            for (b in buildings) if (pointInRings(b.rings, e, n) || nearRing(b, e, n, radius + 0.3)) return false
            return true
        }

        private fun benches() = eachGrid(300.0) { b, d0 ->
            val h = Noise.hash01(b, 3, 0xbe2c)
            if (h > 0.3) return@eachGrid
            val side = if (Noise.hash01(b, 4, 0xbe2c) < 0.5) -1 else 1
            val p = poseAt(d0 + 60.0 + Noise.hash01(b, 5, 0xbe2c) * 180.0) ?: return@eachGrid
            val style = edgeStyleOf(p.sec, landAt(p, side))
            if (style != EdgeStyle.SOFT) return@eachGrid
            val land = landAt(p, side)
            if (land != LandClass.MEADOW && land != LandClass.FOREST && land != LandClass.PARK) return@eachGrid
            val off = p.hw + 1.9
            val e = sideE(p, side, off)
            val n = sideN(p, side, off)
            if (!free(e, n, 0.9)) return@eachGrid
            val g = ctx.ground.heightAt(e, n)
            val wood = RouteGeometry.linColor(0.20, 0.14, 0.085)
            val wood2 = RouteGeometry.linColor(0.17, 0.12, 0.075)
            val stone = RouteGeometry.linColor(0.33, 0.33, 0.32)
            // Laengs zur Strecke, Sitzflaeche zur Strecke hin, Lehne hinten.
            val be = -p.tn * side
            val bn = p.te * side
            for (s in doubleArrayOf(-0.62, 0.62)) {
                box(e + p.te * s, n + p.tn * s, p.te, p.tn, 0.05, 0.2, g - 0.03, g + 0.43, stone)
            }
            box(e, n, p.te, p.tn, 0.85, 0.21, g + 0.43, g + 0.48, wood)
            box(e + be * 0.24, n + bn * 0.24, p.te, p.tn, 0.85, 0.025, g + 0.56, g + 0.86, wood2)
            for (s in doubleArrayOf(-0.62, 0.62)) {
                box(e + p.te * s + be * 0.24, n + p.tn * s + bn * 0.24, p.te, p.tn, 0.03, 0.03, g + 0.43, g + 0.84, stone)
            }
            commit()
        }

        // --- Wegweiser -----------------------------------------------------------

        private fun signposts() {
            for (r in net.roads) {
                if (r.spec.rail || r.spec.rank > 3) continue
                for (i in 1 until r.size) {
                    val hit = track.nearest(r.e[i], r.n[i], 20.0) ?: continue
                    val hw = track.halfWidthAt(hit.distM)
                    // Erste Station, an der der Weg auf die Strecke trifft (von aussen kommend) bzw. sie verlaesst.
                    val inside = hit.distance < hw + 2.4
                    val prevHit = track.nearest(r.e[i - 1], r.n[i - 1], 20.0)
                    val prevInside = prevHit != null && prevHit.distance < track.halfWidthAt(prevHit.distM) + 2.4
                    if (inside == prevInside || r.onTrack[i] || r.onTrack[i - 1]) continue
                    val o = if (inside) i - 1 else i
                    if (Noise.hash01(floor(r.e[o] / 3).toInt(), floor(r.n[o] / 3).toInt(), 0x516e) > 0.55) continue
                    val e = r.e[o] + r.re[o] * (r.halfWidth + 0.8)
                    val n = r.n[o] + r.rn[o] * (r.halfWidth + 0.8)
                    if (!free(e, n, 0.25)) continue
                    val g = ctx.ground.heightAt(e, n)
                    scratch.addCylinder(v(e, g - 0.05, n), v(e, g + 2.35, n), 0.035, 0.03, 6, capStart = false, color = RouteGeometry.linColor(0.34, 0.35, 0.36))
                    // Zwei Schilder: eines entlang des Wegs, eines entlang der Strecke.
                    val pose = track.poseAt(hit.distM)
                    val ha = Math.toRadians(pose.headingDeg)
                    val white = RouteGeometry.linColor(0.78, 0.78, 0.74)
                    val green = RouteGeometry.linColor(0.05, 0.22, 0.09)
                    arrowBoard(e, n, g + 2.12, r.rn[o], -r.re[o], white, green)
                    arrowBoard(e, n, g + 1.92, sin(ha), cos(ha), white, green)
                    commit()
                }
            }
        }

        /** Wegweiser-Schild: 55 × 14 cm, zeigt in Richtung ([de], [dn]); beidseitig, gruener Kopfstreifen. */
        private fun arrowBoard(e: Double, n: Double, y: Double, de: Double, dn: Double, face: Int, tip: Int) {
            val l = hypot(de, dn).coerceAtLeast(1e-9)
            val ae = de / l
            val an = dn / l
            val ne = an
            val nn = -ae
            for (s in intArrayOf(-1, 1)) {
                val oe = e + ne * 0.04 * s
                val on = n + nn * 0.04 * s
                plateSpan(oe + ae * 0.04, on + an * 0.04, oe + ae * 0.47, on + an * 0.47, y - 0.07, y + 0.07, ne * s, nn * s, face)
                plateSpan(oe + ae * 0.47, on + an * 0.47, oe + ae * 0.59, on + an * 0.59, y - 0.07, y + 0.07, ne * s, nn * s, tip)
            }
        }

        // --- Grundformen (in ENU, Y oben) ----------------------------------------

        private fun v(e: Double, u: Double, n: Double) = Vec3(ctx.x(e), ctx.y(u), ctx.z(n))

        private fun quad(a: Vec3, b: Vec3, c: Vec3, d: Vec3, color: Int) {
            val nor = (b - a).cross(d - a).normalized()
            val i = scratch.vertex(a.x, a.y, a.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(b.x, b.y, b.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(c.x, c.y, c.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(d.x, d.y, d.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.quad(i, i + 1, i + 2, i + 3)
        }

        /**
         * Senkrechtes Prisma um ([e], [n]): halbe Breite [w] entlang ([ue], [un]),
         * halbe Tiefe [d] quer dazu. Unter- und Oberkante duerfen ueber die Breite
         * schraeg laufen: Hoehen an der Seite −u ([y0a], [y1a]) und +u ([y0b], [y1b]).
         */
        private fun prism(e: Double, n: Double, ue: Double, un: Double, w: Double, d: Double, y0a: Double, y0b: Double, y1a: Double, y1b: Double, color: Int, cap: Boolean) {
            val ve = un
            val vn = -ue
            // Ecken im Uhrzeigersinn von oben: (−u,−v), (+u,−v), (+u,+v), (−u,+v).
            val ce = doubleArrayOf(e - ue * w - ve * d, e + ue * w - ve * d, e + ue * w + ve * d, e - ue * w + ve * d)
            val cn = doubleArrayOf(n - un * w - vn * d, n + un * w - vn * d, n + un * w + vn * d, n - un * w + vn * d)
            val lo = doubleArrayOf(y0a, y0b, y0b, y0a)
            val hi = doubleArrayOf(y1a, y1b, y1b, y1a)
            for (k in 0..3) {
                val j = (k + 1) % 4
                val a = v(ce[k], lo[k], cn[k])
                val b = v(ce[j], lo[j], cn[j])
                val c = v(ce[j], hi[j], cn[j])
                val dd = v(ce[k], hi[k], cn[k])
                val nor = (b - a).cross(dd - a)
                // Nach aussen drehen, unabhaengig von der Haendigkeit des Rahmens.
                val mid = v((ce[k] + ce[j]) / 2, 0.0, (cn[k] + cn[j]) / 2) - v(e, 0.0, n)
                if (nor.dot(mid) >= 0) quad(a, b, c, dd, color) else quad(b, a, dd, c, color)
            }
            if (cap) {
                val a = v(ce[0], hi[0], cn[0])
                val b = v(ce[1], hi[1], cn[1])
                val c = v(ce[2], hi[2], cn[2])
                val dd = v(ce[3], hi[3], cn[3])
                if ((b - a).cross(dd - a).y >= 0) quad(a, b, c, dd, color) else quad(dd, c, b, a, color)
            }
        }

        /** Quader um ([e], [n]): halbe Laenge [w] entlang ([ue], [un]), halbe Tiefe [d], von [y0] bis [y1], mit Deckel. */
        private fun box(e: Double, n: Double, ue: Double, un: Double, w: Double, d: Double, y0: Double, y1: Double, color: Int) =
            prism(e, n, ue, un, w, d, y0, y0, y1, y1, color, true)

        /** Senkrechte Platte um ([e], [n]): halbe Breite [w] entlang ([ue], [un]), Vorderseite nach ([fe], [fn]). */
        private fun plate(e: Double, n: Double, ue: Double, un: Double, fe: Double, fn: Double, w: Double, y0: Double, y1: Double, color: Int) =
            plateSpan(e - ue * w, n - un * w, e + ue * w, n + un * w, y0, y1, fe, fn, color)

        private fun plateSpan(e0: Double, n0: Double, e1: Double, n1: Double, y0: Double, y1: Double, fe: Double, fn: Double, color: Int) {
            val a = v(e0, y0, n0)
            val b = v(e1, y0, n1)
            val c = v(e1, y1, n1)
            val d = v(e0, y1, n0)
            val front = Vec3(fe, 0.0, -fn)
            if ((b - a).cross(d - a).dot(front) >= 0) quad(a, b, c, d, color) else quad(b, a, d, c, color)
        }

        /** Waagerechte Platte, nach unten sichtbar (Leuchtflaeche). */
        private fun plateDown(e: Double, n: Double, ue: Double, un: Double, w: Double, d: Double, y: Double, color: Int) {
            val ve = un
            val vn = -ue
            val a = v(e - ue * w - ve * d, y, n - un * w - vn * d)
            val b = v(e + ue * w - ve * d, y, n + un * w - vn * d)
            val c = v(e + ue * w + ve * d, y, n + un * w + vn * d)
            val dd = v(e - ue * w + ve * d, y, n - un * w + vn * d)
            if ((b - a).cross(dd - a).y <= 0) quad(a, b, c, dd, color) else quad(dd, c, b, a, color)
        }

        /** Latte von Punkt zu Punkt: [hh] halbe Hoehe, [ht] halbe Dicke; vier Laengsflaechen. */
        private fun beam(e0: Double, n0: Double, y0: Double, e1: Double, n1: Double, y1: Double, hh: Double, ht: Double, color: Int) {
            val l = hypot(e1 - e0, n1 - n0).coerceAtLeast(1e-9)
            val se = (n1 - n0) / l * ht
            val sn = -(e1 - e0) / l * ht
            // Etwas ueber die Pfostenmitte hinaus, damit die Enden im Pfosten stecken.
            fun p(end: Int, sy: Int, ss: Int): Vec3 {
                val e = if (end == 0) e0 else e1
                val n = if (end == 0) n0 else n1
                val y = if (end == 0) y0 else y1
                return v(e + se * ss, y + hh * sy, n + sn * ss)
            }
            val ring = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, 1), intArrayOf(1, -1))
            val centre0 = v(e0, y0, n0)
            for (k in 0..3) {
                val a = ring[k]
                val b = ring[(k + 1) % 4]
                val q0 = p(0, a[0], a[1])
                val q1 = p(1, a[0], a[1])
                val q2 = p(1, b[0], b[1])
                val q3 = p(0, b[0], b[1])
                val nor = (q1 - q0).cross(q3 - q0)
                val outward = (q0 + q3) * 0.5 - centre0
                if (nor.dot(outward) >= 0) quad(q0, q1, q2, q3, color) else quad(q1, q0, q3, q2, color)
            }
        }

        /** Flaches Band (Weidezaunlitze) von Punkt zu Punkt, beidseitig sichtbar; [hh] halbe Hoehe. */
        private fun ribbon(e0: Double, n0: Double, y0: Double, e1: Double, n1: Double, y1: Double, hh: Double, color: Int) {
            val a = v(e0, y0 - hh, n0)
            val b = v(e1, y1 - hh, n1)
            val c = v(e1, y1 + hh, n1)
            val d = v(e0, y0 + hh, n0)
            quad(a, b, c, d, color)
            quad(b, a, d, c, color)
        }

        /** Kreisscheibe um [c] mit Normale [axis] (Schnittflaeche eines Stamms). */
        private fun disc(c: Vec3, axis: Vec3, r: Double, color: Int) {
            val nor = axis.normalized()
            val ref = if (kotlin.math.abs(nor.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
            val a = nor.cross(ref).normalized()
            val b = nor.cross(a)
            val seg = 8
            val centre = scratch.vertex(c.x, c.y, c.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            for (k in 0 until seg) {
                val ang = 2 * PI * k / seg
                val p = c + a * (cos(ang) * r) + b * (sin(ang) * r)
                // Rand etwas dunkler (Rinde, Jahresringe aussen).
                scratch.vertex(p.x, p.y, p.z, nor.x, nor.y, nor.z, 0.0, 0.0, Argb.scale(color, 0.82))
            }
            for (k in 0 until seg) scratch.tri(centre, centre + 1 + k, centre + 1 + (k + 1) % seg)
        }
    }

    companion object {
        const val MATERIAL = "x_mat_props"
    }
}
