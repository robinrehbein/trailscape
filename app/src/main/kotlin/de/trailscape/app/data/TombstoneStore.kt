package de.trailscape.app.data

import de.trailscape.core.RideTombstone
import de.trailscape.core.tombstonesFromJsonString
import de.trailscape.core.tombstonesToJsonString
import java.io.File

/**
 * Persistenz fuer die Loesch-Merkzettel (Tombstones) des Selfhost-Syncs.
 *
 * Eine einzelne JSON-Datei `<filesDir>/rides/tombstones.json` mit einer Liste
 * von `{id, deletedAt}` — die (De-)Serialisierung liegt als reine Funktionen
 * in `:core` (SyncTombstones.kt), hier steht nur das Datei-Handling. Die Datei
 * liegt bewusst im selben Verzeichnis wie die Tour-Dateien; [RideStorage]
 * ueberspringt sie beim Einlesen von selbst, weil sie kein gueltiges
 * Ride-Objekt enthaelt (JSON-Array statt -Objekt).
 *
 * Geschrieben wird atomar (tmp + rename) wie in [RideStorage] — ohne das
 * dortige `fsync`: Ein im Absturzfall verlorener Tombstone bedeutet
 * schlimmstenfalls, dass eine geloeschte Tour beim naechsten Sync wieder
 * auftaucht und erneut geloescht werden muss, keinen Datenverlust.
 *
 * Alle Methoden sind synchron; Aufrufer sind fuer `Dispatchers.IO` selbst
 * verantwortlich (gleiche Konvention wie [RideStorage]).
 */
class TombstoneStore(private val ridesDir: File) {

    private fun tombstoneFile(): File {
        if (!ridesDir.exists()) {
            ridesDir.mkdirs()
        }
        return File(ridesDir, "tombstones.json")
    }

    /** Liest alle Tombstones; fehlende oder kaputte Datei ergibt eine leere Liste. */
    fun list(): List<RideTombstone> {
        val file = tombstoneFile()
        if (!file.exists()) return emptyList()
        return try {
            tombstonesFromJsonString(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Legt einen Tombstone fuer [id] an (Zeitpunkt: jetzt). Ein bereits
     * vorhandener Tombstone derselben Tour wird ersetzt — massgeblich ist die
     * letzte Loeschung.
     */
    fun add(id: String, deletedAt: Long = System.currentTimeMillis()) {
        replaceAll(list().filterNot { it.id == id } + RideTombstone(id = id, deletedAt = deletedAt))
    }

    /**
     * Ersetzt den kompletten Bestand — benutzt vom Sync, der nach dem
     * Abgleich den bereinigten Satz zurueckschreibt (verfallene Tombstones
     * raus, remote Loeschungen rein).
     */
    fun replaceAll(tombstones: List<RideTombstone>) {
        val file = tombstoneFile()
        val tmpFile = File(ridesDir, "${file.name}.tmp")
        try {
            tmpFile.writeText(tombstonesToJsonString(tombstones), Charsets.UTF_8)
            if (!tmpFile.renameTo(file)) {
                // Fallback wie in RideStorage.saveRide: nicht atomar, aber
                // besser als gar nicht geschrieben.
                file.writeText(tombstonesToJsonString(tombstones), Charsets.UTF_8)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            // Bewusst geschluckt: Ein nicht geschriebener Tombstone darf weder
            // das Loeschen einer Tour noch den Sync scheitern lassen (siehe
            // Klassen-KDoc — die Folge ist nur ein moegliches Wiederauftauchen).
        }
    }
}
