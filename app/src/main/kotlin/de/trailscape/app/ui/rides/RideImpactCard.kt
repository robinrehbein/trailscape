package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.theme.CardPadding

/**
 * „Was die Tour gebracht hat" — die Wirkung der Fahrt als kurze Liste
 * (Zeilen aus [rideImpactLines], Rechnung in `:core`/`RideImpact.kt`).
 *
 * Gleicher Satzbau wie der Klartext-Satz darueber (fetter Anfang, dann der
 * Rest), aber auf der neutralen Kartenflaeche: Die eingefaerbte Notiz bleibt
 * der eine Akzent der Seite. Jede Zeile ist schlichter Text, die
 * Bildschirmlesehilfe liest sie deshalb als ganzen Satz vor — eine eigene
 * Beschreibung der Karte braucht es nicht.
 *
 * Eigene Datei, damit `RideDetailScreen.kt` nur einen kurzen Einschub traegt.
 * Ohne Zeilen zeichnet die Karte nichts.
 */
@Composable
internal fun RideImpactCard(lines: List<RideImpactLine>) {
    if (lines.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Eyebrow("Was die Tour gebracht hat")
            for (line in lines) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.lead) }
                        append(" ")
                        append(line.body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
