package de.trailscape.app.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests der reinen Textentscheidungen der Kompaktleiste
 * (`RideCompactBar.kt`): der Tempo-Platz traegt pausiert den Zustand statt
 * einer Null, und die Vorlesesaetze folgen dem `BigValue`-Muster. Reine
 * JVM-Tests — die Compose-Leiste selbst bleibt, wie ueberall in diesem
 * Modul, ungetestet.
 */
class RideCompactBarTextTest {

    // ------------------------------------------------------- Tempo-Wert

    @Test
    fun `fahrend steht das Tempo mit einer Nachkommastelle`() {
        assertEquals("24,3", kompaktTempoWert(24.31, paused = false, autoPaused = false))
        assertEquals("0,0", kompaktTempoWert(0.0, paused = false, autoPaused = false))
    }

    @Test
    fun `unbekanntes Tempo bleibt der Strich`() {
        assertEquals("–", kompaktTempoWert(null, paused = false, autoPaused = false))
    }

    @Test
    fun `pausiert traegt der Wert den Zustand statt einer Null`() {
        assertEquals("Pause", kompaktTempoWert(0.0, paused = true, autoPaused = false))
        assertEquals("Auto-Pause", kompaktTempoWert(0.0, paused = true, autoPaused = true))
        // Auch mit (veraltetem) Tempo gewinnt der Zustand.
        assertEquals("Pause", kompaktTempoWert(12.0, paused = true, autoPaused = false))
    }

    @Test
    fun `autoPaused ohne paused zaehlt nicht als Pause`() {
        // `autoPaused` ist nur die Einfaerbung einer laufenden Pause — ohne
        // `paused` gibt es keine (dieselbe Logik wie der Status-Chip des
        // Fahrmodus).
        assertEquals("24,3", kompaktTempoWert(24.3, paused = false, autoPaused = true))
    }

    // ------------------------------------------------------ Tempo-Label

    @Test
    fun `label wechselt mit dem Pausenzustand`() {
        assertEquals("km/h", kompaktTempoLabel(paused = false))
        assertEquals("Aufzeichnung", kompaktTempoLabel(paused = true))
    }

    // ------------------------------------------------------- Vorlesesatz

    @Test
    fun `vorlesesatz nennt Bedeutung Wert und Einheit`() {
        assertEquals(
            "Tempo 24,3 Kilometer pro Stunde",
            kompaktTempoSpoken(24.3, paused = false, autoPaused = false),
        )
        assertEquals(
            "Tempo unbekannt",
            kompaktTempoSpoken(null, paused = false, autoPaused = false),
        )
    }

    @Test
    fun `vorlesesatz benennt die Pausenart`() {
        assertEquals(
            "Aufzeichnung pausiert",
            kompaktTempoSpoken(0.0, paused = true, autoPaused = false),
        )
        assertEquals(
            "Aufzeichnung in Auto-Pause",
            kompaktTempoSpoken(0.0, paused = true, autoPaused = true),
        )
    }
}
