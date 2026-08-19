package de.trailscape.app.ui.map

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
        title = { Text("Aufzeichnung im Hintergrund schützen") },
        text = {
            Text(
                "Manche Geräte beenden die GPS-Aufzeichnung, sobald der Bildschirm " +
                    "länger aus ist — die Tour bricht dann unbemerkt ab. Eine Ausnahme " +
                    "von der Batterieoptimierung verhindert das. Die Aufzeichnung läuft " +
                    "jetzt trotzdem los; ändern lässt sich das jederzeit unter " +
                    "„Mehr → Aufzeichnung“.",
            )
        },
        confirmButton = {
            TextButton(onClick = onAllow) { Text("Ausnahme erlauben") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("Später") }
        },
    )
}
