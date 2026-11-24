package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.indoor.IndoorDayPlan
import de.trailscape.app.indoor.IndoorDayRouteFinder
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.indoor.DayClimbs
import de.trailscape.core.indoor.DayRideEnd
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DayRouteMatch
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.DayTerrain
import de.trailscape.core.indoor.sessionDurationMinutes
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Zustaende
// ---------------------------------------------------------------------------

/**
 * Ein Vorschlag fuer die Einheit des Tages (docs/indoor/app.md, „Einheit des Tages").
 *
 * @property key Schluessel der Streckenwahl ([IndoorRouteOption.key]) oder
 *   [IndoorDayRouteFinder.PLAN_KEY].
 * @property rideKm Strecke, die waehrend der Einheit gefahren wird (Nachrechnung);
 *   bei [DayRouteAvailability.PLAN] die Zieldistanz der neuen Runde.
 * @property startKm Start nicht am Anfang der Strecke; `null` = vorn.
 * @property laps angefangene Runden (> 1 nur auf einer Runde).
 * @property endsEarlyMin die Strecke ohne Rundschluss endet so viele Minuten vor der Einheit.
 * @property downloadBytes Paketgroesse einer Katalogstrecke ([DayRouteAvailability.DOWNLOAD]).
 * @property buildMinutes geschaetzte Bauzeit der Welt ([DayRouteAvailability.BUILD], PLAN).
 * @property estimated das Profil ist aus Laenge und Hoehenmetern geschaetzt.
 */
data class IndoorDaySuggestionUi(
    val key: String,
    val name: String,
    val availability: DayRouteAvailability,
    val rideKm: Double,
    val startKm: Double? = null,
    val laps: Int = 1,
    val climbs: DayClimbs? = null,
    val endsEarlyMin: Int? = null,
    val downloadBytes: Long? = null,
    val buildMinutes: Int? = null,
    val estimated: Boolean = false,
)

/** Die Karte „Einheit des Tages". */
sealed interface IndoorDayUi {
    /** Die passende Strecke wird gesucht. */
    data object Loading : IndoorDayUi

    /** Ruhetag nach Tagesform; [plannedTitle] = was im Plan gestanden haette. */
    data class Rest(val plannedTitle: String?) : IndoorDayUi

    /** Heute ist das Zielevent. */
    data object Event : IndoorDayUi

    /**
     * Eine Einheit mit Vorschlaegen.
     *
     * @property minutes Dauer der Einheit; [plannedMinutes] die des Plans, wenn
     *   die Tagesform gekuerzt hat (sonst `null`).
     * @property suggestions beste zuerst; leer = keine Strecke gefunden.
     * @property index der gerade gezeigte Vorschlag („Andere Strecke" blaettert).
     */
    data class Offer(
        val kind: DaySessionKind,
        val title: String,
        val minutes: Int,
        val plannedMinutes: Int?,
        val terrain: DayTerrain,
        val suggestions: List<IndoorDaySuggestionUi>,
        val index: Int = 0,
    ) : IndoorDayUi {
        val current: IndoorDaySuggestionUi? get() = suggestions.getOrNull(index)

        /** Der naechste Vorschlag, nach dem letzten wieder der erste. */
        fun next(): Offer = if (suggestions.isEmpty()) this else copy(index = (index + 1) % suggestions.size)

        /** Den Vorschlag mit [key] zeigen, falls es ihn gibt. */
        fun showing(key: String?): Offer = suggestions.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { copy(index = it) } ?: this
    }
}

/**
 * Was am Ende einer ERG-Fahrt angeboten wird ([DayRideEnd]).
 *
 * @property remainingMin bei [DayRideEnd.ROUTE_DONE_EARLY] die Restzeit der Einheit.
 */
data class IndoorDayEndUi(val kind: DayRideEnd, val remainingMin: Int = 0)

/** Hoechstens so viele Vorschlaege zum Durchblaettern. */
const val INDOOR_DAY_MAX_SUGGESTIONS: Int = 6

/**
 * Baut die Karte aus der Einheit [plan] und den bewerteten Strecken
 * [matches] (beste zuerst). Rein, damit Tests und Bilder sie ohne Geraet bauen.
 *
 * @param packageBytes Paketgroesse einer Katalogstrecke nach Kennung.
 */
fun indoorDayUiOf(
    plan: IndoorDayPlan,
    matches: List<DayRouteMatch>,
    packageBytes: (String) -> Long? = { null },
): IndoorDayUi? {
    val kind = plan.day.kind
    if (kind == DaySessionKind.EVENT) return IndoorDayUi.Event
    if (kind == DaySessionKind.REST) return IndoorDayUi.Rest(plan.title)
    val workout = plan.workout ?: return null
    val goal = plan.goal ?: return null
    val minutes = (workout.totalS + 30) / 60
    val plannedMinutes = plan.day.planned?.takeIf { kind == DaySessionKind.PLANNED_REDUCED }
        ?.let { sessionDurationMinutes(it) }?.takeIf { it != minutes }
    val suggestions = matches.take(INDOOR_DAY_MAX_SUGGESTIONS).map { m ->
        val c = m.candidate
        val sim = m.start?.sim
        val lengthKm = c.profile?.lengthM?.div(1000.0)
        val ends = sim?.routeEndsAtS?.let { ((goal.totalS - it) / 60.0).roundToInt().coerceAtLeast(1) }
        val buildKm = when (c.availability) {
            DayRouteAvailability.BUILD -> lengthKm
            DayRouteAvailability.PLAN -> plan.planTarget?.distanceKm
            else -> null
        }
        IndoorDaySuggestionUi(
            key = c.key,
            name = c.name,
            availability = c.availability,
            rideKm = sim?.distanceM?.div(1000.0) ?: plan.planTarget?.distanceKm ?: goal.expectedFlatM / 1000.0,
            startKm = m.start?.startM?.takeIf { it > 0 }?.div(1000.0),
            laps = sim?.laps ?: 1,
            climbs = m.climbs,
            endsEarlyMin = ends,
            downloadBytes = if (c.availability == DayRouteAvailability.DOWNLOAD) packageBytes(c.key.removePrefix("catalog:")) else null,
            buildMinutes = buildKm?.let { ((it * IndoorDayRouteFinder.BUILD_SECONDS_PER_KM) / 60.0).roundToInt().coerceAtLeast(1) },
            estimated = c.profile?.estimated == true,
        )
    }
    return IndoorDayUi.Offer(kind, plan.title ?: workout.title, minutes, plannedMinutes, goal.terrain, suggestions)
}

// ---------------------------------------------------------------------------
// Texte
// ---------------------------------------------------------------------------

/** Der Satz der Begruendung: „42 km · 3 Anstiege von 6–8 min für deine 3 × 8 min · Start bei km 12,4". */
@Composable
fun indoorDayReason(s: IndoorDaySuggestionUi, terrain: DayTerrain): String {
    val formats = LocalAppFormats.current
    val parts = mutableListOf<String>()
    parts += stringResource(R.string.indoor_app_day_reason_km, formats.km(s.rideKm))
    if (s.availability != DayRouteAvailability.PLAN) {
        val climbs = s.climbs
        parts += when (terrain) {
            DayTerrain.CLIMBS -> if (climbs != null && climbs.reps > 0) {
                val range = if (climbs.minMinutes == climbs.maxMinutes) {
                    stringResource(R.string.indoor_app_day_reason_minutes, climbs.minMinutes)
                } else {
                    stringResource(R.string.indoor_app_day_reason_minutes_range, climbs.minMinutes, climbs.maxMinutes)
                }
                pluralStringResource(
                    R.plurals.indoor_app_day_reason_climbs_count,
                    climbs.count,
                    climbs.count,
                    range,
                    climbs.reps,
                    climbs.workMinutes,
                )
            } else {
                stringResource(R.string.indoor_app_day_reason_no_climbs)
            }
            DayTerrain.ROLLING -> stringResource(R.string.indoor_app_day_reason_rolling)
            DayTerrain.FLAT -> if (climbs == null) {
                stringResource(R.string.indoor_app_day_reason_flat)
            } else {
                stringResource(R.string.indoor_app_day_reason_mostly_flat)
            }
        }
        if (s.laps > 1) parts += pluralStringResource(R.plurals.indoor_app_day_reason_laps_count, s.laps, s.laps)
        s.startKm?.let { parts += stringResource(R.string.indoor_app_day_reason_start, formats.km(it)) }
        s.endsEarlyMin?.let { parts += stringResource(R.string.indoor_app_day_reason_ends_early, it) }
        if (s.estimated) parts += stringResource(R.string.indoor_app_day_reason_estimated)
    } else {
        parts += stringResource(
            when (terrain) {
                DayTerrain.CLIMBS -> R.string.indoor_app_day_plan_climbs
                DayTerrain.ROLLING -> R.string.indoor_app_day_plan_rolling
                DayTerrain.FLAT -> R.string.indoor_app_day_plan_flat
            },
        )
    }
    return parts.joinToString(" · ")
}

/** Ob die Welt da ist, geladen oder gebaut werden muss — mit Groesse bzw. Dauer. */
@Composable
private fun availabilityLine(s: IndoorDaySuggestionUi, formats: AppFormats): Pair<ImageVector, String> = when (s.availability) {
    DayRouteAvailability.READY -> Icons.Filled.CheckCircle to stringResource(R.string.indoor_app_day_world_ready)
    DayRouteAvailability.DOWNLOAD -> Icons.Filled.CloudDownload to (
        s.downloadBytes?.let { stringResource(R.string.indoor_app_day_world_download, formats.indoorBytes(it)) }
            ?: stringResource(R.string.indoor_app_day_world_download_unknown)
        )
    DayRouteAvailability.BUILD -> Icons.Filled.Build to stringResource(R.string.indoor_app_day_world_build, s.buildMinutes ?: 1)
    DayRouteAvailability.PLAN -> Icons.Filled.Map to stringResource(R.string.indoor_app_day_world_plan, s.buildMinutes ?: 1)
}

@Composable
private fun rideActionLabel(s: IndoorDaySuggestionUi): String = stringResource(
    when (s.availability) {
        DayRouteAvailability.READY -> R.string.indoor_app_day_ride_action
        DayRouteAvailability.DOWNLOAD -> R.string.indoor_app_day_download_action
        DayRouteAvailability.BUILD -> R.string.indoor_app_day_build_action
        DayRouteAvailability.PLAN -> R.string.indoor_app_day_plan_action
    },
)

// ---------------------------------------------------------------------------
// Karte
// ---------------------------------------------------------------------------

/**
 * Die Karte „Einheit des Tages fahren" — auf „Heute" und oben im Schritt
 * Strecke. Zustandslos; „Andere Strecke" blaettert ueber [onNext] (der Halter
 * des Zustands rechnet [IndoorDayUi.Offer.next]).
 *
 * @param onRide fahren bzw. laden/bauen/planen fuer den gezeigten Vorschlag.
 * @param onOpenIndoor nur am Ruhetag: das Indoor-Training trotzdem oeffnen
 *   (freie Fahrt); `null` = kein Knopf.
 */
@Composable
fun IndoorDayCard(
    ui: IndoorDayUi,
    onRide: (IndoorDaySuggestionUi) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenIndoor: (() -> Unit)? = null,
) {
    val formats = LocalAppFormats.current
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.indoor_app_day_eyebrow),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when (ui) {
                IndoorDayUi.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    MutedText(stringResource(R.string.indoor_app_day_loading))
                }
                is IndoorDayUi.Rest -> {
                    Text(stringResource(R.string.indoor_app_day_rest_title), style = MaterialTheme.typography.titleMedium)
                    MutedText(
                        if (ui.plannedTitle != null) {
                            stringResource(R.string.indoor_app_day_rest_body_planned, ui.plannedTitle)
                        } else {
                            stringResource(R.string.indoor_app_day_rest_body)
                        },
                    )
                    onOpenIndoor?.let {
                        TextButton(onClick = it) { Text(stringResource(R.string.indoor_app_day_open_free_action)) }
                    }
                }
                IndoorDayUi.Event -> {
                    Text(stringResource(R.string.indoor_app_day_event_title), style = MaterialTheme.typography.titleMedium)
                    MutedText(stringResource(R.string.indoor_app_day_event_body))
                }
                is IndoorDayUi.Offer -> OfferBody(ui, formats, onRide, onNext)
            }
        }
    }
}

@Composable
private fun OfferBody(ui: IndoorDayUi.Offer, formats: AppFormats, onRide: (IndoorDaySuggestionUi) -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = ui.title, style = MaterialTheme.typography.titleMedium)
        MutedText(
            when {
                ui.kind == DaySessionKind.EASY -> stringResource(R.string.indoor_app_day_easy_meta, ui.minutes)
                ui.plannedMinutes != null -> stringResource(R.string.indoor_app_day_reduced_meta, ui.minutes, ui.plannedMinutes)
                else -> stringResource(R.string.indoor_app_day_meta, ui.minutes)
            },
        )
    }
    val s = ui.current
    if (s == null) {
        MutedText(stringResource(R.string.indoor_app_day_none_found))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = s.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
        Text(
            text = indoorDayReason(s, ui.terrain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val (icon, line) = availabilityLine(s, formats)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.width(6.dp))
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onRide(s) }) { Text(rideActionLabel(s)) }
        if (ui.suggestions.size > 1) {
            OutlinedButton(onClick = onNext) {
                Text(stringResource(R.string.indoor_app_day_next_action, ui.index + 1, ui.suggestions.size))
            }
        }
    }
}
