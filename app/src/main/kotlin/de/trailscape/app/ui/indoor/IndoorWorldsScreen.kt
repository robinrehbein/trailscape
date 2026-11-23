package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.EmptyState
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.theme.LocalSignalColors

/**
 * # Weltverwaltung
 *
 * Alle Welten auf dem Geraet mit Strecke, Qualitaet, Groesse, Datum und
 * Quellenangaben (docs/indoor.md 8.6 und 10.4). Welten, deren Strecke es nicht
 * mehr gibt, sind markiert und werden zum Loeschen **angeboten** — nie
 * stillschweigend geloescht. Geloescht und geleert wird erst nach Bestaetigung.
 *
 * @param onDelete bekommt die `worldId` der Welt.
 * @param onClearCache leert den Geo-Zwischenspeicher (Hoehen-, Vektor-, Luftbildkacheln);
 *   fertige Welten bleiben.
 */
@Composable
fun IndoorWorldsScreen(
    ui: IndoorWorldsUi,
    onDelete: (String) -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
) {
    val formats = LocalAppFormats.current
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    IndoorPage(
        title = stringResource(R.string.indoor_app_worlds_title),
        onBack = onBack,
    ) {
        MutedText(stringResource(R.string.indoor_app_worlds_intro))

        if (ui.loading) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LabelValueRow(
                        label = stringResource(R.string.indoor_app_worlds_total),
                        value = formats.indoorBytes(ui.totalBytes),
                    )
                    LabelValueRow(
                        label = stringResource(R.string.indoor_app_worlds_cache),
                        value = formats.indoorBytes(ui.cacheBytes),
                    )
                    MutedText(stringResource(R.string.indoor_app_worlds_cache_hint))
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = ui.cacheBytes > 0L,
                        modifier = Modifier.offset(x = (-12).dp),
                    ) {
                        Text(stringResource(R.string.indoor_app_worlds_clear_action))
                    }
                }
            }

            if (ui.worlds.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.indoor_app_worlds_empty_title),
                    body = stringResource(R.string.indoor_app_worlds_empty_body),
                )
            } else {
                Eyebrow(stringResource(R.string.indoor_app_worlds_list_eyebrow))
                ui.worlds.forEach { world ->
                    WorldCard(world = world, onDelete = { pendingDelete = world.worldId })
                }
            }
        }
    }

    val deleting = ui.worlds.firstOrNull { it.worldId == pendingDelete }
    if (pendingDelete != null && deleting != null) {
        OneUiDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.indoor_app_worlds_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.indoor_app_worlds_delete_confirm_body,
                        deleting.routeName,
                        formats.indoorBytes(deleting.bytes),
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.indoor_app_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = deleting.worldId
                        pendingDelete = null
                        onDelete(id)
                    },
                ) {
                    Text(stringResource(R.string.indoor_app_worlds_delete_action))
                }
            },
        )
    }

    if (confirmClear) {
        OneUiDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.indoor_app_worlds_clear_confirm_title)) },
            text = {
                Text(stringResource(R.string.indoor_app_worlds_clear_confirm_body, formats.indoorBytes(ui.cacheBytes)))
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.indoor_app_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearCache()
                    },
                ) {
                    Text(stringResource(R.string.indoor_app_worlds_clear_confirm_action))
                }
            },
        )
    }
}

@Composable
private fun WorldCard(world: IndoorWorldEntryUi, onDelete: () -> Unit) {
    val formats = LocalAppFormats.current
    var showAttribution by rememberSaveable(world.worldId) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = world.routeName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                if (!world.complete) {
                    TagPill(text = stringResource(R.string.indoor_app_worlds_tag_incomplete))
                } else if (world.fromCatalog) {
                    TagPill(text = stringResource(R.string.indoor_app_worlds_tag_catalog))
                }
            }
            LabelValueRow(
                label = stringResource(R.string.indoor_app_worlds_size),
                value = formats.indoorBytes(world.bytes),
            )
            world.quality?.let { quality ->
                LabelValueRow(
                    label = stringResource(R.string.indoor_app_worlds_quality),
                    value = qualityLabel(quality),
                )
            }
            world.createdAt?.let { created ->
                LabelValueRow(
                    label = stringResource(R.string.indoor_app_worlds_created),
                    value = formats.dateFull(created),
                )
            }
            if (world.orphaned) {
                NoticeBox(
                    icon = Icons.Filled.Warning,
                    color = LocalSignalColors.current.warning,
                    text = stringResource(R.string.indoor_app_worlds_orphaned),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (world.attribution.isNotEmpty()) {
                    TextButton(onClick = { showAttribution = !showAttribution }, modifier = Modifier.offset(x = (-12).dp)) {
                        Text(
                            stringResource(
                                if (showAttribution) R.string.indoor_app_worlds_attribution_hide else R.string.indoor_app_worlds_attribution_show,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete, modifier = Modifier.offset(x = 12.dp)) {
                    Text(
                        text = stringResource(R.string.indoor_app_worlds_delete_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (showAttribution && world.attribution.isNotEmpty()) AttributionList(world.attribution)
        }
    }
}
