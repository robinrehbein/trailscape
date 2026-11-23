package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.ShowInterval
import de.trailscape.core.indoor.world.WorldBounds
import de.trailscape.core.indoor.world.WorldBudget
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldTile
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fehlereinordnung, Manifestpruefung, Bildratenmessung. */
class RenderSupportTest {
    @Test
    fun fehlerEinordnen() {
        assertEquals(RenderFailure.NO_GRAPHICS, classifyFailure(UnsatisfiedLinkError("x"), RenderStage.FRAME))
        assertEquals(RenderFailure.OUT_OF_MEMORY, classifyFailure(OutOfMemoryError(), RenderStage.TILES))
        assertEquals(RenderFailure.NO_GRAPHICS, classifyFailure(IllegalStateException("Couldn't create Engine"), RenderStage.ENGINE))
        assertEquals(RenderFailure.BAD_ASSETS, classifyFailure(IllegalStateException(), RenderStage.MODELS))
        assertEquals(RenderFailure.SURFACE_LOST, classifyFailure(RuntimeException(), RenderStage.SURFACE))
        assertEquals(RenderFailure.INTERNAL, classifyFailure(RuntimeException(), RenderStage.FRAME))
        assertTrue(failureDetail(RenderStage.TILES, IllegalStateException("kaputt")).contains("kaputt"))
    }

    private fun manifest(track: WorldTrack = track(), tiles: List<WorldTile> = listOf(tile())) = WorldManifest(
        worldId = "w",
        builder = "test",
        createdAtMs = 0,
        quality = IndoorQuality.MEDIUM,
        routeId = "r",
        routeName = "Runde",
        origin = GeoOrigin(51.0, 13.0, 200.0),
        bounds = WorldBounds(-100.0, -100.0, 100.0, 100.0, 0.0, 10.0),
        track = track,
        levels = emptyList(),
        tiles = tiles,
        budget = WorldBudget(1, 1, 1),
        attribution = emptyList(),
    )

    private fun track(samples: Int = 3) = WorldTrack(
        lengthM = 10.0, stepM = 5.0, loop = false,
        e = FloatArray(samples), n = FloatArray(samples) { it * 5f }, u = FloatArray(samples),
        surfaces = listOf(SurfaceSection(0.0, 10.0, SurfaceKind.ASPHALT)), source = IndoorRouteSource.NONE,
    )

    private fun tile(file: String = "tiles/L0_0_0.glb") = WorldTile(
        "L0_0_0", 0, 0, 0, file, 1, 0.0, 0.0, 0.0, 0.0, 1.0, listOf(ShowInterval(0.0, 10.0)), 1, 1,
    )

    @Test
    fun manifestPruefen() {
        assertNull(manifestProblem(manifest()))
        assertNotNull(manifestProblem(manifest(track = track(samples = 1))))
        assertNotNull(manifestProblem(manifest(tiles = emptyList())))
        assertNotNull(manifestProblem(manifest(tiles = listOf(tile("../../etc/passwd")))))
    }

    @Test
    fun bildrateUndP95() {
        val m = FrameMeter()
        var t = 0L
        var result: Pair<Double, Double>? = null
        m.frame(t)
        // 59 Bilder zu 16 ms und eines zu 50 ms → gut 1 Sekunde
        repeat(70) { i ->
            t += if (i == 30) 50_000_000L else 16_000_000L
            m.frame(t)?.let { result = it }
        }
        val (fps, p95) = assertNotNull(result)
        assertTrue(fps in 55.0..65.0, "fps $fps")
        assertTrue(p95 in 15.9..50.1, "p95 $p95")
        assertEquals(0.0, FrameMeter.percentile(emptyList(), 0.95))
        assertEquals(10.0, FrameMeter.percentile(listOf(1.0, 2.0, 10.0), 0.95))
    }
}
