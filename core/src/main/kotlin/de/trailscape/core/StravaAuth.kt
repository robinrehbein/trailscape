package de.trailscape.core

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anmeldung bei Strava (OAuth 2.0, Authorization Code) — der plattformfreie
 * Teil: Autorisierungs-URL, Auswertung des Rueckrufs, Token-Modell.
 *
 * ## Ablauf
 *  1. Die App oeffnet [stravaAuthorizeUrl] im Browser (Custom Tab). Dort meldet
 *     man sich direkt bei Strava an — Trailscape sieht weder Passwort noch
 *     Konto.
 *  2. Strava leitet auf [STRAVA_REDIRECT_URI] um, mit `code`, `scope` und dem
 *     mitgegebenen `state` (bei Ablehnung mit `error=access_denied`).
 *     [parseStravaCallback] wertet das aus.
 *  3. [StravaClient.exchangeCode] tauscht den Code gegen einen Zugang
 *     ([StravaTokens]), den die App ueber [StravaTokenStore] verschluesselt
 *     ablegt.
 *
 * ## Warum `state`
 * Das Custom-Scheme `trailscape://` kann jede Seite und jede App aufrufen. Ohne
 * ein vorher gemerktes Zufalls-Nonce koennte ein fremder Aufruf der App einen
 * Code unterschieben und sie so mit einem fremden Konto verbinden. Strava
 * unterstuetzt kein PKCE; das Nonce ist der Schutz, den es gibt.
 */

/** Rueckruf-Adresse der Strava-Anmeldung; im Manifest von `StravaAuthActivity` registriert. */
const val STRAVA_REDIRECT_URI = "trailscape://strava-callback"

/**
 * Der einzige angefragte Bereich: Aktivitaeten hochladen. Kein Lesen fremder
 * oder eigener Aktivitaeten, kein Profilzugriff ausser dem, was Strava ohnehin
 * mitschickt (`read`, Vorname).
 */
const val STRAVA_SCOPE = "activity:write"

internal const val STRAVA_AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
internal const val STRAVA_TOKEN_URL = "https://www.strava.com/oauth/token"
internal const val STRAVA_DEAUTHORIZE_URL = "https://www.strava.com/oauth/deauthorize"
internal const val STRAVA_API_BASE = "https://www.strava.com/api/v3"

/** Zugangsdaten der Strava-API-App (aus dem Build, nie aus dem Repo). */
data class StravaAppCredentials(val clientId: String, val clientSecret: String) {
    /** Das Secret gehoert in kein Log und keinen Absturzbericht. */
    override fun toString(): String = "StravaAppCredentials(clientId=$clientId, clientSecret=***)"
}

/**
 * Der Zugang zu einem Strava-Konto.
 *
 * @param expiresAtS Ablauf des [accessToken] in Sekunden seit Epoch (so
 *   liefert Strava ihn).
 * @param athleteFirstName Vorname aus dem Strava-Profil, nur fuer „Verbunden
 *   als …". Beim Erneuern schickt Strava ihn nicht mit; er bleibt dann stehen.
 */
data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtS: Long,
    val athleteFirstName: String? = null,
) {
    /** Laeuft der Zugang in den naechsten fuenf Minuten ab (oder ist er schon abgelaufen)? */
    fun expiresSoon(nowS: Long): Boolean = expiresAtS - EXPIRY_MARGIN_S <= nowS

    fun toJson(): JsonObject = buildJsonObject {
        put("accessToken", accessToken)
        put("refreshToken", refreshToken)
        put("expiresAtS", expiresAtS)
        athleteFirstName?.let { put("athleteFirstName", it) }
    }

    /** Tokens gehoeren in kein Log und keinen Absturzbericht. */
    override fun toString(): String = "StravaTokens(expiresAtS=$expiresAtS, athleteFirstName=$athleteFirstName)"

    companion object {
        /** Sicherheitsabstand vor dem Ablauf: lieber fuenf Minuten zu frueh erneuern als mitten im Upload scheitern. */
        const val EXPIRY_MARGIN_S = 300L

        fun fromJson(json: JsonObject): StravaTokens = StravaTokens(
            accessToken = json.requiredString("accessToken"),
            refreshToken = json.requiredString("refreshToken"),
            expiresAtS = json.requiredLong("expiresAtS"),
            athleteFirstName = json.optionalString("athleteFirstName"),
        )

        /** Wie [fromJson], aber aus Text; kaputtes JSON ergibt `null`. */
        fun fromJsonStringOrNull(raw: String): StravaTokens? = runCatching {
            fromJson(Json.parseToJsonElement(raw).asRequiredObject())
        }.getOrNull()
    }
}

/**
 * Ablage des Strava-Zugangs. In `:app` verschluesselt mit einem Schluessel im
 * Android Keystore; in Tests ein einfacher Speicher.
 */
interface StravaTokenStore {
    fun read(): StravaTokens?
    fun write(tokens: StravaTokens)
    fun clear()
}

/**
 * Die Adresse, unter der man Trailscape bei Strava den Zugang erteilt.
 *
 * `approval_prompt=auto`: Wer schon einmal zugestimmt hat, wird nicht erneut
 * gefragt. Die mobile Variante des Endpunkts oeffnet — wenn installiert — die
 * Strava-App statt der Webanmeldung.
 */
fun stravaAuthorizeUrl(clientId: String, redirectUri: String, state: String): String {
    fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
    return STRAVA_AUTHORIZE_URL +
        "?client_id=" + enc(clientId) +
        "&redirect_uri=" + enc(redirectUri) +
        "&response_type=code" +
        "&approval_prompt=auto" +
        "&scope=" + enc(STRAVA_SCOPE) +
        "&state=" + enc(state)
}

/** Ergebnis der Auswertung eines Strava-Rueckrufs. */
sealed interface StravaCallbackResult {
    /** Alles passt; [code] wird gegen einen Zugang getauscht. */
    data class Code(val code: String) : StravaCallbackResult

    /** Man hat bei Strava „Abbrechen" gewaehlt. */
    data object Denied : StravaCallbackResult

    /** Zugestimmt, aber das Haekchen „Aktivitaeten hochladen" abgewaehlt. */
    data object MissingScope : StravaCallbackResult

    /** Fremder, veralteter oder unvollstaendiger Rueckruf (state passt nicht, Code fehlt). */
    data object Invalid : StravaCallbackResult
}

/**
 * Wertet die Query-Parameter des Rueckrufs aus.
 *
 * Reihenfolge der Pruefungen:
 *  1. `error` gesetzt → [StravaCallbackResult.Denied]. Eine Ablehnung ist
 *     harmlos, auch wenn sie untergeschoben waere; sie verbindet nichts.
 *  2. Kein gemerktes oder ein abweichendes `state` → [StravaCallbackResult.Invalid].
 *  3. Kein `code` → [StravaCallbackResult.Invalid].
 *  4. `scope` ohne [STRAVA_SCOPE] → [StravaCallbackResult.MissingScope]. Strava
 *     laesst einzelne Bereiche abwaehlen und meldet die erteilten im Rueckruf;
 *     ohne `activity:write` waere jeder Upload ein 401.
 *
 * @param expectedState das beim Start gemerkte Nonce, `null`, wenn keines
 *   aussteht (dann ist jeder Rueckruf ungueltig).
 */
fun parseStravaCallback(params: Map<String, String?>, expectedState: String?): StravaCallbackResult {
    if (!params["error"].isNullOrBlank()) return StravaCallbackResult.Denied
    val state = params["state"]
    if (expectedState.isNullOrEmpty() || state != expectedState) return StravaCallbackResult.Invalid
    val code = params["code"]
    if (code.isNullOrBlank()) return StravaCallbackResult.Invalid
    val scopes = params["scope"].orEmpty().split(',', ' ').map { it.trim() }
    if (STRAVA_SCOPE !in scopes) return StravaCallbackResult.MissingScope
    return StravaCallbackResult.Code(code)
}
