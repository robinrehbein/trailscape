package de.trailscape.app.data

import de.trailscape.core.ExplorerTile
import de.trailscape.core.ExplorerTilesStore
import de.trailscape.core.StoredExplorerTiles
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persistenter Cache der Entdeckt-Kacheln je Tour (siehe `:core`,
 * `ExplorerTiles.kt`) — eine einzelne JSON-Datei
 * `<filesDir>/rides/explorer-tiles.json`.
 *
 * ## Warum persistent
 * Dieselbe Rechnung wie beim Tourlast-Cache nebenan ([RideLoadCacheStore]),
 * nur fuer eine andere Frage: Welche z14-Kacheln eine Tour beruehrt, faellt
 * erst beim Durchlaufen ihrer GPS-Punkte ab. Ohne Cache muesste der
 * Karten-Layer nach jedem App-Start saemtliche Touren einmal voll laden, nur
 * um ein paar Dutzend Kachelnummern je Tour wiederzufinden — bei grossen
 * Bestaenden der IO- und Speicherberg, den die Umstellung auf
 * Zusammenfassungen gerade abgetragen hat. Mit Cache passiert der
 * Punktdurchlauf je Tour genau einmal.
 *
 * ## Invalidierung
 * Die uebernimmt der Eintrag selbst ([StoredExplorerTiles]): `updatedAt` der
 * Tour plus die Zahl ihrer Punkte. Diese Klasse haelt nur Eintraege vor und
 * wirft per [retainAll] weg, was zu geloeschten Touren gehoert — die Datei
 * waechst also nicht mit dem Papierkorb mit.
 *
 * ## Dateiformat
 * Bewusst kompakt: Die Kachelliste steht als Feld `tiles` mit zweielementigen
 * Zahlen-Arrays (`[[8712,5623],…]`) statt als Objekte mit `x`/`y`-Namen.
 * Eine lange Tour beruehrt schnell einige Dutzend Kacheln; bei ein paar
 * hundert Touren macht die Kurzform den Unterschied zwischen einer Datei von
 * einigen zehn und einigen hundert Kilobyte, die bei jedem Start gelesen
 * wird. Lesbar bleibt sie trotzdem.
 *
 * Geschrieben wird gesammelt in [flush] (der Sammellauf ruft es einmal am
 * Ende), atomar per tmp + rename und ohne `fsync`: Ein im Absturz verlorener
 * Cache kostet nur Neuberechnung, nie Daten. Eine kaputte Datei liest sich
 * als leerer Cache — gleicher Effekt.
 *
 * Alle Methoden sind synchron und `@Synchronized`; der Sammellauf laeuft auf
 * `Dispatchers.IO`, wo der seltene kleine Dateizugriff nicht auffaellt.
 */
class ExplorerTilesCacheStore(private val ridesDir: File) : ExplorerTilesStore {

    private var entriesCache: MutableMap<String, StoredExplorerTiles>? = null
    private var dirty = false

    private fun cacheFile(): File = File(ridesDir, FILE_NAME)

    private fun entries(): MutableMap<String, StoredExplorerTiles> {
        entriesCache?.let { return it }
        val loaded = LinkedHashMap<String, StoredExplorerTiles>()
        val file = cacheFile()
        if (file.exists()) {
            try {
                val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
                val list = root["entries"] as? JsonArray ?: JsonArray(emptyList())
                for (element in list) {
                    val obj = element as? JsonObject ?: continue
                    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    val updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                    val pointCount = (obj["pointCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: continue
                    val tiles = readTiles(obj["tiles"] as? JsonArray) ?: continue
                    loaded[id] = StoredExplorerTiles(
                        updatedAt = updatedAt,
                        pointCount = pointCount,
                        tiles = tiles,
                    )
                }
            } catch (e: Exception) {
                // Kaputter Cache = leerer Cache; die Kacheln werden neu
                // gerechnet und die Datei beim naechsten flush ersetzt.
                loaded.clear()
                dirty = true
            }
        }
        entriesCache = loaded
        return loaded
    }

    /**
     * Liest `[[x,y],…]`. Ein einziges schiefes Paar verwirft den ganzen
     * Eintrag (`null`) statt eine halbe Kachelmenge zu liefern: Eine zu klein
     * geratene Menge saehe aus wie „hier war ich nie" und wuerde als Nebel
     * ueber bereits befahrenem Gelaende landen — die Neuberechnung derselben
     * Tour kostet dagegen nur einen Dateizugriff.
     */
    private fun readTiles(array: JsonArray?): List<ExplorerTile>? {
        if (array == null) return null
        val tiles = ArrayList<ExplorerTile>(array.size)
        for (element in array) {
            val pair = element as? JsonArray ?: return null
            if (pair.size != 2) return null
            val x = (pair[0] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            val y = (pair[1] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            tiles.add(ExplorerTile(x = x, y = y))
        }
        return tiles
    }

    @Synchronized
    override fun get(id: String): StoredExplorerTiles? = entries()[id]

    @Synchronized
    override fun put(id: String, entry: StoredExplorerTiles) {
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
                                    put("updatedAt", entry.updatedAt)
                                    put("pointCount", entry.pointCount)
                                    put(
                                        "tiles",
                                        buildJsonArray {
                                            entry.tiles.forEach { tile ->
                                                add(
                                                    buildJsonArray {
                                                        add(JsonPrimitive(tile.x))
                                                        add(JsonPrimitive(tile.y))
                                                    },
                                                )
                                            }
                                        },
                                    )
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
        /** Dateiname des Kachel-Caches im Touren-Verzeichnis. */
        const val FILE_NAME: String = "explorer-tiles.json"
    }
}
