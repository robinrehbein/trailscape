package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.core.Ride

/** Ob die Tourdetailansicht „Indoor fahren" anbietet: Aussenfahrt oder Planung mit Spur, keine Indoor-Fahrt. */
internal fun indoorFromRideOffered(ride: Ride): Boolean = !ride.indoor && ride.points.size >= 2

/**
 * # „Indoor fahren" in der Tourdetailansicht
 *
 * „Draußen gefahren → drinnen nachfahren" (docs/indoor/app.md): der Einstieg
 * aus einer gefahrenen Tour oder einer gespeicherten Planung ins
 * Indoor-Training, mit genau dieser Strecke vorausgewaehlt
 * ([de.trailscape.app.ui.AppViewModel.requestIndoorRide] →
 * [IndoorViewModel.preferRide]). Bei einer gefahrenen Tour faehrt die eigene
 * Aussenfahrt als Mitfahrer mit — der Satz darunter sagt das.
 *
 * Zweitrangig unter „Diese Tour nochmal fahren" (umrandet statt gefuellt).
 * Ist die Strecke noch nicht vorbereitet, fuehrt die Startseite des
 * Indoor-Trainings wie bei jeder anderen Wahl dorthin („Wird einmal
 * vorbereitet") — hier gibt es keinen zweiten Weg.
 */
@Composable
internal fun IndoorFromRideAction(planned: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.PedalBike,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.idee01_detail_indoor_action))
        }
        Text(
            text = stringResource(
                if (planned) R.string.idee01_detail_indoor_planned_hint else R.string.idee01_detail_indoor_ridden_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
