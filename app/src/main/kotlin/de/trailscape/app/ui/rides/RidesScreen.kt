package de.trailscape.app.ui.rides

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MoreSection
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiSearchField
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.rememberActivityImportAction
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import kotlinx.coroutines.Job

/**
 * # Der Verlauf-Tab — was bin ich gefahren?
 *
 * Dritter Platz der Navigationskapsel. Zieldesign
 * `docs/design/prototyp-klartext.html`, Screen `#s-verlauf` (Listenansicht)
 * und `NOTES.verlauf`: Touren nach Monaten gruppiert, je mit Mini-Karte und
 * einem Wort fuer die Haerte statt „TL 68"; Suche und Import oben rechts neben
 * dem Zahnrad statt nur im Leerzustand oder tief in den Einstellungen.
 *
 * ## Titel im Inhalt statt einklappender Kopfzeile
 * Hier stand eine `OneUiLargeTopAppBar`, ausgeklappt rund 40 % der
 * Bildschirmhoehe — fuer eine Liste, die man oeffnet, um etwas zu finden,
 * verschenkter Platz. Wie auf „Heute" steht der Titel jetzt als Inhalt oben
 * in der Liste ([VerlaufHeader]): eine Zeile Symbolknoepfe, darunter
 * „Verlauf" in `headlineLarge`. Beides scrollt mit weg.
 *
 * ## Die Detailansicht liegt in einem eigenen Fenster
 * Nur ein eigenes `Dialog`-Fenster deckt auch die schwebende
 * Navigationskapsel ab, die in `TrailscapeApp.kt` als Geschwister **ueber**
 * dem gesamten `NavHost` liegt. Das Fenster faengt zugleich die
 * Systemzurueckgeste ab — die erste Geste schliesst das Detail, nicht den Tab.
 *
 * ## Meldungen und „Rückgängig"
 * [AppViewModel.messages] sammelt dieser Screen ein (Import-Erfolg, erkannte
 * Dublette). Auch die „Rückgängig"-Snackbar nach dem Loeschen steht hier und
 * nicht im Detail: Das Detail schliesst sich mit dem Loeschen, und mit seinem
 * Fenster verschwaende auch eine Snackbar darin (Begruendung bei
 * [RideDetailHost]).
 */
@Composable
fun RidesScreen(appViewModel: AppViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    val scope = rememberCoroutineScope()
    var undoJob by remember { mutableStateOf<Job?>(null) }

    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    // Die geoeffnete Tour als ID, nicht als `Ride`: Nach einem Umbenennen oder
    // HF-Merge liefert `appViewModel.rides` ein neues Objekt, ueber die ID
    // zeigt die Ansicht immer auf den aktuellen Stand.
    var detailRideId by rememberSaveable { mutableStateOf<String?>(null) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    fun closeSearch() {
        searchOpen = false
        query = ""
    }
    BackHandler(enabled = searchOpen && detailRideId == null) { closeSearch() }

    // Der Einzelimport (GPX/FIT) samt SAF-Launcher und Fehlerdialog — die
    // geteilte Aktion aus `ui/ActivityImportAction.kt`.
    val importAction = rememberActivityImportAction(appViewModel)
    // Der Archiv-Import (ZIP mit Fortschritts- und Ergebnisdialog) wohnt in
    // Einstellungen → Daten & Backup; der Sprung dorthin ist der eine Weg,
    // ihn nicht ein zweites Mal zu verdrahten.
    val importArchive = { appViewModel.requestMoreSection(MoreSection.BACKUP) }

    // Von der Startseite („Letzte Tour") angeforderte Detailansicht. Erst
    // quittieren, wenn die Tour wirklich in [rides] vorliegt, sonst ginge eine
    // Anfrage kurz nach dem Kaltstart (Liste noch leer) spurlos verloren.
    val pendingRideDetailRequest by appViewModel.pendingRideDetail.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRideDetailRequest, rides) {
        val wanted = pendingRideDetailRequest ?: return@LaunchedEffect
        if (rides.any { it.id == wanted }) {
            detailRideId = wanted
            appViewModel.consumeRideDetailRequest()
        }
    }

    // Verschwindet die geoeffnete Tour aus der Liste (Sync, Loeschen), schliesst
    // sich die Ansicht von selbst.
    LaunchedEffect(rides) {
        if (detailRideId != null && rides.none { it.id == detailRideId }) {
            detailRideId = null
        }
    }

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest — hier duerfen sie nicht noch einmal aufschlagen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Ohne dieses Padding erschiene die Meldung hinter der schwebenden
            // Navigationskapsel.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalFloatingNavigationBarSpace.current),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            TourListContent(
                appViewModel = appViewModel,
                query = if (searchOpen) query else "",
                onOpenDetail = { detailRideId = it },
                onRecord = { appViewModel.requestRecording() },
                onImportFile = importAction.start,
                onImportArchive = importArchive,
                modifier = Modifier.fillMaxSize(),
                contentPadding = screenContentPadding(),
                header = {
                    item(key = "kopf") {
                        VerlaufHeader(
                            searchOpen = searchOpen,
                            query = query,
                            onQueryChange = { query = it },
                            onToggleSearch = {
                                if (searchOpen) closeSearch() else searchOpen = true
                            },
                            onImportFile = importAction.start,
                            onImportArchive = importArchive,
                            onOpenSettings = { appViewModel.requestTab(AppTab.MORE) },
                            onShowMap = appViewModel::requestHistoryMap,
                        )
                    }
                },
            )
        }
    }

    detailRideId?.let { id ->
        Dialog(
            onDismissRequest = { detailRideId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // `usePlatformDefaultWidth = false` macht dieses Fenster randlos.
            // Anders als im `NavHost` sind die Systemleisten hier NICHT schon
            // aufgeloest; die Detailansicht nimmt das aber an. Dieselbe
            // Aufloesung (oben und seitlich; unten bewusst nicht) wird deshalb
            // hier wiederholt, sonst zeichnet ihr Kopf unter die Statusleiste.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        ),
                ) {
                    RideDetailHost(
                        rideId = id,
                        appViewModel = appViewModel,
                        onBack = { detailRideId = null },
                        onDelete = { rideId ->
                            detailRideId = null
                            undoJob = deleteRideWithUndo(
                                rideId = rideId,
                                appViewModel = appViewModel,
                                scope = scope,
                                snackbarHostState = snackbarHostState,
                                undoJob = undoJob,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Der Kopf des Verlaufs (Zieldesign `.head` + `.title`): rechts oben Suche,
 * Import und Zahnrad, darunter der Titel. Ist die Suche offen, steht das
 * Suchfeld unter dem Titel; die Lupe wird dann zum Schliessen-Knopf.
 */
@Composable
private fun VerlaufHeader(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onImportFile: () -> Unit,
    onImportArchive: () -> Unit,
    onOpenSettings: () -> Unit,
    onShowMap: () -> Unit,
) {
    var importMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ScreenHeader(
            title = "Verlauf",
            actions = {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = if (searchOpen) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                        contentDescription = if (searchOpen) "Suche schließen" else "Touren suchen",
                    )
                }
                Box {
                    IconButton(onClick = { importMenuOpen = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Touren importieren")
                    }
                    ImportMenu(
                        expanded = importMenuOpen,
                        onDismiss = { importMenuOpen = false },
                        onImportFile = onImportFile,
                        onImportArchive = onImportArchive,
                    )
                }
                SettingsAction(onClick = onOpenSettings)
            },
        )
        // „Liste | Karte": Die Karte aller Spuren wohnt im Karten-Tab (echte
        // Grundkarte, Kacheln); „Karte" wechselt dorthin, ✕ kommt zurueck.
        PillSegments(
            options = listOf("Liste", "Karte"),
            selectedIndex = 0,
            onSelect = { if (it == 1) onShowMap() },
            modifier = Modifier.padding(top = 8.dp),
        )
        if (searchOpen) {
            // Wer die Lupe antippt, will tippen: Fokus (und damit Tastatur)
            // gleich ins Feld.
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OneUiSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Tour nach Namen suchen",
                modifier = Modifier
                    .padding(top = CardGap)
                    .focusRequester(focusRequester),
            )
        }
    }
}
