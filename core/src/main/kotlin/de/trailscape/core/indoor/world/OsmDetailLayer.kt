package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # Zusatzobjekte aus vollen OSM-Daten (Detailtreue)
 *
 * Zeichnet, was [OsmDetail] (Overpass-Auszug, nur Katalog-/Server-Bau) liefert, **an der echten Stelle**:
 *
 * | Art | Modell | wo |
 * |---|---|---|
 * | Hecke | Laubkoerper 0,8 m breit, Hoehe `height` oder 1,6 m | Stufe 0, bis [LINE_NEAR_M] neben der Strecke |
 * | Zaun | Pfosten alle 3 m, Holzlatten bzw. Metallholme (`fence_type`) | dto. |
 * | Mauer | Mauerband 0,3 m, Hoehe `height` oder 1,2 m | dto. |
 * | Leitplanke | Pfosten alle 2 m, Schutzplanke 0,45–0,75 m | dto. |
 * | Laermschutzwand | Wand 0,2 m, Hoehe `height` oder 3,5 m | dto. |
 * | Strommast / -leitung | Gittermast (Eckstiele, Riegel, Traversen) bzw. Holzmast; drei Leiterseile je Feld mit Durchhang | Stufe 0 und 1 |
 * | Windrad | Turm, Gondel, drei Blaetter; Nabenhoehe `height`, Rotor `rotor:diameter` | alle Stufen (weithin sichtbar) |
 * | Funkmast, Turm, Schornstein, Wasserturm, Silo | einfache Drehkoerper mit `height` | alle Stufen; Silo/Turm nur ohne Gebaeudegrundriss an der Stelle |
 * | Laterne | Mast 5,5 m mit Ausleger | Stufe 0, bis 30 m neben der Strecke |
 *
 * Linien, die auf der Fahrbahn der Strecke liegen (Leitplanke, Zaun direkt am Rand), werden an deren
 * Kante geschoben. Fehlende Hoehen sind Vorgaben (in den Daten oft nicht eingetragen). Material wie die
 * Kleinteile ([PropsLayer.MATERIAL], Vertexfarben), kein zusaetzlicher Zeichenaufruf.
 */
internal class OsmDetailLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        val s = ctx.spec
        val pad = if (ctx.level == 0) 10.0 else 0.0
        val feats = ctx.map.osmFeatures(s.minE - pad, s.minN - pad, s.maxE + pad, s.maxN + pad)
        if (feats.isEmpty()) return
        Builder(ctx, out).run(feats.sortedWith(compareBy({ it.kind.ordinal }, { it.minE }, { it.minN })))
    }

    private class Builder(private val ctx: TileContext, private val out: TileOutput) {
        private val s = ctx.spec
        private val track = ctx.track
        private val mat = TileMaterial(PropsLayer.MATERIAL, roughness = 0.85f)
        private val scratch = MeshBuilder(64, 64)
        private val budget = when (ctx.level) {
            0 -> min(LEVEL0_MAX, max(0, ctx.budget.props - out.triangles(WorldNames.NODE_PROPS)))
            1 -> LEVEL1_MAX
            else -> LEVEL2_MAX
        }
        private var used = 0
        private val buildings by lazy { ctx.map.buildings(s.minE - 8, s.minN - 8, s.maxE + 8, s.maxN + 8) }

        fun run(feats: List<OsmFeature>) {
            for (f in feats) {
                if (used >= budget) return
                when (f.kind) {
                    OsmKind.HEDGE, OsmKind.FENCE, OsmKind.WALL, OsmKind.GUARD_RAIL, OsmKind.NOISE_BARRIER -> if (ctx.level == 0) barrier(f)
                    OsmKind.POWER_LINE -> if (ctx.level <= 1) powerLine(f)
                    OsmKind.POWER_TOWER -> if (ctx.level <= 1) pointOwned(f) { powerTower(f) }
                    OsmKind.POWER_POLE -> if (ctx.level == 0) pointOwned(f) { powerPole(f) }
                    OsmKind.WIND_TURBINE -> pointOwned(f) { windTurbine(f) }
                    OsmKind.MAST, OsmKind.TOWER, OsmKind.CHIMNEY, OsmKind.WATER_TOWER, OsmKind.SILO -> pointOwned(f) { landmark(f) }
                    OsmKind.STREET_LAMP -> if (ctx.level == 0) pointOwned(f) { lamp(f) }
                }
            }
        }

        private inline fun pointOwned(f: OsmFeature, draw: () -> Unit) {
            if (s.contains(f.e, f.n)) draw()
        }

        private fun ground(e: Double, n: Double) = ctx.ground.heightAt(e, n)

        // --- Linien entlang der Strecke --------------------------------------------

        /** Teilt die Linie in Stuecke ≤ [SEG_M], schiebt Punkte von der Fahrbahn und zeichnet die Stuecke dieser Kachel. */
        private fun barrier(f: OsmFeature) {
            val p = f.pts
            val k = p.size / 2
            val height = f.heightM()
            for (i in 0 until k - 1) {
                val e0 = p[2 * i]; val n0 = p[2 * i + 1]
                val e1 = p[2 * i + 2]; val n1 = p[2 * i + 3]
                // Abschnitt beruehrt die Kachel nicht: weiter. Sonst gehoert jedes Teilstueck der Kachel, in der es
                // beginnt (nicht der ganze Abschnitt der seines Anfangs — sonst fehlten an jeder Kachelgrenze Stuecke).
                if (max(e0, e1) < s.minE || min(e0, e1) >= s.maxE || max(n0, n1) < s.minN || min(n0, n1) >= s.maxN) continue
                val len = hypot(e1 - e0, n1 - n0)
                if (len < 0.3) continue
                val steps = max(1, Math.ceil(len / SEG_M).toInt())
                for (j in 0 until steps) {
                    if (!s.contains(e0 + (e1 - e0) * j / steps, n0 + (n1 - n0) * j / steps)) continue
                    val a = pushOff(e0 + (e1 - e0) * j / steps, n0 + (n1 - n0) * j / steps) ?: continue
                    val b = pushOff(e0 + (e1 - e0) * (j + 1) / steps, n0 + (n1 - n0) * (j + 1) / steps) ?: continue
                    when (f.kind) {
                        OsmKind.HEDGE -> band(a, b, 0.4, 0.3, height ?: 1.6, hedgeColor(a[0], a[1]), top = true)
                        OsmKind.WALL -> band(a, b, 0.15, 0.15, height ?: if (f.tags["barrier"] == "retaining_wall") 1.0 else 1.2, STONE, top = true)
                        OsmKind.NOISE_BARRIER -> band(a, b, 0.1, 0.1, height ?: 3.5, NOISE, top = true)
                        OsmKind.FENCE -> fence(a, b, height ?: 1.2, f.tags["fence_type"], j == 0)
                        OsmKind.GUARD_RAIL -> guardRail(a, b, j == 0)
                        else -> {}
                    }
                    commit(a[0], a[1])
                    if (used >= budget) return
                }
            }
        }

        /** Nur bis [LINE_NEAR_M] neben der Strecke; auf der Fahrbahn an deren Kante geschoben. */
        private fun pushOff(e: Double, n: Double): DoubleArray? {
            val hit = track.nearest(e, n, LINE_NEAR_M) ?: return null
            val hw = track.halfWidthAt(hit.distM)
            if (hit.distance >= hw + 0.35) return doubleArrayOf(e, n)
            val pose = track.poseAt(hit.distM)
            val h = Math.toRadians(pose.headingDeg)
            val side = if (hit.lateral >= 0) 1.0 else -1.0
            // rechts in Fahrtrichtung = (cos h, −sin h)
            return doubleArrayOf(pose.e + cos(h) * (hw + 0.4) * side, pose.n - sin(h) * (hw + 0.4) * side)
        }

        /** Band (Hecke, Mauer, Wand): Querschnitt unten [hb], oben [ht] halbe Breite, Hoehe [h]. */
        private fun band(a: DoubleArray, b: DoubleArray, hb: Double, ht: Double, h: Double, color: Int, top: Boolean) {
            val le = b[0] - a[0]
            val ln = b[1] - a[1]
            val l = hypot(le, ln)
            if (l < 1e-6) return
            val re = ln / l
            val rn = -le / l
            val ga = ground(a[0], a[1]) - 0.1
            val gb = ground(b[0], b[1]) - 0.1
            for (side in doubleArrayOf(-1.0, 1.0)) {
                val p0 = v(a[0] + re * hb * side, ga, a[1] + rn * hb * side)
                val p1 = v(b[0] + re * hb * side, gb, b[1] + rn * hb * side)
                val p2 = v(b[0] + re * ht * side, gb + h + 0.1, b[1] + rn * ht * side)
                val p3 = v(a[0] + re * ht * side, ga + h + 0.1, a[1] + rn * ht * side)
                quadOut(p0, p1, p2, p3, Vec3(re * side, 0.0, -rn * side), color)
            }
            if (top) {
                val p0 = v(a[0] - re * ht, ga + h + 0.1, a[1] - rn * ht)
                val p1 = v(b[0] - re * ht, gb + h + 0.1, b[1] - rn * ht)
                val p2 = v(b[0] + re * ht, gb + h + 0.1, b[1] + rn * ht)
                val p3 = v(a[0] + re * ht, ga + h + 0.1, a[1] + rn * ht)
                quadOut(p0, p1, p2, p3, Vec3(0.0, 1.0, 0.0), shade(color, 1.1))
            }
        }

        private fun fence(a: DoubleArray, b: DoubleArray, h: Double, type: String?, firstPost: Boolean) {
            val metal = type == "chain_link" || type == "metal" || type == "metal_bars" || type == "wire" || type == "electric"
            val postC = if (metal) GALV else WOOD
            val ga = ground(a[0], a[1])
            val gb = ground(b[0], b[1])
            if (firstPost) post(a[0], a[1], ga, h, 0.04, postC)
            post(b[0], b[1], gb, h, 0.04, postC)
            val rails = if (type == "chain_link") doubleArrayOf(0.1, h * 0.5, h) else doubleArrayOf(h * 0.45, h * 0.9)
            for (r in rails) rail(a[0], a[1], ga + r, b[0], b[1], gb + r, if (metal) 0.015 else 0.03, if (metal) GALV else WOOD)
        }

        private fun guardRail(a: DoubleArray, b: DoubleArray, firstPost: Boolean) {
            val ga = ground(a[0], a[1])
            val gb = ground(b[0], b[1])
            if (firstPost) post(a[0], a[1], ga, 0.72, 0.05, GALV_DARK)
            post(b[0], b[1], gb, 0.72, 0.05, GALV_DARK)
            // Planke als schmales Band 0,45–0,75 m
            val le = b[0] - a[0]; val ln = b[1] - a[1]
            val l = hypot(le, ln)
            if (l < 1e-6) return
            val re = ln / l; val rn = -le / l
            for (side in doubleArrayOf(-1.0, 1.0)) {
                val o = 0.06 * side
                val p0 = v(a[0] + re * o, ga + 0.45, a[1] + rn * o)
                val p1 = v(b[0] + re * o, gb + 0.45, b[1] + rn * o)
                val p2 = v(b[0] + re * o, gb + 0.76, b[1] + rn * o)
                val p3 = v(a[0] + re * o, ga + 0.76, a[1] + rn * o)
                quadOut(p0, p1, p2, p3, Vec3(re * side, 0.0, -rn * side), GALV)
            }
        }

        // --- Strom -----------------------------------------------------------------

        private fun towerHeight(f: OsmFeature): Double = f.heightM() ?: 38.0

        private fun powerTower(f: OsmFeature) {
            val h = towerHeight(f)
            val g = ground(f.e, f.n)
            val base = 3.2
            val top = 0.8
            // Eckstiele
            val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0)
            for ((cx, cy) in corners) rail(f.e + cx * base, f.n + cy * base, g - 0.3, f.e + cx * top, f.n + cy * top, g + h, 0.12, STEEL)
            // Riegel alle 7 m
            var y = 4.0
            while (y < h - 2) {
                val w = base + (top - base) * (y / h)
                for (k in corners.indices) {
                    val (ax, ay) = corners[k]
                    val (bx, by) = corners[(k + 1) % 4]
                    rail(f.e + ax * w, f.n + ay * w, g + y, f.e + bx * w, f.n + by * w, g + y, 0.06, STEEL)
                }
                y += 7.0
            }
            // Traversen quer zur Leitung (Richtung unbekannt: Ost-West), zwei Ebenen
            for ((yy, span) in listOf(0.72 to 7.5, 0.86 to 5.5)) {
                rail(f.e - span, f.n, g + h * yy, f.e + span, f.n, g + h * yy, 0.15, STEEL)
            }
            commit(f.e, f.n)
        }

        private fun powerPole(f: OsmFeature) {
            val h = f.heightM() ?: 9.0
            val g = ground(f.e, f.n)
            post(f.e, f.n, g, h, 0.13, WOOD)
            rail(f.e - 0.8, f.n, g + h - 0.4, f.e + 0.8, f.n, g + h - 0.4, 0.05, WOOD)
            commit(f.e, f.n)
        }

        /** Leiterseile je Feld (zwischen zwei Punkten der Leitung), mit Durchhang; das Feld gehoert der Kachel seines Anfangs. */
        private fun powerLine(f: OsmFeature) {
            val p = f.pts
            val k = p.size / 2
            val high = f.tags["power"] == "line"
            for (i in 0 until k - 1) {
                val e0 = p[2 * i]; val n0 = p[2 * i + 1]
                val e1 = p[2 * i + 2]; val n1 = p[2 * i + 3]
                if (!s.contains(e0, n0)) continue
                val len = hypot(e1 - e0, n1 - n0)
                if (len < 5 || len > 700) continue
                val attachA = if (high) 38.0 * 0.72 else 8.6
                val attachB = attachA
                val ga = ground(e0, n0) + attachA
                val gb = ground(e1, n1) + attachB
                val offs = if (high) doubleArrayOf(-7.0, 7.0, -5.0, 5.0) else doubleArrayOf(-0.7, 0.0, 0.7)
                val sag = len * if (high) 0.03 else 0.02
                for (o in offs) {
                    val yo = if (high && kotlin.math.abs(o) < 6) 0.14 * 38.0 else 0.0
                    val segs = 6
                    var pe = e0 + o; var pn = n0; var py = ga + yo
                    for (j in 1..segs) {
                        val t = j.toDouble() / segs
                        val ce = e0 + (e1 - e0) * t + o
                        val cn = n0 + (n1 - n0) * t
                        val cy = ga + (gb - ga) * t + yo - 4 * sag * t * (1 - t)
                        rail(pe, pn, py, ce, cn, cy, if (high) 0.04 else 0.02, CABLE, sides = 3)
                        pe = ce; pn = cn; py = cy
                    }
                }
                commit(e0, n0)
                if (used >= budget) return
            }
        }

        // --- Landmarken ------------------------------------------------------------

        private fun windTurbine(f: OsmFeature) {
            val hub = f.heightM() ?: 125.0
            val rotor = (f.heightM("rotor:diameter") ?: 120.0).coerceIn(20.0, 180.0)
            val g = ground(f.e, f.n)
            scratch.addCylinder(v(f.e, g - 1, f.n), v(f.e, g + hub, f.n), 3.2, 1.6, 10, capStart = false, color = WHITE)
            // Gondel (nach Westen, Hauptwindrichtung)
            box(f.e + 3.0, f.n, 7.5, 2.2, g + hub - 2.0, g + hub + 2.4, WHITE)
            val hubE = f.e - 4.8
            val hubY = g + hub
            scratch.addCylinder(v(hubE + 0.5, hubY, f.n), v(hubE - 1.6, hubY, f.n), 1.6, 0.3, 8, color = WHITE)
            // Drei Blaetter in der Nord-Sued-Ebene, eines nach oben
            val r = rotor / 2
            for (k in 0 until 3) {
                val a = PI / 2 + k * 2 * PI / 3
                val ca = cos(a); val sa = sin(a)
                // senkrecht zum Blatt in der Ebene
                val root = 1.6; val tip = 0.35
                val p0 = v(hubE, hubY + sa * 1.2 - ca * root, f.n + ca * 1.2 + sa * root)
                val p1 = v(hubE, hubY + sa * 1.2 + ca * root, f.n + ca * 1.2 - sa * root)
                val p2 = v(hubE, hubY + sa * r + ca * tip, f.n + ca * r - sa * tip)
                val p3 = v(hubE, hubY + sa * r - ca * tip, f.n + ca * r + sa * tip)
                quadOut(p0, p1, p2, p3, Vec3(-1.0, 0.0, 0.0), WHITE)
                quadOut(p1, p0, p3, p2, Vec3(1.0, 0.0, 0.0), WHITE)
            }
            commit(f.e, f.n)
        }

        private fun landmark(f: OsmFeature) {
            val g = ground(f.e, f.n)
            val type = f.tags["tower:type"]
            when (f.kind) {
                OsmKind.MAST -> {
                    val h = f.heightM() ?: 50.0
                    scratch.addCylinder(v(f.e, g - 0.5, f.n), v(f.e, g + h, f.n), 1.1, 0.35, 6, capStart = false, color = STEEL)
                }
                OsmKind.TOWER -> {
                    if (!freeOfBuildings(f.e, f.n)) return
                    val h = f.heightM() ?: if (type == "communication") 80.0 else 25.0
                    if (type == "communication") {
                        scratch.addCylinder(v(f.e, g - 0.5, f.n), v(f.e, g + h * 0.8, f.n), 4.5, 2.2, 12, capStart = false, color = CONCRETE)
                        scratch.addCylinder(v(f.e, g + h * 0.62, f.n), v(f.e, g + h * 0.68, f.n), 7.0, 7.0, 12, color = CONCRETE)
                        scratch.addCylinder(v(f.e, g + h * 0.8, f.n), v(f.e, g + h, f.n), 0.8, 0.3, 6, capStart = false, color = STEEL)
                    } else {
                        val c = if (type == "observation") WOOD else CONCRETE
                        box(f.e, f.n, 2.2, 2.2, g - 0.5, g + h, c)
                        scratch.addCylinder(v(f.e, g + h, f.n), v(f.e, g + h + 3.0, f.n), 3.0, 0.1, 4, color = shade(c, 0.7))
                    }
                }
                OsmKind.CHIMNEY -> {
                    val h = f.heightM() ?: 40.0
                    scratch.addCylinder(v(f.e, g - 0.5, f.n), v(f.e, g + h, f.n), max(1.2, h * 0.05), max(0.8, h * 0.03), 10, color = BRICK)
                }
                OsmKind.WATER_TOWER -> {
                    if (!freeOfBuildings(f.e, f.n)) return
                    val h = f.heightM() ?: 30.0
                    scratch.addCylinder(v(f.e, g - 0.5, f.n), v(f.e, g + h * 0.68, f.n), 2.6, 2.4, 12, capStart = false, color = CONCRETE)
                    scratch.addCylinder(v(f.e, g + h * 0.68, f.n), v(f.e, g + h, f.n), 6.5, 6.5, 14, color = CONCRETE)
                }
                OsmKind.SILO -> {
                    if (!freeOfBuildings(f.e, f.n)) return
                    val h = f.heightM() ?: 18.0
                    scratch.addCylinder(v(f.e, g - 0.5, f.n), v(f.e, g + h, f.n), 3.2, 3.2, 12, color = CONCRETE)
                }
                else -> return
            }
            commit(f.e, f.n)
        }

        private fun lamp(f: OsmFeature) {
            val hit = track.nearest(f.e, f.n, 30.0) ?: return
            val p = pushOff(f.e, f.n) ?: return
            if (hit.distance < track.halfWidthAt(hit.distM) + 0.3 && (p[0] == f.e && p[1] == f.n)) return
            val g = ground(p[0], p[1])
            post(p[0], p[1], g, 5.6, 0.07, STEEL)
            // Ausleger zur Strecke
            val pose = track.poseAt(hit.distM)
            var de = pose.e - p[0]; var dn = pose.n - p[1]
            val l = hypot(de, dn)
            if (l > 1e-6) { de /= l; dn /= l }
            rail(p[0], p[1], g + 5.5, p[0] + de * 1.1, p[1] + dn * 1.1, g + 5.65, 0.04, STEEL)
            box(p[0] + de * 1.2, p[1] + dn * 1.2, 0.18, 0.08, g + 5.55, g + 5.68, LAMP)
            commit(p[0], p[1])
        }

        private fun freeOfBuildings(e: Double, n: Double): Boolean = buildings.none { pointInRings(it.rings, e, n) }

        // --- Geometrie --------------------------------------------------------------

        private fun v(e: Double, u: Double, n: Double) = Vec3(ctx.x(e), ctx.y(u), ctx.z(n))

        private fun post(e: Double, n: Double, g: Double, h: Double, r: Double, color: Int) {
            scratch.addCylinder(v(e, g - 0.1, n), v(e, g + h, n), r, r, 4, capStart = false, color = color)
        }

        private fun rail(e0: Double, n0: Double, y0: Double, e1: Double, n1: Double, y1: Double, r: Double, color: Int, sides: Int = 4) {
            scratch.addCylinder(v(e0, y0, n0), v(e1, y1, n1), r, r, sides, capStart = false, capEnd = false, color = color)
        }

        /** Achsparalleler Quader (halbe Ausdehnung [we] nach Osten, [wn] nach Norden). */
        private fun box(e: Double, n: Double, we: Double, wn: Double, y0: Double, y1: Double, color: Int) {
            val a = v(e - we, y0, n + wn)
            val b = v(e + we, y1, n - wn)
            scratch.addBox(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z), max(a.x, b.x), max(a.y, b.y), max(a.z, b.z), color)
        }

        /** Viereck mit Normale moeglichst nach [outward] (glTF-Achsen). */
        private fun quadOut(a: Vec3, b: Vec3, c: Vec3, d: Vec3, outward: Vec3, color: Int) {
            var nor = (b - a).cross(d - a).normalized(Vec3(0.0, 1.0, 0.0))
            val flip = nor.dot(outward) < 0
            if (flip) nor = nor * -1.0
            val i = scratch.vertex(a.x, a.y, a.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(b.x, b.y, b.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(c.x, c.y, c.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            scratch.vertex(d.x, d.y, d.z, nor.x, nor.y, nor.z, 0.0, 0.0, color)
            if (flip) scratch.quad(i + 1, i, i + 3, i + 2) else scratch.quad(i, i + 1, i + 2, i + 3)
        }

        private fun hedgeColor(e: Double, n: Double): Int {
            val t = 0.8 + 0.4 * Noise.hash01(Math.floor(e).toInt(), Math.floor(n).toInt(), 0x4ed6)
            return RouteGeometry.linColor(0.045 * t, 0.085 * t, 0.03 * t)
        }

        private fun shade(c: Int, f: Double): Int = Argb.of(
            255,
            min(255, (Argb.red(c) * f).toInt()),
            min(255, (Argb.green(c) * f).toInt()),
            min(255, (Argb.blue(c) * f).toInt()),
        )

        private fun commit(e: Double, n: Double) {
            val tris = scratch.triangleCount
            if (tris > 0 && used + tris <= budget) {
                out.mesh(WorldNames.NODE_PROPS, mat, e, n).append(scratch)
                used += tris
            }
            scratch.clear()
        }
    }

    companion object {
        /** Zaeune, Hecken usw. nur bis zu diesem Abstand (m) von der Strecke. */
        const val LINE_NEAR_M = 60.0

        /** Laenge der Stuecke (m), in denen Linien dem Gelaende folgen. */
        const val SEG_M = 3.0

        const val LEVEL0_MAX = 9000
        const val LEVEL1_MAX = 3000
        const val LEVEL2_MAX = 1200

        private fun lin(r: Double, g: Double, b: Double) = RouteGeometry.linColor(r, g, b)
        private val STONE = lin(0.30, 0.28, 0.25)
        private val NOISE = lin(0.22, 0.24, 0.20)
        private val WOOD = lin(0.22, 0.16, 0.10)
        private val GALV = lin(0.50, 0.51, 0.51)
        private val GALV_DARK = lin(0.35, 0.36, 0.36)
        private val STEEL = lin(0.38, 0.39, 0.40)
        private val CABLE = lin(0.12, 0.12, 0.12)
        private val WHITE = lin(0.82, 0.83, 0.83)
        private val CONCRETE = lin(0.52, 0.50, 0.46)
        private val BRICK = lin(0.30, 0.12, 0.07)
        private val LAMP = lin(0.75, 0.75, 0.70)
    }
}
