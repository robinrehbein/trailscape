package de.trailscape.app.ui.training

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Zeichnet CTL (Fitness) und ATL (Ermuedung) auf gemeinsamer Skala.
 *
 * 1:1-Portierung von `_PmcSparklinePainter` (`lib/screens/training_screen.dart`,
 * 62 Zeilen `CustomPainter`): eine Nulllinie plus zwei Polylinien, bewusst ohne
 * Fuellung und ohne separat markierten aktuellen Punkt — genau das leistet
 * das Original, mehr braucht die Mini-Visualisierung nicht (siehe KDoc dort).
 * Der Abstand beider Kurven ist die Form (TSB), die daneben als Zahl steht.
 */
@Composable
fun PmcSparkline(
    ctl: List<Double>,
    atl: List<Double>,
    ctlColor: Color,
    atlColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (ctl.size < 2) {
            return@Canvas
        }
        var maxValue = 1.0
        for (v in ctl) if (v > maxValue) maxValue = v
        for (v in atl) if (v > maxValue) maxValue = v

        drawLine(
            color = gridColor,
            start = Offset(0f, size.height - 0.5f),
            end = Offset(size.width, size.height - 0.5f),
            strokeWidth = 1f,
        )

        fun drawSeries(values: List<Double>, color: Color, widthDp: Float) {
            if (values.size < 2) return
            val path = Path()
            val lastIndex = values.size - 1
            for (i in values.indices) {
                val x = size.width * i / lastIndex
                val y = size.height - (values[i] / maxValue).toFloat() * size.height
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = widthDp.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        drawSeries(atl, atlColor, 1.5f)
        drawSeries(ctl, ctlColor, 2f)
    }
}
