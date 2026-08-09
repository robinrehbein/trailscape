package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import de.trailscape.core.LoadRatioBand
import de.trailscape.core.classifyLoadRatio
import de.trailscape.core.classifyRampRate
import de.trailscape.core.classifyTsb
import de.trailscape.core.loadRatioLabels
import de.trailscape.core.rampBandLabels
import de.trailscape.core.tsbBandLabels
import de.trailscape.core.tsbBandMessages
import kotlin.math.roundToInt

/**
 * Karte „Form": PMC-Sparkline (CTL/ATL), TSB, Rampenrate und Belastungs-
 * verhaeltnis.
 *
 * Port von `_buildFormCard` (`lib/screens/training_screen.dart`).
 */
@Composable
fun FormCard(insights: TrainingInsights) {
    val theme = MaterialTheme.colorScheme
    val series = insights.fitness
    val latest = series.latest

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Form", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            if (latest == null) {
                Text(
                    text = "Sobald die erste Tour ausgewertet ist, entsteht hier deine " +
                        "Fitness-Kurve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
                return@Card
            }

            val tsbBand = classifyTsb(latest.tsb)
            val ramp = latest.rampRate7d
            val rampBand = ramp?.let { classifyRampRate(it) }
            val ratioBand = classifyLoadRatio(latest.loadRatio)
            val window = series.lastDays(60)

            if (!series.displayReady) {
                NoticeBox(
                    icon = Icons.Filled.Info,
                    color = theme.onSurfaceVariant,
                    text = "Kurve wird aufgebaut (noch " +
                        "${series.daysUntilDisplayReady} " +
                        "${if (series.daysUntilDisplayReady == 1) "Tag" else "Tage"}).",
                )
            } else {
                PmcSparkline(
                    ctl = window.map { it.ctl },
                    atl = window.map { it.atl },
                    ctlColor = trainingGood,
                    atlColor = trainingWarning,
                    gridColor = theme.outlineVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Letzte ${window.size} " +
                        "${if (window.size == 1) "Tag" else "Tage"} · " +
                        "grün: Fitness, orange: Ermüdung",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FigureText(latest.ctl.roundToInt().toString(), "Fitness (CTL)", color = trainingGood)
                FigureText(latest.atl.roundToInt().toString(), "Ermüdung (ATL)", color = trainingWarning)
                FigureText(formatSigned(latest.tsb), "Form (TSB)")
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "${tsbBandLabels.getValue(tsbBand)} — ${tsbBandMessages.getValue(tsbBand)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (ramp == null || rampBand == null) {
                    "Rampenrate: noch keine Aussage möglich (weniger als " +
                        "7 Tage Historie)."
                } else {
                    "Rampenrate: ${formatSigned(ramp)} CTL-Punkte pro Woche — " +
                        "${rampBandLabels.getValue(rampBand)}."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            if (ratioBand == LoadRatioBand.BELASTUNGSSPRUNG) {
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBox(
                    icon = Icons.Filled.Warning,
                    color = trainingWarning,
                    text = "Belastungssprung: dein Verhältnis von akuter zu " +
                        "gewohnter Belastung liegt bei " +
                        "${germanFixed(latest.loadRatio!!, 2)} " +
                        "— außerhalb des Bandes 0,8–1,5.",
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Belastungsverhältnis: ${loadRatioLabels.getValue(ratioBand)}" +
                        (latest.loadRatio?.let { " (${germanFixed(it, 2)})" } ?: "") +
                        ".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
