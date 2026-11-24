package de.trailscape.core.indoor.sky

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Umgebungslicht als Kugelflaechenfunktionen (drei Baender) — die Zahlen fuer
 * Filaments `IndirectLight.Builder.radiance(3, sh)`.
 *
 * Lag frueher in `:app` (`SkyModel`); hier, damit das Werkzeug
 * (`indoor-dev sky`) und der Filament-Referenzrenderer
 * (`tool/indoor-dev/filament`) genau dieselben Koeffizienten bekommen wie das
 * Geraet. Richtungen im glTF-Raum (x Ost, y oben, z Sued).
 */
object SkyIrradiance {
    /**
     * Strahldichte in Richtung (`x`, `y`, `z`) (Einheitsvektor): Verlauf
     * Horizont → Zenit, darunter der Boden.
     */
    fun radiance(zenith: SkyRgb, horizon: SkyRgb, ground: SkyRgb, y: Double): SkyRgb = if (y >= 0.0) {
        horizon.lerp(zenith, y.pow(0.5))
    } else {
        horizon.lerp(ground, (-y * 4.0).coerceIn(0.0, 1.0))
    }

    /**
     * Die neun SH-Koeffizienten (je RGB, 27 Werte). Basis genau wie Filament
     * sie erwartet (`IndirectLight.cpp`, Faktoren `A` ohne den Kosinus-Anteil):
     * `Y0 = 0,282095`, `Y1 = −0,488603·y`, `Y2 = 0,488603·z`, `Y3 = −0,488603·x`,
     * `Y4 = 1,092548·y·x`, `Y5 = −1,092548·y·z`, `Y6 = 0,315392·(3z² − 1)`,
     * `Y7 = −1,092548·z·x`, `Y8 = 0,546274·(x² − y²)`. Numerische Integration.
     */
    fun sh(zenith: SkyRgb, horizon: SkyRgb, ground: SkyRgb, steps: Int = 48): FloatArray {
        val sh = DoubleArray(27)
        val dTheta = PI / steps
        val dPhi = 2 * PI / (2 * steps)
        for (i in 0 until steps) {
            val theta = (i + 0.5) * dTheta
            val st = sin(theta)
            val ct = cos(theta)
            for (j in 0 until 2 * steps) {
                val phi = (j + 0.5) * dPhi
                val x = st * cos(phi)
                val y = ct
                val z = st * sin(phi)
                val c = radiance(zenith, horizon, ground, y)
                val w = st * dTheta * dPhi
                val basis = basis(x, y, z)
                for (k in 0 until 9) {
                    sh[k * 3] += c.r * basis[k] * w
                    sh[k * 3 + 1] += c.g * basis[k] * w
                    sh[k * 3 + 2] += c.b * basis[k] * w
                }
            }
        }
        return FloatArray(27) { sh[it].toFloat() }
    }

    /** Filaments SH-Basis (siehe [sh]). */
    fun basis(x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
        0.282095,
        -0.488603 * y,
        0.488603 * z,
        -0.488603 * x,
        1.092548 * y * x,
        -1.092548 * y * z,
        0.315392 * (3 * z * z - 1),
        -1.092548 * z * x,
        0.546274 * (x * x - y * y),
    )
}
