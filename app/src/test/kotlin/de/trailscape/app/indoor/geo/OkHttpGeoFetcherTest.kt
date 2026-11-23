package de.trailscape.app.indoor.geo

import de.trailscape.core.indoor.world.GeoRequest
import de.trailscape.core.indoor.world.GeoSourceKind
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

/**
 * Der rohe Geodaten-Abruf gegen einen lokalen [MockWebServer]. Der Server
 * spricht kein TLS, deshalb baut der Test den Abruf ueber `forTesting`
 * (ohne https-Pflicht); die https-Pflicht selbst prueft ein eigener Test.
 */
class OkHttpGeoFetcherTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun fetcher(client: OkHttpClient = OkHttpGeoFetcher.createClient()) =
        OkHttpGeoFetcher.forTesting(client, "Trailscape/9.9.9")

    private fun request(path: String, accept: String? = null) =
        GeoRequest(GeoSourceKind.ELEVATION, server.url(path).toString(), accept)

    @Test
    fun `liefert Status, Content-Type und Bytes`() {
        val payload = byteArrayOf(0, 1, 2, 127, -128, -1)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(payload)))

        val response = fetcher().fetch(request("/tile.png"))

        assertEquals(200, response.status)
        assertEquals("image/png", response.contentType)
        assertContentEquals(payload, response.bytes)
        assertEquals(false, response.fromCache)
    }

    @Test
    fun `Status 404 kommt als Antwort und nicht als Ausnahme`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nicht da"))

        val response = fetcher().fetch(request("/fehlt.png"))

        assertEquals(404, response.status)
        assertEquals("nicht da", String(response.bytes))
    }

    @Test
    fun `Weiterleitung 302 wird nicht gefolgt`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/anderswo").toString()))
        server.enqueue(MockResponse().setBody("Ziel"))

        // Auch ein Client, der Weiterleitungen folgen wuerde, wird entschaerft.
        val folgsamerClient = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        val response = fetcher(folgsamerClient).fetch(request("/start"))

        assertEquals(302, response.status)
        assertEquals(1, server.requestCount, "das Weiterleitungsziel darf nicht abgerufen werden")
    }

    @Test
    fun `User-Agent und Accept werden gesetzt`() {
        server.enqueue(MockResponse().setBody("x"))

        fetcher().fetch(request("/a", accept = "image/jpeg"))

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("Trailscape/9.9.9", recorded.getHeader("User-Agent"))
        assertEquals("image/jpeg", recorded.getHeader("Accept"))
    }

    @Test
    fun `ohne Accept wird kein eigener Accept-Kopf gesendet`() {
        server.enqueue(MockResponse().setBody("x"))

        fetcher().fetch(request("/b"))

        val accept = server.takeRequest().getHeader("Accept")
        assertTrue(accept == null || accept == "*/*", "Accept war $accept")
    }

    @Test
    fun `Content-Type fehlt wird null`() {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1))))

        val response = fetcher().fetch(request("/c"))

        // MockWebServer setzt von sich aus keinen Typ.
        assertNull(response.contentType)
    }

    @Test
    fun `nicht-https wird hart verweigert`() {
        val strict = OkHttpGeoFetcher(OkHttpGeoFetcher.createClient(), "Trailscape/9.9.9")
        assertFailsWith<IOException> { strict.fetch(request("/x")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `createClient folgt keinen Weiterleitungen`() {
        val client = OkHttpGeoFetcher.createClient()
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
    }
}
