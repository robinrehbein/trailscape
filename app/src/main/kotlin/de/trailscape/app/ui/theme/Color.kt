package de.trailscape.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * # Atlas-Farbwelt (Mischung Atlas + Cockpit)
 *
 * Ruhige, warme Steinweiss-Flaechen fuer Planung, Uebersichten und Streckenwahl;
 * im Dunkelmodus ein neutrales, tiefes Schiefergrau. Das Markengruen von
 * Trailscape (`primary`) bleibt fuer Fortschritt, Karten und Routenlinien
 * erhalten; genau **eine** Hauptaktion pro Bildschirm traegt das satte
 * Trail-Orange aus [AtlasColors.action].
 *
 * Die Tonal-Slots fuer Karten (`surfaceContainerHighest` und
 * `surfaceContainerLow`) bleiben im Hellmodus reinweiss auf warmgrauem Grund
 * (`surface` = #F6F4EF), im Dunkeln eine Stufe ueber dem Grund (#1B1E23).
 */
val TrailscapeSeed = Color(0xFF1B6B46)

// ------------------------------------------------------------------ Hell
internal val LightPrimary = Color(0xFF1B6B46)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD2EFE0)
internal val LightOnPrimaryContainer = Color(0xFF062617)
internal val LightSecondary = Color(0xFF4B6357)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFD2E7DB)
internal val LightOnSecondaryContainer = Color(0xFF0B1F16)
internal val LightTertiary = Color(0xFF2A6474)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFC4EAF8)
internal val LightOnTertiaryContainer = Color(0xFF04202B)
internal val LightError = Color(0xFFBB2016)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFFDAD4)
internal val LightOnErrorContainer = Color(0xFF410001)
internal val LightBackground = Color(0xFFF6F4EF)
internal val LightOnBackground = Color(0xFF181A1F)
internal val LightSurface = Color(0xFFF6F4EF)
internal val LightOnSurface = Color(0xFF181A1F)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)

/** Flaeche der schwebenden Panels des Karten-Tabs — dieselbe wie die Karte. */
internal val LightSurfaceContainerLow = Color(0xFFFFFFFF)
internal val LightSurfaceContainer = Color(0xFFEEECE5)
internal val LightSurfaceContainerHigh = Color(0xFFE6E3DA)

/** **Die Karte.** Material 3 nimmt diesen Slot fuer das gefuellte `Card`. */
internal val LightSurfaceContainerHighest = Color(0xFFFFFFFF)
internal val LightSurfaceVariant = Color(0xFFECE9E1)
internal val LightOnSurfaceVariant = Color(0xFF575B64)
internal val LightOutline = Color(0xFF767980)
internal val LightOutlineVariant = Color(0xFFE4E0D6)
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
internal val DarkBackground = Color(0xFF111316)
internal val DarkOnBackground = Color(0xFFE8E9ED)
internal val DarkSurface = Color(0xFF111316)
internal val DarkOnSurface = Color(0xFFE8E9ED)
internal val DarkSurfaceContainerLowest = Color(0xFF0A0C0F)

/** Flaeche der schwebenden Panels des Karten-Tabs — dieselbe wie die Karte. */
internal val DarkSurfaceContainerLow = Color(0xFF1B1E23)
internal val DarkSurfaceContainer = Color(0xFF23272E)
internal val DarkSurfaceContainerHigh = Color(0xFF2C313A)

/** **Die Karte.** Material 3 nimmt diesen Slot fuer das gefuellte `Card`. */
internal val DarkSurfaceContainerHighest = Color(0xFF1B1E23)
internal val DarkSurfaceVariant = Color(0xFF21252C)
internal val DarkOnSurfaceVariant = Color(0xFFB3B8C2)
internal val DarkOutline = Color(0xFF8A8F98)
internal val DarkOutlineVariant = Color(0xFF2C3038)
internal val DarkInverseSurface = Color(0xFFE8E9ED)
internal val DarkInverseOnSurface = Color(0xFF2E3033)
internal val DarkInversePrimary = Color(0xFF1B6B46)

/**
 * Zusatzfarben fuer den Atlas-Stil, die bewusst nicht ueber `colorScheme.primary`
 * laufen: genau die eine Hauptaktion traegt [action] (Trail-Orange), waehrend
 * Fortschrittsbalken, Routenlinien und Karten weiter [LightPrimary]/[DarkPrimary]
 * nutzen.
 */
@Immutable
data class AtlasColors(
    /** Fuellfarbe des einen orangefarbenen Hauptknopfs (WCAG AA mit [onAction]). */
    val action: Color,
    /** Text- und Symbolfarbe auf [action]. */
    val onAction: Color,
    /** Sanfte Hervorhebung fuer sekundaere Aktions-Chips oder ausgewaehlte Flaechen. */
    val actionContainer: Color,
    /** Text- und Symbolfarbe auf [actionContainer]. */
    val onActionContainer: Color,
    /** Feine 1-dp-Kartenkante im Atlas-Stil. */
    val cardBorder: Color,
    /** Halbtransparenter Hintergrund fuer Status-Chips auf Streckenbildern. */
    val routeMediaScrim: Color,
)

internal val LightAtlasColors = AtlasColors(
    action = Color(0xFFC9460F),
    onAction = Color(0xFFFFFFFF),
    actionContainer = Color(0xFFFDE7DC),
    onActionContainer = Color(0xFF4A1700),
    cardBorder = LightOutlineVariant,
    routeMediaScrim = Color(0x99121417),
)

internal val DarkAtlasColors = AtlasColors(
    action = Color(0xFFF27038),
    onAction = Color(0xFF140600),
    actionContainer = Color(0xFF4A1D0A),
    onActionContainer = Color(0xFFFFD5C2),
    cardBorder = DarkOutlineVariant,
    routeMediaScrim = Color(0xB30A0D11),
)

/** Die aktuell gueltigen Atlas-Sonderfarben; gestellt von [TrailscapeTheme]. */
val LocalAtlasColors = staticCompositionLocalOf { LightAtlasColors }
