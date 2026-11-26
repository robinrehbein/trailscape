package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.components.TrailscapePrimaryButton
import de.trailscape.app.ui.components.TrailscapeSecondaryButton
import de.trailscape.app.ui.theme.ContentMaxWidth

/**
 * # Detailansicht einer bekannten Strecke
 *
 * Grosses Bild, vollstaendiges Hoehenprofil, Beschreibung, Zahlen, Belaege,
 * Quellen, Paketgroesse und die Aktionen — alle ueber die vorhandenen Wege
 * der Startseite (Auswaehlen, Herunterladen, Abbrechen, Verwerfen, Fahren).
 * Ein Vollbild-Dialog; der Inhalt steht in [CatalogDetailContent], damit er
 * sich ohne Dialogfenster pruefen laesst.
 */
@Composable
internal fun CatalogDetailDialog(
    row: CatalogRow,
    selected: Boolean,
    canRide: Boolean,
    onSelect: (IndoorRouteOption) -> Unit,
    onRide: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            CatalogDetailContent(
                row = row,
                selected = selected,
                canRide = canRide,
                onSelect = onSelect,
                onRide = onRide,
                onDownload = onDownload,
                onCancel = onCancel,
                onDiscard = onDiscard,
                onClose = onDismiss,
                modifier = Modifier.systemBarsPadding(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogDetailContent(
    row: CatalogRow,
    selected: Boolean,
    canRide: Boolean,
    onSelect: (IndoorRouteOption) -> Unit,
    onRide: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = row.item
    val formats = LocalAppFormats.current
    val lang = formats.language.tag
    val name = item.name.pick(lang)
    val option = IndoorRouteOption.Catalog(item.id, name, item.distanceKm, item.ascentM)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.indoor_i10_detail_close))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Grosses Bild; ohne Bild das Profil im Bildbereich (wie auf der Karte).
            CatalogPreviewArea(item, name)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = listOf(item.region.pick(lang), countryName(item.country, formats.language.locale))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TagPill(text = kindLabel(item.kind))
                    CatalogStatusPill(item)
                    CatalogRiddenPill(row)
                }
                CatalogFactsGrid(row)
                if (row.bestS == null) {
                    MutedText(stringResource(R.string.indoor_i10_never_ridden))
                } else {
                    Text(
                        text = stringResource(R.string.indoor_i10_best_time) + ": " + catalogDuration(row.bestS),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }

                Section(R.string.indoor_i10_detail_profile) {
                    if (item.profile != null) {
                        CatalogProfileChart(item.profile, name, Modifier.fillMaxWidth().height(160.dp).padding(top = 26.dp), showRange = true)
                    } else {
                        MutedText(stringResource(R.string.indoor_i10_detail_profile_none))
                    }
                }
                Section(R.string.indoor_i10_detail_about) {
                    Text(item.description.pick(lang), style = MaterialTheme.typography.bodyLarge)
                    if (item.laser) MutedText(stringResource(R.string.indoor_i10_detail_laser))
                }
                Section(R.string.indoor_i10_detail_surfaces) {
                    CatalogSurfaceSummary(item)
                }
                Section(R.string.indoor_i10_detail_package) {
                    Text(
                        text = packageText(item, formats),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Section(R.string.indoor_i10_detail_sources) {
                    Text(stringResource(R.string.indoor_i10_detail_source_map), style = MaterialTheme.typography.bodyMedium)
                    val source = item.aerialSource
                    Text(
                        text = when {
                            item.aerial && !source.isNullOrBlank() -> stringResource(R.string.indoor_i10_detail_source_aerial, source)
                            item.aerial -> stringResource(R.string.indoor_i10_detail_source_aerial, "—")
                            else -> stringResource(R.string.indoor_i10_detail_source_aerial_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                CatalogDetailActions(row, selected, canRide, option, onSelect, onRide, onDownload, onCancel, onDiscard, onClose)
            }
        }
    }
    }
}

@Composable
private fun packageText(item: IndoorCatalogItemUi, formats: de.trailscape.app.i18n.AppFormats): String = when {
    item.packageBytes <= 0 -> stringResource(R.string.indoor_app_home_status_download_unknown)
    item.diskBytes > 0 -> stringResource(R.string.indoor_i10_detail_package_value, formats.indoorBytes(item.packageBytes), formats.indoorBytes(item.diskBytes))
    else -> stringResource(R.string.indoor_i10_detail_package_download_only, formats.indoorBytes(item.packageBytes))
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

/**
 * Die Aktionen je nach Stand:
 *  * bereit: „Diese Strecke fahren" (mit Trainer) bzw. „Strecke auswaehlen";
 *  * nicht geladen / gescheitert / angehalten: „Herunterladen" (waehlt die Strecke und startet den Download);
 *  * laeuft: „Download abbrechen";
 *  * angehaengter oder fehlgeschlagener Download: zusaetzlich „Verwerfen";
 *  * Aktualisieren bei neuer Fassung.
 * Eine fertig geladene Strecke loescht man in den Einstellungen (Hinweis).
 */
@Composable
private fun CatalogDetailActions(
    row: CatalogRow,
    selected: Boolean,
    canRide: Boolean,
    option: IndoorRouteOption.Catalog,
    onSelect: (IndoorRouteOption) -> Unit,
    onRide: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onClose: () -> Unit,
) {
    val item = row.item
    val formats = LocalAppFormats.current
    val size = if (item.packageBytes > 0) formats.indoorBytes(item.packageBytes) else null
    val downloadText = size?.let { stringResource(R.string.indoor_i10_action_download, it) }
        ?: stringResource(R.string.indoor_i10_action_download_unknown)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
        if (item.ready) {
            if (canRide) {
                TrailscapePrimaryButton(
                    onClick = { onSelect(option); onClose(); onRide() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.indoor_i10_action_ride)) }
            }
            if (!selected) {
                if (canRide) {
                    TrailscapeSecondaryButton(onClick = { onSelect(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_i10_action_choose))
                    }
                } else {
                    TrailscapePrimaryButton(onClick = { onSelect(option) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_i10_action_choose))
                    }
                }
            } else {
                MutedText(stringResource(R.string.indoor_i10_action_chosen))
            }
            (item.state as? IndoorCatalogState.UpdateAvailable)?.let {
                TrailscapeSecondaryButton(onClick = { onSelect(option); onDownload(item.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(size?.let { s -> stringResource(R.string.indoor_i10_action_update, s) } ?: stringResource(R.string.indoor_i10_action_download_unknown))
                }
            }
            MutedText(stringResource(R.string.indoor_i10_hint_delete_installed))
        } else {
            when (val s = item.state) {
                is IndoorCatalogState.Downloading -> TrailscapeSecondaryButton(onClick = { onCancel(item.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.indoor_i10_action_cancel))
                }
                IndoorCatalogState.Installing -> Unit
                else -> {
                    TrailscapePrimaryButton(onClick = { onSelect(option); onDownload(item.id) }, modifier = Modifier.fillMaxWidth()) { Text(downloadText) }
                    if (s is IndoorCatalogState.Paused || s is IndoorCatalogState.Failed) {
                        TrailscapeSecondaryButton(
                            onClick = { onDiscard(item.id) },
                            destructive = true,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.indoor_i10_action_discard))
                        }
                    }
                }
            }
        }
    }
}
