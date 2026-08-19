package de.trailscape.app.data

import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.StoredRideLoadFacts
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.computeRideLoadFacts
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [RideLoadCacheStore] — die dateigestuetzte Ablage der
 * Tourlast-Destillate (`rides/last-cache.json`).
 */
class RideLoadCacheStoreTest {

    private val dir: File = createTempDirectory("last-cache-test").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun facts(): StoredRideLoadFacts {
        val createdAt = 1700000000000L
        val ride = Ride(
            id = "a",
            name = "Tour",
            createdAt = createdAt,
            points = (0..720).map {
                TrackPoint(
                    lat = 48.0 + it * 0.0001,
                    lon = 11.0,
                    ele = 500.0,
                    time = createdAt + it * 5000L,
                    hr = 140,
                )
            },
            stats = RideStats(distanceKm = 20.0, ascentM = 100.0, descentM = 100.0, durationS = 3600),
        )
        return StoredRideLoadFacts(
            updatedAt = createdAt,
            profileSignature = "sig-1",
            facts = computeRideLoadFacts(ride, TrainingProfile(ageYears = 40, weightKg = 78.0)),
        )
    }

    @Test
    fun `Eintraege ueberleben flush und Neuinstanziierung`() {
        val store = RideLoadCacheStore(dir)
        val entry = facts()
        store.put("a", entry)
        store.flush()
        assertTrue(File(dir, RideLoadCacheStore.FILE_NAME).exists())

        val reloaded = RideLoadCacheStore(dir)
        val roundtripped = assertNotNull(reloaded.get("a"))
        assertEquals(entry.updatedAt, roundtripped.updatedAt)
        assertEquals(entry.profileSignature, roundtripped.profileSignature)
        // Die tragenden Kennzahlen ueberleben den JSON-Roundtrip exakt.
        assertEquals(entry.facts.hrLoad, roundtripped.facts.hrLoad, 0.0)
        assertEquals(entry.facts.physicsNpW, roundtripped.facts.physicsNpW, 0.0)
        assertEquals(entry.facts.physicsMovingTimeS, roundtripped.facts.physicsMovingTimeS, 0.0)
        assertEquals(entry.facts.bestTwentyMinW, roundtripped.facts.bestTwentyMinW)
        assertEquals(entry.facts.steadySegments, roundtripped.facts.steadySegments)
        assertEquals(entry.facts, roundtripped.facts)
    }

    @Test
    fun `retainAll wirft Eintraege geloeschter Touren weg`() {
        val store = RideLoadCacheStore(dir)
        store.put("a", facts())
        store.put("b", facts())
        store.retainAll(setOf("a"))
        store.flush()

        val reloaded = RideLoadCacheStore(dir)
        assertNotNull(reloaded.get("a"))
        assertNull(reloaded.get("b"))
    }

    @Test
    fun `eine kaputte Cache-Datei liest sich als leerer Cache`() {
        File(dir, RideLoadCacheStore.FILE_NAME).also { it.parentFile.mkdirs() }
            .writeText("kein json", Charsets.UTF_8)
        val store = RideLoadCacheStore(dir)
        assertNull(store.get("a"))
        // Und laesst sich danach normal beschreiben.
        store.put("a", facts())
        store.flush()
        assertNotNull(RideLoadCacheStore(dir).get("a"))
    }
}
