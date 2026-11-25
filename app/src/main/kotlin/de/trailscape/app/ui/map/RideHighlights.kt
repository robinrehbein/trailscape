package de.trailscape.app.ui.map

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.AppLocale
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.localizedFor
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.erfolge.ErfolgeIcons
import de.trailscape.app.ui.erfolge.badgeHighlightText
import de.trailscape.app.ui.erfolge.rememberErfolgeOfRide
import de.trailscape.app.ui.progress.IconBubble
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.ChallengeKind
import de.trailscape.core.MilestoneKind
import de.trailscape.core.RecordKind
import de.trailscape.core.RideFact
import de.trailscape.core.RideHighlight
import de.trailscape.core.RouteComparison
import de.trailscape.core.earthCircumferenceKm
import de.trailscape.core.everestM
import de.trailscape.core.formatDuration
import de.trailscape.core.rideHighlights
import de.trailscape.core.withAchievementHighlight
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * # „Was die Tour gebracht hat" — direkt auf dem Tourblatt
 *
 * Nach dem Beenden einer Aufzeichnung waehlt das [AppViewModel] die neue Tour
 * aus, und die Karte zeigt ihr Tourblatt ([RideCard]). Bestwerte, Runden-
 * vergleich und Wochenziel standen bisher erst im Tourdetail („Auswertung").
 * Hier stehen die zwei, drei wichtigsten davon gleich ueber den Aktionen —
 * gerechnet von `core/…/RideHighlights.kt` ([rideHighlights]), formuliert
 * hier.
 *
 * ## Wann das Blatt sie zeigt
 * Dasselbe Tourblatt oeffnet sich auch, wenn man eine Tour aus der Liste der
 * gespeicherten Routen oder vom Verlauf aus antippt. Die Erfolge erscheinen
 * nur fuer die **juengste gefahrene Tour, wenn sie von heute ist** — also im
 * Moment nach dem Stopp und fuer den Rest des Tages, wenn man die Tour noch
 * einmal aufruft. Fuer aeltere Touren waeren Saetze wie „Noch 22 km bis zum
 * Wochenziel (3 Tage)" schlicht falsch, und ihre Bestwerte zeigt das Detail
 * („damals deine weiteste"). Eine eigene Markierung „gerade beendet" im
 * ViewModel braucht es dafuer nicht: Die Regel ergibt sich aus den Touren
 * selbst und gilt genauso fuer eine Tour, die die Uhr oder ein Import gerade
 * gebracht hat.
 *
 * ## Ohne Verzoegerung
 * Alles ausser dem Rundenvergleich kommt aus den Zusammenfassungen und steht
 * mit dem Blatt da. Der Vergleich liest Signaturen von der Platte
 * ([AppViewModel.routeComparisonFor], wie im Tourdetail ueber
 * [produceState]); seine Zeile kommt dazu, sobald er fertig ist.
 *
 * ## Niedrige Bildschirme
 * Das Blatt schwebt ueber der Karte, und die Tour soll darueber sichtbar
 * bleiben. Unter [HIGHLIGHTS_SHORT_SCREEN_DP] Bildschirmhoehe (z. B.
 * 360 × 640 dp) zeigt es deshalb nur die zwei wichtigsten Zeilen: Mit
 * groesserer Schrift brechen die Saetze dort auf zwei Zeilen um, und drei
 * davon schoeben das Blatt ueber die Haelfte des Bildschirms. Der Rest steht
 * wie immer in der „Auswertung".
 *
 * @param weekTargetKm dasselbe Wochenziel wie auf „Heute"
 *   (`ui/TodayRouteTarget.kt`, `weekTarget`).
 */
@Composable
internal fun rememberRideHighlights(
    appViewModel: AppViewModel,
    rideId: String,
    rideUpdatedAt: Long,
    planned: Boolean,
    facts: List<RideFact>,
    today: LocalDate,
    weekTargetKm: Int?,
): List<RideHighlight> {
    // „Von heute oder gestern": Der Tag einer Tour ist der ihres STARTS. Wer
    // um 23:30 losfaehrt und nach Mitternacht stoppt, hat eine Tour „von
    // gestern" — ebenso eine Aufzeichnung, die erst beim naechsten App-Start
    // aus dem Journal gerettet wird. Deren Bestwerte sollen nicht
    // verschwinden. Wochenziel und Challenge pruefen ihre Woche bzw. ihren
    // Monat in `:core` selbst und behaupten deshalb nichts Falsches.
    val eligible = !planned &&
        facts.lastOrNull()?.let { it.id == rideId && !it.date.isBefore(today.minusDays(1)) } == true
    val comparison by produceState<RouteComparison?>(null, rideId, rideUpdatedAt, eligible) {
        value = if (eligible) appViewModel.routeComparisonFor(rideId) else null
    }
    val max = if (LocalConfiguration.current.screenHeightDp < HIGHLIGHTS_SHORT_SCREEN_DP) 2 else 3
    // Neue Abzeichen der Tour (docs/erfolge.md); leer bei „Erfolge anzeigen" aus.
    val badges = rememberErfolgeOfRide(appViewModel, rideId, active = eligible)
    return remember(eligible, rideId, facts, today, weekTargetKm, comparison, max, badges) {
        if (eligible) {
            withAchievementHighlight(rideHighlights(rideId, facts, today, weekTargetKm, comparison, max = max), badges, max)
        } else {
            emptyList()
        }
    }
}

/** Unterhalb dieser Bildschirmhoehe (dp) zeigt das Tourblatt hoechstens zwei Erfolge. */
internal const val HIGHLIGHTS_SHORT_SCREEN_DP = 700

/**
 * Bis zu drei Zeilen: getoentes Symbol, ein Satz. Kein Titel, keine eigene
 * Karte — das Blatt schwebt ueber der Karte und soll niedrig bleiben. Leer,
 * wenn die Tour nichts gebracht hat, was sich ehrlich sagen laesst.
 */
@Composable
internal fun RideHighlightsBlock(highlights: List<RideHighlight>, modifier: Modifier = Modifier) {
    if (highlights.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (highlight in highlights) {
            val (icon, tint) = highlightIcon(highlight)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBubble(icon, tint, size = 28.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = highlightText(highlight),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Dieselben Symbole und Toenungen wie „Erfolge" im Tourdetail und die Challenge-Karte. */
@Composable
private fun highlightIcon(highlight: RideHighlight): Pair<ImageVector, Color> {
    val signals = LocalSignalColors.current
    return when (highlight) {
        is RideHighlight.Record -> Icons.Rounded.EmojiEvents to signals.warning
        is RideHighlight.RouteBest -> Icons.Rounded.EmojiEvents to signals.good
        is RideHighlight.RouteFaster -> Icons.Rounded.Timer to MaterialTheme.colorScheme.primary
        is RideHighlight.MilestoneReached -> Icons.Rounded.Flag to signals.accentBlue
        is RideHighlight.WeekGoalReached -> Icons.Rounded.CheckCircle to signals.good
        is RideHighlight.WeekGoalProgress -> Icons.Rounded.Route to MaterialTheme.colorScheme.primary
        is RideHighlight.StreakExtended -> Icons.Rounded.LocalFireDepartment to signals.warning
        is RideHighlight.AchievementsUnlocked -> ErfolgeIcons.Medal to signals.warning
        // Wie `challengeIcon` in `ui/progress/GoalCards.kt` (dort privat).
        is RideHighlight.ChallengeReached -> when (highlight.challenge.kind) {
            ChallengeKind.DISTANCE -> Icons.Rounded.Route
            ChallengeKind.ASCENT -> Icons.Rounded.Terrain
            ChallengeKind.ACTIVE_DAYS -> Icons.Rounded.CalendarMonth
            ChallengeKind.LONG_RIDE -> Icons.Rounded.Straighten
        } to signals.good
    }
}

/**
 * Der Satz zu [highlight] im Tourblatt — derselbe wie [rideHighlightText],
 * aufgeloest mit den Ressourcen und Zahlformaten der Komposition.
 * `LocalConfiguration` wird gelesen wie in `stringResource`, damit ein
 * Konfigurationswechsel neu formuliert.
 */
@Composable
private fun highlightText(highlight: RideHighlight): String {
    LocalConfiguration.current
    return rideHighlightText(LocalContext.current.resources, LocalAppFormats.current, highlight)
}

/**
 * Die Saetze zu [highlights] ausserhalb der Oberflaeche, in der App-Sprache —
 * fuer die Uhr nach der Fahrt (`wear/WearAbschluss.kt`). [context] wird hier
 * selbst lokalisiert (`localizedFor`, wie Dienste es tun, docs/i18n.md
 * „Dienste, Worker, Benachrichtigungen").
 */
internal fun rideHighlightTexts(context: Context, highlights: List<RideHighlight>): List<String> {
    if (highlights.isEmpty()) return emptyList()
    val language = AppLocale.current(context)
    val resources = context.localizedFor(language).resources
    val formats = AppFormats(language)
    return highlights.map { rideHighlightText(resources, formats, it) }
}

/**
 * Der eine Satz zu [highlight] — die einzige Stelle, die die
 * `map_highlight_*`-Ressourcen waehlt und fuellt. Das Tourblatt
 * ([highlightText]) und die Uhr ([rideHighlightTexts]) rufen beide hierher.
 *
 * Ohne Compose: [resources] und [formats] muessen dieselbe Sprache meinen.
 */
internal fun rideHighlightText(resources: Resources, formats: AppFormats, highlight: RideHighlight): String =
    when (highlight) {
        is RideHighlight.Record -> {
            val gain = highlight.gain
            resources.getString(
                when (gain.kind) {
                    RecordKind.DISTANCE -> R.string.map_highlight_record_distance
                    RecordKind.ASCENT -> R.string.map_highlight_record_ascent
                    RecordKind.TIME -> R.string.map_highlight_record_time
                    RecordKind.SPEED -> R.string.map_highlight_record_speed
                },
                // Zahl und Einheit bleiben zusammen (geschuetztes Leerzeichen):
                // Ein einzelnes „km" in der zweiten Zeile kostet Hoehe und liest
                // sich schlecht. Der alte Wert steht ohne Einheit — „(vorher
                // 61,0)" ist eindeutig und haelt die Zeile kurz.
                recordValueText(resources, formats, gain.kind, gain.value).replace(' ', '\u00A0'),
                recordNumber(formats, gain.kind, gain.previous),
            )
        }

        is RideHighlight.RouteBest ->
            resources.getString(R.string.map_highlight_route_best, highlightDelta(resources, highlight.deltaToBestOtherS))

        is RideHighlight.RouteFaster ->
            resources.getString(R.string.map_highlight_route_faster, highlightDelta(resources, highlight.deltaToPreviousS))

        is RideHighlight.MilestoneReached ->
            resources.getString(
                R.string.map_highlight_milestone,
                milestoneNameText(resources, formats, highlight.milestone.kind, highlight.milestone.threshold),
            )

        is RideHighlight.WeekGoalReached ->
            resources.getString(
                R.string.map_highlight_week_goal_reached,
                formats.decimal(highlight.riddenKm, 0),
                highlight.targetKm,
            )

        // Der Rest wird aufgerundet wie bei den Meilensteinen: „Noch 0 km" bei
        // 0,4 fehlenden Kilometern saehe erreicht aus und ist es nicht.
        is RideHighlight.WeekGoalProgress ->
            resources.getQuantityString(
                R.plurals.map_highlight_week_goal_remaining_count,
                highlight.daysLeft,
                highlight.daysLeft,
                formats.decimal(ceil(highlight.remainingKm).coerceAtLeast(1.0), 0),
            )

        is RideHighlight.StreakExtended ->
            resources.getQuantityString(R.plurals.map_highlight_streak_count, highlight.weeks, highlight.weeks)

        is RideHighlight.AchievementsUnlocked -> badgeHighlightText(resources, highlight.ids)

        is RideHighlight.ChallengeReached -> {
            val target = highlight.challenge.target
            when (highlight.challenge.kind) {
                ChallengeKind.DISTANCE ->
                    resources.getString(R.string.map_highlight_challenge_distance, formats.decimal(target, 0))
                ChallengeKind.ASCENT ->
                    resources.getString(R.string.map_highlight_challenge_ascent, formats.decimal(target, 0))
                ChallengeKind.ACTIVE_DAYS ->
                    resources.getQuantityString(
                        R.plurals.map_highlight_challenge_days_count,
                        target.toInt(),
                        target.toInt(),
                    )
                ChallengeKind.LONG_RIDE ->
                    resources.getString(R.string.map_highlight_challenge_long_ride, formats.decimal(target, 0))
            }
        }
    }

/**
 * Ein Bestwert mit Einheit („82,4 km", „1100 Hm", „4:10 h", „27,3 km/h") —
 * dieselben Ressourcen und Formate wie `recordValue` in
 * `ui/progress/ProgressFormat.kt`, dort aber nur als Composable zu haben.
 */
private fun recordValueText(resources: Resources, formats: AppFormats, kind: RecordKind, value: Double): String =
    when (kind) {
        RecordKind.DISTANCE -> resources.getString(R.string.common_value_km, formats.km(value))
        RecordKind.ASCENT -> resources.getString(R.string.progress_value_ascent, formats.decimal(value, 0))
        RecordKind.TIME -> {
            val totalMin = (value / 60).roundToInt()
            resources.getString(R.string.progress_duration_hm, totalMin / 60, (totalMin % 60).toString().padStart(2, '0'))
        }
        RecordKind.SPEED -> resources.getString(R.string.common_value_kmh, formats.decimal(value, 1))
    }

/**
 * Name einer Meilenstein-Stufe — dieselben Ressourcen wie `milestoneName` in
 * `ui/progress/ProgressFormat.kt` (dort nur als Composable).
 */
private fun milestoneNameText(resources: Resources, formats: AppFormats, kind: MilestoneKind, threshold: Double): String =
    when (kind) {
        MilestoneKind.TOTAL_KM ->
            if (threshold == earthCircumferenceKm) {
                resources.getString(R.string.progress_milestone_earth)
            } else {
                resources.getString(R.string.progress_milestone_total_km, formats.decimal(threshold, 0))
            }

        MilestoneKind.TOTAL_ASCENT ->
            if (threshold == everestM) {
                resources.getString(R.string.progress_milestone_everest)
            } else {
                resources.getString(R.string.progress_milestone_total_ascent, formats.decimal(threshold, 0))
            }

        MilestoneKind.RIDE_COUNT ->
            resources.getQuantityString(R.plurals.progress_milestone_ride_count, threshold.toInt(), threshold.toInt())

        MilestoneKind.LONGEST_RIDE ->
            resources.getString(R.string.progress_milestone_longest_ride, formats.decimal(threshold, 0))
    }

/** Ein Bestwert als nackte Zahl: „61,0", „1100", „3:05", „27,3". */
private fun recordNumber(formats: AppFormats, kind: RecordKind, value: Double): String = when (kind) {
    RecordKind.DISTANCE -> formats.km(value)
    RecordKind.ASCENT -> formats.decimal(value, 0)
    RecordKind.TIME -> {
        val totalMin = (value / 60).roundToInt()
        "${totalMin / 60}:${(totalMin % 60).toString().padStart(2, '0')}"
    }
    RecordKind.SPEED -> formats.decimal(value, 1)
}

/**
 * Abstand zweier Zeiten ohne Vorzeichen: „45 s", „3:12 min", „1:02:05 h" —
 * wie `routeDelta` in `ui/rides/RouteComparisonCard.kt` (dort privat).
 */
private fun highlightDelta(resources: Resources, seconds: Int): String {
    val s = abs(seconds)
    return when {
        s < 60 -> resources.getString(R.string.map_highlight_delta_seconds, s)
        s < 3600 -> resources.getString(R.string.map_highlight_delta_minutes, formatDuration(s))
        else -> resources.getString(R.string.map_highlight_delta_hours, formatDuration(s))
    }
}
