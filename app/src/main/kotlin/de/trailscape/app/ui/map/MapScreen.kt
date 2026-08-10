package de.trailscape.app.ui.map

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.formatToday
import de.trailscape.app.ui.mapStyleSubtitle
import de.trailscape.app.ui.mapStyles
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.OverlayGap
import de.trailscape.app.ui.theme.OverlayScreenPadding
import de.trailscape.core.GeoResult
import de.trailscape.core.NavState
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RouteNavigator
import de.trailscape.core.RouteProfile
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.brouterProfile
import de.trailscape.core.buildGpx
import de.trailscape.core.computeStats
import de.trailscape.core.fetchRoute
import de.trailscape.core.safeFileName
import de.trailscape.core.searchPlaces
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # Karte — Aufzeichnung, Planung, Navigation und Offline-Ausschnitte
 *
 * Port von `lib/screens/map_screen.dart` (2154 Zeilen) auf Compose und
 * MapLibre. Der Screen selbst haelt nur den *Bildschirmzustand* (Planungsmodus,
 * Wegpunkte, Suchtext, Navigationsziel, Downloadfortschritt); alles, was
 * laenger lebt, liegt woanders:
 *
 *  * Touren, Auswahl und Kartenstil im geteilten [AppViewModel],
 *  * die laufende Aufzeichnung im [RecordingRepository] (Vordergrunddienst),
 *  * die Karte selbst im [MapController] (siehe `MapViewHost.kt`).
 *
 * ## Bewusste Unterschiede zum Flutter-Original
 *  * **Kein Namensdialog nach dem Stopp.** In Flutter lief die Aufzeichnung im
 *    UI-Prozess; der Screen baute die Tour selbst und fragte vorher nach einem
 *    Namen. Nativ speichert der Dienst die Tour selbst (er ueberlebt das
 *    Schliessen der App), vergibt „Tour <Datum>" und meldet sie ueber
 *    [RecordingRepository.lastFinishedRideId]. Quittiert wird diese Meldung im
 *    [AppViewModel] (nicht hier): Gestoppt werden kann auch ueber die
 *    Notification, waehrend ein anderer Tab sichtbar ist. Das ViewModel laedt
 *    die Liste neu, waehlt die Tour aus und schickt den Hinweis in
 *    [AppViewModel.messages]; umbenannt wird im Touren-Tab.
 *  * **Navigation auch entlang einer geplanten Route**, nicht nur entlang
 *    einer gespeicherten Tour.
 *  * **Positionen der Navigation** kommen aus der laufenden Aufzeichnung,
 *    wenn eine laeuft — das Original abonnierte GPS ein zweites Mal.
 *  * **Keine Vibration bei „abseits der Route"**: Dafuer fehlt die
 *    `VIBRATE`-Berechtigung im Manifest, das hier nicht angefasst wird. Die
 *    Warnung erscheint als Meldung und in der Navigationsleiste.
 *  * **Suche jederzeit**, nicht nur im Planungsmodus (siehe `PlanningPanel.kt`).
 *  * **Hoehenprofil** fuer die ausgewaehlte Tour und die geplante Route — das
 *    hatte der Karten-Screen in Flutter noch nicht.
 *  * **Rundkurs aus der Trainingsempfehlung.** Der Trainings-Tab schickt ueber
 *    [AppViewModel.pendingRouteTarget] ein Ziel her; dieser Screen oeffnet
 *    dafuer das Panel aus `RouteGenerationPanel.kt`, laesst im
 *    [RouteGenerationController] suchen und legt den uebernommenen Vorschlag in
 *    **denselben** `plannedRoute`-Zustand, den die Planung von Hand fuellt —
 *    Hoehenprofil, Speichern, Teilen und Navigation funktionieren damit ohne
 *    einen zweiten Weg. Das Flutter-Original kannte weder Generator noch
 *    Uebergabe zwischen den Tabs.
 *  * **Automatischer Erst-Zoom auf die Position** statt des dauerhaften
 *    Deutschland-Defaults: Liegt beim Start (oder unmittelbar nach einer
 *    erteilten Freigabe) eine Standortfreigabe vor und hat die Nutzerin die
 *    Karte noch nicht selbst bewegt, zoomt sie einmalig sanft auf die
 *    aktuelle Position (Zoom ~13) — kein Dart-Vorbild. Details siehe der
 *    Effekt bei `autoLocationZoomDone` weiter unten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(appViewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val controller = remember { MapController() }

    // ------------------------------------------------------ geteilter Zustand
    val mapStyle by appViewModel.mapStyle.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val selectedRide by appViewModel.selectedRide.collectAsStateWithLifecycle()

    val isRecording by RecordingRepository.isRecording.collectAsStateWithLifecycle()
    val isPaused by RecordingRepository.isPaused.collectAsStateWithLifecycle()
    val elapsedMs by RecordingRepository.elapsedMs.collectAsStateWithLifecycle()
    val recordedKm by RecordingRepository.distanceKm.collectAsStateWithLifecycle()
    val livePoints by RecordingRepository.points.collectAsStateWithLifecycle()
    val speedKmh by RecordingRepository.speedKmh.collectAsStateWithLifecycle()
    val recordingError by RecordingRepository.lastError.collectAsStateWithLifecycle()

    // Der Download laeuft ausserhalb der Komposition weiter (siehe
    // OfflineDownloadController) — hier wird nur sein Fortschritt gelesen.
    val downloadState by OfflineDownloadController.state.collectAsStateWithLifecycle()

    // Ebenso die Rundkurs-Suche: Sie dauert 20–40 s und ueberlebt deshalb den
    // Tab-Wechsel (siehe RouteGenerationController).
    val generation by RouteGenerationController.state.collectAsStateWithLifecycle()
    val pendingRouteTarget by appViewModel.pendingRouteTarget.collectAsStateWithLifecycle()

    // ---------------------------------------------------- Zustand des Screens
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var planning by rememberSaveable { mutableStateOf(false) }
    var waypoints by remember { mutableStateOf<List<Waypoint>>(emptyList()) }
    var plannedRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    var routeProfile by rememberSaveable { mutableStateOf(RouteProfile.GRAVEL) }
    var planBusy by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf<String?>(null) }

    // Fortschritt bei weit auseinanderliegenden Wegpunkten: `fetchRoute` zerlegt
    // solche Routen in mehrere Server-Anfragen (siehe `Routing.kt`), was spuerbar
    // dauert. Bei nur einem Teilstueck bleibt die Anzeige leer.
    var planProgress by remember { mutableStateOf<String?>(null) }

    // Ob [plannedRoute] aus dem Rundkurs-Generator stammt statt aus gesetzten
    // Wegpunkten. Der Generator bringt eine fertige Route ohne Wegpunkte mit —
    // ohne dieses Flag wuerde der Planungs-Effekt unten sie beim naechsten Lauf
    // (leere Wegpunktliste) sofort wieder auf `null` setzen. Sobald wieder von
    // Hand geplant wird, faellt es zurueck auf `false`.
    var routeFromGenerator by remember { mutableStateOf(false) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchTrigger by remember { mutableStateOf(0) }
    var searchResults by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchMarker by remember { mutableStateOf<Waypoint?>(null) }

    var navTarget by remember { mutableStateOf<NavigationTarget?>(null) }
    var navState by remember { mutableStateOf<NavState?>(null) }
    var navTotalKm by remember { mutableStateOf(0.0) }

    var liveAscentM by remember { mutableStateOf(0.0) }
    var hoverPoint by remember { mutableStateOf<TrackPoint?>(null) }

    var showStyleSheet by remember { mutableStateOf(false) }
    var saveRouteDialog by remember { mutableStateOf(false) }
    var deleteDialogRide by remember { mutableStateOf<Ride?>(null) }

    // Die Absicht hinter einer Berechtigungsanfrage — bewusst ein
    // `rememberSaveable`-faehiger Wert und kein Lambda: Waehrend des
    // System-Dialogs kann die Activity neu aufgebaut werden (Drehen,
    // Speicherdruck). Ein in `remember` gehaltenes Lambda waere danach weg,
    // die erteilte Freigabe bliebe folgenlos.
    var pendingAction by rememberSaveable { mutableStateOf<PendingAction?>(null) }
    var pendingNavigateRideId by rememberSaveable { mutableStateOf<String?>(null) }

    // Nach erteilter Freigabe auszufuehrende Absicht. Getrennt von
    // [pendingAction], weil die Aktionen selbst weiter unten als lokale
    // Funktionen stehen und der Launcher-Callback sie nicht sehen kann.
    var grantedAction by remember { mutableStateOf<PendingAction?>(null) }

    // --------------------------------------------------------- Berechtigungen
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        locationGranted = hasLocationPermission(context)
        val action = pendingAction
        pendingAction = null
        if (action == null) return@rememberLauncherForActivityResult
        if (locationGranted || action == PendingAction.GENERATE_ROUTES) {
            // Die Rundkurs-Suche braucht die Freigabe nicht zwingend: Ohne sie
            // startet die Runde eben in der Kartenmitte. Sie hier trotzdem
            // anzufragen ist der einzige Weg, spaeter doch den echten Standort
            // zu bekommen — abgelehnt zu werden darf die Suche aber nicht
            // blockieren, sonst haengt die Nutzerin bei dauerhaft verweigerter
            // Freigabe fest.
            grantedAction = action
        } else {
            pendingNavigateRideId = null
            appViewModel.showMessage("Standortfreigabe wurde abgelehnt.")
        }
    }

    /**
     * Fuehrt [run] sofort aus, wenn die noetigen Berechtigungen vorliegen —
     * sonst fragt es sie erst an und merkt sich [action] als Absicht, die der
     * Effekt weiter unten nach der Freigabe ausfuehrt. Genau wie
     * `_ensureLocationPermission` im Original, nur ohne blockierendes `await`.
     */
    fun withPermissions(action: PendingAction, run: () -> Unit) {
        val missing = missingPermissions(context, action == PendingAction.RECORD)
        if (missing.isEmpty()) {
            run()
            return
        }
        pendingAction = action
        permissionLauncher.launch(missing)
    }

    // ------------------------------------------------------------- Meldungen
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Erst anzeigen, dann quittieren: `clearError()` schreibt den StateFlow auf
    // null, das rekomponiert den Screen und aendert den Schluessel dieses
    // Effekts — die Coroutine (und mit ihr die noch wartende Snackbar) wuerde
    // dann abgebrochen, bevor die Meldung je zu sehen war.
    LaunchedEffect(recordingError) {
        val message = recordingError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        RecordingRepository.clearError()
    }

    // Die fertig gespeicherte Tour quittiert das AppViewModel (siehe dessen
    // init-Block) — es laedt die Liste neu, waehlt die Tour aus und schickt
    // die Meldung ueber [AppViewModel.messages]. Dieser Screen braucht dafuer
    // keinen eigenen Effekt mehr; so bekommt auch der Touren- oder
    // Trainings-Tab die neue Tour mit, wenn ueber die Notification gestoppt
    // wurde.

    // ------------------------------------------------- Karte mit Daten fuellen
    LaunchedEffect(controller, selectedRide?.id, selectedRide?.points?.size) {
        controller.setTrack(selectedRide?.points ?: emptyList())
    }

    LaunchedEffect(controller, selectedRide?.id) {
        val points = selectedRide?.points ?: return@LaunchedEffect
        if (points.isNotEmpty()) {
            controller.fitToPoints(points)
        }
    }

    LaunchedEffect(controller, plannedRoute) {
        controller.setPlannedRoute(plannedRoute?.points ?: emptyList())
    }

    // Der Ablesepunkt des Hoehenprofils gehoert zu genau einer Tour bzw. Route.
    LaunchedEffect(selectedRide?.id, isRecording) {
        hoverPoint = null
    }

    // -------------------------------------------------- Automatischer Erst-Zoom
    // Startet die Karte am Deutschland-Default (siehe `GERMANY_LAT/LON/ZOOM` in
    // MapViewHost.kt) UND liegt eine Standortfreigabe vor — egal ob von Anfang
    // an erteilt oder soeben ueber einen der Berechtigungsdialoge oben —, zoomt
    // das genau einmal sanft auf die aktuelle Position (Zoom ~13). Greift
    // NICHT ein, wenn die Kamera schon von der Default-Position abweicht (der
    // Nutzer hat selbst gescrollt/gezoomt, ggf. schon vor einer Drehung — siehe
    // `savedLat/Lon/Zoom` in MapViewHost.kt, die das ueber Config-Aenderungen
    // hinweg merken) oder gerade eine Tour ausgewaehlt, eine Route geplant/
    // generiert, aufgezeichnet oder navigiert wird — sonst wuerde der Zoom in
    // eine bestehende Ansicht graetschen.
    //
    // `autoLocationZoomDone` ist `rememberSaveable`: Ohne dieses Flag wuerde
    // eine Drehung waehrend/nach dem Zoom (derselbe `mapReady`/`locationGranted`
    // -Zustand) den Effekt erneut auslösen und die Karte ein zweites Mal
    // verschieben, obwohl der Nutzer inzwischen vielleicht selbst navigiert hat.
    var autoLocationZoomDone by rememberSaveable { mutableStateOf(false) }
    val mapReady = controller.isReady

    LaunchedEffect(mapReady, locationGranted) {
        if (autoLocationZoomDone || !mapReady || !locationGranted) return@LaunchedEffect
        if (planning || isRecording || selectedRide != null ||
            navTarget != null || generation.target != null
        ) {
            return@LaunchedEffect
        }
        val camera = controller.rememberCamera()
        val atDefault = camera != null &&
            abs(camera.lat - GERMANY_LAT) < DEFAULT_CAMERA_POSITION_EPSILON &&
            abs(camera.lon - GERMANY_LON) < DEFAULT_CAMERA_POSITION_EPSILON &&
            abs(camera.zoom - GERMANY_ZOOM) < DEFAULT_CAMERA_ZOOM_EPSILON
        if (!atDefault) {
            // Kamera weicht schon vom Default ab: Der Nutzer war hier schon
            // selbst am Werk — endgueltig verzichten, kein spaeterer Versuch.
            autoLocationZoomDone = true
            return@LaunchedEffect
        }
        val position = currentLocation(context) ?: return@LaunchedEffect
        autoLocationZoomDone = true
        controller.moveTo(position.latitude, position.longitude, minZoom = AUTO_LOCATION_ZOOM)
    }

    LaunchedEffect(controller, livePoints.size) {
        controller.setLiveTrack(livePoints)
        val last = livePoints.lastOrNull() ?: return@LaunchedEffect
        controller.moveTo(
            lat = last.lat,
            lon = last.lon,
            minZoom = if (livePoints.size == 1) MIN_RECORDING_ZOOM else null,
            animate = false,
        )
    }

    LaunchedEffect(livePoints.size) {
        liveAscentM = if (livePoints.size < 2) {
            0.0
        } else {
            withContext(Dispatchers.Default) { computeStats(livePoints).ascentM }
        }
    }

    val markers = buildMapMarkers(
        planning = planning,
        waypoints = waypoints,
        ride = selectedRide,
        searchMarker = searchMarker,
        hoverPoint = hoverPoint,
    )
    LaunchedEffect(controller, markers) {
        controller.setMarkers(markers)
    }

    // ---------------------------------------------------------- Routenplanung
    LaunchedEffect(waypoints, routeProfile, routeFromGenerator) {
        if (routeFromGenerator) {
            // Die Route kommt fertig aus dem Rundkurs-Generator; sie hat keine
            // Wegpunkte, aus denen sich etwas nachrechnen liesse.
            planBusy = false
            planError = null
            planProgress = null
            return@LaunchedEffect
        }
        if (waypoints.size < 2) {
            plannedRoute = null
            planError = null
            planBusy = false
            planProgress = null
            return@LaunchedEffect
        }
        planBusy = true
        planError = null
        planProgress = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                fetchRoute(
                    waypoints = waypoints,
                    profileId = brouterProfile(routeProfile),
                    client = AppServices.httpClient,
                    onProgress = { done, total ->
                        // Nur melden, wenn wirklich zerlegt wurde.
                        planProgress = if (total > 1) "Teilstrecke $done von $total …" else null
                    },
                )
            }
        }
        result
            .onSuccess {
                plannedRoute = it
                planError = null
            }
            .onFailure {
                // Wegpunkte bleiben stehen, damit es sich erneut versuchen laesst.
                plannedRoute = null
                planError = it.message?.takeIf(String::isNotBlank)
                    ?: "Route konnte nicht berechnet werden."
            }
        planBusy = false
        planProgress = null
    }

    // --------------------------------------------------------------- Ortssuche
    LaunchedEffect(searchQuery, searchTrigger) {
        val query = searchQuery.trim()
        if (query.length < MIN_SEARCH_LENGTH) {
            searchResults = emptyList()
            searchError = null
            searchBusy = false
            return@LaunchedEffect
        }
        // Entprellen: erst tippen lassen, dann fragen (Nominatim-Richtlinien).
        delay(SEARCH_DEBOUNCE_MS)
        searchBusy = true
        searchError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { searchPlaces(query, AppServices.httpClient) }
        }
        result
            .onSuccess { hits ->
                searchResults = hits.take(MAX_SEARCH_RESULTS)
                searchError = if (hits.isEmpty()) "Keine Treffer gefunden." else null
            }
            .onFailure {
                searchResults = emptyList()
                searchError = it.message?.takeIf(String::isNotBlank) ?: "Ortssuche fehlgeschlagen."
            }
        searchBusy = false
    }

    // -------------------------------------------------------------- Navigation
    LaunchedEffect(navTarget, isRecording) {
        val target = navTarget ?: return@LaunchedEffect
        val navigator = runCatching { RouteNavigator(target.points) }.getOrElse { error ->
            appViewModel.showMessage(
                error.message?.takeIf(String::isNotBlank) ?: "Navigation nicht möglich.",
            )
            navTarget = null
            return@LaunchedEffect
        }
        navTotalKm = navigator.totalKm

        // Laeuft eine Aufzeichnung, kommen die Positionen von dort — ein
        // zweiter GPS-Abonnent braeuchte nur Strom fuer dieselben Punkte.
        val positions: Flow<Pair<Double, Double>> = if (isRecording) {
            RecordingRepository.lastPoint.filterNotNull().map { it.lat to it.lon }
        } else {
            locationUpdates(context).map { it.latitude to it.longitude }
        }

        var wasOffRoute = false
        positions.collect { (lat, lon) ->
            val state = navigator.update(lat, lon)
            navState = state
            controller.moveTo(lat, lon, minZoom = null, animate = false)
            if (state.offRoute && !wasOffRoute) {
                appViewModel.showMessage("Achtung: Du bist abseits der Route.")
            }
            wasOffRoute = state.offRoute
        }
    }

    // Wird die navigierte Tour geloescht, endet die Navigation (wie in Dart).
    LaunchedEffect(rides, navTarget?.rideId) {
        val rideId = navTarget?.rideId ?: return@LaunchedEffect
        if (rides.none { it.id == rideId }) {
            navTarget = null
            navState = null
        }
    }

    // -------------------------------------------------------------- Aktionen
    fun stopNavigation() {
        navTarget = null
        navState = null
        navTotalKm = 0.0
    }

    fun exitPlanning() {
        planning = false
        waypoints = emptyList()
        plannedRoute = null
        planError = null
        planBusy = false
        searchMarker = null
        hoverPoint = null
        routeFromGenerator = false
    }

    /** Verwirft den Vorschlag samt Panel und raeumt die Vorschau von der Karte. */
    fun discardGeneratedRoute() {
        RouteGenerationController.close()
        routeFromGenerator = false
        plannedRoute = null
    }

    /**
     * Uebernimmt den gewaehlten Vorschlag als **die** geplante Route — also in
     * genau den Zustand, den auch die Planung von Hand erzeugt. Damit greifen
     * Hoehenprofil, Teilen, Speichern und „Navigieren" sofort, ohne dass es
     * dafuer einen zweiten Weg gaebe.
     */
    fun applyGeneratedRoute() {
        val candidate = generation.selected ?: return
        RouteGenerationController.close()
        appViewModel.select(null)
        waypoints = emptyList()
        planError = null
        planBusy = false
        routeFromGenerator = true
        plannedRoute = candidate.route
        planning = true
        controller.fitToPoints(candidate.route.points)
        appViewModel.showMessage("Runde übernommen – du kannst sie speichern oder navigieren.")
    }

    // Jede Aktion mit Standortbedarf gibt es zweimal: `run…` ist der Rumpf,
    // der die Berechtigung als gegeben voraussetzt, die gleichnamige Funktion
    // ohne Praefix holt sie erst ein. Der Effekt nach einer erteilten Freigabe
    // ruft ausschliesslich die `run…`-Fassung auf — sonst wuerde er bei einer
    // abgelehnten Nebenberechtigung (POST_NOTIFICATIONS ab Android 13) sofort
    // den naechsten Systemdialog ausloesen und sich im Kreis drehen.
    fun runRecording() {
        if (!isLocationEnabled(context)) {
            appViewModel.showMessage("Standortdienste sind deaktiviert.")
            return
        }
        if (planning) exitPlanning()
        discardGeneratedRoute()
        appViewModel.select(null)
        RecordingRepository.start(context)
    }

    fun startRecording() {
        withPermissions(PendingAction.RECORD) { runRecording() }
    }

    fun runGoToMyPosition() {
        locationGranted = true
        scope.launch {
            if (!isLocationEnabled(context)) {
                appViewModel.showMessage("Standortdienste sind deaktiviert.")
                return@launch
            }
            // Erst ein frischer Fix (wie `_goToMyPosition` in Dart), sonst
            // das, was der Standortpunkt der Karte zuletzt gesehen hat.
            val position = currentLocation(context)?.let { it.latitude to it.longitude }
                ?: controller.lastKnownLocation()
            if (position == null) {
                appViewModel.showMessage("Position konnte nicht ermittelt werden.")
                return@launch
            }
            controller.moveTo(position.first, position.second, MIN_RECORDING_ZOOM)
        }
    }

    fun goToMyPosition() {
        withPermissions(PendingAction.LOCATE) { runGoToMyPosition() }
    }

    fun runUseMyPositionAsStart() {
        locationGranted = true
        scope.launch {
            val position = currentLocation(context)
            if (position == null) {
                appViewModel.showMessage("Position konnte nicht ermittelt werden.")
                return@launch
            }
            waypoints = listOf(Waypoint(position.latitude, position.longitude)) + waypoints
            controller.moveTo(position.latitude, position.longitude, MIN_RECORDING_ZOOM)
        }
    }

    fun useMyPositionAsStart() {
        withPermissions(PendingAction.PLAN_START) { runUseMyPositionAsStart() }
    }

    fun onMapTap(lat: Double, lon: Double) {
        if (!planning) return
        // Wer selbst Wegpunkte setzt, plant wieder von Hand — die generierte
        // Runde ist ab dann nicht mehr die Grundlage (siehe Planungs-Effekt).
        routeFromGenerator = false
        val hit = waypoints.indexOfFirst { waypoint ->
            controller.isWithinScreenDistance(
                TrackPoint(lat = waypoint.lat, lon = waypoint.lon),
                lat,
                lon,
                WAYPOINT_TOUCH_RADIUS_PX,
            )
        }
        waypoints = if (hit >= 0) {
            waypoints.filterIndexed { index, _ -> index != hit }
        } else {
            waypoints + Waypoint(lat, lon)
        }
    }

    fun onSearchResult(result: GeoResult) {
        searchMarker = Waypoint(result.lat, result.lon)
        searchResults = emptyList()
        searchOpen = false
        if (planning) {
            waypoints = waypoints + Waypoint(result.lat, result.lon)
        }
        controller.moveTo(result.lat, result.lon, MIN_RECORDING_ZOOM)
    }

    fun shareRoute(name: String, points: List<TrackPoint>) {
        if (points.isEmpty()) {
            appViewModel.showMessage("Keine Punkte zum Teilen.")
            return
        }
        scope.launch {
            runCatching { shareGpxFile(context, name, points) }
                .onFailure {
                    appViewModel.showMessage(
                        "Teilen fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}",
                    )
                }
        }
    }

    fun startDownload() {
        if (downloadState.running) return
        val bounds = controller.visibleBounds()
        val zoom = controller.currentZoom()
        if (bounds == null || zoom == null) {
            appViewModel.showMessage("Karte ist noch nicht bereit.")
            return
        }
        // Alle Grenzen (sinnvolle Groesse, Kachelzahl, Zoombereich) steckt
        // `planOfflineDownload` — reine Rechnung, in OfflineTileMath.kt
        // getestet.
        when (val plan = planOfflineDownload(bounds, zoom, mapStyle)) {
            is OfflineDownloadPlan.Rejected -> appViewModel.showMessage(plan.message)
            // Bewusst nicht in `scope` (der stirbt beim Tab-Wechsel mitsamt
            // dem halbfertigen Download), sondern im App-Scope; die
            // Abschlussmeldung kommt ueber den geteilten Meldungskanal zurueck.
            is OfflineDownloadPlan.Ready -> OfflineDownloadController.start(
                context = context,
                style = mapStyle,
                bounds = bounds,
                plan = plan,
                name = "${mapStyle.label} · ${formatToday()}",
                onMessage = appViewModel::showMessage,
            )
        }
    }

    /**
     * Startet die Rundkurs-Suche. Startpunkt ist die aktuelle Position; ohne
     * Fix (oder ohne Freigabe) die Kartenmitte — das Panel weist darauf hin.
     * Die Suche selbst laeuft im [RouteGenerationController] und ueberlebt
     * damit den Tab-Wechsel.
     */
    fun runGenerateRoutes() {
        locationGranted = hasLocationPermission(context)
        scope.launch {
            val position = currentLocation(context)
            val start = if (position != null) {
                TrackPoint(lat = position.latitude, lon = position.longitude)
            } else {
                controller.rememberCamera()?.let { TrackPoint(lat = it.lat, lon = it.lon) }
            }
            if (start == null) {
                appViewModel.showMessage(
                    "Kein Startpunkt: Position unbekannt und die Karte ist noch nicht bereit.",
                )
                return@launch
            }
            RouteGenerationController.start(
                start = start,
                fromMapCenter = position == null,
                onMessage = appViewModel::showMessage,
            )
        }
    }

    fun generateRoutes() {
        withPermissions(PendingAction.GENERATE_ROUTES) { runGenerateRoutes() }
    }

    fun runNavigateRide(ride: Ride) {
        pendingNavigateRideId = null
        locationGranted = true
        navState = null
        navTarget = NavigationTarget(ride.id, ride.name, ride.points)
    }

    fun navigateRide(ride: Ride) {
        if (ride.points.size < 2) {
            appViewModel.showMessage("Die Tour hat zu wenige Punkte für die Navigation.")
            return
        }
        // Die Tour merken, falls der Systemdialog die Activity neu aufbaut.
        pendingNavigateRideId = ride.id
        withPermissions(PendingAction.NAVIGATE_RIDE) { runNavigateRide(ride) }
    }

    fun runNavigatePlannedRoute() {
        val route = plannedRoute ?: return
        if (route.points.size < 2) return
        locationGranted = true
        navState = null
        navTarget = NavigationTarget(null, "Geplante Route", route.points)
    }

    fun navigatePlannedRoute() {
        if ((plannedRoute?.points?.size ?: 0) < 2) return
        withPermissions(PendingAction.NAVIGATE_ROUTE) { runNavigatePlannedRoute() }
    }

    // Nachgereichte Absicht ausfuehren, sobald die Freigabe erteilt wurde. Der
    // Launcher-Callback selbst kann die Aktionen oben nicht aufrufen (lokale
    // Funktionen, die erst nach ihm im Rumpf stehen), deshalb der Umweg ueber
    // [grantedAction].
    LaunchedEffect(grantedAction) {
        val action = grantedAction ?: return@LaunchedEffect
        grantedAction = null
        when (action) {
            PendingAction.RECORD -> runRecording()
            PendingAction.LOCATE -> runGoToMyPosition()
            PendingAction.PLAN_START -> runUseMyPositionAsStart()
            PendingAction.NAVIGATE_ROUTE -> runNavigatePlannedRoute()
            PendingAction.GENERATE_ROUTES -> runGenerateRoutes()
            PendingAction.NAVIGATE_RIDE -> {
                val rideId = pendingNavigateRideId
                pendingNavigateRideId = null
                rides.firstOrNull { it.id == rideId }?.let { runNavigateRide(it) }
            }
        }
    }

    // ------------------------------------- Trainingsempfehlung → Routenziel
    // Das Ziel wartet als StateFlow im AppViewModel, bis dieser Screen nach dem
    // Tab-Wechsel wirklich in der Komposition ist (siehe dessen KDoc).
    LaunchedEffect(pendingRouteTarget) {
        val target = pendingRouteTarget ?: return@LaunchedEffect
        appViewModel.consumeRouteTarget()
        if (planning) exitPlanning()
        appViewModel.select(null)
        RouteGenerationController.open(target)
    }

    // Der ausgewaehlte Vorschlag ist die Vorschau auf der Karte: Er landet in
    // demselben `plannedRoute`, das auch die Planung von Hand fuellt — also in
    // der blauen, gestrichelten Routenebene aus `MapViewHost.kt`.
    // Schluessel bewusst nur aus billigen Werten: `candidates` traegt komplette
    // Punktlisten, ein Vergleich davon liefe bei jeder Rekomposition mit.
    // (Ziel, Seed, Zahl der Vorschlaege, Auswahl) benennt den Vorschlag genauso
    // eindeutig.
    LaunchedEffect(
        generation.target,
        generation.seed,
        generation.candidates.size,
        generation.selectedIndex,
    ) {
        if (generation.target == null) return@LaunchedEffect
        val candidate = generation.selected
        if (candidate == null) {
            if (routeFromGenerator) {
                routeFromGenerator = false
                plannedRoute = null
            }
            return@LaunchedEffect
        }
        routeFromGenerator = true
        plannedRoute = candidate.route
        controller.fitToPoints(candidate.route.points)
    }

    // ------------------------------------------------------------------ Aufbau
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    Scaffold(
        // Die Huelle (TrailscapeApp) hat die System-Insets schon aufgeloest.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MapViewHost(
                controller = controller,
                style = mapStyle,
                locationEnabled = locationGranted,
                onMapTap = ::onMapTap,
                modifier = Modifier.fillMaxSize(),
            )

            // ------------------------------------------------------------ oben
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(OverlayScreenPadding),
                verticalArrangement = Arrangement.spacedBy(OverlayGap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (navTarget == null) {
                        MapPillButton(
                            label = if (planning) "Planung beenden" else "Route planen",
                            icon = if (planning) Icons.Filled.Close else Icons.Filled.Place,
                            active = planning,
                            activeColor = RouteBlue,
                            onClick = {
                                if (planning) {
                                    exitPlanning()
                                } else if (isRecording) {
                                    appViewModel.showMessage("Beende zuerst die Aufzeichnung.")
                                } else {
                                    // Von Hand planen loest den Generator ab —
                                    // zwei Panels uebereinander helfen niemandem.
                                    discardGeneratedRoute()
                                    appViewModel.select(null)
                                    planning = true
                                    waypoints = emptyList()
                                    plannedRoute = null
                                    planError = null
                                }
                            },
                        )
                        Spacer(Modifier.width(OverlayGap))
                    }
                    MapCircleButton(
                        icon = Icons.Filled.Search,
                        contentDescription = "Ort suchen",
                        active = searchOpen,
                        onClick = { searchOpen = !searchOpen },
                    )
                    Spacer(Modifier.width(OverlayGap))
                    MapCircleButton(
                        icon = Icons.Filled.Layers,
                        contentDescription = "Kartenstil",
                        onClick = { showStyleSheet = true },
                    )
                    Spacer(Modifier.width(OverlayGap))
                    MapCircleButton(
                        icon = Icons.Filled.DownloadForOffline,
                        contentDescription = "Kartenausschnitt herunterladen",
                        enabled = !downloadState.running,
                        onClick = ::startDownload,
                    )
                }

                if (searchOpen) {
                    SearchPanel(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        busy = searchBusy,
                        error = searchError,
                        results = searchResults,
                        planning = planning,
                        onSearchNow = { searchTrigger++ },
                        onSelect = ::onSearchResult,
                        onClose = { searchOpen = false },
                    )
                }

                navTarget?.let { target ->
                    NavigationCard(
                        label = target.label,
                        remainingKm = navState?.remainingKm ?: navTotalKm,
                        doneKm = navState?.doneKm,
                        offRoute = navState?.offRoute == true,
                        onStop = ::stopNavigation,
                    )
                }

                if (generation.target != null) {
                    RouteGenerationPanel(
                        state = generation,
                        maxHeight = screenHeight * PLAN_PANEL_MAX_HEIGHT_FACTOR,
                        onStart = ::generateRoutes,
                        onCancel = RouteGenerationController::cancel,
                        onSelect = RouteGenerationController::select,
                        onNextSuggestions = {
                            RouteGenerationController.nextSuggestions(appViewModel::showMessage)
                        },
                        onApply = ::applyGeneratedRoute,
                        onDiscard = ::discardGeneratedRoute,
                    )
                }

                if (planning) {
                    PlanningCard(
                        profile = routeProfile,
                        onProfileChange = { routeProfile = it },
                        waypointCount = waypoints.size,
                        route = plannedRoute,
                        busy = planBusy,
                        error = planError,
                        maxHeight = screenHeight * PLAN_PANEL_MAX_HEIGHT_FACTOR,
                        progress = planProgress,
                        generated = routeFromGenerator,
                        onUseMyPosition = ::useMyPositionAsStart,
                        onUndo = {
                            routeFromGenerator = false
                            waypoints = waypoints.dropLast(1)
                        },
                        onClear = {
                            routeFromGenerator = false
                            waypoints = emptyList()
                            plannedRoute = null
                            planError = null
                        },
                        onSave = { saveRouteDialog = true },
                        onShare = {
                            plannedRoute?.let { shareRoute("trailscape-route", it.points) }
                        },
                        onNavigate = ::navigatePlannedRoute,
                        onHoverPoint = { hoverPoint = it },
                    )
                }

                if (downloadState.running) {
                    DownloadProgressCard(
                        done = downloadState.completedTiles,
                        total = downloadState.totalTiles,
                    )
                }
            }

            // ----------------------------------------------------------- unten
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(OverlayScreenPadding),
                horizontalAlignment = Alignment.End,
            ) {
                RecordButton(
                    recording = isRecording,
                    onClick = { if (isRecording) RecordingRepository.stop() else startRecording() },
                )
                Spacer(Modifier.height(12.dp))
                LocateButton(onClick = ::goToMyPosition)
                Spacer(Modifier.height(12.dp))

                val ride = selectedRide
                when {
                    isRecording -> LiveRecordingCard(
                        speedKmh = speedKmh,
                        distanceKm = recordedKm,
                        elapsedS = (elapsedMs / 1000).toInt(),
                        ascentM = liveAscentM,
                        pointCount = livePoints.size,
                        paused = isPaused,
                        onTogglePause = { RecordingRepository.togglePause() },
                        onStop = { RecordingRepository.stop() },
                    )

                    ride != null -> RideCard(
                        ride = ride,
                        navigating = navTarget?.rideId == ride.id,
                        onNavigate = { navigateRide(ride) },
                        onShare = { shareRoute(ride.name, ride.points) },
                        onDelete = { deleteDialogRide = ride },
                        onClose = {
                            hoverPoint = null
                            appViewModel.select(null)
                        },
                        onHoverPoint = { hoverPoint = it },
                    )
                }
            }
        }
    }

    // ----------------------------------------------------------------- Dialoge
    if (showStyleSheet) {
        MapStyleSheet(
            current = mapStyle,
            onSelect = {
                appViewModel.setMapStyle(it)
                showStyleSheet = false
            },
            onDismiss = { showStyleSheet = false },
        )
    }

    if (saveRouteDialog) {
        val route = plannedRoute
        if (route == null) {
            saveRouteDialog = false
        } else {
            NameDialog(
                title = "Name der Route",
                suggestion = "Route ${formatToday()}",
                confirmLabel = "Speichern",
                onDismiss = { saveRouteDialog = false },
                onConfirm = { name ->
                    saveRouteDialog = false
                    appViewModel.addRide(rideFromPlannedRoute(name, route))
                    exitPlanning()
                },
            )
        }
    }

    deleteDialogRide?.let { ride ->
        AlertDialog(
            onDismissRequest = { deleteDialogRide = null },
            title = { Text("Tour löschen") },
            text = { Text("Soll „${ride.name}“ wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialogRide = null
                        if (navTarget?.rideId == ride.id) stopNavigation()
                        appViewModel.removeRide(ride.id)
                    },
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogRide = null }) { Text("Abbrechen") }
            },
        )
    }
}

/**
 * Was nach einer erteilten Standortfreigabe passieren soll.
 *
 * Bewusst ein Aufzaehlungswert und kein Lambda: So laesst sich die Absicht in
 * `rememberSaveable` legen und ueberlebt einen Neuaufbau der Activity waehrend
 * des System-Dialogs (`NAVIGATE_RIDE` merkt sich die Tour zusaetzlich ueber
 * ihre ID). Enum-Werte sind `Serializable` und damit bundle-faehig.
 */
private enum class PendingAction {
    RECORD,
    LOCATE,
    PLAN_START,
    NAVIGATE_RIDE,
    NAVIGATE_ROUTE,
    GENERATE_ROUTES,
}

/** Was gerade navigiert wird — eine gespeicherte Tour oder die geplante Route. */
private data class NavigationTarget(
    /** ID der Tour, oder `null` bei der geplanten Route. */
    val rideId: String?,
    val label: String,
    val points: List<TrackPoint>,
)

/**
 * Alle Marker der Karte in einer Liste: Wegpunkte (gruen = Start, rot = Ziel,
 * blau dazwischen), Start und Ende der ausgewaehlten Tour, der Suchtreffer und
 * der im Hoehenprofil abgelesene Punkt.
 */
private fun buildMapMarkers(
    planning: Boolean,
    waypoints: List<Waypoint>,
    ride: Ride?,
    searchMarker: Waypoint?,
    hoverPoint: TrackPoint?,
): List<MapMarker> = buildList {
    if (ride != null && ride.points.size >= 2) {
        val first = ride.points.first()
        val last = ride.points.last()
        add(MapMarker(first.lat, first.lon, GravelGreen.toArgb(), radius = 7f))
        add(MapMarker(last.lat, last.lon, RecordRed.toArgb(), radius = 7f))
    }
    if (planning) {
        waypoints.forEachIndexed { index, waypoint ->
            val color = when (index) {
                0 -> GravelGreen
                waypoints.lastIndex -> RecordRed
                else -> RouteBlue
            }
            add(MapMarker(waypoint.lat, waypoint.lon, color.toArgb(), radius = 8f))
        }
    }
    searchMarker?.let { add(MapMarker(it.lat, it.lon, RouteBlue.toArgb(), radius = 9f)) }
    hoverPoint?.let { add(MapMarker(it.lat, it.lon, HoverAmber.toArgb(), radius = 8f)) }
}

/**
 * Baut aus einer geplanten Route eine speicherbare Tour — wie
 * `_savePlannedRoute` in Dart: Distanz und Hoehenmeter kommen vom
 * Routing-Server, alles Uebrige aus [computeStats].
 */
private fun rideFromPlannedRoute(name: String, route: PlannedRoute): Ride {
    val base = computeStats(route.points)
    return Ride(
        id = newRideId(),
        name = name,
        createdAt = System.currentTimeMillis(),
        stats = base.copy(distanceKm = route.distanceKm, ascentM = route.ascentM),
        points = route.points,
    )
}

/** Auswahl des Kartenstils (Port des `_showStyleSheet`-Bottom-Sheets). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapStyleSheet(
    current: MapStyle,
    onSelect: (MapStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = CardPadding)) {
            Text(
                text = "Kartenstil",
                modifier = Modifier.padding(
                    start = CardPadding,
                    end = CardPadding,
                    top = 4.dp,
                    bottom = 8.dp,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            mapStyles.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = style.id == current.id,
                            onClick = { onSelect(style) },
                        )
                        .padding(horizontal = CardPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = style.id == current.id, onClick = { onSelect(style) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(style.label, style = MaterialTheme.typography.bodyLarge)
                        mapStyleSubtitle(style.id)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Namensabfrage (`_askName` im Original). */
@Composable
private fun NameDialog(
    title: String,
    suggestion: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(suggestion) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifEmpty { suggestion }) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Teilt Punkte als GPX ueber das System-Share-Sheet. Dieselbe Mechanik wie in
 * der Tourenliste (Cache-Unterverzeichnis + FileProvider), hier aber fuer eine
 * geplante Route ohne [Ride]-Objekt.
 */
private suspend fun shareGpxFile(context: Context, name: String, points: List<TrackPoint>) {
    val uri = withContext(Dispatchers.IO) {
        // Gemeinsames Aufraeumen mit der Tourenliste: nur alte Exporte fliegen
        // raus, die gerade uebergebene Datei bleibt der Empfaenger-App
        // erhalten (siehe ui/ShareFiles.kt).
        val dir = prepareShareDirectory(context.cacheDir)
        val file = File(dir, "${safeFileName(name)}.gpx")
        file.writeText(buildGpx(name, points), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TITLE, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Route teilen"))
}

private fun newRideId(): String {
    val suffix = Random.nextInt(0x1000000).toString(36)
    return "${System.currentTimeMillis()}-$suffix"
}

/** Trefferradius fuer das Tippen auf einen Wegpunkt (Bildschirmpixel). */
private const val WAYPOINT_TOUCH_RADIUS_PX = 28f

/** Maximale Hoehe des Planungs-Panels (Dart: `_planPanelMaxHeightFactor`). */
private const val PLAN_PANEL_MAX_HEIGHT_FACTOR = 0.55f

private const val MIN_SEARCH_LENGTH = 3
private const val SEARCH_DEBOUNCE_MS = 450L

/** Zoomstufe des einmaligen automatischen Erst-Zooms auf die Position. */
private const val AUTO_LOCATION_ZOOM = 13.0

/**
 * Toleranz (Grad), innerhalb derer die Kamera noch als „am Deutschland-
 * Default" gilt — 0.0 waere zu knapp: MapLibre rundet die Kamera beim
 * Wiederherstellen aus `rememberSaveable` nicht immer bitgenau.
 */
private const val DEFAULT_CAMERA_POSITION_EPSILON = 0.01

/** Toleranz der Zoomstufe fuer denselben Vergleich. */
private const val DEFAULT_CAMERA_ZOOM_EPSILON = 0.05
