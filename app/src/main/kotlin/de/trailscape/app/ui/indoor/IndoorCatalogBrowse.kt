package de.trailscape.app.ui.indoor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.TrailscapeSecondaryButton
import de.trailscape.core.TrainingProfile
import de.trailscape.core.RideSummary
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.catalog.CatalogClimbClass
import de.trailscape.core.indoor.catalog.CatalogFacts
import de.trailscape.core.indoor.catalog.CatalogFilter
import de.trailscape.core.indoor.catalog.CatalogLengthClass
import de.trailscape.core.indoor.catalog.CatalogRideRecord
import de.trailscape.core.indoor.catalog.CatalogRider
import de.trailscape.core.indoor.catalog.CatalogRouteRef
import de.trailscape.core.indoor.catalog.CatalogSort
import de.trailscape.core.indoor.catalog.CatalogSurfaceGroup
import de.trailscape.core.indoor.catalog.CatalogTimeBasis
import de.trailscape.core.indoor.catalog.apply
import de.trailscape.core.indoor.catalog.catalogBestTimes
import de.trailscape.core.indoor.catalog.catalogGradeStats
import de.trailscape.core.indoor.catalog.estimateCatalogRideSeconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # Streckenkarten mit mehr Aussagekraft (Idee 10)
 *
 * Gemeinsame Teile der Liste „Bekannte Strecken": Fahrerangaben und eigene
 * Fahrten ([IndoorCatalogExtras]), die Zeilen mit Kennzahlen ([CatalogRow]),
 * die Auswahl ueber der Liste (Filter, Sortierung) und deren Merkung ueber
 * die Sitzung ([CatalogBrowseSession]).
 */

/**
 * Was die Karten ueber den Katalog hinaus brauchen.
 *
 * @property rider FTP, Gewicht und Rad fuer die Fahrzeitschaetzung.
 * @property rides gespeicherte Indoor-Fahrten (Name, Strecke, Zeit) fuer „Gefahren" und Bestzeit.
 */
data class IndoorCatalogExtras(
    val rider: CatalogRider = CatalogRider.NEUTRAL,
    val rides: List<CatalogRideRecord> = emptyList(),
)

/** Fahrerangaben aus dem Profil; ohne eingetragene FTP bleibt die Angabe ein Richtwert. */
fun catalogRiderOf(p: TrainingProfile): CatalogRider = CatalogRider(
    ftpW = p.eftpW,
    riderKg = p.weightKg,
    bikeKg = p.setupMassKg,
    cdaM2 = p.cda,
    profileCrr = p.crr,
    efficiency = p.driveEfficiency,
    basis = if (p.eftpOverrideW != null) CatalogTimeBasis.PROFILE else CatalogTimeBasis.NEUTRAL,
)

/** Die Indoor-Fahrten aus der Tourenliste in der Form, die die Zuordnung braucht. */
fun catalogRideRecordsOf(summaries: List<RideSummary>): List<CatalogRideRecord> = summaries
    .filter { it.indoor && !it.planned }
    .map { CatalogRideRecord(it.name, it.stats.distanceKm, it.stats.movingTimeS ?: it.stats.durationS) }

/**
 * Merkt Filter und Sortierung fuer die Dauer des App-Prozesses: Wer die
 * Startseite verlaesst und zurueckkommt, findet seine Auswahl wieder.
 * Bewusst nicht auf Platte (keine Einstellung, nur eine Sitzung).
 */
object CatalogBrowseSession {
    private val _filter = MutableStateFlow(CatalogFilter())
    val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

    fun set(f: CatalogFilter) {
        _filter.value = f
    }

    fun reset() {
        _filter.value = CatalogFilter()
    }
}

/**
 * Eine Karte mit allem, was Filter, Sortierung, Karte und Detail zeigen.
 *
 * @property estimateS geschaetzte Fahrzeit in Sekunden (`null` = nicht berechenbar).
 * @property gradeAvgPct mittlere Steigung der Anstiege in Prozent.
 * @property gradeMaxPct steilster 200-m-Abschnitt, `null` ohne Profil.
 * @property bestS eigene Bestzeit in Sekunden, `null` = noch nicht gefahren.
 */
data class CatalogRow(
    val item: IndoorCatalogItemUi,
    val estimateS: Int?,
    val gradeAvgPct: Double,
    val gradeMaxPct: Double?,
    val bestS: Int?,
    val lengthM: Double,
    val timeBasis: CatalogTimeBasis,
) {
    val ridden: Boolean get() = bestS != null
}

/** Berechnet die Zeilen einmal je Aenderung der Liste. */
fun catalogRows(items: List<IndoorCatalogItemUi>, extras: IndoorCatalogExtras): List<CatalogRow> {
    val best = catalogBestTimes(
        items.map { CatalogRouteRef(it.id, setOf(it.name.de, it.name.en).filter { n -> n.isNotBlank() }.toSet(), it.distanceKm * 1000.0) },
        extras.rides,
    )
    return items.map { item ->
        val lengthM = item.profile?.lengthM?.takeIf { it > 0 } ?: (item.distanceKm * 1000.0)
        val surfaces = item.surfaces.toMap()
        val (avg, max) = catalogGradeStats(item.profile, lengthM, item.ascentM)
        CatalogRow(
            item = item,
            estimateS = estimateCatalogRideSeconds(item.distanceKm * 1000.0, item.ascentM, item.profile, surfaces, extras.rider),
            gradeAvgPct = avg,
            gradeMaxPct = max,
            bestS = best[item.id],
            lengthM = item.distanceKm * 1000.0,
            timeBasis = extras.rider.basis,
        )
    }
}

fun CatalogRow.facts(name: String): CatalogFacts = CatalogFacts(
    name = name,
    lengthM = lengthM,
    ascentM = item.ascentM,
    surfaces = item.surfaces.toMap(),
    loaded = item.ready,
    ridden = ridden,
    estimatedSeconds = estimateS,
)

/** „1 h 12 min", unter einer Stunde „42 min"; auf volle Minuten gerundet. */
@Composable
internal fun catalogDuration(seconds: Int): String {
    val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
    return if (minutes >= 60) {
        stringResource(R.string.indoor_i10_time_h_min, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.indoor_i10_time_min, minutes)
    }
}

/** „Ø 8,1 % · max. 12,4 %" bzw. nur der Durchschnitt ohne Profil. */
@Composable
internal fun catalogGradeText(row: CatalogRow, formats: AppFormats): String {
    val avg = formats.decimal(row.gradeAvgPct, 1)
    val max = row.gradeMaxPct
    return if (max != null) {
        stringResource(R.string.indoor_i10_grade_both, avg, formats.decimal(max, 1))
    } else {
        stringResource(R.string.indoor_i10_grade_avg, avg)
    }
}

// ---------------------------------------------------------------------------
// Belagsleiste
// ---------------------------------------------------------------------------

/** Farbe der Belagsgruppe in der Leiste; zusaetzlich steht der Belag immer als Text darunter. */
@Composable
internal fun surfaceColor(kind: SurfaceKind): Color = when (CatalogSurfaceGroup.of(kind)) {
    CatalogSurfaceGroup.ASPHALT -> MaterialTheme.colorScheme.outline
    CatalogSurfaceGroup.COBBLES -> MaterialTheme.colorScheme.tertiary
    CatalogSurfaceGroup.GRAVEL -> MaterialTheme.colorScheme.primary
    null -> MaterialTheme.colorScheme.outlineVariant
}

/**
 * Schmale Leiste mit den Anteilen der Belaege. Fuer den Vorleser eine
 * Beschreibung („Belag: Asphalt 59 %, Pflaster 29 %"); die Leiste selbst ist
 * nur Zierde, die Zahlen stehen als Text in der Karte.
 */
@Composable
internal fun SurfaceBar(surfaces: List<Pair<SurfaceKind, Double>>, description: String, modifier: Modifier = Modifier, height: Int = 8) {
    val parts = surfaces.filter { it.second > 0.0 }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = description },
    ) {
        if (parts.isEmpty()) return@Row
        parts.forEach { (kind, share) ->
            Box(Modifier.weight(share.toFloat().coerceAtLeast(0.01f)).height(height.dp).background(surfaceColor(kind)))
        }
    }
}

// ---------------------------------------------------------------------------
// Filter und Sortierung
// ---------------------------------------------------------------------------

/**
 * Filter und Sortierung ueber der Liste: eine Zeile „Filter und Sortierung
 * · 2 aktiv · 7 von 12 Strecken", die sich aufklappt. Jede Auswahl ist ein
 * Chip mit Zustandsansage fuer den Vorleser.
 */
@Composable
internal fun CatalogFilterBar(
    filter: CatalogFilter,
    shown: Int,
    total: Int,
    onChange: (CatalogFilter) -> Unit,
    initiallyOpen: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val toggleCd = stringResource(if (open) R.string.indoor_i10_filter_close else R.string.indoor_i10_filter_open)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { open = !open }
                    .semantics { contentDescription = toggleCd }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.indoor_i10_filter_title), style = MaterialTheme.typography.titleSmall)
                    val active = if (filter.isActive) stringResource(R.string.indoor_i10_filter_active, filter.activeCount) + " · " else ""
                    Text(
                        text = active + pluralStringResource(R.plurals.indoor_i10_count, total, shown, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                FilterGroup(R.string.indoor_i10_group_length) {
                    CatalogLengthClass.entries.forEach { c ->
                        Choice(lengthLabel(c), c in filter.lengths) {
                            onChange(filter.copy(lengths = filter.lengths.toggle(c)))
                        }
                    }
                }
                FilterGroup(R.string.indoor_i10_group_climb) {
                    CatalogClimbClass.entries.forEach { c ->
                        Choice(climbLabel(c), c in filter.climbs) {
                            onChange(filter.copy(climbs = filter.climbs.toggle(c)))
                        }
                    }
                }
                FilterGroup(R.string.indoor_i10_group_surface) {
                    CatalogSurfaceGroup.entries.forEach { c ->
                        Choice(surfaceGroupLabel(c), c in filter.surfaces) {
                            onChange(filter.copy(surfaces = filter.surfaces.toggle(c)))
                        }
                    }
                }
                FilterGroup(R.string.indoor_i10_group_state) {
                    Choice(stringResource(R.string.indoor_i10_state_loaded), filter.onlyLoaded) {
                        onChange(filter.copy(onlyLoaded = !filter.onlyLoaded))
                    }
                    Choice(stringResource(R.string.indoor_i10_state_not_ridden), filter.onlyNotRidden) {
                        onChange(filter.copy(onlyNotRidden = !filter.onlyNotRidden))
                    }
                }
                FilterGroup(R.string.indoor_i10_group_sort) {
                    CatalogSort.entries.forEach { s ->
                        Choice(sortLabel(s), filter.sort == s) { onChange(filter.copy(sort = s)) }
                    }
                }
                if (filter.isActive) {
                    TrailscapeSecondaryButton(onClick = { onChange(filter.cleared()) }) {
                        Text(stringResource(R.string.indoor_i10_reset))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterGroup(title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (selected) R.string.indoor_i10_selected else R.string.indoor_i10_not_selected)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.semantics { stateDescription = state; role = Role.Checkbox },
    )
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

@Composable
private fun lengthLabel(c: CatalogLengthClass): String = stringResource(
    when (c) {
        CatalogLengthClass.SHORT -> R.string.indoor_i10_length_short
        CatalogLengthClass.MEDIUM -> R.string.indoor_i10_length_medium
        CatalogLengthClass.LONG -> R.string.indoor_i10_length_long
    },
)

@Composable
private fun climbLabel(c: CatalogClimbClass): String = stringResource(
    when (c) {
        CatalogClimbClass.FLAT -> R.string.indoor_i10_climb_flat
        CatalogClimbClass.ROLLING -> R.string.indoor_i10_climb_rolling
        CatalogClimbClass.HILLY -> R.string.indoor_i10_climb_hilly
    },
)

@Composable
private fun surfaceGroupLabel(c: CatalogSurfaceGroup): String = stringResource(
    when (c) {
        CatalogSurfaceGroup.ASPHALT -> R.string.indoor_i10_surface_asphalt
        CatalogSurfaceGroup.COBBLES -> R.string.indoor_i10_surface_cobbles
        CatalogSurfaceGroup.GRAVEL -> R.string.indoor_i10_surface_gravel
    },
)

@Composable
private fun sortLabel(s: CatalogSort): String = stringResource(
    when (s) {
        CatalogSort.NAME -> R.string.indoor_i10_sort_name
        CatalogSort.LENGTH -> R.string.indoor_i10_sort_length
        CatalogSort.ASCENT -> R.string.indoor_i10_sort_ascent
        CatalogSort.TIME -> R.string.indoor_i10_sort_time
    },
)

/** Kein Treffer: ein klarer Satz und der Weg zurueck. */
@Composable
internal fun CatalogNoMatch(onReset: () -> Unit) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.indoor_i10_empty_title), style = MaterialTheme.typography.titleMedium)
            MutedText(stringResource(R.string.indoor_i10_empty_body))
            TrailscapeSecondaryButton(onClick = onReset) { Text(stringResource(R.string.indoor_i10_reset)) }
        }
    }
}

/** Filter, Sortierung und deren Merkung ueber die Sitzung auf die Zeilen anwenden. */
internal fun List<CatalogRow>.filteredBy(filter: CatalogFilter, nameOf: (CatalogRow) -> String): List<CatalogRow> =
    filter.apply(this) { it.facts(nameOf(it)) }
