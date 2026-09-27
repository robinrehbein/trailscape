package de.trailscape.core.i18n

import de.trailscape.core.FitnessDirection
import de.trailscape.core.TsbBand

/**
 * Texte der Zielprognose und der Formkarte (`GoalPrognosis.kt`).
 *
 * Die Zeitangabe „h:mm" der Prognose ([de.trailscape.core.formatGoalDuration])
 * ist sprachneutral und laeuft deshalb nicht hierueber.
 */
interface GoalTexts {
    fun predictionNeedsDistance(): String

    /** Noch keine passende Tour; [needKm] ist die Mindestlaenge. */
    fun predictionNeedsRides(needKm: Int): String

    /** Eine passende Tour fehlt noch. */
    fun predictionNeedsOneMoreRide(needKm: Int): String

    /** „Fitness steigt seit 6 Wochen" / „Fitness stabil". [weeks] ≥ 1. */
    fun fitnessTrend(direction: FitnessDirection, weeks: Int): String

    /** Die Form als ein Wort: „frisch", „etwas müde" … */
    fun freshnessWord(band: TsbBand): String
}

internal object GoalTextsDe : GoalTexts {
    override fun predictionNeedsDistance() = "Für eine Prognose braucht das Ziel eine Distanz."
    override fun predictionNeedsRides(needKm: Int) =
        "Fahre 2–3 längere Touren (ab etwa $needKm km), dann gibt es eine Prognose."
    override fun predictionNeedsOneMoreRide(needKm: Int) =
        "Noch eine längere Tour (ab etwa $needKm km), dann gibt es eine Prognose."

    override fun fitnessTrend(direction: FitnessDirection, weeks: Int): String {
        val verb = when (direction) {
            FitnessDirection.STABIL -> return "Fitness stabil"
            FitnessDirection.STEIGT -> "steigt"
            FitnessDirection.SINKT -> "sinkt"
        }
        return if (weeks >= 2) "Fitness $verb seit $weeks Wochen" else "Fitness $verb"
    }

    override fun freshnessWord(band: TsbBand) = when (band) {
        TsbBand.SEHR_FRISCH -> "sehr frisch"
        TsbBand.FORMSPITZE -> "frisch"
        TsbBand.NEUTRAL -> "ausgeglichen"
        TsbBand.PRODUKTIV -> "etwas müde"
        TsbBand.UEBERLASTUNG -> "sehr müde"
    }
}

internal object GoalTextsEn : GoalTexts {
    override fun predictionNeedsDistance() = "A prediction needs a goal distance."
    override fun predictionNeedsRides(needKm: Int) =
        "Ride 2–3 longer rides (from about $needKm km) to get a prediction."
    override fun predictionNeedsOneMoreRide(needKm: Int) =
        "One more longer ride (from about $needKm km) and you’ll get a prediction."

    override fun fitnessTrend(direction: FitnessDirection, weeks: Int): String {
        val verb = when (direction) {
            FitnessDirection.STABIL -> return "Fitness stable"
            FitnessDirection.STEIGT -> "rising"
            FitnessDirection.SINKT -> "falling"
        }
        return if (weeks >= 2) "Fitness $verb for $weeks weeks" else "Fitness $verb"
    }

    override fun freshnessWord(band: TsbBand) = when (band) {
        TsbBand.SEHR_FRISCH -> "very fresh"
        TsbBand.FORMSPITZE -> "fresh"
        TsbBand.NEUTRAL -> "balanced"
        TsbBand.PRODUKTIV -> "a bit tired"
        TsbBand.UEBERLASTUNG -> "very tired"
    }
}
