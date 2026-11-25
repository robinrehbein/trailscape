package de.trailscape.app.challenge

import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeCurve
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.createChallenge
import de.trailscape.core.indoor.catalog.CatalogText
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Speicher der Herausforderungen und Zuordnung zur Strecke auf dem Gerät. */
class ChallengeStoreTest {
    private val tmp: File = createTempDirectory("challenges").toFile()
    private val dir = File(tmp, "challenges")
    private val store = ChallengeStore(dir)

    @After
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun challenge(name: String = "Anna", route: ChallengeRoute = ChallengeRoute.Catalog("alpe-dhuez", 2)): Challenge {
        val dm = List(13) { it * 400 }
        return createChallenge(route, ChallengeCurve(5, 60.0, dm), name, "2026-11-25")
    }

    @Test
    fun `speichern, lesen, loeschen`() {
        assertTrue(store.list().isEmpty())
        val c = challenge()
        assertEquals(ChallengeStore.SaveResult.SAVED, store.save(c, 1_000L))
        assertEquals(listOf(c), store.list().map { it.challenge })
        assertEquals(c, store.get(c.id)?.challenge)
        assertTrue(store.delete(c.id))
        assertTrue(store.list().isEmpty())
        assertTrue(!store.delete(c.id))
    }

    @Test
    fun `dieselbe Datei zweimal ergibt einen Eintrag`() {
        val c = challenge()
        assertEquals(ChallengeStore.SaveResult.SAVED, store.save(c, 1L))
        assertEquals(ChallengeStore.SaveResult.ALREADY_THERE, store.save(c, 2L))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `neueste zuerst`() {
        val a = challenge("Anna")
        val b = challenge("Ben")
        store.save(a, 1_000L)
        store.save(b, 5_000L)
        assertEquals(listOf("Ben", "Anna"), store.list().map { it.challenge.name })
    }

    @Test
    fun `beschaedigte oder fremde Dateien stoeren die Liste nicht`() {
        val c = challenge()
        store.save(c, 1L)
        File(dir, "0000000000000000.trailscape-challenge").writeText("kein json")
        File(dir, "notiz.txt").writeText("hallo")
        // Inhalt gueltig, aber Dateiname passt nicht zur Kennung.
        File(dir, "aaaaaaaaaaaaaaaa.trailscape-challenge").writeText(challenge("Ben").toJson())
        // Von einer neueren App geschrieben.
        File(dir, "bbbbbbbbbbbbbbbb.trailscape-challenge").writeText("""{"format":"trailscape-challenge","version":9}""")
        assertEquals(listOf(c), store.list().map { it.challenge })
    }

    @Test
    fun `Loeschen nimmt nur gueltige Kennungen`() {
        val outside = File(tmp, "geheim.trailscape-challenge").apply { writeText("x") }
        assertTrue(!store.delete("../geheim"))
        assertTrue(outside.exists())
    }

    @Test
    fun `die Sammlung hat eine Obergrenze`() {
        dir.mkdirs()
        repeat(ChallengeStore.MAX_ENTRIES) { File(dir, "%016x.trailscape-challenge".format(it)).writeText("x") }
        assertEquals(ChallengeStore.SaveResult.FULL, store.save(challenge(), 1L))
    }

    @Test
    fun `gelesen werden hoechstens so viele Bytes wie erlaubt`() {
        val small = ByteArray(1000) { 1 }
        assertNotNull(readBoundedChallenge(ByteArrayInputStream(small)))
        val big = ByteArray(de.trailscape.core.challenge.CHALLENGE_MAX_BYTES + 1) { 1 }
        assertNull(readBoundedChallenge(ByteArrayInputStream(big)))
    }

    // --- Zuordnung zur Strecke auf dem Geraet ---------------------------------

    private val entry = null as de.trailscape.core.indoor.catalog.CatalogEntry?

    @Test
    fun `Teststrecke ist immer bereit`() {
        val info = challengeRouteInfo(ChallengeRoute.TestWorld, { entry }, { null })
        assertEquals(ChallengeAvailability.READY, info.availability)
        assertNull(info.name)
    }

    @Test
    fun `gleiche Version ist bereit, aeltere oder fehlende wird geladen, neuere passt nicht`() {
        val route = ChallengeRoute.Catalog("alpe-dhuez", 2)
        fun avail(installed: Int?, known: Boolean) =
            challengeRouteInfo(route, { if (known) catalogEntry("alpe-dhuez") else null }, { installed }).availability
        assertEquals(ChallengeAvailability.READY, avail(2, true))
        assertEquals(ChallengeAvailability.DOWNLOAD, avail(null, true))
        assertEquals(ChallengeAvailability.DOWNLOAD, avail(1, true))
        assertEquals(ChallengeAvailability.MISMATCH, avail(3, true))
        assertEquals(ChallengeAvailability.UNKNOWN, avail(null, false))
        // Eine Welt liegt da, der Katalog kennt sie nicht mehr: die Version entscheidet.
        assertEquals(ChallengeAvailability.READY, avail(2, false))
    }

    @Test
    fun `Name und Laenge kommen aus dem Katalog`() {
        val info = challengeRouteInfo(ChallengeRoute.Catalog("alpe-dhuez", 2), { catalogEntry(it) }, { 2 })
        assertEquals(CatalogText("Alpe d'Huez", "Alpe d'Huez"), info.name)
        assertEquals(13.8, info.distanceKm!!, 1e-9)
        assertEquals(1100.0, info.ascentM!!, 1e-9)
    }

    private fun catalogEntry(id: String) = de.trailscape.core.indoor.catalog.CatalogEntry(
        id = id,
        version = 2,
        worldId = "w-$id",
        name = CatalogText("Alpe d'Huez", "Alpe d'Huez"),
        region = CatalogText("Isère", "Isère"),
        country = "FR",
        kind = de.trailscape.core.indoor.catalog.CatalogRideKind.ROAD,
        lengthM = 13_800.0,
        ascentM = 1_100.0,
        surfaces = emptyMap(),
        description = CatalogText("", ""),
        packageFile = "$id-v2.zip",
        packageBytes = 1L,
        sha256 = null,
        aerial = false,
        aerialSource = null,
    )
}
