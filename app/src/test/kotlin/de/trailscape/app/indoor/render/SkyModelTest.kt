package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.SkySetting
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sonnenstand, Umgebungslicht (SH nach Filaments Basis), Himmels-Cubemap, Dunst. */
class SkyModelTest {
    @Test
    fun mittagsImSuedenUndHoch() {
        val (e, n, u) = SkyModel.sunEnu(12.0, 172, 51.0) // Sommeranfang, Mitteldeutschland
        assertTrue(abs(e) < 1e-6, "Mittag: genau im Sueden ($e)")
        assertTrue(n < 0.0, "Sueden heisst n < 0")
        val alt = Math.toDegrees(asin(u))
        assertTrue(abs(alt - (90 - 51 + 23.44)) < 0.6, "Mittagshoehe $alt")
    }

    @Test
    fun morgensImOstenAbendsImWesten() {
        assertTrue(SkyModel.sunEnu(8.0, 160, 51.0).first > 0.3)
        assertTrue(SkyModel.sunEnu(17.0, 160, 51.0).first < -0.3)
    }

    @Test
    fun nachtsBleibtDasLichtDerTiefenSonne() {
        val night = SkyModel.lighting(SkySetting(hourOfDay = 1.0, dayOfYear = 10, haze = 0.3), 51.0)
        assertEquals(SkyModel.MIN_SUN_ALTITUDE_DEG, night.sunAltitudeDeg, 1e-9)
        assertTrue(night.sunDirection.y > 0.13, "Sonne ueber dem Horizont: ${night.sunDirection}")
        assertEquals(1.0, night.sunDirection.length(), 1e-9)
        val day = SkyModel.lighting(SkySetting.DEFAULT, 51.0)
        assertTrue(day.sunAltitudeDeg > 30.0)
        assertTrue(day.sunLux > night.sunLux)
    }

    @Test
    fun dunstVerkuerztDieSicht() {
        val clear = SkyModel.lighting(SkySetting(15.0, 160, 0.0), 51.0)
        val hazy = SkyModel.lighting(SkySetting(15.0, 160, 1.0), 51.0)
        assertTrue(hazy.fogDensity > clear.fogDensity * 5)
        assertEquals(3.9 / 30_000.0, clear.fogDensity, 1e-9)
        // Mit Sichtweite der Welt: dichter, damit der Rand im Dunst verschwindet.
        assertTrue(SkyModel.lighting(SkySetting(15.0, 160, 0.0), 51.0, 6_000.0).fogDensity > clear.fogDensity * 2)
    }

    @Test
    fun weisserOfenGibtEins() {
        // Gleichmaessig 1 aus allen Richtungen: Filament muss fuer jede Normale 1 ergeben.
        val sh = DoubleArray(27)
        val steps = 64
        for (i in 0 until steps) {
            val th = (i + 0.5) * PI / steps
            for (j in 0 until 2 * steps) {
                val ph = (j + 0.5) * PI / steps
                val d = V3(Math.sin(th) * Math.cos(ph), Math.cos(th), Math.sin(th) * Math.sin(ph))
                val w = Math.sin(th) * (PI / steps) * (PI / steps)
                val b = SkyModel.shBasis(d)
                for (k in 0 until 9) for (c in 0 until 3) sh[k * 3 + c] += b[k] * w
            }
        }
        val f = FloatArray(27) { sh[it].toFloat() }
        assertEquals(3.5449, f[0].toDouble(), 2e-3) // 4π · 0,282095
        for (n in listOf(V3.UP, V3(0.0, -1.0, 0.0), V3(1.0, 0.0, 0.0), V3(0.0, 0.0, -1.0))) {
            assertEquals(1.0, SkyModel.irradianceAt(f, n).r, 2e-3)
        }
    }

    @Test
    fun himmelLeuchtetVonObenMehrAlsVonUnten() {
        val l = SkyModel.lighting(SkySetting.DEFAULT, 51.0)
        val sh = SkyModel.radianceSh(l)
        val up = SkyModel.irradianceAt(sh, V3.UP)
        val down = SkyModel.irradianceAt(sh, V3(0.0, -1.0, 0.0))
        val side = SkyModel.irradianceAt(sh, V3(1.0, 0.0, 0.0))
        assertTrue(up.b > down.b * 1.5, "oben ${up.b}, unten ${down.b}")
        assertTrue(side.b in down.b..up.b)
        // Seiten gleich hell: das Modell ist rund um die Senkrechte gleich.
        val other = SkyModel.irradianceAt(sh, V3(0.0, 0.0, 1.0))
        assertEquals(side.b, other.b, 0.02)
    }

    @Test
    fun cubemapHdrUndRueckfall() {
        val l = SkyModel.lighting(SkySetting.DEFAULT, 51.0)
        val size = 8
        val half = SkyModel.cubemapHalfRgb(l, size)
        assertEquals(6 * size * size * 3, half.size)
        // +Y (Zenit) und −Y spiegelbildlich gleich: die Flaechen-Spiegelung eines Treibers aendert nichts.
        val face = size * size * 3
        assertEquals(half.copyOfRange(2 * face, 3 * face).sorted(), half.copyOfRange(3 * face, 4 * face).sorted())
        // Horizont (Mitte der Seitenflaeche) hell; mit der Belichtung je Tageszeit liegt er je
        // nach Stunde unter oder ueber 1 (Wolkenraender darueber) — Gleitkomma-Textur bleibt.
        val mid = ((size / 2) * size + size / 2) * 3
        assertTrue(SkyModel.halfToDouble(half[mid + 2]) > 0.3, "Horizont blau ${SkyModel.halfToDouble(half[mid + 2])}")
        // Zenit blau: Blau deutlich ueber Rot.
        val z = 2 * face + mid
        assertTrue(SkyModel.halfToDouble(half[z + 2]) > 2.0 * SkyModel.halfToDouble(half[z]))
        // Dunstfarbe = sichtbarer Horizont geteilt durch die Skala der Skybox.
        assertEquals(l.look.horizon.b / l.skyScale, l.fogColor.b, 1e-9)
        // Rueckfall RGBA8: gleiche Texelzahl, deckend.
        val bytes = SkyModel.cubemapSrgbBytes(half)
        assertEquals(6 * size * size * 4, bytes.size)
        assertTrue(bytes.withIndex().filter { it.index % 4 == 3 }.all { it.value == 0xFF.toByte() })
        assertEquals(0.5, SkyModel.halfToDouble(0x3800.toShort()), 1e-12)
        assertEquals(1.0, SkyModel.halfToDouble(0x3C00.toShort()), 1e-12)
    }

    @Test
    fun cubemapRichtungenNachOpenGl() {
        fun same(a: V3, b: V3) = assertTrue((a - b).length() < 1e-12, "$a statt $b")
        same(V3(1.0, 0.0, 0.0), SkyModel.cubeDirection(0, 0.0, 0.0))
        same(V3(0.0, 1.0, 0.0), SkyModel.cubeDirection(2, 0.0, 0.0))
        same(V3(0.0, 0.0, -1.0), SkyModel.cubeDirection(5, 0.0, 0.0))
        // Seitenflaeche: Zeile 0 (tc = −1) ist oben.
        assertTrue(SkyModel.cubeDirection(4, 0.0, -1.0).y > 0)
    }

    @Test
    fun srgbKodierung() {
        assertEquals(0, SkyModel.toSrgbByte(0.0).toInt() and 0xFF)
        assertEquals(255, SkyModel.toSrgbByte(1.0).toInt() and 0xFF)
        assertEquals(188, SkyModel.toSrgbByte(0.5).toInt() and 0xFF)
        assertEquals(255, SkyModel.toSrgbByte(7.0).toInt() and 0xFF)
    }
}
