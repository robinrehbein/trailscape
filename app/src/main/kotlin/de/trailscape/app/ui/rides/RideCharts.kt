package de.trailscape.app.ui.rides

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.core.RideCurve

/**
 * Die Diagramme der Tour-Detailansicht: Tempo- und Pulskurve ueber die
 * Distanz.
 *
 * ## Warum eine eigene Zeichenflaeche und kein zweites Hoehenprofil
 * Das **Hoehenprofil** wird nicht nachgebaut: Die Detailansicht benutzt
 * `ui/map/ElevationProfile.kt` unveraendert weiter (samt dessen Ablesepunkt und
 * dessen Stuetzstellenrechnung). Tempo und Puls passen dort aber nicht hinein —
 * jenes Composable rechnet fest mit `TrackPoint.ele` und gibt den abgelesenen
 * *Punkt* nach oben, damit die Karte ihn markieren kann. Eine Verallgemeinerung
 * haette dessen Signatur und damit den Karten-Screen mitgeaendert, ohne dass
 * viel gemeinsamer Code uebrig bliebe; hier liegt die Rechnung ohnehin schon
 * fertig in `:core` ([RideCurve]) und uebrig bleibt reines Zeichnen.
 *
 * ## Stil
 * Aufbau wie das Hoehenprofil (Kopfzeile mit Wertebereich, 88 dp hohe Flaeche,
 * Distanzbeschriftung darunter), Zeichenweise wie `ui/training/PmcChart.kt`
 * (Grundlinie plus Polylinie, Strichstaerken in dp statt in rohen Pixeln).
 * Alle Farben kommen vom Aufrufer aus dem Theme — ein festes Gruen waere im
 * Dunkelmodus auf der dunklen Kartenflaeche kaum zu sehen.
 */
@Composable
internal fun RideCurveChart(
    title: String,
    curve: RideCurve,
    lineColor: Color,
    formatValue: (Double) -> String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    if (curve.samples.size < 2) {
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = "${formatValue(curve.minValue)}–${formatValue(curve.maxValue)}",
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
                .padding(top = 4.dp),
        ) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            val samples = curve.samples
            val startKm = samples.first().distanceKm
            val spanKm = samples.last().distanceKm - startKm
            val spanValue = curve.maxValue - curve.minValue

            // Faellt die Distanzspanne weg (kann bei stehender Aufzeichnung
            // vorkommen), verteilen sich die Stuetzstellen gleichmaessig —
            // sonst laegen alle auf x = 0.
            fun xOf(index: Int): Float = if (spanKm <= 0.0) {
                width * index / (samples.size - 1).toFloat()
            } else {
                width * ((samples[index].distanceKm - startKm) / spanKm).toFloat()
            }

            // Ohne Wertespanne (konstanter Puls) laeuft die Linie mittig statt
            // auf der Grundlinie zu kleben.
            fun yOf(value: Double): Float = if (spanValue <= 0.0) {
                height / 2
            } else {
                (height - ((value - curve.minValue) / spanValue).toFloat() * height)
                    .coerceIn(0f, height)
            }

            drawLine(
                color = gridColor,
                start = Offset(0f, height - 0.5f),
                end = Offset(width, height - 0.5f),
                strokeWidth = GridStrokeWidth.toPx(),
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, height / 2),
                end = Offset(width, height / 2),
                strokeWidth = GridStrokeWidth.toPx(),
            )

            if (filled) {
                val area = Path().apply {
                    moveTo(xOf(0), height)
                    samples.indices.forEach { index -> lineTo(xOf(index), yOf(samples[index].value)) }
                    lineTo(xOf(samples.lastIndex), height)
                    close()
                }
                drawPath(area, color = lineColor.copy(alpha = 0.18f))
            }

            val line = Path().apply {
                samples.indices.forEach { index ->
                    val x = xOf(index)
                    val y = yOf(samples[index].value)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(
                path = line,
                color = lineColor,
                style = Stroke(
                    width = LineStrokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0 km", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(
                text = "${formatKmDe(curve.totalKm)} km",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }
    }
}

/** Hoehe der Zeichenflaeche — dieselbe wie im Hoehenprofil, damit die Karten gleich hoch wirken. */
private val ChartHeight = 88.dp

private val GridStrokeWidth = 1.dp
private val LineStrokeWidth = 2.dp
