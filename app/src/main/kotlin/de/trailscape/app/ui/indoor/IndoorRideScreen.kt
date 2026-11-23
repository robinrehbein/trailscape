package de.trailscape.app.ui.indoor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.HoldToEndButton
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.map.KeepScreenOn
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.indoor.GhostState
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.ShifterEvent
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.WorldAttribution
import kotlin.math.roundToInt

/** ERG: Schritt der Plus/Minus-Knoepfe in Prozentpunkten der FTP. */
private const val ERG_ADJUST_STEP_PCT = 5

/** Reglerbereiche des Demo-Trainers. */
private const val DEMO_POWER_MAX_W = 600
private const val DEMO_CADENCE_MAX_RPM = 130

/** Deckkraft der HUD-Flaechen ueber der 3D-Welt. */
private const val HUD_ALPHA = 0.84f

/**
 * # Schritt 4: Fahren
 *
 * Die 3D-Flaeche liegt vollflaechig ([worldSurface] ist ein Slot — der echte
 * `WorldSurface` oder ein Platzhalter im Screenshot-Test), das HUD darueber.
 * Zustandslos: Alles, was angezeigt wird, steckt in [ui].
 *
 * Verhalten bei Fehlern (docs/indoor.md 10.1):
 *  * [IndoorRenderUi.Failed] — verstaendliche Meldung statt der 3D-Flaeche, das
 *    HUD und das Hoehenprofil laufen weiter;
 *  * [IndoorRenderUi.NoWorld] — das Hoehenprofil nimmt den Platz der 3D-Flaeche ein;
 *  * `session == null` mit `startProblem` — Fehlerseite mit „Zurück".
 *
 * Hoch- und Querformat: Im Querformat stehen die Kacheln links, die Bedienung
 * rechts und die Welt dazwischen.
 *
 * @param onShift Schaltwunsch von den Knoepfen (der Taster geht ueber die Laufzeit).
 * @param onErgAdjust Prozentpunkte der FTP, `±5`.
 * @param onRideWithoutWorld wenn gesetzt, bietet die Fehlermeldung der 3D-Flaeche
 *   „Ohne Welt fahren" an; `null` = kein Angebot.
 * @param onBack nur fuer die Fehlerseite (Start gescheitert).
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
) {
    val session = ui.session
    if (session == null) {
        RideStartFailed(ui = ui, onBack = onBack)
        return
    }

    KeepScreenOn()
    var showAttribution by rememberSaveable { mutableStateOf(false) }
    val showWorld = ui.render is IndoorRenderUi.Ready || ui.render is IndoorRenderUi.Loading

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      androidx.compose.runtime.CompositionLocalProvider(LocalHudAlpha provides if (showWorld) HUD_ALPHA else 1f) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val boxWidth = maxWidth
            if (showWorld) {
                worldSurface(Modifier.fillMaxSize())
            }
            Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                if (landscape) {
                    LandscapeHud(
                        ui = ui,
                        session = session,
                        showWorld = showWorld,
                        availableWidth = boxWidth,
                        onShift = onShift,
                        onTogglePause = onTogglePause,
                        onFinish = onFinish,
                        onCameraChange = onCameraChange,
                        onDemoPower = onDemoPower,
                        onDemoCadence = onDemoCadence,
                        onErgAdjust = onErgAdjust,
                        onDismissNotice = onDismissNotice,
                        onRideWithoutWorld = onRideWithoutWorld,
                        onShowAttribution = { showAttribution = true },
                    )
                } else {
                    PortraitHud(
                        ui = ui,
                        session = session,
                        showWorld = showWorld,
                        onShift = onShift,
                        onTogglePause = onTogglePause,
                        onFinish = onFinish,
                        onCameraChange = onCameraChange,
                        onDemoPower = onDemoPower,
                        onDemoCadence = onDemoCadence,
                        onErgAdjust = onErgAdjust,
                        onDismissNotice = onDismissNotice,
                        onRideWithoutWorld = onRideWithoutWorld,
                        onShowAttribution = { showAttribution = true },
                    )
                }
            }
        }
      }
    }

    if (showAttribution) {
        AttributionDialog(attribution = ui.attribution, onDismiss = { showAttribution = false })
    }
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
    onShift: (ShifterEvent) -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onCameraChange: (CameraMode) -> Unit,
    onDemoPower: (Int) -> Unit,
    onDemoCadence: (Int) -> Unit,
    onErgAdjust: (Int) -> Unit,
    onDismissNotice: () -> Unit,
    onRideWithoutWorld: (() -> Unit)?,
    onShowAttribution: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // Die untere Bedienung (Demo-Regler, ERG, Gang, Knoepfe) hat eine
    // Hoechsthoehe und scrollt dann — so bleibt der Mitte (Welt bzw. das grosse
    // Hoehenprofil) auch auf kleinen Geraeten oder mit grosser Schrift Platz.
    // Ohne Welt braucht das Profil in der Mitte mehr Raum.
    val maxPanelHeight = maxHeight * if (showWorld) 0.58f else 0.42f
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
        ui.notice?.let { NoticeBanner(it, onDismissNotice) }
        MainTilesRow(session = session, ui = ui)
        StateChips(ui = ui, session = session, modifier = Modifier.padding(top = 6.dp))
        // Mitte: die Welt scheint durch; fehlt sie, steht hier die Ersatzanzeige.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)) {
            if (!showWorld) {
                FallbackArea(ui = ui, session = session, onRideWithoutWorld = onRideWithoutWorld)
            }
        }
        BottomPanel(
            ui = ui,
            session = session,
            showProfile = showWorld,
            maxHeight = maxPanelHeight,
            onShift = onShift,
            onTogglePause = onTogglePause,
            onFinish = onFinish,
            onCameraChange = onCameraChange,
            onDemoPower = onDemoPower,
            onDemoCadence = onDemoCadence,
            onErgAdjust = onErgAdjust,
            onShowAttribution = onShowAttribution,
        )
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
    availableWidth: Dp,
    onShift: (ShifterEvent) -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onCameraChange: (CameraMode) -> Unit,
    onDemoPower: (Int) -> Unit,
    onDemoCadence: (Int) -> Unit,
    onErgAdjust: (Int) -> Unit,
    onDismissNotice: () -> Unit,
    onRideWithoutWorld: (() -> Unit)?,
    onShowAttribution: () -> Unit,
) {
    val sideWidth = (availableWidth * 0.30f).coerceIn(200.dp, 340.dp)
    Row(modifier = Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Links: die vier grossen Kacheln als 2×2 und die Strecken-Angaben.
        Column(
            modifier = Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MainTilesGrid(session = session, ui = ui)
            DetailRow(session = session, ui = ui, onShift = onShift)
            StateChips(ui = ui, session = session)
        }
        // Mitte: Welt bzw. Ersatzanzeige, oben der Hinweis.
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ui.notice?.let { NoticeBanner(it, onDismissNotice) }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (!showWorld) {
                    FallbackArea(ui = ui, session = session, onRideWithoutWorld = onRideWithoutWorld)
                }
            }
            if (showWorld) {
                ProfilePanel(ui = ui, session = session, height = 56.dp)
            }
        }
        // Rechts: Bedienung.
        Column(
            modifier = Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ControlsColumn(
                ui = ui,
                session = session,
                showCamera = showWorld,
                onTogglePause = onTogglePause,
                onFinish = onFinish,
                onCameraChange = onCameraChange,
                onDemoPower = onDemoPower,
                onDemoCadence = onDemoCadence,
                onErgAdjust = onErgAdjust,
                onShowAttribution = onShowAttribution,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bausteine
// ---------------------------------------------------------------------------

@Composable
private fun hudColor(): Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = LocalHudAlpha.current)

/** Deckkraft der HUD-Flaechen: ueber der Welt leicht durchscheinend, ohne Welt voll deckend. */
private val LocalHudAlpha = androidx.compose.runtime.compositionLocalOf { HUD_ALPHA }

/** Eine HUD-Kachel: Beschriftung, grosse Zahl, Einheit. */
@Composable
private fun HudTile(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
    valueSize: Int = 28,
) {
    Surface(
        color = hudColor(),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = valueSize.sp, fontWeight = FontWeight.SemiBold),
                    color = valueColor,
                    maxLines = 1,
                    softWrap = false,
                )
                if (unit != null) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = 3.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun powerTile(session: IndoorSessionState): Triple<String, String, Color> {
    val erg = session.erg
    val power = formatIndoorInt(session.powerW)
    // Im ERG-Modus faerbt die Abweichung vom Ziel die Zahl — ruhig, ohne Alarm.
    val watts = session.powerW
    val color = if (erg != null && !erg.done && watts != null) {
        val diff = kotlin.math.abs(watts - erg.targetW)
        if (diff <= maxOf(erg.targetW / 20, 5)) LocalSignalColors.current.good else Color.Unspecified
    } else {
        Color.Unspecified
    }
    return Triple(stringResource(R.string.indoor_app_hud_power), power, color)
}

/** Leistung, Tempo, Trittfrequenz, Puls in einer Reihe (Hochformat). */
@Composable
private fun MainTilesRow(session: IndoorSessionState, ui: IndoorRideUi) {
    val formats = LocalAppFormats.current
    val (powerLabel, power, powerColor) = powerTile(session)
    // Schmales Geraet (360 dp): alle vier Zahlen gleich etwas kleiner, damit
    // "km/h" nicht abgeschnitten wird und die Kacheln gleich hoch bleiben.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val tileValueSize = if (maxWidth < 380.dp) 23 else 28
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        HudTile(
            label = powerLabel,
            value = power,
            unit = stringResource(R.string.indoor_app_unit_watts),
            valueColor = powerColor,
            valueSize = tileValueSize,
            modifier = Modifier.weight(1.15f),
        )
        HudTile(
            label = stringResource(R.string.indoor_app_hud_speed),
            value = formats.indoorSpeedKmh(session.speedMps),
            unit = stringResource(R.string.indoor_app_unit_kmh),
            valueSize = tileValueSize,
            modifier = Modifier.weight(1.25f),
        )
        HudTile(
            label = stringResource(R.string.indoor_app_hud_cadence),
            value = formatIndoorInt(session.cadenceRpm),
            unit = null,
            valueSize = tileValueSize,
            modifier = Modifier.weight(0.9f),
        )
        HudTile(
            label = stringResource(R.string.indoor_app_hud_heart_rate),
            value = formatIndoorInt(session.hrBpm),
            unit = null,
            valueSize = tileValueSize,
            modifier = Modifier.weight(0.9f),
        )
    }
    }
}

/** Dieselben vier Kacheln als 2×2 (Querformat, links). */
@Composable
private fun MainTilesGrid(session: IndoorSessionState, ui: IndoorRideUi) {
    val formats = LocalAppFormats.current
    val (powerLabel, power, powerColor) = powerTile(session)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            HudTile(
                label = powerLabel,
                value = power,
                unit = stringResource(R.string.indoor_app_unit_watts),
                valueColor = powerColor,
                modifier = Modifier.weight(1f),
            )
            HudTile(
                label = stringResource(R.string.indoor_app_hud_speed),
                value = formats.indoorSpeedKmh(session.speedMps),
                unit = stringResource(R.string.indoor_app_unit_kmh),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            HudTile(
                label = stringResource(R.string.indoor_app_hud_cadence),
                value = formatIndoorInt(session.cadenceRpm),
                unit = stringResource(R.string.indoor_app_unit_rpm),
                modifier = Modifier.weight(1f),
            )
            HudTile(
                label = stringResource(R.string.indoor_app_hud_heart_rate),
                value = formatIndoorInt(session.hrBpm),
                unit = stringResource(R.string.indoor_app_unit_bpm),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Steigung, Belag und Gang mit Knoepfen. */
@Composable
private fun DetailRow(session: IndoorSessionState, ui: IndoorRideUi, onShift: (ShifterEvent) -> Unit) {
    val formats = LocalAppFormats.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            HudTile(
                label = stringResource(R.string.indoor_app_hud_grade),
                value = formats.indoorGrade(session.gradePct),
                unit = stringResource(R.string.indoor_app_unit_percent),
                modifier = Modifier.weight(1f),
            )
            HudTile(
                label = stringResource(R.string.indoor_app_hud_surface),
                value = surfaceLabel(session.surface),
                unit = null,
                valueSize = 16,
                modifier = Modifier.weight(1.4f),
            )
        }
        // Im ERG-Modus haengt der Widerstand nicht am Gang — die Knoepfe entfallen.
        if (session.mode == IndoorMode.SIM) {
            GearControl(session = session, onShift = onShift)
        }
    }
}

/** Gang mit − und + (leichter / schwerer). */
@Composable
private fun GearControl(session: IndoorSessionState, onShift: (ShifterEvent) -> Unit) {
    val gear = session.gear
    Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = { onShift(ShifterEvent.DOWN) }, enabled = gear.index > 1) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.indoor_app_hud_gear_easier_cd))
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.indoor_app_hud_gear_value, gear.index, gear.count),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.indoor_app_hud_gear_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onShift(ShifterEvent.UP) }, enabled = gear.index < gear.count) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.indoor_app_hud_gear_harder_cd))
            }
        }
    }
}

/** Pause-Hinweis, Ladeanzeige der Welt, Geist-Abstand. */
@Composable
private fun StateChips(ui: IndoorRideUi, session: IndoorSessionState, modifier: Modifier = Modifier) {
    val phase = phaseLabel(session.phase)
    val loading = ui.render as? IndoorRenderUi.Loading
    val ghost = session.ghost
    if (phase == null && loading == null && ghost == null) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (phase != null) {
            Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = phase,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (loading != null) {
            Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = if (loading.neededTiles > 0) {
                            stringResource(R.string.indoor_app_ride_world_loading_count, loading.loadedTiles, loading.neededTiles)
                        } else {
                            stringResource(R.string.indoor_app_ride_world_loading)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (loading.neededTiles > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (loading.loadedTiles.toFloat() / loading.neededTiles).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        if (ghost != null) {
            GhostChip(ghost = ghost, label = ui.ghostLabel)
        }
    }
}

@Composable
private fun GhostChip(ghost: GhostState, label: String?) {
    val name = label ?: stringResource(R.string.indoor_app_hud_ghost_default)
    val ahead = ghost.gapM >= 0.0
    val gapS = ghost.gapS
    val text = if (gapS != null) {
        val time = formatIndoorGapSeconds(gapS)
        stringResource(if (ahead) R.string.indoor_app_hud_ghost_ahead else R.string.indoor_app_hud_ghost_behind, name, time)
    } else {
        val meters = indoorGapMeters(ghost.gapM)
        stringResource(if (ahead) R.string.indoor_app_hud_ghost_ahead_m else R.string.indoor_app_hud_ghost_behind_m, name, meters)
    }
    Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** Hinweis oben: antippen schliesst ihn; er verschwindet auch von selbst (Sache des ViewModels). */
@Composable
private fun NoticeBanner(notice: IndoorNotice, onDismiss: () -> Unit) {
    val signals = LocalSignalColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable(onClick = onDismiss),
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

/** Ersatz fuer die 3D-Flaeche: Meldung bei Fehler und/oder das grosse Hoehenprofil. */
@Composable
private fun FallbackArea(ui: IndoorRideUi, session: IndoorSessionState, onRideWithoutWorld: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val render = ui.render
        val withoutWorldAction: (@Composable () -> Unit)? = if (onRideWithoutWorld != null) {
            ({
                TextButton(onClick = onRideWithoutWorld) {
                    Text(stringResource(R.string.indoor_app_world_without_action))
                }
            })
        } else {
            null
        }
        if (render is IndoorRenderUi.Failed) {
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = LocalSignalColors.current.warning,
                title = stringResource(R.string.indoor_app_ride_render_failed_title),
                text = if (render.failure == null && render.problem != null) problemText(render.problem) else renderFailureText(render.failure),
                action = withoutWorldAction,
            )
            MutedText(stringResource(R.string.indoor_app_ride_render_failed_hint))
        }
        if (ui.profile != null) {
            Surface(color = hudColor(), shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().weight(1f)) {
                BoxWithConstraints {
                    val canvasHeight = (maxHeight - 72.dp).coerceIn(96.dp, 320.dp)
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.Top) {
                        Text(
                            text = stringResource(R.string.indoor_app_ride_profile_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ProfileCanvasFor(ui = ui, session = session, height = canvasHeight)
                        Spacer(modifier = Modifier.height(8.dp))
                        ProfilePosition(ui = ui, session = session)
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileCanvasFor(ui: IndoorRideUi, session: IndoorSessionState, height: Dp) {
    val profile = ui.profile ?: return
    val formats = LocalAppFormats.current
    val position = formats.indoorKm(session.routeDistM)
    val ghost = session.ghost?.let { routePositionOf(it.distM, profile) }
    IndoorProfileCanvas(
        profile = profile,
        routeDistM = session.routeDistM,
        ghostRouteDistM = ghost,
        description = stringResource(R.string.indoor_app_ride_profile_cd, position),
        height = height,
    )
}

/** Laufmeter des Geists (ueber Runden fortlaufend) auf die Strecke zurueckgefuehrt. */
private fun routePositionOf(distM: Double, profile: IndoorProfileUi): Double =
    if (profile.isLoop && profile.lengthM > 0.0) distM % profile.lengthM else distM.coerceIn(0.0, profile.lengthM)

/** „3,2 von 12,4 km" unter dem Profil, in Runden mit der Rundenzahl. */
@Composable
private fun ProfilePosition(ui: IndoorRideUi, session: IndoorSessionState) {
    val profile = ui.profile ?: return
    val formats = LocalAppFormats.current
    val text = if (profile.isLoop) {
        stringResource(
            R.string.indoor_app_ride_position_loop,
            formats.indoorKm(session.routeDistM),
            formats.indoorKm(profile.lengthM),
            session.lap,
        )
    } else {
        stringResource(
            R.string.indoor_app_ride_position,
            formats.indoorKm(session.routeDistM),
            formats.indoorKm(profile.lengthM),
        )
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Kleines Profil in einer HUD-Flaeche (mit der Welt dahinter). */
@Composable
private fun ProfilePanel(ui: IndoorRideUi, session: IndoorSessionState, height: Dp) {
    if (ui.profile == null) return
    Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            ProfileCanvasFor(ui = ui, session = session, height = height)
            ProfilePosition(ui = ui, session = session)
        }
    }
}

/** Unterer Bereich im Hochformat: ERG, Profil, Gang, Regler, Knoepfe, Status. */
@Composable
private fun BottomPanel(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    showProfile: Boolean,
    maxHeight: Dp,
    onShift: (ShifterEvent) -> Unit,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onCameraChange: (CameraMode) -> Unit,
    onDemoPower: (Int) -> Unit,
    onDemoCadence: (Int) -> Unit,
    onErgAdjust: (Int) -> Unit,
    onShowAttribution: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
    ) {
        DetailRow(session = session, ui = ui, onShift = onShift)
        if (showProfile) ProfilePanel(ui = ui, session = session, height = 64.dp)
        ControlsColumn(
            ui = ui,
            session = session,
            showCamera = showProfile,
            onTogglePause = onTogglePause,
            onFinish = onFinish,
            onCameraChange = onCameraChange,
            onDemoPower = onDemoPower,
            onDemoCadence = onDemoCadence,
            onErgAdjust = onErgAdjust,
            onShowAttribution = onShowAttribution,
        )
    }
}

/** ERG-Abschnitt, Demo-Regler, Kamera/Pause/Beenden und Statuszeile. */
@Composable
private fun ControlsColumn(
    ui: IndoorRideUi,
    session: IndoorSessionState,
    showCamera: Boolean,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit,
    onCameraChange: (CameraMode) -> Unit,
    onDemoPower: (Int) -> Unit,
    onDemoCadence: (Int) -> Unit,
    onErgAdjust: (Int) -> Unit,
    onShowAttribution: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        session.erg?.let { erg ->
            ErgPanel(ui = ui, targetW = erg.targetW, remainingS = erg.stepRemainingS, done = erg.done, onErgAdjust = onErgAdjust)
        }
        if (ui.demoPowerW != null || ui.demoCadenceRpm != null) {
            DemoSliders(ui = ui, onDemoPower = onDemoPower, onDemoCadence = onDemoCadence)
        }
        // Beschriftung und Aktion aus derselben Bedingung (IndoorFlowScreen setzt
        // nur bei PAUSED fort): Bei Auto-Pause pausiert ein Tipp, der Chip
        // erklaert die Auto-Pause.
        val paused = session.phase == IndoorPhase.PAUSED
        // Schmale Spalte (Querformat): Kamera und Pause untereinander, damit
        // die Beschriftung "Von hinten" nicht abgeschnitten wird.
        BoxWithConstraints {
            val stack = showCamera && maxWidth < 300.dp
            val cameraButton: @Composable (Modifier) -> Unit = { m ->
                val cameraName = cameraLabel(ui.camera)
                val cameraCd = stringResource(R.string.indoor_app_hud_camera_cd, cameraName)
                NeutralButton(
                    onClick = { onCameraChange(nextCamera(ui.camera)) },
                    modifier = m.semantics { contentDescription = cameraCd },
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(cameraName, maxLines = 1)
                }
            }
            val pauseButton: @Composable (Modifier) -> Unit = { m ->
                Button(onClick = onTogglePause, modifier = m.heightIn(min = 48.dp)) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(if (paused) R.string.indoor_app_ride_resume_action else R.string.indoor_app_ride_pause_action),
                        maxLines = 1,
                    )
                }
            }
            if (stack) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Kamera nur mit 3D-Welt — ohne sie waere der Knopf wirkungslos.
                    cameraButton(Modifier.fillMaxWidth())
                    pauseButton(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showCamera) cameraButton(Modifier.weight(1f))
                    pauseButton(Modifier.weight(1f))
                }
            }
        }
        HoldToEndButton(
            onEnd = onFinish,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.indoor_app_ride_finish_hold),
            holdHint = stringResource(R.string.indoor_app_ride_finish_hint),
        )
        StatusLine(ui = ui, onShowAttribution = onShowAttribution)
    }
}

/** Naechste Kamera in der Reihenfolge des Enums. */
private fun nextCamera(current: CameraMode): CameraMode {
    val all = CameraMode.entries
    return all[(all.indexOf(current) + 1) % all.size]
}

/** ERG: Ziel, Restzeit, Plus/Minus. */
@Composable
private fun ErgPanel(
    ui: IndoorRideUi,
    targetW: Int,
    remainingS: Int,
    done: Boolean,
    onErgAdjust: (Int) -> Unit,
) {
    Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { onErgAdjust(-ERG_ADJUST_STEP_PCT) }) {
                Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.indoor_app_hud_erg_less_cd))
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = ui.ergTitle ?: stringResource(R.string.indoor_app_hud_erg_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.indoor_app_hud_erg_target, targetW),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (done) {
                        stringResource(R.string.indoor_app_hud_erg_done)
                    } else {
                        stringResource(R.string.indoor_app_hud_erg_remaining, formatIndoorRemaining(remainingS))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onErgAdjust(ERG_ADJUST_STEP_PCT) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.indoor_app_hud_erg_more_cd))
            }
        }
    }
}

/** Regler des Demo-Trainers: Leistung 0..600 W, Trittfrequenz 0..130. */
@Composable
private fun DemoSliders(ui: IndoorRideUi, onDemoPower: (Int) -> Unit, onDemoCadence: (Int) -> Unit) {
    Surface(color = hudColor(), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            ui.demoPowerW?.let { power ->
                DemoSliderRow(
                    label = stringResource(R.string.indoor_app_demo_power, power),
                    value = power,
                    max = DEMO_POWER_MAX_W,
                    description = stringResource(R.string.indoor_app_demo_power_cd),
                    onChange = onDemoPower,
                )
            }
            ui.demoCadenceRpm?.let { cadence ->
                DemoSliderRow(
                    label = stringResource(R.string.indoor_app_demo_cadence, cadence),
                    value = cadence,
                    max = DEMO_CADENCE_MAX_RPM,
                    description = stringResource(R.string.indoor_app_demo_cadence_cd),
                    onChange = onDemoCadence,
                )
            }
        }
    }
}

@Composable
private fun DemoSliderRow(label: String, value: Int, max: Int, description: String, onChange: (Int) -> Unit) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.toFloat().coerceIn(0f, max.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** Trainer- und Schalttaster-Status, rechts das „ⓘ" fuer die Quellenangaben. */
@Composable
private fun StatusLine(ui: IndoorRideUi, onShowAttribution: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(hudColor(), MaterialTheme.shapes.medium)
            .padding(start = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.indoor_app_hud_trainer_status, ui.trainerName, trainerStatusLabel(ui.trainer)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            ui.shifter?.let { shifter ->
                val battery = ui.shifterBatteryPct
                Text(
                    text = if (battery != null) {
                        stringResource(R.string.indoor_app_hud_shifter_status_battery, shifterStatusLabel(shifter), battery)
                    } else {
                        stringResource(R.string.indoor_app_hud_shifter_status, shifterStatusLabel(shifter))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (ui.attribution.isNotEmpty() || ui.render !is IndoorRenderUi.NoWorld) {
            IconButton(onClick = onShowAttribution) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.indoor_app_attribution_open_cd))
            }
        }
    }
}

/** Die Quellenangaben der Welt (ueber das „ⓘ" erreichbar). */
@Composable
private fun AttributionDialog(attribution: List<WorldAttribution>, onDismiss: () -> Unit) {
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.indoor_app_attribution_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AttributionList(attribution)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.indoor_app_action_close)) }
        },
    )
}
