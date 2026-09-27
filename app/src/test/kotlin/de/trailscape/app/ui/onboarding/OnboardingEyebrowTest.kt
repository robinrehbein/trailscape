package de.trailscape.app.ui.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Kopfzeile der Einfuehrung zaehlt Schritte, nicht Seiten: Die
 * Willkommensseite ist kein Schritt, bei fuenf Seiten also „1 von 4" bis
 * „4 von 4" — passend zu den vier Punkten unten.
 */
class OnboardingEyebrowTest {
    @Test
    fun `Willkommen zuerst, danach Schritt n von 4`() {
        assertEquals("Willkommen", onboardingEyebrow(0, 5))
        assertEquals("Schritt 1 von 4", onboardingEyebrow(1, 5))
        assertEquals("Schritt 4 von 4", onboardingEyebrow(4, 5))
    }
}
