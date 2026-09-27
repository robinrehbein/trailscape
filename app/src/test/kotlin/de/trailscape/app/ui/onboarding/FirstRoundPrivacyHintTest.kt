package de.trailscape.app.ui.onboarding

import de.trailscape.app.R
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Der Datenschutzhinweis unter der ersten Runde nennt genau das, was gleich
 * passiert: die Standortabfrage nur ohne Freigabe, Open-Meteo nur mit
 * „Wind berücksichtigen". Vier ganze Saetze, damit keine Sprache Bruchstuecke
 * in fremder Reihenfolge zusammensetzen muss.
 */
class FirstRoundPrivacyHintTest {
    @Test
    fun `ohne Abfrage und ohne Wind nur der Routing-Server`() {
        assertEquals(R.string.shell_onboarding_first_round_privacy_hint, firstRoundPrivacyHint(false, false))
    }

    @Test
    fun `die Standortabfrage wird angekuendigt, wenn sie kommt`() {
        assertEquals(
            R.string.shell_onboarding_first_round_privacy_location_hint,
            firstRoundPrivacyHint(askLocation = true, windEnabled = false),
        )
    }

    @Test
    fun `mit Wind wird Open-Meteo genannt`() {
        assertEquals(
            R.string.shell_onboarding_first_round_privacy_wind_hint,
            firstRoundPrivacyHint(askLocation = false, windEnabled = true),
        )
        assertEquals(
            R.string.shell_onboarding_first_round_privacy_location_wind_hint,
            firstRoundPrivacyHint(askLocation = true, windEnabled = true),
        )
    }
}
