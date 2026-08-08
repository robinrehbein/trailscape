package de.trailscape.core

/**
 * Schmale, plattformneutrale HTTP-Abstraktion.
 *
 * `:core` ist reines Kotlin/JVM ohne Zugriff auf einen konkreten HTTP-Stack
 * (Dart nutzt hier `package:http`, das es in Kotlin/JVM nicht gibt). Statt
 * einer echten Implementierung stellt `:core` nur diese schmale Schnittstelle
 * bereit; [Routing], [Geocoding] und [SyncClient] sind komplett dagegen
 * geschrieben (URL-/Body-Aufbau, Statuscode-Behandlung, deutsche
 * Fehlermeldungen). Eine echte Implementierung (z. B. mit `java.net.http`
 * oder OkHttp) kommt erst in Phase 3 in `:app` hinzu und wrappt die
 * synchronen Aufrufe hier mit `Dispatchers.IO`.
 *
 * In Tests wird [HttpClient] durch ein Fake ersetzt, das eingehende
 * [HttpRequest]s inspiziert und vorbereitete [HttpResponse]s zurueckgibt —
 * das Analogon zu Darts `package:http/testing.dart`-`MockClient`.
 */

/** HTTP-Methode einer [HttpRequest]. Nur die von Trailscape tatsaechlich benutzten. */
enum class HttpMethod { GET, POST, PUT }

/**
 * Eine einzelne HTTP-Anfrage.
 *
 * [body] ist bewusst ein String (kein Byte-Array): alle Request-Bodies in
 * Trailscape sind Text (JSON oder das BRouter-Profil als Klartext).
 */
data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** Antwort auf eine [HttpRequest]. [body] wird als Text interpretiert (UTF-8). */
data class HttpResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * Fuehrt eine [HttpRequest] aus und liefert die [HttpResponse].
 *
 * Darf bei Netzwerkfehlern (kein Server erreichbar, Timeout, ...) werfen —
 * das entspricht Darts `http.Client`, dessen `get`/`post`/`put` bei
 * Netzwerkproblemen z. B. eine `SocketException` werfen. Die aufrufenden
 * Funktionen in [Routing], [Geocoding] und [SyncClient] fangen das ab und
 * uebersetzen es in eine deutsche Fehlermeldung; ein HTTP-Fehlerstatus
 * (4xx/5xx) ist dagegen KEIN Grund zu werfen — er kommt regulaer als
 * [HttpResponse] mit entsprechendem [HttpResponse.statusCode] zurueck.
 */
fun interface HttpClient {
    fun execute(request: HttpRequest): HttpResponse
}
