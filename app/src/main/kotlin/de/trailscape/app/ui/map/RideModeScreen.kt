package de.trailscape.app.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.core.LiveSensorAnzeige
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.components.HoldToEndButton
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.RideModeActionHeight
import de.trailscape.app.ui.theme.RideModeExitHeight
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.TurnRichtung
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
) {
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

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Erst die Systemleisten aussparen (das Fenster ist
                    // randlos), dann der normale Bildschirmrand.
                    .safeDrawingPadding()
                    .padding(ScreenPadding)
                    // Horizontales Wischen wechselt zur Kartenseite — die
                    // Datenseite hat selbst keine horizontalen Gesten, die
                    // Geste ist also konfliktfrei (der `verticalScroll` des
                    // Koerpers laeuft quer dazu). Zurueck von der Karte geht
                    // es bewusst NUR ueber den „Daten"-Knopf der
                    // Kompaktleiste: Dort haetten Kartengesten Vorrang.
                    .pointerInput(Unit) {
                        var gesamtX = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { gesamtX = 0f },
                            onDragEnd = {
                                if (kotlin.math.abs(gesamtX) > SwipeZurKarteSchwelle.toPx()) {
                                    onShowMap()
                                }
                            },
                        ) { _, dragAmount -> gesamtX += dragAmount }
                    },
            ) {
                RideModeHeader(paused = paused, autoPaused = autoPaused, onShowMap = onShowMap)

                // Die Fuehrung steht abgesetzt ganz oben, als farbige Flaeche:
                // Sie ist das, wonach man im Fahren handelt, und darf nicht
                // zwischen den Zahlen untergehen (siehe [NavigationPanel]).
                if (navigation != null) {
                    Spacer(Modifier.height(CardGap))
                    NavigationPanel(navigation)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Nur Notausgang: Auf sehr kleinen oder stark
                        // vergroesserten Bildschirmen passen die grossen Zahlen
                        // sonst nicht mehr untereinander. Im Normalfall gibt es
                        // hier nichts zu scrollen — im Fahren scrollt niemand.
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    BigValue(
                        value = speedKmh?.let { formatOneDecimalDe(it) } ?: "–",
                        label = "km/h",
                        size = SpeedValueSize,
                        spoken = speedKmh
                            ?.let { "Tempo ${formatOneDecimalDe(it)} Kilometer pro Stunde" }
                            ?: "Tempo unbekannt",
                    )
                    Spacer(Modifier.height(CardGap))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        BigValue(
                            modifier = Modifier.weight(1f),
                            value = formatKmDe(distanceKm),
                            label = "km gefahren",
                            size = SecondaryValueSize,
                            spoken = "Distanz ${formatKmDe(distanceKm)} Kilometer",
                        )
                        BigValue(
                            modifier = Modifier.weight(1f),
                            value = formatDuration(elapsedS),
                            label = "Fahrzeit",
                            size = SecondaryValueSize,
                            spoken = "Fahrzeit ${formatDuration(elapsedS)}",
                        )
                    }
                    // Feste Stelle der Sensorzeile (siehe Klassendoc): nach
                    // Distanz/Fahrzeit, vor den Hoehenmetern.
                    if (sensoren.anzahl > 0) {
                        Spacer(Modifier.height(CardGap))
                        LiveSensorRow(
                            sensoren = sensoren,
                            groesse = if (sensoren.anzahl <= 2) SecondaryValueSize else ThreeUpValueSize,
                        )
                    }
                    Spacer(Modifier.height(CardGap))
                    BigValue(
                        value = "${ascentM.roundToInt()}",
                        label = "Höhenmeter ↑",
                        size = SmallValueSize,
                        spoken = "${ascentM.roundToInt()} Höhenmeter bergauf",
                    )
                }

                Spacer(Modifier.height(CardGap))

                Row(modifier = Modifier.fillMaxWidth()) {
                        RideModeAction(
                            modifier = Modifier.weight(1f),
                            label = if (paused) "Weiter" else "Pause",
                            // Pause ist folgenlos und wirkt deshalb sofort —
                            // anders als das Beenden daneben, das erst noch
                            // durch die Rueckfrage muss.
                            description = if (paused) {
                                "Aufzeichnung fortsetzen"
                            } else {
                                "Aufzeichnung pausieren"
                            },
                            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            onClick = onTogglePause,
                        )
                        Spacer(Modifier.width(CardGap))
                        // Beenden nur durch Halten — derselbe Knopf wie auf
                        // der Karte und in der Kompaktleiste.
                        HoldToEndButton(
                            onEnd = onStop,
                            modifier = Modifier.weight(1f),
                            minHeight = RideModeActionHeight,
                            label = "Beenden",
                            holdHint = "gedrückt halten",
                            icon = Icons.Filled.Stop,
                            iconSize = RideModeActionIconSize,
                            textStyle = MaterialTheme.typography.headlineSmall,
                        )
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
 *
 * Der Knopf ist bewusst beschriftet und kein blosses X-Symbol: Wer den
 * Fahrmodus zum ersten Mal sieht, soll ohne Probieren erkennen, dass
 * dahinter die Karte liegt — und nicht das Ende der Aufzeichnung. Seit der
 * Kartenseite des Fahrmodus wechselt er dorthin (Kompaktleiste statt
 * Live-Leiste, KeepScreenOn bleibt an), statt den Fahrmodus zu verlassen;
 * ganz heraus fuehrt weiterhin die Zurueck-Geste.
 */
@Composable
private fun RideModeHeader(paused: Boolean, autoPaused: Boolean, onShowMap: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status-Chip und „Karte" sind gleich hoch und gleich gebaut (Symbol
        // plus Wort in `titleMedium`) — nur der Karte-Knopf ist antippbar
        // und traegt deshalb die Knopf-Farbe `secondaryContainer`.
        Surface(
            modifier = Modifier.height(RideModeExitHeight),
            shape = MaterialTheme.shapes.small,
            color = if (paused) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (paused) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (paused) Icons.Filled.Pause else Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(if (paused) 28.dp else 16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        paused && autoPaused -> "Auto-Pause"
                        paused -> "Pausiert"
                        else -> "Aufzeichnung"
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onShowMap,
            modifier = Modifier
                .height(RideModeExitHeight)
                .semantics {
                    contentDescription = "Zur Kartenseite des Fahrmodus wechseln, " +
                        "Aufzeichnung läuft weiter"
                },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Karte",
                    maxLines = 1,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * Ein Wert mit Beschriftung — dieselbe Rolle wie [Metric] in der Live-Leiste,
 * nur in Fahr-Groesse.
 *
 * Die Groesse kommt als Parameter statt aus `MaterialTheme.typography`: Selbst
 * `displayLarge` bleibt bei 57 sp, und die Rangfolge der Werte (Tempo >
 * Distanz/Zeit > Hoehenmeter) soll aus dem Groessenverhaeltnis sofort ablesbar
 * sein — nicht aus drei aehnlich grossen Typo-Stufen.
 *
 * @param spoken Vorlesetext. Eine nackte „24,3" hilft niemandem; TalkBack liest
 *   deshalb Bedeutung, Wert und Einheit als einen Satz — die getrennten
 *   Textknoten werden dafuer mit [clearAndSetSemantics] ersetzt.
 */
@Composable
private fun BigValue(
    value: String,
    label: String,
    size: TextUnit,
    spoken: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = size,
            // Ohne eigene Zeilenhoehe behaelt der Stil seine kleine bei und
            // schneidet Ober-/Unterlaengen der grossen Ziffern ab.
            lineHeight = size * 1.1f,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Die Sensorzeile des Fahrmodus: je vorhandener Quelle eine gleich breite
 * [BigValue]-Kachel, immer in der Reihenfolge Puls · Leistung ·
 * Trittfrequenz. Mit nur einer Kachel sieht sie aus wie die fruehere
 * Puls-Kachel.
 */
@Composable
private fun LiveSensorRow(sensoren: LiveSensorAnzeige, groesse: TextUnit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        sensoren.puls?.let { kachel ->
            val t = sensorKachelText(SensorKachelArt.PULS, kachel, stringResource(R.string.ble_ride_hr_label))
            BigValue(Modifier.weight(1f), t, groesse)
        }
        sensoren.leistung?.let { kachel ->
            val t = sensorKachelText(SensorKachelArt.LEISTUNG, kachel, stringResource(R.string.ble_ride_power_label))
            BigValue(Modifier.weight(1f), t, groesse)
        }
        sensoren.trittfrequenz?.let { kachel ->
            val t = sensorKachelText(
                SensorKachelArt.TRITTFREQUENZ,
                kachel,
                stringResource(R.string.ble_ride_cadence_label),
            )
            BigValue(Modifier.weight(1f), t, groesse)
        }
    }
}

@Composable
private fun BigValue(modifier: Modifier, text: SensorKachelText, size: TextUnit) {
    BigValue(value = text.wert, label = text.label, size = size, spoken = text.spoken, modifier = modifier)
}

/**
 * Die Fuehrung des Fahrmodus als eigene, farbige Flaeche direkt unter der
 * Kopfzeile: gross die naechste Kurve (Pfeil plus gerundete Distanz — dieselbe
 * Auskunft wie im Navigations-HUD auf der Karte, `NavigationHud.kt`), darunter
 * klein Restdistanz und Routenname. Abgesetzt in `primary`, weil man danach im
 * Fahren handelt; die uebrigen Werte darunter sind Auskunft.
 *
 * Die Flaeche steht an FESTER Position (immer, wenn navigiert wird): Ohne
 * Kurve in Sicht zeigt sie den Geradeaus-Pfeil, statt zu verschwinden — die
 * Werte darunter sollen beim Naeherkommen einer Kurve nicht springen (dieselbe
 * Regel wie bei der Puls-Kachel, siehe Klassen-KDoc). Abseits der Route tritt
 * die Warnflaeche an ihre Stelle: Eine Kurvenauskunft auf fremdem Weg waere
 * eine Falschauskunft.
 */
@Composable
private fun NavigationPanel(navigation: RideModeNavigation) {
    if (navigation.offRoute) {
        OffRouteWarning(navigation)
        return
    }
    val richtung = navigation.naechsteKurve
    val abstandM = navigation.naechsteKurveM
    val spoken = (
        if (richtung != null && abstandM != null) {
            "Nächste Kurve: ${turnAnsageText(richtung, abstandM)}"
        } else {
            "Keine Kurve in Sicht, dem Routenverlauf folgen."
        }
        ) + " Noch ${formatKmDe(navigation.remainingKm)} Kilometer auf ${navigation.label}."
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = turnRichtungIcon(richtung),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (richtung != null && abstandM != null) {
                            kurveAbstandKurzText(abstandM)
                        } else {
                            "Geradeaus"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = SecondaryValueSize,
                        lineHeight = SecondaryValueSize * 1.1f,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (richtung != null) kurveAnzeigeWort(richtung) else "dem Weg folgen",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            RemainingLine(navigation)
        }
    }
}

/** „14,0 km übrig · Geplante Route" — die Fusszeile der Fuehrungsflaeche. */
@Composable
private fun RemainingLine(navigation: RideModeNavigation) {
    Text(
        text = "${formatKmDe(navigation.remainingKm)} km übrig · ${navigation.label}",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * Abweichungswarnung.
 *
 * Als gefuellte Flaeche statt als roter Text: Farbe allein ist bei Sonne und
 * Vibration zu wenig, die Flaeche faellt auch im Augenwinkel auf. Ob abseits
 * der Route gefahren wird, entscheidet die Hysterese im `RouteNavigator`
 * (`:core`) — hier wird das Ergebnis nur gezeigt.
 */
@Composable
private fun OffRouteWarning(navigation: RideModeNavigation) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "Abseits der Route. Noch ${formatKmDe(navigation.remainingKm)} " +
                    "Kilometer auf ${navigation.label}."
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = "Abseits der Route",
                fontSize = SmallValueSize,
                lineHeight = SmallValueSize * 1.1f,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            RemainingLine(navigation)
        }
    }
}

/**
 * Daumengrosse Bedienflaeche: [RideModeActionHeight] hoch, ueber die halbe
 * Breite, Beschriftung und Symbol in Fahr-Groesse. Die Pille erbt sie vom
 * Theme (`shapes.small`); die Hoehe bleibt die dokumentierte
 * Sicherheitsentscheidung und wird von keiner Rundung aufgeweicht.
 */
@Composable
private fun RideModeAction(
    label: String,
    description: String,
    icon: ImageVector?,
    container: Color,
    content: Color,
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
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(RideModeActionIconSize))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
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

/**
 * Schriftgroessen der drei Rangstufen. In `sp`, also der Systemschrift-
 * Einstellung folgend — die Groesse hier ist die Untergrenze fuer „Blick aus
 * einem Meter", nicht eine feste Bildpunktzahl.
 */
private val SpeedValueSize = 96.sp
private val SecondaryValueSize = 52.sp
private val SmallValueSize = 30.sp

/** Drei Sensorkacheln nebeneinander: eine Stufe unter [SecondaryValueSize], damit „215" und „142" passen. */
private val ThreeUpValueSize = 40.sp

/** Symbolgroesse beider Bedienflaechen — Pause/Weiter und Beenden gleich. */
private val RideModeActionIconSize = 36.dp

/**
 * Mindest-Wischstrecke fuer den Wechsel zur Kartenseite. Deutlich ueber dem
 * System-Touch-Slop: Ein schraeger Scrollversuch oder ein Wackler am Lenker
 * darf die Seite nicht wechseln — die Geste ist eine Abkuerzung, der Knopf
 * bleibt der verlaessliche Weg.
 */
private val SwipeZurKarteSchwelle = 64.dp
