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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.more.SettingsSwitchRow
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.sky.IndoorTimeOfDay

/**
 * # Schritt 1: Koppeln
 *
 * Zustandslos. [devicesSection] ist ein Slot fuer `IndoorDevicesSection()`
 * (Koppeln, Status, Vergessen mit Berechtigungsabfrage), damit Screenshot-Tests
 * ihn durch einen Platzhalter ersetzen koennen. Der Demo-Trainer braucht kein
 * Geraet — mit ihm geht es immer weiter.
 *
 * Die Zeile „Kettenblatt am Trainer" stellt die echte Uebersetzung ein
 * (Kettenblatt / Zwift Cog mit 14 Zaehnen), auf die sich die virtuellen Gaenge
 * beziehen; [onChainringChange] bekommt den neuen Wert, das ViewModel begrenzt
 * und merkt ihn. Darunter waehlt [onGearTableChange] die Gangtabelle
 * (gleichmaessig oder wie am Rad) und [onTimeOfDayChange] die Tageszeit der
 * 3D-Welt (automatisch nach der Uhr, Morgen, Mittag, Abend); [onOtherRidersChange] schaltet
 * die anderen Radfahrer in der 3D-Welt.
 */
@Composable
fun IndoorPairScreen(
    ui: IndoorPairUi,
    devicesSection: @Composable () -> Unit,
    onDemoChange: (Boolean) -> Unit,
    onChainringChange: (Int) -> Unit,
    onGearTableChange: (VirtualGearTable) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onTimeOfDayChange: (IndoorTimeOfDay) -> Unit = {},
    onOtherRidersChange: (Boolean) -> Unit = {},
) {
    IndoorPage(
        title = stringResource(R.string.indoor_app_pair_title),
        overline = stepOverline(1),
        onBack = onBack,
        bottomBar = {
            if (!ui.canContinue) {
                MutedText(stringResource(R.string.indoor_app_pair_continue_hint))
            }
            Button(
                onClick = onContinue,
                enabled = ui.canContinue,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text(stringResource(R.string.indoor_app_action_continue))
            }
        },
    ) {
        MutedText(stringResource(R.string.indoor_app_pair_intro))

        Card(modifier = Modifier.fillMaxWidth()) {
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

        if (ui.demoTrainer) {
            NoticeBox(
                icon = Icons.Filled.Info,
                color = MaterialTheme.colorScheme.primary,
                text = stringResource(R.string.indoor_app_pair_demo_notice),
            )
        }

        ChainringCard(teeth = ui.chainringTeeth, onChange = onChainringChange)
        GearTableCard(selected = ui.gearTable, onChange = onGearTableChange)
        TimeOfDayCard(selected = ui.timeOfDay, onChange = onTimeOfDayChange)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.indoor_app_pair_other_riders_title),
                    subtitle = stringResource(R.string.indoor_app_pair_other_riders_body),
                    checked = ui.otherRiders,
                    onCheckedChange = onOtherRidersChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Koppeln, Status, Vergessen: Der Abschnitt bringt Ueberschrift,
        // Einleitung, Berechtigungsabfrage, die gemerkten Geraete je Rolle und
        // den Hinweis „Schalttaster optional" selbst mit — deshalb hier keine
        // zweite Liste der gemerkten Geraete. Die Karte gibt ihm denselben
        // Rahmen wie den Karten darueber.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(IndoorCardPadding)) {
                devicesSection()
            }
        }

        TextButton(onClick = onOpenDiagnostics) {
            Text(stringResource(R.string.indoor_app_pair_diagnostics_action))
        }
    }
}

/** Stepper „Kettenblatt am Trainer" (ein Zahn je Schritt). */
@Composable
private fun ChainringCard(teeth: Int, onChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
private fun GearTableCard(selected: VirtualGearTable, onChange: (VirtualGearTable) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
private fun TimeOfDayCard(selected: IndoorTimeOfDay, onChange: (IndoorTimeOfDay) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
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
