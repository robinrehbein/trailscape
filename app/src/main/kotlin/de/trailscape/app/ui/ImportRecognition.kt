package de.trailscape.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.ui.components.OneUiDialog

/**
 * Was eine per „Teilen"/„Öffnen mit" gereichte Datei war — als Planung oder
 * als gefahrene Tour erkannt (Regeln: `TrackOrigin.kt` in `:core`).
 */
data class ImportRecognition(val rideId: String, val name: String, val planned: Boolean)

/**
 * Haengt den Hinweis [AppViewModel.importRecognition] in die Huelle: Er steht
 * ueber jedem Bildschirm, weil der Import von aussen kommt und die App dabei
 * auf der Karte oder im Verlauf landet. Erst nach der Einfuehrung komponiert
 * (siehe `TrailscapeAppContent`).
 */
@Composable
fun ImportRecognitionHost(appViewModel: AppViewModel) {
    val recognition by appViewModel.importRecognition.collectAsStateWithLifecycle()
    recognition?.let {
        ImportRecognitionDialog(
            recognition = it,
            onKeep = appViewModel::dismissImportRecognition,
            onFlip = appViewModel::flipImportRecognition,
        )
    }
}

/**
 * Der Dialog selbst (zustandslos, damit er ohne ViewModel in einem
 * Screenshot-Test steht): sagt, als was die Datei erkannt wurde und warum,
 * und bietet **einmalig** das Umdrehen an. Danach ist es eine gewoehnliche
 * Tour bzw. Planung, die sich nur noch in der Tourdetailansicht umwandeln
 * laesst.
 */
@Composable
fun ImportRecognitionDialog(
    recognition: ImportRecognition,
    onKeep: () -> Unit,
    onFlip: () -> Unit,
) {
    val planned = recognition.planned
    OneUiDialog(
        onDismissRequest = onKeep,
        title = {
            Text(
                stringResource(
                    if (planned) R.string.gpxplan_recognized_planned_title else R.string.gpxplan_recognized_ridden_title,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        if (planned) R.string.gpxplan_recognized_planned_body else R.string.gpxplan_recognized_ridden_body,
                        recognition.name,
                    ),
                )
                Text(
                    stringResource(R.string.gpxplan_flip_once_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.common_action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onFlip) {
                Text(
                    stringResource(
                        if (planned) R.string.gpxplan_flip_to_ridden_action else R.string.gpxplan_flip_to_planned_action,
                    ),
                )
            }
        },
    )
}
