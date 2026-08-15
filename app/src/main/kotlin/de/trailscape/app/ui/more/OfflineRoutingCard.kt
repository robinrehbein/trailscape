package de.trailscape.app.ui.more

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.data.AppServices
import de.trailscape.app.routing.InstalledSegment
import de.trailscape.app.routing.SEGMENT_PART_SUFFIX
import de.trailscape.app.routing.SegmentDownloads
import de.trailscape.app.routing.SegmentOffer
import de.trailscape.app.routing.SegmentPhase
import de.trailscape.app.routing.describeSegmentOffer
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.formatBytes
import de.trailscape.app.ui.map.currentLocation
import de.trailscape.app.ui.map.hasLocationPermission
import de.trailscape.app.ui.map.missingPermissions
import de.trailscape.core.GeoResult
import de.trailscape.core.parseSegmentTile
import de.trailscape.core.searchPlaces
import de.trailscape.core.segmentTileAt
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Verwaltung der **Routingdaten** fuer das Rechnen ohne Netz.
 *
 * ## Warum eine eigene Karte neben „Offline-Karten"
 * Beide Karten laden „Karten" herunter, und genau deshalb muessen sie
 * unterscheidbar bleiben: [OfflineMapsCard] speichert das **Kartenbild**
 * (MapLibre-Kacheln, damit man auf dem Berg etwas sieht), diese hier
 * speichert die **Wegedaten**, mit denen die App Routen berechnet
 * (BRouter-Kacheln). Wer das eine hat, hat das andere nicht — und wer glaubt,
 * es sei dasselbe, wundert sich, warum die Karte zu sehen ist, das Routing
 * aber trotzdem ins Netz will. Der Einleitungstext sagt den Unterschied
 * deshalb ausdruecklich, und die Reihenfolge im Tab stellt die beiden
 * nebeneinander.
 *
 * ## Warum es hier keine Weltkarte mit Kachelraster gibt
 * Eine 5°-Kachel ist bei uns rund 350 × 550 km gross; es gibt 72 × 36 davon.
 * Ein Raster ueber der Weltkarte waere viel Arbeit fuer eine Auswahl, die
 * praktisch nie gebraucht wird: Wer Routingdaten laedt, will sie fuer die
 * Gegend, in der er **ist** oder in die er **faehrt**. Diese Karte bietet
 * deshalb genau diese beiden Wege an —
 *
 *  * **„Für meinen Standort"** — ein Tipp, der haeufigste Fall ueberhaupt;
 *  * **Ortssuche** (dieselbe Nominatim-Suche wie im Karten-Tab) — fuer die
 *    Reise, die naechste Woche losgeht.
 *
 * Der dritte Weg braucht diese Karte gar nicht: Fehlt beim Planen eine Kachel,
 * bietet die App sie direkt dort an (siehe `AppViewModel.segmentOffer`).
 *
 * ## Warum die Groesse **vor** dem Download steht
 * Eine Kachel ist 120–240 MB. Diese Zahl wird nicht geschaetzt, sondern per
 * `HEAD` beim Server erfragt und im Bestaetigungsdialog genannt. Ohne Antwort
 * vom Server gibt es keinen Download-Knopf — lieber gar kein Angebot als eines
 * mit erfundener Zahl.
 *
 * ## Was diese Karte bewusst NICHT tut
 * Von sich aus nach Aktualisierungen suchen. Die Kacheln werden taeglich neu
 * gebaut; eine automatische Pruefung im Hintergrund waere eine Entscheidung
 * ueber fremdes Datenvolumen, die niemand getroffen hat. „Nach
 * Aktualisierungen suchen" ist deshalb ein Knopf.
 */
@Composable
fun OfflineRoutingCard(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val inventory = AppServices.segmentInventory

    val unmeteredOnly by appViewModel.segmentUnmeteredOnly.collectAsStateWithLifecycle()
    val status by remember { SegmentDownloads.statusFlow(context) }
        .collectAsStateWithLifecycle(initialValue = null)

    var reloadToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var segments by remember { mutableStateOf<List<InstalledSegment>>(emptyList()) }
    var partials by remember { mutableStateOf<List<PartialSegment>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }

    var outdated by remember { mutableStateOf<Set<String>>(emptySet()) }
    var updateCheckDone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var confirmDelete by remember { mutableStateOf<InstalledSegment?>(null) }
    var pendingOffer by remember { mutableStateOf<SegmentOffer?>(null) }

    // Ob das offene Angebot eine **Aktualisierung** ist. Der Unterschied steht
    // im Dialogtext: Der `HEAD` nennt immer die Groesse der ganzen Kachel, eine
    // Aktualisierung laeuft aber meist ueber ein Delta von ein bis zwei MB
    // (siehe `SegmentDownloader`). „119 MB" waere dann zwar die ehrliche
    // Obergrenze, aber eine irrefuehrende Erwartung.
    var pendingIsUpdate by remember { mutableStateOf(false) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoResult>>(emptyList()) }

    LaunchedEffect(reloadToken) {
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            val list = inventory.list()
            Triple(list, list.sumOf { it.sizeBytes }, readPartials(inventory.dir))
        }
        segments = loaded.first
        totalBytes = loaded.second
        partials = loaded.third
        loading = false
    }

    // Ist ein Download durch, hat sich der Bestand geaendert — und mit ihm die
    // Frage, was noch veraltet ist.
    LaunchedEffect(status?.finished, status?.fileName) {
        if (status?.finished == true) {
            outdated = emptySet()
            updateCheckDone = false
            reloadToken++
        }
    }

    /** Fragt Name und Groesse beim Server ab und oeffnet den Bestaetigungsdialog. */
    fun offerDownload(fileNames: List<String>, isUpdate: Boolean = false) {
        if (fileNames.isEmpty()) return
        pendingIsUpdate = isUpdate
        busy = true
        scope.launch {
            val offer = runCatching { describeSegmentOffer(fileNames) }.getOrNull()
            busy = false
            if (offer == null) {
                appViewModel.showMessage(
                    "Die Größe der Kartendaten ließ sich nicht abfragen. Bist du online?",
                )
            } else {
                pendingOffer = offer
            }
        }
    }

    /** Die Kachel zu einem Punkt anbieten — oder sagen, dass sie schon da ist. */
    fun offerTileAt(lat: Double, lon: Double) {
        val tile = segmentTileAt(lat, lon)
        if (segments.any { it.fileName == tile.fileName }) {
            appViewModel.showMessage("Für „${tile.title}“ sind die Routingdaten schon da.")
            return
        }
        offerDownload(listOf(tile.fileName))
    }

    /** Die Ortssuche losschicken (Nominatim, wie im Karten-Tab). */
    fun runSearch() {
        val text = query.trim()
        if (text.length < MIN_QUERY_LENGTH) return
        busy = true
        scope.launch {
            val hits = withContext(Dispatchers.IO) {
                runCatching { searchPlaces(text, AppServices.httpClient) }.getOrDefault(emptyList())
            }
            busy = false
            results = hits.take(MAX_SEARCH_HITS)
            if (hits.isEmpty()) {
                appViewModel.showMessage(
                    "Kein Ort gefunden. Versuche es mit dem Ortsnamen allein, ohne " +
                        "Straße und Postleitzahl — die Kacheln sind ohnehin " +
                        "5° × 5° groß.",
                )
            }
        }
    }

    /** Standort holen und die Kachel dazu anbieten. Setzt die Freigabe voraus. */
    fun offerTileForMyLocation() {
        busy = true
        scope.launch {
            val position = resolveLocation(context)
            busy = false
            if (position == null) {
                appViewModel.showMessage(
                    "Standort nicht verfügbar. Ist die Ortung eingeschaltet?",
                )
            } else {
                offerTileAt(position.first, position.second)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (hasLocationPermission(context)) {
            offerTileForMyLocation()
        } else {
            appViewModel.showMessage(
                "Ohne Standortfreigabe wissen wir nicht, welche Kachel du brauchst. " +
                    "Nimm die Ortssuche darunter — oder erteile die Freigabe unter " +
                    "„Einstellungen → Apps → Trailscape → Berechtigungen“.",
            )
        }
    }

    MoreSectionCard(title = "Karten für Offline-Routing", modifier = modifier) {
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            text = "Mit diesen Daten berechnet die App Routen direkt auf dem Gerät — ohne " +
                "Netz und meist schneller als über den Server. Das ist nicht das Kartenbild: " +
                "Für die Ansicht offline sind die „Offline-Karten“ zuständig.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(Modifier.height(12.dp))

        // ------------------------------------------------------- laufender Lauf
        val running = status?.takeIf { it.running && !it.finished }
        if (running != null) {
            SegmentProgressRow(
                label = downloadLabel(running.fileName, running.phase),
                detail = downloadDetail(running.bytesDone, running.bytesTotal, running.phase),
                percent = running.percent,
                index = running.index,
                count = running.count,
                onCancel = { SegmentDownloads.cancel(context) },
            )
            Spacer(Modifier.height(12.dp))
        } else {
            status?.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // ------------------------------------------------------------- Bestand
        when {
            loading -> Text("Lade …", style = MaterialTheme.typography.bodyMedium)

            segments.isEmpty() -> Text(
                text = "Noch keine Routingdaten gespeichert. Routen werden bis dahin über " +
                    "den Server berechnet.",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> {
                Column {
                    segments.forEachIndexed { index, segment ->
                        SegmentRow(
                            segment = segment,
                            outdated = segment.fileName in outdated,
                            enabled = !busy && running == null,
                            onUpdate = { offerDownload(listOf(segment.fileName), isUpdate = true) },
                            onDelete = { confirmDelete = segment },
                        )
                        if (index != segments.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Zusammen ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = hintColor,
                )
            }
        }

        // Angefangene Downloads belegen Platz, tauchen aber in keiner Liste auf.
        // Wer sich fragt, wo die 54 MB geblieben sind, findet sie hier — samt
        // dem Hinweis, dass der naechste Versuch dort aufsetzt.
        partials.forEach { partial ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Angefangen: ${partial.title} — ${formatBytes(partial.bytes)} geladen. " +
                    "Ein neuer Download setzt hier auf.",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
        }

        // ------------------------------------------------------------ Aktionen
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                enabled = !busy && running == null,
                onClick = {
                    val missing = missingPermissions(context, forRecording = false)
                    if (missing.isEmpty()) {
                        offerTileForMyLocation()
                    } else {
                        permissionLauncher.launch(missing)
                    }
                },
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Für meinen Standort")
            }

            if (segments.isNotEmpty()) {
                OutlinedButton(
                    enabled = !busy && running == null,
                    onClick = {
                        busy = true
                        scope.launch {
                            outdated = withContext(Dispatchers.IO) {
                                segments
                                    .map { it.fileName }
                                    .filter {
                                        runCatching {
                                            AppServices.segmentDownloader.hasUpdate(it)
                                        }.getOrDefault(false)
                                    }
                                    .toSet()
                            }
                            updateCheckDone = true
                            busy = false
                        }
                    },
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Nach Aktualisierungen suchen")
                }
            }
        }

        if (updateCheckDone && outdated.isEmpty() && segments.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Alles auf dem neuesten Stand.",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
        }

        // -------------------------------------------------------- Ortssuche
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy && running == null,
            label = { Text("Gegend suchen") },
            placeholder = { Text("Ort oder Region, z. B. Innsbruck") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            // Der Knopf zusaetzlich zur Eingabetaste: Wer die Tastatur wegwischt,
            // statt „Suchen" zu druecken, steht sonst vor einem Feld ohne Wirkung.
            trailingIcon = {
                IconButton(
                    onClick = { runSearch() },
                    enabled = !busy && query.trim().length >= MIN_QUERY_LENGTH,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Suchen")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
        )

        results.forEach { hit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hit.displayName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        results = emptyList()
                        query = ""
                        offerTileAt(hit.lat, hit.lon)
                    },
                ) { Text("Auswählen") }
            }
        }

        // ------------------------------------------------------- Einstellung
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Nur über WLAN laden", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Eine Kachel ist 120–240 MB. Ist der Schalter aus, lädt sie auch " +
                        "über Mobilfunk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = hintColor,
                )
            }
            Switch(
                checked = unmeteredOnly,
                onCheckedChange = appViewModel::setSegmentUnmeteredOnly,
            )
        }

        if (busy) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Einen Moment …", style = MaterialTheme.typography.bodySmall, color = hintColor)
            }
        }
    }

    // ------------------------------------------------------------- Dialoge
    pendingOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { pendingOffer = null },
            icon = { Icon(Icons.Filled.DownloadForOffline, contentDescription = null) },
            title = { Text(if (pendingIsUpdate) "Routingdaten aktualisieren" else "Routingdaten laden") },
            text = {
                Text(
                    buildString {
                        append(offer.title)
                        append(" — ")
                        if (pendingIsUpdate) {
                            append("meist nur ein bis zwei MB, im schlechtesten Fall ")
                            append(formatBytes(offer.totalBytes))
                        } else {
                            append(formatBytes(offer.totalBytes))
                        }
                        append(".")
                        append(
                            if (unmeteredOnly) {
                                " Der Download startet, sobald WLAN da ist."
                            } else {
                                " Der Download läuft auch über Mobilfunk."
                            },
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOffer = null
                        appViewModel.downloadSegments(context, offer.fileNames)
                    },
                ) { Text("Laden") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOffer = null }) { Text("Abbrechen") }
            },
        )
    }

    confirmDelete?.let { segment ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Routingdaten löschen") },
            text = {
                Text(
                    "Soll „${segment.tile.title}“ (${formatBytes(segment.sizeBytes)}) wirklich " +
                        "gelöscht werden? Routen in dieser Gegend laufen danach wieder über " +
                        "den Server.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = segment.fileName
                        confirmDelete = null
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { inventory.delete(target) }
                            if (!ok) {
                                appViewModel.showMessage(
                                    "Die Routingdaten konnten nicht gelöscht werden. " +
                                        "Läuft gerade ein Download für diese Gegend, " +
                                        "warte ihn ab und versuche es dann erneut.",
                                )
                            }
                            reloadToken++
                        }
                    },
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Bausteine
// ---------------------------------------------------------------------------

/** Eine Zeile des Bestands: Bezeichnung, Gradfeld, Groesse, Alter, Aktionen. */
@Composable
private fun SegmentRow(
    segment: InstalledSegment,
    outdated: Boolean,
    enabled: Boolean,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = segment.tile.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOf(
                    segment.tile.boundsLabel,
                    formatBytes(segment.sizeBytes),
                    segmentAgeText(segment),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outdated) {
                Text(
                    text = "Neuere Fassung verfügbar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (outdated) {
            IconButton(onClick = onUpdate, enabled = enabled) {
                Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
            }
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Filled.Delete, contentDescription = "Löschen")
        }
    }
}

/** Der laufende Download mit Balken und Abbruch. */
@Composable
private fun SegmentProgressRow(
    label: String,
    detail: String,
    percent: Int,
    index: Int,
    count: Int,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (count > 1) "$detail · Kachel ${index + 1} von $count" else detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text("Abbrechen") }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---------------------------------------------------------------------------
// Texte und Hilfen
// ---------------------------------------------------------------------------

/** Eine angefangene, noch unvollstaendige Kachel. */
private data class PartialSegment(val title: String, val bytes: Long)

/**
 * Die Teildateien im Kachelverzeichnis.
 *
 * Bewusst hier und nicht im [de.trailscape.app.routing.SegmentInventory]:
 * Dessen Aufgabe ist der **Bestand** — was die Engine benutzen kann. Eine
 * halbe Datei gehoert ausdruecklich nicht dazu (siehe dessen KDoc); sie ist
 * allein eine Frage der Anzeige.
 */
private fun readPartials(dir: File): List<PartialSegment> {
    val files = dir.listFiles() ?: return emptyList()
    return files
        .filter { it.isFile && it.name.endsWith(SEGMENT_PART_SUFFIX) && it.length() > 0 }
        .mapNotNull { file ->
            val name = file.name.removeSuffix(SEGMENT_PART_SUFFIX)
            parseSegmentTile(name)?.let { PartialSegment(it.title, file.length()) }
        }
        .sortedBy { it.title }
}

/**
 * Wie alt die **Kartendaten** sind — nicht die Datei (siehe
 * [InstalledSegment.ageDays]). „Alter unbekannt", wenn nichts gemerkt wurde;
 * eine erfundene Zahl waere hier schlimmer als die Luecke, weil an ihr die
 * Entscheidung „aktualisieren?" haengt.
 */
private fun segmentAgeText(segment: InstalledSegment): String = when (val days = segment.ageDays()) {
    null -> "Alter unbekannt"
    0L -> "von heute"
    1L -> "1 Tag alt"
    else -> "$days Tage alt"
}

/** Die Kachel eines laufenden Downloads, so lesbar wie moeglich. */
private fun downloadLabel(fileName: String?, phase: SegmentPhase?): String {
    val tile = fileName
        ?.let { parseSegmentTile(it) }
        ?.title
        ?: "Routingdaten"
    return when (phase) {
        SegmentPhase.DELTA_DOWNLOAD, SegmentPhase.DELTA_APPLY -> "$tile — Aktualisierung"
        else -> tile
    }
}

/**
 * Die Zahlen unter dem Balken.
 *
 * Waehrend [SegmentPhase.DELTA_APPLY] rechnet die Engine in Prozent statt in
 * Bytes (sie zaehlt Kachelbloecke) — dort waere „54 von 100 MB" schlicht
 * falsch.
 */
private fun downloadDetail(done: Long, total: Long, phase: SegmentPhase?): String = when (phase) {
    SegmentPhase.DELTA_APPLY -> "Wird eingearbeitet …"
    SegmentPhase.CHECK -> "Wird geprüft …"
    else -> if (total > 0) {
        "${formatBytes(done)} von ${formatBytes(total)}"
    } else {
        "Wird geladen …"
    }
}

/** Eine einzelne Position als Paar, oder `null`. */
private suspend fun resolveLocation(context: Context): Pair<Double, Double>? {
    val location = currentLocation(context) ?: return null
    return location.latitude to location.longitude
}

/** Ab wie vielen Zeichen die Ortssuche losgeschickt wird (wie im Karten-Tab). */
private const val MIN_QUERY_LENGTH = 3

/** Wie viele Treffer die Ortssuche anbietet (wie im Karten-Tab). */
private const val MAX_SEARCH_HITS = 5
