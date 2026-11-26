package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.more.SettingsSwitchRow

/** Schalter „Einblendungen auf der Strecke" auf der Indoor-Einstellungsseite (Abschnitt 3D-Ansicht). */
@Composable
internal fun TrailOverlaysCard(checked: Boolean, onChange: (Boolean) -> Unit) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            SettingsSwitchRow(
                title = stringResource(R.string.trail_settings_overlays_title),
                subtitle = stringResource(R.string.trail_settings_overlays_body),
                checked = checked,
                onCheckedChange = onChange,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
