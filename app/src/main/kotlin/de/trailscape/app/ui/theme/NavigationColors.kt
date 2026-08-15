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
)

/**
 * Hell: weisse Kapsel wie die Karten, damit sie sich vom grauen
 * Bildschirmgrund (#F4F4F6) abhebt; der Pill ist ein heller Grauton, der auf
 * Weiss gerade sichtbar ist, ohne als Knopf zu wirken.
 */
internal val LightNavigationBarColors = NavigationBarColors(
    container = Color(0xFFFFFFFF),
    indicator = Color(0xFFE8EAEE),
    selectedContent = LightOnSurface,
    unselectedContent = LightOnSurfaceVariant,
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
)

/**
 * Die aktuell gueltigen Leistenfarben; `static`, weil sie sich nur beim
 * Wechsel des Hell-/Dunkelmodus aendern. Gestellt von [TrailscapeTheme].
 */
val LocalNavigationBarColors = staticCompositionLocalOf { LightNavigationBarColors }
