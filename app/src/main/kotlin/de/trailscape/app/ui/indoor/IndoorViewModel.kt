package de.trailscape.app.ui.indoor

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.BleHeartRateSource
import de.trailscape.app.indoor.IndoorHeartRateSource
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.app.indoor.IndoorProblem
import de.trailscape.app.indoor.IndoorRematchRouter
import de.trailscape.app.indoor.IndoorRideRuntime
import de.trailscape.app.indoor.IndoorRouteSourcing
import de.trailscape.app.indoor.IndoorRuntimeState
import de.trailscape.app.indoor.MIN_INDOOR_RIDE_S
import de.trailscape.app.indoor.OfflineFirstRematchRouter
import de.trailscape.app.indoor.indoorCatching
import de.trailscape.app.indoor.indoorProfileOf
import de.trailscape.app.indoor.indoorRideToRide
import de.trailscape.app.indoor.problemOf
import de.trailscape.app.indoor.technicalDetailOf
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.indoor.worldRouteOf
import de.trailscape.app.sensors.indoor.IndoorDeviceRef
import de.trailscape.app.sensors.indoor.IndoorDevices
import de.trailscape.core.KeyValueStore
import de.trailscape.core.Ride
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.sessionTitle
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.DemoTrainer
import de.trailscape.core.indoor.ErgWorkout
import de.trailscape.core.indoor.GhostSpec
import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorRideRecord
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.IndoorShifter
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.TrainerCapabilities
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.WorldBuildCancelled
import de.trailscape.core.indoor.world.WorldBuildDeps
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuildPhase
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldPlan
import de.trailscape.core.indoor.world.WorldRenderer
import de.trailscape.core.sessionsForDay
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # Zustand und Ablauf des Indoor-Trainings (docs/indoor.md, Abschnitt 10.1)
 *
 * Koppeln → Strecke und Modus → Welt laden → Fahren → Auswertung. Die
 * Bildschirme unter `ui/indoor/` sind zustandslos; sie lesen die StateFlows
 * hier ([stage], [pair], [route], [world], [ride], [summary]) und melden
 * Aktionen ueber die oeffentlichen Methoden.
 *
 * ## Lebensdauer
 * An den Backstack-Eintrag `indoor` gebunden: ueberlebt Drehung, endet mit dem
 * Verlassen des Ablaufs ([onCleared] verwirft eine laufende Fahrt ohne
 * Aufzeichnung und gibt Trainer, Schalttaster, Puls und Darstellung frei).
 * **Nach einem Prozesstod** beginnt der Ablauf neu bei „Koppeln" — eine
 * Fahrt wird nicht wiederhergestellt (ohne Vordergrunddienst pausiert sie im
 * Hintergrund ohnehin, docs/indoor.md 11.2 Nr. 4). Gemerkt wird allein der
 * Demo-Schalter ([KEY_DEMO_TRAINER]), das Kettenblatt am Trainer
 * ([KEY_CHAINRING_TEETH]) und die Gangtabelle ([KEY_GEAR_TABLE]), alle im [store].
 *
 * ## Fremde Teile
 * Alles aus anderen Slices kommt ueber [parts] und steckt in [indoorCatching]:
 * Ein Platzhalter oder Fehler wird zu einer sichtbaren Meldung (`Failed`,
 * `RouteUnavailable`, `startProblem`, Hinweis im HUD), nie zum Absturz. Ohne
 * Welt faehrt man mit HUD und Hoehenprofil.
 *
 * ## Netz
 * Netzverkehr entsteht erst mit [loadWorld] (Nutzeraktion „Welt laden"):
 * Belagsabgleich ueber den vorhandenen Router, dann der Weltbau ueber die
 * Geodaten-Huelle. Der Voranschlag ([IndoorWorldUi.Plan]) rechnet ohne Netz.
 *
 * ## Injektion
 * Alle Abhaengigkeiten kommen ueber den Konstruktor; die App baut ihn ueber
 * [factory]. Tests reichen Attrappen, einen eigenen [scope]
 * (z. B. `Dispatchers.Unconfined`), eine Fake-Uhr und `autoLoop = false`
 * herein und treiben die Fahrt ueber [activeRuntime].
 *
 * @param profile das aktuelle Trainingsprofil (Masse, CdA, Crr, FTP).
 * @param plan der aktuelle Trainingsplan fuer die Einheit des Tages, `null` = keiner.
 * @param devices die gemerkten Indoor-Geraete (`IndoorDevices.remembered`).
 * @param heartRate liefert je Fahrt eine Pulsquelle, `null` = ohne Puls.
 * @param io Dispatcher fuer Dateien, Weltbau und Routing.
 * @param clock monotone Uhr der Fahrt.
 * @param wallClock Wanduhr (Startzeit der Aufzeichnung, Einheit des Tages).
 * @param runtimeDispatcher serieller Dispatcher der Fahrt-Schleife, `null` = eigener Thread.
 * @param autoLoop `false` = keine Schleife; Tests rufen `tickOnce()` selbst.
 * @param onRideSaved meldet die ID einer gespeicherten Indoor-Tour (die
 *   Integration laedt damit die Touren im `AppViewModel` neu).
 */
class IndoorViewModel(
    private val parts: IndoorParts,
    private val rideStorage: RideStorage,
    private val worlds: WorldLibrary,
    private val store: KeyValueStore,
    private val texts: IndoorTexts,
    private val profile: () -> TrainingProfile,
    private val plan: () -> TrainingPlan?,
    private val coreTexts: () -> CoreTexts,
    devices: Flow<Map<IndoorDeviceRole, IndoorDeviceRef>> = flowOf(emptyMap()),
    rematchRouter: IndoorRematchRouter? = null,
    private val heartRate: () -> IndoorHeartRateSource? = { null },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val runtimeDispatcher: CoroutineDispatcher? = null,
    private val autoLoop: Boolean = true,
    scope: CoroutineScope? = null,
    private val quality: IndoorQuality = IndoorQuality.MEDIUM,
    var onRideSaved: (String) -> Unit = {},
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope
    private val sourcing = IndoorRouteSourcing(parts, rematchRouter)

    // -----------------------------------------------------------------------
    // Oeffentliche Zustaende
    // -----------------------------------------------------------------------

    private val _stage = MutableStateFlow(IndoorStage.PAIR)
    val stage: StateFlow<IndoorStage> = _stage.asStateFlow()

    private var remembered: Map<IndoorDeviceRole, IndoorDeviceRef> = emptyMap()
    private val _pair = MutableStateFlow(
        IndoorPairUi(
            demoTrainer = readDemoFlag(),
            trainerName = null,
            shifterName = null,
            chainringTeeth = readChainringTeeth(),
            gearTable = readGearTable(),
        ),
    )
    val pair: StateFlow<IndoorPairUi> = _pair.asStateFlow()

    private val _route = MutableStateFlow(
        IndoorRouteUi(
            options = listOf(IndoorRouteOption.TestWorld),
            selected = null,
            mode = IndoorMode.SIM,
            ergTitle = null,
            ergMinutes = null,
            ghost = IndoorGhostOption.None,
            ghostPastRideAvailable = false,
            defaultGhostWatts = DEFAULT_GHOST_WATTS,
            loadingOptions = true,
        ),
    )
    val route: StateFlow<IndoorRouteUi> = _route.asStateFlow()

    private val _world = MutableStateFlow<IndoorWorldUi>(IndoorWorldUi.Preparing)
    val world: StateFlow<IndoorWorldUi> = _world.asStateFlow()

    private val _ride = MutableStateFlow<IndoorRideUi?>(null)
    val ride: StateFlow<IndoorRideUi?> = _ride.asStateFlow()

    private val _summary = MutableStateFlow<IndoorSummaryUi?>(null)
    val summary: StateFlow<IndoorSummaryUi?> = _summary.asStateFlow()

    private val _renderer = MutableStateFlow<WorldRenderer?>(null)

    /** Die Darstellung der laufenden Fahrt fuer `WorldSurface`, `null` ohne Welt. */
    val renderer: StateFlow<WorldRenderer?> = _renderer.asStateFlow()

    /** Die Laufzeit der laufenden Fahrt — fuer Tests (Takt von Hand). */
    var activeRuntime: IndoorRideRuntime? = null
        private set

    // -----------------------------------------------------------------------
    // Innerer Zustand des Ablaufs
    // -----------------------------------------------------------------------

    private var ergWorkout: ErgWorkout? = null
    private var routeName: String = ""
    private var selectedRide: Ride? = null

    /** Die Strecke ohne Welt (aus der Tour bzw. dem Streckenband). */
    private var baseRoute: IndoorRoute? = null
    private var worldPlan: WorldPlan? = null
    private var worldManifest: WorldManifest? = null
    private var worldDir: File? = null

    /**
     * Der laufende Welt-Auftrag (Aufbereiten oder Bau). Jeder Auftrag hat sein
     * eigenes Token: Ergebnisse sammelt er darin und uebernimmt sie erst auf
     * dem Hauptthread — und nur, solange er noch der aktuelle ist. Ein
     * abgeloester Auftrag (Zurueck, andere Strecke, neuer Bau) schreibt so nie
     * in die gemeinsamen Felder oder in [world].
     */
    @Volatile private var worldToken: WorldJobToken? = null
    private var worldJob: Job? = null

    private class WorldJobToken {
        /** Abbruch fuer den Weltbau ([WorldBuildListener.isCancelled]). */
        @Volatile var stopRequested = false
        var routeName: String? = null
        var ride: Ride? = null
        var baseRoute: IndoorRoute? = null
        var plan: WorldPlan? = null
        var manifest: WorldManifest? = null
        var dir: File? = null
    }

    // Fahrt
    private var rideCollectJob: Job? = null
    private var rideStatic: RideStatic? = null
    private var finishing = false
    private var pendingRide: Ride? = null

    /** Was sich waehrend der Fahrt nicht aus der Laufzeit ergibt. */
    private data class RideStatic(
        val routeName: String,
        val trainerName: String,
        val profile: IndoorProfileUi?,
        val camera: CameraMode,
        val demoPowerW: Int?,
        val demoCadenceRpm: Int?,
        val ghostLabel: String?,
        val ergTitle: String?,
        val attribution: List<de.trailscape.core.indoor.world.WorldAttribution>,
        val mode: IndoorMode,
    )

    init {
        this.scope.launch {
            devices.collect { map ->
                remembered = map
                publishPair()
            }
        }
    }

    // -----------------------------------------------------------------------
    // 1. Koppeln
    // -----------------------------------------------------------------------

    /** Schalter „Demo-Trainer" — gemerkt in den Einstellungen. */
    fun setDemoTrainer(enabled: Boolean) {
        runCatching { store.setString(KEY_DEMO_TRAINER, enabled.toString()) }
        _pair.update { it.copy(demoTrainer = enabled) }
    }

    /**
     * Kettenblatt am Trainer in Zaehnen — gemerkt in den Einstellungen und auf
     * [CHAINRING_MIN_TEETH]..[CHAINRING_MAX_TEETH] begrenzt. Bezug der
     * virtuellen Gaenge: `RiderSetup.realGearRatio = Kettenblatt / ZWIFT_COG_TEETH`.
     */
    fun setChainringTeeth(teeth: Int) {
        val clamped = teeth.coerceIn(CHAINRING_MIN_TEETH, CHAINRING_MAX_TEETH)
        runCatching { store.setString(KEY_CHAINRING_TEETH, clamped.toString()) }
        _pair.update { it.copy(chainringTeeth = clamped) }
    }

    /**
     * Gangtabelle der virtuellen Gaenge (`RiderSetup.gearTable`) — gemerkt in
     * den Einstellungen; gespeichert wird der Enum-Name.
     */
    fun setGearTable(table: VirtualGearTable) {
        runCatching { store.setString(KEY_GEAR_TABLE, table.name) }
        _pair.update { it.copy(gearTable = table) }
    }

    /** Weiter zur Streckenwahl (nur wenn [IndoorPairUi.canContinue]). */
    fun continueFromPair() {
        if (!_pair.value.canContinue) return
        _stage.value = IndoorStage.ROUTE
        loadRouteOptions()
    }

    private fun readDemoFlag(): Boolean = runCatching { store.getString(KEY_DEMO_TRAINER) == "true" }.getOrDefault(false)

    private fun readChainringTeeth(): Int =
        runCatching { store.getString(KEY_CHAINRING_TEETH)?.trim()?.toIntOrNull() }.getOrNull()
            ?.coerceIn(CHAINRING_MIN_TEETH, CHAINRING_MAX_TEETH)
            ?: CHAINRING_DEFAULT_TEETH

    private fun readGearTable(): VirtualGearTable =
        runCatching { store.getString(KEY_GEAR_TABLE)?.trim() }.getOrNull()
            ?.let { name -> VirtualGearTable.entries.firstOrNull { it.name == name } }
            ?: VirtualGearTable.EVEN

    private fun publishPair() {
        val trainer = remembered[IndoorDeviceRole.TRAINER]
        val shifter = remembered[IndoorDeviceRole.SHIFTER]
        _pair.update {
            it.copy(
                trainerName = trainer?.let { d -> d.name ?: d.address },
                shifterName = shifter?.let { d -> d.name ?: d.address },
            )
        }
    }

    // -----------------------------------------------------------------------
    // 2. Strecke und Modus
    // -----------------------------------------------------------------------

    /**
     * Liest die waehlbaren Strecken: Testwelt, gespeicherte Planungen und
     * gefahrene Touren. **Indoor-Fahrten sind keine Strecke** — ihre Punkte
     * sind die virtuelle Spur einer anderen Strecke, niemand war dort.
     */
    fun loadRouteOptions() {
        _route.update { it.copy(loadingOptions = true) }
        scope.launch {
            val (saved, erg) = withContext(io) { savedOptions() to computeErg() }
            ergWorkout = erg
            val ghostWatts = defaultGhostWatts()
            _route.update { current ->
                val options = listOf<IndoorRouteOption>(IndoorRouteOption.TestWorld) + saved
                val selected = current.selected?.let { sel -> options.firstOrNull { it.key == sel.key } }
                val mode = if (erg == null) IndoorMode.SIM else current.mode
                current.copy(
                    options = options,
                    selected = selected,
                    mode = mode,
                    ergTitle = erg?.title,
                    ergMinutes = erg?.let { (it.totalS + 30) / 60 },
                    ghostPastRideAvailable = isPastRideAvailable(selected),
                    ghost = sanitizeGhost(current.ghost, selected),
                    defaultGhostWatts = ghostWatts,
                    loadingOptions = false,
                )
            }
        }
    }

    private fun savedOptions(): List<IndoorRouteOption.Saved> {
        val summaries = runCatching { rideStorage.listSummaries().summaries }.getOrDefault(emptyList())
        val withWorld = runCatching {
            worlds.listWorlds().filter { it.complete }.mapNotNull { it.source?.rideId }.toSet()
        }.getOrDefault(emptySet())
        return summaries
            .filter { !it.indoor && it.pointCount >= 2 }
            .sortedByDescending { it.createdAt }
            .map { s ->
                IndoorRouteOption.Saved(
                    rideId = s.id,
                    name = s.name,
                    distanceKm = s.stats.distanceKm,
                    ascentM = s.stats.ascentM,
                    createdAt = s.createdAt,
                    planned = s.planned,
                    hasWorld = s.id in withWorld,
                )
            }
    }

    /** Die Einheit des Tages als ERG-Einheit — `null`, wenn es keine passende gibt. */
    private fun computeErg(): ErgWorkout? {
        val p = runCatching { plan() }.getOrNull() ?: return null
        val session = runCatching { sessionsForDay(p, wallClock()) }.getOrNull()
            ?.firstOrNull { !it.isEvent } ?: return null
        val title = runCatching { sessionTitle(session, coreTexts()) }.getOrElse { session.title }
        return indoorCatching(IndoorPart.CORE) { parts.ergWorkout(session, title) }.getOrNull()
    }

    private fun defaultGhostWatts(): Int {
        val ftp = runCatching { profile().eftpW }.getOrNull() ?: return DEFAULT_GHOST_WATTS
        return ((ftp * GHOST_FTP_FRACTION / 5.0).roundToInt() * 5).coerceIn(50, 500)
    }

    private fun isPastRideAvailable(option: IndoorRouteOption?): Boolean =
        option is IndoorRouteOption.Saved && !option.planned

    private fun sanitizeGhost(ghost: IndoorGhostOption, selected: IndoorRouteOption?): IndoorGhostOption = when (ghost) {
        is IndoorGhostOption.PastRide ->
            if (selected is IndoorRouteOption.Saved && !selected.planned && selected.rideId == ghost.rideId) {
                ghost
            } else {
                IndoorGhostOption.None
            }
        else -> ghost
    }

    fun selectRoute(option: IndoorRouteOption) {
        _route.update {
            it.copy(
                selected = option,
                ghostPastRideAvailable = isPastRideAvailable(option),
                ghost = sanitizeGhost(it.ghost, option),
            )
        }
    }

    fun setMode(mode: IndoorMode) {
        if (mode == IndoorMode.ERG && ergWorkout == null) return
        _route.update { it.copy(mode = mode) }
    }

    fun setGhost(ghost: IndoorGhostOption) {
        val selected = _route.value.selected
        val accepted = when (ghost) {
            is IndoorGhostOption.PastRide ->
                if (selected is IndoorRouteOption.Saved && !selected.planned) {
                    IndoorGhostOption.PastRide(selected.rideId, texts.ghostPastRideLabel(selected.createdAt))
                } else {
                    return
                }
            is IndoorGhostOption.ConstantPower -> IndoorGhostOption.ConstantPower(ghost.watts.coerceIn(30, 1000))
            IndoorGhostOption.None -> ghost
        }
        _route.update { it.copy(ghost = accepted) }
    }

    /** Weiter zu „Welt laden". */
    fun continueFromRoute() {
        if (!_route.value.canContinue) return
        _stage.value = IndoorStage.WORLD
        prepareWorld()
    }

    // -----------------------------------------------------------------------
    // 3. Welt laden
    // -----------------------------------------------------------------------

    /** Bereitet die gewaehlte Strecke auf und rechnet den Voranschlag (ohne Netz). */
    fun prepareWorld() {
        val option = _route.value.selected ?: return
        val previous = cancelRunningWorldJob()
        resetWorld()
        _world.value = IndoorWorldUi.Preparing
        val token = WorldJobToken().also { worldToken = it }
        worldJob = scope.launch {
            // Ein abgeloester Testwelt-Bau schreibt sonst noch ins selbe Verzeichnis.
            previous?.join()
            val ui = withContext(io) {
                worldGuard(canRetry = false) {
                    when (option) {
                        IndoorRouteOption.TestWorld -> prepareTestWorld(token)
                        is IndoorRouteOption.Saved -> prepareSaved(option, token)
                    }
                }
            }
            commitWorld(token, ui)
        }
    }

    /** Uebernimmt das Ergebnis eines Welt-Auftrags — nur, wenn er noch der aktuelle ist. */
    private fun commitWorld(token: WorldJobToken, ui: IndoorWorldUi) {
        if (token !== worldToken) return
        token.routeName?.let { routeName = it }
        token.ride?.let { selectedRide = it }
        token.baseRoute?.let { baseRoute = it }
        token.plan?.let { worldPlan = it }
        token.manifest?.let { worldManifest = it }
        token.dir?.let { worldDir = it }
        _world.value = ui
    }

    private fun resetWorld() {
        baseRoute = null
        worldPlan = null
        worldManifest = null
        worldDir = null
        selectedRide = null
    }

    private fun prepareTestWorld(token: WorldJobToken): IndoorWorldUi {
        val routeName = texts.testWorldName()
        token.routeName = routeName
        val dir = worlds.testWorldDir()
        val existing = readManifest(dir)
        val manifest = existing ?: indoorCatching(IndoorPart.WORLD) {
            parts.worldBuilder().buildTestWorld(dir, quality)
        }.getOrElse { return IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.WORLD, it)) }
        val route = indoorCatching(IndoorPart.CORE) { worldRouteOf(manifest, routeName) }
            .getOrElse { return IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.CORE, it)) }
        token.baseRoute = route
        token.manifest = manifest
        token.dir = dir
        return IndoorWorldUi.Ready(routeName, manifest.attribution)
    }

    private suspend fun prepareSaved(option: IndoorRouteOption.Saved, token: WorldJobToken): IndoorWorldUi {
        val ride = runCatching { rideStorage.loadRide(option.rideId) }.getOrNull()
            ?: return IndoorWorldUi.RouteUnavailable(
                IndoorProblem(IndoorPart.APP, false, "Tour ${option.rideId} nicht lesbar"),
            )
        val routeName = ride.name.ifBlank { option.name }
        token.ride = ride
        token.routeName = routeName

        // Vorhandene Welt: direkt weiter, ohne Abgleich und ohne Netz.
        val existing = runCatching { worlds.findWorldForRide(ride.id) }.getOrNull()
        val existingManifest = existing?.manifest
        if (existing != null && existingManifest != null) {
            token.manifest = existingManifest
            token.dir = existing.dir
            return IndoorWorldUi.Ready(routeName, existingManifest.attribution)
        }

        val sourced = sourcing.fromRide(ride, routeName, rematch = false)
            .getOrElse { return IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.CORE, it)) }
        token.baseRoute = sourced.route
        val planned = indoorCatching(IndoorPart.WORLD) { parts.worldBuilder().plan(sourced.route, quality) }
            .getOrElse { return failedOf(it, canRetry = false) }
        token.plan = planned
        val resumable = runCatching { worlds.get(planned.worldId)?.let { !it.complete } ?: false }.getOrDefault(false)
        return planUi(planned, resumable, routeName)
    }

    private fun planUi(plan: WorldPlan, resumable: Boolean, routeName: String = this.routeName) = IndoorWorldUi.Plan(
        routeName = routeName,
        distanceKm = plan.route.lengthM / 1000.0,
        quality = plan.quality,
        downloadBytes = plan.downloadBytes,
        diskBytes = plan.diskBytes,
        hosts = plan.hosts,
        aerialLabels = plan.aerial.map { it.label },
        surfaceSource = plan.route.source,
        resumable = resumable,
    )

    /**
     * Nutzeraktion „Welt laden": Belagsabgleich ueber den Router, Voranschlag
     * neu, dann der Bau auf [io] mit Fortschritt; abbrechbar ueber
     * [cancelWorldBuild].
     */
    fun loadWorld() {
        val ride = selectedRide ?: return
        val current = _world.value
        if (current !is IndoorWorldUi.Plan && current !is IndoorWorldUi.Failed) return
        val previous = cancelRunningWorldJob()
        _world.value = IndoorWorldUi.Building(WorldBuildPhase.ELEVATION, 0, 0, 0f)
        val token = WorldJobToken().also { worldToken = it }
        val name = routeName
        worldJob = scope.launch {
            // Nie zwei Bauten gleichzeitig: der abgeloeste laeuft bis zur
            // naechsten Abfrage von isCancelled() weiter (ggf. im selben Verzeichnis).
            previous?.join()
            val ui = withContext(io) { worldGuard(canRetry = true) { buildWorld(ride, name, token) } }
            commitWorld(token, ui)
        }
    }

    private suspend fun buildWorld(ride: Ride, routeName: String, token: WorldJobToken): IndoorWorldUi {
        val sourced = sourcing.fromRide(ride, routeName, rematch = true)
            .getOrElse { return failedOf(it, canRetry = true) }
        token.baseRoute = sourced.route
        val builder = indoorCatching(IndoorPart.WORLD) { parts.worldBuilder() }
            .getOrElse { return failedOf(it, canRetry = true) }
        val plan = indoorCatching(IndoorPart.WORLD) { builder.plan(sourced.route, quality) }
            .getOrElse { return failedOf(it, canRetry = true) }
        token.plan = plan
        val dir = runCatching { worlds.worldDirFor(plan.worldId) }
            .getOrElse { return failedOf(it, canRetry = false) }
        runCatching { WorldLibrary.writeSource(dir, ride.id, routeName) }
        val deps = indoorCatching(IndoorPart.WORLD) { WorldBuildDeps(parts.geoPipeline(), parts.rasterDecoder()) }
            .getOrElse { return failedOf(it, canRetry = true) }
        val listener = object : WorldBuildListener {
            override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
                if (token.stopRequested || token !== worldToken) return
                _world.value = IndoorWorldUi.Building(phase, done, total, buildFraction(phase, done, total))
                // „Abbrechen" kam genau dazwischen: den Hinweis nicht ueberschreiben.
                if (token.stopRequested) markCancelling()
            }

            override fun isCancelled(): Boolean = token.stopRequested || token !== worldToken
        }
        val built = indoorCatching(IndoorPart.WORLD) { builder.build(plan, deps, dir, listener) }
        return built.fold(
            onSuccess = { manifest ->
                token.manifest = manifest
                token.dir = dir
                IndoorWorldUi.Ready(routeName, manifest.attribution)
            },
            onFailure = { e ->
                if (causeOf<WorldBuildCancelled>(e) != null || token.stopRequested) {
                    planUi(plan, resumable = true, routeName)
                } else {
                    failedOf(e, canRetry = true)
                }
            },
        )
    }

    /** Bricht einen laufenden Weltbau ab; fertige Kacheln bleiben liegen. */
    fun cancelWorldBuild() {
        val token = worldToken ?: return
        token.stopRequested = true
        // Sofort sichtbar machen: Der Bau endet erst, wenn die laufenden Kacheln fertig sind.
        markCancelling()
    }

    private fun markCancelling() {
        _world.update { if (it is IndoorWorldUi.Building) it.copy(cancelling = true) else it }
    }

    /** „Erneut versuchen" nach einem Fehler. */
    fun retryWorld() {
        if (selectedRide != null && worldPlan != null) {
            _world.value.let { if (it is IndoorWorldUi.Failed) loadWorld() else prepareWorld() }
        } else {
            prepareWorld()
        }
    }

    /**
     * Loest den laufenden Welt-Auftrag ab: Abbruch angefordert, Ergebnisse
     * werden verworfen. Liefert den Job, damit ein neuer Auftrag auf sein Ende
     * warten kann (ein blockierender Bau endet erst bei der naechsten Abfrage).
     */
    private fun cancelRunningWorldJob(): Job? {
        worldToken?.stopRequested = true
        worldToken = null
        // Der Job bleibt gemerkt (abgebrochen), damit auch ein spaeterer
        // Auftrag nach „Zurueck" noch auf sein Ende warten kann.
        val job = worldJob
        job?.cancel()
        return job
    }

    private fun failedOf(e: Throwable, canRetry: Boolean): IndoorWorldUi.Failed {
        val reason = causeOf<WorldBuildFailed>(e)?.reason
        return IndoorWorldUi.Failed(
            reason = reason,
            problem = problemOf(IndoorPart.WORLD, e),
            canRetry = canRetry,
            detail = technicalDetailOf(e),
        )
    }

    /**
     * Letztes Netz um einen Welt-Auftrag: Was trotz der einzelnen
     * [indoorCatching] noch durchkommt — auch `Error`s wie
     * `OutOfMemoryError`, `StackOverflowError` oder `NoSuchMethodError` —,
     * wird zur Fehlerkarte mit technischer Einzelheit statt zum Absturz.
     * Abbruch der Koroutine geht weiter.
     */
    private suspend fun worldGuard(canRetry: Boolean, block: suspend () -> IndoorWorldUi): IndoorWorldUi = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failedOf(e, canRetry)
    }

    private fun readManifest(dir: File): WorldManifest? = runCatching {
        val file = File(dir, WorldManifest.FILE_NAME)
        if (file.isFile) WorldManifest.parse(file.readText(Charsets.UTF_8)) else null
    }.getOrNull()

    // -----------------------------------------------------------------------
    // 4. Fahren
    // -----------------------------------------------------------------------

    /**
     * Startet die Fahrt — mit Welt ([withWorld] und eine fertige Welt liegt
     * vor) auf dem Streckenband des Manifests, sonst auf der urspruenglichen
     * Strecke („Ohne Welt fahren"). Vom Hauptthread (erzeugt die Darstellung).
     */
    fun startRide(withWorld: Boolean) {
        if (activeRuntime != null) return
        val manifest = worldManifest
        val dir = worldDir
        val useWorld = withWorld && manifest != null && dir != null
        val routeResult: Result<IndoorRoute> = when {
            useWorld -> indoorCatching(IndoorPart.CORE) { worldRouteOf(manifest, routeName) }
            baseRoute != null -> Result.success(baseRoute!!)
            manifest != null -> indoorCatching(IndoorPart.CORE) { worldRouteOf(manifest, routeName) }
            else -> Result.failure(IllegalStateException("keine Strecke aufbereitet"))
        }
        val route = routeResult.getOrElse {
            _world.value = IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.APP, it))
            return
        }
        val routeUi = _route.value
        val demo = _pair.value.demoTrainer
        val mode = if (routeUi.mode == IndoorMode.ERG && ergWorkout != null) IndoorMode.ERG else IndoorMode.SIM

        finishing = false
        pendingRide = null
        _summary.value = null
        val ghostLabel = ghostLabelOf(routeUi.ghost)
        rideStatic = RideStatic(
            routeName = routeName,
            trainerName = if (demo) texts.demoTrainerName() else (_pair.value.trainerName ?: ""),
            profile = runCatching { indoorProfileOf(route) }.getOrNull(),
            camera = CameraMode.CHASE,
            demoPowerW = if (demo) DEMO_DEFAULT_POWER_W else null,
            demoCadenceRpm = if (demo) DEMO_DEFAULT_CADENCE_RPM else null,
            ghostLabel = ghostLabel,
            ergTitle = if (mode == IndoorMode.ERG) ergWorkout?.title else null,
            attribution = if (useWorld) manifest.attribution else emptyList(),
            mode = mode,
        )
        _stage.value = IndoorStage.RIDE

        // Trainer. Ein Rohr, das kein Trainer oder Schalttaster uebernommen hat,
        // schliesst der Start selbst wieder (ein uebernommenes schliesst
        // dessen `stop()`, bzw. die Laufzeit am Ende der Fahrt).
        var trainerLink: BleLink? = null
        val trainerResult: Result<IndoorTrainer> = if (demo) {
            indoorCatching(IndoorPart.CORE) { parts.createDemoTrainer() }
        } else {
            indoorCatching(IndoorPart.BLE) { parts.openLink(IndoorDeviceRole.TRAINER) }.mapCatching { link ->
                link ?: throw IllegalStateException("kein Trainer gemerkt")
            }.mapCatching { link ->
                trainerLink = link
                indoorCatching(IndoorPart.CORE) { parts.createFtmsTrainer(link) }.getOrThrow()
            }
        }
        val trainer = trainerResult.getOrElse {
            trainerLink?.let { l -> indoorCatching(IndoorPart.BLE) { l.close() } }
            showStartProblem(problemOf(IndoorPart.CORE, it))
            return
        }
        if (trainer is DemoTrainer) {
            indoorCatching(IndoorPart.CORE) {
                trainer.riderPowerW = DEMO_DEFAULT_POWER_W
                trainer.riderCadenceRpm = DEMO_DEFAULT_CADENCE_RPM
            }
        } else {
            indoorCatching(IndoorPart.CORE) { trainer.name }.getOrNull()?.takeIf { it.isNotBlank() }?.let { name ->
                rideStatic = rideStatic?.copy(trainerName = name)
            }
        }

        // Schalttaster (optional). Je Fahrt eine neue Instanz: Ein Taster, der
        // UNSUPPORTED meldet, bleibt es fuer seine Instanz endgueltig (und
        // schliesst sein Rohr selbst); der naechste Versuch ist die naechste Fahrt.
        var shifterLink: BleLink? = null
        val shifter: IndoorShifter? = if (remembered[IndoorDeviceRole.SHIFTER] != null) {
            indoorCatching(IndoorPart.BLE) { parts.openLink(IndoorDeviceRole.SHIFTER) }.getOrNull()?.let { link ->
                shifterLink = link
                indoorCatching(IndoorPart.CORE) { parts.createShifter(link) }
                    .onFailure { indoorCatching(IndoorPart.BLE) { link.close() } }
                    .getOrNull()
            }
        } else {
            null
        }

        // Sitzung
        // Echte Uebersetzung am Trainer (gewaehltes Kettenblatt auf dem Zwift
        // Cog) und Gangtabelle aus den Einstellungen.
        val rider = indoorCatching(IndoorPart.CORE) { parts.riderSetup(profile()) }
            .getOrElse { fallbackRiderSetup(profile()) }
            .copy(
                realGearRatio = realGearRatioOf(_pair.value.chainringTeeth),
                gearTable = _pair.value.gearTable,
            )
        val config = IndoorSessionConfig(
            route = route,
            mode = mode,
            rider = rider,
            workout = if (mode == IndoorMode.ERG) ergWorkout else null,
            ghost = ghostSpecOf(routeUi.ghost),
            // Die Faehigkeiten eines Trainers kennt erst die Verbindung; bis
            // dahin gilt die Annahme „SIM und ERG" (Abschnitt 5.2). Meldet der
            // Trainer sie vor dem ersten Tritt, tauscht die Laufzeit die
            // Sitzung gegen eine mit den echten ([rebuildSession] unten).
            capabilities = ASSUMED_CAPABILITIES,
            startedAtEpochMs = wallClock(),
        )
        val session = indoorCatching(IndoorPart.CORE) { parts.createSession(config) }.getOrElse {
            indoorCatching(IndoorPart.CORE) { trainer.stop() }
            shifter?.let { s -> indoorCatching(IndoorPart.CORE) { s.stop() } }
            // Noch nicht gestartet: stop() schliesst ein Rohr dann nicht selbst.
            trainerLink?.let { l -> indoorCatching(IndoorPart.BLE) { l.close() } }
            shifterLink?.let { l -> indoorCatching(IndoorPart.BLE) { l.close() } }
            showStartProblem(problemOf(IndoorPart.CORE, it))
            return
        }

        // Darstellung (nur mit Welt)
        var renderer: WorldRenderer? = null
        var initialRender: IndoorRenderUi = IndoorRenderUi.NoWorld
        if (useWorld) {
            indoorCatching(IndoorPart.RENDER) { parts.createRenderer() }.fold(
                onSuccess = {
                    renderer = it
                    initialRender = IndoorRenderUi.Loading(0, 0)
                },
                onFailure = { initialRender = IndoorRenderUi.Failed(null, problemOf(IndoorPart.RENDER, it)) },
            )
        }

        val runtime = IndoorRideRuntime(
            session = session,
            trainer = trainer,
            shifter = shifter,
            renderer = renderer,
            heartRate = runCatching { heartRate() }.getOrNull(),
            initialRender = initialRender,
            dispatcher = runtimeDispatcher,
            clock = clock,
            autoLoop = autoLoop,
            rebuildSession = { caps ->
                if (caps == config.capabilities) null else parts.createSession(config.copy(capabilities = caps))
            },
        )
        activeRuntime = runtime
        renderer?.let { r ->
            val loaded = indoorCatching(IndoorPart.RENDER) {
                r.setListener(runtime.rendererListener)
                r.setCamera(CameraMode.CHASE)
                r.loadWorld(dir!!, manifest!!)
            }
            loaded.exceptionOrNull()?.let { e ->
                runtime.rendererListener.onFailure(RenderFailure.INTERNAL, problemOf(IndoorPart.RENDER, e).detail)
            }
            _renderer.value = r
        }
        publishRide(runtime.state.value)
        rideCollectJob = scope.launch { runtime.state.collect { publishRide(it) } }
        runtime.start()
    }

    private fun showStartProblem(problem: IndoorProblem) {
        val st = rideStatic ?: return
        _ride.value = IndoorRideUi(
            routeName = st.routeName,
            session = null,
            trainerName = st.trainerName,
            trainer = TrainerConnection.OFF,
            shifter = null,
            shifterBatteryPct = null,
            render = IndoorRenderUi.NoWorld,
            camera = st.camera,
            profile = st.profile,
            notice = null,
            demoPowerW = st.demoPowerW,
            demoCadenceRpm = st.demoCadenceRpm,
            ghostLabel = st.ghostLabel,
            ergTitle = st.ergTitle,
            attribution = st.attribution,
            startProblem = problem,
        )
    }

    private fun publishRide(rs: IndoorRuntimeState) {
        val st = rideStatic ?: return
        _ride.value = IndoorRideUi(
            routeName = st.routeName,
            session = rs.session,
            trainerName = st.trainerName,
            trainer = rs.trainer,
            shifter = rs.shifter,
            shifterBatteryPct = rs.shifterBatteryPct,
            render = rs.render,
            camera = st.camera,
            profile = st.profile,
            notice = rs.notice,
            demoPowerW = st.demoPowerW,
            demoCadenceRpm = st.demoCadenceRpm,
            ghostLabel = st.ghostLabel,
            ergTitle = st.ergTitle,
            attribution = st.attribution,
            runtimeProblem = rs.problem,
        )
    }

    private fun republishRide() {
        activeRuntime?.let { publishRide(it.state.value) }
    }

    private fun ghostLabelOf(ghost: IndoorGhostOption): String? = when (ghost) {
        IndoorGhostOption.None -> null
        is IndoorGhostOption.PastRide -> ghost.label
        is IndoorGhostOption.ConstantPower -> texts.ghostConstantPowerLabel(ghost.watts)
    }

    private fun ghostSpecOf(ghost: IndoorGhostOption): GhostSpec = when (ghost) {
        IndoorGhostOption.None -> GhostSpec.None
        is IndoorGhostOption.ConstantPower -> GhostSpec.ConstantPower(ghost.watts.toDouble())
        is IndoorGhostOption.PastRide -> {
            val ride = selectedRide?.takeIf { it.id == ghost.rideId }
            val timed = ride?.points?.count { it.time != null } ?: 0
            if (ride != null && timed >= 2) GhostSpec.PastRide(ghost.label, ride.points) else GhostSpec.None
        }
    }

    private fun fallbackRiderSetup(p: TrainingProfile): RiderSetup = RiderSetup(
        riderMassKg = p.weightKg,
        bikeMassKg = p.setupMassKg,
        cdaM2 = p.cda,
        profileCrr = p.crr,
        ftpW = p.eftpW,
        driveEfficiency = p.driveEfficiency,
    )

    fun shift(event: ShifterEvent) {
        activeRuntime?.shift(event)
    }

    fun adjustErg(deltaPct: Int) {
        activeRuntime?.adjustErg(deltaPct)
    }

    fun pauseRide() {
        activeRuntime?.pause()
    }

    fun resumeRide() {
        activeRuntime?.resume()
    }

    fun dismissNotice() {
        activeRuntime?.dismissNotice()
    }

    /** Regler „Leistung" des Demo-Trainers (0..600 W). */
    fun setDemoPower(watts: Int) {
        val st = rideStatic ?: return
        if (st.demoPowerW == null) return
        val w = watts.coerceIn(0, DEMO_MAX_POWER_W)
        rideStatic = st.copy(demoPowerW = w)
        activeRuntime?.setDemoInput(w, st.demoCadenceRpm ?: DEMO_DEFAULT_CADENCE_RPM)
        republishRide()
    }

    /** Regler „Trittfrequenz" des Demo-Trainers (0..130 U/min). */
    fun setDemoCadence(rpm: Int) {
        val st = rideStatic ?: return
        if (st.demoCadenceRpm == null) return
        val c = rpm.coerceIn(0, DEMO_MAX_CADENCE_RPM)
        rideStatic = st.copy(demoCadenceRpm = c)
        activeRuntime?.setDemoInput(st.demoPowerW ?: DEMO_DEFAULT_POWER_W, c)
        republishRide()
    }

    /** Kamera wechseln (Hauptthread). */
    fun setCamera(mode: CameraMode) {
        val st = rideStatic ?: return
        rideStatic = st.copy(camera = mode)
        _renderer.value?.let { r -> indoorCatching(IndoorPart.RENDER) { r.setCamera(mode) } }
        republishRide()
    }

    /** Die App verlaesst den Vordergrund — die Fahrt pausiert. */
    fun onAppBackground() {
        activeRuntime?.onAppBackground()
    }

    fun onAppForeground() {
        activeRuntime?.onAppForeground()
    }

    /**
     * Beendet die Fahrt (Knopf „Beenden" nach Halten) und zeigt die
     * Auswertung. Ohne laufende Fahrt (Start scheiterte) zurueck zu „Welt laden".
     */
    fun finishRide() {
        val runtime = activeRuntime
        if (runtime == null) {
            _ride.value = null
            rideStatic = null
            _stage.value = IndoorStage.WORLD
            return
        }
        if (finishing) return
        finishing = true
        scope.launch {
            val record = runtime.finish()
            rideCollectJob?.cancel()
            rideCollectJob = null
            publishRide(runtime.state.value)
            destroyRenderer()
            _summary.value = summaryOf(record, runtime.state.value)
            activeRuntime = null
            _stage.value = IndoorStage.SUMMARY
        }
    }

    // -----------------------------------------------------------------------
    // 5. Auswertung
    // -----------------------------------------------------------------------

    private fun summaryOf(record: IndoorRideRecord?, rs: IndoorRuntimeState): IndoorSummaryUi {
        val st = rideStatic
        val name = st?.routeName ?: routeName
        pendingRide = record?.let { r ->
            runCatching { indoorRideToRide(r, texts.rideName(name)) }.getOrNull()
        }
        val totals = record?.totals ?: rs.session?.totals
        val movingS = totals?.movingS ?: 0
        val distanceM = totals?.distanceM ?: 0.0
        return IndoorSummaryUi(
            routeName = name,
            mode = record?.mode ?: st?.mode ?: IndoorMode.SIM,
            distanceKm = record?.stats?.distanceKm ?: (distanceM / 1000.0),
            movingS = movingS,
            ascentM = totals?.ascentM ?: 0.0,
            avgPowerW = totals?.avgPowerW?.roundToInt(),
            normalizedPowerW = totals?.normalizedPowerW?.roundToInt(),
            avgCadenceRpm = totals?.avgCadenceRpm?.roundToInt(),
            avgHrBpm = totals?.avgHrBpm?.roundToInt(),
            workKj = totals?.workKj?.roundToInt() ?: 0,
            avgSpeedKmh = if (movingS > 0) distanceM / movingS * 3.6 else 0.0,
            laps = record?.laps ?: rs.session?.lap ?: 1,
            ghostLabel = st?.ghostLabel,
            ghostGapS = record?.ghostFinalGapS,
            // „Zu kurz" nur bei einer echten Aufzeichnung unter 60 s; fehlt sie
            // (fremder Teil warf in finish()) oder liess sie sich nicht
            // umwandeln, ist das ein eigener Zustand mit dem Grund.
            tooShort = record != null && record.totals.movingS < MIN_INDOOR_RIDE_S,
            recordMissing = pendingRide == null && (record == null || record.totals.movingS >= MIN_INDOOR_RIDE_S),
            recordProblem = if (record == null) rs.problem else null,
        )
    }

    /** Speichert die Fahrt als Indoor-Tour (Fahrten unter 60 s gar nicht). */
    fun saveRide() {
        val ride = pendingRide ?: return
        val current = _summary.value ?: return
        if (current.saving || current.savedRideId != null) return
        _summary.value = current.copy(saving = true, saveFailed = false)
        scope.launch {
            val ok = withContext(io) { runCatching { rideStorage.saveRide(ride) }.isSuccess }
            if (ok) {
                _summary.update { it?.copy(saving = false, savedRideId = ride.id) }
                runCatching { onRideSaved(ride.id) }
            } else {
                _summary.update { it?.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** Verwirft die Aufzeichnung — danach laesst sie sich nicht mehr speichern. */
    fun discardRide() {
        pendingRide = null
    }

    /** Eine neue Fahrt: zurueck zur Streckenwahl. */
    fun startOver() {
        pendingRide = null
        _summary.value = null
        _ride.value = null
        rideStatic = null
        _stage.value = IndoorStage.ROUTE
        loadRouteOptions()
    }

    /**
     * Zurueck-Taste. `true` = im Ablauf verarbeitet; `false` = der Ablauf
     * darf verlassen werden. Waehrend der Fahrt wird sie verschluckt — beendet
     * wird nur ueber „Beenden".
     */
    fun back(): Boolean = when (_stage.value) {
        IndoorStage.PAIR -> false
        IndoorStage.ROUTE -> {
            _stage.value = IndoorStage.PAIR
            true
        }
        IndoorStage.WORLD -> {
            cancelRunningWorldJob()
            _stage.value = IndoorStage.ROUTE
            true
        }
        IndoorStage.RIDE -> {
            val runtime = activeRuntime
            when {
                runtime == null -> finishRide()
                // Die Laufzeit laeuft, die Sitzung liefert aber keinen Zustand
                // (fremder Teil wirft bei jedem Takt): kein Weg in die Fahrt —
                // alles freigeben und zurueck zu „Welt laden".
                runtime.state.value.session == null && !finishing -> abortRideStart()
                // Laufende Fahrt: beendet wird nur ueber „Beenden".
                else -> Unit
            }
            true
        }
        IndoorStage.SUMMARY -> false
    }

    // -----------------------------------------------------------------------
    // Aufraeumen
    // -----------------------------------------------------------------------

    /** Gibt eine Fahrt frei, die nie einen Sitzungszustand lieferte, und kehrt zu „Welt laden" zurueck. */
    private fun abortRideStart() {
        rideCollectJob?.cancel()
        rideCollectJob = null
        activeRuntime?.close()
        activeRuntime = null
        destroyRenderer()
        _ride.value = null
        rideStatic = null
        _stage.value = IndoorStage.WORLD
    }

    private fun destroyRenderer() {
        val r = _renderer.value ?: return
        _renderer.value = null
        indoorCatching(IndoorPart.RENDER) {
            r.setListener(null)
            r.destroy()
        }
    }

    /** Gibt alles frei; eine laufende Fahrt wird ohne Aufzeichnung verworfen. */
    fun release() {
        cancelRunningWorldJob()
        rideCollectJob?.cancel()
        activeRuntime?.close()
        activeRuntime = null
        destroyRenderer()
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    companion object {
        /** Schluessel des Demo-Schalters im [KeyValueStore]. */
        const val KEY_DEMO_TRAINER = "trailscape.indoor.demo_trainer"

        /** Schluessel des Kettenblatts am Trainer (Zaehne) im [KeyValueStore]. */
        const val KEY_CHAINRING_TEETH = "trailscape.indoor.chainring_teeth"

        /** Schluessel der Gangtabelle (Enum-Name von [VirtualGearTable]) im [KeyValueStore]. */
        const val KEY_GEAR_TABLE = "trailscape.indoor.gear_table"

        /** Annahme ueber den Trainer, bis er seine Faehigkeiten meldet. */
        val ASSUMED_CAPABILITIES = TrainerCapabilities(simulation = true, targetPower = true)

        /** Das einzige Ritzel am Trainer: der Zwift Cog hat 14 Zaehne. */
        const val ZWIFT_COG_TEETH = 14

        /** Vorgabe: 48er-Kettenblatt (z. B. GRX 820 2x12, 48/31). */
        const val CHAINRING_DEFAULT_TEETH = 48
        const val CHAINRING_MIN_TEETH = 28
        const val CHAINRING_MAX_TEETH = 60

        /** Echte Uebersetzung am Trainer: Kettenblatt / Zwift Cog. */
        fun realGearRatioOf(chainringTeeth: Int): Double = chainringTeeth.toDouble() / ZWIFT_COG_TEETH

        const val DEMO_DEFAULT_POWER_W = 180
        const val DEMO_DEFAULT_CADENCE_RPM = 85
        const val DEMO_MAX_POWER_W = 600
        const val DEMO_MAX_CADENCE_RPM = 130

        private const val DEFAULT_GHOST_WATTS = 150

        /** Vergleichsleistung des Geists als Anteil der FTP (lockere Grundlage). */
        private const val GHOST_FTP_FRACTION = 0.75

        /** Fortschritt 0..1 ueber alle Phasen, jede gleich gewichtet. */
        internal fun buildFraction(phase: WorldBuildPhase, done: Int, total: Int): Float {
            val phases = WorldBuildPhase.entries.size
            val inPhase = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            return ((phase.ordinal + inPhase) / phases).coerceIn(0f, 1f)
        }

        private inline fun <reified T : Throwable> causeOf(e: Throwable): T? {
            var current: Throwable? = e
            var depth = 0
            while (current != null && depth < 10) {
                if (current is T) return current
                current = current.cause
                depth++
            }
            return null
        }

        /**
         * Die Fabrik fuer `viewModel(factory = …)` am Backstack-Eintrag `indoor`.
         *
         * @param profile / [plan] lesen den aktuellen Stand (z. B. aus dem `AppViewModel`).
         * @param onRideSaved z. B. `appViewModel::refreshRides` (Touren neu laden).
         */
        fun factory(
            context: Context,
            profile: () -> TrainingProfile,
            plan: () -> TrainingPlan?,
            onRideSaved: (String) -> Unit = {},
        ): ViewModelProvider.Factory {
            val app = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = IndoorViewModel(
                    parts = AppServices.indoorParts,
                    rideStorage = AppServices.rideStorage,
                    worlds = AppServices.indoorWorldLibrary,
                    store = AppServices.keyValueStore,
                    texts = ContextIndoorTexts(
                        context = { AppServices.localizedContext() },
                        language = { AppServices.appLanguage.value },
                    ),
                    profile = profile,
                    plan = plan,
                    coreTexts = { AppServices.coreTexts() },
                    devices = IndoorDevices.remembered(app),
                    rematchRouter = OfflineFirstRematchRouter(app),
                    heartRate = { BleHeartRateSource(app) },
                    onRideSaved = onRideSaved,
                ) as T
            }
        }
    }
}
