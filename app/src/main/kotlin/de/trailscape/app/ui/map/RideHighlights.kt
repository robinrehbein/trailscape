package de.trailscape.app.ui.map

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.progress.IconBubble
import de.trailscape.app.ui.progress.milestoneName
import de.trailscape.app.ui.progress.recordValue
import de.trailscape.app.ui.progress.whole
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.ChallengeKind
import de.trailscape.core.RecordKind
import de.trailscape.core.RideFact
import de.trailscape.core.RideHighlight
import de.trailscape.core.RouteComparison
import de.trailscape.core.formatDuration
import de.trailscape.core.rideHighlights
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
    val eligible = !planned && facts.lastOrNull()?.let { it.id == rideId && it.date == today } == true
    val comparison by produceState<RouteComparison?>(null, rideId, rideUpdatedAt, eligible) {
        value = if (eligible) appViewModel.routeComparisonFor(rideId) else null
    }
    val max = if (LocalConfiguration.current.screenHeightDp < HIGHLIGHTS_SHORT_SCREEN_DP) 2 else 3
    return remember(eligible, rideId, facts, today, weekTargetKm, comparison, max) {
        if (eligible) rideHighlights(rideId, facts, today, weekTargetKm, comparison, max = max) else emptyList()
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
        // Wie `challengeIcon` in `ui/progress/GoalCards.kt` (dort privat).
        is RideHighlight.ChallengeReached -> when (highlight.challenge.kind) {
            ChallengeKind.DISTANCE -> Icons.Rounded.Route
            ChallengeKind.ASCENT -> Icons.Rounded.Terrain
            ChallengeKind.ACTIVE_DAYS -> Icons.Rounded.CalendarMonth
            ChallengeKind.LONG_RIDE -> Icons.Rounded.Straighten
        } to signals.good
    }
}

@Composable
private fun highlightText(highlight: RideHighlight): String = when (highlight) {
    is RideHighlight.Record -> {
        val gain = highlight.gain
        stringResource(
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
            recordValue(gain.kind, gain.value).replace(' ', '\u00A0'),
            recordNumber(gain.kind, gain.previous),
        )
    }

    is RideHighlight.RouteBest ->
        stringResource(R.string.map_highlight_route_best, highlightDelta(highlight.deltaToBestOtherS))

    is RideHighlight.RouteFaster ->
        stringResource(R.string.map_highlight_route_faster, highlightDelta(highlight.deltaToPreviousS))

    is RideHighlight.MilestoneReached ->
        stringResource(
            R.string.map_highlight_milestone,
            milestoneName(highlight.milestone.kind, highlight.milestone.threshold),
        )

    is RideHighlight.WeekGoalReached ->
        stringResource(R.string.map_highlight_week_goal_reached, whole(highlight.riddenKm), highlight.targetKm)

    // Der Rest wird aufgerundet wie bei den Meilensteinen: „Noch 0 km" bei
    // 0,4 fehlenden Kilometern saehe erreicht aus und ist es nicht.
    is RideHighlight.WeekGoalProgress ->
        pluralStringResource(
            R.plurals.map_highlight_week_goal_remaining_count,
            highlight.daysLeft,
            highlight.daysLeft,
            whole(ceil(highlight.remainingKm).coerceAtLeast(1.0)),
        )

    is RideHighlight.StreakExtended ->
        pluralStringResource(R.plurals.map_highlight_streak_count, highlight.weeks, highlight.weeks)

    is RideHighlight.ChallengeReached -> {
        val target = highlight.challenge.target
        when (highlight.challenge.kind) {
            ChallengeKind.DISTANCE -> stringResource(R.string.map_highlight_challenge_distance, whole(target))
            ChallengeKind.ASCENT -> stringResource(R.string.map_highlight_challenge_ascent, whole(target))
            ChallengeKind.ACTIVE_DAYS ->
                pluralStringResource(R.plurals.map_highlight_challenge_days_count, target.toInt(), target.toInt())
            ChallengeKind.LONG_RIDE -> stringResource(R.string.map_highlight_challenge_long_ride, whole(target))
        }
    }
}

/** Ein Bestwert als nackte Zahl: „61,0", „1100", „3:05", „27,3". */
@Composable
private fun recordNumber(kind: RecordKind, value: Double): String {
    val formats = LocalAppFormats.current
    return when (kind) {
        RecordKind.DISTANCE -> formats.km(value)
        RecordKind.ASCENT -> whole(value)
        RecordKind.TIME -> {
            val totalMin = (value / 60).roundToInt()
            "${totalMin / 60}:${(totalMin % 60).toString().padStart(2, '0')}"
        }
        RecordKind.SPEED -> formats.decimal(value, 1)
    }
}

/**
 * Abstand zweier Zeiten ohne Vorzeichen: „45 s", „3:12 min", „1:02:05 h" —
 * wie `routeDelta` in `ui/rides/RouteComparisonCard.kt` (dort privat).
 */
@Composable
private fun highlightDelta(seconds: Int): String {
    val s = abs(seconds)
    return when {
        s < 60 -> stringResource(R.string.map_highlight_delta_seconds, s)
        s < 3600 -> stringResource(R.string.map_highlight_delta_minutes, formatDuration(s))
        else -> stringResource(R.string.map_highlight_delta_hours, formatDuration(s))
    }
}
