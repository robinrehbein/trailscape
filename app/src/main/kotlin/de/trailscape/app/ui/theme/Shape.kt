package de.trailscape.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * # One-UI-Formen
 *
 * One UI ist an seinen Rundungen zu erkennen: Karten und Bloecke sind mit
 * 26 dp stark gerundet, Knoepfe und Chips sind volle Pillen, Dialoge 28 dp.
 * Die Zuordnung geschieht ueber die Material-3-Slots — jede Komponente der
 * App erbt sie, ohne dass ein Screen eine eigene Zahl mitbringt:
 *
 * | Slot | Mass | Komponenten |
 * |---|---|---|
 * | [Shapes.small] | Pille (50 %) | Buttons, Chips, SegmentedButtons |
 * | [Shapes.medium] | 26 dp | Card, FAB, Assist-Chip-Flaechen |
 * | [Shapes.extraSmall] | 18 dp | Menues, Textfelder |
 * | [Shapes.large] | 26 dp | Schwebende Bloecke |
 * | [Shapes.extraLarge] | 28 dp | Dialoge |
 *
 * Bottom-Sheets nehmen ihre 28-dp-Deckel aus `BottomSheetDefaults` mit —
 * sie stehen damit konsistent neben den Dialogen.
 *
 * ## Regel fuer Screens
 * Keine hardcodede `RoundedCornerShape(n.dp)` mehr fuer Karten oder Knoepfe:
 * entweder erbt die Komponente den Slot, oder sie greift explizit auf
 * `MaterialTheme.shapes.…` zurueck. Eigene Formen bleiben fuer echte
 * Ausnahmen vorbehalten (Ampelpunkte, Fortschrittsanzeigen, Highlight-Rahmen).
 */
val TrailscapeShapes = Shapes(
    extraSmall = RoundedCornerShape(18.dp),
    small = RoundedCornerShape(50),
    medium = RoundedCornerShape(26.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
