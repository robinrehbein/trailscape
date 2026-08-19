package de.trailscape.app.data

import de.trailscape.core.Ride
import de.trailscape.core.SegmentRegistry
import de.trailscape.core.TrackPoint
import de.trailscape.core.computeStats
import de.trailscape.core.updateSegmentRegistry
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests fuer [SegmentStore] — die dateigestuetzte Ablage der Segment-Registry
 * (`rides/segmente.json`). Dasselbe Muster wie `RideLoadCacheStoreTest`.
 */
class SegmentStoreTest {

    private val dir: File = createTempDirectory("segment-store-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Spur mit einem klaren Anstieg (~1,2 km, ~96 Hm) auf 47° Breite. */
    private fun climbRide(id: String, createdAt: Long, dtMs: Long, jitterLat: Double = 0.0): Ride {
        val points = (0 until 128).map { k ->
            val ele = when {
                k <= 40 -> 500.0
                k < 88 -> 500.0 + 2.0 * (k - 40)
                else -> 596.0
            }
            TrackPoint(
                lat = 47.0 + jitterLat,
                lon = 13.0 + k * 0.00032929,
                ele = ele,
                time = createdAt + k * dtMs,
            )
        }
        return Ride(id = id, name = id, createdAt = createdAt, stats = computeStats(points), points = points)
    }

    /** Registry mit einem Segment (2 Efforts) und dem Verarbeitungs-Merker. */
    private fun sampleRegistry(): SegmentRegistry {
        val r1 = climbRide("r1", 1_700_000_000_000L, dtMs = 5000L)
        val r2 = climbRide("r2", 1_700_100_000_000L, dtMs = 4000L, jitterLat = 0.00004)
        var registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        registry = updateSegmentRegistry(registry, r2).registry
        return registry
    }

    @Test
    fun `Registry ueberlebt Schreiben und Neuinstanziierung verlustfrei`() {
        val registry = sampleRegistry()
        assertEquals(1, registry.segments.size)

        SegmentStore(dir).write(registry)
        val reloaded = SegmentStore(dir).read()

        assertEquals(registry, reloaded)
        assertTrue(File(dir, SegmentStore.FILE_NAME).exists())
    }

    @Test
    fun `fehlende Datei liest sich als leere Registry`() {
        assertEquals(SegmentRegistry.EMPTY, SegmentStore(dir).read())
    }

    @Test
    fun `kaputte Datei liest sich als leere Registry`() {
        dir.mkdirs()
        File(dir, SegmentStore.FILE_NAME).writeText("{kein json", Charsets.UTF_8)

        assertEquals(SegmentRegistry.EMPTY, SegmentStore(dir).read())
    }

    @Test
    fun `write ersetzt den vorigen Stand vollstaendig`() {
        val store = SegmentStore(dir)
        store.write(sampleRegistry())

        store.write(SegmentRegistry.EMPTY)

        assertEquals(SegmentRegistry.EMPTY, SegmentStore(dir).read())
    }
}
