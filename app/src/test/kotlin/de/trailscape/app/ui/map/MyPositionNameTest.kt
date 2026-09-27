package de.trailscape.app.ui.map

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.map.MapTestStrings.de
import de.trailscape.app.ui.map.MapTestStrings.en
import de.trailscape.core.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Der Standort-Wegpunkt wird in jeder Sprache erkannt — auch nach einem
 * Sprachwechsel, der die geretteten Wegpunkte in der alten Sprache laesst.
 */
class MyPositionNameTest {

    @Test
    fun `die feste Liste deckt sich mit den Ressourcen`() {
        val res = UiText.Res(R.string.map_planning_my_position_name)
        assertEquals(setOf(de(res), en(res)), MY_POSITION_NAMES)
    }

    @Test
    fun `ein deutsch gesetzter Standort blendet die Zeile auch auf Englisch aus`() {
        val german = listOf(Waypoint(51.0, 13.0, name = "Mein Standort"), Waypoint(51.1, 13.1, name = "Herkules"))
        assertFalse(showUseMyPosition(german))
        val english = listOf(Waypoint(51.0, 13.0, name = "My location"))
        assertFalse(showUseMyPosition(english))
    }

    @Test
    fun `ohne Standort-Wegpunkt bleibt die Zeile`() {
        assertTrue(showUseMyPosition(emptyList()))
        assertTrue(showUseMyPosition(listOf(Waypoint(51.0, 13.0), Waypoint(51.1, 13.1, name = "Herkules"))))
    }

    @Test
    fun `angezeigt wird der Standort in der aktuellen Sprache`() {
        assertEquals("My location", en(waypointNameText("Mein Standort")))
        assertEquals("Mein Standort", de(waypointNameText("My location")))
        assertEquals("Herkules", en(waypointNameText("Herkules")))
    }
}
