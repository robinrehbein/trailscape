package de.trailscape.app.ui.map

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.Waypoint

/**
 * Die Namen, unter denen „Mein Standort als Start" den Wegpunkt der eigenen
 * Position ablegt — je Sprache der Wert von `map_planning_my_position_name`.
 *
 * Warum eine feste Liste statt der Ressource: Die Wegpunkte ueberleben den
 * Sprachwechsel (rememberSaveable, die Activity wird neu erstellt), der Name
 * bleibt aber in der Sprache, in der der Punkt gesetzt wurde. Der Vergleich
 * mit der Ressource der *aktuellen* Sprache erkannte „Mein Standort" nach dem
 * Wechsel auf Englisch nicht mehr — die Zeile „My location as start" kam
 * zurueck, und der Standort liess sich ein zweites Mal einfuegen. Ein Test
 * haelt diese Liste mit den Ressourcen deckungsgleich.
 */
internal val MY_POSITION_NAMES: Set<String> = setOf("Mein Standort", "My location")

/** Ob [name] der Wegpunktname der eigenen Position ist — in jeder Sprache. */
internal fun isMyPositionName(name: String?): Boolean = name != null && name in MY_POSITION_NAMES

/**
 * Ob die Zeile „Mein Standort als Start" angeboten wird: nur, solange kein
 * Wegpunkt schon die eigene Position ist — gleich, in welcher Sprache er
 * gesetzt wurde.
 */
internal fun showUseMyPosition(waypoints: List<Waypoint>): Boolean =
    waypoints.none { isMyPositionName(it.name) }

/**
 * Der angezeigte Name eines Wegpunkts: die eigene Position in der aktuellen
 * Sprache (auch wenn sie in der anderen gesetzt wurde), alles andere
 * unveraendert (Suchtreffer, Ortsnamen).
 */
internal fun waypointNameText(name: String): UiText =
    if (isMyPositionName(name)) UiText.Res(R.string.map_planning_my_position_name) else UiText.Plain(name)
