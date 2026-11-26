package de.trailscape.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * # Atlas-Formen
 *
 * Weiche, ruhige Rundungen fuer Planung und Uebersichten: Karten liegen bei
 * 20 dp, Knoepfe und Chips bleiben volle Pillen (`small`), Dialoge 24 dp.
 * Fuer die Fahrbildschirme liefert [CockpitTheme] kantigere Formen.
 */
val TrailscapeShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(50),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
