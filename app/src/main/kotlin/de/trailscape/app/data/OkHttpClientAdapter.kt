package de.trailscape.app.data

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import de.trailscape.core.HttpResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Implementierung von `de.trailscape.core.HttpClient` (siehe `HttpClient.kt`
 * in `:core`) mit OkHttp.
 *
 * Timeouts: Das Dart-Original (`lib/routing.dart`, `lib/sync_client.dart`,
 * `lib/geocoding.dart`) instanziiert an allen drei Aufrufstellen nur
 * `http.Client()` ohne jede explizite Timeout-Konfiguration bzw.
 * `.timeout(...)`-Aufruf — es verlaesst sich also auf die (praktisch
 * unbegrenzten) Defaults von `dart:io`s `HttpClient`. Fuer die native App ist
 * das bewusst NICHT uebernommen: ein Request ohne Obergrenze kann eine
 * Aufzeichnung oder einen Sync unbemerkt haengen lassen. Gewaehlt sind
 * pragmatische, fuer Mobilfunknetze grosszuegige 15 Sekunden je Phase
 * (Connect/Read/Write) — ausreichend fuer BRouter-Routing-Requests mit
 * groesseren Bodies, kurz genug, um eine haengende UI zu vermeiden. Diese
 * Werte sind bewusst nicht 1:1 aus Dart uebernommen, weil es dort schlicht
 * keine gibt.
 */
class OkHttpClientAdapter(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
) : HttpClient {

    override fun execute(request: HttpRequest): HttpResponse {
        // OkHttp verlangt fuer POST/PUT einen (ggf. leeren) Body, waehrend GET
        // gar keinen haben darf und DELETE ihn nur erlaubt — alle Aufrufstellen
        // in :core setzen bei POST/PUT zwar immer einen Body, dieser Fallback
        // macht den Adapter aber robust, falls das in Zukunft nicht mehr gilt.
        val requiresBody = request.method == HttpMethod.POST || request.method == HttpMethod.PUT
        val requestBodyText = request.body
        val body = when {
            requestBodyText != null -> requestBodyText.toRequestBody(JSON_MEDIA_TYPE)
            requiresBody -> "".toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }

        val okRequest = Request.Builder()
            .url(request.url)
            .apply {
                request.headers.forEach { (name, value) -> addHeader(name, value) }
            }
            .method(request.method.toOkHttpMethod(), body)
            .build()

        try {
            client.newCall(okRequest).execute().use { response ->
                // .string() liest den kompletten Body ein und schliesst ihn danach;
                // das entspricht Darts `http.Response.body`, das ebenfalls den
                // gesamten Text vorab einliest (kein Streaming-API im HttpClient-
                // Interface von `:core`).
                val body = response.body?.string() ?: ""
                return HttpResponse(statusCode = response.code, body = body)
            }
        } catch (e: IOException) {
            // Bewusst unveraendert weitergeworfen: HttpClient.execute() im
            // `:core`-Interface darf bei Netzwerkfehlern werfen (siehe KDoc
            // dort), und Routing/Geocoding/SyncClient fangen es dort ab und
            // uebersetzen es in eine deutsche Fehlermeldung — analog zu Darts
            // `SocketException`, die dort ebenso durchgereicht wird.
            throw e
        }
    }

    private fun HttpMethod.toOkHttpMethod(): String = when (this) {
        HttpMethod.GET -> "GET"
        HttpMethod.POST -> "POST"
        HttpMethod.PUT -> "PUT"
        HttpMethod.DELETE -> "DELETE"
    }

    private companion object {
        // GET-Requests haben in :core nie einen Body (method(...) bekommt dann
        // `null`); POST/PUT senden durchgehend JSON (Sync-Server, Geocoding-
        // Anfragen) oder das BRouter-Profil als Klartext — OkHttp braucht fuer
        // Letzteres keinen exakten Subtyp, "application/json" reicht auch fuer
        // reinen Text als MediaType-Header.
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
