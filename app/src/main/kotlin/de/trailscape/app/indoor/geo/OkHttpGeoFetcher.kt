package de.trailscape.app.indoor.geo

import de.trailscape.core.indoor.world.GeoFetcher
import de.trailscape.core.indoor.world.GeoRequest
import de.trailscape.core.indoor.world.GeoResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Der **rohe** Netzabruf fuer Geodaten (docs/indoor.md, Abschnitt 8.3) mit
 * OkHttp. Blockierend, immer GET.
 *
 * Niemand ruft diese Klasse direkt: Positivliste, Plattencache und Drosselung
 * legt `createGeoPipeline` als Huelle darum. Hier gilt nur das, was die Huelle
 * nicht leisten kann:
 *  * **nur `https`** — alles andere wird hart verweigert ([IOException]);
 *  * **keine Weiterleitungen**: Eine 3xx-Antwort kommt unveraendert als
 *    [GeoResponse] zurueck (die Huelle prueft die URL vor dem Senden; eine
 *    Umleitung auf einen anderen Host waere sonst ein Loch in der Positivliste).
 *    Das wird hier auch dann erzwungen, wenn der hereingereichte Client
 *    Weiterleitungen folgen wuerde;
 *  * `User-Agent: Trailscape/<versionName>` ohne jede Geraetekennung (den
 *    Wert reicht der Aufrufer herein).
 *
 * HTTP-Fehlerstatus (404, 429, 503 …) sind keine Ausnahmen, sondern Antworten;
 * bei Netzfehlern wirft [fetch] eine [IOException].
 */
class OkHttpGeoFetcher private constructor(
    client: OkHttpClient,
    private val userAgent: String,
    private val requireHttps: Boolean,
) : GeoFetcher {

    constructor(client: OkHttpClient, userAgent: String) : this(client, userAgent, requireHttps = true)

    /** Der Client, in jedem Fall ohne Weiterleitungen. */
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun fetch(request: GeoRequest): GeoResponse {
        val okRequest = buildRequest(request)
        http.newCall(okRequest).execute().use { response ->
            val body = response.body
            val declared = body?.contentLength() ?: -1L
            if (declared > MAX_BODY_BYTES) throw IOException("Geodaten-Antwort zu gross ($declared Bytes)")
            val bytes = body?.bytes() ?: ByteArray(0)
            if (bytes.size > MAX_BODY_BYTES) throw IOException("Geodaten-Antwort zu gross (${bytes.size} Bytes)")
            return GeoResponse(
                status = response.code,
                contentType = response.header("Content-Type"),
                bytes = bytes,
            )
        }
    }

    /** Herausgezogen, damit der Test die Kopfzeilen ohne Netz pruefen kann. */
    internal fun buildRequest(request: GeoRequest): Request {
        val scheme = runCatching { java.net.URI(request.url).scheme }.getOrNull()
        if (requireHttps && !"https".equals(scheme, ignoreCase = true)) {
            throw IOException("Geodaten werden nur ueber https abgerufen")
        }
        return Request.Builder()
            .url(request.url)
            .get()
            .header("User-Agent", userAgent)
            .apply { request.accept?.let { header("Accept", it) } }
            .build()
    }

    companion object {
        /** Obergrenze einer einzelnen Antwort (Kacheln sind weit kleiner). */
        const val MAX_BODY_BYTES: Long = 64L * 1024 * 1024

        /**
         * Der Client: Timeouts wie `OkHttpClientAdapter` (15 s je Phase),
         * Weiterleitungen aus.
         */
        fun createClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** Nur fuer Tests: erlaubt `http` (MockWebServer spricht kein TLS). */
        internal fun forTesting(client: OkHttpClient, userAgent: String): OkHttpGeoFetcher =
            OkHttpGeoFetcher(client, userAgent, requireHttps = false)
    }
}
