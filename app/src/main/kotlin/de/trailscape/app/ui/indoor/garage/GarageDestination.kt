package de.trailscape.app.ui.indoor.garage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.trailscape.app.data.PrefsKeyValueStore
import de.trailscape.app.data.trailscapePrefs
import de.trailscape.app.indoor.render.RenderGuard
import de.trailscape.core.garage.RiderAvatarStore

/**
 * Ziel `indoor/garage` im `NavHost`: liest den Avatar aus den Einstellungen,
 * speichert jede Wahl sofort ([RiderAvatarStore]) und zeigt [GarageScreen].
 * Die Darstellung liest ihn beim naechsten Laden einer Strecke neu.
 *
 * Erreicht wird die Seite ueber das Symbol in der Kopfzeile des Indoor-Tabs,
 * Sicht „Strecken" (`docs/ux/umbau.md`).
 *
 * Freischaltungen: weiter `progress = null`, also ist alles frei. Die Erfolge
 * sind inzwischen da; die Kopplung (Stufe und Abzeichen hereinreichen,
 * Kennungen in `GarageUnlocks` angleichen) ist bewusst noch nicht geschaltet
 * — sie wuerde Farben und Muster sperren, die bisher frei waren, und ist eine
 * eigene Entscheidung (`docs/indoor/garage.md`, „Freischaltungen").
 */
@Composable
fun GarageDestination(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RiderAvatarStore(PrefsKeyValueStore(trailscapePrefs(context.applicationContext))) }
    var avatar by remember { mutableStateOf(store.load()) }
    val safeMode = remember { runCatching { RenderGuard.isSafeMode(context) }.getOrDefault(false) }
    GarageScreen(
        avatar = avatar,
        onChange = {
            avatar = it
            store.save(it)
        },
        onBack = onBack,
        progress = null,
        safeMode = safeMode,
    )
}
