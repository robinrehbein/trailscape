package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Eine lineare RGB-Farbe (nicht sRGB). */
data class Rgb(val r: Double, val g: Double, val b: Double) {
    operator fun times(s: Double) = Rgb(r * s, g * s, b * s)
    operator fun plus(o: Rgb) = Rgb(r + o.r, g + o.g, b + o.b)
    fun lerp(o: Rgb, t: Double) = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
    fun toFloatArray() = floatArrayOf(r.toFloat(), g.toFloat(), b.toFloat())
}

/**
 * Himmel, Sonne, Umgebungslicht und Dunst fuer einen [SkySetting] — reine
 * Rechnung, die Darstellung reicht die Zahlen an Filament weiter.
 *
 * @property sunDirection Richtung **zur** Sonne im glTF-Raum (Einheitsvektor).
 * @property sunAltitudeDeg wirksame Sonnenhoehe (mindestens [MIN_SUN_ALTITUDE_DEG]).
 * @property sunColor Farbe des Sonnenlichts (linear, hellster Kanal 1).
 * @property sunLux Beleuchtungsstaerke der Sonne in Lux.
 * @property zenith Himmelsfarbe oben (linear, relativ zu [ambientLux]).
 * @property horizon Himmelsfarbe am Horizont — zugleich die Dunstfarbe.
 * @property ground Farbe des Bodens fuer das Umgebungslicht von unten.
 * @property ambientLux Staerke des Umgebungslichts (`IndirectLight.intensity`);
 *   Himmel (Skybox) und Dunst werden in Filament mit derselben Staerke
 *   multipliziert, deshalb passen Horizont und Dunst zusammen.
 * @property fogDensity Ausloeschung des Dunsts in 1/m.
 */
data class SkyLighting(
    val sunDirection: V3,
    val sunAltitudeDeg: Double,
    val sunColor: Rgb,
    val sunLux: Double,
    val zenith: Rgb,
    val horizon: Rgb,
    val ground: Rgb,
    val ambientLux: Double,
    val fogDensity: Double,
)

object SkyModel {
    /** Unter dieser Sonnenhoehe bleibt das Licht auf dem Stand der tiefen Sonne (Vertrag `SkySetting`). */
    const val MIN_SUN_ALTITUDE_DEG = 8.0

    /** Belichtung der Kamera (Sonnige-16-Regel): Blende 16, 1/125 s, ISO 100. */
    const val APERTURE = 16f
    const val SHUTTER_S = 1f / 125f
    const val ISO = 100f

    /**
     * Richtung zur Sonne in ENU (`e`, `n`, `u`) fuer Ortszeit [hourOfDay]
     * (wahre Sonnenzeit, Naeherung), Tag [dayOfYear] und Breite [latDeg].
     */
    fun sunEnu(hourOfDay: Double, dayOfYear: Int, latDeg: Double): Triple<Double, Double, Double> {
        val decl = Math.toRadians(-23.44 * cos(2 * PI / 365.0 * (dayOfYear.coerceIn(1, 366) + 10)))
        val hourAngle = Math.toRadians(15.0 * (hourOfDay.coerceIn(0.0, 24.0) - 12.0))
        val lat = Math.toRadians(latDeg.coerceIn(-89.9, 89.9))
        val east = -cos(decl) * sin(hourAngle)
        val north = sin(decl) * cos(lat) - cos(decl) * cos(hourAngle) * sin(lat)
        val up = sin(decl) * sin(lat) + cos(decl) * cos(hourAngle) * cos(lat)
        return Triple(east, north, up)
    }

    fun lighting(sky: SkySetting, latDeg: Double): SkyLighting {
        val (e, n, u) = sunEnu(sky.hourOfDay, sky.dayOfYear, latDeg)
        val rawAlt = Math.toDegrees(asin(u.coerceIn(-1.0, 1.0)))
        val alt = max(rawAlt, MIN_SUN_ALTITUDE_DEG)
        // Waagrechte Richtung behalten, Hoehe auf mindestens 8° anheben.
        val horiz = sqrt(e * e + n * n).takeIf { it > 1e-9 }
        val (he, hn) = if (horiz != null) (e / horiz) to (n / horiz) else 0.0 to -1.0
        val altR = Math.toRadians(alt)
        val dir = enuToGltf(he * cos(altR), hn * cos(altR), sin(altR)).normalized()

        // 0 bei tiefer Sonne, 1 ab etwa 35° Hoehe.
        val high = ((alt - MIN_SUN_ALTITUDE_DEG) / 27.0).coerceIn(0.0, 1.0)
        val haze = sky.haze.coerceIn(0.0, 1.0)
        val sunColor = Rgb(1.0, 0.80, 0.58).lerp(Rgb(1.0, 0.97, 0.92), high)
        val sunLux = (70_000.0 + 40_000.0 * high) * (1.0 - 0.35 * haze)

        val clearZenith = Rgb(0.16, 0.34, 0.78).lerp(Rgb(0.10, 0.28, 0.75), high)
        val hazyZenith = Rgb(0.45, 0.55, 0.70)
        val zenith = clearZenith.lerp(hazyZenith, haze * 0.7)
        val clearHorizon = Rgb(0.85, 0.72, 0.62).lerp(Rgb(0.62, 0.74, 0.90), high)
        val horizon = clearHorizon.lerp(Rgb(0.78, 0.80, 0.82), haze * 0.6)
        val ground = Rgb(0.20, 0.22, 0.14)

        // Sichtweite 40 km (klar) .. 4 km (dicht); Koschmieder: Dichte ≈ 3,9 / Sichtweite.
        val visibilityM = 40_000.0 * (1.0 - haze) + 4_000.0 * haze
        return SkyLighting(
            sunDirection = dir,
            sunAltitudeDeg = alt,
            sunColor = sunColor,
            sunLux = sunLux,
            zenith = zenith,
            horizon = horizon,
            ground = ground,
            ambientLux = 22_000.0 + 10_000.0 * high,
            fogDensity = 3.9 / visibilityM,
        )
    }

    /**
     * Strahldichte des Himmelsmodells in Richtung [d] (glTF, Einheitsvektor):
     * Verlauf Horizont → Zenit, darunter der Boden. Fuer das Umgebungslicht.
     */
    fun radiance(l: SkyLighting, d: V3): Rgb = if (d.y >= 0.0) {
        l.horizon.lerp(l.zenith, d.y.pow(0.5))
    } else {
        l.horizon.lerp(l.ground, (-d.y * 4.0).coerceIn(0.0, 1.0))
    }

    /**
     * Farbe der Himmels-Cubemap in Richtung [d]. Bewusst **symmetrisch** zur
     * Waagrechten (haengt nur von `|y|` ab): Unter dem Horizont liegt ohnehin
     * Gelaende, und so ist das Bild unabhaengig davon richtig, ob ein Treiber
     * die Seitenflaechen der Cubemap senkrecht spiegelt. Die Sonnenscheibe
     * zeichnet Filament selbst (`Skybox.showSun`).
     */
    fun skyboxColor(l: SkyLighting, d: V3): Rgb = l.horizon.lerp(l.zenith, abs(d.y).pow(0.5))

    /**
     * Die neun SH-Koeffizienten (drei Baender, je RGB) der Strahldichte fuer
     * `IndirectLight.Builder.radiance(3, sh)`.
     *
     * Basis genau wie Filament sie erwartet (`IndirectLight.cpp`, Faktoren `A`
     * ohne den Kosinus-Anteil): `Y0 = 0,282095`, `Y1 = −0,488603·y`,
     * `Y2 = 0,488603·z`, `Y3 = −0,488603·x`, `Y4 = 1,092548·y·x`,
     * `Y5 = −1,092548·y·z`, `Y6 = 0,315392·(3z² − 1)`, `Y7 = −1,092548·z·x`,
     * `Y8 = 0,546274·(x² − y²)`. Numerische Integration ueber die Kugel.
     */
    fun radianceSh(l: SkyLighting, steps: Int = 48): FloatArray {
        val sh = DoubleArray(27)
        val dTheta = PI / steps
        val dPhi = 2 * PI / (2 * steps)
        for (i in 0 until steps) {
            val theta = (i + 0.5) * dTheta
            val st = sin(theta)
            val ct = cos(theta)
            for (j in 0 until 2 * steps) {
                val phi = (j + 0.5) * dPhi
                val d = V3(st * cos(phi), ct, st * sin(phi))
                val c = radiance(l, d)
                val w = st * dTheta * dPhi
                val basis = shBasis(d)
                for (k in 0 until 9) {
                    sh[k * 3] += c.r * basis[k] * w
                    sh[k * 3 + 1] += c.g * basis[k] * w
                    sh[k * 3 + 2] += c.b * basis[k] * w
                }
            }
        }
        return FloatArray(27) { sh[it].toFloat() }
    }

    /** Filaments SH-Basis (siehe [radianceSh]). */
    fun shBasis(d: V3): DoubleArray {
        val x = d.x
        val y = d.y
        val z = d.z
        return doubleArrayOf(
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

    /**
     * Was Filament aus [sh] fuer die Normale [n] als diffuse Beleuchtung
     * rechnet (bis auf `iblLuminance`): `Σ sh·A·poly(n)` — zum Pruefen.
     */
    fun irradianceAt(sh: FloatArray, n: V3): Rgb {
        val c = doubleArrayOf(PI, 2.0943951, 0.785398)
        val sqrtPi = sqrt(PI)
        val a = doubleArrayOf(
            1.0 / (2 * sqrtPi) * c[0] / PI,
            -sqrt(3.0) / (2 * sqrtPi) * c[1] / PI,
            sqrt(3.0) / (2 * sqrtPi) * c[1] / PI,
            -sqrt(3.0) / (2 * sqrtPi) * c[1] / PI,
            sqrt(15.0) / (2 * sqrtPi) * c[2] / PI,
            -sqrt(15.0) / (2 * sqrtPi) * c[2] / PI,
            sqrt(5.0) / (4 * sqrtPi) * c[2] / PI,
            -sqrt(15.0) / (2 * sqrtPi) * c[2] / PI,
            sqrt(15.0) / (4 * sqrtPi) * c[2] / PI,
        )
        val poly = doubleArrayOf(
            1.0, n.y, n.z, n.x, n.y * n.x, n.y * n.z, 3 * n.z * n.z - 1, n.z * n.x, n.x * n.x - n.y * n.y,
        )
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (k in 0 until 9) {
            val f = a[k] * poly[k]
            r += sh[k * 3] * f
            g += sh[k * 3 + 1] * f
            b += sh[k * 3 + 2] * f
        }
        return Rgb(max(r, 0.0), max(g, 0.0), max(b, 0.0))
    }

    /**
     * Die sechs Flaechen der Himmels-Cubemap als RGBA8 in **sRGB**, eine nach
     * der anderen in der OpenGL-Reihenfolge +X, −X, +Y, −Y, +Z, −Z, je
     * [size]² Texel, Zeile fuer Zeile. Richtungen nach der OpenGL-Tabelle
     * (Spezifikation, Abschnitt „Cube Map Texture Selection“).
     */
    fun cubemapPixels(l: SkyLighting, size: Int): ByteArray {
        val out = ByteArray(6 * size * size * 4)
        var o = 0
        for (face in 0 until 6) {
            for (row in 0 until size) {
                val tc = 2.0 * (row + 0.5) / size - 1.0
                for (col in 0 until size) {
                    val sc = 2.0 * (col + 0.5) / size - 1.0
                    val d = cubeDirection(face, sc, tc).normalized()
                    val c = skyboxColor(l, d)
                    out[o++] = toSrgbByte(c.r)
                    out[o++] = toSrgbByte(c.g)
                    out[o++] = toSrgbByte(c.b)
                    out[o++] = 0xFF.toByte()
                }
            }
        }
        return out
    }

    /** OpenGL-Cubemap: Richtung fuer Flaeche [face] und Flaechenkoordinaten `sc`, `tc` in −1..1. */
    fun cubeDirection(face: Int, sc: Double, tc: Double): V3 = when (face) {
        0 -> V3(1.0, -tc, -sc)
        1 -> V3(-1.0, -tc, sc)
        2 -> V3(sc, 1.0, tc)
        3 -> V3(sc, -1.0, -tc)
        4 -> V3(sc, -tc, 1.0)
        else -> V3(-sc, -tc, -1.0)
    }

    fun toSrgbByte(linear: Double): Byte {
        val c = linear.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
    }
}
