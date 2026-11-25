package de.trailscape.app.challenge

import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeCurve
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.createChallenge
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

/** Die Ablage teilbarer eigener Indoor-Touren (Blatt „Teilen" im Tourdetail). */
class ChallengeDraftStoreTest {
    private val tmp: File = createTempDirectory("challenge-drafts").toFile()
    private val dir = File(tmp, "challenge-drafts")
    private val store = ChallengeDraftStore(dir)

    @After
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun draft(totalS: Double = 60.0): Challenge =
        createChallenge(ChallengeRoute.Catalog("alpe-dhuez", 2), ChallengeCurve(5, totalS, List(13) { it * 400 }), "", "2026-11-25")

    @Test
    fun `ablegen und wieder lesen`() {
        assertNull(store.load("tour-1"))
        val c = draft()
        assertTrue(store.save("tour-1", c, reply = false))
        val loaded = assertNotNull(store.load("tour-1"))
        assertEquals(c, loaded.challenge)
        assertFalse(loaded.reply)
        assertEquals("2026-11-25", loaded.challenge.date)
        assertNull(store.load("tour-2"))
    }

    @Test
    fun `die Antwort bleibt als Antwort erkennbar`() {
        assertTrue(store.save("tour-1", draft(), reply = true))
        assertTrue(assertNotNull(store.load("tour-1")).reply)
    }

    @Test
    fun `ein neuer Entwurf ersetzt den alten, auch mit anderer Fassung`() {
        store.save("tour-1", draft(60.0), reply = true)
        store.save("tour-1", draft(58.0), reply = false)
        val loaded = assertNotNull(store.load("tour-1"))
        assertFalse(loaded.reply)
        assertEquals(58.0, loaded.challenge.curve.totalS)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun `loeschen entfernt den Entwurf`() {
        store.save("tour-1", draft(), reply = false)
        store.delete("tour-1")
        assertNull(store.load("tour-1"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `eine Kennung, die kein schlichter Dateiname ist, wird nicht abgelegt`() {
        assertFalse(store.save("../boese", draft(), reply = false))
        assertFalse(store.save("", draft(), reply = false))
        assertNull(store.load("../boese"))
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `eine beschaedigte Datei ist kein Entwurf`() {
        store.save("tour-1", draft(), reply = false)
        File(dir, ChallengeDraftStore.fileName("tour-1", reply = false)).writeText("{ kaputt")
        assertNull(store.load("tour-1"))
    }

    @Test
    fun `mehr als das Hoechstmass verdraengt die aeltesten`() {
        for (i in 0 until ChallengeDraftStore.MAX_ENTRIES + 3) {
            assertTrue(store.save("tour-$i", draft(), reply = false))
            File(dir, ChallengeDraftStore.fileName("tour-$i", reply = false)).setLastModified(1_000_000L + i * 1_000L)
        }
        assertEquals(ChallengeDraftStore.MAX_ENTRIES, dir.listFiles()!!.size)
        assertNotNull(store.load("tour-${ChallengeDraftStore.MAX_ENTRIES + 2}"))
    }
}
