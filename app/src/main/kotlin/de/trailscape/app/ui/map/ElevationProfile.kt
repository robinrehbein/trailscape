package de.trailscape.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.formatKmDe
import de.trailscape.core.TrackPoint
import de.trailscape.core.haversineM
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Hoehenprofil einer Tour oder einer geplanten Route.
 *
 * ## Kein Port, sondern neu
 * Die Flutter-App hatte auf dem Karten-Screen **kein** Hoehenprofil — sie zeigte
 * nur die Zahl „Hm ↑" in der Statistik-Karte. Diese Darstellung ist also neu
 * und haelt sich an das, was die uebrigen Kennzahlen schon liefern: Distanz auf
 * der X-Achse, Hoehe auf der Y-Achse, dieselben Farben wie die Linie auf der
 * Karte.
 *
 * ## Interaktion
 * Tippen oder Ziehen setzt den Ablesepunkt; die Werte stehen als Text ueber dem
 * Diagramm, und der Punkt wird ueber [onHover] nach oben gereicht — der
 * Karten-Screen setzt dort einen Marker auf die Karte, sodass sich Profil und
 * Karte gegenseitig erklaeren. Loslassen loescht den Punkt wieder.
 *
 * ## Aufloesung
 * Eine lange Tour hat schnell zehntausende Punkte; gezeichnet werden hoechstens
 * [MAX_SAMPLES] Stuetzstellen (gleichmaessig ueber die Punktliste verteilt).
 * Das kostet bei jeder Auswahl einmalig O(n) und danach nichts mehr.
 */
@Composable
internal fun ElevationProfile(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    lineColor: Color = GravelGreen,
    onHover: (TrackPoint?) -> Unit = {},
) {
    val samples = remember(points) { buildElevationSamples(points) }
    var hoverIndex by remember(samples) { mutableIntStateOf(-1) }

    LaunchedEffect(samples, hoverIndex) {
        onHover(samples.getOrNull(hoverIndex)?.point)
    }

    if (samples.size < 2) {
        Text(
            text = "Keine Höhendaten für diese Tour.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    val minEle = samples.minOf { it.eleM }
    val maxEle = samples.maxOf { it.eleM }
    val totalKm = samples.last().distanceKm
    val span = max(maxEle - minEle, 1.0)
    val hovered = samples.getOrNull(hoverIndex)

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Der Ring um den Ablesepunkt liegt auf der Kartenflaeche, nicht auf den
    // Kacheln — vorher hart `Color.White`, im Dunkelmodus ein greller Fleck.
    val markerRingColor = MaterialTheme.colorScheme.surface

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Höhenprofil",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (hovered != null) {
                    "${formatKmDe(hovered.distanceKm)} km · ${hovered.eleM.roundToInt()} m"
                } else {
                    "${minEle.roundToInt()}–${maxEle.roundToInt()} m"
                },
                style = MaterialTheme.typography.labelMedium,
                color = labelColor,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(top = 4.dp)
                .pointerInput(samples) {
                    detectTapGestures(
                        onPress = { offset ->
                            hoverIndex = indexFor(offset.x, size.width, samples.size)
                            tryAwaitRelease()
                            hoverIndex = -1
                        },
                    )
                }
                .pointerInput(samples) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            hoverIndex = indexFor(offset.x, size.width, samples.size)
                        },
                        onDragEnd = { hoverIndex = -1 },
                        onDragCancel = { hoverIndex = -1 },
                    ) { change, _ ->
                        hoverIndex = indexFor(change.position.x, size.width, samples.size)
                    }
                },
        ) {
            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@Canvas

            fun xOf(index: Int): Float =
                if (samples.size < 2) 0f else width * index / (samples.size - 1).toFloat()

            fun yOf(ele: Double): Float =
                (height - ((ele - minEle) / span).toFloat() * height).coerceIn(0f, height)

            // Grundlinie und Mittellinie als leise Orientierung.
            drawLine(gridColor, Offset(0f, height), Offset(width, height), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, height / 2), Offset(width, height / 2), strokeWidth = 1f)

            val area = Path().apply {
                moveTo(0f, height)
                samples.forEachIndexed { index, sample -> lineTo(xOf(index), yOf(sample.eleM)) }
                lineTo(width, height)
                close()
            }
            drawPath(area, color = lineColor.copy(alpha = 0.18f))

            val line = Path().apply {
                samples.forEachIndexed { index, sample ->
                    val x = xOf(index)
                    val y = yOf(sample.eleM)
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(line, color = lineColor, style = Stroke(width = 2.5f))

            if (hovered != null && hoverIndex >= 0) {
                val x = xOf(hoverIndex)
                val y = yOf(hovered.eleM)
                drawLine(lineColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1.5f)
                drawCircle(markerRingColor, radius = 5.5f, center = Offset(x, y))
                drawCircle(lineColor, radius = 4f, center = Offset(x, y))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("0 km", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(
                text = "${formatKmDe(totalKm)} km",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
        }
    }
}

/** Eine Stuetzstelle des Profils. */
internal data class ElevationSample(
    val distanceKm: Double,
    val eleM: Double,
    val point: TrackPoint,
)

private fun indexFor(x: Float, width: Int, count: Int): Int {
    if (count < 2 || width <= 0) return -1
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/**
 * Baut die Stuetzstellen: kumulierte Distanz (Haversine, wie `:core` rechnet)
 * gegen Hoehe. Punkte ohne Hoehe uebernehmen den zuletzt bekannten Wert; hat
 * die Tour gar keine Hoehen, kommt eine leere Liste zurueck.
 */
internal fun buildElevationSamples(points: List<TrackPoint>): List<ElevationSample> {
    if (points.size < 2 || points.none { it.ele != null }) return emptyList()

    val step = max(1, points.size / MAX_SAMPLES)
    val samples = mutableListOf<ElevationSample>()
    var distanceM = 0.0
    var lastEle = points.firstOrNull { it.ele != null }?.ele ?: 0.0

    points.forEachIndexed { index, point ->
        if (index > 0) {
            distanceM += haversineM(points[index - 1], point)
        }
        point.ele?.let { lastEle = it }
        if (index % step == 0 || index == points.lastIndex) {
            samples += ElevationSample(
                distanceKm = distanceM / 1000,
                eleM = lastEle,
                point = point,
            )
        }
    }
    return samples
}

private const val MAX_SAMPLES = 240
