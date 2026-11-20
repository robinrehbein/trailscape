package de.trailscape.app.data

import de.trailscape.core.RiddenWaysStore
import de.trailscape.core.StoredRiddenWays
import de.trailscape.core.readRiddenWaysCache
import de.trailscape.core.writeRiddenWaysCache
import java.io.File

/**
 * Persistenter Cache der gefahrenen Wege je Tour (siehe `:core`,
 * `RiddenWays.kt`) — eine Binaerdatei `<filesDir>/route-cache/ridden-ways.bin`.
 *
 * Zuschnitt wie [RouteSignatureCacheStore] nebenan, aus denselben Gruenden:
 *
 *  * **Nicht in `rides/`** — dort liest `RideStorage` fremde Dateien als
 *    Touren und verschiebt sie in die Quarantaene.
 *  * **Persistent**, weil die Zellen erst beim Durchlaufen aller GPS-Punkte
 *    einer Tour abfallen. Mit Cache passiert das je Tour genau einmal; ein
 *    geloeschter Eintrag kostet beim Neuaufbau nur Sortieren, kein Laden.
 *  * **Nur waehrend einer Benutzung im Speicher**: [flush] (vom Sammellauf
 *    am Ende jedes Laufs gerufen) schreibt bei Aenderungen und gibt die
 *    Eintraege frei. Bei 10 000 km Bestand sind das ~600 000 Zellen, im
 *    Speicher knapp 5 MB — die sollen nicht dauerhaft am Singleton haengen.
 *    Der fertige Index haelt das ViewModel.
 *
 * ## Dateiformat
 * Binaer statt JSON (Aufbau in `:core`, [writeRiddenWaysCache]): Zellen in
 * Fahrtreihenfolge als Differenzen in Varints, rund 2 Byte je Zelle — gut
 * 1,2 MB fuer 10 000 km. Als JSON-Zahlenfeld waeren es acht- bis zehnmal so
 * viel. Fremde, kaputte oder anders versionierte Dateien gelten als leerer
 * Cache und werden beim naechsten [flush] ersetzt.
 *
 * Geschrieben wird atomar per tmp + rename, ohne `fsync`: Ein verlorener
 * Cache kostet nur Neuberechnung. Alle Methoden sind `@Synchronized`; der
 * Sammellauf laeuft auf `Dispatchers.IO`.
 */
class RiddenWaysCacheStore(private val dir: File) : RiddenWaysStore {

    private var entriesCache: MutableMap<String, StoredRiddenWays>? = null
    private var dirty = false

    private fun cacheFile(): File = File(dir, FILE_NAME)

    private fun entries(): MutableMap<String, StoredRiddenWays> {
        entriesCache?.let { return it }
        val file = cacheFile()
        var loaded: MutableMap<String, StoredRiddenWays>? = null
        if (file.exists()) {
            loaded = try {
                file.inputStream().buffered().use { readRiddenWaysCache(it) }
            } catch (e: Exception) {
                null
            }
            if (loaded == null) dirty = true
        }
        return (loaded ?: LinkedHashMap()).also { entriesCache = it }
    }

    @Synchronized
    override fun get(id: String): StoredRiddenWays? = entries()[id]

    @Synchronized
    override fun put(id: String, entry: StoredRiddenWays) {
        entries()[id] = entry
        dirty = true
    }

    @Synchronized
    override fun retainAll(ids: Set<String>) {
        if (entries().keys.retainAll(ids)) dirty = true
    }

    /**
     * Schreibt den Stand fest, falls sich etwas geaendert hat, und gibt die
     * Eintraege in jedem Fall frei (naechste Benutzung liest neu).
     */
    @Synchronized
    override fun flush() {
        val map = entriesCache
        try {
            if (map != null && dirty) {
                if (!dir.exists()) dir.mkdirs()
                val file = cacheFile()
                val tmp = File(dir, "${file.name}.tmp")
                tmp.outputStream().buffered().use { writeRiddenWaysCache(map, it) }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            // Bewusst geschluckt: Ein nicht geschriebener Cache kostet nur
            // Neuberechnung beim naechsten Mal.
        } finally {
            entriesCache = null
            dirty = false
        }
    }

    companion object {
        /** Dateiname des Wege-Caches. */
        const val FILE_NAME: String = "ridden-ways.bin"

        /** Verzeichnis unter `filesDir` — dasselbe wie beim Signatur-Cache. */
        const val DIR_NAME: String = RouteSignatureCacheStore.DIR_NAME
    }
}
