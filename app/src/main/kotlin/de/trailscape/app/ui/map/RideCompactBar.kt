package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.record.RecordingRepository
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.app.ui.theme.OverlayGap
import de.trailscape.core.formatDuration
import kotlin.math.roundToInt

/**
 * # Kompaktleiste — die Fahrwerte auf der Kartenseite des Fahrmodus
 *
 * Liegt am unteren Rand der NAVI_KARTE-Seite (siehe `rideModeSeite` in
 * `MapScreen.kt`): dieselbe laufende Aufzeichnung wie im grossen Fahrmodus
 * (`RideModeScreen.kt`), nur so flach, dass die Karte die Hauptrolle behaelt.
 * Eine Zeile Werte — Tempo · gefahrene km · Hoehenmeter · Fahrzeit, dazu der
 * **Puls**, wenn eine gekoppelte Uhr live liefert (dieselbe Regel wie die
 * Puls-Kachel des Fahrmodus: ohne Uhr erscheint gar nichts, die uebrigen
 * Werte behalten ihre Plaetze) — und darunter die drei Handgriffe:
 * Pause/Weiter, Beenden und rechts „Daten" als Rueckweg zur grossen
 * Datenseite.
 *
 * **Beenden fragt auch hier ueber Kreuz zurueck**: Es ist dieselbe
 * [StopConfirmation] wie im Fahrmodus (dort begruendet — der Fehlgriff auf
 * Schotter darf keine Tour kosten), sie ersetzt fuer die Rueckfrage die
 * ganze Knopfzeile.
 *
 * Der **Auto-Pause-Zustand** ist sichtbar: Statt des Tempos steht dann
 * „Auto-Pause" (bzw. „Pause" bei einer manuellen) — im Stand ist das Tempo
 * ohnehin null und der Zustand die eigentliche Auskunft. Die Zahlen laufen
 * in Tabellenziffern (`tnum`), damit die Leiste beim Sekundentakt der
 * Fahrzeit nicht zappelt.
 *
 * Die reinen Textentscheidungen ([kompaktTempoWert], [kompaktTempoLabel],
 * [kompaktTempoSpoken]) stehen unten ohne Compose-Bezug und sind in
 * `RideCompactBarTextTest` getestet.
 */
@Composable
internal fun RideCompactBar(
    speedKmh: Double?,
    distanceKm: Double,
    ascentM: Double,
    elapsedS: Int,
    paused: Boolean,
    autoPaused: Boolean,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onShowData: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Die Rueckfrage lebt in der Leiste selbst (wie `confirmStop` im
    // Fahrmodus-Fenster): Sie ist nur interessant, solange die Leiste steht.
    var confirmStop by remember { mutableStateOf(false) }

    // Der Puls direkt aus dem Repository statt als Parameter — dasselbe
    // Muster samt Begruendung wie im Fahrmodus (`RideModeScreen.kt`):
    // `watchConnected` als Bedingung, denn eine veraltete Herzfrequenz einer
    // getrennten Uhr waere ein stilles Falschanzeigen.
    val heartRateBpm by RecordingRepository.heartRateBpm.collectAsStateWithLifecycle()
    val watchConnected by RecordingRepository.watchConnected.collectAsStateWithLifecycle()
    val pulsBpm = heartRateBpm.takeIf { watchConnected }

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
            Row(verticalAlignment = Alignment.Bottom) {
                CompactValue(
                    modifier = Modifier.weight(1.2f),
                    value = kompaktTempoWert(speedKmh, paused, autoPaused),
                    label = kompaktTempoLabel(paused),
                    spoken = kompaktTempoSpoken(speedKmh, paused, autoPaused),
                    // Der Pausen-Zustand traegt Wortlaenge statt Ziffern —
                    // kleiner setzen, damit „Auto-Pause" nicht abschneidet.
                    kleiner = paused,
                )
                CompactValue(
                    modifier = Modifier.weight(1f),
                    value = formatKmDe(distanceKm),
                    label = "km",
                    spoken = "Distanz ${formatKmDe(distanceKm)} Kilometer",
                )
                CompactValue(
                    modifier = Modifier.weight(1f),
                    value = "${ascentM.roundToInt()}",
                    label = "Hm ↑",
                    spoken = "${ascentM.roundToInt()} Höhenmeter bergauf",
                )
                CompactValue(
                    modifier = Modifier.weight(1.2f),
                    value = formatDuration(elapsedS),
                    label = "Fahrzeit",
                    spoken = "Fahrzeit ${formatDuration(elapsedS)}",
                )
                if (pulsBpm != null) {
                    CompactValue(
                        modifier = Modifier.weight(1f),
                        value = "$pulsBpm",
                        label = "bpm",
                        spoken = "Puls $pulsBpm Schläge pro Minute",
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (confirmStop) {
                StopConfirmation(
                    onCancel = { confirmStop = false },
                    onConfirm = {
                        confirmStop = false
                        onStop()
                    },
                )
            } else {
                Row {
                    NeutralButton(
                        onClick = onTogglePause,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (paused) "Weiter" else "Pause")
                    }
                    Spacer(Modifier.width(OverlayGap))
                    DangerButton(
                        text = "Beenden",
                        onClick = { confirmStop = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(OverlayGap))
                    PrimaryButton(
                        text = "Daten",
                        onClick = onShowData,
                        modifier = Modifier.weight(1f),
                        leading = {
                            Icon(
                                Icons.Filled.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Ein Wert der Kompaktleiste: fette Zahl in Tabellenziffern, kleine
 * Beschriftung darunter — dieselbe Stat-Grammatik wie [Metric], nur eine
 * Stufe kleiner und mit ganzem Vorlesesatz ([clearAndSetSemantics], das
 * `BigValue`-Muster aus dem Fahrmodus).
 */
@Composable
private fun CompactValue(
    value: String,
    label: String,
    spoken: String,
    modifier: Modifier = Modifier,
    kleiner: Boolean = false,
) {
    Column(modifier = modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (kleiner) CompactValueSizeKlein else CompactValueSize,
            lineHeight = CompactValueSize * 1.1f,
            fontWeight = FontWeight.Bold,
            // Tabellenziffern: gleiche Ziffernbreite, damit die Sekunden der
            // Fahrzeit die Nachbarwerte nicht im Takt verschieben.
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------- reine Darstellungslogik
// Getestet in `RideCompactBarTextTest` — bewusst ohne Compose und Android.

/**
 * Der Tempo-Platz der Leiste: pausiert zeigt er den Zustand („Auto-Pause"
 * bzw. „Pause") statt einer Null — im Stand ist der Zustand die Auskunft.
 * Unbekanntes Tempo bei laufender Aufzeichnung bleibt der Strich.
 */
internal fun kompaktTempoWert(speedKmh: Double?, paused: Boolean, autoPaused: Boolean): String =
    when {
        paused && autoPaused -> "Auto-Pause"
        paused -> "Pause"
        else -> speedKmh?.let { formatOneDecimalDe(it) } ?: "–"
    }

/** Beschriftung unter dem Tempo-Platz — pausiert traegt der Wert selbst den Zustand. */
internal fun kompaktTempoLabel(paused: Boolean): String =
    if (paused) "Aufzeichnung" else "km/h"

/** Vorlesesatz des Tempo-Platzes (dasselbe Muster wie `BigValue.spoken`). */
internal fun kompaktTempoSpoken(speedKmh: Double?, paused: Boolean, autoPaused: Boolean): String =
    when {
        paused && autoPaused -> "Aufzeichnung in Auto-Pause"
        paused -> "Aufzeichnung pausiert"
        else -> speedKmh
            ?.let { "Tempo ${formatOneDecimalDe(it)} Kilometer pro Stunde" }
            ?: "Tempo unbekannt"
    }

/** Schriftgroesse der Kompaktwerte — gross genug fuer den Lenker-Blick, flach genug fuer die Karte. */
private val CompactValueSize = 24.sp

/** Kleinere Stufe fuer Wort-Werte („Auto-Pause"), damit nichts abschneidet. */
private val CompactValueSizeKlein = 18.sp
