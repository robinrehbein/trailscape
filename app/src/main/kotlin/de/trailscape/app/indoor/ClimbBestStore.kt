package de.trailscape.app.indoor

import de.trailscape.core.KeyValueStore
import de.trailscape.core.indoor.climb.ClimbBest
import de.trailscape.core.indoor.climb.ClimbBestBook

/**
 * # Bestzeiten an Anstiegen auf dem Geraet
 *
 * Ein einziger Eintrag im gemeinsamen [KeyValueStore] ([KEY]) im stabilen
 * JSON von [ClimbBestBook]. Lokal, ohne Konto und ohne Netz; das
 * Trailscape-Backup (Mehr → Daten & Backup) nimmt das Buch als Feld
 * `climbBests` mit, der Import fuehrt es zusammen (je Anstieg gewinnt die
 * schnellere Zeit). Fehler beim Lesen oder Schreiben sind nie ein Grund,
 * eine Fahrt zu stoeren: Dann gibt es eben keine Bestzeit.
 */
object ClimbBestStore {
    const val KEY: String = "trailscape.indoor.climb_bests"

    private val lock = Any()

    fun read(store: KeyValueStore): ClimbBestBook =
        runCatching { ClimbBestBook.fromJson(store.getString(KEY)) }.getOrDefault(ClimbBestBook())

    /** Traegt neue Bestzeiten ein (nur schnellere gewinnen). */
    fun record(store: KeyValueStore, bests: List<ClimbBest>) {
        if (bests.isEmpty()) return
        update(store) { book -> bests.fold(book) { acc, b -> acc.record(b) } }
    }

    /** Backup-Import: fuehrt [other] mit dem Bestand zusammen. */
    fun merge(store: KeyValueStore, other: ClimbBestBook) {
        if (other.isEmpty()) return
        update(store) { it.merge(other) }
    }

    private fun update(store: KeyValueStore, change: (ClimbBestBook) -> ClimbBestBook) {
        synchronized(lock) {
            runCatching {
                val next = change(read(store))
                store.setString(KEY, next.toJson())
            }
        }
    }
}
