package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Zentrierter Hinweistext fuer die noch nicht umgesetzten Tabs.
 *
 * Liegt in einem eigenen Paket, weil die Platzhalter-Dateien
 * (`ui/map/MapScreen.kt`, `ui/training/TrainingScreen.kt`,
 * `ui/more/MoreScreen.kt`) von den Parallel-Agenten komplett ersetzt werden —
 * ein hier gemeinsam genutzter Baustein ueberlebt das. Wird nach Welle B
 * niemand mehr aufrufen und kann dann entfallen.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
