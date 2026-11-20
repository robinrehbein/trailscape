package de.trailscape.app.ui.map

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.map.MapTestStrings.de
import de.trailscape.app.ui.map.MapTestStrings.en
import de.trailscape.core.i18n.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * „Neue Wege" in der Oberflaeche: die Wahl im Blatt *Runde ab hier* und die
 * Zeile „davon 23 km neu" am Vorschlag. Reine JVM-Tests; die Bilder liefert
 * `NewWaysScreenshotTest`.
 */
class NewWaysTextTest {

    @Test
    fun `davon N km neu - ganze km ab 10, darunter eine Nachkommastelle`() {
        assertEquals("davon 23 km neu", de(newWaysLabel(23.4, AppLanguage.DE)))
        assertEquals("23 km of it new", en(newWaysLabel(23.4, AppLanguage.EN)))
        assertEquals("davon 4,3 km neu", de(newWaysLabel(4.26, AppLanguage.DE)))
        assertEquals("4.3 km of it new", en(newWaysLabel(4.26, AppLanguage.EN)))
        assertEquals("davon 10 km neu", de(newWaysLabel(9.96, AppLanguage.DE)))
    }

    @Test
    fun `ohne neue Strecke heisst es nur bekannte Wege`() {
        assertEquals("nur bekannte Wege", de(newWaysLabel(0.0, AppLanguage.DE)))
        assertEquals("only roads you know", en(newWaysLabel(0.0, AppLanguage.EN)))
        assertEquals("nur bekannte Wege", de(newWaysLabel(0.03, AppLanguage.DE)))
        assertEquals("nur bekannte Wege", de(newWaysLabel(Double.NaN, AppLanguage.DE)))
        assertEquals("davon 0,1 km neu", de(newWaysLabel(0.06, AppLanguage.DE)))
    }

    @Test
    fun `die Wahl bedient genau einen Schalter des Generators`() {
        assertFalse(DiscoveryMode.OFF.preferNewAreas || DiscoveryMode.OFF.preferNewWays)
        assertTrue(DiscoveryMode.NEW_WAYS.preferNewWays && !DiscoveryMode.NEW_WAYS.preferNewAreas)
        assertTrue(DiscoveryMode.NEW_AREAS.preferNewAreas && !DiscoveryMode.NEW_AREAS.preferNewWays)
        // Reihenfolge der Segmente im Blatt
        assertEquals(listOf(DiscoveryMode.OFF, DiscoveryMode.NEW_WAYS, DiscoveryMode.NEW_AREAS), DiscoveryMode.entries)
    }

    @Test
    fun `jede Stellung hat ihren eigenen Hinweis`() {
        assertEquals(R.string.map_round_trip_discover_off_body, discoveryNoteRes(DiscoveryMode.OFF))
        assertEquals(R.string.map_round_trip_discover_ways_body, discoveryNoteRes(DiscoveryMode.NEW_WAYS))
        assertEquals(R.string.map_round_trip_discover_areas_body, discoveryNoteRes(DiscoveryMode.NEW_AREAS))
    }

    @Test
    fun `jedes Segment sagt TalkBack auch die Ueberschrift`() {
        assertEquals(
            listOf("Neu entdecken: Aus", "Neu entdecken: Wege", "Neu entdecken: Gegenden"),
            DiscoveryMode.entries.map { de(UiText.Res(discoveryDescriptionRes(it))) },
        )
        assertEquals(
            listOf("Discover: off", "Discover: new roads", "Discover: new areas"),
            DiscoveryMode.entries.map { en(UiText.Res(discoveryDescriptionRes(it))) },
        )
    }

    @Test
    fun `scheitert der Wege-Index, sagt es ein Hinweis - sonst nicht`() {
        val text = { de(UiText.Res(R.string.map_generation_new_ways_unavailable_hint)) }
        assertEquals("„Wege“ konnte diesmal nicht berücksichtigt werden.", text())
        assertEquals(
            "“New roads” couldn’t be taken into account this time.",
            en(UiText.Res(R.string.map_generation_new_ways_unavailable_hint)),
        )
        val core = listOf("Zieldistanz auf 15 km angehoben.")
        assertEquals(core + text(), generationHints(core, newWaysUnavailable = true, newWaysUnavailableText = text))
        assertEquals(core, generationHints(core, newWaysUnavailable = false, newWaysUnavailableText = text))
        assertEquals(listOf(text()), generationHints(emptyList(), newWaysUnavailable = true, newWaysUnavailableText = text))
    }
}
