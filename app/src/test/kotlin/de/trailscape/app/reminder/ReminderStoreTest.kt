package de.trailscape.app.reminder

import de.trailscape.core.KeyValueStore
import de.trailscape.core.ReminderSettings
import de.trailscape.core.ReminderState
import de.trailscape.core.reminderSettingsStorageKey
import de.trailscape.core.reminderStateStorageKey
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests der Ablage von Erinnerungs-Einstellungen und Meldestand.
 *
 * Laufen wie die uebrigen `:app`-Tests ohne Robolectric: [ReminderStore] hat
 * keinen Android-Import, der Speicher kommt als Map herein. Geprueft wird
 * genau das, was `:core` nicht abdecken kann — der Weg durch den
 * [KeyValueStore] und das Verhalten bei unlesbarem Inhalt.
 */
class ReminderStoreTest {

    private class FakeStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun setString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun `ohne gespeicherten Eintrag gilt die Vorgabe`() {
        val store = ReminderStore(FakeStore())
        assertEquals(ReminderSettings(), store.readSettings())
        assertEquals(ReminderState(), store.readState())
    }

    @Test
    fun `Einstellungen kommen unveraendert zurueck`() {
        val backing = FakeStore()
        val store = ReminderStore(backing)
        val settings = ReminderSettings(
            dailySessionEnabled = true,
            nudgeEnabled = true,
            dailySessionTime = LocalTime.of(6, 15),
            weeklyReviewTime = LocalTime.of(20, 0),
        )

        store.writeSettings(settings)

        assertEquals(settings, store.readSettings())
        assertEquals(setOf(reminderSettingsStorageKey), backing.values.keys)
    }

    @Test
    fun `Meldestand kommt unveraendert zurueck`() {
        val backing = FakeStore()
        val store = ReminderStore(backing)
        val state = ReminderState(
            lastDailySessionOn = LocalDate.of(2026, 8, 13),
            lastNudgeOn = LocalDate.of(2026, 8, 9),
        )

        store.writeState(state)

        assertEquals(state, store.readState())
        assertEquals(setOf(reminderStateStorageKey), backing.values.keys)
    }

    @Test
    fun `unlesbarer Inhalt fuehrt auf die Vorgabe, nicht in einen Absturz`() {
        val backing = FakeStore()
        backing.values[reminderSettingsStorageKey] = "kein JSON"
        backing.values[reminderStateStorageKey] = "[1,2,3]"
        val store = ReminderStore(backing)

        assertEquals(ReminderSettings(), store.readSettings())
        assertEquals(ReminderState(), store.readState())
    }
}
