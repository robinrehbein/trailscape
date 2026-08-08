package de.trailscape.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Kleine, ueberall im Trainings-Tab wiederverwendete Bausteine — Port von
 * `_notice`, `_figure` und `_signalRow` aus `lib/screens/training_screen.dart`.
 */

/** Farbig hinterlegter Hinweisblock (Ampel, Empfehlung, Warnung). */
@Composable
fun NoticeBox(
    icon: ImageVector,
    color: Color,
    text: String,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.padding(start = 8.dp)) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, color = color)
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Beschriftete Kennzahl (Zahl fett, Beschriftung darunter). */
@Composable
fun FigureText(value: String, label: String, color: Color? = null) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Beschriftete Kennzahl in Fliesstext (Zahl fett, Rest normal) — Dart: `_metric`. */
@Composable
fun InlineMetric(value: String, label: String) {
    Row {
        Text(text = value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(text = " $label", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Zeile eines Erholungssignals: Ampelpunkt statt Icon.
 *
 * Bewusst anders als das Dart-Original (dort ein themenspezifisches Icon je
 * Signal, z. B. `Icons.monitor_heart_outlined`): `:app` bindet nur
 * `material-icons-core` ein (kein `material-icons-extended`), das Original
 * benutzt aber ausschliesslich Outline-Icons, die dort nicht enthalten sind.
 * Ein farbiger Ampelpunkt transportiert dieselbe Information (Grün/Gelb/
 * Orange/Rot) ohne zusaetzliche Abhaengigkeit und passt zur Ampel-Metapher
 * des Readiness-Systems (§5).
 */
@Composable
fun SignalRow(color: Color, headline: String, detail: String) {
    Row {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(text = headline, style = MaterialTheme.typography.titleSmall, color = color)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kleine, feste Spalte fuer den Wochentag-Kuerzel einer Trainingseinheit. */
@Composable
fun RowScope.WeekdayLabel(day: String) {
    Text(
        text = day,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF4CAF50),
        modifier = Modifier
            .width(32.dp)
            .align(Alignment.Top),
    )
}
