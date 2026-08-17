package de.trailscape.app.feedback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.OneUiDialog

/**
 * Der gemeinsame Melde-Dialog beider Wege (Absturz und „Problem melden").
 *
 * Ein Dialog fuer beides, weil die Entscheidung, die der Nutzer trifft, in
 * beiden Faellen dieselbe ist: *Was steht da eigentlich drin — und will ich
 * das ueberhaupt verschicken?* Deshalb ist der vollstaendige Bericht immer
 * einsehbar (aufklappbar, markierbar), bevor irgendein Knopf ihn irgendwohin
 * traegt.
 *
 * Bewusst **kein** Absenden aus der App heraus: „Auf GitHub melden" oeffnet
 * nur das vorbefuellte Formular im Browser, „Teilen" das System-Teilen-Menue.
 * Beides bricht der Nutzer dort noch ab, wenn er will.
 *
 * @param reportText der fertige Berichtstext. Aendert sich er (z. B. weil ein
 *   Anhang an- oder abgewaehlt wurde), zeigt der Dialog sofort den neuen Text.
 * @param onDiscard `null` blendet „Verwerfen" aus — der Problem-Bericht hat
 *   nichts zu verwerfen, der Absturzbericht schon (dort loescht er die Datei).
 * @param extraContent Platz fuer dialogspezifische Bedienelemente (etwa die
 *   Diagnose-Checkbox beim Problem-Bericht).
 */
@Composable
fun ReportDialog(
    title: String,
    intro: String,
    reportText: String,
    issueTitle: String,
    reportHeading: String,
    shareSubject: String,
    onDismiss: () -> Unit,
    onDiscard: (() -> Unit)? = null,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = intro, style = MaterialTheme.typography.bodyMedium)

                extraContent()

                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (expanded) "Bericht ausblenden" else "Bericht anzeigen")
                }

                if (expanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Eigener Scroll-Bereich mit Obergrenze: Ein langer
                        // Stacktrace darf die Aktionsknoepfe nicht aus dem
                        // Dialog schieben.
                        SelectionContainer {
                            Text(
                                text = reportText,
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        if (!shareReportText(context, shareSubject, reportText)) {
                            showFeedbackToast(context, "Keine App zum Teilen gefunden.")
                        }
                    },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Teilen (ohne GitHub-Konto)")
                }

                if (onDiscard != null) {
                    TextButton(
                        onClick = onDiscard,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Verwerfen")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val body = buildIssueBody(reportText, reportHeading)
                    if (!openIssueInBrowser(context, issueTitle, body)) {
                        showFeedbackToast(context, "Kein Browser gefunden — bitte „Teilen“ benutzen.")
                    }
                },
            ) {
                Text("Auf GitHub melden")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
    )
}
