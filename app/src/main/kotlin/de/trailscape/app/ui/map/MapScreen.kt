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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.mapStyles
import de.trailscape.app.ui.prepareShareDirectory
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

    // ---------------------------------------------------- Zustand des Screens
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var planning by rememberSaveable { mutableStateOf(false) }
    var waypoints by remember { mutableStateOf<List<Waypoint>>(emptyList()) }
    var plannedRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    var routeProfile by rememberSaveable { mutableStateOf(RouteProfile.GRAVEL) }
    var planBusy by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf<String?>(null) }

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
        if (locationGranted) {
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
    LaunchedEffect(waypoints, routeProfile) {
        if (waypoints.size < 2) {
            plannedRoute = null
            planError = null
            planBusy = false
            return@LaunchedEffect
        }
        planBusy = true
        planError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { fetchRoute(waypoints, brouterProfile(routeProfile), AppServices.httpClient) }
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
                snackbarHostState.showSnackbar("Standortdienste sind deaktiviert.")
                return@launch
            }
            // Erst ein frischer Fix (wie `_goToMyPosition` in Dart), sonst
            // das, was der Standortpunkt der Karte zuletzt gesehen hat.
            val position = currentLocation(context)?.let { it.latitude to it.longitude }
                ?: controller.lastKnownLocation()
            if (position == null) {
                snackbarHostState.showSnackbar("Position konnte nicht ermittelt werden.")
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
                snackbarHostState.showSnackbar("Position konnte nicht ermittelt werden.")
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
                    snackbarHostState.showSnackbar(
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
        val zoomRange = offlineZoomRange(zoom, mapStyle)
        val estimate = estimateTileCount(bounds, zoomRange.first, zoomRange.last)
        if (estimate <= 0) {
            appViewModel.showMessage("Dieser Ausschnitt lässt sich nicht speichern.")
            return
        }
        if (estimate > MAX_TILES_PER_DOWNLOAD) {
            appViewModel.showMessage(
                "Bereich zu groß: ca. $estimate Kacheln (max. $MAX_TILES_PER_DOWNLOAD). " +
                    "Zoome näher heran.",
            )
            return
        }

        // Bewusst nicht in `scope` (der stirbt beim Tab-Wechsel mitsamt dem
        // halbfertigen Download), sondern im App-Scope; die Abschlussmeldung
        // kommt ueber den geteilten Meldungskanal zurueck.
        OfflineDownloadController.start(
            context = context,
            style = mapStyle,
            bounds = bounds,
            minZoom = zoomRange.first,
            maxZoom = zoomRange.last,
            name = "${mapStyle.label} · ${formatToday()}",
            estimatedTiles = estimate,
            onMessage = appViewModel::showMessage,
        )
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
            PendingAction.NAVIGATE_RIDE -> {
                val rideId = pendingNavigateRideId
                pendingNavigateRideId = null
                rides.firstOrNull { it.id == rideId }?.let { runNavigateRide(it) }
            }
        }
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
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                    appViewModel.select(null)
                                    planning = true
                                    waypoints = emptyList()
                                    plannedRoute = null
                                    planError = null
                                }
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    MapCircleButton(
                        icon = Icons.Filled.Search,
                        contentDescription = "Ort suchen",
                        active = searchOpen,
                        onClick = { searchOpen = !searchOpen },
                    )
                    Spacer(Modifier.width(8.dp))
                    MapCircleButton(
                        icon = Icons.AutoMirrored.Filled.List,
                        contentDescription = "Kartenstil",
                        onClick = { showStyleSheet = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    MapCircleButton(
                        icon = Icons.Filled.KeyboardArrowDown,
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

                if (planning) {
                    PlanningCard(
                        profile = routeProfile,
                        onProfileChange = { routeProfile = it },
                        waypointCount = waypoints.size,
                        route = plannedRoute,
                        busy = planBusy,
                        error = planError,
                        maxHeight = screenHeight * PLAN_PANEL_MAX_HEIGHT_FACTOR,
                        onUseMyPosition = ::useMyPositionAsStart,
                        onUndo = { waypoints = waypoints.dropLast(1) },
                        onClear = {
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
                    .fillMaxWidth()
                    .padding(12.dp),
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
                    Text("Löschen", color = RecordRed)
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
private enum class PendingAction { RECORD, LOCATE, PLAN_START, NAVIGATE_RIDE, NAVIGATE_ROUTE }

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
    hoverPoint?.let { add(MapMarker(it.lat, it.lon, Color(0xFFF2A03D).toArgb(), radius = 8f)) }
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
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = "Kartenstil",
                modifier = Modifier.padding(20.dp, 4.dp, 20.dp, 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            mapStyles.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = style.id == current.id,
                            onClick = { onSelect(style) },
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = style.id == current.id, onClick = { onSelect(style) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(style.label, style = MaterialTheme.typography.bodyLarge)
                        styleSubtitle(style.id)?.let {
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

/** Die beiden Untertitel, die auch die Flutter-App zeigte. */
private fun styleSubtitle(id: String): String? = when (id) {
    "voyager" -> "Klar und aufgeräumt (Standard)"
    "cyclosm" -> "Radwege & Wegbeläge hervorgehoben"
    else -> null
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

private val dateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)

private fun formatToday(): String =
    dateFormatter.format(Instant.now().atZone(ZoneId.systemDefault()).toLocalDate())

/** Trefferradius fuer das Tippen auf einen Wegpunkt (Bildschirmpixel). */
private const val WAYPOINT_TOUCH_RADIUS_PX = 28f

/** Maximale Hoehe des Planungs-Panels (Dart: `_planPanelMaxHeightFactor`). */
private const val PLAN_PANEL_MAX_HEIGHT_FACTOR = 0.55f

private const val MIN_SEARCH_LENGTH = 3
private const val SEARCH_DEBOUNCE_MS = 450L
