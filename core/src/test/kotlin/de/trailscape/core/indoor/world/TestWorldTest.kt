package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TestWorldTest {
    @Test
    fun `Testwelt in LOW besteht die Pruefung, hat alle Belaege und ist deterministisch`() {
        val a = tempDir("testwelt-a")
        val b = tempDir("testwelt-b")
        val start = System.nanoTime()
        val m = WorldBuilderImpl(SimpleWorldParts).buildTestWorld(a, IndoorQuality.LOW)
        val seconds = (System.nanoTime() - start) / 1e9
        assertEquals(emptyList(), checkWorld(a))
        assertTrue(m.track.loop)
        assertTrue(m.track.lengthM in 2_900.0..3_100.0, "Runde: ${m.track.lengthM} m")
        assertEquals(SurfaceKind.entries.toSet(), m.track.surfaces.map { it.kind }.toSet())
        assertTrue(m.track.surfaces.all { it.toM - it.fromM >= 150.0 }, "${m.track.surfaces}")
        assertEquals(0L, m.createdAtMs)
        assertTrue(m.tiles.none { it.aerial || it.degraded })
        assertTrue(m.attribution.isNotEmpty())
        assertTrue(seconds < 120, "Testwelt LOW brauchte $seconds s")

        WorldBuilderImpl(SimpleWorldParts).buildTestWorld(b, IndoorQuality.LOW)
        assertEquals(File(a, WorldManifest.FILE_NAME).readText(), File(b, WorldManifest.FILE_NAME).readText())
        for (t in m.tiles) assertContentEquals(File(a, t.file).readBytes(), File(b, t.file).readBytes(), t.id)
    }

    @Test
    fun `die Kartendaten der Testwelt haben Dorf, Waelder, Felder, Teich und Bach`() {
        val scene = TestWorld.create()
        assertTrue(scene.buildingCount in 40..60, "Gebaeude: ${scene.buildingCount}")
        val map = scene.mapData(SimpleWorldParts.origin(scene.route, scene::heightAtLatLon))
        val all = map.areas(-5_000.0, -5_000.0, 5_000.0, 5_000.0)
        val kinds = all.map { it.kind }.toSet()
        assertTrue(kinds.containsAll(setOf(LandClass.FOREST, LandClass.FARMLAND, LandClass.MEADOW, LandClass.WATER, LandClass.RESIDENTIAL)), "$kinds")
        assertEquals(setOf("broadleaved", "needleleaved"), all.filter { it.kind == LandClass.FOREST }.map { it.sourceClass }.toSet())
        assertTrue(all.all { ringArea(it.rings[0]) > 0 }, "Aussenringe gegen den Uhrzeigersinn")
        assertTrue(map.waterways(-5_000.0, -5_000.0, 5_000.0, 5_000.0).isNotEmpty())
        val roads = map.roads(-5_000.0, -5_000.0, 5_000.0, 5_000.0)
        assertTrue(roads.any { it.cls == "track" } && roads.any { it.brunnel == "bridge" } && roads.count { it.cls == "minor" } >= 2)
        val buildings = map.buildings(-5_000.0, -5_000.0, 5_000.0, 5_000.0)
        assertTrue(buildings.any { it.rings[0].size / 2 == 6 }, "L-foermige Grundrisse")
        assertTrue(buildings.all { ringArea(it.rings[0]) > 0 })
        // keine Strecke durch ein Haus
        val line = TrackLine(SimpleWorldParts.fitTrack(scene.route, SimpleWorldParts.origin(scene.route, scene::heightAtLatLon), { _, _ -> 0.0 }, { false }))
        for (bd in buildings) {
            val r = bd.rings[0]
            for (k in 0 until r.size / 2) assertTrue(line.distanceTo(r[2 * k], r[2 * k + 1], 50.0) > 4.0, "Haus ${bd.id} steht auf der Strecke")
        }
        // Bruecke ueber den Bach
        assertTrue((0 until 3000).any { scene.isBridge(it.toDouble()) })
    }
}
