package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.OneUiDialog

/**
 * Tourdetailansicht: Eine Tour in eine Planung umwandeln — und zurueck.
 *
 * Der Hinweis „Diese Tour hat keine Zeitstempel …" steht nur, wenn die
 * Punkte nach `classifyTrackOrigin` (`:core`) eine Route sind; so findet man
 * eine **frueher** faelschlich als Tour importierte Route (vor der Erkennung
 * kamen alle GPX als Tour herein). Die Umwandlung selbst kann jede
 * Nicht-Indoor-Tour: Wer eine Planung als Fahrt zaehlen will (oder
 * umgekehrt), braucht dafuer keinen Umweg ueber Loeschen und Neuimport.
 *
 * @param looksLikeRoute die Punkte haben keine echten Zeitstempel
 * @param onFlip `true` = zur Planung machen, `false` = als Tour uebernehmen
 */
@Composable
fun PlanFlipSection(
    planned: Boolean,
    looksLikeRoute: Boolean,
    rideName: String,
    onFlip: (toPlanned: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!planned && looksLikeRoute) {
            Text(
                stringResource(R.string.gpxplan_detail_route_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (planned) R.string.gpxplan_detail_to_ridden_action else R.string.gpxplan_detail_to_planned_action,
                ),
            )
        }
    }
    if (confirm) {
        PlanFlipConfirmDialog(
            toPlanned = !planned,
            rideName = rideName,
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                onFlip(!planned)
            },
        )
    }
}

/** Die Rueckfrage vor dem Umwandeln (zustandslos, fuer Screenshot-Tests). */
@Composable
fun PlanFlipConfirmDialog(
    toPlanned: Boolean,
    rideName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (toPlanned) R.string.gpxplan_to_planned_confirm_title else R.string.gpxplan_to_ridden_confirm_title,
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (toPlanned) R.string.gpxplan_to_planned_confirm_body else R.string.gpxplan_to_ridden_confirm_body,
                    rideName,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (toPlanned) R.string.gpxplan_to_planned_confirm_action else R.string.gpxplan_to_ridden_confirm_action,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) }
        },
    )
}
