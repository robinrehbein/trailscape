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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.theme.CardPadding

/**
 * Die Zusammenfassung unter dem Verlauf als Karte (Fuehrung „Klartext",
 * `docs/design/prototyp-klartext.html`, Verlauf → Karte): wie viele Touren,
 * wie viele Kilometer, wie viele Kacheln entdeckt, das groesste Quadrat.
 * Darunter die „Groesste Flaeche (Max-Cluster)" ([clusterSize], siehe
 * `largestCluster` in `:core`) als eigene Zeile: Eine fuenfte Spalte waere
 * auf schmalen Geraeten zu gedraengt, und der Satz „Größte Fläche: 37
 * Kacheln" braucht keine Erklaerung. `null` = noch nicht gerechnet, dann
 * fehlt die Zeile, statt kurz eine falsche 0 zu zeigen.
 * ✕ fuehrt zurueck in den Verlauf.
 */
@Composable
internal fun HistorySummarySheet(
    rideCount: Int,
    totalKm: Double,
    tileCount: Int,
    squareSize: Int?,
    clusterSize: Int?,
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
                    Text(stringResource(R.string.rides_summary_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.rides_summary_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rides_summary_close_cd))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                // Die Einheiten unter den Zahlen sind Plurals: „1 Tour", „1 Kachel".
                SummaryValue(
                    "$rideCount",
                    pluralStringResource(R.plurals.rides_summary_rides_label_count, rideCount),
                    Modifier.weight(1f),
                )
                SummaryValue(
                    // Ganze Kilometer wie bisher: auf eine Stelle gerundet, dann die
                    // Nachkommastelle abgeschnitten (2345,6 → 2345). Beide Sprachen
                    // schreiben ohne Tausendertrennzeichen, also genuegt ',' / '.'.
                    stringResource(
                        R.string.common_value_km,
                        LocalAppFormats.current.km(totalKm).substringBefore(',').substringBefore('.'),
                    ),
                    stringResource(R.string.rides_summary_ridden_label),
                    Modifier.weight(1f),
                )
                SummaryValue(
                    "$tileCount",
                    pluralStringResource(R.plurals.rides_summary_tiles_label_count, tileCount),
                    Modifier.weight(1f),
                )
                SummaryValue(
                    squareSize?.takeIf { it >= 2 }?.let { "$it×$it" } ?: "–",
                    stringResource(R.string.rides_summary_square_label),
                    Modifier.weight(1f),
                )
            }
            // Ohne Cluster (noch keine Kachel mit allen vier Nachbarn) keine
            // Zeile „0 Kacheln" — eine Null ohne Erklaerung wirkt wie ein Fehler.
            if (clusterSize != null && clusterSize > 0) {
                Text(
                    stringResource(R.string.rides_summary_cluster_status, formatTileCount(clusterSize).asString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** „1 Kachel" / „37 Kacheln" — Einzahl sauber statt „1 Kacheln" (Plural-Ressource). */
internal fun formatTileCount(count: Int): UiText = UiText.Plural(R.plurals.rides_summary_tile_count, count)

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
