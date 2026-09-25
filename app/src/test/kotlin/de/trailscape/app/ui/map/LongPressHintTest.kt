package de.trailscape.app.ui.map

import de.trailscape.app.testing.MemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests des einmaligen Tipps zum langen Druecken (`LongPressHint.kt`): die
 * volle Matrix der Entscheidung, die Ableitung der Kartenlage und der Merker.
 * Reine JVM-Tests — wann `MapScreen.kt` die Entscheidung abfragt (nach der
 * Ruhezeit), sieht man im Screenshot `44-karte-langdruck-tipp`.
 */
class LongPressHintTest {

    // ------------------------------------------------------------ Matrix

    @Test
    fun `die ganze Matrix – nur ruhiges Erkunden im Vordergrund ohne Merker, Schwenk und Snackbar zeigt den Tipp`() {
        val jaNein = listOf(false, true)
        for (lage in LangDrueckLage.entries) {
            for (vorne in jaNein) {
                for (erledigt in jaNein) {
                    for (geschwenkt in jaNein) {
                        for (snackbar in jaNein) {
                            val erwartet = lage == LangDrueckLage.ERKUNDEN && vorne && !erledigt &&
                                !geschwenkt && !snackbar
                            assertEquals(
                                erwartet,
                                sollLangDrueckHinweisZeigen(lage, vorne, erledigt, geschwenkt, snackbar),
                                "lage=$lage vorne=$vorne erledigt=$erledigt geschwenkt=$geschwenkt snackbar=$snackbar",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `erkunden auf ruhiger Karte zeigt den Tipp`() {
        assertTrue(sollLangDrueckHinweisZeigen(LangDrueckLage.ERKUNDEN, true, false, false, false))
    }

    @Test
    fun `planung, aufzeichnung und navigation zeigen ihn nie`() {
        for (lage in listOf(LangDrueckLage.PLANUNG, LangDrueckLage.AUFZEICHNUNG, LangDrueckLage.NAVIGATION)) {
            assertFalse(sollLangDrueckHinweisZeigen(lage, true, false, false, false), "$lage")
        }
    }

    @Test
    fun `offene Aufgabe beim Erkunden zeigt ihn nicht`() {
        assertFalse(sollLangDrueckHinweisZeigen(LangDrueckLage.AUFGABE, true, false, false, false))
    }

    @Test
    fun `im Hintergrund kommt er nicht`() {
        assertFalse(sollLangDrueckHinweisZeigen(LangDrueckLage.ERKUNDEN, false, false, false, false))
    }

    @Test
    fun `gesetzter Merker verhindert ihn fuer immer`() {
        assertFalse(sollLangDrueckHinweisZeigen(LangDrueckLage.ERKUNDEN, true, true, false, false))
    }

    @Test
    fun `direkt nach einem Schwenk kommt er nicht`() {
        assertFalse(sollLangDrueckHinweisZeigen(LangDrueckLage.ERKUNDEN, true, false, true, false))
    }

    @Test
    fun `eine andere Snackbar wird nicht verdraengt`() {
        assertFalse(sollLangDrueckHinweisZeigen(LangDrueckLage.ERKUNDEN, true, false, false, true))
    }

    // ---------------------------------------------------------- Gesehen

    @Test
    fun `regulaer beendeter Tipp gilt immer als gesehen`() {
        assertTrue(langDrueckHinweisGesehen(regulaerBeendet = true, sichtbarMs = 0L))
    }

    @Test
    fun `vorzeitig abgeraeumter Tipp gilt erst ab der Lesezeit als gesehen`() {
        assertFalse(langDrueckHinweisGesehen(regulaerBeendet = false, sichtbarMs = 300L))
        assertFalse(langDrueckHinweisGesehen(regulaerBeendet = false, sichtbarMs = LONG_PRESS_HINT_SEEN_MS - 1))
        assertTrue(langDrueckHinweisGesehen(regulaerBeendet = false, sichtbarMs = LONG_PRESS_HINT_SEEN_MS))
    }

    // ------------------------------------------------------------- Lage

    @Test
    fun `aufzeichnung geht allem vor`() {
        for (mode in MapMode.entries) {
            assertEquals(
                LangDrueckLage.AUFZEICHNUNG,
                langDrueckLage(mode, aufzeichnung = true, navigation = true, aufgabeOffen = true),
            )
        }
    }

    @Test
    fun `navigation geht planung und aufgaben vor`() {
        assertEquals(
            LangDrueckLage.NAVIGATION,
            langDrueckLage(MapMode.PLANEN, aufzeichnung = false, navigation = true, aufgabeOffen = true),
        )
        // Der Modus allein reicht auch, falls `navTarget` gerade wechselt.
        assertEquals(
            LangDrueckLage.NAVIGATION,
            langDrueckLage(MapMode.NAVIGIEREN, aufzeichnung = false, navigation = false, aufgabeOffen = false),
        )
    }

    @Test
    fun `planung geht einer offenen Aufgabe vor`() {
        assertEquals(
            LangDrueckLage.PLANUNG,
            langDrueckLage(MapMode.PLANEN, aufzeichnung = false, navigation = false, aufgabeOffen = true),
        )
    }

    @Test
    fun `erkunden mit offener Aufgabe ist keine ruhige Karte`() {
        assertEquals(
            LangDrueckLage.AUFGABE,
            langDrueckLage(MapMode.ERKUNDEN, aufzeichnung = false, navigation = false, aufgabeOffen = true),
        )
        assertEquals(
            LangDrueckLage.ERKUNDEN,
            langDrueckLage(MapMode.ERKUNDEN, aufzeichnung = false, navigation = false, aufgabeOffen = false),
        )
    }

    // ------------------------------------------------------------ Merker

    @Test
    fun `merker ist anfangs leer und bleibt nach dem Setzen gesetzt`() {
        val store = MemoryKeyValueStore()
        assertFalse(langDrueckHinweisErledigt(store))
        merkeLangDrueckHinweisErledigt(store)
        assertTrue(langDrueckHinweisErledigt(store))
        merkeLangDrueckHinweisErledigt(store)
        assertTrue(langDrueckHinweisErledigt(store))
    }

    @Test
    fun `tipp beschreibt die tatsaechliche Wirkung`() {
        // Beim Erkunden setzt der lange Druck einen Punkt und oeffnet die
        // Ortskarte mit „Route hierher" (Ziel) und „Runde ab hier" (Start) —
        // genau das muss der Tipp versprechen, nicht etwa einen Wegpunkt.
        assertTrue("Punkt" in LONG_PRESS_HINT_TEXT)
        assertTrue("Ziel" in LONG_PRESS_HINT_TEXT)
        assertTrue("Runde" in LONG_PRESS_HINT_TEXT)
        assertFalse("Wegpunkt" in LONG_PRESS_HINT_TEXT)
    }
}
