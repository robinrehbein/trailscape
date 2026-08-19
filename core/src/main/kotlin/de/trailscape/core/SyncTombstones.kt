package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tombstones (Loesch-Merkzettel) fuer den bidirektionalen Selfhost-Sync.
 *
 * Loescht die Nutzerin eine Tour lokal, verschwindet nur die Datei — ohne
 * Merkzettel wuerde der naechste Sync die Tour vom Server einfach wieder
 * herunterladen ("Zombie-Tour"). Deshalb hinterlaesst jede endgueltige
 * Loeschung einen [RideTombstone]; [syncRides] traegt ihn als `DELETE` zum
 * Server, und der Server merkt sich seinerseits einen Tombstone fuer andere
 * Geraete.
 *
 * Hier stehen nur die **reinen** Datentypen und (De-)Serialisierungsfunktionen
 * — ohne IO, damit sie in `:core` testbar bleiben. Die Datei-Persistenz
 * (`<filesDir>/rides/tombstones.json`) liegt in `:app`
 * (`de.trailscape.app.data.TombstoneStore`).
 */

/** Merkzettel einer endgueltig geloeschten Tour. Beide Zeiten in ms seit Epoch. */
data class RideTombstone(val id: String, val deletedAt: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("deletedAt", deletedAt)
    }

    companion object {
        fun fromJson(json: JsonObject): RideTombstone = RideTombstone(
            id = json.requiredString("id"),
            deletedAt = json.requiredLong("deletedAt"),
        )
    }
}

/** Serialisiert eine Tombstone-Liste als JSON-Array-Text (Inhalt von `tombstones.json`). */
fun tombstonesToJsonString(tombstones: List<RideTombstone>): String =
    buildJsonArray { tombstones.forEach { add(it.toJson()) } }.toString()

/**
 * Liest eine Tombstone-Liste aus dem JSON-Array-Text.
 *
 * Bewusst tolerant: kaputtes JSON oder unerwartete Formen ergeben eine leere
 * Liste, einzelne unlesbare Eintraege werden uebersprungen. Ein verlorener
 * Tombstone ist verschmerzbar (die Tour taucht schlimmstenfalls wieder auf und
 * kann erneut geloescht werden) — ein Sync, der an einer kaputten Datei
 * dauerhaft scheitert, waere es nicht.
 */
fun tombstonesFromJsonString(raw: String): List<RideTombstone> {
    val parsed = try {
        Json.parseToJsonElement(raw)
    } catch (e: Exception) {
        return emptyList()
    }
    if (parsed !is JsonArray) return emptyList()
    return parsed.mapNotNull { entry ->
        try {
            RideTombstone.fromJson(entry as JsonObject)
        } catch (e: Exception) {
            null
        }
    }
}
