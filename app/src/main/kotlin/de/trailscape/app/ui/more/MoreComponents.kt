package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.theme.CardPadding

/**
 * Gemeinsame Bausteine der Mehr-Karten — Port der wiederkehrenden
 * Card-Struktur aus `lib/screens/more_screen.dart`
 * (`Card(Padding(Column(...)))`).
 *
 * Den Hinweisblock (`_notice`) gab es hier und im Trainings-Paket doppelt; er
 * steht jetzt einmal in `ui/components/NoticeBox.kt`.
 */

/**
 * Eine Themenkarte im One-UI-Settings-Muster: `Card` ohne eigene Farbe (erbt
 * die Kartenflaeche und die 26-dp-Rundung aus dem Theme), Titel in
 * `titleMedium` (durch das Theme fett), [CardPadding] als Innenabstand und
 * 12 dp Abstand zum Inhalt.
 */
@Composable
fun MoreSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.size(12.dp))
            content()
        }
    }
}
