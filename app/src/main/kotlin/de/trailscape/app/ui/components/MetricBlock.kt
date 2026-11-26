package de.trailscape.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.trailscape.app.ui.theme.LocalCockpitColors
import de.trailscape.app.ui.theme.tabular

/**
 * Tabellarische Kennzahl fuer Atlas-Uebersichten (Wochenziel, Tourdetail,
 * Tagesempfehlung) mit optionalem Klartext-Hinweis ohne Fachwort.
 */
@Composable
fun MetricBlock(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    hint: String? = null,
) {
    val cd = listOf(label, value, unit, hint).filterNotNull().joinToString(" ")
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = cd },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.tabular(),
                color = valueColor,
                maxLines = 1,
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                )
            }
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Kantige Datenkachel fuer die Fahrbildschirme im [CockpitTheme] — aus
 * einem Meter Abstand lesbar, ohne springende Ziffern.
 */
@Composable
fun CockpitMetricTile(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalCockpitColors.current.ink,
    prominent: Boolean = false,
    verticalPadding: Dp = 12.dp,
) {
    val cockpit = LocalCockpitColors.current
    val cd = listOf(label, value, unit).filter { it.isNotEmpty() }.joinToString(" ")
    val baseStyle = (if (prominent) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall)
        .copy(color = valueColor, fontWeight = FontWeight.Bold)
        .tabular()

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = cd },
        shape = MaterialTheme.shapes.medium,
        color = cockpit.panel,
        border = BorderStroke(1.dp, cockpit.border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = verticalPadding),
            verticalArrangement = Arrangement.spacedBy(if (verticalPadding < 10.dp) 2.dp else 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = cockpit.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BasicText(
                    text = value,
                    style = baseStyle,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = if (prominent) 34.sp else 20.sp,
                        maxFontSize = baseStyle.fontSize,
                        stepSize = 2.sp,
                    ),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (unit.isNotEmpty()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleMedium,
                        color = cockpit.inkMuted,
                        modifier = Modifier.padding(bottom = if (prominent) 8.dp else 4.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
