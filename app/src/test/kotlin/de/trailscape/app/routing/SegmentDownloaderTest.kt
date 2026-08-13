package de.trailscape.app.routing

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests des Downloaders (`SegmentDownloader.kt`) gegen einen **echten
 * HTTP-Server** — nur eben einen winzigen, der im Test selbst laeuft.
 *
 * ## Warum ein eigener Server und kein Mock
 * Geprueft werden soll genau das, was zwischen Client und Server passiert:
 * `Range`, `If-Range`, `206` gegen `200`, eine mitten im Rumpf abgerissene
 * Verbindung. Ein abgefangener OkHttp-Aufruf wuerde nur die Erwartungen des
 * Tests wiederholen. Der Server unten ist rund hundert Zeilen roher Socket —
 * bewusst ohne zusaetzliche Bibliothek, damit der Testlauf keine neue
 * Abhaengigkeit braucht (MockWebServer waere die naheliegende, ist aber im
 * Projekt nicht vorhanden).
 *
 * Der Beweis am **echten** Server (brouter.de, 119 MB, Abbruch und
 * Fortsetzen) steht daneben in `SegmentDownloadManualTest.kt` und laeuft nur
 * auf Zuruf.
 */
class SegmentDownloaderTest {

    private val fileName = "E10_N50.rd5"
    private val dir = createTempDir()
    private val inventory = SegmentInventory(dir, SegmentMetadataStore(MemoryKeyValueStore()))
    private var server: TinyHttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.close()
    }

    // -----------------------------------------------------------------------
    // Vollabzug
    // -----------------------------------------------------------------------

    @Test
    fun `laedt eine Kachel vollstaendig und merkt sich ETag und Last-Modified`() {
        val content = randomBytes(300_000)
        val server = start(TileServer(content))

        val result = assertIs<SegmentSyncResult.Updated>(downloader(server).sync(fileName))

        assertEquals(content.size.toLong(), result.bytesTransferred)
        assertContentEquals(content, File(dir, fileName).readBytes())
        // Keine Teildatei mehr, und die Engine sieht nie eine halbe Kachel.
        assertTrue(File(dir, fileName + SEGMENT_PART_SUFFIX).exists().not())

        val meta = inventory.metadata.read(fileName)
        assertEquals(TileServer.ETAG, meta.eTag)
        assertEquals(TileServer.LAST_MODIFIED, meta.lastModified)
        assertNotNull(meta.downloadedAtMs)
    }

    @Test
    fun `setzt nach einer abgerissenen Verbindung per Range fort statt von vorn`() {
        val content = randomBytes(300_000)
        val handler = TileServer(content)
        // Der erste Versuch bricht nach 100 kB mitten im Rumpf ab.
        handler.cutAfterBytes = 100_000
        val server = start(handler)
        val downloader = downloader(server)

        // Erster Anlauf: scheitert, laesst aber die Teildatei liegen.
        assertFailsWith<Exception> { downloader.sync(fileName) }
        val partial = inventory.partialBytes(fileName)
        assertTrue(partial in 1 until content.size.toLong(), "Teildatei: $partial")

        // Zweiter Anlauf: der Server liefert wieder vollstaendig.
        handler.cutAfterBytes = null
        val result = assertIs<SegmentSyncResult.Updated>(downloader.sync(fileName))

        assertContentEquals(content, File(dir, fileName).readBytes())
        // Der Kern der Sache: nur der Rest ging noch einmal ueber die Leitung.
        assertEquals(content.size - partial, result.bytesTransferred)
        assertEquals("bytes=$partial-", handler.lastRangeHeader)
        assertEquals(TileServer.ETAG, handler.lastIfRangeHeader)
        assertEquals(206, handler.lastStatus)
    }

    @Test
    fun `verwirft die Teildatei, wenn sich die Kachel auf dem Server geaendert hat`() {
        val first = randomBytes(200_000)
        val handler = TileServer(first)
        handler.cutAfterBytes = 80_000
        val server = start(handler)
        val downloader = downloader(server)

        assertFailsWith<Exception> { downloader.sync(fileName) }
        assertTrue(inventory.partialBytes(fileName) > 0)

        // Neuer Kachelstand: anderer Inhalt, anderes Kennzeichen.
        val second = randomBytes(210_000)
        handler.content = second
        handler.eTag = "\"v2\""
        handler.cutAfterBytes = null

        assertIs<SegmentSyncResult.Updated>(downloader.sync(fileName))

        // Entscheidend: keine Datei, die vorn vom alten und hinten vom neuen
        // Stand ist. Der Server hat den Range wegen If-Range verworfen.
        assertContentEquals(second, File(dir, fileName).readBytes())
        assertEquals(200, handler.lastStatus)
    }

    @Test
    fun `bricht ab und laesst das Geladene fuer den naechsten Versuch liegen`() {
        val content = randomBytes(1_000_000)
        val server = start(TileServer(content))
        val calls = AtomicInteger()

        assertIs<SegmentSyncResult.Cancelled>(
            downloader(server).sync(
                fileName = fileName,
                isCancelled = { calls.incrementAndGet() > 3 },
            ),
        )
        assertTrue(File(dir, fileName).exists().not(), "die Kachel darf nicht eingesetzt sein")
        assertTrue(inventory.partialBytes(fileName) > 0, "das Geladene muss liegen bleiben")
    }

    // -----------------------------------------------------------------------
    // Aktualitaet
    // -----------------------------------------------------------------------

    @Test
    fun `gleicher Stand heisst nichts laden`() {
        val content = randomBytes(50_000)
        val handler = TileServer(content)
        val server = start(handler)
        val downloader = downloader(server)

        assertIs<SegmentSyncResult.Updated>(downloader.sync(fileName))
        val requestsAfterFirst = handler.bodyRequests.get()

        assertIs<SegmentSyncResult.AlreadyCurrent>(downloader.sync(fileName))
        assertEquals(requestsAfterFirst, handler.bodyRequests.get(), "es wurde erneut geladen")
        assertTrue(downloader.hasUpdate(fileName).not())
    }

    @Test
    fun `ein neuer Serverstand wird als Aktualisierung erkannt`() {
        val handler = TileServer(randomBytes(50_000))
        val server = start(handler)
        val downloader = downloader(server)
        downloader.sync(fileName)

        handler.content = randomBytes(60_000)
        handler.eTag = "\"v2\""
        assertTrue(downloader.hasUpdate(fileName))
    }

    // -----------------------------------------------------------------------
    // Delta
    // -----------------------------------------------------------------------

    @Test
    fun `das leere Delta bedeutet bereits aktuell und laedt keine Bytes`() {
        val content = randomBytes(50_000)
        val handler = TileServer(content)
        val server = start(handler)
        val downloader = downloader(server)
        downloader.sync(fileName)

        // Der Server meldet neue Kopfzeilen, der Inhalt ist aber derselbe —
        // genau die Lage, in der er ein Delta der Laenge 0 vorhaelt.
        handler.eTag = "\"v2\""
        handler.emptyDeltaForCurrentContent = true
        val bodiesBefore = handler.bodyRequests.get()

        assertIs<SegmentSyncResult.AlreadyCurrent>(downloader.sync(fileName))

        assertEquals(bodiesBefore, handler.bodyRequests.get(), "es wurde unnoetig geladen")
        // Die neuen Kopfzeilen sind uebernommen, sonst fragt er ewig nach.
        assertEquals("\"v2\"", inventory.metadata.read(fileName).eTag)
    }

    @Test
    fun `ohne passendes Delta faellt er auf den Vollabzug zurueck`() {
        val handler = TileServer(randomBytes(50_000))
        val server = start(handler)
        val downloader = downloader(server)
        downloader.sync(fileName)

        // Neuer Stand, aber der Server hat kein Delta fuer unsere Fassung
        // (404) — dann muss der Vollabzug kommen.
        val second = randomBytes(70_000)
        handler.content = second
        handler.eTag = "\"v2\""

        val result = assertIs<SegmentSyncResult.Updated>(downloader.sync(fileName))

        assertTrue(result.viaDelta.not())
        assertNotNull(result.deltaFallbackReason)
        assertContentEquals(second, File(dir, fileName).readBytes())
    }

    // -----------------------------------------------------------------------
    // Schutz
    // -----------------------------------------------------------------------

    @Test
    fun `Namen, die keine Kachel sind, kommen gar nicht erst ins Netz`() {
        val server = start(TileServer(randomBytes(10)))
        val downloader = downloader(server)
        for (name in listOf("../../etc/passwd", "E10_N50", "lookups.dat", "E11_N50.rd5")) {
            assertFailsWith<SegmentDownloadException> { downloader.sync(name) }
        }
    }

    // -----------------------------------------------------------------------
    // Hilfen
    // -----------------------------------------------------------------------

    private fun downloader(server: TinyHttpServer) =
        SegmentDownloader(inventory, baseUrl = "http://127.0.0.1:${server.port}/")

    private fun start(handler: TileServer): TinyHttpServer {
        val started = TinyHttpServer(handler)
        server = started
        return started
    }

    private fun randomBytes(size: Int): ByteArray = Random(size).nextBytes(size)

    private fun createTempDir(): File {
        val dir = File.createTempFile("trailscape-download", "")
        check(dir.delete() && dir.mkdirs())
        dir.deleteOnExit()
        return dir
    }
}

// ---------------------------------------------------------------------------
// Ein Server, der sich wie brouter.de verhaelt
// ---------------------------------------------------------------------------

/** Eine eingegangene Anfrage, auf das Noetige eingedampft. */
internal data class TinyRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
)

/**
 * Bildet die Teile von brouter.de nach, auf die sich der Downloader stuetzt:
 * `HEAD` mit `Content-Length`/`ETag`/`Last-Modified`/`Accept-Ranges`,
 * `Range`-Anfragen mit `If-Range`, und das Delta-Verzeichnis.
 */
internal class TileServer(@Volatile var content: ByteArray) {

    @Volatile var eTag: String = ETAG
    @Volatile var lastModified: String = LAST_MODIFIED

    /** Wenn gesetzt, bricht die Antwort nach so vielen Bytes einfach ab. */
    @Volatile var cutAfterBytes: Int? = null

    /** Wenn `true`, gibt es zum MD5 des aktuellen Inhalts ein leeres Delta. */
    @Volatile var emptyDeltaForCurrentContent: Boolean = false

    /** Zaehlt Anfragen mit Rumpf (also echte Downloads). */
    val bodyRequests = AtomicInteger()

    @Volatile var lastRangeHeader: String? = null
    @Volatile var lastIfRangeHeader: String? = null
    @Volatile var lastStatus: Int = 0

    fun handle(request: TinyRequest, out: OutputStream) {
        if (request.path.startsWith("/diff/")) {
            handleDiff(request, out)
            return
        }
        if (!request.path.endsWith(".rd5")) {
            respond(out, 404, emptyMap(), null)
            return
        }

        val headers = linkedMapOf(
            "ETag" to eTag,
            "Last-Modified" to lastModified,
            "Accept-Ranges" to "bytes",
            "Content-Type" to "application/octet-stream",
        )

        if (request.method == "HEAD") {
            lastStatus = 200
            respond(out, 200, headers + ("Content-Length" to content.size.toString()), null)
            return
        }

        lastRangeHeader = request.headers["range"]
        lastIfRangeHeader = request.headers["if-range"]
        bodyRequests.incrementAndGet()

        // Wie ein echter Server: Bei unpassendem If-Range wird der Range
        // stillschweigend ignoriert und die ganze Datei geliefert.
        val requested = rangeStart(request)
        val from = requested?.takeIf {
            it < content.size && (lastIfRangeHeader == null || lastIfRangeHeader == eTag)
        }

        val body = if (from != null) content.copyOfRange(from, content.size) else content
        lastStatus = if (from != null) 206 else 200
        val withLength = headers + buildMap {
            put("Content-Length", body.size.toString())
            if (from != null) {
                put("Content-Range", "bytes $from-${content.size - 1}/${content.size}")
            }
        }
        respond(out, lastStatus, withLength, body)
    }

    private fun handleDiff(request: TinyRequest, out: OutputStream) {
        val md5 = request.path.substringAfterLast('/').removeSuffix(".df5")
        val current = md5(content)
        if (emptyDeltaForCurrentContent && md5 == current) {
            lastStatus = 200
            val body = if (request.method == "HEAD") null else ByteArray(0)
            respond(out, 200, mapOf("Content-Length" to "0"), body)
            return
        }
        lastStatus = 404
        respond(out, 404, emptyMap(), null)
    }

    private fun rangeStart(request: TinyRequest): Int? =
        request.headers["range"]
            ?.removePrefix("bytes=")
            ?.substringBefore('-')
            ?.toIntOrNull()

    private fun respond(out: OutputStream, status: Int, headers: Map<String, String>, body: ByteArray?) {
        val text = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        headers.forEach { (name, value) -> text.append("$name: $value\r\n") }
        if (!headers.containsKey("Content-Length")) text.append("Content-Length: 0\r\n")
        text.append("Connection: close\r\n\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        if (body != null) {
            val cut = cutAfterBytes
            if (cut != null && cut < body.size) {
                // Abgerissene Verbindung: Kopfzeile verspricht mehr, als kommt.
                out.write(body, 0, cut)
            } else {
                out.write(body)
            }
        }
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        else -> "Not Found"
    }

    private fun md5(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ETAG = "\"v1\""
        const val LAST_MODIFIED = "Thu, 13 Aug 2026 01:03:01 GMT"
    }
}

/**
 * Ein HTTP/1.1-Server aus rohem Socket — genug fuer die Faelle oben.
 *
 * Jede Verbindung wird einmal bedient und dann geschlossen
 * (`Connection: close`); damit braucht es weder Keep-Alive-Buchhaltung noch
 * Chunked-Encoding.
 */
internal class TinyHttpServer(private val handler: TileServer) : Closeable {

    private val socket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val open = Collections.synchronizedList(mutableListOf<Socket>())

    val port: Int get() = socket.localPort

    private val thread = Thread {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                return@Thread
            }
            open.add(client)
            runCatching { serve(client) }
            runCatching { client.close() }
        }
    }.apply {
        isDaemon = true
        start()
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val request = readRequest(input) ?: return
        handler.handle(request, client.getOutputStream())
    }

    private fun readRequest(input: BufferedInputStream): TinyRequest? {
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.clear()
                if (text.isEmpty()) break
                lines.add(text)
            } else {
                line.append(byte.toChar())
            }
        }
        if (lines.isEmpty()) return null
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val index = header.indexOf(':')
            if (index <= 0) null else header.take(index).lowercase() to header.substring(index + 1).trim()
        }.toMap()
        return TinyRequest(method = parts[0], path = parts[1], headers = headers)
    }

    override fun close() {
        runCatching { socket.close() }
        synchronized(open) { open.forEach { runCatching { it.close() } } }
        thread.interrupt()
    }
}
