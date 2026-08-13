package de.trailscape.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.RideStorage
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.reminder.ReminderStore
import de.trailscape.app.update.UpdateCheckResult
import de.trailscape.app.update.UpdateChecker
import de.trailscape.core.HealthConnection
import de.trailscape.core.HealthSyncException
import de.trailscape.core.HealthSyncReport
import de.trailscape.core.HealthSyncService
import de.trailscape.core.HttpClient
import de.trailscape.core.KeyValueStore
import de.trailscape.core.ReminderSettings
import de.trailscape.core.Ride
import de.trailscape.core.RideLoad
import de.trailscape.core.RouteTarget
import de.trailscape.core.SyncConfig
import de.trailscape.core.SyncResult
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingPlanStore
import de.trailscape.core.TrainingProfile
import de.trailscape.core.VitalsSummary
import de.trailscape.core.getSyncConfig
import de.trailscape.core.healthSyncInitialWindowMs
import de.trailscape.core.loadPlan
import de.trailscape.core.savePlan
import de.trailscape.core.syncRides
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Die fuenf Hauptbereiche als navigierbarer Wert (siehe [AppViewModel.requestTab]).
 *
 * Reihenfolge wie in der Navigationsleiste (`ui/TrailscapeApp.kt`): [HOME] ist
 * die Startseite „Heute" und damit der erste Tab.
 */
enum class AppTab { HOME, MAP, RIDES, TRAINING, MORE }

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
 *    [syncConfig], [tabRequest], [pendingRouteTarget] und [messages] — in
 *    Flutter lagen diese Zustaende verstreut in den einzelnen Screens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val rideStorage: RideStorage = AppServices.rideStorage,
    private val keyValueStore: KeyValueStore = AppServices.keyValueStore,
    private val trainingPlanStore: TrainingPlanStore = AppServices.trainingPlanStore,
    /** Einstellungen der lokalen Erinnerungen (siehe [reminderSettings]). */
    private val reminderStore: ReminderStore = AppServices.reminderStore,
    /**
     * Zugriff auf Health Connect. Der Mehr-Screen benutzt ihn fuer Status,
     * Verbindungsaufbau und manuellen Sync direkt — genau wie in Dart
     * (`AppState.healthSync`).
     */
    val healthSync: HealthSyncService = AppServices.healthSyncService,
    private val httpClient: HttpClient = AppServices.httpClient,
    /** Der Update-Kanal (siehe [UpdateChecker]); versorgt [updateAvailable]. */
    private val updateChecker: UpdateChecker = AppServices.updateChecker,
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
    // Erststart-Einfuehrung
    // -------------------------------------------------------------------------

    private val _onboardingVisible = MutableStateFlow(false)

    /**
     * Ob die Einfuehrung (`ui/onboarding/OnboardingScreen.kt`) statt der
     * Haupt-App gezeigt wird.
     *
     * Startet bewusst mit `false` und wird erst `true`, nachdem der
     * init-Block gelesen hat, dass [ONBOARDING_STORAGE_KEY] fehlt. Andersherum
     * — mit `true` als Startwert — waere bei jedem App-Start ein kurzes
     * Aufblitzen der Einfuehrung zu sehen, auch fuer Nutzer, die sie laengst
     * abgeschlossen haben.
     */
    val onboardingVisible: StateFlow<Boolean> = _onboardingVisible.asStateFlow()

    /**
     * Beendet die Einfuehrung und merkt sich das dauerhaft — egal ob sie
     * durchgeklickt oder uebersprungen wurde. Danach startet die App direkt in
     * die Karte; erneut aufrufbar ist sie ueber [showOnboardingAgain]
     * („Mehr → Über → Einführung erneut ansehen").
     */
    fun completeOnboarding() {
        _onboardingVisible.value = false
        viewModelScope.launch {
            withContext(io) {
                runCatching { keyValueStore.setString(ONBOARDING_STORAGE_KEY, "1") }
            }
        }
    }

    /** Zeigt die Einfuehrung noch einmal (Mehr → Über). */
    fun showOnboardingAgain() {
        _onboardingVisible.value = true
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
    // Trainingsempfehlung → Routenziel
    // -------------------------------------------------------------------------

    private val _pendingRouteTarget = MutableStateFlow<RouteTarget?>(null)

    /**
     * Das Routenziel, das der Karten-Tab als Naechstes anbieten soll — genau
     * dasselbe Muster wie [tabRequest]: Der Trainings-Tab legt es hin, der
     * Karten-Tab holt es ab und quittiert mit [consumeRouteTarget].
     *
     * Bewusst ein [StateFlow] und kein Ereigniskanal: Zwischen dem Tippen im
     * Trainings-Tab und dem Zeitpunkt, an dem der Karten-Screen ueberhaupt in
     * der Komposition ist, liegt der Tab-Wechsel. Ein einmaliges Ereignis
     * waere bis dahin verpufft; der gehaltene Wert wartet.
     */
    val pendingRouteTarget: StateFlow<RouteTarget?> = _pendingRouteTarget.asStateFlow()

    /**
     * Uebergibt ein aus einer Einheit oder der Tagesempfehlung abgeleitetes
     * Ziel an den Karten-Tab und wechselt dorthin
     * (`:core`: `routeTargetForSession` / `routeTargetForToday`).
     */
    fun requestRouteGeneration(target: RouteTarget) {
        _pendingRouteTarget.value = target
        requestTab(AppTab.MAP)
    }

    /** Quittiert das abgeholte Ziel (ruft der Karten-Screen). */
    fun consumeRouteTarget() {
        _pendingRouteTarget.value = null
    }

    // -------------------------------------------------------------------------
    // Startseite → Tourendetail
    // -------------------------------------------------------------------------

    private val _pendingRideDetail = MutableStateFlow<String?>(null)

    /**
     * Die Tour, deren Detailansicht der Touren-Tab als Naechstes oeffnen soll.
     * Dasselbe Muster wie [pendingRouteTarget], aus demselben Grund: Zwischen
     * dem Tippen auf der Startseite und dem Erscheinen des Touren-Screens liegt
     * ein Tab-Wechsel, den ein einmaliges Ereignis nicht ueberleben wuerde.
     *
     * Ohne diesen Weg landete „Letzte Tour" nur in der Liste — der Nutzer haette
     * die Tour, die er gerade angetippt hat, dort ein zweites Mal suchen und
     * antippen muessen.
     */
    val pendingRideDetail: StateFlow<String?> = _pendingRideDetail.asStateFlow()

    /** Oeffnet die Detailansicht einer Tour und wechselt in den Touren-Tab. */
    fun requestRideDetail(rideId: String) {
        _pendingRideDetail.value = rideId
        requestTab(AppTab.RIDES)
    }

    /** Quittiert die abgeholte Tour (ruft der Touren-Screen). */
    fun consumeRideDetailRequest() {
        _pendingRideDetail.value = null
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
     *
     * Sofortige, endgueltige Loeschung ohne Rueckgaengig-Moeglichkeit — benutzt
     * vom Karten-Screen (Loeschen der gerade offenen Tour). Der Touren-Tab
     * benutzt stattdessen [deleteRideWithUndo].
     */
    fun removeRide(id: String) {
        viewModelScope.launch {
            withContext(io) { rideStorage.deleteRide(id) }
            reloadRides()
        }
    }

    /** Eine per [deleteRideWithUndo] optimistisch entfernte, aber noch nicht endgueltig geloeschte Tour. */
    private data class PendingRideDeletion(val ride: Ride, val job: Job)

    /**
     * Ausstehende Loeschung, die [deleteRideWithUndo] zuletzt angestossen hat —
     * `null`, wenn gerade keine laeuft. Es gibt bewusst nie mehr als eine
     * gleichzeitig: Eine neue Loeschung schliesst die vorige sofort endgueltig
     * ab (siehe [deleteRideWithUndo]).
     */
    private var pendingDeletion: PendingRideDeletion? = null

    /**
     * Entfernt eine Tour **optimistisch** aus [rides] — sie verschwindet
     * sofort aus der Liste — loescht sie aber erst nach [UNDO_DELETE_GRACE_MS]
     * wirklich von der Platte (`rideStorage.deleteRide`). Der Touren-Tab zeigt
     * in dieser Frist eine Snackbar „Tour gelöscht" mit Aktion „Rückgängig"
     * (siehe `RidesScreen.kt`); tippt niemand darauf, laeuft der hier
     * gestartete Timer ab und die Datei ist weg.
     *
     * Folgt eine weitere Loeschung — derselben oder einer anderen Tour —,
     * bevor die Frist um ist, wird die vorige sofort endgueltig abgeschlossen
     * (ihre Datei geloescht): Es soll nie zwei gleichzeitig „schwebende"
     * Loeschungen geben, deren Snackbars sich gegenseitig verdraengen wuerden.
     *
     * ACHTUNG Prozess-Tod waehrend der Frist: Der Timer laeuft im
     * `viewModelScope` und damit nur, solange der Prozess lebt. Stirbt er
     * vorher, wurde die Datei nie geloescht — die Tour ist einfach noch da und
     * taucht beim naechsten Start ganz normal wieder in der Liste auf. Das ist
     * bewusst so belassen (kein Datenverlust) statt ueber einen persistenten
     * „geloescht, aber…"-Zustand nachzuhalten.
     */
    fun deleteRideWithUndo(id: String) {
        val ride = _rides.value.firstOrNull { it.id == id } ?: return
        finalizePendingDeletion()
        _rides.value = _rides.value.filterNot { it.id == id }
        val job = viewModelScope.launch {
            delay(UNDO_DELETE_GRACE_MS)
            withContext(io) { rideStorage.deleteRide(id) }
            pendingDeletion = null
        }
        pendingDeletion = PendingRideDeletion(ride, job)
    }

    /**
     * Macht die zuletzt per [deleteRideWithUndo] entfernte Tour rueckgaengig
     * (Aktion der Snackbar). Ohne ausstehende Loeschung — etwa weil die Frist
     * schon abgelaufen ist — passiert nichts.
     *
     * Fuegt die Tour wieder ein und sortiert die Liste neu ein statt sie
     * anzuhaengen: [rides] ist immer nach Datum sortiert, das muss nach dem
     * Undo weiter gelten.
     */
    fun undoDeleteRide() {
        val pending = pendingDeletion ?: return
        pendingDeletion = null
        pending.job.cancel()
        _rides.value = (_rides.value + pending.ride).sortedByDescending { it.createdAt }
    }

    /** Schliesst eine noch laufende Loeschung sofort endgueltig ab (siehe [deleteRideWithUndo]). */
    private fun finalizePendingDeletion() {
        val pending = pendingDeletion ?: return
        pendingDeletion = null
        pending.job.cancel()
        viewModelScope.launch {
            withContext(io) { rideStorage.deleteRide(pending.ride.id) }
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
                // Ein fehlgeschlagenes Speichern ist der eine Fehler, den auch
                // der stille Sync melden muss: Die Nutzerin sieht sonst nie,
                // dass Touren fehlen (der Zeitstempel ist zwar
                // zurueckgerollt, der naechste Versuch kann aber genauso
                // scheitern — etwa bei vollem Speicher).
                if (!applyReport(report)) showMessage(HEALTH_SAVE_FAILED_MESSAGE)
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
     * @param reimportAll betrachtet wieder das volle 30-Tage-Fenster. Der
     *   gespeicherte Zeitstempel wird dafuer **nicht** geloescht (das
     *   Importfenster kommt ueber `since`): Wirft der Import — etwa weil
     *   Health Connect zwischenzeitlich die Berechtigung entzogen hat —,
     *   bleibt der bisherige Stand erhalten, statt den naechsten normalen
     *   Sync unnoetig 30 Tage scannen zu lassen.
     */
    suspend fun syncHealthNow(reimportAll: Boolean = false): Int {
        val report = withContext(io) {
            healthSync.importWithReport(
                existing = _rides.value,
                since = if (reimportAll) fullHealthWindowStart() else null,
            )
        }
        if (!applyReport(report)) {
            throw HealthSyncException(HEALTH_SAVE_FAILED_MESSAGE)
        }
        _vitals.value = withContext(io) { healthSync.readVitals(days = VITALS_WINDOW_DAYS) }
        refreshHealthConnection()
        return report.imported.size
    }

    /**
     * Merkt sich den Bericht und persistiert alles, was er veraendert hat:
     * neue Touren **und** bestehende Touren, die um Watch-Herzfrequenz
     * angereichert wurden (gleiche ID, `saveRide` ueberschreibt sie).
     *
     * `importWithReport` hat den Import-Zeitstempel bereits auf das Ende des
     * betrachteten Fensters gesetzt, bevor diese Methode ueberhaupt laeuft.
     * Scheitert das Speichern (voller Datentraeger, IO-Fehler), waeren die
     * Workouts damit endgueltig verloren: Sie lagen im gerade abgehakten
     * Fenster und tauchen nie wieder auf. Deshalb wird der Zeitstempel dann
     * auf den Fensteranfang ([HealthSyncReport.from]) zurueckgerollt — der
     * naechste Sync betrachtet exakt denselben Zeitraum erneut.
     *
     * @return `false`, wenn das Speichern fehlgeschlagen ist (Zeitstempel
     *   wurde zurueckgerollt). Der Aufrufer meldet das der Nutzerin.
     */
    private suspend fun applyReport(report: HealthSyncReport): Boolean {
        _lastSyncReport.value = report
        if (report.isEmpty) return true

        val saved = withContext(io) {
            runCatching {
                rideStorage.saveRides(report.imported)
                rideStorage.saveRides(report.mergedRides)
            }
        }
        if (saved.isFailure) {
            withContext(io) { runCatching { healthSync.setLastImportAt(report.from) } }
            // Die Liste trotzdem neu laden: Vielleicht ist ein Teil der Touren
            // vor dem Fehler schon auf der Platte gelandet.
            reloadRides()
            return false
        }

        reloadRides()
        return true
    }

    /**
     * Beginn des vollen Importfensters („Alles neu importieren"): jetzt minus
     * [healthSyncInitialWindowMs], gerechnet auf der absoluten Zeitachse —
     * dieselbe Rechnung, die `:core` ohne gesetzten Zeitstempel anstellt.
     */
    private fun fullHealthWindowStart(): LocalDateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(System.currentTimeMillis() - healthSyncInitialWindowMs),
        ZoneId.systemDefault(),
    )

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
    // Erinnerungen
    // -------------------------------------------------------------------------

    private val _reminderSettings = MutableStateFlow(ReminderSettings())

    /**
     * Einstellungen der lokalen Erinnerungen — drei Schalter und zwei
     * Uhrzeiten, ab Werk alle aus (siehe [ReminderSettings]). Gelesen wird der
     * Wert von der Karte im Mehr-Tab; der Hintergrundlauf liest ihn
     * unabhaengig davon direkt aus dem Speicher, weil er ohne ViewModel laeuft.
     */
    val reminderSettings: StateFlow<ReminderSettings> = _reminderSettings.asStateFlow()

    /**
     * Uebernimmt geaenderte Erinnerungs-Einstellungen und speichert sie.
     *
     * Den **Zeitplan** stellt diese Methode bewusst nicht um: Dafuer braucht
     * es einen `Context` (WorkManager), den das ViewModel nicht hat und nicht
     * haben soll. Die Karte im Mehr-Tab ruft direkt im Anschluss
     * `ReminderScheduler.reschedule(context, settings)` mit **demselben**
     * Wert auf — dadurch haengt die Neuplanung nicht davon ab, ob dieses
     * Speichern schon durch ist.
     */
    fun setReminderSettings(settings: ReminderSettings) {
        _reminderSettings.value = settings
        viewModelScope.launch {
            withContext(io) { runCatching { reminderStore.writeSettings(settings) } }
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
    // Update-Hinweis
    // -------------------------------------------------------------------------

    private val _updateAvailable = MutableStateFlow<String?>(null)

    /**
     * Der `versionName` einer verfuegbaren neueren Version (z. B. `"2.0.123"`),
     * sonst `null`.
     *
     * Der Mehr-Tab zeigt daraufhin eine schliessbare Hinweis-Karte. Wurde die
     * Karte fuer genau diese Version schon weggewischt, bleibt der Wert
     * `null` — siehe [de.trailscape.app.update.UpdateChecker.dismiss].
     */
    val updateAvailable: StateFlow<String?> = _updateAvailable.asStateFlow()

    /**
     * Blendet die Update-Karte aus und merkt sich das fuer diese Version.
     * Eine spaetere, hoehere Version meldet sich wieder.
     */
    fun dismissUpdateNotice() {
        val versionName = _updateAvailable.value ?: return
        _updateAvailable.value = null
        viewModelScope.launch {
            withContext(io) { updateChecker.dismiss(versionName) }
        }
    }

    /**
     * Manuelle Pruefung („Mehr → Über → Nach Updates suchen"). Liefert das
     * Ergebnis fuer die Anzeige zurueck und aktualisiert nebenbei
     * [updateAvailable].
     *
     * Ein [UpdateCheckResult.Failed] laesst einen bereits bekannten Hinweis
     * stehen: Dass GitHub gerade nicht erreichbar ist, sagt nichts darueber
     * aus, ob das Update noch existiert.
     */
    suspend fun checkForUpdateNow(): UpdateCheckResult {
        val result = withContext(io) { updateChecker.checkNow() }
        when (result) {
            is UpdateCheckResult.Available -> _updateAvailable.value = result.versionName
            UpdateCheckResult.UpToDate -> _updateAvailable.value = null
            else -> Unit
        }
        return result
    }

    /**
     * Der stille Update-Check beim App-Start: gedrosselt auf hoechstens einen
     * Netzzugriff pro Tag, komplett auf [io], Fehler ohne jede Meldung —
     * offline zu sein ist bei einer Fahrrad-App der Normalfall.
     */
    private fun checkForUpdateInBackground() {
        viewModelScope.launch {
            val startup = withContext(io) {
                runCatching { updateChecker.startupCheck() }.getOrNull()
            } ?: return@launch
            startup.noticeVersion?.let { _updateAvailable.value = it }
            startup.announceVersion?.let {
                showMessage("Version $it ist verfügbar — im Mehr-Tab herunterladen.")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Start
    // -------------------------------------------------------------------------

    init {
        // Fertig gespeicherte Touren quittiert das ViewModel, nicht ein
        // einzelner Screen: Gestoppt wird auch ueber die Notification-Aktion,
        // und die Wiederherstellung eines verwaisten Journals meldet ihre Tour
        // beim App-Start. Beides muss die Tourenliste aktualisieren, egal
        // welcher Tab gerade sichtbar ist. Die Snackbar laeuft ueber
        // [messages] — den Kanal sammelt ohnehin jeder Screen ein.
        viewModelScope.launch {
            RecordingRepository.lastFinishedRideId.filterNotNull().collect { rideId ->
                reloadRides()
                select(rideId)
                RecordingRepository.clearFinishedRide()
                showMessage("Tour gespeichert.")
            }
        }

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
                    onboardingSeen = runCatching {
                        keyValueStore.getString(ONBOARDING_STORAGE_KEY) != null
                    }.getOrDefault(true),
                    reminderSettings = reminderStore.readSettings(),
                )
            }
            _profile.value = restored.profile
            _plan.value = restored.plan
            _mapStyle.value = restored.mapStyle
            _syncConfig.value = restored.syncConfig
            _reminderSettings.value = restored.reminderSettings
            // Erst hier, nicht als Startwert: siehe KDoc von [onboardingVisible].
            // Bei einem Lesefehler gilt die Einfuehrung als gesehen — lieber
            // einmal zu wenig zeigen als bei jedem Start erneut.
            _onboardingVisible.value = !restored.onboardingSeen

            reloadRides()
            autoSyncHealth()
        }

        // Feuern und vergessen, ganz am Ende: Der Update-Check haelt nichts
        // auf und braucht nichts von dem, was oben von der Platte kommt.
        checkForUpdateInBackground()
    }

    private data class Restored(
        val profile: TrainingProfile,
        val plan: TrainingPlan?,
        val mapStyle: MapStyle,
        val syncConfig: SyncConfig?,
        val onboardingSeen: Boolean,
        val reminderSettings: ReminderSettings,
    )
}

/**
 * Schluessel im [KeyValueStore], unter dem steht, dass die Erststart-Einfuehrung
 * durch ist.
 *
 * Im selben `trailscape.*`-Namensraum wie alle uebrigen Schluessel (siehe
 * `data/PrefsStores.kt`). Das `.v1` am Ende ist Absicht: Wird die Einfuehrung
 * spaeter einmal grundlegend anders, laesst sie sich durch einen neuen
 * Schluessel gezielt noch einmal zeigen, ohne den alten Wert loeschen zu
 * muessen.
 */
const val ONBOARDING_STORAGE_KEY: String = "trailscape.onboarding.v1"

/**
 * Wartezeit, bevor eine per [AppViewModel.deleteRideWithUndo] entfernte Tour
 * endgueltig von der Platte verschwindet. `RidesScreen` legt die Anzeigedauer
 * seiner Undo-Snackbar auf denselben Wert, damit beide synchron ablaufen.
 */
const val UNDO_DELETE_GRACE_MS: Long = 5_000L

/**
 * Meldung, wenn die aus Health Connect geholten Touren nicht gespeichert
 * werden konnten. Der Import-Zeitstempel ist dann bereits zurueckgerollt
 * (siehe `AppViewModel.applyReport`), der naechste Sync holt dieselben
 * Workouts also erneut.
 */
private const val HEALTH_SAVE_FAILED_MESSAGE: String =
    "Die importierten Touren konnten nicht gespeichert werden. " +
        "Beim nächsten Sync wird es erneut versucht."
