package de.trailscape.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiNavigationBar
import de.trailscape.app.ui.components.OneUiNavigationBarDefaults
import de.trailscape.app.ui.components.OneUiNavigationBarItem
import de.trailscape.app.ui.map.MapScreen
import de.trailscape.app.ui.more.MoreScreen
import de.trailscape.app.ui.onboarding.OnboardingScreen
import de.trailscape.app.ui.today.TodayScreen
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
 * TodayScreen(appViewModel)
 * MapScreen(appViewModel)
 * TrainingScreen(appViewModel)
 * MoreScreen(appViewModel)
 * ```
 *
 * ## Warum Touren und Karte eine Seite sind
 * Eine Tour ist zuerst und vor allem eine Linie auf der Karte — eine eigene
 * Liste daneben duplizierte dieselbe Information nur in Textform. Die
 * Navigationsleiste war ausserdem mit fuenf Zielen bereits voll (siehe unten,
 * „Vier Tabs sind das Maximum" — vorher „Fuenf"): Ein Ziel, das man auch als
 * Aufsatz auf ein bestehendes bauen kann, verdient keinen eigenen Platz in
 * einer schon vollen Kapsel. Die Tourenliste liegt deshalb als Blatt
 * (`ui/map/TourSheet.kt`) ueber der Karte — genau derselbe Baustein wie das
 * Planungsblatt, nur mit anderem Inhalt. Der Baustein der Liste selbst
 * (`ui/rides/TourList.kt`, `TourListContent`) ist damit kein eigener Screen
 * mehr, sondern ein Zulieferer des Karten-Screens.
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
 * | `ui/map/MapScreen.kt` | Karte, Tourenblatt, Planung, Navigation, Offline-Download |
 * | `ui/rides/TourList.kt` | Baustein: Tourenliste und -detail im Blatt der Karte |
 * | `ui/training/TrainingScreen.kt` | Trainingsplan und Auswertung |
 * | `ui/more/MoreScreen.kt` | Einstellungen, Health, Backup, Sync |
 * | `ui/components/OneUiNavigationBar.kt` | Fundament: die schwebende Navigationskapsel |
 *
 * ## Die Leiste schwebt — was das fuer einen Screen bedeutet
 * Die Navigationskapsel liegt im One-UI-Stil **ueber** dem Inhalt und belegt
 * keine Layout-Hoehe. Jeder Bildschirm haelt seinen unteren Rand deshalb selbst
 * frei: `screenContentPadding()` fuer Listen,
 * `LocalFloatingNavigationBarSpace.current` fuer alles, was sonst unten steht
 * (schwebende Knoepfe, Kartenpanels, `SnackbarHost`). Wer das vergisst, baut
 * ein Bedienelement hinter die Kapsel.
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
 *
 * [AppTab.RIDES] ist dabei eine Ausnahme, die kein eigenes Ziel mehr hat:
 * Screens ausserhalb dieser Datei (`TodayScreen`, das Tourenblatt selbst)
 * fragen weiterhin nach „dem Touren-Tab" — dieser Aufzaehlungswert bleibt
 * deshalb bestehen, damit ihr Aufruf nicht geaendert werden muss. Die Huelle
 * loest ihn beim Auftreffen aber auf die Route „karte" auf und bittet
 * zusaetzlich per [AppViewModel.requestTourSheet] darum, das Tourenblatt
 * aufzuschlagen (siehe `LaunchedEffect(tabRequest)` unten).
 */

/**
 * Die vier Hauptbereiche in der Reihenfolge der Navigationsleiste.
 *
 * ## Warum „Heute" vorne steht
 * Die App startete bisher auf der Karte. Wer sie morgens oeffnete, bekam damit
 * ein Werkzeug zu sehen, aber keine Auskunft: Die Verbindung aus
 * Ruhepuls/HRV/Schlaf, Trainingsplan und automatisch generierter Runde — das
 * Alleinstellungsmerkmal dieser App — lag drei Tabs weiter hinten am Ende einer
 * Karte. `HOME` ist deshalb das erste Ziel und die `startDestination`.
 *
 * ## Vier Tabs sind das Maximum
 * Die schwebende Kapsel (`ui/components/OneUiNavigationBar.kt`) war mit fuenf
 * Zielen bereits voll ausgereizt — „Touren" ist deshalb keine eigene
 * Kapsel-Position mehr, sondern das Blatt ueber der Karte (siehe oben, „Warum
 * Touren und Karte eine Seite sind"). Die verbleibenden Beschriftungen bleiben
 * kurz (das laengste ist „Training"), und jedes Label ist einzeilig mit
 * Ellipse gesetzt — auf einem 320-dp-Geraet bleiben je Ziel deutlich mehr als
 * die frueheren 60 dp, in denen nichts abgeschnitten aussehen darf.
 */
private enum class TopLevelDestination(
    val tab: AppTab,
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(AppTab.HOME, "heute", "Heute", Icons.Filled.Today),
    MAP(AppTab.MAP, "karte", "Karte", Icons.Filled.Place),
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
        // AppTab.RIDES hat seit dem Zusammenlegen von Touren und Karte kein
        // eigenes Ziel mehr in TopLevelDestination — `entries.first` wuerde
        // dafuer werfen. Aufrufer (TodayScreen, das Tourenblatt) kennen und
        // brauchen diesen Unterschied nicht: Sie bitten weiterhin um den
        // „Touren-Tab", die Huelle loest das hier auf die Karte auf und
        // bittet den Karten-Screen zusaetzlich, sein Tourenblatt
        // aufzuschlagen (siehe [AppViewModel.requestTourSheet]).
        if (requested == AppTab.RIDES) {
            navController.navigateToTab(TopLevelDestination.MAP.route)
            appViewModel.requestTourSheet()
        } else {
            val target = TopLevelDestination.entries.first { it.tab == requested }
            navController.navigateToTab(target.route)
        }
        appViewModel.consumeTabRequest()
    }

    val onboardingVisible by appViewModel.onboardingVisible.collectAsStateWithLifecycle()
    if (onboardingVisible) {
        OnboardingScreen(appViewModel)
        return
    }

    // Bodenfreiheit, die jeder Bildschirm unten einplanen muss: die Kapsel samt
    // ihrer Raender plus die Gestenleiste des Systems. Das Inset wird hier
    // gelesen und NICHT verzehrt — die Kapsel selbst legt es sich noch einmal
    // an, und die Bildschirme brauchen es als Zahl, nicht als Padding.
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navigationBarSpace = OneUiNavigationBarDefaults.OverlaySpace + systemBottomInset

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFloatingNavigationBarSpace provides navigationBarSpace) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.HOME.route,
                // Oben und seitlich loest die Huelle die System-Insets auf (das
                // taten vorher die `innerPadding` des `Scaffold`); unten
                // ausdruecklich nicht — dort soll der Inhalt bis unter die
                // Kapsel laufen. `imePadding` haelt Eingabefelder ueber der
                // Tastatur, was zuvor am `contentWindowInsets` des Scaffold hing.
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                        ),
                    )
                    .imePadding(),
            ) {
                composable(TopLevelDestination.HOME.route) { TodayScreen(appViewModel) }
                composable(TopLevelDestination.MAP.route) { MapScreen(appViewModel) }
                composable(TopLevelDestination.TRAINING.route) { TrainingScreen(appViewModel) }
                composable(TopLevelDestination.MORE.route) { MoreScreen(appViewModel) }
            }
        }

        // Die schwebende One-UI-Kapsel liegt ueber dem Inhalt (siehe
        // `components/OneUiNavigationBar.kt`) — deshalb `Box` statt
        // `Scaffold(bottomBar = …)`: Ein bottomBar-Slot wuerde ihr Hoehe im
        // Layout zuweisen, und genau das soll sie nicht haben.
        OneUiNavigationBar(modifier = Modifier.align(Alignment.BottomCenter)) {
            TopLevelDestination.entries.forEach { destination ->
                val selected = currentDestination?.hierarchy?.any {
                    it.route == destination.route
                } == true

                OneUiNavigationBarItem(
                    selected = selected,
                    onClick = { navController.navigateToTab(destination.route) },
                    icon = destination.icon,
                    label = destination.label,
                )
            }
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
