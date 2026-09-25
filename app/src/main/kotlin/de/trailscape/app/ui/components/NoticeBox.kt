package de.trailscape.app.ui.components

import de.trailscape.app.ui.theme.CardPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Farbig hinterlegter Hinweisblock — Ampelmeldung, Empfehlung, Warnung.
 *
 * Port von `_notice()` aus `lib/screens/training_screen.dart` bzw.
 * `lib/screens/more_screen.dart` (dort mit `color.withValues(alpha: 0.12)`).
 *
 * Es gab davon zwei Fassungen: eine im Trainings- und eine im Mehr-Paket, mit
 * unterschiedlichem Aufbau (`clip`+`background` gegen `Surface`), einmal mit
 * und einmal ohne Titelzeile und mit unterschiedlichem Umbruchverhalten des
 * Texts. Dies ist die eine gemeinsame Fassung: `Surface` fuer die getoente
 * Flaeche, optionaler Titel in der Signalfarbe, Text in `bodyMedium` und mit
 * `weight(1f)`, damit lange Hinweise umbrechen statt das Icon zu verdraengen.
 *
 * One-UI-Fassung: flache tonale Flaeche der Signalfarbe (12 % Alpha, wie ein
 * `error`/`secondary`-Container) ohne Schatten und ohne Rand; die Rundung
 * erbt der Block aus dem Theme (`shapes.medium`, 26 dp) statt aus einer
 * eigenen Zahl.
 *
 * @param action optionale Aktion (ein `TextButton`) unter dem Text,
 *   rechtsbuendig. Ein Hinweis mit Weg zur Loesung zeigt diesen Weg sichtbar —
 *   eine nur antippbare Flaeche waere eine versteckte Funktion.
 * @param color die Signalfarbe. Kommt aus
 *   [de.trailscape.app.ui.theme.LocalSignalColors] oder dem `colorScheme` —
 *   nie als Literal.
 */
@Composable
fun NoticeBox(
    icon: ImageVector,
    color: Color,
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Derselbe Innenabstand wie jede Karte ([CardPadding]) — ein Hinweis
        // ist ein Kartenobjekt und sitzt buendig mit dem Text darueber.
        Row(modifier = Modifier.padding(CardPadding)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                    )
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        action()
                    }
                }
            }
        }
    }
}
