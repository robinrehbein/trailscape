package de.trailscape.app.ui.more

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.core.FormatException
import de.trailscape.core.Ride
import de.trailscape.core.TrainingProfile
import de.trailscape.core.backupFileName
import de.trailscape.core.buildBackupJson
import de.trailscape.core.parseBackupJson
import de.trailscape.core.rideFromGpx
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Deutsche Rueckfallmeldung, falls eine geworfene Exception keinen Text traegt. */
private const val UNREADABLE_FILE_MESSAGE = "Die Datei konnte nicht gelesen werden."

/**
 * „Daten & Backup"-Karte — Port von `_buildDataBackupCard()` (und den
 * zugehoerigen `_exportBackup`/`_importBackup`/`_importGpxFile`-Methoden) aus
 * `lib/screens/more_screen.dart`.
 *
 * Nutzt das Storage Access Framework statt `file_picker`/`share_plus`: Export
 * geht ueber [ActivityResultContracts.CreateDocument] (Nutzerin waehlt den
 * Speicherort direkt, kein Zwischenschritt ueber ein Share-Sheet noetig),
 * Import ueber [ActivityResultContracts.OpenDocument] — beides analog zum
 * GPX-Import in `ui/rides/RidesScreen.kt`.
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

    val importGpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val xml = readTextFile(context, uri)
                val fallbackName = displayName(context, uri)
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: "Importierte Tour"
                val ride = withContext(Dispatchers.Default) { rideFromGpx(xml, fallbackName = fallbackName) }

                if (rides.any { it.id == ride.id }) {
                    appViewModel.showMessage("Diese Tour ist bereits vorhanden.")
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

    MoreSectionCard(title = "Daten & Backup", modifier = modifier) {
        Text(
            text = "Sichere alle Touren und dein Trainingsprofil in einer Datei — zum " +
                "Übertragen auf ein neues Gerät oder als Backup vor einer Neuinstallation. " +
                "Einzelne GPX-Dateien (z. B. aus Komoot oder Strava) lassen sich ebenfalls " +
                "importieren.",
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
                onClick = { importGpxLauncher.launch(arrayOf("*/*")) },
                enabled = !busy,
            ) { Text("GPX importieren") }
        }

        if (busy) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.height(4.dp))
        }
    }
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

/** Anzeigename eines `content://`-Dokuments, falls der Anbieter ihn liefert. */
private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
