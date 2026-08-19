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
 * Urspruenglich eine 1:1-Portierung von `lib/sync_client.dart` (reine
 * ID-Mengenvereinigung: fehlende lokal hochladen, fehlende remote holen).
 * Inzwischen ein echter **bidirektionaler** Abgleich:
 *
 *  * Aenderungen propagieren per Last-Write-Wins ueber [Ride.updatedAt] in
 *    beide Richtungen (Umbenennung, HF-Anreicherung, ...).
 *  * Loeschungen propagieren ueber Tombstones (siehe SyncTombstones.kt):
 *    lokale Tombstones werden als `DELETE` zum Server getragen, remote als
 *    `deleted` markierte Eintraege loeschen die Tour lokal. Eine Bearbeitung,
 *    die NEUER als die Loeschung ist, gewinnt gegen den Tombstone
 *    (Wiederbelebung).
 *  * Alte Server ohne `updatedAt`/`deleted` in der Liste funktionieren
 *    weiter — fehlende Felder werden toleriert, das Verhalten entspricht dann
 *    dem bisherigen (nur beidseitig Fehlendes wird uebertragen).
 *
 * Die eigentliche Entscheidung, was zu tun ist, trifft die **reine** Funktion
 * [planSync] (keine IO, direkt testbar); [syncRides] fuehrt den Plan nur noch
 * aus. Konfiguration wird ueber [KeyValueStore] abgelegt (Analogon zu Darts
 * `shared_preferences`); Netzwerk laeuft ueber die plattformneutrale
 * [HttpClient]-Abstraktion (siehe HttpClient.kt).
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

/**
 * Ergebnis eines [syncRides]-Laufs.
 *
 * [pushed]/[pulled] zaehlen ALLE Uploads bzw. Downloads; [updated] davon die,
 * die eine bereits beidseitig vorhandene Tour per Last-Write-Wins
 * aktualisiert haben. [deletedLocal]/[deletedRemote] zaehlen uebertragene
 * Loeschungen, [total] die lokalen Touren nach dem Abgleich.
 */
data class SyncResult(
    val pushed: Int,
    val pulled: Int,
    val total: Int,
    val updated: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
)

/**
 * Ein Eintrag der Server-Liste `GET /api/rides`.
 *
 * Neue Server liefern zusaetzlich [updatedAt] sowie fuer geloeschte Touren
 * `{id, deleted: true, deletedAt}`; alte Server nur `{id, name, createdAt}`.
 * Alle nachtraeglich ergaenzten Felder sind deshalb optional mit Defaults, die
 * das alte Verhalten nachbilden.
 */
data class RemoteRideSummary(
    val id: String,
    val name: String = "",
    /** ms seit Epoch; `null` bei alten Servern ohne das Feld. */
    val updatedAt: Long? = null,
    /** `true`, wenn der Eintrag ein Server-Tombstone ist. */
    val deleted: Boolean = false,
    /** ms seit Epoch; nur bei [deleted] gesetzt. */
    val deletedAt: Long? = null,
)

/** Die fuer die Sync-Entscheidung noetige Zusammenfassung einer lokalen Tour. */
data class LocalRideSummary(val id: String, val updatedAt: Long)

/** [LocalRideSummary] aus einer beliebigen Tour(-Zusammenfassung). */
fun RideInfo.toLocalRideSummary(): LocalRideSummary =
    LocalRideSummary(id = id, updatedAt = updatedAt)

/**
 * Das Ergebnis von [planSync]: welche Touren-IDs in welche Richtung wandern.
 *
 * `push*`/`pull*` sind nach „neu" (Gegenseite kennt die ID nicht) und
 * „aktualisiert" (Last-Write-Wins) getrennt — [syncRides] behandelt beide
 * gleich, die Trennung fuettert nur die Zaehler in [SyncResult].
 * [tombstonesAfterSync] ist der komplette lokale Tombstone-Bestand nach dem
 * Abgleich (verfallene Tombstones entfernt, remote Loeschungen ergaenzt) und
 * ersetzt den bisherigen.
 */
data class SyncPlan(
    val pushNew: List<String>,
    val pushUpdated: List<String>,
    val pullNew: List<String>,
    val pullUpdated: List<String>,
    val deleteRemote: List<String>,
    val deleteLocal: List<String>,
    val tombstonesAfterSync: List<RideTombstone>,
)

/**
 * Die reine Entscheidungsfunktion des Syncs — keine IO, deterministisch,
 * direkt testbar. Regeln je Tour-ID (Konflikt = Last-Write-Wins):
 *
 *  * nur lokal → push; nur remote (nicht geloescht) → pull.
 *  * beidseitig vorhanden → die Seite mit dem groesseren `updatedAt` gewinnt;
 *    gleichstand oder alter Server ohne `updatedAt` → nichts tun.
 *  * lokaler Tombstone → `DELETE` an den Server, AUSSER die Remote-Fassung
 *    wurde nach der Loeschung bearbeitet (`updatedAt > deletedAt`) — dann
 *    Wiederbelebung per Pull, der Tombstone verfaellt.
 *  * remote Tombstone (`deleted: true`) → lokale Tour loeschen und den
 *    Tombstone lokal uebernehmen (damit er nicht als eigene Loeschung erneut
 *    zum Server getragen wird, siehe [SyncPlan.tombstonesAfterSync]), AUSSER
 *    die lokale Fassung ist neuer (`updatedAt > deletedAt`) — dann
 *    Wiederbelebung per Push.
 *  * Ein lokaler Tombstone, den eine neuere lokale Datei ueberholt hat
 *    (`updatedAt > deletedAt`), ist hinfaellig und verfaellt.
 */
fun planSync(
    local: List<LocalRideSummary>,
    remote: List<RemoteRideSummary>,
    tombstones: List<RideTombstone>,
): SyncPlan {
    val localById = local.associateBy { it.id }
    val remoteById = remote.associateBy { it.id }
    val tombstoneById = tombstones.associateBy { it.id }

    val pushNew = mutableListOf<String>()
    val pushUpdated = mutableListOf<String>()
    val pullNew = mutableListOf<String>()
    val pullUpdated = mutableListOf<String>()
    val deleteRemote = mutableListOf<String>()
    val deleteLocal = mutableListOf<String>()
    val tombstonesAfterSync = mutableListOf<RideTombstone>()

    // Stabile Reihenfolge: erst lokale, dann remote, dann Tombstone-IDs.
    val ids = LinkedHashSet<String>()
    local.forEach { ids.add(it.id) }
    remote.forEach { ids.add(it.id) }
    tombstones.forEach { ids.add(it.id) }

    for (id in ids) {
        val l = localById[id]
        val r = remoteById[id]
        // Wiederbelebung lokal: Existiert trotz Tombstone eine NEUERE lokale
        // Datei, ist der Tombstone hinfaellig und wird verworfen.
        val t = tombstoneById[id]?.takeUnless { l != null && l.updatedAt > it.deletedAt }

        if (t != null) {
            // Die Tour gilt lokal als geloescht.
            val remoteUpdatedAt = if (r != null && !r.deleted) r.updatedAt else null
            if (remoteUpdatedAt != null && remoteUpdatedAt > t.deletedAt) {
                // Remote NACH der Loeschung bearbeitet -> Wiederbelebung per
                // Pull; der Tombstone verfaellt.
                if (l == null) pullNew.add(id) else pullUpdated.add(id)
            } else {
                if (r != null && !r.deleted) deleteRemote.add(id)
                // Eine etwaige (aeltere) lokale Restdatei raeumt der Plan mit weg.
                if (l != null) deleteLocal.add(id)
                tombstonesAfterSync.add(t)
            }
            continue
        }

        when {
            l != null && r == null -> pushNew.add(id)

            l != null && !r!!.deleted -> {
                val remoteUpdatedAt = r.updatedAt
                when {
                    // Alter Server ohne Zeitstempel: wie bisher nichts tun.
                    remoteUpdatedAt == null -> Unit
                    l.updatedAt > remoteUpdatedAt -> pushUpdated.add(id)
                    remoteUpdatedAt > l.updatedAt -> pullUpdated.add(id)
                    else -> Unit
                }
            }

            l != null -> {
                // Remote-Tombstone gegen lokale Tour.
                val deletedAt = r!!.deletedAt
                when {
                    // Unvollstaendiger Tombstone ohne Zeitpunkt: im Zweifel
                    // NICHTS loeschen.
                    deletedAt == null -> Unit
                    // Lokale Bearbeitung ist neuer -> Wiederbelebung per Push.
                    l.updatedAt > deletedAt -> pushUpdated.add(id)
                    else -> {
                        deleteLocal.add(id)
                        tombstonesAfterSync.add(RideTombstone(id, deletedAt))
                    }
                }
            }

            r != null && !r.deleted -> pullNew.add(id)

            // Uebrig: nur ein Remote-Tombstone ohne lokale Tour — nichts zu tun.
            else -> Unit
        }
    }

    return SyncPlan(
        pushNew = pushNew,
        pushUpdated = pushUpdated,
        pullNew = pullNew,
        pullUpdated = pullUpdated,
        deleteRemote = deleteRemote,
        deleteLocal = deleteLocal,
        tombstonesAfterSync = tombstonesAfterSync,
    )
}

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

    // Kaputtes JSON auf oberster Ebene wirft wie im Original die rohe
    // Parse-/Cast-Exception durch. Einzelne Eintraege werden dagegen tolerant
    // gelesen: Tombstone-Eintraege neuer Server haben keinen `name`, alte
    // Server kennen `updatedAt`/`deleted`/`deletedAt` nicht — fehlende Felder
    // sind hier der Normalfall, kein Fehler.
    val data = Json.parseToJsonElement(response.body) as JsonArray
    return data.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val id = obj.optionalString("id") ?: return@mapNotNull null
        RemoteRideSummary(
            id = id,
            name = obj.optionalString("name") ?: "",
            updatedAt = obj.optionalLong("updatedAt"),
            deleted = obj.optionalBoolean("deleted") ?: false,
            deletedAt = obj.optionalLong("deletedAt"),
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

private fun deleteRemoteRide(client: HttpClient, config: SyncConfig, id: String) {
    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.DELETE,
                url = "${config.url}/api/rides/$id",
                headers = authHeaders(config),
            ),
        )
    } catch (e: Exception) {
        throw Exception("Löschen einer Tour auf dem Server fehlgeschlagen: Sync-Server nicht erreichbar.")
    }

    // 404 gilt als Erfolg: Die Tour ist auf dem Server bereits weg — genau
    // das sollte die Loeschung erreichen.
    if ((response.statusCode < 200 || response.statusCode >= 300) && response.statusCode != 404) {
        throw Exception("Löschen einer Tour auf dem Server fehlgeschlagen (HTTP ${response.statusCode}).")
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
 * Gleicht lokale Touren bidirektional mit dem konfigurierten Sync-Server ab
 * — die Regeln stehen an [planSync], diese Funktion fuehrt den Plan nur
 * sequenziell aus (Push, Pull, Loeschungen, Tombstone-Bestand ersetzen).
 *
 * Alle Seiteneffekte sind injiziert und damit testbar:
 * [listLocal]/[loadLocal]/[saveLocal] und [deleteLocal] fuer die
 * Tour-Dateien, [listTombstones]/[replaceTombstones] fuer den
 * Loesch-Merkzettel (siehe SyncTombstones.kt), [client]/[store] fuer Netzwerk
 * und Konfiguration. Die Tombstone-Parameter haben No-Op-Defaults — ein
 * Aufrufer ohne Tombstone-Persistenz bekommt exakt das alte Verhalten (plus
 * Last-Write-Wins-Updates).
 *
 * ## Warum Zusammenfassungen plus Lade-Callback
 * Fuer die Sync-**Entscheidung** ([planSync]) reichen `id` und `updatedAt` —
 * dafuer muss niemand saemtliche GPS-Punkte aller Touren in den Speicher
 * heben. Nur fuer den **Push** braucht es die volle Tour; die holt sich diese
 * Funktion je betroffener ID einzeln ueber [loadLocal]. Liefert [loadLocal]
 * `null` (Datei zwischenzeitlich geloescht oder unlesbar), wird die ID
 * uebersprungen — genau wie zuvor eine aus der Liste verschwundene Tour.
 */
fun syncRides(
    listLocal: () -> List<LocalRideSummary>,
    loadLocal: (String) -> Ride?,
    saveLocal: (Ride) -> Unit,
    client: HttpClient,
    store: KeyValueStore,
    deleteLocal: (String) -> Unit = {},
    listTombstones: () -> List<RideTombstone> = { emptyList() },
    replaceTombstones: (List<RideTombstone>) -> Unit = {},
): SyncResult {
    val config = getSyncConfig(store) ?: throw Exception("Sync ist nicht konfiguriert.")

    val remoteRides = fetchRemoteRides(client, config)
    val localRides = listLocal()
    val tombstones = listTombstones()

    val plan = planSync(
        local = localRides,
        remote = remoteRides,
        tombstones = tombstones,
    )

    val remoteById = remoteRides.associateBy { it.id }

    for (id in plan.pushNew + plan.pushUpdated) {
        val ride = loadLocal(id) ?: continue
        pushRide(client, config, ride)
    }

    for (id in plan.pullNew + plan.pullUpdated) {
        val entry = remoteById[id] ?: continue
        val ride = pullRide(client, config, entry)
        saveLocal(ride)
    }

    for (id in plan.deleteRemote) {
        deleteRemoteRide(client, config, id)
    }

    for (id in plan.deleteLocal) {
        deleteLocal(id)
    }

    replaceTombstones(plan.tombstonesAfterSync)

    return SyncResult(
        pushed = plan.pushNew.size + plan.pushUpdated.size,
        pulled = plan.pullNew.size + plan.pullUpdated.size,
        total = localRides.size + plan.pullNew.size - plan.deleteLocal.size,
        updated = plan.pushUpdated.size + plan.pullUpdated.size,
        deletedLocal = plan.deleteLocal.size,
        deletedRemote = plan.deleteRemote.size,
    )
}
