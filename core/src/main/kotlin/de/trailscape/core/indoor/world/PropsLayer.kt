package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Kleinteile am Weg (Knoten `props`)
 *
 * Was deutsche Strassen und Wege glaubwuerdig macht — sparsam und nur
 * unmittelbar an der Strecke:
 *
 *  * **Leitpfosten** an Kraftverkehrsstrassen ausserorts (Abstand nach Kurvenradius) und
 *    **Schutzplanken** an Bruecken und hohen Boeschungen ([RoadsideLayer]),
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
        // Echte Punktobjekte ([PoiLayer]) laufen vorher und zaehlen mit.
        private var used = out.triangles(WorldNames.NODE_PROPS)

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
            bridgeStructures()
            bridgeRailings()
            guidePosts()
            streetLamps()
            townSigns()
            fences()
            logPiles()
            benches()
            signposts()
            prioritySigns()
        }

        private inline fun eachGrid(period: Double, f: (Int, Double) -> Unit) {
            var k = 0
            while (k * period <= track.lengthM) {
                f(k, k * period)
                k++
            }
        }

        // --- Brueckenbauwerk ------------------------------------------------------

        /**
         * Unterbau jeder Bruecke der Karte (`brunnel = bridge`, 6–400 m), die mehr als 0,8 m ueber dem
         * Gelaende liegt: Ueberbau als Betonplatte (0,8 m dick, 0,35 m ueber die Fahrbahn hinaus),
         * Pfeiler etwa alle 22 m, wo der Boden mehr als 2,5 m tiefer liegt, und bei Strassenbruecken
         * neben der Strecke eine Bruestung (0,85 m). Auf der Strecke selbst stehen die Gelaender aus
         * [bridgeRailings] und eine niedrige Randkappe. Vorher lag dort nur das flache Fahrbahnband
         * (enztal km 5,3, schoenbuch km 7,0). Deckhoehe wie im Weltbau: auf der Strecke deren Hoehe,
         * sonst geradlinig zwischen dem Gelaende an den Enden der Linie.
         */
        private fun bridgeStructures() {
            val concrete = RouteGeometry.linColor(0.40, 0.39, 0.37)
            val concreteDark = RouteGeometry.linColor(0.27, 0.27, 0.26)
            for (r in net.roads) {
                if (r.source.brunnel != "bridge" || r.size < 2) continue
                val line = r.source.line
                val k = line.size / 2
                var total = 0.0
                for (i in 1 until k) total += hypot(line[2 * i] - line[2 * i - 2], line[2 * i + 1] - line[2 * i - 1])
                if (total < 6.0 || total > 400.0) continue
                val onTrack = (0 until r.size - 1).count { alongTrack(r, it) } * 2 > r.size - 1
                val h0 = ctx.ground.heightAt(line[0], line[1])
                val h1 = ctx.ground.heightAt(line[2 * k - 2], line[2 * k - 1])
                val top = DoubleArray(r.size)
                val hws = DoubleArray(r.size)
                for (i in 0 until r.size) {
                    val hit = if (onTrack) track.nearest(r.e[i], r.n[i], 20.0) else null
                    top[i] = if (hit != null) hit.u else max(ctx.ground.heightAt(r.e[i], r.n[i]), h0 + (h1 - h0) * (r.s[i] / total).coerceIn(0.0, 1.0)) + 0.05
                    hws[i] = if (hit != null) track.halfWidthAt(hit.distM) else r.halfWidth
                }
                var lastPier = Double.NEGATIVE_INFINITY
                for (i in 0 until r.size - 1) {
                    val me = (r.e[i] + r.e[i + 1]) / 2
                    val mn = (r.n[i] + r.n[i + 1]) / 2
                    if (!ctx.spec.contains(me, mn)) continue
                    val gap = (top[i] + top[i + 1]) / 2 - ctx.ground.heightAt(me, mn)
                    if (gap < 0.8) continue
                    for (side in intArrayOf(-1, 1)) {
                        val o0 = hws[i] + 0.35
                        val o1 = hws[i + 1] + 0.35
                        val ae = r.e[i] + r.re[i] * o0 * side
                        val an = r.n[i] + r.rn[i] * o0 * side
                        val be = r.e[i + 1] + r.re[i + 1] * o1 * side
                        val bn = r.n[i + 1] + r.rn[i + 1] * o1 * side
                        // Seitenflaeche des Ueberbaus (nach aussen)
                        val fe = r.re[i] * side
                        val fn = r.rn[i] * side
                        quad4(v(ae, top[i] - 0.85, an), v(be, top[i + 1] - 0.85, bn), v(be, top[i + 1] - 0.04, bn), v(ae, top[i] - 0.04, an), fe, fn, concrete)
                        if (onTrack) {
                            // Randkappe (0,25 m) aussen neben der Fahrbahn
                            val ie = r.e[i] + r.re[i] * (o0 - 0.3) * side
                            val inn = r.n[i] + r.rn[i] * (o0 - 0.3) * side
                            val je = r.e[i + 1] + r.re[i + 1] * (o1 - 0.3) * side
                            val jn = r.n[i + 1] + r.rn[i + 1] * (o1 - 0.3) * side
                            quad4(v(ae, top[i] - 0.04, an), v(be, top[i + 1] - 0.04, bn), v(be, top[i + 1] + 0.22, bn), v(ae, top[i] + 0.22, an), fe, fn, concrete)
                            quad4(v(ie, top[i] + 0.22, inn), v(je, top[i + 1] + 0.22, jn), v(be, top[i + 1] + 0.22, bn), v(ae, top[i] + 0.22, an), 0.0, 0.0, concrete, up = true)
                            quad4(v(ie, top[i] - 0.02, inn), v(je, top[i + 1] - 0.02, jn), v(je, top[i + 1] + 0.22, jn), v(ie, top[i] + 0.22, inn), -fe, -fn, concreteDark)
                        } else {
                            // Bruestung: Wand 0,85 m ueber dem Deck, 0,25 m dick
                            val ie = r.e[i] + r.re[i] * (o0 - 0.25) * side
                            val inn = r.n[i] + r.rn[i] * (o0 - 0.25) * side
                            val je = r.e[i + 1] + r.re[i + 1] * (o1 - 0.25) * side
                            val jn = r.n[i + 1] + r.rn[i + 1] * (o1 - 0.25) * side
                            quad4(v(ae, top[i] - 0.04, an), v(be, top[i + 1] - 0.04, bn), v(be, top[i + 1] + 0.85, bn), v(ae, top[i] + 0.85, an), fe, fn, concrete)
                            quad4(v(ie, top[i] + 0.85, inn), v(je, top[i + 1] + 0.85, jn), v(be, top[i + 1] + 0.85, bn), v(ae, top[i] + 0.85, an), 0.0, 0.0, concrete, up = true)
                            quad4(v(ie, top[i], inn), v(je, top[i + 1], jn), v(je, top[i + 1] + 0.85, jn), v(ie, top[i] + 0.85, inn), -fe, -fn, concreteDark)
                        }
                    }
                    // Unterseite
                    val l0e = r.e[i] - r.re[i] * (hws[i] + 0.35)
                    val l0n = r.n[i] - r.rn[i] * (hws[i] + 0.35)
                    val r0e = r.e[i] + r.re[i] * (hws[i] + 0.35)
                    val r0n = r.n[i] + r.rn[i] * (hws[i] + 0.35)
                    val l1e = r.e[i + 1] - r.re[i + 1] * (hws[i + 1] + 0.35)
                    val l1n = r.n[i + 1] - r.rn[i + 1] * (hws[i + 1] + 0.35)
                    val r1e = r.e[i + 1] + r.re[i + 1] * (hws[i + 1] + 0.35)
                    val r1n = r.n[i + 1] + r.rn[i + 1] * (hws[i + 1] + 0.35)
                    quad4(v(l0e, top[i] - 0.85, l0n), v(r0e, top[i] - 0.85, r0n), v(r1e, top[i + 1] - 0.85, r1n), v(l1e, top[i + 1] - 0.85, l1n), 0.0, 0.0, concreteDark, down = true)
                    // Pfeiler etwa alle 22 m (nicht an den Enden)
                    val s = r.s[i]
                    if (gap > 2.5 && s > 6.0 && s < total - 6.0 && s - lastPier >= 22.0) {
                        lastPier = s
                        val g = ctx.ground.heightAt(r.e[i], r.n[i])
                        val te = r.rn[i]
                        val tn = -r.re[i]
                        box(r.e[i], r.n[i], r.re[i], r.rn[i], hws[i] * 0.7, 0.45, g - 0.6, top[i] - 0.84, concrete)
                        // Pfeilerkopf
                        box(r.e[i] + te * 0.0, r.n[i] + tn * 0.0, r.re[i], r.rn[i], hws[i] + 0.1, 0.55, top[i] - 1.25, top[i] - 0.84, concreteDark)
                    }
                    if (!commit()) return
                }
            }
        }

        /**
         * Segment `i → i+1` gehoert zur Strecke: als „auf der Strecke" erkannt oder (Bruecken, deren Linie
         * die geglaettete Strecke nur knapp begleitet — schoenbuch km 7) beide Enden hoechstens 2 m neben
         * dem Fahrbahnrand und gleich gerichtet.
         */
        private fun alongTrack(r: RoadNet.Road, i: Int): Boolean {
            if (r.segOnTrack(i)) return true
            val a = track.nearest(r.e[i], r.n[i], 30.0) ?: return false
            val b = track.nearest(r.e[i + 1], r.n[i + 1], 30.0) ?: return false
            if (a.distance > track.halfWidthAt(a.distM) + 2.0 || b.distance > track.halfWidthAt(b.distM) + 2.0) return false
            val p = track.poseAt(a.distM)
            val h = Math.toRadians(p.headingDeg)
            val dx = r.e[i + 1] - r.e[i]
            val dy = r.n[i + 1] - r.n[i]
            val l = hypot(dx, dy)
            return l > 1e-6 && abs((dx * sin(h) + dy * cos(h)) / l) > 0.85
        }

        /** Viereck a b c d; Vorderseite nach ([fe], [fn]) waagrecht, bzw. nach oben/unten. */
        private fun quad4(a: Vec3, b: Vec3, c: Vec3, d: Vec3, fe: Double, fn: Double, color: Int, up: Boolean = false, down: Boolean = false) {
            val front = when {
                up -> Vec3(0.0, 1.0, 0.0)
                down -> Vec3(0.0, -1.0, 0.0)
                else -> Vec3(fe, 0.0, -fn)
            }
            if ((b - a).cross(d - a).dot(front) >= 0) quad(a, b, c, d, color) else quad(b, a, d, c, color)
        }

        // --- Brueckengelaender ---------------------------------------------------

        /**
         * Gelaender beidseits, wo die Strecke ueber eine Bruecke der Karte fuehrt (Wegstueck „auf der
         * Strecke" mit `brunnel = bridge`): Pfosten alle 1,8 m, Handlauf in 1,05 m und Knieleiste in
         * 0,5 m Hoehe ueber dem Belag; Holz bei Holzbelag, sonst Stahl (verzinkt grau).
         */
        private fun bridgeRailings() {
            val spans = ArrayList<DoubleArray>()
            for (r in net.roads) {
                if (r.source.brunnel != "bridge") continue
                for (i in 0 until r.size - 1) {
                    if (!alongTrack(r, i)) continue
                    val a = track.nearest(r.e[i], r.n[i], 30.0) ?: continue
                    val b = track.nearest(r.e[i + 1], r.n[i + 1], 30.0) ?: continue
                    if (abs(a.distM - b.distM) > 400.0) continue
                    spans += doubleArrayOf(min(a.distM, b.distM), max(a.distM, b.distM))
                }
            }
            spans.sortBy { it[0] }
            val merged = ArrayList<DoubleArray>()
            for (sp in spans) {
                val last = merged.lastOrNull()
                if (last != null && sp[0] <= last[1] + 3.0) last[1] = max(last[1], sp[1]) else merged += sp.copyOf()
            }
            for (sp in merged) {
                if (sp[1] - sp[0] < 3.0) continue
                val wood = track.surfaceAt((sp[0] + sp[1]) / 2).kind.visual == SurfaceKind.WOOD
                val color = if (wood) RouteGeometry.linColor(0.24, 0.18, 0.12) else RouteGeometry.linColor(0.42, 0.43, 0.44)
                val count = max(2, Math.round((sp[1] - sp[0]) / 1.8).toInt() + 1)
                for (side in intArrayOf(-1, 1)) {
                    var prev: DoubleArray? = null
                    for (j in 0 until count) {
                        val d = sp[0] + (sp[1] - sp[0]) * j / (count - 1)
                        val p = poseAt(d) ?: run { prev = null; null } ?: continue
                        val off = p.hw + 0.22
                        val e = sideE(p, side, off)
                        val n = sideN(p, side, off)
                        val y = p.u
                        box(e, n, p.te, p.tn, if (wood) 0.05 else 0.03, if (wood) 0.05 else 0.03, y - 0.05, y + 1.08, color)
                        val q = prev
                        if (q != null) {
                            beam(q[0], q[1], q[2] + 1.03, e, n, y + 1.03, if (wood) 0.045 else 0.025, if (wood) 0.03 else 0.025, color)
                            beam(q[0], q[1], q[2] + 0.5, e, n, y + 0.5, 0.02, 0.015, color)
                        }
                        prev = doubleArrayOf(e, n, y)
                        if (j % 6 == 5 && !commit()) return
                    }
                    if (!commit()) return
                }
            }
        }

        // --- Leitpfosten ---------------------------------------------------------

        /**
         * Leitpfosten (Abstand nach Kurvenradius) und Schutzplanken (Bruecken, hohe Boeschungen): [RoadsideLayer];
         * treten zurueck, wo echte Daten ([RealRoadside]) Planken fuehren.
         */
        private fun guidePosts() {
            used += RoadsideLayer.emit(ctx, out, ctx.budget.props - used)
        }

        // --- Laternen und Ortstafel ---------------------------------------------

        /** Die vollen OSM-Daten ([OsmDetail]) fuehren im Umkreis Laternen bzw. Zaeune — dann gelten nur diese. */
        private fun osmHas(kind: OsmKind, r: Double): Boolean {
            val s = ctx.spec
            return ctx.map.osmFeatures(s.minE - r, s.minN - r, s.maxE + r, s.maxN + r).any { it.kind == kind }
        }
        private val realLamps by lazy { osmHas(OsmKind.STREET_LAMP, 500.0) }
        private val realFences by lazy { osmHas(OsmKind.FENCE, 1500.0) }

        private fun streetLamps() = if (realLamps) Unit else eachGrid(36.0) { k, d ->
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
                // Name aus der Karte (naechster Ort); die Tafel waechst mit dem Namen (wie echte Ortstafeln).
                val name = placeName(e, n)
                val half = if (name == null) 0.5 else (0.3 + 0.022 * SignText.width(SignText.normalize(name))).coerceIn(0.45, 0.85)
                val y0 = g + 1.52
                val y1 = g + 2.17
                for (s in intArrayOf(-1, 1)) {
                    if (half > 0.55) scratch.addCylinder(v(e + ue * (half - 0.12) * s, g, n + un * (half - 0.12) * s), v(e + ue * (half - 0.12) * s, g + 2.2, n + un * (half - 0.12) * s), 0.03, 0.03, 6, capStart = false, color = steel)
                }
                plate(e - fe * 0.035, n - fn * 0.035, ue, un, -fe, -fn, half, y0, y1, RouteGeometry.linColor(0.36, 0.37, 0.38))
                if (name == null) {
                    plate(ce, cn, ue, un, fe, fn, half, y0, y1, RouteGeometry.linColor(0.03, 0.03, 0.03))
                    plate(ce + fe * 0.004, cn + fn * 0.004, ue, un, fe, fn, half - 0.03, y0 + 0.03, y1 - 0.03, RouteGeometry.linColor(0.78, 0.60, 0.03))
                    commit()
                } else if (commit()) {
                    signFace(name, ce, cn, ue, un, fe, fn, half, y0, y1)
                }
            }
        }

        /** Name des naechsten Orts (Dorf, Stadt, Weiler; Ortsteile nur, wenn sie deutlich naeher sind). */
        private fun placeName(e: Double, n: Double): String? {
            var best: MapPlace? = null
            var bestD = Double.MAX_VALUE
            for (p in ctx.map.places(e - 3000, n - 3000, e + 3000, n + 3000)) {
                val f = when (p.cls) {
                    "city", "town", "village", "hamlet" -> 1.0
                    "suburb", "quarter", "neighbourhood", "isolated_dwelling" -> 1.8
                    else -> continue
                }
                val d = kotlin.math.hypot(p.e - e, p.n - n) * f
                if (d < bestD) { bestD = d; best = p }
            }
            return best?.name?.takeIf { bestD < 3000.0 }
        }

        /**
         * Vorderseite der Ortstafel mit dem Namen als Textur (eigenes Material je Name, PNG 512 × 256,
         * [SignText.townSign]); 2 Dreiecke ausserhalb des Kleinteil-Budgets der Vertexfarben.
         */
        private fun signFace(name: String, e: Double, n: Double, ue: Double, un: Double, fe: Double, fn: Double, half: Double, y0: Double, y1: Double) {
            val aspect = 2 * half / (y1 - y0)
            val key = "x_sign_" + Integer.toHexString((name + "|" + Math.round(aspect * 20)).hashCode())
            val tex = ctx.textures.get(key, png = true) { SignText.townSign(name, aspect) }
            val m = out.mesh(WorldNames.NODE_PROPS, TileMaterial("x_mat_sign_" + key.substring(7), texture = tex, repeat = false, roughness = 0.6f))
            val front = Vec3(fe, 0.0, -fn)
            val a = v(e - ue * half, y0, n - un * half)
            val b = v(e + ue * half, y0, n + un * half)
            val c = v(e + ue * half, y1, n + un * half)
            val d = v(e - ue * half, y1, n - un * half)
            // Von vorn gesehen laeuft der Text von links nach rechts: links ist −(u) wenn u × oben zur Vorderseite zeigt.
            val leftToRight = (b - a).cross(d - a).dot(front) >= 0
            val (l0, r0, r1, l1) = if (leftToRight) listOf(a, b, c, d) else listOf(b, a, d, c)
            val i0 = m.vertex(l0.x, l0.y, l0.z, front.x, front.y, front.z, 0.0, 1.0)
            val i1 = m.vertex(r0.x, r0.y, r0.z, front.x, front.y, front.z, 1.0, 1.0)
            val i2 = m.vertex(r1.x, r1.y, r1.z, front.x, front.y, front.z, 1.0, 0.0)
            val i3 = m.vertex(l1.x, l1.y, l1.z, front.x, front.y, front.z, 0.0, 0.0)
            m.quad(i0, i1, i2, i3)
            used += 2
        }

        // --- Weidezaun -----------------------------------------------------------

        private fun fences() {
            if (realFences) return
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
                        // Nicht im Wald, den die Karte als Wiese fuehrt (Laser: Bestand im nDOM; Solitude, lidar.md 9.1),
                        // und nicht quer ueber einen Weg: Feld zum vorigen Pfosten nur, wenn dessen Mitte frei ist.
                        if (ctx.land.canopyAt(e, n) >= 0.45 || ctx.land.canopyAt(sideE(p, side, off + 3.0), sideN(p, side, off + 3.0)) >= 0.6) {
                            prev = null
                            continue
                        }
                        val pm = prev
                        if (pm != null && net.clearance((pm[0] + e) / 2, (pm[1] + n) / 2) < 1.0) prev = null
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
                // Der ganze Stapel (4 m lang, gut 2 m tief) muss frei und eben liegen
                // (am Hang steckte er sonst halb in der Boeschung oder schwebte).
                var ok = true
                var gMin = Double.MAX_VALUE
                var gMax = -Double.MAX_VALUE
                for (a in doubleArrayOf(-2.0, 0.0, 2.0)) for (c in doubleArrayOf(-1.0, 1.0)) {
                    val qe = e + p.te * a - p.tn * side * c
                    val qn = n + p.tn * a + p.te * side * c
                    if (!freeAnywhere(qe, qn, 0.3)) ok = false
                    val gq = ctx.ground.heightAt(qe, qn)
                    gMin = minOf(gMin, gq)
                    gMax = maxOf(gMax, gq)
                }
                if (!ok || gMax - gMin > 0.5 || !ctx.spec.contains(e, n)) continue
                val g = (gMin + gMax) / 2
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

        /** Die Karte fuehrt im Umkreis von 1,5 km Wanderwegweiser — dann gelten nur diese. */
        private val realGuideposts by lazy {
            val s = ctx.spec
            ctx.map.pois(s.minE - 1500, s.minN - 1500, s.maxE + 1500, s.maxN + 1500).any { it.cls == "information" && it.subclass == "guidepost" }
        }

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
                    // Wo die Karte echte Wegweiser fuehrt, keine erfundenen ([PoiLayer] stellt die echten auf).
                    if (realGuideposts) continue
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

        /**
         * Vorfahrtstrasse (Zeichen 306, gelbe Raute mit weissem Rand) dezent vor Einmuendungen: auf
         * Kraftverkehrsstrassen ausserorts, rechts der Fahrtrichtung 30 m vor der Stelle, an der ein
         * nachrangiger Weg auf die Strecke trifft; hoechstens eines je 150 m Strecke.
         */
        private fun prioritySigns() {
            val done = HashSet<Int>()
            for (r in net.roads) {
                if (r.spec.rail) continue
                for (i in 1 until r.size) {
                    if (r.onTrack[i] || r.onTrack[i - 1]) continue
                    val hit = track.nearest(r.e[i], r.n[i], 20.0) ?: continue
                    if (hit.distance > track.halfWidthAt(hit.distM) + 2.4) continue
                    val d = hit.distM - 30.0
                    if (!done.add(Math.floorDiv(d.toInt(), 150))) continue
                    val p = poseAt(d) ?: continue
                    if (!RouteLayer.isMotorRoad(p.sec) || landAt(p, 1) == LandClass.RESIDENTIAL) continue
                    val off = p.hw + 1.1
                    val e = sideE(p, 1, off)
                    val n = sideN(p, 1, off)
                    if (!free(e, n, 0.3)) continue
                    val g = ctx.ground.heightAt(e, n)
                    scratch.addCylinder(v(e, g - 0.05, n), v(e, g + 2.05, n), 0.03, 0.03, 6, capStart = false, color = RouteGeometry.linColor(0.34, 0.35, 0.36))
                    // Raute, Vorderseite dem Ankommenden entgegen
                    val fe = -p.te
                    val fn = -p.tn
                    val ue = p.tn
                    val un = -p.te
                    val cy = g + 1.85
                    diamond(e + fe * 0.03, n + fn * 0.03, ue, un, fe, fn, cy, 0.3, RouteGeometry.linColor(0.80, 0.80, 0.78))
                    diamond(e + fe * 0.036, n + fn * 0.036, ue, un, fe, fn, cy, 0.2, RouteGeometry.linColor(0.80, 0.58, 0.02))
                    diamond(e - fe * 0.005, n - fn * 0.005, -ue, -un, -fe, -fn, cy, 0.3, RouteGeometry.linColor(0.36, 0.37, 0.38))
                    commit()
                }
            }
        }

        /** Senkrechte Raute um ([e], [n], Mitte [cy]) mit halber Diagonale [r], Vorderseite nach ([fe], [fn]). */
        private fun diamond(e: Double, n: Double, ue: Double, un: Double, fe: Double, fn: Double, cy: Double, r: Double, color: Int) {
            val top = v(e, cy + r, n)
            val right = v(e + ue * r, cy, n + un * r)
            val bottom = v(e, cy - r, n)
            val left = v(e - ue * r, cy, n - un * r)
            val front = Vec3(fe, 0.0, -fn)
            if ((right - bottom).cross(top - bottom).dot(front) >= 0) quad(bottom, right, top, left, color) else quad(bottom, left, top, right, color)
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
