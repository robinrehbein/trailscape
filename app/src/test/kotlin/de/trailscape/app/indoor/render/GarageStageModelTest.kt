package de.trailscape.app.indoor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GarageStageModelTest {

    @Test
    fun `Studio-Podest erzeugt gueltiges GLB im hellen und dunklen Thema`() {
        for (dark in listOf(false, true)) {
            val glb = GarageStageModel.buildPedestalGlb(dark)
            assertTrue(glb.size > 100, "GLB darf nicht leer sein")
            val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x46546C67, buf.int, "glTF-Magic")
            assertEquals(2, buf.int, "glTF-Version 2")
            val totalLen = buf.int
            assertEquals(glb.size, totalLen)
            val jsonLen = buf.int
            assertEquals(0x4E4F534A, buf.int, "JSON-Chunk")
            val json = String(glb, 20, jsonLen, Charsets.UTF_8)
            assertTrue(json.contains("garage_pedestal"), "Podest-Material enthalten")
            assertTrue(json.contains("garage_pedestal_rim"), "Podest-Randmaterial enthalten")
        }
    }

    @Test
    fun `Studio-Look unterscheidet Hell- und Dunkelmodus deutlich`() {
        val light = GarageStageModel.studioLook(darkTheme = false)
        val dark = GarageStageModel.studioLook(darkTheme = true)
        assertTrue(light.skyLinear[0] > 0.5f)
        assertTrue(dark.skyLinear[0] < 0.05f)
        assertTrue(light.pedestalLinear[0] > dark.pedestalLinear[0])
    }
}
