package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/**
 * # Die eine Grammatik fuer Kennzahlen
 *
 * Dieselbe Art Information wird in der ganzen App dieselbe Art gesetzt: ein
 * ruhiges Label (labelMedium, onSurfaceVariant) **ueber** der Zahl, die Zahl
 * gross und fett aus dem headlineSmall-Slot. Ob Startseite, Tourenliste,
 * Detailansicht, Training oder Karten-Overlay — es gibt keine zweite
 * Grammatik und keine zweite Reihenfolge mehr.
 *
 * `compact` waehlt fuer engere Overlays der Karte den einen Schritt kleineren
 * Wert-Slot (titleMedium) — dieselbe Anordnung, nur leiser.
 *
 * Wer eine Kennzahl zeigt, ruft dieses Baustein statt eigener Spalten: Das
 * Gewicht der Zahl kommt aus dem Slot, niemals aus einem daneben geschriebenen
 * `FontWeight`.
 */
@Composable
fun Fact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    valueColor: Color = Color.Unspecified,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = if (compact) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.headlineSmall
            },
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
