package de.trailscape.app.ui.more

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.UNREADABLE_FILE_MESSAGE
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.withCause
import de.trailscape.core.BulkImportResult
import de.trailscape.core.FormatException
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.importArchive
import de.trailscape.core.scanArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Der Archiv-Import (ZIP mit GPX-/FIT-Dateien, etwa der Strava-, Garmin- oder
 * Wahoo-Export) als fertig verdrahtete Aktion — das Gegenstueck zu
 * `ui/ActivityImportAction.kt` fuer einzelne Dateien.
 *
 * Herausgeloest aus der Backup-Seite, damit der Verlauf („+" → Archiv) ihn
 * direkt starten kann, statt in die Einstellungen zu springen, wo man den
 * Knopf noch einmal suchen musste (`docs/ux/seitenkarte.md`, Befund 9).
 */
class ArchiveImportAction(
    /** Laeuft gerade ein Archiv-Import? Knoepfe sind dann gesperrt. */
    val importing: Boolean,
    /** Oeffnet die Dateiauswahl — wirkungslos, solange [importing] steht. */
    val start: () -> Unit,
)

/**
 * Baut den Archiv-Import samt SAF-Launcher, Fortschritts- und Ergebnisdialog
 * auf. Die Dialoge werden hier mit ausgegeben (Compose-Dialoge oeffnen ein
 * eigenes Fenster, ihr Platz im Baum ist egal).
 *
 * ## Ablauf
 * Der ZIP-Stream wird zweimal geoeffnet: einmal fuer [scanArchive] (nur die
 * Eintragsnamen, um den Nenner fuer die Fortschrittsanzeige zu kennen), einmal
 * fuer den eigentlichen, gestreamten Import. Scheitert das Vor-Oeffnen
 * (mancher Anbieter erlaubt keinen zweiten `openInputStream`-Aufruf auf
 * demselben `Uri`), faellt die Anzeige auf einen unbestimmten Fortschritt
 * zurueck statt abzubrechen — der Import selbst braucht den Nenner nicht.
 *
 * ## Nicht abbrechbar
 * Der Fortschritts-Dialog hat bewusst keinen Abbrechen-Knopf
 * (`onDismissRequest = {}`): [importArchive] in `:core` ist eine einzelne
 * blockierende Funktion ohne Abbruchpunkte — ein `Job.cancel()` wuerde erst
 * greifen, wenn der Aufruf ohnehin fertig ist, also nur die UI faelschlich
 * „abgebrochen" zeigen, waehrend der Import im Hintergrund weiterlaeuft.
 *
 * Meldungen (unlesbare Datei) laufen ueber [AppViewModel.showMessage]; der
 * aufrufende Bildschirm zeigt sie in seiner Snackbar.
 */
@Composable
fun rememberArchiveImportAction(appViewModel: AppViewModel): ArchiveImportAction {
    val coreTexts = LocalCoreTexts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    var archiveBusy by remember { mutableStateOf(false) }
    var archiveDone by remember { mutableIntStateOf(0) }
    var archiveTotal by remember { mutableStateOf<Int?>(null) }
    var archiveResult by remember { mutableStateOf<BulkImportResult?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            archiveBusy = true
            archiveDone = 0
            archiveTotal = null
            try {
                archiveTotal = withContext(Dispatchers.IO) { tryScanArchiveTotal(context, uri, coreTexts) }

                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        importArchive(
                            input = stream,
                            existing = rides,
                            total = archiveTotal,
                            texts = coreTexts,
                            onProgress = { done, total ->
                                archiveDone = done
                                // `importArchive` meldet ohne bekannten Nenner
                                // `total == done` (siehe :core-KDoc) — das
                                // wuerde die Anzeige faelschlich auf
                                // "bestimmt" umschalten, darum hier ignoriert.
                                if (archiveTotal != null) archiveTotal = total
                            },
                        )
                    } ?: throw FormatException(UNREADABLE_FILE_MESSAGE)
                }

                if (result.rides.isNotEmpty()) appViewModel.addRides(result.rides)
                archiveResult = result
            } catch (e: FormatException) {
                appViewModel.showMessage(e.message ?: UNREADABLE_FILE_MESSAGE)
            } catch (e: Exception) {
                appViewModel.showMessage(
                    withCause(context.getString(R.string.more_backup_archive_read_error), e),
                )
            } finally {
                archiveBusy = false
            }
        }
    }

    if (archiveBusy) {
        ArchiveImportProgressDialog(done = archiveDone, total = archiveTotal)
    }

    archiveResult?.let { result ->
        ArchiveImportResultDialog(result = result, onDismiss = { archiveResult = null })
    }

    val importing = archiveBusy
    return ArchiveImportAction(
        importing = importing,
        start = { if (!importing) launcher.launch(arrayOf("application/zip", "*/*")) },
    )
}

/**
 * Ermittelt die Gesamtzahl importierbarer Eintraege vorab ueber [scanArchive],
 * damit der Fortschritts-Dialog einen echten Nenner hat. Liefert `null`, wenn
 * sich die Datei kein zweites Mal oeffnen laesst oder kein ZIP ist — dann
 * bleibt die Anzeige unbestimmt, der eigentliche Import scheitert (falls
 * ueberhaupt) erst beim zweiten, tatsaechlich verwendeten Stream.
 */
private fun tryScanArchiveTotal(context: Context, uri: Uri, texts: CoreTexts): Int? =
    try {
        context.contentResolver.openInputStream(uri)?.use { scanArchive(it, texts).size }
    } catch (e: Exception) {
        null
    }

/** Nicht schliessbarer Fortschritts-Dialog fuer den Archiv-Import — siehe Karten-KDoc. */
@Composable
private fun ArchiveImportProgressDialog(done: Int, total: Int?) {
    OneUiDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.more_backup_archive_progress_title)) },
        text = {
            Column {
                val knownTotal = total?.takeIf { it > 0 }
                if (knownTotal != null) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / knownTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.more_backup_archive_progress_known, done, knownTotal))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.more_backup_archive_progress_unknown, done))
                }
            }
        },
        confirmButton = {},
    )
}

/** Ergebnis-Dialog fuer den Archiv-Import: Zahlen plus aufklappbare Fehlerliste. */
@Composable
private fun ArchiveImportResultDialog(result: BulkImportResult, onDismiss: () -> Unit) {
    var showErrors by remember { mutableStateOf(false) }

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.more_backup_archive_done_title)) },
        text = {
            Column {
                Text(
                    pluralStringResource(
                        R.plurals.more_backup_archive_imported_count,
                        result.importedCount,
                        result.importedCount,
                    ),
                )

                if (result.duplicateCount > 0) {
                    Text(
                        pluralStringResource(
                            R.plurals.more_backup_archive_duplicates_count,
                            result.duplicateCount,
                            result.duplicateCount,
                        ),
                    )
                }

                if (result.errorCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.more_backup_archive_errors_count,
                            result.errorCount,
                            result.errorCount,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { showErrors = !showErrors }) {
                        Text(
                            stringResource(
                                if (showErrors) {
                                    R.string.more_backup_archive_errors_hide_action
                                } else {
                                    R.string.more_backup_archive_errors_show_action
                                },
                            ),
                        )
                    }
                    if (showErrors) {
                        Box(modifier = Modifier.heightIn(max = 240.dp)) {
                            SelectionContainer {
                                Text(
                                    text = result.errors.joinToString("\n\n") {
                                        "${it.path}\n${it.message}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }

                if (result.totalCount == 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.more_backup_archive_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) }
        },
    )
}
