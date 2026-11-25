package de.trailscape.app.indoor.background

import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.core.Ride
import de.trailscape.core.TrackPoint
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorResumePoint
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.SurfaceKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sicherung einer laufenden Indoor-Fahrt und ihre Wiederherstellung (docs/indoor/hintergrund.md). */
class IndoorRideJournalTest {

    private val dir: File = createTempDirectory("indoor-journal").toFile()
    private val journal = IndoorRideJournal(File(File(dir, "indoor"), IndoorRideJournal.FILE_NAME))
    private val header = IndoorRideJournal.Header(
        startedAtEpochMs = 1_765_000_000_000L,
        rideName = "Indoor · Teststrecke",
        mode = IndoorMode.SIM,
        resumeKey = "trailscape.indoor.resume.test",
        routeLengthM = 10_000.0,
        routeIsLoop = false,
    )

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun points(count: Int, from: Int = 0) = (from until from + count).map { i ->
        TrackPoint(
            lat = 50.0 + i * 1e-5,
            lon = 8.0 + i * 1e-5,
            ele = 100.0 + i * 0.1,
            time = header.startedAtEpochMs + i * 1_000L,
            hr = 130,
            power = 180 + i % 3,
            cad = 85,
        )
    }

    private fun state(routeDistM: Double, distanceM: Double, movingS: Int, ascentM: Double = 12.0) = IndoorSessionState(
        phase = IndoorPhase.RUNNING,
        mode = IndoorMode.SIM,
        nowMs = 0L,
        elapsedS = movingS,
        distM = distanceM,
        routeDistM = routeDistM,
        lap = 1,
        speedMps = 8.0,
        powerW = 180,
        cadenceRpm = 85,
        hrBpm = 130,
        gradePct = 0.0,
        surface = SurfaceKind.ASPHALT,
        crr = 0.004,
        headwindMps = 0.0,
        gear = GearState(12, 24, 2.0),
        ghost = null,
        erg = null,
        totals = IndoorTotals(distanceM, ascentM, movingS, 180.0, null, 85.0, 130.0, 20.0),
    )

    @Test
    fun `Kopf, Punkte und Stand kommen unveraendert zurueck`() {
        journal.begin(header)
        journal.append(points(40), state(1_200.0, 320.0, 40))
        journal.append(points(40, from = 40), state(4_800.0, 640.0, 80))

        val c = assertNotNull(journal.read())
        assertEquals(header, c.header)
        assertEquals(points(80), c.points)
        assertEquals(4_800.0, c.routeDistM)
        assertEquals(640.0, c.distanceM)
        assertEquals(80, c.movingS)
    }

    @Test
    fun `ein abgerissener letzter Satz faellt heraus, der Rest bleibt`() {
        journal.begin(header)
        journal.append(points(10), state(100.0, 80.0, 10))
        journal.file.appendText("P\t1765000011000\t50.0001")

        val c = assertNotNull(journal.read())
        assertEquals(10, c.points.size)
        assertEquals(10, c.movingS)
    }

    @Test
    fun `ohne lesbaren Kopf gibt es nichts`() {
        journal.file.parentFile.mkdirs()
        journal.file.writeText("Unsinn\nP\t1\t2\t3\n")
        assertNull(journal.read())
    }

    @Test
    fun `Wiederherstellung speichert die Tour, merkt die Stelle und loescht die Sicherung`() {
        val store = MemoryKeyValueStore()
        val saved = mutableListOf<Ride>()
        journal.begin(header)
        journal.append(points(90), state(3_250.0, 720.0, 90))

        val result = journal.recover(save = { saved += it }, store = store, nameOf = { "$it (wiederhergestellt)" }, nowEpochMs = 1_766_000_000_000L)

        val ok = assertIs<IndoorRideJournal.Recovery.Saved>(result)
        val ride = saved.single()
        assertEquals(ok.rideId, ride.id)
        assertEquals("Indoor · Teststrecke (wiederhergestellt)", ride.name)
        assertTrue(ride.indoor)
        assertFalse(ride.planned)
        assertEquals(header.startedAtEpochMs, ride.createdAt)
        assertEquals(90, ride.points.size)
        assertEquals(0.72, ride.stats.distanceKm, 1e-9)
        assertEquals(90, ride.stats.movingTimeS)
        assertEquals(12.0, ride.stats.ascentM, 1e-9)
        assertFalse(journal.exists(), "nach dem Speichern ist die Sicherung weg")
        val resume = assertNotNull(IndoorResumePoint.fromJson(store.getString(header.resumeKey!!)))
        assertEquals(3_250.0, resume.routeDistM, 1e-6)
    }

    @Test
    fun `zu kurz fuer eine Tour - verworfen, aber die Stelle bleibt gemerkt`() {
        val store = MemoryKeyValueStore()
        val saved = mutableListOf<Ride>()
        journal.begin(header)
        journal.append(points(30), state(2_000.0, 240.0, 30))

        val result = journal.recover(save = { saved += it }, store = store, nameOf = { it }, nowEpochMs = 1_766_000_000_000L)

        assertEquals(IndoorRideJournal.Recovery.TooShort, result)
        assertTrue(saved.isEmpty())
        assertFalse(journal.exists())
        assertNotNull(store.getString(header.resumeKey!!))
    }

    @Test
    fun `scheitert das Speichern, bleibt die Sicherung fuer den naechsten Start`() {
        journal.begin(header)
        journal.append(points(90), state(3_250.0, 720.0, 90))

        val result = journal.recover(save = { error("Datentraeger voll") }, store = null, nameOf = { it }, nowEpochMs = 0L)

        assertEquals(IndoorRideJournal.Recovery.Failed, result)
        assertTrue(journal.exists())
    }

    @Test
    fun `ohne Sicherung gibt es nichts wiederherzustellen`() {
        assertNull(journal.recover(save = { error("darf nicht") }, store = null, nameOf = { it }, nowEpochMs = 0L))
    }

    @Test
    fun `eine neue Fahrt legt eine liegengebliebene Sicherung beiseite statt sie zu ueberschreiben`() {
        journal.begin(header)
        journal.append(points(90), state(3_250.0, 720.0, 90))

        journal.begin(header.copy(startedAtEpochMs = header.startedAtEpochMs + 86_400_000L))

        val aside = IndoorRideJournal.orphanFiles(journal).single()
        assertEquals(90, assertNotNull(IndoorRideJournal(aside).read()).points.size)
        assertEquals(0, assertNotNull(journal.read()).points.size, "die neue Fahrt beginnt leer")
    }
}
