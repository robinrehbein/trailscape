package de.trailscape.app.feedback

/**
 * Holt den lesbaren Text aus dem Absturzprotokoll, das Android fuer einen
 * nativen Absturz aufbewahrt (`ApplicationExitInfo.getTraceInputStream`, ein
 * Protobuf „Tombstone").
 *
 * Bewusst **ohne** Protobuf-Bibliothek und ohne Feldnummern: Der Leser laeuft
 * die Nachricht generisch ab und sammelt jede Zeichenkette ein — Abbruchmeldung
 * (bei Filament z. B. „OpenGL program … failed to link or compile"), Funktions-
 * und Bibliotheksnamen der Aufrufkette, die letzten Logzeilen des Prozesses.
 * Das genuegt, um die Ursache zu erkennen, und haengt nicht an einer
 * bestimmten Android-Version.
 */
object TombstoneText {

    private const val MAX_DEPTH = 8
    private const val MAX_STRINGS = 20_000
    private const val HEAD_LINES = 140
    private const val KEY_LINES = 160
    private const val MAX_LINE_CHARS = 600

    private val KEYWORDS = listOf(
        "filament", "gltfio", "trailscape", "abort", "panic", "failed", "precondition", "postcondition",
        "opengl", "glsl", "shader", "error", "fatal", "signal", "sig",
    )

    /** Lesbarer Auszug, oder `null`, wenn nichts Brauchbares darin steht. */
    fun extract(bytes: ByteArray): String? {
        val all = ArrayList<String>()
        runCatching { walk(bytes, 0, bytes.size, 0, all) }
        val seen = HashSet<String>()
        val unique = all.map { it.trim().take(MAX_LINE_CHARS) }.filter { it.length >= 3 && seen.add(it) }
        if (unique.isEmpty()) return null
        val head = unique.take(HEAD_LINES)
        val headSet = head.toHashSet()
        val key = unique.asSequence()
            .drop(HEAD_LINES)
            .filter { s -> s !in headSet && KEYWORDS.any { s.contains(it, ignoreCase = true) } }
            .toList()
            .takeLast(KEY_LINES)
        return buildString {
            head.forEach { appendLine(it) }
            if (key.isNotEmpty()) {
                appendLine("…")
                key.forEach { appendLine(it) }
            }
        }.trimEnd()
    }

    /** `true`, wenn der Bereich eine gueltige Protobuf-Nachricht war. */
    private fun walk(b: ByteArray, start: Int, end: Int, depth: Int, out: MutableList<String>): Boolean {
        var i = start
        while (i < end) {
            if (out.size >= MAX_STRINGS) return true
            val (tag, afterTag) = varint(b, i, end) ?: return false
            i = afterTag
            if (tag ushr 3 == 0L) return false
            when ((tag and 7).toInt()) {
                0 -> i = (varint(b, i, end) ?: return false).second
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val (len, afterLen) = varint(b, i, end) ?: return false
                    if (len < 0 || len > end - afterLen) return false
                    val s = afterLen
                    val e = afterLen + len.toInt()
                    val text = text(b, s, e)
                    if (text != null) {
                        out.add(text)
                    } else if (depth < MAX_DEPTH && e > s) {
                        val mark = out.size
                        // Kein Text und keine Nachricht: Rohbytes (Speicherauszug) — verwerfen.
                        if (!walk(b, s, e, depth + 1, out)) while (out.size > mark) out.removeAt(out.size - 1)
                    }
                    i = e
                }
                else -> return false
            }
            if (i > end) return false
        }
        return true
    }

    private fun varint(b: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < end && shift < 64) {
            val x = b[i].toInt() and 0xff
            i++
            result = result or ((x and 0x7f).toLong() shl shift)
            if (x and 0x80 == 0) return result to i
            shift += 7
        }
        return null
    }

    /** Der Bereich als Text, wenn er nur druckbare Zeichen enthaelt. */
    private fun text(b: ByteArray, start: Int, end: Int): String? {
        if (end - start < 3) return null
        for (i in start until end) {
            val x = b[i].toInt() and 0xff
            if (x < 0x20 && x != 0x09 && x != 0x0a && x != 0x0d) return null
            if (x == 0x7f) return null
        }
        val s = String(b, start, end - start, Charsets.UTF_8)
        return if (s.contains('�')) null else s
    }
}
