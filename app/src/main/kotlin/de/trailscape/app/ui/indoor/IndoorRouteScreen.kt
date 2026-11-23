package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.components.TagPill
import de.trailscape.core.indoor.IndoorMode

/** Schrittweite der Vergleichsleistung in Watt. */
private const val GHOST_WATTS_STEP = 10

/** Grenzen der Vergleichsleistung (wie im ViewModel). */
private const val GHOST_WATTS_MIN = 30
private const val GHOST_WATTS_MAX = 1000

/**
 * # Schritt 2: Strecke und Modus
 *
 * Eine gespeicherte Planung, eine gefahrene Tour oder die Testwelt; dazu
 * freie Fahrt (SIM) oder die Einheit des Tages (ERG) und wer mitfaehrt (Geist).
 * Zustandslos: jede Wahl geht als Rueckruf nach oben, das `IndoorViewModel`
 * prueft sie und gibt einen neuen [IndoorRouteUi] zurueck.
 *
 * [onGhostChange] bekommt bei „Frühere Fahrt" ein `PastRide` mit leerem Label
 * — das ViewModel setzt Namen und Datum selbst.
 */
@Composable
fun IndoorRouteScreen(
    ui: IndoorRouteUi,
    onSelect: (IndoorRouteOption) -> Unit,
    onModeChange: (IndoorMode) -> Unit,
    onGhostChange: (IndoorGhostOption) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    IndoorPage(
        title = stringResource(R.string.indoor_app_route_title),
        overline = stepOverline(2),
        onBack = onBack,
        bottomBar = {
            Button(
                onClick = onContinue,
                enabled = ui.canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.indoor_app_action_continue))
            }
        },
    ) {
        MutedText(stringResource(R.string.indoor_app_route_intro))

        Eyebrow(stringResource(R.string.indoor_app_route_section_route))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                ui.options.forEach { option ->
                    RouteOptionRow(
                        option = option,
                        selected = ui.selected?.key == option.key,
                        onSelect = { onSelect(option) },
                    )
                }
                if (ui.loadingOptions) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (ui.options.size <= 1) {
                    MutedText(
                        text = stringResource(R.string.indoor_app_route_no_saved),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        Eyebrow(stringResource(R.string.indoor_app_route_section_mode))
        PillSegments(
            options = listOf(
                modeLabel(IndoorMode.SIM),
                modeLabel(IndoorMode.ERG),
            ),
            selectedIndex = if (ui.mode == IndoorMode.SIM) 0 else 1,
            onSelect = { onModeChange(if (it == 0) IndoorMode.SIM else IndoorMode.ERG) },
        )
        when {
            ui.mode == IndoorMode.SIM -> MutedText(stringResource(R.string.indoor_app_mode_sim_body))
            ui.ergTitle != null -> MutedText(
                if (ui.ergMinutes != null) {
                    stringResource(R.string.indoor_app_mode_erg_body, ui.ergTitle, ui.ergMinutes)
                } else {
                    stringResource(R.string.indoor_app_mode_erg_body_no_duration, ui.ergTitle)
                },
            )
            else -> NoticeBox(
                icon = Icons.Filled.Info,
                color = MaterialTheme.colorScheme.tertiary,
                text = stringResource(R.string.indoor_app_mode_erg_unavailable),
            )
        }

        Eyebrow(stringResource(R.string.indoor_app_route_section_ghost))
        GhostChooser(ui = ui, onGhostChange = onGhostChange)
    }
}

@Composable
private fun RouteOptionRow(option: IndoorRouteOption, selected: Boolean, onSelect: () -> Unit) {
    val formats = LocalAppFormats.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            when (option) {
                IndoorRouteOption.TestWorld -> {
                    Text(
                        text = stringResource(R.string.indoor_app_route_testworld_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.indoor_app_route_testworld_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is IndoorRouteOption.Saved -> {
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                    )
                    Text(
                        text = stringResource(
                            R.string.indoor_app_route_meta,
                            formats.km(option.distanceKm),
                            formats.indoorAscent(option.ascentM),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            if (option.planned) R.string.indoor_app_route_kind_planned else R.string.indoor_app_route_kind_ride,
                            formats.dateShort(option.createdAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (option is IndoorRouteOption.Saved && option.hasWorld) {
            Spacer(modifier = Modifier.padding(start = 8.dp))
            TagPill(text = stringResource(R.string.indoor_app_route_world_ready_tag))
        }
    }
}

@Composable
private fun GhostChooser(ui: IndoorRouteUi, onGhostChange: (IndoorGhostOption) -> Unit) {
    val ghost = ui.ghost
    // Ohne gefahrene Tour mit Zeitstempeln gibt es „Frühere Fahrt" nicht.
    val labels = buildList {
        add(stringResource(R.string.indoor_app_ghost_none))
        if (ui.ghostPastRideAvailable) add(stringResource(R.string.indoor_app_ghost_past))
        add(stringResource(R.string.indoor_app_ghost_power))
    }
    val selectedIndex = when (ghost) {
        IndoorGhostOption.None -> 0
        is IndoorGhostOption.PastRide -> if (ui.ghostPastRideAvailable) 1 else 0
        is IndoorGhostOption.ConstantPower -> labels.lastIndex
    }
    PillSegments(
        options = labels,
        selectedIndex = selectedIndex,
        onSelect = { index ->
            val pastIndex = if (ui.ghostPastRideAvailable) 1 else -1
            val selectedRide = ui.selected as? IndoorRouteOption.Saved
            onGhostChange(
                when {
                    index == 0 -> IndoorGhostOption.None
                    index == pastIndex && selectedRide != null ->
                        IndoorGhostOption.PastRide(selectedRide.rideId, "")
                    else -> IndoorGhostOption.ConstantPower(
                        (ghost as? IndoorGhostOption.ConstantPower)?.watts ?: ui.defaultGhostWatts,
                    )
                },
            )
        },
    )
    when (ghost) {
        IndoorGhostOption.None -> MutedText(stringResource(R.string.indoor_app_ghost_none_body))
        is IndoorGhostOption.PastRide -> MutedText(stringResource(R.string.indoor_app_ghost_past_body))
        is IndoorGhostOption.ConstantPower -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        onGhostChange(IndoorGhostOption.ConstantPower((ghost.watts - GHOST_WATTS_STEP).coerceAtLeast(GHOST_WATTS_MIN)))
                    },
                    enabled = ghost.watts > GHOST_WATTS_MIN,
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.indoor_app_ghost_power_less_cd))
                }
                Text(
                    text = stringResource(R.string.indoor_app_ghost_power_value, ghost.watts),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = {
                        onGhostChange(IndoorGhostOption.ConstantPower((ghost.watts + GHOST_WATTS_STEP).coerceAtMost(GHOST_WATTS_MAX)))
                    },
                    enabled = ghost.watts < GHOST_WATTS_MAX,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.indoor_app_ghost_power_more_cd))
                }
            }
            MutedText(stringResource(R.string.indoor_app_ghost_power_body))
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}
