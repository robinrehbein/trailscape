package de.trailscape.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.RideStorage
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.reminder.ReminderStore
import de.trailscape.app.routing.SegmentDownloads
import de.trailscape.app.routing.SegmentOffer
import de.trailscape.app.routing.SegmentSettings
import de.trailscape.app.routing.describeSegmentOffer
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
import de.trailscape.core.VitalsHistory
import de.trailscape.core.VitalsSummary
import de.trailscape.core.getSyncConfig
import de.trailscape.core.healthSyncInitialWindowMs
import de.trailscape.core.loadPlan
import de.trailscape.core.readVitalsHistory
import de.trailscape.core.savePlan
import de.trailscape.core.shouldShowShortSleeperHint
import de.trailscape.core.syncRides
import de.trailscape.core.writeVitalsHistory
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
 * Eine einzelne Zeile des Mehr-Tabs als Sprungziel (siehe
 * [AppViewModel.requestMoreSection]).
 *
 * Bewusst nur die Zeilen, auf die von aussen verwiesen wird — nicht alle acht.
 * Ein Aufzaehlungswert ohne Verweis waere ein Versprechen ohne Einloeser; die
 * Zuordnung Wert → Gruppe steht an genau einer Stelle
 * (`ui/more/MoreScreen.kt`, `moreGroupIndex`).
 */
enum class MoreSection {
    /** „Profil" — Alter, Gewicht, Zeitbudget, HFmax/FTP. */
    PROFILE,

    /** „Daten & Backup" — Einzel-, Archiv- und Backup-Import. */
    BACKUP,

    /** „Health Connect" — Uhr verbinden, Vitalwerte holen. */
    HEALTH,
}

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
     * „Nur über WLAN laden?" fuer die Offline-Routingdaten (siehe
     * [segmentUnmeteredOnly]).
     */
    private val segmentSettings: SegmentSettings = AppServices.segmentSettings,
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
    // Startseite → Aufzeichnung starten
    // -------------------------------------------------------------------------

    private val _pendingRecordStart = MutableStateFlow(false)

    /**
     * Die Bitte an den Karten-Tab, die Aufzeichnung zu starten — dasselbe
     * Muster wie [pendingRouteTarget]: Die Startseite legt die Bitte hin, der
     * Karten-Screen holt sie mit [consumeRecordStart] ab und loest dort
     * **denselben** Pfad aus wie der gruene Aufnahme-Knopf, samt der dort
     * ohnehin noetigen Standort- und Benachrichtigungsabfrage. Ein zweiter,
     * eigener Startpfad fuer die Startseite haette diese Berechtigungslogik
     * verdoppeln oder umgehen muessen — beides schlechter als der eine Schritt
     * (Tab-Wechsel), den der Nutzer jetzt noch sieht.
     */
    val pendingRecordStart: StateFlow<Boolean> = _pendingRecordStart.asStateFlow()

    /** Bittet den Karten-Tab, die Aufzeichnung zu starten, und wechselt dorthin. */
    fun requestRecording() {
        _pendingRecordStart.value = true
        requestTab(AppTab.MAP)
    }

    /** Quittiert die abgeholte Bitte (ruft der Karten-Screen). */
    fun consumeRecordStart() {
        _pendingRecordStart.value = false
    }

    // -------------------------------------------------------------------------
    // Startseite → Tourendetail
    // -------------------------------------------------------------------------

    private val _pendingRideDetail = MutableStateFlow<String?>(null)

    /**
     * Die Tour, deren Detailansicht als Naechstes geoeffnet werden soll.
     * Dasselbe Muster wie [pendingRouteTarget], aus demselben Grund: Zwischen
     * dem Tippen auf der Startseite und dem Erscheinen des Tourenblatts ueber
     * der Karte liegt ein Tab-Wechsel, den ein einmaliges Ereignis nicht
     * ueberleben wuerde.
     *
     * Ohne diesen Weg landete „Letzte Tour" nur in der Liste — der Nutzer haette
     * die Tour, die er gerade angetippt hat, dort ein zweites Mal suchen und
     * antippen muessen.
     *
     * Abgeholt wird der Wert seit dem Zusammenlegen von Touren und Karte vom
     * Karten-Screen (Baustein `ui/rides/TourList.kt`), nicht mehr von einem
     * eigenen Touren-Tab — [requestRideDetail] und dieser Zustand selbst
     * blieben dabei unveraendert, nur der Abholer ist ein anderer.
     */
    val pendingRideDetail: StateFlow<String?> = _pendingRideDetail.asStateFlow()

    /** Oeffnet die Detailansicht einer Tour und wechselt zur Karte. */
    fun requestRideDetail(rideId: String) {
        _pendingRideDetail.value = rideId
        requestTab(AppTab.RIDES)
    }

    /** Quittiert die abgeholte Tour (ruft der Karten-Screen). */
    fun consumeRideDetailRequest() {
        _pendingRideDetail.value = null
    }

    // -------------------------------------------------------------------------
    // Touren-Tab → Tourenblatt ueber der Karte
    // -------------------------------------------------------------------------

    private val _tourSheetRequest = MutableStateFlow(false)

    /**
     * Bitte an den Karten-Screen, das Tourenblatt aufzuschlagen — dasselbe
     * Muster wie [pendingRouteTarget] und [pendingRideDetail], aus demselben
     * Grund: [AppTab.RIDES] loest die Navigationshuelle auf die Route „karte"
     * auf (siehe `TrailscapeApp.kt`), aber zwischen dem Aufruf und dem
     * Erscheinen des Karten-Screens liegt derselbe Tab-Wechsel. Ein einmaliges
     * Ereignis waere bis dahin verpufft, ohne dass das Blatt je aufginge — der
     * gehaltene Wert wartet, bis der Karten-Screen in der Komposition ist.
     *
     * Bewusst ein simples `Boolean` und keine ID wie bei [pendingRideDetail]:
     * Es gibt nichts auszuwaehlen, nur ein „jetzt zeigen". Legt hin, wer zur
     * Karte navigieren und dabei die Liste statt der reinen Kartenansicht
     * zeigen will (aktuell nur die Aufloesung von [AppTab.RIDES]); der
     * Karten-Screen holt es ab und quittiert mit [consumeTourSheetRequest].
     */
    val tourSheetRequest: StateFlow<Boolean> = _tourSheetRequest.asStateFlow()

    /** Bittet den Karten-Screen, das Tourenblatt aufzuschlagen. */
    fun requestTourSheet() {
        _tourSheetRequest.value = true
    }

    /** Quittiert die abgeholte Bitte (ruft der Karten-Screen). */
    fun consumeTourSheetRequest() {
        _tourSheetRequest.value = false
    }

    // -------------------------------------------------------------------------
    // Leerzustand → passende Karte im Mehr-Tab
    // -------------------------------------------------------------------------

    private val _pendingMoreSection = MutableStateFlow<MoreSection?>(null)

    /**
     * Die Karte, zu der der Mehr-Tab als Naechstes scrollen soll — dasselbe
     * Muster wie [pendingRideDetail], aus demselben Grund: Zwischen dem Tippen
     * im Leerzustand und dem Erscheinen des Mehr-Screens liegt ein Tab-Wechsel,
     * den ein einmaliges Ereignis nicht ueberleben wuerde.
     *
     * ## Warum ueberhaupt
     * Vier Leerzustaende („Touren importieren") riefen bis hierher nur
     * `requestTab(AppTab.MORE)`. Der Nutzer landete damit **oben** in einer
     * Liste aus neun Karten und sah zuerst das Profilformular; die Import-
     * Knoepfe liegen in der zweiten Karte und dort noch einmal tiefer. Genau
     * dieser Handgriff ist aber der, den die Einfuehrung selbst als „Schritt 1
     * von 3" fuehrt.
     *
     * ## Warum kein direkter Dateiwaehler
     * Der Launcher aus `ui/ActivityFileImport.kt` haette sich auch an jedem
     * Leerzustand aufhaengen lassen. Dagegen sprechen zwei Dinge: Es gibt
     * **vier** Importwege (Einzeldatei, ZIP-Archiv, Backup, Health Connect),
     * und welcher der richtige ist, weiss nur der Nutzer — ein direkt
     * geoeffneter Dateiwaehler entscheidet das fuer ihn und verschweigt die
     * anderen drei. Ausserdem lernt er dabei nicht, wo der Import wohnt, und
     * sucht ihn beim naechsten Mal erneut. Das Sprungziel zeigt ihm die Karte
     * mit allen Wegen — einmal — und er findet sie danach selbst wieder.
     */
    val pendingMoreSection: StateFlow<MoreSection?> = _pendingMoreSection.asStateFlow()

    /** Wechselt in den Mehr-Tab und scrollt dort zur Karte [section]. */
    fun requestMoreSection(section: MoreSection) {
        _pendingMoreSection.value = section
        requestTab(AppTab.MORE)
    }

    /** Quittiert das abgeholte Sprungziel (ruft der Mehr-Screen). */
    fun consumeMoreSectionRequest() {
        _pendingMoreSection.value = null
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
     * (siehe `TourList.kt`); tippt niemand darauf, laeuft der hier
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

    private val _profileConfirmed = MutableStateFlow(false)

    /**
     * Ob die Werte in [profile] wirklich **von der Nutzerin** stammen.
     *
     * ## Das Problem, das dieses Kennzeichen loest
     * [defaultTrainingProfile] traegt Alter 40 und Gewicht 75 kg. Wer die
     * Einfuehrung ueberspringt — ausdruecklich erlaubt —, bekommt Trainingslast,
     * HFmax, Schwelle und geschaetzte Leistung aus den Massen eines fremden
     * Koerpers, ohne dass irgendwo staende, dass das Schaetzwerte sind.
     * Verschaerfend fuellte das Profilformular seine Felder mit genau diesen
     * Zahlen vor: Sie sahen aus wie eine eigene Eingabe. Ohne dieses Kennzeichen
     * kann die App „nicht gesetzt" und „auf Standard gesetzt" nicht
     * unterscheiden — beides ist derselbe [TrainingProfile].
     *
     * ## Wer es setzt
     * Jeder Weg, auf dem ein Profil bewusst uebernommen wird, laeuft ueber
     * [setProfile]: „Profil speichern" im Mehr-Tab, die Profilseite der
     * Einfuehrung und der Backup-Import (ein wiederhergestelltes Profil ist
     * ebenso das eigene). Ueberspringt die Einfuehrung, ruft niemand
     * [setProfile] — und das Kennzeichen bleibt aus.
     *
     * Zurueckgenommen wird es nie: Einmal eingetragen bleibt eingetragen, auch
     * wenn spaeter zufaellig wieder 40/75 dasteht.
     */
    val profileConfirmed: StateFlow<Boolean> = _profileConfirmed.asStateFlow()

    /**
     * Uebernimmt ein neues Profil und speichert es. Die abgeleiteten Werte in
     * [insights] rechnen sich daraufhin selbst neu.
     *
     * Setzt zugleich [profileConfirmed] — siehe dort, warum das genau hier und
     * nicht an den einzelnen Aufrufstellen passiert.
     */
    fun setProfile(profile: TrainingProfile) {
        _profile.value = profile
        val wasConfirmed = _profileConfirmed.value
        _profileConfirmed.value = true
        viewModelScope.launch {
            withContext(io) {
                runCatching {
                    keyValueStore.setString(PROFILE_STORAGE_KEY, profile.toJson().toString())
                }
                if (!wasConfirmed) {
                    runCatching { keyValueStore.setString(PROFILE_CONFIRMED_STORAGE_KEY, "1") }
                }
            }
        }
    }

    private fun readProfile(): TrainingProfile = runCatching {
        val raw = keyValueStore.getString(PROFILE_STORAGE_KEY) ?: return defaultTrainingProfile
        val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: return defaultTrainingProfile
        TrainingProfile.fromJson(parsed)
    }.getOrDefault(defaultTrainingProfile)

    /**
     * Liest das Bestaetigungs-Kennzeichen.
     *
     * Der zweite Zweig ist die Nachruestung fuer Bestandsnutzer: Wer laengst
     * ein Profil gespeichert hat, soll nicht ploetzlich leere Felder und einen
     * „noch nicht eingetragen"-Hinweis sehen, nur weil der Schluessel neu ist.
     * Ein vorhandener Profileintrag gilt deshalb als Bestaetigung.
     */
    private fun readProfileConfirmed(): Boolean = runCatching {
        keyValueStore.getString(PROFILE_CONFIRMED_STORAGE_KEY) != null ||
            keyValueStore.getString(PROFILE_STORAGE_KEY) != null
    }.getOrDefault(false)

    // -------------------------------------------------------------------------
    // Kurzschlaefer-Hinweis
    // -------------------------------------------------------------------------

    private val _shortSleeperHintShownAt = MutableStateFlow<LocalDateTime?>(null)

    /**
     * Wann der Kurzschlaefer-Hinweis zuletzt gezeigt wurde; `null` = nie.
     *
     * `:core` bringt mit `shouldShowShortSleeperHint` bereits die Regel
     * „hoechstens einmal im Monat" mit — sie war nur nie angeschlossen, der
     * Hinweis stand bei jedem Blick auf die Vitalwerte da. Ein
     * Gesundheitshinweis, den man taeglich liest, ist keiner mehr.
     */
    val shortSleeperHintShownAt: StateFlow<LocalDateTime?> = _shortSleeperHintShownAt.asStateFlow()

    private val _shortSleeperHintVisible = MutableStateFlow(false)

    /**
     * Ob der Kurzschlaefer-Hinweis in **dieser** Sitzung gezeigt werden darf.
     *
     * Wird genau einmal beim Start entschieden (nachdem der gespeicherte
     * Zeitpunkt gelesen ist) und danach nicht mehr angefasst — sonst wuerde der
     * Hinweis vor den Augen der Nutzerin verschwinden, sobald die Karte ihn als
     * gezeigt quittiert. Beim naechsten Start ist er dann fuer 30 Tage weg.
     */
    val shortSleeperHintVisible: StateFlow<Boolean> = _shortSleeperHintVisible.asStateFlow()

    /** Quittiert den gezeigten Hinweis (ruft die Vitalwerte-Karte). */
    fun markShortSleeperHintShown(now: LocalDateTime = LocalDateTime.now()) {
        if (_shortSleeperHintShownAt.value == now) return
        _shortSleeperHintShownAt.value = now
        viewModelScope.launch {
            withContext(io) {
                runCatching {
                    keyValueStore.setString(
                        SHORT_SLEEPER_HINT_STORAGE_KEY,
                        now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().toString(),
                    )
                }
            }
        }
    }

    private fun readShortSleeperHintShownAt(): LocalDateTime? = runCatching {
        val raw = keyValueStore.getString(SHORT_SLEEPER_HINT_STORAGE_KEY) ?: return null
        val ms = raw.toLongOrNull() ?: return null
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    }.getOrNull()

    // -------------------------------------------------------------------------
    // Health Connect
    // -------------------------------------------------------------------------

    private val _vitals = MutableStateFlow<VitalsSummary?>(null)

    /**
     * Vitaldaten aus der **lokalen Historie**, `null` solange nichts vorliegt.
     *
     * Nicht mehr direkt das Ergebnis des letzten Health-Connect-Lesens: Health
     * Connect loescht nach 30 Tagen, die Baselines brauchen aber bis zu 60
     * Tage Material (siehe [de.trailscape.core.VitalsHistory]). Gelesen wird
     * deshalb nur noch die Luecke seit dem letzten Sync; der Rest kommt aus
     * dem lokalen Speicher.
     */
    val vitals: StateFlow<VitalsSummary?> = _vitals.asStateFlow()

    /**
     * Der lokal gehaltene Stand. Nur aus [syncVitals] heraus benutzt, das
     * immer im selben Coroutine-Kontext laeuft — kein zusaetzlicher Schutz
     * noetig.
     */
    private var vitalsHistory: VitalsHistory = VitalsHistory.EMPTY

    /**
     * Holt die fehlenden Tage aus Health Connect, legt sie auf die lokale
     * Historie und schreibt beides zurueck.
     *
     * Bewusst **nicht** „immer 60 Tage neu lesen": Health Connect gibt nur
     * her, was es noch hat (Standard-Aufbewahrung 30 Tage). Wer jeden Start
     * das Fenster neu liest und das Ergebnis ersetzt, verliert alles
     * Aeltere — und die Ruhepuls-Baseline (≥ 21 Werte aus den Tagen −8 … −60)
     * kann dann dauerhaft unerreichbar bleiben.
     */
    private suspend fun syncVitals() {
        val now = LocalDateTime.now()
        val days = vitalsHistory.daysToFetch(now, VITALS_WINDOW_DAYS)
        val fresh = withContext(io) { healthSync.readVitals(days = days) }
        val merged = vitalsHistory.merge(fresh, now = now)
        vitalsHistory = merged
        _vitals.value = merged.toSummary(
            now = now,
            days = VITALS_HISTORY_WINDOW_DAYS,
            unavailable = fresh.unavailable,
        )
        withContext(io) { runCatching { writeVitalsHistory(keyValueStore, merged) } }
    }

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
                syncVitals()
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
        syncVitals()
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
    // Quittierung der Plan-Tragfaehigkeit ("Plan und Ziel passen nicht zusammen")
    // -------------------------------------------------------------------------

    private val _planFeasibilityAckKey = MutableStateFlow<String?>(null)

    /**
     * Der Plan-Schluessel ([planFeasibilityIdentityKey]), fuer den die
     * Startseite den Hinweis „Plan und Ziel passen nicht zusammen" zuletzt mit
     * „Verstanden" quittiert hat — `null`, wenn noch keiner quittiert ist.
     *
     * Dasselbe Muster wie [profileConfirmed]: ein reines Anzeige-Flag, das nur
     * `de.trailscape.app.ui.today.TodayScreen` liest, um die Karte
     * auszublenden. Der Schluessel selbst haengt NICHT an der Zeit, sondern an
     * Zieldistanz, Zieldatum und Wochenzahl des Plans — legt jemand einen
     * neuen oder veraenderten Plan an, ist das ein anderer Schluessel, die
     * gestrige Quittierung passt nicht mehr, und die Karte erscheint
     * automatisch wieder.
     */
    val planFeasibilityAckKey: StateFlow<String?> = _planFeasibilityAckKey.asStateFlow()

    /** Quittiert die Karte fuer den Plan mit Schluessel [key] (Knopf „Verstanden"). */
    fun acknowledgePlanFeasibility(key: String) {
        _planFeasibilityAckKey.value = key
        viewModelScope.launch {
            withContext(io) {
                runCatching { keyValueStore.setString(PLAN_FEASIBILITY_ACK_STORAGE_KEY, key) }
            }
        }
    }

    private fun readPlanFeasibilityAckKey(): String? = runCatching {
        keyValueStore.getString(PLAN_FEASIBILITY_ACK_STORAGE_KEY)
    }.getOrNull()

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
    // Suchverlauf der Ortssuche (Karten-Tab)
    // -------------------------------------------------------------------------

    private val _placeSearchHistory = MutableStateFlow<List<PlaceSearchHistoryEntry>>(emptyList())

    /**
     * Die zuletzt ausgewaehlten Orte der Kartensuche, neuester zuerst — hoechstens
     * [PLACE_SEARCH_HISTORY_LIMIT]. Zeigt sie an: `de.trailscape.app.ui.map.SearchSheet`
     * unter „Zuletzt gesucht", solange das Suchfeld leer ist.
     *
     * Dasselbe Muster wie [mapStyle] direkt darueber: ein einzelner
     * [KeyValueStore]-Schluessel, hier aber fuer eine kleine Liste statt eines
     * einzelnen Werts. Ein eigener Eintrag pro Ort waere fuer fuenf Zeilen
     * unnoetiger Aufwand — die Liste liegt deshalb wie schon der Wegpunktname
     * in `PlanningStateSavers.kt` als ein einziger, mit Steuerzeichen
     * zusammengesetzter String im Speicher (siehe [encodePlaceSearchHistory]).
     */
    val placeSearchHistory: StateFlow<List<PlaceSearchHistoryEntry>> =
        _placeSearchHistory.asStateFlow()

    /**
     * Merkt sich [entry] als zuletzt gewaehlten Ort — vorne angefuegt, ein
     * schon vorhandener Eintrag *desselben* Orts (Koordinaten) ruckt dabei nur
     * nach vorn statt sich zu verdoppeln.
     */
    fun recordPlaceSearchHistory(entry: PlaceSearchHistoryEntry) {
        val updated = (listOf(entry) + _placeSearchHistory.value.filterNot { it.sameLocationAs(entry) })
            .take(PLACE_SEARCH_HISTORY_LIMIT)
        _placeSearchHistory.value = updated
        viewModelScope.launch {
            withContext(io) {
                runCatching {
                    keyValueStore.setString(
                        PLACE_SEARCH_HISTORY_STORAGE_KEY,
                        encodePlaceSearchHistory(updated),
                    )
                }
            }
        }
    }

    private fun readPlaceSearchHistory(): List<PlaceSearchHistoryEntry> = runCatching {
        decodePlaceSearchHistory(keyValueStore.getString(PLACE_SEARCH_HISTORY_STORAGE_KEY))
    }.getOrDefault(emptyList())

    // -------------------------------------------------------------------------
    // Offline-Routingdaten (Kacheln)
    // -------------------------------------------------------------------------

    private val _segmentUnmeteredOnly = MutableStateFlow(true)

    /**
     * „Kacheln nur über WLAN laden?" — die eine Einstellung der
     * Kachelverwaltung (siehe
     * [de.trailscape.app.routing.SegmentSettings]). Vorgabe ist die schonende
     * Antwort `true`.
     */
    val segmentUnmeteredOnly: StateFlow<Boolean> = _segmentUnmeteredOnly.asStateFlow()

    /** Setzt die WLAN-Einstellung und merkt sie sich. */
    fun setSegmentUnmeteredOnly(value: Boolean) {
        _segmentUnmeteredOnly.value = value
        viewModelScope.launch {
            withContext(io) { runCatching { segmentSettings.unmeteredOnly = value } }
        }
    }

    private val _segmentOffer = MutableStateFlow<SegmentOffer?>(null)

    /**
     * Das offene Download-Angebot fuer Kacheln, die einer geplanten Route
     * fehlen — `null`, wenn gerade keins ansteht.
     *
     * ## Warum das ein Angebot ist und keine Fehlermeldung
     * Die Planung laeuft in diesem Fall ueber den Server ganz normal weiter
     * (siehe `de.trailscape.core.routeOfflineFirst`). Der Nutzer hat also
     * kein Problem, das er loesen muesste — er hat die **Gelegenheit**, das
     * naechste Mal schneller und ohne Netz zu routen. Deshalb ein Dialog mit
     * Namen und Groesse und nicht die rote Zeile der Planung, und deshalb
     * blockiert er nichts.
     */
    val segmentOffer: StateFlow<SegmentOffer?> = _segmentOffer.asStateFlow()

    /**
     * Kachelmengen, fuer die in dieser Sitzung schon einmal gefragt wurde.
     *
     * Ohne das Gedaechtnis kaeme der Dialog bei **jedem** gesetzten Wegpunkt
     * wieder — die Planung rechnet nach jeder Aenderung neu. Wer einmal „Nicht
     * jetzt" gesagt hat, wird fuer dieselbe Gegend nicht noch einmal gefragt;
     * beim naechsten App-Start schon, denn dann kann die Antwort anders
     * ausfallen (anderes Netz, anderer Plan).
     */
    private val askedSegmentOffers = mutableSetOf<Set<String>>()

    /**
     * Bietet die fehlenden Kacheln [fileNames] zum Laden an — mit Namen und
     * echter Groesse (`HEAD`, siehe
     * [de.trailscape.app.routing.describeSegmentOffer]).
     *
     * Still, wenn die Liste leer ist, dieselbe Gegend schon abgelehnt wurde,
     * gerade ein anderes Angebot offen steht oder der Server keine Groesse
     * liefert: Ein Angebot ohne Preisschild waere schlechter als keins.
     */
    fun offerMissingSegments(fileNames: List<String>) {
        if (fileNames.isEmpty()) return
        val key = fileNames.toSet()
        if (key in askedSegmentOffers || _segmentOffer.value != null) return
        askedSegmentOffers.add(key)
        viewModelScope.launch {
            _segmentOffer.value = runCatching { describeSegmentOffer(fileNames) }.getOrNull()
        }
    }

    /** Schliesst das Angebot, ohne zu laden. */
    fun dismissSegmentOffer() {
        _segmentOffer.value = null
    }

    /**
     * Nimmt das offene Angebot an und reiht die Kacheln in den
     * Hintergrund-Download ein.
     *
     * [context] kommt von der Aufrufstelle, weil der Rest dieses ViewModels
     * ohne Android-Context auskommt und das so bleiben soll — nur WorkManager
     * braucht einen.
     */
    fun acceptSegmentOffer(context: Context) {
        val offer = _segmentOffer.value ?: return
        _segmentOffer.value = null
        downloadSegments(context, offer.fileNames)
    }

    /**
     * Reiht Kacheln in den Hintergrund-Download ein — der eine Weg dorthin,
     * egal ob er vom Angebot der Planung oder aus der Verwaltung im Mehr-Tab
     * kommt. Nur so gilt die WLAN-Einstellung ueberall gleich und die
     * Rueckmeldung lautet ueberall gleich.
     */
    fun downloadSegments(context: Context, fileNames: List<String>) {
        if (fileNames.isEmpty()) return
        viewModelScope.launch {
            val started = withContext(io) {
                SegmentDownloads.enqueue(
                    context = context,
                    fileNames = fileNames,
                    unmeteredOnly = _segmentUnmeteredOnly.value,
                )
            }
            showMessage(
                when {
                    // Kein Entwicklerdeutsch („nicht eingereiht") und kein
                    // Rueckschluss, den nur wir ziehen koennen: WorkManager
                    // lehnt praktisch nur bei fehlendem Speicher oder
                    // eingeschraeteter App ab — beides loest ein neuer Versuch
                    // nach dem Nachsehen.
                    !started ->
                        "Der Download der Kartendaten ließ sich nicht starten. " +
                            "Prüfe, ob genug Speicher frei ist, und versuche es erneut."
                    _segmentUnmeteredOnly.value ->
                        "Kartendaten werden geladen, sobald WLAN da ist."
                    else -> "Kartendaten werden geladen."
                },
            )
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
                    profileConfirmed = readProfileConfirmed(),
                    plan = loadPlan(trainingPlanStore),
                    mapStyle = mapStyleById(
                        runCatching { keyValueStore.getString(MAP_STYLE_STORAGE_KEY) }.getOrNull(),
                    ),
                    syncConfig = runCatching { getSyncConfig(keyValueStore) }.getOrNull(),
                    onboardingSeen = runCatching {
                        keyValueStore.getString(ONBOARDING_STORAGE_KEY) != null
                    }.getOrDefault(true),
                    reminderSettings = reminderStore.readSettings(),
                    segmentUnmeteredOnly = runCatching { segmentSettings.unmeteredOnly }
                        .getOrDefault(true),
                    vitalsHistory = readVitalsHistory(keyValueStore),
                    shortSleeperHintShownAt = readShortSleeperHintShownAt(),
                    planFeasibilityAckKey = readPlanFeasibilityAckKey(),
                    placeSearchHistory = readPlaceSearchHistory(),
                )
            }
            _profile.value = restored.profile
            _profileConfirmed.value = restored.profileConfirmed
            _plan.value = restored.plan
            _planFeasibilityAckKey.value = restored.planFeasibilityAckKey
            _mapStyle.value = restored.mapStyle
            _placeSearchHistory.value = restored.placeSearchHistory
            _syncConfig.value = restored.syncConfig
            _reminderSettings.value = restored.reminderSettings
            _segmentUnmeteredOnly.value = restored.segmentUnmeteredOnly
            _shortSleeperHintShownAt.value = restored.shortSleeperHintShownAt
            _shortSleeperHintVisible.value = shouldShowShortSleeperHint(
                restored.shortSleeperHintShownAt,
                LocalDateTime.now(),
            )
            // Die gespeicherte Historie steht sofort zur Verfuegung — die
            // Auswertung wartet nicht auf Health Connect.
            vitalsHistory = restored.vitalsHistory
            if (!restored.vitalsHistory.isEmpty) {
                _vitals.value = restored.vitalsHistory.toSummary(
                    now = LocalDateTime.now(),
                    days = VITALS_HISTORY_WINDOW_DAYS,
                )
            }
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
        val profileConfirmed: Boolean,
        val plan: TrainingPlan?,
        val mapStyle: MapStyle,
        val syncConfig: SyncConfig?,
        val onboardingSeen: Boolean,
        val reminderSettings: ReminderSettings,
        val segmentUnmeteredOnly: Boolean,
        val vitalsHistory: VitalsHistory,
        val shortSleeperHintShownAt: LocalDateTime?,
        val planFeasibilityAckKey: String?,
        val placeSearchHistory: List<PlaceSearchHistoryEntry>,
    )
}

/**
 * Schluessel, der einen Trainingsplan fuer [AppViewModel.acknowledgePlanFeasibility]
 * identifiziert: Zieldistanz, Zieldatum und Wochenzahl. Genau die drei Groessen,
 * die [de.trailscape.core.assessPlanFeasibility] bewerten — aendert sich eine
 * davon, ist der Plan fuer diese Pruefung ein anderer, und eine alte
 * Quittierung darf ihn nicht mehr betreffen.
 *
 * Bewusst keine kryptografische Pruefsumme: Die drei Werte sind schon
 * eindeutig genug fuer einen lokalen Vergleich, und ein simpler String bleibt
 * beim Nachlesen in `SharedPreferences` verstaendlich.
 */
fun planFeasibilityIdentityKey(plan: TrainingPlan): String =
    "${plan.goal.distanceKm}|${plan.goal.date}|${plan.weeks.size}"

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
 * endgueltig von der Platte verschwindet. `TourList` legt die Anzeigedauer
 * seiner Undo-Snackbar auf denselben Wert, damit beide synchron ablaufen.
 */
const val UNDO_DELETE_GRACE_MS: Long = 5_000L

/**
 * Schluessel im [KeyValueStore] fuer den Zeitpunkt, an dem der
 * Kurzschlaefer-Hinweis zuletzt gezeigt wurde (siehe
 * [AppViewModel.shortSleeperHintShownAt]).
 */
const val SHORT_SLEEPER_HINT_STORAGE_KEY: String = "trailscape.hint.shortsleeper.v1"

/**
 * Schluessel im [KeyValueStore] fuer den zuletzt quittierten Plan-Schluessel
 * der Karte „Plan und Ziel passen nicht zusammen" (siehe
 * [AppViewModel.planFeasibilityAckKey] und [planFeasibilityIdentityKey]).
 */
const val PLAN_FEASIBILITY_ACK_STORAGE_KEY: String = "trailscape.today.planfeasibility.ack.v1"

/**
 * Ein zuletzt gewaehlter Ort der Kartensuche (siehe [AppViewModel.placeSearchHistory]).
 *
 * Bewusst ein eigener, schlanker Typ statt `de.trailscape.app.ui.map.Place`:
 * Das ViewModel liegt im geteilten `ui`-Paket und soll nicht von einem
 * einzelnen Feature-Paket (`ui.map`) abhaengen, nur um drei Werte zu
 * persistieren. Die Umrechnung zwischen beiden ist trivial und liegt beim
 * Aufrufer (`MapScreen.kt`).
 */
data class PlaceSearchHistoryEntry(val displayName: String, val lat: Double, val lon: Double) {
    /** Ob [other] denselben Ort meint — Grundlage fuer „nach vorn ruecken statt verdoppeln". */
    fun sameLocationAs(other: PlaceSearchHistoryEntry): Boolean = lat == other.lat && lon == other.lon
}

/** Wie viele zuletzt gesuchte Orte [AppViewModel.recordPlaceSearchHistory] behaelt. */
const val PLACE_SEARCH_HISTORY_LIMIT: Int = 5

/**
 * Schluessel im [KeyValueStore] fuer den Suchverlauf der Kartensuche (siehe
 * [AppViewModel.placeSearchHistory]).
 */
const val PLACE_SEARCH_HISTORY_STORAGE_KEY: String = "trailscape.map.searchhistory.v1"

/**
 * Trennzeichen der zusammengesetzten Suchverlauf-Zeichenkette — dasselbe
 * Steuerzeichen-Muster wie bei den geretteten Wegpunktnamen
 * (`PlanningStateSavers.kt`, `WAYPOINT_NAME_SEPARATOR`/`WAYPOINT_NO_NAME_MARKER`),
 * hier aber lokal und unabhaengig definiert: Beide Stellen loesen dasselbe
 * Problem (Menschentext, der Steuerzeichen praktisch nie enthaelt, in einem
 * einzelnen `String` fuer eine [KeyValueStore]-Zeile), teilen aber keinen
 * gemeinsamen Datentyp, den ein gemeinsames Symbol rechtfertigen wuerde.
 * [Char(31)] trennt die Felder eines Eintrags (Name/lat/lon), [Char(30)]
 * trennt die Eintraege selbst.
 */
private val SEARCH_HISTORY_FIELD_SEPARATOR: Char = Char(31)
private val SEARCH_HISTORY_ENTRY_SEPARATOR: Char = Char(30)

/** Baut den Suchverlauf zu einer einzelnen, [KeyValueStore]-tauglichen Zeichenkette zusammen. */
private fun encodePlaceSearchHistory(entries: List<PlaceSearchHistoryEntry>): String =
    entries.joinToString(SEARCH_HISTORY_ENTRY_SEPARATOR.toString()) { entry ->
        listOf(entry.displayName, entry.lat.toString(), entry.lon.toString())
            .joinToString(SEARCH_HISTORY_FIELD_SEPARATOR.toString())
    }

/**
 * Das Gegenstueck zu [encodePlaceSearchHistory]. Ein defekter oder fehlender
 * Wert liefert eine leere Liste statt zu werfen — derselbe Umgang wie bei
 * jedem anderen [KeyValueStore]-Lesen in diesem ViewModel (`runCatching` beim
 * Aufrufer).
 */
private fun decodePlaceSearchHistory(raw: String?): List<PlaceSearchHistoryEntry> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split(SEARCH_HISTORY_ENTRY_SEPARATOR).mapNotNull { record ->
        val fields = record.split(SEARCH_HISTORY_FIELD_SEPARATOR)
        if (fields.size != 3) return@mapNotNull null
        val lat = fields[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = fields[2].toDoubleOrNull() ?: return@mapNotNull null
        PlaceSearchHistoryEntry(displayName = fields[0], lat = lat, lon = lon)
    }.take(PLACE_SEARCH_HISTORY_LIMIT)
}

/**
 * Meldung, wenn die aus Health Connect geholten Touren nicht gespeichert
 * werden konnten. Der Import-Zeitstempel ist dann bereits zurueckgerollt
 * (siehe `AppViewModel.applyReport`), der naechste Sync holt dieselben
 * Workouts also erneut.
 */
private const val HEALTH_SAVE_FAILED_MESSAGE: String =
    "Die importierten Touren konnten nicht gespeichert werden. " +
        "Beim nächsten Sync wird es erneut versucht."
