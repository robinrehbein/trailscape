package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client für den optionalen Trailscape-Selfhost-Sync-Server (server/).
 *
 * 1:1-Portierung von `lib/sync_client.dart`. Spiegelt die Logik der früheren
 * Web-App (sync.ts): Konfiguration wird ueber [KeyValueStore] abgelegt
 * (Analogon zu Darts `shared_preferences`), und [syncRides] gleicht lokale
 * Touren gegen den Server ab (fehlende lokal hochladen, fehlende remote
 * holen).
 *
 * Nutzt fuer Netzwerk-Requests die plattformneutrale [HttpClient]-Abstraktion
 * (siehe HttpClient.kt) — eine echte Implementierung folgt in Phase 3 in
 * `:app`.
 */

private const val STORAGE_KEY = "trailscape.sync"

/**
 * Schmale, plattformneutrale Key-Value-Speicher-Abstraktion.
 *
 * Analogon zu Darts `shared_preferences` (das es in Kotlin/JVM nicht gibt).
 * Eine echte Implementierung (`android.content.SharedPreferences` o. Ä.)
 * folgt in Phase 3 in `:app`.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun setString(key: String, value: String)
    fun remove(key: String)
}

data class SyncConfig(val url: String, val token: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        put("token", token)
    }

    companion object {
        fun fromJson(json: JsonObject): SyncConfig = SyncConfig(
            url = json.requiredString("url"),
            token = json.requiredString("token"),
        )
    }
}

data class SyncResult(val pushed: Int, val pulled: Int, val total: Int)

private data class RemoteRideSummary(val id: String, val name: String)

private fun normalizeUrl(url: String): String {
    var normalized = url.trim()
    while (normalized.endsWith("/")) {
        normalized = normalized.substring(0, normalized.length - 1)
    }
    return normalized
}

/** Liest die gespeicherte Sync-Konfiguration aus [store]. */
fun getSyncConfig(store: KeyValueStore): SyncConfig? {
    val raw = store.getString(STORAGE_KEY) ?: return null
    return try {
        val parsed = Json.parseToJsonElement(raw)
        if (parsed is JsonObject) SyncConfig.fromJson(parsed) else null
    } catch (e: Exception) {
        null
    }
}

/**
 * Speichert (oder löscht bei `null`) die Sync-Konfiguration in [store]. Die
 * URL wird beim Speichern normalisiert (getrimmt, abschließende Slashes
 * entfernt).
 */
fun setSyncConfig(store: KeyValueStore, config: SyncConfig?) {
    if (config == null) {
        store.remove(STORAGE_KEY)
        return
    }
    val normalized = SyncConfig(
        url = normalizeUrl(config.url),
        token = config.token.trim(),
    )
    store.setString(STORAGE_KEY, normalized.toJson().toString())
}

private fun authHeaders(config: SyncConfig): Map<String, String> =
    mapOf("Authorization" to "Bearer ${config.token}")

private fun fetchRemoteRides(client: HttpClient, config: SyncConfig): List<RemoteRideSummary> {
    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.GET,
                url = "${config.url}/api/rides",
                headers = authHeaders(config),
            ),
        )
    } catch (e: Exception) {
        throw Exception("Sync-Server nicht erreichbar.")
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
        if (response.statusCode == 401) {
            throw Exception("Token wird vom Server abgelehnt.")
        }
        throw Exception("Sync fehlgeschlagen (HTTP ${response.statusCode}).")
    }

    // Wie im Original bewusst ungeschuetzt: kaputtes JSON hier wirft die
    // rohe Parse-/Cast-Exception durch, statt in eine deutsche Meldung
    // uebersetzt zu werden (Dart: `jsonDecode(response.body) as List`, ohne
    // try/catch).
    val data = Json.parseToJsonElement(response.body) as JsonArray
    return data.map { entry ->
        entry as JsonObject
        RemoteRideSummary(
            id = (entry["id"] as JsonPrimitive).content,
            name = (entry["name"] as JsonPrimitive).content,
        )
    }
}

private fun pushRide(client: HttpClient, config: SyncConfig, ride: Ride) {
    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.PUT,
                url = "${config.url}/api/rides/${ride.id}",
                headers = authHeaders(config) + ("Content-Type" to "application/json"),
                body = ride.toJson().toString(),
            ),
        )
    } catch (e: Exception) {
        throw Exception(
            "Hochladen der Tour \"${ride.name}\" fehlgeschlagen: Sync-Server nicht erreichbar.",
        )
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception(
            "Hochladen der Tour \"${ride.name}\" fehlgeschlagen (HTTP ${response.statusCode}).",
        )
    }
}

private fun isValidRideJson(data: JsonElement?): Boolean {
    if (data !is JsonObject) return false
    val id = data["id"] as? JsonPrimitive
    val name = data["name"] as? JsonPrimitive
    val points = data["points"]
    return id != null && id.isString && name != null && name.isString && points is JsonArray
}

private fun pullRide(client: HttpClient, config: SyncConfig, entry: RemoteRideSummary): Ride {
    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.GET,
                url = "${config.url}/api/rides/${entry.id}",
                headers = authHeaders(config),
            ),
        )
    } catch (e: Exception) {
        throw Exception(
            "Herunterladen der Tour \"${entry.name}\" fehlgeschlagen: Sync-Server nicht erreichbar.",
        )
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception(
            "Herunterladen der Tour \"${entry.name}\" fehlgeschlagen (HTTP ${response.statusCode}).",
        )
    }

    val data = try {
        Json.parseToJsonElement(response.body)
    } catch (e: Exception) {
        throw Exception(
            "Herunterladen der Tour \"${entry.name}\" fehlgeschlagen: ungültige Daten vom Server.",
        )
    }

    if (!isValidRideJson(data)) {
        throw Exception(
            "Herunterladen der Tour \"${entry.name}\" fehlgeschlagen: ungültige Daten vom Server.",
        )
    }

    return Ride.fromJson(data as JsonObject)
}

/**
 * Gleicht lokale Touren mit dem konfigurierten Sync-Server ab: fehlende
 * lokale Touren werden hochgeladen, fehlende remote Touren heruntergeladen
 * und lokal gespeichert. Läuft sequenziell, wie die Referenz-Web-App.
 *
 * [listLocal]/[saveLocal] und [client]/[store] werden injiziert — in Dart
 * waren `listLocal`/`saveLocal` bereits `Future`-Callbacks (hier synchron,
 * siehe HttpClient.kt), Client und Store haben in Dart kein Aequivalent zur
 * Injektion gebraucht (echter `http.Client`/`SharedPreferences` intern).
 */
fun syncRides(
    listLocal: () -> List<Ride>,
    saveLocal: (Ride) -> Unit,
    client: HttpClient,
    store: KeyValueStore,
): SyncResult {
    val config = getSyncConfig(store) ?: throw Exception("Sync ist nicht konfiguriert.")

    val remoteRides = fetchRemoteRides(client, config)
    val remoteIds = remoteRides.map { it.id }.toSet()

    val localRides = listLocal()
    val localIds = localRides.map { it.id }.toSet()

    var pushed = 0
    for (ride in localRides) {
        if (ride.id !in remoteIds) {
            pushRide(client, config, ride)
            pushed++
        }
    }

    var pulled = 0
    for (entry in remoteRides) {
        if (entry.id !in localIds) {
            val ride = pullRide(client, config, entry)
            saveLocal(ride)
            pulled++
        }
    }

    return SyncResult(
        pushed = pushed,
        pulled = pulled,
        total = localRides.size + pulled,
    )
}
