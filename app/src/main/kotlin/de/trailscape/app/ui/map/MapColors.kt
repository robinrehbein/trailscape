package de.trailscape.app.ui.map

import androidx.compose.ui.graphics.Color

/**
 * Die drei festen Kartenfarben aus `lib/screens/map_screen.dart`
 * (`kGreen`, `kRed`, `kBlue`).
 *
 * Bewusst **nicht** aus `MaterialTheme.colorScheme`: Diese Farben liegen auf
 * den Kartenkacheln, nicht auf einer Theme-Flaeche. Sie muessen im hellen wie
 * im dunklen Modus identisch bleiben, damit eine aufgezeichnete Tour immer
 * gleich aussieht und sich vom Untergrund (Satellitenbild, Gelaende, Strasse)
 * abhebt. Alle *Flaechen* der Bedienelemente daruber (Karten, Knoepfe, Texte)
 * kommen dagegen aus dem Theme.
 */

/** Aufgezeichnete/ausgewaehlte Tour. */
internal val GravelGreen = Color(0xFF2D5A3D)

/** Laufende Aufzeichnung und Warnungen. */
internal val RecordRed = Color(0xFFB3382C)

/** Routenplanung. */
internal val RouteBlue = Color(0xFF2563EB)
