package de.trailscape.app.indoor.background

import de.trailscape.app.indoor.IndoorRideRuntime
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeDemoTrainer
import de.trailscape.app.indoor.testing.FakeIndoorSession
import de.trailscape.app.indoor.testing.testIndoorRoute
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.core.Ride
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.RiderSetup
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** „Beenden" aus der Benachrichtigung — mit und ohne Oberflaeche (docs/indoor/hintergrund.md). */
class IndoorRideHostTest {

    private val dir: File = createTempDirectory("indoor-host").toFile()
    private val clock = FakeClock()
    private val route = testIndoorRoute()
    private val store = MemoryKeyValueStore()
    private val saved = mutableListOf<Ride>()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun runtime(journal: IndoorRideJournal?) = IndoorRideRuntime(
        session = FakeIndoorSession(
            IndoorSessionConfig(
                route = route,
                mode = IndoorMode.SIM,
                rider = RiderSetup(riderMassKg = 75.0, bikeMassKg = 10.0, cdaM2 = 0.4, profileCrr = 0.008, ftpW = 200.0),
                startedAtEpochMs = 1_760_000_000_000L,
            ),
        ),
        trainer = FakeDemoTrainer(),
        shifter = null,
        renderer = null,
        dispatcher = Dispatchers.Unconfined,
        clock = clock,
        autoLoop = false,
        journal = journal,
    )

    private fun ride(runtime: IndoorRideRuntime, journal: IndoorRideJournal?) = IndoorRideHost.Ride(
        runtime = runtime,
        routeName = "Teststrecke",
        journal = journal,
        closer = IndoorRideCloser(
            save = { saved += it },
            store = store,
            nameOf = { km -> if (km == null) "Indoor · Teststrecke" else "Indoor · Teststrecke (ab km $km)" },
            resumeKey = "resume.test",
            routeLengthM = route.lengthM,
            routeIsLoop = route.isLoop,
            wallClock = { 1_766_000_000_000L },
            minShownStartM = 50.0,
        ),
    )

    private fun IndoorRideRuntime.rideFor(seconds: Int) = repeat(seconds * 10) {
        clock.advance(IndoorRideRuntime.TICK_MS)
        tickOnce()
    }

    private fun journal() = IndoorRideJournal(File(dir, IndoorRideJournal.FILE_NAME)).also {
        it.begin(IndoorRideJournal.Header(1_760_000_000_000L, "Indoor · Teststrecke", IndoorMode.SIM, "resume.test", route.lengthM, route.isLoop))
    }

    @Test
    fun `ohne Oberflaeche wird die Fahrt abgeschlossen, gespeichert und die Sicherung geloescht`() {
        val host = IndoorRideHost()
        val journal = journal()
        val rt = runtime(journal)
        rt.start()
        val r = ride(rt, journal)
        host.begin(r)
        assertTrue(host.isRiding)
        rt.rideFor(90)

        val outcome = runBlocking { host.finishFromNotification() }

        val ok = assertIs<IndoorRideHost.Outcome.Saved>(outcome)
        assertEquals(ok.rideId, saved.single().id)
        assertEquals("Indoor · Teststrecke", saved.single().name)
        assertTrue(saved.single().indoor)
        assertFalse(host.isRiding, "danach laeuft keine Fahrt mehr — der Dienst beendet sich")
        assertTrue(rt.state.value.finished)
        assertFalse(journal.exists())
        assertEquals(listOf(ok.rideId), host.savedRides.replayCache)
        assertTrue(store.getString("resume.test") != null, "die Stelle ist gemerkt")
    }

    @Test
    fun `mit Oberflaeche beendet sie die Fahrt und zeigt die Auswertung`() {
        val host = IndoorRideHost()
        val rt = runtime(null)
        rt.start()
        host.begin(ride(rt, null))
        var asked = 0
        val finish = { asked++; Unit }
        host.attachUi(finish)

        assertEquals(IndoorRideHost.Outcome.ShownInApp, runBlocking { host.finishFromNotification() })
        assertEquals(1, asked)
        assertTrue(saved.isEmpty(), "gespeichert wird in der Auswertung")

        host.detachUi(finish)
        rt.rideFor(5)
        assertEquals(IndoorRideHost.Outcome.TooShort, runBlocking { host.finishFromNotification() })
        assertNull(host.ride.value)
        assertNull(runBlocking { host.finishFromNotification() }, "ohne Fahrt nichts zu tun")
    }
}
