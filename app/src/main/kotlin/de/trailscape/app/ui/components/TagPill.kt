package de.trailscape.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * # Die eine Pille fuer Informationsmarken
 *
 * „geplante Route", „aus Health Connect", Fitnesslevel, Wochentyp: Marken,
 * die etwas *einordnen*, statt etwas zu tun. Als getoente Pille aus dem
 * small-Formen-Slot mit titleSmall-Beschriftung — und ausdruecklich kein
 * Knopf: Ein deaktiviertes Bedienelement als Etikett waere ein falsches
 * Versprechen (und spricht bei 38 % Alpha fuer niemanden mehr lesbar).
 *
 * Default ist die neutrale Tonflaeche des Schemas; signalgefaerbte Marken
 * uebergeben ihre Vollfarbe als [contentColor] und ihre 15-%-Toenung als
 * [containerColor].
 */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = contentColor,
        maxLines = 1,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
