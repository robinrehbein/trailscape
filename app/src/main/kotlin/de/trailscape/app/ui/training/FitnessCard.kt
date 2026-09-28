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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.FitnessAssessment
import kotlin.math.roundToInt

/**
 * Karte „Dein Fitnesslevel": Einstufung (Einsteiger/Fortgeschritten/
 * Ambitioniert) aus den Fahrten der letzten 8 Wochen.
 *
 * Sie steht seit dem Redesign „Klartext" im Blatt „Deine Form" ([FormSheet])
 * unter „Alle Werte": Kurve und Kennzahlen sagen, wie die Form gerade steht —
 * diese Karte sagt, auf welchem Niveau. Im Prototyp kommt sie nicht vor;
 * entfallen ist sie deshalb nicht.
 *
 * Port von `_buildFitnessCard` (`lib/screens/training_screen.dart`).
 */
@Composable
fun FitnessCard(assessment: FitnessAssessment) {
    val theme = MaterialTheme.colorScheme
    val formats = LocalAppFormats.current
    val levelColor = LocalSignalColors.current.accentGreen

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(stringResource(R.string.training_fitness_title), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            // Derselbe Chip wie die Wochentyp-Marke im Trainingsplan:
            // die getoente [TagPill] mit Text in der Vollfarbe.
            TagPill(
                text = LocalCoreTexts.current.training.fitnessLevel(assessment.level),
                containerColor = levelColor.copy(alpha = 0.15f),
                contentColor = levelColor,
            )
            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.padding(end = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InlineMetric(formats.km(assessment.weeklyKm), stringResource(R.string.training_fitness_weekly_km_label))
                InlineMetric(
                    assessment.weeklyHm.roundToInt().toString(),
                    stringResource(R.string.training_fitness_weekly_elevation_label),
                )
                InlineMetric(
                    formats.decimal(assessment.weeklyRides, 1),
                    stringResource(R.string.training_fitness_weekly_rides_label),
                )
                InlineMetric(
                    formats.km(assessment.longestRideKm),
                    stringResource(R.string.training_fitness_longest_ride_label),
                )
            }

            if (assessment.rideCount == 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.training_fitness_no_rides_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
            }
        }
    }
}
