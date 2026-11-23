package de.trailscape.core.indoor

import de.trailscape.core.RouteWaySection
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.TrainingSession
import de.trailscape.core.haversineM
import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ende-zu-Ende: Die Laufzeit aus docs/indoor.md Abschnitt 5.7 im Test
 * nachgebaut — 10-Hz-Schleife mit simulierter monotoner Uhr, Demo-Trainer
 * statt Beine, nur die oeffentlichen Einstiege aus `IndoorCoreEntry.kt`.
 */
class IndoorDemoRideTest {

    private val startedAt = 1_800_000_000_000L

    // ------------------------------------------------------------ Hilfsmittel

    /**
     * Die Laufzeit-Schleife (docs/indoor.md 5.7) mit eigener Uhr:
     * `trainer.tick(now); step = session.step(now, input); step.commands.forEach(trainer::send)`.
     */
    private class Rig(val session: IndoorSession, val trainer: DemoTrainer, var now: Long = 50_000L) {
        var connection = TrainerConnection.OFF
        var telemetry: TrainerTelemetry? = null
        var failure: String? = null

        /** Alle gesendeten Befehle mit der Uhrzeit des Schritts. */
        val sent = ArrayList<Pair<Long, TrainerCommand>>()

        init {
            trainer.start(object : TrainerListener {
                override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
                    connection = state
                }

                override fun onTelemetry(telemetry: TrainerTelemetry) {
                    this@Rig.telemetry = telemetry
                }

                override fun onCommandFailed(command: TrainerCommand, reason: String) {
                    failure = "$command: $reason"
                }
            })
        }

        /** Ein Schritt der Schleife (100 ms). */
        fun tick(hr: Int? = 140): IndoorStep {
            now += 100
            trainer.tick(now)
            val step = session.step(now, SessionInput(telemetry, hr, connection == TrainerConnection.READY))
            step.commands.forEach {
                sent += now to it
                trainer.send(it)
            }
            return step
        }

        fun run(seconds: Double): List<IndoorStep> = List(Math.round(seconds * 10).toInt()) { tick() }

        /** Schritte, bis [until] gilt (hoechstens [maxSeconds]); der letzte Schritt gehoert zur Liste. */
        fun runUntil(maxSeconds: Double, until: (IndoorStep) -> Boolean): List<IndoorStep> {
            val out = ArrayList<IndoorStep>()
            repeat(Math.round(maxSeconds * 10).toInt()) {
                val s = tick()
                out += s
                if (until(s)) return out
            }
            fail("Bedingung nach $maxSeconds s nicht erreicht, letzter Zustand: ${out.last().state}")
        }

        fun commandsOf(vararg kinds: Class<*>) = sent.filter { (_, c) -> kinds.any { it.isInstance(c) } }
    }

    /**
     * Eine geschlossene Runde von ca. [lengthM] Metern als Kreis mit einem
     * Huegel (Hoehe 100 + [hillM]·sin) — der letzte Punkt ist der erste.
     */
    private fun loopPoints(lengthM: Double = 3000.0, hillM: Double = 40.0, n: Int = 240): List<TrackPoint> {
        val lat0 = 50.0
        val lon0 = 7.0
        val mPerDegLat = Math.toRadians(1.0) * 6_371_000.0
        val r = lengthM / (2 * Math.PI)
        val pts = List(n) { i ->
            val phi = 2 * Math.PI * i / n // ab West, im Uhrzeigersinn
            val north = r * sin(phi)
            val east = r - r * cos(phi)
            TrackPoint(
                lat = lat0 + north / mPerDegLat,
                lon = lon0 + east / (mPerDegLat * cos(Math.toRadians(lat0))),
                ele = 100.0 + hillM * sin(phi),
            )
        }
        return pts + pts.first()
    }

    /** Ein gerader, leicht gewellter Weg ohne Rundschluss. */
    private fun openPoints(lengthM: Double, n: Int = 120): List<TrackPoint> {
        val mPerDegLat = Math.toRadians(1.0) * 6_371_000.0
        return List(n + 1) { i ->
            val d = lengthM * i / n
            TrackPoint(
                lat = 50.0 + d * cos(Math.toRadians(40.0)) / mPerDegLat,
                lon = 7.0 + d * sin(Math.toRadians(40.0)) / (mPerDegLat * cos(Math.toRadians(50.0))),
                ele = 200.0 + 8.0 * sin(d / lengthM * 2 * Math.PI),
            )
        }
    }

    /** Die Rundstrecke mit Belagswechsel Asphalt / Schotter / Asphalt aus BRouters Wegmerkmalen. */
    private fun buildLoopRoute(): IndoorRoute {
        val route = buildIndoorRoute(
            name = "Huegelrunde",
            points = loopPoints(),
            waySections = listOf(
                RouteWaySection(1200.0, "highway=residential surface=asphalt"),
                RouteWaySection(800.0, "highway=track surface=gravel tracktype=grade3"),
                RouteWaySection(1000.0, "highway=tertiary surface=asphalt"),
            ),
            source = IndoorRouteSource.BROUTER_WAY_TAGS,
        )
        return assertNotNull(route, "Runde muss fahrbar sein")
    }

    private val demoCapabilities = TrainerCapabilities(
        simulation = true,
        targetPower = true,
        minPowerW = 0,
        maxPowerW = 1500,
        reportsCadence = true,
    )

    private fun simConfig(route: IndoorRoute, ghost: GhostSpec = GhostSpec.None, rider: RiderSetup = defaultRider()) =
        IndoorSessionConfig(
            route = route,
            mode = IndoorMode.SIM,
            rider = rider,
            ghost = ghost,
            capabilities = demoCapabilities,
            startedAtEpochMs = startedAt,
        )

    private fun defaultRider(ftp: Double? = 250.0): RiderSetup =
        riderSetupFrom(TrainingProfile(ageYears = 35, weightKg = 72.0, eftpOverrideW = ftp))

    /** Wartet, bis der Demo-Trainer READY meldet (etwa eine Sekunde). */
    private fun Rig.awaitTrainerReady() {
        runUntil(3.0) { connection == TrainerConnection.READY }
    }

    private fun intervalSession(): TrainingSession = TrainingSession(
        day = "2026-11-24",
        title = "Schwellenintervalle",
        description = "10 min einfahren, 3 x 5 min, 3 min Pause, 5 min ausfahren",
        targetKm = 25,
        intensity = SessionIntensity.HART,
        durationMin = 36,
        textKey = SessionTextKey.INTERVALS,
        // [einfahren, wdh, belastung, pause, ausfahren, gesamt, haerte]
        textArgs = listOf(10, 3, 5, 3, 5, 36, 1),
    )

    // ------------------------------------------------------------- Einstiege

    @Test
    fun `Strecke und Fahrer aus den Einstiegen sind plausibel`() {
        val route = buildLoopRoute()
        assertTrue(route.isLoop)
        assertEquals(IndoorRouteSource.BROUTER_WAY_TAGS, route.source)
        assertTrue(route.lengthM in 2900.0..3100.0, "Laenge ${route.lengthM}")
        val kinds = route.surfaces.map { it.kind }.toSet()
        assertTrue(SurfaceKind.ASPHALT in kinds && SurfaceKind.GRAVEL in kinds, "Belaege $kinds")
        // Lueckenlos von 0 bis zur Laenge.
        assertEquals(0.0, route.surfaces.first().fromM)
        assertEquals(route.lengthM, route.surfaces.last().toM, 1e-6)
        val rider = defaultRider()
        assertEquals(72.0, rider.riderMassKg)
        assertEquals(250.0, rider.ftpW)
    }

    @Test
    fun `FTMS-Trainer und Zwift-Click entstehen ohne Fehler und tragen den Namen des Rohrs`() {
        class Rohr(override val deviceName: String?) : BleLink {
            var geoeffnet = false
            var geschlossen = false

            override fun open(listener: BleLinkListener) {
                geoeffnet = true
            }

            override fun subscribe(serviceUuid: String, characteristicUuid: String, indicate: Boolean) = Unit

            override fun read(serviceUuid: String, characteristicUuid: String) = Unit

            override fun write(serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) = Unit

            override fun requestMtu(mtu: Int) = Unit

            override fun close() {
                geschlossen = true
            }
        }

        val trainer = createFtmsTrainer(Rohr("KICKR CORE 1A2B"))
        assertEquals("KICKR CORE 1A2B", trainer.name)
        val shifter = createZwiftClickShifter(Rohr("Zwift Click"))
        assertEquals("Zwift Click", shifter.name)
        // Ohne Namen greifen die Ersatznamen.
        assertEquals("FTMS-Trainer", createFtmsTrainer(Rohr(null)).name)
        assertEquals("Schalttaster", createZwiftClickShifter(Rohr(null)).name)
        // Ein nie gestarteter Trainer darf aufgeraeumt werden (idempotent, wirft nicht).
        trainer.tick(1_000L)
        trainer.stop()
        trainer.stop()
        shifter.tick(1_000L)
        shifter.stop()
    }

    // ------------------------------------------------------------ 1. SIM-Fahrt

    @Test
    fun `SIM-Fahrt mit Demo-Trainer vom Start bis zur Aufzeichnung`() {
        val route = buildLoopRoute()
        val session = createIndoorSession(simConfig(route))
        val trainer = createDemoTrainer()
        trainer.riderPowerW = 200
        trainer.riderCadenceRpm = 85
        val rig = Rig(session, trainer)

        assertEquals(IndoorPhase.READY, session.state.phase)

        // --- Trainer wird READY, Sitzung RUNNING (der erste Tritt kommt mit dem ersten Messwert).
        val first = rig.tick()
        assertEquals(IndoorPhase.READY, first.state.phase)
        assertEquals(TrainerCommand.Start, first.commands.first())
        assertTrue(first.commands.drop(1).single() is TrainerCommand.SetSimulation)
        rig.awaitTrainerReady()
        assertEquals(TrainerConnection.READY, rig.connection)
        assertEquals(IndoorPhase.RUNNING, session.state.phase)
        // Messwerte sind angekommen (200 W, 85 rpm).
        assertEquals(200, rig.telemetry?.powerW)
        assertEquals(85.0, rig.telemetry?.cadenceRpm)

        // --- Schalten: hoch, runter, runter. Jedes Mal sofort eine neue Vorgabe.
        rig.run(60.0)
        fun shiftAndStep(e: ShifterEvent): Pair<Long, TrainerCommand.SetSimulation> {
            session.shift(e)
            val s = rig.tick()
            val cmd = s.commands.filterIsInstance<TrainerCommand.SetSimulation>().single()
            return rig.now to cmd
        }
        val gearBefore = session.state.gear.index
        val (tUp, up) = shiftAndStep(ShifterEvent.UP)
        assertEquals(gearBefore + 1, session.state.gear.index)
        rig.run(2.0)
        val (tDown1, down1) = shiftAndStep(ShifterEvent.DOWN)
        assertEquals(gearBefore, session.state.gear.index)
        rig.run(2.0)
        val (tDown2, down2) = shiftAndStep(ShifterEvent.DOWN)
        assertEquals(gearBefore - 1, session.state.gear.index)
        val shiftTimes = setOf(tUp, tDown1, tDown2)
        assertNotEquals(up.gradePct, down2.gradePct, "schwerer und leichter Gang muessen verschieden steil sein")
        assertNotEquals(up.gradePct, down1.gradePct)
        assertNotEquals(down1.gradePct, down2.gradePct)
        // Der Luftwiderstand waechst mit der Uebersetzung (cw · k^3) — eindeutig, unabhaengig vom Gefaelle.
        assertTrue(up.cwKgPerM > down1.cwKgPerM && down1.cwKgPerM > down2.cwKgPerM)
        // Zurueck in den Startgang fuer den Rest der Fahrt.
        rig.run(2.0)
        session.shift(ShifterEvent.UP)
        rig.tick()
        assertEquals(gearBefore, session.state.gear.index)

        // --- Rest der ersten 10 Minuten.
        rig.run(600.0 - 60.0 - 7.0)
        val at10min = session.state
        assertEquals(IndoorPhase.RUNNING, at10min.phase)
        assertEquals(200, at10min.powerW)
        assertEquals(85, at10min.cadenceRpm)
        assertEquals(140, at10min.hrBpm)
        assertTrue(at10min.totals.movingS in 595..605, "Fahrzeit ${at10min.totals.movingS}")
        // 200 W mit 72+12 kg: am 8-%-Hang etwa 3 m/s, bergab deutlich mehr, nie ueber 45 km/h.
        assertTrue(at10min.speedMps in 1.5..12.5, "Tempo ${at10min.speedMps}")
        assertTrue(at10min.totals.distanceM in 2500.0..7500.0, "Strecke ${at10min.totals.distanceM}")
        assertTrue(at10min.lap >= 1)

        // Beide Belaege kamen vor, mit unterschiedlichem Rollwiderstand in den Vorgaben.
        val sims = rig.commandsOf(TrainerCommand.SetSimulation::class.java).map { it.first to it.second as TrainerCommand.SetSimulation }
        assertTrue(sims.map { it.second.crr }.toSet().size >= 2, "Schotter und Asphalt muessen verschiedene crr senden")
        // Der Huegel (+-40 m auf 1,5 km) zeigt sich in der Steigung der Vorgaben; Gang 16 ist praktisch 1:1.
        assertTrue(sims.maxOf { it.second.gradePct } - sims.minOf { it.second.gradePct } > 4.0)
        // Sparsam: ausser beim Schalten hoechstens einmal je Sekunde.
        sims.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first - a.first >= 1000 || b.first in shiftTimes, "zu dichte Vorgaben bei ${a.first} / ${b.first}")
        }
        assertEquals(1, rig.commandsOf(TrainerCommand.Start::class.java).size)
        assertNull(rig.failure)

        // --- Auto-Pause: bergauf aufhoeren zu treten (dort rollt das Rad sicher aus).
        rig.runUntil(500.0) { it.state.phase == IndoorPhase.RUNNING && it.state.gradePct >= 3.0 }
        trainer.riderPowerW = 0
        trainer.riderCadenceRpm = 0
        val stopStartMs = rig.now
        val coasting = rig.runUntil(120.0) { it.state.phase == IndoorPhase.AUTO_PAUSED }
        val paused = coasting.last().state
        assertTrue(rig.now - stopStartMs >= 3000, "Auto-Pause frueher als nach 3 s Stillstand")
        assertEquals(0.0, paused.speedMps)
        val movingAtPause = paused.totals.movingS
        val distAtPause = paused.distM
        val during = rig.run(20.0)
        assertTrue(during.all { it.state.phase == IndoorPhase.AUTO_PAUSED })
        assertEquals(distAtPause, during.last().state.distM, "Stillstand darf nichts fahren")
        assertEquals(movingAtPause, during.last().state.totals.movingS)
        assertTrue(during.last().state.elapsedS >= paused.elapsedS + 19, "Pausenzeit zaehlt zur verstrichenen Zeit")

        // --- Weiter mit Tritt.
        trainer.riderPowerW = 200
        trainer.riderCadenceRpm = 85
        val resumed = rig.runUntil(3.0) { it.state.phase == IndoorPhase.RUNNING }
        assertEquals(IndoorPhase.RUNNING, resumed.last().state.phase)
        rig.run(60.0)
        assertEquals(IndoorPhase.RUNNING, session.state.phase)
        assertTrue(session.state.distM > distAtPause)

        // --- Ende: Aufzeichnung.
        val state = session.state
        val rec = session.finish()
        assertSame(rec, session.finish(), "mehrfaches finish liefert dieselbe Aufzeichnung")
        assertEquals(startedAt, rec.startedAtEpochMs)
        assertEquals(route.id, rec.routeId)
        assertEquals(IndoorMode.SIM, rec.mode)
        assertEquals(state.totals.distanceM, rec.totals.distanceM, 1e-9)
        assertEquals(ceil(state.distM / route.lengthM).toInt(), rec.laps)
        assertNull(rec.ghostFinalGapS)

        // ~1 Punkt je Sekunde Fahrzeit: Startpunkt beim ersten Tritt, je ganze Sekunde einer und
        // bei finish() ein Schlusspunkt fuer die angefangene Sekunde (falls es eine gibt).
        assertTrue(rec.points.size in rec.totals.movingS + 1..rec.totals.movingS + 2, "${rec.points.size}")
        assertTrue(rec.points.size > 600)
        // Zeitstempel: Wanduhr ab startedAtEpochMs, streng monoton; Auto-Pause zeigt sich als Luecke.
        val times = rec.points.map { assertNotNull(it.time) }
        assertTrue(times.first() > startedAt)
        assertTrue(times.last() <= startedAt + (rig.now - 50_100L), "nicht nach dem Ende der Schleife")
        // Der Schlusspunkt (angefangene Sekunde) darf naeher am vorigen liegen.
        val gaps = times.zipWithNext { a, b -> b - a }.dropLast(1)
        assertTrue(gaps.all { it >= 1000 }, "Punkte frueher als im Sekundentakt")
        assertTrue(gaps.max() >= 20_000, "die Auto-Pause (>= 20 s) muss als Luecke in der Wanduhr stehen: ${gaps.max()}")
        assertEquals(gaps.count { it == 1000L }, gaps.count { it < 3000 }, "Fahren ist exakt im Sekundentakt")
        // Leistung und Kadenz an jedem Punkt; waehrend des Fahrens genau die vom Trainer.
        // Der Startpunkt hat keine Leistung (kein „seit dem vorigen Punkt"), sonst jeder.
        assertNull(rec.points.first().power)
        assertTrue(rec.points.drop(1).all { it.power != null } && rec.points.all { it.cad != null })
        val pedaling = rec.points.drop(1).filter { it.power!! > 0 }
        assertTrue(pedaling.size > 600)
        assertTrue(pedaling.count { it.power == 200 } >= pedaling.size * 9 / 10, "Leistung ~200 W")
        assertTrue(pedaling.filter { it.power == 200 }.all { it.cad == 85 })
        assertTrue(rec.points.all { it.hr == 140 })
        assertTrue(rec.points.all { it.ele != null })
        assertTrue(rec.totals.avgPowerW!! in 100.0..200.5)
        assertEquals(85.0, rec.totals.avgCadenceRpm!!, 1e-6) // Kadenz nur beim Treten gemittelt

        // Strecke der Stats = Summe der Sitzung: Die Sitzung ueberschreibt Weg, Hoehe und Fahrzeit
        // aus ihren Summen, weil der Rundenwechsel in den Punkten einen Sprung Ziel → Start zeigt.
        val statsM = rec.stats.distanceKm * 1000.0
        assertEquals(rec.totals.distanceM, statsM, 1e-6)
        assertEquals(rec.totals.ascentM, rec.stats.ascentM, 1e-9)
        assertEquals(rec.totals.movingS, rec.stats.movingTimeS)
        assertTrue(statsM <= rec.totals.distanceM + 1.0, "Punkte koennen nur weniger Weg zeigen")
        assertEquals(((times.last() - times.first()) / 1000.0).roundToInt(), rec.stats.durationS)
        assertEquals(140, rec.stats.avgHrBpm)

        // Danach liefert der naechste step genau einmal Stop.
        val afterFinish = rig.tick()
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Stop), afterFinish.commands)
        assertEquals(IndoorPhase.FINISHED, afterFinish.state.phase)
        repeat(20) { assertTrue(rig.tick().commands.isEmpty()) }
        assertNull(rig.failure)
    }

    // ------------------------------------------------------------ 2. ERG-Fahrt

    @Test
    fun `ERG-Fahrt folgt den Abschnitten der Intervalleinheit`() {
        val route = buildLoopRoute()
        val rider = defaultRider(ftp = 240.0)
        val workout = assertNotNull(ergWorkoutFor(intervalSession(), "Schwellenintervalle"))
        // 10 min einfahren (3 Stufen) + 3 x 5 min Belastung + 2 x 3 min Pause + 5 min ausfahren
        assertEquals(36 * 60, workout.totalS)
        assertEquals(9, workout.steps.size)
        val expectedTargets = workout.steps.map { (240.0 * it.ftpFraction).roundToInt() }
        assertEquals(listOf(120, 144, 168, 240, 120, 240, 120, 240, 108), expectedTargets)

        val session = createIndoorSession(
            IndoorSessionConfig(route, IndoorMode.ERG, rider, workout = workout, capabilities = demoCapabilities, startedAtEpochMs = startedAt),
        )
        val trainer = createDemoTrainer()
        trainer.riderCadenceRpm = 85
        trainer.riderPowerW = 100 // gilt nur bis zur ersten Vorgabe; ERG laeuft danach dem Ziel nach
        val rig = Rig(session, trainer)

        val states = ArrayList<IndoorSessionState>()
        var runningSince: Long? = null
        // Die ganze Einheit plus 30 s.
        repeat((workout.totalS + 30) * 10) {
            val s = rig.tick()
            states += s.state
            if (runningSince == null && s.state.phase == IndoorPhase.RUNNING) runningSince = rig.now
        }
        val t0 = assertNotNull(runningSince)
        assertNull(rig.failure)
        assertTrue(states.all { it.mode == IndoorMode.ERG })

        // Nur Zielleistungen, nie Simulationsvorgaben; Reihenfolge = Reihenfolge der Abschnitte.
        assertTrue(rig.commandsOf(TrainerCommand.SetSimulation::class.java).isEmpty())
        val targets = rig.sent.filter { it.second is TrainerCommand.SetTargetPower }
        assertEquals(expectedTargets, targets.map { (it.second as TrainerCommand.SetTargetPower).watts })
        // Der erste Befehl ging mit Start in den allerersten Schritt, die weiteren an den Abschnittsgrenzen.
        assertEquals(TrainerCommand.Start, rig.sent.first().second)
        var boundaryMs = 0L
        workout.steps.dropLast(1).forEachIndexed { i, step ->
            boundaryMs += step.durationS * 1000L
            val expectedNow = t0 + boundaryMs - 100
            val actual = targets[i + 1].first
            assertTrue(abs(actual - expectedNow) <= 150, "Wechsel $i: erwartet um $expectedNow, war $actual")
        }
        // Zustand zeigt Abschnitt, Ziel und Restzeit.
        val atWork1 = states.first { it.erg!!.stepIndex == 3 }
        assertEquals(240, atWork1.erg!!.targetW)
        assertEquals(300, atWork1.erg!!.stepRemainingS)
        assertTrue(!atWork1.erg!!.done)
        assertTrue(states.last().erg!!.done)
        assertEquals(108, states.last().erg!!.targetW)

        // Die Leistung im HUD-Zustand laeuft dem Ziel nach: Sprung 168 -> 240 W bei 600 s.
        val idx = states.indexOfFirst { it.erg!!.stepIndex == 3 }
        val ramp = states.subList(idx + 20, idx + 100) // 2 s .. 10 s nach dem Wechsel
        assertTrue(ramp.first().powerW!! in 169..239, "2 s nach dem Wechsel noch unterwegs: ${ramp.first().powerW}")
        assertTrue(ramp.zipWithNext().all { (a, b) -> b.powerW!! >= a.powerW!! }, "Leistung steigt stetig zum Ziel")
        assertTrue(abs(states[idx + 300].powerW!! - 240) <= 2, "nach 30 s am Ziel: ${states[idx + 300].powerW}")
        // Und nach unten: Pause 240 -> 120 W.
        val idxRest = states.indexOfFirst { it.erg!!.stepIndex == 4 }
        assertTrue(states[idxRest + 20].powerW!! in 121..239)
        assertTrue(abs(states[idxRest + 300].powerW!! - 120) <= 2)

        // Die Fahrt laeuft trotzdem unter dem Rad durch.
        assertEquals(IndoorPhase.RUNNING, states.last().phase)
        assertTrue(states.last().totals.distanceM > 8000.0, "Strecke ${states.last().totals.distanceM}")
        assertTrue(states.last().lap >= 2)
    }

    @Test
    fun `adjustErg verschiebt das Ziel sofort und der Demo-Trainer folgt`() {
        val route = buildLoopRoute()
        val rider = defaultRider(ftp = 240.0)
        val workout = assertNotNull(ergWorkoutFor(intervalSession(), "Schwellenintervalle"))
        val session = createIndoorSession(
            IndoorSessionConfig(route, IndoorMode.ERG, rider, workout = workout, capabilities = demoCapabilities, startedAtEpochMs = startedAt),
        )
        val trainer = createDemoTrainer()
        trainer.riderCadenceRpm = 85
        val rig = Rig(session, trainer)

        // Einfahren bei 0,50 x 240 = 120 W.
        rig.run(60.0)
        assertEquals(120, session.state.erg!!.targetW)
        assertTrue(abs(session.state.powerW!! - 120) <= 2, "Leistung ${session.state.powerW}")
        val before = rig.sent.size

        session.adjustErg(+5)
        val step = rig.tick()
        // Sofort im naechsten Schritt: Zustand und Befehl (0,50 + 0,05) x 240 = 132 W.
        assertEquals(132, step.state.erg!!.targetW)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(132)), step.commands)
        assertEquals(before + 1, rig.sent.size)
        // Der Abschnitt ist derselbe geblieben.
        assertEquals(0, step.state.erg!!.stepIndex)

        // Der Demo-Trainer laeuft dem neuen Ziel nach.
        val ramp = rig.run(10.0)
        assertTrue(ramp.zipWithNext().all { (a, b) -> b.state.powerW!! >= a.state.powerW!! })
        assertTrue(ramp.first().state.powerW!! in 120..132)
        rig.run(20.0)
        assertTrue(abs(session.state.powerW!! - 132) <= 2, "Leistung ${session.state.powerW}")

        // Dauerhaft bis +-30 Prozentpunkte der FTP: +40 klemmt bei 0,50 + 0,30 = 0,80 x 240 = 192 W.
        session.adjustErg(+40)
        val clamped = rig.tick()
        assertEquals(192, clamped.state.erg!!.targetW)
        assertEquals(listOf<TrainerCommand>(TrainerCommand.SetTargetPower(192)), clamped.commands)
        // Wirkt auch im naechsten Abschnitt: die Einheit steht danach weiter auf +30.
        rig.run(200.0 - 31.0)
        val nextStep = session.state.erg!!
        assertEquals(1, nextStep.stepIndex)
        assertEquals(((0.60 + 0.30) * 240).roundToInt(), nextStep.targetW)
        assertEquals(nextStep.targetW, (rig.sent.last().second as TrainerCommand.SetTargetPower).watts)
        // Gaenge sind im ERG-Modus ohne Wirkung.
        val gear = session.state.gear.index
        session.shift(ShifterEvent.UP)
        assertTrue(rig.tick().commands.isEmpty())
        assertEquals(gear, session.state.gear.index)
    }

    // ------------------------------------------------------------ 3. Geist

    @Test
    fun `Geist mit 150 W liegt gegen 250 W hinten`() {
        val route = buildLoopRoute()
        val session = createIndoorSession(simConfig(route, ghost = GhostSpec.ConstantPower(150.0)))
        val trainer = createDemoTrainer()
        trainer.riderPowerW = 250
        trainer.riderCadenceRpm = 90
        val rig = Rig(session, trainer)

        rig.awaitTrainerReady()
        val atStart = rig.run(1.0).last().state
        assertNotNull(atStart.ghost)
        assertTrue(abs(atStart.ghost!!.gapM) < 5.0, "beide starten zusammen: ${atStart.ghost}")

        rig.run(59.0)
        val at1min = session.state.ghost!!
        assertTrue(at1min.gapM < 0, "nach 1 min vorn: $at1min")
        rig.run(240.0)
        val st = session.state
        val g = st.ghost!!
        assertTrue(g.gapM < at1min.gapM, "der Vorsprung waechst: ${at1min.gapM} -> ${g.gapM}")
        assertTrue(g.gapM < -100.0, "nach 5 min deutlich vorn: ${g.gapM}")
        assertEquals(g.distM - st.distM, g.gapM, 1e-6)
        assertTrue(g.distM < st.distM)
        assertTrue(g.speedMps > 0.0)
        // Abstand in Sekunden beim Tempo des Hinteren (hier: Geist), negativ.
        assertTrue(g.gapS!! < 0.0 && g.gapS!! > -600.0)
        // Die Aufzeichnung merkt sich den Endabstand.
        val rec = session.finish()
        assertTrue(rec.ghostFinalGapS!! < 0.0)
        assertEquals(g.gapS!!, rec.ghostFinalGapS!!, 1e-6)
    }

    // ------------------------------------------------- 4. Strecke ohne Rundschluss

    @Test
    fun `Strecke ohne Rundschluss endet am Ziel in FINISHED`() {
        val route = assertNotNull(buildIndoorRoute("Stichstrasse", openPoints(1500.0), null, IndoorRouteSource.NONE))
        assertTrue(!route.isLoop)
        assertEquals(IndoorRouteSource.NONE, route.source)
        assertEquals(listOf(SurfaceKind.UNKNOWN), route.surfaces.map { it.kind })

        val session = createIndoorSession(simConfig(route))
        val trainer = createDemoTrainer()
        trainer.riderPowerW = 300
        trainer.riderCadenceRpm = 90
        val rig = Rig(session, trainer)

        val ride = rig.runUntil(600.0) { it.state.phase == IndoorPhase.FINISHED }
        val last = ride.last()
        // Im Zielschritt genau ein Pause (Widerstand frei), noch kein Stop.
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Pause), last.commands)
        assertEquals(route.lengthM, last.state.distM, 1e-9)
        assertEquals(route.lengthM, last.state.totals.distanceM, 1e-9)
        assertEquals(1, last.state.lap)
        // Davor lief die Fahrt, und das Ziel kam nicht vorzeitig.
        assertTrue(ride.dropLast(1).all { it.state.phase != IndoorPhase.FINISHED })
        assertTrue(last.state.totals.movingS in 60..300, "Fahrzeit ${last.state.totals.movingS}")

        // Danach: keine Befehle, kein Weiterrollen — auch wenn der Fahrer weitertritt.
        val after = rig.run(10.0)
        assertTrue(after.all { it.commands.isEmpty() })
        assertTrue(after.all { it.state.phase == IndoorPhase.FINISHED && it.state.distM == route.lengthM })

        // finish(): Aufzeichnung der einen Runde, danach genau einmal Stop.
        val rec = session.finish()
        assertEquals(1, rec.laps)
        // Startpunkt, je ganze Sekunde einer, Schlusspunkt am Ziel fuer die angefangene Sekunde.
        assertTrue(rec.points.size in rec.totals.movingS + 1..rec.totals.movingS + 2, "${rec.points.size}")
        assertEquals(route.lengthM, rec.totals.distanceM, 1e-9)
        // Punkte: ein letzter Punkt hoechstens ~1 s vor dem Ziel; Strecke wie in Fall 1 mit 25 m Toleranz.
        assertEquals(route.lengthM, rec.stats.distanceKm * 1000.0, 25.0)
        val endPoint = route.points.last()
        val lastPoint = rec.points.last()
        val distToGoalM = haversineM(lastPoint, TrackPoint(endPoint.lat, endPoint.lon))
        assertTrue(distToGoalM < 25.0, "letzter Punkt $distToGoalM m vom Ziel")
        assertEquals(listOf<TrainerCommand>(TrainerCommand.Stop), rig.tick().commands)
        assertTrue(rig.tick().commands.isEmpty())
        assertNull(rig.failure)
    }
}
