package de.trailscape.app.ui.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import de.trailscape.app.health.toHealthRoutePoints
import de.trailscape.app.ui.AppViewModel
import de.trailscape.core.RouteConsentRequest
import kotlinx.coroutines.launch

/**
 * Einstieg fuer die Einzel-Freigabe einer GPS-Route aus Health Connect.
 *
 * Liefert eine Funktion, die fuer eine offene Freigabe aus
 * [AppViewModel.routeConsentPending] den Health-Connect-Dialog
 * (`ExerciseRouteRequestContract`) zeigt. Gibt die Nutzerin die Route frei,
 * kommt sie direkt als Ergebnis zurueck und wird ueber
 * [AppViewModel.applyConsentedRoute] in die importierte Tour eingetragen
 * (Trackpunkte, Hoehenmeter, Puls je Punkt). Bei Abbruch bleibt die Freigabe
 * offen.
 *
 * Gedacht fuer die Health-Seite der Einstellungen, etwa:
 * ```kotlin
 * val pending by appViewModel.routeConsentPending.collectAsState()
 * val requestRoute = rememberRouteConsentLauncher(appViewModel)
 * pending.firstOrNull()?.let { first ->
 *     Button(onClick = { requestRoute(first) }) { Text("Route freigeben (${pending.size})") }
 * }
 * ```
 *
 * Tipp fuer die Nutzerin daneben: In Health Connect unter App-Berechtigungen
 * → Trailscape → Trainingsrouten „Immer erlauben" waehlen, dann entfaellt der
 * Dialog fuer kuenftige Touren ganz.
 */
@Composable
fun rememberRouteConsentLauncher(appViewModel: AppViewModel): (RouteConsentRequest) -> Unit {
    val scope = rememberCoroutineScope()
    // Der Contract kennt nur die Session-ID; welche Tour dazugehoert, muss
    // ueber den Dialog hinweg gemerkt werden — saveable, weil Android die
    // Activity waehrend des Dialogs neu aufbauen darf.
    var inFlight by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        val sessionId = inFlight ?: return@rememberLauncherForActivityResult
        inFlight = null
        val request = appViewModel.routeConsentPending.value.firstOrNull { it.sessionId == sessionId }
            ?: return@rememberLauncherForActivityResult
        scope.launch {
            appViewModel.applyConsentedRoute(request, route?.toHealthRoutePoints())
        }
    }
    return { request ->
        inFlight = request.sessionId
        try {
            launcher.launch(request.sessionId)
        } catch (_: Exception) {
            // Health Connect fehlt oder ist zu alt fuer den Dialog.
            inFlight = null
            appViewModel.showMessage("Der Freigabedialog von Health Connect lässt sich nicht öffnen.")
        }
    }
}
