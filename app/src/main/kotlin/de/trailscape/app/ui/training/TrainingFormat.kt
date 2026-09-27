package de.trailscape.app.ui.training

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.HrvAssessment
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Kleine, rein textuelle Formatierungshelfer — Port der gleichnamigen private
 * Methoden aus `lib/screens/training_screen.dart` (`_signed`, `_hrvTrendText`).
 * Nachkommazahlen formatiert der Trainings-Tab ueber `LocalAppFormats`.
 *
 * Eigene, winzige Kopien statt eines Aufrufs von `:core`s `toStringAsFixed`
 * bzw. `dartRound`: beide sind dort `internal` und damit ausserhalb des
 * Moduls nicht sichtbar (siehe [de.trailscape.app.ui.localOfEpochMs] fuer das
 * gleiche, bereits etablierte Muster).
 */

/**
 * Vorzeichenbehaftete Ganzzahl, sprachneutral (Dart: `_signed`) — z. B.
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
 * Tendenz der HRV gegenueber der eigenen Baseline, in Prozent — `null`, wenn
 * Abweichung oder Baseline fehlen (dann steht nur der Satz aus `:core`).
 */
fun hrvTrendText(hrv: HrvAssessment): UiText? {
    val deviation = hrv.deviationPercent ?: return null
    val baseline = hrv.baselineRmssd ?: return null
    val rounded = deviation.roundToInt()
    val sign = if (rounded > 0) "+" else if (rounded < 0) "−" else "±"
    return UiText.Res(R.string.training_format_hrv_trend, listOf("$sign${abs(rounded)}", baseline.roundToInt()))
}
