package de.trailscape.core.indoor.generated

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.LandClass
import de.trailscape.core.indoor.world.MapArea
import de.trailscape.core.indoor.world.MapBuilding
import de.trailscape.core.indoor.world.MapData
import de.trailscape.core.indoor.world.MapRoad
import de.trailscape.core.indoor.world.MapWaterway
import de.trailscape.core.indoor.world.Noise
import de.trailscape.core.indoor.world.RectIndex
import de.trailscape.core.indoor.world.boundsOf
import de.trailscape.core.indoor.world.distanceToLine
import de.trailscape.core.indoor.world.pointInRings
import de.trailscape.core.indoor.world.ringArea
import de.trailscape.core.indoor.world.smoothstep
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Kartendaten der erfundenen Landschaft
 *
 * Liefert genau die Strukturen, die der Weltbau sonst aus den Vektorkacheln
 * liest ([MapData]: Strassen, Gebaeude, Flaechen, Wasserlaeufe) — Baeume,
 * Strassen, Haeuser, Bodentextur und Budgets kommen dadurch unveraendert aus
 * dem bestehenden Weltbau.
 *
 *  * **Landbedeckung** aus glatten Feldern (Wald, Acker; Wiese ist der
 *    Rueckfall), je Feld die Nullinie als Ring (Marching Squares) in festen
 *    Bloecken (256 m mit 8 m Raster bis gut 2 km neben der Strecke, sonst
 *    1 024 m mit 32 m), beim ersten Bedarf gerechnet.
 *  * **Orte** an flachen Stellen der Strecke: Haeuser beidseits, Querstrassen
 *    mit Haeusern, Wohngebiet; manchmal ein Stueck Kopfsteinpflaster.
 *  * **Feldwege** und **Querstrassen**, ab und zu ein Hof am Wegende.
 *  * **Seen** aus [GenTerrain.lakes].
 */
internal class GenMap(private val plan: GenRoutePlan, private val terrain: GenTerrain) {
    private val landscape = plan.landscape
    private val sF = genSeed(plan.profile.seed, 40)
    private val sA = genSeed(plan.profile.seed, 41)
    private val sC = genSeed(plan.profile.seed, 42)
    private val sM = genSeed(plan.profile.seed, 43)
    private val sV = genSeed(plan.profile.seed, 44)
    private val sR = genSeed(plan.profile.seed, 45)

    val roads = ArrayList<MapRoad>()
    val buildings = ArrayList<MapBuilding>()
    val staticAreas = ArrayList<MapArea>()
    val waterways = ArrayList<MapWaterway>()

    /** Belag der Strecke (deckt 0 .. Laenge). */
    val surfaces: List<SurfaceSection>

    /** Linien der Nebenstrassen (fuer Abstandspruefungen). */
    private val sideLines = ArrayList<DoubleArray>()
    private var buildingId = 1L

    // -----------------------------------------------------------------------
    // Felder der Landbedeckung
    // -----------------------------------------------------------------------

    private fun villageNear(e: Double, n: Double): Double {
        var best = Double.MAX_VALUE
        for (v in plan.villages) {
            val i0 = plan.indexAt(v[0])
            val i1 = plan.indexAt(v[1])
            val im = (i0 + i1) / 2
            if (hypot(plan.e[im] - e, plan.n[im] - n) > (v[1] - v[0]) / 2 + 700) continue
            var i = i0
            while (i <= i1) {
                best = min(best, hypot(plan.e[i] - e, plan.n[i] - n))
                i += 4
            }
        }
        return best
    }

    private fun lakeNear(e: Double, n: Double): Double {
        var best = Double.MAX_VALUE
        for (l in terrain.lakes) {
            val dc = hypot(e - l.ce, n - l.cn)
            if (dc > l.radius * 1.6 + 60) continue
            best = min(best, if (pointInRings(listOf(l.ring), e, n)) 0.0 else GenTerrain.ringDistance(l.ring, e, n))
        }
        return best
    }

    /** Wald (> 0). */
    fun forestField(e: Double, n: Double): Double {
        val slope = terrain.slopeAt(e, n)
        val d = terrain.distanceToRoute(e, n)
        val v = villageNear(e, n)
        val base = when (landscape) {
            GenLandscape.MITTELGEBIRGE -> {
                // Talwiesen entlang der Strecke, wo das Maskenrauschen es will
                val mask = Noise.fbm(e / 1_600.0, n / 1_600.0, sM, 2)
                val meadow = (1 - smoothstep(25.0, 170.0, d)) * smoothstep(-0.05, 0.25, mask) * 0.8
                0.12 + 0.55 * Noise.fbm(e / 850.0, n / 850.0, sF, 3) + 2.4 * (slope - 0.13) - meadow
            }
            GenLandscape.HUEGELLAND ->
                -0.14 + 0.7 * Noise.fbm(e / 780.0, n / 780.0, sF, 3) + 2.0 * (slope - 0.10)
        }
        val village = 1.2 * (1 - smoothstep(120.0, 380.0, v))
        val lake = 0.8 * (1 - smoothstep(5.0, 45.0, lakeNear(e, n)))
        return base - village - lake
    }

    /** Acker (> 0), unter Wald. */
    fun farmField(e: Double, n: Double): Double {
        val slope = terrain.slopeAt(e, n)
        val v = villageNear(e, n)
        val base = when (landscape) {
            GenLandscape.MITTELGEBIRGE -> -0.1 + 0.5 * Noise.fbm(e / 1_100.0, n / 1_100.0, sA, 3) - 3.5 * (slope - 0.07)
            GenLandscape.HUEGELLAND -> 0.3 + 0.5 * Noise.fbm(e / 1_300.0, n / 1_300.0, sA, 3) - 3.0 * (slope - 0.12)
        }
        val nearVillage = 0.25 * (1 - smoothstep(200.0, 900.0, v)) - 1.0 * (1 - smoothstep(60.0, 120.0, v))
        val lake = 1.0 * (1 - smoothstep(10.0, 60.0, lakeNear(e, n)))
        return base + nearVillage - lake
    }

    /** Nadelholz-Anteil (> 0,25 Nadel, < −0,25 Laub, dazwischen Misch). */
    fun coniferField(e: Double, n: Double): Double = when (landscape) {
        GenLandscape.MITTELGEBIRGE -> 0.15 + 0.7 * Noise.fbm(e / 1_500.0, n / 1_500.0, sC, 3) + (terrain.heightAt(e, n) - 480.0) / 350.0
        GenLandscape.HUEGELLAND -> -0.25 + 0.7 * Noise.fbm(e / 1_500.0, n / 1_500.0, sC, 3)
    }

    // -----------------------------------------------------------------------
    // Belag der Strecke
    // -----------------------------------------------------------------------

    private fun chooseSurfaces(): List<SurfaceSection> {
        val len = plan.lengthM
        // Grundbelag: Landstrasse
        class Span(val a: Double, val b: Double, val kind: SurfaceKind, val highway: String, val width: Double)
        val spans = ArrayList<Span>()
        // Kopfsteinpflaster in manchen Ortskernen
        for ((k, v) in plan.villages.withIndex()) {
            if (Noise.hash01(sV, k, 30) > 0.45) continue
            val mid = (v[0] + v[1]) / 2
            val half = 45.0 + 35.0 * Noise.hash01(sV, k, 31)
            spans += Span(mid - half, mid + half, SurfaceKind.COBBLESTONE, "living_street", 5.0)
        }
        // Waldweg (wassergebunden) in einem ruhigen Abschnitt durch Wald — nur Mittelgebirge, hoechstens einer
        if (landscape == GenLandscape.MITTELGEBIRGE && Noise.hash01(sR, 0, 1) < 0.55) {
            var s = 600.0
            var done = false
            while (s < len - 1_200 && !done) {
                val leg = plan.legAt(s)
                val want = 600.0 + 500.0 * Noise.hash01(sR, s.toInt(), 2)
                val ok = leg.kind == GeneratedProfile.Kind.FLAT && leg.toM - s >= want &&
                    !plan.inVillage(s, 300.0) && !plan.inVillage(s + want, 300.0) &&
                    plan.serpentines.none { it[0] < s + want && it[1] > s }
                if (ok) {
                    var forest = 0
                    var total = 0
                    var x = s
                    while (x <= s + want) {
                        val i = plan.indexAt(x)
                        if (forestField(plan.e[i], plan.n[i]) > 0) forest++
                        total++
                        x += 50.0
                    }
                    if (forest >= total * 0.7) {
                        spans += Span(s, s + want, SurfaceKind.COMPACTED, "track", 3.2)
                        done = true
                    }
                }
                s += 250.0
            }
        }
        spans.sortBy { it.a }
        val out = ArrayList<SurfaceSection>()
        var at = 0.0
        val main = { a: Double, b: Double -> SurfaceSection(a, b, SurfaceKind.ASPHALT, "tertiary", 6.0) }
        for (sp in spans) {
            val a = sp.a.coerceIn(at, len)
            val b = sp.b.coerceIn(a, len)
            if (b - a < 20) continue
            if (a > at) out += main(at, a)
            out += SurfaceSection(a, b, sp.kind, sp.highway, sp.width)
            at = b
        }
        if (at < len) out += main(at, len)
        return out
    }

    private fun routeLine(a: Double, b: Double): DoubleArray {
        val i0 = plan.indexAt(a)
        val i1 = min(plan.count - 1, plan.indexAt(b) + 1)
        val out = DoubleArray((i1 - i0 + 1) * 2)
        for (i in i0..i1) {
            out[2 * (i - i0)] = plan.e[i]
            out[2 * (i - i0) + 1] = plan.n[i]
        }
        return out
    }

    private fun addRouteRoads() {
        for (s in surfaces) {
            val cls = when (s.highway) {
                "track" -> "track"
                "living_street" -> "minor"
                else -> "tertiary"
            }
            val surface = if (s.kind.coarse == de.trailscape.core.SurfaceClass.UNPAVED) "unpaved" else "paved"
            roads += MapRoad(routeLine(s.fromM, s.toM), cls, if (cls == "minor") "living_street" else null, surface)
        }
    }

    // -----------------------------------------------------------------------
    // Orte
    // -----------------------------------------------------------------------

    private class Street(val line: DoubleArray, val halfWidth: Double)

    private val placed = ArrayList<DoubleArray>() // e, n, radius

    private fun addVillage(k: Int, v: DoubleArray) {
        val streets = ArrayList<Street>()
        val through = routeLine(v[0], v[1])
        streets += Street(through, 3.0 + 2.2) // Fahrbahn + Gehweg
        // Querstrassen
        val nSide = 1 + (Noise.hash01(sV, k, 1) * 2.99).toInt()
        val sideFor = ArrayList<DoubleArray>()
        for (q in 0 until nSide) {
            val s = v[0] + (v[1] - v[0]) * (0.2 + 0.6 * Noise.hash01(sV, k, 10 + q))
            val side = if (Noise.hash01(sV, k, 20 + q) < 0.5) 1.0 else -1.0
            val len = 130.0 + 220.0 * Noise.hash01(sV, k, 25 + q)
            val line = sideRoad(s, side, len, 1.45, Noise.seedOf(sV.toLong(), (k * 10 + q).toLong()), 32.0) ?: continue
            roads += MapRoad(line, "minor", "residential", "paved")
            sideLines += line
            sideFor += line
            streets += Street(line, 2.75 + 1.5)
        }
        // Wohngebiet
        staticAreas += MapArea(listOf(ccw(band(through, 75.0, 25.0, sV + k))), LandClass.RESIDENTIAL, "residential")
        for (l in sideFor) staticAreas += MapArea(listOf(ccw(band(l, 55.0, 15.0, sV + k + 7))), LandClass.RESIDENTIAL, "residential")
        // Haeuser
        val budget = 18 + ((v[1] - v[0]) / 12).toInt()
        var made = 0
        for ((si, st) in streets.withIndex()) {
            val total = lineLength(st.line)
            var s = if (si == 0) 15.0 else 22.0
            var q = 0
            while (s < total - 12 && made < budget) {
                for (side in intArrayOf(1, -1)) {
                    val h = Noise.hash01(k * 100 + si, q * 2 + (side + 1) / 2, sV + 3)
                    if (h > 0.82) continue
                    if (tryHouse(st, s, side, k * 100 + si, q, streets)) made++
                }
                s += 19.0 + 8.0 * Noise.hash01(k * 100 + si, q, sV + 4)
                q++
            }
        }
    }

    /** Ein Haus (ggf. mit Nebengebaeude) an Strasse [st] bei Laenge [s] auf Seite [side]. */
    private fun tryHouse(st: Street, s: Double, side: Int, a: Int, q: Int, streets: List<Street>): Boolean {
        val (p, dir) = pointOnLine(st.line, s)
        val ae = dir[0]
        val an = dir[1]
        val be = -an * side
        val bn = ae * side
        fun r(salt: Int) = Noise.hash01(a, q * 2 + (side + 1) / 2, sV + salt)
        val setback = st.halfWidth + 2.5 + 4.0 * r(8)
        val roll = r(9)
        val big = roll < 0.16
        val front = if (big) 16.0 + 4 * r(10) else 8.5 + 4.0 * r(10)
        val depth = if (big) 10.0 + 3 * r(11) else 9.0 + 4.0 * r(11)
        fun corner(x: Double, y: Double) = doubleArrayOf(p[0] + ae * x + be * (setback + y), p[1] + an * x + bn * (setback + y))
        val ring: DoubleArray = if (roll in 0.16..0.42) {
            val wing = 5.0 + 1.5 * r(12)
            listOf(
                corner(-front / 2, 0.0), corner(front / 2, 0.0), corner(front / 2, depth + 7.0),
                corner(front / 2 - wing, depth + 7.0), corner(front / 2 - wing, depth), corner(-front / 2, depth),
            ).flatMap { it.toList() }.toDoubleArray()
        } else {
            listOf(corner(-front / 2, 0.0), corner(front / 2, 0.0), corner(front / 2, depth), corner(-front / 2, depth))
                .flatMap { it.toList() }.toDoubleArray()
        }
        if (!free(ccw(ring), streets)) return false
        val height = if (big) 8.5 + 2.0 * r(13) else 6.4 + 2.6 * r(13)
        buildings += MapBuilding(buildingId++, listOf(ccw(ring)), Math.round(height * 10) / 10.0)
        if (r(14) < 0.4) {
            val w = 4.0 + 3.0 * r(15)
            val d = 5.0 + 4.0 * r(16)
            val x0 = (r(17) - 0.5) * front
            val y0 = depth + (if (roll in 0.16..0.42) 9.5 else 3.0)
            val shed = listOf(corner(x0 - w / 2, y0), corner(x0 + w / 2, y0), corner(x0 + w / 2, y0 + d), corner(x0 - w / 2, y0 + d))
                .flatMap { it.toList() }.toDoubleArray()
            if (free(ccw(shed), streets)) buildings += MapBuilding(buildingId++, listOf(ccw(shed)), 3.6)
        }
        return true
    }

    private fun free(ring: DoubleArray, streets: List<Street>): Boolean {
        var ce = 0.0
        var cn = 0.0
        val m = ring.size / 2
        for (k in 0 until m) {
            ce += ring[2 * k]
            cn += ring[2 * k + 1]
        }
        ce /= m
        cn /= m
        var r = 0.0
        for (k in 0 until m) r = max(r, hypot(ring[2 * k] - ce, ring[2 * k + 1] - cn))
        if (placed.any { hypot(it[0] - ce, it[1] - cn) < it[2] + r + 2.5 }) return false
        for (k in 0 until m) {
            val e = ring[2 * k]
            val n = ring[2 * k + 1]
            if (streets.any { distanceToLine(it.line, e, n) < it.halfWidth + 2.0 }) return false
            if (terrain.distanceToRoute(e, n) < 7.5) return false
            if (sideLines.any { distanceToLine(it, e, n) < 5.0 }) return false
            if (lakeNear(e, n) < 15) return false
        }
        // nicht an einen Steilhang
        if (terrain.slopeAt(ce, cn) > 0.28) return false
        placed += doubleArrayOf(ce, cn, r)
        return true
    }

    // -----------------------------------------------------------------------
    // Feldwege, Querstrassen, Hoefe
    // -----------------------------------------------------------------------

    /**
     * Ein Weg, der bei Laufmeter [s] von der Strecke abzweigt, nach Seite
     * [side] (links = +1), [len] Meter lang, mit Kurven bis [bend] rad.
     * `null`, wenn er der Strecke, einem See oder einer anderen Nebenstrasse zu nahe kaeme.
     */
    private fun sideRoad(s: Double, side: Double, len: Double, angle: Double, seed: Int, startGap: Double): DoubleArray? {
        val i = plan.indexAt(s)
        val j = min(plan.count - 1, i + 2)
        val i0 = max(0, i - 2)
        val heading = atan2(plan.n[j] - plan.n[i0], plan.e[j] - plan.e[i0])
        var th = heading + side * (angle + 0.35 * (Noise.hash01(seed, 1, 1) - 0.5))
        var x = plan.e[i]
        var y = plan.n[i]
        val out = ArrayList<Double>()
        out += x
        out += y
        var t = 0.0
        val step = 10.0
        while (t < len) {
            th += step * 0.0035 * noise1(t / 220.0, seed + 3)
            x += cos(th) * step
            y += sin(th) * step
            t += step
            out += x
            out += y
            if (t > startGap && terrain.distanceToRoute(x, y) < 24.0) return null
            if (lakeNear(x, y) < 12.0) return null
            if (t > startGap && sideLines.any { distanceToLine(it, x, y) < 25.0 }) return null
            if (terrain.slopeAt(x, y) > 0.32) {
                // Weg endet am Steilhang
                return if (t >= 80) out.toDoubleArray() else null
            }
        }
        return out.toDoubleArray()
    }

    private fun addTracksAndCrossings() {
        var s = 300.0 + 500.0 * Noise.hash01(sR, 1, 3)
        var k = 0
        var sinceCross = 0.0
        while (s < plan.lengthM - 150) {
            val inSerp = plan.serpentines.any { s > it[0] - 60 && s < it[1] + 60 }
            if (!plan.inVillage(s, 60.0) && !inSerp) {
                val roll = Noise.hash01(sR, k, 4)
                val side = if (Noise.hash01(sR, k, 5) < 0.5) 1.0 else -1.0
                if (sinceCross > 3_500 && roll < 0.35) {
                    // Querstrasse: beidseits
                    val l1 = 350.0 + 500.0 * Noise.hash01(sR, k, 6)
                    val l2 = 350.0 + 500.0 * Noise.hash01(sR, k, 7)
                    val a = sideRoad(s, side, l1, PI / 2, Noise.seedOf(sR.toLong(), k * 2L), 45.0)
                    val b = if (a != null) sideRoad(s, -side, l2, PI / 2, Noise.seedOf(sR.toLong(), k * 2L + 1), 45.0) else null
                    if (a != null && b != null) {
                        val line = reverse(b) + a.copyOfRange(2, a.size)
                        val cls = if (Noise.hash01(sR, k, 8) < 0.5) "minor" else "tertiary"
                        roads += MapRoad(line, cls, null, "paved")
                        sideLines += a
                        sideLines += b
                        sinceCross = 0.0
                    }
                } else {
                    val len = 220.0 + 520.0 * Noise.hash01(sR, k, 9)
                    val line = sideRoad(s, side, len, 1.0 + 0.8 * Noise.hash01(sR, k, 10), Noise.seedOf(sR.toLong(), 1000L + k), 50.0)
                    if (line != null) {
                        val end = line.size - 2
                        val inForest = forestField(line[end], line[end + 1]) > 0
                        roads += MapRoad(line, "track", null, "unpaved")
                        sideLines += line
                        if (!inForest && Noise.hash01(sR, k, 11) < 0.35) addFarm(line, k)
                    }
                }
            }
            val gap = if (landscape == GenLandscape.HUEGELLAND) 650.0 else 900.0
            val adv = gap * (0.6 + 0.9 * Noise.hash01(sR, k, 12))
            s += adv
            sinceCross += adv
            k++
        }
    }

    /** Hof am Ende eines Feldwegs: Wohnhaus und ein bis zwei Hallen. */
    private fun addFarm(line: DoubleArray, k: Int) {
        val m = line.size / 2
        val ex = line[2 * (m - 1)]
        val ey = line[2 * (m - 1) + 1]
        val px = line[2 * (m - 2)]
        val py = line[2 * (m - 2) + 1]
        val th = atan2(ey - py, ex - px)
        val ae = cos(th)
        val an = sin(th)
        val street = listOf(Street(line, 2.0))
        fun rect(cx: Double, cy: Double, w: Double, d: Double, rot: Double): DoubleArray {
            val ca = cos(th + rot)
            val sa = sin(th + rot)
            val pts = listOf(-w / 2 to -d / 2, w / 2 to -d / 2, w / 2 to d / 2, -w / 2 to d / 2)
            return pts.flatMap { (x, y) -> listOf(cx + x * ca - y * sa, cy + x * sa + y * ca) }.toDoubleArray()
        }
        fun r(salt: Int) = Noise.hash01(k, salt, sR + 77)
        // Wohnhaus seitlich am Wegende, Hallen dahinter
        val hx = ex - an * 14 + ae * 4
        val hy = ey + ae * 14 + an * 4
        val house = ccw(rect(hx, hy, 11.0 + 3 * r(1), 9.0 + 2 * r(2), 0.0))
        if (free(house, street)) buildings += MapBuilding(buildingId++, listOf(house), 7.5)
        val bx = ex + an * 18 + ae * 10
        val by = ey - ae * 18 + an * 10
        val barn = ccw(rect(bx, by, 28.0 + 12 * r(3), 15.0 + 6 * r(4), PI / 2 * (if (r(5) < 0.5) 0.0 else 1.0)))
        if (free(barn, street)) buildings += MapBuilding(buildingId++, listOf(barn), 8.0 + 2 * r(6))
        if (r(7) < 0.5) {
            val cx = ex + ae * 32
            val cy = ey + an * 32
            val shed = ccw(rect(cx, cy, 14.0 + 6 * r(8), 9.0 + 3 * r(9), 0.0))
            if (free(shed, street)) buildings += MapBuilding(buildingId++, listOf(shed), 5.0)
        }
        // Hofflaeche
        staticAreas += MapArea(listOf(ccw(rect(ex + ae * 8, ey + an * 8, 90.0, 80.0, 0.0))), LandClass.FARMLAND, "farmyard")
    }

    // -----------------------------------------------------------------------
    // Landbedeckung in Bloecken
    // -----------------------------------------------------------------------

    private val superM = 1_024.0
    private val chunks = ConcurrentHashMap<Long, List<MapArea>>()

    private fun superKey(x: Long, y: Long) = (x shl 32) xor (y and 0xffffffffL)

    private fun landChunk(sx: Long, sy: Long): List<MapArea> = chunks.computeIfAbsent(superKey(sx, sy)) {
        val e0 = sx * superM
        val n0 = sy * superM
        val near = terrain.distanceToRoute(e0 + superM / 2, n0 + superM / 2) < 2_300.0
        val out = ArrayList<MapArea>()
        if (near) {
            for (j in 0 until 4) for (i in 0 until 4) contourAreas(e0 + i * 256.0, n0 + j * 256.0, 256.0, 8.0, out)
        } else {
            contourAreas(e0, n0, superM, 32.0, out)
        }
        out
    }

    /** Ringe der Landbedeckung eines Quadrats. */
    private fun contourAreas(e0: Double, n0: Double, size: Double, step: Double, out: MutableList<MapArea>) {
        val cells = (size / step).toInt()
        val nodes = cells + 1
        val f = DoubleArray(nodes * nodes)
        val a = DoubleArray(nodes * nodes)
        val c = DoubleArray(nodes * nodes)
        for (j in 0 until nodes) for (i in 0 until nodes) {
            val e = e0 + i * step
            val n = n0 + j * step
            f[j * nodes + i] = forestField(e, n)
            a[j * nodes + i] = farmField(e, n)
            c[j * nodes + i] = if (f[j * nodes + i] > -0.3) coniferField(e, n) else 0.0
        }
        fun emit(values: DoubleArray, kind: LandClass, cls: String) {
            val rings = marchingSquares(values, nodes, e0, n0, step)
            if (rings.isNotEmpty()) out += MapArea(rings, kind, cls)
        }
        val needle = DoubleArray(f.size) { min(f[it], c[it] - 0.25) }
        val broad = DoubleArray(f.size) { min(f[it], -c[it] - 0.25) }
        val mixed = DoubleArray(f.size) { min(f[it], 0.25 - abs(c[it])) }
        emit(a, LandClass.FARMLAND, "farmland")
        emit(needle, LandClass.FOREST, "needleleaved")
        emit(broad, LandClass.FOREST, "broadleaved")
        emit(mixed, LandClass.FOREST, "mixed")
    }

    // -----------------------------------------------------------------------
    // MapData
    // -----------------------------------------------------------------------

    /** Die Kartendaten in der Welt mit Ursprung [origin] (Umrechnung ueber Breite/Laenge wie bei echten Daten). */
    fun mapData(origin: GeoOrigin): MapData {
        val d = GEN_DESIGN_ORIGIN
        fun conv(src: DoubleArray) = DoubleArray(src.size) { i ->
            if (i % 2 == 0) origin.toEast(d.toLon(src[i])) else origin.toNorth(d.toLat(src[i]))
        }
        fun convBack(e: Double, n: Double): DoubleArray = doubleArrayOf(d.toEast(origin.toLon(e)), d.toNorth(origin.toLat(n)))
        val bs = buildings.map { MapBuilding(it.id, it.rings.map(::conv), it.heightM, it.minHeightM, it.colour) }
        val rs = roads.map { MapRoad(conv(it.line), it.cls, it.subclass, it.surface, it.brunnel, it.layer, it.service) }
        val stat = staticAreas.map { MapArea(it.rings.map(::conv), it.kind, it.sourceClass) }
        val ws = waterways.map { MapWaterway(conv(it.line), it.cls, it.brunnel) }
        val bi = RectIndex(bs, { boundsOf(it.rings) })
        val ri = RectIndex(rs, { boundsOf(listOf(it.line)) })
        val ai = RectIndex(stat, { boundsOf(it.rings) })
        val wi = RectIndex(ws, { boundsOf(listOf(it.line)) })
        val converted = ConcurrentHashMap<Long, List<MapArea>>()
        return object : MapData {
            override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = bi.query(minE, minN, maxE, maxN)
            override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = ri.query(minE, minN, maxE, maxN)
            override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = wi.query(minE, minN, maxE, maxN)
            override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapArea> {
                val lo = convBack(minE, minN)
                val hi = convBack(maxE, maxN)
                val out = ArrayList<MapArea>(ai.query(minE, minN, maxE, maxN))
                val x0 = floor(lo[0] / superM).toLong()
                val x1 = floor(hi[0] / superM).toLong()
                val y0 = floor(lo[1] / superM).toLong()
                val y1 = floor(hi[1] / superM).toLong()
                for (x in x0..x1) for (y in y0..y1) {
                    val list = converted.computeIfAbsent(superKey(x, y)) {
                        landChunk(x, y).map { MapArea(it.rings.map(::conv), it.kind, it.sourceClass) }
                    }
                    for (a in list) {
                        val b = boundsOf(a.rings)
                        if (b[2] >= minE && b[0] <= maxE && b[3] >= minN && b[1] <= maxN) out += a
                    }
                }
                return out
            }
        }
    }

    // Erst hier: alle Felder oben sind initialisiert.
    init {
        surfaces = chooseSurfaces()
        addRouteRoads()
        for ((k, v) in plan.villages.withIndex()) addVillage(k, v)
        addTracksAndCrossings()
        for (l in terrain.lakes) staticAreas += MapArea(listOf(ccw(l.ring)), LandClass.WATER, "lake")
    }

    companion object {
        /** Ring gegen den Uhrzeigersinn (positive Flaeche). */
        fun ccw(ring: DoubleArray): DoubleArray {
            if (ringArea(ring) >= 0) return ring
            val m = ring.size / 2
            return DoubleArray(ring.size) { i -> ring[2 * (m - 1 - i / 2) + i % 2] }
        }

        fun reverse(line: DoubleArray): DoubleArray {
            val m = line.size / 2
            return DoubleArray(line.size) { i -> line[2 * (m - 1 - i / 2) + i % 2] }
        }

        fun lineLength(line: DoubleArray): Double {
            var l = 0.0
            for (k in 0 until line.size / 2 - 1) l += hypot(line[2 * k + 2] - line[2 * k], line[2 * k + 3] - line[2 * k + 1])
            return l
        }

        fun pointOnLine(line: DoubleArray, s: Double): Pair<DoubleArray, DoubleArray> {
            var rest = s
            for (k in 0 until line.size / 2 - 1) {
                val de = line[2 * k + 2] - line[2 * k]
                val dn = line[2 * k + 3] - line[2 * k + 1]
                val l = hypot(de, dn)
                if (l <= 0) continue
                if (rest <= l || k == line.size / 2 - 2) {
                    val t = (rest / l).coerceIn(0.0, 1.0)
                    return doubleArrayOf(line[2 * k] + de * t, line[2 * k + 1] + dn * t) to doubleArrayOf(de / l, dn / l)
                }
                rest -= l
            }
            return doubleArrayOf(line[0], line[1]) to doubleArrayOf(1.0, 0.0)
        }

        /** Flaeche beidseits einer Linie, Breite [half] mit [wobble] m Rauschen am Rand. */
        fun band(line: DoubleArray, half: Double, wobble: Double, seed: Int): DoubleArray {
            val m = line.size / 2
            val left = ArrayList<Double>()
            val right = ArrayList<Double>()
            var acc = 0.0
            val stride = max(1, m / 60)
            var k = 0
            while (k < m) {
                val a = max(0, k - 1)
                val b = min(m - 1, k + 1)
                val de = line[2 * b] - line[2 * a]
                val dn = line[2 * b + 1] - line[2 * a + 1]
                val l = hypot(de, dn).coerceAtLeast(1e-6)
                if (k > 0) acc += hypot(line[2 * k] - line[2 * (k - stride).coerceAtLeast(0)], line[2 * k + 1] - line[2 * (k - stride).coerceAtLeast(0) + 1])
                val w1 = half + wobble * Noise.fbm(acc / 90.0, 0.5, seed, 2)
                val w2 = half + wobble * Noise.fbm(acc / 90.0, 7.5, seed, 2)
                left += line[2 * k] - dn / l * w1
                left += line[2 * k + 1] + de / l * w1
                right += line[2 * k] + dn / l * w2
                right += line[2 * k + 1] - de / l * w2
                if (k == m - 1) break
                k = min(m - 1, k + stride)
            }
            val out = ArrayList<Double>(left)
            for (q in right.size / 2 - 1 downTo 0) {
                out += right[2 * q]
                out += right[2 * q + 1]
            }
            return out.toDoubleArray()
        }

        /**
         * Nullinien von [v] (Gitter [nodes]², Abstand [step] ab [e0]/[n0]) als
         * geschlossene Ringe; ausserhalb des Gitters gilt „negativ", die Ringe
         * schliessen also eine halbe Zelle jenseits des Rands. Ringe mit
         * weniger als 40 m² entfallen. Orientierung beliebig (gerade/ungerade).
         */
        fun marchingSquares(v: DoubleArray, nodes: Int, e0: Double, n0: Double, step: Double): List<DoubleArray> {
            val pn = nodes + 2
            fun value(i: Int, j: Int): Double {
                if (i < 0 || j < 0 || i >= nodes || j >= nodes) {
                    val ci = i.coerceIn(0, nodes - 1)
                    val cj = j.coerceIn(0, nodes - 1)
                    return -abs(v[cj * nodes + ci]) - 1e-6
                }
                val x = v[j * nodes + i]
                return if (x == 0.0) 1e-9 else x
            }
            // Kanten-Schluessel: horizontale Kante (i,j)-(i+1,j) = 2*(j*pn+i), vertikale (i,j)-(i,j+1) = 2*(j*pn+i)+1, Indizes um 1 verschoben
            fun hKey(i: Int, j: Int) = 2 * ((j + 1) * pn + (i + 1))
            fun vKey(i: Int, j: Int) = 2 * ((j + 1) * pn + (i + 1)) + 1
            val adj = HashMap<Int, IntArray>()
            fun link(a: Int, b: Int) {
                val la = adj[a]
                if (la == null) adj[a] = intArrayOf(b, -1) else la[1] = b
                val lb = adj[b]
                if (lb == null) adj[b] = intArrayOf(a, -1) else lb[1] = a
            }
            val point = HashMap<Int, DoubleArray>()
            fun cross(key: Int, i: Int, j: Int, horizontal: Boolean) {
                if (point.containsKey(key)) return
                val a = value(i, j)
                val b = if (horizontal) value(i + 1, j) else value(i, j + 1)
                val t = (a / (a - b)).coerceIn(0.0, 1.0)
                point[key] = if (horizontal) doubleArrayOf(e0 + (i + t) * step, n0 + j * step) else doubleArrayOf(e0 + i * step, n0 + (j + t) * step)
            }
            for (j in -1 until nodes) for (i in -1 until nodes) {
                val v00 = value(i, j)
                val v10 = value(i + 1, j)
                val v01 = value(i, j + 1)
                val v11 = value(i + 1, j + 1)
                val idx = (if (v00 > 0) 1 else 0) or (if (v10 > 0) 2 else 0) or (if (v11 > 0) 4 else 0) or (if (v01 > 0) 8 else 0)
                if (idx == 0 || idx == 15) continue
                val bottom = hKey(i, j)
                val top = hKey(i, j + 1)
                val leftK = vKey(i, j)
                val rightK = vKey(i + 1, j)
                fun b() { cross(bottom, i, j, true) }
                fun t() { cross(top, i, j + 1, true) }
                fun l() { cross(leftK, i, j, false) }
                fun r() { cross(rightK, i + 1, j, false) }
                when (idx) {
                    1, 14 -> { l(); b(); link(leftK, bottom) }
                    2, 13 -> { b(); r(); link(bottom, rightK) }
                    3, 12 -> { l(); r(); link(leftK, rightK) }
                    4, 11 -> { r(); t(); link(rightK, top) }
                    6, 9 -> { b(); t(); link(bottom, top) }
                    7, 8 -> { l(); t(); link(leftK, top) }
                    5, 10 -> {
                        l(); b(); r(); t()
                        val center = (v00 + v10 + v01 + v11) / 4
                        val joined = (center > 0) == (idx == 5)
                        if (joined) {
                            link(leftK, top); link(bottom, rightK)
                        } else {
                            link(leftK, bottom); link(rightK, top)
                        }
                    }
                }
            }
            val seen = HashSet<Int>()
            val rings = ArrayList<DoubleArray>()
            for (start in adj.keys.sorted()) {
                if (start in seen) continue
                val pts = ArrayList<Double>()
                var prev = -1
                var cur = start
                while (true) {
                    seen += cur
                    val p = point.getValue(cur)
                    pts += p[0]
                    pts += p[1]
                    val nb = adj.getValue(cur)
                    val next = if (nb[0] != prev) nb[0] else nb[1]
                    prev = cur
                    cur = next
                    if (cur == start || cur < 0 || cur in seen) break
                }
                val ring = simplify(pts.toDoubleArray(), step * 0.12)
                if (ring.size >= 6 && abs(ringArea(ring)) >= 40.0) rings += ring
            }
            return rings
        }

        /** Douglas-Peucker fuer geschlossene Ringe (Start bleibt). */
        fun simplify(ring: DoubleArray, tol: Double): DoubleArray {
            val m = ring.size / 2
            if (m < 8) return ring
            val keep = BooleanArray(m)
            keep[0] = true
            keep[m / 2] = true
            fun rec(a: Int, b: Int) {
                if (b <= a + 1) return
                val ax = ring[2 * a]
                val ay = ring[2 * a + 1]
                val bx = ring[2 * (b % m)]
                val by = ring[2 * (b % m) + 1]
                val dx = bx - ax
                val dy = by - ay
                val l = hypot(dx, dy)
                var worst = -1.0
                var wi = -1
                for (k in a + 1 until b) {
                    val px = ring[2 * k] - ax
                    val py = ring[2 * k + 1] - ay
                    val d = if (l > 1e-9) abs(px * dy - py * dx) / l else hypot(px, py)
                    if (d > worst) {
                        worst = d
                        wi = k
                    }
                }
                if (worst > tol) {
                    keep[wi] = true
                    rec(a, wi)
                    rec(wi, b)
                }
            }
            rec(0, m / 2)
            rec(m / 2, m)
            val out = ArrayList<Double>()
            for (k in 0 until m) if (keep[k]) {
                out += ring[2 * k]
                out += ring[2 * k + 1]
            }
            return out.toDoubleArray()
        }
    }
}
