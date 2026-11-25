package de.trailscape.app.challenge

import de.trailscape.core.challenge.CHALLENGE_FILE_EXTENSION
import de.trailscape.core.challenge.CHALLENGE_MAX_BYTES
import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeParse
import de.trailscape.core.challenge.parseChallenge
import java.io.File

/**
 * # Teilbare eigene Indoor-Touren
 *
 * „Herausforderung teilen" steht im Blatt „Teilen" der gespeicherten Tour
 * (`docs/ux/umbau.md`), nicht mehr in der Auswertung. Was dafuer noetig ist —
 * die Strecke samt Version und die Zeit-Weg-Kurve —, kennt aber nur der
 * Indoor-Ablauf am Ende der Fahrt; die gespeicherte Tour traegt es nicht.
 * Deshalb legt der Ablauf beim Speichern einer teilbaren Tour hier einen
 * **Entwurf** ab: dieselbe Datei wie eine Herausforderung
 * (`core/.../challenge/Challenge.kt`), ohne Namen und ohne Avatar, mit dem Tag
 * der Fahrt. Name und Datum waehlt man erst beim Teilen.
 *
 * Je Tour eine Datei `<Tour-ID>.<n|r>.trailscape-challenge` im privaten
 * App-Verzeichnis ([dir]); `r` heisst: Die Fahrt ging gegen eine
 * Herausforderung, geteilt wird sie „als Antwort". Nichts davon verlaesst das
 * Geraet, bevor man teilt. Hoechstens [MAX_ENTRIES] Entwuerfe; der aelteste
 * weicht. Kein Android: `java.io` genuegt, der Test laeuft als JVM-Test.
 */
class ChallengeDraftStore(private val dir: File) {

    /**
     * @property challenge Strecke, Kurve und Tag der Fahrt; Name leer.
     * @property reply die Fahrt ging gegen eine Herausforderung.
     */
    data class Draft(val challenge: Challenge, val reply: Boolean)

    /** Legt den Entwurf zur Tour ab (ersetzt einen frueheren). `false`, wenn das nicht ging. */
    fun save(rideId: String, challenge: Challenge, reply: Boolean): Boolean {
        if (!ID.matches(rideId)) return false
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            delete(rideId)
            val target = File(dir, fileName(rideId, reply))
            val tmp = File(dir, "$rideId.tmp")
            tmp.writeText(challenge.toJson(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            prune()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Der Entwurf zur Tour; `null`, wenn es keinen gibt oder die Datei nicht (mehr) lesbar ist. */
    fun load(rideId: String): Draft? {
        if (!ID.matches(rideId)) return null
        for (reply in listOf(false, true)) {
            val f = File(dir, fileName(rideId, reply))
            if (!f.isFile || f.length() > CHALLENGE_MAX_BYTES) continue
            val parsed = runCatching { parseChallenge(f.readBytes()) }.getOrNull() as? ChallengeParse.Ok ?: continue
            return Draft(parsed.challenge, reply)
        }
        return null
    }

    /** Entfernt den Entwurf zur Tour (etwa wenn die Tour geloescht wird). */
    fun delete(rideId: String) {
        if (!ID.matches(rideId)) return
        File(dir, fileName(rideId, reply = false)).delete()
        File(dir, fileName(rideId, reply = true)).delete()
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_ENTRIES).forEach { it.delete() }
    }

    companion object {
        const val MAX_ENTRIES: Int = 200
        private const val SUFFIX = ".$CHALLENGE_FILE_EXTENSION"

        /** Tour-IDs sind UUIDs oder aehnlich schlichte Kennungen; alles andere wird nicht zum Dateinamen. */
        private val ID = Regex("[A-Za-z0-9_-]{1,80}")

        internal fun fileName(rideId: String, reply: Boolean): String = "$rideId.${if (reply) "r" else "n"}$SUFFIX"
    }
}
