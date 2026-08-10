package de.trailscape.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatDate
import de.trailscape.app.ui.formatDateShort
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.core.Ride
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.weekKindLabels
import de.trailscape.core.weekKm

/**
 * Titelzeile plus eine Karte je Trainingswoche.
 *
 * Port von `_buildPlanWeeks` (`lib/screens/training_screen.dart`). Bewusst
 * ohne `_EntranceFade`-Aequivalent: die Wochenliste steht in derselben
 * `LazyColumn` wie die uebrigen Karten von [de.trailscape.app.ui.training.TrainingScreen]
 * und wuerde beim Scrollen recycelt — dieselbe Begruendung, mit der
 * `ui/rides/RidesScreen.kt` das gestaffelte Einblenden bereits wegliess.
 */
@Composable
fun PlanHeader(plan: TrainingPlan) {
    Text(
        text = "${plan.goal.name} – ${formatKmDe(plan.goal.distanceKm)} km am " +
            formatDate(plan.goal.date),
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * @param onPlanRoute sucht zu einer Einheit eine passende Runde (siehe
 *   `ui/map/RouteGenerationPanel.kt`). Der Knopf steht bewusst nur an den
 *   Einheiten der **laufenden** Woche: Fuer eine Einheit in vier Wochen ist
 *   eine heute berechnete Runde wertlos, und die Zeile bliebe ueberladen.
 */
@Composable
fun PlanWeekCard(
    week: TrainingWeek,
    plan: TrainingPlan,
    rides: List<Ride>,
    onPlanRoute: ((TrainingSession) -> Unit)? = null,
) {
    val theme = MaterialTheme.colorScheme
    val activeIndex = currentWeekIndex(plan)
    val isCurrent = week.index == activeIndex
    val isPastOrCurrent = week.index <= activeIndex
    val ridden = if (isPastOrCurrent) weekKm(week, rides) else 0.0
    val progress = if (week.targetKm > 0) (ridden / week.targetKm).toFloat().coerceIn(0f, 1f) else 0f
    val kindColor = weekKindColor(week.kind)

    Card(
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = theme.primaryContainer.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Woche ${week.index + 1} · " +
                        "${formatDateShort(week.start)}–${formatDateShort(week.end)}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = weekKindLabels.getValue(week.kind),
                    color = kindColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .wrapContentWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(kindColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isPastOrCurrent) {
                    "${formatKmDe(ridden)} von ${week.targetKm} km"
                } else {
                    "Ziel: ${week.targetKm} km"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Kopfzeile (Tag, Titel, Distanz, Planungs-Knopf) und darunter die
            // Beschreibung in voller Breite. Titel und Beschreibung duerfen
            // NICHT in einer Zeile stehen: die Beschreibung bekaeme nur die
            // Restbreite neben Distanz und Knopf und braeche pro Wort um.
            for (session in week.sessions) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    WeekdayLabel(session.day)
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${session.targetKm} km",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (isCurrent && onPlanRoute != null) {
                                IconButton(
                                    onClick = { onPlanRoute(session) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Route,
                                        contentDescription = "Passende Route für „${session.title}“ planen",
                                        tint = theme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            text = session.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
