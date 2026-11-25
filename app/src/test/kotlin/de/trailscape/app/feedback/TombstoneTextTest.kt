package de.trailscape.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneTextTest {
    private fun varint(v: Int): ByteArray {
        var x = v
        val out = ArrayList<Byte>()
        while (true) {
            if (x < 0x80) { out.add(x.toByte()); break }
            out.add(((x and 0x7f) or 0x80).toByte())
            x = x ushr 7
        }
        return out.toByteArray()
    }

    private fun field(no: Int, payload: ByteArray) = varint((no shl 3) or 2) + varint(payload.size) + payload
    private fun str(no: Int, s: String) = field(no, s.toByteArray())
    private fun num(no: Int, v: Int) = varint(no shl 3) + varint(v)

    @Test
    fun `liest Abbruchmeldung und verschachtelte Aufrufkette`() {
        val frame = num(1, 4660) + str(4, "filament::backend::ShaderCompilerService::checkProgramStatus") + str(6, "libfilament-jni.so")
        val thread = num(1, 77) + str(2, "main") + field(4, frame)
        val msg = str(1, "samsung/fingerprint") + num(3, 1700000000) +
            str(14, "OpenGL program TrailscapeTerrain failed to link or compile") +
            field(16, num(1, 77) + field(2, thread)) +
            field(20, byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))
        val text = TombstoneText.extract(msg)!!
        assertTrue(text.contains("TrailscapeTerrain failed to link"))
        assertTrue(text.contains("ShaderCompilerService"))
        assertTrue(text.contains("libfilament-jni.so"))
        assertEquals(1, Regex("samsung/fingerprint").findAll(text).count())
    }

    @Test
    fun `Muell ergibt keinen Bericht und wirft nicht`() {
        assertNull(TombstoneText.extract(ByteArray(64) { 0xff.toByte() }))
        assertNull(TombstoneText.extract(ByteArray(0)))
    }
}
