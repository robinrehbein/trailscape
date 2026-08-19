package de.trailscape.app.data

import de.trailscape.core.RideLoadFactsStore
import de.trailscape.core.StoredRideLoadFacts
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persistenter Cache der Tourlast-Destillate (siehe `:core`,
 * `RideLoadFacts.kt`) — eine einzelne JSON-Datei
 * `<filesDir>/rides/last-cache.json`.
 *
 * ## Warum persistent
 * Das Destillat einer Tour entsteht aus ihren GPS-Punkten. Ohne persistenten
 * Cache muesste die Trainingsauswertung nach jedem App-Start saemtliche
 * Touren einmal voll laden — genau der Speicher- und IO-Berg, den die
 * Umstellung auf Zusammenfassungen abtragen soll. Mit Cache passiert der
 * Punktdurchlauf je Tour genau einmal; danach traegt `last-cache.json` die
 * paar Dutzend Zahlen je Tour.
 *
 * ## Invalidierung
 * Die uebernimmt der Eintrag selbst ([StoredRideLoadFacts]): `updatedAt` der
 * Tour plus Profil-Signatur. Diese Klasse haelt nur Eintraege vor und wirft
 * per [retainAll] weg, was zu geloeschten Touren gehoert — die Datei waechst
 * also nicht mit dem Papierkorb mit.
 *
 * Geschrieben wird gesammelt in [flush] (die Auswertung ruft es einmal am
 * Ende ihres Laufs), atomar per tmp + rename und ohne `fsync`: Ein im
 * Absturz verlorener Cache kostet nur Neuberechnung, nie Daten. Eine kaputte
 * Datei liest sich als leerer Cache — gleicher Effekt.
 *
 * Alle Methoden sind synchron und `@Synchronized`; die Auswertung laeuft auf
 * `Dispatchers.Default`, wo der seltene kleine Dateizugriff nicht auffaellt.
 */
class RideLoadCacheStore(private val ridesDir: File) : RideLoadFactsStore {

    private var entriesCache: MutableMap<String, StoredRideLoadFacts>? = null
    private var dirty = false

    private fun cacheFile(): File = File(ridesDir, FILE_NAME)

    private fun entries(): MutableMap<String, StoredRideLoadFacts> {
        entriesCache?.let { return it }
        val loaded = LinkedHashMap<String, StoredRideLoadFacts>()
        val file = cacheFile()
        if (file.exists()) {
            try {
                val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
                val list = root["entries"] as? JsonArray ?: JsonArray(emptyList())
                for (element in list) {
                    val obj = element as? JsonObject ?: continue
                    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    val entryObj = obj["entry"] as? JsonObject ?: continue
                    StoredRideLoadFacts.fromJson(entryObj)?.let { loaded[id] = it }
                }
            } catch (e: Exception) {
                // Kaputter Cache = leerer Cache; die Destillate werden neu
                // gerechnet und die Datei beim naechsten flush ersetzt.
                loaded.clear()
                dirty = true
            }
        }
        entriesCache = loaded
        return loaded
    }

    @Synchronized
    override fun get(id: String): StoredRideLoadFacts? = entries()[id]

    @Synchronized
    override fun put(id: String, entry: StoredRideLoadFacts) {
        entries()[id] = entry
        dirty = true
    }

    @Synchronized
    override fun retainAll(ids: Set<String>) {
        val map = entries()
        if (map.keys.retainAll(ids)) {
            dirty = true
        }
    }

    @Synchronized
    override fun flush() {
        if (!dirty) return
        val map = entries()
        try {
            if (!ridesDir.exists()) {
                ridesDir.mkdirs()
            }
            val json = buildJsonObject {
                put("version", 1)
                put(
                    "entries",
                    buildJsonArray {
                        map.forEach { (id, entry) ->
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put("entry", entry.toJson())
                                },
                            )
                        }
                    },
                )
            }
            val file = cacheFile()
            val tmp = File(ridesDir, "${file.name}.tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            dirty = false
        } catch (e: Exception) {
            // Bewusst geschluckt: Ein nicht geschriebener Cache kostet nur
            // Neuberechnung beim naechsten Start.
        }
    }

    companion object {
        /** Dateiname des Last-Caches im Touren-Verzeichnis. */
        const val FILE_NAME: String = "last-cache.json"
    }
}
