package de.trailscape.core.garage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Schreibt Zonenfarben als `baseColorFactor` **in die GLB-Datei** — fuer den
 * sicheren Grafikmodus: Dort setzt die Darstellung keine Materialparameter zur
 * Laufzeit, gltfio liest die Farben wie bisher aus der Datei (bewaehrter Weg).
 *
 * Geaendert wird nur `materials[i].pbrMetallicRoughness.baseColorFactor` der
 * Materialien, deren Name in [factors] steht; Binaerblock, Puffer und alles
 * andere bleiben Byte fuer Byte. Faellt bei unerwartetem Aufbau auf die
 * unveraenderte Datei zurueck (wirft nie).
 */
object RiderGlbColors {
    private const val MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A

    fun apply(glb: ByteArray, factors: Map<String, FloatArray>): ByteArray =
        runCatching { applyOrThrow(glb, factors) }.getOrDefault(glb)

    fun applyOrThrow(glb: ByteArray, factors: Map<String, FloatArray>): ByteArray {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(glb.size >= 20 && bb.getInt(0) == MAGIC) { "keine GLB" }
        require(bb.getInt(16) == CHUNK_JSON) { "erster Block nicht JSON" }
        val jsonLen = bb.getInt(12)
        require(jsonLen > 0 && 20 + jsonLen <= glb.size) { "JSON-Block zu lang" }
        val json = Json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)) as JsonObject
        val materials = json["materials"] as? JsonArray ?: return glb
        var changed = 0
        val newMaterials = JsonArray(
            materials.map { m ->
                val mo = m as? JsonObject ?: return@map m
                val name = (mo["name"] as? JsonPrimitive)?.content
                val f = factors[name] ?: return@map m
                require(f.size == 4 && f.all { it.isFinite() && it in 0f..1f }) { "Faktor fuer $name ungueltig" }
                val pbr = mo["pbrMetallicRoughness"] as? JsonObject ?: JsonObject(emptyMap())
                changed++
                JsonObject(mo + ("pbrMetallicRoughness" to JsonObject(pbr + ("baseColorFactor" to JsonArray(f.map { JsonPrimitive(it) })))))
            },
        )
        if (changed == 0) return glb
        val text = JsonObject(json + ("materials" to newMaterials)).toString().toByteArray(Charsets.UTF_8)
        val padded = (text.size + 3) / 4 * 4
        val rest = glb.size - (20 + jsonLen)
        val out = ByteBuffer.allocate(20 + padded + rest).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC)
        out.putInt(bb.getInt(4))
        out.putInt(20 + padded + rest)
        out.putInt(padded)
        out.putInt(CHUNK_JSON)
        out.put(text)
        repeat(padded - text.size) { out.put(0x20) }
        out.put(glb, 20 + jsonLen, rest)
        return out.array()
    }
}
