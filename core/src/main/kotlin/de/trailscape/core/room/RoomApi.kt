package de.trailscape.core.room

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Raum anlegen und beenden (REST, nur mit `SYNC_TOKEN`)
 *
 * Nur der Betreiber des Servers (wer den `SYNC_TOKEN` in der App eingetragen
 * hat) legt Räume an. Gäste brauchen das nicht: Sie treten mit dem
 * [RoomInvite] über den WebSocket bei.
 *
 * Läuft über die plattformneutrale [HttpClient]-Abstraktion wie `SyncClient`;
 * in `:app` steckt OkHttp dahinter. Blockierend — nicht auf dem
 * Hauptthread aufrufen.
 */
class RoomApi(private val http: HttpClient) {

    fun create(
        serverUrl: String,
        token: String,
        route: RoomRoute,
        maxRiders: Int? = null,
        ttlMin: Int? = null,
        startAt: Long? = null,
    ): RoomCreateResult {
        val server = RoomInvite.normalizeServerUrl(serverUrl) ?: return RoomCreateResult.Failed(0, "INVALID_URL")
        val body = buildJsonObject {
            put("route", RoomCodec.encodeRoute(route))
            maxRiders?.let { put("maxRiders", it) }
            ttlMin?.let { put("ttlMin", it) }
            startAt?.let { put("startAt", it) }
        }.toString()
        val response = try {
            http.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = "$server/api/rooms",
                    headers = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
                    body = body,
                ),
            )
        } catch (e: Exception) {
            return RoomCreateResult.Failed(0, "NETWORK")
        }
        val json = RoomCodec.parseObject(response.body)
        return when (response.statusCode) {
            201 -> parseCreated(server, json) ?: RoomCreateResult.Failed(201, "BAD_RESPONSE")
            401 -> RoomCreateResult.Unauthorized
            404 -> RoomCreateResult.Disabled
            429 -> if (json?.str("code") == "ROOMS_FULL") RoomCreateResult.ServerFull else RoomCreateResult.Failed(429, "RATE")
            else -> RoomCreateResult.Failed(response.statusCode, json?.str("code") ?: "HTTP_${response.statusCode}")
        }
    }

    /** Beendet einen Raum. `true`, wenn er beendet wurde oder schon weg war. */
    fun delete(serverUrl: String, token: String, roomId: String): Boolean {
        val server = RoomInvite.normalizeServerUrl(serverUrl) ?: return false
        if (!Regex("^[A-Za-z0-9_-]{16}$").matches(roomId)) return false
        return try {
            val r = http.execute(
                HttpRequest(HttpMethod.DELETE, "$server/api/rooms/$roomId", mapOf("Authorization" to "Bearer $token")),
            )
            r.statusCode == 204 || r.statusCode == 404
        } catch (e: Exception) {
            false
        }
    }

    private fun parseCreated(server: String, json: JsonObject?): RoomCreateResult.Created? {
        if (json == null) return null
        val room = RoomCodec.decodeRoom(json["room"]) ?: return null
        val secret = json.str("secret") ?: return null
        val hostSecret = json.str("hostSecret") ?: return null
        val invite = RoomInvite.of(server, room.id, secret) ?: return null
        return RoomCreateResult.Created(
            room = room,
            invite = invite,
            hostSecret = hostSecret,
            serverTime = json.long("serverTime"),
        )
    }
}

sealed interface RoomCreateResult {
    /**
     * Raum steht. [invite] wird geteilt; [hostSecret] bleibt auf dem Gerät des
     * Gastgebers (Beitritt als Gastgeber: Countdown, Beenden).
     */
    class Created(
        val room: RoomInfo,
        val invite: RoomInvite,
        val hostSecret: String,
        val serverTime: Long?,
    ) : RoomCreateResult {
        override fun toString(): String = "Created(room=${room.id}, invite=$invite, hostSecret=***)"
    }

    /** Falscher `SYNC_TOKEN`. */
    data object Unauthorized : RoomCreateResult

    /** Server kennt keine Räume (alte Version oder `ROOMS` nicht eingeschaltet). */
    data object Disabled : RoomCreateResult

    /** Höchstzahl der Räume erreicht. */
    data object ServerFull : RoomCreateResult

    data class Failed(val status: Int, val code: String) : RoomCreateResult
}
