package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.core.readinessBandLabels
import kotlin.math.roundToInt

/**
 * Karte „Heute": Readiness-Score, Band und Tagesempfehlung.
 *
 * Port von `_buildTodayCard` (`lib/screens/training_screen.dart`). Bewusst
 * ohne `TweenAnimationBuilder`-Aequivalent: der Score wird statisch gezeigt
 * (siehe KDoc von [de.trailscape.app.ui.training.TrainingScreen]).
 *
 * @param onPlanRoute laesst zur Empfehlung eine passende Runde suchen (siehe
 *   `ui/map/RouteGenerationPanel.kt`). `null` an einem Ruhetag: `:core` liefert
 *   dafuer kein Routenziel, und eine Ausfahrt ist dann das falsche Angebot.
 */
@Composable
fun ReadinessCard(insights: TrainingInsights, onPlanRoute: (() -> Unit)? = null) {
    val theme = MaterialTheme.colorScheme
    val readiness = insights.readiness
    val recommendation = insights.recommendation
    val color = if (readiness.available) readinessBandColor(readiness.band) else theme.onSurfaceVariant
    val missingDays = insights.fitness.daysUntilDisplayReady

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Heute", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            if (readiness.available) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = readiness.score.roundToInt().toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Erholung (0–100)",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onSurfaceVariant,
                        )
                        Text(
                            text = readinessBandLabels.getValue(readiness.band),
                            style = MaterialTheme.typography.titleSmall,
                            color = color,
                        )
                    }
                }
            } else {
                Text(
                    text = readiness.unavailableReason ?: readiness.headline,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (missingDays > 0) {
                    Text(
                        text = "Braucht noch $missingDays " +
                            "${if (missingDays == 1) "Tag" else "Tage"} Daten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            NoticeBox(
                icon = Icons.Filled.Favorite,
                color = color,
                title = recommendation.title,
                text = recommendation.detail,
            )

            if (recommendation.reasons.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    for (reason in recommendation.reasons) {
                        Text(
                            text = "· $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }

            if (readiness.available) {
                Text(
                    text = readiness.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (onPlanRoute != null) {
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(onClick = onPlanRoute) {
                    Icon(
                        Icons.Filled.Route,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Passende Route planen")
                }
            }
        }
    }
}
