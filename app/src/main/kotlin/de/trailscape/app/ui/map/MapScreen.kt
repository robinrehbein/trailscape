package de.trailscape.app.ui.map

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.routing.missingSegmentsFor
import de.trailscape.app.routing.planRouteOfflineFirst
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.PlaceSearchHistoryEntry
import de.trailscape.app.ui.components.LocalFloatingNavigationBarSpace
import de.trailscape.app.ui.formatBytes
import de.trailscape.app.ui.formatToday
import de.trailscape.app.ui.mapStyleSubtitle
import de.trailscape.app.ui.mapStyles
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.app.ui.rides.RideDetailHost
import de.trailscape.app.ui.rides.TourListContent
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.OverlayGap
import de.trailscape.app.ui.theme.OverlayScreenPadding
import de.trailscape.core.AscentPreference
import de.trailscape.core.GeoResult
import de.trailscape.core.NavState
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RouteNavigator
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.RoutingSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.buildGpx
import de.trailscape.core.computeStats
import de.trailscape.core.haversineM
import de.trailscape.core.safeFileName
import de.trailscape.core.searchPlaces
import de.trailscape.app.ui.rides.finishMarkers
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # Karte — Aufzeichnung, Planung, Navigation und Offline-Ausschnitte
 *
 * Zur laufenden Aufzeichnung gehoert neben der Live-Leiste der **Fahrmodus**
 * (`RideModeScreen.kt`): dieselben Werte, aber gross genug fuer den Blick aus
 * einem Meter. Er haelt hier nur ein `Boolean` (`rideMode`) — Zustand,
 * Kommandos und Navigationswerte bleiben die dieses Screens. Er ist fuer eine
 * Aufzeichnung der Normalfall: Startet sie durch eine Nutzeraktion in dieser
 * Sitzung, oeffnet er direkt (siehe `runRecording()`), nicht erst ueber einen
 * eigenen Knopf in der Live-Leiste.
 *
 * Port von `lib/screens/map_screen.dart` (2154 Zeilen) auf Compose und
 * MapLibre. Der Screen selbst haelt nur den *Bildschirmzustand* (Planungsmodus,
 * Wegpunkte, Suchtext, Navigationsziel, Downloadfortschritt); alles, was
 * laenger lebt, liegt woanders:
 *
 *  * Touren, Auswahl und Kartenstil im geteilten [AppViewModel],
 *  * die laufende Aufzeichnung im [RecordingRepository] (Vordergrunddienst),
 *  * die Karte selbst im [MapController] (siehe `MapViewHost.kt`).
 *
 * ## Der Kartenmodus
 * Ob ein Kartentipp einen Wegpunkt setzt, ob die Zurueck-Geste die Planung
 * verlaesst, ob das Tourenblatt weichen muss — all das entschied bis vor
 * Kurzem eine eigene Kombination aus `planning: Boolean` und
 * `navTarget != null`, an jeder Stelle neu zusammengesetzt. [MapMode]
 * (`MapMode.kt`) fasst das in einen einzigen `rememberSaveable`-Zustand
 * (`mode`) mit genau drei Werten — [MapMode.ERKUNDEN], [MapMode.PLANEN],
 * [MapMode.NAVIGIEREN] — und jede Sichtbarkeits- oder Verhaltensfrage im
 * Screen fragt seither `mode` statt einer Flag-Kombination ab. Das
 * ausformulierte Modell samt der einen bewussten Ausnahme (Navigation der
 * eigenen geplanten Route bleibt in [MapMode.PLANEN], siehe [runNavigatePlannedRoute])
 * steht im KDoc von `MapMode.kt`; hier nur, wo `mode` im Bildschirmablauf
 * wechselt:
 *
 *  * **[MapMode.ERKUNDEN] → [MapMode.PLANEN]**: der Knopf „Route planen“,
 *    [restorePlanning] (Rueckgaengig nach „Planung beenden"/„Leeren") und
 *    [applyGeneratedRoute] (ein uebernommener Rundkurs-Vorschlag ist ab da
 *    eine ganz normale geplante Route).
 *  * **[MapMode.PLANEN] → [MapMode.ERKUNDEN]**: [exitPlanning] (und damit
 *    [exitPlanningWithUndo]) sowie [runRecording], **sofern** noch geplant
 *    wurde — steht `mode` schon auf [MapMode.NAVIGIEREN] (Aufnahme waehrend
 *    einer Tour-Navigation), laesst [runRecording] ihn unangetastet.
 *  * **[MapMode.ERKUNDEN] → [MapMode.NAVIGIEREN]**: [runNavigateRide]. Nur von
 *    hier aus erreichbar, weil sich waehrend [MapMode.PLANEN] gar keine Tour
 *    auswaehlen laesst (das Tourenblatt weicht dort ja bereits).
 *  * **[MapMode.NAVIGIEREN] → [MapMode.ERKUNDEN]**: [stopNavigation] sowie der
 *    Effekt, der die Navigation beendet, wenn die navigierte Tour geloescht
 *    wird.
 *
 * `searchOpen` ist bewusst kein vierter Wert: Die Ortssuche bleibt in allen
 * drei Modi erreichbar (siehe „Suche jederzeit" unten) und ist damit
 * orthogonal zum Kartenmodus, nicht ein weiterer Zustand desselben Schalters.
 *
 * ## Bewusste Unterschiede zum Flutter-Original
 *  * **Kein Namensdialog nach dem Stopp.** In Flutter lief die Aufzeichnung im
 *    UI-Prozess; der Screen baute die Tour selbst und fragte vorher nach einem
 *    Namen. Nativ speichert der Dienst die Tour selbst (er ueberlebt das
 *    Schliessen der App), vergibt „Tour <Datum>" und meldet sie ueber
 *    [RecordingRepository.lastFinishedRideId]. Quittiert wird diese Meldung im
 *    [AppViewModel] (nicht hier): Gestoppt werden kann auch ueber die
 *    Notification, waehrend ein anderer Tab sichtbar ist. Das ViewModel laedt
 *    die Liste neu, waehlt die Tour aus und schickt den Hinweis in
 *    [AppViewModel.messages]; umbenannt wird im Touren-Tab.
 *  * **Navigation auch entlang einer geplanten Route**, nicht nur entlang
 *    einer gespeicherten Tour.
 *  * **Positionen der Navigation** kommen aus der laufenden Aufzeichnung,
 *    wenn eine laeuft — das Original abonnierte GPS ein zweites Mal.
 *  * **Keine Vibration bei „abseits der Route"**: Dafuer fehlt die
 *    `VIBRATE`-Berechtigung im Manifest, das hier nicht angefasst wird. Die
 *    Warnung erscheint als Meldung und in der Navigationsleiste.
 *  * **Suche jederzeit**, nicht nur im Planungsmodus — als von unten
 *    hochfahrendes Blatt (siehe `SearchSheet.kt`) statt als Panel im oberen
 *    Stapel; ein gewaehlter Treffer ist seither ein Ort-Objekt ([Place]) mit
 *    eigener Karte (`PlaceCard.kt`), keine Sofortaktion mehr an der
 *    Trefferzeile.
 *  * **Hoehenprofil** fuer die ausgewaehlte Tour und die geplante Route — das
 *    hatte der Karten-Screen in Flutter noch nicht.
 *  * **Rundkurs aus der Trainingsempfehlung.** Der Trainings-Tab schickt ueber
 *    [AppViewModel.pendingRouteTarget] ein Ziel her; dieser Screen oeffnet
 *    dafuer das Panel aus `RouteGenerationPanel.kt`, laesst im
 *    [RouteGenerationController] suchen und legt den uebernommenen Vorschlag in
 *    **denselben** `plannedRoute`-Zustand, den die Planung von Hand fuellt —
 *    Hoehenprofil, Speichern, Teilen und Navigation funktionieren damit ohne
 *    einen zweiten Weg. Das Flutter-Original kannte weder Generator noch
 *    Uebergabe zwischen den Tabs.
 *  * **Aufzeichnung von der Startseite.** Die Karte „Aufzeichnung" im
 *    Heute-Tab (`RecordPromptCard` in `ui/today/TodayCards.kt`) schickt ueber
 *    [AppViewModel.pendingRecordStart] dieselbe Bitte her, die dieser Screen
 *    sonst nur vom gruenen Knopf kennt — abgeholt und ausgeloest wird sie ueber
 *    **dieselbe** lokale Funktion `startRecording()`, also mit derselben
 *    Berechtigungsabfrage. Vorher versprach die Karte auf der Startseite nur
 *    den Weg dorthin und erklaerte in einem Absatz, wo der eigentliche Knopf
 *    liegt; jetzt haelt der eine Knopf das eine Versprechen.
 *  * **Rundkurs auch ohne Trainingsziel.** Im Planungsblatt steht bei null
 *    Wegpunkten „Runde ab hier" mit drei Distanzen und einem Feld fuer die
 *    eigene Zahl (siehe [startRoundTrip] und `PlanningPanel.kt`). Vorher war
 *    der Generator ausschliesslich ueber den Heute- oder Trainings-Tab
 *    erreichbar — an einem Ruhetag also gar nicht.
 *  * **Die Planung liegt unten und hat zwei Stufen** (`PlanningSheet` in
 *    `PlanningPanel.kt`): eingeklappt eine Zeile, aufgeklappt der volle
 *    Inhalt. Vorher stapelten sich alle Panels oben und liessen auf einem
 *    360×800-dp-Geraet einen Kartenstreifen von rund 80 dp uebrig —
 *    ausgerechnet dort, wo Wegpunkte hingetippt werden.
 *  * **Wegpunkte, Route und Navigationsziel ueberleben** Tabwechsel und
 *    Drehung (siehe `PlanningStateSavers.kt`), und die **Aufzeichnung loescht
 *    die geplante Route nicht mehr** — planen, „Navigieren", losfahren ist die
 *    vorgesehene Reihenfolge und darf die blaue Linie nicht mitnehmen.
 *  * **Automatischer Erst-Zoom auf die Position** statt des dauerhaften
 *    Deutschland-Defaults: Liegt beim Start (oder unmittelbar nach einer
 *    erteilten Freigabe) eine Standortfreigabe vor und hat die Nutzerin die
 *    Karte noch nicht selbst bewegt, zoomt sie einmalig sanft auf die
 *    aktuelle Position (Zoom ~13) — kein Dart-Vorbild. Details siehe der
 *    Effekt bei `autoLocationZoomDone` weiter unten.
 *
 * ## Die Tourenliste und ihre Rangfolge am unteren Kartenrand
 * Seit dem Wegfall des eigenen Touren-Tabs (siehe `ui/TrailscapeApp.kt`,
 * „Warum Touren und Karte eine Seite sind") liegt die Tourenliste als Koerper
 * des Erkunden-Blatts ([ExploreSheet], `ExploreSheet.kt`) — kein eigenes
 * drittes Blatt mehr: Eingeklappt zeigt das Blatt Suchzeile und Werkzeugreihe,
 * aufgeklappt (ueber [SwipeableSheet], `SwipeableSheet.kt`) zusaetzlich die
 * Kopfzeile „Touren" (· Anzahl) und die Liste bis zu
 * [TOUR_SHEET_MAX_HEIGHT_FACTOR] der Bildschirmhoehe. Am unteren Rand
 * bewerben sich damit mehrere Zustaende um denselben Platz — Aufzeichnung,
 * Navigation, Planung, ausgewaehlte Tour (`RideCard`), offene Suche und
 * Erkunden-Blatt —, und es gilt eine feste Rangfolge:
 *
 *  1. **Aufzeichnung, Navigation, Planung, ausgewaehlte Tour oder offene
 *     Suche haben Vorrang.** Sie laufen entweder waehrend der Fahrt (die
 *     Live-Leiste und die Navigationsleiste duerfen nicht hinter einer Liste
 *     verschwinden) oder sind eine bewusste Handlung der Nutzerin (Planung,
 *     eine ausgewaehlte Tour, eine Suche) — das Erkunden-Blatt und mit ihm
 *     die Tourenliste sind in all diesen Faellen ueberhaupt nicht komponiert
 *     (siehe die Sichtbarkeitsbedingung um den `ExploreSheet`-Aufruf weiter
 *     unten).
 *  2. **Sonst ist die eingeklappte Stufe der Ruhezustand**: eine Zeile, die
 *     die Karte kaum verdeckt. Aufgeklappt entsteht nur auf zwei Wegen — die
 *     Nutzerin zieht oder tippt selbst am Griff, oder ein anderer Tab bittet
 *     ueber [AppViewModel.tourSheetRequest] darum (siehe „Vier Tabs sind das
 *     Maximum" in `TrailscapeApp.kt`: „Touren" ist kein eigenes Ziel mehr,
 *     sondern genau dieser Wunsch).
 *  3. **Ein schon aufgeschlagenes Blatt faellt beim Eintreten eines
 *     Vorrang-Zustands auf die eingeklappte Stufe zurueck und bleibt dort**,
 *     auch nachdem der Vorrang-Zustand wieder endet — es springt nicht von
 *     selbst wieder auf. Wer waehrend der Aufzeichnung zufaellig auf
 *     „Touren" tippt, soll nach dem Stopp nicht ueberrascht ein offenes
 *     Blatt vorfinden, das sie selbst nie geoeffnet hat.
 *
 * Die Detailansicht einer Tour ([RideDetailHost] aus `ui/rides/TourList.kt`)
 * liegt darueber noch einmal in einem eigenen Fenster (`Dialog`, wie der
 * Fahrmodus — siehe dessen KDoc „Warum ein eigenes Fenster" in
 * `RideModeScreen.kt`): Nur ein eigenes Fenster deckt auch die schwebende
 * Navigationskapsel ab, die in `TrailscapeApp.kt` als Geschwister-`Box`
 * **ueber** dem gesamten `NavHost` und damit auch ueber diesem Screen liegt.
 *
 * Die Systemzurueckgeste ordnet sich in dieselbe Rangfolge ein: **Detail**
 * vor **Blatt** vor **Planung** vor der App-Voreinstellung. Das
 * Detailfenster braucht dafuer keinen eigenen `BackHandler` — als eigenes
 * `Dialog`-Fenster faengt es die Geste ab, bevor sie diesen Screen ueberhaupt
 * erreicht (dieselbe Mechanik wie beim Fahrmodus). Fuer Blatt und Planung
 * steht ein einzelner `BackHandler` weiter unten: Ist das Erkunden-Blatt
 * aufgeschlagen, schliesst die erste Geste es wieder ein; ist stattdessen die
 * Planung an (das Erkunden-Blatt ist dann ohnehin nicht komponiert, siehe
 * Punkt 1), beendet sie ueber [exitPlanningWithUndo] die Planung — mit
 * derselben Rueckhol-Snackbar wie der Knopf „Planung beenden". Ist keins von
 * beidem der Fall, bleibt der `BackHandler` deaktiviert und die Geste faellt
 * auf das normale Verhalten der App zurueck.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(appViewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val controller = remember { MapController() }

    // Trefferradius fuer das Tippen auf einen Wegpunkt. Er stand vorher als
    // feste Pixelzahl im Code und war damit auf einem dichten Display nur halb
    // so gross wie auf einem groben — ausgerechnet dort, wo mit dem Daumen
    // getroffen wird. In dp gerechnet ist er ueberall gleich gross und haelt
    // die 48 dp der Material-Empfehlung ein (siehe
    // [WAYPOINT_TOUCH_RADIUS_DP]).
    val waypointTouchRadiusPx = with(LocalDensity.current) { WAYPOINT_TOUCH_RADIUS_DP.toPx() }

    // ------------------------------------------------------ geteilter Zustand
    val mapStyle by appViewModel.mapStyle.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val selectedRide by appViewModel.selectedRide.collectAsStateWithLifecycle()

    // Suchverlauf der Ortssuche (siehe AppViewModel „Suchverlauf"-Abschnitt) —
    // dieselbe kurze Liste, die auch das Suchblatt unter „Zuletzt gesucht"
    // zeigt.
    val placeSearchHistory by appViewModel.placeSearchHistory.collectAsStateWithLifecycle()

    val isRecording by RecordingRepository.isRecording.collectAsStateWithLifecycle()
    val isPaused by RecordingRepository.isPaused.collectAsStateWithLifecycle()
    val elapsedMs by RecordingRepository.elapsedMs.collectAsStateWithLifecycle()
    val recordedKm by RecordingRepository.distanceKm.collectAsStateWithLifecycle()
    val livePoints by RecordingRepository.points.collectAsStateWithLifecycle()
    val speedKmh by RecordingRepository.speedKmh.collectAsStateWithLifecycle()
    val recordingError by RecordingRepository.lastError.collectAsStateWithLifecycle()

    // Der Download laeuft ausserhalb der Komposition weiter (siehe
    // OfflineDownloadController) — hier wird nur sein Fortschritt gelesen.
    val downloadState by OfflineDownloadController.state.collectAsStateWithLifecycle()

    // Ebenso die Rundkurs-Suche: Sie dauert 20–40 s und ueberlebt deshalb den
    // Tab-Wechsel (siehe RouteGenerationController).
    val generation by RouteGenerationController.state.collectAsStateWithLifecycle()
    val pendingRouteTarget by appViewModel.pendingRouteTarget.collectAsStateWithLifecycle()

    // Die Aufzeichnungs-Bitte von der Startseite (siehe [pendingRouteTarget]
    // gleich darueber, dasselbe Muster): Der Effekt weiter unten holt sie ab,
    // sobald die lokalen Aktionen dieses Screens (u. a. `startRecording`)
    // deklariert sind.
    val pendingRecordStart by appViewModel.pendingRecordStart.collectAsStateWithLifecycle()

    // Das offene Download-Angebot fuer fehlende Routing-Kacheln (siehe
    // AppViewModel.segmentOffer). Liegt dort und nicht hier, damit es einen
    // Tab-Wechsel uebersteht und nicht bei jedem Wegpunkt neu entsteht.
    val segmentOffer by appViewModel.segmentOffer.collectAsStateWithLifecycle()

    // ---------------------------------------------------- Zustand des Screens
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }

    // Der explizite Kartenmodus (siehe `MapMode.kt` fuer das ausformulierte
    // Modell und die eine bewusste Ausnahme bei der Navigation der eigenen
    // geplanten Route). Ersetzt das fruehere `planning: Boolean` — jede Stelle,
    // die vorher `if (planning)` fragte, fragt jetzt `if (mode ==
    // MapMode.PLANEN)`. `rememberSaveable`, aus demselben Grund wie vorher:
    // Tabwechsel und Drehung duerfen eine begonnene Planung nicht stillschweigend
    // beenden.
    var mode by rememberSaveable { mutableStateOf(MapMode.ERKUNDEN) }

    // Wegpunkte und berechnete Route liegen in `rememberSaveable`, nicht in
    // `remember`: Der `NavHost` entsorgt diesen Screen beim Tabwechsel, und
    // eine Drehung am Lenker baut ihn ohnehin neu auf. Bis hierher gingen dabei
    // ausgerechnet die Wegpunkte verloren — lautlos, waehrend Planungsmodus,
    // Profil und sogar die Kameraposition sorgfaeltig gerettet wurden. Wie die
    // Umrechnung aussieht und warum sie eine Obergrenze hat, steht in
    // `PlanningStateSavers.kt`.
    var waypoints by rememberSaveable(stateSaver = WaypointListSaver) {
        mutableStateOf<List<Waypoint>>(emptyList())
    }
    var plannedRoute by rememberSaveable(stateSaver = PlannedRouteSaver) {
        mutableStateOf<PlannedRoute?>(null)
    }

    // Wofuer [plannedRoute] berechnet wurde (Wegpunkte + Profil). Nach einer
    // Drehung stehen Wegpunkte und Route wieder da; ohne dieses Kennzeichen
    // wuerde der Planungs-Effekt weiter unten sie sofort neu berechnen — eine
    // ueberfluessige Server- bzw. Geraeterechnung, die im Funkloch sogar mit
    // einem Fehler enden wuerde, obwohl die Route laengst vorliegt.
    var plannedFor by rememberSaveable { mutableStateOf<String?>(null) }

    var routeProfile by rememberSaveable { mutableStateOf(RouteProfile.GRAVEL) }
    var planBusy by remember { mutableStateOf(false) }
    var planError by remember { mutableStateOf<String?>(null) }

    // Ob das Planungsblatt aufgeklappt ist (siehe `PlanningSheet`). Es startet
    // offen — dort stehen der Rundkurs-Einstieg und die Anleitung — und geht
    // beim ersten selbst gesetzten Wegpunkt zu: Wer auf die Karte tippt, will
    // die Karte sehen.
    var planSheetExpanded by rememberSaveable { mutableStateOf(true) }

    // Ob gerade auf einen GPS-Fix gewartet wird (bis zu zehn Sekunden, siehe
    // `CURRENT_LOCATION_TIMEOUT_MS` in `LocationAccess.kt`).
    var locating by remember { mutableStateOf(false) }

    // Rueckmeldung waehrend der Berechnung. Zwei Gruende, warum sie noetig ist:
    // Weit auseinanderliegende Wegpunkte werden in mehrere Etappen zerlegt
    // (siehe `Routing.kt`), und die Berechnung **auf dem Geraet** dauert
    // spuerbar (Sekunden bis Minuten). Bei einer kurzen Route ueber den Server
    // bleibt die Anzeige wie bisher leer.
    var planProgress by remember { mutableStateOf<String?>(null) }

    // Woher die gerade laufende Berechnung kommt — `null`, solange keine
    // laeuft. Steht getrennt vom Fortschrittstext, weil die Quelle **vor** dem
    // ersten Fortschrittsruf feststeht und sich nach einem lokalen Fehlschlag
    // noch aendern kann.
    var planSource by remember { mutableStateOf<RoutingSource?>(null) }

    // Woher [plannedRoute] tatsaechlich stammt — anders als [planSource]
    // bewusst NICHT wieder auf `null` gesetzt, sobald die Berechnung fertig
    // ist: Die Planungszeile soll auch bei stehender Route noch sagen „Gerät"
    // oder „Server" (siehe `PlanningSheet`s `source`-Parameter). Der Nutzer
    // hat den stillen Server-Rueckfall bis hierher nie gesehen (siehe
    // Aufgaben-Hintergrund „Routing-Transparenz").
    var routeSource by remember { mutableStateOf<RoutingSource?>(null) }

    // Ob [plannedRoute] aus dem Rundkurs-Generator stammt statt aus gesetzten
    // Wegpunkten. Der Generator bringt eine fertige Route ohne Wegpunkte mit —
    // ohne dieses Flag wuerde der Planungs-Effekt unten sie beim naechsten Lauf
    // (leere Wegpunktliste) sofort wieder auf `null` setzen. Sobald wieder von
    // Hand geplant wird, faellt es zurueck auf `false`.
    var routeFromGenerator by rememberSaveable { mutableStateOf(false) }

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Der ausgewaehlte Ort — das Google-Maps-Muster „der Ort ist ein Objekt"
    // (siehe `PlaceCard.kt`). Ersetzt den fruehreren `searchMarker: Waypoint?`:
    // Der Marker ist seither nur noch eine Ableitung davon (siehe
    // `buildMapMarkers`), die Karte selbst zeigt die eigentliche Information.
    // Bewusst `remember` und nicht `rememberSaveable`: genau wie beim
    // fruehreren `searchMarker` ist ein gerade betrachteter Suchtreffer
    // Beiwerk, das eine Drehung nicht ueberstehen muss (siehe
    // `PlanningSnapshot.isEmpty`-Kommentar weiter unten, dieselbe Haltung).
    var selectedPlace by remember { mutableStateOf<Place?>(null) }

    // Ziel des „Ortswaehler"-Aufrufmodus (siehe `openPlaceSearch` weiter
    // unten) — `null` heisst „normales Suchblatt, Auswahl zeigt die
    // Ortskarte". Ausserhalb von `rememberSaveable`: Ein Lambda ueberlebt eine
    // Bundle-Wiederherstellung ohnehin nicht, und der Aufrufmodus ist ein
    // kurzlebiger Vorgang innerhalb einer Komposition (Blatt auf → Ort waehlen
    // → Blatt zu), keiner, der eine Drehung ueberstehen muesste.
    var searchPickerCallback by remember { mutableStateOf<((Place) -> Unit)?>(null) }

    var navTarget by remember { mutableStateOf<NavigationTarget?>(null) }
    var navState by remember { mutableStateOf<NavState?>(null) }
    var navTotalKm by rememberSaveable { mutableStateOf(0.0) }

    // Das Navigationsziel in bundle-faehiger Kurzform: die Kennung der Tour
    // (oder `null` fuer die geplante Route) und die Beschriftung. Die
    // Punktliste bleibt bewusst draussen — eine mehrstuendige Aufzeichnung
    // haette zehntausende Punkte, und ein Bundle dieser Groesse beendet die
    // App beim Drehen. Zusammengesetzt wird das Ziel gleich wieder aus den
    // geladenen Touren bzw. aus der geretteten Route (siehe Effekt unten).
    var navRideId by rememberSaveable { mutableStateOf<String?>(null) }
    var navLabel by rememberSaveable { mutableStateOf<String?>(null) }

    // Ob die Karte der eigenen Position folgen soll. Bis hierher tat sie das
    // immer und ohne Schalter: Wer beim Navigieren vorausschauen wollte, war
    // spaetestens beim naechsten GPS-Punkt wieder zurueckgezogen. Jetzt schaltet
    // das eigene Verschieben der Karte das Folgen ab und der Positions-Knopf es
    // wieder ein.
    var followMe by rememberSaveable { mutableStateOf(true) }

    var liveAscentM by remember { mutableStateOf(0.0) }
    var hoverPoint by remember { mutableStateOf<TrackPoint?>(null) }

    // Ob der Fahrmodus (`RideModeScreen.kt`) ueber der Karte liegt. Bewusst
    // `rememberSaveable`: Eine Drehung am Lenker darf nicht dazu fuehren, dass
    // die Fahrerin ploetzlich wieder die kleine Live-Leiste vor sich hat.
    var rideMode by rememberSaveable { mutableStateOf(false) }

    // Ob die Tourenliste im Erkunden-Blatt aufgeklappt ist (siehe
    // `ExploreSheet.kt`) — `rememberSaveable`, damit eine Drehung nicht ein
    // von der Nutzerin aufgeschlagenes Blatt wieder einklappt. `false` ist
    // der Startwert: Beim allerersten Aufbau dieses Screens gilt noch kein
    // Vorrang-Zustand, und die eingeklappte Zeile ist die richtige Ruhelage
    // (siehe Klassen-KDoc, "Rangfolge am unteren Kartenrand").
    var toursExpanded by rememberSaveable { mutableStateOf(false) }

    // Die in der Tourendetailansicht geoeffnete Tour. Bewusst die ID und
    // nicht das `Ride` selbst — wortgleiche Begruendung wie beim frueheren
    // `detailRideId` in `ui/rides/TourList.kt`: Nach einem Umbenennen oder
    // einem HF-Merge aus Health Connect liefert `appViewModel.rides` ein
    // neues Objekt, ueber die ID zeigt die Ansicht immer auf den aktuellen
    // Stand.
    var detailRideId by rememberSaveable { mutableStateOf<String?>(null) }

    var showStyleSheet by remember { mutableStateOf(false) }
    var saveRouteDialog by remember { mutableStateOf(false) }
    var deleteDialogRide by remember { mutableStateOf<Ride?>(null) }

    // Die Absicht hinter einer Berechtigungsanfrage — bewusst ein
    // `rememberSaveable`-faehiger Wert und kein Lambda: Waehrend des
    // System-Dialogs kann die Activity neu aufgebaut werden (Drehen,
    // Speicherdruck). Ein in `remember` gehaltenes Lambda waere danach weg,
    // die erteilte Freigabe bliebe folgenlos.
    var pendingAction by rememberSaveable { mutableStateOf<PendingAction?>(null) }
    var pendingNavigateRideId by rememberSaveable { mutableStateOf<String?>(null) }

    // Nach erteilter Freigabe auszufuehrende Absicht. Getrennt von
    // [pendingAction], weil die Aktionen selbst weiter unten als lokale
    // Funktionen stehen und der Launcher-Callback sie nicht sehen kann.
    var grantedAction by remember { mutableStateOf<PendingAction?>(null) }

    // Verweigerte Freigabe oder „Ungefähr“ statt „Genau“ beim Aufzeichnen:
    // Beides braucht eine Entscheidung, keine Kenntnisnahme — bis hierher
    // liefen beide Faelle als 4-Sekunden-Snackbar und waren verschwunden,
    // bevor sich etwas taete. Die Karte dazu ([LocationPermissionNotice] in
    // `MapPanels.kt`) ist deshalb ein stehender Zustand statt einer Snackbar
    // (siehe deren KDoc fuer die Begruendung im Detail).
    //
    // [locationDeniedAction] traegt dieselbe Absicht wie [pendingAction], nur
    // fuer den abgelehnten Fall — „Erneut fragen" liest sie wieder aus und
    // loest denselben [withPermissions]-Pfad noch einmal aus. Fuer den
    // Ungefaehr-Fall reicht ein Schalter: Er tritt ausschliesslich bei
    // [PendingAction.RECORD] auf (siehe `permissionLauncher` gleich unten),
    // „Erneut fragen" ruft dort deshalb direkt [startRecording] auf.
    var locationDeniedAction by remember { mutableStateOf<PendingAction?>(null) }
    var impreciseLocationNotice by remember { mutableStateOf(false) }

    // --------------------------------------------------------- Berechtigungen
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        locationGranted = hasLocationPermission(context)
        val action = pendingAction
        pendingAction = null
        if (action == null) return@rememberLauncherForActivityResult
        if (action == PendingAction.RECORD && !hasFineLocationPermission(context)) {
            // „Ungefaehr" statt „Genau": Die Karte kaeme damit zurecht, die
            // Aufzeichnung nicht — sie haengt am GPS-Provider, der
            // ACCESS_FINE_LOCATION verlangt. Das bleibt ein stehender Hinweis
            // auf der Karte (siehe [LocationPermissionNotice]) statt einer
            // Snackbar: Der Dienst liefe sonst entweder wortlos wieder ab,
            // oder die Meldung waere laengst weg, wenn die Nutzerin reagieren
            // wollte.
            pendingNavigateRideId = null
            impreciseLocationNotice = true
            return@rememberLauncherForActivityResult
        }
        impreciseLocationNotice = false
        if (locationGranted || action == PendingAction.GENERATE_ROUTES) {
            // Die Rundkurs-Suche braucht die Freigabe nicht zwingend: Ohne sie
            // startet die Runde eben in der Kartenmitte. Sie hier trotzdem
            // anzufragen ist der einzige Weg, spaeter doch den echten Standort
            // zu bekommen — abgelehnt zu werden darf die Suche aber nicht
            // blockieren, sonst haengt die Nutzerin bei dauerhaft verweigerter
            // Freigabe fest.
            locationDeniedAction = null
            grantedAction = action
        } else {
            // Bleibt als stehender Hinweis auf der Karte liegen (siehe
            // [LocationPermissionNotice]) statt als Snackbar zu verschwinden —
            // die verweigerte Freigabe braucht eine Entscheidung, keine
            // Kenntnisnahme. `pendingNavigateRideId` bleibt deshalb bewusst
            // stehen: „Erneut fragen" nimmt genau diese Tour wieder auf.
            locationDeniedAction = action
        }
    }

    /**
     * Fuehrt [run] sofort aus, wenn die noetigen Berechtigungen vorliegen —
     * sonst fragt es sie erst an und merkt sich [action] als Absicht, die der
     * Effekt weiter unten nach der Freigabe ausfuehrt. Genau wie
     * `_ensureLocationPermission` im Original, nur ohne blockierendes `await`.
     */
    fun withPermissions(action: PendingAction, run: () -> Unit) {
        val missing = missingPermissions(context, action == PendingAction.RECORD)
        if (missing.isEmpty()) {
            run()
            return
        }
        pendingAction = action
        permissionLauncher.launch(missing)
    }

    // ------------------------------------------------------------- Meldungen
    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // Erst anzeigen, dann quittieren: `clearError()` schreibt den StateFlow auf
    // null, das rekomponiert den Screen und aendert den Schluessel dieses
    // Effekts — die Coroutine (und mit ihr die noch wartende Snackbar) wuerde
    // dann abgebrochen, bevor die Meldung je zu sehen war.
    LaunchedEffect(recordingError) {
        val message = recordingError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        RecordingRepository.clearError()
    }

    // Die fertig gespeicherte Tour quittiert das AppViewModel (siehe dessen
    // init-Block) — es laedt die Liste neu, waehlt die Tour aus und schickt
    // die Meldung ueber [AppViewModel.messages]. Dieser Screen braucht dafuer
    // keinen eigenen Effekt mehr; so bekommt auch der Touren- oder
    // Trainings-Tab die neue Tour mit, wenn ueber die Notification gestoppt
    // wurde.

    // ------------------------------------------------- Karte mit Daten fuellen
    LaunchedEffect(controller, selectedRide?.id, selectedRide?.points?.size) {
        controller.setTrack(selectedRide?.points ?: emptyList())
    }

    LaunchedEffect(controller, selectedRide?.id) {
        val points = selectedRide?.points ?: return@LaunchedEffect
        if (points.isNotEmpty()) {
            controller.fitToPoints(points)
        }
    }

    LaunchedEffect(controller, plannedRoute) {
        controller.setPlannedRoute(plannedRoute?.points ?: emptyList())
    }

    // Der Ablesepunkt des Hoehenprofils gehoert zu genau einer Tour bzw. Route.
    LaunchedEffect(selectedRide?.id, isRecording) {
        hoverPoint = null
    }

    // -------------------------------------------------- Automatischer Erst-Zoom
    // Startet die Karte am Deutschland-Default (siehe `GERMANY_LAT/LON/ZOOM` in
    // MapViewHost.kt) UND liegt eine Standortfreigabe vor — egal ob von Anfang
    // an erteilt oder soeben ueber einen der Berechtigungsdialoge oben —, zoomt
    // das genau einmal sanft auf die aktuelle Position (Zoom ~13). Greift
    // NICHT ein, wenn die Kamera schon von der Default-Position abweicht (der
    // Nutzer hat selbst gescrollt/gezoomt, ggf. schon vor einer Drehung — siehe
    // `savedLat/Lon/Zoom` in MapViewHost.kt, die das ueber Config-Aenderungen
    // hinweg merken) oder gerade eine Tour ausgewaehlt, eine Route geplant/
    // generiert, aufgezeichnet oder navigiert wird — sonst wuerde der Zoom in
    // eine bestehende Ansicht graetschen.
    //
    // `autoLocationZoomDone` ist `rememberSaveable`: Ohne dieses Flag wuerde
    // eine Drehung waehrend/nach dem Zoom (derselbe `mapReady`/`locationGranted`
    // -Zustand) den Effekt erneut auslösen und die Karte ein zweites Mal
    // verschieben, obwohl der Nutzer inzwischen vielleicht selbst navigiert hat.
    var autoLocationZoomDone by rememberSaveable { mutableStateOf(false) }
    val mapReady = controller.isReady

    LaunchedEffect(mapReady, locationGranted) {
        if (autoLocationZoomDone || !mapReady || !locationGranted) return@LaunchedEffect
        if (mode == MapMode.PLANEN || isRecording || selectedRide != null ||
            navTarget != null || generation.target != null
        ) {
            return@LaunchedEffect
        }
        val camera = controller.rememberCamera()
        val atDefault = camera != null &&
            abs(camera.lat - GERMANY_LAT) < DEFAULT_CAMERA_POSITION_EPSILON &&
            abs(camera.lon - GERMANY_LON) < DEFAULT_CAMERA_POSITION_EPSILON &&
            abs(camera.zoom - GERMANY_ZOOM) < DEFAULT_CAMERA_ZOOM_EPSILON
        if (!atDefault) {
            // Kamera weicht schon vom Default ab: Der Nutzer war hier schon
            // selbst am Werk — endgueltig verzichten, kein spaeterer Versuch.
            autoLocationZoomDone = true
            return@LaunchedEffect
        }
        val position = currentLocation(context) ?: return@LaunchedEffect
        autoLocationZoomDone = true
        controller.moveTo(position.latitude, position.longitude, minZoom = AUTO_LOCATION_ZOOM)
    }

    LaunchedEffect(controller, livePoints.size, followMe) {
        controller.setLiveTrack(livePoints)
        // Wer die Karte selbst verschoben hat, will sie dort haben — auch
        // waehrend der Aufzeichnung. Der Positions-Knopf holt sie zurueck.
        if (!followMe) return@LaunchedEffect
        val last = livePoints.lastOrNull() ?: return@LaunchedEffect
        controller.moveTo(
            lat = last.lat,
            lon = last.lon,
            minZoom = if (livePoints.size == 1) MIN_RECORDING_ZOOM else null,
            animate = false,
        )
    }

    // Der Fahrmodus ist nur eine andere Ansicht auf dieselbe Aufzeichnung —
    // endet sie (auch ueber die Notification oder den Aufnahmeknopf), gibt es
    // nichts mehr anzuzeigen, und er schliesst sich mit ihr.
    LaunchedEffect(isRecording) {
        if (!isRecording) rideMode = false
    }

    LaunchedEffect(livePoints.size) {
        liveAscentM = if (livePoints.size < 2) {
            0.0
        } else {
            withContext(Dispatchers.Default) { computeStats(livePoints).ascentM }
        }
    }

    val markers = buildMapMarkers(
        planning = mode == MapMode.PLANEN,
        waypoints = waypoints,
        ride = selectedRide,
        place = selectedPlace,
        hoverPoint = hoverPoint,
    )
    LaunchedEffect(controller, markers) {
        controller.setMarkers(markers)
    }

    // ---------------------------------------------------------- Routenplanung
    LaunchedEffect(waypoints, routeProfile, routeFromGenerator) {
        if (routeFromGenerator) {
            // Die Route kommt fertig aus dem Rundkurs-Generator; sie hat keine
            // Wegpunkte, aus denen sich etwas nachrechnen liesse.
            planBusy = false
            planError = null
            planProgress = null
            return@LaunchedEffect
        }
        if (waypoints.size < 2) {
            plannedRoute = null
            plannedFor = null
            planError = null
            planBusy = false
            planProgress = null
            return@LaunchedEffect
        }
        val inputs = planningInputsKey(waypoints, routeProfile)
        if (plannedRoute != null && plannedFor == inputs) {
            // Nach Tabwechsel oder Drehung laeuft dieser Effekt erneut, obwohl
            // sich nichts geaendert hat — die vorhandene Route ist die Antwort.
            planBusy = false
            planError = null
            planProgress = null
            return@LaunchedEffect
        }
        planBusy = true
        planError = null
        planProgress = null

        // Entprellen, bevor ueberhaupt gerechnet wird. Wer drei Wegpunkte
        // hintereinander setzt, loest sonst drei Berechnungen aus — und die
        // lokale ist blockierend und **nicht abbrechbar** (siehe
        // `routing/OfflineFirstPlanner.kt`), die zweite wuerde also hinter der
        // ersten in der Engine-Sperre warten. Diese kurze Pause bricht mit der
        // Coroutine ab und verhindert das zuverlaessig; fuer den Serverweg ist
        // sie ein willkommener Nebeneffekt weniger Anfragen.
        delay(PLAN_DEBOUNCE_MS)

        val result = runCatching {
            planRouteOfflineFirst(
                context = context,
                waypoints = waypoints,
                profile = routeProfile,
                onSource = { source -> planSource = source },
                onProgress = { done, total ->
                    planProgress = planProgressText(planSource, done, total)
                },
            )
        }
        result
            .onSuccess { outcome ->
                plannedRoute = outcome.route
                plannedFor = inputs
                planError = null
                routeSource = outcome.source
                // Kein Fehler, sondern eine Gelegenheit: Die Route ist da (ueber
                // den Server), koennte beim naechsten Mal aber lokal und
                // schneller entstehen. Das Angebot blockiert nichts.
                appViewModel.offerMissingSegments(outcome.missingSegmentFiles)
            }
            .onFailure {
                // Wegpunkte bleiben stehen, damit es sich erneut versuchen laesst.
                plannedRoute = null
                plannedFor = null
                routeSource = null
                planError = it.message?.takeIf(String::isNotBlank)
                    ?: "Route konnte nicht berechnet werden."
                // Auch im Fehlerfall dasselbe Angebot wie sonst nur nach Erfolg:
                // Schlaegt sogar der Server ab (z. B. eine 600-km-Route ohne
                // lokale Kacheln, siehe `missingSegmentsFor`-KDoc), soll die
                // Nutzerin den Ausweg sehen und nicht nur „Server überlastet"
                // lesen. `missingSegmentsFor` ist leer, wenn nichts fehlt —
                // `offerMissingSegments` tut dann nichts.
                appViewModel.offerMissingSegments(
                    missingSegmentsFor(context, waypoints, routeProfile),
                )
            }
        planBusy = false
        planProgress = null
        planSource = null
    }

    // --------------------------------------------------------------- Ortssuche
    LaunchedEffect(searchQuery) {
        val query = searchQuery.trim()
        if (query.length < MIN_SEARCH_LENGTH) {
            searchResults = emptyList()
            searchError = null
            searchBusy = false
            return@LaunchedEffect
        }
        // Entprellen: erst tippen lassen, dann fragen (Nominatim-Richtlinien).
        delay(SEARCH_DEBOUNCE_MS)
        searchBusy = true
        searchError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { searchPlaces(query, AppServices.httpClient) }
        }
        result
            .onSuccess { hits ->
                searchResults = hits.take(MAX_SEARCH_RESULTS)
                searchError = if (hits.isEmpty()) "Keine Treffer gefunden." else null
            }
            .onFailure {
                searchResults = emptyList()
                searchError = it.message?.takeIf(String::isNotBlank) ?: "Ortssuche fehlgeschlagen."
            }
        searchBusy = false
    }

    // -------------------------------------------------------------- Navigation
    LaunchedEffect(navTarget, isRecording) {
        val target = navTarget ?: return@LaunchedEffect
        val navigator = runCatching { RouteNavigator(target.points) }.getOrElse { error ->
            appViewModel.showMessage(
                error.message?.takeIf(String::isNotBlank) ?: "Navigation nicht möglich.",
            )
            navTarget = null
            return@LaunchedEffect
        }
        navTotalKm = navigator.totalKm

        // Laeuft eine Aufzeichnung, kommen die Positionen von dort — ein
        // zweiter GPS-Abonnent braeuchte nur Strom fuer dieselben Punkte.
        val positions: Flow<Pair<Double, Double>> = if (isRecording) {
            RecordingRepository.lastPoint.filterNotNull().map { it.lat to it.lon }
        } else {
            locationUpdates(context).map { it.latitude to it.longitude }
        }

        var wasOffRoute = false
        positions.collect { (lat, lon) ->
            val state = navigator.update(lat, lon)
            navState = state
            // `followMe` wird hier bei jedem Punkt frisch gelesen (kein
            // Effekt-Schluessel): Der Navigator soll beim Umschalten
            // weiterlaufen, nur die Kamera haelt sich zurueck.
            if (followMe) controller.moveTo(lat, lon, minZoom = null, animate = false)
            if (state.offRoute && !wasOffRoute) {
                appViewModel.showMessage("Achtung: Du bist abseits der Route.")
            }
            wasOffRoute = state.offRoute
        }
    }

    // Wird die navigierte Tour geloescht, endet die Navigation (wie in Dart).
    // Betrifft ausschliesslich eine Tour-Navigation (`rideId != null`) — die
    // geplante Route kennt keine Loeschung von aussen, deshalb bricht diese
    // Bedingung fuer sie sofort ab, und `mode` steht an dieser Stelle immer auf
    // [MapMode.NAVIGIEREN] (siehe `MapMode.kt`, „die eine bewusste Ausnahme").
    LaunchedEffect(rides, navTarget?.rideId) {
        val rideId = navTarget?.rideId ?: return@LaunchedEffect
        if (rides.none { it.id == rideId }) {
            navTarget = null
            navState = null
            navRideId = null
            navLabel = null
            mode = MapMode.ERKUNDEN
        }
    }

    // Navigation nach Tabwechsel/Drehung wieder aufnehmen: Gerettet wurden nur
    // Kennung und Beschriftung (siehe oben), die Punkte kommen aus den
    // geladenen Touren bzw. aus der geretteten geplanten Route. Bis dahin zeigt
    // die Leiste die gespeicherte Gesamtstrecke; den Rest rechnet der
    // `RouteNavigator` beim naechsten GPS-Punkt neu.
    // Schluessel bewusst billig: `plannedRoute` traegt eine komplette
    // Punktliste, die Zahl der Punkte benennt den Wechsel genauso.
    LaunchedEffect(rides, plannedRoute?.points?.size, navLabel) {
        if (navTarget != null) return@LaunchedEffect
        val label = navLabel ?: return@LaunchedEffect
        val rideId = navRideId
        val points = if (rideId != null) {
            rides.firstOrNull { it.id == rideId }?.points
        } else {
            plannedRoute?.points
        }
        if (points == null || points.size < 2) return@LaunchedEffect
        navTarget = NavigationTarget(rideId, label, points)
    }

    // -------------------------------------------------------------- Aktionen
    /**
     * Beendet die Navigation.
     *
     * `mode` faellt dabei nur aus [MapMode.NAVIGIEREN] zurueck auf
     * [MapMode.ERKUNDEN] — stand er (Ausnahmefall Navigation der eigenen
     * geplanten Route, siehe `MapMode.kt`) auf [MapMode.PLANEN], bleibt er
     * dort: Die Planung selbst endet hier nicht, nur die Navigation entlang
     * ihrer Route.
     */
    fun stopNavigation() {
        navTarget = null
        navState = null
        navTotalKm = 0.0
        navRideId = null
        navLabel = null
        if (mode == MapMode.NAVIGIEREN) mode = MapMode.ERKUNDEN
    }

    /**
     * Beendet die Planung und wirft alles weg — Wegpunkte, Route, Suchtreffer.
     *
     * Der Rueckweg dazu steht in [exitPlanningWithUndo]; direkt aufgerufen
     * wird diese Fassung nur dort, wo ohnehin gleich etwas anderes an ihre
     * Stelle tritt.
     */
    fun exitPlanning() {
        mode = MapMode.ERKUNDEN
        waypoints = emptyList()
        plannedRoute = null
        plannedFor = null
        planError = null
        planBusy = false
        selectedPlace = null
        hoverPoint = null
        routeFromGenerator = false
        planSheetExpanded = true
    }

    /**
     * Stellt eine weggeworfene Planung wieder her — das Gegenstueck zu
     * [exitPlanning] und zum „Leeren"-Knopf.
     */
    fun restorePlanning(snapshot: PlanningSnapshot) {
        mode = MapMode.PLANEN
        waypoints = snapshot.waypoints
        plannedRoute = snapshot.route
        plannedFor = snapshot.plannedFor
        routeFromGenerator = snapshot.fromGenerator
        planError = null
    }

    /**
     * Wirft die Planung weg, aber nicht endgueltig: Eine Meldung mit
     * „Rückgängig" holt sie zurueck.
     *
     * Das X „Planung beenden" sitzt in der Kopfzeile des Planungsblatts
     * direkt neben dem Klappgriff, und „Leeren" steht mitten zwischen den
     * uebrigen Knoepfen — beide vernichteten bis hierher eine halbe Stunde
     * Arbeit mit einem Fehlgriff und ohne jede Nachfrage. Ein Bestaetigungsdialog waere der schlechtere Tausch: Er
     * kostet **jedes** Mal einen Tipp, waehrend die Meldung nur im seltenen
     * Fehlerfall etwas verlangt. Der Touren-Tab loest das Loeschen laengst
     * genauso.
     */
    fun exitPlanningWithUndo(message: String) {
        val snapshot = PlanningSnapshot(waypoints, plannedRoute, plannedFor, routeFromGenerator)
        exitPlanning()
        if (snapshot.isEmpty) return
        scope.launch {
            val answer = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Rückgängig",
                duration = SnackbarDuration.Long,
            )
            if (answer == SnackbarResult.ActionPerformed) restorePlanning(snapshot)
        }
    }

    /** Verwirft den Vorschlag samt Panel und raeumt die Vorschau von der Karte. */
    fun discardGeneratedRoute() {
        RouteGenerationController.close()
        routeFromGenerator = false
        plannedRoute = null
        plannedFor = null
    }

    /**
     * Wechselt nach [MapMode.PLANEN] — der Einstieg „Route planen" im
     * Erkunden-Blatt (`ExploreSheet.kt`).
     */
    fun enterPlanning() {
        if (isRecording) {
            appViewModel.showMessage("Beende zuerst die Aufzeichnung.")
            return
        }
        // Ein noch nicht uebernommener Vorschlag weicht: Wer „Route planen"
        // drueckt, will selbst planen, und zwei Panels uebereinander helfen
        // niemandem.
        if (generation.target != null) discardGeneratedRoute()
        // Eine Route, die schon steht, bleibt dagegen liegen — sie ueberlebt
        // den Start der Aufzeichnung (siehe [runRecording]), und genau sie
        // ist der Grund, die Planung wieder zu oeffnen.
        appViewModel.select(null)
        mode = MapMode.PLANEN
        planSheetExpanded = true
        planError = null
    }

    /**
     * Uebernimmt den gewaehlten Vorschlag als **die** geplante Route — also in
     * genau den Zustand, den auch die Planung von Hand erzeugt. Damit greifen
     * Hoehenprofil, Teilen, Speichern und „Navigieren" sofort, ohne dass es
     * dafuer einen zweiten Weg gaebe.
     */
    fun applyGeneratedRoute() {
        val candidate = generation.selected ?: return
        RouteGenerationController.close()
        appViewModel.select(null)
        waypoints = emptyList()
        plannedFor = null
        planError = null
        planBusy = false
        routeFromGenerator = true
        plannedRoute = candidate.route
        mode = MapMode.PLANEN
        planSheetExpanded = true
        controller.fitToPoints(candidate.route.points)
        appViewModel.showMessage("Runde übernommen – du kannst sie speichern oder navigieren.")
    }

    // Jede Aktion mit Standortbedarf gibt es zweimal: `run…` ist der Rumpf,
    // der die Berechtigung als gegeben voraussetzt, die gleichnamige Funktion
    // ohne Praefix holt sie erst ein. Der Effekt nach einer erteilten Freigabe
    // ruft ausschliesslich die `run…`-Fassung auf — sonst wuerde er bei einer
    // abgelehnten Nebenberechtigung (POST_NOTIFICATIONS ab Android 13) sofort
    // den naechsten Systemdialog ausloesen und sich im Kreis drehen.
    /**
     * Startet die Aufzeichnung, **ohne** die geplante Route wegzuwerfen.
     *
     * Genau die vorgesehene Reihenfolge (planen → „Navigieren" → Aufnahme
     * starten) loeschte bis hierher `plannedRoute` und damit die blaue Linie
     * auf der Karte, waehrend die Navigationsleiste unbeirrt Kilometer zu einer
     * Route herunterzaehlte, die niemand mehr sah. Der Planungs*modus* geht
     * zu — die Bedienflaechen der Planung haben neben der Live-Leiste nichts
     * verloren —, die **Route** bleibt liegen.
     *
     * Ein noch offenes Generator-Panel wird geschlossen; sein Vorschlag zaehlt
     * nur dann als „die Route", wenn er vorher uebernommen wurde (dann ist der
     * Planungsmodus an, siehe [applyGeneratedRoute]).
     *
     * `mode` geht dabei nur aus [MapMode.PLANEN] zurueck auf [MapMode.ERKUNDEN]
     * — steht er auf [MapMode.NAVIGIEREN] (Aufnahme waehrend der Navigation
     * einer gespeicherten Tour), bleibt er das: Die Navigation laeuft unbeirrt
     * weiter, nur die Tourauswahl wird gleich darunter geloescht (siehe
     * `appViewModel.select(null)`).
     *
     * ## Warum hier `rideMode = true` gesetzt wird
     * Diese Funktion laeuft ausschliesslich, wenn eine Nutzeraktion in dieser
     * Sitzung die Aufzeichnung tatsaechlich in Gang setzt — ueber den gruenen
     * Aufnahme-Knopf oder die von der Startseite gereichte Bitte (siehe
     * [pendingRecordStart] weiter unten), beide ueber [startRecording] und
     * damit [withPermissions]. Der Fahrmodus ist fuer eine Aufzeichnung der
     * Normalfall, nicht ein Angebot, das erst gefunden werden muss — deshalb
     * oeffnet er direkt an der Stelle, an der die Aufzeichnung wirklich
     * beginnt. Bewusst **hier** und nicht in einem `LaunchedEffect` auf
     * [isRecording]: Ein solcher Effekt liefe auch dann, wenn eine laengst
     * laufende Aufzeichnung nur durch Tab-Wechsel oder Drehung wieder in die
     * Komposition kommt — genau das darf den Fahrmodus nicht von selbst
     * aufreissen (siehe dessen `rememberSaveable` weiter oben). Die
     * Zeile steht bewusst **nach** der Standort-Pruefung: Bricht die Funktion
     * vorher ab, hat auch nichts begonnen, das ein Fahrmodus zeigen koennte.
     */
    fun runRecording() {
        if (!isLocationEnabled(context)) {
            appViewModel.showMessage("Standortdienste sind deaktiviert.")
            return
        }
        val keepRoute = mode == MapMode.PLANEN
        RouteGenerationController.close()
        if (!keepRoute) {
            routeFromGenerator = false
            plannedRoute = null
            plannedFor = null
        }
        if (mode == MapMode.PLANEN) mode = MapMode.ERKUNDEN
        selectedPlace = null
        hoverPoint = null
        appViewModel.select(null)
        rideMode = true
        RecordingRepository.start(context)
    }

    fun startRecording() {
        withPermissions(PendingAction.RECORD) { runRecording() }
    }

    fun runGoToMyPosition() {
        locationGranted = true
        // Der Positions-Knopf ist zugleich der Weg zurueck zu „Karte folgt
        // mir" (siehe [LocateButton]).
        followMe = true
        scope.launch {
            if (!isLocationEnabled(context)) {
                appViewModel.showMessage("Standortdienste sind deaktiviert.")
                return@launch
            }
            // Erst ein frischer Fix (wie `_goToMyPosition` in Dart), sonst
            // das, was der Standortpunkt der Karte zuletzt gesehen hat.
            val position = currentLocation(context)?.let { it.latitude to it.longitude }
                ?: controller.lastKnownLocation()
            if (position == null) {
                appViewModel.showMessage("Position konnte nicht ermittelt werden.")
                return@launch
            }
            controller.moveTo(position.first, position.second, MIN_RECORDING_ZOOM)
        }
    }

    fun goToMyPosition() {
        withPermissions(PendingAction.LOCATE) { runGoToMyPosition() }
    }

    fun runUseMyPositionAsStart() {
        locationGranted = true
        scope.launch {
            // Bis zu zehn Sekunden Warten auf den Fix — bisher ohne jede
            // Anzeige (siehe `locating`).
            locating = true
            val position = try {
                currentLocation(context)
            } finally {
                locating = false
            }
            if (position == null) {
                appViewModel.showMessage("Position konnte nicht ermittelt werden.")
                return@launch
            }
            waypoints = listOf(Waypoint(position.latitude, position.longitude)) + waypoints
            controller.moveTo(position.latitude, position.longitude, MIN_RECORDING_ZOOM)
        }
    }

    fun useMyPositionAsStart() {
        withPermissions(PendingAction.PLAN_START) { runUseMyPositionAsStart() }
    }

    fun onMapTap(lat: Double, lon: Double) {
        if (mode != MapMode.PLANEN) return
        if (routeFromGenerator) {
            // Ein einziger Fehltipp machte aus der uebernommenen 48-km-Runde
            // einen einzelnen Wegpunkt — unwiederbringlich, „Letzten entfernen"
            // holt sie nicht zurueck. Die Runde bleibt deshalb stehen, bis die
            // Nutzerin das eigene Planen ausdruecklich bestaetigt; der Tipp
            // selbst ist dann der erste Wegpunkt und geht nicht verloren.
            scope.launch {
                val answer = snackbarHostState.showSnackbar(
                    message = "Die übernommene Runde bleibt stehen.",
                    actionLabel = "Selbst planen",
                    duration = SnackbarDuration.Long,
                )
                if (answer == SnackbarResult.ActionPerformed) {
                    routeFromGenerator = false
                    plannedRoute = null
                    plannedFor = null
                    waypoints = listOf(Waypoint(lat, lon))
                    planSheetExpanded = false
                }
            }
            return
        }
        val hit = waypoints.indexOfFirst { waypoint ->
            controller.isWithinScreenDistance(
                TrackPoint(lat = waypoint.lat, lon = waypoint.lon),
                lat,
                lon,
                waypointTouchRadiusPx,
            )
        }
        waypoints = if (hit >= 0) {
            waypoints.filterIndexed { index, _ -> index != hit }
        } else {
            // Wer auf die Karte tippt, arbeitet mit der Karte: Das Blatt geht
            // beim ersten Wegpunkt zu und gibt sie frei.
            if (waypoints.isEmpty()) planSheetExpanded = false
            waypoints + Waypoint(lat, lon)
        }
    }

    /**
     * Oeffnet das Suchblatt.
     *
     * ## Zwei Aufrufmodi
     * Ohne [onPicked] (der Normalfall — die Suchzeile im Erkunden-Blatt) landet der
     * gewaehlte Ort in [selectedPlace] und zeigt die Ortskarte ([PlaceCard]).
     * Mit [onPicked] wird das Blatt zum reinen Ortswaehler: Die Auswahl geht
     * ausschliesslich an [onPicked], [selectedPlace] bleibt unberuehrt und die
     * Ortskarte erscheint nicht. Gedacht fuer Folge-Screens, die einen Ort an
     * einer eigenen Stelle brauchen (z. B. eine einzelne Zeile einer
     * Planungsliste) — sie rufen `openPlaceSearch { ort -> … }` auf und
     * bekommen den gewaehlten Ort direkt zurueck, ohne je die Ortskarte zu
     * sehen.
     */
    fun openPlaceSearch(onPicked: ((Place) -> Unit)? = null) {
        searchPickerCallback = onPicked
        searchQuery = ""
        searchResults = emptyList()
        searchError = null
        searchOpen = true
    }

    /** Schliesst das Suchblatt und raeumt einen offenen Ortswaehler-Aufruf ab. */
    fun closeSearchSheet() {
        searchOpen = false
        searchPickerCallback = null
    }

    /**
     * Waehlt [place] aus dem Suchblatt — ein Nominatim-Treffer oder ein
     * Eintrag aus „Zuletzt gesucht" (beide sind zu diesem Zeitpunkt schon ein
     * [Place], siehe `SearchSheet.kt`). Schliesst das Blatt und bedient je
     * nach Aufrufmodus entweder den Ortswaehler-Callback oder die normale
     * Ortskarte (siehe [openPlaceSearch]).
     */
    fun onPlaceChosen(place: Place) {
        val picker = searchPickerCallback
        closeSearchSheet()
        appViewModel.recordPlaceSearchHistory(
            PlaceSearchHistoryEntry(place.displayName, place.lat, place.lon),
        )
        if (picker != null) {
            picker(place)
            return
        }
        selectedPlace = place
        controller.moveTo(place.lat, place.lon, MIN_RECORDING_ZOOM)
    }

    /**
     * „Route hierher" auf der Ortskarte ([MapMode.ERKUNDEN]): wechselt in
     * [MapMode.PLANEN] und setzt die Startwegpunkte — die eigene Position
     * (falls gerade bekannt, siehe unten) und den gewaehlten Ort, beide
     * benannt (siehe `Waypoint.name`, seit dem Umbau auf [MapMode] verfuegbar).
     *
     * Fragt bewusst **nicht** erst nach der Standortfreigabe: Wer einen Ort
     * antippt, will eine Route zu ihm sehen, keinen Berechtigungsdialog. Ohne
     * Freigabe (oder ohne Fix binnen der ueblichen Wartezeit, siehe
     * [currentLocation]) bleibt der Ort schlicht der einzige Wegpunkt — „Position
     * als Start" im aufgeklappten Planungsblatt fragt danach ausdruecklich,
     * genau fuer diesen Fall.
     */
    fun runRouteToPlace(place: Place) {
        if (generation.target != null) discardGeneratedRoute()
        routeFromGenerator = false
        plannedRoute = null
        plannedFor = null
        planError = null
        selectedPlace = null
        appViewModel.select(null)
        mode = MapMode.PLANEN
        planSheetExpanded = true
        controller.moveTo(place.lat, place.lon, MIN_RECORDING_ZOOM)
        scope.launch {
            locating = true
            val position = try {
                currentLocation(context)
            } finally {
                locating = false
            }
            val start = position?.let {
                Waypoint(it.latitude, it.longitude, name = MY_LOCATION_WAYPOINT_NAME)
            }
            waypoints = listOfNotNull(start) + Waypoint(place.lat, place.lon, name = place.displayName)
        }
    }

    /**
     * „Runde ab hier" auf der Ortskarte ([MapMode.ERKUNDEN]): reicht den Ort
     * direkt als Startpunkt an den Rundkurs-Generator weiter — derselbe
     * [RouteGenerationController], den auch [startRoundTrip] und die
     * Trainingsempfehlung fuellen.
     *
     * Anders als [startRoundTrip] (Distanz-Chips im Planungsblatt) fragt diese
     * Kachel nicht erst nach einer Distanz: Der Ort ist die einzige Angabe,
     * die die Nutzerin hier macht, also gilt [PLACE_ROUND_TRIP_DEFAULT_KM] —
     * dieselbe Zahl wie der erste, haeufigste Distanz-Chip. Der Startpunkt ist
     * bereits bekannt (der angetippte Ort), deshalb entfaellt auch der sonst
     * noetige GPS-Fix samt Standortfreigabe komplett — [RouteGenerationController.start]
     * nimmt ihn direkt entgegen (siehe dessen KDoc: der Startpunkt ist ein
     * expliziter Parameter, keine intern ermittelte Position).
     */
    fun runRoundTripFromPlace(place: Place) {
        selectedPlace = null
        controller.moveTo(place.lat, place.lon, MIN_RECORDING_ZOOM)
        RouteGenerationController.open(
            RouteTarget(
                distanceKm = PLACE_ROUND_TRIP_DEFAULT_KM,
                ascentPreference = AscentPreference.MODERAT,
                durationH = null,
                speedKmh = 0.0,
                intensity = SessionIntensity.GRUNDLAGE,
                label = SELF_PLANNED_ROUTE_LABEL,
                source = RouteTargetSource.SELBST_GEWAEHLT,
            ),
        )
        RouteGenerationController.start(
            start = TrackPoint(lat = place.lat, lon = place.lon),
            fromMapCenter = false,
            onMessage = appViewModel::showMessage,
        )
    }

    /**
     * „Als Wegpunkt" auf der Ortskarte ([MapMode.PLANEN]): haengt den
     * benannten Ort ans Ende der Wegpunktliste — dieselbe Stelle, an die auch
     * ein Kartentipp einen namenlosen Wegpunkt haengt (siehe [onMapTap]),
     * samt derselben Sonderregel fuer eine noch nicht bestaetigte
     * uebernommene Runde.
     */
    fun addPlaceAsWaypoint(place: Place) {
        selectedPlace = null
        if (routeFromGenerator) {
            scope.launch {
                val answer = snackbarHostState.showSnackbar(
                    message = "Die übernommene Runde bleibt stehen.",
                    actionLabel = "Selbst planen",
                    duration = SnackbarDuration.Long,
                )
                if (answer == SnackbarResult.ActionPerformed) {
                    routeFromGenerator = false
                    plannedRoute = null
                    plannedFor = null
                    waypoints = listOf(Waypoint(place.lat, place.lon, name = place.displayName))
                    planSheetExpanded = false
                }
            }
            return
        }
        waypoints = waypoints + Waypoint(place.lat, place.lon, name = place.displayName)
        controller.moveTo(place.lat, place.lon, MIN_RECORDING_ZOOM)
    }

    fun shareRoute(name: String, points: List<TrackPoint>) {
        if (points.isEmpty()) {
            appViewModel.showMessage("Keine Punkte zum Teilen.")
            return
        }
        scope.launch {
            runCatching { shareGpxFile(context, name, points) }
                .onFailure {
                    appViewModel.showMessage(
                        "Teilen fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}",
                    )
                }
        }
    }

    fun startDownload() {
        if (downloadState.running) return
        val bounds = controller.visibleBounds()
        val zoom = controller.currentZoom()
        if (bounds == null || zoom == null) {
            appViewModel.showMessage("Karte ist noch nicht bereit.")
            return
        }
        // Alle Grenzen (sinnvolle Groesse, Kachelzahl, Zoombereich) steckt
        // `planOfflineDownload` — reine Rechnung, in OfflineTileMath.kt
        // getestet.
        when (val plan = planOfflineDownload(bounds, zoom, mapStyle)) {
            is OfflineDownloadPlan.Rejected -> appViewModel.showMessage(plan.message)
            // Bewusst nicht in `scope` (der stirbt beim Tab-Wechsel mitsamt
            // dem halbfertigen Download), sondern im App-Scope; die
            // Abschlussmeldung kommt ueber den geteilten Meldungskanal zurueck.
            is OfflineDownloadPlan.Ready -> OfflineDownloadController.start(
                context = context,
                style = mapStyle,
                bounds = bounds,
                plan = plan,
                name = "${mapStyle.label} · ${formatToday()}",
                onMessage = appViewModel::showMessage,
            )
        }
    }

    /**
     * Startet die Rundkurs-Suche. Startpunkt ist die aktuelle Position; ohne
     * Fix (oder ohne Freigabe) die Kartenmitte — das Panel weist darauf hin.
     * Die Suche selbst laeuft im [RouteGenerationController] und ueberlebt
     * damit den Tab-Wechsel.
     */
    fun runGenerateRoutes() {
        locationGranted = hasLocationPermission(context)
        scope.launch {
            locating = true
            val position = try {
                currentLocation(context)
            } finally {
                locating = false
            }
            val start = if (position != null) {
                TrackPoint(lat = position.latitude, lon = position.longitude)
            } else {
                controller.rememberCamera()?.let { TrackPoint(lat = it.lat, lon = it.lon) }
            }
            if (start == null) {
                appViewModel.showMessage(
                    "Kein Startpunkt: Position unbekannt und die Karte ist noch nicht bereit.",
                )
                return@launch
            }
            RouteGenerationController.start(
                start = start,
                fromMapCenter = position == null,
                onMessage = appViewModel::showMessage,
            )
        }
    }

    fun generateRoutes() {
        withPermissions(PendingAction.GENERATE_ROUTES) { runGenerateRoutes() }
    }

    /**
     * „Runde ab hier über X km" — der Einstieg von der Karte aus.
     *
     * Bis hierher gab es ihn nicht: Das Rundkurs-Panel erschien einzig ueber
     * ein Ziel aus dem Heute- oder Trainings-Tab (`pendingRouteTarget`), an
     * einem Ruhetag also gar nicht, und eine eigene Distanz liess sich nirgends
     * eingeben — obwohl das der haeufigste Wunsch ueberhaupt ist und die
     * gesamte Rechenmaschinerie bereitstand.
     *
     * Gebaut wird daraus dasselbe [RouteTarget], das auch das Training
     * schickt; die uebrigen Felder sind bewusst neutral gesetzt:
     * [AscentPreference.MODERAT] als Mitte zwischen flach und bergig (die
     * Nutzerin hat nur eine Distanz genannt, kein Profil),
     * [SessionIntensity.GRUNDLAGE] als haeufigster Fall und keine Dauer —
     * geschaetzte Stunden gehoeren zu einem Trainingsziel, nicht zu einer
     * frei gewaehlten Runde.
     *
     * Den Startpunkt bestimmt [runGenerateRoutes] wie gehabt: eigene Position,
     * ersatzweise die Kartenmitte (darauf weist das Panel dann ausdruecklich
     * hin, und „Neu suchen" nimmt ihn spaeter noch einmal auf).
     */
    fun startRoundTrip(distanceKm: Double) {
        RouteGenerationController.open(
            RouteTarget(
                distanceKm = distanceKm,
                ascentPreference = AscentPreference.MODERAT,
                durationH = null,
                speedKmh = 0.0,
                intensity = SessionIntensity.GRUNDLAGE,
                label = SELF_PLANNED_ROUTE_LABEL,
                // Seit `:core` dafuer einen eigenen Wert kennt: Ueber einer
                // selbst eingetippten Distanz stand vorher „(Tagesempfehlung)".
                source = RouteTargetSource.SELBST_GEWAEHLT,
            ),
        )
        // Das Blatt zu: Waehrend der Suche gehoert der Platz dem
        // Rundkurs-Panel und der Karte.
        planSheetExpanded = false
        generateRoutes()
    }

    fun runNavigateRide(ride: Ride) {
        pendingNavigateRideId = null
        locationGranted = true
        navState = null
        navRideId = ride.id
        navLabel = ride.name
        navTarget = NavigationTarget(ride.id, ride.name, ride.points)
        // Der Regelfall aus `MapMode.kt`: Navigation einer gespeicherten Tour
        // ist ihr eigener, exklusiver Modus (nur aus [MapMode.ERKUNDEN]
        // erreichbar — waehrend [MapMode.PLANEN] laesst sich keine Tour
        // auswaehlen).
        mode = MapMode.NAVIGIEREN
    }

    fun navigateRide(ride: Ride) {
        if (ride.points.size < 2) {
            appViewModel.showMessage("Die Tour hat zu wenige Punkte für die Navigation.")
            return
        }
        // Die Tour merken, falls der Systemdialog die Activity neu aufbaut.
        pendingNavigateRideId = ride.id
        withPermissions(PendingAction.NAVIGATE_RIDE) { runNavigateRide(ride) }
    }

    /**
     * Navigiert die geplante Route — **ohne** `mode` anzufassen.
     *
     * Die Ausnahme aus `MapMode.kt`: Ausgeloest wird das ausschliesslich vom
     * „Navigieren"-Knopf im Planungsblatt, `mode` steht also bereits auf
     * [MapMode.PLANEN] und bleibt es. Ein Wechsel nach [MapMode.NAVIGIEREN]
     * wuerde [runRecording] das Signal nehmen, mit dem es entscheidet, ob die
     * Route eine anschliessende Aufzeichnung ueberlebt.
     */
    fun runNavigatePlannedRoute() {
        val route = plannedRoute ?: return
        if (route.points.size < 2) return
        locationGranted = true
        navState = null
        navRideId = null
        navLabel = PLANNED_ROUTE_LABEL
        navTarget = NavigationTarget(null, PLANNED_ROUTE_LABEL, route.points)
    }

    fun navigatePlannedRoute() {
        if ((plannedRoute?.points?.size ?: 0) < 2) return
        withPermissions(PendingAction.NAVIGATE_ROUTE) { runNavigatePlannedRoute() }
    }

    /**
     * Fuehrt die zu [action] gehoerende `run…`-Funktion aus — die Berechtigung
     * gilt an dieser Stelle als erteilt, das haben die beiden Aufrufer schon
     * geprueft. Gemeinsame Stelle fuer den Effekt unten (frisch erteilte
     * Freigabe) und [retryLocationPermission] („Erneut fragen" auf einer
     * zuvor verweigerten): Beide fuehren am Ende dieselbe Absicht aus, nur
     * ueber verschiedene Wege dorthin.
     */
    fun runPendingAction(action: PendingAction) {
        when (action) {
            PendingAction.RECORD -> runRecording()
            PendingAction.LOCATE -> runGoToMyPosition()
            PendingAction.PLAN_START -> runUseMyPositionAsStart()
            PendingAction.NAVIGATE_ROUTE -> runNavigatePlannedRoute()
            PendingAction.GENERATE_ROUTES -> runGenerateRoutes()
            PendingAction.NAVIGATE_RIDE -> {
                val rideId = pendingNavigateRideId
                pendingNavigateRideId = null
                rides.firstOrNull { it.id == rideId }?.let { runNavigateRide(it) }
            }
        }
    }

    /**
     * „Erneut fragen" auf [LocationPermissionNotice]: fragt dieselbe
     * Berechtigung fuer dieselbe gemerkte Absicht noch einmal an — derselbe
     * [withPermissions]-Pfad wie beim ersten Versuch. Der Hinweis raeumt sich
     * schon hier ab, nicht erst nach einer erneuten Ablehnung: Liegt die
     * Freigabe inzwischen laengst vor (z. B. ueber die System-Einstellungen
     * erteilt), lief `withPermissions` sofort durch, ohne den Launcher und
     * damit ohne dessen Aufraeumen unten im Callback zu beruehren.
     */
    fun retryLocationPermission() {
        val action = locationDeniedAction ?: return
        locationDeniedAction = null
        withPermissions(action) { runPendingAction(action) }
    }

    // Nachgereichte Absicht ausfuehren, sobald die Freigabe erteilt wurde. Der
    // Launcher-Callback selbst kann die Aktionen oben nicht aufrufen (lokale
    // Funktionen, die erst nach ihm im Rumpf stehen), deshalb der Umweg ueber
    // [grantedAction].
    LaunchedEffect(grantedAction) {
        val action = grantedAction ?: return@LaunchedEffect
        grantedAction = null
        runPendingAction(action)
    }

    // ------------------------------------- Trainingsempfehlung → Routenziel
    // Das Ziel wartet als StateFlow im AppViewModel, bis dieser Screen nach dem
    // Tab-Wechsel wirklich in der Komposition ist (siehe dessen KDoc).
    LaunchedEffect(pendingRouteTarget) {
        val target = pendingRouteTarget ?: return@LaunchedEffect
        appViewModel.consumeRouteTarget()
        if (mode == MapMode.PLANEN) exitPlanning()
        appViewModel.select(null)
        RouteGenerationController.open(target)
    }

    // -------------------------------------- Startseite → Aufzeichnung starten
    // Dasselbe Muster wie eben bei [pendingRouteTarget]: Die Bitte wartet im
    // AppViewModel, bis dieser Screen nach dem Tab-Wechsel wirklich in der
    // Komposition ist. Ausgeloest wird **derselbe** Pfad wie am gruenen
    // Aufnahme-Knopf — dieselbe lokale Funktion [startRecording], die zuerst
    // die Berechtigungen prueft und erst danach ueber [runRecording] startet.
    // Ein zweiter, eigener Startweg fuer die Startseite wuerde die
    // Berechtigungslogik entweder verdoppeln oder umgehen; so bleibt es bei
    // genau einer Stelle, und die Standortabfrage erscheint dort, wo sie auch
    // sonst erscheint — auf der Karte, nicht auf der Startseite.
    LaunchedEffect(pendingRecordStart) {
        if (!pendingRecordStart) return@LaunchedEffect
        appViewModel.consumeRecordStart()
        // Laeuft schon eine Aufzeichnung, ist die Bitte bereits erfuellt —
        // ein erneuter Aufruf wuerde nur unnoetig Planung/Auswahl zuruecksetzen
        // (siehe [runRecording]), ohne dass sich am Zustand etwas aendert.
        if (isRecording) return@LaunchedEffect
        startRecording()
    }

    // Der ausgewaehlte Vorschlag ist die Vorschau auf der Karte: Er landet in
    // demselben `plannedRoute`, das auch die Planung von Hand fuellt — also in
    // der blauen, gestrichelten Routenebene aus `MapViewHost.kt`.
    // Schluessel bewusst nur aus billigen Werten: `candidates` traegt komplette
    // Punktlisten, ein Vergleich davon liefe bei jeder Rekomposition mit.
    // (Ziel, Seed, Zahl der Vorschlaege, Auswahl) benennt den Vorschlag genauso
    // eindeutig.
    LaunchedEffect(
        generation.target,
        generation.seed,
        generation.candidates.size,
        generation.selectedIndex,
    ) {
        if (generation.target == null) return@LaunchedEffect
        val candidate = generation.selected
        if (candidate == null) {
            if (routeFromGenerator) {
                routeFromGenerator = false
                plannedRoute = null
            }
            return@LaunchedEffect
        }
        routeFromGenerator = true
        plannedRoute = candidate.route
        controller.fitToPoints(candidate.route.points)
    }

    // ------------------------------------------------ Touren-Tab → Kartenscreen
    // Seit dem Wegfall des eigenen Touren-Tabs (siehe `ui/TrailscapeApp.kt`,
    // „Warum Touren und Karte eine Seite sind") bittet die Huelle hier statt
    // eines Tab-Wechsels nur noch darum, das Tourenblatt aufzuschlagen.
    val tourSheetRequest by appViewModel.tourSheetRequest.collectAsStateWithLifecycle()
    LaunchedEffect(tourSheetRequest) {
        if (tourSheetRequest) {
            toursExpanded = true
            appViewModel.consumeTourSheetRequest()
        }
    }

    // Von der Startseite (oder anderswo) angeforderte Tourendetailansicht —
    // wortgleich uebernommen aus dem frueheren `ui/rides/TourList.kt`: Erst
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

    // Verschwindet die geoeffnete Tour aus der Liste (Sync, Loeschen aus dem
    // Blatt), ohne dass die Detailansicht selbst geloescht hat, schliesst sie
    // sich von selbst statt eine nicht mehr existierende Tour anzuzeigen —
    // wortgleiche Uebernahme derselben Regel aus `ui/rides/TourList.kt`.
    LaunchedEffect(rides) {
        if (detailRideId != null && rides.none { it.id == detailRideId }) {
            detailRideId = null
        }
    }

    // -------------------------------------------------- Tourenblatt: Rangfolge
    // Ausformuliert im Klassen-KDoc oben („Die Tourenliste und ihre Rangfolge
    // am unteren Kartenrand"); hier nur die Umsetzung. Bewusst ein eigener
    // Effekt statt einer reinen Ableitung: Eine schon aufgeschlagene
    // Tourenliste soll beim Eintreten eines Vorrang-Zustands **dauerhaft**
    // wieder einklappen — mit einer reinen Ableitung bliebe
    // `toursExpanded` unveraendert `true` und spraenge sofort wieder auf,
    // sobald der Vorrang-Zustand endet. Eine abgeleitete „Sichtbarkeits"-
    // Variable braucht es dagegen nicht mehr: Das Erkunden-Blatt ist in all
    // diesen Zustaenden ohnehin nicht komponiert (siehe die Bedingung um den
    // `ExploreSheet`-Aufruf weiter unten).
    val tourSheetPriorityActive =
        isRecording || navTarget != null || mode == MapMode.PLANEN ||
            selectedRide != null || selectedPlace != null || searchOpen
    LaunchedEffect(tourSheetPriorityActive) {
        if (tourSheetPriorityActive && toursExpanded) {
            toursExpanded = false
        }
    }

    // Zurueck-Geste: erst die Tourenliste wieder einklappen, dann die Planung
    // (mit derselben Rueckhol-Snackbar wie der Knopf „Planung beenden"),
    // sonst das normale Verhalten der App. Die Tourendetailansicht braucht
    // hier keinen Fall — sie faengt die Geste bereits als eigenes
    // Dialogfenster ab (siehe unten und das Klassen-KDoc oben).
    BackHandler(enabled = toursExpanded || mode == MapMode.PLANEN) {
        if (toursExpanded) {
            toursExpanded = false
        } else {
            exitPlanningWithUndo("Planung beendet.")
        }
    }

    // ------------------------------------------------------------------ Aufbau
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    Scaffold(
        // Die Huelle (TrailscapeApp) hat die System-Insets schon aufgeloest.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Ueber der schwebenden Navigationskapsel, nicht dahinter.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalFloatingNavigationBarSpace.current),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            MapViewHost(
                controller = controller,
                style = mapStyle,
                locationEnabled = locationGranted,
                onMapTap = ::onMapTap,
                onUserPan = { followMe = false },
                modifier = Modifier.fillMaxSize(),
                // Im Fahrmodus liegt die Karte vollstaendig verdeckt dahinter.
                // Sie dann weiterzeichnen zu lassen waere ausgerechnet in dem
                // Modus teuer, der fuer mehrstuendige Touren gedacht ist — und
                // der ohnehin schon den Bildschirm anlaesst.
                renderingActive = !rideMode,
            )

            // ------------------------------------------------------------ oben
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(OverlayScreenPadding),
                verticalArrangement = Arrangement.spacedBy(OverlayGap),
            ) {
                // Keine Knopfreihe mehr an dieser Kante: Suche, „Route
                // planen", Kartenstil und Offline wohnen im Erkunden-Gesicht
                // des unteren Blatts (siehe `ExploreSheet.kt`). Oben bleiben
                // nur Zustaende, die sich ueber die Karte legen MUESSEN
                // (Hinweise, Navigation, Generator, Downloadfortschritt).
                locationDeniedAction?.let {
                    LocationPermissionNotice(
                        text = "Standortfreigabe wurde abgelehnt – ohne sie geht es hier " +
                            "nicht weiter.",
                        onRetry = ::retryLocationPermission,
                        onDismiss = { locationDeniedAction = null },
                    )
                }

                if (impreciseLocationNotice) {
                    LocationPermissionNotice(
                        text = "Zum Aufzeichnen wird der genaue Standort gebraucht. " +
                            "Wähle in der Abfrage „Genau“ statt „Ungefähr“.",
                        onRetry = {
                            impreciseLocationNotice = false
                            startRecording()
                        },
                        onDismiss = { impreciseLocationNotice = false },
                    )
                }

                navTarget?.let { target ->
                    NavigationCard(
                        label = target.label,
                        remainingKm = navState?.remainingKm ?: navTotalKm,
                        doneKm = navState?.doneKm,
                        offRoute = navState?.offRoute == true,
                        onStop = ::stopNavigation,
                    )
                }

                if (generation.target != null) {
                    RouteGenerationPanel(
                        state = generation,
                        maxHeight = screenHeight * PLAN_PANEL_MAX_HEIGHT_FACTOR,
                        locating = locating,
                        onStart = ::generateRoutes,
                        onCancel = RouteGenerationController::cancel,
                        onSelect = RouteGenerationController::select,
                        onNextSuggestions = {
                            RouteGenerationController.nextSuggestions(appViewModel::showMessage)
                        },
                        onApply = ::applyGeneratedRoute,
                        onDiscard = ::discardGeneratedRoute,
                    )
                }

                if (downloadState.running) {
                    DownloadProgressCard(
                        done = downloadState.completedTiles,
                        total = downloadState.totalTiles,
                    )
                }
            }

            // ----------------------------------------------------------- unten
            // Hier stapelt sich alles, was den Blick auf die Karte am wenigsten
            // verstellt: die beiden runden Knoepfe, darunter die Live-Leiste
            // bzw. die Tour-Karte und ganz unten das Planungsblatt. Dass die
            // Planung hier und nicht mehr oben liegt, ist der Kern der
            // Umstellung — die Knoepfe stehen jetzt *ueber* ihr statt auf ihr.
            // Waehrend die Suche offen ist, bleibt dieser Stapel weg. Beide
            // Stapel haengen an gegenueberliegenden Kanten derselben Box; mit
            // aufgeklappter Tastatur ist dazwischen so wenig Platz, dass das
            // Planungsblatt von unten in die Trefferliste der Suche lief und sie
            // halb verdeckte. Ein Suchtreffer ist ausserdem genau der Moment, in
            // dem niemand die Aufnahme- oder Standortknoepfe braucht — und
            // sobald die Suche zu ist, steht alles unveraendert wieder da.
            if (!searchOpen) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(OverlayScreenPadding)
                        // Die Navigationskapsel schwebt ueber der Karte (siehe
                        // ui/TrailscapeApp.kt). Der ganze Stapel rueckt deshalb um
                        // ihre Hoehe nach oben — sonst laege das Planungsblatt
                        // teilweise hinter ihr.
                        .padding(bottom = LocalFloatingNavigationBarSpace.current),
                    horizontalAlignment = Alignment.End,
                ) {
                    RecordButton(
                        recording = isRecording,
                        onClick = { if (isRecording) RecordingRepository.stop() else startRecording() },
                    )
                    Spacer(Modifier.height(12.dp))
                    LocateButton(onClick = ::goToMyPosition, following = followMe)
                    Spacer(Modifier.height(12.dp))

                    val ride = selectedRide
                    val place = selectedPlace
                    when {
                        isRecording -> LiveRecordingCard(
                            speedKmh = speedKmh,
                            distanceKm = recordedKm,
                            elapsedS = (elapsedMs / 1000).toInt(),
                            ascentM = liveAscentM,
                            pointCount = livePoints.size,
                            paused = isPaused,
                            onTogglePause = { RecordingRepository.togglePause() },
                            onStop = { RecordingRepository.stop() },
                            onOpenRideMode = { rideMode = true },
                        )

                        ride != null -> RideCard(
                            ride = ride,
                            navigating = navTarget?.rideId == ride.id,
                            onNavigate = { navigateRide(ride) },
                            onShare = { shareRoute(ride.name, ride.points) },
                            onDelete = { deleteDialogRide = ride },
                            onClose = {
                                hoverPoint = null
                                appViewModel.select(null)
                            },
                            onHoverPoint = { hoverPoint = it },
                        )

                        place != null -> PlaceCard(
                            place = place,
                            mode = mode,
                            // Synchron aus dem Standortpunkt der Karte gelesen
                            // (siehe dessen KDoc): kein zweiter GPS-Abonnent
                            // nur fuer diese eine Entfernungszahl.
                            distanceKm = controller.lastKnownLocation()?.let { (lat, lon) ->
                                haversineM(
                                    TrackPoint(lat = lat, lon = lon),
                                    TrackPoint(lat = place.lat, lon = place.lon),
                                ) / 1000.0
                            },
                            onRouteHere = { runRouteToPlace(place) },
                            onRoundTripHere = { runRoundTripFromPlace(place) },
                            onAddWaypoint = { addPlaceAsWaypoint(place) },
                            onClose = { selectedPlace = null },
                        )
                    }

                    if (mode == MapMode.PLANEN) {
                        Spacer(Modifier.height(OverlayGap))
                        PlanningSheet(
                            expanded = planSheetExpanded,
                            onExpandedChange = { planSheetExpanded = it },
                            profile = routeProfile,
                            onProfileChange = { routeProfile = it },
                            waypoints = waypoints,
                            route = plannedRoute,
                            busy = planBusy,
                            error = planError,
                            maxHeight = screenHeight * PLAN_SHEET_MAX_HEIGHT_FACTOR,
                            progress = planProgress,
                            generated = routeFromGenerator,
                            source = routeSource,
                            locating = locating,
                            onRoundTrip = ::startRoundTrip,
                            onUseMyPosition = ::useMyPositionAsStart,
                            onRemoveWaypoint = { index ->
                                waypoints = waypoints.filterIndexed { i, _ -> i != index }
                            },
                            onAddWaypointViaSearch = { openPlaceSearch { place -> addPlaceAsWaypoint(place) } },
                            onUndo = {
                                routeFromGenerator = false
                                waypoints = waypoints.dropLast(1)
                            },
                            onClear = {
                                // Wie „Planung beenden": Der Fehlgriff darf nicht
                                // das Ende der Arbeit sein (siehe
                                // [exitPlanningWithUndo]) — nur bleibt der
                                // Planungsmodus hier an.
                                val snapshot = PlanningSnapshot(
                                    waypoints = waypoints,
                                    route = plannedRoute,
                                    plannedFor = plannedFor,
                                    fromGenerator = routeFromGenerator,
                                )
                                routeFromGenerator = false
                                waypoints = emptyList()
                                plannedRoute = null
                                plannedFor = null
                                planError = null
                                planSheetExpanded = true
                                if (!snapshot.isEmpty) {
                                    scope.launch {
                                        val answer = snackbarHostState.showSnackbar(
                                            message = "Planung geleert.",
                                            actionLabel = "Rückgängig",
                                            duration = SnackbarDuration.Long,
                                        )
                                        if (answer == SnackbarResult.ActionPerformed) {
                                            restorePlanning(snapshot)
                                        }
                                    }
                                }
                            },
                            onSave = { saveRouteDialog = true },
                            onShare = {
                                plannedRoute?.let { shareRoute("trailscape-route", it.points) }
                            },
                            onNavigate = ::navigatePlannedRoute,
                            onHoverPoint = { hoverPoint = it },
                            onClose = { exitPlanningWithUndo("Planung beendet.") },
                        )
                    }

                    // Das eine untere Blatt (siehe `ExploreSheet.kt`): Suche,
                    // Werkzeuge und die Tourenliste als aufziehbarer Koerper —
                    // aber nur, wenn kein anderes Gesicht dran ist — keine
                    // Aufzeichnung, keine gewaehlte Tour, kein Ort, keine
                    // Planung, keine Navigation und kein offener
                    // Generator-Vorschlag (dessen Panel liegt oben; zwei
                    // Werkzeugflaechen zugleich helfen niemandem). Das ist
                    // zugleich die Rangfolge aus dem Klassen-KDoc oben („Die
                    // Tourenliste und ihre Rangfolge am unteren Kartenrand"):
                    // In all diesen Faellen ist das Blatt schlicht nicht
                    // komponiert, kein eigener HIDDEN-Zustand noetig.
                    if (mode == MapMode.ERKUNDEN && !isRecording && ride == null &&
                        place == null && navTarget == null && generation.target == null
                    ) {
                        Spacer(Modifier.height(OverlayGap))
                        ExploreSheet(
                            expanded = toursExpanded,
                            onExpandedChange = { toursExpanded = it },
                            rideCount = rides.size,
                            toursMaxHeight = screenHeight * TOUR_SHEET_MAX_HEIGHT_FACTOR,
                            onOpenSearch = { openPlaceSearch() },
                            onStartPlanning = ::enterPlanning,
                            onOpenStyle = { showStyleSheet = true },
                            onDownload = ::startDownload,
                            downloadEnabled = !downloadState.running,
                        ) { padding ->
                            TourListContent(
                                appViewModel = appViewModel,
                                onOpenDetail = { detailRideId = it },
                                onShowOnMap = { ride ->
                                    // Kein manueller `controller.fitToPoints`
                                    // hier: Der Effekt auf `selectedRide?.id`
                                    // weiter oben zoomt schon automatisch auf
                                    // jede neu ausgewaehlte Tour — ein
                                    // zweiter Aufruf waere nur ein doppelter.
                                    appViewModel.select(ride.id)
                                    toursExpanded = false
                                },
                                contentPadding = padding,
                            )
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- Dialoge
    if (searchOpen) {
        SearchSheet(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            busy = searchBusy,
            error = searchError,
            results = searchResults,
            history = placeSearchHistory.map { Place(it.displayName, it.lat, it.lon) },
            onSelect = ::onPlaceChosen,
            onDismiss = ::closeSearchSheet,
        )
    }

    if (showStyleSheet) {
        MapStyleSheet(
            current = mapStyle,
            onSelect = {
                appViewModel.setMapStyle(it)
                showStyleSheet = false
            },
            onDismiss = { showStyleSheet = false },
        )
    }

    if (saveRouteDialog) {
        val route = plannedRoute
        if (route == null) {
            saveRouteDialog = false
        } else {
            NameDialog(
                title = "Name der Route",
                suggestion = "Route ${formatToday()}",
                confirmLabel = "Speichern",
                onDismiss = { saveRouteDialog = false },
                onConfirm = { name ->
                    saveRouteDialog = false
                    appViewModel.addRide(rideFromPlannedRoute(name, route))
                    exitPlanning()
                },
            )
        }
    }

    // ---------------------------------------------------------- Fahrmodus
    // Liegt als eigenes Fenster ueber allem (siehe `RideModeScreen.kt`) und
    // bekommt ausschliesslich fertige Werte: dieselben Aufzeichnungs-Flows wie
    // die Live-Leiste und den Navigationszustand, den der Effekt oben aus dem
    // `RouteNavigator` (`:core`) mitschreibt. Gerechnet wird dort nichts.
    if (rideMode && isRecording) {
        RideModeScreen(
            speedKmh = speedKmh,
            distanceKm = recordedKm,
            elapsedS = (elapsedMs / 1000).toInt(),
            ascentM = liveAscentM,
            paused = isPaused,
            navigation = navTarget?.let { target ->
                RideModeNavigation(
                    label = target.label,
                    remainingKm = navState?.remainingKm ?: navTotalKm,
                    offRoute = navState?.offRoute == true,
                )
            },
            onTogglePause = { RecordingRepository.togglePause() },
            onStop = {
                // Zurueck auf die Karte: Nach dem Stopp waehlt das AppViewModel
                // die gespeicherte Tour aus und meldet sie — das gehoert auf
                // die Karte, nicht hinter eine leere Fahranzeige.
                rideMode = false
                RecordingRepository.stop()
            },
            onClose = { rideMode = false },
        )
    }

    // ---------------------------------------------------------- Tourendetail
    // Eigenes Fenster aus demselben Grund wie der Fahrmodus (siehe dessen
    // KDoc „Warum ein eigenes Fenster" oben): Die schwebende Navigationskapsel
    // aus `ui/TrailscapeApp.kt` liegt als Geschwister-`Box` UEBER dem gesamten
    // `NavHost` und damit auch ueber jeder gewoehnlichen Ebene dieses Screens
    // — nur ein `Dialog` deckt sie mit ab und macht sie unbedienbar, solange
    // die Detailansicht offen ist. Die Zurueck-Geste braucht dafuer keinen
    // eigenen Fall (siehe Klassen-KDoc, Rangfolge der Zurueck-Geste): Ein
    // `Dialog` faengt sie bereits als eigenes Fenster ab, bevor sie den
    // `BackHandler` weiter oben ueberhaupt erreicht — [RideDetailHost] bringt
    // dafuer sogar schon einen eigenen `BackHandler` mit.
    detailRideId?.let { id ->
        Dialog(
            onDismissRequest = { detailRideId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // `usePlatformDefaultWidth = false` macht dieses Fenster randlos —
            // dieselbe Falle wie beim Fahrmodus (siehe dessen KDoc). Anders als
            // im `NavHost` von `TrailscapeApp.kt` sind die Systemleisten hier
            // NICHT schon aufgeloest: [RideDetailHost] (und mit ihm
            // `RideDetailScreen.kt`) setzt `contentWindowInsets = WindowInsets
            // (0, 0, 0, 0)` in der Annahme, dass genau das laengst geschehen
            // ist — eine Annahme, die in diesem eigenen Fenster nicht mehr
            // stimmt. Dieselbe Aufloesung (oben und seitlich; unten bewusst
            // nicht, siehe dort) wird deshalb hier wiederholt, sonst zeichnet
            // die Kopfzeile der Detailansicht unter die Statusleiste.
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

    // Fehlende Kartendaten: ein Angebot, keine Fehlermeldung. Die Route liegt
    // in diesem Moment schon vor (ueber den Server berechnet) — hier geht es
    // nur darum, ob das naechste Mal ohne Netz und schneller gehen soll.
    segmentOffer?.let { offer ->
        OneUiDialog(
            onDismissRequest = appViewModel::dismissSegmentOffer,
            icon = { Icon(Icons.Filled.DownloadForOffline, contentDescription = null) },
            title = { Text("Karten für Offline-Routing") },
            text = {
                Text(
                    "Für diese Gegend fehlen die Kartendaten: ${offer.title}, " +
                        "${formatBytes(offer.totalBytes)}. Danach berechnet die App Routen " +
                        "hier ohne Netz — meist schneller als über den Server.",
                )
            },
            confirmButton = {
                TextButton(onClick = { appViewModel.acceptSegmentOffer(context) }) {
                    Text("Jetzt laden")
                }
            },
            dismissButton = {
                TextButton(onClick = appViewModel::dismissSegmentOffer) { Text("Nicht jetzt") }
            },
        )
    }

    deleteDialogRide?.let { ride ->
        OneUiDialog(
            onDismissRequest = { deleteDialogRide = null },
            title = { Text("Tour löschen") },
            text = { Text("Soll „${ride.name}“ wirklich gelöscht werden?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDialogRide = null
                        if (navTarget?.rideId == ride.id) stopNavigation()
                        appViewModel.removeRide(ride.id)
                    },
                ) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogRide = null }) { Text("Abbrechen") }
            },
        )
    }
}

/**
 * Was nach einer erteilten Standortfreigabe passieren soll.
 *
 * Bewusst ein Aufzaehlungswert und kein Lambda: So laesst sich die Absicht in
 * `rememberSaveable` legen und ueberlebt einen Neuaufbau der Activity waehrend
 * des System-Dialogs (`NAVIGATE_RIDE` merkt sich die Tour zusaetzlich ueber
 * ihre ID). Enum-Werte sind `Serializable` und damit bundle-faehig.
 */
private enum class PendingAction {
    RECORD,
    LOCATE,
    PLAN_START,
    NAVIGATE_RIDE,
    NAVIGATE_ROUTE,
    GENERATE_ROUTES,
}

/** Was gerade navigiert wird — eine gespeicherte Tour oder die geplante Route. */
private data class NavigationTarget(
    /** ID der Tour, oder `null` bei der geplanten Route. */
    val rideId: String?,
    val label: String,
    val points: List<TrackPoint>,
)

/**
 * Alle Marker der Karte in einer Liste: Wegpunkte (gruen = Start, rot = Ziel,
 * blau dazwischen), Start und Ende der ausgewaehlten Tour, der ausgewaehlte
 * Ort (als Ring statt als gefuellter Punkt, siehe `MapMarker.filled`) und der
 * im Hoehenprofil abgelesene Punkt.
 */
private fun buildMapMarkers(
    planning: Boolean,
    waypoints: List<Waypoint>,
    ride: Ride?,
    place: Place?,
    hoverPoint: TrackPoint?,
): List<MapMarker> = buildList {
    if (ride != null && ride.points.size >= 2) {
        val first = ride.points.first()
        val last = ride.points.last()
        // Start ist ein Punkt, Ziel eine Zielscheibe — der Unterschied liegt in
        // der Form, nicht nur in der Farbe (siehe `startAndFinishMarkers` in
        // `rides/RideDetailScreen.kt`).
        add(MapMarker(first.lat, first.lon, GravelGreen.toArgb(), radius = 7f))
        addAll(finishMarkers(last.lat, last.lon))
    }
    if (planning) {
        waypoints.forEachIndexed { index, waypoint ->
            when (index) {
                // Erster und letzter Wegpunkt tragen dieselbe Unterscheidung
                // wie Start und Ziel einer gefahrenen Tour: Ein einzelner
                // Wegpunkt ist nur Start, noch kein Ziel.
                0 -> add(MapMarker(waypoint.lat, waypoint.lon, GravelGreen.toArgb(), radius = 8f))
                waypoints.lastIndex -> addAll(finishMarkers(waypoint.lat, waypoint.lon))
                else -> add(MapMarker(waypoint.lat, waypoint.lon, RouteBlue.toArgb(), radius = 8f))
            }
        }
    }
    place?.let { add(MapMarker(it.lat, it.lon, RouteBlue.toArgb(), radius = 10f, filled = false)) }
    hoverPoint?.let { add(MapMarker(it.lat, it.lon, HoverAmber.toArgb(), radius = 8f)) }
}

/**
 * Baut aus einer geplanten Route eine speicherbare Tour — wie
 * `_savePlannedRoute` in Dart: Distanz und Hoehenmeter kommen vom
 * Routing-Server, alles Uebrige aus [computeStats].
 *
 * ## Warum [Ride.planned] hier gesetzt wird
 * „Als Tour speichern" legte bis hierher eine ganz normale Tour an. Danach
 * meldete die Startseite die **geplanten** Kilometer als gefahren, der
 * Wochenfortschritt sprang und Fitness, Ermuedung und Form rechneten mit einer
 * Fahrt, die niemand gemacht hat — ausgeloest durch eine reine
 * Planungsaktion. Das Kennzeichen haelt die Planung aus allem heraus, was
 * „gefahren" meint (siehe `:core`: `riddenRides`), laesst sie in Tourenliste,
 * Export und Sync aber sichtbar.
 */
private fun rideFromPlannedRoute(name: String, route: PlannedRoute): Ride {
    val base = computeStats(route.points)
    return Ride(
        id = newRideId(),
        name = name,
        createdAt = System.currentTimeMillis(),
        stats = base.copy(distanceKm = route.distanceKm, ascentM = route.ascentM),
        points = route.points,
        planned = true,
    )
}

/** Auswahl des Kartenstils (Port des `_showStyleSheet`-Bottom-Sheets). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapStyleSheet(
    current: MapStyle,
    onSelect: (MapStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = CardPadding)) {
            Text(
                text = "Kartenstil",
                modifier = Modifier.padding(
                    start = CardPadding,
                    end = CardPadding,
                    top = 4.dp,
                    bottom = 8.dp,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            mapStyles.forEach { style ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = style.id == current.id,
                            onClick = { onSelect(style) },
                        )
                        .padding(horizontal = CardPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = style.id == current.id, onClick = { onSelect(style) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(style.label, style = MaterialTheme.typography.bodyLarge)
                        mapStyleSubtitle(style.id)?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Namensabfrage (`_askName` im Original). */
@Composable
private fun NameDialog(
    title: String,
    suggestion: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(suggestion) }
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifEmpty { suggestion }) },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/**
 * Teilt Punkte als GPX ueber das System-Share-Sheet. Dieselbe Mechanik wie in
 * der Tourenliste (Cache-Unterverzeichnis + FileProvider), hier aber fuer eine
 * geplante Route ohne [Ride]-Objekt.
 */
private suspend fun shareGpxFile(context: Context, name: String, points: List<TrackPoint>) {
    val uri = withContext(Dispatchers.IO) {
        // Gemeinsames Aufraeumen mit der Tourenliste: nur alte Exporte fliegen
        // raus, die gerade uebergebene Datei bleibt der Empfaenger-App
        // erhalten (siehe ui/ShareFiles.kt).
        val dir = prepareShareDirectory(context.cacheDir)
        val file = File(dir, "${safeFileName(name)}.gpx")
        file.writeText(buildGpx(name, points), Charsets.UTF_8)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/gpx+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        putExtra(Intent.EXTRA_TITLE, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Route teilen"))
}

private fun newRideId(): String {
    val suffix = Random.nextInt(0x1000000).toString(36)
    return "${System.currentTimeMillis()}-$suffix"
}

/**
 * Wartezeit, bevor eine Aenderung an den Wegpunkten wirklich gerechnet wird.
 *
 * Kurz genug, um nicht als Verzoegerung aufzufallen, lang genug, damit zwei
 * schnell hintereinander gesetzte Wegpunkte nur **eine** Berechnung ausloesen.
 * Das ist beim Offline-Routing kein Komfort, sondern noetig: Die lokale
 * Rechnung blockiert ihren Thread und laesst sich nicht abbrechen.
 */
private const val PLAN_DEBOUNCE_MS = 250L

/**
 * Der Fortschrittstext der Planung — oder `null`, wenn es nichts zu sagen gibt.
 *
 * Beim Serverweg bleibt es wie bisher still, solange die Route in einem Stueck
 * berechnet wird; die Wartezeit ist kurz und die Meldung waere Laerm. Wird
 * dagegen **auf dem Geraet** gerechnet, sagt die App das immer: Es dauert
 * spuerbar laenger, und ohne Rueckmeldung saehe es nach einer haengenden App
 * aus statt nach einer arbeitenden.
 */
private fun planProgressText(source: RoutingSource?, done: Int, total: Int): String? = when {
    source == RoutingSource.OFFLINE && total > 1 ->
        "Auf dem Gerät: Teilstrecke ${(done + 1).coerceAtMost(total)} von $total …"
    source == RoutingSource.OFFLINE -> "Berechne auf dem Gerät …"
    total > 1 -> "Teilstrecke $done von $total …"
    else -> null
}

/**
 * Trefferradius fuer das Tippen auf einen Wegpunkt.
 *
 * In **dp**, nicht in Pixeln: Als feste Pixelzahl (frueher 28) schrumpfte das
 * Ziel mit jeder Displaydichte — auf einem 3x-Geraet blieben davon rund 9 dp,
 * ein Drittel dessen, was Material fuer eine Beruehrungsflaeche verlangt. 24 dp
 * Radius sind die geforderten 48 dp im Durchmesser.
 */
private val WAYPOINT_TOUCH_RADIUS_DP = 24.dp

/** Maximale Hoehe des Rundkurs-Panels (Dart: `_planPanelMaxHeightFactor`). */
private const val PLAN_PANEL_MAX_HEIGHT_FACTOR = 0.55f

/**
 * Maximale Hoehe des **aufgeklappten** Planungsblatts.
 *
 * Knapper als [PLAN_PANEL_MAX_HEIGHT_FACTOR], weil ueber dem Blatt noch die
 * beiden runden Knoepfe (zusammen rund 120 dp) im selben Stapel stehen. Fuer
 * die Sicht auf die Karte ist ohnehin die eingeklappte Stufe zustaendig — sie
 * misst eine Zeile.
 */
private const val PLAN_SHEET_MAX_HEIGHT_FACTOR = 0.45f

/**
 * Maximale Hoehe des **aufgeklappten** Tourenblatts.
 *
 * Deutlich grosszuegiger als [PLAN_SHEET_MAX_HEIGHT_FACTOR]: Ueber dem
 * Tourenblatt stehen in seinem einzigen Sichtbarkeits-Zustand (siehe die
 * Rangfolge im Klassen-KDoc — es ist ohnehin HIDDEN, sobald noch etwas
 * anderes um den unteren Rand konkurriert) ausschliesslich die beiden runden
 * Knoepfe, keine zweite Kopfzeile und kein Fehlertext wie bei der Planung.
 * Vorbild ist ausdruecklich Google Maps/Komoot: Wer eine Liste aufschlaegt,
 * will moeglichst viele Touren auf einen Blick sehen, ohne staendig zu
 * scrollen — die Karte ist in diesem Moment ohnehin Nebensache. 0,8 laesst
 * trotzdem einen schmalen Kartenstreifen samt der beiden Knoepfe sichtbar,
 * damit der Wechsel zurueck auf PEEK nie ein Blindflug ist — ganz
 * verschwinden darf die Karte nicht.
 */
private const val TOUR_SHEET_MAX_HEIGHT_FACTOR = 0.8f

/** Beschriftung der Navigation entlang der geplanten Route. */
private const val PLANNED_ROUTE_LABEL = "Geplante Route"

/** Wegpunktname der eigenen Position, gesetzt von [runRouteToPlace]. */
private const val MY_LOCATION_WAYPOINT_NAME = "Mein Standort"

/**
 * Zieldistanz der Rundkurs-Suche aus der Ortskarte ([runRoundTripFromPlace]).
 *
 * Dieselbe Zahl wie der erste, haeufigste Chip in [RoundTripEntry]
 * (`PlanningPanel.kt`) — die Kachel „Runde ab hier" auf der Ortskarte fragt
 * (anders als das Planungsblatt) nicht erst nach einer eigenen Distanz, muss
 * also selbst eine sinnvolle Vorgabe treffen.
 */
private const val PLACE_ROUND_TRIP_DEFAULT_KM = 30.0

/**
 * Ein Stand der Planung, wie ihn „Rückgängig" wieder herstellt.
 *
 * Bewusst nur die vier Werte, die zusammen die Arbeit ausmachen — der
 * Suchtreffer-Marker und der Ablesepunkt des Hoehenprofils sind Beiwerk und
 * kommen nicht zurueck.
 */
private data class PlanningSnapshot(
    val waypoints: List<Waypoint>,
    val route: PlannedRoute?,
    val plannedFor: String?,
    val fromGenerator: Boolean,
) {
    /** Ob es gar nichts zu retten gab — dann bleibt die Meldung aus. */
    val isEmpty: Boolean get() = waypoints.isEmpty() && route == null
}

/**
 * Kennzeichnet, wofuer eine Route berechnet wurde: Profil und Wegpunkte.
 *
 * Fuenf Nachkommastellen sind rund ein Meter — genauer setzt niemand einen
 * Wegpunkt, und die Zeichenkette bleibt kurz genug fuer das Bundle.
 */
private fun planningInputsKey(waypoints: List<Waypoint>, profile: RouteProfile): String =
    waypoints.joinToString(separator = ";", prefix = "${profile.name}|") { waypoint ->
        String.format(Locale.ROOT, "%.5f,%.5f", waypoint.lat, waypoint.lon)
    }

private const val MIN_SEARCH_LENGTH = 3
private const val SEARCH_DEBOUNCE_MS = 450L

/** Zoomstufe des einmaligen automatischen Erst-Zooms auf die Position. */
private const val AUTO_LOCATION_ZOOM = 13.0

/**
 * Toleranz (Grad), innerhalb derer die Kamera noch als „am Deutschland-
 * Default" gilt — 0.0 waere zu knapp: MapLibre rundet die Kamera beim
 * Wiederherstellen aus `rememberSaveable` nicht immer bitgenau.
 */
private const val DEFAULT_CAMERA_POSITION_EPSILON = 0.01

/** Toleranz der Zoomstufe fuer denselben Vergleich. */
private const val DEFAULT_CAMERA_ZOOM_EPSILON = 0.05
