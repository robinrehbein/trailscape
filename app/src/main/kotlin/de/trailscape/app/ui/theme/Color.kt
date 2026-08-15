package de.trailscape.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * # One-UI-Farbwelt
 *
 * One UI 9 stellt Inhalte auf neutrale Flaechen: ein helles, kuehles Grau als
 * Bildschirmhintergrund, weisse Karten darauf; im Dunkeln ein nahezu
 * schwarzer Grund mit angehobenen Dunkelgrau-Karten. Die Akzentfarbe bleibt
 * das Markengruen von Trailscape — aber in der One-UI-typischen Leuchtkraft
 * statt des bisherigen erdigen Tons, und wie bei Samsung konzentriert auf
 * echte Aktionen (ein gefuellter Knopf je Blick, alles andere ist neutral.
 *
 * ## Tonal-Slots (die eigentlichen Flaechen)
 * `surface*` ist der Bildschirm, `surfaceContainerLow` ist die Karte. Wer
 * eine Karte faerbt, nimmt `surfaceContainerLow` (Default von `Card`), nie
 * `surface` — sonst verschwindet sie auf dem Hintergrund.
 *
 * ## Primaerfarbe als Textfarbe
 * Das Gruen ist eine Stufe dunkler als die Flaeechen-Variante, die man naiv
 * wuerde: Als *Text* auf weisser Karte und auf dem Hintergrund muss es
 * WCAG-AA (4,5:1) bestehen — #0D7A42 schafft beide (5,4:1 / 4,9:1). Als
 * gefuellte Knopf-Flaeche bleibt es dabei lebendig genug fuer One UI.
 */
val TrailscapeSeed = Color(0xFF0D7A42)

// ------------------------------------------------------------------ Hell
internal val LightPrimary = Color(0xFF0D7A42)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFC3F0D4)
internal val LightOnPrimaryContainer = Color(0xFF002411)
internal val LightSecondary = Color(0xFF4E655B)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFD3E9DC)
internal val LightOnSecondaryContainer = Color(0xFF0C1F16)
internal val LightTertiary = Color(0xFF2A6474)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFC4EAF8)
internal val LightOnTertiaryContainer = Color(0xFF04202B)
internal val LightError = Color(0xFFBB2016)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD4)
internal val LightOnErrorContainer = Color(0xFF410001)
internal val LightBackground = Color(0xFFF4F4F6)
internal val LightOnBackground = Color(0xFF1A1B1E)
internal val LightSurface = Color(0xFFF4F4F6)
internal val LightOnSurface = Color(0xFF1A1B1E)
internal val LightSurfaceContainerLowest = Color(0xFFFBFBFD)
internal val LightSurfaceContainerLow = Color(0xFFFFFFFF)
internal val LightSurfaceContainer = Color(0xFFEFEFF2)
internal val LightSurfaceContainerHigh = Color(0xFFEAEAEF)
internal val LightSurfaceContainerHighest = Color(0xFFE4E4E9)
internal val LightSurfaceVariant = Color(0xFFEDEDF0)
internal val LightOnSurfaceVariant = Color(0xFF545B63)
internal val LightOutline = Color(0xFF767C86)
internal val LightOutlineVariant = Color(0xFFDDDEE3)
internal val LightInverseSurface = Color(0xFF2E3033)
internal val LightInverseOnSurface = Color(0xFFF2F2F4)
internal val LightInversePrimary = Color(0xFF75D9A4)

// ------------------------------------------------------------------ Dunkel
internal val DarkPrimary = Color(0xFF6BD69C)
internal val DarkOnPrimary = Color(0xFF00391D)
internal val DarkPrimaryContainer = Color(0xFF155F38)
internal val DarkOnPrimaryContainer = Color(0xFFA7F2C6)
internal val DarkSecondary = Color(0xFFB0CCB9)
internal val DarkOnSecondary = Color(0xFF1D2A22)
internal val DarkSecondaryContainer = Color(0xFF344638)
internal val DarkOnSecondaryContainer = Color(0xFFCFE7D4)
internal val DarkTertiary = Color(0xFF92CEDD)
internal val DarkOnTertiary = Color(0xFF00333E)
internal val DarkTertiaryContainer = Color(0xFF104B56)
internal val DarkOnTertiaryContainer = Color(0xFFC8EBF8)
internal val DarkError = Color(0xFFFFB4A9)
internal val DarkOnError = Color(0xFF690001)
internal val DarkErrorContainer = Color(0xFF930006)
internal val DarkOnErrorContainer = Color(0xFFFFDAD4)
internal val DarkBackground = Color(0xFF0A0A0C)
internal val DarkOnBackground = Color(0xFFE3E3E7)
internal val DarkSurface = Color(0xFF0A0A0C)
internal val DarkOnSurface = Color(0xFFE3E3E7)
internal val DarkSurfaceContainerLowest = Color(0xFF060607)
internal val DarkSurfaceContainerLow = Color(0xFF17181B)
internal val DarkSurfaceContainer = Color(0xFF1C1D21)
internal val DarkSurfaceContainerHigh = Color(0xFF232429)
internal val DarkSurfaceContainerHighest = Color(0xFF2A2B30)
internal val DarkSurfaceVariant = Color(0xFF232429)
internal val DarkOnSurfaceVariant = Color(0xFFBFC3C9)
internal val DarkOutline = Color(0xFF8A8F98)
internal val DarkOutlineVariant = Color(0xFF3A3B40)
internal val DarkInverseSurface = Color(0xFFE3E3E7)
internal val DarkInverseOnSurface = Color(0xFF2E3033)
internal val DarkInversePrimary = Color(0xFF0F8A4A)
