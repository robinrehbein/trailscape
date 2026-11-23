package de.trailscape.app.ui.indoor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.theme.CardPadding

/**
 * Die Einstiegskarte auf „Heute": fuehrt in den Indoor-Ablauf. Hat der Tag
 * eine Einheit, die sich fuer die Rolle eignet, steht ihr Titel in der Karte
 * ([ergTitle]); sonst ein Satz, was das Indoor-Training ist.
 *
 * Gleiches Kartenbild wie die Ziel-Karte in `TodayCards.kt`: Titel, gedaempfte
 * Zeile, Chevron rechts als Zeichen „fuehrt woandershin".
 */
@Composable
fun IndoorEntryCard(ergTitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(R.string.indoor_app_entry_action),
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(modifier = Modifier.padding(CardPadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.DirectionsBike,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.indoor_app_entry_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (ergTitle != null) {
                        stringResource(R.string.indoor_app_entry_body_erg, ergTitle)
                    } else {
                        stringResource(R.string.indoor_app_entry_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
