package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.more.SettingsSwitchRow

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
 * und merkt ihn.
 */
@Composable
fun IndoorPairScreen(
    ui: IndoorPairUi,
    devicesSection: @Composable () -> Unit,
    onDemoChange: (Boolean) -> Unit,
    onChainringChange: (Int) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
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

        // Koppeln, Status, Vergessen: der Abschnitt bringt seine Ueberschriften mit.
        devicesSection()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelValueRow(
                    label = stringResource(R.string.indoor_app_pair_remembered_trainer),
                    value = when {
                        ui.demoTrainer -> stringResource(R.string.indoor_app_pair_demo_name)
                        ui.trainerName != null -> ui.trainerName
                        else -> stringResource(R.string.indoor_app_pair_none)
                    },
                )
                LabelValueRow(
                    label = stringResource(R.string.indoor_app_pair_remembered_shifter),
                    value = ui.shifterName ?: stringResource(R.string.indoor_app_pair_none),
                )
                if (ui.shifterName == null) {
                    MutedText(stringResource(R.string.indoor_app_pair_shifter_optional))
                }
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
