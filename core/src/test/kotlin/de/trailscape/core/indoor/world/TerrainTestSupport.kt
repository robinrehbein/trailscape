package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Kartendaten aus Listen, mit Rechteckfilter ueber die Umgebungsrechtecke. */
internal class ListMapData(
    private val buildings: List<MapBuilding> = emptyList(),
    private val roads: List<MapRoad> = emptyList(),
    private val areas: List<MapArea> = emptyList(),
    private val waterways: List<MapWaterway> = emptyList(),
) : MapData {
    private fun hit(coords: List<DoubleArray>, minE: Double, minN: Double, maxE: Double, maxN: Double): Boolean {
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (r in coords) for (i in 0 until r.size / 2) {
            a = minOf(a, r[2 * i]); b = minOf(b, r[2 * i + 1]); c = maxOf(c, r[2 * i]); d = maxOf(d, r[2 * i + 1])
        }
        return c >= minE && a <= maxE && d >= minN && b <= maxN
    }

    override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = buildings.filter { hit(it.rings, minE, minN, maxE, maxN) }
    override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = roads.filter { hit(listOf(it.line), minE, minN, maxE, maxN) }
    override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = areas.filter { hit(it.rings, minE, minN, maxE, maxN) }
    override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = waterways.filter { hit(listOf(it.line), minE, minN, maxE, maxN) }
}

internal object TerrainTestSupport {
    val origin = GeoOrigin(51.0575, 7.1485, 120.0)

    /** Rechteck als Ring (gegen den Uhrzeigersinn). */
    fun rect(e0: Double, n0: Double, e1: Double, n1: Double) = doubleArrayOf(e0, n0, e1, n0, e1, n1, e0, n1)

    /** Unregelmaessiges Vieleck um einen Mittelpunkt (deterministisch gewellt). */
    fun blob(ce: Double, cn: Double, r: Double, seed: Int, points: Int = 24): DoubleArray {
        val out = DoubleArray(points * 2)
        for (i in 0 until points) {
            val a = 2 * PI * i / points
            val rr = r * (0.8 + 0.35 * Noise.value(i * 0.7, seed.toDouble(), seed))
            out[2 * i] = ce + cos(a) * rr
            out[2 * i + 1] = cn + sin(a) * rr
        }
        return out
    }

    /**
     * Baut eine [IndoorRoute] aus einer ENU-Linie (`e0, n0, e1, n1, …`), alle
     * [stepM] Meter neu abgetastet; Laufmeter = Bogenlaenge in ENU.
     */
    fun routeFromEnu(
        line: DoubleArray,
        loop: Boolean = false,
        stepM: Double = 10.0,
        ele: (Double, Double) -> Double = { _, _ -> 120.0 },
        surfaces: List<SurfaceKind> = listOf(SurfaceKind.ASPHALT),
    ): IndoorRoute {
        val pts = ArrayList<IndoorRoutePoint>()
        val cnt = line.size / 2
        var dist = 0.0
        var carry = 0.0
        pts.add(IndoorRoutePoint(0.0, origin.toLat(line[1]), origin.toLon(line[0]), ele(line[0], line[1])))
        for (i in 1 until cnt) {
            val ax = line[2 * i - 2]; val ay = line[2 * i - 1]
            val bx = line[2 * i]; val by = line[2 * i + 1]
            val l = hypot(bx - ax, by - ay)
            var t = stepM - carry
            while (t <= l) {
                val x = ax + (bx - ax) * t / l
                val y = ay + (by - ay) * t / l
                pts.add(IndoorRoutePoint(dist + t, origin.toLat(y), origin.toLon(x), ele(x, y)))
                t += stepM
            }
            carry = l - (t - stepM)
            dist += l
        }
        if (dist - pts.last().distM > 0.5) {
            pts.add(IndoorRoutePoint(dist, origin.toLat(line[2 * cnt - 1]), origin.toLon(line[2 * cnt - 2]), ele(line[2 * cnt - 2], line[2 * cnt - 1])))
        }
        val len = pts.last().distM
        val part = len / surfaces.size
        val sections = surfaces.mapIndexed { i, k -> SurfaceSection(i * part, if (i == surfaces.size - 1) len else (i + 1) * part, k) }
        return IndoorRoute("test", "Test", pts, sections, loop, IndoorRouteSource.NONE)
    }

    /** Kreis um (ce, cn) mit Radius [r], gegen den Uhrzeigersinn, geschlossen. */
    fun circle(ce: Double, cn: Double, r: Double, step: Double = 10.0): DoubleArray {
        val n = maxOf(16, (2 * PI * r / step).toInt())
        return DoubleArray((n + 1) * 2) { k ->
            val i = k / 2
            val a = 2 * PI * i / n
            if (k % 2 == 0) ce + r * cos(a) else cn + r * sin(a)
        }
    }

    /** Liniensegmente aneinanderhaengen. */
    fun polyline(vararg pts: Double) = pts
}
