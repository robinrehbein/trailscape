package de.trailscape.app.ui.more

import androidx.compose.runtime.Composable
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.PlaceholderScreen

/**
 * PLATZHALTER — gehoert dem Mehr-Agenten.
 *
 * Diese Datei wird als Ganzes durch den echten Mehr-Screen ersetzt (Profil,
 * Health-Connect-Status und -Diagnose, Kartenstil-Auswahl, Kachel-Vorrat,
 * Selfhost-Sync, Backup-Import/-Export). Die Signatur
 * `MoreScreen(appViewModel: AppViewModel)` ist fest — `TrailscapeApp.kt` ruft
 * genau so auf und darf dabei nicht angefasst werden.
 *
 * Alles Noetige haengt am ViewModel: `profile`/`setProfile`,
 * `healthConnection`/`refreshHealthConnection`/`requestHealthPermissions`/
 * `syncHealthNow`/`lastSyncReport`/`vitals`, `mapStyle`/`setMapStyle`
 * (Katalog: `de.trailscape.app.ui.mapStyles`), `syncConfig`/`setSyncConfig`/
 * `syncNow`.
 */
@Composable
fun MoreScreen(appViewModel: AppViewModel) {
    PlaceholderScreen(
        title = "Mehr",
        hint = "Der Mehr-Screen wird gerade gebaut.",
    )
}
