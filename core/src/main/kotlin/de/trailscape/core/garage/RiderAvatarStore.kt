package de.trailscape.core.garage

import de.trailscape.core.KeyValueStore

/**
 * Speichert den [RiderAvatar] als kompaktes JSON unter [KEY]. Ohne Eintrag oder
 * bei unlesbarem Inhalt gilt [RiderAvatar.DEFAULT]. Wirft nie.
 */
class RiderAvatarStore(private val store: KeyValueStore) {
    fun load(): RiderAvatar = runCatching { RiderAvatar.parse(store.getString(KEY)) }.getOrNull() ?: RiderAvatar.DEFAULT

    fun save(avatar: RiderAvatar) {
        runCatching { store.setString(KEY, avatar.toJson()) }
    }

    /** Ob je etwas gespeichert wurde (fuer das Backup: nur dann ein Eintrag `avatar`). */
    fun hasSaved(): Boolean = runCatching { store.getString(KEY) != null }.getOrDefault(false)

    companion object {
        const val KEY = "garage_avatar"
    }
}
