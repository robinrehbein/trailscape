package de.trailscape.app.ui.erfolge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.data.ErfolgeStore
import de.trailscape.app.ui.more.SettingsSection
import de.trailscape.app.ui.more.SettingsSwitchRow

/**
 * „Erfolge anzeigen" in den Einstellungen. Aus heisst: keine Karte im
 * Training-Tab, keine Abzeichen-Zeile nach der Fahrt (Tourblatt und Uhr).
 * Gerechnet und festgehalten wird dann nichts; der gespeicherte Stand bleibt
 * und ist beim Wiedereinschalten unveraendert da.
 */
@Composable
internal fun ErfolgeSettingsSection(modifier: Modifier = Modifier) {
    val progress by ErfolgeStore.shared.state.collectAsStateWithLifecycle()
    SettingsSection(label = stringResource(R.string.erfolge_settings_group), modifier = modifier) {
        SettingsSwitchRow(
            title = stringResource(R.string.erfolge_settings_show_title),
            subtitle = stringResource(R.string.erfolge_settings_show_body),
            checked = !progress.hidden,
            onCheckedChange = { ErfolgeStore.shared.setShown(it) },
        )
    }
}
