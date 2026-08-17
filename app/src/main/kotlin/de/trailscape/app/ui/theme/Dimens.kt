package de.trailscape.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Das Raster, an das sich alle Tabs halten.
 *
 * Wer eine neue Karte oder einen neuen Screen baut, nimmt diese Konstanten,
 * statt eine weitere Zahl zu erfinden.
 *
 * ## Die 24-dp-Regel — und worauf sie sich bezieht
 *
 * Samsungs Designleitfaden verlangt, **Information und Bedienelemente** mit
 * mindestens **24 dp** Abstand zum linken und rechten Bildschirmrand zu
 * platzieren. Der Grund steht dort dabei und ist nicht wegzudiskutieren:
 * Displaykruemmung, **Reject-Zone** (Beruehrungen am aeussersten Rand
 * verwirft das System) und **Grip-Zone** (Handballen beim Halten).
 *
 * Dieser Wert hier stand kurzzeitig auf 24 dp — und das war ein Denkfehler.
 * Die Regel spricht von der **Information**, nicht vom Behaelter, in dem sie
 * liegt. In Samsungs eigenen Einstellungen sitzt die Karte bei rund 16 dp;
 * ihr Inhalt landet durch [CardPadding] erst bei rund 32 dp — die 24 dp sind
 * also mit Reserve erfuellt, ohne dass die Karte selbst so weit einruecken
 * muesste. Wer beides addiert, legt die Regel ein zweites Mal drauf, und der
 * Unterschied faellt im direkten Vergleich mit einer Samsung-App sofort auf:
 * Die eigenen Karten stehen sichtbar weiter innen als die des Systems.
 *
 * Daraus folgt die Aufteilung:
 *
 *  * [ScreenPadding] (16 dp) traegt die **Karte** an den Rand.
 *  * [CardPadding] (16 dp) bringt den **Inhalt** auf die geforderten 24 dp
 *    und darueber.
 *  * Text, der ohne Karte direkt auf dem Grund steht — Begruessung,
 *    Gruppenueberschrift —, bekommt [CardPadding] zusaetzlich, damit er auf
 *    derselben Kante sitzt wie der Text *in* den Karten. Genau so macht es
 *    Samsungs Telefon-App mit ihren Datumsueberschriften.
 */

/**
 * Maximale Inhaltsbreite. Auf dem Telefon greift sie nie, auf Tablets bleibt
 * die Spalte mittig und lesbar schmal (Port von Darts
 * `ConstrainedBox(maxWidth: 640)`).
 */
val ContentMaxWidth = 640.dp

/**
 * Rand einer Bildschirmliste zum Bildschirmrand — der Abstand der **Karte**,
 * nicht des Inhalts. An Samsungs Einstellungen gemessen; die 24-dp-Regel
 * erfuellt erst [CardPadding] obendrauf. Siehe die Begruendung oben.
 */
val ScreenPadding = 16.dp

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
 * Dieselben 16 dp wie [ScreenPadding], und aus demselben Grund: Es ist der
 * Abstand der *Flaeche*, ihr Inhalt kommt durch das Kartenpolster ohnehin
 * jenseits der geforderten 24 dp an. Die Karte darunter bleibt unberuehrt —
 * sie laeuft weiter randlos bis zur Bildschirmkante, nur ihre Bedienflaechen
 * ruecken ein.
 */
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
