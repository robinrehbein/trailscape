package de.trailscape.app.ui.indoor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorStartPoints
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * # Startpunkt waehlen — die Karte unten im HUD
 *
 * Steht anstelle von Gang und schmalem Profil, solange sich der Fahrer
 * versetzen laesst (vor dem ersten Tritt, in der Pause; [IndoorStartUi]):
 *
 *  * ein grosses Hoehenprofil zum Ziehen oder Antippen — der Punkt ist die
 *    Startstelle, links davon (der uebersprungene Teil) blass;
 *  * daneben −/+ fuer 100 m genau, darueber „km x · y m";
 *  * mit gemerkter Stelle „Weiter bei km x" (vorausgewaehlt) und „Von vorn";
 *  * „Losfahren" (die Fahrt wartet darauf) bzw. in der Pause „Weiter".
 *
 * Waehrend des Ziehens zeigt die Karte die Fingerstelle sofort; die Sitzung
 * folgt mit dem naechsten Takt.
 *
 * @param compact Querformat: flacheres Profil, ohne Hinweiszeile, Auswahl und
 *   Knopf in einer Reihe.
 */
@Composable
internal fun IndoorStartPanel(
    start: IndoorStartUi,
    profile: IndoorProfileUi,
    phase: IndoorPhase,
    onMove: (Double) -> Unit,
    onNudge: (Double) -> Unit,
    onRelease: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val formats = LocalAppFormats.current
    // Fingerstelle, bis die Sitzung sie bestaetigt (oder kurz nach dem Loslassen).
    var pending by remember { mutableStateOf<Double?>(null) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(start.selectedM, pending, dragging) {
        val p = pending ?: return@LaunchedEffect
        if (dragging) return@LaunchedEffect
        if (abs(p - start.selectedM) <= IndoorStartPoints.SNAP_M) {
            pending = null
        } else {
            delay(PENDING_TIMEOUT_MS)
            pending = null
        }
    }
    val shownM = pending ?: start.selectedM
    val title = when {
        start.held -> stringResource(R.string.indoor_app_start_title_choose)
        else -> phaseLabel(phase) ?: stringResource(R.string.indoor_app_start_title_choose)
    }
    val position = stringResource(
        R.string.indoor_app_start_position,
        formats.indoorKm(shownM),
        formats.decimal(profileEleAt(profile, shownM).toDouble(), 0),
    )

    Surface(color = HudColors.panelStrong, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = position,
                    style = hudStyle(MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), HudColors.accent),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (!compact) {
                Text(
                    text = stringResource(
                        if (phase == IndoorPhase.PAUSED) R.string.indoor_app_start_hint_drag_pause else R.string.indoor_app_start_hint_drag,
                    ),
                    style = hudStyle(MaterialTheme.typography.bodySmall, HudColors.inkMuted),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudRoundButton(
                    icon = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.indoor_app_start_earlier_cd),
                    onClick = { onNudge(-IndoorStartPoints.NUDGE_M) },
                )
                StartProfile(
                    profile = profile,
                    shownM = shownM,
                    maxM = start.maxM,
                    height = if (compact) 52.dp else 76.dp,
                    onDrag = { m ->
                        pending = m
                        onMove(m)
                    },
                    onDragState = { dragging = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                HudRoundButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.indoor_app_start_later_cd),
                    onClick = { onNudge(IndoorStartPoints.NUDGE_M) },
                )
            }
            val choices: @Composable () -> Unit = {
                start.resumeM?.let { resumeM ->
                    StartChip(
                        text = stringResource(R.string.indoor_app_start_resume_chip, formats.indoorKm(resumeM)),
                        selected = abs(shownM - resumeM) < IndoorStartPoints.SNAP_M,
                        onClick = {
                            pending = resumeM
                            onMove(resumeM)
                        },
                    )
                    StartChip(
                        text = stringResource(R.string.indoor_app_start_from_beginning_chip),
                        selected = shownM < IndoorStartPoints.SNAP_M,
                        onClick = {
                            pending = 0.0
                            onMove(0.0)
                        },
                    )
                }
            }
            val action: @Composable (Modifier) -> Unit = { m ->
                when {
                    start.held -> StartAction(stringResource(R.string.indoor_app_start_go_action), onRelease, m)
                    phase == IndoorPhase.PAUSED -> StartAction(stringResource(R.string.indoor_app_ride_resume_action), onResume, m)
                    else -> Unit
                }
            }
            val hasAction = start.held || phase == IndoorPhase.PAUSED
            if (compact) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices()
                    Spacer(modifier = Modifier.weight(1f))
                    if (hasAction) action(Modifier)
                }
            } else {
                if (start.resumeM != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices() }
                }
                if (hasAction) action(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Das ziehbare Profil: Antippen setzt, Ziehen verschiebt; fuer die Bedienungshilfe ein Regler. */
@Composable
private fun StartProfile(
    profile: IndoorProfileUi,
    shownM: Double,
    maxM: Double,
    height: androidx.compose.ui.unit.Dp,
    onDrag: (Double) -> Unit,
    onDragState: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = LocalAppFormats.current
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragState by rememberUpdatedState(onDragState)
    val lengthM = profile.lengthM
    fun toMeters(x: Float, width: Int): Double {
        if (width <= 0 || lengthM <= 0) return 0.0
        return ((x / width).toDouble() * lengthM).coerceIn(0.0, maxM)
    }
    val cd = stringResource(R.string.indoor_app_start_profile_cd, formats.indoorKm(shownM), formats.indoorKm(lengthM))
    Box(
        modifier = modifier
            .pointerInput(lengthM, maxM) {
                detectTapGestures(onTap = { o -> currentDrag(toMeters(o.x, size.width)) })
            }
            .pointerInput(lengthM, maxM) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        currentDragState(true)
                        currentDrag(toMeters(o.x, size.width))
                    },
                    onDragEnd = { currentDragState(false) },
                    onDragCancel = { currentDragState(false) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentDrag(toMeters(change.position.x, size.width))
                    },
                )
            }
            .clearAndSetSemantics {
                contentDescription = cd
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = shownM.toFloat(),
                    range = 0f..maxM.toFloat().coerceAtLeast(1f),
                )
                setProgress { target ->
                    currentDrag(target.toDouble().coerceIn(0.0, maxM))
                    true
                }
            },
    ) {
        IndoorProfileCanvas(
            profile = profile,
            routeDistM = shownM,
            ghostRouteDistM = null,
            description = cd,
            height = height,
            colors = StartProfileColors,
        )
    }
}

@Composable
private fun StartChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = HudColors.ink,
            selectedContainerColor = HudColors.accent,
            selectedLabelColor = Color.Black,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = HudColors.outline,
            selectedBorderColor = HudColors.accent,
        ),
    )
}

@Composable
private fun StartAction(text: String, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = HudColors.ink, contentColor = Color.Black),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text)
    }
}

/** Startwahl: der uebersprungene Teil (links) blass, die Strecke ab dem Punkt hell. */
private val StartProfileColors = IndoorProfileColors(
    line = Color.White.copy(alpha = 0.92f),
    fill = HudColors.accent.copy(alpha = 0.30f),
    riddenLine = Color.White.copy(alpha = 0.32f),
    riddenFill = Color.White.copy(alpha = 0.06f),
    rider = HudColors.accent,
    riderRing = Color.White,
    ghost = HudColors.ghost,
    baseline = Color.White.copy(alpha = 0.25f),
)

/** So lange haelt die Karte die Fingerstelle, wenn die Sitzung sie nicht uebernimmt. */
private const val PENDING_TIMEOUT_MS = 700L

/** Hoehe an [distM] aus den Stuetzpunkten des Profils (linear dazwischen). */
internal fun profileEleAt(profile: IndoorProfileUi, distM: Double): Float {
    val n = minOf(profile.distM.size, profile.eleM.size)
    if (n == 0) return 0f
    if (n == 1) return profile.eleM[0]
    val d = distM.coerceIn(0.0, profile.lengthM).toFloat()
    var i = 1
    while (i < n - 1 && profile.distM[i] < d) i++
    val d0 = profile.distM[i - 1]
    val d1 = profile.distM[i]
    val t = if (d1 > d0) ((d - d0) / (d1 - d0)).coerceIn(0f, 1f) else 0f
    return profile.eleM[i - 1] + (profile.eleM[i] - profile.eleM[i - 1]) * t
}
