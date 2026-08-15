package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.FitnessAssessment
import de.trailscape.core.levelLabels
import kotlin.math.roundToInt

/**
 * Karte „Dein Fitnesslevel": Einstufung (Einsteiger/Fortgeschritten/
 * Ambitioniert) aus den Fahrten der letzten 8 Wochen.
 *
 * Port von `_buildFitnessCard` (`lib/screens/training_screen.dart`).
 */
@Composable
fun FitnessCard(assessment: FitnessAssessment) {
    val theme = MaterialTheme.colorScheme
    val levelColor = LocalSignalColors.current.accentGreen

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Dein Fitnesslevel", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            // Derselbe Chip wie die Wochentyp-Marke im Trainingsplan:
            // die getoente [TagPill] mit Text in der Vollfarbe.
            TagPill(
                text = levelLabels.getValue(assessment.level),
                containerColor = levelColor.copy(alpha = 0.15f),
                contentColor = levelColor,
            )
            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.padding(end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InlineMetric(formatKmDe(assessment.weeklyKm), "km/Woche")
                InlineMetric(assessment.weeklyHm.roundToInt().toString(), "Hm/Woche")
                InlineMetric(germanFixed(assessment.weeklyRides, 1), "Touren/Woche")
                InlineMetric(formatKmDe(assessment.longestRideKm), "km längste Tour")
            }

            if (assessment.rideCount == 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Noch keine Touren der letzten 8 Wochen vorhanden – die " +
                        "Einstufung ist daher konservativ.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
            }
        }
    }
}
