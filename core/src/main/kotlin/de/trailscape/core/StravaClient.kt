package de.trailscape.core

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Upload fertiger Touren zu Strava — Token-Tausch, Erneuerung, Upload und
 * Statusabfrage, komplett gegen das schmale [HttpClient]-Interface und damit
 * mit einem Fake testbar.
 *
 * ## Local-first bleibt
 * Diese Klasse wird nur benutzt, wenn jemand sein Strava-Konto ausdruecklich
 * verbunden hat. Ohne Verbindung gibt es keinen Zugang ([StravaTokenStore]
 * leer), und jede Methode, die Strava anspricht, scheitert mit
 * [StravaError.UNAUTHORIZED], bevor eine Anfrage abgeht.
 *
 * ## Format: GPX
 * Strava nimmt FIT, TCX und GPX. Hochgeladen wird der vorhandene GPX-Export
 * ([rideToGpx]) — mit Zeit, Hoehe und, wo gemessen, Puls, Trittfrequenz und
 * Leistung je Punkt. Einen FIT-Writer gibt es
 * in `:core` nicht, und ein binaerer Body passte nicht zum String-Body von
 * [HttpRequest]. Damit Strava die Fahrt als Radfahrt einordnet und nicht nach
 * der Standard-Sportart des Kontos, traegt der Track `<type>cycling</type>`
 * ([withGpxTrackType]).
 *
 * ## Fehler
 * Alle Methoden bilden Fehler auf [StravaError] ab und liefern keine Saetze;
 * die Texte kommen aus den Ressourcen der App.
 * Blockiert (Netz, [sleep]) — nur von einem Hintergrund-Thread aufrufen.
 *
 * @param nowS Uhr in Sekunden seit Epoch (injizierbar fuer Tests).
 * @param sleep Pause zwischen zwei Statusabfragen in ms (injizierbar fuer Tests).
 */
class StravaClient(
    private val http: HttpClient,
    private val credentials: StravaAppCredentials,
    private val tokens: StravaTokenStore,
    private val nowS: () -> Long = { System.currentTimeMillis() / 1000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {

    /**
     * Tauscht den Code aus dem Rueckruf gegen einen Zugang und legt ihn ab.
     *
     * @throws StravaException mit [StravaError.UNAUTHORIZED], wenn Strava den
     *   Code ablehnt (abgelaufen, schon benutzt), sonst NETWORK/SERVER/RATE_LIMITED.
     */
    fun exchangeCode(code: String): StravaTokens {
        val response = postForm(
            STRAVA_TOKEN_URL,
            listOf(
                "client_id" to credentials.clientId,
                "client_secret" to credentials.clientSecret,
                "code" to code,
                "grant_type" to "authorization_code",
            ),
        )
        if (response.statusCode !in 200..299) throw StravaException(errorForStatus(response.statusCode), response.snippet())
        val json = parseObject(response.body)
        val parsed = tokensFromResponse(json, previousFirstName = null)
        tokens.write(parsed)
        return parsed
    }

    /**
     * Ein gueltiger Access-Token; erneuert ihn, wenn er in den naechsten fuenf
     * Minuten ablaeuft.
     *
     * `@Synchronized`: Worker und Oberflaeche koennen gleichzeitig fragen.
     * Strava stellt beim Erneuern einen neuen Refresh-Token aus — zwei
     * parallele Erneuerungen wuerden sich gegenseitig den Token ungueltig
     * machen.
     *
     * @throws StravaException [StravaError.UNAUTHORIZED], wenn kein Zugang
     *   hinterlegt ist oder Strava ihn nicht mehr annimmt (der Speicher ist
     *   dann geleert).
     */
    @Synchronized
    fun validAccessToken(): String {
        val current = tokens.read() ?: throw StravaException(StravaError.UNAUTHORIZED, "nicht verbunden")
        if (!current.expiresSoon(nowS())) return current.accessToken
        return refresh(current).accessToken
    }

    /**
     * Erneuert den Zugang unabhaengig vom Ablaufdatum — fuer den Fall, dass
     * Strava einen noch nicht abgelaufenen Token mit 401 abweist. Wurde er
     * seit [rejectedToken] schon von einem anderen Aufrufer erneuert, wird
     * der neue genommen statt ein zweites Mal zu erneuern.
     */
    @Synchronized
    internal fun refreshAfterRejection(rejectedToken: String): String {
        val current = tokens.read() ?: throw StravaException(StravaError.UNAUTHORIZED, "nicht verbunden")
        if (current.accessToken != rejectedToken) return current.accessToken
        return refresh(current).accessToken
    }

    private fun refresh(current: StravaTokens): StravaTokens {
        val response = postForm(
            STRAVA_TOKEN_URL,
            listOf(
                "client_id" to credentials.clientId,
                "client_secret" to credentials.clientSecret,
                "grant_type" to "refresh_token",
                "refresh_token" to current.refreshToken,
            ),
        )
        when (response.statusCode) {
            in 200..299 -> Unit
            400, 401 -> {
                // Zugang in den Strava-Einstellungen entzogen oder
                // Refresh-Token verbraucht: Er kommt nicht wieder. Speicher
                // leeren, damit die App „nicht verbunden" zeigt.
                tokens.clear()
                throw StravaException(StravaError.UNAUTHORIZED, response.snippet())
            }
            else -> throw StravaException(errorForStatus(response.statusCode), response.snippet())
        }
        val renewed = tokensFromResponse(parseObject(response.body), previousFirstName = current.athleteFirstName)
        // Immer speichern: Strava darf mit jeder Erneuerung einen neuen
        // Refresh-Token ausgeben, der alte gilt dann nicht mehr.
        tokens.write(renewed)
        return renewed
    }

    /**
     * Trennt die Verbindung: Strava den Zugang entziehen (so gut es geht) und
     * lokal **immer** vergessen — auch ohne Netz. Wer „Trennen" tippt, muss
     * sich darauf verlassen koennen, dass danach nichts mehr hochgeladen wird.
     *
     * `@Synchronized` (derselbe Monitor wie [validAccessToken]): Laeuft
     * gerade eine Erneuerung, wartet das Trennen, bis sie fertig ist, und
     * loescht danach. Sonst schriebe die Erneuerung den frischen Zugang nach
     * dem Loeschen wieder in den Speicher — und nach dem naechsten Start
     * waere die App wieder verbunden, obwohl jemand „Trennen" getippt hat.
     */
    @Synchronized
    fun disconnect() {
        val current = tokens.read()
        try {
            if (current != null) {
                runCatching {
                    http.execute(
                        HttpRequest(
                            method = HttpMethod.POST,
                            url = STRAVA_DEAUTHORIZE_URL,
                            headers = mapOf(
                                "Authorization" to "Bearer ${current.accessToken}",
                                "Content-Type" to FORM_CONTENT_TYPE,
                            ),
                            body = "",
                        ),
                    )
                }
            }
        } finally {
            tokens.clear()
        }
    }

    /**
     * Laedt [ride] hoch und wartet auf das Ergebnis der Verarbeitung.
     *
     * Ablauf: POST `/uploads` (multipart, GPX), danach GET `/uploads/{id}` mit
     * den Pausen aus [POLL_DELAYS_S], bis Strava eine Aktivitaets-ID oder einen
     * Fehler meldet. Ist die Verarbeitung danach noch nicht fertig, kommt
     * [StravaUploadOutcome.Processing] zurueck; [resumePolling] fragt spaeter
     * weiter.
     *
     * Ein 401 auf den Upload erneuert den Zugang genau einmal und wiederholt
     * die Anfrage genau einmal.
     *
     * @throws StravaException [StravaError.NOT_UPLOADABLE] fuer eine geplante
     *   Tour oder eine ohne Zeitstempel ([isStravaUploadable]) — bevor eine
     *   Anfrage abgeht. Alle anderen Fehler kommen als
     *   [StravaUploadOutcome.Failed] zurueck.
     */
    fun upload(ride: Ride): StravaUploadOutcome {
        if (!isStravaUploadable(ride)) {
            throw StravaException(StravaError.NOT_UPLOADABLE, "geplante Tour oder ohne Zeitstempel")
        }
        return catchingFailure {
            val gpx = withGpxTrackType(rideToGpx(ride), "cycling")
            val multipart = buildMultipartFormData(
                listOf(
                    MultipartPart("data_type", "gpx"),
                    MultipartPart("name", ride.name),
                    // external_id macht den Upload bei Strava wiedererkennbar
                    // (die Endung verlangt Strava so).
                    MultipartPart("external_id", "trailscape-${ride.id}.gpx"),
                    MultipartPart(
                        name = "file",
                        value = gpx,
                        fileName = "${safeFileName(ride.name)}.gpx",
                        contentType = "application/gpx+xml",
                    ),
                ),
            )
            val response = authorized { token ->
                HttpRequest(
                    method = HttpMethod.POST,
                    url = "$STRAVA_API_BASE/uploads",
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to multipart.contentType,
                    ),
                    body = multipart.body,
                )
            }
            if (response.statusCode !in 200..299) {
                return@catchingFailure StravaUploadOutcome.Failed(uploadErrorForStatus(response.statusCode), response.snippet())
            }
            val status = UploadStatus.from(parseObject(response.body))
            status.outcome() ?: poll(status.id)
        }
    }

    /**
     * Fragt einen schon angenommenen Upload weiter ab, ohne erneut
     * hochzuladen — fuer den Worker, der nach [StravaUploadOutcome.Processing]
     * spaeter wieder anlaeuft.
     */
    fun resumePolling(uploadId: Long): StravaUploadOutcome = catchingFailure { poll(uploadId) }

    /**
     * Fragt den Status von [uploadId] ab, bis Strava fertig ist.
     *
     * Der Upload ist hier schon angenommen. Jeder voruebergehende Fehler
     * (Netz, Drosselung, Serverfehler — auch beim Erneuern des Zugangs oder
     * bei einer unlesbaren Antwort) heisst deshalb „spaeter weiterfragen"
     * ([StravaUploadOutcome.Processing] mit der ID), nie „fehlgeschlagen":
     * Sonst ginge die Upload-ID verloren, der naechste Versuch luede die Tour
     * erneut hoch, und Strava meldete die eigene Fahrt als Duplikat. Nur ein
     * entzogener Zugang ([StravaError.UNAUTHORIZED]) bricht ab.
     */
    private fun poll(uploadId: Long): StravaUploadOutcome {
        for (delayS in POLL_DELAYS_S) {
            sleep(delayS * 1000)
            try {
                val response = authorized { token ->
                    HttpRequest(
                        method = HttpMethod.GET,
                        url = "$STRAVA_API_BASE/uploads/$uploadId",
                        headers = mapOf("Authorization" to "Bearer $token"),
                    )
                }
                when {
                    response.statusCode in 200..299 -> {
                        val status = UploadStatus.from(parseObject(response.body))
                        status.outcome()?.let { return it }
                    }
                    response.statusCode == 429 || response.statusCode >= 500 ->
                        return StravaUploadOutcome.Processing(uploadId)
                    else ->
                        return StravaUploadOutcome.Failed(uploadErrorForStatus(response.statusCode), response.snippet())
                }
            } catch (e: StravaException) {
                if (e.error == StravaError.UNAUTHORIZED) throw e
                return StravaUploadOutcome.Processing(uploadId)
            }
        }
        return StravaUploadOutcome.Processing(uploadId)
    }

    /**
     * Fuehrt die Anfrage mit einem gueltigen Token aus; bei 401 einmal
     * erneuern und einmal wiederholen. Ein zweites 401 heisst: Der Zugang
     * reicht nicht (Bereich entzogen) — er wird vergessen, damit die App zum
     * neuen Verbinden auffordert.
     */
    private fun authorized(build: (String) -> HttpRequest): HttpResponse {
        val token = validAccessToken()
        val first = execute(build(token))
        if (first.statusCode != 401) return first
        val renewed = refreshAfterRejection(token)
        val second = execute(build(renewed))
        if (second.statusCode == 401) {
            tokens.clear()
            throw StravaException(StravaError.UNAUTHORIZED, second.snippet())
        }
        return second
    }

    private fun postForm(url: String, fields: List<Pair<String, String>>): HttpResponse = execute(
        HttpRequest(
            method = HttpMethod.POST,
            url = url,
            headers = mapOf("Content-Type" to FORM_CONTENT_TYPE),
            body = formUrlEncode(fields),
        ),
    )

    private fun execute(request: HttpRequest): HttpResponse = try {
        http.execute(request)
    } catch (e: IOException) {
        throw StravaException(StravaError.NETWORK, e.message)
    }

    private inline fun catchingFailure(block: () -> StravaUploadOutcome): StravaUploadOutcome = try {
        block()
    } catch (e: StravaException) {
        StravaUploadOutcome.Failed(e.error, e.message)
    }

    private fun tokensFromResponse(json: JsonObject, previousFirstName: String?): StravaTokens = try {
        StravaTokens(
            accessToken = json.requiredString("access_token"),
            refreshToken = json.requiredString("refresh_token"),
            expiresAtS = json.requiredLong("expires_at"),
            athleteFirstName = (json.fieldOrNull("athlete") as? JsonObject)?.optionalString("firstname")
                ?.takeIf { it.isNotBlank() }
                ?: previousFirstName,
        )
    } catch (e: IllegalArgumentException) {
        throw StravaException(StravaError.SERVER, "unerwartete Token-Antwort")
    }

    /** Die Antwort von `/uploads` bzw. `/uploads/{id}`. */
    private data class UploadStatus(val id: Long, val error: String?, val activityId: Long?) {
        /** Endgueltiges Ergebnis oder `null`, solange Strava noch verarbeitet. */
        fun outcome(): StravaUploadOutcome? = when {
            error != null -> if (error.contains("duplicate of", ignoreCase = true)) {
                StravaUploadOutcome.Duplicate(duplicateActivityId(error))
            } else {
                StravaUploadOutcome.Failed(StravaError.FILE, error)
            }
            activityId != null -> StravaUploadOutcome.Uploaded(activityId)
            else -> null
        }

        companion object {
            fun from(json: JsonObject): UploadStatus = try {
                UploadStatus(
                    id = json.requiredLong("id"),
                    error = json.optionalString("error")?.takeIf { it.isNotBlank() },
                    activityId = json.optionalLong("activity_id"),
                )
            } catch (e: IllegalArgumentException) {
                throw StravaException(StravaError.SERVER, "unerwartete Upload-Antwort")
            }
        }
    }

    companion object {
        /**
         * Pausen zwischen den Statusabfragen in Sekunden — zusammen knapp
         * eine Minute. Strava braucht fuer ein GPX meist wenige Sekunden; wer
         * laenger braucht, wird spaeter vom Worker weiter abgefragt, statt das
         * Ratenlimit (200 Anfragen je 15 min fuer die ganze App) aufzubrauchen.
         */
        val POLL_DELAYS_S: List<Long> = listOf(2, 2, 3, 5, 8, 10, 10, 10)

        private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
    }
}

/**
 * Fehlerklassen des Strava-Uploads. Die App uebersetzt sie in Saetze
 * (`strings_strava.xml`); `:core` liefert bewusst keinen Text.
 */
enum class StravaError {
    /** Keine Verbindung oder Zeitueberschreitung — spaeter erneut versuchen. */
    NETWORK,

    /** Kein oder kein gueltiger Zugang mehr — neu verbinden. */
    UNAUTHORIZED,

    /** Strava drosselt (HTTP 429) — spaeter erneut versuchen. */
    RATE_LIMITED,

    /** Strava hat einen Fehler (5xx) oder unerwartet geantwortet. */
    SERVER,

    /** Strava konnte die Datei nicht verarbeiten (Upload-Fehler, 4xx). */
    FILE,

    /** Die Tour eignet sich nicht (geplant, ohne Zeitstempel). */
    NOT_UPLOADABLE,
}

/** Fehler mit Klasse; [message] ist technisches Detail, kein Nutzertext. */
class StravaException(val error: StravaError, message: String?) : Exception(message)

/** Ergebnis eines Uploads bzw. einer Statusabfrage. */
sealed interface StravaUploadOutcome {
    /** Fertig; die Aktivitaet ist unter [stravaActivityUrl] zu sehen. */
    data class Uploaded(val activityId: Long) : StravaUploadOutcome

    /** Strava kennt die Fahrt schon — [activityId], falls Strava sie nennt. */
    data class Duplicate(val activityId: Long?) : StravaUploadOutcome

    /** Angenommen, aber noch in Verarbeitung; spaeter mit [StravaClient.resumePolling] weiterfragen. */
    data class Processing(val uploadId: Long) : StravaUploadOutcome

    data class Failed(val error: StravaError, val detail: String?) : StravaUploadOutcome
}

/** Die Seite einer Aktivitaet; oeffnet die Strava-App, wenn sie installiert ist. */
fun stravaActivityUrl(id: Long): String = "https://www.strava.com/activities/$id"

/**
 * Ob sich [ride] fuer Strava eignet: gefahren (nicht geplant), mindestens
 * zwei Punkte und mindestens ein Zeitstempel — ohne Zeit lehnt Strava ein GPX
 * ab, und eine Planung ist keine Aktivitaet.
 */
fun isStravaUploadable(ride: Ride): Boolean =
    !ride.planned && ride.points.size >= 2 && ride.points.any { it.time != null }

private val DUPLICATE_REGEX = Regex("duplicate of\\D*(\\d+)", RegexOption.IGNORE_CASE)

/**
 * Die Aktivitaets-ID aus Stravas Duplikatmeldung, z. B.
 * `x.gpx duplicate of <a href='/activities/123'>123</a>` → 123. `null`, wenn
 * die Meldung keine Zahl traegt.
 */
internal fun duplicateActivityId(error: String): Long? =
    DUPLICATE_REGEX.find(error)?.groupValues?.get(1)?.toLongOrNull()

/**
 * Fuegt einem GPX die Sportart des ersten Tracks hinzu (`<type>` direkt nach
 * dessen `<name>`, wie es die GPX-1.1-Reihenfolge verlangt).
 *
 * Bewusst hier und nicht als Parameter von [buildGpx]: Das Teilen als GPX
 * braucht den Typ nicht, und [buildGpx] bleibt so unveraendert.
 */
internal fun withGpxTrackType(gpx: String, type: String): String {
    val trk = gpx.indexOf("<trk>")
    if (trk < 0) return gpx
    val typeTag = "<type>$type</type>"
    val trkseg = gpx.indexOf("<trkseg>", trk).let { if (it < 0) gpx.length else it }
    val nameEnd = gpx.indexOf("</name>", trk)
    val insertAt = if (nameEnd in 0 until trkseg) nameEnd + "</name>".length else trk + "<trk>".length
    return gpx.substring(0, insertAt) + "\n    " + typeTag + gpx.substring(insertAt)
}

private fun parseObject(body: String): JsonObject = try {
    Json.parseToJsonElement(body) as? JsonObject
        ?: throw StravaException(StravaError.SERVER, "Antwort ist kein JSON-Objekt")
} catch (e: IllegalArgumentException) {
    throw StravaException(StravaError.SERVER, "Antwort ist kein JSON")
}

private fun errorForStatus(status: Int): StravaError = when (status) {
    400, 401, 403 -> StravaError.UNAUTHORIZED
    429 -> StravaError.RATE_LIMITED
    else -> StravaError.SERVER
}

private fun uploadErrorForStatus(status: Int): StravaError = when (status) {
    401, 403 -> StravaError.UNAUTHORIZED
    429 -> StravaError.RATE_LIMITED
    in 400..499 -> StravaError.FILE
    else -> StravaError.SERVER
}

/** Die ersten Zeichen der Antwort als Detail fuer Diagnose — nie als Nutzertext. */
private fun HttpResponse.snippet(): String = "HTTP $statusCode: ${body.take(200)}"
