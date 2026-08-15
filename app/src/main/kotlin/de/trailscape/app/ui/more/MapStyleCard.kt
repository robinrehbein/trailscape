package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.mapStyleSubtitle
import de.trailscape.app.ui.mapStyles

/**
 * Kartenstil-Auswahl. Kein Vorbild in `lib/screens/more_screen.dart` — die
 * Flutter-App hatte hier keinen Umschalter, nur die Kachel-Cache-Karte (siehe
 * [OfflineMapsCard]). Katalog und Persistenz liegen im gemeinsamen
 * [AppViewModel] ([de.trailscape.app.ui.MapStyles]); diese Karte ist nur die
 * Auswahl-UI dafuer.
 */
@Composable
fun MapStyleCard(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val selected by appViewModel.mapStyle.collectAsStateWithLifecycle()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    MoreSectionCard(title = "Kartenstil", modifier = modifier) {
        Text(
            text = "Wähle den Kartenhintergrund für die Kartenansicht.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.selectableGroup()) {
            mapStyles.forEachIndexed { index, style ->
                val isSelected = style.id == selected.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { appViewModel.setMapStyle(style) },
                            role = Role.RadioButton,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = isSelected, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(text = style.label, style = MaterialTheme.typography.bodyLarge)
                        // Dieselben Erlaeuterungen wie im Bottom-Sheet des
                        // Karten-Tabs: Es ist dieselbe Liste, sie soll auch
                        // dasselbe erzaehlen.
                        mapStyleSubtitle(style.id)?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = hintColor,
                            )
                        }
                    }
                }
                if (index != mapStyles.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}
