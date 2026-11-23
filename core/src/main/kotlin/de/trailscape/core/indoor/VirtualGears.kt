package de.trailscape.core.indoor

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * # Virtuelle Gaenge und Widerstandsmodell
 *
 * Entscheidung (docs/indoor.md, Abschnitt 5.3): **Weg (a), Simulation mit
 * Ersatzsteigung.** Der Trainer bekommt weiter `SetSimulation`, aber mit
 * Werten, die die virtuelle Uebersetzung schon enthalten. Weg (b) (Zielleistung
 * aus Kadenz × Uebersetzung nachfuehren) waere eine Regelschleife ueber die
 * Funkstrecke mit 0,25–1 s Totzeit je Richtung; sie schwingt bei Antritten und
 * fuehlt sich „gummiartig" an. Bei (a) rechnet der Trainer die Bremskraft
 * selbst aus seinem Schwungradtempo — ohne Totzeit, so direkt wie draussen.
 *
 * ## Herleitung: Drehmomentgleichheit an der Kurbel
 *
 * Kurbel → Kette → Ritzel → Rad: Bei Kettenblattradius r_k, Ritzelradius r_r,
 * Uebersetzung G = r_k / r_r und Radradius R gilt fuer Kurbeldrehmoment T und
 * Kraft am Rad F
 *
 * ```
 *   Kettenzug = T / r_k,   Ritzelmoment = T·r_r/r_k = T/G,   F = T / (G·R)   →   T = F·G·R
 * ```
 *
 * und fuer Kadenz c (U/s) das Radtempo v = c·G·2πR. Draussen mit der
 * virtuellen Uebersetzung G_v faehrt man v_v = c·G_v·2πR gegen die Kraft
 * F_v(v_v) und spuert T = F_v·G_v·R. Am Trainer mit der echten Uebersetzung
 * G_r (`RiderSetup.realGearRatio`, Cog: 40/14) laeuft das Schwungrad mit dem
 * Radaequivalent v_r = c·G_r·2πR. Soll sich die Kurbel bei **gleicher Kadenz**
 * gleich schwer drehen, muss gelten
 *
 * ```
 *   F_r·G_r·R = F_v·G_v·R   →   F_r = k·F_v(v_v),   k = G_v/G_r,   v_v = k·v_r.
 * ```
 *
 * (Gleiche Kadenz und gleiches Moment heisst auch gleiche Leistung:
 * F_r·v_r = k·F_v·v_v/k = F_v·v_v.) Der Trainer rechnet aus der
 * Simulationsvorgabe (FTMS) mit seiner eigenen, unbekannten Masse m_t
 *
 * ```
 *   F_t(v_r) = m_t·g·(s/100 + crr_s) + cw_s·(v_r + w_s)·|v_r + w_s|
 * ```
 *
 * (s = gesendete Steigung in %, linear wie bei allen gaengigen Trainern).
 * Gefordert ist F_t(v_r) = k·F_v(k·v_r) fuer **jedes** v_r, mit
 * F_v(v) = m·g·(sin θ_d + crr·cos θ_d) + cw·(v + w)·|v + w|:
 *
 *  * Luftanteil: k·cw·(k·v_r + w)·|k·v_r + w| = cw·k³·(v_r + w/k)·|v_r + w/k|
 *    → **cw_s = cw·k³**, **w_s = w/k** (k > 0);
 *  * tempounabhaengiger Anteil: m_t·g·(s/100 + crr_s) = k·m·g·(sin θ_d + crr·cos θ_d)
 *    → **s = 100·( k·(m/m_t)·(sin θ_d + crr·cos θ_d) − crr_s )**.
 *
 * Dabei ist θ_d = atan(difficulty·Steigung/100) — die Trainer-Schwierigkeit
 * daempft nur den Hangabtrieb, nicht das Rollen. m/m_t ist unbekannt (die
 * Trainer rechnen mit einer festen oder per App eingestellten Masse) und steckt
 * in `RiderSetup.trainerScale` (1,0 = Trainer rechnet mit derselben Masse).
 * crr_s ist der Crr der Stelle, soweit die Kodierung (uint8 × 0,0001) ihn
 * fasst; der Rest wandert in die Steigung, sodass die Summe stimmt.
 *
 * Bei k = 1, Skala 1 und ungeklemmtem crr_s ergibt sich
 * s = 100·(sin θ + crr·cos θ − crr) ≈ Steigung — genau die Strecke.
 *
 * Grenzen: Die Klemmung der Kodierungsbereiche (Steigung ±40 %, cw 0..2,54,
 * crr 0..0,0254, Wind ±32,767 m/s) bricht die Gleichheit erst in Bereichen,
 * die beim Fahren nicht vorkommen (im Test belegt). Die Rechnung setzt voraus,
 * dass das gemeldete Schwungradtempo dem Radaequivalent der echten
 * Uebersetzung entspricht — das ist die Konvention von FTMS (`speedKmh`) und
 * auf dem Geraet zu pruefen (docs/indoor/core.md).
 */

/** Leichteste virtuelle Uebersetzung (z. B. 30/40 — Bergaufkriechgang). */
internal const val MIN_VIRTUAL_RATIO: Double = 0.75

/** Schwerste virtuelle Uebersetzung (z. B. 55/10). */
internal const val MAX_VIRTUAL_RATIO: Double = 5.5

/** Wertebereiche der FTMS-Kodierung von „Set Indoor Bike Simulation Parameters". */
internal const val SIM_MAX_ABS_GRADE_PCT: Double = 40.0
internal const val SIM_MAX_CRR: Double = 0.0254
internal const val SIM_MAX_CW: Double = 2.54
internal const val SIM_MAX_ABS_WIND_MPS: Double = 32.767

/**
 * Die 24 Uebersetzungen, geometrisch gestuft von [MIN_VIRTUAL_RATIO] bis
 * [MAX_VIRTUAL_RATIO] (Faktor (5,5/0,75)^(1/23) ≈ 1,0905 je Gang — etwa so fein
 * wie eine 12-fach-Kassette, nur ueber eine groessere Bandbreite).
 */
internal val VIRTUAL_GEAR_RATIOS: List<Double> = List(VIRTUAL_GEAR_COUNT) { i ->
    MIN_VIRTUAL_RATIO * (MAX_VIRTUAL_RATIO / MIN_VIRTUAL_RATIO).pow(i / (VIRTUAL_GEAR_COUNT - 1.0))
}

/** Gang [index] (1-basiert, geklemmt) als [GearState]. */
internal fun gearState(index: Int): GearState {
    val i = index.coerceIn(1, VIRTUAL_GEAR_COUNT)
    return GearState(i, VIRTUAL_GEAR_COUNT, VIRTUAL_GEAR_RATIOS[i - 1])
}

/**
 * Startgang: der Gang, dessen Uebersetzung der echten am naechsten liegt
 * (im logarithmischen Mass, denn die Stufung ist geometrisch). Bei 40/14
 * ≈ 2,857 ist das Gang 16 (2,75); Gang 17 waere 3,0 (genauer 2,9992).
 */
internal fun startGearIndex(realGearRatio: Double): Int {
    val real = realGearRatio.takeIf { it.isFinite() && it > 0 } ?: RiderSetup.DEFAULT_REAL_GEAR_RATIO
    return VIRTUAL_GEAR_RATIOS.indices.minBy { abs(ln(VIRTUAL_GEAR_RATIOS[it] / real)) } + 1
}

/** Ein Gang weiter, an den Enden geklemmt. */
internal fun shiftedGearIndex(index: Int, event: ShifterEvent): Int = when (event) {
    ShifterEvent.UP -> index + 1
    ShifterEvent.DOWN -> index - 1
}.coerceIn(1, VIRTUAL_GEAR_COUNT)

/**
 * Die Simulationsvorgabe fuer den Trainer nach der Herleitung im Dateikopf.
 *
 * @param gradePct Steigung der Strecke an der Stelle (ohne Schwierigkeit).
 * @param crr wirksamer Rollwiderstand der Stelle (`effectiveCrr`).
 * @param cwKgPerM `½·ρ·CdA` der virtuellen Fahrt an der Stelle.
 * @param headwindMps Gegenwind in Fahrtrichtung (negativ = Rueckenwind).
 * @param gearRatio virtuelle Uebersetzung G_v.
 */
internal fun simulationCommand(
    gradePct: Double,
    crr: Double,
    cwKgPerM: Double,
    headwindMps: Double,
    gearRatio: Double,
    rider: RiderSetup,
): TrainerCommand.SetSimulation {
    val real = rider.realGearRatio.takeIf { it.isFinite() && it > 0 } ?: RiderSetup.DEFAULT_REAL_GEAR_RATIO
    val k = (gearRatio.takeIf { it.isFinite() && it > 0 } ?: real) / real
    val scale = rider.trainerScale.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val difficulty = if (rider.difficulty.isFinite()) rider.difficulty.coerceIn(0.0, 1.0) else 1.0
    val grade = if (gradePct.isFinite()) gradePct else 0.0
    val c = if (crr.isFinite() && crr > 0) crr else 0.0
    val cw = if (cwKgPerM.isFinite() && cwKgPerM > 0) cwKgPerM else 0.0
    val wind = if (headwindMps.isFinite()) headwindMps else 0.0

    val thetaD = atan(difficulty * grade / 100.0)
    val crrS = c.coerceIn(0.0, SIM_MAX_CRR)
    val gradeS = 100.0 * (k * scale * (sin(thetaD) + c * cos(thetaD)) - crrS)
    return TrainerCommand.SetSimulation(
        windMps = (wind / k).coerceIn(-SIM_MAX_ABS_WIND_MPS, SIM_MAX_ABS_WIND_MPS),
        gradePct = gradeS.coerceIn(-SIM_MAX_ABS_GRADE_PCT, SIM_MAX_ABS_GRADE_PCT),
        crr = crrS,
        cwKgPerM = (cw * k * k * k).coerceIn(0.0, SIM_MAX_CW),
    )
}
