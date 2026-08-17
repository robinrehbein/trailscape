package de.trailscape.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Das Raster, an das sich alle Tabs halten.
 *
 * Wer eine neue Karte oder einen neuen Screen baut, nimmt diese Konstanten,
 * statt eine weitere Zahl zu erfinden.
 *
 * ## Warum der Bildschirmrand 24 dp ist und nicht 16
 *
 * Hier standen 16 dp, begruendet mit einer Messung an den Samsung-Einstellungen.
 * Samsungs Designleitfaden schreibt aber **mindestens 24 dp** vor — und zwar
 * mit einer Begruendung, die eine Messung am flachen Screenshot nicht sehen
 * kann: die **Kruemmung** heutiger Displays, die **Reject-Zone** (Beruehrungen
 * am aeussersten Rand werden vom System verworfen) und die **Grip-Zone**
 * (Handballen beim Halten). Was auf dem Screenshot wie 16 dp aussieht, ist auf
 * dem Geraet teilweise gar nicht mehr zuverlaessig treffbar.
 *
 * Der Leitfaden gewinnt hier gegen die Messung. Die 8 dp, die das je Seite
 * kostet, gehen von der Textbreite ab — das ist der Preis dafuer, dass am
 * Rand nichts liegt, was der Daumen nicht erreicht.
 */

/**
 * Maximale Inhaltsbreite. Auf dem Telefon greift sie nie, auf Tablets bleibt
 * die Spalte mittig und lesbar schmal (Port von Darts
 * `ConstrainedBox(maxWidth: 640)`).
 */
val ContentMaxWidth = 640.dp

/**
 * Rand einer Bildschirmliste zum Bildschirmrand. Samsungs Mindestmass, siehe
 * die Begruendung oben.
 */
val ScreenPadding = 24.dp

/** Senkrechter Abstand zwischen zwei Karten einer Liste. */
val CardGap = 12.dp

/**
 * Innenabstand einer Karte. Bleibt bei 16 dp: Das Karteninnere ist nicht der
 * Bildschirmrand — dort greifen weder Kruemmung noch Reject-Zone.
 */
val CardPadding = 16.dp

/**
 * Innenabstand einer Karte, die auf der Karte schwebt (Karten-Tab). Etwas
 * flacher als [CardPadding], damit die Panels nicht mehr Kartenbild verdecken
 * als noetig — waagerecht bleibt es bei [CardPadding].
 */
val OverlayCardPaddingVertical = 14.dp

/**
 * Rand der schwebenden Bedienflaechen zum Bildschirmrand (Karten-Tab).
 *
 * Dieselben 24 dp wie [ScreenPadding]: Kruemmung und Reject-Zone
 * interessiert es nicht, ob unter der Flaeche eine Liste oder eine Karte
 * liegt. Die Karte selbst bleibt davon unberuehrt — sie laeuft weiter
 * randlos bis zur Bildschirmkante, nur ihre Bedienflaechen ruecken ein.
 */
val OverlayScreenPadding = 24.dp

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
