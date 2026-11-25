package de.trailscape.core.indoor.water

import de.trailscape.core.indoor.sky.DaylightModel
import de.trailscape.core.indoor.sky.IndoorDaylight
import de.trailscape.core.indoor.sky.IndoorTimeOfDay
import de.trailscape.core.indoor.world.IndoorQuality
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Wasser: Wellen-Normalenkarte und Parameter des Materials (docs/indoor/render.md, „Wasser"). */
class WaterWavesTest {
    private val tex = WaterWaves.normalTexture()
    private val n = WaterWaves.SIZE

    private fun slope(x: Int, y: Int, c: Int) = ((tex[((y * n) + x) * 4 + c].toInt() and 0xFF) - 127.5) / 127.5

    @Test
    fun `Karte ist reproduzierbar`() {
        assertTrue(tex.contentEquals(WaterWaves.normalTexture()))
        assertEquals(n * n * 4, tex.size)
        assertEquals(WaterWaves.WAVE_COUNT, WaterWaves.waves().size)
        // Wellenvektoren eindeutig und im Band
        val ks = WaterWaves.waves().map { it.kx to it.ky }
        assertEquals(ks.size, ks.toSet().size)
        for ((kx, ky) in ks) {
            val k = kotlin.math.sqrt((kx * kx + ky * ky).toDouble())
            assertTrue(k >= WaterWaves.K_MIN && k <= WaterWaves.K_MAX, "k=$k")
        }
    }

    @Test
    fun `Steigungen nutzen den Bereich und haben keinen Versatz`() {
        var mx = 0.0
        var my = 0.0
        var peak = 0.0
        for (y in 0 until n) for (x in 0 until n) {
            mx += slope(x, y, 0)
            my += slope(x, y, 1)
            peak = maxOf(peak, abs(slope(x, y, 0)), abs(slope(x, y, 1)))
        }
        mx /= n * n
        my /= n * n
        // Mittel 0 (sonst kippt die ganze Wasserflaeche in der Ferne, wo die Mipmaps mitteln)
        assertTrue(abs(mx) < 0.01 && abs(my) < 0.01, "Mittel $mx/$my")
        assertTrue(peak > 0.98, "Spitze $peak")
        for (i in 0 until n * n) assertEquals(255, tex[i * 4 + 3].toInt() and 0xFF)
    }

    @Test
    fun `Karte ist nahtlos kachelbar`() {
        // Sprung ueber die Naht nicht groesser als zwischen Nachbarn im Inneren
        var seam = 0.0
        var inner = 0.0
        for (y in 0 until n) for (c in 0..1) {
            seam += abs(slope(n - 1, y, c) - slope(0, y, c))
            inner += abs(slope(n / 2, y, c) - slope(n / 2 + 1, y, c))
        }
        for (x in 0 until n) for (c in 0..1) {
            seam += abs(slope(x, n - 1, c) - slope(x, 0, c))
            inner += abs(slope(x, n / 2, c) - slope(x, n / 2 + 1, c))
        }
        assertTrue(seam < inner * 1.25, "Naht $seam, innen $inner")
    }

    @Test
    fun `Parameter passen zu water mat und Karte fuer den Referenzrenderer`() {
        val l = DaylightModel.lighting(IndoorDaylight.skySetting(IndoorTimeOfDay.EVENING, 1_795_532_340_000L, 48.77, 9.06), 48.77, IndoorQuality.MEDIUM.viewDistanceM)
        val f = listOf(File("../app/src/main/materials/water.mat"), File("app/src/main/materials/water.mat")).first { it.exists() }
        val declared = Regex("""name\s*:\s*(\w+)\s*[,}]""").findAll(f.readText().substringAfter("parameters").substringBefore("],"))
            .map { it.groupValues[1] }.toSet()
        // Jeder gesetzte Name muss deklariert sein (sonst nativer Abbruch); deklarierte Sampler setzt FilamentCore.
        assertEquals(declared, WaterShading.params(l).keys + setOf("time", "waves", "waveNormals"))
        assertEquals(0.0, WaterShading.params(l, detail = 0.0)["detail"])
        // Karte fuer tool/indoor-dev/filament (filarender: $WATER_WAVES oder <sky>/water_waves.bin)
        val out = File(if (File("../app").isDirectory) "build/indoor" else "core/build/indoor").apply { mkdirs() }
        File(out, "water_waves.bin").writeBytes(tex)
        val sha = MessageDigest.getInstance("SHA-256").digest(tex).joinToString("") { "%02x".format(it) }
        File(out, "water_waves.sha256").writeText(sha + "\n")
    }
}
