package de.trailscape.app.ui.rides

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.withCause
import de.trailscape.core.Ride
import de.trailscape.core.rideToGpx
import de.trailscape.core.safeFileName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [ShareRideDialog] samt beider Teilen-Wege — Tour-Bild ([shareRideImage])
 * und GPX ([shareRideGpx]) — fuer eine **gefahrene** Tour.
 *
 * ## Warum ein gemeinsamer Wirt
 * Geteilt wird von zwei Stellen: aus der Detailansicht ([RideDetailHost]) und
 * vom Tourblatt der Karte, dem ersten Blatt nach einer Aufzeichnung — gerade
 * dort liegt das Bild am naechsten. Beide sollen dieselben Fehlermeldungen
 * und dieselbe Absicherung gegen knappen Speicher haben; zwei Kopien der
 * Handler liefen frueher oder spaeter auseinander.
 *
 * [load] ist die Trainingslast aus [AppViewModel.insights] (nur gelesen) oder
 * `null`. Fehler landen ueber [AppViewModel.showMessage] in der Snackbar des
 * jeweiligen Bildschirms. Der Dialog schliesst sich vor dem Teilen selbst
 * ([onDismiss]).
 *
 * Der Schalter „Start und Ziel ausblenden" wird hier gelesen und gemerkt
 * (`ShareCardSettings.kt`): Die Wahl gilt fuer jedes kuenftige Tour-Bild,
 * egal von welcher der beiden Stellen aus geteilt wird.
 */
@Composable
internal fun RideShareDialog(
    ride: Ride,
    load: Double?,
    appViewModel: AppViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hideEnds by remember { mutableStateOf(shareHideEnds(context)) }
    ShareRideDialog(
        ride = ride,
        load = load,
        hideEnds = hideEnds,
        onHideEndsChange = {
            hideEnds = it
            setShareHideEnds(context, it)
        },
        onDismiss = onDismiss,
        onShareGpx = {
            onDismiss()
            scope.launch {
                try {
                    shareRideGpx(context, ride)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    appViewModel.showMessage(
                        withCause(
                            "Die Tour konnte nicht geteilt werden. Prüfe, ob genug " +
                                "Speicher frei ist, und versuche es erneut.",
                            e,
                        ),
                    )
                }
            }
        },
        onShareImage = { format ->
            onDismiss()
            scope.launch {
                // Auch OutOfMemoryError: Eine Story-Bitmap belegt rund 8 MB,
                // auf knappen Geraeten soll das eine Meldung sein, kein Absturz.
                val failure: Throwable? = try {
                    shareRideImage(context, ride, load, format, hideEnds)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                } catch (e: OutOfMemoryError) {
                    e
                }
                if (failure != null) {
                    appViewModel.showMessage(
                        withCause(
                            "Das Bild konnte nicht erstellt werden. Prüfe, ob genug " +
                                "Speicher frei ist, und versuche es erneut.",
                            failure,
                        ),
                    )
                }
            }
        },
    )
}

/**
 * Teilt eine Tour als GPX-Datei ueber das System-Share-Sheet (z. B. fuer
 * Komoot, Strava oder eine andere Trainings-App).
 *
 * Die Datei landet unter `<cacheDir>/geteilte-touren` — genau der Pfad, den
 * `res/xml/file_paths.xml` fuer den FileProvider freigibt. Dabei werden **alte**
 * Exporte aufgeraeumt (siehe `ui/ShareFiles.kt`), damit der Cache nicht
 * mitwaechst; frische bleiben liegen, weil die Empfaenger-App sie erst nach
 * dem Chooser liest.
 */
internal suspend fun shareRideGpx(context: Context, ride: Ride) {
    val uri = withContext(Dispatchers.IO) {
        val dir = prepareShareDirectory(context.cacheDir)
        val file = File(dir, "${safeFileName(ride.name)}.gpx")
        file.writeText(rideToGpx(ride), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, ride.name)
        putExtra(Intent.EXTRA_TITLE, ride.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Tour teilen"))
}
