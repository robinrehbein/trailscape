package de.trailscape.tool.indoor

import de.trailscape.core.indoor.sky.DaylightModel
import de.trailscape.core.indoor.sky.IndoorDaylight
import de.trailscape.core.indoor.sky.IndoorTimeOfDay
import de.trailscape.core.indoor.sky.SkyClouds
import de.trailscape.core.indoor.sky.SkyRgb
import de.trailscape.core.indoor.world.IndoorQuality
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * `sky --time AUTO|MORNING|NOON|EVENING [--lat 51.3] [--lon 9.5] [--epoch-ms …] [--quality …] --out <dir>`
 *
 * Schreibt den Himmel der Darstellung fuer den Betrachter
 * (`tool/indoor-dev/viewer`, Parameter `skyDir`):
 *  - `sky.json`: Sonne (Azimut, Hoehe, Farbe, Lux), Umgebungslicht, Dunst, Belichtung;
 *  - `sky.f32`: Rundumbild 1024 × 512, RGB-Float32 little-endian, szenenlinear;
 *  - `preview.png`: dasselbe Bild durch eine Nachbildung von Filaments `ACES_LEGACY`.
 */
internal fun skyExport(
    time: IndoorTimeOfDay,
    latDeg: Double,
    lonDeg: Double,
    epochMs: Long,
    quality: IndoorQuality,
    out: File,
): Int {
    out.mkdirs()
    val setting = IndoorDaylight.skySetting(time, epochMs, latDeg, lonDeg)
    val l = DaylightModel.lighting(setting, latDeg, quality.viewDistanceM)
    val w = 1024
    val h = 512
    val t0 = System.nanoTime()
    val px = SkyClouds.equirectRgb(l.look, w, h)
    val ms = (System.nanoTime() - t0) / 1_000_000
    // Verteilung der rohen Wolkendichte oberhalb von 20° (zum Abstimmen der Bedeckung).
    val raws = ArrayList<Double>()
    for (i in 0 until 40) for (j in 0 until 90) {
        val el = Math.toRadians(20.0 + 70.0 * i / 40.0)
        val az = Math.toRadians(4.0 * j)
        raws += SkyClouds.rawDensity(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az))
    }
    raws.sort()
    val q = listOf(0.1, 0.25, 0.5, 0.75, 0.9).joinToString(" ") { String.format(Locale.ROOT, "%.3f", raws[(it * (raws.size - 1)).toInt()]) }
    println("rohe Wolkendichte, Quantile 10/25/50/75/90 %: $q")
    val buf = ByteBuffer.allocate(px.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in px) buf.putFloat(v)
    File(out, "sky.f32").writeBytes(buf.array())

    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) for (x in 0 until w) {
        val i = (y * w + x) * 3
        val c = FilamentAces.display(SkyRgb(px[i].toDouble(), px[i + 1].toDouble(), px[i + 2].toDouble()))
        img.setRGB(x, y, (c[0] shl 16) or (c[1] shl 8) or c[2])
    }
    ImageIO.write(img, "png", File(out, "preview.png"))

    fun rgb(c: SkyRgb) = String.format(Locale.ROOT, "[%.5f, %.5f, %.5f]", c.r, c.g, c.b)
    val json = buildString {
        appendLine("{")
        appendLine("  \"time\": \"${time.name}\",")
        appendLine(String.format(Locale.ROOT, "  \"hourOfDay\": %.4f, \"dayOfYear\": %d, \"haze\": %.3f,", setting.hourOfDay, setting.dayOfYear, setting.haze))
        appendLine(String.format(Locale.ROOT, "  \"sunAzimuthDeg\": %.3f, \"sunElevationDeg\": %.3f,", l.sunAzimuthDeg, l.sunAltitudeDeg))
        appendLine("  \"sunColor\": ${rgb(l.sunColor)},")
        appendLine(String.format(Locale.ROOT, "  \"sunLux\": %.1f, \"ambientLux\": %.1f, \"exposure\": %.8g,", l.sunLux, l.ambientLux, de.trailscape.core.indoor.sky.SkyLookModel.EXPOSURE))
        appendLine("  \"iblZenith\": ${rgb(l.iblZenith)}, \"iblHorizon\": ${rgb(l.iblHorizon)}, \"iblGround\": ${rgb(l.iblGround)},")
        appendLine("  \"skyZenith\": ${rgb(l.look.zenith)}, \"skyHorizon\": ${rgb(l.look.horizon)},")
        appendLine(String.format(Locale.ROOT, "  \"fogDensity\": %.8g,", l.fogDensity))
        appendLine("  \"skyWidth\": $w, \"skyHeight\": $h")
        appendLine("}")
    }
    File(out, "sky.json").writeText(json)
    println(
        String.format(
            Locale.ROOT,
            "sky %s: Sonnenzeit %.2f h, Tag %d, Sonne %.1f° hoch, Azimut %.0f°, Dunst %.5f/m, Rundumbild %d ms → %s",
            time, setting.hourOfDay, setting.dayOfYear, l.sunAltitudeDeg, l.sunAzimuthDeg, l.fogDensity, ms, out,
        ),
    )
    return 0
}

/**
 * Filaments Standard-Tonwertabbildung `ACES_LEGACY` (ToneMapper.cpp, v1.75.1:
 * ACES-RRT/ODT-Naeherung mit Helligkeit 1/0,6), fuer Vorschaubilder auf dem
 * Entwicklerrechner. Eingabe linear sRGB, Ausgabe sRGB-Bytes.
 */
internal object FilamentAces {
    private fun m(vararg v: Double) = v // spaltenweise wie mat3f in Filament
    private val sRGB_to_AP0 = m(0.4397010, 0.0897923, 0.0175440, 0.3829780, 0.8134230, 0.1115440, 0.1773350, 0.0967616, 0.8707040)
    private val AP0_to_AP1 = m(1.4514393161, -0.0765537734, 0.0083161484, -0.2365107469, 1.1762296998, -0.0060324498, -0.2149285693, -0.0996759264, 0.9977163014)
    private val AP1_to_sRGB = m(1.70505, -0.13026, -0.02400, -0.62179, 1.14080, -0.12897, -0.08326, -0.01055, 1.15297)
    private val AP1_to_XYZ = m(0.6624541811, 0.2722287168, -0.0055746495, 0.1340042065, 0.6740817658, 0.0040607335, 0.1561876870, 0.0536895174, 1.0103391003)
    private val XYZ_to_AP1 = m(1.6410233797, -0.6636628587, 0.0117218943, -0.3248032942, 1.6153315917, -0.0082844420, -0.2364246952, 0.0167563477, 0.9883948585)
    private val LUM = doubleArrayOf(0.272229, 0.674082, 0.0536895)

    private fun mul(mat: DoubleArray, v: DoubleArray) = DoubleArray(3) { r -> mat[r] * v[0] + mat[3 + r] * v[1] + mat[6 + r] * v[2] }
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun tonemap(c: SkyRgb): DoubleArray {
        var ap0 = mul(sRGB_to_AP0, doubleArrayOf(c.r, c.g, c.b))
        val mi = min(ap0[0], min(ap0[1], ap0[2]))
        val ma = max(ap0[0], max(ap0[1], ap0[2]))
        val sat = (max(ma, 1e-5) - max(mi, 1e-5)) / max(ma, 1e-2)
        val chroma = sqrt(max(0.0, ap0[2] * (ap0[2] - ap0[1]) + ap0[1] * (ap0[1] - ap0[0]) + ap0[0] * (ap0[0] - ap0[2])))
        val yc = (ap0[0] + ap0[1] + ap0[2] + 1.75 * chroma) / 3.0
        val x = (sat - 0.4) / 0.2
        val t = max(1.0 - abs(x / 2.0), 0.0)
        val s = (1.0 + Math.signum(x) * (1.0 - t * t)) / 2.0
        val gain = 0.05 * s
        val glow = when {
            yc <= 2.0 / 3.0 * 0.08 -> gain
            yc >= 0.16 -> 0.0
            else -> gain * (0.08 / yc - 0.5)
        }
        ap0 = DoubleArray(3) { ap0[it] * (1 + glow) }
        var hue = if (ap0[0] == ap0[1] && ap0[1] == ap0[2]) 0.0 else
            Math.toDegrees(atan2(sqrt(3.0) * (ap0[1] - ap0[2]), 2 * ap0[0] - ap0[1] - ap0[2]))
        if (hue < 0) hue += 360.0
        if (hue > 180) hue -= 360.0
        val hw0 = (1.0 - abs(2.0 * hue / 135.0)).coerceIn(0.0, 1.0)
        val hw = (hw0 * hw0 * (3 - 2 * hw0)).let { it * it }
        ap0[0] += hw * sat * (0.03 - ap0[0]) * (1.0 - 0.82)
        var ap1 = mul(AP0_to_AP1, ap0).map { it.coerceIn(0.0, 65504.0) }.toDoubleArray()
        var y = dot(ap1, LUM)
        ap1 = DoubleArray(3) { (y + (ap1[it] - y) * 0.96) / 0.6 }
        val post = DoubleArray(3) { val v = ap1[it]; (v * (2.785085 * v + 0.107772)) / (v * (2.936045 * v + 0.887122) + 0.806889) }
        var xyz = mul(AP1_to_XYZ, post)
        val sum = xyz[0] + xyz[1] + xyz[2]
        if (sum > 0 && xyz[1] > 0) {
            val cx = xyz[0] / sum
            val cy = xyz[1] / sum
            val yy = xyz[1].pow(0.9811)
            xyz = doubleArrayOf(cx * yy / cy, yy, (1 - cx - cy) * yy / cy)
        }
        var lin = mul(XYZ_to_AP1, xyz)
        y = dot(lin, LUM)
        lin = DoubleArray(3) { y + (lin[it] - y) * 0.93 }
        return mul(AP1_to_sRGB, lin)
    }

    fun display(c: SkyRgb): IntArray = tonemap(c).map { v ->
        val l = v.coerceIn(0.0, 1.0)
        val s = if (l <= 0.0031308) 12.92 * l else 1.055 * l.pow(1 / 2.4) - 0.055
        (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }.toIntArray()
}
