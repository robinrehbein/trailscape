package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalAppLanguage
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.components.ActionTileRow
import de.trailscape.app.ui.components.CoachCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.ScreenHeader
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.components.TileAction
import de.trailscape.app.ui.components.screenContentPadding
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
import de.trailscape.app.ui.rememberTodayDecision
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
import de.trailscape.core.computeRideImpact
import de.trailscape.core.estimateVo2MaxFromSegments
import de.trailscape.core.sensorMittelwerte
import de.trailscape.core.extractSteadySegments
import de.trailscape.core.formatDuration
import de.trailscape.core.heartRateCurve
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.formatTime
import de.trailscape.core.i18n.formatWeekdayDate
import de.trailscape.core.i18n.formatWeekdayDateYear
import de.trailscape.core.segmentEffortsForRide
import de.trailscape.core.speedCurveKmh
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * # Detailansicht einer Tour — was hat die Fahrt gebracht?
 *
 * Zieldesign `docs/design/prototyp-klartext.html`, Screen `#s-tour`, Menue
 * `m-tourmenu` und `NOTES.tour`. Vorher standen hier zehn Abschnitte
 * untereinander, vom Coach mit „Entkopplung Pe:Hr" bis zu den Segmenten. Jetzt
 * zuerst nur, was man nach einer Fahrt wissen will — und alles andere einen
 * Tipp tiefer, aber vollstaendig:
 *
 *  1. Kopf: „‹ Verlauf". Kein ⋮ mehr — siehe Punkt 5.
 *  2. Name gross, darunter gedaempft Wochentag, Datum, Uhrzeit — und die
 *     Herkunft („aus Health Connect", „geplante Route") genau einmal.
 *  3. Karte mit der Spur, darunter km · Std. · Hm · Ø Puls.
 *  4. Ein Satz in Klartext ([rideNote]): passt die Tour zum Plan, oder wie
 *     hart war sie?
 *  4b. Was die Tour gebracht hat ([RideImpactCard]) — Fitness/Frische,
 *     Wochenziel, neue Kacheln, neue Bestzeiten; entfaellt ganz ohne Daten.
 *  5. Die eine Hauptaktion **„Diese Tour nochmal fahren"** — die Spur als
 *     Route auf der Karte ([AppViewModel.requestRideAsRoute]) — und darunter
 *     sichtbar und beschriftet: Karte zeigen, Umbenennen, Teilen, Loeschen.
 *     Diese vier lagen frueher hinter ⋮; eine App ohne versteckte Funktionen
 *     kann sich kein Menue leisten, dessen Inhalt man erraten muss.
 *     „Teilen" fragt nach dem Was: Tour-Bild (Story oder Quadrat) oder GPX
 *     (`ShareRideDialog.kt`) — eine fuenfte Kachel haette die Reihe umbrochen.
 *     Darunter optional eine eigene Zeile fuer Strava ([StravaRideAction]),
 *     nur wenn ein Strava-Konto verbunden ist.
 *  6. Hoehenprofil.
 *  7. „Alle Werte" klappt den Rest auf: Fahrzeit, Ø Tempo, Hm ↓, Max. Puls,
 *     Tempo- und Pulskurve, die Coach-Auswertung (Trainingslast als Zahl,
 *     Entkopplung, VO₂max samt Verlaesslichkeit) und die Segmente.
 *
 * „Auf der Karte öffnen" als eigener Knopf unter der Karte ist entfallen: Es
 * gab dafuer zwei Woerter an zwei Stellen („zeigen" im Listenmenue, „öffnen"
 * hier). Jetzt gibt es eines: die Kachel „Karte zeigen".
 *
 * ## Warum die Karte hier nicht bedienbar ist
 * MapLibre bringt eine eigene Gestenerkennung mit; in einer scrollbaren
 * Spalte stritten sich beide um jeden senkrechten Wisch. Die Karte hat deshalb
 * eine feste Hoehe und nimmt keine Gesten an — wer die Tour erkunden will,
 * nimmt „Auf der Karte zeigen".
 *
 * ## Was hier NICHT gerechnet wird
 * Kennzahlen aus `ride.stats` (`:core`/`Stats.kt`), Kurven aus
 * `:core`/`RideCurves.kt`, Hoehenprofil aus `ui/map/ElevationProfile.kt`,
 * Auswertung aus `:core`/`RideAnalysis.kt`, Planzuordnung aus
 * `:core`/`TrainingPlanProgress.kt`, Wirkung aus `:core`/`RideImpact.kt`.
 * Diese Datei formatiert und zeichnet nur.
 *
 * ## Grenzfaelle
 * Abschnitte ohne Datengrundlage entfallen **ganz**: keine Hoehen → kein
 * Hoehenprofil, keine Zeitstempel → keine Tempokurve, keine Herzfrequenz →
 * keine Pulskurve, keine Last → kein Satz. Eine Tour aus zwei, drei Punkten
 * zeigt am Ende nur Karte und Kennzahlen — und genau das ist richtig.
 *
 * @param snackbarHostState vom [RideDetailHost] gehalten, der auch die
 *   Meldungen einsammelt.
 */
@Composable
internal fun RideDetailScreen(
    ride: Ride,
    appViewModel: AppViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onShowOnMap: () -> Unit,
    onRideAgain: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val mapStyle by appViewModel.mapStyle.collectAsStateWithLifecycle()
    val insights by appViewModel.insights.collectAsStateWithLifecycle()
    val plan by appViewModel.plan.collectAsStateWithLifecycle()
    val rides by appViewModel.rides.collectAsStateWithLifecycle()
    val load = insights.rideLoads[ride.id]

    // Segment-Bestleistungen dieser Tour. Der Aufruf stoesst zugleich den
    // lazy Erstlauf der Registry an (siehe [AppViewModel.refreshSegments]) —
    // die Detailansicht ist ihr „erster Bedarf".
    val segmentRegistry by appViewModel.segmentRegistry.collectAsStateWithLifecycle()
    LaunchedEffect(ride.id) { appViewModel.refreshSegments() }
    val segmentViews = remember(segmentRegistry, ride.id) {
        segmentRegistry?.let { segmentEffortsForRide(it, ride.id) }.orEmpty()
    }

    val curves by rememberRideCurves(ride)
    val analysis by rememberRideAnalysis(ride, insights.profile, insights.eftp.watts)

    // Dieselbe Zuordnung wie die Haken der Planwoche im Trainings-Tab.
    val planMatch = remember(plan, rides, insights.rideLoads, ride.id) {
        planMatchForRide(
            plan = plan,
            rides = rides,
            rideId = ride.id,
            rideLoads = insights.rideLoads.mapValues { it.value.load },
            now = System.currentTimeMillis(),
        )
    }
    val effort = rideEffort(load, ride.stats)
    val note = rideNote(effort, planMatch, analysis?.decoupling?.decouplingPercent)

    // Was die Tour gebracht hat — Wochenziel aus demselben Anzeigeplan wie auf
    // „Heute"; die Kachelzahl kommt nach, sobald der Kachelbestand steht.
    val decision = rememberTodayDecision(appViewModel)
    val explorerTiles by appViewModel.explorerTiles.collectAsStateWithLifecycle()
    val newTiles by produceState<Int?>(null, ride.id, ride.updatedAt, explorerTiles) {
        value = appViewModel.explorerTilesGainedBy(ride.id)
    }
    val impact = remember(
        ride.id, ride.updatedAt, load, insights.fitness, decision.displayPlan, rides, newTiles, segmentViews,
    ) {
        computeRideImpact(
            ride = ride,
            rideAt = localOfEpochMs(ride.createdAt),
            rideLoad = load?.takeIf { it.available }?.load,
            fitness = insights.fitness,
            plan = decision.displayPlan,
            rides = rides,
            newTiles = newTiles,
            segmentViews = segmentViews,
        )
    }

    // Aufgeklappt bleibt aufgeklappt, auch ueber eine Drehung hinweg.
    var allValues by rememberSaveable(ride.id) { mutableStateOf(false) }

    Scaffold(
        // Die Insets hat der Wirt (Dialogfenster in `RidesScreen.kt`) oben und
        // seitlich bereits aufgeloest.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                    .padding(screenContentPadding()),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                // Dieselbe Kopfzeile wie ueberall (Fuehrung „Klartext"): das
                // Datum als ruhige Zeile ueber dem Titel, wie auf „Heute".
                ScreenHeader(
                    title = ride.name,
                    overline = rideDetailDateLine(
                        at = localOfEpochMs(ride.createdAt),
                        today = LocalDate.now(),
                        planned = ride.planned,
                        fromHealthConnect = ride.id.startsWith("hc-"),
                        language = LocalAppLanguage.current,
                    ).asString(),
                    backLabel = stringResource(R.string.rides_detail_back_label),
                    onBack = onBack,
                )

                if (ride.points.isNotEmpty()) {
                    RideMapCard(ride = ride, style = mapStyle)
                }

                RideStatsRow(ride = ride)

                // Eine gespeicherte Planung hat weder Last noch Plantreffer —
                // statt eines Satzes zur Haerte steht hier, was das fuer die
                // Auswertung bedeutet (siehe `:core`: `Ride.planned`).
                if (ride.planned) {
                    NoticeBox(
                        icon = Icons.Filled.Route,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = stringResource(R.string.rides_detail_planned_notice),
                    )
                } else {
                    note?.let { RideNoteBox(it) }
                    impact?.let { RideImpactCard(rideImpactLines(it, LocalCoreTexts.current)) }
                }

                if (ride.points.size >= 2) {
                    Button(
                        onClick = onRideAgain,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.rides_detail_ride_again_action))
                    }
                }

                // Die vier Handgriffe, die frueher hinter ⋮ lagen — jetzt
                // sichtbar und beschriftet, direkt unter der Hauptaktion und in
                // derselben Ordnung wie auf dem Karten-Tourblatt
                // (`ui/map/MapPanels.kt`, [de.trailscape.app.ui.map.RideCard]):
                // gefuellt die eine Hauptaktion, darunter neutrale Kacheln,
                // Loeschen mit rotem Wort und ganz aussen. Loeschen fragt nicht
                // nach, sondern bietet im Verlauf „Rückgängig" an (siehe
                // [deleteRideWithUndo]). Die Bildschirmlesehilfe hoert bei
                // Teilen und Loeschen das Objekt mit — ein nacktes „Löschen"
                // sagt nicht, *was* geloescht wird.
                ActionTileRow(
                    actions = listOf(
                        TileAction(
                            stringResource(R.string.rides_detail_show_map_action),
                            Icons.Filled.Map,
                            onClick = onShowOnMap,
                        ),
                        TileAction(
                            stringResource(R.string.rides_detail_rename_action),
                            Icons.Filled.Edit,
                            onClick = onRename,
                        ),
                        TileAction(
                            stringResource(R.string.common_action_share),
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.rides_detail_share_cd),
                            onClick = onShare,
                        ),
                        TileAction(
                            stringResource(R.string.common_action_delete),
                            Icons.Filled.Delete,
                            destructive = true,
                            contentDescription = stringResource(R.string.rides_detail_delete_cd),
                            onClick = onDelete,
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Optional darunter: Strava (nur mit verbundenem Konto, siehe
                // StravaRideAction.kt).
                StravaRideAction(ride = ride)

                val elevation = curves?.elevation.orEmpty()
                if (elevation.size >= 2) {
                    // Ohne eigene Abschnittsueberschrift: Das Hoehenprofil
                    // traegt seinen Titel selbst, eine zweite „Höhenprofil"-
                    // Zeile darueber war doppelt.
                    run {
                        DetailCard {
                            // Wiederverwendung statt Nachbau: dieselbe
                            // Darstellung wie auf dem Karten-Screen, die
                            // Linienfarbe aus dem Theme.
                            ElevationProfile(
                                points = ride.points,
                                lineColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                NeutralButton(
                    onClick = { allValues = !allValues },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (allValues) {
                                R.string.rides_detail_fewer_values_action
                            } else {
                                R.string.rides_detail_all_values_action
                            },
                        ),
                    )
                }

                if (allValues) {
                    AllValues(
                        ride = ride,
                        load = load,
                        curves = curves,
                        analysis = analysis,
                        segmentViews = segmentViews,
                    )
                }

                // Ein wenig Luft unter dem letzten Element tut dem Daumen gut.
                Spacer(Modifier.height(ScreenPadding))
            }
        }
    }
}

/**
 * Die gedaempfte Zeile unter dem Namen: `Dienstag, 23. September · 18:12`
 * (englisch `Tuesday 23 September · 18:12`), aus frueheren Jahren mit
 * Jahreszahl, und die Herkunft genau **einmal** — frueher stand „aus Health
 * Connect" hier *und* als Pille weiter unten. Datumsmuster aus `:core`
 * (`DateFormats.kt`); je Herkunft eine ganze Zeile als Ressource.
 *
 * „aus Health Connect", nicht „aus Samsung Health": Das `hc-`-Praefix vergibt
 * der Health-Connect-Import (`:core`, HealthSyncLogic.kt), unabhaengig davon,
 * welche App die Daten dort hineingeschrieben hat.
 */
internal fun rideDetailDateLine(
    at: LocalDateTime,
    today: LocalDate,
    planned: Boolean,
    fromHealthConnect: Boolean,
    language: AppLanguage,
): UiText {
    val date = if (at.year == today.year) formatWeekdayDate(at, language) else formatWeekdayDateYear(at, language)
    val time = formatTime(at, language)
    val id = when {
        planned && fromHealthConnect -> R.string.rides_detail_date_line_planned_health_connect
        planned -> R.string.rides_detail_date_line_planned
        fromHealthConnect -> R.string.rides_detail_date_line_health_connect
        else -> R.string.rides_detail_date_line
    }
    return UiText.Res(id, listOf(date, time))
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
private fun RideMapCard(ride: Ride, style: MapStyle) {
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

    val description = stringResource(R.string.rides_detail_map_cd, ride.name)
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MapHeight)
                .semantics {
                    contentDescription = description
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
                gesturesEnabled = false,
            )
        }
    }
}

/**
 * Die vierteilige Zahlenzeile (Zieldesign `.stats3` mit vier Spalten):
 * Distanz, Dauer, Anstieg und Ø Puls als grosse, zentrierte Zahlen mit
 * Tabellenziffern. Die Dauer steht als `h:mm` unter „Std." — vorher stand
 * unter „h:min" ein Wert in `h:mm:ss`. Fehlt die Herzfrequenz, steht „–",
 * damit die Zeile immer vierteilig bleibt.
 */
@Composable
private fun RideStatsRow(ride: Ride) {
    val stats = ride.stats
    val formats = LocalAppFormats.current

    DetailCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            BigStat(
                value = formats.km(stats.distanceKm),
                label = stringResource(R.string.rides_detail_stat_km_label),
            )
            BigStat(
                value = formatHoursMinutes(stats.durationS),
                label = stringResource(R.string.rides_detail_stat_hours_label),
            )
            BigStat(
                value = "${stats.ascentM.roundToInt()}",
                label = stringResource(R.string.rides_detail_stat_elevation_label),
            )
            BigStat(
                value = stats.avgHrBpm?.toString() ?: "–",
                label = stringResource(R.string.rides_detail_stat_avg_hr_label),
            )
        }
    }
}

/**
 * Ein Wert der Zahlenzeile: grosse, zentrierte Zahl in Tabellenziffern
 * (`"tnum"`, Repo-Muster fuer Zahlenreihen — siehe `ui/map/RideCompactBar.kt`),
 * kleine Einheit darunter.
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
 * Der Klartext-Satz (Zieldesign `.note`): Akzent-Toenung
 * (`primaryContainer`/`onPrimaryContainer`), fetter Anfang, dann der Rest.
 * Bewusst keine [CoachCard]: Sie traegt eine Absender-Augenbraue, dieser Satz
 * braucht keinen Absender.
 */
@Composable
private fun RideNoteBox(note: RideNote) {
    val headline = note.headline.asString()
    val body = note.body.asString()
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(headline) }
                append(" ")
                append(body)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

// ---------------------------------------------------------------- Alle Werte

/**
 * Was „Alle Werte" aufklappt — alles, was vor dem Klartext-Umbau bereits auf
 * der Seite stand, nichts davon faellt weg: weitere Kennzahlen, Tempo- und
 * Pulskurve, die Coach-Auswertung und die Segmente. Jeder Abschnitt traegt
 * eine [Eyebrow] und entfaellt ganz, wenn ihm die Daten fehlen.
 */
@Composable
private fun AllValues(
    ride: Ride,
    load: RideLoad?,
    curves: RideCurves?,
    analysis: RideAnalysis?,
    segmentViews: List<SegmentEffortView>,
) {
    // Fuer die Wertebereiche der Kurven — `formatValue` ist kein Composable.
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
        DetailSection(title = stringResource(R.string.rides_detail_more_values_eyebrow)) {
            RideExtraFactsCard(ride)
        }

        val speed = curves?.speed
        val heartRate = curves?.heartRate
        if (speed != null || heartRate != null) {
            DetailSection(title = stringResource(R.string.rides_detail_curves_eyebrow)) {
                speed?.let {
                    DetailCard {
                        RideCurveChart(
                            title = stringResource(R.string.rides_detail_speed_chart_title),
                            curve = it,
                            lineColor = LocalSignalColors.current.accentBlue,
                            formatValue = { v -> context.getString(R.string.common_value_kmh, formats.decimal(v, 1)) },
                            filled = true,
                        )
                    }
                }
                heartRate?.let {
                    DetailCard {
                        RideCurveChart(
                            title = stringResource(R.string.rides_detail_hr_chart_title),
                            curve = it,
                            lineColor = MaterialTheme.colorScheme.primary,
                            formatValue = { v -> context.getString(R.string.common_value_bpm, "${v.roundToInt()}") },
                        )
                    }
                }
            }
        }

        RideAnalysisCard(
            load = load,
            decoupling = analysis?.decoupling,
            vo2max = analysis?.vo2max,
            powerMeasured = analysis?.powerMeasured == true,
        )

        if (segmentViews.isNotEmpty()) {
            DetailSection(title = stringResource(R.string.rides_detail_segments_eyebrow)) {
                SegmentsCard(views = segmentViews)
            }
        }
    }
}

/**
 * Die Kennzahlen, die nicht in der Zahlenzeile stehen: Fahrzeit (im
 * Unterschied zur Gesamtdauer dort), Ø Tempo, Abstieg und Max. Puls — und,
 * wo ein Leistungsmesser oder Trittfrequenzsensor mitgemessen hat (oder die
 * importierte GPX-Datei es mitbrachte), Ø Leistung und Ø Trittfrequenz.
 */
@Composable
private fun RideExtraFactsCard(ride: Ride) {
    val stats = ride.stats
    val sensoren = remember(ride.id, ride.points.size) { sensorMittelwerte(ride.points) }
    val formats = LocalAppFormats.current

    DetailCard {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Fact(
                stringResource(R.string.rides_detail_moving_time_label),
                stringResource(R.string.rides_detail_hours_value, formatHoursMinutes(stats.movingTimeS)),
            )
            Fact(
                label = stringResource(R.string.rides_detail_avg_speed_label),
                value = stats.avgSpeedKmh
                    ?.let { stringResource(R.string.common_value_kmh, formats.decimal(it, 1)) }
                    ?: "–",
            )
            Fact(
                stringResource(R.string.rides_detail_descent_label),
                stringResource(R.string.rides_detail_elevation_value, stats.descentM.roundToInt()),
            )
            stats.maxHrBpm?.let {
                Fact(
                    stringResource(R.string.rides_detail_max_hr_label),
                    stringResource(R.string.common_value_bpm, "$it"),
                )
            }
            sensoren.avgPowerW?.let {
                Fact(stringResource(R.string.ble_fact_avg_power), stringResource(R.string.ble_fact_power_value, it))
            }
            sensoren.avgCadenceRpm?.let {
                Fact(
                    stringResource(R.string.ble_fact_avg_cadence),
                    stringResource(R.string.ble_fact_cadence_value, it),
                )
            }
        }
    }
}

/**
 * Die Auswertung, soweit `:core` sie fuer **diese** Fahrt tragen kann:
 * Trainingslast (hier als Zahl samt Quelle — in der Liste steht nur das Wort),
 * Pe:Hr-Entkopplung und VO₂max aus gleichmaessigen Abschnitten, jeweils mit
 * ihrer Verlaesslichkeit. Faellt alles drei aus, entfaellt die Karte ganz.
 *
 * Die geteilte [CoachCard] aus `ui/components/` bringt ihre Augenbraue
 * „Coach" selbst mit — dieselbe Kennzeichnung wie auf „Heute" und „Training".
 */
@Composable
private fun RideAnalysisCard(
    load: RideLoad?,
    decoupling: DecouplingResult?,
    vo2max: Vo2MaxEstimate?,
    powerMeasured: Boolean = false,
) {
    val coreTexts = LocalCoreTexts.current
    val formats = LocalAppFormats.current
    val usableLoad = load?.takeIf { it.available }
    if (usableLoad == null && decoupling == null && vo2max == null) {
        return
    }

    CoachCard(modifier = Modifier.padding(top = SectionTopGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            usableLoad?.let { entry ->
                AnalysisEntry(
                    label = stringResource(R.string.rides_detail_load_label),
                    value = stringResource(
                        R.string.rides_detail_load_value,
                        entry.load.roundToInt(),
                        coreTexts.load.loadSource(entry.source),
                    ),
                    explanation = entry.note,
                    confidence = entry.confidence,
                )
            }

            decoupling?.let { result ->
                val percent = formats.decimal(result.decouplingPercent ?: 0.0, 1)
                AnalysisEntry(
                    label = stringResource(R.string.rides_detail_decoupling_label),
                    value = result.rating
                        ?.let { stringResource(R.string.rides_detail_decoupling_value_rated, percent, it) }
                        ?: stringResource(R.string.rides_detail_decoupling_value, percent),
                    explanation = stringResource(R.string.rides_detail_decoupling_body),
                    confidence = result.confidence,
                )
            }

            vo2max?.let { estimate ->
                AnalysisEntry(
                    label = stringResource(R.string.rides_detail_vo2max_label),
                    value = estimate.text(coreTexts),
                    explanation = if (powerMeasured) {
                        stringResource(R.string.ble_vo2max_explanation_measured)
                    } else {
                        stringResource(R.string.rides_detail_vo2max_body)
                    },
                    confidence = estimate.confidence,
                )
            }
        }
    }
}

/**
 * Die Segmente dieser Tour: je automatisch erkanntem Anstieg die Zeit dieser
 * Befahrung, die persoenliche Bestzeit, Platz und Rueckstand — alles aus der
 * lokalen Registry (`:core`, `RideSegments.kt`), nichts davon verlaesst das
 * Geraet. Eine **neue** Bestzeit traegt eine Pille „★ Neue Bestzeit".
 */
@Composable
private fun SegmentsCard(views: List<SegmentEffortView>) {
    DetailCard {
        Text(
            text = stringResource(R.string.rides_detail_segments_body),
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
                // Bedeutung nicht allein ueber Farbe: Stern und Text tragen
                // sie auch in Graustufen.
                TagPill(
                    text = stringResource(R.string.rides_detail_segment_new_best_label),
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
            Fact(stringResource(R.string.rides_detail_segment_time_label), formatDuration(view.timeS))
            Fact(stringResource(R.string.rides_detail_segment_best_label), formatDuration(view.bestTimeS))
            Fact(
                stringResource(R.string.rides_detail_segment_rank_label),
                stringResource(R.string.rides_detail_segment_rank_value, view.rank, view.effortCount),
            )
            Fact(
                label = stringResource(R.string.rides_detail_segment_gap_label),
                value = if (view.deltaToBestS <= 0) "–" else "+${view.deltaToBestS} s",
            )
            view.avgHr?.let {
                Fact(
                    stringResource(R.string.rides_detail_segment_avg_hr_label),
                    stringResource(R.string.common_value_bpm, "$it"),
                )
            }
        }
    }
}

// -------------------------------------------------------------- Bausteine

/**
 * Ein Abschnitt mit geteilter [Eyebrow] darueber — um [CardPadding]
 * eingerueckt, damit sie auf der Kante des Kartentexts steht (wie die
 * Monatsueberschriften der Liste).
 */
@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
        Eyebrow(
            text = title,
            modifier = Modifier.padding(start = CardPadding, top = SectionTopGap),
        )
        content()
    }
}

/** Luft ueber einer Abschnitts-Augenbraue — trennt Abschnitte deutlicher als [CardGap]. */
private val SectionTopGap = 8.dp

/** Eine Karte der Detailansicht — ueberall dasselbe Innenmass. */
@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(CardPadding), content = content)
    }
}

/**
 * Ein Eintrag der Coach-Auswertung: die Zahl im [Fact]-Mass, darunter kurze
 * Erklaerung und wie belastbar sie ist — gedaempft in der Textfarbe der
 * Akzentflaeche, genau wie die Augenbraue der [CoachCard].
 */
@Composable
private fun AnalysisEntry(
    label: String,
    value: String,
    explanation: String,
    confidence: Confidence,
) {
    val muted = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    Column {
        Fact(label = label, value = value)
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
        if (confidence != Confidence.NONE) {
            Text(
                text = stringResource(
                    R.string.rides_detail_confidence_label,
                    LocalCoreTexts.current.load.confidence(confidence),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
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
    /** Leistung vom Leistungsmesser statt geschaetzt (siehe `PhysicsEstimate.measured`). */
    val powerMeasured: Boolean = false,
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
    texts: CoreTexts = LocalCoreTexts.current,
): State<RideAnalysis?> = produceState<RideAnalysis?>(
    initialValue = null,
    ride.id,
    ride.points.size,
    profile,
    eftpW,
    texts,
) {
    value = withContext(Dispatchers.Default) {
        val estimate = computePhysicsEstimate(
            buildRideSeries(ride.points, profile),
            profile,
            eftpW = eftpW,
            texts = texts,
        )
        if (!estimate.available) {
            RideAnalysis(decoupling = null, vo2max = null)
        } else {
            val decoupling = computeDecoupling(estimate, profile, texts)
            val vo2max = estimateVo2MaxFromSegments(
                extractSteadySegments(estimate.series, profile),
                profile,
                texts,
            )
            RideAnalysis(
                decoupling = decoupling.takeIf { it.available },
                vo2max = vo2max.takeIf { it.available },
                powerMeasured = estimate.measured,
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
private val MapHeight = 200.dp

/**
 * Rand der Kamerafahrt in Pixeln. Deutlich schmaler als der Standardwert des
 * Karten-Screens: Der ist fuer eine bildschirmfuellende Karte mit Panels davor
 * gedacht und wuerde bei 240 dp Hoehe fast die ganze Flaeche wegnehmen.
 */
private val MapFitPadding = MapPadding(left = 32, top = 32, right = 32, bottom = 32)

/** Zoomstufe fuer eine Tour, die nur aus einem einzigen Punkt besteht. */
private const val SinglePointZoom = 14.0
