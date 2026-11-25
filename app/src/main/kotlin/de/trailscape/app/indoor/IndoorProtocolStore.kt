package de.trailscape.app.indoor

import java.io.File

/**
 * Die Fahrprotokolle (`core/.../RideProtocol.kt`) als Textdateien im privaten
 * Speicher der App: `<filesDir>/diag/fahrprotokolle/`. Das liegt unter `diag/`
 * und ist damit — wie das Diagnose-Log — vom Auto Backup ausgenommen
 * (`res/xml/backup_rules.xml`): Es verlässt das Gerät nur, wenn der Nutzer es
 * selbst teilt.
 *
 * Es bleiben höchstens [MAX_FILES] Dateien (die ältesten gehen zuerst) und
 * höchstens [MAX_FILE_BYTES] je Datei. Reine Dateioperationen, ohne Android-Import,
 * damit sie sich als JVM-Test prüfen lassen.
 */
class IndoorProtocolStore(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    /** Ein gespeichertes Protokoll. */
    data class Entry(val file: File, val title: String, val modifiedMs: Long, val sizeBytes: Long)

    /** Legt ein Protokoll ab. Gibt die Datei zurück, bei Fehlern `null` (nie eine Ausnahme). */
    fun save(startedAtMs: Long, routeName: String, text: String): File? = runCatching {
        dir.mkdirs()
        val safe = routeName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("").take(24).ifEmpty { "fahrt" }
        val file = File(dir, "fahrt-$startedAtMs-$safe.txt")
        val bytes = text.toByteArray(Charsets.UTF_8)
        file.writeBytes(if (bytes.size > MAX_FILE_BYTES) bytes.copyOf(MAX_FILE_BYTES) else bytes)
        prune()
        file
    }.getOrNull()

    /** Neueste zuerst. */
    fun list(): List<Entry> = runCatching {
        dir.listFiles { f -> f.isFile && f.name.startsWith("fahrt-") && f.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { Entry(it, it.name.removePrefix("fahrt-").removeSuffix(".txt"), it.lastModified(), it.length()) }
    }.getOrDefault(emptyList())

    fun read(entry: Entry): String? = runCatching { entry.file.readText(Charsets.UTF_8) }.getOrNull()

    fun delete(entry: Entry): Boolean = runCatching { entry.file.delete() }.getOrDefault(false)

    /** Löscht alle Protokolle. */
    fun clear() {
        list().forEach { delete(it) }
    }

    private fun prune() {
        list().drop(maxFiles).forEach { delete(it) }
    }

    companion object {
        const val MAX_FILES: Int = 12
        const val MAX_FILE_BYTES: Int = 512 * 1024
    }
}
