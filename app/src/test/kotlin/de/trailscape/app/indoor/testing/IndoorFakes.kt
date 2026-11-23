package de.trailscape.app.indoor.testing

import de.trailscape.app.indoor.IndoorHeartRateSource
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.TrainingSession
import de.trailscape.core.Waypoint
import de.trailscape.core.computeStats
import de.trailscape.core.haversineM
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.DemoTrainer
import de.trailscape.core.indoor.ErgState
import de.trailscape.core.indoor.ErgWorkout
import de.trailscape.core.indoor.GearState
import de.trailscape.core.indoor.GhostSpec
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorRideRecord
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.IndoorSession
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorShifter
import de.trailscape.core.indoor.IndoorStep
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.SessionInput
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.TrainerCapabilities
import de.trailscape.core.indoor.TrainerCommand
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.TrainerListener
import de.trailscape.core.indoor.TrainerTelemetry
import de.trailscape.core.indoor.VIRTUAL_GEAR_COUNT
import de.trailscape.core.indoor.effectiveCrr
import de.trailscape.core.indoor.sampleAt
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.GeoFetcher
import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.GeoResponse
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RasterDecoder
import de.trailscape.core.indoor.world.RenderDeviceInfo
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.SkySetting
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldBounds
import de.trailscape.core.indoor.world.WorldBudget
import de.trailscape.core.indoor.world.WorldBuildCancelled
import de.trailscape.core.indoor.world.WorldBuildDeps
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuildPhase
import de.trailscape.core.indoor.world.WorldBuilder
import de.trailscape.core.indoor.world.WorldLevel
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldPlan
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.indoor.world.WorldRendererListener
import de.trailscape.core.indoor.world.WorldState
import de.trailscape.core.indoor.world.WorldTrack
import de.trailscape.core.indoor.world.worldCellM
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * # Gemeinsame Attrappen der Indoor-Tests (Slice indoor-app)
 *
 * Gebaut gegen die **Vertragsschnittstellen** (`IndoorSession`,
 * `IndoorTrainer`, `WorldRenderer`, `WorldBuilder` …), nie gegen die
 * Einstiege der anderen Slices (docs/indoor.md, Abschnitt 2.2). Die Attrappen
 * sind absichtlich einfach und deterministisch: Zeit kommt nur ueber
 * `tick(nowMs)`/`step(nowMs)`.
 *
 * [RealOrFakeIndoorParts] nimmt die echten Teile aus `:core`, sobald sie nicht
 * mehr `NotImplementedError` werfen — so prueft derselbe Test nach dem
 * Zusammenfuehren die echten Teile ohne Umbau.
 */

// ---------------------------------------------------------------------------
// Strecken
// ---------------------------------------------------------------------------

/** Startpunkt der Test-Strecken (Koeln, im NRW-Rechteck). */
const val TEST_LAT = 50.94
const val TEST_LON = 6.96

/**
 * Eine einfache Strecke nach Norden: Punkte alle [stepM] Meter, sanfte Wellen
 * in der Hoehe, ein Belagswechsel in der Mitte.
 */
fun testIndoorRoute(lengthM: Double = 3_000.0, stepM: Double = 10.0, name: String = "Teststrecke"): IndoorRoute {
    val count = (lengthM / stepM).toInt() + 1
    val points = List(count) { i ->
        val d = minOf(i * stepM, lengthM)
        IndoorRoutePoint(
            distM = d,
            lat = TEST_LAT + Math.toDegrees(d / 6_371_000.0),
            lon = TEST_LON,
            eleM = 60.0 + 10.0 * sin(d / 400.0),
        )
    }.distinctBy { it.distM }
    val len = points.last().distM
    return IndoorRoute(
        id = "test-route-${len.toInt()}",
        name = name,
        points = points,
        surfaces = listOf(
            SurfaceSection(0.0, len / 2, SurfaceKind.ASPHALT),
            SurfaceSection(len / 2, len, SurfaceKind.GRAVEL),
        ),
        isLoop = false,
        source = IndoorRouteSource.NONE,
    )
}

/** Punkte einer gefahrenen Tour nach Norden, alle [stepM] Meter, mit Zeitstempeln (5 m/s). */
fun testTrackPoints(lengthM: Double = 2_000.0, stepM: Double = 20.0, startMs: Long = 1_760_000_000_000L): List<TrackPoint> {
    val count = (lengthM / stepM).toInt() + 1
    return List(count) { i ->
        val d = i * stepM
        TrackPoint(
            lat = TEST_LAT + Math.toDegrees(d / 6_371_000.0),
            lon = TEST_LON + 0.001 * sin(d / 300.0),
            ele = 55.0 + 5.0 * sin(d / 250.0),
            time = startMs + (d / 5.0 * 1000).toLong(),
        )
    }
}

/** Eine gespeicherte, gefahrene Tour fuer die Streckenwahl. */
fun testSavedRide(id: String = "tour-1", name: String = "Feierabendrunde", planned: Boolean = false): Ride {
    val points = testTrackPoints()
    return Ride(
        id = id,
        name = name,
        createdAt = points.first().time ?: 0L,
        stats = computeStats(points),
        points = points,
        planned = planned,
    )
}

/** Ein `IndoorRoute` aus Trackpunkten — die Attrappe von `buildIndoorRoute` (ohne Glaetten). */
fun fakeRouteFromPoints(name: String, points: List<TrackPoint>, source: IndoorRouteSource): IndoorRoute? {
    if (points.size < 2) return null
    val out = ArrayList<IndoorRoutePoint>()
    var dist = 0.0
    var prev: TrackPoint? = null
    for (p in points) {
        if (prev != null) {
            val step = haversineM(prev, p)
            if (step < 1.0) continue
            dist += step
        }
        out += IndoorRoutePoint(dist, p.lat, p.lon, p.ele ?: 0.0)
        prev = p
    }
    if (out.size < 2) return null
    return IndoorRoute(
        id = "fake-${name.hashCode()}-${out.size}",
        name = name,
        points = out,
        surfaces = listOf(SurfaceSection(0.0, dist, SurfaceKind.UNKNOWN)),
        isLoop = haversineM(points.first(), points.last()) <= IndoorRoute.LOOP_CLOSE_M,
        source = source,
    )
}

// ---------------------------------------------------------------------------
// Teile
// ---------------------------------------------------------------------------

/**
 * Attrappe aller fremden Teile. Jede Fabrik ist austauschbar; erzeugte
 * Objekte werden fuer die Pruefung gemerkt.
 */
class FakeIndoorParts(
    var demoTrainerFactory: () -> DemoTrainer = { FakeDemoTrainer() },
    var sessionFactory: (IndoorSessionConfig) -> IndoorSession = { FakeIndoorSession(it) },
    var worldBuilderFactory: () -> WorldBuilder = { FakeWorldBuilder() },
    var rendererFactory: () -> WorldRenderer = { FakeWorldRenderer() },
    var ergWorkoutFactory: (TrainingSession, String) -> ErgWorkout? = { _, _ -> null },
    var links: Map<IndoorDeviceRole, BleLink> = emptyMap(),
    var ftmsFactory: (BleLink) -> IndoorTrainer = { FakeDemoTrainer(name = it.deviceName ?: "FTMS") },
    var shifterFactory: (BleLink) -> IndoorShifter = { throw UnsupportedOperationException("kein Schalttaster") },
    var rematchFactory: (List<TrackPoint>) -> List<Waypoint> = { pts ->
        listOf(pts.first(), pts[pts.size / 2], pts.last()).map { Waypoint(it.lat, it.lon) }
    },
) : IndoorParts {
    val trainers = mutableListOf<IndoorTrainer>()
    val sessions = mutableListOf<IndoorSession>()
    val renderers = mutableListOf<WorldRenderer>()
    val builtRouteSources = mutableListOf<IndoorRouteSource>()

    override fun buildRoute(
        name: String,
        points: List<TrackPoint>,
        waySections: List<RouteWaySection>?,
        source: IndoorRouteSource,
    ): IndoorRoute? {
        builtRouteSources += source
        return fakeRouteFromPoints(name, points, if (waySections == null) IndoorRouteSource.NONE else source)
    }

    override fun rematchWaypoints(points: List<TrackPoint>): List<Waypoint> = rematchFactory(points)

    override fun riderSetup(profile: TrainingProfile): RiderSetup = RiderSetup(
        riderMassKg = profile.weightKg,
        bikeMassKg = profile.setupMassKg,
        cdaM2 = profile.cda,
        profileCrr = profile.crr,
        ftpW = profile.eftpW,
    )

    override fun ergWorkout(session: TrainingSession, title: String): ErgWorkout? = ergWorkoutFactory(session, title)

    override fun createSession(config: IndoorSessionConfig): IndoorSession = sessionFactory(config).also { sessions += it }

    override fun createDemoTrainer(): DemoTrainer = demoTrainerFactory().also { trainers += it }

    override fun createFtmsTrainer(link: BleLink): IndoorTrainer = ftmsFactory(link).also { trainers += it }

    override fun createShifter(link: BleLink): IndoorShifter = shifterFactory(link)

    override fun openLink(role: IndoorDeviceRole): BleLink? = links[role]

    override fun worldBuilder(): WorldBuilder = worldBuilderFactory()

    override fun geoPipeline(): GeoFetcher = GeoFetcher { GeoResponse(404, null, ByteArray(0)) }

    override fun rasterDecoder(): RasterDecoder = RasterDecoder { null }

    override fun createRenderer(): WorldRenderer = rendererFactory().also { renderers += it }
}

/** Ruft [real]; wirft es `NotImplementedError` (Platzhalter), gilt [fake]. */
inline fun <T> realOrFake(real: () -> T, fake: () -> T): T = try {
    real()
} catch (e: NotImplementedError) {
    fake()
}

/**
 * Die echten Kern- und Welt-Teile, wo es sie schon gibt — sonst die
 * Attrappen aus [fake]. Funk und Darstellung bleiben Attrappen (sie brauchen
 * ein Geraet).
 */
class RealOrFakeIndoorParts(private val fake: FakeIndoorParts = FakeIndoorParts()) : IndoorParts by fake {
    /** Welche Teile echt waren — fuer Meldungen im Test. */
    val realParts = mutableSetOf<String>()

    private inline fun <T> pick(label: String, real: () -> T, fallback: () -> T): T {
        var isReal = true
        val value = realOrFake(real) {
            isReal = false
            fallback()
        }
        if (isReal) realParts += label
        return value
    }

    override fun buildRoute(
        name: String,
        points: List<TrackPoint>,
        waySections: List<RouteWaySection>?,
        source: IndoorRouteSource,
    ): IndoorRoute? = pick(
        "buildIndoorRoute",
        { de.trailscape.core.indoor.buildIndoorRoute(name, points, waySections, source) },
        { fake.buildRoute(name, points, waySections, source) },
    )

    override fun rematchWaypoints(points: List<TrackPoint>): List<Waypoint> = pick(
        "surfaceRematchWaypoints",
        { de.trailscape.core.indoor.surfaceRematchWaypoints(points) },
        { fake.rematchWaypoints(points) },
    )

    override fun riderSetup(profile: TrainingProfile): RiderSetup = pick(
        "riderSetupFrom",
        { de.trailscape.core.indoor.riderSetupFrom(profile) },
        { fake.riderSetup(profile) },
    )

    override fun createSession(config: IndoorSessionConfig): IndoorSession = pick(
        "createIndoorSession",
        { de.trailscape.core.indoor.createIndoorSession(config).also { fake.sessions += it } },
        { fake.createSession(config) },
    )

    override fun createDemoTrainer(): DemoTrainer = pick(
        "createDemoTrainer",
        { de.trailscape.core.indoor.createDemoTrainer().also { fake.trainers += it } },
        { fake.createDemoTrainer() },
    )

    override fun worldBuilder(): WorldBuilder {
        val fakeBuilder = fake.worldBuilder()
        val real = realOrFake({ de.trailscape.core.indoor.world.createWorldBuilder() }, { null })
            ?: return fakeBuilder
        realParts += "createWorldBuilder"
        return RealOrFakeWorldBuilder(real, fakeBuilder)
    }
}

/** Je Methode der echte Weltbau, bei `NotImplementedError` die Attrappe. */
class RealOrFakeWorldBuilder(private val real: WorldBuilder, private val fake: WorldBuilder) : WorldBuilder {
    override fun plan(route: IndoorRoute, quality: IndoorQuality): WorldPlan =
        realOrFake({ real.plan(route, quality) }, { fake.plan(route, quality) })

    override fun build(plan: WorldPlan, deps: WorldBuildDeps, outDir: File, listener: WorldBuildListener): WorldManifest =
        realOrFake({ real.build(plan, deps, outDir, listener) }, { fake.build(plan, deps, outDir, listener) })

    override fun buildTestWorld(outDir: File, quality: IndoorQuality): WorldManifest =
        realOrFake({ real.buildTestWorld(outDir, quality) }, { fake.buildTestWorld(outDir, quality) })
}

// ---------------------------------------------------------------------------
// Trainer
// ---------------------------------------------------------------------------

/**
 * Demo-Trainer-Attrappe: `CONNECTING` beim Start, `READY` nach
 * [readyAfterMs], dann je Sekunde ein Messwert mit Reglerstand. Merkt sich
 * alle Befehle.
 */
class FakeDemoTrainer(
    override val name: String = "Demo-Trainer",
    private val readyAfterMs: Long = 1_000L,
) : DemoTrainer {
    @Volatile override var riderPowerW: Int = 180

    @Volatile override var riderCadenceRpm: Int = 85
    val commands = mutableListOf<TrainerCommand>()
    var listener: TrainerListener? = null
        private set
    var ticks = 0
        private set
    var stopped = false
        private set
    private var startedAtMs: Long? = null
    private var lastTelemetryMs: Long? = null
    private var ready = false
    var ergTargetW: Int? = null
        private set

    override fun start(listener: TrainerListener) {
        this.listener = listener
        listener.onConnection(TrainerConnection.CONNECTING, TrainerCapabilities.NONE)
    }

    override fun send(command: TrainerCommand) {
        commands += command
        if (command is TrainerCommand.SetTargetPower) ergTargetW = command.watts
    }

    override fun tick(nowMs: Long) {
        ticks++
        val l = listener ?: return
        val start = startedAtMs ?: nowMs.also { startedAtMs = it }
        if (!ready && nowMs - start >= readyAfterMs) {
            ready = true
            l.onConnection(TrainerConnection.READY, TrainerCapabilities(simulation = true, targetPower = true))
        }
        if (ready && (lastTelemetryMs == null || nowMs - lastTelemetryMs!! >= 1_000L)) {
            lastTelemetryMs = nowMs
            l.onTelemetry(TrainerTelemetry(ergTargetW ?: riderPowerW, riderCadenceRpm.toDouble(), 30.0, nowMs))
        }
    }

    /** Simuliert den Verlust der Verbindung (BLE-Abbruch). */
    fun dropConnection() {
        ready = false
        startedAtMs = null
        listener?.onConnection(TrainerConnection.CONNECTING, TrainerCapabilities.NONE)
    }

    override fun stop() {
        stopped = true
        listener = null
    }
}

// ---------------------------------------------------------------------------
// Sitzung
// ---------------------------------------------------------------------------

/**
 * Sitzungs-Attrappe mit grober Physik: Tempo proportional zur Leistung
 * (180 W ≈ 8 m/s), ein Punkt je gefahrener Sekunde, Befehle wie im Vertrag
 * (`Start` beim ersten Tritt, `SetSimulation` hoechstens einmal je Sekunde).
 */
class FakeIndoorSession(val config: IndoorSessionConfig) : IndoorSession {
    val shifts = mutableListOf<ShifterEvent>()
    var ergDeltaPct = 0
        private set
    var stepCount = 0
        private set
    var finishCalls = 0
        private set

    /** Auf `true` setzen, damit der naechste Schritt wirft (Fehler eines Teils). */
    var throwOnNextStep = false

    private var phase = IndoorPhase.READY
    private var lastNow: Long? = null
    private var distM = 0.0
    private var speedMps = 0.0
    private var elapsedMs = 0L
    private var movingMs = 0L
    private var nextPointMs = 0L
    private var lastSimCommandMs: Long? = null
    private var gear = 12
    private var lastPower: Int? = null
    private var lastCadence: Int? = null
    private var lastHr: Int? = null
    private var workJ = 0.0
    private var powerSumS = 0.0
    private var powerSamples = 0
    private val points = mutableListOf<TrackPoint>()
    private var record: IndoorRideRecord? = null

    private var current: IndoorSessionState = buildState(0L)

    override val state: IndoorSessionState get() = current

    override fun step(nowMs: Long, input: SessionInput): IndoorStep {
        stepCount++
        if (throwOnNextStep) {
            throwOnNextStep = false
            throw IllegalStateException("Attrappe: Schritt scheitert absichtlich")
        }
        if (phase == IndoorPhase.FINISHED) return IndoorStep(state, emptyList())
        val prev = lastNow
        require(prev == null || nowMs > prev) { "nowMs muss streng steigen" }
        val dtMs = if (prev == null) 0L else (nowMs - prev).coerceAtMost(2_000L)
        lastNow = nowMs
        val commands = mutableListOf<TrainerCommand>()
        val power = input.telemetry?.powerW
        lastPower = power
        lastCadence = input.telemetry?.cadenceRpm?.toInt()
        lastHr = input.hrBpm
        val pedaling = (power ?: 0) > 0
        when (phase) {
            IndoorPhase.READY, IndoorPhase.AUTO_PAUSED -> if (pedaling && input.trainerReady) {
                if (phase == IndoorPhase.READY) commands += TrainerCommand.Start
                phase = IndoorPhase.RUNNING
            }
            IndoorPhase.RUNNING -> if (!input.trainerReady) phase = IndoorPhase.AUTO_PAUSED
            else -> Unit
        }
        if (phase != IndoorPhase.READY) elapsedMs += dtMs
        if (phase == IndoorPhase.RUNNING) {
            speedMps = if (pedaling) 8.0 * (power!! / 180.0) else 0.0
            distM += speedMps * dtMs / 1000.0
            movingMs += dtMs
            workJ += (power ?: 0) * dtMs / 1000.0
            powerSumS += (power ?: 0) * dtMs / 1000.0
            powerSamples++
            if (movingMs >= nextPointMs) {
                nextPointMs += 1_000L
                val s = config.route.sampleAt(distM)
                points += TrackPoint(
                    lat = s.lat,
                    lon = s.lon,
                    ele = s.eleM,
                    time = config.startedAtEpochMs + elapsedMs,
                    hr = lastHr,
                    power = power,
                    cad = lastCadence,
                )
            }
            if (config.mode == IndoorMode.SIM && (lastSimCommandMs == null || nowMs - lastSimCommandMs!! >= 1_000L)) {
                lastSimCommandMs = nowMs
                val s = config.route.sampleAt(distM)
                commands += TrainerCommand.SetSimulation(0.0, s.gradePct, effectiveCrr(s.surface.kind, config.rider.profileCrr), 0.4)
            }
        } else {
            speedMps = 0.0
        }
        if (config.route.isLoop.not() && distM >= config.route.lengthM) phase = IndoorPhase.FINISHED
        current = buildState(nowMs)
        return IndoorStep(current, commands)
    }

    override fun shift(event: ShifterEvent) {
        shifts += event
        gear = (gear + if (event == ShifterEvent.UP) 1 else -1).coerceIn(1, VIRTUAL_GEAR_COUNT)
    }

    override fun adjustErg(deltaPct: Int) {
        ergDeltaPct += deltaPct
    }

    override fun pause() {
        if (phase == IndoorPhase.RUNNING || phase == IndoorPhase.AUTO_PAUSED) phase = IndoorPhase.PAUSED
        current = buildState(lastNow ?: 0L)
    }

    override fun resume() {
        if (phase == IndoorPhase.PAUSED) phase = IndoorPhase.RUNNING
        current = buildState(lastNow ?: 0L)
    }

    override fun finish(): IndoorRideRecord {
        finishCalls++
        record?.let { return it }
        phase = IndoorPhase.FINISHED
        current = buildState(lastNow ?: 0L)
        val r = IndoorRideRecord(
            startedAtEpochMs = config.startedAtEpochMs,
            routeId = config.route.id,
            routeName = config.route.name,
            mode = config.mode,
            points = points.toList(),
            stats = if (points.size >= 2) computeStats(points) else RideStats(0.0, 0.0, 0.0),
            totals = state.totals,
            laps = state.lap,
            ghostFinalGapS = state.ghost?.gapS,
        )
        record = r
        return r
    }

    private fun buildState(nowMs: Long): IndoorSessionState {
        val route = config.route
        val s = route.sampleAt(distM)
        val movingS = (movingMs / 1000).toInt()
        val ghost = when (val g = config.ghost) {
            GhostSpec.None -> null
            is GhostSpec.ConstantPower -> {
                val gDist = 8.0 * (g.watts / 180.0) * movingMs / 1000.0
                GhostState(gDist, 8.0 * (g.watts / 180.0), gDist - distM, null)
            }
            is GhostSpec.PastRide -> GhostState(distM, speedMps, 0.0, 0.0)
        }
        return IndoorSessionState(
            phase = phase,
            mode = config.mode,
            nowMs = nowMs,
            elapsedS = (elapsedMs / 1000).toInt(),
            distM = distM,
            routeDistM = s.distM,
            lap = if (route.isLoop && route.lengthM > 0) (distM / route.lengthM).toInt() + 1 else 1,
            speedMps = speedMps,
            powerW = lastPower,
            cadenceRpm = lastCadence,
            hrBpm = lastHr,
            gradePct = s.gradePct,
            surface = s.surface.kind,
            crr = effectiveCrr(s.surface.kind, config.rider.profileCrr),
            headwindMps = 0.0,
            gear = GearState(gear, VIRTUAL_GEAR_COUNT, 0.8 + gear * 0.15),
            ghost = ghost,
            erg = if (config.mode == IndoorMode.ERG) ErgState(0, 150, 60, false) else null,
            totals = IndoorTotals(
                distanceM = distM,
                ascentM = 0.0,
                movingS = movingS,
                avgPowerW = if (movingMs > 0) powerSumS / (movingMs / 1000.0) else null,
                normalizedPowerW = null,
                avgCadenceRpm = lastCadence?.toDouble(),
                avgHrBpm = lastHr?.toDouble(),
                workKj = workJ / 1000.0,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Darstellung
// ---------------------------------------------------------------------------

/**
 * Darstellungs-Attrappe. Mit [failure] meldet sie beim Laden `onFailure`
 * (wie der Platzhalter von indoor-render), sonst `onLoading` und `onReady`.
 */
class FakeWorldRenderer(
    private val failure: RenderFailure? = null,
    var throwOnSetState: Boolean = false,
) : WorldRenderer {
    var listener: WorldRendererListener? = null
        private set
    val states = mutableListOf<WorldState>()
    var loadedDir: File? = null
        private set
    var camera: CameraMode = CameraMode.CHASE
        private set
    var destroyed = false
        private set

    override fun setListener(listener: WorldRendererListener?) {
        this.listener = listener
    }

    override fun loadWorld(worldDir: File, manifest: WorldManifest) {
        loadedDir = worldDir
        val l = listener ?: return
        if (failure != null) {
            l.onFailure(failure, "Attrappe: Darstellung scheitert absichtlich")
        } else {
            l.onLoading(0, 2)
            l.onReady(RenderDeviceInfo("Testgeraet", "15", "OpenGL", "", "", "", "1.75.1"))
        }
    }

    override fun loadTestScene() = Unit

    override fun unloadWorld() = Unit

    override fun setState(state: WorldState) {
        if (throwOnSetState) throw IllegalStateException("Attrappe: setState scheitert absichtlich")
        synchronized(states) { states += state }
    }

    override fun setCamera(mode: CameraMode) {
        camera = mode
    }

    override fun setSky(sky: SkySetting) = Unit

    override fun setSettings(settings: RenderSettings) = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun destroy() {
        destroyed = true
        listener = null
    }
}

// ---------------------------------------------------------------------------
// Weltbau
// ---------------------------------------------------------------------------

/**
 * Weltbau-Attrappe: schreibt ein echtes `manifest.json` (ohne Kacheln), meldet
 * Fortschritt je Phase und kann gezielt scheitern.
 */
class FakeWorldBuilder(
    var planError: Throwable? = null,
    var buildError: Throwable? = null,
    var testWorldError: Throwable? = null,
) : WorldBuilder {
    var buildCalls = 0
        private set
    val progress = mutableListOf<WorldBuildPhase>()

    override fun plan(route: IndoorRoute, quality: IndoorQuality): WorldPlan {
        planError?.let { throw it }
        return WorldPlan(
            worldId = "welt-${route.id}-${quality.name.lowercase()}",
            route = route,
            quality = quality,
            tileCount = 4,
            requests = mapOf(GeoSourceKind.ELEVATION to 4, GeoSourceKind.VECTOR to 4),
            downloadBytes = 1_000_000L,
            diskBytes = 2_000_000L,
            hosts = listOf("s3.amazonaws.com", "tiles.openfreemap.org"),
            aerial = emptyList(),
        )
    }

    override fun build(plan: WorldPlan, deps: WorldBuildDeps, outDir: File, listener: WorldBuildListener): WorldManifest {
        buildCalls++
        buildError?.let { throw it }
        for (phase in WorldBuildPhase.entries) {
            if (listener.isCancelled()) throw WorldBuildCancelled()
            listener.onProgress(phase, 1, 1)
            progress += phase
        }
        val manifest = manifestFor(plan.worldId, plan.route, plan.quality)
        writeManifest(outDir, manifest)
        return manifest
    }

    override fun buildTestWorld(outDir: File, quality: IndoorQuality): WorldManifest {
        testWorldError?.let { throw it }
        val manifest = manifestFor("testwelt", testLoopRoute(), quality)
        writeManifest(outDir, manifest)
        return manifest
    }

    companion object {
        /** Eine Kreisrunde von 3 km um den Testpunkt. */
        fun testLoopRoute(lengthM: Double = 3_000.0, stepM: Double = 5.0): IndoorRoute {
            val r = lengthM / (2 * PI)
            val count = (lengthM / stepM).toInt()
            val origin = GeoOrigin(TEST_LAT, TEST_LON, 50.0)
            val points = List(count + 1) { i ->
                val d = minOf(i * stepM, lengthM)
                val a = d / r
                IndoorRoutePoint(
                    distM = d,
                    lat = origin.toLat(r * sin(a)),
                    lon = origin.toLon(r * (1 - cos(a))),
                    eleM = 50.0 + 8.0 * sin(a * 2),
                )
            }
            return IndoorRoute(
                id = "testwelt-runde",
                name = "Testwelt",
                points = points,
                surfaces = listOf(
                    SurfaceSection(0.0, 1_000.0, SurfaceKind.ASPHALT),
                    SurfaceSection(1_000.0, 2_000.0, SurfaceKind.GRAVEL),
                    SurfaceSection(2_000.0, lengthM, SurfaceKind.DIRT),
                ),
                isLoop = true,
                source = IndoorRouteSource.BROUTER_WAY_TAGS,
            )
        }

        /** Ein Manifest ohne Kacheln, dessen Streckenband [route] mit 5 m Proben abbildet. */
        fun manifestFor(worldId: String, route: IndoorRoute, quality: IndoorQuality): WorldManifest {
            val first = route.points.first()
            val origin = GeoOrigin(first.lat, first.lon, first.eleM)
            val stepM = WorldTrack.DEFAULT_STEP_M
            val length = route.lengthM
            val count = (length / stepM).toInt() + 1
            val dists = (List(count) { i -> minOf(i * stepM, length) } + length).distinct()
            val e = FloatArray(dists.size)
            val n = FloatArray(dists.size)
            val u = FloatArray(dists.size)
            dists.forEachIndexed { i, d ->
                // Am Ende einer Runde nicht auf den Start falten.
                val s = if (route.isLoop && d >= length) route.sampleAt(length - 1e-6) else route.sampleAt(d)
                e[i] = origin.toEast(s.lon).toFloat()
                n[i] = origin.toNorth(s.lat).toFloat()
                u[i] = origin.toUp(s.eleM).toFloat()
            }
            val track = WorldTrack(
                lengthM = length,
                stepM = stepM,
                loop = route.isLoop,
                e = e,
                n = n,
                u = u,
                surfaces = route.surfaces,
                source = route.source,
            )
            return WorldManifest(
                worldId = worldId,
                builder = "fake-world/1",
                createdAtMs = 1_760_000_000_000L,
                quality = quality,
                routeId = route.id,
                routeName = route.name,
                origin = origin,
                bounds = WorldBounds(
                    minE = (e.minOrNull() ?: 0f).toDouble(),
                    minN = (n.minOrNull() ?: 0f).toDouble(),
                    maxE = (e.maxOrNull() ?: 0f).toDouble(),
                    maxN = (n.maxOrNull() ?: 0f).toDouble(),
                    minU = (u.minOrNull() ?: 0f).toDouble(),
                    maxU = (u.maxOrNull() ?: 0f).toDouble(),
                ),
                track = track,
                levels = listOf(WorldLevel(0, worldCellM(0), quality.level0TexturePx)),
                tiles = emptyList(),
                budget = WorldBudget(0L, 0, 0L),
                attribution = listOf(
                    WorldAttribution(
                        GeoSourceKind.VECTOR,
                        "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
                        "ODbL",
                        "https://www.openstreetmap.org/copyright",
                    ),
                ),
            )
        }

        fun writeManifest(outDir: File, manifest: WorldManifest) {
            outDir.mkdirs()
            File(outDir, WorldManifest.FILE_NAME).writeText(manifest.toJson(), Charsets.UTF_8)
        }
    }
}

// ---------------------------------------------------------------------------
// Puls
// ---------------------------------------------------------------------------

/** Pulsquelle mit festem Wert; merkt sich Belegen und Freigeben. */
class FakeHeartRate(var bpm: Int? = 130) : IndoorHeartRateSource {
    var acquired = 0
        private set
    var released = 0
        private set

    override fun acquire() {
        acquired++
    }

    override fun currentBpm(): Int? = bpm

    override fun release() {
        released++
    }
}

/** Eine Uhr fuer Tests: steht, bis sie vorgestellt wird. */
class FakeClock(var nowMs: Long = 10_000L) : () -> Long {
    override fun invoke(): Long = nowMs

    fun advance(ms: Long) {
        nowMs += ms
    }
}
