package de.trailscape.app.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.core.AscentPreference
import de.trailscape.core.RouteTarget
import de.trailscape.core.ascentPreferenceLabel
import de.trailscape.core.formatGoalDuration
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
