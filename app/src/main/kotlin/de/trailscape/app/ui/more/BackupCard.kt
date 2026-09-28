package de.trailscape.app.ui.more

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.UNREADABLE_FILE_MESSAGE
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.rememberActivityImportAction
import de.trailscape.app.ui.withCause
import de.trailscape.core.BulkImportResult
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.FormatException
import de.trailscape.core.backupFileName
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.importArchive
import de.trailscape.core.parseBackupJson
import de.trailscape.core.scanArchive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * „Daten & Backup"-Karte — Port von `_buildDataBackupCard()` (und den
 * zugehoerigen `_exportBackup`/`_importBackup`/`_importGpxFile`-Methoden) aus
 * `lib/screens/more_screen.dart`, erweitert um FIT- und Archiv-Import (kein
 * Dart-Vorbild — siehe `:core`, `Fit.kt`/`BulkImport.kt`).
 *
 * Nutzt das Storage Access Framework statt `file_picker`/`share_plus`: Export
 * geht ueber [ActivityResultContracts.CreateDocument] (Nutzerin waehlt den
 * Speicherort direkt, kein Zwischenschritt ueber ein Share-Sheet noetig),
 * Import ueber [ActivityResultContracts.OpenDocument]. Der Datei-Import
 * („Touren importieren", Mehrfachauswahl) ist dieselbe Aktion wie im Verlauf
 * (siehe `ui/ActivityImportAction.kt`).
 *
 * ## Archiv-Import
 * „Archiv importieren (ZIP)" oeffnet den ZIP-Stream zweimal: einmal fuer
 * [scanArchive] (nur die Eintragsnamen, um den Nenner fuer die
 * Fortschrittsanzeige zu kennen — die zweite Variante von
 * [de.trailscape.core.importArchive] macht das ebenso, akzeptiert dafuer aber
 * nur ein bereits komplett geladenes [ByteArray]), einmal fuer den
 * eigentlichen, gestreamten Import. Scheitert das Vor-Oeffnen (mancher
 * Anbieter erlaubt keinen zweiten `openInputStream`-Aufruf auf demselben
 * `Uri`), faellt die Anzeige auf einen unbestimmten Fortschritt zurueck statt
 * abzubrechen — der Import selbst braucht den Nenner nicht.
 *
 * Der Fortschritts-Dialog ist bewusst **nicht** abbrechbar (kein
 * Abbrechen-Knopf, `onDismissRequest = {}`): `importArchive` in `:core` ist
 * eine einzelne blockierende Funktion ohne Abbruchpunkte (kein
 * `isActive`/`ensureActive` je Eintrag) — ein `Job.cancel()` wuerde erst
 * greifen, wenn der Aufruf ohnehin fertig ist, also nur die UI faelschlich
 * „abgebrochen" zeigen, waehrend der Import im Hintergrund weiterlaeuft. Bei
 * Bedarf muesste [de.trailscape.core.importArchive] dafuer erst eine
 * Abbruchpruefung bekommen (`:core`, ausserhalb dieses Auftrags).
 *
 * Die Aktions-Buttons tragen inzwischen Icons (anders als vorher: `:app` band
 * damals nur `material-icons-core` ein, dessen kleiner Symbolsatz weder ein
 * Save- noch ein Routen-Symbol enthielt). Seit `material-icons-extended`
 * eingebunden ist, stehen beide zur Verfuegung.
 *
 * Der Inhalt der Seite „Import & Backup" der Einstellungen (siehe
 * `MoreScreen.kt`).
 *
 * ## Letzte Sicherung
 * Ein erfolgreicher Export merkt sich seinen Zeitpunkt ([lastBackupAt]); die
 * Listenzeile zeigt daraus „Zuletzt: 12.9." oder — in der Warnfarbe — „Noch
 * nie gesichert". Die App ist local-first: Ohne Sync-Server ist diese Datei
 * die einzige Kopie der Touren ausserhalb des Telefons.
 */
@Composable
fun BackupCardContent(appViewModel: AppViewModel) {
    val coreTexts = LocalCoreTexts.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }

    // Archiv-Import: eigener Zustand, weil er zusaetzlich einen
    // Fortschritts- und einen Ergebnis-Dialog braucht.
    var archiveBusy by remember { mutableStateOf(false) }
    var archiveDone by remember { mutableIntStateOf(0) }
    var archiveTotal by remember { mutableStateOf<Int?>(null) }
    var archiveResult by remember { mutableStateOf<BulkImportResult?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                writeBackupFile(context, uri, appViewModel)
                setLastBackupAt(context, System.currentTimeMillis())
                appViewModel.showMessage(UiText.Res(R.string.more_backup_exported_status))
            } catch (e: Exception) {
                // Verstaendlicher Satz zuerst, technische Ursache nur in
                // Klammern — siehe ui/ErrorText.kt.
                appViewModel.showMessage(
                    withCause(context.getString(R.string.more_backup_write_error), e),
                )
            } finally {
                busy = false
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val raw = readTextFile(context, uri)
                val data = parseBackupJson(raw, coreTexts)

                val existingIds = rides.map { it.id }.toSet()
                val newRides = data.rides.filter { it.id !in existingIds }
                val skipped = data.rides.size - newRides.size

                appViewModel.addRides(newRides)
                data.profile?.let { appViewModel.setProfile(it) }
                DiagLog.shared.log(DiagEvent.BACKUP_IMPORT_OK, count = newRides.size.toLong())

                appViewModel.showMessage(backupImportMessage(newRides.size, skipped, data.profile != null))
            } catch (e: FormatException) {
                DiagLog.shared.log(DiagEvent.BACKUP_IMPORT_FAILED, error = e)
                appViewModel.showMessage(e.message ?: UNREADABLE_FILE_MESSAGE)
            } catch (e: Exception) {
                DiagLog.shared.log(DiagEvent.BACKUP_IMPORT_FAILED, error = e)
                appViewModel.showMessage(
                    withCause(context.getString(R.string.more_backup_read_error), e),
                )
            } finally {
                busy = false
            }
        }
    }

    // Import einzelner Aktivitaetsdateien (GPX oder FIT, je auch `.gz`,
    // mehrere auf einmal) — dieselbe fertig verdrahtete Aktion wie im
    // Verlauf (`ui/ActivityImportAction.kt`): Mehrfachauswahl, Import ueber
    // das ViewModel, Ergebnis-Snackbar und stehender Fehlerdialog. Frueher
    // hatte die Karte eine eigene Kopie davon, die nur eine Datei nahm.
    val activityImport = rememberActivityImportAction(appViewModel)

    // Massenimport aus einem ZIP-Archiv (Strava-/Garmin-/Wahoo-Export) — siehe
    // KDoc der Karte oben fuer den Ablauf und die Abbrechbarkeits-Entscheidung.
    val importArchiveLauncher = rememberLauncherForActivityResult(
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

    SettingsHint(stringResource(R.string.more_backup_hint))
    Spacer(modifier = Modifier.height(8.dp))
    // Ehrlicher Hinweis statt Modal-Dialog: Die Export-Datei ist bewusst
    // unverschluesseltes Klartext-JSON (lesbar, importierbar, zukunftssicher) —
    // aber genau deshalb muss hier stehen, was drinsteckt, BEVOR jemand sie
    // per Mail oder Cloud weiterreicht. Siehe PRIVACY.md, Abschnitt 7.
    SettingsHint(stringResource(R.string.more_backup_privacy_hint))
    Spacer(modifier = Modifier.height(12.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { exportLauncher.launch(backupFileName(LocalDate.now())) },
            enabled = !busy,
        ) {
            Icon(
                Icons.Filled.Save,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.more_backup_export_action))
        }
        SettingsSecondaryButton(
            onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
            enabled = !busy,
        ) {
            Icon(
                Icons.Filled.Upload,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.more_backup_import_action))
        }
        SettingsSecondaryButton(
            onClick = activityImport.start,
            enabled = !busy && !activityImport.importing,
        ) {
            Icon(
                Icons.Filled.Route,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.more_backup_import_rides_action))
        }
        SettingsSecondaryButton(
            onClick = { importArchiveLauncher.launch(arrayOf("application/zip", "*/*")) },
            enabled = !busy && !archiveBusy,
        ) {
            Icon(
                Icons.Filled.FolderZip,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.more_backup_import_archive_action))
        }
    }

    if (busy || activityImport.importing) {
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }

    if (archiveBusy) {
        ArchiveImportProgressDialog(done = archiveDone, total = archiveTotal)
    }

    archiveResult?.let { result ->
        ArchiveImportResultDialog(result = result, onDismiss = { archiveResult = null })
    }
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

/**
 * Die Meldung nach einem Backup-Import: „3 Touren importiert, 1 übersprungen
 * · Profil übernommen". Die Zahl der Touren bestimmt die Pluralform; das
 * uebernommene Profil haengt als eigener Teil hinten an.
 */
internal fun backupImportMessage(imported: Int, skipped: Int, profileRestored: Boolean): UiText {
    val count = if (skipped > 0) {
        UiText.Plural(R.plurals.more_backup_imported_skipped_count, imported, listOf(imported, skipped))
    } else {
        UiText.Plural(R.plurals.more_backup_imported_count, imported)
    }
    return if (profileRestored) UiText.Res(R.string.more_backup_imported_with_profile, listOf(count)) else count
}

/** Liest die gewaehlte Datei komplett als UTF-8-Text. Laeuft auf [Dispatchers.IO]. */
private suspend fun readTextFile(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes().toString(Charsets.UTF_8)
    } ?: throw FormatException(UNREADABLE_FILE_MESSAGE)
}

/**
 * Schreibt das Backup-JSON **streamend** in das vom SAF gewaehlte Ziel: Tour
 * fuer Tour ueber [AppViewModel.writeBackup] geladen und geschrieben — der
 * Gesamtdump entsteht nie als ein String im Speicher (frueher hielt
 * `buildBackupJson` bei grossen Bestaenden hunderte MB fest). Laeuft auf
 * [Dispatchers.IO].
 */
private suspend fun writeBackupFile(
    context: Context,
    uri: Uri,
    appViewModel: AppViewModel,
) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri)?.use { stream ->
        java.io.BufferedWriter(java.io.OutputStreamWriter(stream, Charsets.UTF_8)).use { writer ->
            appViewModel.writeBackup(writer)
        }
    } ?: throw IllegalStateException(context.getString(R.string.more_backup_file_write_error))
}

/** Schluessel des Zeitpunkts der letzten erfolgreichen Sicherung (Long, Epoch-ms). */
private const val PREF_LAST_BACKUP_AT = "trailscape.backup.lastExportAtMs"

/** Wann zuletzt erfolgreich ein Backup exportiert wurde, oder `null` = noch nie. */
internal fun lastBackupAt(context: Context): LocalDateTime? {
    val prefs = trailscapePrefs(context)
    if (!prefs.contains(PREF_LAST_BACKUP_AT)) return null
    return LocalDateTime.ofInstant(
        Instant.ofEpochMilli(prefs.getLong(PREF_LAST_BACKUP_AT, 0L)),
        ZoneId.systemDefault(),
    )
}

/** Merkt sich den Zeitpunkt einer erfolgreichen Sicherung (siehe Karten-KDoc). */
private fun setLastBackupAt(context: Context, epochMs: Long) {
    trailscapePrefs(context).edit().putLong(PREF_LAST_BACKUP_AT, epochMs).apply()
}
