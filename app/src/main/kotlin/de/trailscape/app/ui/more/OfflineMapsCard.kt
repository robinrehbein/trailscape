package de.trailscape.app.ui.more

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.formatBytes
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.map.readOfflineRegionInfo
import de.trailscape.app.ui.mapStyles
import de.trailscape.app.ui.withCause
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

/**
 * Offline-Karten-Verwaltung.
 *
 * Kein direktes Vorbild in `lib/screens/more_screen.dart` (das Original hat
 * dort seinen eigenen Kachel-Cache samt Kachelzahl und „Kacheln löschen",
 * `TileCache` aus `lib/tile_cache.dart`). Die native App nutzt fuer
 * Offline-Karten stattdessen MapLibres eigene Offline-Regionen — diese Karte
 * ist also eine bewusste Neuentwicklung, kein Port.
 *
 * Zustaendigkeitsgrenze: Der **Download** neuer Regionen gehoert dem
 * Karten-Screen (`ui/map/OfflineRegions.kt`) — diese Karte listet nur
 * bestehende Regionen, zeigt Stil, Datum und Groesse und loescht sie (einzeln
 * oder alle). Die Metadaten liest sie mit
 * [de.trailscape.app.ui.map.readOfflineRegionInfo], also mit genau dem Leser,
 * der zum Schreiber der Download-Seite gehoert — ein eigener, halb passender
 * JSON-Decoder an dieser Stelle hat frueher Stil und Zeitpunkt schlicht
 * verworfen.
 *
 * Der Inhalt der Zeile „Offline-Karten" in der Gruppe „Karte" des Mehr-Tabs
 * (siehe `MoreScreen.kt`) — keine eigene Karte mehr, `MoreRow` stellt Titel
 * und Aufklapp-Rahmen.
 *
 * @param onMessage Kanal fuer kurze Rueckmeldungen (Loeschfehler); im Mehr-Tab
 *   `AppViewModel::showMessage`, damit die Snackbar dieselbe ist wie ueberall.
 */
@Composable
fun OfflineMapsCardContent(onMessage: (String) -> Unit = {}) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var regions by remember { mutableStateOf<List<OfflineRegionRow>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var busyRegionId by remember { mutableStateOf<Long?>(null) }
    var deleteAllBusy by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteRegion by remember { mutableStateOf<OfflineRegionRow?>(null) }

    LaunchedEffect(reloadToken) {
        loading = true
        errorText = null
        try {
            regions = listOfflineRegionsWithStatus(context)
        } catch (e: Exception) {
            // Vorher gewann die englische MapLibre-Meldung; der deutsche Satz
            // kam nur zum Vorschein, wenn sie leer war (siehe ui/ErrorText.kt).
            errorText = withCause(
                "Die gespeicherten Offline-Karten ließen sich nicht auflisten. " +
                    "Starte die App neu; bleibt es dabei, hilft ein Blick in die Karte, " +
                    "die den Kartenspeicher selbst öffnet.",
                e,
            )
        } finally {
            loading = false
        }
    }

    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = "Für die Offline-Nutzung heruntergeladene Kartenausschnitte. Der Download " +
            "neuer Ausschnitte läuft über die Karte.",
        style = MaterialTheme.typography.bodySmall,
        color = hintColor,
    )
    Spacer(modifier = Modifier.height(12.dp))

    when {
        loading -> Text("Lade …", style = MaterialTheme.typography.bodyMedium)
        errorText != null -> Text(
            text = errorText ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        regions.isEmpty() -> Text(
            text = "Keine Offline-Karten gespeichert.",
            style = MaterialTheme.typography.bodyMedium,
        )
        else -> {
            Column {
                regions.forEachIndexed { index, info ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = info.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = info.details,
                                style = MaterialTheme.typography.bodySmall,
                                color = hintColor,
                            )
                        }
                        if (busyRegionId == info.id) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.height(20.dp),
                            )
                        } else {
                            IconButton(onClick = { confirmDeleteRegion = info }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Löschen")
                            }
                        }
                    }
                    if (index != regions.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            NeutralButton(
                onClick = { confirmDeleteAll = true },
                enabled = !deleteAllBusy,
                destructive = true,
            ) { Text("Alle löschen") }
        }
    }

    confirmDeleteRegion?.let { target ->
        OneUiDialog(
            onDismissRequest = { confirmDeleteRegion = null },
            title = { Text("Offline-Karte löschen") },
            text = { Text("Soll „${target.name}“ wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteRegion = null
                        busyRegionId = target.id
                        deleteOfflineRegionAsync(target.region) { success ->
                            busyRegionId = null
                            if (success) {
                                reloadToken++
                            } else {
                                onMessage(DELETE_FAILED_MESSAGE)
                            }
                        }
                    },
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteRegion = null }) { Text("Abbrechen") }
            },
        )
    }

    if (confirmDeleteAll) {
        OneUiDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Offline-Karten löschen") },
            text = { Text("Sollen alle heruntergeladenen Kartenausschnitte wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        deleteAllBusy = true
                        deleteAllOfflineRegionsAsync(regions.map { it.region }) { failed ->
                            deleteAllBusy = false
                            reloadToken++
                            if (failed > 0) {
                                onMessage(
                                    if (failed == 1) {
                                        DELETE_FAILED_MESSAGE
                                    } else {
                                        "$failed Offline-Karten konnten nicht gelöscht " +
                                            "werden. Läuft gerade ein Download, warte ihn " +
                                            "ab und versuche es dann erneut."
                                    },
                                )
                            }
                        }
                    },
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") }
            },
        )
    }
}

/** Eine gelistete MapLibre-Offline-Region mit den fuer die UI aufbereiteten Feldern. */
private data class OfflineRegionRow(
    val id: Long,
    val name: String,
    /** Untertitel: Kartenstil · Datum · Groesse, soweit bekannt. */
    val details: String,
    val region: OfflineRegion,
)

/**
 * Meldung, wenn MapLibre das Loeschen einer Region ablehnt.
 *
 * Mit Handlungsanweisung: Der haeufigste Grund ist ein Download, der zu genau
 * dieser Region noch laeuft — dann geht es nach dessen Ende von selbst.
 */
private const val DELETE_FAILED_MESSAGE =
    "Die Offline-Karte konnte nicht gelöscht werden. Läuft gerade ein Download " +
        "für diesen Ausschnitt, warte ihn ab und versuche es dann erneut."

/**
 * Laedt alle gespeicherten Offline-Regionen samt Downloadstatus (fuer die
 * Groessenanzeige). `MapLibre.getInstance(context)` ist idempotent und wird
 * hier vorsorglich aufgerufen, falls der Karten-Screen (der die Kartenansicht
 * selbst initialisiert) noch nicht sichtbar war.
 */
private suspend fun listOfflineRegionsWithStatus(context: Context): List<OfflineRegionRow> {
    val appContext = context.applicationContext
    MapLibre.getInstance(appContext)
    val manager = OfflineManager.getInstance(appContext)

    val rawRegions = suspendCancellableCoroutine<List<OfflineRegion>> { cont ->
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (cont.isActive) cont.resume(offlineRegions?.toList() ?: emptyList())
            }

            override fun onError(error: String) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error))
            }
        })
    }

    return rawRegions.map { region ->
        val status = runCatching { offlineRegionStatus(region) }.getOrNull()
        val info = readOfflineRegionInfo(region.metadata)
        OfflineRegionRow(
            id = region.id,
            name = info?.name?.takeIf { it.isNotBlank() } ?: fallbackRegionName(region),
            details = buildList {
                mapStyles.firstOrNull { it.id == info?.styleId }?.let { add(it.label) }
                info?.createdAtMs
                    ?.takeIf { it > 0L }
                    ?.let { add(formatDate(it)) }
                add(formatBytes(status?.completedResourceSize))
                // Eine Region ohne eine einzige Kachel ist der Rest eines
                // abgebrochenen Downloads (frueher blieb so etwas nach dem
                // haengenden „0/1"-Balken liegen). MapLibre selbst meldet sie
                // als „vollstaendig", weil es fuer gespeicherte Regionen die
                // Sollzahl gleich der Istzahl setzt — also sagen wir es hier.
                if (status != null && status.completedTileCount <= 0L) {
                    add("unvollständig – bitte löschen")
                }
            }.joinToString(" · "),
            region = region,
        )
    }
}

private suspend fun offlineRegionStatus(region: OfflineRegion): OfflineRegionStatus? =
    suspendCancellableCoroutine { cont ->
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (cont.isActive) cont.resume(status)
            }

            override fun onError(error: String?) {
                if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException(error ?: "Status unbekannt"))
                }
            }
        })
    }

/**
 * Startet das Loeschen im Hintergrund und meldet das Ergebnis ueber
 * [onDone] zurueck (Main-Thread, wie die MapLibre-Callbacks selbst). Bewusst
 * ohne `rememberCoroutineScope`/`LaunchedEffect` an der Aufrufstelle, damit
 * ein Loeschvorgang eine Recomposition (z. B. Dialog schliessen) uebersteht.
 */
private fun deleteOfflineRegionAsync(region: OfflineRegion, onDone: (success: Boolean) -> Unit) {
    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = onDone(true)
        override fun onError(error: String) = onDone(false)
    })
}

/**
 * Loescht alle uebergebenen Regionen und meldet ueber [onDone], wie viele
 * davon fehlgeschlagen sind.
 */
private fun deleteAllOfflineRegionsAsync(
    regions: List<OfflineRegion>,
    onDone: (failed: Int) -> Unit,
) {
    if (regions.isEmpty()) {
        onDone(0)
        return
    }
    var remaining = regions.size
    var failed = 0
    val finishOne = { success: Boolean ->
        if (!success) failed++
        remaining--
        if (remaining <= 0) onDone(failed)
    }
    regions.forEach { region ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                finishOne(true)
            }

            override fun onError(error: String) {
                finishOne(false)
            }
        })
    }
}

/**
 * Anzeigename einer Region, deren Metadaten nicht von dieser App stammen (oder
 * unlesbar sind): Kartenstil aus der Style-URL plus laufende Nummer.
 *
 * Die Style-URL traegt die Stilkennung im Pfad — sowohl die heutige Adresse
 * aus [de.trailscape.app.ui.map.offlineStyleUrl]
 * (`https://offline-style.trailscape.invalid/voyager.json`) als auch die
 * `file://`-Adresse aelterer Regionen (`…/map-styles/voyager.json`). Das
 * schlichte `contains` erkennt deshalb beide.
 */
private fun fallbackRegionName(region: OfflineRegion): String {
    val definition = region.definition
    if (definition is OfflineTilePyramidRegionDefinition) {
        val styleUrl = definition.styleURL
        val styleLabel = mapStyles.firstOrNull { styleUrl?.contains(it.id) == true }?.label
        return "${styleLabel ?: "Kartenausschnitt"} #${region.id}"
    }
    return "Region #${region.id}"
}
