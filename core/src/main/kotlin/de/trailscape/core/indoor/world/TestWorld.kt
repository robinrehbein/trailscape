package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.SurfaceClass
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * # Die erfundene Testwelt (docs/indoor.md 7.5)
 *
 * Eine geschlossene Runde von rund 3 km mit **allen** Belagsklassen (je
 * etwa 270 m, `UNKNOWN` eingeschlossen) durch eine erfundene, aber
 * mitteleuropaeisch gebaute Landschaft: Huegel (±40 m), ein Tal mit Bach
 * (Holzbruecke) und Teich, ein Dorf mit rund 50 Haeusern an der
 * Durchgangsstrasse und einer Querstrasse, ein Laub- und ein Nadelwald,
 * Aecker, Wiesen und Feldwege. Kein Netz, keine Uhrzeit, kein Zufall: Alles
 * folgt aus festen Samen, zweimal gebaut ergibt dieselben Bytes.
 *
 * Entworfen wird in einem eigenen ENU-System um [DESIGN_ORIGIN]; in die Welt
 * gelangt alles ueber Breite/Laenge, also genau wie echte Geodaten.
 */
internal object TestWorld {
    /** Bezugspunkt des Entwurfs (erfunden, im Bergischen Land). */
    val DESIGN_ORIGIN = GeoOrigin(51.02, 7.25, 0.0)

    /** Ziel-Rundenlaenge in Metern. */
    const val LOOP_M: Double = 3_000.0

    val ATTRIBUTION = WorldAttribution(GeoSourceKind.VECTOR, "Testwelt: erfundene Landschaft, keine Geodaten", "", "")

    /** Reihenfolge der Belaege entlang der Runde, mit OSM-`highway` und Wegklasse der Karte. */
    private val SECTIONS: List<Triple<SurfaceKind, String, String>> = listOf(
        Triple(SurfaceKind.ASPHALT, "tertiary", "tertiary"),
        Triple(SurfaceKind.PAVING_STONES, "residential", "minor"),
        Triple(SurfaceKind.COBBLESTONE, "living_street", "minor"),
        Triple(SurfaceKind.CONCRETE, "service", "service"),
        Triple(SurfaceKind.WOOD, "footway", "path"),
        Triple(SurfaceKind.COMPACTED, "track", "track"),
        Triple(SurfaceKind.GRAVEL, "track", "track"),
        Triple(SurfaceKind.DIRT, "path", "path"),
        Triple(SurfaceKind.GRASS, "track", "track"),
        Triple(SurfaceKind.SAND, "path", "path"),
        Triple(SurfaceKind.UNKNOWN, "cycleway", "path"),
    )

    /** Halbe Spannweite der Bruecke ueber den Bach. */
    private const val BRIDGE_HALF_M = 30.0

    private const val SEED = 0x7e57

    fun create(): TestWorldScene = Builder().build()

    private class Builder {
        // Runde als dichter Linienzug, dann gleichmaessig neu abgetastet.
        val loop: DoubleArray
        val length: Double
        val step = 5.0

        init {
            val dense = 4000
            fun raw(th: Double, scale: Double): DoubleArray {
                val r = scale * (1 + 0.16 * sin(2 * th + 0.4) + 0.07 * cos(3 * th))
                return doubleArrayOf(r * cos(th), r * sin(th))
            }
            fun polyLen(scale: Double): Double {
                var l = 0.0
                var prev = raw(0.0, scale)
                for (k in 1..dense) {
                    val p = raw(2 * PI * k / dense, scale)
                    l += hypot(p[0] - prev[0], p[1] - prev[1])
                    prev = p
                }
                return l
            }
            val scale = 470.0 * LOOP_M / polyLen(470.0)
            val pts = ArrayList<DoubleArray>()
            val cum = DoubleArray(dense + 1)
            for (k in 0..dense) {
                pts += raw(2 * PI * k / dense, scale)
                if (k > 0) cum[k] = cum[k - 1] + hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1])
            }
            val total = cum[dense]
            val count = (total / step).roundToInt()
            length = total
            loop = DoubleArray((count + 1) * 2)
            var j = 0
            for (i in 0..count) {
                val s = total * i / count
                while (j < dense - 1 && cum[j + 1] < s) j++
                val t = ((s - cum[j]) / (cum[j + 1] - cum[j])).coerceIn(0.0, 1.0)
                loop[2 * i] = pts[j][0] + (pts[j + 1][0] - pts[j][0]) * t
                loop[2 * i + 1] = pts[j][1] + (pts[j + 1][1] - pts[j][1]) * t
            }
            // geschlossen: letzter Punkt = erster
            loop[2 * count] = loop[0]
            loop[2 * count + 1] = loop[1]
        }

        val count: Int get() = loop.size / 2 - 1
        val sectionM: Double get() = length / SECTIONS.size

        /** Lage bei Laufmeter [s] (Entwurfs-ENU). */
        fun at(s: Double): DoubleArray {
            val d = ((s % length) + length) % length
            val f = d / length * count
            val i = f.toInt().coerceIn(0, count - 1)
            val t = f - i
            return doubleArrayOf(
                loop[2 * i] + (loop[2 * i + 2] - loop[2 * i]) * t,
                loop[2 * i + 1] + (loop[2 * i + 3] - loop[2 * i + 1]) * t,
            )
        }

        /** Einheitsvektor nach links (Runde gegen den Uhrzeigersinn: links = innen). */
        fun leftAt(s: Double): DoubleArray {
            val a = at(s - 4)
            val b = at(s + 4)
            val de = b[0] - a[0]
            val dn = b[1] - a[1]
            val l = hypot(de, dn)
            return doubleArrayOf(-dn / l, de / l)
        }

        fun offset(s: Double, o: Double): DoubleArray {
            val p = at(s)
            val l = leftAt(s)
            return doubleArrayOf(p[0] + l[0] * o, p[1] + l[1] * o)
        }

        // Bach: entspringt innerhalb der Runde und kreuzt sie einmal in der Mitte des Holzabschnitts.
        val bridgeAt = sectionM * 4.5
        val brook: DoubleArray = run {
            val p = at(bridgeAt)
            val l = leftAt(bridgeAt)
            val out = ArrayList<Double>()
            var t = -160.0
            while (t <= 1600.0) {
                val wiggle = 22 * sin(t / 170.0) * smoothstep(-160.0, 0.0, t) + 8 * sin(t / 47.0)
                // t < 0 nach innen (links), t > 0 nach aussen
                out += p[0] - l[0] * t + l[1] * wiggle
                out += p[1] - l[1] * t - l[0] * wiggle
                t += 20.0
            }
            out.toDoubleArray()
        }

        // Teich neben dem Bach, ausserhalb der Runde.
        val pond: DoubleArray = run {
            val p = at(bridgeAt)
            val l = leftAt(bridgeAt)
            val ce = p[0] - l[0] * 300 + l[1] * 75
            val cn = p[1] - l[1] * 300 - l[0] * 75
            val n = 28
            DoubleArray(n * 2) { k ->
                val a = 2 * PI * (k / 2) / n
                val r = 36 + 6 * sin(3 * a + 1) + 3 * cos(5 * a)
                if (k % 2 == 0) ce + r * cos(a) else cn + r * sin(a)
            }
        }
        val pondCenter = doubleArrayOf(pond.filterIndexed { i, _ -> i % 2 == 0 }.average(), pond.filterIndexed { i, _ -> i % 2 == 1 }.average())

        /** Gelaende ohne Teich: Huegel und Bachtal. */
        fun terrain(e: Double, n: Double): Double {
            val hills = 210.0 + 30.0 * Noise.fbm(e / 1100.0, n / 1100.0, SEED, 4) + 10.0 * Noise.fbm(e / 320.0, n / 320.0, SEED + 1, 3)
            val dBrook = distanceToLine(brook, e, n)
            // Tal nach aussen tiefer werdend, an der Quelle flach
            val along = hypot(e - brook[0], n - brook[1])
            val depth = 14.0 * smoothstep(0.0, 260.0, along)
            return hills - depth * exp(-(dBrook / 70.0) * (dBrook / 70.0)) - 0.6 * exp(-(dBrook / 3.0) * (dBrook / 3.0))
        }

        val pondLevel = run {
            var lo = Double.MAX_VALUE
            for (k in 0 until pond.size / 2) lo = min(lo, terrain(pond[2 * k], pond[2 * k + 1]))
            lo - 0.4
        }

        /** Gelaende ueber dem Meer (Entwurfs-ENU). */
        fun height(e: Double, n: Double): Double {
            val h = terrain(e, n)
            val d = hypot(e - pondCenter[0], n - pondCenter[1])
            if (d > 70) return h
            val inside = pointInRings(listOf(pond), e, n)
            val bottom = if (inside) pondLevel - 1.2 * smoothstep(0.0, 30.0, 40 - d) else pondLevel + 0.3
            val w = if (inside) 1.0 else 1 - smoothstep(0.0, 25.0, distanceToRing(pond, e, n))
            return h + (bottom - h) * w
        }

        fun build(): TestWorldScene {
            val points = ArrayList<IndoorRoutePoint>()
            for (i in 0..count) {
                val d = min(i * length / count, length)
                val e = loop[2 * i]
                val n = loop[2 * i + 1]
                points += IndoorRoutePoint(d, DESIGN_ORIGIN.toLat(n), DESIGN_ORIGIN.toLon(e), height(e, n))
            }
            // Hoehen der Strecke glaetten (BRouter/GPS-Hoehen sind ebenfalls geglaettet)
            val ele = points.map { it.eleM }
            val smoothed = points.mapIndexed { i, p ->
                var s = 0.0
                for (k in -4..4) s += ele[Math.floorMod(i + k, count)]
                p.copy(eleM = s / 9)
            }.toMutableList()
            smoothed[count] = smoothed[count].copy(eleM = smoothed[0].eleM)
            val sections = SECTIONS.mapIndexed { k, (kind, highway, _) ->
                SurfaceSection(k * sectionM, if (k == SECTIONS.size - 1) length else (k + 1) * sectionM, kind, highway)
            }
            val route = IndoorRoute(
                id = "testwelt-1",
                name = "Testwelt",
                points = smoothed,
                surfaces = sections,
                isLoop = true,
                // erfundene Wegmerkmale, wie sie BRouter liefern wuerde
                source = IndoorRouteSource.BROUTER_WAY_TAGS,
            )
            val map = DesignMap()
            addRoads(map)
            addWater(map)
            addVillage(map)
            addLand(map)
            return TestWorldScene(route, this::height, { d -> abs(d - bridgeAt) <= BRIDGE_HALF_M }, map)
        }

        // -------------------------------------------------------------------
        // Kartendaten
        // -------------------------------------------------------------------

        fun arcLine(from: Double, to: Double, o: Double = 0.0): DoubleArray {
            val out = ArrayList<Double>()
            var s = from
            while (true) {
                val p = offset(s, o)
                out += p[0]
                out += p[1]
                if (s >= to) break
                s = min(to, s + step)
            }
            return out.toDoubleArray()
        }

        fun addRoads(map: DesignMap) {
            // Die Runde selbst, je Abschnitt mit Wegklasse; um den Bach als Bruecke.
            for ((k, sec) in SECTIONS.withIndex()) {
                val (kind, _, cls) = sec
                val a = k * sectionM
                val b = (k + 1) * sectionM
                val surface = if (kind.coarse == SurfaceClass.PAVED) "paved" else if (kind.coarse == SurfaceClass.UNPAVED) "unpaved" else null
                if (kind == SurfaceKind.WOOD) {
                    map.roads += MapRoad(arcLine(a, bridgeAt - BRIDGE_HALF_M), cls, "footway", surface)
                    map.roads += MapRoad(arcLine(bridgeAt - BRIDGE_HALF_M, bridgeAt + BRIDGE_HALF_M), cls, "footway", surface, brunnel = "bridge", layer = 1)
                    map.roads += MapRoad(arcLine(bridgeAt + BRIDGE_HALF_M, b), cls, "footway", surface)
                } else {
                    map.roads += MapRoad(arcLine(a, b), cls, if (cls == "path") sec.second else null, surface)
                }
            }
            // Querstrassen im Dorf
            map.roads += MapRoad(street(sectionM * 1.45, -280.0, 170.0), "minor", surface = "paved")
            map.roads += MapRoad(street(sectionM * 0.45, -160.0, 0.0), "minor", surface = "paved")
            // Feldwege und ein Pfad am Bach
            map.roads += MapRoad(street(sectionM * 5.6, -650.0, 0.0), "track", surface = "unpaved")
            map.roads += MapRoad(street(sectionM * 8.3, 0.0, 380.0), "track", surface = "unpaved")
            map.roads += MapRoad(street(sectionM * 3.3, -420.0, 0.0), "track", surface = "unpaved")
            val bank = ArrayList<Double>()
            for (k in 10 until brook.size / 2 - 10) {
                val i = 2 * k
                val de = brook[i + 2] - brook[i - 2]
                val dn = brook[i + 3] - brook[i - 1]
                val l = hypot(de, dn)
                bank += brook[i] - dn / l * 9
                bank += brook[i + 1] + de / l * 9
            }
            map.roads += MapRoad(bank.toDoubleArray(), "path", "footway", "unpaved")
        }

        /** Gerade Strasse quer zur Runde bei [s], von Versatz [from] bis [to] (links positiv), leicht geschwungen. */
        fun street(s: Double, from: Double, to: Double): DoubleArray {
            val p = at(s)
            val l = leftAt(s)
            val out = ArrayList<Double>()
            var o = from
            while (true) {
                val bend = 6 * sin(o / 90.0)
                out += p[0] + l[0] * o + l[1] * bend
                out += p[1] + l[1] * o - l[0] * bend
                if (o >= to) break
                o = min(to, o + 10.0)
            }
            return out.toDoubleArray()
        }

        fun addWater(map: DesignMap) {
            map.waterways += MapWaterway(brook, "stream")
            map.areas += MapArea(listOf(pond), LandClass.WATER, "pond")
        }

        /** Haeuser entlang der Durchgangsstrasse und der Querstrassen. */
        fun addVillage(map: DesignMap) {
            val streets = ArrayList<Pair<DoubleArray, Double>>() // Linie, halbe Breite
            streets += arcLine(0.0, sectionM * 3.0) to 3.0
            map.roads.filter { it.cls == "minor" }.forEach { streets += it.line to 2.5 }
            val placed = ArrayList<DoubleArray>() // Mitte e, n, Radius
            var id = 1L
            fun free(ring: DoubleArray): Boolean {
                var ce = 0.0
                var cn = 0.0
                for (k in 0 until ring.size / 2) { ce += ring[2 * k]; cn += ring[2 * k + 1] }
                ce /= ring.size / 2
                cn /= ring.size / 2
                var r = 0.0
                for (k in 0 until ring.size / 2) r = max(r, hypot(ring[2 * k] - ce, ring[2 * k + 1] - cn))
                if (placed.any { hypot(it[0] - ce, it[1] - cn) < it[2] + r + 2.5 }) return false
                // Abstand zu allen Strassen und zum Bach/Teich
                for (k in 0 until ring.size / 2) {
                    val e = ring[2 * k]
                    val n = ring[2 * k + 1]
                    if (streets.any { (line, hw) -> distanceToLine(line, e, n) < hw + 2.5 }) return false
                    if (map.roads.any { it.cls != "minor" && distanceToLine(it.line, e, n) < 4.0 }) return false
                    if (distanceToLine(brook, e, n) < 25) return false
                }
                placed += doubleArrayOf(ce, cn, r)
                return true
            }
            for ((line, hw) in streets) {
                var s = 12.0
                var k = 0
                val total = lineLength(line)
                while (s < total - 10 && map.buildings.size < MAX_HOUSES) {
                    for (side in intArrayOf(1, -1)) {
                        val h = Noise.hash01(k, side, SEED + 7)
                        if (h > 0.86) continue
                        val (p, dir) = pointOnLine(line, s)
                        // Achsen: entlang der Strasse (a) und von ihr weg (b)
                        val ae = dir[0]
                        val an = dir[1]
                        val be = -an * side
                        val bn = ae * side
                        val setback = hw + 4.0 + 3.0 * Noise.hash01(k, side, SEED + 8)
                        val kindRoll = Noise.hash01(k, side, SEED + 9)
                        val front = if (kindRoll < 0.2) 17.0 else 8.5 + 4.0 * Noise.hash01(k, side, SEED + 10)
                        val depth = if (kindRoll < 0.2) 9.0 else 9.5 + 4.0 * Noise.hash01(k, side, SEED + 11)
                        fun corner(x: Double, y: Double) = doubleArrayOf(p[0] + ae * x + be * (setback + y), p[1] + an * x + bn * (setback + y))
                        val ring: DoubleArray = if (kindRoll in 0.2..0.45) {
                            // L-Form: Haupthaus mit Fluegel nach hinten
                            val wing = 5.5 + 1.5 * Noise.hash01(k, side, SEED + 12)
                            listOf(
                                corner(-front / 2, 0.0), corner(front / 2, 0.0), corner(front / 2, depth + 7.0),
                                corner(front / 2 - wing, depth + 7.0), corner(front / 2 - wing, depth), corner(-front / 2, depth),
                            ).flatMap { it.toList() }.toDoubleArray()
                        } else {
                            listOf(corner(-front / 2, 0.0), corner(front / 2, 0.0), corner(front / 2, depth), corner(-front / 2, depth))
                                .flatMap { it.toList() }.toDoubleArray()
                        }
                        if (!free(ccw(ring))) continue
                        val height = if (kindRoll < 0.2) 7.0 else 6.5 + 2.5 * Noise.hash01(k, side, SEED + 13)
                        map.buildings += MapBuilding(id++, listOf(ccw(ring)), Math.round(height * 10) / 10.0)
                        // Nebengebaeude (Garage, Scheune) dahinter
                        if (Noise.hash01(k, side, SEED + 14) < 0.45 && map.buildings.size < MAX_HOUSES) {
                            val w = 4.0 + 2.5 * Noise.hash01(k, side, SEED + 15)
                            val d = 5.0 + 3.0 * Noise.hash01(k, side, SEED + 16)
                            val x0 = (Noise.hash01(k, side, SEED + 17) - 0.5) * front
                            val y0 = depth + (if (kindRoll in 0.2..0.45) 9.5 else 3.0)
                            val shed = listOf(corner(x0 - w / 2, y0), corner(x0 + w / 2, y0), corner(x0 + w / 2, y0 + d), corner(x0 - w / 2, y0 + d))
                                .flatMap { it.toList() }.toDoubleArray()
                            if (free(ccw(shed))) map.buildings += MapBuilding(id++, listOf(ccw(shed)), 3.5)
                        }
                    }
                    s += 21.0 + 7.0 * Noise.hash01(k, 0, SEED + 18)
                    k++
                }
            }
        }

        /** Wohngebiet, zwei Waelder, Aecker, Wiesen. */
        fun addLand(map: DesignMap) {
            val sm = sectionM
            map.areas += MapArea(listOf(band(-10.0, sm * 3.15, 75.0, -75.0, 0.0)), LandClass.RESIDENTIAL, "residential")
            map.areas += MapArea(listOf(ccw(streetBand(sm * 1.45, -290.0, 180.0, 55.0))), LandClass.RESIDENTIAL, "residential")
            // Laubwald beidseits des Erdwegs, Nadelwald am Sandweg
            map.areas += MapArea(listOf(band(sm * 6.0, sm * 8.0, 170.0, -300.0, 40.0)), LandClass.FOREST, "broadleaved")
            map.areas += MapArea(listOf(band(sm * 8.85, sm * 10.0, 140.0, -260.0, 30.0)), LandClass.FOREST, "needleleaved")
            // Aecker ausserhalb (rechts) zwischen Dorf und Bach und hinter dem Bach, durch Feldwege getrennt
            map.areas += MapArea(listOf(band(sm * 3.2, sm * 3.95, -12.0, -330.0, 15.0)), LandClass.FARMLAND, "farmland")
            map.areas += MapArea(listOf(band(sm * 5.15, sm * 5.55, -12.0, -380.0, 15.0)), LandClass.FARMLAND, "farmland")
            map.areas += MapArea(listOf(band(sm * 5.65, sm * 5.98, -12.0, -380.0, 15.0)), LandClass.FARMLAND, "farmland")
            // Acker und Wiese innen
            map.areas += MapArea(listOf(band(sm * 3.3, sm * 4.2, 15.0, 230.0, 12.0)), LandClass.FARMLAND, "farmland")
            map.areas += MapArea(listOf(band(sm * 8.0, sm * 8.8, 12.0, 260.0, 10.0)), LandClass.MEADOW, "meadow")
            map.areas += MapArea(listOf(band(sm * 10.05, sm * 11.0, -12.0, -260.0, 12.0)), LandClass.MEADOW, "meadow")
        }

        /**
         * Flaeche entlang der Runde zwischen Laufmeter [from] und [to], vom
         * Versatz [o1] bis [o2] (links positiv), aeussere Kante mit [wobble] m
         * Rauschen.
         */
        fun band(from: Double, to: Double, o1: Double, o2: Double, wobble: Double): DoubleArray {
            val out = ArrayList<Double>()
            var s = from
            while (s <= to) {
                val p = offset(s, o1)
                out += p[0]; out += p[1]
                s += 20.0
            }
            s = to
            while (s >= from) {
                val w = wobble * (Noise.fbm(s / 120.0, o2 / 50.0, SEED + 3, 3))
                val p = offset(s, o2 + if (o2 > o1) w else -w)
                out += p[0]; out += p[1]
                s -= 20.0
            }
            return ccw(out.toDoubleArray())
        }

        fun streetBand(s: Double, from: Double, to: Double, half: Double): DoubleArray {
            val line = street(s, from, to)
            val left = ArrayList<Double>()
            val right = ArrayList<Double>()
            for (k in 0 until line.size / 2) {
                val a = max(0, k - 1)
                val b = min(line.size / 2 - 1, k + 1)
                val de = line[2 * b] - line[2 * a]
                val dn = line[2 * b + 1] - line[2 * a + 1]
                val l = hypot(de, dn)
                left += line[2 * k] - dn / l * half; left += line[2 * k + 1] + de / l * half
                right += line[2 * k] + dn / l * half; right += line[2 * k + 1] - de / l * half
            }
            val out = ArrayList<Double>(left)
            for (k in right.size / 2 - 1 downTo 0) { out += right[2 * k]; out += right[2 * k + 1] }
            return out.toDoubleArray()
        }
    }

    private const val MAX_HOUSES = 58

    private fun lineLength(line: DoubleArray): Double {
        var l = 0.0
        for (k in 0 until line.size / 2 - 1) l += hypot(line[2 * k + 2] - line[2 * k], line[2 * k + 3] - line[2 * k + 1])
        return l
    }

    /** Punkt und Richtung (Einheitsvektor) bei Laenge [s] auf [line]. */
    private fun pointOnLine(line: DoubleArray, s: Double): Pair<DoubleArray, DoubleArray> {
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

    private fun distanceToRing(ring: DoubleArray, e: Double, n: Double): Double =
        distanceToLine(ring + doubleArrayOf(ring[0], ring[1]), e, n)

    /** Ring gegen den Uhrzeigersinn (positive Flaeche in e/n), wie die Vektorkacheln ihn liefern. */
    private fun ccw(ring: DoubleArray): DoubleArray {
        if (ringArea(ring) >= 0) return ring
        val n = ring.size / 2
        return DoubleArray(ring.size) { i -> ring[2 * (n - 1 - i / 2) + i % 2] }
    }

    /** Sammelt die Kartendaten im Entwurfs-ENU. */
    class DesignMap {
        val buildings = ArrayList<MapBuilding>()
        val roads = ArrayList<MapRoad>()
        val areas = ArrayList<MapArea>()
        val waterways = ArrayList<MapWaterway>()
    }
}

/**
 * Die fertige Testwelt: Strecke, Gelaende, Bruecke und Kartendaten.
 *
 * @property isBridge Laufmeter auf der Holzbruecke ueber den Bach.
 */
internal class TestWorldScene(
    val route: IndoorRoute,
    private val designHeight: (Double, Double) -> Double,
    private val bridge: (Double) -> Boolean,
    private val design: TestWorld.DesignMap,
) {
    private val o = TestWorld.DESIGN_ORIGIN

    /** Gelaendehoehe ueber dem Meer. */
    fun heightAtLatLon(lat: Double, lon: Double): Double = designHeight(o.toEast(lon), o.toNorth(lat))

    fun isBridge(distM: Double): Boolean = bridge(distM)

    /** Zahl der Gebaeude (fuer Tests). */
    val buildingCount: Int get() = design.buildings.size

    /** Die Kartendaten in der Welt mit Ursprung [origin]. */
    fun mapData(origin: GeoOrigin): MapData {
        fun conv(a: DoubleArray) = DoubleArray(a.size) { i ->
            if (i % 2 == 0) origin.toEast(o.toLon(a[i])) else origin.toNorth(o.toLat(a[i]))
        }
        val buildings = design.buildings.map { MapBuilding(it.id, it.rings.map(::conv), it.heightM, it.minHeightM, it.colour) }
        val roads = design.roads.map { MapRoad(conv(it.line), it.cls, it.subclass, it.surface, it.brunnel, it.layer, it.service) }
        val areas = design.areas.map { MapArea(it.rings.map(::conv), it.kind, it.sourceClass) }
        val waterways = design.waterways.map { MapWaterway(conv(it.line), it.cls, it.brunnel) }
        val bi = RectIndex(buildings, { boundsOf(it.rings) })
        val ri = RectIndex(roads, { boundsOf(listOf(it.line)) })
        val ai = RectIndex(areas, { boundsOf(it.rings) })
        val wi = RectIndex(waterways, { boundsOf(listOf(it.line)) })
        return object : MapData {
            override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = bi.query(minE, minN, maxE, maxN)
            override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = ri.query(minE, minN, maxE, maxN)
            override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = ai.query(minE, minN, maxE, maxN)
            override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = wi.query(minE, minN, maxE, maxN)
        }
    }
}
