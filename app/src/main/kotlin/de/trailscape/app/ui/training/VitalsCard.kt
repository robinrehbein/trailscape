package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.core.confidenceLabels
import de.trailscape.core.recoveryFlagLabels
import de.trailscape.core.shortSleeperHint
import kotlin.math.roundToInt

/**
 * Karte „Vitalwerte": HRV-, Ruhepuls- und Schlafampel plus VO2max-Band.
 *
 * Port von `_buildVitalsCard` (`lib/screens/training_screen.dart`). HRV steht
 * oben — sie ist das staerkste Einzelsignal des Erholungswerts, sobald genug
 * Tage vorliegen.
 *
 * ## Messwert und Bewertung sind zweierlei
 * In der Ueberschrift steht der **zuletzt gemessene Tageswert**
 * (`HrvAssessment.lastRmssd`, `RestingHrAssessment.last`) — das ist die Zahl,
 * die jemand erwartet, der „HRV 48 ms" liest. Bewertet wird dagegen mit dem
 * 7-Tage-Rollmittel bzw. dem 3-Tage-Median; die stehen im Begruendungstext.
 * Frueher stand das Mittel oben und war als Messwert beschriftet.
 *
 * [showShortSleeperHint] setzt die Regel „hoechstens einmal im Monat" aus
 * `:core` (`shouldShowShortSleeperHint`) durch — sie war definiert, getestet
 * und nie aufgerufen. [onShortSleeperHintShown] quittiert die Anzeige.
 */
@Composable
fun VitalsCard(
    insights: TrainingInsights,
    showShortSleeperHint: Boolean = true,
    onShortSleeperHintShown: () -> Unit = {},
) {
    val theme = MaterialTheme.colorScheme
    val unknown = theme.onSurfaceVariant
    val hrv = insights.hrv
    val rhr = insights.restingHr
    val sleep = insights.sleep
    val vo2 = insights.vo2max

    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text("Vitalwerte", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            SignalRow(
                color = recoveryFlagColor(hrv.flag, unknown),
                headline = if (hrv.lastRmssd != null) {
                    "HRV ${hrv.lastRmssd!!.roundToInt()} ms" +
                        if (hrv.available) " · ${recoveryFlagLabels.getValue(hrv.flag)}" else ""
                } else {
                    "HRV"
                },
                detail = if (hrv.available) {
                    "${hrv.message} ${hrvTrendText(hrv)}"
                } else {
                    hrv.unavailableReason ?: "Keine Aussage möglich."
                },
            )
            Spacer(modifier = Modifier.height(12.dp))

            SignalRow(
                color = recoveryFlagColor(rhr.flag, unknown),
                headline = if (rhr.last != null) {
                    "Ruhepuls ${rhr.last!!.roundToInt()} bpm" +
                        if (rhr.available) " · ${recoveryFlagLabels.getValue(rhr.flag)}" else ""
                } else {
                    "Ruhepuls"
                },
                detail = if (rhr.available) rhr.message else (rhr.unavailableReason ?: "Keine Aussage möglich."),
            )
            Spacer(modifier = Modifier.height(12.dp))

            SignalRow(
                color = recoveryFlagColor(sleep.flag, unknown),
                headline = if (sleep.available && sleep.lastNightH != null) {
                    "Schlaf ${germanFixed(sleep.lastNightH!!, 1)} h · ${recoveryFlagLabels.getValue(sleep.flag)}"
                } else {
                    "Schlaf"
                },
                detail = if (sleep.available) {
                    sleep.message
                } else {
                    sleep.unavailableReason ?: "Keine Aussage möglich."
                },
            )

            if (sleep.available && sleep.shortSleeper && showShortSleeperHint) {
                Spacer(modifier = Modifier.height(12.dp))
                NoticeBox(icon = Icons.Filled.Info, color = unknown, text = shortSleeperHint)
                LaunchedEffect(Unit) { onShortSleeperHintShown() }
            }

            if (vo2.available) {
                Spacer(modifier = Modifier.height(12.dp))
                SignalRow(
                    color = unknown,
                    headline = vo2.text,
                    detail = "Geschätzt (${confidenceLabels.getValue(vo2.confidence)}) — " +
                        "ein Bereich, keine Messung.",
                )
            }
        }
    }
}
