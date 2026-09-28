package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UhrPulsVorrangTest {

    @Test
    fun `ohne Telefonwert zaehlt die eigene Messung`() {
        assertTrue(eigenerPulsZaehlt(jetztMs = 1_000L, telefonPulsSeitMs = null))
    }

    @Test
    fun `frischer Telefonwert hat Vorrang`() {
        assertFalse(eigenerPulsZaehlt(jetztMs = 105_000L, telefonPulsSeitMs = 100_000L))
        assertFalse(eigenerPulsZaehlt(jetztMs = 100_000L + TELEFON_PULS_VORRANG_MS, telefonPulsSeitMs = 100_000L))
    }

    @Test
    fun `nach dem Fenster uebernimmt wieder die Uhr`() {
        assertTrue(eigenerPulsZaehlt(jetztMs = 100_001L + TELEFON_PULS_VORRANG_MS, telefonPulsSeitMs = 100_000L))
    }

    @Test
    fun `Zeitsprung rueckwaerts haelt den Telefonwert`() {
        assertFalse(eigenerPulsZaehlt(jetztMs = 90_000L, telefonPulsSeitMs = 100_000L))
    }
}
