package de.trailscape.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Die Navigationsstruktur nach der Seitenkarte (`docs/ux/umbau.md`). */
class AppNavigationTest {

    @Test
    fun `die Leiste zeigt Heute, Karte, Indoor, Verlauf in dieser Reihenfolge`() {
        assertEquals(
            listOf(AppTab.HOME, AppTab.MAP, AppTab.INDOOR, AppTab.RIDES),
            SHELL_TABS.map { it.tab },
        )
        assertEquals(
            listOf(AppRoutes.HOME, AppRoutes.MAP, AppRoutes.INDOOR, AppRoutes.RIDES),
            SHELL_TABS.map { it.route },
        )
        // Vier ist das Maximum der Kapsel; Routen sind eindeutig.
        assertEquals(4, SHELL_TABS.map { it.route }.toSet().size)
    }

    @Test
    fun `jeder Tab-Wunsch hat ein Ziel, die Einstellungen werden gepusht`() {
        for (tab in AppTab.entries) {
            val command = navCommandFor(tab)
            if (tab == AppTab.MORE) {
                assertEquals(NavCommand.Push(AppRoutes.MORE), command)
            } else {
                assertTrue(command is NavCommand.SwitchTab, "$tab wechselt den Tab")
                assertTrue(SHELL_TABS.any { it.route == (command as NavCommand.SwitchTab).route })
            }
        }
        assertEquals(NavCommand.SwitchTab(AppRoutes.INDOOR), navCommandFor(AppTab.INDOOR))
        assertEquals(NavCommand.SwitchTab(AppRoutes.RIDES), navCommandFor(AppTab.RIDES))
    }

    @Test
    fun `jedes Indoor-Ziel ist eine eigene gepushte Seite und nie der Tab`() {
        val routes = IndoorDestination.entries.map { navCommandFor(it).route }
        assertEquals(routes.size, routes.toSet().size)
        for (route in routes) {
            assertTrue(isPushedRoute(route), route)
            assertFalse(route == AppRoutes.INDOOR, "der Tab ist kein gepushtes Ziel")
        }
        assertEquals(AppRoutes.INDOOR_SETTINGS, navCommandFor(IndoorDestination.SETTINGS).route)
        assertEquals(AppRoutes.INDOOR_FLOW, navCommandFor(IndoorDestination.FLOW).route)
    }

    @Test
    fun `Tabs sind keine gepushten Seiten, alles andere schon`() {
        for (tab in SHELL_TABS) assertFalse(isPushedRoute(tab.route), tab.route)
        assertTrue(isPushedRoute(AppRoutes.MORE))
        assertTrue(isPushedRoute(AppRoutes.INDOOR_WORLDS))
        assertTrue(isPushedRoute(AppRoutes.INDOOR_DIAGNOSTICS))
        // Vor dem ersten Ziel (noch kein Backstack-Eintrag) ist nichts gepusht.
        assertFalse(isPushedRoute(null))
    }

    @Test
    fun `auf den Tabs steht die Leiste, solange keine Aufgabe laeuft`() {
        for (tab in SHELL_TABS) {
            assertFalse(
                navigationBarHidden(tab.route, mapTaskActive = false, rideDetailOpen = false, indoorTaskActive = false),
                tab.route,
            )
        }
        assertFalse(navigationBarHidden(null, mapTaskActive = false, rideDetailOpen = false, indoorTaskActive = false))
    }

    @Test
    fun `gepushte Seiten haben nie eine Leiste`() {
        val pushed = listOf(AppRoutes.MORE) + IndoorDestination.entries.map { navCommandFor(it).route }
        for (route in pushed) {
            assertTrue(
                navigationBarHidden(route, mapTaskActive = false, rideDetailOpen = false, indoorTaskActive = false),
                route,
            )
        }
    }

    @Test
    fun `eine Aufgabe blendet die Leiste nur auf ihrem eigenen Tab aus`() {
        fun hidden(route: String, map: Boolean = false, detail: Boolean = false, indoor: Boolean = false) =
            navigationBarHidden(route, mapTaskActive = map, rideDetailOpen = detail, indoorTaskActive = indoor)

        assertTrue(hidden(AppRoutes.MAP, map = true))
        assertTrue(hidden(AppRoutes.RIDES, detail = true))
        assertTrue(hidden(AppRoutes.INDOOR, indoor = true))

        // Ein liegen gebliebener Wert eines anderen Tabs wirkt nicht.
        assertFalse(hidden(AppRoutes.HOME, map = true, detail = true, indoor = true))
        assertFalse(hidden(AppRoutes.MAP, detail = true, indoor = true))
        assertFalse(hidden(AppRoutes.RIDES, map = true, indoor = true))
        assertFalse(hidden(AppRoutes.INDOOR, map = true, detail = true))
    }

    @Test
    fun `Verlauf zeigt Touren und Fortschritt, Erfolge nur wenn es sie gibt`() {
        assertEquals(
            listOf(HistorySegment.TOURS, HistorySegment.PROGRESS),
            historySegments(achievementsVisible = false),
        )
        assertEquals(
            listOf(HistorySegment.TOURS, HistorySegment.PROGRESS, HistorySegment.ACHIEVEMENTS),
            historySegments(achievementsVisible = true),
        )
    }

    @Test
    fun `Indoor zeigt Strecken und Plan, Herausforderungen und Raum nur wenn es sie gibt`() {
        assertEquals(
            listOf(IndoorSegment.ROUTES, IndoorSegment.PLAN),
            indoorSegments(challengesAvailable = false, roomAvailable = false),
        )
        assertEquals(
            listOf(IndoorSegment.ROUTES, IndoorSegment.PLAN, IndoorSegment.CHALLENGES),
            indoorSegments(challengesAvailable = true, roomAvailable = false),
        )
        assertEquals(
            listOf(IndoorSegment.ROUTES, IndoorSegment.PLAN, IndoorSegment.CHALLENGES, IndoorSegment.ROOM),
            indoorSegments(challengesAvailable = true, roomAvailable = true),
        )
        assertEquals(
            listOf(IndoorSegment.ROUTES, IndoorSegment.PLAN, IndoorSegment.ROOM),
            indoorSegments(challengesAvailable = false, roomAvailable = true),
        )
    }

    @Test
    fun `im Indoor-Tab haengen jetzt die Herausforderungen, der Raum bleibt unsichtbar`() {
        assertEquals(
            listOf(IndoorSegment.ROUTES, IndoorSegment.PLAN, IndoorSegment.CHALLENGES),
            shippedIndoorSegments(),
        )
        assertFalse(IndoorSegment.ROOM in shippedIndoorSegments(), "der Raum hat noch keine Oberflaeche")
    }

    @Test
    fun `waehrend einer Aufgabe bleibt im Indoor-Tab die Sicht Strecken stehen`() {
        val segments = shippedIndoorSegments()
        for (wanted in IndoorSegment.entries) {
            assertEquals(IndoorSegment.ROUTES, shownIndoorSegment(wanted, segments, taskActive = true), "$wanted")
        }
        assertEquals(IndoorSegment.ROUTES, shownIndoorSegment(null, segments, taskActive = true))
        // Ohne Aufgabe gilt der Wunsch — ein Sprung von aussen landet auf den Herausforderungen.
        assertEquals(IndoorSegment.CHALLENGES, shownIndoorSegment(IndoorSegment.CHALLENGES, segments, taskActive = false))
        assertEquals(IndoorSegment.PLAN, shownIndoorSegment(IndoorSegment.PLAN, segments, taskActive = false))
        // Ein Platz ohne Funktion faellt auf „Strecken" zurueck.
        assertEquals(IndoorSegment.ROUTES, shownIndoorSegment(IndoorSegment.ROOM, segments, taskActive = false))
        assertEquals(IndoorSegment.ROUTES, shownIndoorSegment(null, segments, taskActive = false))
    }

    @Test
    fun `Strecken und Herausforderungen leben im Indoor-Ablauf, der Plan daneben`() {
        assertTrue(indoorSegmentUsesFlow(IndoorSegment.ROUTES))
        assertTrue(indoorSegmentUsesFlow(IndoorSegment.CHALLENGES))
        assertFalse(indoorSegmentUsesFlow(IndoorSegment.PLAN))
        assertFalse(indoorSegmentUsesFlow(IndoorSegment.ROOM))
    }

    @Test
    fun `ein Segment, das es nicht mehr gibt, faellt auf das erste zurueck`() {
        val withAchievements = historySegments(achievementsVisible = true)
        val without = historySegments(achievementsVisible = false)
        assertEquals(HistorySegment.ACHIEVEMENTS, resolveSegment(HistorySegment.ACHIEVEMENTS, withAchievements))
        // „Erfolge anzeigen" wird ausgeschaltet, waehrend das Segment offen ist.
        assertEquals(HistorySegment.TOURS, resolveSegment(HistorySegment.ACHIEVEMENTS, without))
        assertEquals(HistorySegment.PROGRESS, resolveSegment(HistorySegment.PROGRESS, without))
        assertEquals(HistorySegment.TOURS, resolveSegment(null, without))
        assertEquals(
            IndoorSegment.ROUTES,
            resolveSegment(IndoorSegment.ROOM, indoorSegments(challengesAvailable = false, roomAvailable = false)),
        )
    }
}
