package de.trailscape.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.trailscape.app.ui.map.MapScreen
import de.trailscape.app.ui.more.MoreScreen
import de.trailscape.app.ui.onboarding.OnboardingScreen
import de.trailscape.app.ui.rides.RidesScreen
import de.trailscape.app.ui.training.TrainingScreen

/**
 * # Navigationshuelle der App — und die Zustaendigkeitsgrenzen dahinter
 *
 * Diese Datei ist **gemeinsames Fundament**. Sie enthaelt nur die
 * Navigationsleiste, den `NavHost` und den Aufruf der vier Screens. Wer an
 * einem einzelnen Screen arbeitet, aendert sie **nicht** — die vier Aufrufe
 * unten sind fest verabredete Signaturen:
 *
 * ```kotlin
 * MapScreen(appViewModel)
 * RidesScreen(appViewModel)
 * TrainingScreen(appViewModel)
 * MoreScreen(appViewModel)
 * ```
 *
 * ## Was wo liegt
 *
 * | Datei | Rolle |
 * |---|---|
 * | `ui/TrailscapeApp.kt` (diese Datei) | Fundament: Navigationsleiste und `NavHost` |
 * | `ui/AppViewModel.kt` | Fundament: die API ist der Vertrag aller Screens |
 * | `ui/TrainingInsights.kt` | Fundament: reine Rechenschicht ueber `:core` |
 * | `ui/MapStyles.kt` | Fundament: Katalog der Kartenstile |
 * | `ui/map/MapScreen.kt` | Karte, Planung, Navigation, Offline-Download |
 * | `ui/rides/RidesScreen.kt` | Tourenliste |
 * | `ui/training/TrainingScreen.kt` | Trainingsplan und Auswertung |
 * | `ui/more/MoreScreen.kt` | Einstellungen, Health, Backup, Sync |
 *
 * Neue Hilfs-Composables eines Screens gehoeren in **dessen** Paket
 * (`ui/map/…`, `ui/training/…`, `ui/more/…`) oder — wenn wirklich geteilt —
 * in `ui/components/`, nie in diese Datei.
 *
 * ## Was ein Screen braucht, holt er sich selbst
 *  * Context/Activity: `LocalContext.current`
 *  * Berechtigungen (Standort, Benachrichtigungen): im jeweiligen Screen, mit
 *    `rememberLauncherForActivityResult` — die Huelle fragt nichts an.
 *  * Aufzeichnungszustand: `de.trailscape.app.record.RecordingRepository`
 *  * Alles Uebrige (Touren, Profil, Auswertung, Plan, Kartenstil, Health,
 *    Sync): das **eine** geteilte [AppViewModel], das hier unten mit
 *    `viewModel()` im Activity-Scope erzeugt und als Parameter
 *    durchgereicht wird. Screens erzeugen **kein** eigenes ViewModel.
 *
 * ## Erststart
 * Beim allerersten Start liegt vor den vier Tabs die Einfuehrung
 * (`ui/onboarding/OnboardingScreen.kt`). Ob sie faellig ist, entscheidet
 * [AppViewModel.onboardingVisible] — der Zustand kommt aus den
 * SharedPreferences, nicht aus dieser Datei. Solange sie laeuft, gibt es keine
 * Navigationsleiste: Die Einfuehrung ist kein Tab, sondern ein Zustand davor.
 *
 * ## Tab-Wechsel aus einem Screen heraus
 * Statt eines `onShowMap`-Callbacks (so machte es die Flutter-App) ruft ein
 * Screen `appViewModel.requestTab(AppTab.MAP)`; die Huelle beobachtet
 * [AppViewModel.tabRequest] und navigiert. Deshalb kommen alle vier Screens
 * mit derselben, parameterlosen Signatur aus.
 */

/**
 * Die vier Hauptbereiche — bewusst identisch zur Navigationsleiste der
 * Flutter-App, damit der Rewrite fuer Nutzer nicht wie eine andere App wirkt.
 */
private enum class TopLevelDestination(
    val tab: AppTab,
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    MAP(AppTab.MAP, "karte", "Karte", Icons.Filled.Place),
    TOURS(AppTab.RIDES, "touren", "Touren", Icons.AutoMirrored.Filled.List),
    TRAINING(AppTab.TRAINING, "training", "Training", Icons.Filled.Favorite),
    MORE(AppTab.MORE, "mehr", "Mehr", Icons.Filled.Settings),
}

@Composable
fun TrailscapeApp() {
    // Activity-Scope: `viewModel()` ohne eigenen Store-Owner nimmt die
    // Activity als Owner — genau eine Instanz fuer alle vier Tabs, die
    // Tabwechsel und Konfigurationsaenderungen ueberlebt.
    val appViewModel: AppViewModel = viewModel()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val tabRequest by appViewModel.tabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        val requested = tabRequest ?: return@LaunchedEffect
        val target = TopLevelDestination.entries.first { it.tab == requested }
        navController.navigateToTab(target.route)
        appViewModel.consumeTabRequest()
    }

    val onboardingVisible by appViewModel.onboardingVisible.collectAsStateWithLifecycle()
    if (onboardingVisible) {
        OnboardingScreen(appViewModel)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == destination.route
                    } == true

                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(destination.route) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.MAP.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.MAP.route) { MapScreen(appViewModel) }
            composable(TopLevelDestination.TOURS.route) { RidesScreen(appViewModel) }
            composable(TopLevelDestination.TRAINING.route) { TrainingScreen(appViewModel) }
            composable(TopLevelDestination.MORE.route) { MoreScreen(appViewModel) }
        }
    }
}

/**
 * Wechselt zu einem Tab, ohne den Backstack wachsen zu lassen — derselbe
 * Weg fuer die Navigationsleiste und fuer [AppViewModel.requestTab].
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
