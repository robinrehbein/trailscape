package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.core.indoor.world.IndoorGraphics

/**
 * Karte „Grafikstufe“ der Indoor-Einstellungen (Abschnitt 3D-Ansicht):
 * Standard (Vorgabe, wie bisher), Hoch, Ultra — siehe `RenderTiers`.
 */
@Composable
internal fun GraphicsCard(selected: IndoorGraphics, onChange: (IndoorGraphics) -> Unit) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Eyebrow(stringResource(R.string.render2_graphics_title), modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                stringResource(R.string.render2_graphics_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                for (g in IndoorGraphics.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = g == selected, role = Role.RadioButton, onClick = { onChange(g) })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = g == selected, onClick = null)
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(stringResource(graphicsLabel(g)), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(graphicsBody(g)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun graphicsLabel(g: IndoorGraphics): Int = when (g) {
    IndoorGraphics.STANDARD -> R.string.render2_graphics_standard
    IndoorGraphics.HIGH -> R.string.render2_graphics_high
    IndoorGraphics.ULTRA -> R.string.render2_graphics_ultra
}

private fun graphicsBody(g: IndoorGraphics): Int = when (g) {
    IndoorGraphics.STANDARD -> R.string.render2_graphics_standard_body
    IndoorGraphics.HIGH -> R.string.render2_graphics_high_body
    IndoorGraphics.ULTRA -> R.string.render2_graphics_ultra_body
}
