package de.trailscape.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.core.Ride
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingWeek
import de.trailscape.core.currentWeekIndex
import de.trailscape.core.formatKm
import de.trailscape.core.weekKindLabels
import de.trailscape.core.weekKm
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)
private val longDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)

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
        text = "${plan.goal.name} – ${formatKm(plan.goal.distanceKm)} km am " +
            longDateFormatter.format(localOfEpochMs(plan.goal.date)),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
fun PlanWeekCard(week: TrainingWeek, plan: TrainingPlan, rides: List<Ride>) {
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Woche ${week.index + 1} · " +
                        "${shortDateFormatter.format(localOfEpochMs(week.start))}–" +
                        shortDateFormatter.format(localOfEpochMs(week.end)),
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
                    "${formatKm(ridden)} von ${week.targetKm} km"
                } else {
                    "Ziel: ${week.targetKm} km"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))

            for (session in week.sessions) {
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    WeekdayLabel(session.day)
                    Column(modifier = Modifier.weight(1f)) {
                        Row {
                            Text(text = session.title, fontWeight = FontWeight.Bold)
                            Text(text = " – ${session.description}")
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "${session.targetKm} km")
                }
            }
        }
    }
}
