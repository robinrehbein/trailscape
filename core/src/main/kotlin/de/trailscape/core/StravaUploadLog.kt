package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pro Tour ein Vermerk, ob und wohin sie zu Strava hochgeladen wurde.
 *
 * Liegt lokal auf dem [KeyValueStore] (in der App die `SharedPreferences`,
 * die vom Systembackup ausgenommen sind) — bewusst **nicht** in der
 * Tour-Datei: Die Tour-JSON ist das Austauschformat mit Sync-Server,
 * Web-App und Sicherung; ein Strava-Vermerk hat dort nichts zu suchen und
 * gehoert zum Geraet, das hochgeladen hat.
 */

/** Stand des Uploads einer Tour. */
enum class StravaUploadState { UPLOADING, DONE, DUPLICATE, FAILED }

/**
 * Der Vermerk zu einer Tour.
 *
 * @param uploadId Stravas Upload-ID, sobald der Upload angenommen ist — damit
 *   ein spaeterer Versuch nur weiter abfragt statt erneut hochzuladen.
 * @param activityId die Aktivitaet auf Strava (fuer „Auf Strava ansehen").
 * @param error Fehlerklasse bei [StravaUploadState.FAILED]; `null` heisst
 *   „unbekannter Fehler".
 * @param detail technisches Detail fuer die Diagnose, kein Nutzertext.
 */
data class StravaUploadRecord(
    val rideId: String,
    val state: StravaUploadState,
    val uploadId: Long? = null,
    val activityId: Long? = null,
    val error: StravaError? = null,
    val detail: String? = null,
    val updatedAtMs: Long,
) {
    /**
     * Haengt der Upload? Ein Vermerk „wird hochgeladen", der seit
     * [STALE_AFTER_MS] nicht mehr angefasst wurde, gehoert zu keinem
     * laufenden Worker mehr (Prozess beendet, Arbeit verworfen). Dann bietet
     * die Tour „Erneut versuchen" an.
     */
    fun isStale(nowMs: Long): Boolean =
        state == StravaUploadState.UPLOADING && nowMs - updatedAtMs > STALE_AFTER_MS

    fun toJson(): JsonObject = buildJsonObject {
        put("state", state.name)
        uploadId?.let { put("uploadId", it) }
        activityId?.let { put("activityId", it) }
        error?.let { put("error", it.name) }
        detail?.let { put("detail", it) }
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        const val STALE_AFTER_MS: Long = 6 * 60 * 60 * 1000L

        /** `null` bei unbrauchbarem Eintrag (unbekannter Zustand, fehlende Zeit). */
        fun fromJson(rideId: String, json: JsonObject): StravaUploadRecord? {
            val state = json.optionalString("state")
                ?.let { name -> StravaUploadState.entries.firstOrNull { it.name == name } }
                ?: return null
            return StravaUploadRecord(
                rideId = rideId,
                state = state,
                uploadId = json.optionalLong("uploadId"),
                activityId = json.optionalLong("activityId"),
                error = json.optionalString("error")?.let { name -> StravaError.entries.firstOrNull { it.name == name } },
                detail = json.optionalString("detail"),
                updatedAtMs = json.optionalLong("updatedAtMs") ?: return null,
            )
        }
    }
}

/** Speicherschluessel des Upload-Protokolls. */
const val STRAVA_UPLOAD_LOG_KEY = "trailscape.strava.uploads"

/**
 * Das Protokoll aller Vermerke als ein JSON-Objekt `rideId → Vermerk` unter
 * [STRAVA_UPLOAD_LOG_KEY].
 *
 * Robust statt streng: Kaputtes JSON ergibt ein leeres Protokoll (schlimmstenfalls
 * bietet eine Tour erneut „Zu Strava hochladen" an, und Strava meldet ein
 * Duplikat), unbekannte Felder werden ignoriert. Schreibzugriffe sind
 * synchronisiert, weil Worker und Oberflaeche gleichzeitig schreiben koennen.
 */
class StravaUploadLog(private val store: KeyValueStore) {

    @Synchronized
    fun all(): Map<String, StravaUploadRecord> {
        val raw = store.getString(STRAVA_UPLOAD_LOG_KEY) ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return emptyMap()
        return buildMap {
            for ((rideId, value) in root) {
                val record = (value as? JsonObject)?.let { runCatching { StravaUploadRecord.fromJson(rideId, it) }.getOrNull() }
                if (record != null) put(rideId, record)
            }
        }
    }

    fun get(rideId: String): StravaUploadRecord? = all()[rideId]

    @Synchronized
    fun put(record: StravaUploadRecord) {
        write(all() + (record.rideId to record))
    }

    @Synchronized
    fun remove(rideId: String) {
        val current = all()
        if (rideId in current) write(current - rideId)
    }

    /** Entfernt alle Vermerke, auf die [predicate] zutrifft. */
    @Synchronized
    fun removeWhere(predicate: (StravaUploadRecord) -> Boolean) {
        val current = all()
        val kept = current.filterValues { !predicate(it) }
        if (kept.size != current.size) write(kept)
    }

    private fun write(records: Map<String, StravaUploadRecord>) {
        if (records.isEmpty()) {
            store.remove(STRAVA_UPLOAD_LOG_KEY)
            return
        }
        val json = buildJsonObject {
            for ((rideId, record) in records) put(rideId, record.toJson())
        }
        store.setString(STRAVA_UPLOAD_LOG_KEY, json.toString())
    }
}
