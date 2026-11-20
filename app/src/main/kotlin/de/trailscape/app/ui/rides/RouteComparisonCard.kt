package de.trailscape.app.ui.rides

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.RouteComparison
import de.trailscape.core.RouteEffort
import de.trailscape.core.formatDuration
import kotlin.math.abs

/** Hoechstens so viele Fahrten stehen in der Liste der Karte. */
private const val ROUTE_RECENT_EFFORTS = 5

/**
 * „Diese Runde" — wie die Tour im Vergleich mit frueheren Fahrten auf
 * derselben Strecke dasteht (Rechnung in `:core`/`RouteMatch.kt`, geladen
 * ueber [de.trailscape.app.ui.AppViewModel.routeComparisonFor]).
 *
 * Aufbau:
 *  1. Titel „Diese Runde" und rechts „5× gefahren".
 *  2. Ein Satz: „Neue Bestzeit auf dieser Runde" (Pokal, gruen; die Fahrt
 *     ist die schnellste und hat eine fruehere geschlagen), „Bestzeit auf
 *     dieser Runde" (Pokal, gruen; schnellste, aber die erste Fahrt — „neu"
 *     waere falsch, wenn sie spaeter angesehen wird, ohne je etwas geschlagen
 *     zu haben) oder „2. schnellste von 5" (Stoppuhr).
 *  3. Gegen das letzte Mal: „3:12 min schneller als beim letzten Mal (12.10.)"
 *     — entfaellt, wenn dies die erste Fahrt der Runde war.
 *  4. Gegen die Bestzeit, wenn diese Fahrt sie nicht haelt.
 *  5. Die juengsten Fahrten (Datum, Zeit, Ø km/h); diese Fahrt hervorgehoben,
 *     jede andere oeffnet per Tipp ihre eigene Detailansicht ([onOpenRide]).
 *  6. Ein leiser Hinweis, was hier verglichen wird.
 *
 * Gestaltung wie die Karten in `ui/progress/ProgressCards.kt` (Titel in
 * `titleSmall`, runde getoente Symbole, Zeilen mit Pfeil); deren private
 * Bausteine sind hier im Kleinen nachgebaut statt geteilt — die Datei gehoert
 * einem anderen Bereich.
 *
 * Gezeigt wird die Bewegungszeit (`movingTimeS`) — dieselbe Groesse, nach der
 * `:core` sortiert; Fahrten ohne Bewegungszeit zaehlen dort nicht mit.
 */
@Composable
internal fun RouteComparisonCard(
    comparison: RouteComparison,
    onOpenRide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = LocalAppFormats.current
    val signals = LocalSignalColors.current
    val isBest = comparison.isBest
    // „Neu" nur, wenn es etwas zu schlagen gab: Die erste Fahrt der Runde ist
    // zwar (noch) die schnellste, aber nie eine neue Bestzeit gewesen.
    val newBest = isBest && comparison.previous != null

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = CardPadding, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.rides_route_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Text(
                    text = pluralStringResource(R.plurals.rides_route_ridden_count, comparison.count, comparison.count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBest) {
                    RouteIconBubble(Icons.Rounded.EmojiEvents, signals.good)
                } else {
                    RouteIconBubble(Icons.Rounded.Timer, MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        newBest -> stringResource(R.string.rides_route_new_best_title)
                        isBest -> stringResource(R.string.rides_route_best_title)
                        else -> stringResource(R.string.rides_route_rank_title, comparison.rank, comparison.count)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isBest) signals.good else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            val previous = comparison.previous
            val deltaPrevious = comparison.deltaToPreviousS
            if (previous != null && deltaPrevious != null) {
                val date = formats.dateShort(previous.createdAt)
                Text(
                    text = when {
                        deltaPrevious < 0 ->
                            stringResource(R.string.rides_route_previous_faster, routeDelta(deltaPrevious), date)
                        deltaPrevious > 0 ->
                            stringResource(R.string.rides_route_previous_slower, routeDelta(deltaPrevious), date)
                        else -> stringResource(R.string.rides_route_previous_equal, date)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val deltaBest = comparison.deltaToBestOtherS
            if (!comparison.isBest && deltaBest != null) {
                val best = comparison.best
                val bestTime = formatDuration(best.timeS)
                val bestDate = formats.dateShort(best.createdAt)
                Text(
                    text = if (deltaBest > 0) {
                        stringResource(R.string.rides_route_best_line, bestTime, bestDate, routeDelta(deltaBest))
                    } else {
                        stringResource(R.string.rides_route_best_line_equal, bestTime, bestDate)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = CardPadding),
        )
        Text(
            text = stringResource(R.string.rides_route_recent_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 12.dp, bottom = 4.dp),
        )
        for (effort in recentEfforts(comparison)) {
            val isCurrent = effort.rideId == comparison.current.rideId
            RouteEffortRow(
                effort = effort,
                isCurrent = isCurrent,
                onClick = if (isCurrent) null else ({ onOpenRide(effort.rideId) }),
            )
        }

        Text(
            text = stringResource(R.string.rides_route_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 8.dp, bottom = CardPadding),
        )
    }
}

/**
 * Die juengsten [ROUTE_RECENT_EFFORTS] Fahrten, neueste oben. Liegt diese
 * Fahrt weiter zurueck (eine alte Tour wird angesehen), ersetzt sie die
 * aelteste der Liste — die hervorgehobene Zeile soll nie fehlen.
 */
internal fun recentEfforts(comparison: RouteComparison): List<RouteEffort> {
    val recent = comparison.efforts.takeLast(ROUTE_RECENT_EFFORTS)
    val withCurrent = if (recent.any { it.rideId == comparison.current.rideId }) {
        recent
    } else {
        comparison.efforts.takeLast(ROUTE_RECENT_EFFORTS - 1) + comparison.current
    }
    return withCurrent.sortedWith(compareByDescending<RouteEffort> { it.createdAt }.thenByDescending { it.rideId })
}

/** Abstand zweier Zeiten ohne Vorzeichen: „45 s", „3:12 min", „1:02:05 h". */
@Composable
private fun routeDelta(seconds: Int): String {
    val s = abs(seconds)
    return when {
        s < 60 -> stringResource(R.string.rides_route_delta_seconds, s)
        s < 3600 -> stringResource(R.string.rides_route_delta_minutes, formatDuration(s))
        else -> stringResource(R.string.rides_route_delta_hours, formatDuration(s))
    }
}

/** Rundes, getoentes Symbol — wie `IconBubble` in `ui/progress/ProgressCards.kt`. */
@Composable
private fun RouteIconBubble(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Eine Fahrt der Liste: Datum, Zeit, Ø km/h; diese Fahrt getoent, die anderen antippbar. */
@Composable
private fun RouteEffortRow(effort: RouteEffort, isCurrent: Boolean, onClick: (() -> Unit)?) {
    val formats = LocalAppFormats.current
    val date = formats.dateShort(effort.createdAt)
    val numberStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val weight = if (isCurrent) FontWeight.SemiBold else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = stringResource(R.string.rides_route_effort_open_cd),
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                },
            )
            .heightIn(min = 48.dp)
            .padding(start = CardPadding, end = CardPadding - 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isCurrent) stringResource(R.string.rides_route_effort_this, date) else date,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(effort.timeS),
            style = numberStyle,
            fontWeight = weight,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Text(
            text = stringResource(R.string.common_value_kmh, formats.decimal(effort.avgSpeedKmh, 1)),
            style = numberStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 96.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}
