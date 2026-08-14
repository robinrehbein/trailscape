package de.trailscape.app.reminder

import de.trailscape.core.KeyValueStore
import de.trailscape.core.ReminderSettings
import de.trailscape.core.ReminderState
import de.trailscape.core.reminderSettingsStorageKey
import de.trailscape.core.reminderStateStorageKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Persistenz der Erinnerungs-Einstellungen und des Meldestands.
 *
 * Sitzt auf demselben [KeyValueStore] wie Profil, Kartenstil und
 * Sync-Zugangsdaten (siehe `data/PrefsStores.kt`) — kein eigener
 * `SharedPreferences`-Namensraum und keine zweite Datei fuer drei Schalter und
 * zwei Uhrzeiten. Das JSON-Format samt seiner Nachsicht gegenueber fehlenden
 * Feldern liegt in `:core` ([ReminderSettings.fromJson]).
 *
 * Alle Methoden sind synchron wie bei [de.trailscape.app.data.RideStorage]:
 * Der Aufrufer sorgt fuer den Wechsel auf [kotlinx.coroutines.Dispatchers.IO].
 *
 * Lesen wirft nie: Ein defekter Eintrag liefert die Vorgabe — im schlimmsten
 * Fall bleibt eine Erinnerung aus, was allemal besser ist als ein Absturz
 * beim App-Start oder im Hintergrundlauf.
 */
class ReminderStore(private val store: KeyValueStore) {

    fun readSettings(): ReminderSettings = runCatching {
        val raw = store.getString(reminderSettingsStorageKey) ?: return ReminderSettings()
        ReminderSettings.fromJson(Json.parseToJsonElement(raw) as JsonObject)
    }.getOrDefault(ReminderSettings())

    fun writeSettings(settings: ReminderSettings) {
        runCatching { store.setString(reminderSettingsStorageKey, settings.toJson().toString()) }
    }

    fun readState(): ReminderState = runCatching {
        val raw = store.getString(reminderStateStorageKey) ?: return ReminderState()
        ReminderState.fromJson(Json.parseToJsonElement(raw) as JsonObject)
    }.getOrDefault(ReminderState())

    fun writeState(state: ReminderState) {
        runCatching { store.setString(reminderStateStorageKey, state.toJson().toString()) }
    }
}
