package de.trailscape.core.indoor.world

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Rebzeilen im Weinberg (Detailtreue)
 *
 * Weinberge (`landcover farmland/vineyard` der Vektorkacheln) waren „Gebuesch“ ([LandClass.SCRUB]): auf
 * dem Luftbild als Boden die Zeilen, darueber hoechstens gewuerfelte Straeucher. Auf den Stuttgarter
 * Strecken (Lemberg, Bottwar-, Enztal) praegen Weinberge das Bild (`docs/indoor/detailtreue.md`, Mangel 6).
 *
 * Jetzt stehen in Stufe 0 bis [NEAR_M] neben der Strecke **Rebzeilen**: ein schmales Laubband
 * (Λ-Querschnitt, 0,25–1,7 m ueber dem Boden, 0,6 m breit), Zeilenabstand [ROW_M].
 * Die **Zeilenrichtung kommt aus dem Luftbild** des Weinbergs (Strukturtensor der Helligkeit: die Zeilen
 * sind im Bild Streifen); nur wo das Bild keine klare Richtung zeigt (Kohaerenz < [MIN_COHERENCE]), laufen
 * die Zeilen in Fallrichtung des Gelaendes (die haeufigste Anlage in Steillagen). Die Lage der einzelnen
 * Zeile (Phase) ist nicht gemessen.
 *
 * Eigenes Material [MAT_VINES] (Vertexfarben, ohne Textur, ohne Erweiterung) im Knoten der Baeume;
 * Budget: hoechstens [MAX_TRIANGLES] und nur, was die Kachel noch frei hat; von der Strecke nach aussen.
 */
internal class VineyardLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        if (ctx.level != 0 || ctx.budget.vegetation <= 0) return
        val s = ctx.spec
        if (ctx.track.nearest(s.anchorE, s.anchorN, s.cellM * 0.71 + NEAR_M) == null) return
        val areas = ctx.map.areas(s.minE, s.minN, s.maxE, s.maxN).filter { it.sourceClass == "vineyard" }
        if (areas.isEmpty()) return
        // Was die Kachel nach Baeumen, Strassen und Gebaeuden noch frei hat (die Baeume nehmen auch die Reste
        // der anderen Schichten, [TreesLayer]); die Summe der Kachel bleibt damit im Budget.
        val b = ctx.budget
        val spare = b.route + b.roads + b.buildings + b.vegetation + b.water -
            listOf(WorldNames.NODE_ROUTE, WorldNames.NODE_ROADS, WorldNames.NODE_BUILDINGS, WorldNames.NODE_TREES, WorldNames.NODE_WATER).sumOf { out.triangles(it) }
        val budget = min(MAX_TRIANGLES, spare)
        if (budget <= 0) return
        val net = RoadNet(ctx, marginM = 6.0)
        val buildings = ctx.map.buildings(s.minE - 4, s.minN - 4, s.maxE + 4, s.maxN + 4)
        // Alle Zeilenstuecke sammeln, dann von der Strecke nach aussen bis zum Budget.
        val pieces = ArrayList<Piece>()
        for (a in areas) rowsOf(ctx, a, net, buildings, pieces)
        if (pieces.isEmpty()) return
        pieces.sortBy { it.dist }
        val mesh = out.mesh(WorldNames.NODE_TREES, TileMaterial(MAT_VINES, roughness = 0.9f))
        var used = 0
        for (p in pieces) {
            val cost = (p.pts.size / 2 - 1) * 4
            if (used + cost > budget) break
            emit(ctx, mesh, p)
            used += cost
        }
    }

    private class Piece(val pts: DoubleArray, val dist: Double, val seed: Int)

    /** Zeilen eines Weinbergs in dieser Kachel. */
    private fun rowsOf(ctx: TileContext, a: MapArea, net: RoadNet, buildings: List<MapBuilding>, out: MutableList<Piece>) {
        val s = ctx.spec
        val outer = a.rings[0]
        var minE = Double.MAX_VALUE; var minN = Double.MAX_VALUE; var maxE = -Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (i in 0 until outer.size / 2) {
            minE = min(minE, outer[2 * i]); maxE = max(maxE, outer[2 * i])
            minN = min(minN, outer[2 * i + 1]); maxN = max(maxN, outer[2 * i + 1])
        }
        minE = max(minE, s.minE); minN = max(minN, s.minN); maxE = min(maxE, s.maxE); maxN = min(maxN, s.maxN)
        if (minE >= maxE || minN >= maxN) return
        val dir = rowDirection(ctx, a, minE, minN, maxE, maxN) ?: return
        val de = dir[0]
        val dn = dir[1]
        // Querrichtung (Zeilenabstand)
        val qe = dn
        val qn = -de
        val ce = (minE + maxE) / 2
        val cn = (minN + maxN) / 2
        val half = hypot(maxE - minE, maxN - minN) / 2 + ROW_M
        val rows = (half / ROW_M).toInt()
        // Phase aus Weltkoordinaten (Nachbarkacheln setzen dieselben Zeilen)
        val q0 = floor((ce * qe + cn * qn) / ROW_M) * ROW_M
        for (k in -rows..rows) {
            val q = q0 + k * ROW_M
            // Punkt auf der Zeile, der der Mitte am naechsten liegt
            val t0 = ce * de + cn * dn
            val oe = qe * q + de * t0
            val on = qn * q + dn * t0
            var cur = ArrayList<Double>()
            var bestD = Double.MAX_VALUE
            fun flush() {
                if (cur.size >= 4) out += Piece(cur.toDoubleArray(), bestD, (q * 7).toInt())
                cur = ArrayList(); bestD = Double.MAX_VALUE
            }
            var t = -half
            while (t <= half) {
                val e = oe + de * t
                val n = on + dn * t
                t += STEP_M
                var ok = s.contains(e, n) && pointInRings(a.rings, e, n)
                if (ok) {
                    val hit = ctx.track.nearest(e, n, NEAR_M)
                    ok = hit != null && hit.distance > ctx.track.halfWidthAt(hit.distM) + 2.0
                    if (hit != null && ok && hit.distance < bestD) bestD = hit.distance
                }
                ok = ok && net.clearance(e, n) > 1.0 && buildings.none { pointInRings(it.rings, e, n) }
                if (ok) {
                    cur += e; cur += n
                } else {
                    flush()
                }
            }
            flush()
        }
    }

    /**
     * Zeilenrichtung (Einheitsvektor e, n) aus dem Luftbild ueber dem Weinberg: Die Helligkeit wechselt quer
     * zu den Zeilen; der Strukturtensor der Gradienten liefert die Querrichtung. Ohne klares Muster
     * Fallrichtung des Gelaendes; ohne Gefaelle `null` (dann keine Zeilen — lieber nichts als erfunden).
     */
    private fun rowDirection(ctx: TileContext, a: MapArea, minE: Double, minN: Double, maxE: Double, maxN: Double): DoubleArray? {
        val img = ctx.aerialRaw
        if (img != null) {
            val s = ctx.spec
            val mpp = s.cellM / img.width
            val x0 = max(1, ((minE - s.minE) / mpp).toInt())
            val x1 = min(img.width - 2, ((maxE - s.minE) / mpp).toInt())
            val y0 = max(1, ((s.maxN - maxN) / mpp).toInt())
            val y1 = min(img.height - 2, ((s.maxN - minN) / mpp).toInt())
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            var cnt = 0
            val step = max(1, ((x1 - x0) * (y1 - y0) / 40000.0).let { sqrt(it) }.toInt())
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val e = s.minE + (x + 0.5) * mpp
                    val n = s.maxN - (y + 0.5) * mpp
                    if (pointInRings(a.rings, e, n)) {
                        val gx = lum(img[x + 1, y]) - lum(img[x - 1, y])
                        val gy = lum(img[x, y - 1]) - lum(img[x, y + 1]) // nach Norden positiv
                        sxx += gx * gx; syy += gy * gy; sxy += gx * gy
                        cnt++
                    }
                    x += step
                }
                y += step
            }
            if (cnt > 200) {
                val tr = sxx + syy
                val diff = sqrt((sxx - syy) * (sxx - syy) + 4 * sxy * sxy)
                val coherence = if (tr > 1e-9) diff / tr else 0.0
                if (coherence >= MIN_COHERENCE) {
                    // Hauptrichtung der Gradienten = quer zu den Zeilen
                    val theta = 0.5 * atan2(2 * sxy, sxx - syy)
                    val ge = cos(theta)
                    val gn = sin(theta)
                    return doubleArrayOf(-gn, ge)
                }
            }
        }
        // Fallrichtung
        val e = (minE + maxE) / 2
        val n = (minN + maxN) / 2
        val dx = ctx.ground.heightAt(e + 5, n) - ctx.ground.heightAt(e - 5, n)
        val dy = ctx.ground.heightAt(e, n + 5) - ctx.ground.heightAt(e, n - 5)
        val l = hypot(dx, dy)
        if (l < 0.3) return null
        return doubleArrayOf(dx / l, dy / l)
    }

    private fun lum(c: Int): Double = 0.299 * Argb.red(c) + 0.587 * Argb.green(c) + 0.114 * Argb.blue(c)

    /** Λ-Band entlang der Punkte: zwei schraege Flaechen je Abschnitt. */
    private fun emit(ctx: TileContext, m: MeshBuilder, p: Piece) {
        val pts = p.pts
        val k = pts.size / 2
        for (i in 0 until k - 1) {
            val e0 = pts[2 * i]; val n0 = pts[2 * i + 1]
            val e1 = pts[2 * i + 2]; val n1 = pts[2 * i + 3]
            var re = n1 - n0
            var rn = -(e1 - e0)
            val l = hypot(re, rn)
            if (l < 1e-6) continue
            re /= l; rn /= l
            val g0 = ctx.ground.heightAt(e0, n0)
            val g1 = ctx.ground.heightAt(e1, n1)
            val tone = 0.85 + 0.3 * Noise.hash01(p.seed, i, 0x7173)
            val leaf = RouteGeometry.linColor(0.075 * tone, 0.13 * tone, 0.035 * tone)
            val low = RouteGeometry.linColor(0.05 * tone, 0.08 * tone, 0.03 * tone)
            for (side in intArrayOf(-1, 1)) {
                val bo = BASE_HALF_M * side
                val to = TOP_HALF_M * side
                val a = Vec3(ctx.x(e0 + re * bo), ctx.y(g0 + BASE_M), ctx.z(n0 + rn * bo))
                val b = Vec3(ctx.x(e1 + re * bo), ctx.y(g1 + BASE_M), ctx.z(n1 + rn * bo))
                val c = Vec3(ctx.x(e1 + re * to), ctx.y(g1 + TOP_M), ctx.z(n1 + rn * to))
                val d = Vec3(ctx.x(e0 + re * to), ctx.y(g0 + TOP_M), ctx.z(n0 + rn * to))
                var nor = (b - a).cross(d - a).normalized(Vec3(0.0, 1.0, 0.0))
                val outward = Vec3(re * side, 0.0, -rn * side)
                val flip = nor.dot(outward) < 0
                if (flip) nor = nor * -1.0
                val i0 = m.vertex(a.x, a.y, a.z, nor.x, nor.y, nor.z, 0.0, 0.0, low)
                m.vertex(b.x, b.y, b.z, nor.x, nor.y, nor.z, 0.0, 0.0, low)
                m.vertex(c.x, c.y, c.z, nor.x, nor.y, nor.z, 0.0, 0.0, leaf)
                m.vertex(d.x, d.y, d.z, nor.x, nor.y, nor.z, 0.0, 0.0, leaf)
                if (flip) m.quad(i0 + 1, i0, i0 + 3, i0 + 2) else m.quad(i0, i0 + 1, i0 + 2, i0 + 3)
            }
        }
    }

    companion object {
        const val MAT_VINES = "x_mat_vines"

        /** Zeilen nur bis zu diesem Abstand (m) von der Strecke. */
        const val NEAR_M = 70.0

        /** Zeilenabstand (m), ueblich 1,8–2,2 m. */
        const val ROW_M = 2.0

        /** Laenge eines Abschnitts (m). */
        const val STEP_M = 3.0

        const val BASE_M = 0.25
        const val TOP_M = 1.7
        const val BASE_HALF_M = 0.3
        const val TOP_HALF_M = 0.08

        /** Mindest-Kohaerenz des Strukturtensors fuer eine Richtung aus dem Luftbild. */
        const val MIN_COHERENCE = 0.12

        const val MAX_TRIANGLES = 7000
    }
}
