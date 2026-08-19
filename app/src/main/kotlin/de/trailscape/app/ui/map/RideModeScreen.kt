package de.trailscape.app.ui.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.app.ui.theme.RideModeActionHeight
import de.trailscape.app.ui.theme.RideModeExitHeight
import de.trailscape.app.ui.theme.ScreenPadding
import de.trailscape.core.formatDuration
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
 * Laeuft zusaetzlich eine **Navigation**, kommen Restdistanz (in derselben
 * Groesse wie Distanz und Fahrzeit) und die Abweichungswarnung dazu — beides
 * Werte, nach denen man im Fahren wirklich handelt. Die Navigationslogik selbst
 * bleibt, wo sie ist: `RouteNavigator` in `:core`, ausgewertet in
 * `MapScreen.kt`. Hier wird nur angezeigt, was dort schon berechnet ist.
 *
 * Liefert eine gekoppelte Uhr live Werte (Handy-Bruecke, siehe
 * `de.trailscape.app.record.RecordingRepository.heartRateBpm`/
 * `.watchConnected`), kommt eine **Puls**-Kachel dazu — an einer FESTEN
 * Stelle direkt nach Distanz/Fahrzeit, unabhaengig davon, ob zusaetzlich eine
 * Navigation laeuft: Die Reihenfolge der uebrigen Kacheln soll sich weder
 * beim Verbinden noch beim Trennen der Uhr veraendern, nur um die Puls-Kachel
 * herum wachsen oder schrumpfen. Ohne Uhr erscheint gar nichts — eine leere
 * oder veraltete Pulsanzeige waere eine Falschmeldung, kein Informationsverlust.
 *
 * ## Bedienung
 * Zwei Flaechen ueber je die halbe Breite, [RideModeActionHeight] hoch.
 * **Pause/Weiter** wirkt sofort — ein versehentlicher Griff dorthin kostet ein
 * paar Sekunden Fahrzeit und sonst nichts. **Beenden** dagegen fragt zurueck
 * (siehe [StopConfirmation]), denn dieser Fehlgriff kostet die ganze Tour.
 * Zurueck zur Karte geht es ueber den beschrifteten Knopf in der Kopfzeile und
 * ueber die Zurueck-Geste ([BackHandler]) — beides ohne jede Wirkung auf die
 * Aufzeichnung, die als Vordergrunddienst ohnehin unabhaengig von dieser
 * Ansicht weiterlaeuft.
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
    // Ob die laufende Pause eine Auto-Pause ist (Stillstand erkannt, endet
    // von selbst bei Weiterfahrt) — nur fuer die Beschriftung des
    // Status-Chips, die Bedienung ist dieselbe wie bei einer manuellen Pause.
    autoPaused: Boolean = false,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            // Volle Fensterbreite und -hoehe statt der Dialog-Standardbreite.
            usePlatformDefaultWidth = false,
            // Zurueck wird unten selbst behandelt: Steht die Beenden-Rueckfrage
            // offen, soll die erste Zurueck-Geste nur sie zuruecknehmen und
            // nicht gleich den ganzen Fahrmodus schliessen.
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        KeepScreenOn()

        // Direkt aus dem Repository statt als Parameter: Anders als
        // speedKmh/distanceKm/... (aus der laufenden Navigation berechnet und
        // vom Aufrufer durchgereicht) hat der Puls mit der Fahrt selbst
        // nichts zu tun — er ist ein reiner Live-Wert der Handy-Bruecke
        // (siehe `RecordingRepository.heartRateBpm`). `watchConnected` gilt
        // als Bedingung dafuer, dass die Kachel ueberhaupt erscheint: eine
        // veraltete Herzfrequenz von einer inzwischen getrennten Uhr waere
        // ein stilles Falschanzeigen, kein leeres Feld (siehe Klassendoc).
        val heartRateBpm by RecordingRepository.heartRateBpm.collectAsStateWithLifecycle()
        val watchConnected by RecordingRepository.watchConnected.collectAsStateWithLifecycle()
        val pulsBpm = heartRateBpm.takeIf { watchConnected }

        // Die Rueckfrage vor dem Beenden. Bewusst Zustand *dieses* Fensters und
        // nicht des Screens: Sie ist nur so lange interessant, wie der
        // Fahrmodus offen ist.
        var confirmStop by remember { mutableStateOf(false) }

        BackHandler {
            if (confirmStop) confirmStop = false else onClose()
        }

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
                    .padding(ScreenPadding),
            ) {
                RideModeHeader(paused = paused, autoPaused = autoPaused, onClose = onClose)

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
                    if (pulsBpm != null) {
                        Spacer(Modifier.height(CardGap))
                        BigValue(
                            value = "$pulsBpm",
                            label = "bpm · Puls",
                            size = SecondaryValueSize,
                            spoken = "Puls $pulsBpm Schläge pro Minute",
                        )
                    }
                    if (navigation != null) {
                        Spacer(Modifier.height(CardGap))
                        BigValue(
                            value = formatKmDe(navigation.remainingKm),
                            label = "km übrig · ${navigation.label}",
                            size = SecondaryValueSize,
                            spoken = "Noch ${formatKmDe(navigation.remainingKm)} Kilometer " +
                                "bis zum Ziel der Route ${navigation.label}",
                        )
                        if (navigation.offRoute) {
                            Spacer(Modifier.height(CardGap))
                            OffRouteWarning()
                        }
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

                if (confirmStop) {
                    StopConfirmation(
                        onCancel = { confirmStop = false },
                        onConfirm = {
                            confirmStop = false
                            onStop()
                        },
                    )
                } else {
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
                        RideModeAction(
                            modifier = Modifier.weight(1f),
                            label = "Beenden",
                            description = "Aufzeichnung beenden, mit Rückfrage",
                            icon = Icons.Filled.Stop,
                            container = MaterialTheme.colorScheme.error,
                            content = MaterialTheme.colorScheme.onError,
                            onClick = { confirmStop = true },
                        )
                    }
                }
            }
        }
    }
}

/** Restdistanz und Abweichung der laufenden Navigation — fertig aus `:core`. */
internal data class RideModeNavigation(
    /** Name der Tour bzw. „Geplante Route". */
    val label: String,
    val remainingKm: Double,
    val offRoute: Boolean,
)

/**
 * Kopfzeile: links der Zustand der Aufzeichnung, rechts der Weg zurueck.
 *
 * Der Rueckweg ist bewusst ein beschrifteter Knopf und kein blosses X-Symbol:
 * Wer den Fahrmodus zum ersten Mal sieht, soll ohne Probieren erkennen, dass
 * dahinter die Karte liegt — und nicht das Ende der Aufzeichnung.
 */
@Composable
private fun RideModeHeader(paused: Boolean, autoPaused: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
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
            Text(
                text = when {
                    paused && autoPaused -> "Auto-Pause"
                    paused -> "Pausiert"
                    else -> "Aufzeichnung läuft"
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.weight(1f))
        Surface(
            onClick = onClose,
            modifier = Modifier
                .height(RideModeExitHeight)
                .semantics { contentDescription = "Fahrmodus verlassen, zurück zur Karte" },
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
 * Abweichungswarnung.
 *
 * Als gefuellte Flaeche statt als roter Text: Farbe allein ist bei Sonne und
 * Vibration zu wenig, die Flaeche faellt auch im Augenwinkel auf. Ob abseits
 * der Route gefahren wird, entscheidet die Hysterese im `RouteNavigator`
 * (`:core`) — hier wird das Ergebnis nur gezeigt.
 */
@Composable
private fun OffRouteWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Text(
            text = "Abseits der Route",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontSize = SmallValueSize,
            lineHeight = SmallValueSize * 1.1f,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Die Rueckfrage vor dem Beenden.
 *
 * Ein Fehlgriff auf Schotter darf keine laufende Aufzeichnung beenden — die
 * ist zu teuer, um sie noch einmal zu fahren. Die Rueckfrage ersetzt deshalb
 * die gesamte Knopfzeile, und zwar **ueber Kreuz**: „Ja, beenden" steht links,
 * wo eben noch „Pause" lag, „Abbrechen" rechts, wo der Finger gerade
 * „Beenden" getroffen hat. Wer beim Ruettelfehlgriff ein zweites Mal auf
 * dieselbe Stelle tippt, bricht damit ab, statt zu bestaetigen — genau der
 * Fall, den ein Dialog mit gleicher Knopfreihenfolge nicht abfaengt.
 *
 * Bewusst kein `AlertDialog`: dessen Textknoepfe sind genau die kleinen Ziele,
 * die dieser Modus vermeiden soll.
 */
@Composable
private fun StopConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Aufzeichnung wirklich beenden?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            RideModeAction(
                modifier = Modifier.weight(1f),
                label = "Ja, beenden",
                description = "Ja, Aufzeichnung jetzt beenden und speichern",
                icon = Icons.Filled.Stop,
                container = MaterialTheme.colorScheme.error,
                content = MaterialTheme.colorScheme.onError,
                onClick = onConfirm,
            )
            Spacer(Modifier.width(CardGap))
            RideModeAction(
                modifier = Modifier.weight(1f),
                label = "Abbrechen",
                description = "Abbrechen, Aufzeichnung läuft weiter",
                icon = null,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onCancel,
            )
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
                Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall,
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
 */
@Composable
private fun KeepScreenOn() {
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
