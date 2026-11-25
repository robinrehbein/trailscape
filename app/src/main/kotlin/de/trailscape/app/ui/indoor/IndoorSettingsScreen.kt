package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.more.SettingsNavRow
import de.trailscape.app.ui.more.SettingsSwitchRow
import de.trailscape.core.indoor.VirtualGearTable
import de.trailscape.core.indoor.sky.IndoorTimeOfDay

/**
 * # Nebenseite „Einstellungen" des Indoor-Trainings (von der Startseite)
 *
 * Alles, was man einmal einstellt und dann vergisst, und alles Technische —
 * damit die Startseite nur den Weg zur Fahrt zeigt:
 *
 *  * **Fahrgefuehl:** Kettenblatt am Trainer, virtuelle Gaenge
 *    (frueher im Schritt „Koppeln").
 *  * **Hintergrund:** „Im Hintergrund weiterfahren" (docs/indoor/hintergrund.md).
 *  * **3D-Ansicht:** Tageszeit, andere Radfahrer (frueher „Koppeln").
 *  * **Erweitert:** Strecken auf dem Geraet (Weltverwaltung, frueher unter
 *    Mehr), Liste der bekannten Strecken aktualisieren (frueher in der
 *    Streckenwahl) samt Hinweis, was dabei geladen wird, und die Diagnose
 *    (frueher im Schritt „Koppeln"; auch weiter unter Mehr).
 *
 * Zustandslos; die Werte merkt das `IndoorViewModel` wie bisher.
 */
@Composable
fun IndoorSettingsScreen(
    ui: IndoorPairUi,
    catalog: IndoorCatalogUi,
    onChainringChange: (Int) -> Unit,
    onGearTableChange: (VirtualGearTable) -> Unit,
    onTimeOfDayChange: (IndoorTimeOfDay) -> Unit,
    onOtherRidersChange: (Boolean) -> Unit,
    onOpenWorlds: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    backgroundRide: Boolean = true,
    onBackgroundRideChange: (Boolean) -> Unit = {},
) {
    IndoorPage(
        title = stringResource(R.string.indoor_app_settings_title),
        onBack = onBack,
    ) {
        Eyebrow(stringResource(R.string.indoor_app_settings_section_ride))
        ChainringCard(teeth = ui.chainringTeeth, onChange = onChainringChange)
        GearTableCard(selected = ui.gearTable, onChange = onGearTableChange)

        // Fahrt im Hintergrund (docs/indoor/hintergrund.md).
        Eyebrow(stringResource(R.string.idee05_settings_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.idee05_settings_background_title),
                    subtitle = stringResource(R.string.idee05_settings_background_body),
                    checked = backgroundRide,
                    onCheckedChange = onBackgroundRideChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        Eyebrow(stringResource(R.string.indoor_app_settings_section_view))
        TimeOfDayCard(selected = ui.timeOfDay, onChange = onTimeOfDayChange)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.indoor_app_pair_other_riders_title),
                    subtitle = stringResource(R.string.indoor_app_pair_other_riders_body),
                    checked = ui.otherRiders,
                    onCheckedChange = onOtherRidersChange,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        Eyebrow(stringResource(R.string.indoor_app_settings_section_advanced))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                SettingsNavRow(
                    title = stringResource(R.string.indoor_app_worlds_title),
                    status = stringResource(R.string.indoor_app_settings_worlds_body),
                    onClick = onOpenWorlds,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.indoor_app_catalog_refresh),
                    status = stringResource(R.string.indoor_app_settings_refresh_body),
                    onClick = { if (!catalog.refreshing) onRefreshCatalog() },
                )
                if (catalog.refreshing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.indoor_app_catalog_refresh),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                SettingsNavRow(
                    title = stringResource(R.string.indoor_app_settings_diagnostics_title),
                    status = stringResource(R.string.indoor_app_settings_diagnostics_body),
                    onClick = onOpenDiagnostics,
                )
            }
        }
        catalog.refreshResult?.let { MutedText(catalogRefreshText(it)) }
        MutedText(stringResource(R.string.indoor_app_catalog_privacy))
    }
}
