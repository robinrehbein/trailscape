package de.trailscape.core

import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Kodierung von Formular-Bodies fuer [HttpRequest] — `multipart/form-data`
 * (RFC 7578) und `application/x-www-form-urlencoded`.
 *
 * Gebraucht vom Strava-Upload ([StravaClient]): Der Token-Tausch schickt ein
 * urlkodiertes Formular, der Upload die Tour als Datei in einem
 * Multipart-Body.
 *
 * ## Warum nur Text
 * [HttpRequest.body] ist bewusst ein String (siehe dort). Die Tour geht als
 * GPX an Strava, und GPX ist Text — ein Multipart-Body aus Text-Teilen passt
 * also ohne Aenderung des Interfaces. FIT waere binaer und braeuchte einen
 * Byte-Body; einen FIT-Writer gibt es in `:core` ohnehin nicht (Fit.kt kann
 * nur lesen). Der Adapter in `:app` kodiert den String als UTF-8 und setzt den
 * Content-Type des Aufrufers unveraendert.
 */

/**
 * Ein Teil eines `multipart/form-data`-Bodies.
 *
 * @param fileName macht den Teil zu einem Datei-Teil (`filename="…"`).
 * @param contentType Content-Type des Teils, typischerweise nur bei Dateien.
 */
data class MultipartPart(
    val name: String,
    val value: String,
    val fileName: String? = null,
    val contentType: String? = null,
)

/**
 * Fertiger Multipart-Body: [contentType] gehoert als `Content-Type`-Header
 * in die Anfrage (er traegt die Boundary), [body] ist der Inhalt.
 */
data class MultipartBody(val contentType: String, val body: String)

private const val CRLF = "\r\n"

/**
 * Baut einen `multipart/form-data`-Body aus [parts].
 *
 * Zeilenenden sind CRLF, wie RFC 7578 es verlangt. Anfuehrungszeichen und
 * Zeilenumbrueche in Namen und Dateinamen werden prozentkodiert (so macht es
 * auch jeder Browser, siehe HTML-Spezifikation „multipart/form-data encoding
 * algorithm") — sonst koennte ein Tourname wie `Runde "Nord"` den Kopf eines
 * Teils zerbrechen.
 *
 * @throws IllegalArgumentException wenn die [boundary] in einem Wert
 *   vorkommt; der Body waere dann nicht mehr eindeutig zerlegbar. Mit der
 *   zufaelligen Vorgabe ([randomMultipartBoundary]) passiert das praktisch nie.
 */
fun buildMultipartFormData(
    parts: List<MultipartPart>,
    boundary: String = randomMultipartBoundary(),
): MultipartBody {
    require(boundary.isNotEmpty() && boundary.length <= 70) { "Ungueltige Boundary" }
    for (part in parts) {
        require(
            !part.value.contains(boundary) && !part.name.contains(boundary) &&
                part.fileName?.contains(boundary) != true,
        ) {
            "Die Boundary kommt im Inhalt des Teils '${part.name}' vor"
        }
    }
    val body = buildString {
        for (part in parts) {
            append("--").append(boundary).append(CRLF)
            append("Content-Disposition: form-data; name=\"").append(escapeDispositionValue(part.name)).append('"')
            if (part.fileName != null) {
                append("; filename=\"").append(escapeDispositionValue(part.fileName)).append('"')
            }
            append(CRLF)
            if (part.contentType != null) {
                append("Content-Type: ").append(part.contentType).append(CRLF)
            }
            append(CRLF)
            append(part.value)
            append(CRLF)
        }
        append("--").append(boundary).append("--").append(CRLF)
    }
    return MultipartBody(contentType = "multipart/form-data; boundary=$boundary", body = body)
}

private fun escapeDispositionValue(value: String): String = buildString {
    for (c in value) {
        when (c) {
            '"' -> append("%22")
            '\r' -> append("%0D")
            '\n' -> append("%0A")
            else -> append(c)
        }
    }
}

private val boundaryRandom = SecureRandom()

/** Zufaellige Boundary aus 24 Hex-Zeichen hinter einem festen Praefix. */
fun randomMultipartBoundary(): String {
    val bytes = ByteArray(12)
    boundaryRandom.nextBytes(bytes)
    return "TrailscapeBoundary" + bytes.joinToString("") { "%02x".format(it) }
}

/**
 * Kodiert [fields] als `application/x-www-form-urlencoded` (UTF-8,
 * Leerzeichen als `+`) — in der gegebenen Reihenfolge.
 */
fun formUrlEncode(fields: List<Pair<String, String>>): String =
    fields.joinToString("&") { (key, value) ->
        // Die Ueberladung mit `Charset` gibt es auf Android erst ab API 33;
        // `:core` laeuft auch dort (minSdk 26), deshalb der Name als String.
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }
