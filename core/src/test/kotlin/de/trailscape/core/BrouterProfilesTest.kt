package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/brouter_profiles.dart`.
 *
 * Fuer `brouter_profiles.dart` selbst existiert kein eigener Dart-Testfile;
 * die `gravelProfileText`-Faelle stammen 1:1 aus der `gravelProfileText`-Gruppe
 * in `test/routing_test.dart` (dort mitgetestet, weil `routing.dart` das
 * eingebettete Profil konsumiert) — gleiche Faelle, gleiche Erwartungswerte.
 */
class BrouterProfilesTest {
    @Test
    fun `gravelProfileText schaltet prefer_unpaved_paths auf true`() {
        val text = gravelProfileText()
        assertTrue(text.contains("assign prefer_unpaved_paths true"))
        assertFalse(text.contains("assign prefer_unpaved_paths false"))
    }

    @Test
    fun `gravelProfileText laesst den Rest des Profils intakt`() {
        val text = gravelProfileText()
        assertTrue(text.startsWith("#"), "Header muss erhalten sein")
        assertTrue(text.contains("gravel.brf"))
        assertTrue(text.contains("---context:global"))
        assertEquals(GRAVEL_BRF.length - 1, text.length)
    }

    @Test
    fun `GRAVEL_BRF ist wortwoertlich das offizielle Profil`() {
        assertTrue(GRAVEL_BRF.startsWith("# \"gravel.brf\" -- Version 28.04.2024"))
        assertTrue(GRAVEL_BRF.contains("---context:way"))
        assertTrue(GRAVEL_BRF.contains("---context:node"))
        assertEquals(19121, GRAVEL_BRF.length)
    }
}
