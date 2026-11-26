package de.trailscape.app.ui.indoor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.RouteCard
import de.trailscape.app.ui.components.TagPill
import de.trailscape.core.indoor.catalog.CatalogFilter
import de.trailscape.core.indoor.catalog.CatalogProfile
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogTimeBasis
import java.util.Locale

/**
 * # Bekannte Strecken in der Streckenliste der Startseite (docs/indoor/catalog.md)
 *
 * Karten mit Vorschau (Bild der Strecke, sonst Hoehenprofil, sonst
 * Platzhalter), Kennzeichen „Extra detailliert"/„Neu", Name, Land, Laenge,
 * Hoehenmetern, Belag und einem **Status** („Bereit", „Herunterladen · 77 MB",
 * „Laedt · 45 %"). Jede Karte ist auswaehlbar, gleich in welchem Zustand —
 * was dann noch fehlt (Herunterladen, Fortsetzen, Abbrechen), steht unten auf
 * der Startseite ueber „Fahren" ([IndoorRouteReadiness]). So gibt es pro
 * Karte keinen eigenen Knopf mehr und genau einen Weg: waehlen, dann unten
 * das tun, was dort steht. Zustandslos.
 */
@Composable
internal fun IndoorCatalogList(
    catalog: IndoorCatalogUi,
    selectedKey: String?,
    onSelect: (IndoorRouteOption) -> Unit,
    extras: IndoorCatalogExtras = IndoorCatalogExtras(),
    canRide: Boolean = false,
    onRide: () -> Unit = {},
    onDownload: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
    onDiscard: (String) -> Unit = {},
    initialFilterOpen: Boolean = false,
    initialDetailId: String? = null,
) {
    val formats = LocalAppFormats.current
    val lang = formats.language.tag
    when {
        catalog.loading -> Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        catalog.items.isEmpty() -> MutedText(stringResource(R.string.indoor_app_catalog_empty))
        else -> {
            val filter by CatalogBrowseSession.filter.collectAsState()
            val rows = remember(catalog.items, extras) { catalogRows(catalog.items, extras) }
            val shown = remember(rows, filter, lang) { rows.filteredBy(filter) { it.item.name.pick(lang) } }
            var detailId by rememberSaveable { mutableStateOf(initialDetailId) }
            CatalogFilterBar(
                filter = filter,
                shown = shown.size,
                total = rows.size,
                onChange = CatalogBrowseSession::set,
                initiallyOpen = initialFilterOpen,
            )
            if (shown.isEmpty()) {
                CatalogNoMatch(onReset = { CatalogBrowseSession.set(filter.cleared()) })
            }
            shown.forEach { row ->
                CatalogCard(
                    row = row,
                    selected = selectedKey == "catalog:${row.item.id}",
                    onSelect = onSelect,
                    onDetails = { detailId = row.item.id },
                )
            }
            // Eine Strecke, die der Filter gerade ausblendet, bleibt im Detail offen.
            val detail = rows.firstOrNull { it.item.id == detailId }
            if (detail != null) {
                CatalogDetailDialog(
                    row = detail,
                    selected = selectedKey == "catalog:${detail.item.id}",
                    canRide = canRide,
                    onSelect = onSelect,
                    onRide = onRide,
                    onDownload = onDownload,
                    onCancel = onCancel,
                    onDiscard = onDiscard,
                    onDismiss = { detailId = null },
                )
            }
        }
    }
}

/** Erweitert ein `LazyVerticalGrid` um Filterleiste und 2-spaltige Katalog-Karten (Tablet quer). */
internal fun LazyGridScope.indoorCatalogGridItems(
    catalog: IndoorCatalogUi,
    rows: List<CatalogRow>,
    shown: List<CatalogRow>,
    filter: CatalogFilter,
    selectedKey: String?,
    onSelect: (IndoorRouteOption) -> Unit,
    onOpenDetail: (String) -> Unit,
    initialFilterOpen: Boolean = false,
) {
    when {
        catalog.loading -> item(span = { GridItemSpan(2) }) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        catalog.items.isEmpty() -> item(span = { GridItemSpan(2) }) {
            MutedText(stringResource(R.string.indoor_app_catalog_empty))
        }
        else -> {
            item(span = { GridItemSpan(2) }) {
                CatalogFilterBar(
                    filter = filter,
                    shown = shown.size,
                    total = rows.size,
                    onChange = CatalogBrowseSession::set,
                    initiallyOpen = initialFilterOpen,
                )
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    CatalogNoMatch(onReset = { CatalogBrowseSession.set(filter.cleared()) })
                }
            }
            items(shown, key = { "catalog:${it.item.id}" }) { row ->
                CatalogCard(
                    row = row,
                    selected = selectedKey == "catalog:${row.item.id}",
                    onSelect = onSelect,
                    onDetails = { onOpenDetail(row.item.id) },
                )
            }
        }
    }
}

/** Ergebnis von „Liste aktualisieren" als ein Satz (Einstellungen). */
@Composable
internal fun catalogRefreshText(r: IndoorCatalogRefresh): String = stringResource(
    when (r) {
        IndoorCatalogRefresh.UPDATED -> R.string.indoor_app_catalog_refresh_done
        IndoorCatalogRefresh.NOT_AVAILABLE -> R.string.indoor_app_catalog_refresh_not_available
        IndoorCatalogRefresh.FAILED -> R.string.indoor_app_catalog_refresh_failed
    },
)

@Composable
internal fun CatalogCard(
    row: CatalogRow,
    selected: Boolean,
    onSelect: (IndoorRouteOption) -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = row.item
    val formats = LocalAppFormats.current
    val lang = formats.language.tag
    val name = item.name.pick(lang)
    val option = IndoorRouteOption.Catalog(item.id, name, item.distanceKm, item.ascentM)
    val subtitle = listOf(
        item.region.pick(lang),
        countryName(item.country, formats.language.locale),
        kindLabel(item.kind),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    RouteCard(
        title = name,
        subtitle = subtitle.ifBlank { null },
        distanceText = stringResource(R.string.indoor_i10_km_value, formats.km(item.distanceKm)),
        ascentText = stringResource(R.string.indoor_app_value_elevation, formats.indoorAscent(item.ascentM)),
        durationText = row.estimateS?.let { catalogDuration(it) } ?: INDOOR_NO_VALUE,
        statusLabel = catalogStatusText(item),
        selected = selected,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(option) }),
        media = { CatalogRouteMedia(item, name) },
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = catalogGradeText(row, formats),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CatalogSurfaceSummary(item)
                // Mit Bild steht das Profil klein unter den Zahlen; ohne Bild steht es oben gross.
                if (item.profile != null && item.previewPath != null) {
                    CatalogProfileChart(item.profile, name, Modifier.fillMaxWidth().height(40.dp), showRange = false)
                }
                Text(
                    text = item.description.pick(lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CatalogRiddenPill(row)
                    }
                    val detailsCd = stringResource(R.string.indoor_i10_details_cd, name)
                    TextButton(onClick = onDetails, modifier = Modifier.semantics { contentDescription = detailsCd }) {
                        Text(stringResource(R.string.indoor_i10_details))
                    }
                }
            }
        },
    )
}

@Composable
internal fun CatalogRouteMedia(item: IndoorCatalogItemUi, name: String) {
    val bitmap = remember(item.previewPath) {
        item.previewPath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.indoor_app_catalog_preview_image, name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            item.profile != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 8.dp),
            ) {
                CatalogProfileChart(item.profile, name, Modifier.fillMaxSize(), showRange = false)
            }
            else -> Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
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
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
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

/** „Gefahren · Bestzeit 41 min" — nur, wenn es eine volle Indoor-Fahrt auf dieser Strecke gibt. */
@Composable
internal fun CatalogRiddenPill(row: CatalogRow) {
    val best = row.bestS ?: return
    TagPill(
        text = stringResource(R.string.indoor_i10_ridden, catalogDuration(best)),
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

/**
 * Die Kennzahlen der Karte in festen Feldern: Laenge, Hoehenmeter, Steigung
 * (Durchschnitt und Maximum) und die geschaetzte Fahrzeit mit Hinweis, woher
 * sie kommt. Umbrechen bei grosser Schrift.
 */
@Composable
internal fun CatalogFactsGrid(row: CatalogRow, modifier: Modifier = Modifier) {
    val formats = LocalAppFormats.current
    val item = row.item
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Fact(stringResource(R.string.indoor_i10_facts_length), stringResource(R.string.indoor_i10_km_value, formats.km(item.distanceKm)))
        Fact(stringResource(R.string.indoor_i10_facts_ascent), stringResource(R.string.indoor_i10_ascent_value, formats.indoorAscent(item.ascentM)))
        Fact(stringResource(R.string.indoor_i10_facts_grade), catalogGradeText(row, formats))
        val est = row.estimateS
        if (est != null) {
            Fact(
                stringResource(R.string.indoor_i10_facts_time),
                stringResource(R.string.indoor_i10_time_value, catalogDuration(est)),
                note = stringResource(
                    if (row.timeBasis == CatalogTimeBasis.PROFILE) R.string.indoor_i10_time_basis_profile else R.string.indoor_i10_time_basis_neutral,
                ),
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, note: String? = null) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Belagsleiste mit Text („Asphalt 59 %, Pflaster 29 %") — die Leiste allein sagt dem Vorleser nichts. */
@Composable
internal fun CatalogSurfaceSummary(item: IndoorCatalogItemUi) {
    val text = surfaceSummary(item)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SurfaceBar(item.surfaces, stringResource(R.string.indoor_i10_surface_bar_cd, text))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Der Status einer bekannten Strecke als Marke: bereit (Signalfarbe),
 * herunterladen mit Groesse, laedt mit Prozent, angehalten, entpacken,
 * fehlgeschlagen, neue Fassung.
 */
@Composable
internal fun catalogStatusText(item: IndoorCatalogItemUi): String {
    val formats = LocalAppFormats.current
    val size = if (item.packageBytes > 0) formats.indoorBytes(item.packageBytes) else null
    fun pct(done: Long, total: Long): Int = indoorPercent(if (total > 0) (done.toDouble() / total).toFloat() else 0f)
    return when (val s = item.state) {
        IndoorCatalogState.Ready -> stringResource(R.string.indoor_app_home_status_ready)
        is IndoorCatalogState.UpdateAvailable -> stringResource(R.string.indoor_app_home_status_update)
        IndoorCatalogState.NotLoaded ->
            size?.let { stringResource(R.string.indoor_app_home_status_download, it) }
                ?: stringResource(R.string.indoor_app_home_status_download_unknown)
        is IndoorCatalogState.Paused -> stringResource(R.string.indoor_app_home_status_paused, pct(s.doneBytes, s.totalBytes))
        is IndoorCatalogState.Downloading -> stringResource(R.string.indoor_app_home_status_downloading, indoorPercent(s.fraction))
        IndoorCatalogState.Installing -> stringResource(R.string.indoor_app_home_status_installing)
        is IndoorCatalogState.Failed ->
            if (s.installed) {
                stringResource(R.string.indoor_app_home_status_ready)
            } else {
                stringResource(R.string.indoor_app_home_status_failed)
            }
    }
}

@Composable
internal fun CatalogStatusPill(item: IndoorCatalogItemUi) {
    val text = catalogStatusText(item)
    val strong = when (val s = item.state) {
        IndoorCatalogState.Ready, is IndoorCatalogState.UpdateAvailable -> true
        is IndoorCatalogState.Failed -> s.installed
        else -> false
    }
    if (strong) {
        TagPill(
            text = text,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        TagPill(text = text)
    }
}

/** Fortschritt eines Downloads: Balken und „45 % · 35 MB von 77 MB". */
@Composable
internal fun CatalogProgressLine(done: Long, total: Long, indeterminate: Boolean) {
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

/** Warum ein Download scheiterte, als ein Satz. */
@Composable
internal fun catalogFailureText(reason: IndoorCatalogFailure): String = stringResource(
    when (reason) {
        IndoorCatalogFailure.NOT_AVAILABLE -> R.string.indoor_app_catalog_failed_not_available
        IndoorCatalogFailure.OFFLINE -> R.string.indoor_app_catalog_failed_offline
        IndoorCatalogFailure.CHECKSUM -> R.string.indoor_app_catalog_failed_checksum
        IndoorCatalogFailure.NO_SPACE -> R.string.indoor_app_catalog_failed_space
        IndoorCatalogFailure.OTHER -> R.string.indoor_app_catalog_failed_other
    },
)

@Composable
internal fun kindLabel(kind: CatalogRideKind): String = stringResource(
    when (kind) {
        CatalogRideKind.ROAD -> R.string.indoor_app_catalog_kind_road
        CatalogRideKind.GRAVEL -> R.string.indoor_app_catalog_kind_gravel
        CatalogRideKind.COBBLES -> R.string.indoor_app_catalog_kind_cobbles
    },
)

/** „Asphalt 95 %, Pflaster 5 %" — hoechstens drei Belaege ab 5 %. */
@Composable
internal fun surfaceSummary(item: IndoorCatalogItemUi): String {
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
internal fun CatalogPreviewArea(item: IndoorCatalogItemUi, name: String) {
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
internal fun CatalogProfileChart(profile: CatalogProfile, name: String, modifier: Modifier, showRange: Boolean) {
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

internal fun countryName(code: String, locale: Locale): String =
    if (code.length == 2) Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code } else code
