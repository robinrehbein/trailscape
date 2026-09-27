package de.trailscape.app.ui.map

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.app.ui.components.OneUiDialog

/**
 * Einmaliger Hinweis auf die Batterieoptimierung beim Start einer
 * Aufzeichnung (siehe `record/RecordingSettings.kt` fuer den Hintergrund):
 * Manche Geraete beenden GPS-Aufzeichnungen im Hintergrund, die Ausnahme von
 * der Batterieoptimierung ist das offizielle Mittel dagegen.
 *
 * Bewusst **nicht blockierend**: Die Aufzeichnung laeuft beim Erscheinen
 * dieses Dialogs bereits — „Später" verliert nichts ausser der Ausnahme, und
 * der Dialog kommt hoechstens einmal automatisch (Prefs-Merker, siehe
 * Aufrufstelle in `MapScreen.kt`). Danach fuehrt der Weg ueber Mehr →
 * Aufzeichnung (`ui/more/RecordingCard.kt`).
 *
 * @param onAllow „Ausnahme erlauben" — der Aufrufer startet den Systemdialog
 *   (`batterieAusnahmeIntent`) und schliesst diesen hier.
 * @param onLater „Später" bzw. Wegtippen — nur schliessen und merken.
 */
@Composable
internal fun BatteryNoticeDialog(
    onAllow: () -> Unit,
    onLater: () -> Unit,
) {
    OneUiDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.map_battery_title)) },
        text = {
            Text(stringResource(R.string.map_battery_body))
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.map_battery_allow_action)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.map_battery_later_action)) }
        },
    )
}
