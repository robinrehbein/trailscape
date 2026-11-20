package de.trailscape.app.ui.progress

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.core.MilestoneKind
import de.trailscape.core.MilestoneProgress
import de.trailscape.core.RecordKind
import de.trailscape.core.RideFact
import de.trailscape.core.RideInfo
import de.trailscape.core.earthCircumferenceKm
import de.trailscape.core.everestM
import de.trailscape.core.rideFacts
import kotlin.math.roundToInt

/**
 * Die gefahrenen Touren als [RideFact]s fuer die Motivationsrechnung
 * (`core/…/Motivation.kt`). Der Kalendertag ist der in der Zeitzone des
 * Geraets — dieselbe Umrechnung wie ueberall sonst in der Oberflaeche
 * ([localOfEpochMs]), damit „diese Woche" hier und im Wochenstreifen dieselbe
 * Woche ist.
 */
@Composable
fun rememberRideFacts(rides: List<RideInfo>): List<RideFact> =
    remember(rides) { rideFacts(rides) { localOfEpochMs(it).toLocalDate() } }

/** Ganze Zahl in der Sprache der Oberflaeche, z. B. Kilometer oder Hoehenmeter. */
@Composable
internal fun whole(value: Double): String = LocalAppFormats.current.decimal(value, 0)

/**
 * Ein Stand auf dem Weg zu [target] als ganze Zahl — **abgerundet**, solange
 * das Ziel nicht erreicht ist. Kaufmaennisch gerundet stuende bei 149,6 von
 * 150 km „150 von 150" da, ohne Haken daneben.
 */
@Composable
internal fun wholeTowards(value: Double, target: Double): String =
    whole(if (value >= target) value else kotlin.math.floor(value + 1e-9))

/** Fahrzeit als „3:05 h". */
@Composable
internal fun durationHm(seconds: Double): String {
    val totalMin = (seconds / 60).roundToInt()
    return stringResource(R.string.progress_duration_hm, totalMin / 60, (totalMin % 60).toString().padStart(2, '0'))
}

/** Ein Bestwert mit Einheit, z. B. „82,4 km", „1100 Hm", „4:10 h", „27,3 km/h". */
@Composable
internal fun recordValue(kind: RecordKind, value: Double): String {
    val formats = LocalAppFormats.current
    return when (kind) {
        RecordKind.DISTANCE -> stringResource(R.string.common_value_km, formats.km(value))
        RecordKind.ASCENT -> stringResource(R.string.progress_value_ascent, whole(value))
        RecordKind.TIME -> durationHm(value)
        RecordKind.SPEED -> stringResource(R.string.common_value_kmh, formats.decimal(value, 1))
    }
}

/** Name einer Stufe, z. B. „1000 km gesamt" oder „Einmal um die Erde". */
@Composable
internal fun milestoneName(kind: MilestoneKind, threshold: Double): String = when (kind) {
    MilestoneKind.TOTAL_KM ->
        if (threshold == earthCircumferenceKm) {
            stringResource(R.string.progress_milestone_earth)
        } else {
            stringResource(R.string.progress_milestone_total_km, whole(threshold))
        }

    MilestoneKind.TOTAL_ASCENT ->
        if (threshold == everestM) {
            stringResource(R.string.progress_milestone_everest)
        } else {
            stringResource(R.string.progress_milestone_total_ascent, whole(threshold))
        }

    MilestoneKind.RIDE_COUNT ->
        pluralStringResource(R.plurals.progress_milestone_ride_count, threshold.toInt(), threshold.toInt())

    MilestoneKind.LONGEST_RIDE -> stringResource(R.string.progress_milestone_longest_ride, whole(threshold))
}

/**
 * „Noch 38 km bis: 1000 km gesamt" — der Satz zur naechsten Stufe. `null`
 * oberhalb der Leiter.
 *
 * Der Rest wird **aufgerundet**: „Noch 0 km" bei 0,4 fehlenden Kilometern
 * waere eine Stufe, die erreicht aussieht und es nicht ist.
 */
@Composable
internal fun milestoneRemaining(progress: MilestoneProgress): String? {
    val next = progress.next ?: return null
    val name = milestoneName(progress.kind, next)
    val remaining = kotlin.math.ceil(progress.remaining).coerceAtLeast(1.0)
    return when (progress.kind) {
        MilestoneKind.TOTAL_KM, MilestoneKind.LONGEST_RIDE ->
            stringResource(R.string.progress_milestone_remaining_km, whole(remaining), name)

        MilestoneKind.TOTAL_ASCENT ->
            stringResource(R.string.progress_milestone_remaining_ascent, whole(remaining), name)

        MilestoneKind.RIDE_COUNT ->
            pluralStringResource(
                R.plurals.progress_milestone_remaining_rides,
                remaining.toInt(),
                remaining.toInt(),
                name,
            )
    }
}
