package de.trailscape.app.ui.indoor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.trailscape.app.R
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.map.KeepScreenOn
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.indoor.DayRideEnd
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.ShifterConnection
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.TrainerConnection
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.WorldAttribution

/** ERG: Schritt der Plus/Minus-Knoepfe in Prozentpunkten der FTP. */
private const val ERG_ADJUST_STEP_PCT = 5

/** Hoehe des schmalen Hoehenprofils unten (mit der Welt dahinter). */
private val PROFILE_STRIP_HEIGHT = 56.dp

/**
 * # Schritt 4: Fahren
 *
 * Die 3D-Flaeche fuellt den ganzen Bildschirm, auch unter Status- und
 * Navigationsleiste ([worldSurface] ist ein Slot — der echte `WorldSurface`
 * oder ein Platzhalter im Screenshot-Test). Darueber ein schlankes HUD auf
 * dunklem, auslaufendem Schleier, wie man es von Indoor-Apps kennt:
 *
 *  * oben die Kernwerte — Leistung gross, Tempo, Trittfrequenz, Puls kompakt —,
 *    darunter klein Steigung (farbig) und Belag, der Abstand zum Geist als Chip;
 *  * unten der Gang mit grossen −/+-Tasten am Rand (der Ersatz fuer die
 *    Schalthebel; im ERG-Modus stattdessen die Zielleistung), darunter das
 *    schmale Hoehenprofil mit „x von y km" und der Fahrzeit;
 *  * Kamera, Pause, Beenden (gedrueckt halten) und der Geraetestatus als
 *    kleine runde Symbolknoepfe am rechten Rand (quer: oben rechts).
 *
 * Ein Tipp auf die Welt blendet die Nebensachen aus und wieder ein (Knoepfe,
 * Profil, Geist, Demo-Regler); Kernwerte und Schalten bleiben. Zustandslos bis
 * auf diese Sichtbarkeit und offene Dialoge: Alles andere steckt in [ui].
 *
 * Verhalten bei Fehlern (docs/indoor.md 10.1):
 *  * [IndoorRenderUi.Failed] — verstaendliche Meldung statt der 3D-Flaeche, das
 *    HUD und das Hoehenprofil laufen weiter;
 *  * [IndoorRenderUi.NoWorld] — das grosse Hoehenprofil nimmt den Platz der 3D-Flaeche ein;
 *  * `session == null` mit `startProblem` — Fehlerseite mit „Zurück".
 *
 * @param onShift Schaltwunsch von den Knoepfen (der Taster geht ueber die Laufzeit).
 * @param onErgAdjust Prozentpunkte der FTP, `±5`.
 * @param onRideWithoutWorld wenn gesetzt, bietet die Fehlermeldung der 3D-Flaeche
 *   „Ohne Welt fahren" an; `null` = kein Angebot.
 * @param onMoveStart / [onNudgeStart] / [onReleaseStart] Startpunkt waehlen
 *   ([IndoorStartPanel], solange [IndoorRideUi.start] gesetzt ist): Stelle in
 *   Metern, Feinschritt in Metern, „Losfahren".
 * @param onBack nur fuer die Fehlerseite (Start gescheitert).
 * @param initialChromeHidden nur fuer Screenshot-Tests: Nebensachen schon ausgeblendet.
 */
@Composable
fun IndoorRideScreen(
    ui: IndoorRideUi,
    worldSurface: @Composable (Modifier) -> Unit,
    onShift: (ShifterEvent) -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onCameraChange: (CameraMode) -> Unit,
    onDemoPower: (Int) -> Unit,
    onDemoCadence: (Int) -> Unit,
    onErgAdjust: (Int) -> Unit,
    onDismissNotice: () -> Unit,
    onRideWithoutWorld: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    initialChromeHidden: Boolean = false,
    onMoveStart: (Double) -> Unit = {},
    onNudgeStart: (Double) -> Unit = {},
    onReleaseStart: () -> Unit = {},
    onDismissDayEnd: () -> Unit = {},
) {
    val session = ui.session
    if (session == null) {
        RideStartFailed(ui = ui, onBack = onBack)
        return
    }

    KeepScreenOn()
    LightSystemBarIcons()
    var showDevices by rememberSaveable { mutableStateOf(false) }
    // „Fahrt beenden" in der Pause ist ein einfacher Tipp — deshalb eine
    // Rueckfrage, denn aus der Auswertung fuehrt kein Weg zurueck in die Fahrt.
    // (Der runde Knopf beendet nur gedrueckt gehalten, ohne Rueckfrage.)
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var chromeHidden by rememberSaveable { mutableStateOf(initialChromeHidden) }
    val showWorld = ui.render is IndoorRenderUi.Ready || ui.render is IndoorRenderUi.Loading

    val actions = HudActions(
        onShift = onShift,
        onTogglePause = onTogglePause,
        onFinish = onFinish,
        onCameraChange = onCameraChange,
        onDemoPower = onDemoPower,
        onDemoCadence = onDemoCadence,
        onErgAdjust = onErgAdjust,
        onDismissNotice = onDismissNotice,
        onRideWithoutWorld = onRideWithoutWorld,
        onShowDevices = { showDevices = true },
        onFinishFromPause = { confirmFinish = true },
        onMoveStart = onMoveStart,
        onNudgeStart = onNudgeStart,
        onReleaseStart = onReleaseStart,
        onDismissDayEnd = onDismissDayEnd,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (showWorld) Modifier.background(Color.Black) else Modifier.background(HudColors.backdrop)),
    ) {
        if (showWorld) {
            worldSurface(Modifier.fillMaxSize())
        }
        // Ein Tipp irgendwo auf die Welt (nicht auf ein Bedienelement) blendet
        // die Nebensachen aus bzw. ein. Liegt unter dem HUD: Knoepfe gehen vor.
        val toggleLabel = stringResource(
            if (chromeHidden) R.string.indoor_app_hud_chrome_show_cd else R.string.indoor_app_hud_chrome_hide_cd,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = toggleLabel,
                ) { chromeHidden = !chromeHidden }
                .semantics { contentDescription = toggleLabel },
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth > maxHeight) {
                LandscapeHud(ui, session, showWorld, chromeHidden, actions)
            } else {
                PortraitHud(ui, session, showWorld, chromeHidden, actions)
            }
        }
    }

    if (showDevices) {
        DevicesDialog(ui = ui, onDismiss = { showDevices = false })
    }
    if (confirmFinish) {
        OneUiDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(stringResource(R.string.indoor_app_flow_leave_title)) },
            text = { Text(stringResource(R.string.indoor_app_flow_leave_body)) },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text(stringResource(R.string.indoor_app_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmFinish = false
                        onFinish()
                    },
                ) { Text(stringResource(R.string.indoor_app_flow_leave_finish)) }
            },
        )
    }
}

/** Alle Rueckrufe des HUD gebuendelt, damit die Layouts schlank bleiben. */
private class HudActions(
    val onShift: (ShifterEvent) -> Unit,
    val onTogglePause: () -> Unit,
    val onFinish: () -> Unit,
    val onCameraChange: (CameraMode) -> Unit,
    val onDemoPower: (Int) -> Unit,
    val onDemoCadence: (Int) -> Unit,
    val onErgAdjust: (Int) -> Unit,
    val onDismissNotice: () -> Unit,
    val onRideWithoutWorld: (() -> Unit)?,
    val onShowDevices: () -> Unit,
    /** „Fahrt beenden" auf der Pausen- bzw. Startpunkt-Karte: erst nachfragen. */
    val onFinishFromPause: () -> Unit,
    val onMoveStart: (Double) -> Unit,
    val onNudgeStart: (Double) -> Unit,
    val onReleaseStart: () -> Unit,
    val onDismissDayEnd: () -> Unit,
)

/** Die Startpunkt-Karte, wenn sie gerade gilt (sonst `false`, dann zeigt das HUD Gang und Profil). */
@Composable
private fun StartPanelIfAny(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    actions: HudActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
): Boolean {
    val start = ui.start ?: return false
    val profile = ui.profile ?: return false
    IndoorStartPanel(
        start = start,
        profile = profile,
        phase = session.phase,
        onMove = actions.onMoveStart,
        onNudge = actions.onNudgeStart,
        onRelease = actions.onReleaseStart,
        onResume = actions.onTogglePause,
        onFinish = actions.onFinishFromPause,
        compact = compact,
        modifier = modifier,
    )
    return true
}

/**
 * Helle Symbole in Status- und Navigationsleiste, solange die Fahransicht
 * steht — darunter liegt der dunkle Schleier. Beim Verlassen wie vorher.
 */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val lightStatus = controller?.isAppearanceLightStatusBars
        val lightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (lightStatus != null) controller.isAppearanceLightStatusBars = lightStatus
            if (lightNav != null) controller.isAppearanceLightNavigationBars = lightNav
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

// ---------------------------------------------------------------------------
// Start gescheitert
// ---------------------------------------------------------------------------

@Composable
private fun RideStartFailed(ui: IndoorRideUi, onBack: () -> Unit) {
    IndoorPage(
        title = stringResource(R.string.indoor_app_ride_start_title),
        onBack = onBack,
        bottomBar = {
            if (ui.startProblem != null || ui.runtimeProblem != null) {
                NeutralButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.indoor_app_action_back))
                }
            }
        },
    ) {
        if (ui.startProblem != null) {
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = LocalSignalColors.current.warning,
                title = stringResource(R.string.indoor_app_ride_start_failed_title),
                text = problemText(ui.startProblem),
            )
            MutedText(startFailedHint(ui.startProblem))
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            MutedText(stringResource(R.string.indoor_app_ride_starting))
            // Die Laufzeit laeuft, ein Teil scheitert aber, bevor es einen
            // Sitzungszustand gibt: sichtbar machen statt endlos zu warten.
            ui.runtimeProblem?.let { problem ->
                NoticeBox(
                    icon = Icons.Filled.Warning,
                    color = LocalSignalColors.current.warning,
                    title = stringResource(R.string.indoor_app_ride_start_stuck_title),
                    text = problemText(problem),
                )
                MutedText(stringResource(R.string.indoor_app_ride_start_stuck_hint))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hochformat
// ---------------------------------------------------------------------------

@Composable
private fun PortraitHud(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    showWorld: Boolean,
    chromeHidden: Boolean,
    actions: HudActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Oben: Kernwerte auf dem Schleier, der unter die Statusleiste reicht.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.topScrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HudMetrics(session = session, powerColor = powerColor(session))
            HudGradeLine(session = session)
            TopExtras(ui = ui, session = session, chromeHidden = chromeHidden, actions = actions)
        }

        // Mitte: die Welt (bzw. die Ersatzanzeige), unten rechts die Knoepfe.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp),
        ) {
            if (showWorld) {
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        DemoPanel(ui = ui, chromeHidden = chromeHidden, actions = actions)
                    }
                    ChromeVisibility(visible = !chromeHidden) {
                        HudButtons(ui = ui, session = session, showCamera = true, vertical = true, actions = actions)
                    }
                }
            } else {
                // Ohne Welt: Meldung oben, darunter fuellt das grosse Profil
                // den Platz; die Knoepfe stehen rechts daneben, damit das
                // Profil auch auf kleinen Geraeten Hoehe behaelt.
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RenderFailedCard(ui = ui, actions = actions)
                    Row(modifier = Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BigProfilePanel(ui = ui, session = session, modifier = Modifier.weight(1f))
                            DemoPanel(ui = ui, chromeHidden = chromeHidden, actions = actions)
                        }
                        ChromeVisibility(visible = !chromeHidden, modifier = Modifier.padding(start = 12.dp)) {
                            HudButtons(ui = ui, session = session, showCamera = false, vertical = true, actions = actions)
                        }
                    }
                }
            }
            if (ui.start == null) {
                val cardModifier = if (showWorld) {
                    // Mit Welt oben in der Mitte: frei von der Knopfspalte unten rechts.
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                } else {
                    Modifier.align(Alignment.Center)
                }
                val dayEnd = ui.dayEnd
                if (dayEnd != null) {
                    DayEndCard(dayEnd = dayEnd, actions = actions, modifier = cardModifier)
                } else {
                    PhaseCard(session = session, onTogglePause = actions.onTogglePause, modifier = cardModifier, onFinish = actions.onFinishFromPause)
                }
            }
        }

        // Unten: Gang bzw. Zielleistung, darunter das schmale Hoehenprofil.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.bottomScrim)
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                )
                .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Vor dem ersten Tritt und in der Pause: Startpunkt waehlen statt Gang und Profil.
            if (!StartPanelIfAny(ui = ui, session = session, actions = actions, compact = false)) {
                ShiftRow(ui = ui, session = session, actions = actions) { pill ->
                    pill()
                }
                if (showWorld && ui.profile != null) {
                    ChromeVisibility(visible = !chromeHidden) {
                        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                            HudProfileHeader(ui = ui, session = session)
                            HudProfileCanvas(ui = ui, session = session, height = PROFILE_STRIP_HEIGHT)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Querformat
// ---------------------------------------------------------------------------

@Composable
private fun LandscapeHud(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    showWorld: Boolean,
    chromeHidden: Boolean,
    actions: HudActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Oben links die Kernwerte, oben rechts die Knoepfe in einer Reihe.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.topScrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HudMetrics(session = session, powerColor = powerColor(session), modifier = Modifier.widthIn(max = 520.dp))
                HudGradeLine(session = session)
                TopExtras(ui = ui, session = session, chromeHidden = chromeHidden, actions = actions)
            }
            ChromeVisibility(visible = !chromeHidden, modifier = Modifier.padding(start = 16.dp)) {
                HudButtons(ui = ui, session = session, showCamera = showWorld, vertical = false, actions = actions)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp),
        ) {
            if (showWorld) {
                Box(modifier = Modifier.align(Alignment.BottomStart).widthIn(max = 340.dp)) {
                    DemoPanel(ui = ui, chromeHidden = chromeHidden, actions = actions)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RenderFailedCard(ui = ui, actions = actions)
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        BigProfilePanel(ui = ui, session = session, modifier = Modifier.weight(1f).fillMaxHeight())
                        if (ui.demoPowerW != null || ui.demoCadenceRpm != null) {
                            Box(modifier = Modifier.width(300.dp)) {
                                DemoPanel(ui = ui, chromeHidden = chromeHidden, actions = actions)
                            }
                        }
                    }
                }
            }
            if (ui.start == null) {
                val dayEnd = ui.dayEnd
                if (dayEnd != null) {
                    DayEndCard(dayEnd = dayEnd, actions = actions, modifier = Modifier.align(Alignment.Center))
                } else {
                    PhaseCard(session = session, onTogglePause = actions.onTogglePause, modifier = Modifier.align(Alignment.Center), onFinish = actions.onFinishFromPause)
                }
            }
        }

        // Unten: −/+ an den Raendern, dazwischen Gang und Hoehenprofil.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.bottomScrim)
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                )
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        ) {
            val startShown = StartPanelIfAny(
                ui = ui,
                session = session,
                actions = actions,
                compact = true,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 760.dp),
            )
            if (!startShown) ShiftRow(ui = ui, session = session, actions = actions) { pill ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pill()
                    if (showWorld && ui.profile != null) {
                        ChromeVisibility(visible = !chromeHidden) {
                            Column {
                                HudProfileHeader(ui = ui, session = session)
                                HudProfileCanvas(ui = ui, session = session, height = 44.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Gemeinsame Teile der beiden Layouts
// ---------------------------------------------------------------------------

/** Im ERG-Modus faerbt die Naehe zum Ziel die Leistung gruen — ruhig, ohne Alarm. */
private fun powerColor(session: IndoorSessionState): Color {
    val erg = session.erg
    val watts = session.powerW
    if (erg == null || erg.done || watts == null) return HudColors.ink
    val diff = kotlin.math.abs(watts - erg.targetW)
    return if (diff <= maxOf(erg.targetW / 20, 5)) HudColors.accent else HudColors.ink
}

/** Unter den Kernwerten: Geist (ausblendbar), Laden der Welt, Bergwertung, Hinweis. */
@Composable
private fun TopExtras(ui: IndoorRideUi, session: IndoorSessionState, chromeHidden: Boolean, actions: HudActions) {
    val ghost = session.ghost
    if (ghost != null) {
        ChromeVisibility(visible = !chromeHidden) {
            HudGhostChip(ghost = ghost, label = ui.ghostLabel, modifier = Modifier.padding(top = 4.dp))
        }
    }
    (ui.render as? IndoorRenderUi.Loading)?.let { loading ->
        HudLoadingChip(loading = loading, modifier = Modifier.padding(top = 4.dp))
    }
    // Bergwertung und Kilometer (IndoorTrailHud.kt); abschaltbar in den Einstellungen.
    ui.trail?.let { IndoorTrailOverlay(trail = it, modifier = Modifier.padding(top = 4.dp)) }
    ui.notice?.let { notice ->
        NoticeBanner(notice = notice, onDismiss = actions.onDismissNotice, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Ein- und Ausblenden der Nebensachen, weich. */
@Composable
private fun ChromeVisibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        content()
    }
}

/**
 * Die grossen −/+-Tasten an den Raendern; dazwischen [middle] mit der Pille
 * (Gang oder Zielleistung). Im ERG-Modus verschieben die Tasten das Ziel.
 */
@Composable
private fun ShiftRow(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    actions: HudActions,
    middle: @Composable (pill: @Composable () -> Unit) -> Unit,
) {
    val erg = session.erg
    val gear = session.gear
    val ergMode = session.mode == IndoorMode.ERG && erg != null
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (ergMode) {
            HudStepButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.indoor_app_hud_erg_less_cd),
                enabled = true,
                onClick = { actions.onErgAdjust(-ERG_ADJUST_STEP_PCT) },
            )
        } else {
            HudStepButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.indoor_app_hud_gear_easier_cd),
                enabled = gear.index > 1,
                onClick = { actions.onShift(ShifterEvent.DOWN) },
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            middle {
                if (ergMode && erg != null) {
                    HudErgPill(
                        title = ui.ergTitle,
                        targetW = erg.targetW,
                        remainingS = erg.stepRemainingS,
                        done = erg.done,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    HudGearPill(index = gear.index, count = gear.count)
                }
            }
        }
        if (ergMode) {
            HudStepButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.indoor_app_hud_erg_more_cd),
                enabled = true,
                onClick = { actions.onErgAdjust(ERG_ADJUST_STEP_PCT) },
            )
        } else {
            HudStepButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.indoor_app_hud_gear_harder_cd),
                enabled = gear.index < gear.count,
                onClick = { actions.onShift(ShifterEvent.UP) },
            )
        }
    }
}

/** Ob Trainer oder Schalttaster gerade ein Problem haben (Warnfarbe am Geraeteknopf). */
private fun devicesHaveProblem(ui: IndoorRideUi): Boolean =
    ui.trainer != TrainerConnection.READY ||
        ui.shifter == ShifterConnection.NO_PERMISSION ||
        ui.shifter == ShifterConnection.BLUETOOTH_OFF ||
        ui.shifter == ShifterConnection.UNSUPPORTED

/**
 * Die runden Symbolknoepfe: Geraete, Kamera (nur mit 3D-Welt), Pause/Weiter,
 * Beenden (gedrueckt halten). [vertical] = Spalte am rechten Rand, sonst Reihe.
 */
@Composable
private fun HudButtons(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    showCamera: Boolean,
    vertical: Boolean,
    actions: HudActions,
) {
    // Beschriftung und Aktion aus derselben Bedingung (IndoorFlowScreen setzt
    // nur bei PAUSED fort): Bei Auto-Pause pausiert ein Tipp.
    val paused = session.phase == IndoorPhase.PAUSED
    val problem = devicesHaveProblem(ui)
    val buttons: List<@Composable () -> Unit> = buildList {
        add {
            HudRoundButton(
                icon = if (problem) Icons.Filled.Warning else Icons.Filled.BluetoothConnected,
                contentDescription = stringResource(
                    if (problem) R.string.indoor_app_hud_devices_problem_cd else R.string.indoor_app_hud_devices_cd,
                ),
                onClick = actions.onShowDevices,
                container = if (problem) HudColors.warning else HudColors.panelStrong,
                tint = if (problem) Color.Black else HudColors.ink,
            )
        }
        if (showCamera) {
            add {
                val cameraName = cameraLabel(ui.camera)
                HudRoundButton(
                    icon = Icons.Filled.Videocam,
                    contentDescription = stringResource(R.string.indoor_app_hud_camera_cd, cameraName),
                    onClick = { actions.onCameraChange(nextCamera(ui.camera)) },
                )
            }
        }
        add { HudHoldToFinishButton(onFinish = actions.onFinish) }
        add {
            HudRoundButton(
                icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = stringResource(
                    if (paused) R.string.indoor_app_ride_resume_action else R.string.indoor_app_ride_pause_action,
                ),
                onClick = actions.onTogglePause,
            )
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons.forEach { it() }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons.forEach { it() }
        }
    }
}

/** Naechste Kamera in der Reihenfolge des Enums. */
private fun nextCamera(current: CameraMode): CameraMode {
    val all = CameraMode.entries
    return all[(all.indexOf(current) + 1) % all.size]
}

/** Demo-Regler, wenn der Demo-Trainer faehrt (ausblendbar). */
@Composable
private fun DemoPanel(ui: IndoorRideUi, chromeHidden: Boolean, actions: HudActions) {
    if (ui.demoPowerW == null && ui.demoCadenceRpm == null) return
    ChromeVisibility(visible = !chromeHidden) {
        HudDemoSliders(
            powerW = ui.demoPowerW,
            cadenceRpm = ui.demoCadenceRpm,
            onDemoPower = actions.onDemoPower,
            onDemoCadence = actions.onDemoCadence,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Pause, Auto-Pause, Bereit, Ziel: eine ruhige Karte in der Mitte. Bei harter
 * Pause mit „Weiter" — dort, wo der Blick ohnehin hingeht.
 */
@Composable
private fun PhaseCard(
    session: IndoorSessionState,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
    onFinish: (() -> Unit)? = null,
) {
    val phase = phaseLabel(session.phase) ?: return
    Surface(color = HudColors.panelStrong, shape = RoundedCornerShape(24.dp), modifier = modifier.widthIn(max = 320.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = phase,
                style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)),
                textAlign = TextAlign.Center,
            )
            if (session.phase == IndoorPhase.PAUSED) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // In der Pause ein einfacher Tipp zum Beenden (danach: speichern oder verwerfen).
                    if (onFinish != null) {
                        TextButton(onClick = onFinish) {
                            Text(stringResource(R.string.indoor_app_start_finish_action), color = HudColors.ink)
                        }
                    }
                    Button(
                        onClick = onTogglePause,
                        colors = ButtonDefaults.buttonColors(containerColor = HudColors.ink, contentColor = Color.Black),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.indoor_app_ride_resume_action))
                    }
                }
            }
        }
    }
}

/**
 * Einheit oder Strecke ist zu Ende, die andere nicht (docs/indoor/app.md,
 * „Einheit des Tages"): Einheit geschafft → „Ausfahren" (der Trainer haelt die
 * Ausfahrleistung, die Karte verschwindet) oder „Beenden"; Strecke ohne
 * Rundschluss zu Ende → Hinweis mit der Restzeit und „Beenden".
 */
@Composable
private fun DayEndCard(dayEnd: IndoorDayEndUi, actions: HudActions, modifier: Modifier = Modifier) {
    val done = dayEnd.kind == DayRideEnd.WORKOUT_DONE
    Surface(color = HudColors.panelStrong, shape = RoundedCornerShape(24.dp), modifier = modifier.widthIn(max = 340.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = stringResource(if (done) R.string.indoor_app_day_end_done_title else R.string.indoor_app_day_end_route_title),
                style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (done) {
                    stringResource(R.string.indoor_app_day_end_done_body)
                } else {
                    stringResource(R.string.indoor_app_day_end_route_body, dayEnd.remainingMin)
                },
                style = hudStyle(MaterialTheme.typography.bodyMedium),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Button(
                        onClick = actions.onDismissDayEnd,
                        colors = ButtonDefaults.buttonColors(containerColor = HudColors.ink, contentColor = Color.Black),
                    ) { Text(stringResource(R.string.indoor_app_day_end_cooldown_action)) }
                }
                OutlinedButton(onClick = actions.onFinish) {
                    Text(stringResource(R.string.indoor_app_day_end_finish_action), color = HudColors.ink)
                }
            }
        }
    }
}

/** Hinweis oben: antippen schliesst ihn; er verschwindet auch von selbst (Sache des ViewModels). */
@Composable
private fun NoticeBanner(notice: IndoorNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val signals = LocalSignalColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().clickable(onClick = onDismiss),
    ) {
        NoticeBox(
            icon = if (notice.isGood()) Icons.Filled.Info else Icons.Filled.Warning,
            color = if (notice.isGood()) signals.good else signals.warning,
            text = noticeText(notice),
            action = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.indoor_app_notice_dismiss_action)) }
            },
        )
    }
}

/** Statt der 3D-Flaeche bei einem Fehler der Darstellung: Meldung und Hinweis, dass es weitergeht. */
@Composable
private fun RenderFailedCard(ui: IndoorRideUi, actions: HudActions) {
    val render = ui.render as? IndoorRenderUi.Failed ?: return
    val onWithout = actions.onRideWithoutWorld
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(4.dp)) {
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = LocalSignalColors.current.warning,
                title = stringResource(R.string.indoor_app_ride_render_failed_title),
                text = if (render.failure == null && render.problem != null) problemText(render.problem) else renderFailureText(render.failure),
                action = onWithout?.let { action ->
                    {
                        TextButton(onClick = action) {
                            Text(stringResource(R.string.indoor_app_world_without_action))
                        }
                    }
                },
            )
            TechnicalDetail(render.problem?.detail, modifier = Modifier.padding(horizontal = 12.dp))
            MutedText(
                stringResource(R.string.indoor_app_ride_render_failed_hint),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Ohne 3D-Welt: das grosse Hoehenprofil fuellt den freien Platz. Wird der
 * Platz knapp (kleines Geraet, Hinweis, Demo-Regler), entfaellt die
 * Ueberschrift und das Profil schrumpft, bleibt aber sichtbar.
 */
@Composable
private fun BigProfilePanel(ui: IndoorRideUi, session: IndoorSessionState, modifier: Modifier = Modifier) {
    if (ui.profile == null) {
        Spacer(modifier = modifier)
        return
    }
    Surface(color = HudColors.panel, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val roomy = maxHeight >= 160.dp
            val chrome = if (roomy) 88.dp else 48.dp
            val canvasHeight = (maxHeight - chrome).coerceIn(24.dp, 360.dp)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (roomy) 16.dp else 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (roomy) {
                    Text(
                        text = stringResource(R.string.indoor_app_ride_profile_title),
                        style = hudStyle(MaterialTheme.typography.labelLarge, HudColors.inkMuted),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                HudProfileCanvas(ui = ui, session = session, height = canvasHeight)
                Spacer(modifier = Modifier.height(if (roomy) 8.dp else 4.dp))
                HudProfileHeader(ui = ui, session = session)
            }
        }
    }
}


/** Trainer, Schalttaster und die Quellenangaben der Welt (ueber den Geraeteknopf). */
@Composable
private fun DevicesDialog(ui: IndoorRideUi, onDismiss: () -> Unit) {
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.indoor_app_hud_devices_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.indoor_app_hud_trainer_status, ui.trainerName, trainerStatusLabel(ui.trainer)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ui.shifter?.let { shifter ->
                    val battery = ui.shifterBatteryPct
                    Text(
                        text = if (battery != null) {
                            stringResource(R.string.indoor_app_hud_shifter_status_battery, shifterStatusLabel(shifter), battery)
                        } else {
                            stringResource(R.string.indoor_app_hud_shifter_status, shifterStatusLabel(shifter))
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (ui.attribution.isNotEmpty() || ui.render !is IndoorRenderUi.NoWorld) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.indoor_app_attribution_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    AttributionList(ui.attribution)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.indoor_app_action_close)) }
        },
    )
}
