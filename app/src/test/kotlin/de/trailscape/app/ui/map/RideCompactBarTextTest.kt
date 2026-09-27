package de.trailscape.app.ui.map

import de.trailscape.app.ui.map.MapTestStrings.de
import de.trailscape.app.ui.map.MapTestStrings.en
import de.trailscape.core.i18n.AppLanguage
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
        assertEquals("24,3", de(kompaktTempoWert(24.31, paused = false, autoPaused = false, language = AppLanguage.DE)))
        assertEquals("0,0", de(kompaktTempoWert(0.0, paused = false, autoPaused = false, language = AppLanguage.DE)))
    }

    @Test
    fun `unbekanntes Tempo bleibt der Strich`() {
        assertEquals("–", de(kompaktTempoWert(null, paused = false, autoPaused = false, language = AppLanguage.DE)))
    }

    @Test
    fun `pausiert traegt der Wert den Zustand statt einer Null`() {
        assertEquals("Pause", de(kompaktTempoWert(0.0, paused = true, autoPaused = false, language = AppLanguage.DE)))
        assertEquals("Pause", de(kompaktTempoWert(0.0, paused = true, autoPaused = true, language = AppLanguage.DE)))
        // Auch mit (veraltetem) Tempo gewinnt der Zustand.
        assertEquals("Pause", de(kompaktTempoWert(12.0, paused = true, autoPaused = false, language = AppLanguage.DE)))
    }

    @Test
    fun `autoPaused ohne paused zaehlt nicht als Pause`() {
        // `autoPaused` ist nur die Einfaerbung einer laufenden Pause — ohne
        // `paused` gibt es keine (dieselbe Logik wie der Status-Chip des
        // Fahrmodus).
        assertEquals("24,3", de(kompaktTempoWert(24.3, paused = false, autoPaused = true, language = AppLanguage.DE)))
    }

    // ------------------------------------------------------ Tempo-Label

    @Test
    fun `label wechselt mit dem Pausenzustand`() {
        assertEquals("km/h", de(kompaktTempoLabel(paused = false)))
        assertEquals("Aufzeichnung", de(kompaktTempoLabel(paused = true)))
        assertEquals("automatisch", de(kompaktTempoLabel(paused = true, autoPaused = true)))
    }

    // ------------------------------------------------------- Vorlesesatz

    @Test
    fun `vorlesesatz nennt Bedeutung Wert und Einheit`() {
        assertEquals(
            "Tempo 24,3 Kilometer pro Stunde",
            de(kompaktTempoSpoken(24.3, paused = false, autoPaused = false, language = AppLanguage.DE)),
        )
        assertEquals(
            "Tempo unbekannt",
            de(kompaktTempoSpoken(null, paused = false, autoPaused = false, language = AppLanguage.DE)),
        )
    }

    @Test
    fun `vorlesesatz benennt die Pausenart`() {
        assertEquals(
            "Aufzeichnung pausiert",
            de(kompaktTempoSpoken(0.0, paused = true, autoPaused = false, language = AppLanguage.DE)),
        )
        assertEquals(
            "Aufzeichnung in Auto-Pause",
            de(kompaktTempoSpoken(0.0, paused = true, autoPaused = true, language = AppLanguage.DE)),
        )
    }

    // ----------------------------------------------------------- Englisch

    @Test
    fun `auf Englisch mit Punkt und englischen Zustaenden`() {
        assertEquals("24.3", en(kompaktTempoWert(24.31, paused = false, autoPaused = false, language = AppLanguage.EN)))
        assertEquals("Paused", en(kompaktTempoWert(0.0, paused = true, autoPaused = false, language = AppLanguage.EN)))
        assertEquals("recording", en(kompaktTempoLabel(paused = true)))
        assertEquals("automatic", en(kompaktTempoLabel(paused = true, autoPaused = true)))
        assertEquals(
            "Speed 24.3 kilometres per hour",
            en(kompaktTempoSpoken(24.3, paused = false, autoPaused = false, language = AppLanguage.EN)),
        )
        assertEquals(
            "Recording on auto-pause",
            en(kompaktTempoSpoken(0.0, paused = true, autoPaused = true, language = AppLanguage.EN)),
        )
    }
}
