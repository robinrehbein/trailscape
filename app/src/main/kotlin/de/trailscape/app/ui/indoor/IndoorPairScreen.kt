package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.TrailscapePrimaryButton
import de.trailscape.app.ui.more.SettingsSwitchRow
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.sky.IndoorTimeOfDay

/**
 * # Nebenseite „Trainer koppeln" (von der Startseite)
 *
 * Zustandslos. [devicesSection] ist ein Slot fuer `IndoorDevicesSection()`
 * (Suche, Berechtigung, Bluetooth, Koppeln, Vergessen), damit Screenshot-Tests
 * ihn durch einen Platzhalter ersetzen koennen. Darunter der Schalter „Ohne
 * Trainer ausprobieren" (Demo-Trainer). „Fertig" und Zurueck fuehren zur
 * Startseite; dort steht, ob noch etwas fehlt — diese Seite sperrt nichts.
 *
 * Fahrgefuehl und 3D-Ansicht (Kettenblatt, Gaenge, Tageszeit, andere
 * Radfahrer) stehen seit dem neuen Nutzerfluss unter Einstellungen
 * ([IndoorSettingsScreen]).
 */
@Composable
fun IndoorPairScreen(
    ui: IndoorPairUi,
    devicesSection: @Composable () -> Unit,
    onDemoChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    IndoorPage(
        title = stringResource(R.string.indoor_app_pair_title),
        onBack = onBack,
        bottomBar = {
            TrailscapePrimaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(stringResource(R.string.indoor_app_action_done))
            }
        },
    ) {
        MutedText(stringResource(R.string.indoor_app_pair_intro))

        // Koppeln, Status, Vergessen: Der Abschnitt bringt Ueberschrift,
        // Einleitung, Berechtigungsabfrage, die gemerkten Geraete je Rolle und
        // den Hinweis „Schalttaster optional" selbst mit.
        AtlasCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(IndoorCardPadding)) {
                devicesSection()
            }
        }

        AtlasCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.indoor_app_pair_demo_title),
                    subtitle = stringResource(R.string.indoor_app_pair_demo_body),
                    checked = ui.demoTrainer,
                    onCheckedChange = onDemoChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Stepper „Kettenblatt am Trainer" (ein Zahn je Schritt). */
@Composable
internal fun ChainringCard(teeth: Int, onChange: (Int) -> Unit) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(stringResource(R.string.indoor_app_pair_chainring_title))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = { onChange(teeth - 1) },
                    enabled = teeth > IndoorViewModel.CHAINRING_MIN_TEETH,
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.indoor_app_pair_chainring_less_cd))
                }
                Text(
                    text = stringResource(R.string.indoor_app_pair_chainring_value, teeth),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = { onChange(teeth + 1) },
                    enabled = teeth < IndoorViewModel.CHAINRING_MAX_TEETH,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.indoor_app_pair_chainring_more_cd))
                }
            }
            MutedText(
                stringResource(R.string.indoor_app_pair_chainring_body, teeth, IndoorViewModel.ZWIFT_COG_TEETH),
            )
        }
    }
}

/** Wahl der Gangtabelle der virtuellen Gaenge (je 24 Gaenge). */
@Composable
internal fun GearTableCard(selected: VirtualGearTable, onChange: (VirtualGearTable) -> Unit) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Eyebrow(
                stringResource(R.string.indoor_app_pair_gears_title),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                for (table in VirtualGearTable.entries) {
                    GearTableRow(table = table, selected = table == selected, onSelect = { onChange(table) })
                }
            }
        }
    }
}

/** Wahl der Tageszeit der 3D-Welt (Sonnenstand, Licht, Himmel). */
@Composable
internal fun TimeOfDayCard(selected: IndoorTimeOfDay, onChange: (IndoorTimeOfDay) -> Unit) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Eyebrow(
                stringResource(R.string.indoor_app_pair_time_title),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                for (choice in IndoorTimeOfDay.entries) {
                    ChoiceRow(
                        label = stringResource(timeOfDayLabel(choice)),
                        body = stringResource(timeOfDayBody(choice)),
                        selected = choice == selected,
                        onSelect = { onChange(choice) },
                    )
                }
            }
        }
    }
}

private fun timeOfDayLabel(choice: IndoorTimeOfDay): Int = when (choice) {
    IndoorTimeOfDay.AUTO -> R.string.indoor_app_pair_time_auto_label
    IndoorTimeOfDay.MORNING -> R.string.indoor_app_pair_time_morning_label
    IndoorTimeOfDay.NOON -> R.string.indoor_app_pair_time_noon_label
    IndoorTimeOfDay.EVENING -> R.string.indoor_app_pair_time_evening_label
}

private fun timeOfDayBody(choice: IndoorTimeOfDay): Int = when (choice) {
    IndoorTimeOfDay.AUTO -> R.string.indoor_app_pair_time_auto_body
    IndoorTimeOfDay.MORNING -> R.string.indoor_app_pair_time_morning_body
    IndoorTimeOfDay.NOON -> R.string.indoor_app_pair_time_noon_body
    IndoorTimeOfDay.EVENING -> R.string.indoor_app_pair_time_evening_body
}

@Composable
private fun GearTableRow(table: VirtualGearTable, selected: Boolean, onSelect: () -> Unit) {
    ChoiceRow(
        label = stringResource(gearTableLabel(table)),
        body = stringResource(gearTableBody(table)),
        selected = selected,
        onSelect = onSelect,
    )
}

/** Eine Zeile mit Auswahlknopf, Titel und kurzer Erklaerung. */
@Composable
private fun ChoiceRow(label: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun gearTableLabel(table: VirtualGearTable): Int = when (table) {
    VirtualGearTable.EVEN -> R.string.indoor_app_pair_gears_even_label
    VirtualGearTable.BIKE_GRX_2X12 -> R.string.indoor_app_pair_gears_bike_label
}

private fun gearTableBody(table: VirtualGearTable): Int = when (table) {
    VirtualGearTable.EVEN -> R.string.indoor_app_pair_gears_even_body
    VirtualGearTable.BIKE_GRX_2X12 -> R.string.indoor_app_pair_gears_bike_body
}
