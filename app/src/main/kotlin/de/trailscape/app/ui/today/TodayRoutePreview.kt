package de.trailscape.app.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.ui.theme.LocalAtlasColors
import de.trailscape.core.AscentPreference
import de.trailscape.core.RouteTarget
import de.trailscape.core.ascentPreferenceLabel
import de.trailscape.core.formatGoalDuration
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Deterministische Hoehenmeterschaetzung fuer eine noch nicht gebaute
 * Tagesrunde aus Strecke und Hoehenwunsch (`RouteGenerator`-Baender:
 * flach ~5 m/km, moderat ~12 m/km, bergig ~20 m/km, auf 10 m gerundet).
 */
internal fun estimatedAscentM(distanceKm: Double, preference: AscentPreference): Int {
    if (!distanceKm.isFinite() || distanceKm <= 0.0) return 0
    val mPerKm = when (preference) {
        AscentPreference.FLACH -> 5.0
        AscentPreference.MODERAT -> 12.0
        AscentPreference.BERGIG -> 20.0
    }
    val tens = (distanceKm * mPerKm) / 10.0
    val maxTens = (Int.MAX_VALUE / 10).toLong()
    if (!tens.isFinite()) return maxTens.toInt() * 10
    return tens.roundToLong().coerceIn(0L, maxTens).toInt() * 10
}

/**
 * Geschaetzte Fahrzeit in Minuten fuer ein [RouteTarget] (mit Fallback ueber
 * `fallbackMinutes` oder `distanceKm / speedKmh`).
 */
internal fun routeDurationMinutes(target: RouteTarget, fallbackMinutes: Int? = null): Int {
    val fromDurationH = target.durationH
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { (it * 60.0).roundToLong().coerceIn(1L, Int.MAX_VALUE.toLong()).toInt() }
    if (fromDurationH != null) return fromDurationH
    if (fallbackMinutes != null && fallbackMinutes > 0) return fallbackMinutes
    if (!target.distanceKm.isFinite() || target.distanceKm <= 0.0) {
        return fallbackMinutes?.coerceAtLeast(1) ?: 1
    }
    val safeSpeed = target.speedKmh.takeIf { it.isFinite() && it > 0.0 } ?: 22.0
    return ((target.distanceKm / safeSpeed) * 60.0)
        .roundToLong()
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal data class TodayRouteMetrics(
    val distanceText: String,
    val ascentText: String,
    val durationText: String,
    val distanceValue: String,
    val distanceUnit: String,
    val ascentValue: String,
    val ascentUnit: String,
    val durationValue: String,
    val durationUnit: String,
    val statusLabel: String,
)

@Composable
internal fun rememberTodayRouteMetrics(
    distanceKm: Double,
    ascentM: Int,
    exactAscent: Boolean,
    durationMin: Int,
    ascentPreference: AscentPreference,
    restDay: Boolean = false,
    isEvent: Boolean = false,
): TodayRouteMetrics {
    val formats = LocalAppFormats.current
    val coreTexts = LocalCoreTexts.current
    val distanceValue = formats.km(distanceKm)
    val durationValue = formatGoalDuration(durationMin)
    val ascentValue = if (exactAscent) "$ascentM" else "~$ascentM"
    val ascentFull = stringResource(
        if (exactAscent) R.string.today_route_ascent_exact else R.string.today_route_ascent_approx,
        ascentM,
    )
    val ascentUnit = ascentFull.removePrefix(ascentValue).trim()
    val statusLabel = when {
        restDay -> stringResource(R.string.today_route_status_rest)
        isEvent -> stringResource(R.string.today_route_status_event)
        else -> ascentPreferenceLabel(ascentPreference, coreTexts)
    }
    return TodayRouteMetrics(
        distanceText = stringResource(R.string.today_route_distance_km, distanceValue),
        ascentText = ascentFull,
        durationText = stringResource(R.string.today_route_duration_hours, durationValue),
        distanceValue = distanceValue,
        distanceUnit = "km",
        ascentValue = ascentValue,
        ascentUnit = ascentUnit,
        durationValue = durationValue,
        durationUnit = "h",
        statusLabel = statusLabel,
    )
}

private val PreviewShape = RoundedCornerShape(14.dp)

/**
 * Topografische Streckenvorschau im Atlas-Stil (Hell- und Dunkelmodus) mit
 * waldgruener Rundschleife (`colorScheme.primary`) und Hoehenprofil.
 */
@Composable
internal fun TodayRoutePreview(
    ascentPreference: AscentPreference,
    restDay: Boolean = false,
    isEvent: Boolean = false,
    statusLabel: String? = null,
    spokenSummary: String? = null,
    showStatusBadge: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    val atlas = LocalAtlasColors.current
    val routeColor = theme.primary
    val contourColor = theme.outlineVariant.copy(alpha = 0.65f)
    val surfaceTop = theme.surfaceContainer
    val surfaceBottom = theme.primaryContainer.copy(alpha = 0.35f)
    val cd = spokenSummary?.let { stringResource(R.string.today_route_preview_cd, it) }

    Box(
        modifier = modifier
            .clip(PreviewShape)
            .background(Brush.verticalGradient(listOf(surfaceTop, surfaceBottom)))
            .let { base ->
                if (cd != null) base.clearAndSetSemantics { contentDescription = cd } else base
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Topografische Hoehenlinien im Hintergrund.
            val contourStroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
            val contour1 = Path().apply {
                moveTo(0f, h * 0.24f)
                cubicTo(w * 0.25f, h * 0.10f, w * 0.55f, h * 0.36f, w, h * 0.18f)
            }
            val contour2 = Path().apply {
                moveTo(0f, h * 0.52f)
                cubicTo(w * 0.32f, h * 0.40f, w * 0.68f, h * 0.64f, w, h * 0.44f)
            }
            val contour3 = Path().apply {
                moveTo(w * 0.08f, h * 0.78f)
                cubicTo(w * 0.38f, h * 0.68f, w * 0.72f, h * 0.84f, w * 0.96f, h * 0.70f)
            }
            drawPath(contour1, color = contourColor, style = contourStroke)
            drawPath(contour2, color = contourColor, style = contourStroke)
            drawPath(contour3, color = contourColor, style = contourStroke)

            // Hoehenprofil-Silhouette am unteren Rand.
            val amp = when (ascentPreference) {
                AscentPreference.FLACH -> 0.14f
                AscentPreference.MODERAT -> 0.26f
                AscentPreference.BERGIG -> 0.38f
            } * if (restDay) 0.65f else 1.0f
            val baseY = h * 0.94f
            val profileLine = Path().apply {
                moveTo(0f, baseY - h * 0.08f)
                cubicTo(
                    w * 0.18f, baseY - h * (0.08f + amp * 0.55f),
                    w * 0.30f, baseY - h * (0.08f + amp * 0.30f),
                    w * 0.45f, baseY - h * (0.08f + amp * 0.95f),
                )
                cubicTo(
                    w * 0.60f, baseY - h * (0.08f + amp * 1.15f),
                    w * 0.76f, baseY - h * (0.08f + amp * 0.45f),
                    w, baseY - h * 0.10f,
                )
            }
            val profileFill = Path().apply {
                addPath(profileLine)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(
                profileFill,
                brush = Brush.verticalGradient(
                    colors = listOf(routeColor.copy(alpha = 0.20f), routeColor.copy(alpha = 0.05f)),
                    startY = baseY - h * amp,
                    endY = h,
                ),
            )
            drawPath(
                profileLine,
                color = routeColor.copy(alpha = 0.55f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            // Stilisierte Rundkurs-Schleife in Waldgruen.
            val loopPath = Path().apply {
                val startX = w * 0.22f
                val startY = h * 0.56f
                moveTo(startX, startY)
                cubicTo(w * 0.16f, h * 0.28f, w * 0.38f, h * 0.16f, w * 0.56f, h * 0.24f)
                cubicTo(w * 0.76f, h * 0.32f, w * 0.84f, h * 0.48f, w * 0.72f, h * 0.62f)
                cubicTo(w * 0.58f, h * 0.76f, w * 0.34f, h * 0.72f, startX, startY)
                close()
            }
            drawPath(
                loopPath,
                color = routeColor.copy(alpha = 0.16f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            val dash = if (restDay) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
            drawPath(
                loopPath,
                color = routeColor,
                style = Stroke(
                    width = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dash,
                ),
            )

            // Start- und Zielpunkt auf der Schleife.
            val startCenter = Offset(w * 0.22f, h * 0.56f)
            drawCircle(color = theme.surfaceContainerHighest, radius = 5.5.dp.toPx(), center = startCenter)
            drawCircle(color = routeColor, radius = 3.5.dp.toPx(), center = startCenter)

            if (isEvent) {
                val peakCenter = Offset(w * 0.56f, h * 0.24f)
                drawCircle(color = theme.surfaceContainerHighest, radius = 4.5.dp.toPx(), center = peakCenter)
                drawCircle(color = routeColor, radius = 2.5.dp.toPx(), center = peakCenter)
            }
        }

        if (showStatusBadge && statusLabel != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                shape = CircleShape,
                color = atlas.routeMediaScrim,
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
