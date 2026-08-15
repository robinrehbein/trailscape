package de.trailscape.app.ui.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.theme.LocalSignalColors

/**
 * Kleine, ueberall im Trainings-Tab wiederverwendete Bausteine — Port von
 * `_figure` und `_signalRow` aus `lib/screens/training_screen.dart`.
 *
 * Der frueher hier ebenfalls definierte Hinweisblock (`_notice`) steht jetzt
 * als gemeinsame Fassung in `ui/components/NoticeBox.kt` — es gab ihn vorher
 * zweimal, hier und im Mehr-Paket.
 */

/**
 * Beschriftete Kennzahl — dieselbe Grammatik wie in der ganzen App ([Fact]):
 * Label ueber der Zahl, die Zahl gross und fett aus dem Slot. Eine Farbe ist
 * optional (z. B. Ampelton der Zeile darunter).
 */
@Composable
fun FigureText(value: String, label: String, color: Color? = null) {
    Fact(label = label, value = value, valueColor = color ?: Color.Unspecified)
}

/**
 * Beschriftete Kennzahl in Fliesstext — Dart: `_metric`. Die Zahl traegt das
 * Gewicht (700) aus dem titleSmall-Slot, das Label bleibt Fliesstext.
 */
@Composable
fun InlineMetric(value: String, label: String) {
    Row {
        Text(text = value, style = MaterialTheme.typography.titleSmall)
        Text(text = " $label", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Zeile eines Erholungssignals: Ampelpunkt statt Icon.
 *
 * Bewusst anders als das Dart-Original (dort ein themenspezifisches Icon je
 * Signal, z. B. `Icons.monitor_heart_outlined`) — und bewusst unveraendert
 * seit `material-icons-extended` eingebunden ist: Ein farbiger Ampelpunkt
 * transportiert dieselbe Information (Grün/Gelb/Orange/Rot) ohne den Umweg
 * über ein Icon je Signal und passt zur Ampel-Metapher des
 * Readiness-Systems (§5). Diese Zeile bleibt deshalb Gestaltung, nicht
 * Verlegenheitsloesung — anders als die zweckentfremdeten bzw. selbst
 * gezeichneten Icons, die mit dem vollen Icon-Satz durch echte Symbole
 * ersetzt wurden.
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
            // Der Messwert ist die Hauptzahl der Zeile — deshalb derselbe
            // fette headlineSmall-Slot wie bei [FigureText], nicht eine
            // blosse Titelzeile.
            Text(text = headline, style = MaterialTheme.typography.headlineSmall, color = color)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Kleine, feste Spalte fuer den Wochentag-Kuerzel einer Trainingseinheit.
 *
 * Farbe und Schriftrolle kommen aus dem Theme: vorher stand hier ein
 * `Color(0xFF4CAF50)`-Literal (das dritte Exemplar desselben Werts in der App)
 * und gar keine Typografie-Angabe, wodurch das Kuerzel in `bodyLarge` neben
 * `bodyMedium`-Text stand.
 */
@Composable
fun RowScope.WeekdayLabel(day: String) {
    Text(
        text = day,
        style = MaterialTheme.typography.titleSmall,
        color = LocalSignalColors.current.accentGreen,
        modifier = Modifier
            .width(32.dp)
            .align(Alignment.Top),
    )
}
