package de.trailscape.app.indoor.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Faerbt das Trikot des Fahrermodells (`rider.glb` aus dem Weltbau) fuer die anderen
 * Radfahrer um: In den Teilen mit dem Material `x_mat_kit` werden die rot-dominanten
 * Vertexfarben (`COLOR_0`, normierte Bytes) durch [jersey] ersetzt, ihre Helligkeit relativ
 * zum Trikotrot bleibt (dunkle Raender bleiben dunkel); Hose, Saeume und Haut bleiben.
 * Reine Byte-Arbeit ohne Filament, getestet. Faellt bei unerwartetem Aufbau auf das
 * unveraenderte Modell zurueck.
 */
internal object RiderRecolor {
    /** Trikotfarben der anderen Fahrer (linear RGB, je 0..1). */
    val JERSEYS: List<FloatArray> = listOf(
        floatArrayOf(0.02f, 0.16f, 0.62f), // Blau
        floatArrayOf(0.85f, 0.62f, 0.02f), // Gelb
        floatArrayOf(0.03f, 0.36f, 0.12f), // Gruen
        floatArrayOf(0.80f, 0.80f, 0.78f), // Weiss
        floatArrayOf(0.02f, 0.02f, 0.025f), // Schwarz
        floatArrayOf(0.02f, 0.40f, 0.48f), // Petrol
    )

    private const val KIT = "x_mat_kit"

    /** Rot des Trikots im Modell (Byte, linear) — Bezug fuer die Helligkeit. */
    private const val REF_RED = 175.0

    fun recolor(glb: ByteArray, jersey: FloatArray): ByteArray = runCatching { recolorOrThrow(glb, jersey) }.getOrDefault(glb)

    fun recolorOrThrow(glb: ByteArray, jersey: FloatArray): ByteArray {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(bb.getInt(0) == 0x46546C67) { "keine GLB" }
        val jsonLen = bb.getInt(12)
        require(bb.getInt(16) == 0x4E4F534A) { "erster Block nicht JSON" }
        val json = Json.parseToJsonElement(String(glb, 20, jsonLen, Charsets.UTF_8)).jsonObject
        val binStart = 20 + jsonLen + 8
        val out = glb.copyOf()
        val materials = json["materials"]?.jsonArray ?: return out
        val kit = materials.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == KIT }
        if (kit < 0) return out
        val accessors = json["accessors"]!!.jsonArray
        val views = json["bufferViews"]!!.jsonArray
        val done = HashSet<Int>()
        for (mesh in json["meshes"]!!.jsonArray) {
            for (p in mesh.jsonObject["primitives"]!!.jsonArray) {
                val po = p.jsonObject
                if (po["material"]?.jsonPrimitive?.int != kit) continue
                val ci = (po["attributes"] as JsonObject)["COLOR_0"]?.jsonPrimitive?.int ?: continue
                if (!done.add(ci)) continue
                val acc = accessors[ci].jsonObject
                if (acc["componentType"]!!.jsonPrimitive.int != 5121) continue // nur UNSIGNED_BYTE
                val comps = if (acc["type"]!!.jsonPrimitive.content == "VEC4") 4 else 3
                val view = views[acc["bufferView"]!!.jsonPrimitive.int].jsonObject
                val stride = view["byteStride"]?.jsonPrimitive?.int ?: comps
                val base = binStart + (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (acc["byteOffset"]?.jsonPrimitive?.int ?: 0)
                val count = acc["count"]!!.jsonPrimitive.int
                for (k in 0 until count) {
                    val o = base + k * stride
                    val r = out[o].toInt() and 0xFF
                    val g = out[o + 1].toInt() and 0xFF
                    val b = out[o + 2].toInt() and 0xFF
                    if (!isJersey(r, g, b)) continue
                    val f = (r / REF_RED).coerceIn(0.0, 1.3)
                    out[o] = toByte(jersey[0] * f)
                    out[o + 1] = toByte(jersey[1] * f)
                    out[o + 2] = toByte(jersey[2] * f)
                }
            }
        }
        return out
    }

    /** Trikotrot (auch die dunkle Variante): Rot deutlich ueber Gruen und Blau. */
    fun isJersey(r: Int, g: Int, b: Int): Boolean = r >= 40 && r > 2.5 * g && r > 2.5 * b

    private fun toByte(v: Double): Byte = (v * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte()
}
