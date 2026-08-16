package de.trailscape.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.app.ui.theme.OverlayGap
import de.trailscape.core.Ride
import de.trailscape.core.TrackPoint
import de.trailscape.core.formatDuration
import kotlin.math.roundToInt

/**
 * Die Bedienflaechen, die auf der Karte liegen: Live-Leiste der Aufzeichnung,
 * Statistik-Karte der ausgewaehlten Tour, Navigationsleiste, Downloadanzeige,
 * der stehende Standort-Hinweis ([LocationPermissionNotice]) und die kleinen
 * Knoepfe am oberen Rand.
 *
 * Flaechen, Formen und **Tinte** erben das One-UI-Theme (`MaterialTheme`,
 * heller/dunkler Modus): Die Karten bringen Rundung (26 dp) und
 * `surfaceContainerLow` vom `Card`-Slot mit, die Knopfformen die Pille von
 * `shapes.small`, Text und Symbole auf Kartenflaechen `primary`/`error` aus
 * dem Schema. Als **schwebende** Container ueber der Karte tragen sie seit
 * dem One-UI-8.5/9-Look einen weichen Drop-Shadow — nur eingelassene Listen
 * (Startseite, Mehr, Training) sind flach; das ist kein Widerspruch, sondern
 * genau die Trennung, die One UI zwischen schwebenden und eingelassenen
 * Flaechen macht. Die drei festen Farben aus `MapColors.kt` liegen
 * ausschliesslich auf den Kacheln (Spuren, Markierungen) und als Fuellung
 * aktivierter Bedienelemente — nie als Text auf einer Theme-Flaeche, deren
 * Kontrast im Dunkelmodus niemand garantiert.
 *
 * Bewusst anders als das Flutter-Original: Dort war jedes Panel in
 * `AnimatedSwitcher`/`AnimatedContainer` verpackt und jeder Zahlenwechsel
 * animiert. Hier bleibt es bei einfachen, ruhigen Karten — Compose animiert
 * Sichtbarkeit ueber `AnimatedVisibility` im Screen, und eine Kennzahl, die
 * sich im Sekundentakt hereinschiebt, ist auf dem Rad eher unruhig als schoen.
 */

/** Ein grosser Wert mit Beschriftung (`_Metric` im Original) — dieselbe Stat-Grammatik wie in jedem Tab ([Fact]). */
@Composable
internal fun Metric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    Fact(label = label, value = value, modifier = modifier, compact = !big)
}

/**
 * Live-Leiste waehrend der Aufzeichnung.
 *
 * Die Werte kommen unveraendert aus dem
 * [de.trailscape.app.record.RecordingRepository]; nur die Hoehenmeter rechnet
 * der Screen selbst aus den bisherigen Punkten.
 *
 * ## Pause und Beenden: mindestens 48 dp
 * [NeutralButton] und [DangerButton] sind wie jeder Contained-Knopf der App
 * 48 dp hoch — dieselbe Material-Mindestflaeche, die die Karte selbst schon
 * beim Tippen auf einen Wegpunkt einhaelt (siehe `WAYPOINT_TOUCH_RADIUS_DP`
 * in `MapScreen.kt`). Kleiner darf hier nichts werden: Getroffen wird waehrend
 * der Fahrt, nicht erst danach.
 *
 * ## Einstieg in den Fahrmodus
 * Der Knopf „Fahrmodus" bekommt eine eigene Zeile ueber Pause/Beenden statt
 * eines Platzes in einer der bestehenden Zeilen. Die Kopfzeile ist auf einem
 * 360-dp-Geraet mit Zustandstext und Punktzahl bereits voll, und ein dritter
 * Knopf in der unteren Zeile haette alle drei auf ein Drittel der Breite
 * gedrueckt — ausgerechnet „Beenden" waere damit schmaler und schwerer zu
 * treffen geworden.
 *
 * Startet die Aufzeichnung durch eine Nutzeraktion in dieser Sitzung, ist
 * der Fahrmodus schon offen, bevor diese Leiste ueberhaupt zu sehen ist
 * (siehe `runRecording()` in `MapScreen.kt`) — die Leiste ist dann der
 * Rueckweg von dort, nicht der Einstieg. Der Knopf bleibt trotzdem: Er ist der
 * einzige Weg zurueck in den Fahrmodus, wenn die Fahrerin ihn selbst
 * verlassen hat (`onClose` in `RideModeScreen.kt`), oder wenn die
 * Aufzeichnung schon lief, bevor dieser Screen ueberhaupt neu aufgebaut wurde
 * — etwa nach einem Neustart der App bei laufendem Vordergrunddienst, wo
 * kein `rideMode = true` je gesetzt wurde.
 */
@Composable
internal fun LiveRecordingCard(
    speedKmh: Double?,
    distanceKm: Double,
    elapsedS: Int,
    ascentM: Double,
    pointCount: Int,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onOpenRideMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CardPadding,
                vertical = OverlayCardPaddingVertical,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordDot(color = MaterialTheme.colorScheme.error, size = 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (paused) "Pausiert" else "Aufzeichnung läuft",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$pointCount Punkte",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = speedKmh?.let { formatOneDecimalDe(it) } ?: "–",
                    label = "km/h",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = formatKmDe(distanceKm),
                    label = "km",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = formatDuration(elapsedS),
                    label = "Zeit",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    big = true,
                    value = "${ascentM.roundToInt()}",
                    label = "Hm ↑",
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                text = "Fahrmodus",
                onClick = onOpenRideMode,
                modifier = Modifier.fillMaxWidth(),
                leading = {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            Row {
                NeutralButton(
                    onClick = onTogglePause,
                    modifier = Modifier.weight(1f),
                ) {
                    if (paused) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (paused) "Weiter" else "Pause")
                }
                Spacer(Modifier.width(OverlayGap))
                DangerButton(
                    text = "Beenden",
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Statistik-Karte der ausgewaehlten Tour, inklusive Hoehenprofil.
 *
 * @param onHoverPoint meldet den im Hoehenprofil abgelesenen Punkt nach oben,
 *   damit der Screen ihn auf der Karte markieren kann.
 */
@Composable
internal fun RideCard(
    ride: Ride,
    navigating: Boolean,
    onNavigate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onHoverPoint: (TrackPoint?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = ride.stats
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        // Rechts 8 dp statt [CardPadding]: der Schliessen-IconButton bringt
        // seinen eigenen Beruehrungsrand mit — dasselbe Zugestaendnis wie in
        // der Tourenkarte des Touren-Tabs.
        Column(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ride.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Auswahl aufheben")
                }
            }
            Row(modifier = Modifier.padding(end = 8.dp)) {
                Metric(
                    modifier = Modifier.weight(1f),
                    value = formatKmDe(stats.distanceKm),
                    label = "km",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    value = formatDuration(stats.durationS),
                    label = "Dauer",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    value = stats.avgSpeedKmh?.let { formatOneDecimalDe(it) } ?: "–",
                    label = "Ø km/h",
                )
                Metric(
                    modifier = Modifier.weight(1f),
                    value = "${stats.ascentM.roundToInt()}",
                    label = "Hm ↑",
                )
            }
            Spacer(Modifier.height(8.dp))
            ElevationProfile(
                points = ride.points,
                modifier = Modifier.padding(end = 8.dp),
                lineColor = MaterialTheme.colorScheme.primary,
                onHover = onHoverPoint,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = if (navigating) "Navigation läuft" else "Navigieren",
                    onClick = onNavigate,
                    enabled = !navigating,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "Als GPX teilen")
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Tour löschen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Leiste waehrend der Navigation (`_NavBar` im Original). */
@Composable
internal fun NavigationCard(
    label: String,
    remainingKm: Double,
    doneKm: Double?,
    offRoute: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${formatKmDe(remainingKm)} km übrig",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (doneKm == null) {
                        label
                    } else {
                        "$label · ${formatKmDe(doneKm)} km geschafft"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (offRoute) {
                    Text(
                        text = "Abseits der Route",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = onStop) { Text("Beenden") }
        }
    }
}

/** Fortschritt des Offline-Downloads (`_DownloadProgress` im Original). */
@Composable
internal fun DownloadProgressCard(
    done: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = CardPadding,
                vertical = OverlayCardPaddingVertical,
            ),
        ) {
            Text(
                text = "Lade Kacheln … $done/$total",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/**
 * Stehender Hinweis auf der Karte fuer eine Standort-Entscheidung, die eine
 * Handlung braucht — verweigerte Freigabe oder „Ungefähr“ statt „Genau“ beim
 * Aufzeichnen (siehe `MapScreen.kt`: `locationDeniedAction`,
 * `impreciseLocationNotice`).
 *
 * Beide liefen bis hierher als 4-Sekunden-Snackbar durch
 * [de.trailscape.app.ui.AppViewModel.messages] — und waren verschwunden,
 * bevor irgendeine Entscheidung fiel: Die Ablehnung blieb Ablehnung, „Genau“
 * blieb ungewaehlt, die Nutzerin sah eine Zeile, die schon wieder weg war,
 * wenn sie reagieren wollte. Das ist genau die Regel, die der Touren-Tab beim
 * Undo-Loeschen schon gefunden hat (siehe `TourList.kt`): Snackbar nur fuer
 * Bestaetigungen, alles mit Handlungsbedarf wird ein stehender Zustand an der
 * Stelle des Geschehens mit der Aktion daneben — hier auf der Karte, wo die
 * verweigerte Aktion ausgeloest wurde.
 *
 * Die `caution`-Signalfarbe (nicht `danger`): Eine verweigerte Freigabe ist
 * kein Fehler der App, sondern eine noch offene Entscheidung der Nutzerin —
 * „Erneut fragen" fragt einfach noch einmal.
 *
 * @param onRetry loest denselben `withPermissions`-Pfad mit der gemerkten
 *   Absicht noch einmal aus. Liegt die Freigabe danach vor, raeumt der
 *   Aufrufer den zugehoerigen Zustand ab und diese Karte verschwindet von
 *   selbst — sie fragt selbst nie nach dem Berechtigungsstatus.
 */
@Composable
internal fun LocationPermissionNotice(
    text: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signals = LocalSignalColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(
                start = CardPadding,
                top = OverlayCardPaddingVertical,
                end = 8.dp,
                bottom = OverlayCardPaddingVertical,
            ),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                NoticeBox(
                    icon = Icons.Filled.Info,
                    color = signals.caution,
                    text = text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Hinweis schließen")
                }
            }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Erneut fragen")
            }
        }
    }
}

// --------------------------------------------------------------- Bedienteile

/**
 * Pillenfoermiger Knopf mit Text (oben links: „Route planen"). Die Pille erbt
 * ihre Form von `shapes.small`, die inaktive Flaeche ist die One-UI-Karte
 * (`surfaceContainerLow`); nur der aktive Zustand faerbt sich mit der
 * Kartenfarbe der Funktion.
 */
@Composable
internal fun MapPillButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (active) activeColor else MaterialTheme.colorScheme.surfaceContainerLow
    val foreground = if (active) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = foreground,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Runder Knopf mit Symbol (Suche, Kartenstil, Offline). */
@Composable
internal fun MapCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        shape = CircleShape,
        color = if (active) RouteBlue else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Der grosse Aufnahmeknopf: gruener Kreis (Start) bzw. rotes Quadrat (Stopp)
 * ueber der Flaeche des Knopfs. Seit `material-icons-extended` eingebunden
 * ist, kommen Start- und Stopp-Symbol als echte Vektor-Icons statt als
 * selbst gezeichnete Formen. Groesse bleibt der 56-dp-Knopf; die Farben
 * kommen seit dem One-UI-Umzug aus dem Schema (`primary`/`error`) statt aus
 * den Kachelfarben — auf der hellen wie dunklen Kartenflaeche geprueft.
 */
@Composable
internal fun RecordButton(
    recording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (recording) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Aufzeichnung beenden",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Aufzeichnung starten",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/**
 * Runder Knopf „Meine Position", gleiche Groesse wie [RecordButton].
 *
 * Er ist zugleich die Anzeige und der Rueckweg fuer „Karte folgt mir": Solange
 * die Karte der eigenen Position folgt, ist er gefuellt gruen; sobald die
 * Nutzerin die Karte selbst verschoben hat (etwa um beim Navigieren
 * vorauszuschauen), wird er blass — ein Tipp holt sie zurueck und schaltet das
 * Folgen wieder ein. Vorher zog es die Karte spaetestens nach zwei Sekunden
 * kommentarlos zurueck, und ein Schalter dafuer fehlte ganz.
 */
@Composable
internal fun LocateButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    following: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp),
        shape = CircleShape,
        color = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (following) Color.White else MaterialTheme.colorScheme.primary,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = if (following) {
                    "Meine Position – die Karte folgt dir"
                } else {
                    "Meine Position – die Karte folgt dir nicht mehr"
                },
                tint = if (following) Color.White else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Kleiner Punkt — dient der Live-Leiste als „Aufzeichnung läuft"-Indikator. */
@Composable
private fun RecordDot(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawCircle(color = color)
    }
}

/**
 * Gefuellter Knopf fuer die Hauptaktion eines Panels, mit optionalem Symbol
 * davor.
 *
 * Als echter Material-`Button` erbt er alles aus dem One-UI-Theme: die Pille
 * von `shapes.small`, `primary`/`onPrimary` als Farben und `labelLarge` fuer
 * die Beschriftung. Die Hoehe folgt One UI statt Material: Contained-Knoepfe
 * sind dort durchgaengig 48 dp hoch. Das Symbol ist dieselbe Zutat wie in
 * [DangerButton] und [de.trailscape.app.ui.components.NeutralButton].
 */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Gefuellter Knopf in der semantischen Warnfarbe (`error`) mit optionalem
 * Symbol davor — Form und Typografie erbt er wie [PrimaryButton] aus dem
 * Theme.
 */
@Composable
internal fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

