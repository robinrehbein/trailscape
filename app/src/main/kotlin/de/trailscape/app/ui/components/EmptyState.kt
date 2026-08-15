package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding

/**
 * # Der Leerzustand, den alle Tabs teilen
 *
 * Ein leerer Bildschirm ist die erste Seite, die ein fremder Nutzer von einem
 * Tab sieht — und die einzige Gelegenheit zu erklaeren, was dort spaeter steht
 * und wie er dorthin kommt. Vorher tat das nur der Touren-Tab, und der mit
 * zwei nackten Textzeilen ohne Aktion.
 *
 * Aufbau, bewusst immer gleich:
 *  1. **Titel** (`titleMedium`) — was dieser Bereich kann.
 *  2. **Zwei bis drei Saetze** (`bodyMedium`, gedaempft) — wie es funktioniert
 *     und was es dafuer braucht. Keine Werbung, nur Bedienwissen.
 *  3. **Aktionen** — der kuerzeste Weg zu echten Daten, als Knoepfe. Der
 *     wichtigste zuerst und ausgefuellt (contained), die uebrigen als
 *     flat (`TextButton`) oder helle Contained-Variante (`NeutralButton`) —
 *     One UI kennt keine Outline-Knoepfe.
 *
 * Die Karte erbt das One-UI-Kartenbild aus dem Theme (Kartenfarbe und 26-dp-
 * Rundung, kein Rand, kein Schatten) wie jede andere Karte der App; ein
 * Leerzustand ist ein normaler Inhalt, kein Fehlerfall.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions()
            }

            if (hint != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
