package de.trailscape.core.i18n

import de.trailscape.core.FitnessLevel
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingSession
import de.trailscape.core.WeekKind

/**
 * Texte des Trainingsplans: Titel und Beschreibungen der Einheiten, die
 * Klartext-Wochenliste, Fehler und Hinweise der Planerzeugung.
 *
 * ## Warum Plantexte einen Schluessel bekommen
 * Titel und Beschreibung einer Einheit stehen im gespeicherten Plan-JSON —
 * ein Plan, der auf Deutsch erzeugt wurde, hiesse nach dem Sprachwechsel
 * sonst weiter „Lange Tour". Deshalb traegt jede neu erzeugte Einheit einen
 * [SessionTextKey] plus ganzzahlige Argumente ([TrainingSession.textArgs]);
 * angezeigt wird ausschliesslich ueber [sessionTitle]/[sessionDescription],
 * die den Text in der aktuellen Sprache neu bauen. Der gespeicherte
 * Titel/Text bleibt als Rueckfall fuer Plaene aus der Zeit davor, fuer
 * Backups und fuer aeltere App-Versionen, die die neuen Felder nicht kennen.
 */
interface TrainingTexts {
    fun errorTooSoon(): String
    fun errorTooFar(): String

    fun fitnessLevel(level: FitnessLevel): String
    fun weekKind(kind: WeekKind): String
    fun sessionIntensity(intensity: SessionIntensity): String
    fun planSessionStatus(status: PlanSessionStatus): String

    /**
     * Titel einer Einheit. [eventName] ist nur fuer [SessionTextKey.GOAL_EVENT]
     * gesetzt (der Name des Ziels).
     */
    fun sessionTitle(key: SessionTextKey, args: List<Int>, eventName: String?): String
    fun sessionDescription(key: SessionTextKey, args: List<Int>): String

    // Klartext der Wochenliste
    fun plainTitleEvent(km: Int): String
    fun plainTitleLongRide(km: Int): String
    fun plainTitleHard(km: Int): String
    fun plainTitleEasy(km: Int): String
    fun plainHintEvent(): String
    fun plainHintLongRide(hilly: Boolean): String
    fun plainHintHard(): String
    fun plainHintRecovery(): String
    fun plainHintEndurance(): String

    // Plananpassung und Machbarkeit
    fun planAdapted(weekNumber: Int, percent: Int): String

    /**
     * Hinweis, dass der Plan sein Ziel nicht traegt. [suggestedWeeks] ist
     * `null`, wenn selbst ein Jahr nicht reicht; [needsLongerPlan], wenn der
     * Vorschlag laenger ist als der Plan.
     */
    fun planNotFeasible(
        longestKm: Int,
        percent: Int,
        goalKm: Int,
        suggestedKm: Int,
        suggestedWeeks: Int?,
        needsLongerPlan: Boolean,
    ): String
}

/**
 * Stabiler Schluessel eines Plantexts. [jsonName] steht im Plan-JSON und
 * darf sich nie aendern; neue Einheiten bekommen neue Schluessel.
 *
 * Die erwarteten [TrainingSession.textArgs] stehen je Eintrag dabei.
 */
enum class SessionTextKey(val jsonName: String) {
    /** Erholungswoche, „Lockere Ausfahrt". Keine Argumente. */
    RECOVERY_EASY_RIDE("recoveryEasyRide"),

    /** Erholungswoche, „Ruhige Runde". Keine Argumente. */
    RECOVERY_CALM_LOOP("recoveryCalmLoop"),

    /** Taper, „Locker mit Antritten". Keine Argumente. */
    TAPER_EASY_SURGES("taperEasySurges"),

    /** Taper, „Kurze lockere Ausfahrt". Keine Argumente. */
    TAPER_SHORT_EASY("taperShortEasy"),

    /** Einsteiger, „Lockere Ausfahrt GA1". Keine Argumente. */
    BASE_EASY_RIDE("baseEasyRide"),

    /** Die lange Fahrt der Woche. Argumente: `[mitAnstiegen (0/1)]`. */
    LONG_RIDE("longRide"),

    /** Einsteiger, „Regeneration locker". Keine Argumente. */
    RECOVERY_SPIN("recoverySpin"),

    /** Fortgeschritten, „GA1" zum Auffuellen. Keine Argumente. */
    ENDURANCE_FILL("enduranceFill"),

    /** Ambitioniert, „GA1" ohne Spitzen. Keine Argumente. */
    ENDURANCE_STEADY("enduranceSteady"),

    /** Ambitioniert, „GA1 kompensatorisch". Keine Argumente. */
    ENDURANCE_COMPENSATION("enduranceCompensation"),

    /**
     * Schwellenintervalle. Argumente:
     * `[einfahrenMin, wiederholungen, belastungMin, pauseMin, ausfahrenMin, gesamtMin, haerte]`
     * mit `haerte` 0 = zuegig im Schwellenbereich, 1 = hart an der Schwelle.
     */
    INTERVALS("intervals"),

    /** Das Zielevent. Argumente: `[km, hoehenmeter]`, Hoehenmeter `-1` ohne Anstiegshinweis. */
    GOAL_EVENT("goalEvent"),

    /** Zielwoche, „Aktivierung locker". Keine Argumente. */
    ACTIVATION("activation"),
    ;

    companion object {
        /** `null` bei unbekanntem Namen (Plan aus einer neueren App-Version). */
        fun fromJsonNameOrNull(name: String?): SessionTextKey? =
            entries.firstOrNull { it.jsonName == name }
    }
}

/** Index des Intervall-Arguments „haerte" (siehe [SessionTextKey.INTERVALS]). */
internal const val INTERVAL_ARG_EFFORT = 6

/**
 * Titel einer Planeinheit in der Sprache von [texts]. Mit
 * [TrainingSession.textKey] neu gebaut, ohne (Altplan) der gespeicherte Titel.
 */
fun sessionTitle(session: TrainingSession, texts: CoreTexts): String {
    val key = session.textKey ?: return session.title
    val eventName = if (key == SessionTextKey.GOAL_EVENT) {
        // Der Name des Ziels steht nur im gespeicherten Titel („Zielevent: X"
        // bzw. „Goal event: X") — beide Sprachen trennen mit „: ".
        session.title.substringAfter(": ", session.title)
    } else {
        null
    }
    return runCatching { texts.training.sessionTitle(key, session.textArgs, eventName) }
        .getOrDefault(session.title)
}

/**
 * Beschreibung einer Planeinheit in der Sprache von [texts]. Mit
 * [TrainingSession.textKey] neu gebaut, ohne (Altplan) der gespeicherte Text.
 * Passen die Argumente nicht zum Schluessel, bleibt es beim gespeicherten Text.
 */
fun sessionDescription(session: TrainingSession, texts: CoreTexts): String {
    val key = session.textKey ?: return session.description
    return runCatching { texts.training.sessionDescription(key, session.textArgs) }
        .getOrDefault(session.description)
}
