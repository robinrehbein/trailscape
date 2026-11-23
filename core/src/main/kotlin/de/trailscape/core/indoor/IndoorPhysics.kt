package de.trailscape.core.indoor

import de.trailscape.core.airDensity
import de.trailscape.core.defaultCda
import de.trailscape.core.defaultDriveEfficiency
import de.trailscape.core.gravity
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Fahrphysik der virtuellen Fahrt
 *
 * Das Tempo der virtuellen Fahrt rechnet immer die Sitzung aus der gemessenen
 * Leistung (docs/indoor.md, Abschnitt 5.3/5.6) — nie aus dem Schwungradtempo
 * des Trainers. Grundlage ist die Bewegungsgleichung in Fahrtrichtung:
 *
 * ```
 * P·η = v·( m·g·(sin θ + Crr·cos θ) + ½·ρ·CdA·(v + v_w)·|v + v_w| ) + m_eff·v·dv/dt
 * ```
 *
 *  * θ = atan(Steigung/100) der **Strecke** — die Trainer-Schwierigkeit
 *    ([RiderSetup.difficulty]) wirkt nur auf den Widerstand am Trainer, nie
 *    auf das Tempo;
 *  * ρ = `airDensity(Hoehe)`, g = `gravity` (beide `TrainingLoad.kt`), Crr =
 *    `effectiveCrr(Belag, Profil-Crr)`;
 *  * Luftwiderstand **mit Vorzeichen**: Ein Rueckenwind, der schneller weht als
 *    man faehrt (v + v_w < 0), schiebt;
 *  * m_eff = Gesamtmasse + [ROTATING_MASS_KG] fuer die Drehmasse der Laufraeder.
 *
 * ## Antriebskraft beim Anfahren und am steilen Berg
 *
 * Die Antriebskraft ist `P·η / v` — bei v → 0 unendlich. Gekappt wird die
 * **Kraft**, nicht das Tempo: `F = min(P·η / v, F_max)` mit
 * `F_max = [MAX_DRIVE_ACCEL_MPS2] · m_eff`. (Eine fruehere Fassung nahm
 * `P·η / max(v, 1 m/s)`; damit fiel jedes Gleichgewicht unter 1 m/s auf 0 —
 * 87 kg mit 100 W an 15 % blieben stehen statt mit ≈ 0,73 m/s zu kriechen,
 * gerade in der Erholung einer ERG-Einheit am Berg.) F_max liegt ueber jedem
 * Widerstand, der auf einer Strecke vorkommen kann (25 % Steigung plus Crr
 * 0,07 ≈ 3,05 m/s² · m), sodass jede Leistung > 0 an jeder Stelle vorwaerts
 * kommt, und begrenzt den Antritt aus dem Stand auf etwa 4 m/s².
 *
 * ## Integration
 *
 * Teilschritte von hoechstens [MAX_SUBSTEP_S]. Der Antrieb wird **halbimplizit**
 * genommen (mit dem Tempo am Ende des Teilschritts), die Widerstaende explizit:
 *
 * ```
 * m_eff·(v' − v)/h = P·η/v' − F_res(v)   →   v' = (b + √(b² + 4·m_eff·h·P·η)) / (2·m_eff),
 * b = m_eff·v − h·F_res(v)
 * ```
 *
 * Bei kleinem v ist `P·η/v` sehr steif (Ableitung −P·η/v²); explizit wuerde es
 * dort schwingen, halbimplizit ist es fuer jedes h stabil und trifft das
 * Gleichgewicht `F_res·v = P·η` exakt. Ueberschreitet `P·η/v'` die Kappung,
 * gilt `v' = v + h·(F_max − F_res)/m_eff`. Die uebrige kuerzeste
 * Zeitkonstante ist die des Luftwiderstands, `m_eff / (2·cw·v)` — bei 90 kg,
 * cw 0,23 kg/m und 20 m/s rund 10 s, 200-mal laenger als ein Teilschritt:
 * Auch ein Schritt von 2 s (die Kappung der Sitzung) rechnet genau wie zwanzig
 * zu 0,1 s (im Test belegt).
 */

/**
 * Drehmasse der Laufraeder als Zusatzmasse in kg.
 *
 * Ein Laufrad mit Felge, Reifen und Schlauch wirkt translatorisch mit etwa
 * `I/r²` ≈ Masse des Aussenrings (Felge + Reifen + Schlauch, bei einem
 * Gravel-Laufradsatz rund 0,7–0,8 kg je Rad); Speichen und Nabe tragen kaum
 * bei. Fuer beide Raeder zusammen also rund 1,5 kg. Die Kurbel und das
 * Schwungrad des Trainers zaehlen nicht: Die virtuelle Fahrt soll sich wie
 * draussen anfuehlen.
 */
internal const val ROTATING_MASS_KG: Double = 1.5

/** Laengster Teilschritt der Integration in Sekunden. */
internal const val MAX_SUBSTEP_S: Double = 0.05

/**
 * Kappung der Antriebskraft als Beschleunigung: `F_max = 4 m/s² · m_eff`
 * (Begruendung im Dateikopf).
 */
internal const val MAX_DRIVE_ACCEL_MPS2: Double = 4.0

/** Untergrenze des Tempos im Nenner von `P·η / v` (nur gegen Division durch 0; die Kappung greift weit darueber). */
private const val DRIVE_SPEED_EPS_MPS: Double = 1e-6

/** Obergrenze fuer [steadySpeedMps] (Suchbereich der Bisektion), 360 km/h. */
private const val MAX_SEARCH_SPEED_MPS: Double = 100.0

/** Ergebnis eines Integrationsschritts: neues Tempo und zurueckgelegter Weg. */
internal data class PhysicsStep(val speedMps: Double, val distanceM: Double)

/**
 * Gegenwindanteil in Fahrtrichtung: positiv = Gegenwind, negativ = Rueckenwind.
 *
 * Der Wind weht AUS [IndoorWind.fromDeg]. Wer genau dorthin faehrt
 * (`headingDeg == fromDeg`), hat vollen Gegenwind; in die Gegenrichtung vollen
 * Rueckenwind; quer dazu keinen Anteil (Seitenwind bleibt unberuecksichtigt).
 */
internal fun headwindMps(wind: IndoorWind, headingDeg: Double): Double {
    if (!wind.speedMps.isFinite() || !wind.fromDeg.isFinite() || !headingDeg.isFinite()) {
        return 0.0
    }
    return wind.speedMps * cos(Math.toRadians(wind.fromDeg - headingDeg))
}

/** Luftwiderstandsbeiwert `cw = ½·ρ·CdA` in kg/m auf Hoehe [eleM]. */
internal fun aeroCoefficient(cdaM2: Double, eleM: Double): Double =
    0.5 * airDensity(if (eleM.isFinite()) eleM else 0.0) * cdaM2

/**
 * Summe der Fahrwiderstaende in N beim Tempo [speedMps]: Hangabtrieb, Rollen,
 * Luft (mit Vorzeichen, siehe Dateikopf).
 */
internal fun resistanceForceN(
    speedMps: Double,
    gradePct: Double,
    crr: Double,
    cwKgPerM: Double,
    headwindMps: Double,
    massKg: Double,
): Double {
    val theta = atan(gradePct / 100.0)
    val air = speedMps + headwindMps
    return massKg * gravity * (sin(theta) + crr * cos(theta)) + cwKgPerM * air * abs(air)
}

/** Antriebskraft in N am Rad fuer [powerW] beim Tempo [speedMps], gekappt auf [maxForceN]. */
internal fun driveForceN(powerW: Double, speedMps: Double, efficiency: Double, maxForceN: Double): Double =
    min(max(powerW, 0.0) * efficiency / max(speedMps, DRIVE_SPEED_EPS_MPS), maxForceN)

/** Die Werte des Fahrers, die die Physik braucht — einmal geprueft, damit nichts NaN wird. */
internal data class PhysicsRider(val massKg: Double, val cdaM2: Double, val efficiency: Double) {
    val effectiveMassKg: Double get() = massKg + ROTATING_MASS_KG

    /** Kappung der Antriebskraft in N. */
    val maxDriveForceN: Double get() = MAX_DRIVE_ACCEL_MPS2 * effectiveMassKg

    companion object {
        fun of(rider: RiderSetup): PhysicsRider {
            val m = rider.totalMassKg.takeIf { it.isFinite() && it > 0 } ?: 87.0
            val cda = rider.cdaM2.takeIf { it.isFinite() && it > 0 } ?: defaultCda
            val eta = rider.driveEfficiency.takeIf { it.isFinite() && it > 0 && it <= 1.0 } ?: defaultDriveEfficiency
            return PhysicsRider(m, cda, eta)
        }
    }
}

/**
 * Integriert die Bewegungsgleichung ueber [dtS] Sekunden mit festen
 * Streckenwerten (Steigung, Crr, Hoehe, Gegenwind gelten fuer den ganzen
 * Schritt — die Sitzung ruft das etwa zehnmal je Sekunde, also ueber wenige
 * Meter).
 *
 * Das Tempo wird nie negativ: Bergauf ohne Leistung bleibt man stehen statt
 * rueckwaerts zu rollen. Bergab ohne Leistung rollt man bis zum Gleichgewicht
 * aus Hangabtrieb, Rollen und Luft.
 */
internal fun advancePhysics(
    speedMps: Double,
    powerW: Double,
    gradePct: Double,
    crr: Double,
    eleM: Double,
    headwindMps: Double,
    rider: RiderSetup,
    dtS: Double,
): PhysicsStep {
    var v = if (speedMps.isFinite()) max(speedMps, 0.0) else 0.0
    if (!(dtS > 0) || !dtS.isFinite()) {
        return PhysicsStep(v, 0.0)
    }
    val r = PhysicsRider.of(rider)
    val p = if (powerW.isFinite()) max(powerW, 0.0) else 0.0
    val grade = if (gradePct.isFinite()) gradePct else 0.0
    val c = if (crr.isFinite() && crr >= 0) crr else effectiveCrr(SurfaceKind.UNKNOWN, rider.profileCrr)
    val wind = if (headwindMps.isFinite()) headwindMps else 0.0
    val cw = aeroCoefficient(r.cdaM2, eleM)
    val n = max(1, ceil(dtS / MAX_SUBSTEP_S - 1e-9).toInt())
    val h = dtS / n
    var dist = 0.0
    val m = r.effectiveMassKg
    val drive = p * r.efficiency
    val fMax = r.maxDriveForceN
    repeat(n) {
        val res = resistanceForceN(v, grade, c, cw, wind, r.massKg)
        val b = m * v - h * res
        var next = (b + sqrt(b * b + 4 * m * h * drive)) / (2 * m)
        if (drive > 0 && drive > fMax * next) {
            next = v + h * (fMax - res) / m
        }
        next = max(0.0, next)
        dist += (v + next) / 2 * h
        v = next
    }
    return PhysicsStep(v, dist)
}

/**
 * Gleichgewichtstempo in m/s: das Tempo, bei dem die Antriebskraft (wie in
 * [advancePhysics], also mit Kappung) die Widerstaende genau
 * aufwiegt. Bisektion — die Kraftbilanz faellt mit dem Tempo monoton.
 * 0, wenn man an der Stelle nicht einmal anrollt (bergauf ohne Leistung).
 */
internal fun steadySpeedMps(
    powerW: Double,
    gradePct: Double,
    crr: Double,
    eleM: Double,
    headwindMps: Double,
    rider: RiderSetup,
): Double {
    val r = PhysicsRider.of(rider)
    val cw = aeroCoefficient(r.cdaM2, eleM)
    fun balance(v: Double) =
        driveForceN(powerW, v, r.efficiency, r.maxDriveForceN) - resistanceForceN(v, gradePct, crr, cw, headwindMps, r.massKg)
    if (balance(0.0) <= 0) {
        return 0.0
    }
    var lo = 0.0
    var hi = MAX_SEARCH_SPEED_MPS
    if (balance(hi) > 0) {
        return hi
    }
    repeat(80) {
        val mid = (lo + hi) / 2
        if (balance(mid) > 0) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}
