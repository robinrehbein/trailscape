package de.trailscape.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.components.OneUiDialog
import kotlinx.coroutines.launch

/**
 * Der Einzelimport (GPX/FIT, je auch `.gz`) als fertig verdrahtete Aktion —
 * SAF-Auswahl, Lesen ueber [importActivityFile], Duplikatpruefung, Erfolgs-
 * meldung und der stehende Fehlerdialog in einem Stueck.
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
 * kombiniert den Einzelimport mit Archiv und Backup und behaelt ihre eigene
 * Verdrahtung.
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
 * Der Dialog wird hier mit ausgegeben (Compose-Dialoge oeffnen ein eigenes
 * Fenster, ihr Platz im Baum ist egal): Ein gescheiterter Import ist eine
 * Entscheidung, keine Meldung — der Fehlertext bleibt stehen, bis eine andere
 * Datei gewaehlt oder geschlossen wird; eine 4-Sekunden-Snackbar waere
 * verschwunden, bevor jemand vom Dateidialog zurueckgeblickt hat. Erfolg und
 * erkannte Dublette laufen dagegen wie ueberall ueber
 * [AppViewModel.showMessage].
 *
 * [ActivityImportAction.importing] liegt bewusst in `remember`, nicht in
 * `rememberSaveable`: `true` gilt nur, solange die Import-Coroutine laeuft —
 * ein Prozesstod beendet die mit, und ein wiederhergestelltes `true` haette
 * den Knopf dauerhaft stillgelegt.
 */
@Composable
fun rememberActivityImportAction(appViewModel: AppViewModel): ActivityImportAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    var importing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Aktivitaets-Auswahl (GPX oder FIT) ueber das Storage Access Framework.
    // Bewusst `*/*`: Der MIME-Typ einer .gpx-/.fit-Datei ist je nach Anbieter
    // application/gpx+xml, application/xml, text/xml, application/octet-stream
    // oder gar nichts — ein enger Filter blendet die Datei bei manchen
    // Dateimanagern schlicht aus.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            try {
                val ride = importActivityFile(context, uri)
                // Inhaltsbasiert pruefen: `rideFromGpx`/`rideFromFit` vergeben
                // bei jedem Import eine frische ID, ein ID-Vergleich ginge also
                // immer ins Leere (siehe ui/RideImport.kt).
                if (isDuplicateRide(rides, ride)) {
                    appViewModel.showMessage(DUPLICATE_RIDE_MESSAGE)
                } else {
                    appViewModel.addRide(ride)
                    appViewModel.showMessage("„${ride.name}“ importiert")
                }
            } catch (e: Exception) {
                // Deutscher Satz mit Handlungsanweisung zuerst, technische
                // Ursache nur in Klammern (siehe ui/ErrorText.kt).
                errorMessage = withCause(
                    "Die Datei konnte nicht importiert werden. Trailscape liest " +
                        "GPX- und FIT-Dateien, auch als .gz gepackt.",
                    e,
                )
            } finally {
                importing = false
            }
        }
    }

    fun start() {
        if (!importing) launcher.launch(arrayOf("*/*"))
    }

    errorMessage?.let { message ->
        OneUiDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Import fehlgeschlagen") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        errorMessage = null
                        start()
                    },
                ) { Text("Andere Datei wählen") }
            },
            dismissButton = {
                TextButton(onClick = { errorMessage = null }) { Text("Schließen") }
            },
        )
    }

    return ActivityImportAction(importing = importing, start = ::start)
}
