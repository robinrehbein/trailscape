package de.trailscape.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.Ride
import de.trailscape.core.RideInfo
import de.trailscape.core.TrackPoint
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal const val ROUTE_PREVIEW_MAX_POINTS: Int = 48
internal const val DEFAULT_GRAVEL_SPEED_KMH: Double = 20.0

/**
 * Duennt [points] auf hoechstens [maxPoints] Stuetzpunkte aus und normiert sie
 * auf das Einheitsquadrat (Norden oben, Seitenverhaeltnis erhalten).
 */
internal fun normalizeRoutePolyline(
    points: List<TrackPoint>,
    maxPoints: Int = ROUTE_PREVIEW_MAX_POINTS,
): FloatArray {
    val finitePoints = points.filter { it.lat.isFinite() && it.lon.isFinite() }
    if (finitePoints.size < 2 || maxPoints < 2) return FloatArray(0)
    val gaps = finitePoints.size - 1
    val slots = (maxPoints - 2).coerceAtLeast(1)
    val step = max(1, (gaps + slots - 1) / slots)
    val picked = buildList {
        var i = 0
        while (i < finitePoints.size) {
            add(finitePoints[i])
            i += step
        }
        if (last() !== finitePoints.last()) add(finitePoints.last())
    }

    val meanLat = picked.sumOf { it.lat } / picked.size
    val lonScale = cos(Math.toRadians(meanLat))
    val xs = DoubleArray(picked.size) { picked[it].lon * lonScale }
    val ys = DoubleArray(picked.size) { -picked[it].lat }

    val minX = xs.min()
    val minY = ys.min()
    val spanX = xs.max() - minX
    val spanY = ys.max() - minY
    val span = max(spanX, spanY)
    if (!span.isFinite() || span <= 0.0) return floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
    val offsetX = (span - spanX) / 2
    val offsetY = (span - spanY) / 2

    val out = FloatArray(picked.size * 2)
    for (i in picked.indices) {
        out[2 * i] = ((xs[i] - minX + offsetX) / span).toFloat()
        out[2 * i + 1] = ((ys[i] - minY + offsetY) / span).toFloat()
    }
    return out
}

internal object MapRoutePreviewCache {
    private const val MaxEntries = 400

    private val entries = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > MaxEntries
    }

    private val loads = Semaphore(2)

    private fun key(ride: RideInfo) = "${ride.id}@${ride.updatedAt}"

    fun peek(ride: RideInfo): FloatArray? = synchronized(entries) { entries[key(ride)] }

    suspend fun load(
        ride: RideInfo,
        loadRide: suspend (String) -> Ride?,
    ): FloatArray? {
        peek(ride)?.let { return it }
        val loaded = loads.withPermit {
            peek(ride) ?: withContext(Dispatchers.IO) { loadRide(ride.id) }?.let { full ->
                normalizeRoutePolyline(full.points)
            }
        }
        if (loaded != null) {
            synchronized(entries) { entries[key(ride)] = loaded }
        }
        return loaded
    }
}

/**
 * Zeichnet die Streckenlinie in Waldgruen (`colorScheme.primary`) auf einer
 * ruhigen Kartenflaeche mit zartem Raster, Startpunkt und Zielring.
 */
@Composable
internal fun RoutePolylinePreview(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
    paddingFraction: Float = 0.14f,
) {
    val normalized = remember(points) { normalizeRoutePolyline(points) }
    RouteNormalizedPreview(
        normalized = normalized,
        modifier = modifier,
        strokeWidth = strokeWidth,
        paddingFraction = paddingFraction,
    )
}

@Composable
internal fun SavedRoutePreview(
    ride: RideInfo,
    loadRide: suspend (String) -> Ride?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
) {
    val poly by produceState<FloatArray?>(
        initialValue = MapRoutePreviewCache.peek(ride),
        key1 = ride.id,
        key2 = ride.updatedAt,
    ) {
        value = MapRoutePreviewCache.load(ride, loadRide)
    }
    RouteNormalizedPreview(
        normalized = poly,
        modifier = modifier,
        strokeWidth = strokeWidth,
    )
}

@Composable
internal fun RouteNormalizedPreview(
    normalized: FloatArray?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
    paddingFraction: Float = 0.14f,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val mapTint = if (isDark) Color(0xFF161B20) else Color(0xFFE6ECE6)
    val gridTint = if (isDark) Color(0xFF242B33) else Color(0xFFD5DFD5)
    val lineColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.background(mapTint),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val gridStroke = 1.dp.toPx()
            for (step in 1..3) {
                val x = w * step / 4f
                val y = h * step / 4f
                drawLine(gridTint, Offset(x, 0f), Offset(x, h), strokeWidth = gridStroke)
                drawLine(gridTint, Offset(0f, y), Offset(w, y), strokeWidth = gridStroke)
            }

            if (normalized == null || normalized.size < 4) return@Canvas

            val padX = w * paddingFraction
            val padY = h * paddingFraction
            val drawW = (w - 2f * padX).coerceAtLeast(1f)
            val drawH = (h - 2f * padY).coerceAtLeast(1f)
            val side = minOf(drawW, drawH)
            val originX = (w - side) / 2f
            val originY = (h - side) / 2f

            val path = Path()
            val count = normalized.size / 2
            for (i in 0 until count) {
                val px = originX + normalized[2 * i] * side
                val py = originY + normalized[2 * i + 1] * side
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )

            val start = Offset(originX + normalized[0] * side, originY + normalized[1] * side)
            val end = Offset(
                originX + normalized[2 * (count - 1)] * side,
                originY + normalized[2 * (count - 1) + 1] * side,
            )
            val dotRadius = (strokeWidth.toPx() * 1.25f).coerceAtLeast(3.dp.toPx())
            drawCircle(color = GravelGreen, radius = dotRadius, center = start)
            drawCircle(color = Color.White, radius = dotRadius * 0.45f, center = start)
            drawCircle(color = RecordRed, radius = dotRadius, center = end)
            drawCircle(color = Color.White, radius = dotRadius * 0.45f, center = end)
        }
        if (normalized == null || normalized.size < 4) {
            Icon(
                imageVector = Icons.Rounded.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

internal fun estimatedDurationMinutes(
    distanceKm: Double,
    speedKmh: Double? = null,
): Int {
    if (!distanceKm.isFinite() || distanceKm <= 0.0) return 0
    val effectiveSpeed = speedKmh?.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_GRAVEL_SPEED_KMH
    return (distanceKm / effectiveSpeed * 60.0).roundToInt().coerceAtLeast(1)
}

internal fun formatRouteDurationApprox(minutes: Int): UiText =
    UiText.Res(R.string.map_route_duration_approx, listOf(restzeitDauer(minutes)))
