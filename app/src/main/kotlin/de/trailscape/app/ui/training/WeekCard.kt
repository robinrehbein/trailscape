package de.trailscape.app.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.core.formatHours
import kotlin.math.roundToInt

/**
 * Karte „Diese Woche": Wochenlast, Zielwert (Last-Budget, Stunden) und
 * Deload-Empfehlung.
 *
 * Port von `_buildWeekCard` (`lib/screens/training_screen.dart`).
 *
 * [onOpenMore] wird nur fuer den Zeitbudget-Hinweis gebraucht: Das
 * Dart-Original nennt dort nur den „Mehr-Tab" im Text, ohne eigene Aktion.
 * Da [de.trailscape.app.ui.AppViewModel.requestTab] genau dafuer existiert
 * (und der Auftrag explizit „requestTab zu Mehr/Karte" als Beispiel nennt),
 * ist der Hinweistext hier zusaetzlich antippbar — bewusste, kleine
 * Verbesserung ueber das Original hinaus, der Text selbst bleibt identisch.
 */
@Composable
fun WeekCard(insights: TrainingInsights, onOpenMore: () -> Unit) {
    val theme = MaterialTheme.colorScheme
    val deload = insights.deload
    val target = insights.weeklyTarget
    val reference = insights.fourWeekMeanWeeklyLoad
    val weeklyHours = insights.profile.weeklyHours

    var budgetText: String? = null
    var budgetClickable = false
    if (target != null && !deload.recommended) {
        val hours = formatHours(target.estimatedHours)
        if (weeklyHours != null && weeklyHours > 0) {
            budgetText = "Zielwert entspricht ≈ $hours h Fahrzeit bei deinem Budget von " +
                "${formatHours(weeklyHours)} h pro Woche."
        } else {
            budgetText = "Zielwert entspricht ≈ $hours h Fahrzeit. Trage im Mehr-Tab dein " +
                "Zeitbudget ein, dann rechnen wir es mit ein."
            budgetClickable = true
        }
    }

    var deloadRange: String? = null
    if (deload.recommended && reference != null && reference > 0) {
        val low = (reference * (1 - deload.volumeReductionHigh)).roundToInt()
        val high = (reference * (1 - deload.volumeReductionLow)).roundToInt()
        deloadRange = "$low–$high Last statt zuletzt ${reference.roundToInt()}"
    }

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Diese Woche", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FigureText(insights.weeklyLoad.roundToInt().toString(), "Last (7 Tage)")
                if (reference != null) {
                    FigureText(reference.roundToInt().toString(), "ø Woche (4 Wochen)")
                }
                if (target != null && !deload.recommended) {
                    FigureText(target.weeklyLoad.roundToInt().toString(), "Zielwert", color = trainingGood)
                    FigureText(
                        "${formatHours(target.estimatedHours)} h",
                        if (weeklyHours != null && weeklyHours > 0) {
                            "Fahrzeit (Budget ${formatHours(weeklyHours)} h)"
                        } else {
                            "Fahrzeit (geschätzt)"
                        },
                    )
                }
            }

            if (budgetText != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = budgetText,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = if (budgetClickable) {
                        Modifier.clickable(onClick = onOpenMore)
                    } else {
                        Modifier
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            NoticeBox(
                icon = if (deload.recommended) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                color = if (deload.recommended) trainingWarning else trainingGood,
                title = deload.title,
                text = if (deloadRange != null) "${deload.detail} Richtwert: $deloadRange." else deload.detail,
            )

            for (trigger in deload.triggers) {
                Text(
                    text = "· $trigger",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            for (warning in deload.warnings) {
                Text(
                    text = "· $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (target != null && !deload.recommended) {
                for (cap in target.caps) {
                    Text(
                        text = "· $cap",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
