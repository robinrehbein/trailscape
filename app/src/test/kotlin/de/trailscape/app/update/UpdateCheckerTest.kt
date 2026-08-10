package de.trailscape.app.update

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpRequest
import de.trailscape.core.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests des Update-Kanals — Tag-Parsing, Versionsvergleich, Drosselung und
 * die „einmal pro Version"-Regel der Snackbar.
 *
 * Laufen als gewoehnliche JVM-Tests ohne Robolectric: [UpdateChecker] und
 * `UpdateLogic.kt` haben keinen einzigen Android-Import. Netz und Speicher
 * kommen als Fakes herein — der `HttpClient` als Lambda (die `:core`-
 * Schnittstelle ist ein `fun interface`, genau wie in `SyncClientTest`), der
 * `KeyValueStore` als Map.
 */
class UpdateCheckerTest {

    private class FakeStore : de.trailscape.core.KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun setString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    /** Zaehlt die Anfragen mit — so faellt auf, wenn die Drosselung nicht greift. */
    private class RecordingClient(
        private val handler: (HttpRequest) -> HttpResponse,
    ) : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun releasesJson(vararg tags: String): String =
        tags.joinToString(prefix = "[", postfix = "]") { tag ->
            """{"tag_name":"$tag","draft":false,"prerelease":false}"""
        }

    private fun checker(
        store: FakeStore = FakeStore(),
        client: HttpClient = RecordingClient { HttpResponse(200, releasesJson()) },
        installedRun: Int? = 100,
        now: Long = 1_000_000L,
    ) = UpdateChecker(
        httpClient = client,
        store = store,
        installedRunNumber = { installedRun },
        nowMs = { now },
    )

    // --- Tag-Parsing ---

    @Test
    fun `runNumberFromTag liest die Lauf-Nummer aus einem v2_0-Tag`() {
        assertEquals(123, runNumberFromTag("v2.0.123"))
        assertEquals(1, runNumberFromTag("v2.0.1"))
    }

    @Test
    fun `runNumberFromTag ignoriert fremde Tags`() {
        assertNull(runNumberFromTag("latest"))
        assertNull(runNumberFromTag("v2.1.4"))
        assertNull(runNumberFromTag("v1.9.0"))
        assertNull(runNumberFromTag("v2.0."))
        assertNull(runNumberFromTag("v2.0.12-rc1"))
        assertNull(runNumberFromTag(""))
    }

    @Test
    fun `newestRunNumber nimmt die hoechste Nummer und ueberspringt den latest-Alias`() {
        val json = releasesJson("latest", "v2.0.7", "v2.0.42", "v2.0.13")
        assertEquals(42, newestRunNumber(json))
    }

    @Test
    fun `newestRunNumber ueberspringt Entwuerfe und Vorabversionen`() {
        val json = """
            [
              {"tag_name":"v2.0.99","draft":true,"prerelease":false},
              {"tag_name":"v2.0.98","draft":false,"prerelease":true},
              {"tag_name":"v2.0.10","draft":false,"prerelease":false}
            ]
        """.trimIndent()
        assertEquals(10, newestRunNumber(json))
    }

    @Test
    fun `newestRunNumber liefert null bei unbrauchbarer Antwort`() {
        assertNull(newestRunNumber(""))
        assertNull(newestRunNumber("kein JSON"))
        assertNull(newestRunNumber("[]"))
        assertNull(newestRunNumber("""{"message":"rate limit"}"""))
        assertNull(newestRunNumber(releasesJson("latest")))
    }

    @Test
    fun `runNumberFromVersionCode rechnet den Offset heraus`() {
        assertEquals(123, runNumberFromVersionCode(2123L))
        assertEquals(1, runNumberFromVersionCode(2001L))
        // Codes der alten Flutter-Pipeline liegen unter dem Offset.
        assertNull(runNumberFromVersionCode(2000L))
        assertNull(runNumberFromVersionCode(42L))
    }

    // --- Drosselung ---

    @Test
    fun `shouldCheckNow entscheidet nach dem 24-Stunden-Fenster`() {
        val now = 100_000_000L
        assertTrue(shouldCheckNow(lastCheckAtMs = null, nowMs = now))
        assertFalse(shouldCheckNow(lastCheckAtMs = now - 1_000L, nowMs = now))
        assertFalse(shouldCheckNow(lastCheckAtMs = now - UPDATE_CHECK_INTERVAL_MS + 1, nowMs = now))
        assertTrue(shouldCheckNow(lastCheckAtMs = now - UPDATE_CHECK_INTERVAL_MS, nowMs = now))
        // Zurueckgestellte Uhr: lieber einmal zu viel pruefen als nie wieder.
        assertTrue(shouldCheckNow(lastCheckAtMs = now + UPDATE_CHECK_INTERVAL_MS, nowMs = now))
    }

    @Test
    fun `der Start-Check fragt innerhalb von 24 Stunden nicht erneut nach`() {
        val store = FakeStore()
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        val first = checker(store = store, client = client, now = 1_000_000L)
        assertEquals(UpdateCheckResult.Available("2.0.150", 150), first.check())
        assertEquals(1, client.requests.size)

        // Zwei Stunden spaeter: kein zweiter Netzzugriff.
        val second = checker(store = store, client = client, now = 1_000_000L + 2 * 3_600_000L)
        assertEquals(UpdateCheckResult.Skipped, second.check())
        assertEquals(1, client.requests.size)

        // Einen Tag spaeter schon.
        val third = checker(store = store, client = client, now = 1_000_000L + UPDATE_CHECK_INTERVAL_MS)
        assertEquals(UpdateCheckResult.Available("2.0.150", 150), third.check())
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `die manuelle Pruefung uebergeht die Drosselung`() {
        val store = FakeStore()
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        checker(store = store, client = client).check()
        val result = checker(store = store, client = client).checkNow()
        assertEquals(UpdateCheckResult.Available("2.0.150", 150), result)
        assertEquals(2, client.requests.size)
    }

    // --- Versionsvergleich ---

    @Test
    fun `keine Meldung wenn die installierte Version die neueste ist`() {
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.100", "v2.0.99")) }
        assertEquals(UpdateCheckResult.UpToDate, checker(client = client, installedRun = 100).check())
        // Eine noch neuere lokale Version (Entwicklungsbuild) meldet ebenfalls nichts.
        assertEquals(UpdateCheckResult.UpToDate, checker(client = client, installedRun = 300).check())
    }

    // --- Fehler bleiben still ---

    @Test
    fun `ein Netzwerkfehler endet in Failed und verbraucht das Zeitfenster nicht`() {
        val store = FakeStore()
        val client = RecordingClient { throw java.io.IOException("kein Netz") }
        assertEquals(UpdateCheckResult.Failed, checker(store = store, client = client).check())
        assertTrue(store.values.isEmpty(), "Der Zeitstempel darf erst nach Erfolg stehen")

        // Der naechste Start darf es sofort wieder versuchen.
        val ok = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        assertEquals(
            UpdateCheckResult.Available("2.0.150", 150),
            checker(store = store, client = ok).check(),
        )
    }

    @Test
    fun `ein HTTP-Fehlerstatus endet in Failed`() {
        val client = RecordingClient { HttpResponse(403, "rate limit exceeded") }
        assertEquals(UpdateCheckResult.Failed, checker(client = client).check())
    }

    @Test
    fun `ohne ermittelbare Lauf-Nummer wird gar nicht erst gefragt`() {
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        assertEquals(UpdateCheckResult.Failed, checker(client = client, installedRun = null).check())
        assertEquals(0, client.requests.size)
    }

    @Test
    fun `die Anfrage traegt den von GitHub verlangten User-Agent`() {
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        checker(client = client).check()
        val request = client.requests.single()
        assertEquals(RELEASES_API_URL, request.url)
        assertEquals(UPDATE_USER_AGENT, request.headers["User-Agent"])
    }

    // --- Start-Check: Karte und Snackbar ---

    @Test
    fun `die Snackbar erscheint einmal pro entdeckter Version`() {
        val store = FakeStore()
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }

        val first = checker(store = store, client = client, now = 1_000L).startupCheck()
        assertEquals("2.0.150", first.noticeVersion)
        assertEquals("2.0.150", first.announceVersion)

        // Naechster App-Start, gedrosselt: Karte ja, Snackbar nein.
        val second = checker(store = store, client = client, now = 2_000L).startupCheck()
        assertEquals("2.0.150", second.noticeVersion)
        assertNull(second.announceVersion)

        // Eine neuere Version meldet sich wieder.
        val newer = RecordingClient { HttpResponse(200, releasesJson("v2.0.151")) }
        val third = checker(
            store = store,
            client = newer,
            now = 1_000L + UPDATE_CHECK_INTERVAL_MS,
        ).startupCheck()
        assertEquals("2.0.151", third.noticeVersion)
        assertEquals("2.0.151", third.announceVersion)
    }

    @Test
    fun `eine weggewischte Karte bleibt weg bis eine neuere Version erscheint`() {
        val store = FakeStore()
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        checker(store = store, client = client, now = 1_000L).startupCheck()

        checker(store = store, client = client, now = 1_000L).dismiss("2.0.150")
        val afterDismiss = checker(store = store, client = client, now = 2_000L).startupCheck()
        assertNull(afterDismiss.noticeVersion)
        assertNull(afterDismiss.announceVersion)

        val newer = RecordingClient { HttpResponse(200, releasesJson("v2.0.160")) }
        val next = checker(
            store = store,
            client = newer,
            now = 1_000L + UPDATE_CHECK_INTERVAL_MS,
        ).startupCheck()
        assertEquals("2.0.160", next.noticeVersion)
        assertEquals("2.0.160", next.announceVersion)
    }

    @Test
    fun `der Start-Check zeigt offline den zuletzt bekannten Stand`() {
        val store = FakeStore()
        val online = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        checker(store = store, client = online, now = 1_000L).startupCheck()

        // Einen Tag spaeter ohne Netz: die Karte steht trotzdem, die Snackbar
        // schweigt (diese Version wurde schon gemeldet).
        val offline = RecordingClient { throw java.io.IOException("kein Netz") }
        val result = checker(
            store = store,
            client = offline,
            now = 1_000L + UPDATE_CHECK_INTERVAL_MS,
        ).startupCheck()
        assertEquals("2.0.150", result.noticeVersion)
        assertNull(result.announceVersion)
    }

    @Test
    fun `die manuelle Pruefung holt eine weggewischte Karte zurueck`() {
        val store = FakeStore()
        val client = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }
        val c = checker(store = store, client = client)
        c.startupCheck()
        c.dismiss("2.0.150")

        assertEquals(UpdateCheckResult.Available("2.0.150", 150), c.checkNow())
        // Danach steht die Karte wieder — aber ohne erneute Snackbar beim
        // naechsten Start: Die Nutzerin hat die Antwort ja gerade gelesen.
        val next = checker(store = store, client = client, now = 2_000L).startupCheck()
        assertEquals("2.0.150", next.noticeVersion)
        assertNull(next.announceVersion)
    }

    @Test
    fun `ohne bekannten Stand meldet der Start-Check nichts`() {
        val store = FakeStore()
        val client = RecordingClient { throw java.io.IOException("kein Netz") }
        val result = checker(store = store, client = client).startupCheck()
        assertNull(result.noticeVersion)
        assertNull(result.announceVersion)
    }
}
