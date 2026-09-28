package de.trailscape.app.strava

import de.trailscape.core.StravaAppCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StravaConfigTest {

    @Test
    fun `leer, nur Leerzeichen oder nur eine Haelfte ergibt keine Zugangsdaten`() {
        assertNull(stravaCredentialsOrNull("", ""))
        assertNull(stravaCredentialsOrNull("  ", " \t"))
        assertNull(stravaCredentialsOrNull("123", ""))
        assertNull(stravaCredentialsOrNull("", "geheim"))
    }

    @Test
    fun `beide Werte ergeben getrimmte Zugangsdaten`() {
        assertEquals(StravaAppCredentials("123", "abc"), stravaCredentialsOrNull(" 123 ", "abc\n"))
    }
}
