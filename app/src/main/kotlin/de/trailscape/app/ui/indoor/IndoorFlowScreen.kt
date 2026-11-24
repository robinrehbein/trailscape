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
import androidx.lifecycle.viewmodel.compose.viewModel
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorPart
import de.trailscape.app.indoor.IndoorParts
import de.trailscape.app.indoor.indoorCatching
import de.trailscape.app.indoor.render.WorldSurface
import de.trailscape.app.sensors.indoor.IndoorDevicesSection
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.decideToday
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.sessionTitle
import de.trailscape.core.indoor.IndoorPhase

/**
 * # Verdrahtung des Indoor-Ablaufs (Ziel `indoor` im `NavHost`)
 *
 * Bindet das [IndoorViewModel] an die zustandslosen Bildschirme je
 * [IndoorStage]. Das ViewModel haengt am Backstack-Eintrag `indoor` (der
 * `viewModel()`-Aufruf hier nimmt den Eintrag als Owner): Es ueberlebt die
 * Drehung und endet mit dem Verlassen des Ablaufs.
 *
 * ## Zurueck
 *  * Koppeln: verlaesst den Ablauf ([onExit]).
 *  * Strecke, Welt: einen Schritt zurueck ([IndoorViewModel.back]); ein
 *    laufender Weltbau wird dabei abgebrochen.
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
 */
@Composable
fun IndoorFlowScreen(
    appViewModel: AppViewModel,
    onExit: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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
        IndoorStage.PAIR -> PairStage(vm, onExit, onOpenDiagnostics)
        IndoorStage.ROUTE -> RouteStage(vm)
        IndoorStage.WORLD -> WorldStage(vm)
        IndoorStage.RIDE -> RideStage(vm)
        IndoorStage.SUMMARY -> SummaryStage(vm, onExit)
    }
}

@Composable
private fun PairStage(vm: IndoorViewModel, onExit: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val ui by vm.pair.collectAsStateWithLifecycle()
    IndoorPairScreen(
        ui = ui,
        devicesSection = { IndoorDevicesSection() },
        onDemoChange = vm::setDemoTrainer,
        onChainringChange = vm::setChainringTeeth,
        onGearTableChange = vm::setGearTable,
        onTimeOfDayChange = vm::setTimeOfDay,
        onContinue = vm::continueFromPair,
        onBack = onExit,
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

@Composable
private fun RouteStage(vm: IndoorViewModel) {
    val ui by vm.route.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val day by vm.day.collectAsStateWithLifecycle()
    BackHandler { vm.back() }
    IndoorRouteScreen(
        ui = ui,
        day = day,
        onDayRide = { vm.rideDaySuggestion() },
        onDayNext = vm::nextDaySuggestion,
        onSelect = vm::selectRoute,
        onModeChange = vm::setMode,
        onGhostChange = vm::setGhost,
        onContinue = vm::continueFromRoute,
        onBack = { vm.back() },
        catalog = catalog,
        onTabChange = vm::setRouteTab,
        onCatalogDownload = vm::downloadCatalog,
        onCatalogCancel = vm::cancelCatalogDownload,
        onCatalogDiscard = vm::discardCatalogDownload,
        onCatalogRefresh = vm::refreshCatalog,
    )
}

@Composable
private fun WorldStage(vm: IndoorViewModel) {
    val ui by vm.world.collectAsStateWithLifecycle()
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
