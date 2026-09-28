package de.trailscape.app.ui.map

import de.trailscape.app.ui.map.MapTestStrings.de
import de.trailscape.app.ui.map.MapTestStrings.en
import de.trailscape.core.TurnRichtung
import de.trailscape.core.i18n.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests der reinen Darstellungslogik des Navigations-HUD (`NavigationHud.kt`):
 * Kurvendistanz-Kurzform, Richtungsworte, Tempo-Glaettung und die
 * Restzeit-Zeile. Reine JVM-Tests — das Compose-HUD selbst bleibt, wie ueberall
 * in diesem Modul, ungetestet.
 */
class NavigationHudTextTest {

    // ------------------------------------------------- Kurvendistanz-Kurzform

    @Test
    fun `Kurvendistanz wird auf 50er gerundet`() {
        assertEquals("In 250 m", de(kurveAbstandKurzText(250.0)))
        assertEquals("In 150 m", de(kurveAbstandKurzText(137.0)))
        assertEquals("In 50 m", de(kurveAbstandKurzText(44.0)))
        assertEquals("In 1000 m", de(kurveAbstandKurzText(990.0)))
    }

    @Test
    fun `im Nahbereich heisst es Gleich`() {
        // Dieselbe Schwelle wie die Sprachansage (`ANSAGE_GLEICH_M` = 40 m).
        assertEquals("Gleich", de(kurveAbstandKurzText(39.9)))
        assertEquals("Gleich", de(kurveAbstandKurzText(0.0)))
        assertEquals("In 50 m", de(kurveAbstandKurzText(40.0)))
    }

    @Test
    fun `Anzeigeworte decken alle Richtungen ab`() {
        assertEquals("Links", de(kurveAnzeigeWort(TurnRichtung.LINKS)))
        assertEquals("Rechts", de(kurveAnzeigeWort(TurnRichtung.RECHTS)))
        assertEquals("Scharf links", de(kurveAnzeigeWort(TurnRichtung.KEHRE_LINKS)))
        assertEquals("Scharf rechts", de(kurveAnzeigeWort(TurnRichtung.KEHRE_RECHTS)))
    }

    // -------------------------------------------------------- Tempo-Glaettung

    @Test
    fun `Glaettung startet mit dem ersten Messwert`() {
        assertNull(glaetteTempo(null, null))
        assertEquals(20.0, glaetteTempo(null, 20.0))
    }

    @Test
    fun `unbekanntes Tempo laesst den bisherigen Wert stehen`() {
        assertEquals(20.0, glaetteTempo(20.0, null))
    }

    @Test
    fun `neue Messwerte fliessen gewichtet ein`() {
        // 20 + (30 - 20) * 0,3 = 23 — ein Sprung wird gedaempft, nicht kopiert.
        assertEquals(23.0, glaetteTempo(20.0, 30.0)!!, 1e-9)
    }

    // ----------------------------------------------------------- Restzeit

    @Test
    fun `Restzeit rechnet mit dem gemessenen Tempo`() {
        // 10 km bei 30 km/h sind 20 Minuten.
        assertEquals(20, restzeitMin(10.0, 30.0))
    }

    @Test
    fun `ohne Tempo gilt die Annahme von 15 kmh`() {
        // 12,4 km bei 15 km/h sind 49,6 min, aufgerundet 50.
        assertEquals(50, restzeitMin(12.4, null))
    }

    @Test
    fun `Schritttempo faellt auf die Annahme zurueck`() {
        // 1 km/h ergaebe 12 Stunden fuer 12 km — ein Zwischenhalt darf die
        // Schaetzung nicht auf Stunden treiben.
        assertEquals(restzeitMin(12.0, null), restzeitMin(12.0, 1.0))
    }

    @Test
    fun `Restzeit wird aufgerundet`() {
        // 10 km bei 27 km/h sind 22,2 min — wer 22,2 braucht, ist nicht in 22 da.
        assertEquals(23, restzeitMin(10.0, 27.0))
    }

    @Test
    fun `Restzeittext unter einer Stunde in Minuten`() {
        assertEquals("ca. 50 min", de(restzeitText(50)))
        assertEquals("ca. 0 min", de(restzeitText(0)))
    }

    @Test
    fun `Restzeittext ab einer Stunde in Stunden und Minuten`() {
        assertEquals("ca. 1 h 10 min", de(restzeitText(70)))
        assertEquals("ca. 2 h", de(restzeitText(120)))
    }

    @Test
    fun `die Restzeile kombiniert Distanz und Restzeit deutsch`() {
        assertEquals("12,4 km · ca. 50 min", de(navRestZeile(12.4, null, AppLanguage.DE)))
        assertEquals("10,0 km · ca. 20 min", de(navRestZeile(10.0, 30.0, AppLanguage.DE)))
    }

    // ----------------------------------------------------------- Englisch

    @Test
    fun `Kurvenzeile auf Englisch`() {
        assertEquals("In 250 m", en(kurveAbstandKurzText(250.0)))
        assertEquals("Now", en(kurveAbstandKurzText(10.0)))
        assertEquals("Left", en(kurveAnzeigeWort(TurnRichtung.LINKS)))
        assertEquals("Right", en(kurveAnzeigeWort(TurnRichtung.RECHTS)))
        assertEquals("Sharp left", en(kurveAnzeigeWort(TurnRichtung.KEHRE_LINKS)))
        assertEquals("Sharp right", en(kurveAnzeigeWort(TurnRichtung.KEHRE_RECHTS)))
    }

    @Test
    fun `Restzeit und Restzeile auf Englisch mit Punkt`() {
        assertEquals("approx. 50 min", en(restzeitText(50)))
        assertEquals("approx. 1 h 10 min", en(restzeitText(70)))
        assertEquals("approx. 2 h", en(restzeitText(120)))
        assertEquals("12.4 km · approx. 50 min", en(navRestZeile(12.4, null, AppLanguage.EN)))
    }

    @Test
    fun `die vorgelesene Dauer kommt ohne ca`() {
        assertEquals("50 min", de(restzeitDauer(50)))
        assertEquals("1 h 10 min", en(restzeitDauer(70)))
    }
}
