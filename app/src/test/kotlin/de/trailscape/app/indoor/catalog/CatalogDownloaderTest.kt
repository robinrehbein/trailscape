package de.trailscape.app.indoor.catalog

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * Der Paket-Download gegen einen gefaelschten Server ([MockWebServer], ohne
 * TLS — die https-Positivliste wird fuer den Test durch „localhost erlaubt"
 * ersetzt; die echte Liste prueft `IndoorCatalogTest` in `:core` und ein
 * eigener Fall hier).
 */
class CatalogDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    private val payload = ByteArray(700_000) { (it * 7 + it / 13).toByte() }
    private val sha = sha(payload)

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("catalog-dl").toFile()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun downloader(allowed: (String) -> Boolean = { it.startsWith(server.url("/").toString()) }) =
        CatalogDownloader(CatalogDownloader.createClient(), "Trailscape/9.9.9", allowed)

    /** Ein Server, der Range versteht (wie release-assets.githubusercontent.com). */
    private fun rangeServer(body: ByteArray = payload, honourRange: Boolean = true) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null && honourRange) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    if (from >= body.size) return MockResponse().setResponseCode(416)
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                        .setBody(Buffer().write(body.copyOfRange(from, body.size)))
                }
                return MockResponse().setBody(Buffer().write(body))
            }
        }
    }

    @Test
    fun laedtVollstaendigUndPrueft() {
        rangeServer()
        val part = File(dir, "a.zip.part")
        val progress = ArrayList<Long>()
        val r = downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha, { d, _ -> progress += d })
        assertIs<CatalogFetchResult.Done>(r)
        assertContentEquals(payload, part.readBytes())
        assertEquals(payload.size.toLong(), progress.last())
        val req = server.takeRequest()
        assertNull(req.getHeader("Range"))
        assertEquals("Trailscape/9.9.9", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Cookie"))
    }

    @Test
    fun abbruchUndWiederaufnahmeMitRange() {
        rangeServer()
        val part = File(dir, "a.zip.part")
        var calls = 0
        val first = downloader().download(
            server.url("/a.zip").toString(), part, payload.size.toLong(), sha,
            isCancelled = { calls++ > 3 },
        )
        assertEquals(CatalogFetchResult.Cancelled, first)
        val partial = part.length()
        assertTrue(partial in 1 until payload.size, "Teil bleibt liegen: $partial")
        server.takeRequest()

        val second = downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha)
        assertIs<CatalogFetchResult.Done>(second)
        assertEquals("bytes=$partial-", server.takeRequest().getHeader("Range"))
        assertContentEquals(payload, part.readBytes())
    }

    @Test
    fun serverOhneRangeBeginntNeu() {
        rangeServer(honourRange = false)
        val part = File(dir, "a.zip.part")
        part.writeBytes(payload.copyOf(1000))
        val r = downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha)
        assertIs<CatalogFetchResult.Done>(r)
        assertContentEquals(payload, part.readBytes())
    }

    @Test
    fun schonVollstaendigerTeil416() {
        rangeServer()
        val part = File(dir, "a.zip.part")
        part.writeBytes(payload)
        assertIs<CatalogFetchResult.Done>(downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha))
    }

    @Test
    fun pruefsummenfehlerVerwirftDenTeil() {
        val broken = payload.copyOf().also { it[123] = (it[123] + 1).toByte() }
        rangeServer(body = broken)
        val part = File(dir, "a.zip.part")
        val r = downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha)
        assertEquals(CatalogFetchResult.ChecksumMismatch, r)
        assertFalse(part.exists())
    }

    @Test
    fun zuLangeAntwortIstPruefsummenfehler() {
        rangeServer(body = payload + ByteArray(10))
        val part = File(dir, "a.zip.part")
        assertEquals(CatalogFetchResult.ChecksumMismatch, downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha))
        assertFalse(part.exists())
    }

    @Test
    fun fehlendesPaket404() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        val part = File(dir, "a.zip.part")
        assertEquals(CatalogFetchResult.NotAvailable, downloader().download(server.url("/a.zip").toString(), part, 10, sha))
        assertFalse(part.exists())
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(CatalogFetchResult.NotAvailable, downloader().fetchText(server.url("/catalog.json").toString()))
    }

    @Test
    fun serverfehlerUndAbbruchDerVerbindung() {
        server.enqueue(MockResponse().setResponseCode(503))
        val part = File(dir, "a.zip.part")
        val r = downloader().download(server.url("/a.zip").toString(), part, payload.size.toLong(), sha)
        assertIs<CatalogFetchResult.Failed>(r)
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val offline = downloader { true }.download("http://127.0.0.1:$closedPort/a.zip", part, payload.size.toLong(), sha)
        assertTrue((offline as CatalogFetchResult.Failed).offline)
    }

    @Test
    fun weiterleitungNurAufErlaubteZiele() {
        rangeServer()
        // Weiterleitung innerhalb des erlaubten Servers wird verfolgt …
        val okServer = MockWebServer()
        okServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/blob?sig=1").toString()))
        okServer.start()
        try {
            val part = File(dir, "a.zip.part")
            val allowed = { url: String -> url.startsWith(okServer.url("/").toString()) || url.startsWith(server.url("/").toString()) }
            val r = downloader(allowed).download(okServer.url("/a.zip").toString(), part, payload.size.toLong(), sha)
            assertIs<CatalogFetchResult.Done>(r)
            assertEquals("/blob?sig=1", server.takeRequest().path)

            // … eine auf einen fremden Host nicht.
            okServer.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/x"))
            part.delete()
            val denied = downloader(allowed).download(okServer.url("/a.zip").toString(), part, payload.size.toLong(), sha)
            assertIs<CatalogFetchResult.Failed>(denied)
            assertTrue(denied.detail.contains("evil.example"))
        } finally {
            okServer.shutdown()
        }
    }

    @Test
    fun echtePositivlisteLaesstKeinHttpZu() {
        val r = CatalogDownloader(CatalogDownloader.createClient(), "T").fetchText(server.url("/catalog.json").toString())
        assertIs<CatalogFetchResult.Failed>(r)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun katalogText() {
        server.enqueue(MockResponse().setBody("{\"format\":1}"))
        val r = downloader().fetchText(server.url("/catalog.json").toString())
        assertEquals("{\"format\":1}", (r as CatalogFetchResult.Done).text)
    }
}
