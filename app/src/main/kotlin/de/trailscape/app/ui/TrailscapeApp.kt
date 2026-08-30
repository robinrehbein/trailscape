package de.trailscape.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiNavigationBar
import de.trailscape.app.ui.components.OneUiNavigationBarDefaults
import de.trailscape.app.ui.components.OneUiNavigationBarItem
import de.trailscape.app.ui.components.RecButtonState
import de.trailscape.app.ui.components.RecCapsuleButton
import de.trailscape.app.ui.components.RecCapsuleButtonDefaults
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
 * Navigationsleiste samt dem Aufnahme-Knopf daneben, den `NavHost` und den
 * Aufruf der Screens. Wer an einem einzelnen Screen arbeitet, aendert sie
 * **nicht** — die Aufrufe unten sind fest verabredete Signaturen:
 *
 * ```kotlin
 * TodayScreen(appViewModel)
 * MapScreen(appViewModel)
 * RidesScreen(appViewModel)
 * TrainingScreen(appViewModel)
 * MoreScreen(appViewModel, onBack = …)
 * ```
 *
 * ## Die Fuehrung „Eine Leiste"
 * Hier stand bis zuletzt die Begruendung, warum „Touren" **kein** Tab sei
 * (Tourenliste als Blatt ueber der Karte) und warum „Mehr" einer sei. Beides
 * ist revidiert. Ergebnis der Designstudie
 * (`docs/design/ui-navigationsstudien.html`, Kapitel „Eine Leiste") und des
 * klickbaren Prototyps (`docs/design/prototyp-eine-leiste.html`) ist ein
 * anderer Zuschnitt derselben vier Plaetze:
 *
 *  * **Touren wird Tab.** Die Karte ist die *raeumliche* Sicht auf den
 *    Bestand, die Liste die *chronologische* — das ist keine Dublette,
 *    sondern eine zweite Frage („wo war ich?" gegen „was habe ich gefahren?").
 *    Als aufziehbares Blatt hinter einem Griff am unteren Kartenrand war die
 *    Liste faktisch unauffindbar: Wer die App nicht kannte, fand seine Touren
 *    nicht. Sie hat deshalb wieder einen eigenen, beschrifteten Platz
 *    (`ui/rides/RidesScreen.kt`) und lebt jetzt **ausschliesslich** dort — das
 *    Blatt ueber der Karte (`ui/map/ExploreSheet.kt`) zeigt keine Tourenliste
 *    mehr. Eine Tour-Spur zeigt die Karte seither nur noch „auf Zuruf" aus dem
 *    Touren-Tab (`AppViewModel.requestShowRideOnMap`); das Blatt selbst ist auf
 *    Suche, Planung und Kartenwerkzeuge reduziert.
 *  * **Mehr wandert hinters Zahnrad.** „Mehr" war nie ein Ort, an den man
 *    geht, sondern eine Schublade, in der man etwas nachschlaegt — Profil,
 *    Import, Offline-Karten, Sync. Ein Viertel der immer sichtbaren
 *    Hauptnavigation dafuer auszugeben, war der teuerste Platz der App fuer
 *    den seltensten Handgriff. Der Bereich ist jetzt ein **gepushtes Ziel**
 *    (Route [MORE_ROUTE]) hinter einem ⚙ in den Kopfzeilen von Heute, Touren
 *    und Training, mit Zurueck-Pfeil und Systemzurueckgeste wie jede andere
 *    zweite Ebene.
 *  * **Fahren ist kein Ziel, sondern ein Zustand.** Rechts neben der Kapsel
 *    schwebt der runde Aufnahme-Knopf ([RecCapsuleButton]) — abgesetzt, kein
 *    fuenfter Tab: Er teilt sich die Navigationsebene mit den vier Zielen,
 *    ohne eines von ihnen zu sein. Seine drei Zustaende (Ruhe, „Route
 *    bereit", „laeuft") bildet diese Datei aus [RecordingRepository] und
 *    [AppViewModel.plannedRouteKm]; was ein Tipp bewirkt, steht in
 *    `ui/ReadyToRideDialog.kt`.
 *
 * ## Was wo liegt
 *
 * | Datei | Rolle |
 * |---|---|
 * | `ui/TrailscapeApp.kt` (diese Datei) | Fundament: Navigationsleiste, Aufnahme-Knopf, `NavHost` |
 * | `ui/AppViewModel.kt` | Fundament: die API ist der Vertrag aller Screens |
 * | `ui/ReadyToRideDialog.kt` | Fundament: der Bereit-Dialog des Aufnahme-Knopfs |
 * | `ui/TrainingInsights.kt` | Fundament: reine Rechenschicht ueber `:core` |
 * | `ui/MapStyles.kt` | Fundament: Katalog der Kartenstile |
 * | `ui/today/TodayScreen.kt` | Startseite: Tagesempfehlung, Woche, letzte Tour |
 * | `ui/map/MapScreen.kt` | Karte, Suche/Aktionen/Planen, Navigation, Offline-Download |
 * | `ui/rides/RidesScreen.kt` | Touren-Tab: Vollbild-Liste und Detailansicht |
 * | `ui/rides/TourList.kt` | Baustein: Tourenliste und -detail |
 * | `ui/training/TrainingScreen.kt` | Trainingsplan und Auswertung |
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
 * Auf der Route [MORE_ROUTE] gibt es weder Kapsel noch Knopf — dort meldet
 * [LocalFloatingNavigationBarSpace] nur noch die Gestenleiste, damit die
 * Einstellungsliste nicht gegen einen leeren Streifen scrollt.
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
 *    durchgereicht wird. Screens erzeugen **kein** eigenes ViewModel.
 *
 * ## Erststart
 * Beim allerersten Start liegt vor den vier Tabs die Einfuehrung
 * (`ui/onboarding/OnboardingScreen.kt`). Ob sie faellig ist, entscheidet
 * [AppViewModel.onboardingVisible] — der Zustand kommt aus den
 * SharedPreferences, nicht aus dieser Datei. Solange sie laeuft, gibt es weder
 * Navigationsleiste noch Aufnahme-Knopf: Die Einfuehrung ist kein Tab, sondern
 * ein Zustand davor.
 *
 * ## Tab-Wechsel aus einem Screen heraus
 * Statt eines `onShowMap`-Callbacks (so machte es die Flutter-App) ruft ein
 * Screen `appViewModel.requestTab(AppTab.MAP)`; die Huelle beobachtet
 * [AppViewModel.tabRequest] und navigiert. Deshalb kommen alle Screens mit
 * derselben, parameterlosen Signatur aus.
 *
 * [AppTab.MORE] ist dabei die eine Ausnahme, die kein Tab mehr ist: Ein gutes
 * Dutzend Aufrufer — Leerzustaende, Hinweise,
 * [AppViewModel.requestMoreSection] — bittet weiterhin schlicht um „den
 * Mehr-Bereich" und soll dafuer nicht umgeschrieben werden muessen. Die Huelle
 * loest den Wunsch beim Auftreffen auf ein `navigate("mehr")` auf, also auf
 * ein gepushtes Ziel mit Zurueck-Weg statt auf einen Tab-Wechsel (siehe
 * [navigateToSettings] und `LaunchedEffect(tabRequest)` unten).
 */

/**
 * Die vier Hauptbereiche in der Reihenfolge der Navigationsleiste.
 *
 * ## Warum „Heute" vorne steht
 * Die App startete einmal auf der Karte. Wer sie morgens oeffnete, bekam damit
 * ein Werkzeug zu sehen, aber keine Auskunft: Die Verbindung aus
 * Ruhepuls/HRV/Schlaf, Trainingsplan und automatisch generierter Runde — das
 * Alleinstellungsmerkmal dieser App — lag drei Tabs weiter hinten am Ende einer
 * Karte. `HOME` ist deshalb das erste Ziel und die `startDestination`.
 *
 * ## Warum genau diese vier
 * Vier Ziele sind das Maximum, das die schwebende Kapsel
 * (`ui/components/OneUiNavigationBar.kt`) einzeilig beschriftet traegt — bei
 * fuenf steht das laengste Label auf einem 320-dp-Geraet vor der Ellipse. Die
 * Frage ist deshalb nicht, ob vier, sondern **welche** vier. Die Antwort der
 * Studie „Eine Leiste": die vier Dinge, die man taeglich tut — den Tag
 * ansehen, die Karte benutzen, die eigenen Touren nachschlagen, das Training
 * verfolgen. Alles Seltene (Profil, Import, Offline-Karten, Sync) liegt
 * hinterm Zahnrad, und das Fahren selbst hat den runden Knopf daneben, weil es
 * ein Zustand ist und kein Ort.
 *
 * Der frueher hier stehende Tab „Mehr" ist damit entfallen; „Touren" hat
 * seinen Platz zurueck. Die verbleibenden Beschriftungen bleiben kurz (das
 * laengste ist „Training"), und jedes Label ist einzeilig mit Ellipse gesetzt.
 *
 * ## Duenne Symbole, die das sagen, was sie zeigen
 * One UI 8/9 zeichnet Systemsymbole duenn (outlined), nicht gefuellt — gefuellte
 * Icons sind dort dem *ausgewaehlten* Zustand vorbehalten, nicht der Ruhelage.
 * Deshalb sind alle vier Icons hier `Outlined`-Varianten. Zwei davon trugen
 * einmal eine Bedeutung, die nicht zum Tab passte: TRAINING zeigte ein Herz
 * (`Favorite`) — das Symbol fuer Favoriten bzw. Herzfrequenz, obwohl der Tab
 * Trainingskurven und Auswertung zeigt, keinen Puls. Das Herz bleibt deshalb
 * app-weit der Herzfrequenz vorbehalten; TRAINING bekommt `ShowChart`. MAP
 * zeigte eine einzelne Ortsmarke (`Place`); eine Karte ist aber kein einzelner
 * Ort, sondern die Flaeche selbst — `Map` trifft das ehrlicher. RIDES bekommt
 * `Route`: Eine gefahrene Tour ist eine Linie durch die Landschaft, kein
 * Listeneintrag — dasselbe Symbol, das die Karte fuer ihre Routen ohnehin
 * schon verwendet (`Icons.Filled.Route` in `ui/map/…`), hier in der duennen
 * Variante.
 */
private enum class TopLevelDestination(
    val tab: AppTab,
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME(AppTab.HOME, "heute", "Heute", Icons.Outlined.Today),
    MAP(AppTab.MAP, "karte", "Karte", Icons.Outlined.Map),
    RIDES(AppTab.RIDES, "touren", "Touren", Icons.Outlined.Route),
    TRAINING(AppTab.TRAINING, "training", "Training", Icons.AutoMirrored.Outlined.ShowChart),
}

/**
 * Die Route des Mehr-Bereichs — kein Tab, sondern ein gepushtes Ziel hinterm
 * Zahnrad (siehe Datei-KDoc, „Mehr wandert hinters Zahnrad").
 */
private const val MORE_ROUTE = "mehr"

@Composable
fun TrailscapeApp() {
    // Activity-Scope: `viewModel()` ohne eigenen Store-Owner nimmt die
    // Activity als Owner — genau eine Instanz fuer alle Ziele, die
    // Tabwechsel und Konfigurationsaenderungen ueberlebt.
    val appViewModel: AppViewModel = viewModel()

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val tabRequest by appViewModel.tabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tabRequest) {
        val requested = tabRequest ?: return@LaunchedEffect
        // [AppTab.MORE] hat seit der Fuehrung „Eine Leiste" kein Ziel mehr in
        // TopLevelDestination — `entries.first` wuerde dafuer werfen.
        // Aufrufer (Leerzustaende, Hinweise, requestMoreSection) kennen und
        // brauchen diesen Unterschied nicht: Sie bitten weiterhin um „den
        // Mehr-Bereich", die Huelle loest das hier auf ein gepushtes Ziel mit
        // Zurueck-Weg auf statt auf einen Tab-Wechsel.
        if (requested == AppTab.MORE) {
            navController.navigateToSettings()
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

    // Ob gerade der Mehr-Bereich offen ist. Er ist die einzige zweite Ebene
    // im `NavHost` und traegt deshalb weder Kapsel noch Aufnahme-Knopf: Eine
    // Navigationsleiste ohne markiertes Ziel behauptete, man sei nirgends,
    // und der Aufnahme-Knopf haette ueber einem Einstellungsformular nichts
    // verloren.
    val settingsOpen = currentDestination?.hierarchy?.any { it.route == MORE_ROUTE } == true

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
    val systemBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var navigationBandHeightPx by remember { mutableIntStateOf(0) }
    val measuredBandHeight = with(LocalDensity.current) { navigationBandHeightPx.toDp() }
    val navigationBarSpace = if (settingsOpen) {
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
                composable(TopLevelDestination.RIDES.route) { RidesScreen(appViewModel) }
                composable(TopLevelDestination.TRAINING.route) { TrainingScreen(appViewModel) }
                // Zweite Ebene, kein Tab: Der Zurueck-Pfeil in der Kopfzeile
                // und die Systemzurueckgeste (die der `NavHost` fuer ein
                // gepushtes Ziel von selbst bedient) fuehren zurueck dorthin,
                // von wo das Zahnrad angetippt wurde.
                composable(MORE_ROUTE) {
                    MoreScreen(appViewModel, onBack = { navController.popBackStack() })
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
        if (!settingsOpen) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
                ) {
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
                        onClick = {
                            // Laeuft eine Aufzeichnung, ist der Knopf der Weg
                            // ins Fahr-Cockpit — das wohnt im Karten-Screen
                            // (`RideModeScreen.kt`), also dorthin. Sonst
                            // fragt der Bereit-Dialog, was gefahren werden
                            // soll.
                            if (isRecording) {
                                appViewModel.requestTab(AppTab.MAP)
                            } else {
                                readyDialogOpen = true
                            }
                        },
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
 */
private fun NavHostController.navigateToTab(route: String) {
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
    navigate(MORE_ROUTE) { launchSingleTop = true }
}
