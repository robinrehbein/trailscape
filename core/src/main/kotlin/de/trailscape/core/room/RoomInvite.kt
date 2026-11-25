package de.trailscape.core.room

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * # Einladungslink
 *
 * `trailscape://room?server=<Server-Adresse, URL-kodiert>&room=<Raumkennung>#<Raum-Geheimnis>`
 *
 * Das Geheimnis steht bewusst im **Fragment** (`#…`): Wird der Link später
 * über eine Web-Adresse (App Links, Etappe 3) verteilt, schickt ein Browser
 * das Fragment nie an einen Server, und es landet in keinem Zugriffsprotokoll.
 * Der Link enthält **nie** den `SYNC_TOKEN` und nie das Gastgeber-Geheimnis.
 *
 * Erlaubt sind nur `https://`-Server; `http://` nur für Rechner im eigenen
 * Gerät bzw. Emulator (`localhost`, `127.0.0.1`, `10.0.2.2`) zum Testen.
 */
class RoomInvite(val serverUrl: String, val roomId: String, val secret: String) {

    /** Link zum Teilen (Messenger, QR-Code). */
    fun toLink(): String =
        "$SCHEME://$HOST?server=${enc(serverUrl)}&room=${enc(roomId)}#$secret"

    /** WebSocket-Adresse des Raum-Dienstes auf diesem Server. */
    fun webSocketUrl(): String = webSocketUrl(serverUrl)

    override fun toString(): String = "RoomInvite(server=$serverUrl, room=$roomId, secret=***)"
    override fun equals(other: Any?): Boolean =
        other is RoomInvite && other.serverUrl == serverUrl && other.roomId == roomId && other.secret == secret
    override fun hashCode(): Int = serverUrl.hashCode() * 31 + roomId.hashCode()

    companion object {
        const val SCHEME = "trailscape"
        const val HOST = "room"
        private val ROOM_ID = Regex("^[A-Za-z0-9_-]{16}$")
        private val SECRET = Regex("^[A-Za-z0-9_-]{43}$")
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        /** Erstellt eine Einladung oder `null`, wenn ein Teil nicht passt. */
        fun of(serverUrl: String, roomId: String, secret: String): RoomInvite? {
            val server = normalizeServerUrl(serverUrl) ?: return null
            if (!ROOM_ID.matches(roomId) || !SECRET.matches(secret)) return null
            return RoomInvite(server, roomId, secret)
        }

        /** Liest einen Einladungslink; `null` bei allem, was nicht genau passt. */
        fun parse(link: String): RoomInvite? {
            val uri = try {
                URI(link.trim())
            } catch (e: Exception) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
            val secret = uri.rawFragment ?: return null
            val params = (uri.rawQuery ?: return null).split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to dec(part.substring(i + 1))
            }.toMap()
            return of(params["server"] ?: return null, params["room"] ?: return null, secret)
        }

        /**
         * Server-Adresse wie in den Sync-Einstellungen: Schema und Host, ohne
         * Abfrage und Fragment, ohne `/` am Ende. Ein Pfad bleibt erhalten
         * (Server hinter einem Unterpfad des Reverse-Proxys).
         */
        fun normalizeServerUrl(url: String): String? {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            when (scheme) {
                "https" -> Unit
                "http" -> if (host !in LOCAL_HOSTS) return null
                else -> return null
            }
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = (uri.rawPath ?: "").trimEnd('/')
            return "$scheme://$host$port$path"
        }

        /** `https://x` → `wss://x/api/rooms/ws`, `http://localhost` → `ws://localhost/api/rooms/ws`. */
        fun webSocketUrl(serverUrl: String): String {
            val base = serverUrl.trimEnd('/')
            val ws = when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
                else -> base
            }
            return "$ws/api/rooms/ws"
        }

        // Bewusst die String-Variante: die mit Charset gibt es auf Android erst ab API 33.
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            ""
        }
    }
}
