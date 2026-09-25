package de.trailscape.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.PlanFeasibility

/**
 * Hinweis, wenn der Plan sein eigenes Ziel nicht traegt.
 *
 * ## UMZUGSKANDIDAT
 * Seit der Klartext-Fassung (`docs/design/prototyp-klartext.html`) zeigt
 * „Heute" diese Karte **nicht mehr** — ein Plan, der sein Ziel nicht einholt,
 * ist eine Auskunft ueber den Plan, nicht ueber heute, und gehoert in den
 * Trainings-Tab. Die Karte liegt nur noch hier, bis sie dorthin umzieht;
 * `TodayScreen` benutzt sie nicht.
 *
 * ## Warnung oder Auskunft?
 * Hier ist nichts akut — der Plan laeuft unveraendert weiter, er traegt nur ein
 * kuerzeres Ziel als eingetragen. Titel deshalb als Feststellung, Farbe die
 * mildere `caution`-Stufe, Icon ein schlichtes Info-Zeichen statt des
 * Warndreiecks.
 *
 * ## Zahlen statt Fliesstext
 * [PlanFeasibility] traegt die Distanzen als eigene Felder; eine kompakte
 * Zahlenzeile im Stil von [Fact] sagt dasselbe wie
 * [PlanFeasibility.message] auf einen Blick.
 *
 * ## Quittierung
 * „Verstanden" ruft [onAcknowledge] — gedacht fuer
 * `AppViewModel.acknowledgePlanFeasibility`, damit der Hinweis fuer genau
 * diesen Plan nicht bei jedem Start wiederkommt.
 */
@Composable
internal fun PlanFeasibilityCard(
    feasibility: PlanFeasibility,
    onAdjustGoal: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val cautionColor = LocalSignalColors.current.caution
    Card {
        Column(modifier = Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = cautionColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Plan und Ziel passen nicht zusammen",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Fact(
                    label = "Längste Fahrt",
                    value = "${feasibility.longestRideKm} km",
                    compact = true,
                )
                Fact(
                    label = "Ziel",
                    value = "${formatKmDe(feasibility.goalDistanceKm)} km",
                    compact = true,
                )
                feasibility.suggestedDistanceKm?.let { suggested ->
                    Fact(
                        label = "Trägt bis",
                        value = "$suggested km",
                        compact = true,
                        valueColor = cautionColor,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAdjustGoal) { Text("Ziel anpassen") }
                TextButton(onClick = onAcknowledge) { Text("Verstanden") }
            }
        }
    }
}
