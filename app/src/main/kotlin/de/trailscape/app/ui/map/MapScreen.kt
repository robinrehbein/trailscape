package de.trailscape.app.ui.map

import androidx.compose.runtime.Composable
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.PlaceholderScreen

/**
 * PLATZHALTER — gehoert dem Karten-Agenten.
 *
 * Diese Datei wird als Ganzes durch die echte Karte ersetzt (MapLibre,
 * Aufzeichnung, Routing, Navigation, Berechtigungen fuer Standort und
 * Benachrichtigungen). Die Signatur `MapScreen(appViewModel: AppViewModel)`
 * ist fest — `TrailscapeApp.kt` ruft genau so auf und darf dabei nicht
 * angefasst werden. Alles Weitere holt sich der Screen selbst:
 * `LocalContext.current` fuer Context/Activity, `RecordingRepository` fuer die
 * laufende Aufzeichnung, `appViewModel.mapStyle` fuer den Kartenstil,
 * `appViewModel.selectedRide` fuer die anzuzeigende Tour und
 * `appViewModel.requestTab(...)` fuer einen Tab-Wechsel.
 *
 * Der Stil-Katalog liegt bewusst ausserhalb dieser Datei in
 * `ui/MapStyles.kt` (inklusive `MapStyle.toRasterStyleJson()`), damit er den
 * Austausch dieser Datei ueberlebt.
 */
@Composable
fun MapScreen(appViewModel: AppViewModel) {
    PlaceholderScreen(
        title = "Karte",
        hint = "Der Karten-Screen wird gerade gebaut.",
    )
}
