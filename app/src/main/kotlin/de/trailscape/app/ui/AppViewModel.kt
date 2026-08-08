package de.trailscape.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.RideStorage
import de.trailscape.core.HealthConnection
import de.trailscape.core.HealthSyncReport
import de.trailscape.core.HealthSyncService
import de.trailscape.core.HttpClient
import de.trailscape.core.KeyValueStore
import de.trailscape.core.Ride
import de.trailscape.core.RideLoad
import de.trailscape.core.SyncConfig
import de.trailscape.core.SyncResult
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingPlanStore
import de.trailscape.core.TrainingProfile
import de.trailscape.core.VitalsSummary
import de.trailscape.core.getSyncConfig
import de.trailscape.core.loadPlan
import de.trailscape.core.savePlan
import de.trailscape.core.syncRides
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Die vier Hauptbereiche als navigierbarer Wert (siehe [AppViewModel.requestTab]). */
enum class AppTab { MAP, RIDES, TRAINING, MORE }

/**
 * Zentraler, geteilter App-Zustand — Kotlin-Port von `AppState` aus
 * `lib/state.dart`.
 *
 * ## Nutzung durch die Screens
 * Es gibt genau **eine** Instanz pro Activity: `TrailscapeApp()` holt sie
 * ueber `viewModel()` im Activity-Scope und reicht sie jedem Screen als
 * Parameter herein. Screens erzeugen **nie** ein eigenes [AppViewModel] und
 * halten selbst keinen dauerhaften Zustand, der hier hingehoert.
 *
 * Alle beobachtbaren Werte sind [StateFlow]s; in Compose:
 * ```kotlin
 * val rides by appViewModel.rides.collectAsStateWithLifecycle()
 * ```
 *
 * ## Nebenlaeufigkeit
 * Jede Datei-/Netz-/Health-Connect-Operation laeuft auf [Dispatchers.IO], die
 * Trainingsauswertung auf [Dispatchers.Default]; die Flows werden immer im
 * Main-Thread-freien `viewModelScope` fortgeschrieben. `void`-Methoden feuern
 * und vergessen (Fehler landen als Text in [messages]), `suspend`-Methoden
 * geben ihr Ergebnis bzw. ihren Fehler an den Aufrufer weiter — das braucht
 * z. B. der Mehr-Screen, um eine Health-Connect-Fehlermeldung anzuzeigen.
 *
 * ## Unterschiede zum Dart-Original (bewusst)
 *  * Statt `ChangeNotifier` + `notifyListeners()` ein Satz getrennter
 *    [StateFlow]s: Compose rekomponiert dadurch nur die Screens, die den
 *    jeweiligen Wert wirklich lesen.
 *  * Die Auswahl wird als **ID** gehalten und die Tour daraus abgeleitet
 *    ([selectedRide]). Das Dart-Original haengt das Objekt nach jedem
 *    `loadRides()` von Hand um; hier faellt dieser Schritt weg und die
 *    Auswahl kann per Konstruktion nicht auf eine veraltete Tour zeigen.
 *  * `insights` ist kein lazy Cache mit manueller Invalidierung, sondern ein
 *    aus (Touren, Vitaldaten, Profil) abgeleiteter Flow. Der Cache der
 *    unkalibrierten Tourlasten bleibt erhalten (siehe [baseLoadCache]), die
 *    Invalidierung passiert aber nicht mehr von Hand.
 *  * Zusaetzlich zum Original: [renameRide], [mapStyle], [plan],
 *    [syncConfig], [tabRequest] und [messages] — in Flutter lagen diese
 *    Zustaende verstreut in den einzelnen Screens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val rideStorage: RideStorage = AppServices.rideStorage,
    private val keyValueStore: KeyValueStore = AppServices.keyValueStore,
    private val trainingPlanStore: TrainingPlanStore = AppServices.trainingPlanStore,
    /**
     * Zugriff auf Health Connect. Der Mehr-Screen benutzt ihn fuer Status,
     * Verbindungsaufbau und manuellen Sync direkt — genau wie in Dart
     * (`AppState.healthSync`).
     */
    val healthSync: HealthSyncService = AppServices.healthSyncService,
    private val httpClient: HttpClient = AppServices.httpClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    // -------------------------------------------------------------------------
    // Meldungen (Snackbar)
    // -------------------------------------------------------------------------

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Einmalige, kurze Hinweise fuer eine Snackbar (deutschsprachig). Der
     * Screen, der gerade sichtbar ist, sammelt sie ein:
     * ```kotlin
     * LaunchedEffect(Unit) { appViewModel.messages.collect { snackbarHostState.showSnackbar(it) } }
     * ```
     */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Stellt einen Hinweis in die Warteschlange (verwirft ihn bei vollem Puffer). */
    fun showMessage(text: String) {
        _messages.tryEmit(text)
    }

    // -------------------------------------------------------------------------
    // Tab-Wechsel aus einem Screen heraus
    // -------------------------------------------------------------------------

    private val _tabRequest = MutableStateFlow<AppTab?>(null)

    /**
     * Bitte an die Navigationshuelle, zu einem anderen Tab zu wechseln —
     * der Ersatz fuer den `onShowMap`-Callback der Flutter-Screens. Wird von
     * `TrailscapeApp()` beobachtet und danach mit [consumeTabRequest]
     * quittiert; Screens rufen nur [requestTab].
     */
    val tabRequest: StateFlow<AppTab?> = _tabRequest.asStateFlow()

    fun requestTab(tab: AppTab) {
        _tabRequest.value = tab
    }

    fun consumeTabRequest() {
        _tabRequest.value = null
    }

    // -------------------------------------------------------------------------
    // Touren
    // -------------------------------------------------------------------------

    private val _rides = MutableStateFlow<List<Ride>>(emptyList())

    /** Alle gespeicherten Touren, **neueste zuerst** (wie `listRides()` in Dart). */
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    private val _ridesLoading = MutableStateFlow(true)

    /** Ob gerade vom Datentraeger gelesen wird (erster Lauf: `true`). */
    val ridesLoading: StateFlow<Boolean> = _ridesLoading.asStateFlow()

    private val _selectedRideId = MutableStateFlow<String?>(null)

    /** ID der ausgewaehlten Tour, oder `null`. */
    val selectedRideId: StateFlow<String?> = _selectedRideId.asStateFlow()

    /**
     * Die ausgewaehlte Tour, abgeleitet aus [rides] und [selectedRideId]:
     * verschwindet automatisch, wenn die Tour geloescht wurde, und zeigt nach
     * einem HF-Merge automatisch auf die angereicherte Fassung.
     */
    val selectedRide: StateFlow<Ride?> = combine(_rides, _selectedRideId) { list, id ->
        if (id == null) null else list.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Laedt alle gespeicherten Touren neu. Die Auswahl bleibt erhalten. */
    fun refreshRides() {
        viewModelScope.launch { reloadRides() }
    }

    private suspend fun reloadRides() {
        _ridesLoading.value = true
        try {
            _rides.value = withContext(io) { rideStorage.listRides() }
        } finally {
            _ridesLoading.value = false
        }
    }

    /** Speichert eine neue Tour, laedt die Liste neu und waehlt sie aus. */
    fun addRide(ride: Ride) {
        viewModelScope.launch {
            withContext(io) { rideStorage.saveRide(ride) }
            reloadRides()
            select(ride.id)
        }
    }

    /**
     * Speichert mehrere Touren, ohne die Auswahl zu aendern (z. B. beim
     * Health-Connect-Import). Laedt die Liste nur neu, wenn tatsaechlich
     * etwas gespeichert wurde.
     */
    fun addRides(newRides: List<Ride>) {
        if (newRides.isEmpty()) return
        viewModelScope.launch {
            withContext(io) { rideStorage.saveRides(newRides) }
            reloadRides()
        }
    }

    /**
     * Loescht eine Tour und laedt die Liste neu. Die Auswahl loest sich
     * dadurch von selbst auf (siehe [selectedRide]).
     */
    fun removeRide(id: String) {
        viewModelScope.launch {
            withContext(io) { rideStorage.deleteRide(id) }
            reloadRides()
        }
    }

    /**
     * Benennt eine Tour um. Leere Namen werden ignoriert, fuehrende/folgende
     * Leerzeichen abgeschnitten. Kein Dart-Vorbild — die Flutter-App konnte
     * Touren nicht umbenennen.
     */
    fun renameRide(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val ride = _rides.value.firstOrNull { it.id == id } ?: return@launch
            if (ride.name == trimmed) return@launch
            withContext(io) { rideStorage.saveRide(ride.copy(name = trimmed)) }
            reloadRides()
        }
    }

    /** Setzt die ausgewaehlte Tour (oder hebt die Auswahl mit `null` auf). */
    fun select(rideId: String?) {
        _selectedRideId.value = rideId
    }

    // -------------------------------------------------------------------------
    // Trainingsprofil
    // -------------------------------------------------------------------------

    private val _profile = MutableStateFlow(defaultTrainingProfile)

    /** Vom Nutzer gepflegtes Trainingsprofil (Alter, Gewicht, Overrides). */
    val profile: StateFlow<TrainingProfile> = _profile.asStateFlow()

    /**
     * Uebernimmt ein neues Profil und speichert es. Die abgeleiteten Werte in
     * [insights] rechnen sich daraufhin selbst neu.
     */
    fun setProfile(profile: TrainingProfile) {
        _profile.value = profile
        viewModelScope.launch {
            withContext(io) {
                runCatching {
                    keyValueStore.setString(PROFILE_STORAGE_KEY, profile.toJson().toString())
                }
            }
        }
    }

    private fun readProfile(): TrainingProfile = runCatching {
        val raw = keyValueStore.getString(PROFILE_STORAGE_KEY) ?: return defaultTrainingProfile
        val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: return defaultTrainingProfile
        TrainingProfile.fromJson(parsed)
    }.getOrDefault(defaultTrainingProfile)

    // -------------------------------------------------------------------------
    // Health Connect
    // -------------------------------------------------------------------------

    private val _vitals = MutableStateFlow<VitalsSummary?>(null)

    /** Zuletzt gelesene Vitaldaten, `null` solange nie erfolgreich gelesen wurde. */
    val vitals: StateFlow<VitalsSummary?> = _vitals.asStateFlow()

    private val _lastSyncReport = MutableStateFlow<HealthSyncReport?>(null)

    /** Bericht des letzten Imports — Grundlage der Diagnose im Mehr-Tab. */
    val lastSyncReport: StateFlow<HealthSyncReport?> = _lastSyncReport.asStateFlow()

    private val _healthConnection = MutableStateFlow<HealthConnection?>(null)

    /** Zuletzt ermittelter Health-Connect-Status; `null` = noch nicht geprueft. */
    val healthConnection: StateFlow<HealthConnection?> = _healthConnection.asStateFlow()

    /** Fragt den Health-Connect-Status ab und legt ihn in [healthConnection] ab. */
    suspend fun refreshHealthConnection(): HealthConnection {
        val connection = withContext(io) { healthSync.checkAvailability() }
        _healthConnection.value = connection
        return connection
    }

    /**
     * Oeffnet den Health-Connect-Berechtigungsdialog (blockiert bis zur
     * Antwort) und aktualisiert danach [healthConnection].
     */
    suspend fun requestHealthPermissions(): Boolean {
        val granted = withContext(io) { healthSync.requestPermissions() }
        refreshHealthConnection()
        return granted
    }

    /**
     * Einmaliger, stiller Hintergrund-Sync beim App-Start.
     *
     * Fragt **nie** Berechtigungen an; importiert nur, wenn Health Connect
     * bereits verbunden ist. Fehler werden verschluckt, damit ein
     * Health-Connect-Problem den App-Start nie stoert.
     */
    fun autoSyncHealth() {
        viewModelScope.launch {
            runCatching {
                val connection = refreshHealthConnection()
                if (!connection.isReady) return@runCatching
                val report = withContext(io) { healthSync.importWithReport(existing = _rides.value) }
                applyReport(report)
                _vitals.value = withContext(io) { healthSync.readVitals(days = VITALS_WINDOW_DAYS) }
            }
        }
    }

    /**
     * Manueller Sync, ausgeloest ueber den Mehr-Screen. Liefert die Anzahl neu
     * importierter Touren; der vollstaendige Bericht steht danach unter
     * [lastSyncReport].
     *
     * Wirft [de.trailscape.core.HealthSyncException] mit einer fuer die UI
     * geeigneten Meldung — anders als [autoSyncHealth] wird der Fehler hier
     * bewusst nicht verschluckt.
     *
     * @param reimportAll loescht zuerst den gespeicherten Import-Zeitstempel,
     *   der naechste Import betrachtet dann wieder das volle 30-Tage-Fenster.
     */
    suspend fun syncHealthNow(reimportAll: Boolean = false): Int {
        val report = withContext(io) {
            if (reimportAll) {
                healthSync.setLastImportAt(null)
            }
            healthSync.importWithReport(existing = _rides.value)
        }
        applyReport(report)
        _vitals.value = withContext(io) { healthSync.readVitals(days = VITALS_WINDOW_DAYS) }
        refreshHealthConnection()
        return report.imported.size
    }

    /**
     * Merkt sich den Bericht und persistiert alles, was er veraendert hat:
     * neue Touren **und** bestehende Touren, die um Watch-Herzfrequenz
     * angereichert wurden (gleiche ID, `saveRide` ueberschreibt sie).
     */
    private suspend fun applyReport(report: HealthSyncReport) {
        _lastSyncReport.value = report
        if (report.isEmpty) return
        withContext(io) {
            rideStorage.saveRides(report.imported)
            rideStorage.saveRides(report.mergedRides)
        }
        reloadRides()
    }

    // -------------------------------------------------------------------------
    // Abgeleitete Trainingsauswertung
    // -------------------------------------------------------------------------

    /**
     * Cache der Tourlasten **vor** Kalibrierung. Wird ausschliesslich aus dem
     * `mapLatest`-Block unten benutzt; `mapLatest` bricht den vorigen Lauf ab,
     * bevor es den naechsten startet, und [computeInsights] enthaelt keinen
     * Suspendierungspunkt — es gibt also nie zwei gleichzeitige Zugriffe.
     */
    private val baseLoadCache = mutableMapOf<String, RideLoad>()

    /**
     * Gesamte Trainingsauswertung (CTL/ATL/TSB-Serie, Ampeln, Readiness,
     * Deload, Wochenziel, VO2max, Tourlasten). Rechnet sich neu, sobald sich
     * Touren, Vitaldaten oder Profil aendern — und nur dann.
     */
    val insights: StateFlow<TrainingInsights> =
        combine(_rides, _vitals, _profile) { rides, vitals, profile ->
            Triple(rides, vitals, profile)
        }
            .mapLatest { (rides, vitals, profile) ->
                computeInsights(
                    rides = rides,
                    vitals = vitals,
                    profile = profile,
                    now = LocalDateTime.now(),
                    baseLoadCache = baseLoadCache,
                )
            }
            .flowOn(computation)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyTrainingInsights())

    /** Trainingslast einer einzelnen Tour; `null`, wenn sie unbekannt ist. */
    fun rideLoad(rideId: String): RideLoad? = insights.value.rideLoads[rideId]

    // -------------------------------------------------------------------------
    // Trainingsplan
    // -------------------------------------------------------------------------

    private val _plan = MutableStateFlow<TrainingPlan?>(null)

    /** Der gespeicherte Trainingsplan, oder `null`. */
    val plan: StateFlow<TrainingPlan?> = _plan.asStateFlow()

    /** Speichert den Plan; `null` loescht den gespeicherten Plan. */
    fun setPlan(plan: TrainingPlan?) {
        _plan.value = plan
        viewModelScope.launch {
            withContext(io) { savePlan(trainingPlanStore, plan) }
        }
    }

    // -------------------------------------------------------------------------
    // Kartenstil
    // -------------------------------------------------------------------------

    private val _mapStyle = MutableStateFlow(defaultMapStyle)

    /** Der gewaehlte Kartenstil (siehe [mapStyles]). */
    val mapStyle: StateFlow<MapStyle> = _mapStyle.asStateFlow()

    /** Waehlt einen Kartenstil und merkt sich die Auswahl. */
    fun setMapStyle(style: MapStyle) {
        _mapStyle.value = style
        viewModelScope.launch {
            withContext(io) {
                runCatching { keyValueStore.setString(MAP_STYLE_STORAGE_KEY, style.id) }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Selfhost-Sync
    // -------------------------------------------------------------------------

    private val _syncConfig = MutableStateFlow<SyncConfig?>(null)

    /** Zugangsdaten des eigenen Sync-Servers, oder `null`. */
    val syncConfig: StateFlow<SyncConfig?> = _syncConfig.asStateFlow()

    /** Speichert die Sync-Zugangsdaten; `null` entfernt sie. */
    fun setSyncConfig(config: SyncConfig?) {
        _syncConfig.value = config
        viewModelScope.launch {
            // Voll qualifiziert: der gleichnamige `:core`-Aufruf soll hier
            // nicht mit dieser Methode verwechselt werden koennen.
            withContext(io) {
                runCatching { de.trailscape.core.setSyncConfig(keyValueStore, config) }
            }
        }
    }

    /**
     * Gleicht die Touren mit dem konfigurierten Server ab und laedt die Liste
     * danach neu. Wirft, wenn kein Server konfiguriert ist oder der Abgleich
     * fehlschlaegt — die Meldung ist fuer die UI geeignet.
     */
    suspend fun syncNow(): SyncResult {
        val result = withContext(io) {
            syncRides(
                listLocal = { rideStorage.listRides() },
                saveLocal = { rideStorage.saveRide(it) },
                client = httpClient,
                store = keyValueStore,
            )
        }
        reloadRides()
        return result
    }

    // -------------------------------------------------------------------------
    // Start
    // -------------------------------------------------------------------------

    init {
        viewModelScope.launch {
            // Alles, was von der Platte kommt, in EINEM IO-Sprung — danach
            // steht der Zustand vollstaendig, bevor der erste Sync laeuft.
            val restored = withContext(io) {
                Restored(
                    profile = readProfile(),
                    plan = loadPlan(trainingPlanStore),
                    mapStyle = mapStyleById(
                        runCatching { keyValueStore.getString(MAP_STYLE_STORAGE_KEY) }.getOrNull(),
                    ),
                    syncConfig = runCatching { getSyncConfig(keyValueStore) }.getOrNull(),
                )
            }
            _profile.value = restored.profile
            _plan.value = restored.plan
            _mapStyle.value = restored.mapStyle
            _syncConfig.value = restored.syncConfig

            reloadRides()
            autoSyncHealth()
        }
    }

    private data class Restored(
        val profile: TrainingProfile,
        val plan: TrainingPlan?,
        val mapStyle: MapStyle,
        val syncConfig: SyncConfig?,
    )
}
