package de.trailscape.app.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.CockpitMetricTile
import de.trailscape.app.ui.components.HoldToEndButton
import de.trailscape.app.ui.components.TwoPaneLayout
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.CockpitTheme
import de.trailscape.app.ui.theme.ContentMaxWidth
import de.trailscape.app.ui.theme.LocalCockpitColors
import de.trailscape.app.ui.theme.LocalWindowLayoutClass
import de.trailscape.app.ui.theme.RideModeActionHeight
import de.trailscape.app.ui.theme.RideModeExitHeight
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.app.ui.theme.TabletCardGap
import de.trailscape.app.ui.theme.TabletScreenPadding
import de.trailscape.app.ui.theme.WindowLayoutClass
import de.trailscape.app.ui.theme.tabular
import de.trailscape.app.ui.theme.windowLayoutClassFor
import de.trailscape.core.LiveSensorAnzeige
import de.trailscape.core.TurnRichtung
import de.trailscape.core.ZielZeile
import de.trailscape.core.formatDuration
import de.trailscape.core.turnAnsageText
import kotlin.math.roundToInt

/**
 * # Fahrmodus — dieselbe laufende Aufzeichnung, nur fuer den Blick im Fahren
 *
 * Die Live-Leiste auf der Karte ([LiveRecordingCard]) ist zum Nachschauen im
 * Stand gebaut: vier Kennzahlen nebeneinander in `headlineSmall`, zwei
 * 48-dp-Knoepfe mit 18-dp-Symbolen — die Material-Mindestflaeche, mehr nicht.
 * Auf Schotter, mit Handschuhen, bei Sonne und Vibration ist davon nichts mehr
 * sicher zu treffen oder aus der Bewegung heraus zu lesen. Der Fahrmodus ist
 * die Antwort darauf — **kein zweiter Aufzeichnungsweg**: Er liest exakt
 * dieselben StateFlows des [de.trailscape.app.record.RecordingRepository], die
 * auch die Leiste zeigt, und schickt dieselben zwei Kommandos zurueck.
 * Verlassen wird er ohne jede Wirkung auf die Aufzeichnung.
 *
 * Startet die Aufzeichnung durch eine Nutzeraktion, ist dieser Bildschirm
 * schon der erste, den man sieht (siehe `runRecording()` in `MapScreen.kt`)
 * — der Fahrmodus ist damit der Normalfall einer Aufzeichnung, nicht ein
 * Angebot, das ueber einen eigenen Knopf erst gefunden werden muss. Der Knopf
 * „Fahrmodus" in der Live-Leiste bleibt trotzdem: Er ist der Rueckweg, wenn
 * man diesen Bildschirm selbst verlassen hat, waehrend die Aufzeichnung
 * weiterlief.
 *
 * ## Warum genau diese vier Werte — und nichts weiter
 * Leitfrage war ausschliesslich: *Was liest man im Fahren mit einem Blick aus
 * einem Meter Abstand?* Danach bleibt eine klare Rangfolge:
 *
 *  1. **Tempo**, als groesste Zahl. Es ist der einzige Wert, der sich im
 *     Sekundentakt aendert und nach dem im Fahren tatsaechlich gehandelt wird
 *     (Tritt, Windschatten, Anstieg). Alles andere kann man auch am naechsten
 *     Halt ablesen.
 *  2. **Distanz und Fahrzeit**, gleich gross nebeneinander. Beide beantworten
 *     dieselbe Frage — „wie weit bin ich in der Tour?" — und keiner der beiden
 *     ist dem anderen uebergeordnet: Wer nach Zeit faehrt, liest links, wer nach
 *     Strecke faehrt, rechts.
 *  3. **Hoehenmeter**, als kleinster Wert. Auf Gravel gehoeren sie dazu, aber
 *     sie aendern sich traege und beeinflussen im Fahren keine Entscheidung.
 *
 * Bewusst **weggelassen**: die Punktzahl der Aufzeichnung (Diagnose, kein
 * Fahrwert) und jedes Beiwerk der Leiste (Rahmen, Karten, Symbole). Vier Zahlen
 * sind schon die Obergrenze dessen, was ein Blick erfasst.
 *
 * Laeuft zusaetzlich eine **Navigation**, steht die Fuehrung abgesetzt
 * ganz oben als farbige Flaeche ([NavigationPanel]): naechste Kurve (Pfeil
 * plus Distanz) gross, Restdistanz klein darunter, abseits der Route die
 * Warnflaeche an ihrer Stelle — die Werte, nach denen man im Fahren wirklich
 * handelt, sollen nicht zwischen den Zahlen untergehen. Die Navigationslogik
 * selbst bleibt, wo sie ist: `RouteNavigator` und `TurnHints` in `:core`,
 * ausgewertet in `MapScreen.kt`. Hier wird nur angezeigt, was dort schon
 * berechnet ist.
 *
 * Liefern Sensoren live Werte — eine gekoppelte Uhr (Handy-Bruecke, siehe
 * `de.trailscape.app.record.RecordingRepository.heartRateBpm`/
 * `.watchConnected`) oder per Bluetooth ein Pulsgurt, Leistungsmesser oder
 * Trittfrequenzsensor (`de.trailscape.app.sensors.BleSensors`) —, kommt eine
 * **Sensorzeile** dazu: an einer FESTEN Stelle direkt nach Distanz/Fahrzeit
 * und vor den Hoehenmetern, unabhaengig davon, ob zusaetzlich eine Navigation
 * laeuft. Darin stehen nebeneinander, immer in dieser Reihenfolge, Puls ·
 * Leistung · Trittfrequenz — nur die Kacheln, fuer die es eine Quelle gibt.
 * Die Reihenfolge der uebrigen Werte veraendert sich weder beim Verbinden
 * noch beim Trennen; die Zeile waechst oder schrumpft nur in sich.
 *
 * Welche Kachel mit welchem Wert erscheint, entscheidet `liveSensorAnzeige`
 * in `:core` (siehe [rememberLiveSensorAnzeige]): Ein frischer Gurtwert
 * schlaegt den Puls der Uhr; ist der Gurt still, springt die verbundene Uhr
 * ein. Ein Sensor, der seit mehr als fuenf Sekunden nichts liefert, zeigt den
 * Strich und „seit X s nichts" — nie einen veralteten Wert, der im Fahren
 * nicht von einem echten zu unterscheiden waere. Ohne jede Quelle erscheint
 * gar nichts. Mit allen drei Sensoren sind es sieben statt vier Zahlen — die
 * Obergrenze von vier Zahlen ueberschreitet deshalb nur, wer Sensoren
 * gekoppelt hat; die drei Kacheln werden dann eine Stufe kleiner (40 sp).
 *
 * ## Bedienung
 * Zwei gleich gebaute Flaechen ueber je die halbe Breite,
 * [RideModeActionHeight] hoch, Symbol und Wort in derselben Groesse.
 * **Pause/Weiter** wirkt sofort — ein versehentlicher Griff dorthin kostet ein
 * paar Sekunden Fahrzeit und sonst nichts. **Beenden** geht nur durch Halten
 * ([de.trailscape.app.ui.components.HoldToEndButton]), denn dieser Fehlgriff
 * kostet die ganze Tour.
 * Der beschriftete Knopf „Karte" in der Kopfzeile und ein horizontales
 * Wischen wechseln zur **Kartenseite des Fahrmodus** (NAVI_KARTE in
 * `MapScreen.kt`: Karte mit Kompaktleiste, KeepScreenOn bleibt an); die
 * Zurueck-Geste ([BackHandler]) verlaesst den Fahrmodus ganz — alles ohne
 * jede Wirkung auf die Aufzeichnung, die als Vordergrunddienst ohnehin
 * unabhaengig von dieser Ansicht weiterlaeuft.
 *
 * Die Formen sind One UI: Bedienflaechen und Status-Chip sind volle Pillen und
 * erben sie von `MaterialTheme.shapes.small`, die Warnung ist ein 26-dp-Block
 * (`shapes.medium`). Nur die Hoehe der Bedienflaechen ist bewusst hoeher
 * gelegen (siehe [RideModeActionHeight]) — an der wird fuer keine Mode
 * gedreht.
 *
 * ## Warum ein eigenes Fenster
 * Der Fahrmodus laeuft als [Dialog] ueber dem ganzen Fenster und nicht als
 * Ebene im Karten-`Box`. Nur so ist er wirklich bildschirmfuellend: Die
 * Navigationsleiste der Huelle (`ui/TrailscapeApp.kt`) liegt sonst weiter unter
 * dem Inhalt, und ein Fehlgriff neben dem Beenden-Knopf haette den Tab
 * gewechselt. Der Dialog bringt ausserdem seinen eigenen Zurueck-Dispatcher
 * mit, weshalb [BackHandler] hier greift (siehe unten).
 *
 * ## Farben und Kontrast
 * Alle Farben kommen aus `MaterialTheme.colorScheme` (Theme-Flaeche, nicht
 * Kartenkacheln — die drei festen Kartenfarben aus `MapColors.kt` gehoeren
 * hierher also gerade **nicht**). `surface`/`onSurface` liegen in beiden Modi
 * ueber 14:1 Kontrast; die Hauptwerte stehen durchgehend in `FontWeight.Bold`,
 * die Beschriftungen klein, aber in `onSurfaceVariant` (hell 9:1) — ein
 * zusaetzlicher Ton im Theme war dafuer nicht noetig.
 */
@Composable
internal fun RideModeScreen(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    paused: Boolean,
    navigation: RideModeNavigation?,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    /**
     * Wechselt zur Kartenseite des Fahrmodus (NAVI_KARTE in `MapScreen.kt`):
     * Dialog zu, Karte mit Kompaktleiste (und HUD, falls navigiert wird)
     * uebernimmt. Ausgeloest vom „Karte"-Knopf der Kopfzeile und von der
     * Wischgeste — beides ohne Wirkung auf die Aufzeichnung.
     */
    onShowMap: () -> Unit,
    // Ob die laufende Pause eine Auto-Pause ist (Stillstand erkannt, endet
    // von selbst bei Weiterfahrt) — nur fuer die Beschriftung des
    // Status-Chips, die Bedienung ist dieselbe wie bei einer manuellen Pause.
    autoPaused: Boolean = false,
    // Die Live-Sensorwerte — Parameter nur, damit Screenshot-Tests sie
    // einsetzen koennen; im Betrieb gilt der Vorgabewert.
    sensoren: LiveSensorAnzeige = rememberLiveSensorAnzeige(),
    // Die Zielzeile („Noch 12 km bis zum Wochenziel", `LiveZielZeile.kt`),
    // `null` = keine. Klein unter den Hoehenmetern: Sie aendert sich traege
    // und ist zum Nachdenken da, nicht zum Reagieren.
    zielZeile: ZielZeile? = null,
    useDialog: Boolean = true,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val currentLayoutClass = LocalWindowLayoutClass.current
    val configWidthDp = LocalConfiguration.current.screenWidthDp
    val layoutClass = if (currentLayoutClass != WindowLayoutClass.Compact) {
        currentLayoutClass
    } else {
        windowLayoutClassFor(configWidthDp)
    }

    CockpitTheme {
        CompositionLocalProvider(LocalWindowLayoutClass provides layoutClass) {
            if (useDialog) {
                Dialog(
                    onDismissRequest = onClose,
                    properties = DialogProperties(
                        // Volle Fensterbreite und -hoehe statt der Dialog-Standardbreite.
                        usePlatformDefaultWidth = false,
                        // Zurueck wird unten selbst behandelt (BackHandler).
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                    ),
                ) {
                    KeepScreenOn()
                    BackHandler { onClose() }
                    RideModeContent(
                        speedKmh = speedKmh,
                        distanceKm = distanceKm,
                        elapsedS = elapsedS,
                        ascentM = ascentM,
                        paused = paused,
                        autoPaused = autoPaused,
                        navigation = navigation,
                        sensoren = sensoren,
                        zielZeile = zielZeile,
                        layoutClass = layoutClass,
                        onTogglePause = onTogglePause,
                        onStop = onStop,
                        onShowMap = onShowMap,
                        mapContent = mapContent,
                    )
                }
            } else {
                KeepScreenOn()
                BackHandler { onClose() }
                RideModeContent(
                    speedKmh = speedKmh,
                    distanceKm = distanceKm,
                    elapsedS = elapsedS,
                    ascentM = ascentM,
                    paused = paused,
                    autoPaused = autoPaused,
                    navigation = navigation,
                    sensoren = sensoren,
                    zielZeile = zielZeile,
                    layoutClass = layoutClass,
                    onTogglePause = onTogglePause,
                    onStop = onStop,
                    onShowMap = onShowMap,
                    mapContent = mapContent,
                )
            }
        }
    }
}

@Composable
private fun RideModeContent(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    paused: Boolean,
    autoPaused: Boolean,
    navigation: RideModeNavigation?,
    sensoren: LiveSensorAnzeige,
    zielZeile: ZielZeile?,
    layoutClass: WindowLayoutClass,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onShowMap: () -> Unit,
    mapContent: (@Composable (Modifier) -> Unit)?,
) {
    val cockpit = LocalCockpitColors.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = cockpit.background,
        contentColor = cockpit.ink,
    ) {
        when (layoutClass) {
            WindowLayoutClass.Expanded -> RideModeExpandedContent(
                speedKmh = speedKmh,
                distanceKm = distanceKm,
                elapsedS = elapsedS,
                ascentM = ascentM,
                paused = paused,
                autoPaused = autoPaused,
                navigation = navigation,
                sensoren = sensoren,
                zielZeile = zielZeile,
                onTogglePause = onTogglePause,
                onStop = onStop,
                onShowMap = onShowMap,
                mapContent = mapContent,
            )

            WindowLayoutClass.Medium -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(TabletScreenPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                RideModeSingleColumnContent(
                    speedKmh = speedKmh,
                    distanceKm = distanceKm,
                    elapsedS = elapsedS,
                    ascentM = ascentM,
                    paused = paused,
                    autoPaused = autoPaused,
                    navigation = navigation,
                    sensoren = sensoren,
                    zielZeile = zielZeile,
                    compact = false,
                    onTogglePause = onTogglePause,
                    onStop = onStop,
                    onShowMap = onShowMap,
                    modifier = Modifier
                        .widthIn(max = ContentMaxWidth)
                        .fillMaxSize(),
                )
            }

            WindowLayoutClass.Compact -> RideModeSingleColumnContent(
                speedKmh = speedKmh,
                distanceKm = distanceKm,
                elapsedS = elapsedS,
                ascentM = ascentM,
                paused = paused,
                autoPaused = autoPaused,
                navigation = navigation,
                sensoren = sensoren,
                zielZeile = zielZeile,
                compact = true,
                onTogglePause = onTogglePause,
                onStop = onStop,
                onShowMap = onShowMap,
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(ScreenPadding),
            )
        }
    }
}

@Composable
private fun RideModeSingleColumnContent(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    paused: Boolean,
    autoPaused: Boolean,
    navigation: RideModeNavigation?,
    sensoren: LiveSensorAnzeige,
    zielZeile: ZielZeile?,
    compact: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onShowMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dense = compact && LocalDensity.current.fontScale > 1.15f
    val gap = when {
        dense -> 6.dp
        compact -> CompactCardGap
        else -> TabletCardGap
    }
    Column(
        modifier = modifier.swipeToMap(onShowMap),
    ) {
        RideModeHeader(paused = paused, autoPaused = autoPaused, onShowMap = onShowMap)

        if (navigation != null) {
            Spacer(Modifier.height(gap))
            NavigationPanel(navigation = navigation, compact = compact, dense = dense)
        }

        Spacer(Modifier.height(gap))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            RideModeMetricsGrid(
                speedKmh = speedKmh,
                distanceKm = distanceKm,
                elapsedS = elapsedS,
                ascentM = ascentM,
                sensoren = sensoren,
                zielZeile = zielZeile,
                compact = compact,
                dense = dense,
                twoColumnSensors = compact,
                heroMaxSize = when {
                    dense -> DenseCompactHeroSpeedSize
                    compact -> CompactHeroSpeedSize
                    else -> MediumHeroSpeedSize
                },
            )
        }

        Spacer(Modifier.height(gap))

        RideModeBottomActions(
            paused = paused,
            compact = compact,
            onTogglePause = onTogglePause,
            onStop = onStop,
        )
    }
}

@Composable
private fun RideModeExpandedContent(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    paused: Boolean,
    autoPaused: Boolean,
    navigation: RideModeNavigation?,
    sensoren: LiveSensorAnzeige,
    zielZeile: ZielZeile?,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onShowMap: () -> Unit,
    mapContent: (@Composable (Modifier) -> Unit)?,
) {
    val cockpit = LocalCockpitColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(TabletScreenPadding),
    ) {
        TwoPaneLayout(
            primaryWeight = 0.46f,
            gap = TabletCardGap,
            modifier = Modifier.fillMaxSize(),
            primary = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .swipeToMap(onShowMap),
                ) {
                    RideModeHeader(paused = paused, autoPaused = autoPaused, onShowMap = onShowMap)

                    Spacer(Modifier.height(TabletCardGap))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        RideModeMetricsGrid(
                            speedKmh = speedKmh,
                            distanceKm = distanceKm,
                            elapsedS = elapsedS,
                            ascentM = ascentM,
                            sensoren = sensoren,
                            zielZeile = zielZeile,
                            compact = false,
                            dense = false,
                            twoColumnSensors = false,
                            heroMaxSize = ExpandedHeroSpeedSize,
                        )
                    }

                    Spacer(Modifier.height(TabletCardGap))

                    RideModeBottomActions(
                        paused = paused,
                        compact = false,
                        onTogglePause = onTogglePause,
                        onStop = onStop,
                    )
                }
            },
            secondary = {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.large,
                    color = cockpit.panel,
                    contentColor = cockpit.ink,
                    border = BorderStroke(1.dp, cockpit.border),
                ) {
                    if (mapContent != null) {
                        Box(modifier = Modifier.fillMaxSize().testTag("ride_mode_map_content")) {
                            mapContent(Modifier.fillMaxSize())
                            if (navigation != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    NavigationPanel(navigation = navigation, compact = false)
                                }
                            }
                        }
                    } else {
                        RideModeRightPaneFallback(
                            navigation = navigation,
                            paused = paused,
                            autoPaused = autoPaused,
                            zielZeile = zielZeile,
                            onShowMap = onShowMap,
                        )
                    }
                }
            },
        )
    }
}

private fun Modifier.swipeToMap(onShowMap: () -> Unit): Modifier = pointerInput(Unit) {
    var gesamtX = 0f
    detectHorizontalDragGestures(
        onDragStart = { gesamtX = 0f },
        onDragEnd = {
            if (kotlin.math.abs(gesamtX) > SwipeZurKarteSchwelle.toPx()) {
                onShowMap()
            }
        },
    ) { _, dragAmount -> gesamtX += dragAmount }
}

@Composable
private fun RideModeRightPaneFallback(
    navigation: RideModeNavigation?,
    paused: Boolean,
    autoPaused: Boolean,
    zielZeile: ZielZeile?,
    onShowMap: () -> Unit,
) {
    val cockpit = LocalCockpitColors.current
    val formats = LocalAppFormats.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("ride_mode_right_pane_fallback")
            .padding(TabletScreenPadding),
        verticalArrangement = Arrangement.spacedBy(TabletCardGap),
    ) {
        if (navigation != null) {
            NavigationPanel(navigation = navigation, compact = false)
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = MaterialTheme.shapes.medium,
            color = cockpit.background,
            contentColor = cockpit.ink,
            border = BorderStroke(1.dp, cockpit.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(TabletScreenPadding),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (navigation != null) {
                                Icons.Filled.Navigation
                            } else if (paused) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.FiberManualRecord
                            },
                            contentDescription = null,
                            tint = when {
                                navigation?.offRoute == true -> cockpit.danger
                                paused -> cockpit.caution
                                else -> cockpit.good
                            },
                            modifier = Modifier.size(if (navigation != null || paused) 22.dp else 14.dp),
                        )
                        Text(
                            text = when {
                                navigation != null -> stringResource(R.string.map_ride_card_navigating_label)
                                paused && autoPaused -> stringResource(R.string.map_live_status_auto_paused)
                                paused -> stringResource(R.string.map_live_status_paused)
                                else -> stringResource(R.string.map_live_status_recording)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = cockpit.inkMuted,
                        )
                    }

                    if (navigation != null) {
                        Text(
                            text = navigation.label,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = cockpit.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = formats.km(navigation.remainingKm),
                                style = MaterialTheme.typography.displayMedium.tabular(),
                                fontWeight = FontWeight.Bold,
                                color = if (navigation.offRoute) cockpit.danger else cockpit.good,
                            )
                            Text(
                                text = "km",
                                style = MaterialTheme.typography.titleLarge,
                                color = cockpit.inkMuted,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                    } else {
                        Text(
                            text = when {
                                paused && autoPaused -> stringResource(R.string.map_ride_status_auto_paused)
                                paused -> stringResource(R.string.map_ride_status_paused)
                                else -> stringResource(R.string.map_ride_status_recording)
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = cockpit.ink,
                        )
                    }

                    if (zielZeile != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = cockpit.panel,
                            border = BorderStroke(1.dp, cockpit.border),
                        ) {
                            LiveZielZeile(
                                zielZeile,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.titleMedium.tabular(),
                            )
                        }
                    }
                }

                val showMapCd = stringResource(R.string.map_ride_show_map_cd)
                Surface(
                    onClick = onShowMap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(RideModeExitHeight)
                        .semantics { contentDescription = showMapCd },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    border = BorderStroke(1.dp, cockpit.border),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.map_ride_show_map_action),
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Restdistanz, naechste Kurve und Abweichung der laufenden Navigation — fertig aus `:core`. */
internal data class RideModeNavigation(
    /** Name der Tour bzw. „Geplante Route". */
    val label: String,
    val remainingKm: Double,
    val offRoute: Boolean,
    /** Richtung der naechsten Kurve, `null` = keine Kurve in Sicht. */
    val naechsteKurve: TurnRichtung? = null,
    /** Distanz bis zur naechsten Kurve entlang der Route in Metern. */
    val naechsteKurveM: Double? = null,
)

/**
 * Kopfzeile: links der Zustand der Aufzeichnung, rechts der Wechsel zur
 * Kartenseite.
 */
@Composable
private fun RideModeHeader(paused: Boolean, autoPaused: Boolean, onShowMap: () -> Unit) {
    val cockpit = LocalCockpitColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statusColor = if (paused) cockpit.caution else cockpit.ink
        val statusText = when {
            paused && autoPaused -> stringResource(R.string.map_ride_status_auto_paused)
            paused -> stringResource(R.string.map_ride_status_paused)
            else -> stringResource(R.string.map_ride_status_recording)
        }
        Surface(
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 8.dp)
                .height(RideModeExitHeight),
            shape = MaterialTheme.shapes.small,
            color = cockpit.panel,
            contentColor = statusColor,
            border = BorderStroke(1.dp, if (paused) cockpit.caution else cockpit.border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (paused) Icons.Filled.Pause else Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = if (paused) cockpit.caution else cockpit.good,
                    modifier = Modifier.size(if (paused) 24.dp else 14.dp),
                )
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = statusText,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 12.sp,
                        maxFontSize = 16.sp,
                        stepSize = 1.sp,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(color = statusColor),
                )
            }
        }
        val showMapCd = stringResource(R.string.map_ride_show_map_cd)
        Surface(
            onClick = onShowMap,
            modifier = Modifier
                .height(RideModeExitHeight)
                .semantics { contentDescription = showMapCd },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, cockpit.border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.map_ride_show_map_action),
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun RideModeMetricsGrid(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    sensoren: LiveSensorAnzeige,
    zielZeile: ZielZeile?,
    compact: Boolean,
    dense: Boolean,
    twoColumnSensors: Boolean,
    heroMaxSize: TextUnit,
) {
    val formats = LocalAppFormats.current
    val cockpit = LocalCockpitColors.current
    val gap = when {
        dense -> 6.dp
        compact -> CompactCardGap
        else -> CardGap
    }

    CockpitSpeedHero(
        value = speedKmh?.let { formats.decimal(it, 1) } ?: "–",
        spoken = speedKmh
            ?.let { stringResource(R.string.map_ride_speed_cd, formats.decimal(it, 1)) }
            ?: stringResource(R.string.map_ride_speed_unknown_cd),
        maxFontSize = heroMaxSize,
        compact = compact,
        dense = dense,
    )

    Spacer(Modifier.height(gap))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        val tileVertical = if (dense) 6.dp else 12.dp
        CockpitMetricTile(
            value = formats.km(distanceKm),
            unit = "",
            label = stringResource(R.string.map_ride_distance_label),
            modifier = Modifier.weight(1f),
            verticalPadding = tileVertical,
        )
        CockpitMetricTile(
            value = formatDuration(elapsedS),
            unit = "",
            label = stringResource(R.string.map_ride_time_label),
            modifier = Modifier.weight(1f),
            verticalPadding = tileVertical,
        )
    }

    val sensorItems = buildList {
        sensoren.puls?.let { kachel ->
            add(sensorKachelText(SensorKachelArt.PULS, kachel, stringResource(R.string.ble_ride_hr_label)))
        }
        sensoren.leistung?.let { kachel ->
            add(sensorKachelText(SensorKachelArt.LEISTUNG, kachel, stringResource(R.string.ble_ride_power_label)))
        }
        sensoren.trittfrequenz?.let { kachel ->
            add(sensorKachelText(SensorKachelArt.TRITTFREQUENZ, kachel, stringResource(R.string.ble_ride_cadence_label)))
        }
    }

    val ascentValue = "${ascentM.roundToInt()}"
    val ascentLabel = stringResource(R.string.map_ride_ascent_label)
    val ascentSpoken = stringResource(R.string.map_ride_ascent_cd, ascentM.roundToInt())

    Spacer(Modifier.height(gap))

    if (twoColumnSensors) {
        when (sensorItems.size) {
            0 -> CockpitDataTile(
                value = ascentValue,
                label = ascentLabel,
                spoken = ascentSpoken,
                dense = dense,
                modifier = Modifier.fillMaxWidth(),
            )

            1 -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                SensorDataTile(sensorItems[0], dense = dense, modifier = Modifier.weight(1f))
                CockpitDataTile(
                    value = ascentValue,
                    label = ascentLabel,
                    spoken = ascentSpoken,
                    dense = dense,
                    modifier = Modifier.weight(1f),
                )
            }

            2 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    SensorDataTile(sensorItems[0], dense = dense, modifier = Modifier.weight(1f))
                    SensorDataTile(sensorItems[1], dense = dense, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(gap))
                CockpitDataTile(
                    value = ascentValue,
                    label = ascentLabel,
                    spoken = ascentSpoken,
                    dense = dense,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    SensorDataTile(sensorItems[0], dense = dense, modifier = Modifier.weight(1f))
                    SensorDataTile(sensorItems[1], dense = dense, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(gap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    SensorDataTile(sensorItems[2], dense = dense, modifier = Modifier.weight(1f))
                    CockpitDataTile(
                        value = ascentValue,
                        label = ascentLabel,
                        spoken = ascentSpoken,
                        dense = dense,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    } else {
        if (sensorItems.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                sensorItems.forEach { item ->
                    SensorDataTile(item, dense = dense, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(gap))
        }
        CockpitDataTile(
            value = ascentValue,
            label = ascentLabel,
            spoken = ascentSpoken,
            dense = dense,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (zielZeile != null) {
        Spacer(Modifier.height(gap))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = cockpit.panel,
            border = BorderStroke(1.dp, cockpit.border),
        ) {
            LiveZielZeile(
                zeile = zielZeile,
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = when {
                        dense -> 6.dp
                        compact -> 8.dp
                        else -> 10.dp
                    },
                ),
                style = MaterialTheme.typography.titleSmall.tabular(),
            )
        }
    }
}

@Composable
private fun CockpitSpeedHero(
    value: String,
    spoken: String,
    maxFontSize: TextUnit,
    compact: Boolean,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cockpit = LocalCockpitColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.large,
        color = cockpit.panel,
        border = BorderStroke(1.dp, cockpit.border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 18.dp,
                    vertical = when {
                        dense -> 6.dp
                        compact -> 10.dp
                        else -> 14.dp
                    },
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = value,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = if (dense) 40.sp else 48.sp,
                    maxFontSize = maxFontSize,
                    stepSize = 2.sp,
                ),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = maxFontSize,
                    lineHeight = TextUnit.Unspecified,
                    fontWeight = FontWeight.Bold,
                    color = cockpit.ink,
                ).tabular(),
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.titleLarge,
                color = cockpit.inkMuted,
                maxLines = 1,
                modifier = Modifier.padding(
                    bottom = when {
                        dense -> 6.dp
                        compact -> 8.dp
                        else -> 10.dp
                    },
                ),
            )
        }
    }
}

@Composable
private fun SensorDataTile(text: SensorKachelText, dense: Boolean = false, modifier: Modifier = Modifier) {
    val cockpit = LocalCockpitColors.current
    CockpitDataTile(
        value = text.wert,
        label = text.label,
        spoken = text.spoken,
        valueColor = if (text.wert == "–") cockpit.inkMuted else cockpit.ink,
        dense = dense,
        modifier = modifier,
    )
}

@Composable
private fun CockpitDataTile(
    value: String,
    label: String,
    spoken: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalCockpitColors.current.ink,
    dense: Boolean = false,
) {
    val cockpit = LocalCockpitColors.current
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium,
        color = cockpit.panel,
        border = BorderStroke(1.dp, cockpit.border),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = if (dense) 6.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 4.dp),
        ) {
            BasicText(
                text = label,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 10.sp,
                    maxFontSize = 12.sp,
                    stepSize = 1.sp,
                ),
                style = MaterialTheme.typography.labelMedium.copy(color = cockpit.inkMuted),
            )
            BasicText(
                text = value,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 20.sp,
                    maxFontSize = if (dense) 30.sp else 38.sp,
                    stepSize = 2.sp,
                ),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = valueColor,
                    lineHeight = TextUnit.Unspecified,
                ).tabular(),
            )
        }
    }
}

/**
 * Die Fuehrung des Fahrmodus als eigene, farbige Flaeche direkt unter der
 * Kopfzeile: gross die naechste Kurve (Pfeil plus gerundete Distanz), darunter
 * Restdistanz und Routenname. Abseits der Route tritt [OffRouteWarning] an
 * ihre Stelle.
 */
@Composable
private fun NavigationPanel(navigation: RideModeNavigation, compact: Boolean = false, dense: Boolean = false) {
    if (navigation.offRoute) {
        OffRouteWarning(navigation = navigation, compact = compact, dense = dense)
        return
    }
    val cockpit = LocalCockpitColors.current
    val richtung = navigation.naechsteKurve
    val abstandM = navigation.naechsteKurveM
    val remainingKm = LocalAppFormats.current.km(navigation.remainingKm)
    val spoken = if (richtung != null && abstandM != null) {
        stringResource(
            R.string.map_ride_nav_turn_cd,
            turnAnsageText(richtung, abstandM, LocalCoreTexts.current),
            remainingKm,
            navigation.label,
        )
    } else {
        stringResource(R.string.map_ride_nav_no_turn_cd, remainingKm, navigation.label)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = cockpit.ink,
        border = BorderStroke(1.5.dp, cockpit.good),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 16.dp else 20.dp,
                vertical = when {
                    dense -> 8.dp
                    compact -> 12.dp
                    else -> 14.dp
                },
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = turnRichtungIcon(richtung),
                    contentDescription = null,
                    tint = cockpit.ink,
                    modifier = Modifier.size(
                        when {
                            dense -> 40.dp
                            compact -> 48.dp
                            else -> 60.dp
                        },
                    ),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    val turnMaxSize = if (dense) 34.sp else 40.sp
                    BasicText(
                        text = if (richtung != null && abstandM != null) {
                            kurveAbstandKurzText(abstandM).asString()
                        } else {
                            stringResource(R.string.map_nav_straight_label)
                        },
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 22.sp,
                            maxFontSize = turnMaxSize,
                            stepSize = 2.sp,
                        ),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = turnMaxSize,
                            lineHeight = TextUnit.Unspecified,
                            fontWeight = FontWeight.Bold,
                            color = cockpit.ink,
                        ).tabular(),
                    )
                    Text(
                        text = if (richtung != null) {
                            kurveAnzeigeWort(richtung).asString()
                        } else {
                            stringResource(R.string.map_nav_follow_label)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = if (compact) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        color = cockpit.ink,
                    )
                }
            }
            Spacer(Modifier.height(if (dense) 4.dp else 8.dp))
            RemainingLine(navigation)
        }
    }
}

/** „14,0 km übrig · Geplante Route" — die Fusszeile der Fuehrungsflaeche. */
@Composable
private fun RemainingLine(navigation: RideModeNavigation) {
    Text(
        text = stringResource(
            R.string.map_ride_nav_remaining_line,
            LocalAppFormats.current.km(navigation.remainingKm),
            navigation.label,
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleSmall.tabular(),
    )
}

/**
 * Abweichungswarnung als gefuellte Flaeche mit deutlichem Gefahr-Rahmen.
 */
@Composable
private fun OffRouteWarning(navigation: RideModeNavigation, compact: Boolean = false, dense: Boolean = false) {
    val cockpit = LocalCockpitColors.current
    val spoken = stringResource(
        R.string.map_ride_nav_off_route_cd,
        LocalAppFormats.current.km(navigation.remainingKm),
        navigation.label,
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.5.dp, cockpit.danger),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 16.dp else 20.dp,
                vertical = when {
                    dense -> 8.dp
                    compact -> 12.dp
                    else -> 14.dp
                },
            ),
        ) {
            Text(
                text = stringResource(R.string.map_nav_off_route_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            RemainingLine(navigation)
        }
    }
}

@Composable
private fun RideModeBottomActions(
    paused: Boolean,
    compact: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
) {
    val cockpit = LocalCockpitColors.current
    val pauseWeight = if (compact) 0.86f else 1f
    val endWeight = if (compact) 1.14f else 1f
    val endIconSize = if (compact) 22.dp else 28.dp
    val pauseIconSize = if (compact) 22.dp else 28.dp
    val endTextStyle = if (compact) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.headlineSmall
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        RideModeAction(
            modifier = Modifier.weight(pauseWeight),
            label = if (paused) {
                stringResource(R.string.map_ride_resume_action)
            } else {
                stringResource(R.string.map_ride_pause_action)
            },
            description = if (paused) {
                stringResource(R.string.map_ride_resume_cd)
            } else {
                stringResource(R.string.map_ride_pause_cd)
            },
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            iconSize = pauseIconSize,
            maxFontSize = if (compact) 18.sp else 22.sp,
            container = if (paused) cockpit.action else MaterialTheme.colorScheme.secondaryContainer,
            content = if (paused) cockpit.onAction else MaterialTheme.colorScheme.onSecondaryContainer,
            border = if (paused) null else BorderStroke(1.dp, cockpit.border),
            onClick = onTogglePause,
        )
        Spacer(Modifier.width(CardGap))
        HoldToEndButton(
            onEnd = onStop,
            modifier = Modifier.weight(endWeight),
            minHeight = RideModeActionHeight,
            label = stringResource(R.string.map_ride_end_action),
            holdHint = stringResource(R.string.map_ride_end_hold_hint),
            icon = Icons.Filled.Stop,
            iconSize = endIconSize,
            textStyle = endTextStyle,
        )
    }
}

/**
 * Daumengrosse Bedienflaeche: [RideModeActionHeight] hoch, scharfkantiges
 * Cockpit-Rechteck (`CockpitShapes.small`) und automatisch skalierende
 * Beschriftung, damit auch bei 360 dp und 1,3× Schrift nichts abschneidet.
 */
@Composable
private fun RideModeAction(
    label: String,
    description: String,
    icon: ImageVector?,
    iconSize: androidx.compose.ui.unit.Dp,
    maxFontSize: TextUnit,
    container: Color,
    content: Color,
    border: BorderStroke?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(RideModeActionHeight)
            .semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
                Spacer(Modifier.width(6.dp))
            }
            BasicText(
                text = label,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 12.sp,
                    maxFontSize = maxFontSize,
                    stepSize = 1.sp,
                ),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = maxFontSize,
                    lineHeight = TextUnit.Unspecified,
                    fontWeight = FontWeight.Bold,
                    color = content,
                ),
            )
        }
    }
}

/**
 * Haelt den Bildschirm an, solange der Fahrmodus in der Komposition steht.
 *
 * ## Warum nur hier und nicht in der ganzen App
 * In allen anderen Tabs liegt das Telefon in der Hand: Dort ist die
 * System-Abschaltung genau richtig, und ein dauerhaft heller Bildschirm waere
 * nichts als Akkuverbrauch. Im Fahrmodus steckt es dagegen am Lenker und wird
 * minutenlang nicht beruehrt — die Abschaltung wuerde die Anzeige genau dann
 * dunkel machen, wenn sie gebraucht wird, und liesse sich mit Handschuhen nur
 * umstaendlich wieder aufwecken. Deshalb haengt das Flag am Fahrmodus, nicht
 * an der Aufzeichnung (die laeuft als Vordergrunddienst ohnehin bei dunklem
 * Bildschirm weiter) und schon gar nicht an der Activity insgesamt.
 *
 * Das Flag sitzt am Fenster der Activity, nicht am Dialogfenster: Das
 * Activity-Fenster bleibt hinter dem Fahrmodus sichtbar, und [onDispose] nimmt
 * das Flag beim Verlassen — auf welchem Weg auch immer (Knopf, Zurueck,
 * Beenden der Aufzeichnung, Prozessende der Komposition) — wieder zurueck. Ein
 * vergessenes Flag leert sonst den Akku, bis die App neu gestartet wird.
 *
 * `internal`, weil auch die Kartenseite des Fahrmodus (NAVI_KARTE in
 * `MapScreen.kt`) den Bildschirm anhaelt — dieselbe Lenker-Situation, nur
 * mit Karte statt grosser Zahlen.
 */
@Composable
internal fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Sucht die Activity hinter einem Context. Der Context einer View im
 * Dialogfenster ist ein `ContextWrapper` um die Activity, kein direkter
 * Activity-Verweis — deshalb die Kette entlang.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private val DenseCompactHeroSpeedSize = 56.sp
private val CompactHeroSpeedSize = 76.sp
private val MediumHeroSpeedSize = 88.sp
private val ExpandedHeroSpeedSize = 84.sp
private val CompactCardGap = 8.dp

/**
 * Mindest-Wischstrecke fuer den Wechsel zur Kartenseite. Deutlich ueber dem
 * System-Touch-Slop: Ein schraeger Scrollversuch oder ein Wackler am Lenker
 * darf die Seite nicht wechseln — die Geste ist eine Abkuerzung, der Knopf
 * bleibt der verlaessliche Weg.
 */
private val SwipeZurKarteSchwelle = 64.dp
