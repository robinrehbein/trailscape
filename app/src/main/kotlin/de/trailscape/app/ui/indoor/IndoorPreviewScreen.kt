package de.trailscape.app.ui.indoor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.map.KeepScreenOn
import de.trailscape.core.indoor.world.CameraMode
import kotlin.math.roundToInt

/**
 * # 3D-Vorschau einer Strecke (Idee 4, docs/indoor/vorschau.md)
 *
 * Vollflaechig die vorhandene 3D-Ansicht ([worldSurface], im Test ein
 * Platzhalter), darueber oben Name, Stelle, Hoehe und Steigung, unten das
 * Hoehenprofil als Schieber (Tippen oder Ziehen springt) und die Knoepfe
 * Kamera · Wiedergabe/Pause · Geschwindigkeit. Kein Trainer, keine
 * Aufzeichnung.
 *
 * Faellt die 3D-Ansicht aus (oder wurde „Nur Höhenprofil ansehen" gewaehlt),
 * bleibt der Rueckfall der Fahrt: dunkler Grund, ein Hinweis und das
 * Hoehenprofil, auf dem die Vorschau weiterlaeuft.
 */
@Composable
fun IndoorPreviewScreen(
    ui: IndoorPreviewUi,
    worldSurface: @Composable (Modifier) -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onSeek: (Double) -> Unit,
    onCameraChange: (CameraMode) -> Unit,
) {
    KeepScreenOn()
    val showWorld = ui.render is IndoorRenderUi.Ready || ui.render is IndoorRenderUi.Loading
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (showWorld) {
            worldSurface(Modifier.fillMaxSize())
        } else {
            Box(modifier = Modifier.fillMaxSize().background(HudColors.backdrop))
        }

        // Oben: Name, Stelle, Hoehe, Steigung.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HudColors.topScrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HudRoundButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.idee04_preview_back_cd),
                    onClick = onBack,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.idee04_preview_eyebrow),
                        style = hudStyle(MaterialTheme.typography.labelMedium, HudColors.inkMuted),
                        maxLines = 1,
                    )
                    Text(
                        text = ui.routeName,
                        style = hudStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            PreviewMetrics(ui)
            Spacer(modifier = Modifier.height(8.dp))
            PreviewStatusChips(ui)
        }

        if (!showWorld) {
            PreviewFallback(ui, modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp))
        }

        // Unten: Hoehenprofil als Schieber, Knoepfe.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(HudColors.bottomScrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PreviewScrubber(ui, onSeek)
            Spacer(modifier = Modifier.height(8.dp))
            PreviewControls(ui, onTogglePlay, onCycleSpeed, onCameraChange)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (ui.paceFromRide) {
                    stringResource(R.string.idee04_preview_pace_ride)
                } else {
                    stringResource(R.string.idee04_preview_pace_model, ui.paceWatts)
                },
                style = hudStyle(MaterialTheme.typography.labelSmall, HudColors.inkMuted),
                maxLines = 2,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** „12,4 von 48,0 km" · Höhe · Steigung. */
@Composable
private fun PreviewMetrics(ui: IndoorPreviewUi) {
    val formats = LocalAppFormats.current
    val f = ui.frame
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        PreviewMetric(
            label = stringResource(R.string.idee04_preview_distance_label),
            value = stringResource(R.string.idee04_preview_position, formats.indoorKm(f.distM), formats.indoorKm(f.lengthM)),
            modifier = Modifier.weight(1.4f),
        )
        PreviewMetric(
            label = stringResource(R.string.idee04_preview_elevation_label),
            value = stringResource(R.string.idee04_preview_elevation_value, formats.indoorAscent(f.eleM)),
            modifier = Modifier.weight(1f),
        )
        PreviewMetric(
            label = stringResource(R.string.idee04_preview_grade_label),
            value = stringResource(R.string.idee04_preview_grade_value, formats.indoorGrade(f.gradePct)),
            color = gradeColor(f.gradePct),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewMetric(label: String, value: String, modifier: Modifier = Modifier, color: Color = HudColors.ink) {
    Column(modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $value" }) {
        Text(
            text = label,
            style = hudStyle(MaterialTheme.typography.labelMedium, HudColors.inkMuted),
            maxLines = 1,
        )
        Text(
            text = value,
            style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Laden der 3D-Ansicht bzw. Warten auf Kacheln. */
@Composable
private fun PreviewStatusChips(ui: IndoorPreviewUi) {
    val loading = ui.render as? IndoorRenderUi.Loading
    when {
        loading != null -> HudLoadingChip(loading)
        ui.frame.waitingForTiles -> HudChip {
            Text(stringResource(R.string.idee04_preview_waiting), style = hudStyle(MaterialTheme.typography.labelLarge))
        }
        else -> Unit
    }
}

/** Ohne 3D-Ansicht: ein kurzer Hinweis in der Mitte (das Profil unten bleibt der Schieber). */
@Composable
private fun PreviewFallback(ui: IndoorPreviewUi, modifier: Modifier = Modifier) {
    val failed = ui.render as? IndoorRenderUi.Failed
    Surface(color = HudColors.panel, shape = RoundedCornerShape(20.dp), modifier = modifier.widthIn(max = 420.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (failed != null) {
                Text(
                    text = stringResource(R.string.idee04_preview_no3d_title),
                    style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (failed.failure == null && failed.problem != null) problemText(failed.problem) else renderFailureText(failed.failure),
                    style = hudStyle(MaterialTheme.typography.bodyMedium, HudColors.inkMuted),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.idee04_preview_no3d_body),
                    style = hudStyle(MaterialTheme.typography.bodyMedium, HudColors.inkMuted),
                )
            } else {
                Text(
                    text = stringResource(R.string.idee04_preview_profile_only_body),
                    style = hudStyle(MaterialTheme.typography.bodyMedium, HudColors.inkMuted),
                )
            }
        }
    }
}

/**
 * Das Hoehenprofil als Schieber: Tippen oder seitliches Ziehen springt an die
 * Stelle. Fuer TalkBack ein Fortschrittswert in Metern mit „Wert setzen".
 */
@Composable
private fun PreviewScrubber(ui: IndoorPreviewUi, onSeek: (Double) -> Unit) {
    val profile = ui.profile ?: return
    val formats = LocalAppFormats.current
    val length by rememberUpdatedState(profile.lengthM)
    val seek by rememberUpdatedState(onSeek)
    val description = stringResource(R.string.idee04_preview_profile_cd, formats.indoorKm(ui.frame.distM))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PREVIEW_SCRUBBER_TAG)
            .pointerInput(Unit) {
                detectTapGestures { pos -> if (size.width > 0) seek(pos.x / size.width * length) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (size.width > 0) seek((change.position.x / size.width).coerceIn(0f, 1f) * length)
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = ui.frame.distM.toFloat(),
                    range = 0f..profile.lengthM.toFloat().coerceAtLeast(1f),
                )
                setProgress { target ->
                    seek(target.toDouble())
                    true
                }
            },
    ) {
        IndoorProfileCanvas(
            profile = profile,
            routeDistM = ui.frame.distM,
            ghostRouteDistM = null,
            description = description,
            height = 72.dp,
            colors = HudColors.profile,
        )
    }
}

/** Kamera · Wiedergabe/Pause · Geschwindigkeit, dazu die Restzeit. */
@Composable
private fun PreviewControls(
    ui: IndoorPreviewUi,
    onTogglePlay: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCameraChange: (CameraMode) -> Unit,
) {
    val f = ui.frame
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (f.atEnd) {
                stringResource(R.string.idee04_preview_finished)
            } else {
                stringResource(R.string.idee04_preview_remaining, formatIndoorClock(f.remainingS.roundToInt()))
            },
            style = hudStyle(MaterialTheme.typography.labelLarge),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HudRoundButton(
                icon = Icons.Filled.Videocam,
                contentDescription = stringResource(R.string.idee04_preview_camera_cd, previewCameraLabel(ui.camera)),
                onClick = { onCameraChange(nextPreviewCamera(ui.camera)) },
            )
            val playIcon = when {
                f.playing -> Icons.Filled.Pause
                f.atEnd -> Icons.Filled.Replay
                else -> Icons.Filled.PlayArrow
            }
            val playCd = when {
                f.playing -> R.string.idee04_preview_pause_cd
                f.atEnd -> R.string.idee04_preview_replay_cd
                else -> R.string.idee04_preview_play_cd
            }
            HudRoundButton(
                icon = playIcon,
                contentDescription = stringResource(playCd),
                onClick = onTogglePlay,
                container = HudColors.accent,
                tint = Color.Black,
                modifier = Modifier.testTag(PREVIEW_PLAY_TAG),
            )
            val speedCd = stringResource(R.string.idee04_preview_speed_cd, f.speedFactor)
            Surface(
                onClick = onCycleSpeed,
                shape = CircleShape,
                color = HudColors.panelStrong,
                contentColor = HudColors.ink,
                modifier = Modifier
                    .size(HudRoundButtonSize)
                    .testTag(PREVIEW_SPEED_TAG)
                    .clearAndSetSemantics { contentDescription = speedCd },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.idee04_preview_speed_label, f.speedFactor),
                        style = hudStyle(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)),
                    )
                }
            }
        }
    }
}

@Composable
private fun previewCameraLabel(mode: CameraMode): String = stringResource(
    when (mode) {
        CameraMode.CHASE -> R.string.idee04_preview_camera_chase
        CameraMode.DRONE -> R.string.idee04_preview_camera_drone
        CameraMode.HANDLEBAR -> R.string.idee04_preview_camera_handlebar
    },
)

/** Reihenfolge der Kameras in der Vorschau: Drohne → Verfolger → Lenker → Drohne. */
fun nextPreviewCamera(current: CameraMode): CameraMode = when (current) {
    CameraMode.DRONE -> CameraMode.CHASE
    CameraMode.CHASE -> CameraMode.HANDLEBAR
    CameraMode.HANDLEBAR -> CameraMode.DRONE
}

internal const val PREVIEW_SCRUBBER_TAG = "idee04_preview_scrubber"
internal const val PREVIEW_PLAY_TAG = "idee04_preview_play"
internal const val PREVIEW_SPEED_TAG = "idee04_preview_speed"
