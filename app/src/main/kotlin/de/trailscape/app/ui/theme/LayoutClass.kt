package de.trailscape.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Fensterklassen fuer Telefon und Tablet (Galaxy Tab S11).
 *
 * Reine Logik ohne Compose-Abhaengigkeit, damit alle Breakpoints direkt als
 * JVM-Test geprueft werden koennen (`LayoutClassTest`).
 */
enum class WindowLayoutClass {
    /** Telefon hochkant (360 dp, 412 dp): 1 Spalte, Leiste unten. */
    Compact,

    /** Tablet hochkant (z. B. Tab S11 hochkant ~800 dp): Rail links, 1 breite Spalte. */
    Medium,

    /** Tablet quer (z. B. Tab S11 quer ~1280 dp): Rail links, 2 Spalten. */
    Expanded,
}

/** Ab dieser Breite (dp) wechselt die Navigation in die linke Leiste (Rail). */
const val MEDIUM_WIDTH_DP = 600

/** Ab dieser Breite (dp) zeigen Uebersichten und Streckenwahl zwei Spalten. */
const val EXPANDED_WIDTH_DP = 840

/** Ordnet einer verfuegbaren Breite in dp die passende [WindowLayoutClass] zu. */
fun windowLayoutClassFor(widthDp: Int): WindowLayoutClass = when {
    widthDp >= EXPANDED_WIDTH_DP -> WindowLayoutClass.Expanded
    widthDp >= MEDIUM_WIDTH_DP -> WindowLayoutClass.Medium
    else -> WindowLayoutClass.Compact
}

/** Ob die Hauptnavigation links als Rail statt unten als schwebende Kapsel steht. */
fun useNavigationRail(layoutClass: WindowLayoutClass): Boolean =
    layoutClass != WindowLayoutClass.Compact

/** Ob zwei Spalten nebeneinander sinnvoll Platz haben. */
fun useTwoPaneLayout(layoutClass: WindowLayoutClass): Boolean =
    layoutClass == WindowLayoutClass.Expanded

/** Aktuelle Fensterklasse; gestellt von `TrailscapeApp`. */
val LocalWindowLayoutClass = staticCompositionLocalOf { WindowLayoutClass.Compact }
