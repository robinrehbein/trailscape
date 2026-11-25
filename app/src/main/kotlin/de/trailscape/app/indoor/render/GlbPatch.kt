package de.trailscape.app.indoor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Entschaerft `KHR_materials_specular` in einer GLB, bevor gltfio sie liest.
 *
 * Grund (Absturz auf dem Geraet, Issue 106): Mit einem Kotlin-`MaterialProvider`
 * reicht gltfios JNI-Schicht den Materialschluessel ueber eine Java-Klasse
 * durch, die das Feld `hasSpecular` nicht kennt. Der Ubershader waehlt deshalb
 * ein Material **ohne** Spiegel-Parameter, gltfio setzt danach trotzdem
 * `specularColorFactor` — und Filament beendet den Prozess
 * („uniform named specularColorFactor not found").
 *
 * Die Welt benutzt die Erweiterung nur fuer Vegetation (`specularFactor` 0).
 * Das eigene Laubmaterial stellt den Spiegelanteil ohnehin selbst ab, also
 * genuegt es, die Erweiterung fuer gltfio unsichtbar zu machen: Ihr Name wird
 * im JSON-Teil **gleich lang** ueberschrieben, die Datei bleibt sonst Byte fuer
 * Byte dieselbe. Unbekannte Erweiterungen ueberliest gltfio.
 */
internal object GlbPatch {
    private val NEEDLE = "\"KHR_materials_specular\"".toByteArray(Charsets.US_ASCII)
    private val REPLACEMENT = "\"ZZZ_materials_specular\"".toByteArray(Charsets.US_ASCII)
    private const val MAGIC_GLTF = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A

    /** Aendert [glb] an Ort und Stelle (Position und Grenze bleiben); gibt die Zahl der Ersetzungen zurueck. */
    fun neutralizeSpecular(glb: ByteBuffer): Int {
        val b = glb.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = b.position()
        if (b.remaining() < 20) return 0
        if (b.getInt(base) != MAGIC_GLTF || b.getInt(base + 16) != CHUNK_JSON) return 0
        val jsonLen = b.getInt(base + 12)
        val start = base + 20
        if (jsonLen <= 0 || jsonLen > b.limit() - start) return 0
        val end = start + jsonLen - NEEDLE.size
        var count = 0
        var i = start
        val first = NEEDLE[0]
        val second = NEEDLE[1]
        while (i <= end) {
            if (b.get(i) == first && b.get(i + 1) == second && matches(b, i)) {
                for (k in REPLACEMENT.indices) glb.put(i + k, REPLACEMENT[k])
                count++
                i += NEEDLE.size
            } else {
                i++
            }
        }
        return count
    }

    private fun matches(b: ByteBuffer, at: Int): Boolean {
        for (k in NEEDLE.indices) if (b.get(at + k) != NEEDLE[k]) return false
        return true
    }
}
