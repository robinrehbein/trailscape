package de.trailscape.app.indoor.render

import android.content.Context
import de.trailscape.app.data.PrefsKeyValueStore
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.garage.RiderAvatarStore
import de.trailscape.core.indoor.world.WorldModels

/** Das Aussehen des eigenen Fahrers aus der Garage; ohne Eintrag die Vorgabe. Wirft nie. */
internal fun loadOwnAvatar(context: Context): RiderAvatar =
    runCatching { RiderAvatarStore(PrefsKeyValueStore(trailscapePrefs(context))).load() }.getOrDefault(RiderAvatar.DEFAULT)

/** Ein Fahrermodell aus `assets/indoor/models/` oder `null`, wenn es fehlt. */
internal fun readRiderModel(context: Context, name: String): ByteArray? =
    runCatching { context.assets.open("${WorldModels.ASSET_DIR}/$name").use { it.readBytes() } }.getOrNull()
