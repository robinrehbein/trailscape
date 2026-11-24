package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.indoor.server.BuilderMismatch
import de.trailscape.core.indoor.server.LaserExpectation

/**
 * Die zweite Wahl im Schritt „Welt laden": Bau auf dem eigenen Server in
 * Laserqualitaet (docs/indoor/serverbau.md). Unsichtbar, solange kein Server
 * eingerichtet ist oder er den Bau-Dienst nicht meldet.
 */
@Composable
fun IndoorServerSection(
    ui: IndoorServerUi,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    when (ui) {
        IndoorServerUi.Hidden -> Unit
        is IndoorServerUi.Offer -> {
            Eyebrow(stringResource(R.string.indoor_app_server_eyebrow))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.indoor_app_server_offer_title), style = MaterialTheme.typography.titleMedium)
                    MutedText(stringResource(R.string.indoor_app_server_offer_body))
                    LaserExpectationText(ui.laser)
                    if (ui.replacesDeviceWorld) MutedText(stringResource(R.string.indoor_app_server_offer_replaces))
                    Button(onClick = onBuild, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_server_action))
                    }
                }
            }
            NoticeBox(
                icon = Icons.Filled.Info,
                color = MaterialTheme.colorScheme.tertiary,
                text = stringResource(R.string.indoor_app_server_privacy),
            )
        }
        is IndoorServerUi.Incompatible -> {
            Eyebrow(stringResource(R.string.indoor_app_server_eyebrow))
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = LocalSignalColors.current.warning,
                title = stringResource(R.string.indoor_app_server_incompatible_title),
                text = stringResource(
                    when (ui.mismatch) {
                        BuilderMismatch.UPDATE_SERVER -> R.string.indoor_app_server_incompatible_server
                        BuilderMismatch.UPDATE_APP -> R.string.indoor_app_server_incompatible_app
                        BuilderMismatch.UNKNOWN -> R.string.indoor_app_server_incompatible_unknown
                    },
                    ui.serverBuilderId,
                ),
            )
        }
        is IndoorServerUi.Working -> WorkingCard(ui, onCancel)
        is IndoorServerUi.Failed -> {
            Eyebrow(stringResource(R.string.indoor_app_server_eyebrow))
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = LocalSignalColors.current.warning,
                title = stringResource(R.string.indoor_app_server_failed_title),
                text = stringResource(serverFailureText(ui.reason)),
            )
            TechnicalDetail(ui.detail)
            if (ui.reason != IndoorServerFailure.UNAUTHORIZED) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.indoor_app_action_retry))
                }
            }
            if (ui.canResume) {
                NeutralButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), destructive = true) {
                    Text(stringResource(R.string.indoor_app_server_cancel_action))
                }
            }
        }
    }
}

@Composable
private fun WorkingCard(ui: IndoorServerUi.Working, onCancel: () -> Unit) {
    val formats = LocalAppFormats.current
    Eyebrow(stringResource(R.string.indoor_app_server_eyebrow))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.indoor_app_server_offer_title), style = MaterialTheme.typography.titleMedium)
            val fraction = when (ui.step) {
                IndoorServerStep.BUILDING -> ui.fraction
                IndoorServerStep.DOWNLOADING -> if (ui.totalBytes > 0) ui.downloadedBytes.toFloat() / ui.totalBytes else 0f
                IndoorServerStep.INSTALLING -> 1f
                else -> null
            }
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = when (ui.step) {
                        IndoorServerStep.SENDING -> stringResource(R.string.indoor_app_server_step_sending)
                        IndoorServerStep.QUEUED -> stringResource(R.string.indoor_app_server_step_queued, ui.queuePosition.coerceAtLeast(1))
                        IndoorServerStep.BUILDING -> stringResource(R.string.indoor_app_server_step_building)
                        IndoorServerStep.DOWNLOADING -> stringResource(
                            R.string.indoor_app_server_step_downloading,
                            formats.indoorBytes(ui.downloadedBytes),
                            formats.indoorBytes(ui.totalBytes),
                        )
                        IndoorServerStep.INSTALLING -> stringResource(R.string.indoor_app_server_step_installing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (fraction != null && ui.step != IndoorServerStep.INSTALLING) {
                    Text(
                        text = stringResource(R.string.indoor_app_value_percent, indoorPercent(fraction)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ui.laser?.let { LaserExpectationText(it) }
            MutedText(stringResource(R.string.indoor_app_server_working_hint))
            if (ui.step != IndoorServerStep.INSTALLING) {
                NeutralButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(), enabled = !ui.cancelling, destructive = true) {
                    Text(stringResource(if (ui.cancelling) R.string.indoor_app_server_cancelling else R.string.indoor_app_server_cancel_action))
                }
            }
        }
    }
}

@Composable
private fun LaserExpectationText(laser: IndoorServerLaserUi) {
    val region = laser.regionId?.let { serverRegionName(it) }.orEmpty()
    Text(
        text = when (laser.expectation) {
            LaserExpectation.FULL -> stringResource(R.string.indoor_app_server_laser_full, region)
            LaserExpectation.PARTIAL -> stringResource(R.string.indoor_app_server_laser_partial, laser.sharePct, region)
            LaserExpectation.TOO_LITTLE -> stringResource(R.string.indoor_app_server_laser_too_little, laser.sharePct, region)
            LaserExpectation.NONE -> stringResource(R.string.indoor_app_server_laser_none)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (laser.expectation == LaserExpectation.PARTIAL || laser.expectation == LaserExpectation.TOO_LITTLE) {
        MutedText(stringResource(R.string.indoor_app_server_laser_border_hint))
    }
}

/** Name des Landes einer Laser-Quelle (`nw`, `bw`); unbekannte Kurznamen bleiben, wie sie sind. */
@Composable
fun serverRegionName(id: String): String = when (id) {
    "nw" -> stringResource(R.string.indoor_app_server_region_nw)
    "bw" -> stringResource(R.string.indoor_app_server_region_bw)
    else -> id
}

private fun serverFailureText(reason: IndoorServerFailure): Int = when (reason) {
    IndoorServerFailure.OFFLINE -> R.string.indoor_app_server_failure_offline
    IndoorServerFailure.UNAUTHORIZED -> R.string.indoor_app_server_failure_unauthorized
    IndoorServerFailure.TOO_LONG -> R.string.indoor_app_server_failure_too_long
    IndoorServerFailure.QUEUE_FULL -> R.string.indoor_app_server_failure_queue_full
    IndoorServerFailure.BUILD_FAILED -> R.string.indoor_app_server_failure_build_failed
    IndoorServerFailure.GONE -> R.string.indoor_app_server_failure_gone
    IndoorServerFailure.CHECKSUM -> R.string.indoor_app_server_failure_checksum
    IndoorServerFailure.NO_SPACE -> R.string.indoor_app_server_failure_no_space
    IndoorServerFailure.OTHER -> R.string.indoor_app_server_failure_other
}
