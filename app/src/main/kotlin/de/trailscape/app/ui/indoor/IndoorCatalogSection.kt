package de.trailscape.app.ui.indoor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.TagPill
import de.trailscape.core.indoor.catalog.CatalogProfile
import de.trailscape.core.indoor.catalog.CatalogRideKind
import java.util.Locale

/**
 * # Bereich „Bekannte Strecken" der Streckenwahl (docs/indoor/catalog.md)
 *
 * Karten mit Vorschau (Bild der Welt, sonst Hoehenprofil, sonst
 * Platzhalter), Kennzeichen „Laser"/„Neu", Name, Land, Laenge,
 * Hoehenmetern, Belag, Paketgroesse und Zustand. Eine bereite Strecke ist auswaehlbar wie eine eigene; alles andere
 * hat einen Knopf („Laden", „Fortsetzen", „Abbrechen", „Aktualisieren").
 * Zustandslos — Aktionen gehen nach oben ins `IndoorViewModel`.
 */
@Composable
internal fun IndoorCatalogSection(
    catalog: IndoorCatalogUi,
    selectedKey: String?,
    onSelect: (IndoorRouteOption) -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    MutedText(stringResource(R.string.indoor_app_catalog_intro))
    when {
        catalog.loading -> Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        catalog.items.isEmpty() -> MutedText(stringResource(R.string.indoor_app_catalog_empty))
        else -> catalog.items.forEach { item ->
            CatalogCard(
                item = item,
                selected = selectedKey == "catalog:${item.id}",
                onSelect = onSelect,
                onDownload = { onDownload(item.id) },
                onCancel = { onCancel(item.id) },
                onDiscard = { onDiscard(item.id) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onRefresh, enabled = !catalog.refreshing) {
            if (catalog.refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                text = stringResource(R.string.indoor_app_catalog_refresh),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    catalog.refreshResult?.let { r ->
        MutedText(
            stringResource(
                when (r) {
                    IndoorCatalogRefresh.UPDATED -> R.string.indoor_app_catalog_refresh_done
                    IndoorCatalogRefresh.NOT_AVAILABLE -> R.string.indoor_app_catalog_refresh_not_available
                    IndoorCatalogRefresh.FAILED -> R.string.indoor_app_catalog_refresh_failed
                },
            ),
        )
    }
    MutedText(stringResource(R.string.indoor_app_catalog_privacy))
}

@Composable
private fun CatalogCard(
    item: IndoorCatalogItemUi,
    selected: Boolean,
    onSelect: (IndoorRouteOption) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
) {
    val formats = LocalAppFormats.current
    val lang = formats.language.tag
    val name = item.name.pick(lang)
    val option = IndoorRouteOption.Catalog(item.id, name, item.distanceKm, item.ascentM)
    val selectable = if (item.ready) {
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) })
    } else {
        Modifier
    }
    Card(
        modifier = Modifier.fillMaxWidth().then(selectable),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        CatalogPreviewArea(item, name)
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.ready) RadioButton(selected = selected, onClick = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        text = listOf(item.region.pick(lang), countryName(item.country, formats.language.locale))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TagPill(text = kindLabel(item.kind))
            }
            Text(
                text = stringResource(
                    R.string.indoor_app_catalog_meta,
                    formats.km(item.distanceKm),
                    formats.indoorAscent(item.ascentM),
                    surfaceSummary(item),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Mit Bild steht das Profil klein unter den Zahlen; ohne Bild steht es oben gross.
            if (item.profile != null && item.previewPath != null) {
                CatalogProfileChart(item.profile, name, Modifier.fillMaxWidth().height(44.dp), showRange = true)
            }
            Text(
                text = item.description.pick(lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = listOfNotNull(
                    if (item.packageBytes > 0) stringResource(R.string.indoor_app_catalog_package, formats.indoorBytes(item.packageBytes)) else null,
                    stringResource(if (item.aerial) R.string.indoor_app_catalog_aerial_yes else R.string.indoor_app_catalog_aerial_no),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.laser) {
                Text(
                    text = stringResource(R.string.indoor_app_catalog_laser_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CatalogStateRow(item, onDownload, onCancel, onDiscard)
        }
    }
}

@Composable
private fun CatalogStateRow(item: IndoorCatalogItemUi, onDownload: () -> Unit, onCancel: () -> Unit, onDiscard: () -> Unit) {
    val formats = LocalAppFormats.current
    val size = if (item.packageBytes > 0) formats.indoorBytes(item.packageBytes) else null
    when (val s = item.state) {
        IndoorCatalogState.NotLoaded -> OutlinedButton(onClick = onDownload) {
            Text(
                if (size != null) {
                    stringResource(R.string.indoor_app_catalog_download, size)
                } else {
                    stringResource(R.string.indoor_app_catalog_download_unknown)
                },
            )
        }
        is IndoorCatalogState.Paused -> {
            ProgressLine(s.doneBytes, s.totalBytes, indeterminate = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.indoor_app_catalog_resume)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.indoor_app_catalog_discard)) }
            }
        }
        is IndoorCatalogState.Downloading -> {
            ProgressLine(s.doneBytes, s.totalBytes, indeterminate = s.totalBytes <= 0)
            TextButton(onClick = onCancel, enabled = !s.cancelling) {
                Text(stringResource(if (s.cancelling) R.string.indoor_app_catalog_cancelling else R.string.indoor_app_action_cancel))
            }
        }
        IndoorCatalogState.Installing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            MutedText(stringResource(R.string.indoor_app_catalog_installing))
        }
        IndoorCatalogState.Ready -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill(
                text = stringResource(R.string.indoor_app_catalog_ready),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            MutedText(stringResource(R.string.indoor_app_catalog_ready_hint))
        }
        is IndoorCatalogState.UpdateAvailable -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagPill(text = stringResource(R.string.indoor_app_catalog_update_tag))
                MutedText(stringResource(R.string.indoor_app_catalog_update_hint))
            }
            OutlinedButton(onClick = onDownload) {
                Text(
                    if (size != null) {
                        stringResource(R.string.indoor_app_catalog_update, size)
                    } else {
                        stringResource(R.string.indoor_app_catalog_update_unknown)
                    },
                )
            }
        }
        is IndoorCatalogState.Failed -> {
            NoticeBox(
                icon = when (s.reason) {
                    IndoorCatalogFailure.OFFLINE -> Icons.Filled.CloudOff
                    IndoorCatalogFailure.NOT_AVAILABLE -> Icons.Filled.Info
                    else -> Icons.Filled.Warning
                },
                color = if (s.reason == IndoorCatalogFailure.NOT_AVAILABLE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                text = stringResource(
                    when (s.reason) {
                        IndoorCatalogFailure.NOT_AVAILABLE -> R.string.indoor_app_catalog_failed_not_available
                        IndoorCatalogFailure.OFFLINE -> R.string.indoor_app_catalog_failed_offline
                        IndoorCatalogFailure.CHECKSUM -> R.string.indoor_app_catalog_failed_checksum
                        IndoorCatalogFailure.NO_SPACE -> R.string.indoor_app_catalog_failed_space
                        IndoorCatalogFailure.OTHER -> R.string.indoor_app_catalog_failed_other
                    },
                ),
            )
            if (s.reason != IndoorCatalogFailure.NOT_AVAILABLE) TechnicalDetail(s.detail)
            if (s.installed) MutedText(stringResource(R.string.indoor_app_catalog_failed_keeps_old))
            if (s.doneBytes > 0) ProgressLine(s.doneBytes, item.packageBytes, indeterminate = false)
            OutlinedButton(onClick = onDownload) {
                Text(stringResource(if (s.doneBytes > 0) R.string.indoor_app_catalog_resume else R.string.indoor_app_action_retry))
            }
        }
    }
}

@Composable
private fun ProgressLine(done: Long, total: Long, indeterminate: Boolean) {
    val formats = LocalAppFormats.current
    if (indeterminate) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        MutedText(stringResource(R.string.indoor_app_catalog_progress_unknown, formats.indoorBytes(done)))
    } else {
        val fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        MutedText(
            stringResource(
                R.string.indoor_app_catalog_progress,
                indoorPercent(fraction),
                formats.indoorBytes(done),
                formats.indoorBytes(total),
            ),
        )
    }
}

@Composable
private fun kindLabel(kind: CatalogRideKind): String = stringResource(
    when (kind) {
        CatalogRideKind.ROAD -> R.string.indoor_app_catalog_kind_road
        CatalogRideKind.GRAVEL -> R.string.indoor_app_catalog_kind_gravel
        CatalogRideKind.COBBLES -> R.string.indoor_app_catalog_kind_cobbles
    },
)

/** „Asphalt 95 %, Pflaster 5 %" — hoechstens drei Belaege ab 5 %. */
@Composable
private fun surfaceSummary(item: IndoorCatalogItemUi): String {
    val parts = item.surfaces.filter { it.second >= 0.05 }.take(3).map { (kind, f) ->
        stringResource(R.string.indoor_app_catalog_surface_share, surfaceLabel(kind), (f * 100).toInt().coerceAtLeast(1))
    }
    return parts.joinToString(", ").ifEmpty { surfaceLabel(de.trailscape.core.indoor.SurfaceKind.UNKNOWN) }
}

/**
 * Oberer Teil der Karte: Vorschaubild der Welt; ohne Bild das Hoehenprofil;
 * ohne beides ein ruhiger Platzhalter. Darueber die Kennzeichen „Laser" und
 * „Neu".
 */
@Composable
private fun CatalogPreviewArea(item: IndoorCatalogItemUi, name: String) {
    val bitmap = remember(item.previewPath) {
        item.previewPath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    Box(modifier = Modifier.fillMaxWidth().clip(shape)) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.indoor_app_catalog_preview_image, name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            item.profile != null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 8.dp),
            ) {
                CatalogProfileChart(item.profile, name, Modifier.fillMaxSize(), showRange = true)
            }
            else -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    Icons.Outlined.Landscape,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.indoor_app_catalog_preview_missing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.laser) {
                TagPill(
                    text = stringResource(R.string.indoor_app_catalog_laser),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (item.isNew) {
                TagPill(
                    text = stringResource(R.string.indoor_app_catalog_new),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Hoehenprofil einer Katalogstrecke: Flaeche und Linie, flache Strecken
 * nicht kuenstlich steil (mindestens 40 m Spanne), mit [showRange] die
 * Spanne „612–812 m" oben rechts.
 */
@Composable
private fun CatalogProfileChart(profile: CatalogProfile, name: String, modifier: Modifier, showRange: Boolean) {
    val formats = LocalAppFormats.current
    val lo = profile.elevM.min()
    val hi = profile.elevM.max()
    val description = stringResource(
        R.string.indoor_app_catalog_profile_description,
        name, Math.round(lo).toInt(), Math.round(hi).toInt(), formats.km(profile.lengthM / 1000.0),
    )
    val line = MaterialTheme.colorScheme.primary
    val fill = line.copy(alpha = 0.18f)
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = description }) {
            val w = size.width
            val h = size.height
            val n = profile.elevM.size
            if (w <= 0f || h <= 0f || n < 2) return@Canvas
            val span = maxOf(hi - lo, MIN_CATALOG_PROFILE_SPAN_M)
            val mid = (hi + lo) / 2
            val pad = 2.dp.toPx()
            fun y(e: Double) = (pad + (h - 2 * pad) * (1 - (e - (mid - span / 2)) / span)).toFloat()
            val path = Path()
            val area = Path()
            for (i in 0 until n) {
                val x = w * i / (n - 1)
                val yy = y(profile.elevM[i])
                if (i == 0) {
                    path.moveTo(x, yy)
                    area.moveTo(x, h)
                    area.lineTo(x, yy)
                } else {
                    path.lineTo(x, yy)
                    area.lineTo(x, yy)
                }
            }
            area.lineTo(w, h)
            area.close()
            drawPath(area, fill)
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawLine(base, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1.dp.toPx())
        }
        if (showRange) {
            Text(
                text = stringResource(R.string.indoor_app_catalog_profile_range, Math.round(lo).toInt(), Math.round(hi).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).offset(y = (-18).dp),
            )
        }
    }
}

/** Kleinste dargestellte Hoehenspanne — Arenberg (20 Hm) soll nicht wie ein Pass aussehen. */
private const val MIN_CATALOG_PROFILE_SPAN_M = 40.0

private fun countryName(code: String, locale: Locale): String =
    if (code.length == 2) Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code } else code
