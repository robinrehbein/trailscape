package de.trailscape.app.ui.rides

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.FileImportNoticeEffect
import de.trailscape.app.ui.UNDO_DELETE_GRACE_MS
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.components.TrailscapePrimaryButton
import de.trailscape.app.ui.components.TrailscapeSecondaryButton
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalAtlasColors
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.tabular
import de.trailscape.core.Ride
import de.trailscape.core.RideSummary
import de.trailscape.core.SessionIntensity
import de.trailscape.core.planningSpeedKmh
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
 * Detailansicht; Umbenennen, Teilen, Loeschen und „Auf der Karte zeigen"
 * wohnen nur noch dort (Symbol „Teilen" und Menue der Tour in der Kopfzeile,
 * siehe `RideDetailScreen.kt`) — ein
 * Ueberlaufmenue je Zeile machte die Liste unruhig und bot dieselben vier
 * Handgriffe zweimal an. „aus Health Connect" steht ebenfalls nur noch im
 * Detail: In der Zeile war es eine zweite Textzeile fuer eine Auskunft, die
 * man beim Ueberfliegen nicht braucht.
 *
 * ## Planungen oben, fuer sich
 * Gespeicherte Planungen ([RideSummary.planned]) standen frueher ohne
 * Kennzeichen zwischen den Fahrten, einsortiert nach ihrem Erstelldatum — in
 * einer Liste, die „Was bin ich gefahren?" beantwortet, sah eine nie
 * gefahrene Route aus wie eine Fahrt. Jetzt stehen sie in einem eigenen
 * Abschnitt „Geplant" ganz oben ([splitHistory]), immer offen (Einklappen
 * waere eine versteckte Funktion), jede Zeile mit der Pille „Geplant" und
 * Laenge, Hoehenmetern und Erstelldatum statt Dauer ([PlannedRow]). Darunter
 * die gefahrenen Touren wie gehabt nach Monaten; gibt es nur Planungen, sagt
 * der gefahrene Teil ehrlich „Noch keine Fahrt".
 *
 * @param query Suchtext; filtert beide Abschnitte nach Namen ([filterRidesByName]).
 * @param onRecord / [onImportFile] / [onImportArchive] tragen den Leerzustand.
 * @param contentPadding wird an die `LazyColumn` durchgereicht und traegt die
 *   Bodenfreiheit der schwebenden Navigationskapsel.
 */
@Composable
internal fun TourListContent(
    appViewModel: AppViewModel,
    query: String,
    onOpenDetail: (String) -> Unit,
    onRecord: () -> Unit,
    onImportFile: () -> Unit,
    onImportArchive: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    selectedRideId: String? = null,
    showDetailSelection: Boolean = false,
    recordAsSecondary: Boolean = showDetailSelection && selectedRideId != null,
    kindFilter: RideKindFilter? = null,
    onKindFilterChange: ((RideKindFilter) -> Unit)? = null,
    header: LazyListScope.() -> Unit = {},
) {
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val loading by appViewModel.ridesLoading.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()

    // Das heutige Datum nur fuer die Jahresfrage der Monatsueberschrift —
    // einmal gemerkt genuegt, ein Jahreswechsel bei offener App ist egal.
    val today = remember { LocalDate.now() }
    val language = LocalAppLanguage.current
    // Art-Filter (Alle / Draussen / Indoor); die Auswahl erscheint erst, wenn
    // es eine Indoor-Fahrt gibt. Verschwindet die letzte, gilt wieder „Alle".
    val hasIndoor = remember(rides) { rides.any { it.indoor && !it.planned } }
    var localKindName by rememberSaveable { mutableStateOf(RideKindFilter.ALL.name) }
    val kind = when {
        !hasIndoor -> RideKindFilter.ALL
        kindFilter != null -> kindFilter
        else -> RideKindFilter.valueOf(localKindName)
    }
    val onSelectKind: (RideKindFilter) -> Unit = { chosen ->
        if (onKindFilterChange != null) onKindFilterChange(chosen) else localKindName = chosen.name
    }
    val sections = remember(rides, query, today, language, kind) {
        splitHistory(rides, query, today, ::localOfEpochMs, language, kind)
    }
    val planningSpeed = remember(insights.profile, rides) {
        planningSpeedKmh(SessionIntensity.GRUNDLAGE, insights.profile, rides)
    }

    LazyColumn(
        modifier = modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        header()

        if (hasIndoor) {
            item(key = "art-filter") {
                RideKindFilterRow(selected = kind, onSelect = onSelectKind)
            }
        }

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
                    title = stringResource(R.string.rides_list_empty_title),
                    onRecord = onRecord,
                    onImportFile = onImportFile,
                    onImportArchive = onImportArchive,
                )
            }

            sections.noMatch -> item(key = "keine-treffer") {
                NoMatchText(stringResource(R.string.rides_list_no_match, query.trim()))
            }

            else -> {
                if (sections.planned.isNotEmpty()) {
                    item(key = "sec-geplant") { SectionEyebrow(stringResource(R.string.rides_list_planned_eyebrow)) }
                    // Eigener Schluesselraum: Die IDs sind zwar eindeutig, aber
                    // ein Praefix haelt die Zeilen beider Abschnitte auch dann
                    // auseinander, wenn eine Planung einmal zur Fahrt wuerde.
                    itemsIndexed(sections.planned, key = { _, ride -> "geplant-${ride.id}" }) { index, ride ->
                        PlannedRow(
                            ride = ride,
                            planningSpeedKmh = planningSpeed,
                            loadRide = appViewModel::loadRide,
                            first = index == 0,
                            last = index == sections.planned.lastIndex,
                            selected = showDetailSelection && ride.id == selectedRideId,
                            onClick = { onOpenDetail(ride.id) },
                        )
                    }
                }
                when (sections.ridden) {
                    RiddenPart.NOCH_KEINE_FAHRT -> item(key = "noch-keine-fahrt") {
                        // Nur Planungen: Der gefahrene Teil sagt ehrlich, dass
                        // es noch keine Fahrt gibt, statt leer zu bleiben.
                        RidesEmptyState(
                            title = stringResource(R.string.rides_list_nothing_ridden_title),
                            onRecord = onRecord,
                            onImportFile = onImportFile,
                            onImportArchive = onImportArchive,
                            modifier = Modifier.padding(top = SectionEyebrowTop),
                            recordAsSecondary = recordAsSecondary,
                        )
                    }

                    RiddenPart.KEIN_TREFFER -> item(key = "keine-fahrt-treffer") {
                        NoMatchText(
                            if (query.isBlank()) {
                                stringResource(R.string.idee03_filter_empty)
                            } else {
                                stringResource(R.string.rides_list_no_ridden_match, query.trim())
                            },
                        )
                    }

                    RiddenPart.LISTE -> sections.months.forEach { group ->
                        item(key = "monat-${group.month}") { SectionEyebrow(group.label) }
                        itemsIndexed(group.rides, key = { _, ride -> ride.id }) { index, ride ->
                            RideRow(
                                ride = ride,
                                load = insights.rideLoads[ride.id],
                                loadRide = appViewModel::loadRide,
                                first = index == 0,
                                last = index == group.rides.lastIndex,
                                selected = showDetailSelection && ride.id == selectedRideId,
                                onClick = { onOpenDetail(ride.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Die Wahl „Alle · Draussen · Indoor" ueber der Verlaufsliste (siehe [RideKindFilter]). */
@Composable
private fun RideKindFilterRow(selected: RideKindFilter, onSelect: (RideKindFilter) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val atlas = LocalAtlasColors.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CardPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in RideKindFilter.entries) {
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.surfaceContainerHighest,
                    labelColor = colors.onSurface,
                    selectedContainerColor = colors.primaryContainer,
                    selectedLabelColor = colors.onPrimaryContainer,
                    selectedLeadingIconColor = colors.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = atlas.cardBorder,
                    selectedBorderColor = colors.primary,
                ),
                label = {
                    Text(
                        stringResource(
                            when (option) {
                                RideKindFilter.ALL -> R.string.idee03_filter_all
                                RideKindFilter.OUTDOOR -> R.string.idee03_filter_outdoor
                                RideKindFilter.INDOOR -> R.string.idee03_filter_indoor
                            },
                        ),
                    )
                },
            )
        }
    }
}

/** Abschnittsueberschrift („Geplant", „September") — dieselbe Einrueckung wie der Text in der Karte darunter. */
@Composable
private fun SectionEyebrow(text: String) {
    Eyebrow(
        text = text,
        modifier = Modifier.padding(start = CardPadding, top = SectionEyebrowTop, bottom = 8.dp),
    )
}

/** Gedaempfter Satz, wenn die Suche nichts trifft. */
@Composable
private fun NoMatchText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = CardPadding, vertical = CardGap),
    )
}

/** Hoehe der Ladeanzeige, solange die Liste noch geladen wird. */
private val LoadingRowHeight = 96.dp

/** Luft ueber einer Abschnittsueberschrift — trennt zwei Gruppen-Karten. */
private val SectionEyebrowTop = 16.dp

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
 * Eine Zeile der Verlaufsliste (Atlas-Stil): Mini-Karte, Name, ruhige
 * Datumszeile, darunter Laenge, Hoehenmeter und Dauer in Tabellenziffern,
 * rechts optional die Marken (Indoor / Haerte).
 */
@Composable
private fun RideRow(
    ride: RideSummary,
    load: de.trailscape.core.RideLoad?,
    loadRide: suspend (String) -> Ride?,
    first: Boolean,
    last: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val created = localOfEpochMs(ride.createdAt)
    val effort = rideEffort(load, ride.stats)
    HistoryRow(
        ride = ride,
        dateLine = rideRowDateText(created, language),
        distanceText = rideRowDistanceText(ride.stats.distanceKm, language).asString(),
        ascentText = rideRowAscentText(ride.stats.ascentM).asString(),
        durationText = rideRowDurationText(ride.stats.durationS, estimated = false).asString(),
        loadRide = loadRide,
        first = first,
        last = last,
        selected = selected,
        onClick = onClick,
        trailing = if (ride.indoor || effort != null) {
            {
                // Indoor-Fahrt (`docs/indoor.md` 10.2): Strecke und Kennzahlen
                // wie jede Tour, dazu eine Marke, damit die virtuelle Strecke
                // nicht fuer eine Ausfahrt gehalten wird.
                if (ride.indoor) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TagPill(text = stringResource(R.string.indoor_app_tourlist_pill))
                        effort?.let { EffortPill(it) }
                    }
                } else {
                    effort?.let { EffortPill(it) }
                }
            }
        } else {
            null
        },
    )
}

/**
 * Eine Zeile im Abschnitt „Geplant": dieselbe Atlas-Form wie [RideRow], mit
 * Erstelldatum, Laenge, Hoehenmetern, geschaetzter Dauer (`~3:14 h`) und
 * rechts der Pille „Geplant".
 */
@Composable
private fun PlannedRow(
    ride: RideSummary,
    planningSpeedKmh: Double,
    loadRide: suspend (String) -> Ride?,
    first: Boolean,
    last: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val created = localOfEpochMs(ride.createdAt)
    val estimated = ride.stats.durationS == null
    val durationS = ride.stats.durationS ?: estimatedPlannedDurationS(ride.stats.distanceKm, planningSpeedKmh)
    HistoryRow(
        ride = ride,
        dateLine = plannedRowDateText(created, language).asString(),
        distanceText = rideRowDistanceText(ride.stats.distanceKm, language, integerFrom10Km = true).asString(),
        ascentText = rideRowAscentText(ride.stats.ascentM).asString(),
        durationText = rideRowDurationText(durationS, estimated = estimated).asString(),
        loadRide = loadRide,
        first = first,
        last = last,
        selected = selected,
        onClick = onClick,
        trailing = { TagPill(text = stringResource(R.string.rides_list_planned_pill)) },
    )
}

/**
 * Die gemeinsame Atlas-Zeile von [RideRow] und [PlannedRow]: Mini-Karte,
 * Titel + Pille(n), Datumszeile und umbruchfaehige Kennzahlenreihe (Distanz,
 * Hoehenmeter, Dauer) in Tabellenziffern.
 */
@Composable
private fun HistoryRow(
    ride: RideSummary,
    dateLine: String,
    distanceText: String,
    ascentText: String,
    durationText: String,
    loadRide: suspend (String) -> Ride?,
    first: Boolean,
    last: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val atlas = LocalAtlasColors.current
    val fontScale = LocalDensity.current.fontScale
    val containerColor = if (selected) {
        colors.primaryContainer.copy(alpha = 0.45f)
    } else {
        colors.surfaceContainerHighest
    }
    val borderColor = if (selected) colors.primary else atlas.cardBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(groupShape(first, last))
            .background(containerColor)
            .groupBorder(
                first = first,
                last = last,
                fullOutline = selected,
                color = borderColor,
            )
            .clickable(onClick = onClick),
    ) {
        if (!first && !selected) {
            HorizontalDivider(
                color = atlas.cardBorder,
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
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val stackPillsBelow = trailing != null && fontScale > 1.15f && maxWidth < 300.dp
                    if (stackPillsBelow) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = ride.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                trailing()
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = ride.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            trailing?.invoke()
                        }
                    }
                }
                Text(
                    text = dateLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = colors.onSurface,
                    )
                    Text(
                        text = ascentText,
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.labelLarge.tabular(),
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Zeichnet die feine 1-dp-Atlas-Kante einer Gruppenzeile ohne doppelt
 * gestrichene Innenlinien (bei ausgewaehlter Zeile im Master-Detail den vollen
 * Rahmen in Akzentfarbe).
 */
private fun Modifier.groupBorder(
    first: Boolean,
    last: Boolean,
    fullOutline: Boolean,
    color: Color,
): Modifier = drawWithContent {
    drawContent()
    val strokePx = 1.dp.toPx()
    val half = strokePx / 2f
    val w = size.width
    val h = size.height
    val radius = 20.dp.toPx().coerceAtMost(minOf(w, h) / 2f)
    val style = Stroke(width = strokePx)

    when {
        (first && last) || fullOutline -> {
            val topR = if (first) radius else 0f
            val bottomR = if (last) radius else 0f
            if (topR > 0f && bottomR > 0f && topR == bottomR) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(half, half),
                    size = Size(w - strokePx, h - strokePx),
                    cornerRadius = CornerRadius((radius - half).coerceAtLeast(0f)),
                    style = style,
                )
            } else {
                val path = Path().apply {
                    moveTo(half, if (first) radius else half)
                    if (first) {
                        arcTo(
                            Rect(half, half, 2 * radius - half, 2 * radius - half),
                            startAngleDegrees = 180f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false,
                        )
                        lineTo(w - radius, half)
                        arcTo(
                            Rect(w - 2 * radius + half, half, w - half, 2 * radius - half),
                            startAngleDegrees = 270f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false,
                        )
                    } else {
                        lineTo(half, half)
                        lineTo(w - half, half)
                    }
                    if (last) {
                        lineTo(w - half, h - radius)
                        arcTo(
                            Rect(w - 2 * radius + half, h - 2 * radius + half, w - half, h - half),
                            startAngleDegrees = 0f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false,
                        )
                        lineTo(radius, h - half)
                        arcTo(
                            Rect(half, h - 2 * radius + half, 2 * radius - half, h - half),
                            startAngleDegrees = 90f,
                            sweepAngleDegrees = 90f,
                            forceMoveTo = false,
                        )
                    } else {
                        lineTo(w - half, h - half)
                        lineTo(half, h - half)
                    }
                    close()
                }
                drawPath(path = path, color = color, style = style)
            }
        }

        first -> {
            val path = Path().apply {
                moveTo(half, h)
                lineTo(half, radius)
                arcTo(
                    Rect(half, half, 2 * radius - half, 2 * radius - half),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false,
                )
                lineTo(w - radius, half)
                arcTo(
                    Rect(w - 2 * radius + half, half, w - half, 2 * radius - half),
                    startAngleDegrees = 270f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false,
                )
                lineTo(w - half, h)
            }
            drawPath(path = path, color = color, style = style)
        }

        last -> {
            val path = Path().apply {
                moveTo(half, 0f)
                lineTo(half, h - radius)
                arcTo(
                    Rect(half, h - 2 * radius + half, 2 * radius - half, h - half),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = -90f,
                    forceMoveTo = false,
                )
                lineTo(w - radius, h - half)
                arcTo(
                    Rect(w - 2 * radius + half, h - 2 * radius + half, w - half, h - half),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = -90f,
                    forceMoveTo = false,
                )
                lineTo(w - half, 0f)
            }
            drawPath(path = path, color = color, style = style)
        }

        else -> {
            drawLine(color = color, start = Offset(half, 0f), end = Offset(half, h), strokeWidth = strokePx)
            drawLine(color = color, start = Offset(w - half, 0f), end = Offset(w - half, h), strokeWidth = strokePx)
        }
    }
}

/** Senkrechter Innenabstand einer [HistoryRow] — kompakter als eine volle [CardPadding]. */
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
    val label = stringResource(effort.labelRes)
    val description = stringResource(R.string.rides_list_effort_cd, label)
    TagPill(
        text = label,
        containerColor = container,
        contentColor = content,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/**
 * Das Import-Menue hinter „+" (und hinter „Touren importieren" im
 * Leerzustand): Dateien (Mehrfachauswahl) oder Archiv. Ein `DropdownMenu`, das der Aufrufer
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
            text = { Text(stringResource(R.string.rides_import_menu_files_action)) },
            leadingIcon = { Icon(Icons.Filled.Route, contentDescription = null) },
            onClick = {
                onDismiss()
                onImportFile()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.rides_import_menu_archive_action)) },
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
    title: String,
    onRecord: () -> Unit,
    onImportFile: () -> Unit,
    onImportArchive: () -> Unit,
    modifier: Modifier = Modifier,
    recordAsSecondary: Boolean = false,
) {
    var importMenuOpen by remember { mutableStateOf(false) }
    AtlasCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.rides_list_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (recordAsSecondary) {
                    TrailscapeSecondaryButton(onClick = onRecord) {
                        Text(stringResource(R.string.rides_list_empty_record_action))
                    }
                } else {
                    TrailscapePrimaryButton(onClick = onRecord) {
                        Text(stringResource(R.string.rides_list_empty_record_action))
                    }
                }
                Box {
                    TextButton(onClick = { importMenuOpen = true }) {
                        Text(stringResource(R.string.rides_list_empty_import_action))
                    }
                    ImportMenu(
                        expanded = importMenuOpen,
                        onDismiss = { importMenuOpen = false },
                        onImportFile = onImportFile,
                        onImportArchive = onImportArchive,
                    )
                }
            }
        }
    }
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
 * ## Teilen: Bild, GPX, Strava
 * „Teilen" oeffnet [RideShareDialog]: das Tour-Bild als Story oder Quadrat
 * ([shareRideImage]), die Spur als GPX ([shareRideGpx]) und darunter die
 * Strava-Zeile ([StravaShareRow]). Die Trainingslast
 * fuer das Bild kommt aus [AppViewModel.insights] — nur gelesen, keine neue
 * Rechnung.
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
    inline: Boolean = false,
) {

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
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    // Die Trainingslast kann auf dem Tour-Bild stehen (ohne Puls als vierte Zahl).
    val insights by appViewModel.insights.collectAsStateWithLifecycle()

    if (!inline) {
        LaunchedEffect(appViewModel) {
            appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
        }
        // Wird eine per Teilen importierte Tour direkt hier geoeffnet, gehoert
        // die Ergebnismeldung hierher (siehe RidesScreen).
        FileImportNoticeEffect(appViewModel, snackbarHostState)
    }

    LaunchedEffect(summary) {
        if (summary == null) onBack()
    }

    BackHandler(enabled = !inline, onBack = onBack)

    val loaded = ride ?: return
    val load = insights.rideLoads[loaded.id]?.takeIf { it.available }?.load

    RideDetailScreen(
        ride = loaded,
        appViewModel = appViewModel,
        snackbarHostState = if (inline) null else snackbarHostState,
        onBack = if (inline) null else onBack,
        inline = inline,
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
        onShare = { shareOpen = true },
        onDelete = { onDelete(loaded.id) },
        // „Indoor fahren" (Blatt „Nochmal fahren", ui/rides/RideAgain.kt): wie „nochmal fahren"
        // schliesst das Detail, dann oeffnet der Indoor-Ablauf mit dieser Strecke.
        onRideIndoor = {
            onBack()
            appViewModel.requestIndoorRide(loaded.id)
        },
        onPreview3d = {
            onBack()
            appViewModel.requestIndoorPreview(loaded.id)
        },
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

    if (shareOpen) {
        RideShareDialog(
            ride = loaded,
            load = load,
            appViewModel = appViewModel,
            onDismiss = { shareOpen = false },
            // „Herausforderung teilen" einer teilbaren Indoor-Tour (docs/ux/umbau.md).
            extraRows = { ChallengeShareRow(loaded) },
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
 * [message] und [actionLabel] reicht der Aufrufer schon uebersetzt herein
 * (`rides_delete_undo_*`) — diese Funktion laeuft ausserhalb der Komposition.
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
    message: String,
    actionLabel: String,
): Job {
    undoJob?.cancel()
    appViewModel.deleteRideWithUndo(rideId)
    return scope.launch {
        val result = withTimeoutOrNull(UNDO_DELETE_GRACE_MS) {
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
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
        title = { Text(stringResource(R.string.rides_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.rides_rename_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            ) { Text(stringResource(R.string.common_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
    )
}
