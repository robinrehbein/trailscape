package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = color,
                    )
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
