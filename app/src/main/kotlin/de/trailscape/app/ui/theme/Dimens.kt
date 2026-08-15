package de.trailscape.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Das Raster, an das sich alle Tabs halten — im One-UI-Mass: grosszuegige
 * Raender (20 dp), Karten mit etwas mehr Luft innen (20 dp) und dafuer ein
 * ruhigerer Abstand zwischen den Karten (12 dp), weil die stark gerundeten
 * Flaechen ohnehin visuell trennen. Wer eine neue Karte oder einen neuen
 * Screen baut, nimmt diese Konstanten, statt eine weitere Zahl zu erfinden.
 */

/**
 * Maximale Inhaltsbreite. Auf dem Telefon greift sie nie, auf Tablets bleibt
 * die Spalte mittig und lesbar schmal (Port von Darts
 * `ConstrainedBox(maxWidth: 640)`).
 */
val ContentMaxWidth = 640.dp

/** Rand einer Bildschirmliste zum Bildschirmrand. */
val ScreenPadding = 20.dp

/** Senkrechter Abstand zwischen zwei Karten einer Liste. */
val CardGap = 12.dp

/** Innenabstand einer Karte. */
val CardPadding = 20.dp

/**
 * Innenabstand einer Karte, die auf der Karte schwebt (Karten-Tab). Etwas
 * flacher als [CardPadding], damit die Panels nicht mehr Kartenbild verdecken
 * als noetig — waagerecht bleibt es bei [CardPadding].
 */
val OverlayCardPaddingVertical = 14.dp

/** Rand der schwebenden Bedienflaechen zum Bildschirmrand (Karten-Tab). */
val OverlayScreenPadding = 16.dp

/** Abstand zwischen zwei schwebenden Bedienflaechen (Karten-Tab). */
val OverlayGap = 8.dp

/**
 * Hoehe der beiden Bedienflaechen im Fahrmodus (Pause/Weiter und Beenden).
 *
 * Deutlich ueber der Material-Untergrenze von 48 dp und ueber den 40 dp der
 * uebrigen Knoepfe der App: Getroffen wird hier mit Handschuh, aus der Bewegung
 * und ohne hinzusehen. Wer diese Zahl senkt, macht den Modus wertlos — sie ist
 * sein eigentlicher Zweck.
 */
val RideModeActionHeight = 88.dp

/**
 * Hoehe des Rueckwegs zur Karte im Fahrmodus. Kleiner als
 * [RideModeActionHeight], weil er im Fahren nicht gebraucht wird — aber
 * immer noch weit ueber einem Symbolknopf, damit er im Stand sicher trifft.
 */
val RideModeExitHeight = 56.dp
