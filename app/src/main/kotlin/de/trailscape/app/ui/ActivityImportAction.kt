package de.trailscape.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.core.BulkImportError
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Der Datei-Import (GPX/FIT, je auch `.gz`, eine oder mehrere Dateien) als
 * fertig verdrahtete Aktion — SAF-Mehrfachauswahl, Lesen ueber
 * [readActivityFiles], Import ueber [AppViewModel.importActivityFiles] und
 * der stehende Fehlerdialog in einem Stueck.
 *
 * Entstanden aus dem Import-Knopf der Tourenliste (`ui/rides/TourList.kt`):
 * Der lag als Kopfzeile **in** der aufgeklappten Liste und war damit nur zu
 * finden, wer das Erkunden-Blatt erst aufzieht — genau die Frage „kann ich
 * keine GPX-Dateien mehr importieren?", die diesen Umbau ausgeloest hat.
 * Seither haelt der Karten-Screen genau **eine** Instanz dieser Aktion und
 * reicht sie an beide Stellen weiter: die immer sichtbare Touren-Zeile im
 * eingeklappten Erkunden-Blatt (`ui/map/ExploreSheet.kt`) und den
 * Leerzustand der Tourenliste. Ein zweiter, unabhaengiger Weg bleibt die
 * Backup-Karte (Mehr → Daten & Backup, `ui/more/BackupCard.kt`) — sie
 * kombiniert den Datei-Import mit Archiv und Backup, nutzt fuer ersteren
 * aber dieselbe Aktion.
 *
 * @see rememberActivityImportAction
 */
class ActivityImportAction internal constructor(
    /** Laeuft gerade ein Import? Knoepfe zeigen dann einen Spinner statt zu feuern. */
    val importing: Boolean,
    /** Oeffnet die Dateiauswahl — wirkungslos, solange [importing] steht. */
    val start: () -> Unit,
)

/**
 * Baut die Import-Aktion samt SAF-Launcher und Fehlerdialog auf.
 *
 * Seit dem Sammelimport ist die Auswahl eine **Mehrfach**auswahl
 * ([ActivityResultContracts.OpenMultipleDocuments]): Wer von Komoot oder
 * Strava umsteigt, hat zwanzig Touren im Download-Ordner, nicht eine. Der
 * Import selbst laeuft ueber [AppViewModel.importActivityFiles] — derselbe
 * Weg wie „Teilen an Trailscape" — und meldet sich per Snackbar mit
 * „7 importiert · 1 schon vorhanden · 1 unlesbar".
 *
 * Der Dialog wird hier mit ausgegeben (Compose-Dialoge oeffnen ein eigenes
 * Fenster, ihr Platz im Baum ist egal): Liess sich **gar nichts** lesen, ist
 * das eine Entscheidung, keine Meldung — der Fehlertext bleibt stehen, bis
 * eine andere Datei gewaehlt oder geschlossen wird; eine 4-Sekunden-Snackbar
 * waere verschwunden, bevor jemand vom Dateidialog zurueckgeblickt hat.
 * Teilerfolge laufen dagegen als Snackbar, der Rest ist ja angekommen.
 *
 * Laufzustand und Fehlertext liegen im ViewModel
 * ([AppViewModel.fileImportRunning], [AppViewModel.fileImportFailure]), nicht
 * in `remember`: Der Import ueberlebt eine Drehung, und sein Ergebnis soll
 * danach noch ankommen. Dieselbe Quelle bedient auch „Teilen an
 * Trailscape" — scheitert dort alles, steht der Dialog im Verlauf, in den die
 * App dabei springt.
 */
@Composable
fun rememberActivityImportAction(appViewModel: AppViewModel): ActivityImportAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importing by appViewModel.fileImportRunning.collectAsStateWithLifecycle()

    // Zwischen Auswahl und Uebergabe ans ViewModel (Einlesen der Bytes)
    // laeuft noch kein ViewModel-Import — ohne diesen Merker liesse sich der
    // Knopf in genau diesem Moment ein zweites Mal ausloesen.
    var reading by remember { mutableStateOf(false) }
    val errorMessage by appViewModel.fileImportFailure.collectAsStateWithLifecycle()

    // Aktivitaets-Auswahl (GPX oder FIT) ueber das Storage Access Framework.
    // Bewusst `*/*`: Der MIME-Typ einer .gpx-/.fit-Datei ist je nach Anbieter
    // application/gpx+xml, application/xml, text/xml, application/octet-stream
    // oder gar nichts — ein enger Filter blendet die Datei bei manchen
    // Dateimanagern schlicht aus. Was keine GPX/FIT ist, faellt spaeter an
    // Endung und Magic Bytes heraus (`:core`, ActivityFiles.kt).
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            try {
                appViewModel.importActivityFiles(readActivityFiles(context, uris.distinct()))
            } finally {
                reading = false
            }
        }
    }

    fun start() {
        if (!importing && !reading) launcher.launch(arrayOf("*/*"))
    }

    errorMessage?.let { message ->
        OneUiDialog(
            onDismissRequest = appViewModel::dismissFileImportFailure,
            title = { Text("Import fehlgeschlagen") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        appViewModel.dismissFileImportFailure()
                        start()
                    },
                ) { Text("Andere Datei wählen") }
            },
            dismissButton = {
                TextButton(onClick = appViewModel::dismissFileImportFailure) { Text("Schließen") }
            },
        )
    }

    return ActivityImportAction(importing = importing || reading, start = ::start)
}

/**
 * Ergebnis eines Datei-Imports fuer die Snackbar — siehe
 * [AppViewModel.fileImportNotice].
 */
data class FileImportNotice(
    /** Die eine Zeile, z. B. „7 importiert · 1 schon vorhanden · 1 unlesbar". */
    val message: String,
    /** Unlesbare Dateien samt Grund — hinter „Details" an der Snackbar. */
    val errors: List<BulkImportError>,
    /**
     * true beim Import per Teilen/Oeffnen: Die App springt dabei in den
     * Verlauf, nur dort (Liste oder Detail) soll die Meldung erscheinen.
     */
    val inHistory: Boolean,
)

/** Fehlertext, wenn der Import-Lauf selbst scheitert (Speicherfehler, volles Geraet). */
internal const val FILE_IMPORT_CRASH_MESSAGE =
    "Der Import ist fehlgeschlagen. Es wurden möglicherweise nicht alle Touren gespeichert — " +
        "versuche es mit weniger Dateien auf einmal erneut."

/**
 * Zeigt [AppViewModel.fileImportNotice] in [snackbarHostState] an und
 * quittiert sie — samt Aktion „Details", wenn Dateien unlesbar waren. Ein
 * blosses „1 unlesbar" ohne Weg zu Name und Grund waere genau die Art
 * versteckter Information, die die App vermeiden will; der Dialog dahinter
 * listet jede Datei mit ihrem Grund.
 *
 * Abgeholt wird erst, wenn der Screen wirklich der richtige ist: kein
 * Tab-Wechsel und keine Tour-Detailansicht stehen noch an (sonst naehme ein
 * Screen die Meldung mit, der im naechsten Frame verschwindet), und
 * [standBy] ist `false` — der Verlauf setzt es, solange ein Detail ueber der
 * Liste liegt, dann zeigt das Detail die Meldung. Mit [acceptHistory] =
 * `false` laesst ein Screen Meldungen aus Teilen/Oeffnen liegen, weil die App
 * dafuer ohnehin in den Verlauf springt.
 */
@Composable
fun FileImportNoticeEffect(
    appViewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
    acceptHistory: Boolean = true,
    standBy: () -> Boolean = { false },
) {
    var details by remember { mutableStateOf<List<BulkImportError>?>(null) }
    val currentStandBy by rememberUpdatedState(standBy)

    LaunchedEffect(appViewModel, snackbarHostState, acceptHistory) {
        combine(
            appViewModel.fileImportNotice,
            appViewModel.tabRequest,
            appViewModel.pendingRideDetail,
            snapshotFlow { currentStandBy() },
        ) { notice, tab, pendingDetail, waiting ->
            notice?.takeIf {
                tab == null && pendingDetail == null && !waiting && (acceptHistory || !it.inHistory)
            }
        }
            .filterNotNull()
            .collect { notice ->
                appViewModel.consumeFileImportNotice(notice)
                val result = snackbarHostState.showSnackbar(
                    message = notice.message,
                    actionLabel = if (notice.errors.isNotEmpty()) "Details" else null,
                    // Mit Aktion lange genug stehen lassen, um sie zu treffen.
                    duration = if (notice.errors.isNotEmpty()) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) details = notice.errors
            }
    }

    details?.let { errors ->
        OneUiDialog(
            onDismissRequest = { details = null },
            title = { Text(if (errors.size == 1) "Nicht importiert" else "${errors.size} Dateien nicht importiert") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    errors.forEach { error ->
                        Column {
                            Text(error.path, style = MaterialTheme.typography.titleSmall)
                            Text(error.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { details = null }) { Text("OK") }
            },
        )
    }
}
