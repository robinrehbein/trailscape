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
import androidx.compose.ui.unit.dp
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
        title = "Problem melden",
        intro = "Trailscape hat keine Fehler-Telemetrie — ohne deine Meldung erfährt " +
            "niemand von einem Problem. Angehängt werden App-Version, Gerät, " +
            "Android-Version und – solange der Haken unten gesetzt ist – die technische " +
            "Diagnose. Du siehst den kompletten Text, bevor du ihn absendest; ohne " +
            "deinen Klick verlässt nichts das Gerät.",
        reportText = report,
        issueTitle = PROBLEM_ISSUE_TITLE,
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
                        showFeedbackToast(context, "Diagnose-Protokoll gelöscht.")
                    }
                },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("Diagnose-Protokoll löschen")
            }
            if (healthDiagnostics.isNotEmpty()) {
                LabeledCheckbox(
                    checked = attachHealth,
                    onCheckedChange = { attachHealth = it },
                    label = "Health-Sync-Diagnose anhängen",
                )
            }
        },
    )
}

/**
 * Text, der ehrlich sagt, was die technische Diagnose enthaelt — und was
 * nicht. Gemeinsam fuer Problem- und Absturzbericht, damit beide Dialoge
 * dasselbe versprechen.
 */
const val DIAG_ATTACHMENT_EXPLANATION: String =
    "Enthält feste Ereignisnamen (z. B. „GPS-Start fehlgeschlagen“), Uhrzeiten, " +
        "Zähler, Fehlercodes und die Namen von Fehlerklassen der letzten Tage. " +
        "Nicht enthalten: Standorte, Touren und ihre Namen, Gesundheitswerte, " +
        "Server-Adressen oder Zugangsdaten."

/** Die vorausgewaehlte Diagnose-Checkbox samt Erklaerung. */
@Composable
internal fun DiagAttachmentCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    LabeledCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = "Technische Diagnose anhängen",
    )
    Text(
        text = DIAG_ATTACHMENT_EXPLANATION,
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
