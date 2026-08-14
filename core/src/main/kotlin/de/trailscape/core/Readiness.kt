package de.trailscape.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Erholung: Ruhepuls, HRV, Schlaf, Readiness (§5) und die daraus abgeleiteten
 * Empfehlungen (§6.2/§6.3).
 *
 * 1:1-Portierung des entsprechenden Abschnitts aus `lib/training_load.dart`.
 */

/** Ampelstufe eines Erholungssignals. */
enum class RecoveryFlag { UNBEKANNT, GRUEN, GELB, ORANGE, ROT }

val recoveryFlagLabels: Map<RecoveryFlag, String> = mapOf(
    RecoveryFlag.UNBEKANNT to "keine Aussage",
    RecoveryFlag.GRUEN to "unauffällig",
    RecoveryFlag.GELB to "leicht erhöht",
    RecoveryFlag.ORANGE to "deutlich auffällig",
    RecoveryFlag.ROT to "stark auffällig",
)

internal fun atLeast(flag: RecoveryFlag, min: RecoveryFlag): Boolean =
    flag.ordinal >= min.ordinal

/** Bewertung der Ruhepuls-Tagesserie (§5.1). */
data class RestingHrAssessment(
    val available: Boolean,
    val unavailableReason: String?,
    /** Median der Tage −60 … −8. */
    val baseline: Double?,
    /** `1.4826 × MAD` derselben Tage. */
    val sigma: Double?,
    /** Median der letzten 3 Tage — die Groesse, gegen die bewertet wird. */
    val current: Double?,
    /**
     * Juengster gemessener Tageswert in bpm — **nur zur Anzeige**.
     *
     * [current] ist ein 3-Tage-Median und damit eine Ableitung; wer „Ruhepuls
     * 52 bpm" liest, erwartet aber die Messung von heute Nacht. Dasselbe
     * Prinzip wie [HrvAssessment.lastRmssd].
     */
    val last: Double?,
    val deltaBpm: Double?,
    val z: Double?,
    val flag: RecoveryFlag,
    /** Anzahl gueltiger Werte im Baseline-Fenster (Gate: ≥ 21). */
    val baselineDays: Int,
    /** Wie viele aufeinanderfolgende Messungen bereits auffaellig sind. */
    val streakDays: Int,
    val message: String,
) {
    companion object {
        fun unavailable(
            reason: String,
            baselineDays: Int,
            last: Double? = null,
        ): RestingHrAssessment =
            RestingHrAssessment(
                available = false,
                unavailableReason = reason,
                baseline = null,
                sigma = null,
                current = null,
                last = last,
                deltaBpm = null,
                z = null,
                flag = RecoveryFlag.UNBEKANNT,
                baselineDays = baselineDays,
                streakDays = 0,
                message = reason,
            )
    }
}

private data class DayValue(val day: LocalDateTime, val value: Double)

private fun normalizeDaily(
    series: List<DailyValue>,
    min: Double = 0.0,
    max: Double = Double.POSITIVE_INFINITY,
): List<DayValue> {
    val byDay = linkedMapOf<LocalDateTime, Double>()
    for (v in series) {
        if (!v.value.isFinite() || v.value < min || v.value > max) {
            continue
        }
        byDay[atMidnight(v.day)] = v.value
    }
    val days = byDay.keys.sorted()
    return days.map { DayValue(it, byDay[it]!!) }
}

/**
 * Bewertet den Ruhepuls gegen die eigene, rollierende Baseline (§5.1).
 *
 * [afterHardDay] passt nur die Formulierung an (nach einer harten Tour sind
 * +3–5 bpm normal) — nicht die Stufe.
 */
fun assessRestingHeartRate(
    series: List<DailyValue>,
    today: LocalDateTime? = null,
    afterHardDay: Boolean = false,
): RestingHrAssessment {
    val values = normalizeDaily(series, min = 25.0, max = 130.0)
    if (values.isEmpty()) {
        return RestingHrAssessment.unavailable(
            "Noch keine Ruhepuls-Werte vorhanden.",
            0,
        )
    }
    val ref = atMidnight(today ?: values.last().day)
    val lastValue = values.lastOrNull { dayDifference(ref, it.day) >= 0 }?.value

    val baselineValues = values
        .filter {
            val diff = dayDifference(ref, it.day)
            diff in 8..60
        }
        .map { it.value }

    if (baselineValues.size < 21) {
        return RestingHrAssessment.unavailable(
            "Ruhepuls-Baseline wird aufgebaut (${baselineValues.size} von 21 Tagen).",
            baselineValues.size,
            last = lastValue,
        )
    }

    val baseline = median(baselineValues)!!
    val sigma = max(madSigma(baselineValues, baseline) ?: 0.0, 1.5)

    val recent = values.filter { dayDifference(ref, it.day) <= 2 }
    if (recent.isEmpty()) {
        return RestingHrAssessment.unavailable(
            "Kein aktueller Ruhepuls-Wert (letzte 3 Tage).",
            baselineValues.size,
            last = lastValue,
        )
    }
    val current = median(recent.map { it.value })!!
    val delta = current - baseline
    val z = delta / sigma

    // Streaks ueber die tatsaechlich vorhandenen Messungen, rueckwaerts ab heute.
    val descending = values.reversed().filter { dayDifference(ref, it.day) >= 0 }

    fun streak(minDelta: Double, minZ: Double?, maxSpanDays: Int): Int {
        var count = 0
        for (v in descending) {
            if (dayDifference(ref, v.day) > maxSpanDays) {
                break
            }
            val d = v.value - baseline
            val zz = d / sigma
            if (d >= minDelta && (minZ == null || zz >= minZ)) {
                count++
            } else {
                break
            }
        }
        return count
    }

    val yellowStreak = streak(3.0, 1.0, 3)
    val redStreak = streak(5.0, null, 5)

    var flag = RecoveryFlag.GRUEN
    if (delta >= 3 && z >= 1.0 && yellowStreak >= 2) {
        flag = RecoveryFlag.GELB
    }
    if (delta >= 5 && z >= 1.5) {
        flag = RecoveryFlag.ORANGE
    }
    if (delta >= 8 || redStreak >= 3) {
        flag = RecoveryFlag.ROT
    }

    val rounded = if (abs(delta) < 0.05) "0,0" else toStringAsFixed(abs(delta), 1)
    val signed = if (delta >= 0.05) "+$rounded" else (if (delta <= -0.05) "−$rounded" else "±0,0")
    val message = when (flag) {
        RecoveryFlag.GRUEN ->
            "Dein Ruhepuls liegt im gewohnten Bereich ($signed bpm gegenüber " +
                "deinem Normalwert)."

        RecoveryFlag.GELB -> if (afterHardDay) {
            "Dein Ruhepuls liegt +$rounded bpm über deinem Normalwert — " +
                "nach der gestrigen Belastung erwartbar."
        } else {
            "Dein Ruhepuls liegt seit mindestens zwei Messungen +$rounded bpm " +
                "über deinem Normalwert. Das kann an Training, Schlaf, Stress, " +
                "Alkohol, Hitze oder einem beginnenden Infekt liegen."
        }

        RecoveryFlag.ORANGE ->
            "Dein Ruhepuls liegt deutlich über deinem Normalwert (+$rounded bpm). " +
                "Das kann an Training, Schlaf, Stress, Alkohol, Hitze oder einem " +
                "Infekt liegen."

        RecoveryFlag.ROT ->
            "Dein Ruhepuls liegt seit mehreren Tagen klar über deinem Normalwert " +
                "(+$rounded bpm) — das kann an Training, Schlaf, Stress oder einem " +
                "Infekt liegen."

        RecoveryFlag.UNBEKANNT -> "Keine Aussage möglich."
    }

    return RestingHrAssessment(
        available = true,
        unavailableReason = null,
        baseline = baseline,
        sigma = sigma,
        current = current,
        last = lastValue,
        deltaBpm = delta,
        z = z,
        flag = flag,
        baselineDays = baselineValues.size,
        streakDays = max(yellowStreak, redStreak),
        message = message,
    )
}

// ---------------------------------------------------------------------------
// Herzratenvariabilitaet (rMSSD)
// ---------------------------------------------------------------------------

/** Lage der HRV gegenueber dem persoenlichen Normalband. */
enum class HrvStatus {
    UNBEKANNT,

    /** Unter dem Band — typisch fuer Belastung, Stress, Schlafmangel, Infekt. */
    NIEDRIG,

    /** Innerhalb des Bands. */
    IM_BAND,

    /** Ueber dem Band, Ruhepuls unauffaellig — gutes Zeichen. */
    UEBER_BAND,

    /**
     * Ueber dem Band **bei gleichzeitig erhoehtem Ruhepuls**: moegliche
     * parasympathische Saettigung, kein Freibrief fuer harte Reize.
     */
    SAETTIGUNG,
}

val hrvStatusLabels: Map<HrvStatus, String> = mapOf(
    HrvStatus.UNBEKANNT to "keine Aussage",
    HrvStatus.NIEDRIG to "unter deinem Normalband",
    HrvStatus.IM_BAND to "im Normalband",
    HrvStatus.UEBER_BAND to "über deinem Normalband",
    HrvStatus.SAETTIGUNG to "über dem Band bei erhöhtem Ruhepuls",
)

/**
 * Bewertung der naechtlichen HRV (rMSSD) gegen die persoenliche Baseline.
 *
 * Methodik nach Plews & Laursen bzw. HRV4Training: Einzelwerte sind
 * rechtsschief verteilt, deshalb wird `ln(rMSSD)` verwendet; verglichen wird
 * nicht der Tageswert, sondern das [hrvRollingDays]-Tage-Rollmittel gegen ein
 * [hrvBaselineDays]-Tage-Mittel plus Normalband
 * `Baseline ± hrvBandFactor × SD`.
 *
 * Zwei Groessen, die nicht verwechselt werden duerfen:
 *
 *  * [z] misst die Abweichung in **Tageswert**-Streuungen. Das ist die Skala,
 *    auf der das Normalband gezeichnet wird (Plews' „smallest worthwhile
 *    change") und auf der eine Abweichung physiologisch eingeordnet wird.
 *  * [zMean] misst dieselbe Abweichung in **Standardfehlern des Rollmittels**
 *    (`σ/√n`). Das ist die Skala, auf der sich sagen laesst, ob die Abweichung
 *    ueberhaupt gesichert ist — bei drei getragenen Naechten eben viel weniger
 *    als bei sieben.
 */
data class HrvAssessment(
    val available: Boolean,
    val unavailableReason: String?,
    /**
     * Mittelwert von `ln(rMSSD)` im Baselinefenster (Tage [hrvRollingDays] …
     * [hrvBaselineDays]−1, also **ohne** das Rollfenster).
     */
    val baselineLn: Double?,
    /** Streuung derselben Tage, mindestens [hrvMinSigmaLn]. */
    val sigmaLn: Double?,
    /** [hrvRollingDays]-Tage-Rollmittel von `ln(rMSSD)`. */
    val currentLn: Double?,
    /** Juengster Tageswert in ms — nur zur Anzeige, nie zur Bewertung. */
    val lastRmssd: Double?,
    /**
     * `(currentLn − baselineLn) / sigmaLn` auf Tageswert-Skala; das Normalband
     * endet bei ±[hrvBandFactor].
     */
    val z: Double?,
    /**
     * Dieselbe Abweichung, normiert auf den Standardfehler des Rollmittels
     * (`sigmaLn / √recentDays`) — die Sicherheitsachse der Eskalation.
     */
    val zMean: Double?,
    val status: HrvStatus,
    val flag: RecoveryFlag,
    /** Gueltige Tage im Baselinefenster (Gate: ≥ [hrvMinBaselineDays]). */
    val historyDays: Int,
    /** Gueltige Tage im Rollfenster (Gate: ≥ [hrvMinRecentDays]). */
    val recentDays: Int,
    val message: String,
) {
    /** Rollmittel in ms (geometrisches Mittel der letzten Tage). */
    val currentRmssd: Double? get() = currentLn?.let { exp(it) }

    /** Baseline in ms. */
    val baselineRmssd: Double? get() = baselineLn?.let { exp(it) }

    /** Untere Bandgrenze in ms. */
    val bandLowRmssd: Double?
        get() = baselineLn?.let { exp(it - hrvBandFactor * sigmaLn!!) }

    /** Obere Bandgrenze in ms. */
    val bandHighRmssd: Double?
        get() = baselineLn?.let { exp(it + hrvBandFactor * sigmaLn!!) }

    /** Abweichung des Rollmittels von der Baseline in Prozent. */
    val deviationPercent: Double?
        get() = if (currentLn == null || baselineLn == null) {
            null
        } else {
            (exp(currentLn - baselineLn) - 1) * 100
        }

    companion object {
        /** Zustand „gar keine HRV uebergeben" — Defaultwert von [computeReadiness]. */
        val MISSING = HrvAssessment(
            available = false,
            unavailableReason = "Noch keine HRV-Werte vorhanden.",
            baselineLn = null,
            sigmaLn = null,
            currentLn = null,
            lastRmssd = null,
            z = null,
            zMean = null,
            status = HrvStatus.UNBEKANNT,
            flag = RecoveryFlag.UNBEKANNT,
            historyDays = 0,
            recentDays = 0,
            message = "Noch keine HRV-Werte vorhanden.",
        )

        fun unavailable(
            reason: String,
            historyDays: Int,
            lastRmssd: Double? = null,
        ): HrvAssessment = HrvAssessment(
            available = false,
            unavailableReason = reason,
            baselineLn = null,
            sigmaLn = null,
            currentLn = null,
            // Auch ohne Bewertung darf der zuletzt gemessene Wert angezeigt
            // werden — er ist eine Messung, keine Ableitung.
            lastRmssd = lastRmssd,
            z = null,
            zMean = null,
            status = HrvStatus.UNBEKANNT,
            flag = RecoveryFlag.UNBEKANNT,
            historyDays = historyDays,
            recentDays = 0,
            message = reason,
        )
    }
}

private fun mean(values: Iterable<Double>): Double {
    var sum = 0.0
    var n = 0
    for (v in values) {
        sum += v
        n++
    }
    return if (n == 0) Double.NaN else sum / n
}

/** Stichproben-Standardabweichung (n − 1), 0 bei weniger als zwei Werten. */
private fun stdDev(values: List<Double>): Double {
    if (values.size < 2) {
        return 0.0
    }
    val m = mean(values)
    var sum = 0.0
    for (v in values) {
        sum += (v - m) * (v - m)
    }
    return sqrt(sum / (values.size - 1))
}

/**
 * Bewertet die HRV-Tagesserie (rMSSD in ms) gegen die eigene Baseline.
 *
 * [restingHrFlag] wird nur fuer den Saettigungsfall gebraucht: Ein Wert **ueber**
 * dem Band ist fuer sich genommen ein gutes Zeichen — zusammen mit einem
 * erhoehten Ruhepuls ist er aber ein bekanntes Muster bei starker Ermuedung
 * (parasympathische Saettigung) und wird dann als Warnzeichen gefuehrt.
 *
 * ## Baseline und Rollfenster ueberlappen nicht
 * Die Baseline bildet sich aus den Tagen [hrvRollingDays] …
 * [hrvBaselineDays]−1, das Rollmittel aus den Tagen 0 … [hrvRollingDays]−1.
 * Frueher war die Baseline „letzte 28 Tage" und enthielt das Rollfenster: Ein
 * anhaltender Einbruch zog seine eigene Referenz mit und blaehte zugleich die
 * Streuung auf, sodass [RecoveryFlag.ROT] praktisch unerreichbar war. Der
 * getrennte Zuschnitt ist Voraussetzung dafuer, dass die Schwellen ueberhaupt
 * bedeuten, was sie sagen.
 *
 * ## Zwei Achsen fuer die Eskalation
 * GELB entscheidet das Normalband auf Tagesskala (Plews & Laursen, SWC).
 * ORANGE und ROT verlangen zusaetzlich, dass die Abweichung auch als Mittel
 * abgesichert ist ([hrvOrangeMeanZ] / [hrvRedMeanZ] auf der SEM-Skala) — sonst
 * koennten drei verrauschte Naechte einen Ruhetag ausloesen.
 */
fun assessHrv(
    series: List<DailyValue>,
    today: LocalDateTime? = null,
    restingHrFlag: RecoveryFlag = RecoveryFlag.UNBEKANNT,
): HrvAssessment {
    val values = normalizeDaily(series, min = hrvMinMs, max = hrvMaxMs)
    if (values.isEmpty()) {
        return HrvAssessment.unavailable("Noch keine HRV-Werte vorhanden.", 0)
    }
    val ref = atMidnight(today ?: values.last().day)

    // Juengster Wert bis einschliesslich heute — nur fuer die Anzeige.
    val lastValue = values.lastOrNull { dayDifference(ref, it.day) >= 0 }?.value

    val recent = values.filter {
        val diff = dayDifference(ref, it.day)
        diff >= 0 && diff < hrvRollingDays
    }
    val window = values.filter {
        val diff = dayDifference(ref, it.day)
        diff >= hrvRollingDays && diff < hrvBaselineDays
    }

    if (window.size < hrvMinBaselineDays) {
        val missing = hrvMinBaselineDays - window.size
        return HrvAssessment.unavailable(
            "Braucht noch $missing ${if (missing == 1) "Tag" else "Tage"} HRV-Daten " +
                "(${window.size} von $hrvMinBaselineDays im Vergleichszeitraum).",
            window.size,
            lastRmssd = lastValue,
        )
    }

    val baselineLn = mean(window.map { ln(it.value) })
    val sigmaLn = max(
        stdDev(window.map { ln(it.value) }),
        hrvMinSigmaLn,
    )

    if (recent.size < hrvMinRecentDays) {
        return HrvAssessment.unavailable(
            "Zu wenige HRV-Messungen in den letzten sieben Tagen " +
                "(${recent.size} von $hrvMinRecentDays).",
            window.size,
            lastRmssd = lastValue,
        )
    }

    val currentLn = mean(recent.map { ln(it.value) })
    val z = (currentLn - baselineLn) / sigmaLn
    // Standardfehler des Rollmittels: n Messungen mitteln die Tagesstreuung
    // um den Faktor √n herunter.
    val zMean = (currentLn - baselineLn) / (sigmaLn / sqrt(recent.size.toDouble()))

    val status: HrvStatus
    var flag = RecoveryFlag.GRUEN
    if (z <= -hrvBandFactor) {
        status = HrvStatus.NIEDRIG
        flag = RecoveryFlag.GELB
        if (z <= hrvOrangeDailyZ && zMean <= hrvOrangeMeanZ) {
            flag = RecoveryFlag.ORANGE
        }
        if (z <= hrvRedDailyZ && zMean <= hrvRedMeanZ) {
            flag = RecoveryFlag.ROT
        }
    } else if (z >= hrvBandFactor) {
        if (atLeast(restingHrFlag, RecoveryFlag.GELB)) {
            status = HrvStatus.SAETTIGUNG
            flag = RecoveryFlag.ORANGE
        } else {
            status = HrvStatus.UEBER_BAND
        }
    } else {
        status = HrvStatus.IM_BAND
    }

    val current = dartRound(exp(currentLn)).toInt()
    val low = dartRound(exp(baselineLn - hrvBandFactor * sigmaLn)).toInt()
    val high = dartRound(exp(baselineLn + hrvBandFactor * sigmaLn)).toInt()

    val message = when (status) {
        HrvStatus.NIEDRIG -> if (flag == RecoveryFlag.GELB) {
            "Deine HRV liegt im 7-Tage-Mittel mit $current ms knapp unter deinem " +
                "Normalband ($low–$high ms). Das kann an Training, Schlaf, Stress, " +
                "Alkohol oder einem beginnenden Infekt liegen."
        } else {
            "Deine HRV liegt im 7-Tage-Mittel mit $current ms deutlich unter deinem " +
                "Normalband ($low–$high ms). Das kann an Training, Schlaf, Stress, " +
                "Alkohol oder einem Infekt liegen."
        }

        HrvStatus.IM_BAND ->
            "Deine HRV liegt im 7-Tage-Mittel mit $current ms in deinem Normalband " +
                "($low–$high ms)."

        HrvStatus.UEBER_BAND ->
            "Deine HRV liegt im 7-Tage-Mittel mit $current ms über deinem Normalband " +
                "($low–$high ms) — dein Nervensystem wirkt gut erholt."

        HrvStatus.SAETTIGUNG ->
            "Deine HRV liegt im 7-Tage-Mittel mit $current ms über deinem Normalband " +
                "($low–$high ms), gleichzeitig ist dein Ruhepuls erhöht. Diese " +
                "Kombination kommt auch bei starker Ermüdung vor — beobachte die " +
                "nächsten Tage, bevor du hart trainierst."

        HrvStatus.UNBEKANNT -> "Keine Aussage möglich."
    }

    return HrvAssessment(
        available = true,
        unavailableReason = null,
        baselineLn = baselineLn,
        sigmaLn = sigmaLn,
        currentLn = currentLn,
        lastRmssd = lastValue,
        z = z,
        zMean = zMean,
        status = status,
        flag = flag,
        historyDays = window.size,
        recentDays = recent.size,
        message = message,
    )
}

/** Bewertung der Schlafserie gegen die persoenliche Baseline (§5.2). */
data class SleepAssessment(
    val available: Boolean,
    val unavailableReason: String?,
    /** Persoenlicher 28-Tage-Median, geklemmt auf 4,5–9,5 h. */
    val baselineH: Double?,
    val sigmaH: Double?,
    val lastNightH: Double?,
    /** Abweichung der letzten Nacht vom eigenen Normalwert in Stunden. */
    val deviationH: Double?,
    val z: Double?,
    /** Kumuliertes Defizit der letzten 7 Naechte in Stunden (≤ 0). */
    val debt7dH: Double?,
    val flag: RecoveryFlag,
    val validNights: Int,
    /**
     * Baseline < 6,5 h — loest nur einen separaten Info-Hinweis aus, **nie**
     * eine Drosselung der Tagesempfehlung (§8.3).
     */
    val shortSleeper: Boolean,
    val message: String,
) {
    companion object {
        fun unavailable(reason: String, validNights: Int): SleepAssessment = SleepAssessment(
            available = false,
            unavailableReason = reason,
            baselineH = null,
            sigmaH = null,
            lastNightH = null,
            deviationH = null,
            z = null,
            debt7dH = null,
            flag = RecoveryFlag.UNBEKANNT,
            validNights = validNights,
            shortSleeper = false,
            message = reason,
        )
    }
}

/** Nicht-blockierender Gesundheitshinweis fuer chronische Kurzschlaefer (§5.2). */
const val shortSleeperHint: String =
    "Dein üblicher Schlaf liegt seit Wochen unter 6,5 Stunden. Für Erwachsene " +
        "werden 7–9 Stunden empfohlen, bei viel Training eher mehr — mehr Schlaf " +
        "verbessert Regeneration und Leistung. Deine Tagesempfehlung ändert das " +
        "nicht."

/** Ob der Kurzschlaefer-Hinweis gezeigt werden darf (hoechstens 1×/Monat). */
fun shouldShowShortSleeperHint(lastShownAt: LocalDateTime?, now: LocalDateTime): Boolean =
    lastShownAt == null || dayDifference(now, lastShownAt) >= 30

/** Bewertet den Schlaf als Abweichung vom eigenen Normalwert (§5.2). */
fun assessSleep(
    series: List<DailyValue>,
    today: LocalDateTime? = null,
    restingHrFlag: RecoveryFlag = RecoveryFlag.UNBEKANNT,
): SleepAssessment {
    // Sensorartefakte ausschliessen: < 2 h und > 14 h zaehlen nicht.
    val values = normalizeDaily(series, min = 2.0, max = 14.0)
    if (values.isEmpty()) {
        return SleepAssessment.unavailable("Noch keine Schlafdaten vorhanden.", 0)
    }
    val ref = atMidnight(today ?: values.last().day)

    val window = values.filter {
        val diff = dayDifference(ref, it.day)
        diff in 0..27
    }

    if (window.size < 14) {
        return SleepAssessment.unavailable(
            "Schlaf-Baseline wird aufgebaut (${window.size} von 14 Nächten).",
            window.size,
        )
    }

    val raw = median(window.map { it.value })!!
    val baseline = clamp(raw, 4.5, 9.5)
    val sigma = max(madSigma(window.map { it.value }, raw) ?: 0.0, 0.5)

    val recent = window.filter { dayDifference(ref, it.day) <= 1 }
    if (recent.isEmpty()) {
        return SleepAssessment.unavailable(
            "Keine aktuelle Schlafmessung vorhanden.",
            window.size,
        )
    }
    val lastNight = recent.last().value
    val deviation = lastNight - baseline
    val z = deviation / sigma

    var debt = 0.0
    for (v in window) {
        if (dayDifference(ref, v.day) < 7) {
            debt += min(0.0, v.value - baseline)
        }
    }

    // Die Gelb-Regel ist eine ODER-Bedingung und kann sich mit „gruen"
    // (dev ≥ −0,5 h) ueberlappen. In dem Fall gewinnt die strengere Stufe — das
    // ist die in §8.3 gewollte Wirkung der MAD-Skalierung: ein sehr
    // regelmaessiger Schlaefer (σ klein) reagiert empfindlicher als ein
    // schwankender.
    var flag = RecoveryFlag.GRUEN
    if (deviation <= -1.0 || z <= -1.0) {
        flag = RecoveryFlag.GELB
    }
    if (deviation <= -1.5 || debt <= -4) {
        flag = RecoveryFlag.ORANGE
    }
    if (deviation <= -2.5 && atLeast(restingHrFlag, RecoveryFlag.GELB)) {
        flag = RecoveryFlag.ROT
    }

    val devText = toStringAsFixed(abs(deviation), 1)
    val message = when (flag) {
        RecoveryFlag.GRUEN ->
            "Dein Schlaf entspricht deinem Normalwert (${toStringAsFixed(baseline, 1)} h)."

        RecoveryFlag.GELB -> "Du hast $devText h weniger geschlafen als sonst."

        RecoveryFlag.ORANGE ->
            "Dein Schlaf liegt deutlich unter deinem Normalwert " +
                "(−$devText h; 7-Tage-Defizit ${toStringAsFixed(debt, 1)} h)."

        RecoveryFlag.ROT ->
            "Deutlich zu wenig Schlaf (−$devText h) bei gleichzeitig erhöhtem " +
                "Ruhepuls."

        RecoveryFlag.UNBEKANNT -> "Keine Aussage möglich."
    }

    return SleepAssessment(
        available = true,
        unavailableReason = null,
        baselineH = baseline,
        sigmaH = sigma,
        lastNightH = lastNight,
        deviationH = deviation,
        z = z,
        debt7dH = debt,
        flag = flag,
        validNights = window.size,
        shortSleeper = baseline < 6.5,
        message = message,
    )
}

/** Baender des Readiness-Scores (§5.4). */
enum class ReadinessBand { HART, NORMAL, LOCKER, RUHE }

val readinessBandLabels: Map<ReadinessBand, String> = mapOf(
    ReadinessBand.HART to "bereit für eine harte Einheit",
    ReadinessBand.NORMAL to "normales Training",
    ReadinessBand.LOCKER to "locker / Z2",
    ReadinessBand.RUHE to "Ruhe oder sehr locker",
)

fun classifyReadiness(score: Double): ReadinessBand {
    if (score >= 80) return ReadinessBand.HART
    if (score >= 60) return ReadinessBand.NORMAL
    if (score >= 40) return ReadinessBand.LOCKER
    return ReadinessBand.RUHE
}

/** Trailscape Readiness Score (§5.4). */
data class Readiness(
    val available: Boolean,
    val unavailableReason: String?,
    /** 0…100. Nur bei [available] aussagekraeftig. */
    val score: Double,
    val band: ReadinessBand,
    /**
     * Strafterme nach §5.4 — unveraendert die Rohwerte, auch wenn sie fuer den
     * Score normiert und gewichtet zusammengefuehrt werden.
     */
    val penaltyRhr: Double,
    val penaltySleep: Double,
    val penaltyLoad: Double,
    val restingHr: RestingHrAssessment,
    val sleep: SleepAssessment,
    val tsb: Double?,
    val confidence: Confidence,
    val headline: String,
    val detail: String,
    val hrv: HrvAssessment = HrvAssessment.MISSING,
    /** HRV-Strafterm auf der Skala 0…100 (nur gesetzt, wenn [usesHrv]). */
    val penaltyHrv: Double = 0.0,
    /**
     * Ob HRV in den Score eingeflossen ist. Aendert **nicht** mehr die Formel,
     * sondern nur, ob [readinessWeightHrv] verteilt wurde — und damit die
     * [confidence].
     */
    val usesHrv: Boolean = false,
    /**
     * Anteil des Gesamtgewichts, der von tatsaechlich vorhandenen Signalen
     * abgedeckt ist (1,0 = alle vier). Macht sichtbar, auf wie viel der Score
     * ueberhaupt beruht.
     */
    val signalCoverage: Double = 0.0,
)

/**
 * Berechnet den Readiness-Score aus HRV, Ruhepuls, Schlaf und Form (§5.4).
 *
 * Der Score erscheint nur, wenn alle drei Confidence-Gates halten: ≥ 21
 * Ruhepuls-Werte, ≥ 14 Schlafnaechte, ≥ 28 Tage Trainingshistorie.
 *
 * ## Eine Formel, egal welche Signale da sind
 * Jeder Strafterm wird auf 0…100 normiert und mit seinem Gewicht
 * ([readinessWeightHrv] & Co.) verrechnet; **fehlende Signale bekommen kein
 * Gewicht, und die restlichen Gewichte werden auf ihre Summe renormiert**.
 * Ein fehlendes Signal verhaelt sich damit wie „so gut oder schlecht wie der
 * Durchschnitt der vorhandenen" — die neutralste verfuegbare Annahme.
 *
 * Frueher gab es zwei Formeln: gewichtetes Mittel mit HRV, Summe der
 * Strafterme ohne. Bei identischer Physiologie ergaben dieselben Strafterme
 * 55 gegen 77 Punkte, und weil `usesHrv` allein daran haengt, ob die Uhr
 * genug Naechte getragen wurde, sprang der Score ueber die Baender
 * (40/60/80) hin und her. Der Unterschied lag nicht im Koerper, sondern im
 * Handgelenk.
 *
 * Fehlende Signale senken jetzt die [Readiness.confidence] und
 * [Readiness.signalCoverage], **nicht** den Score — Unwissen darf weder
 * bestrafen noch belohnen.
 */
fun computeReadiness(
    restingHr: RestingHrAssessment,
    sleep: SleepAssessment,
    hrv: HrvAssessment = HrvAssessment.MISSING,
    tsb: Double? = null,
    trainingHistoryDays: Int = 0,
): Readiness {
    val restingHrZ = restingHr.z
    val usesRhr = restingHr.available && restingHrZ != null
    val penaltyRhr = if (usesRhr) {
        clamp((restingHrZ - 0.5) * 18, 0.0, maxPenaltyRhr)
    } else {
        0.0
    }

    var penaltySleep = 0.0
    if (sleep.available) {
        val sleepZ = sleep.z
        if (sleepZ != null) {
            penaltySleep += clamp((-sleepZ - 0.5) * 12, 0.0, 30.0)
        }
        val debt = sleep.debt7dH
        if (debt != null) {
            penaltySleep += clamp((-debt - 2) * 4, 0.0, 15.0)
        }
    }

    val penaltyLoad = if (tsb != null) {
        clamp((-tsb - 20) * 1.2, 0.0, maxPenaltyLoad)
    } else {
        0.0
    }

    // HRV-Strafterm auf der Skala 0…100: greift ab dem unteren Bandrand
    // (z = −0,75) und ist bei z ≈ −2,75 voll ausgereizt. Die parasympathische
    // Saettigung kostet die Haelfte — sie ist ein Warnzeichen, aber ein deutlich
    // unsichereres als ein echter Einbruch.
    val hrvZ = hrv.z
    val usesHrv = hrv.available && hrvZ != null
    var penaltyHrv = 0.0
    if (usesHrv) {
        penaltyHrv = clamp((-hrvZ - hrvBandFactor) * 50, 0.0, 100.0)
        if (hrv.status == HrvStatus.SAETTIGUNG) {
            penaltyHrv = max(penaltyHrv, 50.0)
        }
    }

    // Ein Eintrag je Signal: (Gewicht, Strafterm auf 0…100), nur wenn das
    // Signal wirklich vorliegt.
    val parts = mutableListOf<Pair<Double, Double>>()
    if (usesHrv) {
        parts.add(readinessWeightHrv to penaltyHrv)
    }
    if (usesRhr) {
        parts.add(readinessWeightRhr to (penaltyRhr / maxPenaltyRhr * 100))
    }
    if (sleep.available) {
        parts.add(readinessWeightSleep to (penaltySleep / maxPenaltySleep * 100))
    }
    if (tsb != null) {
        parts.add(readinessWeightLoad to (penaltyLoad / maxPenaltyLoad * 100))
    }

    val coverage = parts.sumOf { it.first }
    val score = if (coverage <= 0) {
        // Kein einziges Signal: Es gibt nichts zu bestrafen. Der Gate unten
        // sorgt dafuer, dass dieser Wert nie als Aussage erscheint.
        100.0
    } else {
        clamp(100 - parts.sumOf { it.first * it.second } / coverage, 0.0, 100.0)
    }
    val band = classifyReadiness(score)

    val missing = mutableListOf<String>()
    if (!restingHr.available) {
        missing.add("Ruhepuls")
    }
    if (!sleep.available) {
        missing.add("Schlaf")
    }
    if (trainingHistoryDays < 28) {
        missing.add("Trainingshistorie")
    }
    val available = missing.isEmpty()

    // Vollstaendigkeit statt Formelwechsel: Wer alle vier Signale liefert,
    // bekommt den belastbarsten Score; wem HRV fehlt, dem sagen wir es an der
    // Confidence — nicht an einem stillschweigend anderen Rechenweg.
    val confidence = when {
        !available -> Confidence.NONE
        coverage >= 0.99 -> Confidence.HIGH
        coverage >= 0.55 -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    return Readiness(
        available = available,
        unavailableReason = if (available) {
            null
        } else {
            "Noch nicht genug Daten für einen Gesamtwert " +
                "(${missing.joinToString(", ")}). Die einzelnen Signale siehst du trotzdem."
        },
        score = score,
        band = band,
        penaltyRhr = penaltyRhr,
        penaltySleep = penaltySleep,
        penaltyLoad = penaltyLoad,
        penaltyHrv = penaltyHrv,
        restingHr = restingHr,
        sleep = sleep,
        hrv = hrv,
        tsb = tsb,
        usesHrv = usesHrv,
        signalCoverage = coverage,
        confidence = confidence,
        headline = if (available) {
            "Erholung: ${dartRound(score).toInt()} — ${readinessBandLabels[band]}"
        } else {
            "Erholung noch nicht berechenbar"
        },
        detail = if (available) {
            if (usesHrv) {
                "Basierend auf HRV, Ruhepuls, Schlaf und Trainingslast — " +
                    "ein Trendindikator, keine Messung."
            } else {
                "Basierend auf Ruhepuls, Schlaf und Trainingslast (ohne HRV) — " +
                    "ein Trendindikator, keine Messung. Ohne HRV fehlt das " +
                    "direkteste Signal; der Wert ist deshalb unsicherer."
            }
        } else {
            "Sobald genug Tage vorliegen, fassen wir Ruhepuls, Schlaf und " +
                "Trainingslast zu einem Wert zusammen."
        },
    )
}

/** Ein Tag der rueckwirkend berechneten Readiness-Reihe. */
data class ReadinessPoint(val day: LocalDateTime, val readiness: Readiness)

private fun upTo(series: List<DailyValue>, day: LocalDateTime): List<DailyValue> {
    val ref = atMidnight(day)
    return series.filter { !atMidnight(it.day).isAfter(ref) }
}

/**
 * Berechnet die Readiness der letzten [days] Tage rueckwirkend.
 *
 * Fuer jeden Tag zaehlt nur, was **bis dahin** vorlag: Vitalserien werden auf
 * den jeweiligen Stichtag beschnitten, TSB und Historienlaenge kommen aus dem
 * passenden Punkt der Fitness-Kurve. Damit laesst sich der Deload-Trigger
 * „Readiness < 40 an ≥ 3 von 7 Tagen" (§6.2) ohne Persistenz auswerten.
 *
 * Die Liste ist aufsteigend nach Datum und enthaelt auch Tage ohne
 * Gesamtscore (dann `readiness.available == false`).
 */
fun computeReadinessSeries(
    restingHrSeries: List<DailyValue> = emptyList(),
    sleepSeries: List<DailyValue> = emptyList(),
    hrvSeries: List<DailyValue> = emptyList(),
    fitness: FitnessSeries = FitnessSeries.EMPTY,
    today: LocalDateTime? = null,
    days: Int = 7,
): List<ReadinessPoint> {
    if (days <= 0) {
        return emptyList()
    }
    val ref = atMidnight(today ?: LocalDateTime.now())
    val points = mutableListOf<ReadinessPoint>()

    for (offset in days - 1 downTo 0) {
        val day = addDays(ref, -offset)

        // Stand der Fitness-Kurve an diesem Tag (letzter Punkt bis einschliesslich
        // Stichtag) plus die bis dahin abgedeckten Historientage.
        var point: FitnessPoint? = null
        var historyDays = 0
        for (p in fitness.points) {
            if (p.day.isAfter(day)) {
                break
            }
            point = p
            historyDays++
        }

        val restingHr = assessRestingHeartRate(
            upTo(restingHrSeries, day),
            today = day,
        )
        val hrv = assessHrv(
            upTo(hrvSeries, day),
            today = day,
            restingHrFlag = restingHr.flag,
        )
        val sleep = assessSleep(
            upTo(sleepSeries, day),
            today = day,
            restingHrFlag = restingHr.flag,
        )

        points.add(
            ReadinessPoint(
                day = day,
                readiness = computeReadiness(
                    restingHr = restingHr,
                    sleep = sleep,
                    hrv = hrv,
                    tsb = point?.tsb,
                    trainingHistoryDays = historyDays,
                ),
            ),
        )
    }

    return points
}

/**
 * Die Scores der Tage, an denen ein Gesamtwert berechenbar war — genau das,
 * was [assessDeload] als `readinessLast7` erwartet.
 */
fun availableReadinessScores(points: Iterable<ReadinessPoint>): List<Double> =
    points.filter { it.readiness.available }.map { it.readiness.score }

// ---------------------------------------------------------------------------
// 6. Empfehlungen (§6.2/§6.3)
// ---------------------------------------------------------------------------

/** Art der Tagesempfehlung (§6.3). */
enum class DailyRecommendationKind {
    RUHETAG,
    LOCKER_Z2,
    RECOVERY,
    HARTE_EINHEIT,
    GRUNDLAGE,
}

/** Konkrete Empfehlung fuer heute. */
data class DailyRecommendation(
    val kind: DailyRecommendationKind,
    val title: String,
    val detail: String,
    /** Warum diese Empfehlung — nur beschreibend, keine Diagnose. */
    val reasons: List<String>,
)

/** Tagesempfehlung aus Readiness, Ampeln und Form (§6.3). */
fun recommendToday(
    readiness: Readiness,
    tsb: Double? = null,
    hitBudgetLeft: Boolean = true,
): DailyRecommendation {
    val rhr = readiness.restingHr.flag
    val sleep = readiness.sleep.flag
    val hrv = if (readiness.hrv.available) readiness.hrv.flag else RecoveryFlag.UNBEKANNT
    val reasons = mutableListOf<String>()
    if (readiness.hrv.available) {
        reasons.add(readiness.hrv.message)
    }
    if (readiness.restingHr.available) {
        reasons.add(readiness.restingHr.message)
    }
    if (readiness.sleep.available) {
        reasons.add(readiness.sleep.message)
    }
    if (tsb != null) {
        reasons.add(tsbBandMessages[classifyTsb(tsb)]!!)
    }

    // Ohne Gesamtscore steuern nur die vorhandenen Einzelsignale.
    val score = if (readiness.available) readiness.score else null

    // Die Ampeln greifen zusaetzlich zum Score: Ein einzelnes, klar auffaelliges
    // Signal soll auch dann durchschlagen, wenn die Gewichtung es im
    // Gesamtwert abfedert.
    if ((score != null && score < 40) ||
        rhr == RecoveryFlag.ROT ||
        hrv == RecoveryFlag.ROT
    ) {
        return DailyRecommendation(
            kind = DailyRecommendationKind.RUHETAG,
            title = "Heute besser Ruhetag",
            detail = "Deine Erholungssignale sprechen für Pause statt Training.",
            reasons = reasons,
        )
    }
    if ((score != null && score < 60) ||
        sleep == RecoveryFlag.ORANGE ||
        hrv == RecoveryFlag.ORANGE
    ) {
        return DailyRecommendation(
            kind = DailyRecommendationKind.LOCKER_Z2,
            title = "Locker in Z2, 60–90 min",
            detail = "Keine Intervalle — halte die Intensität heute im " +
                "Grundlagenbereich.",
            reasons = reasons,
        )
    }
    if (tsb != null && tsb < -25) {
        return DailyRecommendation(
            kind = DailyRecommendationKind.RECOVERY,
            title = "Regenerationsfahrt in Z1/Z2",
            detail = "Deine Ermüdung ist gerade hoch — kurz und locker fahren.",
            reasons = reasons,
        )
    }
    if (score != null &&
        score >= 80 &&
        tsb != null &&
        tsb > -20 &&
        hitBudgetLeft &&
        // Eine HRV unter dem Normalband reicht, um den harten Reiz zu vertagen.
        !atLeast(hrv, RecoveryFlag.GELB)
    ) {
        return DailyRecommendation(
            kind = DailyRecommendationKind.HARTE_EINHEIT,
            title = "Harte Einheit möglich (Z4/Z5)",
            detail = "Erholung und Form passen — heute darf ein harter Reiz rein.",
            reasons = reasons,
        )
    }
    return DailyRecommendation(
        kind = DailyRecommendationKind.GRUNDLAGE,
        title = "Grundlageneinheit",
        detail = "Fahre nach dem Restbudget deiner Woche, überwiegend Z2.",
        reasons = reasons,
    )
}

/** Empfehlung fuer eine Entlastungswoche (§6.2). */
data class DeloadRecommendation(
    val recommended: Boolean,
    /** Ausgeloeste Deload-Trigger (deutschsprachig). */
    val triggers: List<String>,
    /** Weiche Hinweise (z. B. Wochenlastsprung), die keinen Deload ausloesen. */
    val warnings: List<String>,
    val title: String,
    val detail: String,
) {
    /** Empfohlene Volumenreduktion (Anteil), Intensitaet bleibt erhalten. */
    val volumeReductionLow: Double get() = 0.40
    val volumeReductionHigh: Double get() = 0.50
}

/**
 * Prueft die Deload-Trigger aus §6.2 / §8.2.
 *
 * [readinessLast7] sind die Readiness-Scores der letzten sieben Tage
 * (Reihenfolge egal, nur vorhandene Werte uebergeben).
 */
fun assessDeload(
    series: FitnessSeries,
    readinessLast7: List<Double> = emptyList(),
    weeklyLoad: Double? = null,
    fourWeekMeanWeeklyLoad: Double? = null,
): DeloadRecommendation {
    val triggers = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    val tail = series.lastDays(3)
    if (tail.size == 3 && tail.all { it.tsb < -30 }) {
        triggers.add("Dein Formwert liegt seit drei Tagen sehr tief.")
    }

    val points = series.points
    val rampDays = listOf(points.size - 1, points.size - 8, points.size - 15)
    if (rampDays.all { it >= 0 }) {
        val ramps = rampDays.map { points[it].rampRate7d }
        if (ramps.all { it != null && it > 8 }) {
            triggers.add("Deine Fitness ist seit drei Wochen sehr schnell gestiegen.")
        }
    }

    val lowReadiness = readinessLast7.count { it < 40 }
    if (lowReadiness >= 3) {
        triggers.add(
            "Deine Erholung lag an $lowReadiness von sieben Tagen im unteren Bereich.",
        )
    }

    if (weeklyLoad != null &&
        fourWeekMeanWeeklyLoad != null &&
        fourWeekMeanWeeklyLoad > 0 &&
        weeklyLoad > 1.3 * fourWeekMeanWeeklyLoad
    ) {
        warnings.add("Deine Belastung ist diese Woche deutlich gestiegen.")
    }

    val latest = series.latest
    if (latest?.loadRatio != null &&
        classifyLoadRatio(latest.loadRatio) == LoadRatioBand.BELASTUNGSSPRUNG
    ) {
        warnings.add(
            "Deine akute Belastung liegt klar über deinem gewohnten Niveau.",
        )
    }

    val recommended = triggers.isNotEmpty()
    return DeloadRecommendation(
        recommended = recommended,
        triggers = triggers,
        warnings = warnings,
        title = if (recommended) "Entlastungswoche empfohlen" else "Kein Deload nötig",
        detail = if (recommended) {
            "Nimm das Wochenvolumen um 40–50 % zurück und behalte die Intensität " +
                "bei — kurze harte Reize dürfen drinbleiben."
        } else {
            "Deine Belastung sieht aktuell tragfähig aus."
        },
    )
}
