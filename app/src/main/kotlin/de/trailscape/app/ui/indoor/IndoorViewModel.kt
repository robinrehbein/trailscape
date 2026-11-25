package de.trailscape.app.ui.indoor

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.catalog.CatalogDownloader
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.app.indoor.BleHeartRateSource
import de.trailscape.app.indoor.IndoorDayPlan
import de.trailscape.app.indoor.IndoorDayRouteFinder
import de.trailscape.app.indoor.DefaultIndoorGeneratedWorlds
import de.trailscape.app.indoor.GENERATED_SOURCE_PREFIX
import de.trailscape.app.indoor.IndoorGeneratedWorlds
import de.trailscape.app.indoor.IndoorHeartRateSource
import de.trailscape.app.indoor.indoorDayPlanOf
import de.trailscape.app.indoor.rank
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
import de.trailscape.core.RouteTarget
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingProfile
import de.trailscape.app.challenge.ChallengeStore
import de.trailscape.app.challenge.challengeRouteInfo
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.routeOfKey
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.sessionTitle
import de.trailscape.core.indoor.BleLink
import de.trailscape.core.indoor.DayRideEnd
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DayRouteMatch
import de.trailscape.core.indoor.DemoTrainer
import de.trailscape.core.indoor.ErgWorkout
import de.trailscape.core.indoor.GhostSpec
import de.trailscape.core.indoor.IndoorDeviceRole
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorResumePoint
import de.trailscape.core.indoor.IndoorRideRecord
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorSessionConfig
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorShifter
import de.trailscape.core.indoor.IndoorStartPoints
import de.trailscape.core.indoor.IndoorTrainer
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.TrainerCapabilities
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.sky.IndoorDaylight
import de.trailscape.core.indoor.sky.IndoorTimeOfDay
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.GeoOrigin
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
import de.trailscape.core.indoor.dayRideEnd
import de.trailscape.core.indoor.ergRemainingS
import de.trailscape.core.indoor.moveStartTo
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
 * ([KEY_CHAINRING_TEETH]), die Gangtabelle ([KEY_GEAR_TABLE]) und die
 * Tageszeit der 3D-Welt ([KEY_TIME_OF_DAY]), alle im [store].
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
 * @param today die Tagesentscheidung (`decideTodayRoute` ueber `decideToday`);
 *   `null` = unbekannt — dann gilt die Planeinheit ungedaempft wie bisher und es
 *   gibt keine Karte „Einheit des Tages" (docs/indoor/app.md).
 * @param dayFinder Kandidaten fuer die Einheit des Tages; `null` = keine Karte.
 * @param onPlanRoute „Runde planen" der Karte: oeffnet die Routensuche mit dem Ziel.
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
    private val today: () -> TodayRoute? = { null },
    private val dayFinder: IndoorDayRouteFinder? = null,
    private val generatedWorlds: IndoorGeneratedWorlds = DefaultIndoorGeneratedWorlds,
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
    catalogStore: CatalogStore? = null,
    catalogDownloader: CatalogDownloader? = null,
    catalogOnline: () -> Boolean = { false },
    serverDeps: IndoorServerDeps? = null,
    challengeStore: ChallengeStore? = null,
    avatarJson: () -> String? = { null },
    var onRideSaved: (String) -> Unit = {},
    var onPlanRoute: (RouteTarget) -> Unit = {},
) : ViewModel() {

    private val scope: CoroutineScope = scope ?: viewModelScope

    /** Bereich „Bekannte Strecken" (docs/indoor/catalog.md). */
    private val catalogController = IndoorCatalogController(catalogStore, catalogDownloader, this.scope, io, catalogOnline, wallClock)

    /** Zustand der Katalogkarten im Schritt Streckenwahl. */
    val catalog: StateFlow<IndoorCatalogUi> = catalogController.ui

    /** Geteilte Herausforderungen (docs/herausforderung.md): Liste, Import, Teilen. */
    private val challengeController = IndoorChallengeController(
        store = challengeStore,
        routeInfo = { ref ->
            challengeRouteInfo(
                ref,
                entry = { catalogController.entry(it) },
                installedVersion = { catalogController.installedWorld(it)?.version },
            )
        },
        scope = this.scope,
        io = io,
        kv = store,
        nowMs = wallClock,
        avatarJson = avatarJson,
    )

    /** Gespeicherte Herausforderungen. */
    val challenges: StateFlow<List<IndoorChallengeUi>> = challengeController.items

    /** Vorschau und Rückmeldung beim Import einer Datei; `null` = keine. */
    val challengeImport: StateFlow<ChallengeImportUi?> = challengeController.importState

    /** Vorschau beim Teilen der eigenen Fahrt; `null` = keine. */
    val challengeShare: StateFlow<ChallengeShareUi?> = challengeController.share

    /** Bau auf dem eigenen Server (docs/indoor/serverbau.md). */
    private val serverController = IndoorServerBuildController(serverDeps, this.scope, io) { rideId, dir, manifest, laser, replaced ->
        onServerWorldInstalled(rideId, dir, manifest, laser, replaced)
    }

    /** Die zweite Wahl im Schritt „Welt laden": Server-Bau in Laserqualitaet. */
    val server: StateFlow<IndoorServerUi> = serverController.ui
    private val sourcing = IndoorRouteSourcing(parts, rematchRouter)

    // -----------------------------------------------------------------------
    // Oeffentliche Zustaende
    // -----------------------------------------------------------------------

    // Der Ablauf beginnt auf der Startseite (Trainer-Status, Strecken, „Fahren");
    // Koppeln und Einstellungen sind Nebenseiten davon.
    private val _stage = MutableStateFlow(IndoorStage.ROUTE)
    val stage: StateFlow<IndoorStage> = _stage.asStateFlow()

    private var remembered: Map<IndoorDeviceRole, IndoorDeviceRef> = emptyMap()
    private val _pair = MutableStateFlow(
        IndoorPairUi(
            demoTrainer = readDemoFlag(),
            trainerName = null,
            shifterName = null,
            chainringTeeth = readChainringTeeth(),
            gearTable = readGearTable(),
            timeOfDay = readTimeOfDay(),
            otherRiders = readOtherRiders(),
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

    private val _day = MutableStateFlow<IndoorDayUi?>(null)

    /**
     * Die Karte „Einheit des Tages" im Schritt Strecke; `null` = keine (ohne
     * Tagesentscheidung oder ohne Kandidatensuche).
     */
    val day: StateFlow<IndoorDayUi?> = _day.asStateFlow()

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

    // Einheit des Tages (docs/indoor/app.md, „Einheit des Tages")
    private var dayPlan: IndoorDayPlan? = null
    private var dayMatches: List<DayRouteMatch> = emptyList()

    /** Von „Heute" gewaehlter Vorschlag, der beim Anzeigen vorausgewaehlt wird. */
    private var preferredDayKey: String? = null

    /** Startpunkt des gewaehlten Vorschlags: Schluessel der Streckenwahl und Anteil der Laenge. */
    private var dayStartPick: Pair<String, Double>? = null

    /** Katalogstrecke, die fuer die Einheit gerade geladen wird — danach geht es von selbst weiter. */
    private var dayAwaitingCatalog: String? = null

    /** Das Ende-Angebot, das in dieser Fahrt schon weggetippt wurde. */
    private var dismissedDayEnd: DayRideEnd? = null
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

        /** Katalogwelt bereit → ohne „Welt laden" direkt losfahren. */
        var autoStart = false

        /** Generierte Welt der Einheit des Tages, die gerade aufbereitet oder gebaut wird. */
        var generated: IndoorRouteOption.Generated? = null
    }

    // Fahrt
    private var rideCollectJob: Job? = null
    private var rideStatic: RideStatic? = null
    private var finishing = false
    private var pendingRide: Ride? = null

    // Herausforderung teilen (docs/herausforderung.md): was die Auswertung zum Teilen braucht
    private var shareRoute: IndoorRoute? = null
    private var shareRef: ChallengeRoute? = null
    private var challengeOpponent: String? = null
    private var rideDateIso: String? = null

    /** Stelle in der Runde, an der die Fahrt mit dem ersten Tritt begann; `null` vorher. */
    private var rideStartRouteM: Double? = null

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
        /** Die ERG-Einheit dieser Fahrt (fuer das Ende-Angebot), `null` in SIM. */
        val dayWorkout: ErgWorkout? = null,
        /** Schluessel der gemerkten Stelle dieser Strecke, `null` = nicht merken. */
        val resumeKey: String? = null,
        /** Angebot „Weiter bei km …" (bis zum ersten Tritt). */
        val resumeM: Double? = null,
        val startMaxM: Double = 0.0,
        val routeLengthM: Double = 0.0,
        val routeIsLoop: Boolean = false,
    )

    init {
        this.scope.launch {
            devices.collect { map ->
                remembered = map
                publishPair()
            }
        }
        // Einheit des Tages: Ist die fuer sie geladene Katalogstrecke bereit,
        // geht es ohne weitere Auswahl weiter.
        this.scope.launch {
            catalog.collect { c ->
                val id = dayAwaitingCatalog ?: return@collect
                val item = c.items.firstOrNull { it.id == id } ?: return@collect
                when {
                    item.ready -> {
                        dayAwaitingCatalog = null
                        if (_stage.value == IndoorStage.ROUTE) {
                            rideDayOn(
                                IndoorRouteOption.Catalog(id, item.name.pick(texts.languageTag()), item.distanceKm, item.ascentM),
                                "catalog:$id",
                            )
                        }
                    }
                    item.state is IndoorCatalogState.Failed -> dayAwaitingCatalog = null
                    else -> Unit
                }
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

    /**
     * Tageszeit der 3D-Welt — gemerkt in den Einstellungen (Enum-Name). Gilt ab
     * der naechsten Fahrt; waehrend einer Fahrt sofort.
     */
    fun setTimeOfDay(choice: IndoorTimeOfDay) {
        runCatching { store.setString(KEY_TIME_OF_DAY, choice.name) }
        _pair.update { it.copy(timeOfDay = choice) }
        applySky(force = true)
    }

    /**
     * Andere Radfahrer in der 3D-Welt anzeigen — gemerkt in den Einstellungen. Rein dekorativ;
     * waehrend einer Fahrt sofort.
     */
    fun setOtherRiders(enabled: Boolean) {
        runCatching { store.setString(KEY_OTHER_RIDERS, enabled.toString()) }
        _pair.update { it.copy(otherRiders = enabled) }
        _renderer.value?.let { r -> indoorCatching(IndoorPart.RENDER) { r.setOtherRidersEnabled(enabled) } }
    }

    private fun readOtherRiders(): Boolean = runCatching { store.getString(KEY_OTHER_RIDERS) != "false" }.getOrDefault(true)

    /**
     * Zurueck von „Trainer koppeln" zur Startseite und die Strecken neu lesen
     * (nur wenn [IndoorPairUi.canContinue]; sonst bleibt die Seite offen).
     */
    fun continueFromPair() {
        if (!_pair.value.canContinue) return
        _stage.value = IndoorStage.ROUTE
        loadRouteOptions()
    }

    /** Nebenseite „Trainer koppeln" (von der Startseite). */
    fun openPair() {
        if (_stage.value == IndoorStage.ROUTE) _stage.value = IndoorStage.PAIR
    }

    /** Nebenseite „Einstellungen" (von der Startseite). */
    fun openSettings() {
        if (_stage.value == IndoorStage.ROUTE) _stage.value = IndoorStage.SETTINGS
    }

    /** Nebenseite schliessen — zurueck zur Startseite, die Auswahl bleibt. */
    fun closeSubPage() {
        if (_stage.value == IndoorStage.PAIR || _stage.value == IndoorStage.SETTINGS) _stage.value = IndoorStage.ROUTE
    }

    private fun readDemoFlag(): Boolean = runCatching { store.getString(KEY_DEMO_TRAINER) == "true" }.getOrDefault(false)

    private fun readChainringTeeth(): Int =
        runCatching { store.getString(KEY_CHAINRING_TEETH)?.trim()?.toIntOrNull() }.getOrNull()
            ?.coerceIn(CHAINRING_MIN_TEETH, CHAINRING_MAX_TEETH)
            ?: CHAINRING_DEFAULT_TEETH

    private fun readTimeOfDay(): IndoorTimeOfDay =
        runCatching { IndoorTimeOfDay.fromName(store.getString(KEY_TIME_OF_DAY)) }.getOrDefault(IndoorTimeOfDay.AUTO)

    /** Wann der Himmel zuletzt gesetzt wurde (Wanduhr), fuer das Nachfuehren bei „Automatisch“. */
    private var skySetAtMs = Long.MIN_VALUE
    private var skyOrigin: GeoOrigin? = null

    /**
     * Setzt den Himmel der laufenden Darstellung aus der gewaehlten Tageszeit
     * und dem Ort der Welt. Ohne [force] nur, wenn „Automatisch“ gewaehlt ist
     * und der letzte Stand mindestens [SKY_REFRESH_MS] alt ist (die Sonne
     * wandert waehrend langer Fahrten mit).
     */
    private fun applySky(force: Boolean) {
        val r = _renderer.value ?: return
        val origin = skyOrigin ?: return
        val choice = _pair.value.timeOfDay
        val now = wallClock()
        if (!force && (choice != IndoorTimeOfDay.AUTO || now - skySetAtMs < SKY_REFRESH_MS)) return
        skySetAtMs = now
        indoorCatching(IndoorPart.RENDER) {
            r.setSky(IndoorDaylight.skySetting(choice, now, origin.lat, origin.lon))
        }
    }

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

    private var routeOptionsRequested = false

    /**
     * Beim Oeffnen der Startseite: die Strecken lesen, falls das noch nicht
     * geschah (nach einer Drehung nicht noch einmal).
     */
    fun ensureRouteOptions() {
        if (!routeOptionsRequested) loadRouteOptions()
    }

    /**
     * Liest die waehlbaren Strecken: Testwelt, gespeicherte Planungen und
     * gefahrene Touren. **Indoor-Fahrten sind keine Strecke** — ihre Punkte
     * sind die virtuelle Spur einer anderen Strecke, niemand war dort.
     */
    fun loadRouteOptions() {
        routeOptionsRequested = true
        _route.update { it.copy(loadingOptions = true) }
        catalogController.load()
        challengeController.refresh()
        scope.launch {
            val (saved, erg) = withContext(io) { savedOptions() to computeErg() }
            ergWorkout = erg
            launchDaySearch()
            val ghostWatts = defaultGhostWatts()
            _route.update { current ->
                val options = listOf<IndoorRouteOption>(IndoorRouteOption.TestWorld) + saved
                val selected = current.selected?.let { sel -> options.firstOrNull { it.key == sel.key } }
                    ?: current.selected as? IndoorRouteOption.Catalog
                    ?: current.selected as? IndoorRouteOption.Generated
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

    /**
     * Die Einheit des Tages als ERG-Einheit — `null`, wenn es keine passende
     * gibt. Mit Tagesentscheidung ([today]) die von der Tagesform gedaempfte
     * Einheit ([indoorDayPlanOf]): dieselbe, die die Karte „Einheit des Tages"
     * anbietet; am Ruhetag keine, an einem Tag ohne Planeinheit die lockere Runde.
     */
    private fun computeErg(): ErgWorkout? {
        val plan = computeDayPlan()
        dayPlan = plan
        if (plan != null) return plan.workout
        val p = runCatching { plan() }.getOrNull() ?: return null
        val session = runCatching { sessionsForDay(p, wallClock()) }.getOrNull()
            ?.firstOrNull { !it.isEvent } ?: return null
        val title = runCatching { sessionTitle(session, coreTexts()) }.getOrElse { session.title }
        return indoorCatching(IndoorPart.CORE) { parts.ergWorkout(session, title) }.getOrNull()
    }

    private fun computeDayPlan(): IndoorDayPlan? {
        val t = runCatching { today() }.getOrNull() ?: return null
        return runCatching {
            indoorDayPlanOf(
                today = t,
                parts = parts,
                profile = profile(),
                plannedTitle = { d -> d.planned?.let { s -> runCatching { sessionTitle(s, coreTexts()) }.getOrElse { s.title } } },
                easyTitle = texts.dayEasyTitle(),
            )
        }.getOrNull()
    }

    /** Sucht die Strecken zur Einheit des Tages (im Hintergrund) und zeigt die Karte. */
    private fun launchDaySearch() {
        val plan = dayPlan
        val finder = dayFinder
        if (plan == null || finder == null) {
            _day.value = null
            return
        }
        val first = indoorDayUiOf(plan, emptyList())
        if (first !is IndoorDayUi.Offer) {
            _day.value = first
            return
        }
        if (_day.value !is IndoorDayUi.Offer) _day.value = IndoorDayUi.Loading
        scope.launch {
            val (matches, genReady) = withContext(io) {
                runCatching { finder.rank(plan, texts.testWorldName(), texts.languageTag(), texts.dayPlanName()) }.getOrDefault(emptyList()) to
                    generatedReady(plan)
            }
            if (dayPlan !== plan) return@launch
            dayMatches = matches
            val bytes = catalog.value.items.associate { it.id to it.packageBytes }
            val ui = indoorDayUiOf(plan, matches, generatedReady = genReady) { id -> bytes[id] }
            val previous = (_day.value as? IndoorDayUi.Offer)?.current?.key
            val prev = _day.value as? IndoorDayUi.Offer
            val shown = (ui as? IndoorDayUi.Offer)?.let { offer ->
                when {
                    preferredDayKey != null -> offer.showing(preferredDayKey)
                    prev?.path == IndoorDayPath.GENERATED && offer.generated != null -> offer.copy(path = IndoorDayPath.GENERATED)
                    else -> offer.showing(previous)
                }
            }
            preferredDayKey = null
            _day.value = shown ?: ui
        }
    }

    /** Von „Heute": diesen Vorschlag vorauswaehlen, sobald die Karte steht. */
    fun preferDaySuggestion(key: String?) {
        val offer = _day.value as? IndoorDayUi.Offer
        if (offer != null) _day.value = offer.showing(key) else preferredDayKey = key
    }

    /** Ob die generierte Welt zur Einheit [plan] schon gebaut auf dem Geraet liegt (blockierend). */
    private fun generatedReady(plan: IndoorDayPlan): Boolean = runCatching {
        val profile = de.trailscape.core.indoor.dayGeneratedProfile(plan.goal!!)
        val wp = generatedWorlds.plan(profile, quality, "")
        worlds.get(wp.worldId)?.complete == true
    }.getOrDefault(false)

    /** Weg der Karte: echte Strecke oder generierte Welt. */
    fun setDayPath(path: IndoorDayPath) {
        (_day.value as? IndoorDayUi.Offer)?.let { if (it.generated != null) _day.value = it.copy(path = path) }
    }

    /**
     * „Fahren"/„Welt bauen" auf dem Weg „Generierte Welt": Zielprofil aus der
     * Einheit, ERG-Modus, weiter zu „Welt laden" (bereit → direkt in die Fahrt).
     */
    fun rideDayGenerated() {
        if (!_pair.value.canContinue) {
            openPair()
            return
        }
        val goal = dayPlan?.goal ?: return
        val profile = runCatching { de.trailscape.core.indoor.dayGeneratedProfile(goal) }.getOrNull() ?: return
        val option = IndoorRouteOption.Generated(profile, texts.dayGeneratedName(dayPlan?.title ?: ""))
        dayStartPick = null
        rideDayOn(option, option.key)
    }

    /** „Andere Strecke": der naechste Vorschlag. */
    fun nextDaySuggestion() {
        (_day.value as? IndoorDayUi.Offer)?.let { _day.value = it.next() }
    }

    /**
     * „Fahren" auf der Karte: waehlt die Strecke, den ERG-Modus und den
     * Startpunkt des Vorschlags und geht weiter — ohne weitere Auswahl. Eine
     * Katalogstrecke wird erst geladen (danach geht es von selbst weiter), eine
     * neue Runde oeffnet die Routensuche ([onPlanRoute]).
     */
    fun rideDaySuggestion() {
        val s = (_day.value as? IndoorDayUi.Offer)?.current ?: return
        val match = dayMatches.firstOrNull { it.candidate.key == s.key }
        dayStartPick = match?.start?.let { st ->
            val len = match.candidate.profile?.lengthM ?: 0.0
            if (st.startM > 0 && len > 0) s.key to st.startM / len else null
        }
        // Ohne Trainer fuehrt „Fahren" erst zum Koppeln (die Strecke laedt
        // sich auch ohne Trainer).
        if (!_pair.value.canContinue &&
            (s.availability == DayRouteAvailability.READY || s.availability == DayRouteAvailability.BUILD)
        ) {
            openPair()
            return
        }
        when (s.availability) {
            DayRouteAvailability.PLAN -> dayPlan?.planTarget?.let { onPlanRoute(it) }
            DayRouteAvailability.DOWNLOAD -> {
                val id = s.key.removePrefix("catalog:")
                dayAwaitingCatalog = id
                // Die Strecke ist danach in der Liste gewaehlt; unten steht der Fortschritt.
                optionForDayKey(s.key, s.name, match)?.let(::selectRoute)
                downloadCatalog(id)
            }
            DayRouteAvailability.READY, DayRouteAvailability.BUILD -> {
                val option = optionForDayKey(s.key, s.name, match) ?: return
                rideDayOn(option, s.key)
            }
        }
    }

    private fun rideDayOn(option: IndoorRouteOption, key: String) {
        if (ergWorkout == null) return
        if (dayStartPick?.first != key) dayStartPick = null
        selectRoute(option)
        setMode(IndoorMode.ERG)
        continueFromRoute()
    }

    private fun optionForDayKey(key: String, name: String, match: DayRouteMatch?): IndoorRouteOption? = when {
        key == IndoorRouteOption.TestWorld.key -> IndoorRouteOption.TestWorld
        key.startsWith("ride:") -> _route.value.options.firstOrNull { it.key == key }
        key.startsWith("catalog:") -> IndoorRouteOption.Catalog(
            catalogId = key.removePrefix("catalog:"),
            name = name,
            distanceKm = (match?.candidate?.profile?.lengthM ?: 0.0) / 1000.0,
            ascentM = match?.candidate?.profile?.ascentM ?: 0.0,
        )
        else -> null
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
        is IndoorGhostOption.Challenge ->
            if (challengeController.rideable(ghost.challengeId)?.route?.key == selected?.key) ghost else IndoorGhostOption.None
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
            is IndoorGhostOption.Challenge -> {
                val c = challengeController.rideable(ghost.challengeId) ?: return
                if (c.route.key != selected?.key) return
                IndoorGhostOption.Challenge(c.id, c.name.ifBlank { texts.challengeFallbackName() })
            }
            IndoorGhostOption.None -> ghost
        }
        _route.update { it.copy(ghost = accepted) }
    }

    // -----------------------------------------------------------------------
    // Herausforderungen (docs/herausforderung.md)
    // -----------------------------------------------------------------------

    /** Die Strecke zur Herausforderung als Kennung des Teilens; `null` = nicht teilbar (nur bekannte Strecken und die Teststrecke). */
    private fun challengeRefOf(option: IndoorRouteOption): ChallengeRoute? = when (option) {
        IndoorRouteOption.TestWorld -> ChallengeRoute.TestWorld
        is IndoorRouteOption.Catalog -> catalogController.installedWorld(option.catalogId)?.version?.let { routeOfKey(option.key, it) }
        else -> null
    }

    /** Liest die gespeicherten Herausforderungen neu (beim Öffnen der Startseite). */
    fun refreshChallenges() = challengeController.refresh()

    fun deleteChallenge(id: String) {
        challengeController.delete(id)
        _route.update { if ((it.ghost as? IndoorGhostOption.Challenge)?.challengeId == id) it.copy(ghost = IndoorGhostOption.None) else it }
    }

    /** Eine Datei wurde gewählt: prüft sie und zeigt die Vorschau (`null` = nicht lesbar oder zu groß). */
    fun importChallengeFile(bytes: ByteArray?) = challengeController.importBytes(bytes)

    fun confirmChallengeImport() = challengeController.confirmImport()

    fun dismissChallengeImport() = challengeController.dismissImport()

    /**
     * „Gegen <Name> fahren": wählt die Strecke der Herausforderung und sie als
     * Mitfahrer. Nur, wenn die Strecke in der passenden Version bereitliegt.
     *
     * @param routeName Anzeigename der Strecke in der Sprache der Oberfläche.
     */
    fun useChallenge(id: String, routeName: String) {
        val c = challengeController.rideable(id) ?: return
        val option: IndoorRouteOption = _route.value.options.firstOrNull { it.key == c.route.key } ?: when (val r = c.route) {
            ChallengeRoute.TestWorld -> IndoorRouteOption.TestWorld
            is ChallengeRoute.Catalog -> {
                val e = catalogController.entry(r.id) ?: return
                IndoorRouteOption.Catalog(r.id, routeName.ifBlank { e.name.de }, e.lengthM / 1000.0, e.ascentM)
            }
        }
        selectRoute(option)
        setGhost(IndoorGhostOption.Challenge(id, ""))
    }

    /** Öffnet die Vorschau zum Teilen der eben beendeten Fahrt. */
    fun openChallengeShare() = challengeController.openShare(
        route = shareRoute,
        points = pendingRide?.points.orEmpty(),
        ref = shareRef,
        dateIso = rideDateIso,
        reply = challengeOpponent != null,
    )

    fun setChallengeShareName(name: String) = challengeController.setShareName(name)

    fun setChallengeShareDate(include: Boolean) = challengeController.setShareIncludeDate(include)

    fun closeChallengeShare() = challengeController.closeShare()

    /** Nutzeraktion „Laden"/„Fortsetzen"/„Aktualisieren" einer bekannten Strecke. */
    fun downloadCatalog(id: String) = catalogController.download(id)

    /** Bricht das Laden ab; der geladene Teil bleibt fuer „Fortsetzen". */
    fun cancelCatalogDownload(id: String) = catalogController.cancel(id)

    /** Verwirft einen angefangenen Download. */
    fun discardCatalogDownload(id: String) = catalogController.discard(id)

    /** Nutzeraktion „Katalog aktualisieren". */
    fun refreshCatalog() = catalogController.refreshCatalog()

    fun dismissCatalogRefresh() = catalogController.dismissRefreshResult()

    /**
     * Weiter zu „Welt laden". Eine bekannte Strecke mit fertiger Welt faehrt
     * direkt los (ohne Voranschlag und ohne Netz).
     */
    fun continueFromRoute() {
        // Ohne Trainer (oder Demo) geht es nicht in die Fahrt; die Startseite
        // sagt das und bietet „Koppeln" an.
        if (!_route.value.canContinue || !_pair.value.canContinue) return
        _stage.value = IndoorStage.WORLD
        val sel = _route.value.selected
        prepareWorld(autoStart = sel is IndoorRouteOption.Catalog || sel is IndoorRouteOption.Generated)
    }

    // -----------------------------------------------------------------------
    // 3. Welt laden
    // -----------------------------------------------------------------------

    /** Bereitet die gewaehlte Strecke auf und rechnet den Voranschlag (ohne Netz). */
    fun prepareWorld(autoStart: Boolean = false) {
        val option = _route.value.selected ?: return
        val previous = cancelRunningWorldJob()
        resetWorld()
        serverController.close()
        _world.value = IndoorWorldUi.Preparing
        val token = WorldJobToken().also {
            it.autoStart = autoStart
            worldToken = it
        }
        worldJob = scope.launch {
            // Ein abgeloester Testwelt-Bau schreibt sonst noch ins selbe Verzeichnis.
            previous?.join()
            val ui = withContext(io) {
                worldGuard(canRetry = false) {
                    when (option) {
                        IndoorRouteOption.TestWorld -> prepareTestWorld(token)
                        is IndoorRouteOption.Saved -> prepareSaved(option, token)
                        is IndoorRouteOption.Catalog -> prepareCatalog(option, token)
                        is IndoorRouteOption.Generated -> prepareGenerated(option, token)
                    }
                }
            }
            commitWorld(token, ui)
            if (token === worldToken) openServerFor(option, ui)
        }
    }

    /**
     * Bietet fuer eine eigene Tour den Server-Bau an (fragt den eigenen Server
     * nach dem Bau-Dienst, ohne Streckendaten) — auch, wenn schon eine auf dem
     * Geraet gebaute Welt bereitliegt (dann ersetzt die Server-Welt sie).
     */
    private fun openServerFor(option: IndoorRouteOption, ui: IndoorWorldUi) {
        val ride = selectedRide
        if (option !is IndoorRouteOption.Saved || ride == null) {
            serverController.close()
            return
        }
        val track = ride.points.map { it.lat to it.lon }
        when (ui) {
            is IndoorWorldUi.Plan -> serverController.open(ride.id, track, hasDeviceWorld = false)
            is IndoorWorldUi.Ready -> if (ui.fromServer) serverController.close() else serverController.open(ride.id, track, hasDeviceWorld = true)
            else -> serverController.close()
        }
    }

    /**
     * Nutzeraktion „Auf dem Server in Laserqualitaet bauen": Strecke wie fuer
     * den Bau auf dem Geraet aufbereiten (Belagsabgleich), Welt-ID planen, an
     * den eigenen Server schicken.
     */
    fun buildOnServer() {
        val ride = selectedRide ?: return
        if (serverController.busy) return
        val name = routeName
        serverController.showSending()
        scope.launch {
            val prepared = withContext(io) {
                try {
                    val sourced = sourcing.fromRide(ride, name, rematch = true).getOrThrow()
                    val plan = indoorCatching(IndoorPart.WORLD) { parts.worldBuilder().plan(sourced.route, quality) }.getOrThrow()
                    Result.success(sourced.route to plan.worldId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
            }
            prepared.fold(
                onSuccess = { (route, worldId) -> serverController.start(ride.id, name, route, quality, worldId) },
                onFailure = { serverController.showFailure(technicalDetailOf(it)) },
            )
        }
    }

    /** „Abbrechen" des Server-Baus: Auftrag auf dem Server loeschen. */
    fun cancelServerBuild() = serverController.cancel()

    /** „Erneut versuchen" nach einem Fehler des Server-Baus. */
    fun retryServerBuild() {
        val ui = serverController.ui.value
        if (ui is IndoorServerUi.Failed && ui.canResume) serverController.resume() else buildOnServer()
    }

    private fun onServerWorldInstalled(rideId: String, dir: File, manifest: WorldManifest, laser: String?, replaced: Boolean) {
        if (_stage.value != IndoorStage.WORLD || selectedRide?.id != rideId) return
        cancelRunningWorldJob()
        worldManifest = manifest
        worldDir = dir
        _world.value = IndoorWorldUi.Ready(routeName, manifest.attribution, fromServer = true, serverLaser = laser, replacedDeviceWorld = replaced)
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
        if (token.autoStart && ui is IndoorWorldUi.Ready && _stage.value == IndoorStage.WORLD) startRide(withWorld = true)
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

    /** Bekannte Strecke: Welt liegt fertig vor, die Strecke kommt aus ihrem Manifest. */
    private fun prepareCatalog(option: IndoorRouteOption.Catalog, token: WorldJobToken): IndoorWorldUi {
        val routeName = option.name
        token.routeName = routeName
        val installed = catalogController.installedWorld(option.catalogId)
            ?: return IndoorWorldUi.RouteUnavailable(
                IndoorProblem(IndoorPart.APP, false, "Katalogwelt ${option.catalogId} nicht vorhanden"),
            )
        val route = indoorCatching(IndoorPart.CORE) { worldRouteOf(installed.manifest, routeName) }
            .getOrElse { return IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.CORE, it)) }
        token.baseRoute = route
        token.manifest = installed.manifest
        token.dir = installed.dir
        return IndoorWorldUi.Ready(routeName, installed.manifest.attribution)
    }

    /**
     * Generierte Welt der Einheit des Tages: Voranschlag ohne Netz; liegt die
     * Welt schon gebaut vor, ist sie sofort bereit.
     */
    private fun prepareGenerated(option: IndoorRouteOption.Generated, token: WorldJobToken): IndoorWorldUi {
        token.routeName = option.name
        token.generated = option
        val plan = indoorCatching(IndoorPart.WORLD) { generatedWorlds.plan(option.profile, quality, option.name) }
            .getOrElse { return IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.WORLD, it)) }
        token.plan = plan
        token.baseRoute = plan.route
        val dir = runCatching { worlds.worldDirFor(plan.worldId) }
            .getOrElse { return IndoorWorldUi.RouteUnavailable(problemOf(IndoorPart.APP, it)) }
        readManifest(dir)?.let { m ->
            token.manifest = m
            token.dir = dir
            return IndoorWorldUi.Ready(option.name, m.attribution)
        }
        val resumable = runCatching { worlds.get(plan.worldId)?.let { !it.complete } ?: false }.getOrDefault(false)
        return planUi(plan, resumable, option.name)
    }

    private fun buildGenerated(option: IndoorRouteOption.Generated, token: WorldJobToken): IndoorWorldUi {
        token.generated = option
        val plan = worldPlan ?: indoorCatching(IndoorPart.WORLD) { generatedWorlds.plan(option.profile, quality, option.name) }
            .getOrElse { return failedOf(it, canRetry = true) }
        token.plan = plan
        token.baseRoute = plan.route
        val dir = runCatching { worlds.worldDirFor(plan.worldId) }.getOrElse { return failedOf(it, canRetry = false) }
        runCatching { WorldLibrary.writeSource(dir, GENERATED_SOURCE_PREFIX + java.lang.Long.toHexString(option.profile.seed), option.name) }
        val listener = buildListener(token)
        return indoorCatching(IndoorPart.WORLD) { generatedWorlds.build(plan, option.profile, dir, listener) }.fold(
            onSuccess = { manifest ->
                token.manifest = manifest
                token.dir = dir
                IndoorWorldUi.Ready(option.name, manifest.attribution)
            },
            onFailure = { e ->
                if (causeOf<WorldBuildCancelled>(e) != null || token.stopRequested) {
                    planUi(plan, resumable = true, option.name)
                } else {
                    failedOf(e, canRetry = true)
                }
            },
        )
    }

    private fun buildListener(token: WorldJobToken): WorldBuildListener {
        val progress = BuildProgress()
        return object : WorldBuildListener {
            override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
                if (token.stopRequested || token !== worldToken) return
                _world.value = progress.report(phase, done, total)
                if (token.stopRequested) markCancelling()
            }

            override fun isCancelled(): Boolean = token.stopRequested || token !== worldToken
        }
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
            return IndoorWorldUi.Ready(
                routeName,
                existingManifest.attribution,
                fromServer = existing.server != null,
                serverLaser = existing.server?.laserSource,
            )
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
        val current = _world.value
        if (current !is IndoorWorldUi.Plan && current !is IndoorWorldUi.Failed) return
        // Nie zugleich auf dem Server und auf dem Geraet bauen.
        if (serverController.busy) return
        (_route.value.selected as? IndoorRouteOption.Generated)?.let { generated ->
            val previous = cancelRunningWorldJob()
            _world.value = IndoorWorldUi.Building(WorldBuildPhase.ELEVATION, 0, 0, 0f)
            val token = WorldJobToken().also { worldToken = it }
            worldJob = scope.launch {
                previous?.join()
                val ui = withContext(io) { worldGuard(canRetry = true) { buildGenerated(generated, token) } }
                commitWorld(token, ui)
            }
            return
        }
        val ride = selectedRide ?: return
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
        val progress = BuildProgress()
        val listener = object : WorldBuildListener {
            override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
                if (token.stopRequested || token !== worldToken) return
                _world.value = progress.report(phase, done, total)
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
        shareRoute = route
        shareRef = routeUi.selected?.let { challengeRefOf(it) }
        challengeOpponent = (routeUi.ghost as? IndoorGhostOption.Challenge)?.label
        val demo = _pair.value.demoTrainer
        val mode = if (routeUi.mode == IndoorMode.ERG && ergWorkout != null) IndoorMode.ERG else IndoorMode.SIM

        finishing = false
        pendingRide = null
        rideStartRouteM = null
        _summary.value = null
        val ghostLabel = ghostLabelOf(routeUi.ghost)
        // Fortsetzen: die gemerkte Stelle dieser Strecke ist die Vorauswahl.
        val resumeKey = routeUi.selected?.let { resumeKeyOf(it) }
        val resumeM = resumeKey?.let { key ->
            runCatching { IndoorStartPoints.offer(readResume(key), route.lengthM, route.isLoop) }.getOrNull()
        }
        // Einheit des Tages: der vorgeschlagene Startpunkt (anteilig auf die
        // Strecke dieser Fahrt umgerechnet) geht vor der gemerkten Stelle;
        // „Weiter bei …" bleibt als Angebot auf der Startpunkt-Karte.
        val dayStartM = dayStartPick?.takeIf { mode == IndoorMode.ERG && it.first == routeUi.selected?.key }
            ?.let { IndoorStartPoints.clamp(it.second * route.lengthM, route.lengthM, route.isLoop) }
            ?.takeIf { it > 0 }
        val initialM = dayStartM ?: resumeM
        dismissedDayEnd = null
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
            dayWorkout = if (mode == IndoorMode.ERG) ergWorkout else null,
            resumeKey = resumeKey,
            resumeM = resumeM,
            startMaxM = IndoorStartPoints.maxStartM(route.lengthM, route.isLoop),
            routeLengthM = route.lengthM,
            routeIsLoop = route.isLoop,
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
        // Vor dem ersten Schritt versetzt: Darstellung, Kacheln und erste Vorgabe gelten schon der Startstelle.
        initialM?.let { d -> indoorCatching(IndoorPart.CORE) { session.moveStartTo(d) } }

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
                if (caps == config.capabilities) {
                    null
                } else {
                    parts.createSession(config.copy(capabilities = caps)).also { s -> initialM?.let { s.moveStartTo(it) } }
                }
            },
            // Vor jeder Fahrt steht die Startpunkt-Karte, bis „Losfahren" kommt. Mit
            // echtem Trainer gilt auch der erste Tritt, solange der Startpunkt
            // nicht angefasst wurde (wer einfach lostritt, will an der Vorauswahl
            // starten); der Demo-Trainer „tritt" sofort und wartet deshalb immer.
            holdStart = true,
            releaseOnFirstStroke = !demo,
        )
        activeRuntime = runtime
        renderer?.let { r ->
            val loaded = indoorCatching(IndoorPart.RENDER) {
                r.setListener(runtime.rendererListener)
                r.setCamera(CameraMode.CHASE)
                r.loadWorld(dir!!, manifest!!)
            }
            skyOrigin = manifest?.origin
            loaded.exceptionOrNull()?.let { e ->
                runtime.rendererListener.onFailure(RenderFailure.INTERNAL, problemOf(IndoorPart.RENDER, e).detail)
            }
            _renderer.value = r
            applySky(force = true)
            indoorCatching(IndoorPart.RENDER) { r.setOtherRidersEnabled(_pair.value.otherRiders) }
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
        rs.session?.let { s ->
            if (rideStartRouteM == null && s.phase != IndoorPhase.READY && !(s.phase == IndoorPhase.PAUSED && s.elapsedS == 0)) {
                // Beim ersten Tritt: Stelle minus das schon Gefahrene (hier fast 0).
                var d = s.routeDistM - s.totals.distanceM
                if (d < 0 && st.routeIsLoop) d += st.routeLengthM
                rideStartRouteM = d.coerceAtLeast(0.0)
            }
        }
        applySky(force = false)
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
            start = startUiOf(rs, st),
            dayEnd = dayEndOf(rs, st),
        )
    }

    /** Einheit und Strecke enden nicht gleichzeitig ([dayRideEnd]); `null` = nichts anzubieten. */
    private fun dayEndOf(rs: IndoorRuntimeState, st: RideStatic): IndoorDayEndUi? {
        val s = rs.session ?: return null
        val w = st.dayWorkout ?: return null
        val kind = runCatching { dayRideEnd(s) }.getOrDefault(DayRideEnd.NONE)
        if (kind == DayRideEnd.NONE || kind == dismissedDayEnd) return null
        val remaining = s.erg?.let { e -> runCatching { ergRemainingS(w, e) }.getOrDefault(0) } ?: 0
        return IndoorDayEndUi(kind, ((remaining + 59) / 60).coerceAtLeast(if (kind == DayRideEnd.ROUTE_DONE_EARLY) 1 else 0))
    }

    /** „Ausfahren" bzw. Hinweis weggetippt — fuer diese Fahrt nicht wieder anbieten. */
    fun dismissDayEnd() {
        dismissedDayEnd = _ride.value?.dayEnd?.kind ?: return
        republishRide()
    }

    /** Startpunkt-Wahl fuer das HUD, solange sich der Fahrer versetzen laesst. */
    private fun startUiOf(rs: IndoorRuntimeState, st: RideStatic): IndoorStartUi? {
        val s = rs.session ?: return null
        if (!rs.canMoveStart || rs.finished || st.profile == null) return null
        if (s.phase != IndoorPhase.READY && s.phase != IndoorPhase.PAUSED) return null
        return IndoorStartUi(
            selectedM = s.routeDistM,
            maxM = st.startMaxM,
            // „Weiter bei …" gilt nur vor dem ersten Tritt; in der Pause wird frei versetzt.
            resumeM = if (s.phase == IndoorPhase.READY) st.resumeM else null,
            held = rs.startHeld,
            pedalStarts = rs.pedalStarts,
        )
    }

    private fun republishRide() {
        activeRuntime?.let { publishRide(it.state.value) }
    }

    private fun ghostLabelOf(ghost: IndoorGhostOption): String? = when (ghost) {
        IndoorGhostOption.None -> null
        is IndoorGhostOption.PastRide -> ghost.label
        is IndoorGhostOption.ConstantPower -> texts.ghostConstantPowerLabel(ghost.watts)
        is IndoorGhostOption.Challenge -> ghost.label
    }

    private fun ghostSpecOf(ghost: IndoorGhostOption): GhostSpec = when (ghost) {
        IndoorGhostOption.None -> GhostSpec.None
        is IndoorGhostOption.ConstantPower -> GhostSpec.ConstantPower(ghost.watts.toDouble())
        is IndoorGhostOption.PastRide -> {
            val ride = selectedRide?.takeIf { it.id == ghost.rideId }
            val timed = ride?.points?.count { it.time != null } ?: 0
            if (ride != null && timed >= 2) GhostSpec.PastRide(ghost.label, ride.points) else GhostSpec.None
        }
        is IndoorGhostOption.Challenge ->
            challengeController.rideable(ghost.challengeId)?.let { GhostSpec.Shared(ghost.label, it.curve) } ?: GhostSpec.None
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

    // -----------------------------------------------------------------------
    // Startpunkt und Fortsetzen
    // -----------------------------------------------------------------------

    /** Setzt den Fahrer an [routeDistM] (Meter auf der Strecke) — vor dem ersten Tritt und in der Pause. */
    fun moveStart(routeDistM: Double) {
        val st = rideStatic ?: return
        if (_ride.value?.start == null) return
        activeRuntime?.moveStart(IndoorStartPoints.clamp(routeDistM, st.routeLengthM, st.routeIsLoop))
    }

    /** Feinknoepfe −/+: um [deltaM] Meter verschieben. */
    fun nudgeStart(deltaM: Double) {
        val st = rideStatic ?: return
        val current = _ride.value?.start ?: return
        activeRuntime?.moveStart(IndoorStartPoints.nudge(current.selectedM, deltaM, st.routeLengthM, st.routeIsLoop))
    }

    /** „Losfahren": Die Fahrt beginnt mit dem ersten Tritt an der gewaehlten Stelle. */
    fun releaseStart() {
        activeRuntime?.releaseStart()
    }

    private fun resumeKeyOf(option: IndoorRouteOption): String = KEY_RESUME_PREFIX + option.key

    private fun readResume(key: String): IndoorResumePoint? =
        runCatching { IndoorResumePoint.fromJson(store.getString(key)) }.getOrNull()

    /**
     * Merkt die Stelle, an der die Fahrt endete, bzw. loescht sie am Ziel
     * (Regeln: [IndoorStartPoints.afterStop]).
     */
    private fun rememberPosition(st: RideStatic?, last: IndoorSessionState?) {
        val key = st?.resumeKey ?: return
        last ?: return
        val point = IndoorStartPoints.afterStop(last.routeDistM, st.routeLengthM, st.routeIsLoop, wallClock())
        runCatching {
            if (point == null) store.remove(key) else store.setString(key, point.toJson())
        }
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
            rememberPosition(rideStatic, runtime.state.value.session)
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
        val startKm = rideStartRouteM?.takeIf { it >= MIN_SHOWN_START_M }?.let { it / 1000.0 }
        pendingRide = record?.let { r ->
            val rideName = if (startKm != null) texts.rideNameFrom(name, startKm) else texts.rideName(name)
            runCatching { indoorRideToRide(r, rideName) }.getOrNull()
        }
        rideDateIso = record?.let {
            java.time.Instant.ofEpochMilli(it.startedAtEpochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
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
            startKm = startKm,
            challengeOpponent = challengeOpponent,
            // Teilbar: bekannte Strecke oder Teststrecke, ab dem Start, lang genug, mit Aufzeichnung.
            canShareChallenge = pendingRide != null && shareRef != null && startKm == null &&
                record != null && record.totals.movingS >= MIN_INDOOR_RIDE_S,
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
        dayStartPick = null
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
        // Die Startseite ist der Anfang: Zurueck verlaesst den Ablauf.
        IndoorStage.ROUTE -> false
        IndoorStage.PAIR, IndoorStage.SETTINGS -> {
            _stage.value = IndoorStage.ROUTE
            true
        }
        IndoorStage.WORLD -> {
            cancelRunningWorldJob()
            serverController.close()
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
        skyOrigin = null
        indoorCatching(IndoorPart.RENDER) {
            r.setListener(null)
            r.destroy()
        }
    }

    /** Gibt alles frei; eine laufende Fahrt wird ohne Aufzeichnung verworfen. */
    fun release() {
        cancelRunningWorldJob()
        serverController.close()
        catalogController.release()
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

        /**
         * Vorsilbe der gemerkten Stelle je Strecke im [KeyValueStore]; dahinter
         * der Schluessel der Streckenwahl ([IndoorRouteOption.key]).
         */
        const val KEY_RESUME_PREFIX = "trailscape.indoor.resume."

        /** Ab hier zeigen Auswertung und Tourname „ab km …" (darunter gilt es als Start am Anfang). */
        const val MIN_SHOWN_START_M = 50.0

        /** Schluessel der Gangtabelle (Enum-Name von [VirtualGearTable]) im [KeyValueStore]. */
        const val KEY_GEAR_TABLE = "trailscape.indoor.gear_table"

        /** Schluessel der Tageszeit der 3D-Welt (Enum-Name von [IndoorTimeOfDay]). */
        const val KEY_TIME_OF_DAY = "trailscape.indoor.time_of_day"
        const val KEY_OTHER_RIDERS = "trailscape.indoor.other_riders"

        /** „Automatisch“: Himmel waehrend der Fahrt alle 10 Minuten der Uhr nachfuehren. */
        const val SKY_REFRESH_MS = 10 * 60_000L

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
            today: () -> TodayRoute? = { null },
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
                    today = today,
                    dayFinder = IndoorDayRouteFinder(
                        AppServices.rideStorage,
                        AppServices.indoorWorldLibrary,
                        AppServices.indoorCatalogStore,
                    ),
                    devices = IndoorDevices.remembered(app),
                    rematchRouter = OfflineFirstRematchRouter(app),
                    heartRate = { BleHeartRateSource(app) },
                    catalogStore = AppServices.indoorCatalogStore,
                    catalogDownloader = AppServices.indoorCatalogDownloader,
                    catalogOnline = { AppServices.isNetworkAvailable() },
                    serverDeps = AppServices.indoorServerDeps,
                    challengeStore = AppServices.challengeStore,
                    onRideSaved = onRideSaved,
                ) as T
            }
        }
    }
}

/**
 * Fasst die Fortschrittsmeldungen eines Weltbaus zu **einem** Balken zusammen.
 *
 * Der Weltbau meldet Luftbilder, Geometrie und Speichern verschraenkt (die
 * Kacheln laufen als Fliessband durch alle drei). Wer jede Meldung einzeln
 * anzeigt, laesst Balken und Text zwischen „Luftbilder laden 43 %" und „Welt
 * speichern 84 %" springen. Hier zaehlt deshalb der Stand jeder Phase in eine
 * gewichtete Summe, die nie zurueckgeht; angezeigt wird die frueheste Phase,
 * die noch nicht fertig ist.
 */
internal class BuildProgress {
    private val done = IntArray(WorldBuildPhase.entries.size)
    private val total = IntArray(WorldBuildPhase.entries.size)
    private var shown = 0f

    @Synchronized
    fun report(phase: WorldBuildPhase, phaseDone: Int, phaseTotal: Int): IndoorWorldUi.Building {
        val i = phase.ordinal
        total[i] = phaseTotal.coerceAtLeast(0)
        done[i] = maxOf(done[i], phaseDone).coerceIn(0, total[i])
        // Wer eine spaetere Phase meldet, hat die Ladephasen davor hinter sich.
        for (earlier in 0 until minOf(i, WorldBuildPhase.AERIAL.ordinal)) {
            if (total[earlier] == 0) total[earlier] = 1
            done[earlier] = total[earlier]
        }
        var sum = 0f
        for (p in WorldBuildPhase.entries) sum += WEIGHTS[p.ordinal] * part(p.ordinal)
        shown = maxOf(shown, sum.coerceIn(0f, 1f))
        val current = WorldBuildPhase.entries.firstOrNull { total[it.ordinal] > 0 && done[it.ordinal] < total[it.ordinal] } ?: phase
        return IndoorWorldUi.Building(current, done[current.ordinal], total[current.ordinal], shown)
    }

    private fun part(i: Int): Float = if (total[i] > 0) done[i].toFloat() / total[i] else 0f

    private companion object {
        /** Anteil je Phase in der Reihenfolge von [WorldBuildPhase]; Summe 1. */
        val WEIGHTS = floatArrayOf(0.08f, 0.12f, 0.30f, 0.25f, 0.25f)
    }
}
