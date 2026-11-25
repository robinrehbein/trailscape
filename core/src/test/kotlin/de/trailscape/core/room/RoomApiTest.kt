package de.trailscape.core.room

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import de.trailscape.core.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomApiTest {
    private val secret = "s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-s3cr3t-x"
    private val hostSecret = "h0st-h0st-h0st-h0st-h0st-h0st-h0st-h0st-xyz"
    private val route = RoomRoute("katalog/eifel", 12345.6, loop = true, version = "3")

    private class Fake(val status: Int, val body: String) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return HttpResponse(status, body)
        }
    }

    private val createdBody = """{"v":1,"room":{"id":"AbCdEfGhIjKlMnOp","route":{"id":"katalog/eifel","lengthM":12345.6,"loop":true,"version":"3"},
        "maxRiders":10,"createdAt":1,"expiresAt":2,"startAt":null},"secret":"$secret","hostSecret":"$hostSecret","serverTime":5}"""

    @Test
    fun `Raum anlegen schickt Token und Strecke, liefert Einladung`() {
        val http = Fake(201, createdBody)
        val r = assertIs<RoomCreateResult.Created>(RoomApi(http).create("https://sync.example.org/", "TOKEN-1234567890", route, maxRiders = 4))
        val req = http.requests.single()
        assertEquals(HttpMethod.POST, req.method)
        assertEquals("https://sync.example.org/api/rooms", req.url)
        assertEquals("Bearer TOKEN-1234567890", req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!).jsonObject
        assertEquals("4", body["maxRiders"].toString())
        assertEquals("katalog/eifel", body["route"]!!.jsonObject["id"]!!.toString().trim('"'))
        assertEquals("AbCdEfGhIjKlMnOp", r.room.id)
        assertEquals(secret, r.invite.secret)
        assertEquals(hostSecret, r.hostSecret)
        assertFalse(r.invite.toLink().contains(hostSecret), "Gastgeber-Geheimnis nie im Link")
        assertFalse(r.invite.toLink().contains("TOKEN"), "SYNC_TOKEN nie im Link")
        assertFalse(r.toString().contains(hostSecret) || r.toString().contains(secret))
    }

    @Test
    fun `Fehlerfaelle`() {
        assertEquals(RoomCreateResult.Unauthorized, RoomApi(Fake(401, "{}")).create("https://x.org", "t", route))
        assertEquals(RoomCreateResult.Disabled, RoomApi(Fake(404, "{}")).create("https://x.org", "t", route))
        assertEquals(RoomCreateResult.ServerFull, RoomApi(Fake(429, """{"code":"ROOMS_FULL"}""")).create("https://x.org", "t", route))
        assertEquals(RoomCreateResult.Failed(429, "RATE"), RoomApi(Fake(429, "")).create("https://x.org", "t", route))
        assertEquals(RoomCreateResult.Failed(400, "INVALID"), RoomApi(Fake(400, """{"code":"INVALID"}""")).create("https://x.org", "t", route))
        assertEquals(RoomCreateResult.Failed(201, "BAD_RESPONSE"), RoomApi(Fake(201, "{}")).create("https://x.org", "t", route))
        assertEquals(RoomCreateResult.Failed(0, "INVALID_URL"), RoomApi(Fake(201, createdBody)).create("http://x.org", "t", route))
        val throwing = HttpClient { throw java.io.IOException("weg") }
        assertEquals(RoomCreateResult.Failed(0, "NETWORK"), RoomApi(throwing).create("https://x.org", "t", route))
    }

    @Test
    fun `Raum beenden`() {
        val http = Fake(204, "")
        assertTrue(RoomApi(http).delete("https://x.org", "t", "AbCdEfGhIjKlMnOp"))
        assertEquals(HttpMethod.DELETE, http.requests.single().method)
        assertEquals("https://x.org/api/rooms/AbCdEfGhIjKlMnOp", http.requests.single().url)
        assertTrue(RoomApi(Fake(404, "")).delete("https://x.org", "t", "AbCdEfGhIjKlMnOp"))
        assertFalse(RoomApi(Fake(500, "")).delete("https://x.org", "t", "AbCdEfGhIjKlMnOp"))
        assertFalse(RoomApi(Fake(204, "")).delete("https://x.org", "t", "../rides"))
    }
}
