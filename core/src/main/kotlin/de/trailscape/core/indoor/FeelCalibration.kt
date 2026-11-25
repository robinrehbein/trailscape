package de.trailscape.core.indoor

import de.trailscape.core.gravity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * # Geführter Abgleich „Fahrgefühl einstellen“ (reine Rechnung)
 *
 * Ablauf (App): 2–3 Minuten gleichmäßig treten, auf einer flachen Teststrecke
 * ([flatCalibrationRoute]), in einem festen Gang. Die App liefert je Sekunde eine
 * [FeelSample] (3-s-Leistung, Trittfrequenz, simulierte Geschwindigkeit der
 * Sitzung); [FeelCalibrator] sammelt sie, [evaluateFeel] rechnet.
 *
 * ## Die Rechnung
 *
 * Wer mit Trittfrequenz c im virtuellen Gang G_v tritt, fährt draußen
 * `v_g = c/60 · G_v · U` (U = Radumfang, [WHEEL_CIRCUMFERENCE_M]). In der Ebene
 * widersteht ihm `F = m·g·Crr + cw·v_g²` — das ist der **Erwartungswert**. Der
 * Trainer bekommt nur Simulationsparameter (siehe `VirtualGears.kt`): Der
 * **Luftanteil** (cw·k³, Wind/k) ist in den Parametern vollständig enthalten und
 * kommt beim Trainer so an, wie gesendet. Der **massenabhängige Anteil**
 * (Rollen) geht dagegen über die unbekannte Trainermasse und ist genau das, was
 * `trainerScale` ausgleicht:
 *
 * ```
 * F_gemessen = P·η / v_g  =  F_Luft + s_wirksam · F_Rollen,    s_wirksam = λ · trainerScale
 * ```
 *
 * Gesucht ist der neue `trainerScale`, bei dem `s_wirksam = 1` wird, also
 * `neu = alt · F_Rollen / (F_gemessen − F_Luft)`. Zusätzlich liefert das Ergebnis
 * `powerRatio = P_erwartet / P_gemessen` als anschauliche Zahl („der Trainer
 * lag bei 93 % der erwarteten Leistung“).
 *
 * Eine ebene Strecke schätzt den Massenanteil nur grob: Er macht bei 30 km/h
 * etwa 30 % des Widerstands aus, 3 % Leistungsfehler werden also zu rund 10 %
 * Fehler im Faktor. Deshalb gibt es klare Ablehnung statt Schätzung ([FeelRejection]),
 * Plausibilitätsgrenzen und die Genauigkeit im Ergebnis ([FeelSuggestion.uncertaintyPct]).
 *
 * Offene Annahmen (nur auf dem Gerät zu prüfen): Radumfang 2,1 m und die
 * Konvention, dass die gemeldete Trittfrequenz zur echten Kurbel passt; dass
 * der Trainer die gesendeten cw/Crr tatsächlich so rechnet; η = Wirkungsgrad
 * des Profils gilt auch für das Leistungsmessgerät des Trainers.
 */

/** Radumfang der virtuellen Gänge in Metern (28″-Rad mit 40-mm-Reifen ≈ 2,1 m). */
const val WHEEL_CIRCUMFERENCE_M: Double = 2.1

/** Mindestleistung der Messung in Watt — darunter ist der Rollwiderstandsanteil im Rauschen. */
const val FEEL_MIN_POWER_W: Double = 100.0

/** Trittfrequenzbereich der Messung. */
const val FEEL_MIN_CADENCE_RPM: Double = 60.0
const val FEEL_MAX_CADENCE_RPM: Double = 110.0

/** Länge des Messfensters in Sekunden (Soll) und das Minimum, bei dem noch gerechnet wird. */
const val FEEL_TARGET_WINDOW_S: Int = 150
const val FEEL_MIN_SAMPLES: Int = 90

/** Einlaufzeit in Sekunden, bevor Werte zählen (Trainer und Sitzung müssen sich einschwingen). */
const val FEEL_WARMUP_S: Int = 30

/** Grenzen der Unruhe: robuste Streuung (MAD/Median) von Leistung und Trittfrequenz, Drift zwischen den Hälften. */
const val FEEL_MAX_POWER_SPREAD: Double = 0.08
const val FEEL_MAX_CADENCE_SPREAD: Double = 0.06
const val FEEL_MAX_DRIFT: Double = 0.06

/** So weit darf die simulierte Geschwindigkeit vom Gleichgewichtstempo der Median-Leistung abweichen. */
const val FEEL_MAX_SPEED_MISMATCH: Double = 0.15

/** Plausibler Bereich für den rohen Faktor (neu/alt); außerhalb: Ablehnung statt Schätzung. */
const val FEEL_RAW_FACTOR_MIN: Double = 0.5
const val FEEL_RAW_FACTOR_MAX: Double = 2.0

/** Unter diesem Anteil des Rollwiderstands wird der Überschuss nicht mehr als Messung gewertet. */
private const val MIN_EXCESS_SHARE: Double = 0.25

/** Ein Messwert je Sekunde. [gearIndex]/[gearRatio] sind der Gang zu diesem Zeitpunkt. */
data class FeelSample(
    val powerW: Double,
    val cadenceRpm: Double,
    val simSpeedMps: Double,
    val gearIndex: Int,
    val gearRatio: Double,
)

/** Warum eine Messung nicht zu einem Vorschlag führt. */
enum class FeelRejection {
    /** Zu wenige brauchbare Sekunden. */
    TOO_FEW_SAMPLES,

    /** Leistung zu niedrig oder gar keine. */
    POWER_TOO_LOW,

    /** Trittfrequenz außerhalb 60–110 U/min. */
    CADENCE_OUT_OF_RANGE,

    /** Leistung oder Trittfrequenz zu unruhig bzw. zu stark gewandert. */
    UNSTEADY,

    /** Die simulierte Geschwindigkeit passt nicht zur Leistung (Sitzung noch nicht eingeschwungen, Pause, Fehler). */
    INCONSISTENT_SPEED,

    /** Der Gang wurde gewechselt. */
    GEAR_CHANGED,

    /** Der Vorschlag läge weit außerhalb des Plausiblen — eher ein Messfehler als ein Trainerunterschied. */
    IMPLAUSIBLE,
}

/**
 * Ein Vorschlag.
 *
 * @property currentResistance der heutige Faktor (`trainerScale`).
 * @property suggestedResistance der vorgeschlagene, auf [RideFeel.RESISTANCE_MIN]..[RideFeel.RESISTANCE_MAX] geklemmt.
 * @property clamped der rohe Wert lag außerhalb der Grenzen und wurde geklemmt.
 * @property powerRatio erwartete / gemessene Leistung (1,0 = passt).
 * @property medianPowerW Median der Leistung.
 * @property medianCadenceRpm Median der Trittfrequenz.
 * @property expectedPowerW Erwartungswert für ein Rad in der Ebene beim Median-Tempo des Gangs.
 * @property uncertaintyPct grobe Unsicherheit des Faktors in Prozent, aus Leistungsstreuung und Überschussanteil.
 * @property samples Zahl der gewerteten Sekunden.
 */
data class FeelSuggestion(
    val currentResistance: Double,
    val suggestedResistance: Double,
    val clamped: Boolean,
    val powerRatio: Double,
    val medianPowerW: Double,
    val medianCadenceRpm: Double,
    val expectedPowerW: Double,
    val uncertaintyPct: Double,
    val samples: Int,
) {
    /** Ob sich der Vorschlag überhaupt vom heutigen Wert abhebt (mehr als eine Stufe [RideFeel.STEP] / 2). */
    val worthChanging: Boolean get() = abs(suggestedResistance - currentResistance) >= RideFeel.STEP / 2
}

/** Ergebnis von [evaluateFeel]. */
sealed interface FeelResult {
    data class Suggest(val suggestion: FeelSuggestion) : FeelResult
    data class Reject(val reason: FeelRejection) : FeelResult
}

private fun median(v: List<Double>): Double {
    val s = v.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}

/** Robuste Streuung: Median der Abweichungen vom Median, relativ zum Median. */
private fun relSpread(v: List<Double>): Double {
    val m = median(v)
    if (m <= 0) return Double.POSITIVE_INFINITY
    return median(v.map { abs(it - m) }) * 1.4826 / m
}

/**
 * Rechnet den Vorschlag. Reine Funktion: dieselben Eingaben, dasselbe Ergebnis.
 *
 * @param samples die Sekundenwerte des Messfensters (nach der Einlaufzeit).
 * @param rider Fahrer/Rad; `trainerScale` ist der heutige Faktor, `profileCrr` und
 *   `surfaceStrength` bestimmen den Crr der Teststrecke.
 */
fun evaluateFeel(samples: List<FeelSample>, rider: RiderSetup): FeelResult {
    val clean = samples.filter {
        it.powerW.isFinite() && it.cadenceRpm.isFinite() && it.simSpeedMps.isFinite() &&
            it.gearRatio.isFinite() && it.gearRatio > 0
    }
    if (clean.size < FEEL_MIN_SAMPLES) return FeelResult.Reject(FeelRejection.TOO_FEW_SAMPLES)
    if (clean.map { it.gearIndex }.distinct().size > 1) return FeelResult.Reject(FeelRejection.GEAR_CHANGED)

    val powers = clean.map { it.powerW }
    val cadences = clean.map { it.cadenceRpm }
    val pMed = median(powers)
    val cMed = median(cadences)
    if (pMed < FEEL_MIN_POWER_W) return FeelResult.Reject(FeelRejection.POWER_TOO_LOW)
    if (cMed < FEEL_MIN_CADENCE_RPM || cMed > FEEL_MAX_CADENCE_RPM) {
        return FeelResult.Reject(FeelRejection.CADENCE_OUT_OF_RANGE)
    }
    val pSpread = relSpread(powers)
    val cSpread = relSpread(cadences)
    val half = clean.size / 2
    val drift = abs(median(powers.take(half)) - median(powers.drop(clean.size - half))) / pMed
    if (pSpread > FEEL_MAX_POWER_SPREAD || cSpread > FEEL_MAX_CADENCE_SPREAD || drift > FEEL_MAX_DRIFT) {
        return FeelResult.Reject(FeelRejection.UNSTEADY)
    }

    val r = PhysicsRider.of(rider)
    val crr = effectiveCrr(SurfaceKind.COMPACTED, rider.profileCrr, rider.surfaceStrength)
    val gearRatio = median(clean.map { it.gearRatio })
    val vG = cMed / 60.0 * gearRatio * WHEEL_CIRCUMFERENCE_M
    // Die Sitzung muss zur Leistung passen (gleiche Physik, gleiches Setup).
    val vSim = median(clean.map { it.simSpeedMps })
    val vSteady = steadySpeedMps(pMed, 0.0, crr, 0.0, 0.0, rider)
    if (vSteady <= 0 || abs(vSim - vSteady) / vSteady > FEEL_MAX_SPEED_MISMATCH) {
        return FeelResult.Reject(FeelRejection.INCONSISTENT_SPEED)
    }

    val cw = aeroCoefficient(r.cdaM2, 0.0)
    val fAero = cw * vG * vG
    val fRoll = r.massKg * gravity * crr
    val pExpected = (fAero + fRoll) * vG / r.efficiency
    val fMeasured = pMed * r.efficiency / vG
    val excess = max(fMeasured - fAero, MIN_EXCESS_SHARE * fRoll)
    val current = rider.trainerScale.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val raw = current * fRoll / excess
    val rawFactor = raw / current
    if (rawFactor < FEEL_RAW_FACTOR_MIN || rawFactor > FEEL_RAW_FACTOR_MAX) {
        return FeelResult.Reject(FeelRejection.IMPLAUSIBLE)
    }
    val clampedValue = raw.coerceIn(RideFeel.RESISTANCE_MIN, RideFeel.RESISTANCE_MAX)
    // Unsicherheit: Leistungsfehler (Streuung, mindestens 1 %) wird über den Rollanteil an Widerstand verstärkt.
    val relPowerErr = max(pSpread / Math.sqrt(clean.size.toDouble()), 0.01) + 0.01
    val uncertainty = min(100.0, 100.0 * relPowerErr * fMeasured / excess)
    val rounded = Math.round(clampedValue / RideFeel.STEP) * RideFeel.STEP
    return FeelResult.Suggest(
        FeelSuggestion(
            currentResistance = current,
            suggestedResistance = rounded.coerceIn(RideFeel.RESISTANCE_MIN, RideFeel.RESISTANCE_MAX),
            clamped = clampedValue != raw,
            powerRatio = pExpected / pMed,
            medianPowerW = pMed,
            medianCadenceRpm = cMed,
            expectedPowerW = pExpected,
            uncertaintyPct = uncertainty,
            samples = clean.size,
        ),
    )
}

/** Stand des geführten Abgleichs. */
enum class FeelPhase {
    /** Noch kein Tritt oder zu wenig Leistung/Trittfrequenz: warten. */
    WAITING,

    /** Einlaufen (Trainer und Sitzung einschwingen). */
    WARMUP,

    /** Messfenster läuft. */
    MEASURING,

    /** Fertig: [FeelCalibrator.result] liegt vor. */
    DONE,
}

/**
 * Sammelt die Sekundenwerte eines Abgleichs und stößt am Ende [evaluateFeel] an.
 * Zustandsmaschine ohne Zeitquelle: [feed] wird ~1× je Sekunde mit dem neuen
 * Wert gerufen (nicht gerufen = keine Zeit vergangen).
 *
 * Fällt die Leistung/Trittfrequenz aus dem Bereich oder wechselt der Gang,
 * beginnt das Einlaufen von vorn (kein Vorschlag aus zusammengeflicktem Material).
 */
class FeelCalibrator(private val rider: RiderSetup) {
    var phase: FeelPhase = FeelPhase.WAITING
        private set

    /** Sekunden im aktuellen Abschnitt (Einlaufen bzw. Messen). */
    var phaseS: Int = 0
        private set

    var result: FeelResult? = null
        private set

    private var gear = -1
    private val window = ArrayList<FeelSample>()

    /** Sekunden bis zum Ende des Messfensters, solange gemessen wird. */
    val remainingS: Int get() = if (phase == FeelPhase.MEASURING) max(0, FEEL_TARGET_WINDOW_S - phaseS) else FEEL_TARGET_WINDOW_S

    fun feed(sample: FeelSample?) {
        if (phase == FeelPhase.DONE) return
        val ok = sample != null && sample.powerW >= FEEL_MIN_POWER_W &&
            sample.cadenceRpm in FEEL_MIN_CADENCE_RPM..FEEL_MAX_CADENCE_RPM
        if (!ok) {
            restart()
            return
        }
        if (gear != -1 && gear != sample!!.gearIndex) {
            restart()
        }
        gear = sample!!.gearIndex
        when (phase) {
            FeelPhase.WAITING -> { phase = FeelPhase.WARMUP; phaseS = 1 }
            FeelPhase.WARMUP -> {
                phaseS++
                if (phaseS > FEEL_WARMUP_S) { phase = FeelPhase.MEASURING; phaseS = 0; window.clear() }
            }
            FeelPhase.MEASURING -> {
                window.add(sample)
                phaseS++
                if (phaseS >= FEEL_TARGET_WINDOW_S) finish()
            }
            FeelPhase.DONE -> Unit
        }
    }

    /** Vorzeitig auswerten (z. B. „Fertig“ nach 90+ Sekunden); zu wenig Material wird abgelehnt. */
    fun finishNow() {
        if (phase != FeelPhase.DONE) finish()
    }

    private fun finish() {
        result = evaluateFeel(window.toList(), rider)
        phase = FeelPhase.DONE
    }

    private fun restart() {
        phase = FeelPhase.WAITING
        phaseS = 0
        gear = -1
        window.clear()
    }
}

/**
 * Die ebene Teststrecke des Abgleichs: Rundkurs von 3 km (Quadrat), exakt eben,
 * Belag [SurfaceKind.COMPACTED] (Crr 0,008 — der Mischwert). Die Koordinaten
 * sind nur für die Sitzung da (Richtung), nicht für eine Karte.
 */
fun flatCalibrationRoute(): IndoorRoute {
    val sideM = 750.0
    val stepM = 125.0
    val perSide = (sideM / stepM).toInt()
    val mPerDegLat = 111_320.0
    val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0, 0.0 to 0.0)
    val pts = ArrayList<IndoorRoutePoint>()
    var dist = 0.0
    for (side in 0 until 4) {
        val (y0, x0) = corners[side]
        val (y1, x1) = corners[side + 1]
        for (i in 0 until perSide) {
            val t = i.toDouble() / perSide
            pts.add(IndoorRoutePoint(dist, (y0 + (y1 - y0) * t) * sideM / mPerDegLat, (x0 + (x1 - x0) * t) * sideM / mPerDegLat, 100.0))
            dist += stepM
        }
    }
    pts.add(IndoorRoutePoint(dist, 0.0, 0.0, 100.0))
    return IndoorRoute(
        id = "calibration-flat",
        name = "Abgleich",
        points = pts,
        surfaces = listOf(SurfaceSection(0.0, dist, SurfaceKind.COMPACTED)),
        isLoop = true,
        source = IndoorRouteSource.NONE,
    )
}

/** Zieltrittfrequenz der Messung. */
const val FEEL_TARGET_CADENCE_RPM: Double = 85.0

/**
 * Wie viele Gänge vom Startgang aus geschaltet werden sollen (negativ = leichter),
 * damit man bei [FEEL_TARGET_CADENCE_RPM] auf der Ebene etwa 55 % der FTP tritt
 * (mindestens 120, höchstens 220 W) — ein Tempo, das sich 2–3 Minuten halten lässt.
 */
fun calibrationGearShift(rider: RiderSetup): Int {
    val r = PhysicsRider.of(rider)
    val crr = effectiveCrr(SurfaceKind.COMPACTED, rider.profileCrr, rider.surfaceStrength)
    val cw = aeroCoefficient(r.cdaM2, 0.0)
    val target = (0.55 * rider.ftpW).takeIf { it.isFinite() }?.coerceIn(120.0, 220.0) ?: 150.0
    val ratios = rider.gearTable.ratios
    fun powerAt(ratio: Double): Double {
        val v = FEEL_TARGET_CADENCE_RPM / 60.0 * ratio * WHEEL_CIRCUMFERENCE_M
        return (cw * v * v + r.massKg * gravity * crr) * v / r.efficiency
    }
    val best = ratios.indices.minBy { abs(powerAt(ratios[it]) - target) } + 1
    return best - startGearIndex(rider.realGearRatio, rider.gearTable)
}
