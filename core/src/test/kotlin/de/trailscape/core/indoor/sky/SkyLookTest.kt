package de.trailscape.core.indoor.sky

import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sichtbarer Himmel, Licht je Tageszeit, Wolken und Cubemap. */
class SkyLookTest {
    private val sun = SkyRgb(1.0, 0.97, 0.92)

    private fun saturation(c: SkyRgb): Double {
        val mx = maxOf(c.r, c.g, c.b)
        val mn = minOf(c.r, c.g, c.b)
        return (mx - mn) / mx
    }

    @Test
    fun `Zenit kraeftig blau, Horizont heller`() {
        for (alt in listOf(8.0, 20.0, 45.0, 70.0)) {
            val l = SkyLookModel.look(alt, sun, 0.3)
            assertTrue(l.zenith.b > 2.5 * l.zenith.r, "Zenit blau bei $alt°: ${l.zenith}")
            assertTrue(l.horizon.luminance > 2.5 * l.zenith.luminance, "Horizont hell bei $alt°")
        }
    }

    @Test
    fun `tiefe Sonne macht den Horizont warm, hohe blau`() {
        val low = SkyLookModel.look(8.0, SkyRgb(1.0, 0.76, 0.52), 0.0)
        val high = SkyLookModel.look(50.0, sun, 0.0)
        assertTrue(low.horizon.r > low.horizon.b, "abends warm: ${low.horizon}")
        assertTrue(high.horizon.b > high.horizon.r, "mittags blau: ${high.horizon}")
        assertTrue(low.cloudLit.r / low.cloudLit.b > high.cloudLit.r / high.cloudLit.b, "Abendwolken rotgolden")
    }

    @Test
    fun `Dunst nimmt Farbe weg`() {
        val clear = SkyLookModel.look(40.0, sun, 0.0)
        val hazy = SkyLookModel.look(40.0, sun, 1.0)
        assertTrue(saturation(hazy.zenith) < saturation(clear.zenith))
        assertTrue(saturation(hazy.horizon) < saturation(clear.horizon))
        assertTrue(hazy.cloudCover > clear.cloudCover)
    }

    @Test
    fun `Verlauf beginnt am Horizont und wird stetig blauer`() {
        val l = SkyLookModel.look(30.0, sun, 0.3)
        assertEquals(l.horizon, SkyLookModel.gradient(l, 0.0))
        var last = SkyLookModel.gradient(l, 0.0)
        for (i in 1..20) {
            val c = SkyLookModel.gradient(l, i / 20.0)
            assertTrue(c.b / c.r >= last.b / last.r - 1e-12)
            last = c
        }
        // Bei 25° Hoehe (oberer Bildrand im Hochformat) schon ueberwiegend Zenitfarbe.
        val at25 = SkyLookModel.gradient(l, 0.42)
        assertTrue(abs(at25.r - l.zenith.r) < 0.15 * abs(l.horizon.r - l.zenith.r))
    }

    @Test
    fun `Licht je Tageszeit`() {
        val evening = DaylightModel.lighting(IndoorDaylight.skySetting(IndoorTimeOfDay.EVENING, 0, 51.3, 9.5), 51.3)
        val noon = DaylightModel.lighting(IndoorDaylight.skySetting(IndoorTimeOfDay.NOON, 0, 51.3, 9.5), 51.3)
        assertTrue(evening.sunColor.b < noon.sunColor.b, "Abendlicht waermer")
        assertTrue(evening.sunLux < noon.sunLux)
        assertTrue(evening.ambientLux >= 26_000.0)
        assertEquals(1.0, sqrt(evening.sunEast * evening.sunEast + evening.sunNorth * evening.sunNorth + evening.sunUp * evening.sunUp), 1e-9)
        assertTrue(evening.sunAzimuthDeg in 270.0..330.0, "Abendsonne im Nordwesten: ${evening.sunAzimuthDeg}")
        // Nachts: Untergrenze 8°.
        val night = DaylightModel.lighting(SkySetting(hourOfDay = 0.5, dayOfYear = 10), 51.3)
        assertEquals(IndoorDaylight.DUSK_FLOOR_DEG, night.sunAltitudeDeg, 1e-9)
    }

    @Test
    fun `Dunst reicht bis an den Rand der Welt`() {
        val s = SkySetting(13.0, 165, 0.0)
        val clear = DaylightModel.lighting(s, 51.0)
        assertEquals(3.9 / 30_000.0, clear.fogDensity, 1e-12)
        val medium = DaylightModel.lighting(s, 51.0, IndoorQuality.MEDIUM.viewDistanceM)
        // Am Rand der Sichtweite mindestens 90 % Dunst.
        assertTrue(1.0 - Math.exp(-medium.fogDensity * IndoorQuality.MEDIUM.viewDistanceM) >= 0.899)
        val hazy = DaylightModel.lighting(SkySetting(13.0, 165, 1.0), 51.0)
        assertTrue(hazy.fogDensity > 5 * clear.fogDensity)
    }

    @Test
    fun `Dunstfarbe ist der sichtbare Horizont`() {
        val l = DaylightModel.lighting(SkySetting.DEFAULT, 51.0)
        val f = l.fogColor * l.skyScale
        assertEquals(l.look.horizon.r, f.r, 1e-12)
        assertEquals(l.look.horizon.b, f.b, 1e-12)
        assertTrue(l.skyScale in 0.5..1.0, "${l.skyScale}")
    }

    @Test
    fun halbfloats() {
        assertEquals(0x3C00, SkyClouds.toHalf(1.0).toInt() and 0xFFFF)
        assertEquals(0x3800, SkyClouds.toHalf(0.5).toInt() and 0xFFFF)
        assertEquals(0x4000, SkyClouds.toHalf(2.0).toInt() and 0xFFFF)
        assertEquals(0x2E66, SkyClouds.toHalf(0.1).toInt() and 0xFFFF)
        assertEquals(0x7BFF, SkyClouds.toHalf(1e9).toInt() and 0xFFFF)
        assertEquals(0, SkyClouds.toHalf(-3.0).toInt())
        assertEquals(0, SkyClouds.toHalf(Double.NaN).toInt())
    }

    @Test
    fun `Wolken spiegelgleich zur Waagrechten und frei am Horizont`() {
        val size = 32
        val raw = SkyClouds.rawCube(size)
        val face = size * size
        for (row in 0 until size) for (col in 0 until size) {
            // −Y ist +Y mit gespiegelten Zeilen.
            assertEquals(raw[2 * face + row * size + col], raw[3 * face + (size - 1 - row) * size + col])
            // Seitenflaechen: untere Haelfte = obere gespiegelt.
            for (f in intArrayOf(0, 1, 4, 5)) {
                assertEquals(raw[f * face + row * size + col], raw[f * face + (size - 1 - row) * size + col])
            }
        }
        // Gespiegelt gleich direkt gerechnet.
        assertEquals(SkyClouds.rawDensity(0.3, 0.4, -0.2), SkyClouds.rawDensity(0.3, -0.4, -0.2), 1e-12)
        // Am Horizont keine Wolken.
        assertEquals(0.0, SkyClouds.rawDensity(1.0, 0.02, 0.3))
    }

    @Test
    fun `Bedeckung im erwarteten Bereich`() {
        var cloudy = 0
        var total = 0
        val cover = SkyLookModel.look(30.0, sun, 0.3).cloudCover
        for (i in 0 until 60) for (j in 0 until 120) {
            val el = Math.toRadians(20.0 + 70.0 * i / 60.0)
            val az = Math.toRadians(3.0 * j)
            val d = SkyClouds.density(SkyClouds.rawDensity(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)), cover)
            if (d > 0.05) cloudy++
            total++
        }
        val share = cloudy.toDouble() / total
        assertTrue(share in 0.12..0.55, "Wolkenanteil $share")
    }

    @Test
    fun `Cubemap Groesse und Horizontfarbe`() {
        val l = DaylightModel.lighting(SkySetting.DEFAULT, 51.0)
        val size = 16
        val px = SkyClouds.cubemapHalfRgb(l.look, size, l.skyScale)
        assertEquals(6 * size * size * 3, px.size)
        // Seitenflaeche +X, Zeile in der Mitte: Horizont (keine Wolken), geteilt durch die Skala.
        fun half(s: Short): Double {
            val h = s.toInt() and 0xFFFF
            val e = (h shr 10) and 0x1F
            val m = h and 0x3FF
            return (1.0 + m / 1024.0) * Math.pow(2.0, (e - 15).toDouble())
        }
        val i = ((size / 2) * size + size / 2) * 3
        val expected = SkyLookModel.gradient(l.look, 1.0 / size) * (1.0 / l.skyScale)
        assertEquals(expected.b, half(px[i + 2]), 0.02 * expected.b)
    }

    @Test
    fun `Rundumbild fuer den Betrachter`() {
        val l = DaylightModel.lighting(SkySetting.DEFAULT, 51.0)
        val px = SkyClouds.equirectRgb(l.look, 32, 16)
        assertEquals(32 * 16 * 3, px.size)
        assertTrue(px.all { it.isFinite() && it >= 0f })
    }
}
