package de.trailscape.app.ui.training

import de.trailscape.app.ui.formatDecimalDe
import de.trailscape.core.HrvAssessment
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Kleine, rein textuelle Formatierungshelfer — Port der gleichnamigen private
 * Methoden aus `lib/screens/training_screen.dart` (`_signed`, `_hrvTrendText`)
 * und der lokalen `.toStringAsFixed(...).replaceAll('.', ',')`-Stellen dort.
 *
 * Eigene, winzige Kopien statt eines Aufrufs von `:core`s `toStringAsFixed`
 * bzw. `dartRound`: beide sind dort `internal` und damit ausserhalb des
 * Moduls nicht sichtbar (siehe [de.trailscape.app.ui.localOfEpochMs] fuer das
 * gleiche, bereits etablierte Muster).
 */

/**
 * Vorzeichenbehaftete, deutsch formatierte Ganzzahl (Dart: `_signed`) — z. B.
 * fuer TSB oder die Rampenrate.
 */
fun formatSigned(value: Double): String {
    val rounded = value.roundToInt()
    return when {
        rounded > 0 -> "+$rounded"
        rounded < 0 -> "−${-rounded}"
        else -> "±0"
    }
}

/**
 * Deutsch formatierte Nachkommazahl (Punkt → Komma), kaufmaennisch gerundet.
 *
 * Nur noch ein Name fuer [formatDecimalDe] — dieselbe Formatierung brauchen
 * inzwischen auch Karten- und Touren-Tab, deshalb liegt die Rechnung in
 * `ui/UiFormat.kt`. Der alte Name bleibt, weil ihn die Trainingskarten an
 * einem Dutzend Stellen benutzen.
 */
fun germanFixed(value: Double, digits: Int): String = formatDecimalDe(value, digits)

/** Tendenz der HRV gegenueber der eigenen Baseline, in Prozent. */
fun hrvTrendText(hrv: HrvAssessment): String {
    val deviation = hrv.deviationPercent ?: return ""
    val baseline = hrv.baselineRmssd ?: return ""
    val rounded = deviation.roundToInt()
    val sign = if (rounded > 0) "+" else if (rounded < 0) "−" else "±"
    return "Tendenz: $sign${abs(rounded)} % gegenüber deinem Normalwert " +
        "(${baseline.roundToInt()} ms)."
}
