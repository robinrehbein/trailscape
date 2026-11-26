package de.trailscape.app.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutClassTest {

    @Test
    fun `Fensterklassen trennen Telefon, Tablet hochkant und Tablet quer sauber`() {
        assertEquals(WindowLayoutClass.Compact, windowLayoutClassFor(360))
        assertEquals(WindowLayoutClass.Compact, windowLayoutClassFor(411))
        assertEquals(WindowLayoutClass.Compact, windowLayoutClassFor(599))

        assertEquals(WindowLayoutClass.Medium, windowLayoutClassFor(600))
        assertEquals(WindowLayoutClass.Medium, windowLayoutClassFor(800))
        assertEquals(WindowLayoutClass.Medium, windowLayoutClassFor(839))

        assertEquals(WindowLayoutClass.Expanded, windowLayoutClassFor(840))
        assertEquals(WindowLayoutClass.Expanded, windowLayoutClassFor(1280))
    }

    @Test
    fun `Rail erscheint ab Medium, Zwei-Spalten-Layout erst ab Expanded`() {
        assertFalse(useNavigationRail(WindowLayoutClass.Compact))
        assertTrue(useNavigationRail(WindowLayoutClass.Medium))
        assertTrue(useNavigationRail(WindowLayoutClass.Expanded))

        assertFalse(useTwoPaneLayout(WindowLayoutClass.Compact))
        assertFalse(useTwoPaneLayout(WindowLayoutClass.Medium))
        assertTrue(useTwoPaneLayout(WindowLayoutClass.Expanded))
    }
}
