package de.trailscape.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * # Die Toene der schwebenden Navigationsleiste
 *
 * One UI 8.5/9 setzt die Hauptnavigation nicht mehr als durchgehende Leiste an
 * den Bildschirmrand, sondern als **schwebende Kapsel** darueber (so wie
 * Samsung Health, Galerie oder Kontakte). Diese Kapsel braucht drei Toene, die
 * `MaterialTheme.colorScheme` nicht sinnvoll hergibt:
 *
 *  * Sie liegt **ueber** den Karten, muss sich also von Karte *und* Hintergrund
 *    absetzen. `surfaceContainerLow` (die Karte) waere im Dunkeln zu nah am
 *    Grund, `surfaceContainerHigh` im Hellen zu nah am Hintergrund — hell ist
 *    sie deshalb reinweiss wie die Karten (plus Schatten), dunkel eine Stufe
 *    ueber der Karte.
 *  * Der **Auswahl-Pill** hinter dem aktiven Ziel ist bewusst *neutral* und
 *    nicht markengruen: One UI markiert den aktiven Reiter durch Kontrast
 *    (heller Pill, voll deckendes Symbol), nicht durch Farbe. Ein gruener Pill
 *    saehe nach Material aus, nicht nach Samsung.
 *
 * Warum ein eigener Halter und keine `colorScheme`-Slots: Beide Werte haengen
 * am Hell-/Dunkelmodus, sind aber keine Material-Rollen — genau die Lage, fuer
 * die es in dieser App schon [SignalColors] gibt. Dieselbe Bauart, damit ein
 * Leser nicht zwei Muster lernen muss.
 */
@Immutable
data class NavigationBarColors(
    /** Flaeche der schwebenden Kapsel. */
    val container: Color,
    /** Pille hinter dem aktiven Ziel. */
    val indicator: Color,
    /** Symbol und Beschriftung des aktiven Ziels. */
    val selectedContent: Color,
    /** Symbol und Beschriftung der uebrigen Ziele. */
    val unselectedContent: Color,
    /**
     * Das **Randlicht** der Kapsel: ein 1 dp schmaler, sehr blasser Streifen
     * an ihrer Kante.
     *
     * One UI 9 kehrt vom Flachen ab und setzt schwebende Flaechen mit
     * geschichteter Tiefe ab — Randlicht plus abgesetztem Schatten statt eines
     * einzelnen schweren Schattens. Vorher trug hier ein 12-dp-Schatten die
     * Schwebe allein; im Hellmodus war das der einzige Unterschied zwischen
     * Kapsel und darunter wegscrollender Karte. Das Randlicht zieht die Kante
     * nach, der Schatten darf dafuer leichter werden.
     */
    val rim: Color,
)

/**
 * Hell: ein heller Grauton — bewusst **nicht** das Weiss der Karten. Die Kapsel
 * schwebt ueber der Liste, und in dem Moment, in dem eine weisse Karte unter
 * ihr steht, waere eine weisse Kapsel nur noch am Schatten zu erkennen. Der
 * Pill ist eine Stufe dunkler und traegt so die Auswahl, ohne wie ein Knopf zu
 * wirken.
 */
internal val LightNavigationBarColors = NavigationBarColors(
    container = Color(0xFFECEEF2),
    indicator = Color(0xFFDBDEE5),
    selectedContent = LightOnSurface,
    unselectedContent = LightOnSurfaceVariant,
    // 8 % Schwarz: gerade genug, dass die Kante steht, ohne dass die Kapsel
    // umrandet aussieht.
    rim = LightOnSurface.copy(alpha = 0.08f),
)

/**
 * Dunkel: eine Stufe ueber der Karte (#17181B), damit die Kapsel vor dem
 * fast schwarzen Grund schwebt statt in ihm zu versinken — Schatten traegt im
 * Dunkelmodus nichts, die Helligkeit muss die Arbeit tun.
 */
internal val DarkNavigationBarColors = NavigationBarColors(
    container = Color(0xFF26272C),
    indicator = Color(0xFF3A3C43),
    selectedContent = Color(0xFFF2F2F5),
    unselectedContent = Color(0xFF9CA2AA),
    // Im Dunkeln muss das Randlicht heller sein als der Grund, nicht dunkler —
    // deshalb Weiss und etwas kraeftiger, weil ein Schatten hier ohnehin
    // nichts traegt.
    rim = Color(0xFFF2F2F5).copy(alpha = 0.12f),
)

/**
 * Die aktuell gueltigen Leistenfarben; `static`, weil sie sich nur beim
 * Wechsel des Hell-/Dunkelmodus aendern. Gestellt von [TrailscapeTheme].
 */
val LocalNavigationBarColors = staticCompositionLocalOf { LightNavigationBarColors }
