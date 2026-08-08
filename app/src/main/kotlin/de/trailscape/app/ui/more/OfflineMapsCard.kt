package de.trailscape.app.ui.more

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import de.trailscape.app.ui.mapStyles
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Karten-Screen (`ui/map/`, Parallel-Agent) — diese Karte listet nur
 * bestehende Regionen, zeigt ihre Groesse und loescht sie (einzeln oder
 * alle). Es wird bewusst NICHT auf eine `ui/map/OfflineRegions.kt` verwiesen,
 * falls der Karten-Agent so etwas anlegt — das koennte parallel entstehen und
 * ist beim Bauen dieser Datei nicht garantiert vorhanden; hier wird direkt
 * gegen `org.maplibre.android.offline.*` programmiert.
 */
@Composable
fun OfflineMapsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(true) }
    var regions by remember { mutableStateOf<List<OfflineRegionInfo>>(emptyList()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var busyRegionId by remember { mutableStateOf<Long?>(null) }
    var deleteAllBusy by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteRegion by remember { mutableStateOf<OfflineRegionInfo?>(null) }

    LaunchedEffect(reloadToken) {
        loading = true
        errorText = null
        try {
            regions = listOfflineRegionsWithStatus(context)
        } catch (e: Exception) {
            errorText = e.message ?: "Offline-Karten konnten nicht geladen werden."
        } finally {
            loading = false
        }
    }

    MoreSectionCard(title = "Offline-Karten", modifier = modifier) {
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            text = "Für die Offline-Nutzung heruntergeladene Kartenausschnitte. Der Download " +
                "neuer Ausschnitte läuft über die Karte.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(modifier = Modifier.height(12.dp))

        when {
            loading -> Text("Lade …")
            errorText != null -> Text(errorText ?: "")
            regions.isEmpty() -> Text("Keine Offline-Karten gespeichert.")
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
                                    text = formatOfflineRegionSize(info.sizeBytes),
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
                OutlinedButton(
                    onClick = { confirmDeleteAll = true },
                    enabled = !deleteAllBusy,
                ) { Text("Alle löschen") }
            }
        }
    }

    confirmDeleteRegion?.let { target ->
        AlertDialog(
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
                            if (success) reloadToken++
                        }
                    },
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteRegion = null }) { Text("Abbrechen") }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Offline-Karten löschen") },
            text = { Text("Sollen alle heruntergeladenen Kartenausschnitte wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        deleteAllBusy = true
                        deleteAllOfflineRegionsAsync(regions.map { it.region }) {
                            deleteAllBusy = false
                            reloadToken++
                        }
                    },
                ) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Abbrechen") }
            },
        )
    }
}

/** Eine gelistete MapLibre-Offline-Region mit den fuer die UI aufbereiteten Feldern. */
private data class OfflineRegionInfo(
    val id: Long,
    val name: String,
    val sizeBytes: Long?,
    val region: OfflineRegion,
)

private fun formatOfflineRegionSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Größe unbekannt"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) {
        String.format(Locale.GERMANY, "%.1f MB", mb)
    } else {
        String.format(Locale.GERMANY, "%.0f KB", bytes / 1024.0)
    }
}

/**
 * Laedt alle gespeicherten Offline-Regionen samt Downloadstatus (fuer die
 * Groessenanzeige). `MapLibre.getInstance(context)` ist idempotent und wird
 * hier vorsorglich aufgerufen, falls der Karten-Screen (der die Kartenansicht
 * selbst initialisiert) noch nicht sichtbar war.
 */
private suspend fun listOfflineRegionsWithStatus(context: Context): List<OfflineRegionInfo> {
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
        OfflineRegionInfo(
            id = region.id,
            name = describeOfflineRegion(region),
            sizeBytes = status?.completedResourceSize,
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

private fun deleteAllOfflineRegionsAsync(regions: List<OfflineRegion>, onDone: () -> Unit) {
    if (regions.isEmpty()) {
        onDone()
        return
    }
    var remaining = regions.size
    val finishOne = {
        remaining--
        if (remaining <= 0) onDone()
    }
    regions.forEach { region ->
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = finishOne()
            override fun onError(error: String) = finishOne()
        })
    }
}

/**
 * Anzeigename einer Region: zuerst aus den Metadaten (falls sie — Konvention
 * vieler MapLibre-/Mapbox-Apps — ein JSON-Objekt mit einem `name`-Feld
 * enthalten), sonst ein Fallback aus Kartenstil und laufender Nummer.
 */
private fun describeOfflineRegion(region: OfflineRegion): String {
    decodeOfflineRegionName(region.metadata)?.let { return it }

    val definition = region.definition
    if (definition is OfflineTilePyramidRegionDefinition) {
        val styleUrl = definition.styleURL
        val styleLabel = mapStyles.firstOrNull { styleUrl?.contains(it.id) == true }?.label
        return "${styleLabel ?: "Kartenausschnitt"} #${region.id}"
    }
    return "Region #${region.id}"
}

private fun decodeOfflineRegionName(metadata: ByteArray?): String? {
    if (metadata == null || metadata.isEmpty()) return null
    return try {
        val json = Json.parseToJsonElement(metadata.toString(Charsets.UTF_8))
        if (json !is JsonObject) return null
        listOf("name", "FIELD_REGION_NAME", "regionName")
            .firstNotNullOfOrNull { key ->
                (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
