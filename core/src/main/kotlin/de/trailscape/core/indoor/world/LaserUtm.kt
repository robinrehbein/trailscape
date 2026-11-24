package de.trailscape.core.indoor.world

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * # UTM fuer die amtlichen Hoehen- und Gebaeudedaten
 *
 * Die Laserdaten der Laender (DGM1, DOM1, LoD2) liegen in ETRS89/UTM
 * (NRW: Zone 32, EPSG:25832). Fuer den Weltbau ist ETRS89 von WGS84 nicht zu
 * unterscheiden (Abstand in Mitteleuropa unter 1 m, gleichmaessig ueber die
 * ganze Welt — die Lage gegen OSM und Luftbild verschiebt sich nicht
 * sichtbar).
 *
 * Formeln: transversale Mercator-Projektion nach Krueger mit der Reihe in
 * der dritten Abplattung `n` bis `n⁴` (Karney 2011, Gl. 35/36) —
 * Genauigkeit innerhalb einer Zone besser als 1 mm, Hin und Rueck.
 */
internal object Utm {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257222101 // GRS80
    private const val K0 = 0.9996
    private const val FALSE_E = 500_000.0

    private val n = F / (2 - F)
    private val aHat = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
    private val e = sqrt(F * (2 - F))
    private val alpha = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16 + 41 * n * n * n * n / 180,
        13 * n * n / 48 - 3 * n * n * n / 5 + 557 * n * n * n * n / 1440,
        61 * n * n * n / 240 - 103 * n * n * n * n / 140,
        49561 * n * n * n * n / 161280,
    )
    private val beta = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 37 * n * n * n / 96 - n * n * n * n / 360,
        n * n / 48 + n * n * n / 15 - 437 * n * n * n * n / 1440,
        17 * n * n * n / 480 - 37 * n * n * n * n / 840,
        4397 * n * n * n * n / 161280,
    )

    /** Mittelmeridian der Zone in Grad. */
    fun centralMeridian(zone: Int): Double = zone * 6.0 - 183.0

    /** WGS84/ETRS89 → (Ostwert, Nordwert) in Zone [zone]. */
    fun forward(lat: Double, lon: Double, zone: Int): DoubleArray {
        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon - centralMeridian(zone))
        val tau = tan(phi)
        val sigma = kotlin.math.sinh(e * atanh(e * sin(phi)))
        // tan der konformen Breite
        val tp = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
        val xiP = kotlin.math.atan2(tp, cos(lam))
        val etaP = asinh(sin(lam) / sqrt(tp * tp + cos(lam) * cos(lam)))
        var xi = xiP
        var eta = etaP
        for (j in 1..4) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * kotlin.math.cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * kotlin.math.sinh(2 * j * etaP)
        }
        return doubleArrayOf(FALSE_E + K0 * aHat * eta, K0 * aHat * xi)
    }

    /** (Ostwert, Nordwert) in Zone [zone] → (Breite, Laenge) in Grad. */
    fun inverse(east: Double, north: Double, zone: Int): DoubleArray {
        val xi = north / (K0 * aHat)
        val eta = (east - FALSE_E) / (K0 * aHat)
        var xiP = xi
        var etaP = eta
        for (j in 1..4) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * kotlin.math.cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * kotlin.math.sinh(2 * j * eta)
        }
        val tauP = sin(xiP) / sqrt(kotlin.math.sinh(etaP) * kotlin.math.sinh(etaP) + cos(xiP) * cos(xiP))
        val lam = kotlin.math.atan2(kotlin.math.sinh(etaP), cos(xiP))
        // tau aus tau' per Newton (Karney Gl. 19–21)
        var tau = tauP
        repeat(5) {
            val sigma = kotlin.math.sinh(e * atanh(e * tau / sqrt(1 + tau * tau)))
            val tpi = tau * sqrt(1 + sigma * sigma) - sigma * sqrt(1 + tau * tau)
            val d = (tauP - tpi) / sqrt(1 + tpi * tpi) * (1 + (1 - e * e) * tau * tau) /
                ((1 - e * e) * sqrt(1 + tau * tau))
            tau += d
        }
        return doubleArrayOf(Math.toDegrees(atan(tau)), centralMeridian(zone) + Math.toDegrees(lam))
    }

    private fun atanh(x: Double) = 0.5 * kotlin.math.ln((1 + x) / (1 - x))
    private fun asinh(x: Double) = kotlin.math.ln(x + sqrt(x * x + 1))
}

/**
 * Lineare Naeherung ENU ↔ UTM um einen Punkt (`centerE`, `centerN`): an der
 * Mitte exakt, in 200 m Abstand wenige Millimeter daneben (beide Abbildungen
 * sind dort fast affin zueinander; in 3 km Abstand waeren es schon ueber 1 m,
 * deshalb je Block ein eigener Rahmen). Spart je Abfrage die Winkelfunktionen.
 */
internal class EnuUtmFrame(val origin: GeoOrigin, val zone: Int, centerE: Double, centerN: Double) {
    private val e0 = centerE
    private val n0 = centerN
    private val x0: Double
    private val y0: Double
    private val xe: Double
    private val xn: Double
    private val ye: Double
    private val yn: Double
    private val det: Double

    init {
        fun utm(e: Double, n: Double) = Utm.forward(origin.toLat(n), origin.toLon(e), zone)
        val c = utm(e0, n0)
        val pe = utm(e0 + 100, n0)
        val me = utm(e0 - 100, n0)
        val pn = utm(e0, n0 + 100)
        val mn = utm(e0, n0 - 100)
        x0 = c[0]
        y0 = c[1]
        xe = (pe[0] - me[0]) / 200
        ye = (pe[1] - me[1]) / 200
        xn = (pn[0] - mn[0]) / 200
        yn = (pn[1] - mn[1]) / 200
        det = xe * yn - xn * ye
    }

    fun utmX(e: Double, n: Double): Double = x0 + (e - e0) * xe + (n - n0) * xn
    fun utmY(e: Double, n: Double): Double = y0 + (e - e0) * ye + (n - n0) * yn

    fun east(x: Double, y: Double): Double {
        val dx = x - x0
        val dy = y - y0
        return e0 + (dx * yn - dy * xn) / det
    }

    fun north(x: Double, y: Double): Double {
        val dx = x - x0
        val dy = y - y0
        return n0 + (dy * xe - dx * ye) / det
    }
}
