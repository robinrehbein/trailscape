package de.trailscape.app.ui.onboarding

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Kopfzeile der Einfuehrung zaehlt Schritte, nicht Seiten: Die
 * Willkommensseite ist kein Schritt, bei fuenf Seiten also „1 von 4" bis
 * „4 von 4" — passend zu den vier Punkten unten.
 *
 * Seit der Uebersetzung liefert [onboardingEyebrow] ein [UiText]; geprueft
 * werden Schluessel und Argumente („Schritt %1$d von %2$d" / „Step %1$d of
 * %2$d"). Die Saetze selbst prueft `ShellScreenshotTest` in beiden Sprachen.
 */
class OnboardingEyebrowTest {
    @Test
    fun `Willkommen zuerst, danach Schritt n von 4`() {
        assertEquals(UiText.Res(R.string.shell_onboarding_welcome_eyebrow), onboardingEyebrow(0, 5))
        assertEquals(UiText.Res(R.string.shell_onboarding_step_eyebrow, listOf(1, 4)), onboardingEyebrow(1, 5))
        assertEquals(UiText.Res(R.string.shell_onboarding_step_eyebrow, listOf(4, 4)), onboardingEyebrow(4, 5))
    }
}
