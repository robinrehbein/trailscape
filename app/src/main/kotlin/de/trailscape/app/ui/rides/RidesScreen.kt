package de.trailscape.app.ui.rides

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.DUPLICATE_RIDE_MESSAGE
import de.trailscape.app.ui.UNDO_DELETE_GRACE_MS
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.importActivityFile
import de.trailscape.app.ui.isDuplicateRide
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.LoadSource
import de.trailscape.core.Ride
import de.trailscape.core.formatDuration
import de.trailscape.core.rideToGpx
import de.trailscape.core.safeFileName
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kurzes Quellen-Label der Trainingslast fuer die Tourenliste
 * (`rideLoadSourceShortLabels` aus `lib/screens/rides_screen.dart`).
 */
private val loadSourceShortLabels: Map<LoadSource, String> = mapOf(
    LoadSource.HERZFREQUENZ to "Puls",
    LoadSource.PHYSIK to "Leistung",
    LoadSource.RPE to "Empfinden",
    LoadSource.HEURISTIK to "geschätzt",
    LoadSource.KEINE to "",
)

/**
 * Tourenliste — Port von `lib/screens/rides_screen.dart`.
 *
 * Kann: alle gespeicherten Touren anzeigen (Name, Datum, Distanz, Dauer,
 * Hoehenmeter, Ø-Puls, Trainingslast), eine Tour auswaehlen (und damit auf der
 * Karte oeffnen), umbenennen, loeschen und als GPX teilen sowie eine
 * GPX-Datei importieren.
 *
 * Bewusst anders als das Flutter-Original:
 *  * **Karten statt `ListTile` + `Dismissible`.** Das Wischen zum Loeschen war
 *    in Flutter der einzige Loeschweg; hier liegt Loeschen (wie Umbenennen und
 *    Teilen) im Ueberlaufmenue der Karte — ein Weg, ueberall gleich erreichbar,
 *    und kein versehentliches Wischen beim Scrollen.
 *  * **Kein gestaffeltes Einblenden** (`_EntranceFade`): `LazyColumn`
 *    recycelt Eintraege, eine „nur beim ersten Aufbau"-Animation waere dort
 *    nicht dasselbe und wuerde beim Zurueckscrollen erneut laufen.
 *  * **Import ueber das Storage Access Framework** statt `file_picker`.
 *    Akzeptiert neben GPX auch FIT (je auch `.gz`) — die Dateitypenerkennung
 *    teilt sich der Knopf mit der „Tour importieren"-Schaltflaeche der
 *    Backup-Karte (`ui/ActivityFileImport.kt`, kein Dart-Vorbild). Fuer den
 *    Massenimport eines ganzen Strava-/Garmin-Exports als ZIP-Archiv siehe
 *    „Mehr → Daten & Backup" (`ui/more/BackupCard.kt`) — dieser Screen bleibt
 *    bewusst beim Einzelimport, ein Fortschritts-/Ergebnisdialog fuer einen
 *    Archiv-Import waere hier fehl am Platz.
 *
 * ## Leerzustand
 * [RidesEmptyState] statt der bisherigen zwei zentrierten Textzeilen: gleicher
 * Aufbau wie im Trainings-Tab (`ui/components/EmptyState.kt`), mit allen drei
 * Wegen, auf denen eine Tour hier landen kann — aufzeichnen, Einzeldatei,
 * Archiv.
 *
 * ## Loeschen mit „Rückgängig"
 * Der Bestaetigungsdialog entfernt die Tour sofort aus der Liste
 * ([AppViewModel.deleteRideWithUndo]), loescht die Datei aber erst nach
 * [UNDO_DELETE_GRACE_MS], solange in der Zwischenzeit keine weitere Loeschung
 * dazwischenkommt. Waehrenddessen zeigt dieser Screen eine eigene Snackbar
 * „Tour gelöscht" mit Aktion „Rückgängig" — bewusst **nicht** ueber den
 * geteilten [AppViewModel.messages]-Kanal, der nur einfache, aktionslose
 * Text-Snackbars kennt und von jedem Screen gleichermassen mitgelesen wird
 * (ein Undo waere dort inhaerent verschickt an einen Ort, an dem er nicht
 * hingehoert). Der Kanal bleibt dadurch additiv unveraendert; nur
 * [AppViewModel] bekommt mit [AppViewModel.deleteRideWithUndo] und
 * [AppViewModel.undoDeleteRide] zwei neue Methoden.
 *
 * Die Snackbar laeuft ueber `withTimeoutOrNull(UNDO_DELETE_GRACE_MS)`: Tippt
 * niemand auf „Rückgängig", verschwindet sie nach Ablauf der Frist von
 * selbst — zeitgleich mit dem im ViewModel laufenden Loesch-Timer. Eine
 * zweite Loeschung waehrend einer noch offenen Snackbar bricht deren
 * Anzeige-Coroutine ab (die alte Snackbar verschwindet sofort) und zeigt eine
 * frische fuer die neue Tour; im ViewModel schliesst dieselbe Aktion die
 * vorige Loeschung sofort endgueltig ab.
 *
 * Stirbt der Prozess waehrend der Frist, bleibt die Datei einfach liegen —
 * die Tour taucht beim naechsten Start ganz normal wieder auf. Akzeptierter
 * Kompromiss, siehe KDoc von [AppViewModel.deleteRideWithUndo].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(appViewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val loading by appViewModel.ridesLoading.collectAsStateWithLifecycle()
    val selectedId by appViewModel.selectedRideId.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var importing by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Ride?>(null) }
    var deleteTarget by remember { mutableStateOf<Ride?>(null) }

    // Anzeige-Coroutine der aktuellen Undo-Snackbar (siehe Klassen-KDoc oben).
    // Ueberschreibt sich selbst bei jeder neuen Loeschung — `Job.cancel()` auf
    // die vorige laesst deren Snackbar sofort verschwinden, statt sie
    // hinter der neuen einzureihen.
    var undoSnackbarJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Aktivitaets-Auswahl (GPX oder FIT) ueber das Storage Access Framework.
    // Bewusst `*/*`: Der MIME-Typ einer .gpx-/.fit-Datei ist je nach Anbieter
    // application/gpx+xml, application/xml, text/xml, application/octet-stream
    // oder gar nichts — ein enger Filter blendet die Datei bei manchen
    // Dateimanagern schlicht aus.
    val importLauncher = rememberLauncherForActivityResult(
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
                    appViewModel.requestTab(AppTab.MAP)
                }
            } catch (e: Exception) {
                appViewModel.showMessage(
                    e.message?.takeIf { it.isNotBlank() } ?: "Import fehlgeschlagen.",
                )
            } finally {
                importing = false
            }
        }
    }

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben — hier duerfen sie
        // kein zweites Mal aufschlagen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Touren") },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!importing) importLauncher.launch(arrayOf("*/*")) },
                icon = {
                    if (importing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                },
                text = { Text("Tour importieren") },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                loading && rides.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                // Gleiche Breitendeckelung und dasselbe 16-dp-Raster wie im
                // Trainings- und Mehr-Tab; vorher stand die Liste hier ohne
                // Deckelung mit 12-dp-Rand und 8-dp-Abstand.
                rides.isEmpty() -> Box(
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(ScreenPadding),
                ) {
                    RidesEmptyState(
                        onRecord = { appViewModel.requestTab(AppTab.MAP) },
                        onImportFile = { if (!importing) importLauncher.launch(arrayOf("*/*")) },
                        onOpenBackup = { appViewModel.requestTab(AppTab.MORE) },
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = ScreenPadding,
                        end = ScreenPadding,
                        top = ScreenPadding,
                        // Platz, damit der schwebende Knopf die letzte Karte nicht verdeckt.
                        bottom = ScreenPadding + 80.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(CardGap),
                ) {
                    items(items = rides, key = { it.id }) { ride ->
                        val load = insights.rideLoads[ride.id]
                        val loadText = if (load != null && load.available) {
                            "Last ${load.load.roundToInt()} · " +
                                loadSourceShortLabels[load.source].orEmpty()
                        } else {
                            null
                        }

                        RideCard(
                            ride = ride,
                            loadText = loadText,
                            selected = ride.id == selectedId,
                            onClick = {
                                appViewModel.select(ride.id)
                                appViewModel.requestTab(AppTab.MAP)
                            },
                            onRename = { renameTarget = ride },
                            onShare = {
                                scope.launch {
                                    try {
                                        shareGpx(context, ride)
                                    } catch (e: Exception) {
                                        appViewModel.showMessage(
                                            "Teilen fehlgeschlagen: ${e.message}",
                                        )
                                    }
                                }
                            },
                            onDelete = { deleteTarget = ride },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { ride ->
        RenameDialog(
            ride = ride,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                appViewModel.renameRide(ride.id, newName)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { ride ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Tour löschen") },
            text = { Text("Soll „${ride.name}“ wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        undoSnackbarJob?.cancel()
                        appViewModel.deleteRideWithUndo(ride.id)
                        undoSnackbarJob = scope.launch {
                            val result = withTimeoutOrNull(UNDO_DELETE_GRACE_MS) {
                                snackbarHostState.showSnackbar(
                                    message = "Tour gelöscht",
                                    actionLabel = "Rückgängig",
                                    duration = SnackbarDuration.Indefinite,
                                )
                            }
                            if (result == SnackbarResult.ActionPerformed) {
                                appViewModel.undoDeleteRide()
                            }
                        }
                    },
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun RenameDialog(
    ride: Ride,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(ride.id) { mutableStateOf(ride.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tour umbenennen") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun RideCard(
    ride: Ride,
    loadText: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        ),
    ) {
        // 16 dp ringsum wie in jeder anderen Karte der App; rechts bleibt es
        // bei 4 dp, weil der IconButton daneben seinen eigenen Beruehrungsrand
        // von 12 dp mitbringt und die Karte sonst rechts zu luftig wirkt.
        Column(
            modifier = Modifier.padding(
                start = CardPadding,
                end = 4.dp,
                top = CardPadding,
                bottom = CardPadding,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ride.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatDate(ride.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Aktionen")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Umbenennen") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Als GPX teilen") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onShare()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Löschen") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RideFact("Distanz", "${formatKmDe(ride.stats.distanceKm)} km")
                RideFact("Dauer", formatDuration(ride.stats.durationS))
                RideFact("Höhenmeter", "${ride.stats.ascentM.roundToInt()} hm")
                ride.stats.avgHrBpm?.let { RideFact("Ø Puls", "$it bpm") }
            }

            if (loadText != null || ride.id.startsWith("hc-")) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loadText != null) {
                        Text(
                            text = loadText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.primary,
                        )
                    }
                    if (ride.id.startsWith("hc-")) {
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("aus Samsung Health") },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Was der Touren-Tab kann, solange nichts gespeichert ist.
 *
 * Vorher standen hier zwei mittig zentrierte Textzeilen ohne Aktion; jetzt
 * derselbe Aufbau wie im Trainings-Tab (siehe `ui/components/EmptyState.kt`)
 * samt der drei Wege, auf denen eine Tour hier landen kann.
 */
@Composable
private fun RidesEmptyState(
    onRecord: () -> Unit,
    onImportFile: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    EmptyState(
        title = "Noch keine Touren",
        body = "Hier sammeln sich alle Touren — aufgezeichnete wie importierte — mit " +
            "Distanz, Dauer, Höhenmetern, Ø-Puls und der berechneten Trainingslast. " +
            "Ein Tipp auf eine Tour öffnet sie auf der Karte.",
        hint = "Ein ganzer Strava- oder Garmin-Export lässt sich als ZIP-Archiv auf " +
            "einmal einlesen — unter Mehr → Daten & Backup.",
        actions = {
            Button(onClick = onRecord) { Text("Tour aufzeichnen") }
            OutlinedButton(onClick = onImportFile) { Text("GPX-/FIT-Datei öffnen") }
            OutlinedButton(onClick = onOpenBackup) { Text("Archiv importieren") }
        },
    )
}

/** Eine Kennzahl der Tour: kleines Label, darunter der Wert. */
@Composable
private fun RideFact(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
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
private suspend fun shareGpx(context: Context, ride: Ride) {
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
