package de.trailscape.app.indoor

import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeDemoTrainer
import de.trailscape.app.indoor.testing.FakeIndoorSession
import de.trailscape.app.indoor.testing.FakeWorldBuilder
import de.trailscape.app.indoor.testing.FakeWorldRenderer
import de.trailscape.app.indoor.testing.testIndoorRoute
import de.trailscape.app.ui.indoor.IndoorRenderUi
import de.trailscape.core.TrackPoint
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.world.IndoorQuality
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Was die Laufzeit fuer die Fahrt im Hintergrund kann (docs/indoor/hintergrund.md):
 * laufende Sicherung der Punkte und Tausch der Darstellung, wenn die Oberflaeche
 * geht und wiederkommt.
 */
class IndoorRideRuntimeBackgroundTest {

    private val dir: File = createTempDirectory("indoor-runtime-bg").toFile()
    private val clock = FakeClock()
    private val route = testIndoorRoute()
    private val manifest = FakeWorldBuilder.manifestFor("welt", route, IndoorQuality.MEDIUM)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class RecordingSink : IndoorRideJournalSink {
        val batches = mutableListOf<List<TrackPoint>>()
        val states = mutableListOf<IndoorSessionState>()
        override fun append(points: List<TrackPoint>, state: IndoorSessionState) {
            batches += points
            states += state
        }
        val all: List<TrackPoint> get() = batches.flatten()
    }

    private fun session() = FakeIndoorSession(
        IndoorSessionConfig(
            route = route,
            mode = IndoorMode.SIM,
            rider = RiderSetup(riderMassKg = 75.0, bikeMassKg = 10.0, cdaM2 = 0.4, profileCrr = 0.008, ftpW = 200.0),
            startedAtEpochMs = 1_760_000_000_000L,
        ),
    )

    private fun IndoorRideRuntime.ticks(count: Int) = repeat(count) {
        clock.advance(IndoorRideRuntime.TICK_MS)
        tickOnce()
    }

    @Test
    fun `die Sicherung bekommt alle 5 s die neuen Punkte, bei Pause sofort, am Ende den Rest`() {
        val sink = RecordingSink()
        val session = session()
        val rt = IndoorRideRuntime(
            session = session,
            trainer = FakeDemoTrainer(),
            shifter = null,
            renderer = null,
            dispatcher = Dispatchers.Unconfined,
            clock = clock,
            autoLoop = false,
            journal = sink,
        )
        rt.start()
        rt.ticks(10) // erster Takt (READY) und Phasenwechsel zu RUNNING
        val afterStart = sink.batches.size
        rt.ticks(30) // 3 s: noch keine weitere Sicherung
        assertEquals(afterStart, sink.batches.size, "zwischen zwei Sicherungen nichts")
        rt.ticks(30) // jetzt sind 5 s seit der letzten vorbei
        assertTrue(sink.batches.size > afterStart, "nach 5 s wird gesichert")

        val before = sink.batches.size
        rt.pause()
        rt.ticks(1)
        assertEquals(before + 1, sink.batches.size, "die Pause wird sofort gesichert")
        assertEquals(IndoorPhase.PAUSED, sink.states.last().phase)

        rt.resume()
        rt.ticks(25)
        rt.requestJournal()
        val requested = sink.batches.size
        rt.ticks(1)
        assertEquals(requested + 1, sink.batches.size, "auf Wunsch (App geht) im naechsten Takt")

        val record = assertNotNull(runBlocking { rt.finish() })
        assertEquals(record.points, sink.all, "am Ende liegt die ganze Aufzeichnung in der Sicherung, ohne Dopplung")
    }

    @Test
    fun `die Darstellung laesst sich abgeben und neu anhaengen - die Fahrt laeuft dazwischen weiter`() {
        val first = FakeWorldRenderer()
        val rt = IndoorRideRuntime(
            session = session(),
            trainer = FakeDemoTrainer(),
            shifter = null,
            renderer = first,
            dispatcher = Dispatchers.Unconfined,
            clock = clock,
            autoLoop = false,
        )
        rt.start()
        first.setListener(rt.rendererListener)
        first.loadWorld(dir, manifest)
        rt.ticks(20)
        val seen = first.states.size
        assertTrue(seen > 0)

        // Die Oberflaeche geht: keine Aufrufe mehr an die alte Darstellung.
        assertSame(first, rt.replaceRenderer(null, rt.state.value.render))
        rt.ticks(50)
        assertEquals(seen, first.states.size, "die abgegebene Darstellung wird nicht mehr gerufen")
        val distInBackground = assertNotNull(rt.state.value.session).distM

        // Sie kommt wieder: die neue bekommt sofort die aktuelle Stelle.
        val second = FakeWorldRenderer()
        rt.replaceRenderer(second, IndoorRenderUi.Loading(0, 0))
        second.setListener(rt.rendererListener)
        second.loadWorld(dir, manifest)
        assertEquals(IndoorRenderUi.Ready, rt.state.value.render)
        rt.ticks(1)
        assertTrue(second.states.single().rider.distM >= distInBackground, "weiter an der richtigen Stelle")
        runBlocking { rt.finish() }
    }
}
