package de.trailscape.core.indoor

import de.trailscape.core.TrackPoint
import de.trailscape.core.computeStats
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Eine neue Sitzung in der Phase [IndoorPhase.READY] (Umsetzung von `createIndoorSession`). */
internal fun indoorSession(config: IndoorSessionConfig): IndoorSession = IndoorSessionImpl(config)

/** Hoechster Schrittabstand, der als gefahrene Zeit zaehlt (Vertrag [IndoorSession]). */
internal const val MAX_STEP_MS: Long = 2000

/** So alt darf ein Messwert sein, damit er noch gilt; danach zaehlt die Leistung als 0. */
internal const val TELEMETRY_FRESH_MS: Long = 3000

/** Fenster des angezeigten Leistungsmittels. */
internal const val POWER_DISPLAY_WINDOW_MS: Long = 3000

/** Auto-Pause: so lange keine Leistung und Tempo unter [AUTO_PAUSE_SPEED_MPS]. */
internal const val AUTO_PAUSE_IDLE_MS: Long = 3000

/** Auto-Pause: laenger als so lange nicht READY. */
internal const val AUTO_PAUSE_NOT_READY_MS: Long = 5000

internal const val AUTO_PAUSE_SPEED_MPS: Double = 1.0 / 3.6

/** SIM-Vorgaben hoechstens so oft (ausser nach dem Schalten). */
internal const val SIM_MIN_INTERVAL_MS: Long = 1000

/** Ab dieser Aenderung der gesendeten Steigung lohnt eine neue Vorgabe. */
internal const val SIM_MIN_GRADE_CHANGE_PCT: Double = 0.2

/** Bereich von [IndoorSession.adjustErg] in Prozentpunkten der FTP. */
internal const val ERG_ADJUST_LIMIT_PCT: Int = 30

private const val NP_WINDOW_S = 30

/**
 * # Die Indoor-Sitzung als reiner Automat
 *
 * Zeit kommt nur aus `nowMs` (monotone Uhr). Ablauf je [step]:
 *
 *  1. Nach [finish] bzw. am Ziel: letzter Zustand ohne Befehle — nur der erste
 *     Schritt nach [finish] liefert genau einmal `Stop`. Nicht steigendes
 *     `nowMs`: letzter Zustand ohne Befehle, nichts wird gerechnet.
 *  2. Der erste Schritt merkt die Referenzzeit und liefert `Start` und die
 *     erste Vorgabe (SIM: Startstelle im Startgang; ERG: erster Abschnitt).
 *  3. dt = min(nowMs − letzter, [MAX_STEP_MS]).
 *  4. Je Messfeld (Leistung, Kadenz) gilt der letzte Nicht-null-Wert, solange
 *     er hoechstens [TELEMETRY_FRESH_MS] alt ist (`receivedMs` ist dieselbe
 *     Uhr) — FTMS-Trainer teilen Indoor Bike Data auf mehrere Pakete auf, ein
 *     `null` heisst also „nicht in diesem Paket", nicht 0 W. Aelter: Leistung 0
 *     bzw. keine Kadenz. Die
 *     Physik rechnet mit der momentanen Leistung, angezeigt wird das
 *     zeitgewichtete 3-s-Mittel.
 *  5. Phase: READY → RUNNING beim ersten Tritt (Leistung oder Kadenz > 0).
 *     RUNNING → AUTO_PAUSED nach [AUTO_PAUSE_IDLE_MS] ohne Leistung unter
 *     1 km/h oder nach mehr als [AUTO_PAUSE_NOT_READY_MS] ohne bereiten
 *     Trainer; zurueck beim ersten Tritt mit bereitem Trainer. PAUSED nur ueber
 *     [pause]/[resume]. Physik, Aufzeichnung, ERG-Uhr und Geist laufen nur in
 *     RUNNING; `elapsedS` laeuft ab dem ersten Tritt immer (mit gekapptem dt).
 *  6. Am Ziel einer Strecke ohne Rundschluss: Phase FINISHED, einmal `Pause`
 *     (der Widerstand wird frei); `Stop` kommt erst ueber [finish].
 *  7. Vorgaben: SIM hoechstens einmal je Sekunde und nur bei merklicher
 *     Aenderung der **gesendeten** Werte (Steigung ≥ 0,2 Prozentpunkte, sonst
 *     mehr als eine Einheit der FTMS-Kodierung), nach dem Schalten sofort;
 *     ERG bei jeder Aenderung der Zielleistung (Abschnittswechsel,
 *     [adjustErg]). In PAUSED und FINISHED keine Vorgaben; [resume] liefert
 *     `Start` und die aktuelle Vorgabe.
 *
 * Entscheidungen, wo Entwurf oder Vertrag Spielraum liessen:
 *  * ERG ohne (oder mit leerer) Einheit ist eine Fehlkonfiguration und faehrt
 *    wie SIM; [IndoorSessionState.mode] und [IndoorRideRecord.mode] zeigen dann
 *    SIM (die Oberflaeche soll Gaenge zeigen, die auch wirken).
 *  * [adjustErg] wirkt nach dem Vertrag in **Prozentpunkten der FTP**:
 *    Ziel = round(FTP · (Anteil + adj/100)). Der Entwurf schrieb
 *    `Anteil · FTP · (1 + adj/100)` (Prozent der Zielleistung); der Vertrags-KDoc
 *    geht vor.
 *  * [resume] kehrt in die Phase vor der Pause zurueck (READY bleibt READY,
 *    sonst RUNNING — ohne Tritt folgt nach 3 s die Auto-Pause).
 *  * Ein Schritt, in dem der erste Tritt erkannt wird, zaehlt schon als
 *    Fahrzeit (die Leistung kam in diesem Abstand an).
 *  * Ueberquert ein Schritt eine volle Sekunde der Fahrzeit mehrfach (dt bis
 *    2 s), entsteht je Sekunde ein Punkt, Lage linear dazwischen.
 *  * Erreichen auf einer Strecke ohne Rundschluss Geist und Fahrer beide das
 *    Ziel, ist der Endabstand in Sekunden die Differenz der Ankunftszeiten
 *    (positiv = Geist war vorn); der Abstand in Metern ist dann 0.
 */
internal class IndoorSessionImpl(private val config: IndoorSessionConfig) : IndoorSession {
    private val route = config.route
    private val rider = config.rider
    private val lengthM = route.lengthM
    private val workout: ErgWorkout? = config.workout?.takeIf { w -> w.steps.isNotEmpty() && w.steps.all { it.durationS > 0 } }
    private val mode: IndoorMode = if (config.mode == IndoorMode.ERG && workout != null) IndoorMode.ERG else IndoorMode.SIM
    private val ghost: GhostRunner? = ghostRunner(config.ghost, route, rider, config.wind)

    // Zeit
    private var firstNowMs: Long? = null
    private var lastNowMs: Long = 0
    private var started = false
    private var elapsedMs = 0L
    private var runningMs = 0L

    // Bewegung
    private var phase = IndoorPhase.READY
    private var pausedFrom = IndoorPhase.READY
    private var speed = 0.0
    private var dist = 0.0
    private var idleMs = 0L
    private var notReadyMs = 0L

    // Gaenge und Befehle
    private var gearIndex = startGearIndex(rider.realGearRatio)
    private val pendingShifts = ArrayList<ShifterEvent>()
    private val pendingCommands = ArrayList<TrainerCommand>()
    private var resendMode = false
    private var lastSim: TrainerCommand.SetSimulation? = null
    private var lastSimMs = 0L
    private var lastTargetW: Int? = null
    private var ergMs = 0L
    private var ergAdjustPct = 0

    // Ende
    private var stopPending = false
    private var record: IndoorRideRecord? = null
    private var ghostFinishMs: Long? = null
    private var riderFinishMs: Long? = null

    // Leistung
    private var powerSeen = false
    private var lastPowerW = 0
    private var powerAtMs = Long.MIN_VALUE
    private var lastCadenceRpm: Double? = null
    private var cadenceAtMs = Long.MIN_VALUE
    private val powerWindow = ArrayDeque<PowerSlice>()
    private var powerWindowMs = 0L

    // Aufzeichnung und Summen
    private val points = ArrayList<TrackPoint>()
    private var secondMs = 0L
    private var secondEnergyJ = 0.0
    private var energyJ = 0.0
    private var cadenceSum = 0.0
    private var cadenceMs = 0L
    private var hrSum = 0.0
    private var hrMs = 0L
    private var ascentM = 0.0
    private val npWindow = ArrayDeque<Double>()
    private var npWindowSum = 0.0
    private var np4Sum = 0.0
    private var npCount = 0

    private var lastCadence: Int? = null
    private var lastHr: Int? = null
    private var lastPowerDisplay: Int? = null

    override var state: IndoorSessionState = buildState(0L)
        private set

    override fun step(nowMs: Long, input: SessionInput): IndoorStep {
        if (phase == IndoorPhase.FINISHED) {
            if (stopPending) {
                stopPending = false
                return IndoorStep(state, listOf(TrainerCommand.Stop))
            }
            return IndoorStep(state, emptyList())
        }
        val first = firstNowMs == null
        if (!first && nowMs <= lastNowMs) {
            return IndoorStep(state, emptyList())
        }
        val commands = ArrayList<TrainerCommand>()
        var sendMode = false
        if (first) {
            firstNowMs = nowMs
            commands += TrainerCommand.Start
            sendMode = true
        }
        val dtMs = if (first) 0L else min(nowMs - lastNowMs, MAX_STEP_MS)
        lastNowMs = nowMs

        // Messwerte: je Feld der letzte Nicht-null-Wert mit eigener Frische
        // (FTMS-Trainer teilen Indoor Bike Data auf mehrere Pakete auf).
        input.telemetry?.let { t ->
            t.powerW?.let { w ->
                if (t.receivedMs >= powerAtMs) {
                    lastPowerW = w
                    powerAtMs = t.receivedMs
                    powerSeen = true
                }
            }
            t.cadenceRpm?.takeIf { it.isFinite() && it >= 0 }?.let { c ->
                if (t.receivedMs >= cadenceAtMs) {
                    lastCadenceRpm = c
                    cadenceAtMs = t.receivedMs
                }
            }
        }
        val power = if (powerSeen && nowMs - powerAtMs <= TELEMETRY_FRESH_MS) max(lastPowerW, 0).toDouble() else 0.0
        val cadence = lastCadenceRpm.takeIf { it != null && nowMs - cadenceAtMs <= TELEMETRY_FRESH_MS }
        lastCadence = cadence?.roundToInt()
        lastHr = input.hrBpm
        updatePowerDisplay(dtMs, power)
        notReadyMs = if (input.trainerReady) 0 else notReadyMs + dtMs

        // Wuensche aus pause/resume/shift
        commands += pendingCommands
        pendingCommands.clear()
        if (resendMode) {
            resendMode = false
            sendMode = true
        }
        var forceSim = false
        if (pendingShifts.isNotEmpty()) {
            if (mode == IndoorMode.SIM) {
                for (e in pendingShifts) gearIndex = shiftedGearIndex(gearIndex, e)
                forceSim = true
            }
            pendingShifts.clear()
        }

        // Phase
        val pedaling = power > 0 || (cadence ?: 0.0) > 0
        when (phase) {
            IndoorPhase.READY -> if (pedaling) {
                phase = IndoorPhase.RUNNING
                started = true
                idleMs = 0
            }
            IndoorPhase.AUTO_PAUSED -> if (pedaling && input.trainerReady) {
                phase = IndoorPhase.RUNNING
                idleMs = 0
            }
            else -> Unit
        }
        if (started) {
            elapsedMs += dtMs
        }

        var reachedGoal = false
        if (phase == IndoorPhase.RUNNING) {
            reachedGoal = ride(nowMs, dtMs, power, cadence, input.hrBpm)
            if (!reachedGoal) {
                idleMs = if (power <= 0 && speed < AUTO_PAUSE_SPEED_MPS) idleMs + dtMs else 0
                if (idleMs >= AUTO_PAUSE_IDLE_MS || notReadyMs > AUTO_PAUSE_NOT_READY_MS) {
                    phase = IndoorPhase.AUTO_PAUSED
                    speed = 0.0
                    idleMs = 0
                }
            }
        }

        if (reachedGoal) {
            phase = IndoorPhase.FINISHED
            commands += TrainerCommand.Pause
        } else if (phase != IndoorPhase.PAUSED) {
            modeCommand(nowMs, sendMode, forceSim)?.let { commands += it }
        }
        state = buildState(nowMs)
        return IndoorStep(state, commands)
    }

    /** Ein Schritt Fahrt in RUNNING; `true`, wenn dabei das Ziel erreicht wurde. */
    private fun ride(nowMs: Long, dtMs: Long, power: Double, cadence: Double?, hr: Int?): Boolean {
        if (dtMs <= 0) {
            return false
        }
        val dtS = dtMs / 1000.0
        val s = route.sampleAt(dist)
        val step = advancePhysics(
            speedMps = speed,
            powerW = power,
            gradePct = s.gradePct,
            crr = effectiveCrr(s.surface.kind, rider.profileCrr),
            eleM = s.eleM,
            headwindMps = headwindMps(config.wind, s.headingDeg),
            rider = rider,
            dtS = dtS,
        )
        val from = dist
        var to = dist + step.distanceM
        speed = step.speedMps
        val goal = !route.isLoop && to >= lengthM
        if (goal) {
            to = lengthM
        }
        ascentM += climbM(from, to)
        recordSeconds(nowMs, dtMs, power, from, to, cadence, hr)
        dist = to
        runningMs += dtMs
        energyJ += power * dtS
        if (cadence != null && cadence > 0) {
            cadenceSum += cadence * dtMs
            cadenceMs += dtMs
        }
        if (hr != null) {
            hrSum += hr.toDouble() * dtMs
            hrMs += dtMs
        }
        if (mode == IndoorMode.ERG) {
            ergMs += dtMs
        }
        ghost?.let { g ->
            g.advance(runningMs, dtS)
            if (!route.isLoop && ghostFinishMs == null && g.distM >= lengthM) {
                ghostFinishMs = runningMs
            }
        }
        if (goal) {
            riderFinishMs = runningMs
        }
        return goal
    }

    /** Positive Hoehendifferenz der virtuellen Stelle von [from] nach [to] (auf einer Runde ueber das Ziel hinweg). */
    private fun climbM(from: Double, to: Double): Double {
        fun up(a: Double, b: Double) = max(0.0, b - a)
        if (route.isLoop && lengthM > 0 && floor(to / lengthM) > floor(from / lengthM)) {
            return up(route.sampleAt(from).eleM, route.points.last().eleM) +
                up(route.points.first().eleM, route.sampleAt(to).eleM)
        }
        return up(route.sampleAt(from).eleM, route.sampleAt(to).eleM)
    }

    /** Ein Punkt je voller Sekunde Fahrzeit; Leistung als Mittel ueber genau diese Sekunde. */
    private fun recordSeconds(nowMs: Long, dtMs: Long, power: Double, from: Double, to: Double, cadence: Double?, hr: Int?) {
        var consumed = 0L
        while (consumed < dtMs) {
            val take = min(dtMs - consumed, 1000 - secondMs)
            secondEnergyJ += power * take / 1000.0
            secondMs += take
            consumed += take
            if (secondMs >= 1000) {
                val d = from + (to - from) * consumed / dtMs
                val s = route.sampleAt(d)
                val avgW = secondEnergyJ
                points += TrackPoint(
                    lat = s.lat,
                    lon = s.lon,
                    ele = s.eleM,
                    time = config.startedAtEpochMs + (nowMs - firstNowMs!!) - (dtMs - consumed),
                    hr = hr,
                    power = if (powerSeen) avgW.roundToInt() else null,
                    cad = cadence?.roundToInt(),
                )
                addNormalizedPowerSecond(avgW)
                secondMs = 0
                secondEnergyJ = 0.0
            }
        }
    }

    private fun addNormalizedPowerSecond(watts: Double) {
        npWindow.addLast(watts)
        npWindowSum += watts
        if (npWindow.size > NP_WINDOW_S) {
            npWindowSum -= npWindow.removeFirst()
        }
        if (npWindow.size == NP_WINDOW_S) {
            np4Sum += (npWindowSum / NP_WINDOW_S).pow(4)
            npCount++
        }
    }

    private fun updatePowerDisplay(dtMs: Long, power: Double) {
        if (dtMs > 0) {
            powerWindow.addLast(PowerSlice(dtMs, power))
            powerWindowMs += dtMs
            while (powerWindow.size > 1 && powerWindowMs - powerWindow.first().ms >= POWER_DISPLAY_WINDOW_MS) {
                powerWindowMs -= powerWindow.removeFirst().ms
            }
        }
        lastPowerDisplay = if (!powerSeen) {
            null
        } else if (powerWindowMs > 0) {
            (powerWindow.sumOf { it.ms * it.watts } / powerWindowMs).roundToInt()
        } else {
            power.roundToInt()
        }
    }

    /** Die Vorgabe dieses Schritts oder `null`, wenn keine noetig ist. */
    private fun modeCommand(nowMs: Long, force: Boolean, forceSim: Boolean): TrainerCommand? {
        if (mode == IndoorMode.ERG) {
            val target = ergState()!!.targetW
            if (!force && target == lastTargetW) {
                return null
            }
            lastTargetW = target
            return TrainerCommand.SetTargetPower(target)
        }
        val wanted = currentSimulation()
        val last = lastSim
        val due = force || forceSim || last == null ||
            (nowMs - lastSimMs >= SIM_MIN_INTERVAL_MS && noticeablyDifferent(wanted, last))
        if (!due) {
            return null
        }
        lastSim = wanted
        lastSimMs = nowMs
        return wanted
    }

    private fun currentSimulation(): TrainerCommand.SetSimulation {
        val s = route.sampleAt(dist)
        return simulationCommand(
            gradePct = s.gradePct,
            crr = effectiveCrr(s.surface.kind, rider.profileCrr),
            cwKgPerM = aeroCoefficient(PhysicsRider.of(rider).cdaM2, s.eleM),
            headwindMps = headwindMps(config.wind, s.headingDeg),
            gearRatio = gearState(gearIndex).ratio,
            rider = rider,
        )
    }

    private fun noticeablyDifferent(a: TrainerCommand.SetSimulation, b: TrainerCommand.SetSimulation): Boolean {
        val eps = 1e-9
        return abs(a.gradePct - b.gradePct) >= SIM_MIN_GRADE_CHANGE_PCT - eps ||
            abs(a.crr - b.crr) > 0.0001 + eps ||
            abs(a.cwKgPerM - b.cwKgPerM) > 0.01 + eps ||
            abs(a.windMps - b.windMps) > 0.001 + eps
    }

    private fun targetW(step: ErgStep): Int {
        val ftp = if (rider.ftpW.isFinite()) max(rider.ftpW, 0.0) else 0.0
        var w = (ftp * (step.ftpFraction + ergAdjustPct / 100.0)).roundToInt()
        config.capabilities.maxPowerW?.let { w = min(w, it) }
        config.capabilities.minPowerW?.let { w = max(w, it) }
        return max(w, 0)
    }

    private fun ergState(): ErgState? {
        val w = workout ?: return null
        if (mode != IndoorMode.ERG) {
            return null
        }
        var endMs = 0L
        for ((i, s) in w.steps.withIndex()) {
            endMs += s.durationS * 1000L
            if (ergMs < endMs) {
                val remaining = ceil((endMs - ergMs) / 1000.0).toInt()
                return ErgState(stepIndex = i, targetW = targetW(s), stepRemainingS = remaining, done = false)
            }
        }
        val lastIndex = w.steps.size - 1
        return ErgState(lastIndex, targetW(w.steps[lastIndex]), 0, done = true)
    }

    private fun ghostNow(): GhostState? {
        val g = ghost ?: return null
        val gs = ghostState(g, dist, speed)
        val gf = ghostFinishMs
        val rf = riderFinishMs
        return if (gf != null && rf != null) gs.copy(gapS = (rf - gf) / 1000.0) else gs
    }

    private fun totals(): IndoorTotals {
        val runningS = runningMs / 1000.0
        return IndoorTotals(
            distanceM = dist,
            ascentM = ascentM,
            movingS = (runningMs / 1000).toInt(),
            avgPowerW = if (powerSeen && runningMs > 0) energyJ / runningS else null,
            normalizedPowerW = if (powerSeen && npCount > 0) (np4Sum / npCount).pow(0.25) else null,
            avgCadenceRpm = if (cadenceMs > 0) cadenceSum / cadenceMs else null,
            avgHrBpm = if (hrMs > 0) hrSum / hrMs else null,
            workKj = energyJ / 1000.0,
        )
    }

    private fun buildState(nowMs: Long): IndoorSessionState {
        val s = route.sampleAt(dist)
        val lap = if (route.isLoop && lengthM > 0) floor(dist / lengthM).toInt() + 1 else 1
        return IndoorSessionState(
            phase = phase,
            mode = mode,
            nowMs = nowMs,
            elapsedS = (elapsedMs / 1000).toInt(),
            distM = dist,
            routeDistM = s.distM,
            lap = lap,
            speedMps = speed,
            powerW = lastPowerDisplay,
            cadenceRpm = lastCadence,
            hrBpm = lastHr,
            gradePct = s.gradePct,
            surface = s.surface.kind,
            crr = effectiveCrr(s.surface.kind, rider.profileCrr),
            headwindMps = headwindMps(config.wind, s.headingDeg),
            gear = gearState(gearIndex),
            ghost = ghostNow(),
            erg = ergState(),
            totals = totals(),
        )
    }

    override fun shift(event: ShifterEvent) {
        if (mode == IndoorMode.SIM && phase != IndoorPhase.FINISHED) {
            pendingShifts += event
        }
    }

    override fun adjustErg(deltaPct: Int) {
        ergAdjustPct = (ergAdjustPct.toLong() + deltaPct).coerceIn(-ERG_ADJUST_LIMIT_PCT.toLong(), ERG_ADJUST_LIMIT_PCT.toLong()).toInt()
    }

    override fun pause() {
        if (phase == IndoorPhase.READY || phase == IndoorPhase.RUNNING || phase == IndoorPhase.AUTO_PAUSED) {
            pausedFrom = if (phase == IndoorPhase.READY) IndoorPhase.READY else IndoorPhase.RUNNING
            phase = IndoorPhase.PAUSED
            speed = 0.0
            pendingCommands += TrainerCommand.Pause
        }
    }

    override fun resume() {
        if (phase == IndoorPhase.PAUSED) {
            phase = pausedFrom
            idleMs = 0
            pendingCommands += TrainerCommand.Start
            resendMode = true
        }
    }

    override fun finish(): IndoorRideRecord {
        record?.let { return it }
        phase = IndoorPhase.FINISHED
        stopPending = true
        state = state.copy(phase = IndoorPhase.FINISHED)
        val hrs = points.mapNotNull { it.hr }
        val stats = computeStats(points).copy(
            avgHrBpm = if (hrs.isEmpty()) null else (hrs.sum().toDouble() / hrs.size).roundToInt(),
            maxHrBpm = hrs.maxOrNull(),
        )
        val laps = if (route.isLoop && lengthM > 0) max(1, ceil(dist / lengthM - 1e-9).toInt()) else 1
        val r = IndoorRideRecord(
            startedAtEpochMs = config.startedAtEpochMs,
            routeId = route.id,
            routeName = route.name,
            mode = mode,
            points = points.toList(),
            stats = stats,
            totals = state.totals,
            laps = laps,
            ghostFinalGapS = state.ghost?.gapS,
        )
        record = r
        return r
    }
}

/** Ein Stueck des Leistungsfensters: Dauer und Leistung. */
private class PowerSlice(val ms: Long, val watts: Double)
