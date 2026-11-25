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
import kotlin.math.sqrt

/**
 * # Abziehbilder auf der Fahrbahn der Strecke (Teil des Knotens `route`)
 *
 * Was auf und an der Fahrbahn liegt und aus Lenkerhöhe ständig im Bild ist — als flache Geometrie
 * knapp über der Fahrbahn, nach Priorität (was nicht ins Budget passt, entfällt als Gruppe):
 *
 *  1. **Mittellinie** nach [MarkingPlan] (Leitlinie, Warnlinie, Fahrstreifenbegrenzung),
 *  2. **Randlinien**, an Einmündungen unterbrochen (Strich/Lücke [MOUTH_DASH_M]),
 *  3. **Warte- und Haltelinien** (Z 341 / Z 294), **Radfahr-/Schutzstreifen** (nur aus [RealRoadside]),
 *  4. **Bröselkante** am Übergang Asphalt → Bankett (statt einer geraden Messerkante),
 *  5. **innerorts:** Pflasterrinne am Bordstein (in jedem zweiten Ort), Straßenabläufe, Schachtdeckel,
 *  6. **Flickstellen** (Spurrinnen-, Graben-, Flächenflicken) mit Fugenverguss,
 *  7. **vergossene Risse** (Längsnaht, Randrisse).
 *
 * Alles hängt nur von Laufmeter, Strecke und Karte ab (deterministisch, in jeder Kachel gleich); ein
 * Element, das über das Ende eines gezeichneten Stücks hinausreicht, zeichnet jede Kachel zu ihrem
 * Teil. Die Eckpunkte liegen genau auf den Stationen der Fahrbahn ([Path] rechnet sie wie der
 * [RouteEmitter] für die gewählte Vereinfachungsstufe) — sonst sänken Abziehbilder in Wannen unter
 * das Band. Höhen über der Fahrbahn, wo sich zwei überdecken können mindestens 5 mm auseinander
 * (sonst Flimmern, `indoor-dev overlaps`): Flicken und Rinne 0,5 cm, Fugen, Risse, Deckel und Abläufe
 * 1,0 cm, Linien und Bröselkante [LINE_LIFT_M] = 1,6 cm (wie die bisherigen Linien; das Band des
 * Belagswechsels liegt bei 1,2 cm, Flicken und Risse halten 8 m Abstand zu ihm). Wo andere Streckenteile darüber liegen, wird wie die Fahrbahn geschnitten
 * ([RouteEnv.trackMask]); Linien enden an der Kante einer höherrangigen Straße, die die Strecke kreuzt.
 *
 * Materialien: `x_mat_route_marking` (MASK, abgenutzte Farbe), `x_mat_route_crumble` (MASK),
 * `x_mat_route_decal` (MASK, Schachtdeckel/Ablauf), `x_mat_route_patch` (Flicken, Asphalttextur) und
 * `x_mat_route_gutter` (Rinne, Pflastertextur) — die Texturbytes sind dieselben wie die der Fahrbahn und
 * stehen nur einmal in der GLB —, dazu `x_mat_route_plain` (Fugen, Risse; dasselbe Primitiv wie der
 * Bordstein). Keine eigenen Shader, keine neuen Materialparameter.
 */
internal class RoadDecals(private val ctx: TileContext, private val env: RouteEnv, private val units: IntArray) {
    private val track = ctx.track
    private val plan = MarkingPlan.of(track, ctx.land)
    private val real = ctx.map.roadside()
    private val toE: (Double) -> Double = { x -> x + ctx.spec.anchorE }
    private val toN: (Double) -> Double = { z -> ctx.spec.anchorN - z }

    /** Gruppe von Netzen je Material (schon geschnitten). */
    private class Group {
        val parts = LinkedHashMap<String, Pair<TileMaterial, MeshBuilder>>()
        val triangles: Int get() = parts.values.sumOf { it.second.triangleCount }
        fun mesh(mat: TileMaterial): MeshBuilder = parts.getOrPut(mat.name) { mat to MeshBuilder(64, 64) }.second
    }

    private val materials = HashMap<String, TileMaterial>()

    /** Dreiecke aller Gruppen bei feinster Teilung, höchstens [RESERVE_SHARE] des Fahrbahnbudgets. */
    fun reserve(): Int {
        if (ctx.budget.route <= 0) return 0
        val groups = build(RouteLayer.DETAILS[0])
        return min(groups.sumOf { it.triangles }, (ctx.budget.route * RESERVE_SHARE).toInt())
    }

    /** Zeichnet die Gruppen (nach Priorität), solange sie in [room] Dreiecke passen; gibt die Zahl zurück. */
    fun emit(detail: RouteLayer.Detail, out: TileOutput, room: Int): Int {
        var used = 0
        for (g in build(detail)) {
            val t = g.triangles
            if (t == 0 || used + t > room) continue
            for ((mat, mb) in g.parts.values) if (!mb.isEmpty) out.mesh(WorldNames.NODE_ROUTE, mat).append(mb)
            used += t
        }
        return used
    }

    // --- Stationen wie im RouteEmitter ------------------------------------------------

    /** Eine Station der Fahrbahn (dieselben Werte wie `Station` im [RouteEmitter]). */
    private class St {
        var dist = 0.0
        var seg = 0
        var e = 0.0
        var n = 0.0
        var u = 0.0
        var te = 0.0
        var tn = 0.0
        var grade = 0.0
        var kappa = 0.0
        var hw = 0.0
        var crown = 0.0
        var lift = 0.0
        val t2 = DoubleArray(2)
        val h1 = DoubleArray(2)
        val h2 = DoubleArray(2)
        val bankett = BooleanArray(2)
        val urban = BooleanArray(2)
        val kerb = DoubleArray(2)
    }

    /** Die Stationen eines gezeichneten Stücks (Einheiten `a..b`) und Abfragen am Laufmeter. */
    private inner class Path(a: Int, b: Int, detail: RouteLayer.Detail) {
        val st: Array<St>
        val from: Double
        val to: Double

        init {
            val xs = ArrayList<Double>()
            var i = a
            while (i < b) {
                val pa = env.profile(i)
                var span = 1
                if (detail.merge && i % 2 == 0 && i + 2 <= b) {
                    val pm = env.profile(i + 1)
                    val pb = env.profile(i + 2)
                    val same = pa.sectionIndex == pm.sectionIndex && RouteGeometry.segSection(track, i + 1) == pa.sectionIndex
                    if (same && !pa.nearRoad && !pm.nearRoad && !pb.nearRoad && !pa.nearOther && !pb.nearOther) span = 2
                }
                var sub = 1
                for (q in 0 until span) sub = max(sub, RouteGeometry.subdivisions(track, i + q, detail.maxSub))
                for (p in 0 until sub) xs.add(i + span.toDouble() * p / sub)
                i += span
            }
            xs.add(b.toDouble())
            st = Array(xs.size) { station(xs[it]) }
            from = st.first().dist
            to = st.last().dist
        }

        private fun station(x: Double): St {
            val t = track.track
            val s = St()
            val seg = min(floor(x).toInt(), t.sampleCount - 2).coerceAtLeast(0)
            val f = x - seg
            val tmp = DoubleArray(5)
            RouteGeometry.centerAt(track, seg, f, tmp)
            s.seg = seg
            s.e = tmp[0]; s.n = tmp[1]; s.u = tmp[2]; s.te = tmp[3]; s.tn = tmp[4]
            s.dist = min(x * track.stepM, track.lengthM)
            s.grade = (t.u[seg + 1] - t.u[seg]) / max(1e-6, t.distAt(seg + 1) - t.distAt(seg))
            s.kappa = RouteGeometry.curvatureAt(track, seg) * (1 - f) + RouteGeometry.curvatureAt(track, seg + 1) * f
            s.hw = track.halfWidthAt(s.dist)
            val p0 = env.profile(seg)
            val p1 = env.profile(seg + 1)
            s.crown = p0.crown + (p1.crown - p0.crown) * f
            if (p0.nearOther || p1.nearOther) {
                val oth = DoubleArray(3)
                env.hood.other(s.e, s.n, s.dist, RouteEnv.OTHER_GAP_M, oth)
                if (oth[1] >= 0 && oth[1] < seg && oth[0] < s.hw + oth[2]) s.lift = 0.008
            }
            for (side in 0..1) {
                val sgn = if (side == 0) -1.0 else 1.0
                fun mix(a: Double, b: Double) = a + (b - a) * f
                s.bankett[side] = p0.style[side] == EdgeStyle.BANKETT && p1.style[side] == EdgeStyle.BANKETT
                s.urban[side] = p0.style[side] == EdgeStyle.URBAN && p1.style[side] == EdgeStyle.URBAN
                s.kerb[side] = mix(p0.kerb[side], p1.kerb[side])
                s.t2[side] = s.hw + mix(p0.shoulderW[side], p1.shoulderW[side])
                s.h1[side] = s.u - s.crown * s.hw + s.lift
                s.h2[side] = max(s.h1[side] + mix(p0.drop[side], p1.drop[side]) + s.kerb[side], groundAt(s, sgn * s.t2[side]) + 0.03)
            }
            return s
        }

        /** Index der Station am Anfang des Stücks, in dem [d] liegt. */
        fun index(d: Double): Int {
            var lo = 0
            var hi = st.size - 2
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (st[mid].dist <= d) lo = mid else hi = mid - 1
            }
            return lo
        }

        /** Laufmeter, an denen ein Abziehbild von [d0] bis [d1] Eckpunkte braucht: Enden und alle Stationen dazwischen. */
        fun samples(d0: Double, d1: Double): DoubleArray {
            val out = ArrayList<Double>()
            out.add(d0)
            var j = index(d0) + 1
            while (j < st.size && st[j].dist < d1 - 1e-6) {
                if (st[j].dist > d0 + 1e-6) out.add(st[j].dist)
                j++
            }
            out.add(d1)
            return out.toDoubleArray()
        }
    }

    private val meshCell = level0TerrainCellM(ctx.quality)

    private fun pointE(s: St, t: Double): Double = s.e + s.tn * RouteGeometry.limitOffset(t, s.kappa)
    private fun pointN(s: St, t: Double): Double = s.n - s.te * RouteGeometry.limitOffset(t, s.kappa)

    private fun groundAt(s: St, t: Double): Double {
        val e = pointE(s, t)
        val n = pointN(s, t)
        return max(ctx.ground.heightAt(e, n), ctx.meshHeightAt(e, n, meshCell))
    }

    /** Höhe der Oberfläche bei Versatz [t]: Fahrbahn (Dachprofil), dahinter das Bankett bzw. der Randstreifen. */
    private fun surfaceH(s: St, t: Double): Double {
        val a = abs(t)
        if (a <= s.hw) return s.u - s.crown * a + s.lift
        val side = if (t < 0) 0 else 1
        val span = max(0.05, s.t2[side] - s.hw)
        return s.h1[side] + (s.h2[side] - s.h1[side]) * ((a - s.hw) / span).coerceIn(0.0, 1.0)
    }

    private fun slopeAt(s: St, t: Double): Double {
        val a = abs(t)
        val sg = if (t < 0) -1.0 else 1.0
        if (a <= s.hw) return if (a < 1e-6) 0.0 else -s.crown * sg
        val side = if (t < 0) 0 else 1
        return sg * (s.h2[side] - s.h1[side]) / max(0.05, s.t2[side] - s.hw)
    }

    /** Eckpunkt am Laufmeter [d] (zwischen zwei Stationen linear) bei Versatz [tOf] der Station, [lift] über der Oberfläche. */
    private fun vertexAt(m: MeshBuilder, path: Path, d: Double, tOf: (St) -> Double, lift: Double, u: Double, v: Double, color: Int, uOfT: ((Double) -> Double)? = null): Int {
        val j = path.index(d)
        val a = path.st[j]
        val b = path.st[min(j + 1, path.st.size - 1)]
        val f = if (b.dist - a.dist > 1e-9) ((d - a.dist) / (b.dist - a.dist)).coerceIn(0.0, 1.0) else 0.0
        val ta = tOf(a)
        val tb = tOf(b)
        val e = pointE(a, ta) + (pointE(b, tb) - pointE(a, ta)) * f
        val n = pointN(a, ta) + (pointN(b, tb) - pointN(a, ta)) * f
        val h = surfaceH(a, ta) + (surfaceH(b, tb) - surfaceH(a, ta)) * f + lift
        val uu = if (uOfT != null) uOfT(ta + (tb - ta) * f) else u
        val slope = slopeAt(a, ta)
        val te = a.te + (b.te - a.te) * f
        val tn = a.tn + (b.tn - a.tn) * f
        val grade = a.grade
        val ne = -grade * te - slope * tn
        val nn = -grade * tn + slope * te
        val len = sqrt(ne * ne + nn * nn + 1.0)
        return m.vertex(ctx.x(e), ctx.y(h), ctx.z(n), ne / len, 1.0 / len, -nn / len, uu, v, color)
    }

    /**
     * Streifen von [d0] bis [d1] zwischen den Versätzen [tA] < [tB] (je Station gerechnet). [vScale]: Meter je
     * `v`; [uA]/[uB]: `u` an den Rändern.
     */
    private fun strip(m: MeshBuilder, path: Path, d0: Double, d1: Double, tA: (St) -> Double, tB: (St) -> Double, lift: Double, color: (Double) -> Int, uA: Double = 0.0, uB: Double = 1.0, vScale: Double = 3.0, vOff: Double = 0.0, uOfT: ((Double) -> Double)? = null) {
        val a = max(d0, path.from)
        val b = min(d1, path.to)
        if (b - a < 0.05) return
        var prev = -1
        for (d in path.samples(a, b)) {
            val c = color(d)
            val v = d / vScale + vOff
            val i0 = vertexAt(m, path, d, tA, lift, uA, v, c, uOfT)
            vertexAt(m, path, d, tB, lift, uB, v, c, uOfT)
            if (prev >= 0) m.quad(prev, prev + 1, i0 + 1, i0)
            prev = i0
        }
    }

    // --- Materialien -------------------------------------------------------------------

    private fun mat(name: String, make: () -> TileMaterial): TileMaterial = materials.getOrPut(name) { make() }

    private val markingMat get() = mat(MAT_MARKING) { TileMaterial(MAT_MARKING, RoadDecalTextures.marking(ctx.textures), roughness = 0.78f, alphaMode = GlbAlphaMode.MASK) }
    private val crumbleMat get() = mat(MAT_CRUMBLE) { TileMaterial(MAT_CRUMBLE, RoadDecalTextures.crumble(ctx.textures), roughness = 0.95f, alphaMode = GlbAlphaMode.MASK) }
    private val decalMat get() = mat(MAT_DECAL) { TileMaterial(MAT_DECAL, RoadDecalTextures.atlas(ctx.textures), repeat = false, roughness = 0.8f, alphaMode = GlbAlphaMode.MASK) }
    private val plainMat get() = mat(RouteLayer.MAT_PLAIN) { TileMaterial(RouteLayer.MAT_PLAIN, roughness = 0.85f) }

    /**
     * Belagstextur unter eigenem Namen ([MAT_PATCH], [MAT_GUTTER]): Die Bytes der Textur bettet die GLB nur
     * einmal ein; das eigene Primitiv hält die Fahrbahn selbst (Fläche, Höhe, Prüfungen) unverändert.
     */
    private fun surfaceMat(name: String, kind: SurfaceKind): TileMaterial = mat(name) {
        TileMaterial(
            name, RouteTextures.surfaceTexture(ctx.textures, kind, ctx.quality == IndoorQuality.LOW),
            roughness = if (kind.visual == SurfaceKind.ASPHALT) 0.86f else 0.95f,
        )
    }

    // --- Aufbau ------------------------------------------------------------------------

    private fun build(detail: RouteLayer.Detail): List<Group> {
        val groups = List(7) { Group() }
        val keep = units.filter { !env.isDuplicate(it) }
        var a = 0
        while (a < keep.size) {
            var b = a
            while (b + 1 < keep.size && keep[b + 1] == keep[b] + 1) b++
            val path = Path(RouteGeometry.unitStart(keep[a]), RouteGeometry.unitEnd(track, keep[b]), detail)
            if (path.st.size >= 2) run(path, groups)
            a = b + 1
        }
        return groups
    }

    private fun run(path: Path, g: List<Group>) {
        val jn = junctions
        centreLine(path, g[0], jn)
        edgeLines(path, g[1], jn)
        controlLines(path, g[2], jn)
        cycleLanes(path, g[2])
        crumble(path, g[3])
        urbanDetails(path, g[4])
        patches(path, g[5])
        cracks(path, g[6])
    }

    /** Schneidet [src] wie die Fahrbahn: an früheren Streckenteilen und an [stop]; hängt an [dst]. */
    private fun clipRoad(src: MeshBuilder, dst: MeshBuilder, d0: Double, stop: PlanMask?) {
        if (src.isEmpty) return
        var s = src
        if (stop != null && !stop.isEmpty) {
            val t = MeshBuilder(64, 64)
            clipInto(s, t, stop, toE, toN)
            s = t
        }
        clipInto(s, dst, env.trackMask, toE, toN, skipFrom = d0 - 3.0, skipTo = Double.MAX_VALUE, inner = true)
    }

    /** Schneidet wie der Randstreifen (Bankett, Gras) an allen Sperrflächen ausser der eigenen Fahrbahn ±[own] m. */
    private fun clipEdge(src: MeshBuilder, dst: MeshBuilder, d0: Double, d1: Double, own: Double, inner: Boolean) {
        if (src.isEmpty) return
        val t = MeshBuilder(64, 64)
        clipInto(src, t, env.mask, toE, toN, skipFrom = d0 - own, skipTo = d1 + own, inner = inner)
        if (inner) clipInto(t, dst, env.trackMask, toE, toN, skipFrom = d0 - 3.0, skipTo = Double.MAX_VALUE, inner = true) else dst.append(t)
    }

    private fun paintColor(d: Double, k: Int): Int {
        // Nicht steril weiss: leicht gelblich, mit Schmutz je Strich verschieden.
        val w = Noise.hash01(floor(d / 3.0).toInt(), k, 0x3a71)
        val c = 0.74 + 0.10 * w
        return RouteGeometry.linColor(c, c * 0.985, c * 0.94)
    }

    // --- 1 Mittellinie ---------------------------------------------------------------

    private fun centreLine(path: Path, g: Group, jn: Junctions) {
        val pieces = plan.centreDashes(path.from, path.to)
        if (pieces.isEmpty()) return
        val tmp = MeshBuilder(64, 64)
        val dst = g.mesh(markingMat)
        val hasLanes = laneSides(path)
        for (p in pieces) {
            // Mit Schutzstreifen auf beiden Seiten keine Leitlinie (Kernfahrbahn).
            val mid = (p[0] + p[1]) / 2
            if (hasLanes != null && hasLanes(mid, 0) == CycleLaneKind.ADVISORY && hasLanes(mid, 1) == CycleLaneKind.ADVISORY) continue
            tmp.clear()
            strip(tmp, path, p[0], p[1], { -LINE_HALF_M }, { LINE_HALF_M }, LINE_LIFT_M, { d -> paintColor(d, 1) })
            clipRoad(tmp, dst, p[0], jn.stop)
        }
    }

    // --- 2 Randlinien ----------------------------------------------------------------

    private fun edgeLines(path: Path, g: Group, jn: Junctions) {
        val runs = plan.edgeRuns(path.from, path.to)
        if (runs.isEmpty()) return
        val tmp = MeshBuilder(64, 64)
        val dst = g.mesh(markingMat)
        for (side in 0..1) {
            val mouths = jn.mouths[side]
            for (r in runs) {
                // Durchgezogen ausserhalb der Einmuendungen, unterbrochen darin.
                val solid = subtract(r[0], r[1], mouths)
                val broken = ArrayList<DoubleArray>()
                for (m in mouths) {
                    val a = max(r[0], m[0])
                    val b = min(r[1], m[1])
                    if (b > a) MarkingPlan.pattern(a, b, MOUTH_DASH_M, 2 * MOUTH_DASH_M, broken)
                }
                for ((list, stop) in listOf(solid to jn.stop, broken to null)) {
                    for (p in list) {
                        tmp.clear()
                        if (side == 0) {
                            strip(tmp, path, p[0], p[1], { s -> -(s.hw - EDGE_IN_M) }, { s -> -(s.hw - EDGE_IN_M - 2 * LINE_HALF_M) }, LINE_LIFT_M, { d -> paintColor(d, 2) })
                        } else {
                            strip(tmp, path, p[0], p[1], { s -> s.hw - EDGE_IN_M - 2 * LINE_HALF_M }, { s -> s.hw - EDGE_IN_M }, LINE_LIFT_M, { d -> paintColor(d, 3) })
                        }
                        clipRoad(tmp, dst, p[0], stop)
                    }
                }
            }
        }
    }

    // --- 3 Warte-/Haltelinien, Radfahrstreifen ------------------------------------------

    private fun controlLines(path: Path, g: Group, jn: Junctions) {
        if (jn.lines.isEmpty()) return
        val tmp = MeshBuilder(64, 64)
        val dst = g.mesh(markingMat)
        for (l in jn.lines) {
            if (l.dist < path.from - 1 || l.dist > path.to + 1) continue
            // Rechte Haelfte (eigene Zufahrt) bzw. linke (Gegenverkehr); Querstriche 0,5 m, Luecke 0,25 m.
            val d0 = (l.dist - CONTROL_W_M / 2).coerceIn(path.from, path.to)
            val d1 = (l.dist + CONTROL_W_M / 2).coerceIn(path.from, path.to)
            if (d1 - d0 < 0.2) continue
            val j = path.index(l.dist)
            val hw = path.st[j].hw
            val inner = 0.12
            val outer = hw - 0.42
            if (outer - inner < 0.8) continue
            val pieces = ArrayList<DoubleArray>()
            if (l.solid) pieces.add(doubleArrayOf(inner, outer)) else {
                var x = inner
                while (x + 0.2 < outer) {
                    pieces.add(doubleArrayOf(x, min(outer, x + 0.5)))
                    x += 0.75
                }
            }
            for (p in pieces) {
                tmp.clear()
                val sg = if (l.right) 1.0 else -1.0
                val ta = if (sg > 0) p[0] else -p[1]
                val tb = if (sg > 0) p[1] else -p[0]
                strip(tmp, path, d0, d1, { ta }, { tb }, LINE_LIFT_M, { d -> paintColor(d, 5) }, vScale = 1.5)
                clipRoad(tmp, dst, d0, null)
            }
        }
    }

    /** Radfahr-/Schutzstreifen der Strecke aus echten Daten: Art je Laufmeter und Seite, sonst `null`. */
    private fun laneSides(path: Path): ((Double, Int) -> CycleLaneKind?)? {
        val s = ctx.spec
        val lanes = real.cycleLanes(s.minE - 30, s.minN - 30, s.maxE + 30, s.maxN + 30)
        if (lanes.isEmpty()) return null
        return { d, side ->
            val tmp = DoubleArray(5)
            RouteGeometry.centerAtDist(track, d, tmp)
            var found: CycleLaneKind? = null
            for (l in lanes) {
                val k = l.line.size / 2
                for (i in 0 until k - 1) {
                    val ax = l.line[2 * i]; val ay = l.line[2 * i + 1]
                    val dx = l.line[2 * i + 2] - ax; val dy = l.line[2 * i + 3] - ay
                    val l2 = dx * dx + dy * dy
                    if (l2 < 1e-6) continue
                    val f = (((tmp[0] - ax) * dx + (tmp[1] - ay) * dy) / l2).coerceIn(0.0, 1.0)
                    if (hypot(ax + dx * f - tmp[0], ay + dy * f - tmp[1]) > 3.0) continue
                    val cosA = (dx * tmp[3] + dy * tmp[4]) / sqrt(l2)
                    if (abs(cosA) < 0.85) continue
                    // Seite der Strecke: rechts (1) / links (0); entgegen der Linienrichtung gespiegelt.
                    val laneRight = if (cosA > 0) l.side >= 0 else l.side <= 0
                    val laneLeft = if (cosA > 0) l.side <= 0 else l.side >= 0
                    if ((side == 1 && laneRight) || (side == 0 && laneLeft)) found = l.kind
                }
            }
            found
        }
    }

    private fun cycleLanes(path: Path, g: Group) {
        val at = laneSides(path) ?: return
        val tmp = MeshBuilder(64, 64)
        val dst = g.mesh(markingMat)
        val step = 2.0
        for (side in 0..1) {
            var d = floor(path.from / step) * step
            while (d < path.to) {
                val kind = at(d + step / 2, side)
                if (kind != null) {
                    val a = max(d, path.from)
                    val b = min(d + step, path.to)
                    val pieces = ArrayList<DoubleArray>()
                    if (kind == CycleLaneKind.ADVISORY) MarkingPlan.pattern(a, b, 1.0, 2.0, pieces) else pieces.add(doubleArrayOf(a, b))
                    val w = if (kind == CycleLaneKind.ADVISORY) 2 * LINE_HALF_M else 0.25
                    val off = if (kind == CycleLaneKind.ADVISORY) 1.5 else 1.85
                    for (p in pieces) {
                        tmp.clear()
                        if (side == 1) strip(tmp, path, p[0], p[1], { s -> s.hw - off - w }, { s -> s.hw - off }, LINE_LIFT_M, { x -> paintColor(x, 6) })
                        else strip(tmp, path, p[0], p[1], { s -> -(s.hw - off) }, { s -> -(s.hw - off - w) }, LINE_LIFT_M, { x -> paintColor(x, 7) })
                        clipRoad(tmp, dst, p[0], null)
                    }
                }
                d += step
            }
        }
    }

    // --- 4 Broeselkante ----------------------------------------------------------------

    private fun crumble(path: Path, g: Group) {
        val tmp = MeshBuilder(64, 64)
        val dst = g.mesh(crumbleMat)
        for (side in 0..1) {
            // Abschnitte, in denen beide Nachbarstationen Bankett haben.
            var i = 0
            val st = path.st
            while (i < st.size - 1) {
                if (!(st[i].bankett[side] && st[i + 1].bankett[side])) { i++; continue }
                var j = i
                while (j + 1 < st.size - 1 && st[j + 1].bankett[side] && st[j + 2].bankett[side]) j++
                val d0 = st[i].dist
                val d1 = st[j + 1].dist
                tmp.clear()
                val col = { d: Double -> val c = 0.80 + 0.16 * RouteGeometry.tone(d, side * 7.0, 0x2c3); RouteGeometry.linColor(c, c, c * 0.98) }
                // Drei Eckpunkte je Station: auf dem Asphalt, an der Kante (Knick zum Bankett), auf dem Bankett.
                if (side == 1) {
                    strip3(tmp, path, d0, d1, { s -> s.hw - CRUMBLE_IN_M }, { s -> s.hw }, { s -> min(s.hw + CRUMBLE_OUT_M, s.t2[1] - 0.05) }, CRUMBLE_LIFT_M, col, 0.0, CRUMBLE_IN_M / (CRUMBLE_IN_M + CRUMBLE_OUT_M), 1.0)
                } else {
                    strip3(tmp, path, d0, d1, { s -> -min(s.hw + CRUMBLE_OUT_M, s.t2[0] - 0.05) }, { s -> -s.hw }, { s -> -(s.hw - CRUMBLE_IN_M) }, CRUMBLE_LIFT_M, col, 1.0, CRUMBLE_IN_M / (CRUMBLE_IN_M + CRUMBLE_OUT_M), 0.0)
                }
                clipEdge(tmp, dst, d0, d1, 4.0, inner = false)
                i = j + 1
            }
        }
    }

    /** Wie [strip], mit drei Eckpunkten je Laufmeter (Knick in der Mitte). */
    private fun strip3(m: MeshBuilder, path: Path, d0: Double, d1: Double, tA: (St) -> Double, tM: (St) -> Double, tB: (St) -> Double, lift: Double, color: (Double) -> Int, uA: Double, uM: Double, uB: Double) {
        val a = max(d0, path.from)
        val b = min(d1, path.to)
        if (b - a < 0.05) return
        var prev = -1
        for (d in path.samples(a, b)) {
            val c = color(d)
            val v = d / CRUMBLE_V_M
            val i0 = vertexAt(m, path, d, tA, lift, uA, v, c)
            vertexAt(m, path, d, tM, lift, uM, v, c)
            vertexAt(m, path, d, tB, lift, uB, v, c)
            if (prev >= 0) {
                m.quad(prev, prev + 1, i0 + 1, i0)
                m.quad(prev + 1, prev + 2, i0 + 2, i0 + 1)
            }
            prev = i0
        }
    }

    // --- 5 innerorts -----------------------------------------------------------------

    private fun urbanDetails(path: Path, g: Group) {
        val st = path.st
        val tmp = MeshBuilder(64, 64)
        for (side in 0..1) {
            val sgn = if (side == 0) -1.0 else 1.0
            var i = 0
            while (i < st.size - 1) {
                if (!(st[i].urban[side] && st[i + 1].urban[side] && st[i].kerb[side] > 0.05)) { i++; continue }
                var j = i
                while (j + 1 < st.size - 1 && st[j + 1].urban[side] && st[j + 2].urban[side] && st[j + 1].kerb[side] > 0.05) j++
                val d0 = st[i].dist
                val d1 = st[j + 1].dist
                val kind = track.surfaceAt((d0 + d1) / 2).kind.visual
                val paved = MarkingPlan.paved(kind)
                // Pflasterrinne am Bordstein: je Ort (Belagsabschnitt) fest, in gut der Haelfte.
                val secIdx = RouteGeometry.segSection(track, st[i].seg)
                if (paved && Noise.hash01(secIdx, side, 0x6077) < 0.55) {
                    tmp.clear()
                    val cob = surfaceMat(MAT_GUTTER, SurfaceKind.COBBLESTONE)
                    val col = { d: Double -> val c = 0.74 + 0.12 * RouteGeometry.tone(d, 3.0 + side, 0x617); RouteGeometry.linColor(c, c * 0.99, c * 0.96) }
                    if (side == 1) strip(tmp, path, d0, d1, { s -> s.hw - GUTTER_W_M }, { s -> s.hw - 0.01 }, PATCH_LIFT_M, col, uA = 0.0, uB = GUTTER_W_M / 2.0, vScale = 8.0)
                    else strip(tmp, path, d0, d1, { s -> -(s.hw - 0.01) }, { s -> -(s.hw - GUTTER_W_M) }, PATCH_LIFT_M, col, uA = 0.0, uB = GUTTER_W_M / 2.0, vScale = 8.0)
                    clipEdge(tmp, g.mesh(cob), d0, d1, 15.0, inner = true)
                }
                // Strassenablaeufe etwa alle 30 m (versetzt je Seite).
                if (paved) {
                    var k = floor(d0 / GULLY_M).toInt()
                    while (k * GULLY_M < d1) {
                        val h = Noise.hash01(k, side, 0x6a11)
                        val d = k * GULLY_M + 4.0 + h * (GULLY_M - 8.0)
                        k++
                        if (d < d0 + 1 || d > d1 - 1 || h > 0.88 || nearSectionChange(d)) continue
                        tmp.clear()
                        val t0 = { s: St -> sgn * (s.hw - 0.05) }
                        val t1 = { s: St -> sgn * (s.hw - 0.37) }
                        stamp(tmp, path, d - 0.25, d + 0.25, if (side == 1) t1 else t0, if (side == 1) t0 else t1, 0.5, 0.0, 1.0, 0.5, GULLY_LIFT_M, side == 1)
                        clipEdge(tmp, g.mesh(decalMat), d - 0.3, d + 0.3, 15.0, inner = true)
                    }
                }
                i = j + 1
            }
        }
        // Schachtdeckel in der Fahrbahn (Kanal in der Strassenachse), innerorts auf befestigten Strassen.
        var k = floor(path.from / MANHOLE_M).toInt()
        while (k * MANHOLE_M < path.to) {
            val h = Noise.hash01(k, 0, 0x5c4d)
            val d = k * MANHOLE_M + 3.0 + h * (MANHOLE_M - 6.0)
            k++
            if (d < path.from + 0.5 || d > path.to - 0.5 || h > 0.62 || nearSectionChange(d)) continue
            val j = path.index(d)
            val s = path.st[j]
            if (!(s.urban[0] || s.urban[1])) continue
            if (!MarkingPlan.paved(track.surfaceAt(d).kind) && track.surfaceAt(d).kind.visual != SurfaceKind.PAVING_STONES) continue
            val off = (Noise.hash01(k, 1, 0x5c4e) - 0.5) * min(1.6, s.hw * 0.6)
            if (patchNear(d - 1.0, d + 1.0) || (plan.centreAt(d) != MarkingPlan.Centre.NONE && abs(off) < MANHOLE_R_M + 0.15)) continue
            tmp.clear()
            disc(tmp, path, d, off, MANHOLE_R_M)
            clipRoad(tmp, g.mesh(decalMat), d - 1, null)
        }
    }

    /** Näher als 7 m an einem Belagswechsel (dort liegt das Band des Wechsels 1,2 cm über der Fahrbahn)? */
    private fun nearSectionChange(d: Double): Boolean {
        val sec = track.surfaceAt(d)
        return (sec.fromM > 0 && d - sec.fromM < 7.0) || (sec.toM < track.lengthM && sec.toM - d < 7.0)
    }

    /** Rechteck aus dem Atlas ([u0]..[u1], [v0]..[v1]) zwischen den Versätzen; [flip] dreht die Textur quer. */
    private fun stamp(m: MeshBuilder, path: Path, d0: Double, d1: Double, tA: (St) -> Double, tB: (St) -> Double, u0: Double, v0: Double, u1: Double, v1: Double, lift: Double, flip: Boolean) {
        val a = max(d0, path.from)
        val b = min(d1, path.to)
        if (b - a < 0.05) return
        val c = RouteGeometry.linColor(0.9, 0.9, 0.9)
        var prev = -1
        for (d in path.samples(a, b)) {
            val f = (d - d0) / (d1 - d0)
            val v = v0 + (v1 - v0) * f
            val i0 = vertexAt(m, path, d, tA, lift, if (flip) u1 else u0, v, c)
            vertexAt(m, path, d, tB, lift, if (flip) u0 else u1, v, c)
            if (prev >= 0) m.quad(prev, prev + 1, i0 + 1, i0)
            prev = i0
        }
    }

    /** Runder Deckel um Laufmeter [d], Versatz [t]: Faecher aus 12 Dreiecken, Atlas links unten. */
    private fun disc(m: MeshBuilder, path: Path, d: Double, t: Double, r: Double) {
        val c = RouteGeometry.linColor(0.9, 0.9, 0.9)
        val center = vertexAt(m, path, d, { t }, MANHOLE_LIFT_M, 0.25, 0.75, c)
        val seg = 12
        val first = m.vertexCount
        for (k in 0 until seg) {
            val a = 2 * PI * k / seg
            val dd = (d + r * cos(a)).coerceIn(path.from, path.to)
            val tt = t + r * sin(a)
            vertexAt(m, path, dd, { tt }, MANHOLE_LIFT_M, 0.25 + 0.24 * sin(a), 0.75 + 0.24 * cos(a), c)
        }
        // Umlaufsinn: Laufmeter vorwaerts, Versatz nach rechts — im Grundriss im Uhrzeigersinn, also (k+1, k) gegen ihn.
        for (k in 0 until seg) m.tri(center, first + (k + 1) % seg, first + k)
    }

    // --- 6 Flickstellen ----------------------------------------------------------------

    /** Eine Flickstelle (global nach Laufmeter-Block [PATCH_M] gewürfelt). */
    private class Patch(val d0: Double, val d1: Double, val type: Int, val right: Boolean, val fresh: Boolean)

    /** Die Flickstelle im Block [k], oder `null`. */
    private fun patchAt(k: Int): Patch? {
        val h = Noise.hash01(k, 0, 0x7f1a)
        val mid = k * PATCH_M + PATCH_M / 2
        if (mid < 0 || mid > track.lengthM) return null
        val sec = track.surfaceAt(mid)
        if (sec.kind.visual != SurfaceKind.ASPHALT) return null
        val prob = when {
            plan.urbanAt(mid) -> 0.35
            MarkingPlan.rankOf(sec) >= 5 -> 0.25
            else -> 0.45
        }
        if (h >= prob) return null
        val r = Noise.hash01(k, 1, 0x7f1b)
        val type = if (r < 0.45) 0 else if (r < 0.75) 1 else 2
        val len = when (type) {
            0 -> 3.0 + 7.0 * Noise.hash01(k, 2, 0x7f1c)
            1 -> 0.8 + 0.8 * Noise.hash01(k, 2, 0x7f1c)
            else -> 6.0 + 12.0 * Noise.hash01(k, 2, 0x7f1c)
        }
        val d0 = k * PATCH_M + Noise.hash01(k, 3, 0x7f1d) * (PATCH_M - len)
        val d1 = d0 + len
        // Nur innerhalb eines Asphaltabschnitts, nicht am Belagswechsel (dort liegt das Band des Wechsels).
        if (d0 < sec.fromM + 8 || d1 > sec.toM - 8) return null
        return Patch(d0, d1, type, Noise.hash01(k, 4, 0x7f1e) < 0.7, Noise.hash01(k, 5, 0x7f1f) < 0.5)
    }

    private fun patchSpan(p: Patch): Pair<(St) -> Double, (St) -> Double> = when (p.type) {
        // Spurrinnenflicken in einer Rollspur.
        0 -> if (p.right) Pair({ s: St -> s.hw * 0.22 }, { s: St -> min(s.hw - 0.45, s.hw * 0.22 + 1.25) })
        else Pair({ s: St -> -min(s.hw - 0.45, s.hw * 0.22 + 1.25) }, { s: St -> -s.hw * 0.22 })
        // Leitungsgraben quer über eine bzw. beide Hälften.
        1 -> if (p.right) Pair({ _: St -> -0.05 }, { s: St -> s.hw - 0.40 }) else Pair({ s: St -> -(s.hw - 0.40) }, { s: St -> s.hw - 0.40 })
        // Flächenflicken. Überall 0,40 m Abstand zum Rand: dort liegen Rinne bzw. Bröselkante.
        else -> Pair({ s: St -> -(s.hw - 0.40) }, { s: St -> s.hw - 0.40 })
    }

    private fun patches(path: Path, g: Group) {
        val tmp = MeshBuilder(64, 64)
        val spec = RouteTextures.spec(SurfaceKind.ASPHALT)
        var k = floor(path.from / PATCH_M).toInt() - 1
        while (k * PATCH_M < path.to) {
            val p = patchAt(k++) ?: continue
            if (p.d1 < path.from || p.d0 > path.to) continue
            val (tA, tB) = patchSpan(p)
            val base = if (p.fresh) 0.66 else 0.90
            val col = { d: Double -> val c = (0.84 + 0.16 * RouteGeometry.tone(d, 0.0, 0x70e)) * base; RouteGeometry.linColor(c, c, min(1.0, c * (if (p.fresh) 1.02 else 0.98))) }
            tmp.clear()
            // Andere Stelle der Belagstextur (versetzt), damit die Körnung nicht mit dem Untergrund fluchtet.
            strip(tmp, path, p.d0, p.d1, tA, tB, PATCH_LIFT_M, col, vScale = spec.lengthM, vOff = 0.43, uOfT = { t -> 0.5 + t / spec.widthM + 0.31 })
            clipRoad(tmp, g.mesh(surfaceMat(MAT_PATCH, SurfaceKind.ASPHALT)), p.d0, null)
            // Fugenverguss: dünne dunkle Streifen an den Längsseiten und Stirnen.
            tmp.clear()
            val seal = { _: Double -> RouteGeometry.linColor(0.035, 0.035, 0.04) }
            strip(tmp, path, p.d0, p.d1, tA, { s -> tA(s) + SEAL_M }, SEAL_LIFT_M, seal)
            strip(tmp, path, p.d0, p.d1, { s -> tB(s) - SEAL_M }, tB, SEAL_LIFT_M, seal)
            strip(tmp, path, p.d0, p.d0 + SEAL_M, tA, tB, SEAL_LIFT_M, seal)
            strip(tmp, path, p.d1 - SEAL_M, p.d1, tA, tB, SEAL_LIFT_M, seal)
            clipRoad(tmp, g.mesh(plainMat), p.d0, null)
        }
    }

    /** Liegt bei Laufmeter [d0]..[d1] eine Flickstelle (Deckel nicht auf eine Fuge legen)? */
    private fun patchNear(d0: Double, d1: Double): Boolean {
        for (k in floor(d0 / PATCH_M).toInt() - 1..floor(d1 / PATCH_M).toInt() + 1) {
            val p = patchAt(k) ?: continue
            if (p.d1 >= d0 && p.d0 <= d1) return true
        }
        return false
    }

    // --- 7 Risse ---------------------------------------------------------------------

    private fun cracks(path: Path, g: Group) {
        val tmp = MeshBuilder(64, 64)
        var k = floor(path.from / CRACK_M).toInt() - 1
        while (k * CRACK_M < path.to) {
            val kk = k++
            if (Noise.hash01(kk, 0, 0x4c21) >= 0.35) continue
            val mid = kk * CRACK_M + CRACK_M / 2
            if (mid < 0 || mid > track.lengthM) continue
            val sec = track.surfaceAt(mid)
            if (sec.kind.visual != SurfaceKind.ASPHALT || plan.urbanAt(mid)) continue
            val len = 6.0 + 19.0 * Noise.hash01(kk, 1, 0x4c22)
            val d0 = kk * CRACK_M + Noise.hash01(kk, 2, 0x4c23) * (CRACK_M - len)
            val d1 = d0 + len
            if (d0 < sec.fromM + 8 || d1 > sec.toM - 8 || d1 < path.from || d0 > path.to) continue
            val atJoint = Noise.hash01(kk, 3, 0x4c24) < 0.55
            val hasCentre = plan.centreAt(mid) != MarkingPlan.Centre.NONE
            // Laengsnaht in der Mitte (neben der Mittellinie) oder Randriss 0,75 m innerhalb des Fahrbahnrands.
            val o = if (hasCentre) 0.22 else 0.0
            val sg = if (Noise.hash01(kk, 4, 0x4c25) < 0.6) 1.0 else -1.0
            val base: (St) -> Double = if (atJoint) ({ _ -> o }) else ({ s -> sg * (s.hw - 0.75) })
            val w = if (Noise.hash01(kk, 5, 0x4c26) < 0.5) 0.06 else 0.03
            val col = { d: Double -> val c = 0.05 + 0.03 * RouteGeometry.tone(d, 1.0, 0x4c27); RouteGeometry.linColor(c, c, c * 1.05) }
            // Leicht geschlaengelt: seitlicher Versatz je 1,5 m aus dem Laufmeter.
            val wig = { s: St -> (Noise.hash01(floor(s.dist / 1.5).toInt(), kk, 0x4c28) - 0.5) * 0.08 }
            tmp.clear()
            strip(tmp, path, d0, d1, { s -> base(s) + wig(s) - w / 2 }, { s -> base(s) + wig(s) + w / 2 }, SEAL_LIFT_M, col)
            clipRoad(tmp, g.mesh(plainMat), d0, null)
        }
    }

    // --- Knoten ------------------------------------------------------------------------

    /** Eine Warte- bzw. Haltelinie: Laufmeter, Fahrtrichtung (rechte Hälfte = eigene Zufahrt). */
    private class ControlLine(val dist: Double, val right: Boolean, val solid: Boolean)

    private class Junctions(
        /** Je Seite (0 links, 1 rechts): Laufmeter-Bereiche der Einmündungen. */
        val mouths: Array<List<DoubleArray>>,
        val lines: List<ControlLine>,
        /** Bänder höherrangiger Straßen, die die Strecke kreuzen: Linien der Strecke enden dort. */
        val stop: PlanMask,
    )

    private val junctions: Junctions by lazy { findJunctions() }

    private fun findJunctions(): Junctions {
        val mouths = arrayOf(ArrayList<DoubleArray>(), ArrayList<DoubleArray>())
        val lines = ArrayList<ControlLine>()
        val stop = PlanMask()
        val net = env.roads
        class Hit(val road: RoadNet.Road, val seg: Int, val dJ: Double, val side: Int, val sinT: Double, val cosT: Double)
        val hits = ArrayList<Hit>()
        for (r in net.roads) {
            if (r.spec.rail) continue
            if (r.spec.rank < 1 && r.source.subclass != "cycleway") continue
            for (i in 0 until r.size - 1) {
                if (r.segOnTrack(i)) continue
                val ha = track.nearest(r.e[i], r.n[i], 40.0) ?: continue
                val hb = track.nearest(r.e[i + 1], r.n[i + 1], 40.0) ?: continue
                val wa = track.halfWidthAt(ha.distM)
                val wb = track.halfWidthAt(hb.distM)
                val inA = ha.distance < wa || r.onTrack[i]
                val inB = hb.distance < wb || r.onTrack[i + 1]
                if (inA == inB) continue
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
                val jnn = r.n[ii] + (r.n[oo] - r.n[ii]) * f
                val hit = track.nearest(je, jnn, 40.0) ?: continue
                val pose = track.poseAt(hit.distM)
                val hd = Math.toRadians(pose.headingDeg)
                val te = sin(hd)
                val tn = cos(hd)
                val cosT = dx * te + dy * tn
                val sinT = abs(dx * tn - dy * te)
                if (abs(cosT) > 0.96) continue
                val side = if (hit.lateral >= 0) 1 else 0
                hits.add(Hit(r, i, hit.distM, side, sinT, cosT))
            }
        }
        // Warte-/Haltelinien: echte Daten zuerst, sonst nach Rang.
        val sp = ctx.spec
        val controls = real.controls(sp.minE - 40, sp.minN - 40, sp.maxE + 40, sp.maxN + 40)
        val handled = ArrayList<Double>()
        for (c in controls) {
            val hit = track.nearest(c.e, c.n, 15.0) ?: continue
            if (hit.distance > track.halfWidthAt(hit.distM) + 3.0) continue
            // Fuer die Zufahrt, die auf den naechsten Knoten zulaeuft.
            val ahead = hits.minByOrNull { abs(it.dJ - hit.distM) }
            val right = ahead == null || ahead.dJ >= hit.distM
            lines.add(ControlLine(hit.distM, right, c.kind != RoadControlKind.GIVE_WAY))
            handled.add(hit.distM)
        }
        val minor = HashSet<Hit>()
        val byRoad = hits.groupBy { it.road }
        for ((road, hs) in byRoad) {
            val rank = road.spec.rank
            if (rank < 4) continue
            for (h in hs) {
                val hw = track.halfWidthAt(h.dJ)
                val s = max(0.4, h.sinT)
                // Wo die Mittellinie der Strasse die Mitte der Strecke trifft, und die Kante der Strasse davor/dahinter.
                val dC = h.dJ - h.cosT * hw / s
                val edge = road.halfWidth / s
                val before = track.surfaceAt(dC - edge - 15.0)
                val after = track.surfaceAt(dC + edge + 15.0)
                val rb = MarkingPlan.rankOf(before)
                val ra = MarkingPlan.rankOf(after)
                if (min(rb, ra) < rank && min(rb, ra) >= 2) {
                    minor.add(h)
                    // Baender der kreuzenden Strasse um den Knoten: Linien der Strecke enden dort.
                    for (i in max(0, h.seg - 4) until min(road.size - 1, h.seg + 5)) {
                        val oa = road.halfWidth * min(road.miter[i], 2.0)
                        val ob = road.halfWidth * min(road.miter[i + 1], 2.0)
                        stop.addQuad(
                            road.e[i] - road.re[i] * oa, road.n[i] - road.rn[i] * oa, road.e[i] + road.re[i] * oa, road.n[i] + road.rn[i] * oa,
                            road.e[i + 1] - road.re[i + 1] * ob, road.n[i + 1] - road.rn[i + 1] * ob, road.e[i + 1] + road.re[i + 1] * ob, road.n[i + 1] + road.rn[i + 1] * ob,
                        )
                    }
                }
                fun wanted(d: Double, sec: SurfaceSection, r: Int): Boolean =
                    r < rank && r >= 2 && MarkingPlan.paved(sec.kind) && RouteLayer.isMotorRoad(sec) &&
                        handled.none { abs(it - d) < 25.0 } && lines.none { abs(it.dist - d) < 4.0 }
                val dIn = dC - edge - 0.6
                if (wanted(dIn, before, rb)) lines.add(ControlLine(dIn, right = true, solid = false))
                val dOut = dC + edge + 0.6
                if (wanted(dOut, after, ra)) lines.add(ControlLine(dOut, right = false, solid = false))
            }
        }
        // Einmuendungen (Randlinie dort unterbrochen) — nicht, wo die Strecke selbst auf eine hoeherrangige Strasse trifft.
        for (h in hits) {
            if (h in minor) continue
            val half = min(14.0, h.road.halfWidth / max(0.4, h.sinT) + 3.0)
            mouths[h.side].add(doubleArrayOf(h.dJ - half, h.dJ + half))
        }
        return Junctions(arrayOf(merge(mouths[0]), merge(mouths[1])), lines, stop)
    }

    private fun merge(list: List<DoubleArray>): List<DoubleArray> {
        if (list.isEmpty()) return list
        val s = list.sortedBy { it[0] }
        val out = ArrayList<DoubleArray>()
        var cur = s[0].copyOf()
        for (k in 1 until s.size) {
            if (s[k][0] <= cur[1]) cur[1] = max(cur[1], s[k][1]) else { out.add(cur); cur = s[k].copyOf() }
        }
        out.add(cur)
        return out
    }

    private fun subtract(a: Double, b: Double, cut: List<DoubleArray>): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var x = a
        for (c in cut) {
            if (c[1] <= x || c[0] >= b) continue
            if (c[0] > x) out.add(doubleArrayOf(x, c[0]))
            x = max(x, c[1])
        }
        if (b > x) out.add(doubleArrayOf(x, b))
        return out.filter { it[1] - it[0] >= 0.3 }
    }

    companion object {
        const val MAT_MARKING = "x_mat_route_marking"
        const val MAT_CRUMBLE = "x_mat_route_crumble"
        const val MAT_DECAL = "x_mat_route_decal"
        const val MAT_PATCH = "x_mat_route_patch"
        const val MAT_GUTTER = "x_mat_route_gutter"

        /** Hoechstens dieser Anteil des Fahrbahnbudgets wird fuer die Abziehbilder freigehalten. */
        const val RESERVE_SHARE = 0.3

        /** Linien: wie die bisherigen Markierungen 1,6 cm ueber der Fahrbahn. */
        const val LINE_LIFT_M = RouteLayer.OVERLAY_LIFT_M + 0.004
        const val LINE_HALF_M = 0.06

        /** Randlinie: Innenkante [EDGE_IN_M] + 12 cm vom Fahrbahnrand, also 25 cm Abstand. */
        const val EDGE_IN_M = 0.25

        /** Unterbrochene Randlinie an Einmuendungen (Annahme nach Augenschein): Strich = Luecke. */
        const val MOUTH_DASH_M = 1.5

        /** Warte-/Haltelinie: Breite laengs der Strecke. */
        const val CONTROL_W_M = 0.5

        const val CRUMBLE_IN_M = 0.10
        const val CRUMBLE_OUT_M = 0.24
        const val CRUMBLE_LIFT_M = 0.016
        const val CRUMBLE_V_M = 2.0

        const val PATCH_M = 55.0
        const val PATCH_LIFT_M = 0.005
        const val SEAL_M = 0.04
        const val SEAL_LIFT_M = 0.010
        const val CRACK_M = 80.0

        const val GUTTER_W_M = 0.32
        const val GULLY_M = 30.0
        const val GULLY_LIFT_M = 0.010
        const val MANHOLE_M = 42.0
        const val MANHOLE_R_M = 0.31
        const val MANHOLE_LIFT_M = 0.010
    }
}
