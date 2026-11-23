package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.LocalSignalColors

/**
 * # Schritt 3: Welt laden
 *
 * Erst der **Voranschlag** (Hosts, Datenmenge, Platz, Datenschutzhinweis);
 * Netzverkehr gibt es erst nach „Welt laden". Danach Fortschritt mit Abbruch
 * oder ein verstaendlicher Fehler. „Ohne Welt fahren" ist ausser waehrend des
 * Baus (dort zuerst abbrechen) und nach fertiger Welt immer angeboten.
 *
 * @param onLoad Voranschlag bestaetigt — ab jetzt laeuft Netzverkehr.
 * @param onRetry Weltbau erneut (setzt Fertiges fort).
 * @param onContinue die Welt ist fertig, weiter zur Fahrt.
 */
@Composable
fun IndoorWorldScreen(
    ui: IndoorWorldUi,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    onRideWithoutWorld: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val building = ui is IndoorWorldUi.Building
    IndoorPage(
        title = stringResource(R.string.indoor_app_world_title),
        overline = stepOverline(3),
        // Waehrend des Baus fuehrt „Zurueck" nicht aus Versehen weg: erst Abbrechen.
        onBack = if (building) null else onBack,
        bottomBar = {
            when (ui) {
                IndoorWorldUi.Preparing -> Unit
                is IndoorWorldUi.Plan -> {
                    Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (ui.resumable) R.string.indoor_app_world_resume_action else R.string.indoor_app_world_load_action,
                            ),
                        )
                    }
                    NeutralButton(onClick = onRideWithoutWorld, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_world_without_action))
                    }
                }
                is IndoorWorldUi.Building -> {
                    NeutralButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !ui.cancelling,
                        destructive = true,
                    ) {
                        Text(
                            stringResource(
                                if (ui.cancelling) R.string.indoor_app_world_cancelling else R.string.indoor_app_world_cancel_action,
                            ),
                        )
                    }
                }
                is IndoorWorldUi.Ready -> {
                    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_world_ride_action))
                    }
                }
                is IndoorWorldUi.Failed -> {
                    if (ui.canRetry) {
                        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.indoor_app_action_retry))
                        }
                    }
                    NeutralButton(onClick = onRideWithoutWorld, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_world_without_action))
                    }
                }
                is IndoorWorldUi.RouteUnavailable -> {
                    NeutralButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_world_other_route_action))
                    }
                }
            }
        },
    ) {
        when (ui) {
            IndoorWorldUi.Preparing -> PreparingContent()
            is IndoorWorldUi.Plan -> PlanContent(ui)
            is IndoorWorldUi.Building -> BuildingContent(ui)
            is IndoorWorldUi.Ready -> ReadyContent(ui)
            is IndoorWorldUi.Failed -> FailedContent(ui)
            is IndoorWorldUi.RouteUnavailable -> RouteUnavailableContent(ui)
        }
    }
}

@Composable
private fun PreparingContent() {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            MutedText(stringResource(R.string.indoor_app_world_preparing))
        }
    }
}

@Composable
private fun PlanContent(plan: IndoorWorldUi.Plan) {
    val formats = LocalAppFormats.current
    Text(text = plan.routeName, style = MaterialTheme.typography.titleLarge)
    MutedText(stringResource(R.string.indoor_app_world_plan_intro))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_length),
                value = stringResource(R.string.indoor_app_value_km, formats.km(plan.distanceKm)),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_quality),
                value = qualityLabel(plan.quality),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_download),
                value = if (plan.downloadBytes > 0L) formats.indoorBytes(plan.downloadBytes) else stringResource(R.string.indoor_app_world_plan_download_none),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_disk),
                value = formats.indoorBytes(plan.diskBytes),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_surfaces),
                value = routeSourceLabel(plan.surfaceSource),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_ground),
                value = if (plan.aerialLabels.isEmpty()) {
                    stringResource(R.string.indoor_app_world_plan_ground_generated)
                } else {
                    plan.aerialLabels.joinToString(", ")
                },
            )
        }
    }
    if (plan.resumable) {
        NoticeBox(
            icon = Icons.Filled.Info,
            color = MaterialTheme.colorScheme.primary,
            text = stringResource(R.string.indoor_app_world_plan_resumable),
        )
    }

    Eyebrow(stringResource(R.string.indoor_app_world_plan_hosts_eyebrow))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (plan.hosts.isEmpty()) {
                MutedText(stringResource(R.string.indoor_app_world_plan_hosts_none))
            } else {
                plan.hosts.forEach { host -> Text(text = host, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    NoticeBox(
        icon = Icons.Filled.Info,
        color = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.indoor_app_world_privacy_title),
        text = stringResource(R.string.indoor_app_world_privacy_body),
    )
}

@Composable
private fun BuildingContent(building: IndoorWorldUi.Building) {
    Text(text = stringResource(R.string.indoor_app_world_building_title), style = MaterialTheme.typography.titleLarge)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(
                progress = { building.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = buildPhaseLabel(building.phase), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.indoor_app_value_percent, indoorPercent(building.fraction)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (building.total > 0) {
                MutedText(stringResource(R.string.indoor_app_world_building_count, building.done, building.total))
            }
        }
    }
    MutedText(stringResource(R.string.indoor_app_world_building_hint))
}

@Composable
private fun ReadyContent(ready: IndoorWorldUi.Ready) {
    NoticeBox(
        icon = Icons.Filled.CheckCircle,
        color = LocalSignalColors.current.good,
        title = stringResource(R.string.indoor_app_world_ready_title),
        text = stringResource(R.string.indoor_app_world_ready_body, ready.routeName),
    )
    Eyebrow(stringResource(R.string.indoor_app_attribution_eyebrow))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding)) {
            AttributionList(ready.attribution)
        }
    }
}

@Composable
private fun FailedContent(failed: IndoorWorldUi.Failed) {
    NoticeBox(
        icon = Icons.Filled.Warning,
        color = LocalSignalColors.current.warning,
        title = stringResource(R.string.indoor_app_world_failed_title),
        text = failed.reason?.let { worldFailureText(it) } ?: problemText(failed.problem),
    )
    MutedText(stringResource(R.string.indoor_app_world_failed_hint))
}

@Composable
private fun RouteUnavailableContent(unavailable: IndoorWorldUi.RouteUnavailable) {
    NoticeBox(
        icon = Icons.Filled.Warning,
        color = LocalSignalColors.current.warning,
        title = stringResource(R.string.indoor_app_world_route_unavailable_title),
        text = problemText(unavailable.problem),
    )
    // Den Streckentipp nur bei echten Streckenfehlern — ist ein Teil nur noch
    // nicht fertig, hilft eine andere Strecke nicht.
    MutedText(
        stringResource(
            if (unavailable.problem?.notImplemented == true) {
                R.string.indoor_app_problem_not_ready_hint
            } else {
                R.string.indoor_app_world_route_unavailable_hint
            },
        ),
    )
}
