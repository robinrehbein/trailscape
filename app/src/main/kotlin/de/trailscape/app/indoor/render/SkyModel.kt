package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.sky.DaylightModel
import de.trailscape.core.indoor.sky.IndoorDaylight
import de.trailscape.core.indoor.sky.SkyClouds
import de.trailscape.core.indoor.sky.SkyLook
import de.trailscape.core.indoor.sky.SkyRgb
import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.PI
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
 * Rechnung, die Darstellung reicht die Zahlen an Filament weiter. Die Werte
 * kommen aus `core/…/indoor/sky` ([DaylightModel]); hier stehen nur die
 * Richtung im glTF-Raum, die SH-Koeffizienten und die Cubemap.
 *
 * @property sunDirection Richtung **zur** Sonne im glTF-Raum (Einheitsvektor).
 * @property sunAltitudeDeg wirksame Sonnenhoehe (mindestens [MIN_SUN_ALTITUDE_DEG]).
 * @property sunColor Farbe des Sonnenlichts (linear, hellster Kanal 1).
 * @property sunLux Beleuchtungsstaerke der Sonne in Lux.
 * @property zenith Verlauf des **Umgebungslichts** oben (relativ zu [ambientLux]).
 * @property horizon Verlauf des Umgebungslichts am Horizont.
 * @property ground Farbe des Bodens fuer das Umgebungslicht von unten.
 * @property ambientLux Staerke des Umgebungslichts (`IndirectLight.intensity`);
 *   Himmel (Skybox) und Dunst werden in Filament mit `ambientLux × Belichtung`
 *   ([skyScale]) multipliziert.
 * @property fogDensity Ausloeschung des Dunsts in 1/m.
 * @property look der sichtbare Himmel (szenenlinear, vor der Tonwertabbildung).
 * @property skyScale `ambientLux × Belichtung`.
 * @property fogColor Dunstfarbe fuer `fogOptions.color` (sichtbarer Horizont / [skyScale]).
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
    val look: SkyLook,
    val skyScale: Double,
    val fogColor: Rgb,
)

object SkyModel {
    /** Unter dieser Sonnenhoehe bleibt das Licht auf dem Stand der tiefen Sonne (Vertrag `SkySetting`). */
    const val MIN_SUN_ALTITUDE_DEG = IndoorDaylight.DUSK_FLOOR_DEG

    /** Belichtung der Kamera (Sonnige-16-Regel): Blende 16, 1/125 s, ISO 100. */
    const val APERTURE = 16f
    const val SHUTTER_S = 1f / 125f
    const val ISO = 100f

    /**
     * Richtung zur Sonne in ENU (`e`, `n`, `u`) fuer Ortszeit [hourOfDay]
     * (wahre Sonnenzeit, Naeherung), Tag [dayOfYear] und Breite [latDeg].
     */
    fun sunEnu(hourOfDay: Double, dayOfYear: Int, latDeg: Double): Triple<Double, Double, Double> =
        IndoorDaylight.sunEnu(hourOfDay, dayOfYear, latDeg)

    /**
     * Licht und Himmel fuer [sky] an der Breite [latDeg]; [viewDistanceM] ist
     * die Sichtweite der Welt (Dunst reicht bis an ihren Rand).
     */
    fun lighting(sky: SkySetting, latDeg: Double, viewDistanceM: Double? = null): SkyLighting {
        val d = DaylightModel.lighting(sky, latDeg, viewDistanceM)
        return SkyLighting(
            sunDirection = enuToGltf(d.sunEast, d.sunNorth, d.sunUp).normalized(),
            sunAltitudeDeg = d.sunAltitudeDeg,
            sunColor = d.sunColor.toRgb(),
            sunLux = d.sunLux,
            zenith = d.iblZenith.toRgb(),
            horizon = d.iblHorizon.toRgb(),
            ground = d.iblGround.toRgb(),
            ambientLux = d.ambientLux,
            fogDensity = d.fogDensity,
            look = d.look,
            skyScale = d.skyScale,
            fogColor = d.fogColor.toRgb(),
        )
    }

    private fun SkyRgb.toRgb() = Rgb(r, g, b)

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
     * Die Himmels-Cubemap (Verlauf und weiche Wolken, siehe `SkyClouds`) als
     * RGB-Halbfloats fuer eine `R11F_G11F_B10F`-Textur: sechs Flaechen +X, −X,
     * +Y, −Y, +Z, −Z, je [size]² Texel. Durch [SkyLighting.skyScale] geteilt,
     * weil Filament die Skybox damit multipliziert. HDR, damit der helle
     * Horizont und die Wolken nicht bei 1 abgeschnitten werden.
     */
    fun cubemapHalfRgb(l: SkyLighting, size: Int): ShortArray = SkyClouds.cubemapHalfRgb(l.look, size, l.skyScale)

    /**
     * Rueckfall ohne Gleitkomma-Textur: dieselbe Cubemap als RGBA8 in sRGB
     * (Werte ueber 1 abgeschnitten).
     */
    fun cubemapSrgbBytes(half: ShortArray): ByteArray {
        val texels = half.size / 3
        val out = ByteArray(texels * 4)
        for (i in 0 until texels) {
            out[i * 4] = toSrgbByte(halfToDouble(half[i * 3]))
            out[i * 4 + 1] = toSrgbByte(halfToDouble(half[i * 3 + 1]))
            out[i * 4 + 2] = toSrgbByte(halfToDouble(half[i * 3 + 2]))
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    /** IEEE 754 binary16 → Double (ohne Subnormale, wie `SkyClouds.toHalf` schreibt). */
    fun halfToDouble(h: Short): Double {
        val v = h.toInt() and 0xFFFF
        val e = (v shr 10) and 0x1F
        if (e == 0) return 0.0
        val m = v and 0x3FF
        val sign = if (v and 0x8000 != 0) -1.0 else 1.0
        return sign * (1.0 + m / 1024.0) * 2.0.pow(e - 15)
    }

    /** OpenGL-Cubemap: Richtung fuer Flaeche [face] und Flaechenkoordinaten `sc`, `tc` in −1..1. */
    fun cubeDirection(face: Int, sc: Double, tc: Double): V3 =
        SkyClouds.cubeDirection(face, sc, tc).let { V3(it[0], it[1], it[2]) }

    fun toSrgbByte(linear: Double): Byte {
        val c = linear.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
    }
}
