package de.trailscape.core.i18n

import de.trailscape.core.FitnessLevel
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TargetZone
import de.trailscape.core.TrainingPhase
import de.trailscape.core.WeekKind
import de.trailscape.core.formatGoalDuration

internal object TrainingTextsEn : TrainingTexts {
    override fun errorTooSoon() = "Your goal is too soon – plan at least 3 weeks."
    override fun errorTooFar() = "Your goal is more than a year away."
    override fun errorFitnessBlockLength(minWeeks: Int, maxWeeks: Int) =
        "A fitness block runs for $minWeeks to $maxWeeks weeks."

    override fun trainingPhase(phase: TrainingPhase) = when (phase) {
        TrainingPhase.GRUNDLAGE -> "Base"
        TrainingPhase.AUFBAU -> "Build"
        TrainingPhase.SPEZIFISCH -> "Specific"
        TrainingPhase.ERHOLUNG -> "Recovery"
        TrainingPhase.TAPER -> "Taper"
        TrainingPhase.EVENT -> "Goal week"
        TrainingPhase.TEST -> "Test week"
    }

    override fun targetZone(zone: TargetZone) = when (zone) {
        TargetZone.RECOVERY -> "Recovery (Z1)"
        TargetZone.ENDURANCE -> "Endurance (Z2)"
        TargetZone.TEMPO -> "Tempo (Z3)"
        TargetZone.SWEET_SPOT -> "Sweet spot"
        TargetZone.THRESHOLD -> "Threshold (Z4)"
        TargetZone.VO2MAX -> "VO2max (Z5)"
    }

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
        SessionTextKey.WORKOUT -> workoutTitle(args)
        SessionTextKey.RECOVERY_LONG -> "Shortened long ride"
        SessionTextKey.FTP_TEST -> "20-minute test"
        SessionTextKey.BENCHMARK_RIDE -> "Benchmark loop"
    }

    private fun workoutTitle(args: List<Int>): String {
        val base = when (args.getOrNull(0)) {
            0 -> "Tempo efforts"
            1 -> "Sweet-spot intervals"
            3 -> "VO2max intervals"
            else -> "Threshold intervals"
        }
        return if (args.getOrNull(7) == 1) "$base (uphill)" else base
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
            val climbing = if (args.firstOrNull() == 1) {
                "$base Deliberately include climbs to get used to the elevation gain of your goal."
            } else {
                base
            }
            val blocks = args.getOrNull(1) ?: 0
            if (blocks > 0) {
                val speed = (args.getOrNull(3) ?: -1).takeIf { it > 0 }
                    ?.let { " – your goal means an average of about ${formatDecimal(it / 10.0, 1, AppLanguage.EN)} km/h" }
                    .orEmpty()
                "$climbing In the second half, ride ${blocks}×${args.getOrNull(2) ?: 0} minutes at goal pace$speed, " +
                    "easy again in between."
            } else {
                climbing
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
        SessionTextKey.WORKOUT -> {
            val effort = when (args[0]) {
                0 -> "briskly at tempo – you can only talk in short sentences"
                1 -> "at sweet spot, just below threshold"
                3 -> "very hard at VO2max – 90–95% of your maximum heart rate towards the end of each effort"
                else -> "at threshold, hard but steady"
            }
            val hill = if (args.getOrNull(7) == 1) " Ride the efforts on a steady climb." else ""
            "After ${args[1]} minutes of warm-up, ride ${args[2]}×${args[3]} minutes $effort, with " +
                "${args[4]} easy minutes in between; finish with ${args[5]} minutes of cool-down – " +
                "about ${args[6]} minutes in total.$hill"
        }
        SessionTextKey.RECOVERY_LONG ->
            "This week's long ride is deliberately shorter and calm at endurance pace – " +
                "now your body absorbs the build-up of the last weeks."
        SessionTextKey.FTP_TEST ->
            "Warm up for ${args[0]} minutes with two short build-ups, then ride ${args[1]} minutes as " +
                "hard and even as you can and note average power and heart rate – about 95% of that " +
                "power is your FTP. Cool down for ${args[2]} minutes, about ${args[3]} minutes in total."
        SessionTextKey.BENCHMARK_RIDE ->
            "Your benchmark loop of ${args[0]} km: ride it briskly and evenly and compare time and " +
                "average heart rate with earlier rides – that shows what the block has done."
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

    override fun planBudgetLimited(budgetHours: Double, neededHours: Double) =
        "The bottleneck is your time budget of ${formatCompactDecimal(budgetHours, AppLanguage.EN)} h per " +
            "week: without that cap the plan would need about " +
            "${formatCompactDecimal(neededHours, AppLanguage.EN)} h per week at its peak."

    override fun targetTimeAmbitious(
        targetMin: Int,
        predictedMin: Int,
        targetSpeedKmh: Double,
        predictedSpeedKmh: Double,
    ) = "Your target time of ${formatGoalDuration(targetMin)} h needs an average of about " +
        "${formatDecimal(targetSpeedKmh, 1, AppLanguage.EN)} km/h – clearly faster than the " +
        "prediction of ${formatGoalDuration(predictedMin)} h " +
        "(${formatDecimal(predictedSpeedKmh, 1, AppLanguage.EN)} km/h). The goal-pace sections of " +
        "your long rides will show whether that pace becomes realistic."
}
