package de.trailscape.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Today
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.AppFormats
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.languageOf
import de.trailscape.app.indoor.render.IndoorDiagnosticsScreen
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.ui.components.AtlasNavigationRail
import de.trailscape.app.ui.components.AtlasNavigationRailItem
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiNavigationBar
import de.trailscape.app.ui.components.OneUiNavigationBarDefaults
import de.trailscape.app.ui.components.OneUiNavigationBarItem
import de.trailscape.app.ui.components.RecButtonState
import de.trailscape.app.ui.components.RecCapsuleButton
import de.trailscape.app.ui.components.RecCapsuleButtonDefaults
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.indoor.IndoorFlowMode
import de.trailscape.app.ui.indoor.IndoorFlowScreen
import de.trailscape.app.ui.indoor.IndoorTabScreen
import de.trailscape.app.ui.indoor.IndoorWorldsDestination
import de.trailscape.app.ui.indoor.garage.GarageDestination
import de.trailscape.app.ui.map.MapScreen
import de.trailscape.app.ui.more.MoreScreen
import de.trailscape.app.ui.onboarding.OnboardingScreen
import de.trailscape.app.ui.rides.RidesScreen
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.M3Transitions
import de.trailscape.app.ui.theme.useNavigationRail
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.app.ui.today.TodayScreen
import de.trailscape.core.i18n.coreTexts

/**
 * # Navigationshuelle der App — und die Zustaendigkeitsgrenzen dahinter
 *
 * Diese Datei ist **gemeinsames Fundament**. Sie enthaelt nur die
 * Navigationsleiste samt dem Aufnahme-Knopf daneben, den `NavHost` und den
 * Aufruf der Screens. Wer an einem einzelnen Screen arbeitet, aendert sie
 * **nicht** — die Aufrufe unten sind fest verabredete Signaturen:
 *
 * ```kotlin
 * TodayScreen(appViewModel)
 * MapScreen(appViewModel)
 * IndoorTabScreen(appViewModel, onOpenDiagnostics = …, onOpenWorlds = …, onOpenGarage = …)
 * RidesScreen(appViewModel)
 * MoreScreen(appViewModel, onBack = …)
 * ```
 *
 * ## Die Struktur: Heute · Karte · Indoor · Verlauf
 * Grundlage ist die Seitenkarte (`docs/ux/seitenkarte.md`); was umgesetzt ist
 * und wo Kommendes eingehaengt wird, steht in `docs/ux/umbau.md`. **Was wohin
 * fuehrt, entscheidet `ui/AppNavigation.kt`** — reine, getestete Logik
 * ([SHELL_TABS], [navCommandFor], [navigationBarHidden]); diese Datei fuehrt
 * es nur aus.
 *
 *  * **Heute** beantwortet „Was fahre ich heute?".
 *  * **Karte** ist alles fuer draussen: Ort suchen, Route planen, Runde bauen.
 *  * **Indoor** ersetzt den frueheren Tab „Training" und buendelt Strecken,
 *    den Trainingsplan und den Start der Indoor-Fahrt
 *    (`ui/indoor/IndoorTabScreen.kt`). Vorher war der Indoor-Bereich nur ueber
 *    eine Karte auf „Heute", einen Textknopf im Bereit-Dialog und eine Zeile
 *    in den Einstellungen erreichbar.
 *  * **Verlauf** ist der Blick zurueck: Touren und Fortschritt als Segmente
 *    (`ui/rides/RidesScreen.kt`).
 *  * **Die Einstellungen liegen hinterm Zahnrad** — auf allen vier Tabs, immer
 *    dieselbe Seite, ein gepushtes Ziel (Route [AppRoutes.MORE]) mit
 *    Zurueck-Pfeil und Systemzurueckgeste.
 *  * **Fahren ist kein Ziel, sondern ein Zustand.** Rechts neben der Kapsel
 *    schwebt der runde Aufnahme-Knopf ([RecCapsuleButton]) — abgesetzt, kein
 *    fuenfter Tab. Seine drei Zustaende (Ruhe, „Route bereit", „laeuft")
 *    bildet diese Datei aus [RecordingRepository] und
 *    [AppViewModel.plannedRouteKm]; was ein Tipp bewirkt, steht in
 *    `ui/ReadyToRideDialog.kt`.
 *
 * ## Was wo liegt
 *
 * | Datei | Rolle |
 * |---|---|
 * | `ui/TrailscapeApp.kt` (diese Datei) | Fundament: Navigationsleiste, Aufnahme-Knopf, `NavHost` |
 * | `ui/AppNavigation.kt` | Fundament: Tabs, Routen, Sprungziele, Segmente als reine Logik |
 * | `ui/AppViewModel.kt` | Fundament: die API ist der Vertrag aller Screens |
 * | `ui/ReadyToRideDialog.kt` | Fundament: der Bereit-Dialog des Aufnahme-Knopfs |
 * | `ui/TrainingInsights.kt` | Fundament: reine Rechenschicht ueber `:core` |
 * | `ui/MapStyles.kt` | Fundament: Katalog der Kartenstile |
 * | `ui/today/TodayScreen.kt` | Startseite: Tagesempfehlung, Woche, letzte Tour |
 * | `ui/map/MapScreen.kt` | Karte, Suche/Aktionen/Planen, Navigation, Offline-Download |
 * | `ui/indoor/IndoorTabScreen.kt` | Indoor-Tab: Strecken (Indoor-Ablauf) und Plan |
 * | `ui/training/PlanScreen.kt` | Sicht „Plan" des Indoor-Tabs |
 * | `ui/rides/RidesScreen.kt` | Verlauf-Tab: Touren, Fortschritt, Detailansicht |
 * | `ui/training/ProgressContent.kt` | Sicht „Fortschritt" des Verlaufs |
 * | `ui/more/MoreScreen.kt` | Einstellungen, Health, Backup, Sync — hinterm Zahnrad |
 * | `ui/components/OneUiNavigationBar.kt` | Fundament: die schwebende Navigationskapsel |
 * | `ui/components/RecCapsuleButton.kt` | Fundament: der Aufnahme-Knopf neben der Kapsel |
 *
 * ## Die Leiste schwebt — was das fuer einen Screen bedeutet
 * Die Navigationskapsel liegt im One-UI-Stil **ueber** dem Inhalt und belegt
 * keine Layout-Hoehe. Jeder Bildschirm haelt seinen unteren Rand deshalb selbst
 * frei: `screenContentPadding()` fuer Listen,
 * `LocalFloatingNavigationBarSpace.current` fuer alles, was sonst unten steht
 * (schwebende Knoepfe, Kartenpanels, `SnackbarHost`). Wer das vergisst, baut
 * ein Bedienelement hinter die Kapsel.
 *
 * Der Aufnahme-Knopf steht **neben** der Kapsel, bringt aber seine eigene
 * Hoehe mit — die gemeldete Bodenfreiheit ist deshalb die **gemessene** Hoehe
 * des ganzen Bandes und nicht laenger eine Konstante (Begruendung im Rumpf).
 * Wo die Leiste zuruecktritt ([navigationBarHidden]: gepushte Seiten, eine
 * Aufgabe auf Karte, Verlauf oder Indoor), meldet
 * [LocalFloatingNavigationBarSpace] nur noch die Gestenleiste.
 *
 * Neue Hilfs-Composables eines Screens gehoeren in **dessen** Paket
 * (`ui/map/…`, `ui/training/…`, `ui/more/…`) oder — wenn wirklich geteilt —
 * in `ui/components/`, nie in diese Datei.
 *
 * ## Was ein Screen braucht, holt er sich selbst
 *  * Context/Activity: `LocalContext.current`
 *  * Berechtigungen (Standort, Benachrichtigungen): im jeweiligen Screen, mit
 *    `rememberLauncherForActivityResult` — die Huelle fragt nichts an. Das
 *    gilt ausdruecklich auch fuer den Aufnahme-Knopf dieser Datei: Er bittet
 *    den Karten-Screen ueber [AppViewModel.requestRecording] bzw.
 *    [AppViewModel.requestNavigatePlanned] und ueberlaesst ihm die
 *    Berechtigungsabfrage, statt sie hier ein zweites Mal zu bauen.
 *  * Aufzeichnungszustand: `de.trailscape.app.record.RecordingRepository`
 *  * Alles Uebrige (Touren, Profil, Auswertung, Plan, Kartenstil, Health,
 *    Sync): das **eine** geteilte [AppViewModel], das hier unten mit
 *    `viewModel()` im Activity-Scope erzeugt und als Parameter
 *    durchgereicht wird. Screens erzeugen **kein** eigenes ViewModel
 *    (Ausnahme: der Indoor-Ablauf haelt sein `IndoorViewModel` am eigenen
 *    Backstack-Eintrag).
 *
 * ## Erststart
 * Beim allerersten Start liegt vor den vier Tabs die Einfuehrung
 * (`ui/onboarding/OnboardingScreen.kt`). Ob sie faellig ist, entscheidet
 * [AppViewModel.onboardingVisible] — der Zustand kommt aus den
 * SharedPreferences, nicht aus dieser Datei. Solange sie laeuft, gibt es weder
 * Navigationsleiste noch Aufnahme-Knopf: Die Einfuehrung ist kein Tab, sondern
 * ein Zustand davor.
 *
 * ## Wechsel aus einem Screen heraus
 * Ein Screen ruft `appViewModel.requestTab(AppTab.MAP)`; die Huelle beobachtet
 * [AppViewModel.tabRequest] und navigiert. Deshalb kommen alle Screens ohne
 * Navigations-Callbacks aus. Gezielte Spruenge haben eigene Funktionen, die
 * dasselbe Muster nutzen: [AppViewModel.requestPlan] (Indoor → Plan),
 * [AppViewModel.requestProgress] (Verlauf → Fortschritt),
 * [AppViewModel.requestRideDetail], [AppViewModel.requestMoreSection] und
 * [AppViewModel.requestIndoor] fuer die gepushten Indoor-Seiten.
 *
 * [AppTab.MORE] ist dabei der eine Wert, der kein Tab ist: [navCommandFor]
 * loest ihn auf ein gepushtes Ziel mit Zurueck-Weg auf statt auf einen
 * Tab-Wechsel (siehe [navigateToSettings]).
 */

/**
 * Beschriftung und Symbol je Tab. Reihenfolge und Routen kommen aus
 * [SHELL_TABS] (`ui/AppNavigation.kt`) — hier steht nur, was Compose braucht.
 *
 * ## Warum „Heute" vorne steht
 * Die App startete einmal auf der Karte. Wer sie morgens oeffnete, bekam damit
 * ein Werkzeug zu sehen, aber keine Auskunft: Die Verbindung aus
 * Ruhepuls/HRV/Schlaf, Trainingsplan und automatisch generierter Runde lag
 * drei Tabs weiter hinten. „Heute" ist deshalb das erste Ziel und die
 * `startDestination`.
 *
 * ## Warum genau diese vier
 * Vier Ziele sind das Maximum, das die schwebende Kapsel
 * (`ui/components/OneUiNavigationBar.kt`) einzeilig beschriftet traegt — bei
 * fuenf steht das laengste Label auf einem 320-dp-Geraet vor der Ellipse. Die
 * Seitenkarte gibt den vierten Platz dem Indoor-Bereich, weil er nach Umfang
 * der groesste Bereich der App ist und vorher keinen festen Platz hatte; Plan
 * und Fortschritt des frueheren Tabs „Training" sind auf „Indoor" und
 * „Verlauf" verteilt. Die Beschriftungen bleiben kurz („Indoor" ist kuerzer
 * als „Training"), und jedes Label ist einzeilig mit Ellipse gesetzt.
 *
 * ## Duenne Symbole, die das sagen, was sie zeigen
 * One UI 8/9 zeichnet Systemsymbole duenn (outlined), nicht gefuellt — gefuellte
 * Icons sind dort dem *ausgewaehlten* Zustand vorbehalten. Deshalb sind alle
 * vier Icons `Outlined`-Varianten. Die Karte bekommt `Map` (die Flaeche, kein
 * einzelner Ort), der Verlauf `Route` (eine gefahrene Tour ist eine Linie
 * durch die Landschaft), Indoor das Fahrrad — dasselbe Symbol, das die
 * Indoor-Startseite fuer den Trainer verwendet. Das Herz bleibt app-weit der
 * Herzfrequenz vorbehalten.
 */
private data class TabLook(@param:StringRes val labelRes: Int, val icon: ImageVector)

private fun tabLook(tab: AppTab): TabLook = when (tab) {
    AppTab.HOME -> TabLook(R.string.shell_nav_today_label, Icons.Outlined.Today)
    AppTab.MAP -> TabLook(R.string.shell_nav_map_label, Icons.Outlined.Map)
    AppTab.INDOOR -> TabLook(R.string.shell_nav_indoor_label, Icons.AutoMirrored.Outlined.DirectionsBike)
    AppTab.RIDES -> TabLook(R.string.shell_nav_history_label, Icons.Outlined.Route)
    // Kein Tab (siehe [SHELL_TABS]); nur der Vollstaendigkeit des `when` wegen.
    AppTab.MORE -> TabLook(R.string.shell_nav_today_label, Icons.Outlined.Today)
}

private fun NavDestination?.isOn(route: String): Boolean =
    this?.hierarchy?.any { it.route == route } == true

/**
 * Wurzel der Oberflaeche: stellt die Sprache bereit und zeichnet darin die
 * Navigationshuelle ([TrailscapeAppContent]).
 *
 * Die Sprache kommt aus der Konfiguration der Activity (dort steckt der
 * Override aus `AppLocale` bereits drin, in Robolectric-Tests der
 * Qualifier), neu berechnet bei jeder Konfigurationsaenderung. Der
 * `LaunchedEffect` gleicht [de.trailscape.app.data.AppServices.appLanguage]
 * an — damit rechnet das [AppViewModel] seine `:core`-Saetze in derselben
 * Sprache, in der die Oberflaeche sie zeigt.
 */
@Composable
fun TrailscapeApp() {
    val configuration = LocalConfiguration.current
    val language = remember(configuration) { languageOf(configuration) }
    val layoutClass = remember(configuration.screenWidthDp) {
        windowLayoutClassFor(configuration.screenWidthDp)
    }
    LaunchedEffect(language) { AppServices.setAppLanguage(language) }
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalCoreTexts provides coreTexts(language),
        LocalAppFormats provides remember(language) { AppFormats(language) },
        LocalWindowLayoutClass provides layoutClass,
    ) {
        TrailscapeAppContent()
    }
}

@Composable
private fun TrailscapeAppContent() {
    // Activity-Scope: `viewModel()` ohne eigenen Store-Owner nimmt die
    // Activity als Owner — genau eine Instanz fuer alle Ziele, die
    // Tabwechsel und Konfigurationsaenderungen ueberlebt.
    val appViewModel: AppViewModel = viewModel()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val onboardingVisible by appViewModel.onboardingVisible.collectAsStateWithLifecycle()
    if (onboardingVisible) {
        OnboardingScreen(appViewModel)
        return
    }

    // Idee 7: „als Planung/als Tour erkannt" nach Teilen/Oeffnen mit.
    ImportRecognitionHost(appViewModel)

    // Erst unterhalb der Einfuehrung: Solange sie steht, ist der `NavHost`
    // nicht komponiert. Ein Tab-Wunsch in dieser Zeit (etwa „Oeffnen mit
    // Trailscape" direkt nach der Installation) liefe sonst ins Leere oder
    // — ohne je gesetzten Graphen — in eine Exception von
    // `findStartDestination`. So bleibt er liegen und greift, sobald die
    // Einfuehrung vorbei ist.
    val tabRequest by appViewModel.tabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        val requested = tabRequest ?: return@LaunchedEffect
        // Was der Wunsch bedeutet, entscheidet [navCommandFor]: die vier Tabs
        // wechseln, [AppTab.MORE] wird als zweite Ebene gepusht. Aufrufer
        // (Leerzustaende, Hinweise, requestMoreSection) kennen und brauchen
        // diesen Unterschied nicht.
        when (val command = navCommandFor(requested)) {
            is NavCommand.SwitchTab -> navController.navigateToTab(command.route)
            is NavCommand.Push -> navController.navigateToSettings()
        }
        appViewModel.consumeTabRequest()
    }

    // Gepushte Indoor-Seiten (Ablauf mit Vorwahl, Einstellungen → Indoor,
    // Strecken auf dem Geraet, Diagnose): dasselbe Muster wie der Tab-Wunsch,
    // aufgeloest auf ein gepushtes Ziel mit Zurueck-Weg.
    val indoorRequest by appViewModel.indoorRequest.collectAsStateWithLifecycle()
    LaunchedEffect(indoorRequest) {
        val requested = indoorRequest ?: return@LaunchedEffect
        navController.navigate(navCommandFor(requested).route) { launchSingleTop = true }
        appViewModel.consumeIndoorRequest()
    }

    // Wann Kapsel und Fahren-Knopf zuruecktreten, entscheidet
    // [navigationBarHidden]: auf jeder gepushten Seite, und auf Karte, Verlauf
    // und Indoor, solange dort eine Aufgabe den ganzen Bildschirm braucht
    // (Planung, Tourdetail, Indoor-Fahrt).
    val currentRoute = SHELL_TABS.firstOrNull { currentDestination.isOn(it.route) }?.route
        ?: currentDestination?.route
    val mapTaskActive by appViewModel.mapTaskActive.collectAsStateWithLifecycle()
    val rideDetailOpen by appViewModel.rideDetailOpen.collectAsStateWithLifecycle()
    val indoorTaskActive by appViewModel.indoorTaskActive.collectAsStateWithLifecycle()
    val onMap = currentRoute == AppRoutes.MAP
    val navHidden = navigationBarHidden(
        route = currentRoute,
        mapTaskActive = mapTaskActive,
        rideDetailOpen = rideDetailOpen,
        indoorTaskActive = indoorTaskActive,
    )
    val mapSheetUnderNav by appViewModel.mapSheetUnderNav.collectAsStateWithLifecycle()

    // Bodenfreiheit, die jeder Bildschirm unten einplanen muss: das schwebende
    // Band aus Kapsel und Aufnahme-Knopf plus die Gestenleiste des Systems.
    //
    // Die Zahl wird **gemessen** statt gerechnet. Grund: Das Band ist seit dem
    // Aufnahme-Knopf nicht mehr nur die Kapsel — der Knopf bringt eine eigene
    // Hoehe mit (Kreis, Raum fuer seinen Pulsring, das Mini-Label darunter,
    // siehe `ui/components/RecCapsuleButton.kt`), und diese Hoehe gehoert ihm,
    // nicht dieser Datei. Eine hier von Hand nachgepflegte Summe waere beim
    // naechsten Feinschliff am Knopf still falsch, und „still falsch" heisst
    // hier: Das letzte Listenelement liegt hinter einem Bedienelement.
    // [OneUiNavigationBarDefaults.OverlaySpace] bleibt die Untergrenze — es
    // gilt fuer den ersten Frame, bevor gemessen wurde, und fuer jeden Fall,
    // in dem die Kapsel (grosse Schrift) hoeher ist als der Knopf.
    //
    // Das System-Inset wird gelesen und NICHT verzehrt: Die Bildschirme
    // brauchen es als Zahl, nicht als Padding.
    val layoutClass = LocalWindowLayoutClass.current
    val railMode = useNavigationRail(layoutClass)
    val railVisible = railMode && !navHidden

    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var navigationBandHeightPx by remember { mutableIntStateOf(0) }
    val measuredBandHeight = with(LocalDensity.current) { navigationBandHeightPx.toDp() }
    val navigationBarSpace = if (railMode || navHidden) {
        systemBottomInset
    } else {
        maxOf(
            OneUiNavigationBarDefaults.OverlaySpace + systemBottomInset,
            measuredBandHeight,
        )
    }

    // ------------------------------------------------ Zustand des Aufnahme-Knopfs
    // Die Huelle bildet ihn, weil der Knopf ausserhalb jedes Screens schwebt.
    // „laeuft" kommt aus dem Aufzeichnungsdienst, „Route bereit" aus der
    // einen Zahl, die der Karten-Screen ueber [AppViewModel.plannedRouteKm]
    // meldet — mehr braucht die Huelle von der Planung nicht zu wissen.
    val isRecording by RecordingRepository.isRecording.collectAsStateWithLifecycle()
    val recordingPaused by RecordingRepository.isPaused.collectAsStateWithLifecycle()
    val recordingElapsedMs by RecordingRepository.elapsedMs.collectAsStateWithLifecycle()
    val plannedRouteKm by appViewModel.plannedRouteKm.collectAsStateWithLifecycle()

    // Lokale Kopie, damit der Smart-Cast im `when` unten greift: `by`-Delegate
    // lassen sich nicht auf Nicht-Null verengen.
    val readyRouteKm = plannedRouteKm
    val recState = when {
        isRecording -> RecButtonState.Recording(
            elapsedMs = recordingElapsedMs,
            paused = recordingPaused,
        )

        readyRouteKm != null -> RecButtonState.RouteReady(distanceKm = readyRouteKm)
        else -> RecButtonState.Idle
    }

    // Ob der Bereit-Dialog offen ist (siehe `ui/ReadyToRideDialog.kt`). Kein
    // `rememberSaveable`: Ein Dialog, der eine Fahrt beginnen will, soll nach
    // einem Prozesstod nicht von selbst wieder dastehen.
    var readyDialogOpen by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val onRecClick = {
        // Laeuft eine Aufzeichnung, ist der Knopf der Weg ins Fahr-Cockpit —
        // das wohnt im Karten-Screen (`RideModeScreen.kt`), also dorthin.
        // Sonst fragt der Bereit-Dialog, was gefahren werden soll.
        if (isRecording) {
            appViewModel.requestRideCockpit()
        } else {
            readyDialogOpen = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFloatingNavigationBarSpace provides navigationBarSpace) {
            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = railVisible) {
                    AtlasNavigationRail(
                        topAction = {
                            RecCapsuleButton(state = recState, onClick = onRecClick)
                        },
                        bottomAction = {
                            SettingsAction(onClick = { navController.navigateToSettings() })
                        },
                    ) {
                        SHELL_TABS.forEach { shellTab ->
                            val look = tabLook(shellTab.tab)
                            AtlasNavigationRailItem(
                                selected = currentDestination.isOn(shellTab.route),
                                onClick = { navController.navigateToTab(shellTab.route) },
                                icon = look.icon,
                                label = stringResource(look.labelRes),
                            )
                        }
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = AppRoutes.HOME,
                    // Oben und seitlich loest jedes Ziel die System-Insets ueber
                    // [InsetPadded] auf (das taten vorher die `innerPadding` des
                    // `Scaffold`, danach der `NavHost` selbst); unten ausdruecklich
                    // nicht — dort soll der Inhalt bis unter die Kapsel laufen.
                    // Ausnahme sind Indoor-Tab und Indoor-Ablauf: Die Fahransicht legt die
                    // 3D-Welt randlos unter Status- und Navigationsleiste und
                    // loest die Insets selbst auf (`IndoorRideScreen`,
                    // `IndoorPage`). `imePadding` haelt Eingabefelder ueber der
                    // Tastatur, was zuvor am `contentWindowInsets` des Scaffold hing.
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .imePadding(),
                // Uebergaenge nach M3 (siehe [M3Transitions]): zwischen den
                // Tabs „Top level" (Fade through), in die Einstellungen und
                // zurueck „Forward and backward" (Shared axis X). Die
                // Zurueckgeste des Systems spult denselben Uebergang
                // rueckwaerts ab (Predictive back).
                enterTransition = {
                    when {
                        isPushedRoute(targetState.destination.route) ->
                            M3Transitions.sharedAxisXEnter(forward = true, density)
                        // Die Karte blendet nur ein; ihr Blatt faehrt selbst
                        // von unten herauf (siehe `MapScreen.kt`).
                        targetState.destination.route == AppRoutes.MAP ->
                            M3Transitions.topLevelEnter(scale = false)
                        else -> M3Transitions.topLevelEnter()
                    }
                },
                exitTransition = {
                    if (isPushedRoute(targetState.destination.route)) {
                        M3Transitions.sharedAxisXExit(forward = true, density)
                    } else {
                        M3Transitions.topLevelExit()
                    }
                },
                popEnterTransition = {
                    when {
                        isPushedRoute(initialState.destination.route) ->
                            M3Transitions.sharedAxisXEnter(forward = false, density)
                        targetState.destination.route == AppRoutes.MAP ->
                            M3Transitions.topLevelEnter(scale = false)
                        else -> M3Transitions.topLevelEnter()
                    }
                },
                popExitTransition = {
                    if (isPushedRoute(initialState.destination.route)) {
                        M3Transitions.sharedAxisXExit(forward = false, density)
                    } else {
                        M3Transitions.topLevelExit()
                    }
                },
            ) {
                val openDiagnostics = {
                    navController.navigate(AppRoutes.INDOOR_DIAGNOSTICS) { launchSingleTop = true }
                }
                val openWorlds = {
                    navController.navigate(AppRoutes.INDOOR_WORLDS) { launchSingleTop = true }
                }
                val openGarage = {
                    navController.navigate(AppRoutes.INDOOR_GARAGE) { launchSingleTop = true }
                }
                composable(AppRoutes.HOME) { InsetPadded(consumeStartInset = !railVisible) { TodayScreen(appViewModel) } }
                composable(AppRoutes.MAP) { InsetPadded(consumeStartInset = !railVisible) { MapScreen(appViewModel) } }
                // Der Indoor-Tab: Strecken (der Indoor-Ablauf, sein ViewModel
                // haengt an diesem Eintrag) und Plan. Ohne [InsetPadded] — die
                // Fahransicht ist randlos, jede Seite loest die Insets selbst auf.
                composable(AppRoutes.INDOOR) {
                    IndoorTabScreen(
                        appViewModel = appViewModel,
                        onOpenDiagnostics = openDiagnostics,
                        onOpenWorlds = openWorlds,
                        onOpenGarage = openGarage,
                    )
                }
                composable(AppRoutes.RIDES) { InsetPadded(consumeStartInset = !railVisible) { RidesScreen(appViewModel) } }
                // Zweite Ebene, kein Tab: Der Zurueck-Pfeil in der Kopfzeile
                // und die Systemzurueckgeste (die der `NavHost` fuer ein
                // gepushtes Ziel von selbst bedient) fuehren zurueck dorthin,
                // von wo das Zahnrad angetippt wurde.
                composable(AppRoutes.MORE) {
                    InsetPadded(consumeStartInset = !railVisible) { MoreScreen(appViewModel, onBack = { navController.popBackStack() }) }
                }
                // Indoor-Ablauf mit vorgewaehlter Strecke (Einheit des Tages
                // von „Heute", „Indoor fahren" und „In 3D ansehen" aus dem
                // Tourdetail): eigenes ViewModel am eigenen Eintrag, Zurueck
                // fuehrt zum Ausgangspunkt.
                composable(AppRoutes.INDOOR_FLOW) {
                    IndoorFlowScreen(
                        appViewModel = appViewModel,
                        // Gezielt bis einschliesslich des Ablaufs: Ein doppelt
                        // ausgeloestes Verlassen raeumt so nie den Tab darunter ab.
                        onExit = { navController.popBackStack(AppRoutes.INDOOR_FLOW, inclusive = true) },
                        onOpenDiagnostics = openDiagnostics,
                        onOpenWorlds = openWorlds,
                        mode = IndoorFlowMode.PUSHED,
                    )
                }
                // „Einstellungen → Indoor": die Indoor-Einstellungen samt
                // Fahrgefuehl; Strecken auf dem Geraet und Diagnose darunter.
                composable(AppRoutes.INDOOR_SETTINGS) {
                    IndoorFlowScreen(
                        appViewModel = appViewModel,
                        onExit = { navController.popBackStack(AppRoutes.INDOOR_SETTINGS, inclusive = true) },
                        onOpenDiagnostics = openDiagnostics,
                        onOpenWorlds = openWorlds,
                        mode = IndoorFlowMode.SETTINGS,
                    )
                }
                // Die Garage: gepusht vom Symbol in der Kopfzeile des Indoor-Tabs.
                // Ohne [InsetPadded]: Die Seite loest ihre Insets wie der
                // Indoor-Ablauf selbst auf (`IndoorPage`).
                composable(AppRoutes.INDOOR_GARAGE) {
                    GarageDestination(onBack = { navController.popBackStack() })
                }
                composable(AppRoutes.INDOOR_WORLDS) {
                    InsetPadded(consumeStartInset = !railVisible) { IndoorWorldsDestination(onBack = { navController.popBackStack() }) }
                }
                composable(AppRoutes.INDOOR_DIAGNOSTICS) {
                    InsetPadded(consumeStartInset = !railVisible) { IndoorDiagnosticsScreen(onBack = { navController.popBackStack() }) }
                }
            }
            }
        }

        // Die schwebende One-UI-Kapsel liegt ueber dem Inhalt (siehe
        // `components/OneUiNavigationBar.kt`) — deshalb `Box` statt
        // `Scaffold(bottomBar = …)`: Ein bottomBar-Slot wuerde ihr Hoehe im
        // Layout zuweisen, und genau das soll sie nicht haben.
        //
        // Kapsel und Aufnahme-Knopf stehen in **einer** Zeile: die Kapsel
        // flexibel (`weight`), der Knopf fix daneben, mit demselben Abstand
        // zum Bildschirmrand ([OneUiNavigationBarDefaults.SideMargin]).
        //
        // Die Gestenleiste loest diese Zeile **einmal** auf, nicht jedes Kind
        // fuer sich: `windowInsetsPadding` verzehrt das Inset fuer den ganzen
        // Teilbaum, der gleichnamige Aufruf in [OneUiNavigationBar] legt
        // danach nichts mehr drauf. So haben Kapsel und Knopf per Konstruktion
        // denselben Boden.
        //
        // `CenterVertically`: Der Knopf misst nur seinen quadratischen Slot
        // (Kreis samt symmetrischer Ringluft; das Mini-Label haengt darunter,
        // ohne Hoehe zu beanspruchen — siehe `RecCapsuleButton.kt`). Die
        // gemeinsame Mitte der Zeile ist damit exakt die Mitte des Kreises
        // und die Mitte der Kapsel; die Hoehe des Bandes misst
        // `onSizeChanged` weiter oben.
        //
        // Ein- und Ausblenden nach M3 „Enter and exit, beyond screen bounds":
        // Das Band faehrt ueber den unteren Rand hinaus und wieder herein,
        // statt schlagartig zu verschwinden.
        AnimatedVisibility(
            visible = !railMode && !navHidden,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = M3Transitions.sheetEnter(),
            exit = M3Transitions.sheetExit(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bewusst **vor** `windowInsetsPadding`: Modifier wirken
                    // von aussen nach innen, gemessen wird hier also das ganze
                    // Band einschliesslich der Gestenleisten-Aufloesung — genau
                    // die Zahl, die [LocalFloatingNavigationBarSpace] meldet.
                    .onSizeChanged { navigationBandHeightPx = it.height }
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 14 statt 24 dp Aussenrand: Die Zeile teilt sich der Knopf
                // mit der Kapsel — ohne die engeren Raender enden die
                // Tab-Beschriftungen auf schmalen Geraeten in Ellipsen.
                OneUiNavigationBar(
                    modifier = Modifier.weight(1f),
                    horizontalMargin = 14.dp,
                    floating = !(onMap && mapSheetUnderNav),
                ) {
                    SHELL_TABS.forEach { shellTab ->
                        val look = tabLook(shellTab.tab)
                        OneUiNavigationBarItem(
                            selected = currentDestination.isOn(shellTab.route),
                            onClick = { navController.navigateToTab(shellTab.route) },
                            icon = look.icon,
                            label = stringResource(look.labelRes),
                        )
                    }
                }

                Box(
                    // Der sichtbare Kreis soll auf derselben Randflucht
                    // stehen wie die Kapsel (`SideMargin`); die unsichtbare
                    // Ringluft des Slots wird deshalb abgezogen.
                    modifier = Modifier.padding(
                        end = OneUiNavigationBarDefaults.SideMargin -
                            RecCapsuleButtonDefaults.RingAllowance,
                    ),
                ) {
                    RecCapsuleButton(
                        state = recState,
                        onClick = onRecClick,
                    )
                }
            }
        }

        if (readyDialogOpen) {
            ReadyToRideDialog(
                appViewModel = appViewModel,
                plannedRouteKm = readyRouteKm,
                onDismiss = { readyDialogOpen = false },
            )
        }
    }
}

/**
 * Wechselt zu einem Tab, ohne den Backstack wachsen zu lassen — derselbe
 * Weg fuer die Navigationsleiste und fuer [AppViewModel.requestTab].
 *
 * Steht der Tab schon vorn, passiert nichts. Navigation legt sonst auch fuer
 * das bereits sichtbare Ziel einen **neuen** `NavBackStackEntry` an (ueber
 * `popUpTo`/`restoreState` wie ueber `launchSingleTop`); die Komposition
 * bleibt wegen derselben ID stehen, aber `LocalLifecycleOwner` wechselt unter
 * ihr. Genau das legte die Karte still, sobald „Passende Runde" auf der Karte
 * selbst `requestTab(MAP)` rief: keine Vorschau-Linie, keine Gesten (siehe
 * `MapViewHost`).
 */
/**
 * Oben und seitlich die System-Insets (Statusleiste, Ausschnitt, seitliche
 * Navigationsleiste) fuer ein Ziel des `NavHost` — unten bewusst nicht, dort
 * laeuft der Inhalt unter die Kapsel.
 */
@Composable
private fun InsetPadded(
    consumeStartInset: Boolean = true,
    content: @Composable () -> Unit,
) {
    val sides = if (consumeStartInset) {
        WindowInsetsSides.Top + WindowInsetsSides.Horizontal
    } else {
        WindowInsetsSides.Top + WindowInsetsSides.End
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(sides)),
        // Wie zuvor am `NavHost`: Das Ziel bekommt die volle Flaeche als Mindestmass.
        propagateMinConstraints = true,
    ) {
        content()
    }
}

private fun NavHostController.navigateToTab(route: String) {
    if (currentDestination?.hierarchy?.any { it.route == route } == true) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Oeffnet den Mehr-Bereich als zweite Ebene ueber dem gerade sichtbaren Tab.
 *
 * Bewusst **nicht** [navigateToTab]: Dessen `popUpTo(startDestination)` waere
 * hier genau falsch — es raeumte den Tab, von dem aus das Zahnrad angetippt
 * wurde, aus dem Backstack und die Zurueckgeste landete auf „Heute" statt dort,
 * wo man herkam. `launchSingleTop` verhindert nur, dass zweimaliges Tippen den
 * Bereich doppelt stapelt.
 */
private fun NavHostController.navigateToSettings() {
    navigate(AppRoutes.MORE) { launchSingleTop = true }
}
