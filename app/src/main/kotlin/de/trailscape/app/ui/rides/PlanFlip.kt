package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.OneUiDialog

/**
 * Tourdetailansicht: Eine Tour in eine Planung umwandeln — und zurueck.
 *
 * Umgewandelt wird im Menue der Tour (⋮, `RideDetailScreen.kt`), mit der
 * Rueckfrage [PlanFlipConfirmDialog]. Die Umwandlung kann jede
 * Nicht-Indoor-Tour: Wer eine Planung als Fahrt zaehlen will (oder umgekehrt),
 * braucht dafuer keinen Umweg ueber Loeschen und Neuimport.
 *
 * Im Inhalt bleibt nur dieser Hinweis „Diese Tour hat keine Zeitstempel …".
 * Er steht, wenn die Punkte nach `classifyTrackOrigin` (`:core`) eine Route
 * sind; so findet man eine **frueher** faelschlich als Tour importierte Route
 * (vor der Erkennung kamen alle GPX als Tour herein) — und er sagt, wo sich
 * das beheben laesst.
 */
@Composable
fun PlanFlipRouteHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.gpxplan_detail_route_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.rides_detail_route_hint_menu),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
