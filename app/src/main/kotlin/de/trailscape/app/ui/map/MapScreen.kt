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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.data.AppServices
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.record.abbiegehinweiseAktiviert
import de.trailscape.app.record.batterieAusnahmeIntent
import de.trailscape.app.record.navCourseUpAktiviert
import de.trailscape.app.record.setzeNavCourseUpAktiviert
import de.trailscape.app.record.setzeSprachansagenAktiviert
import de.trailscape.app.record.sprachansagenAktiviert
import de.trailscape.app.record.batterieHinweisGezeigt
import de.trailscape.app.record.merkeBatterieHinweisGezeigt
import de.trailscape.app.record.vonBatterieoptimierungAusgenommen
import de.trailscape.app.routing.missingSegmentsFor
import de.trailscape.app.voice.VoiceAnnouncer
import de.trailscape.app.voice.vibriereOffRoute
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
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.OverlayGap
import de.trailscape.app.ui.theme.OverlayScreenPadding
import de.trailscape.core.AscentPreference
import de.trailscape.core.ExplorerSquare
import de.trailscape.core.GeoResult
import de.trailscape.core.NavState
import de.trailscape.core.PlannedRoute
import de.trailscape.core.Ride
import de.trailscape.core.RouteNavigator
import de.trailscape.core.RouteProfile
import de.trailscape.core.TurnAnnouncer
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.RoutingSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.NAV_ZOOM_NAH
import de.trailscape.core.buildGpx
import de.trailscape.core.computeStats
import de.trailscape.core.daempfeKurs
import de.trailscape.core.extractTurnHints
import de.trailscape.core.glaetteZoom
import de.trailscape.core.haversineM
import de.trailscape.core.kursZwischen
import de.trailscape.core.largestExplorerSquare
import de.trailscape.core.naechsteKurve
import de.trailscape.core.zoomFuerTempo
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
 * mit seinen zwei Seiten (siehe [RideModeSeite]): der grossen Datenseite
 * (`RideModeScreen.kt`, dieselben Werte, aber gross genug fuer den Blick aus
 * einem Meter) und der Kartenseite NAVI_KARTE (die Karte selbst mit
 * Kompaktleiste aus `RideCompactBar.kt` und — falls navigiert wird — dem
 * HUD). Der Fahrmodus haelt hier nur diesen Seiten-Zustand (`rideModeSeite`)
 * — Zustand, Kommandos und Navigationswerte bleiben die dieses Screens. Er
 * ist fuer eine Aufzeichnung der Normalfall: Startet sie durch eine
 * Nutzeraktion in dieser Sitzung, oeffnet er direkt (siehe `runRecording()`),
 * nicht erst ueber einen eigenen Knopf in der Live-Leiste.
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
 * verlaesst, welche Stufe das untere Blatt zeigt — all das entschied bis vor
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
 *    auswaehlen laesst (das Erkunden-Gesicht des Blatts weicht dort ja
 *    bereits).
 *  * **[MapMode.NAVIGIEREN] → [MapMode.ERKUNDEN]**: [stopNavigation] sowie der
 *    Effekt, der die Navigation beendet, wenn die navigierte Tour geloescht
 *    wird.
 *
 * Die Ortssuche ist bewusst kein vierter Wert: Sie bleibt in allen drei Modi
 * erreichbar (siehe „Suche jederzeit" unten) und ist damit orthogonal zum
 * Kartenmodus, nicht ein weiterer Zustand desselben Schalters. Sie hat
 * allerdings zwei Gesichter — `exploreSearching` fuer die Suche im
 * Erkunden-Blatt, `searchOpen` fuer das modale Blatt der Wegpunktsuche.
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
 *  * **Vibration und Sprachansage bei „abseits der Route"** — zusaetzlich zur
 *    Meldung und zur Navigationsleiste, denn beide sind im Fahrmodus (eigenes
 *    Dialog-Fenster, siehe `RideModeScreen.kt`) unsichtbar. Dazu kommen
 *    Abbiegehinweise aus der Routengeometrie (`TurnHints.kt` in `:core`) und
 *    die Zielansage; gesprochen wird ueber `voice/VoiceAnnouncer.kt`
 *    (Hauptschalter „Sprachansagen" unter Mehr → Aufzeichnung, Default AUS),
 *    vibriert ueber `voice/Vibration.kt` (eigener Schalter, Default AN).
 *  * **Suche jederzeit**, nicht nur im Planungsmodus, und **im Blatt selbst**
 *    statt als Panel im oberen Stapel: Die Suchzeile der eingeklappten Stufe
 *    ist das echte Feld, und die Treffer erscheinen direkt darunter (siehe
 *    `ExploreSheet.kt`). Vorher war die Zeile eine Attrappe, die ein zweites,
 *    modales Blatt oeffnete — das gibt es nur noch fuer die Wegpunktsuche der
 *    Planung (`SearchSheet.kt`). Ein gewaehlter Treffer ist ein Ort-Objekt
 *    ([Place]) mit eigener Karte (`PlaceCard.kt`), keine Sofortaktion mehr an
 *    der Trefferzeile.
 *  * **Hoehenprofil** fuer die ausgewaehlte Tour und die geplante Route — das
 *    hatte der Karten-Screen in Flutter noch nicht.
 *  * **Rundkurs aus der Trainingsempfehlung.** Der Trainings-Tab schickt ueber
 *    [AppViewModel.pendingRouteTarget] ein Ziel her; dieser Screen oeffnet
 *    dafuer das Blatt aus `RouteGenerationSheet.kt`, laesst im
 *    [RouteGenerationController] suchen und legt den uebernommenen Vorschlag in
 *    **denselben** `plannedRoute`-Zustand, den die Planung von Hand fuellt —
 *    Hoehenprofil, Speichern, Teilen und Navigation funktionieren damit ohne
 *    einen zweiten Weg. Das Flutter-Original kannte weder Generator noch
 *    Uebergabe zwischen den Tabs.
 *  * **Aufzeichnung von ausserhalb.** Der schwebende REC-Knopf der Huelle
 *    (`ui/components/RecCapsuleButton.kt` samt Bereit-Dialog) und der
 *    Erststart-Leerzustand des Heute-Tabs schicken ueber
 *    [AppViewModel.pendingRecordStart] dieselbe Bitte her, die dieser Screen
 *    sonst nur von seinem frueheren Knopf kannte — abgeholt und ausgeloest
 *    wird sie ueber
 *    **dieselbe** lokale Funktion `startRecording()`, also mit derselben
 *    Berechtigungsabfrage. Vorher versprach die Karte auf der Startseite nur
 *    den Weg dorthin und erklaerte in einem Absatz, wo der eigentliche Knopf
 *    liegt; jetzt haelt der eine Knopf das eine Versprechen. Denselben Weg
 *    nimmt seit der Fuehrung „Eine Leiste" der schwebende Aufnahme-Knopf der
 *    Huelle (`ui/components/RecCapsuleButton.kt`) samt seinem Bereit-Dialog
 *    (`ui/ReadyToRideDialog.kt`) — fuer die Aufzeichnung ueber
 *    [AppViewModel.requestRecording], fuer „Mit Navigation starten" ueber
 *    [AppViewModel.navigatePlannedRequest]. Die Berechtigungslogik dieses
 *    Screens bleibt damit die einzige der App; was die Huelle von der Planung
 *    ueberhaupt weiss, ist die eine Kilometerzahl aus
 *    [AppViewModel.plannedRouteKm], die der Effekt weiter unten meldet.
 *  * **Rundkurs auch ohne Trainingsziel.** Im Planungsblatt steht bei null
 *    Wegpunkten „Runde ab hier" mit drei Distanzen und einem Feld fuer die
 *    eigene Zahl (siehe [startRoundTrip] und `PlanningPanel.kt`). Vorher war
 *    der Generator ausschliesslich ueber den Heute- oder Trainings-Tab
 *    erreichbar — an einem Ruhetag also gar nicht.
 *  * **Die Planung ist die oberste Stufe desselben unteren Blatts**
 *    (`PlanningSheet` in `PlanningPanel.kt`, Stufe [MapSheetStage.PLANEN]):
 *    aufgezogen der volle Inhalt, eingeklappt nur ihre Statuszeile. Vorher
 *    stapelten sich alle Panels oben und liessen auf einem 360×800-dp-Geraet
 *    einen Kartenstreifen von rund 80 dp uebrig — ausgerechnet dort, wo
 *    Wegpunkte hingetippt werden.
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
 * ## Das eine Kartenblatt und seine drei Stufen
 * Ueber der schwebenden Navigationskapsel liegt **ein** aufziehbares Blatt mit
 * drei Stufen ([MapSheetStage], `MapMode.kt`) — genau das Verhalten des vom
 * Nutzer freigegebenen Karte-Screens in
 * `docs/design/prototyp-eine-leiste.html`:
 *
 *  1. [MapSheetStage.EINGEKLAPPT] (Ruhezustand): Griff und eine Zeile. Im
 *     Erkunden-Gesicht ist das die Suchzeile ([ExploreSheet]), waehrend der
 *     Planung die Statuszeile der Planung (`PlanningSheet`). Die Karte bleibt
 *     fast vollstaendig sichtbar.
 *  2. [MapSheetStage.AUFGEZOGEN]: zusaetzlich die Aktionszeile „Route planen ·
 *     Kartenstil · Offline" — die drei Einstiege dieses Tabs.
 *  3. [MapSheetStage.PLANEN]: an Stelle der Aktionszeile der Planungsinhalt
 *     (`PlanningSheet` in `PlanningPanel.kt`).
 *
 * Gewechselt wird per Tipp auf den Griff und per vertikalem Ziehen (beides
 * bringt [SwipeableSheet] mit), aus der Planung heraus zusaetzlich ueber den
 * Zurueck-Pfeil in deren Kopfzeile. Jeder **gewollte** Stufenwechsel laeuft
 * durch `goToSheetStage` im Rumpf (die Rangfolge weiter unten klappt das Blatt
 * zusaetzlich von sich aus ein); gelesen wird die Stufe als Ableitung aus
 * `mode` und `exploreExpanded`, damit es keine zweite Wahrheit ueber „wird
 * gerade geplant?" gibt (Begruendung im KDoc von [MapSheetStage]).
 *
 * ## Was aus dem Blatt verschwunden ist — und warum
 * Bis hierher trug dasselbe Blatt zusaetzlich die **Tourenliste** (als
 * aufziehbaren Koerper) und den **Import-Einstieg**. Beides ist ersatzlos
 * entfallen: Touren haben seit der Fuehrung „Eine Leiste" einen eigenen Tab
 * (`ui/rides/RidesScreen.kt`), und in ihm wohnt auch der GPX-Import. Was hier
 * stand, war damit eine Dublette — dieselbe Liste, derselbe Einstieg, nur
 * schlechter erreichbar und in Konkurrenz zu genau dem Platz, den die Karte
 * braucht. Mit ihr sind auch die Tourendetailansicht (`RideDetailHost` im
 * eigenen `Dialog`) und die Import-Aktion aus diesem Screen verschwunden: Sie
 * hingen beide ausschliesslich an der Liste und haetten von hier aus keinen
 * Aufrufer mehr.
 *
 * Die Karte bleibt trotzdem die **raeumliche** Sicht auf den Bestand, nur auf
 * Zuruf statt als stehende Liste: Der Touren-Tab schickt ueber
 * [AppViewModel.showRideOnMapRequest] eine Tour-Kennung herueber („zeig mir das
 * auf der Karte"); der Effekt weiter unten waehlt sie aus — womit die
 * bestehenden Effekte ihre Spur zeichnen und auf sie zoomen — und laesst das
 * Blatt dabei eingeklappt, weil die Tourkarte (`RideCard`) ohnehin den Platz
 * uebernimmt.
 *
 * ## Rangfolge am unteren Kartenrand
 * Um denselben Platz bewerben sich mehrere Zustaende — Aufzeichnung,
 * Navigation, Planung, ausgewaehlte Tour (`RideCard`), gewaehlter Ort
 * (`PlaceCard`), offene Rundenwahl und das Erkunden-Gesicht des Blatts:
 *
 *  1. **Aufzeichnung, Navigation, ausgewaehlte Tour, gewaehlter Ort, offene
 *     Suche oder Rundenwahl haben Vorrang.** Sie laufen entweder waehrend der
 *     Fahrt (die Live-Leiste und die Navigationsleiste duerfen nicht hinter
 *     einem Blatt verschwinden) oder sind eine bewusste Handlung der Nutzerin
 *     — das Erkunden-Gesicht ist in all diesen Faellen ueberhaupt nicht
 *     komponiert (siehe die Sichtbarkeitsbedingung um den
 *     [ExploreSheet]-Aufruf weiter unten), es braucht keinen eigenen
 *     Versteck-Zustand.
 *  2. **Sonst ist [MapSheetStage.EINGEKLAPPT] der Ruhezustand.** Hoeher kommt
 *     das Blatt nur, wenn die Nutzerin selbst zieht oder tippt.
 *  3. **Ein schon aufgezogenes Blatt faellt beim Eintreten eines
 *     Vorrang-Zustands auf die eingeklappte Stufe zurueck und bleibt dort**,
 *     auch nachdem der Vorrang-Zustand wieder endet — es springt nicht von
 *     selbst wieder auf. Wer waehrend der Aufzeichnung zufaellig am Griff
 *     zieht, soll nach dem Stopp nicht ueberrascht ein offenes Blatt
 *     vorfinden, das sie selbst nie geoeffnet hat.
 *
 * Die Systemzurueckgeste geht dieselben Stufen abwaerts: erst aus der Suche,
 * dann aus der Rundenwahl, dann eine Blatt-Stufe tiefer, dann aus der Planung
 * (ueber [exitPlanningWithUndo], mit derselben Rueckhol-Snackbar wie der
 * Zurueck-Pfeil der Planung). Ist nichts davon der Fall, bleibt der
 * `BackHandler` deaktiviert und die Geste faellt auf das normale Verhalten der
 * App zurueck.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    // Entdeckt-Kacheln: der Schalter aus dem Kartenstil-Blatt und der
    // Kachelbestand des ganzen Tourarchivs (siehe AppViewModel). Beides liegt
    // dort und nicht hier, weil der Bestand einen Lauf ueber alle Touren
    // kostet und einen Tab-Wechsel ueberleben muss.
    val explorerTilesEnabled by appViewModel.explorerTilesEnabled.collectAsStateWithLifecycle()
    val explorerTiles by appViewModel.explorerTiles.collectAsStateWithLifecycle()

    // Der Suchverlauf in der Form, die beide Suchen brauchen — das Blatt und
    // das modale Blatt der Planung. Frueher stand dieselbe Umformung an der
    // einen Aufrufstelle; mit zweien gehoert sie hierher.
    val placeHistory by remember(placeSearchHistory) {
        derivedStateOf { placeSearchHistory.map { Place(it.displayName, it.lat, it.lon) } }
    }

    val isRecording by RecordingRepository.isRecording.collectAsStateWithLifecycle()
    val isPaused by RecordingRepository.isPaused.collectAsStateWithLifecycle()
    val isAutoPaused by RecordingRepository.isAutoPaused.collectAsStateWithLifecycle()
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

    /**
     * Die Streckenart der Planung: `false` = „Einfach" (A nach B, Vorgabe),
     * `true` = „Rundweg" — dann kehrt die Route vom letzten Wegpunkt zum
     * **ersten** zurueck (siehe den Segmentschalter in `PlanningPanel.kt` und
     * `routingWaypoints` im Planungs-Effekt weiter unten).
     *
     * Der Wert geht in [planningInputsKey] ein: Ohne ihn saehe der Effekt beim
     * blossen Umschalten unveraenderte Wegpunkte und liesse die alte Route
     * stehen.
     *
     * Bewusst kein eigener Saver in `PlanningStateSavers.kt`: Ein `Boolean`
     * geht ohne Umweg ins Bundle. Und bewusst nicht in [exitPlanning]
     * zurueckgesetzt — die Streckenart ist wie das Routenprofil eine
     * Voreinstellung der Nutzerin, keine Arbeit an einer einzelnen Route.
     *
     * Fuer eine Runde aus dem Generator (`routeFromGenerator`) gilt er nicht:
     * Die kommt als fertige Schleife ohne Wegpunkte an (siehe
     * `RouteGenerationSheet.kt`), es gaebe dort nichts zu schliessen.
     */
    var roundTrip by rememberSaveable { mutableStateOf(false) }
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

    // `searchOpen` steht nur noch fuer das **modale** Suchblatt, das heute
    // ausschliesslich die Wegpunktsuche der Planung bedient (siehe
    // `openPlaceSearch`). Die Ortssuche des Erkunden-Blatts laeuft dort an Ort
    // und Stelle und haengt an `exploreSearching`.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeoResult>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    /**
     * Ob das Suchfeld der eingeklappten Blatt-Stufe gerade den Fokus hat — und
     * damit, ob unter ihm die Trefferliste steht (dann bleibt die Aktionszeile
     * weg, siehe `ExploreSheet.kt`).
     *
     * Absichtlich **kein** `rememberSaveable`: Nach einer Drehung oder einem
     * Prozesstod haette das Feld den Fokus nicht mehr, ein wiederhergestelltes
     * `true` wuerde also einen Zustand behaupten, den die Tastatur nicht
     * teilt.
     */
    var exploreSearching by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

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

    // Kurvenpunkte der navigierten Route — einmal je Ziel aus der Geometrie
    // extrahiert (`extractTurnHints`, `:core`). Dieselbe Liste fuettert den
    // `TurnAnnouncer` im Navigations-Effekt (Ansagen) und die kontinuierliche
    // Naechste-Kurve-Anzeige in HUD (`NavigationHud.kt`) und Fahrmodus.
    val turnHints = remember(navTarget) {
        navTarget?.points?.let(::extractTurnHints) ?: emptyList()
    }

    // Die naechste Kurve fuer die Anzeige: naechster noch bevorstehender
    // Hinweis plus Distanz bis dahin entlang der Route (`naechsteKurve`,
    // `:core`). Jenseits der Sichtweite zeigen HUD und Fahrmodus den
    // Geradeaus-Pfeil (siehe `NAECHSTE_KURVE_SICHT_M` in `NavigationHud.kt`).
    val naechsteKurveInfo = navState?.let { state ->
        naechsteKurve(turnHints, state.doneKm * 1000)
            ?.takeIf { it.second <= NAECHSTE_KURVE_SICHT_M }
    }

    // Gleitend gemitteltes Tempo fuer die Restzeit-Schaetzung im HUD —
    // geschrieben vom Navigations-Effekt je Positionsupdate (siehe
    // `glaetteTempo` in `NavigationHud.kt`), `null` solange keins bekannt ist.
    var navTempoKmh by remember { mutableStateOf<Double?>(null) }

    // Zustand der Navi-Kamera (Kernrechnung in `:core`, `NavCamera.kt`) —
    // geschrieben vom Navigations-Effekt je Positionsupdate: der gedaempfte
    // Fahrkurs in Grad (im Stand eingefroren, damit die Karte an der Ampel
    // nicht kreiselt), der geglaettete Tempo-Zoom und die letzte
    // Navi-Position, damit „Re-zentrieren" und der Kompass-Umschalter sofort
    // fahren koennen statt auf den naechsten GPS-Punkt zu warten.
    var navKurs by remember { mutableStateOf<Double?>(null) }
    var navZoom by remember { mutableStateOf<Double?>(null) }
    var navPosition by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    // Kompass-Verhalten der Navi-Kamera: Fahrtrichtung oben (Default) oder
    // Nord oben. Die Wahrheit liegt in den Prefs
    // (`record/RecordingSettings.kt`, Schluessel `trailscape.nav.courseUp`);
    // hier die Bildschirm-Kopie fuer den Kompass-Knopf — dasselbe Muster wie
    // [sprachansagenAn] direkt darunter. Ausserhalb der Navigation bleibt die
    // Karte unabhaengig davon bei Nord oben.
    var navCourseUp by remember { mutableStateOf(navCourseUpAktiviert(context)) }

    // Der Hauptschalter „Sprachansagen" als Bildschirmzustand fuer den
    // Lautsprecher-Knopf im HUD. Die Wahrheit liegt in den Prefs
    // (`record/RecordingSettings.kt`, dieselben, die Mehr → Aufzeichnung
    // schreibt); hier steht nur die Kopie, die Compose zum Neuzeichnen des
    // Knopfes braucht. Der Navigations-Effekt liest sie bei jedem Start der
    // Navigation frisch ein, falls der Schalter zwischenzeitlich unter Mehr
    // umgelegt wurde.
    var sprachansagenAn by remember { mutableStateOf(sprachansagenAktiviert(context)) }

    var liveAscentM by remember { mutableStateOf(0.0) }
    var hoverPoint by remember { mutableStateOf<TrackPoint?>(null) }

    // Welche Seite des Fahrmodus offen ist (siehe [RideModeSeite]): KEINE
    // (normale Karte mit Live-Leiste), DATEN (der grosse Dialog aus
    // `RideModeScreen.kt`) oder NAVI_KARTE (die Karte selbst mit
    // Kompaktleiste, `RideCompactBar.kt`, und — falls navigiert wird — dem
    // HUD). Ersetzt das fruehere `rideMode: Boolean`; nur bei laufender
    // Aufzeichnung relevant. Bewusst `rememberSaveable`: Eine Drehung am
    // Lenker darf nicht dazu fuehren, dass die Fahrerin ploetzlich wieder die
    // kleine Live-Leiste vor sich hat.
    var rideModeSeite by rememberSaveable { mutableStateOf(RideModeSeite.KEINE) }

    // Ob die Kartenseite des Fahrmodus gerade wirklich zu sehen ist — die
    // eine Bedingung, an der Kompaktleiste, KeepScreenOn und die
    // Zurueck-Geste dieser Seite haengen.
    val naviKarteAktiv = rideModeSeite == RideModeSeite.NAVI_KARTE && isRecording

    // Ob der einmalige Batterieoptimierungs-Hinweis gerade offen ist (siehe
    // `BatteryNoticeDialog.kt`). Gesetzt in [runRecording], wenn die Ausnahme
    // fehlt und der Hinweis noch nie lief (Prefs-Merker) — die Aufzeichnung
    // startet unabhaengig davon.
    var showBatteryNotice by remember { mutableStateOf(false) }

    // Ob das Erkunden-Gesicht des Blatts seine Aktionszeile freigibt — also
    // [MapSheetStage.AUFGEZOGEN] statt [MapSheetStage.EINGEKLAPPT] (siehe
    // `ExploreSheet.kt`). `rememberSaveable`, damit weder ein Tabwechsel noch
    // eine Drehung ein von der Nutzerin aufgezogenes Blatt wieder einklappt.
    // `false` ist der Startwert: Beim allerersten Aufbau dieses Screens gilt
    // noch kein Vorrang-Zustand, und die eingeklappte Suchzeile ist die
    // richtige Ruhelage (siehe Klassen-KDoc, „Rangfolge am unteren
    // Kartenrand").
    //
    // Zusammen mit `mode` ist das die einzige Quelle der Blatt-Stufe; die
    // Stufe selbst wird daraus abgeleitet (`sheetStage` weiter unten) und
    // nicht ein zweites Mal gespeichert.
    var exploreExpanded by rememberSaveable { mutableStateOf(false) }

    // Tourenliste, GPX-Import und Tourendetail sind aus diesem Screen
    // verschwunden (siehe Klassen-KDoc, „Was aus dem Blatt verschwunden ist");
    // damit entfallen hier auch die Zustaende, die nur sie brauchten —
    // `rememberActivityImportAction` und die geoeffnete Detail-Tour. Beides
    // lebt jetzt ausschliesslich im Touren-Tab (`ui/rides/`).

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
    LaunchedEffect(controller, selectedRide?.id, selectedRide?.points?.size, navTarget) {
        // Die navigierte Tour bleibt auch ohne Auswahl auf der Karte: Der
        // Start der Navigation hebt die Tourauswahl auf (die Tour-Karte
        // weicht dem HUD, siehe [runNavigateRide]), aber die Linie ist
        // waehrend der Fahrt gerade die Hauptinformation. Die geplante Route
        // (rideId == null) zeichnet dagegen weiterhin ausschliesslich die
        // eigene blaue Ebene (`setPlannedRoute`) — sonst laege dieselbe
        // Strecke doppelt uebereinander.
        val navPoints = navTarget?.takeIf { it.rideId != null }?.points
        controller.setTrack(selectedRide?.points ?: navPoints ?: emptyList())
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

    /**
     * Das groesste zusammenhaengende Quadrat des aktuellen Kachelbestands —
     * gemerkt aus derselben Rechnung, die auch das GeoJSON dafuer baut. Die
     * Zaehler-Pille zeigt es an; sie duerfte es keinesfalls selbst noch einmal
     * ermitteln, denn die Suche laeuft ueber den gesamten Bestand und stuende
     * damit bei jeder Rekomposition im Main-Thread.
     */
    var explorerMaxSquare by remember { mutableStateOf<ExplorerSquare?>(null) }

    // Die drei Kachel-Ebenen fuellen. Aus tausenden Kacheln entstehen hier
    // ebenso viele GeoJSON-Rechtecke — das ist Rechenarbeit und gehoert
    // deshalb auf Dispatchers.Default; gesetzt wird erst das fertige Ergebnis.
    // Ist der Layer aus, gehen drei leere Merkmalsammlungen hinaus (siehe
    // [MapController.setExplorerTiles]) und die Ebenen zeichnen nichts.
    LaunchedEffect(explorerTilesEnabled, explorerTiles, controller.isReady) {
        if (!controller.isReady) return@LaunchedEffect
        if (!explorerTilesEnabled) {
            explorerMaxSquare = null
            controller.setExplorerTiles(null, null, null)
            return@LaunchedEffect
        }
        val tiles = explorerTiles
        val geoJson = withContext(Dispatchers.Default) {
            val square = largestExplorerSquare(tiles)
            ExplorerTileGeoJson(
                fog = fogFeatureCollection(tiles),
                outline = exploredOutlineFeatureCollection(tiles),
                maxSquare = maxSquareFeatureCollection(square),
                square = square,
            )
        }
        explorerMaxSquare = geoJson.square
        controller.setExplorerTiles(geoJson.fog, geoJson.outline, geoJson.maxSquare)
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
        // Waehrend einer Navigation fuehrt die Navi-Kamera im
        // Navigations-Effekt (course-up, Tempo-Zoom, unteres Drittel) — ein
        // zweiter Kamerazug je Punkt wuerde nur gegen sie ankaempfen.
        if (navTarget != null) return@LaunchedEffect
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
    // nichts mehr anzuzeigen, und beide Seiten schliessen sich mit ihr.
    LaunchedEffect(isRecording) {
        if (!isRecording) rideModeSeite = RideModeSeite.KEINE
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
    LaunchedEffect(waypoints, routeProfile, roundTrip, routeFromGenerator) {
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
        // „Rundweg": Die Liste, die zum Routing geht, bekommt den ersten
        // Wegpunkt noch einmal ans Ende — damit schliesst sich die Schleife,
        // und Distanz und Hoehenmeter (und darueber das Kilometer-Etikett des
        // Aufnahme-Knopfs, siehe `reportPlannedRoute` weiter unten) rechnen die
        // Rueckfahrt von selbst mit. Die **angezeigte** Wegpunktliste im
        // Planungsblatt bleibt ohne dieses Duplikat: Es ist kein Wegpunkt, den
        // die Nutzerin gesetzt hat, sondern die Folge der gewaehlten
        // Streckenart.
        val routingWaypoints = if (roundTrip && waypoints.size >= 2) {
            waypoints + waypoints.first()
        } else {
            waypoints
        }
        val inputs = planningInputsKey(waypoints, routeProfile, roundTrip)
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
                waypoints = routingWaypoints,
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
                    missingSegmentsFor(context, routingWaypoints, routeProfile),
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

        // Der Lautsprecher-Knopf im HUD und Mehr → Aufzeichnung schreiben in
        // dieselben Prefs; beim (Neu-)Start der Navigation wird die
        // Bildschirm-Kopie einmal frisch gelesen.
        sprachansagenAn = sprachansagenAktiviert(context)
        navTempoKmh = null

        // Abbiegehinweise aus der Routengeometrie (`:core`, dort getestet) —
        // dieselben `turnHints`, die auch die Anzeige speisen. Je Effekt-Lauf
        // eine frische Announcer-Instanz — das IST der geforderte Reset bei
        // Routenwechsel; bereits ueberfahrene Hinweise verfallen im Announcer
        // selbst still.
        val turnAnnouncer = TurnAnnouncer(turnHints)

        // Laeuft eine Aufzeichnung, kommen die Positionen von dort — ein
        // zweiter GPS-Abonnent braeuchte nur Strom fuer dieselben Punkte.
        val positions: Flow<Pair<Double, Double>> = if (isRecording) {
            RecordingRepository.lastPoint.filterNotNull().map { it.lat to it.lon }
        } else {
            locationUpdates(context).map { it.latitude to it.longitude }
        }

        var wasOffRoute = false
        var zielGemeldet = false
        var vorherLat: Double? = null
        var vorherLon: Double? = null
        var vorherZeitMs = 0L
        positions.collect { (lat, lon) ->
            val state = navigator.update(lat, lon)
            navState = state
            navPosition = lat to lon

            // Tempo: bevorzugt aus der laufenden Aufzeichnung, ohne sie aus
            // dem Weg zwischen den letzten beiden Fixes — Zoom und
            // Kurs-Einfrieren der Navi-Kamera brauchen in beiden Faellen
            // eine Zahl. Fuer die Restzeit im HUD wie bisher gleitend
            // gemittelt (`glaetteTempo`).
            val jetztMs = System.currentTimeMillis()
            val vLat = vorherLat
            val vLon = vorherLon
            val schrittM = if (vLat != null && vLon != null) {
                haversineM(TrackPoint(lat = vLat, lon = vLon), TrackPoint(lat = lat, lon = lon))
            } else {
                null
            }
            val schrittS = (jetztMs - vorherZeitMs) / 1000.0
            val tempoRohKmh = RecordingRepository.speedKmh.value
                ?: schrittM?.takeIf { vorherZeitMs > 0L && schrittS > 0.0 }
                    ?.let { it / schrittS * 3.6 }
            navTempoKmh = glaetteTempo(navTempoKmh, tempoRohKmh)

            // Der Fahrkurs kommt aus den letzten beiden Fixes
            // (Aufzeichnungspunkte tragen keinen GPS-Kurs); unterhalb weniger
            // Meter Schritt ist die Richtung reines Rauschen und bleibt
            // draussen. Daempfung samt Wraparound und Einfrieren im Stand in
            // `:core` (`daempfeKurs`), ebenso der Tempo-Zoom.
            val kursRoh = if (vLat != null && vLon != null &&
                schrittM != null && schrittM >= NAV_KURS_MIN_SCHRITT_M
            ) {
                kursZwischen(vLat, vLon, lat, lon)
            } else {
                null
            }
            navKurs = daempfeKurs(navKurs, kursRoh, tempoRohKmh)
            navZoom = glaetteZoom(navZoom, zoomFuerTempo(navTempoKmh))
            vorherLat = lat
            vorherLon = lon
            vorherZeitMs = jetztMs

            // `followMe` (und die uebrigen Kamera-Zustaende) werden hier bei
            // jedem Punkt frisch gelesen (kein Effekt-Schluessel): Der
            // Navigator soll beim Umschalten weiterlaufen, nur die Kamera
            // haelt sich zurueck.
            if (followMe) {
                val anker = target.points.getOrNull(state.nearestIndex)
                if (state.offRoute && anker != null) {
                    // Abseits: so weit heraus, dass Position UND der naechste
                    // Routenpunkt gemeinsam im Bild stehen (Zoomgrenzen und
                    // Klemmung in `:core`); wieder auf der Route uebernimmt
                    // die normale Navi-Kamera.
                    controller.frameOffRoute(lat, lon, anker.lat, anker.lon)
                } else {
                    controller.moveToNavCamera(
                        lat = lat,
                        lon = lon,
                        zoom = navZoom ?: NAV_ZOOM_NAH,
                        bearingGrad = if (navCourseUp) navKurs ?: 0.0 else 0.0,
                        versatz = navCourseUp,
                    )
                }
            }
            if (state.offRoute && !wasOffRoute) {
                appViewModel.showMessage("Achtung: Du bist abseits der Route.")
                // Die Snackbar ist im Fahrmodus (eigenes Dialog-Fenster)
                // unsichtbar und mit dem Telefon in der Tasche sowieso —
                // deshalb je Off-Route-Episode zusaetzlich einmal Vibration
                // (README-Zusage, eigener Schalter) und Sprachansage.
                vibriereOffRoute(context)
                VoiceAnnouncer.sagAn(context, "Du bist abseits der Route.")
            }
            if (!state.offRoute && wasOffRoute) {
                VoiceAnnouncer.sagAn(context, "Zurück auf der Route.")
            }
            wasOffRoute = state.offRoute

            if (!state.offRoute) {
                // Abbiegehinweise nur auf der Route: Abseits stimmt der
                // projizierte Fortschritt nicht, und eine Ansage „In 100
                // Metern links" auf fremdem Weg waere eine Falschauskunft.
                // Das Tempo kommt aus der laufenden Aufzeichnung; ohne sie
                // nimmt der Announcer sein Standardtempo an.
                if (abbiegehinweiseAktiviert(context)) {
                    turnAnnouncer.melde(state.doneKm * 1000, RecordingRepository.speedKmh.value)
                        ?.let { ansage -> VoiceAnnouncer.sagAn(context, ansage) }
                }
                if (!zielGemeldet && state.remainingKm <= ZIEL_ERREICHT_KM && state.doneKm > ZIEL_ERREICHT_KM) {
                    // Einmal je Effekt-Lauf; die Mindest-Fahrstrecke davor
                    // verhindert die Zielansage direkt am Start einer Runde,
                    // deren Ziel neben dem Start liegt.
                    zielGemeldet = true
                    VoiceAnnouncer.sagAn(context, "Ziel erreicht.")
                }
            }
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
            navKurs = null
            navZoom = null
            navPosition = null
            // Auch hier die Navi-Kamera zuruecknehmen (Kurs Nord, Versatz
            // weg) — dieselbe Aufraeumarbeit wie in [stopNavigation].
            controller.resetNavCamera()
            mode = MapMode.ERKUNDEN
        }
    }

    // Navigation nach Tabwechsel/Drehung wieder aufnehmen: Gerettet wurden nur
    // Kennung und Beschriftung (siehe oben), die Punkte kommen on-demand von
    // der Platte (die Liste haelt nur noch Zusammenfassungen) bzw. aus der
    // geretteten geplanten Route. Bis dahin zeigt die Leiste die gespeicherte
    // Gesamtstrecke; den Rest rechnet der `RouteNavigator` beim naechsten
    // GPS-Punkt neu.
    // Schluessel bewusst billig: `plannedRoute` traegt eine komplette
    // Punktliste, die Zahl der Punkte benennt den Wechsel genauso.
    LaunchedEffect(rides, plannedRoute?.points?.size, navLabel) {
        if (navTarget != null) return@LaunchedEffect
        val label = navLabel ?: return@LaunchedEffect
        val rideId = navRideId
        val points = if (rideId != null) {
            // Erst die billige Anwesenheitspruefung ueber die Liste, dann der
            // eine Dateizugriff — nicht umgekehrt.
            if (rides.any { it.id == rideId }) appViewModel.loadRide(rideId)?.points else null
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
        navKurs = null
        navZoom = null
        navPosition = null
        navTempoKmh = null
        // Die Navi-Kamera raeumt sich weg: Kurs zurueck auf Nord, der
        // Drittel-Versatz (Kamera-Padding) auf null — danach verhaelt sich
        // die Karte wieder exakt wie vor der Navigation. Eine laufende
        // Aufzeichnung laeuft unbeirrt weiter; nur die Fuehrung endet.
        controller.resetNavCamera()
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
     * Wechselt nach [MapMode.PLANEN] — die Aktion „Route planen" der
     * aufgezogenen Blatt-Stufe (`ExploreSheet.kt`). Der Weg dorthin fuehrt
     * ueber [goToSheetStage]; direkt aufgerufen wird das hier nur von Wegen,
     * die ohnehin schon in der Planung landen (Ortskarte, Rundkurs).
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

    // ------------------------------------------- Die drei Stufen des Blatts
    /**
     * Welche Stufe das eine untere Blatt gerade zeigt — **abgeleitet**, nicht
     * gespeichert (Begruendung im KDoc von [MapSheetStage]).
     *
     * Waehrend [MapMode.PLANEN] entscheidet `planSheetExpanded`, ob der
     * Planungsinhalt steht ([MapSheetStage.PLANEN]) oder nur dessen
     * Statuszeile ([MapSheetStage.EINGEKLAPPT]) — Letzteres stellt `onMapTap`
     * beim ersten Wegpunkt her, damit die Karte zum Tippen frei wird. Sonst
     * entscheidet `exploreExpanded` ueber die Aktionszeile.
     */
    val sheetStage = when {
        mode == MapMode.PLANEN && planSheetExpanded -> MapSheetStage.PLANEN
        mode == MapMode.PLANEN -> MapSheetStage.EINGEKLAPPT
        exploreExpanded -> MapSheetStage.AUFGEZOGEN
        else -> MapSheetStage.EINGEKLAPPT
    }

    /**
     * Faehrt das Blatt auf [target] — die eine Stelle, durch die jeder
     * **gewollte** Stufenwechsel laeuft: Griff, Aktionszeile, Zurueck-Pfeil
     * der Planung und Zurueck-Geste.
     *
     * Die Stufe hat keine eigene Variable; geschrieben wird jeweils die
     * Quelle, aus der [sheetStage] sie ableitet. Ein Wechsel nach
     * [MapSheetStage.AUFGEZOGEN] aus der Planung heraus beendet diese deshalb
     * wirklich — und zwar ueber [exitPlanningWithUndo], damit ein Fehlgriff
     * am Griff nicht eine halbe Stunde Arbeit kostet (dieselbe
     * Rueckhol-Snackbar wie der Zurueck-Pfeil der Planung).
     */
    fun goToSheetStage(target: MapSheetStage) {
        when (target) {
            MapSheetStage.EINGEKLAPPT -> {
                if (mode == MapMode.PLANEN) {
                    planSheetExpanded = false
                } else {
                    exploreExpanded = false
                }
            }

            MapSheetStage.AUFGEZOGEN -> {
                if (mode == MapMode.PLANEN) exitPlanningWithUndo("Planung beendet.")
                exploreExpanded = true
            }

            MapSheetStage.PLANEN -> {
                if (mode == MapMode.PLANEN) {
                    planSheetExpanded = true
                } else {
                    enterPlanning()
                }
            }
        }
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
     * ## Warum hier die Fahrmodus-Seite gesetzt wird
     * Diese Funktion laeuft ausschliesslich, wenn eine Nutzeraktion in dieser
     * Sitzung die Aufzeichnung tatsaechlich in Gang setzt — ueber den gruenen
     * Aufnahme-Knopf, die von der Startseite gereichte Bitte (siehe
     * [pendingRecordStart] weiter unten) oder den automatischen Mitstart
     * beim Navigieren (siehe [starteNavigationsAnsicht]), alle ueber
     * [startRecording] und damit [withPermissions]. Der Fahrmodus ist fuer
     * eine Aufzeichnung der Normalfall, nicht ein Angebot, das erst gefunden
     * werden muss — deshalb oeffnet er direkt an der Stelle, an der die
     * Aufzeichnung wirklich beginnt: Bei laufender Navigation die
     * NAVI_KARTE-Seite (Karte mit HUD und Kompaktleiste), sonst die
     * Datenseite. Bewusst **hier** und nicht in einem `LaunchedEffect` auf
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
        // Laeuft eine Navigation, oeffnet direkt die Kartenseite des
        // Fahrmodus — die Fuehrung (HUD, Navi-Kamera) ist dann wichtiger als
        // die grossen Zahlen. Ohne Navigation wie gehabt die Datenseite.
        rideModeSeite = if (navTarget != null) {
            RideModeSeite.NAVI_KARTE
        } else {
            RideModeSeite.DATEN
        }
        RecordingRepository.start(context)
        // Nach dem Start, nicht statt des Starts: Der Batterie-Hinweis ist
        // eine Empfehlung, keine Huerde — und er kommt hoechstens einmal
        // automatisch (siehe [showBatteryNotice]).
        if (!vonBatterieoptimierungAusgenommen(context) && !batterieHinweisGezeigt(context)) {
            showBatteryNotice = true
        }
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

    /**
     * „Re-zentrieren" ([RezentrierenChip]): holt die Navi-Kamera zurueck,
     * nachdem die Karte selbst verschoben oder gezoomt wurde (das pausiert
     * das Folgen, siehe `onUserPan`). Faehrt sofort auf die letzte bekannte
     * Navi-Position statt auf den naechsten GPS-Punkt zu warten; ohne
     * laufende Navigation (Kartenseite des Fahrmodus ohne Ziel) einfach
     * zurueck auf den letzten Aufzeichnungspunkt mit der heutigen Kamera.
     */
    fun rezentriereNaviKamera() {
        followMe = true
        if (navTarget == null) {
            livePoints.lastOrNull()?.let { controller.moveTo(it.lat, it.lon, minZoom = null) }
            return
        }
        navPosition?.let { (lat, lon) ->
            controller.moveToNavCamera(
                lat = lat,
                lon = lon,
                zoom = navZoom ?: NAV_ZOOM_NAH,
                bearingGrad = if (navCourseUp) navKurs ?: 0.0 else 0.0,
                versatz = navCourseUp,
            )
        }
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
     * Oeffnet das **modale** Suchblatt als reinen Ortswaehler.
     *
     * Die Auswahl geht ausschliesslich an [onPicked]; [selectedPlace] bleibt
     * unberuehrt und die Ortskarte erscheint nicht. Gebraucht wird das genau
     * einmal: von „Wegpunkt per Suche" in der Routenplanung — dort ist das
     * Erkunden-Blatt nicht komponiert, und die Suche ist eine kurze Besorgung,
     * die einen Ort zurueckbringt und dann verschwindet.
     *
     * Die gewoehnliche Ortssuche laeuft **nicht** mehr hier durch: Sie sitzt
     * seit dem Umbau im Erkunden-Blatt an Ort und Stelle (siehe
     * `ExploreSheet.kt` und `exploreSearching` oben). [onPicked] ist deshalb
     * kein optionaler Parameter mehr — ein Aufruf ohne Rueckruf haette keinen
     * Aufrufer und waere nur noch ein zweiter Weg zu demselben Ziel.
     */
    fun openPlaceSearch(onPicked: (Place) -> Unit) {
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
     * Beendet die Suche **im Kartenblatt** (nicht die modale der Planung):
     * Fokus weg, Tastatur zu, Feld und Treffer geleert — das Blatt steht
     * danach wieder auf der Stufe, die es vor der Suche hatte.
     *
     * Das Leeren gehoert dazu und ist keine Bequemlichkeit: Bliebe die alte
     * Anfrage stehen, zeigte das Blatt beim naechsten Antippen die Treffer von
     * vorgestern, bevor der erste Buchstabe getippt ist.
     */
    fun endExploreSearch() {
        exploreSearching = false
        searchQuery = ""
        searchResults = emptyList()
        searchError = null
        focusManager.clearFocus()
    }

    /**
     * Waehlt [place] aus einer der beiden Suchen — ein Nominatim-Treffer oder
     * ein Eintrag aus „Zuletzt gesucht" (beide sind zu diesem Zeitpunkt schon
     * ein [Place], siehe [PlaceResults]).
     *
     * Steht ein Ortswaehler-Rueckruf offen (Wegpunktsuche der Planung, siehe
     * [openPlaceSearch]), bekommt der den Ort und sonst passiert nichts.
     * Andernfalls kommt der Aufruf aus dem Erkunden-Blatt: Dann wird [place]
     * zum ausgewaehlten Ort, die Karte springt hin und die Ortskarte
     * ([PlaceCard]) uebernimmt.
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
            context = context,
            start = TrackPoint(lat = place.lat, lon = place.lon),
            profile = routeProfile,
            fromMapCenter = false,
            onMessage = appViewModel::showMessage,
            onOfferMissingSegments = appViewModel::offerMissingSegments,
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

    /**
     * „+ Als Wegpunkt" auf der Ortskarte — die Aktion, die es dort **in jedem**
     * Kartenmodus gibt (siehe den Karte-Screen in
     * `docs/design/prototyp-eine-leiste.html`): Sie ist der Weg, auf dem aus
     * einem gesuchten Ort eine Route aus mehreren Orten wird, ohne vorher
     * „Route planen" zu suchen.
     *
     * Zwei Faelle, ein Ergebnis:
     *  * **Planung laeuft nicht** — die Planung beginnt hier (derselbe Einstieg
     *    [enterPlanning] wie die Aktionszeile des Kartenblatts), und der Ort ist
     *    ihr erster Wegpunkt. Was von einer frueheren Planung noch herumliegt
     *    (eine Route, die die Aufzeichnung ueberlebt hat, siehe [runRecording],
     *    oder eine uebernommene Generator-Runde), wird dabei zum neuen Anfang
     *    weggeraeumt.
     *  * **Planung laeuft** — der Ort haengt sich hinten an, ueber genau
     *    dieselbe [addPlaceAsWaypoint], die auch die Wegpunktsuche der
     *    Planungsliste benutzt. Damit entstehen Name und Koordinate auf beiden
     *    Wegen identisch, samt der dortigen Sonderregel fuer eine noch nicht
     *    bestaetigte uebernommene Runde.
     *
     * [enterPlanning] lehnt waehrend einer laufenden Aufzeichnung ab (mit
     * eigener Meldung); dass es dazu kam, steht danach am Modus — deshalb die
     * zweite Abfrage statt einer Kopie jener Bedingung.
     */
    fun addPlaceAsWaypointFromCard(place: Place) {
        if (mode != MapMode.PLANEN) {
            enterPlanning()
            if (mode != MapMode.PLANEN) return
            routeFromGenerator = false
            plannedRoute = null
            plannedFor = null
            waypoints = emptyList()
        }
        addPlaceAsWaypoint(place)
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
                context = context,
                start = start,
                profile = routeProfile,
                fromMapCenter = position == null,
                onMessage = appViewModel::showMessage,
                onOfferMissingSegments = appViewModel::offerMissingSegments,
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

    /**
     * Schaltet die Karte beim Start jeder Navigation in den Fahr-Blick —
     * gemeinsames Stueck aller Startpfade ([runNavigateRide] fuer die
     * gespeicherte Tour, [runNavigatePlannedRoute] fuer die geplante Route):
     *
     *  * **Karte folgt der Position**: `followMe` an; zentriert wird sofort
     *    auf einen frischen Fix (ersatzweise den letzten bekannten Standort),
     *    nicht erst beim naechsten GPS-Punkt des Navigations-Effekts. Wer
     *    zwischenzeitlich selbst pannt, gewinnt — dann bleibt die Kamera weg.
     *    Ab dem ersten GPS-Punkt uebernimmt die **Navi-Kamera** des
     *    Navigations-Effekts: course-up (sofern nicht auf Nord oben
     *    umgeschaltet), Zoom nach Tempo, Position im unteren Drittel.
     *  * **Aufzeichnung startet automatisch mit** (Komoot-Muster): ohne
     *    laufende Aufzeichnung ueber [startRecording] samt Berechtigungs-
     *    und Batteriefluss; mit laufender wechselt nur die Ansicht. In
     *    beiden Faellen landet die Fahrt auf der NAVI_KARTE-Seite des
     *    Fahrmodus. „Beenden" im HUD beendet weiterhin NUR die Navigation —
     *    die Aufzeichnung endet ausschliesslich ueber ihre eigenen Knoepfe
     *    (Kompaktleiste bzw. Datenseite, mit der Kreuz-Rueckfrage).
     *  * **Einmaliger Hinweis, wenn die Sprachansagen aus sind**: Wer
     *    „Navigieren" tippt, erwartet Ansagen; deren Hauptschalter steht ab
     *    Werk aber bewusst auf AUS (siehe `record/RecordingSettings.kt`). Die
     *    Snackbar nennt beide Wege zum Einschalten — den Lautsprecher im HUD
     *    und Mehr → Aufzeichnung — und laeuft ueber denselben Meldungskanal
     *    wie alle Hinweise dieses Screens.
     *
     * Das Schliessen der jeweiligen Bedienflaeche (Tour-Karte bzw.
     * Planungsblatt) bleibt bei den Aufrufern — es ist je Startpfad ein
     * anderes Blatt.
     */
    fun starteNavigationsAnsicht() {
        followMe = true
        // Die gemerkte Kompass-Wahl frisch einlesen — wie [sprachansagenAn]
        // kann sie sich seit dem letzten Navigationsstart geaendert haben.
        navCourseUp = navCourseUpAktiviert(context)
        if (!sprachansagenAktiviert(context)) {
            appViewModel.showMessage(
                "Sprachansagen sind aus — hier im HUD oder unter Mehr → Aufzeichnung einschalten.",
            )
        }
        // Komoot-Muster: Eine Navigation zeichnet automatisch mit auf.
        // Laeuft schon eine Aufzeichnung, wechselt nur die Ansicht auf die
        // Kartenseite des Fahrmodus; sonst startet die Aufzeichnung ueber den
        // bestehenden Pfad ([startRecording], inklusive Berechtigungs- und
        // Batteriefluss) und landet ueber [runRecording] ebenfalls dort.
        // Wird die Berechtigung verweigert, laeuft die Navigation trotzdem —
        // nur eben ohne Aufzeichnung ([withPermissions] blockiert nichts).
        if (isRecording) {
            rideModeSeite = RideModeSeite.NAVI_KARTE
        } else {
            startRecording()
        }
        scope.launch {
            val position = currentLocation(context)?.let { it.latitude to it.longitude }
                ?: controller.lastKnownLocation()
            if (position != null && followMe) {
                controller.moveTo(position.first, position.second, MIN_RECORDING_ZOOM)
            }
        }
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
        // Echter Navigationsmodus statt Statuszeile: Die Tour-Karte mit dem
        // eben getippten „Navigieren" weicht (die Linie der Tour bleibt, siehe
        // den `setTrack`-Effekt oben), das HUD uebernimmt, die Kamera geht auf
        // die Position. Wird die Tour spaeter erneut ausgewaehlt, zeigt ihre
        // Karte wie bisher den deaktivierten Knopf „Navigation läuft".
        hoverPoint = null
        appViewModel.select(null)
        starteNavigationsAnsicht()
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
        // Der Modus bleibt PLANEN (siehe KDoc oben), aber die Ansicht wird
        // trotzdem zur Navigation: Das Planungsblatt klappt ein — Wegpunkte
        // setzt jetzt niemand mehr, und das HUD braucht die Karte —, die
        // Kamera geht auf die Position.
        planSheetExpanded = false
        starteNavigationsAnsicht()
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
                if (rideId != null) {
                    // Die volle Tour on-demand laden — die Liste haelt nur
                    // noch Zusammenfassungen ohne Punkte.
                    scope.launch {
                        appViewModel.loadRide(rideId)?.let { runNavigateRide(it) }
                    }
                }
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

    // ------------------------------- geplante Route → Aufnahme-Knopf der Huelle
    // Der schwebende Aufnahme-Knopf neben der Navigationskapsel (siehe
    // `ui/TrailscapeApp.kt` und `ui/components/RecCapsuleButton.kt`) zeigt
    // „Route bereit" mit Kilometerzahl, sobald hier eine geplante Route liegt.
    // Er steht ausserhalb jedes Screens und kann `plannedRoute` — einen
    // `rememberSaveable`-Zustand dieses Bildschirms — nicht sehen; gemeldet
    // wird deshalb die eine Zahl, die er anzeigt.
    //
    // Bewusst EIN abgeleiteter Effekt statt eines Aufrufs an jeder der elf
    // Stellen, an denen `plannedRoute` entsteht oder verschwindet (Berechnung,
    // Fehlschlag, Generator, `exitPlanning`, `restorePlanning`,
    // `discardGeneratedRoute`, `runRecording`, Kartentipp …): Elf Aufrufe
    // waeren elf Gelegenheiten, einen zu vergessen — und ein vergessener
    // liesse den Knopf eine Route anbieten, die es nicht mehr gibt. So kann
    // die Meldung per Konstruktion nicht von der Wahrheit abweichen.
    //
    // Waehrend einer laufenden Navigation meldet der Effekt `null`: Die Route
    // wird dann bereits gefahren, „bereit" waere die falsche Auskunft. Der
    // Schluessel ist bewusst billig (Zahl und Boolean statt der Route selbst
    // mit ihrer kompletten Punktliste), damit der Vergleich nicht bei jeder
    // Rekomposition ueber tausende Punkte laeuft.
    LaunchedEffect(plannedRoute?.distanceKm, navTarget != null) {
        appViewModel.reportPlannedRoute(
            if (navTarget == null) plannedRoute?.distanceKm else null,
        )
    }

    // „Mit Navigation starten" aus dem Bereit-Dialog des Aufnahme-Knopfs
    // (`ui/ReadyToRideDialog.kt`) — dasselbe Muster und derselbe Gewinn wie
    // bei [pendingRecordStart] gleich darueber: Beantwortet wird die Bitte mit
    // **derselben** lokalen Funktion, die auch der „Navigieren"-Knopf im
    // Planungsblatt ausloest, also samt Berechtigungsabfrage. Die Huelle baut
    // keinen zweiten Startweg.
    val navigatePlannedRequest by appViewModel.navigatePlannedRequest
        .collectAsStateWithLifecycle()
    LaunchedEffect(navigatePlannedRequest) {
        if (!navigatePlannedRequest) return@LaunchedEffect
        appViewModel.consumeNavigatePlannedRequest()
        // Ist die Route zwischenzeitlich verschwunden, tut `navigatePlannedRoute`
        // von sich aus nichts — die Pruefung steht dort.
        navigatePlannedRoute()
    }

    // „Route verwerfen" aus demselben Dialog. Die Huelle hat ihre Anzeige
    // schon geleert; hier wird die Planung wirklich weggeraeumt — inklusive
    // eines noch offenen Generator-Panels, dessen Vorschlag sonst beim
    // naechsten Blick auf die Karte wieder als Route dastuende.
    val discardPlannedRouteRequest by appViewModel.discardPlannedRouteRequest
        .collectAsStateWithLifecycle()
    LaunchedEffect(discardPlannedRouteRequest) {
        if (!discardPlannedRouteRequest) return@LaunchedEffect
        appViewModel.consumeDiscardPlannedRouteRequest()
        if (generation.target != null) discardGeneratedRoute()
        exitPlanning()
    }

    // -------------------------------------- „auf der Karte zeigen" → Auswahl
    // Der Touren-Tab (`ui/rides/RidesScreen.kt`) schickt ueber
    // [AppViewModel.showRideOnMapRequest] eine Tour-Kennung herueber. Das ist
    // der Rest dessen, was frueher das Tourenblatt dieses Screens war (siehe
    // Klassen-KDoc, „Was aus dem Blatt verschwunden ist"): Ausgefuehrt wird
    // genau das, was bis dahin der Tipp auf eine Tour in jenem Blatt tat —
    // Tour auswaehlen, womit die Effekte weiter oben ihre Spur zeichnen und
    // die Karte auf sie zoomen, und das Blatt eingeklappt lassen, damit die
    // Tourkarte (`RideCard`) den unteren Rand bekommt.
    //
    // Das Einklappen steht hier bewusst NICHT noch einmal im Code: Eine
    // ausgewaehlte Tour ist selbst ein Vorrang-Zustand, und der Effekt weiter
    // unten klappt das Blatt darauf ohnehin ein (siehe „Blatt-Stufe:
    // Rangfolge"). Ein zweiter Aufruf waere eine zweite Regel fuer dieselbe
    // Sache.
    val showRideOnMapRequest by appViewModel.showRideOnMapRequest
        .collectAsStateWithLifecycle()
    LaunchedEffect(showRideOnMapRequest) {
        val rideId = showRideOnMapRequest ?: return@LaunchedEffect
        appViewModel.select(rideId)
        // Quittiert wird auch dann, wenn die Kennung nichts trifft: Eine
        // stehen gebliebene Bitte wuerde bei jedem weiteren Bildaufbau erneut
        // versucht.
        appViewModel.consumeShowRideOnMapRequest()
    }

    // [AppViewModel.pendingRideDetail] holt dieser Screen NICHT ab: Die
    // Detailansicht einer Tour gehoert seit der Fuehrung „Eine Leiste" in den
    // Touren-Tab (`ui/rides/RidesScreen.kt`), und `requestRideDetail`
    // navigiert genau dorthin. Zwei Abholer waeren ein Rennen: Der noch
    // komponierte Karten-Screen kaeme dem Tab zuvor und oeffnete das
    // Detailfenster ueber der Karte, waehrend die Navigation zugleich
    // wegwechselt.

    // ------------------------------------------------ Blatt-Stufe: Rangfolge
    // Ausformuliert im Klassen-KDoc oben („Rangfolge am unteren Kartenrand");
    // hier nur die Umsetzung. Bewusst ein eigener Effekt statt einer reinen
    // Ableitung: Ein schon aufgezogenes Blatt soll beim Eintreten eines
    // Vorrang-Zustands **dauerhaft** einklappen — mit einer reinen Ableitung
    // bliebe `exploreExpanded` unveraendert `true` und spraenge sofort wieder
    // auf, sobald der Vorrang-Zustand endet. Eine abgeleitete
    // „Sichtbarkeits"-Variable braucht es dagegen nicht: Das Erkunden-Gesicht
    // ist in all diesen Zustaenden ohnehin nicht komponiert (siehe die
    // Bedingung um den `ExploreSheet`-Aufruf weiter unten).
    //
    // Geschrieben wird hier `exploreExpanded` direkt und nicht ueber
    // [goToSheetStage]: Waehrend [MapMode.PLANEN] — selbst ein Vorrang-Zustand
    // — wuerde ein „eine Stufe tiefer" den gerade geoeffneten Planungsinhalt
    // wieder zuklappen, und genau der ist in diesem Fall gemeint.
    val sheetPriorityActive =
        isRecording || navTarget != null || mode == MapMode.PLANEN ||
            selectedRide != null || selectedPlace != null || searchOpen
    LaunchedEffect(sheetPriorityActive) {
        if (sheetPriorityActive && exploreExpanded) {
            exploreExpanded = false
        }
    }

    // Ein Vorrang-Zustand beendet auch die Suche: Wer aufzeichnet, navigiert
    // oder plant, sucht nicht nebenher — und eine Tastatur vor einem Blatt,
    // das es nicht mehr gibt, waere das Schlechteste von beidem.
    LaunchedEffect(sheetPriorityActive) {
        if (sheetPriorityActive && exploreSearching) endExploreSearch()
    }

    // Zurueck-Geste: dieselben Stufen abwaerts wie der Griff — erst aus der
    // Suche, dann aus der Rundenwahl, dann aus der Planung, dann eine
    // Blatt-Stufe tiefer, sonst das normale Verhalten der App.
    //
    // Die Suche steht vorn, weil sie der oberste Zustand ist: Wer sucht und
    // zurueck geht, will aus der Suche heraus — nicht gleich das ganze Blatt
    // zuklappen.
    //
    // Aus der Planung fuehrt **eine** Geste heraus, auch wenn deren Inhalt
    // gerade eingeklappt ist: Diese eingeklappte Stufe stellt `onMapTap` beim
    // Wegpunktsetzen her (siehe [sheetStage]), sie ist also kein Schritt, den
    // die Nutzerin selbst gegangen waere und den sie rueckwaerts wieder
    // erwarten wuerde.
    BackHandler(
        enabled = exploreSearching || generation.target != null ||
            mode == MapMode.PLANEN || sheetStage == MapSheetStage.AUFGEZOGEN,
    ) {
        when {
            exploreSearching -> endExploreSearch()
            // Die Rundenwahl ist jetzt das unterste Blatt und damit der
            // oberste Zustand. Vorher lag sie als eigene Karte oben und die
            // Zurueck-Geste ging an ihr vorbei — sie beendete die Planung
            // darunter, waehrend die Wahl unbeirrt stehen blieb.
            generation.target != null -> discardGeneratedRoute()
            mode == MapMode.PLANEN -> goToSheetStage(MapSheetStage.AUFGEZOGEN)
            else -> goToSheetStage(MapSheetStage.EINGEKLAPPT)
        }
    }

    // Zurueck auf der Kartenseite des Fahrmodus fuehrt zur Datenseite —
    // NICHT zum Beenden der Aufzeichnung oder aus dem Fahrmodus heraus.
    // Bewusst ein eigener, NACH dem allgemeinen `BackHandler` komponierter
    // Handler: Der zuletzt komponierte gewinnt, sobald er aktiviert ist, und
    // waehrend der Aufzeichnung ist ohnehin keiner der obigen Zustaende
    // erreichbar.
    BackHandler(enabled = naviKarteAktiv) {
        rideModeSeite = RideModeSeite.DATEN
    }

    // ------------------------------------------------------------------ Aufbau
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    // Ob die Tastatur steht. Bewusst `WindowInsets.isImeVisible` und nicht
    // `WindowInsets.ime`: Die Huelle (`ui/TrailscapeApp.kt`) hat den
    // IME-Abstand mit `imePadding()` bereits **verzehrt**, hier kaeme also
    // ueberall null an. `isImeVisible` liest die Sichtbarkeit direkt am
    // Fenster und ist davon unberuehrt.
    val imeVisible = WindowInsets.isImeVisible

    // ## Der Platz, den ein unteres Blatt wirklich hat
    //
    // Ein Blatt bekam seine Obergrenze frueher als **Anteil der
    // Bildschirmhoehe** — 0,8 fuer die Tourenliste, 0,45 fuer die Planung. Das
    // ist genau die Konstellation, in der ein `verticalScroll` oder eine
    // `LazyColumn` NICHT scrollt: Faellt der Inhalt unter die Obergrenze, wird
    // der Behaelter inhaltsgross und hat keinen Scrollweg — nur das Fenster
    // darum (bei [SwipeableSheet] der Zieh-Offset, bei der Spalte hier der
    // Rest des Stapels) ist kleiner. Ergebnis: unten abgeschnitten und kein Weg
    // hin. Ein zu grosszuegiger Deckel ist hier schaedlicher als ein zu enger.
    //
    // Deshalb rechnet **ein** Budget den Platz aus, den der Stapel einem Blatt
    // ueberhaupt lassen kann, und die Inhalte mit echtem Scrollweg — Planung
    // und Rundenwahl — ziehen davon nur noch ab, was ihr eigener Peek
    // verbraucht. Die Aktionszeile der Erkunden-Stufe braucht das nicht: eine
    // Zeile fester Hoehe, kein Scrollweg, nichts zu deckeln.
    //
    // Was oben drueber steht — die beiden runden Knoepfe und die Karte zur
    // Auswahl, falls eine dran ist — wird **gemessen** statt geschaetzt
    // ([overlayHeaderPx] weiter unten). Eine Zahl von Hand haette die Karte
    // nicht kennen koennen und waere bei 200 % Schriftgroesse ohnehin falsch.
    var overlayHeaderPx by remember { mutableIntStateOf(0) }
    val overlayHeaderHeight = if (overlayHeaderPx > 0) {
        with(density) { overlayHeaderPx.toDp() }
    } else {
        // Erster Bildaufbau, noch nichts gemessen: die beiden Knoepfe als
        // Startwert. Ohne ihn waere das Budget fuer genau einen Bildaufbau zu
        // grosszuegig — also fuer einen Bildaufbau der alte Fehler.
        OverlayFloatingButtonsHeight
    }

    // Oben loest die Huelle die System-Insets auf; diese Hoehe steht dem
    // Karten-Screen gar nicht erst zur Verfuegung.
    val overlayTopInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    // Unten entweder die Tastatur (die Huelle hat dafuer schon `imePadding`
    // gelegt) oder die Bodenfreiheit der schwebenden Kapsel — nie beides,
    // genau wie im Padding des Stapels weiter unten.
    val overlayBottomInset = if (imeVisible) {
        WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    } else {
        LocalFloatingNavigationBarSpace.current
    }

    val overlaySheetBudget = (
        screenHeight -
            overlayTopInset -
            overlayBottomInset -
            overlayHeaderHeight -
            OverlayScreenPadding * 2 -
            OverlayGap
        ).coerceAtLeast(MinOverlaySheetBudget)

    // Was der Peek der Rundenwahl unabhaengig von der Vorschlagsliste braucht —
    // Griff, Titelzeile, die beiden Zielzeilen, „Übernehmen" und die Raender.
    // Der Rest teilt sich zwischen Liste und Koerper auf; beide scrollen, wenn
    // ihr Anteil nicht reicht.
    val generationRest =
        (overlaySheetBudget - GenerationPeekFixedHeight).coerceAtLeast(MinSheetBodyHeight)

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
                // Hinter der Datenseite des Fahrmodus liegt die Karte
                // vollstaendig verdeckt. Sie dann weiterzeichnen zu lassen
                // waere ausgerechnet in dem Modus teuer, der fuer
                // mehrstuendige Touren gedacht ist — und der ohnehin schon
                // den Bildschirm anlaesst. Auf der NAVI_KARTE-Seite ist die
                // Karte dagegen die Hauptdarstellerin und zeichnet natuerlich.
                renderingActive = rideModeSeite != RideModeSeite.DATEN,
            )

            // Auch die Kartenseite des Fahrmodus haelt den Bildschirm an —
            // dieselbe Lenker-Situation wie im Dialog, nur mit Karte statt
            // grosser Zahlen (KeepScreenOn wohnt in `RideModeScreen.kt`).
            if (naviKarteAktiv) {
                KeepScreenOn()
            }

            // ------------------------------------------------------------ oben
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .padding(OverlayScreenPadding),
                verticalArrangement = Arrangement.spacedBy(OverlayGap),
            ) {
                // Kein Overlay-Chip und keine Knopfreihe an dieser Kante:
                // Suche, „Route planen", Kartenstil und Offline wohnen alle im
                // einen unteren Blatt und seinen Stufen (siehe
                // `ExploreSheet.kt` und [MapSheetStage]) — ein Suchchip hier
                // oben waere ein zweiter Weg zu einem Feld, das eine Stufe
                // tiefer ohnehin dauerhaft steht. Oben bleiben nur Zustaende,
                // die sich ueber die Karte legen MUESSEN (Hinweise,
                // Navigation, Downloadfortschritt).

                // Die Zaehler-Pille der Entdeckt-Kacheln ist der einzige
                // Bewohner dieser Kante, der KEIN Hinweis ist — sie zeigt
                // einen Zustand an und laesst sich nicht bedienen. Sie steht
                // hier im selben Stapel und nicht als eigenes Overlay in der
                // Box, damit sie sich niemals mit einem Hinweis oder dem
                // Navigations-HUD ueberlagert; der Stapel schiebt beides
                // sauber untereinander.
                //
                // Gezeigt nur im ruhigen Kartenzustand: Wer aufzeichnet oder
                // navigiert, braucht am oberen Rand jeden Pixel fuer HUD und
                // Hinweise — und die Zahl aendert sich waehrend der Fahrt
                // ohnehin erst beim Speichern der Tour (siehe die Snackbar im
                // AppViewModel).
                if (explorerTilesEnabled && explorerTiles.isNotEmpty() &&
                    !isRecording && navTarget == null
                ) {
                    ExplorerTilesPill(
                        tileCount = explorerTiles.size,
                        square = explorerMaxSquare,
                    )
                }

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
                    NavigationHud(
                        label = target.label,
                        remainingKm = navState?.remainingKm ?: navTotalKm,
                        doneKm = navState?.doneKm,
                        offRoute = navState?.offRoute == true,
                        naechsteKurve = naechsteKurveInfo?.first?.richtung,
                        kurveAbstandM = naechsteKurveInfo?.second,
                        tempoKmh = navTempoKmh,
                        sprachansagenAn = sprachansagenAn,
                        onToggleSprachansagen = {
                            val neu = !sprachansagenAn
                            setzeSprachansagenAktiviert(context, neu)
                            sprachansagenAn = neu
                        },
                        onStop = ::stopNavigation,
                    )
                    // Der Kompass-Umschalter der Navi-Kamera, rechtsbuendig
                    // unter dem HUD: Fahrtrichtung oben <-> Nord oben; die
                    // Wahl wandert in die Prefs und gilt fuer jede weitere
                    // Navigation. Beim Umschalten faehrt die Kamera sofort —
                    // nicht erst mit dem naechsten GPS-Punkt.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        NavKompassKnopf(
                            courseUp = navCourseUp,
                            onToggle = {
                                val neu = !navCourseUp
                                setzeNavCourseUpAktiviert(context, neu)
                                navCourseUp = neu
                                if (!neu) {
                                    controller.resetNavCamera()
                                } else if (followMe) {
                                    navPosition?.let { (lat, lon) ->
                                        controller.moveToNavCamera(
                                            lat = lat,
                                            lon = lon,
                                            zoom = navZoom ?: NAV_ZOOM_NAH,
                                            bearingGrad = navKurs ?: 0.0,
                                            versatz = true,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                // Die Rundenwahl stand hier — als Karte im oberen Stapel,
                // waehrend das Planungsblatt unten mitlief. Zwei Flaechen an
                // gegenueberliegenden Raendern fuer eine Aufgabe, und das
                // untere Blatt schnitt die obere beim Aufziehen ab. Sie ist
                // jetzt selbst das untere Blatt (siehe `RouteGenerationSheet`
                // weiter unten). Oben bleibt nur, was sich ueber die Karte
                // legen MUSS.

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
            //
            // Auf der NAVI_KARTE-Seite des Fahrmodus weicht der ganze Stapel
            // der Kompaktleiste: Karte und Fuehrung sind dort die Hauptsache,
            // Live-Leiste, Blaetter und die runden Knoepfe wuerden sie nur
            // verstellen (Beenden/Pause wohnen in der Leiste, „Re-zentrieren"
            // ersetzt den Positions-Knopf).
            if (naviKarteAktiv) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxWidth()
                        .padding(OverlayScreenPadding)
                        // Dieselbe Bodenfreiheit fuer die schwebende
                        // Navigationskapsel wie beim normalen Stapel.
                        .padding(bottom = LocalFloatingNavigationBarSpace.current),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!followMe) {
                        RezentrierenChip(onClick = ::rezentriereNaviKamera)
                        Spacer(Modifier.height(OverlayGap))
                    }
                    RideCompactBar(
                        speedKmh = speedKmh,
                        distanceKm = recordedKm,
                        ascentM = liveAscentM,
                        elapsedS = (elapsedMs / 1000).toInt(),
                        paused = isPaused,
                        autoPaused = isAutoPaused,
                        onTogglePause = { RecordingRepository.togglePause() },
                        onStop = { RecordingRepository.stop() },
                        onShowData = { rideModeSeite = RideModeSeite.DATEN },
                    )
                }
            } else if (!searchOpen) {
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
                        //
                        // Bei offener Tastatur aber NICHT: Die Kapsel sitzt dann
                        // hinter der Tastatur und ist gar nicht sichtbar. Der
                        // reservierte Platz waere ein gutes Stueck Leere
                        // zwischen Blatt und Tastatur — genau die Luecke, die
                        // beim ersten Anlauf der Suche im Blatt zu sehen war.
                        .padding(bottom = if (imeVisible) 0.dp else LocalFloatingNavigationBarSpace.current),
                    horizontalAlignment = Alignment.End,
                ) {
                    val ride = selectedRide
                    val place = selectedPlace

                    // Alles ueber dem Blatt in einer eigenen Spalte, damit es
                    // sich in einem Stueck messen laesst — die Zahl ist das
                    // [overlayHeaderHeight] der Budgetrechnung weiter oben.
                    // Eine Konstante von Hand kannte die Karte nicht, die hier
                    // je nach Zustand steht (Aufzeichnung, Tour, Ort), und lag
                    // bei grosser Schrift ohnehin daneben.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { overlayHeaderPx = it.height },
                        horizontalAlignment = Alignment.End,
                    ) {
                        // Waehrend einer Navigation mit selbst verschobener
                        // Karte: der Rueckweg in die Navi-Kamera — derselbe
                        // Chip wie auf der NAVI_KARTE-Seite.
                        if (navTarget != null && !followMe) {
                            RezentrierenChip(
                                onClick = ::rezentriereNaviKamera,
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        // Der fruehere RecordButton an dieser Stelle ist mit
                        // der Fuehrung "Eine Leiste" entfallen: Die Aufnahme
                        // startet und stoppt jetzt der eine schwebende
                        // REC-Knopf neben der Navigationskapsel (siehe
                        // `ui/components/RecCapsuleButton.kt`) — zwei
                        // Startknoepfe auf demselben Bildschirm waren nach dem
                        // ersten Geraetetest sichtbar einer zu viel.
                        LocateButton(onClick = ::goToMyPosition, following = followMe)
                        Spacer(Modifier.height(12.dp))

                        when {
                            isRecording -> LiveRecordingCard(
                                speedKmh = speedKmh,
                                distanceKm = recordedKm,
                                elapsedS = (elapsedMs / 1000).toInt(),
                                ascentM = liveAscentM,
                                pointCount = livePoints.size,
                                paused = isPaused,
                                autoPaused = isAutoPaused,
                                onTogglePause = { RecordingRepository.togglePause() },
                                onStop = { RecordingRepository.stop() },
                                onOpenRideMode = { rideModeSeite = RideModeSeite.DATEN },
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
                                // Synchron aus dem Standortpunkt der Karte
                                // gelesen (siehe dessen KDoc): kein zweiter
                                // GPS-Abonnent nur fuer diese eine
                                // Entfernungszahl.
                                distanceKm = controller.lastKnownLocation()?.let { (lat, lon) ->
                                    haversineM(
                                        TrackPoint(lat = lat, lon = lon),
                                        TrackPoint(lat = place.lat, lon = place.lon),
                                    ) / 1000.0
                                },
                                onRouteHere = { runRouteToPlace(place) },
                                onRoundTripHere = { runRoundTripFromPlace(place) },
                                // Beide Wegpunkt-Aktionen der Ortskarte — „Als
                                // Wegpunkt" waehrend der Planung und
                                // „+ Als Wegpunkt" im Erkunden-Zustand — gehen
                                // durch **dieselbe** Funktion: Sie startet die
                                // Planung, falls sie noch nicht laeuft, und
                                // haengt den Ort sonst hinten an, ueber
                                // denselben Weg wie die Wegpunktsuche der
                                // Planungsliste (gleicher Name, gleiche
                                // Koordinate). Zwei Lambdas mit derselben
                                // Fallunterscheidung waeren zwei Gelegenheiten,
                                // sie auseinanderlaufen zu lassen. Die
                                // Ortskarte schliesst dabei wie bei den anderen
                                // Aktionen (`selectedPlace = null` steckt in
                                // [addPlaceAsWaypoint]).
                                onAddWaypoint = { addPlaceAsWaypointFromCard(place) },
                                onAddAsWaypoint = { addPlaceAsWaypointFromCard(place) },
                                onClose = { selectedPlace = null },
                            )
                        }
                    }

                    // Ein Blatt, eine Aufgabe: Solange Vorschlaege zur Wahl
                    // stehen, IST das Blatt die Wahl; danach ist es die
                    // Planung. Vorher liefen beide gleichzeitig — die Wahl
                    // oben, die Planung unten — und ueberlappten sich beim
                    // Aufziehen (siehe KDoc von `RouteGenerationSheet`).
                    if (generation.target != null) {
                        Spacer(Modifier.height(OverlayGap))
                        RouteGenerationSheet(
                            state = generation,
                            expanded = planSheetExpanded,
                            onExpandedChange = { planSheetExpanded = it },
                            route = plannedRoute,
                            candidatesMaxHeight = generationRest * GENERATION_CANDIDATES_SHARE,
                            bodyMaxHeight = generationRest * GENERATION_BODY_SHARE,
                            locating = locating,
                            onStart = ::generateRoutes,
                            onCancel = RouteGenerationController::cancel,
                            onSelect = RouteGenerationController::select,
                            onNextSuggestions = {
                                RouteGenerationController.nextSuggestions(appViewModel::showMessage)
                            },
                            onApply = ::applyGeneratedRoute,
                            onDiscard = ::discardGeneratedRoute,
                            onHoverPoint = { hoverPoint = it },
                        )
                    } else if (mode == MapMode.PLANEN) {
                        // Die oberste Stufe desselben Blatts
                        // ([MapSheetStage.PLANEN]): Der Planungsinhalt tritt an
                        // die Stelle der Aktionszeile, der Griff bleibt.
                        Spacer(Modifier.height(OverlayGap))
                        PlanningSheet(
                            expanded = planSheetExpanded,
                            // Der Griff bleibt hier die Stufe zwischen vollem
                            // Planungsinhalt und blosser Statuszeile — nicht
                            // der Ausgang aus der Planung. Wer waehrend des
                            // Wegpunktsetzens die Karte freiraeumt, will die
                            // Planung ja gerade behalten; der Ausgang steht
                            // eindeutig als Zurueck-Pfeil in der Kopfzeile
                            // (`onClose` weiter unten).
                            onExpandedChange = { planSheetExpanded = it },
                            profile = routeProfile,
                            onProfileChange = { routeProfile = it },
                            // Der Segmentschalter „Einfach | Rundweg": Er
                            // aendert keinen Wegpunkt, nur die Art, wie sie
                            // verbunden werden — nachgerechnet wird ueber
                            // `roundTrip` im Schluessel des Planungs-Effekts.
                            roundTrip = roundTrip,
                            onRoundTripChange = { roundTrip = it },
                            waypoints = waypoints,
                            route = plannedRoute,
                            busy = planBusy,
                            error = planError,
                            maxHeight = (overlaySheetBudget - PlanningPeekFixedHeight)
                                .coerceAtLeast(MinSheetBodyHeight),
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
                            // Der Zurueck-Pfeil der Planungs-Kopfzeile: eine
                            // Stufe tiefer, also zurueck auf die Aktionszeile
                            // ([MapSheetStage.AUFGEZOGEN]). Dass dabei die
                            // Planung endet, faengt die Rueckhol-Snackbar in
                            // [exitPlanningWithUndo] ab, durch die
                            // [goToSheetStage] genau dafuer laeuft.
                            onClose = { goToSheetStage(MapSheetStage.AUFGEZOGEN) },
                        )
                    }

                    // Die beiden Erkunden-Stufen desselben Blatts (siehe
                    // `ExploreSheet.kt`): eingeklappt die Suchzeile, aufgezogen
                    // zusaetzlich die Aktionszeile — aber nur, wenn kein
                    // anderer Zustand den unteren Rand beansprucht: keine
                    // Aufzeichnung, keine gewaehlte Tour, kein Ort, keine
                    // Planung, keine Navigation und kein offener
                    // Generator-Vorschlag (dessen Blatt liegt hier ebenfalls;
                    // zwei Werkzeugflaechen zugleich helfen niemandem). Das ist
                    // zugleich die Rangfolge aus dem Klassen-KDoc oben
                    // („Rangfolge am unteren Kartenrand"): In all diesen
                    // Faellen ist das Blatt schlicht nicht komponiert, kein
                    // eigener Versteck-Zustand noetig.
                    if (mode == MapMode.ERKUNDEN && !isRecording && ride == null &&
                        place == null && navTarget == null && generation.target == null
                    ) {
                        Spacer(Modifier.height(OverlayGap))
                        ExploreSheet(
                            expanded = sheetStage == MapSheetStage.AUFGEZOGEN,
                            // Waehrend der Suche gibt es keinen Koerper zum
                            // Auf- und Zuklappen; der Griff wird dann zum
                            // Ausgang aus der Suche. Ohne das haette er im
                            // Suchzustand gar keine Wirkung — ein Bedienelement,
                            // das nichts tut, ist schlimmer als keins.
                            onExpandedChange = { want ->
                                when {
                                    exploreSearching -> endExploreSearch()
                                    want -> goToSheetStage(MapSheetStage.AUFGEZOGEN)
                                    else -> goToSheetStage(MapSheetStage.EINGEKLAPPT)
                                }
                            },
                            searchMaxHeight = screenHeight * SEARCH_RESULTS_MAX_HEIGHT_FACTOR,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            searching = exploreSearching,
                            onSearchingChange = { focused ->
                                // Nur das Gewinnen des Fokus schaltet um. Das
                                // Verlieren tut es NICHT: Wer eine Trefferzeile
                                // antippt, nimmt dem Feld kurz den Fokus — die
                                // Liste duerfte in genau diesem Moment nicht
                                // unter dem Finger verschwinden. Beendet wird
                                // die Suche ausdruecklich: ueber Zurueck, ueber
                                // das Einklappen oder mit der Auswahl.
                                if (focused) exploreSearching = true
                            },
                            searchBusy = searchBusy,
                            searchError = searchError,
                            searchResults = searchResults,
                            searchHistory = placeHistory,
                            onSelectPlace = { place ->
                                endExploreSearch()
                                onPlaceChosen(place)
                            },
                            // „Route planen" ist der Weg auf die oberste Stufe
                            // — derselbe Einstieg wie bisher, nur eine Stufe
                            // hoeher statt in ein zweites Blatt.
                            onStartPlanning = { goToSheetStage(MapSheetStage.PLANEN) },
                            onOpenStyle = { showStyleSheet = true },
                            onDownload = ::startDownload,
                            downloadEnabled = !downloadState.running,
                        )
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
            history = placeHistory,
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
            explorerTilesEnabled = explorerTilesEnabled,
            // Bewusst OHNE Schliessen des Blatts: Der Nebel liegt hinter dem
            // Blatt und ist beim Umlegen sofort zu sehen — wer ihn danach
            // wieder ausschalten will, muesste das Blatt sonst erneut suchen.
            onExplorerTilesEnabledChange = appViewModel::setExplorerTilesEnabled,
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

    // -------------------------------------------- Batterieoptimierungs-Hinweis
    // Hoechstens einmal automatisch (Prefs-Merker), ausgeloest in
    // [runRecording]; danach nur noch unter Mehr → Aufzeichnung.
    if (showBatteryNotice) {
        BatteryNoticeDialog(
            onAllow = {
                showBatteryNotice = false
                merkeBatterieHinweisGezeigt(context)
                try {
                    context.startActivity(batterieAusnahmeIntent(context))
                } catch (e: Exception) {
                    // Manche Geraete kennen den Dialog nicht — dann bleibt nur
                    // der Weg ueber die Systemeinstellungen.
                    appViewModel.showMessage(
                        "Der Systemdialog ließ sich nicht öffnen. Die Ausnahme lässt sich " +
                            "in den Android-Einstellungen unter „Akku“ erteilen.",
                    )
                }
            },
            onLater = {
                showBatteryNotice = false
                merkeBatterieHinweisGezeigt(context)
            },
        )
    }

    // ---------------------------------------------------------- Fahrmodus
    // Liegt als eigenes Fenster ueber allem (siehe `RideModeScreen.kt`) und
    // bekommt ausschliesslich fertige Werte: dieselben Aufzeichnungs-Flows wie
    // die Live-Leiste und den Navigationszustand, den der Effekt oben aus dem
    // `RouteNavigator` (`:core`) mitschreibt. Gerechnet wird dort nichts.
    if (rideModeSeite == RideModeSeite.DATEN && isRecording) {
        RideModeScreen(
            speedKmh = speedKmh,
            distanceKm = recordedKm,
            elapsedS = (elapsedMs / 1000).toInt(),
            ascentM = liveAscentM,
            paused = isPaused,
            autoPaused = isAutoPaused,
            navigation = navTarget?.let { target ->
                RideModeNavigation(
                    label = target.label,
                    remainingKm = navState?.remainingKm ?: navTotalKm,
                    offRoute = navState?.offRoute == true,
                    naechsteKurve = naechsteKurveInfo?.first?.richtung,
                    naechsteKurveM = naechsteKurveInfo?.second,
                )
            },
            onTogglePause = { RecordingRepository.togglePause() },
            onStop = {
                // Zurueck auf die Karte: Nach dem Stopp waehlt das AppViewModel
                // die gespeicherte Tour aus und meldet sie — das gehoert auf
                // die Karte, nicht hinter eine leere Fahranzeige.
                rideModeSeite = RideModeSeite.KEINE
                RecordingRepository.stop()
            },
            onClose = { rideModeSeite = RideModeSeite.KEINE },
            // „Karte" wechselt zur NAVI_KARTE-Seite dieses Screens (Dialog
            // zu, Kompaktleiste an) — der Fahrmodus endet dabei nicht.
            onShowMap = { rideModeSeite = RideModeSeite.NAVI_KARTE },
        )
    }

    // Die Tourendetailansicht stand hier bis zum Umbau auf das eine Blatt mit
    // drei Stufen als eigenes `Dialog`-Fenster — erreichbar ausschliesslich
    // ueber die Tourenliste im Blatt. Mit der Liste ist auch sie entfallen:
    // Touren und ihre Details wohnen im Touren-Tab (`ui/rides/`), und
    // `AppViewModel.requestRideDetail` navigiert genau dorthin (siehe
    // Klassen-KDoc, „Was aus dem Blatt verschwunden ist").

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

/**
 * Die Seiten des Fahrmodus waehrend einer laufenden Aufzeichnung.
 *
 * Der Fahrmodus ist EIN Modus mit zwei Ansichten: [DATEN] ist der grosse
 * Vollbild-Dialog (`RideModeScreen.kt`), [NAVI_KARTE] die Karte selbst mit
 * Kompaktleiste (`RideCompactBar.kt`), HUD (falls navigiert wird) und
 * KeepScreenOn — bewusst KEIN zweites Karten-Composable im Dialog, sondern
 * ein Zustand dieses Screens, in dem der Dialog schlicht zu ist. [KEINE] ist
 * die normale Karte mit der kleinen Live-Leiste (der Rueckweg, wenn der
 * Fahrmodus per Zurueck-Geste verlassen wurde).
 *
 * Gewechselt wird ueber den „Karte"-Knopf bzw. die Wischgeste der Datenseite
 * (→ [NAVI_KARTE]), den „Daten"-Knopf der Kompaktleiste und die
 * Zurueck-Geste der Kartenseite (→ [DATEN]) sowie die Zurueck-Geste der
 * Datenseite (→ [KEINE]). Enum statt zweier Booleans, damit sich Dialog und
 * Karten-Overlay nie gleichzeitig fuer zustaendig halten; als `Serializable`
 * bundle-faehig fuer `rememberSaveable` (dasselbe Muster wie [MapMode]).
 */
private enum class RideModeSeite {
    KEINE,
    DATEN,
    NAVI_KARTE,
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

/**
 * Auswahl des Kartenstils (Port des `_showStyleSheet`-Bottom-Sheets), seit
 * den Entdeckt-Kacheln zugleich der Ort ihres Schalters.
 *
 * ## Warum der Kachel-Schalter hier wohnt und nicht im Mehr-Tab
 * Er beantwortet dieselbe Frage wie die Stilliste darueber: „Wie soll die
 * Karte aussehen?" Seine Wirkung ist sofort und ausschliesslich auf der Karte
 * zu sehen — und genau die liegt hinter diesem Blatt. Im Mehr-Tab waere er
 * eine Einstellung ohne sichtbares Ergebnis, drei Bildschirme von ihrer
 * Wirkung entfernt. Die Trennlinie markiert dabei den Wechsel von „welche
 * Kacheln" zu „was liegt darueber": ein Schalter, keine weitere Stil-Option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapStyleSheet(
    current: MapStyle,
    onSelect: (MapStyle) -> Unit,
    explorerTilesEnabled: Boolean,
    onExplorerTilesEnabledChange: (Boolean) -> Unit,
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

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = CardPadding, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // `toggleable` statt eines Klick-Modifiers am Schalter:
                    // Die ganze Zeile schaltet, und die Bedienhilfen melden
                    // sie als einen Schalter statt als Text plus Knopf —
                    // dasselbe Muster wie die Schalterzeilen im Mehr-Tab
                    // (`more/RecordingCard.kt`).
                    .toggleable(
                        value = explorerTilesEnabled,
                        role = Role.Switch,
                        onValueChange = onExplorerTilesEnabledChange,
                    )
                    .padding(horizontal = CardPadding, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Entdeckt-Kacheln", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Befahrene Gegenden bleiben klar, der Rest liegt unter Nebel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // `onCheckedChange = null`: Der Schalter ist hier nur die
                // Anzeige des Zustands, geschaltet wird ueber die Zeile.
                Switch(checked = explorerTilesEnabled, onCheckedChange = null)
            }
        }
    }
}

/**
 * Die Zaehler-Pille der Entdeckt-Kacheln: „312 Kacheln · Größtes Quadrat 6×6"
 * — klein, oben links auf der Karte und nur, wenn der Layer an ist und
 * ueberhaupt etwas entdeckt wurde.
 *
 * Das groesste Quadrat kommt fertig herein ([square]) statt hier gerechnet zu
 * werden: Die Suche laeuft ueber den gesamten Kachelbestand und gehoert
 * deshalb in die Hintergrundrechnung, die ohnehin schon das GeoJSON dafuer
 * baut (siehe den zugehoerigen `LaunchedEffect` in [MapScreen]). Unter
 * Kantenlaenge 2 bleibt es unerwaehnt — ein „Größtes Quadrat 1×1" hat jeder,
 * der einmal um den Block gefahren ist, und waere keine Auskunft.
 *
 * Form und Farben wie beim [RezentrierenChip] am unteren Rand, nur in der
 * ruhigen Flaechenfarbe statt in der Akzentfarbe: Die Pille meldet einen
 * Stand, sie will nicht angetippt werden.
 */
@Composable
private fun ExplorerTilesPill(
    tileCount: Int,
    square: ExplorerSquare?,
    modifier: Modifier = Modifier,
) {
    val text = buildString {
        append(tileCount)
        append(" Kacheln")
        if (square != null && square.size >= 2) {
            append(" · Größtes Quadrat ")
            append(square.size)
            append("×")
            append(square.size)
        }
    }
    Surface(
        modifier = modifier
            // Rueckt an der MapLibre-Attribution vorbei, die als Info-Knopf
            // in genau dieser Ecke der Karte sitzt (`attributionGravity` in
            // `MapViewHost.kt`, oben links mit 12 px Rand) und aus
            // rechtlichen Gruenden erreichbar bleiben muss. Die Pille steht
            // dadurch neben ihr statt auf ihr.
            .padding(start = ExplorerPillAttributionInset),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Das fertige GeoJSON der drei Kachel-Ebenen samt dem groessten Quadrat, das
 * dabei ohnehin ermittelt wurde.
 *
 * Ein eigener Typ statt vier lose Rueckgabewerte: Alle vier entstehen in
 * derselben Hintergrundrechnung und gehoeren zum selben Kachelstand — sie
 * duerfen nie aus zwei verschiedenen Laeufen stammen.
 */
private data class ExplorerTileGeoJson(
    val fog: String,
    val outline: String,
    val maxSquare: String,
    val square: ExplorerSquare?,
)

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

/**
 * Wie weit die Zaehler-Pille der Entdeckt-Kacheln vom linken Rand des oberen
 * Overlay-Stapels einrueckt (siehe [ExplorerTilesPill]).
 *
 * Der Platz gehoert dem Attributions-Knopf von MapLibre, der in genau dieser
 * Ecke der Karte sitzt (`attributionGravity = TOP or START` in
 * `MapViewHost.kt`). Er ist rechtlich Pflicht (OSM/CARTO/Esri) und muss
 * antippbar bleiben — der lange Kommentar dort beschreibt ausdruecklich, dass
 * diese Ecke in jedem Bildschirmzustand frei bleibt. Die Pille haelt sich
 * daran und stellt sich daneben.
 *
 * Grosszuegig gerechnet: Das Symbol misst rund 24 dp, sein Rand von 12 px
 * faellt je nach Displaydichte zwischen 3 und 12 dp aus, und die
 * Beruehrungsflaeche darf nicht am Rand der Pille kleben.
 */
private val ExplorerPillAttributionInset = 40.dp

/**
 * Anteil der Bildschirmhoehe, den die Trefferliste der Ortssuche im
 * Erkunden-Blatt hoechstens einnimmt.
 *
 * Knapp gehalten, weil waehrend der Suche die Tastatur im Bild steht und rund
 * ein Drittel des Bildschirms belegt. Was uebrig bleibt, teilt sich die Liste
 * ausserdem mit dem Suchfeld, dem Griff und den beiden schwebenden Knoepfen
 * (Aufzeichnen, Position), die im selben Stapel darueber sitzen.
 *
 * Das ist ein **Sicherheitsnetz**, keine Rechnung: Die Liste steht im Peek und
 * wird deshalb ohnehin gegen den wirklich vorhandenen Platz gemessen. Der Wert
 * begrenzt nur, wie viel sie sich davon nimmt, bevor sie zu scrollen anfaengt
 * — grob drei bis vier Trefferzeilen auf einem 800-dp-Geraet, bei
 * [MAX_SEARCH_RESULTS] von fuenf also der uebliche Fall ohne Scrollen.
 */
private const val SEARCH_RESULTS_MAX_HEIGHT_FACTOR = 0.3f

/**
 * Hoehe der beiden schwebenden Knoepfe ueber dem Blatt (Aufzeichnen, Position)
 * samt ihrer Abstaende — 56 + 12 + 56 + 12 dp.
 *
 * Nur noch **Startwert** fuer den allerersten Bildaufbau: Danach steht die
 * gemessene Hoehe des ganzen Kopfteils (Knoepfe plus die Karte, die je nach
 * Zustand darunter sitzt) zur Verfuegung — siehe `overlayHeaderPx` im Rumpf.
 */
private val OverlayFloatingButtonsHeight = 136.dp

/**
 * Was die drei Blaetter jeweils **vor** ihrem Koerper verbrauchen. Das
 * Blatt-Budget (`overlaySheetBudget` im Rumpf) minus diesen Wert ist der
 * Deckel, gegen den der Koerper gemessen wird — und damit die Hoehe, ab der er
 * zu scrollen anfaengt statt abgeschnitten zu werden.
 *
 * Warum von Hand gezaehlt und nicht gemessen: Der Peek liegt **innerhalb** des
 * Blatts, dessen Koerperhoehe von dieser Zahl abhaengt. Eine Messung waere ein
 * Kreis. Der Kopfteil ueber dem Blatt hat dieses Problem nicht und wird
 * deshalb gemessen.
 *
 * Rundenwahl: Griff (48), Titelzeile (48), die beiden Zielzeilen (~44),
 * „Übernehmen" (48) und die Raender (~20).
 */
private val GenerationPeekFixedHeight = 208.dp

// Fuer das Erkunden-Gesicht braucht es keine solche Zahl mehr: Sein Koerper
// ist seit dem Umbau auf drei Stufen nur noch die Aktionszeile — eine Zeile
// fester Hoehe ohne Scrollweg, die in jedes Budget passt (siehe
// `ExploreSheet.kt`). Der frueher hier stehende Wert (240 dp) beschrieb den
// Peek mit Suchfeld, Werkzeugreihe UND Touren-Zeile und deckelte die
// Tourenliste; beides gibt es nicht mehr.

/**
 * Planung: Griff (48) und die eine Statuszeile (`heightIn(min = 48.dp)`) —
 * siehe `PlanningSheet` in `PlanningPanel.kt`.
 */
private val PlanningPeekFixedHeight = 96.dp

/** Untergrenze, damit die Rechnung auf sehr flachen Fenstern nicht negativ wird. */
private val MinOverlaySheetBudget = 240.dp

/**
 * Untergrenze fuer den Koerper eines Blatts.
 *
 * Bleibt vom Budget weniger uebrig (sehr flaches Fenster, grosse Karte
 * darueber), ragt das Blatt lieber ein Stueck ueber den Stapel hinaus, als
 * einen Koerper zu zeigen, in dem nicht einmal eine Zeile steht.
 */
private val MinSheetBodyHeight = 120.dp

/**
 * Wie sich der Platz jenseits von [GenerationPeekFixedHeight] zwischen
 * Vorschlagsliste und Koerper aufteilt.
 *
 * Die Liste bekommt den groesseren Anteil: Sie ist die Entscheidung. Der
 * Koerper traegt Zusatzwissen (Hoehenprofil, Zweitaktionen) und darf dafuer
 * scrollen — bei drei Vorschlaegen, dem Regelfall, passt die Liste ohnehin
 * ohne Scrollen hinein.
 */
private const val GENERATION_CANDIDATES_SHARE = 0.62f
private const val GENERATION_BODY_SHARE = 0.38f

/** Beschriftung der Navigation entlang der geplanten Route. */
private const val PLANNED_ROUTE_LABEL = "Geplante Route"

/**
 * Restdistanz, ab der die Zielansage „Ziel erreicht" faellt (30 m — etwa die
 * Off-Route-Austrittsschwelle des `RouteNavigator`, naeher projiziert GPS
 * ohnehin nicht zuverlaessig). Dieselbe Distanz dient als Mindest-Fortschritt,
 * damit eine Runde nicht schon am Start ihr eigenes Ziel meldet.
 */
private const val ZIEL_ERREICHT_KM = 0.03

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
 * Kennzeichnet, wofuer eine Route berechnet wurde: Profil, Streckenart und
 * Wegpunkte.
 *
 * Fuenf Nachkommastellen sind rund ein Meter — genauer setzt niemand einen
 * Wegpunkt, und die Zeichenkette bleibt kurz genug fuer das Bundle.
 *
 * [roundTrip] gehoert mit in den Schluessel, obwohl er die Wegpunkte nicht
 * aendert: Beim Umschalten von „Einfach" auf „Rundweg" bleibt die Liste gleich,
 * die zu rechnende Route aber nicht — ohne dieses Zeichen haette der
 * Planungs-Effekt die alte Route als „passt schon" durchgewunken.
 */
private fun planningInputsKey(
    waypoints: List<Waypoint>,
    profile: RouteProfile,
    roundTrip: Boolean,
): String =
    waypoints.joinToString(
        separator = ";",
        prefix = "${profile.name}|${if (roundTrip) "R" else "E"}|",
    ) { waypoint ->
        String.format(Locale.ROOT, "%.5f,%.5f", waypoint.lat, waypoint.lon)
    }

private const val MIN_SEARCH_LENGTH = 3
private const val SEARCH_DEBOUNCE_MS = 450L

/** Zoomstufe des einmaligen automatischen Erst-Zooms auf die Position. */
private const val AUTO_LOCATION_ZOOM = 13.0

/**
 * Mindestschritt (Meter) zwischen zwei Fixes, damit ihre Verbindungslinie
 * als Fahrkurs zaehlt — darunter dominiert das GPS-Zittern die Richtung.
 * Zusaetzlich zur Tempo-Schwelle in `daempfeKurs` (`:core`), die das
 * Einfrieren im Stand regelt: Diese hier filtert schon den Rohwert.
 */
private const val NAV_KURS_MIN_SCHRITT_M = 2.0

/**
 * Toleranz (Grad), innerhalb derer die Kamera noch als „am Deutschland-
 * Default" gilt — 0.0 waere zu knapp: MapLibre rundet die Kamera beim
 * Wiederherstellen aus `rememberSaveable` nicht immer bitgenau.
 */
private const val DEFAULT_CAMERA_POSITION_EPSILON = 0.01

/** Toleranz der Zoomstufe fuer denselben Vergleich. */
private const val DEFAULT_CAMERA_ZOOM_EPSILON = 0.05
