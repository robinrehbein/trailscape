package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.indoor.climb.ClimbEffort

/**
 * # Anstiege in der Auswertung
 *
 * Je gefahrenem Anstieg eine Zeile: Nummer (und Runde), Laenge, Steigung,
 * Hoehenmeter und Passhoehe, die Zeit am Anstieg und wie sie ausging
 * („Neue Bestzeit", „Erste Zeit …", „ohne Wertung"). Ohne gefahrene Anstiege
 * erscheint nichts.
 */
@Composable
internal fun IndoorTrailClimbs(climbs: List<ClimbEffort>) {
    if (climbs.isEmpty()) return
    val showLap = climbs.any { it.lap > 1 }
    Eyebrow(stringResource(R.string.trail_summary_title))
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            climbs.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider()
                ClimbRow(e, showLap)
            }
        }
    }
}

@Composable
private fun ClimbRow(e: ClimbEffort, showLap: Boolean) {
    val formats = LocalAppFormats.current
    val signal = LocalSignalColors.current
    val number = e.climb.index + 1
    val title = if (showLap) stringResource(R.string.trail_summary_climb_lap, number, e.lap) else stringResource(R.string.trail_summary_climb, number)
    val outcome = climbOutcome(e)
    val color: Color = when (outcome) {
        ClimbOutcome.FIRST, ClimbOutcome.NEW_BEST -> signal.good
        ClimbOutcome.SLOWER -> signal.warning
        ClimbOutcome.UNRANKED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            climbCategoryLabel(e.climb.category)?.let {
                Text(text = it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "  ·  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = formatClimbTime(e.timeMs),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            )
        }
        Text(
            text = stringResource(
                R.string.trail_summary_detail,
                formats.climbKm(e.climb),
                formats.climbGrade(e.climb),
                formats.decimal(e.climb.gainM, 0),
                formats.decimal(e.climb.topEleM, 0),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = climbVerdictText(e), style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
            val best = if (e.newBest) null else e.previousBestMs
            best?.let {
                Text(
                    text = stringResource(R.string.trail_summary_best, formatClimbTime(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
