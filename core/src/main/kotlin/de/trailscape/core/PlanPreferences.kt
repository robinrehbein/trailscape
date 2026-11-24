package de.trailscape.core

import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persoenliche Rahmenbedingungen eines Trainingsplans.
 *
 * ## Warum es das gibt
 * Bis hierher lagen die Einheiten fest auf Di/Do/Sa/So, und das Zeitbudget aus
 * [TrainingProfile.weeklyHours] spielte fuer den Plan keine Rolle — ein Plan
 * fuer jemanden mit 5 h pro Woche wuchs trotzdem auf 11 h. Jetzt bestimmt
 * [generatePlan] aus diesen Praeferenzen:
 *
 *  * **auf welche Tage** die Einheiten fallen ([trainingDays]): die Zahl der
 *    Einheiten ist `min(Stufenraster, Zahl der Tage)`, und die Tage werden so
 *    gewaehlt, dass die Qualitaet nie am Tag nach der langen Tour liegen muss
 *    und sie moeglichst gleichmaessig ueber die Woche verteilt sind;
 *  * **wann die lange Tour** liegt ([longRideDay]);
 *  * **wie viel Zeit** eine Woche hoechstens kostet ([weeklyHours]) — in jeder
 *    Woche, auch in Ziel- und Testwoche (ohne das Event). Wird das Budget
 *    knapp, sinkt zuerst die Zahl der Einheiten (je Einheit mindestens
 *    30 min), dann kuerzt der Plan: im Aufbau eines Eventplans zuerst die
 *    Wiederholungen der Qualitaet, dann die Grundlagenfahrten, dann die
 *    Qualitaet, die lange Tour zuletzt — nie durch Skalieren der Zahlen. Und
 *    [assessPlanFeasibility] sagt, wenn das Budget das Ziel gefaehrdet.
 *
 * Alle Felder sind optional; ein leeres Objekt verhaelt sich exakt wie „keine
 * Praeferenzen" (Stufenraster, lange Tour am Samstag, kein Zeitdeckel).
 *
 * ## JSON
 * Die Tage stehen als dieselben internen Kuerzel „Mo" … „So" im JSON wie
 * [TrainingSession.day] — ein Code, keine Anzeige. Unbekannte Kuerzel und
 * unsinnige Stunden werden beim Lesen still verworfen; der Plan bleibt lesbar.
 */
data class PlanPreferences(
    /**
     * Verfuegbare Trainingstage als Index 0 = Mo … 6 = So, aufsteigend und
     * ohne Doppelte; `null` oder leer = Stufenraster der App.
     */
    val trainingDays: List<Int>? = null,
    /** Bevorzugter Tag der langen Tour (0 = Mo … 6 = So); `null` = Samstag bzw. der naechstliegende Tag. */
    val longRideDay: Int? = null,
    /** Zeitbudget in Stunden pro Woche; `null` = kein Deckel. */
    val weeklyHours: Double? = null,
) {
    /** Ob die Praeferenzen ueberhaupt etwas festlegen — leere werden nicht gespeichert. */
    val isEmpty: Boolean
        get() = normalizedDays() == null && validLongRideDay() == null && validWeeklyHours() == null

    /** Die verfuegbaren Tage, bereinigt (0…6, sortiert, ohne Doppelte); `null` ohne Vorgabe. */
    fun normalizedDays(): List<Int>? =
        trainingDays?.filter { it in 0..6 }?.distinct()?.sorted()?.takeIf { it.isNotEmpty() }

    /** Der Tag der langen Tour, falls gueltig. */
    fun validLongRideDay(): Int? = longRideDay?.takeIf { it in 0..6 }

    /** Das Zeitbudget, falls plausibel (mehr als 0, hoechstens 40 h). */
    fun validWeeklyHours(): Double? =
        weeklyHours?.takeIf { it.isFinite() && it > 0 && it <= MAX_PLAUSIBLE_WEEKLY_HOURS }

    fun toJson(): JsonObject = buildJsonObject {
        normalizedDays()?.let { days ->
            put("trainingDays", buildJsonArray { days.forEach { add(PLAN_WEEKDAY_CODES[it]) } })
        }
        validLongRideDay()?.let { put("longRideDay", PLAN_WEEKDAY_CODES[it]) }
        validWeeklyHours()?.let { put("weeklyHours", it) }
    }

    companion object {
        /**
         * Obergrenze eines glaubwuerdigen Zeitbudgets. 40 h pro Woche faehrt
         * auch im Profibereich kaum jemand dauerhaft; alles darueber ist ein
         * Tippfehler und wird ignoriert statt zu einem absurden Plan.
         */
        const val MAX_PLAUSIBLE_WEEKLY_HOURS: Double = 40.0

        /** Liest tolerant: Unbekanntes faellt weg, nie eine Ausnahme. */
        fun fromJson(json: JsonObject): PlanPreferences {
            val days = (json["trainingDays"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.map { PLAN_WEEKDAY_CODES.indexOf(it) }
                ?.filter { it >= 0 }
            val longDay = (json["longRideDay"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.let { PLAN_WEEKDAY_CODES.indexOf(it) }
                ?.takeIf { it >= 0 }
            val hours = (json["weeklyHours"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            return PlanPreferences(
                trainingDays = days?.takeIf { it.isNotEmpty() },
                longRideDay = longDay,
                weeklyHours = hours,
            ).let { it.copy(weeklyHours = it.validWeeklyHours()) }
        }
    }
}

/**
 * Phase einer Planwoche — die Periodisierung ueber die Laufzeit.
 *
 * **Nicht gespeichert**, sondern aus der Wochenfolge abgeleitet
 * ([planWeekPhases]): Das Plan-JSON ist mit Web-App und Sync-Server
 * abgestimmt, und eine Groesse, die sich jederzeit aus den gespeicherten
 * Wochenarten berechnen laesst, waere nur eine zweite Wahrheit.
 */
enum class TrainingPhase {
    /** Grundlage: Umfang aufbauen, Qualitaet moderat (Sweet Spot / Tempo). */
    GRUNDLAGE,

    /** Aufbau: Schwellen- und VO2max-Reize mit steigender Belastungszeit. */
    AUFBAU,

    /** Spitze / spezifisch: zielnahe Reize (Zieltempo, Intervalle am Berg). */
    SPEZIFISCH,

    /** Entlastungswoche im Belastungsrhythmus. */
    ERHOLUNG,

    /** Taper vor dem Event. */
    TAPER,

    /** Zielwoche mit dem Event. */
    EVENT,

    /** Abschluss eines Fitnessblocks: Testwoche statt Event. */
    TEST,
}
