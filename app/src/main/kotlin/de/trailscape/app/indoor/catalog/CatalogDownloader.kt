package de.trailscape.app.indoor.catalog

import de.trailscape.core.indoor.catalog.isAllowedCatalogUrl
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Ergebnis eines Abrufs (Katalog oder Paket). */
sealed interface CatalogFetchResult {
    /** Fertig: Text des Katalogs bzw. die vollstaendige, gepruefte Paketdatei. */
    data class Done(val text: String? = null, val file: File? = null) : CatalogFetchResult

    /** 404/410: das Paket (oder der Katalog im Release) gibt es (noch) nicht. */
    data object NotAvailable : CatalogFetchResult

    /** Vom Nutzer abgebrochen; Teildatei bleibt fuer die Wiederaufnahme liegen. */
    data object Cancelled : CatalogFetchResult

    /** Groesse oder SHA-256 stimmen nicht; die Teildatei ist geloescht. */
    data object ChecksumMismatch : CatalogFetchResult

    /** Netz weg, Server-Fehler, unzulaessige Weiterleitung … [detail] fuer die „Technische Einzelheit". */
    data class Failed(val detail: String, val offline: Boolean = false) : CatalogFetchResult
}

/**
 * Laedt Katalog und Pakete aus dem GitHub-Release (docs/indoor/catalog.md).
 *
 * * Nur nach einer Nutzeraktion aufgerufen (der Aufrufer sorgt dafuer).
 * * **Weiterleitungen folgt OkHttp nicht selbst**: `github.com` antwortet
 *   mit `302` auf den Speicher der Release-Assets; jede Station wird gegen
 *   [isAllowed] geprueft (Vorgabe [isAllowedCatalogUrl]: nur https, nur die
 *   GitHub-Hosts), hoechstens [MAX_REDIRECTS].
 * * **Wiederaufnahme** ueber `Range: bytes=<n>-` auf die vorhandene
 *   Teildatei: `206` haengt an, `200` beginnt neu (Server ohne Range),
 *   `416` heisst „schon vollstaendig".
 * * **Pruefung**: Groesse und SHA-256 der fertigen Datei gegen den Katalog;
 *   stimmt etwas nicht, wird die Teildatei geloescht.
 * * `User-Agent: Trailscape/<versionName>`, keine Geraetekennung, keine
 *   Cookies, kein Konto.
 *
 * Blockierend; gehoert auf `Dispatchers.IO`.
 */
class CatalogDownloader(
    client: OkHttpClient,
    private val userAgent: String,
    private val isAllowed: (String) -> Boolean = ::isAllowedCatalogUrl,
) {
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Holt den (kleinen) Katalog aus dem Release. */
    fun fetchText(url: String, maxBytes: Long = MAX_CATALOG_BYTES): CatalogFetchResult = try {
        execute(url, rangeFrom = 0L) { response ->
            when (response.code) {
                200 -> {
                    val body = response.body ?: return@execute CatalogFetchResult.Failed("leere Antwort")
                    if (body.contentLength() > maxBytes) return@execute CatalogFetchResult.Failed("Katalog zu gross")
                    val bytes = body.byteStream().use { it.readNBytesCompat(maxBytes + 1) }
                    if (bytes.size > maxBytes) CatalogFetchResult.Failed("Katalog zu gross") else CatalogFetchResult.Done(text = bytes.toString(Charsets.UTF_8))
                }
                404, 410 -> CatalogFetchResult.NotAvailable
                else -> CatalogFetchResult.Failed("HTTP ${response.code}")
            }
        }
    } catch (e: IOException) {
        CatalogFetchResult.Failed(e.toString(), offline = true)
    }

    /**
     * Laedt [url] nach [part] (setzt eine vorhandene Teildatei fort) und
     * prueft sie gegen [expectedBytes] (0 = unbekannt) und [expectedSha256].
     *
     * @param onProgress (geladen, gesamt) — gesamt 0, wenn unbekannt.
     * @param isCancelled zwischen den Bloecken gefragt.
     * @param headers zusaetzliche Kopfzeilen (Server-Bau: `Authorization` des
     *   eigenen Servers). Sie gehen an jede Station, die [isAllowed] erlaubt —
     *   der Aufrufer erlaubt dann nur die Adresse des eigenen Servers.
     */
    fun download(
        url: String,
        part: File,
        expectedBytes: Long,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        headers: Map<String, String> = emptyMap(),
    ): CatalogFetchResult {
        part.parentFile?.mkdirs()
        var have = if (part.isFile) part.length() else 0L
        if (expectedBytes > 0 && have > expectedBytes) {
            part.delete()
            have = 0L
        }
        val result = try {
            execute(url, rangeFrom = have, headers = headers) { response -> receive(response, part, have, expectedBytes, onProgress, isCancelled) }
        } catch (e: IOException) {
            CatalogFetchResult.Failed(e.toString(), offline = true)
        }
        if (result !is CatalogFetchResult.Done) return result
        val size = part.length()
        if ((expectedBytes > 0 && size != expectedBytes) || sha256Of(part) != expectedSha256.lowercase()) {
            part.delete()
            return CatalogFetchResult.ChecksumMismatch
        }
        return CatalogFetchResult.Done(file = part)
    }

    private fun receive(
        response: Response,
        part: File,
        have: Long,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): CatalogFetchResult {
        val append = when (response.code) {
            206 -> {
                // Content-Range muss genau an unserem Stand beginnen.
                val start = response.header("Content-Range")?.let { CONTENT_RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (start != have) return CatalogFetchResult.Failed("Content-Range passt nicht: ${response.header("Content-Range")}")
                true
            }
            200 -> false
            416 -> return if (have > 0) CatalogFetchResult.Done(file = part) else CatalogFetchResult.Failed("HTTP 416")
            404, 410 -> return CatalogFetchResult.NotAvailable
            else -> return CatalogFetchResult.Failed("HTTP ${response.code}")
        }
        val body = response.body ?: return CatalogFetchResult.Failed("leere Antwort")
        val startAt = if (append) have else 0L
        val total = when {
            expectedBytes > 0 -> expectedBytes
            body.contentLength() >= 0 -> startAt + body.contentLength()
            else -> 0L
        }
        var done = startAt
        onProgress(done, total)
        FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                var lastReport = 0L
                while (true) {
                    if (isCancelled()) return CatalogFetchResult.Cancelled
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (expectedBytes > 0 && done > expectedBytes) {
                        out.close()
                        part.delete()
                        return CatalogFetchResult.ChecksumMismatch
                    }
                    if (done - lastReport >= REPORT_EVERY) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
            }
        }
        onProgress(done, total)
        return CatalogFetchResult.Done(file = part)
    }

    /** Fuehrt GET aus und folgt Weiterleitungen nur auf erlaubte Ziele. */
    private fun execute(
        url: String,
        rangeFrom: Long,
        headers: Map<String, String> = emptyMap(),
        handle: (Response) -> CatalogFetchResult,
    ): CatalogFetchResult {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowed(current)) return CatalogFetchResult.Failed("Adresse nicht erlaubt: ${hostOf(current)}")
            val request = Request.Builder()
                .url(current)
                .get()
                .header("User-Agent", userAgent)
                .apply { if (rangeFrom > 0) header("Range", "bytes=$rangeFrom-") }
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            http.newCall(request).execute().use { response ->
                if (response.code in REDIRECTS) {
                    val location = response.header("Location") ?: return CatalogFetchResult.Failed("Weiterleitung ohne Ziel")
                    current = response.request.url.resolve(location)?.toString()
                        ?: return CatalogFetchResult.Failed("Weiterleitung unlesbar")
                    return@repeat
                }
                return handle(response)
            }
        }
        return CatalogFetchResult.Failed("zu viele Weiterleitungen")
    }

    companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_CATALOG_BYTES: Long = 1L * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256L * 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("^bytes (\\d+)-")

        /** Timeouts wie der Geodaten-Client, Lesefrist laenger (grosse Dateien ueber Mobilfunk). */
        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: "?"

        fun sha256Of(file: File): String = file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (total < limit) {
                val n = read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}
