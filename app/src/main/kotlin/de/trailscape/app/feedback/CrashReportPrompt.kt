package de.trailscape.app.feedback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fragt beim Start nach, wenn beim letzten Mal ein Absturzbericht
 * liegengeblieben ist (siehe [CrashReporter]).
 *
 * Haengt in `MainActivity.onCreate` **neben** `TrailscapeApp()` und nicht
 * darin: Der Dialog ist eine Angelegenheit der Activity, kein Bestandteil der
 * Navigationshuelle (siehe Zustaendigkeits-KDoc in `ui/TrailscapeApp.kt` —
 * diese Datei wird von Screen-Arbeiten nicht angefasst). Ein `AlertDialog`
 * belegt keinen Platz im Layout, er zeichnet in ein eigenes Fenster.
 *
 * Unaufdringlich heisst hier: einmal fragen, jeder Ausgang ist erlaubt. Wer
 * „Schließen" tippt oder neben den Dialog, behaelt den Bericht — er kommt beim
 * naechsten Start wieder. Wer „Verwerfen" tippt, ist ihn los.
 *
 * Enthaelt der Bericht einen Diagnose-Abschnitt (siehe [CrashReporter]),
 * erscheint dieselbe vorausgewaehlte Checkbox wie im Problembericht. Der
 * Bericht wurde im Absturz schon fertig geschrieben; abgewaehlt wird der
 * Abschnitt deshalb nachtraeglich abgeschnitten ([withoutDiagSection]) — der
 * Titel haengt nur am Stacktrace und bleibt gleich.
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var attachDiag by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // Datei-I/O gehoert nicht auf den Main-Thread, auch wenn es hier um
        // wenige Kilobyte geht.
        report = withContext(Dispatchers.IO) { CrashReporter.readPendingReport(context) }
    }

    val pending = report
    if (pending == null || dismissed) return

    ReportDialog(
        title = "Absturz beim letzten Mal",
        intro = "Trailscape ist beim letzten Mal abgestürzt. Bericht ansehen und auf " +
            "GitHub melden? Der Bericht wurde nur auf diesem Gerät gespeichert und " +
            "bisher nirgendwohin gesendet — er enthält keine Standort-, Touren- oder " +
            "Gesundheitsdaten.",
        reportText = if (attachDiag) pending else withoutDiagSection(pending),
        issueTitle = crashIssueTitleFromReport(pending),
        reportHeading = "Absturzbericht",
        shareSubject = "Trailscape-Absturzbericht",
        onDismiss = { dismissed = true },
        onDiscard = {
            CrashReporter.clearPendingReport(context)
            dismissed = true
            showFeedbackToast(context, "Absturzbericht gelöscht.")
        },
        extraContent = {
            if (hasDiagSection(pending)) {
                DiagAttachmentCheckbox(checked = attachDiag, onCheckedChange = { attachDiag = it })
            }
        },
    )
}
