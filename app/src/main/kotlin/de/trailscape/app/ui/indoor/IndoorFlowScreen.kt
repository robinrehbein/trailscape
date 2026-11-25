package de.trailscape.app.ui.indoor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.decideToday
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.sessionTitle
import de.trailscape.core.indoor.IndoorPhase
import kotlinx.coroutines.delay

/**
 * # Verdrahtung des Indoor-Ablaufs (Ziel `indoor` im `NavHost`)
 *
 * Bindet das [IndoorViewModel] an die zustandslosen Bildschirme je
 * [IndoorStage]. Das ViewModel haengt am Backstack-Eintrag `indoor` (der
 * `viewModel()`-Aufruf hier nimmt den Eintrag als Owner): Es ueberlebt die
 * Drehung und endet mit dem Verlassen des Ablaufs.
 *
 * ## Zurueck
 *  * Startseite: verlaesst den Ablauf ([onExit]).
 *  * Koppeln, Einstellungen: zurueck zur Startseite.
 *  * Strecke vorbereiten: zurueck zur Startseite ([IndoorViewModel.back]);
 *    ein laufender Weltbau wird dabei abgebrochen.
 *  * Fahren: **nie versehentlich verlassen** — Zurueck haelt die Fahrt an und
 *    fragt: weiterfahren oder beenden (dann folgt die Auswertung, dort wird
 *    gespeichert). Ist der Start gescheitert, geht es zurueck zu „Welt laden".
 *  * Auswertung: ungespeicherte Fahrt → Rueckfrage „Verwerfen?"; sonst raus.
 *
 * ## Lebenszyklus
 * `ON_STOP` → [IndoorViewModel.onAppBackground] (die Fahrt pausiert, es gibt
 * keinen Vordergrunddienst, docs/indoor.md 11.2 Nr. 4), `ON_START` →
 * [IndoorViewModel.onAppForeground]. Der Bildschirm bleibt waehrend der Fahrt
 * an (`KeepScreenOn` in [IndoorRideScreen]).
 *
 * @param onExit verlaesst den Ablauf (Backstack-Eintrag entfernen).
 * @param onOpenDiagnostics oeffnet `indoor/diagnose`.
 * @param onOpenWorlds oeffnet `indoor/welten` (Strecken auf dem Geraet).
 */
@Composable
fun IndoorFlowScreen(
    appViewModel: AppViewModel,
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenWorlds: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: IndoorViewModel = viewModel(
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
    // Von „Heute" gewaehlter Vorschlag der Einheit des Tages.
    LaunchedEffect(vm) { appViewModel.consumeIndoorDayPick()?.let(vm::preferDaySuggestion) }
    // „In 3D ansehen" im Tourdetail: direkt in die Vorschau (Idee 4, docs/indoor/vorschau.md).
    LaunchedEffect(vm) { appViewModel.consumeIndoorPreviewPick()?.let(vm::openPreview) }
    // Die Startseite ist der Anfang: Strecken einmal lesen (nach einer Drehung nicht erneut).
    LaunchedEffect(vm) { vm.ensureRouteOptions() }

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
    when (stage) {
        IndoorStage.ROUTE -> HomeStage(vm, onExit)
        IndoorStage.PAIR -> PairStage(vm)
        IndoorStage.SETTINGS -> SettingsStage(vm, onOpenWorlds, onOpenDiagnostics)
        IndoorStage.WORLD -> if (vm.previewMode) PreviewWorldStage(vm, onExit) else WorldStage(vm)
        IndoorStage.RIDE -> RideStage(vm)
        IndoorStage.SUMMARY -> SummaryStage(vm, onExit)
        IndoorStage.PREVIEW -> PreviewStage(vm, onExit)
    }
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
    BackHandler { vm.closeSubPage() }
    IndoorPairScreen(
        ui = ui,
        devicesSection = { IndoorDevicesSection() },
        onDemoChange = vm::setDemoTrainer,
        onDone = vm::closeSubPage,
        onBack = vm::closeSubPage,
    )
}

@Composable
private fun SettingsStage(vm: IndoorViewModel, onOpenWorlds: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val ui by vm.pair.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    BackHandler { vm.closeSubPage() }
    IndoorSettingsScreen(
        ui = ui,
        catalog = catalog,
        onChainringChange = vm::setChainringTeeth,
        onGearTableChange = vm::setGearTable,
        onTimeOfDayChange = vm::setTimeOfDay,
        onOtherRidersChange = vm::setOtherRiders,
        onOpenWorlds = onOpenWorlds,
        onRefreshCatalog = vm::refreshCatalog,
        onOpenDiagnostics = onOpenDiagnostics,
        onBack = vm::closeSubPage,
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

@Composable
private fun HomeStage(vm: IndoorViewModel, onExit: () -> Unit) {
    val ui by vm.route.collectAsStateWithLifecycle()
    val pair by vm.pair.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    val (bluetoothOn, permissionGranted) = rememberBluetoothState()
    BackHandler { onExit() }
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
        onOpenSettings = vm::openSettings,
        catalog = catalog,
        onCatalogDownload = vm::downloadCatalog,
        onCatalogCancel = vm::cancelCatalogDownload,
        onCatalogDiscard = vm::discardCatalogDownload,
    )
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
private fun SummaryStage(vm: IndoorViewModel, onExit: () -> Unit) {
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
