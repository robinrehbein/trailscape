package de.trailscape.app.indoor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbPatchTest {
    private fun glb(json: String, bin: ByteArray): ByteBuffer {
        val j = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val total = 12 + 8 + j.size + 8 + bin.size
        val b = ByteBuffer.allocateDirect(total).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x46546C67).putInt(2).putInt(total)
        b.putInt(j.size).putInt(0x4E4F534A).put(j)
        b.putInt(bin.size).putInt(0x004E4942).put(bin)
        b.flip()
        return b.order(ByteOrder.nativeOrder())
    }

    private fun text(b: ByteBuffer): String = ByteArray(b.remaining()).also { b.duplicate().get(it) }.toString(Charsets.ISO_8859_1)

    @Test
    fun `ueberschreibt die Erweiterung gleich lang und nur im JSON-Teil`() {
        val json = """{"extensionsUsed":["KHR_materials_specular"],"materials":[{"name":"a","extensions":{"KHR_materials_specular":{"specularFactor":0}}}]}"""
        val bin = "\"KHR_materials_specular\"".toByteArray()
        val b = glb(json, bin)
        val before = b.remaining()
        assertEquals(2, GlbPatch.neutralizeSpecular(b))
        assertEquals(before, b.remaining())
        assertEquals(0, b.position())
        val t = text(b)
        val jsonPart = t.substring(20, 20 + json.length)
        assertFalse(jsonPart.contains("KHR_materials_specular"))
        assertTrue(jsonPart.contains("\"ZZZ_materials_specular\":{\"specularFactor\":0}"))
        // Der Binaerteil bleibt unberuehrt.
        assertTrue(t.endsWith("\"KHR_materials_specular\""))
    }

    @Test
    fun `laesst andere Daten in Ruhe`() {
        assertEquals(0, GlbPatch.neutralizeSpecular(glb("""{"materials":[{"extensions":{"KHR_materials_unlit":{}}}]}""", ByteArray(4))))
        assertEquals(0, GlbPatch.neutralizeSpecular(ByteBuffer.allocateDirect(8)))
        val junk = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        junk.putInt(0, 0x46546C67).putInt(12, 1_000_000).putInt(16, 0x4E4F534A)
        assertEquals(0, GlbPatch.neutralizeSpecular(junk))
    }
}
