package de.trailscape.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tour-Auswertung: Entkopplung und VO2max (§7.2/§7.3).
 *
 * 1:1-Portierung des entsprechenden Abschnitts aus `lib/training_load.dart`.
 */

/** Pe:Hr-Entkopplung einer Tour (§7.2). */
data class DecouplingResult(
    val available: Boolean,
    val unavailableReason: String?,
    /** Efficiency Factor der ersten Haelfte (geschaetzte NP pro bpm). */
    val efFirst: Double?,
    val efSecond: Double?,
    /** `(EF1 − EF2) / EF1 × 100`. */
    val decouplingPercent: Double?,
    val variabilityIndex: Double?,
    /** Einordnung nach Friel/TrainingPeaks. */
    val rating: String?,
    val confidence: Confidence,
) {
    companion object {
        fun unavailable(reason: String): DecouplingResult = DecouplingResult(
            available = false,
            unavailableReason = reason,
            efFirst = null,
            efSecond = null,
            decouplingPercent = null,
            variabilityIndex = null,
            rating = null,
            confidence = Confidence.NONE,
        )
    }
}

private fun decouplingRating(pct: Double): String {
    if (pct < 5) {
        return "gute aerobe Ausdauer"
    }
    if (pct <= 10) {
        return "aerobe Ausdauer im Aufbau"
    }
    return "mehr Grundlagenarbeit sinnvoll"
}

/** Berechnet die Pe:Hr-Entkopplung — **nur**, wenn alle Gates halten (§7.2). */
fun computeDecoupling(
    physics: PhysicsEstimate,
    profile: TrainingProfile,
): DecouplingResult {
    if (!physics.available) {
        return DecouplingResult.unavailable(
            physics.unavailableReason ?: "Kein Leistungsmodell verfügbar.",
        )
    }
    val series = physics.series
    if (series.movingTimeS < 3600) {
        return DecouplingResult.unavailable(
            "Für die Entkopplung braucht es mindestens 60 Minuten Bewegungszeit.",
        )
    }
    if (series.hrCoverage < 0.90) {
        return DecouplingResult.unavailable(
            "Für die Entkopplung braucht es Herzfrequenz auf mindestens 90 % der Fahrt.",
        )
    }
    val avgHr = series.avgHr
    if (avgHr == null || avgHr <= 0) {
        return DecouplingResult.unavailable("Keine Herzfrequenz vorhanden.")
    }
    val relative = avgHr / profile.lthr
    if (relative < 0.70 || relative > 0.95) {
        return DecouplingResult.unavailable(
            "Die Tour lag nicht im gleichmäßig-aeroben Bereich — " +
                "die Entkopplung wäre nicht aussagekräftig.",
        )
    }
    if (physics.variabilityIndex > 1.15) {
        return DecouplingResult.unavailable(
            "Die Fahrt war zu ungleichmäßig für eine Entkopplungs-Analyse.",
        )
    }

    // Haelften nach Bewegungszeit teilen (Pausen sind bereits ausgeschlossen).
    val half = series.movingTimeS / 2
    var acc = 0.0
    var splitIndex = 0
    for (i in series.samples.indices) {
        acc += series.samples[i].dtS
        if (acc >= half) {
            splitIndex = i + 1
            break
        }
    }
    if (splitIndex <= 0 || splitIndex >= series.samples.size) {
        return DecouplingResult.unavailable(
            "Die Tour lässt sich nicht in zwei vergleichbare Hälften teilen.",
        )
    }

    val firstHalf = series.slice(0, splitIndex)
    val secondHalf = series.slice(splitIndex, series.samples.size)

    val gain1 = firstHalf.ascentM
    val gain2 = secondHalf.ascentM
    val maxGain = max(gain1, gain2)
    if (maxGain > 0 && abs(gain1 - gain2) / maxGain > 0.35) {
        return DecouplingResult.unavailable(
            "Die beiden Tourhälften unterscheiden sich zu stark im Höhenprofil.",
        )
    }

    val hr1 = firstHalf.avgHr
    val hr2 = secondHalf.avgHr
    val np1 = firstHalf.normalizedPowerW
    val np2 = secondHalf.normalizedPowerW
    if (hr1 == null || hr2 == null || hr1 <= 0 || hr2 <= 0 || np1 <= 0) {
        return DecouplingResult.unavailable(
            "Für eine der Tourhälften fehlen auswertbare Werte.",
        )
    }

    val ef1 = np1 / hr1
    val ef2 = np2 / hr2
    val pct = (ef1 - ef2) / ef1 * 100

    return DecouplingResult(
        available = true,
        unavailableReason = null,
        efFirst = ef1,
        efSecond = ef2,
        decouplingPercent = pct,
        variabilityIndex = physics.variabilityIndex,
        rating = decouplingRating(pct),
        // Die Leistung ist geschaetzt — mehr als „medium" ist nicht serioes.
        confidence = minConfidence(Confidence.MEDIUM, physics.confidence),
    )
}

/**
 * Rollierender Median der letzten fuenf qualifizierenden Entkopplungswerte
 * — Einzelwerte schwanken zu stark (§7.2).
 */
fun decouplingTrend(qualifyingValues: List<Double>): Double? {
    if (qualifyingValues.isEmpty()) {
        return null
    }
    val recent = if (qualifyingValues.size > 5) {
        qualifyingValues.subList(qualifyingValues.size - 5, qualifyingValues.size)
    } else {
        qualifyingValues
    }
    return median(recent)
}

/** Ein stabiles Submaximal-Segment fuer die VO2max-Regression (§7.3 B). */
data class SteadySegment(
    val avgPowerW: Double,
    val avgHr: Double,
    val durationS: Double,
)

/**
 * Extrahiert stabile Segmente (≥ 5 min, HF-Drift < 1 bpm/min, VI ≤ 1,1,
 * HF zwischen 60 % und 90 % HFmax) aus einer Leistungsreihe (§7.3 B).
 */
fun extractSteadySegments(
    series: PowerSeries,
    profile: TrainingProfile,
    minDurationS: Double = 300.0,
): List<SteadySegment> {
    val result = mutableListOf<SteadySegment>()
    if (series.isEmpty) {
        return result
    }
    val samples = series.samples
    var start = 0
    while (start < samples.size) {
        var end = start
        var duration = 0.0
        while (end < samples.size && duration < minDurationS) {
            if (samples[end].hr == null) {
                break
            }
            duration += samples[end].dtS
            end++
        }
        if (duration < minDurationS || end <= start) {
            start = end + 1
            continue
        }

        val block = samples.subList(start, end)
        val hrValues = block.map { it.hr!!.toDouble() }
        val avgHr = hrValues.reduce { a, b -> a + b } / hrValues.size
        val drift = abs(hrValues.last() - hrValues.first()) / (duration / 60)
        val avgP = block.fold(0.0) { a, s -> a + s.powerW * s.dtS } / duration
        val slice = series.slice(start, end)
        val vi = if (avgP > 0) slice.normalizedPowerW / avgP else Double.POSITIVE_INFINITY

        val inHrWindow = avgHr >= 0.60 * profile.hrMax && avgHr <= 0.90 * profile.hrMax
        if (drift < 1.0 && vi <= 1.1 && inHrWindow && avgP > 0) {
            result.add(
                SteadySegment(
                    avgPowerW = avgP,
                    avgHr = avgHr,
                    durationS = duration,
                ),
            )
        }
        start = end
    }
    return result
}

/** Welche VO2max-Methode zum Ergebnis gefuehrt hat (§7.3). */
enum class Vo2MaxMethod { KEINE, UTH_RATIO, REGRESSION, PLATTFORM }

/** VO2max-Schaetzung mit Unsicherheitsband (§7.3). */
data class Vo2MaxEstimate(
    val available: Boolean,
    val unavailableReason: String?,
    /** Punktschaetzung in ml·kg⁻¹·min⁻¹ — im UI **nie allein** zeigen. */
    val value: Double?,
    val lower: Double?,
    val upper: Double?,
    val method: Vo2MaxMethod,
    val r2: Double?,
    val segmentCount: Int,
    val hrSpanBpm: Double?,
    val confidence: Confidence,
) {
    /** Formulierung gemaess §8.5: immer als Band. */
    val text: String
        get() = if (available) {
            "VO2max geschätzt: ${dartRound(lower!!).toInt()}–${dartRound(upper!!).toInt()} ml/kg/min"
        } else {
            unavailableReason ?: "VO2max nicht schätzbar"
        }

    companion object {
        fun unavailable(reason: String): Vo2MaxEstimate = Vo2MaxEstimate(
            available = false,
            unavailableReason = reason,
            value = null,
            lower = null,
            upper = null,
            method = Vo2MaxMethod.KEINE,
            r2 = null,
            segmentCount = 0,
            hrSpanBpm = null,
            confidence = Confidence.NONE,
        )
    }
}

private fun bandedEstimate(
    value: Double,
    band: Double,
    method: Vo2MaxMethod,
    confidence: Confidence,
    r2: Double? = null,
    segmentCount: Int = 0,
    hrSpan: Double? = null,
): Vo2MaxEstimate {
    val v = clamp(value, 15.0, 90.0)
    return Vo2MaxEstimate(
        available = true,
        unavailableReason = null,
        value = v,
        lower = v * (1 - band),
        upper = v * (1 + band),
        method = method,
        r2 = r2,
        segmentCount = segmentCount,
        hrSpanBpm = hrSpan,
        confidence = confidence,
    )
}

/** VO2max nach Uth-Sørensen-Overgaard-Pedersen: `15,3 × HFmax / HFruhe` (§7.3 A). */
fun estimateVo2MaxFromHrRatio(profile: TrainingProfile): Vo2MaxEstimate {
    if (profile.restingHr <= 0) {
        return Vo2MaxEstimate.unavailable("Ohne Ruhepuls nicht schätzbar.")
    }
    return bandedEstimate(
        15.3 * profile.hrMax / profile.restingHr,
        vo2MaxBandRatio,
        Vo2MaxMethod.UTH_RATIO,
        Confidence.LOW,
    )
}

/**
 * VO2max aus submaximalen Segmenten (ACSM-Regression, §7.3 B).
 *
 * Gates: ≥ 6 Segmente, r² ≥ 0,80, HF-Spanne ≥ 25 bpm. Faellt eines davon,
 * liefert die Funktion einen „nicht berechenbar"-Zustand — der Aufrufer
 * weicht dann auf [estimateVo2MaxFromHrRatio] aus.
 */
fun estimateVo2MaxFromSegments(
    segments: List<SteadySegment>,
    profile: TrainingProfile,
): Vo2MaxEstimate {
    if (profile.weightKg <= 0) {
        return Vo2MaxEstimate.unavailable("Ohne Gewichtsangabe nicht schätzbar.")
    }
    val usable = segments.filter {
        it.avgHr > 0 &&
            it.avgPowerW >= 50 &&
            it.avgPowerW <= 200 &&
            it.avgPowerW.isFinite()
    }
    if (usable.size < 6) {
        return Vo2MaxEstimate.unavailable(
            "Zu wenige gleichmäßige Abschnitte (${usable.size} von 6).",
        )
    }

    val hrs = usable.map { it.avgHr }
    val span = hrs.reduce { a, b -> max(a, b) } - hrs.reduce { a, b -> min(a, b) }
    if (span < 25) {
        return Vo2MaxEstimate.unavailable(
            "Die Herzfrequenz-Spanne der Abschnitte ist zu klein.",
        )
    }

    // ACSM-Beinergometrie: VO2 = (10,8 × W) / kg + 7.
    val vo2 = usable.map { 10.8 * it.avgPowerW / profile.weightKg + 7 }

    val n = usable.size
    val meanHr = hrs.reduce { a, b -> a + b } / n
    val meanVo2 = vo2.reduce { a, b -> a + b } / n
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    for (i in 0 until n) {
        val dx = hrs[i] - meanHr
        val dy = vo2[i] - meanVo2
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }
    if (sxx <= 0 || syy <= 0) {
        return Vo2MaxEstimate.unavailable("Regression nicht bestimmbar.")
    }
    val slope = sxy / sxx
    val intercept = meanVo2 - slope * meanHr
    val r2 = (sxy * sxy) / (sxx * syy)

    if (r2 < 0.80) {
        return Vo2MaxEstimate.unavailable(
            "Der Zusammenhang zwischen Herzfrequenz und Leistung ist zu unscharf " +
                "(r² = ${toStringAsFixed(r2, 2)}).",
        )
    }
    if (slope <= 0) {
        return Vo2MaxEstimate.unavailable("Regression nicht plausibel.")
    }

    return bandedEstimate(
        slope * profile.hrMax + intercept,
        vo2MaxBandRegression,
        Vo2MaxMethod.REGRESSION,
        Confidence.MEDIUM,
        r2 = r2,
        segmentCount = n,
        hrSpan = span,
    )
}

/** Waehlt die beste verfuegbare VO2max-Quelle: Plattform > Regression > Uth. */
fun estimateVo2Max(
    profile: TrainingProfile,
    segments: List<SteadySegment> = emptyList(),
    platformValue: Double? = null,
): Vo2MaxEstimate {
    if (platformValue != null && platformValue > 0) {
        return bandedEstimate(
            platformValue,
            vo2MaxBandRegression,
            Vo2MaxMethod.PLATTFORM,
            Confidence.MEDIUM,
        )
    }
    val regression = estimateVo2MaxFromSegments(segments, profile)
    if (regression.available) {
        return regression
    }
    return estimateVo2MaxFromHrRatio(profile)
}

/** Rollierender 28-Tage-Median der VO2max-Punktwerte (§7.3, Edge Case). */
fun vo2MaxRollingMedian(values: List<DailyValue>, today: LocalDateTime? = null): Double? {
    if (values.isEmpty()) {
        return null
    }
    val ref = atMidnight(today ?: values.last().day)
    val window = values
        .filter {
            val diff = dayDifference(ref, it.day)
            diff in 0..27 && it.value > 0
        }
        .map { it.value }
    return median(window)
}

/** Ob eine VO2max-Aenderung kommuniziert werden sollte (≥ 2 ml/kg/min). */
fun vo2MaxChangeWorthShowing(previous: Double?, current: Double?): Boolean {
    if (previous == null || current == null) {
        return current != null
    }
    return abs(current - previous) >= 2
}
