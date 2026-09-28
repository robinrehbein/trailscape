package de.trailscape.core.i18n

import de.trailscape.core.AscentPreference
import de.trailscape.core.DailyRecommendationKind

/**
 * Texte der Tagesentscheidung („Heute"): Routenziel-Beschriftungen,
 * Abweichungen vom Plan (`TodayRoute.kt`, `SessionTarget.kt`,
 * `FirstRound.kt`) und die Erinnerungen (`Reminders.kt`).
 */
interface TodayTexts {
    /** „Flach", „Wellig", „Bergig" — gross geschrieben, wie in Auswahllisten. */
    fun ascentPreference(preference: AscentPreference): String

    /** Beschriftung der lockeren Runde am Ruhetag. */
    fun restDayRideLabel(): String

    /** Beschriftung der ersten Runde ohne gefahrene Tour. */
    fun firstRoundLabel(): String

    /** Der „weil …"-Teil, warum heute heruntergestuft wird. */
    fun downgradeReason(kind: DailyRecommendationKind): String

    /** Hinweis am Zieltag: keine Runde, die Strecke steht schon. */
    fun eventDayNote(km: Int): String

    /** Am Ruhetag steht eine Einheit im Plan, die ausgesetzt wird. */
    fun sessionSkippedNote(sessionTitle: String, km: Int, reason: String): String

    /**
     * „Plan: 90 km bergig – heute auf 55 km flach reduziert, weil …". Nennt
     * beide Zahlen, damit die Abweichung zum Plan nachvollziehbar bleibt.
     */
    fun downgradeNote(
        plannedKm: Int,
        plannedAscent: AscentPreference,
        adjustedKm: Int,
        adjustedAscent: AscentPreference,
        distanceChanged: Boolean,
        ascentChanged: Boolean,
        reason: String,
    ): String

    // Erinnerungen
    fun reminderTodayTitle(): String
    fun reminderRestDay(): String
    fun reminderSession(sessionTitle: String, km: Int): String
    fun reminderWeeklyReviewTitle(): String
    fun reminderWeeklyReview(riddenKm: Int, targetKm: Int): String
    fun reminderNudgeTitle(days: Int): String
    fun reminderNudgeText(): String
}

internal object TodayTextsDe : TodayTexts {
    override fun ascentPreference(preference: AscentPreference) = when (preference) {
        AscentPreference.FLACH -> "Flach"
        AscentPreference.MODERAT -> "Wellig"
        AscentPreference.BERGIG -> "Bergig"
    }

    override fun restDayRideLabel() = "Ruhetag – locker rollen"
    override fun firstRoundLabel() = "Erste Runde"

    override fun downgradeReason(kind: DailyRecommendationKind) = when (kind) {
        DailyRecommendationKind.RUHETAG -> "deine Erholungssignale für eine Pause sprechen"
        DailyRecommendationKind.RECOVERY -> "deine Ermüdung gerade hoch ist"
        DailyRecommendationKind.LOCKER_Z2 ->
            "deine Erholungswerte heute nur für eine lockere Einheit reichen"
        DailyRecommendationKind.GRUNDLAGE ->
            "deine Erholungswerte für normales, aber nicht für volles Training sprechen"
        DailyRecommendationKind.HARTE_EINHEIT -> "deine Erholung passt"
    }

    override fun eventDayNote(km: Int) =
        "Heute ist dein Zielevent über $km km. Dafür braucht es " +
            "keine Runde vor der Haustür – die Strecke steht schon."

    override fun sessionSkippedNote(sessionTitle: String, km: Int, reason: String) =
        "Im Plan steht heute „$sessionTitle“ über $km km – ausgesetzt, weil " +
            "$reason. Schieb die Einheit lieber um einen Tag."

    override fun downgradeNote(
        plannedKm: Int,
        plannedAscent: AscentPreference,
        adjustedKm: Int,
        adjustedAscent: AscentPreference,
        distanceChanged: Boolean,
        ascentChanged: Boolean,
        reason: String,
    ): String {
        val plannedLabel = ascentPreference(plannedAscent).lowercase()
        val adjustedLabel = ascentPreference(adjustedAscent).lowercase()
        val change = when {
            distanceChanged && ascentChanged -> "heute auf $adjustedKm km $adjustedLabel reduziert"
            distanceChanged -> "heute auf $adjustedKm km reduziert"
            else -> "heute $adjustedLabel statt $plannedLabel"
        }
        return "Plan: $plannedKm km $plannedLabel – $change, weil $reason."
    }

    override fun reminderTodayTitle() = "Heute"
    override fun reminderRestDay() = "Ruhetag — im Plan steht heute keine Einheit."
    override fun reminderSession(sessionTitle: String, km: Int) = "$sessionTitle, $km km"
    override fun reminderWeeklyReviewTitle() = "Wochenrückschau"
    override fun reminderWeeklyReview(riddenKm: Int, targetKm: Int) =
        "Diese Woche: $riddenKm von $targetKm km gefahren."
    override fun reminderNudgeTitle(days: Int) = "Seit $days Tagen keine Tour"
    override fun reminderNudgeText() =
        "Wenn du wieder unterwegs bist, zeichnet Trailscape die Runde auf — " +
            "auch eine kurze zählt für die Auswertung."
}

internal object TodayTextsEn : TodayTexts {
    override fun ascentPreference(preference: AscentPreference) = when (preference) {
        AscentPreference.FLACH -> "Flat"
        AscentPreference.MODERAT -> "Rolling"
        AscentPreference.BERGIG -> "Hilly"
    }

    override fun restDayRideLabel() = "Rest day – easy spin"
    override fun firstRoundLabel() = "First loop"

    override fun downgradeReason(kind: DailyRecommendationKind) = when (kind) {
        DailyRecommendationKind.RUHETAG -> "your recovery signals point to a break"
        DailyRecommendationKind.RECOVERY -> "your fatigue is high right now"
        DailyRecommendationKind.LOCKER_Z2 ->
            "your recovery values only allow an easy session today"
        DailyRecommendationKind.GRUNDLAGE ->
            "your recovery values allow normal but not full training"
        DailyRecommendationKind.HARTE_EINHEIT -> "your recovery is fine"
    }

    override fun eventDayNote(km: Int) =
        "Today is your $km km goal event. You don’t need a loop from " +
            "your front door for it – the route is already set."

    override fun sessionSkippedNote(sessionTitle: String, km: Int, reason: String) =
        "Your plan has “$sessionTitle” over $km km today – skipped because " +
            "$reason. Better move the session by a day."

    override fun downgradeNote(
        plannedKm: Int,
        plannedAscent: AscentPreference,
        adjustedKm: Int,
        adjustedAscent: AscentPreference,
        distanceChanged: Boolean,
        ascentChanged: Boolean,
        reason: String,
    ): String {
        val plannedLabel = ascentPreference(plannedAscent).lowercase()
        val adjustedLabel = ascentPreference(adjustedAscent).lowercase()
        val change = when {
            distanceChanged && ascentChanged -> "reduced to $adjustedKm km $adjustedLabel today"
            distanceChanged -> "reduced to $adjustedKm km today"
            else -> "$adjustedLabel instead of $plannedLabel today"
        }
        return "Plan: $plannedKm km $plannedLabel – $change because $reason."
    }

    override fun reminderTodayTitle() = "Today"
    override fun reminderRestDay() = "Rest day — there’s no session in your plan today."
    override fun reminderSession(sessionTitle: String, km: Int) = "$sessionTitle, $km km"
    override fun reminderWeeklyReviewTitle() = "Weekly review"
    override fun reminderWeeklyReview(riddenKm: Int, targetKm: Int) =
        "This week: $riddenKm of $targetKm km ridden."
    override fun reminderNudgeTitle(days: Int) = "No ride for $days days"
    override fun reminderNudgeText() =
        "When you’re out again, Trailscape records the loop — " +
            "even a short one counts for your analysis."
}
