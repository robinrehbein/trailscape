package de.trailscape.app.data

import de.trailscape.core.RouteSignatureStore
import de.trailscape.core.StoredRouteSignature
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persistenter Cache der Strecken-Signaturen je Tour (siehe `:core`,
 * `RouteMatch.kt`) — eine einzelne JSON-Datei
 * `<filesDir>/route-cache/route-signatures.json`.
 *
 * ## Warum nicht in `rides/`
 * `RideStorage.listSummaries` liest jede `.json` im Touren-Verzeichnis als
 * Tour und verschiebt, was keine ist, nach `defekt/` — ausser den Namen auf
 * seiner Liste reservierter Dateien. Dieser Cache steht nicht darauf; im
 * Touren-Verzeichnis landete er nach jedem Neuladen in der Quarantaene (samt
 * Meldung „Tour-Datei unlesbar"). Deshalb ein eigenes Verzeichnis
 * ([DIR_NAME]).
 *
 * ## Warum persistent
 * Dieselbe Rechnung wie beim Kachel-Cache nebenan ([ExplorerTilesCacheStore]):
 * Durch welche ~100-m-Zellen eine Tour fuehrte, faellt erst beim Durchlaufen
 * ihrer GPS-Punkte ab. Ohne Cache muesste „Diese Runde bist du 5× gefahren"
 * bei jedem Oeffnen einer Tour den ganzen Bestand laden. Mit Cache passiert
 * der Punktdurchlauf je Tour genau einmal; danach genuegen die
 * Zusammenfassungen.
 *
 * ## Invalidierung
 * Die uebernimmt der Eintrag selbst ([StoredRouteSignature]): `updatedAt` der
 * Tour plus die Zahl ihrer Punkte. Diese Klasse haelt nur Eintraege vor und
 * wirft per [retainAll] weg, was zu geloeschten Touren gehoert. Eine Tour
 * ohne Signatur (zu kurz) steht mit `signature: null` drin — sonst wuerde sie
 * bei jedem Lauf erneut geladen.
 *
 * ## Dateiformat
 * `{"version":1,"entries":[{"id":"…", …}]}`; jeder Eintrag ist die
 * `:core`-Form [StoredRouteSignature.toJson] plus `id`. Die Zellen stehen dort
 * als sortiertes Zahlenfeld — bei einer 40-km-Runde einige hundert Zahlen.
 * Gelesen wird je Eintrag tolerant ([StoredRouteSignature.fromJson]): Ein
 * kaputter Eintrag faellt weg und wird neu berechnet, der Rest bleibt.
 *
 * Geschrieben wird gesammelt in [flush] (der Aufrufer ruft es einmal nach dem
 * Sammellauf), atomar per tmp + rename und ohne `fsync`: Ein im Absturz
 * verlorener Cache kostet nur Neuberechnung, nie Daten. Eine kaputte Datei
 * liest sich als leerer Cache — gleicher Effekt.
 *
 * Alle Methoden sind synchron und `@Synchronized`; der Sammellauf laeuft auf
 * `Dispatchers.IO`.
 */
class RouteSignatureCacheStore(private val dir: File) : RouteSignatureStore {

    private var entriesCache: MutableMap<String, StoredRouteSignature>? = null
    private var dirty = false

    private fun cacheFile(): File = File(dir, FILE_NAME)

    private fun entries(): MutableMap<String, StoredRouteSignature> {
        entriesCache?.let { return it }
        val loaded = LinkedHashMap<String, StoredRouteSignature>()
        val file = cacheFile()
        if (file.exists()) {
            try {
                val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
                val list = root["entries"] as? JsonArray ?: JsonArray(emptyList())
                for (element in list) {
                    val obj = element as? JsonObject ?: continue
                    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    val entry = StoredRouteSignature.fromJson(obj) ?: continue
                    loaded[id] = entry
                }
            } catch (e: Exception) {
                // Kaputter Cache = leerer Cache; die Signaturen werden neu
                // gerechnet und die Datei beim naechsten flush ersetzt.
                loaded.clear()
                dirty = true
            }
        }
        entriesCache = loaded
        return loaded
    }

    @Synchronized
    override fun read(rideId: String): StoredRouteSignature? = entries()[rideId]

    @Synchronized
    override fun write(rideId: String, value: StoredRouteSignature) {
        entries()[rideId] = value
        dirty = true
    }

    @Synchronized
    override fun remove(rideId: String) {
        if (entries().remove(rideId) != null) {
            dirty = true
        }
    }

    /** Wirft alle Eintraege weg, deren ID nicht in [ids] liegt (geloeschte oder geplante Touren). */
    @Synchronized
    fun retainAll(ids: Set<String>) {
        val map = entries()
        if (map.keys.retainAll(ids)) {
            dirty = true
        }
    }

    /** Schreibt den Stand fest, falls sich seit dem letzten Mal etwas geaendert hat. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        val map = entries()
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val json = buildJsonObject {
                put("version", 1)
                put(
                    "entries",
                    buildJsonArray {
                        map.forEach { (id, entry) ->
                            add(JsonObject(mapOf("id" to JsonPrimitive(id)) + entry.toJson()))
                        }
                    },
                )
            }
            val file = cacheFile()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            dirty = false
        } catch (e: Exception) {
            // Bewusst geschluckt: Ein nicht geschriebener Cache kostet nur
            // Neuberechnung beim naechsten Mal.
        }
    }

    companion object {
        /** Dateiname des Signatur-Caches. */
        const val FILE_NAME: String = "route-signatures.json"

        /** Verzeichnis unter `filesDir` — bewusst nicht `rides/` (siehe oben). */
        const val DIR_NAME: String = "route-cache"
    }
}
