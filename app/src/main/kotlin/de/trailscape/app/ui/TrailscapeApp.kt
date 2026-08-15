package de.trailscape.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import de.trailscape.app.ui.today.TodayScreen
import de.trailscape.app.ui.training.TrainingScreen

/**
 * # Navigationshuelle der App — und die Zustaendigkeitsgrenzen dahinter
 *
 * Diese Datei ist **gemeinsames Fundament**. Sie enthaelt nur die
 * Navigationsleiste, den `NavHost` und den Aufruf der fuenf Screens. Wer an
 * einem einzelnen Screen arbeitet, aendert sie **nicht** — die fuenf Aufrufe
 * unten sind fest verabredete Signaturen:
 *
 * ```kotlin
 * TodayScreen(appViewModel)
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
 * | `ui/today/TodayScreen.kt` | Startseite: Tagesempfehlung, Woche, letzte Tour |
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
 * Beim allerersten Start liegt vor den fuenf Tabs die Einfuehrung
 * (`ui/onboarding/OnboardingScreen.kt`). Ob sie faellig ist, entscheidet
 * [AppViewModel.onboardingVisible] — der Zustand kommt aus den
 * SharedPreferences, nicht aus dieser Datei. Solange sie laeuft, gibt es keine
 * Navigationsleiste: Die Einfuehrung ist kein Tab, sondern ein Zustand davor.
 *
 * ## Tab-Wechsel aus einem Screen heraus
 * Statt eines `onShowMap`-Callbacks (so machte es die Flutter-App) ruft ein
 * Screen `appViewModel.requestTab(AppTab.MAP)`; die Huelle beobachtet
 * [AppViewModel.tabRequest] und navigiert. Deshalb kommen alle fuenf Screens
 * mit derselben, parameterlosen Signatur aus.
 */

/**
 * Die fuenf Hauptbereiche in der Reihenfolge der Navigationsleiste.
 *
 * ## Warum „Heute" vorne steht
 * Die App startete bisher auf der Karte. Wer sie morgens oeffnete, bekam damit
 * ein Werkzeug zu sehen, aber keine Auskunft: Die Verbindung aus
 * Ruhepuls/HRV/Schlaf, Trainingsplan und automatisch generierter Runde — das
 * Alleinstellungsmerkmal dieser App — lag drei Tabs weiter hinten am Ende einer
 * Karte. `HOME` ist deshalb das erste Ziel und die `startDestination`.
 *
 * ## Fuenf Tabs sind das Maximum
 * Material 3 laesst in der `NavigationBar` bis zu fuenf Ziele zu; damit ist die
 * Leiste jetzt voll. Die Beschriftungen bleiben kurz (das laengste ist
 * „Training"), und jedes Label ist einzeilig mit Ellipse gesetzt — auf einem
 * 320-dp-Geraet bleiben je Ziel rund 64 dp, in denen nichts abgeschnitten
 * aussehen darf. Ein sechstes Ziel braeuchte ein anderes Muster.
 */
private enum class TopLevelDestination(
    val tab: AppTab,
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(AppTab.HOME, "heute", "Heute", Icons.Filled.Today),
    MAP(AppTab.MAP, "karte", "Karte", Icons.Filled.Place),
    TOURS(AppTab.RIDES, "touren", "Touren", Icons.AutoMirrored.Filled.List),
    TRAINING(AppTab.TRAINING, "training", "Training", Icons.Filled.Favorite),
    MORE(AppTab.MORE, "mehr", "Mehr", Icons.Filled.Settings),
}

@Composable
fun TrailscapeApp() {
    // Activity-Scope: `viewModel()` ohne eigenen Store-Owner nimmt die
    // Activity als Owner — genau eine Instanz fuer alle fuenf Tabs, die
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
            // One-UI-8.5/9-Dock: Die Navigation liegt nicht mehr als Kante an
            // Kante unten, sondern schwebt als Pille mit weichem Schatten
            // ueber dem Boden — dasselbe Muster wie Galaxy Store, Dialer und
            // Gallery seit One UI 8.5. Der Schatten gehoert dazu: Schwebende
            // Container tragen dort Drop-Shadow, nur eingelassene Listen sind
            // flach. Inset-Padding laesst die Pille ueber der Gestenleiste
            // schweben, ohne dass der Inhalt dahinter durchschimmert.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 6.dp,
                ) {
                    NavigationBar(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        tonalElevation = 0.dp,
                    ) {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == destination.route
                            } == true

                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTab(destination.route) },
                                icon = { Icon(destination.icon, contentDescription = null) },
                                modifier = Modifier,
                                label = {
                                    // Einzeilig mit Ellipse: Mit fuenf Zielen wird der
                                    // Platz je Beschriftung eng, und ein umgebrochenes
                                    // Label wuerde die Leiste hoeher machen als alle
                                    // anderen Bildschirme sie erwarten.
                                    Text(
                                        text = destination.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.HOME.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.HOME.route) { TodayScreen(appViewModel) }
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
