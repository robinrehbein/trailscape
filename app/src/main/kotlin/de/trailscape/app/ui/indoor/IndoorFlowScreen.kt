package de.trailscape.app.ui.indoor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.app.indoor.indoorCatching
import de.trailscape.app.indoor.render.WorldSurface
import de.trailscape.app.sensors.bluetoothEnabled
import de.trailscape.app.sensors.hasBluetoothPermissions
import de.trailscape.app.sensors.indoor.IndoorDevicesSection
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.decideToday
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.SectionEyebrow
import de.trailscape.app.ui.components.TwoPaneLayout
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.TabletCardGap
import de.trailscape.app.ui.theme.TabletScreenPadding
import de.trailscape.app.ui.theme.useTwoPaneLayout
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.sessionTitle
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.ShifterEvent
import kotlinx.coroutines.delay

/**
 * # Verdrahtung des Indoor-Ablaufs
 *
 * Bindet das [IndoorViewModel] an die zustandslosen Bildschirme je
 * [IndoorStage]. Das ViewModel haengt am Backstack-Eintrag, in dem der Ablauf
 * steht (der `viewModel()`-Aufruf hier nimmt den Eintrag als Owner): Es
 * ueberlebt die Drehung und endet mit dem Verlassen des Eintrags.
 *
 * ## Drei Arten, den Ablauf zu zeigen ([IndoorFlowMode])
 *  * [IndoorFlowMode.TAB] — die Sicht „Strecken" des Tabs „Indoor"
 *    (`IndoorTabScreen.kt`). Die Startseite traegt den Kopf des Tabs
 *    ([tabHeader]) und hat keinen Zurueck-Pfeil; die Navigationsleiste steht
 *    nur auf der Startseite ([AppViewModel.setIndoorTaskActive]). Nach der
 *    Auswertung geht es zurueck auf die Startseite
 *    ([IndoorViewModel.startOver]) statt aus dem Ablauf heraus.
 *  * [IndoorFlowMode.PUSHED] — gepushte Seite mit vorgewaehlter Strecke
 *    (Einheit des Tages von „Heute", „Indoor fahren" und „In 3D ansehen" aus
 *    dem Tourdetail). [onExit] fuehrt dorthin zurueck, woher man kam.
 *  * [IndoorFlowMode.SETTINGS] — „Einstellungen → Indoor": oeffnet direkt die
 *    Einstellungsseite; ist sie geschlossen, verlaesst [onExit] die Seite.
 *    Der Abgleich „Fahrgefühl einstellen" laeuft darin wie bisher.
 *
 * ## Zurueck
 *  * Startseite: verlaesst den Ablauf ([onExit]); im Tab uebernimmt die
 *    Huelle (zurueck auf „Heute").
 *  * Koppeln, Einstellungen: zurueck zur Startseite.
 *  * Strecke vorbereiten: zurueck zur Startseite ([IndoorViewModel.back]);
 *    ein laufender Weltbau wird dabei abgebrochen.
 *  * Fahren: **nie versehentlich verlassen** — Zurueck haelt die Fahrt an und
 *    fragt: weiterfahren oder beenden (dann folgt die Auswertung, dort wird
 *    gespeichert). Ist der Start gescheitert, geht es zurueck zu „Welt laden".
 *  * Auswertung: ungespeicherte Fahrt → Rueckfrage „Verwerfen?"; sonst raus.
 *    Nach dem Speichern fuehrt „Tour ansehen" ins Tourdetail der neuen Tour
 *    ([AppViewModel.requestRideDetail]).
 *
 * ## Lebenszyklus
 * `ON_STOP` → [IndoorViewModel.onAppBackground] (die Fahrt pausiert, es gibt
 * keinen Vordergrunddienst, docs/indoor.md 11.2 Nr. 4), `ON_START` →
 * [IndoorViewModel.onAppForeground]. Der Bildschirm bleibt waehrend der Fahrt
 * an (`KeepScreenOn` in [IndoorRideScreen]).
 *
 * @param onExit verlaesst den Ablauf (Backstack-Eintrag entfernen); im Tab
 *   ohne Wirkung.
 * @param onOpenDiagnostics oeffnet `indoor/diagnose`.
 * @param onOpenWorlds oeffnet `indoor/welten` (Strecken auf dem Geraet).
 * @param mode siehe oben.
 * @param tabHeader nur [IndoorFlowMode.TAB]: die Segmente des Tabs, direkt
 *   unter der Kopfzeile der Startseite.
 * @param togetherSegment nur [IndoorFlowMode.TAB]: statt der Startseite die
 *   Sicht „Gemeinsam" zeigen (Raum und Herausforderungen, `docs/ux/umbau.md`).
 *   Sie braucht dasselbe [IndoorViewModel] wie die Startseite, deshalb liegt
 *   sie in diesem Ablauf und nicht daneben.
 * @param onOpenGarage oeffnet `indoor/garage` (Fahrer und Rad anpassen) vom
 *   Symbol in der Kopfzeile der Startseite. Nur im Tab gesetzt; `null` (der
 *   gepushte Ablauf) zeichnet kein Symbol.
 * @param onShowRoutes „Gegen … fahren", „Strecke herunterladen" oder „Zu den
 *   Strecken" in dieser Sicht: Der Tab wechselt auf „Strecken".
 * @param roomInvite ein von aussen geoeffneter Einladungslink zu einem Raum;
 *   der Abschnitt „Raum" oeffnet damit „Raum beitreten" und quittiert ueber
 *   [onRoomInviteConsumed]. Verbunden wird erst nach der Bestaetigung dort.
 */
@Composable
fun IndoorFlowScreen(
    appViewModel: AppViewModel,
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWorlds: () -> Unit = {},
    mode: IndoorFlowMode = IndoorFlowMode.PUSHED,
    tabHeader: (@Composable () -> Unit)? = null,
    togetherSegment: Boolean = false,
    onShowRoutes: () -> Unit = {},
    onOpenGarage: (() -> Unit)? = null,
    roomInvite: String? = null,
    onRoomInviteConsumed: () -> Unit = {},
    vmOverride: IndoorViewModel? = null,
) {
    val context = LocalContext.current
    val vm: IndoorViewModel = vmOverride ?: viewModel(
        factory = remember(appViewModel) {
            IndoorViewModel.factory(
                context = context,
                profile = { appViewModel.profile.value },
                plan = { appViewModel.plan.value },
                onRideSaved = appViewModel::onIndoorRideSaved,
                // Dieselbe Tagesentscheidung wie auf „Heute" (decideToday).
                today = {
                    runCatching {
                        decideToday(
                            insights = appViewModel.insights.value,
                            plan = appViewModel.plan.value,
                            rides = appViewModel.rides.value,
                            nowMs = System.currentTimeMillis(),
                            texts = AppServices.coreTexts(),
                        ).route
                    }.getOrNull()
                },
            )
        },
    )
    // Nach einer Drehung ist es dieselbe AppViewModel-Instanz; trotzdem immer
    // auf die aktuelle zeigen, damit nie eine alte Huelle benachrichtigt wird.
    SideEffect {
        vm.onRideSaved = appViewModel::onIndoorRideSaved
        // „Runde planen" der Einheit des Tages: Routensuche auf der Karte mit
        // dem Ziel der Einheit; der Indoor-Ablauf wird dafuer verlassen.
        vm.onPlanRoute = { target ->
            onExit()
            appViewModel.requestRouteGeneration(target)
        }
    }
    val (bluetoothOn, permissionGranted) = rememberBluetoothState()
    LaunchedEffect(vm, bluetoothOn, permissionGranted) {
        vm.updateBluetoothState(bluetoothOn, permissionGranted)
    }

    // Die Vorwahlen gelten nur dem gepushten Ablauf: Der Tab und die
    // Einstellungsseite duerfen sie nicht wegnehmen.
    if (mode == IndoorFlowMode.PUSHED) {
        // Von „Heute" gewaehlter Vorschlag der Einheit des Tages — startet direkt.
        LaunchedEffect(vm) { appViewModel.consumeIndoorDayPick()?.let(vm::startDaySuggestion) }
        // „Indoor fahren" aus der Tourdetailansicht: diese Strecke vorauswaehlen.
        LaunchedEffect(vm) { appViewModel.consumeIndoorRidePick()?.let(vm::preferRide) }
        // „In 3D ansehen" im Tourdetail: direkt in die Vorschau (Idee 4, docs/indoor/vorschau.md).
        LaunchedEffect(vm) { appViewModel.consumeIndoorPreviewPick()?.let(vm::openPreview) }
    }
    // „Einstellungen → Indoor": gleich die Einstellungsseite, noch bevor die
    // Stufe unten zum ersten Mal gelesen wird (sonst blitzte die Startseite
    // auf). Nach einer Drehung steht die Stufe schon richtig.
    remember(vm) {
        if (mode == IndoorFlowMode.SETTINGS && vm.stage.value == IndoorStage.ROUTE) vm.openSettings()
        true
    }
    // Die Startseite ist der Anfang: Strecken einmal lesen (nach einer Drehung nicht erneut).
    // Zum Ansehen geoeffnet (Vorschau): keine Startseite, also auch keine Streckenliste und kein Katalogabruf.
    // Im Tab lebt das ViewModel ueber Tab-Wechsel hinweg: bei jedem Wiedereintritt
    // Einstellungen und Strecken neu lesen ([IndoorViewModel.refreshHome]).
    LaunchedEffect(vm) {
        if (vm.previewMode) return@LaunchedEffect
        if (mode == IndoorFlowMode.TAB) vm.refreshHome() else vm.ensureRouteOptions()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onAppBackground()
                Lifecycle.Event.ON_START -> vm.onAppForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val stage by vm.stage.collectAsStateWithLifecycle()
    // Im Tab steht die Navigationsleiste nur auf der Startseite; jede andere
    // Stufe braucht den ganzen Bildschirm.
    if (mode == IndoorFlowMode.TAB) {
        val taskActive = stage != IndoorStage.ROUTE
        DisposableEffect(taskActive) {
            appViewModel.setIndoorTaskActive(taskActive)
            onDispose { appViewModel.setIndoorTaskActive(false) }
        }
    }
    // Nach der Auswertung: im Tab zurueck auf die Startseite, sonst raus.
    val leaveSummary: () -> Unit = if (mode == IndoorFlowMode.TAB) {
        { vm.startOver() }
    } else {
        onExit
    }
    when (stage) {
        IndoorStage.ROUTE -> when (mode) {
            IndoorFlowMode.TAB -> if (togetherSegment) {
                TogetherStage(
                    vm = vm,
                    onOpenSettings = { appViewModel.requestTab(AppTab.MORE) },
                    title = stringResource(R.string.indoor_app_tab_title),
                    tabHeader = tabHeader,
                    onShowRoutes = onShowRoutes,
                    roomInvite = roomInvite,
                    onRoomInviteConsumed = onRoomInviteConsumed,
                )
            } else {
                HomeStage(
                    vm = vm,
                    bluetoothOn = bluetoothOn,
                    permissionGranted = permissionGranted,
                    onExit = null,
                    onOpenSettings = { appViewModel.requestTab(AppTab.MORE) },
                    title = stringResource(R.string.indoor_app_tab_title),
                    tabHeader = tabHeader,
                    onOpenGarage = onOpenGarage,
                )
            }
            IndoorFlowMode.PUSHED -> HomeStage(
                vm = vm,
                bluetoothOn = bluetoothOn,
                permissionGranted = permissionGranted,
                onExit = onExit,
                onOpenSettings = vm::openSettings,
            )
            // Die Einstellungsseite ist geschlossen: zurueck in die Einstellungen der App.
            IndoorFlowMode.SETTINGS -> {
                // Leere Flaeche in Hintergrundfarbe, solange die Seite hinausfaehrt.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                LaunchedEffect(Unit) { onExit() }
            }
        }
        IndoorStage.PAIR -> PairStage(vm)
        IndoorStage.SETTINGS -> SettingsStage(vm, onOpenWorlds, onOpenDiagnostics)
        IndoorStage.WORLD -> if (vm.previewMode) PreviewWorldStage(vm, onExit) else WorldStage(vm)
        IndoorStage.RIDE -> RideStage(vm)
        IndoorStage.SUMMARY -> SummaryStage(
            vm = vm,
            onExit = leaveSummary,
            onOpenTour = { rideId ->
                leaveSummary()
                appViewModel.requestRideDetail(rideId)
            },
        )
        IndoorStage.PREVIEW -> PreviewStage(vm, onExit)
    }
}

/** Wie der Indoor-Ablauf gezeigt wird (siehe [IndoorFlowScreen]). */
enum class IndoorFlowMode {
    /** Sicht „Strecken" des Tabs „Indoor". */
    TAB,

    /** Gepushte Seite mit vorgewaehlter Strecke; Zurueck fuehrt zum Ausgangspunkt. */
    PUSHED,

    /** „Einstellungen → Indoor". */
    SETTINGS,
}

/**
 * „Strecke vorbereiten" vor der Vorschau: derselbe Bildschirm wie vor einer
 * Fahrt (Voranschlag, Datenmenge, Fortschritt), ohne Server-Bau; statt
 * „Ohne 3D-Ansicht fahren" steht „Nur Höhenprofil ansehen". Zurueck verlaesst
 * den Ablauf (die Vorschau kam aus dem Tourdetail).
 */
@Composable
private fun PreviewWorldStage(vm: IndoorViewModel, onExit: () -> Unit) {
    val ui by vm.world.collectAsStateWithLifecycle()
    val leave = { if (!vm.back()) onExit() }
    BackHandler {
        if (ui is IndoorWorldUi.Building) vm.cancelWorldBuild() else leave()
    }
    IndoorWorldScreen(
        ui = ui,
        onLoad = vm::loadWorld,
        onCancel = vm::cancelWorldBuild,
        onRideWithoutWorld = { vm.startPreview(withWorld = false) },
        onRetry = vm::retryWorld,
        onBack = leave,
        onContinue = { vm.startPreview(withWorld = true) },
        withoutWorldLabel = stringResource(R.string.idee04_world_without_action),
        continueLabel = stringResource(R.string.idee04_world_continue_action),
    )
}

/** Die 3D-Vorschau selbst; Zurueck schliesst sie und verlaesst den Ablauf. */
@Composable
private fun PreviewStage(vm: IndoorViewModel, onExit: () -> Unit) {
    val preview by vm.preview.collectAsStateWithLifecycle()
    val renderer by vm.renderer.collectAsStateWithLifecycle()
    val leave = { if (!vm.back()) onExit() }
    BackHandler { leave() }
    val ui = preview
    if (ui == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    IndoorPreviewScreen(
        ui = ui,
        worldSurface = { modifier ->
            renderer?.let { r -> WorldSurface(renderer = r, modifier = modifier) }
        },
        onBack = leave,
        onTogglePlay = { vm.previewTogglePlay() },
        onCycleSpeed = { vm.previewCycleSpeed() },
        onSeek = { vm.previewSeek(it) },
        onCameraChange = { vm.previewSetCamera(it) },
    )
}

@Composable
private fun PairStage(vm: IndoorViewModel) {
    val ui by vm.pair.collectAsStateWithLifecycle()
    val fromRideFlow by vm.pairFromRideFlow.collectAsStateWithLifecycle()
    val trainerReady = indoorTrainerIssue(ui, ui.bluetoothOn, ui.permissionGranted) == null
    BackHandler { vm.closeSubPage() }
    IndoorPairScreen(
        ui = ui,
        devicesSection = { IndoorDevicesSection() },
        onDemoChange = vm::setDemoTrainer,
        onDone = if (fromRideFlow && trainerReady) vm::continueFromPair else vm::closeSubPage,
        onBack = vm::closeSubPage,
    )
}

@Composable
private fun SettingsStage(vm: IndoorViewModel, onOpenWorlds: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val ui by vm.pair.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val feel by vm.feel.collectAsStateWithLifecycle()
    // Unterseite Fahrprotokolle (kein eigener Ablaufschritt: bleibt in den Einstellungen).
    var showProtocols by rememberSaveable { mutableStateOf(false) }
    if (showProtocols) {
        ProtocolsPage(vm, onBack = { showProtocols = false })
        return
    }
    BackHandler { vm.closeSubPage() }
    IndoorSettingsScreen(
        ui = ui,
        catalog = catalog,
        onChainringChange = vm::setChainringTeeth,
        onGearTableChange = vm::setGearTable,
        onTimeOfDayChange = vm::setTimeOfDay,
        onOtherRidersChange = vm::setOtherRiders,
        onTrailOverlaysChange = vm::setTrailOverlays,
        onGraphicsChange = vm::setGraphics,
        onOpenWorlds = onOpenWorlds,
        onRefreshCatalog = vm::refreshCatalog,
        onOpenDiagnostics = onOpenDiagnostics,
        onBack = vm::closeSubPage,
        feel = feel,
        onResistanceChange = vm::setFeelResistance,
        onSurfaceStrengthChange = vm::setFeelSurfaceStrength,
        onResetFeel = vm::resetFeel,
        onStartCalibration = vm::startCalibration,
        calibrationNeedsTrainer = ui.demoTrainer || ui.trainerName == null,
        onOpenProtocols = if (vm.protocols != null) ({ showProtocols = true }) else null,
    )
}

/** Liste und Ansicht der Fahrprotokolle; liest und löscht Dateien im Hintergrund-unkritischen Umfang (klein, höchstens 12). */
@Composable
private fun ProtocolsPage(vm: IndoorViewModel, onBack: () -> Unit) {
    val store = vm.protocols ?: run { onBack(); return }
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var openedName by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = remember(version) { store.list() }
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    val rows = entries.map { ProtocolRowUi(it.file.name, format.format(java.util.Date(it.modifiedMs)), ((it.sizeBytes + 1023) / 1024).toInt()) }
    val opened = entries.firstOrNull { it.file.name == openedName }
    val openedText = remember(opened) { opened?.let { store.read(it) } }
    BackHandler { if (openedName != null) openedName = null else onBack() }
    IndoorProtocolScreen(
        rows = rows,
        openedId = opened?.file?.name,
        openedText = openedText,
        onOpen = { openedName = it },
        onShare = { id -> entries.firstOrNull { it.file.name == id }?.let { e -> runCatching { shareProtocol(context, e) } } },
        onDelete = { id ->
            entries.firstOrNull { it.file.name == id }?.let { store.delete(it) }
            openedName = null
            version++
        },
        onDeleteAll = { store.clear(); openedName = null; version++ },
        onCloseOpened = { openedName = null },
        onBack = onBack,
    )
}

/**
 * Ob Bluetooth an ist und die Berechtigung erteilt — beides kann sich in den
 * Systemeinstellungen aendern, ohne dass die Seite davon hoert; deshalb
 * sekundenweise nachgefragt, solange die Seite sichtbar ist (wie der
 * Geraeteabschnitt von indoor-ble).
 */
@Composable
private fun rememberBluetoothState(): Pair<Boolean, Boolean> {
    val context = LocalContext.current
    var on by remember { mutableStateOf(bluetoothEnabled(context)) }
    var granted by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                on = bluetoothEnabled(context)
                granted = hasBluetoothPermissions(context)
                delay(1_000)
            }
        }
    }
    return on to granted
}

/**
 * @param onExit `null` im Tab: kein Zurueck-Pfeil, und die Zurueck-Taste
 *   gehoert der Huelle.
 */
@Composable
private fun HomeStage(
    vm: IndoorViewModel,
    bluetoothOn: Boolean,
    permissionGranted: Boolean,
    onExit: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    title: String = stringResource(R.string.indoor_app_route_title),
    tabHeader: (@Composable () -> Unit)? = null,
    onOpenGarage: (() -> Unit)? = null,
) {
    val ui by vm.route.collectAsStateWithLifecycle()
    val pair by vm.pair.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val catalogExtras by vm.catalogExtras.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    BackHandler(enabled = onExit != null) { onExit?.invoke() }
    // Eine geladene Strecke ändert, ob eine Herausforderung fahrbar ist (Mitfahrer-Pille).
    LaunchedEffect(catalog) { vm.refreshChallenges() }
    IndoorHomeScreen(
        route = ui,
        pair = pair,
        trainerIssue = indoorTrainerIssue(pair, bluetoothOn, permissionGranted),
        day = day,
        onDayRide = { vm.rideDaySuggestion() },
        onDayNext = vm::nextDaySuggestion,
        onDayGenerated = vm::rideDayGenerated,
        onDayPath = vm::setDayPath,
        onSelect = vm::selectRoute,
        onModeChange = vm::setMode,
        onGhostChange = vm::setGhost,
        onRide = vm::continueFromRoute,
        onBack = onExit,
        onOpenPair = vm::openPair,
        onOpenSettings = onOpenSettings,
        title = title,
        headerExtra = tabHeader,
        catalog = catalog,
        onCatalogDownload = vm::downloadCatalog,
        onCatalogCancel = vm::cancelCatalogDownload,
        onCatalogDiscard = vm::discardCatalogDownload,
        catalogExtras = catalogExtras,
        onOpenGarage = onOpenGarage,
        onLoadPreview = vm::routePreviewPoints,
    )
}

/**
 * Die Sicht „Gemeinsam" des Indoor-Tabs (`docs/ux/umbau.md`): zwei Abschnitte
 * unter derselben Kopfzeile und denselben Segmenten wie „Strecken".
 *
 *  * **Raum** (`docs/raum-protokoll.md`): [IndoorRoomSection]. „Zum Start"
 *    wählt die Strecke des Raums im [IndoorViewModel] und geht weiter zur
 *    Fahrt; fehlt dafür noch etwas (kein Trainer gekoppelt), wechselt der Tab
 *    auf „Strecken", wo es steht.
 *  * **Herausforderungen** (`docs/herausforderung.md`): gespeicherte
 *    Herausforderungen und „Datei wählen". „Gegen … fahren" setzt Strecke und
 *    Mitfahrer und wechselt auf „Strecken"; ebenso „Strecke herunterladen",
 *    weil Voranschlag und Fortschritt des Downloads dort stehen.
 */
@Composable
private fun TogetherStage(
    vm: IndoorViewModel,
    onOpenSettings: () -> Unit,
    title: String,
    tabHeader: (@Composable () -> Unit)?,
    onShowRoutes: () -> Unit,
    roomInvite: String?,
    onRoomInviteConsumed: () -> Unit,
) {
    val ui by vm.route.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val challenges by vm.challenges.collectAsStateWithLifecycle()
    val challengeImport by vm.challengeImport.collectAsStateWithLifecycle()
    val room by vm.room.collectAsStateWithLifecycle()
    val roomServer by vm.roomServer.collectAsStateWithLifecycle()
    // Eine geladene Strecke ändert, ob eine Herausforderung fahrbar ist.
    LaunchedEffect(catalog) { vm.refreshChallenges() }
    // Der eigene Server kann sich in den Einstellungen geändert haben (ohne Netz gelesen).
    LaunchedEffect(Unit) { vm.refreshRoomServer() }
    // Die unter „Strecken" gewählte Strecke, falls sie sich für einen Raum eignet.
    val createRoute = remember(ui.selected, catalog) { vm.roomRouteOfSelection() }
    ChallengeImportDialog(
        ui = challengeImport,
        onSave = vm::confirmChallengeImport,
        onDismiss = vm::dismissChallengeImport,
    )
    val roomTitle = stringResource(R.string.raum_section_title)
    val challengesTitle = stringResource(R.string.indoor_app_tab_segment_challenges)
    IndoorTogetherScreen(
        title = title,
        tabHeader = tabHeader,
        onOpenSettings = onOpenSettings,
        roomBlock = {
            SectionEyebrow(roomTitle)
            IndoorRoomSection(
                ui = room,
                server = roomServer,
                createRoute = createRoute,
                savedName = remember(room is IndoorRoomUi.Closed) { vm.roomSavedName() },
                savedSharePower = remember(room is IndoorRoomUi.Closed) { vm.roomSavedSharePower() },
                pendingInvite = roomInvite,
                onInviteConsumed = onRoomInviteConsumed,
                onCreate = vm::createRoom,
                onJoin = vm::joinRoom,
                onLeave = vm::leaveRoom,
                onEnd = vm::endRoom,
                onStartCountdown = vm::startRoomCountdown,
                onSharePower = vm::setRoomSharePower,
                onRide = { routeName -> if (!vm.rideRoomRoute(routeName)) onShowRoutes() },
                onDownload = { id ->
                    vm.downloadCatalog(id)
                    onShowRoutes()
                },
                onDismissNotice = vm::dismissRoomNotice,
                onOpenSettings = onOpenSettings,
                onChooseRoute = onShowRoutes,
            )
        },
        challengeBlock = {
            SectionEyebrow(challengesTitle)
            IndoorChallengeSection(
                ui = IndoorChallengesUi(
                    items = challenges,
                    selectedId = (ui.ghost as? IndoorGhostOption.Challenge)?.challengeId,
                ),
                onRide = { id, routeName ->
                    vm.useChallenge(id, routeName)
                    onShowRoutes()
                },
                onDownload = { id ->
                    vm.downloadCatalog(id)
                    onShowRoutes()
                },
                onDelete = vm::deleteChallenge,
                onImport = vm::importChallengeFile,
            )
        },
    )
}

@Composable
internal fun IndoorTogetherScreen(
    title: String,
    tabHeader: (@Composable () -> Unit)?,
    onOpenSettings: () -> Unit,
    roomBlock: @Composable () -> Unit,
    challengeBlock: @Composable () -> Unit,
) {
    if (useTwoPaneLayout(LocalWindowLayoutClass.current)) {
        val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomSpace = maxOf(LocalFloatingNavigationBarSpace.current, systemBottom)
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(bottom = bottomSpace)
                    .padding(horizontal = TabletScreenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader(
                    title = title,
                    onBack = null,
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.indoor_app_home_settings_cd))
                        }
                    },
                )
                tabHeader?.invoke()
                TwoPaneLayout(
                    primaryWeight = 0.5f,
                    gap = TabletCardGap,
                    modifier = Modifier.weight(1f),
                    primary = {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            roomBlock()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    },
                    secondary = {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            challengeBlock()
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    },
                )
            }
        }
    } else {
        IndoorPage(
            title = title,
            onBack = null,
            headerExtra = tabHeader,
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.indoor_app_home_settings_cd))
                }
            },
        ) {
            roomBlock()
            challengeBlock()
        }
    }
}

@Composable
private fun WorldStage(vm: IndoorViewModel) {
    val ui by vm.world.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    // Waehrend des Baus bricht Zurueck ihn ab (wie „Abbrechen"), sonst ein
    // Schritt zurueck zur Streckenwahl.
    BackHandler {
        if (ui is IndoorWorldUi.Building) vm.cancelWorldBuild() else vm.back()
    }
    IndoorWorldScreen(
        ui = ui,
        onLoad = vm::loadWorld,
        onCancel = vm::cancelWorldBuild,
        onRideWithoutWorld = { vm.startRide(withWorld = false) },
        onRetry = vm::retryWorld,
        onBack = { vm.back() },
        onContinue = { vm.startRide(withWorld = true) },
        server = server,
        onServerBuild = vm::buildOnServer,
        onServerCancel = vm::cancelServerBuild,
        onServerRetry = vm::retryServerBuild,
    )
}

@Composable
private fun RideStage(vm: IndoorViewModel) {
    // Geführter Abgleich „Fahrgefühl einstellen“: eigene Seite statt Fahransicht.
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    calibration?.let { cal ->
        BackHandler { vm.leaveCalibration() }
        IndoorCalibrationScreen(
            ui = cal,
            onGearLess = { vm.shift(ShifterEvent.DOWN) },
            onGearMore = { vm.shift(ShifterEvent.UP) },
            onFinishNow = vm::finishCalibrationNow,
            onAccept = vm::acceptCalibration,
            onLeave = vm::leaveCalibration,
        )
        return
    }
    val ride by vm.ride.collectAsStateWithLifecycle()
    val renderer by vm.renderer.collectAsStateWithLifecycle()
    var askLeave by rememberSaveable { mutableStateOf(false) }
    // Ob die Rueckfrage die Fahrt selbst angehalten hat — nur dann setzt
    // „Weiterfahren" sie fort; eine Pause des Nutzers bleibt bestehen.
    var pausedByAsk by rememberSaveable { mutableStateOf(false) }

    val ui = ride
    val running = ui?.session != null
    BackHandler {
        if (!running) {
            // Start gescheitert oder noch nicht da: zurueck zu „Welt laden".
            vm.back()
        } else {
            val phase = ui?.session?.phase
            if (phase == IndoorPhase.RUNNING || phase == IndoorPhase.AUTO_PAUSED || phase == IndoorPhase.READY) {
                vm.pauseRide()
                pausedByAsk = true
            }
            askLeave = true
        }
    }

    if (ui == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
    IndoorRideScreen(
        ui = ui,
        worldSurface = { modifier ->
            renderer?.let { r -> WorldSurface(renderer = r, modifier = modifier) }
        },
        onShift = vm::shift,
        onTogglePause = {
            val phase = ui.session?.phase
            if (phase == IndoorPhase.PAUSED) vm.resumeRide() else vm.pauseRide()
        },
        onFinish = vm::finishRide,
        onCameraChange = vm::setCamera,
        onDemoPower = vm::setDemoPower,
        onDemoCadence = vm::setDemoCadence,
        onErgAdjust = vm::adjustErg,
        onDismissNotice = vm::dismissNotice,
        onRideWithoutWorld = null,
        onBack = { vm.back() },
        onMoveStart = vm::moveStart,
        onNudgeStart = vm::nudgeStart,
        onReleaseStart = vm::releaseStart,
        onDismissDayEnd = vm::dismissDayEnd,
    )
        // Raum fuer Gruppenfahrten: vor dem gemeinsamen Start liegt die Karte
        // „Warten auf den Start" ueber der gehaltenen Fahrt (IndoorRoomHud.kt).
        ui.room?.takeIf { it.waiting && !it.closed && ui.session != null }?.let { room ->
            RoomStartCard(
                room = room,
                onStart = vm::startRoomCountdown,
                onLeave = vm::leaveRoom,
                modifier = Modifier
                    .align(Alignment.Center)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(24.dp),
            )
        }
    }

    if (askLeave) {
        val dismiss = {
            askLeave = false
            if (pausedByAsk) vm.resumeRide()
            pausedByAsk = false
        }
        OneUiDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.indoor_app_flow_leave_title)) },
            text = { Text(stringResource(R.string.indoor_app_flow_leave_body)) },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.indoor_app_flow_leave_resume)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askLeave = false
                        pausedByAsk = false
                        vm.finishRide()
                    },
                ) { Text(stringResource(R.string.indoor_app_flow_leave_finish)) }
            },
        )
    }
}

@Composable
private fun SummaryStage(vm: IndoorViewModel, onExit: () -> Unit, onOpenTour: (String) -> Unit) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    var askDiscard by rememberSaveable { mutableStateOf(false) }
    val ui = summary
    val unsaved = ui != null && !ui.tooShort && ui.savedRideId == null
    BackHandler {
        if (unsaved) askDiscard = true else onExit()
    }
    if (ui == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    IndoorSummaryScreen(
        ui = ui,
        onSave = vm::saveRide,
        onDiscard = {
            vm.discardRide()
            onExit()
        },
        onDone = onExit,
        onOpenTour = ui.savedRideId?.let { rideId -> { onOpenTour(rideId) } },
    )
    if (askDiscard) {
        OneUiDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.indoor_app_summary_discard_confirm_title)) },
            text = { Text(stringResource(R.string.indoor_app_summary_discard_confirm_body)) },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) {
                    Text(stringResource(R.string.indoor_app_action_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askDiscard = false
                        vm.discardRide()
                        onExit()
                    },
                ) { Text(stringResource(R.string.indoor_app_summary_discard_action)) }
            },
        )
    }
}

/**
 * Die Weltverwaltung (Ziel `indoor/welten`, docs/indoor.md 10.4): bindet das
 * [IndoorWorldsViewModel] an [IndoorWorldsScreen] (die Liste liest das
 * ViewModel beim Anlegen).
 */
@Composable
fun IndoorWorldsDestination(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: IndoorWorldsViewModel = viewModel(factory = remember { IndoorWorldsViewModel.factory(context) })
    val ui by vm.state.collectAsStateWithLifecycle()
    IndoorWorldsScreen(
        ui = ui,
        onDelete = vm::delete,
        onClearCache = vm::clearCache,
        onBack = onBack,
    )
}

/**
 * Titel der Einheit des Tages, wenn sie sich als ERG-Einheit auf dem
 * Rollentrainer fahren laesst — sonst `null` (kein Plan, Ruhetag, Termin, oder
 * der Kern-Einstieg ist in dieser Version noch nicht fertig). Fuer die
 * Einstiegskarte auf „Heute"; wirft nie.
 */
fun indoorErgTitleFor(parts: IndoorParts, session: TrainingSession?, texts: CoreTexts): String? {
    if (session == null || session.isEvent) return null
    val title = runCatching { sessionTitle(session, texts) }.getOrElse { session.title }
    return indoorCatching(IndoorPart.CORE) { parts.ergWorkout(session, title) }.getOrNull()?.title
}
