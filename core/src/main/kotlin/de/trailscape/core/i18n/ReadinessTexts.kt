package de.trailscape.core.i18n

import de.trailscape.core.DailyRecommendationKind
import de.trailscape.core.HrvStatus
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import kotlin.math.abs

/**
 * Saetze und Labels der Erholungsauswertung (`Readiness.kt`): Ruhepuls, HRV,
 * Schlaf, Gesamtwert, Tagesempfehlung und Entlastungswoche.
 *
 * Die Argumente sind Zahlen und Typen, nie fertige Satzteile — die
 * Implementierung entscheidet ueber Dezimaltrennzeichen, Plural und
 * Satzbau.
 */
interface ReadinessTexts {
    fun recoveryFlag(flag: RecoveryFlag): String
    fun hrvStatus(status: HrvStatus): String
    fun readinessBand(band: ReadinessBand): String

    /** „Keine Aussage möglich." — fuer den Zustand ohne Bewertung. */
    fun noStatement(): String

    // Ruhepuls
    fun restingHrNoValues(): String
    fun restingHrBaselineBuilding(days: Int, needed: Int): String
    fun restingHrNoRecent(): String

    /** Ruhepuls im gewohnten Bereich; [deltaBpm] mit Vorzeichen. */
    fun restingHrGreen(deltaBpm: Double): String
    fun restingHrYellow(deltaBpm: Double, afterHardDay: Boolean): String
    fun restingHrOrange(deltaBpm: Double): String
    fun restingHrRed(deltaBpm: Double): String

    // HRV
    fun hrvNoValues(): String
    fun hrvNeedsDays(missing: Int, have: Int, needed: Int): String
    fun hrvTooFewRecent(have: Int, needed: Int): String
    fun hrvLow(currentMs: Int, lowMs: Int, highMs: Int, clearly: Boolean): String
    fun hrvInBand(currentMs: Int, lowMs: Int, highMs: Int): String
    fun hrvAboveBand(currentMs: Int, lowMs: Int, highMs: Int): String
    fun hrvSaturation(currentMs: Int, lowMs: Int, highMs: Int): String

    // Schlaf
    fun sleepNoData(): String
    fun sleepBaselineBuilding(nights: Int, needed: Int): String
    fun sleepNoRecent(): String
    fun sleepGreen(baselineH: Double): String
    fun sleepYellow(deficitH: Double): String
    fun sleepOrange(deficitH: Double, debt7dH: Double): String
    fun sleepRed(deficitH: Double): String

    /** Nicht-blockierender Gesundheitshinweis fuer chronische Kurzschlaefer. */
    fun shortSleeperHint(): String

    // Gesamtwert
    fun signalName(signal: ReadinessSignal): String
    fun readinessUnavailable(missing: List<ReadinessSignal>): String
    fun readinessHeadline(score: Int, band: ReadinessBand): String
    fun readinessHeadlineUnavailable(): String
    fun readinessDetail(usesHrv: Boolean): String
    fun readinessDetailUnavailable(): String

    // Tagesempfehlung
    fun recommendationTitle(kind: DailyRecommendationKind): String
    fun recommendationDetail(kind: DailyRecommendationKind): String

    // Entlastungswoche
    fun deloadTriggerLowForm(): String
    fun deloadTriggerFastRamp(): String
    fun deloadTriggerLowReadiness(days: Int): String
    fun deloadWarningWeeklyJump(): String
    fun deloadWarningAcuteLoad(): String
    fun deloadTitle(recommended: Boolean): String
    fun deloadDetail(recommended: Boolean): String
}

/** Die Signale, die fuer einen Gesamtwert noch fehlen koennen. */
enum class ReadinessSignal { RESTING_HR, SLEEP, TRAINING_HISTORY }

/**
 * Betrag einer Abweichung mit einer Nachkommastelle — „0,0" statt „−0,0"
 * bei kaum messbaren Werten.
 */
internal fun absOneDecimal(value: Double, language: AppLanguage): String =
    if (abs(value) < 0.05) formatDecimal(0.0, 1, language) else formatDecimal(abs(value), 1, language)

/** „+2,1" / „−2,1" / „±0,0" — der Ruhepuls gegen den Normalwert. */
internal fun signedOneDecimal(value: Double, language: AppLanguage): String {
    val rounded = absOneDecimal(value, language)
    return when {
        value >= 0.05 -> "+$rounded"
        value <= -0.05 -> "−$rounded"
        else -> "±$rounded"
    }
}
