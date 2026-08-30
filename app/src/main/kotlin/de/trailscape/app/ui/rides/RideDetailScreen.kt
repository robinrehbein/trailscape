package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.components.OneUiLargeTopAppBar
import de.trailscape.app.ui.components.oneUiTopAppBarScrollBehavior
import de.trailscape.app.ui.components.screenContentPadding
import de.trailscape.app.ui.formatDateTime
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.app.ui.map.ElevationProfile
import de.trailscape.app.ui.map.ElevationSample
import de.trailscape.app.ui.map.GravelGreen
import de.trailscape.app.ui.map.MapController
import de.trailscape.app.ui.map.MapMarker
import de.trailscape.app.ui.map.MapPadding
import de.trailscape.app.ui.map.MapViewHost
import de.trailscape.app.ui.map.RecordRed
import de.trailscape.app.ui.map.buildElevationSamples
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.Confidence
import de.trailscape.core.DecouplingResult
import de.trailscape.core.Ride
import de.trailscape.core.RideCurve
import de.trailscape.core.RideLoad
import de.trailscape.core.SegmentEffortView
import de.trailscape.core.TrainingProfile
import de.trailscape.core.Vo2MaxEstimate
import de.trailscape.core.buildRideSeries
import de.trailscape.core.computeDecoupling
import de.trailscape.core.computePhysicsEstimate
import de.trailscape.core.confidenceLabels
import de.trailscape.core.estimateVo2MaxFromSegments
import de.trailscape.core.extractSteadySegments
import de.trailscape.core.formatDuration
import de.trailscape.core.heartRateCurve
import de.trailscape.core.loadSourceLabels
import de.trailscape.core.segmentEffortsForRide
import de.trailscape.core.speedCurveKmh
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # Detailansicht einer aufgezeichneten Tour
 *
 * Was die Tourenliste in einer Zeile zusammenfasst, steht hier ausgebreitet:
 * die gefahrene Spur auf der Karte, alle Kennzahlen aus [de.trailscape.core.RideStats],
 * Hoehen-, Tempo- und Pulsverlauf und — falls die Datenlage es hergibt — die
 * Auswertung aus `:core` (Entkopplung, VO2max). Erreichbar durch Antippen eines
 * Listeneintrags in `TourList.kt`.
 *
 * ## Kein eigener Navigationseintrag
 * Die Ansicht ist ein **Zustand des Touren-Tabs**, kein Ziel im `NavHost`:
 * `TourList` (RideDetailHost) merkt sich die angetippte Tour-ID in einem `rememberSaveable`
 * und zeigt statt der Liste diesen Screen; die Systemzurueckgeste faengt dort
 * ein `BackHandler` ab. Die Navigationsstruktur der App (`ui/TrailscapeApp.kt`)
 * bleibt dadurch unberuehrt — und die Liste behaelt ihren Scrollzustand, weil
 * sie beim Zurueckgehen nicht neu aufgebaut wird.
 *
 * ## Warum die Karte hier nicht bedienbar ist
 * MapLibre bringt eine eigene OpenGL-View mit eigener Gestenerkennung mit.
 * Liegt sie in einer scrollbaren Spalte, streiten sich beide um jeden
 * senkrechten Wisch: Entweder verschluckt die Karte das Scrollen der Seite oder
 * die Seite das Verschieben der Karte — je nachdem, wer zuerst zugreift.
 * Deshalb hat die Karte hier eine **feste Hoehe** und liegt unter einer
 * durchsichtigen Flaeche, die alle Beruehrungen abfaengt, ohne sie zu
 * verbrauchen: Die Karte bekommt nichts, die Seite scrollt normal weiter. Wer
 * die Tour wirklich erkunden will, kommt ueber „Auf der Karte öffnen" in den
 * Karten-Tab — dort ist sie ganzflaechig und vollstaendig bedienbar. Ein
 * verschachteltes Gesten-Ping-Pong waere die schlechtere Antwort auf dieselbe
 * Frage.
 *
 * ## Was hier NICHT gerechnet wird
 * Die Kennzahlen kommen unveraendert aus `ride.stats` (berechnet in
 * `:core`/`Stats.kt`), die Kurven aus `:core`/`RideCurves.kt`, das Hoehenprofil
 * aus `ui/map/ElevationProfile.kt` und die Auswertung aus `:core`/`RideAnalysis.kt`.
 * Diese Datei formatiert und zeichnet nur.
 *
 * ## Grenzfaelle
 * Abschnitte ohne Datengrundlage entfallen **ganz**, statt leer gezeichnet zu
 * werden: keine Hoehen (haeufig bei importierten GPX-Dateien) → kein
 * Hoehenprofil, keine Zeitstempel → keine Tempokurve, keine Herzfrequenz →
 * keine Pulskurve, zu kurze/zu ungleichmaessige Tour → keine Analyse. Eine Tour
 * aus zwei, drei Punkten zeigt am Ende nur Karte und Kennzahlen — und genau das
 * ist richtig.
 *
 * @param snackbarHostState bewusst von der Liste hereingereicht: Meldungen aus
 *   [AppViewModel.messages] sammelt der Karten-Screen fuer beide Ansichten ein, und
 *   die „Rückgängig"-Snackbar nach dem Loeschen soll dieselbe bleiben.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideDetailScreen(
    ride: Ride,
    appViewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val mapStyle by appViewModel.mapStyle.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val load = insights.rideLoads[ride.id]

    // Segment-Bestleistungen dieser Tour. Der Aufruf stoesst zugleich den
    // lazy Erstlauf der Registry an (siehe [AppViewModel.refreshSegments]) —
    // die Detailansicht ist ihr „erster Bedarf".
    val segmentRegistry by appViewModel.segmentRegistry.collectAsStateWithLifecycle()
    LaunchedEffect(ride.id) { appViewModel.refreshSegments() }
    val segmentViews = remember(segmentRegistry, ride.id) {
        segmentRegistry?.let { segmentEffortsForRide(it, ride.id) }.orEmpty()
    }

    var menuOpen by remember { mutableStateOf(false) }

    val curves by rememberRideCurves(ride)
    val analysis by rememberRideAnalysis(ride, insights.profile, insights.eftp.watts)

    // Zweite Ebene: Der Leitfaden laesst die Kopfzeile hier **eingeklappt**
    // starten, aber ausklappbar bleiben. Wer eine Tour geoeffnet hat, will die
    // Tour sehen — nicht noch einmal deren Namen in Grossschrift. Vorher stand
    // hier eine feste `TopAppBar`, die sich gar nicht oeffnen liess.
    val scrollBehavior = oneUiTopAppBarScrollBehavior(initiallyCollapsed = true)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Die aeussere Huelle (TrailscapeApp) hat die System-Insets bereits
        // aufgeloest — genau wie in der Tourenliste duerfen sie hier kein
        // zweites Mal aufschlagen.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            OneUiLargeTopAppBar(
                title = ride.name,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück zur Tourenliste",
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Aktionen")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Umbenennen") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Als GPX teilen") },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onShare()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Löschen") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    // Unten die Bodenfreiheit der schwebenden Navigationskapsel
                    // (siehe screenContentPadding), sonst endet die letzte
                    // Karte hinter ihr.
                    .padding(screenContentPadding()),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                // Titel + Datum/Typ-Zeile (Zieldesign
                // `docs/design/prototyp-eine-leiste.html`, Screen
                // „Tour-Detail", `#dName`/`#dDate`): Der Titel selbst liegt in
                // der auf-/einklappbaren [OneUiLargeTopAppBar] oben, dieser
                // Zeile obliegt nur das gedaempfte Darunter.
                Text(
                    text = rideDateTypeLine(ride),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (ride.points.isNotEmpty()) {
                    RideMapCard(
                        ride = ride,
                        style = mapStyle,
                        onOpenOnMap = {
                            appViewModel.select(ride.id)
                            appViewModel.requestShowRideOnMap(ride.id)
                        },
                    )
                }

                RideStatsRow(ride = ride)

                val elevation = curves?.elevation.orEmpty()
                if (elevation.size >= 2) {
                    DetailCard {
                        // Mono-Eyebrow „HÖHENPROFIL": bewusst nicht identisch
                        // mit dem Zieldesign-Mockup (dessen `.p-eyebrow` dort
                        // ohne `.mono` steht) — die Aufgabenstellung verlangt
                        // hier ausdruecklich die Monospace-Grossschrift-
                        // Variante, siehe [DetailEyebrow].
                        DetailEyebrow(
                            text = "Höhenprofil",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            mono = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        // Wiederverwendung statt Nachbau: dieselbe Darstellung
                        // wie auf dem Karten-Screen. Die Linienfarbe kommt hier
                        // aber aus dem Theme — das feste Kartengruen liegt dort
                        // auf Kacheln, auf der dunklen Kartenflaeche waere es
                        // kaum zu sehen.
                        ElevationProfile(
                            points = ride.points,
                            lineColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                RideAnalysisCard(
                    load = load,
                    decoupling = analysis?.decoupling,
                    vo2max = analysis?.vo2max,
                )

                // Ab hier alles Weitere, das die Zielstruktur nicht mehr
                // namentlich vorgibt, aber unveraendert erhalten bleibt: die
                // Planungs-Ausnahme, die restlichen Kennzahlen, Tempo- und
                // Pulskurve, Segmente.

                // Eine gespeicherte Planung sieht hier aus wie eine Tour, hat
                // aber weder Trainingslast noch Auswertung — ohne diesen Satz
                // waere das ein Fehler statt einer Auskunft (siehe `:core`:
                // `Ride.planned`). Die kurze Erwaehnung in der Datum/Typ-Zeile
                // oben ersetzt diesen Hinweis nicht: Dort steht nur, *was* die
                // Tour ist, hier *was das fuer die Auswertung bedeutet*.
                if (ride.planned) {
                    NoticeBox(
                        icon = Icons.Filled.Route,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = "Das ist eine gespeicherte Planung, keine gefahrene Tour. Sie " +
                            "zählt deshalb nicht für Wochenfortschritt, Fitness und Form.",
                    )
                }

                RideExtraFactsCard(ride = ride)

                curves?.speed?.let { speed ->
                    DetailCard {
                        RideCurveChart(
                            title = "Tempo",
                            curve = speed,
                            lineColor = LocalSignalColors.current.accentBlue,
                            formatValue = { "${formatOneDecimalDe(it)} km/h" },
                            filled = true,
                        )
                    }
                }

                curves?.heartRate?.let { heartRate ->
                    DetailCard {
                        RideCurveChart(
                            title = "Puls",
                            curve = heartRate,
                            lineColor = MaterialTheme.colorScheme.primary,
                            formatValue = { "${it.roundToInt()} bpm" },
                        )
                    }
                }

                if (segmentViews.isNotEmpty()) {
                    SegmentsCard(views = segmentViews)
                }

                // Der schwebende Knopf der Liste ist hier zwar weg, ein wenig
                // Luft unter der letzten Karte tut dem Daumen trotzdem gut.
                Spacer(Modifier.height(ScreenPadding))
            }
        }
    }
}

// ---------------------------------------------------------------- Karten

/**
 * Karte mit der gefahrenen Spur, auf die Ausdehnung der Tour eingepasst.
 *
 * Benutzt dieselbe Karteninfrastruktur wie der Karten-Tab
 * ([MapViewHost]/[MapController]) — inklusive der festen Kartenfarben aus
 * `ui/map/MapColors.kt` fuer Spur und Start-/Zielpunkt, damit eine Tour hier
 * genauso aussieht wie dort. Warum die Karte keine Gesten annimmt, steht im
 * KDoc von [RideDetailScreen].
 */
@Composable
private fun RideMapCard(
    ride: Ride,
    style: MapStyle,
    onOpenOnMap: () -> Unit,
) {
    val controller = remember(ride.id) { MapController() }

    LaunchedEffect(controller, ride.id, ride.points.size) {
        controller.setTrack(ride.points)
        controller.setMarkers(startAndFinishMarkers(ride))
        val points = ride.points
        if (points.size >= 2) {
            controller.fitToPoints(points, MapFitPadding)
        } else {
            // Eine Tour aus einem einzigen Punkt hat keine Ausdehnung, aus der
            // sich ein Ausschnitt bilden liesse — dann einfach hinzoomen.
            points.firstOrNull()?.let {
                controller.moveTo(it.lat, it.lon, minZoom = SinglePointZoom, animate = false)
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MapHeight)
                    .semantics {
                        contentDescription = "Karte mit der gefahrenen Spur von „${ride.name}“"
                    },
            ) {
                MapViewHost(
                    controller = controller,
                    style = style,
                    // Der eigene Standort gehoert auf die grosse Karte, nicht in
                    // die Rueckschau auf eine gefahrene Tour.
                    locationEnabled = false,
                    onMapTap = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    // Ohne das schluckt die Karte in dieser scrollbaren Seite
                    // jeden senkrechten Wisch. Wer die Tour wirklich erkunden
                    // will, nimmt „Auf der Karte öffnen" darunter.
                    gesturesEnabled = false,
                )
            }

            NeutralButton(
                onClick = onOpenOnMap,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(CardPadding),
            ) {
                Text("Auf der Karte öffnen")
            }
        }
    }
}

/**
 * Datum/Typ-Zeile direkt unter dem Titel — Zieldesign
 * `docs/design/prototyp-eine-leiste.html`, Screen „Tour-Detail" (`#dDate`):
 * Datum und Uhrzeit, ergaenzt um die Herkunft, falls die Tour eine
 * gespeicherte Planung ist oder aus Health Connect stammt. Der Titel selbst
 * liegt weiterhin in der auf-/einklappbaren [OneUiLargeTopAppBar] — dieser
 * Zeile obliegt nur das Darunter.
 *
 * Ersetzt nicht die ausfuehrliche Planungs-[NoticeBox] weiter unten: Dort
 * steht, was die Kennzeichnung fuer die Auswertung bedeutet, hier nur, dass
 * sie zutrifft.
 */
private fun rideDateTypeLine(ride: Ride): String = buildList {
    add(formatDateTime(localOfEpochMs(ride.createdAt)))
    if (ride.planned) add("Geplante Route")
    if (ride.id.startsWith("hc-")) add("aus Health Connect")
}.joinToString(" · ")

/**
 * Die vierteilige Statistik-Zeile (Zieldesign
 * `docs/design/prototyp-eine-leiste.html`, Klasse `.statrow4`): Distanz,
 * Gesamtdauer, Anstieg und Ø Puls als grosse, zentrierte Zahlen mit
 * Tabellenziffern ([BigStat]) — bewusst nicht ueber das gemeinsame [Fact]
 * (Label-ueber-Wert, linksbuendig, keine Tabellenziffern), sondern nach dem
 * Muster von `CompactValue` in `ui/map/RideCompactBar.kt`, das im Fahrmodus
 * bereits fette Zahlen mit Tabellenziffern zeigt. Fehlt die Herzfrequenz
 * (haeufig bei importierten GPX-Dateien), steht „–" statt die vierte Spalte
 * ausfallen zu lassen — die Zeile bleibt so immer vierteilig, wie das
 * Zieldesign es zeigt.
 */
@Composable
private fun RideStatsRow(ride: Ride) {
    val stats = ride.stats

    DetailCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            BigStat(value = formatKmDe(stats.distanceKm), label = "km")
            BigStat(value = formatDuration(stats.durationS), label = "h:min")
            BigStat(value = "${stats.ascentM.roundToInt()}", label = "Hm")
            BigStat(
                value = stats.avgHrBpm?.toString() ?: "–",
                label = "Ø Puls",
            )
        }
    }
}

/**
 * Ein Wert der vierteiligen Statistik-Zeile: grosse, zentrierte Zahl in
 * Tabellenziffern ([fontFeatureSettings] `"tnum"`, Repo-Muster fuer
 * Zahlenreihen — siehe `ui/map/RideCompactBar.kt`), kleine Einheit darunter.
 */
@Composable
private fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Die restlichen Kennzahlen der Tour, die nicht in [RideStatsRow] stehen —
 * Fahrzeit (im Unterschied zur Gesamtdauer dort), Ø Tempo, Abstieg, Max-Puls
 * — plus die Health-Connect-Kennzeichnung. Unveraendert aus der fruesheren
 * `RideFactsCard`, nur um die vier Werte bereinigt, die jetzt gross oben
 * stehen (und um das Datum, das in die Datum/Typ-Zeile gewandert ist).
 */
@Composable
private fun RideExtraFactsCard(ride: Ride) {
    val stats = ride.stats

    DetailCard {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailFact("Fahrzeit", formatDuration(stats.movingTimeS))
            DetailFact(
                label = "Ø Tempo",
                value = stats.avgSpeedKmh?.let { "${formatOneDecimalDe(it)} km/h" } ?: "–",
            )
            DetailFact("Höhenmeter ↓", "${stats.descentM.roundToInt()} hm")
            stats.maxHrBpm?.let { DetailFact("Max. Puls", "$it bpm") }
        }
        if (ride.id.startsWith("hc-")) {
            Spacer(Modifier.height(12.dp))
            // „aus Health Connect", nicht „aus Samsung Health": Das `hc-`-
            // Praefix vergibt der Health-Connect-Import (`:core`,
            // HealthSyncLogic.kt) — unabhaengig davon, welche App die Daten
            // dort hineingeschrieben hat. Samsung Health ist nur eine von
            // vielen Quellen; wer eine Garmin traegt, hielt den Chip fuer einen
            // Fehler. Steht zusaetzlich zur kurzen Erwaehnung in der
            // Datum/Typ-Zeile, weil dort nur der Text steht — die Pille bleibt
            // die auffindbare Marke, wie ueberall sonst in der App.
            TagPill(text = "aus Health Connect")
        }
    }
}

/**
 * Auswertung der Tour, soweit `:core` sie fuer **diese** Fahrt tragen kann:
 * Trainingslast, Pe:Hr-Entkopplung und VO2max aus gleichmaessigen Abschnitten.
 *
 * Faellt alles drei aus (kurze Tour, keine Herzfrequenz, zu ungleichmaessig
 * gefahren), entfaellt die Karte ganz. Die Gruende dafuer stehen bewusst
 * **nicht** hier: Sie sind fuer die Nutzerin nicht handlungsleitend — sie kann
 * eine gefahrene Tour nicht nachtraeglich gleichmaessiger machen.
 *
 * Zieldesign `docs/design/prototyp-eine-leiste.html` (Klasse `.coach`): eine
 * Akzent-Container-Karte ([CoachCard], `primaryContainer`/`onPrimaryContainer`
 * statt der neutralen Kartenflaeche) mit der Eyebrow „Coach" statt des
 * frueheren Titels „Analyse" — dieselbe Kennzeichnung wie die Coach-Kacheln
 * auf „Heute" und „Training". Anders als beim Hoehenprofil bewusst **nicht**
 * die Monospace-Variante: Im Zieldesign traegt `.coach .eyebrow` keine
 * `.mono`-Klasse, „Coach" bleibt dort schlichte Groteskschrift.
 */
@Composable
private fun RideAnalysisCard(
    load: RideLoad?,
    decoupling: DecouplingResult?,
    vo2max: Vo2MaxEstimate?,
) {
    val usableLoad = load?.takeIf { it.available }
    if (usableLoad == null && decoupling == null && vo2max == null) {
        return
    }

    CoachCard {
        DetailEyebrow(
            text = "Coach",
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = EyebrowAlpha),
        )

        usableLoad?.let { entry ->
            Spacer(Modifier.height(12.dp))
            AnalysisEntry(
                label = "Trainingslast",
                value = "${entry.load.roundToInt()} " +
                    "(${loadSourceLabels[entry.source].orEmpty()})",
                explanation = entry.note,
                confidence = entry.confidence,
            )
        }

        decoupling?.let { result ->
            Spacer(Modifier.height(12.dp))
            AnalysisEntry(
                label = "Entkopplung (Pe:Hr)",
                value = "${formatOneDecimalDe(result.decouplingPercent ?: 0.0)} %" +
                    (result.rating?.let { " · $it" } ?: ""),
                explanation = "Vergleicht die zweite Tourhälfte mit der ersten: wie viel " +
                    "Leistung dein Puls am Ende noch trägt. Unter 5 % gilt die aerobe " +
                    "Ausdauer als gut, über 10 % lohnt sich mehr Grundlagenarbeit.",
                confidence = result.confidence,
            )
        }

        vo2max?.let { estimate ->
            Spacer(Modifier.height(12.dp))
            AnalysisEntry(
                label = "VO2max",
                value = estimate.text,
                explanation = "Aus den gleichmäßigen Abschnitten dieser Tour geschätzt " +
                    "(Herzfrequenz gegen geschätzte Leistung). Deshalb ein Band und " +
                    "kein Messwert.",
                confidence = estimate.confidence,
            )
        }
    }
}

/**
 * Die Segmente dieser Tour: je automatisch erkanntem Anstieg die Zeit dieser
 * Befahrung, die persoenliche Bestzeit, Anzahl der Befahrungen, der Platz und
 * der Rueckstand — alles aus der lokalen Registry (`:core`,
 * `RideSegments.kt`), nichts davon verlaesst das Geraet.
 *
 * Eine **neue** Bestzeit traegt eine Pille „★ Neue Bestzeit" in Signalfarbe;
 * fuhr die Tour denselben Anstieg mehrmals (Runden), erscheint je Runde ein
 * Eintrag. Ohne erkannte Segmente entfaellt die Karte ganz — dieselbe Regel
 * wie bei den Kurven: kein leerer Abschnitt mit Hinweistext.
 */
@Composable
private fun SegmentsCard(views: List<SegmentEffortView>) {
    DetailCard {
        Text(text = "Segmente", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Automatisch erkannte Anstiege, verglichen mit deinen " +
                "früheren Fahrten über dasselbe Stück.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        views.forEach { view ->
            Spacer(Modifier.height(16.dp))
            SegmentEffortEntry(view)
        }
    }
}

/** Ein Eintrag der Segmentkarte: Name, ggf. Bestzeit-Pille, Kennzahlenzeile. */
@Composable
private fun SegmentEffortEntry(view: SegmentEffortView) {
    Column {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = view.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (view.isNewBest) {
                // Bedeutung nicht allein ueber Farbe: der Stern und der Text
                // tragen sie auch in Graustufen (siehe Leitfaden-Kommentar an
                // [startAndFinishMarkers]).
                TagPill(
                    text = "★ Neue Bestzeit",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailFact("Zeit", formatDuration(view.timeS))
            DetailFact("Bestzeit", formatDuration(view.bestTimeS))
            DetailFact("Platz", "${view.rank}. von ${view.effortCount}")
            DetailFact(
                label = "Rückstand",
                value = if (view.deltaToBestS <= 0) "–" else "+${view.deltaToBestS} s",
            )
            view.avgHr?.let { DetailFact("Ø Puls", "$it bpm") }
        }
    }
}

// -------------------------------------------------------------- Bausteine

/** Eine Karte der Detailansicht — ueberall dasselbe Innenmass. */
@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding)) { content() }
    }
}

/**
 * Die Akzent-Container-Karte fuer die Coach-Einordnung ([RideAnalysisCard]) —
 * Zieldesign `docs/design/prototyp-eine-leiste.html`, Klasse `.coach`:
 * `primaryContainer`/`onPrimaryContainer` statt der neutralen Kartenflaeche
 * von [DetailCard], sonst dasselbe Innenmass. Die Vorlagen-Farben (One-UI
 * `--acc-cont`/`--on-acc-cont`) sind exakt `primaryContainer`/
 * `onPrimaryContainer` aus `theme/Color.kt`, deshalb genuegt hier
 * [CardDefaults.cardColors] statt eigener Farbwerte.
 */
@Composable
private fun CoachCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(CardPadding)) { content() }
    }
}

/** Eine Kennzahl der Detailansicht — dieselbe Grammatik wie ueberall ([Fact]). */
@Composable
private fun DetailFact(label: String, value: String) {
    Fact(label = label, value = value)
}

/**
 * Kleine Kennzeichnung ueber einem Abschnitt oder einer Karte — Zieldesign
 * `docs/design/prototyp-eine-leiste.html`, Klasse `.eyebrow`: 12 sp/600 mit
 * leichter Spreizung, gedaempfte Farbe.
 *
 * [mono] schaltet auf die Monospace-Grossschrift-Variante um (`.eyebrow.mono`
 * im Zieldesign, siehe der Trainings-Screen „FORM · 90 TAGE") — hier fuer den
 * Hoehenprofil-Abschnitt gebraucht ([RideDetailScreen]), damit er sich von
 * der schlichten „Coach"-Kennzeichnung absetzt.
 *
 * Rein privat in dieser Datei: Ein gleichnamiger, geteilter Baustein unter
 * `ui/components/` existierte zum Zeitpunkt dieser Umstellung noch nicht.
 * Parallele Arbeit an `ui/today`/`ui/training` koennte einen anlegen — eine
 * lokale Kopie hier ist die sicherere Wahl als ein moeglicher Namenskonflikt.
 */
@Composable
private fun DetailEyebrow(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    Text(
        text = if (mono) text.uppercase() else text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            letterSpacing = if (mono) 1.2.sp else 0.4.sp,
        ),
        color = color,
        modifier = modifier,
    )
}

/** Deckkraft gedaempfter Eyebrow-/Nebentexte auf der Coach-Akzentkarte ([CoachCard]). */
private const val EyebrowAlpha = 0.75f

/**
 * Ein Eintrag der Analyse: die Zahl gross und fett im One-UI-Mass
 * (headlineSmall auf labelMedium), darunter kurze Erklaerung und wie
 * belastbar er ist.
 *
 * Liegt seit der Umstellung auf [CoachCard] auf akzentfarbenem Grund statt
 * der neutralen Kartenflaeche: Erklaerung und Verlaesslichkeit laufen deshalb
 * ueber `onPrimaryContainer` (gedaempft, [EyebrowAlpha]) statt ueber das feste
 * `onSurfaceVariant`. Das Label aus [Fact] bleibt dabei unveraendert bei
 * `onSurfaceVariant` — [Fact] ist ein geteilter Baustein unter
 * `ui/components/` und liegt ausserhalb dessen, was diese Umstellung anfassen
 * darf; der Kontrast auf der hellgruenen Flaeche bleibt ausreichend, auch
 * wenn er nicht exakt denselben Farbton traegt wie der Rest der Karte.
 */
@Composable
private fun AnalysisEntry(
    label: String,
    value: String,
    explanation: String,
    confidence: Confidence,
) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Column {
        Fact(label = label, value = value)
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = onContainer.copy(alpha = EyebrowAlpha),
        )
        if (confidence != Confidence.NONE) {
            Text(
                text = "Verlässlichkeit: ${confidenceLabels[confidence].orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = onContainer.copy(alpha = EyebrowAlpha),
            )
        }
    }
}

// ------------------------------------------------------------ Aufbereitung

/** Die fertig aufbereiteten Verlaeufe einer Tour. */
private data class RideCurves(
    val elevation: List<ElevationSample>,
    val speed: RideCurve?,
    val heartRate: RideCurve?,
)

/**
 * Bereitet Hoehen-, Tempo- und Pulsverlauf auf — auf [Dispatchers.Default],
 * nicht im Kompositionsdurchlauf: Eine Tagestour hat schnell zehntausende
 * Punkte, und jede der drei Rechnungen laeuft einmal ueber die ganze Liste.
 *
 * Die Hoehen-Stuetzstellen werden hier nur gebaut, um zu **entscheiden**, ob
 * ein Hoehenprofil ueberhaupt Daten haette; gezeichnet wird es aus den Punkten
 * (siehe `ui/map/ElevationProfile.kt`, das seine Stuetzstellen selbst merkt).
 * Das ist ein zusaetzlicher Durchlauf und trotzdem der bessere Handel: Die
 * Alternative waere ein leer gezeichnetes Diagramm mit Hinweistext.
 */
@Composable
private fun rememberRideCurves(ride: Ride): State<RideCurves?> = produceState<RideCurves?>(
    initialValue = null,
    ride.id,
    ride.points.size,
    ride.stats.avgHrBpm,
) {
    value = withContext(Dispatchers.Default) {
        RideCurves(
            elevation = buildElevationSamples(ride.points),
            speed = speedCurveKmh(ride.points),
            heartRate = heartRateCurve(ride.points),
        )
    }
}

/** Was `:core` an Auswertung fuer genau diese Tour hergibt. */
private data class RideAnalysis(
    val decoupling: DecouplingResult?,
    val vo2max: Vo2MaxEstimate?,
)

/**
 * Rechnet Entkopplung und VO2max fuer diese eine Tour.
 *
 * Die Leistungsreihe wird hier aus der (ohnehin fuer diese Ansicht geladenen)
 * Volltour frisch gebaut: Die Trainingsauswertung haelt seit der Umstellung
 * auf Zusammenfassungen keine Leistungsreihen mehr im Speicher — ihre
 * [RideLoad]-Objekte tragen nur noch Kennzahlen (siehe `:core`,
 * `RideLoadFacts.kt`). Der eine Aufbau fuer die eine offene Tour ist billig
 * und laeuft auf `Dispatchers.Default`. Ohne Leistungsreihe (zu wenige
 * Punkte, keine Zeitstempel, kein Hoehenprofil) gibt es nichts zu rechnen;
 * nicht berechenbare Ergebnisse werden zu `null` und der Abschnitt entfaellt.
 *
 * @param eftpW die FTP der aktuellen Lastskala (`insights.eftp.watts`), damit
 *   die hier gezeigte Analyse zur selben Skala gehoert wie die Trainingslast.
 */
@Composable
private fun rememberRideAnalysis(
    ride: Ride,
    profile: TrainingProfile,
    eftpW: Double,
): State<RideAnalysis?> = produceState<RideAnalysis?>(
    initialValue = null,
    ride.id,
    ride.points.size,
    profile,
    eftpW,
) {
    value = withContext(Dispatchers.Default) {
        val estimate = computePhysicsEstimate(
            buildRideSeries(ride.points, profile),
            profile,
            eftpW = eftpW,
        )
        if (!estimate.available) {
            RideAnalysis(decoupling = null, vo2max = null)
        } else {
            val decoupling = computeDecoupling(estimate, profile)
            val vo2max = estimateVo2MaxFromSegments(
                extractSteadySegments(estimate.series, profile),
                profile,
            )
            RideAnalysis(
                decoupling = decoupling.takeIf { it.available },
                vo2max = vo2max.takeIf { it.available },
            )
        }
    }
}

/**
 * Start- und Zielpunkt — dieselben Marker wie auf dem Karten-Screen.
 *
 * Start ist ein einfacher Punkt, **Ziel eine Zielscheibe**: derselbe Punkt,
 * umschlossen von einem Ring. Vorher unterschieden sich beide ausschliesslich
 * durch die Farbe (gruen gegen rot) bei gleicher Form und gleicher Groesse —
 * der Lehrbuchfall dessen, was der Leitfaden verbietet: Bedeutung allein ueber
 * Farbe. Wer rot und gruen nicht auseinanderhaelt — und das sind rund acht
 * Prozent der Maenner —, sah zwei identische Punkte und wusste nicht, wo die
 * Tour begann.
 *
 * Die Probe des Leitfadens ist, den Bildschirm in Graustufen zu denken. Punkt
 * gegen Punkt-im-Ring haelt ihr stand; die Farbe bleibt daneben als zweites,
 * schnelleres Signal bestehen.
 */
private fun startAndFinishMarkers(ride: Ride): List<MapMarker> {
    if (ride.points.size < 2) {
        return emptyList()
    }
    val first = ride.points.first()
    val last = ride.points.last()
    return buildList {
        add(MapMarker(first.lat, first.lon, GravelGreen.toArgb(), radius = 7f))
        addAll(finishMarkers(last.lat, last.lon))
    }
}

/**
 * Der Zielpunkt als Zielscheibe: ein gefuellter Kern und ein Ring darum.
 *
 * Zwei Eintraege derselben [MapMarker]-Pipeline statt eines neuen Symbol-Layers
 * — die vorhandene `CircleLayer`-Kette kann Radius und Ringform bereits (siehe
 * `MapMarker.filled`), es braucht also weder ein Bild-Asset noch eine zweite
 * Ebene.
 */
internal fun finishMarkers(lat: Double, lon: Double): List<MapMarker> = listOf(
    MapMarker(lat, lon, RecordRed.toArgb(), radius = 4f),
    MapMarker(lat, lon, RecordRed.toArgb(), radius = 9f, filled = false),
)

/**
 * Hoehe der eingebetteten Karte. Fest, weil sie in einer scrollbaren Spalte
 * liegt (siehe KDoc von [RideDetailScreen]); hoch genug, damit auch eine
 * langgezogene Tour als Form erkennbar bleibt.
 */
private val MapHeight = 240.dp

/**
 * Rand der Kamerafahrt in Pixeln. Deutlich schmaler als der Standardwert des
 * Karten-Screens: Der ist fuer eine bildschirmfuellende Karte mit Panels davor
 * gedacht und wuerde bei 240 dp Hoehe fast die ganze Flaeche wegnehmen.
 */
private val MapFitPadding = MapPadding(left = 32, top = 32, right = 32, bottom = 32)

/** Zoomstufe fuer eine Tour, die nur aus einem einzigen Punkt besteht. */
private const val SinglePointZoom = 14.0
