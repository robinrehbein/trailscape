package de.trailscape.app.feedback

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/**
 * „Problem melden" aus dem Mehr-Screen (siehe `ui/more/AboutCard.kt`).
 *
 * Derselbe Weg wie beim Absturzbericht ([CrashReportPrompt]) — nur ohne
 * Stacktrace und dafuer mit einem optionalen Anhang: der Diagnose des letzten
 * Health-Syncs. Die ist bei den haeufigsten „bei mir kommt nichts an"-Meldungen
 * das Entscheidende, gehoert aber nicht ungefragt in einen oeffentlichen
 * Issue — deshalb eine Checkbox, standardmaessig **aus**.
 *
 * @param healthDiagnostics `debugLines` des letzten Health-Sync-Reports. Ist
 *   die Liste leer, erscheint die Checkbox gar nicht erst.
 */
@Composable
fun ProblemReportDialog(
    healthDiagnostics: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var attachDiagnostics by remember { mutableStateOf(false) }

    // Geraetedaten und Zeitstempel einmal pro Dialog — nicht bei jedem
    // Umschalten der Checkbox neu erfragen.
    val deviceInfo = remember { CrashReporter.currentDeviceInfo(context) }
    val timestamp = remember {
        formatReportTimestamp(System.currentTimeMillis(), ZoneId.systemDefault())
    }

    val report = buildProblemReport(
        info = deviceInfo,
        timestamp = timestamp,
        healthDiagnostics = if (attachDiagnostics) healthDiagnostics else emptyList(),
    )

    ReportDialog(
        title = "Problem melden",
        intro = "Trailscape hat keine Fehler-Telemetrie — ohne deine Meldung erfährt " +
            "niemand von einem Problem. Angehängt werden nur App-Version, Gerät und " +
            "Android-Version; du siehst den kompletten Text, bevor du ihn absendest.",
        reportText = report,
        issueTitle = PROBLEM_ISSUE_TITLE,
        reportHeading = "Technische Angaben",
        shareSubject = "Trailscape-Problembericht",
        onDismiss = onDismiss,
        extraContent = {
            if (healthDiagnostics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = attachDiagnostics,
                        onCheckedChange = { attachDiagnostics = it },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Health-Sync-Diagnose anhängen",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}
