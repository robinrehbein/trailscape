package de.trailscape.app.room

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Echte WebSocket-Verbindung gegen MockWebServer: Öffnen, Text hin und her, Schließcode. */
class OkHttpRoomTransportTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private class Recorder : RoomSocketListener {
        val events = LinkedBlockingQueue<String>()
        override fun onOpen() {
            events += "open"
        }
        override fun onMessage(text: String) {
            events += "msg:$text"
        }
        override fun onClosed(code: Int, reason: String) {
            events += "closed:$code"
        }
        override fun onFailure(error: Throwable) {
            events += "failure"
        }
        fun next(): String = events.poll(5, TimeUnit.SECONDS) ?: "zeitueberschreitung"
    }

    @Test
    fun `Text hin und zurueck, Schliesscode des Servers kommt an`() {
        val fromClient = LinkedBlockingQueue<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                override fun onMessage(webSocket: WebSocket, text: String) {
                    fromClient += text
                    webSocket.send("""{"type":"welcome"}""")
                    webSocket.close(4004, "Raum beendet")
                }
            }),
        )
        server.start()
        val url = server.url("/api/rooms/ws").toString().replace("http://", "ws://")
        val rec = Recorder()
        val socket = OkHttpRoomTransport().open(url, rec)
        assertEquals("open", rec.next())
        assertTrue(socket.send("""{"type":"join"}"""))
        assertEquals("""{"type":"join"}""", fromClient.poll(5, TimeUnit.SECONDS))
        assertEquals("""msg:{"type":"welcome"}""", rec.next())
        assertEquals("closed:4004", rec.next())
        // Genau ein Abschluss, kein zusätzliches onClosed/onFailure.
        assertEquals(null, rec.events.poll(500, TimeUnit.MILLISECONDS))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/rooms/ws", request.path, "kein Geheimnis in der URL")
        assertEquals(null, request.getHeader("Authorization"), "kein SYNC_TOKEN im Handshake")
    }

    @Test
    fun `Fehlschlag beim Verbinden meldet onFailure`() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        val rec = Recorder()
        OkHttpRoomTransport().open(server.url("/api/rooms/ws").toString(), rec)
        assertEquals("failure", rec.next())
    }

    @Test
    fun `Schliesscodes zum Zurueckspiegeln`() {
        assertEquals(4004, OkHttpRoomTransport.echoableCloseCode(4004))
        assertEquals(1000, OkHttpRoomTransport.echoableCloseCode(1005))
        assertEquals(1000, OkHttpRoomTransport.echoableCloseCode(1006))
        assertEquals(1000, OkHttpRoomTransport.echoableCloseCode(1015))
        assertEquals(1000, OkHttpRoomTransport.echoableCloseCode(2000))
        assertEquals(1008, OkHttpRoomTransport.echoableCloseCode(1008))
    }
}
