package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Kartendaten aus einer festen Liste von Gebaeuden (Tests und Vorschau). */
internal class ListMapData(val all: List<MapBuilding>) : MapData {
    override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapBuilding> = all.filter { b ->
        val r = b.rings[0]
        var loE = Double.MAX_VALUE
        var hiE = -Double.MAX_VALUE
        var loN = Double.MAX_VALUE
        var hiN = -Double.MAX_VALUE
        for (i in 0 until r.size / 2) {
            loE = minOf(loE, r[2 * i]); hiE = maxOf(hiE, r[2 * i])
            loN = minOf(loN, r[2 * i + 1]); hiN = maxOf(hiN, r[2 * i + 1])
        }
        !(hiE < minE || loE > maxE || hiN < minN || loN > maxN)
    }

    override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapRoad>()
    override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapArea>()
    override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapWaterway>()
}

/**
 * Ein Dorf fuer Vorschau und Tests: echte Grundrisse aus Odenthal (Blecher,
 * Altenberg, Bergisches Land), an eine Dorfstrasse entlang der Vorschau-Strecke
 * gestellt.
 *
 * Die Grundrisse stammen aus den OpenFreeMap-Vektorkacheln z14 (8515/5481,
 * 8516/5480, 8516/5481), Daten © OpenStreetMap-Mitwirkende, ODbL. Sie sind um
 * ihren Mittelpunkt verschoben und an ihrer Hauptachse ausgerichtet, sonst
 * unveraendert — samt der Rasterung der Kacheln auf rund 0,4 m (schiefe
 * Rechtecke, kleine Versaetze), mit der der Weltbau zurechtkommen muss.
 */
internal object BuildingsVillage {
    /** [tag]: H Haus, S Schuppen/Garage, L/E/M/X Winkel und Anbauten (6/8/10–12/13+ Ecken), O schief, G gross, B Halle. */
    class Shape(val tag: Char, val heightM: Double, val ring: DoubleArray)

    val shapes: List<Shape> = listOf(
        Shape('H', 4.0, doubleArrayOf(-5.39, 3.94, -5.60, -3.96, 5.60, -3.95, 5.41, 3.96)),
        Shape('H', 5.0, doubleArrayOf(-5.78, -3.23, 5.62, -3.17, 5.85, 3.23, -5.85, 3.13)),
        Shape('H', 5.0, doubleArrayOf(-5.44, -3.28, 5.36, -3.40, 5.44, 3.40, -5.37, 3.12)),
        Shape('H', 5.0, doubleArrayOf(-6.90, -5.56, 6.70, -5.36, 6.90, 5.56, -6.70, 5.36)),
        Shape('H', 5.0, doubleArrayOf(-6.87, -6.24, 6.86, -6.08, 7.07, 6.09, -7.07, 6.24)),
        Shape('H', 5.0, doubleArrayOf(5.83, -5.30, 5.76, 5.23, -5.83, 5.30, -5.76, -5.23)),
        Shape('H', 5.0, doubleArrayOf(-5.41, -3.15, 5.52, -3.26, 5.41, 3.15, -5.52, 3.26)),
        Shape('H', 5.0, doubleArrayOf(-5.12, -4.31, 5.12, -4.35, 5.40, 4.35, -5.40, 4.31)),
        Shape('H', 4.0, doubleArrayOf(-5.96, -4.45, 6.07, -4.56, 5.96, 4.45, -6.07, 4.56)),
        Shape('H', 5.0, doubleArrayOf(-6.17, 5.65, -5.93, -5.65, 6.10, -5.60, 6.17, 5.44)),
        Shape('H', 5.0, doubleArrayOf(-4.95, -3.04, 4.95, -3.22, 4.85, 3.22, -4.86, 3.05)),
        Shape('H', 5.0, doubleArrayOf(-5.93, -3.08, 5.98, -3.29, 6.04, 3.29, -6.04, 3.13)),
        Shape('H', 5.0, doubleArrayOf(-5.00, -4.88, 4.89, -4.78, 5.00, 4.88, -4.89, 4.78)),
        Shape('H', 5.0, doubleArrayOf(-5.98, -5.25, 6.00, -5.27, 5.98, 5.25, -6.00, 5.27)),
        Shape('S', 5.0, doubleArrayOf(-3.90, -1.50, 3.90, -1.50, 3.90, 1.50, -3.90, 1.50)),
        Shape('S', 5.0, doubleArrayOf(2.85, -2.80, 2.66, 2.61, -2.85, 2.80, -2.66, -2.61)),
        Shape('S', 4.0, doubleArrayOf(2.81, -1.57, 2.73, 1.25, -2.81, 1.59, -2.57, -1.59)),
        Shape('S', 5.0, doubleArrayOf(2.77, -1.97, 2.49, 1.49, -2.77, 2.13, -2.59, -2.13)),
        Shape('S', 5.0, doubleArrayOf(-3.17, -1.58, 3.39, -1.94, 3.28, 1.94, -3.39, 1.92)),
        Shape('S', 5.0, doubleArrayOf(3.51, 3.01, -3.40, 2.85, -3.72, -2.96, 3.72, -3.01)),
        Shape('S', 5.0, doubleArrayOf(-3.09, -1.45, 3.47, -2.02, 3.48, 2.02, -3.48, 1.83)),
        Shape('S', 5.0, doubleArrayOf(-3.31, -1.50, 3.12, -1.24, 3.32, 1.42, -3.32, 1.50)),
        Shape('L', 5.0, doubleArrayOf(3.41, 2.75, 3.36, 1.20, -10.08, 1.18, -10.40, -2.79, 10.40, -2.62, 10.28, 2.79)),
        Shape('L', 5.0, doubleArrayOf(-7.50, -5.65, 1.90, -5.65, 1.90, -1.95, 7.50, -1.95, 7.50, 5.65, -7.50, 5.65)),
        Shape('L', 5.0, doubleArrayOf(5.71, -4.46, 5.84, 2.72, 2.77, 2.48, 2.48, 4.38, -5.84, 4.46, -5.83, -4.14)),
        Shape('L', 5.0, doubleArrayOf(-7.03, 0.52, -5.96, 0.74, -5.93, -5.07, 7.03, -5.15, 6.94, 5.12, -6.83, 5.15)),
        Shape('L', 5.0, doubleArrayOf(-6.97, -2.36, 2.43, -2.41, 2.70, -6.62, 6.90, -6.64, 6.97, 6.56, -6.93, 6.64)),
        Shape('L', 5.0, doubleArrayOf(-7.14, -5.26, 3.15, -6.22, 7.14, -4.22, 4.04, 2.15, 4.08, 5.18, -6.33, 6.22)),
        Shape('L', 5.0, doubleArrayOf(-8.12, -4.49, 8.12, -4.09, 8.03, 2.09, -0.10, 1.89, 0.01, 4.20, -7.83, 4.49)),
        Shape('L', 5.0, doubleArrayOf(-6.84, -2.57, 0.89, -2.68, 1.07, -6.44, 6.79, -6.31, 6.84, 6.44, -6.60, 6.33)),
        Shape('E', 5.0, doubleArrayOf(-5.98, -0.74, -4.05, -0.76, -3.99, -4.52, 5.98, -4.40, 5.90, -0.12, 3.95, -0.20, 4.02, 4.35, -5.89, 4.52)),
        Shape('E', 5.0, doubleArrayOf(6.27, -3.57, 6.24, 0.59, 5.39, 0.33, 5.18, 3.40, -5.13, 3.57, -4.87, 0.18, -6.05, 0.38, -6.27, -3.44)),
        Shape('E', 5.0, doubleArrayOf(-9.67, -4.84, 5.33, -4.48, 5.15, -3.70, 9.42, -3.96, 9.67, 3.48, 5.12, 3.16, 5.18, 4.71, -9.52, 4.84)),
        Shape('E', 5.0, doubleArrayOf(-2.89, 6.76, -2.96, 5.70, -6.63, 5.69, -6.90, 0.18, -2.66, 0.19, -2.64, -6.74, 6.90, -6.79, 6.59, 6.79)),
        Shape('E', 5.0, doubleArrayOf(-6.71, -0.76, -6.52, -5.46, 0.19, -5.50, 0.36, -0.99, 6.71, -0.85, 6.57, 5.50, -4.79, 5.44, -5.05, -0.82)),
        Shape('E', 5.0, doubleArrayOf(-6.08, 4.33, -6.30, -2.63, -4.36, -2.67, -4.47, -4.22, 4.71, -4.33, 4.82, -2.78, 6.30, -2.50, 5.85, 4.23)),
        Shape('M', 5.0, doubleArrayOf(-7.27, -1.58, -1.86, -1.55, -1.80, -7.06, 4.84, -6.98, 4.95, 0.45, 7.39, 0.33, 7.41, 7.00, -1.99, 7.06, -2.00, 4.24, -7.41, 4.22)),
        Shape('M', 5.0, doubleArrayOf(2.92, 5.72, -8.77, 5.65, -8.85, -7.24, -3.49, -7.10, -3.39, -2.98, 5.28, -2.94, 5.21, 5.44, 8.63, 5.42, 8.85, 7.20, 2.81, 7.24)),
        Shape('M', 5.0, doubleArrayOf(-6.98, 1.71, -8.12, 1.47, -8.16, -1.55, -7.01, -1.32, -7.10, -4.74, 7.68, -4.54, 8.16, 3.67, 5.13, 3.71, 4.85, 4.55, -6.95, 4.74)),
        Shape('M', 5.0, doubleArrayOf(-9.19, -2.55, -4.59, -2.29, -3.68, -3.48, -4.91, -6.32, -1.57, -7.54, 0.10, -3.48, 2.59, -6.23, 9.80, -0.18, 4.00, 6.71, 1.47, 7.54, -9.80, 6.94)),
        Shape('X', 5.0, doubleArrayOf(-8.90, 2.50, -4.95, 2.23, -4.80, -4.95, -0.61, -4.56, -0.57, -6.49, 6.99, -6.47, 8.26, -5.33, 8.49, -1.48, 9.04, -0.83, 9.03, 1.19, 8.24, 2.23, 8.41, 6.21, -9.04, 6.49)),
        Shape('X', 5.0, doubleArrayOf(2.42, 5.66, 2.49, 5.10, -0.25, 5.32, -0.31, 6.38, -0.80, 5.75, -6.94, 5.82, -6.93, -0.38, -5.95, -0.26, -5.84, -5.02, -0.68, -5.21, -0.61, -6.28, 6.89, -6.38, 6.94, 5.96)),
        Shape('X', 5.0, doubleArrayOf(3.70, -3.04, 7.94, -3.05, 8.72, -2.28, 9.02, 2.25, 7.68, 3.03, 4.00, 3.04, 3.66, 6.51, -8.79, 6.26, -9.02, 0.68, -7.46, 0.67, -7.47, -0.88, -8.74, -1.16, -8.83, -6.46, 3.90, -6.51)),
        Shape('O', 5.0, doubleArrayOf(-5.60, -4.03, 4.10, -4.01, 5.69, -2.69, 5.62, 3.66, -5.69, 4.03)),
        Shape('O', 5.0, doubleArrayOf(-5.74, -4.90, 4.03, -4.98, 3.97, -0.90, 5.74, 0.59, 4.21, 2.45, 4.02, 4.75, -5.38, 4.98)),
        Shape('O', 5.0, doubleArrayOf(-6.76, 5.68, -6.57, -5.68, 6.76, -5.37, 6.67, 5.10, 1.04, 5.15)),
        Shape('G', 8.0, doubleArrayOf(16.88, 6.39, -13.74, 6.50, -13.81, 2.13, -16.88, 1.82, -16.75, -6.50, 16.55, -6.42)),
        Shape('G', 5.0, doubleArrayOf(-16.88, 9.14, -16.76, -9.03, 16.88, -9.14, 16.76, 9.03)),
        Shape('B', 5.0, doubleArrayOf(-18.36, 3.49, -17.98, -13.77, 18.36, -13.85, 18.21, 13.73, -11.21, 13.85, -11.22, 3.17)),
    )

    /** Ein Vierkanthof mit Innenhof (nach Art der Hoefe der Gegend, kein einzelnes echtes Gebaeude). */
    val courtyard: List<DoubleArray> = listOf(
        doubleArrayOf(-13.1, -10.2, 13.0, -10.0, 13.2, 10.1, -12.9, 10.3),
        doubleArrayOf(-6.2, -4.4, -6.0, 4.6, 6.1, 4.4, 6.3, -4.5),
    )

    /** Dreht und verschiebt einen Ring: [angle] = Richtung der x-Achse gegen Osten. */
    fun place(ring: DoubleArray, e: Double, n: Double, angle: Double): DoubleArray {
        val c = cos(angle)
        val s = sin(angle)
        return DoubleArray(ring.size) { i ->
            val x = ring[i - i % 2]
            val y = ring[i - i % 2 + 1]
            if (i % 2 == 0) e + x * c - y * s else n + x * s + y * c
        }
    }

    /**
     * Stellt die Grundrisse beidseits der Strecke auf: Haeuser 6–15 m vom
     * Weg, mal trauf-, mal giebelstaendig, Garagen und Schuppen dazwischen,
     * die grossen Bauten in zweiter Reihe.
     */
    fun along(track: WorldTrack, fromM: Double = 45.0, toM: Double = track.lengthM - 45.0): List<MapBuilding> {
        val out = ArrayList<MapBuilding>()
        val circles = ArrayList<DoubleArray>()
        val order = shapes.indices.sortedBy { Noise.hash(it, 77, 0x51de) }
        val next = doubleArrayOf(fromM, fromM + 9.0)
        var id = 1L

        fun tryPlace(rings: List<DoubleArray>, heightM: Double, side: Int, lateralExtra: Double, k: Int, big: Boolean): Boolean {
            val ring = rings[0]
            var w = 0.0
            var d = 0.0
            for (i in 0 until ring.size / 2) {
                w = max(w, abs(ring[2 * i]))
                d = max(d, abs(ring[2 * i + 1]))
            }
            val gableToStreet = !big && w < 8.0 && Noise.hash01(k, 3, 0x51de) < 0.35
            val along = if (gableToStreet) d else w
            val depth = if (gableToStreet) w else d
            val idx = if (side > 0) 0 else 1
            var s = next[idx] + along
            while (s < toM) {
                val p = track.poseAt(s)
                val h = Math.toRadians(p.headingDeg)
                val lat = 6.5 + 8.0 * Noise.hash01(k, 4, 0x51de) + depth + lateralExtra
                // rechts der Fahrtrichtung: (cos h, −sin h)
                val ce = p.e + side * cos(h) * lat
                val cn = p.n - side * sin(h) * lat
                val r = hypot(w, d)
                if (circles.none { hypot(it[0] - ce, it[1] - cn) < it[2] + r + 1.0 }) {
                    // x-Achse des Grundrisses entlang der Strasse (Richtung (sin h, cos h)) oder quer dazu.
                    val jitter = (Noise.hash01(k, 5, 0x51de) - 0.5) * 0.14
                    val angle = atan2(cos(h), sin(h)) + (if (gableToStreet) PI / 2 else 0.0) + jitter
                    out.add(MapBuilding(id++, rings.map { place(it, ce, cn, angle) }, heightM))
                    circles.add(doubleArrayOf(ce, cn, r))
                    next[idx] = s + along + 2.5 + 5.0 * Noise.hash01(k, 6, 0x51de)
                    return true
                }
                s += 3.0
            }
            return false
        }

        var k = 0
        for (i in order) {
            val sh = shapes[i]
            val big = sh.tag == 'G' || sh.tag == 'B'
            val side = if (Noise.hash01(k, 1, 0x51de) < 0.5) 1 else -1
            val extra = if (big) 9.0 else 0.0
            if (!tryPlace(listOf(sh.ring), sh.heightM, side, extra, k, big)) tryPlace(listOf(sh.ring), sh.heightM, -side, extra, k, big)
            k++
            if (k == 20) tryPlace(courtyard, 5.0, -side, 6.0, 1000, true)
        }
        return out
    }
}
