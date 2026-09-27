package de.trailscape.core

import kotlin.math.roundToInt

/**
 * Mittelwerte der je Punkt gespeicherten Sensorwerte einer Tour — fuer
 * „Ø Leistung" und „Ø Trittfrequenz" in den Tourdetails.
 *
 * Bewusst nicht in `BleSensorLogik.kt`: Die Werte koennen ebenso aus einem
 * GPX-Import stammen ([TrackPoint.power]/[TrackPoint.cad]); woher sie kamen,
 * spielt fuer das Mittel keine Rolle.
 */
data class SensorMittelwerte(val avgPowerW: Int?, val avgCadenceRpm: Int?)

/**
 * Zeitgewichtetes Mittel ueber die Punktabstaende; Abstaende ueber
 * [maxHrGapS] (30 s — Pause, Tunnel, Aufzeichnungsluecke) zaehlen nicht, wie
 * bei der Herzfrequenz. Jeder Abstand traegt den Wert des Punkts an seinem
 * Ende — bei der Leistung ist das genau das Mittel dieses Intervalls.
 *
 * Die Leistung schliesst Nullen ein (Rollen gehoert zum Mittel, sonst waere
 * die Energie falsch), die Trittfrequenz schliesst sie aus (branchenueblich:
 * Ø Trittfrequenz meint die Frequenz, *wenn* getreten wird).
 */
fun sensorMittelwerte(points: List<TrackPoint>): SensorMittelwerte {
    val timed = points.filter { it.time != null }.sortedBy { it.time!! }
    var powerSum = 0.0
    var powerWeight = 0.0
    var cadSum = 0.0
    var cadWeight = 0.0
    for (i in 1 until timed.size) {
        val dt = (timed[i].time!! - timed[i - 1].time!!) / 1000.0
        if (dt <= 0 || dt > maxHrGapS) continue
        val p = timed[i].power
        if (p != null && p >= 0) {
            powerSum += p * dt
            powerWeight += dt
        }
        val c = timed[i].cad
        if (c != null && c > 0) {
            cadSum += c * dt
            cadWeight += dt
        }
    }
    return SensorMittelwerte(
        avgPowerW = if (powerWeight > 0) (powerSum / powerWeight).roundToInt() else null,
        avgCadenceRpm = if (cadWeight > 0) (cadSum / cadWeight).roundToInt() else null,
    )
}
