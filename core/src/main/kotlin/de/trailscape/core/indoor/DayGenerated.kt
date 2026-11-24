package de.trailscape.core.indoor

import de.trailscape.core.indoor.generated.GeneratedProfile
import kotlin.math.abs

/**
 * # Generierte Welt zur Einheit des Tages: das Zielprofil
 *
 * Zweiter Weg neben der echten Strecke (docs/indoor/app.md, „Einheit des
 * Tages"): eine erfundene Landschaft, exakt zur Einheit. Hier entsteht nur das
 * Zielprofil ([GeneratedProfile]); Strecke und Welt baut
 * `generated/GeneratedWorldEntry.kt` (Session „generierte Welt").
 *
 * ## Regeln je Abschnitt der ERG-Einheit
 *  * Belastung ab [DAY_INTERVAL_MIN_FRACTION] → [GeneratedProfile.Kind.CLIMB]
 *    mit [climbGradePct] (6 % bei 95 % FTP, 7 % an der Schwelle, 4..8 %);
 *  * Belastung darunter → ROLLING mit 1 %;
 *  * Pause → leichte Abfahrt ([DAY_GEN_RECOVER_GRADE_PCT]);
 *  * gleichmaessig ab [DAY_ROLLING_MIN_FRACTION] → ROLLING um 0 %;
 *  * Einfahren, Ausfahren, gleichmaessig locker → FLAT.
 *
 * Die Laenge jedes Abschnitts ist `Dauer × Gleichgewichtstempo` bei der
 * Sollleistung und der Steigung des Abschnitts (dieselbe Indoor-Physik wie
 * [simulateDayRide]) — so ergibt jeder Schritt bei Sollleistung seine
 * Solldauer, und die Einheit endet am Ziel. Benachbarte Abschnitte gleicher
 * Art und Steigung werden zusammengelegt (die drei Stufen des Einfahrens sind
 * eine flache Strecke).
 *
 * ## Seed
 * [dayGeneratedSeed] haengt nur an der Einheit (Art, Dauer, Anteil je
 * Abschnitt), nicht an Datum oder Fahrer: Dieselbe Einheit ergibt denselben
 * Seed. Die Laengen haengen am Fahrer (FTP, Gewicht) — gleiche Einheit und
 * gleicher Fahrer ergeben damit dieselbe Welt.
 */

/** Steigung der Pausen in Prozent: leicht bergab, damit Hoehe zurueckkommt. */
const val DAY_GEN_RECOVER_GRADE_PCT: Double = -2.0

/** Steigung eines Intervalls nach dem Anteil der FTP. */
fun climbGradePct(ftpFraction: Double): Double = (6.0 + (ftpFraction - 0.95) * 20.0).coerceIn(4.0, 8.0)

/** Stabiler Seed aus der Einheit (FNV-1a, 64 Bit). */
fun dayGeneratedSeed(workout: ErgWorkout): Long {
    var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
    fun mix(v: Long) {
        var x = v
        repeat(8) {
            h = h xor (x and 0xff)
            h *= 0x100000001b3L
            x = x ushr 8
        }
    }
    for (s in workout.steps) {
        mix(s.kind.ordinal.toLong())
        mix(s.durationS.toLong())
        mix(Math.round(s.ftpFraction * 1000))
    }
    return h
}

/** Art und Steigung eines Abschnitts der Einheit (Regeln im Dateikopf). */
internal fun generatedLegShape(step: ErgStep): Pair<GeneratedProfile.Kind, Double> = when {
    step.kind == ErgStepKind.WORK && step.ftpFraction >= DAY_INTERVAL_MIN_FRACTION ->
        GeneratedProfile.Kind.CLIMB to climbGradePct(step.ftpFraction)
    step.kind == ErgStepKind.WORK -> GeneratedProfile.Kind.ROLLING to 1.0
    step.kind == ErgStepKind.RECOVER -> GeneratedProfile.Kind.DESCENT to DAY_GEN_RECOVER_GRADE_PCT
    step.kind == ErgStepKind.STEADY && step.ftpFraction >= DAY_ROLLING_MIN_FRACTION -> GeneratedProfile.Kind.ROLLING to 0.0
    else -> GeneratedProfile.Kind.FLAT to 0.0
}

/** Das Zielprofil der generierten Welt zur Einheit von [goal] (Regeln im Dateikopf). */
fun dayGeneratedProfile(goal: DayRouteGoal): GeneratedProfile {
    val ftp = goal.rider.ftpW.takeIf { it.isFinite() && it > 0 } ?: 200.0
    val legs = mutableListOf<GeneratedProfile.Leg>()
    for (s in goal.workout.steps) {
        val (kind, grade) = generatedLegShape(s)
        val v = dayGeneratedSpeedMps(s.ftpFraction * ftp, grade, goal.rider)
        val len = s.durationS * v
        val last = legs.lastOrNull()
        if (last != null && last.kind == kind && abs(last.gradePct - grade) < 1e-9) {
            legs[legs.size - 1] = last.copy(lengthM = last.lengthM + len)
        } else {
            legs += GeneratedProfile.Leg(len, grade, kind)
        }
    }
    return GeneratedProfile(dayGeneratedSeed(goal.workout), legs)
}

/** Gleichgewichtstempo auf einem Abschnitt — dieselbe Rechnung wie in der Nachrechnung. */
fun dayGeneratedSpeedMps(powerW: Double, gradePct: Double, rider: RiderSetup): Double = daySpeedMps(powerW, gradePct, rider)
