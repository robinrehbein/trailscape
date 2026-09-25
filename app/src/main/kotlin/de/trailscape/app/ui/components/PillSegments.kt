package de.trailscape.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * # Der Pillen-Umschalter (Fuehrung „Klartext")
 *
 * Eine graue Spur, darin das gewaehlte Segment als weisse, leicht gehobene
 * Pille — wie der One-UI-Umschalter und wie im Prototyp
 * (`docs/design/prototyp-klartext.html`, `.seg`). Er ersetzt die
 * Material-Segmentleiste, die mit Haken und Umriss aus dem Rest der App
 * herausfiel.
 *
 * Ueberall derselbe: „Runde ab hier" (Untergrund), Fahr-Cockpit
 * („Daten | Karte"). Im Verlauf stand frueher „Liste | Karte"; weil
 * „Karte" dort nur den Tab wechselte, ist es jetzt ein ausgeschriebener Knopf
 * (siehe `RidesScreen`).
 */
@Composable
fun PillSegments(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        modifier = modifier.fillMaxWidth().height(SegmentTrackHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(SegmentInset).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(SegmentTrackHeight - SegmentInset * 2)
                        .clip(CircleShape)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = {
                                if (!selected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(index)
                                }
                            },
                        ),
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                    shadowElevation = if (selected) 1.dp else 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Hoehe der ganzen Spur. */
private val SegmentTrackHeight = 44.dp

/** Luft zwischen Spur und gewaehltem Segment. */
private val SegmentInset = 3.dp
