package de.trailscape.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomInviteTest {
    private val roomId = "AbCdEfGhIjKlMnOp"
    private val secret = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x"

    @Test
    fun `Link hin und zurueck, Geheimnis im Fragment`() {
        val invite = assertNotNull(RoomInvite.of("https://sync.example.org/", roomId, secret))
        val link = invite.toLink()
        assertEquals("trailscape://room?server=https%3A%2F%2Fsync.example.org&room=$roomId#$secret", link)
        assertEquals(invite, RoomInvite.parse(link))
        assertEquals("wss://sync.example.org/api/rooms/ws", invite.webSocketUrl())
    }

    @Test
    fun `Unterpfad und Port bleiben erhalten`() {
        val invite = assertNotNull(RoomInvite.of("https://Example.org:8443/trailscape/", roomId, secret))
        assertEquals("https://example.org:8443/trailscape", invite.serverUrl)
        assertEquals("wss://example.org:8443/trailscape/api/rooms/ws", invite.webSocketUrl())
        assertEquals(invite, RoomInvite.parse(invite.toLink()))
    }

    @Test
    fun `http nur fuer das eigene Geraet und den Emulator`() {
        assertNull(RoomInvite.of("http://sync.example.org", roomId, secret))
        assertNotNull(RoomInvite.of("http://10.0.2.2:8080", roomId, secret))
        assertNotNull(RoomInvite.of("http://localhost:8080", roomId, secret))
        assertEquals("ws://localhost:8080/api/rooms/ws", RoomInvite.webSocketUrl("http://localhost:8080"))
        assertNull(RoomInvite.of("ftp://example.org", roomId, secret))
        assertNull(RoomInvite.of("https://user:pw@example.org", roomId, secret))
        assertNull(RoomInvite.of("https://example.org/?x=1", roomId, secret))
    }

    @Test
    fun `kaputte Links werden abgewiesen`() {
        val ok = RoomInvite(server, roomId, secret).toLink()
        assertNotNull(RoomInvite.parse(ok))
        for (bad in listOf(
            "",
            "kein link",
            ok.substringBefore('#'), // ohne Geheimnis
            ok.replace("trailscape://", "https://"),
            ok.replace("://room", "://raum"),
            ok.replace(roomId, "kurz"),
            ok.replace(secret, "zu-kurz"),
            ok.replace("server=", "srv="),
            "trailscape://room?server=http%3A%2F%2Fexample.org&room=$roomId#$secret",
        )) {
            assertNull(RoomInvite.parse(bad), bad)
        }
    }

    @Test
    fun `toString verraet das Geheimnis nicht`() {
        val invite = assertNotNull(RoomInvite.of("https://x.org", roomId, secret))
        assertFalse(invite.toString().contains(secret))
        assertTrue(invite.toLink().contains(secret))
    }

    private val server = "https://sync.example.org"
}
