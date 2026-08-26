package de.trailscape.app.ui.rides

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.UNDO_DELETE_GRACE_MS
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.withCause
import de.trailscape.core.LoadSource
import de.trailscape.core.Ride
import de.trailscape.core.RideSummary
import de.trailscape.core.formatDuration
import de.trailscape.core.rideToGpx
import de.trailscape.core.safeFileName
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kurzes Quellen-Label der Trainingslast fuer die Tourenliste
 * (`rideLoadSourceShortLabels` aus `lib/screens/rides_screen.dart`).
 */
/**
 * Hoehe der Ladeanzeige, solange die Tourenliste noch geladen wird. Eine
 * Zeile hoch — gerade genug fuer den Kreis, ohne dass das Blatt darueber auf
 * volle Hoehe aufzieht und danach wieder zusammenfaellt.
 */
private val LoadingRowHeight = 96.dp

private val loadSourceShortLabels: Map<LoadSource, String> = mapOf(
    LoadSource.HERZFREQUENZ to "Puls",
    LoadSource.PHYSIK to "Leistung",
    LoadSource.RPE to "Empfinden",
    LoadSource.HEURISTIK to "geschätzt",
    LoadSource.KEINE to "",
)

/**
 * Tourenliste als Inhalt eines Blatts — Nachfolger des fruehen eigenen
 * Touren-Tabs (`ui/rides/RidesScreen.kt`, entfallen). Karte und Touren sind zu
 * einer Seite zusammengelegt; die Liste liegt seither als Blatt ueber der Karte
 * (`ui/map/ExploreSheet.kt`, dort eingebaut).
 *
 * Deshalb **kein** `Scaffold`, **keine** Titelleiste und **keine** eigene
 * Aufloesung der System-Insets: Dieses Composable zeichnet nur seinen Inhalt
 * und fuellt, was der Behaelter ihm an Platz gibt. Randmasse fuer die
 * schwebende Navigationskapsel kommen ueber [contentPadding] von aussen, nicht
 * ueber `screenContentPadding()` wie bei einem eigenstaendigen Bildschirm.
 *
 * ## Import wohnt nicht mehr in der Liste
 * Der Import-Knopf stand zuletzt als Kopfzeile in dieser Liste — und war damit
 * nur zu finden, wer das Erkunden-Blatt erst aufzieht. Seither haelt der
 * Karten-Screen die Aktion selbst (`rememberActivityImportAction` in
 * `ui/ActivityImportAction.kt`) und zeigt sie in der immer sichtbaren
 * Touren-Zeile des eingeklappten Erkunden-Blatts (`ui/map/ExploreSheet.kt`).
 * Diese Liste bekommt davon nur noch [onImportFile] fuer ihren Leerzustand
 * gereicht — derselbe Weg, keine zweite Verdrahtung.
 *
 * ## Tippen zeigt auf der Karte, „Details" oeffnet die Vollansicht
 * Frueher oeffnete ein Tipp auf eine Karte sofort die Detailansicht. Jetzt
 * liegt die Liste selbst ueber der Karte — der naheliegende Zweck eines Tipps
 * ist deshalb, die Route der Tour dort zu zeigen ([onShowOnMap]), nicht in
 * eine weitere Ansicht zu wechseln. Wer wirklich Kennzahlen, Hoehenprofil und
 * Auswertung sehen will, tippt auf den eigens beschrifteten Knopf „Details"
 * auf der Karte ([onOpenDetail]) — ein Weg, der ohne Raten auffindbar ist,
 * weil er als Text und nicht nur im Ueberlaufmenue steht.
 *
 * ## Undo-Snackbar bleibt lokal, „normale" Meldungen nicht mehr
 * [AppViewModel.messages] sammelt jetzt der Karten-Screen ein (er umschliesst
 * dieses Blatt und bleibt bestehen, waehrend das Blatt auf- und zufaehrt) —
 * eine erkannte Dublette oder der Import-Erfolg laufen also weiterhin ueber
 * [AppViewModel.showMessage], zeigen sich aber dort. Die „Rückgängig"-Snackbar
 * beim Loeschen ist etwas anderes: Sie braucht eine Aktionsschaltflaeche und
 * eine eigene Anzeigedauer (siehe [DeleteRideWithUndo]), Dinge, die der einfache
 * Text-Kanal von `messages` nicht kennt. Diese Datei bringt dafuer einen
 * eigenen, kleinen [SnackbarHostState] mit und zeichnet ihn selbst als
 * Overlay — nicht ueber einen zusaetzlichen Parameter nach aussen gereicht:
 * Die Compose-Version dieses Projekts (Material 3 1.4.0) kennt kein
 * `LocalSnackbarHostState`, und ein Blatt ueber einer Karte ist der falsche Ort
 * fuer eine Annahme darueber, wie der Behaelter seinen eigenen SnackbarHost
 * aufbaut. Ein selbstaendiges Overlay funktioniert unabhaengig davon.
 *
 * @param onImportFile startet den Einzelimport (GPX/FIT) — die eine, vom
 *   Karten-Screen gehaltene Aktion (`rememberActivityImportAction`), hier nur
 *   fuer den Knopf „GPX-/FIT-Datei öffnen" im Leerzustand gebraucht.
 * @param contentPadding wird unveraendert an die `LazyColumn` durchgereicht.
 *   Der Behaelter (das Tourenblatt) traegt hierueber die Bodenfreiheit der
 *   schwebenden Navigationskapsel bei — und, je nach Blatthoehe, zusaetzliche
 *   Raender. Der Standardwert (kein Rand) gilt nur, wenn niemand etwas
 *   uebergibt, etwa in einer Vorschau.
 */
@Composable
fun TourListContent(
    appViewModel: AppViewModel,
    onOpenDetail: (String) -> Unit,
    onShowOnMap: (RideSummary) -> Unit,
    onImportFile: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val loading by appViewModel.ridesLoading.collectAsStateWithLifecycle()
    val selectedId by appViewModel.selectedRideId.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()

    var renameTarget by remember { mutableStateOf<RideSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<RideSummary?>(null) }

    // Nur fuer die „Rückgängig"-Snackbar (siehe Klassen-KDoc oben) — normale
    // Meldungen laufen nicht mehr durch diesen Screen.
    val snackbarHostState = remember { SnackbarHostState() }
    val undoSnackbarJob = remember { mutableStateOf<Job?>(null) }

    // Teilen liegt als lokale Funktion vor, damit Liste und Kartenmenue
    // nachweislich denselben Weg nehmen (siehe [shareGpx] am Dateiende).
    // Die Liste haelt nur Zusammenfassungen — fuer das GPX wird die volle
    // Tour on-demand geladen.
    fun share(summary: RideSummary) {
        scope.launch {
            try {
                val full = appViewModel.loadRide(summary.id)
                    ?: error("Die Tour-Datei ist nicht mehr lesbar.")
                shareGpx(context, full)
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
    }

    // Breite ja, Hoehe nein: Das Tourenblatt deckelt die Hoehe nur nach oben
    // (`heightIn(max = …)` in `ExploreSheet.kt`). Wuerde hier `fillMaxSize`
    // stehen, nähme die Liste diese Obergrenze immer ein — ein Blatt, das
    // auch mit zwei Touren 80 % des Bildschirms verdeckt, davon vier Fuenftel
    // leer. So waechst das Blatt mit seinem Inhalt und scrollt erst, wenn es
    // an die Grenze stoesst.
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            loading && rides.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Feste Hoehe statt `fillMaxSize` aus demselben Grund:
                    // Ein Ladekreis soll das Blatt nicht auf volle Hoehe
                    // aufziehen.
                    .height(LoadingRowHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            rides.isEmpty() -> Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(contentPadding),
            ) {
                RidesEmptyState(
                    onRecord = { appViewModel.requestTab(AppTab.MAP) },
                    onImportFile = onImportFile,
                    onOpenBackup = { appViewModel.requestTab(AppTab.MORE) },
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth(),
                contentPadding = contentPadding,
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
                        onShowOnMap = { onShowOnMap(ride) },
                        onOpenDetail = { onOpenDetail(ride.id) },
                        onRename = { renameTarget = ride },
                        onShare = { share(ride) },
                        onDelete = { deleteTarget = ride },
                    )
                }
            }
        }

        // Eigenes, kleines Overlay statt eines vom Behaelter uebernommenen
        // SnackbarHost — Begruendung im Klassen-KDoc. Der untere Rand folgt
        // demselben [contentPadding], damit die Meldung nicht hinter der
        // schwebenden Navigationskapsel verschwindet.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
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
        DeleteRideWithUndo(
            rideId = ride.id,
            appViewModel = appViewModel,
            scope = scope,
            snackbarHostState = snackbarHostState,
            undoSnackbarJob = undoSnackbarJob,
            onDismiss = { deleteTarget = null },
        )
    }

}

/**
 * Detailansicht einer Tour samt ihrer Dialoge (Umbenennen, Loeschen, Teilen)
 * als eigenstaendige Vollbildansicht — Nachfolger des fruesheren
 * `if (detailRide != null)`-Zweigs von `TourList.kt`.
 *
 * ## Warum eine Tour-ID statt eines internen Zustands
 * `RidesScreen` merkte sich die geoeffnete Tour frueher selbst
 * (`rememberSaveable`), weil Liste und Detail **derselbe** Bildschirm waren.
 * Jetzt ist die Liste ein Blatt ueber der Karte und die Detailansicht ein
 * eigenstaendiges Vollbild, das der Karten-Screen darueber legt — welche Tour
 * offen ist, haelt deshalb er als lokalen Zustand fest (angestossen ueber das
 * `onOpenDetail`-Callback von [TourListContent]) und reicht ihn hier als
 * einfachen Parameter herein. Diese Funktion bleibt bewusst zustandslos
 * gegenueber der ID selbst; sie schlaegt die Zusammenfassung aus
 * [AppViewModel.rides] nach und laedt die volle Tour (mit Punkten) on-demand
 * ueber [AppViewModel.loadRide] — dieselbe Begruendung wie zuvor: Nach einem
 * Umbenennen oder einem HF-Merge aus Health Connect aendert sich `updatedAt`
 * der Zusammenfassung, und ueber (ID, updatedAt) zeigt die Ansicht immer auf
 * den aktuellen Stand.
 *
 * Verschwindet die Tour aus der Liste, waehrend die Ansicht offen ist (Loeschen
 * anderswo, Sync), ruft diese Funktion [onBack] von selbst auf — genau wie
 * frueher, als das schlicht ein Wechsel zurueck zur Liste war.
 *
 * ## Meldungen bleiben hier eingesammelt
 * Anders als [TourListContent] sammelt diese Funktion [AppViewModel.messages]
 * weiterhin selbst ein: Sie ist ein Vollbild mit eigenem `Scaffold` und eigener
 * `SnackbarHost` (siehe `RideDetailScreen.kt`), nicht ein Blatt vor einer
 * dauerhaften Karte — es gibt keinen laenger lebenden Bildschirm darunter, der
 * die Meldungen sonst zeigen wuerde. Ein Teilen-Fehler etwa muss genau hier
 * auftauchen.
 */
@Composable
fun RideDetailHost(
    rideId: String,
    appViewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val summary = rides.firstOrNull { it.id == rideId }

    // Die volle Tour (mit Punkten) wird on-demand geladen — die Liste haelt
    // nur noch Zusammenfassungen. Schluessel ist (ID, updatedAt): Nach einem
    // Umbenennen oder HF-Merge aendert sich updatedAt und die Ansicht laedt
    // die neue Fassung; der bereits angezeigte Stand bleibt waehrenddessen
    // stehen (produceState behaelt seinen Wert ueber Schluesselwechsel).
    val ride by produceState<Ride?>(initialValue = null, rideId, summary?.updatedAt) {
        if (summary != null) {
            value = appViewModel.loadRide(rideId)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<RideSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<RideSummary?>(null) }
    val undoSnackbarJob = remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Siehe Klassen-KDoc: Eine Tour, die aus der Liste verschwindet, waehrend
    // diese Ansicht offen ist, schliesst die Ansicht von selbst.
    LaunchedEffect(summary) {
        if (summary == null) onBack()
    }

    // Die Systemzurueckgeste fuehrt aus dem Detail zurueck, unabhaengig davon,
    // ob die Tour schon geladen ist.
    BackHandler(onBack = onBack)

    val loaded = ride ?: return

    fun share(target: Ride) {
        scope.launch {
            try {
                shareGpx(context, target)
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
    }

    RideDetailScreen(
        ride = loaded,
        appViewModel = appViewModel,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRename = { renameTarget = summary },
        onShare = { share(loaded) },
        onDelete = { deleteTarget = summary },
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

    deleteTarget?.let { target ->
        DeleteRideWithUndo(
            rideId = target.id,
            appViewModel = appViewModel,
            scope = scope,
            snackbarHostState = snackbarHostState,
            undoSnackbarJob = undoSnackbarJob,
            onDismiss = { deleteTarget = null },
            // Die Detailansicht der geloeschten Tour muss zu sein, bevor die
            // Undo-Snackbar erscheint — anders als in der Liste gibt es hier
            // sonst nichts mehr anzuzeigen.
            onDeleted = onBack,
        )
    }
}

/**
 * Bestaetigungsdialog fuer das Loeschen samt „Rückgängig"-Snackbar — von
 * [TourListContent] und [RideDetailHost] gemeinsam genutzt, damit beide
 * nachweislich denselben Loeschweg nehmen und der Undo-Mechanismus nicht
 * zweimal (und womoeglich unterschiedlich) gebaut wird.
 *
 * ## Loeschen mit „Rückgängig"
 * Der Bestaetigungsdialog entfernt die Tour sofort aus der Liste
 * ([AppViewModel.deleteRideWithUndo]), loescht die Datei aber erst nach
 * [UNDO_DELETE_GRACE_MS], solange in der Zwischenzeit keine weitere Loeschung
 * dazwischenkommt. Waehrenddessen zeigt [snackbarHostState] eine Snackbar
 * „Tour gelöscht" mit Aktion „Rückgängig".
 *
 * Die Snackbar laeuft ueber `withTimeoutOrNull(UNDO_DELETE_GRACE_MS)`: Tippt
 * niemand auf „Rückgängig", verschwindet sie nach Ablauf der Frist von selbst —
 * zeitgleich mit dem im ViewModel laufenden Loesch-Timer.
 *
 * @param undoSnackbarJob Anzeige-Coroutine der aktuellen Undo-Snackbar, vom
 *   Aufrufer gehalten (`remember { mutableStateOf<Job?>(null) }`) und
 *   ueberlebt deshalb ueber mehrere Loeschungen hinweg. Ueberschreibt sich
 *   selbst bei jeder neuen Loeschung — `Job.cancel()` auf die vorige laesst
 *   deren Snackbar sofort verschwinden, statt sie hinter der neuen
 *   einzureihen. Eine zweite Loeschung waehrend einer noch offenen Snackbar
 *   bricht deren Anzeige-Coroutine ab; im ViewModel schliesst dieselbe Aktion
 *   die vorige Loeschung sofort endgueltig ab.
 * @param onDeleted zusaetzlich zu [onDismiss] aufgerufen, sobald die Loeschung
 *   ausgeloest wurde — in [RideDetailHost] etwa, um sofort zur Liste
 *   zurueckzukehren (dort gibt es sonst nichts mehr anzuzeigen).
 *
 * Stirbt der Prozess waehrend der Frist, bleibt die Datei einfach liegen — die
 * Tour taucht beim naechsten Start ganz normal wieder auf. Akzeptierter
 * Kompromiss, siehe KDoc von [AppViewModel.deleteRideWithUndo].
 */
/**
 * Loescht sofort und bietet stattdessen „Rückgängig" an — **ohne** vorherige
 * Nachfrage.
 *
 * Hier stand ein Bestaetigungsdialog *und* danach die Rueckgaengig-Snackbar:
 * zwei Sicherheitsnetze fuer dieselbe Handlung. Samsungs Leitfaden schliesst
 * das aus — ein Bestaetigungsdialog gehoert nur dorthin, wo sich das Geloeschte
 * **nicht** leicht wiederherstellen laesst; sonst wird sofort geloescht. Und
 * wiederherstellbar ist es hier bewiesenermassen: [AppViewModel.deleteRideWithUndo]
 * nimmt die Tour zunaechst nur aus der Liste und raeumt die Datei erst nach
 * [UNDO_DELETE_GRACE_MS] weg.
 *
 * Der Gewinn ist nicht nur Regeltreue. Eine Nachfrage, die man immer mit „Ja"
 * beantwortet, erzieht zum Wegklicken — und entwertet damit genau die
 * Nachfragen, bei denen es ernst wird. Die Snackbar ist das ehrlichere
 * Werkzeug: Sie kostet keinen Handgriff, wenn alles stimmt, und rettet den
 * Fehlgriff trotzdem.
 *
 * Kein Dialog mehr, also auch keine sichtbare Oberflaeche — dieses Composable
 * ist reiner Effekt. Der Aufrufer setzt es genauso ein wie vorher den Dialog
 * (bei gesetztem `deleteTarget` einbinden); es meldet sich ueber [onDismiss]
 * selbst wieder ab.
 */
@Composable
private fun DeleteRideWithUndo(
    rideId: String,
    appViewModel: AppViewModel,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    undoSnackbarJob: MutableState<Job?>,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    LaunchedEffect(rideId) {
        undoSnackbarJob.value?.cancel()
        appViewModel.deleteRideWithUndo(rideId)
        // Bewusst `scope` und nicht der Effekt-Bereich: Der Effekt endet mit
        // dem `onDismiss()` gleich darunter, die Snackbar soll aber ihre
        // volle Frist stehen bleiben.
        undoSnackbarJob.value = scope.launch {
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
        onDeleted()
        onDismiss()
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
 * Eine Tour-Karte der Liste.
 *
 * Ein Tipp auf die Karte zeigt die Route auf der Karte dahinter
 * ([onShowOnMap]) statt die Detailansicht zu oeffnen — Begruendung im KDoc von
 * [TourListContent]. Der eigens beschriftete Knopf „Details" unten rechts
 * fuehrt in die Vollansicht ([onOpenDetail]); Umbenennen, Teilen und Loeschen
 * bleiben im Ueberlaufmenue, weil sie seltener gebraucht werden und dort schon
 * vorher lagen.
 */
@Composable
private fun RideCard(
    ride: RideSummary,
    loadText: String?,
    selected: Boolean,
    onShowOnMap: () -> Unit,
    onOpenDetail: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // `selectable` statt `clickable`: Die Auswahl stand bisher
            // ausschliesslich in der Flaechenfarbe — wer den Bildschirm
            // vorlesen laesst, erfuhr nie, welche Tour gerade auf der Karte
            // liegt. Der Leitfaden verlangt, Zustaende anzusagen, und
            // „ausgewaehlt" ist ausdruecklich einer davon.
            .selectable(selected = selected, onClick = onShowOnMap),
        // Unselektiert erbt die Karte ihre Flaeche aus dem Theme (Default von
        // Card, siehe theme/Color.kt); nur der Auswahlzustand hebt sie
        // bewusst auf secondaryContainer.
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.secondaryContainer else Color.Unspecified,
        ),
    ) {
        // CardPadding (20 dp) ringsum wie in jeder anderen Karte der App;
        // rechts bleibt es bei 4 dp, weil der IconButton daneben seinen
        // eigenen Beruehrungsrand von 12 dp mitbringt und die Karte sonst
        // rechts zu luftig wirkt.
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
                    // Eine Zeile, nicht zwei: Der Leitfaden begrenzt den Titel
                    // einer Listenzeile ausdruecklich auf eine Zeile — nur so
                    // bleiben die Zeilen gleich hoch und die Liste ueberfliegbar.
                    // Was nicht hineinpasst, steht vollstaendig in der
                    // Detailansicht; die Kennzahlen darunter unterscheiden zwei
                    // aehnlich benannte Touren ohnehin zuverlaessiger als ihr
                    // abgeschnittener Name.
                    Text(
                        text = ride.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (loadText != null) {
                        Text(
                            text = loadText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    // Eine gespeicherte Planung sieht in dieser Liste sonst
                    // genauso aus wie eine gefahrene Tour — sie zaehlt aber
                    // weder fuer den Wochenfortschritt noch fuer die
                    // Trainingsauswertung (siehe `:core`: `Ride.planned`).
                    // Ohne Kennzeichnung waere ihr Fehlen in den Zahlen ein
                    // Fehler, mit Kennzeichnung ist es eine Auskunft.
                    if (ride.planned) {
                        TagPill(text = "geplante Route")
                    }
                    if (ride.id.startsWith("hc-")) {
                        TagPill(text = "aus Health Connect")
                    }
                }

                TextButton(onClick = onOpenDetail) { Text("Details") }
            }
        }
    }
}

/**
 * Was die Tourenliste kann, solange nichts gespeichert ist.
 *
 * Textbudget wie in allen Leerzustaenden (`ui/components/EmptyState.kt`): ein
 * Satz, dann die Aktionen. Die drei Wege, auf denen eine Tour hier landen
 * kann, stehen als Knoepfe da und brauchen keine Vorrede; das ZIP-Wissen wohnt
 * beim Archiv-Import selbst (Mehr -> Daten & Backup).
 */
@Composable
private fun RidesEmptyState(
    onRecord: () -> Unit,
    onImportFile: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    EmptyState(
        title = "Noch keine Touren",
        body = "Jede aufgezeichnete oder importierte Tour landet hier — mit Distanz, " +
            "Dauer, Höhenmetern und Trainingslast.",
        actions = {
            Button(onClick = onRecord) { Text("Tour aufzeichnen") }
            TextButton(onClick = onImportFile) { Text("GPX-/FIT-Datei öffnen") }
            TextButton(onClick = onOpenBackup) { Text("Archiv importieren") }
        },
    )
}

/** Eine Kennzahl der Tour — dieselbe Grammatik wie ueberall ([Fact]). */
@Composable
private fun RideFact(label: String, value: String) {
    Fact(label = label, value = value)
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
