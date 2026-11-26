package de.trailscape.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.RouteCard
import de.trailscape.app.ui.components.TrailscapeSecondaryButton
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.wear.WearGlanceSender
import de.trailscape.core.CachedForecast
import de.trailscape.core.DayForecast
import de.trailscape.core.PlaceChoice
import de.trailscape.core.PlaceReason
import de.trailscape.core.PlanSessionProgress
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.RideSummary
import de.trailscape.core.RidePlace
import de.trailscape.core.RouteTarget
import de.trailscape.core.TodayPlace
import de.trailscape.core.TrackPoint
import de.trailscape.core.WEEK_PLAN_FORECAST_CACHE_KEY
import de.trailscape.core.applyPlaceChoice
import de.trailscape.core.coarsePlacePoint
import de.trailscape.core.flipped
import de.trailscape.core.heuteVorschlagVon
import de.trailscape.core.indoor.daySessionFor
import de.trailscape.core.outdoorRides
import de.trailscape.core.parseDailyForecast
import de.trailscape.core.readPlaceChoice
import de.trailscape.core.readWeekPlanWeatherEnabled
import de.trailscape.core.suggestTodayPlace
import de.trailscape.core.todaySessionProgress
import de.trailscape.core.writePlaceChoice
import de.trailscape.core.writeShownHeute
import de.trailscape.app.ui.TodayDecision
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # „Wo fährst du heute?" — draussen oder drinnen zur Einheit des Tages
 *
 * Die Karte zwischen Tagesempfehlung und Indoor-Karte auf „Heute". Sie zeigt
 * den Vorschlag aus `core/…/TodayPlace.kt` ([suggestTodayPlace]) samt
 * hoechstens zwei Gruenden und bringt die Strecke mit: draussen die Runde der
 * Tagesentscheidung (Knopf „Runde planen"), drinnen den Verweis auf die
 * Indoor-Karte direkt darunter, die die passende Strecke zur Einheit bereits
 * ausgesucht hat. Ein Tipp dreht den Vorschlag um; die Wahl gilt bis
 * Mitternacht ([PlaceChoice]).
 *
 * Ist die heutige Planeinheit schon gefahren (draussen, drinnen oder beides,
 * [todaySessionProgress]), steht hier „Einheit erledigt" statt des Vorschlags.
 *
 * ## Daten
 *  * Ort: Start der juengsten Tour draussen, gerundet ([coarsePlacePoint]) —
 *    lokal, ohne Standortberechtigung.
 *  * Wetter: nur die schon zwischengespeicherte Tagesvorhersage, und nur bei
 *    eingeschaltetem Schalter „Wetter für den Wochenplan". Diese Karte fragt
 *    selbst nichts im Netz ab.
 *  * Uhr: Der gezeigte Vorschlag wird gemerkt ([writeShownHeute]); bei einer
 *    Aenderung stoesst die Karte das Senden des Wochenstands an
 *    ([WearGlanceSender]), die Uhr zeigt dann dieselbe Zeile.
 */
sealed interface TodayPlaceUi {
    /** Der Vorschlag (ggf. vom Nutzer umgedreht) und jetzt — fuer die Saetze mit Uhrzeit. */
    data class Suggest(val place: TodayPlace, val now: ZonedDateTime) : TodayPlaceUi

    /** Die heutige Planeinheit ist erledigt. */
    data class Done(val progress: PlanSessionProgress) : TodayPlaceUi
}

/**
 * Rechnet den Zustand der Karte und zeigt sie; ohne Vorschlag (Ruhetag,
 * Zieltag, kein Angebot) zeigt sie nichts.
 */
@Composable
fun TodayPlaceEntry(
    decision: TodayDecision,
    rides: List<RideSummary>,
    rideLoads: Map<String, Double>,
    now: ZonedDateTime,
    onPlanRoute: (RouteTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val date = now.toLocalDate()
    var choice by remember { mutableStateOf(runCatching { readPlaceChoice(AppServices.keyValueStore) }.getOrNull()) }
    var location by remember { mutableStateOf<TrackPoint?>(null) }
    var forecast by remember { mutableStateOf<DayForecast?>(null) }
    val lastOutdoor = remember(rides) { outdoorRides(rides).maxByOrNull { it.createdAt } }
    LaunchedEffect(lastOutdoor?.id, date) {
        val (point, day) = withContext(Dispatchers.IO) {
            val start = lastOutdoor?.id?.let { id ->
                runCatching { AppServices.rideStorage.loadRide(id)?.points?.firstOrNull() }.getOrNull()
            }
            coarsePlacePoint(start) to cachedTodayForecast(date)
        }
        location = point
        forecast = day
    }
    val suppressed = decision.planRestDay || decision.effort == TodayEffort.RUHETAG || decision.effort == TodayEffort.ZIELTAG
    val progress = remember(decision, rides, rideLoads, now, suppressed) {
        if (suppressed) {
            null
        } else {
            runCatching {
                todaySessionProgress(decision.currentWeek, decision.todaySession, rides, now.toInstant().toEpochMilli(), rideLoads)
            }.getOrNull()
        }
    }
    val suggested = remember(decision.route, now, location, forecast, suppressed) {
        if (suppressed) {
            null
        } else {
            runCatching { suggestTodayPlace(daySessionFor(decision.route), decision.route, now, location, forecast) }.getOrNull()
        }
    }
    val place = suggested?.let { applyPlaceChoice(it, choice, date) }
    val done = progress?.status == PlanSessionStatus.ERLEDIGT

    // Fuer die Uhr merken, was hier steht — und nur bei einer Aenderung senden.
    val shown = heuteVorschlagVon(place, date, done, progress?.takeIf { done }?.let { it.riddenIndoor && !it.riddenOutdoor })
    LaunchedEffect(shown) {
        val changed = withContext(Dispatchers.IO) {
            runCatching { writeShownHeute(AppServices.keyValueStore, shown) }.getOrDefault(false)
        }
        if (changed) runCatching { WearGlanceSender.request(context) }
    }

    val ui: TodayPlaceUi = when {
        suppressed -> return
        progress?.status == PlanSessionStatus.ERLEDIGT -> TodayPlaceUi.Done(progress)
        place != null -> TodayPlaceUi.Suggest(place, now)
        else -> return
    }
    TodayPlaceCard(
        ui = ui,
        onFlip = {
            val current = place ?: return@TodayPlaceCard
            val next = PlaceChoice(date, current.place.flipped())
            runCatching { writePlaceChoice(AppServices.keyValueStore, next, current.suggested) }
            choice = next
        },
        onPlanRoute = onPlanRoute,
        modifier = modifier,
    )
}

/** Die zwischengespeicherte Vorhersage fuer [date] — nur bei eingeschaltetem Schalter, ohne Netz. */
private fun cachedTodayForecast(date: LocalDate): DayForecast? = runCatching {
    val store = AppServices.keyValueStore
    if (!readWeekPlanWeatherEnabled(store)) return null
    val cached = CachedForecast.decode(store.getString(WEEK_PLAN_FORECAST_CACHE_KEY)) ?: return null
    parseDailyForecast(cached.body)?.firstOrNull { it.date == date }
}.getOrNull()

/** Die Karte selbst — zustandslos, fuer „Heute" und die Screenshot-Tests. */
@Composable
fun TodayPlaceCard(
    ui: TodayPlaceUi,
    onFlip: () -> Unit,
    onPlanRoute: (RouteTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = LocalAppFormats.current
    if (ui is TodayPlaceUi.Suggest && ui.place.place == RidePlace.OUTDOOR && ui.place.outdoorTarget != null) {
        OutdoorSuggestRouteCard(
            ui = ui,
            target = ui.place.outdoorTarget!!,
            formats = formats,
            onFlip = onFlip,
            onPlanRoute = onPlanRoute,
            modifier = modifier,
        )
        return
    }
    AtlasCard(modifier = modifier) {
        Column(modifier = Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (ui) {
                is TodayPlaceUi.Done -> DoneBody(ui.progress, formats)
                is TodayPlaceUi.Suggest -> SuggestBody(ui, formats, onFlip, onPlanRoute)
            }
        }
    }
}

@Composable
private fun OutdoorSuggestRouteCard(
    ui: TodayPlaceUi.Suggest,
    target: RouteTarget,
    formats: AppFormats,
    onFlip: () -> Unit,
    onPlanRoute: (RouteTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = ui.place
    val metrics = rememberTodayRouteMetrics(
        distanceKm = target.distanceKm,
        ascentM = estimatedAscentM(target.distanceKm, target.ascentPreference),
        exactAscent = false,
        durationMin = routeDurationMinutes(target, fallbackMinutes = p.minutes),
        ascentPreference = target.ascentPreference,
    )
    val lines = p.reasons.take(2).map { placeReasonText(it, p, ui.now, formats) }
    val allBullets = buildList {
        if (lines.isEmpty() && p.suggested == RidePlace.OUTDOOR) {
            add("• ${stringResource(R.string.idee02_place_reason_none)}")
        }
        lines.forEach { add("• $it") }
        if (p.chosen) {
            add(
                "• ${
                    stringResource(
                        if (p.suggested == RidePlace.INDOOR) R.string.idee02_place_suggested_was_indoor
                        else R.string.idee02_place_suggested_was_outdoor,
                    )
                }",
            )
        }
    }
    val title = stringResource(
        if (p.chosen) R.string.idee02_place_title_outdoor_chosen else R.string.idee02_place_title_outdoor,
    )
    val primaryReason = allBullets.firstOrNull()
    val extraReasons = allBullets.drop(1)

    RouteCard(
        title = title,
        subtitle = primaryReason,
        distanceText = metrics.distanceText,
        ascentText = metrics.ascentText,
        durationText = metrics.durationText,
        statusLabel = metrics.statusLabel,
        modifier = modifier,
        media = {
            TodayRoutePreview(
                ascentPreference = target.ascentPreference,
                spokenSummary = "${metrics.distanceText}, ${metrics.ascentText}, ${metrics.durationText}",
                showStatusBadge = false,
                modifier = Modifier.matchParentSize(),
            )
        },
        action = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                extraReasons.forEach { bullet ->
                    Text(text = bullet, style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    TrailscapeSecondaryButton(onClick = { onPlanRoute(target) }) {
                        Text(stringResource(R.string.idee02_place_plan_action))
                    }
                    TextButton(onClick = onFlip) {
                        Text(stringResource(R.string.idee02_place_flip_to_indoor_action))
                    }
                }
            }
        },
    )
}

@Composable
private fun Eyebrow(indoor: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (indoor) Icons.Filled.Home else Icons.Filled.Terrain,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.idee02_place_eyebrow),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SuggestBody(
    ui: TodayPlaceUi.Suggest,
    formats: AppFormats,
    onFlip: () -> Unit,
    onPlanRoute: (RouteTarget) -> Unit,
) {
    val p = ui.place
    val indoor = p.place == RidePlace.INDOOR
    Eyebrow(indoor)
    Text(
        text = stringResource(
            when {
                indoor && p.chosen -> R.string.idee02_place_title_indoor_chosen
                indoor -> R.string.idee02_place_title_indoor
                p.chosen -> R.string.idee02_place_title_outdoor_chosen
                else -> R.string.idee02_place_title_outdoor
            },
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val lines = p.reasons.take(2).map { placeReasonText(it, p, ui.now, formats) }
        if (lines.isEmpty() && p.suggested == RidePlace.OUTDOOR) {
            ReasonLine(stringResource(R.string.idee02_place_reason_none))
        }
        lines.forEach { ReasonLine(it) }
        if (p.chosen) {
            ReasonLine(
                stringResource(
                    if (p.suggested == RidePlace.INDOOR) R.string.idee02_place_suggested_was_indoor
                    else R.string.idee02_place_suggested_was_outdoor,
                ),
            )
        }
    }
    val target = p.outdoorTarget
    if (indoor) {
        Text(
            text = stringResource(R.string.idee02_place_route_indoor),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (target != null) {
        Text(
            text = stringResource(
                R.string.idee02_place_route_outdoor,
                formats.km(target.distanceKm),
                ((target.durationH ?: (p.minutes / 60.0)) * 60).roundToInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!indoor && target != null) {
            TrailscapeSecondaryButton(onClick = { onPlanRoute(target) }) { Text(stringResource(R.string.idee02_place_plan_action)) }
        }
        TextButton(onClick = onFlip) {
            Text(
                stringResource(
                    if (indoor) R.string.idee02_place_flip_to_outdoor_action else R.string.idee02_place_flip_to_indoor_action,
                ),
            )
        }
    }
}

@Composable
private fun ReasonLine(text: String) {
    Text(text = "• $text", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DoneBody(progress: PlanSessionProgress, formats: AppFormats) {
    val indoor = progress.riddenIndoor && !progress.riddenOutdoor
    Eyebrow(indoor)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = stringResource(R.string.idee02_place_done_title), style = MaterialTheme.typography.titleMedium)
    }
    val where = stringResource(
        when {
            progress.riddenIndoor && progress.riddenOutdoor -> R.string.idee02_place_done_both
            indoor -> R.string.idee02_place_done_indoor
            else -> R.string.idee02_place_done_outdoor
        },
    )
    val minutes = progress.riddenMinutes
    val km = progress.riddenKm
    Text(
        text = when {
            // Drinnen sind die Kilometer virtuell — dort zaehlt die Zeit.
            indoor && minutes != null -> stringResource(R.string.idee02_place_done_minutes, where, minutes)
            km != null -> stringResource(R.string.idee02_place_done_km, where, formats.km(km))
            minutes != null -> stringResource(R.string.idee02_place_done_minutes, where, minutes)
            else -> where
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Der Satz zu einem Grund (Uhrzeiten in der Zeitzone von [now]). */
@Composable
private fun placeReasonText(reason: PlaceReason, p: TodayPlace, now: ZonedDateTime, formats: AppFormats): String {
    val zone: ZoneId = now.zone
    val setAt = p.sunset
    val riseAt = p.sunrise
    val sunset = setAt?.atZone(zone)?.toLocalTime()?.let { formats.time(it) }
    val sunrise = riseAt?.atZone(zone)?.toLocalTime()?.let { formats.time(it) }
    return when (reason) {
        PlaceReason.DARK -> when {
            setAt == null || riseAt == null || sunset == null || sunrise == null ->
                stringResource(R.string.idee02_place_reason_dark_polar)
            now.toInstant().isBefore(riseAt) -> stringResource(R.string.idee02_place_reason_dark_before, sunrise)
            !now.toInstant().isBefore(setAt) -> stringResource(R.string.idee02_place_reason_dark_after, sunset)
            else -> stringResource(R.string.idee02_place_reason_dark_share, sunset, p.minutes)
        }
        PlaceReason.DUSK -> stringResource(R.string.idee02_place_reason_dusk, sunset.orEmpty())
        PlaceReason.DAYLIGHT -> stringResource(R.string.idee02_place_reason_daylight, sunset.orEmpty(), p.minutes)
        PlaceReason.INTERVALS -> stringResource(R.string.idee02_place_reason_intervals)
        PlaceReason.LONG_ENDURANCE -> stringResource(R.string.idee02_place_reason_long, p.minutes)
        PlaceReason.RAIN -> stringResource(R.string.idee02_place_reason_rain)
        PlaceReason.STORM -> stringResource(R.string.idee02_place_reason_storm)
        PlaceReason.FROST -> stringResource(R.string.idee02_place_reason_frost)
    }
}
