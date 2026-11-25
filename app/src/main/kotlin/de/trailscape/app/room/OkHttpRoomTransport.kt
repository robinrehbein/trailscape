package de.trailscape.app.room

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WebSocket mit OkHttp (ist ohnehin im APK, keine neue Abhängigkeit).
 *
 * Kein Geheimnis in der URL: Raumkennung und Geheimnis stehen erst in der
 * ersten Nachricht (`join`), damit sie in keinem Zugriffsprotokoll eines
 * Reverse-Proxys landen (docs/raum-protokoll.md, Sicherheit).
 */
class OkHttpRoomTransport(
    private val client: OkHttpClient = defaultClient(),
) : RoomTransport {

    override fun open(url: String, listener: RoomSocketListener): RoomSocket {
        val finished = AtomicBoolean(false)
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Gegenseite schließt: bestätigen und sofort melden (nicht auf onClosed warten).
                webSocket.close(echoableCloseCode(code), null)
                if (finished.compareAndSet(false, true)) listener.onClosed(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finished.compareAndSet(false, true)) listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (finished.compareAndSet(false, true)) listener.onFailure(t)
            }
        })
        return object : RoomSocket {
            override fun send(text: String): Boolean = ws.send(text)
            override fun close(code: Int, reason: String) {
                ws.close(code, reason)
            }
            override fun cancel() = ws.cancel()
        }
    }

    companion object {
        /** OkHttp wirft bei Codes, die man nicht senden darf (RFC 6455, 7.4.1). */
        internal fun echoableCloseCode(code: Int): Int =
            if (code in 1000..1003 || code in 1007..1011 || code in 3000..4999) code else 1000

        /**
         * Eigener Client: Lesen ohne Zeitlimit (der Server schickt nur, wenn
         * sich etwas tut), dafür Ping alle 15 s — so merkt OkHttp eine tote
         * Verbindung und hält Reverse-Proxys wach.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
