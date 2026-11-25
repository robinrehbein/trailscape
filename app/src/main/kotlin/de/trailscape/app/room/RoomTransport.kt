package de.trailscape.app.room

/**
 * Schmale Schicht über einem WebSocket, damit [ReconnectingRoomClient] ohne
 * Netz testbar ist (Attrappe in den Tests, OkHttp im Betrieb).
 *
 * Vertrag: Nach [RoomTransport.open] kommt genau eines von
 * [RoomSocketListener.onClosed] oder [RoomSocketListener.onFailure], auch
 * wenn vorher [RoomSocketListener.onOpen] kam.
 */
interface RoomTransport {
    fun open(url: String, listener: RoomSocketListener): RoomSocket
}

interface RoomSocket {
    /** `false`, wenn die Verbindung zu ist oder der Puffer überläuft. */
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
    fun cancel()
}

interface RoomSocketListener {
    fun onOpen()
    fun onMessage(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(error: Throwable)
}
