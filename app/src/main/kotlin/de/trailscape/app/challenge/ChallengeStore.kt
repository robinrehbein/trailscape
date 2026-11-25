package de.trailscape.app.challenge

import de.trailscape.core.challenge.CHALLENGE_FILE_EXTENSION
import de.trailscape.core.challenge.CHALLENGE_MAX_BYTES
import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeParse
import de.trailscape.core.challenge.parseChallenge
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * # Gespeicherte Herausforderungen
 *
 * Je Herausforderung eine Datei `<Kennung>.trailscape-challenge` im privaten
 * App-Verzeichnis ([dir], etwa `<filesDir>/challenges`) — dasselbe Format wie
 * die geteilte Datei (`core/.../challenge/Challenge.kt`). Die Kennung ist der
 * Inhaltshash ([Challenge.id]): Dieselbe Datei zweimal zu importieren ergibt
 * keinen zweiten Eintrag.
 *
 * Die Dateien werden beim Lesen **erneut geprueft**; eine beschaedigte oder von
 * einer neueren App geschriebene Datei erscheint nicht in der Liste und
 * stoert nichts. Kein Android: `java.io` genuegt, der Test laeuft als
 * einfacher JVM-Test.
 */
class ChallengeStore(private val dir: File) {

    /** Eine gespeicherte Herausforderung mit dem Zeitpunkt des Speicherns (ms). */
    data class Entry(val challenge: Challenge, val savedAtMs: Long)

    enum class SaveResult {
        /** Neu gespeichert. */
        SAVED,

        /** Es gab sie schon (inhaltsgleich). */
        ALREADY_THERE,

        /** Die Sammlung ist voll ([MAX_ENTRIES]). */
        FULL,

        /** Schreiben schlug fehl. */
        FAILED,
    }

    /** Alle lesbaren Eintraege, neueste zuerst. */
    fun list(): List<Entry> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return emptyList()
        return files.mapNotNull { f ->
            if (f.length() > CHALLENGE_MAX_BYTES) return@mapNotNull null
            val parsed = runCatching { parseChallenge(f.readBytes()) }.getOrNull() as? ChallengeParse.Ok
                ?: return@mapNotNull null
            // Der Dateiname muss zum Inhalt passen, sonst ist es nicht unsere Datei.
            if (f.name != fileName(parsed.challenge.id)) return@mapNotNull null
            Entry(parsed.challenge, f.lastModified())
        }.sortedByDescending { it.savedAtMs }
    }

    fun get(id: String): Entry? = list().firstOrNull { it.challenge.id == id }

    fun save(challenge: Challenge, nowMs: Long): SaveResult {
        val target = File(dir, fileName(challenge.id))
        if (target.isFile) return SaveResult.ALREADY_THERE
        if ((dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.size ?: 0) >= MAX_ENTRIES) return SaveResult.FULL
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return SaveResult.FAILED
            val tmp = File(dir, "${challenge.id}.tmp")
            tmp.writeText(challenge.toJson(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return SaveResult.FAILED
            }
            target.setLastModified(nowMs)
            SaveResult.SAVED
        } catch (_: Exception) {
            SaveResult.FAILED
        }
    }

    /** `true`, wenn es sie gab und sie nun weg ist. */
    fun delete(id: String): Boolean {
        if (!ID.matches(id)) return false
        val f = File(dir, fileName(id))
        return f.isFile && f.delete()
    }

    companion object {
        const val MAX_ENTRIES: Int = 100
        private const val SUFFIX = ".$CHALLENGE_FILE_EXTENSION"
        private val ID = Regex("[0-9a-f]{16}")

        fun fileName(id: String): String = "$id$SUFFIX"
    }
}

/**
 * Liest höchstens [CHALLENGE_MAX_BYTES] aus [stream]; `null`, wenn mehr drinsteckt.
 * Nie mehr als die Grenze im Speicher, auch bei einer unendlichen Quelle.
 */
internal fun readBoundedChallenge(stream: InputStream): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        if (out.size() + n > CHALLENGE_MAX_BYTES) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
