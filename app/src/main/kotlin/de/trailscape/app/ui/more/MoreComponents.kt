package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
 * Gemeinsame Bausteine der Mehr-Karten — Port der wiederkehrenden
 * Card-/Hinweis-Strukturen aus `lib/screens/more_screen.dart`
 * (`Card(Padding(Column(...)))` bzw. `_notice`).
 */

/**
 * Eine Themenkarte wie im Original: `Card` mit 16dp-Innenabstand, Titel in
 * `titleMedium` und 12dp Abstand zum Inhalt.
 */
@Composable
fun MoreSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.size(12.dp))
            content()
        }
    }
}

/**
 * Farbig hinterlegter Hinweis mit Icon — Port von `_notice()` aus dem
 * Dart-Original (dort mit `color.withValues(alpha: 0.12)`).
 */
@Composable
fun NoticeBox(
    icon: ImageVector,
    color: Color,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
