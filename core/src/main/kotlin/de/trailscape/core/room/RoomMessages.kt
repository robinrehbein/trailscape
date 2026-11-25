package de.trailscape.core.room

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * # Raum-Protokoll v1: Nachrichten und Codec
 *
 * Spezifikation: `docs/raum-protokoll.md`. Gegenstück im Server:
 * `server/rooms.mjs`. Jede Nachricht ist ein JSON-Objekt mit `type`.
 *
 * Toleranz (Abschnitt „Versionierung“ der Spezifikation):
 *  * unbekannte Felder werden ignoriert,
 *  * unbekannte Nachrichtentypen werden zu [ServerMessage.Unknown] bzw. `null`
 *    und nicht als Fehler behandelt,
 *  * fehlende **optionale** Felder werden zu `null` bzw. zur Vorgabe,
 *  * fehlt ein **Pflichtfeld** oder hat es den falschen Typ, ist die ganze
 *    Nachricht ungültig (`null`) — außer in Listen (`riders`, `states`): dort
 *    fällt nur der kaputte Eintrag weg.
 *
 * Zahlen werden streng gelesen: `"12"` (String) ist keine Zahl.
 *
 * Datenklassen mit Geheimnissen überschreiben `toString`, damit ein
 * versehentliches Protokollieren nichts verrät.
 */
const val ROOM_PROTOCOL_VERSION: Int = 1

/** Strecke eines Raums; [id] und [version] muss die App bei sich wiederfinden. */
data class RoomRoute(
    val id: String,
    val lengthM: Double,
    val loop: Boolean = false,
    val version: String? = null,
    val name: String? = null,
)

data class RoomInfo(
    val id: String,
    val route: RoomRoute,
    val maxRiders: Int,
    val createdAt: Long,
    val expiresAt: Long,
    /** Startzeit in Server-Uhr (ms seit Epoch) oder `null`, solange kein Countdown läuft. */
    val startAt: Long?,
)

data class RiderInfo(
    val id: String,
    val name: String,
    val host: Boolean = false,
    val sharePower: Boolean = true,
    /** Undurchsichtig: Der Server reicht es nur weiter (Garage, Etappe 2). */
    val avatar: JsonElement? = null,
)

/** Ein Zustand eines Fahrers, wie ihn der Server rundsendet; [at] ist Server-Uhr beim Empfang. */
data class RiderStateEntry(
    val id: String,
    val at: Long,
    val distM: Double,
    val speedMps: Double,
    val powerW: Int? = null,
    val cadenceRpm: Int? = null,
    val lane: Int? = null,
)

/** Grenzen, die der Server im `welcome` nennt (alle optional, Vorgaben wie im Server). */
data class RoomLimits(
    val maxRidersPerRoom: Int = 10,
    val maxMessageBytes: Int = 1024,
    val maxAvatarBytes: Int = 512,
    val maxNameChars: Int = 32,
    val maxMessagesPerSecond: Int = 8,
    val broadcastIntervalMs: Int = 250,
    val rejoinGraceS: Int = 30,
)

/** Nachrichten vom Server an das Gerät. */
sealed interface ServerMessage {
    data class Welcome(
        val v: Int,
        val serverTime: Long,
        val you: You,
        val room: RoomInfo,
        val riders: List<RiderInfo>,
        val states: List<RiderStateEntry>,
        val limits: RoomLimits,
    ) : ServerMessage

    class You(val id: String, val rejoinKey: String, val host: Boolean) {
        override fun toString(): String = "You(id=$id, rejoinKey=***, host=$host)"
        override fun equals(other: Any?): Boolean =
            other is You && other.id == id && other.rejoinKey == rejoinKey && other.host == host
        override fun hashCode(): Int = id.hashCode()
    }

    /** Fahrer neu im Raum oder geändert (Name, Leistung teilen). */
    data class Rider(val rider: RiderInfo) : ServerMessage

    data class Left(val id: String, val reason: String) : ServerMessage

    data class Snapshot(val serverTime: Long, val states: List<RiderStateEntry>) : ServerMessage

    data class Countdown(val startAt: Long, val serverTime: Long) : ServerMessage

    data class Ended(val reason: String, val serverTime: Long?) : ServerMessage

    /** Antwort auf [ClientMessage.Ping]: [c] gespiegelt, [s] Server-Uhr. */
    data class Pong(val c: Long?, val s: Long) : ServerMessage

    data class Error(val code: String, val message: String?, val supported: List<Int> = emptyList()) : ServerMessage

    /** Typ, den diese App-Version nicht kennt (neuerer Server). */
    data class Unknown(val type: String) : ServerMessage
}

/** Nachrichten vom Gerät an den Server. */
sealed interface ClientMessage {
    class Join(
        val room: String,
        val secret: String,
        val name: String,
        val avatar: JsonElement? = null,
        val sharePower: Boolean = true,
        val rejoin: Rejoin? = null,
        val v: Int = ROOM_PROTOCOL_VERSION,
    ) : ClientMessage {
        override fun toString(): String =
            "Join(v=$v, room=$room, secret=***, name=$name, sharePower=$sharePower, rejoin=${rejoin?.let { "${it.id}/***" }})"
        override fun equals(other: Any?): Boolean =
            other is Join && other.room == room && other.secret == secret && other.name == name &&
                other.avatar == avatar && other.sharePower == sharePower && other.rejoin == rejoin && other.v == v
        override fun hashCode(): Int = room.hashCode() * 31 + name.hashCode()
    }

    class Rejoin(val id: String, val key: String) {
        override fun toString(): String = "Rejoin(id=$id, key=***)"
        override fun equals(other: Any?): Boolean = other is Rejoin && other.id == id && other.key == key
        override fun hashCode(): Int = id.hashCode()
    }

    /** Eigener Zustand, 2–4 Hz. [powerW] nur, wenn der Fahrer Leistung teilt. */
    data class State(
        val distM: Double,
        val speedMps: Double,
        val powerW: Double? = null,
        val cadenceRpm: Double? = null,
        val lane: Int? = null,
    ) : ClientMessage

    /** [c] ist die Uhr des Geräts (monoton, ms); kommt im [ServerMessage.Pong] zurück. */
    data class Ping(val c: Long) : ClientMessage

    data class Settings(val sharePower: Boolean? = null, val name: String? = null) : ClientMessage

    data object Leave : ClientMessage

    /** Nur Gastgeber: Countdown von [inS] Sekunden. */
    data class Start(val inS: Int) : ClientMessage

    /** Nur Gastgeber: Raum beenden. */
    data object End : ClientMessage
}

object RoomCodec {
    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------
    // Gerät → Server
    // ---------------------------------------------------------------------

    fun encode(message: ClientMessage): String = encodeClient(message).toString()

    internal fun encodeClient(message: ClientMessage): JsonObject = when (message) {
        is ClientMessage.Join -> buildJsonObject {
            put("type", "join")
            put("v", message.v)
            put("room", message.room)
            put("secret", message.secret)
            put("name", message.name)
            message.avatar?.let { put("avatar", it) }
            put("sharePower", message.sharePower)
            message.rejoin?.let { r ->
                put("rejoin", buildJsonObject {
                    put("id", r.id)
                    put("key", r.key)
                })
            }
        }
        is ClientMessage.State -> buildJsonObject {
            put("type", "state")
            put("distM", round2(message.distM))
            put("speedMps", round2(message.speedMps))
            message.powerW?.takeIf { it.isFinite() }?.let { put("powerW", it.roundToInt()) }
            message.cadenceRpm?.takeIf { it.isFinite() }?.let { put("cadenceRpm", it.roundToInt()) }
            message.lane?.let { put("lane", it) }
        }
        is ClientMessage.Ping -> buildJsonObject {
            put("type", "ping")
            put("c", message.c)
        }
        is ClientMessage.Settings -> buildJsonObject {
            put("type", "settings")
            message.sharePower?.let { put("sharePower", it) }
            message.name?.let { put("name", it) }
        }
        ClientMessage.Leave -> buildJsonObject { put("type", "leave") }
        is ClientMessage.Start -> buildJsonObject {
            put("type", "start")
            put("inS", message.inS)
        }
        ClientMessage.End -> buildJsonObject { put("type", "end") }
    }

    /** Liest eine Geräte-Nachricht (für Attrappen und einen künftigen Kotlin-Server). */
    fun decodeClient(text: String): ClientMessage? {
        val o = parseObject(text) ?: return null
        return when (o.str("type")) {
            "join" -> {
                val rejoin = (o["rejoin"] as? JsonObject)?.let { r ->
                    val id = r.str("id")
                    val key = r.str("key")
                    if (id != null && key != null) ClientMessage.Rejoin(id, key) else null
                }
                ClientMessage.Join(
                    room = o.str("room") ?: return null,
                    secret = o.str("secret") ?: return null,
                    name = o.str("name") ?: return null,
                    avatar = o["avatar"]?.takeUnless { it is JsonNull },
                    sharePower = o.bool("sharePower") ?: true,
                    rejoin = rejoin,
                    v = o.int("v") ?: return null,
                )
            }
            "state" -> ClientMessage.State(
                distM = o.num("distM") ?: return null,
                speedMps = o.num("speedMps") ?: return null,
                powerW = o.num("powerW"),
                cadenceRpm = o.num("cadenceRpm"),
                lane = o.int("lane"),
            )
            "ping" -> ClientMessage.Ping(o.long("c") ?: return null)
            "settings" -> ClientMessage.Settings(o.bool("sharePower"), o.str("name"))
            "leave" -> ClientMessage.Leave
            "start" -> ClientMessage.Start(o.int("inS") ?: return null)
            "end" -> ClientMessage.End
            else -> null
        }
    }

    // ---------------------------------------------------------------------
    // Server → Gerät
    // ---------------------------------------------------------------------

    /** Liest eine Server-Nachricht; `null`, wenn sie kaputt ist. */
    fun decodeServer(text: String): ServerMessage? {
        val o = parseObject(text) ?: return null
        val type = o.str("type") ?: return null
        return when (type) {
            "welcome" -> {
                val you = o["you"] as? JsonObject ?: return null
                ServerMessage.Welcome(
                    v = o.int("v") ?: return null,
                    serverTime = o.long("serverTime") ?: return null,
                    you = ServerMessage.You(
                        id = you.str("id") ?: return null,
                        rejoinKey = you.str("rejoinKey") ?: return null,
                        host = you.bool("host") ?: false,
                    ),
                    room = decodeRoom(o["room"]) ?: return null,
                    riders = (o["riders"] as? JsonArray).orEmpty().mapNotNull(::decodeRider),
                    states = (o["states"] as? JsonArray).orEmpty().mapNotNull(::decodeState),
                    limits = decodeLimits(o["limits"] as? JsonObject),
                )
            }
            "rider" -> ServerMessage.Rider(decodeRider(o["rider"]) ?: return null)
            "left" -> ServerMessage.Left(o.str("id") ?: return null, o.str("reason") ?: "unknown")
            "snapshot" -> ServerMessage.Snapshot(
                serverTime = o.long("serverTime") ?: return null,
                states = (o["states"] as? JsonArray).orEmpty().mapNotNull(::decodeState),
            )
            "countdown" -> ServerMessage.Countdown(
                startAt = o.long("startAt") ?: return null,
                serverTime = o.long("serverTime") ?: return null,
            )
            "ended" -> ServerMessage.Ended(o.str("reason") ?: "unknown", o.long("serverTime"))
            "pong" -> ServerMessage.Pong(c = o.long("c"), s = o.long("s") ?: return null)
            "error" -> ServerMessage.Error(
                code = o.str("code") ?: return null,
                message = o.str("message"),
                supported = (o["supported"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.strictInt() },
            )
            else -> ServerMessage.Unknown(type)
        }
    }

    /** Schreibt eine Server-Nachricht (für Attrappen in Tests). */
    fun encode(message: ServerMessage): String = when (message) {
        is ServerMessage.Welcome -> buildJsonObject {
            put("type", "welcome")
            put("v", message.v)
            put("serverTime", message.serverTime)
            put("you", buildJsonObject {
                put("id", message.you.id)
                put("rejoinKey", message.you.rejoinKey)
                put("host", message.you.host)
            })
            put("room", encodeRoom(message.room))
            put("riders", buildJsonArray { message.riders.forEach { add(encodeRider(it)) } })
            put("states", buildJsonArray { message.states.forEach { add(encodeState(it)) } })
            put("limits", buildJsonObject {
                put("maxRidersPerRoom", message.limits.maxRidersPerRoom)
                put("maxMessageBytes", message.limits.maxMessageBytes)
                put("maxAvatarBytes", message.limits.maxAvatarBytes)
                put("maxNameChars", message.limits.maxNameChars)
                put("maxMessagesPerSecond", message.limits.maxMessagesPerSecond)
                put("broadcastIntervalMs", message.limits.broadcastIntervalMs)
                put("rejoinGraceS", message.limits.rejoinGraceS)
            })
        }
        is ServerMessage.Rider -> buildJsonObject {
            put("type", "rider")
            put("rider", encodeRider(message.rider))
        }
        is ServerMessage.Left -> buildJsonObject {
            put("type", "left")
            put("id", message.id)
            put("reason", message.reason)
        }
        is ServerMessage.Snapshot -> buildJsonObject {
            put("type", "snapshot")
            put("serverTime", message.serverTime)
            put("states", buildJsonArray { message.states.forEach { add(encodeState(it)) } })
        }
        is ServerMessage.Countdown -> buildJsonObject {
            put("type", "countdown")
            put("startAt", message.startAt)
            put("serverTime", message.serverTime)
        }
        is ServerMessage.Ended -> buildJsonObject {
            put("type", "ended")
            put("reason", message.reason)
            message.serverTime?.let { put("serverTime", it) }
        }
        is ServerMessage.Pong -> buildJsonObject {
            put("type", "pong")
            put("c", message.c)
            put("s", message.s)
        }
        is ServerMessage.Error -> buildJsonObject {
            put("type", "error")
            put("code", message.code)
            message.message?.let { put("message", it) }
            if (message.supported.isNotEmpty()) put("supported", buildJsonArray { message.supported.forEach { add(JsonPrimitive(it)) } })
        }
        is ServerMessage.Unknown -> buildJsonObject { put("type", message.type) }
    }.toString()

    // ---------------------------------------------------------------------
    // Teile
    // ---------------------------------------------------------------------

    internal fun decodeRoute(e: JsonElement?): RoomRoute? {
        val o = e as? JsonObject ?: return null
        val lengthM = o.num("lengthM") ?: return null
        if (lengthM <= 0) return null
        return RoomRoute(
            id = o.str("id") ?: return null,
            lengthM = lengthM,
            loop = o.bool("loop") ?: false,
            version = o.str("version"),
            name = o.str("name"),
        )
    }

    internal fun encodeRoute(r: RoomRoute): JsonObject = buildJsonObject {
        put("id", r.id)
        put("lengthM", r.lengthM)
        put("loop", r.loop)
        r.version?.let { put("version", it) }
        r.name?.let { put("name", it) }
    }

    internal fun decodeRoom(e: JsonElement?): RoomInfo? {
        val o = e as? JsonObject ?: return null
        return RoomInfo(
            id = o.str("id") ?: return null,
            route = decodeRoute(o["route"]) ?: return null,
            maxRiders = o.int("maxRiders") ?: 10,
            createdAt = o.long("createdAt") ?: 0L,
            expiresAt = o.long("expiresAt") ?: Long.MAX_VALUE,
            startAt = o.long("startAt"),
        )
    }

    internal fun encodeRoom(r: RoomInfo): JsonObject = buildJsonObject {
        put("id", r.id)
        put("route", encodeRoute(r.route))
        put("maxRiders", r.maxRiders)
        put("createdAt", r.createdAt)
        put("expiresAt", r.expiresAt)
        put("startAt", r.startAt)
    }

    private fun decodeRider(e: JsonElement?): RiderInfo? {
        val o = e as? JsonObject ?: return null
        return RiderInfo(
            id = o.str("id") ?: return null,
            name = o.str("name") ?: return null,
            host = o.bool("host") ?: false,
            sharePower = o.bool("sharePower") ?: true,
            avatar = o["avatar"]?.takeUnless { it is JsonNull },
        )
    }

    private fun encodeRider(r: RiderInfo): JsonObject = buildJsonObject {
        put("id", r.id)
        put("name", r.name)
        put("host", r.host)
        put("sharePower", r.sharePower)
        r.avatar?.let { put("avatar", it) }
    }

    private fun decodeState(e: JsonElement?): RiderStateEntry? {
        val o = e as? JsonObject ?: return null
        val distM = o.num("distM") ?: return null
        val speedMps = o.num("speedMps") ?: return null
        if (distM < 0 || speedMps < 0) return null
        return RiderStateEntry(
            id = o.str("id") ?: return null,
            at = o.long("at") ?: return null,
            distM = distM,
            speedMps = speedMps,
            powerW = o.int("powerW"),
            cadenceRpm = o.int("cadenceRpm"),
            lane = o.int("lane"),
        )
    }

    private fun encodeState(s: RiderStateEntry): JsonObject = buildJsonObject {
        put("id", s.id)
        put("at", s.at)
        put("distM", s.distM)
        put("speedMps", s.speedMps)
        s.powerW?.let { put("powerW", it) }
        s.cadenceRpm?.let { put("cadenceRpm", it) }
        s.lane?.let { put("lane", it) }
    }

    private fun decodeLimits(o: JsonObject?): RoomLimits {
        val d = RoomLimits()
        if (o == null) return d
        return RoomLimits(
            maxRidersPerRoom = o.int("maxRidersPerRoom") ?: d.maxRidersPerRoom,
            maxMessageBytes = o.int("maxMessageBytes") ?: d.maxMessageBytes,
            maxAvatarBytes = o.int("maxAvatarBytes") ?: d.maxAvatarBytes,
            maxNameChars = o.int("maxNameChars") ?: d.maxNameChars,
            maxMessagesPerSecond = o.int("maxMessagesPerSecond") ?: d.maxMessagesPerSecond,
            broadcastIntervalMs = o.int("broadcastIntervalMs") ?: d.broadcastIntervalMs,
            rejoinGraceS = o.int("rejoinGraceS") ?: d.rejoinGraceS,
        )
    }

    internal fun parseObject(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (e: IllegalArgumentException) {
        // SerializationException ist eine IllegalArgumentException.
        null
    }

    private fun round2(v: Double): Double = if (v.isFinite()) (v * 100).roundToLong() / 100.0 else v
}

// -------------------------------------------------------------------------
// Strenge Feldzugriffe (kein String als Zahl, kein NaN)
// -------------------------------------------------------------------------

private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content

internal fun JsonObject.num(key: String): Double? =
    prim(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

internal fun JsonObject.bool(key: String): Boolean? = prim(key)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.long(key: String): Long? = num(key)?.let { d ->
    if (abs(d) < 9.0e15) d.roundToLong() else null
}

internal fun JsonObject.int(key: String): Int? = prim(key)?.strictInt()

private fun JsonPrimitive.strictInt(): Int? {
    if (isString) return null
    val d = doubleOrNull?.takeIf { it.isFinite() } ?: return null
    if (d != floor(d) || abs(d) > Int.MAX_VALUE) return null
    return d.toInt()
}
