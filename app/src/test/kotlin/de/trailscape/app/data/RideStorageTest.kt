package de.trailscape.app.data

import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [RideStorage] — Metadaten-Index, inkrementelle Pflege und die
 * Quarantaene defekter Dateien. Reine JVM-Tests: [RideStorage] hat bewusst
 * keinen Android-Import, das Verzeichnis kommt als Parameter.
 */
class RideStorageTest {

    private val dir: File = createTempDirectory("rides-test").toFile()
    private val storage = RideStorage(dir)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun ride(
        id: String,
        name: String = "Tour $id",
        createdAt: Long = 1700000000000L,
        updatedAt: Long = createdAt,
        pointCount: Int = 3,
    ): Ride = Ride(
        id = id,
        name = name,
        createdAt = createdAt,
        points = (0 until pointCount).map {
            TrackPoint(lat = 48.0 + it * 0.001, lon = 11.0, ele = 500.0, time = createdAt + it * 1000L)
        },
        stats = RideStats(distanceKm = 10.0, ascentM = 100.0, descentM = 50.0, durationS = 3600),
        updatedAt = updatedAt,
    )

    // ------------------------------------------------------------- Aufbau

    @Test
    fun `listSummaries baut den Index aus den Tour-Dateien auf`() {
        storage.saveRides(listOf(ride("a", createdAt = 1000), ride("b", createdAt = 2000)))

        // Frische Instanz ohne In-Memory-Zustand: liest Index bzw. Dateien.
        val listing = RideStorage(dir).listSummaries()
        assertEquals(0, listing.quarantinedCount)
        assertEquals(listOf("b", "a"), listing.summaries.map { it.id })
        assertEquals(3, listing.summaries.first().pointCount)
        assertEquals(10.0, listing.summaries.first().stats.distanceKm)
        assertTrue(File(dir, RideStorage.INDEX_FILE_NAME).exists())
    }

    @Test
    fun `unveraenderte Dateien kommen aus dem Index statt aus dem Voll-Parse`() {
        // Zwei Namen gleicher Byte-Laenge, damit sich der Fingerabdruck
        // (Groesse + mtime) NICHT aendert, wenn die Datei heimlich getauscht wird.
        val alt = ride("a", name = "AAAA", pointCount = 0)
        val neu = ride("a", name = "BBBB", pointCount = 0)
        storage.saveRide(alt)
        val file = File(dir, "a.json")
        val mtime = file.lastModified()

        // Datei hinter dem Ruecken des Index tauschen, Fingerabdruck erhalten.
        val storage2 = RideStorage(dir)
        file.writeText(neu.toJson().toString(), Charsets.UTF_8)
        assertTrue(file.setLastModified(mtime))
        assertEquals("AAAA", storage2.listSummaries().summaries.single().name)

        // Aenderung am Fingerabdruck (mtime) laesst nachparsen.
        assertTrue(file.setLastModified(mtime + 10_000))
        assertEquals("BBBB", storage2.listSummaries().summaries.single().name)
    }

    @Test
    fun `kaputter Index wird komplett neu aufgebaut`() {
        storage.saveRide(ride("a"))
        File(dir, RideStorage.INDEX_FILE_NAME).writeText("das ist kein json", Charsets.UTF_8)

        val listing = RideStorage(dir).listSummaries()
        assertEquals(listOf("a"), listing.summaries.map { it.id })
        assertEquals(0, listing.quarantinedCount)
    }

    // ---------------------------------------------------------- Inkrement

    @Test
    fun `saveRide und deleteRide pflegen den Index inkrementell`() {
        storage.saveRide(ride("a", createdAt = 1000))
        storage.saveRide(ride("b", createdAt = 2000))
        assertEquals(listOf("b", "a"), storage.listSummaries().summaries.map { it.id })

        storage.deleteRide("b")
        assertEquals(listOf("a"), storage.listSummaries().summaries.map { it.id })

        // Auch eine frische Instanz (nur Index + Verzeichnis) sieht den Stand.
        assertEquals(listOf("a"), RideStorage(dir).listSummaries().summaries.map { it.id })
    }

    @Test
    fun `Dateien fremder Instanzen werden beim Abgleich erkannt`() {
        storage.saveRide(ride("a", createdAt = 1000))
        storage.listSummaries()

        // Eine zweite Instanz (z. B. der Aufzeichnungsdienst mit eigenem
        // RideStorage) legt eine Datei an, ohne dass diese Instanz es merkt.
        RideStorage(dir).saveRide(ride("fremd", createdAt = 3000))

        assertEquals(
            listOf("fremd", "a"),
            storage.listSummaries().summaries.map { it.id },
        )
    }

    // ---------------------------------------------------------- Volltour

    @Test
    fun `loadRide liefert die volle Tour mit Punkten`() {
        val original = ride("a", pointCount = 5)
        storage.saveRide(original)
        val loaded = assertNotNull(storage.loadRide("a"))
        assertEquals(original, loaded)
        assertNull(storage.loadRide("gibt-es-nicht"))
    }

    // --------------------------------------------------------- Quarantaene

    @Test
    fun `defekte Dateien wandern nach defekt und werden gezaehlt`() {
        storage.saveRide(ride("gut"))
        File(dir, "kaputt.json").writeText("{ kein json", Charsets.UTF_8)
        File(dir, "falsches-format.json").writeText("""{"foo": 1}""", Charsets.UTF_8)

        val listing = RideStorage(dir).listSummaries()
        assertEquals(2, listing.quarantinedCount)
        assertEquals(listOf("gut"), listing.summaries.map { it.id })

        val quarantine = File(dir, RideStorage.QUARANTINE_DIR_NAME)
        assertTrue(File(quarantine, "kaputt.json").exists())
        assertTrue(File(quarantine, "falsches-format.json").exists())
        assertFalse(File(dir, "kaputt.json").exists())

        // Der naechste Lauf meldet nichts mehr — die Dateien sind ja weg.
        assertEquals(0, RideStorage(dir).listSummaries().quarantinedCount)
    }

    @Test
    fun `loadRide stellt eine defekte Datei in Quarantaene`() {
        storage.saveRide(ride("a"))
        storage.listSummaries()
        File(dir, "a.json").writeText("kaputt", Charsets.UTF_8)

        assertNull(storage.loadRide("a"))
        assertTrue(File(File(dir, RideStorage.QUARANTINE_DIR_NAME), "a.json").exists())
        assertTrue(storage.listSummaries().summaries.isEmpty())
    }

    @Test
    fun `tombstones und Caches im Tourenverzeichnis bleiben unangetastet`() {
        storage.saveRide(ride("a"))
        File(dir, "tombstones.json").writeText("""[{"id":"x","deletedAt":1}]""", Charsets.UTF_8)
        File(dir, RideLoadCacheStore.FILE_NAME).writeText("""{"version":1,"entries":[]}""", Charsets.UTF_8)

        val listing = RideStorage(dir).listSummaries()
        assertEquals(0, listing.quarantinedCount)
        assertEquals(listOf("a"), listing.summaries.map { it.id })
        assertTrue(File(dir, "tombstones.json").exists())
        assertTrue(File(dir, RideLoadCacheStore.FILE_NAME).exists())
    }

    // ---------------------------------------------------------- Uebergang

    @Test
    fun `listRides liefert weiterhin volle Touren`() {
        val a = ride("a", createdAt = 1000)
        val b = ride("b", createdAt = 2000)
        storage.saveRides(listOf(a, b))
        assertEquals(listOf(b, a), storage.listRides())
    }
}
