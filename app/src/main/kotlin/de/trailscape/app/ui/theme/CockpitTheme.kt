package de.trailscape.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * # Cockpit-Farben fuer die Fahrbildschirme (drinnen und draussen)
 *
 * Tiefdunkles, kontraststarkes Schema im Radcomputer-Stil, das unabhaengig vom
 * System-Hell-/Dunkelmodus immer dunkel bleibt: draussen bei direkter Sonne
 * und drinnen ueber der 3D-Welt muessen Zahlen aus einem Meter Abstand sofort
 * lesbar sein.
 */
@Immutable
data class CockpitColors(
    /** Tiefer Grund hinter Kacheln (Outdoor) bzw. ohne 3D-Welt. */
    val background: Color,
    /** Kantige Datenkachel. */
    val panel: Color,
    /** Halbtransparente HUD-Leiste ueber der 3D-Welt. */
    val hudBar: Color,
    /** Feine 1-dp-Rahmenlinie zwischen Kacheln. */
    val border: Color,
    /** Primaere Zahlen und Titel. */
    val ink: Color,
    /** Einheiten, Bezeichnungen und Nebentexte. */
    val inkMuted: Color,
    /** Fokussierte Aktion im Cockpit (z. B. Start/Weiter). */
    val action: Color,
    /** Schrift/Symbol auf [action]. */
    val onAction: Color,
    /** Gruen im Zielbereich (ERG-Leistung, guter Abstand). */
    val good: Color,
    /** Gelb/Bernstein als leichter Hinweis. */
    val caution: Color,
    /** Orange bei Abweichung oder starker Steigung. */
    val warning: Color,
    /** Rot fuer steile Anstiege oder Beenden. */
    val danger: Color,
)

internal val DefaultCockpitColors = CockpitColors(
    background = Color(0xFF0A0D11),
    panel = Color(0xFF141920),
    hudBar = Color(0xD60E1116),
    border = Color(0xFF262E3B),
    ink = Color(0xFFFFFFFF),
    inkMuted = Color(0xFFA0ACC0),
    action = Color(0xFFFF6B2C),
    onAction = Color(0xFF140600),
    good = Color(0xFF4ADE80),
    caution = Color(0xFFFBBF24),
    warning = Color(0xFFFB923C),
    danger = Color(0xFFF87171),
)

/** Aktuell gueltige Cockpit-Farben; gestellt von [CockpitTheme]. */
val LocalCockpitColors = staticCompositionLocalOf { DefaultCockpitColors }

/** Kantige Radcomputer-Formen fuer das Cockpit. */
val CockpitShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

/** Typografie fuer das Cockpit: grosse, tabellarische Ziffern aus 1 m lesbar. */
val CockpitTypography: Typography = TrailscapeTypography.copy(
    displayLarge = TrailscapeTypography.displayLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W700,
        fontSize = 68.sp,
        lineHeight = 74.sp,
        letterSpacing = (-0.8).sp,
    ).tabular(),
    displayMedium = TrailscapeTypography.displayMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W700,
        fontSize = 48.sp,
        lineHeight = 54.sp,
        letterSpacing = (-0.5).sp,
    ).tabular(),
    displaySmall = TrailscapeTypography.displaySmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W700,
        fontSize = 38.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.3).sp,
    ).tabular(),
    headlineLarge = TrailscapeTypography.headlineLarge.tabular(),
    headlineMedium = TrailscapeTypography.headlineMedium.tabular(),
    headlineSmall = TrailscapeTypography.headlineSmall.tabular(),
)

private val CockpitColorScheme = darkColorScheme(
    primary = DefaultCockpitColors.good,
    onPrimary = Color(0xFF002411),
    primaryContainer = Color(0xFF155F38),
    onPrimaryContainer = Color(0xFFA7F2C6),
    secondary = Color(0xFFB0CCB9),
    onSecondary = Color(0xFF1D2A22),
    secondaryContainer = Color(0xFF232B36),
    onSecondaryContainer = DefaultCockpitColors.ink,
    error = DefaultCockpitColors.danger,
    onError = Color(0xFF410001),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFFDAD4),
    background = DefaultCockpitColors.background,
    onBackground = DefaultCockpitColors.ink,
    surface = DefaultCockpitColors.background,
    onSurface = DefaultCockpitColors.ink,
    surfaceContainerLowest = Color(0xFF06080B),
    surfaceContainerLow = DefaultCockpitColors.panel,
    surfaceContainer = Color(0xFF1A2029),
    surfaceContainerHigh = Color(0xFF232B36),
    surfaceContainerHighest = DefaultCockpitColors.panel,
    surfaceVariant = Color(0xFF1A2029),
    onSurfaceVariant = DefaultCockpitColors.inkMuted,
    outline = DefaultCockpitColors.border,
    outlineVariant = DefaultCockpitColors.border,
)

/**
 * Stellt das immer dunkle, kantige Cockpit-Design fuer die Fahrbildschirme
 * (drinnen auf der Rolle und draussen im Fahrmodus) bereit.
 */
@Composable
fun CockpitTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCockpitColors provides DefaultCockpitColors,
        LocalSignalColors provides DarkSignalColors,
    ) {
        MaterialTheme(
            colorScheme = CockpitColorScheme,
            typography = CockpitTypography,
            shapes = CockpitShapes,
            content = content,
        )
    }
}
