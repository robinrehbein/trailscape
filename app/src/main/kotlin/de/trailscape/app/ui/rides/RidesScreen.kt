package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppTab
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.components.OneUiLargeTopAppBar
import de.trailscape.app.ui.components.SettingsAction
import de.trailscape.app.ui.components.oneUiTopAppBarScrollBehavior
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.rememberActivityImportAction

/**
 * # Der Touren-Tab — die chronologische Sicht auf den Bestand
 *
 * Vierter Platz der Navigationskapsel und der Hauptzugang zu den eigenen
 * Touren (siehe `ui/TrailscapeApp.kt`, „Die Fuehrung ‚Eine Leiste‘").
 *
 * ## Warum es diesen Bildschirm wieder gibt
 * Die Tourenliste lag zwischenzeitlich ausschliesslich als aufziehbares Blatt
 * ueber der Karte (`ui/map/ExploreSheet.kt`) — mit dem Argument, eine Tour sei
 * zuerst eine Linie auf der Karte und eine Liste daneben nur dieselbe
 * Information in Textform. Das Argument stimmt fuer *eine* Tour und geht fuer
 * den *Bestand* daneben: Karte und Liste beantworten zwei verschiedene Fragen
 * — „wo war ich?" gegen „was habe ich gefahren, wie lang, wie hart, wann?".
 * Vor allem aber war der Zugang unauffindbar: ein Griff am unteren
 * Kartenrand, den man erst aufziehen muss. Wer die App nicht kannte, fand
 * seine Touren nicht.
 *
 * Das Blatt ueber der Karte bleibt trotzdem bestehen und unveraendert — es ist
 * die **raeumliche** Sicht und der Weg, eine Tour dort auszuwaehlen, wo man
 * gerade plant. Beide teilen sich denselben Baustein ([TourListContent]), es
 * gibt also nur eine Tourenliste, in zwei Behaeltern.
 *
 * ## Was dieser Bildschirm selbst tut
 * Wenig, und das ist Absicht: Kopfzeile, Bodenfreiheit fuer die schwebende
 * Kapsel, das Zahnrad in den Mehr-Bereich, die Detailansicht als eigenes
 * Fenster und die drei Callbacks, die [TourListContent] braucht. Karten,
 * Menues, Umbenennen, Loeschen mit „Rückgängig", Teilen und der Leerzustand
 * liegen unveraendert im Baustein.
 *
 * ## Die Detailansicht liegt in einem eigenen Fenster
 * Genau wie auf dem Karten-Screen: Nur ein eigenes `Dialog`-Fenster deckt auch
 * die schwebende Navigationskapsel ab, die in `TrailscapeApp.kt` als
 * Geschwister-`Box` **ueber** dem gesamten `NavHost` liegt. Ein Vollbild
 * innerhalb dieses Screens haette die Kapsel und den Aufnahme-Knopf ueber der
 * Tourdetailansicht stehen lassen. Das Fenster faengt zugleich die
 * Systemzurueckgeste ab, bevor sie den `NavHost` erreicht — die erste Geste
 * schliesst also das Detail und nicht den Tab.
 *
 * ## Meldungen
 * [AppViewModel.messages] sammelt dieser Screen ein: [TourListContent] tut es
 * bewusst nicht (es ist ein Baustein, kein Bildschirm), und ein Import-Erfolg
 * oder eine erkannte Dublette muss sichtbar werden. Die „Rückgängig"-Snackbar
 * des Loeschens bringt der Baustein selbst mit — Begruendung in dessen KDoc.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidesScreen(appViewModel: AppViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val rides by appViewModel.rides.collectAsStateWithLifecycle()

    // Die in der Detailansicht geoeffnete Tour — die ID, nicht das `Ride`:
    // Nach einem Umbenennen oder einem HF-Merge aus Health Connect liefert
    // `appViewModel.rides` ein neues Objekt, ueber die ID zeigt die Ansicht
    // immer auf den aktuellen Stand (wortgleiche Begruendung wie im
    // Karten-Screen).
    var detailRideId by rememberSaveable { mutableStateOf<String?>(null) }

    // Der Einzelimport (GPX/FIT) samt SAF-Launcher und Fehlerdialog — dieselbe
    // geteilte Aktion, die auch der Karten-Screen haelt. Hier gebraucht fuer
    // den Knopf „GPX-/FIT-Datei öffnen" im Leerzustand der Liste.
    val importAction = rememberActivityImportAction(appViewModel)

    // Von der Startseite („Letzte Tour") angeforderte Detailansicht. Erst
    // quittieren, wenn die Tour wirklich in [rides] vorliegt, sonst ginge eine
    // Anfrage kurz nach dem Kaltstart (Liste noch leer) spurlos verloren.
    val pendingRideDetailRequest by appViewModel.pendingRideDetail.collectAsStateWithLifecycle()
    LaunchedEffect(pendingRideDetailRequest, rides) {
        val wanted = pendingRideDetailRequest ?: return@LaunchedEffect
        if (rides.any { it.id == wanted }) {
            detailRideId = wanted
            appViewModel.consumeRideDetailRequest()
        }
    }

    // Verschwindet die geoeffnete Tour aus der Liste (Sync, Loeschen
    // anderswo), schliesst sich die Ansicht von selbst statt eine nicht mehr
    // existierende Tour anzuzeigen.
    LaunchedEffect(rides) {
        if (detailRideId != null && rides.none { it.id == detailRideId }) {
            detailRideId = null
        }
    }

    val scrollBehavior = oneUiTopAppBarScrollBehavior()

    Scaffold(
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest und als Padding an den NavHost gegeben — hier duerfen sie
        // nicht noch einmal aufschlagen.
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OneUiLargeTopAppBar(
                title = "Touren",
                scrollBehavior = scrollBehavior,
                actions = {
                    SettingsAction(onClick = { appViewModel.requestTab(AppTab.MORE) })
                },
            )
        },
        snackbarHost = {
            // Ohne dieses Padding erschiene die Meldung hinter der schwebenden
            // Navigationskapsel (siehe LocalFloatingNavigationBarSpace).
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(
                    bottom = LocalFloatingNavigationBarSpace.current,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            TourListContent(
                appViewModel = appViewModel,
                onOpenDetail = { detailRideId = it },
                // „Auf der Karte zeigen": die Tour auswaehlen und in den
                // Karten-Tab wechseln — dort liegt sie danach als Linie samt
                // Tourkarte am unteren Rand. Genau der Weg, den auch die
                // Startseite fuer „Letzte Tour" nimmt, nur ohne Detailansicht.
                onShowOnMap = { ride ->
                    appViewModel.select(ride.id)
                    appViewModel.requestTab(AppTab.MAP)
                },
                onImportFile = importAction.start,
                modifier = Modifier.fillMaxSize(),
                // Anders als im Blatt ueber der Karte ist dies ein
                // eigenstaendiger Bildschirm: Der Rand kommt aus der
                // App-Konvention und traegt die Bodenfreiheit der Kapsel
                // gleich mit.
                contentPadding = screenContentPadding(),
            )
        }
    }

    detailRideId?.let { id ->
        Dialog(
            onDismissRequest = { detailRideId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // `usePlatformDefaultWidth = false` macht dieses Fenster randlos.
            // Anders als im `NavHost` von `TrailscapeApp.kt` sind die
            // Systemleisten hier NICHT schon aufgeloest: [RideDetailHost] (und
            // mit ihm `RideDetailScreen.kt`) setzt `contentWindowInsets =
            // WindowInsets(0, 0, 0, 0)` in der Annahme, dass genau das laengst
            // geschehen ist. Dieselbe Aufloesung (oben und seitlich; unten
            // bewusst nicht) wird deshalb hier wiederholt, sonst zeichnet die
            // Kopfzeile der Detailansicht unter die Statusleiste. Wortgleich
            // zum Karten-Screen, aus wortgleichem Grund.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        ),
                ) {
                    RideDetailHost(
                        rideId = id,
                        appViewModel = appViewModel,
                        onBack = { detailRideId = null },
                    )
                }
            }
        }
    }
}
