package de.trailscape.app.feedback

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.core.DiagLog
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * „Problem melden" aus dem Mehr-Screen (siehe `ui/more/AboutCard.kt`).
 *
 * Derselbe Weg wie beim Absturzbericht ([CrashReportPrompt]) — nur ohne
 * Stacktrace und dafuer mit zwei optionalen Anhaengen:
 *
 *  * **Technische Diagnose** (`core/DiagLog.kt`) — standardmaessig **an**.
 *    Entscheidung des Projekts: Ohne sie sind die meisten Meldungen („zeichnet
 *    manchmal nichts auf") nicht nachvollziehbar, und das Log enthaelt
 *    konstruktionsbedingt nur feste Ereignisnamen, Zahlen und Fehlerklassen.
 *    Der Haken ist abwaehlbar, und der Nutzer sieht den kompletten Text vor
 *    dem Absenden.
 *  * **Health-Sync-Diagnose** des letzten Syncs — standardmaessig **aus**.
 *    Die ist bei „bei mir kommt nichts an"-Meldungen das Entscheidende, nennt
 *    aber Zeitraeume und Quell-Apps und gehoert daher nicht ungefragt in einen
 *    oeffentlichen Issue.
 *
 * @param healthDiagnostics `debugLines` des letzten Health-Sync-Reports. Ist
 *   die Liste leer, erscheint die zugehoerige Checkbox gar nicht erst.
 */
@Composable
fun ProblemReportDialog(
    healthDiagnostics: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachHealth by remember { mutableStateOf(false) }
    var attachDiag by remember { mutableStateOf(true) }
    var diagLines by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Datei-I/O (bis 128 KB) gehoert nicht auf den Main-Thread.
        diagLines = withContext(Dispatchers.IO) { selectDiagLines(DiagLog.shared.readAll()) }
    }

    // Geraetedaten und Zeitstempel einmal pro Dialog — nicht bei jedem
    // Umschalten der Checkbox neu erfragen.
    val deviceInfo = remember { CrashReporter.currentDeviceInfo(context) }
    val timestamp = remember {
        formatReportTimestamp(System.currentTimeMillis(), ZoneId.systemDefault())
    }

    val report = buildProblemReport(
        info = deviceInfo,
        timestamp = timestamp,
        healthDiagnostics = if (attachHealth) healthDiagnostics else emptyList(),
        diagLines = if (attachDiag) diagLines else null,
    )

    ReportDialog(
        title = stringResource(R.string.more_report_title),
        intro = stringResource(R.string.more_report_intro),
        reportText = report,
        issueTitle = PROBLEM_ISSUE_TITLE,
        // Ueberschrift im Issue-Text und Betreff beim Teilen gehoeren zum
        // Bericht an den Entwickler und bleiben deshalb deutsch (siehe
        // docs/i18n.md, „Diagnose bleibt Deutsch").
        reportHeading = "Technische Angaben",
        shareSubject = "Trailscape-Problembericht",
        onDismiss = onDismiss,
        extraContent = {
            DiagAttachmentCheckbox(checked = attachDiag, onCheckedChange = { attachDiag = it })
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AppDiagnostics.clear() }
                        diagLines = emptyList()
                        showFeedbackToast(context, context.getString(R.string.more_report_diag_cleared_status))
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(stringResource(R.string.more_report_diag_clear_action))
            }
            if (healthDiagnostics.isNotEmpty()) {
                LabeledCheckbox(
                    checked = attachHealth,
                    onCheckedChange = { attachHealth = it },
                    label = stringResource(R.string.more_report_health_attach_label),
                )
            }
        },
    )
}

/**
 * Die vorausgewaehlte Diagnose-Checkbox samt Erklaerung — einem Text, der
 * ehrlich sagt, was die technische Diagnose enthaelt und was nicht
 * (`more_report_diag_explanation`). Gemeinsam fuer Problem- und
 * Absturzbericht, damit beide Dialoge dasselbe versprechen. Das Beispiel
 * darin („GPS-Start fehlgeschlagen") bleibt auch im Englischen deutsch: So
 * steht der Ereignisname tatsaechlich im Protokoll.
 */
@Composable
internal fun DiagAttachmentCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    LabeledCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = stringResource(R.string.more_report_diag_attach_label),
    )
    Text(
        text = stringResource(R.string.more_report_diag_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabeledCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
