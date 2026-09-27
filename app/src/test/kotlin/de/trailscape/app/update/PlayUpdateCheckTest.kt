package de.trailscape.app.update

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpRequest
import de.trailscape.core.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests fuer das Play-Tor der Update-Pruefung: Stammt die Installation aus
 * Google Play, darf die App nie bei GitHub nachfragen und nie auf die APK
 * verweisen — weder still beim Start noch von Hand.
 *
 * Gewoehnliche JVM-Tests ohne Robolectric, wie `UpdateCheckerTest`. Die Fakes
 * sind von dort kopiert (dort `private`), damit jene Datei unberuehrt bleibt.
 */
class PlayUpdateCheckTest {

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

    /** Zaehlt die Anfragen mit — jede einzelne waere bei Play ein Fehler. */
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

    private fun newerRelease() = RecordingClient { HttpResponse(200, releasesJson("v2.0.150")) }

    private fun checker(
        store: FakeStore = FakeStore(),
        client: HttpClient = newerRelease(),
        allowed: () -> Boolean,
    ) = UpdateChecker(
        httpClient = client,
        store = store,
        installedRunNumber = { 100 },
        nowMs = { 1_000_000L },
        checkAllowed = allowed,
    )

    // --- Die reine Entscheidung ---

    @Test
    fun `Play-Installation schaltet die Pruefung ab`() {
        assertFalse(isUpdateCheckAllowed("com.android.vending"))
        assertFalse(isUpdateCheckAllowed(PLAY_STORE_INSTALLER))
    }

    @Test
    fun `unbekannter Installer erlaubt die Pruefung wie bisher`() {
        // adb, fehlgeschlagene Abfrage
        assertTrue(isUpdateCheckAllowed(null))
    }

    @Test
    fun `Sideload und fremde Stores erlauben die Pruefung`() {
        assertTrue(isUpdateCheckAllowed("com.google.android.packageinstaller"))
        assertTrue(isUpdateCheckAllowed("com.android.chrome"))
        assertTrue(isUpdateCheckAllowed("org.fdroid.fdroid"))
        assertTrue(isUpdateCheckAllowed(""))
    }

    // --- Das Tor im UpdateChecker ---

    @Test
    fun `startupCheck bei Play fragt nicht und zeigt auch keinen gespeicherten Stand`() {
        val store = FakeStore()
        // Rest aus einer frueheren GitHub-APK: neuere Version bekannt, Schalter an.
        store.values["trailscape.update.latestKnown"] = "150"
        store.values["trailscape.updates.autoCheck"] = "true"
        val before = store.values.toMap()
        val client = newerRelease()

        val result = checker(store = store, client = client, allowed = { false }).startupCheck()

        assertEquals(StartupUpdate(noticeVersion = null, announceVersion = null), result)
        assertEquals(0, client.requests.size)
        assertEquals(before, store.values, "kein ANNOUNCED, kein Zeitstempel")
    }

    @Test
    fun `checkNow bei Play fragt nicht und laesst den Store unveraendert`() {
        val store = FakeStore()
        store.values["trailscape.update.dismissed"] = "2.0.150"
        store.values["trailscape.update.latestKnown"] = "150"
        val before = store.values.toMap()
        val client = newerRelease()

        val result = checker(store = store, client = client, allowed = { false }).checkNow()

        assertEquals(UpdateCheckResult.Skipped, result)
        assertEquals(0, client.requests.size)
        assertEquals(before, store.values)
    }

    @Test
    fun `check mit force fragt bei Play trotzdem nicht`() {
        val client = newerRelease()
        val checker = checker(client = client, allowed = { false })

        assertEquals(UpdateCheckResult.Skipped, checker.check(force = true))
        assertEquals(UpdateCheckResult.Skipped, checker.check(force = false))
        assertEquals(0, client.requests.size)
        assertFalse(checker.isCheckAllowed())
    }

    @Test
    fun `wirft die Installer-Abfrage, gilt die Pruefung als erlaubt`() {
        val checker = checker(allowed = { error("PackageManager kaputt") })
        assertTrue(checker.isCheckAllowed())
    }

    @Test
    fun `Sideload findet ein neueres Release weiterhin`() {
        val client = newerRelease()
        val checker = UpdateChecker(
            httpClient = client,
            store = FakeStore(),
            installedRunNumber = { 100 },
            nowMs = { 1_000_000L },
            // Vorgabe fuer checkAllowed: true
        )

        val result = checker.startupCheck()

        assertTrue(checker.isCheckAllowed())
        assertEquals(StartupUpdate(noticeVersion = "2.0.150", announceVersion = "2.0.150"), result)
        assertEquals(1, client.requests.size)
    }
}
