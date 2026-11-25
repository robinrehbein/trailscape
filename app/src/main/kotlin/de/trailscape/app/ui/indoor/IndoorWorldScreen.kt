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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
 * # Strecke vorbereiten (nur fuer Strecken ohne fertige 3D-Ansicht sichtbar)
 *
 * Erst der **Voranschlag** (Datenmenge, Platz, Datenschutzhinweis; Qualitaet,
 * Belag, Boden und die Liste der Hosts unter „Einzelheiten");
 * Netzverkehr gibt es erst nach „Vorbereiten". Der Bau auf dem eigenen Server
 * steht eingeklappt darunter. Danach Fortschritt mit Abbruch
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
    server: IndoorServerUi = IndoorServerUi.Hidden,
    onServerBuild: () -> Unit = {},
    onServerCancel: () -> Unit = {},
    onServerRetry: () -> Unit = {},
    initialAdvancedOpen: Boolean = false,
) {
    val building = ui is IndoorWorldUi.Building
    val serverBusy = server is IndoorServerUi.Working
    IndoorPage(
        title = stringResource(
            if (ui is IndoorWorldUi.Ready) R.string.indoor_app_world_ready_page_title else R.string.indoor_app_world_title,
        ),
        // Waehrend des Baus fuehrt „Zurueck" nicht aus Versehen weg: erst Abbrechen.
        onBack = if (building) null else onBack,
        bottomBar = {
            when (ui) {
                IndoorWorldUi.Preparing -> Unit
                is IndoorWorldUi.Plan -> {
                    // Waehrend der Server baut, nicht zugleich auf dem Geraet bauen.
                    if (!serverBusy) {
                        Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(
                                    if (ui.resumable) R.string.indoor_app_world_resume_action else R.string.indoor_app_world_load_action,
                                ),
                            )
                        }
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
            is IndoorWorldUi.Plan -> {
                // Die zweite Wahl steht oben, sobald sie laeuft — dann ist sie das Wichtigste.
                if (serverBusy) IndoorServerSection(server, onServerBuild, onServerCancel, onServerRetry)
                PlanContent(ui)
                if (!serverBusy) AdvancedServer(server, onServerBuild, onServerCancel, onServerRetry, initialAdvancedOpen)
            }
            is IndoorWorldUi.Building -> BuildingContent(ui)
            is IndoorWorldUi.Ready -> {
                ReadyContent(ui)
                if (serverBusy) {
                    IndoorServerSection(server, onServerBuild, onServerCancel, onServerRetry)
                } else {
                    AdvancedServer(server, onServerBuild, onServerCancel, onServerRetry, initialAdvancedOpen)
                }
            }
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
    var details by rememberSaveable { mutableStateOf(false) }
    Text(text = plan.routeName, style = MaterialTheme.typography.titleLarge)
    MutedText(stringResource(R.string.indoor_app_world_plan_intro))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_length),
                value = stringResource(R.string.indoor_app_value_km, formats.km(plan.distanceKm)),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_download),
                value = if (plan.downloadBytes > 0L) formats.indoorBytes(plan.downloadBytes) else stringResource(R.string.indoor_app_world_plan_download_none),
            )
            LabelValueRow(
                label = stringResource(R.string.indoor_app_world_plan_disk),
                value = formats.indoorBytes(plan.diskBytes),
            )
            if (details) {
                LabelValueRow(
                    label = stringResource(R.string.indoor_app_world_plan_quality),
                    value = qualityLabel(plan.quality),
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
    }
    if (plan.resumable) {
        NoticeBox(
            icon = Icons.Filled.Info,
            color = MaterialTheme.colorScheme.primary,
            text = stringResource(R.string.indoor_app_world_plan_resumable),
        )
    }
    // Datenschutz bleibt sichtbar (es geht um die Zustimmung zum Netzverkehr);
    // die Liste der Hosts steht unter „Einzelheiten".
    NoticeBox(
        icon = Icons.Filled.Info,
        color = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.indoor_app_world_privacy_title),
        text = stringResource(R.string.indoor_app_world_privacy_body),
    )
    if (details) {
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
    }
    TextButton(onClick = { details = !details }) {
        Text(stringResource(if (details) R.string.indoor_app_world_details_hide else R.string.indoor_app_world_details_show))
    }
}

/**
 * Der Bau auf dem eigenen Server ist eine Moeglichkeit fuer Fortgeschrittene:
 * eingeklappt hinter einer Zeile, nur sichtbar, wenn ein Server ihn anbietet.
 * Laeuft er, steht er offen oben (siehe [IndoorWorldScreen]).
 */
@Composable
private fun AdvancedServer(
    server: IndoorServerUi,
    onBuild: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    initiallyOpen: Boolean,
) {
    if (server == IndoorServerUi.Hidden) return
    // Ein Fehler steht offen, damit er nicht untergeht.
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val expanded = open || server is IndoorServerUi.Failed
    if (!expanded) {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.indoor_app_world_advanced_show))
        }
    } else {
        IndoorServerSection(server, onBuild, onCancel, onRetry)
    }
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
    if (ready.fromServer) {
        NoticeBox(
            icon = Icons.Filled.Info,
            color = MaterialTheme.colorScheme.primary,
            text = listOfNotNull(
                ready.serverLaser?.let { stringResource(R.string.indoor_app_server_ready_laser, serverRegionName(it)) }
                    ?: stringResource(R.string.indoor_app_server_ready_no_laser),
                if (ready.replacedDeviceWorld) stringResource(R.string.indoor_app_server_ready_replaced) else null,
            ).joinToString(" "),
        )
    }
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
    TechnicalDetail(failed.detail ?: failed.problem?.detail)
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
    TechnicalDetail(unavailable.problem?.detail)
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
