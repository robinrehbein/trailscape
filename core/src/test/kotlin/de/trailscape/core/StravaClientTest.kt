package de.trailscape.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [StravaClient] gegen einen geskripteten Fake-[HttpClient]: Jede
 * erwartete Anfrage bekommt der Reihe nach eine vorbereitete Antwort (oder
 * eine [IOException]); jede weitere Anfrage laesst den Test scheitern.
 */
class StravaClientTest {

    private class ScriptedHttp(vararg steps: (HttpRequest) -> HttpResponse) : HttpClient {
        private val remaining = ArrayDeque(steps.toList())
        val requests = mutableListOf<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val step = remaining.removeFirstOrNull() ?: throw AssertionError("unerwartete Anfrage: ${request.method} ${request.url}")
            return step(request)
        }

        fun assertDone() = assertTrue(remaining.isEmpty(), "nicht alle erwarteten Anfragen kamen: ${remaining.size} offen")
    }

    private class MemoryTokens(var tokens: StravaTokens? = null) : StravaTokenStore {
        override fun read(): StravaTokens? = tokens
        override fun write(tokens: StravaTokens) {
            this.tokens = tokens
        }
        override fun clear() {
            tokens = null
        }
    }

    private val credentials = StravaAppCredentials("4711", "s3cret")
    private val now = 1_000_000L
    private val sleeps = mutableListOf<Long>()

    private fun client(http: HttpClient, tokens: StravaTokenStore) =
        StravaClient(http, credentials, tokens, nowS = { now }, sleep = { sleeps += it })

    private fun freshTokens() = StravaTokens("acc", "ref", expiresAtS = now + 3600, athleteFirstName = "Robin")

    private fun respond(status: Int, body: String = ""): (HttpRequest) -> HttpResponse = { HttpResponse(status, body) }

    private fun ride(planned: Boolean = false, withTime: Boolean = true) = Ride(
        id = "ride-1",
        name = "Feierabend \"Runde\"",
        createdAt = 1_700_000_000_000L,
        stats = RideStats(distanceKm = 1.0, ascentM = 0.0, descentM = 0.0),
        points = listOf(
            TrackPoint(50.0, 8.0, ele = 100.0, time = if (withTime) 1_700_000_000_000L else null, hr = 120),
            TrackPoint(50.001, 8.001, ele = 101.0, time = if (withTime) 1_700_000_010_000L else null),
        ),
        planned = planned,
    )

    private fun formFields(body: String?): Map<String, String> =
        body.orEmpty().split('&').associate {
            val (k, v) = it.split('=', limit = 2)
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }

    // ------------------------------------------------------------ Token-Tausch

    @Test
    fun `exchangeCode schickt das Formular und speichert Zugang samt Vorname`() {
        val http = ScriptedHttp(
            respond(
                200,
                """{"token_type":"Bearer","expires_at":1003600,"access_token":"A1","refresh_token":"R1","athlete":{"id":1,"firstname":"Robin"}}""",
            ),
        )
        val store = MemoryTokens()

        val tokens = client(http, store).exchangeCode("CODE")

        val request = http.requests.single()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("https://www.strava.com/oauth/token", request.url)
        assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
        assertEquals(
            mapOf("client_id" to "4711", "client_secret" to "s3cret", "code" to "CODE", "grant_type" to "authorization_code"),
            formFields(request.body),
        )
        assertEquals(StravaTokens("A1", "R1", 1_003_600L, "Robin"), tokens)
        assertEquals(tokens, store.tokens)
    }

    @Test
    fun `exchangeCode ordnet Fehler zu`() {
        val abgelehnt = assertFailsWith<StravaException> {
            client(ScriptedHttp(respond(400, """{"message":"Bad Request"}""")), MemoryTokens()).exchangeCode("x")
        }
        assertEquals(StravaError.UNAUTHORIZED, abgelehnt.error)

        val netz = assertFailsWith<StravaException> {
            client(ScriptedHttp({ throw IOException("offline") }), MemoryTokens()).exchangeCode("x")
        }
        assertEquals(StravaError.NETWORK, netz.error)

        val kaputt = assertFailsWith<StravaException> {
            client(ScriptedHttp(respond(200, "<html>")), MemoryTokens()).exchangeCode("x")
        }
        assertEquals(StravaError.SERVER, kaputt.error)
    }

    // ------------------------------------------------------------ Token

    @Test
    fun `frischer Token braucht keine Anfrage`() {
        val http = ScriptedHttp()
        assertEquals("acc", client(http, MemoryTokens(freshTokens())).validAccessToken())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `bald ablaufender Token wird erneuert und der neue Refresh-Token gespeichert`() {
        val http = ScriptedHttp(respond(200, """{"access_token":"A2","refresh_token":"R2","expires_at":1021600}"""))
        val store = MemoryTokens(freshTokens().copy(expiresAtS = now + 200))

        assertEquals("A2", client(http, store).validAccessToken())

        val fields = formFields(http.requests.single().body)
        assertEquals("refresh_token", fields["grant_type"])
        assertEquals("ref", fields["refresh_token"])
        assertEquals("s3cret", fields["client_secret"])
        // Vorname bleibt stehen, obwohl die Erneuerung ihn nicht mitschickt.
        assertEquals(StravaTokens("A2", "R2", 1_021_600L, "Robin"), store.tokens)
    }

    @Test
    fun `abgelehnte Erneuerung leert den Speicher`() {
        val store = MemoryTokens(freshTokens().copy(expiresAtS = now - 10))
        val e = assertFailsWith<StravaException> {
            client(ScriptedHttp(respond(401, "{}")), store).validAccessToken()
        }
        assertEquals(StravaError.UNAUTHORIZED, e.error)
        assertNull(store.tokens)
    }

    @Test
    fun `ohne Zugang geht keine Anfrage ab`() {
        val http = ScriptedHttp()
        val e = assertFailsWith<StravaException> { client(http, MemoryTokens()).validAccessToken() }
        assertEquals(StravaError.UNAUTHORIZED, e.error)
        val outcome = client(http, MemoryTokens()).upload(ride())
        assertEquals(StravaError.UNAUTHORIZED, (outcome as StravaUploadOutcome.Failed).error)
        assertTrue(http.requests.isEmpty())
    }

    // ------------------------------------------------------------ Upload

    @Test
    fun `Upload schickt GPX als Multipart und fragt bis zur Aktivitaet ab`() {
        val http = ScriptedHttp(
            respond(201, """{"id":99,"id_str":"99","external_id":"x","error":null,"status":"Your activity is still being processed.","activity_id":null}"""),
            respond(200, """{"id":99,"error":null,"status":"Your activity is still being processed.","activity_id":null}"""),
            respond(200, """{"id":99,"error":null,"status":"Your activity is ready.","activity_id":12345}"""),
        )

        val outcome = client(http, MemoryTokens(freshTokens())).upload(ride())

        assertEquals(StravaUploadOutcome.Uploaded(12345L), outcome)
        http.assertDone()
        val post = http.requests[0]
        assertEquals(HttpMethod.POST, post.method)
        assertEquals("https://www.strava.com/api/v3/uploads", post.url)
        assertEquals("Bearer acc", post.headers["Authorization"])
        val contentType = post.headers["Content-Type"].orEmpty()
        assertTrue(contentType.startsWith("multipart/form-data; boundary="), contentType)
        val body = post.body.orEmpty()
        assertTrue(body.contains("name=\"data_type\"\r\n\r\ngpx\r\n"), body)
        assertTrue(body.contains("name=\"name\"\r\n\r\nFeierabend \"Runde\"\r\n"), body)
        assertTrue(body.contains("name=\"external_id\"\r\n\r\ntrailscape-ride-1.gpx\r\n"), body)
        assertTrue(body.contains("name=\"file\"; filename=\"Feierabend_Runde.gpx\"\r\nContent-Type: application/gpx+xml"), body)
        assertTrue(body.contains("<type>cycling</type>"), body)
        assertTrue(body.contains("<time>2023-11-14T22:13:20.000Z</time>"), body)
        assertTrue(body.contains("<gpxtpx:hr>120</gpxtpx:hr>"), body)

        assertEquals("https://www.strava.com/api/v3/uploads/99", http.requests[1].url)
        assertEquals(HttpMethod.GET, http.requests[1].method)
        assertEquals("Bearer acc", http.requests[2].headers["Authorization"])
        assertEquals(listOf(2000L, 2000L), sleeps)
    }

    @Test
    fun `sofort fertige Aktivitaet braucht keine Abfrage`() {
        val http = ScriptedHttp(respond(201, """{"id":1,"activity_id":77}"""))
        assertEquals(StravaUploadOutcome.Uploaded(77L), client(http, MemoryTokens(freshTokens())).upload(ride()))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `Duplikat mit und ohne Aktivitaets-ID`() {
        val mitId = ScriptedHttp(
            respond(201, """{"id":5,"activity_id":null,"error":null}"""),
            respond(200, """{"id":5,"activity_id":null,"error":"x.gpx duplicate of <a href='/activities/123'>123</a>"}"""),
        )
        assertEquals(StravaUploadOutcome.Duplicate(123L), client(mitId, MemoryTokens(freshTokens())).upload(ride()))

        val ohneId = ScriptedHttp(respond(201, """{"id":5,"error":"Duplicate of activity"}"""))
        assertEquals(StravaUploadOutcome.Duplicate(null), client(ohneId, MemoryTokens(freshTokens())).upload(ride()))
    }

    @Test
    fun `anderer Verarbeitungsfehler ist FILE mit Detail`() {
        val http = ScriptedHttp(respond(201, """{"id":5,"error":"Improperly formatted data."}"""))
        assertEquals(
            StravaUploadOutcome.Failed(StravaError.FILE, "Improperly formatted data."),
            client(http, MemoryTokens(freshTokens())).upload(ride()),
        )
    }

    @Test
    fun `401 beim Upload erneuert genau einmal und wiederholt genau einmal`() {
        val store = MemoryTokens(freshTokens())
        val http = ScriptedHttp(
            respond(401, """{"message":"Authorization Error"}"""),
            respond(200, """{"access_token":"A2","refresh_token":"R2","expires_at":1021600}"""),
            respond(201, """{"id":8,"activity_id":88}"""),
        )

        assertEquals(StravaUploadOutcome.Uploaded(88L), client(http, store).upload(ride()))

        http.assertDone()
        assertEquals("https://www.strava.com/oauth/token", http.requests[1].url)
        assertEquals("Bearer A2", http.requests[2].headers["Authorization"])
        assertEquals("A2", store.tokens?.accessToken)
    }

    @Test
    fun `zweites 401 nach Erneuerung vergisst den Zugang`() {
        val store = MemoryTokens(freshTokens())
        val http = ScriptedHttp(
            respond(401, "{}"),
            respond(200, """{"access_token":"A2","refresh_token":"R2","expires_at":1021600}"""),
            respond(401, "{}"),
        )
        val outcome = client(http, store).upload(ride())
        assertEquals(StravaError.UNAUTHORIZED, (outcome as StravaUploadOutcome.Failed).error)
        assertNull(store.tokens)
    }

    @Test
    fun `Statuscodes und Netzfehler beim Upload werden zugeordnet`() {
        fun errorFor(step: (HttpRequest) -> HttpResponse): StravaError {
            val outcome = client(ScriptedHttp(step), MemoryTokens(freshTokens())).upload(ride())
            return (outcome as StravaUploadOutcome.Failed).error
        }
        assertEquals(StravaError.RATE_LIMITED, errorFor(respond(429)))
        assertEquals(StravaError.SERVER, errorFor(respond(503)))
        assertEquals(StravaError.FILE, errorFor(respond(400, """{"message":"Bad Request"}""")))
        assertEquals(StravaError.UNAUTHORIZED, errorFor(respond(403)))
        assertEquals(StravaError.NETWORK, errorFor { throw IOException("Zeitueberschreitung") })
    }

    @Test
    fun `nach allen Abfragen noch in Verarbeitung liefert Processing`() {
        val processing = respond(200, """{"id":7,"activity_id":null,"error":null}""")
        val steps = arrayOf(respond(201, """{"id":7}""")) + Array(StravaClient.POLL_DELAYS_S.size) { processing }
        val http = ScriptedHttp(*steps)

        assertEquals(StravaUploadOutcome.Processing(7L), client(http, MemoryTokens(freshTokens())).upload(ride()))

        http.assertDone()
        assertEquals(StravaClient.POLL_DELAYS_S.map { it * 1000 }, sleeps)
    }

    @Test
    fun `Netzfehler oder Drosselung waehrend der Abfrage ist Processing`() {
        val offline = ScriptedHttp(respond(201, """{"id":7}"""), { throw IOException("weg") })
        assertEquals(StravaUploadOutcome.Processing(7L), client(offline, MemoryTokens(freshTokens())).upload(ride()))
        val gedrosselt = ScriptedHttp(respond(201, """{"id":7}"""), respond(429))
        assertEquals(StravaUploadOutcome.Processing(7L), client(gedrosselt, MemoryTokens(freshTokens())).upload(ride()))
    }

    @Test
    fun `Fehler beim Erneuern oder unlesbare Antwort waehrend der Abfrage behalten die Upload-ID`() {
        // Upload angenommen, dann laeuft der Token ab: Die Erneuerung wird
        // gedrosselt bzw. scheitert am Server — die ID muss erhalten bleiben.
        for (refreshStatus in listOf(429, 500)) {
            var clock = now
            val store = MemoryTokens(freshTokens().copy(expiresAtS = now + 3600))
            val http = ScriptedHttp(
                { clock = now + 7200; HttpResponse(201, """{"id":7}""") },
                respond(refreshStatus),
            )
            val client = StravaClient(http, credentials, store, nowS = { clock }, sleep = {})
            assertEquals(StravaUploadOutcome.Processing(7L), client.upload(ride()), "Erneuerung mit $refreshStatus")
            http.assertDone()
            // Der Zugang bleibt — nur 400/401 beim Erneuern vergisst ihn.
            assertEquals("ref", store.tokens?.refreshToken)
        }

        val kaputt = ScriptedHttp(respond(201, """{"id":7}"""), respond(200, "<html>"))
        assertEquals(StravaUploadOutcome.Processing(7L), client(kaputt, MemoryTokens(freshTokens())).upload(ride()))
    }

    @Test
    fun `entzogener Zugang waehrend der Abfrage ist UNAUTHORIZED`() {
        val http = ScriptedHttp(
            respond(201, """{"id":7}"""),
            respond(401),
            respond(401, "{}"),
        )
        val store = MemoryTokens(freshTokens())
        val outcome = client(http, store).upload(ride())
        assertEquals(StravaError.UNAUTHORIZED, (outcome as StravaUploadOutcome.Failed).error)
        assertNull(store.tokens)
    }

    @Test
    fun `disconnect wartet auf eine laufende Erneuerung und loescht danach`() {
        val store = MemoryTokens(freshTokens().copy(expiresAtS = now - 10))
        val refreshStarted = java.util.concurrent.CountDownLatch(1)
        val releaseRefresh = java.util.concurrent.CountDownLatch(1)
        val http = ScriptedHttp(
            {
                refreshStarted.countDown()
                releaseRefresh.await()
                HttpResponse(200, """{"access_token":"A2","refresh_token":"R2","expires_at":1021600}""")
            },
            respond(200), // Deauthorize
        )
        val client = client(http, store)
        val refresher = Thread { runCatching { client.validAccessToken() } }
        refresher.start()
        refreshStarted.await()
        val disconnecter = Thread { client.disconnect() }
        disconnecter.start()
        Thread.sleep(100)
        releaseRefresh.countDown()
        refresher.join(5000)
        disconnecter.join(5000)

        assertNull(store.tokens)
        http.assertDone()
    }

    @Test
    fun `resumePolling fragt nur ab und laedt nicht erneut hoch`() {
        val http = ScriptedHttp(respond(200, """{"id":7,"activity_id":70}"""))

        assertEquals(StravaUploadOutcome.Uploaded(70L), client(http, MemoryTokens(freshTokens())).resumePolling(7L))

        val request = http.requests.single()
        assertEquals(HttpMethod.GET, request.method)
        assertEquals("https://www.strava.com/api/v3/uploads/7", request.url)
    }

    @Test
    fun `geplante Tour oder Tour ohne Zeitstempel wird ohne Anfrage abgelehnt`() {
        val http = ScriptedHttp()
        val c = client(http, MemoryTokens(freshTokens()))
        assertEquals(StravaError.NOT_UPLOADABLE, assertFailsWith<StravaException> { c.upload(ride(planned = true)) }.error)
        assertEquals(StravaError.NOT_UPLOADABLE, assertFailsWith<StravaException> { c.upload(ride(withTime = false)) }.error)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `isStravaUploadable braucht mindestens zwei Punkte`() {
        assertTrue(isStravaUploadable(ride()))
        assertTrue(!isStravaUploadable(ride().copy(points = ride().points.take(1))))
    }

    // ------------------------------------------------------------ Trennen

    @Test
    fun `disconnect entzieht den Zugang und loescht ihn lokal`() {
        val store = MemoryTokens(freshTokens())
        val http = ScriptedHttp(respond(200, """{"access_token":"acc"}"""))
        client(http, store).disconnect()
        val request = http.requests.single()
        assertEquals("https://www.strava.com/oauth/deauthorize", request.url)
        assertEquals("Bearer acc", request.headers["Authorization"])
        assertNull(store.tokens)
    }

    @Test
    fun `disconnect loescht auch ohne Netz`() {
        val store = MemoryTokens(freshTokens())
        client(ScriptedHttp({ throw IOException("offline") }), store).disconnect()
        assertNull(store.tokens)
    }

    // ------------------------------------------------------------ Helfer

    @Test
    fun `duplicateActivityId liest die erste Zahl hinter duplicate of`() {
        assertEquals(123L, duplicateActivityId("a.gpx duplicate of <a href='/activities/123'>123</a>"))
        assertEquals(9L, duplicateActivityId("Duplicate of activity 9"))
        assertNull(duplicateActivityId("duplicate of activity"))
    }

    @Test
    fun `withGpxTrackType setzt den Typ hinter den Tracknamen`() {
        val gpx = buildGpx("Runde", listOf(TrackPoint(1.0, 2.0)))
        val typed = withGpxTrackType(gpx, "cycling")
        assertTrue(typed.contains("<trk>\n    <name>Runde</name>\n    <type>cycling</type>\n    <trkseg>"), typed)
        // Genau einmal, und nicht in den Metadaten.
        assertEquals(1, Regex("<type>").findAll(typed).count())
        // Weiterhin gueltiges GPX.
        assertEquals(1, parseGpx(typed).points.size)
        assertEquals("kein gpx", withGpxTrackType("kein gpx", "cycling"))
    }

    @Test
    fun `stravaActivityUrl`() {
        assertEquals("https://www.strava.com/activities/42", stravaActivityUrl(42))
    }
}
