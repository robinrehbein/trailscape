package de.trailscape.app.data

import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.Request
import okio.Buffer

/**
 * Aufbau der OkHttp-Anfrage ([OkHttpClientAdapter.buildOkRequest]) — ohne
 * Netz. Wichtig ist vor allem der Content-Type: Er muss genau der des
 * Aufrufers sein, sonst kommt ein Multipart-Upload ohne Boundary an.
 */
class OkHttpClientAdapterTest {

    private val adapter = OkHttpClientAdapter()

    private fun Request.bodyBytes(): ByteArray = Buffer().also { body!!.writeTo(it) }.readByteArray()

    @Test
    fun `ohne Content-Type bleibt es JSON mit charset`() {
        val request = adapter.buildOkRequest(HttpRequest(HttpMethod.POST, "https://example.com/x", body = """{"a":"ä"}"""))
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertEquals("""{"a":"ä"}""", String(request.bodyBytes(), Charsets.UTF_8))
        assertNull(request.header("Content-Type"))
    }

    @Test
    fun `multipart-Typ wird exakt uebernommen`() {
        val body = "--x\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\nRunde über den Berg\r\n--x--\r\n"
        val request = adapter.buildOkRequest(
            HttpRequest(
                method = HttpMethod.POST,
                url = "https://www.strava.com/api/v3/uploads",
                headers = mapOf("Authorization" to "Bearer t", "content-type" to "multipart/form-data; boundary=x"),
                body = body,
            ),
        )
        assertEquals("multipart/form-data; boundary=x", request.body!!.contentType().toString())
        assertEquals(body.toByteArray(Charsets.UTF_8).toList(), request.bodyBytes().toList())
        assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), request.body!!.contentLength())
        assertEquals("Bearer t", request.header("Authorization"))
        // Nicht zusaetzlich als Header — der Typ steckt im Body.
        assertNull(request.header("Content-Type"))
    }

    @Test
    fun `form-urlencoded ohne charset-Zusatz`() {
        val request = adapter.buildOkRequest(
            HttpRequest(
                method = HttpMethod.POST,
                url = "https://www.strava.com/oauth/token",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body = "a=1&b=2",
            ),
        )
        assertEquals("application/x-www-form-urlencoded", request.body!!.contentType().toString())
        assertEquals("a=1&b=2", String(request.bodyBytes(), Charsets.UTF_8))
    }

    @Test
    fun `GET ohne Body, POST ohne Body bekommt einen leeren`() {
        val get = adapter.buildOkRequest(
            HttpRequest(HttpMethod.GET, "https://example.com/", headers = mapOf("Authorization" to "Bearer t")),
        )
        assertNull(get.body)
        assertEquals("Bearer t", get.header("Authorization"))

        val post = adapter.buildOkRequest(
            HttpRequest(
                HttpMethod.POST,
                "https://example.com/",
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            ),
        )
        assertEquals(0L, post.body!!.contentLength())
        assertEquals("application/x-www-form-urlencoded", post.body!!.contentType().toString())
    }
}
