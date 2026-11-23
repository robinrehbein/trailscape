package de.trailscape.core.indoor.world

import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FullWorldPartsTest {
    private fun nodeNames(dir: File, m: WorldManifest, level: Int): Set<String> =
        m.tiles.filter { it.level == level }.flatMap { t -> parseGlb(File(dir, t.file).readBytes()).nodes.mapNotNull { it.name } }.toSet()

    private fun materials(dir: File, m: WorldManifest): Set<String> =
        m.tiles.flatMap { t -> parseGlb(File(dir, t.file).readBytes()).materials.mapNotNull { it.name } }.toSet()

    @Test
    fun `Testwelt aus dem Einstieg besteht die Pruefung, hat alle Schichten und ist deterministisch`() {
        val a = tempDir("voll-a")
        val m = createWorldBuilder().buildTestWorld(a, IndoorQuality.LOW)
        assertEquals(emptyList(), checkWorld(a))
        val near = nodeNames(a, m, 0)
        for (node in listOf(WorldNames.NODE_TERRAIN, WorldNames.NODE_ROUTE, WorldNames.NODE_ROADS, WorldNames.NODE_BUILDINGS, WorldNames.NODE_TREES, WorldNames.NODE_PROPS)) {
            assertTrue(node in near, "Knoten $node fehlt in Stufe 0: $near")
        }
        // Der Teich liegt rund 300 m neben der Runde, also nur in Stufe 1: dort haengt das Wasser an den Teilflaechen.
        val mats = materials(a, m)
        for (mat in listOf(WorldNames.MAT_TREE_TRUNK, WorldNames.MAT_TREE_LEAVES, WorldNames.MAT_WATER, WorldNames.MAT_BUILDING_WALL)) {
            assertTrue(mat in mats, "Material $mat fehlt: $mats")
        }
        assertTrue(m.budget.maxTriangles <= IndoorQuality.LOW.maxTriangles)
        assertTrue(m.budget.maxTextureBytes <= IndoorQuality.LOW.maxTextureBytes)

        val b = tempDir("voll-b")
        createWorldBuilder().buildTestWorld(b, IndoorQuality.LOW)
        assertEquals(File(a, WorldManifest.FILE_NAME).readText(), File(b, WorldManifest.FILE_NAME).readText())
        for (t in m.tiles) assertContentEquals(File(a, t.file).readBytes(), File(b, t.file).readBytes(), t.id)
    }

    @Test
    fun `Welt mit Geodaten aus dem Einstieg besteht die Pruefung und laesst sich abbrechen`() {
        val builder = WorldBuilderImpl(FullWorldParts, aerialRegister = listOf(TEST_AERIAL), clock = { 1L })
        val plan = builder.plan(shortRoute(), IndoorQuality.LOW)
        val dir = tempDir("voll-geo")
        val m = builder.build(plan, fakeDeps(FakeGeo()), dir, RecordingListener())
        assertEquals(emptyList(), checkWorld(dir))
        assertTrue(m.tiles.all { it.aerial })
        assertEquals(plan.route.lengthM, m.track.lengthM)

        val cancelDir = tempDir("voll-abbruch")
        val cancelling = RecordingListener { phase, _, _ -> phase == WorldBuildPhase.MESHING }
        val failure = runCatching { builder.build(plan, fakeDeps(FakeGeo()), cancelDir, cancelling) }.exceptionOrNull()
        assertTrue(failure is WorldBuildCancelled, "erwartet Abbruch, bekommen $failure")
        assertTrue(!File(cancelDir, WorldManifest.FILE_NAME).exists())
    }
}
