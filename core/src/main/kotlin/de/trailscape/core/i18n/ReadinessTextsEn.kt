package de.trailscape.core.i18n

import de.trailscape.core.DailyRecommendationKind
import de.trailscape.core.HrvStatus
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag

internal object ReadinessTextsEn : ReadinessTexts {
    private val lang = AppLanguage.EN
    private fun d1(value: Double) = formatDecimal(value, 1, lang)

    override fun recoveryFlag(flag: RecoveryFlag) = when (flag) {
        RecoveryFlag.UNBEKANNT -> "no assessment"
        RecoveryFlag.GRUEN -> "normal"
        RecoveryFlag.GELB -> "slightly raised"
        RecoveryFlag.ORANGE -> "clearly unusual"
        RecoveryFlag.ROT -> "strongly unusual"
    }

    override fun hrvStatus(status: HrvStatus) = when (status) {
        HrvStatus.UNBEKANNT -> "no assessment"
        HrvStatus.NIEDRIG -> "below your normal range"
        HrvStatus.IM_BAND -> "within your normal range"
        HrvStatus.UEBER_BAND -> "above your normal range"
        HrvStatus.SAETTIGUNG -> "above the range with a raised resting heart rate"
    }

    override fun readinessBand(band: ReadinessBand) = when (band) {
        ReadinessBand.HART -> "ready for a hard session"
        ReadinessBand.NORMAL -> "normal training"
        ReadinessBand.LOCKER -> "easy (endurance pace)"
        ReadinessBand.RUHE -> "rest or very easy"
    }

    override fun noStatement() = "No assessment possible."

    override fun restingHrNoValues() = "No resting heart rate values yet."
    override fun restingHrBaselineBuilding(days: Int, needed: Int) =
        "Building your resting heart rate baseline ($days of $needed days)."
    override fun restingHrNoRecent() = "No recent resting heart rate value (last 3 days)."

    override fun restingHrGreen(deltaBpm: Double) =
        "Your resting heart rate is in its usual range (${signedOneDecimal(deltaBpm, lang)} bpm " +
            "compared with your normal value)."

    override fun restingHrYellow(deltaBpm: Double, afterHardDay: Boolean): String {
        val rounded = absOneDecimal(deltaBpm, lang)
        return if (afterHardDay) {
            "Your resting heart rate is +$rounded bpm above your normal value — " +
                "expected after yesterday’s effort."
        } else {
            "Your resting heart rate has been +$rounded bpm above your normal value " +
                "for at least two readings. Training, sleep, stress, alcohol, heat or " +
                "an oncoming infection can all cause this."
        }
    }

    override fun restingHrOrange(deltaBpm: Double) =
        "Your resting heart rate is clearly above your normal value " +
            "(+${absOneDecimal(deltaBpm, lang)} bpm). Training, sleep, stress, alcohol, heat or " +
            "an infection can all cause this."

    override fun restingHrRed(deltaBpm: Double) =
        "Your resting heart rate has been well above your normal value for several days " +
            "(+${absOneDecimal(deltaBpm, lang)} bpm) — training, sleep, stress or an infection " +
            "can cause this."

    override fun hrvNoValues() = "No HRV values yet."
    override fun hrvNeedsDays(missing: Int, have: Int, needed: Int) =
        "Needs ${plural(missing, "1 more day", "$missing more days")} of HRV data " +
            "($have of $needed in the comparison period)."
    override fun hrvTooFewRecent(have: Int, needed: Int) =
        "Too few HRV readings in the last seven days ($have of $needed)."

    override fun hrvLow(currentMs: Int, lowMs: Int, highMs: Int, clearly: Boolean) = if (!clearly) {
        "Your 7-day HRV average of $currentMs ms is just below your normal range " +
            "($lowMs–$highMs ms). Training, sleep, stress, alcohol or an oncoming " +
            "infection can all cause this."
    } else {
        "Your 7-day HRV average of $currentMs ms is clearly below your normal range " +
            "($lowMs–$highMs ms). Training, sleep, stress, alcohol or an infection " +
            "can all cause this."
    }

    override fun hrvInBand(currentMs: Int, lowMs: Int, highMs: Int) =
        "Your 7-day HRV average of $currentMs ms is within your normal range " +
            "($lowMs–$highMs ms)."

    override fun hrvAboveBand(currentMs: Int, lowMs: Int, highMs: Int) =
        "Your 7-day HRV average of $currentMs ms is above your normal range " +
            "($lowMs–$highMs ms) — your nervous system looks well recovered."

    override fun hrvSaturation(currentMs: Int, lowMs: Int, highMs: Int) =
        "Your 7-day HRV average of $currentMs ms is above your normal range " +
            "($lowMs–$highMs ms) while your resting heart rate is raised. This " +
            "combination also occurs with heavy fatigue — watch the next few days " +
            "before training hard."

    override fun sleepNoData() = "No sleep data yet."
    override fun sleepBaselineBuilding(nights: Int, needed: Int) =
        "Building your sleep baseline ($nights of $needed nights)."
    override fun sleepNoRecent() = "No recent sleep measurement."
    override fun sleepGreen(baselineH: Double) =
        "Your sleep matches your normal value (${d1(baselineH)} h)."
    override fun sleepYellow(deficitH: Double) =
        "You slept ${absOneDecimal(deficitH, lang)} h less than usual."
    override fun sleepOrange(deficitH: Double, debt7dH: Double) =
        "Your sleep is clearly below your normal value " +
            "(−${absOneDecimal(deficitH, lang)} h; 7-day deficit ${d1(debt7dH)} h)."
    override fun sleepRed(deficitH: Double) =
        "Far too little sleep (−${absOneDecimal(deficitH, lang)} h) combined with a raised " +
            "resting heart rate."

    override fun shortSleeperHint() =
        "For weeks your usual sleep has been under 6.5 hours. Adults are advised " +
            "to sleep 7–9 hours, more with a lot of training — more sleep improves " +
            "recovery and performance. This doesn’t change your daily recommendation."

    override fun signalName(signal: ReadinessSignal) = when (signal) {
        ReadinessSignal.RESTING_HR -> "resting heart rate"
        ReadinessSignal.SLEEP -> "sleep"
        ReadinessSignal.TRAINING_HISTORY -> "training history"
    }

    override fun readinessUnavailable(missing: List<ReadinessSignal>) =
        "Not enough data for an overall score yet " +
            "(${missing.joinToString(", ") { signalName(it) }}). You can still see the individual signals."

    override fun readinessHeadline(score: Int, band: ReadinessBand) =
        "Readiness: $score — ${readinessBand(band)}"

    override fun readinessHeadlineUnavailable() = "Readiness can’t be calculated yet"

    override fun readinessDetail(usesHrv: Boolean) = if (usesHrv) {
        "Based on HRV, resting heart rate, sleep and training load — " +
            "a trend indicator, not a measurement."
    } else {
        "Based on resting heart rate, sleep and training load (without HRV) — " +
            "a trend indicator, not a measurement. Without HRV the most direct " +
            "signal is missing, so the score is less certain."
    }

    override fun readinessDetailUnavailable() =
        "Once there are enough days, we combine resting heart rate, sleep and " +
            "training load into one score."

    override fun recommendationTitle(kind: DailyRecommendationKind) = when (kind) {
        DailyRecommendationKind.RUHETAG -> "Better take a rest day today"
        DailyRecommendationKind.LOCKER_Z2 -> "Easy at endurance pace (zone 2), 60–90 min"
        DailyRecommendationKind.RECOVERY -> "Recovery ride, very easy (zone 1–2)"
        DailyRecommendationKind.HARTE_EINHEIT -> "Hard session possible (Z4/Z5)"
        DailyRecommendationKind.GRUNDLAGE -> "Endurance session"
    }

    override fun recommendationDetail(kind: DailyRecommendationKind) = when (kind) {
        DailyRecommendationKind.RUHETAG -> "Your recovery signals point to a break instead of training."
        DailyRecommendationKind.LOCKER_Z2 ->
            "No intervals — keep today’s intensity at endurance level."
        DailyRecommendationKind.RECOVERY -> "Your fatigue is high right now — ride short and easy."
        DailyRecommendationKind.HARTE_EINHEIT ->
            "Recovery and form are right — a hard effort fits in today."
        DailyRecommendationKind.GRUNDLAGE ->
            "Ride to what’s left of your weekly budget, mostly at endurance pace " +
                "(zone 2 — a pace at which you can still hold a conversation)."
    }

    override fun deloadTriggerLowForm() = "Your form has been very low for three days."
    override fun deloadTriggerFastRamp() = "Your fitness has risen very quickly for three weeks."
    override fun deloadTriggerLowReadiness(days: Int) =
        "Your readiness was in the low range on $days of seven days."
    override fun deloadWarningWeeklyJump() = "Your load has risen clearly this week."
    override fun deloadWarningAcuteLoad() = "Your acute load is well above your usual level."
    override fun deloadTitle(recommended: Boolean) =
        if (recommended) "Recovery week recommended" else "No recovery week needed"
    override fun deloadDetail(recommended: Boolean) = if (recommended) {
        "Cut your weekly volume by 40–50% and keep the intensity " +
            "— short hard efforts can stay in."
    } else {
        "Your load looks sustainable right now."
    }
}
