package de.trailscape.app.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests der reinen Textentscheidungen hinter [RecCapsuleButton]
 * (`recElapsedLabel`, `recRouteLabel`) — reine JVM-Asserts ohne Android und
 * ohne Compose, so wie ueberall in diesem Modul (`:app` hat bewusst kein
 * Robolectric und keine Compose-UI-Tests).
 */
class RecButtonStateTest {

    // ------------------------------------------------------- Fahrzeit-Label

    @Test
    fun `null Millisekunden ergeben 0 00`() {
        assertEquals("0:00", recElapsedLabel(0L))
    }

    @Test
    fun `Minuten bleiben unter einer Stunde ohne fuehrende Null`() {
        assertEquals("0:07", recElapsedLabel(7_000L))
        assertEquals("12:34", recElapsedLabel(754_000L))
    }

    @Test
    fun `59 Sekunden kippen noch nicht auf eine Minute`() {
        assertEquals("0:59", recElapsedLabel(59_000L))
    }

    @Test
    fun `60 Sekunden werden zur ersten vollen Minute`() {
        assertEquals("1:00", recElapsedLabel(60_000L))
    }

    @Test
    fun `3599 Sekunden bleiben knapp unter der Stundengrenze`() {
        assertEquals("59:59", recElapsedLabel(3_599_000L))
    }

    @Test
    fun `3600 Sekunden bringen die erste Stunde mit zweistelligen Minuten`() {
        assertEquals("1:00:00", recElapsedLabel(3_600_000L))
    }

    @Test
    fun `3661 Sekunden zeigen Stunde Minute und Sekunde einzeln`() {
        assertEquals("1:01:01", recElapsedLabel(3_661_000L))
    }

    @Test
    fun `groessere Fahrzeiten bleiben lesbar`() {
        // 1 h 2 min 3 s
        assertEquals("1:02:03", recElapsedLabel(3_723_000L))
    }

    // ------------------------------------------------------- Strecken-Label

    @Test
    fun `null Kilometer bleiben eine Nachkommastelle`() {
        assertEquals("0,0 km", recRouteLabel(0.0))
    }

    @Test
    fun `44 75 rundet kaufmaennisch auf 44 8`() {
        assertEquals("44,8 km", recRouteLabel(44.75))
    }

    @Test
    fun `100 04 rundet ab und behaelt die Nachkommastelle`() {
        assertEquals("100,0 km", recRouteLabel(100.04))
    }

    @Test
    fun `glatte Werte bleiben nie ohne Nachkommastelle`() {
        // Bewusst NICHT "45 km" — das Label bleibt in jedem Fall einstellig
        // nach dem Komma, konsistent mit jeder anderen Kilometerzahl der App.
        assertEquals("45,0 km", recRouteLabel(45.0))
    }
}
