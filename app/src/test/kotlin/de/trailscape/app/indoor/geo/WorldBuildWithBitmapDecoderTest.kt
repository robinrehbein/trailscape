package de.trailscape.app.indoor.geo

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import de.trailscape.core.TrackPoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.buildIndoorRoute
import de.trailscape.core.indoor.world.GeoFetcher
import de.trailscape.core.indoor.world.GeoResponse
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldBuildDeps
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuildPhase
import de.trailscape.core.indoor.world.checkWorld
import de.trailscape.core.indoor.world.createGeoPipeline
import de.trailscape.core.indoor.world.createWorldBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Der echte Weltbau mit dem **Android-Dekodierer** ([BitmapRasterDecoder],
 * Robolectric mit nativer Grafik) und aufgezeichneten echten Kachel-Bytes
 * (core/src/test/resources/indoor/world, Altenberg/NRW).
 *
 * Die JVM-Pruefungen des Weltbaus laufen mit ImageIO; hier zaehlt, dass
 * BitmapFactory fuer Terrarium-PNG dieselben Kanalwerte und fuer WMS-JPEG
 * gleichwertige Pixel liefert und ein ganzer Bau damit durchlaeuft.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class WorldBuildWithBitmapDecoderTest {

    private val resources = File("../core/src/test/resources/indoor/world").let { if (it.isDirectory) it else File("core/src/test/resources/indoor/world") }

    private fun bytes(name: String): ByteArray = File(resources, name).readBytes()

    @Test
    fun `Terrarium-PNG ergibt dieselben Kanalwerte wie ImageIO`() {
        val png = bytes("terrarium_13_4258_2740.png")
        val android = assertNotNull(BitmapRasterDecoder().decode(png))
        val awt = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(awt.width, android.width)
        assertEquals(awt.height, android.height)
        var diff = 0
        for (y in 0 until awt.height) for (x in 0 until awt.width) {
            if ((awt.getRGB(x, y) and 0xFFFFFF) != (android.argb[y * android.width + x] and 0xFFFFFF)) diff++
        }
        assertEquals(0, diff, "Terrarium-Pixel mit anderem RGB (Meter stecken in R, G, B)")
    }

    @Test
    fun `WMS-JPEG ergibt bis auf Rundung dieselben Pixel wie ImageIO`() {
        for (name in listOf("aerial_nw_256.jpg", "aerial_be_progressive_256.jpg")) {
            val jpg = bytes(name)
            val android = assertNotNull(BitmapRasterDecoder().decode(jpg), name)
            val awt = ImageIO.read(ByteArrayInputStream(jpg))
            assertEquals(awt.width to awt.height, android.width to android.height, name)
            var sum = 0L
            for (y in 0 until awt.height) for (x in 0 until awt.width) {
                val a = awt.getRGB(x, y)
                val b = android.argb[y * android.width + x]
                for (shift in intArrayOf(16, 8, 0)) sum += abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
                assertEquals(0xFF, b ushr 24, "$name: undurchsichtig")
            }
            val mean = sum.toDouble() / (awt.width * awt.height * 3)
            assertTrue(mean < 3.0, "$name: mittlere Abweichung $mean")
        }
    }

    /** Liefert die aufgezeichneten Kacheln; Luftbilder in der verlangten Groesse aus dem echten NRW-Ausschnitt. */
    private inner class RecordedGeo : GeoFetcher {
        private val terrarium = bytes("terrarium_13_4258_2740.png")
        private val aerialSource: Bitmap = BitmapFactory.decodeByteArray(bytes("aerial_nw_256.jpg"), 0, bytes("aerial_nw_256.jpg").size)
        private val aerialBySize = HashMap<Pair<Int, Int>, ByteArray>()
        val counts = HashMap<GeoSourceKind, Int>()

        @Synchronized
        override fun fetch(request: de.trailscape.core.indoor.world.GeoRequest): GeoResponse {
            counts.merge(request.kind, 1, Int::plus)
            val url = request.url
            return when (request.kind) {
                // Jede Hoehenkachel bekommt die echte Altenberger Kachel (andere Lage, echte Werte).
                GeoSourceKind.ELEVATION -> GeoResponse(200, "image/png", terrarium)
                GeoSourceKind.VECTOR -> when {
                    url.endsWith("/14/8516/5480.pbf") -> GeoResponse(200, "application/x-protobuf", bytes("vector_14_8516_5480.pbf"))
                    url.endsWith("/14/8516/5481.pbf") -> GeoResponse(200, "application/x-protobuf", bytes("vector_14_8516_5481.pbf"))
                    url.endsWith(".pbf") -> GeoResponse(404, null, ByteArray(0))
                    else -> GeoResponse(200, "application/json", bytes("tilejson.json"))
                }
                GeoSourceKind.AERIAL -> {
                    val w = Regex("[?&]WIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(url)!!.groupValues[1].toInt()
                    val h = Regex("[?&]HEIGHT=(\\d+)", RegexOption.IGNORE_CASE).find(url)!!.groupValues[1].toInt()
                    val jpg = aerialBySize.getOrPut(w to h) {
                        val scaled = Bitmap.createScaledBitmap(aerialSource, w, h, true)
                        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                    }
                    GeoResponse(200, "image/jpeg", jpg)
                }
            }
        }
    }

    @Test
    fun `ganzer Weltbau mit BitmapRasterDecoder und echten Kachel-Bytes`() {
        // Gut 1 km um den Altenberger Dom (in den aufgezeichneten Vektorkacheln).
        val points = (0..60).map { i ->
            val t = i / 60.0
            TrackPoint(lat = 51.0530 + 0.0060 * t, lon = 7.1300 + 0.0120 * t + 0.0015 * kotlin.math.sin(t * 6.0), ele = 95.0 + 20 * t)
        }
        val route = assertNotNull(buildIndoorRoute("Altenberg", points, null, IndoorRouteSource.NONE))
        val builder = createWorldBuilder()
        val plan = builder.plan(route, IndoorQuality.LOW)
        assertTrue(plan.aerial.isNotEmpty(), "NRW hat Luftbild")
        val tmp = Files.createTempDirectory("weltbau-android").toFile()
        try {
            val geo = RecordedGeo()
            val deps = WorldBuildDeps(createGeoPipeline(geo, File(tmp, "cache")), BitmapRasterDecoder())
            val phases = HashSet<WorldBuildPhase>()
            val manifest = builder.build(plan, deps, File(tmp, "welt"), object : WorldBuildListener {
                override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
                    phases += phase
                }
            })
            assertEquals(emptyList(), checkWorld(File(tmp, "welt")))
            assertEquals(WorldBuildPhase.entries.toSet(), phases)
            assertTrue(manifest.tiles.any { it.aerial }, "Luftbilder ueber BitmapFactory dekodiert und verbaut")
            assertTrue(manifest.origin.eleM in 60.0..300.0, "Hoehe aus der echten Terrarium-Kachel: ${manifest.origin.eleM}")
            assertTrue((geo.counts[GeoSourceKind.ELEVATION] ?: 0) > 0)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
