package de.trailscape.core

import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Ortssuche über den öffentlichen Nominatim-Server (OpenStreetMap).
 *
 * 1:1-Portierung von `lib/geocoding.dart`. Hält sich an die
 * Nominatim-Nutzungsrichtlinien (aussagekräftiger User-Agent-Header), damit
 * die App nicht gesperrt wird.
 */

private const val USER_AGENT = "Trailscape/1.0 (github.com/robinrehbein/trailscape)"

/** Ein Suchtreffer aus der Nominatim-Ortssuche. */
data class GeoResult(val displayName: String, val lat: Double, val lon: Double)

/**
 * Prozent-kodiert [value] fuer den Query-Teil einer URL, exakt wie Darts
 * `Uri.encodeQueryComponent` (das intern von `Uri(...).replace(queryParameters:
 * ...)` genutzt wird, siehe `Uri._makeQueryFromParametersDefault` im
 * Dart-SDK): unreservierte Zeichen `[A-Za-z0-9\-._~]` bleiben unveraendert,
 * ein Leerzeichen wird zu `+`, alles andere wird byteweise (UTF-8) als
 * `%XX` mit GROSSEN Hex-Ziffern codiert.
 *
 * Das unterscheidet sich bewusst von Javas `URLEncoder.encode`, dessen
 * unreservierte Zeichenmenge `[A-Za-z0-9\-._*]` ist (also `~` codiert,
 * `*` nicht) — mit Javas Encoder waeren manche Zeichen anders (falsch)
 * codiert als von der frueheren Dart-App.
 */
internal fun dartEncodeQueryComponent(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val sb = StringBuilder(bytes.size)
    for (byte in bytes) {
        val c = byte.toInt() and 0xFF
        when {
            c == ' '.code -> sb.append('+')
            isDartUnreserved(c) -> sb.append(c.toChar())
            else -> {
                sb.append('%')
                sb.append(HEX_DIGITS[(c shr 4) and 0xF])
                sb.append(HEX_DIGITS[c and 0xF])
            }
        }
    }
    return sb.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun isDartUnreserved(c: Int): Boolean =
    (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
        c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code

/**
 * Baut die Nominatim-Such-URL mit denselben Query-Parametern (und derselben
 * Reihenfolge) wie Dart. `accept-language` ist die App-Sprache (`de`/`en`),
 * damit Ortsnamen in der Sprache der Oberflaeche zurueckkommen.
 */
internal fun buildSearchUrl(query: String, language: AppLanguage): String {
    val params = listOf(
        "q" to query,
        "format" to "jsonv2",
        "limit" to "5",
        "accept-language" to language.tag,
    )
    val encodedQuery = params.joinToString("&") { (key, value) ->
        "${dartEncodeQueryComponent(key)}=${dartEncodeQueryComponent(value)}"
    }
    return "https://nominatim.openstreetmap.org/search?$encodedQuery"
}

/**
 * Sucht Orte über Nominatim.
 *
 * [client] wird injiziert (in `:app` produktiv ein echter HTTP-Stack, in
 * Tests ein Fake) — anders als in Dart gibt es hier keinen intern erzeugten
 * Standard-Client, da `:core` keine konkrete HTTP-Implementierung enthält.
 */
fun searchPlaces(query: String, client: HttpClient, texts: CoreTexts): List<GeoResult> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return emptyList()
    }

    val url = buildSearchUrl(trimmed, texts.language)

    val response = try {
        client.execute(
            HttpRequest(
                method = HttpMethod.GET,
                url = url,
                headers = mapOf("User-Agent" to USER_AGENT),
            ),
        )
    } catch (e: Exception) {
        throw Exception(texts.routing.placeSearchUnreachable())
    }

    if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception(texts.routing.placeSearchFailed(response.statusCode))
    }

    val data = try {
        Json.parseToJsonElement(response.body)
    } catch (e: Exception) {
        throw Exception(texts.routing.placeSearchUnexpected())
    }

    if (data !is JsonArray) {
        throw Exception(texts.routing.placeSearchUnexpected())
    }

    val results = mutableListOf<GeoResult>()
    for (entry in data) {
        if (entry !is JsonObject) {
            continue
        }
        val displayNamePrim = entry["display_name"] as? JsonPrimitive
        val latPrim = entry["lat"] as? JsonPrimitive
        val lonPrim = entry["lon"] as? JsonPrimitive
        if (displayNamePrim == null || !displayNamePrim.isString ||
            latPrim == null || !latPrim.isString ||
            lonPrim == null || !lonPrim.isString
        ) {
            continue
        }
        val lat = latPrim.content.toDoubleOrNull()
        val lon = lonPrim.content.toDoubleOrNull()
        if (lat == null || lon == null) {
            continue
        }
        results.add(GeoResult(displayName = displayNamePrim.content, lat = lat, lon = lon))
    }

    return results
}
