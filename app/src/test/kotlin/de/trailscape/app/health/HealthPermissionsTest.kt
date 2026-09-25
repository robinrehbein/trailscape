package de.trailscape.app.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Die Historien-Freigabe ist optional: Sie darf nie in [HealthPermissions.required]
 * landen, sonst gaelte eine Verbindung ohne sie als „nicht verbunden".
 */
class HealthPermissionsTest {

    @Test
    fun `Historien-Freigabe traegt den offiziellen Namen und ist kein Pflichtrecht`() {
        assertEquals(
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
            HealthPermissions.READ_HEALTH_DATA_HISTORY,
        )
        assertFalse(HealthPermissions.required.contains(HealthPermissions.READ_HEALTH_DATA_HISTORY))
    }

    @Test
    fun `Verbindung gilt ohne Zusatzrechte als hergestellt`() {
        assertTrue(HealthPermissions.hasAllRequired(HealthPermissions.required))
        assertFalse(HealthPermissions.hasAllRequired(HealthPermissions.optional))
    }
}
