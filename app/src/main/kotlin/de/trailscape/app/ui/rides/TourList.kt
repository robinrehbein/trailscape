package de.trailscape.app.ui.rides

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.UNDO_DELETE_GRACE_MS
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.withCause
import de.trailscape.core.Ride
import de.trailscape.core.RideLoad
import de.trailscape.core.RideSummary
import de.trailscape.core.rideToGpx
import de.trailscape.core.safeFileName
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Die Verlaufsliste als **Baustein** — ohne eigenen Bildschirmrahmen. Ihr
 * Wirt ist der Verlauf-Tab (`ui/rides/RidesScreen.kt`), der Kopf (Aktionen,
 * Titel, Suchfeld) ueber [header] hereinreicht, damit er mit der Liste
 * wegscrollt statt ein Drittel des Bildschirms fest zu belegen.
 *
 * ## Nach Monaten, in einer `LazyColumn`
 * Zieldesign `docs/design/prototyp-klartext.html`, Screen `#s-verlauf`: je
 * Monat eine Augenbraue („September", aus anderen Jahren mit Jahreszahl) und
 * darunter eine Gruppen-Karte mit Hairline-Trennern. Die Liste stand vorher
 * als eine einzige Karte mit nicht-lazy `Column` da — alle Touren auf einmal
 * gezeichnet, unter der Ueberschrift „Letzte Touren", obwohl es alle waren.
 * Jetzt ist jede Zeile ein eigenes `item`; die Kartenform entsteht, indem die
 * erste und letzte Zeile eines Monats die oberen bzw. unteren Ecken rund
 * zeichnen ([groupShape]). So zeichnet die Liste nur, was sichtbar ist — das
 * zaehlt, seit jede Zeile eine Mini-Spur nachlaedt ([RideThumbnail]).
 *
 * ## Eine Zeile, eine Handlung
 * Mini-Karte, Name, darunter gedaempft „Di 23.9. · 32,4 km · 1:24 h", rechts
 * das Wort fuer die Haerte ([EffortPill]). Ein Tipp oeffnet die
 * Detailansicht; Umbenennen, Teilen, Loeschen und „Karte zeigen" wohnen
 * seit dem Klartext-Umbau nur noch dort, als beschriftete Kacheln — ein
 * Ueberlaufmenue je Zeile machte die Liste unruhig und bot dieselben vier
 * Handgriffe zweimal an. „geplante Route" und „aus Health Connect" stehen
 * ebenfalls nur noch im Detail: In der Zeile waren sie eine zweite
 * Textzeile fuer eine Auskunft, die man beim Ueberfliegen nicht braucht.
 *
 * @param query Suchtext; filtert nach Namen ([filterRidesByName]).
 * @param onRecord / [onImportFile] / [onImportArchive] tragen den Leerzustand.
 * @param contentPadding wird an die `LazyColumn` durchgereicht und traegt die
 *   Bodenfreiheit der schwebenden Navigationskapsel.
 */
@Composable
fun TourListContent(
    appViewModel: AppViewModel,
    query: String,
    onOpenDetail: (String) -> Unit,
    onRecord: () -> Unit,
    onImportFile: () -> Unit,
    onImportArchive: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: LazyListScope.() -> Unit = {},
) {
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val loading by appViewModel.ridesLoading.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()

    // Das heutige Datum nur fuer die Jahresfrage der Monatsueberschrift —
    // einmal gemerkt genuegt, ein Jahreswechsel bei offener App ist egal.
    val today = remember { LocalDate.now() }
    val groups = remember(rides, query, today) {
        groupRidesByMonth(filterRidesByName(rides, query), today, ::localOfEpochMs)
    }

    LazyColumn(
        modifier = modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        header()

        when {
            loading && rides.isEmpty() -> item(key = "laden") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LoadingRowHeight),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            rides.isEmpty() -> item(key = "leer") {
                RidesEmptyState(
                    onRecord = onRecord,
                    onImportFile = onImportFile,
                    onImportArchive = onImportArchive,
                )
            }

            groups.isEmpty() -> item(key = "keine-treffer") {
                Text(
                    text = "Keine Tour heißt „${query.trim()}“.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = CardPadding, vertical = CardGap),
                )
            }

            else -> groups.forEach { group ->
                item(key = "monat-${group.month}") {
                    // Dieselbe Einrueckung wie der Text in der Karte darunter.
                    Eyebrow(
                        text = group.label,
                        modifier = Modifier.padding(
                            start = CardPadding,
                            top = MonthEyebrowTop,
                            bottom = 8.dp,
                        ),
                    )
                }
                itemsIndexed(group.rides, key = { _, ride -> ride.id }) { index, ride ->
                    RideRow(
                        ride = ride,
                        load = insights.rideLoads[ride.id],
                        loadRide = appViewModel::loadRide,
                        first = index == 0,
                        last = index == group.rides.lastIndex,
                        onClick = { onOpenDetail(ride.id) },
                    )
                }
            }
        }
    }
}

/** Hoehe der Ladeanzeige, solange die Liste noch geladen wird. */
private val LoadingRowHeight = 96.dp

/** Luft ueber einer Monatsueberschrift — trennt zwei Monatskarten. */
private val MonthEyebrowTop = 16.dp

/**
 * Die Form einer Zeile innerhalb ihrer Monatskarte: oben rund, wenn sie die
 * erste ist, unten rund, wenn sie die letzte ist, dazwischen eckig — zusammen
 * ergibt das eine Karte, obwohl jede Zeile ein eigenes `LazyColumn`-Element
 * ist. Die Rundung kommt aus `shapes.medium`, dem Slot, den auch `Card` nimmt.
 */
@Composable
private fun groupShape(first: Boolean, last: Boolean): Shape {
    val card = MaterialTheme.shapes.medium
    val square = CornerSize(0.dp)
    return when {
        first && last -> card
        first -> card.copy(bottomStart = square, bottomEnd = square)
        last -> card.copy(topStart = square, topEnd = square)
        else -> RectangleShape
    }
}

/**
 * Eine Zeile der Verlaufsliste (Zieldesign `.li`): Mini-Karte, Name in einer
 * Zeile, darunter [rideListMeta], rechts [EffortPill]. Der Name bleibt
 * einzeilig — nur so bleiben die Zeilen gleich hoch und die Liste
 * ueberfliegbar; der volle Name steht im Detail.
 */
@Composable
private fun RideRow(
    ride: RideSummary,
    load: RideLoad?,
    loadRide: suspend (String) -> Ride?,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val effort = rideEffort(load, ride.stats)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupShape(first, last))
            // Dieselbe Flaeche wie jede Karte des Themes (Material-Default
            // von `Card`), damit die Zeilen zusammen als eine Karte lesen.
            .background(CardDefaults.cardColors().containerColor)
            .clickable(onClick = onClick),
    ) {
        if (!first) {
            HorizontalDivider(
                color = colors.outlineVariant,
                modifier = Modifier.padding(horizontal = CardPadding),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = CardPadding, vertical = RideRowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RideThumbnail(ride = ride, loadRide = loadRide)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ride.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rideListMeta(localOfEpochMs(ride.createdAt), ride.stats),
                    // Tabellenziffern, damit Datum und km untereinander ruhig
                    // stehen (Repo-Muster, siehe `ui/map/RideCompactBar.kt`).
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            effort?.let { EffortPill(it) }
        }
    }
}

/** Senkrechter Innenabstand einer [RideRow] — kompakter als eine volle [CardPadding]. */
private val RideRowVerticalPadding = 12.dp

/**
 * Das Haerte-Wort als Pille (Zieldesign `.pill`): „locker" auf der
 * Akzent-Toenung (`.pill.ok`), „hart" in der Warnfarbe auf deren
 * 15-%-Toenung (`.pill.warn`, dieselbe Regel wie bei [de.trailscape.app.ui.components.TagPill]),
 * „mittel" neutral. Die Farbe ist nur zweites Signal — das Wort steht immer da.
 */
@Composable
internal fun EffortPill(effort: RideEffort, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val signals = LocalSignalColors.current
    val (container, content) = when (effort) {
        RideEffort.LOCKER -> colors.primaryContainer to colors.onPrimaryContainer
        RideEffort.MITTEL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
        RideEffort.HART -> signals.warning.copy(alpha = 0.15f) to signals.warning
    }
    // Dieselbe Pille wie ueberall (TagPill), nur mit Belastungsfarbe.
    TagPill(
        text = effort.label,
        containerColor = container,
        contentColor = content,
        modifier = modifier.semantics { contentDescription = "Belastung: ${effort.label}" },
    )
}

/**
 * Das Import-Menue hinter „+" (und hinter „Touren importieren" im
 * Leerzustand): Einzeldatei oder Archiv. Ein `DropdownMenu`, das der Aufrufer
 * an seinem Knopf verankert — zwei Eintraege rechtfertigen kein Blatt.
 */
@Composable
internal fun ImportMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onImportFile: () -> Unit,
    onImportArchive: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("GPX-/FIT-Datei") },
            leadingIcon = { Icon(Icons.Filled.Route, contentDescription = null) },
            onClick = {
                onDismiss()
                onImportFile()
            },
        )
        DropdownMenuItem(
            text = { Text("Archiv (Strava/Garmin ZIP)") },
            leadingIcon = { Icon(Icons.Filled.FolderZip, contentDescription = null) },
            onClick = {
                onDismiss()
                onImportArchive()
            },
        )
    }
}

/**
 * Was die Liste kann, solange nichts gespeichert ist: ein Satz, dann die zwei
 * Wege zu echten Daten — aufzeichnen (gefuellt, die eine Hauptaktion) oder
 * importieren (flach, oeffnet dasselbe [ImportMenu] wie „+").
 */
@Composable
private fun RidesEmptyState(
    onRecord: () -> Unit,
    onImportFile: () -> Unit,
    onImportArchive: () -> Unit,
) {
    var importMenuOpen by remember { mutableStateOf(false) }
    EmptyState(
        title = "Noch keine Touren",
        body = "Jede aufgezeichnete oder importierte Tour landet hier, nach Monaten sortiert.",
        actions = {
            Button(onClick = onRecord) { Text("Tour aufzeichnen") }
            Box {
                TextButton(onClick = { importMenuOpen = true }) { Text("Touren importieren") }
                ImportMenu(
                    expanded = importMenuOpen,
                    onDismiss = { importMenuOpen = false },
                    onImportFile = onImportFile,
                    onImportArchive = onImportArchive,
                )
            }
        },
    )
}

/**
 * Detailansicht einer Tour samt Umbenennen und Teilen als eigenstaendige
 * Vollbildansicht, die der Verlauf-Tab in einem eigenen Fenster darueberlegt
 * (`RidesScreen.kt`, damit auch die schwebende Navigationskapsel verdeckt ist).
 *
 * ## Warum eine Tour-ID statt einer Tour
 * Der Wirt merkt sich nur die ID. Diese Funktion schlaegt die Zusammenfassung
 * in [AppViewModel.rides] nach und laedt die volle Tour (mit Punkten) ueber
 * [AppViewModel.loadRide] — Schluessel (ID, `updatedAt`), damit nach einem
 * Umbenennen oder HF-Merge aus Health Connect die neue Fassung erscheint.
 * Verschwindet die Tour aus der Liste, ruft die Ansicht [onBack] selbst.
 *
 * ## Loeschen gehoert dem Wirt
 * [onDelete] reicht die Loeschung nach aussen: Die „Rückgängig"-Snackbar muss
 * im Verlauf-Tab stehen, nicht hier — diese Ansicht schliesst sich mit dem
 * Loeschen, und eine Snackbar in ihrem Fenster (samt der Coroutine, die auf
 * „Rückgängig" wartet) verschwaende mit ihr, bevor jemand tippen kann.
 *
 * ## Meldungen
 * [AppViewModel.messages] sammelt diese Ansicht selbst ein: Ihr Fenster
 * verdeckt den Verlauf-Tab, ein Teilen-Fehler muss also hier erscheinen.
 */
@Composable
fun RideDetailHost(
    rideId: String,
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val summary = rides.firstOrNull { it.id == rideId }

    // Der bereits angezeigte Stand bleibt waehrend eines Nachladens stehen
    // (produceState behaelt seinen Wert ueber Schluesselwechsel).
    val ride by produceState<Ride?>(initialValue = null, rideId, summary?.updatedAt) {
        if (summary != null) {
            value = appViewModel.loadRide(rideId)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<RideSummary?>(null) }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(summary) {
        if (summary == null) onBack()
    }

    BackHandler(onBack = onBack)

    val loaded = ride ?: return

    RideDetailScreen(
        ride = loaded,
        appViewModel = appViewModel,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        // Beide Wege fuehren auf die Karte; das Detail schliesst dabei, sonst
        // stuende es beim Zurueckkommen in den Verlauf wieder offen da.
        onShowOnMap = {
            onBack()
            appViewModel.select(loaded.id)
            appViewModel.requestShowRideOnMap(loaded.id)
        },
        onRideAgain = {
            onBack()
            appViewModel.requestRideAsRoute(loaded.id)
        },
        onRename = { renameTarget = summary },
        onShare = {
            scope.launch {
                try {
                    shareGpx(context, loaded)
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
        onDelete = { onDelete(loaded.id) },
    )

    renameTarget?.let { target ->
        RenameDialog(
            ride = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                appViewModel.renameRide(target.id, newName)
                renameTarget = null
            },
        )
    }
}

/**
 * Loescht sofort und bietet stattdessen „Rückgängig" an — **ohne** vorherige
 * Nachfrage. Ein Bestaetigungsdialog gehoert nach Samsungs Leitfaden nur
 * dorthin, wo sich das Geloeschte nicht leicht wiederherstellen laesst; hier
 * nimmt [AppViewModel.deleteRideWithUndo] die Tour zunaechst nur aus der Liste
 * und raeumt die Datei erst nach [UNDO_DELETE_GRACE_MS] weg. Eine Nachfrage,
 * die man immer mit „Ja" beantwortet, erzieht nur zum Wegklicken.
 *
 * Die Snackbar laeuft ueber `withTimeoutOrNull(UNDO_DELETE_GRACE_MS)`: Tippt
 * niemand auf „Rückgängig", verschwindet sie zeitgleich mit dem Loesch-Timer
 * im ViewModel. [undoJob] ist die Anzeige-Coroutine der vorigen Snackbar; eine
 * neue Loeschung bricht sie ab, statt eine zweite dahinter einzureihen (im
 * ViewModel schliesst dieselbe Aktion die vorige Loeschung endgueltig ab).
 *
 * Stirbt der Prozess waehrend der Frist, bleibt die Datei einfach liegen — die
 * Tour taucht beim naechsten Start wieder auf. Akzeptierter Kompromiss, siehe
 * [AppViewModel.deleteRideWithUndo].
 *
 * @return die neue Anzeige-Coroutine; der Aufrufer merkt sie sich als [undoJob]
 *   fuer die naechste Loeschung.
 */
internal fun deleteRideWithUndo(
    rideId: String,
    appViewModel: AppViewModel,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    undoJob: Job?,
): Job {
    undoJob?.cancel()
    appViewModel.deleteRideWithUndo(rideId)
    return scope.launch {
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
}

@Composable
private fun RenameDialog(
    ride: RideSummary,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(ride.id) { mutableStateOf(ride.name) }

    OneUiDialog(
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
