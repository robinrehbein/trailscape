package de.trailscape.core.indoor.sky

import de.trailscape.core.indoor.world.IndoorQuality
import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Licht und Farbe der Darstellung (docs/indoor/render.md, „Licht und Farbe"). */
class LightAndShadingTest {
    private val lat = 48.77

    private fun at(time: IndoorTimeOfDay, epochMs: Long = 1_795_532_340_000L) =
        DaylightModel.lighting(IndoorDaylight.skySetting(time, epochMs, lat, 9.06), lat, IndoorQuality.MEDIUM.viewDistanceM)

    @Test
    fun `PBR Neutral laesst mittlere Farben unveraendert und verdichtet helle weich`() {
        val mid = ToneGrade.pbrNeutral(doubleArrayOf(0.3, 0.5, 0.2))
        assertEquals(0.3 - 0.04, mid[0], 1e-12)
        assertEquals(0.5 - 0.04, mid[1], 1e-12)
        val bright = ToneGrade.pbrNeutral(doubleArrayOf(8.0, 8.0, 8.0))
        assertTrue(bright.all { it in 0.95..1.0 }, bright.toList().toString())
    }

    @Test
    fun `Zielfarben des Himmels kommen nach dem Farbweg wieder heraus`() {
        val g = ToneGradeLook(contrast = 1.06, vibrance = 1.18)
        for (c in listOf(intArrayOf(74, 108, 182), intArrayOf(54, 120, 214), intArrayOf(242, 192, 150), intArrayOf(194, 214, 236), intArrayOf(132, 116, 136))) {
            val lin = ToneGrade.invert(c, g)
            val back = ToneGrade.displaySrgb(lin, g)
            for (i in 0..2) assertTrue(abs(back[i] - c[i]) <= 2, "${c.toList()} → ${back.toList()}")
        }
    }

    @Test
    fun `Belichtung folgt dem Licht, Daemmerung wird nicht mehr zwei Blenden zu dunkel`() {
        val dusk = at(IndoorTimeOfDay.AUTO) // 24.11., 18:19 Uhr: Daemmerung bei 8°
        val noon = at(IndoorTimeOfDay.NOON)
        assertEquals(IndoorDaylight.DUSK_FLOOR_DEG, dusk.sunAltitudeDeg, 1e-9)
        assertTrue(dusk.ev100 < noon.ev100 - 1.0, "EV abends ${dusk.ev100}, mittags ${noon.ev100}")
        assertTrue(dusk.ev100 < DaylightLighting.DEFAULT_EV100 - 1.0, "frueher galt immer EV 15")
        // Eine weisse, waagrechte Flaeche landet nach der Belichtung nahe am Ziel (abends etwas darunter).
        for (l in listOf(dusk, noon)) {
            val sun = l.sunLux * sin(Math.toRadians(l.sunAltitudeDeg)) * l.sunColor.luminance / Math.PI
            val amb = l.ambientLux * (l.iblHorizon * 0.2 + l.iblZenith * 0.8).luminance
            val white = (sun + amb) * l.exposure
            assertTrue(white in 0.8..1.1, "weiss nach Belichtung $white bei ${l.sunAltitudeDeg}°")
        }
    }

    @Test
    fun `Umgebungslicht bei tiefer Sonne warm statt blau`() {
        val dusk = at(IndoorTimeOfDay.AUTO)
        val up = dusk.iblHorizon * 0.2 + dusk.iblZenith * 0.8
        assertTrue(up.r >= up.b, "Licht von oben abends $up")
        assertTrue(dusk.iblGround.r > dusk.iblGround.b)
        assertEquals(ToneGradeLook.PBR_NEUTRAL, dusk.grade.toneMapper)
        assertTrue(dusk.fogInScatteringSize > 0 && dusk.fogInScatteringStart >= 100.0)
    }

    @Test
    fun `Wolkentextur kachelbar und voll ausgesteuert`() {
        val n = 64
        val t = SkyClouds.cloudTexture(n)
        assertEquals(n * n, t.size)
        val v = t.map { it.toInt() and 0xFF }
        assertEquals(0, v.min())
        assertEquals(255, v.max())
        // Naht: Sprung von der letzten zur ersten Spalte nicht groesser als zwischen Nachbarn im Inneren
        var seam = 0.0
        var inner = 0.0
        for (y in 0 until n) {
            seam += abs(v[y * n + n - 1] - v[y * n])
            inner += abs(v[y * n + n / 2] - v[y * n + n / 2 + 1])
        }
        assertTrue(seam < inner * 2.0 + n, "Naht $seam, innen $inner")
    }

    @Test
    fun `Parameter passen zu den Materialien der App`() {
        fun params(file: String): Set<String> {
            val f = listOf(File("../app/src/main/materials/$file"), File("app/src/main/materials/$file")).first { it.exists() }
            return Regex("""name\s*:\s*(\w+)\s*[,}]""").findAll(f.readText().substringAfter("parameters").substringBefore("],"))
                .map { it.groupValues[1] }.toSet()
        }
        val l = at(IndoorTimeOfDay.EVENING)
        assertEquals(params("sky.mat"), SkyShading.sky(l).keys + setOf("time", "clouds"))
        assertEquals(params("water.mat"), SkyShading.water(l).keys + setOf("time", "waves"))
        // Horizont des Himmelsmaterials = Dunstfarbe × iblLuminance: Gelaende und Himmel stossen nahtlos aneinander.
        val h = SkyShading.sky(l)["horizon"] as DoubleArray
        assertEquals(l.fogColor.g * l.skyScale, h[1], 1e-9)
    }
}
