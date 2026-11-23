package de.trailscape.core.indoor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Demo-Trainer: Zeitverhalten, SIM/ERG, Pause, Kadenz 0, Reentranz, grobe Nebenlaeufigkeit. */
class DemoTrainerTest {

    private class Recorder : TrainerListener {
        val connections = mutableListOf<Pair<TrainerConnection, TrainerCapabilities>>()
        val telemetry = mutableListOf<TrainerTelemetry>()
        val failures = mutableListOf<Pair<TrainerCommand, String>>()

        @Synchronized
        override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
            connections += state to capabilities
        }

        @Synchronized
        override fun onTelemetry(telemetry: TrainerTelemetry) {
            this.telemetry += telemetry
        }

        @Synchronized
        override fun onCommandFailed(command: TrainerCommand, reason: String) {
            failures += command to reason
        }

        val states get() = connections.map { it.first }
    }

    /** Startet einen Trainer und treibt ihn bis READY; liefert die Zeit des READY-Ticks. */
    private fun ready(t: DemoTrainer, r: Recorder, t0: Long = 10_000L): Long {
        t.start(r)
        t.tick(t0)
        t.tick(t0 + 1000)
        assertEquals(TrainerConnection.READY, r.states.last())
        return t0 + 1000
    }

    /** Tickt [seconds] Sekunden im Sekundentakt ab [from] und liefert die Leistungen. */
    private fun run(t: DemoTrainer, r: Recorder, from: Long, seconds: Int): List<Int> {
        val before = r.telemetry.size
        for (i in 1..seconds) t.tick(from + i * 1000L)
        return r.telemetry.drop(before).map { it.powerW!! }
    }

    // ------------------------------------------------------------ Zeitverhalten

    @Test
    fun `Name und Fabrik`() {
        val t = demoTrainer()
        assertEquals("Demo-Trainer", t.name)
        assertTrue(t is DemoTrainerImpl)
    }

    @Test
    fun `start meldet sofort CONNECTING`() {
        val r = Recorder()
        demoTrainer().start(r)
        assertEquals(listOf(TrainerConnection.CONNECTING), r.states)
        assertEquals(TrainerCapabilities.NONE, r.connections[0].second)
        assertTrue(r.telemetry.isEmpty())
    }

    @Test
    fun `READY erst eine Sekunde nach dem ersten tick`() {
        val t = demoTrainer()
        val r = Recorder()
        t.start(r)
        t.tick(5_000)
        t.tick(5_500)
        t.tick(5_999)
        assertEquals(listOf(TrainerConnection.CONNECTING), r.states)
        assertTrue(r.telemetry.isEmpty())
        t.tick(6_000)
        assertEquals(listOf(TrainerConnection.CONNECTING, TrainerConnection.READY), r.states)
    }

    @Test
    fun `Startzeit ist der erste tick, nicht der Aufruf von start`() {
        val t = demoTrainer()
        val r = Recorder()
        t.start(r)
        // Beliebig grosse Uhr beim ersten tick: noch kein READY.
        t.tick(1_000_000)
        assertEquals(1, r.connections.size)
    }

    @Test
    fun `READY meldet die Faehigkeiten`() {
        val r = Recorder()
        ready(demoTrainer(), r)
        val caps = r.connections.last().second
        assertTrue(caps.simulation)
        assertTrue(caps.targetPower)
        assertEquals(0, caps.minPowerW)
        assertEquals(1500, caps.maxPowerW)
        assertTrue(caps.reportsCadence)
    }

    @Test
    fun `READY wird nur einmal gemeldet`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        run(t, r, now, 5)
        assertEquals(1, r.states.count { it == TrainerConnection.READY })
    }

    @Test
    fun `Messwerte nur aus tick und im Sekundentakt`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        assertEquals(1, r.telemetry.size) // erster Messwert beim READY-Tick
        t.riderPowerW = 200 // setzen allein erzeugt nichts
        assertEquals(1, r.telemetry.size)
        t.tick(now + 100)
        t.tick(now + 999)
        assertEquals(1, r.telemetry.size)
        t.tick(now + 1000)
        assertEquals(2, r.telemetry.size)
        t.tick(now + 1500)
        assertEquals(2, r.telemetry.size)
        t.tick(now + 2000)
        assertEquals(3, r.telemetry.size)
        assertEquals(now + 2000, r.telemetry.last().receivedMs)
    }

    @Test
    fun `Messwert nach laengerer Luecke genau einer je tick`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.tick(now + 10_000)
        assertEquals(2, r.telemetry.size)
        t.tick(now + 10_500)
        assertEquals(2, r.telemetry.size)
        t.tick(now + 11_000)
        assertEquals(3, r.telemetry.size)
    }

    @Test
    fun `Uhr die rueckwaerts laeuft erzeugt nichts`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.tick(now - 500)
        t.tick(now)
        assertEquals(1, r.telemetry.size)
    }

    // ------------------------------------------------------------ SIM

    @Test
    fun `ohne Modusbefehl gilt SIM mit riderPowerW`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 180
        t.riderCadenceRpm = 90
        val now = ready(t, r)
        val m = r.telemetry.last()
        assertEquals(180, m.powerW)
        assertEquals(90.0, m.cadenceRpm)
    }

    @Test
    fun `SIM folgt riderPowerW sofort beim naechsten Messwert`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.send(TrainerCommand.SetSimulation(0.0, 3.0, 0.004, 0.2))
        t.riderPowerW = 250
        t.tick(now + 1000)
        assertEquals(250, r.telemetry.last().powerW)
        t.riderPowerW = 120
        t.tick(now + 2000)
        assertEquals(120, r.telemetry.last().powerW)
    }

    @Test
    fun `Geschwindigkeit ist grobe Schwungradschaetzung aus der Kadenz`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderCadenceRpm = 90
        ready(t, r)
        val expected = 90 * 40.0 / 14.0 * 2.1 * 60.0 / 1000.0
        assertEquals(expected, r.telemetry.last().speedKmh!!, 1e-9)
        assertTrue(expected in 30.0..35.0)
    }

    @Test
    fun `negative Werte werden auf 0 geklemmt`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = -50
        t.riderCadenceRpm = -5
        ready(t, r)
        val m = r.telemetry.last()
        assertEquals(0, m.powerW)
        assertEquals(0.0, m.cadenceRpm)
        assertEquals(0.0, m.speedKmh)
    }

    // ------------------------------------------------------------ ERG

    @Test
    fun `ERG laeuft dem Ziel nach und konvergiert innerhalb 5 Prozent in 10 s`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 50
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(200))
        val p = run(t, r, now, 10)
        assertTrue(abs(p.last() - 200) <= 10, "Leistung nach 10 s: ${p.last()}")
        // Monoton steigend, nie ueber dem Ziel.
        p.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertTrue(p.all { it <= 200 })
        // Nicht schlagartig: der erste Schritt liegt klar zwischen Start und Ziel.
        assertTrue(p.first() in 51..199, "erster Schritt: ${p.first()}")
    }

    @Test
    fun `ERG Zeitkonstante etwa 2 s`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 0
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(200))
        val p = run(t, r, now, 3)
        // Je Sekunde ~40 % der Differenz: 80, 128, 157 aus 0 W.
        assertEquals(listOf(80, 128, 157), p)
    }

    @Test
    fun `ERG faellt auch zum niedrigeren Ziel zurueck`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(300))
        run(t, r, now, 15)
        t.send(TrainerCommand.SetTargetPower(100))
        val p = run(t, r, now + 15_000, 10)
        assertTrue(abs(p.last() - 100) <= 5)
        p.zipWithNext().forEach { (a, b) -> assertTrue(b <= a) }
    }

    @Test
    fun `ERG Ziel wird auf 0 bis 1500 W geklemmt`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(99_999))
        val p = run(t, r, now, 40)
        assertEquals(1500, p.last())
        assertTrue(p.all { it <= 1500 })
        t.send(TrainerCommand.SetTargetPower(-40))
        val q = run(t, r, now + 40_000, 40)
        assertEquals(0, q.last())
        assertTrue(q.all { it >= 0 })
    }

    @Test
    fun `ERG Ziel 0 ergibt 0 W`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(0))
        val p = run(t, r, now, 30)
        assertEquals(0, p.last())
    }

    @Test
    fun `ERG ignoriert riderPowerW`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 400
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(150))
        run(t, r, now, 1) // Nachlauf beginnt bei der Leistung der SIM
        t.riderPowerW = 0
        val p = run(t, r, now + 1000, 15)
        assertTrue(abs(p.last() - 150) <= 8)
    }

    @Test
    fun `Kadenz 0 im ERG ergibt 0 W und der Nachlauf beginnt danach neu`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(200))
        run(t, r, now, 12)
        t.riderCadenceRpm = 0
        val stand = run(t, r, now + 12_000, 3)
        assertEquals(listOf(0, 0, 0), stand)
        assertEquals(0.0, r.telemetry.last().speedKmh)
        t.riderCadenceRpm = 85
        val p = run(t, r, now + 15_000, 1)
        assertEquals(80, p.single()) // 40 % von 200 aus 0 W, nicht mehr der alte Wert
    }

    @Test
    fun `Kadenz 0 im SIM laesst riderPowerW unberuehrt`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 150
        t.riderCadenceRpm = 0
        ready(t, r)
        assertEquals(150, r.telemetry.last().powerW)
    }

    // ------------------------------------------------------------ Moduswechsel

    @Test
    fun `Wechsel SIM nach ERG startet beim bisherigen Wert`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 100
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(200))
        val p = run(t, r, now, 1)
        assertEquals(140, p.single()) // 100 + 40 % von 100
    }

    @Test
    fun `Wechsel ERG nach SIM springt auf riderPowerW`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 130
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(300))
        run(t, r, now, 3)
        t.send(TrainerCommand.SetSimulation(0.0, 0.0, 0.004, 0.2))
        val p = run(t, r, now + 3000, 1)
        assertEquals(130, p.single())
    }

    @Test
    fun `erneutes ERG-Ziel waehrend des Nachlaufs setzt nicht zurueck`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 0
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(200))
        val a = run(t, r, now, 2) // 80, 128
        t.send(TrainerCommand.SetTargetPower(100))
        val b = run(t, r, now + 2000, 1)
        assertEquals(listOf(80, 128), a)
        assertEquals(117, b.single()) // 128 + 40 % von (100 - 128), kein Neuanlauf aus 0 W
    }

    // ------------------------------------------------------------ Pause / Stop

    @Test
    fun `Pause gibt 0 W bis Start`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 200
        val now = ready(t, r)
        t.send(TrainerCommand.Pause)
        assertEquals(listOf(0, 0, 0), run(t, r, now, 3))
        t.send(TrainerCommand.Start)
        assertEquals(200, run(t, r, now + 3000, 1).single())
    }

    @Test
    fun `Stop wirkt wie Pause`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 200
        val now = ready(t, r)
        t.send(TrainerCommand.Stop)
        assertEquals(listOf(0, 0), run(t, r, now, 2))
        t.send(TrainerCommand.Start)
        assertEquals(200, run(t, r, now + 2000, 1).single())
    }

    @Test
    fun `Pause im ERG setzt den Nachlauf auf 0`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        t.send(TrainerCommand.SetTargetPower(200))
        run(t, r, now, 12)
        t.send(TrainerCommand.Pause)
        assertEquals(0, run(t, r, now + 12_000, 1).single())
        t.send(TrainerCommand.Start)
        // Ziel gilt weiter, der Anlauf beginnt bei 0.
        assertEquals(listOf(80, 128), run(t, r, now + 13_000, 2))
    }

    @Test
    fun `Pause erzeugt weiter Messwerte mit Kadenz`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderCadenceRpm = 70
        val now = ready(t, r)
        t.send(TrainerCommand.Pause)
        t.tick(now + 1000)
        assertEquals(0, r.telemetry.last().powerW)
        assertEquals(70.0, r.telemetry.last().cadenceRpm)
    }

    @Test
    fun `Start ohne vorherige Pause aendert nichts`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 160
        val now = ready(t, r)
        t.send(TrainerCommand.Start)
        assertEquals(160, run(t, r, now, 1).single())
    }

    // ------------------------------------------------------------ send vor READY

    @Test
    fun `send vor READY merkt still und meldet keinen Fehler`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 0
        t.start(r)
        t.send(TrainerCommand.SetTargetPower(100))
        t.send(TrainerCommand.SetTargetPower(200)) // der letzte gilt
        t.send(TrainerCommand.Start)
        t.tick(0)
        t.send(TrainerCommand.SetSimulation(0.0, 5.0, 0.004, 0.2))
        t.send(TrainerCommand.SetTargetPower(200))
        t.tick(1000)
        assertEquals(TrainerConnection.READY, r.states.last())
        assertEquals(80, r.telemetry.last().powerW) // ERG 200 W, erster Schritt aus 0 W
        assertTrue(r.failures.isEmpty())
    }

    @Test
    fun `Pause vor READY wirkt ab dem ersten Messwert`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 200
        t.start(r)
        t.send(TrainerCommand.Pause)
        t.tick(0)
        t.tick(1000)
        assertEquals(0, r.telemetry.single().powerW)
    }

    @Test
    fun `Reihenfolge Pause dann Start vor READY ergibt Betrieb`() {
        val t = demoTrainer()
        val r = Recorder()
        t.riderPowerW = 200
        t.start(r)
        t.send(TrainerCommand.Pause)
        t.send(TrainerCommand.Start)
        t.tick(0)
        t.tick(1000)
        assertEquals(200, r.telemetry.single().powerW)
    }

    @Test
    fun `send ohne start wird ignoriert`() {
        val t = demoTrainer()
        t.send(TrainerCommand.SetTargetPower(500))
        t.send(TrainerCommand.Pause)
        val r = Recorder()
        t.riderPowerW = 150
        ready(t, r)
        assertEquals(150, r.telemetry.last().powerW)
    }

    @Test
    fun `Befehle melden nie einen Fehler`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        listOf(
            TrainerCommand.Start, TrainerCommand.Pause, TrainerCommand.Stop,
            TrainerCommand.SetTargetPower(-5), TrainerCommand.SetTargetPower(9999),
            TrainerCommand.SetSimulation(100.0, -50.0, 5.0, 5.0),
        ).forEach { t.send(it) }
        run(t, r, now, 3)
        assertTrue(r.failures.isEmpty())
    }

    // ------------------------------------------------------------ stop

    @Test
    fun `stop meldet einmal OFF und vergisst den Listener`() {
        val t = demoTrainer()
        val r = Recorder()
        val now = ready(t, r)
        val n = r.telemetry.size
        t.stop()
        assertEquals(TrainerConnection.OFF, r.states.last())
        assertEquals(TrainerCapabilities.NONE, r.connections.last().second)
        t.tick(now + 1000)
        t.tick(now + 5000)
        t.send(TrainerCommand.Start)
        assertEquals(n, r.telemetry.size)
        assertEquals(1, r.states.count { it == TrainerConnection.OFF })
    }

    @Test
    fun `stop ist idempotent`() {
        val t = demoTrainer()
        val r = Recorder()
        ready(t, r)
        t.stop()
        t.stop()
        t.stop()
        assertEquals(1, r.states.count { it == TrainerConnection.OFF })
    }

    @Test
    fun `stop ohne start und vor READY`() {
        demoTrainer().stop()
        val t = demoTrainer()
        val r = Recorder()
        t.start(r)
        t.tick(0)
        t.stop()
        t.tick(5000)
        assertEquals(listOf(TrainerConnection.CONNECTING, TrainerConnection.OFF), r.states)
    }

    @Test
    fun `nach stop kann neu gestartet werden`() {
        val t = demoTrainer()
        val r1 = Recorder()
        t.riderPowerW = 100
        val now = ready(t, r1)
        t.send(TrainerCommand.SetTargetPower(300))
        t.send(TrainerCommand.Pause)
        t.stop()
        val r2 = Recorder()
        t.start(r2)
        t.tick(now + 100)
        t.tick(now + 1100)
        assertEquals(listOf(TrainerConnection.CONNECTING, TrainerConnection.READY), r2.states)
        assertEquals(100, r2.telemetry.single().powerW) // SIM, nicht pausiert: Zustand zurueckgesetzt
    }

    // ------------------------------------------------------------ Reentranz

    @Test
    fun `Listener ruft send aus onConnection`() {
        val t = demoTrainer()
        val got = mutableListOf<TrainerTelemetry>()
        t.riderPowerW = 0
        val l = object : TrainerListener {
            override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
                if (state == TrainerConnection.READY) t.send(TrainerCommand.SetTargetPower(100))
            }

            override fun onTelemetry(telemetry: TrainerTelemetry) {
                got += telemetry
            }

            override fun onCommandFailed(command: TrainerCommand, reason: String) = Unit
        }
        t.start(l)
        t.tick(0)
        t.tick(1000) // READY: erster Messwert noch vor dem ERG-Befehl (SIM, 0 W)
        t.tick(2000)
        assertEquals(0, got[0].powerW)
        assertEquals(40, got[1].powerW) // ERG 100 W, 40 % aus 0 W
    }

    @Test
    fun `Listener ruft send aus onTelemetry`() {
        val t = demoTrainer()
        val powers = mutableListOf<Int>()
        t.riderPowerW = 0
        val l = object : TrainerListener {
            override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) = Unit

            override fun onTelemetry(telemetry: TrainerTelemetry) {
                powers += telemetry.powerW!!
                t.send(TrainerCommand.SetTargetPower(100))
            }

            override fun onCommandFailed(command: TrainerCommand, reason: String) = Unit
        }
        t.start(l)
        t.tick(0)
        t.tick(1000)
        t.tick(2000)
        t.tick(3000)
        assertEquals(listOf(0, 40, 64), powers)
    }

    @Test
    fun `Listener ruft stop aus onTelemetry und start aus onConnection`() {
        val t = demoTrainer()
        val states = mutableListOf<TrainerConnection>()
        val count = AtomicInteger()
        val l = object : TrainerListener {
            override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
                states += state
            }

            override fun onTelemetry(telemetry: TrainerTelemetry) {
                count.incrementAndGet()
                t.stop()
            }

            override fun onCommandFailed(command: TrainerCommand, reason: String) = Unit
        }
        t.start(l)
        t.tick(0)
        t.tick(1000)
        t.tick(2000)
        assertEquals(1, count.get())
        assertEquals(listOf(TrainerConnection.CONNECTING, TrainerConnection.READY, TrainerConnection.OFF), states)
    }

    // ------------------------------------------------------------ Nebenlaeufigkeit

    @Test
    fun `zwei Threads ohne Deadlock und Ausnahme`() {
        val t = demoTrainer()
        val errors = AtomicReference<Throwable?>(null)
        val received = AtomicInteger()
        val l = object : TrainerListener {
            override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
                if (state == TrainerConnection.READY) t.send(TrainerCommand.SetTargetPower(150))
            }

            override fun onTelemetry(telemetry: TrainerTelemetry) {
                received.incrementAndGet()
                t.send(TrainerCommand.Start)
            }

            override fun onCommandFailed(command: TrainerCommand, reason: String) = Unit
        }
        t.start(l)
        val done = CountDownLatch(2)
        val ticker = Thread {
            try {
                var now = 0L
                repeat(5000) {
                    now += 1000
                    t.tick(now)
                }
            } catch (e: Throwable) {
                errors.set(e)
            } finally {
                done.countDown()
            }
        }
        val sender = Thread {
            try {
                repeat(20_000) { i ->
                    when (i % 5) {
                        0 -> t.send(TrainerCommand.SetTargetPower(i % 400))
                        1 -> t.send(TrainerCommand.SetSimulation(0.0, 2.0, 0.004, 0.2))
                        2 -> t.send(TrainerCommand.Pause)
                        3 -> t.send(TrainerCommand.Start)
                        else -> {
                            t.riderPowerW = i % 300
                            t.riderCadenceRpm = i % 100
                        }
                    }
                }
            } catch (e: Throwable) {
                errors.set(e)
            } finally {
                done.countDown()
            }
        }
        ticker.start()
        sender.start()
        assertTrue(done.await(30, TimeUnit.SECONDS), "Deadlock?")
        assertNull(errors.get())
        assertEquals(4999, received.get()) // erster tick merkt nur die Startzeit
    }

    @Test
    fun `stop aus fremdem Thread waehrend tick`() {
        repeat(50) {
            val t = demoTrainer()
            val r = Recorder()
            t.start(r)
            val errors = AtomicReference<Throwable?>(null)
            val done = CountDownLatch(2)
            val a = Thread {
                try {
                    var now = 0L
                    repeat(2000) { now += 1000; t.tick(now) }
                } catch (e: Throwable) {
                    errors.set(e)
                } finally {
                    done.countDown()
                }
            }
            val b = Thread {
                try {
                    Thread.yield()
                    t.stop()
                } catch (e: Throwable) {
                    errors.set(e)
                } finally {
                    done.countDown()
                }
            }
            a.start(); b.start()
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertNull(errors.get())
            assertEquals(1, r.states.count { it == TrainerConnection.OFF })
        }
    }
}
