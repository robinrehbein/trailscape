package de.trailscape.app.ui.more

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.indoor.ClimbBestStore
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.UNREADABLE_FILE_MESSAGE
import de.trailscape.app.ui.withCause
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.FormatException
import de.trailscape.core.backupFileName
import de.trailscape.core.parseBackupJson
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
 * „Archiv importieren (ZIP)" ist die Aktion aus `ArchiveImportAction.kt`
 * ([rememberArchiveImportAction]); Ablauf und die Entscheidung gegen einen
 * Abbrechen-Knopf stehen dort.
 *
 * Die Aktions-Buttons tragen inzwischen Icons (anders als vorher: `:app` band
 * damals nur `material-icons-core` ein, dessen kleiner Symbolsatz weder ein
 * Save- noch ein Routen-Symbol enthielt). Seit `material-icons-extended`
 * eingebunden ist, stehen beide zur Verfuegung.
 *
 * Der Inhalt der Seite „Backup" der Einstellungen (siehe
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
                data.yearGoal?.let { appViewModel.restoreYearGoal(it) }
                // Bestzeiten an Anstiegen (Indoor-Bergwertung): je Anstieg gewinnt die schnellere Zeit.
                withContext(Dispatchers.IO) { ClimbBestStore.merge(AppServices.keyValueStore, data.climbBests) }
                // Erfolge: zusammengefuehrt, nie ueberschrieben (`docs/erfolge.md`).
                data.progress?.let { de.trailscape.app.data.ErfolgeStore.shared.restore(it) }
                data.avatar?.let { appViewModel.restoreAvatar(it) }
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

    // Der Tour-Import (GPX/FIT und ZIP-Archive) liegt seit dem Umbau nach der
    // Seitenkarte allein im Verlauf („+"): Touren kommen dort hinein, wo sie
    // danach liegen. Diese Seite ist nur noch das Backup.
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
    }

    if (busy) {
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
    }
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
