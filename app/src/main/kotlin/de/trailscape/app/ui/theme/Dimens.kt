package de.trailscape.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Das Raster, an das sich alle Tabs halten. Die Zahlen sind an den
 * Samsung-Einstellungen unter One UI 8.5 abgemessen (Bildschirmrand rund
 * 14–16 dp, Innenabstand einer Karte rund 16 dp, sichtbarer Abstand zwischen
 * zwei Karten rund 12 dp): Die App stand vorher durchgehend auf 20 dp und
 * wirkte daneben luftig-fremd — bei 360 dp Bildschirmbreite gehen 8 dp
 * Rand plus Innenabstand direkt von der Textbreite ab.
 *
 * Wer eine neue Karte oder einen neuen Screen baut, nimmt diese Konstanten,
 * statt eine weitere Zahl zu erfinden.
 */

/**
 * Maximale Inhaltsbreite. Auf dem Telefon greift sie nie, auf Tablets bleibt
 * die Spalte mittig und lesbar schmal (Port von Darts
 * `ConstrainedBox(maxWidth: 640)`).
 */
val ContentMaxWidth = 640.dp

/** Rand einer Bildschirmliste zum Bildschirmrand. */
val ScreenPadding = 16.dp

/** Senkrechter Abstand zwischen zwei Karten einer Liste. */
val CardGap = 12.dp

/** Innenabstand einer Karte. */
val CardPadding = 16.dp

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
 * Deutlich ueber den 48 dp, die schon die Material-Untergrenze und damit auch
 * die uebrigen Knoepfe der App sind: Getroffen wird hier mit Handschuh, aus
 * der Bewegung und ohne hinzusehen — dafuer reicht die Mindestflaeche nicht.
 * Wer diese Zahl senkt, macht den Modus wertlos — sie ist sein eigentlicher
 * Zweck.
 */
val RideModeActionHeight = 88.dp

/**
 * Hoehe des Rueckwegs zur Karte im Fahrmodus. Kleiner als
 * [RideModeActionHeight], weil er im Fahren nicht gebraucht wird — aber
 * immer noch weit ueber einem Symbolknopf, damit er im Stand sicher trifft.
 */
val RideModeExitHeight = 56.dp
