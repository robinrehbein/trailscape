package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.more.SettingsSwitchRow

/**
 * # Schritt 1: Koppeln
 *
 * Zustandslos. [devicesSection] ist ein Slot fuer `IndoorDevicesSection()`
 * (Koppeln, Status, Vergessen mit Berechtigungsabfrage), damit Screenshot-Tests
 * ihn durch einen Platzhalter ersetzen koennen. Der Demo-Trainer braucht kein
 * Geraet — mit ihm geht es immer weiter.
 */
@Composable
fun IndoorPairScreen(
    ui: IndoorPairUi,
    devicesSection: @Composable () -> Unit,
    onDemoChange: (Boolean) -> Unit,
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
