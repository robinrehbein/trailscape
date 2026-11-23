package de.trailscape.app.ui.indoor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Kleinste Hoehenspanne, die das Profil darstellt — flache Strecken sollen nicht wie Gebirge aussehen. */
private const val MIN_ELEVATION_SPAN_M = 20f

/**
 * Das Hoehenprofil der Strecke mit der Position des Fahrers (und des Geists).
 *
 * Eigene Zeichnung aus [IndoorProfileUi]: Flaeche und Linie ueber den
 * Laufmetern, ein Punkt bei [routeDistM] (Meter auf der Strecke, in Runden
 * bereits auf die Strecke zurueckgefuehrt). Der Geist ist ein kleinerer Punkt
 * in der Zweitfarbe bei [ghostRouteDistM].
 *
 * @param description Text fuer die Bedienungshilfe („Höhenprofil, du bist bei 3,2 km").
 */
@Composable
fun IndoorProfileCanvas(
    profile: IndoorProfileUi,
    routeDistM: Double,
    ghostRouteDistM: Double?,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.20f)
    val riderRing = MaterialTheme.colorScheme.surface
    val ghostColor = MaterialTheme.colorScheme.tertiary
    val gridColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        val n = minOf(profile.distM.size, profile.eleM.size)
        if (w <= 0f || h <= 0f || n < 2 || profile.lengthM <= 0.0) return@Canvas

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until n) {
            lo = minOf(lo, profile.eleM[i])
            hi = maxOf(hi, profile.eleM[i])
        }
        val span = maxOf(hi - lo, MIN_ELEVATION_SPAN_M)
        // Luft ueber und unter der Linie, damit Punkt und Strich nicht am Rand kleben.
        val pad = 6.dp.toPx()
        val usableH = h - 2 * pad
        val mid = (hi + lo) / 2f
        fun xAt(distM: Double): Float = (distM / profile.lengthM).toFloat().coerceIn(0f, 1f) * w
        fun yAt(eleM: Float): Float = pad + usableH * (1f - ((eleM - (mid - span / 2f)) / span))

        val line = Path()
        val area = Path()
        for (i in 0 until n) {
            val x = xAt(profile.distM[i].toDouble())
            val y = yAt(profile.eleM[i])
            if (i == 0) {
                line.moveTo(x, y)
                area.moveTo(x, h)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(xAt(profile.distM[n - 1].toDouble()), h)
        area.close()

        drawLine(gridColor, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1.dp.toPx())
        drawPath(area, color = fillColor)
        drawPath(line, color = lineColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        fun eleAtDist(distM: Double): Float {
            val d = distM.coerceIn(0.0, profile.lengthM).toFloat()
            var i = 1
            while (i < n - 1 && profile.distM[i] < d) i++
            val d0 = profile.distM[i - 1]
            val d1 = profile.distM[i]
            val t = if (d1 > d0) ((d - d0) / (d1 - d0)).coerceIn(0f, 1f) else 0f
            return profile.eleM[i - 1] + (profile.eleM[i] - profile.eleM[i - 1]) * t
        }

        if (ghostRouteDistM != null) {
            drawCircle(ghostColor, radius = 4.dp.toPx(), center = Offset(xAt(ghostRouteDistM), yAt(eleAtDist(ghostRouteDistM))))
        }
        val rx = xAt(routeDistM)
        val ry = yAt(eleAtDist(routeDistM))
        drawCircle(riderRing, radius = 7.dp.toPx(), center = Offset(rx, ry))
        drawCircle(lineColor, radius = 5.dp.toPx(), center = Offset(rx, ry))
    }
}
