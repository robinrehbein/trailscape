package de.trailscape.core.indoor.world

import de.trailscape.core.garage.JerseyPattern
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.garage.RiderGlbColors
import de.trailscape.core.garage.RiderLook
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Farben in die Datei schreiben (sicherer Grafikmodus). */
class RiderGlbColorsTest {
    private val rider = buildRiderGlb()

    private fun factors(glb: ByteArray): Map<String, List<Float>> {
        val len = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        val j = Json.parseToJsonElement(String(glb, 20, len, Charsets.UTF_8)).jsonObject
        return j["materials"]!!.jsonArray.associate { m ->
            m.jsonObject["name"]!!.jsonPrimitive.content to
                m.jsonObject["pbrMetallicRoughness"]!!.jsonObject["baseColorFactor"]!!.jsonArray.map { it.jsonPrimitive.float }
        }
    }

    @Test
    fun `geschriebene Farben stehen in der Datei, sonst bleibt alles`() {
        val a = RiderAvatar(jersey = 0x2C5BB8, jerseyAccent = 0xE8B923, pattern = JerseyPattern.STRIPES, frame = 0xD8432C, skin = 6)
        val want = RiderLook.materialFactors(a)
        val out = RiderGlbColors.applyOrThrow(rider, want)
        assertEquals(emptyList(), checkModelGlb(out))
        val got = factors(out)
        for ((name, f) in want) for (k in 0..3) assertEquals(f[k], got.getValue(name)[k], 1e-6f, "$name $k")
        assertEquals(listOf(1f, 1f, 1f, 1f), got["x_mat_bike"])
        // Geometrie, Knoten und Animationen unveraendert; der Binaerblock ist Byte fuer Byte derselbe.
        val before = parseGlb(rider)
        val after = parseGlb(out)
        assertEquals(before.triangleCount, after.triangleCount)
        assertEquals(before.nodes.map { it.name }, after.nodes.map { it.name })
        assertEquals(before.materials.map { it.name }, after.materials.map { it.name })
        val binBefore = rider.copyOfRange(20 + ByteBuffer.wrap(rider).order(ByteOrder.LITTLE_ENDIAN).getInt(12), rider.size)
        val binAfter = out.copyOfRange(20 + ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(12), out.size)
        assertContentEquals(binBefore, binAfter)
        assertEquals(out.size, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(8))
        assertEquals(0, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getInt(12) % 4)
    }

    @Test
    fun `Vorgabe aendert keine Farbe und kaputte Eingaben kommen unveraendert zurueck`() {
        val same = RiderGlbColors.apply(rider, RiderLook.materialFactors(RiderAvatar.DEFAULT))
        assertEquals(factors(rider).keys, factors(same).keys)
        for ((name, f) in factors(rider)) for (k in 0..3) assertEquals(f[k], factors(same).getValue(name)[k], 1e-6f)
        val junk = ByteArray(40) { 7 }
        assertContentEquals(junk, RiderGlbColors.apply(junk, RiderLook.materialFactors(RiderAvatar.DEFAULT)))
        assertContentEquals(rider, RiderGlbColors.apply(rider, emptyMap()))
        // Ungueltiger Faktor: lieber das Original als eine kaputte Datei.
        assertContentEquals(rider, RiderGlbColors.apply(rider, mapOf("x_mat_frame" to floatArrayOf(2f, 0f, 0f, 1f))))
        assertTrue(RiderGlbColors.apply(rider, mapOf("gibt_es_nicht" to floatArrayOf(0f, 0f, 0f, 1f))).contentEquals(rider))
    }
}
