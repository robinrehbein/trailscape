package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding

/**
 * Die Zusammenfassung unter dem Verlauf als Karte (Fuehrung „Klartext",
 * `docs/design/prototyp-klartext.html`, Verlauf → Karte): wie viele Touren,
 * wie viele Kilometer, wie viele Kacheln entdeckt, das groesste Quadrat.
 * ✕ fuehrt zurueck in den Verlauf.
 */
@Composable
internal fun HistorySummarySheet(
    rideCount: Int,
    totalKm: Double,
    tileCount: Int,
    squareSize: Int?,
    onClose: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    StaticSheet(
        modifier = modifier,
        bottomInset = bottomInset,
    ) {
        Column(
            modifier = Modifier.padding(start = CardPadding, end = CardPadding, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wo du überall warst", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Grau = noch nie gefahren",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Zurück zum Verlauf")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                SummaryValue("$rideCount", "Touren", Modifier.weight(1f))
                SummaryValue("${formatKmDe(totalKm).substringBefore(',')} km", "gefahren", Modifier.weight(1f))
                SummaryValue("$tileCount", "Kacheln", Modifier.weight(1f))
                SummaryValue(
                    squareSize?.takeIf { it >= 2 }?.let { "$it×$it" } ?: "–",
                    "größtes Quadrat",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SummaryValue(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
