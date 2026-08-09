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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.DUPLICATE_RIDE_MESSAGE
import de.trailscape.app.ui.UNREADABLE_FILE_MESSAGE
import de.trailscape.app.ui.importActivityFile
import de.trailscape.app.ui.isDuplicateRide
import de.trailscape.core.BulkImportResult
import de.trailscape.core.FormatException
import de.trailscape.core.Ride
import de.trailscape.core.TrainingProfile
import de.trailscape.core.backupFileName
import de.trailscape.core.buildBackupJson
import de.trailscape.core.importArchive
import de.trailscape.core.parseBackupJson
import de.trailscape.core.scanArchive
import java.time.LocalDate
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
 * Import ueber [ActivityResultContracts.OpenDocument]. Der Einzelimport
 * („Tour importieren") teilt seine Dateitypenerkennung mit dem
 * Import-Knopf in `ui/rides/RidesScreen.kt` (siehe `ui/ActivityFileImport.kt`).
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
 * Keine Icons auf den Aktions-Buttons (anders als im Dart-Original): `:app`
 * bindet bewusst nur `material-icons-core` ein (siehe `build.gradle.kts`),
 * und dessen kleiner Symbolsatz enthaelt weder ein Save- noch ein
 * Routen-Symbol.
 */
@Composable
fun BackupCard(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
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
                writeBackupFile(context, uri, rides, appViewModel.profile.value)
                appViewModel.showMessage("Backup exportiert.")
            } catch (e: Exception) {
                appViewModel.showMessage("Export fehlgeschlagen: ${e.message}")
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
                val data = parseBackupJson(raw)

                val existingIds = rides.map { it.id }.toSet()
                val newRides = data.rides.filter { it.id !in existingIds }
                val skipped = data.rides.size - newRides.size

                appViewModel.addRides(newRides)
                data.profile?.let { appViewModel.setProfile(it) }

                val rideWord = if (newRides.size == 1) "Tour" else "Touren"
                appViewModel.showMessage(
                    "${newRides.size} $rideWord importiert" +
                        (if (skipped > 0) ", $skipped übersprungen" else "") +
                        (if (data.profile != null) " · Profil übernommen" else ""),
                )
            } catch (e: FormatException) {
                appViewModel.showMessage(e.message ?: UNREADABLE_FILE_MESSAGE)
            } catch (e: Exception) {
                appViewModel.showMessage("Import fehlgeschlagen: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    // Einzelimport einer Aktivitaetsdatei (GPX oder FIT, je auch `.gz`) —
    // Erkennung und Lesen teilt sich `importActivityFile` mit dem
    // Import-Knopf in `ui/rides/RidesScreen.kt` (`ui/ActivityFileImport.kt`).
    // Bewusst `*/*`: Der MIME-Typ ist je nach Dateimanager/Anbieter
    // uneinheitlich (siehe RidesScreen-KDoc), ein enger Filter blendet die
    // Datei bei manchen davon schlicht aus.
    val importActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val ride = importActivityFile(context, uri)

                // Inhaltsbasiert statt ueber die ID: `rideFromGpx`/`rideFromFit`
                // vergeben beim Import jedes Mal eine neue ID (`:core`,
                // Export.kt/Fit.kt), ein ID-Vergleich konnte deshalb nie
                // anschlagen.
                if (isDuplicateRide(rides, ride)) {
                    appViewModel.showMessage(DUPLICATE_RIDE_MESSAGE)
                } else {
                    appViewModel.addRide(ride)
                    appViewModel.showMessage("„${ride.name}\" importiert")
                }
            } catch (e: FormatException) {
                appViewModel.showMessage(e.message ?: UNREADABLE_FILE_MESSAGE)
            } catch (e: Exception) {
                appViewModel.showMessage("Import fehlgeschlagen: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

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
                archiveTotal = withContext(Dispatchers.IO) { tryScanArchiveTotal(context, uri) }

                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        importArchive(
                            input = stream,
                            existing = rides,
                            total = archiveTotal,
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
                appViewModel.showMessage("Import fehlgeschlagen: ${e.message}")
            } finally {
                archiveBusy = false
            }
        }
    }

    MoreSectionCard(title = "Daten & Backup", modifier = modifier) {
        Text(
            text = "Sichere alle Touren und dein Trainingsprofil in einer Datei — zum " +
                "Übertragen auf ein neues Gerät oder als Backup vor einer Neuinstallation. " +
                "Einzelne GPX- oder FIT-Dateien (z. B. aus Komoot oder Strava) lassen sich " +
                "ebenfalls importieren — oder gleich ein ganzer Strava-/Garmin-Export als " +
                "ZIP-Archiv.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { exportLauncher.launch(backupFileName(LocalDate.now())) },
                enabled = !busy,
            ) { Text("Backup exportieren") }
            OutlinedButton(
                onClick = { importBackupLauncher.launch(arrayOf("application/json", "*/*")) },
                enabled = !busy,
            ) { Text("Backup importieren") }
            OutlinedButton(
                onClick = { importActivityLauncher.launch(arrayOf("*/*")) },
                enabled = !busy,
            ) { Text("Tour importieren (GPX/FIT)") }
            OutlinedButton(
                onClick = { importArchiveLauncher.launch(arrayOf("application/zip", "*/*")) },
                enabled = !busy && !archiveBusy,
            ) { Text("Archiv importieren (ZIP)") }
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
private fun tryScanArchiveTotal(context: Context, uri: Uri): Int? =
    try {
        context.contentResolver.openInputStream(uri)?.use { scanArchive(it).size }
    } catch (e: Exception) {
        null
    }

/** Nicht schliessbarer Fortschritts-Dialog fuer den Archiv-Import — siehe Karten-KDoc. */
@Composable
private fun ArchiveImportProgressDialog(done: Int, total: Int?) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Archiv wird importiert …") },
        text = {
            Column {
                val knownTotal = total?.takeIf { it > 0 }
                if (knownTotal != null) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / knownTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$done von $knownTotal importiert …")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$done importiert …")
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Archiv importiert") },
        text = {
            Column {
                val rideWord = if (result.importedCount == 1) "Tour" else "Touren"
                Text("${result.importedCount} $rideWord importiert")

                if (result.duplicateCount > 0) {
                    val dupWord = if (result.duplicateCount == 1) "Duplikat" else "Duplikate"
                    Text("${result.duplicateCount} $dupWord übersprungen")
                }

                if (result.errorCount > 0) {
                    val errorWord = if (result.errorCount == 1) "Datei" else "Dateien"
                    Text(
                        text = "${result.errorCount} $errorWord mit Fehler",
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { showErrors = !showErrors }) {
                        Text(if (showErrors) "Fehler ausblenden" else "Fehler anzeigen")
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
                        "Keine GPX- oder FIT-Dateien im Archiv gefunden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
    )
}

/** Liest die gewaehlte Datei komplett als UTF-8-Text. Laeuft auf [Dispatchers.IO]. */
private suspend fun readTextFile(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.readBytes().toString(Charsets.UTF_8)
    } ?: throw FormatException(UNREADABLE_FILE_MESSAGE)
}

/** Schreibt das Backup-JSON in das vom SAF gewaehlte Ziel. Laeuft auf [Dispatchers.IO]. */
private suspend fun writeBackupFile(
    context: Context,
    uri: Uri,
    rides: List<Ride>,
    profile: TrainingProfile,
) = withContext(Dispatchers.IO) {
    val json = buildBackupJson(rides, profile)
    context.contentResolver.openOutputStream(uri)?.use { stream ->
        stream.write(json.toByteArray(Charsets.UTF_8))
    } ?: throw IllegalStateException("Die Datei konnte nicht geschrieben werden.")
}
