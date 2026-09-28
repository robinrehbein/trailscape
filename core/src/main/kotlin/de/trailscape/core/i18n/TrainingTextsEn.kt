package de.trailscape.core.i18n

import de.trailscape.core.FitnessLevel
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.SessionIntensity
import de.trailscape.core.WeekKind

internal object TrainingTextsEn : TrainingTexts {
    override fun errorTooSoon() = "Your goal is too soon – plan at least 3 weeks."
    override fun errorTooFar() = "Your goal is more than a year away."

    override fun fitnessLevel(level: FitnessLevel) = when (level) {
        FitnessLevel.EINSTEIGER -> "Beginner"
        FitnessLevel.FORTGESCHRITTEN -> "Intermediate"
        FitnessLevel.AMBITIONIERT -> "Ambitious"
    }

    override fun weekKind(kind: WeekKind) = when (kind) {
        WeekKind.AUFBAU -> "Build"
        WeekKind.ERHOLUNG -> "Recovery"
        WeekKind.TAPER -> "Taper"
        WeekKind.ZIELWOCHE -> "Goal week"
    }

    override fun sessionIntensity(intensity: SessionIntensity) = when (intensity) {
        SessionIntensity.LOCKER -> "easy"
        SessionIntensity.GRUNDLAGE -> "endurance"
        SessionIntensity.HART -> "intense"
    }

    override fun planSessionStatus(status: PlanSessionStatus) = when (status) {
        PlanSessionStatus.OFFEN -> "Open"
        PlanSessionStatus.ERLEDIGT -> "Done"
        PlanSessionStatus.TEILWEISE -> "Partly done"
        PlanSessionStatus.VERPASST -> "Missed"
    }

    override fun sessionTitle(key: SessionTextKey, args: List<Int>, eventName: String?) = when (key) {
        SessionTextKey.RECOVERY_EASY_RIDE -> "Easy ride"
        SessionTextKey.RECOVERY_CALM_LOOP -> "Calm loop"
        SessionTextKey.TAPER_EASY_SURGES -> "Easy with surges"
        SessionTextKey.TAPER_SHORT_EASY -> "Short easy ride"
        SessionTextKey.BASE_EASY_RIDE -> "Easy endurance ride"
        SessionTextKey.LONG_RIDE -> "Long ride"
        SessionTextKey.RECOVERY_SPIN -> "Easy recovery spin"
        SessionTextKey.ENDURANCE_FILL, SessionTextKey.ENDURANCE_STEADY -> "Endurance"
        SessionTextKey.ENDURANCE_COMPENSATION -> "Endurance recovery spin"
        SessionTextKey.INTERVALS -> "Intervals"
        SessionTextKey.GOAL_EVENT -> "Goal event: ${eventName.orEmpty()}"
        SessionTextKey.ACTIVATION -> "Easy activation"
    }

    override fun sessionDescription(key: SessionTextKey, args: List<Int>) = when (key) {
        SessionTextKey.RECOVERY_EASY_RIDE ->
            "Roll along relaxed in low gears with a high cadence – this week " +
                "is purely for recovery."
        SessionTextKey.RECOVERY_CALM_LOOP ->
            "A leisurely ride without pressure; keep your heart rate low " +
                "throughout."
        SessionTextKey.TAPER_EASY_SURGES ->
            "Roll easily and add 3 short surges of 30 seconds each to stay sharp."
        SessionTextKey.TAPER_SHORT_EASY ->
            "Ride short and calm, then check your gear and deliberately rest your legs."
        SessionTextKey.BASE_EASY_RIDE ->
            "Calm endurance pace – you should be able to hold a conversation for " +
                "the whole ride."
        SessionTextKey.LONG_RIDE -> {
            val base = "The key session of the week: ride steadily at endurance pace and " +
                "eat and drink consistently."
            if (args.firstOrNull() == 1) {
                "$base Deliberately include climbs to get used to the elevation gain of your goal."
            } else {
                base
            }
        }
        SessionTextKey.RECOVERY_SPIN ->
            "A short recovery loop in an easy gear, deliberately low intensity for fresh legs."
        SessionTextKey.ENDURANCE_FILL ->
            "An easy endurance session to top up your weekly volume; keep your heart " +
                "rate steady in the endurance zone."
        SessionTextKey.ENDURANCE_STEADY ->
            "A calm endurance session with an even effort, no spikes and no sprints."
        SessionTextKey.ENDURANCE_COMPENSATION ->
            "A recovery spin with a high cadence to loosen your legs after the long ride."
        SessionTextKey.INTERVALS -> {
            val effort = if (args[INTERVAL_ARG_EFFORT] == 1) "hard at threshold" else "brisk in the threshold range"
            "After ${args[0]} minutes of warming up, ${args[1]}×${args[2]} minutes " +
                "$effort with ${args[3]} easy minutes in between; finish with " +
                "${args[4]} minutes of cooling down – about ${args[5]} minutes in total."
        }
        SessionTextKey.GOAL_EVENT -> if (args[1] >= 0) {
            "Your goal event over ${args[0]} km and about ${args[1]} m of climbing – " +
                "pace yourself on the climbs and drink regularly from the start."
        } else {
            "Your goal event over ${args[0]} km – start in control, hold your pace and " +
                "keep fuelling along the way."
        }
        SessionTextKey.ACTIVATION ->
            "A short easy loop with a few surges, then get your bike and food ready " +
                "for the big day."
    }

    override fun plainTitleEvent(km: Int) = "Race $km km"
    override fun plainTitleLongRide(km: Int) = "Long ride $km km"
    override fun plainTitleHard(km: Int) = "Hard $km km"
    override fun plainTitleEasy(km: Int) = "Easy $km km"
    override fun plainHintEvent() = "your goal"
    override fun plainHintLongRide(hilly: Boolean) =
        if (hilly) "steady, with climbing like in the race" else "steady, long in the saddle"
    override fun plainHintHard() = "with hard stretches, easy in between"
    override fun plainHintRecovery() = "very calm, for fresh legs"
    override fun plainHintEndurance() = "calm, you can chat while riding"

    override fun planAdapted(weekNumber: Int, percent: Int) =
        "Plan adjusted: in week $weekNumber you reached only $percent% of the " +
            "weekly target. The remaining weeks build up again from what you " +
            "actually ride – goal and date stay the same."

    override fun planNotFeasible(
        longestKm: Int,
        percent: Int,
        goalKm: Int,
        suggestedKm: Int,
        suggestedWeeks: Int?,
        needsLongerPlan: Boolean,
    ): String {
        val outlook = when {
            suggestedWeeks != null && needsLongerPlan ->
                "For the full $goalKm km you need about $suggestedWeeks weeks from " +
                    "your current volume."
            suggestedWeeks != null ->
                "With a slightly different structure, the $goalKm km would be " +
                    "reachable in $suggestedWeeks weeks."
            else ->
                "Even a year of preparation from your current volume isn’t enough for " +
                    "the full $goalKm km — build up over an intermediate distance first."
        }
        return "The longest ride in this plan is $longestKm km – only $percent% of your " +
            "goal distance of $goalKm km. Realistically this plan supports a goal of about " +
            "$suggestedKm km. $outlook"
    }
}
