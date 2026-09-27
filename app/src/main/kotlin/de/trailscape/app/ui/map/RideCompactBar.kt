package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.core.LiveSensorAnzeige
import de.trailscape.app.ui.components.HoldToEndButton
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
 * **Puls**, wenn Uhr oder Pulsgurt live liefern, und die **Leistung**, wenn
 * ein Leistungsmesser gekoppelt ist (dieselben Regeln wie die Sensorzeile
 * des Fahrmodus, siehe `rememberLiveSensorAnzeige`: ohne Quelle erscheint
 * gar nichts, ein stiller Sensor zeigt den Strich, die uebrigen Werte
 * behalten ihre Plaetze). Die **Trittfrequenz** steht bewusst nur im
 * Fahrmodus: Mit sieben Spalten wuerde die Leiste auf normal breiten
 * Telefonen die Fahrzeit abschneiden. — Darunter die drei Handgriffe:
 * Pause/Weiter, Beenden und rechts „Daten" als Rueckweg zur grossen
 * Datenseite.
 *
 * **Beenden fragt auch hier ueber Kreuz zurueck**: Es ist dieselbe
 * [de.trailscape.app.ui.components.HoldToEndButton] wie im Fahrmodus: Beenden
 * nur durch Halten — der Fehlgriff auf Schotter darf keine Tour kosten.
 *
 * Der **Auto-Pause-Zustand** ist sichtbar: Statt des Tempos steht dann
 * „Pause", darunter „automatisch" bzw. „Aufzeichnung" bei einer manuellen — im Stand ist das Tempo
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
    // Die Live-Sensorwerte — dieselbe Quelle wie im Fahrmodus; Parameter nur
    // fuer Screenshot-Tests.
    sensoren: LiveSensorAnzeige = rememberLiveSensorAnzeige(),
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
            Row(verticalAlignment = Alignment.Bottom) {
                CompactValue(
                    modifier = Modifier.weight(1.2f),
                    value = kompaktTempoWert(speedKmh, paused, autoPaused),
                    label = kompaktTempoLabel(paused, autoPaused),
                    spoken = kompaktTempoSpoken(speedKmh, paused, autoPaused),
                    // Der Pausen-Zustand traegt ein Wort statt Ziffern —
                    // eine Stufe kleiner, damit es neben den Zahlen nicht laut wird.
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
                sensoren.puls?.let { kachel ->
                    val t = sensorKachelText(SensorKachelArt.PULS, kachel, label = "bpm", stillLabelKurz = true)
                    CompactValue(
                        modifier = Modifier.weight(1f),
                        value = t.wert,
                        label = t.label,
                        spoken = t.spoken,
                    )
                }
                sensoren.leistung?.let { kachel ->
                    val t = sensorKachelText(
                        SensorKachelArt.LEISTUNG,
                        kachel,
                        label = stringResource(R.string.ble_compact_power_label),
                        stillLabelKurz = true,
                    )
                    CompactValue(
                        modifier = Modifier.weight(1f),
                        value = t.wert,
                        label = t.label,
                        spoken = t.spoken,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Drei gleich gebaute Pillen — gleiche Hoehe, gleiches Innenmass,
            // Symbol plus ein Wort. Vorher sassen hier drei verschiedene
            // Knopfarten mit 20 dp Innenrand nebeneinander, und auf normal
            // breiten Telefonen brach „Pause" um und „Daten" wurde zu „Da…".
            Row {
                KompaktAktion(
                    icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    label = if (paused) "Weiter" else "Pause",
                    description = if (paused) "Aufzeichnung fortsetzen" else "Aufzeichnung pausieren",
                    // `secondaryContainer` wie der „Karte"-Knopf des Fahrmodus —
                    // die Kartenflaeche selbst ist schon hell, ein graues
                    // Neutral verschwand darauf.
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = onTogglePause,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(OverlayGap))
                HoldToEndButton(
                    onEnd = onStop,
                    label = "Beenden",
                    holdHint = "halten",
                    icon = Icons.Filled.Stop,
                    minHeight = KompaktAktionHoehe,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(OverlayGap))
                KompaktAktion(
                    icon = Icons.Filled.Speed,
                    label = "Daten",
                    description = "Zur Datenseite des Fahrmodus",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onShowData,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Eine Pille der Kompaktleiste: [KompaktAktionHoehe] hoch, schmaler
 * Innenrand, Symbol plus ein Wort in `labelLarge` — dieselbe Machart wie der
 * [HoldToEndButton] daneben, damit alle drei Knoepfe gleich aussehen.
 */
@Composable
private fun KompaktAktion(
    icon: ImageVector,
    label: String,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = KompaktAktionHoehe)
            .semantics { contentDescription = description },
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Hoehe aller drei Knoepfe der Kompaktleiste — die One-UI-Knopfhoehe. */
private val KompaktAktionHoehe = 48.dp

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
    // Rechts etwas Luft: geschrumpfte Werte sollen nicht an der Nachbarspalte kleben.
    Column(modifier = modifier.padding(end = 6.dp).clearAndSetSemantics { contentDescription = spoken }) {
        val groesse = if (kleiner) CompactValueSizeKlein else CompactValueSize
        // Schrumpfen statt abschneiden: Mit Puls und Watt teilen sich sechs
        // Spalten die Breite, und auf langen Touren wurde aus „112,8" sonst
        // „11…" — ein abgeschnittener Wert ist eine falsche Auskunft.
        BasicText(
            text = value,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = CompactValueSizeMin,
                maxFontSize = groesse,
                stepSize = 1.sp,
            ),
            // Tabellenziffern: gleiche Ziffernbreite, damit die Sekunden der
            // Fahrzeit die Nachbarwerte nicht im Takt verschieben.
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFeatureSettings = "tnum",
                fontSize = groesse,
                lineHeight = CompactValueSize * 1.1f,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
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
        paused -> "Pause"
        else -> speedKmh?.let { formatOneDecimalDe(it) } ?: "–"
    }

/**
 * Beschriftung unter dem Tempo-Platz — pausiert traegt der Wert selbst den
 * Zustand, die Beschriftung sagt, ob automatisch. „Auto-Pause" als Wert
 * passte auf normal breiten Telefonen nicht in die Spalte („Auto-Pau…").
 */
internal fun kompaktTempoLabel(paused: Boolean, autoPaused: Boolean = false): String =
    when {
        paused && autoPaused -> "automatisch"
        paused -> "Aufzeichnung"
        else -> "km/h"
    }

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

/** Untergrenze beim Schrumpfen langer Werte (siehe [CompactValue]). */
private val CompactValueSizeMin = 14.sp

/** Kleinere Stufe fuer Wort-Werte („Auto-Pause"), damit nichts abschneidet. */
private val CompactValueSizeKlein = 18.sp
