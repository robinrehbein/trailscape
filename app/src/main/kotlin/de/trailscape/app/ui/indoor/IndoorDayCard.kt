package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import de.trailscape.core.indoor.DAY_PLAN_SCORE
import de.trailscape.core.indoor.dayGeneratedProfile
import de.trailscape.core.indoor.generated.GeneratedProfile
import de.trailscape.core.indoor.sessionDurationMinutes
import de.trailscape.app.ui.components.PillSegments
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

/** Die beiden Wege der Karte. */
enum class IndoorDayPath {
    /** Eine echte Strecke (Vorschlaege zum Durchblaettern). */
    REAL,

    /** Eine erfundene Landschaft genau zur Einheit, auf dem Geraet gebaut. */
    GENERATED,
}

/**
 * Die generierte Welt zur Einheit (Weg [IndoorDayPath.GENERATED]).
 *
 * @property km Laenge = Strecke der Einheit bei Sollleistung (die Einheit endet am Ziel).
 * @property climbs Anstiege (eins je Intervall), 0 ohne Intervalle.
 * @property buildMinutes geschaetzte Bauzeit ohne Netz; [ready] = liegt schon gebaut vor.
 */
data class IndoorDayGeneratedUi(
    val km: Double,
    val climbs: Int,
    val reps: Int,
    val workMinutes: Int,
    val buildMinutes: Int,
    val ready: Boolean = false,
)

/** Schluessel, mit dem „Heute" den Weg „Generierte Welt" an den Ablauf weitergibt. */
const val INDOOR_DAY_GENERATED_KEY: String = "generated"

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
     * @property startable die Karte zeigt gerade etwas zum Losfahren (einen
     *   Vorschlag oder die generierte Welt) und traegt damit selbst einen Knopf.
     */
    data class Offer(
        val kind: DaySessionKind,
        val title: String,
        val minutes: Int,
        val plannedMinutes: Int?,
        val terrain: DayTerrain,
        val suggestions: List<IndoorDaySuggestionUi>,
        val index: Int = 0,
        val generated: IndoorDayGeneratedUi? = null,
        val path: IndoorDayPath = IndoorDayPath.REAL,
    ) : IndoorDayUi {
        val current: IndoorDaySuggestionUi? get() = suggestions.getOrNull(index)

        val startable: Boolean
            get() = if (path == IndoorDayPath.GENERATED && generated != null) true else current != null

        /** Der naechste Vorschlag, nach dem letzten wieder der erste. */
        fun next(): Offer = if (suggestions.isEmpty()) this else copy(index = (index + 1) % suggestions.size)

        /** Den Vorschlag mit [key] zeigen, falls es ihn gibt. */
        fun showing(key: String?): Offer {
            if (key == INDOOR_DAY_GENERATED_KEY && generated != null) return copy(path = IndoorDayPath.GENERATED)
            return suggestions.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { copy(index = it, path = IndoorDayPath.REAL) } ?: this
        }
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
    generatedReady: Boolean = false,
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
    val generated = runCatching {
        val profile = dayGeneratedProfile(goal)
        val km = profile.lengthM / 1000.0
        IndoorDayGeneratedUi(
            km = km,
            climbs = profile.legs.count { it.kind == GeneratedProfile.Kind.CLIMB },
            reps = goal.intervals.size,
            workMinutes = goal.intervals.maxOfOrNull { it.durationS }?.let { (it / 60.0).roundToInt() } ?: 0,
            buildMinutes = ((km * GENERATED_BUILD_SECONDS_PER_KM) / 60.0).roundToInt().coerceAtLeast(1),
            ready = generatedReady,
        )
    }.getOrNull()
    // Gibt es keine passende echte Strecke, ist die generierte Welt der Vorschlag.
    val realFits = matches.any {
        it.candidate.availability != DayRouteAvailability.PLAN &&
            it.rank >= DAY_PLAN_SCORE - DayRouteAvailability.PLAN.penalty
    }
    val path = if (generated != null && !realFits) IndoorDayPath.GENERATED else IndoorDayPath.REAL
    return IndoorDayUi.Offer(kind, plan.title ?: workout.title, minutes, plannedMinutes, goal.terrain, suggestions, generated = generated, path = path)
}

/**
 * Grobe Bauzeit einer generierten Welt in Sekunden je km — ohne Netz, also
 * etwa die Haelfte der Bauzeit einer echten Strecke
 * ([IndoorDayRouteFinder.BUILD_SECONDS_PER_KM]); auf dem Geraet zu pruefen.
 */
const val GENERATED_BUILD_SECONDS_PER_KM: Int = 10

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
 * @param primary der Knopf der Karte ist die Hauptaktion des Bildschirms
 *   (gefuellt). `false`, wenn daneben schon ein anderer Hauptknopf steht (die
 *   Startseite mit gewaehlter Strecke) — dann getoent, damit es nur einen gibt.
 */
@Composable
fun IndoorDayCard(
    ui: IndoorDayUi,
    onRide: (IndoorDaySuggestionUi) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenIndoor: (() -> Unit)? = null,
    onRideGenerated: () -> Unit = {},
    onPathChange: (IndoorDayPath) -> Unit = {},
    primary: Boolean = true,
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
                is IndoorDayUi.Offer -> OfferBody(ui, formats, onRide, onNext, onRideGenerated, onPathChange, primary)
            }
        }
    }
}

@Composable
private fun OfferBody(
    ui: IndoorDayUi.Offer,
    formats: AppFormats,
    onRide: (IndoorDaySuggestionUi) -> Unit,
    onNext: () -> Unit,
    onRideGenerated: () -> Unit,
    onPathChange: (IndoorDayPath) -> Unit,
    primary: Boolean,
) {
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
    val generated = ui.generated
    if (generated != null) {
        PillSegments(
            options = listOf(
                stringResource(R.string.indoor_app_day_path_real),
                stringResource(R.string.indoor_app_day_path_generated),
            ),
            selectedIndex = if (ui.path == IndoorDayPath.REAL) 0 else 1,
            onSelect = { onPathChange(if (it == 0) IndoorDayPath.REAL else IndoorDayPath.GENERATED) },
        )
        if (ui.path == IndoorDayPath.GENERATED) {
            GeneratedBody(generated, ui.terrain, formats, primary, onRideGenerated)
            return
        }
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
    // Untereinander, nie nebeneinander: Auf einem schmalen Geraet bliebe dem
    // zweiten Knopf sonst kaum Breite, und sein Text braeche Buchstabe fuer Buchstabe um.
    Column(modifier = Modifier.fillMaxWidth()) {
        DayMainButton(text = rideActionLabel(s), primary = primary, onClick = { onRide(s) })
        if (ui.suggestions.size > 1) {
            TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.indoor_app_day_next_action, ui.index + 1, ui.suggestions.size),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Der eine Knopf der Karte ueber die volle Breite; gefuellt nur als Hauptaktion des Bildschirms. */
@Composable
private fun DayMainButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Text(text, textAlign = TextAlign.Center) }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier) { Text(text, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun GeneratedBody(g: IndoorDayGeneratedUi, terrain: DayTerrain, formats: AppFormats, primary: Boolean, onRide: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = stringResource(R.string.indoor_app_day_generated_title), style = MaterialTheme.typography.titleSmall)
        val shape = when {
            g.climbs > 0 && g.reps > 0 -> pluralStringResource(R.plurals.indoor_app_day_generated_climbs_count, g.climbs, g.climbs, g.reps, g.workMinutes)
            terrain == DayTerrain.ROLLING -> stringResource(R.string.indoor_app_day_reason_rolling)
            else -> stringResource(R.string.indoor_app_day_reason_flat)
        }
        Text(
            text = listOf(
                stringResource(R.string.indoor_app_day_reason_km, formats.km(g.km)),
                shape,
                stringResource(R.string.indoor_app_day_generated_ends),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Icon(
                if (g.ready) Icons.Filled.CheckCircle else Icons.Filled.Build,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (g.ready) {
                    stringResource(R.string.indoor_app_day_world_ready)
                } else {
                    stringResource(R.string.indoor_app_day_generated_build, g.buildMinutes)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    DayMainButton(
        text = stringResource(if (g.ready) R.string.indoor_app_day_ride_action else R.string.indoor_app_day_build_action),
        primary = primary,
        onClick = onRide,
    )
}
