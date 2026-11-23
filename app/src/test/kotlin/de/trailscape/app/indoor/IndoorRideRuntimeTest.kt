package de.trailscape.app.indoor

import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeDemoTrainer
import de.trailscape.app.indoor.testing.FakeHeartRate
import de.trailscape.app.indoor.testing.FakeIndoorSession
import de.trailscape.app.indoor.testing.FakeWorldBuilder
import de.trailscape.app.indoor.testing.FakeWorldRenderer
import de.trailscape.app.indoor.testing.testIndoorRoute
import de.trailscape.app.ui.indoor.IndoorNotice
import de.trailscape.app.ui.indoor.IndoorRenderUi
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.TrainerCommand
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.TrainerListener
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Die Laufzeit einer Indoor-Fahrt mit Attrappen fuer Trainer, Sitzung und
 * Darstellung (docs/indoor.md, Abschnitte 5.7 und 10.6). Ohne Schleife
 * (`autoLoop = false`): Der Test treibt die Takte selbst mit einer Fake-Uhr.
 */
class IndoorRideRuntimeTest {

    private val dir: File = createTempDirectory("indoor-runtime").toFile()
    private val clock = FakeClock()
    private val route = testIndoorRoute()
    private val manifest = FakeWorldBuilder.manifestFor("welt", route, IndoorQuality.MEDIUM)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun session(mode: IndoorMode = IndoorMode.SIM) = FakeIndoorSession(
        IndoorSessionConfig(
            route = route,
            mode = mode,
            rider = RiderSetup(riderMassKg = 75.0, bikeMassKg = 10.0, cdaM2 = 0.4, profileCrr = 0.008, ftpW = 200.0),
            startedAtEpochMs = 1_760_000_000_000L,
        ),
    )

    private fun runtime(
        session: FakeIndoorSession = session(),
        trainer: IndoorTrainer = FakeDemoTrainer(),
        renderer: FakeWorldRenderer? = null,
        heartRate: FakeHeartRate? = null,
    ) = IndoorRideRuntime(
        session = session,
        trainer = trainer,
        shifter = null,
        renderer = renderer,
        heartRate = heartRate,
        dispatcher = Dispatchers.Unconfined,
        clock = clock,
        autoLoop = false,
    )

    private fun IndoorRideRuntime.ticks(count: Int) = repeat(count) {
        clock.advance(IndoorRideRuntime.TICK_MS)
        tickOnce()
    }

    @Test
    fun `jeder Takt treibt Trainer und Sitzung und reicht Befehle und Zustand weiter`() {
        val trainer = FakeDemoTrainer()
        val session = session()
        val renderer = FakeWorldRenderer()
        val rt = runtime(session, trainer, renderer)
        rt.start()
        renderer.setListener(rt.rendererListener)
        renderer.loadWorld(dir, manifest)

        rt.ticks(50)

        assertEquals(50, trainer.ticks)
        assertEquals(50, session.stepCount)
        assertTrue(TrainerCommand.Start in trainer.commands, "Start beim ersten Tritt")
        assertTrue(trainer.commands.any { it is TrainerCommand.SetSimulation }, "SIM-Vorgaben an den Trainer")
        val state = assertNotNull(rt.state.value.session)
        assertEquals(IndoorPhase.RUNNING, state.phase)
        assertTrue(state.distM > 0.0)
        assertEquals(TrainerConnection.READY, rt.state.value.trainer)
        assertEquals(IndoorRenderUi.Ready, rt.state.value.render)
        assertTrue(renderer.states.isNotEmpty())
        assertEquals(state.distM, renderer.states.last().rider.distM, 1e-9)
        assertTrue(renderer.states.last().timeMs > renderer.states.first().timeMs)
    }

    @Test
    fun `Darstellung meldet onFailure - Meldung statt Bild, die Fahrt laeuft weiter`() {
        val renderer = FakeWorldRenderer(failure = RenderFailure.NO_GRAPHICS)
        val session = session()
        val rt = runtime(session, renderer = renderer)
        rt.start()
        renderer.setListener(rt.rendererListener)
        renderer.loadWorld(dir, manifest)

        val failed = assertIs<IndoorRenderUi.Failed>(rt.state.value.render)
        assertEquals(RenderFailure.NO_GRAPHICS, failed.failure)
        assertEquals(IndoorPart.RENDER, failed.problem?.part)

        rt.ticks(30)
        val before = assertNotNull(rt.state.value.session).distM
        rt.ticks(30)
        assertTrue(assertNotNull(rt.state.value.session).distM > before, "Fahrt laeuft ohne Bild weiter")
        assertTrue(renderer.states.isEmpty(), "eine gescheiterte Darstellung wird nicht mehr gerufen")
        assertIs<IndoorRenderUi.Failed>(rt.state.value.render)
    }

    @Test
    fun `wirft setState der Darstellung, wird sie abgeschaltet und die Fahrt laeuft weiter`() {
        val renderer = FakeWorldRenderer(throwOnSetState = true)
        val rt = runtime(renderer = renderer)
        rt.start()
        renderer.setListener(rt.rendererListener)
        renderer.loadWorld(dir, manifest)

        rt.ticks(40)

        assertIs<IndoorRenderUi.Failed>(rt.state.value.render)
        assertEquals(IndoorPart.RENDER, rt.state.value.problem?.part)
        assertTrue(assertNotNull(rt.state.value.session).distM > 0.0)
    }

    @Test
    fun `Trainer weg - Hinweis, Auto-Pause, danach wieder da`() {
        val trainer = FakeDemoTrainer()
        val session = session()
        val rt = runtime(session, trainer)
        rt.start()
        rt.ticks(30)
        assertEquals(IndoorPhase.RUNNING, assertNotNull(rt.state.value.session).phase)

        trainer.dropConnection()
        assertEquals(IndoorNotice.TRAINER_LOST, rt.state.value.notice)
        assertEquals(TrainerConnection.CONNECTING, rt.state.value.trainer)
        rt.ticks(3)
        assertEquals(IndoorPhase.AUTO_PAUSED, assertNotNull(rt.state.value.session).phase)

        // Die Attrappe verbindet nach einer Sekunde neu.
        rt.ticks(15)
        assertEquals(IndoorNotice.TRAINER_BACK, rt.state.value.notice)
        assertEquals(TrainerConnection.READY, rt.state.value.trainer)
        rt.ticks(20)
        assertEquals(IndoorPhase.RUNNING, assertNotNull(rt.state.value.session).phase)
    }

    @Test
    fun `Hinweise verschwinden von selbst und auf Tipp`() {
        val trainer = FakeDemoTrainer()
        val rt = runtime(trainer = trainer)
        rt.start()
        rt.ticks(20)
        trainer.listener!!.onCommandFailed(TrainerCommand.Start, "Attrappe")
        assertEquals(IndoorNotice.TRAINER_COMMAND_FAILED, rt.state.value.notice)
        rt.ticks((IndoorRideRuntime.NOTICE_DURATION_MS / IndoorRideRuntime.TICK_MS).toInt() + 1)
        assertNull(rt.state.value.notice)

        trainer.listener!!.onCommandFailed(TrainerCommand.Start, "Attrappe")
        rt.dismissNotice()
        assertNull(rt.state.value.notice)
    }

    @Test
    fun `eine Ausnahme der Sitzung faengt die Schleife ab`() {
        val session = session()
        val rt = runtime(session)
        rt.start()
        rt.ticks(20)
        session.throwOnNextStep = true

        rt.ticks(1)

        assertEquals(IndoorNotice.PART_FAILED, rt.state.value.notice)
        val problem = assertNotNull(rt.state.value.problem)
        assertEquals(IndoorPart.CORE, problem.part)
        val before = assertNotNull(rt.state.value.session).distM
        rt.ticks(20)
        assertTrue(assertNotNull(rt.state.value.session).distM > before, "Schleife laeuft nach dem Fehler weiter")
    }

    @Test
    fun `ein Platzhalter-Trainer wirft NotImplementedError - Meldung, kein Absturz`() {
        val placeholder = object : IndoorTrainer {
            override val name = "Platzhalter"

            override fun start(listener: TrainerListener) = throw NotImplementedError("Indoor: Platzhalter")

            override fun send(command: TrainerCommand) = throw NotImplementedError("Indoor: Platzhalter")

            override fun tick(nowMs: Long) = throw NotImplementedError("Indoor: Platzhalter")

            override fun stop() = throw NotImplementedError("Indoor: Platzhalter")
        }
        val session = session()
        val rt = runtime(session, placeholder)
        rt.start()
        rt.ticks(10)

        val problem = assertNotNull(rt.state.value.problem)
        assertTrue(problem.notImplemented)
        assertEquals(10, session.stepCount, "die Sitzung wird trotzdem jeden Takt gerufen")
        assertNotNull(runBlocking { rt.finish() })
    }

    @Test
    fun `Pause, Schalten, Demo-Regler und Puls wirken im naechsten Takt`() {
        val trainer = FakeDemoTrainer()
        val session = session()
        val hr = FakeHeartRate(bpm = 142)
        val rt = runtime(session, trainer, heartRate = hr)
        rt.start()
        assertEquals(1, hr.acquired)
        rt.ticks(20)

        rt.shift(ShifterEvent.UP)
        rt.shift(ShifterEvent.UP)
        rt.setDemoInput(250, 95)
        rt.ticks(1)
        assertEquals(listOf(ShifterEvent.UP, ShifterEvent.UP), session.shifts)
        assertEquals(250, trainer.riderPowerW)
        assertEquals(95, trainer.riderCadenceRpm)
        assertEquals(142, assertNotNull(rt.state.value.session).hrBpm)

        rt.pause()
        rt.ticks(1)
        assertEquals(IndoorPhase.PAUSED, assertNotNull(rt.state.value.session).phase)
        rt.resume()
        rt.ticks(1)
        assertEquals(IndoorPhase.RUNNING, assertNotNull(rt.state.value.session).phase)

        rt.onAppBackground()
        rt.ticks(1)
        assertEquals(IndoorPhase.PAUSED, assertNotNull(rt.state.value.session).phase)
        rt.onAppForeground()
        rt.ticks(1)
        assertEquals(IndoorPhase.RUNNING, assertNotNull(rt.state.value.session).phase)
    }

    @Test
    fun `finish liefert die Aufzeichnung, stoppt den Trainer und gibt den Puls frei`() {
        val trainer = FakeDemoTrainer()
        val session = session()
        val hr = FakeHeartRate()
        val rt = runtime(session, trainer, heartRate = hr)
        rt.start()
        rt.ticks(700)

        val record = assertNotNull(runBlocking { rt.finish() })

        assertTrue(record.points.size >= 60, "ein Punkt je gefahrener Sekunde")
        assertEquals(TrainerCommand.Stop, trainer.commands.last())
        assertTrue(trainer.stopped)
        assertEquals(1, hr.released)
        assertTrue(rt.state.value.finished)
        assertEquals(IndoorPhase.FINISHED, assertNotNull(rt.state.value.session).phase)

        // Nach dem Ende passiert nichts mehr, ein zweites finish liefert dasselbe.
        val steps = session.stepCount
        rt.ticks(5)
        assertEquals(steps, session.stepCount)
        assertEquals(record, runBlocking { rt.finish() })
        assertEquals(1, session.finishCalls)
    }

    @Test
    fun `gleiche Uhrzeit zweimal - die Sitzung bekommt nur streng steigende Zeit`() {
        val session = session()
        val rt = runtime(session)
        rt.start()
        rt.ticks(5)
        rt.tickOnce() // Uhr steht
        assertEquals(5, session.stepCount)
        assertNull(rt.state.value.problem)
    }
}
