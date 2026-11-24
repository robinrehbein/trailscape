package de.trailscape.core.indoor

import de.trailscape.core.FtpTestArg
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingSession
import de.trailscape.core.WorkoutArg
import de.trailscape.core.parseWorkoutArgs
import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * # ERG-Einheit aus der Planeinheit
 *
 * Aus der Planeinheit des Tages wird eine Folge von Abschnitten mit
 * Zielleistung (Anteil der FTP). Regeln:
 *
 *  * Das Zielevent ([TrainingSession.isEvent]) ist kein Rollentraining → `null`.
 *  * **Qualitaetseinheiten** ([SessionTextKey.WORKOUT]) und der **20-min-Test**
 *    ([SessionTextKey.FTP_TEST]): ihre Struktur aus den Argumenten, Belastung
 *    in der Mitte des Zielbereichs (siehe `structuredSteps`).
 *  * **Intervalle** ([SessionTextKey.INTERVALS] mit plausiblen Argumenten
 *    `[einfahren, wdh, belastung, pause, ausfahren, gesamt, haerte]`): genau
 *    die Struktur aus dem Plantext — Einfahren in bis zu drei steigenden Stufen
 *    (0,50/0,60/0,70; bei nur einer Minute 0,55), je Wiederholung Belastung
 *    (haerte 1 „hart an der Schwelle" 1,00, sonst 0,95) und Pause 0,50 (die
 *    letzte Pause entfaellt), Ausfahren 0,45. Die Dauer ist die Summe dieser
 *    Teile (der Plan erzeugt `durationMin` genau so, `gesamt` wird nicht
 *    gebraucht).
 *  * Sonst nach [TrainingSession.intensity] auf die Dauer der Einheit:
 *    `durationMin`, ohne sie `targetKm / 25 km/h` (20..240 min), ohne beides
 *    60 min; jede Dauer wird auf [MIN_DURATION_MIN]..[MAX_DURATION_MIN] geklemmt.
 *     - HART: 10 min Einfahren (3 Stufen), so viele 8-min-Belastungen @0,95
 *       mit 4 min Pause @0,50 wie passen (mindestens 2, hoechstens
 *       [MAX_HARD_REPS]), der Rest gleichmaessig @0,70, 5 min Ausfahren @0,45;
 *     - GRUNDLAGE: 10 min Einfahren (0,50 → 0,60), gleichmaessig @0,68,
 *       5 min Ausfahren @0,45;
 *     - LOCKER: 5 min Einfahren @0,45, gleichmaessig @0,55, 5 min Ausfahren @0,45.
 *    Passen die festen Teile nicht in die Dauer, werden sie anteilig gestaucht.
 *  * Die Summe der Abschnitte ist exakt die Dauer in Sekunden; kein Abschnitt
 *    ist kuerzer als [MIN_STEP_S] (kuerzere werden dem Nachbarn zugeschlagen).
 *
 * Abweichung vom Entwurf: Die Obergrenze der Wiederholungen bei HART ohne
 * Argumente ([MAX_HARD_REPS]) stand dort nicht („so viele wie passen") — ohne
 * sie wuerden aus 4 Stunden 18 Schwellenintervalle. 6 ist die Obergrenze des
 * ambitionierten Intervallrasters im Plan (`Training.kt`).
 */

internal const val MIN_STEP_S: Int = 30
internal const val MIN_DURATION_MIN: Int = 10
internal const val MAX_DURATION_MIN: Int = 360
internal const val MAX_HARD_REPS: Int = 6
private const val PLAN_SPEED_KMH: Double = 25.0
private const val DEFAULT_DURATION_MIN: Int = 60

internal fun ergWorkoutForImpl(session: TrainingSession, title: String): ErgWorkout? {
    if (session.isEvent) {
        return null
    }
    val steps = intervalSteps(session) ?: run {
        val totalS = sessionDurationMin(session) * 60
        when (session.intensity) {
            SessionIntensity.HART -> hardSteps(totalS)
            SessionIntensity.GRUNDLAGE -> fillSteps(
                totalS,
                head = warmup(600, 0.50, 0.60, stages = 2),
                fillFraction = 0.68,
                tail = listOf(ErgStep(ErgStepKind.COOLDOWN, 300, 0.45)),
            )
            SessionIntensity.LOCKER -> fillSteps(
                totalS,
                head = listOf(ErgStep(ErgStepKind.WARMUP, 300, 0.45)),
                fillFraction = 0.55,
                tail = listOf(ErgStep(ErgStepKind.COOLDOWN, 300, 0.45)),
            )
        }
    }
    return ErgWorkout(title, mergeShortSteps(steps))
}

/** Dauer der Einheit in Minuten (Regeln im Dateikopf). */
internal fun sessionDurationMin(session: TrainingSession): Int {
    val raw = session.durationMin?.takeIf { it > 0 }
        ?: if (session.targetKm > 0) {
            (session.targetKm / PLAN_SPEED_KMH * 60).roundToInt().coerceIn(20, 240)
        } else {
            DEFAULT_DURATION_MIN
        }
    return raw.coerceIn(MIN_DURATION_MIN, MAX_DURATION_MIN)
}

/**
 * Die Struktur einer neuen Qualitaetseinheit ([SessionTextKey.WORKOUT]) oder
 * des 20-min-Tests ([SessionTextKey.FTP_TEST]) als ERG-Abschnitte. Die
 * Zielleistung der Belastung ist die Mitte des Zielbereichs der Art
 * (Tempo 0,82, Sweet Spot 0,91, Schwelle 1,00, VO2max 1,13; Test 1,00 — ein
 * Test im ERG-Modus haelt die Leistung fest, er misst also nur, ob die FTP
 * noch traegt).
 */
private fun structuredSteps(session: TrainingSession): List<ErgStep>? {
    val a = session.textArgs
    return when (session.textKey) {
        SessionTextKey.WORKOUT -> {
            val shape = parseWorkoutArgs(a) ?: return null
            val zone = shape.kind.zone
            val fraction = round2((zone.powerLowFtp + zone.powerHighFtp) / 2)
            stepsFor(a[WorkoutArg.WARMUP], shape.reps, shape.workMin, shape.restMin, a[WorkoutArg.COOLDOWN], fraction)
        }
        SessionTextKey.FTP_TEST -> {
            if (a.size < 3) return null
            stepsFor(a[FtpTestArg.WARMUP], 1, a[FtpTestArg.TEST], 0, a[FtpTestArg.COOLDOWN], 1.00)
        }
        else -> null
    }
}

private fun stepsFor(warm: Int, reps: Int, work: Int, rest: Int, cool: Int, workFraction: Double): List<ErgStep>? {
    val plausible = warm in 0..120 && reps in 1..20 && work in 1..60 && rest in 0..30 && cool in 0..120
    if (!plausible) return null
    val totalMin = warm + reps * work + (reps - 1) * rest + cool
    if (totalMin !in MIN_DURATION_MIN..MAX_DURATION_MIN) return null
    val steps = mutableListOf<ErgStep>()
    if (warm > 0) {
        steps += warmup(warm * 60, 0.50, 0.70, stages = minOf(3, warm), single = 0.55)
    }
    for (i in 1..reps) {
        steps += ErgStep(ErgStepKind.WORK, work * 60, workFraction)
        if (i < reps && rest > 0) {
            steps += ErgStep(ErgStepKind.RECOVER, rest * 60, 0.50)
        }
    }
    if (cool > 0) {
        steps += ErgStep(ErgStepKind.COOLDOWN, cool * 60, 0.45)
    }
    return steps
}

/** Die Intervallstruktur aus den Plan-Argumenten, `null` wenn sie fehlen oder unplausibel sind. */
private fun intervalSteps(session: TrainingSession): List<ErgStep>? {
    structuredSteps(session)?.let { return it }
    if (session.textKey != SessionTextKey.INTERVALS) {
        return null
    }
    val a = session.textArgs
    if (a.size < 7) {
        return null
    }
    val warm = a[0]
    val reps = a[1]
    val work = a[2]
    val rest = a[3]
    val cool = a[4]
    val effort = a[6]
    val plausible = warm in 0..120 && reps in 1..20 && work in 1..60 && rest in 0..30 &&
        cool in 0..120 && effort in 0..1
    if (!plausible) {
        return null
    }
    val totalMin = warm + reps * work + (reps - 1) * rest + cool
    if (totalMin !in MIN_DURATION_MIN..MAX_DURATION_MIN) {
        return null
    }
    val workFraction = if (effort == 1) 1.00 else 0.95
    val steps = mutableListOf<ErgStep>()
    if (warm > 0) {
        steps += warmup(warm * 60, 0.50, 0.70, stages = minOf(3, warm), single = 0.55)
    }
    for (i in 1..reps) {
        steps += ErgStep(ErgStepKind.WORK, work * 60, workFraction)
        if (i < reps && rest > 0) {
            steps += ErgStep(ErgStepKind.RECOVER, rest * 60, 0.50)
        }
    }
    if (cool > 0) {
        steps += ErgStep(ErgStepKind.COOLDOWN, cool * 60, 0.45)
    }
    return steps
}

/**
 * Einfahren in [stages] gleich langen, linear von [lo] bis [hi] steigenden
 * Stufen; eine einzige Stufe liegt bei [single] (Vorgabe: in der Mitte). Der Rest der Sekunden geht an
 * die letzten Stufen, damit die Summe exakt [seconds] ist.
 */
private fun warmup(seconds: Int, lo: Double, hi: Double, stages: Int, single: Double = (lo + hi) / 2): List<ErgStep> {
    val n = stages.coerceIn(1, maxOf(1, seconds / MIN_STEP_S))
    if (n == 1) {
        return listOf(ErgStep(ErgStepKind.WARMUP, seconds, round2(single)))
    }
    val base = seconds / n
    val extra = seconds % n
    return List(n) { i ->
        ErgStep(ErgStepKind.WARMUP, base + if (i >= n - extra) 1 else 0, round2(lo + (hi - lo) * i / (n - 1)))
    }
}

private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

/** HART ohne Intervall-Argumente (siehe Dateikopf). */
private fun hardSteps(totalS: Int): List<ErgStep> {
    val warmS = 600
    val coolS = 300
    val workS = 480
    val restS = 240
    val room = totalS - warmS - coolS
    val reps = floor((room + restS).toDouble() / (workS + restS)).toInt().coerceIn(2, MAX_HARD_REPS)
    val intervals = mutableListOf<ErgStep>()
    for (i in 1..reps) {
        intervals += ErgStep(ErgStepKind.WORK, workS, 0.95)
        if (i < reps) {
            intervals += ErgStep(ErgStepKind.RECOVER, restS, 0.50)
        }
    }
    return fillSteps(
        totalS,
        head = warmup(warmS, 0.50, 0.70, stages = 3) + intervals,
        fillFraction = 0.70,
        tail = listOf(ErgStep(ErgStepKind.COOLDOWN, coolS, 0.45)),
    )
}

/**
 * Feste Teile [head] und [tail], dazwischen ein gleichmaessiger Abschnitt, der
 * die Dauer [totalS] exakt auffuellt. Sind die festen Teile laenger als die
 * Dauer, werden sie anteilig gestaucht (groesste Reste zuerst, Summe exakt).
 */
private fun fillSteps(totalS: Int, head: List<ErgStep>, fillFraction: Double, tail: List<ErgStep>): List<ErgStep> {
    val fixed = head + tail
    val fixedS = fixed.sumOf { it.durationS }
    if (fixedS <= totalS) {
        val fill = totalS - fixedS
        val middle = if (fill > 0) listOf(ErgStep(ErgStepKind.STEADY, fill, fillFraction)) else emptyList()
        return head + middle + tail
    }
    val exact = fixed.map { it.durationS.toDouble() * totalS / fixedS }
    val floors = exact.map { floor(it).toInt() }.toMutableList()
    var missing = totalS - floors.sum()
    val order = exact.indices.sortedByDescending { exact[it] - floors[it] }
    for (i in order) {
        if (missing <= 0) break
        floors[i] += 1
        missing--
    }
    return fixed.mapIndexed { i, s -> s.copy(durationS = floors[i]) }
}

/**
 * Schlaegt Abschnitte unter [MIN_STEP_S] (auch leere) dem vorigen zu (dem
 * folgenden, wenn es keinen vorigen gibt). Die Summe bleibt gleich.
 */
private fun mergeShortSteps(steps: List<ErgStep>): List<ErgStep> {
    val out = steps.toMutableList()
    var i = 0
    while (i < out.size && out.size > 1) {
        val s = out[i]
        if (s.durationS >= MIN_STEP_S) {
            i++
            continue
        }
        if (i > 0) {
            out[i - 1] = out[i - 1].copy(durationS = out[i - 1].durationS + s.durationS)
            out.removeAt(i)
        } else {
            out[1] = out[1].copy(durationS = out[1].durationS + s.durationS)
            out.removeAt(0)
        }
    }
    return out
}
