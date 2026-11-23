package de.trailscape.app.indoor

import android.os.SystemClock
import de.trailscape.app.ui.indoor.IndoorNotice
import de.trailscape.app.ui.indoor.IndoorRenderUi
import de.trailscape.core.indoor.DemoTrainer
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorRideRecord
import de.trailscape.core.indoor.IndoorSession
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorShifter
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.SessionInput
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.ShifterListener
import de.trailscape.core.indoor.TrainerCapabilities
import de.trailscape.core.indoor.TrainerCommand
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.TrainerListener
import de.trailscape.core.indoor.TrainerTelemetry
import de.trailscape.core.indoor.world.ActorState
import de.trailscape.core.indoor.world.RenderDeviceInfo
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderStats
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.WorldRendererListener
import de.trailscape.core.indoor.world.WorldState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * # Die Laufzeit einer Indoor-Fahrt (docs/indoor.md, Abschnitt 5.7)
 *
 * Eine Schleife mit 10 Hz auf einem **eigenen, seriellen** Dispatcher. Je Takt:
 * ```
 * trainer.tick(now); shifter?.tick(now)
 * step = session.step(now, SessionInput(letzteTelemetrie, puls, trainerBereit))
 * step.commands.forEach(trainer::send)
 * renderer?.setState(WorldState(now, ActorState(…), geist, pausiert))
 * state.value = …            // fuer das HUD
 * ```
 *
 * ## Threads
 *  * Die Sitzung ist nicht threadsicher — sie wird **nur** aus [tickOnce]
 *    gerufen (Schleife) bzw. nach dem Anhalten der Schleife aus [finish].
 *  * Telemetrie und Schaltereignisse kommen vom BLE-Thread und werden nur
 *    abgelegt (`@Volatile`, [ConcurrentLinkedQueue]).
 *  * Wuensche der Oberflaeche (Pause, Schalten, ERG, Demo-Regler) landen
 *    ebenfalls in einer Schlange und wirken im naechsten Takt.
 *  * Die Darstellung meldet ihre Ereignisse auf dem Hauptthread
 *    ([rendererListener]); sie aendern nur den [state].
 *
 * ## Faehigkeiten des Trainers
 * Die Sitzung bekommt ihre [TrainerCapabilities] beim Anlegen
 * (`IndoorSessionConfig.capabilities`, unveraenderlich), der Trainer meldet
 * die echten aber erst mit `READY`. Deshalb tauscht die Laufzeit die Sitzung
 * **einmal** gegen eine mit den gemeldeten Faehigkeiten ([rebuildSession]),
 * solange sie noch in [IndoorPhase.READY] auf den ersten Tritt wartet.
 * Schaltvorgaenge und ERG-Verschiebungen, die die alte Sitzung bis dahin
 * bekommen hat, werden auf die neue uebertragen. Meldet der Trainer seine
 * Faehigkeiten erst nach dem ersten Tritt (oder nach einem Neuverbinden
 * mitten in der Fahrt), bleibt es bei der Annahme vom Start — eine laufende
 * Sitzung laesst sich ohne Aenderung des Vertrags nicht umstellen.
 *
 * ## Robustheit
 * Jeder Aufruf eines fremden Teils (Sitzung, Trainer, Schalttaster,
 * Darstellung, Puls) steckt in `try/catch`: Ein Fehler wird zum Hinweis
 * [IndoorNotice.PART_FAILED] (bzw. zur Meldung statt 3D-Flaeche), die
 * Schleife laeuft weiter. Wirft die Darstellung, wird sie fuer den Rest der
 * Fahrt nicht mehr gerufen — HUD und Hoehenprofil bleiben.
 *
 * ## Uhr und Takt
 * [clock] ist die monotone Uhr (Vorgabe `SystemClock.elapsedRealtime()`),
 * [dispatcher] ist injizierbar. Mit `autoLoop = false` laeuft keine Schleife;
 * Tests treiben die Fahrt dann ueber [tickOnce] mit einer eigenen Uhr.
 *
 * @param renderer die Darstellung, `null` ohne Welt oder wenn sie sich nicht
 *   erzeugen liess (dann sagt [initialRender], warum).
 * @param dispatcher der serielle Dispatcher der Schleife; `null` = ein eigener
 *   Einzelthread, den die Laufzeit am Ende selbst schliesst.
 * @param rebuildSession baut zu den vom Trainer gemeldeten Faehigkeiten eine
 *   neue Sitzung, oder liefert `null`, wenn die vorhandene schon passt;
 *   `null` = nie tauschen. Wird nur auf dem Takt-Thread gerufen.
 */
class IndoorRideRuntime(
    session: IndoorSession,
    private val trainer: IndoorTrainer,
    private val shifter: IndoorShifter?,
    private val renderer: WorldRenderer?,
    private val heartRate: IndoorHeartRateSource? = null,
    initialRender: IndoorRenderUi = if (renderer == null) IndoorRenderUi.NoWorld else IndoorRenderUi.Loading(0, 0),
    dispatcher: CoroutineDispatcher? = null,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val autoLoop: Boolean = true,
    private val noticeDurationMs: Long = NOTICE_DURATION_MS,
    private val rebuildSession: ((TrainerCapabilities) -> IndoorSession?)? = null,
) {
    /** Die aktuelle Sitzung; nur vom Takt-Thread (bzw. nach dem Anhalten der Schleife). */
    private var session: IndoorSession = session

    private val ownDispatcher: ExecutorCoroutineDispatcher? =
        if (dispatcher == null) newIndoorLoopDispatcher() else null
    private val loopDispatcher: CoroutineDispatcher = dispatcher ?: ownDispatcher!!
    private val scope = CoroutineScope(SupervisorJob() + loopDispatcher)
    private var loopJob: Job? = null

    // --- vom BLE-Thread abgelegt --------------------------------------------
    @Volatile private var lastTelemetry: TrainerTelemetry? = null

    @Volatile private var trainerConnection: TrainerConnection = TrainerConnection.OFF

    @Volatile private var capabilities: TrainerCapabilities = TrainerCapabilities.NONE

    /** Der Trainer war schon einmal bereit — erst danach ist ein Abbruch ein „Verlust". */
    @Volatile private var trainerWasReady = false

    @Volatile private var trainerLost = false
    private val shiftEvents = ConcurrentLinkedQueue<ShifterEvent>()

    /** Faehigkeiten aus dem letzten `READY`, die der Takt noch nicht uebernommen hat. */
    @Volatile private var pendingCapabilities: TrainerCapabilities? = null

    // --- Wuensche der Oberflaeche --------------------------------------------
    private val actions = ConcurrentLinkedQueue<SessionAction>()

    // --- nur vom Takt-Thread ---------------------------------------------------
    private var lastStepNow = Long.MIN_VALUE
    private var started = false

    /**
     * Was die Sitzung vor dem ersten Tritt bekommen hat (Schalten, ERG) — fuer
     * den Tausch gegen eine Sitzung mit den echten Faehigkeiten. `null`, sobald
     * kein Tausch mehr in Frage kommt.
     */
    private var preStartLog: MutableList<(IndoorSession) -> Unit>? = if (rebuildSession != null) mutableListOf() else null

    @Volatile private var renderEnabled = renderer != null

    @Volatile private var finished = false
    private var record: IndoorRideRecord? = null
    private var pausedByBackground = false

    private val noticeLock = Any()
    private var noticeUntilMs = 0L

    private val _state = MutableStateFlow(
        IndoorRuntimeState(
            session = null,
            trainer = TrainerConnection.OFF,
            capabilities = TrainerCapabilities.NONE,
            shifter = if (shifter != null) ShifterConnection.OFF else null,
            shifterBatteryPct = null,
            render = initialRender,
            notice = null,
            problem = null,
            finished = false,
        ),
    )

    /** Alles, was das HUD aus der Laufzeit braucht; aendert sich mit jedem Takt. */
    val state: StateFlow<IndoorRuntimeState> = _state.asStateFlow()

    /** Der Demo-Trainer, falls einer faehrt — sonst `null`. */
    val demoTrainer: DemoTrainer? get() = trainer as? DemoTrainer

    // -----------------------------------------------------------------------
    // Listener
    // -----------------------------------------------------------------------

    private val trainerListener = object : TrainerListener {
        override fun onConnection(state: TrainerConnection, capabilities: TrainerCapabilities) {
            val previous = trainerConnection
            trainerConnection = state
            this@IndoorRideRuntime.capabilities = capabilities
            _state.update { it.copy(trainer = state, capabilities = capabilities) }
            when (state) {
                TrainerConnection.READY -> {
                    pendingCapabilities = capabilities
                    if (trainerLost) {
                        trainerLost = false
                        postNotice(IndoorNotice.TRAINER_BACK)
                    }
                    trainerWasReady = true
                }
                TrainerConnection.UNSUPPORTED -> postNotice(IndoorNotice.TRAINER_UNSUPPORTED)
                TrainerConnection.NO_PERMISSION -> postNotice(IndoorNotice.NO_PERMISSION)
                TrainerConnection.BLUETOOTH_OFF -> postNotice(IndoorNotice.BLUETOOTH_OFF)
                TrainerConnection.CONNECTING, TrainerConnection.CONNECTED -> {
                    if (previous == TrainerConnection.READY && trainerWasReady && !finished) {
                        trainerLost = true
                        postNotice(IndoorNotice.TRAINER_LOST)
                    }
                }
                TrainerConnection.OFF -> Unit
            }
        }

        override fun onTelemetry(telemetry: TrainerTelemetry) {
            lastTelemetry = telemetry
        }

        override fun onCommandFailed(command: TrainerCommand, reason: String) {
            postNotice(IndoorNotice.TRAINER_COMMAND_FAILED)
        }
    }

    private val shifterListener = object : ShifterListener {
        override fun onConnection(state: ShifterConnection) {
            _state.update { it.copy(shifter = state) }
            if (state == ShifterConnection.UNSUPPORTED) postNotice(IndoorNotice.SHIFTER_UNSUPPORTED)
        }

        override fun onShift(event: ShifterEvent) {
            shiftEvents.add(event)
        }

        override fun onBattery(percent: Int) {
            _state.update { it.copy(shifterBatteryPct = percent) }
        }
    }

    /**
     * Fuer `WorldRenderer.setListener` — der Aufrufer, der die Darstellung
     * erzeugt hat, haengt ihn an, bevor er `loadWorld` ruft.
     */
    val rendererListener: WorldRendererListener = object : WorldRendererListener {
        override fun onReady(info: RenderDeviceInfo) {
            if (!renderEnabled) return
            _state.update { if (it.render is IndoorRenderUi.Failed) it else it.copy(render = IndoorRenderUi.Ready) }
        }

        override fun onLoading(loadedTiles: Int, neededTiles: Int) {
            if (!renderEnabled) return
            _state.update {
                when (it.render) {
                    is IndoorRenderUi.Failed, IndoorRenderUi.NoWorld -> it
                    // Nachladen waehrend der Fahrt soll das Bild nicht wieder verdecken.
                    IndoorRenderUi.Ready -> it
                    is IndoorRenderUi.Loading -> it.copy(render = IndoorRenderUi.Loading(loadedTiles, neededTiles))
                }
            }
        }

        override fun onTileFailed(tileId: String, detail: String) {
            postNotice(IndoorNotice.TILE_FAILED)
        }

        override fun onFailure(failure: RenderFailure, detail: String) {
            renderEnabled = false
            _state.update {
                it.copy(
                    render = IndoorRenderUi.Failed(failure, IndoorProblem(IndoorPart.RENDER, false, detail)),
                )
            }
        }

        override fun onStats(stats: RenderStats) = Unit
    }

    // -----------------------------------------------------------------------
    // Lebenslauf
    // -----------------------------------------------------------------------

    /**
     * Verbindet Trainer, Schalttaster und Puls und startet die Schleife.
     * Einmal je Laufzeit; spaetere Aufrufe tun nichts.
     */
    fun start() {
        if (started || finished) return
        started = true
        guard(IndoorPart.CORE) { trainer.start(trainerListener) }
        shifter?.let { s ->
            val ok = guard(IndoorPart.CORE) { s.start(shifterListener) }
            if (ok == null) _state.update { it.copy(shifter = ShifterConnection.UNSUPPORTED) }
        }
        heartRate?.let { hr -> guard(IndoorPart.BLE) { hr.acquire() } }
        if (autoLoop) {
            loopJob = scope.launch {
                while (isActive) {
                    val t0 = clock()
                    tickOnce()
                    val spent = clock() - t0
                    delay((TICK_MS - spent).coerceAtLeast(1L))
                }
            }
        }
    }

    /** Pause durch den Nutzer. */
    fun pause() = enqueue { it.pause() }

    /** Weiter nach einer Pause. */
    fun resume() = enqueue { it.resume() }

    /** Schaltknopf auf dem Bildschirm. */
    fun shift(event: ShifterEvent) {
        shiftEvents.add(event)
    }

    /** ERG-Ziel dauerhaft um [deltaPct] Prozentpunkte verschieben. */
    fun adjustErg(deltaPct: Int) = enqueue(replayBeforeStart = true) { it.adjustErg(deltaPct) }

    /** Reglerstand des Demo-Trainers. Ohne Demo-Trainer ohne Wirkung. */
    fun setDemoInput(powerW: Int, cadenceRpm: Int) {
        val demo = demoTrainer ?: return
        enqueue {
            demo.riderPowerW = powerW
            demo.riderCadenceRpm = cadenceRpm
        }
    }

    /**
     * Die App verlaesst den Vordergrund: Die Fahrt pausiert (kein
     * Vordergrunddienst im Prototyp, docs/indoor.md 11.2 Nr. 4).
     */
    fun onAppBackground() = enqueue { s ->
        if (s.state.phase == IndoorPhase.RUNNING || s.state.phase == IndoorPhase.AUTO_PAUSED) {
            s.pause()
            pausedByBackground = true
        }
    }

    /** Zurueck im Vordergrund: Eine nur durch den Hintergrund ausgeloeste Pause endet. */
    fun onAppForeground() = enqueue { s ->
        if (pausedByBackground) {
            pausedByBackground = false
            if (s.state.phase == IndoorPhase.PAUSED) s.resume()
        }
    }

    /** Blendet den aktuellen Hinweis aus. */
    fun dismissNotice() {
        synchronized(noticeLock) { noticeUntilMs = 0L }
        _state.update { it.copy(notice = null) }
    }

    /**
     * Ein Takt. Wird von der Schleife gerufen; Tests rufen ihn direkt
     * (`autoLoop = false`). Wirft nie.
     */
    fun tickOnce() {
        if (finished) return
        val now = clock()
        // Wuensche der Oberflaeche zuerst, damit eine Pause noch in diesem Takt wirkt.
        while (true) {
            val action = actions.poll() ?: break
            guard(IndoorPart.CORE) { action.block(session) }
            if (action.replayBeforeStart) preStartLog?.add(action.block)
        }
        guard(IndoorPart.CORE) { trainer.tick(now) }
        shifter?.let { s -> guard(IndoorPart.CORE) { s.tick(now) } }
        pendingCapabilities?.let { caps ->
            pendingCapabilities = null
            adoptCapabilities(caps)
        }
        while (true) {
            val event = shiftEvents.poll() ?: break
            guard(IndoorPart.CORE) { session.shift(event) }
            preStartLog?.add { s -> s.shift(event) }
        }
        if (now <= lastStepNow) {
            // Die Sitzung verlangt streng steigende Zeit; dieser Takt faellt aus.
            expireNotice(now)
            return
        }
        lastStepNow = now
        val hr = heartRate?.let { source -> guard(IndoorPart.BLE) { source.currentBpm() } }
        val input = SessionInput(
            telemetry = lastTelemetry,
            hrBpm = hr,
            trainerReady = trainerConnection == TrainerConnection.READY,
        )
        val step = guard(IndoorPart.CORE) { session.step(now, input) }
        if (step != null) {
            // Nach dem ersten Tritt (oder einer Pause) wird nicht mehr getauscht.
            if (step.state.phase != IndoorPhase.READY) preStartLog = null
            for (command in step.commands) {
                guard(IndoorPart.CORE) { trainer.send(command) }
            }
            pushToRenderer(now, step.state)
            _state.update { it.copy(session = step.state) }
        }
        expireNotice(now)
    }

    /**
     * Beendet die Fahrt: haelt die Schleife an, schliesst die Sitzung ab,
     * schickt dem Trainer [TrainerCommand.Stop], trennt Trainer und
     * Schalttaster und gibt den Puls frei.
     *
     * @return die Aufzeichnung, `null` wenn die Sitzung sie nicht liefern konnte
     *   (dann steht der Grund in [IndoorRuntimeState.problem]). Mehrfacher
     *   Aufruf liefert dasselbe.
     */
    suspend fun finish(): IndoorRideRecord? {
        loopJob?.cancelAndJoin()
        loopJob = null
        return finishNow()
    }

    private fun finishNow(): IndoorRideRecord? {
        if (finished) return record
        finished = true
        val result = guard(IndoorPart.CORE) { session.finish() }
        record = result
        guard(IndoorPart.CORE) { trainer.send(TrainerCommand.Stop) }
        guard(IndoorPart.CORE) { trainer.tick(clock()) }
        releaseDevices()
        if (renderEnabled && renderer != null) {
            val last = _state.value.session
            if (last != null) {
                runCatchingPart(IndoorPart.RENDER) { renderer.setState(worldStateOf(clock(), last, paused = true)) }
            }
        }
        _state.update { it.copy(finished = true, session = runCatching { session.state }.getOrNull() ?: it.session) }
        shutdownLoop()
        return result
    }

    /**
     * Verwirft die Fahrt ohne Aufzeichnung (Bildschirm verlassen, ViewModel
     * geraeumt). Idempotent.
     */
    fun close() {
        loopJob?.cancel()
        loopJob = null
        if (!finished) {
            finished = true
            guard(IndoorPart.CORE) { trainer.send(TrainerCommand.Stop) }
            releaseDevices()
            _state.update { it.copy(finished = true) }
        }
        shutdownLoop()
    }

    private var devicesReleased = false

    private fun releaseDevices() {
        if (devicesReleased) return
        devicesReleased = true
        guard(IndoorPart.CORE) { trainer.stop() }
        shifter?.let { s -> guard(IndoorPart.CORE) { s.stop() } }
        heartRate?.let { hr -> guard(IndoorPart.BLE) { hr.release() } }
    }

    private fun shutdownLoop() {
        scope.cancel()
        ownDispatcher?.close()
    }

    // -----------------------------------------------------------------------
    // Intern
    // -----------------------------------------------------------------------

    private class SessionAction(val replayBeforeStart: Boolean, val block: (IndoorSession) -> Unit)

    private fun enqueue(replayBeforeStart: Boolean = false, action: (IndoorSession) -> Unit) {
        if (finished) return
        actions.add(SessionAction(replayBeforeStart, action))
    }

    /**
     * Tauscht die Sitzung gegen eine mit [caps], solange sie noch vor dem
     * ersten Tritt steht (siehe Klassen-KDoc). Scheitert der Neubau, faehrt
     * die alte Sitzung mit der Annahme vom Start weiter — ohne Hinweis, die
     * Fahrt ist davon nicht beeintraechtigt.
     */
    private fun adoptCapabilities(caps: TrainerCapabilities) {
        val rebuild = rebuildSession ?: return
        val log = preStartLog ?: return
        val phase = indoorCatching(IndoorPart.CORE) { session.state.phase }.getOrNull()
        if (phase != IndoorPhase.READY) {
            preStartLog = null
            return
        }
        val fresh = indoorCatching(IndoorPart.CORE) { rebuild(caps) }.getOrNull() ?: return
        for (replay in log) guard(IndoorPart.CORE) { replay(fresh) }
        session = fresh
        // Der Trainer meldet seine Faehigkeiten je Verbindung neu; getauscht wird einmal.
        preStartLog = null
    }

    private fun pushToRenderer(now: Long, s: IndoorSessionState) {
        val r = renderer ?: return
        if (!renderEnabled) return
        val ok = runCatchingPart(IndoorPart.RENDER) { r.setState(worldStateOf(now, s, paused = s.phase != IndoorPhase.RUNNING)) }
        if (!ok) {
            renderEnabled = false
            val problem = _state.value.problem
            _state.update { it.copy(render = IndoorRenderUi.Failed(null, problem)) }
        }
    }

    private fun worldStateOf(now: Long, s: IndoorSessionState, paused: Boolean): WorldState {
        val cadence = (s.cadenceRpm ?: 0).toDouble()
        val ghost = s.ghost?.let { g ->
            ActorState(distM = g.distM, speedMps = g.speedMps, cadenceRpm = if (g.speedMps > 0.3) GHOST_CADENCE_RPM else 0.0)
        }
        return WorldState(
            timeMs = now,
            rider = ActorState(distM = s.distM, speedMps = s.speedMps, cadenceRpm = cadence),
            ghost = ghost,
            paused = paused,
        )
    }

    /** Fuehrt einen Aufruf eines fremden Teils aus; jeder Fehler wird zum Hinweis. */
    private inline fun <T> guard(part: IndoorPart, block: () -> T): T? {
        val result = indoorCatching(part, block)
        val error = result.exceptionOrNull()
        if (error != null) {
            val problem = problemOf(part, error)
            _state.update { it.copy(problem = problem) }
            postNotice(IndoorNotice.PART_FAILED)
            return null
        }
        return result.getOrNull()
    }

    /** Wie [guard], meldet aber nur Erfolg — fuer Aufrufe ohne Rueckgabewert. */
    private inline fun runCatchingPart(part: IndoorPart, block: () -> Unit): Boolean {
        val result = indoorCatching(part, block)
        val error = result.exceptionOrNull() ?: return true
        _state.update { it.copy(problem = problemOf(part, error)) }
        postNotice(IndoorNotice.PART_FAILED)
        return false
    }

    private fun postNotice(notice: IndoorNotice) {
        val until = runCatching { clock() }.getOrDefault(0L) + noticeDurationMs
        synchronized(noticeLock) { noticeUntilMs = until }
        _state.update { it.copy(notice = notice) }
    }

    private fun expireNotice(now: Long) {
        val expired = synchronized(noticeLock) { noticeUntilMs != 0L && now >= noticeUntilMs }
        if (expired) {
            synchronized(noticeLock) { noticeUntilMs = 0L }
            _state.update { it.copy(notice = null) }
        }
    }

    companion object {
        /** Abstand der Takte: 10 Hz. */
        const val TICK_MS: Long = 100L

        /** So lange bleibt ein Hinweis stehen, wenn niemand ihn wegtippt. */
        const val NOTICE_DURATION_MS: Long = 5_000L

        /** Gedachte Trittfrequenz des Geists fuer die Beinbewegung. */
        private const val GHOST_CADENCE_RPM = 85.0

        /** Ein eigener Einzelthread — der serielle Dispatcher der Schleife. */
        fun newIndoorLoopDispatcher(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "trailscape-indoor").apply { isDaemon = true } }
                .asCoroutineDispatcher()
    }
}

/**
 * Zustand der Laufzeit fuer das HUD.
 *
 * @property session letzter Zustand der Sitzung; `null` vor dem ersten Takt.
 * @property problem der letzte abgefangene Fehler eines Teils (Diagnose).
 */
data class IndoorRuntimeState(
    val session: IndoorSessionState?,
    val trainer: TrainerConnection,
    val capabilities: TrainerCapabilities,
    val shifter: ShifterConnection?,
    val shifterBatteryPct: Int?,
    val render: IndoorRenderUi,
    val notice: IndoorNotice?,
    val problem: IndoorProblem?,
    val finished: Boolean,
)

/**
 * Pulsquelle der Fahrt. In der App [BleHeartRateSource] (Gurt ueber
 * `BleSensors`), im Test abschaltbar (`null`) oder eine Attrappe.
 */
interface IndoorHeartRateSource {
    fun acquire()

    /** Aktueller Puls oder `null`, wenn keiner (frisch) vorliegt. */
    fun currentBpm(): Int?

    fun release()
}
