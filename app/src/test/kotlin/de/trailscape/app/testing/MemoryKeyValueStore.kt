package de.trailscape.app.testing

import de.trailscape.core.KeyValueStore

/**
 * [KeyValueStore] im Speicher — der Ersatz fuer `SharedPreferences` im Test.
 *
 * Liegt in einem eigenen Test-Paket, weil ihn Tests aus verschiedenen
 * Bereichen brauchen (Routing-Segmente, Karten-Merker); vorher lag er in
 * `SegmentInventoryTest.kt`, und jeder andere Nutzer musste ein fremdes
 * Testpaket importieren.
 */
internal class MemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun setString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
