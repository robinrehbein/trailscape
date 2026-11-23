package de.trailscape.core.indoor.world

import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WorldCheckTest {
    private companion object {
        /** Eine heile Testwelt, einmal gebaut; jeder Test beschaedigt eine Kopie. */
        val world: File by lazy {
            tempDir("weltpruefung").also { WorldBuilderImpl(SimpleWorldParts).buildTestWorld(it, IndoorQuality.LOW) }
        }
    }

    private fun damaged(name: String): Pair<File, WorldManifest> {
        val dir = copyWorld(world, tempDir("weltpruefung-$name"))
        return dir to WorldManifest.parse(File(dir, WorldManifest.FILE_NAME).readText())
    }

    private fun write(dir: File, m: WorldManifest) = File(dir, WorldManifest.FILE_NAME).writeText(m.toJson())

    private fun assertReports(problems: List<String>, vararg needles: String) {
        for (needle in needles) assertTrue(problems.any { needle in it }, "erwartet Meldung mit \"$needle\", bekommen: ${problems.take(8)}")
    }

    @Test
    fun `heile Welt hat keine Verstoesse`() {
        assertEquals(emptyList(), checkWorld(world))
    }

    @Test
    fun `fehlendes Manifest und fehlende Kachel werden gemeldet`() {
        assertReports(checkWorld(tempDir("leer")), "manifest.json fehlt")
        val (dir, m) = damaged("kachel")
        val victim = m.tiles.first { it.level == 0 }
        File(dir, victim.file).delete()
        assertReports(checkWorld(dir), "Kachel ${victim.id}: Datei ${victim.file} fehlt")
    }

    @Test
    fun `Luecke in show wird gemeldet`() {
        val (dir, m) = damaged("luecke")
        val pose = m.track.poseAt(1_000.0)
        val under = m.tiles.first { it.level == 0 && it.ix == floor(pose.e / 256).toInt() && it.iy == floor(pose.n / 256).toInt() }
        val cut = under.show.flatMap { iv ->
            if (1_000.0 in iv) listOf(ShowInterval(iv.fromM, 990.0), ShowInterval(1_010.0, iv.toM)) else listOf(iv)
        }
        write(dir, m.copy(tiles = m.tiles.map { if (it.id == under.id) it.copy(show = cut) else it }))
        assertReports(checkWorld(dir), "keine gezeigte Stufe-0-Kachel unter dem Fahrer")
    }

    @Test
    fun `fehlende Eltern-Kachel und unsortiertes show werden gemeldet`() {
        val (dir, m) = damaged("eltern")
        val pose = m.track.poseAt(500.0)
        val parent = m.tiles.first { it.level == 1 && it.ix == floor(pose.e / 1024).toInt() && it.iy == floor(pose.n / 1024).toInt() }
        val l0 = m.tiles.first { it.level == 0 }
        write(
            dir,
            m.copy(
                tiles = m.tiles.map {
                    when (it.id) {
                        parent.id -> it.copy(show = listOf(ShowInterval(0.0, 1.0)))
                        l0.id -> it.copy(show = listOf(ShowInterval(20.0, 30.0), ShowInterval(5.0, 10.0)))
                        else -> it
                    }
                },
            ),
        )
        assertReports(checkWorld(dir), "die Eltern-Kachel ${parent.id} aber nicht", "nicht sortiert")
    }

    @Test
    fun `falsches Budget und falsche Kennzahlen werden gemeldet`() {
        val (dir, m) = damaged("budget")
        val t = m.tiles.first()
        write(
            dir,
            m.copy(
                budget = m.budget.copy(maxTriangles = m.budget.maxTriangles + 1),
                tiles = m.tiles.map { if (it.id == t.id) it.copy(textureBytes = it.textureBytes + 4) else it },
            ),
        )
        assertReports(checkWorld(dir), "maxTriangles", "Texturbytes in der GLB")
    }

    @Test
    fun `verbotene Erweiterung in einer Kachel wird gemeldet`() {
        val (dir, m) = damaged("erweiterung")
        val t = m.tiles.first { it.level == 1 }
        val f = File(dir, t.file)
        f.writeBytes(withExtension(f.readBytes(), "EXT_mesh_gpu_instancing"))
        assertReports(checkWorld(dir), "Verbotene Erweiterung in extensionsUsed: EXT_mesh_gpu_instancing", "Kachel ${t.id}: Datei hat")
    }

    @Test
    fun `offene Runde und Steigung werden gemeldet`() {
        val (dir, m) = damaged("band")
        val u = m.track.u.copyOf()
        u[10] += 5f
        u[u.size - 1] += 3f
        write(dir, m.copy(track = m.track.copy(u = u)))
        assertReports(checkWorld(dir), "Runde nicht geschlossen", "Steigung")
    }

    /** Setzt `extensionsUsed` in den JSON-Block einer GLB (Laengen werden neu gerechnet). */
    private fun withExtension(glb: ByteArray, ext: String): ByteArray {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLen = bb.getInt(12)
        val json = String(glb, 20, jsonLen, Charsets.UTF_8).trimEnd()
        val patched = "{\"extensionsUsed\":[\"$ext\"]," + json.substring(1)
        var bytes = patched.toByteArray(Charsets.UTF_8)
        val pad = (4 - bytes.size % 4) % 4
        bytes += ByteArray(pad) { ' '.code.toByte() }
        val rest = glb.copyOfRange(20 + jsonLen, glb.size)
        val out = ByteBuffer.allocate(20 + bytes.size + rest.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(20 + bytes.size + rest.size)
        out.putInt(bytes.size).putInt(0x4E4F534A).put(bytes).put(rest)
        return out.array()
    }
}
