package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StravaAuthTest {

    @Test
    fun `Autorisierungs-URL enthaelt alle Parameter kodiert`() {
        val url = stravaAuthorizeUrl("12345", STRAVA_REDIRECT_URI, "abc_-XYZ")
        assertTrue(url.startsWith("https://www.strava.com/oauth/mobile/authorize?"), url)
        assertTrue(url.contains("client_id=12345"), url)
        assertTrue(url.contains("redirect_uri=trailscape%3A%2F%2Fstrava-callback"), url)
        assertTrue(url.contains("response_type=code"), url)
        assertTrue(url.contains("approval_prompt=auto"), url)
        assertTrue(url.contains("scope=activity%3Awrite"), url)
        assertTrue(url.contains("state=abc_-XYZ"), url)
    }

    @Test
    fun `Rueckruf mit passendem state und Bereich liefert den Code`() {
        val result = parseStravaCallback(
            mapOf("state" to "s1", "code" to "c0de", "scope" to "read,activity:write"),
            expectedState = "s1",
        )
        assertEquals(StravaCallbackResult.Code("c0de"), result)
    }

    @Test
    fun `Rueckruf ohne activity-write ist MissingScope`() {
        assertEquals(
            StravaCallbackResult.MissingScope,
            parseStravaCallback(mapOf("state" to "s1", "code" to "c", "scope" to "read"), "s1"),
        )
        assertEquals(
            StravaCallbackResult.MissingScope,
            parseStravaCallback(mapOf("state" to "s1", "code" to "c"), "s1"),
        )
    }

    @Test
    fun `Ablehnung ist Denied`() {
        assertEquals(
            StravaCallbackResult.Denied,
            parseStravaCallback(mapOf("state" to "s1", "error" to "access_denied"), "s1"),
        )
    }

    @Test
    fun `falscher oder fehlender state und fehlender Code sind Invalid`() {
        val ok = mapOf("state" to "s1", "code" to "c", "scope" to "activity:write")
        assertEquals(StravaCallbackResult.Invalid, parseStravaCallback(ok, "anders"))
        assertEquals(StravaCallbackResult.Invalid, parseStravaCallback(ok, null))
        assertEquals(StravaCallbackResult.Invalid, parseStravaCallback(ok - "state", "s1"))
        assertEquals(StravaCallbackResult.Invalid, parseStravaCallback(ok - "code", "s1"))
        assertEquals(StravaCallbackResult.Invalid, parseStravaCallback(ok + ("code" to ""), "s1"))
    }

    @Test
    fun `Tokens ueberstehen JSON verlustfrei`() {
        val tokens = StravaTokens("a", "r", 1_700_000_000L, "Robin")
        assertEquals(tokens, StravaTokens.fromJson(tokens.toJson()))
        val ohneName = StravaTokens("a", "r", 5L)
        assertEquals(ohneName, StravaTokens.fromJsonStringOrNull(ohneName.toJson().toString()))
        assertNull(StravaTokens.fromJsonStringOrNull("{kaputt"))
        assertNull(StravaTokens.fromJsonStringOrNull("""{"accessToken":"a"}"""))
    }

    @Test
    fun `expiresSoon kippt genau fuenf Minuten vor Ablauf`() {
        val tokens = StravaTokens("a", "r", expiresAtS = 10_000L)
        assertFalse(tokens.expiresSoon(10_000L - 301))
        assertTrue(tokens.expiresSoon(10_000L - 300))
        assertTrue(tokens.expiresSoon(20_000L))
    }

    @Test
    fun `toString verraet keine Geheimnisse`() {
        assertFalse(StravaTokens("geheim-a", "geheim-r", 1L).toString().contains("geheim"))
        assertFalse(StravaAppCredentials("1", "geheim").toString().contains("geheim"))
    }
}
