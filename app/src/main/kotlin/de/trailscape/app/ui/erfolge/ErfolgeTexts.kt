package de.trailscape.app.ui.erfolge

import android.content.res.Resources
import androidx.annotation.StringRes
import de.trailscape.app.R
import de.trailscape.app.i18n.AppFormats
import de.trailscape.core.AchievementGroup
import de.trailscape.core.AchievementId
import de.trailscape.core.AchievementState
import de.trailscape.core.AchievementStatus
import de.trailscape.core.AchievementUnit
import kotlin.math.floor

/**
 * # Texte der Erfolge
 *
 * Die einzige Stelle, die Abzeichen auf ihre Ressourcen abbildet
 * (`res/values[-en]/strings_spiel1.xml`, Praefix `erfolge_`). Ohne Compose,
 * damit Tourblatt, Uhr und Bildschirm dieselben Saetze bekommen.
 */

/** Name eines Abzeichens („Hunderter"). */
@StringRes
internal fun badgeTitleRes(id: AchievementId): Int = when (id) {
    AchievementId.FIRST_RIDE -> R.string.erfolge_badge_first_ride_title
    AchievementId.RIDE_50_KM -> R.string.erfolge_badge_ride_50km_title
    AchievementId.RIDE_100_KM -> R.string.erfolge_badge_ride_100km_title
    AchievementId.RIDE_1000_M -> R.string.erfolge_badge_ride_1000m_title
    AchievementId.RIDE_4_H -> R.string.erfolge_badge_ride_4h_title
    AchievementId.RIDES_10 -> R.string.erfolge_badge_rides_10_title
    AchievementId.RIDES_100 -> R.string.erfolge_badge_rides_100_title
    AchievementId.TOTAL_1000_KM -> R.string.erfolge_badge_total_1000km_title
    AchievementId.TOTAL_EARTH -> R.string.erfolge_badge_total_earth_title
    AchievementId.TOTAL_EVEREST -> R.string.erfolge_badge_total_everest_title
    AchievementId.STREAK_4 -> R.string.erfolge_badge_streak_4_title
    AchievementId.STREAK_12 -> R.string.erfolge_badge_streak_12_title
    AchievementId.STREAK_52 -> R.string.erfolge_badge_streak_52_title
    AchievementId.MONTH_COMPLETE -> R.string.erfolge_badge_month_complete_title
    AchievementId.CHALLENGE_BONUS -> R.string.erfolge_badge_challenge_bonus_title
    AchievementId.YEAR_GOAL -> R.string.erfolge_badge_year_goal_title
    AchievementId.PLAN_FIRST -> R.string.erfolge_badge_plan_first_title
    AchievementId.PLAN_FULL_WEEK -> R.string.erfolge_badge_plan_full_week_title
    AchievementId.RECOVERY_WEEK -> R.string.erfolge_badge_recovery_week_title
    AchievementId.PLAN_FINISHED -> R.string.erfolge_badge_plan_finished_title
    AchievementId.INDOOR_FIRST -> R.string.erfolge_badge_indoor_first_title
    AchievementId.INDOOR_10_H -> R.string.erfolge_badge_indoor_10h_title
    AchievementId.GHOST_BEATEN -> R.string.erfolge_badge_ghost_beaten_title
    AchievementId.KNOWN_ROUTE -> R.string.erfolge_badge_known_route_title
    AchievementId.CLIMB_REPEAT -> R.string.erfolge_badge_climb_repeat_title
    AchievementId.CLIMB_FASTER -> R.string.erfolge_badge_climb_faster_title
    AchievementId.ROUND_5 -> R.string.erfolge_badge_round_5_title
    AchievementId.TILES_100 -> R.string.erfolge_badge_tiles_100_title
    AchievementId.TILES_SQUARE_5 -> R.string.erfolge_badge_tiles_square_5_title
    AchievementId.NEW_WAYS_100_KM -> R.string.erfolge_badge_new_ways_100km_title
}

/** Bedingung eines Abzeichens als ganzer Satz. */
@StringRes
internal fun badgeBodyRes(id: AchievementId): Int = when (id) {
    AchievementId.FIRST_RIDE -> R.string.erfolge_badge_first_ride_body
    AchievementId.RIDE_50_KM -> R.string.erfolge_badge_ride_50km_body
    AchievementId.RIDE_100_KM -> R.string.erfolge_badge_ride_100km_body
    AchievementId.RIDE_1000_M -> R.string.erfolge_badge_ride_1000m_body
    AchievementId.RIDE_4_H -> R.string.erfolge_badge_ride_4h_body
    AchievementId.RIDES_10 -> R.string.erfolge_badge_rides_10_body
    AchievementId.RIDES_100 -> R.string.erfolge_badge_rides_100_body
    AchievementId.TOTAL_1000_KM -> R.string.erfolge_badge_total_1000km_body
    AchievementId.TOTAL_EARTH -> R.string.erfolge_badge_total_earth_body
    AchievementId.TOTAL_EVEREST -> R.string.erfolge_badge_total_everest_body
    AchievementId.STREAK_4 -> R.string.erfolge_badge_streak_4_body
    AchievementId.STREAK_12 -> R.string.erfolge_badge_streak_12_body
    AchievementId.STREAK_52 -> R.string.erfolge_badge_streak_52_body
    AchievementId.MONTH_COMPLETE -> R.string.erfolge_badge_month_complete_body
    AchievementId.CHALLENGE_BONUS -> R.string.erfolge_badge_challenge_bonus_body
    AchievementId.YEAR_GOAL -> R.string.erfolge_badge_year_goal_body
    AchievementId.PLAN_FIRST -> R.string.erfolge_badge_plan_first_body
    AchievementId.PLAN_FULL_WEEK -> R.string.erfolge_badge_plan_full_week_body
    AchievementId.RECOVERY_WEEK -> R.string.erfolge_badge_recovery_week_body
    AchievementId.PLAN_FINISHED -> R.string.erfolge_badge_plan_finished_body
    AchievementId.INDOOR_FIRST -> R.string.erfolge_badge_indoor_first_body
    AchievementId.INDOOR_10_H -> R.string.erfolge_badge_indoor_10h_body
    AchievementId.GHOST_BEATEN -> R.string.erfolge_badge_ghost_beaten_body
    AchievementId.KNOWN_ROUTE -> R.string.erfolge_badge_known_route_body
    AchievementId.CLIMB_REPEAT -> R.string.erfolge_badge_climb_repeat_body
    AchievementId.CLIMB_FASTER -> R.string.erfolge_badge_climb_faster_body
    AchievementId.ROUND_5 -> R.string.erfolge_badge_round_5_body
    AchievementId.TILES_100 -> R.string.erfolge_badge_tiles_100_body
    AchievementId.TILES_SQUARE_5 -> R.string.erfolge_badge_tiles_square_5_body
    AchievementId.NEW_WAYS_100_KM -> R.string.erfolge_badge_new_ways_100km_body
}

/** Ueberschrift einer Gruppe auf dem Bildschirm. */
@StringRes
internal fun groupTitleRes(group: AchievementGroup): Int = when (group) {
    AchievementGroup.RIDES -> R.string.erfolge_group_rides
    AchievementGroup.TOTALS -> R.string.erfolge_group_totals
    AchievementGroup.ROUTINE -> R.string.erfolge_group_routine
    AchievementGroup.PLAN -> R.string.erfolge_group_plan
    AchievementGroup.INDOOR -> R.string.erfolge_group_indoor
    AchievementGroup.EXPLORE -> R.string.erfolge_group_explore
}

/**
 * Die Statuszeile eines Abzeichens: „Erreicht am …", der Fortschritt
 * („60 von 100 km") oder warum es keinen gibt. Abgerundet, damit „100 von
 * 100" nie vor dem Erreichen dasteht.
 */
internal fun badgeStatusText(
    resources: Resources,
    formats: AppFormats,
    state: AchievementState,
    hasPlan: Boolean,
): String {
    if (state.reached) {
        return state.date?.let { resources.getString(R.string.erfolge_reached_on, formats.dateFull(it)) }
            ?: resources.getString(R.string.erfolge_reached)
    }
    if (state.status == AchievementStatus.UNKNOWN) return resources.getString(R.string.erfolge_progress_unknown)
    val current = state.current
    val id = state.id
    fun n(value: Double, digits: Int = 0): String {
        val factor = if (digits == 0) 1.0 else 10.0
        return formats.decimal(floor(value * factor + 1e-9) / factor, digits)
    }
    return when {
        id == AchievementId.YEAR_GOAL && (current == null || state.target <= 0) ->
            resources.getString(R.string.erfolge_progress_no_goal)
        // Die Erholungswoche gibt es auch ohne Plan (Entlastungswoche des Wochenziels).
        id.group == AchievementGroup.PLAN && id != AchievementId.RECOVERY_WEEK && !hasPlan ->
            resources.getString(R.string.erfolge_progress_no_plan)
        id == AchievementId.PLAN_FULL_WEEK && (current == null || state.target <= 0) ->
            resources.getString(R.string.erfolge_progress_no_plan_week)
        current == null -> resources.getString(R.string.erfolge_progress_open)
        id == AchievementId.TILES_SQUARE_5 ->
            resources.getString(R.string.erfolge_progress_square, n(current))
        id == AchievementId.ROUND_5 ->
            resources.getString(R.string.erfolge_progress_rounds, n(current), n(state.target))
        id == AchievementId.MONTH_COMPLETE ->
            resources.getString(R.string.erfolge_progress_month, n(current), n(state.target))
        else -> when (id.unit) {
            AchievementUnit.NONE -> resources.getString(R.string.erfolge_progress_open)
            AchievementUnit.COUNT -> resources.getString(R.string.erfolge_progress_value, n(current), n(state.target))
            AchievementUnit.KM -> resources.getString(R.string.erfolge_progress_km, n(current), n(state.target))
            AchievementUnit.METERS -> resources.getString(R.string.erfolge_progress_meters, n(current), n(state.target))
            AchievementUnit.HOURS -> resources.getString(R.string.erfolge_progress_hours, n(current, 1), n(state.target))
            AchievementUnit.WEEKS -> resources.getString(R.string.erfolge_progress_weeks, n(current), n(state.target))
            AchievementUnit.SESSIONS ->
                resources.getString(R.string.erfolge_progress_sessions, n(current), n(state.target))
            AchievementUnit.PERCENT -> resources.getString(R.string.erfolge_progress_percent, n(current))
            AchievementUnit.TILES -> resources.getString(R.string.erfolge_progress_tiles, n(current), n(state.target))
        }
    }
}

/**
 * Die Zeile „Neues Abzeichen: Hunderter" bzw. „Neue Abzeichen: Hunderter und
 * 2 weitere" — im Tourblatt und auf der Uhr (dort hoechstens 60 Zeichen, die
 * Uhr kuerzt selbst).
 */
internal fun badgeHighlightText(resources: Resources, ids: List<AchievementId>): String {
    val first = resources.getString(badgeTitleRes(ids.first()))
    val more = ids.size - 1
    return if (more <= 0) {
        resources.getString(R.string.erfolge_highlight_one, first)
    } else {
        resources.getQuantityString(R.plurals.erfolge_highlight_more_count, more, first, more)
    }
}
